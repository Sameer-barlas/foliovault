package com.example.services

import com.example.BuildConfig
import com.example.models.UserRole
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class BindDeviceRequest(
    val idToken: String,
    val deviceId: String,
    val deviceLabel: String,
)

@JsonClass(generateAdapter = true)
data class BindDeviceResponse(
    val customToken: String? = null,
    val role: String? = null,
    val email: String? = null,
    val error: String? = null,
    val message: String? = null,
    val boundDeviceLabel: String? = null,
)

@JsonClass(generateAdapter = true)
data class ResetDeviceRequest(
    val targetEmail: String,
    val reason: String,
)

@JsonClass(generateAdapter = true)
data class ResetDeviceResponse(
    val ok: Boolean? = null,
    val targetEmail: String? = null,
    val sessionVersion: Long? = null,
    val error: String? = null,
    val message: String? = null,
    val retryAfterSeconds: Long? = null,
)

interface FolioVaultBackendApi {
    @POST("api/bind-device")
    suspend fun bindDevice(
        @Header("X-Firebase-AppCheck") appCheckToken: String?,
        @Body body: BindDeviceRequest,
    ): Response<BindDeviceResponse>

    @POST("api/reset-device")
    suspend fun resetDevice(
        @Header("Authorization") bearerToken: String,
        @Header("X-Firebase-AppCheck") appCheckToken: String?,
        @Body body: ResetDeviceRequest,
    ): Response<ResetDeviceResponse>
}

sealed interface BindDeviceOutcome {
    data class Success(
        val customToken: String,
        val role: UserRole,
        val email: String,
    ) : BindDeviceOutcome

    data class NotAuthorized(val email: String) : BindDeviceOutcome
    data class DeviceLocked(val boundDeviceLabel: String) : BindDeviceOutcome
    data class Failure(val message: String) : BindDeviceOutcome
}

sealed interface ResetDeviceOutcome {
    data class Success(val targetEmail: String, val sessionVersion: Long) : ResetDeviceOutcome
    data class RateLimited(val message: String, val retryAfterSeconds: Long) : ResetDeviceOutcome
    data class Failure(val message: String) : ResetDeviceOutcome
}

/**
 * HTTPS client for the FolioVault Express backend.
 * Zero token logging: no OkHttp body/header logging interceptors are attached.
 */
class DeviceApiService(
    private val baseUrl: String = BuildConfig.API_BASE_URL,
) {
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val bindErrorAdapter = moshi.adapter(BindDeviceResponse::class.java)
    private val resetErrorAdapter = moshi.adapter(ResetDeviceResponse::class.java)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS) // Accommodates Render free cold starts
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean {
        val trimmed = baseUrl.trim()
        return trimmed.startsWith("https://") || (BuildConfig.DEBUG && trimmed.startsWith("http://"))
    }

    private fun buildApi(): FolioVaultBackendApi? {
        if (!isConfigured()) return null
        val normalized = baseUrl.trim().trimEnd('/') + "/"
        return Retrofit.Builder()
            .baseUrl(normalized)
            .client(httpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(FolioVaultBackendApi::class.java)
    }

    suspend fun bindDevice(
        idToken: String,
        deviceId: String,
        deviceLabel: String,
        appCheckToken: String?,
        fallbackEmail: String,
    ): BindDeviceOutcome = withContext(Dispatchers.IO) {
        val api = buildApi()
            ?: return@withContext BindDeviceOutcome.Failure(
                "API_BASE_URL is not configured with an HTTPS backend URL."
            )

        try {
            val response = api.bindDevice(
                appCheckToken = appCheckToken,
                body = BindDeviceRequest(
                    idToken = idToken,
                    deviceId = deviceId,
                    deviceLabel = deviceLabel,
                ),
            )
            if (response.isSuccessful) {
                val payload = response.body()
                val token = payload?.customToken
                if (!token.isNullOrBlank()) {
                    BindDeviceOutcome.Success(
                        customToken = token,
                        role = UserRole.fromWire(payload.role),
                        email = payload.email?.lowercase() ?: fallbackEmail.lowercase(),
                    )
                } else {
                    BindDeviceOutcome.Failure("Backend returned an empty session token.")
                }
            } else {
                val errorBody = response.errorBody()?.string()
                val parsed = errorBody?.let {
                    runCatching { bindErrorAdapter.fromJson(it) }.getOrNull()
                }
                when (response.code()) {
                    403 -> BindDeviceOutcome.NotAuthorized(
                        email = parsed?.email?.lowercase() ?: fallbackEmail.lowercase(),
                    )
                    409 -> BindDeviceOutcome.DeviceLocked(
                        boundDeviceLabel = parsed?.boundDeviceLabel ?: "another physical device",
                    )
                    else -> BindDeviceOutcome.Failure(
                        parsed?.message ?: "Device verification failed (HTTP ${response.code()})."
                    )
                }
            }
        } catch (e: Exception) {
            BindDeviceOutcome.Failure(
                "Could not reach FolioVault server. Check your connection and try again."
            )
        }
    }

    suspend fun resetDevice(
        firebaseIdToken: String,
        targetEmail: String,
        reason: String,
        appCheckToken: String?,
    ): ResetDeviceOutcome = withContext(Dispatchers.IO) {
        val api = buildApi()
            ?: return@withContext ResetDeviceOutcome.Failure(
                "API_BASE_URL is not configured with an HTTPS backend URL."
            )

        try {
            val response = api.resetDevice(
                bearerToken = "Bearer $firebaseIdToken",
                appCheckToken = appCheckToken,
                body = ResetDeviceRequest(
                    targetEmail = targetEmail.trim().lowercase(),
                    reason = reason.trim(),
                ),
            )
            if (response.isSuccessful) {
                val payload = response.body()
                ResetDeviceOutcome.Success(
                    targetEmail = payload?.targetEmail ?: targetEmail.trim().lowercase(),
                    sessionVersion = payload?.sessionVersion ?: 2L,
                )
            } else {
                val errorBody = response.errorBody()?.string()
                val parsed = errorBody?.let {
                    runCatching { resetErrorAdapter.fromJson(it) }.getOrNull()
                }
                when (response.code()) {
                    429 -> ResetDeviceOutcome.RateLimited(
                        message = parsed?.message ?: "Reset rate limit reached. Try again later.",
                        retryAfterSeconds = parsed?.retryAfterSeconds ?: 60L,
                    )
                    else -> ResetDeviceOutcome.Failure(
                        parsed?.message ?: "Device reset failed (HTTP ${response.code()})."
                    )
                }
            }
        } catch (e: Exception) {
            ResetDeviceOutcome.Failure("Network error while contacting the reset server.")
        }
    }
}
