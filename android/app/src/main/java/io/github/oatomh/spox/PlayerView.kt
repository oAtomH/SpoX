package io.github.oatomh.spox

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The SpoX player, drawn from the desktop skin at a whole-number scale: the main window on top,
 * and below it a panel with Lyrics / Up next / Settings.
 */
class PlayerView(ctx: Context) : View(ctx) {
    private val skin = Skin.get(ctx)
    private val mainW = skin.json.optJSONArray("main_size")?.optInt(0, 292) ?: 292
    private val mainH = skin.json.optJSONArray("main_size")?.optInt(1, 112) ?: 112
    private var s = 4f
    private var ox = 0f
    private var oy = 0f

    enum class Tab(val label: String) { LYRICS("Lyrics"), QUEUE("Up next"), SETTINGS("Settings") }
    var tab = Tab.LYRICS
    var showRemaining = false
    private var t0 = SystemClock.elapsedRealtime()

    // panel list
    private var scroll = 0f
    private var manualScrollUntil = 0L
    private var rowsShown = 0

    // touch
    private var pressed: String? = null
    private var dragging: String? = null
    private var seekPreview = -1L
    private var lastY = 0f
    private var downY = 0f

    // lyrics for the current song
    private var lyricsKey = ""
    private var lyrics: Lyrics.Result? = null
    private var lyricsState = "Play something to see lyrics."

    // pixel-art album cover
    private var artSrc: Bitmap? = null
    private var artPix: Bitmap? = null

    // slow system lookups, done now and then instead of every frame
    private var access = Player.hasAccess(ctx)
    private var vol = Player.volume(ctx)
    private var volAt = 0L
    private var animating = false

    /** Call when coming back to the app (access may have changed). */
    fun refresh() {
        access = Player.hasAccess(context)
        vol = Player.volume(context)
        invalidate()
    }

    var onSettingsAction: ((String) -> Unit)? = null
    var settingsRows: () -> List<Pair<String, String>> = { emptyList() }

    private val playerChanged: () -> Unit = { invalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Player.listen(playerChanged)
    }

    override fun onDetachedFromWindow() {
        Player.unlisten(playerChanged)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // biggest whole-number scale that fits the width (pixels stay square and sharp)
        s = max(1f, floor((w * 0.96f) / mainW))
        ox = floor((w - mainW * s) / 2f)
        oy = floor(h * 0.04f)
    }

    private fun lx(x: Float) = (x - ox) / s
    private fun ly(y: Float) = (y - oy) / s
    private fun inside(px: Float, py: Float, r: IntArray) = px >= r[0] && px < r[0] + r[2] && py >= r[1] && py < r[1] + r[3]
    private fun btnRect(name: String): IntArray? {
        val p = skin.pos(name) ?: return null
        val sz = skin.size(name)
        return intArrayOf(p[0], p[1], sz[0], sz[1])
    }

    private val buttons = listOf("btn_prev", "btn_play", "btn_pause", "btn_next", "btn_shuffle", "btn_repeat", "btn_link", "btn_menu")

    // panel geometry in 1x pixels
    private fun panelTop() = mainH + 4f
    private fun panelH() = (height - oy) / s - panelTop() - 2
    private fun listRect() = floatArrayOf(10f, panelTop() + 18f, mainW - 20f, panelH() - 24f)

    override fun onDraw(cv: Canvas) {
        val st = Player.state
        val now = SystemClock.elapsedRealtime()
        cv.drawColor(skin.color("anim_bg", Color.rgb(10, 10, 11)))
        cv.save()
        cv.translate(ox, oy)
        drawMain(cv, st, now)
        drawPanel(cv, st, now)
        cv.restore()
        // smooth (every screen refresh) while anything moves; otherwise twice a second for the clock
        if (st.playing || animating || dragging != null) postInvalidateOnAnimation() else postInvalidateDelayed(500)
        animating = false
    }

