package com.safechild.ai.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.safechild.ai.MainActivity
import com.safechild.ai.R
import com.safechild.ai.data.SafetyEventRepository

object SosNotificationManager {
    private const val CHANNEL_ID = "sos_emergency_alerts_v2"
    private const val CHANNEL_NAME = "Emergency SOS alerts"
    private const val TAG = "SosNotifications"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Audible alerts when a connected child sends an SOS."
            setSound(sound, audioAttributes)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 700, 250, 700, 250, 1000)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    fun showSos(context: Context, parentUid: String, event: SafetyEventRepository.Event) {
        val preferences = context.getSharedPreferences(SOS_NOTIFICATION_PREFS, Context.MODE_PRIVATE)
        val deliveredKey = "delivered_$parentUid"
        val deliveredEvents = preferences.getStringSet(deliveredKey, emptySet()).orEmpty()
        if (event.id in deliveredEvents) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
            Log.w(TAG, "Notifications are disabled; SOS remains open in the in-app alerts list.")
            return
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            event.id.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val publicNotification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_sos)
            .setContentTitle("Emergency SOS")
            .setContentText("A connected child needs help. Open SafeChild AI.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingIntent)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_sos)
            .setContentTitle("SOS from ${event.childName}")
            .setContentText("Your child needs help. Tap to view the alert.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${event.childName} sent an SOS. Open SafeChild AI to review the alert and location."))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicNotification)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(event.id.hashCode().takeIf { it != 0 } ?: 1, notification)
            preferences.edit().putStringSet(deliveredKey, deliveredEvents.toMutableSet().apply { add(event.id) }).apply()
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification permission is not granted; SOS remains saved in Firestore.", error)
        }
    }

    private const val SOS_NOTIFICATION_PREFS = "safechild_sos_notifications"
}
