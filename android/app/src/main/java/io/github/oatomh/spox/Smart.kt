package io.github.oatomh.spox

import android.content.Context
import io.github.oatomh.spox.engine.Engine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * SpoX's own queue, run like Spotify's app runs its queue: the order lives in SpoX, and SpoX
 * Engine (Soloist, on the phone) is only told what to play, with local commands. No Spotify
 * Web API for playing at all, so no waiting on Spotify's request limits.
 *
 *  - The "deck" is the order: songs already played (going back works) and ~100 planned ahead.
 *    Shuffle: your list spread out fairly (artists apart, every song once per round), with new
 *    songs mixed in by the share in SpoX shuffle. In order: your list from the song you're on.
 *  - Moving (next, back, a song tapped in Up next, a new list): SpoX plays that song directly.
 *    Several quick presses become one move, so nothing is skipped late.
 *  - Gapless: about 20 s before a song ends, SpoX puts the next one in Spotify's queue, so it
 *    follows by itself (also with the screen off). Until then nothing is handed to Spotify, so
 *    changing shuffle, the share or Up next is instant and never touches the song playing.
 *  - If a song ends anyway with nothing after it (or Spotify starts its own pick), SpoX plays
 *    the next one itself. Everything is saved, so a restart carries on where you were.
 */
object Smart {
    class Entry(val uri: String, val title: String, val artist: String, val kind: String) {
        val isNew get() = kind == "new"
        val isMine get() = kind == "user"
    }

    /** Songs planned ahead (Up next shows this many). */
    private const val AHEAD = 100
    /** Songs kept behind the one playing (for going back). */
    private const val BEHIND = 100
    /** The next song goes into Spotify's queue this long before the end. */
    private const val ARM_MS = 20_000L

    /** SpoX is in charge of what plays next. */
    @Volatile var on = false
        private set
    /** true: shuffled; false: your list in your order. */
    @Volatile var shuffled = true
        private set
    @Volatile var listUri = ""
        private set
    @Volatile var listName = ""
        private set
    /** Songs SpoX brought in (Up next marks them). */
    val newUris: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    @Volatile var holdUntil = 0L
    /** Shown while the list loads. */
    @Volatile var busy = ""
        private set

    private lateinit var app: Context
    private val engine get() = Engine.get(app)
    private val lock = Any()
    private val deck = ArrayList<Entry>()
    @Volatile private var index = 0
    /** Your list's songs still to come in this round. */
    private val bag = ArrayDeque<Web.Item>()
    private var needNew = 0
    private var acc = 0.0
    /** the list's songs (merged, playable) and the same in your order (sort / search) */
    private var seeds: List<Web.Item> = emptyList()
    private var ordered: List<Web.Item> = emptyList()

    // where SpoX told SpoX Engine to go, and what it put in Spotify's queue
    @Volatile private var target = -1
    @Volatile private var targetUri = ""
    @Volatile private var targetAt = 0L
    @Volatile private var targetPos = 0L
    @Volatile private var fellBack = false
    /** the next song, already in Spotify's queue */
    @Volatile private var armed: String? = null
    /** songs SpoX put in Spotify's queue that it no longer wants: skipped the moment they start */
    private val stale = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** songs queued from somewhere else (another device): Spotify plays them in between */
    private val queued: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    private var lastTrack = ""
    private var endedFor = ""

    private val pool = java.util.concurrent.ConcurrentLinkedDeque<Web.Item>()
    private val used: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
    private val finding = AtomicInteger(0)
    private val saver = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main by lazy { android.os.Handler(app.mainLooper) }

    fun ratio(ctx: Context) = ctx.getSharedPreferences("spox", 0).getInt("smart_ratio", 50).let { if (it in listOf(0, 25, 50, 75, 100)) it else 50 }

    /** The shuffle button: "off" (in order), "shuffle" or "spox" (shuffle + new songs). A setting that sticks. */
    fun mode(ctx: Context) = ctx.getSharedPreferences("spox", 0).getString("shuffle_mode", "off").let { if (it == "shuffle" || it == "spox") it else "off" }
    fun setMode(ctx: Context, m: String) { ctx.getSharedPreferences("spox", 0).edit().putString("shuffle_mode", m).apply(); changed() }
    fun nextMode(m: String) = when (m) { "off" -> "shuffle"; "shuffle" -> "spox"; else -> "off" }

