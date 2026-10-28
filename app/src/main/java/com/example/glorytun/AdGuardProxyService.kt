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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.mqvpn.sdk.core.MqvpnVpnService
import com.mqvpn.sdk.core.model.MqvpnConfig
import com.mqvpn.sdk.core.model.MqvpnState
import com.mqvpn.sdk.core.model.PathInfo
import com.mqvpn.sdk.core.model.TunnelInfo
import com.mqvpn.sdk.core.model.VpnStats
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AdGuardProxyService : MqvpnVpnService() {

    private data class ProxyTarget(
        val host: String,
        val port: Int
    )

    private val isRunning = AtomicBoolean(false)
    private val acceptExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val clientExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsLock = Any()
    private val adGuardAutomationLock = Any()
    private var statsRunning = false
    private val sourcePort = AtomicInteger(FIRST_EPHEMERAL_PORT)
    private val udpIpId = AtomicInteger((System.nanoTime() and 0xffff).toInt())
    private val connections = ConcurrentHashMap<TcpTunnelKey, ProxyTcpTunnelConnection>()
    private val udpAssociations = ConcurrentHashMap<UdpTunnelKey, ProxyUdpTunnelAssociation>()
    private val pairShareConnections = ConcurrentHashMap.newKeySet<PairShareTcpProxyConnection>()
    private val pairShareUdpAssociations = ConcurrentHashMap.newKeySet<PairShareUdpAssociation>()
    private val pathTrafficAccumulator = PathTrafficAccumulator()
    private val pairBondTrafficAccumulator = PairBondDashboardTrafficAccumulator()
    private val bandwidthHistory = BandwidthHistory(maxSamples = 1)
    private val mqvpnTrafficDelta = MqvpnTrafficDelta()
    private lateinit var hourlyTrafficRecorder: TrafficHourRecorder
    private lateinit var bandwidthEnforcer: BandwidthEnforcer
    private lateinit var pairShareRepository: PairShareRepository

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var localAddress: Inet4Address? = null
    @Volatile private var downlinkReadPfd: ParcelFileDescriptor? = null
    @Volatile private var downlinkReader: Thread? = null
    @Volatile private var tunnelMtu: Int = GlorytunConstants.DEFAULT_MTU
    @Volatile private var latestPaths: List<PathInfo> = emptyList()
    @Volatile private var latestStats: VpnStats? = null
    @Volatile private var dailyWifiKB = 0.0
    @Volatile private var dailySimKB = 0.0
    @Volatile private var dailyTotalBytes = 0L
    @Volatile private var wifiLimited = false
    @Volatile private var simLimited = false
    @Volatile private var pairShareMode = false
    @Volatile private var adGuardAutomationForSession = false

    private val statsRunnable = object : Runnable {
        override fun run() {
            synchronized(statsLock) {
            if (!isRunning.get() || !statsRunning) return
            val pairUpdate = if (pairShareMode) {
                pairBondTrafficAccumulator.update(
                    stats = PairShareCoordinator.currentPeerStats(),
                    peerNames = pairShareRepository.peers()
                        .associate { it.id to it.displayName },
                    nowMillis = SystemClock.elapsedRealtime(),
                )
            } else {
                null
            }
            val paths = latestPaths
            val stats = if (pairUpdate == null) latestStats else null
            val sampleTimeMillis = System.currentTimeMillis()
            val trafficUpdate = pairUpdate?.let {
                PathTrafficUpdate(it.totals, it.wifiDeltaKB, it.simDeltaKB)
            } ?: pathTrafficAccumulator.update(paths)
            val bandwidthSample = if (pairUpdate == null) {
                bandwidthHistory.onTick(paths, System.nanoTime()).lastOrNull()
            } else {
                null
            }
            val wifiBps = pairUpdate?.wifiBps ?: bandwidthSample?.perPathBps
                ?.filterKeys(::isWifiInterface)?.values?.sum() ?: 0L
            val simBps = pairUpdate?.simBps ?: bandwidthSample?.perPathBps
                ?.filterKeys(::isCellularInterface)?.values?.sum() ?: 0L
            val bluetoothBps = pairUpdate?.bluetoothBps ?: bandwidthSample?.perPathBps
                ?.filterKeys(::isBluetoothInterface)?.values?.sum() ?: 0L
            val ethernetBps = pairUpdate?.ethernetBps ?: bandwidthSample?.perPathBps
                ?.filterKeys(::isEthernetInterface)?.values?.sum() ?: 0L
            val pairBps = pairUpdate?.pairShareBps ?: 0L
            val totalBps = pairUpdate?.let {
                wifiBps + simBps + bluetoothBps + ethernetBps + pairBps
            } ?: bandwidthSample?.totalBps ?: 0L
            val durationNanos = pairUpdate?.sampleDurationNanos ?: bandwidthSample?.elapsedNanos ?: 0L
            val wifiSampleBytes = pairUpdate?.wifiDeltaBytes ?: bandwidthSample?.perPathDeltaBytes
                ?.filterKeys(::isWifiInterface)?.values?.sum() ?: 0L
            val simSampleBytes = pairUpdate?.simDeltaBytes ?: bandwidthSample?.perPathDeltaBytes
                ?.filterKeys(::isCellularInterface)?.values?.sum() ?: 0L
            val totalDeltaBytes = pairUpdate?.let {
                it.wifiDeltaBytes + it.simDeltaBytes + it.bluetoothDeltaBytes +
                    it.ethernetDeltaBytes + it.pairShareDeltaBytes
            } ?: stats?.let(mqvpnTrafficDelta::consume) ?: 0L
            hourlyTrafficRecorder.record(
                sampleTimeMillis, wifiSampleBytes, simSampleBytes,
                totalDeltaBytes, durationNanos / 1_000_000L, totalBps / 8192f,
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
            wifiLimited = bandwidthStatus.wifiLimited
            simLimited = bandwidthStatus.simLimited
            broadcastTrafficStats(
                trafficUpdate.totals,
                wifiBps,
                simBps,
                bluetoothBps,
                ethernetBps,
                pairBps,
                pairUpdate?.receivingPeerNames ?: emptyList(),
                totalBps,
                durationNanos,
                wifiSampleBytes,
                simSampleBytes,
                totalDeltaBytes,
                sampleTimeMillis,
                stats,
            )
            statsHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        restoreAdGuardProxyOffIfNeeded()
        hourlyTrafficRecorder = TrafficHourRecorder(HourlyTrafficSink(this)::save)
        bandwidthEnforcer = BandwidthEnforcer(this, ::setPathRateLimits)
        pairShareRepository = PairShareRepository(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action ?: "null"}")
        return when (intent?.action) {
            GlorytunConstants.ACTION_PROXY_START -> {
                startProxy(intent, startId)
                START_STICKY
            }
            GlorytunConstants.ACTION_PROXY_STOP -> {
                stopProxy()
                START_NOT_STICKY
            }
            GlorytunConstants.ACTION_PROXY_QUERY_STATE -> {
                if (isRunning.get()) sendProxyState() else stopSelf(startId)
                if (isRunning.get()) START_STICKY else START_NOT_STICKY
            }
            else -> {
                if (intent == null) {
                    restoreAdGuardProxyOffIfNeeded()
                    stopSelf(startId)
                    START_NOT_STICKY
                } else if (isRunning.get()) {
                    START_STICKY
                } else {
                    START_NOT_STICKY
                }
            }
        }
    }

    private fun startProxy(intent: Intent, startId: Int) {
        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "Ignoring proxy start because a session is already running")
            return
        }

        if (intent.getBooleanExtra(GlorytunConstants.EXTRA_PAIR_SHARE_RECEIVE, false)) {
            adGuardAutomationForSession = AdGuardAutomation.isEnabled(this)
            Log.i(TAG, "AdGuard automatic proxy switch captured for Pair & Share session=$adGuardAutomationForSession")
            if (!pairShareRepository.isReceivingEnabled()) {
                Log.w(TAG, "PairBond receive was requested while receiving is disabled")
                isRunning.set(false)
                sendState(ConnectionStates.DISCONNECTED)
                stopSelf(startId)
                return
            }
            val bondConfig = runCatching { PairBondConfig.fromIntent(intent) }
                .getOrElse { error ->
                    Log.w(TAG, "Invalid PairBond relay config: " + error.message)
                    isRunning.set(false)
                    sendState(ConnectionStates.DISCONNECTED)
                    stopSelf(startId)
                    return
                }
            PairShareCoordinator.startBonding(this, bondConfig)
            pairShareMode = true
            val port = proxyPort()
            startForegroundNotification(port, "Pair & Share 接続を開始しています")
            resetProxyState()
            sendState(ConnectionStates.PROXY_CONNECTING)
            startStats()
            startLocalProxy()
            return
        }

        pairShareMode = false
        adGuardAutomationForSession = AdGuardAutomation.isEnabled(this)
        Log.i(TAG, "AdGuard automatic proxy switch captured for session=$adGuardAutomationForSession")

        val config = runCatching { MqvpnConfigFactory.fromIntent(this, intent) }
            .getOrElse { error ->
                Log.e(TAG, "Invalid mqvpn proxy config: ${error.message}", error)
                isRunning.set(false)
                sendState(ConnectionStates.DISCONNECTED)
                stopSelf(startId)
                return
            }

        val port = proxyPort()
        startForegroundNotification(port, "mqvpn tunnel starting")
        resetProxyState()
        sendState(ConnectionStates.PROXY_CONNECTING)
        startTunnel(config)
    }

    private fun stopProxy() {
        val wasRunning = isRunning.getAndSet(false)
        val wasPairShareMode = pairShareMode
        pairShareMode = false
        synchronized(adGuardAutomationLock) {
            adGuardAutomationForSession = false
            if (AdGuardAutomation.isProxyActive(this)) {
                AdGuardAutomation.setProxyEnabled(this, enabled = false)
            }
        }

        if (!wasRunning) {
            sendState(ConnectionStates.DISCONNECTED)
            stopSelf()
            return
        }

        closeLocalProxy()
        closeConnections()
        closePairShareConnections()
        stopStats()
        closeDownlinkPipe()
        if (!wasPairShareMode) stopTunnel()
        if (wasPairShareMode) PairShareCoordinator.closeClient()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        sendState(ConnectionStates.DISCONNECTED)
        stopSelf()
    }

    override fun onCreateTun(info: TunnelInfo, config: MqvpnConfig): ParcelFileDescriptor {
        val address = InetAddress.getByName(info.assignedIp)
        require(address is Inet4Address) { "Proxy mode requires an IPv4 mqvpn tunnel address" }
        localAddress = address
        tunnelMtu = info.mtu

        val pipe = ParcelFileDescriptor.createPipe()
        downlinkReadPfd = pipe[0]
        return pipe[1]
    }

    override fun useDefaultTunnelIo(): Boolean = false

    override fun onTunFdReady(tunPfd: ParcelFileDescriptor, mtu: Int) {
        startDownlinkReader()
    }

    override fun onVpnStateChanged(newState: MqvpnState) {
        when (newState) {
            is MqvpnState.Connecting,
            is MqvpnState.Reconnecting -> {
                if (isRunning.get()) {
                    updateNotification("mqvpn tunnel connecting")
                    sendState(ConnectionStates.PROXY_CONNECTING)
                }
            }
            is MqvpnState.Connected -> {
                if (isRunning.get()) {
                    updateNotification("mqvpn tunnel ready")
                    startLocalProxy()
                    startStats()
                }
            }
            is MqvpnState.Disconnected -> {
                if (isRunning.get()) {
                    Log.w(TAG, "mqvpn tunnel disconnected while proxy mode was active")
                    stopProxyAfterTunnelClosed()
                }
            }
            is MqvpnState.Error -> {
                Log.e(TAG, "mqvpn proxy tunnel error: ${newState.error.message}")
                if (isRunning.get()) stopProxyAfterTunnelClosed()
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

    private fun stopProxyAfterTunnelClosed() {
        if (pairShareMode) return
        isRunning.set(false)
        synchronized(adGuardAutomationLock) {
            adGuardAutomationForSession = false
            if (AdGuardAutomation.isProxyActive(this)) {
                AdGuardAutomation.setProxyEnabled(this, enabled = false)
            }
        }
        closeLocalProxy()
        closeConnections()
        stopStats()
        closeDownlinkPipe()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        sendState(ConnectionStates.DISCONNECTED)
        stopSelf()
    }

    private fun startLocalProxy() {
        if (serverSocket != null) return

        val port = proxyPort()
        acceptExecutor.execute {
            try {
                ServerSocket().use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), port))
                    val proxyStarted = synchronized(adGuardAutomationLock) {
                        if (!isRunning.get()) {
                            false
                        } else {
                            serverSocket = socket
                            Log.i(TAG, "AdGuard mqvpn proxy listening on $LOOPBACK_HOST:$port")
                            Log.i(
                                TAG,
                                "Proxy session ready: pairShare=$pairShareMode, " +
                                    "automationRequested=$adGuardAutomationForSession",
                            )
                            val route = if (pairShareMode) "ペア端末経由" else "mqvpn 経由"
                            updateNotification("$LOOPBACK_HOST:$port $route")
                            if (adGuardAutomationForSession) {
                                AdGuardAutomation.enableProxyForSession(
                                    context = this@AdGuardProxyService,
                                    automationRequested = adGuardAutomationForSession,
                                    sessionIsActive = {
                                        isRunning.get() && serverSocket === socket
                                    },
                                )
                            }
                            sendState(ConnectionStates.PROXY_CONNECTED)
                            true
                        }
                    }
                    if (!proxyStarted) return@use

                    while (isRunning.get()) {
                        val client = try {
                            socket.accept()
                        } catch (e: IOException) {
                            if (isRunning.get()) {
                                Log.w(TAG, "Proxy accept failed", e)
                                stopProxy()
                            }
                            break
                        }
                        clientExecutor.execute { handleClient(client) }
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Proxy listener failed", e)
                    stopProxy()
                }
            } finally {
                serverSocket = null
            }
        }
    }

    private fun closeLocalProxy() {
        closeQuietly(serverSocket)
        serverSocket = null
    }

    private fun handleClient(client: Socket) {
        client.tcpNoDelay = true
        client.soTimeout = SOCKET_TIMEOUT_MS

        try {
            client.use {
                val clientIn = PushbackInputStream(client.getInputStream(), 1)
                val firstByte = clientIn.read()
                if (firstByte < 0) return
                clientIn.unread(firstByte)

                if (firstByte == SOCKS5_VERSION) {
                    handleSocks5(client, clientIn)
                } else {
                    handleHttpConnect(client, clientIn)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Proxy client closed: ${e.message}")
        }
    }

    private fun handleSocks5(client: Socket, clientIn: PushbackInputStream) {
        val version = readRequiredByte(clientIn)
        if (version != SOCKS5_VERSION) return

        val methods = readRequiredByte(clientIn)
        repeat(methods) { readRequiredByte(clientIn) }
        client.getOutputStream().write(byteArrayOf(SOCKS5_VERSION.toByte(), 0x00))

        val requestVersion = readRequiredByte(clientIn)
        val command = readRequiredByte(clientIn)
        readRequiredByte(clientIn)
        val addressType = readRequiredByte(clientIn)
        if (requestVersion != SOCKS5_VERSION) {
            sendSocks5Reply(client.getOutputStream(), SOCKS5_COMMAND_NOT_SUPPORTED)
            return
        }

        val host = when (addressType) {
            SOCKS5_IPV4 -> {
                val bytes = readRequiredBytes(clientIn, 4)
                InetAddress.getByAddress(bytes).hostAddress ?: return
            }
            SOCKS5_DOMAIN -> {
                val length = readRequiredByte(clientIn)
                String(readRequiredBytes(clientIn, length), StandardCharsets.UTF_8)
            }
            SOCKS5_IPV6 -> {
                readRequiredBytes(clientIn, 16)
                null
            }
            else -> {
                sendSocks5Reply(client.getOutputStream(), SOCKS5_ADDRESS_NOT_SUPPORTED)
                return
            }
        }
        val port = readPort(clientIn)

        if (command == SOCKS5_UDP_ASSOCIATE) {
            handleUdpAssociate(client, clientIn)
            return
        }

        if (command != SOCKS5_CONNECT) {
            sendSocks5Reply(client.getOutputStream(), SOCKS5_COMMAND_NOT_SUPPORTED)
            return
        }

        if (host == null) {
            sendSocks5Reply(client.getOutputStream(), SOCKS5_ADDRESS_NOT_SUPPORTED)
            return
        }

        if (pairShareMode) {
            val connection = openPairShareConnection(host, port, client.getOutputStream())
            if (connection == null) {
                sendSocks5Reply(client.getOutputStream(), SOCKS5_NETWORK_UNREACHABLE)
                return
            }
            sendSocks5Reply(client.getOutputStream(), SOCKS5_SUCCEEDED)
            connection.relayFrom(clientIn)
            return
        }

        val connection = openTunnelConnection(host, port, client.getOutputStream())
        if (connection == null) {
            sendSocks5Reply(client.getOutputStream(), SOCKS5_NETWORK_UNREACHABLE)
            return
        }

        if (!connection.start()) {
            connection.close()
            sendSocks5Reply(client.getOutputStream(), SOCKS5_NETWORK_UNREACHABLE)
            return
        }

        sendSocks5Reply(client.getOutputStream(), SOCKS5_SUCCEEDED)
        connection.relayFrom(clientIn)
    }

    private fun handleUdpAssociate(client: Socket, clientIn: InputStream) {
        if (pairShareMode) {
            handlePairShareUdpAssociate(client, clientIn)
            return
        }

        val local = localAddress
        if (local == null) {
            sendSocks5Reply(client.getOutputStream(), SOCKS5_NETWORK_UNREACHABLE)
            return
        }

        val association = try {
            ProxyUdpTunnelAssociation(
                socket = ProxyUdpTunnelAssociation.bindLoopback(),
                localAddress = local,
                mtu = tunnelMtu,
                sourcePort = sourcePort,
                ipId = udpIpId,
                resolveIpv4 = { host -> resolveIpv4(host) },
                packetSender = { packet -> sendTunPacket(packet) },
                registerAssociation = { key, assoc -> udpAssociations[key] = assoc },
                unregisterAssociation = { key -> udpAssociations.remove(key) }
            )
        } catch (e: Exception) {
            Log.w(TAG, "UDP associate bind failed: ${e.message}")
            sendSocks5Reply(client.getOutputStream(), SOCKS5_NETWORK_UNREACHABLE)
            return
        }

        association.start()
        sendSocks5Reply(
            output = client.getOutputStream(),
            status = SOCKS5_SUCCEEDED,
            boundAddress = InetAddress.getByName(LOOPBACK_HOST).address,
            boundPort = association.localUdpPort
        )
        association.waitForControlClose(clientIn)
    }

    private fun handleHttpConnect(client: Socket, clientIn: PushbackInputStream) {
        val header = readHttpHeader(clientIn)
        val firstLine = header.lineSequence().firstOrNull() ?: return
        val parts = firstLine.split(' ')
        if (parts.size < 3 || !parts[0].equals("CONNECT", ignoreCase = true)) {
            client.getOutputStream().write(
                "HTTP/1.1 405 Method Not Allowed\r\nConnection: close\r\n\r\n"
                    .toByteArray(StandardCharsets.ISO_8859_1)
            )
            return
        }

        val target = parseAuthority(parts[1]) ?: run {
            client.getOutputStream().write(
                "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n"
                    .toByteArray(StandardCharsets.ISO_8859_1)
            )
            return
        }

        if (pairShareMode) {
            val connection = openPairShareConnection(target.host, target.port, client.getOutputStream())
            if (connection == null) {
                client.getOutputStream().write(
                    "HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n"
                        .toByteArray(StandardCharsets.ISO_8859_1)
                )
                return
            }
            client.getOutputStream().write(
                "HTTP/1.1 200 Connection Established\r\n\r\n"
                    .toByteArray(StandardCharsets.ISO_8859_1)
            )
            connection.relayFrom(clientIn)
            return
        }

        val connection = openTunnelConnection(target.host, target.port, client.getOutputStream())
        if (connection == null || !connection.start()) {
            connection?.close()
            client.getOutputStream().write(
                "HTTP/1.1 503 Service Unavailable\r\nConnection: close\r\n\r\n"
                    .toByteArray(StandardCharsets.ISO_8859_1)
            )
            return
        }

        client.getOutputStream().write(
            "HTTP/1.1 200 Connection Established\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1)
        )
        connection.relayFrom(clientIn)
    }

    private fun handlePairShareUdpAssociate(client: Socket, clientIn: InputStream) {
        lateinit var association: PairShareUdpAssociation
        association = try {
            PairShareUdpAssociation.bind(
                context = this,
                allowedAddress = client.inetAddress,
                onClosed = { pairShareUdpAssociations.remove(association) },
            ).also {
                pairShareUdpAssociations.add(it)
                it.start()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Pair & Share UDP associate failed: ${error.message}")
            sendSocks5Reply(client.getOutputStream(), SOCKS5_NETWORK_UNREACHABLE)
            return
        }

        sendSocks5Reply(
            output = client.getOutputStream(),
            status = SOCKS5_SUCCEEDED,
            boundAddress = InetAddress.getByName(LOOPBACK_HOST).address,
            boundPort = association.localUdpPort,
        )
        association.waitForControlClose(clientIn)
    }

    private fun openPairShareConnection(
        host: String,
        port: Int,
        clientOutput: OutputStream,
    ): PairShareTcpProxyConnection? {
        return runCatching {
            val stream = PairShareCoordinator.openTcp(this, host, port)
            lateinit var connection: PairShareTcpProxyConnection
            connection = PairShareTcpProxyConnection(stream, clientOutput) {
                pairShareConnections.remove(connection)
            }
            pairShareConnections.add(connection)
            connection
        }.onFailure { error ->
            Log.w(TAG, "Pair & Share TCP connection failed: ${error.message}")
        }.getOrNull()
    }

    private fun openTunnelConnection(host: String, port: Int, clientOutput: OutputStream): ProxyTcpTunnelConnection? {
        val local = localAddress ?: return null
        val remote = resolveIpv4(host) ?: return null
        val key = allocateConnectionKey(remote, port) ?: return null

        val connection = ProxyTcpTunnelConnection(
            key = key,
            localAddress = local,
            remoteAddress = remote,
            clientOutput = clientOutput,
            mtu = tunnelMtu,
            packetSender = { packet -> sendTunPacket(packet) },
            onClosed = { closedKey -> connections.remove(closedKey) }
        )
        connections[key] = connection
        return connection
    }

    private fun allocateConnectionKey(remote: Inet4Address, remotePort: Int): TcpTunnelKey? {
        repeat(MAX_PORT_PROBES) {
            val localPort = sourcePort.updateAndGet { current ->
                if (current >= LAST_EPHEMERAL_PORT) FIRST_EPHEMERAL_PORT else current + 1
            }
            val key = TcpTunnelKey(localPort, remote.hostAddress ?: return null, remotePort)
            if (!connections.containsKey(key)) return key
        }
        return null
    }

    private fun resolveIpv4(host: String): Inet4Address? {
        return runCatching {
            InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().firstOrNull()
        }.getOrNull()
    }

    private fun startDownlinkReader() {
        val readPfd = downlinkReadPfd ?: return
        downlinkReader?.interrupt()
        downlinkReader = Thread({
            FileInputStream(readPfd.fileDescriptor).use { input ->
                readDownlinkPackets(input)
            }
        }, "mqvpn-proxy-downlink").apply {
            isDaemon = true
            start()
        }
    }

    private fun readDownlinkPackets(input: InputStream) {
        val firstHeader = ByteArray(IPV4_MIN_HEADER_BYTES)
        while (isRunning.get()) {
            if (!readFully(input, firstHeader, 0, firstHeader.size)) break
            val totalLength = ipTotalLength(firstHeader) ?: break
            if (totalLength < firstHeader.size || totalLength > MAX_IP_PACKET_BYTES) break

            val packet = ByteArray(totalLength)
            firstHeader.copyInto(packet, 0)
            if (!readFully(input, packet, firstHeader.size, totalLength - firstHeader.size)) break
            handleDownlinkPacket(packet)
        }
    }

    private fun handleDownlinkPacket(packet: ByteArray) {
        val local = localAddress ?: return

        val tcp = Ipv4TcpCodec.parse(packet)
        if (tcp != null) {
            if (tcp.destinationAddress != local) return
            val remoteHost = tcp.sourceAddress.hostAddress ?: return
            val key = TcpTunnelKey(
                localPort = tcp.destinationPort,
                remoteAddress = remoteHost,
                remotePort = tcp.sourcePort
            )
            connections[key]?.handlePacket(tcp)
            return
        }

        val udp = Ipv4UdpCodec.parse(packet) ?: return
        if (udp.destinationAddress != local) return
        val remoteHost = udp.sourceAddress.hostAddress ?: return
        val udpKey = UdpTunnelKey(
            localPort = udp.destinationPort,
            remoteAddress = remoteHost,
            remotePort = udp.sourcePort
        )
        udpAssociations[udpKey]?.sendToClient(udp)
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Boolean {
        var current = offset
        val end = offset + length
        while (current < end) {
            val read = try {
                input.read(buffer, current, end - current)
            } catch (_: IOException) {
                return false
            }
            if (read < 0) return false
            current += read
        }
        return true
    }

    private fun ipTotalLength(headerPrefix: ByteArray): Int? {
        val version = (u8(headerPrefix[0]) ushr 4) and 0x0f
        return when (version) {
            4 -> u16(headerPrefix, 2)
            6 -> IPV6_HEADER_BYTES + u16(headerPrefix, 4)
            else -> null
        }
    }

    private fun closeConnections() {
        connections.values.forEach { it.close() }
        connections.clear()
        udpAssociations.values.toSet().forEach { it.close() }
        udpAssociations.clear()
    }

    private fun closePairShareConnections() {
        pairShareConnections.toSet().forEach { it.close() }
        pairShareConnections.clear()
        pairShareUdpAssociations.toSet().forEach { it.close() }
        pairShareUdpAssociations.clear()
    }

    private fun closeDownlinkPipe() {
        downlinkReader?.interrupt()
        downlinkReader = null
        closeQuietly(downlinkReadPfd)
        downlinkReadPfd = null
        localAddress = null
    }

    private fun startStats() { synchronized(statsLock) {
        if (statsRunning) return
        hourlyTrafficRecorder.flush()
        pathTrafficAccumulator.reset()
        pairBondTrafficAccumulator.reset()
        bandwidthHistory.clear()
        mqvpnTrafficDelta.reset()
        latestStats = null
        val bandwidthStatus = bandwidthEnforcer.onTick(latestPaths, 0f, 0f)
        dailyWifiKB = bandwidthStatus.usage.dailyWifiBytes / 1_024.0
        dailySimKB = bandwidthStatus.usage.dailySimBytes / 1_024.0
        dailyTotalBytes = bandwidthStatus.usage.dailyTotalBytes
        wifiLimited = bandwidthStatus.wifiLimited
        simLimited = bandwidthStatus.simLimited
        statsRunning = true
        statsHandler.removeCallbacks(statsRunnable)
        statsHandler.post(statsRunnable)
    } }

    private fun stopStats() { synchronized(statsLock) {
        if (!statsRunning) return
        statsRunning = false
        statsHandler.removeCallbacks(statsRunnable)
        hourlyTrafficRecorder.flush()
        bandwidthEnforcer.stop()
        pathTrafficAccumulator.reset()
        pairBondTrafficAccumulator.reset()
        bandwidthHistory.clear()
        latestPaths = emptyList()
        latestStats = null
        mqvpnTrafficDelta.reset()
    } }

    private fun resetProxyState() {
        latestPaths = emptyList()
        dailyWifiKB = 0.0
        dailySimKB = 0.0
        dailyTotalBytes = 0L
        pathTrafficAccumulator.reset()
        bandwidthHistory.clear()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                GlorytunConstants.CHANNEL_ID,
                GlorytunConstants.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "AdGuard compatible mqvpn proxy state" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification(port: Int, contentText: String) {
        val notification = buildNotification("$LOOPBACK_HOST:$port - $contentText")
        ServiceCompat.startForeground(
            this,
            GlorytunConstants.NOTIFICATION_ID + 1,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun updateNotification(contentText: String) {
        getSystemService(NotificationManager::class.java)
            .notify(GlorytunConstants.NOTIFICATION_ID + 1, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String) =
        NotificationCompat.Builder(this, GlorytunConstants.CHANNEL_ID)
            .setContentTitle("BondVPN Proxy")
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

    private fun sendProxyState() {
        sendState(if (serverSocket != null) ConnectionStates.PROXY_CONNECTED else ConnectionStates.PROXY_CONNECTING)
    }

    private fun sendState(state: String) {
        sendBroadcast(Intent(GlorytunConstants.ACTION_VPN_STATE).apply {
            setPackage(packageName)
            putExtra(GlorytunConstants.EXTRA_STATE, state)
            putExtra(GlorytunConstants.EXTRA_STATE_SOURCE, GlorytunConstants.STATE_SOURCE_PROXY)
        })
    }

    private fun broadcastTrafficStats(
        totals: NetworkTrafficTotals,
        wifiBps: Long,
        simBps: Long,
        bluetoothBps: Long,
        ethernetBps: Long,
        pairShareBps: Long,
        pairSharePeerNames: List<String>,
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
            putExtra("pair_share_bps", pairShareBps)
            putStringArrayListExtra("pair_share_peer_names", ArrayList(pairSharePeerNames))
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
            putExtra("stats_source", GlorytunConstants.STATE_SOURCE_PROXY)
            putExtra("daily_wifi_kb", dailyWifiKB)
            putExtra("daily_sim_kb", dailySimKB)
            putExtra("wifi_throttled", wifiLimited)
            putExtra("sim_throttled", simLimited)
        })
    }

    private fun proxyPort(): Int {
        return getSharedPreferences(GlorytunConstants.PREFS_PROXY, MODE_PRIVATE)
            .getInt(GlorytunConstants.KEY_ADGUARD_PROXY_PORT, GlorytunConstants.DEFAULT_ADGUARD_PROXY_PORT)
            .coerceIn(1024, 65535)
    }

    private fun readHttpHeader(input: InputStream): String {
        val out = ByteArrayOutputStream()
        var matched = 0
        while (out.size() < MAX_HTTP_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) break
            out.write(value)
            matched = if (value == HTTP_HEADER_END[matched].toInt()) matched + 1 else 0
            if (matched == HTTP_HEADER_END.size) break
        }
        return out.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun parseAuthority(authority: String): ProxyTarget? {
        val host: String
        val portText: String
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end <= 0 || end + 2 > authority.length || authority[end + 1] != ':') return null
            host = authority.substring(1, end)
            portText = authority.substring(end + 2)
        } else {
            val split = authority.lastIndexOf(':')
            if (split <= 0 || split == authority.lastIndex - 1) return null
            host = authority.substring(0, split)
            portText = authority.substring(split + 1)
        }
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return ProxyTarget(host, port)
    }

    private fun readPort(input: InputStream): Int {
        val high = readRequiredByte(input)
        val low = readRequiredByte(input)
        return (high shl 8) or low
    }

    private fun readRequiredByte(input: InputStream): Int {
        val value = input.read()
        if (value < 0) throw IOException("Unexpected EOF")
        return value
    }

    private fun readRequiredBytes(input: InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(bytes, offset, count - offset)
            if (read < 0) throw IOException("Unexpected EOF")
            offset += read
        }
        return bytes
    }

    private fun sendSocks5Reply(
        output: OutputStream,
        status: Int,
        boundAddress: ByteArray = byteArrayOf(0, 0, 0, 0),
        boundPort: Int = 0
    ) {
        val reply = ByteArray(10)
        reply[0] = SOCKS5_VERSION.toByte()
        reply[1] = status.toByte()
        reply[2] = 0x00
        reply[3] = SOCKS5_IPV4.toByte()
        boundAddress.copyInto(reply, 4, 0, 4)
        reply[8] = ((boundPort ushr 8) and 0xff).toByte()
        reply[9] = (boundPort and 0xff).toByte()
        output.write(reply)
    }

    private fun closeQuietly(closeable: Closeable?) {
        try { closeable?.close() } catch (_: Exception) {}
    }

    private fun u16(buffer: ByteArray, offset: Int): Int =
        (u8(buffer[offset]) shl 8) or u8(buffer[offset + 1])

    private fun u8(value: Byte): Int = value.toInt() and 0xff

    override fun onDestroy() {
        stopProxy()
        acceptExecutor.shutdownNow()
        clientExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (isRunning.get() && adGuardAutomationForSession) stopProxy()
        super.onTaskRemoved(rootIntent)
    }

    private fun restoreAdGuardProxyOffIfNeeded() {
        synchronized(adGuardAutomationLock) {
            AdGuardAutomation.restoreProxyOffIfNeeded(this)
        }
    }

    companion object {
        private const val TAG = "AdGuardProxyService"
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val SOCKET_TIMEOUT_MS = 120_000
        private const val MAX_HTTP_HEADER_BYTES = 16 * 1024
        private const val IPV4_MIN_HEADER_BYTES = 20
        private const val IPV6_HEADER_BYTES = 40
        private const val MAX_IP_PACKET_BYTES = 65_535
        private const val FIRST_EPHEMERAL_PORT = 20_000
        private const val LAST_EPHEMERAL_PORT = 60_999
        private const val MAX_PORT_PROBES = 41_000

        private const val SOCKS5_VERSION = 0x05
        private const val SOCKS5_CONNECT = 0x01
        private const val SOCKS5_UDP_ASSOCIATE = 0x03
        private const val SOCKS5_SUCCEEDED = 0x00
        private const val SOCKS5_NETWORK_UNREACHABLE = 0x03
        private const val SOCKS5_COMMAND_NOT_SUPPORTED = 0x07
        private const val SOCKS5_ADDRESS_NOT_SUPPORTED = 0x08
        private const val SOCKS5_IPV4 = 0x01
        private const val SOCKS5_DOMAIN = 0x03
        private const val SOCKS5_IPV6 = 0x04
        private val HTTP_HEADER_END = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
    }
}
