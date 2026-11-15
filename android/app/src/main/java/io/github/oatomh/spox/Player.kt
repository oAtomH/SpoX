package io.github.oatomh.spox

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/**
 * What's playing and how to control it. Step 1 of SpoX for Android: a remote for the music app on
 * the phone (Spotify), through Android's own media controls, so it works with free accounts too.
 */
object Player {
    data class State(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val art: Bitmap? = null,
        val durationMs: Long = 0,
        val positionMs: Long = 0,
        /** elapsedRealtime when positionMs was true */
        val positionAt: Long = 0,
        val playing: Boolean = false,
        val shuffle: Boolean = false,
        val repeat: Boolean = false,
        val app: String = "",
    ) {
        val hasTrack get() = title.isNotEmpty()
        fun position(now: Long = SystemClock.elapsedRealtime()): Long =
            if (playing) (positionMs + (now - positionAt)).coerceIn(0, if (durationMs > 0) durationMs else Long.MAX_VALUE) else positionMs
    }

    @Volatile var state = State()
        private set
    private var controller: MediaController? = null
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    private lateinit var app: Context

    /** Music apps SpoX follows, best first. */
    private val preferred = listOf("com.spotify.music", "com.spotify.lite")

    fun init(ctx: Context) {
        if (!::app.isInitialized) app = ctx.applicationContext
    }

    // A button press shows at once; Spotify confirms a moment later. Until then (or 1.5 s),
    // SpoX keeps showing what you pressed instead of flickering back.
    private var wantPlaying: Boolean? = null
    private var wantShuffle: Boolean? = null
    private var wantRepeat: Boolean? = null
    private var wantUntil = 0L

    private fun expect(playing: Boolean? = null, shuffle: Boolean? = null, repeat: Boolean? = null) {
        val now = SystemClock.elapsedRealtime()
        if (playing != null) wantPlaying = playing
        if (shuffle != null) wantShuffle = shuffle
        if (repeat != null) wantRepeat = repeat
        wantUntil = now + 1500
        state = state.copy(
            playing = wantPlaying ?: state.playing,
            positionMs = state.position(now), positionAt = now,
            shuffle = wantShuffle ?: state.shuffle,
            repeat = wantRepeat ?: state.repeat,
        )
        changed()
    }

    /** Spotify's answer: take it, unless it's still the old state from before the press. */
    private fun settle(s: State): State {
        if (SystemClock.elapsedRealtime() > wantUntil) { wantPlaying = null; wantShuffle = null; wantRepeat = null; return s }
        if (wantPlaying == s.playing) wantPlaying = null
        if (wantShuffle == s.shuffle) wantShuffle = null
        if (wantRepeat == s.repeat) wantRepeat = null
        return s.copy(playing = wantPlaying ?: s.playing, shuffle = wantShuffle ?: s.shuffle, repeat = wantRepeat ?: s.repeat)
    }

    // asking Android for the shuffle/repeat state is slow-ish: one helper per music session
    private var compat: MediaControllerCompat? = null
    private var compatFor: Any? = null
    private fun compatOf(c: MediaController): MediaControllerCompat? {
        if (compatFor != c.sessionToken) {
            compat = runCatching { MediaControllerCompat(app, MediaSessionCompat.Token.fromToken(c.sessionToken)) }.getOrNull()
            compatFor = c.sessionToken
        }
        return compat
    }

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    private fun changed() = main.post { listeners.toList().forEach { it() } }

