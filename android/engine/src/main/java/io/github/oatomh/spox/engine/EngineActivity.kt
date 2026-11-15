package io.github.oatomh.spox.engine

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * "SpoX without Spotify": runs Spotify's Soloist (its Linux ARM64 build, downloaded from Spotify's
 * own server, never bundled) on the phone. Soloist is a normal Linux program, so this fetches the
 * few Linux libraries it needs from Debian and starts it through spoxrun, a tiny helper that
 * answers the Linux calls Android doesn't allow and points Linux's /etc lookups at a private folder.
 */
class EngineActivity : Activity() {
    private lateinit var out: TextView
    private lateinit var scroller: ScrollView
    private lateinit var keyBox: EditText
    private lateinit var liveBtn: Button
    private val report = StringBuilder()
    private val soloistUrl = "https://soloist-builds.spotifycdn.com/soloist_release_arm64.tar.gz"
    private val version = "0.5"

    private val prefs get() = getSharedPreferences("engine", 0)
    private val dir get() = File(filesDir, "soloist")
    private val root get() = File(filesDir, "linux")
    private val lib get() = File(root, "usr/lib/aarch64-linux-gnu")
    private val bin get() = File(dir, "soloist")
    private val run get() = File(filesDir, "spoxrun")
    private val data get() = File(filesDir, "soloist-data")
    private val cache get() = File(cacheDir, "soloist-cache")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(10, 10, 11))
            setPadding(32, 96, 32, 32)
        }
        fun button(t: String, f: () -> Unit) = Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { f() }
            box.addView(this)
        }
        button("1. Set up and test") { setUp() }
        keyBox = EditText(this).apply {
            hint = "Your Soloist API key (stays on this phone)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("key", ""))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            box.addView(this)
        }
        liveBtn = button("2. Start Soloist") { if (live == null) startLive() else stopLive() }
        button("3. Copy report") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SpoX Engine report", report.toString()))
            log("(report copied: paste it to Claude)")
        }
        out = TextView(this).apply {
            setTextColor(Color.rgb(91, 227, 142))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        scroller = ScrollView(this).apply { addView(out) }
        box.addView(scroller, LinearLayout.LayoutParams(-1, -1))
        setContentView(box)
        log("SpoX Engine test $version")
        log("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
        log("Tap 1 and wait. Then paste your key, tap 2, and on your PC open Spotify > Connect")
        log("(the speaker icon) and pick \"SpoX Phone\". After ~30 s tap 3 and send the report.")
    }

    override fun onDestroy() {
        stopLive()
        super.onDestroy()
    }

    private fun log(s: String) {
        val clean = prefs.getString("key", "").orEmpty().let { k -> if (k.length >= 6) s.replace(k, "***") else s }
        synchronized(report) { report.appendLine(clean) }
        runOnUiThread {
            out.text = synchronized(report) { report.toString() }
            scroller.post { scroller.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private var busy = false

    // ---- 1. set up -----------------------------------------------------------------------

    private fun setUp() {
        if (busy) return
        busy = true
        thread {
            try {
                prepare(force = true)
                val ld = loaderPath()
                log("\n== Soloist starts? ==")
                tryRun(listOf(run.path, ld, "--library-path", lib.path, bin.path, "--version"), dir, linuxEnv())
                log("\n== DNS (finding Spotify's servers) ==")
                val getent = File(root, "usr/bin/getent")
                if (getent.exists()) {
                    for (h in listOf("apresolve.spotify.com", "spclient.wg.spotify.com"))
                        tryRun(listOf(run.path, ld, "--library-path", lib.path, getent.path, "ahostsv4", h), dir, linuxEnv())
                } else log("(no getent)")
                log("\n== done: now paste your key and tap 2 ==")
            } catch (e: Throwable) {
                log("failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    private fun loaderPath(): String = File(lib, "ld-linux-aarch64.so.1").canonicalPath

    /** Everything Soloist needs on disk. Downloads only what's missing (or all of it with force). */
    private fun prepare(force: Boolean) {
        if (force || !bin.exists()) {
            dir.deleteRecursively(); dir.mkdirs()
            log("\n== download Soloist ==")
            val c = URL(soloistUrl).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 60000
            log("HTTP ${c.responseCode}, ${c.contentLengthLong / 1024} KB")
            untarGz(BufferedInputStream(c.inputStream), dir)
            bin.setExecutable(true, false)
            elfInfo(bin)
        }
        if (force || !File(lib, "ld-linux-aarch64.so.1").exists() || !File(root, "usr/bin/getent").exists()) {
            log("\n== Linux libraries (Debian 13, arm64) ==")
            root.mkdirs()
            getDebs(listOf("libc6", "libgcc-s1", "libatomic1", "libc-bin"), root)
            File(loaderPath()).setExecutable(true, false)
        }
        runCatching {
            assets.open("spoxrun").use { i -> run.outputStream().use { i.copyTo(it) } }
            run.setExecutable(true, false)
        }.onFailure { log("spoxrun missing from this build!") }
        writeEtc()
    }

    /** Linux looks in /etc for DNS servers, certificates, users...: spoxrun points it here. */
    private fun writeEtc() {
        val etc = File(root, "etc").apply { mkdirs() }
        // the phone's own DNS servers, then public ones as backup
        val dns = runCatching {
            val cm = getSystemService(ConnectivityManager::class.java)
            cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') }
        }.getOrNull().orEmpty().filter { ':' !in it }.take(2) + listOf("1.1.1.1", "8.8.8.8")
        File(etc, "resolv.conf").writeText(dns.distinct().take(3).joinToString("") { "nameserver $it\n" } + "options timeout:2 attempts:2\n")
        File(etc, "hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(etc, "nsswitch.conf").writeText("passwd: files\ngroup: files\nhosts: files dns\n")
        val uid = android.system.Os.getuid()
        File(etc, "passwd").writeText("spox:x:$uid:$uid:SpoX:${filesDir.path}:/bin/sh\n")
        File(etc, "group").writeText("spox:x:$uid:\n")
        // Android's trusted certificates, as one Linux-style bundle
        val certs = File(etc, "ssl/certs").apply { mkdirs() }
        val bundle = File(certs, "ca-certificates.crt")
        if (!bundle.exists() || bundle.length() < 1000) {
            val srcs = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")
                .map(::File).firstOrNull { it.isDirectory && (it.list()?.isNotEmpty() == true) }
            val pem = StringBuilder()
            srcs?.listFiles()?.forEach { f ->
                val t = runCatching { f.readText() }.getOrNull() ?: return@forEach
                val a = t.indexOf("-----BEGIN CERTIFICATE-----")
                val b = t.indexOf("-----END CERTIFICATE-----")
                if (a >= 0 && b > a) pem.append(t, a, b + 25).append('\n')
            }
            bundle.writeText(pem.toString())
            File(etc, "ssl/cert.pem").writeText(pem.toString())
        }
        log("etc: DNS ${dns.distinct().take(3).joinToString()}, ${bundle.length() / 1024} KB of certificates")
    }

    private fun linuxEnv() = mapOf(
        "SPOXRUN_ETC" to File(root, "etc").path,
        "HOME" to filesDir.path,
        "TMPDIR" to cacheDir.path,
        "SSL_CERT_FILE" to File(root, "etc/ssl/certs/ca-certificates.crt").path,
        "SSL_CERT_DIR" to File(root, "etc/ssl/certs").path,
        "LANG" to "C.UTF-8",
    )

    // ---- 2. live run ----------------------------------------------------------------------

    @Volatile private var live: Process? = null
    private var multicast: WifiManager.MulticastLock? = null

    private fun startLive() {
        val key = keyBox.text.toString().trim()
        if (key.length < 6) { log("\nPaste your Soloist API key first (from the Soloist page, same as on Windows)."); return }
        prefs.edit().putString("key", key).apply()
        if (busy) return
        busy = true
        liveBtn.text = "2. Stop Soloist"
        thread {
            try {
                prepare(force = false)
                data.mkdirs(); cache.mkdirs()
                File(data, "ws.port").delete()
                // Spotify Connect finds devices by multicast on the Wi-Fi; Android filters it unless asked
                multicast = getSystemService(WifiManager::class.java).createMulticastLock("spox").apply { setReferenceCounted(false); acquire() }
                val cmd = listOf(run.path, loaderPath(), "--library-path", lib.path, bin.path,
                    "-n", "SpoX Phone", "-k", key, "-D", data.path, "-C", cache.path, "-w", "127.0.0.1:0", "-v")
                log("\n== Soloist running (tap 2 again to stop) ==")
                val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
                pb.environment().putAll(linuxEnv())
                val p = pb.start()
                live = p
                val t0 = System.currentTimeMillis()
                thread { watchWs(p) }
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { l ->
                        val sec = (System.currentTimeMillis() - t0) / 1000
                        log("[${sec}s] ${l.take(300)}")
                    }
                }
                val code = p.waitFor()
                log("Soloist ended: ${if (code > 128) "$code = ${signals[code - 128] ?: "signal ${code - 128}"}" else "$code"}${if (code == 10) " (this Soloist build expired: tap 1)" else ""}")
            } catch (e: Throwable) {
                log("failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                live = null
                busy = false
                multicast?.release()
                runOnUiThread { liveBtn.text = "2. Start Soloist" }
            }
        }
    }

    private fun stopLive() {
        live?.destroy()
    }

    /** Soloist writes the port of its control connection to ws.port: check that it answers. */
    private fun watchWs(p: Process) {
        val f = File(data, "ws.port")
        repeat(60) {
            if (!p.isAlive) return
            val port = runCatching { f.readText().trim().toInt() }.getOrNull()
            if (port != null) {
                log("[ws] control port $port")
                runCatching {
                    Socket("127.0.0.1", port).use { s ->
                        s.soTimeout = 3000
                        val key = android.util.Base64.encodeToString(ByteArray(16).also { java.util.Random().nextBytes(it) }, android.util.Base64.NO_WRAP)
                        s.getOutputStream().write("GET / HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n".toByteArray())
                        val buf = ByteArray(2048)
                        val n = s.getInputStream().read(buf)
                        val text = String(buf, 0, maxOf(n, 0), Charsets.ISO_8859_1)
                        log("[ws] ${text.lineSequence().first()}")
                        val body = text.substringAfter("\r\n\r\n", "")
                        if (body.length > 2) log("[ws] first message: ${body.drop(2).filter { it.code in 32..126 }.take(300)}")
                    }
                }.onFailure { log("[ws] could not connect: ${it.message}") }
                return
            }
            Thread.sleep(500)
        }
        log("[ws] no control port after 30 s")
    }

    // ---- helpers ----------------------------------------------------------------------------

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
