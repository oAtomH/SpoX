package io.github.oatomh.spox.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
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
class Engine private constructor(private val ctx: Context) {
    companion object {
        const val VERSION = "1.1"
        @Volatile private var one: Engine? = null
        fun get(c: Context): Engine = one ?: synchronized(this) { one ?: Engine(c.applicationContext).also { one = it } }
    }

    private val report = StringBuilder()
    private val soloistUrl = "https://soloist-builds.spotifycdn.com/soloist_release_arm64.tar.gz"

    val prefs get() = ctx.getSharedPreferences("engine", 0)
    private val dir get() = File(ctx.filesDir, "soloist")
    private val root get() = File(ctx.filesDir, "linux")
    private val lib get() = File(root, "usr/lib/aarch64-linux-gnu")
    private val bin get() = File(dir, "soloist")
    private val run get() = File(ctx.filesDir, "spoxrun")
    private val data get() = File(ctx.filesDir, "soloist-data")
    // kept in the app's own storage (Android empties "cache" folders when it likes), so songs you
    // play again come from the phone instead of the internet: less mobile data
    private val cache get() = File(ctx.filesDir, "soloist-cache")

    /** Song cache size in MB (Settings > Song cache); Soloist's minimum is 100. */
    fun cacheMb() = ctx.getSharedPreferences("spox", 0).getInt("cache_mb", 1024)
    fun cacheUsedMb(): Long = (cache.walkTopDown().filter { it.isFile }.sumOf { it.length() }) / (1024 * 1024)

    // ---- log (shown in the app, copied as the report) --------------------------------------

    val logListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun reportText(): String = synchronized(report) { report.toString() }

    fun log(s: String) {
        val clean = prefs.getString("key", "").orEmpty().let { k -> if (k.length >= 6) s.replace(k, "***") else s }
        synchronized(report) {
            report.appendLine(clean)
            if (report.length > 400_000) report.delete(0, report.length - 300_000)
        }
        logListeners.forEach { it() }
    }