    private fun share() = if (shuffled && mode(app) == "spox") ratio(app) else 0

    fun setRatio(ctx: Context, r: Int) {
        val old = ratio(ctx)
        ctx.getSharedPreferences("spox", 0).edit().putInt("smart_ratio", r).apply()
        if (on && shuffled && mode(ctx) == "spox" && r != old) replanAhead(keepOrder = true)
        changed()
    }

    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private fun changed() { listeners.forEach { it() } }

    fun init(c: Context) {
        if (::app.isInitialized) return
        app = c.applicationContext
        restore()
        engine.now.listeners += { if (on) runCatching { onChange() } }
        main.postDelayed(ticker, 1000)
    }

    private fun toast(t: String) = PlayerView.say(app, t)

    // every second: arm the next song near the end, catch a song that ended with nothing after it;
    // every few seconds: keep planning and finding new songs
    private var tick = 0
    private val ticker = object : Runnable {
        override fun run() {
            if (on) {
                runCatching { arm(); ended() }
                if (++tick % 4 == 0) thread(name = "spox-shuffle-tick") { runCatching { work() } }
            }
            main.postDelayed(this, 1000)
        }
    }

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
     * Plays a list through SpoX. [songs]: its songs if loaded; [order]: the same in the order you
     * see them (for "in order"); [first]: the song to start with. If [first] is the song playing
     * right now, it simply carries on (nothing restarts): only what comes after changes.
     */
    fun start(ctx: Context, list: Web.Item, songs: List<Web.Item>? = null, first: String? = null, shuffle: Boolean = true,
              positionMs: Long = 0, order: List<Web.Item>? = null): Boolean {
        init(ctx)
        if (!Web.signedIn) { toast("Sign in to Spotify first (Settings)"); return false }
        if (!engine.isLive) { toast("Turn on SpoX Engine first (Settings)"); return false }
        busy = "Loading ${list.title.ifEmpty { "the list" }}..."
        changed()
        if (list.uri.startsWith("spotify:")) Web.loadKnown(list.uri)
        thread(name = "spox-shuffle") {
            val all = songs ?: Web.tracks(list)
            val seen = HashSet<String>()
            val clean = all.filter { it.kind == "track" && it.uri.startsWith("spotify:track:") && seen.add(Web.key(it.title, it.sub)) }
            if (clean.isEmpty()) { busy = ""; changed(); toast("Nothing to play in ${list.title}"); return@thread }
            val keys = clean.map { it.uri }.toSet()
            val ord = order?.filter { it.uri in keys }?.distinctBy { it.uri }?.takeIf { it.isNotEmpty() } ?: clean
            val playingNow = engine.now.track?.uri
            val carryOn = first != null && first == playingNow && engine.now.track != null
            synchronized(lock) {
                // what's been played stays (going back still works)
                while (deck.size > index + 1) deck.removeAt(deck.size - 1)
                on = true; shuffled = shuffle
                listUri = list.uri; listName = list.title
                seeds = clean; ordered = ord
                used.clear(); clean.forEach { used += Web.key(it.title, it.sub) }
                deck.forEach { used += Web.key(it.title, it.artist) }
                newUris.clear(); pool.clear(); queued.clear(); owed.clear(); acc = 0.0; needNew = 0
                dropArmed()
            }
            loadFound(list.uri)
            val r = share()
            warmUntil = if (r > 0 && pool.size < 10) System.currentTimeMillis() + 10_000 else 0L
            val firstItem = first?.let { f -> clean.firstOrNull { it.uri == f } }
            val startIndex = synchronized(lock) {
                fillBag(firstItem?.uri)
                val t = firstItem ?: (if (r == 100) pick("") else null) ?: bag.removeFirstOrNull()
                if (t == null) -1 else {
                    if (carryOn && deck.isNotEmpty() && deck[index].uri == t.uri) index
                    else { deck.add(Entry(t.uri, t.title, t.sub, if (t.uri in newUris) "new" else "list")); deck.size - 1 }
                }
            }
            busy = ""
            if (startIndex < 0) { stop(); toast("No songs to play"); return@thread }
            // Spotify's own shuffle / repeat would fight SpoX's order
            if (engine.now.shuffle) engine.command("set_shuffle", mapOf("enabled" to false))
            if (engine.now.repeat == "context") engine.command("set_repeat_context", mapOf("enabled" to false))
            if (carryOn) {
                synchronized(lock) { index = startIndex; lastTrack = playingNow.orEmpty(); target = -1 }
                plan()
            } else {
                synchronized(lock) { index = startIndex }
                plan()
                go(startIndex, if (first != null) positionMs else 0, now = true)
            }
            work()
            toast(when { !shuffle -> "In order"; r == 0 -> "Shuffle on"; else -> "SpoX shuffle · $r% new songs" })
        }
        return true
    }

