package io.github.oatomh.spox

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Your Spotify account (Spotify's Web API), the same way SpoX on Windows does it: your own
 * developer app's Client ID, signed in once in the browser. Gives the library (playlists, Liked
 * Songs), search and liking. Playing itself always goes through SpoX Engine.
 */
object Web {
    const val REDIRECT = "http://127.0.0.1:43821/callback"
    private const val PORT = 43821
    private const val API = "https://api.spotify.com/v1"
    private const val SCOPES = "playlist-read-private playlist-read-collaborative playlist-modify-private playlist-modify-public " +
        "user-library-read user-library-modify user-read-private user-read-playback-state user-modify-playback-state"

    /** A song, list or artist. For songs: sub = artists, plus album, length and when it was added. */
    class Item(val uri: String, val title: String, val sub: String, val kind: String, val id: String = "",
               val album: String = "", val durationMs: Long = 0, val added: Long = 0, val order: Int = 0)

    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences("web", 0)

    @Volatile var status = ""
    val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private fun changed() = listeners.forEach { it() }

    fun init(c: Context) { if (!::app.isInitialized) app = c.applicationContext }

    fun clientId() = prefs.getString("client_id", "").orEmpty()
    fun setClientId(id: String) = prefs.edit().putString("client_id", id.trim()).apply()
    val signedIn get() = ::app.isInitialized && prefs.getString("refresh", "").orEmpty().isNotEmpty()
    fun userName() = prefs.getString("name", "").orEmpty()

    fun signOut() { prefs.edit().remove("refresh").remove("access").remove("name").remove("uid").apply(); changed() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun b64(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)

    // ---- sign in (PKCE): browser -> Spotify -> back to a tiny server inside SpoX -------------------

    fun signIn(ctx: Context) {
        val id = clientId()
        if (id.isEmpty()) { status = "add your Client ID first"; changed(); return }
        val verifier = b64(ByteArray(48).also { SecureRandom().nextBytes(it) })
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = b64(ByteArray(12).also { SecureRandom().nextBytes(it) })
        val server = runCatching { ServerSocket(PORT, 4, java.net.InetAddress.getByName("127.0.0.1")) }.getOrElse {
            status = "sign-in port busy, try again"; changed(); return
        }
        status = "signing in in the browser..."
        changed()
        thread(name = "spotify-sign-in") {
            server.soTimeout = 5 * 60 * 1000
            try {
                server.accept().use { s ->
                    val line = s.getInputStream().bufferedReader().readLine().orEmpty()
                    val q = Uri.parse("http://x" + line.substringAfter("GET ").substringBefore(" HTTP"))
                    val code = q.getQueryParameter("code")
                    val ok = code != null && q.getQueryParameter("state") == state
                    // back to SpoX by itself
                    val page = "<html><head><meta name=viewport content='width=device-width'><meta http-equiv=refresh content='0;url=spox://signed-in'></head>" +
                        "<body style='background:#0a0a0b;color:#5be38e;font-family:monospace;padding:24px'>" +
                        (if (ok) "Signed in. <a style='color:#5be38e' href='spox://signed-in'>Back to SpoX</a>" else "Sign-in failed: ${q.getQueryParameter("error") ?: "?"}") + "</body></html>"
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n$page".toByteArray())
                    if (ok) exchange(code!!, verifier) else status = "sign-in failed"
                }
            } catch (e: Throwable) {
                status = "sign-in stopped (${e.javaClass.simpleName})"
            } finally {
                runCatching { server.close() }
                changed()
            }
        }
        val url = "https://accounts.spotify.com/authorize?client_id=${enc(id)}&response_type=code&redirect_uri=${enc(REDIRECT)}" +
            "&code_challenge_method=S256&code_challenge=$challenge&scope=${enc(SCOPES)}&state=$state"
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun form(url: String, fields: Map<String, String>): JSONObject? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.write(fields.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }.toByteArray())
        if (c.responseCode !in 200..299) null else JSONObject(c.inputStream.bufferedReader().readText())
    }.getOrNull()

