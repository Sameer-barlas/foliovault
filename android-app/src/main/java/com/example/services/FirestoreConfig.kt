package com.example.services

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import kotlinx.coroutines.tasks.await

/**
 * Centralized Firebase initialization for FolioVault:
 * - Single FIRESTORE_DATABASE_ID constant (defaults to "(default)").
 * - Strict MemoryCacheSettings so zero book/page content is written to disk.
 * - App Check in monitor mode (Debug provider in debug builds, Play Integrity in release).
 */
object FirestoreConfig {
    private const val TAG = "FirestoreConfig"

    const val FIRESTORE_DATABASE_ID: String = BuildConfig.FIRESTORE_DATABASE_ID
    const val COLLECTION_AUTHORIZED_USERS = "authorized_users"
    const val COLLECTION_ACCESS = "access"
    const val COLLECTION_BOOKS = "books"
    const val SUBCOLLECTION_PAGES = "pages"
    const val COLLECTION_RESET_AUDIT_LOGS = "reset_audit_logs"
    const val COLLECTION_APP_STATS = "app_stats"
    const val DOC_STORAGE_STATS = "storage"

    const val MAX_COVER_BYTES = 60 * 1024
    const val TARGET_PAGE_BYTES = 150 * 1024
    const val MAX_PAGE_BYTES = 300 * 1024
    const val TARGET_PAGE_WIDTH_PX = 1080
    const val PAGE_BATCH_SIZE = 12
    const val PREFETCH_THRESHOLD_FROM_END = 3

    @Volatile
    private var appCheckInitialized = false

    @Volatile
    private var cachedFirestore: FirebaseFirestore? = null

    fun isFirebaseConfigured(context: Context): Boolean {
        return try {
            FirebaseApp.getApps(context).isNotEmpty() || FirebaseApp.initializeApp(context) != null
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseApp is not configured yet: ${e.message}")
            false
        }
    }

    fun initializeAppCheck(context: Context) {
        if (appCheckInitialized) return
        if (!isFirebaseConfigured(context)) return
        try {
            val appCheck = FirebaseAppCheck.getInstance()
            val factory = if (BuildConfig.DEBUG) {
                DebugAppCheckProviderFactory.getInstance()
            } else {
                PlayIntegrityAppCheckProviderFactory.getInstance()
            }
            appCheck.installAppCheckProviderFactory(factory)
            appCheckInitialized = true
        } catch (e: Exception) {
            // Monitor mode: log and continue without crashing unprovisioned builds
            Log.w(TAG, "App Check initialization skipped: ${e.message}")
        }
    }

    suspend fun fetchAppCheckTokenOrNull(): String? {
        if (!appCheckInitialized) return null
        return try {
            val result = FirebaseAppCheck.getInstance().getAppCheckToken(false).await()
            result.token
        } catch (e: Exception) {
            Log.w(TAG, "App Check token unavailable (monitor mode): ${e.message}")
            null
        }
    }

    fun firestore(context: Context): FirebaseFirestore {
        cachedFirestore?.let { return it }
        synchronized(this) {
            cachedFirestore?.let { return it }
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            val app = FirebaseApp.getInstance()
            val instance = FirebaseFirestore.getInstance(app, FIRESTORE_DATABASE_ID)
            val memoryOnlySettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()
            try {
                instance.firestoreSettings = memoryOnlySettings
            } catch (e: IllegalStateException) {
                // Settings already applied on this instance
                Log.d(TAG, "Firestore settings already applied.")
            }
            cachedFirestore = instance
            return instance
        }
    }

    fun auth(context: Context): FirebaseAuth {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }
        return FirebaseAuth.getInstance()
    }
}
