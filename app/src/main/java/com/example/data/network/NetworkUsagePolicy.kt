package com.example.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class NetworkUsagePolicy(context: Context) {
    private val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun requireAllowed(unmeteredOnly: Boolean) {
        val manager = connectivityManager ?: throw UserNetworkUnavailableException()
        val network = manager.activeNetwork ?: throw UserNetworkUnavailableException()
        val capabilities = manager.getNetworkCapabilities(network)
            ?: throw UserNetworkUnavailableException()
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            throw UserNetworkUnavailableException()
        }
        if (unmeteredOnly &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        ) {
            throw MeteredNetworkNotAllowedException()
        }
    }
}

class MeteredNetworkNotAllowedException : UserNetworkException(
    "This operation requires an unmetered active network."
)
