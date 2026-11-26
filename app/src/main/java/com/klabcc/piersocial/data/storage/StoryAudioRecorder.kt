package com.klabcc.piersocial.data.storage

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

data class AudioTrimWindow(
    val startMs: Int,
    val durationMs: Int,
    val endMs: Int
)

data class AudioRecorderState(
    val isRecording: Boolean = false,
    val isPlayingPreview: Boolean = false,
    val isProcessingImport: Boolean = false,
    val elapsedMs: Int = 0,
    val recordedFile: File? = null,
    val recordedDurationMs: Int = 0,
    val amplitudes: List<Float> = emptyList(),
    val errorMessage: String? = null,
    val importedSourceFile: File? = null,
    val importedTrackName: String? = null,
    val importedCodecLabel: String? = null,
    val importedTotalDurationMs: Int = 0,
    val trimStartMs: Int = 0,
    val trimWindowMs: Int = 0,
    val previewProgress: Float = 0f
) {
    val hasImportedTrack: Boolean
        get() = importedSourceFile != null && importedTotalDurationMs > 0

    val maxTrimStartMs: Int
        get() = (importedTotalDurationMs - trimWindowMs).coerceAtLeast(0)

    val trimEndMs: Int
        get() = (trimStartMs + trimWindowMs).coerceAtMost(importedTotalDurationMs)
}

class StoryAudioRecorder(private val context: Context) {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var recordingFinishedLatch: CountDownLatch? = null
    private val recordingActive = AtomicBoolean(false)

    private var player: MediaPlayer? = null
    private var previewJob: Job? = null
    private var currentTempFile: File? = null
    private var currentImportedSourceFile: File? = null
    private var recordingStartMs: Long = 0L
    private val extractMutex = Mutex()

