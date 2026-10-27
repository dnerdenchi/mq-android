package com.example.glorytun

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Wi-Fi-only transport helpers. Pair & Share is intentionally LAN-scoped. */
object PairShareNetwork {
    fun activeWifi(context: Context): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.firstOrNull { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            isLanWifiCandidate(
                hasWifiTransport = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                isNotVpn = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true,
                hasLocalIpv4 = localIpv4(context, network) != null,
            )
        }
    }

    internal fun isLanWifiCandidate(
        hasWifiTransport: Boolean,
        isNotVpn: Boolean,
        hasLocalIpv4: Boolean,
    ): Boolean = hasWifiTransport && isNotVpn && hasLocalIpv4

    /**
     * Returns an underlying cellular network rather than the app's VPN default
     * network. PairBond uses this to make each paired device a distinct SIM
     * egress path.
     */
    fun activeCellular(context: Context): Network? {
        return activeInternetNetwork(context, NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private fun activeInternetNetwork(context: Context, transport: Int): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.let { capabilities ->
                capabilities.hasTransport(transport) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            } == true
        }
    }

    fun openCellularSocket(context: Context, host: String, port: Int): Socket {
        require(port in 1..65535)
        val network = activeCellular(context)
            ?: throw IOException("利用可能なモバイル回線がありません")
        return openNetworkSocket(network, host, port)
    }

    fun openWifiSocket(context: Context, host: String, port: Int): Socket {
        require(port in 1..65535)
        val network = activeWifi(context)
            ?: throw IOException("利用可能なWi-Fi回線がありません")
        return openNetworkSocket(network, host, port)
    }

    private fun openNetworkSocket(network: Network, host: String, port: Int): Socket {
        val address = network.getAllByName(host)
            .firstOrNull(::isPublicInternetAddress)
            ?: throw IOException("PairBond サーバーはパブリックアドレスである必要があります")
        val socket = network.socketFactory.createSocket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(address, port), CELLULAR_CONNECT_TIMEOUT_MILLIS)
            return socket
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    fun localIpv4(context: Context, network: Network): Inet4Address? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.getLinkProperties(network)
            ?.linkAddresses
            ?.asSequence()
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    }

    fun wifiNetworkRequest(): NetworkRequest = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .build()

    /** Tracks network changes while a socket is being opened or already bound. */
    internal class LeaseNetworkState<T : Any, S : Closeable>(initial: T? = null) {
        private var selected: T? = initial
        private var bound: T? = null
        private var socket: S? = null
        private var closed = false

        @Synchronized
        fun onAvailable(network: T): S? {
            if (closed) return null
            selected = network
            if (bound != null && bound != network) {
                bound = null
                return socket.also { socket = null }
            }
            return null
        }

        @Synchronized
        fun onLost(network: T): S? {
            if (selected == network) selected = null
            if (bound == network) {
                bound = null
                return socket.also { socket = null }
            }
            return null
        }

        @Synchronized
        fun current(): T? = selected

        @Synchronized
        fun accept(network: T, candidate: S): Boolean {
            if (closed || selected != network) return false
            bound = network
            socket = candidate
            return true
        }

        @Synchronized
        fun isBoundAvailable(): Boolean = !closed && bound != null && bound == selected

        @Synchronized
        fun isClosed(): Boolean = closed

        @Synchronized
        fun close(): S? {
            closed = true
            selected = null
            bound = null
            return socket.also { socket = null }
        }
    }

    /** Keeps an internet transport available even when another is the default route. */
    class InternetLease(
        context: Context,
        transport: Int,
        private val label: String,
    ) : Closeable {
        private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        private val state = LeaseNetworkState<Network, Socket>(activeInternetNetwork(context, transport))

        private val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(available: Network) {
                state.onAvailable(available)?.let { runCatching { it.close() } }
            }

            override fun onLost(lost: Network) {
                state.onLost(lost)?.let { runCatching { it.close() } }
            }
        }

        init {
            manager.requestNetwork(
                NetworkRequest.Builder()
                    .addTransportType(transport)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                callback,
            )
        }

        fun openSocket(host: String, port: Int): Socket {
            require(port in 1..65535)
            val deadline = SystemClock.elapsedRealtime() + CELLULAR_CONNECT_TIMEOUT_MILLIS
            var selected = state.current()
            while (selected == null && !state.isClosed() && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(NETWORK_POLL_MILLIS)
                selected = state.current()
            }
            val selectedNetwork = selected ?: throw IOException("自端末の${label}回線を開始できません")
            val socket = openNetworkSocket(selectedNetwork, host, port)
            if (!state.accept(selectedNetwork, socket)) {
                runCatching { socket.close() }
                throw IOException("自端末の${label}回線が切断されました")
            }
            return socket
        }

        fun hasSelectedNetwork(): Boolean = state.current() != null

        fun isBoundNetworkAvailable(): Boolean = state.isBoundAvailable()

        override fun close() {
            state.close()?.let { runCatching { it.close() } }
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }

    /** Prevent a paired device from using this feature as an internal-network pivot. */
    fun isPublicInternetAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }

        val bytes = address.address
        // IPv6 unique-local addresses (fc00::/7) are not covered consistently by isSiteLocalAddress.
        if (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return false
        // IPv4 carrier-grade NAT and documentation/reserved ranges are not valid public egress targets.
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            if (first == 0 || first == 10 || first == 127 || first >= 224) return false
            if (first == 100 && second in 64..127) return false
            if (first == 169 && second == 254) return false
            if (first == 172 && second in 16..31) return false
            if (first == 192 && second == 168) return false
        }
        return true
    }

    private const val CELLULAR_CONNECT_TIMEOUT_MILLIS = 15_000
    private const val NETWORK_POLL_MILLIS = 50L
}
