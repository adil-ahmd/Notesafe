package com.example.notesafe

import com.google.firebase.firestore.FirebaseFirestore
import android.location.Location
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

object FirestoreManager {
    private val db = FirebaseFirestore.getInstance()
    private const val COLLECTION_NAME = "fakeCurrencyRecords"

    data class FakeCurrencyRecord(
        val userId: String = "",
        val denomination: String = "",
        val isFake: Boolean = false,
        val confidenceScore: Float = 0f,
        @ServerTimestamp val timestamp: Date? = null,
        val latitude: Double? = null, // Store latitude
        val longitude: Double? = null, // Store longitude @ServerTimestamp
    ) {
        // Secondary constructor to create a record from a Location object
        constructor(
            userId: String,
            denomination: String,
            isFake: Boolean,
            confidenceScore: Float,
            location: Location? // Accept a nullable Location object
        ) : this(
            userId = userId,
            denomination = denomination,
            isFake = isFake,
            confidenceScore = confidenceScore,
            latitude = location?.latitude, // Extract latitude
            longitude = location?.longitude // Extract longitude
        )
    }

    fun saveDetectionResult(record: FakeCurrencyRecord, onComplete: (Boolean) -> Unit) {
        db.collection(COLLECTION_NAME)
            .add(record)
            .addOnSuccessListener {
                onComplete(true)
                android.util.Log.d("FirestoreManager", "Record saved successfully with ID: ${it.id}")
            }
            .addOnFailureListener { e ->
                android.util.Log.e("FirestoreManager", "Error saving record", e)
                onComplete(false)
            }
    }
}