    private fun exchange(code: String, verifier: String) {
        val v = form("https://accounts.spotify.com/api/token", mapOf("grant_type" to "authorization_code", "code" to code,
            "redirect_uri" to REDIRECT, "client_id" to clientId(), "code_verifier" to verifier))
        if (v == null) { status = "sign-in failed (check the Client ID and that $REDIRECT is in your Spotify app)"; return }
        store(v)
        get("/me")?.let { prefs.edit().putString("name", it.optString("display_name", it.optString("id"))).putString("uid", it.optString("id")).apply() }
        status = ""
    }

    private fun store(v: JSONObject) {
        val e = prefs.edit().putString("access", v.optString("access_token"))
            .putLong("expires", System.currentTimeMillis() + v.optLong("expires_in", 3600) * 1000 - 60_000)
        v.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { e.putString("refresh", it) }
        e.apply()
    }

    @Synchronized
    private fun token(): String? {
        if (!signedIn) return null
        if (System.currentTimeMillis() < prefs.getLong("expires", 0)) return prefs.getString("access", null)
        val v = form("https://accounts.spotify.com/api/token", mapOf("grant_type" to "refresh_token",
            "refresh_token" to prefs.getString("refresh", "").orEmpty(), "client_id" to clientId())) ?: return null
        store(v)
        return v.optString("access_token")
    }

    // ---- requests (call these off the main thread) ------------------------------------------

    fun get(path: String): JSONObject? = runCatching {
        val t = token() ?: return null
        val c = URL(if (path.startsWith("http")) path else API + path).openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer $t")
        c.connectTimeout = 10000; c.readTimeout = 15000
        if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().readText().let { if (it.isBlank()) JSONObject() else if (it.trim().startsWith("[")) JSONObject().put("list", JSONArray(it)) else JSONObject(it) }
    }.getOrNull()

    fun send(method: String, path: String, body: JSONObject? = null): Boolean = runCatching {
        val t = token() ?: return false
        val c = URL(API + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.setRequestProperty("Authorization", "Bearer $t")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.write(body.toString().toByteArray())
        } else if (method == "PUT" || method == "POST") { c.doOutput = true; c.setFixedLengthStreamingMode(0) }
        c.responseCode in 200..299
    }.getOrDefault(false)

