package com.example.data.benchmark

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.ProxyXApplication

class ProxyQuickTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        publishState()
    }

    override fun onClick() {
        super.onClick()
        val coordinator = (application as ProxyXApplication).appContainer.benchmarkCoordinator
        when (coordinator.state.value.status) {
            BenchmarkRunStatus.RUNNING -> coordinator.pause()
            BenchmarkRunStatus.PAUSED -> coordinator.resume()
            else -> BenchmarkExecution.start(
                context = this,
                count = QUICK_BENCHMARK_COUNT,
                runTelegramFinalistTransfers = false
            )
        }
        publishState()
    }

    private fun publishState() {
        val state = (application as? ProxyXApplication)
            ?.appContainer
            ?.benchmarkCoordinator
            ?.state
            ?.value
        qsTile?.apply {
            label = when (state?.status) {
                BenchmarkRunStatus.RUNNING -> "Pause ProxyX"
                BenchmarkRunStatus.PAUSED -> "Resume ProxyX"
                else -> "Test top 20"
            }
            this.state = if (state?.isActive == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    private companion object {
        const val QUICK_BENCHMARK_COUNT = 20
    }
}
