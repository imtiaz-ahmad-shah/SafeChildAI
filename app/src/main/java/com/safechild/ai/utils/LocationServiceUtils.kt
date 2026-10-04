package com.safechild.ai.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import com.safechild.ai.services.LocationService

private const val LOCATION_PREFS = "safechild_location_sharing"
private const val KEY_ENABLED = "enabled"
private const val KEY_CHILD_UID = "child_uid"

fun isLocationSharingEnabled(context: Context, childUid: String): Boolean {
    val prefs = context.getSharedPreferences(LOCATION_PREFS, Context.MODE_PRIVATE)
    return prefs.getBoolean(KEY_ENABLED, false) && prefs.getString(KEY_CHILD_UID, null) == childUid
}

fun startLocationService(context: Context, childUid: String) {
    val intent = Intent(context, LocationService::class.java).apply {
        putExtra(LocationService.EXTRA_CHILD_UID, childUid)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
    context.getSharedPreferences(LOCATION_PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_ENABLED, true)
        .putString(KEY_CHILD_UID, childUid)
        .apply()
}

fun stopLocationService(context: Context) {
    val intent = Intent(context, LocationService::class.java).apply {
        action = LocationService.ACTION_STOP
    }
    context.startService(intent)
    context.getSharedPreferences(LOCATION_PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_ENABLED, false)
        .remove(KEY_CHILD_UID)
        .apply()
}
