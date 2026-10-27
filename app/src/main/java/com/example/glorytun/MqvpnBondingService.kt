package com.example.glorytun

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mqvpn.sdk.core.MqvpnVpnService
import com.mqvpn.sdk.core.model.MqvpnConfig
import com.mqvpn.sdk.core.model.MqvpnState
import com.mqvpn.sdk.core.model.PathInfo
import com.mqvpn.sdk.core.model.TunnelInfo
import com.mqvpn.sdk.core.model.VpnStats

class MqvpnBondingService : MqvpnVpnService() {

    private lateinit var hourlyTrafficRecorder: TrafficHourRecorder
    private lateinit var bandwidthEnforcer: BandwidthEnforcer
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsLock = Any()

    @Volatile private var latestPaths: List<PathInfo> = emptyList()
    @Volatile private var latestStats: VpnStats? = null
    @Volatile private var isTunnelRequested = false
    @Volatile private var isConnected = false

    private var statsRunning = false
    private val pathTrafficAccumulator = PathTrafficAccumulator()
    private val bandwidthHistory = BandwidthHistory(maxSamples = 1)
    private val mqvpnTrafficDelta = MqvpnTrafficDelta()
    private var dailyWifiKB = 0.0
    private var dailySimKB = 0.0
    private var dailyTotalBytes = 0L
    private var monthlyWifiKB = 0.0
    private var monthlySimKB = 0.0
    private var wifiLimited = false
    private var simLimited = false

