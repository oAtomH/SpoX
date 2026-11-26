package io.github.oatomh.spox

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.VelocityTracker
import android.view.View
import android.view.WindowInsets
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * SpoX for phones: a full-screen player in the desktop skin's pixel style, laid out like a phone
 * app (big cover, big buttons, tabs at the bottom), not the desktop window shrunk onto the screen.
 * Everything is drawn from the skin at whole-number scales, so the pixels stay square and sharp.
 */
class PlayerView(ctx: Context) : View(ctx) {
    private val skin get() = Skin.get(context)

    enum class Tab(val label: String) { PLAYER("Player"), LYRICS("Lyrics"), QUEUE("Up next"), LIBRARY("Library"), SETTINGS("Settings") }
    var tab = Tab.PLAYER
        set(v) {
            if (field == Tab.LIBRARY) pages.lastOrNull()?.scroll = scroll
            field = v
            scroll = if (v == Tab.LIBRARY) pages.lastOrNull()?.scroll ?: 0f else 0f
            fling = 0f; queueAsked = 0L; selected.clear()
            saveLibrary()
            invalidate()
        }

    /** Frames per second while something moves: Settings > Smoothness. */
    var fps = 30
    var onSettingsAction: ((String) -> Unit)? = null
    var settingsRows: () -> List<Pair<String, String>> = { emptyList() }

    // one skin pixel = u screen pixels; titles a bit bigger, buttons bigger still
    private var u = 4f
    private var tu = 6f
    private var bu = 8f
    private var pad = 16f
    private var insetTop = 0f
    private var insetBottom = 0f

    private val t0 = SystemClock.elapsedRealtime()
    private var animating = false
    /** scrolling by itself (fling, lyrics gliding): full frame rate so it's not choppy */
    private var smooth = false
    private var showRemaining = false

    // slow system lookups, done now and then instead of every frame
    private var access = Player.hasAccess(ctx)
    private var vol = Player.volume(ctx)
    private var volAt = 0L

    // lyrics
    private var lyricsKey = ""
    private var lyrics: Lyrics.Result? = null
    private var lyricsState = "Play something to see lyrics."
    private var lyricsSince = 0L
    private var prefetchFor = ""

    /** The next song's lyrics, looked up while this one plays. */
    private fun prefetchNext() {
        val next = io.github.oatomh.spox.engine.Engine.get(context).now.queue.firstOrNull { !Player.isSkipped(it.uri) } ?: return
        if (next.uri == prefetchFor) return
        prefetchFor = next.uri
        Lyrics.prefetch(next.title, next.artist, next.album, next.durationMs)
    }
    private var wrapKey = ""
    private var wrapCache: List<Pair<Int, String>> = emptyList()

