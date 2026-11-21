package com.example.controllers

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.models.AuthorizedUser
import com.example.models.UserRole
import com.example.services.BindDeviceOutcome
import com.example.services.DeviceApiService
import com.example.services.DeviceBindingService
import com.example.services.FirestoreConfig
import com.example.services.GoogleSignInResult
import com.example.services.PageMemoryCache
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed interface AuthUiState {
    data object CheckingSession : AuthUiState

    data class Welcome(
        val loading: Boolean = false,
        val errorMessage: String? = null,
        val configMissing: Boolean = false,
    ) : AuthUiState

    data class NotAuthorized(
        val email: String,
        val reasonMessage: String? = null,
    ) : AuthUiState

    data class DeviceLocked(
        val email: String,
        val boundDeviceLabel: String,
        val wasRevoked: Boolean = false,
    ) : AuthUiState

    data class Authenticated(
        val uid: String,
        val email: String,
        val role: UserRole,
        val deviceId: String,
        val deviceLabel: String,
    ) : AuthUiState
}

/**
 * Coordinates Google Credential Manager sign-in, server-enforced device binding,
 * Firebase custom-token session restore, and the live revocation Firestore listener.
 */
class AuthController(
    application: Application,
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "AuthController"
    }

    private val appContext = application.applicationContext
    private val deviceBindingService = DeviceBindingService(appContext)
    private val deviceApiService = DeviceApiService()

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.CheckingSession)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private var revocationListener: ListenerRegistration? = null

    init {
        restoreExistingSession()
    }

    private fun isConfigMissing(): Boolean {
        val firebaseReady = FirestoreConfig.isFirebaseConfigured(appContext)
        val backendReady = deviceApiService.isConfigured()
        val clientIdReady = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()
        return !firebaseReady || !backendReady || !clientIdReady
    }

    private fun restoreExistingSession() {
        viewModelScope.launch {
            if (!FirestoreConfig.isFirebaseConfigured(appContext)) {
                _uiState.value = AuthUiState.Welcome(configMissing = true)
                return@launch
            }

            try {
                val auth = FirestoreConfig.auth(appContext)
                val currentUser = auth.currentUser
                if (currentUser == null) {
                    _uiState.value = AuthUiState.Welcome(configMissing = isConfigMissing())
                    return@launch
                }

                val tokenResult = currentUser.getIdToken(false).await()
                val claims = tokenResult.claims
                val claimedEmail = (claims["userEmail"] as? String)?.trim()?.lowercase()
                val claimedDeviceId = (claims["deviceId"] as? String)?.trim()
                val claimedRole = UserRole.fromWire(claims["role"] as? String)
                val localIdentity = deviceBindingService.resolveDeviceIdentity()

                if (claimedEmail.isNullOrBlank() ||
                    claimedDeviceId.isNullOrBlank() ||
                    claimedDeviceId != localIdentity.deviceId
                ) {
                    clearSessionAndSignOut()
                    _uiState.value = AuthUiState.Welcome(configMissing = isConfigMissing())
                    return@launch
                }

                val session = AuthUiState.Authenticated(
                    uid = currentUser.uid,
                    email = claimedEmail,
                    role = claimedRole,
                    deviceId = claimedDeviceId,
                    deviceLabel = localIdentity.deviceLabel,
                )
                _uiState.value = session
                attachLiveRevocationGuard(session)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Session restore failed: ${e.javaClass.simpleName}")
                clearSessionAndSignOut()
                _uiState.value = AuthUiState.Welcome(configMissing = isConfigMissing())
            }
        }
    }

    fun signInWithGoogle(activityContext: Context) {
        viewModelScope.launch {
            if (!FirestoreConfig.isFirebaseConfigured(appContext)) {
                _uiState.value = AuthUiState.Welcome(
                    loading = false,
                    errorMessage = "Authentication service is not configured yet. Please contact your administrator.",
                    configMissing = true,
                )
                return@launch
            }

            _uiState.value = AuthUiState.Welcome(
                loading = true,
                errorMessage = null,
                configMissing = isConfigMissing(),
            )

            when (val googleResult = deviceBindingService.requestGoogleIdToken(activityContext)) {
                is GoogleSignInResult.Cancelled -> {
                    _uiState.value = AuthUiState.Welcome(
                        loading = false,
                        errorMessage = null,
                        configMissing = isConfigMissing(),
                    )
                }

                is GoogleSignInResult.Error -> {
                    _uiState.value = AuthUiState.Welcome(
                        loading = false,
                        errorMessage = googleResult.message,
                        configMissing = isConfigMissing(),
                    )
                }

                is GoogleSignInResult.Success -> {
                    val identity = deviceBindingService.resolveDeviceIdentity()
                    val appCheckToken = FirestoreConfig.fetchAppCheckTokenOrNull()

                    when (
                        val bindOutcome = deviceApiService.bindDevice(
                            idToken = googleResult.idToken,
                            deviceId = identity.deviceId,
                            deviceLabel = identity.deviceLabel,
                            appCheckToken = appCheckToken,
                            fallbackEmail = googleResult.email,
                        )
                    ) {
                        is BindDeviceOutcome.NotAuthorized -> {
                            _uiState.value = AuthUiState.NotAuthorized(email = bindOutcome.email)
                        }

                        is BindDeviceOutcome.DeviceLocked -> {
                            _uiState.value = AuthUiState.DeviceLocked(
                                email = googleResult.email,
                                boundDeviceLabel = bindOutcome.boundDeviceLabel,
                                wasRevoked = false,
                            )
                        }

                        is BindDeviceOutcome.Failure -> {
                            _uiState.value = AuthUiState.Welcome(
                                loading = false,
                                errorMessage = bindOutcome.message,
                                configMissing = isConfigMissing(),
                            )
                        }

                        is BindDeviceOutcome.Success -> {
                            completeCustomTokenSignIn(
                                customToken = bindOutcome.customToken,
                                email = bindOutcome.email,
                                role = bindOutcome.role,
                                deviceId = identity.deviceId,
                                deviceLabel = identity.deviceLabel,
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun completeCustomTokenSignIn(
        customToken: String,
        email: String,
        role: UserRole,
        deviceId: String,
        deviceLabel: String,
    ) {
        try {
            val auth = FirestoreConfig.auth(appContext)
            val authResult = auth.signInWithCustomToken(customToken).await()
            val uid = authResult.user?.uid
            if (uid.isNullOrBlank()) {
                _uiState.value = AuthUiState.Welcome(
                    loading = false,
                    errorMessage = "Firebase custom-token sign-in returned no user.",
                    configMissing = isConfigMissing(),
                )
                return
            }

            val session = AuthUiState.Authenticated(
                uid = uid,
                email = email,
                role = role,
                deviceId = deviceId,
                deviceLabel = deviceLabel,
            )
            _uiState.value = session
            attachLiveRevocationGuard(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Custom token sign-in failed: ${e.javaClass.simpleName}")
            _uiState.value = AuthUiState.Welcome(
                loading = false,
                errorMessage = "Could not establish Firebase session. Please try again.",
                configMissing = isConfigMissing(),
            )
        }
    }

    /**
     * Live Revocation Guard (Spec Section 2.1 #5):
     * Listens to own authorized_users/{email}. If deleted, boundDeviceId changes/nulls,
     * or PERMISSION_DENIED fires, immediately wipes RAM cache, signs out, and routes to gate.
     */
    private fun attachLiveRevocationGuard(session: AuthUiState.Authenticated) {
        detachRevocationListener()
        val db = FirestoreConfig.firestore(appContext)
        val docRef = db.collection(FirestoreConfig.COLLECTION_AUTHORIZED_USERS)
            .document(session.email)

        revocationListener = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.w(TAG, "Revocation listener error (${error.code}): signing out.")
                handleRevocation(
                    email = session.email,
                    boundDeviceLabel = null,
                    accountRemoved = true,
                )
                return@addSnapshotListener
            }

            if (snapshot == null || !snapshot.exists()) {
                handleRevocation(
                    email = session.email,
                    boundDeviceLabel = null,
                    accountRemoved = true,
                )
                return@addSnapshotListener
            }

            val user = snapshot.toObject(
                AuthorizedUser::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE,
            )
            val currentBoundId = user?.boundDeviceId
            if (currentBoundId.isNullOrBlank() || currentBoundId != session.deviceId) {
                handleRevocation(
                    email = session.email,
                    boundDeviceLabel = user?.boundDeviceLabel,
                    accountRemoved = false,
                )
                return@addSnapshotListener
            }

            val updatedRole = user.parsedRole()
            val current = _uiState.value
            if (current is AuthUiState.Authenticated && current.role != updatedRole) {
                _uiState.value = current.copy(role = updatedRole)
            }
        }
    }

    private fun handleRevocation(
        email: String,
        boundDeviceLabel: String?,
        accountRemoved: Boolean,
    ) {
        viewModelScope.launch {
            clearSessionAndSignOut()
            _uiState.value = if (accountRemoved) {
                AuthUiState.NotAuthorized(
                    email = email,
                    reasonMessage = "Your access was revoked by an administrator.",
                )
            } else {
                AuthUiState.DeviceLocked(
                    email = email,
                    boundDeviceLabel = boundDeviceLabel ?: "reset by administrator",
                    wasRevoked = true,
                )
            }
        }
    }

    fun returnToWelcome() {
        viewModelScope.launch {
            clearSessionAndSignOut()
            _uiState.value = AuthUiState.Welcome(configMissing = isConfigMissing())
        }
    }

    fun signOut() {
        viewModelScope.launch {
            clearSessionAndSignOut()
            _uiState.value = AuthUiState.Welcome(configMissing = isConfigMissing())
        }
    }

    private suspend fun clearSessionAndSignOut() {
        withContext(NonCancellable) {
            detachRevocationListener()
            PageMemoryCache.clear()
            if (FirestoreConfig.isFirebaseConfigured(appContext)) {
                runCatching { FirestoreConfig.auth(appContext).signOut() }
            }
            deviceBindingService.clearCredentialState()
        }
    }

    private fun detachRevocationListener() {
        revocationListener?.remove()
        revocationListener = null
    }

    override fun onCleared() {
        detachRevocationListener()
        PageMemoryCache.clear()
        super.onCleared()
    }
}
