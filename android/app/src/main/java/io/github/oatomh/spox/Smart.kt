package io.github.oatomh.spox

import android.content.Context
import io.github.oatomh.spox.engine.Engine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * SpoX shuffle (same as SpoX on Windows). SpoX decides the order itself instead of Spotify.
 *
 * How it works:
 *  - The "deck" is the order: songs already played (kept, so going back works) and about 100
 *    songs ahead, fully planned: your list's songs shuffled properly (artists spread out, every
 *    song once per round) with new songs mixed in by the "new songs" share. Up next shows exactly
 *    this, and it only ever grows at the bottom: nothing already shown is swapped.
 *  - When the list has been played through, a fresh shuffled round follows (never-ending), avoiding
 *    the songs you just heard.
 *  - New songs are found like a radio: songs by artists similar to the song at the end of the
 *    plan, plus Spotify's own autoplay picks.
 *  - Spotify is handed a window of the deck (20 songs back, up to 80 ahead) as its playing list,
 *    so Spotify's own next / previous work normally (also from headphones, the lock screen, a
 *    car, another device). Before the window runs out SpoX hands over the next one, at a song change.
 *  - All of it is saved, so closing SpoX (or the phone restarting) carries on where you were.
 *
 * With shuffle off the same deck plays a list in your sort order (no new songs then).
 */
object Smart {
    class Entry(val uri: String, val title: String, val artist: String, val kind: String) {
        val isNew get() = kind == "new"
    }

    /** Songs planned ahead (Up next shows this many). Planning tops it up when it gets lower. */
    private const val AHEAD = 100
    /** Songs kept behind the one playing (for going back). */
    private const val BEHIND = 100
    /** How much of the window Spotify gets before / after the song playing. */
    private const val WIN_BACK = 20
    private const val WIN_AHEAD = 80
    /** Hand over the next window when fewer songs than this are left in it. */
    private const val WIN_LOW = 25

    /** SpoX is in charge of what plays next. */
    @Volatile var on = false
        private set
    /** true: SpoX shuffle; false: your list in your sort order. */
    @Volatile var shuffled = true
        private set
    @Volatile var listUri = ""
        private set
    @Volatile var listName = ""
        private set
    /** Songs SpoX brought in (Up next marks them). */
    val newUris: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    @Volatile var holdUntil = 0L
    /** Shown while the list loads / new songs are being found. */
    @Volatile var busy = ""
        private set

    private lateinit var app: Context
    private val engine get() = Engine.get(app)
    private val lock = Any()
    private val deck = ArrayList<Entry>()
    @Volatile private var index = 0
    /** Your list's songs still to come in this round (the next round is shuffled when it's empty). */
    private val bag = ArrayDeque<Web.Item>()
    /** new songs still owed after the last list song planned (the share's running total) */
    private var needNew = 0
    private var acc = 0.0
    /** exactly what Spotify was given as its playing list */
    private var sent: List<String> = emptyList()
    /** the window has to be handed over again (songs taken out, share changed...) */
    private var dirty = false
    private var startedAt = 0L
    private var lastTrack = ""
    /** songs you queued (Spotify plays them first, then carries on with the deck) */
    private val queued: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    /** songs taken out that Spotify may still play from the old window: skipped if they come */
    private val removed: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    private val pool = java.util.concurrent.ConcurrentLinkedDeque<Web.Item>()
    private val used: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    private var seeds: List<Web.Item> = emptyList()
    private val finding = AtomicInteger(0)
    private val saver = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main by lazy { android.os.Handler(app.mainLooper) }

    fun ratio(ctx: Context) = ctx.getSharedPreferences("spox", 0).getInt("smart_ratio", 50).let { if (it in listOf(0, 25, 50, 75, 100)) it else 50 }

    /**
     * The shuffle button: "off" (in order), "shuffle" (fair shuffle, only your list) or "spox"
     * (fair shuffle + new songs). A setting that sticks: it doesn't reset when you play something
     * else, it's simply how the next list plays.
     */
    fun mode(ctx: Context) = ctx.getSharedPreferences("spox", 0).getString("shuffle_mode", "off").let { if (it == "shuffle" || it == "spox") it else "off" }
    fun setMode(ctx: Context, m: String) { ctx.getSharedPreferences("spox", 0).edit().putString("shuffle_mode", m).apply(); changed() }
    fun nextMode(m: String) = when (m) { "off" -> "shuffle"; "shuffle" -> "spox"; else -> "off" }