    fun hasAccess(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(ComponentName(ctx, MediaListener::class.java).flattenToString())
    }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(st: PlaybackState?) = refresh()
        override fun onSessionDestroyed() {
            controller = null
            pick()
        }
    }

    /** Find the music app's player (called when sessions come and go). */
    fun pick() {
        if (!::app.isInitialized) return
        val msm = app.getSystemService(MediaSessionManager::class.java) ?: return
        val list = runCatching { msm.getActiveSessions(ComponentName(app, MediaListener::class.java)) }.getOrNull() ?: return
        val best = list.sortedBy { c -> preferred.indexOf(c.packageName).let { if (it < 0) 99 else it } }
            .firstOrNull { it.packageName != app.packageName }
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
        }
        refresh()
    }

    private var lastArtKey = ""
    private var lastArt: Bitmap? = null

    private fun refresh() {
        val c = controller
        if (c == null) {
            state = State()
            changed()
            return
        }
        val m = c.metadata
        val p = c.playbackState
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val artKey = "$title|$artist"
        // Spotify often sends the cover a moment after the title: keep looking until it's there
        if (artKey != lastArtKey || lastArt == null) {
            lastArtKey = artKey
            lastArt = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (lastArt == null) loadArtUri(m, artKey)
        }
        val compat = compatOf(c)
        state = settle(State(
            title = title,
            artist = artist,
            album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
            art = lastArt,
            durationMs = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            positionMs = p?.position ?: 0,
            positionAt = p?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            playing = p?.state == PlaybackState.STATE_PLAYING || p?.state == PlaybackState.STATE_BUFFERING,
            shuffle = (compat?.shuffleMode ?: 0) == PlaybackStateCompat.SHUFFLE_MODE_ALL,
            repeat = (compat?.repeatMode ?: 0) != PlaybackStateCompat.REPEAT_MODE_NONE,
            app = c.packageName,
        ))
        changed()
    }

    /** Some apps only give a link to the cover: fetch it in the background. */
    private fun loadArtUri(m: MediaMetadata?, key: String) {
        val uri = m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: m?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI) ?: return
        kotlin.concurrent.thread(name = "art") {
            val bmp = runCatching {
                if (uri.startsWith("http")) java.net.URL(uri).openStream().use { android.graphics.BitmapFactory.decodeStream(it) }
                else app.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { android.graphics.BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return@thread
            main.post {
                if (key == lastArtKey && lastArt == null) {
                    lastArt = bmp
                    refresh()
                }
            }
        }
    }

    // ---- controls -------------------------------------------------------------------------

    private val tc get() = controller?.transportControls

    fun play() { if (controller != null) expect(playing = true); tc?.play() }
    fun pause() { if (controller != null) expect(playing = false); tc?.pause() }

    fun playPause() {
        if (state.playing) pause() else play()
    }
    fun next() = tc?.skipToNext()
    fun prev() = tc?.skipToPrevious()
    fun seek(ms: Long) = tc?.seekTo(ms)

    /** Shuffle / repeat: the music app's own buttons if it has them, else the standard command. */
    private fun custom(word: String): Boolean {
        val a = controller?.playbackState?.customActions?.firstOrNull { it.action.contains(word, ignoreCase = true) } ?: return false
        tc?.sendCustomAction(a, null)
        return true
    }

    fun toggleShuffle() {
        if (controller == null) return
        expect(shuffle = !state.shuffle)
        if (custom("shuffle")) return
        val c = controller ?: return
        runCatching {
            compatOf(c)!!.transportControls
                .setShuffleMode(if (state.shuffle) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
        }
    }

    fun toggleRepeat() {
        if (controller == null) return
        expect(repeat = !state.repeat)
        if (custom("repeat")) return
        val c = controller ?: return
        runCatching {
            compatOf(c)!!.transportControls
                .setRepeatMode(if (state.repeat) PlaybackStateCompat.REPEAT_MODE_ALL else PlaybackStateCompat.REPEAT_MODE_NONE)
        }
    }

    // ---- volume (the phone's media volume) ------------------------------------------------

    fun volume(ctx: Context): Float {
        val am = ctx.getSystemService(AudioManager::class.java)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }

    fun setVolume(ctx: Context, f: Float) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (f.coerceIn(0f, 1f) * max).toInt(), 0)
    }

    /** Up next, if the music app shares it ("Artist - Title"). */
    fun queue(): List<String> = controller?.queue?.mapNotNull { q ->
        val t = q.description.title?.toString() ?: return@mapNotNull null
        val a = q.description.subtitle?.toString()
        if (a.isNullOrEmpty()) t else "$a - $t"
    } ?: emptyList()

    /** Up next, as (id, title, artist) for the list. */
    fun queueItems(): List<Triple<Long, String, String>> = controller?.queue?.mapNotNull { q ->
        val t = q.description.title?.toString() ?: return@mapNotNull null
        Triple(q.queueId, t, q.description.subtitle?.toString().orEmpty())
    } ?: emptyList()

    fun playQueueItem(id: Long) = tc?.skipToQueueItem(id)

    /** Opens the music app (e.g. to pick a playlist there). */
    fun openApp(ctx: Context) {
        val pkg = state.app.ifEmpty { "com.spotify.music" }
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(it)
        }
    }
}
