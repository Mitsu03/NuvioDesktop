package com.nuvio.app.features.simkl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class SimklDeviceRequest(
    @SerialName("client_id") val clientId: String,
    val scope: String,
)

@Serializable
internal data class SimklDeviceResponse(
    @SerialName("device_code") val deviceCode: String? = null,
    @SerialName("user_code") val userCode: String? = null,
    @SerialName("verification_uri") val verificationUri: String? = null,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    val interval: Int? = null,
)

@Serializable
internal data class SimklDeviceTokenRequest(
    @SerialName("client_id") val clientId: String,
    @SerialName("device_code") val deviceCode: String,
    @SerialName("grant_type") val grantType: String = "urn:ietf:params:oauth:grant-type:device_code",
)

internal data class SimklPendingPinAuthorization(
    val userCode: String,
    val deviceCode: String,
    val verificationUrl: String,
    val intervalSeconds: Int,
    val expiresAtEpochMs: Long,
)

internal sealed interface SimklPinPollResult {
    data class Authorized(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSeconds: Long?,
    ) : SimklPinPollResult
    data object Pending : SimklPinPollResult
    data class SlowDown(val addSeconds: Int = 5) : SimklPinPollResult
    data object Expired : SimklPinPollResult
    data object Failed : SimklPinPollResult
}

internal fun SimklDeviceResponse.toPendingAuthorization(
    nowEpochMs: Long,
): SimklPendingPinAuthorization? {
    val code = userCode?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val device = deviceCode?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val url = verificationUriComplete?.trim()?.takeIf(String::isNotEmpty)
        ?: verificationUri?.trim()?.takeIf(String::isNotEmpty)
        ?: return null
    val lifetimeSeconds = expiresIn?.coerceAtLeast(1L) ?: 900L
    return SimklPendingPinAuthorization(
        userCode = code,
        deviceCode = device,
        verificationUrl = url,
        intervalSeconds = interval?.coerceAtLeast(1) ?: 5,
        expiresAtEpochMs = nowEpochMs + lifetimeSeconds * 1_000L,
    )
}

internal fun isSimklPinAuthorizationExpired(
    expiresAtEpochMs: Long?,
    nowEpochMs: Long,
): Boolean = expiresAtEpochMs == null || nowEpochMs >= expiresAtEpochMs
