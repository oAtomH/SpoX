package io.github.oatomh.spox

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.content.ComponentName

class MainActivity : Activity() {
    private lateinit var view: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Player.init(this)
        Web.init(this)
        view = PlayerView(this)
        Web.listeners += { runOnUiThread { view.invalidate() } }
        view.onSearch = { askSearch() }
        view.onSignIn = { signIn() }
        view.onDevices = { pickDevice() }
        // full screen: SpoX draws under the status and navigation bars and keeps clear of them itself
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        setContentView(view)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
                if (!view.back()) moveTaskToBack(true)
            }
        }
        view.fps = getSharedPreferences("spox", 0).getInt("fps", 30)
        handle(intent)

        view.settingsRows = {
            val prefs = getSharedPreferences("spox", 0)
            val eng = io.github.oatomh.spox.engine.Engine.get(this)
            val hasKey = eng.prefs.getString("key", "").orEmpty().length >= 6
            listOf(
                "#SpoX Engine (Spotify without the Spotify app)" to "",
                "Engine" to when { !hasKey -> "add your key"; Player.fromEngine -> "on, playing"; eng.isLive -> "on"; else -> "off" },
                "Soloist key" to if (hasKey) "set (change...)" else "add...",
                "Engine report" to "copy",
                "Song cache" to cacheText(),
                "#Spotify account (library, search, likes)" to "",
                "Account" to if (Web.signedIn) Web.userName().ifEmpty { "signed in" } else Web.status.ifEmpty { "sign in..." },
                "Client ID" to if (Web.clientId().isEmpty()) "add..." else "set (change...)",
                "Last.fm scrobbling" to if (Stats.lastfmOn()) Stats.lastfmUser() else "connect...",
                "#Look" to "",
                "Colour pack" to Skin.packName(this),
                "Player in notifications" to when (SpoxNotification.style(this)) { "pixel" -> "pixel card"; "media" -> "phone's player"; else -> "off" },
                "Lock screen art" to when (SpoxNotification.lockArt(this)) { "cover" -> "cover"; "card" -> "SpoX card"; else -> "pixel cover" },
                "Smoothness" to when (prefs.getInt("fps", 30)) { 15 -> "battery (15 fps)"; 60 -> "smooth (60 fps)"; else -> "normal (30 fps)" },
                "#Listening" to "",
                "Sleep timer" to Player.sleepLabel(),
                "SpoX shuffle: new songs" to "${Smart.ratio(this)}%",
                "Volume buttons" to if (prefs.getBoolean("own_volume", true)) "SpoX volume" else "phone's slider",
                "When you close SpoX" to when (io.github.oatomh.spox.engine.EngineService.onClose(this)) { "now" -> "quit at once"; "keep" -> "keep running"; else -> "quit when paused" },
                "#Spotify app" to "",
                "Notification access" to if (Player.hasAccess(this)) "allowed" else "allow...",
                "Hide Spotify's own player" to "open...",
                "Open Spotify" to "",
                "#SpoX" to "",
                "Updates" to Updater.status.ifEmpty { "check now" },
                "Auto-update" to if (prefs.getBoolean("auto_update", true)) "on" else "off",
                "Memory (RAM)" to memText(),
                "Get SpoX for Windows" to "github",
                "Version" to (packageManager.getPackageInfo(packageName, 0).versionName ?: "?"),
            )
        }
        view.onSettingsAction = { what ->
            val prefs = getSharedPreferences("spox", 0)
            when (what) {
                "access", "Notification access" -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                "Player in notifications" -> SpoxNotification.cycleStyle(this)
                "Engine" -> {
                    val eng = io.github.oatomh.spox.engine.Engine.get(this)
                    when {
                        eng.prefs.getString("key", "").orEmpty().length < 6 -> askKey()
                        eng.isLive -> { prefs.edit().putBoolean("engine_on", false).apply(); io.github.oatomh.spox.engine.EngineService.stop(this) }
                        else -> { prefs.edit().putBoolean("engine_on", true).apply(); io.github.oatomh.spox.engine.EngineService.start(this) }
                    }
                    view.postDelayed({ view.invalidate() }, 600)
                }
                "Soloist key" -> askKey()
                "Last.fm scrobbling" -> lastfm()
                "Song cache" -> {
                    val sizes = listOf(500, 1024, 2048, 4096, 8192)
                    val cur = prefs.getInt("cache_mb", 1024)
                    view.show(PlayerView.Sheet("Song cache", listOf("Songs you play are kept on the phone, so", "playing them again uses less mobile data.", "Changes apply when SpoX Engine restarts."),
                        sizes.map { mb -> PlayerView.SheetItem(if (mb >= 1024) "${mb / 1024} GB" else "$mb MB", "", mb == cur) { prefs.edit().putInt("cache_mb", mb).apply(); cacheAt = 0 } }))
                }
                "Engine report" -> {
                    val eng = io.github.oatomh.spox.engine.Engine.get(this)
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("SpoX Engine report", eng.reportText()))
                    view.say("Engine report copied")
                }
                "Colour pack" -> {
                    val names = Skin.PACKS.map { it.name }
                    val next = names[(names.indexOf(Skin.packName(this)) + 1) % names.size]
                    prefs.edit().putString("pack", next).apply()
                    Skin.reload()
                    PlayerWidget.updateAll(this)
                }
                "Lock screen art" -> SpoxNotification.cycleLockArt(this)
                "Account" -> if (Web.signedIn) {
                    view.confirm("Spotify account", "Signed in as ${Web.userName()}.", "Sign out", "Keep") { Web.signOut() }
                } else signIn()
                "Client ID" -> askClientId { }
                "Sleep timer" -> Player.cycleSleep()
                "SpoX shuffle: new songs" -> view.mixSheet()
                "When you close SpoX" -> io.github.oatomh.spox.engine.EngineService.cycleOnClose(this)
                "Volume buttons" -> prefs.edit().putBoolean("own_volume", !prefs.getBoolean("own_volume", true)).apply()
                "Updates" -> Updater.check(this, true) { runOnUiThread { view.invalidate() } }
                "Smoothness" -> {
                    val next = when (prefs.getInt("fps", 30)) { 30 -> 60; 60 -> 15; else -> 30 }
                    prefs.edit().putInt("fps", next).apply()
                    view.fps = next
                }
                "Auto-update" -> prefs.edit().putBoolean("auto_update", !prefs.getBoolean("auto_update", true)).apply()
                "Open Spotify" -> Player.openApp(this)
                // Android won't let another app restyle or remove Spotify's player, but you can switch
                // its notification off: Spotify keeps playing, and SpoX's pixel card is the only player
                "Hide Spotify's own player" -> runCatching {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, "com.spotify.music"))
                }
                "Get SpoX for Windows" -> startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/oAtomH/SpoX/releases/latest")))
            }
            view.invalidate()
        }

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // SpoX Engine starts with the app once it's been switched on
        val engPrefs = io.github.oatomh.spox.engine.Engine.get(this).prefs
        if (getSharedPreferences("spox", 0).getBoolean("engine_on", false) && engPrefs.getString("key", "").orEmpty().length >= 6 &&
            !io.github.oatomh.spox.engine.Engine.get(this).isLive) io.github.oatomh.spox.engine.EngineService.start(this)
        if (getSharedPreferences("spox", 0).getBoolean("auto_update", true)) {
            Updater.check(this, false) { runOnUiThread { view.invalidate() } }
        }
    }

    @Deprecated("still the simplest way for a plain Activity")
    override fun onBackPressed() {
        if (!view.back()) moveTaskToBack(true)
    }

    /** Last.fm: your own API key + secret (last.fm/api/account/create), then approve SpoX once. */
    private fun lastfm() {
        if (Stats.lastfmOn()) {
            view.confirm("Last.fm", "Scrobbling as ${Stats.lastfmUser()}.", "Disconnect", "Keep") { Stats.disconnect() }
            return
        }
        view.ask("Last.fm API key", "API key", Stats.lastfmKey(),
            message = "Make a free API account at last.fm/api/account/create (any name, no callback needed), then paste its API key here.", ok = "Next") { key ->
            view.ask("Last.fm shared secret", "Shared secret", password = true, message = "The \"Shared secret\" from the same page.", ok = "Connect") { secret ->
                Stats.setKeys(key, secret)
                view.say("Approve SpoX on Last.fm, then come back")
                Stats.connect(this) { msg -> runOnUiThread { view.say(msg); view.invalidate() } }
            }
        }
    }

    private var cacheTxt = ""
    private var cacheAt = 0L
    private fun cacheText(): String {
        val eng = io.github.oatomh.spox.engine.Engine.get(this)
        val max = eng.cacheMb()
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - cacheAt > 10_000) {
            cacheAt = now
            kotlin.concurrent.thread { val used = eng.cacheUsedMb(); cacheTxt = "$used MB of ${if (max >= 1024) "${max / 1024} GB" else "$max MB"}" }
        }
        return cacheTxt.ifEmpty { if (max >= 1024) "${max / 1024} GB" else "$max MB" }
    }

    /** RAM: SpoX itself, and SpoX Engine (Soloist) running next to it. */
    private var memCache = ""
    private var memAt = 0L
    private fun memText(): String {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - memAt < 2000 && memCache.isNotEmpty()) return memCache
        memAt = now
        val app = android.os.Debug.getPss() / 1024
        var engine = 0L
        val me = android.os.Process.myPid()
        val uid = android.os.Process.myUid()
        java.io.File("/proc").listFiles()?.forEach { d ->
            val pid = d.name.toIntOrNull() ?: return@forEach
            if (pid == me) return@forEach
            val st = runCatching { java.io.File(d, "status").readLines() }.getOrNull() ?: return@forEach
            if (st.firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull() != uid) return@forEach
            st.firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.let { engine += it }
        }
        memCache = if (engine > 0) "SpoX $app MB · engine ${engine / 1024} MB" else "SpoX $app MB"
        return memCache
    }

    /** Move the music to another device: your PC (Spotify or SpoX), a speaker, this phone... */
    private fun pickDevice() {
        view.say("Looking for devices...")
        kotlin.concurrent.thread {
            val list = Web.devices()
            runOnUiThread {
                if (list.isEmpty()) {
                    view.say("No devices: open Spotify (or SpoX) on the other device")
                    return@runOnUiThread
                }
                val out = Player.output(this)
                val here = if (out.kind != "phone" && out.name.isNotEmpty()) "Sound: ${out.name}" else ""
                view.show(PlayerView.Sheet("Play on", if (here.isEmpty()) emptyList() else listOf(here), list.map { d ->
                    val kind = if (d.name == "SpoX Phone") "this phone" else when (d.type.lowercase()) { "computer" -> "PC"; "smartphone" -> "phone"; "speaker" -> "speaker"; "tv" -> "TV"; "automobile" -> "car"; else -> d.type.lowercase() }
                    PlayerView.SheetItem(d.name, if (d.active) "playing" else kind, d.active) {
                        kotlin.concurrent.thread {
                            val ok = Web.transfer(d.id)
                            runOnUiThread { view.say(if (ok) "Playing on ${d.name}" else "Couldn't move it to ${d.name}") }
                        }
                    }
                }))
            }
        }
    }

    private fun signIn() {
        if (Web.clientId().isEmpty()) askClientId { Web.signIn(this) } else Web.signIn(this)
    }

    /** Your Spotify developer app's Client ID: the same one as SpoX on Windows. */
    private fun askClientId(then: () -> Unit) {
        view.ask("Spotify Client ID", "Client ID", Web.clientId(),
            message = "The Client ID of your Spotify developer app (the same one SpoX on Windows uses). Its Redirect URIs must include ${Web.REDIRECT}.",
            ok = "Save") { v -> Web.setClientId(v); view.invalidate(); if (Web.clientId().isNotEmpty()) then() }
    }

    private fun askSearch() {
        view.ask("Search", "Song, artist, album, playlist", ok = "Search") { q -> q.trim().takeIf { it.isNotEmpty() }?.let { view.search(it) } }
    }

    // ---- links from other apps: songs people send you, "Share > SpoX" -----------------------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        val text = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        if (text.startsWith("spox://")) return  // back from signing in
        val m = Regex("(?:open\\.spotify\\.com/(?:intl-[a-z]+/)?|spotify:)(track|album|playlist|artist|episode|show)[/:]([A-Za-z0-9]+)").find(text)
            ?: run { view.say("That isn't a Spotify link"); return }
        val uri = "spotify:${m.groupValues[1]}:${m.groupValues[2]}"
        intent.action = null
        val eng = io.github.oatomh.spox.engine.Engine.get(this)
        if (!eng.isLive) {
            view.say("Turn on SpoX Engine (Settings) to play links")
            return
        }
        if (m.groupValues[1] == "track") {
            view.show(PlayerView.Sheet("Song from a link", emptyList(), listOf(
                PlayerView.SheetItem("Play now") { if (Smart.on) Smart.stop(); Player.playUri(uri); view.tab = PlayerView.Tab.PLAYER },
                PlayerView.SheetItem("Add to queue") { Player.addToQueue(uri); view.say("Added to queue") },
            )))
        } else {
            Player.playUri(uri)
            view.tab = PlayerView.Tab.PLAYER
        }
    }

    /** The Soloist key: same one as SpoX on Windows; it stays on this phone. */
    private fun askKey() {
        val eng = io.github.oatomh.spox.engine.Engine.get(this)
        view.ask("Your Soloist key", "Soloist API key", eng.prefs.getString("key", "").orEmpty(), password = true,
            message = "The same key as SpoX on Windows (from Spotify's Soloist page). It stays on this phone. After starting, pick \"SpoX Phone\" once in Spotify Connect on another device to sign in.",
            ok = "Save and start") { v ->
            val k = v.trim()
            if (k.length >= 6) {
                eng.prefs.edit().putString("key", k).apply()
                getSharedPreferences("spox", 0).edit().putBoolean("engine_on", true).apply()
                io.github.oatomh.spox.engine.EngineService.start(this)
            }
            view.postDelayed({ view.invalidate() }, 600)
        }
    }

    /** Volume buttons while SpoX is open: SpoX's own pixel volume instead of Android's slider. */
    override fun dispatchKeyEvent(e: android.view.KeyEvent): Boolean {
        val up = e.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
        if ((up || e.keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN) && getSharedPreferences("spox", 0).getBoolean("own_volume", true)) {
            if (e.action == android.view.KeyEvent.ACTION_DOWN) view.showVolume(Player.stepVolume(this, up))
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onResume() {
        super.onResume()
        // opened again after being swiped away: don't quit when the music pauses
        io.github.oatomh.spox.engine.EngineService.closed = false
        // coming back from the "Notification access" screen: start following the music app
        if (Player.hasAccess(this)) {
            runCatching { NotificationListenerService.requestRebind(ComponentName(this, MediaListener::class.java)) }
            Player.pick()
        }
        view.refresh()
    }
}
