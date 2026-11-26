package io.github.oatomh.spox

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Launcher icon in the colours of the colour pack: one activity-alias per pack, switched when
 *  SpoX goes to the background (switching while it's open can close it on some launchers). */
object AppIcon {
    private val SLUGS = listOf("ambercrt", "ice", "rose", "lime", "mono", "gameboy", "vapor", "blood", "ocean", "sunset", "paper",
        "catppuccin", "catppuccinlatte", "dracula", "nord", "gruvbox", "gruvboxlight", "tokyonight", "solarized", "solarizedlight", "onedark", "rosepine", "everforest", "kanagawa", "monokai", "synthwave84", "ayu", "githubdark", "matrix")

    @Volatile var visible = false
    @Volatile private var pending = false
    private var watching = false

    /** Tries again when the music stops while SpoX is in the background. */
    fun watch(ctx: Context) {
        if (watching) return
        watching = true
        val app = ctx.applicationContext
        Player.listen { if (pending && !Player.state.playing && !visible) apply(app) }
    }

    fun follows(ctx: Context) = ctx.getSharedPreferences("spox", 0).getBoolean("icon_follows", false)

    fun setFollows(ctx: Context, on: Boolean) = ctx.getSharedPreferences("spox", 0).edit().putBoolean("icon_follows", on).apply()

    private fun slug(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    /** Enables the alias that should show and switches the others off. Does nothing if it's already right. */
    fun apply(ctx: Context) {
        val pm = ctx.packageManager
        val want = if (follows(ctx)) slug(Skin.packName(ctx)).takeIf { it in SLUGS } else null
        // once per change: if the launcher didn't take it, don't keep trying every time
        val prefs = ctx.getSharedPreferences("spox", 0)
        val tag = want ?: "default"
        if (prefs.getString("icon_applied", "default") == tag) return
        // Samsung restarts an app whose icon changes, which would stop the music: wait until
        // nothing plays and SpoX isn't on screen (see watch())
        if (Player.state.playing || visible) { pending = true; return }
        pending = false
        prefs.edit().putString("icon_applied", tag).apply()
        val names = listOf("IconDefault" to (want == null)) + SLUGS.map { "Icon_$it" to (it == want) }
        for ((n, on) in names.sortedBy { !it.second }) {
            val cn = ComponentName(ctx.packageName, "${ctx.packageName}.$n")
            val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val cur = runCatching { pm.getComponentEnabledSetting(cn) }.getOrNull() ?: continue
            val isOn = cur == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || (cur == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && n == "IconDefault")
            if (isOn == on) continue
            runCatching { pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP) }
        }
    }
}
