package com.example.glorytun

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import java.util.Calendar

data class MqvpnTrafficStats(
    val txBytes: Long = 0L,
    val rxBytes: Long = 0L,
    val available: Boolean = false,
) {
    val totalBytes: Long get() = txBytes + rxBytes
}

data class PathSpeedSnapshot(
    val wifiActive: Boolean = false,
    val simActive: Boolean = false,
    val bluetoothActive: Boolean = false,
    val ethernetActive: Boolean = false,
    val wifiBps: Long = 0L,
    val simBps: Long = 0L,
    val bluetoothBps: Long = 0L,
    val ethernetBps: Long = 0L,
)

data class ServerCheckEntry(
    val checkedAt: Long,
    val reachable: Boolean,
    val detail: String,
    val rttMs: Long
)

class VpnViewModel(application: Application) : AndroidViewModel(application) {

    val trafficDataStore = TrafficDataStore(application)
    val historicalPoints = mutableListOf<TrafficPoint>()
    val trafficHistory = mutableListOf<TrafficPoint>()
    private val historyReconciler = TrafficHistoryReconciler(historicalPoints, trafficHistory)

    val realtimeWifiRates = ArrayDeque<Float>()
    val realtimeSimRates = ArrayDeque<Float>()
    val realtimePairShareRates = ArrayDeque<Float>()
    val realtimeBluetoothRates = ArrayDeque<Float>()
    val realtimeEthernetRates = ArrayDeque<Float>()
    val realtimeTotalRates = ArrayDeque<Float>()
    val realtimeUpdated = MutableLiveData(0L)

    val connectionState = MutableLiveData(ConnectionStates.DISCONNECTED)

    val wifiKBs = MutableLiveData(0f)
    val simKBs = MutableLiveData(0f)
    val pairShareKBs = MutableLiveData(0f)
    val pairSharePeerNames = MutableLiveData<List<String>>(emptyList())
    val pathSpeeds = MutableLiveData(PathSpeedSnapshot())
    val mqvpnStats = MutableLiveData(MqvpnTrafficStats())
    val wifiTotalBytes = MutableLiveData(0L)
    val simTotalBytes = MutableLiveData(0L)

    val wifiDailyBytes = MutableLiveData(0L)
    val simDailyBytes = MutableLiveData(0L)
    val totalDailyBytes = MutableLiveData(0L)

    val wifiThrottled = MutableLiveData(false)
    val simThrottled = MutableLiveData(false)

    val maxWifiKBs = MutableLiveData(0f)
    val maxSimKBs = MutableLiveData(0f)

    val serverIp = MutableLiveData("")
    val serverPort = MutableLiveData(MqvpnConfigFactory.DEFAULT_PORT)
    val serverCheckCache = mutableMapOf<String, ServerCheckEntry>()

    private val trafficRateCalculator = TrafficRateCalculator()
    private var previousTrafficNanos: Long? = null

    init {
        historicalPoints.addAll(trafficDataStore.load())

        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        var dWifi = 0L
        var dSim = 0L
        var dTotal = 0L
        for (point in historicalPoints) {
            if (point.timestamp >= todayStart) {
                dWifi += point.measuredWifiBytes
                dSim += point.measuredSimBytes
                dTotal += point.measuredTotalBytes
            }
        }
        wifiDailyBytes.value = dWifi
        simDailyBytes.value = dSim
        totalDailyBytes.value = dTotal
    }

    fun addHistoricalPoint(
        point: TrafficPoint,
        sampleScopeId: String? = null,
        fromMillis: Long? = null,
        throughMillis: Long? = null,
    ) {
        historyReconciler.acceptCommitted(point, sampleScopeId, fromMillis, throughMillis)
        realtimeUpdated.value = System.currentTimeMillis()
    }

