package io.github.oatomh.spox

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * What you listened to: a small log on the phone (for the stats page) and, if you connect it,
 * Last.fm scrobbling ("now playing" + a scrobble once you've heard half the song or 4 minutes).
 */
object Stats {
    class Play(val at: Long, val uri: String, val title: String, val artist: String, val ms: Long)

    private lateinit var app: Context
    private val file get() = File(app.filesDir, "plays.jsonl")
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun init(c: Context) { if (!::app.isInitialized) app = c.applicationContext }

    // ---- following the song that plays ---------------------------------------------------------

    private var curUri = ""
    private var curTitle = ""
    private var curArtist = ""
    private var curAlbum = ""
    private var curDur = 0L
    private var heard = 0L
    private var startedAt = 0L
    private var lastTick = 0L
    private var scrobbled = false

    /** Called with what plays (often): counts the time actually heard. */
    private var lastPlaying = false

    @Synchronized
    fun tick(uri: String, title: String, artist: String, album: String, durationMs: Long, playing: Boolean) {
        if (!::app.isInitialized || uri.isEmpty()) return
        val now = System.currentTimeMillis()
        // time heard since the last call (updates can be minutes apart while nothing changes)
        if (lastPlaying && lastTick > 0) heard = (heard + (now - lastTick)).coerceAtMost(curDur.coerceAtLeast(30_000))
        lastTick = now
        lastPlaying = playing
        if (uri != curUri) {
            check()
            finish()
            curUri = uri; curTitle = title; curArtist = artist; curAlbum = album; curDur = durationMs
            heard = 0; startedAt = now; scrobbled = false
            if (playing) nowPlaying(title, artist, album, durationMs)
            return
        }
        check()
    }

    /** Last.fm's rule: at least 30 s long, and heard half of it or 4 minutes. */
    private fun check() {
        if (!scrobbled && curUri.isNotEmpty() && curDur > 30_000 && (heard >= curDur / 2 || heard >= 240_000)) {
            scrobbled = true
            scrobble(curTitle, curArtist, curAlbum, curDur, startedAt / 1000)
        }
    }

    /** The song ended or changed: into the log if you heard at least 30 s of it. */
    private fun finish() {
        if (curUri.isEmpty() || heard < 30_000) return
        val line = JSONObject().put("at", startedAt).put("uri", curUri).put("t", curTitle).put("a", curArtist).put("ms", heard).toString()
        io.execute { runCatching { file.appendText(line + "\n") } }
    }

    fun all(): List<Play> = runCatching {
        file.readLines().mapNotNull { l ->
            runCatching { JSONObject(l).let { Play(it.optLong("at"), it.optString("uri"), it.optString("t"), it.optString("a"), it.optLong("ms")) } }.getOrNull()
        }
    }.getOrDefault(emptyList())

    // ---- the stats page ------------------------------------------------------------------------

    /** Rows for the stats page: (title, detail, uri or ""). Headings start with "#". */
    fun rows(days: Int): List<Triple<String, String, String>> {
        val from = if (days <= 0) 0L else System.currentTimeMillis() - days * 86_400_000L
        val plays = all().filter { it.at >= from }
        val out = ArrayList<Triple<String, String, String>>()
        val total = plays.sumOf { it.ms }
        out += Triple("#Listening", "", "")
        out += Triple(time(total), "${plays.size} songs played · ${plays.map { it.artist.split(", ").first() }.distinct().size} artists", "")
        if (plays.isEmpty()) return out
        out += Triple("#Top artists", "", "")
        plays.groupBy { it.artist.split(", ").first() }.entries.sortedByDescending { e -> e.value.sumOf { it.ms } }.take(10).forEachIndexed { i, e ->
            out += Triple("${i + 1}. ${e.key}", "${e.value.size} plays · ${time(e.value.sumOf { it.ms })}", "")
        }
        out += Triple("#Top songs", "", "")
        plays.groupBy { it.uri }.entries.sortedByDescending { it.value.size }.take(15).forEachIndexed { i, e ->
            val p = e.value.first()
            out += Triple("${i + 1}. ${p.title}", "${p.artist} · ${e.value.size} plays", e.key)
        }
        // hour of day you listen most
        val hours = IntArray(24)
        val cal = java.util.Calendar.getInstance()
        plays.forEach { cal.timeInMillis = it.at; hours[cal.get(java.util.Calendar.HOUR_OF_DAY)]++ }
        val peak = hours.indices.maxByOrNull { hours[it] } ?: 0
        out += Triple("#Habits", "", "")
        out += Triple("Most listening around ${peak}:00", "${hours[peak]} songs started in that hour", "")
        return out
    }

    private fun time(ms: Long): String {
        val m = ms / 60_000
        return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
    }

    // ---- Last.fm ---------------------------------------------------------------------------------

    private val prefs get() = app.getSharedPreferences("lastfm", 0)
    fun lastfmUser() = prefs.getString("user", "").orEmpty()
    fun lastfmKey() = prefs.getString("key", "").orEmpty()
    fun lastfmOn() = prefs.getString("sk", "").orEmpty().isNotEmpty()

    fun setKeys(key: String, secret: String) = prefs.edit().putString("key", key.trim()).putString("secret", secret.trim()).apply()
    fun disconnect() = prefs.edit().remove("sk").remove("user").apply()

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** Signed Last.fm call (POST). */
    private fun call(params: Map<String, String>): JSONObject? = runCatching {
        val secret = prefs.getString("secret", "").orEmpty()
        val p = params + ("api_key" to lastfmKey())
        val sig = md5(p.toSortedMap().entries.joinToString("") { it.key + it.value } + secret)
        val body = (p + ("api_sig" to sig) + ("format" to "json")).entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val c = java.net.URL("https://ws.audioscrobbler.com/2.0/").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8000; c.readTimeout = 10000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.write(body.toByteArray())
        JSONObject((if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().readText())
    }.getOrNull()

    /** Connect: Last.fm asks you to approve SpoX in the browser; SpoX waits for that. */
    fun connect(ctx: Context, done: (String) -> Unit) {
        if (lastfmKey().isEmpty() || prefs.getString("secret", "").isNullOrEmpty()) { done("Add your Last.fm API key and secret first"); return }
        thread {
            val token = call(mapOf("method" to "auth.getToken"))?.optString("token").orEmpty()
            if (token.isEmpty()) { done("Last.fm didn't answer (check the key and secret)"); return@thread }
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.last.fm/api/auth/?api_key=${lastfmKey()}&token=$token")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            // wait (up to 3 minutes) until you've said yes on Last.fm
            repeat(60) {
                Thread.sleep(3000)
                val s = call(mapOf("method" to "auth.getSession", "token" to token))?.optJSONObject("session")
                if (s != null) {
                    prefs.edit().putString("sk", s.optString("key")).putString("user", s.optString("name")).apply()
                    done("Last.fm connected: ${s.optString("name")}")
                    return@thread
                }
            }
            done("Last.fm: not approved in time, try again")
        }
    }

    private fun nowPlaying(title: String, artist: String, album: String, dur: Long) {
        if (!lastfmOn()) return
        val sk = prefs.getString("sk", "").orEmpty()
        io.execute {
            call(mapOf("method" to "track.updateNowPlaying", "artist" to artist.split(", ").first(), "track" to title,
                "album" to album, "duration" to (dur / 1000).toString(), "sk" to sk))
        }
    }

    private fun scrobble(title: String, artist: String, album: String, dur: Long, at: Long) {
        if (!lastfmOn()) return
        val sk = prefs.getString("sk", "").orEmpty()
        io.execute {
            call(mapOf("method" to "track.scrobble", "artist[0]" to artist.split(", ").first(), "track[0]" to title,
                "album[0]" to album, "duration[0]" to (dur / 1000).toString(), "timestamp[0]" to at.toString(), "sk" to sk))
        }
    }
}
