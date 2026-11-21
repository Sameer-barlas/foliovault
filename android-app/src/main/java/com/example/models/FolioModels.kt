package com.example.models

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties

enum class UserRole(val wireValue: String) {
    ADMIN("admin"),
    READER("reader");

    companion object {
        fun fromWire(value: String?): UserRole =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: READER
    }
}

@Keep
@IgnoreExtraProperties
data class AuthorizedUser(
    @DocumentId val id: String = "",
    val email: String = "",
    val displayName: String = "",
    val role: String = UserRole.READER.wireValue,
    val boundDeviceId: String? = null,
    val boundDeviceLabel: String? = null,
    val boundUid: String? = null,
    val boundAt: Timestamp? = null,
    val sessionVersion: Long = 1L,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
) {
    fun parsedRole(): UserRole = UserRole.fromWire(role)
}

@Keep
@IgnoreExtraProperties
data class AccessGrant(
    @DocumentId val id: String = "",
    val email: String = "",
    val bookId: String = "",
    val grantedBy: String = "",
    val grantedAt: Timestamp? = null,
) {
    companion object {
        fun docId(email: String, bookId: String): String =
            "${email.trim().lowercase()}__${bookId.trim()}"
    }
}

@Keep
@IgnoreExtraProperties
data class Book(
    @DocumentId val id: String = "",
    val title: String = "",
    val author: String = "",
    val description: String = "",
    val category: String = "",
    val coverBytes: Blob? = null,
    val pageCount: Int = 0,
    val createdBy: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class BookPage(
    @DocumentId val id: String = "",
    val orderIndex: Double = 0.0,
    val imageBytes: Blob? = null,
    val width: Int = 0,
    val height: Int = 0,
    val caption: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class ResetAuditLog(
    @DocumentId val id: String = "",
    val targetEmail: String = "",
    val previousDeviceId: String? = null,
    val previousDeviceLabel: String? = null,
    val adminEmail: String = "",
    val adminUid: String = "",
    val reason: String = "",
    val resetAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class StorageStats(
    val totalImageBytes: Long = 0L,
    val updatedAt: Timestamp? = null,
) {
    companion object {
        const val SPARK_STORAGE_LIMIT_BYTES: Long = 1024L * 1024L * 1024L // 1 GiB
        const val WARNING_THRESHOLD_FRACTION: Float = 0.80f
    }

    fun usageFraction(): Float =
        (totalImageBytes.toFloat() / SPARK_STORAGE_LIMIT_BYTES.toFloat()).coerceIn(0f, 1f)

    fun warningActive(): Boolean = usageFraction() >= WARNING_THRESHOLD_FRACTION
}