    fun updateTraffic(
        wifiActive: Boolean,
        simActive: Boolean,
        wifiTx: Long,
        wifiRx: Long,
        simTx: Long,
        simRx: Long,
        measuredWifiBps: Long? = null,
        measuredSimBps: Long? = null,
        measuredPairShareBps: Long = 0L,
        pairSharePeerNames: List<String> = emptyList(),
        measuredBluetoothBps: Long = 0L,
        measuredEthernetBps: Long = 0L,
        measuredTotalBps: Long? = null,
        sampleDurationNanos: Long? = null,
        sampleWifiBytes: Long? = null,
        sampleSimBytes: Long? = null,
        sampleTotalBytes: Long? = null,
        sampleTimeMillis: Long? = null,
        sampleScopeId: String? = null,
    ) {
        val nowMs = sampleTimeMillis ?: System.currentTimeMillis()
        val nowNanos = System.nanoTime()
        val elapsedNanos = sampleDurationNanos?.takeIf { it >= 0L }
            ?: previousTrafficNanos?.let { (nowNanos - it).coerceAtLeast(0L) }
            ?: 0L
        previousTrafficNanos = nowNanos
        val rates = trafficRateCalculator.update(
            nowMs = nowMs,
            wifiActive = wifiActive,
            simActive = simActive,
            wifiTx = wifiTx,
            wifiRx = wifiRx,
            simTx = simTx,
            simRx = simRx
        )

        // mqvpn サンプルが 0 でも別の推定値に置き換えない。
        val sampledWifiKBs = selectTrafficRate(rates.wifiKBs, measuredWifiBps)
        val sampledSimKBs = selectTrafficRate(rates.simKBs, measuredSimBps)
        val sampledPairShareKBs = measuredPairShareBps.coerceAtLeast(0L) / 8192f
        val sampledBluetoothKBs = measuredBluetoothBps.coerceAtLeast(0L) / 8192f
        val sampledEthernetKBs = measuredEthernetBps.coerceAtLeast(0L) / 8192f
        val sampledTotalKBs = measuredTotalBps?.coerceAtLeast(0L)?.div(8192f)
            ?: (sampledWifiKBs + sampledSimKBs + sampledBluetoothKBs + sampledEthernetKBs + sampledPairShareKBs)

        wifiKBs.value = sampledWifiKBs
        simKBs.value = sampledSimKBs
        pairShareKBs.value = sampledPairShareKBs
        this.pairSharePeerNames.value = pairSharePeerNames

        if (sampledWifiKBs > (maxWifiKBs.value ?: 0f)) maxWifiKBs.value = sampledWifiKBs
        if (sampledSimKBs > (maxSimKBs.value ?: 0f)) maxSimKBs.value = sampledSimKBs

        val elapsedMillis = elapsedNanos / 1_000_000L
        createTrafficSample(
            timestamp = nowMs,
            wifiKBs = sampledWifiKBs,
            simKBs = sampledSimKBs,
            totalKBs = sampledTotalKBs,
            durationMillis = elapsedMillis,
            wifiBytes = sampleWifiBytes,
            simBytes = sampleSimBytes,
            totalBytes = sampleTotalBytes,
            sampleScopeId = sampleScopeId,
        )?.let(historyReconciler::addLive)
        while (trafficHistory.size > MAX_TRAFFIC_HISTORY_POINTS) trafficHistory.removeAt(0)

        addRealtimePoint(
            sampledWifiKBs, sampledSimKBs, sampledPairShareKBs,
            sampledBluetoothKBs, sampledEthernetKBs, sampledTotalKBs,
        )

        wifiTotalBytes.value = rates.wifiTotalBytes
        simTotalBytes.value = rates.simTotalBytes
    }

    fun updatePathSpeeds(snapshot: PathSpeedSnapshot) {
        pathSpeeds.value = snapshot
    }

    fun updateMqvpnStats(txBytes: Long, rxBytes: Long) {
        mqvpnStats.value = MqvpnTrafficStats(txBytes.coerceAtLeast(0L), rxBytes.coerceAtLeast(0L), true)
    }

    fun addRealtimePoint(
        wifi: Float,
        sim: Float,
        pairShare: Float = 0f,
        bluetooth: Float = 0f,
        ethernet: Float = 0f,
        total: Float = wifi + sim + bluetooth + ethernet + pairShare,
    ) {
        fun append(queue: ArrayDeque<Float>, value: Float) {
            if (queue.size >= MAX_REALTIME_POINTS) queue.removeFirst()
            queue.addLast(value.coerceAtLeast(0f))
        }
        append(realtimeWifiRates, wifi)
        append(realtimeSimRates, sim)
        append(realtimePairShareRates, pairShare)
        append(realtimeBluetoothRates, bluetooth)
        append(realtimeEthernetRates, ethernet)
        append(realtimeTotalRates, total)
        realtimeUpdated.value = System.currentTimeMillis()
    }

    fun resetRealtimeData() {
        realtimeWifiRates.clear()
        realtimeSimRates.clear()
        realtimePairShareRates.clear()
        realtimeBluetoothRates.clear()
        realtimeEthernetRates.clear()
        realtimeTotalRates.clear()
        realtimeUpdated.postValue(0L)
    }

    fun resetTrafficBaselines(clearSessionHistory: Boolean = false) {
        trafficRateCalculator.reset()
        previousTrafficNanos = null
        wifiKBs.value = 0f
        simKBs.value = 0f
        pairShareKBs.value = 0f
        pairSharePeerNames.value = emptyList()
        pathSpeeds.value = PathSpeedSnapshot()
        mqvpnStats.value = MqvpnTrafficStats()
        wifiTotalBytes.value = 0L
        simTotalBytes.value = 0L
        if (clearSessionHistory) {
            trafficHistory.clear()
            resetRealtimeData()
        }
    }

    fun updateDailyTraffic(
        wifiKB: Double,
        simKB: Double,
        wThrottled: Boolean,
        sThrottled: Boolean,
        totalBytes: Long? = null,
    ) {
        wifiDailyBytes.value = (wifiKB * 1024).toLong()
        simDailyBytes.value = (simKB * 1024).toLong()
        totalDailyBytes.value = totalBytes?.coerceAtLeast(0L)
            ?: ((wifiKB + simKB) * 1024).toLong().coerceAtLeast(0L)
        wifiThrottled.value = wThrottled
        simThrottled.value = sThrottled
    }

    fun reset() {
        resetTrafficBaselines(clearSessionHistory = true)
        wifiThrottled.value = false
        simThrottled.value = false
    }

    fun formatBps(kbs: Float): String = formatBandwidthBps((kbs.coerceAtLeast(0f) * 8192f).toLong())

    fun formatBytes(bytes: Long): String = formatBandwidthBytes(bytes)

    private companion object {
        const val MAX_REALTIME_POINTS = 60
        const val MAX_TRAFFIC_HISTORY_POINTS = 86_400
    }
}
