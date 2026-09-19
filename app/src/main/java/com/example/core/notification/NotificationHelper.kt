package com.example.core.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity

/**
 * Transparent notification manager for the Family Supervision Foreground Service.
 * Ensures the device user is explicitly and continuously aware that wellbeing synchronization is active.
 */
class NotificationHelper(private val context: Context) {

    private val notificationManager: NotificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Family Wellbeing Supervision",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies that family supervision & device wellbeing sync is active."
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds the mandatory ongoing notification for the child device foreground service.
     */
    fun buildForegroundNotification(
        parentName: String = "Parent Account",
        lastSyncFormatted: String = "Just now"
    ): Notification {
        // Tap intent opens MainActivity directly to the Child Mode transparency screen
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_NAVIGATE_TO", "child_transparency")
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop supervision action intent
        val stopIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_ACTION", "REVOKE_SUPERVISION")
        }
        val stopPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Family supervision is active")
            .setContentText("Device wellbeing synchronization is running ($lastSyncFormatted)")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Family supervision is active and linked to $parentName. Approved wellbeing metrics are periodically synchronized. Tap to view collected data or manage permissions.")
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Supervision Settings",
                stopPendingIntent
            )
            .build()
    }

    fun updateForegroundNotification(parentName: String, lastSyncFormatted: String) {
        val notification = buildForegroundNotification(parentName, lastSyncFormatted)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "family_supervision_active_channel"
        const val NOTIFICATION_ID = 44001
        private const val REQUEST_CODE_OPEN = 101
        private const val REQUEST_CODE_STOP = 102
    }
}