    private fun drawMain(cv: Canvas, st: Player.State, now: Long) {
        skin.draw(cv, "main_bg", 0f, 0f, s)
        skin.draw(cv, "btn_menu", skin.pos("btn_menu")?.get(0)?.toFloat() ?: 4f, skin.pos("btn_menu")?.get(1)?.toFloat() ?: 4f, s)

        // album art, pixelated like on desktop
        val ar = skin.rect("art") ?: intArrayOf(12, 24, 48, 48)
        if (st.art != null) {
            if (st.art !== artSrc) {
                artSrc = st.art
                val n = skin.json.optInt("art_pixels", 24)
                artPix = Bitmap.createScaledBitmap(st.art, n, n, true)
            }
            artPix?.let { cv.drawBitmap(it, null, android.graphics.RectF(ar[0] * s, ar[1] * s, (ar[0] + ar[2]) * s, (ar[1] + ar[3]) * s), android.graphics.Paint().apply { isFilterBitmap = false }) }
        } else {
            skin.draw(cv, "art_empty", ar[0].toFloat(), ar[1].toFloat(), s)
        }

        // LCD timer with faint "8"s behind
        val tp = skin.pos("timer") ?: intArrayOf(72, 24)
        val dur = st.durationMs
        val pos = if (dragging == "seek" && seekPreview >= 0) seekPreview else st.position(now)
        val shown = if (showRemaining) max(0, dur - pos) else pos
        val secs = shown / 1000
        val txt = if (st.hasTrack) String.format("%02d:%02d", min(99, secs / 60), secs % 60) else "  :  "
        val dj = skin.json.optJSONObject("digits")
        val dw = dj?.optInt("width", 12) ?: 12
        val colonW = dj?.optInt("colon_width", dw) ?: dw
        val ghost = (dj?.optDouble("ghost", 0.13) ?: 0.13).toFloat()
        val order = dj?.optString("order", "0123456789:- ") ?: "0123456789:- "
        val dh = skin.size("digits")[1]
        val dcol = skin.color("digits")
        val ghostCol = Color.argb((255 * ghost).toInt(), Color.red(dcol), Color.green(dcol), Color.blue(dcol))
        val blinkOff = !st.playing && st.hasTrack && ((now - t0) / 500) % 2 == 1L
        var x = tp[0].toFloat()
        for (ch in txt) {
            if (ch == ':') {
                val gi = order.indexOf(':')
                val c0 = (dw - colonW) / 2
                if (!blinkOff && gi >= 0) skin.drawPart(cv, "digits", Rect(gi * dw + c0, 0, gi * dw + c0 + colonW, dh), x, tp[1].toFloat(), colonW.toFloat(), dh.toFloat(), s, dcol)
                x += colonW
                continue
            }
            val e = order.indexOf('8')
            if (e >= 0) skin.drawPart(cv, "digits", Rect(e * dw, 0, e * dw + dw, dh), x, tp[1].toFloat(), dw.toFloat(), dh.toFloat(), s, ghostCol)
            val gi = order.indexOf(ch)
            if (!blinkOff && ch != ' ' && gi >= 0) skin.drawPart(cv, "digits", Rect(gi * dw, 0, gi * dw + dw, dh), x, tp[1].toFloat(), dw.toFloat(), dh.toFloat(), s, dcol)
            x += dw
        }
        if (showRemaining && st.hasTrack) {
            val mp = skin.pos("minus") ?: intArrayOf(64, 24)
            val mi = order.indexOf('-')
            if (mi >= 0 && !blinkOff) skin.drawPart(cv, "digits", Rect(mi * dw + 4, 0, mi * dw + 8, dh), mp[0].toFloat(), tp[1].toFloat(), 4f, dh.toFloat(), s, dcol)
        }

        // visualizer: on the phone it dances to the beat of the clock (no sound access needed)
        val vr = skin.rect("vis") ?: intArrayOf(64, 44, 68, 28)
        val vj = skin.json.optJSONObject("vis")
        val bars = vj?.optInt("bars", 17) ?: 17
        val bw = vj?.optInt("bar_width", 3) ?: 3
        val gap = vj?.optInt("gap", 1) ?: 1
        val cols = skin.sprite("vis_colors")
        val t = (now - t0) / 1000f
        for (i in 0 until bars) {
            val bx = vr[0] + i * (bw + gap)
            if (bx + bw > vr[0] + vr[2]) break
            val xr = i / bars.toFloat()
            val v = if (st.playing) ((0.15f + 0.55f * (sin(t * 3.1f + xr * 9f) * 0.5f + 0.5f) * (sin(t * 5.3f - xr * 4f) * 0.5f + 0.5f)) * (1f - xr * 0.45f)) else 0f
            val bh = max(1, (v * vr[3]).toInt())
            for (yy in 0 until bh) {
                val py = vr[1] + vr[3] - 1 - yy
                val c = visCol(cols, (py - vr[1]).toFloat() / vr[3])
                skin.fill(cv, bx.toFloat(), py.toFloat(), bw.toFloat(), 1f, s, c)
            }
        }

        // title / artist / album
        val marquee = { r: IntArray, text: String, col: Int ->
            cv.save()
            cv.clipRect(r[0] * s, r[1] * s, (r[0] + r[2]) * s, (r[1] + r[3]) * s)
            val w = skin.textWidth(text)
            if (w <= r[2]) skin.text(cv, text, r[0].toFloat(), r[1].toFloat() + 1, s, col)
            else {
                animating = true
                val period = w + 24
                val cyc = 2.5f + period / 14f
                val ph = ((now - t0) / 1000f) % cyc
                val off = if (ph < 2.5f) 0f else floor((ph - 2.5f) * 14f)
                skin.text(cv, text, r[0] - off, r[1].toFloat() + 1, s, col)
                skin.text(cv, text, r[0] - off + period, r[1].toFloat() + 1, s, col)
            }
            cv.restore()
        }
        val title = when {
            !access -> "ALLOW NOTIFICATION ACCESS"
            !st.hasTrack -> "Play something in Spotify"
            else -> st.title
        }
        skin.rect("title")?.let { marquee(it, title, skin.color("title")) }
        skin.rect("artist")?.let { marquee(it, st.artist, skin.color("info")) }
        skin.rect("album")?.let { marquee(it, st.album, skin.color("album", skin.color("info"))) }

        // seek bar
        skin.rect("seek")?.let { r ->
            skin.nine(cv, "seek_track", r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[3].toFloat(), s, 2)
            val f = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            val fw = floor(r[2] * f)
            if (fw > 0) skin.nine(cv, "seek_fill", r[0].toFloat(), r[1].toFloat(), fw, r[3].toFloat(), s, 2)
            val th = skin.size("seek_thumb")
            if (dur > 0) skin.draw(cv, "seek_thumb", (r[0] + fw - th[0] / 2).coerceIn(r[0].toFloat(), (r[0] + r[2] - th[0]).toFloat()), r[1] + (r[3] - th[1]) / 2f, s)
        }
        // volume = the phone's media volume
        skin.rect("vol")?.let { r ->
            skin.nine(cv, "vol_track", r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[3].toFloat(), s, 2)
            if (SystemClock.elapsedRealtime() - volAt > 1000 && dragging != "vol") {
                vol = Player.volume(context)
                volAt = SystemClock.elapsedRealtime()
            }
            val fw = floor(r[2] * vol)
            if (fw > 0) skin.nine(cv, "vol_fill", r[0].toFloat(), r[1].toFloat(), fw, r[3].toFloat(), s, 2)
            val th = skin.size("vol_thumb")
            skin.draw(cv, "vol_thumb", (r[0] + fw - th[0] / 2).coerceIn(r[0].toFloat(), (r[0] + r[2] - th[0]).toFloat()), r[1] + (r[3] - th[1]) / 2f, s)
        }

        // buttons (pressed look while held, and for the active state)
        for (b in buttons) {
            if (b == "btn_menu") continue
            val p = skin.pos(b) ?: continue
            val active = when (b) {
                "btn_play" -> st.playing
                "btn_pause" -> !st.playing && st.hasTrack
                "btn_shuffle" -> st.shuffle
                "btn_repeat" -> st.repeat
                else -> false
            }
            val name = if ((pressed == b || active) && skin.sprite("${b}_down") != null) "${b}_down" else b
            skin.draw(cv, name, p[0].toFloat(), p[1].toFloat(), s)
        }
    }

