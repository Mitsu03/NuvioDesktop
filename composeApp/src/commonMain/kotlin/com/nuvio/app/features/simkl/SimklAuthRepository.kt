package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.isDesktop
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SimklAuthRepository : TrackingAuthProvider {
    private val log = Logger.withTag("SimklAuth")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val authorizationMutex = Mutex()
    private val refreshMutex = Mutex()

    private val _uiState = MutableStateFlow(SimklAuthUiState())
    val uiState: StateFlow<SimklAuthUiState> = _uiState.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.SIMKL,
        displayName = "Simkl",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE,
        ),
    )

    private var hasLoaded = false
    private var profileGeneration = 0L
    private var storedState = SimklStoredAuthState()
    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var pinPollingJob: Job? = null

    init {
        TrackingProviderRegistry.register(this)
    }

    override fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    override fun onProfileChanged() {
        loadFromDisk()
    }

    override fun clearLocalState() {
        pinPollingJob?.cancel()
        hasLoaded = false
        profileGeneration += 1L
        storedState = SimklStoredAuthState()
        accessToken = null
        refreshToken = null
        publish()
    }

    override fun removeStoredProfile(profileId: Int) {
        SimklAuthStorage.removeProfile(profileId)
    }

    fun snapshot(): SimklAuthUiState {
        ensureLoaded()
        return uiState.value
    }

    fun hasRequiredCredentials(): Boolean = SimklConfig.CLIENT_ID.isNotBlank()

    fun onConnectRequested(): String? {
        ensureLoaded()
        if (!hasRequiredCredentials()) {
            publish(error = SimklAuthError.MISSING_CLIENT_ID)
            return null
        }

        if (isDesktop) {
            return startPinAuthorization()
        }

        val material = generateSimklPkceMaterial()
        SimklAuthStorage.saveCodeVerifier(material.verifier)
        storedState = storedState.copy(
            pendingAuthorizationState = material.state,
            pendingAuthorizationStartedAtEpochMs = SimklPlatformClock.nowEpochMs(),
        )
        persistMetadata()
        publish(error = null)
        return authorizationUrl(material)
    }

    fun pendingAuthorizationUrl(): String? {
        ensureLoaded()
        if (isDesktop) {
            val verificationUrl = storedState.pendingPinVerificationUrl
                ?.takeIf { storedState.hasPendingPinAuthorization }
            if (verificationUrl == null) return null
            if (isSimklPinAuthorizationExpired(
                    expiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
                    nowEpochMs = SimklPlatformClock.nowEpochMs(),
                )
            ) {
                pinPollingJob?.cancel()
                clearPendingAuthorization()
                persistMetadata()
                publish(error = SimklAuthError.AUTHORIZATION_EXPIRED)
                return null
            }
            startPinPollingIfNeeded()
            return verificationUrl
        }
        val state = storedState.pendingAuthorizationState?.takeIf(String::isNotBlank) ?: return null
        val verifier = SimklAuthStorage.loadCodeVerifier()?.takeIf(String::isNotBlank) ?: run {
            clearPendingAuthorization()
            persistMetadata()
            publish(error = SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        if (isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
        ) {
            clearPendingAuthorization()
            persistMetadata()
            publish(error = SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        return authorizationUrl(
            SimklPkceMaterial(
                verifier = verifier,
                challenge = SimklPkceCrypto.sha256(verifier.encodeToByteArray()).base64UrlWithoutPadding(),
                state = state,
            ),
        )
    }

    fun onCancelAuthorization() {
        ensureLoaded()
        pinPollingJob?.cancel()
        profileGeneration += 1L
        clearPendingAuthorization()
        persistMetadata()
        publish(error = null)
    }

    override fun handleAuthCallback(url: String): Boolean {
        ensureLoaded()
        if (isDesktop) return false
        return when (val callback = parseSimklAuthCallback(url, SimklConfig.REDIRECT_URI)) {
            SimklAuthCallback.NotSimkl -> false
            SimklAuthCallback.Invalid -> {
                clearPendingAuthorization()
                persistMetadata()
                publish(error = SimklAuthError.INVALID_CALLBACK)
                true
            }
            is SimklAuthCallback.AuthorizationCode -> {
                scope.launch { completeAuthorization(callback) }
                true
            }
        }
    }

    fun onDisconnectRequested() {
        ensureLoaded()
        pinPollingJob?.cancel()
        profileGeneration += 1L
        accessToken = null
        refreshToken = null
        SimklAuthStorage.saveAccessToken(null)
        SimklAuthStorage.saveRefreshToken(null)
        clearPendingAuthorization()
        storedState = SimklStoredAuthState()
        persistMetadata()
        SimklSyncRepository.clearLocalState()
        publish(error = null)
    }

    internal suspend fun authorizedAccessToken(): String? {
        ensureLoaded()
        val token = accessToken?.takeIf(String::isNotBlank) ?: return null
        val expiresAt = storedState.tokenExpiresAtEpochMs
        if (expiresAt == null || SimklPlatformClock.nowEpochMs() < expiresAt - TOKEN_EXPIRY_SKEW_MS) {
            return token
        }
        return refreshMutex.withLock {
            val current = accessToken?.takeIf(String::isNotBlank)
            val currentExpiresAt = storedState.tokenExpiresAtEpochMs
            if (current != null &&
                (currentExpiresAt == null || SimklPlatformClock.nowEpochMs() < currentExpiresAt - TOKEN_EXPIRY_SKEW_MS)
            ) {
                return@withLock current
            }
            val pendingRefreshToken = refreshToken?.takeIf(String::isNotBlank)
            if (pendingRefreshToken == null) {
                invalidateCredentials(SimklAuthError.AUTHORIZATION_EXPIRED)
                return@withLock null
            }
            refreshAccessToken(pendingRefreshToken)
        }
    }

    private suspend fun refreshAccessToken(currentRefreshToken: String): String? {
        val request = SimklRefreshTokenRequest(
            refreshToken = currentRefreshToken,
            clientId = SimklConfig.CLIENT_ID,
        )
        val response = try {
            httpRequestRaw(
                method = "POST",
                url = buildSimklApiUrl(SIMKL_TOKEN_PATH),
                headers = simklRequestHeaders(contentTypeJson = true),
                body = json.encodeToString(request),
                maxResponseBodyBytes = SIMKL_TOKEN_RESPONSE_MAX_BYTES,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Simkl token refresh failed: ${error.message}" }
            invalidateCredentials(SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        if (response.status !in 200..299) {
            log.w { "Simkl token refresh rejected: status=${response.status}" }
            invalidateCredentials(SimklAuthError.AUTHORIZATION_REVOKED)
            return null
        }
        val token = runCatching { json.decodeFromString<SimklTokenResponse>(response.body) }
            .getOrNull()
            ?.takeIf { it.accessToken.isNotBlank() }
        if (token == null) {
            invalidateCredentials(SimklAuthError.INVALID_TOKEN_RESPONSE)
            return null
        }
        accessToken = token.accessToken
        refreshToken = token.refreshToken?.takeIf(String::isNotBlank) ?: currentRefreshToken
        SimklAuthStorage.saveAccessToken(accessToken)
        SimklAuthStorage.saveRefreshToken(refreshToken)
        val now = SimklPlatformClock.nowEpochMs()
        storedState = storedState.copy(
            tokenExpiresAtEpochMs = token.expiresIn
                ?.takeIf { seconds -> seconds > 0L }
                ?.let { seconds -> now + seconds * 1_000L },
            refreshTokenExpiresAtEpochMs = now + REFRESH_TOKEN_LIFETIME_MS,
        )
        persistMetadata()
        publish(error = null)
        return accessToken
    }

    internal fun onUnauthorizedResponse() {
        invalidateCredentials(SimklAuthError.AUTHORIZATION_REVOKED)
    }

    suspend fun refreshUserSettings(): String? {
        authorizedAccessToken() ?: return null
        return if (fetchAndStoreUserSettings()) storedState.username else null
    }

    internal suspend fun synchronizeUserSettings(activityWatermark: String?) {
        authorizedAccessToken() ?: return
        when (simklSettingsRefreshAction(storedState, activityWatermark)) {
            SimklSettingsRefreshAction.NONE -> Unit
            SimklSettingsRefreshAction.RECORD_WATERMARK -> {
                storedState = storedState.copy(settingsActivityWatermark = activityWatermark)
                persistMetadata()
            }
            SimklSettingsRefreshAction.FETCH -> {
                fetchAndStoreUserSettings(activityWatermark)
            }
        }
    }

    private fun startPinAuthorization(): String? {
        val existingVerificationUrl = storedState.pendingPinVerificationUrl
            ?.takeIf { storedState.hasPendingPinAuthorization }
            ?.takeUnless {
                isSimklPinAuthorizationExpired(
                    expiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
                    nowEpochMs = SimklPlatformClock.nowEpochMs(),
                )
            }
        if (existingVerificationUrl != null) {
            publish(isLoading = false, error = null)
            startPinPollingIfNeeded()
            return existingVerificationUrl
        }

        pinPollingJob?.cancel()
        profileGeneration += 1L
        clearPendingAuthorization()
        persistMetadata()
        publish(isLoading = true, error = null)
        val generation = profileGeneration
        scope.launch {
            requestPinAuthorization(generation)
        }
        return null
    }

    private suspend fun requestPinAuthorization(generation: Long) {
        val request = SimklDeviceRequest(clientId = SimklConfig.CLIENT_ID, scope = SIMKL_SCOPE)
        val response = try {
            SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = SIMKL_DEVICE_PATH,
                    body = json.encodeToString(request),
                    requiresAuthentication = false,
                    retryPolicy = SimklRetryPolicy.NEVER,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Failed to start Simkl device authorization: ${error.message}" }
            null
        }
        if (profileGeneration != generation) return

        val now = SimklPlatformClock.nowEpochMs()
        val pending = response
            ?.let { runCatching { json.decodeFromString<SimklDeviceResponse>(it.body) }.getOrNull() }
            ?.toPendingAuthorization(now)
        if (pending == null) {
            clearPendingAuthorization()
            persistMetadata()
            publish(isLoading = false, error = SimklAuthError.INVALID_TOKEN_RESPONSE)
            return
        }

        SimklAuthStorage.saveCodeVerifier(null)
        storedState = storedState.copy(
            pendingAuthorizationState = null,
            pendingAuthorizationStartedAtEpochMs = now,
            pendingPinUserCode = pending.userCode,
            pendingPinDeviceCode = pending.deviceCode,
            pendingPinVerificationUrl = pending.verificationUrl,
            pendingPinIntervalSeconds = pending.intervalSeconds,
            pendingPinExpiresAtEpochMs = pending.expiresAtEpochMs,
        )
        persistMetadata()
        publish(isLoading = false, error = null)
        startPinPollingIfNeeded()
    }

    private fun startPinPollingIfNeeded() {
        if (!isDesktop || !storedState.hasPendingPinAuthorization) return
        if (isSimklPinAuthorizationExpired(
                expiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
        ) {
            expirePinAuthorization()
            return
        }
        if (pinPollingJob?.isActive == true) return
        val userCode = storedState.pendingPinUserCode ?: return
        val generation = profileGeneration
        pinPollingJob = scope.launch {
            pollPinAuthorization(userCode, generation)
        }
    }

    private suspend fun pollPinAuthorization(
        userCode: String,
        generation: Long,
    ) {
        var intervalSeconds = storedState.pendingPinIntervalSeconds?.coerceAtLeast(1) ?: 5
        while (isCurrentPinAuthorization(userCode, generation)) {
            if (isSimklPinAuthorizationExpired(
                    expiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
                    nowEpochMs = SimklPlatformClock.nowEpochMs(),
                )
            ) {
                expirePinAuthorization()
                return
            }

            delay(intervalSeconds * 1_000L)
            if (!isCurrentPinAuthorization(userCode, generation)) return
            if (isSimklPinAuthorizationExpired(
                    expiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
                    nowEpochMs = SimklPlatformClock.nowEpochMs(),
                )
            ) {
                expirePinAuthorization()
                return
            }

            val deviceCode = storedState.pendingPinDeviceCode
            if (deviceCode == null) {
                expirePinAuthorization()
                return
            }
            when (val result = pollPinAuthorizationOnce(deviceCode)) {
                is SimklPinPollResult.Authorized -> {
                    completePinAuthorization(result, generation)
                    return
                }
                SimklPinPollResult.Pending -> publish(isLoading = false, error = null)
                is SimklPinPollResult.SlowDown -> intervalSeconds += result.addSeconds
                SimklPinPollResult.Expired -> {
                    expirePinAuthorization()
                    return
                }
                SimklPinPollResult.Failed -> {
                    clearPendingAuthorization()
                    persistMetadata()
                    publish(isLoading = false, error = SimklAuthError.TOKEN_EXCHANGE_FAILED)
                    return
                }
            }
        }
    }

    private suspend fun pollPinAuthorizationOnce(deviceCode: String): SimklPinPollResult {
        val request = SimklDeviceTokenRequest(clientId = SimklConfig.CLIENT_ID, deviceCode = deviceCode)
        val response = try {
            SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = SIMKL_TOKEN_PATH,
                    body = json.encodeToString(request),
                    requiresAuthentication = false,
                    retryPolicy = SimklRetryPolicy.NEVER,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimklApiException) {
            return when (error.errorCode) {
                "authorization_pending" -> SimklPinPollResult.Pending
                "slow_down" -> SimklPinPollResult.SlowDown()
                "expired_token" -> SimklPinPollResult.Expired
                else -> SimklPinPollResult.Failed
            }
        } catch (error: Throwable) {
            log.w { "Failed to poll Simkl device authorization: ${error.message}" }
            return SimklPinPollResult.Failed
        }
        val token = runCatching { json.decodeFromString<SimklTokenResponse>(response.body) }
            .getOrNull()
            ?.takeIf { it.accessToken.isNotBlank() }
            ?: return SimklPinPollResult.Failed
        return SimklPinPollResult.Authorized(
            accessToken = token.accessToken,
            refreshToken = token.refreshToken?.takeIf(String::isNotBlank),
            expiresInSeconds = token.expiresIn,
        )
    }

    private suspend fun completePinAuthorization(
        result: SimklPinPollResult.Authorized,
        generation: Long,
    ) = authorizationMutex.withLock {
        if (profileGeneration != generation) return@withLock
        publish(isLoading = true, error = null)
        accessToken = result.accessToken
        refreshToken = result.refreshToken
        SimklAuthStorage.saveAccessToken(accessToken)
        SimklAuthStorage.saveRefreshToken(refreshToken)
        clearPendingAuthorization()
        val now = SimklPlatformClock.nowEpochMs()
        storedState = storedState.copy(
            tokenExpiresAtEpochMs = result.expiresInSeconds
                ?.takeIf { seconds -> seconds > 0L }
                ?.let { seconds -> now + seconds * 1_000L },
            refreshTokenExpiresAtEpochMs = refreshToken?.let { now + REFRESH_TOKEN_LIFETIME_MS },
        )
        persistMetadata()
        publish(isLoading = false, error = null)
        fetchAndStoreUserSettings()
        SimklSyncRepository.refreshAsync(
            intent = TrackingRefreshIntent.INVALIDATED,
            origin = SimklRefreshOrigin.AUTHORIZATION,
        )
    }

    private fun isCurrentPinAuthorization(
        userCode: String,
        generation: Long,
    ): Boolean = isDesktop &&
        profileGeneration == generation &&
        storedState.pendingPinUserCode == userCode &&
        storedState.hasPendingPinAuthorization &&
        accessToken.isNullOrBlank()

    private fun expirePinAuthorization() {
        clearPendingAuthorization()
        persistMetadata()
        publish(isLoading = false, error = SimklAuthError.AUTHORIZATION_EXPIRED)
    }

    private suspend fun completeAuthorization(callback: SimklAuthCallback.AuthorizationCode) =
        authorizationMutex.withLock {
            publish(isLoading = true, error = null)
            val expectedState = storedState.pendingAuthorizationState
            val verifier = SimklAuthStorage.loadCodeVerifier()
            val isExpired = isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            if (expectedState.isNullOrBlank() || verifier.isNullOrBlank() || isExpired) {
                clearPendingAuthorization()
                persistMetadata()
                publish(isLoading = false, error = SimklAuthError.AUTHORIZATION_EXPIRED)
                return@withLock
            }
            if (!constantTimeEquals(callback.state, expectedState)) {
                clearPendingAuthorization()
                persistMetadata()
                publish(isLoading = false, error = SimklAuthError.INVALID_CALLBACK_STATE)
                return@withLock
            }

            val request = SimklTokenRequest(
                code = callback.code,
                clientId = SimklConfig.CLIENT_ID,
                codeVerifier = verifier,
                redirectUri = SimklConfig.REDIRECT_URI,
            )
            val response = try {
                SimklApi.client.execute(
                    SimklApiRequest(
                        method = SimklHttpMethod.POST,
                        path = SIMKL_TOKEN_PATH,
                        body = json.encodeToString(request),
                        requiresAuthentication = false,
                        retryPolicy = SimklRetryPolicy.NEVER,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "Simkl token exchange failed: ${error.message}" }
                clearPendingAuthorization()
                persistMetadata()
                publish(isLoading = false, error = SimklAuthError.TOKEN_EXCHANGE_FAILED)
                return@withLock
            }
            val token = runCatching { json.decodeFromString<SimklTokenResponse>(response.body) }
                .getOrNull()
                ?.takeIf { it.accessToken.isNotBlank() }
            if (token == null) {
                clearPendingAuthorization()
                persistMetadata()
                publish(isLoading = false, error = SimklAuthError.INVALID_TOKEN_RESPONSE)
                return@withLock
            }

            accessToken = token.accessToken
            refreshToken = token.refreshToken?.takeIf(String::isNotBlank)
            SimklAuthStorage.saveAccessToken(token.accessToken)
            SimklAuthStorage.saveRefreshToken(refreshToken)
            clearPendingAuthorization()
            val now = SimklPlatformClock.nowEpochMs()
            storedState = storedState.copy(
                tokenExpiresAtEpochMs = token.expiresIn
                    ?.takeIf { seconds -> seconds > 0L }
                    ?.let { seconds -> now + seconds * 1_000L },
                refreshTokenExpiresAtEpochMs = refreshToken?.let { now + REFRESH_TOKEN_LIFETIME_MS },
            )
            persistMetadata()
            publish(isLoading = false, error = null)
            fetchAndStoreUserSettings()
            SimklSyncRepository.refreshAsync(
                intent = TrackingRefreshIntent.INVALIDATED,
                origin = SimklRefreshOrigin.AUTHORIZATION,
            )
        }

    private suspend fun fetchAndStoreUserSettings(activityWatermark: String? = null): Boolean {
        val response = try {
            SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/users/settings",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Failed to fetch Simkl user settings: ${error.message}" }
            return false
        }
        val settings = runCatching { json.decodeFromString<SimklUserSettingsResponse>(response.body) }
            .getOrNull() ?: return false
        storedState = storedState.copy(
            username = settings.user?.name,
            accountId = settings.account?.id,
            hasFetchedUserSettings = true,
            settingsActivityWatermark = activityWatermark ?: storedState.settingsActivityWatermark,
        )
        persistMetadata()
        publish(error = null)
        return true
    }

    private fun loadFromDisk() {
        pinPollingJob?.cancel()
        profileGeneration += 1L
        hasLoaded = true
        storedState = SimklAuthStorage.loadMetadataPayload()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { payload ->
                runCatching { json.decodeFromString<SimklStoredAuthState>(payload) }
                    .onFailure { error -> log.w { "Failed to parse Simkl auth metadata: ${error.message}" } }
                    .getOrNull()
            }
            ?: SimklStoredAuthState()
        accessToken = SimklAuthStorage.loadAccessToken()?.takeIf(String::isNotBlank)
        refreshToken = SimklAuthStorage.loadRefreshToken()?.takeIf(String::isNotBlank)
        // A token left over from AUTH V1 has no `simkl_at_` prefix. V1 tokens can't be
        // exchanged for V2 ones and never expire on their own, so without this check a
        // pre-migration session would look permanently valid and never hit the new flow.
        val isLegacyV1Token = accessToken != null && accessToken?.startsWith(SIMKL_V2_ACCESS_TOKEN_PREFIX) != true
        val accessTokenExpired = accessToken != null && storedState.tokenExpiresAtEpochMs?.let { expiresAt ->
            SimklPlatformClock.nowEpochMs() >= expiresAt - TOKEN_EXPIRY_SKEW_MS
        } == true
        val refreshTokenExpired = refreshToken != null && storedState.refreshTokenExpiresAtEpochMs?.let { expiresAt ->
            SimklPlatformClock.nowEpochMs() >= expiresAt
        } == true
        val canRefreshLater = refreshToken != null && !refreshTokenExpired
        if (accessToken != null && (isLegacyV1Token || ((accessTokenExpired || refreshTokenExpired) && !canRefreshLater))) {
            accessToken = null
            refreshToken = null
            SimklAuthStorage.saveAccessToken(null)
            SimklAuthStorage.saveRefreshToken(null)
            storedState = SimklStoredAuthState()
            persistMetadata()
        }
        val hasWrongPlatformAuthorization = if (isDesktop) {
            !storedState.pendingAuthorizationState.isNullOrBlank()
        } else {
            storedState.hasPendingPinAuthorization
        }
        val hasExpiredAuthorization = when {
            storedState.hasPendingPinAuthorization -> isSimklPinAuthorizationExpired(
                expiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            !storedState.pendingAuthorizationState.isNullOrBlank() -> isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            else -> false
        }
        if (hasWrongPlatformAuthorization || hasExpiredAuthorization) {
            clearPendingAuthorization()
            persistMetadata()
        }
        publish(error = null)
        startPinPollingIfNeeded()
    }

    private fun invalidateCredentials(error: SimklAuthError) {
        pinPollingJob?.cancel()
        profileGeneration += 1L
        accessToken = null
        refreshToken = null
        SimklAuthStorage.saveAccessToken(null)
        SimklAuthStorage.saveRefreshToken(null)
        clearPendingAuthorization()
        storedState = SimklStoredAuthState()
        persistMetadata()
        SimklSyncRepository.clearLocalState()
        publish(isLoading = false, error = error)
    }

    private fun clearPendingAuthorization() {
        SimklAuthStorage.saveCodeVerifier(null)
        storedState = storedState.copy(
            pendingAuthorizationState = null,
            pendingAuthorizationStartedAtEpochMs = null,
            pendingPinUserCode = null,
            pendingPinDeviceCode = null,
            pendingPinVerificationUrl = null,
            pendingPinIntervalSeconds = null,
            pendingPinExpiresAtEpochMs = null,
        )
    }

    private fun persistMetadata() {
        SimklAuthStorage.saveMetadataPayload(json.encodeToString(storedState))
    }

    private fun publish(
        isLoading: Boolean = _uiState.value.isLoading,
        error: SimklAuthError? = _uiState.value.error,
    ) {
        val authenticated = !accessToken.isNullOrBlank()
        _isAuthenticated.value = authenticated
        _uiState.value = SimklAuthUiState(
            mode = when {
                authenticated -> SimklConnectionMode.CONNECTED
                storedState.hasPendingAuthorization -> SimklConnectionMode.AWAITING_APPROVAL
                else -> SimklConnectionMode.DISCONNECTED
            },
            credentialsConfigured = hasRequiredCredentials(),
            isLoading = isLoading,
            username = storedState.username,
            accountId = storedState.accountId,
            tokenExpiresAtEpochMs = storedState.tokenExpiresAtEpochMs,
            pendingAuthorizationStartedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
            usesPinFlow = isDesktop,
            pendingPinUserCode = storedState.pendingPinUserCode,
            pendingPinVerificationUrl = storedState.pendingPinVerificationUrl,
            pendingPinExpiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
            error = error,
        )
    }

    private fun authorizationUrl(material: SimklPkceMaterial): String =
        buildSimklAuthorizationUrl(
            clientId = SimklConfig.CLIENT_ID,
            redirectUri = SimklConfig.REDIRECT_URI,
            appName = SimklConfig.APP_NAME,
            appVersion = simklAppVersion,
            material = material,
        )

    private const val TOKEN_EXPIRY_SKEW_MS = 60_000L
    private const val REFRESH_TOKEN_LIFETIME_MS = 180L * 24L * 60L * 60L * 1_000L
    private const val SIMKL_TOKEN_RESPONSE_MAX_BYTES = 64 * 1024
    private const val SIMKL_DEVICE_PATH = "/oauth2/device"
    private const val SIMKL_V2_ACCESS_TOKEN_PREFIX = "simkl_at_"
}

@Serializable
private data class SimklTokenRequest(
    val code: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("code_verifier") val codeVerifier: String,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("grant_type") val grantType: String = "authorization_code",
)

@Serializable
private data class SimklRefreshTokenRequest(
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("grant_type") val grantType: String = "refresh_token",
)

@Serializable
private data class SimklTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val scope: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

@Serializable
private data class SimklUserSettingsResponse(
    val user: SimklUser? = null,
    val account: SimklAccount? = null,
)

@Serializable
private data class SimklUser(
    val name: String? = null,
)

@Serializable
private data class SimklAccount(
    val id: Long? = null,
)
