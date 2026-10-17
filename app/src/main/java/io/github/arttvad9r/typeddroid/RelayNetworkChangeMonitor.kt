package io.github.arttvad9r.typeddroid

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network

internal interface RelayNetworkChangeMonitor {
    fun start(onNetworkChanged: () -> Unit)
    fun close()
}

internal class AndroidRelayNetworkChangeMonitor(context: Context) : RelayNetworkChangeMonitor {
    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var currentNetwork: Network? = connectivityManager.activeNetwork
    private var listener: (() -> Unit)? = null

    override fun start(onNetworkChanged: () -> Unit) {
        synchronized(lock) {
            if (callback != null) return
            listener = onNetworkChanged
            currentNetwork = connectivityManager.activeNetwork
            val registered = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val changed = synchronized(lock) {
                        if (network == currentNetwork) false else {
                            currentNetwork = network
                            true
                        }
                    }
                    if (changed) listener?.invoke()
                }

                override fun onLost(network: Network) {
                    val changed = synchronized(lock) {
                        if (network != currentNetwork) false else {
                            currentNetwork = null
                            true
                        }
                    }
                    if (changed) listener?.invoke()
                }
            }
            callback = registered
            connectivityManager.registerDefaultNetworkCallback(registered)
        }
    }

    override fun close() {
        val registered = synchronized(lock) {
            listener = null
            callback.also { callback = null }
        } ?: return
        runCatching { connectivityManager.unregisterNetworkCallback(registered) }
    }
}