    /** The new-songs share in use: only in SpoX mode. */
    private fun share() = if (shuffled && mode(app) == "spox") ratio(app) else 0

    fun setRatio(ctx: Context, r: Int) {
        val old = ratio(ctx)
        ctx.getSharedPreferences("spox", 0).edit().putInt("smart_ratio", r).apply()
        if (on && shuffled && mode(ctx) == "spox" && r != old) thread {
            // re-plan what's ahead: Up next changes once, to the new mix
            synchronized(lock) {
                val ahead = deck.subList(index + 1, deck.size).toList()
                while (deck.size > index + 1) deck.removeAt(deck.size - 1)
                // the list songs go back into the round, in the same order
                ahead.filter { it.kind == "list" }.reversed().forEach { e -> seeds.firstOrNull { it.uri == e.uri }?.let { bag.addFirst(it) } }
                needNew = 0; acc = 0.0; dirty = true
            }
            work()
        }
        changed()
    }

    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private fun changed() { listeners.forEach { it() } }

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        restore()
        engine.now.listeners += { if (on) runCatching { onChange() } }
        main.postDelayed(ticker, 3000)
    }

    private fun toast(t: String) = PlayerView.say(app, t)

    // every few seconds: keep planning ahead and finding new songs
    private val ticker = object : Runnable {
        override fun run() {
            if (on) thread(name = "spox-shuffle-tick") { runCatching { work() } }
            main.postDelayed(this, 3000)
        }
    }

    /** Plan ahead, look for more new songs if they're short, save, tell the screen. */
    private fun work() {
        val hungry = plan()
        if (share() > 0 && (hungry || pool.size < 20)) discover()
        save()
        changed()
    }

    // ---- starting ----------------------------------------------------------------------------

    /** The list playing now, as something SpoX can load. */
    fun playingList(): Web.Item? {
        val n = engine.now
        val u = n.contextUri
        return when {
            on && listUri.isNotEmpty() -> listItem(listUri, listName)
            u.startsWith("spotify:playlist:") || u.startsWith("spotify:album:") -> listItem(u, n.contextName)
            u.contains(":collection") -> listItem("liked", "Liked Songs")
            else -> null
        }
    }

    private fun listItem(uri: String, name: String) = when {
        uri == "liked" -> Web.Item("liked", name.ifEmpty { "Liked Songs" }, "", "liked")
        uri.startsWith("spotify:album:") -> Web.Item(uri, name, "", "album", uri.substringAfterLast(':'))
        else -> Web.Item(uri, name, "", "playlist", uri.substringAfterLast(':'))
    }

    /**
     * Start SpoX shuffle (or, with [shuffle] false, your sort order) on a list.
     * [songs]: the list's songs if already loaded (in the order to play when not shuffling).
     * [first]: the song to start with (a song you tapped). [positionMs]: carry on mid-song.
     */
    fun start(ctx: Context, list: Web.Item, songs: List<Web.Item>? = null, first: String? = null, shuffle: Boolean = true, positionMs: Long = 0): Boolean {
        init(ctx)
        if (!Web.signedIn) { toast("SpoX shuffle needs the Spotify sign-in (Settings)"); return false }
        if (!engine.isLive) { toast("Turn on SpoX Engine first (Settings)"); return false }
        // keep what's already been played (going back still works after switching)
        synchronized(lock) {
            on = true; shuffled = shuffle
            listUri = list.uri; listName = list.title
            while (deck.size > index + 1) deck.removeAt(deck.size - 1)
            bag.clear(); queued.clear(); removed.clear()
            newUris.clear(); pool.clear(); acc = 0.0; needNew = 0
            lastTrack = ""
        }
        busy = "Loading ${list.title.ifEmpty { "the list" }}..."
        changed()
        if (list.uri.startsWith("spotify:")) Web.loadKnown(list.uri)
        thread(name = "spox-shuffle") {
            val all = songs ?: Web.tracks(list)
            // merge duplicates (same song on another album) and drop things Spotify can't play
            val seen = HashSet<String>()
            val clean = all.filter { it.kind == "track" && it.uri.startsWith("spotify:track:") && seen.add(Web.key(it.title, it.sub)) }
            if (clean.isEmpty()) { busy = ""; stop(); toast("Nothing to play in ${list.title}"); return@thread }
            seeds = clean
            used.clear()
            clean.forEach { used += Web.key(it.title, it.sub) }
            val r = if (shuffle && mode(ctx) == "spox") ratio(ctx) else 0
            var order: List<Web.Item> = if (shuffle) spread(clean) else clean
            var firstItem: Web.Item? = null
            first?.let { f ->
                order.firstOrNull { it.uri == f }?.let { t ->
                    firstItem = t
                    // shuffled: that song first, then the mix; in order: from that song on
                    order = if (shuffle) order.filter { it !== t } else order.dropWhile { it !== t }.drop(1)
                }
            }
            synchronized(lock) { bag.addAll(order) }
            // new songs found for this list before: ready at once (more are found in the background)
            loadFound(list.uri)
            warmUntil = if (r > 0 && pool.size < 10) System.currentTimeMillis() + 10_000 else 0L
            val begin = synchronized(lock) {
                // the start: the song you tapped, else the first of the plan (plays at once)
                val t = firstItem ?: (if (r == 100) pick("") else null) ?: bag.removeFirstOrNull()
                if (t != null) {
                    deck.add(Entry(t.uri, t.title, t.sub, if (t.uri in newUris) "new" else "list"))
                    index = deck.size - 1
                }
                t != null
            }
            busy = ""
            if (!begin) { stop(); toast("No songs to play"); return@thread }
            plan()
            val at = if (first != null && deck.getOrNull(index)?.uri == first) positionMs else 0L
            sendWindow(at)
            work()
            if (shuffle) toast(if (r == 0) "Shuffle on" else "SpoX shuffle on · $r% new songs")
        }
        return true
    }

    fun stop() {
        synchronized(lock) { on = false; deck.clear(); index = 0; bag.clear(); sent = emptyList(); queued.clear() }
        pool.clear()
        busy = ""
        save()
        changed()
    }

    /** Nothing is playing (e.g. SpoX Engine just started): carry on with the saved order. */
    fun resume(positionMs: Long = 0): Boolean {
        if (!on || !Web.signedIn || synchronized(lock) { deck.isEmpty() }) return false
        lastTrack = ""
        thread { sendWindow(positionMs) }
        return true
    }

    /**
     * Gives Spotify the window around the song at [index] as its playing list (20 back, 80 ahead)
     * and starts that song at [positionMs].
     */
    private fun sendWindow(positionMs: Long) {
        val win = synchronized(lock) {
            if (deck.isEmpty()) return
            index = index.coerceIn(0, deck.size - 1)
            val from = maxOf(0, index - WIN_BACK)
            val to = minOf(deck.size, index + 1 + WIN_AHEAD)
            val list = deck.subList(from, to).map { it.uri }
            sent = list
            dirty = false
            startedAt = System.currentTimeMillis()
            list to (index - from)
        }
        if (!Web.playUris(win.first, positionMs, win.second)) toast("SpoX Phone isn't ready yet: press play again in a moment")
        engine.command("get_queue", mapOf("limit" to 0))
        save()
    }

    /** Jump to a song in Up next: one jump, no skipping through the ones in between. */
    fun jumpTo(uri: String): Boolean {
        synchronized(lock) {
            val i = (index + 1 until deck.size).firstOrNull { deck[it].uri == uri } ?: return false
            index = i
        }
        holdUntil = System.currentTimeMillis() + 4000
        lastTrack = uri
        thread { sendWindow(0); work() }
        return true
    }

    // ---- Up next ------------------------------------------------------------------------------

    /** What's coming, in SpoX's order (songs you queued are shown first by the player). */
    fun upcoming(): List<Entry> = synchronized(lock) { if (index + 1 < deck.size) deck.subList(index + 1, deck.size).toList() else emptyList() }

    /** The song SpoX shuffle is at (shown, paused, before SpoX Engine has anything loaded). */
    fun current(): Entry? = synchronized(lock) { if (on) deck.getOrNull(index) else null }

    /** Songs you queued, still to play (Up next shows them first). */
    fun queuedUris(): Set<String> = queued.toSet()

    /** Take songs out of what's coming. */
    fun remove(uris: Set<String>) {
        synchronized(lock) {
            for (j in deck.indices.reversed()) {
                if (j <= index || deck[j].uri !in uris) continue
                if (deck[j].uri in sent) dirty = true
                deck.removeAt(j)
            }
            // Spotify may still have them in its window: skipped if they come before the next hand-over
            removed += uris.filter { it in sent }
            queued.removeAll(uris)
        }
        thread { runCatching { work() } }
    }

    /** Play songs next: Spotify's queue plays them right after this song, then the order carries on. */
    fun playNext(items: List<Entry>) {
        if (items.isEmpty()) return
        val uris = items.map { it.uri }.toSet()
        synchronized(lock) {
            // they leave their old spot in the order (no playing twice)
            for (j in deck.indices.reversed()) if (j > index && deck[j].uri in uris) { deck.removeAt(j); dirty = true }
            removed += uris.filter { it in sent }
            queued += uris
        }
        items.forEach { engine.command("add_to_queue", mapOf("uri" to it.uri)) }
        thread { runCatching { work() } }
    }

    /** A song you added to the queue (library, a link): it plays next, then the order carries on. */
    fun noteQueued(uri: String, title: String, artist: String) {
        if (!on) return
        queued += uri
        changed()
    }

    // ---- following what plays --------------------------------------------------------------

    private fun onChange() {
        harvest()
        val n = engine.now
        val cur = n.track?.uri ?: return
        if (cur == lastTrack) return
        val now = System.currentTimeMillis()
        lastTrack = cur
        if (Player.isSkipped(cur)) return
        // a song you queued: Spotify plays it in between, the order waits
        if (queued.remove(cur)) { changed(); return }
        // taken out of Up next but still in Spotify's old window
        if (removed.remove(cur)) { engine.command("skip_next"); return }
        val pos = synchronized(lock) {
            // nearest match around where we are (the same song can be in the deck twice)
            val lo = maxOf(0, index - WIN_BACK - 5)
            val hi = minOf(deck.size, index + WIN_AHEAD + 5)
            (lo until hi).filter { deck[it].uri == cur }.minByOrNull { kotlin.math.abs(it - index - 1) } ?: -1
        }
        if (pos < 0) {
            // just started / jumping: Spotify may still report the old song for a moment
            if (now - startedAt < 6000 || now < holdUntil) return
            // you played something else: SpoX shuffle hands back to Spotify
            stop()
            return
        }
        val handOver = synchronized(lock) {
            index = pos
            // trim far history (keeps going back possible, without growing forever)
            if (index > BEHIND + 50) {
                val cut = index - BEHIND
                repeat(cut) { deck.removeAt(0) }
                index -= cut
            }
            val at = sent.indexOf(cur)
            val left = if (at < 0) 0 else sent.size - 1 - at
            (dirty || left < WIN_LOW) && deck.size > index + 1 + left
        }
        if (handOver) {
            // a new song just started: hand over the next window now (from this same spot)
            val at = n.position()
            thread { sendWindow(at); work() }
        } else thread { runCatching { work() } }
    }

    /**
     * Fills the order up to [AHEAD] songs ahead: the next list songs of this round (a new shuffled
     * round when it's done), with new songs between them by the share. Returns true while new
     * songs are owed but none are found yet (planning waits for them, unless it runs low).
     */
    private fun plan(): Boolean = synchronized(lock) {
        val r = share()
        var hungry = false
        while (deck.size - index - 1 < AHEAD) {
            if (r >= 100 || needNew > 0) {
                val t = pick(deck.lastOrNull()?.artist.orEmpty())
                if (t != null) {
                    deck += Entry(t.uri, t.title, t.sub, "new")
                    if (needNew > 0) needNew--
                    continue
                }
                hungry = true
                // the very first seconds of a list SpoX has no new songs for yet: plan a few
                // songs, wait for the first new ones, then fill Up next in one go
                if (System.currentTimeMillis() < warmUntil && deck.size - index - 1 >= 5) break
                // no new song ready: carry on with the list, never wait (that was the slowness)
                needNew = 0
                if (r >= 100) { if (bag.isEmpty() && seeds.isEmpty()) break }
            }
            if (bag.isEmpty()) {
                // played the whole list: a new round, shuffled, without the songs you just heard
                if (!shuffled || seeds.isEmpty()) break
                val recent = deck.takeLast(minOf(deck.size, seeds.size / 3)).map { it.uri }.toSet()
                val fresh = spread(seeds)
                bag.addAll(fresh.filter { it.uri !in recent } + fresh.filter { it.uri in recent })
            }
            val t = bag.removeFirstOrNull() ?: break
            deck += Entry(t.uri, t.title, t.sub, "list")
            if (r in 1..99) {
                acc += r / (100.0 - r)
                while (acc >= 1.0) { needNew++; acc -= 1.0 }
            }
        }
        // Spotify has a much shorter list than what's planned now: hand it over at the next song change
        if (sent.isNotEmpty() && deck.size - index - 1 > sent.size - 1 - sent.indexOf(deck.getOrNull(index)?.uri) + 10 && sent.size < WIN_BACK + WIN_AHEAD) dirty = true
        hungry
    }

    // ---- new songs ---------------------------------------------------------------------------

    private fun known(title: String, artist: String) = used.contains(Web.key(title, artist)) || Web.knows(title, artist) == true

    /** Spotify's own autoplay picks in the queue: also used as new songs. */
    private fun harvest() {
        if (share() == 0) return
        for (t in engine.now.queue) {
            if (!(t.provider.contains("autoplay") || t.provider.contains("recommend"))) continue
            if (!known(t.title, t.artist) && pool.none { it.uri == t.uri }) pool.addFirst(Web.Item(t.uri, t.title, t.artist, "track"))
        }
    }

    private fun first(a: String) = a.split(", ").firstOrNull().orEmpty().lowercase()

    /** A new song, not by the artist next to it in the order. */
    private fun pick(next: String): Web.Item? {
        val avoid = first(next)
        var tries = pool.size
        while (tries-- > 0) {
            val t = pool.pollFirst() ?: break
            if (known(t.title, t.sub)) continue
            if (first(t.sub) == avoid && pool.isNotEmpty()) { pool.addLast(t); continue }
            used += Web.key(t.title, t.sub)
            newUris += t.uri
            return t
        }
        return null
    }

    private fun json(url: String): JSONObject? = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10000
        JSONObject(c.inputStream.bufferedReader().readText())
    }.getOrNull()

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** Artists similar to [artist] (Deezer's free "related artists", no Spotify requests). */
    private fun similarArtists(artist: String): List<String> {
        val id = json("https://api.deezer.com/search/artist?limit=1&q=${enc(artist)}")?.optJSONArray("data")?.optJSONObject(0)?.optLong("id") ?: return emptyList()
        val rel = json("https://api.deezer.com/artist/$id/related?limit=25")?.optJSONArray("data") ?: return emptyList()
        return (0 until rel.length()).mapNotNull { rel.optJSONObject(it)?.optString("name") }.filter { it.isNotEmpty() }.shuffled()
    }

    /** Like a radio: mostly the last list song of the plan (where new songs go next), sometimes what's playing or any list song. */
    private fun seedArtist(): String = synchronized(lock) {
        val roll = Math.random()
        val a = when {
            roll < 0.6 -> deck.lastOrNull { it.kind == "list" }?.artist
            roll < 0.8 -> engine.now.track?.artist
            else -> null
        } ?: seeds.randomOrNull()?.sub ?: engine.now.track?.artist.orEmpty()
        a.split(", ").firstOrNull().orEmpty()
    }

    @Volatile private var warmUntil = 0L

    /** One worker looks for new songs (Spotify requests go through its slow background lane). */
    private fun discover() {
        if (!finding.compareAndSet(0, 1)) return
        thread(name = "spox-discover") {
            try { discoverNow(null) } finally { finding.set(0) }
            if (on && pool.isNotEmpty()) runCatching {
                val wasWarm = warmUntil > 0
                if (pool.size >= 6) warmUntil = 0
                plan()
                // the first new songs arrived: hand Spotify the full plan at the next song change
                if (wasWarm && warmUntil == 0L) synchronized(lock) { dirty = true }
                save(); changed()
            }
        }
    }

    /**
     * Finds new songs: three artists similar to the seed (Deezer), then ONE Spotify search per
     * artist that brings ten of their songs; up to three you don't know are kept per artist.
     * (Before, every single song cost its own Spotify search: that ran into Spotify's limit.)
     */
    private fun discoverNow(seed: String?) {
        val artist = seed?.split(", ")?.firstOrNull() ?: seedArtist()
        if (artist.isEmpty()) return
        val found = ArrayList<Web.Item>()
        for (a in similarArtists(artist).take(3).ifEmpty { listOf(artist) }) {
            val r = Web.getBg("/search?type=track&limit=10&q=" + enc("artist:\"$a\""))?.optJSONObject("tracks")?.optJSONArray("items") ?: continue
            val songs = (0 until r.length()).mapNotNull { Web.track(r.optJSONObject(it)) }
                .filter { it.sub.split(", ").first().equals(a, true) && !known(it.title, it.sub) && pool.none { p -> p.uri == it.uri } && it.uri !in newUris }
                .shuffled().take(3)
            pool.addAll(songs)
            found += songs
        }
        if (found.isNotEmpty()) saveFound(found)
    }

    // new songs found for each list, kept on the phone: starting that list again is instant
    private fun foundFile(list: String) = File(app.filesDir, "found/" + list.replace(Regex("[^A-Za-z0-9]"), "_") + ".json")

    private fun loadFound(list: String) {
        val a = runCatching { JSONArray(foundFile(list).readText()) }.getOrNull() ?: return
        val items = (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map { item(it) }.shuffled()
        for (t in items) if (!known(t.title, t.sub) && pool.none { it.uri == t.uri }) pool.addLast(t)
    }

    private fun saveFound(add: List<Web.Item>) {
        val list = listUri.ifEmpty { return }
        val f = foundFile(list)
        saver.execute {
            runCatching {
                val old = runCatching { JSONArray(f.readText()) }.getOrNull()?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map { item(it) } }.orEmpty()
                val all = (old + add).distinctBy { it.uri }.takeLast(400)
                f.parentFile?.mkdirs()
                f.writeText(JSONArray().apply { all.forEach { put(JSONArray().put(it.uri).put(it.title).put(it.sub)) } }.toString())
            }
        }
    }

    // ---- the shuffle itself ------------------------------------------------------------------

    /**
     * Shuffle that spreads each artist out: every artist's songs are placed at even gaps across
     * the list with a random start, then everything is merged by position (like a good DJ would).
     */
    fun spread(songs: List<Web.Item>): List<Web.Item> {
        val rnd = java.util.Random()
        val groups = songs.groupBy { it.sub.split(", ").firstOrNull().orEmpty().lowercase() }
        val placed = ArrayList<Pair<Double, Web.Item>>(songs.size)
        for ((_, g) in groups) {
            val list = g.shuffled(rnd)
            val gap = 1.0 / list.size
            val startAt = rnd.nextDouble() * gap
            list.forEachIndexed { i, t ->
                // a little wobble so it doesn't feel mechanical
                placed += (startAt + i * gap + (rnd.nextDouble() - 0.5) * gap * 0.3) to t
            }
        }
        return placed.sortedBy { it.first }.map { it.second }
    }

    // ---- saved between runs ----------------------------------------------------------------------

    private val file get() = File(app.filesDir, "spox_shuffle.json")

    private fun item(e: JSONArray) = Web.Item(e.optString(0), e.optString(1), e.optString(2), "track")

    private fun save() {
        if (!::app.isInitialized) return
        val snap = synchronized(lock) {
            JSONObject().put("v", 3).put("on", on).put("shuffled", shuffled).put("list", listUri).put("name", listName).put("index", index)
                .put("need", needNew).put("acc", acc)
                .put("deck", JSONArray().apply { deck.forEach { put(JSONArray().put(it.uri).put(it.title).put(it.artist).put(it.kind)) } })
                .put("bag", JSONArray().apply { bag.forEach { put(JSONArray().put(it.uri).put(it.title).put(it.sub)) } })
                .put("sent", JSONArray(sent))
                .put("seeds", JSONArray().apply { seeds.take(2000).forEach { put(JSONArray().put(it.uri).put(it.title).put(it.sub)) } })
                .toString()
        }
        saver.execute { runCatching { val tmp = File(file.path + ".tmp"); tmp.writeText(snap); tmp.renameTo(file) } }
    }

    private fun restore() {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        if (!o.optBoolean("on") || o.optInt("v") < 3) return
        synchronized(lock) {
            deck.clear()
            o.optJSONArray("deck")?.let { a ->
                for (i in 0 until a.length()) a.optJSONArray(i)?.let { e -> deck += Entry(e.optString(0), e.optString(1), e.optString(2), e.optString(3, "list")) }
            }
            o.optJSONArray("bag")?.let { a -> for (i in 0 until a.length()) a.optJSONArray(i)?.let { bag += item(it) } }
            index = o.optInt("index").coerceIn(0, (deck.size - 1).coerceAtLeast(0))
            needNew = o.optInt("need"); acc = o.optDouble("acc", 0.0)
            sent = o.optJSONArray("sent")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            on = deck.isNotEmpty()
            shuffled = o.optBoolean("shuffled", true)
            listUri = o.optString("list"); listName = o.optString("name")
            deck.filter { it.isNew }.forEach { newUris += it.uri }
            deck.forEach { used += Web.key(it.title, it.artist) }
        }
        seeds = o.optJSONArray("seeds")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map { item(it) } }.orEmpty()
        seeds.forEach { used += Web.key(it.title, it.sub) }
    }
}
