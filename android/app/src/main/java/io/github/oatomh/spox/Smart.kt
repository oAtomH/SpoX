package io.github.oatomh.spox

import android.content.Context
import android.widget.Toast
import io.github.oatomh.spox.engine.Engine
import kotlin.concurrent.thread

/**
 * Smart shuffle (same idea as SpoX on Windows): Spotify shuffles the playlist you're playing as
 * usual, and SpoX slips songs you don't know yet in between: Spotify's own autoplay picks, and
 * more songs by the playlist's artists. "Don't know" = not in your Liked Songs, this playlist or
 * your recent plays (from your Spotify account). Needs SpoX Engine and the Spotify sign-in.
 */
object Smart {
    @Volatile var on = false
        private set
    /** Songs smart shuffle brought in (Up next marks them "new"). */
    val newUris = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private lateinit var app: Context
    private val engine get() = Engine.get(app)
    private val pool = java.util.concurrent.ConcurrentLinkedDeque<Web.Item>()
    private val used = HashSet<String>()
    private val queued = ArrayDeque<String>()
    private var playlist = ""
    private var seeds: List<Web.Item> = emptyList()
    private var acc = 0
    private var lastTrack = ""
    @Volatile private var fetching = false
    @Volatile var holdUntil = 0L

    fun ratio(ctx: Context) = ctx.getSharedPreferences("spox", 0).getInt("smart_ratio", 30)
    fun cycleRatio(ctx: Context) {
        val next = when (ratio(ctx)) { 15 -> 30; 30 -> 50; else -> 15 }
        ctx.getSharedPreferences("spox", 0).edit().putInt("smart_ratio", next).apply()
    }

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        engine.now.listeners += { if (on) onChange() }
    }

    fun start(ctx: Context): Boolean {
        init(ctx)
        val ctxUri = engine.now.contextUri
        if (!Web.signedIn) { toast("Smart shuffle needs the Spotify sign-in (Settings)"); return false }
        if (!ctxUri.startsWith("spotify:playlist:")) { toast("Play one of your playlists first"); return false }
        on = true
        playlist = ctxUri
        used.clear(); queued.clear(); newUris.clear(); pool.clear()
        acc = 0
        lastTrack = engine.now.track?.uri.orEmpty()
        engine.command("set_shuffle", mapOf("enabled" to true))
        Web.loadKnown(ctxUri)
        thread { seeds = Web.tracks(Web.Item(ctxUri, "", "", "playlist", ctxUri.substringAfterLast(':'))); discover() }
        toast("Smart shuffle on · ${ratio(ctx)}% new songs")
        return true
    }

    fun stop() {
        on = false
        queued.clear()
        pool.clear()
    }

    private fun toast(t: String) = android.os.Handler(app.mainLooper).post { Toast.makeText(app, t, Toast.LENGTH_SHORT).show() }

    private fun known(t: Web.Item) = used.contains(Web.key(t.title, t.sub)) || Web.knows(t.title, t.sub) == true

    @Synchronized
    private fun onChange() {
        val n = engine.now
        // you switched to another playlist: smart shuffle ends
        if (n.contextUri.isNotEmpty() && n.contextUri != playlist && !n.contextUri.startsWith("spotify:track")) { stop(); return }
        harvest()
        val cur = n.track?.uri ?: return
        if (cur == lastTrack) return
        lastTrack = cur
        // jumping ahead in Up next: wait until you land
        if (System.currentTimeMillis() < holdUntil) { while (queued.isNotEmpty() && queued.removeFirst() != cur) {}; return }
        val i = queued.indexOf(cur)
        if (i >= 0) { repeat(i + 1) { queued.removeFirst() }; return }
        if (queued.isEmpty()) {
            acc += ratio(app)
            if (acc >= 100) {
                val t = pick()
                if (t != null) {
                    acc -= 100
                    engine.command("add_to_queue", mapOf("uri" to t.uri))
                    queued.addLast(t.uri)
                    newUris += t.uri
                    engine.command("get_queue", mapOf("limit" to 50))
                } else acc = 100
            }
        }
        if (pool.size < 6) discover()
    }

    /** Spotify's autoplay picks in the queue: keep the ones you don't know. */
    private fun harvest() {
        for (t in engine.now.queue) {
            if (!(t.provider.contains("autoplay") || t.provider.contains("recommend"))) continue
            val item = Web.Item(t.uri, t.title, t.artist, "track")
            if (!known(item) && pool.none { it.uri == t.uri }) pool.addLast(item)
        }
    }

    private fun pick(): Web.Item? {
        val nowArtist = engine.now.track?.artist?.split(", ")?.firstOrNull().orEmpty()
        var tries = pool.size
        while (tries-- > 0) {
            val t = pool.pollFirst() ?: break
            if (known(t)) continue
            if (t.sub.split(", ").firstOrNull() == nowArtist && pool.isNotEmpty()) { pool.addLast(t); continue }
            used += Web.key(t.title, t.sub)
            return t
        }
        return null
    }

    /** More songs by artists from this playlist that you don't have yet. */
    private fun discover() {
        if (fetching || seeds.isEmpty() || pool.size >= 12) return
        fetching = true
        val seed = seeds.random()
        thread {
            try {
                val artist = seed.sub.split(", ").firstOrNull().orEmpty()
                if (artist.isNotEmpty()) {
                    val off = (System.currentTimeMillis() / 1000 % 3) * 10
                    val v = Web.get("/search?type=track&limit=10&offset=$off&q=" + java.net.URLEncoder.encode("artist:\"$artist\"", "UTF-8"))
                    val a = v?.optJSONObject("tracks")?.optJSONArray("items")
                    var added = 0
                    if (a != null) for (i in 0 until a.length()) {
                        val t = Web.track(a.optJSONObject(i)) ?: continue
                        if (known(t) || pool.any { it.uri == t.uri }) continue
                        pool.addLast(t)
                        if (++added >= 4) break
                    }
                }
            } finally { fetching = false }
        }
    }
}
