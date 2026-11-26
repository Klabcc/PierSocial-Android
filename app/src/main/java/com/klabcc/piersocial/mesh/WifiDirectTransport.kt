package com.klabcc.piersocial.mesh

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Seamless high-bandwidth Wi-Fi Direct (Wi-Fi P2P + framed TCP) transport layer
 * coordinated out-of-band over the always-on BLE Mesh.
 *
 * - Uses BLE peer IDs for deterministic Group Owner election (smaller peerId hosts).
 * - Broadcasts autonomous group credentials (SSID + passphrase + IP + port) over BLE.
 * - Joins silently on Android 10+ via `WifiP2pConfig.Builder().setNetworkName().setPassphrase()`
 *   without confirmation dialogs, then opens multi-Mbps priority TCP sockets for bulk media & DMs.
 */
class WifiDirectTransport(
    private val context: Context,
    private val localPeerIdProvider: () -> String,
    private val onFrameReceived: (wifiAddress: String, mtu: Int, frame: MeshProtocol.MeshFrame) -> Unit,
    private val onTcpPeerConnected: (wifiAddress: String) -> Unit,
    private val onTcpPeerDisconnected: (wifiAddress: String) -> Unit,
    private val onOfferReadyToSend: (targetBleAddress: String?, offer: WifiDirectOfferPayload) -> Unit,
    private val onConnectionsChanged: () -> Unit,
    private val onStateChanged: (enabled: Boolean, isGroupOwner: Boolean, count: Int) -> Unit,
    private val onLog: (tag: String, message: String, isError: Boolean) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var wifiManager: WifiManager? = null

    private val isStarted = AtomicBoolean(false)
    private var isReceiverRegistered = false
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    private var isP2pEnabled = false

    @Volatile
    private var isGroupOwner = false

    @Volatile
    private var isGroupCreating = false

    @Volatile
    private var currentGroupOffer: WifiDirectOfferPayload? = null

    @Volatile
    private var pendingClientOffer: WifiDirectOfferPayload? = null

    @Volatile
    private var connectedGoPeerId: String? = null

    private var lastOfferAttemptAtMs = 0L
    private var lastClientConnectAtMs = 0L
    private var tcpConnectJob: Job? = null

    private val linkIdCounter = AtomicInteger(1)
    private val tcpLinks = ConcurrentHashMap<String, TcpPeerLink>()
    private val connectionInfos = ConcurrentHashMap<String, GattConnectionInfo>()
    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED
                    )
                    isP2pEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    notifyState()
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val p2pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    }
                    @Suppress("DEPRECATION")
                    val netInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    } else {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }
                    if (p2pInfo?.groupFormed == true || netInfo?.isConnected == true) {
                        queryConnectionAndGroupInfo()
                    } else {
                        isGroupOwner = false
                        currentGroupOffer = null
                        connectedGoPeerId = null
                        notifyState()
                    }
                }
            }
        }
    }

    fun start() {
        if (isStarted.getAndSet(true)) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            onLog("WIFI-P2P", "Device does not report FEATURE_WIFI_DIRECT; staying on BLE mesh", false)
            isStarted.set(false)
            return
        }
        wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        val mgr = p2pManager
        if (mgr == null) {
            isStarted.set(false)
            return
        }
        p2pChannel = mgr.initialize(context, context.mainLooper, null)
        startTcpServer()

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(p2pReceiver, filter)
        }
        isReceiverRegistered = true
        isP2pEnabled = wifiManager?.isWifiEnabled == true
        notifyState()
        queryConnectionAndGroupInfo()
        onLog("WIFI-P2P", "Wi-Fi Direct transport ready on TCP port ${serverSocket?.localPort ?: -1}", false)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!isStarted.getAndSet(false)) return
        tcpConnectJob?.cancel()
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        tcpLinks.values.forEach { it.close() }
        tcpLinks.clear()
        connectionInfos.clear()
        lastOfferSentToBle.clear()
        currentGroupOffer = null
        pendingClientOffer = null
        connectedGoPeerId = null
        isGroupOwner = false
        isGroupCreating = false

        if (isReceiverRegistered) {
            runCatching { context.unregisterReceiver(p2pReceiver) }
            isReceiverRegistered = false
        }
        val ch = p2pChannel
        val mgr = p2pManager
        if (ch != null && mgr != null && MeshController.hasWifiDirectPermissions(context)) {
            runCatching { mgr.removeGroup(ch, null) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                runCatching { ch.close() }
            }
        }
        p2pChannel = null
        notifyState()
        onConnectionsChanged()
    }

    fun isReady(): Boolean = isStarted.get() && isP2pEnabled && MeshController.hasWifiDirectPermissions(context)

    private val lastOfferSentToBle = ConcurrentHashMap<String, Long>()

    fun onBlePeerIdentified(bleAddress: String, remotePeerId: String) {
        if (!isReady()) return
        if (hasActiveLinkToPeer(remotePeerId)) return

        val localId = localPeerIdProvider()
        val offer = currentGroupOffer
        if (offer != null && isGroupOwner) {
            val now = System.currentTimeMillis()
            val prevSent = lastOfferSentToBle[bleAddress] ?: 0L
            if (now - prevSent > 8_000L) {
                lastOfferSentToBle[bleAddress] = now
                onOfferReadyToSend(bleAddress, offer)
            }
            return
        }

        if (localId < remotePeerId && pendingClientOffer == null && tcpLinks.isEmpty()) {
            ensureGroupHosted(targetBleAddress = bleAddress)
        }
    }

    fun triggerManualUpgrade() {
        if (!isReady()) return
        val offer = currentGroupOffer
        if (offer != null && isGroupOwner) {
            onOfferReadyToSend(null, offer)
        } else if (pendingClientOffer == null) {
            ensureGroupHosted(targetBleAddress = null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun ensureGroupHosted(targetBleAddress: String?) {
        if (!isReady()) return
        val offer = currentGroupOffer
        if (offer != null && isGroupOwner) {
            if (targetBleAddress != null) {
                val now = System.currentTimeMillis()
                val prevSent = lastOfferSentToBle[targetBleAddress] ?: 0L
                if (now - prevSent <= 8_000L) return
                lastOfferSentToBle[targetBleAddress] = now
            }
            onOfferReadyToSend(targetBleAddress, offer)
            return
        }
        val now = System.currentTimeMillis()
        if (isGroupCreating || now - lastOfferAttemptAtMs < 6_000L) return
        lastOfferAttemptAtMs = now
        isGroupCreating = true

        val mgr = p2pManager ?: return
        val ch = p2pChannel ?: return
        runCatching {
            mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    isGroupCreating = false
                    onLog("WIFI-P2P", "Autonomous Wi-Fi Direct group created; reading credentials...", false)
                    scope.launch {
                        // Allow WifiP2pService a brief moment to populate SSID/passphrase
                        repeat(6) {
                            delay(250L)
                            queryConnectionAndGroupInfo()
                            if (currentGroupOffer != null) return@launch
                        }
                    }
                }

                override fun onFailure(reason: Int) {
                    isGroupCreating = false
                    // Error 2 (BUSY) often means a group already exists — inspect existing group info
                    if (reason == WifiP2pManager.BUSY) {
                        queryConnectionAndGroupInfo()
                    } else {
                        onLog("WIFI-P2P", "createGroup deferred (reason=$reason); BLE mesh continues", false)
                    }
                }
            })
        }.onFailure { isGroupCreating = false }
    }

    @SuppressLint("MissingPermission")
    fun onWifiDirectOfferReceived(sourceBleAddress: String, offer: WifiDirectOfferPayload) {
        if (!isReady()) return
        val localId = localPeerIdProvider()
        if (offer.senderPeerId == localId) return
        if (hasActiveLinkToPeer(offer.senderPeerId)) return
        if (offer.networkName.isBlank() || offer.passphrase.isBlank() || offer.tcpPort <= 0) return

        // Deterministic Group Owner election: smaller peerId hosts unless it is already a connected client
        if (localId < offer.senderPeerId) {
            if (!isGroupOwner && tcpLinks.isNotEmpty()) return
            ensureGroupHosted(targetBleAddress = sourceBleAddress)
            return
        }

        // If we're already connected as a client to an even smaller GO, keep that group
        val activeGo = connectedGoPeerId
        if (!isGroupOwner && activeGo != null && hasActiveLinkToPeer(activeGo) && activeGo < offer.senderPeerId) {
            return
        }

        // If we are hosting active clients right now, don't disrupt them
        if (isGroupOwner && tcpLinks.isNotEmpty()) {
            currentGroupOffer?.let { onOfferReadyToSend(sourceBleAddress, it) }
            return
        }

        val now = System.currentTimeMillis()
        if (pendingClientOffer?.senderPeerId == offer.senderPeerId && now - lastClientConnectAtMs < 8_000L) {
            return
        }
        pendingClientOffer = offer
        lastClientConnectAtMs = now

        val mgr = p2pManager ?: return
        val ch = p2pChannel ?: return

        // If we were temporarily acting as an empty GO, tear down our empty group first, then join
        if (isGroupOwner) {
            runCatching {
                mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        isGroupOwner = false
                        currentGroupOffer = null
                        scope.launch {
                            delay(250L)
                            connectToWifiP2pOffer(mgr, ch, offer)
                        }
                    }

                    override fun onFailure(reason: Int) {
                        isGroupOwner = false
                        currentGroupOffer = null
                        scope.launch {
                            delay(250L)
                            connectToWifiP2pOffer(mgr, ch, offer)
                        }
                    }
                })
            }
            return
        }

        connectToWifiP2pOffer(mgr, ch, offer)
    }

    @SuppressLint("MissingPermission")
    private fun connectToWifiP2pOffer(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        offer: WifiDirectOfferPayload
    ) {
        if (!isReady() || hasActiveLinkToPeer(offer.senderPeerId)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val config = WifiP2pConfig.Builder()
                .setNetworkName(offer.networkName)
                .setPassphrase(offer.passphrase)
                .build()
            onLog(
                "WIFI-P2P",
                "Joining ${offer.senderPeerId.take(8)} silently (${offer.networkName}) for high-speed TCP",
                false
            )
            runCatching {
                mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        scope.launch {
                            repeat(12) {
                                delay(350L)
                                queryConnectionAndGroupInfo()
                                if (hasActiveLinkToPeer(offer.senderPeerId)) return@launch
                            }
                        }
                    }

                    override fun onFailure(reason: Int) {
                        onLog("WIFI-P2P", "Silent P2P join failed (reason=$reason); BLE fallback active", false)
                    }
                })
            }
        } else if (offer.deviceAddress.isNotBlank()) {
            val config = WifiP2pConfig().apply {
                deviceAddress = offer.deviceAddress
                wps.setup = WpsInfo.PBC
            }
            runCatching { mgr.connect(ch, config, null) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun queryConnectionAndGroupInfo() {
        val mgr = p2pManager ?: return
        val ch = p2pChannel ?: return
        if (!MeshController.hasWifiDirectPermissions(context)) return

        runCatching {
            mgr.requestConnectionInfo(ch) { info: WifiP2pInfo? ->
                if (info == null || !info.groupFormed) return@requestConnectionInfo
                isGroupOwner = info.isGroupOwner
                val goHost = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"

                if (info.isGroupOwner) {
                    runCatching {
                        mgr.requestGroupInfo(ch) { group: WifiP2pGroup? ->
                            val ssid = group?.networkName
                            val pass = group?.passphrase
                            val port = serverSocket?.localPort ?: 0
                            if (!ssid.isNullOrBlank() && !pass.isNullOrBlank() && port > 0) {
                                val offer = WifiDirectOfferPayload(
                                    senderPeerId = localPeerIdProvider(),
                                    networkName = ssid,
                                    passphrase = pass,
                                    hostIp = goHost,
                                    tcpPort = port,
                                    deviceAddress = group.owner?.deviceAddress.orEmpty()
                                )
                                val isNewOffer = currentGroupOffer?.networkName != ssid ||
                                    currentGroupOffer?.tcpPort != port
                                currentGroupOffer = offer
                                notifyState()
                                if (isNewOffer) {
                                    onLog("WIFI-P2P", "Hosting $ssid @ $goHost:$port", false)
                                    onOfferReadyToSend(null, offer)
                                }
                            }
                        }
                    }
                } else {
                    val offer = pendingClientOffer
                    if (offer != null && !hasActiveLinkToPeer(offer.senderPeerId)) {
                        connectTcpClient(goHost, offer.tcpPort, offer.senderPeerId)
                    }
                }
            }
        }
    }

    private fun startTcpServer() {
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        val ss = runCatching { ServerSocket(0) }.getOrNull() ?: return
        serverSocket = ss
        acceptJob = scope.launch {
            while (isActive && !ss.isClosed) {
                val client = runCatching { ss.accept() }.getOrNull() ?: break
                registerTcpSocket(client, role = "Wi-Fi Direct GO", expectedPeerId = null)
            }
        }
    }

    private fun connectTcpClient(host: String, port: Int, expectedPeerId: String) {
        if (port <= 0 || hasActiveLinkToPeer(expectedPeerId)) return
        if (connectedGoPeerId == expectedPeerId && tcpConnectJob?.isActive == true) return
        connectedGoPeerId = expectedPeerId
        tcpConnectJob?.cancel()
        tcpConnectJob = scope.launch {
            repeat(5) { attempt ->
                if (!isActive || hasActiveLinkToPeer(expectedPeerId)) return@launch
                val socket = runCatching {
                    Socket().apply {
                        tcpNoDelay = true
                        keepAlive = true
                        sendBufferSize = 65536
                        receiveBufferSize = 65536
                        connect(InetSocketAddress(host, port), 4_000)
                    }
                }.getOrNull()
                if (socket != null) {
                    registerTcpSocket(socket, role = "Wi-Fi Direct Client", expectedPeerId = expectedPeerId)
                    return@launch
                }
                if (attempt < 4) delay(350L)
            }
            connectedGoPeerId = null
        }
    }

    private fun registerTcpSocket(socket: Socket, role: String, expectedPeerId: String?) {
        runCatching {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.sendBufferSize = 65536
            socket.receiveBufferSize = 65536
        }
        val remoteIp = socket.inetAddress?.hostAddress ?: "p2p"
        val wifiAddress = "wifi:${linkIdCounter.getAndIncrement()}_$remoteIp"
        val link = TcpPeerLink(
            socket = socket,
            scope = scope,
            onFrame = { frame ->
                onFrameReceived(wifiAddress, WIFI_DIRECT_VIRTUAL_MTU, frame)
            },
            onDisconnected = {
                tcpLinks.remove(wifiAddress)
                connectionInfos.remove(wifiAddress)
                if (expectedPeerId != null && connectedGoPeerId == expectedPeerId) {
                    connectedGoPeerId = null
                }
                notifyState()
                onConnectionsChanged()
                onTcpPeerDisconnected(wifiAddress)
                onLog("WIFI-P2P", "TCP link $wifiAddress closed", false)
            }
        )
        tcpLinks[wifiAddress] = link
        connectionInfos[wifiAddress] = GattConnectionInfo(
            address = wifiAddress,
            peerId = expectedPeerId,
            role = role,
            negotiatedMtu = WIFI_DIRECT_VIRTUAL_MTU,
            state = "Connected",
            connectedAt = System.currentTimeMillis(),
            rssiDbm = -42,
            supports2MPhy = true,
            transportType = MeshProtocol.TransportProtocol.WIFI_DIRECT
        )
        notifyState()
        onConnectionsChanged()
        onLog("WIFI-P2P", "High-speed TCP link connected: $wifiAddress ($role)", false)
        onTcpPeerConnected(wifiAddress)
    }

    fun updatePeerIdentity(wifiAddress: String, peerId: String) {
        val current = connectionInfos[wifiAddress] ?: return
        if (current.peerId == peerId) return
        connectionInfos[wifiAddress] = current.copy(peerId = peerId)
        onConnectionsChanged()
    }

    fun hasActiveLinkToPeer(peerId: String): Boolean =
        connectionInfos.values.any { it.peerId == peerId && it.state == "Connected" }

    fun sendFrame(wifiAddress: String, frame: MeshProtocol.MeshFrame): Boolean {
        val link = tcpLinks[wifiAddress] ?: return false
        link.enqueue(frame)
        return true
    }

    fun getReadyAddresses(): List<String> = tcpLinks.keys.toList()

    fun getPeerIdForAddress(wifiAddress: String): String? = connectionInfos[wifiAddress]?.peerId

    fun getActiveConnections(): List<GattConnectionInfo> = connectionInfos.values.toList()

    fun getLinkSnapshot(wifiAddress: String): LinkTransportSnapshot? {
        val info = connectionInfos[wifiAddress] ?: return null
        val link = tcpLinks[wifiAddress] ?: return null
        return LinkTransportSnapshot(
            address = wifiAddress,
            negotiatedMtu = WIFI_DIRECT_VIRTUAL_MTU,
            rssiDbm = -42,
            supports2MPhy = true,
            queuedSlices = link.queuedCount.get(),
            role = info.role,
            transportType = MeshProtocol.TransportProtocol.WIFI_DIRECT
        )
    }

    fun getTotalQueuedFrames(): Int = tcpLinks.values.sumOf { it.queuedCount.get() }

    private fun notifyState() {
        onStateChanged(isP2pEnabled, isGroupOwner, tcpLinks.size)
    }

    private class TcpPeerLink(
        private val socket: Socket,
        scope: CoroutineScope,
        private val onFrame: (MeshProtocol.MeshFrame) -> Unit,
        private val onDisconnected: () -> Unit
    ) {
        private val expressQueue = ConcurrentLinkedQueue<ByteArray>()
        private val controlQueue = ConcurrentLinkedQueue<ByteArray>()
        private val bulkQueue = ConcurrentLinkedQueue<ByteArray>()
        private val wakeup = Channel<Unit>(capacity = Channel.CONFLATED)
        val queuedCount = AtomicInteger(0)
        private val closed = AtomicBoolean(false)

        private val writerJob = scope.launch {
            try {
                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 65536))
                while (!closed.get()) {
                    val nextBytes = pollNextWithPriority()
                    if (nextBytes == null) {
                        val sig = wakeup.receiveCatching()
                        if (sig.isClosed) break
                        continue
                    }
                    queuedCount.decrementAndGet().coerceAtLeast(0)
                    out.writeInt(nextBytes.size)
                    out.write(nextBytes)
                    if (expressQueue.isEmpty() && controlQueue.isEmpty() && bulkQueue.isEmpty()) {
                        out.flush()
                    } else if (!expressQueue.isEmpty()) {
                        out.flush()
                    }
                }
            } catch (_: Exception) {
                close()
            }
        }

        private val readerJob = scope.launch {
            try {
                val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 65536))
                while (!closed.get()) {
                    val len = input.readInt()
                    if (len <= 0 || len > 512 * 1024) break
                    val buf = ByteArray(len)
                    input.readFully(buf)
                    val frame = MeshProtocol.MeshFrame.decode(buf) ?: continue
                    onFrame(frame)
                }
            } catch (_: Exception) {
                // Socket closed or EOF
            } finally {
                close()
            }
        }

        private fun pollNextWithPriority(): ByteArray? =
            expressQueue.poll() ?: controlQueue.poll() ?: bulkQueue.poll()

        fun enqueue(frame: MeshProtocol.MeshFrame) {
            if (closed.get()) return
            val encoded = frame.encode()
            val target = when (frame.type.sendPriority) {
                MeshProtocol.SendPriority.EXPRESS -> expressQueue
                MeshProtocol.SendPriority.CONTROL -> controlQueue
                MeshProtocol.SendPriority.BULK -> bulkQueue
            }
            target.offer(encoded)
            queuedCount.incrementAndGet()
            wakeup.trySend(Unit)
        }

        fun close() {
            if (closed.getAndSet(true)) return
            wakeup.close()
            expressQueue.clear()
            controlQueue.clear()
            bulkQueue.clear()
            queuedCount.set(0)
            runCatching { socket.close() }
            writerJob.cancel()
            readerJob.cancel()
            onDisconnected()
        }
    }

    companion object {
        const val WIFI_DIRECT_VIRTUAL_MTU = 32_768
    }
}
