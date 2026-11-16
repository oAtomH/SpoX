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

    class Item(val uri: String, val title: String, val sub: String, val kind: String, val id: String = "")

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

    private fun track(t: JSONObject?): Item? {
        if (t == null || !t.has("uri")) return null
        return Item(t.optString("uri"), t.optString("name"), artists(t), "track", t.optString("id"))
    }

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
                (track(e.optJSONObject("item") ?: e.optJSONObject("track")) ?: track(e))?.let { out += it }
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

    fun isLiked(trackId: String): Boolean? = get("/me/tracks/contains?ids=$trackId")?.optJSONArray("list")?.optBoolean(0)
    fun setLiked(trackId: String, on: Boolean) = send(if (on) "PUT" else "DELETE", "/me/tracks?ids=$trackId")

    /** Play a song inside its playlist/album on SpoX Phone (so "next" continues in the list). */
    fun playIn(context: String?, track: String?, uris: List<String> = emptyList()): Boolean {
        val dev = get("/me/player/devices")?.optJSONArray("devices")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { it.optString("name") == "SpoX Phone" }?.optString("id")
        } ?: return false
        val body = JSONObject()
        if (context != null && context != "liked") {
            body.put("context_uri", context)
            if (track != null) body.put("offset", JSONObject().put("uri", track))
        } else {
            // Liked Songs has no playable link: play the list itself from that song on
            val i = uris.indexOf(track).coerceAtLeast(0)
            body.put("uris", JSONArray(uris.drop(i).take(100)))
        }
        return send("PUT", "/me/player/play?device_id=${enc(dev)}", body)
    }
}
