package com.example.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class NetworkStatus(
    val isConnected: Boolean = true,
    val transportLabel: String = "Online"
)

class NetworkConnectivityObserver(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun currentStatus(): NetworkStatus {
        val cm = connectivityManager ?: return NetworkStatus(isConnected = true, transportLabel = "Online")
        return try {
            val activeNetwork = cm.activeNetwork
            val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
            statusFromCapabilities(capabilities)
        } catch (_: SecurityException) {
            NetworkStatus(isConnected = true, transportLabel = "Online")
        } catch (_: Exception) {
            NetworkStatus(isConnected = false, transportLabel = "Offline")
        }
    }

    fun observe(): Flow<NetworkStatus> = callbackFlow {
        val cm = connectivityManager
        if (cm == null) {
            trySend(NetworkStatus(isConnected = true, transportLabel = "Online"))
            awaitClose { }
            return@callbackFlow
        }

        trySend(currentStatus())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
                trySend(statusFromCapabilities(caps, assumeConnected = true))
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                trySend(statusFromCapabilities(networkCapabilities))
            }

            override fun onLost(network: Network) {
                trySend(currentStatus())
            }

            override fun onUnavailable() {
                trySend(NetworkStatus(isConnected = false, transportLabel = "Offline"))
            }
        }

        val registered = try {
            cm.registerDefaultNetworkCallback(callback)
            true
        } catch (_: Exception) {
            false
        }

        awaitClose {
            if (registered) {
                runCatching { cm.unregisterNetworkCallback(callback) }
            }
        }
    }.distinctUntilChanged()

    private fun statusFromCapabilities(
        capabilities: NetworkCapabilities?,
        assumeConnected: Boolean = false
    ): NetworkStatus {
        if (capabilities == null) {
            return if (assumeConnected) {
                NetworkStatus(isConnected = true, transportLabel = "Online")
            } else {
                NetworkStatus(isConnected = false, transportLabel = "Offline")
            }
        }
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!hasInternet && !assumeConnected) {
            return NetworkStatus(isConnected = false, transportLabel = "Offline")
        }
        val label = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Online"
        }
        return NetworkStatus(isConnected = true, transportLabel = label)
    }
}
