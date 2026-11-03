package com.example.data.telegram

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TdlibTransferCleanupTest {
    @Test fun nativeDownloadIsCancelledBeforeItsCachedBytesAreDeleted() = runTest {
        val commands = mutableListOf<JsonObject>()
        TdlibTransferCleanup.cancelAndDeleteFile(42) { commands += it }

        assertEquals(listOf("cancelDownloadFile", "deleteFile"), commands.map { it["@type"] })
        assertEquals(listOf(42, 42), commands.map { it["file_id"] })
        assertEquals(false, commands.first()["only_if_pending"])
    }

    @Test fun cacheDeletionStillRunsIfNativeCancellationFails() = runTest {
        val commands = mutableListOf<String>()
        TdlibTransferCleanup.cancelAndDeleteFile(42) {
            commands += it.stringValue("@type")!!
            if (commands.size == 1) throw IllegalStateException("File is already unavailable")
        }
        assertEquals(listOf("cancelDownloadFile", "deleteFile"), commands)
    }

    @Test fun unknownFileIdDoesNotIssueNativeRequests() = runTest {
        var called = false
        TdlibTransferCleanup.cancelAndDeleteFile(0) { called = true }
        TdlibTransferCleanup.cancelAndDeleteFile(-1) { called = true }
        assertFalse(called)
    }
}
