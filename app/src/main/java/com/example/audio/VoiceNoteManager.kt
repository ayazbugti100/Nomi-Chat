package com.example.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class VoiceRecordingUiState(
    val isRecording: Boolean = false,
    val elapsedSec: Long = 0L,
    val amplitudes: List<Float> = emptyList()
)

data class VoicePlaybackUiState(
    val playingMessageId: String? = null,
    val isPlaying: Boolean = false,
    val progress: Float = 0f,
    val positionSec: Long = 0L,
    val durationSec: Long = 0L
)

data class RecordedVoiceNote(
    val mediaData: String,
    val durationSec: Long
)

class VoiceNoteManager(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext

    private val _recordingState = MutableStateFlow(VoiceRecordingUiState())
    val recordingState: StateFlow<VoiceRecordingUiState> = _recordingState.asStateFlow()

    private val _playbackState = MutableStateFlow(VoicePlaybackUiState())
    val playbackState: StateFlow<VoicePlaybackUiState> = _playbackState.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingStartMs: Long = 0L
    private var recordingTickJob: Job? = null

    private var mediaPlayer: MediaPlayer? = null
    private var playbackFile: File? = null
    private var playbackTickJob: Job? = null

    fun startRecording(onMaxDurationReached: (RecordedVoiceNote) -> Unit = {}): Boolean {
        if (_recordingState.value.isRecording) return true
        stopPlayback()
        return try {
            val outFile = File(appContext.cacheDir, "voice_rec_${UUID.randomUUID()}.m4a")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(appContext)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(24_000)
                setAudioSamplingRate(16_000)
                setAudioChannels(1)
                setOutputFile(outFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            recordingFile = outFile
            recordingStartMs = System.currentTimeMillis()
            _recordingState.value = VoiceRecordingUiState(
                isRecording = true,
                elapsedSec = 0L,
                amplitudes = emptyList()
            )
            recordingTickJob?.cancel()
            recordingTickJob = scope.launch {
                while (isActive && _recordingState.value.isRecording) {
                    delay(120L)
                    val elapsedSec = ((System.currentTimeMillis() - recordingStartMs) / 1000L).coerceAtLeast(0L)
                    val amp = runCatching { mediaRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                    val normalized = (amp / 18_000f).coerceIn(0.12f, 1f)
                    val updatedBars = (_recordingState.value.amplitudes + normalized).takeLast(28)
                    _recordingState.value = VoiceRecordingUiState(
                        isRecording = true,
                        elapsedSec = elapsedSec,
                        amplitudes = updatedBars
                    )
                    if (elapsedSec >= MAX_VOICE_SECONDS) {
                        val note = stopAndEncode()
                        if (note != null) {
                            onMaxDurationReached(note)
                        }
                        break
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start voice recording", e)
            cancelRecording()
            false
        }
    }

    fun stopAndEncode(): RecordedVoiceNote? {
        recordingTickJob?.cancel()
        recordingTickJob = null
        val recorder = mediaRecorder
        val file = recordingFile
        val elapsedSec = ((System.currentTimeMillis() - recordingStartMs) / 1000L).coerceAtLeast(1L)
        mediaRecorder = null
        recordingFile = null
        _recordingState.value = VoiceRecordingUiState()

        if (recorder == null || file == null) {
            runCatching { file?.delete() }
            return null
        }
        return try {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            if (!file.exists() || file.length() <= 64L) {
                runCatching { file.delete() }
                return null
            }
            val bytes = file.readBytes()
            runCatching { file.delete() }
            val dataUri = "data:audio/mp4;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            if (dataUri.length > 235_000) {
                Log.w(TAG, "Voice note exceeded size budget (${dataUri.length} chars)")
                null
            } else {
                RecordedVoiceNote(
                    mediaData = dataUri,
                    durationSec = elapsedSec.coerceIn(1L, MAX_VOICE_SECONDS)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode voice recording", e)
            runCatching { file.delete() }
            null
        }
    }

    fun cancelRecording() {
        recordingTickJob?.cancel()
        recordingTickJob = null
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        runCatching { recordingFile?.delete() }
        recordingFile = null
        _recordingState.value = VoiceRecordingUiState()
    }

    fun togglePlayback(
        messageId: String,
        mediaData: String?,
        fallbackDurationSec: Long = 0L,
        onError: (String) -> Unit = {}
    ) {
        if (mediaData.isNullOrBlank()) {
            onError("Audio clip unavailable.")
            return
        }
        val current = _playbackState.value
        if (current.playingMessageId == messageId && mediaPlayer != null) {
            if (current.isPlaying) {
                runCatching { mediaPlayer?.pause() }
                _playbackState.value = current.copy(isPlaying = false)
            } else {
                runCatching { mediaPlayer?.start() }
                _playbackState.value = current.copy(isPlaying = true)
            }
            return
        }
        startPlaybackAtFraction(
            messageId = messageId,
            mediaData = mediaData,
            fallbackDurationSec = fallbackDurationSec,
            initialFraction = 0f,
            onError = onError
        )
    }

    fun seekToFraction(
        messageId: String,
        mediaData: String?,
        fallbackDurationSec: Long = 0L,
        fraction: Float,
        onError: (String) -> Unit = {}
    ) {
        val clamped = fraction.coerceIn(0f, 1f)
        val current = _playbackState.value
        val activePlayer = mediaPlayer
        if (current.playingMessageId == messageId && activePlayer != null) {
            val durMs = runCatching { activePlayer.duration }.getOrDefault(0)
                .coerceAtLeast((fallbackDurationSec.coerceAtLeast(1L) * 1000L).toInt())
            val targetMs = (durMs * clamped).toInt().coerceIn(0, durMs)
            runCatching {
                activePlayer.seekTo(targetMs)
                if (!current.isPlaying) {
                    activePlayer.start()
                }
            }.onSuccess {
                _playbackState.value = current.copy(
                    isPlaying = true,
                    progress = clamped,
                    positionSec = (targetMs / 1000L).toLong(),
                    durationSec = (durMs / 1000L).toLong().coerceAtLeast(current.durationSec.coerceAtLeast(1L))
                )
            }.onFailure {
                onError("Unable to seek voice message.")
            }
            return
        }
        if (mediaData.isNullOrBlank()) {
            onError("Audio clip unavailable.")
            return
        }
        startPlaybackAtFraction(
            messageId = messageId,
            mediaData = mediaData,
            fallbackDurationSec = fallbackDurationSec,
            initialFraction = clamped,
            onError = onError
        )
    }

    private fun startPlaybackAtFraction(
        messageId: String,
        mediaData: String,
        fallbackDurationSec: Long,
        initialFraction: Float,
        onError: (String) -> Unit
    ) {
        stopPlayback()
        try {
            val base64Part = mediaData.substringAfter("base64,", "")
            val bytes = Base64.decode(base64Part, Base64.DEFAULT)
            if (bytes.isEmpty()) {
                onError("Unable to decode voice message.")
                return
            }
            val tempFile = File(appContext.cacheDir, "voice_play_${UUID.randomUUID()}.m4a")
            tempFile.writeBytes(bytes)
            val player = MediaPlayer().apply {
                setDataSource(tempFile.absolutePath)
                prepare()
                val durMs = duration.takeIf { it > 0 } ?: 0
                if (initialFraction > 0f && durMs > 0) {
                    seekTo((durMs * initialFraction.coerceIn(0f, 1f)).toInt().coerceIn(0, durMs))
                }
                setOnCompletionListener {
                    stopPlayback()
                }
                start()
            }
            mediaPlayer = player
            playbackFile = tempFile
            val totalSec = ((player.duration.takeIf { it > 0 } ?: 0) / 1000L)
                .coerceAtLeast(fallbackDurationSec.coerceAtLeast(1L))
            val initPosSec = (totalSec * initialFraction.coerceIn(0f, 1f)).toLong()
            _playbackState.value = VoicePlaybackUiState(
                playingMessageId = messageId,
                isPlaying = true,
                progress = initialFraction.coerceIn(0f, 1f),
                positionSec = initPosSec,
                durationSec = totalSec
            )
            playbackTickJob?.cancel()
            playbackTickJob = scope.launch {
                while (isActive && mediaPlayer != null) {
                    val p = mediaPlayer ?: break
                    val durMs = p.duration.coerceAtLeast(1)
                    val posMs = runCatching { p.currentPosition }.getOrDefault(0)
                    val playing = runCatching { p.isPlaying }.getOrDefault(false)
                    _playbackState.value = VoicePlaybackUiState(
                        playingMessageId = messageId,
                        isPlaying = playing,
                        progress = (posMs.toFloat() / durMs.toFloat()).coerceIn(0f, 1f),
                        positionSec = (posMs / 1000L).toLong(),
                        durationSec = (durMs / 1000L).toLong().coerceAtLeast(totalSec)
                    )
                    delay(120L)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play voice note", e)
            stopPlayback()
            onError("Could not play voice message.")
        }
    }

    fun stopPlayback() {
        playbackTickJob?.cancel()
        playbackTickJob = null
        runCatching {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
        }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        runCatching { playbackFile?.delete() }
        playbackFile = null
        _playbackState.value = VoicePlaybackUiState()
    }

    fun release() {
        cancelRecording()
        stopPlayback()
    }

    companion object {
        private const val TAG = "VoiceNoteManager"
        private const val MAX_VOICE_SECONDS = 45L
    }
}
