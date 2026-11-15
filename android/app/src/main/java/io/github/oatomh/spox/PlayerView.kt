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
import android.view.MotionEvent
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
    private val skin = Skin.get(ctx)

    enum class Tab(val label: String) { PLAYER("Player"), LYRICS("Lyrics"), QUEUE("Up next"), SETTINGS("Settings") }
    var tab = Tab.PLAYER
        set(v) { field = v; scroll = 0f; fling = 0f; invalidate() }

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
    private var showRemaining = false

    // slow system lookups, done now and then instead of every frame
    private var access = Player.hasAccess(ctx)
    private var vol = Player.volume(ctx)
    private var volAt = 0L

    // lyrics
    private var lyricsKey = ""
    private var lyrics: Lyrics.Result? = null
    private var lyricsState = "Play something to see lyrics."
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
    private var contentTop = 0f
    private var contentBottom = 0f

    init {
        setOnApplyWindowInsetsListener { _, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                insetTop = b.top.toFloat(); insetBottom = b.bottom.toFloat()
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
        if (tab == Tab.PLAYER) return false
        tab = Tab.PLAYER
        return true
    }

    private val playerChanged: () -> Unit = { invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Player.listen(playerChanged)
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
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
        hits.clear()
        cv.drawColor(cBg)

        // fling
        if (abs(fling) > 0.05f && dragging != "list") {
            scroll += fling * dt
            fling *= Math.pow(0.996, dt.toDouble()).toFloat()
            if (scroll < 0 || scroll > maxScroll) fling = 0f
            animating = true
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
            Tab.QUEUE -> drawQueue(cv)
            Tab.SETTINGS -> drawSettings(cv)
        }

        // Pixel art moves in whole pixels, so drawing at the phone's full 120 Hz mostly repeats the
        // same picture and costs battery. Dragging stays at full speed; otherwise the chosen rate.
        when {
            dragging != null -> postInvalidateOnAnimation()
            st.playing || animating -> postInvalidateDelayed(1000L / fps)
            else -> postInvalidateDelayed(500)
        }
        animating = false
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
        val bw = sprW("btn_play", u)
        val bx = width - pad - bw
        val by = top + (h - sprH("btn_play", u)) / 2
        val btn = if (st.playing) "btn_pause" else "btn_play"
        spr(cv, if (pressed == "mini_play") "${btn}_down" else btn, bx, by, u)
        val tx = pad + a + 6 * u
        val textW = bx - tx - 6 * u
        marquee(cv, st.title, tx, top + 6 * u, textW, u, cTitle, now)
        txt(cv, fit(st.artist, textW, u), tx, top + 17 * u, u, cInfo)
        // progress line along the top edge
        if (st.durationMs > 0) box(cv, 0f, top, width * (st.position(now).toFloat() / st.durationMs).coerceIn(0f, 1f), max(1f, u / 2), cTitle)
        hit(bx - 4 * u, top, width.toFloat(), top + h, "mini_play")
        hit(0f, top, bx - 4 * u, top + h, "tab:PLAYER")
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
        // header: where the music comes from, and a shortcut to the app
        val src = when (st.app) { "com.spotify.music", "com.spotify.lite" -> "Playing on Spotify"; "" -> "SpoX"; else -> "Playing" }
        txt(cv, src, pad, top + 4 * u, u, cDim)
        val lw = sprW("btn_link", u)
        spr(cv, if (pressed == "link") "btn_link_down" else "btn_link", width - pad - lw, top, u)
        hit(width - pad - lw - 4 * u, top - 4 * u, width.toFloat(), top + 20 * u, "link")

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
                active && skin.sprite("${b}_on") != null -> "${b}_on"
                active && skin.sprite("${b}_down") != null -> "${b}_down"
                else -> b
            }
            spr(cv, sprite, bx, y, bu)
            hit(bx - gap / 2, y - 6 * u, bx + bw + gap / 2, y + ctrlH + 6 * u, b)
        }
        y += ctrlH + 10 * u

        // volume = the phone's media volume
        if (now - volAt > 1000 && dragging != "vol") { vol = Player.volume(context); volAt = now }
        volRect = RectF(pad, y, pad + w, y + volH)
        nine(cv, "vol_track", pad, y, w, volH, u, 2)
        val vw = floor(w * vol / u) * u
        if (vw > 0) nine(cv, "vol_fill", pad, y, vw, volH, u, 2)
        spr(cv, "vol_thumb", (pad + vw - 4 * u).coerceIn(pad, pad + w - 8 * u), y, u)
        y += volH + 8 * u

        // visualizer in the space that's left
        val vh = floor((contentBottom - y - 4 * u) / u) * u
        if (vh >= 12 * u) drawVis(cv, st, now, pad, y, w, vh)
    }

    private var visColors: IntArray? = null
    private fun visCol(frac: Float): Int {
        val b = skin.sprite("vis_colors") ?: return cTitle
        val c = visColors ?: IntArray(b.height) { b.getPixel(0, it) }.also { visColors = it }
        return c[min(c.size - 1, (frac * c.size).toInt().coerceAtLeast(0))]
    }

    private fun drawVis(cv: Canvas, st: Player.State, now: Long, x: Float, y: Float, w: Float, h: Float) {
        val bw = 3 * u
        val gap = u
        val bars = ((w + gap) / (bw + gap)).toInt()
        val x0 = x + floor((w - (bars * (bw + gap) - gap)) / 2 / u) * u
        val t = (now - t0) / 1000f
        val rows = (h / u).toInt()
        for (i in 0 until bars) {
            val xr = i / bars.toFloat()
            val v = if (st.playing) ((0.15f + 0.55f * (sin(t * 3.1f + xr * 9f) * 0.5f + 0.5f) * (sin(t * 5.3f - xr * 4f) * 0.5f + 0.5f)) * (1f - abs(xr - 0.5f) * 0.6f)) else 0.02f
            val bh = max(1, (v * rows).toInt())
            for (yy in 0 until bh) {
                box(cv, x0 + i * (bw + gap), y + h - (yy + 1) * u, bw, u, visCol(1f - yy.toFloat() / rows))
            }
        }
    }

    // ---- lists ----------------------------------------------------------------------------

    private fun drawLyrics(cv: Canvas, st: Player.State, now: Long) {
        val top = header(cv, "Lyrics", if (st.hasTrack) "${st.title} - ${st.artist}" else "")
        val key = "${st.artist}|${st.title}"
        if (key != lyricsKey && st.hasTrack) {
            lyricsKey = key
            lyrics = null
            lyricsState = "Looking for lyrics..."
            scroll = 0f
            Lyrics.fetch(st.title, st.artist, st.album, st.durationMs) { r ->
                post {
                    if (key == lyricsKey) {
                        lyrics = r
                        lyricsState = if (r == null) "No lyrics for this song." else ""
                        invalidate()
                    }
                }
            }
        }
        val l = lyrics
        if (l == null) {
            txt(cv, lyricsState, (width - tw(lyricsState, u)) / 2, top + 20 * u, u, cDim)
            return
        }
        val k = tu
        val lh = 13 * k
        val maxW = width - 2 * pad
        val wrapped = wrapCache.takeIf { wrapKey == "$lyricsKey|$maxW|$k" } ?: wrap(l, maxW, k).also {
            wrapCache = it; wrapKey = "$lyricsKey|$maxW|$k"
        }
        val p = st.position(now) + 250
        val current = if (l.synced) l.lines.indexOfLast { it.first <= p } else -1
        val curRow = wrapped.indexOfFirst { it.first == current }
        val viewH = contentBottom - top
        maxScroll = max(0f, wrapped.size * lh - viewH + 20 * u)
        // follow the song unless you scrolled by hand a moment ago (glides there, no jumps)
        if (curRow >= 0 && SystemClock.elapsedRealtime() > manualUntil && dragging != "list") {
            val target = (curRow * lh - viewH * 0.35f).coerceIn(0f, maxScroll)
            if (abs(target - scroll) > 1f) { scroll += (target - scroll) * 0.18f; animating = true } else scroll = target
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
    private fun drawRows(cv: Canvas, top: Float, rows: List<Triple<String, String, String>>, empty: String, valueRight: Boolean) {
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
            if (pressed == id) box(cv, 0f, ry, width.toFloat(), rh, cLine)
            if (valueRight) {
                val vw = tw(b, u)
                txt(cv, fit(a, width - 2 * pad - vw - 8 * u, u), pad, ry + 9 * u, u, cList)
                txt(cv, b, width - pad - vw, ry + 9 * u, u, cDim)
            } else {
                txt(cv, fit(a, width - 2 * pad, u), pad, ry + 5 * u, u, cList)
                txt(cv, fit(b, width - 2 * pad, u), pad, ry + 16 * u, u, cDim)
            }
            box(cv, pad, ry + rh - max(1f, u / 2), width - 2 * pad, max(1f, u / 2), cLine)
            if (id.isNotEmpty()) hit(0f, max(top, ry), width.toFloat(), min(contentBottom, ry + rh), id)
        }
        cv.restore()
    }

    private fun drawQueue(cv: Canvas) {
        val top = header(cv, "Up next")
        val rows = Player.queueItems().map { (id, t, a) -> Triple(t, a, "queue:$id") }
        drawRows(cv, top, rows, "Spotify didn't share its queue.", valueRight = false)
    }

    private fun drawSettings(cv: Canvas) {
        val top = header(cv, "Settings")
        drawRows(cv, top, settingsRows().map { (l, v) -> Triple(l, v, "set:$l") }, "", valueRight = true)
    }

    // ---- touch ----------------------------------------------------------------------------

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
                val slop = 6 * u
                dragging = when {
                    tab == Tab.PLAYER && access && RectF(seekRect.left, seekRect.top - slop, seekRect.right, seekRect.bottom + slop).contains(x, y) -> "seek"
                    tab == Tab.PLAYER && access && RectF(volRect.left, volRect.top - slop, volRect.right, volRect.bottom + slop).contains(x, y) -> "vol"
                    tab != Tab.PLAYER && y > contentTop && y < contentBottom -> "list"
                    tab == Tab.PLAYER && artRect.contains(x, y) -> "art"
                    else -> null
                }
                pressed = if (dragging == null || dragging == "list" || dragging == "art") hitAt(x, y) else null
                drag(x)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
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
                pressed = null; dragging = null; seekPreview = -1
                vt?.recycle(); vt = null
            }
        }
        return true
    }

    private fun drag(x: Float) {
        when (dragging) {
            "seek" -> seekPreview = (Player.state.durationMs * ((x - seekRect.left) / seekRect.width()).coerceIn(0f, 1f)).toLong()
            "vol" -> {
                vol = ((x - volRect.left) / volRect.width()).coerceIn(0f, 1f)
                Player.setVolume(context, vol)
            }
        }
    }

    private fun buzz() = performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    private fun press(id: String) {
        buzz()
        when {
            id.startsWith("tab:") -> tab = Tab.valueOf(id.removePrefix("tab:"))
            id.startsWith("set:") -> onSettingsAction?.invoke(id.removePrefix("set:"))
            id.startsWith("queue:") -> id.removePrefix("queue:").toLongOrNull()?.let { Player.playQueueItem(it) }
            id == "btn_prev" -> Player.prev()
            id == "btn_next" -> Player.next()
            id == "btn_play" || id == "btn_pause" || id == "mini_play" -> Player.playPause()
            id == "btn_shuffle" -> Player.toggleShuffle()
            id == "btn_repeat" -> Player.toggleRepeat()
            id == "link" -> Player.openApp(context)
            id == "time" -> showRemaining = !showRemaining
        }
        invalidate()
    }
}
