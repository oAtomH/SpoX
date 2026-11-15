package io.github.oatomh.spox

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.widget.RemoteViews

/**
 * Home-screen (and lock-screen, where allowed) widget: the taskbar player from the desktop app,
 * drawn as one pixel-art picture with invisible buttons on top.
 */
class PlayerWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = updateAll(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            "w_prev" -> Player.prev()
            "w_next" -> Player.next()
            "w_play" -> Player.playPause()
            else -> super.onReceive(ctx, intent)
        }
    }

    companion object {
        // layout in 1x pixels: art 16 | gap 2 | text 92 | gap 2 | 3 buttons of 16 = 160 x 16 (10 parts of 16)
        private const val TW = 92
        private const val W = 16 + 2 + TW + 2 + 48
        private const val H = 16
        private const val S = 8

        private var lastKey = ""

        fun render(ctx: Context): Bitmap {
            val skin = Skin.get(ctx)
            val st = Player.state
            val bmp = Bitmap.createBitmap(W * S, H * S, Bitmap.Config.ARGB_8888)
            val cv = Canvas(bmp)
            val s = S.toFloat()
            skin.nine(cv, "tb_bg", 0f, 0f, W.toFloat(), H.toFloat(), s, 4)
            val art = st.art
            if (art != null) {
                val px = Bitmap.createScaledBitmap(art, 16, 16, true)
                cv.drawBitmap(px, null, RectF(0f, 0f, 16 * s, 16 * s), Paint().apply { isFilterBitmap = false })
            } else skin.draw(cv, "tb_art_empty", 0f, 0f, s)
            cv.save()
            cv.clipRect(18 * s, 0f, (18 + TW) * s, H * s)
            // text at half the scale, so two lines fit in the 16 px row (coordinates in half pixels)
            val t1 = if (st.hasTrack) skin.fit(st.title, TW * 2) else "SpoX"
            val t2 = if (st.hasTrack) skin.fit(st.artist, TW * 2) else if (Player.hasAccess(ctx)) "Nothing playing" else "Open SpoX to set up"
            skin.text(cv, t1, 36f, 7f, s / 2, skin.color("tb_title", skin.color("title")))
            skin.text(cv, t2, 36f, 18f, s / 2, skin.color("tb_artist", skin.color("info")))
            cv.restore()
            val bx = 18f + TW + 2
            skin.draw(cv, "tb_prev", bx, 0f, s)
            skin.draw(cv, if (st.playing) "tb_pause" else "tb_play", bx + 16, 0f, s)
            skin.draw(cv, "tb_next", bx + 32, 0f, s)
            skin.nine(cv, "tb_frame", 0f, 0f, W.toFloat(), H.toFloat(), s, 2)
            return bmp
        }

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, PlayerWidget::class.java))
            if (ids.isEmpty()) return
            val st = Player.state
            val key = "${st.title}|${st.artist}|${st.playing}|${st.art?.generationId}"
            if (key == lastKey) return
            lastKey = key
            mgr.updateAppWidget(ids, views(ctx))
        }

        /** The pixel player picture with its four touch zones (widget and pixel notification). */
        fun views(ctx: Context): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            rv.setImageViewBitmap(R.id.w_image, render(ctx))
            fun pi(a: String) = PendingIntent.getBroadcast(ctx, a.hashCode(), Intent(ctx, PlayerWidget::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
            rv.setOnClickPendingIntent(R.id.w_prev, pi("w_prev"))
            rv.setOnClickPendingIntent(R.id.w_play, pi("w_play"))
            rv.setOnClickPendingIntent(R.id.w_next, pi("w_next"))
            rv.setOnClickPendingIntent(R.id.w_open, PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            return rv
        }
    }
}