    override fun onCreate() {
        super.onCreate()
        hourlyTrafficRecorder = TrafficHourRecorder(HourlyTrafficSink(this)::save)
        bandwidthEnforcer = BandwidthEnforcer(this, ::setPathRateLimits)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            GlorytunConstants.ACTION_CONNECT -> {
                runCatching {
                    connect(MqvpnConfigFactory.fromIntent(this, intent))
                }.onFailure { error ->
                    Log.e(TAG, "invalid mqvpn config: ${error.message}", error)
                    isTunnelRequested = false
                    isConnected = false
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    sendVpnState(ConnectionStates.DISCONNECTED)
                    stopSelf(startId)
                }
                START_STICKY
            }
            GlorytunConstants.ACTION_DISCONNECT -> {
                disconnect()
                START_NOT_STICKY
            }
            GlorytunConstants.ACTION_QUERY_STATE -> {
                sendVpnState(
                    when {
                        isConnected -> ConnectionStates.VPN_CONNECTED
                        isTunnelRequested -> ConnectionStates.VPN_CONNECTING
                        else -> ConnectionStates.DISCONNECTED
                    }
                )
                if (!isTunnelRequested) stopSelf(startId)
                if (isTunnelRequested) START_STICKY else START_NOT_STICKY
            }
            else -> if (isTunnelRequested) START_STICKY else START_NOT_STICKY
        }
    }

    private fun connect(config: MqvpnConfig) {
        if (isTunnelRequested) return

        isTunnelRequested = true
        isConnected = false
        latestPaths = emptyList()
        resetStatsBaselines()
        startForegroundNotification("mqvpn 接続を準備中")
        sendVpnState(ConnectionStates.VPN_CONNECTING)
        startTunnel(config)
    }

    private fun disconnect() {
        if (!isTunnelRequested) {
            sendVpnState(ConnectionStates.DISCONNECTED)
            stopSelf()
            return
        }

        isTunnelRequested = false
        isConnected = false
        stopStats()
        sendVpnState(ConnectionStates.DISCONNECTING)
        stopTunnel()
    }

    override fun onCreateTun(info: TunnelInfo, config: MqvpnConfig): ParcelFileDescriptor {
        val builder = Builder()
            .setSession("BondVPN mqvpn")
            .addAddress(info.assignedIp, info.prefix)
            .addRoute("0.0.0.0", 0)
            .setMtu(info.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setBlocking(true)
        }

        val assignedIp6 = info.assignedIp6
        if (info.hasV6 && assignedIp6 != null) {
            builder.addAddress(assignedIp6, info.prefix6)
            builder.addRoute("::", 0)
        } else if (config.killSwitch) {
            builder.addRoute("::", 0)
        }

        config.dnsServers.forEach { builder.addDnsServer(it) }

        return builder.establish()
            ?: throw IllegalStateException("VPN 権限がありません")
    }

    override fun onVpnStateChanged(newState: MqvpnState) {
        when (newState) {
            is MqvpnState.Connecting -> {
                updateNotification("mqvpn 接続中")
                sendVpnState(ConnectionStates.VPN_CONNECTING)
            }
            is MqvpnState.Reconnecting -> {
                updateNotification("mqvpn 再接続中")
                sendVpnState(ConnectionStates.VPN_CONNECTING)
            }
            is MqvpnState.Connected -> {
                isConnected = true
                isTunnelRequested = true
                updateNotification("接続済み: ${newState.tunnelInfo.assignedIp}")
                sendVpnState(ConnectionStates.VPN_CONNECTED)
                startStats()
            }
            is MqvpnState.Disconnected -> {
                isConnected = false
                isTunnelRequested = false
                latestPaths = emptyList()
                stopStats()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                sendVpnState(ConnectionStates.DISCONNECTED)
                stopSelf()
            }
            is MqvpnState.Error -> {
                Log.e(TAG, "mqvpn error: ${newState.error.message}")
                isConnected = false
                isTunnelRequested = false
                stopStats()
                updateNotification("mqvpn エラー: ${newState.error.message}")
                sendVpnState(ConnectionStates.DISCONNECTED)
                stopSelf()
            }
        }
    }

    override fun onPathsUpdated(paths: List<PathInfo>) {
        latestPaths = paths
    }

    override fun onStatsUpdated(stats: VpnStats) {
        latestStats = stats
    }

    override fun onLog(level: Int, message: String) {
        when (level) {
            0 -> Log.d(TAG, message)
            1 -> Log.i(TAG, message)
            2 -> Log.w(TAG, message)
            3 -> Log.e(TAG, message)
        }
    }

    override fun onDestroy() {
        stopStats()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            GlorytunConstants.CHANNEL_ID,
            GlorytunConstants.CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "mqvpn 接続状態の通知"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundNotification(contentText: String) {
        val notification = buildNotification(contentText)
        ServiceCompat.startForeground(
            this,
            GlorytunConstants.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    private fun updateNotification(contentText: String) {
        getSystemService(NotificationManager::class.java)
            .notify(GlorytunConstants.NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String) =
        NotificationCompat.Builder(this, GlorytunConstants.CHANNEL_ID)
            .setContentTitle("BondVPN")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun startStats() { synchronized(statsLock) {
        if (statsRunning) return
        initializeUsageCounters()
        statsRunning = true
        statsHandler.post(statsRunnable)
    } }

    private fun stopStats() { synchronized(statsLock) {
        if (!statsRunning) return
        statsRunning = false
        statsHandler.removeCallbacks(statsRunnable)
        hourlyTrafficRecorder.flush()
        bandwidthEnforcer.stop()
        resetStatsBaselines()
    } }

    private val statsRunnable = object : Runnable {
        override fun run() {
            synchronized(statsLock) {
            if (!statsRunning) return

            val paths = latestPaths
            val stats = latestStats
            val sampleTimeMillis = System.currentTimeMillis()
            val trafficUpdate = pathTrafficAccumulator.update(paths)
            val bandwidthSample = bandwidthHistory
                .onTick(paths, System.nanoTime())
                .lastOrNull()
            val wifiBps = bandwidthSample?.perPathBps
                ?.filterKeys(::isWifiInterface)
                ?.values
                ?.sum()
                ?: 0L
            val bluetoothBps = bandwidthSample?.perPathBps
                ?.filterKeys(::isBluetoothInterface)
                ?.values
                ?.sum()
                ?: 0L
            val ethernetBps = bandwidthSample?.perPathBps
                ?.filterKeys(::isEthernetInterface)
                ?.values
                ?.sum()
                ?: 0L
            val simBps = bandwidthSample?.perPathBps
                ?.filterKeys(::isCellularInterface)
                ?.values
                ?.sum()
                ?: 0L

            val wifiSampleBytes = bandwidthSample?.perPathDeltaBytes
                ?.filterKeys(::isWifiInterface)?.values?.sum() ?: 0L
            val simSampleBytes = bandwidthSample?.perPathDeltaBytes
                ?.filterKeys(::isCellularInterface)?.values?.sum() ?: 0L
            val durationNanos = bandwidthSample?.elapsedNanos ?: 0L
            val totalDeltaBytes = stats?.let(mqvpnTrafficDelta::consume) ?: 0L
            hourlyTrafficRecorder.record(
                sampleTimeMillis,
                wifiSampleBytes,
                simSampleBytes,
                totalDeltaBytes,
                durationNanos / 1_000_000L,
                (bandwidthSample?.totalBps ?: 0L) / 8192f,
            )
            val bandwidthStatus = bandwidthEnforcer.onTick(
                paths,
                trafficUpdate.wifiDeltaKB,
                trafficUpdate.simDeltaKB,
                totalDeltaBytes,
            )
            dailyWifiKB = bandwidthStatus.usage.dailyWifiBytes / 1_024.0
            dailySimKB = bandwidthStatus.usage.dailySimBytes / 1_024.0
            dailyTotalBytes = bandwidthStatus.usage.dailyTotalBytes
            monthlyWifiKB = bandwidthStatus.usage.monthlyWifiBytes / 1_024.0
            monthlySimKB = bandwidthStatus.usage.monthlySimBytes / 1_024.0
            wifiLimited = bandwidthStatus.wifiLimited
            simLimited = bandwidthStatus.simLimited
            broadcastStats(
                trafficUpdate.totals, wifiBps, simBps, bluetoothBps, ethernetBps,
                bandwidthSample?.totalBps ?: 0L, durationNanos,
                wifiSampleBytes, simSampleBytes, totalDeltaBytes, sampleTimeMillis, stats,
            )

            statsHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun initializeUsageCounters() {
        resetStatsBaselines()

        val bandwidthStatus = bandwidthEnforcer.onTick(latestPaths, 0f, 0f)
        dailyWifiKB = bandwidthStatus.usage.dailyWifiBytes / 1_024.0
        dailySimKB = bandwidthStatus.usage.dailySimBytes / 1_024.0
        dailyTotalBytes = bandwidthStatus.usage.dailyTotalBytes
        monthlyWifiKB = bandwidthStatus.usage.monthlyWifiBytes / 1_024.0
        monthlySimKB = bandwidthStatus.usage.monthlySimBytes / 1_024.0
        wifiLimited = bandwidthStatus.wifiLimited
        simLimited = bandwidthStatus.simLimited
    }

    private fun resetStatsBaselines() {
        pathTrafficAccumulator.reset()
        bandwidthHistory.clear()
        mqvpnTrafficDelta.reset()
        latestStats = null
    }

    private fun broadcastStats(
        totals: NetworkTrafficTotals,
        wifiBps: Long,
        simBps: Long,
        bluetoothBps: Long,
        ethernetBps: Long,
        totalBps: Long,
        durationNanos: Long,
        wifiSampleBytes: Long,
        simSampleBytes: Long,
        totalSampleBytes: Long,
        sampleTimeMillis: Long,
        stats: VpnStats?,
    ) {
        sendBroadcast(Intent(GlorytunConstants.ACTION_VPN_TRAFFIC_STATS).apply {
            setPackage(packageName)
            putExtra("wifi_tx_bytes", totals.wifiTx)
            putExtra("wifi_rx_bytes", totals.wifiRx)
            putExtra("wifi_active", totals.wifiActive)
            putExtra("wifi_path_active", totals.wifiPathActive)
            putExtra("sim_tx_bytes", totals.simTx)
            putExtra("sim_rx_bytes", totals.simRx)
            putExtra("sim_active", totals.simActive)
            putExtra("wifi_bps", wifiBps)
            putExtra("sim_bps", simBps)
            putExtra("bluetooth_active", totals.bluetoothActive)
            putExtra("bluetooth_bps", bluetoothBps)
            putExtra("ethernet_active", totals.ethernetActive)
            putExtra("ethernet_bps", ethernetBps)
            putExtra("total_bps", totalBps)
            putExtra("sample_duration_nanos", durationNanos)
            putExtra("wifi_sample_bytes", wifiSampleBytes)
            putExtra("sim_sample_bytes", simSampleBytes)
            putExtra("total_sample_bytes", totalSampleBytes)
            putExtra("sample_time_ms", sampleTimeMillis)
            putExtra("sample_scope_id", hourlyTrafficRecorder.sampleScopeId)
            putExtra("daily_total_bytes", dailyTotalBytes)
            if (stats != null) {
                putExtra("mqvpn_tx_bytes", stats.bytesTx)
                putExtra("mqvpn_rx_bytes", stats.bytesRx)
            }
            putExtra("stats_source", GlorytunConstants.STATE_SOURCE_VPN)
            putExtra("daily_wifi_kb", dailyWifiKB)
            putExtra("daily_sim_kb", dailySimKB)
            putExtra("wifi_throttled", wifiLimited)
            putExtra("sim_throttled", simLimited)
        })
    }

    private fun sendVpnState(state: String) {
        sendBroadcast(Intent(GlorytunConstants.ACTION_VPN_STATE).apply {
            setPackage(packageName)
            putExtra(GlorytunConstants.EXTRA_STATE, state)
            putExtra(GlorytunConstants.EXTRA_STATE_SOURCE, GlorytunConstants.STATE_SOURCE_VPN)
        })
    }

    companion object {
        private const val TAG = "MqvpnBondingService"
    }
}
