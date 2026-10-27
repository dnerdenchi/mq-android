package com.example.glorytun

internal data class PairBondDashboardTrafficUpdate(
    val totals: NetworkTrafficTotals,
    val wifiDeltaKB: Float,
    val simDeltaKB: Float,
    val wifiDeltaBytes: Long,
    val simDeltaBytes: Long,
    val bluetoothDeltaBytes: Long,
    val ethernetDeltaBytes: Long,
    val pairShareDeltaBytes: Long,
    val sampleDurationNanos: Long,
    val wifiBps: Long,
    val simBps: Long,
    val bluetoothBps: Long,
    val ethernetBps: Long,
    val pairShareBps: Long,
    val receivingPeerNames: List<String>,
)

/** Converts PairBond's cumulative per-path counters into one-second dashboard samples. */
internal class PairBondDashboardTrafficAccumulator {
    private data class Counter(val tx: Long, val rx: Long)

    private val previousById = mutableMapOf<String, Counter>()
    private var previousSampleMillis: Long? = null
    private var cumulativeWifiTx = 0L
    private var cumulativeWifiRx = 0L
    private var cumulativeSimTx = 0L
    private var cumulativeSimRx = 0L

    fun update(
        stats: Collection<PairSharePeerStats>,
        peerNames: Map<String, String>,
        nowMillis: Long,
    ): PairBondDashboardTrafficUpdate {
        var wifiDeltaTx = 0L
        var wifiDeltaRx = 0L
        var simDeltaTx = 0L
        var simDeltaRx = 0L
        var bluetoothDelta = 0L
        var ethernetDelta = 0L
        var pairDelta = 0L
        val activeIds = mutableSetOf<String>()
        val receivingNames = linkedSetOf<String>()

        stats.forEach { path ->
            activeIds += path.peerId
            val current = Counter(path.txBytes.coerceAtLeast(0L), path.rxBytes.coerceAtLeast(0L))
            val previous = previousById[path.peerId] ?: current
            val deltaTx = (current.tx - previous.tx).coerceAtLeast(0L)
            val deltaRx = (current.rx - previous.rx).coerceAtLeast(0L)
            when (path.peerId) {
                PairBondLocalPath.WIFI_ID -> {
                    wifiDeltaTx += deltaTx
                    wifiDeltaRx += deltaRx
                }
                PairBondLocalPath.CELLULAR_ID -> {
                    simDeltaTx += deltaTx
                    simDeltaRx += deltaRx
                }
                PairBondLocalPath.BLUETOOTH_ID -> bluetoothDelta += deltaTx + deltaRx
                PairBondLocalPath.ETHERNET_ID -> ethernetDelta += deltaTx + deltaRx
                else -> {
                    pairDelta += deltaTx + deltaRx
                    if (path.pathReady) peerNames[path.peerId]?.let(receivingNames::add)
                }
            }
            previousById[path.peerId] = current
        }
        previousById.keys.retainAll(activeIds)

        cumulativeWifiTx += wifiDeltaTx
        cumulativeWifiRx += wifiDeltaRx
        cumulativeSimTx += simDeltaTx
        cumulativeSimRx += simDeltaRx

        val elapsedMillis = previousSampleMillis
            ?.let { (nowMillis - it).takeIf { elapsed -> elapsed > 0L } }
        previousSampleMillis = nowMillis

        fun toBitsPerSecond(bytes: Long): Long = if (elapsedMillis == null) {
            0L
        } else {
            bytes.coerceAtLeast(0L) * 8_000L / elapsedMillis
        }

        return PairBondDashboardTrafficUpdate(
            totals = NetworkTrafficTotals(
                wifiTx = cumulativeWifiTx,
                wifiRx = cumulativeWifiRx,
                wifiActive = stats.any { it.peerId == PairBondLocalPath.WIFI_ID && it.pathReady },
                simTx = cumulativeSimTx,
                simRx = cumulativeSimRx,
                simActive = stats.any { it.peerId == PairBondLocalPath.CELLULAR_ID && it.pathReady },
                bluetoothActive = stats.any { it.peerId == PairBondLocalPath.BLUETOOTH_ID && it.pathReady },
                ethernetActive = stats.any { it.peerId == PairBondLocalPath.ETHERNET_ID && it.pathReady },
            ),
            wifiDeltaKB = (wifiDeltaTx + wifiDeltaRx) / 1024f,
            simDeltaKB = (simDeltaTx + simDeltaRx) / 1024f,
            wifiDeltaBytes = wifiDeltaTx + wifiDeltaRx,
            simDeltaBytes = simDeltaTx + simDeltaRx,
            bluetoothDeltaBytes = bluetoothDelta,
            ethernetDeltaBytes = ethernetDelta,
            pairShareDeltaBytes = pairDelta,
            sampleDurationNanos = (elapsedMillis ?: 0L) * 1_000_000L,
            wifiBps = toBitsPerSecond(wifiDeltaTx + wifiDeltaRx),
            simBps = toBitsPerSecond(simDeltaTx + simDeltaRx),
            bluetoothBps = toBitsPerSecond(bluetoothDelta),
            ethernetBps = toBitsPerSecond(ethernetDelta),
            pairShareBps = toBitsPerSecond(pairDelta),
            receivingPeerNames = receivingNames.toList(),
        )
    }

    fun reset() {
        previousById.clear()
        previousSampleMillis = null
        cumulativeWifiTx = 0L
        cumulativeWifiRx = 0L
        cumulativeSimTx = 0L
        cumulativeSimRx = 0L
    }
}
