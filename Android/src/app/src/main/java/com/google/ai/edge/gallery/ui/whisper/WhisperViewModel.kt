package com.google.ai.edge.gallery.ui.whisper

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.audio.AudioDecoder
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.whisper.WhisperEngine
import com.google.ai.edge.gallery.whisper.WhisperSegment
import com.google.ai.edge.gallery.whisper.toFloat32
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WhisperUiState(
    val isRecording: Boolean = false,
    val isTranscribing: Boolean = false,
    val transcript: String = "",
    val segments: List<WhisperSegment> = emptyList(),
    val language: String = "auto",
    val showTimestamps: Boolean = true,
    val sourceLabel: String? = null,
    val errorMessage: String? = null,
)

@HiltViewModel
class WhisperViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(WhisperUiState())
    val uiState: StateFlow<WhisperUiState> = _uiState.asStateFlow()

    private var recordJob: Job? = null
    private val pcmBuffer = mutableListOf<Short>()
    private var audioRecord: AudioRecord? = null

    fun setLanguage(lang: String) {
        _uiState.value = _uiState.value.copy(language = lang)
    }

    fun toggleTimestamps() {
        _uiState.value = _uiState.value.copy(showTimestamps = !_uiState.value.showTimestamps)
    }

    fun startRecording() {
        if (_uiState.value.isRecording) return
        _uiState.value = _uiState.value.copy(
            isRecording = true,
            isTranscribing = false,
            errorMessage = null,
            sourceLabel = "Microphone",
        )
        pcmBuffer.clear()

        recordJob = viewModelScope.launch(Dispatchers.IO) {
            val bufferSize = AudioRecord.getMinBufferSize(
                WhisperEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(4096)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                WhisperEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            audioRecord = recorder
            val chunk = ShortArray(bufferSize / 2)
            recorder.startRecording()

            while (_uiState.value.isRecording) {
                val read = recorder.read(chunk, 0, chunk.size)
                if (read > 0) {
                    synchronized(pcmBuffer) {
                        for (i in 0 until read) pcmBuffer.add(chunk[i])
                    }
                }
            }
            recorder.stop()
            recorder.release()
            audioRecord = null
        }
    }

    fun stopRecordingAndTranscribe(model: Model) {
        _uiState.value = _uiState.value.copy(isRecording = false, isTranscribing = true)

        viewModelScope.launch(Dispatchers.Default) {
            recordJob?.join()

            val samples: FloatArray
            synchronized(pcmBuffer) {
                samples = pcmBuffer.toShortArray().toFloat32()
                pcmBuffer.clear()
            }
            runTranscription(model, samples, emptyError = "No audio recorded")
        }
    }

    fun transcribeUri(context: Context, uri: Uri, model: Model, fileName: String? = null) {
        if (_uiState.value.isRecording || _uiState.value.isTranscribing) return
        _uiState.value = _uiState.value.copy(
            isTranscribing = true,
            errorMessage = null,
            sourceLabel = fileName ?: uri.lastPathSegment ?: "Audio file",
        )
        viewModelScope.launch(Dispatchers.Default) {
            val samples = AudioDecoder.decodeTo16kMonoFloat(context, uri)
            runTranscription(model, samples, emptyError = "Could not decode audio file")
        }
    }

    private suspend fun runTranscription(model: Model, samples: FloatArray, emptyError: String) {
        if (samples.isEmpty()) {
            _uiState.value = _uiState.value.copy(isTranscribing = false, errorMessage = emptyError)
            return
        }
        val engine = model.instance as? WhisperEngine ?: run {
            _uiState.value = _uiState.value.copy(isTranscribing = false, errorMessage = "Model not loaded")
            return
        }
        val lang = _uiState.value.language
        val segments = engine.transcribeWithSegments(samples, lang)
        val joined = if (segments.isNotEmpty()) {
            segments.joinToString(" ") { it.text.trim() }.trim()
        } else {
            engine.transcribe(samples, lang)
        }
        _uiState.value = _uiState.value.copy(
            isTranscribing = false,
            segments = segments.ifEmpty { _uiState.value.segments },
            transcript = joined.ifEmpty { _uiState.value.transcript },
            errorMessage = if (joined.isEmpty()) "Could not transcribe audio" else null,
        )
    }

    fun clearTranscript() {
        _uiState.value = _uiState.value.copy(transcript = "", segments = emptyList(), errorMessage = null, sourceLabel = null)
    }

    private fun MutableList<Short>.toShortArray(): ShortArray {
        val arr = ShortArray(size)
        for (i in indices) arr[i] = this[i]
        return arr
    }
}
