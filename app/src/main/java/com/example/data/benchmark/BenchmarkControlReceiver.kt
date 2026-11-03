package com.example.data.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ProxyXApplication

class BenchmarkControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val coordinator = (context.applicationContext as? ProxyXApplication)
            ?.appContainer
            ?.benchmarkCoordinator
            ?: return
        when (intent.action) {
            ACTION_PAUSE -> coordinator.pause()
            ACTION_RESUME -> coordinator.resume()
            ACTION_CANCEL -> {
                coordinator.cancel()
                BenchmarkExecution.cancelScheduled(context)
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.example.action.UIDT_PAUSE"
        const val ACTION_RESUME = "com.example.action.UIDT_RESUME"
        const val ACTION_CANCEL = "com.example.action.UIDT_CANCEL"
    }
}
