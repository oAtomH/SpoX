package io.github.oatomh.spox

import android.content.Context
import io.github.oatomh.spox.engine.Engine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * SpoX shuffle (same as SpoX on Windows). SpoX decides the order itself instead of Spotify:
 *
 *  - Your list is shuffled properly: every song once before any repeats, duplicates merged, and the
 *    same artist spread out instead of bunched together.
 *  - New songs (not in your Liked Songs, this list or recent plays) are planned into the order AHEAD
 *    of time, by the "new songs" share (0, 25, 50, 75 or 100%), so Up next shows a long, fixed list
 *    with the new songs already in it. Going back and forth never swaps them for others.
 *  - New songs are found like a radio: songs by artists similar to the song next to them in the
 *    order (and Spotify's own autoplay picks).
 *  - Spotify is only handed the next few songs at a time (its queue), so the rest stays SpoX's to
 *    plan and change.
 *  - The order is saved, so closing SpoX (or the phone restarting) carries on where you were.
 *
 * The same "deck" also plays a list in your own sort order with shuffle off (no new songs then).
 */
object Smart {
    class Entry(val uri: String, val title: String, val artist: String, val kind: String, @Volatile var skip: Boolean = false) {
        val isNew get() = kind == "new"
    }

    /** How many songs Spotify gets ahead of the one playing. */
    private const val AHEAD = 3
    /** How far ahead new songs are planned in. */
    private const val HORIZON = 60

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
    @Volatile var index = 0
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
    /** deck[fedTo] is the first song Spotify hasn't been given yet. */
    private var fedTo = 0
    /** New songs are planned in up to here. */
    private var plannedTo = 0
    /** new songs still owed at plannedTo (the share's running total) */
    private var needNew = 0
    private var acc = 0.0
    private var startedAt = 0L
    private var lastTrack = ""
    private val pool = java.util.concurrent.ConcurrentLinkedDeque<Web.Item>()
    private val used: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    private var seeds: List<Web.Item> = emptyList()
    @Volatile private var fetching = false
    private val saver = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main by lazy { android.os.Handler(app.mainLooper) }

    fun ratio(ctx: Context) = ctx.getSharedPreferences("spox", 0).getInt("smart_ratio", 50).let { if (it in listOf(0, 25, 50, 75, 100)) it else 50 }
    fun setRatio(ctx: Context, r: Int) {
        val old = ratio(ctx)
        ctx.getSharedPreferences("spox", 0).edit().putInt("smart_ratio", r).apply()
        if (on && shuffled && r != old) {
            if ((r == 100) != (old == 100)) restartMix()
            else {
                synchronized(lock) {
                    // re-plan what's not with Spotify yet: take the planned new songs out, plan again
                    for (j in deck.indices.reversed()) if (j >= fedTo && deck[j].isNew) deck.removeAt(j)
                    plannedTo = fedTo; needNew = 0; acc = 0.0
                }
                thread { runCatching { work() } }
            }
        }
        listeners.forEach { it() }
    }

    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private fun changed() { listeners.forEach { it() } }

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        restore()
        engine.now.listeners += { if (on) runCatching { onChange() } }
        main.postDelayed(ticker, 4000)
    }

    private fun toast(t: String) = PlayerView.say(app, t)

    // every few seconds: plan new songs in, keep Spotify a few songs ahead, find more new songs
    private val ticker = object : Runnable {
        override fun run() {
            if (on) thread(name = "spox-shuffle-tick") { runCatching { tick() } }
            main.postDelayed(this, 4000)
        }
    }

    private fun tick() {
        if (!on || !engine.isLive) return
        val n = engine.now
        val t = n.track ?: return
        val left = t.durationMs - n.position()
        // the song is ending and Spotify has nothing after it: carry on even if no new song was found
        work(force = n.playing && left < 25_000)
    }

    /** Plan, hand Spotify what it needs, and look for new songs if more are wanted. */
    private fun work(force: Boolean = false) {
        val hungry = plan()
        feed(force)
        if (shuffled && ratio(app) > 0 && (hungry || pool.size < 6)) discover()
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

    /** Songs Spotify still has queued from SpoX that aren't wanted any more: skip them when they come. */
    private fun dropFed(from: Int) {
        val left = synchronized(lock) { (from until minOf(fedTo, deck.size)).filter { !deck[it].skip }.map { deck[it].uri } }
        if (left.isNotEmpty()) Player.skipQueued(left)
    }

    /**
     * Start SpoX shuffle (or, with [shuffle] false, your sort order) on a list.
     * [songs]: the list's songs if already loaded (in the order to play when not shuffling).
     * [first]: the song to start with (a song you tapped).
     */
    fun start(ctx: Context, list: Web.Item, songs: List<Web.Item>? = null, first: String? = null, shuffle: Boolean = true, positionMs: Long = 0): Boolean {
        init(ctx)
        if (!Web.signedIn) { toast("SpoX shuffle needs the Spotify sign-in (Settings)"); return false }
        if (!engine.isLive) { toast("Turn on SpoX Engine first (Settings)"); return false }
        if (on) dropFed(index + 1)
        synchronized(lock) {
            on = true; shuffled = shuffle
            listUri = list.uri; listName = list.title
            newUris.clear(); used.clear(); pool.clear(); acc = 0.0; needNew = 0
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
            clean.forEach { used += Web.key(it.title, it.sub) }
            val r = ratio(ctx)
            var order: List<Web.Item> = if (shuffle) spread(clean) else clean
            first?.let { f ->
                order.firstOrNull { it.uri == f }?.let { t ->
                    // shuffled: that song first, then the mix; in order: from that song to the end
                    order = if (shuffle) listOf(t) + order.filter { it !== t } else order.dropWhile { it !== t }
                }
            }
            if (shuffle && r == 100) {
                // only new songs: find a few before starting
                busy = "Finding new songs..."
                changed()
                repeat(6) { if (pool.size < 8) discoverNow(seeds.randomOrNull()?.sub) }
                val fresh = generateSequence { pick("") }.take(10).toList()
                if (fresh.isEmpty()) { toast("No new songs found, playing your list"); fill(order.map { Entry(it.uri, it.title, it.sub, "list") }) }
                else fill(fresh.map { Entry(it.uri, it.title, it.sub, "new").also { e -> newUris += e.uri } })
            } else fill(order.map { Entry(it.uri, it.title, it.sub, "list") })
            busy = ""
            // carry on mid-song only if it's the same song that was playing
            val at = synchronized(lock) { if (first != null && deck.firstOrNull()?.uri == first) positionMs else 0L }
            playAt(0, at)
            if (shuffle) toast(if (r == 0) "SpoX shuffle on" else "SpoX shuffle on · $r% new, planned in as they're found")
        }
        return true
    }

    private fun fill(list: List<Entry>) = synchronized(lock) { deck.clear(); deck.addAll(list); index = 0; fedTo = 0; plannedTo = 0 }

    /** The new-songs share crossed 100% (or back): rebuild from the list's songs. */
    private fun restartMix() {
        val list = listItem(listUri, listName)
        val cur = engine.now.track?.uri
        start(app, list, seeds.ifEmpty { null }, cur.takeIf { s -> seeds.any { it.uri == s } }, true, engine.now.position())
    }

    fun stop() {
        if (on) dropFed(index + 1)
        synchronized(lock) { on = false; deck.clear(); index = 0; fedTo = 0; plannedTo = 0 }
        pool.clear()
        busy = ""
        save()
        changed()
    }

    /** Nothing is playing (e.g. SpoX Engine just started): carry on with the saved order. */
    fun resume(positionMs: Long = 0): Boolean {
        if (!on || !Web.signedIn) return false
        val i = synchronized(lock) { if (deck.isEmpty()) return false; index }
        lastTrack = ""
        thread { playAt(i, positionMs) }
        return true
    }

    /** Starts deck[i] right now; the next songs follow through Spotify's queue. */
    private fun playAt(i: Int, positionMs: Long) {
        val uri = synchronized(lock) {
            if (deck.isEmpty()) return
            index = i.coerceIn(0, deck.size - 1)
            fedTo = index + 1
            if (plannedTo < fedTo) { plannedTo = fedTo; needNew = 0 }
            startedAt = System.currentTimeMillis()
            deck[index].uri
        }
        if (!Web.playUris(listOf(uri), positionMs)) toast("Couldn't start SpoX shuffle (is SpoX Phone signed in?)")
        work()
        engine.command("get_queue", mapOf("limit" to 0))
    }

    /** Jump to a song in Up next. */
    fun jumpTo(uri: String): Boolean {
        val target: Pair<Int, Int> = synchronized(lock) {
            val i = (index + 1 until deck.size).firstOrNull { deck[it].uri == uri } ?: return false
            // already with Spotify: skip there through its queue; else start it directly
            i to if (i < fedTo) (index + 1 until i).count { !deck[it].skip } else -1
        }
        val (i, fedSkip) = target
        holdUntil = System.currentTimeMillis() + 3000 + 450L * maxOf(0, fedSkip)
        if (fedSkip >= 0) engine.skipAhead(fedSkip + 1)
        else {
            dropFed(index + 1)
            thread { playAt(i, 0) }
        }
        return true
    }

    // ---- Up next ------------------------------------------------------------------------------

    /** What's coming, from SpoX's own order. */
    fun upcoming(): List<Entry> = synchronized(lock) { if (index + 1 < deck.size) deck.subList(index + 1, deck.size).filter { !it.skip } else emptyList() }

    fun total() = synchronized(lock) { deck.size }

    /** The song SpoX shuffle is at (shown, paused, before SpoX Engine has anything loaded). */
    fun current(): Entry? = synchronized(lock) { if (on) deck.getOrNull(index) else null }

    /** Take songs out of what's coming. Already given to Spotify: skipped when they come up. */
    fun remove(uris: Set<String>) {
        synchronized(lock) {
            for (j in deck.indices.reversed()) {
                if (j <= index || deck[j].uri !in uris) continue
                if (j < fedTo) deck[j].skip = true
                else { deck.removeAt(j); if (j < plannedTo) plannedTo-- }
            }
        }
        thread { runCatching { work() } }
    }

    /** Put songs next, right after the few Spotify already has lined up. */
    fun playNext(items: List<Entry>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            val uris = items.map { it.uri }.toSet()
            for (j in deck.indices.reversed()) {
                if (j <= index || deck[j].uri !in uris) continue
                if (j < fedTo) deck[j].skip = true else { deck.removeAt(j); if (j < plannedTo) plannedTo-- }
            }
            items.forEachIndexed { k, e -> deck.add(fedTo + k, Entry(e.uri, e.title, e.artist, if (e.isNew) "new" else "user")) }
            if (plannedTo >= fedTo) plannedTo += items.size
        }
        thread { runCatching { work() } }
    }

    /** A song you added to Spotify's queue (library, a link): it plays after what SpoX lined up. */
    fun noteQueued(uri: String, title: String, artist: String) {
        if (!on) return
        synchronized(lock) {
            deck.add(fedTo, Entry(uri, title, artist, "user"))
            if (plannedTo >= fedTo) plannedTo++
            fedTo++
        }
        save()
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
        val found: Boolean = synchronized(lock) {
            // nearest match around where we are (the same song can be in the deck twice)
            val pos = (maxOf(0, index - 10) until minOf(deck.size, fedTo + 2)).filter { deck[it].uri == cur }.minByOrNull { kotlin.math.abs(it - index - 1) } ?: -1
            if (pos >= 0) {
                index = pos
                if (deck[pos].skip) { engine.command("skip_next"); return }
            }
            pos >= 0
        }
        if (!found) {
            // just started / jumping: Spotify may still report the old song for a moment
            if (now - startedAt < 6000 || now < holdUntil) return
            val next = synchronized(lock) { deck.getOrNull(index + 1)?.uri }
            // a song queued on another device: SpoX shuffle carries on after it
            if (next != null && n.queue.any { it.uri == next }) return
            // you played something else: SpoX shuffle hands back to Spotify
            stop()
            return
        }
        thread { runCatching { work() } }
    }

    /**
     * Plans new songs into the order, up to [HORIZON] songs ahead (never into what Spotify already
     * has). Returns true while it wants more new songs than it has.
     */
    private fun plan(): Boolean = synchronized(lock) {
        val r = ratio(app)
        if (plannedTo < fedTo) { plannedTo = fedTo; needNew = 0 }
        if (!shuffled || r == 0) { plannedTo = deck.size; return false }
        if (r >= 100) {
            // only new songs: keep the order topped up
            while (deck.size - index < HORIZON) {
                val t = pick(deck.lastOrNull()?.artist.orEmpty()) ?: return true
                deck += Entry(t.uri, t.title, t.sub, "new"); newUris += t.uri
            }
            plannedTo = deck.size
            return false
        }
        while (plannedTo < deck.size && plannedTo < index + HORIZON) {
            if (needNew > 0) {
                val t = pick(deck.getOrNull(plannedTo - 1)?.artist.orEmpty()) ?: return true
                deck.add(plannedTo, Entry(t.uri, t.title, t.sub, "new")); newUris += t.uri
                plannedTo++; needNew--
                continue
            }
            val e = deck[plannedTo++]
            if (e.kind == "list") {
                acc += r / (100.0 - r)
                while (acc >= 1.0) { needNew++; acc -= 1.0 }
            }
        }
        needNew > 0
    }

    /** Gives Spotify the next songs (up to [AHEAD] after the one playing). [force]: the song is
     *  ending and nothing follows it, so carry on even without the new song that was planned. */
    private fun feed(force: Boolean) {
        val send = ArrayList<String>()
        synchronized(lock) {
            var limit = minOf(plannedTo, deck.size)
            if (force && fedTo <= index + 1 && fedTo >= limit && fedTo < deck.size) {
                plannedTo = fedTo + 1; needNew = 0; limit = plannedTo
            }
            while (fedTo < limit && fedTo - index <= AHEAD) {
                if (!deck[fedTo].skip) send += deck[fedTo].uri
                fedTo++
            }
        }
        send.forEach { engine.command("add_to_queue", mapOf("uri" to it)) }
        if (send.isNotEmpty()) engine.command("get_queue", mapOf("limit" to 0))
    }

    // ---- new songs ---------------------------------------------------------------------------

    private fun known(title: String, artist: String) = used.contains(Web.key(title, artist)) || Web.knows(title, artist) == true

    /** Spotify's own autoplay picks in the queue: also used as new songs. */
    private fun harvest() {
        if (!shuffled || ratio(app) == 0) return
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

    /** Popular songs by artists SIMILAR to [artist] (Deezer's free "related artists"), shuffled. */
    private fun similarSongs(artist: String): List<Pair<String, String>> {
        val id = json("https://api.deezer.com/search/artist?limit=1&q=${enc(artist)}")?.optJSONArray("data")?.optJSONObject(0)?.optLong("id") ?: return emptyList()
        val rel = json("https://api.deezer.com/artist/$id/related?limit=25")?.optJSONArray("data") ?: return emptyList()
        val ids = (0 until rel.length()).mapNotNull { rel.optJSONObject(it)?.optLong("id") }.shuffled().take(4)
        val out = mutableListOf<Pair<String, String>>()
        for (r in ids) {
            val top = json("https://api.deezer.com/artist/$r/top?limit=10")?.optJSONArray("data") ?: continue
            // not always the biggest hit: any of an artist's top ten, two at most
            val songs = (0 until top.length()).mapNotNull { top.optJSONObject(it) }.shuffled().take(2)
            for (t in songs) out += t.optString("title") to t.optJSONObject("artist")?.optString("name").orEmpty()
        }
        return out.shuffled()
    }

    /** Like a radio: mostly the song where the next new song goes, sometimes what's playing or any list song. */
    private fun seedArtist(): String = synchronized(lock) {
        val roll = Math.random()
        val a = when {
            roll < 0.6 -> deck.getOrNull(plannedTo - 1)?.takeIf { it.kind == "list" }?.artist
            roll < 0.8 -> engine.now.track?.artist
            else -> null
        } ?: seeds.randomOrNull()?.sub ?: engine.now.track?.artist.orEmpty()
        a.split(", ").firstOrNull().orEmpty()
    }

    private fun discover() {
        if (fetching || pool.size >= 16) return
        thread(name = "spox-discover") {
            discoverNow(null)
            // found some: plan them in straight away
            if (on && pool.isNotEmpty()) runCatching { plan(); feed(false); save(); changed() }
        }
    }

    @Synchronized
    private fun discoverNow(seed: String?) {
        if (fetching) return
        fetching = true
        try {
            val artist = seed?.split(", ")?.firstOrNull() ?: seedArtist()
            if (artist.isEmpty()) return
            var added = 0
            for ((title, a) in similarSongs(artist)) {
                if (added >= 5) break
                if (title.isEmpty() || known(title, a)) continue
                val t = Web.get("/search?type=track&limit=3&q=" + enc("track:$title artist:$a"))?.optJSONObject("tracks")?.optJSONArray("items")?.let { Web.track(it.optJSONObject(0)) } ?: continue
                if (known(t.title, t.sub) || pool.any { it.uri == t.uri }) continue
                pool.addLast(t)
                added++
            }
            if (added > 0) return
            // nothing similar found: other songs by that artist
            val off = (System.currentTimeMillis() / 1000 % 3) * 10
            val r = Web.get("/search?type=track&limit=10&offset=$off&q=" + enc("artist:\"$artist\""))?.optJSONObject("tracks")?.optJSONArray("items") ?: return
            for (i in 0 until r.length()) {
                val t = Web.track(r.optJSONObject(i)) ?: continue
                if (known(t.title, t.sub) || pool.any { it.uri == t.uri }) continue
                pool.addLast(t)
                if (++added >= 3) break
            }
        } finally { fetching = false }
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

    private fun save() {
        if (!::app.isInitialized) return
        val snap = synchronized(lock) {
            JSONObject().put("on", on).put("shuffled", shuffled).put("list", listUri).put("name", listName).put("index", index)
                .put("fed", fedTo).put("planned", plannedTo).put("need", needNew).put("acc", acc)
                .put("deck", JSONArray().apply { deck.forEach { put(JSONArray().put(it.uri).put(it.title).put(it.artist).put(it.kind).put(it.skip)) } })
                .put("seeds", JSONArray().apply { seeds.take(400).forEach { put(JSONArray().put(it.uri).put(it.title).put(it.sub)) } })
                .toString()
        }
        saver.execute { runCatching { val tmp = File(file.path + ".tmp"); tmp.writeText(snap); tmp.renameTo(file) } }
    }

    private fun restore() {
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return
        if (!o.optBoolean("on")) return
        synchronized(lock) {
            deck.clear()
            o.optJSONArray("deck")?.let { a ->
                for (i in 0 until a.length()) a.optJSONArray(i)?.let { e -> deck += Entry(e.optString(0), e.optString(1), e.optString(2), e.optString(3, "list"), e.optBoolean(4)) }
            }
            index = o.optInt("index").coerceIn(0, (deck.size - 1).coerceAtLeast(0))
            fedTo = o.optInt("fed", index + 1).coerceIn(index + 1, deck.size.coerceAtLeast(index + 1))
            plannedTo = o.optInt("planned", fedTo).coerceIn(fedTo, deck.size.coerceAtLeast(fedTo))
            needNew = o.optInt("need"); acc = o.optDouble("acc", 0.0)
            on = deck.isNotEmpty()
            shuffled = o.optBoolean("shuffled", true)
            listUri = o.optString("list"); listName = o.optString("name")
            deck.filter { it.isNew }.forEach { newUris += it.uri }
            deck.forEach { used += Web.key(it.title, it.artist) }
        }
        seeds = o.optJSONArray("seeds")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map { Web.Item(it.optString(0), it.optString(1), it.optString(2), "track") } }.orEmpty()
        seeds.forEach { used += Web.key(it.title, it.sub) }
    }
}