    private fun artists(t: JSONObject) = t.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString(", ") { a.optJSONObject(it)?.optString("name").orEmpty() } }.orEmpty()

    fun track(t: JSONObject?, added: Long = 0, order: Int = 0): Item? {
        if (t == null || !t.has("uri")) return null
        return Item(t.optString("uri"), t.optString("name"), artists(t), "track", t.optString("id"),
            t.optJSONObject("album")?.optString("name").orEmpty(), t.optLong("duration_ms"), added, order)
    }

    /** "2025-03-01T12:00:00Z" -> epoch ms (0 if missing). */
    private fun time(s: String?): Long = runCatching {
        if (s.isNullOrEmpty() || s == "null") 0L else java.time.Instant.parse(s).toEpochMilli()
    }.getOrDefault(0L)

    fun playlists(): List<Item> {
        val out = mutableListOf(Item("liked", "Liked Songs", "your saved songs", "liked"))
        var next: String? = "/me/playlists?limit=50"
        while (next != null) {
            val v = get(next) ?: break
            val items = v.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val p = items.optJSONObject(i) ?: continue
                val total = p.optJSONObject("items")?.optInt("total") ?: p.optJSONObject("tracks")?.optInt("total") ?: 0
                out += Item(p.optString("uri"), p.optString("name"), "${p.optJSONObject("owner")?.optString("display_name").orEmpty()} · $total songs", "playlist", p.optString("id"))
            }
            next = v.optString("next").takeIf { it.isNotEmpty() && it != "null" }
        }
        return out
    }

    fun tracks(list: Item): List<Item> {
        val out = mutableListOf<Item>()
        var next: String? = when (list.kind) {
            "liked" -> "/me/tracks?limit=50"
            "album" -> "/albums/${list.id}/tracks?limit=50"
            else -> "/playlists/${list.id}/items?limit=100"
        }
        while (next != null && out.size < 1000) {
            val v = get(next) ?: break
            val items = v.optJSONArray("items") ?: break
            for (i in 0 until items.length()) {
                val e = items.optJSONObject(i) ?: continue
                val at = time(e.optString("added_at"))
                (track(e.optJSONObject("item") ?: e.optJSONObject("track"), at, out.size) ?: track(e, at, out.size))?.let { out += it }
            }
            next = v.optString("next").takeIf { it.isNotEmpty() && it != "null" }
        }
        return out
    }

    fun search(q: String): List<Item> {
        val v = get("/search?type=track,album,playlist,artist&limit=10&q=${enc(q)}") ?: return emptyList()
        val out = mutableListOf<Item>()
        v.optJSONObject("tracks")?.optJSONArray("items")?.let { a -> for (i in 0 until a.length()) track(a.optJSONObject(i))?.let { out += it } }
        v.optJSONObject("artists")?.optJSONArray("items")?.let { a ->
            for (i in 0 until minOf(3, a.length())) a.optJSONObject(i)?.let { out += Item(it.optString("uri"), it.optString("name"), "Artist", "artist", it.optString("id")) }
        }
        v.optJSONObject("albums")?.optJSONArray("items")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { out += Item(it.optString("uri"), it.optString("name"), "Album · ${artists(it)}", "album", it.optString("id")) }
        }
        v.optJSONObject("playlists")?.optJSONArray("items")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { out += Item(it.optString("uri"), it.optString("name"), "Playlist · ${it.optJSONObject("owner")?.optString("display_name").orEmpty()}", "playlist", it.optString("id")) }
        }
        return out
    }

    // Spotify's February 2026 API: one "library" endpoint for every kind of thing, by uri
    // (the old /me/tracks/contains and PUT /me/tracks were removed, which broke liking)
    fun isLiked(trackId: String): Boolean? {
        val uri = enc("spotify:track:$trackId")
        return get("/me/library/contains?uris=$uri")?.optJSONArray("list")?.let { if (it.length() > 0) it.optBoolean(0) else null }
    }
    fun setLiked(trackId: String, on: Boolean): Boolean {
        val uri = "spotify:track:$trackId"
        val ok = send(if (on) "PUT" else "DELETE", "/me/library?uris=${enc(uri)}")
        return ok
    }

    // ---- songs you know, according to Spotify (Liked Songs, the playing playlist, recent plays) ----

    /** "Song (Remastered) - Live" + "Artist, Feat" -> "song|artist" (same as SpoX on Windows). */
    fun key(title: String, artists: String): String {
        var n = title.lowercase()
        for (cut in listOf(" (", " [", " - ")) { val i = n.indexOf(cut); if (i >= 0) n = n.substring(0, i) }
        return n.trim() + "|" + artists.split(", ").firstOrNull().orEmpty().lowercase().trim()
    }

    private val known = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    @Volatile private var knownLoaded = false
    @Volatile private var knownAt = 0L
    private val knownContexts = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** Do you know this song? null until Spotify's lists have loaded. */
    fun knows(title: String, artists: String): Boolean? = if (!knownLoaded) null else key(title, artists) in known

    fun noteKnown(title: String, artists: String) { known += key(title, artists) }

    /** Loads (or refreshes, every 30 min) Liked Songs + recent plays, and the playing playlist. */
    fun loadKnown(context: String?) {
        if (!signedIn) return
        val now = System.currentTimeMillis()
        if (now - knownAt > 30 * 60_000) {
            knownAt = now
            thread {
                tracks(Item("liked", "", "", "liked")).forEach { known += key(it.title, it.sub) }
                get("/me/player/recently-played?limit=50")?.optJSONArray("items")?.let { a ->
                    for (i in 0 until a.length()) track(a.optJSONObject(i)?.optJSONObject("track"))?.let { known += key(it.title, it.sub) }
                }
                knownLoaded = true
                changed()
            }
        }
        if (context != null && context.startsWith("spotify:playlist:") && knownContexts.add(context)) {
            thread { tracks(Item(context, "", "", "playlist", context.substringAfterLast(':'))).forEach { known += key(it.title, it.sub) }; changed() }
        }
    }

    // ---- the songs of the playing playlist (for + / -) -------------------------------------------

    private val playlistCache = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    private val playlistAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Is the song in this playlist? null while loading / unknown. Re-read every 2 minutes, so
     *  songs added or removed on another device show the right button here too. */
    fun inPlaylist(context: String, uri: String): Boolean? {
        if (!context.startsWith("spotify:playlist:")) return null
        val now = System.currentTimeMillis()
        val set = playlistCache[context]
        if (set == null || now - (playlistAt[context] ?: 0) > 120_000) {
            if (set == null) playlistCache[context] = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
            playlistAt[context] = now
            thread {
                val uris = tracks(Item(context, "", "", "playlist", context.substringAfterLast(':'))).map { it.uri }
                if (uris.isNotEmpty() || context !in playlistLoaded) {
                    playlistCache[context]?.let { s -> s.retainAll(uris.toSet()); s.addAll(uris) }
                    playlistLoaded += context
                    changed()
                }
            }
        }
        return if (context in playlistLoaded) uri in (playlistCache[context] ?: return null) else null
    }
    /** Optimistic change (the button flips at once; [addTo]/[removeFrom] confirm it). */
    fun markInPlaylist(context: String, uri: String, inside: Boolean) {
        playlistCache[context]?.let { if (inside) it.add(uri) else it.remove(uri) }
        changed()
    }
    private val playlistLoaded = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun addTo(context: String, uri: String): Boolean {
        val id = context.substringAfterLast(':')
        val ok = send("POST", "/playlists/$id/items", JSONObject().put("uris", JSONArray().put(uri))) ||
            send("POST", "/playlists/$id/tracks", JSONObject().put("uris", JSONArray().put(uri)))
        if (ok) playlistCache[context]?.add(uri)
        return ok
    }

    fun removeFrom(context: String, uri: String): Boolean {
        val id = context.substringAfterLast(':')
        val one = JSONArray().put(JSONObject().put("uri", uri))
        val ok = send("DELETE", "/playlists/$id/items", JSONObject().put("items", one)) ||
            send("DELETE", "/playlists/$id/items", JSONObject().put("tracks", one)) ||
            send("DELETE", "/playlists/$id/tracks", JSONObject().put("tracks", one))
        if (ok) playlistCache[context]?.remove(uri)
        return ok
    }

    // ---- devices: move the music between this phone, your PC, a speaker... (Spotify Connect) ----

    class Device(val id: String, val name: String, val type: String, val active: Boolean)

    fun devices(): List<Device> = get("/me/player/devices")?.optJSONArray("devices")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Device(it.optString("id"), it.optString("name"), it.optString("type"), it.optBoolean("is_active")) }
    } ?: emptyList()

    /** Moves what's playing to another device (any Spotify app, SpoX or speaker), keeps playing. */
    fun transfer(deviceId: String): Boolean = send("PUT", "/me/player", JSONObject().put("device_ids", JSONArray().put(deviceId)).put("play", true))

    @Volatile private var phoneId = ""
    private fun phoneDevice(): String? {
        phoneId.takeIf { it.isNotEmpty() }?.let { return it }
        return get("/me/player/devices")?.optJSONArray("devices")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("name") == "SpoX Phone" }?.optString("id")
        }?.also { phoneId = it }
    }

    /** Plays exactly these songs, in this order, on SpoX Phone (SpoX's own shuffle and sorting).
     *  [positionMs] starts the first one part-way (to carry on without a jump). */
    fun playUris(uris: List<String>, positionMs: Long = 0): Boolean {
        if (uris.isEmpty()) return false
        val body = JSONObject().put("uris", JSONArray(uris.take(100)))
        if (positionMs > 0) body.put("position_ms", positionMs)
        for (attempt in 0..1) {
            val dev = phoneDevice() ?: return false
            if (send("PUT", "/me/player/play?device_id=${enc(dev)}", body)) return true
            phoneId = "" // the id changes when the engine restarts: look it up again
        }
        return false
    }

    /** Play a song inside its playlist/album on SpoX Phone (so "next" continues in the list). */
    fun playIn(context: String?, track: String?, uris: List<String> = emptyList()): Boolean {
        val dev = phoneDevice() ?: return false
        val body = JSONObject()
        if (context != null && context != "liked") {
            body.put("context_uri", context)
            if (track != null) body.put("offset", JSONObject().put("uri", track))
        } else {
            // Liked Songs has no playable link: play the list itself from that song on
            val i = uris.indexOf(track).coerceAtLeast(0)
            body.put("uris", JSONArray(uris.drop(i).take(100)))
        }
        val ok = send("PUT", "/me/player/play?device_id=${enc(dev)}", body)
        if (!ok) phoneId = ""
        return ok
    }

    /** Spotify Connect name of the device playing right now (for "Playing on ..."). */
    fun activeDevice(): Device? = devices().firstOrNull { it.active }
}
