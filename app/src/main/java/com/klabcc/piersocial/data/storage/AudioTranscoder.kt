package com.klabcc.piersocial.data.storage

import com.beatofthedrum.alacdecoder.AlacContext
import com.beatofthedrum.alacdecoder.AlacUtils
import com.jcraft.jogg.Packet
import com.jcraft.jogg.Page
import com.jcraft.jogg.StreamState
import com.jcraft.jogg.SyncState
import com.jcraft.jorbis.Block
import com.jcraft.jorbis.Comment
import com.jcraft.jorbis.DspState
import com.jcraft.jorbis.Info
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusMSDecoder
import org.gagravarr.ogg.OggFile
import org.gagravarr.ogg.OggStreamIdentifier
import org.gagravarr.opus.OpusFile
import org.jflac.FLACDecoder
import org.jflac.FrameListener
import org.jflac.frame.Frame
import org.jflac.metadata.Metadata
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

data class DecodedMonoPcm(
    val sampleRate: Int,
    val samples: FloatArray,
    val durationMs: Int,
    val codecLabel: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedMonoPcm) return false
        return sampleRate == other.sampleRate &&
            durationMs == other.durationMs &&
            codecLabel == other.codecLabel &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = sampleRate
        result = 31 * result + samples.contentHashCode()
        result = 31 * result + durationMs
        result = 31 * result + codecLabel.hashCode()
        return result
    }
}

data class ImportedAudioMetadata(
    val durationMs: Int,
    val codecLabel: String
)

data class EncodedMp3Result(
    val file: File,
    val durationMs: Int,
    val amplitudes: List<Float>,
    val sampleRate: Int,
    val bitrateKbps: Int
)

/**
 * Pure-Kotlin/Java audio utilities for multi-channel -> mono downmixing, sample-rate conversion,
 * pure-Java container/codec decoding (FLAC, Opus, Ogg Vorbis, ALAC, WAV, AIFF), and Jump3r/LAME
 * mono MP3 encoding without any `javax.sound.sampled` dependency.
 */
object AudioPcmProcessor {
    private val SUPPORTED_MP3_RATES = intArrayOf(
        8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000
    )

    fun selectValidMp3SampleRate(sourceRate: Int): Int {
        if (sourceRate <= 0) return 44100
        if (sourceRate in SUPPORTED_MP3_RATES) return sourceRate
        if (sourceRate > 48000) {
            return if (sourceRate % 11025 == 0) 44100 else 48000
        }
        return SUPPORTED_MP3_RATES.minByOrNull { abs(it - sourceRate) } ?: 44100
    }