    // cover
    private var artSrc: Bitmap? = null
    private var artPix: Bitmap? = null
    private val pixelPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }

    // lists: scroll in screen pixels, with a fling that slows down by itself
    private var scroll = 0f
    private var fling = 0f
    private var maxScroll = 0f
    private var manualUntil = 0L
    private var lastFrame = 0L

    // touch
    private class Hit(val l: Float, val t: Float, val r: Float, val b: Float, val id: String)
    private val hits = ArrayList<Hit>()
    private var pressed: String? = null
    private var dragging: String? = null
    private var seekPreview = -1L
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var vt: VelocityTracker? = null
    private var seekRect = RectF()
    private var volRect = RectF()
    private var artRect = RectF()
    private var miniRect = RectF()
    private var contentTop = 0f
    private var contentBottom = 0f
    /** the on-screen keyboard's height (SpoX's text boxes sit above it) */
    private var imeBottom = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setOnApplyWindowInsetsListener { _, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                insetTop = b.top.toFloat(); insetBottom = b.bottom.toFloat()
                imeBottom = ins.getInsets(WindowInsets.Type.ime()).bottom.toFloat()
            } else {
                @Suppress("DEPRECATION")
                insetTop = ins.systemWindowInsetTop.toFloat()
                @Suppress("DEPRECATION")
                insetBottom = ins.systemWindowInsetBottom.toFloat()
            }
            invalidate()
            ins
        }
    }

    /** Call when coming back to the app (access may have changed). */
    fun refresh() {
        access = Player.hasAccess(context)
        vol = Player.volume(context)
        invalidate()
    }

    /** Back button: other tabs go back to the player first. */
    fun back(): Boolean {
        if (sheet != null) { closeSheet(); return true }
        if (selected.isNotEmpty()) { selected.clear(); invalidate(); return true }
        if (tab == Tab.LIBRARY && pages.size > 1) { pages.removeAt(pages.size - 1); scroll = 0f; invalidate(); return true }
        if (tab == Tab.PLAYER) return false
        tab = Tab.PLAYER
        return true
    }

    private val playerChanged: () -> Unit = { invalidate() }

    // volume changed by anything (Bluetooth headphones, a car, the phone): show it SpoX's way too
    private var lastVolSeen = -1f
    private val volumeWatch = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: android.content.Intent) {
            if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != android.media.AudioManager.STREAM_MUSIC) return
            val v = Player.volume(context)
            if (kotlin.math.abs(v - lastVolSeen) < 0.001f) return
            lastVolSeen = v
            showVolume(v)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        current = this
        lastVolSeen = Player.volume(context)
        runCatching { context.registerReceiver(volumeWatch, android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION")) }
        Player.listen(playerChanged)
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        runCatching { context.unregisterReceiver(volumeWatch) }
        if (current === this) current = null
        if (tab == Tab.LIBRARY) pages.lastOrNull()?.scroll = scroll
        saveLibrary()
        Player.unlisten(playerChanged)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val d = resources.displayMetrics.density
        u = max(2f, (d * 1.6f).roundToInt().toFloat())
        tu = floor(u * 1.5f)
        pad = 6 * u
        // the five big buttons (24 skin pixels wide each) must fit across with gaps
        bu = max(u, min(u * 2, floor((w - 2 * pad) / (5 * 24 + 4 * 6))))
    }

    // ---- drawing helpers (screen pixels in, skin pixels scaled by k) ----------------------

    private fun spr(cv: Canvas, name: String, x: Float, y: Float, k: Float) = skin.draw(cv, name, x / k, y / k, k)
    private fun sprW(name: String, k: Float) = skin.size(name)[0] * k
    private fun sprH(name: String, k: Float) = skin.size(name)[1] * k
    private fun txt(cv: Canvas, t: String, x: Float, y: Float, k: Float, c: Int) = skin.text(cv, t, x / k, y / k, k, c)
    private fun tw(t: String, k: Float) = skin.textWidth(t) * k
    private fun nine(cv: Canvas, name: String, x: Float, y: Float, w: Float, h: Float, k: Float, corner: Int) =
        skin.nine(cv, name, x / k, y / k, w / k, h / k, k, corner)
    private fun box(cv: Canvas, x: Float, y: Float, w: Float, h: Float, c: Int) = skin.fill(cv, x, y, w, h, 1f, c)
    private fun fit(t: String, w: Float, k: Float) = skin.fit(t, (w / k).toInt())
    private fun hit(l: Float, t: Float, r: Float, b: Float, id: String) { hits += Hit(l, t, r, b, id) }

    private val cTitle get() = skin.color("title")
    private val cInfo get() = skin.color("info")
    private val cList get() = skin.color("list")
    private val cDim get() = skin.color("list_dim")
    private val cHeader get() = skin.color("header")
    private val cLine get() = skin.color("list_selected_bg", Color.rgb(28, 53, 39))
    private val cBg get() = skin.color("anim_bg", Color.rgb(10, 10, 11))

    /** One line of text that slides sideways when it doesn't fit. */
    private fun marquee(cv: Canvas, t: String, x: Float, y: Float, w: Float, k: Float, c: Int, now: Long, center: Boolean = false) {
        val width = tw(t, k)
        if (width <= w) {
            txt(cv, t, if (center) x + floor((w - width) / 2 / k) * k else x, y, k, c)
            return
        }
        animating = true
        cv.save()
        cv.clipRect(x, y - 2 * k, x + w, y + 14 * k)
        val period = width + 24 * k
        val speed = 14 * k
        val cyc = 2.5f + period / speed
        val ph = ((now - t0) / 1000f) % cyc
        val off = if (ph < 2.5f) 0f else floor((ph - 2.5f) * speed / k) * k
        txt(cv, t, x - off, y, k, c)
        txt(cv, t, x - off + period, y, k, c)
        cv.restore()
    }

    private fun clock(ms: Long): String {
        val s = max(0, ms) / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    // ---- frame ----------------------------------------------------------------------------

    override fun onDraw(cv: Canvas) {
        val st = Player.state
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrame == 0L) 16f else min(100f, (now - lastFrame).toFloat())
        lastFrame = now
        lastDt = dt
        hits.clear()
        miniRect = RectF()
        cv.drawColor(cBg)

        // fling
        if (abs(fling) > 0.05f && dragging != "list") {
            scroll += fling * dt
            fling *= Math.pow(0.996, dt.toDouble()).toFloat()
            if (scroll < 0 || scroll > maxScroll) fling = 0f
            animating = true
            smooth = true
        } else if (dragging != "list") fling = 0f

        // bottom: tab bar, and above it a small player on the other tabs
        val tabH = 34 * u
        val tabTop = height - insetBottom - tabH
        drawTabs(cv, tabTop, tabH)
        var bottom = tabTop
        if (tab != Tab.PLAYER && st.hasTrack) {
            val mh = 30 * u
            drawMini(cv, st, bottom - mh, mh, now)
            bottom -= mh
        }
        contentTop = insetTop + 4 * u
        contentBottom = bottom

        if (!access && tab != Tab.SETTINGS) drawAccess(cv)
        else when (tab) {
            Tab.PLAYER -> drawPlayer(cv, st, now)
            Tab.LYRICS -> drawLyrics(cv, st, now)
            Tab.QUEUE -> drawQueue(cv, now)
            Tab.LIBRARY -> drawLibrary(cv)
            Tab.SETTINGS -> drawSettings(cv)
        }
        if (Player.fromEngine && st.playing) prefetchNext()
        drawVolumeOsd(cv, now)
        sheet?.let { drawSheet(cv, it, now) }

        // Pixel art moves in whole pixels, so drawing at the phone's full 120 Hz mostly repeats the
        // same picture and costs battery. Dragging stays at full speed; otherwise the chosen rate.
        when {
            dragging != null || smooth -> postInvalidateOnAnimation()
            st.playing || animating -> postInvalidateDelayed(1000L / fps)
            else -> postInvalidateDelayed(500)
        }
        animating = false
        smooth = false
    }

    private fun header(cv: Canvas, title: String, sub: String = ""): Float {
        var y = contentTop + 4 * u
        txt(cv, title, pad, y, tu, cHeader)
        y += 12 * tu
        if (sub.isNotEmpty()) {
            txt(cv, fit(sub, width - 2 * pad, u), pad, y, u, cDim)
            y += 12 * u
        }
        return y + 2 * u
    }

    private fun drawTabs(cv: Canvas, top: Float, h: Float) {
        box(cv, 0f, top, width.toFloat(), height - top, skin.color("menu_bg", Color.rgb(17, 17, 19)))
        box(cv, 0f, top, width.toFloat(), max(1f, u / 2), cLine)
        val tabs = Tab.values()
        val w = width / tabs.size.toFloat()
        for ((i, t) in tabs.withIndex()) {
            val on = t == tab
            val cx = w * i + w / 2
            val iconY = top + 5 * u
            when (t) {
                Tab.PLAYER -> spr(cv, "icon", cx - 8 * u, iconY, u)
                Tab.LYRICS -> spr(cv, if (on) "tab_lyrics_on" else "tab_lyrics", cx - 8 * u, iconY, u)
                Tab.QUEUE -> spr(cv, if (on) "tab_queue_on" else "tab_queue", cx - 8 * u, iconY, u)
                Tab.LIBRARY -> spr(cv, if (on) "tab_playlist_on" else "tab_playlist", cx - 8 * u, iconY, u)
                Tab.SETTINGS -> spr(cv, if (on) "btn_menu_down" else "btn_menu", cx - 8 * u, iconY, 2 * u)
            }
            val lw = tw(t.label, u)
            txt(cv, t.label, cx - floor(lw / 2 / u) * u, iconY + 19 * u, u, if (on) cTitle else cDim)
            hit(w * i, top, w * (i + 1), height.toFloat(), "tab:${t.name}")
        }
    }

    private fun drawMini(cv: Canvas, st: Player.State, top: Float, h: Float, now: Long) {
        box(cv, 0f, top, width.toFloat(), h, skin.color("menu_bg", Color.rgb(17, 17, 19)))
        box(cv, 0f, top, width.toFloat(), max(1f, u / 2), cLine)
        val a = h - 8 * u
        drawArt(cv, st, RectF(pad, top + 4 * u, pad + a, top + 4 * u + a))
        // previous / play / next on the right (and swipe the bar sideways, like Spotify)
        val bw = sprW("btn_play", u)
        val by = top + (h - sprH("btn_play", u)) / 2
        val nx = width - pad - bw
        val bx = nx - bw - 2 * u
        val px = bx - bw - 2 * u
        val btn = if (st.playing) "btn_pause" else "btn_play"
        spr(cv, if (pressed == "mini_prev") "btn_prev_down" else "btn_prev", px, by, u)
        spr(cv, if (pressed == "mini_play") "${btn}_down" else btn, bx, by, u)
        spr(cv, if (pressed == "mini_next") "btn_next_down" else "btn_next", nx, by, u)
        miniRect = RectF(0f, top, width.toFloat(), top + h)
        // heart and + / - in front of them (Settings → Mini player buttons)
        var left = px
        if (Player.fromEngine && Web.signedIn && miniExtras(context)) {
            checkLiked()
            val lw = sprW("btn_like", u)
            val lby = top + (h - sprH("btn_like", u)) / 2
            val uri = Player.engineTrackUri()
            val inPl = if (uri != null) Web.inPlaylist(plusList(), uri) else null
            if (inPl != null) {
                val ax = left - lw - 2 * u
                val b = if (inPl) "btn_remove" else "btn_add"
                spr(cv, if (pressed == "plus") "${b}_down" else b, ax, lby, u)
                hit(ax - u, top, left - u, top + h, "plus")
                left = ax
            }
            val lx = left - lw - 2 * u
            spr(cv, if (liked == true || pressed == "like") "btn_like_down" else "btn_like", lx, lby, u)
            hit(lx - u, top, left - u, top + h, "like")
            left = lx
        }
        val tx = pad + a + 6 * u
        val textW = left - tx - 6 * u
        marquee(cv, st.title, tx, top + 6 * u, textW, u, cTitle, now)
        txt(cv, fit(st.artist, textW, u), tx, top + 17 * u, u, cInfo)
        // progress line along the top edge
        if (st.durationMs > 0) box(cv, 0f, top, width * (st.position(now).toFloat() / st.durationMs).coerceIn(0f, 1f), max(1f, u / 2), cTitle)
        hit(0f, top, left - 2 * u, top + h, "tab:PLAYER")
        hit(px - 2 * u, top, bx - u, top + h, "mini_prev")
        hit(bx - u, top, nx - u, top + h, "mini_play")
        hit(nx - u, top, width.toFloat(), top + h, "mini_next")
    }

    private fun drawArt(cv: Canvas, st: Player.State, r: RectF) {
        val art = st.art
        if (art != null) {
            if (art !== artSrc) {
                artSrc = art
                val n = skin.json.optInt("art_pixels", 24) * 2
                artPix = Bitmap.createScaledBitmap(art, n, n, true)
            }
            artPix?.let { cv.drawBitmap(it, null, r, pixelPaint) }
        } else {
            skin.sprite("art_empty")?.let { cv.drawBitmap(it, null, r, pixelPaint) }
        }
    }

    private fun drawAccess(cv: Canvas) {
        var y = header(cv, "Welcome to SpoX")
        val lines = listOf(
            "SpoX shows and controls what Spotify",
            "is playing. For that it needs",
            "notification access.",
            "",
            "Android may warn you: SpoX only",
            "reads the music player, nothing else.",
        )
        for (l in lines) { txt(cv, fit(l, width - 2 * pad, u), pad, y, u, cList); y += 12 * u }
        y += 8 * u
        val bh = 22 * u
        nine(cv, "list_bg", pad, y, width - 2 * pad, bh, u, 4)
        val label = "Allow notification access"
        txt(cv, label, (width - tw(label, u)) / 2, y + 7 * u, u, cTitle)
        hit(pad, y, width - pad, y + bh, "set:Notification access")
    }

    // ---- player tab ---------------------------------------------------------------------

    private fun drawPlayer(cv: Canvas, st: Player.State, now: Long) {
        val w = width - 2 * pad
        val top = contentTop
        // header: where it plays (SpoX Phone, your PC, a speaker) and through what (Bluetooth, car...)
        val out = Player.output(context)
        val elsewhere = if (!Player.fromEngine) otherDevice() else ""
        val src = when {
            st.app == "io.github.oatomh.spox.engine" -> "Playing on SpoX Phone"
            elsewhere.isNotEmpty() -> "Playing on $elsewhere"
            st.app == "com.spotify.music" || st.app == "com.spotify.lite" -> "Playing on Spotify"
            st.app.isEmpty() -> "SpoX"
            else -> "Playing"
        }
        var hx = pad
        val iconCol = if (out.kind == "phone") cDim else cTitle
        if (st.hasTrack || Player.fromEngine) {
            val ic = icon(out.kind)
            drawIcon(cv, ic, hx, top + 3 * u, u, iconCol)
            hx += (ic[0].length + 4) * u
        }
        val lineW = width - pad - 3 * sprW("btn_link", u) - 20 * u - hx
        val label = fit(src + if (out.kind != "phone" && out.name.isNotEmpty()) " · ${out.name}" else "", lineW - 10 * u, u)
        txt(cv, label, hx, top + 4 * u, u, cDim)
        if (Web.signedIn || Player.fromEngine) {
            // a small "v": tap to move the music to another device
            val ax = hx + tw(label, u) + 4 * u
            for (i in 0..2) box(cv, ax + i * u, top + 8 * u + i * u, (5 - 2 * i) * u, u, cDim)
            hit(0f, top - 4 * u, ax + 10 * u, top + 16 * u, "devices")
        }
        val lw = sprW("btn_link", u)
        if (Player.fromEngine) {
            // share the song, and like it (heart) when signed in to Spotify
            spr(cv, if (pressed == "share") "btn_link_down" else "btn_link", width - pad - lw, top, u)
            hit(width - pad - lw - 2 * u, top - 4 * u, width.toFloat(), top + 20 * u, "share")
            if (Web.signedIn) {
                checkLiked()
                val lx = width - pad - 2 * lw - 6 * u
                spr(cv, if (liked == true || pressed == "like") "btn_like_down" else "btn_like", lx, top, u)
                hit(lx - 2 * u, top - 4 * u, lx + lw + 2 * u, top + 20 * u, "like")
                // + / -: add the song to the playlist that's playing, or take it out
                val uri = Player.engineTrackUri()
                val inPl = if (uri != null) Web.inPlaylist(plusList(), uri) else null
                if (inPl != null) {
                    val px = lx - lw - 6 * u
                    val b = if (inPl) "btn_remove" else "btn_add"
                    spr(cv, if (pressed == "plus") "${b}_down" else b, px, top, u)
                    hit(px - 2 * u, top - 4 * u, px + lw + 2 * u, top + 20 * u, "plus")
                }
            }
        } else {
            spr(cv, if (pressed == "link") "btn_link_down" else "btn_link", width - pad - lw, top, u)
            hit(width - pad - lw - 4 * u, top - 4 * u, width.toFloat(), top + 20 * u, "link")
        }

        // everything below the cover, from the bottom up
        val ctrlH = 16 * bu
        val volH = 8 * u
        val textBlock = 10 * tu + 4 * u + 10 * u + 3 * u + 10 * u
        val below = 8 * u + textBlock + 10 * u + 8 * u + 4 * u + 10 * u + 8 * u + ctrlH + 10 * u + volH
        val visMin = 20 * u
        val avail = contentBottom - (top + 20 * u) - below - visMin - 6 * u
        val a = floor(min(w, max(48 * u, avail)) / u) * u
        val ax = (width - a) / 2
        val ay = top + 20 * u
        nine(cv, "list_bg", ax - 3 * u, ay - 3 * u, a + 6 * u, a + 6 * u, u, 4)
        artRect = RectF(ax, ay, ax + a, ay + a)
        drawArt(cv, st, artRect)
        hit(ax, ay, ax + a, ay + a, "art")

        var y = ay + a + 10 * u
        val title = if (st.hasTrack) st.title else "Nothing playing"
        marquee(cv, title, pad, y, w, tu, cTitle, now, center = true)
        y += 10 * tu + 4 * u
        marquee(cv, if (st.hasTrack) st.artist else "Play something in Spotify", pad, y, w, u, cInfo, now, center = true)
        y += 10 * u + 3 * u
        if (st.album.isNotEmpty()) marquee(cv, st.album, pad, y, w, u, skin.color("album", cDim), now, center = true)
        y += 10 * u + 8 * u

        // seek bar, with the times under it
        val dur = st.durationMs
        val pos = if (dragging == "seek" && seekPreview >= 0) seekPreview else st.position(now)
        seekRect = RectF(pad, y, pad + w, y + 8 * u)
        nine(cv, "seek_track", pad, y, w, 8 * u, u, 2)
        val f = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        val fw = floor(w * f / u) * u
        if (fw > 0) nine(cv, "seek_fill", pad, y, fw, 8 * u, u, 2)
        if (dur > 0) spr(cv, "seek_thumb", (pad + fw - 8 * u).coerceIn(pad, pad + w - 16 * u), y, u)
        y += 8 * u + 4 * u
        if (st.hasTrack) {
            txt(cv, clock(pos), pad, y, u, cDim)
            val right = if (showRemaining) "-" + clock(dur - pos) else clock(dur)
            txt(cv, right, pad + w - tw(right, u), y, u, cDim)
            hit(pad + w - 40 * u, y - 2 * u, pad + w, y + 12 * u, "time")
        }
        y += 10 * u + 8 * u

        // the big buttons
        val names = listOf("btn_shuffle", "btn_prev", if (st.playing) "btn_pause" else "btn_play", "btn_next", "btn_repeat")
        val bw = 24 * bu
        val gap = (w - 5 * bw) / 4
        for ((i, b) in names.withIndex()) {
            val bx = floor((pad + i * (bw + gap)) / u) * u
            val active = (b == "btn_shuffle" && st.shuffle) || (b == "btn_repeat" && st.repeat)
            val sprite = when {
                pressed == b && skin.sprite("${b}_down") != null -> "${b}_down"
                b == "btn_shuffle" && Smart.mode(context) == "spox" && Player.fromEngine && skin.sprite("btn_shuffle_smart") != null -> "btn_shuffle_smart"
                active && skin.sprite("${b}_on") != null -> "${b}_on"
                active && skin.sprite("${b}_down") != null -> "${b}_down"
                else -> b
            }
            spr(cv, sprite, bx, y, bu)
            hit(bx - gap / 2, y - 6 * u, bx + bw + gap / 2, y + ctrlH + 6 * u, b)
        }
        y += ctrlH + 10 * u

        // volume = the phone's media volume. A speaker on the left mutes / unmutes; the volume
        // buttons show their change right here (the pop-up is for the other tabs)
        if (now - volAt > 1000 && dragging != "vol") { vol = Player.volume(context); volAt = now }
        val muted = vol <= 0.001f
        val flash = now < volOsdUntil
        val spk = if (muted) ICON_MUTED else ICON_SPEAKER
        val iconY = y + (volH - spk.size * u) / 2
        drawIcon(cv, spk, pad, floor(iconY / u) * u, u, if (muted || flash || pressed == "mute") cTitle else cDim)
        hit(0f, y - 8 * u, pad + 12 * u, y + volH + 8 * u, "mute")
        // the number is always there; it lights up while the volume changes
        val pctW = tw("100", u) + 6 * u
        val vbx = pad + 13 * u
        val vbw = w - 13 * u - pctW
        volRect = RectF(vbx, y, vbx + vbw, y + volH)
        nine(cv, "vol_track", vbx, y, vbw, volH, u, 2)
        val vw = floor(vbw * vol / u) * u
        if (vw > 0) nine(cv, "vol_fill", vbx, y, vw, volH, u, 2)
        spr(cv, "vol_thumb", (vbx + vw - 4 * u).coerceIn(vbx, vbx + vbw - 8 * u), y, u)
        val pct = "${(vol * 100).roundToInt()}"
        txt(cv, pct, pad + w - tw(pct, u), y, u, if (flash || muted) cTitle else cDim)
        if (flash) animating = true
        y += volH + 8 * u

        // visualizer in the space that's left
        val vh = floor((contentBottom - y - 4 * u) / u) * u
        if (vh >= 12 * u) drawVis(cv, st, now, pad, y, w, vh)
    }

    private var spec = FloatArray(0)
    private var level = FloatArray(0)
    private var peak = FloatArray(0)
    private var peakHold = LongArray(0)
    private var visAt = 0L
    private var visColors: IntArray? = null
    private var visFor: Skin? = null
    /** Bar colour at [frac] (0 = top of the bar area, 1 = bottom): the theme's visualizer colours
     *  in a few hard pixel-art steps (no smooth blend), richer and turning in hue towards the top,
     *  like a vaporwave sunset. Worked out once per theme. */
    private fun visCol(frac: Float): Int {
        val b = skin.sprite("vis_colors") ?: return cTitle
        if (visFor !== skin) { visFor = skin; visColors = null }
        val c = visColors ?: run {
            val raw = IntArray(b.height) { b.getPixel(0, it) }
            val steps = 6
            IntArray(steps) { i ->
                val f = (i + 0.5f) / steps // 0 top .. 1 bottom
                val base = raw[min(raw.size - 1, (f * raw.size).toInt())]
                val hsv = FloatArray(3)
                Color.colorToHSV(base, hsv)
                hsv[0] = (hsv[0] - 50f * (1f - f) + 360f) % 360f // tops turn (pink / violet side)
                hsv[1] = min(1f, hsv[1] * 1.35f + 0.1f) // more colour
                hsv[2] = min(1f, hsv[2] * (1.05f + 0.15f * (1f - f))) // tops brighter
                Color.HSVToColor(Color.alpha(base), hsv)
            }.also { visColors = it }
        }
        return c[min(c.size - 1, (frac * c.size).toInt().coerceAtLeast(0))]
    }

    private fun drawVis(cv: Canvas, st: Player.State, now: Long, x: Float, y: Float, w: Float, h: Float) {
        val bw = 3 * u
        val gap = u
        val bars = ((w + gap) / (bw + gap)).toInt()
        val x0 = x + floor((w - (bars * (bw + gap) - gap)) / 2 / u) * u
        val t = (now - t0) / 1000f
        val rows = (h / u).toInt()
        // the real sound when SpoX Engine plays it; a gentle stand-in for the Spotify app
        if (spec.size != bars) { spec = FloatArray(bars); level = FloatArray(bars); peak = FloatArray(bars); peakHold = LongArray(bars) }
        val real = Player.fromEngine && st.playing && io.github.oatomh.spox.engine.Engine.get(context).spectrum(spec)
        val dt = min(100f, (now - visAt).toFloat()).coerceAtLeast(1f)
        visAt = now
        val mirror = context.getSharedPreferences("spox", 0).getString("vis_style", "mirror") == "mirror"
        for (i in 0 until bars) {
            val xr = i / bars.toFloat()
            val target = when {
                real -> spec[i]
                st.playing -> (0.15f + 0.55f * (sin(t * 3.1f + xr * 9f) * 0.5f + 0.5f) * (sin(t * 5.3f - xr * 4f) * 0.5f + 0.5f)) * (1f - abs(xr - 0.5f) * 0.6f)
                else -> 0f
            }
            // bars jump up at once and fall smoothly; a peak pixel hangs on top, then drops
            level[i] = if (target > level[i]) target else max(target, level[i] - dt / 600f)
            if (level[i] >= peak[i]) { peak[i] = level[i]; peakHold[i] = now + 350 }
            else if (now > peakHold[i]) peak[i] = max(level[i], peak[i] - dt / 900f)
            val bx = x0 + i * (bw + gap)
            if (mirror) {
                // like SpoX on PC: bars grow up from the middle, with a dimmer reflection below
                val half = rows / 2
                val mid = y + half * u
                val bh = max(1, (level[i] * half).toInt())
                for (yy in 0 until bh) {
                    val c = visCol(1f - yy.toFloat() / half)
                    box(cv, bx, mid - (yy + 1) * u, bw, u, c)
                    box(cv, bx, mid + yy * u, bw, u, (c and 0x00ffffff) or (0x8c shl 24))
                }
            } else {
                val bh = max(1, (level[i] * rows).toInt())
                for (yy in 0 until bh) box(cv, bx, y + h - (yy + 1) * u, bw, u, visCol(1f - yy.toFloat() / rows))
                val py = (peak[i] * rows).toInt()
                if (py > bh) box(cv, bx, y + h - (py + 1) * u, bw, u, skin.color("vis_peak", cHeader))
            }
        }
        if (st.playing || level.any { it > 0.01f }) animating = true
    }

    // ---- lists ----------------------------------------------------------------------------

    private fun drawLyrics(cv: Canvas, st: Player.State, now: Long) {
        val top = header(cv, "Lyrics", if (st.hasTrack) "${st.title} - ${st.artist}" else "")
        val key = "${st.artist}|${st.title}"
        if (key != lyricsKey && st.hasTrack) {
            lyricsKey = key
            lyricsSince = now
            scroll = 0f
            wrapKey = ""
            // already looked up (prefetched while the last song played): swap straight over, no
            // "loading" in between
            var instant = false
            Lyrics.fetch(st.title, st.artist, st.album, st.durationMs) { r ->
                val apply = {
                    if (key == lyricsKey) {
                        lyrics = r
                        lyricsState = if (r == null) "No lyrics for this song." else ""
                        invalidate()
                    }
                }
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) { apply(); instant = true } else post(apply)
            }
            if (!instant) { lyrics = null; lyricsState = "" }
        }
        val l = lyrics
        if (l == null) {
            // only say "looking" if it takes a moment (a quick answer shouldn't flash a message)
            val msg = lyricsState.ifEmpty { if (now - lyricsSince > 900) "Looking for lyrics..." else "" }
            if (msg.isNotEmpty()) txt(cv, msg, (width - tw(msg, u)) / 2, top + 20 * u, u, cDim) else animating = true
            return
        }
        val k = tu
        val lh = 13 * k
        val maxW = width - 2 * pad
        val wrapped = wrapCache.takeIf { wrapKey == "$lyricsKey|$maxW|$k" } ?: wrap(l, maxW, k).also {
            wrapCache = it; wrapKey = "$lyricsKey|$maxW|$k"
        }
        // a quarter second early reads naturally
        val p = st.position(now) + 250
        val current = if (l.synced) l.lines.indexOfLast { it.first <= p } else -1
        val curRow = wrapped.indexOfFirst { it.first == current }
        val viewH = contentBottom - top
        maxScroll = max(0f, wrapped.size * lh - viewH + 20 * u)
        // follow the song unless you scrolled by hand a moment ago (glides there, no jumps)
        if (curRow >= 0 && SystemClock.elapsedRealtime() > manualUntil && dragging != "list") {
            val target = (curRow * lh - viewH * 0.35f).coerceIn(0f, maxScroll)
            if (abs(target - scroll) > 1f) { scroll += (target - scroll) * (1f - Math.pow(0.82, dt60()).toFloat()); animating = true; smooth = true } else scroll = target
        }
        scroll = scroll.coerceIn(0f, maxScroll)
        cv.save()
        cv.clipRect(0f, top, width.toFloat(), contentBottom)
        val first = max(0, (scroll / lh).toInt() - 1)
        for (i in first until wrapped.size) {
            val ry = top + i * lh - scroll
            if (ry > contentBottom) break
            val (li, text) = wrapped[i]
            val col = when {
                li == current -> skin.color("lyric_current")
                !l.synced || li < current -> skin.color("lyric")
                else -> skin.color("lyric_next", skin.color("lyric"))
            }
            txt(cv, text, pad, ry, k, col)
            // tap a line to jump there
            if (l.synced && text.isNotEmpty() && ry + lh > top) hit(0f, maxOf(top, ry - 2 * u), width.toFloat(), minOf(contentBottom, ry + lh - 2 * u), "lyric:${l.lines[li].first}")
        }
        cv.restore()
    }



    /** Lyrics lines cut to the screen width once per song (not every frame). */
    private fun wrap(l: Lyrics.Result, maxW: Float, k: Float): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        l.lines.forEachIndexed { i, (_, text) ->
            var rest = text
            if (rest.isEmpty()) out += i to ""
            while (rest.isNotEmpty()) {
                var cut = rest
                while (tw(cut, k) > maxW && cut.contains(' ')) cut = cut.substringBeforeLast(' ')
                if (tw(cut, k) > maxW) cut = fit(cut, maxW, k).dropLast(3)
                if (cut.isEmpty()) break
                out += i to cut
                rest = rest.removePrefix(cut).trimStart()
            }
        }
        return out
    }

    /** A scrolling list of two-line rows. */
    private fun drawRows(cv: Canvas, top: Float, rows: List<Triple<String, String, String>>, empty: String, valueRight: Boolean, accent: Set<String> = emptySet()) {
        if (rows.isEmpty()) {
            txt(cv, empty, (width - tw(empty, u)) / 2, top + 20 * u, u, cDim)
            return
        }
        val rh = if (valueRight) 26 * u else 30 * u
        val viewH = contentBottom - top
        maxScroll = max(0f, rows.size * rh - viewH)
        scroll = scroll.coerceIn(0f, maxScroll)
        cv.save()
        cv.clipRect(0f, top, width.toFloat(), contentBottom)
        for ((i, r) in rows.withIndex()) {
            val ry = top + i * rh - scroll
            if (ry + rh < top) continue
            if (ry > contentBottom) break
            val (a, b, id) = r
            if (a.startsWith("#")) {
                // section heading
                txt(cv, fit(a.drop(1), width - 2 * pad, u), pad, ry + 12 * u, u, cHeader)
                continue
            }
            if (pressed == id) box(cv, 0f, ry, width.toFloat(), rh, cLine)
            if (valueRight) {
                val vw = tw(b, u)
                txt(cv, fit(a, width - 2 * pad - vw - 8 * u, u), pad, ry + 9 * u, u, cList)
                txt(cv, b, width - pad - vw, ry + 9 * u, u, cDim)
            } else {
                txt(cv, fit(a, width - 2 * pad, u), pad, ry + 5 * u, u, if (id in accent) skin.color("list_new", cTitle) else cList)
                txt(cv, fit(b, width - 2 * pad, u), pad, ry + 16 * u, u, cDim)
            }
            box(cv, pad, ry + rh - max(1f, u / 2), width - 2 * pad, max(1f, u / 2), cLine)
            if (id.isNotEmpty()) hit(0f, max(top, ry), width.toFloat(), min(contentBottom, ry + rh), id)
            // songs in the library get a "+" on the right: add to queue
            if (id.startsWith("lib:") && libItem(id)?.kind == "track") {
                val px = width - pad - 12 * u
                box(cv, px - 4 * u, ry + 4 * u, 20 * u, rh - 8 * u, if (pressed == "q$id") cLine else cBg)
                txt(cv, "+", px + 2 * u, ry + 10 * u, u, cTitle)
                hit(px - 6 * u, max(top, ry), width.toFloat(), min(contentBottom, ry + rh), "q$id")
            }
        }
        cv.restore()
    }

    private var queueAsked = 0L
    /** Up next songs you've picked (long-press a song to start picking). */
    private val selected = LinkedHashSet<String>()
    private var qRows: List<Player.QRow> = emptyList()

    private fun drawQueue(cv: Canvas, now: Long) {
        // keep it the same as on your other devices: ask again every few seconds while it's open
        if (Player.fromEngine && SystemClock.elapsedRealtime() - queueAsked > 5000) {
            queueAsked = SystemClock.elapsedRealtime()
            io.github.oatomh.spox.engine.Engine.get(context).command("get_queue", mapOf("limit" to 0))
        }
        val rows = Player.queueRows()
        qRows = rows
        selected.retainAll(rows.map { it.uri }.toSet())
        val newCount = rows.count { it.neverHeard || it.outside }
        // header: what's playing it, and the new-songs mix (tap to change it)
        var y = contentTop + 4 * u
        txt(cv, "Up next", pad, y, tu, cHeader)
        val mode = when {
            Smart.busy.isNotEmpty() -> Smart.busy
            Smart.mode(context) == "spox" -> "SpoX shuffle · ${Smart.ratio(context)}% new  v"
            Smart.mode(context) == "shuffle" -> "Shuffle"
            Smart.on -> "In order"
            else -> ""
        }
        if (mode.isNotEmpty()) {
            val mw = tw(mode, u)
            val spox = Smart.mode(context) == "spox"
            txt(cv, mode, width - pad - mw, y + 3 * u, u, if (spox) cTitle else cDim)
            if (spox) hit(width - pad - mw - 8 * u, y - 4 * u, width.toFloat(), y + 16 * u, "qmix")
        }
        y += 12 * tu
        if (selected.isNotEmpty()) {
            // picking songs: what to do with them
            val bh = 18 * u
            box(cv, 0f, y - 2 * u, width.toFloat(), bh, cLine)
            txt(cv, "${selected.size} picked", pad, y + 3 * u, u, cTitle)
            var bx = width - pad
            for ((label, id) in listOf("Cancel" to "qcancel", "Remove" to "qremove", "Play next" to "qnext")) {
                val w = tw(label, u) + 12 * u
                bx -= w
                if (pressed == id) box(cv, bx, y - 2 * u, w, bh, cBg)
                txt(cv, label, bx + 6 * u, y + 3 * u, u, if (id == "qremove") skin.color("list_new", cTitle) else cList)
                hit(bx, y - 4 * u, bx + w, y + bh, id)
            }
            y += bh + 2 * u
        } else {
            val mins = rows.size
            val sub = when {
                rows.isEmpty() -> ""
                newCount > 0 -> "$mins songs · $newCount new · hold to pick, swipe to remove"
                else -> "$mins songs · hold to pick, swipe to remove"
            }
            if (sub.isNotEmpty()) txt(cv, fit(sub, width - 2 * pad, u), pad, y, u, cDim)
            y += 14 * u
        }
        val top = y
        if (rows.isEmpty()) {
            val msg = if (Smart.busy.isNotEmpty()) Smart.busy else if (Smart.on) "Planning what's next..." else "Nothing queued."
            txt(cv, msg, (width - tw(msg, u)) / 2, top + 20 * u, u, cDim)
            return
        }
        // rows, with a heading where the group changes
        val rh = 28 * u
        val gh = 18 * u
        data class Line(val y: Float, val h: Float, val head: String?, val row: Player.QRow?)
        val lines = ArrayList<Line>(rows.size + 4)
        var yy = 0f
        var group = ""
        for (q in rows) {
            if (q.group.isNotEmpty() && q.group != group) { lines += Line(yy, gh, q.group, null); yy += gh; group = q.group }
            lines += Line(yy, rh, null, q); yy += rh
        }
        val viewH = contentBottom - top
        maxScroll = max(0f, yy - viewH + 8 * u)
        scroll = scroll.coerceIn(0f, maxScroll)
        val accent = skin.color("list_new", cTitle)
        cv.save()
        cv.clipRect(0f, top, width.toFloat(), contentBottom)
        for (ln in lines) {
            val ry = top + ln.y - scroll
            if (ry + ln.h < top) continue
            if (ry > contentBottom) break
            if (ln.head != null) {
                txt(cv, fit(ln.head, width - 2 * pad, u), pad, ry + 7 * u, u, cHeader)
                continue
            }
            val q = ln.row!!
            val id = "qrow:${q.id}:${q.uri}"
            val isSel = q.uri in selected
            val isNew = q.neverHeard || q.outside
            // swiping this row left: "Remove" shows underneath, the row slides with your finger
            val dx = if (swipeId == id) min(0f, swipeDx) else 0f
            if (dx < 0f) {
                box(cv, 0f, ry, width.toFloat(), rh, cLine)
                val lbl = if (-dx > width * 0.3f) "Let go to remove" else "Remove"
                txt(cv, lbl, width - pad - tw(lbl, u), ry + 10 * u, u, skin.color("list_new", cTitle))
                cv.save()
                cv.clipRect(0f, ry, width + dx, ry + rh)
                cv.translate(dx, 0f)
                box(cv, 0f, ry, width.toFloat(), rh, cBg)
            }
            if (isSel || pressed == id) box(cv, 0f, ry, width.toFloat(), rh, cLine)
            var tx = pad
            if (selected.isNotEmpty()) {
                // tick box while picking
                box(cv, pad, ry + 9 * u, 10 * u, 10 * u, if (isSel) cTitle else cDim)
                box(cv, pad + u, ry + 10 * u, 8 * u, 8 * u, if (isSel) cTitle else cBg)
                if (isSel) drawIcon(cv, ICON_CHECK, pad + 2 * u, ry + 12 * u, u, cBg)
                tx += 16 * u
            } else if (isNew) {
                // a bar on the left: songs you haven't heard stand out at a glance
                box(cv, 0f, ry + 4 * u, 2 * u, rh - 8 * u, accent)
            }
            // tag on the right: NEW = never heard; "pick" = SpoX/Spotify brought it in
            val tags = context.getSharedPreferences("spox", 0).getBoolean("show_new", true)
            val tag = when { !tags -> ""; q.neverHeard -> "NEW"; q.outside -> "pick"; else -> "" }
            var tagW = 0f
            if (tag.isNotEmpty()) {
                tagW = tw(tag, u) + 8 * u
                val tx2 = width - pad - tagW
                if (tag == "NEW") {
                    box(cv, tx2, ry + 8 * u, tagW, 12 * u, accent)
                    txt(cv, tag, tx2 + 4 * u, ry + 9 * u, u, cBg)
                } else txt(cv, tag, tx2 + 4 * u, ry + 9 * u, u, cDim)
            }
            val textW = width - pad - tx - tagW - 6 * u
            txt(cv, fit(q.title, textW, u), tx, ry + 5 * u, u, if (isNew) accent else cList)
            txt(cv, fit(q.artist, textW, u), tx, ry + 16 * u, u, cDim)
            box(cv, pad, ry + rh - max(1f, u / 2), width - 2 * pad, max(1f, u / 2), cLine)
            if (dx < 0f) cv.restore()
            hit(0f, max(top, ry), width.toFloat(), min(contentBottom, ry + rh), id)
        }
        cv.restore()
    }

    // swipe a song in Up next to the left to take it out
    private var swipeId: String? = null
    private var swipeDx = 0f

    private fun queueTap(id: String) {
        val parts = id.split(":", limit = 3)
        val qid = parts.getOrNull(1)?.toLongOrNull() ?: return
        val uri = parts.getOrNull(2).orEmpty()
        if (selected.isNotEmpty() && uri.isNotEmpty()) {
            if (!selected.remove(uri)) selected += uri
            return
        }
        Player.playQueueItem(qid, uri)
    }

    private fun queueLong(id: String) {
        val uri = id.split(":", limit = 3).getOrNull(2).orEmpty()
        if (uri.isEmpty()) return
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (!selected.remove(uri)) selected += uri
        invalidate()
    }

    private fun queueAction(id: String) {
        when (id) {
            "qcancel" -> selected.clear()
            "qremove" -> {
                val n = selected.size
                Player.removeFromQueue(selected.toSet())
                selected.clear()
                say("Removed $n from Up next")
            }
            "qnext" -> {
                val pick = qRows.filter { it.uri in selected }
                if (Smart.on) Smart.playNext(pick.map { Smart.Entry(it.uri, it.title, it.artist, if (it.outside) "new" else "user") })
                else {
                    // Spotify's queue: queue them again (the old spot is skipped when it comes up)
                    Player.removeFromQueue(pick.map { it.uri }.toSet())
                    pick.forEach { Player.addToQueue(it.uri, it.title, it.artist) }
                }
                selected.clear()
                say("Playing next: ${pick.size} songs")
            }
            "qmix" -> mixSheet()
        }
    }

    /** Colour packs and themes, as a list to pick from. */
    fun packSheet(onPick: (String) -> Unit) {
        val cur = Skin.packName(context)
        show(Sheet("Theme", emptyList(), Skin.PACKS.map { p ->
            SheetItem(p.name, if (p.pal != null) "theme" else "pack", p.name == cur) { onPick(p.name) }
        }))
    }

    /** SpoX shuffle: how many new songs (0% = just your list, 100% = only new songs). */
    fun mixSheet() {
        val r = Smart.ratio(context)
        show(Sheet("New songs in SpoX shuffle", listOf("New = not in your Liked Songs, this list", "or what you played lately."),
            listOf(0 to "Just my list", 25 to "A few new songs", 50 to "Half new", 75 to "Mostly new", 100 to "Only new songs").map { (v, l) ->
                SheetItem(l, "$v%", v == r) { Smart.setRatio(context, v) }
            }))
    }

    // ---- library: playlists, Liked Songs, search (Spotify account) -----------------------------

    /**
     * One list in the library. [sort]: "added" (newest first), "title", "artist", "album",
     * "length" or "custom" (the list's own order). [filter]: words to look for in the list.
     */
    class Page(val title: String, val list: Web.Item?, val from: Map<Web.Item, Web.Item> = emptyMap(), @Volatile var items: List<Web.Item> = emptyList(), @Volatile var loading: Boolean = true,
               var sort: String = "", var filter: String = "", var scroll: Float = 0f) {
        private var viewKey = ""
        private var viewCache: List<Web.Item> = emptyList()
        private var index: Map<Web.Item, String> = emptyMap()
        /** The songs as shown: searched, then sorted. Worked out once per change, not per frame. */
        fun view(): List<Web.Item> {
            val k = "${System.identityHashCode(items)}|$sort|$filter"
            if (k == viewKey) return viewCache
            if (index.size != items.size || index.keys.firstOrNull() !== items.firstOrNull()) {
                index = items.associateWith { "${it.title} ${it.sub} ${it.album}".lowercase() }
            }
            val words = filter.lowercase().split(' ').filter { it.isNotBlank() }
            var v = if (words.isEmpty()) items else items.filter { t -> val hay = index[t].orEmpty(); words.all { hay.contains(it) } }
            v = when (sort.removeSuffix("!")) {
                "added" -> v.sortedByDescending { it.added }
                "title" -> v.sortedBy { it.title.lowercase() }
                "artist" -> v.sortedWith(compareBy({ it.sub.lowercase() }, { it.album.lowercase() }, { it.order }))
                "album" -> v.sortedWith(compareBy({ it.album.lowercase() }, { it.order }))
                "length" -> v.sortedBy { it.durationMs }
                else -> v
            }
            // "!" = the other way round (oldest first, Z-A, longest first...)
            if (sort.endsWith("!")) v = v.reversed()
            viewCache = v; viewKey = k
            return v
        }
    }
    private val pages = mutableListOf<Page>()
    var onSearch: (() -> Unit)? = null
    var onSignIn: (() -> Unit)? = null

    private val sortNames = linkedMapOf("added" to "Recently added", "title" to "Title", "artist" to "Artist", "album" to "Album", "length" to "Length", "custom" to "List order")

    private fun libItem(id: String): Web.Item? {
        val i = id.removePrefix("lib:").toIntOrNull() ?: return null
        return pages.lastOrNull()?.view()?.getOrNull(i)
    }

    // ---- lists kept on the phone, so the library opens instantly where you left it ----

    private fun cacheFile(uri: String) = java.io.File(context.cacheDir, "lists/" + uri.replace(Regex("[^A-Za-z0-9]"), "_") + ".json")

    private fun saveList(uri: String, items: List<Web.Item>) {
        val a = org.json.JSONArray()
        items.forEach { a.put(org.json.JSONArray().put(it.uri).put(it.title).put(it.sub).put(it.kind).put(it.id).put(it.album).put(it.durationMs).put(it.added).put(it.order)) }
        runCatching { cacheFile(uri).also { it.parentFile?.mkdirs() }.writeText(a.toString()) }
    }

    private fun loadList(uri: String): List<Web.Item>? = runCatching {
        val a = org.json.JSONArray(cacheFile(uri).readText())
        (0 until a.length()).mapNotNull { a.optJSONArray(it) }.map {
            Web.Item(it.optString(0), it.optString(1), it.optString(2), it.optString(3), it.optString(4), it.optString(5), it.optLong(6), it.optLong(7), it.optInt(8))
        }
    }.getOrNull()

    private fun load(p: Page, key: String?, f: () -> List<Web.Item>) {
        // the copy from last time straight away, then Spotify's newest in the background
        key?.let { k -> loadList(k)?.let { if (it.isNotEmpty()) { p.items = it; p.loading = true } } }
        kotlin.concurrent.thread {
            val r = runCatching(f).getOrDefault(emptyList())
            post {
                if (r.isNotEmpty() || p.items.isEmpty()) p.items = r
                p.loading = false
                if (key != null && r.isNotEmpty()) kotlin.concurrent.thread { saveList(key, r) }
                invalidate()
            }
        }
    }

    /** Opens a list (or search results) on top of the library. */
    fun openPage(title: String, list: Web.Item?, f: () -> List<Web.Item>) {
        pages.lastOrNull()?.scroll = scroll
        val saved = list?.let { libPrefs.getString("sort:" + it.uri, null) }
        val p = Page(title, list, sort = saved ?: if (list?.kind == "album" || list == null) "custom" else "added")
        pages += p
        scroll = 0f
        tab = Tab.LIBRARY
        load(p, list?.uri?.takeIf { list.kind != "search" }, f)
        saveLibrary()
    }

    /** One search for everything: the list you have open, your library (playlists and the songs of
     *  lists you've opened), then Spotify. */
    fun search(q: String) {
        val inList = pages.lastOrNull()?.takeIf { it.list != null }
        val words = q.lowercase().split(' ').filter { it.isNotBlank() }
        fun match(it: Web.Item) = "${it.title} ${it.sub} ${it.album}".lowercase().let { h -> words.all { w -> h.contains(w) } }
        val from = HashMap<Web.Item, Web.Item>()
        val local = ArrayList<Web.Item>()
        inList?.let { pg ->
            val m = pg.items.filter { it.kind == "track" && match(it) }.take(30)
            if (m.isNotEmpty()) { local += Web.Item("", "In ${pg.title}", "", "header"); m.forEach { from[it] = pg.list!! }; local += m }
        }
        val lib = pages.firstOrNull()?.items.orEmpty()
        val lists = lib.filter { it.kind != "header" && match(it) }.take(10)
        val songs = ArrayList<Web.Item>()
        val seen = HashSet<String>(local.map { it.uri })
        for (l in lib) {
            if (songs.size >= 30) break
            loadList(l.uri)?.forEach { t -> if (t.kind == "track" && songs.size < 30 && match(t) && seen.add(t.uri)) { songs += t; from[t] = l } }
        }
        if (lists.isNotEmpty() || songs.isNotEmpty()) { local += Web.Item("", "In your library", "", "header"); local += lists; local += songs }
        val page = Page("Search: $q", null, from)
        page.items = local
        pages.lastOrNull()?.scroll = scroll
        pages += page
        scroll = 0f
        tab = Tab.LIBRARY
        kotlin.concurrent.thread {
            val sp = runCatching { Web.search(q) }.getOrDefault(emptyList())
            post {
                page.items = local + (if (sp.isNotEmpty()) listOf(Web.Item("", "On Spotify", "", "header")) + sp else emptyList())
                page.loading = false
                invalidate()
            }
        }
    }

    private val libPrefs get() = context.getSharedPreferences("library", 0)

    /** Which list is open (and how it's sorted / searched), saved for next time. */
    private fun saveLibrary() {
        // the library isn't loaded yet (e.g. a tab switch at start): nothing to save, keep what's saved
        if (pages.isEmpty()) return
        val top = pages.lastOrNull()
        val e = libPrefs.edit()
        val open = pages.drop(1).lastOrNull { it.list != null }
        if (open?.list != null) {
            val l = open.list
            e.putString("open", org.json.JSONArray().put(l.uri).put(l.title).put(l.kind).put(l.id).toString())
            e.putString("open_filter", open.filter)
            e.putFloat("open_scroll", if (top === open && tab == Tab.LIBRARY) scroll else open.scroll)
        } else e.remove("open")
        pages.firstOrNull()?.let { e.putString("top_sort", it.sort).putString("top_filter", it.filter) }
        e.apply()
    }

    private fun restoreLibrary() {
        val top = Page("Library", null, sort = libPrefs.getString("top_sort", "custom") ?: "custom", filter = "")
        pages += top
        load(top, "library") { Web.playlists() }
        val o = libPrefs.getString("open", null)?.let { runCatching { org.json.JSONArray(it) }.getOrNull() } ?: return
        val item = Web.Item(o.optString(0), o.optString(1), "", o.optString(2), o.optString(3))
        val p = Page(item.title, item, sort = libPrefs.getString("sort:" + item.uri, null) ?: if (item.kind == "album") "custom" else "added",
            filter = "", scroll = libPrefs.getFloat("open_scroll", 0f))
        pages += p
        if (tab == Tab.LIBRARY) scroll = p.scroll
        load(p, item.uri) { Web.tracks(item) }
    }

    private fun drawLibrary(cv: Canvas) {
        if (!Web.signedIn) {
            val top = header(cv, "Library", Web.status)
            drawRows(cv, top, listOf(
                Triple("Sign in to Spotify", "for your playlists, Liked Songs, search and likes", "libsignin"),
                Triple("#Uses your own Spotify developer Client ID,", "", ""),
                Triple("#the same one as SpoX on Windows.", "", ""),
            ), "", valueRight = false)
            return
        }
        if (pages.isEmpty()) restoreLibrary()
        val p = pages.last()
        val v = p.view()
        val sub = when {
            p.loading && p.items.isEmpty() -> "Loading..."
            pages.size > 1 -> "< Back" + if (p.loading) " · updating..." else ""
            else -> Web.userName()
        }
        var top = header(cv, p.title, sub)
        if (pages.size > 1) hit(0f, contentTop, width.toFloat(), top, "libback")
        // tools: one search for everything (this list, your library, Spotify), and sorting
        val th = 22 * u
        val isStats = p.title == STATS
        val searching = p.list == null && pages.size > 1 && !isStats
        if (isStats) {
            // which time span (tap to change)
            val lbl = when (p.sort) { "7" -> "Last 7 days"; "30" -> "Last 30 days"; "365" -> "This year"; else -> "All time" } + "  v"
            val sw = tw(lbl, u) + 12 * u
            nine(cv, "list_bg", pad, top, sw, th, u, 4)
            txt(cv, lbl, pad + 6 * u, top + 6 * u, u, cList)
            hit(pad, top, pad + sw, top + th, "libsort")
            top += th + 4 * u
        } else if (!searching) {
            val sortLabel = sortLabel(p)
            val sw = tw(sortLabel, u) + 12 * u
            // a Play button for the open list (plays it the way the shuffle button is set)
            val playW = if (p.list != null && v.isNotEmpty()) th + 4 * u else 0f
            if (playW > 0) {
                val px = width - pad - th
                box(cv, px, top, th, th, if (pressed == "libplayall") cList else cTitle)
                // a pixel "play" triangle
                val tx0 = px + 8 * u; val ty0 = top + 5 * u
                for (i in 0 until 6) box(cv, tx0 + i * u, ty0 + i * u, u, (12 - 2 * i) * u, cBg)
                hit(px, top, px + th, top + th, "libplayall")
            }
            val sx = width - pad - sw - playW
            nine(cv, "list_bg", sx, top, sw, th, u, 4)
            txt(cv, sortLabel, sx + 6 * u, top + 6 * u, u, if (pressed == "libsort") cTitle else cList)
            hit(sx, top, sx + sw, top + th, "libsort")
            val fw = sx - 4 * u - pad
            // looks like a text box, so it's clearly where you type
            box(cv, pad, top, fw, th, if (pressed == "libsearch") cTitle else cDim)
            box(cv, pad + u, top + u, fw - 2 * u, th - 2 * u, skin.color("input_bg", cBg))
            drawIcon(cv, ICON_SEARCH, pad + 5 * u, top + 7 * u, u, cDim)
            val hint = if (p.list != null) "Search this list, library, Spotify" else "Search library and Spotify"
            txt(cv, fit(hint, fw - 22 * u, u), pad + 16 * u, top + 6 * u, u, cDim)
            hit(pad, top, pad + fw, top + th, "libsearch")
            top += th + 4 * u
        }
        val rows = mutableListOf<Triple<String, String, String>>()
        if (pages.size == 1) rows += Triple("Your listening stats", "top artists, songs, time listened" + if (Stats.lastfmOn()) " · Last.fm on" else "", "libstats")

        v.forEachIndexed { i, it ->
            if (it.kind == "header") { rows += Triple("#" + it.title, "", ""); return@forEachIndexed }
            val extra = if (it.kind == "track" && p.sort == "added" && it.added > 0) " · " + ago(it.added) else if (it.kind == "track" && p.sort == "album" && it.album.isNotEmpty()) " · " + it.album else ""
            rows += Triple(it.title, it.sub + extra, "lib:$i")
        }
        drawRows(cv, top, rows, if (p.loading) "" else "Nothing here.", valueRight = false)
    }

    private fun dirName(k: String, rev: Boolean) = when (k) {
        "added" -> if (rev) "oldest first" else "newest first"
        "title", "artist", "album" -> if (rev) "Z-A" else "A-Z"
        "length" -> if (rev) "longest first" else "shortest first"
        else -> if (rev) "backwards" else ""
    }

    private fun sortLabel(p: Page): String {
        val k = p.sort.removeSuffix("!")
        if (p.list == null) return if (k == "title") "A-Z" else "Recent"
        val short = mapOf("added" to "Newest", "title" to "A-Z", "artist" to "Artist", "album" to "Album", "length" to "Length", "custom" to "List order")
        return if (p.sort.endsWith("!")) when (k) { "added" -> "Oldest"; "title" -> "Z-A"; "length" -> "Longest"; else -> (short[k] ?: k) + " ^" } else short[k] ?: "List order"
    }

    private fun openStats(days: String) {
        pages.lastOrNull()?.scroll = scroll
        val p = Page(STATS, null, sort = days)
        p.items = Stats.rows(days.toInt()).map { (a, b, uri) ->
            when {
                a.startsWith("#") -> Web.Item("", a.drop(1), "", "header")
                uri.isNotEmpty() -> Web.Item(uri, a, b, "track")
                else -> Web.Item("", a, b, "stat")
            }
        }
        p.loading = false
        pages += p
        scroll = 0f
        tab = Tab.LIBRARY
    }

    private fun ago(t: Long): String {
        val d = (System.currentTimeMillis() - t) / 86_400_000L
        return when { d < 1 -> "today"; d < 2 -> "yesterday"; d < 31 -> "${d}d ago"; d < 365 -> "${d / 30}mo ago"; else -> "${d / 365}y ago" }
    }

    private fun libTap(id: String) {
        val p = pages.lastOrNull() ?: return
        when {
            id == "libsignin" -> onSignIn?.invoke()
            id == "libsearch" -> onSearch?.invoke()
            id == "libback" -> back()
            id == "libstats" -> openStats("7")
            id == "libsort" && p.title == STATS -> show(Sheet("Stats for", emptyList(), listOf("7" to "Last 7 days", "30" to "Last 30 days", "365" to "This year", "0" to "All time").map { (k, l) ->
                SheetItem(l, "", k == p.sort) { pages.removeAt(pages.size - 1); openStats(k) }
            }))
            id == "libsort" -> {
                val opts = if (p.list == null) linkedMapOf("custom" to "Recent (like Spotify)", "title" to "Name") else sortNames
                val base = p.sort.removeSuffix("!")
                val rev = p.sort.endsWith("!")
                show(Sheet("Sort by", listOf("Tap the one that's on to turn it around."), opts.map { (k, l) ->
                    val on = k == base
                    SheetItem(l, if (on) dirName(k, rev) else dirName(k, false), on) {
                        p.sort = if (on && k != "custom") (if (rev) k else "$k!") else k
                        p.list?.let { libPrefs.edit().putString("sort:" + it.uri, p.sort).apply() }
                        scroll = 0f
                        saveLibrary()
                    }
                }))
            }
            id == "libplayall" -> p.list?.let { l -> playFrom(p, l, null) }
            id.startsWith("qlib:") -> libItem(id.drop(1))?.let { Player.addToQueue(it.uri, it.title, it.sub); say("Added to queue: ${it.title}") }
            id.startsWith("lib:") -> libItem(id)?.let { item ->
                when (item.kind) {
                    "stat", "header" -> {}
                    "playlist", "liked", "album" -> openPage(item.title, item) { Web.tracks(item) }
                    "artist" -> Player.playUri(item.uri)
                    else -> {
                        val l = p.list
                        val home = p.from[item]
                        when {
                            l != null -> playFrom(p, l, item.uri)
                            home != null -> kotlin.concurrent.thread {
                                val uris = loadList(home.uri)?.filter { it.kind == "track" }?.map { it.uri }.orEmpty()
                                if (!Web.playIn(home.uri, item.uri, uris)) Player.playUri(item.uri)
                            }
                            else -> kotlin.concurrent.thread { Player.playUri(item.uri) }
                        }
                        tab = Tab.PLAYER
                    }
                }
            }
        }
    }

    /**
     * Play a list from a song. Shuffle on: SpoX shuffle (starting with that song). Off: the list
     * in the order you see it (your sort and search), like Spotify does with its own sorting.
     */
    private fun playFrom(p: Page, l: Web.Item, first: String?) {
        val songs = p.view().filter { it.kind == "track" }
        // the shuffle button decides: SpoX shuffle, shuffle, or in the order you see
        val mode = Smart.mode(context)
        if (Web.signedIn && Player.fromEngine || Web.signedIn && io.github.oatomh.spox.engine.Engine.get(context).isLive) {
            if (mode == "off") Smart.start(context, l, p.items.filter { it.kind == "track" }, first ?: songs.firstOrNull()?.uri, false, order = songs)
            else Smart.start(context, l, p.items.filter { it.kind == "track" }, first, true, order = songs)
        } else kotlin.concurrent.thread { if (!Web.playIn(l.uri.takeIf { l.kind != "liked" }, first ?: songs.firstOrNull()?.uri, songs.map { it.uri })) Player.playUri(l.uri) }
        tab = Tab.PLAYER
    }


    // ---- like + share (player header) -----------------------------------------------------

    private var liked: Boolean? = null
    private var likedFor = ""
    private var likedAt = 0L
    @Volatile private var likeBusy = false
    private var likeWant: Boolean? = null
    private fun trackId() = Player.engineTrackUri()?.substringAfterLast(':').orEmpty()

    /** Asks Spotify when the song changes, and again every 30 s (you may like it on another device). */
    private fun checkLiked() {
        val id = trackId()
        val now = SystemClock.elapsedRealtime()
        if (id.isEmpty() || likeBusy || (id == likedFor && now - likedAt < 30_000)) return
        if (id != likedFor) liked = null
        likedFor = id
        likedAt = now
        kotlin.concurrent.thread { val r = Web.isLiked(id); post { if (likedFor == id && !likeBusy && r != null) { liked = r; invalidate() } } }
    }

    /** Flips at once; taps while Spotify is still answering are kept (the last one wins). */
    private fun toggleLike() {
        val id = trackId()
        if (id.isEmpty()) return
        val on = liked != true
        liked = on
        likeWant = on
        if (likeBusy) return
        likeBusy = true
        kotlin.concurrent.thread {
            var sent: Boolean? = null
            var ok = true
            while (likeWant != sent) {
                val want = likeWant ?: break
                ok = Web.setLiked(id, want)
                sent = want
                if (!ok) break
            }
            post {
                likeBusy = false
                likedAt = SystemClock.elapsedRealtime()
                if (!ok) {
                    liked = sent != true
                    toast("Couldn't change Liked Songs (sign in again in Settings?)")
                } else toast(if (sent == true) "Saved to Liked Songs" else "Removed from Liked Songs")
                invalidate()
            }
        }
    }

    var onDevices: (() -> Unit)? = null

    private fun miniExtras(ctx: Context) = ctx.getSharedPreferences("spox", Context.MODE_PRIVATE).getBoolean("mini_extras", false)

    /** The playlist + / - works on: SpoX shuffle's list, else the one playing. */
    private fun plusList(): String = Smart.listUri.takeIf { Smart.on && it.startsWith("spotify:playlist:") }
        ?: io.github.oatomh.spox.engine.Engine.get(context).now.contextUri

    private fun togglePlaylist() {
        val ctxUri = plusList()
        val uri = Player.engineTrackUri() ?: return
        val inPl = Web.inPlaylist(ctxUri, uri) ?: return
        val name = (if (Smart.on) Smart.listName else io.github.oatomh.spox.engine.Engine.get(context).now.contextName).ifEmpty { "the playlist" }
        // the button flips straight away; put back if Spotify says no
        Web.markInPlaylist(ctxUri, uri, !inPl)
        invalidate()
        kotlin.concurrent.thread {
            val ok = if (inPl) Web.removeFrom(ctxUri, uri) else Web.addTo(ctxUri, uri)
            post {
                if (!ok) Web.markInPlaylist(ctxUri, uri, inPl)
                toast(if (!ok) "Couldn't change $name (is it yours?)" else if (inPl) "Removed from $name" else "Added to $name")
                invalidate()
            }
        }
    }

    private fun toast(t: String) = say(t)

    private fun share() {
        val uri = Player.engineTrackUri() ?: return
        val parts = uri.split(':')
        val link = if (parts.size >= 3) "https://open.spotify.com/${parts[1]}/${parts[2]}" else return
        val st = Player.state
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TEXT, "${st.title} - ${st.artist}\n$link")
        context.startActivity(android.content.Intent.createChooser(send, "Send this song"))
    }

    private fun drawSettings(cv: Canvas) {
        val top = header(cv, "Settings")
        drawRows(cv, top, settingsRows().map { (l, v) -> Triple(l, v, "set:$l") }, "", valueRight = true)
    }

    // ---- SpoX's own pop-ups (instead of Android's dialogs) -----------------------------------

    class SheetItem(val label: String, val value: String = "", val checked: Boolean = false, val run: () -> Unit)
    class SheetInput(val hint: String, var text: String, val password: Boolean = false, val done: (String) -> Unit)
    class Sheet(val title: String, val lines: List<String> = emptyList(), val items: List<SheetItem> = emptyList(),
                val input: SheetInput? = null, val buttons: List<SheetItem> = emptyList())

    private var sheet: Sheet? = null
    private var sheetScroll = 0f
    private var sheetMax = 0f
    private var sheetListRect = RectF()
    private var composing = ""

    fun show(s: Sheet) {
        sheet = s
        sheetScroll = 0f
        composing = ""
        val imm = context.getSystemService(InputMethodManager::class.java)
        if (s.input != null) {
            requestFocus()
            imm.restartInput(this)
            post { imm.showSoftInput(this, 0) }
        } else imm.hideSoftInputFromWindow(windowToken, 0)
        invalidate()
    }

    fun closeSheet() {
        if (sheet?.input != null) context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
        sheet = null
        invalidate()
    }

    /** A question with buttons. */
    fun confirm(title: String, text: String, yes: String, no: String = "Cancel", onYes: () -> Unit) =
        show(Sheet(title, wrapText(text), buttons = listOf(SheetItem(no) {}, SheetItem(yes, run = onYes))))

    /** A text box (search, keys...). */
    fun ask(title: String, hint: String, initial: String = "", password: Boolean = false, message: String = "", ok: String = "OK", done: (String) -> Unit) {
        val inp = SheetInput(hint, initial, password, done)
        show(Sheet(title, if (message.isEmpty()) emptyList() else wrapText(message), input = inp,
            buttons = listOf(SheetItem("Cancel") {}, SheetItem(ok) { done(inp.text + composing) })))
    }

    private fun wrapText(t: String): List<String> {
        val maxW = width - 4 * pad - 8 * u
        val out = ArrayList<String>()
        for (para in t.split('\n')) {
            var line = ""
            for (w in para.split(' ')) {
                val tryL = if (line.isEmpty()) w else "$line $w"
                if (maxW > 0 && tw(tryL, u) > maxW && line.isNotEmpty()) { out += line; line = w } else line = tryL
            }
            out += line
        }
        return out
    }

    private fun sheetDone() {
        val inp = sheet?.input ?: return
        val text = inp.text + composing
        closeSheet()
        inp.done(text)
    }

    private fun drawSheet(cv: Canvas, sh: Sheet, now: Long) {
        hits.clear()
        // the rest of the screen dims; tapping it closes the pop-up
        box(cv, 0f, 0f, width.toFloat(), height.toFloat(), Color.argb(170, 0, 0, 0))
        hit(0f, 0f, width.toFloat(), height.toFloat(), "sheet:out")
        val x = pad
        val w = width - 2 * pad
        val rh = 24 * u
        val bh = 22 * u
        val inner = 6 * u
        val bottomLimit = height - max(insetBottom, imeBottom) - 8 * u
        val titleH = 12 * tu + 2 * u
        val linesH = sh.lines.size * 12f * u + if (sh.lines.isNotEmpty()) 4 * u else 0f
        val inputH = if (sh.input != null) 24 * u + 6 * u else 0f
        val btnH = if (sh.buttons.isNotEmpty()) bh + 6 * u else 0f
        val listFull = sh.items.size * rh
        val room = bottomLimit - (insetTop + 24 * u) - titleH - linesH - inputH - btnH - 2 * inner
        val listH = min(listFull, max(rh * 2, room))
        val h = titleH + linesH + inputH + listH + btnH + 2 * inner
        val y0 = floor((bottomLimit - h) / u) * u
        nine(cv, "list_bg", x, y0, w, h, u, 4)
        hit(x, y0, x + w, y0 + h, "sheet:in")
        var y = y0 + inner
        txt(cv, fit(sh.title, w - 2 * inner, tu), x + inner, y, tu, cHeader)
        y += titleH
        for (l in sh.lines) { txt(cv, fit(l, w - 2 * inner, u), x + inner, y, u, cList); y += 12 * u }
        if (sh.lines.isNotEmpty()) y += 4 * u
        sh.input?.let { inp ->
            val ih = 24 * u
            box(cv, x + inner, y, w - 2 * inner, ih, cTitle)
            box(cv, x + inner + u, y + u, w - 2 * inner - 2 * u, ih - 2 * u, skin.color("input_bg", cBg))
            val raw = inp.text + composing
            val shown = if (inp.password) "*".repeat(raw.length) else raw
            val tx = x + inner + 5 * u
            val maxW = w - 2 * inner - 14 * u
            if (shown.isEmpty()) txt(cv, inp.hint, tx, y + 7 * u, u, cDim)
            else {
                // keep the end (where you type) in view
                var t = shown
                while (t.isNotEmpty() && tw(t, u) > maxW) t = t.drop(1)
                txt(cv, t, tx, y + 7 * u, u, cList)
            }
            val cx = tx + if (shown.isEmpty()) 0f else min(maxW, tw(shown, u)) + u
            if ((now / 500) % 2 == 0L) box(cv, cx, y + 6 * u, u, 12 * u, cTitle)
            animating = true
            hit(x + inner, y, x + w - inner, y + ih, "sheet:input")
            y += ih + 6 * u
        }
        if (sh.items.isNotEmpty()) {
            sheetListRect = RectF(x, y, x + w, y + listH)
            sheetMax = max(0f, listFull - listH)
            sheetScroll = sheetScroll.coerceIn(0f, sheetMax)
            cv.save()
            cv.clipRect(x, y, x + w, y + listH)
            for ((i, it) in sh.items.withIndex()) {
                val ry = y + i * rh - sheetScroll
                if (ry + rh < y || ry > y + listH) continue
                val id = "sheet:item:$i"
                if (pressed == id) box(cv, x + u, ry, w - 2 * u, rh, cLine)
                val vw = if (it.value.isEmpty()) 0f else tw(it.value, u) + 6 * u
                if (it.checked) drawIcon(cv, ICON_CHECK, x + inner, ry + 9 * u, u, cTitle)
                val lx = x + inner + 12 * u
                txt(cv, fit(it.label, w - 2 * inner - 12 * u - vw, u), lx, ry + 7 * u, u, if (it.checked) cTitle else cList)
                if (it.value.isNotEmpty()) txt(cv, it.value, x + w - inner - tw(it.value, u), ry + 7 * u, u, cDim)
                if (i < sh.items.size - 1) box(cv, lx, ry + rh - max(1f, u / 2), w - inner - lx + x - inner, max(1f, u / 2), cLine)
                hit(x, max(y, ry), x + w, min(y + listH, ry + rh), id)
            }
            cv.restore()
            y += listH
        } else sheetListRect = RectF()
        if (sh.buttons.isNotEmpty()) {
            y += 6 * u
            val n = sh.buttons.size
            val gap = 4 * u
            val bw = (w - 2 * inner - (n - 1) * gap) / n
            for ((i, b) in sh.buttons.withIndex()) {
                val bx = x + inner + i * (bw + gap)
                val id = "sheet:btn:$i"
                val main = i == n - 1
                box(cv, bx, y, bw, bh, if (main) cTitle else cLine)
                if (pressed == id) box(cv, bx + u, y + u, bw - 2 * u, bh - 2 * u, cBg)
                val lw = tw(b.label, u)
                txt(cv, fit(b.label, bw - 4 * u, u), bx + max(2 * u, floor((bw - lw) / 2 / u) * u), y + 6 * u, u, if (main && pressed != id) cBg else cList)
                hit(bx, y, bx + bw, y + bh, id)
            }
        }
    }

    private fun sheetTap(id: String) {
        val sh = sheet ?: return
        when {
            id == "sheet:out" -> closeSheet()
            id == "sheet:input" -> context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
            id.startsWith("sheet:item:") -> {
                val it = sh.items.getOrNull(id.removePrefix("sheet:item:").toInt()) ?: return
                closeSheet(); it.run()
            }
            id.startsWith("sheet:btn:") -> {
                val i = id.removePrefix("sheet:btn:").toInt()
                val b = sh.buttons.getOrNull(i) ?: return
                // the main button of a text box hands over what you typed
                if (sh.input != null && i == sh.buttons.size - 1) sheetDone() else { closeSheet(); b.run() }
            }
        }
    }

    // typing into SpoX's own text box (the phone's keyboard, SpoX's pixel font)
    override fun onCheckIsTextEditor() = sheet?.input != null

    override fun onCreateInputConnection(out: EditorInfo): InputConnection? {
        val inp = sheet?.input ?: return null
        val variant = if (inp.password) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        out.inputType = android.text.InputType.TYPE_CLASS_TEXT or variant
        out.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                inp.text += text?.toString().orEmpty().replace("\n", ""); composing = ""; invalidate(); return true
            }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean { composing = text?.toString().orEmpty(); invalidate(); return true }
            override fun finishComposingText(): Boolean { inp.text += composing; composing = ""; invalidate(); return true }
            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                if (composing.isNotEmpty()) composing = composing.dropLast(before) else inp.text = inp.text.dropLast(before)
                invalidate(); return true
            }
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = (inp.text + composing).takeLast(n)
            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
            override fun performEditorAction(actionCode: Int): Boolean { sheetDone(); return true }
            override fun sendKeyEvent(e: KeyEvent): Boolean {
                if (e.action == KeyEvent.ACTION_DOWN) when (e.keyCode) {
                    KeyEvent.KEYCODE_DEL -> deleteSurroundingText(1, 0)
                    KeyEvent.KEYCODE_ENTER -> sheetDone()
                    else -> e.unicodeChar.takeIf { it > 0 }?.let { commitText(it.toChar().toString(), 1) }
                }
                return true
            }
        }
    }

    // ---- a SpoX message at the bottom (instead of Android's grey toast) ----------------------

    private var osdText = ""
    private var osdUntil = 0L

    fun say(t: String) { osdText = t; osdUntil = SystemClock.elapsedRealtime() + 2200; invalidate() }

    // ---- volume: SpoX's own pixel volume, like a handheld player (not the phone's slider) ----

    private var volOsd = -1f
    private var volOsdUntil = 0L

    fun showVolume(f: Float) { lastVolSeen = f; vol = f; volAt = SystemClock.elapsedRealtime(); volOsd = f; volOsdUntil = volAt + 1400; invalidate() }

    private fun drawVolumeOsd(cv: Canvas, now: Long) {
        if (now < osdUntil && osdText.isNotEmpty()) {
            val t = fit(osdText, width - 4 * pad, u)
            val w = tw(t, u) + 16 * u
            val h = 20 * u
            val x = floor((width - w) / 2 / u) * u
            val y = contentBottom - h - 8 * u
            nine(cv, "list_bg", x, y, w, h, u, 4)
            txt(cv, t, x + 8 * u, y + 5 * u, u, cTitle)
            animating = true
        }
        if (now >= volOsdUntil || volOsd < 0) return
        animating = true
        // on the Player tab the big volume bar itself shows it
        if (tab == Tab.PLAYER && sheet == null) return
        val segs = 16
        val sw = 5 * u
        val gap = u
        val barW = segs * (sw + gap) - gap
        val w = barW + 40 * u
        val h = 26 * u
        val x = floor((width - w) / 2 / u) * u
        val y = insetTop + 12 * u
        nine(cv, "list_bg", x, y, w, h, u, 4)
        drawIcon(cv, ICON_SPEAKER, x + 7 * u, y + 9 * u, u, cTitle)
        val on = (volOsd * segs + 0.001f).toInt()
        for (i in 0 until segs) {
            // little steps that get taller, like an old MP3 player
            val bh = (4 + i * 10 / segs) * u
            box(cv, x + 20 * u + i * (sw + gap), y + 18 * u - bh, sw, bh, if (i < on) visCol(1f - i.toFloat() / segs) else cLine)
        }
        val pct = "${(volOsd * 100).roundToInt()}"
        txt(cv, pct, x + w - 6 * u - tw(pct, u), y + 8 * u, u, cDim)
    }

    // ---- tiny pixel icons -------------------------------------------------------------------

    private fun icon(kind: String): Array<String> = when (kind) {
        "bt" -> ICON_BT
        "car" -> ICON_CAR
        "wired", "usb" -> ICON_HEAD
        else -> ICON_PHONE
    }

    private fun drawIcon(cv: Canvas, g: Array<String>, x: Float, y: Float, k: Float, c: Int) {
        for ((j, row) in g.withIndex()) for ((i, ch) in row.withIndex()) if (ch == 'X') box(cv, x + i * k, y + j * k, k, k, c)
    }

    companion object {
        private val ICON_BT = arrayOf("...X...", "...XX..", ".X.X.X.", "..XXX..", "...X...", "..XXX..", ".X.X.X.", "...XX..", "...X...")
        private val ICON_CAR = arrayOf("..XXXXX..", ".X.X.X.X.", "XXXXXXXXX", "X.XXXXX.X", "XXXXXXXXX", ".XX...XX.")
        private val ICON_HEAD = arrayOf("..XXXXX..", ".X.....X.", "X.......X", "X.......X", "XX.....XX", "XXX...XXX", "XXX...XXX", ".X.....X.")
        private val ICON_PHONE = arrayOf("XXXXX", "X...X", "X...X", "X...X", "X...X", "X.X.X", "XXXXX")
        private val ICON_SEARCH = arrayOf(".XXX..", "X...X.", "X...X.", "X...X.", ".XXX..", ".....X")
        private val ICON_MUTED = arrayOf("...X....", "..XX....", "XXXX.X.X", "XXXX..X.", "XXXX.X.X", "..XX....", "...X....")
        private val ICON_SPEAKER = arrayOf("...X....", "..XX..X.", "XXXX...X", "XXXX.X.X", "XXXX...X", "..XX..X.", "...X....")
        private const val STATS = "Your stats"
        val ICON_CHECK = arrayOf("......X", ".....X.", "X...X..", ".X.X...", "..X....")

        /** The SpoX window on screen (for messages from the player and SpoX shuffle). */
        @Volatile var current: PlayerView? = null

        /** A SpoX message if the app is open, else Android's toast. */
        fun say(ctx: Context, t: String) {
            val v = current
            if (v != null && v.isAttachedToWindow) v.post { v.say(t) }
            else android.os.Handler(ctx.mainLooper).post { android.widget.Toast.makeText(ctx, t, android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    /** Asks Spotify now and then which device plays, when it isn't this phone. */
    private var otherName = ""
    private var otherAt = 0L
    private fun otherDevice(): String {
        val now = SystemClock.elapsedRealtime()
        if (Web.signedIn && now - otherAt > 15_000) {
            otherAt = now
            kotlin.concurrent.thread { val d = Web.activeDevice(); post { otherName = d?.name.orEmpty(); invalidate() } }
        }
        return otherName
    }

    // ---- touch ----------------------------------------------------------------------------

    private var lastDt = 16f
    private fun dt60() = (lastDt / 16.67f).toDouble()

    private fun hitAt(x: Float, y: Float) = hits.lastOrNull { x >= it.l && x < it.r && y >= it.t && y < it.b }?.id

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle(); vt = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = x; downY = y; lastY = y
                fling = 0f
                longFired = false
                removeCallbacks(longPress)
                if (sheet != null) {
                    dragging = if (sheetListRect.contains(x, y)) "sheetlist" else null
                    pressed = hitAt(x, y)
                    invalidate()
                    return true
                }
                val slop = 6 * u
                dragging = when {
                    tab == Tab.PLAYER && access && RectF(seekRect.left, seekRect.top - slop, seekRect.right, seekRect.bottom + slop).contains(x, y) -> "seek"
                    tab == Tab.PLAYER && access && RectF(volRect.left, volRect.top - slop, volRect.right, volRect.bottom + slop).contains(x, y) -> "vol"
                    tab != Tab.PLAYER && y > contentTop && y < contentBottom -> "list"
                    tab == Tab.PLAYER && artRect.contains(x, y) -> "art"
                    tab != Tab.PLAYER && miniRect.contains(x, y) -> "art"
                    else -> null
                }
                pressed = if (dragging == null || dragging == "list" || dragging == "art") hitAt(x, y) else null
                // hold a song in Up next to pick it; hold shuffle for the new-songs mix
                pressed?.takeIf { it.startsWith("qrow:") || it == "btn_shuffle" }?.let { postDelayed(longPress, 420) }
                drag(x)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (abs(x - downX) > 8 * u || abs(y - downY) > 8 * u) removeCallbacks(longPress)
                if (dragging == "sheetlist") {
                    sheetScroll = (sheetScroll - (y - lastY)).coerceIn(0f, sheetMax)
                    lastY = y
                    if (abs(y - downY) > 8 * u) pressed = null
                    invalidate()
                    return true
                }
                if (swipeId == null && dragging == "list" && tab == Tab.QUEUE && selected.isEmpty() && pressed?.startsWith("qrow:") == true &&
                    abs(x - downX) > 10 * u && abs(x - downX) > abs(y - downY) * 1.5f) {
                    swipeId = pressed; pressed = null
                }
                if (swipeId != null) {
                    swipeDx = x - downX
                    invalidate()
                    return true
                }
                if (dragging == "list") {
                    scroll -= y - lastY
                    lastY = y
                    if (abs(y - downY) > 8 * u) { pressed = null; manualUntil = SystemClock.elapsedRealtime() + 4000 }
                } else drag(x)
                if (pressed != null && (abs(x - downX) > 8 * u || abs(y - downY) > 8 * u) && dragging != "list") pressed = null
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                vt?.addMovement(e)
                removeCallbacks(longPress)
                swipeId?.let { id ->
                    if (-swipeDx > width * 0.3f) {
                        val q = id.split(":", limit = 3)
                        val uri = q.getOrNull(2).orEmpty()
                        val title = qRows.firstOrNull { it.uri == uri }?.title.orEmpty()
                        buzz()
                        Player.removeFromQueue(setOf(uri))
                        say("Removed: $title")
                    }
                    swipeId = null; swipeDx = 0f
                    pressed = null; dragging = null
                    vt?.recycle(); vt = null
                    invalidate()
                    return true
                }
                if (longFired) {
                    pressed = null; dragging = null; seekPreview = -1
                    vt?.recycle(); vt = null
                    invalidate()
                    return true
                }
                if (dragging == "seek" && seekPreview >= 0) Player.seek(seekPreview)
                if (dragging == "list") {
                    vt?.computeCurrentVelocity(1)
                    val v = -(vt?.yVelocity ?: 0f)
                    if (abs(y - downY) > 8 * u && abs(v) > 0.3f) { fling = v; manualUntil = SystemClock.elapsedRealtime() + 4000 }
                }
                // swipe the cover sideways: next / previous song
                if (dragging == "art" && abs(x - downX) > 40 * u && abs(x - downX) > abs(y - downY) * 2) {
                    buzz()
                    if (x < downX) Player.next() else Player.prev()
                } else pressed?.let { p -> if (hitAt(x, y) == p) press(p) }
                pressed = null; dragging = null; seekPreview = -1
                vt?.recycle(); vt = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                swipeId = null; swipeDx = 0f
                pressed = null; dragging = null; seekPreview = -1
                vt?.recycle(); vt = null
            }
        }
        return true
    }

    private var longFired = false
    private val longPress = Runnable {
        val p = pressed ?: return@Runnable
        longFired = true
        when {
            p.startsWith("qrow:") -> queueLong(p)
            p == "btn_shuffle" -> { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); if (Web.signedIn) mixSheet() }
        }
        pressed = null
        invalidate()
    }

    private fun drag(x: Float) {
        when (dragging) {
            "seek" -> seekPreview = (Player.state.durationMs * ((x - seekRect.left) / seekRect.width()).coerceIn(0f, 1f)).toLong()
            "vol" -> {
                vol = ((x - volRect.left) / volRect.width()).coerceIn(0f, 1f)
                Player.setVolume(context, vol)
                volAt = SystemClock.elapsedRealtime()
            }
        }
    }

    private fun buzz() = performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    private fun press(id: String) {
        buzz()
        when {
            id.startsWith("sheet:") -> { if (id != "sheet:in") sheetTap(id) }
            id.startsWith("qrow:") -> queueTap(id)
            id == "qcancel" || id == "qremove" || id == "qnext" || id == "qmix" -> queueAction(id)
            id.startsWith("tab:") -> tab = Tab.valueOf(id.removePrefix("tab:"))
            id.startsWith("set:") -> onSettingsAction?.invoke(id.removePrefix("set:"))
            id == "btn_prev" || id == "mini_prev" -> Player.prev()
            id == "mini_next" -> Player.next()
            id == "btn_next" -> Player.next()
            id == "btn_play" || id == "btn_pause" || id == "mini_play" -> Player.playPause()
            id == "btn_shuffle" -> Player.toggleShuffle()
            id == "btn_repeat" -> Player.toggleRepeat()
            id == "link" -> Player.openApp(context)
            id == "share" -> share()
            id == "like" -> toggleLike()
            id == "plus" -> togglePlaylist()
            id == "devices" -> onDevices?.invoke()
            id.startsWith("lib") || id.startsWith("qlib") -> libTap(id)
            id == "time" -> showRemaining = !showRemaining
            id == "mute" -> { vol = Player.toggleMute(context); volAt = SystemClock.elapsedRealtime(); volOsdUntil = volAt + 1400; volOsd = vol }
            id.startsWith("lyric:") -> id.removePrefix("lyric:").toLongOrNull()?.let { Player.seek(it); manualUntil = 0 }
        }
        invalidate()
    }
}
