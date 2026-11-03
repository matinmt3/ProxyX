package com.example.viewmodel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ManualTransferTrackerTest {
    @Test fun tracksPreparationAndClearsAfterSuccess() = runTest {
        val tracker = ManualTransferTracker()
        val result = tracker.track("proxy") {
            assertEquals("proxy", tracker.activeProxyUrl.value)
            42
        }
        assertEquals(42, result)
        assertNull(tracker.activeProxyUrl.value)
    }

    @Test fun clearsAfterPreparationFailureAndCanStartAgain() = runTest {
        val tracker = ManualTransferTracker()
        try {
            tracker.track("proxy") { error("Route unavailable") }
            fail("Expected the preparation failure")
        } catch (error: IllegalStateException) {
            assertEquals("Route unavailable", error.message)
        }
        assertNull(tracker.activeProxyUrl.value)
        tracker.track("second") { assertEquals("second", tracker.activeProxyUrl.value) }
        assertNull(tracker.activeProxyUrl.value)
    }

    @Test fun cancellationBeforeNativeTransferReleasesState() = runTest {
        val tracker = ManualTransferTracker()
        val preparing = CompletableDeferred<Unit>()
        val job = launch {
            tracker.track("proxy") {
                preparing.complete(Unit)
                awaitCancellation()
            }
        }
        preparing.await()
        assertEquals("proxy", tracker.activeProxyUrl.value)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertNull(tracker.activeProxyUrl.value)
    }

    @Test fun rejectingOverlapDoesNotClearTheActiveOperation() = runTest {
        val tracker = ManualTransferTracker()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            tracker.track("first") { awaitCancellation() }
        }
        try {
            tracker.track("second") { fail("Must not run an overlapping operation") }
            fail("Expected overlap rejection")
        } catch (_: IllegalStateException) {
            assertEquals("first", tracker.activeProxyUrl.value)
        }
        job.cancelAndJoin()
        assertNull(tracker.activeProxyUrl.value)
    }
}
