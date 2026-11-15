package io.github.oatomh.spox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import org.json.JSONObject

/**
 * The desktop skin (PNGs + skin.json from /skin), drawn pixel-perfect: every sprite is scaled by a
 * whole number with no smoothing, exactly like SpoX on Windows.
 */
class Skin private constructor(val json: JSONObject, private val sprites: Map<String, Bitmap>) {

    private val plain = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val tinted = HashMap<Int, Paint>()
    private val fill = Paint().apply { isAntiAlias = false }

    // font: 8x16 cells, white glyphs tinted when drawn; widths measured from the pixels
    private val font = sprites["font"]
    private val fontCols = json.optJSONObject("font")?.optInt("cols", 16) ?: 16
    private val fontFirst = json.optJSONObject("font")?.optInt("first", 32) ?: 32
    private val fontTop = json.optJSONObject("font")?.optInt("top_rows", 2) ?: 2
    private val fontSpace = json.optJSONObject("font")?.optInt("space", 4) ?: 4
    private val fontSpacing = json.optJSONObject("font")?.optInt("spacing", 1) ?: 1
    val lineHeight = json.optJSONObject("font")?.optInt("line_height", 10) ?: 10
    private val widths: IntArray = measureGlyphs()

    fun sprite(name: String): Bitmap? = sprites[name]

    fun color(name: String, fallback: Int = Color.WHITE): Int =
        json.optJSONObject("colors")?.optString(name)?.takeIf { it.startsWith("#") }?.let { Color.parseColor(it) } ?: fallback

    fun pos(name: String): IntArray? = json.optJSONObject("pos")?.optJSONArray(name)?.let { intArrayOf(it.getInt(0), it.getInt(1)) }

