package io.github.oatomh.spox

import android.content.Context
import io.github.oatomh.spox.engine.Engine
import kotlin.concurrent.thread

/** Heart and + / - for the notification and lock screen player (the song SpoX Engine plays). */
object Quick {
    @Volatile private var likedFor = ""
    @Volatile private var liked: Boolean? = null
    @Volatile private var likedAt = 0L

    private fun trackUri(ctx: Context) = Engine.get(ctx).now.track?.uri

    /** Liked? Cached; asks Spotify when the song changes or every 30 s, then redraws the player. */
    fun liked(ctx: Context): Boolean? {
        val uri = trackUri(ctx) ?: return null
        val now = System.currentTimeMillis()
        if (uri != likedFor || now - likedAt > 30_000) {
            if (uri != likedFor) liked = null
            likedFor = uri; likedAt = now
            thread {
                val r = Web.isLiked(uri.substringAfterLast(':'))
                if (likedFor == uri && r != null && r != liked) { liked = r; SpoxNotification.refresh(ctx) }
            }
        }
        return liked
    }

    fun toggleLike(ctx: Context) {
        val uri = trackUri(ctx) ?: return
        thread {
            val cur = (if (likedFor == uri) liked else null) ?: Web.isLiked(uri.substringAfterLast(':')) ?: false
            liked = !cur; likedFor = uri; likedAt = System.currentTimeMillis()
            SpoxNotification.refresh(ctx)
            val ok = Web.setLiked(uri.substringAfterLast(':'), !cur)
            if (!ok) { liked = cur; SpoxNotification.refresh(ctx) }
            PlayerView.say(ctx, if (!ok) "Couldn't change Liked Songs" else if (!cur) "Saved to Liked Songs" else "Removed from Liked Songs")
        }
    }

    /** The playlist + / - works on: SpoX's list, else the playlist playing. */
    fun listUri(ctx: Context): String = Smart.listUri.takeIf { Smart.on && it.startsWith("spotify:playlist:") } ?: Engine.get(ctx).now.contextUri

    /** In the playing playlist? null while loading or not a playlist of yours. */
    fun inList(ctx: Context): Boolean? {
        val uri = trackUri(ctx) ?: return null
        return Web.inPlaylist(listUri(ctx), uri)
    }

    fun togglePlaylist(ctx: Context) {
        val uri = trackUri(ctx) ?: return
        val list = listUri(ctx)
        val name = (if (Smart.on) Smart.listName else Engine.get(ctx).now.contextName).ifEmpty { "the playlist" }
        thread {
            var inPl = Web.inPlaylist(list, uri)
            var tries = 0
            while (inPl == null && tries++ < 15 && list.startsWith("spotify:playlist:")) { Thread.sleep(200); inPl = Web.inPlaylist(list, uri) }
            if (inPl == null) { PlayerView.say(ctx, "Play one of your playlists first"); return@thread }
            Web.markInPlaylist(list, uri, !inPl)
            SpoxNotification.refresh(ctx)
            val ok = if (inPl) Web.removeFrom(list, uri) else Web.addTo(list, uri)
            if (!ok) { Web.markInPlaylist(list, uri, inPl); SpoxNotification.refresh(ctx) }
            PlayerView.say(ctx, if (!ok) "Couldn't change $name (is it yours?)" else if (inPl) "Removed from $name" else "Added to $name")
        }
    }
}
