package com.example.data.benchmark

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

data class DeviceWorkload(
    val cpuCores: Int,
    val memoryClassMb: Int,
    val batteryTemperatureC: Float?,
    val isCharging: Boolean,
    val networkType: String,
    val isMetered: Boolean
)

object AdaptiveWorkerPolicy {
    fun select(configuredMaximum: Int, workload: DeviceWorkload): Int {
        val maximum = configuredMaximum.coerceIn(1, MAX_WORKERS)
        var workers = when {
            workload.cpuCores >= 8 && workload.memoryClassMb >= 384 -> 12
            workload.cpuCores >= 6 && workload.memoryClassMb >= 256 -> 8
            workload.cpuCores >= 4 && workload.memoryClassMb >= 192 -> 6
            else -> 3
        }
        if (workload.networkType == "CELLULAR" || workload.isMetered) {
            workers = minOf(workers, 6)
        }
        val temperature = workload.batteryTemperatureC
        if (temperature != null) {
            workers = when {
                temperature >= CRITICAL_TEMPERATURE_C -> minOf(workers, 2)
                temperature >= HOT_TEMPERATURE_C -> minOf(workers, 4)
                else -> workers
            }
        }
        if (!workload.isCharging && workload.memoryClassMb < 192) {
            workers = minOf(workers, 3)
        }
        return workers.coerceIn(1, maximum)
    }

    private const val MAX_WORKERS = 16
    private const val HOT_TEMPERATURE_C = 42f
    private const val CRITICAL_TEMPERATURE_C = 46f
}

class AdaptiveWorkerSelector(context: Context) {
    private val appContext = context.applicationContext

    fun select(configuredMaximum: Int, adaptive: Boolean): Int {
        val maximum = configuredMaximum.coerceIn(1, 16)
        return if (adaptive) AdaptiveWorkerPolicy.select(maximum, readWorkload()) else maximum
    }

    internal fun readWorkload(): DeviceWorkload {
        val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE)
            as? ActivityManager
        val battery = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val rawTemperature = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager
        val capabilities = connectivityManager?.activeNetwork?.let {
            connectivityManager.getNetworkCapabilities(it)
        }
        val networkType = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "VPN"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
            else -> "OTHER"
        }
        return DeviceWorkload(
            cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            memoryClassMb = activityManager?.memoryClass ?: 128,
            batteryTemperatureC = rawTemperature.takeIf { it > 0 }?.div(10f),
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
            networkType = networkType,
            isMetered = capabilities?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_NOT_METERED
            ) != true
        )
    }
}
