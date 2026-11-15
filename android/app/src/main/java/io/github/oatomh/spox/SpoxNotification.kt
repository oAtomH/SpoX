package io.github.oatomh.spox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * SpoX's own pixel player in the notification shade / lock screen / Now Bar. It mirrors the music
 * app and sends the buttons back to it. (Spotify's own player stays next to it for now.)
 */
object SpoxNotification {
    private const val CHANNEL = "player"
    private const val ID = 77
    private var session: MediaSession? = null
    private var lastKey = ""

    /** "pixel" = SpoX's own pixel card, "media" = Android's media player look (Now Bar), "off". */
    fun style(ctx: Context): String = ctx.getSharedPreferences("spox", 0).getString("notif_style", "media") ?: "media"
    fun enabled(ctx: Context) = style(ctx) != "off"

    fun cycleStyle(ctx: Context) {
        val next = when (style(ctx)) { "media" -> "pixel"; "pixel" -> "off"; else -> "media" }
        ctx.getSharedPreferences("spox", 0).edit().putString("notif_style", next).apply()
        clear(ctx)
        update(ctx)
    }

    private fun session(ctx: Context): MediaSession = session ?: MediaSession(ctx, "SpoX").also { s ->
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { Player.play() }
            override fun onPause() { Player.pause() }
            override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                when (action) {
                    "shuffle" -> Player.toggleShuffle()
                    "repeat" -> Player.toggleRepeat()
                }
            }
            override fun onSkipToNext() { Player.next() }
            override fun onSkipToPrevious() { Player.prev() }
            override fun onSeekTo(pos: Long) { Player.seek(pos) }
        })
        s.isActive = true
        session = s
    }

    private fun clear(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID)
        session?.isActive = false
        session?.release()
        session = null
        lastKey = ""
    }

    /** Album art as chunky pixels (nearest-neighbour upscale). */
    private fun pixelArt(src: Bitmap?, ctx: Context): Bitmap? {
        val n = Skin.get(ctx).json.optInt("art_pixels", 24)
        val small = src?.let { Bitmap.createScaledBitmap(it, n, n, true) } ?: Skin.get(ctx).sprite("icon") ?: return null
        val big = Bitmap.createBitmap(small.width * 8, small.height * 8, Bitmap.Config.ARGB_8888)
        Canvas(big).drawBitmap(small, null, android.graphics.Rect(0, 0, big.width, big.height), Paint().apply { isFilterBitmap = false })
        return big
    }

    /** The taskbar player picture as the whole notification, buttons on top (Android still adds its
     *  small header line; everything else is pixels). */
    private fun pixelCard(ctx: Context, nm: NotificationManager, st: Player.State, open: PendingIntent) {
        val rv = PlayerWidget.views(ctx)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_spox)
            .setContentTitle(st.title)
            .setContentText(st.artist)
            .setCustomContentView(rv)
            .setCustomBigContentView(rv)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setContentIntent(open)
            // stays put at the top like a player (not swiped away by accident)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        runCatching { nm.notify(ID, n) }
    }

    fun update(ctx: Context) {
        if (!enabled(ctx)) return
        val st = Player.state
        if (!st.hasTrack) {
            clear(ctx); return
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "SpoX player", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            })
        }
        val key = "${style(ctx)}|${st.title}|${st.artist}|${st.playing}|${st.positionMs / 1000}|${st.art?.generationId}|${st.shuffle}|${st.repeat}"
        if (key == lastKey) return
        lastKey = key
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        if (style(ctx) == "pixel") {
            // no media session here: otherwise Android shows its own (non-pixel) player for SpoX too
            session?.let { it.isActive = false; it.release() }
            session = null
            pixelCard(ctx, nm, st, open)
            return
        }
        val s = session(ctx)
        val art = pixelArt(st.art, ctx)
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, st.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, st.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, st.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, st.durationMs)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art)
                .build()
        )
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO)
                .setState(if (st.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, st.position(), if (st.playing) 1f else 0f)
                // extra buttons on the phone's player (Android shows up to two)
                .addCustomAction(PlaybackState.CustomAction.Builder("shuffle", if (st.shuffle) "Shuffle on" else "Shuffle off", R.drawable.ic_shuffle).build())
                .addCustomAction(PlaybackState.CustomAction.Builder("repeat", if (st.repeat) "Repeat on" else "Repeat off", R.drawable.ic_repeat).build())
                .build()
        )
        fun action(name: String, icon: Int, label: String) = Notification.Action.Builder(
            Icon.createWithResource(ctx, icon), label,
            PendingIntent.getBroadcast(ctx, name.hashCode(), Intent(ctx, NotifyActions::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)
        ).build()

        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_spox)
            .setLargeIcon(art)
            .setContentTitle(st.title)
            .setContentText(st.artist)
            .setSubText("SpoX")
            .setContentIntent(open)
            .setOngoing(st.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(Skin.get(ctx).color("title"))
            .addAction(action("prev", android.R.drawable.ic_media_previous, "Previous"))
            .addAction(if (st.playing) action("toggle", android.R.drawable.ic_media_pause, "Pause") else action("toggle", android.R.drawable.ic_media_play, "Play"))
            .addAction(action("next", android.R.drawable.ic_media_next, "Next"))
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
        runCatching { nm.notify(ID, n) }
    }
}

/** Buttons in SpoX's notification. */
class NotifyActions : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.action) {
            "prev" -> Player.prev()
            "next" -> Player.next()
            "toggle" -> Player.playPause()
        }
    }
}