    /** Fills the round: shuffled (spread), or your order from the song after [from]. */
    private fun fillBag(from: String?) {
        bag.clear()
        if (shuffled) {
            val recent = deck.takeLast(minOf(deck.size, seeds.size / 2)).map { it.uri }.toSet() + setOfNotNull(from)
            val fresh = spread(seeds)
            bag.addAll(fresh.filter { it.uri !in recent } + fresh.filter { it.uri in recent && it.uri != from })
        } else {
            val at = from?.let { f -> ordered.indexOfFirst { it.uri == f } } ?: -1
            bag.addAll(if (at >= 0) ordered.drop(at + 1) else ordered)
        }
    }

    /** The shuffle button pressed while SpoX plays a list: what's ahead is planned again at once,
     *  the song playing carries on. */
    fun applyMode(ctx: Context, mode: String): Boolean {
        if (!on || seeds.isEmpty()) return false
        synchronized(lock) { shuffled = mode != "off" }
        if (mode == "spox") { loadFound(listUri); warmUntil = if (pool.size < 10) System.currentTimeMillis() + 10_000 else 0L }
        replanAhead(keepOrder = false)
        toast(when (mode) { "spox" -> "SpoX shuffle · ${ratio(ctx)}% new songs"; "shuffle" -> "Shuffle on"; else -> "Shuffle off · in order" })
        return true
    }

    /** Throws away the plan after the song playing (your own picks stay first) and plans again. */
    private fun replanAhead(keepOrder: Boolean) {
        synchronized(lock) {
            val ahead = deck.subList(index + 1, deck.size).toList()
            while (deck.size > index + 1) deck.removeAt(deck.size - 1)
            ahead.filter { it.isMine }.forEach { deck += it }
            needNew = 0; acc = 0.0; owed.clear()
            if (keepOrder) {
                // same list songs in the same order, only the new songs are mixed again
                bag.clear()
                ahead.filter { it.kind == "list" }.forEach { e -> seeds.firstOrNull { it.uri == e.uri }?.let { bag.addLast(it) } }
            } else fillBag(deck.getOrNull(index)?.uri)
        }
        plan()
        rearm()
        save(); changed()
    }

    fun stop() {
        synchronized(lock) { on = false; deck.clear(); index = 0; bag.clear(); target = -1; queued.clear(); owed.clear(); dropArmed() }
        pool.clear()
        busy = ""
        save()
        changed()
    }

    /** Nothing loaded (SpoX Engine just started): carry on with the saved order at [positionMs]. */
    fun resume(positionMs: Long = 0): Boolean {
        if (!on || !Web.signedIn || synchronized(lock) { deck.isEmpty() }) return false
        go(index, positionMs, now = true)
        return true
    }

    // ---- moving ------------------------------------------------------------------------------

    private val send = Runnable { thread(name = "spox-go") { runCatching { sendGo() } } }

    /** Go to deck[i]. Quick presses in a row are gathered into one move (sent 250 ms after the last). */
    private fun go(i: Int, positionMs: Long = 0, now: Boolean = false) {
        synchronized(lock) {
            if (deck.isEmpty()) return
            val j = i.coerceIn(0, deck.size - 1)
            target = j; targetUri = deck[j].uri; targetAt = System.currentTimeMillis(); targetPos = positionMs
            fellBack = false
            holdUntil = targetAt + 8000
        }
        changed()
        main.removeCallbacks(send)
        main.postDelayed(send, if (now) 0 else 250)
    }

