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
    const val CHANNEL = "playback"
    const val ID = 77
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

    /** Why the lock screen player is (not) there, for Settings. */
    fun lockStatus(ctx: Context): String {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return "blocked: tap to allow"
        val ch = nm.getNotificationChannel(CHANNEL)
        if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) return "blocked: tap to allow"
        if (ch != null && ch.lockscreenVisibility == Notification.VISIBILITY_SECRET) return "blocked: tap to allow"
        return when (style(ctx)) {
            "pixel" -> "pixel card has none: tap to fix"
            "off" -> "off: tap to turn on"
            else -> if (nm.activeNotifications.any { it.id == ID && it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }) "on" else "not posted: tap to retry"
        }
    }

    /** The SpoX player's session (also what Android Auto connects to). */
    fun token(ctx: Context): MediaSession.Token = session(ctx).sessionToken

    private fun session(ctx: Context): MediaSession = session?.also { if (!it.isActive) it.isActive = true } ?: MediaSession(ctx.applicationContext, "SpoX").also { s ->
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
            // Android Auto / Assistant: play something from SpoX's library
            override fun onPlayFromMediaId(id: String, extras: android.os.Bundle?) { SpoxBrowser.play(id) }
            override fun onPlayFromSearch(query: String?, extras: android.os.Bundle?) {
                val q = query.orEmpty().trim()
                kotlin.concurrent.thread {
                    if (q.isEmpty()) { Player.play(); return@thread }
                    Web.search(q).firstOrNull()?.let { Player.playUri(it.uri) }
                }
            }
        })
        s.setSessionActivity(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        s.isActive = true
        session = s
    }

    /** Everything that decides whether the lock screen shows SpoX's player, for "Lock screen check". */
    fun lockReport(ctx: Context): String {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ch = nm.getNotificationChannel(CHANNEL)
        val posted = nm.activeNotifications.firstOrNull { it.id == ID }
        val sb = StringBuilder()
        sb.append("SpoX ").append(runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()).append(" · Android ").append(android.os.Build.VERSION.RELEASE)
            .append(" (").append(android.os.Build.VERSION.SDK_INT).append(") ").append(android.os.Build.MODEL).append('\n')
        sb.append("style: ").append(style(ctx)).append(" · notifications on: ").append(nm.areNotificationsEnabled()).append('\n')
        sb.append("channel: ").append(if (ch == null) "none" else "importance ${ch.importance}, lock screen ${ch.lockscreenVisibility}").append('\n')
        sb.append("posted: ").append(posted != null)
        if (posted != null) {
            val n = posted.notification
            sb.append(" · media session in it: ").append(n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION))
                .append(" · template: ").append(n.extras.getString(Notification.EXTRA_TEMPLATE))
                .append(" · visibility: ").append(n.visibility)
                .append(" · foreground: ").append(n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        }
        sb.append('\n')
        val s = session
        sb.append("session: ").append(if (s == null) "none" else "active ${s.isActive}, state ${s.controller.playbackState?.state}, title ${s.controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)}").append('\n')
        val msm = ctx.getSystemService(android.media.session.MediaSessionManager::class.java)
        val list = runCatching { msm.getActiveSessions(android.content.ComponentName(ctx, MediaListener::class.java)) }.getOrNull()
        sb.append("Android's active players: ").append(list?.joinToString { "${it.packageName}(${it.playbackState?.state})" } ?: "needs Notification access to see").append('\n')
        val cr = ctx.contentResolver
        for (k in listOf("lock_screen_show_notifications", "lock_screen_allow_private_notifications", "media_controls_lock_screen", "lockscreen_show_media", "now_bar_enabled"))
            sb.append(k).append('=').append(runCatching { android.provider.Settings.Secure.getString(cr, k) }.getOrNull()).append(' ')
        return sb.toString()
    }

    private fun clear(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID)
        // keep the session itself (Android Auto stays connected to it), just switch it off
        session?.isActive = false
        lastKey = ""
    }

    /** Album art as chunky pixels (nearest-neighbour upscale). */
    private var artFor: Bitmap? = null
    private var artDone: Bitmap? = null

    /** Pixel-art cover on the lock screen / phone's player (Settings > Pixel cover). */
    /** What the lock screen / phone's player shows as the picture: "cover", "pixel" or "card". */
    fun lockArt(ctx: Context) = ctx.getSharedPreferences("spox", 0).getString("lock_art", "pixel") ?: "pixel"

    fun cycleLockArt(ctx: Context) {
        val next = when (lockArt(ctx)) { "pixel" -> "card"; "card" -> "cover"; else -> "pixel" }
        ctx.getSharedPreferences("spox", 0).edit().putString("lock_art", next).apply()
        artFor = null; lastKey = ""
        update(ctx)
    }

    private var artTitle = ""
    private fun pixelArt(src: Bitmap?, ctx: Context): Bitmap? {
        val mode = lockArt(ctx)
        if (mode == "cover") return src
        val key = if (mode == "card") "${Skin.packName(ctx)}|${Player.state.title}|${Player.state.artist}" else ""
        if (src != null && src === artFor && key == artTitle) return artDone
        artTitle = key
        return (if (mode == "card") card(src, ctx) else pixelArtNew(src, ctx)).also { artFor = src; artDone = it }
    }

    /** "SpoX card": the picture Android puts behind the player is drawn by SpoX: the skin's
     *  background, the pixel cover and the song in the pixel font. Android takes its colours
     *  from this picture, so the whole player follows your colour pack. */
    private fun card(src: Bitmap?, ctx: Context): Bitmap {
        val skin = Skin.get(ctx)
        val st = Player.state
        val k = 8f
        val size = 64
        val out = Bitmap.createBitmap((size * k).toInt(), (size * k).toInt(), Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(skin.color("anim_bg", android.graphics.Color.rgb(10, 10, 11)))
        val n = skin.json.optInt("art_pixels", 24)
        val art = src?.let { Bitmap.createScaledBitmap(it, n, n, true) } ?: skin.sprite("art_empty")
        art?.let { cv.drawBitmap(it, null, android.graphics.RectF(12 * k, 8 * k, 52 * k, 48 * k), Paint().apply { isFilterBitmap = false }) }
        skin.nine(cv, "tb_frame", 11f, 7f, 42f, 42f, k, 2)
        val half = k / 2
        val title = skin.fit(st.title, (size - 4) * 2)
        val artist = skin.fit(st.artist, (size - 4) * 2)
        skin.text(cv, title, (size * 2 - skin.textWidth(title)) / 2f, 104f, half, skin.color("title"))
        skin.text(cv, artist, (size * 2 - skin.textWidth(artist)) / 2f, 114f, half, skin.color("info"))
        return out
    }

    private fun pixelArtNew(src: Bitmap?, ctx: Context): Bitmap? {
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

    fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            // a fresh channel (0.10.1): the old "player" one may carry lock screen settings from
            // before; this one is made public on the lock screen from the start
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "SpoX player", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
            runCatching { nm.deleteNotificationChannel("player") }
        }
    }

    /** What SpoX Engine shows while it starts (and when the player is switched off). */
    fun placeholder(ctx: Context, text: String): Notification {
        channel(ctx)
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_spox)
            .setContentTitle("SpoX")
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    /** Post the player again even if nothing changed (Android sometimes drops a media player
     *  that hasn't been updated for a while, especially on the lock screen). */
    fun refresh(ctx: Context) {
        lastKey = ""
        session?.let { if (!it.isActive) it.isActive = true }
        update(ctx)
    }

    fun update(ctx: Context) {
        val st = Player.state
        if (!enabled(ctx)) {
            // the engine still needs its notification to keep running
            if (Player.fromEngine) runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, placeholder(ctx, st.title.ifEmpty { "Engine on" })) }
            return
        }
        if (!st.hasTrack) {
            // nothing playing anywhere (Spotify closed, SpoX Engine off): no player left behind
            val live = io.github.oatomh.spox.engine.Engine.get(ctx).isLive
            if (!live) clear(ctx)
            else if (lastKey.isNotEmpty()) {
                lastKey = ""
                session?.isActive = false
                runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, placeholder(ctx, "Ready · pick a song")) }
            }
            return
        }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        channel(ctx)
        // the song position only matters when it jumps (a seek): Android moves the bar by itself.
        // So rebuild on "when did this song start" changing, not on every position report.
        val at = if (st.playing) (st.positionAt - st.positionMs) / 2000 else st.positionMs / 1000
        val key = "${style(ctx)}|${st.title}|${st.artist}|${st.playing}|$at|${st.art?.generationId}|${st.shuffle}|${st.repeat}"
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
                .addCustomAction(PlaybackState.CustomAction.Builder("shuffle", if (st.shuffle) "Shuffle on" else "Shuffle off", if (st.shuffle) R.drawable.ic_px_shuffle_on else R.drawable.ic_px_shuffle).build())
                .addCustomAction(PlaybackState.CustomAction.Builder("repeat", if (st.repeat) "Repeat on" else "Repeat off", if (st.repeat) R.drawable.ic_px_repeat_on else R.drawable.ic_px_repeat).build())
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
            .setSubText(if (Player.fromEngine) "SpoX" else "SpoX · Spotify")
            .setContentIntent(open)
            // SpoX Engine keeps its player put (it's the engine's notification); the Spotify-app
            // mirror can be swiped away while paused
            .setOngoing(st.playing || io.github.oatomh.spox.engine.Engine.get(ctx).isLive)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(Skin.get(ctx).color("menu_bg", Skin.get(ctx).color("anim_bg")))
            // a coloured card in the skin's colours where the phone allows it (media players only)
            .setColorized(true)
            .addAction(action("prev", R.drawable.ic_px_prev, "Previous"))
            .addAction(if (st.playing) action("toggle", R.drawable.ic_px_pause, "Pause") else action("toggle", R.drawable.ic_px_play, "Play"))
            .addAction(action("next", R.drawable.ic_px_next, "Next"))
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
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
