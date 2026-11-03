package com.example.data.telegram

internal object TdlibTransferCleanup {
    suspend fun cancelAndDeleteFile(fileId: Int, request: suspend (JsonObject) -> Unit) {
        if (fileId <= 0) return
        val commands = listOf(
            mapOf("@type" to "cancelDownloadFile", "file_id" to fileId, "only_if_pending" to false),
            mapOf("@type" to "deleteFile", "file_id" to fileId)
        )
        // Deleting cached bytes alone doesn't stop TDLib's native download request.
        // The caller bounds this best-effort cleanup in a NonCancellable block.
        commands.forEach { command -> runCatching { request(command) } }
    }
}
