package com.example.data.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BenchmarkBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            ProxyBenchmarkScheduler.enqueueInterruptedRecovery(context)
        }
    }
}
