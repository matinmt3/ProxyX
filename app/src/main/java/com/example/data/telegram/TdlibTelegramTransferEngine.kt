package com.example.data.telegram

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import com.example.BuildConfig
import com.example.data.network.UserNetworkChangedException
import com.example.data.network.UserNetworkSession
import com.example.data.network.UserNetworkUnavailableException
import com.example.model.MtProtoProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.JsonClient
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class TdlibTelegramTransferEngine(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
        as? ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val keyStore by lazy { TdlibDatabaseKeyStore(appContext) }
    private val operationMutex = Mutex()
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val files = MutableStateFlow<Map<Int, JsonObject>>(emptyMap())
    private val messageSendEvents = MutableStateFlow<Map<Long, JsonObject>>(emptyMap())
    private val authorizationStates = Channel<JsonObject>(Channel.UNLIMITED)
    private val parametersSubmitted = AtomicBoolean(false)
    private var clientId = 0
    private var receiverJob: Job? = null
    private var authorizationJob: Job? = null
    @Volatile private var activeTransfer: ActiveTransfer? = null
    private var authorizationProxyId = 0
    private var authorizationProxyUrl: String? = null
    private var authorizationProxyAddress: String? = null

    @Volatile
    private var runtimeAvailable = false
    private var runtimeChecked = false
    private val credentialsConfigured = BuildConfig.TELEGRAM_API_ID > 0 &&
        BuildConfig.TELEGRAM_API_HASH.isNotBlank()
    private val _state = MutableStateFlow(startupState())
    val state: StateFlow<TelegramTransferState> = _state.asStateFlow()

    suspend fun initialize(): Unit = operationMutex.withLock {
        if (!credentialsConfigured) return@withLock
        _state.value = baseState(TelegramAuthorizationStep.STARTING)
        if (!runtimeChecked) {
            runtimeAvailable = withContext(Dispatchers.IO) { verifyRuntime() }
            runtimeChecked = true
        }
        if (!runtimeAvailable) {
            _state.value = baseState(TelegramAuthorizationStep.UNAVAILABLE)
            return@withLock
        }
        try {
            if (receiverJob?.isActive == true && clientId > 0) {
                handleAuthorizationState(
                    request(
                        mapOf("@type" to "getAuthorizationState"),
                        timeoutMs = INITIALIZATION_REQUEST_TIMEOUT_MS,
                        reportErrors = false
                    )
                )
                awaitInitializationState()
                return@withLock
            }
            _state.value = baseState(TelegramAuthorizationStep.STARTING)
            clientId = JsonClient.createClientId()
            startAuthorizationProcessor()
            receiverJob = scope.launch { receiveLoop() }
            handleAuthorizationState(
                request(
                    mapOf("@type" to "getAuthorizationState"),
                    timeoutMs = INITIALIZATION_REQUEST_TIMEOUT_MS,
                    reportErrors = false
                )
            )
            awaitInitializationState()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.value = baseState(TelegramAuthorizationStep.ERROR).copy(
                error = error.message ?: "TDLib couldn't restore the Telegram session. Retry initialization."
            )
        }
    }

    suspend fun submitPhoneNumber(
        phoneNumber: String,
        proxyCandidates: List<MtProtoProxy> = emptyList()
    ): Unit = operationMutex.withLock {
        requireState(TelegramAuthorizationStep.WAIT_PHONE)
        val trimmed = phoneNumber.trim()
        require(trimmed.all { character ->
            character.isDigit() || character.isWhitespace() || character in "+-()."
        }) {
            "The phone number contains unsupported characters."
        }
        val normalized = "+" + trimmed.filter(Char::isDigit)
        require(trimmed.startsWith('+') && normalized.matches(E164_PHONE_REGEX)) {
            "Use the phone number in international format."
        }

        val networkSession = UserNetworkSession.capture(connectivityManager, true)
            ?: throw UserNetworkUnavailableException()
        _state.update {
            it.copy(
                authorizationInProgress = true,
                authorizationRouteAttempt = 0,
                authorizationRouteTotal = 0,
                error = null
            )
        }
        try {
            prepareAuthorizationRoute(proxyCandidates, networkSession)
            networkSession.ensureCurrent()
            request(
                mapOf(
                    "@type" to "setAuthenticationPhoneNumber",
                    "phone_number" to normalized,
                    "settings" to null
                ),
                timeoutMs = AUTHENTICATION_REQUEST_TIMEOUT_MS,
                reportErrors = false
            )
        } catch (error: TimeoutCancellationException) {
            throw IllegalStateException(
                "Telegram didn't answer through the selected route. Test fresh proxies and retry.",
                error
            ).also(::publishAuthorizationError)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publishAuthorizationError(error)
            throw error
        } finally {
            _state.update { it.copy(authorizationInProgress = false) }
        }
    }

    private suspend fun prepareAuthorizationRoute(
        proxyCandidates: List<MtProtoProxy>,
        networkSession: UserNetworkSession
    ) {
        val candidates = proxyCandidates.asSequence()
            .distinctBy(MtProtoProxy::url)
            .mapNotNull { proxy ->
                MtProtoProxy.normalizeSecret(proxy.secret)?.let { secret -> proxy to secret }
            }
            .sortedByDescending { (proxy, _) -> proxy.url == authorizationProxyUrl }
            .take(MAX_AUTHORIZATION_PROXY_CANDIDATES)
            .toList()
        val routeTotal = candidates.size + 1

        candidates.forEachIndexed { index, (proxy, secret) ->
            networkSession.ensureCurrent()
            _state.update {
                it.copy(
                    authorizationRouteAttempt = index + 1,
                    authorizationRouteTotal = routeTotal,
                    error = null
                )
            }
            if (tryActivateAuthorizationProxy(proxy, secret, networkSession)) return
        }

        _state.update {
            it.copy(
                authorizationRouteAttempt = routeTotal,
                authorizationRouteTotal = routeTotal,
                error = null
            )
        }
        if (tryUseDirectTelegramRoute(networkSession)) return

        throw IllegalStateException(
            "No route to Telegram is available on the current network. " +
                "Run a fresh proxy benchmark, then retry sign-in."
        )
    }

    private suspend fun tryActivateAuthorizationProxy(
        proxy: MtProtoProxy,
        secret: String,
        networkSession: UserNetworkSession
    ): Boolean {
        val tdlibProxy = tdlibProxy(proxy, secret)
        if (!probeTelegramRoute(tdlibProxy, networkSession)) return false

        val previousProxyId = authorizationProxyId
        var addedProxyId = 0
        return try {
            val addedProxy = request(
                mapOf(
                    "@type" to "addProxy",
                    "proxy" to tdlibProxy,
                    "enable" to true,
                    "comment" to "ProxyX secure sign-in route"
                ),
                timeoutMs = AUTHORIZATION_ROUTE_TIMEOUT_MS,
                reportErrors = false
            )
            addedProxyId = requireNotNull(addedProxy.intValue("id")) {
                "TDLib returned no proxy ID."
            }
            verifyActiveProxyRoute(networkSession, AUTHORIZATION_ROUTE_TIMEOUT_MS)

            authorizationProxyId = addedProxyId
            authorizationProxyUrl = proxy.url
            authorizationProxyAddress = proxy.safeAddress()
            _state.update {
                it.copy(
                    authorizationProxyAddress = authorizationProxyAddress,
                    error = null
                )
            }
            if (previousProxyId != 0 && previousProxyId != addedProxyId) {
                removeProxyQuietly(previousProxyId)
            }
            true
        } catch (error: TimeoutCancellationException) {
            restoreAuthorizationProxyAfterFailedAttempt(previousProxyId, addedProxyId)
            false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            restoreAuthorizationProxyAfterFailedAttempt(previousProxyId, addedProxyId)
            false
        }
    }

    private suspend fun tryUseDirectTelegramRoute(networkSession: UserNetworkSession): Boolean {
        if (!probeTelegramRoute(null, networkSession)) return false
        val previousProxyId = authorizationProxyId
        return try {
            request(
                mapOf("@type" to "disableProxy"),
                timeoutMs = AUTHORIZATION_ROUTE_TIMEOUT_MS,
                reportErrors = false
            )
            verifyActiveProxyRoute(networkSession, AUTHORIZATION_ROUTE_TIMEOUT_MS)
            authorizationProxyId = 0
            authorizationProxyUrl = null
            authorizationProxyAddress = null
            _state.update { it.copy(authorizationProxyAddress = null, error = null) }
            if (previousProxyId != 0) removeProxyQuietly(previousProxyId)
            true
        } catch (error: TimeoutCancellationException) {
            restoreProxyQuietly(previousProxyId)
            false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            restoreProxyQuietly(previousProxyId)
            false
        }
    }

    private suspend fun probeTelegramRoute(
        proxy: JsonObject?,
        networkSession: UserNetworkSession
    ): Boolean {
        return try {
            networkSession.ensureCurrent()
            val response = request(
                mapOf("@type" to "pingProxy", "proxy" to proxy),
                timeoutMs = AUTHORIZATION_ROUTE_TIMEOUT_MS,
                reportErrors = false
            )
            networkSession.ensureCurrent()
            response.stringValue("@type") == "seconds"
        } catch (error: TimeoutCancellationException) {
            false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun restoreAuthorizationProxyAfterFailedAttempt(
        previousProxyId: Int,
        addedProxyId: Int
    ) {
        if (addedProxyId != 0 && addedProxyId != previousProxyId) {
            removeProxyQuietly(addedProxyId)
        }
        restoreProxyQuietly(previousProxyId)
    }

    private suspend fun restoreProxyQuietly(proxyId: Int) {
        if (proxyId == 0) return
        runCatching {
            request(
                mapOf("@type" to "enableProxy", "proxy_id" to proxyId),
                timeoutMs = CLEANUP_TIMEOUT_MS,
                reportErrors = false
            )
        }
    }

    private suspend fun removeProxyQuietly(proxyId: Int) {
        if (proxyId == 0) return
        runCatching {
            request(
                mapOf("@type" to "removeProxy", "proxy_id" to proxyId),
                timeoutMs = CLEANUP_TIMEOUT_MS,
                reportErrors = false
            )
        }
    }

    private fun publishAuthorizationError(error: Exception) {
        _state.update {
            it.copy(
                authorizationInProgress = false,
                error = error.message ?: "Telegram sign-in failed."
            )
        }
    }

    suspend fun submitEmailAddress(email: String) {
        requireState(TelegramAuthorizationStep.WAIT_EMAIL)
        val normalized = email.trim()
        require(normalized.length <= 254 && EMAIL_REGEX.matches(normalized)) {
            "Enter a valid email address."
        }
        request(mapOf("@type" to "setAuthenticationEmailAddress", "email_address" to normalized))
    }

    suspend fun submitEmailCode(code: String) {
        requireState(TelegramAuthorizationStep.WAIT_EMAIL_CODE)
        request(
            mapOf(
                "@type" to "checkAuthenticationEmailCode",
                "code" to mapOf("@type" to "emailAddressAuthenticationCode", "code" to code.trim())
            )
        )
    }

    suspend fun submitAuthenticationCode(code: String) {
        requireState(TelegramAuthorizationStep.WAIT_CODE)
        request(mapOf("@type" to "checkAuthenticationCode", "code" to code.trim()))
    }

    suspend fun resendAuthenticationCode(): Unit = operationMutex.withLock {
        requireState(TelegramAuthorizationStep.WAIT_CODE)
        _state.update { it.copy(authorizationInProgress = true, error = null) }
        try {
            request(
                mapOf("@type" to "resendAuthenticationCode", "reason" to null),
                timeoutMs = AUTHENTICATION_REQUEST_TIMEOUT_MS,
                reportErrors = false
            )
        } catch (error: TimeoutCancellationException) {
            throw IllegalStateException(
                "Telegram didn't answer the code resend request.",
                error
            ).also(::publishAuthorizationError)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publishAuthorizationError(error)
            throw error
        } finally {
            _state.update { it.copy(authorizationInProgress = false) }
        }
    }

    suspend fun submitPassword(password: String) {
        requireState(TelegramAuthorizationStep.WAIT_PASSWORD)
        request(mapOf("@type" to "checkAuthenticationPassword", "password" to password))
    }

    suspend fun register(firstName: String, lastName: String) {
        requireState(TelegramAuthorizationStep.WAIT_REGISTRATION)
        val normalizedFirstName = firstName.trim()
        val normalizedLastName = lastName.trim()
        require(normalizedFirstName.length in 1..64) {
            "First name must contain 1 to 64 characters."
        }
        require(normalizedLastName.length <= 64) {
            "Last name must contain at most 64 characters."
        }
        request(
            mapOf(
                "@type" to "registerUser",
                "first_name" to normalizedFirstName,
                "last_name" to normalizedLastName,
                "disable_notification" to false
            )
        )
    }

    suspend fun logout(): Unit = operationMutex.withLock {
        if (clientId <= 0) return@withLock
        _state.value = baseState(TelegramAuthorizationStep.LOGGING_OUT)
        request(mapOf("@type" to "logOut"))
    }

    suspend fun saveFavoriteProxy(
        proxy: MtProtoProxy,
        isPersian: Boolean,
        existingMessageId: Long = 0L,
        routeCandidates: List<MtProtoProxy> = emptyList()
    ): Long = operationMutex.withLock {
        require(_state.value.step == TelegramAuthorizationStep.READY) {
            "Telegram authorization is not ready."
        }
        require(MtProtoProxy.normalizeSecret(proxy.secret) != null) {
            "Invalid MTProto secret."
        }
        val networkSession = UserNetworkSession.capture(connectivityManager, true)
            ?: throw UserNetworkUnavailableException()
        var temporaryMessageId = 0L
        try {
            networkSession.ensureCurrent()
            // Favoriting is a Saved Messages action, not a benchmark of the favorite itself.
            // Recover the TDLib route independently when the proxy used during login has died.
            ensureFavoriteDeliveryRoute(routeCandidates, networkSession)

            val chatId = savedMessagesChatId()
            if (existingMessageId != 0L) {
                val existingMessage = try {
                    request(
                        mapOf(
                            "@type" to "getMessage",
                            "chat_id" to chatId,
                            "message_id" to existingMessageId
                        ),
                        timeoutMs = SAVED_MESSAGE_TIMEOUT_MS,
                        reportErrors = false
                    )
                } catch (error: TdlibRequestException) {
                    if (error.code == TD_MESSAGE_NOT_FOUND_CODE) null else throw error
                }
                val verifiedExistingId = existingMessage?.let { message ->
                    runCatching {
                        SavedProxyMessageVerifier.requireMatchingMessage(
                            message = message,
                            expectedChatId = chatId,
                            proxy = proxy
                        )
                    }.getOrNull()
                }
                if (verifiedExistingId != null) {
                    _state.update { it.copy(error = null) }
                    return@withLock verifiedExistingId
                }
            }

            val formattedText = request(
                mapOf(
                    "@type" to "parseTextEntities",
                    "text" to SavedProxyMessageFormatter.format(proxy, isPersian),
                    "parse_mode" to mapOf("@type" to "textParseModeHTML")
                ),
                timeoutMs = SAVED_MESSAGE_TIMEOUT_MS,
                reportErrors = false
            )
            require(formattedText.stringValue("@type") == "formattedText") {
                "TDLib couldn't format the saved proxy message."
            }

            val sentMessage = request(
                mapOf(
                    "@type" to "sendMessage",
                    "chat_id" to chatId,
                    "topic_id" to null,
                    "reply_to" to null,
                    "options" to null,
                    "reply_markup" to null,
                    "input_message_content" to mapOf(
                        "@type" to "inputMessageText",
                        "text" to formattedText.asTdlibRequestPayload(),
                        "link_preview_options" to null,
                        "clear_draft" to false
                    )
                ),
                timeoutMs = SAVED_MESSAGE_TIMEOUT_MS,
                reportErrors = false
            )
            temporaryMessageId = requireNotNull(sentMessage.longValue("id")) {
                "TDLib returned no Saved Messages message ID."
            }
            val deliveredMessage = if (sentMessage["sending_state"] != null) {
                awaitMessageSent(
                    temporaryMessageId = temporaryMessageId,
                    networkSession = networkSession,
                    timeoutMs = SAVED_MESSAGE_TIMEOUT_MS
                )
            } else {
                sentMessage
            }
            networkSession.ensureCurrent()
            val deliveredMessageId = requireNotNull(deliveredMessage.longValue("id")) {
                "TDLib returned no delivered Saved Messages ID."
            }
            val storedMessage = request(
                mapOf(
                    "@type" to "getMessage",
                    "chat_id" to chatId,
                    "message_id" to deliveredMessageId
                ),
                timeoutMs = SAVED_MESSAGE_TIMEOUT_MS,
                reportErrors = false
            )
            val verifiedMessageId = SavedProxyMessageVerifier.requireMatchingMessage(
                message = storedMessage,
                expectedChatId = chatId,
                proxy = proxy
            )
            _state.update { it.copy(error = null) }
            verifiedMessageId
        } catch (error: TimeoutCancellationException) {
            val timeoutError = IllegalStateException(
                "Favorite delivery timed out on the active Telegram route.",
                error
            )
            _state.update { it.copy(error = timeoutError.message) }
            throw timeoutError
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update { it.copy(error = error.message ?: "Favorite delivery failed.") }
            throw error
        } finally {
            if (temporaryMessageId != 0L) {
                messageSendEvents.update { it - temporaryMessageId }
            }
        }
    }

    private suspend fun ensureFavoriteDeliveryRoute(
        routeCandidates: List<MtProtoProxy>,
        networkSession: UserNetworkSession
    ) {
        val currentRouteWorks = try {
            verifyActiveProxyRoute(networkSession, FAVORITE_ROUTE_HEALTH_TIMEOUT_MS)
            true
        } catch (error: CancellationException) {
            if (error !is TimeoutCancellationException) throw error
            false
        } catch (_: Exception) {
            false
        }
        if (currentRouteWorks) return

        prepareAuthorizationRoute(routeCandidates, networkSession)
    }

    suspend fun measureAdaptiveTransfer(
        proxy: MtProtoProxy,
        maximumConfirmationBytes: Int = TelegramAdaptiveTransferPolicy.MAX_CONFIRMATION_BYTES
    ): TelegramTransferResult {
        val probe = measureTransfer(proxy, TelegramAdaptiveTransferPolicy.PROBE_BYTES)
        val confirmationBytes = TelegramAdaptiveTransferPolicy.confirmationBytes(
            proxy = proxy,
            probe = probe,
            maximumBytes = maximumConfirmationBytes
        )
        if (confirmationBytes <= 0) {
            return probe.copy(
                confidence = TelegramAdaptiveTransferPolicy.singleRoundConfidence(probe)
            )
        }
        val confirmation = measureTransfer(proxy, confirmationBytes)
        val repeatedConfirmation = measureTransfer(proxy, confirmationBytes)
        return TransferRoundStatistics.summarize(listOf(probe, confirmation, repeatedConfirmation))
    }

    suspend fun measureTransfer(
        proxy: MtProtoProxy,
        sampleBytes: Int = DEFAULT_SAMPLE_BYTES
    ): TelegramTransferResult = operationMutex.withLock {
        require(_state.value.step == TelegramAuthorizationStep.READY) {
            "Telegram authorization is not ready."
        }
        val validatedSecret = MtProtoProxy.normalizeSecret(proxy.secret)
            ?: throw IllegalArgumentException("Invalid MTProto secret.")
        val networkSession = UserNetworkSession.capture(connectivityManager, true)
            ?: throw UserNetworkUnavailableException()
        val safeSampleBytes = sampleBytes.coerceIn(MIN_SAMPLE_BYTES, MAX_SAMPLE_BYTES)
        val testFile = withContext(Dispatchers.IO) { createRandomSample(safeSampleBytes) }
        var chatId = 0L
        var messageId = 0L
        var pendingMessageId = 0L
        var fileId = 0
        var proxyId = 0
        activeTransfer = ActiveTransfer(0, safeSampleBytes.toLong(), TransferPhase.UPLOAD)
        _state.value = baseState(TelegramAuthorizationStep.TRANSFERRING).copy(
            totalBytes = safeSampleBytes.toLong() * 2L,
            activeProxyUrl = proxy.url
        )
        try {
            val expectedDigest = withContext(Dispatchers.IO) {
                TransferIntegrity.digest(testFile, safeSampleBytes.toLong())
            }
            val networkBefore = readTdlibNetworkBytes()
            networkSession.ensureCurrent()
            val tdlibProxy = tdlibProxy(proxy, validatedSecret)
            request(
                mapOf("@type" to "pingProxy", "proxy" to tdlibProxy),
                timeoutMs = CONNECTION_READY_TIMEOUT_MS
            )
            networkSession.ensureCurrent()
            if (authorizationProxyId != 0 && authorizationProxyUrl == proxy.url) {
                request(mapOf("@type" to "enableProxy", "proxy_id" to authorizationProxyId))
            } else {
                val addedProxy = request(
                    mapOf(
                        "@type" to "addProxy",
                        "proxy" to tdlibProxy,
                        "enable" to true,
                        "comment" to "ProxyX real transfer test"
                    )
                )
                proxyId = requireNotNull(addedProxy.intValue("id")) {
                    "TDLib returned no proxy ID."
                }
            }
            verifyActiveProxyRoute(networkSession)

            chatId = savedMessagesChatId()

            val uploadStarted = SystemClock.elapsedRealtime()
            val sentMessage = request(
                mapOf(
                    "@type" to "sendMessage",
                    "chat_id" to chatId,
                    "topic_id" to null,
                    "reply_to" to null,
                    "options" to null,
                    "reply_markup" to null,
                    "input_message_content" to mapOf(
                        "@type" to "inputMessageDocument",
                        "document" to mapOf(
                            "@type" to "inputDocument",
                            "document" to mapOf(
                                "@type" to "inputFileLocal",
                                "path" to testFile.absolutePath
                            ),
                            "thumbnail" to null,
                            "disable_content_type_detection" to true
                        ),
                        "caption" to mapOf("@type" to "formattedText", "text" to "", "entities" to emptyList<Any>())
                    )
                ),
                timeoutMs = TRANSFER_TIMEOUT_MS
            )
            val temporaryMessageId = requireNotNull(sentMessage.longValue("id")) {
                "TDLib returned no message ID."
            }
            pendingMessageId = temporaryMessageId
            messageId = temporaryMessageId
            val file = sentMessage.objectValue("content")
                ?.objectValue("document")
                ?.objectValue("document")
                ?: throw IllegalStateException("TDLib returned no uploaded document.")
            fileId = requireNotNull(file.intValue("id")) { "TDLib returned no file ID." }
            activeTransfer = ActiveTransfer(fileId, safeSampleBytes.toLong(), TransferPhase.UPLOAD)
            // updateFile can arrive before sendMessage returns; never replace that newer state.
            files.update { current ->
                if (fileId in current) current else
                    TdlibUpdateCache.remember(current, fileId, file, activeTransfer?.fileId)
            }
            awaitFile(fileId, TransferPhase.UPLOAD, networkSession)
            val uploadDuration = (SystemClock.elapsedRealtime() - uploadStarted).coerceAtLeast(1L)

            val deliveredMessage = if (sentMessage["sending_state"] == null) {
                sentMessage
            } else {
                awaitMessageSent(temporaryMessageId, networkSession, TRANSFER_TIMEOUT_MS)
            }
            messageId = requireNotNull(deliveredMessage.longValue("id")) {
                "TDLib returned no delivered message ID."
            }
            fileId = deliveredMessage.objectValue("content")
                ?.objectValue("document")
                ?.objectValue("document")
                ?.intValue("id")
                ?: fileId
            messageSendEvents.update { it - temporaryMessageId }

            check(!testFile.exists() || testFile.delete()) {
                "The local upload sample could not be removed before the download test."
            }
            request(mapOf("@type" to "deleteFile", "file_id" to fileId))
            networkSession.ensureCurrent()
            activeTransfer = ActiveTransfer(fileId, safeSampleBytes.toLong(), TransferPhase.DOWNLOAD)
            val downloadStarted = SystemClock.elapsedRealtime()
            val downloadedFile = request(
                mapOf(
                    "@type" to "downloadFile",
                    "file_id" to fileId,
                    "priority" to 32,
                    "offset" to 0L,
                    "limit" to 0L,
                    "synchronous" to true
                ),
                timeoutMs = TRANSFER_TIMEOUT_MS
            )
            files.update { TdlibUpdateCache.remember(it, fileId, downloadedFile, activeTransfer?.fileId) }
            require(downloadedFile.objectValue("local")?.booleanValue("is_downloading_completed") == true) {
                "TDLib did not complete the test download."
            }
            networkSession.ensureCurrent()
            val downloadDuration = (SystemClock.elapsedRealtime() - downloadStarted).coerceAtLeast(1L)
            val downloadedPath = downloadedFile.objectValue("local")?.stringValue("path")
                ?.takeIf(String::isNotBlank)
                ?: error("TDLib returned no downloaded test file.")
            withContext(Dispatchers.IO) {
                TransferIntegrity.verify(File(downloadedPath), safeSampleBytes.toLong(), expectedDigest)
            }
            networkSession.ensureCurrent()
            val networkAfter = readTdlibNetworkBytes()
            val networkUploadBytes = networkAfter.sentBytes
                .minus(networkBefore.sentBytes)
                .coerceAtLeast(0L)
            val networkDownloadBytes = networkAfter.receivedBytes
                .minus(networkBefore.receivedBytes)
                .coerceAtLeast(0L)
            val measuredAt = System.currentTimeMillis()
            val measured = TelegramTransferResult(
                proxyUrl = proxy.url,
                uploadKbps = throughputKbps(safeSampleBytes.toLong(), uploadDuration),
                downloadKbps = throughputKbps(safeSampleBytes.toLong(), downloadDuration),
                sampleBytes = safeSampleBytes.toLong(),
                uploadDurationMs = uploadDuration,
                downloadDurationMs = downloadDuration,
                measuredAt = measuredAt,
                networkFingerprint = networkSession.identity.fingerprint,
                networkType = networkSession.identity.type,
                isMetered = networkSession.identity.isMetered,
                rounds = 1,
                networkUploadBytes = networkUploadBytes,
                networkDownloadBytes = networkDownloadBytes
            )
            measured.copy(
                confidence = TelegramAdaptiveTransferPolicy.singleRoundConfidence(measured)
            ).also {
                _state.value = baseState(TelegramAuthorizationStep.READY).copy(
                    transferProgress = 1f,
                    transferredBytes = safeSampleBytes.toLong() * 2L,
                    totalBytes = safeSampleBytes.toLong() * 2L,
                    activeProxyUrl = proxy.url
                )
            }
        } catch (error: TimeoutCancellationException) {
            val timeoutError = IllegalStateException(
                "The Telegram transfer timed out on the current network.",
                error
            )
            _state.value = baseState(TelegramAuthorizationStep.READY).copy(
                error = timeoutError.message,
                activeProxyUrl = proxy.url
            )
            throw timeoutError
        } catch (error: CancellationException) {
            _state.value = baseState(TelegramAuthorizationStep.READY)
            throw error
        } catch (error: Exception) {
            _state.value = baseState(TelegramAuthorizationStep.READY).copy(
                error = error.message ?: "Telegram transfer failed.",
                activeProxyUrl = proxy.url
            )
            throw error
        } finally {
            activeTransfer = null
            testFile.delete()
            if (pendingMessageId != 0L) {
                messageSendEvents.update { it - pendingMessageId }
            }
            withContext(NonCancellable) {
                withTimeoutOrNull(CLEANUP_TOTAL_TIMEOUT_MS) {
                    cleanupTransfer(fileId, chatId, messageId, proxyId)
                }
            }
        }
    }

    private suspend fun readTdlibNetworkBytes(): TdlibNetworkBytes {
        val statistics = runCatching {
            request(
                mapOf("@type" to "getNetworkStatistics", "only_current" to true),
                timeoutMs = NETWORK_STATISTICS_TIMEOUT_MS,
                reportErrors = false
            )
        }.getOrNull() ?: return TdlibNetworkBytes()
        var sent = 0L
        var received = 0L
        statistics.listValue("entries").forEach { entry ->
            @Suppress("UNCHECKED_CAST")
            val item = entry as? JsonObject ?: return@forEach
            sent += item.longValue("sent_bytes")?.coerceAtLeast(0L) ?: 0L
            received += item.longValue("received_bytes")?.coerceAtLeast(0L) ?: 0L
        }
        return TdlibNetworkBytes(sentBytes = sent, receivedBytes = received)
    }

    private suspend fun cleanupTransfer(fileId: Int, chatId: Long, messageId: Long, proxyId: Int) {
        if (fileId != 0) {
            TdlibTransferCleanup.cancelAndDeleteFile(fileId) { command ->
                request(
                    command,
                    timeoutMs = CLEANUP_TIMEOUT_MS,
                    reportErrors = false
                )
            }
            files.update { it - fileId }
        }
        val canDeleteMessage = chatId != 0L && messageId != 0L
        val messageDeleted = canDeleteMessage && deleteMessage(chatId, messageId)
        if (proxyId != 0) {
            removeProxyQuietly(proxyId)
        }
        if (authorizationProxyId != 0) {
            restoreProxyQuietly(authorizationProxyId)
        } else if (proxyId != 0) {
            runCatching {
                request(
                    mapOf("@type" to "disableProxy"),
                    timeoutMs = CLEANUP_TIMEOUT_MS,
                    reportErrors = false
                )
            }
        }
        if (canDeleteMessage && !messageDeleted) {
            deleteMessage(chatId, messageId)
        }
    }

    private suspend fun cleanupTemporaryProxyRoute(proxyId: Int) {
        if (proxyId != 0) removeProxyQuietly(proxyId)
        if (authorizationProxyId != 0) {
            restoreProxyQuietly(authorizationProxyId)
        } else if (proxyId != 0) {
            runCatching {
                request(
                    mapOf("@type" to "disableProxy"),
                    timeoutMs = CLEANUP_TIMEOUT_MS,
                    reportErrors = false
                )
            }
        }
    }

    private suspend fun deleteMessage(chatId: Long, messageId: Long): Boolean = runCatching {
        request(
            mapOf(
                "@type" to "deleteMessages",
                "chat_id" to chatId,
                "message_ids" to listOf(messageId),
                "revoke" to true
            ),
            timeoutMs = CLEANUP_TIMEOUT_MS,
            reportErrors = false
        )
    }.isSuccess

    private suspend fun verifyActiveProxyRoute(
        networkSession: UserNetworkSession,
        timeoutMs: Long = CONNECTION_READY_TIMEOUT_MS
    ) {
        val country = request(
            mapOf("@type" to "getCountryCode"),
            timeoutMs = timeoutMs,
            reportErrors = false
        )
        networkSession.ensureCurrent()
        require(country.stringValue("@type") == "text" && country.stringValue("text").orEmpty().isNotBlank()) {
            "TDLib couldn't verify the active proxy route."
        }
    }

    private suspend fun savedMessagesChatId(): Long {
        val me = request(mapOf("@type" to "getMe"))
        val userId = requireNotNull(me.longValue("id")) { "TDLib returned no user ID." }
        val chat = request(
            mapOf("@type" to "createPrivateChat", "user_id" to userId, "force" to false)
        )
        return requireNotNull(chat.longValue("id")) {
            "TDLib returned no Saved Messages chat."
        }
    }

    private fun tdlibProxy(proxy: MtProtoProxy, validatedSecret: String): JsonObject {
        return mapOf(
            "@type" to "proxy",
            "server" to proxy.server,
            "port" to proxy.port,
            "type" to mapOf(
                "@type" to "proxyTypeMtproto",
                "secret" to validatedSecret
            )
        )
    }

    private fun MtProtoProxy.safeAddress(): String {
        val safeServer = if (':' in server) "[$server]" else server
        return "$safeServer:$port"
    }

    private suspend fun awaitFile(
        fileId: Int,
        phase: TransferPhase,
        networkSession: UserNetworkSession
    ): JsonObject {
        return withTimeout(TRANSFER_TIMEOUT_MS) {
            files.map { it[fileId] }
                .filterNotNull()
                .first { file ->
                    networkSession.ensureCurrent()
                    publishTransferProgress(file, phase)
                    when (phase) {
                        TransferPhase.UPLOAD -> file.objectValue("remote")
                            ?.booleanValue("is_uploading_completed") == true
                        TransferPhase.DOWNLOAD -> file.objectValue("local")
                            ?.booleanValue("is_downloading_completed") == true
                    }
                }
        }
    }

    private suspend fun awaitMessageSent(
        temporaryMessageId: Long,
        networkSession: UserNetworkSession,
        timeoutMs: Long
    ): JsonObject {
        val event = withTimeout(timeoutMs) {
            messageSendEvents.map { it[temporaryMessageId] }
                .filterNotNull()
                .first {
                    networkSession.ensureCurrent()
                    true
                }
        }
        return event.requireDeliveredMessage()
    }

    private fun publishTransferProgress(file: JsonObject, phase: TransferPhase) {
        val transfer = activeTransfer ?: return
        val uploaded = file.objectValue("remote")?.longValue("uploaded_size") ?: 0L
        val downloaded = file.objectValue("local")?.longValue("downloaded_size") ?: 0L
        val completed = when (phase) {
            TransferPhase.UPLOAD -> uploaded
            TransferPhase.DOWNLOAD -> transfer.sampleBytes + downloaded
        }.coerceIn(0L, transfer.sampleBytes * 2L)
        _state.update { current ->
            current.copy(
                transferredBytes = completed,
                totalBytes = transfer.sampleBytes * 2L,
                transferProgress = completed.toFloat() / (transfer.sampleBytes * 2L).toFloat()
            )
        }
    }

    private suspend fun request(
        body: JsonObject,
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
        reportErrors: Boolean = true
    ): JsonObject {
        check(clientId > 0 && receiverJob?.isActive == true) { "TDLib is not initialized." }
        val requestId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[requestId] = deferred
        val request = LinkedHashMap(body).apply { put("@extra", requestId) }
        try {
            JsonClient.send(clientId, TdlibJsonCodec.encode(request))
            val response = withTimeout(timeoutMs) { deferred.await() }
            if (response.stringValue("@type") == "error") {
                val code = response.intValue("code") ?: -1
                val message = response.stringValue("message") ?: "Unknown TDLib error"
                throw TdlibRequestException(code = code, message = message)
            }
            return response
        } catch (error: Exception) {
            if (reportErrors && error !is CancellationException) {
                _state.update { it.copy(error = error.message) }
            }
            throw error
        } finally {
            pendingRequests.remove(requestId)
        }
    }

    private suspend fun receiveLoop() {
        while (currentCoroutineContext().isActive) {
            val encoded = try {
                JsonClient.receive(RECEIVE_TIMEOUT_SECONDS)
            } catch (error: Throwable) {
                _state.value = baseState(TelegramAuthorizationStep.ERROR).copy(
                    error = error.message ?: "TDLib receive loop failed."
                )
                return
            }
            if (encoded == null) continue
            val message = TdlibJsonCodec.decode(encoded) ?: continue
            val messageClientId = message.intValue("@client_id")
            if (messageClientId != null && messageClientId != clientId) continue
            message.stringValue("@extra")?.let { requestId ->
                pendingRequests.remove(requestId)?.complete(message)
            }
            handleUpdate(message)
        }
    }

    private fun handleUpdate(message: JsonObject) {
        when (message.stringValue("@type")) {
            "updateAuthorizationState" -> {
                val authorizationState = message.objectValue("authorization_state") ?: return
                authorizationStates.trySend(authorizationState)
            }
            "updateFile" -> {
                val file = message.objectValue("file") ?: return
                val id = file.intValue("id") ?: return
                files.update { TdlibUpdateCache.remember(it, id, file, activeTransfer?.fileId) }
                activeTransfer?.takeIf { it.fileId == id }?.let { transfer ->
                    publishTransferProgress(file, transfer.phase)
                }
            }
            "updateMessageSendSucceeded", "updateMessageSendFailed" -> {
                val oldMessageId = message.longValue("old_message_id") ?: return
                messageSendEvents.update { TdlibUpdateCache.remember(it, oldMessageId, message) }
            }
        }
    }

    private suspend fun handleAuthorizationState(authorizationState: JsonObject) {
        val type = authorizationState.stringValue("@type") ?: return
        when (type) {
            "authorizationStateWaitTdlibParameters" -> {
                _state.value = baseState(TelegramAuthorizationStep.STARTING)
                if (parametersSubmitted.compareAndSet(false, true)) submitParameters()
            }
            "authorizationStateWaitPhoneNumber" -> setAuthorizationStep(TelegramAuthorizationStep.WAIT_PHONE)
            "authorizationStateWaitPremiumPurchase" -> {
                _state.value = baseState(TelegramAuthorizationStep.ERROR).copy(
                    error = "Telegram requires an in-store Premium purchase for this login; " +
                        "this app cannot complete store purchases."
                )
            }
            "authorizationStateWaitEmailAddress" -> setAuthorizationStep(TelegramAuthorizationStep.WAIT_EMAIL)
            "authorizationStateWaitEmailCode" -> setAuthorizationStep(TelegramAuthorizationStep.WAIT_EMAIL_CODE)
            "authorizationStateWaitCode" -> publishWaitCodeState(authorizationState)
            "authorizationStateWaitPassword" -> setAuthorizationStep(TelegramAuthorizationStep.WAIT_PASSWORD)
            "authorizationStateWaitRegistration" -> setAuthorizationStep(TelegramAuthorizationStep.WAIT_REGISTRATION)
            "authorizationStateWaitOtherDeviceConfirmation" -> {
                _state.value = baseState(TelegramAuthorizationStep.WAIT_OTHER_DEVICE).copy(
                    confirmationLink = authorizationState.stringValue("link")
                )
            }
            "authorizationStateReady" -> {
                setAuthorizationStep(TelegramAuthorizationStep.READY)
                restorePersistedProxyMetadata()
            }
            "authorizationStateLoggingOut", "authorizationStateClosing" ->
                setAuthorizationStep(TelegramAuthorizationStep.LOGGING_OUT)
            "authorizationStateClosed" -> {
                parametersSubmitted.set(false)
                clientId = 0
                authorizationProxyId = 0
                authorizationProxyUrl = null
                authorizationProxyAddress = null
                files.value = emptyMap()
                messageSendEvents.value = emptyMap()
                pendingRequests.values.forEach { pending ->
                    pending.completeExceptionally(IllegalStateException("TDLib session closed."))
                }
                pendingRequests.clear()
                receiverJob?.cancel()
                receiverJob = null
                authorizationJob?.cancel()
                authorizationJob = null
                _state.value = baseState(TelegramAuthorizationStep.NOT_STARTED)
            }
        }
    }

    private suspend fun submitParameters() {
        val databaseDirectory = appContext.noBackupFilesDir.resolve("tdlib/database")
        val filesDirectory = appContext.noBackupFilesDir.resolve("tdlib/files")
        databaseDirectory.mkdirs()
        filesDirectory.mkdirs()
        val encryptionKey = Base64.encodeToString(keyStore.getOrCreate(), Base64.NO_WRAP)
        try {
            request(
                mapOf(
                    "@type" to "setTdlibParameters",
                    "use_test_dc" to false,
                    "database_directory" to databaseDirectory.absolutePath,
                    "files_directory" to filesDirectory.absolutePath,
                    "database_encryption_key" to encryptionKey,
                    "use_file_database" to true,
                    "use_chat_info_database" to true,
                    "use_message_database" to true,
                    "use_secret_chats" to false,
                    "api_id" to BuildConfig.TELEGRAM_API_ID,
                    "api_hash" to BuildConfig.TELEGRAM_API_HASH,
                    "system_language_code" to appContext.resources.configuration.locales[0].toLanguageTag(),
                    "device_model" to Build.MODEL.ifBlank { "Android" },
                    "system_version" to Build.VERSION.RELEASE,
                    "application_version" to BuildConfig.VERSION_NAME
                )
            )
        } catch (error: Exception) {
            parametersSubmitted.set(false)
            _state.value = baseState(TelegramAuthorizationStep.ERROR).copy(error = error.message)
        }
    }

    private suspend fun awaitInitializationState() {
        if (_state.value.step != TelegramAuthorizationStep.STARTING) return
        val initialized = withTimeoutOrNull(INITIALIZATION_STATE_TIMEOUT_MS) {
            while (_state.value.step == TelegramAuthorizationStep.STARTING) {
                delay(INITIALIZATION_POLL_INTERVAL_MS)
                handleAuthorizationState(
                    request(
                        mapOf("@type" to "getAuthorizationState"),
                        timeoutMs = CLEANUP_TIMEOUT_MS,
                        reportErrors = false
                    )
                )
            }
            true
        } == true
        check(initialized) {
            "TDLib couldn't restore the Telegram session. Check the network and retry initialization."
        }
    }

    private suspend fun restorePersistedProxyMetadata() {
        val enabledProxyId = runCatching {
            request(
                mapOf("@type" to "getOption", "name" to "enabled_proxy_id"),
                timeoutMs = CLEANUP_TIMEOUT_MS,
                reportErrors = false
            ).longValue("value")?.toInt() ?: 0
        }.getOrDefault(0)
        authorizationProxyId = enabledProxyId.coerceAtLeast(0)
        authorizationProxyUrl = null
        authorizationProxyAddress = if (authorizationProxyId == 0) {
            null
        } else {
            runCatching {
                val response = request(
                    mapOf("@type" to "getProxies"),
                    timeoutMs = CLEANUP_TIMEOUT_MS,
                    reportErrors = false
                )
                @Suppress("UNCHECKED_CAST")
                val proxies = response["proxies"] as? List<JsonObject>
                val activeProxy = proxies?.firstOrNull {
                    it.intValue("id") == authorizationProxyId
                }
                val server = activeProxy?.stringValue("server")
                val port = activeProxy?.intValue("port")
                if (server.isNullOrBlank() || port == null) null else {
                    val safeServer = if (':' in server) "[$server]" else server
                    "$safeServer:$port"
                }
            }.getOrNull()
        }
        _state.update {
            it.copy(
                authorizationProxyAddress = authorizationProxyAddress,
                error = null
            )
        }
    }

    private fun startAuthorizationProcessor() {
        if (authorizationJob?.isActive == true) return
        while (authorizationStates.tryReceive().isSuccess) {
            // Drop stale authorization updates left by a closed TDLib instance.
        }
        authorizationJob = scope.launch {
            for (authorizationState in authorizationStates) {
                handleAuthorizationState(authorizationState)
            }
        }
    }

    private fun setAuthorizationStep(step: TelegramAuthorizationStep) {
        _state.value = baseState(step)
    }

    private fun publishWaitCodeState(authorizationState: JsonObject) {
        val codeInfo = authorizationState.objectValue("code_info")
        val deliveryType = codeInfo?.objectValue("type")
        val nextDeliveryType = codeInfo?.objectValue("next_type")
        val resendTimeoutSeconds = codeInfo?.intValue("timeout")?.coerceAtLeast(0) ?: 0
        _state.value = baseState(TelegramAuthorizationStep.WAIT_CODE).copy(
            codeDeliveryMethod = deliveryType.toCodeDeliveryMethod(),
            nextCodeDeliveryMethod = nextDeliveryType.toCodeDeliveryMethod(),
            authenticationCodeLength = deliveryType?.intValue("length")?.coerceAtLeast(0) ?: 0,
            codeResendAvailableAtElapsedRealtime = if (nextDeliveryType == null) {
                0L
            } else {
                SystemClock.elapsedRealtime() + resendTimeoutSeconds * 1_000L
            }
        )
    }

    private fun JsonObject?.toCodeDeliveryMethod(): TelegramCodeDeliveryMethod? {
        return when (this?.stringValue("@type")) {
            "authenticationCodeTypeTelegramMessage" -> TelegramCodeDeliveryMethod.TELEGRAM_APP
            "authenticationCodeTypeSms",
            "authenticationCodeTypeSmsPhrase",
            "authenticationCodeTypeSmsWord",
            "authenticationCodeTypeFirebaseAndroid",
            "authenticationCodeTypeFirebaseIos" -> TelegramCodeDeliveryMethod.SMS
            "authenticationCodeTypeCall" -> TelegramCodeDeliveryMethod.PHONE_CALL
            "authenticationCodeTypeMissedCall" -> TelegramCodeDeliveryMethod.MISSED_CALL
            "authenticationCodeTypeFlashCall" -> TelegramCodeDeliveryMethod.FLASH_CALL
            "authenticationCodeTypeFragment" -> TelegramCodeDeliveryMethod.FRAGMENT
            null -> null
            else -> TelegramCodeDeliveryMethod.OTHER
        }
    }

    private fun requireState(expected: TelegramAuthorizationStep) {
        check(_state.value.step == expected) { "TDLib isn't waiting for ${expected.name}." }
    }

    private fun createRandomSample(size: Int): File {
        val file = File.createTempFile("proxyx-transfer-", ".bin", appContext.cacheDir)
        val random = SecureRandom()
        FileOutputStream(file).buffered().use { output ->
            val buffer = ByteArray(SAMPLE_BUFFER_BYTES)
            var remaining = size
            while (remaining > 0) {
                random.nextBytes(buffer)
                val count = minOf(remaining, buffer.size)
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
        return file
    }

    private fun throughputKbps(bytes: Long, durationMs: Long): Int {
        if (bytes <= 0L || durationMs <= 0L) return 0
        return (bytes.toDouble() * 1_000.0 / durationMs.toDouble() / 1_024.0)
            .roundToInt()
            .coerceAtLeast(1)
    }

    private fun startupState() = TelegramTransferState(
        step = if (credentialsConfigured) {
            TelegramAuthorizationStep.STARTING
        } else {
            TelegramAuthorizationStep.NEEDS_DEVELOPER_CREDENTIALS
        },
        runtimeAvailable = false,
        credentialsConfigured = credentialsConfigured
    )

    private fun verifyRuntime(): Boolean {
        if (!JsonClient.isAvailable()) return false
        return try {
            val response = JsonClient.execute(
                TdlibJsonCodec.encode(
                    mapOf("@type" to "setLogVerbosityLevel", "new_verbosity_level" to 1)
                )
            ) ?: return false
            TdlibJsonCodec.decode(response)?.stringValue("@type") == "ok"
        } catch (_: Throwable) {
            false
        }
    }

    private fun baseState(step: TelegramAuthorizationStep) = TelegramTransferState(
        step = step,
        runtimeAvailable = runtimeAvailable,
        credentialsConfigured = credentialsConfigured,
        authorizationProxyAddress = authorizationProxyAddress
    )

    private data class ActiveTransfer(
        val fileId: Int,
        val sampleBytes: Long,
        val phase: TransferPhase
    )

    private data class TdlibNetworkBytes(
        val sentBytes: Long = 0L,
        val receivedBytes: Long = 0L
    )

    private enum class TransferPhase { UPLOAD, DOWNLOAD }

    private companion object {
        const val DEFAULT_SAMPLE_BYTES = 4 * 1024 * 1024
        const val MIN_SAMPLE_BYTES = 256 * 1024
        const val MAX_SAMPLE_BYTES = 32 * 1024 * 1024
        const val SAMPLE_BUFFER_BYTES = 64 * 1024
        const val NETWORK_STATISTICS_TIMEOUT_MS = 5_000L
        val E164_PHONE_REGEX = Regex("^\\+[1-9][0-9]{6,14}$")
        val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val INITIALIZATION_REQUEST_TIMEOUT_MS = 60_000L
        const val INITIALIZATION_STATE_TIMEOUT_MS = 60_000L
        const val INITIALIZATION_POLL_INTERVAL_MS = 750L
        const val AUTHENTICATION_REQUEST_TIMEOUT_MS = 45_000L
        const val AUTHORIZATION_ROUTE_TIMEOUT_MS = 12_000L
        const val MAX_AUTHORIZATION_PROXY_CANDIDATES = 8
        const val FAVORITE_ROUTE_HEALTH_TIMEOUT_MS = 8_000L
        const val CONNECTION_READY_TIMEOUT_MS = 45_000L
        const val SAVED_MESSAGE_TIMEOUT_MS = 60_000L
        const val TD_MESSAGE_NOT_FOUND_CODE = 404
        const val TRANSFER_TIMEOUT_MS = 180_000L
        const val CLEANUP_TIMEOUT_MS = 5_000L
        const val CLEANUP_TOTAL_TIMEOUT_MS = 12_000L
        const val RECEIVE_TIMEOUT_SECONDS = 0.5
    }
}