    private fun drawPanel(cv: Canvas, st: Player.State, now: Long) {
        val top = panelTop()
        val h = panelH()
        skin.nine(cv, "panel_bg", 0f, top, mainW.toFloat(), h, s, 4)
        // tabs
        var tx = 12f
        for (t in Tab.values()) {
            val col = if (t == tab) skin.color("header") else skin.color("list_dim")
            skin.text(cv, t.label, tx, top + 5, s, col)
            if (t == tab) skin.fill(cv, tx, top + 14, skin.textWidth(t.label).toFloat(), 1f, s, skin.color("title"))
            tx += skin.textWidth(t.label) + 14
        }
        val lr = listRect()
        skin.nine(cv, "list_bg", lr[0] - 3, lr[1] - 3, lr[2] + 6, lr[3] + 6, s, 4)
        val lh = skin.lineHeight.toFloat()
        val rows = mutableListOf<Triple<String, Int, Boolean>>() // text, color, centered
        var current = -1
        if (!access) {
            val c = skin.color("list")
            listOf(
                "SpoX needs Notification access to see and",
                "control what Spotify is playing.",
                "",
                "Tap here, then turn SpoX on.",
                "(Android may warn you: SpoX only reads",
                "the music player, nothing else.)",
            ).forEach { rows += Triple(it, c, false) }
        } else when (tab) {
            Tab.LYRICS -> {
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
                                lyricsState = if (r == null) "No lyrics found on LRCLIB." else ""
                                invalidate()
                            }
                        }
                    }
                }
                val l = lyrics
                if (l == null) rows += Triple(lyricsState, skin.color("list_dim"), true)
                else {
                    val p = st.position(now) + 250
                    current = if (l.synced) l.lines.indexOfLast { it.first <= p } else -1
                    val maxW = (lr[2] - 4).toInt()
                    val wrapped = wrapCache.takeIf { wrapKey == "$lyricsKey|$maxW" } ?: wrap(l, maxW).also {
                        wrapCache = it
                        wrapKey = "$lyricsKey|$maxW"
                    }
                    var curRow = -1
                    for ((li, text) in wrapped) {
                        val col = when {
                            li == current -> skin.color("lyric_current")
                            !l.synced || li < current -> skin.color("lyric")
                            else -> skin.color("lyric_next", skin.color("lyric"))
                        }
                        if (li == current && curRow < 0) curRow = rows.size
                        rows += Triple(text, col, true)
                    }
                    current = curRow
                }
            }
            Tab.QUEUE -> {
                val q = Player.queue()
                if (q.isEmpty()) rows += Triple("Spotify didn't share its queue.", skin.color("list_dim"), true)
                q.forEach { rows += Triple(it, skin.color("list"), false) }
            }
            Tab.SETTINGS -> settingsRows().forEach { (l, v) -> rows += Triple("$l\u0000$v", skin.color("list"), false) }
        }
        rowsShown = rows.size
        val vis = floor(lr[3] / lh).toInt()
        // lyrics follow the song unless you scrolled by hand a moment ago
        if (tab == Tab.LYRICS && current >= 0 && SystemClock.elapsedRealtime() > manualScrollUntil) {
            scroll = max(0f, (current - vis / 2 + 1).toFloat())
        }
        scroll = scroll.coerceIn(0f, max(0, rows.size - vis).toFloat())
        cv.save()
        cv.clipRect(lr[0] * s, lr[1] * s, (lr[0] + lr[2]) * s, (lr[1] + lr[3]) * s)
        val first = floor(scroll).toInt()
        for (i in first until min(rows.size, first + vis + 1)) {
            val (text, col, center) = rows[i]
            val y = lr[1] + (i - scroll) * lh
            if (text.contains('\u0000')) {
                val (l, v) = text.split('\u0000')
                skin.text(cv, l, lr[0] + 2, y + 1, s, col)
                val vw = skin.textWidth(v)
                skin.text(cv, v, lr[0] + lr[2] - vw - 2, y + 1, s, skin.color("list_dim"))
                continue
            }
            val fitted = skin.fit(text, lr[2].toInt() - 4)
            val x = if (center) lr[0] + (lr[2] - skin.textWidth(fitted)) / 2 else lr[0] + 2
            skin.text(cv, fitted, x, y + 1, s, col)
        }
        cv.restore()
    }

    private var visColors: IntArray? = null
    private fun visCol(b: Bitmap?, frac: Float): Int {
        if (b == null) return skin.color("title")
        val c = visColors ?: IntArray(b.height) { b.getPixel(0, it) }.also { visColors = it }
        return c[min(c.size - 1, (frac * c.size).toInt().coerceAtLeast(0))]
    }

    private var wrapKey = ""
    private var wrapCache: List<Pair<Int, String>> = emptyList()

    /** Lyrics lines cut to the panel width once per song (not every frame). */
    private fun wrap(l: Lyrics.Result, maxW: Int): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        l.lines.forEachIndexed { i, (_, text) ->
            var rest = text
            if (rest.isEmpty()) out += i to ""
            while (rest.isNotEmpty()) {
                var cut = rest
                while (skin.textWidth(cut) > maxW && cut.contains(' ')) cut = cut.substringBeforeLast(' ')
                if (skin.textWidth(cut) > maxW) cut = skin.fit(cut, maxW).dropLast(3)
                if (cut.isEmpty()) break
                out += i to cut
                rest = rest.removePrefix(cut).trimStart()
            }
        }
        return out
    }

    // ---- touch ----------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val px = lx(e.x)
        val py = ly(e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = buttons.firstOrNull { b -> btnRect(b)?.let { inside(px, py, it) } == true }
                dragging = when {
                    skin.rect("seek")?.let { inside(px, py, intArrayOf(it[0], it[1] - 4, it[2], it[3] + 8)) } == true -> "seek"
                    skin.rect("vol")?.let { inside(px, py, intArrayOf(it[0], it[1] - 4, it[2], it[3] + 8)) } == true -> "vol"
                    py > panelTop() -> "list"
                    else -> null
                }
                lastY = e.y
                downY = e.y
                drag(px)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging == "list") {
                    val lh = skin.lineHeight * s
                    scroll -= (e.y - lastY) / lh
                    lastY = e.y
                    manualScrollUntil = SystemClock.elapsedRealtime() + 4000
                } else drag(px)
                if (pressed != null && btnRect(pressed!!)?.let { inside(px, py, it) } != true) pressed = null
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (dragging == "seek" && seekPreview >= 0) Player.seek(seekPreview)
                val tapped = kotlin.math.abs(e.y - downY) < 12 * resources.displayMetrics.density
                pressed?.let { b -> if (btnRect(b)?.let { inside(px, py, it) } == true) press(b) }
                if (pressed == null && tapped) tap(px, py)
                pressed = null
                dragging = null
                seekPreview = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null; dragging = null; seekPreview = -1
            }
        }
        return true
    }

    private fun drag(px: Float) {
        when (dragging) {
            "seek" -> skin.rect("seek")?.let { r ->
                val f = ((px - r[0]) / r[2]).coerceIn(0f, 1f)
                seekPreview = (Player.state.durationMs * f).toLong()
            }
            "vol" -> skin.rect("vol")?.let { r ->
                vol = ((px - r[0]) / r[2]).coerceIn(0f, 1f)
                Player.setVolume(context, vol)
            }
        }
    }

    private fun press(b: String) {
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        when (b) {
            "btn_prev" -> Player.prev()
            "btn_next" -> Player.next()
            "btn_play", "btn_pause" -> Player.playPause()
            "btn_shuffle" -> Player.toggleShuffle()
            "btn_repeat" -> Player.toggleRepeat()
            "btn_link" -> Player.openApp(context)
            "btn_menu" -> { tab = Tab.SETTINGS; scroll = 0f }
        }
    }

    private fun tap(px: Float, py: Float) {
        // the clock: elapsed / remaining
        val tp = skin.pos("timer") ?: intArrayOf(72, 24)
        if (inside(px, py, intArrayOf(tp[0] - 8, tp[1], 72, skin.size("digits")[1]))) {
            showRemaining = !showRemaining; return
        }
        val top = panelTop()
        if (!access && py > top) {
            onSettingsAction?.invoke("access"); return
        }
        // tabs
        if (py >= top + 2 && py < top + 16) {
            var tx = 12f
            for (t in Tab.values()) {
                val w = skin.textWidth(t.label)
                if (px >= tx - 4 && px < tx + w + 4) {
                    tab = t; scroll = 0f; return
                }
                tx += w + 14
            }
        }
        // settings rows
        if (tab == Tab.SETTINGS) {
            val lr = listRect()
            if (px >= lr[0] && px < lr[0] + lr[2] && py >= lr[1] && py < lr[1] + lr[3]) {
                val i = floor((py - lr[1]) / skin.lineHeight + scroll).toInt()
                settingsRows().getOrNull(i)?.let { onSettingsAction?.invoke(it.first) }
            }
        }
        // the art opens Spotify
        val ar = skin.rect("art") ?: intArrayOf(12, 24, 48, 48)
        if (inside(px, py, ar)) Player.openApp(context)
    }
}
