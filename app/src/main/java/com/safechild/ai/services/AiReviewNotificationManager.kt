package com.safechild.ai.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.safechild.ai.MainActivity
import com.safechild.ai.data.RoutineDemoModel

object AiReviewNotificationManager {
    const val EXTRA_CHILD_UID = "ai_review_child_uid"
    private const val CHANNEL_ID = "ai_routine_review_v1"
    private const val TAG = "AiReviewNotifications"
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "AI routine review", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Non-emergency reminders when a child’s location differs from the learned routine."
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), null)
            enableVibration(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }
    fun show(context: Context, childUid: String, childName: String, assessment: RoutineDemoModel.Assessment) {
        val intent = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CHILD_UID, childUid)
        }
        val id = childUid.hashCode().takeIf { it != 0 } ?: 2
        val pendingIntent = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Routine review for $childName")
            .setContentText(assessment.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(assessment.explanation))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true).setContentIntent(pendingIntent).build()
        try { NotificationManagerCompat.from(context).notify(id, notification) }
        catch (error: SecurityException) { Log.w(TAG, "Notification permission not granted; review remains saved locally.", error) }
    }
}
