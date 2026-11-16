package io.github.oatomh.spox

import android.media.MediaDescription
import android.media.browse.MediaBrowser.MediaItem
import android.os.Bundle
import android.service.media.MediaBrowserService
import kotlin.concurrent.thread

/**
 * SpoX in the car (Android Auto) and for anything else that browses music apps: your playlists
 * and Liked Songs, with the songs inside, played on SpoX Engine. (Apple CarPlay is iPhone-only.)
 */
class SpoxBrowser : MediaBrowserService() {
    companion object {
        /** Media ids: "list|<uri>" = a playlist / Liked Songs, "song|<context>|<song uri>". */
        fun play(id: String) {
            val p = id.split('|')
            thread {
                when (p.firstOrNull()) {
                    "list" -> { val uri = p.getOrNull(1) ?: return@thread; if (!Web.playIn(uri, null)) Player.playUri(uri) }
                    "song" -> {
                        val ctx = p.getOrNull(1).orEmpty()
                        val song = p.getOrNull(2) ?: return@thread
                        if (ctx.isEmpty() || !Web.playIn(ctx, song, listOf(song))) Player.playUri(song)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Player.init(this)
        Web.init(this)
        sessionToken = SpoxNotification.token(this)
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?) = BrowserRoot("root", null)

    private fun item(id: String, title: String, sub: String, browsable: Boolean) = MediaItem(
        MediaDescription.Builder().setMediaId(id).setTitle(title).setSubtitle(sub).build(),
        if (browsable) MediaItem.FLAG_BROWSABLE else MediaItem.FLAG_PLAYABLE)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        result.detach()
        thread {
            val out = mutableListOf<MediaItem>()
            if (!Web.signedIn) {
                out += item("none", "Sign in to Spotify in SpoX", "Settings > Account, on the phone", false)
            } else if (parentId == "root") {
                for (p in Web.playlists()) out += item("open|${p.uri}|${p.id}|${p.kind}", p.title, p.sub, true)
            } else if (parentId.startsWith("open|")) {
                val (_, uri, id, kind) = parentId.split('|').let { it + List(4) { "" } }
                out += item("list|$uri", "Play all", "", false)
                val list = Web.Item(uri, "", "", kind, id)
                for (t in Web.tracks(list).take(300)) out += item("song|$uri|${t.uri}", t.title, t.sub, false)
            }
            result.sendResult(out)
        }
    }
}
