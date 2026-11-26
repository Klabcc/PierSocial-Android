package com.klabcc.piersocial.mesh

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Live link-quality and remote-node load metrics exchanged via LINK_PROBE / LINK_PROBE_ACK
 * and SYNC_VECTOR messages, plus empirical RSSI and transfer goodput measured locally.
 */
data class PeerLinkMetrics(
    val peerAddress: String,
    val peerId: String? = null,
    val role: String = "Central",
    val negotiatedMtu: Int = MeshProtocol.DEFAULT_MTU,
    val rssiDbm: Int = -65,
    val rttMs: Long = 0L,
    val supports2MPhy: Boolean = false,
    val remoteActiveUploads: Int = 0,
    val remoteQueuedSlices: Int = 0,
    val remoteConnectedPeers: Int = 0,
    val advertisedGoodputBps: Long = 0L,
    val measuredGoodputBps: Long = 0L,
    val localFailureCount: Int = 0,
    val lastUpdatedMs: Long = System.currentTimeMillis(),
    val transportType: MeshProtocol.TransportProtocol = MeshProtocol.TransportProtocol.BLE
)

data class MediaChunkAssignmentPlan(
    /** Map of peerAddress -> (mediaId -> list of chunk indices to request in parallel) */
    val requestsByPeer: Map<String, Map<String, List<Int>>>,
    /** Optimality scores computed for all connected addresses */
    val scoresByAddress: Map<String, Double>,
    /** Canonical addresses chosen as primary optimal download sources */
    val optimalAddresses: Set<String>
)

/**
 * Coordinates inter-node link scoring, optimal source selection, and weighted
 * multi-peer parallel chunk striping across independent BLE GATT connections.
 */
