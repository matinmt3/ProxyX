package com.example.ui

internal fun normalizeBenchmarkDigits(value: String): String {
    return buildString(value.length) {
        value.forEach { character ->
            when (character) {
                in '0'..'9' -> append(character)
                in '\u06F0'..'\u06F9' -> append(('0'.code + character.code - '\u06F0'.code).toChar())
                in '\u0660'..'\u0669' -> append(('0'.code + character.code - '\u0660'.code).toChar())
            }
        }
    }
}

internal fun parseBenchmarkCount(value: String): Int? {
    val normalized = normalizeBenchmarkDigits(value).take(MAX_BENCHMARK_INPUT_DIGITS)
    val parsed = normalized.toLongOrNull() ?: return null
    if (parsed <= 0L) return null
    return parsed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

internal const val MAX_BENCHMARK_INPUT_DIGITS = 10