    private fun sendGo() {
        val (uri, useSkip) = synchronized(lock) {
            if (target < 0) return
            val u = targetUri
            // the next song is already first in Spotify's queue: just skip to it (gapless, instant)
            val head = engine.now.queue.firstOrNull { it.provider.contains("queue") }?.uri
            val skip = armed == u && target == index + 1 && head == u && queued.isEmpty()
            if (skip) armed = null else dropArmed()
            u to skip
        }
        targetAt = System.currentTimeMillis()
        if (!engine.now.active) engine.command("activate")
        if (useSkip) engine.command("skip_next") else engine.command("play", mapOf("uri" to uri))
        // SpoX Engine didn't take it (not the active device yet...): ask through Spotify once
        main.postDelayed({
            if (target >= 0 && targetUri == uri && engine.now.track?.uri != uri && !fellBack) {
                fellBack = true
                thread { Web.playUris(listOf(uri), targetPos, 0) }
            }
        }, 5000)
    }

    /** Next (button, lock screen, headphones). */
    fun next() {
        // songs queued from another device play first, like Spotify
        if (queued.isNotEmpty() && engine.now.queue.firstOrNull()?.uri in queued) { engine.command("skip_next"); return }
        val base = synchronized(lock) { if (target >= 0) target else index }
        synchronized(lock) { if (base + 1 >= deck.size) plan() }
        if (base + 1 < synchronized(lock) { deck.size }) go(base + 1)
    }

    /** Back: to the start of the song, or (in its first 3 s) the song before. */
    fun prev() {
        val pos = engine.now.position()
        val base = synchronized(lock) { if (target >= 0) target else index }
        if (target < 0 && pos > 3000 || base == 0) { engine.command("seek", mapOf("position_ms" to 0)); return }
        go(base - 1)
    }

    /** Jump to a song in Up next: the songs before it are passed over (like Spotify). */
    fun jumpTo(uri: String): Boolean {
        val i = synchronized(lock) {
            val i = (index + 1 until deck.size).firstOrNull { deck[it].uri == uri } ?: return false
            repeat(i - index - 1) { deck.removeAt(index + 1) }
            index + 1
        }
        go(i, now = true)
        thread { runCatching { work() } }
        return true
    }

    // ---- Up next -----------------------------------------------------------------------------

    fun upcoming(): List<Entry> = synchronized(lock) {
        val from = (if (target >= 0) target else index) + 1
        if (from < deck.size) deck.subList(from, deck.size).toList() else emptyList()
    }

    fun current(): Entry? = synchronized(lock) { if (on) deck.getOrNull(if (target >= 0) target else index) else null }

    fun queuedUris(): Set<String> = synchronized(lock) { deck.drop(index + 1).filter { it.isMine }.map { it.uri }.toSet() } + queued

    fun remove(uris: Set<String>) {
        synchronized(lock) {
            for (j in deck.indices.reversed()) if (j > index && deck[j].uri in uris) deck.removeAt(j)
            queued.removeAll(uris)
        }
        rearm()
        thread { runCatching { work() } }
    }

    /** Play next: right after this song (after songs you picked before), in SpoX's own order. */
    fun playNext(items: List<Entry>) {
        if (items.isEmpty()) return
        val uris = items.map { it.uri }.toSet()
        synchronized(lock) {
            for (j in deck.indices.reversed()) if (j > index && deck[j].uri in uris) deck.removeAt(j)
            var at = index + 1
            while (at < deck.size && deck[at].isMine) at++
            items.forEachIndexed { k, e -> deck.add(at + k, Entry(e.uri, e.title, e.artist, "user")) }
        }
        rearm()
        thread { runCatching { work() } }
    }

    /** Added to the queue from the library / a link while SpoX plays: it goes into SpoX's order. */
    fun noteQueued(uri: String, title: String, artist: String): Boolean {
        if (!on) return false
        playNext(listOf(Entry(uri, title, artist, "user")))
        return true
    }

    // ---- Spotify's queue: only the very next song, only near the end -----------------------

    /** The armed song is no longer wanted: if it starts, it's skipped. */
    private fun dropArmed() {
        armed?.let { stale.merge(it, 1, Int::plus) }
        armed = null
    }

