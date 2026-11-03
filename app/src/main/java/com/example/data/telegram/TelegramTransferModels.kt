package com.example.data.telegram

enum class TelegramAuthorizationStep {
    UNAVAILABLE,
    NEEDS_DEVELOPER_CREDENTIALS,
    NOT_STARTED,
    STARTING,
    WAIT_PHONE,
    WAIT_EMAIL,
    WAIT_EMAIL_CODE,
    WAIT_CODE,
    WAIT_PASSWORD,
    WAIT_REGISTRATION,
    WAIT_OTHER_DEVICE,
    READY,
    TRANSFERRING,
    LOGGING_OUT,
    ERROR
}

enum class TelegramCodeDeliveryMethod {
    TELEGRAM_APP,
    SMS,
    PHONE_CALL,
    MISSED_CALL,
    FLASH_CALL,
    FRAGMENT,
    OTHER
}

data class TelegramTransferState(
    val step: TelegramAuthorizationStep,
    val runtimeAvailable: Boolean,
    val credentialsConfigured: Boolean,
    val authorizationInProgress: Boolean = false,
    val authorizationRouteAttempt: Int = 0,
    val authorizationRouteTotal: Int = 0,
    val authorizationProxyAddress: String? = null,
    val codeDeliveryMethod: TelegramCodeDeliveryMethod? = null,
    val nextCodeDeliveryMethod: TelegramCodeDeliveryMethod? = null,
    val authenticationCodeLength: Int = 0,
    val codeResendAvailableAtElapsedRealtime: Long = 0L,
    val transferProgress: Float = 0f,
    val transferredBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val activeProxyUrl: String? = null,
    val confirmationLink: String? = null,
    val error: String? = null
) {
    val canInitialize: Boolean
        get() = runtimeAvailable && credentialsConfigured &&
            step in setOf(
                TelegramAuthorizationStep.NOT_STARTED,
                TelegramAuthorizationStep.ERROR
            )
}

data class TelegramTransferResult(
    val proxyUrl: String,
    val uploadKbps: Int,
    val downloadKbps: Int,
    val sampleBytes: Long,
    val uploadDurationMs: Long,
    val downloadDurationMs: Long,
    val measuredAt: Long,
    val networkFingerprint: String,
    val networkType: String,
    val isMetered: Boolean = false,
    val rounds: Int = 1,
    val confidence: Int = 0,
    val networkUploadBytes: Long = 0L,
    val networkDownloadBytes: Long = 0L,
    val downloadFloorKbps: Int = downloadKbps,
    val uploadFloorKbps: Int = uploadKbps,
    val downloadPeakKbps: Int = downloadKbps,
    val uploadPeakKbps: Int = uploadKbps
)

class TdlibRequestException(
    val code: Int,
    override val message: String
) : Exception("TDLib $code: $message")