    fun mixInterleavedFloatsToMono(
        interleaved: FloatArray,
        frames: Int,
        channels: Int
    ): FloatArray {
        if (frames <= 0 || channels <= 0) return FloatArray(0)
        if (channels == 1) return interleaved.copyOf(frames)
        val out = FloatArray(frames)
        val inv = 1f / channels.toFloat()
        var idx = 0
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += interleaved[idx++]
            }
            out[f] = (sum * inv).coerceIn(-1f, 1f)
        }
        return out
    }

    fun decodePcmBytesToMonoFloats(
        bytes: ByteArray,
        size: Int,
        channels: Int,
        bitsPerSample: Int,
        bigEndian: Boolean = false,
        isFloat: Boolean = false,
        isUnsigned8Bit: Boolean = false,
        audioFormatTag: Int = 1 // 1=PCM, 3=IEEE_FLOAT, 6=ALAW, 7=ULAW
    ): FloatArray {
        if (size <= 0 || channels <= 0) return FloatArray(0)
        val bytesPerSample = when {
            audioFormatTag == 6 || audioFormatTag == 7 -> 1
            else -> (bitsPerSample + 7) / 8
        }.coerceAtLeast(1)
        val frameBytes = bytesPerSample * channels
        val frameCount = size / frameBytes
        if (frameCount <= 0) return FloatArray(0)

        val out = FloatArray(frameCount)
        val invChannels = 1f / channels.toFloat()
        var pos = 0

        for (f in 0 until frameCount) {
            var sum = 0f
            for (c in 0 until channels) {
                val sampleFloat: Float = when {
                    audioFormatTag == 6 -> decodeAlaw(bytes[pos])
                    audioFormatTag == 7 -> decodeMulaw(bytes[pos])
                    isFloat && bytesPerSample == 4 -> {
                        val bits = readInt32(bytes, pos, bigEndian)
                        java.lang.Float.intBitsToFloat(bits).coerceIn(-1f, 1f)
                    }
                    isFloat && bytesPerSample == 8 -> {
                        val bits = readInt64(bytes, pos, bigEndian)
                        java.lang.Double.longBitsToDouble(bits).toFloat().coerceIn(-1f, 1f)
                    }
                    bytesPerSample == 1 -> {
                        if (isUnsigned8Bit) {
                            ((bytes[pos].toInt() and 0xFF) - 128) / 128f
                        } else {
                            bytes[pos].toFloat() / 128f
                        }
                    }
                    bytesPerSample == 2 -> {
                        val s = if (bigEndian) {
                            ((bytes[pos].toInt() shl 8) or (bytes[pos + 1].toInt() and 0xFF)).toShort()
                        } else {
                            ((bytes[pos + 1].toInt() shl 8) or (bytes[pos].toInt() and 0xFF)).toShort()
                        }
                        s.toFloat() / 32768f
                    }
                    bytesPerSample == 3 -> {
                        val v = if (bigEndian) {
                            ((bytes[pos].toInt() shl 24) or
                                ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                                ((bytes[pos + 2].toInt() and 0xFF) shl 8)) shr 8
                        } else {
                            ((bytes[pos + 2].toInt() shl 24) or
                                ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                                ((bytes[pos].toInt() and 0xFF) shl 8)) shr 8
                        }
                        v.toFloat() / 8388608f
                    }
                    bytesPerSample == 4 -> {
                        val v = readInt32(bytes, pos, bigEndian)
                        (v.toDouble() / 2147483648.0).toFloat()
                    }
                    else -> 0f
                }
                sum += sampleFloat
                pos += bytesPerSample
            }
            out[f] = (sum * invChannels).coerceIn(-1f, 1f)
        }
        return out
    }

    fun resampleMono(samples: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        if (samples.isEmpty() || srcRate <= 0 || dstRate <= 0 || srcRate == dstRate) {
            return samples
        }
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outLen = ((samples.size.toDouble() * dstRate.toDouble()) / srcRate.toDouble())
            .roundToInt()
            .coerceAtLeast(1)
        val out = FloatArray(outLen)
        val filterRadius = if (ratio > 1.25) ((ratio * 0.5).toInt()).coerceIn(1, 6) else 0

        for (i in 0 until outLen) {
            val srcPos = i.toDouble() * ratio
            if (filterRadius > 0) {
                val center = srcPos.toInt().coerceIn(0, samples.lastIndex)
                val from = (center - filterRadius).coerceAtLeast(0)
                val to = (center + filterRadius).coerceAtMost(samples.lastIndex)
                var sum = 0f
                var count = 0
                for (j in from..to) {
                    sum += samples[j]
                    count++
                }
                out[i] = if (count > 0) sum / count else samples[center]
            } else {
                val idx0 = srcPos.toInt().coerceIn(0, samples.lastIndex)
                val idx1 = (idx0 + 1).coerceAtMost(samples.lastIndex)
                val frac = (srcPos - idx0).toFloat()
                out[i] = samples[idx0] + (samples[idx1] - samples[idx0]) * frac
            }
        }
        return out
    }

    fun computeWaveformBars(samples: FloatArray, targetBars: Int = 30): List<Float> {
        if (samples.isEmpty()) return List(targetBars) { 0.35f }
        val raw = List(targetBars) { bar ->
            val start = (bar * samples.size) / targetBars
            val end = (((bar + 1) * samples.size) / targetBars).coerceAtLeast(start + 1)
                .coerceAtMost(samples.size)
            var sumSq = 0.0
            val count = (end - start).coerceAtLeast(1)
            val step = (count / 64).coerceAtLeast(1)
            var n = 0
            var i = start
            while (i < end) {
                val v = samples[i].toDouble()
                sumSq += v * v
                n++
                i += step
            }
            if (n > 0) kotlin.math.sqrt(sumSq / n).toFloat() else 0.1f
        }
        val maxVal = raw.maxOrNull()?.coerceAtLeast(1e-3f) ?: 1f
        return raw.map { (it / maxVal).coerceIn(0.14f, 1f) }
    }

    private fun readInt32(bytes: ByteArray, pos: Int, bigEndian: Boolean): Int =
        if (bigEndian) {
            ((bytes[pos].toInt() and 0xFF) shl 24) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
        } else {
            (bytes[pos].toInt() and 0xFF) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 3].toInt() and 0xFF) shl 24)
        }

    private fun readInt64(bytes: ByteArray, pos: Int, bigEndian: Boolean): Long {
        var result = 0L
        if (bigEndian) {
            for (i in 0 until 8) {
                result = (result shl 8) or (bytes[pos + i].toLong() and 0xFFL)
            }
        } else {
            for (i in 7 downTo 0) {
                result = (result shl 8) or (bytes[pos + i].toLong() and 0xFFL)
            }
        }
        return result
    }

    private fun decodeMulaw(uValByte: Byte): Float {
        val uVal = (uValByte.toInt() and 0xFF).inv()
        val sign = uVal and 0x80
        val exponent = (uVal shr 4) and 0x07
        val mantissa = uVal and 0x0F
        var sample = ((mantissa shl 3) + 0x84) shl exponent
        sample -= 0x84
        val signed = if (sign != 0) -sample else sample
        return (signed / 32768f).coerceIn(-1f, 1f)
    }

    private fun decodeAlaw(aValByte: Byte): Float {
        val aVal = (aValByte.toInt() and 0xFF) xor 0x55
        val sign = aVal and 0x80
        val exponent = (aVal and 0x70) shr 4
        var data = (aVal and 0x0F) shl 4
        data += 8
        if (exponent != 0) data += 0x100
        if (exponent > 1) data = data shl (exponent - 1)
        val signed = if (sign != 0) data else -data
        return (signed / 32768f).coerceIn(-1f, 1f)
    }
}

/**
 * Pure-Java mono MP3 encoder built directly on Jump3r's LAME engine without `javax.sound.sampled`.
 */