    private val _state = MutableStateFlow(AudioRecorderState())
    val state: StateFlow<AudioRecorderState> = _state.asStateFlow()

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startRecording(scope: CoroutineScope) {
        stopPlayback()
        if (_state.value.isRecording) return

        currentImportedSourceFile?.delete()
        currentImportedSourceFile = null
        currentTempFile?.delete()
        currentTempFile = null

        val outFile = File(context.cacheDir, "story_clip_${UUID.randomUUID().toString().take(8)}.mp3")
        currentTempFile = outFile

        var selectedRate = 0
        var minBufSize = 0
        var createdRecord: AudioRecord? = null

        for (candidateRate in intArrayOf(22050, 44100, 16000)) {
            val minBytes = AudioRecord.getMinBufferSize(
                candidateRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBytes <= 0) continue
            val bufBytes = maxOf(minBytes * 2, candidateRate / 5 * 2)
            val ar = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    candidateRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufBytes
                )
            }.getOrNull()
            if (ar != null && ar.state == AudioRecord.STATE_INITIALIZED) {
                selectedRate = candidateRate
                minBufSize = bufBytes
                createdRecord = ar
                break
            } else {
                runCatching { ar?.release() }
            }
        }

        if (createdRecord == null || selectedRate <= 0) {
            outFile.delete()
            _state.value = _state.value.copy(
                isRecording = false,
                errorMessage = "Could not initialize microphone for mono MP3 recording."
            )
            return
        }

        try {
            val encoder = StreamingMonoMp3Encoder(
                rawSampleRate = selectedRate,
                bitrateKbps = 48,
                outputStream = FileOutputStream(outFile)
            )
            createdRecord.startRecording()
            audioRecord = createdRecord
            recordingStartMs = System.currentTimeMillis()
            recordingActive.set(true)
            val latch = CountDownLatch(1)
            recordingFinishedLatch = latch

            _state.value = AudioRecorderState(
                isRecording = true,
                elapsedMs = 0,
                recordedFile = null,
                recordedDurationMs = 0,
                amplitudes = emptyList(),
                errorMessage = null
            )

            recordingJob = scope.launch(Dispatchers.IO) {
                val readShorts = ShortArray((minBufSize / 2).coerceIn(512, 4096))
                var totalFrames = 0L
                val bars = mutableListOf<Float>()
                try {
                    while (recordingActive.get()) {
                        val elapsed = (System.currentTimeMillis() - recordingStartMs).toInt()
                            .coerceIn(0, MAX_DURATION_MS)
                        val maxAllowedFrames = ((MAX_DURATION_MS.toLong() * selectedRate) / 1000L)
                        val remainingFrames = (maxAllowedFrames - totalFrames).toInt()
                        if (remainingFrames <= 0 || elapsed >= MAX_DURATION_MS) {
                            break
                        }
                        val toRead = minOf(readShorts.size, remainingFrames)
                        val read = createdRecord.read(readShorts, 0, toRead)
                        if (read > 0) {
                            encoder.encodeMonoShorts(readShorts, read)
                            totalFrames += read
                            var sumSq = 0.0
                            for (i in 0 until read) {
                                val v = readShorts[i].toDouble() / 32768.0
                                sumSq += v * v
                            }
                            val rms = sqrt(sumSq / read).toFloat()
                            val bar = (rms * 4.5f).coerceIn(0.08f, 1f)
                            bars.add(bar)
                            if (bars.size > 36) bars.removeAt(0)
                            val updatedElapsed = ((totalFrames * 1000L) / selectedRate).toInt()
                                .coerceIn(0, MAX_DURATION_MS)
                            withContext(Dispatchers.Main) {
                                if (recordingActive.get()) {
                                    _state.value = _state.value.copy(
                                        elapsedMs = updatedElapsed,
                                        amplitudes = bars.toList()
                                    )
                                }
                            }
                        } else if (read < 0) {
                            break
                        }
                    }
                } finally {
                    recordingActive.set(false)
                    runCatching { createdRecord.stop() }
                    runCatching { createdRecord.release() }
                    runCatching { encoder.close() }
                    val finalDurationMs = ((totalFrames * 1000L) / selectedRate).toInt()
                        .coerceIn(0, MAX_DURATION_MS)
                    latch.countDown()
                    withContext(Dispatchers.Main) {
                        finalizeRecordingUi(outFile, finalDurationMs)
                    }
                }
            }
        } catch (e: Exception) {
            recordingActive.set(false)
            runCatching { createdRecord.release() }
            audioRecord = null
            outFile.delete()
            _state.value = _state.value.copy(
                isRecording = false,
                errorMessage = "Could not start microphone recording: ${e.message ?: "Hardware error"}"
            )
        }
    }

    @Synchronized
    fun stopRecording() {
        val ar = audioRecord ?: return
        audioRecord = null
        recordingActive.set(false)
        runCatching { ar.stop() }
        recordingFinishedLatch?.await(1500, TimeUnit.MILLISECONDS)
        recordingFinishedLatch = null
        recordingJob = null
    }

    @Synchronized
    private fun finalizeRecordingUi(file: File, finalDurationMs: Int) {
        if (audioRecord != null) {
            audioRecord = null
        }
        if (finalDurationMs >= MIN_DURATION_MS && file.exists() && file.length() > 256L) {
            _state.value = _state.value.copy(
                isRecording = false,
                elapsedMs = finalDurationMs,
                recordedFile = file,
                recordedDurationMs = finalDurationMs,
                errorMessage = null
            )
        } else {
            file.delete()
            _state.value = _state.value.copy(
                isRecording = false,
                elapsedMs = 0,
                recordedFile = null,
                recordedDurationMs = 0,
                errorMessage = "Clip too short. Hold or record at least 0.5 seconds."
            )
        }
    }

    fun togglePreviewPlayback(scope: CoroutineScope? = null) {
        if (_state.value.isPlayingPreview) {
            stopPlayback()
            return
        }
        val file = _state.value.recordedFile ?: return
        if (!file.exists() || _state.value.isProcessingImport) return
        try {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stopPlayback()
                }
                prepare()
                start()
            }
            player = mp
            _state.value = _state.value.copy(
                isPlayingPreview = true,
                previewProgress = 0f,
                errorMessage = null
            )
            if (scope != null) {
                previewJob?.cancel()
                previewJob = scope.launch(Dispatchers.Main) {
                    while (_state.value.isPlayingPreview && player != null) {
                        val active = player ?: break
                        val prog = runCatching {
                            val dur = active.duration.coerceAtLeast(1)
                            (active.currentPosition.toFloat() / dur).coerceIn(0f, 1f)
                        }.getOrDefault(0f)
                        _state.value = _state.value.copy(previewProgress = prog)
                        delay(60L)
                    }
                }
            }
        } catch (e: Exception) {
            stopPlayback()
            _state.value = _state.value.copy(errorMessage = "Preview failed: ${e.message}")
        }
    }

    fun stopPlayback() {
        previewJob?.cancel()
        previewJob = null
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        if (_state.value.isPlayingPreview || _state.value.previewProgress != 0f) {
            _state.value = _state.value.copy(isPlayingPreview = false, previewProgress = 0f)
        }
    }

    fun clearClip() {
        stopPlayback()
        if (_state.value.isRecording) stopRecording()
        currentTempFile?.delete()
        _state.value.recordedFile?.delete()
        currentImportedSourceFile?.delete()
        currentTempFile = null
        currentImportedSourceFile = null
        _state.value = AudioRecorderState()
    }

    suspend fun importClipFromUri(uri: Uri) {
        stopPlayback()
        if (_state.value.isRecording) stopRecording()

        val previousImport = currentImportedSourceFile
        val previousClip = currentTempFile
        currentImportedSourceFile = null
        currentTempFile = null

        _state.value = AudioRecorderState(
            isProcessingImport = true
        )

        val inspected = withContext(Dispatchers.IO) {
            previousImport?.delete()
            previousClip?.delete()
            prepareImportedTrack(context, uri)
        }
        if (inspected == null) {
            _state.value = AudioRecorderState(
                errorMessage = "Unsupported or unreadable audio file. Supports FLAC, MP3, M4A/AAC, ALAC, Opus, Ogg Vorbis, WAV, AIFF, AMR."
            )
            return
        }

        val initialWindow = computeTrimWindow(inspected.totalDurationMs, 0)
        val extracted = withContext(Dispatchers.IO) {
            extractMutex.withLock {
                extractSegmentToMp3(
                    context = context,
                    sourceFile = inspected.sourceFile,
                    startMs = initialWindow.startMs,
                    durationMs = initialWindow.durationMs
                )
            }
        }

        if (extracted != null) {
            currentImportedSourceFile = inspected.sourceFile
            currentTempFile = extracted.file
            _state.value = AudioRecorderState(
                isProcessingImport = false,
                recordedFile = extracted.file,
                recordedDurationMs = extracted.durationMs,
                elapsedMs = extracted.durationMs,
                amplitudes = extracted.amplitudes,
                importedSourceFile = inspected.sourceFile,
                importedTrackName = inspected.trackName,
                importedCodecLabel = inspected.codecLabel,
                importedTotalDurationMs = inspected.totalDurationMs,
                trimStartMs = initialWindow.startMs,
                trimWindowMs = initialWindow.durationMs
            )
        } else {
            inspected.sourceFile.delete()
            _state.value = AudioRecorderState(
                errorMessage = "Could not decode and compress 5s mono MP3 segment from this track."
            )
        }
    }

    fun updateTrimStartPreview(requestedStartMs: Int) {
        val current = _state.value
        if (!current.hasImportedTrack) return
        stopPlayback()
        val window = computeTrimWindow(current.importedTotalDurationMs, requestedStartMs)
        _state.value = current.copy(
            trimStartMs = window.startMs,
            trimWindowMs = window.durationMs,
            errorMessage = null
        )
    }

    suspend fun commitTrimSelection(requestedStartMs: Int? = null) {
        val current = _state.value
        val sourceFile = current.importedSourceFile ?: return
        if (!sourceFile.exists()) return
        stopPlayback()

        val targetStart = requestedStartMs ?: current.trimStartMs
        val window = computeTrimWindow(current.importedTotalDurationMs, targetStart)
        _state.value = current.copy(
            isProcessingImport = true,
            trimStartMs = window.startMs,
            trimWindowMs = window.durationMs,
            errorMessage = null
        )

        val extracted = withContext(Dispatchers.IO) {
            extractMutex.withLock {
                extractSegmentToMp3(
                    context = context,
                    sourceFile = sourceFile,
                    startMs = window.startMs,
                    durationMs = window.durationMs
                )
            }
        }

        val latest = _state.value
        if (latest.importedSourceFile != sourceFile) {
            extracted?.file?.delete()
            return
        }

        if (extracted != null) {
            val oldClip = currentTempFile
            if (oldClip != null && oldClip != extracted.file) {
                oldClip.delete()
            }
            currentTempFile = extracted.file
            _state.value = latest.copy(
                isProcessingImport = false,
                recordedFile = extracted.file,
                recordedDurationMs = extracted.durationMs,
                elapsedMs = extracted.durationMs,
                amplitudes = extracted.amplitudes,
                errorMessage = null
            )
        } else {
            _state.value = latest.copy(
                isProcessingImport = false,
                errorMessage = "Could not trim selected audio portion."
            )
        }
    }

    fun release() {
        stopPlayback()
        recordingActive.set(false)
        audioRecord?.let { ar ->
            runCatching { ar.stop() }
            runCatching { ar.release() }
        }
        audioRecord = null
        recordingJob?.cancel()
        recordingJob = null
        previewJob?.cancel()
        currentImportedSourceFile?.delete()
        currentImportedSourceFile = null
    }

    private data class InspectedTrack(
        val sourceFile: File,
        val trackName: String,
        val codecLabel: String,
        val totalDurationMs: Int
    )

    companion object {
        const val MAX_DURATION_MS = 5000
        const val MIN_DURATION_MS = 400
        const val MAX_BLE_AUDIO_BYTES = 240 * 1024L

        fun computeTrimWindow(
            totalDurationMs: Int,
            requestedStartMs: Int,
            maxClipMs: Int = MAX_DURATION_MS
        ): AudioTrimWindow {
            val safeTotal = totalDurationMs.coerceAtLeast(0)
            if (safeTotal <= 0) return AudioTrimWindow(0, 0, 0)
            val clipDur = safeTotal.coerceAtMost(maxClipMs)
            val maxStart = (safeTotal - clipDur).coerceAtLeast(0)
            val clampedStart = requestedStartMs.coerceIn(0, maxStart)
            return AudioTrimWindow(
                startMs = clampedStart,
                durationMs = clipDur,
                endMs = (clampedStart + clipDur).coerceAtMost(safeTotal)
            )
        }

        private fun prepareImportedTrack(context: Context, uri: Uri): InspectedTrack? {
            val tempInput = File(context.cacheDir, "import_src_${UUID.randomUUID().toString().take(8)}")
            return try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempInput.outputStream().use { output -> input.copyTo(output) }
                } ?: return null
                if (!tempInput.exists() || tempInput.length() < 64L) {
                    tempInput.delete()
                    return null
                }

                val trackName = queryDisplayName(context, uri) ?: "Imported track"

                // 1. Check pure-Java containers first (FLAC, Ogg Opus, Ogg Vorbis, ALAC, WAV, AIFF)
                val pureMeta = PureAudioContainerDecoder.inspectTrackMetadata(tempInput)
                if (pureMeta != null) {
                    return InspectedTrack(
                        sourceFile = tempInput,
                        trackName = trackName,
                        codecLabel = pureMeta.codecLabel,
                        totalDurationMs = pureMeta.durationMs
                    )
                }

                // 2. Fall back to Android MediaExtractor + MediaMetadataRetriever (MP3, AAC, M4A, AMR, WebM, etc.)
                val extractor = MediaExtractor()
                var audioFormat: MediaFormat? = null
                try {
                    extractor.setDataSource(tempInput.absolutePath)
                    for (idx in 0 until extractor.trackCount) {
                        val fmt = extractor.getTrackFormat(idx)
                        if ((fmt.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                            audioFormat = fmt
                            break
                        }
                    }
                } finally {
                    runCatching { extractor.release() }
                }
                if (audioFormat == null) {
                    tempInput.delete()
                    return null
                }

                val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
                val codecLabel = readableCodecLabel(mime)
                val extractorDurationMs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    (audioFormat.getLong(MediaFormat.KEY_DURATION) / 1000L).toInt()
                } else {
                    0
                }

                val mmr = MediaMetadataRetriever()
                val mmrDurationMs = try {
                    mmr.setDataSource(tempInput.absolutePath)
                    mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
                } catch (_: Exception) {
                    0
                } finally {
                    runCatching { mmr.release() }
                }

                val durationMs = maxOf(extractorDurationMs, mmrDurationMs)
                    .coerceAtLeast(MIN_DURATION_MS)

                InspectedTrack(
                    sourceFile = tempInput,
                    trackName = trackName,
                    codecLabel = codecLabel,
                    totalDurationMs = durationMs
                )
            } catch (_: Exception) {
                tempInput.delete()
                null
            }
        }

        private fun readableCodecLabel(mime: String): String = when {
            mime.contains("flac", ignoreCase = true) -> "FLAC"
            mime.contains("opus", ignoreCase = true) -> "Opus"
            mime.contains("vorbis", ignoreCase = true) -> "Ogg Vorbis"
            mime.contains("alac", ignoreCase = true) -> "ALAC"
            mime.contains("mpeg", ignoreCase = true) -> "MP3"
            mime.contains("mp4a", ignoreCase = true) || mime.contains("aac", ignoreCase = true) -> "AAC/M4A"
            mime.contains("amr", ignoreCase = true) -> "AMR"
            mime.contains("raw", ignoreCase = true) -> "PCM/WAV"
            else -> "Audio"
        }

        private fun queryDisplayName(context: Context, uri: Uri): String? {
            return runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) cursor.getString(idx) else null
                        } else null
                    }
            }.getOrNull()
        }

        private fun extractSegmentToMp3(
            context: Context,
            sourceFile: File,
            startMs: Int,
            durationMs: Int
        ): EncodedMp3Result? {
            if (!sourceFile.exists()) return null
            val outFile = File(context.cacheDir, "story_clip_${UUID.randomUUID().toString().take(8)}.mp3")

            // 1. Try pure-Java decoders first (FLAC, Ogg Opus, Ogg Vorbis, ALAC, WAV, AIFF)
            val purePcm = PureAudioContainerDecoder.decodeSegment(sourceFile, startMs, durationMs)
            if (purePcm != null && purePcm.samples.isNotEmpty()) {
                val encoded = MonoMp3Encoder.encodeMonoPcmToMp3File(
                    samples = purePcm.samples,
                    sampleRate = purePcm.sampleRate,
                    outFile = outFile,
                    maxBytes = MAX_BLE_AUDIO_BYTES
                )
                if (encoded != null) return encoded
                outFile.delete()
            }

            // 2. Fallback to Android MediaExtractor + MediaCodec -> PCM -> Mono MP3
            val mediaCodecPcm = decodeViaMediaCodecToMonoPcm(sourceFile, startMs, durationMs)
            if (mediaCodecPcm != null && mediaCodecPcm.samples.isNotEmpty()) {
                val encoded = MonoMp3Encoder.encodeMonoPcmToMp3File(
                    samples = mediaCodecPcm.samples,
                    sampleRate = mediaCodecPcm.sampleRate,
                    outFile = outFile,
                    maxBytes = MAX_BLE_AUDIO_BYTES
                )
                if (encoded != null) return encoded
            }
            outFile.delete()
            return null
        }

        private fun decodeViaMediaCodecToMonoPcm(
            sourceFile: File,
            startMs: Int,
            durationMs: Int
        ): DecodedMonoPcm? {
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            try {
                extractor.setDataSource(sourceFile.absolutePath)
                var audioTrackIndex = -1
                var audioFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        audioFormat = fmt
                        break
                    }
                }
                if (audioTrackIndex < 0 || audioFormat == null) return null
                val inputMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return null

                extractor.selectTrack(audioTrackIndex)
                val startUs = startMs * 1000L
                val endUs = (startMs + durationMs) * 1000L
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                decoder = MediaCodec.createDecoderByType(inputMime)
                decoder.configure(audioFormat, null, null, 0)
                decoder.start()

                var sampleRate = audioFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
                var channels = audioFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
                var pcmEncoding = audioFormat.getIntegerOrDefault(
                    MediaFormat.KEY_PCM_ENCODING,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                val targetMonoSamples = ((durationMs.toLong() * sampleRate) / 1000L).toInt().coerceAtLeast(1)
                val collected = ArrayList<Float>(targetMonoSamples.coerceIn(1024, 260_000))
                var extractorDone = false
                var decoderDone = false
                val decInfo = MediaCodec.BufferInfo()
                val deadlineMs = System.currentTimeMillis() + 12_000L

                while (!decoderDone && System.currentTimeMillis() < deadlineMs) {
                    if (!extractorDone) {
                        val inIdx = decoder.dequeueInputBuffer(5_000L)
                        if (inIdx >= 0) {
                            val inBuf = decoder.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                val sampleSize = extractor.readSampleData(inBuf, 0)
                                val ptsUs = extractor.sampleTime
                                if (sampleSize < 0 || ptsUs > endUs + 80_000L) {
                                    decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    extractorDone = true
                                } else {
                                    decoder.queueInputBuffer(inIdx, 0, sampleSize, ptsUs, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outIdx = decoder.dequeueOutputBuffer(decInfo, 5_000L)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFmt = decoder.outputFormat
                            sampleRate = outFmt.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                            channels = outFmt.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                                .coerceAtLeast(1)
                            pcmEncoding = outFmt.getIntegerOrDefault(
                                MediaFormat.KEY_PCM_ENCODING,
                                AudioFormat.ENCODING_PCM_16BIT
                            )
                        }
                        outIdx >= 0 -> {
                            if (decInfo.size > 0) {
                                val outBuf = decoder.getOutputBuffer(outIdx)
                                if (outBuf != null) {
                                    outBuf.position(decInfo.offset)
                                    outBuf.limit(decInfo.offset + decInfo.size)
                                    val rawBytes = ByteArray(decInfo.size)
                                    outBuf.get(rawBytes)

                                    val (bitsPerSample, isFloat, isUnsigned8) = pcmFormatSpec(pcmEncoding)
                                    val monoChunk = AudioPcmProcessor.decodePcmBytesToMonoFloats(
                                        bytes = rawBytes,
                                        size = rawBytes.size,
                                        channels = channels,
                                        bitsPerSample = bitsPerSample,
                                        bigEndian = false,
                                        isFloat = isFloat,
                                        isUnsigned8Bit = isUnsigned8
                                    )
                                    val bufDurationUs = if (sampleRate > 0) {
                                        (monoChunk.size * 1_000_000L) / sampleRate
                                    } else 0L
                                    val bufEndUs = decInfo.presentationTimeUs + bufDurationUs

                                    if (bufEndUs > startUs && decInfo.presentationTimeUs <= endUs) {
                                        val skipFrames = if (decInfo.presentationTimeUs < startUs) {
                                            (((startUs - decInfo.presentationTimeUs) * sampleRate) / 1_000_000L)
                                                .toInt()
                                                .coerceIn(0, monoChunk.size)
                                        } else 0
                                        val maxTake = (((durationMs.toLong() * sampleRate) / 1000L).toInt() - collected.size)
                                            .coerceAtLeast(0)
                                        val avail = (monoChunk.size - skipFrames).coerceAtLeast(0)
                                        val take = minOf(avail, maxTake)
                                        for (i in 0 until take) {
                                            collected.add(monoChunk[skipFrames + i])
                                        }
                                        if (collected.size >= ((durationMs.toLong() * sampleRate) / 1000L).toInt()) {
                                            decoderDone = true
                                        }
                                    }
                                }
                            }
                            decoder.releaseOutputBuffer(outIdx, false)
                            if ((decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                decoderDone = true
                            }
                        }
                    }
                }

                if (collected.isEmpty()) return null
                val mono = collected.toFloatArray()
                val actualMs = ((mono.size.toLong() * 1000L) / sampleRate).toInt()
                return DecodedMonoPcm(sampleRate, mono, actualMs, readableCodecLabel(inputMime))
            } catch (_: Exception) {
                return null
            } finally {
                runCatching { decoder?.stop() }
                runCatching { decoder?.release() }
                runCatching { extractor.release() }
            }
        }

        private data class PcmSpec(
            val bitsPerSample: Int,
            val isFloat: Boolean,
            val isUnsigned8Bit: Boolean
        )

        private fun pcmFormatSpec(pcmEncoding: Int): PcmSpec {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (pcmEncoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) {
                    return PcmSpec(24, isFloat = false, isUnsigned8Bit = false)
                }
                if (pcmEncoding == AudioFormat.ENCODING_PCM_32BIT) {
                    return PcmSpec(32, isFloat = false, isUnsigned8Bit = false)
                }
            }
            return when (pcmEncoding) {
                AudioFormat.ENCODING_PCM_8BIT -> PcmSpec(8, isFloat = false, isUnsigned8Bit = true)
                AudioFormat.ENCODING_PCM_FLOAT -> PcmSpec(32, isFloat = true, isUnsigned8Bit = false)
                else -> PcmSpec(16, isFloat = false, isUnsigned8Bit = false)
            }
        }

        private fun MediaFormat.getIntegerOrDefault(key: String, defaultValue: Int): Int =
            if (containsKey(key)) getInteger(key) else defaultValue
    }
}
