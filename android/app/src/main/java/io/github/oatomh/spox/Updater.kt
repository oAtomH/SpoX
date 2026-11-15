package io.github.oatomh.spox

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Updates from GitHub releases, like the desktop app: downloads SpoX.apk, Android asks to install. */
object Updater {
    private const val API = "https://api.github.com/repos/oAtomH/SpoX/releases/latest"
    @Volatile var status = ""
        private set

    private fun ver(s: String) = s.trimStart('v').split('.', '-').map { it.toIntOrNull() ?: 0 }

    private fun newer(a: List<Int>, b: List<Int>): Boolean {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "SpoX-Android")
        connectTimeout = 10000
        readTimeout = 30000
        instanceFollowRedirects = true
    }

    /** `manual` = the user tapped "Check for updates" (always says what happened). */
    fun check(ctx: Context, manual: Boolean, done: () -> Unit = {}) {
        val app = ctx.applicationContext
        thread(name = "update") {
            status = "Checking..."
            done()
            runCatching {
                val j = JSONObject(open(API).inputStream.bufferedReader().readText())
                val tag = j.getString("tag_name")
                val mine = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
                if (!newer(ver(tag), ver(mine))) {
                    status = "Up to date ($mine)"
                    done()
                    return@thread
                }
                val assets = j.getJSONArray("assets")
                val url = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.getString("name") == "SpoX.apk" }?.getString("browser_download_url")
                if (url == null) {
                    status = "$tag has no Android version yet"
                    done()
                    return@thread
                }
                status = "Downloading $tag..."
                done()
                val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                val f = File(dir, "SpoX.apk")
                open(url).inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                status = "Ready: $tag"
                done()
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", f)
                app.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                status = if (manual) "Couldn't check (${it.javaClass.simpleName})" else ""
                done()
            }
        }
    }
}
