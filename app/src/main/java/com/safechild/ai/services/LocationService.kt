package com.safechild.ai.services

import android.R
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import com.safechild.ai.utils.LocationHelper

class LocationService : Service() {

    private lateinit var locationHelper: LocationHelper
    private var childUid: String? = null

    override fun onCreate() {
        super.onCreate()
        locationHelper = LocationHelper(this, FirebaseFirestore.getInstance())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        childUid = intent?.getStringExtra(EXTRA_CHILD_UID)
            ?: getSharedPreferences("safechild_location_sharing", MODE_PRIVATE)
                .takeIf { it.getBoolean("enabled", false) }
                ?.getString("child_uid", null)
        if (childUid != null) {
            startForegroundService()
            locationHelper.startLocationUpdates(childUid!!)
        } else {
            stopSelf()
        }

        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "location_tracking_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("SafeChild AI Tracking")
            .setContentText("Your location is being shared with your parent.")
            .setSmallIcon(R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        locationHelper.stopLocationUpdates()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "STOP_LOCATION_SERVICE"
        const val EXTRA_CHILD_UID = "CHILD_UID"
        private const val NOTIFICATION_ID = 12345
    }
}
