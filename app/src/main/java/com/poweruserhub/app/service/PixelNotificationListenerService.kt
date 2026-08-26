package com.poweruserhub.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Notification source for the replacement shade.
 * The UI will observe [notifications] instead of depending on the stock SystemUI shade.
 */
class PixelNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        publishActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        publishActiveNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        publishActiveNotifications()
    }

    private fun publishActiveNotifications() {
        notificationsMutable.value = runCatching {
            activeNotifications?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    companion object {
        private val notificationsMutable = MutableStateFlow<List<StatusBarNotification>>(emptyList())
        val notifications: StateFlow<List<StatusBarNotification>> = notificationsMutable
    }
}
