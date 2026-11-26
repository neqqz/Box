package com.google.ai.edge.gallery.customtasks.sensevoice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.audio.AudioDecoder
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDataFile
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.whisper.WhisperEngine
import com.google.ai.edge.gallery.whisper.toFloat32
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val TAG = "SenseVoiceEngine"
private val SENSE_VOICE_TAG_REGEX = Regex("<\\|[^|>]*\\|>")

val SENSE_VOICE_LANGUAGES = listOf(
  "auto" to "Auto-detect",
  "en" to "English",
  "zh" to "Chinese",
  "yue" to "Cantonese",
  "ja" to "Japanese",
  "ko" to "Korean",
)

data class SenseVoiceResult(
  val text: String = "",
  val emotion: String = "",
  val event: String = "",
  val language: String = "",
)

class SenseVoiceEngine {
  @Volatile var recognizer: OfflineRecognizer? = null

  suspend fun loadModel(
    modelPath: String,
    tokensPath: String,
    language: String = "auto",
    useItn: Boolean = true,
  ): Boolean = withContext(Dispatchers.Default) {
    try {
      runCatching { recognizer?.release() }
      recognizer = null
      val config = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = WhisperEngine.SAMPLE_RATE, featureDim = 80),
        modelConfig = OfflineModelConfig(
          senseVoice = OfflineSenseVoiceModelConfig(
            model = modelPath,
            language = language,
            useInverseTextNormalization = useItn,
          ),
          tokens = tokensPath,
          numThreads = 2,
          provider = "cpu",
        ),
        decodingMethod = "greedy_search",
      )
      recognizer = OfflineRecognizer(null, config)
      true
    } catch (t: Throwable) {
      Log.e(TAG, "loadModel failed", t)
      false
    }
  }

  suspend fun transcribe(samples: FloatArray): SenseVoiceResult = withContext(Dispatchers.Default) {
    val rec = recognizer ?: return@withContext SenseVoiceResult()
    try {
      val stream = rec.createStream()
      stream.acceptWaveform(samples, WhisperEngine.SAMPLE_RATE)
      rec.decode(stream)
      val res = rec.getResult(stream)
      stream.release()
      SenseVoiceResult(
        text = res.text.replace(SENSE_VOICE_TAG_REGEX, "").trim(),
        emotion = cleanMetaTag(res.emotion),
        event = cleanMetaTag(res.event),
        language = cleanMetaTag(res.lang),
      )
    } catch (t: Throwable) {
      Log.e(TAG, "transcribe failed", t)
      SenseVoiceResult()
    }
  }

  fun release() {
    runCatching { recognizer?.release() }
    recognizer = null
  }

  private fun cleanMetaTag(raw: String): String =
    raw.replace("<|", "").replace("|>", "").trim()
}

data class SenseVoiceUiState(
  val isRecording: Boolean = false,
  val isTranscribing: Boolean = false,
  val transcripts: List<SenseVoiceResult> = emptyList(),
  val language: String = "auto",
  val useItn: Boolean = true,
  val showEmotion: Boolean = true,
  val showEvents: Boolean = true,
  val isApplyingSettings: Boolean = false,
  val errorMessage: String? = null,
)

@HiltViewModel
class SenseVoiceViewModel @Inject constructor() : ViewModel() {
  private val _uiState = MutableStateFlow(SenseVoiceUiState())
  val uiState: StateFlow<SenseVoiceUiState> = _uiState.asStateFlow()

  private var recordJob: Job? = null
  private val pcmBuffer = mutableListOf<Short>()

  fun setLanguage(context: Context, model: Model, language: String) {
    _uiState.update { it.copy(language = language) }
    reloadConfig(context, model)
  }

  fun toggleItn(context: Context, model: Model) {
    _uiState.update { it.copy(useItn = !it.useItn) }
    reloadConfig(context, model)
  }

  fun toggleShowEmotion() {
    _uiState.update { it.copy(showEmotion = !it.showEmotion) }
  }

  fun toggleShowEvents() {
    _uiState.update { it.copy(showEvents = !it.showEvents) }
  }

  fun clearTranscripts() {
    _uiState.update { it.copy(transcripts = emptyList(), errorMessage = null) }
  }

  private fun reloadConfig(context: Context, model: Model) {
    val engine = model.instance as? SenseVoiceEngine ?: return
    val modelPath = model.getPath(context, model.downloadFileName)
    val tokensPath = model.getPath(context, "tokens.txt")
    val state = _uiState.value
    viewModelScope.launch {
      _uiState.update { it.copy(isApplyingSettings = true) }
      engine.loadModel(modelPath, tokensPath, state.language, state.useItn)
      _uiState.update { it.copy(isApplyingSettings = false) }
    }
  }

  fun startRecording() {
    if (_uiState.value.isRecording || _uiState.value.isTranscribing) return
    _uiState.update { it.copy(isRecording = true, errorMessage = null) }
    pcmBuffer.clear()
    recordJob = viewModelScope.launch(Dispatchers.IO) {
      val minBuf = AudioRecord.getMinBufferSize(
        WhisperEngine.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      ).coerceAtLeast(4096)
      val recorder = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        WhisperEngine.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBuf,
      )
      val chunk = ShortArray(minBuf / 2)
      recorder.startRecording()
      while (_uiState.value.isRecording) {
        val read = recorder.read(chunk, 0, chunk.size)
        if (read > 0) {
          synchronized(pcmBuffer) {
            for (i in 0 until read) pcmBuffer.add(chunk[i])
          }
        }
      }
      runCatching { recorder.stop() }
      runCatching { recorder.release() }
    }
  }

  fun stopRecordingAndTranscribe(model: Model) {
    _uiState.update { it.copy(isRecording = false, isTranscribing = true) }
    viewModelScope.launch {
      recordJob?.join()
      val samples = synchronized(pcmBuffer) {
        val arr = pcmBuffer.toShortArray().toFloat32()
        pcmBuffer.clear()
        arr
      }
      runInference(model, samples, "No audio recorded")
    }
  }

  fun transcribeUri(context: Context, uri: Uri, model: Model) {
    if (_uiState.value.isRecording || _uiState.value.isTranscribing) return
    _uiState.update { it.copy(isTranscribing = true, errorMessage = null) }
    viewModelScope.launch {
      val samples = AudioDecoder.decodeTo16kMonoFloat(context, uri)
      runInference(model, samples, "Could not decode audio file")
    }
  }

  private suspend fun runInference(model: Model, samples: FloatArray, emptyError: String) {
    if (samples.isEmpty()) {
      _uiState.update { it.copy(isTranscribing = false, errorMessage = emptyError) }
      return
    }
    val engine = model.instance as? SenseVoiceEngine ?: run {
      _uiState.update { it.copy(isTranscribing = false, errorMessage = "Model not loaded") }
      return
    }
    val res = engine.transcribe(samples)
    if (res.text.isEmpty()) {
      _uiState.update { it.copy(isTranscribing = false, errorMessage = "No speech recognized") }
    } else {
      _uiState.update {
        it.copy(
          isTranscribing = false,
          transcripts = listOf(res) + it.transcripts,
          errorMessage = null,
        )
      }
    }
  }
}