    fun rect(name: String): IntArray? =
        json.optJSONObject("rect")?.optJSONArray(name)?.let { intArrayOf(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3)) }

    fun size(name: String): IntArray = sprites[name]?.let { intArrayOf(it.width, it.height) } ?: intArrayOf(0, 0)

    private fun tint(c: Int): Paint = tinted.getOrPut(c) {
        Paint().apply {
            isFilterBitmap = false
            colorFilter = PorterDuffColorFilter(c, PorterDuff.Mode.MULTIPLY)
            alpha = Color.alpha(c)
        }
    }

    /** Sprite at 1x position (x, y), scale s. */
    fun draw(cv: Canvas, name: String, x: Float, y: Float, s: Float, alpha: Int = 255) {
        val b = sprites[name] ?: return
        plain.alpha = alpha
        cv.drawBitmap(b, null, RectF(x * s, y * s, (x + b.width) * s, (y + b.height) * s), plain)
        plain.alpha = 255
    }

    /** Part of a sprite (source rect in sprite pixels) into a 1x rect. */
    fun drawPart(cv: Canvas, name: String, src: Rect, x: Float, y: Float, w: Float, h: Float, s: Float, color: Int? = null) {
        val b = sprites[name] ?: return
        cv.drawBitmap(b, src, RectF(x * s, y * s, (x + w) * s, (y + h) * s), if (color != null) tint(color) else plain)
    }

    /** 9-slice: corners stay sharp, edges and middle stretch. */
    fun nine(cv: Canvas, name: String, x: Float, y: Float, w: Float, h: Float, s: Float, corner: Int = 4) {
        val b = sprites[name] ?: return
        val c = minOf(corner, b.width / 2, b.height / 2)
        val xs = listOf(Triple(0, c, 0f to c.toFloat()), Triple(c, b.width - c, c.toFloat() to w - 2 * c), Triple(b.width - c, b.width, w - c to c.toFloat()))
        val ys = listOf(Triple(0, c, 0f to c.toFloat()), Triple(c, b.height - c, c.toFloat() to h - 2 * c), Triple(b.height - c, b.height, h - c to c.toFloat()))
        for ((u0, u1, dx) in xs) for ((v0, v1, dy) in ys) {
            if (dx.second <= 0 || dy.second <= 0 || u1 <= u0 || v1 <= v0) continue
            cv.drawBitmap(b, Rect(u0, v0, u1, v1), RectF((x + dx.first) * s, (y + dy.first) * s, (x + dx.first + dx.second) * s, (y + dy.first + dy.second) * s), plain)
        }
    }

    fun fill(cv: Canvas, x: Float, y: Float, w: Float, h: Float, s: Float, c: Int) {
        fill.color = c
        cv.drawRect(x * s, y * s, (x + w) * s, (y + h) * s, fill)
    }

    // ---- pixel font -----------------------------------------------------------------------

    private fun measureGlyphs(): IntArray {
        val f = font ?: return IntArray(0)
        val n = (f.width / 8) * (f.height / 16)
        return IntArray(n) { i ->
            val cx = (i % fontCols) * 8
            val cy = (i / fontCols) * 16
            var last = -1
            for (x in 0 until 8) for (y in 0 until 16) {
                if (cx + x < f.width && cy + y < f.height && Color.alpha(f.getPixel(cx + x, cy + y)) > 0) last = maxOf(last, x)
            }
            if (last < 0) 0 else last + 1
        }
    }

    private fun glyphIndex(ch: Char): Int {
        val i = ch.code - fontFirst
        return if (i in widths.indices) i else ('?'.code - fontFirst)
    }

    fun textWidth(t: String): Int {
        var w = 0
        for (ch in t) {
            w += if (ch == ' ') fontSpace else {
                val gi = glyphIndex(ch)
                (if (gi in widths.indices) widths[gi] else 0) + fontSpacing
            }
        }
        return w
    }

    /** Text at 1x position (x, y) with scale s. Returns the width in 1x pixels. */
    fun text(cv: Canvas, t: String, x: Float, y: Float, s: Float, color: Int): Int {
        val f = font ?: return 0
        var cx = x
        for (ch in t) {
            if (ch == ' ') {
                cx += fontSpace; continue
            }
            val gi = glyphIndex(ch)
            if (gi !in widths.indices) continue
            val gw = widths[gi]
            if (gw > 0) {
                val sx = (gi % fontCols) * 8
                val sy = (gi / fontCols) * 16
                drawPartRaw(cv, f, Rect(sx, sy, sx + gw, sy + 16), cx, y - fontTop, gw.toFloat(), 16f, s, color)
            }
            cx += gw + fontSpacing
        }
        return (cx - x).toInt()
    }

    private fun drawPartRaw(cv: Canvas, b: Bitmap, src: Rect, x: Float, y: Float, w: Float, h: Float, s: Float, color: Int) {
        cv.drawBitmap(b, src, RectF(x * s, y * s, (x + w) * s, (y + h) * s), tint(color))
    }

    /** Cuts text to fit `w` pixels, with "..." at the end. */
    fun fit(t: String, w: Int): String {
        if (textWidth(t) <= w) return t
        var s = t
        while (s.isNotEmpty() && textWidth("$s...") > w) s = s.dropLast(1)
        return "$s..."
    }

    companion object {
        @Volatile private var cached: Skin? = null

        fun get(ctx: Context): Skin = cached ?: synchronized(this) {
            cached ?: load(ctx).also { cached = it }
        }

        /** Forget the loaded skin (after changing the colour pack). */
        fun reload() { cached = null }

        // ---- colour packs (the same ones as SpoX on Windows) ----------------------------------
        // name, hue turn, saturation, grey tint colour, tint strength, grey brightness
        class Pack(val name: String, val hue: Float, val sat: Float, val tint: Int, val tintK: Float, val light: Float)

        val PACKS = listOf(
            Pack("Default", 0f, 1f, 0x000000, 0f, 1f),
            Pack("Amber CRT", -100f, 1f, 0xFFAA3C, 0.12f, 0.95f),
            Pack("Ice", 55f, 0.9f, 0x78BEFF, 0.10f, 1f),
            Pack("Rose", -160f, 0.9f, 0xFF78A0, 0.08f, 1f),
            Pack("Lime", -55f, 1f, 0x000000, 0f, 1f),
            Pack("Mono", 0f, 0f, 0x000000, 0f, 1f),
            Pack("Game Boy", -60f, 0.55f, 0x96AA28, 0.35f, 1.05f),
            Pack("Vapor", 160f, 1f, 0xC850FF, 0.14f, 1f),
            Pack("Blood", -140f, 1f, 0xFF1E1E, 0.10f, 0.85f),
            Pack("Ocean", 70f, 1f, 0x2878C8, 0.16f, 0.95f),
            Pack("Sunset", -120f, 1.1f, 0xFF6E3C, 0.12f, 1f),
            Pack("Paper", 0f, 0.8f, 0xFFF0D2, 0.10f, 1.6f),
        )

        fun packName(ctx: Context) = ctx.getSharedPreferences("spox", 0).getString("pack", "Default") ?: "Default"

        /** Accent (coloured) pixels get their hue turned and saturation scaled; greys keep their
         *  shape and only get the pack's tint / brightness. Same maths as the desktop app. */
        private fun recolor(c: Int, p: Pack): Int {
            val a = Color.alpha(c)
            val r = Color.red(c) / 255f; val g = Color.green(c) / 255f; val b = Color.blue(c) / 255f
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = mx - mn
            val sat = if (mx > 0) d / mx else 0f
            if (sat < 0.22f || d < 0.06f) {
                if (p.tintK == 0f && p.light == 1f) return c
                fun q(v: Int, t: Int): Int {
                    val x = v * p.light
                    return (x + (t * maxOf(x / 255f, 0.25f) - x) * p.tintK).toInt().coerceIn(0, 255)
                }
                return Color.argb(a, q(Color.red(c), (p.tint shr 16) and 255), q(Color.green(c), (p.tint shr 8) and 255), q(Color.blue(c), p.tint and 255))
            }
            var h = when (mx) { r -> 60f * (((g - b) / d).mod(6f)); g -> 60f * ((b - r) / d + 2f); else -> 60f * ((r - g) / d + 4f) }
            h = (h + p.hue).mod(360f)
            val s2 = (sat * p.sat).coerceIn(0f, 1f)
            val cc = mx * s2
            val x = cc * (1f - kotlin.math.abs((h / 60f).mod(2f) - 1f))
            val m = mx - cc
            val (r1, g1, b1) = when ((h / 60f).toInt()) { 0 -> Triple(cc, x, 0f); 1 -> Triple(x, cc, 0f); 2 -> Triple(0f, cc, x); 3 -> Triple(0f, x, cc); 4 -> Triple(x, 0f, cc); else -> Triple(cc, 0f, x) }
            fun q(f: Float) = ((f + m) * 255f).toInt().coerceIn(0, 255)
            return Color.argb(a, q(r1), q(g1), q(b1))
        }

        private fun load(ctx: Context): Skin {
            val am = ctx.assets
            val json = runCatching { JSONObject(am.open("skin/skin.json").bufferedReader().readText()) }.getOrElse { JSONObject() }
            val opts = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888; inMutable = true }
            val pack = PACKS.firstOrNull { it.name == packName(ctx) }?.takeIf { it.name != "Default" }
            val cache = HashMap<Int, Int>()
            fun rc(c: Int) = if (pack == null) c else cache.getOrPut(c) { recolor(c, pack) }
            val map = HashMap<String, Bitmap>()
            for (f in am.list("skin") ?: emptyArray()) {
                if (!f.endsWith(".png")) continue
                runCatching { am.open("skin/$f").use { BitmapFactory.decodeStream(it, null, opts) } }.getOrNull()?.let { b ->
                    if (pack != null) {
                        val px = IntArray(b.width * b.height)
                        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
                        for (i in px.indices) if (Color.alpha(px[i]) != 0) px[i] = rc(px[i])
                        b.setPixels(px, 0, b.width, 0, 0, b.width, b.height)
                    }
                    map[f.removeSuffix(".png")] = b
                }
            }
            if (pack != null) json.optJSONObject("colors")?.let { cols ->
                for (k in cols.keys().asSequence().toList()) {
                    val v = cols.optString(k)
                    if (v.startsWith("#") && v.length == 7) {
                        val c = rc(Color.parseColor(v))
                        cols.put(k, String.format("#%06X", c and 0xFFFFFF))
                    }
                }
            }
            return Skin(json, map)
        }
    }
}