class OptimalPeerSelector(
    private val stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
    private val maxParallelPeersPerMedia: Int = DEFAULT_MAX_PARALLEL_PEERS,
    private val stragglerThresholdRatio: Double = DEFAULT_STRAGGLER_THRESHOLD
) {
    private data class ChunkKey(val mediaId: String, val chunkIndex: Int)
    private data class InFlightChunk(val peerAddress: String, val assignedAtMs: Long)
    private data class ByteWindow(var windowStartMs: Long, var windowBytes: Long, var ewmaBps: Long)

    private val peerMetrics = ConcurrentHashMap<String, PeerLinkMetrics>()
    private val peerCompleteMedia = ConcurrentHashMap<String, Set<String>>()
    private val inFlightChunks = ConcurrentHashMap<ChunkKey, InFlightChunk>()
    private val throughputWindows = ConcurrentHashMap<String, ByteWindow>()

    fun updateLinkMetrics(address: String, transform: (PeerLinkMetrics) -> PeerLinkMetrics) {
        peerMetrics.compute(address) { _, current ->
            val base = current ?: PeerLinkMetrics(peerAddress = address)
            transform(base).copy(lastUpdatedMs = System.currentTimeMillis())
        }
    }

    fun updatePeerMediaInventory(address: String, completeMediaIds: Collection<String>) {
        peerCompleteMedia[address] = completeMediaIds.toHashSet()
    }

    fun getLinkMetrics(address: String): PeerLinkMetrics? = peerMetrics[address]

    fun getAllLinkMetrics(): Map<String, PeerLinkMetrics> = peerMetrics.toMap()

    fun getAddressesWithMedia(mediaId: String): List<String> =
        peerCompleteMedia.entries
            .filter { mediaId in it.value && peerMetrics.containsKey(it.key) }
            .map { it.key }

    fun recordBytesReceived(address: String, bytesCount: Int, nowMs: Long = System.currentTimeMillis()) {
        if (bytesCount <= 0) return
        val window = throughputWindows.compute(address) { _, existing ->
            if (existing == null) {
                ByteWindow(nowMs, bytesCount.toLong(), 0L)
            } else {
                val elapsed = nowMs - existing.windowStartMs
                if (elapsed >= 1_000L) {
                    val sampleBps = ((existing.windowBytes + bytesCount) * 1000L) / elapsed.coerceAtLeast(1L)
                    val blended = if (existing.ewmaBps <= 0L) {
                        sampleBps
                    } else {
                        ((existing.ewmaBps * 6L) + (sampleBps * 4L)) / 10L
                    }
                    ByteWindow(nowMs, 0L, blended)
                } else {
                    existing.windowBytes += bytesCount.toLong()
                    existing
                }
            }
        }
        val measured = window?.ewmaBps ?: 0L
        if (measured > 0L) {
            updateLinkMetrics(address) { it.copy(measuredGoodputBps = measured) }
        }
    }

    fun onChunkReceived(mediaId: String, chunkIndex: Int) {
        inFlightChunks.remove(ChunkKey(mediaId, chunkIndex))
    }

    fun onMediaCompleted(mediaId: String) {
        inFlightChunks.keys.removeIf { it.mediaId == mediaId }
    }

    fun onPeerDisconnected(address: String) {
        peerMetrics.remove(address)
        peerCompleteMedia.remove(address)
        throughputWindows.remove(address)
        inFlightChunks.entries.removeIf { it.value.peerAddress == address }
    }

    fun recordLinkFailure(address: String) {
        updateLinkMetrics(address) { it.copy(localFailureCount = (it.localFailureCount + 1).coerceAtMost(10)) }
        inFlightChunks.entries.removeIf { it.value.peerAddress == address }
    }

    /**
     * Computes a deterministic optimality score (higher = better download source).
     * Weighs transport protocol (Wi-Fi Direct TCP vs BLE), ATT MTU payload capacity,
     * BLE 2M PHY support, measured RTT & empirical goodput, RSSI link margin, and
     * remote node upload/queue congestion.
     */
    fun computeOptimalityScore(metrics: PeerLinkMetrics): Double {
        val effectiveRttMs = if (metrics.rttMs > 0L) metrics.rttMs.coerceIn(5L, 2000L) else 75L
        val rttFactor = 100.0 / (effectiveRttMs + 50.0)
        val empiricalBps = maxOf(metrics.measuredGoodputBps, metrics.advertisedGoodputBps)
        val congestionPenalty = 1.0 / (
            1.0 +
                metrics.remoteActiveUploads * 0.45 +
                (metrics.remoteQueuedSlices / 40.0) * 0.35 +
                metrics.localFailureCount * 0.60
            )

        if (metrics.transportType == MeshProtocol.TransportProtocol.WIFI_DIRECT) {
            val wifiGoodputFactor = if (empiricalBps > 0L) {
                (empiricalBps / 150_000.0).coerceIn(0.60, 8.00)
            } else {
                1.0
            }
            return (850.0 * rttFactor * wifiGoodputFactor * congestionPenalty).coerceAtLeast(100.0)
        }

        val effectiveAttPayload = minOf(metrics.negotiatedMtu - 3, 495).coerceAtLeast(20)
        val mtuFactor = effectiveAttPayload / 495.0
        val phyMultiplier = if (metrics.supports2MPhy) 1.30 else 1.0
        val normalizedRssi = ((metrics.rssiDbm.coerceIn(-100, -40) + 100) / 60.0).coerceIn(0.0, 1.0)
        val rssiFactor = 0.45 + normalizedRssi * 0.65

        val goodputFactor = if (empiricalBps > 0L) {
            (empiricalBps / 25_000.0).coerceIn(0.35, 2.50)
        } else {
            1.0
        }

        return (mtuFactor * 60.0 * phyMultiplier * rttFactor * rssiFactor * goodputFactor * congestionPenalty)
            .coerceAtLeast(0.1)
    }

    /**
     * If dual GATT links (Central + Peripheral) happen to exist to the exact same remote peerId,
     * keeps the highest-scoring link so parallel transfers target distinct physical neighbor nodes.
     */
    fun selectCanonicalLinks(links: Collection<PeerLinkMetrics>): List<PeerLinkMetrics> {
        val scored = links.map { it to computeOptimalityScore(it) }
        val byPeer = LinkedHashMap<String, Pair<PeerLinkMetrics, Double>>()
        for ((link, score) in scored) {
            val key = link.peerId?.takeIf { it.isNotBlank() } ?: link.peerAddress
            val existing = byPeer[key]
            if (existing == null ||
                score > existing.second ||
                (score == existing.second && link.negotiatedMtu > existing.first.negotiatedMtu)
            ) {
                byPeer[key] = link to score
            }
        }
        return byPeer.values
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * Plans optimal parallel downloads across all connected peers for any media blobs that still
     * have missing chunks. Returns disjoint chunk-index assignments per peer.
     */
    fun planParallelDownloads(
        missingChunksByMedia: Map<String, List<Int>>,
        nowMs: Long = System.currentTimeMillis(),
        maxChunksPerMediaPerRound: Int = DEFAULT_BATCH_CHUNKS_PER_MEDIA
    ): MediaChunkAssignmentPlan {
        // Evict expired in-flight requests so stalled chunks are automatically stolen by faster peers
        inFlightChunks.entries.removeIf { (_, inflight) ->
            (nowMs - inflight.assignedAtMs) >= stallTimeoutMs || !peerMetrics.containsKey(inflight.peerAddress)
        }

        val allLinks = peerMetrics.values.toList()
        val scoresByAddress = allLinks.associate { it.peerAddress to computeOptimalityScore(it) }
        val canonicalLinks = selectCanonicalLinks(allLinks)

        val requestsByPeer = LinkedHashMap<String, MutableMap<String, MutableList<Int>>>()
        val optimalAddresses = LinkedHashSet<String>()

        for ((mediaId, allMissing) in missingChunksByMedia) {
            if (allMissing.isEmpty()) continue
            val eligibleChunks = allMissing.filter { chunkIdx ->
                val inflight = inFlightChunks[ChunkKey(mediaId, chunkIdx)]
                inflight == null
            }
            if (eligibleChunks.isEmpty()) continue

            val candidates = canonicalLinks
                .filter { link ->
                    val mediaSet = peerCompleteMedia[link.peerAddress]
                    mediaSet != null && mediaId in mediaSet
                }
                .map { it to (scoresByAddress[it.peerAddress] ?: computeOptimalityScore(it)) }
                .sortedByDescending { it.second }

            if (candidates.isEmpty()) continue

            val bestScore = candidates.first().second
            optimalAddresses.add(candidates.first().first.peerAddress)

            // Filter out severe stragglers so slow links don't bottleneck parallel assembly
            val selected = candidates
                .filter { (_, score) -> score >= bestScore * stragglerThresholdRatio }
                .take(maxParallelPeersPerMedia)
                .ifEmpty { listOf(candidates.first()) }

            val roundWindow = eligibleChunks.take(maxChunksPerMediaPerRound.coerceAtLeast(selected.size))
            val totalScore = selected.sumOf { it.second }.coerceAtLeast(0.01)

            // Distribute disjoint chunks across selected peers weighted by their optimality scores
            var cursor = 0
            selected.forEachIndexed { idx, (link, score) ->
                if (cursor >= roundWindow.size) return@forEachIndexed
                val remaining = roundWindow.size - cursor
                val quota = if (idx == selected.lastIndex) {
                    remaining
                } else {
                    ((roundWindow.size * (score / totalScore)).roundToInt()).coerceIn(1, remaining)
                }
                if (quota > 0) {
                    val assigned = roundWindow.subList(cursor, cursor + quota)
                    cursor += quota
                    val peerMap = requestsByPeer.getOrPut(link.peerAddress) { LinkedHashMap() }
                    peerMap.getOrPut(mediaId) { ArrayList(assigned.size) }.addAll(assigned)
                    for (chunkIdx in assigned) {
                        inFlightChunks[ChunkKey(mediaId, chunkIdx)] = InFlightChunk(link.peerAddress, nowMs)
                    }
                }
            }
        }

        // Fallback: if no missing media yet, still mark the highest-scoring canonical link as optimal
        if (optimalAddresses.isEmpty() && canonicalLinks.isNotEmpty()) {
            optimalAddresses.add(canonicalLinks.first().peerAddress)
        }

        return MediaChunkAssignmentPlan(
            requestsByPeer = requestsByPeer,
            scoresByAddress = scoresByAddress,
            optimalAddresses = optimalAddresses
        )
    }

    companion object {
        const val DEFAULT_STALL_TIMEOUT_MS = 3_500L
        const val DEFAULT_MAX_PARALLEL_PEERS = 3
        const val DEFAULT_STRAGGLER_THRESHOLD = 0.30
        const val DEFAULT_BATCH_CHUNKS_PER_MEDIA = 24
        const val WIFI_DIRECT_BATCH_CHUNKS_PER_MEDIA = 512
    }
}
