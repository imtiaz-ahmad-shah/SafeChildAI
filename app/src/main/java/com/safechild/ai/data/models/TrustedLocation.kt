package com.safechild.ai.data.models

import com.google.firebase.Timestamp

data class TrustedLocation(
    val id: String = "",
    val name: String = "",
    val type: String = "other", // home, school, academy, other
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radiusMeters: Float = 150f,
    val enabled: Boolean = true,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
)
