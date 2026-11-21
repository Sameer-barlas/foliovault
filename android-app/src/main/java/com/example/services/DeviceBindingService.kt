package com.example.services

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.example.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.util.Locale

data class DeviceIdentity(
    val deviceId: String,
    val deviceLabel: String,
)

sealed interface GoogleSignInResult {
    data class Success(val idToken: String, val email: String) : GoogleSignInResult
    data object Cancelled : GoogleSignInResult
    data class Error(val message: String) : GoogleSignInResult
}

/**
 * Resolves hardware device identity and runs Jetpack Credential Manager Google Sign-In
 * using a single GetSignInWithGoogleOption per GetCredentialRequest.
 */
class DeviceBindingService(
    private val appContext: Context,
) {
    companion object {
        private const val TAG = "Auth"
    }

    @SuppressLint("HardwareIds")
    fun resolveDeviceIdentity(): DeviceIdentity {
        val rawAndroidId = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID,
        )?.trim().orEmpty()

        val stableId = if (rawAndroidId.length >= 4) {
            rawAndroidId
        } else {
            "fallback-${Build.BOARD}-${Build.MODEL}".replace("\\s+".toRegex(), "_")
        }

        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val label = when {
            model.isBlank() && manufacturer.isBlank() -> "Android Device"
            model.startsWith(manufacturer, ignoreCase = true) -> model
            manufacturer.isBlank() -> model
            else -> "${manufacturer.replaceFirstChar { it.titlecase(Locale.US) }} $model"
        }.take(100)

        return DeviceIdentity(deviceId = stableId, deviceLabel = label)
    }

    suspend fun requestGoogleIdToken(activityContext: Context): GoogleSignInResult {
        val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
        if (webClientId.isEmpty()) {
            return GoogleSignInResult.Error(
                "Sign-in service is not configured. Please contact your administrator."
            )
        }

        val option = GetSignInWithGoogleOption.Builder(webClientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        val credentialManager = CredentialManager.create(activityContext)
        return try {
            val response = credentialManager.getCredential(
                context = activityContext,
                request = request,
            )
            val credential = response.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
                GoogleSignInResult.Success(
                    idToken = googleCred.idToken,
                    email = googleCred.id.trim().lowercase(Locale.US),
                )
            } else {
                GoogleSignInResult.Error("Unexpected credential returned by Google Sign-In.")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google Sign-In cancelled by user.")
            GoogleSignInResult.Cancelled
        } catch (e: GetCredentialException) {
            Log.w(TAG, "Google Sign-In failed: ${e.type}")
            GoogleSignInResult.Error(e.errorMessage?.toString() ?: "Google Sign-In failed.")
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected sign-in error: ${e.javaClass.simpleName}")
            GoogleSignInResult.Error(e.message ?: "Unable to complete Google Sign-In.")
        }
    }

    suspend fun clearCredentialState() {
        try {
            CredentialManager.create(appContext)
                .clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w(TAG, "Credential state clear skipped: ${e.javaClass.simpleName}")
        }
    }
}