class StreamingMonoMp3Encoder(
    rawSampleRate: Int,
    private val bitrateKbps: Int = 48,
    private val outputStream: java.io.OutputStream
) : Closeable {
    val sampleRate: Int = AudioPcmProcessor.selectValidMp3SampleRate(rawSampleRate)
    private val srcRate: Int = rawSampleRate.coerceAtLeast(8000)

    private val lame = Lame()
    private val ga = GainAnalysis()
    private val bs = BitStream()
    private val p = Presets()
    private val qupvt = QuantizePVT()
    private val qu = Quantize()
    private val vbr = VBRTag()
    private val ver = Version()
    private val id3 = ID3Tag()
    private val rv = Reservoir()
    private val tak = Takehiro()
    private val mpg = MPGLib()
    private val intf = Interface()
    private val common = Common()
    private val gfp: LameGlobalFlags

    private var closed = false
    private var encodedFrameCount = 0

    init {
        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        p.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lame, bs, ver)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)

        gfp = lame.lame_init().apply {
            num_channels = 1
            in_samplerate = sampleRate
            out_samplerate = sampleRate
            mode = MPEGMode.MONO
            brate = bitrateKbps
            quality = 2
            write_id3tag_automatic = false
            bWriteVbrTag = false
            findReplayGain = false
        }
        id3.id3tag_init(gfp)
        val rc = lame.lame_init_params(gfp)
        require(rc >= 0) { "LAME initialization failed (rc=$rc, rate=$sampleRate, brate=$bitrateKbps)" }
    }

    fun encodeMonoFloats(samples: FloatArray, count: Int = samples.size) {
        if (closed || count <= 0) return
        val working = if (srcRate != sampleRate) {
            AudioPcmProcessor.resampleMono(
                if (count == samples.size) samples else samples.copyOf(count),
                srcRate,
                sampleRate
            )
        } else if (count == samples.size) {
            samples
        } else {
            samples.copyOf(count)
        }
        if (working.isEmpty()) return

        val chunkSize = 8192
        var offset = 0
        val leftInt = IntArray(chunkSize)
        val rightZeros = IntArray(chunkSize)
        val mp3Buf = ByteArray((chunkSize * 5 / 4) + 7200)

        while (offset < working.size) {
            val n = minOf(chunkSize, working.size - offset)
            for (i in 0 until n) {
                val clamped = (working[offset + i].coerceIn(-1f, 1f) * 32767f).roundToInt()
                leftInt[i] = clamped shl 16
            }
            val written = lame.lame_encode_buffer_int(gfp, leftInt, rightZeros, n, mp3Buf, 0, mp3Buf.size)
            if (written > 0) {
                outputStream.write(mp3Buf, 0, written)
                encodedFrameCount++
            } else if (written < 0) {
                throw IllegalStateException("LAME encode error $written")
            }
            offset += n
        }
    }

    fun encodeMonoShorts(samples: ShortArray, count: Int = samples.size) {
        if (closed || count <= 0) return
        val floats = FloatArray(count) { i -> samples[i].toFloat() / 32768f }
        encodeMonoFloats(floats, count)
    }

    fun finish() {
        if (closed) return
        closed = true
        val flushBuf = ByteArray(16384)
        val flushed = lame.lame_encode_flush(gfp, flushBuf, 0, flushBuf.size)
        if (flushed > 0) {
            outputStream.write(flushBuf, 0, flushed)
        }
        outputStream.flush()
        lame.lame_close(gfp)
    }

    override fun close() {
        finish()
    }
}

object MonoMp3Encoder {
    private val CANDIDATE_BITRATES = intArrayOf(64, 48, 32)

    fun encodeMonoPcmToMp3File(
        samples: FloatArray,
        sampleRate: Int,
        outFile: File,
        maxBytes: Long = StoryAudioRecorder.MAX_BLE_AUDIO_BYTES
    ): EncodedMp3Result? {
        if (samples.isEmpty()) return null
        val targetRate = AudioPcmProcessor.selectValidMp3SampleRate(sampleRate)
        val resampled = AudioPcmProcessor.resampleMono(samples, sampleRate.coerceAtLeast(8000), targetRate)
        if (resampled.isEmpty()) return null

        val durationMs = ((resampled.size.toLong() * 1000L) / targetRate.toLong())
            .toInt()
            .coerceIn(StoryAudioRecorder.MIN_DURATION_MS, StoryAudioRecorder.MAX_DURATION_MS)
        val bars = AudioPcmProcessor.computeWaveformBars(resampled)

        for (bitrate in CANDIDATE_BITRATES) {
            val baos = ByteArrayOutputStream()
            val ok = runCatching {
                StreamingMonoMp3Encoder(
                    rawSampleRate = targetRate,
                    bitrateKbps = bitrate,
                    outputStream = baos
                ).use { enc ->
                    enc.encodeMonoFloats(resampled)
                    enc.finish()
                }
                true
            }.getOrDefault(false)
            if (!ok) continue

            val mp3Bytes = baos.toByteArray()
            if (mp3Bytes.size > 256 && mp3Bytes.size.toLong() <= maxBytes) {
                outFile.writeBytes(mp3Bytes)
                return EncodedMp3Result(
                    file = outFile,
                    durationMs = durationMs,
                    amplitudes = bars,
                    sampleRate = targetRate,
                    bitrateKbps = bitrate
                )
            }
        }
        return null
    }
}

/**
 * Pure-Java decoders for FLAC, Ogg Opus, Ogg Vorbis, ALAC (M4A), RIFF WAV, and AIFF/AIFC.
 * Runs cleanly on both Android (minSdk 24+) and JVM unit tests with zero native `.so` dependencies.
 */
object PureAudioContainerDecoder {
    private class TargetReachedException : RuntimeException()

    fun inspectTrackMetadata(file: File): ImportedAudioMetadata? {
        if (!file.exists() || file.length() < 16L) return null
        val head = readHeaderBytes(file, 32) ?: return null

        if (isWavHeader(head)) {
            val info = parseWavInfo(file) ?: return null
            val durMs = ((info.totalFrames * 1000L) / info.sampleRate).toInt()
                .coerceAtLeast(StoryAudioRecorder.MIN_DURATION_MS)
            return ImportedAudioMetadata(durMs, "WAV")
        }
        if (isAiffHeader(head)) {
            val info = parseAiffInfo(file) ?: return null
            val durMs = ((info.totalFrames * 1000L) / info.sampleRate).toInt()
                .coerceAtLeast(StoryAudioRecorder.MIN_DURATION_MS)
            return ImportedAudioMetadata(durMs, if (info.isAifc) "AIFC" else "AIFF")
        }
        if (isFlacFile(file, head)) {
            val durMs = inspectFlacDurationMs(file) ?: return null
            return ImportedAudioMetadata(durMs, "FLAC")
        }
        if (isOggHeader(head)) {
            return inspectOggMetadata(file)
        }
        if (isAlacMp4(file, head)) {
            val durMs = inspectAlacDurationMs(file) ?: return null
            return ImportedAudioMetadata(durMs, "ALAC")
        }
        return null
    }

    fun decodeSegment(file: File, startMs: Int, durationMs: Int): DecodedMonoPcm? {
        if (!file.exists() || file.length() < 16L) return null
        val head = readHeaderBytes(file, 32) ?: return null
        val clampedDurationMs = durationMs.coerceIn(StoryAudioRecorder.MIN_DURATION_MS, StoryAudioRecorder.MAX_DURATION_MS)

        if (isWavHeader(head)) {
            return decodeWavSegment(file, startMs, clampedDurationMs)
        }
        if (isAiffHeader(head)) {
            return decodeAiffSegment(file, startMs, clampedDurationMs)
        }
        if (isFlacFile(file, head)) {
            return decodeFlacSegment(file, startMs, clampedDurationMs)
        }
        if (isOggHeader(head)) {
            return decodeOggSegment(file, startMs, clampedDurationMs)
        }
        if (isAlacMp4(file, head)) {
            return decodeAlacSegment(file, startMs, clampedDurationMs)
        }
        return null
    }

