package com.example.di

import android.content.Context
import android.net.ConnectivityManager
import com.example.data.database.ProxyDatabase
import com.example.data.benchmark.AdaptiveWorkerSelector
import com.example.data.benchmark.BenchmarkCoordinator
import com.example.data.network.NetworkUsagePolicy
import com.example.data.network.NetworkSpeedMeter
import com.example.data.network.ProxyCollector
import com.example.data.network.ProxyCountryResolver
import com.example.data.network.ProxyVerifier
import com.example.data.repository.ProxyRepository
import com.example.data.repository.SettingsRepository
import com.example.data.telegram.TdlibTelegramTransferEngine

class AppContainer(private val context: Context) {
    private val genericDatabase by lazy { com.example.data.generic.GenericProxyDatabase.open(context) }
    val genericRepository by lazy { com.example.data.generic.GenericProxyRepository(context, genericDatabase.dao()) }
    private val database: ProxyDatabase by lazy {
        ProxyDatabase.getDatabase(context)
    }

    val proxyDao by lazy {
        database.proxyDao()
    }

    val sourceHealthDao by lazy {
        database.sourceHealthDao()
    }

    val collector by lazy {
        ProxyCollector(context, sourceHealthDao)
    }

    val verifier by lazy {
        ProxyVerifier(context)
    }

    val countryResolver by lazy {
        ProxyCountryResolver(context)
    }

    private val networkSpeedMeter by lazy {
        NetworkSpeedMeter(
            context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        )
    }

    val proxyRepository by lazy {
        ProxyRepository(
            proxyDao = proxyDao,
            collector = collector,
            verifier = verifier,
            countryResolver = countryResolver,
            networkSpeedMeter = networkSpeedMeter,
            sourceHealthDao = sourceHealthDao
        )
    }

    val settingsRepository by lazy {
        SettingsRepository(context)
    }

    val telegramTransferEngine by lazy {
        TdlibTelegramTransferEngine(context)
    }

    val benchmarkCoordinator by lazy {
        BenchmarkCoordinator(
            repository = proxyRepository,
            settingsRepository = settingsRepository,
            workerSelector = AdaptiveWorkerSelector(context),
            networkUsagePolicy = NetworkUsagePolicy(context),
            telegramTransferEngine = telegramTransferEngine
        )
    }
}
