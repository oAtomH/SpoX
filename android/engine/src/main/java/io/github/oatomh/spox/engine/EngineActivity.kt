package io.github.oatomh.spox.engine

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * Step 1 of "SpoX without Spotify": find out what Spotify's Soloist (Linux ARM64 build) needs to run
 * on this phone. It downloads Soloist from Spotify's own server (never bundled), unpacks it, reads
 * what kind of program it is, tries to start it, and writes a report you can copy and send.
 */
class EngineActivity : Activity() {
    private lateinit var out: TextView
    private val report = StringBuilder()
    private val soloistUrl = "https://soloist-builds.spotifycdn.com/soloist_release_arm64.tar.gz"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 10, 11))
            setPadding(32, 96, 32, 32)
        }
        fun button(t: String, f: () -> Unit) = Button(this).apply {
            text = t
            setOnClickListener { f() }
            root.addView(this)
        }
        button("1. Run the test") { runTest() }
        button("2. Copy report") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SpoX Engine report", report.toString()))
            log("(report copied: paste it to Claude)")
        }
        out = TextView(this).apply {
            setTextColor(Color.rgb(91, 227, 142))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(out) }, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
        log("SpoX Engine test 0.1")
        log("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
        log("Tap 1, wait, then tap 2 and send the report.")
    }

    private fun log(s: String) {
        report.appendLine(s)
        runOnUiThread { out.text = report.toString() }
    }

    private var running = false

    private fun runTest() {
        if (running) return
        running = true
        thread {
            try {
                val dir = File(filesDir, "soloist").apply { deleteRecursively(); mkdirs() }
                log("\n== download ==")
                val c = URL(soloistUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 60000
                log("HTTP ${c.responseCode}, ${c.contentLengthLong / 1024} KB")
                val files = untarGz(BufferedInputStream(c.inputStream), dir)
                log("unpacked ${files.size} files:")
                files.take(40).forEach { log("  ${it.relativeTo(dir)}  ${it.length() / 1024} KB") }
                val bin = files.firstOrNull { it.name == "soloist" } ?: files.maxByOrNull { it.length() }
                if (bin == null) {
                    log("no program found"); return@thread
                }
                bin.setExecutable(true, false)
                log("\n== program: ${bin.name} ==")
                elfInfo(bin)
                log("\n== start it ==")
                for (args in listOf(listOf("--help"), listOf("--version"))) {
                    tryRun(listOf(bin.absolutePath) + args, dir)
                }
                // if it needs the glibc loader, also try the phone's own loader (normally fails)
                log("\n== done ==")
            } catch (e: Throwable) {
                log("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                running = false
            }
        }
    }

    private fun tryRun(cmd: List<String>, dir: File) {
        log("$ ${cmd.joinToString(" ") { it.substringAfterLast('/') }}")
        try {
            val p = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start()
            val text = StringBuilder()
            val reader = thread { text.append(p.inputStream.bufferedReader().readText().take(4000)) }
            val done = p.waitFor(8, TimeUnit.SECONDS)
            if (!done) p.destroy()
            reader.join(2000)
            log("exit: ${if (done) p.exitValue() else "still running after 8 s (killed)"}")
            log(text.toString().trim().ifEmpty { "(no output)" })
        } catch (e: Throwable) {
            log("could not start: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---- tar.gz (only what Soloist's archive needs) ------------------------------------------

    private fun untarGz(input: InputStream, dest: File): List<File> {
        val out = mutableListOf<File>()
        GZIPInputStream(input).use { gz ->
            val header = ByteArray(512)
            while (true) {
                if (readFully(gz, header) < 512) break
                if (header.all { it.toInt() == 0 }) break
                val name = String(header, 0, 100).trimEnd('\u0000').let { n ->
                    val prefix = String(header, 345, 155).trimEnd('\u0000')
                    if (prefix.isNotEmpty()) "$prefix/$n" else n
                }
                val size = String(header, 124, 12).trim('\u0000', ' ').toLongOrNull(8) ?: 0
                val type = header[156].toInt().toChar()
                val f = File(dest, name.removePrefix("./"))
                if (!f.canonicalPath.startsWith(dest.canonicalPath)) throw IllegalStateException("bad path in archive")
                when (type) {
                    '5' -> f.mkdirs()
                    '0', '\u0000' -> {
                        f.parentFile?.mkdirs()
                        f.outputStream().use { o ->
                            var left = size
                            val buf = ByteArray(65536)
                            while (left > 0) {
                                val n = gz.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) break
                                o.write(buf, 0, n)
                                left -= n
                            }
                        }
                        out += f
                    }
                    else -> skip(gz, size)
                }
                val pad = (512 - size % 512) % 512
                skip(gz, pad)
            }
        }
        return out
    }

    private fun readFully(i: InputStream, b: ByteArray): Int {
        var n = 0
        while (n < b.size) {
            val r = i.read(b, n, b.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private fun skip(i: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) break
            left -= r
        }
    }

    // ---- ELF: what loader and libraries the program asks for ------------------------------

    private fun elfInfo(f: File) {
        RandomAccessFile(f, "r").use { r ->
            val id = ByteArray(16)
            r.readFully(id)
            if (id[0] != 0x7f.toByte() || id[1] != 'E'.code.toByte()) {
                log("not an ELF program (first bytes: ${id.take(4)})"); return
            }
            val bits64 = id[4].toInt() == 2
            log("ELF ${if (bits64) "64" else "32"}-bit, size ${f.length() / 1024} KB")
            if (!bits64) return
            fun u16(o: Long): Int { r.seek(o); val b = ByteArray(2); r.readFully(b); return (b[0].toInt() and 255) or ((b[1].toInt() and 255) shl 8) }
            fun u32(o: Long): Long { r.seek(o); val b = ByteArray(4); r.readFully(b); var v = 0L; for (i in 3 downTo 0) v = (v shl 8) or (b[i].toLong() and 255); return v }
            fun u64(o: Long): Long { r.seek(o); val b = ByteArray(8); r.readFully(b); var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 255); return v }
            fun str(o: Long): String { r.seek(o); val sb = StringBuilder(); while (true) { val c = r.read(); if (c <= 0) break; sb.append(c.toChar()) }; return sb.toString() }
            val machine = u16(18)
            log("machine: ${if (machine == 183) "ARM64 (aarch64)" else machine.toString()}")
            val phoff = u64(32)
            val phentsize = u16(54)
            val phnum = u16(56)
            data class Ph(val type: Long, val off: Long, val vaddr: Long, val filesz: Long)
            val phs = (0 until phnum).map { i ->
                val b = phoff + i * phentsize
                Ph(u32(b), u64(b + 8), u64(b + 16), u64(b + 32))
            }
            fun v2o(v: Long): Long? = phs.filter { it.type == 1L }.firstOrNull { v >= it.vaddr && v < it.vaddr + it.filesz }?.let { it.off + (v - it.vaddr) }
            val interp = phs.firstOrNull { it.type == 3L }?.let { str(it.off) }
            log("loader (interpreter): ${interp ?: "none, statically linked"}")
            val dyn = phs.firstOrNull { it.type == 2L }
            if (dyn == null) {
                log("no dynamic section: static program"); return
            }
            val needed = mutableListOf<Long>()
            var strtab = 0L
            var o = dyn.off
            while (o < dyn.off + dyn.filesz) {
                val tag = u64(o)
                val v = u64(o + 8)
                if (tag == 0L) break
                if (tag == 1L) needed += v
                if (tag == 5L) strtab = v
                o += 16
            }
            val so = v2o(strtab)
            log("needs libraries:")
            needed.forEach { log("  " + (so?.let { s -> str(s + it) } ?: "?")) }
        }
    }
}
