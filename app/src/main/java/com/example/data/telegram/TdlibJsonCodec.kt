package com.example.data.telegram

import com.squareup.moshi.Moshi
import kotlin.math.abs
import kotlin.math.floor

internal typealias JsonObject = Map<String, Any?>

internal object TdlibJsonCodec {
    private val adapter = Moshi.Builder().build().adapter(Any::class.java)

    fun encode(value: JsonObject): String = adapter.toJson(value)

    @Suppress("UNCHECKED_CAST")
    fun decode(value: String): JsonObject? = adapter.fromJson(value) as? JsonObject
}

internal fun JsonObject.objectValue(key: String): JsonObject? {
    @Suppress("UNCHECKED_CAST")
    return this[key] as? JsonObject
}

internal fun JsonObject.stringValue(key: String): String? = this[key] as? String

internal fun JsonObject.longValue(key: String): Long? = (this[key] as? Number)?.toLong()

internal fun JsonObject.intValue(key: String): Int? = (this[key] as? Number)?.toInt()

internal fun JsonObject.booleanValue(key: String): Boolean = this[key] as? Boolean ?: false

internal fun JsonObject.listValue(key: String): List<Any?> {
    return this[key] as? List<Any?> ?: emptyList()
}

/**
 * TDLib responses decoded through Moshi use Double for JSON numbers. When a response object is
 * reused in another request, integer-only fields such as text-entity offsets must be written back
 * without a decimal suffix. Transport-only @extra values must not become part of nested objects.
 */
internal fun JsonObject.asTdlibRequestPayload(): JsonObject = buildMap {
    this@asTdlibRequestPayload.forEach { (key, value) ->
        if (key != "@extra") put(key, value.asTdlibRequestValue())
    }
}

private fun Any?.asTdlibRequestValue(): Any? = when (this) {
    is Double -> if (
        isFinite() &&
        abs(this) <= MAX_SAFE_JSON_INTEGER &&
        floor(this) == this
    ) {
        toLong()
    } else {
        this
    }

    is Map<*, *> -> buildMap {
        this@asTdlibRequestValue.forEach { (key, value) ->
            if (key is String && key != "@extra") put(key, value.asTdlibRequestValue())
        }
    }

    is List<*> -> map { it.asTdlibRequestValue() }
    else -> this
}

private const val MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991.0

internal fun JsonObject.requireDeliveredMessage(): JsonObject {
    if (stringValue("@type") == "updateMessageSendFailed") {
        val error = objectValue("error")
        throw TdlibRequestException(
            code = error?.intValue("code") ?: -1,
            message = error?.stringValue("message") ?: "Telegram rejected the test message."
        )
    }
    require(stringValue("@type") == "updateMessageSendSucceeded") {
        "Unexpected TDLib message-send update."
    }
    return requireNotNull(objectValue("message")) {
        "TDLib returned no delivered message."
    }
}