    private fun rearm() {
        synchronized(lock) { if (armed != null && armed != deck.getOrNull(index + 1)?.uri) dropArmed() }
        arm()
    }

    private fun arm() {
        val n = engine.now
        val t = n.track ?: return
        val want = synchronized(lock) {
            if (target >= 0 || !isAt(index, t.uri)) return
            deck.getOrNull(index + 1)?.uri ?: return
        }
        if (n.repeat == "track" || !n.active) return
        if (t.durationMs <= 0 || t.durationMs - n.position() > ARM_MS) return
        if (armed == want) return
        synchronized(lock) { if (armed != null) dropArmed(); armed = want }
        engine.command("add_to_queue", mapOf("uri" to want))
    }

    /** The song ended and nothing came after it (or Spotify stopped): play the next one. */
    private fun ended() {
        val n = engine.now
        val t = n.track ?: return
        if (target >= 0 || t.uri == endedFor || !n.active) return
        val atEnd = t.durationMs > 0 && n.position() >= t.durationMs - 1200
        if (!atEnd || n.playing) return
        if (synchronized(lock) { !isAt(index, t.uri) }) return
        endedFor = t.uri
        next()
    }

    // ---- following what plays ----------------------------------------------------------------

    private fun onChange() {
        harvest()
        val n = engine.now
        val cur = n.track?.uri ?: return
        if (cur == lastTrack) return
        val now = System.currentTimeMillis()
        lastTrack = cur
        endedFor = ""
        // another device plays (SpoX on the PC, the Spotify app...): none of this SpoX's business;
        // reacting to its songs made the two take the music from each other
        if (target < 0 && !n.active) return
        var fix = false
        var leave = false
        synchronized(lock) {
            when {
                target >= 0 && (cur == targetUri || isAt(target, cur)) -> {
                    index = target; target = -1
                    if (targetPos > 0) { engine.command("seek", mapOf("position_ms" to targetPos)); targetPos = 0 }
                }
                // a move is on its way: Spotify may still report the old song for a moment
                target >= 0 && now - targetAt < 8000 -> return
                else -> {
                    target = -1
                    when {
                        queued.remove(cur) -> {}
                        isAt(index, cur) -> {}
                        armed != null && (armed == cur || isAt(index + 1, cur)) -> { armed = null; index = nearest(cur).takeIf { it >= 0 } ?: index }
                        stale[cur] != null -> {
                            stale.computeIfPresent(cur) { _, c -> if (c <= 1) null else c - 1 }
                            fix = true
                        }
                        nearest(cur) >= 0 -> { index = nearest(cur); if (armed != null && armed != deck.getOrNull(index + 1)?.uri) dropArmed() }
                        Player.isSkipped(cur) -> {}
                        // Spotify's own pick after a song (autoplay), or next pressed on another device
                        isFallthrough(n) -> fix = true
                        // you started another list / album / artist: SpoX hands over. Anything else
                        // unexpected never throws the order away.
                        otherList(n) -> leave = true
                    }
                }
            }
            if (index > BEHIND + 50) {
                val cut = index - BEHIND
                repeat(cut) { deck.removeAt(0) }
                index -= cut
            }
        }
        when {
            fix -> go(synchronized(lock) { index } + 1, now = true)
            // you played something else (another list, a song from search): SpoX hands over
            leave -> stop()
            else -> thread { runCatching { work() } }
        }
    }

    /** Nearest place of [uri] in the deck around the song playing (a song can be in it twice). */
    /** Is deck[k] the song playing? By its link, or by title + artist: Spotify sometimes plays a
     *  song under another link than the one asked for (the same song on another release). */
    private fun isAt(k: Int, uri: String): Boolean {
        val e = deck.getOrNull(k) ?: return false
        if (e.uri == uri) return true
        val t = engine.now.track ?: return false
        return t.uri == uri && Web.key(e.title, e.artist) == Web.key(t.title, t.artist)
    }

    private fun nearest(uri: String): Int {
        val lo = maxOf(0, index - BEHIND)
        return (lo until deck.size).filter { isAt(it, uri) }.minByOrNull { kotlin.math.abs(it - index - 1) } ?: -1
    }