    init {
        log("SpoX Engine $VERSION")
        log("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    }

    private var busy = false

    // ---- 1. set up -----------------------------------------------------------------------

    fun setUp() {
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
            val had = File(lib, "ld-linux-aarch64.so.1").exists()
            runCatching { getDebs(listOf("libc6", "libgcc-s1", "libatomic1", "libc-bin"), root) }
                .onFailure { if (had) log("(Debian not reachable right now, keeping the libraries already here)") else throw it }
            File(loaderPath()).setExecutable(true, false)
        }
        runCatching {
            ctx.assets.open("engine/spoxrun").use { i -> run.outputStream().use { i.copyTo(it) } }
            run.setExecutable(true, false)
        }.onFailure { log("spoxrun missing from this build!") }
        // SpoX's stand-in for PipeWire (Linux's sound system): Soloist plays into it, it hands the
        // sound to this app, and this app plays it on the phone
        runCatching {
            ctx.assets.open("engine/libpipewire-0.3.so.0").use { i -> File(lib, "libpipewire-0.3.so.0").outputStream().use { i.copyTo(it) } }
        }.onFailure { log("sound bridge missing from this build!") }
        writeEtc()
    }

    /** Linux looks in /etc for DNS servers, certificates, users...: spoxrun points it here. */
    private fun lookUpHosts(hosts: File) {
        val names = listOf("apresolve.spotify.com", "spclient.wg.spotify.com", "dealer.spotify.com",
            "login5.spotify.com", "clienttoken.spotify.com", "api.spotify.com", "accounts.spotify.com",
            "ap.spotify.com", "ap-gew1.spotify.com", "ap-gew4.spotify.com", "ap-gue1.spotify.com", "ap-gae2.spotify.com",
            "gew1-spclient.spotify.com", "gew4-spclient.spotify.com", "gue1-spclient.spotify.com", "gae2-spclient.spotify.com",
            "gew1-dealer.spotify.com", "gew4-dealer.spotify.com", "gue1-dealer.spotify.com",
            "audio4-ak-spotify-com.akamaized.net", "audio-ak-spotify-com.akamaized.net", "audio-ak.spotify.com.edgesuite.net",
            "audio4-fa.scdn.co", "audio-fa.scdn.co", "heads-fa.scdn.co", "heads4-ak-spotify-com.akamaized.net",
            "i.scdn.co", "seektables.scdn.co", "spotify.com", "www.spotify.com", "open.spotify.com",
            "soloist-builds.spotifycdn.com", "event-sender.spotify.com",
            "gew1-dealer.g2.spotify.com", "gew4-dealer.g2.spotify.com", "gue1-dealer.g2.spotify.com", "gae2-dealer.g2.spotify.com",
            "heads-fa-tls13.spotifycdn.com", "audio-fa-tls13.spotifycdn.com", "audio4-fa-tls13.spotifycdn.com", "i.scdn.co")
        val lines = StringBuilder("127.0.0.1 localhost\n::1 localhost\n")
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val found = names.map { n -> pool.submit<String?> {
            runCatching { java.net.InetAddress.getAllByName(n).firstOrNull { it is java.net.Inet4Address }?.hostAddress }.getOrNull()?.let { "$it $n\n" }
        } }.mapNotNull { runCatching { it.get(4, TimeUnit.SECONDS) }.getOrNull() }
        pool.shutdownNow()
        found.forEach { lines.append(it) }
        if (found.size > 10 || !hosts.exists()) hosts.writeText(lines.toString())
        log("hosts: ${found.size} of ${names.size} Spotify servers looked up")
    }

    private fun writeEtc() {
        val etc = File(root, "etc").apply { mkdirs() }
        // the phone's own DNS servers, then public ones as backup
        val dns = runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') }
        }.getOrNull().orEmpty().filter { ':' !in it }.take(2) + listOf("1.1.1.1", "8.8.8.8")
        File(etc, "resolv.conf").writeText(dns.distinct().take(3).joinToString("") { "nameserver $it\n" } + "options timeout:2 attempts:2\n")
        // Spotify's server addresses, looked up by Android (Soloist finds them even if its own
        // lookups fail). Looking ~40 names up takes seconds: reuse the list from last time if it's
        // recent and refresh it in the background, so the engine starts at once.
        val hosts = File(etc, "hosts")
        val fresh = hosts.exists() && System.currentTimeMillis() - hosts.lastModified() < 12 * 3600_000L && hosts.readLines().size > 15
        if (fresh) thread(name = "spox-hosts") { runCatching { lookUpHosts(hosts) } } else lookUpHosts(hosts)
        File(etc, "nsswitch.conf").writeText("passwd: files\ngroup: files\nhosts: files dns\n")
        val uid = android.system.Os.getuid()
        File(etc, "passwd").writeText("spox:x:$uid:$uid:SpoX:${ctx.filesDir.path}:/bin/sh\n")
        File(etc, "group").writeText("spox:x:$uid:\n")
        val pwConf = "# SpoX: sound goes to the SpoX Engine app\ncontext.properties = { }\n"
        File(etc, "pipewire").mkdirs(); File(etc, "pipewire/client.conf").writeText(pwConf)
        File(ctx.filesDir, "config/pipewire").mkdirs(); File(ctx.filesDir, "config/pipewire/client.conf").writeText(pwConf)
        File(ctx.cacheDir, "run").mkdirs()
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
        "SPOXRUN_LOG" to (if (tracing) "1" else "0"),
        "SPOXRUN_ETC" to File(root, "etc").path,
        "HOME" to ctx.filesDir.path,
        "TMPDIR" to ctx.cacheDir.path,
        "SSL_CERT_FILE" to File(root, "etc/ssl/certs/ca-certificates.crt").path,
        "SSL_CERT_DIR" to File(root, "etc/ssl/certs").path,
        "LANG" to "C.UTF-8",
        "XDG_CONFIG_HOME" to File(ctx.filesDir, "config").path,
        "PIPEWIRE_CONFIG_DIR" to File(root, "etc/pipewire").path,
        "XDG_RUNTIME_DIR" to File(ctx.cacheDir, "run").path,
    )

