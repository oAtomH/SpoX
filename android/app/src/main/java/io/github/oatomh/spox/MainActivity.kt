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
        setContentView(view)

        view.settingsRows = {
            val prefs = getSharedPreferences("spox", 0)
            listOf(
                "Notification" to when (SpoxNotification.style(this)) { "pixel" -> "pixel card"; "media" -> "media player"; else -> "off" },
                "Updates" to Updater.status.ifEmpty { "check now" },
                "Auto-update" to if (prefs.getBoolean("auto_update", true)) "on" else "off",
                "Notification access" to if (Player.hasAccess(this)) "allowed" else "allow...",
                "Hide Spotify's own player" to "open...",
                "Open Spotify" to "",
                "Get SpoX for Windows" to "github",
                "Version" to (packageManager.getPackageInfo(packageName, 0).versionName ?: "?"),
            )
        }
        view.onSettingsAction = { what ->
            val prefs = getSharedPreferences("spox", 0)
            when (what) {
                "access", "Notification access" -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                "Notification" -> SpoxNotification.cycleStyle(this)
                "Updates" -> Updater.check(this, true) { runOnUiThread { view.invalidate() } }
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
        if (getSharedPreferences("spox", 0).getBoolean("auto_update", true)) {
            Updater.check(this, false) { runOnUiThread { view.invalidate() } }
        }
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