    private fun readHeaderBytes(file: File, count: Int): ByteArray? = runCatching {
        FileInputStream(file).use { fis ->
            val buf = ByteArray(count)
            val n = fis.read(buf)
            if (n < 12) null else buf.copyOf(n)
        }
    }.getOrNull()

    private fun isWavHeader(head: ByteArray): Boolean =
        head.size >= 12 &&
            head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
            head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() &&
            head[8] == 'W'.code.toByte() && head[9] == 'A'.code.toByte() &&
            head[10] == 'V'.code.toByte() && head[11] == 'E'.code.toByte()

    private fun isAiffHeader(head: ByteArray): Boolean =
        head.size >= 12 &&
            head[0] == 'F'.code.toByte() && head[1] == 'O'.code.toByte() &&
            head[2] == 'R'.code.toByte() && head[3] == 'M'.code.toByte() &&
            head[8] == 'A'.code.toByte() && head[9] == 'I'.code.toByte() &&
            head[10] == 'F'.code.toByte() &&
            (head[11] == 'F'.code.toByte() || head[11] == 'C'.code.toByte())

    private fun isOggHeader(head: ByteArray): Boolean =
        head.size >= 4 &&
            head[0] == 'O'.code.toByte() && head[1] == 'g'.code.toByte() &&
            head[2] == 'g'.code.toByte() && head[3] == 'S'.code.toByte()

    private fun isFlacFile(file: File, head: ByteArray): Boolean {
        if (head.size >= 4 &&
            head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
            head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte()
        ) return true
        // ID3v2 tag before FLAC sync
        if (head.size >= 10 &&
            head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()
        ) {
            return runCatching {
                BufferedInputStream(FileInputStream(file)).use { bis ->
                    val dec = FLACDecoder(bis)
                    dec.readStreamInfo() != null
                }
            }.getOrDefault(false)
        }
        return false
    }

    private fun isAlacMp4(file: File, head: ByteArray): Boolean {
        return !(head.size < 8 ||
                head[4] != 'f'.code.toByte() || head[5] != 't'.code.toByte() ||
                head[6] != 'y'.code.toByte() || head[7] != 'p'.code.toByte()) && runCatching {
            val ac = AlacContext()
            AlacUtils.AlacOpenFileInput(ac, file)
            val ok = !ac.error && AlacUtils.AlacGetNumSamples(ac) > 0
            runCatching { ac.input_stream?.close() }
            ok
        }.getOrDefault(false)
    }

    // ---- WAV / RIFF ----
    private data class WavInfo(
        val formatTag: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val blockAlign: Int,
        val dataOffset: Long,
        val dataSize: Long
    ) {
        val totalFrames: Long
            get() = if (blockAlign <= 0) 0L else dataSize / blockAlign
    }

    private fun parseWavInfo(file: File): WavInfo? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (len < 36) return@use null
            raf.seek(12L)
            var fmtTag = 0
            var channels = 0
            var sampleRate = 0
            var bitsPerSample = 0
            var blockAlign = 0
            var dataOffset = -1L
            var dataSize = 0L