    // ---- 2. live run ----------------------------------------------------------------------

    @Volatile private var live: Process? = null
    @Volatile private var tracing = false
    private var multicast: WifiManager.MulticastLock? = null

    val isLive get() = live != null || starting
    @Volatile private var starting = false

    fun startLive(key: String) {
        if (key.length < 6) { log("\nPaste your Soloist API key first (from the Soloist page, same as on Windows)."); return }
        prefs.edit().putString("key", key).apply()
        if (busy || isLive) return
        busy = true
        starting = true
        thread {
            try {
                prepare(force = false)
                data.mkdirs(); cache.mkdirs()
                File(ctx.cacheDir, "soloist-cache").takeIf { it.exists() }?.deleteRecursively()
                File(data, "ws.port").delete()
                tracing = false
                startAudio()
                // Spotify Connect finds devices by multicast on the Wi-Fi; Android filters it unless asked
                multicast = ctx.getSystemService(WifiManager::class.java).createMulticastLock("spox").apply { setReferenceCounted(false); acquire() }
                val cmd = listOf(run.path, loaderPath(), "--library-path", lib.path, bin.path,
                    "-n", "SpoX Phone", "-k", key, "-D", data.path, "-C", cache.path, "-z", cacheMb().coerceAtLeast(100).toString(), "-w", "127.0.0.1:0", "-v")
                log("\n== Soloist running (tap 2 again to stop) ==")
                val pb = ProcessBuilder(cmd).directory(dir).redirectErrorStream(true)
                pb.environment().putAll(linuxEnv())
                val p = pb.start()
                live = p
                starting = false
                val t0 = System.currentTimeMillis()
                thread { watchWs(p) }
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { l ->
                        val sec = (System.currentTimeMillis() - t0) / 1000
                        log("[${sec}s] ${named(l).replace("\u001b[0m", "").replace(Regex("\u001b\\[[0-9;]*m"), "").take(300)}")
                        Regex("Started ZeroConf service on port (\\d+)").find(l)?.let { announce(it.groupValues[1].toInt()) }
                    }
                }
                val code = p.waitFor()
                log("Soloist ended: ${if (code > 128) "$code = ${signals[code - 128] ?: "signal ${code - 128}"}" else "$code"}${if (code == 10) " (this Soloist build expired: tap 1)" else ""}")
            } catch (e: Throwable) {
                log("failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                live = null
                starting = false
                tracing = false
                busy = false
                multicast?.release()
                unannounce()
                stopAudio()
                ws?.close(); ws = null
                now.reset()
                stateListeners.forEach { it(null) }
            }
        }
    }

    // ---- sound: pwshim (inside Soloist) sends raw sound here, Android plays it ----------------

    @Volatile private var audioServer: android.net.LocalServerSocket? = null

    // ---- the real visualizer: a copy of the sound, read where the speaker is right now -------

    /** Silence the phone at once (headphones disconnected: not a note out of the speaker) and pause. */
    @Volatile private var hushedUntil = 0L
    fun hush() {
        hushedUntil = System.currentTimeMillis() + 1500
        runCatching { playing?.let { it.pause(); it.flush() } }
        command("pause")
    }

    private val ring = FloatArray(1 shl 16)
    @Volatile private var ringFrames = 0L
    @Volatile private var playing: android.media.AudioTrack? = null
    @Volatile private var rate = 44100
    private var carry = 0
    private val carryBuf = ByteArray(8)

    private fun tap(b: ByteArray, n: Int, float: Boolean, ch: Int) {
        val bps = if (float) 4 else 2
        val fb = bps * ch
        var i = 0
        val bb = java.nio.ByteBuffer.wrap(b, 0, n).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        // (reads arrive in any size: frames split across reads are simply dropped, it's only for the picture)
        val frames = n / fb
        var f = ringFrames
        while (i < frames) {
            var sum = 0f
            for (c in 0 until ch) {
                val o = i * fb + c * bps
                sum += if (float) bb.getFloat(o) else bb.getShort(o) / 32768f
            }
            ring[(f and 0xffff).toInt()] = sum / ch
            f++; i++
        }
        ringFrames = f
    }

    private val win = FloatArray(1024) { (0.5 - 0.5 * Math.cos(2 * Math.PI * it / 1023)).toFloat() }
    private val re = FloatArray(1024)
    private val im = FloatArray(1024)

    /** Loudness of [out].size frequency bands (0..1) of what the speaker plays right now. */
    @Synchronized
    fun spectrum(out: FloatArray): Boolean {
        val t = playing ?: return false
        val head = runCatching { t.playbackHeadPosition.toLong() and 0xffffffffL }.getOrNull() ?: return false
        val end = minOf(head, ringFrames)
        if (end < 1024) return false
        for (k in 0 until 1024) { re[k] = ring[((end - 1024 + k) and 0xffff).toInt()] * win[k]; im[k] = 0f }
        fft(re, im)
        val nyq = rate / 2f
        val lo = 40f; val hi = minOf(16000f, nyq)
        for (b in out.indices) {
            val f0 = lo * Math.pow((hi / lo).toDouble(), b.toDouble() / out.size).toFloat()
            val f1 = lo * Math.pow((hi / lo).toDouble(), (b + 1).toDouble() / out.size).toFloat()
            val i0 = (f0 / nyq * 512).toInt().coerceIn(1, 511)
            val i1 = (f1 / nyq * 512).toInt().coerceIn(i0 + 1, 512)
            var m = 0f
            for (k in i0 until i1) m = maxOf(m, re[k] * re[k] + im[k] * im[k])
            // decibels, -60..0 dB -> 0..1
            val db = 10f * kotlin.math.log10(m + 1e-12f) - 24f
            out[b] = ((db + 60f) / 60f).coerceIn(0f, 1f)
        }
        return true
    }

    private fun fft(x: FloatArray, y: FloatArray) {
        val n = x.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { val a = x[i]; x[i] = x[j]; x[j] = a; val c = y[i]; y[i] = y[j]; y[j] = c }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * Math.PI / len
            val wr = Math.cos(ang).toFloat(); val wi = Math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val ar = x[i + k + len / 2] * cr - y[i + k + len / 2] * ci
                    val ai = x[i + k + len / 2] * ci + y[i + k + len / 2] * cr
                    x[i + k + len / 2] = x[i + k] - ar; y[i + k + len / 2] = y[i + k] - ai
                    x[i + k] += ar; y[i + k] += ai
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Sound written to Android's player but not heard yet (ms). */
    @Volatile var audioLagMs = 120L

    private fun startAudio() {
        stopAudio()
        val server = runCatching { android.net.LocalServerSocket("spox-audio") }.getOrElse { log("[audio] ${it.message}"); return }
        audioServer = server
        thread(name = "spox-audio") {
            while (audioServer === server) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                thread { play(sock) }
            }
        }
    }

    private fun stopAudio() {
        audioServer?.let { s -> audioServer = null; runCatching { s.close() } }
    }

    private fun play(sock: android.net.LocalSocket) {
        var track: android.media.AudioTrack? = null
        try {
            runCatching { sock.receiveBufferSize = 16384 }
            val input = java.io.DataInputStream(sock.inputStream.buffered(8192))
            val hdr = ByteArray(16)
            input.readFully(hdr)
            val h = java.nio.ByteBuffer.wrap(hdr).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val magic = h.int; val rate = h.int; val ch = h.int; val fmt = h.int
            if (magic != 0x58504f53) { log("[audio] unknown sender"); return }
            val enc = when (fmt) { 0x103 -> android.media.AudioFormat.ENCODING_PCM_16BIT; 0x11b -> android.media.AudioFormat.ENCODING_PCM_FLOAT; else -> { log("[audio] sound format 0x${fmt.toString(16)} not supported yet"); return } }
            val mask = if (ch == 1) android.media.AudioFormat.CHANNEL_OUT_MONO else android.media.AudioFormat.CHANNEL_OUT_STEREO
            val min = android.media.AudioTrack.getMinBufferSize(rate, mask, enc)
            track = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(rate).setChannelMask(mask).setEncoding(enc).build())
                .setBufferSizeInBytes(maxOf(min * 2, rate / 12 * ch * 4))
                .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                .build()
            track.play()
            this.rate = rate
            ringFrames = 0
            log("[audio] playing on the phone: $rate Hz, $ch channels, ${if (fmt == 0x11b) "float" else "16-bit"}")
            val buf = java.nio.ByteBuffer.allocateDirect(16384).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val arr = ByteArray(16384)
            var total = 0L
            val frameBytes = ch * (if (fmt == 0x11b) 4 else 2)
            var written = 0L
            while (true) {
                val n = input.read(arr)
                if (n <= 0) break
                buf.clear(); buf.put(arr, 0, n); buf.flip()
                tap(arr, n, fmt == 0x11b, ch)
                playing = track
                // just hushed: drop what Soloist still sends before its pause lands
                if (System.currentTimeMillis() < hushedUntil) continue
                // stopped by hush() (headphones pulled out): start again with the next sound
                if (track.playState != android.media.AudioTrack.PLAYSTATE_PLAYING) track.play()
                while (buf.hasRemaining()) {
                    val w = track.write(buf, buf.remaining(), android.media.AudioTrack.WRITE_BLOCKING)
                    if (w < 0) { log("[audio] write error $w"); return }
                }
                if (total == 0L) log("[audio] first sound arrived")
                total += n
                written += n / frameBytes
                // how far behind the speaker is (written but not heard yet): lyrics use this
                val queued = written - (track.playbackHeadPosition.toLong() and 0xffffffffL)
                audioLagMs = (queued * 1000 / rate + 20).coerceIn(0, 2000)
            }
            log("[audio] stream ended (${total / 1024} KB played)")
        } catch (e: Throwable) {
            log("[audio] ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            playing = null
            runCatching { track?.stop(); track?.release() }
            runCatching { sock.close() }
        }
    }

    // Soloist can't see the phone's Wi-Fi address, so its own "I'm here" messages never go out.
    // Android announces it instead, the standard way, pointing at Soloist's login server.
    private var nsdListener: NsdManager.RegistrationListener? = null

    private fun announce(port: Int) {
        val nsd = ctx.getSystemService(NsdManager::class.java)
        val info = NsdServiceInfo().apply {
            serviceName = "SpoX Phone"
            serviceType = "_spotify-connect._tcp"
            setPort(port)
            setAttribute("CPath", "/zc")
            setAttribute("VERSION", "1.0")
            setAttribute("Stack", "SP")
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) = log("[connect] announced as \"${i.serviceName}\" on port $port")
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) = log("[connect] announce failed ($e)")
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        unannounce()
        nsdListener = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }.onFailure { log("[connect] ${it.message}") }
    }

