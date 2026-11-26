package com.klabcc.piersocial

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.klabcc.piersocial.data.storage.StoryAudioRecorder
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import kotlinx.coroutines.runBlocking
import org.gagravarr.ogg.OggFile
import org.gagravarr.opus.OpusAudioData
import org.gagravarr.opus.OpusInfo
import org.gagravarr.opus.OpusTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class StoryAudioRecorderInstrumentedTest {

    private val createdFiles = mutableListOf<File>()

    @After
    fun cleanup() {
        createdFiles.forEach { it.delete() }
    }

    @Test
    fun importWavAndSelectMiddlePortion_compressesToMonoMp3() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wavFile = File(context.cacheDir, "test_track_12s_stereo.wav").also { createdFiles.add(it) }
        writeTestStereoWavFile(wavFile, durationSeconds = 12, sampleRate = 44100)

        val recorder = StoryAudioRecorder(context)
        try {
            recorder.importClipFromUri(Uri.fromFile(wavFile))
            val initial = recorder.state.value
            assertNull(initial.errorMessage)
            assertTrue(initial.hasImportedTrack)
            assertEquals("WAV", initial.importedCodecLabel)
            assertTrue("Expected ~12s duration, got ${initial.importedTotalDurationMs}", initial.importedTotalDurationMs in 11500..12500)
            assertEquals(0, initial.trimStartMs)
            assertEquals(5000, initial.trimWindowMs)
            val initialFile = initial.recordedFile
            assertNotNull(initialFile)
            assertMonoMp3File(initialFile!!)

            recorder.updateTrimStartPreview(6000)
            recorder.commitTrimSelection()
            val trimmed = recorder.state.value
            assertNull(trimmed.errorMessage)
            assertEquals(6000, trimmed.trimStartMs)
            assertEquals(11000, trimmed.trimEndMs)
            val trimmedFile = trimmed.recordedFile
            assertNotNull(trimmedFile)
            assertMonoMp3File(trimmedFile!!)

            val actualDurationMs = readDurationMs(trimmedFile)
            assertTrue("Expected ~5s trimmed clip, got ${actualDurationMs}ms", actualDurationMs in 4200..5300)
        } finally {
            recorder.clearClip()
            recorder.release()
        }
    }

    @Test
    fun importM4aAndSelectLaterPortion_compressesToMonoMp3() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val m4aFile = File(context.cacheDir, "test_track_12s_stereo.m4a").also { createdFiles.add(it) }
        writeTestStereoAacM4aFile(m4aFile, durationSeconds = 12, sampleRate = 44100)

        val recorder = StoryAudioRecorder(context)
        try {
            recorder.importClipFromUri(Uri.fromFile(m4aFile))
            val initial = recorder.state.value
            assertNull(initial.errorMessage)
            assertTrue(initial.importedTotalDurationMs in 11500..12500)

            recorder.commitTrimSelection(5000)
            val trimmed = recorder.state.value
            assertNull(trimmed.errorMessage)
            assertEquals(5000, trimmed.trimStartMs)
            assertEquals(10000, trimmed.trimEndMs)
            val trimmedFile = trimmed.recordedFile
            assertNotNull(trimmedFile)
            assertMonoMp3File(trimmedFile!!)

            val actualDurationMs = readDurationMs(trimmedFile)
            assertTrue("Expected ~5s clip, got ${actualDurationMs}ms", actualDurationMs in 4200..5300)
        } finally {
            recorder.clearClip()
            recorder.release()
        }
    }

    @Test
    fun importFlacAndSelectPortion_compressesToMonoMp3() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val flacFile = File(context.cacheDir, "test_track_10s.flac").also { createdFiles.add(it) }
        writeTestStereoFlacFile(flacFile, durationSeconds = 10, sampleRate = 44100)

        val recorder = StoryAudioRecorder(context)
        try {
            recorder.importClipFromUri(Uri.fromFile(flacFile))
            val initial = recorder.state.value
            assertNull(initial.errorMessage)
            assertEquals("FLAC", initial.importedCodecLabel)
            assertTrue("Expected ~10s duration, got ${initial.importedTotalDurationMs}", initial.importedTotalDurationMs in 9500..10500)

            recorder.commitTrimSelection(3000)
            val trimmed = recorder.state.value
            assertNull(trimmed.errorMessage)
            assertEquals(3000, trimmed.trimStartMs)
            assertEquals(8000, trimmed.trimEndMs)
            val trimmedFile = trimmed.recordedFile
            assertNotNull(trimmedFile)
            assertMonoMp3File(trimmedFile!!)

            val actualDurationMs = readDurationMs(trimmedFile)
            assertTrue("Expected ~5s FLAC->MP3 clip, got ${actualDurationMs}ms", actualDurationMs in 4200..5300)
        } finally {
            recorder.clearClip()
            recorder.release()
        }
    }

    @Test
    fun importOpusAndSelectPortion_compressesToMonoMp3() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val opusFile = File(context.cacheDir, "test_track_10s.opus").also { createdFiles.add(it) }
        writeTestStereoOpusFile(opusFile, durationSeconds = 10)

        val recorder = StoryAudioRecorder(context)
        try {
            recorder.importClipFromUri(Uri.fromFile(opusFile))
            val initial = recorder.state.value
            assertNull(initial.errorMessage)
            assertEquals("Opus", initial.importedCodecLabel)
            assertTrue("Expected ~10s duration, got ${initial.importedTotalDurationMs}", initial.importedTotalDurationMs in 9500..10500)

            recorder.commitTrimSelection(2000)
            val trimmed = recorder.state.value
            assertNull(trimmed.errorMessage)
            assertEquals(2000, trimmed.trimStartMs)
            assertEquals(7000, trimmed.trimEndMs)
            val trimmedFile = trimmed.recordedFile
            assertNotNull(trimmedFile)
            assertMonoMp3File(trimmedFile!!)

            val actualDurationMs = readDurationMs(trimmedFile)
            assertTrue("Expected ~5s Opus->MP3 clip, got ${actualDurationMs}ms", actualDurationMs in 4200..5300)
        } finally {
            recorder.clearClip()
            recorder.release()
        }
    }

    private fun readDurationMs(file: File): Int {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
        } finally {
            mmr.release()
        }
    }

    private fun assertMonoMp3File(file: File) {
        assertTrue(file.exists())
        assertTrue(file.name.endsWith(".mp3"))
        assertTrue(file.length() in 1024L..StoryAudioRecorder.MAX_BLE_AUDIO_BYTES)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var foundMonoMp3 = false
            for (idx in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(idx)
                val mime = fmt.getString(MediaFormat.KEY_MIME)
                val ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else -1
                if (mime == MediaFormat.MIMETYPE_AUDIO_MPEG && ch == 1) {
                    foundMonoMp3 = true
                    break
                }
            }
            assertTrue("Expected 1-channel audio/mpeg MP3 track for ${file.name}", foundMonoMp3)
        } finally {
            extractor.release()
        }
    }

    private fun writeTestStereoWavFile(file: File, durationSeconds: Int, sampleRate: Int) {
        val channels = 2
        val totalSamples = durationSeconds * sampleRate
        val blockAlign = channels * 2
        val dataBytes = totalSamples * blockAlign
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + dataBytes)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * blockAlign)
            putShort(blockAlign.toShort())
            putShort(16)
            put("data".toByteArray())
            putInt(dataBytes)
        }.array()

        FileOutputStream(file).use { out ->
            out.write(header)
            val chunk = ByteBuffer.allocate(sampleRate * blockAlign).order(ByteOrder.LITTLE_ENDIAN)
            for (sec in 0 until durationSeconds) {
                chunk.clear()
                val freq = if (sec < 5) 440.0 else 880.0
                for (i in 0 until sampleRate) {
                    val sample = (sin(2.0 * PI * freq * i / sampleRate) * 12000.0).toInt().toShort()
                    chunk.putShort(sample)
                    chunk.putShort(sample)
                }
                out.write(chunk.array())
            }
        }
    }

    private fun writeTestStereoAacM4aFile(file: File, durationSeconds: Int, sampleRate: Int) {
        val channels = 2
        val encFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 96000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxTrack = -1
        var muxStarted = false
        val info = MediaCodec.BufferInfo()
        val totalFrames = durationSeconds * sampleRate
        var fedFrames = 0
        var eosSent = false
        var done = false

        try {
            while (!done) {
                if (!eosSent) {
                    val inIdx = encoder.dequeueInputBuffer(5000L)
                    if (inIdx >= 0) {
                        val buf = encoder.getInputBuffer(inIdx)!!
                        buf.clear()
                        if (fedFrames >= totalFrames) {
                            encoder.queueInputBuffer(
                                inIdx, 0, 0,
                                (fedFrames.toLong() * 1_000_000L) / sampleRate,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            eosSent = true
                        } else {
                            val maxFrames = minOf(buf.remaining() / (channels * 2), totalFrames - fedFrames)
                            val ptsUs = (fedFrames.toLong() * 1_000_000L) / sampleRate
                            buf.order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until maxFrames) {
                                val frameIdx = fedFrames + i
                                val freq = if (frameIdx < 5 * sampleRate) 330.0 else 660.0
                                val v = (sin(2.0 * PI * freq * frameIdx / sampleRate) * 12000.0).toInt().toShort()
                                buf.putShort(v)
                                buf.putShort(v)
                            }
                            fedFrames += maxFrames
                            encoder.queueInputBuffer(inIdx, 0, maxFrames * channels * 2, ptsUs, 0)
                        }
                    }
                }

                while (true) {
                    val outIdx = encoder.dequeueOutputBuffer(info, 5000L)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxStarted = true
                        }
                        outIdx >= 0 -> {
                            val outBuf = encoder.getOutputBuffer(outIdx)
                            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            if (!isConfig && info.size > 0 && outBuf != null && muxStarted) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                muxer.writeSampleData(muxTrack, outBuf, info)
                            }
                            encoder.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                done = true
                                break
                            }
                        }
                    }
                }
            }
        } finally {
            if (muxStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
    }

    private fun writeTestStereoFlacFile(file: File, durationSeconds: Int, sampleRate: Int) {
        val blockSize = 4096
        val channels = 2
        val bps = 16
        val frameCount = (durationSeconds * sampleRate + blockSize - 1) / blockSize
        val totalSamples = frameCount.toLong() * blockSize

        FileOutputStream(file).use { out ->
            out.write("fLaC".toByteArray())
            out.write(byteArrayOf(0x80.toByte(), 0, 0, 34))
            val info = ByteArray(34)
            info[0] = ((blockSize ushr 8) and 0xFF).toByte()
            info[1] = (blockSize and 0xFF).toByte()
            info[2] = ((blockSize ushr 8) and 0xFF).toByte()
            info[3] = (blockSize and 0xFF).toByte()
            val packed = ((sampleRate.toLong() and 0xFFFFFL) shl 44) or
                (((channels - 1).toLong() and 0x7L) shl 41) or
                (((bps - 1).toLong() and 0x1FL) shl 36) or
                (totalSamples and 0xFFFFFFFFFL)
            for (b in 0 until 8) {
                info[10 + b] = ((packed ushr ((7 - b) * 8)) and 0xFFL).toByte()
            }
            out.write(info)

            val frameOut = ByteArrayOutputStream(blockSize * channels * 2 + 16)
            for (fIdx in 0 until frameCount) {
                frameOut.reset()
                frameOut.write(0xFF)
                frameOut.write(0xF8)
                frameOut.write(0xC9)
                frameOut.write(0x18)
                if (fIdx < 0x80) {
                    frameOut.write(fIdx)
                } else {
                    frameOut.write((0xC0 or (fIdx ushr 6)) and 0xFF)
                    frameOut.write((0x80 or (fIdx and 0x3F)) and 0xFF)
                }
                frameOut.write(crc8(frameOut.toByteArray()))

                val freq = if (fIdx * blockSize < 5 * sampleRate) 440.0 else 880.0
                for (ch in 0 until channels) {
                    frameOut.write(0x02)
                    for (s in 0 until blockSize) {
                        val v = (sin(2.0 * PI * freq * (fIdx * blockSize + s) / sampleRate) * 10000.0)
                            .toInt().toShort()
                        frameOut.write((v.toInt() ushr 8) and 0xFF)
                        frameOut.write(v.toInt() and 0xFF)
                    }
                }
                val frameBody = frameOut.toByteArray()
                val c16 = crc16(frameBody)
                out.write(frameBody)
                out.write((c16 ushr 8) and 0xFF)
                out.write(c16 and 0xFF)
            }
        }
    }

    private fun crc8(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x07) else (crc shl 1)
                crc = crc and 0xFF
            }
        }
        return crc
    }

    private fun crc16(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) ((crc shl 1) xor 0x8005) else (crc shl 1)
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    private fun writeTestStereoOpusFile(file: File, durationSeconds: Int) {
        val sampleRate = 48000
        val channels = 2
        val frameSize = 960
        val totalFrames = (durationSeconds * 1000) / 20
        val encoder = OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_AUDIO).apply {
            bitrate = 64000
        }
        val info = OpusInfo().apply {
            setSampleRate(sampleRate.toLong())
            numChannels = channels
            preSkip = 312
        }
        val outBuf = ByteArray(1275)
        val pcm = ShortArray(frameSize * channels)
        OggFile(FileOutputStream(file)).use { ogg ->
            val writer = ogg.packetWriter
            writer.bufferPacket(info.write(), true)
            writer.bufferPacket(OpusTags().write(), true)
            var granule = 0L
            for (f in 0 until totalFrames) {
                val freq = if (f * 20 < 5000) 440.0 else 880.0
                for (i in 0 until frameSize) {
                    val t = (f * frameSize + i).toDouble() / sampleRate
                    val s = (sin(2.0 * PI * freq * t) * 12000.0).toInt().toShort()
                    pcm[i * 2] = s
                    pcm[i * 2 + 1] = s
                }
                val len = encoder.encode(pcm, 0, frameSize, outBuf, 0, outBuf.size)
                granule += frameSize
                writer.bufferPacket(OpusAudioData(outBuf.copyOf(len)).write(), granule)
                if ((f + 1) % 50 == 0 && f + 1 < totalFrames) {
                    writer.flush()
                }
            }
            writer.close()
        }
    }
}
