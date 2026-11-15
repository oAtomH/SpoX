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
        log("SpoX Engine test 0.4")
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
                // it's a normal Linux (glibc) program: fetch the few Linux libraries it asks for from
                // Debian and start it through Debian's own loader
                val root = File(filesDir, "linux").apply { mkdirs() }
                log("\n== Linux libraries (Debian 13, arm64) ==")
                getDebs(listOf("libc6", "libgcc-s1", "libatomic1"), root)
                val lib = File(root, "usr/lib/aarch64-linux-gnu")
                val ld = File(lib, "ld-linux-aarch64.so.1").let { if (it.exists()) it else File(root, "usr/lib/ld-linux-aarch64.so.1") }
                log("loader: ${ld.exists()} (${ld.canonicalPath.substringAfter(root.path)})")
                File(ld.canonicalPath).setExecutable(true, false)
                lib.listFiles()?.forEach { it.setReadable(true, false) }
                val loader = listOf(ld.canonicalPath, "--library-path", lib.path)
                val base = loader + bin.absolutePath
                log("\n== A. the Debian loader on its own ==")
                tryRun(loader + "--version", dir)
                log("\n== B. does it find every library ==")
                tryRun(loader + "--list" + bin.absolutePath, dir)
                // glibc registers "rseq" at startup, which Android may not allow
                val noRseq = mapOf("GLIBC_TUNABLES" to "glibc.pthread.rseq=0")
                log("\n== C. Soloist without rseq ==")
                tryRun(base + "--version", dir, noRseq)
                // sysguard: answers blocked calls with "not supported" instead of a crash,
                // and reports which ones they were
                val guard = File(filesDir, "sysguard.so")
                val haveGuard = runCatching {
                    assets.open("sysguard.so").use { i -> guard.outputStream().use { i.copyTo(it) } }
                    guard.setReadable(true, false)
                }.isSuccess
                if (haveGuard) {
                    val g = noRseq + ("LD_PRELOAD" to guard.path)
                    log("\n== D. Soloist with sysguard ==")
                    tryRun(base + "--version", dir, g)
                    tryRun(base + "--help", dir, g)
                    log("\n== E. sysguard, with rseq left on ==")
                    tryRun(base + "--version", dir, mapOf("LD_PRELOAD" to guard.path))
                } else log("\n(sysguard not inside this build)")
                // spoxrun: watches Soloist from outside and answers every blocked call with
                // "not supported", even the ones made before any add-on could load
                val run = File(filesDir, "spoxrun")
                val haveRun = runCatching {
                    assets.open("spoxrun").use { i -> run.outputStream().use { i.copyTo(it) } }
                    run.setExecutable(true, false)
                }.isSuccess
                if (haveRun) {
                    val r = listOf(run.path) + base
                    log("\n== F. spoxrun: does it find every library ==")
                    tryRun(listOf(run.path) + loader + "--list" + bin.absolutePath, dir)
                    log("\n== G. spoxrun + Soloist ==")
                    tryRun(r + "--version", dir)
                    tryRun(r + "--help", dir)
                    log("\n== H. spoxrun + Soloist, rseq off ==")
                    tryRun(r + "--version", dir, noRseq)
                } else log("\n(spoxrun not inside this build)")
                log("\n== system log ==")
                runCatching {
                    val p = ProcessBuilder("logcat", "-d", "-t", "400").redirectErrorStream(true).start()
                    val hits = p.inputStream.bufferedReader().readLines()
                        .filter { Regex("seccomp|SIGSYS|syscall=|type=1326|soloist|ld-linux", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                    hits.takeLast(15).forEach { log(it.take(220)) }
                    if (hits.isEmpty()) log("(nothing about it)")
                }
                log("\n== done ==")
            } catch (e: Throwable) {
                log("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                running = false
            }
        }
    }

    private val calls = mapOf(
        293 to "rseq", 435 to "clone3", 439 to "faccessat2", 99 to "set_robust_list",
        147 to "setresuid", 149 to "setresgid", 146 to "setuid", 144 to "setgid",
        219 to "keyctl", 217 to "add_key", 51 to "chroot", 40 to "mount", 167 to "prctl",
        278 to "getrandom", 441 to "epoll_pwait2", 436 to "close_range", 449 to "futex_waitv",
        434 to "pidfd_open", 437 to "openat2", 452 to "fchmodat2", 453 to "map_shadow_stack",
        444 to "landlock_create_ruleset", 280 to "bpf", 291 to "statx", 425 to "io_uring_setup",
        426 to "io_uring_enter", 241 to "perf_event_open", 117 to "ptrace", 272 to "kcmp",
        283 to "membarrier", 451 to "cachestat", 462 to "mseal", 279 to "memfd_create")

    private val signals = mapOf(4 to "SIGILL", 6 to "SIGABRT", 7 to "SIGBUS", 9 to "SIGKILL",
        11 to "SIGSEGV (crash)", 31 to "SIGSYS (Android blocked a system call)")

    private fun tryRun(cmd: List<String>, dir: File, env: Map<String, String> = emptyMap()) {
        val e = env.entries.joinToString(" ") { "${it.key}=${it.value.substringAfterLast('/')}" }
        log("$ ${if (e.isEmpty()) "" else "$e "}${cmd.joinToString(" ") { it.substringAfterLast('/') }}")
        try {
            val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
            pb.environment().putAll(env)
            val p = pb.start()
            val text = StringBuilder()
            val reader = thread { text.append(p.inputStream.bufferedReader().readText().take(4000)) }
            val done = p.waitFor(8, TimeUnit.SECONDS)
            if (!done) p.destroy()
            reader.join(2000)
            val code = if (done) p.exitValue() else -1
            log("exit: ${if (!done) "still running after 8 s (killed)" else if (code > 128) "$code = ${signals[code - 128] ?: "signal ${code - 128}"}" else "$code"}")
            val out = text.toString().trim().replace(Regex("blocked syscall (\\d+)")) {
                val n = it.groupValues[1].toInt(); "blocked syscall $n (${calls[n] ?: "?"})"
            }.replace(Regex(" (\\d+)x(\\d+)")) {
                val n = it.groupValues[1].toInt(); " ${calls[n] ?: n}x${it.groupValues[2]}"
            }
            log(out.ifEmpty { "(no output)" })
        } catch (e: Throwable) {
            log("could not start: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---- Debian packages ------------------------------------------------------------------

    private fun getDebs(names: List<String>, root: File) {
        val index = "https://deb.debian.org/debian/dists/trixie/main/binary-arm64/Packages.gz"
        val want = names.toMutableSet()
        val files = HashMap<String, String>()
        var pkg = ""
        GZIPInputStream(BufferedInputStream(URL(index).openStream())).bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.startsWith("Package: ")) pkg = line.removePrefix("Package: ").trim()
                else if (line.startsWith("Filename: ") && pkg in want) {
                    files[pkg] = line.removePrefix("Filename: ").trim()
                    want -= pkg
                    if (want.isEmpty()) break
                }
            }
        }
        for ((p, path) in files) {
            val url = "https://deb.debian.org/debian/$path"
            val bytes = URL(url).openStream().use { it.readBytes() }
            log("$p: ${path.substringAfterLast('/')} (${bytes.size / 1024} KB)")
            // .deb = "ar" archive; the files are in data.tar.xz
            var o = 8
            while (o + 60 <= bytes.size) {
                val name = String(bytes, o, 16).trim().trimEnd('/')
                val size = String(bytes, o + 48, 10).trim().toInt()
                val start = o + 60
                if (name.startsWith("data.tar")) {
                    val raw = bytes.inputStream(start, size)
                    val s: InputStream = when {
                        name.endsWith(".xz") -> org.tukaani.xz.XZInputStream(raw)
                        name.endsWith(".gz") -> GZIPInputStream(raw)
                        else -> raw
                    }
                    val got = untar(s, root)
                    log("  unpacked ${got.size} files")
                }
                o = start + size + (size and 1)
            }
        }
        if (want.isNotEmpty()) log("not found: $want")
    }

    // ---- tar.gz (only what Soloist's archive needs) ------------------------------------------

    private fun untarGz(input: InputStream, dest: File): List<File> = untar(GZIPInputStream(input), dest)

    private fun untar(stream: InputStream, dest: File): List<File> {
        val out = mutableListOf<File>()
        stream.use { gz ->
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
                    '2' -> {
                        val target = String(header, 157, 100).trimEnd('\u0000')
                        f.parentFile?.mkdirs()
                        f.delete()
                        runCatching { android.system.Os.symlink(target, f.path) }
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
