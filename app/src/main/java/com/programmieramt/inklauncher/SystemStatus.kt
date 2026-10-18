package com.programmieramt.inklauncher

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BatteryState(val percent: Int, val charging: Boolean)

/** Reads the sticky battery broadcast; cheap enough to call on every clock tick. */
fun readBattery(context: Context): BatteryState? {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return BatteryState(level * 100 / scale, charging)
}

/**
 * Counts the notifications worth looking at for the status line.
 * Only runs once the user granted notification access ("Benachrichtigungszugriff").
 */
class NotificationCounter : NotificationListenerService() {

    override fun onListenerConnected() = publish()

    override fun onListenerDisconnected() {
        _count.value = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = publish()

    override fun onNotificationRemoved(sbn: StatusBarNotification) = publish()

    private fun publish() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        _count.value = active.count { it.isMessageLike() }
    }

    companion object {
        private val _count = MutableStateFlow<Int?>(null)

        /** Null while the listener is not connected (no access granted yet). */
        val count: StateFlow<Int?> = _count.asStateFlow()

        fun hasAccess(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
}

/** Status notifications that aren't news: background services, sticky entries, group summaries. */
private const val NonMessageFlags = Notification.FLAG_FOREGROUND_SERVICE or
    Notification.FLAG_NO_CLEAR or
    Notification.FLAG_GROUP_SUMMARY

/** Same filter as the old launcher's notification previews (see ../Launcher NotificationListener). */
private fun StatusBarNotification.isMessageLike(): Boolean =
    !isOngoing &&
        notification.flags and NonMessageFlags == 0 &&
        !notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) &&
        notification.category != Notification.CATEGORY_TRANSPORT &&
        notification.category != Notification.CATEGORY_SERVICE &&
        notification.category != Notification.CATEGORY_PROGRESS
