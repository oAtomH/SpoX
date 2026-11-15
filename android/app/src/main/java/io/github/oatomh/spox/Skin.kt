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

        private fun load(ctx: Context): Skin {
            val am = ctx.assets
            val json = runCatching { JSONObject(am.open("skin/skin.json").bufferedReader().readText()) }.getOrElse { JSONObject() }
            val opts = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val map = HashMap<String, Bitmap>()
            for (f in am.list("skin") ?: emptyArray()) {
                if (!f.endsWith(".png")) continue
                runCatching { am.open("skin/$f").use { BitmapFactory.decodeStream(it, null, opts) } }.getOrNull()?.let {
                    map[f.removeSuffix(".png")] = it
                }
            }
            return Skin(json, map)
        }
    }
}