    private fun otherList(n: Engine.Now): Boolean {
        val c = n.contextUri
        return listOf("spotify:playlist:", "spotify:album:", "spotify:artist:", "spotify:show:", ":collection").any { c.contains(it) } && c != listUri &&
            !(listUri == "liked" && c.contains(":collection"))
    }

    private fun isFallthrough(n: Engine.Now): Boolean {
        val c = n.contextUri
        // SpoX plays single songs, so its context is a song of the deck; after it Spotify may go on
        // with its own picks (autoplay / a station)
        return c.isEmpty() || c.contains("station") || c.contains("autoplay") || c.contains("radio") ||
            (c.startsWith("spotify:track:") && deck.any { it.uri == c }) || (c == listUri && listUri.isNotEmpty())
    }

    /**
     * Fills the order up to [AHEAD] songs ahead. Returns true while new songs are owed but none
     * are found yet.
     */
    private fun plan(): Boolean = synchronized(lock) {
        val r = share()
        fillOwed()
        var hungry = owed.isNotEmpty()
        while (deck.size - index - 1 < AHEAD) {
            if (r >= 100 || needNew > 0) {
                val t = pick(deck.lastOrNull()?.artist.orEmpty())
                if (t != null) {
                    deck += Entry(t.uri, t.title, t.sub, "new")
                    if (needNew > 0) needNew--
                    continue
                }
                hungry = true
                // none found yet: the list carries on (never waits), and the new songs owed here
                // are slotted in at this spot as soon as they're found (the share stays even)
                deck.lastOrNull()?.takeIf { owed.size < 40 }?.let { e -> repeat(maxOf(needNew, 1)) { owed += e } }
                needNew = 0
                if (r >= 100 && bag.isEmpty() && seeds.isEmpty()) break
            }
            if (bag.isEmpty()) {
                if (seeds.isEmpty()) break
                // played the whole round: another one (shuffled again, or your order from the top)
                if (shuffled) fillBag(null) else bag.addAll(ordered)
            }
            val t = bag.removeFirstOrNull() ?: break
            deck += Entry(t.uri, t.title, t.sub, "list")
            if (r in 1..99) {
                acc += r / (100.0 - r)
                while (acc >= 1.0) { needNew++; acc -= 1.0 }
            }
        }
        hungry
    }

    /** Spots in the plan still owed a new song (the entry it goes after), oldest first. */
    private val owed = ArrayList<Entry>()

    /** Puts found new songs into the owed spots, only further ahead than the next two songs:
     *  nothing already planned moves or disappears, new songs just appear between them. */
    private fun fillOwed() {
        if (owed.isEmpty()) return
        if (share() == 0) { owed.clear(); return }
        var added = false
        val it = owed.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val pos = deck.indexOfFirst { x -> x === e }
            if (pos < 0 || pos <= index + 1) { it.remove(); continue }
            val t = pick(e.artist) ?: break
            deck.add(pos + 1, Entry(t.uri, t.title, t.sub, "new"))
            it.remove()
            added = true
        }
        if (added && armed != null && armed != deck.getOrNull(index + 1)?.uri) dropArmed()
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
    private var lastDisc = 0L

    private fun discover() {
        // only while this SpoX plays (not the PC or another device), at most every 15 s
        if (!engine.now.active || System.currentTimeMillis() - lastDisc < 15_000) return
        if (!finding.compareAndSet(0, 1)) return
        lastDisc = System.currentTimeMillis()
        thread(name = "spox-discover") {
            try { discoverNow(null) } finally { finding.set(0) }
            if (on && pool.isNotEmpty()) runCatching {
                val wasWarm = warmUntil > 0
                if (pool.size >= 6) warmUntil = 0
                plan()
                // still owed: look again right away (the slow lane keeps Spotify happy)
                if (synchronized(lock) { owed.isNotEmpty() }) main.postDelayed({ discover() }, 15_000)
                if (wasWarm && warmUntil == 0L) rearm()
                save(); changed()
            }
        }
    }

