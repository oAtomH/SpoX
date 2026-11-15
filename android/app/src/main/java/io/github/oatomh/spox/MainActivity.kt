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
        view = PlayerView(this)
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

        view.settingsRows = {
            val prefs = getSharedPreferences("spox", 0)
            val eng = io.github.oatomh.spox.engine.Engine.get(this)
            val hasKey = eng.prefs.getString("key", "").orEmpty().length >= 6
            listOf(
                "#SpoX Engine (Spotify without the Spotify app)" to "",
                "Engine" to when { !hasKey -> "add your key"; Player.fromEngine -> "on, playing"; eng.isLive -> "on"; else -> "off" },
                "Soloist key" to if (hasKey) "set (change...)" else "add...",
                "Engine report" to "copy",
                "#Look" to "",
                "Colour pack" to Skin.packName(this),
                "Player in notifications" to when (SpoxNotification.style(this)) { "pixel" -> "pixel card"; "media" -> "phone's player"; else -> "off" },
                "Pixel cover on lock screen" to if (SpoxNotification.pixelCover(this)) "on" else "off",
                "Smoothness" to when (prefs.getInt("fps", 30)) { 15 -> "battery (15 fps)"; 60 -> "smooth (60 fps)"; else -> "normal (30 fps)" },
                "#Listening" to "",
                "Sleep timer" to Player.sleepLabel(),
                "#Spotify app" to "",
                "Notification access" to if (Player.hasAccess(this)) "allowed" else "allow...",
                "Hide Spotify's own player" to "open...",
                "Open Spotify" to "",
                "#SpoX" to "",
                "Updates" to Updater.status.ifEmpty { "check now" },
                "Auto-update" to if (prefs.getBoolean("auto_update", true)) "on" else "off",
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
                "Pixel cover on lock screen" -> { prefs.edit().putBoolean("pixel_cover", !SpoxNotification.pixelCover(this)).apply(); SpoxNotification.update(this) }
                "Sleep timer" -> Player.cycleSleep()
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