            val chunkHeader = ByteArray(8)
            while (raf.filePointer + 8 <= len) {
                raf.readFully(chunkHeader)
                val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val chunkStart = raf.filePointer
                when (id) {
                    "fmt " -> {
                        if (chunkSize >= 16) {
                            val fmtBuf = ByteArray(minOf(chunkSize, 40L).toInt())
                            raf.readFully(fmtBuf)
                            val bb = ByteBuffer.wrap(fmtBuf).order(ByteOrder.LITTLE_ENDIAN)
                            fmtTag = bb.short.toInt() and 0xFFFF
                            channels = bb.short.toInt() and 0xFFFF
                            sampleRate = bb.int
                            bb.int // byteRate
                            blockAlign = bb.short.toInt() and 0xFFFF
                            bitsPerSample = bb.short.toInt() and 0xFFFF
                            if (fmtTag == 0xFFFE && fmtBuf.size >= 26) {
                                // WAVE_FORMAT_EXTENSIBLE: subFormat GUID starts at byte 24 (first 2 bytes = subFormat tag)
                                fmtTag = ByteBuffer.wrap(fmtBuf, 24, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                            }
                        }
                    }
                    "data" -> {
                        dataOffset = chunkStart
                        dataSize = minOf(chunkSize, len - chunkStart)
                    }
                }
                if (fmtTag != 0 && dataOffset >= 0) break
                val nextPos = chunkStart + chunkSize + (chunkSize and 1L)
                if (nextPos <= chunkStart || nextPos > len) break
                raf.seek(nextPos)
            }
            if (channels <= 0 || sampleRate <= 0 || blockAlign <= 0 || dataOffset < 0 || dataSize <= 0) {
                return@use null
            }
            if (fmtTag !in intArrayOf(1, 3, 6, 7)) return@use null
            WavInfo(fmtTag, channels, sampleRate, bitsPerSample, blockAlign, dataOffset, dataSize)
        }
    }.getOrNull()

    private fun decodeWavSegment(file: File, startMs: Int, durationMs: Int): DecodedMonoPcm? {
        val info = parseWavInfo(file) ?: return null
        val startFrame = ((startMs.toLong() * info.sampleRate) / 1000L).coerceIn(0L, info.totalFrames)
        val wantedFrames = ((durationMs.toLong() * info.sampleRate) / 1000L).coerceAtLeast(1L)
        val readFrames = minOf(wantedFrames, info.totalFrames - startFrame).coerceAtLeast(0L)
        if (readFrames <= 0L) return null

        val byteCount = (readFrames * info.blockAlign).toInt()
        val raw = ByteArray(byteCount)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(info.dataOffset + startFrame * info.blockAlign)
            raf.readFully(raw)
        }
        val mono = AudioPcmProcessor.decodePcmBytesToMonoFloats(
            bytes = raw,
            size = raw.size,
            channels = info.channels,
            bitsPerSample = info.bitsPerSample,
            bigEndian = false,
            isFloat = info.formatTag == 3,
            isUnsigned8Bit = info.bitsPerSample <= 8 && info.formatTag == 1,
            audioFormatTag = info.formatTag
        )
        val actualMs = ((mono.size.toLong() * 1000L) / info.sampleRate).toInt()
        return DecodedMonoPcm(info.sampleRate, mono, actualMs, "WAV")
    }

    // ---- AIFF / AIFC ----
    private data class AiffInfo(
        val isAifc: Boolean,
        val channels: Int,
        val totalFrames: Long,
        val bitsPerSample: Int,
        val sampleRate: Int,
        val isFloat: Boolean,
        val soundDataOffset: Long,
        val soundDataSize: Long
    )

    private fun parseAiffInfo(file: File): AiffInfo? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (len < 32) return@use null
            val formHdr = ByteArray(12)
            raf.readFully(formHdr)
            val isAifc = formHdr[11] == 'C'.code.toByte()

            var channels = 0
            var totalFrames = 0L
            var bitsPerSample = 0
            var sampleRate = 0
            var isFloat = false
            var ssndOffset = -1L
            var ssndSize = 0L

            val chunkHdr = ByteArray(8)
            while (raf.filePointer + 8 <= len) {
                raf.readFully(chunkHdr)
                val id = String(chunkHdr, 0, 4, Charsets.US_ASCII)
                val chunkSize = ByteBuffer.wrap(chunkHdr, 4, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val chunkStart = raf.filePointer
                when (id) {
                    "COMM" -> {
                        if (chunkSize >= 18) {
                            val commBuf = ByteArray(minOf(chunkSize, 32L).toInt())
                            raf.readFully(commBuf)
                            val bb = ByteBuffer.wrap(commBuf).order(ByteOrder.BIG_ENDIAN)
                            channels = bb.short.toInt() and 0xFFFF
                            totalFrames = bb.int.toLong() and 0xFFFFFFFFL
                            bitsPerSample = bb.short.toInt() and 0xFFFF
                            val ext80 = ByteArray(10)
                            bb.get(ext80)
                            sampleRate = decodeIeee80Extended(ext80).roundToInt()
                            if (isAifc && commBuf.size >= 22) {
                                val compType = String(commBuf, 18, 4, Charsets.US_ASCII)
                                isFloat = compType == "fl32" || compType == "FL32" || compType == "fl64"
                                if (compType !in listOf("NONE", "sowt", "fl32", "FL32", "fl64", "twos")) {
                                    return@use null
                                }
                            }
                        }
                    }
                    "SSND" -> {
                        if (chunkSize >= 8) {
                            val ssndHdr = ByteArray(8)
                            raf.readFully(ssndHdr)
                            val offset = ByteBuffer.wrap(ssndHdr, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                            ssndOffset = chunkStart + 8L + offset
                            ssndSize = (chunkSize - 8L - offset).coerceAtMost(len - ssndOffset)
                        }
                    }
                }
                if (channels > 0 && ssndOffset >= 0) break
                val nextPos = chunkStart + chunkSize + (chunkSize and 1L)
                if (nextPos <= chunkStart || nextPos > len) break
                raf.seek(nextPos)
            }
            if (channels <= 0 || sampleRate <= 0 || totalFrames <= 0 || ssndOffset < 0) return@use null
            AiffInfo(isAifc, channels, totalFrames, bitsPerSample, sampleRate, isFloat, ssndOffset, ssndSize)
        }
    }.getOrNull()

    private fun decodeAiffSegment(file: File, startMs: Int, durationMs: Int): DecodedMonoPcm? {
        val info = parseAiffInfo(file) ?: return null
        val bytesPerSample = ((info.bitsPerSample + 7) / 8).coerceAtLeast(1)
        val blockAlign = bytesPerSample * info.channels
        val startFrame = ((startMs.toLong() * info.sampleRate) / 1000L).coerceIn(0L, info.totalFrames)
        val wantedFrames = ((durationMs.toLong() * info.sampleRate) / 1000L).coerceAtLeast(1L)
        val readFrames = minOf(wantedFrames, info.totalFrames - startFrame).coerceAtLeast(0L)
        if (readFrames <= 0L) return null

        val raw = ByteArray((readFrames * blockAlign).toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(info.soundDataOffset + startFrame * blockAlign)
            raf.readFully(raw)
        }
        val mono = AudioPcmProcessor.decodePcmBytesToMonoFloats(
            bytes = raw,
            size = raw.size,
            channels = info.channels,
            bitsPerSample = info.bitsPerSample,
            bigEndian = true,
            isFloat = info.isFloat,
            isUnsigned8Bit = false
        )
        val actualMs = ((mono.size.toLong() * 1000L) / info.sampleRate).toInt()
        return DecodedMonoPcm(info.sampleRate, mono, actualMs, if (info.isAifc) "AIFC" else "AIFF")
    }

    private fun decodeIeee80Extended(bytes: ByteArray): Double {
        val sign = if ((bytes[0].toInt() and 0x80) != 0) -1.0 else 1.0
        val exp = ((bytes[0].toInt() and 0x7F) shl 8) or (bytes[1].toInt() and 0xFF)
        var mantissa = 0L
        for (i in 2 until 10) {
            mantissa = (mantissa shl 8) or (bytes[i].toLong() and 0xFFL)
        }
        if (exp == 0 && mantissa == 0L) return 0.0
        return sign * mantissa.toULong().toDouble() * 2.0.pow((exp - 16383 - 63).toDouble())
    }

    // ---- FLAC ----
    private fun inspectFlacDurationMs(file: File): Int? = runCatching {
        BufferedInputStream(FileInputStream(file)).use { bis ->
            val decoder = FLACDecoder(bis)
            val info = decoder.readStreamInfo() ?: return@use null
            val sr = info.sampleRate.coerceAtLeast(1)
            val total = info.totalSamples
            if (total <= 0L) return@use null
            ((total * 1000L) / sr).toInt().coerceAtLeast(StoryAudioRecorder.MIN_DURATION_MS)
        }
    }.getOrNull()

    private fun decodeFlacSegment(file: File, startMs: Int, durationMs: Int): DecodedMonoPcm? {
        val streamInfo = runCatching {
            BufferedInputStream(FileInputStream(file)).use { bis ->
                FLACDecoder(bis).readStreamInfo()
            }
        }.getOrNull() ?: return null

        val sampleRate = streamInfo.sampleRate.coerceAtLeast(8000)
        val channels = streamInfo.channels.coerceAtLeast(1)
        val bitsPerSample = streamInfo.bitsPerSample.coerceIn(4, 32)
        val startSample = (startMs.toLong() * sampleRate) / 1000L
        val targetSamples = ((durationMs.toLong() * sampleRate) / 1000L).toInt().coerceAtLeast(1)
        val endSample = startSample + targetSamples

        // Use deterministic sequential decoding instead of jflac's FLACDecoder.seek(),
        // which can loop infinitely when STREAMINFO maxFrameSize == 0 or without a SeekTable.
        return runCatching {
            BufferedInputStream(FileInputStream(file)).use { bis ->
                val decoder = FLACDecoder(bis)
                decoder.readMetadata()
                collectFlacFrames(decoder, sampleRate, channels, bitsPerSample, startSample, endSample)
            }
        }.getOrNull()?.takeIf { it.samples.isNotEmpty() }
    }

    private fun collectFlacFrames(
        decoder: FLACDecoder,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        startSample: Long,
        endSample: Long
    ): DecodedMonoPcm {
        val collected = ArrayList<Float>((endSample - startSample).toInt().coerceIn(1024, 260_000))
        val scale = (1L shl (bitsPerSample - 1)).toFloat()
        val invChannels = 1f / channels.toFloat()
        var sequentialCursor = 0L

        decoder.addFrameListener(object : FrameListener {
            override fun processMetadata(metadata: Metadata?) {}
            override fun processError(msg: String?) {}
            override fun processFrame(frame: Frame) {
                val blockSize = frame.header.blockSize
                val frameStart = if (frame.header.sampleNumber > 0L || sequentialCursor == 0L) {
                    frame.header.sampleNumber
                } else {
                    sequentialCursor
                }
                sequentialCursor = frameStart + blockSize
                val frameEnd = frameStart + blockSize

                if (frameEnd <= startSample) return
                if (frameStart >= endSample) throw TargetReachedException()

                val chData = decoder.channelData
                val activeChannels = minOf(channels, frame.header.channels)
                val fromIdx = (startSample - frameStart).toInt().coerceIn(0, blockSize)
                val toIdx = (endSample - frameStart).toInt().coerceIn(0, blockSize)

                for (i in fromIdx until toIdx) {
                    var sum = 0f
                    for (ch in 0 until activeChannels) {
                        sum += chData[ch].output[i].toFloat() / scale
                    }
                    collected.add((sum * invChannels).coerceIn(-1f, 1f))
                }
                if (frameEnd >= endSample) throw TargetReachedException()
            }
        })

        try {
            decoder.decodeFrames()
        } catch (_: TargetReachedException) {
            // Reached end of requested trim window
        }

        val mono = collected.toFloatArray()
        val actualMs = ((mono.size.toLong() * 1000L) / sampleRate).toInt()
        return DecodedMonoPcm(sampleRate, mono, actualMs, "FLAC")
    }

    // ---- Ogg (Opus / Vorbis) ----
    private fun inspectOggMetadata(file: File): ImportedAudioMetadata? {
        val streamType = runCatching {
            FileInputStream(file).use { fis ->
                OggFile(fis).use { ogg ->
                    val reader = ogg.packetReader
                    var found: OggStreamIdentifier.OggStreamType? = null
                    while (true) {
                        val p = reader.nextPacket ?: break
                        if (p.isBeginningOfStream && p.data != null && p.data.size > 10) {
                            val type = OggStreamIdentifier.identifyType(p)
                            if (type.kind == OggStreamIdentifier.OggStreamType.Kind.AUDIO) {
                                found = type
                                break
                            }
                        }
                    }
                    found
                }
            }
        }.getOrNull() ?: return null

        return when (streamType) {
            OggStreamIdentifier.OPUS_AUDIO, OggStreamIdentifier.OPUS_AUDIO_ALT -> {
                val durMs = inspectOpusDurationMs(file) ?: return null
                ImportedAudioMetadata(durMs, "Opus")
            }
            OggStreamIdentifier.OGG_VORBIS -> {
                val durMs = inspectVorbisDurationMs(file) ?: return null
                ImportedAudioMetadata(durMs, "Ogg Vorbis")
            }
            else -> null
        }
    }

    private fun decodeOggSegment(file: File, startMs: Int, durationMs: Int): DecodedMonoPcm? {
        val meta = inspectOggMetadata(file) ?: return null
        return when (meta.codecLabel) {
            "Opus" -> decodeOpusSegment(file, startMs, durationMs)
            "Ogg Vorbis" -> decodeVorbisSegment(file, startMs, durationMs)
            else -> null
        }
    }

    private fun inspectOpusDurationMs(file: File): Int? = runCatching {
        var preSkip = 0
        var sid = -1
        OpusFile(file).use { opus ->
            preSkip = opus.info.preSkip
            sid = opus.sid
        }
        val lastGranule = findLastOggGranule(file, sid) ?: return@runCatching null
        val totalSamples = (lastGranule - preSkip).coerceAtLeast(0L)
        ((totalSamples * 1000L) / 48000L).toInt().coerceAtLeast(StoryAudioRecorder.MIN_DURATION_MS)
    }.getOrNull()

    private fun findLastOggGranule(file: File, targetSid: Int): Long? = runCatching {
        FileInputStream(file).use { fis ->
            val sync = SyncState().apply { init() }
            val page = Page()
            val buf = ByteArray(8192)
            var lastGranule = -1L
            while (true) {
                val read = fis.read(buf)
                if (read <= 0) break
                val off = sync.buffer(read)
                System.arraycopy(buf, 0, sync.data, off, read)
                sync.wrote(read)
                while (sync.pageout(page) == 1) {
                    if (targetSid < 0 || page.serialno() == targetSid) {
                        val g = page.granulepos()
                        if (g > 0L) lastGranule = g
                    }
                }
            }
            sync.clear()
            if (lastGranule > 0L) lastGranule else null
        }
    }.getOrNull()

    private fun decodeOpusSegment(file: File, startMs: Int, durationMs: Int): DecodedMonoPcm? = runCatching {
        OpusFile(file).use { opus ->
            val info = opus.info
            val channels = info.numChannels.coerceIn(1, 8)
            val preSkip = info.preSkip.coerceAtLeast(0)
            val gainFactor = if (info.outputGain != 0) {
                10.0.pow(info.outputGain.toDouble() / (20.0 * 256.0)).toFloat()
            } else {
                1f
            }

            val singleDecoder = if (channels <= 2 && info.channelMappingFamily.toInt() == 0) {
                OpusDecoder(48000, channels)
            } else {
                null
            }
            val msDecoder = if (singleDecoder == null) {
                val mappingBytes = info.channelMapping
                val mappingShorts = ShortArray(mappingBytes.size) { idx ->
                    (mappingBytes[idx].toInt() and 0xFF).toShort()
                }
                OpusMSDecoder.create(
                    48000,
                    channels,
                    info.streamCount.toInt() and 0xFF,
                    info.twoChannelStreamCount.toInt() and 0xFF,
                    mappingShorts
                )
            } else {
                null
            }

            val startSample48k = preSkip.toLong() + (startMs.toLong() * 48L)
            val targetSamples = (durationMs.toLong() * 48L).toInt().coerceAtLeast(1)
            val endSample48k = startSample48k + targetSamples

            // If seeking > 1s in, skip ahead to a slightly earlier granule so Opus overlap converges cleanly
            var sampleCursor = 0L
            if (startSample48k > 48000L) {
                val seekTargetGranule = (startSample48k - 4800L).coerceAtLeast(0L)
                runCatching { opus.skipToGranule(seekTargetGranule) }
            }

            val pcmShort = ShortArray(5760 * channels)
            val out = ArrayList<Float>(targetSamples.coerceIn(1024, 260_000))
            val invChannels = 1f / channels.toFloat()

            while (true) {
                val pkt = opus.nextAudioPacket ?: break
                val bytes = pkt.data ?: continue
                val granule = pkt.granulePosition
                val decodedPerCh = singleDecoder?.decode(bytes, 0, bytes.size, pcmShort, 0, 5760, false)
                    ?: msDecoder!!.decodeMultistream(bytes, 0, bytes.size, pcmShort, 0, 5760, 0)
                if (decodedPerCh <= 0) continue

                val pktStart = if (granule > 0L && sampleCursor == 0L && startSample48k > 48000L) {
                    (granule - decodedPerCh).coerceAtLeast(0L)
                } else {
                    sampleCursor
                }
                val pktEnd = pktStart + decodedPerCh
                sampleCursor = pktEnd

                if (pktEnd <= startSample48k) continue
                if (pktStart >= endSample48k) break

                val fromIdx = (startSample48k - pktStart).toInt().coerceIn(0, decodedPerCh)
                val toIdx = (endSample48k - pktStart).toInt().coerceIn(0, decodedPerCh)
                for (i in fromIdx until toIdx) {
                    var sum = 0f
                    val base = i * channels
                    for (c in 0 until channels) {
                        sum += pcmShort[base + c].toFloat() / 32768f
                    }
                    out.add(((sum * invChannels) * gainFactor).coerceIn(-1f, 1f))
                }
                if (pktEnd >= endSample48k) break
            }

            if (out.isEmpty()) return@use null
            val mono = out.toFloatArray()
            val actualMs = ((mono.size.toLong() * 1000L) / 48000L).toInt()
            DecodedMonoPcm(48000, mono, actualMs, "Opus")
        }
    }.getOrNull()

    private fun inspectVorbisDurationMs(file: File): Int? = runCatching {
        val vf = com.jcraft.jorbis.VorbisFile(file.absolutePath)
        val totalSec = vf.time_total(-1)
        if (totalSec > 0f) {
            (totalSec * 1000f).roundToInt().coerceAtLeast(StoryAudioRecorder.MIN_DURATION_MS)
        } else {
            val sr = vf.getInfo(0)?.rate?.coerceAtLeast(1) ?: 44100
            val lastGranule = findLastOggGranule(file, -1) ?: return@runCatching null
            ((lastGranule * 1000L) / sr).toInt().coerceAtLeast(StoryAudioRecorder.MIN_DURATION_MS)
        }
    }.getOrNull()

    private fun decodeVorbisSegment(file: File, startMs: Int, durationMs: Int): DecodedMonoPcm? = runCatching {
        FileInputStream(file).use { fis ->
            val oy = SyncState().apply { init() }
            val os = StreamState()
            val og = Page()
            val op = Packet()
            val vi = Info().apply { init() }
            val vc = Comment().apply { init() }
            val vd = DspState()
            val vb = Block(vd)

            val buf = ByteArray(8192)
            var streamInitialized = false
            var headersParsed = 0

            fun fillSync(): Int {
                val n = fis.read(buf)
                if (n <= 0) return n
                val off = oy.buffer(n)
                System.arraycopy(buf, 0, oy.data, off, n)
                oy.wrote(n)
                return n
            }

            // Read 3 Vorbis header packets
            while (headersParsed < 3) {
                val res = oy.pageout(og)
                if (res == 0) {
                    if (fillSync() <= 0) break
                    continue
                }
                if (res == 1) {
                    if (!streamInitialized) {
                        os.init(og.serialno())
                        os.reset()
                        streamInitialized = true
                    }
                    os.pagein(og)
                    while (headersParsed < 3) {
                        val pRes = os.packetout(op)
                        if (pRes == 0) break
                        if (pRes == 1) {
                            if (vi.synthesis_headerin(vc, op) < 0) {
                                oy.clear()
                                return@use null
                            }
                            headersParsed++
                        }
                    }
                }
            }
            if (headersParsed < 3 || vi.rate <= 0 || vi.channels <= 0) {
                oy.clear()
                return@use null
            }

            val sampleRate = vi.rate
            val channels = vi.channels
            val invChannels = 1f / channels.toFloat()
            val startSample = (startMs.toLong() * sampleRate) / 1000L
            val targetSamples = ((durationMs.toLong() * sampleRate) / 1000L).toInt().coerceAtLeast(1)
            val endSample = startSample + targetSamples

            vd.synthesis_init(vi)
            vb.init(vd)

            val out = ArrayList<Float>(targetSamples.coerceIn(1024, 260_000))
            val pcmHolder = Array(1) { emptyArray<FloatArray>() }
            val indexArray = IntArray(channels)
            var sampleCursor = 0L
            var eos = false

            while (!eos && sampleCursor < endSample) {
                val res = oy.pageout(og)
                if (res == 0) {
                    if (fillSync() <= 0) break
                    continue
                }
                if (res < 0) continue

                os.pagein(og)
                while (!eos && sampleCursor < endSample) {
                    val pRes = os.packetout(op)
                    if (pRes == 0) break
                    if (pRes < 0) continue

                    if (vb.synthesis(op) == 0) {
                        vd.synthesis_blockin(vb)
                    }
                    var available: Int
                    while (true) {
                        available = vd.synthesis_pcmout(pcmHolder, indexArray)
                        if (available <= 0) break

                        val pcm = pcmHolder[0]
                        val blockStart = sampleCursor
                        val blockEnd = sampleCursor + available
                        if (blockEnd > startSample && blockStart < endSample) {
                            val fromIdx = (startSample - blockStart).toInt().coerceIn(0, available)
                            val toIdx = (endSample - blockStart).toInt().coerceIn(0, available)
                            for (i in fromIdx until toIdx) {
                                var sum = 0f
                                for (c in 0 until channels) {
                                    sum += pcm[c][indexArray[c] + i]
                                }
                                out.add((sum * invChannels).coerceIn(-1f, 1f))
                            }
                        }
                        sampleCursor = blockEnd
                        vd.synthesis_read(available)
                        if (sampleCursor >= endSample) break
                    }
                }
                if (og.eos() != 0) eos = true
            }

            os.clear()
            vb.clear()
            vd.clear()
            vi.clear()
            oy.clear()

            if (out.isEmpty()) return@use null
            val mono = out.toFloatArray()
            val actualMs = ((mono.size.toLong() * 1000L) / sampleRate).toInt()
            DecodedMonoPcm(sampleRate, mono, actualMs, "Ogg Vorbis")
        }
    }.getOrNull()

    // ---- ALAC (Apple Lossless in M4A/MP4) ----
    private fun inspectAlacDurationMs(file: File): Int? = runCatching {
        val ac = AlacContext()
        AlacUtils.AlacOpenFileInput(ac, file)
        try {
            if (ac.error) return@runCatching null
            val sr = AlacUtils.AlacGetSampleRate(ac).coerceAtLeast(1)
            val total = AlacUtils.AlacGetNumSamples(ac).toLong()
            if (total <= 0L) return@runCatching null
            ((total * 1000L) / sr).toInt().coerceAtLeast(StoryAudioRecorder.MIN_DURATION_MS)
        } finally {
            runCatching { ac.input_stream?.close() }
        }
    }.getOrNull()

    private fun decodeAlacSegment(file: File, startMs: Int, durationMs: Int): DecodedMonoPcm? = runCatching {
        val ac = AlacContext()
        AlacUtils.AlacOpenFileInput(ac, file)
        try {
            if (ac.error) return@runCatching null
            val sampleRate = AlacUtils.AlacGetSampleRate(ac).coerceAtLeast(8000)
            val channels = AlacUtils.AlacGetNumChannels(ac).coerceAtLeast(1)
            val bitsPerSample = AlacUtils.AlacGetBitsPerSample(ac).coerceIn(8, 32)
            val bytesPerSample = AlacUtils.AlacGetBytesPerSample(ac).coerceAtLeast(1)
            val startSample = (startMs.toLong() * sampleRate) / 1000L
            val targetSamples = ((durationMs.toLong() * sampleRate) / 1000L).toInt().coerceAtLeast(1)

            if (startSample > 0L) {
                AlacUtils.AlacSetPosition(ac, startSample)
                if (ac.error) return@runCatching null
            }

            val destBuffer = IntArray(16384 * channels * bytesPerSample)
            val out = ArrayList<Float>(targetSamples.coerceIn(1024, 260_000))
            while (out.size < targetSamples) {
                val bytesDecoded = AlacUtils.AlacUnpackSamples(ac, destBuffer)
                if (bytesDecoded <= 0) break
                val rawBytes = ByteArray(bytesDecoded) { idx -> destBuffer[idx].toByte() }
                val monoChunk = AudioPcmProcessor.decodePcmBytesToMonoFloats(
                    bytes = rawBytes,
                    size = bytesDecoded,
                    channels = channels,
                    bitsPerSample = bitsPerSample,
                    bigEndian = false,
                    isFloat = false,
                    isUnsigned8Bit = false
                )
                val needed = minOf(monoChunk.size, targetSamples - out.size)
                for (i in 0 until needed) out.add(monoChunk[i])
            }
            if (out.isEmpty()) return@runCatching null
            val mono = out.toFloatArray()
            val actualMs = ((mono.size.toLong() * 1000L) / sampleRate).toInt()
            DecodedMonoPcm(sampleRate, mono, actualMs, "ALAC")
        } finally {
            runCatching { ac.input_stream?.close() }
        }
    }.getOrNull()
}
