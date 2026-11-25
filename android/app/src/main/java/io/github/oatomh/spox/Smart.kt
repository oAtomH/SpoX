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
 *  - New songs (not in your Liked Songs, this list or recent plays) are slipped in between, by the
 *    "new songs" share: 0% (just your list), 25, 50, 75 or 100% (only new songs). They come from
 *    Spotify's own autoplay picks and from artists similar to what's playing right now.
 *  - The order is saved, so closing SpoX (or the phone restarting) carries on where you were.
 *
 * The same "deck" also plays a list in your own sort order with shuffle off (no new songs then).
 * Spotify gets the next songs as one list (up to 100); SpoX hands it the next part as you get there.
 */
object Smart {
    class Entry(val uri: String, val title: String, val artist: String, val kind: String, @Volatile var skip: Boolean = false) {
        val isNew get() = kind == "new"
    }

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
    /** deck[sentTo] is the first song Spotify hasn't been given yet. */
    private var sentTo = 0
    /** A deck song queued to carry on into the next part of the list. */
    private var bridge = ""
    private var startedAt = 0L
    private var lastTrack = ""
    private var acc = 0.0
    private val pool = java.util.concurrent.ConcurrentLinkedDeque<Web.Item>()
    private val used: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    private var seeds: List<Web.Item> = emptyList()
    @Volatile private var fetching = false
    private val saver = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun ratio(ctx: Context) = ctx.getSharedPreferences("spox", 0).getInt("smart_ratio", 50).let { if (it in listOf(0, 25, 50, 75, 100)) it else 50 }
    fun setRatio(ctx: Context, r: Int) {
        ctx.getSharedPreferences("spox", 0).edit().putInt("smart_ratio", r).apply()
        // 100% plays only new songs: the list itself changes, so start the mix again
        if (on && shuffled && (r == 100) != (deckKind() == "new")) restartMix()
        listeners.forEach { it() }
    }
    fun cycleRatio(ctx: Context) = setRatio(ctx, when (ratio(ctx)) { 0 -> 25; 25 -> 50; 50 -> 75; 75 -> 100; else -> 0 })

    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private fun changed() { listeners.forEach { it() } }

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        restore()
        engine.now.listeners += { if (on) runCatching { onChange() } }
    }

    private fun toast(t: String) = PlayerView.say(app, t)

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
     * [first]: the song to start with (a song you tapped).
     */
    fun start(ctx: Context, list: Web.Item, songs: List<Web.Item>? = null, first: String? = null, shuffle: Boolean = true, positionMs: Long = 0): Boolean {
        init(ctx)
        if (!Web.signedIn) { toast("SpoX shuffle needs the Spotify sign-in (Settings)"); return false }
        if (!engine.isLive) { toast("Turn on SpoX Engine first (Settings)"); return false }
        synchronized(lock) {
            on = true; shuffled = shuffle
            listUri = list.uri; listName = list.title
            newUris.clear(); used.clear(); pool.clear(); acc = 0.0
            bridge = ""; lastTrack = ""
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
                repeat(5) { if (pool.size < 6) discoverNow() }
                val fresh = generateSequence { pick() }.take(8).toList()
                if (fresh.isEmpty()) { busy = ""; toast("No new songs found, playing your list"); fill(order.map { Entry(it.uri, it.title, it.sub, "list") }) }
                else fill(fresh.map { Entry(it.uri, it.title, it.sub, "new").also { e -> newUris += e.uri } })
            } else fill(order.map { Entry(it.uri, it.title, it.sub, "list") })
            busy = ""
            sendFrom(0, positionMs)
            if (shuffle && r > 0) discover()
            changed()
            if (shuffle) toast(if (r == 0) "SpoX shuffle on" else "SpoX shuffle on · $r% new songs")
        }
        return true
    }

    private fun fill(list: List<Entry>) = synchronized(lock) { deck.clear(); deck.addAll(list); index = 0; sentTo = 0 }

    private fun deckKind() = synchronized(lock) { if (deck.isNotEmpty() && deck.all { it.isNew }) "new" else "list" }

    /** The new-songs share crossed 100% (or back): rebuild from the list's songs. */
    private fun restartMix() {
        val list = listItem(listUri, listName)
        val cur = engine.now.track?.uri
        start(app, list, seeds.ifEmpty { null }, cur.takeIf { s -> seeds.any { it.uri == s } }, true)
    }

    fun stop() {
        synchronized(lock) { on = false; deck.clear(); index = 0; sentTo = 0; bridge = "" }
        pool.clear()
        busy = ""
        save()
        changed()
    }

    /** Nothing is playing (e.g. SpoX Engine just started): carry on with the saved order. */
    fun resume(): Boolean {
        if (!on || !Web.signedIn) return false
        val e = synchronized(lock) { deck.getOrNull(index) } ?: return false
        thread { sendFrom(index, 0) }
        lastTrack = ""
        return e.uri.isNotEmpty()
    }

    /** Jump to a song in Up next (one jump, no skipping through). */
    fun jumpTo(uri: String): Boolean {
        val i = synchronized(lock) { deck.indexOfFirst { it.uri == uri }.takeIf { it >= index } } ?: return false
        holdUntil = System.currentTimeMillis() + 3000
        thread { sendFrom(i, 0) }
        return true
    }

    /** Hands Spotify the deck from [i] on (up to 100 songs, skipping ones you removed). */
    private fun sendFrom(i: Int, positionMs: Long) {
        val uris = synchronized(lock) {
            index = i.coerceIn(0, (deck.size - 1).coerceAtLeast(0))
            val part = ArrayList<String>()
            var j = index
            while (j < deck.size && part.size < 100) { if (!deck[j].skip || j == index) part += deck[j].uri; j++ }
            sentTo = j
            bridge = ""
            startedAt = System.currentTimeMillis()
            part
        }
        save()
        if (uris.isEmpty()) return
        if (!Web.playUris(uris, positionMs)) toast("Couldn't start SpoX shuffle (is SpoX Phone signed in?)")
        engine.command("get_queue", mapOf("limit" to 0))
    }

    // ---- Up next ------------------------------------------------------------------------------

    /** What's coming, from SpoX's own order (Spotify's queue doesn't know about later parts). */
    fun upcoming(): List<Entry> = synchronized(lock) { if (index + 1 < deck.size) deck.subList(index + 1, deck.size).filter { !it.skip } else emptyList() }

    fun total() = synchronized(lock) { deck.size }

    /** The song SpoX shuffle is at (shown, paused, before SpoX Engine has anything loaded). */
    fun current(): Entry? = synchronized(lock) { if (on) deck.getOrNull(index) else null }

    /** Take songs out of what's coming. Already given to Spotify: skipped when they come up. */
    fun remove(uris: Set<String>) {
        synchronized(lock) {
            for (j in deck.indices.reversed()) {
                if (j <= index || deck[j].uri !in uris) continue
                if (j < sentTo) deck[j].skip = true else deck.removeAt(j)
            }
        }
        save()
        changed()
    }

    /** Put songs right after the one playing (also tells Spotify). */
    fun playNext(items: List<Entry>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            items.reversed().forEach { e ->
                val old = deck.indexOfFirst { it.uri == e.uri && it !== deck.getOrNull(index) }
                if (old > index) { if (old < sentTo) deck[old].skip = true else deck.removeAt(old) }
                deck.add(index + 1, Entry(e.uri, e.title, e.artist, if (e.isNew) "new" else "user"))
                sentTo++
            }
        }
        items.forEach { engine.command("add_to_queue", mapOf("uri" to it.uri)) }
        save()
        changed()
    }

    /** A song you added to the queue from the library or a link, while SpoX shuffle runs. */
    fun noteQueued(uri: String, title: String, artist: String) {
        if (!on) return
        synchronized(lock) {
            // after the other songs you queued
            var at = index + 1
            while (at < deck.size && deck[at].kind == "user") at++
            deck.add(at, Entry(uri, title, artist, "user"))
            sentTo++
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
        var resend = -1
        var mix = false
        var queueBridge = ""
        val found: Boolean = synchronized(lock) {
            val pos = (maxOf(0, index - 5) until deck.size).firstOrNull { deck[it].uri == cur } ?: -1
            if (pos >= 0) {
                index = pos
                val e = deck[pos]
                if (e.skip) { engine.command("skip_next"); return }
                if (cur == bridge) { bridge = ""; resend = pos }
                mix = e.kind == "list" && resend < 0
            }
            pos >= 0
        }
        if (found && mix && shuffled) mixIn()
        if (found && shuffled && deckKind() == "new") topUp()
        if (found && resend < 0) synchronized(lock) {
            if (index >= sentTo - 1 && sentTo < deck.size && bridge.isEmpty()) {
                // the last song Spotify has: queue the next one so it carries on smoothly
                bridge = deck[sentTo].uri; queueBridge = bridge
            }
        }
        if (queueBridge.isNotEmpty()) engine.command("add_to_queue", mapOf("uri" to queueBridge))
        if (resend >= 0) {
            // arrived in the next part: give Spotify the rest of the list from here, same spot
            val at = n.position()
            thread { sendFrom(resend, at) }
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
        save()
        if (shuffled && ratio(app) > 0 && pool.size < 8) discover()
        changed()
    }

    /** After one of your songs: maybe a new song (or a few, at 75%) right after it. */
    private fun mixIn() {
        val r = ratio(app)
        if (r <= 0 || r >= 100) return
        acc = minOf(3.0, acc + r / (100.0 - r))
        val picks = ArrayList<Web.Item>()
        while (acc >= 1.0) { val t = pick() ?: break; picks += t; acc -= 1.0 }
        if (picks.isEmpty()) return
        synchronized(lock) {
            picks.forEachIndexed { k, t -> deck.add(index + 1 + k, Entry(t.uri, t.title, t.sub, "new")); newUris += t.uri }
            sentTo += picks.size
        }
        picks.forEach { engine.command("add_to_queue", mapOf("uri" to it.uri)) }
        engine.command("get_queue", mapOf("limit" to 0))
        save()
    }

    /** 100% new: keep about ten new songs ahead. */
    private fun topUp() {
        synchronized(lock) {
            while (deck.size - index < 10) {
                val t = pick() ?: break
                deck += Entry(t.uri, t.title, t.sub, "new"); newUris += t.uri
            }
            // nothing sent yet beyond the end: the bridge carries it over
        }
    }

    // ---- new songs ---------------------------------------------------------------------------

    private fun known(title: String, artist: String) = used.contains(Web.key(title, artist)) || Web.knows(title, artist) == true

    /** Spotify's own autoplay picks in the queue: the first source of new songs. */
    private fun harvest() {
        if (!shuffled || ratio(app) == 0) return
        for (t in engine.now.queue) {
            if (!(t.provider.contains("autoplay") || t.provider.contains("recommend"))) continue
            if (!known(t.title, t.artist) && pool.none { it.uri == t.uri }) pool.addFirst(Web.Item(t.uri, t.title, t.artist, "track"))
        }
    }

    /** A new song, not by the artist playing now, and not the same artist twice in the pool. */
    private fun pick(): Web.Item? {
        val nowArtist = engine.now.track?.artist?.split(", ")?.firstOrNull().orEmpty().lowercase()
        var tries = pool.size
        while (tries-- > 0) {
            val t = pool.pollFirst() ?: break
            if (known(t.title, t.sub)) continue
            if (t.sub.split(", ").firstOrNull().orEmpty().lowercase() == nowArtist && pool.isNotEmpty()) { pool.addLast(t); continue }
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

    /** Seeds: mostly what's playing now (so new songs fit the moment), sometimes any list song. */
    private fun seedArtist(): String {
        val cur = engine.now.track
        val fromNow = cur != null && Math.random() < 0.6
        val a = if (fromNow) cur!!.artist else seeds.randomOrNull()?.sub.orEmpty()
        return a.split(", ").firstOrNull().orEmpty()
    }

    private fun discover() {
        if (fetching || pool.size >= 12) return
        thread(name = "spox-discover") { discoverNow() }
    }

    @Synchronized
    private fun discoverNow() {
        if (fetching) return
        fetching = true
        try {
            val artist = seedArtist()
            if (artist.isEmpty()) return
            var added = 0
            for ((title, a) in similarSongs(artist)) {
                if (added >= 4) break
                if (title.isEmpty() || known(title, a)) continue
                val t = Web.get("/search?type=track&limit=3&q=" + enc("track:$title artist:$a"))?.optJSONObject("tracks")?.optJSONArray("items")?.let { Web.track(it.optJSONObject(0)) } ?: continue
                if (known(t.title, t.sub) || pool.any { it.uri == t.uri }) continue
                pool.addLast(t)
                added++
            }
            if (added > 0) return
            // nothing similar found: other songs by the list's own artists
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
            // Spotify may have kept the old part; after a restart SpoX hands it over again when needed
            sentTo = minOf(deck.size, index + 100)
            on = deck.isNotEmpty()
            shuffled = o.optBoolean("shuffled", true)
            listUri = o.optString("list"); listName = o.optString("name")
            deck.filter { it.isNew }.forEach { newUris += it.uri }
            deck.forEach { used += Web.key(it.title, it.artist) }
        }
        seeds = o.optJSONArray("seeds")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map { Web.Item(it.optString(0), it.optString(1), it.optString(2), "track") } }.orEmpty()
    }
}
