package com.example.data.telegram

internal object TdlibUpdateCache {
    const val MAX_ENTRIES = 128

    fun <K, V> remember(current: Map<K, V>, key: K, value: V, protectedKey: K? = null): Map<K, V> {
        val updated = LinkedHashMap(current)
        updated.remove(key)
        updated[key] = value
        val oldest = updated.keys.iterator()
        while (updated.size > MAX_ENTRIES && oldest.hasNext()) {
            if (oldest.next() != protectedKey) oldest.remove()
        }
        return updated
    }
}
