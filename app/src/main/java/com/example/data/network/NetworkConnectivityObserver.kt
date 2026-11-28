package com.example.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class NetworkStatus(
    val isConnected: Boolean = true,
    val transportLabel: String = "Online",
    val wifiConnected: Boolean = false,
    val mobileDataConnected: Boolean = false,
    val hasValidatedInternet: Boolean = true
)

open class NetworkConnectivityObserver(
    context: Context,
    private val internetReachabilityProbe: (suspend () -> Boolean)? = null
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    open fun currentStatus(): NetworkStatus {
        val cm = connectivityManager
            ?: return NetworkStatus(isConnected = true, transportLabel = "Online", hasValidatedInternet = true)
        return try {
            val active = cm.activeNetwork
            @Suppress("DEPRECATION")
            val allNets = runCatching { cm.allNetworks.toList() }.getOrElse { emptyList() }
            val networks = buildList {
                if (active != null) add(active)
                allNets.forEach { net -> if (net !in this) add(net) }
            }
            if (networks.isEmpty()) {
                return NetworkStatus(
                    isConnected = false,
                    transportLabel = "Offline",
                    wifiConnected = false,
                    mobileDataConnected = false,
                    hasValidatedInternet = false
                )
            }

            var wifiUp = false
            var mobileUp = false
            var ethUp = false
            var otherUp = false
            var validated = false

            networks.forEach { net ->
                val caps = runCatching { cm.getNetworkCapabilities(net) }.getOrNull() ?: return@forEach
                if (!hasUsableInternetCapability(caps)) return@forEach
                val isNetValidated = isValidatedCapability(caps)
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    wifiUp = true
                    if (isNetValidated) validated = true
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    mobileUp = true
                    if (isNetValidated) validated = true
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    ethUp = true
                    if (isNetValidated) validated = true
                }
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) {
                    otherUp = true
                    if (isNetValidated) validated = true
                }
            }

            val anyTransport = wifiUp || mobileUp || ethUp || otherUp
            if (!anyTransport) {
                return NetworkStatus(
                    isConnected = false,
                    transportLabel = "Offline",
                    wifiConnected = false,
                    mobileDataConnected = false,
                    hasValidatedInternet = false
                )
            }

            val label = when {
                wifiUp && mobileUp -> "Wi-Fi + Mobile Data"
                wifiUp -> "Wi-Fi"
                mobileUp -> "Mobile Data"
                ethUp -> "Ethernet"
                else -> "Online"
            }

            NetworkStatus(
                isConnected = true,
                transportLabel = label,
                wifiConnected = wifiUp,
                mobileDataConnected = mobileUp,
                hasValidatedInternet = validated || anyTransport
            )
        } catch (_: SecurityException) {
            NetworkStatus(isConnected = true, transportLabel = "Online", hasValidatedInternet = true)
        } catch (_: Exception) {
            NetworkStatus(
                isConnected = false,
                transportLabel = "Offline",
                wifiConnected = false,
                mobileDataConnected = false,
                hasValidatedInternet = false
            )
        }
    }

    open suspend fun verifyInternetAccess(): Boolean {
        val status = currentStatus()
        if (!status.isConnected) return false
        val probe = internetReachabilityProbe
        if (probe != null) {
            return runCatching { probe() }.getOrDefault(false)
        }
        return true
    }

    open fun observe(): Flow<NetworkStatus> = callbackFlow {
        val cm = connectivityManager
        if (cm == null) {
            trySend(NetworkStatus(isConnected = true, transportLabel = "Online", hasValidatedInternet = true))
            awaitClose { }
            return@callbackFlow
        }

        trySend(currentStatus())

        val defaultCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentStatus())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                trySend(currentStatus())
            }

            override fun onLost(network: Network) {
                trySend(currentStatus())
            }

            override fun onUnavailable() {
                trySend(currentStatus())
            }
        }

        val wifiCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentStatus())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                trySend(currentStatus())
            }

            override fun onLost(network: Network) {
                trySend(currentStatus())
            }
        }

        val cellularCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(currentStatus())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                trySend(currentStatus())
            }

            override fun onLost(network: Network) {
                trySend(currentStatus())
            }
        }

        val defaultRegistered = runCatching {
            cm.registerDefaultNetworkCallback(defaultCallback)
            true
        }.getOrDefault(false)

        val wifiRegistered = runCatching {
            val wifiRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(wifiRequest, wifiCallback)
            true
        }.getOrDefault(false)

        val cellularRegistered = runCatching {
            val cellularRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(cellularRequest, cellularCallback)
            true
        }.getOrDefault(false)

        awaitClose {
            if (defaultRegistered) runCatching { cm.unregisterNetworkCallback(defaultCallback) }
            if (wifiRegistered) runCatching { cm.unregisterNetworkCallback(wifiCallback) }
            if (cellularRegistered) runCatching { cm.unregisterNetworkCallback(cellularCallback) }
        }
    }.distinctUntilChanged()

    private fun hasUsableInternetCapability(caps: NetworkCapabilities): Boolean {
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        ) {
            return false
        }
        return true
    }

    private fun isValidatedCapability(caps: NetworkCapabilities): Boolean {
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        const val NO_INTERNET_POPUP_MESSAGE = "No Internet — Please check your internet connection."
    }
}
