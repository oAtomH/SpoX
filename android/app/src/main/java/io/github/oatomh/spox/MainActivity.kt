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
                if (!view.back()) finish()
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
                "#Spotify account (library, search, likes)" to "",
                "Account" to if (Web.signedIn) Web.userName().ifEmpty { "signed in" } else Web.status.ifEmpty { "sign in..." },
                "Client ID" to if (Web.clientId().isEmpty()) "add..." else "set (change...)",
                "#Look" to "",
                "Colour pack" to Skin.packName(this),
                "Player in notifications" to when (SpoxNotification.style(this)) { "pixel" -> "pixel card"; "media" -> "phone's player"; else -> "off" },
                "Lock screen art" to when (SpoxNotification.lockArt(this)) { "cover" -> "cover"; "card" -> "SpoX card"; else -> "pixel cover" },
                "Smoothness" to when (prefs.getInt("fps", 30)) { 15 -> "battery (15 fps)"; 60 -> "smooth (60 fps)"; else -> "normal (30 fps)" },
                "#Listening" to "",
                "Sleep timer" to Player.sleepLabel(),
                "Smart shuffle: new songs" to "${Smart.ratio(this)}%",
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
                "Engine report" -> {
                    val eng = io.github.oatomh.spox.engine.Engine.get(this)
                    getSystemService(android.content.ClipboardManager::class.java)
                        .setPrimaryClip(android.content.ClipData.newPlainText("SpoX Engine report", eng.reportText()))
                    android.widget.Toast.makeText(this, "Engine report copied", android.widget.Toast.LENGTH_SHORT).show()
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
                    android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Spotify account").setMessage("Signed in as ${Web.userName()}.")
                        .setPositiveButton("Sign out") { _, _ -> Web.signOut() }.setNegativeButton("Keep", null).show()
                } else signIn()
                "Client ID" -> askClientId { }
                "Sleep timer" -> Player.cycleSleep()
                "Smart shuffle: new songs" -> Smart.cycleRatio(this)
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
        if (!view.back()) @Suppress("DEPRECATION") super.onBackPressed()
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
        kotlin.concurrent.thread {
            val list = Web.devices()
            runOnUiThread {
                if (list.isEmpty()) {
                    android.widget.Toast.makeText(this, "No devices: open Spotify (or SpoX) on the other device", android.widget.Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val names = list.map { (if (it.active) "▶ " else "") + it.name + "  (" + it.type.lowercase() + ")" }.toTypedArray()
                android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Play on...")
                    .setItems(names) { _, i ->
                        kotlin.concurrent.thread {
                            val ok = Web.transfer(list[i].id)
                            runOnUiThread { android.widget.Toast.makeText(this, if (ok) "Playing on ${list[i].name}" else "Couldn't move it to ${list[i].name}", android.widget.Toast.LENGTH_SHORT).show() }
                        }
                    }.show()
            }
        }
    }

    private fun signIn() {
        if (Web.clientId().isEmpty()) askClientId { Web.signIn(this) } else Web.signIn(this)
    }

    /** Your Spotify developer app's Client ID: the same one as SpoX on Windows. */
    private fun askClientId(then: () -> Unit) {
        val box = android.widget.EditText(this).apply { hint = "Client ID"; setText(Web.clientId()) }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Spotify Client ID")
            .setMessage("The Client ID of your Spotify developer app (the same one SpoX on Windows uses). " +
                "Its Redirect URIs must include ${Web.REDIRECT} (already there if Windows SpoX signs in).")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> Web.setClientId(box.text.toString()); view.invalidate(); if (Web.clientId().isNotEmpty()) then() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun askSearch() {
        val box = android.widget.EditText(this).apply { hint = "Song, artist, album, playlist"; isSingleLine = true }
        val d = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Search")
            .setView(box)
            .setPositiveButton("Search") { _, _ -> box.text.toString().trim().takeIf { it.isNotEmpty() }?.let { view.search(it) } }
            .setNegativeButton("Cancel", null)
            .create()
        box.setOnEditorActionListener { _, _, _ -> d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick(); true }
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
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
            ?: run { android.widget.Toast.makeText(this, "That isn't a Spotify link", android.widget.Toast.LENGTH_SHORT).show(); return }
        val uri = "spotify:${m.groupValues[1]}:${m.groupValues[2]}"
        intent.action = null
        val eng = io.github.oatomh.spox.engine.Engine.get(this)
        if (!eng.isLive) {
            android.widget.Toast.makeText(this, "Turn on SpoX Engine (Settings) to play links", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        if (m.groupValues[1] == "track") {
            android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Song from a link")
                .setItems(arrayOf("Play now", "Add to queue")) { _, which ->
                    if (which == 0) { Player.playUri(uri); view.tab = PlayerView.Tab.PLAYER } else Player.addToQueue(uri)
                }.show()
        } else {
            Player.playUri(uri)
            view.tab = PlayerView.Tab.PLAYER
        }
    }

    /** The Soloist key: same one as SpoX on Windows; it stays on this phone. */
    private fun askKey() {
        val eng = io.github.oatomh.spox.engine.Engine.get(this)
        val box = android.widget.EditText(this).apply {
            hint = "Soloist API key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(eng.prefs.getString("key", ""))
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Your Soloist key")
            .setMessage("The same key as SpoX on Windows (from Spotify's Soloist page). It stays on this phone.\n\nAfter starting, pick \"SpoX Phone\" once in Spotify Connect on another device to sign in.")
            .setView(box)
            .setPositiveButton("Save and start") { _, _ ->
                val k = box.text.toString().trim()
                if (k.length >= 6) {
                    eng.prefs.edit().putString("key", k).apply()
                    getSharedPreferences("spox", 0).edit().putBoolean("engine_on", true).apply()
                    io.github.oatomh.spox.engine.EngineService.start(this)
                }
                view.postDelayed({ view.invalidate() }, 600)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // coming back from the "Notification access" screen: start following the music app
        if (Player.hasAccess(this)) {
            runCatching { NotificationListenerService.requestRebind(ComponentName(this, MediaListener::class.java)) }
            Player.pick()
        }
        view.refresh()
    }
}
