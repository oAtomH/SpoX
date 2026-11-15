package io.github.oatomh.spox

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread
import kotlin.math.abs

/** Synced lyrics from LRCLIB (lrclib.net), like the desktop app: matched by song length. */
object Lyrics {
    data class Result(val synced: Boolean, val lines: List<Pair<Long, String>>)

    private val cache = HashMap<String, Result?>()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "SpoX-Android (https://github.com/oAtomH/SpoX)")
        c.connectTimeout = 8000
        c.readTimeout = 8000
        if (c.responseCode != 200) null else c.inputStream.bufferedReader().readText()
    }.getOrNull()

    fun parse(lrc: String): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        val tag = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")
        for (line in lrc.lines()) {
            val stamps = tag.findAll(line).map { it.groupValues[1].toLong() * 60_000 + (it.groupValues[2].toDouble() * 1000).toLong() }.toList()
            val text = line.replace(tag, "").trim()
            stamps.forEach { out += it to text }
        }
        return out.sortedBy { it.first }
    }

    private fun fromRecord(o: JSONObject): Result? {
        o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }?.let { s ->
            val l = parse(s)
            if (l.isNotEmpty()) return Result(true, l)
        }
        o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }?.let { s ->
            return Result(false, s.lines().map { 0L to it.trim() })
        }
        return null
    }

    /** Calls back on a background thread. */
    fun fetch(title: String, artist: String, album: String, durationMs: Long, done: (Result?) -> Unit) {
        val key = "$artist|$title"
        if (cache.containsKey(key)) {
            done(cache[key]); return
        }
        thread(name = "lyrics") {
            val a = artist.split(", ").first()
            var res = get("https://lrclib.net/api/get?artist_name=${enc(a)}&track_name=${enc(title)}&album_name=${enc(album)}&duration=${durationMs / 1000}")
                ?.let { runCatching { fromRecord(JSONObject(it)) }.getOrNull() }
            if (res == null) {
                // only versions within 2 s of this song's length, closest first
                val arr = get("https://lrclib.net/api/search?artist_name=${enc(a)}&track_name=${enc(title)}")?.let { runCatching { JSONArray(it) }.getOrNull() }
                val dur = durationMs / 1000.0
                val good = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }
                    .filter { abs(it.optDouble("duration", 999.0) - dur) <= 2.0 }
                    .sortedBy { abs(it.optDouble("duration", 999.0) - dur) }
                res = (good.firstOrNull { it.optString("syncedLyrics").let { s -> s.isNotBlank() && s != "null" } } ?: good.firstOrNull())?.let { fromRecord(it) }
            }
            cache[key] = res
            done(res)
        }
    }
}
