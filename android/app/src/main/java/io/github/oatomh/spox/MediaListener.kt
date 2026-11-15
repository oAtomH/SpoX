package io.github.oatomh.spox

import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.content.ComponentName

/**
 * Android only lets an app see other apps' players while it has "Notification access".
 * This service is that access: it keeps SpoX following the music app, and keeps SpoX's own
 * pixel notification + widget up to date.
 */
class MediaListener : NotificationListenerService() {
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { Player.pick() }

    override fun onListenerConnected() {
        Player.init(this)
        val msm = getSystemService(MediaSessionManager::class.java)
        runCatching { msm.addOnActiveSessionsChangedListener(sessions, ComponentName(this, MediaListener::class.java)) }
        Player.pick()
    }

    override fun onListenerDisconnected() {
        runCatching { getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessions) }
    }
}
