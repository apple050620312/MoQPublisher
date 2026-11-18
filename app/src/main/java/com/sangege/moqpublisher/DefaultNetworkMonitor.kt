package com.sangege.moqpublisher

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/** Re-dial on a different default network, without binding sockets or changing system routing. */
internal class DefaultNetworkMonitor(context: Context, private val changed: () -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val active = AtomicBoolean(true)
    private var previous: Network? = manager.activeNetwork
    private var addresses: Set<InetAddress> = emptySet()
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val notify = synchronized(this@DefaultNetworkMonitor) {
                val different = network != previous
                if (network != previous) addresses = emptySet()
                previous = network
                active.get() && different
            }
            if (notify) changed()
        }
        override fun onLost(network: Network) {
            synchronized(this@DefaultNetworkMonitor) {
                if (network == previous) {
                    previous = null
                    addresses = emptySet()
                }
            }
        }

        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            val updated = properties.linkAddresses.map { it.address }
                .filterNot { it.isLinkLocalAddress || it.isLoopbackAddress }.toSet()
            val notify = synchronized(this@DefaultNetworkMonitor) {
                if (!active.get() || network != previous || updated.isEmpty()) return
                val different = defaultNetworkAddressReplaced(addresses, updated)
                addresses = updated
                different
            }
            if (notify) changed()
        }
    }

    init { manager.registerDefaultNetworkCallback(callback); registered = true }

    override fun close() {
        active.set(false)
        if (registered) { registered = false; runCatching { manager.unregisterNetworkCallback(callback) } }
    }
}

internal fun defaultNetworkAddressReplaced(previous: Set<InetAddress>, current: Set<InetAddress>): Boolean {
    if (previous.isEmpty() || current.isEmpty()) return false
    val before = previous.filterIsInstance<Inet4Address>().ifEmpty { previous }
    return before.none { it in current }
}