    private fun unannounce() {
        nsdListener?.let { l -> runCatching { ctx.getSystemService(NsdManager::class.java).unregisterService(l) } }
        nsdListener = null
    }

    private fun named(l: String) = l.replace(Regex("blocked syscall (\\d+)")) {
        val n = it.groupValues[1].toInt(); "blocked syscall $n (${calls[n] ?: "?"})"
    }

    fun stopLive() {
        live?.destroy()
    }

    // ---- control connection (Soloist's WebSocket API, like SpoX on Windows) -------------------

    @Volatile private var ws: Ws? = null
    /** Every message Soloist sends (null = Soloist stopped). */
    val stateListeners = java.util.concurrent.CopyOnWriteArrayList<(org.json.JSONObject?) -> Unit>()

    private val out = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** Sends on a background thread (Android doesn't allow network on the main thread). */
    fun send(json: String) { out.execute { ws?.send(json) } }
    fun command(name: String, extra: Map<String, Any> = emptyMap()) {
        val o = org.json.JSONObject().put("type", "command").put("command", name)
        extra.forEach { (k, v) -> o.put(k, v) }
        send(o.toString())
    }

    /** Jump n songs ahead in the queue (Soloist skips one at a time, so: paced skips). */
    fun skipAhead(n: Int) {
        thread {
            repeat(n.coerceIn(1, 50)) { command("skip_next"); Thread.sleep(450) }
        }
    }

    // ---- what's playing, from Soloist's messages ---------------------------------------------

    class Track(val uri: String, val title: String, val artist: String, val album: String, val durationMs: Long, val cover: String, val provider: String = "")

    inner class Now {
        @Volatile var track: Track? = null
        @Volatile var status = "stopped"
        @Volatile var posMs = 0.0
        @Volatile var posAt = 0L
        @Volatile var speed = 1.0
        @Volatile var shuffle = false
        @Volatile var repeat = "off"
        @Volatile var active = false
        @Volatile var queue: List<Track> = emptyList()
        @Volatile var contextUri = ""
        @Volatile var queueAsked = 0L
        @Volatile var contextName = ""
        @Volatile var art: android.graphics.Bitmap? = null
        private var artFor = ""
        val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

        val playing get() = status == "playing" || status == "buffering"
        /** Soloist's own position (what it has sent to the speaker). */
        fun position(): Long {
            var p = posMs
            if (playing && posAt > 0) p += (System.currentTimeMillis() - posAt) * speed
            val d = track?.durationMs ?: 0
            if (d > 0) p = p.coerceAtMost(d.toDouble())
            return p.toLong().coerceAtLeast(0)
        }

        fun reset() { track = null; status = "stopped"; active = false; queue = emptyList(); changed() }
        private fun changed() = listeners.forEach { it() }

        private fun name(o: org.json.JSONObject?): String = o?.optJSONObject("decorations")?.optJSONObject("identity")?.optString("name").orEmpty()

        private fun context(c: org.json.JSONObject?) {
            if (c == null) return
            contextUri = c.optString("uri")
            contextName = name(c)
        }

        private fun parse(o: org.json.JSONObject?, provider: String = ""): Track? {
            if (o == null || !o.has("uri")) return null
            val d = o.optJSONObject("decorations")
            val artist = d?.optJSONArray("creators")?.let { a -> (0 until a.length()).map { name(a.optJSONObject(it)?.optJSONObject("entity")) }.filter { it.isNotEmpty() }.joinToString(", ") }.orEmpty()
            val covers = d?.optJSONObject("visual_identity")?.optJSONArray("cover")
            val cover = covers?.let { c ->
                val list = (0 until c.length()).mapNotNull { c.optJSONObject(it) }
                (list.firstOrNull { it.optString("size") == "large" } ?: list.firstOrNull { it.optString("size") == "default" } ?: list.firstOrNull())?.optString("url")
            }.orEmpty()
            return Track(o.optString("uri"), name(o), artist, name(d?.optJSONObject("parent")?.optJSONObject("entity")),
                d?.optJSONObject("playback")?.optLong("duration_ms") ?: 0, cover, provider)
        }

        private fun pos(p: org.json.JSONObject?) {
            if (p == null) return
            posMs = p.optDouble("position_ms", posMs)
            posAt = p.optLong("timestamp_ms", System.currentTimeMillis())
            speed = p.optDouble("speed", 1.0)
        }

        private fun options(o: org.json.JSONObject?) {
            if (o == null) return
            shuffle = o.optBoolean("shuffle", shuffle)
            repeat = o.optString("repeat", repeat)
        }

        fun apply(m: org.json.JSONObject) {
            val before = track?.uri
            when (m.optString("type")) {
                "playback_state" -> {
                    status = m.optString("status", status)
                    parse(m.optJSONObject("item"))?.let { track = it }
                    pos(m.optJSONObject("position"))
                    options(m.optJSONObject("options"))
                    context(m.optJSONObject("context"))
                    if (m.has("is_active")) active = m.optBoolean("is_active")
                }
                "context_changed" -> context(m.optJSONObject("context"))
                "track_changed" -> { parse(m.optJSONObject("item"))?.let { track = it }; posMs = 0.0; posAt = System.currentTimeMillis() }
                "playback_changed" -> { val p = position(); posMs = p.toDouble(); posAt = System.currentTimeMillis(); status = m.optString("status", status) }
                "position_sync" -> pos(m.optJSONObject("position"))
                "options_changed" -> options(m.optJSONObject("options"))
                "device_changed", "auth_state" -> if (m.has("is_active")) active = m.optBoolean("is_active")
            }
            // the queue comes as its own message or as the answer to get_queue
            val up = m.optJSONArray("upcoming") ?: m.optJSONObject("queue")?.optJSONArray("upcoming")
                ?: m.optJSONObject("result")?.optJSONArray("upcoming") ?: m.optJSONObject("data")?.optJSONArray("upcoming")
            if (up != null) {
                val list = (0 until up.length()).mapNotNull { i ->
                    up.optJSONObject(i)?.let { e ->
                        // where it comes from: "context" (the playlist), "queue" (you added it), "autoplay"...
                        val raw = e.opt("provider") ?: e.opt("source")
                        val pv = (if (raw is org.json.JSONObject) raw.optString("type") else raw?.toString()).orEmpty().lowercase()
                        parse(e.optJSONObject("item"), pv) ?: parse(e, pv)
                    }
                }
                // Soloist's own queue updates only carry the next 10 songs: keep the full list we
                // fetched if it still lines up, and fetch the whole list again
                val type = m.optString("type")
                val sameStart = list.firstOrNull()?.uri == queue.firstOrNull()?.uri
                if (!(type == "queue_changed" && list.size == 10 && queue.size > 10 && sameStart)) queue = list
                if (type == "queue_changed" && System.currentTimeMillis() - queueAsked > 2000) {
                    queueAsked = System.currentTimeMillis()
                    command("get_queue", mapOf("limit" to 0))
                }
            }
            val t = track
            if (t != null && t.uri != before) { queueAsked = System.currentTimeMillis(); command("get_queue", mapOf("limit" to 0)) }
            if (t != null && t.cover.isNotEmpty() && t.cover != artFor) {
                artFor = t.cover
                val want = t.cover
                thread {
                    val b = runCatching { URL(want).openStream().use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
                    if (want == artFor) { art = b; changed() }
                }
            }
            changed()
        }
    }

    val now = Now()

    private fun watchWs(p: Process) {
        val f = File(data, "ws.port")
        var said = false
        while (p.isAlive) {
            val port = runCatching { f.readText().trim().toInt() }.getOrNull()
            if (port == null) { Thread.sleep(300); continue }
            val c = runCatching { Ws.open(port) }.getOrNull()
            if (c == null) { Thread.sleep(1000); continue }
            ws = c
            if (!said) { log("[ws] connected to Soloist's control port $port"); said = true }
            c.send("{\"type\":\"command\",\"command\":\"get_state\"}")
            c.send("{\"type\":\"command\",\"command\":\"get_queue\",\"limit\":0}")
            try {
                while (p.isAlive) {
                    val m = c.read() ?: break
                    val o = runCatching { org.json.JSONObject(m) }.getOrNull() ?: continue
                    now.apply(o)
                    stateListeners.forEach { it(o) }
                }
            } catch (_: Throwable) {}
            ws = null
            c.close()
            Thread.sleep(500)
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private val calls = mapOf(
        293 to "rseq", 435 to "clone3", 439 to "faccessat2", 99 to "set_robust_list",
        202 to "accept", 147 to "setresuid", 149 to "setresgid", 146 to "setuid", 144 to "setgid",
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