    /**
     * Finds new songs: five artists similar to the seed (Deezer), then ONE Spotify search per
     * artist that brings ten of their songs; up to three you don't know are kept per artist.
     * (Before, every single song cost its own Spotify search: that ran into Spotify's limit.)
     */
    private fun discoverNow(seed: String?) {
        val artist = seed?.split(", ")?.firstOrNull() ?: seedArtist()
        if (artist.isEmpty()) return
        val found = ArrayList<Web.Item>()
        for (a in similarArtists(artist).take(5).ifEmpty { listOf(artist) }) {
            val songs = artistSongs(a)
                .filter { it.sub.split(", ").first().equals(a, true) && !known(it.title, it.sub) && pool.none { p -> p.uri == it.uri } && it.uri !in newUris }
                .shuffled().take(3)
            pool.addAll(songs)
            found += songs
        }
        if (found.isNotEmpty()) saveFound(found)
    }

    /** Songs by an artist. Each artist is asked once every two weeks (kept on the phone), and at
     *  most 8 searches go out per 10 minutes: new songs never eat the request budget SpoX (phone
     *  and PC share one Spotify app) needs for playing, likes and lists. */
    private val searches = ArrayList<Long>()
    private val artistFile get() = File(app.filesDir, "artists.json")
    private val artistCache: JSONObject by lazy { runCatching { JSONObject(artistFile.readText()) }.getOrDefault(JSONObject()) }

    private fun artistSongs(a: String): List<Web.Item> {
        val k = a.lowercase()
        val now = System.currentTimeMillis()
        synchronized(artistCache) {
            artistCache.optJSONArray(k)?.let { e ->
                if (now - e.optLong(0) < 14 * 86_400_000L) return items(e.optJSONArray(1))
            }
        }
        synchronized(searches) {
            searches.removeAll { now - it > 600_000 }
            if (searches.size >= 8) return emptyList()
            searches += now
        }
        val r = Web.getBg("/search?type=track&limit=10&q=" + enc("artist:\"$a\""))?.optJSONObject("tracks")?.optJSONArray("items") ?: return emptyList()
        val songs = (0 until r.length()).mapNotNull { Web.track(r.optJSONObject(it)) }
        synchronized(artistCache) {
            artistCache.put(k, JSONArray().put(now).put(arr(songs)))
            val snap = artistCache.toString()
            saver.execute { runCatching { artistFile.writeText(snap) } }
        }
        return songs
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
    private fun items(a: JSONArray?) = a?.let { (0 until it.length()).mapNotNull { i -> it.optJSONArray(i) }.map { e -> item(e) } }.orEmpty()
    private fun arr(l: List<Web.Item>) = JSONArray().apply { l.forEach { put(JSONArray().put(it.uri).put(it.title).put(it.sub)) } }

    private fun save() {
        if (!::app.isInitialized) return
        val snap = synchronized(lock) {
            JSONObject().put("v", 4).put("on", on).put("shuffled", shuffled).put("list", listUri).put("name", listName).put("index", index)
                .put("need", needNew).put("acc", acc)
                .put("deck", JSONArray().apply { deck.forEach { put(JSONArray().put(it.uri).put(it.title).put(it.artist).put(it.kind)) } })
                .put("bag", arr(bag.toList()))
                .put("seeds", arr(seeds.take(3000)))
                .put("ordered", JSONArray().apply { ordered.take(3000).forEach { put(it.uri) } })
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
            bag.addAll(items(o.optJSONArray("bag")))
            index = o.optInt("index").coerceIn(0, (deck.size - 1).coerceAtLeast(0))
            needNew = o.optInt("need"); acc = o.optDouble("acc", 0.0)
            on = deck.isNotEmpty()
            shuffled = o.optBoolean("shuffled", true)
            listUri = o.optString("list"); listName = o.optString("name")
            deck.filter { it.isNew }.forEach { newUris += it.uri }
            deck.forEach { used += Web.key(it.title, it.artist) }
            seeds = items(o.optJSONArray("seeds"))
            val byUri = seeds.associateBy { it.uri }
            ordered = o.optJSONArray("ordered")?.let { a -> (0 until a.length()).mapNotNull { byUri[a.optString(it)] } }?.takeIf { it.isNotEmpty() } ?: seeds
            lastTrack = deck.getOrNull(index)?.uri.orEmpty()
        }
        seeds.forEach { used += Web.key(it.title, it.sub) }
    }
}
