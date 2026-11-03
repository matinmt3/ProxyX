package com.example.data.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BenchmarkRuntimeStateTest {
    @Test fun aFreshProxySnapshotClearsTerminalProgressAndRunTimestamps() {
        val completed = BenchmarkRuntimeState(status = BenchmarkRunStatus.COMPLETED,
            requestedCount = 20, accuracyMode = BenchmarkAccuracyMode.PRECISE,
            progress = ThreeStageProgress(BenchmarkStage.FINALIST, 5, 5, 20, 20),
            startedAt = 100, finishedAt = 200, telegramTransfersRequested = true)
        assertEquals(BenchmarkRuntimeState(accuracyMode = BenchmarkAccuracyMode.PRECISE),
            completed.forFreshProxySnapshot())
        assertEquals(BenchmarkRuntimeState(), completed.copy(accuracyMode = BenchmarkAccuracyMode.BALANCED,
            status = BenchmarkRunStatus.CANCELLED).forFreshProxySnapshot())
    }

    @Test fun freshSnapshotResetCannotClearAnActiveRun() {
        listOf(BenchmarkRunStatus.RUNNING, BenchmarkRunStatus.PAUSED).forEach {
            val state = BenchmarkRuntimeState(status = it, requestedCount = 1800, startedAt = 100)
            assertSame(state, state.forFreshProxySnapshot())
        }
    }

    @Test fun laterStagesMustNotDisplayFrozenScreeningRows() {
        val state = BenchmarkRuntimeState(status = BenchmarkRunStatus.RUNNING,
            progress = ThreeStageProgress(BenchmarkStage.SCREENING, 5, 5, 5, 5))
        org.junit.Assert.assertTrue(state.showsScreeningPreview)
        org.junit.Assert.assertFalse(state.copy(progress = state.progress!!.copy(stage = BenchmarkStage.STRICT)).showsScreeningPreview)
        org.junit.Assert.assertFalse(state.copy(telegramTransferProgress = TelegramFinalistTransferProgress(2, 0, 0, 0)).showsScreeningPreview)
        org.junit.Assert.assertFalse(state.copy(status = BenchmarkRunStatus.COMPLETED).showsScreeningPreview)
    }
    @Test fun fourStageProgressDoesNotRestartAtZeroWhenTransferBegins() {
        val routesDone = BenchmarkRuntimeState(status = BenchmarkRunStatus.RUNNING,
            telegramTransfersRequested = true,
            progress = ThreeStageProgress(BenchmarkStage.FINALIST, 3, 3, 5, 5))
        assertEquals(0.75f, routesDone.overallFraction, 0.001f)
        val transfer = routesDone.copy(telegramTransferProgress =
            TelegramFinalistTransferProgress(1, 0, 0, 0, completedSamples = 1, totalSamples = 3))
        assertEquals(0.8333f, transfer.overallFraction, 0.001f)
        assertEquals(1f, transfer.copy(status = BenchmarkRunStatus.COMPLETED).overallFraction, 0f)
    }
    @Test fun pauseAndResumePreserveRunMetadata() {
        val running = BenchmarkRuntimeState(
            status = BenchmarkRunStatus.RUNNING,
            requestedCount = 1800,
            selectedWorkers = 3,
            startedAt = 1234L
        )
        val paused = running.withPauseState(paused = true)
        assertEquals(running.copy(status = BenchmarkRunStatus.PAUSED), paused)
        assertEquals(running, paused.withPauseState(paused = false))
        assertSame(paused, paused.withPauseState(paused = true))
        assertSame(running, running.withPauseState(paused = false))
    }

    @Test fun delayedControlsCannotReviveTerminalOrIdleRuns() {
        BenchmarkRunStatus.entries.filter {
            it != BenchmarkRunStatus.RUNNING && it != BenchmarkRunStatus.PAUSED
        }.forEach { status ->
            val state = BenchmarkRuntimeState(status = status, finishedAt = 5678L)
            assertSame(state, state.withPauseState(paused = true))
            assertSame(state, state.withPauseState(paused = false))
        }
    }
}
