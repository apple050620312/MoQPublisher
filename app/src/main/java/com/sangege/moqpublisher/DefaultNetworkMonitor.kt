package com.sangege.moqpublisher

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.util.concurrent.atomic.AtomicBoolean

/** Re-dial on a different default network, without binding sockets or changing system routing. */
internal class DefaultNetworkMonitor(context: Context, private val changed: () -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val active = AtomicBoolean(true)
    private var previous: Network? = manager.activeNetwork
    private var seenNetwork = previous != null
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val notify = synchronized(this@DefaultNetworkMonitor) {
                val different = seenNetwork && network != previous
                previous = network; seenNetwork = true
                active.get() && different
            }
            if (notify) changed()
        }
        override fun onLost(network: Network) {
            // Native QUIC detects the loss and supplies exponential backoff. onAvailable
            // above starts a fresh MoQ session as soon as the replacement route exists.
        }
    }

    init { manager.registerDefaultNetworkCallback(callback); registered = true }

    override fun close() {
        active.set(false)
        if (registered) { registered = false; runCatching { manager.unregisterNetworkCallback(callback) } }
    }
}
