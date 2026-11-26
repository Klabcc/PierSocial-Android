package com.klabcc.piersocial.mesh

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.klabcc.piersocial.MainActivity
import com.klabcc.piersocial.PierSocialApp
import com.klabcc.piersocial.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Persistent ForegroundService (`foregroundServiceType="connectedDevice"`) implementing
 * Bitchat-style dual-role BLE mesh transport with high-speed parallel links:
 * 1. Peripheral / GATT Server + BluetoothLeAdvertiser
 * 2. Central / GATT Client + BluetoothLeScanner
 *
 * Optimizations:
 * - Parallel per-peer GATT queues using WRITE_TYPE_NO_RESPONSE for bulk media chunks
 * - BLE 2M PHY negotiation (API 26+) + CONNECTION_PRIORITY_HIGH (11.25–15ms intervals)
 * - Periodic RSSI + LINK_PROBE RTT/load telemetry fed into OptimalPeerSelector
 * - Concurrent multi-sequence SliceAccumulator so interleaved frames never clobber
 */
class BleMeshService : LifecycleService(), BleMeshTransport {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var meshController: MeshController
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null

    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var metaCharacteristic: BluetoothGattCharacteristic? = null

    // Active GATT Client connections (we are Central)
    private val clientGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val clientRxChars = ConcurrentHashMap<String, BluetoothGattCharacteristic>()
    private val connectingTimestamps = ConcurrentHashMap<String, Long>()

    // Active GATT Server connected devices (we are Peripheral)
    private val serverDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val serverSubscribed = ConcurrentHashMap<String, Boolean>()

    // Live link telemetry per peer address
    private val peerMtus = ConcurrentHashMap<String, Int>()
    private val peerRssi = ConcurrentHashMap<String, Int>()
    private val peerPhy2M = ConcurrentHashMap<String, Boolean>()
    private val connectionInfos = ConcurrentHashMap<String, GattConnectionInfo>()

    // Serialized outbound GATT queues (one independent async worker per peer link)
    private val clientSendQueues = ConcurrentHashMap<String, PeerSendQueue>()
    private val serverSendQueues = ConcurrentHashMap<String, PeerSendQueue>()

    // Multi-sequence slice reassembly buffers per peer address
    private val reassemblyBuffers = ConcurrentHashMap<String, SliceAccumulator>()
    private val frameSeqCounter = AtomicInteger(1)

    private var periodicSyncJob: Job? = null
    private var isAdvertisingActive = false
    private var isScanningActive = false
    private var advRetryWithoutScanResponse = false
    private var isBtReceiverRegistered = false
    private val json = Json { ignoreUnknownKeys = true }
    private var wifiDirectTransport: WifiDirectTransport? = null

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        meshController.appendLog("BT", "Bluetooth turned ON — starting BLE mesh radio", false)
                        startMeshRadio()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        meshController.appendLog("BT", "Bluetooth turned OFF — pausing BLE mesh radio", true)
                        stopMeshRadio()
                        meshController.updateRadioState { it.copy(bluetoothEnabled = false) }
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        meshController = (application as PierSocialApp).meshController
        meshController.serviceTransport = this
        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        createNotificationChannel()

        wifiDirectTransport = WifiDirectTransport(
            context = this,
            localPeerIdProvider = { (application as PierSocialApp).repository.localIdentity.peerId },
            onFrameReceived = { wifiAddr, mtu, frame ->
                meshController.syncManager.handleIncomingFrame(wifiAddr, mtu, frame)
            },
            onTcpPeerConnected = { wifiAddr ->
                meshController.syncManager.initiateHandshakeAndSync(wifiAddr)
            },
            onTcpPeerDisconnected = { wifiAddr ->
                meshController.syncManager.onPeerDisconnected(wifiAddr)
            },
            onOfferReadyToSend = { targetBleAddress, offer ->
                val localId = (application as PierSocialApp).repository.localIdentity.peerId
                val frame = MeshProtocol.MeshFrame(
                    type = MeshProtocol.PacketType.WIFI_DIRECT_OFFER,
                    packetId = "wdo_${localId.take(8)}_${System.currentTimeMillis()}",
                    originPeerId = localId,
                    targetPeerId = targetBleAddress?.let { connectionInfos[it]?.peerId }.orEmpty(),
                    ttl = 1,
                    payload = json.encodeToString(offer).toByteArray(Charsets.UTF_8)
                )
                if (targetBleAddress != null) {
                    sendFrame(targetBleAddress, frame)
                } else {
                    broadcastOfferOverBle(frame)
                }
            },
            onConnectionsChanged = {
                publishConnections()
            },
            onStateChanged = { enabled, isGo, count ->
                meshController.updateRadioState {
                    it.copy(
                        isWifiDirectEnabled = enabled,
                        isWifiDirectGroupOwner = isGo,
                        wifiDirectConnectedCount = count
                    )
                }
            },
            onLog = { tag, msg, isErr ->
                meshController.appendLog(tag, msg, isErr)
            }
        )

        registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
        isBtReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopMeshRadio()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification(peerCount = connectionInfos.size)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        meshController.updateRadioState { it.copy(isServiceRunning = true) }
        startMeshRadio()
        return START_STICKY
    }

    override fun onDestroy() {
        if (isBtReceiverRegistered) {
            runCatching { unregisterReceiver(bluetoothStateReceiver) }
            isBtReceiverRegistered = false
        }
        stopMeshRadio()
        meshController.serviceTransport = null
        meshController.updateRadioState {
            it.copy(isServiceRunning = false, isAdvertising = false, isScanning = false)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startMeshRadio() {
        if (!MeshController.hasRequiredMeshPermissions(this)) {
            meshController.appendLog("BLE", "Cannot start BLE radio: missing permissions", true)
            meshController.updateRadioState { it.copy(hasPermissions = false) }
            return
        }
        val adapter = bluetoothAdapter ?: (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        bluetoothAdapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            meshController.appendLog("BLE", "Bluetooth adapter disabled — waiting for BT ON", true)
            meshController.updateRadioState { it.copy(bluetoothEnabled = false) }
            return
        }
        meshController.updateRadioState { it.copy(bluetoothEnabled = true, hasPermissions = true) }

        setupGattServer()
        startAdvertising(adapter, includeScanResponse = !advRetryWithoutScanResponse)
        startScanning(adapter)
        wifiDirectTransport?.start()

        periodicSyncJob?.cancel()
        periodicSyncJob = serviceScope.launch {
            var tick = 0
            while (isActive) {
                delay(5_000L)
                tick++
                if (!isAdvertisingActive || !isScanningActive) {
                    bluetoothAdapter?.takeIf { it.isEnabled }?.let {
                        if (!isAdvertisingActive) startAdvertising(it, includeScanResponse = !advRetryWithoutScanResponse)
                        if (!isScanningActive) startScanning(it)
                    }
                }
                // Refresh live RSSI and exchange RTT + load probes on all active links
                for (gatt in clientGatts.values) {
                    runCatching { gatt.readRemoteRssi() }
                }
                val wifiPeers = wifiDirectTransport?.getReadyAddresses().orEmpty()
                val allPeers = (wifiPeers + clientGatts.keys + serverSubscribed.keys).distinct()
                for (addr in allPeers) {
                    meshController.syncManager.sendLinkProbe(addr)
                }
                meshController.syncManager.triggerParallelPullCheck()

                // Every 15 seconds, run full anti-entropy vector exchange safety net
                if (tick % 3 == 0) {
                    for (addr in allPeers) {
                        meshController.syncManager.initiateHandshakeAndSync(addr)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopMeshRadio() {
        periodicSyncJob?.cancel()
        wifiDirectTransport?.stop()
        runCatching {
            if (isAdvertisingActive && MeshController.hasRequiredMeshPermissions(this)) {
                advertiser?.stopAdvertising(advertiseCallback)
            }
        }
        runCatching {
            if (isScanningActive && MeshController.hasRequiredMeshPermissions(this)) {
                scanner?.stopScan(scanCallback)
            }
        }
        runCatching {
            if (MeshController.hasRequiredMeshPermissions(this)) {
                clientGatts.values.forEach { it.close() }
                gattServer?.close()
            }
        }
        clientSendQueues.values.forEach { it.close() }
        serverSendQueues.values.forEach { it.close() }
        clientSendQueues.clear()
        serverSendQueues.clear()
        clientGatts.clear()
        clientRxChars.clear()
        connectingTimestamps.clear()
        serverDevices.clear()
        serverSubscribed.clear()
        peerRssi.clear()
        peerPhy2M.clear()
        connectionInfos.clear()
        reassemblyBuffers.clear()
        gattServer = null
        isAdvertisingActive = false
        isScanningActive = false
        meshController.updateConnections(emptyList())
        meshController.updateRadioState { it.copy(isAdvertising = false, isScanning = false) }
    }

    @SuppressLint("MissingPermission")
    private fun setupGattServer() {
        if (gattServer != null) return
        val server = bluetoothManager?.openGattServer(this, gattServerCallback) ?: run {
            meshController.appendLog("GATT-SRV", "openGattServer returned null", true)
            return
        }
        gattServer = server

        val service = BluetoothGattService(
            MeshProtocol.MESH_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val tx = BluetoothGattCharacteristic(
            MeshProtocol.MESH_TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        ).apply {
            addDescriptor(
                BluetoothGattDescriptor(
                    MeshProtocol.CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
        }

        val rx = BluetoothGattCharacteristic(
            MeshProtocol.MESH_RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val meta = BluetoothGattCharacteristic(
            MeshProtocol.MESH_META_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        txCharacteristic = tx
        rxCharacteristic = rx
        metaCharacteristic = meta

        service.addCharacteristic(tx)
        service.addCharacteristic(rx)
        service.addCharacteristic(meta)
        val added = server.addService(service)
        meshController.appendLog("GATT-SRV", "Initialized PierSocial GATT Server (added=$added)", !added)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(adapter: BluetoothAdapter, includeScanResponse: Boolean) {
        if (isAdvertisingActive) return
        advertiser = adapter.bluetoothLeAdvertiser ?: run {
            meshController.appendLog("ADV", "BLE peripheral advertising unsupported on this device", true)
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(MeshProtocol.MESH_SERVICE_UUID))
            .build()

        if (includeScanResponse) {
            val scanResp = AdvertiseData.Builder()
                .addServiceData(
                    ParcelUuid(MeshProtocol.MESH_SERVICE_UUID),
                    (application as PierSocialApp).repository.localIdentity.peerId.take(8).toByteArray()
                )
                .build()
            advertiser?.startAdvertising(settings, data, scanResp, advertiseCallback)
        } else {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScanning(adapter: BluetoothAdapter) {
        if (isScanningActive) return
        scanner = adapter.bluetoothLeScanner ?: run {
            meshController.appendLog("SCAN", "bluetoothLeScanner unavailable", true)
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MeshProtocol.MESH_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(listOf(filter), settings, scanCallback)
        isScanningActive = true
        meshController.updateRadioState { it.copy(isScanning = true) }
        meshController.appendLog("SCAN", "Started low-latency BLE mesh scan for PierSocial UUID", false)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            isAdvertisingActive = true
            meshController.updateRadioState { it.copy(isAdvertising = true) }
            meshController.appendLog("ADV", "BLE Peripheral advertising active", false)
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertisingActive = false
            meshController.updateRadioState { it.copy(isAdvertising = false) }
            meshController.appendLog("ADV", "Advertising failed (code=$errorCode)", true)
            if (!advRetryWithoutScanResponse && errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE) {
                advRetryWithoutScanResponse = true
                bluetoothAdapter?.takeIf { it.isEnabled }?.let {
                    startAdvertising(it, includeScanResponse = false)
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val serviceBytes = result.scanRecord?.getServiceData(ParcelUuid(MeshProtocol.MESH_SERVICE_UUID))
            val peerIdHint = serviceBytes?.let { String(it, Charsets.UTF_8) }
            val devName = runCatching { device.name }.getOrNull()
            val name = peerIdHint?.let { "@pier-$it" } ?: devName ?: "Pier Peer"

            peerRssi[address] = result.rssi
            connectionInfos[address]?.let { existing ->
                connectionInfos[address] = existing.copy(rssiDbm = result.rssi)
            }

            meshController.upsertDiscoveredPeer(
                DiscoveredBlePeer(
                    address = address,
                    peerId = peerIdHint,
                    displayName = name,
                    rssi = result.rssi,
                    lastSeenAt = System.currentTimeMillis()
                )
            )

            val now = System.currentTimeMillis()
            val lastAttempt = connectingTimestamps[address] ?: 0L
            val alreadyConnectedToSamePeerId = !peerIdHint.isNullOrEmpty() &&
                connectionInfos.values.any { info ->
                    info.state == "Connected" &&
                        info.peerId?.startsWith(peerIdHint, ignoreCase = true) == true
                }
            if (!alreadyConnectedToSamePeerId &&
                !clientGatts.containsKey(address) &&
                !serverDevices.containsKey(address) &&
                (now - lastAttempt > 8_000L)
            ) {
                connectingTimestamps[address] = now
                connectToPeer(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanningActive = false
            meshController.updateRadioState { it.copy(isScanning = false) }
            meshController.appendLog("SCAN", "BLE Scan failed (code=$errorCode)", true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToPeer(device: BluetoothDevice) {
        if (!MeshController.hasRequiredMeshPermissions(this)) return
        val address = device.address
        meshController.appendLog("GATT-CLI", "Connecting to $address...", false)
        val gatt = device.connectGatt(this, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt != null) {
            clientGatts[address] = gatt
            connectionInfos[address] = GattConnectionInfo(
                address = address,
                peerId = null,
                role = "Central",
                negotiatedMtu = MeshProtocol.DEFAULT_MTU,
                state = "Connecting",
                connectedAt = System.currentTimeMillis(),
                rssiDbm = peerRssi[address] ?: -65,
                supports2MPhy = peerPhy2M[address] ?: false
            )
            publishConnections()
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val addr = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                peerMtus.putIfAbsent(addr, MeshProtocol.DEFAULT_MTU)
                connectionInfos[addr] = GattConnectionInfo(
                    address = addr,
                    peerId = connectionInfos[addr]?.peerId,
                    role = "Central",
                    negotiatedMtu = peerMtus[addr] ?: MeshProtocol.DEFAULT_MTU,
                    state = "Connected",
                    connectedAt = System.currentTimeMillis(),
                    rssiDbm = peerRssi[addr] ?: -65,
                    supports2MPhy = peerPhy2M[addr] ?: false
                )
                publishConnections()
                // Maximize BLE throughput: lower connection interval + 2M PHY when supported
                runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isLocal2MPhySupported()) {
                    runCatching {
                        gatt.setPreferredPhy(
                            BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_LE_2M_MASK,
                            BluetoothDevice.PHY_OPTION_NO_PREFERRED
                        )
                    }
                }
                runCatching { gatt.readRemoteRssi() }
                meshController.appendLog("GATT-CLI", "Connected to $addr (HIGH priority), requesting MTU 512", false)
                if (!gatt.requestMtu(MeshProtocol.TARGET_MTU)) {
                    gatt.discoverServices()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                clientGatts.remove(addr)
                clientRxChars.remove(addr)
                clientSendQueues.remove(addr)?.close()
                connectionInfos.remove(addr)
                reassemblyBuffers.remove(addr)
                peerPhy2M.remove(addr)
                connectingTimestamps[addr] = System.currentTimeMillis() - 4_000L
                meshController.syncManager.onPeerDisconnected(addr)
                publishConnections()
                gatt.close()
                meshController.appendLog("GATT-CLI", "Disconnected from $addr (status=$status)", status != BluetoothGatt.GATT_SUCCESS)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val addr = gatt.device.address
            val effectiveMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else MeshProtocol.DEFAULT_MTU
            peerMtus[addr] = effectiveMtu
            connectionInfos[addr]?.let {
                connectionInfos[addr] = it.copy(negotiatedMtu = effectiveMtu)
                publishConnections()
            }
            meshController.appendLog("MTU", "Negotiated MTU=$effectiveMtu with $addr", false)
            gatt.discoverServices()
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            val addr = gatt.device.address
            val is2M = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                status == BluetoothGatt.GATT_SUCCESS &&
                (txPhy == BluetoothDevice.PHY_LE_2M || rxPhy == BluetoothDevice.PHY_LE_2M)
            peerPhy2M[addr] = is2M
            connectionInfos[addr]?.let {
                connectionInfos[addr] = it.copy(supports2MPhy = is2M)
                publishConnections()
            }
            if (is2M) {
                meshController.appendLog("PHY", "Negotiated 2M PHY with $addr (tx=$txPhy, rx=$rxPhy)", false)
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val addr = gatt.device.address
            peerRssi[addr] = rssi
            connectionInfos[addr]?.let {
                connectionInfos[addr] = it.copy(rssiDbm = rssi)
                publishConnections()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val addr = gatt.device.address
            if (status != BluetoothGatt.GATT_SUCCESS) {
                meshController.appendLog("GATT-CLI", "Service discovery failed for $addr ($status)", true)
                gatt.disconnect()
                return
            }
            val service = gatt.services.firstOrNull {
                it.uuid == MeshProtocol.MESH_SERVICE_UUID &&
                    it.getCharacteristic(MeshProtocol.MESH_TX_CHAR_UUID) != null &&
                    it.getCharacteristic(MeshProtocol.MESH_RX_CHAR_UUID) != null
            } ?: run {
                meshController.appendLog("GATT-CLI", "PierSocial service/characteristics not found on $addr", true)
                gatt.disconnect()
                return
            }
            val tx = service.getCharacteristic(MeshProtocol.MESH_TX_CHAR_UUID)!!
            val rx = service.getCharacteristic(MeshProtocol.MESH_RX_CHAR_UUID)!!
            clientRxChars[addr] = rx

            val queue = getOrCreateClientQueue(addr, gatt)
            gatt.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(MeshProtocol.CCCD_UUID)
            if (cccd != null) {
                queue.enqueueOperations(
                    timeoutMs = 3_000L,
                    ops = listOf {
                        writeDescriptorCompat(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    }
                )
                return
            }
            meshController.syncManager.initiateHandshakeAndSync(addr)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val addr = gatt.device.address
            clientSendQueues[addr]?.completeCurrentOperation()
            if (descriptor.uuid == MeshProtocol.CCCD_UUID) {
                meshController.appendLog("GATT-CLI", "Subscribed to $addr TX notifications (status=$status)", status != BluetoothGatt.GATT_SUCCESS)
                meshController.syncManager.initiateHandshakeAndSync(addr)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                meshController.appendLog("GATT-CLI", "onCharacteristicWrite status=$status for ${gatt.device.address}", true)
                meshController.syncManager.peerSelector.recordLinkFailure(gatt.device.address)
            }
            clientSendQueues[gatt.device.address]?.completeCurrentOperation()
        }

        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val bytes = characteristic.value ?: return
                onSliceReceived(gatt.device.address, bytes)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onSliceReceived(gatt.device.address, value)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val addr = device.address
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                serverDevices[addr] = device
                peerMtus.putIfAbsent(addr, MeshProtocol.DEFAULT_MTU)
                connectionInfos[addr] = GattConnectionInfo(
                    address = addr,
                    peerId = connectionInfos[addr]?.peerId,
                    role = "Peripheral",
                    negotiatedMtu = peerMtus[addr] ?: MeshProtocol.DEFAULT_MTU,
                    state = "Connected",
                    connectedAt = System.currentTimeMillis(),
                    rssiDbm = peerRssi[addr] ?: -65,
                    supports2MPhy = peerPhy2M[addr] ?: false
                )
                publishConnections()
                meshController.appendLog("GATT-SRV", "Peer $addr connected to our GATT Server", false)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverDevices.remove(addr)
                serverSubscribed.remove(addr)
                serverSendQueues.remove(addr)?.close()
                connectionInfos.remove(addr)
                reassemblyBuffers.remove(addr)
                peerPhy2M.remove(addr)
                meshController.syncManager.onPeerDisconnected(addr)
                publishConnections()
                meshController.appendLog("GATT-SRV", "Peer $addr disconnected from GATT Server", false)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            val addr = device.address
            peerMtus[addr] = mtu
            connectionInfos[addr]?.let {
                connectionInfos[addr] = it.copy(negotiatedMtu = mtu)
                publishConnections()
            }
            meshController.appendLog("MTU", "GATT Server peer $addr MTU=$mtu", false)
        }

        override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            val addr = device.address
            val is2M = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                status == BluetoothGatt.GATT_SUCCESS &&
                (txPhy == BluetoothDevice.PHY_LE_2M || rxPhy == BluetoothDevice.PHY_LE_2M)
            peerPhy2M[addr] = is2M
            connectionInfos[addr]?.let {
                connectionInfos[addr] = it.copy(supports2MPhy = is2M)
                publishConnections()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val payload = if (characteristic.uuid == MeshProtocol.MESH_META_CHAR_UUID) {
                (application as PierSocialApp).repository.localIdentity.handle.toByteArray()
            } else {
                ByteArray(0)
            }
            val sliced = if (offset < payload.size) payload.copyOfRange(offset, payload.size) else ByteArray(0)
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, sliced)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            if (characteristic.uuid == MeshProtocol.MESH_RX_CHAR_UUID && value != null) {
                onSliceReceived(device.address, value)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            if (descriptor.uuid == MeshProtocol.CCCD_UUID) {
                val enabled = value != null && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (enabled) {
                    serverSubscribed[device.address] = true
                    meshController.appendLog("GATT-SRV", "Peer ${device.address} subscribed to TX notifications", false)
                    meshController.syncManager.initiateHandshakeAndSync(device.address)
                } else {
                    serverSubscribed.remove(device.address)
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                meshController.appendLog("GATT-SRV", "onNotificationSent status=$status for ${device.address}", true)
                meshController.syncManager.peerSelector.recordLinkFailure(device.address)
            }
            serverSendQueues[device.address]?.completeCurrentOperation()
        }
    }

    private fun onSliceReceived(peerAddress: String, slice: ByteArray) {
        if (slice.size < 6) return
        val acc = reassemblyBuffers.getOrPut(peerAddress) { SliceAccumulator() }
        val completeBytes = acc.appendSlice(slice) ?: return
        val frame = MeshProtocol.MeshFrame.decode(completeBytes) ?: return
        val mtu = peerMtus[peerAddress] ?: MeshProtocol.DEFAULT_MTU
        meshController.syncManager.handleIncomingFrame(peerAddress, mtu, frame)
    }

    @SuppressLint("MissingPermission")
    override fun sendFrame(peerAddress: String, frame: MeshProtocol.MeshFrame): Boolean {
        if (peerAddress.startsWith("wifi:")) {
            return wifiDirectTransport?.sendFrame(peerAddress, frame) ?: false
        }
        if (!MeshController.hasRequiredMeshPermissions(this)) return false
        val mtu = peerMtus[peerAddress] ?: MeshProtocol.DEFAULT_MTU
        val seq = ((frameSeqCounter.getAndIncrement() and 0x7F) + 1).toByte()
        val slices = MeshProtocol.sliceForGattTransport(frame.encode(), mtu, seq)
        val priority = frame.type.sendPriority
        val isExpress = priority == MeshProtocol.SendPriority.EXPRESS
        val isFast = frame.type.isFastNoResponse
        val retryDelayMs = if (isExpress) 5L else 15L

        val gatt = clientGatts[peerAddress]
        val rxChar = clientRxChars[peerAddress]
        if (gatt != null && rxChar != null) {
            val supportsNoResp =
                (rxChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            val writeType = if (isFast && supportsNoResp) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            val opTimeout = when {
                isExpress && writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE -> 150L
                writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE -> 180L
                isExpress -> 1_200L
                else -> 3_000L
            }
            val queue = getOrCreateClientQueue(peerAddress, gatt)
            queue.enqueueOperations(
                priority = priority,
                timeoutMs = opTimeout,
                ops = slices.map { slice ->
                    { writeCharacteristicCompat(gatt, rxChar, slice, writeType, retryDelayMs) }
                }
            )
            return true
        }

        val serverDevice = serverDevices[peerAddress]
        val txChar = txCharacteristic
        val server = gattServer
        if (serverDevice != null && txChar != null && server != null && serverSubscribed[peerAddress] == true) {
            val opTimeout = when {
                isExpress -> 150L
                frame.type.isBulkData -> 200L
                else -> 2_000L
            }
            val queue = getOrCreateServerQueue(peerAddress)
            queue.enqueueOperations(
                priority = priority,
                timeoutMs = opTimeout,
                ops = slices.map { slice ->
                    { notifyCharacteristicCompat(server, serverDevice, txChar, slice, retryDelayMs) }
                }
            )
            return true
        }
        return false
    }

    override fun broadcastFrame(excludeAddress: String?, frame: MeshProtocol.MeshFrame): Boolean {
        val wifiPeers = wifiDirectTransport?.getReadyAddresses().orEmpty()
        val blePeers = (clientGatts.keys + serverSubscribed.keys).distinct()
        val readyPeers = (wifiPeers + blePeers).distinct().filter { it != excludeAddress }
        if (readyPeers.isEmpty()) return false

        // Deduplicate links to the same peerId (strongly preferring Wi-Fi Direct TCP over BLE, then 2M PHY & highest MTU)
        val selectedByPeerId = LinkedHashMap<String, String>()
        val unmappedPeers = ArrayList<String>()
        for (addr in readyPeers) {
            val peerId = if (addr.startsWith("wifi:")) {
                wifiDirectTransport?.getPeerIdForAddress(addr)
            } else {
                connectionInfos[addr]?.peerId
            }
            if (peerId.isNullOrBlank()) {
                unmappedPeers.add(addr)
            } else {
                val existingAddr = selectedByPeerId[peerId]
                if (existingAddr == null || linkRank(addr) > linkRank(existingAddr)) {
                    selectedByPeerId[peerId] = addr
                }
            }
        }

        val targetId = frame.targetPeerId
        val orderedTargets = (selectedByPeerId.values + unmappedPeers).sortedByDescending { addr ->
            val pid = if (addr.startsWith("wifi:")) {
                wifiDirectTransport?.getPeerIdForAddress(addr)
            } else {
                connectionInfos[addr]?.peerId
            }
            when {
                targetId.isNotEmpty() && pid == targetId -> 1_000_000
                else -> linkRank(addr)
            }
        }

        var sentAny = false
        for (addr in orderedTargets) {
            if (sendFrame(addr, frame)) sentAny = true
        }
        return sentAny
    }

    private fun broadcastOfferOverBle(frame: MeshProtocol.MeshFrame) {
        val blePeers = (clientGatts.keys + serverSubscribed.keys).distinct()
        if (blePeers.isEmpty()) return
        val selectedByPeerId = LinkedHashMap<String, String>()
        val unmappedPeers = ArrayList<String>()
        for (addr in blePeers) {
            val peerId = connectionInfos[addr]?.peerId
            if (peerId.isNullOrBlank()) {
                unmappedPeers.add(addr)
            } else {
                val existing = selectedByPeerId[peerId]
                if (existing == null || linkRank(addr) > linkRank(existing)) {
                    selectedByPeerId[peerId] = addr
                }
            }
        }
        for (addr in (selectedByPeerId.values + unmappedPeers)) {
            sendFrame(addr, frame)
        }
    }

    private fun linkRank(addr: String): Int {
        if (addr.startsWith("wifi:")) return 100_000
        val mtu = peerMtus[addr] ?: connectionInfos[addr]?.negotiatedMtu ?: MeshProtocol.DEFAULT_MTU
        val phyBonus = if (peerPhy2M[addr] == true) 300 else 0
        val cliBonus = if (clientGatts.containsKey(addr) && clientRxChars.containsKey(addr)) 50 else 0
        return mtu + phyBonus + cliBonus
    }

    override fun triggerManualSync() {
        val adapter = bluetoothAdapter ?: (getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        bluetoothAdapter = adapter
        if (adapter != null && adapter.isEnabled && (!isAdvertisingActive || !isScanningActive || gattServer == null)) {
            startMeshRadio()
        } else {
            wifiDirectTransport?.start()
        }
        wifiDirectTransport?.triggerManualUpgrade()
        val wifiPeers = wifiDirectTransport?.getReadyAddresses().orEmpty()
        val allPeers = (wifiPeers + clientGatts.keys + serverSubscribed.keys).distinct()
        for (addr in allPeers) {
            meshController.syncManager.initiateHandshakeAndSync(addr)
        }
        meshController.syncManager.triggerParallelPullCheck()
    }

    override fun onPeerIdentityResolved(peerAddress: String, peerId: String) {
        if (peerAddress.startsWith("wifi:")) {
            wifiDirectTransport?.updatePeerIdentity(peerAddress, peerId)
        } else {
            wifiDirectTransport?.onBlePeerIdentified(peerAddress, peerId)
        }
    }

    override fun onWifiDirectOfferReceived(sourceBleAddress: String, offer: WifiDirectOfferPayload) {
        wifiDirectTransport?.onWifiDirectOfferReceived(sourceBleAddress, offer)
    }

    override fun getLinkSnapshot(peerAddress: String): LinkTransportSnapshot? {
        if (peerAddress.startsWith("wifi:")) {
            return wifiDirectTransport?.getLinkSnapshot(peerAddress)
        }
        val info = connectionInfos[peerAddress] ?: return null
        val queueDepth = (clientSendQueues[peerAddress]?.queuedCount?.get() ?: 0) +
            (serverSendQueues[peerAddress]?.queuedCount?.get() ?: 0)
        return LinkTransportSnapshot(
            address = peerAddress,
            negotiatedMtu = peerMtus[peerAddress] ?: info.negotiatedMtu,
            rssiDbm = peerRssi[peerAddress] ?: info.rssiDbm,
            supports2MPhy = peerPhy2M[peerAddress] ?: info.supports2MPhy,
            queuedSlices = queueDepth,
            role = info.role,
            transportType = MeshProtocol.TransportProtocol.BLE
        )
    }

    override fun getTotalQueuedSlices(): Int {
        val cli = clientSendQueues.values.sumOf { it.queuedCount.get() }
        val srv = serverSendQueues.values.sumOf { it.queuedCount.get() }
        val wifi = wifiDirectTransport?.getTotalQueuedFrames() ?: 0
        return cli + srv + wifi
    }

    override fun isLocal2MPhySupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            bluetoothAdapter?.isLe2MPhySupported == true
    }

    private fun getOrCreateClientQueue(addr: String, @Suppress("UNUSED_PARAMETER") gatt: BluetoothGatt): PeerSendQueue {
        return clientSendQueues.getOrPut(addr) { PeerSendQueue(serviceScope) }
    }

    private fun getOrCreateServerQueue(addr: String): PeerSendQueue {
        return serverSendQueues.getOrPut(addr) { PeerSendQueue(serviceScope) }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
        retryDelayMs: Long = 15L
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            for (attempt in 0 until 6) {
                val res = gatt.writeCharacteristic(characteristic, value, writeType)
                if (res == BluetoothStatusCodes.SUCCESS) return true
                if (res == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY && attempt < 5) {
                    delay(retryDelayMs)
                } else {
                    meshController.appendLog("GATT-CLI", "writeCharacteristic failed (code=$res)", true)
                    return false
                }
            }
            false
        } else {
            for (attempt in 0 until 4) {
                characteristic.value = value
                characteristic.writeType = writeType
                if (gatt.writeCharacteristic(characteristic)) return true
                if (attempt < 3) delay(retryDelayMs)
            }
            false
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            for (attempt in 0 until 4) {
                val res = gatt.writeDescriptor(descriptor, value)
                if (res == BluetoothStatusCodes.SUCCESS) return true
                if (res == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY && attempt < 3) {
                    delay(20L)
                } else {
                    meshController.appendLog("GATT-CLI", "writeDescriptor failed (code=$res)", true)
                    return false
                }
            }
            false
        } else {
            descriptor.value = value
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun notifyCharacteristicCompat(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        retryDelayMs: Long = 15L
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            for (attempt in 0 until 6) {
                val res = server.notifyCharacteristicChanged(device, characteristic, false, value)
                if (res == BluetoothStatusCodes.SUCCESS) return true
                if (res == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY && attempt < 5) {
                    delay(retryDelayMs)
                } else {
                    meshController.appendLog("GATT-SRV", "notifyCharacteristicChanged failed (code=$res)", true)
                    return false
                }
            }
            false
        } else {
            for (attempt in 0 until 4) {
                characteristic.value = value
                if (server.notifyCharacteristicChanged(device, characteristic, false)) return true
                if (attempt < 3) delay(retryDelayMs)
            }
            false
        }
    }

    private fun publishConnections() {
        val list = connectionInfos.values.toList() + wifiDirectTransport?.getActiveConnections().orEmpty()
        meshController.updateConnections(list)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(list.size))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PierSocial BLE Mesh",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent Bluetooth Low Energy mesh relay & sync"
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(peerCount: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PierSocial BLE Mesh Active")
            .setContentText("$peerCount connected peer(s) • Parallel 2M PHY Mesh enabled")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    /**
     * Strict 3-tier priority GATT dispatcher (EXPRESS DMs/ACKs > CONTROL > BULK chunks).
     * Guarantees DM packets preempt any queued bulk media transfers immediately.
     */
    private class PeerSendQueue(scope: CoroutineScope) {
        private data class QueuedOp(val timeoutMs: Long, val op: suspend () -> Boolean)

        private val expressQueue = ConcurrentLinkedQueue<QueuedOp>()
        private val controlQueue = ConcurrentLinkedQueue<QueuedOp>()
        private val bulkQueue = ConcurrentLinkedQueue<QueuedOp>()
        private val wakeup = Channel<Unit>(capacity = Channel.CONFLATED)
        val queuedCount = AtomicInteger(0)

        @Volatile
        private var currentDeferred: CompletableDeferred<Unit>? = null

        @Volatile
        private var isClosed = false

        private val worker = scope.launch {
            while (!isClosed) {
                val next = pollNext()
                if (next == null) {
                    val res = wakeup.receiveCatching()
                    if (res.isClosed) break
                    continue
                }
                queuedCount.decrementAndGet().coerceAtLeast(0)
                val deferred = CompletableDeferred<Unit>()
                currentDeferred = deferred
                val started = runCatching { next.op() }.getOrDefault(false)
                if (started) {
                    withTimeoutOrNull(next.timeoutMs) {
                        deferred.await()
                    }
                }
                if (currentDeferred === deferred) {
                    currentDeferred = null
                }
            }
        }

        private fun pollNext(): QueuedOp? =
            expressQueue.poll() ?: controlQueue.poll() ?: bulkQueue.poll()

        fun enqueueOperations(
            priority: MeshProtocol.SendPriority = MeshProtocol.SendPriority.CONTROL,
            timeoutMs: Long = 3_000L,
            ops: List<suspend () -> Boolean>
        ) {
            if (isClosed || ops.isEmpty()) return
            val targetQueue = when (priority) {
                MeshProtocol.SendPriority.EXPRESS -> expressQueue
                MeshProtocol.SendPriority.CONTROL -> controlQueue
                MeshProtocol.SendPriority.BULK -> bulkQueue
            }
            for (op in ops) {
                targetQueue.offer(QueuedOp(timeoutMs, op))
                queuedCount.incrementAndGet()
            }
            wakeup.trySend(Unit)
        }

        fun completeCurrentOperation() {
            currentDeferred?.complete(Unit)
        }

        fun close() {
            isClosed = true
            currentDeferred?.complete(Unit)
            wakeup.close()
            expressQueue.clear()
            controlQueue.clear()
            bulkQueue.clear()
            queuedCount.set(0)
            worker.cancel()
        }
    }

    /**
     * Supports concurrent/interleaved multi-sequence frame reassembly per peer link.
     */
    private class SliceAccumulator {
        private class InFlightFrame(
            val total: Int,
            var lastIdx: Int = 0,
            var updatedAtMs: Long = System.currentTimeMillis(),
            val out: ByteArrayOutputStream = ByteArrayOutputStream()
        )

        private val framesBySeq = HashMap<Byte, InFlightFrame>()

        @Synchronized
        fun appendSlice(slice: ByteArray): ByteArray? {
            if (slice.size < 6) return null
            val seq = slice[0]
            val idx = ((slice[1].toInt() and 0xFF) shl 8) or (slice[2].toInt() and 0xFF)
            val total = ((slice[3].toInt() and 0xFF) shl 8) or (slice[4].toInt() and 0xFF)
            if (total <= 0) return null

            val now = System.currentTimeMillis()
            if (idx == 1) {
                if (framesBySeq.size > 16) {
                    framesBySeq.entries.removeIf { now - it.value.updatedAtMs > 15_000L }
                }
                framesBySeq[seq] = InFlightFrame(total = total, lastIdx = 0, updatedAtMs = now)
            }

            val inflight = framesBySeq[seq] ?: return null
            if (idx != inflight.lastIdx + 1 || total != inflight.total) {
                framesBySeq.remove(seq)
                return null
            }

            inflight.out.write(slice, 5, slice.size - 5)
            inflight.lastIdx = idx
            inflight.updatedAtMs = now
            return if (idx == inflight.total) {
                framesBySeq.remove(seq)
                inflight.out.toByteArray()
            } else {
                null
            }
        }
    }

    companion object {
        const val ACTION_START = "com.klabcc.piersocial.mesh.START"
        const val ACTION_STOP = "com.klabcc.piersocial.mesh.STOP"
        private const val CHANNEL_ID = "piersocial_ble_mesh"
        private const val NOTIFICATION_ID = 4041
    }
}
