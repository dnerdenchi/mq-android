package com.example.glorytun

import com.mqvpn.sdk.core.model.PathInfo

data class NetworkTrafficTotals(
    val wifiTx: Long,
    val wifiRx: Long,
    val wifiActive: Boolean,
    val simTx: Long,
    val simRx: Long,
    val simActive: Boolean,
    val wifiPathActive: Boolean = wifiActive,
    val bluetoothActive: Boolean = false,
    val ethernetActive: Boolean = false,
) {
    val wifiTotal: Long get() = wifiTx + wifiRx
    val simTotal: Long get() = simTx + simRx
}

data class PathTrafficUpdate(
    val totals: NetworkTrafficTotals,
    val wifiDeltaKB: Float,
    val simDeltaKB: Float
)

class PathTrafficAccumulator {
    private val previousByHandle = mutableMapOf<Long, PathCounter>()
    private var cumulativeWifiTx = 0L
    private var cumulativeWifiRx = 0L
    private var cumulativeSimTx = 0L
    private var cumulativeSimRx = 0L

    fun update(paths: List<PathInfo>): PathTrafficUpdate {
        var wifiDeltaBytes = 0L
        var simDeltaBytes = 0L
        var wifiActive = false
        var simActive = false
        var wifiPathActive = false
        var bluetoothActive = false
        var ethernetActive = false
        val activeHandles = mutableSetOf<Long>()

        paths.forEach { path ->
            val group = path.transportGroup()
            val isPathAvailable = path.status != PathInfo.STATUS_CLOSED
            if (isPathAvailable) {
                activeHandles.add(path.handle)
                if (group == TransportGroup.WIFI) {
                    wifiActive = true
                } else {
                    simActive = true
                }
                wifiPathActive = wifiPathActive || isWifiInterface(path.iface)
                bluetoothActive = bluetoothActive || isBluetoothInterface(path.iface)
                ethernetActive = ethernetActive || isEthernetInterface(path.iface)
            }

            val current = PathCounter(
                tx = path.bytesTx.coerceAtLeast(0L),
                rx = path.bytesRx.coerceAtLeast(0L)
            )
            val previous = previousByHandle[path.handle]
            if (previous != null) {
                val deltaTx = (current.tx - previous.tx).coerceAtLeast(0L)
                val deltaRx = (current.rx - previous.rx).coerceAtLeast(0L)
                if (group == TransportGroup.WIFI) {
                    cumulativeWifiTx += deltaTx
                    cumulativeWifiRx += deltaRx
                    wifiDeltaBytes += deltaTx + deltaRx
                } else {
                    cumulativeSimTx += deltaTx
                    cumulativeSimRx += deltaRx
                    simDeltaBytes += deltaTx + deltaRx
                }
            }
            if (isPathAvailable) {
                previousByHandle[path.handle] = current
            } else {
                previousByHandle.remove(path.handle)
            }
        }

        previousByHandle.keys.retainAll(activeHandles)

        return PathTrafficUpdate(
            totals = NetworkTrafficTotals(
                wifiTx = cumulativeWifiTx,
                wifiRx = cumulativeWifiRx,
                wifiActive = wifiActive,
                simTx = cumulativeSimTx,
                simRx = cumulativeSimRx,
                simActive = simActive,
                wifiPathActive = wifiPathActive,
                bluetoothActive = bluetoothActive,
                ethernetActive = ethernetActive,
            ),
            wifiDeltaKB = wifiDeltaBytes / 1024f,
            simDeltaKB = simDeltaBytes / 1024f
        )
    }

    fun reset() {
        previousByHandle.clear()
        cumulativeWifiTx = 0L
        cumulativeWifiRx = 0L
        cumulativeSimTx = 0L
        cumulativeSimRx = 0L
    }

    private fun PathInfo.transportGroup(): TransportGroup {
        return if (isCellularInterface(iface)) TransportGroup.CELLULAR else TransportGroup.WIFI
    }

    private data class PathCounter(
        val tx: Long,
        val rx: Long
    )

    private enum class TransportGroup {
        WIFI,
        CELLULAR
    }
}

internal fun isCellularInterface(iface: String): Boolean {
    val lower = iface.lowercase()
    return lower.startsWith("cellular") ||
        lower.startsWith("rmnet") ||
        lower.startsWith("ccmni") ||
        lower.startsWith("wwan") ||
        lower.startsWith("pdp")
}

internal fun isWifiInterface(iface: String): Boolean {
    val lower = iface.lowercase()
    return lower.startsWith("wifi") || lower.startsWith("wlan") ||
        lower.startsWith("wlp") || lower.startsWith("wl")
}

internal fun isBluetoothInterface(iface: String): Boolean {
    val lower = iface.lowercase()
    return lower.startsWith("bluetooth") || lower.startsWith("bnep") ||
        lower.startsWith("bt-pan") || lower.startsWith("pan")
}

internal fun isEthernetInterface(iface: String): Boolean {
    val lower = iface.lowercase()
    return lower.startsWith("ethernet") || lower.startsWith("usb") ||
        lower.startsWith("rndis") || lower.startsWith("eth") || lower.startsWith("en")
}
