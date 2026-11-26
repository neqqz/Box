package com.google.ai.edge.gallery.customtasks.supertonic

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.audio.WavUtils
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDataFile
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
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
import java.io.File
import javax.inject.Inject

private const val TAG = "SupertonicEngine"

class SupertonicEngine {
  @Volatile var tts: OfflineTts? = null
  @Volatile var sampleRate: Int = 44100

  suspend fun loadModel(
    durationPredictor: String,
    textEncoder: String,
    vectorEstimator: String,
    vocoder: String,
    ttsJson: String,
    unicodeIndexer: String,
    voiceStyle: String,
  ): Boolean = withContext(Dispatchers.Default) {
    try {
      runCatching { tts?.release() }
      tts = null
      val config = OfflineTtsConfig(
        model = OfflineTtsModelConfig(
          supertonic = OfflineTtsSupertonicModelConfig(
            durationPredictor = durationPredictor,
            textEncoder = textEncoder,
            vectorEstimator = vectorEstimator,
            vocoder = vocoder,
            ttsJson = ttsJson,
            unicodeIndexer = unicodeIndexer,
            voiceStyle = voiceStyle,
          ),
          numThreads = 2,
          debug = false,
          provider = "cpu",
        )
      )
      val instance = OfflineTts(null, config)
      tts = instance
      sampleRate = instance.sampleRate()
      true
    } catch (t: Throwable) {
      Log.e(TAG, "loadModel failed", t)
      false
    }
  }

  suspend fun synthesize(text: String, speakerId: Int, speed: Float): FloatArray? =
    withContext(Dispatchers.Default) {
      val instance = tts ?: return@withContext null
      val trimmed = text.trim()
      if (trimmed.isEmpty()) return@withContext FloatArray(0)
      try {
        instance.generate(trimmed, speakerId, speed).samples
      } catch (t: Throwable) {
        Log.e(TAG, "synthesize failed", t)
        null
      }
    }

  fun release() {
    runCatching { tts?.release() }
    tts = null
  }
}

data class SupertonicUiState(
  val text: String = "",
  val speakerId: Int = 0,
  val speed: Float = 1.0f,
  val numSpeakers: Int = 10,
  val isSpeaking: Boolean = false,
  val lastWavPath: String? = null,
  val errorMessage: String? = null,
)

@HiltViewModel
class SupertonicViewModel @Inject constructor() : ViewModel() {
  private val _uiState = MutableStateFlow(SupertonicUiState())
  val uiState: StateFlow<SupertonicUiState> = _uiState.asStateFlow()

  private var playbackJob: Job? = null

  fun onTextChange(value: String) = _uiState.update { it.copy(text = value) }
  fun onSpeakerChange(id: Int) = _uiState.update { it.copy(speakerId = id) }
  fun onSpeedChange(speed: Float) = _uiState.update { it.copy(speed = speed) }

  fun syncSpeakers(model: Model) {
    val count = (model.instance as? SupertonicEngine)?.tts?.numSpeakers()?.coerceAtLeast(1) ?: 10
    _uiState.update { it.copy(numSpeakers = count, speakerId = it.speakerId.coerceIn(0, count - 1)) }
  }

  fun speak(context: Context, model: Model) {
    val engine = model.instance as? SupertonicEngine ?: run {
      _uiState.update { it.copy(errorMessage = "Model not loaded") }
      return
    }
    val text = _uiState.value.text.trim()
    if (text.isEmpty()) return
    stop()
    _uiState.update { it.copy(isSpeaking = true, errorMessage = null) }
    playbackJob = viewModelScope.launch {
      val state = _uiState.value
      val samples = engine.synthesize(text, state.speakerId, state.speed)
      if (samples == null || samples.isEmpty()) {
        _uiState.update { it.copy(isSpeaking = false, errorMessage = "Synthesis failed") }
        return@launch
      }
      val wavFile = File(context.cacheDir, "supertonic_last.wav")
      withContext(Dispatchers.IO) {
        WavUtils.writeMonoFloatWav(wavFile, samples, engine.sampleRate)
      }
      _uiState.update { it.copy(lastWavPath = wavFile.absolutePath) }
      WavUtils.playMonoFloat(samples, engine.sampleRate)
      _uiState.update { it.copy(isSpeaking = false) }
    }
  }

  fun stop() {
    playbackJob?.cancel()
    playbackJob = null
    _uiState.update { it.copy(isSpeaking = false) }
  }
}

class SupertonicTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.SUPERTONIC,
      label = "Supertonic",
      category = Category.VOICE,
      icon = Icons.Outlined.RecordVoiceOver,
      models =
        mutableListOf(
          Model(
            name = "Supertonic (multilingual)",
            info =
              "Reads text aloud, very quickly. A small, fast voice engine with several built-in voices in English, Korean, Spanish, Portuguese and French. Works completely offline (~96 MB).",
            url =
              "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-tts-int8-2026-03-06/resolve/main/vector_estimator.int8.onnx?download=true",
            sizeInBytes = 40_686_657L,
            downloadFileName = "vector_estimator.int8.onnx",
            version = "2026-03-06",
            extraDataFiles =
              listOf(
                ModelDataFile("text_encoder", "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-tts-int8-2026-03-06/resolve/main/text_encoder.int8.onnx?download=true", "text_encoder.int8.onnx", 27_431_318L),
                ModelDataFile("vocoder", "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-tts-int8-2026-03-06/resolve/main/vocoder.int8.onnx?download=true", "vocoder.int8.onnx", 25_977_132L),
                ModelDataFile("duration_predictor", "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-tts-int8-2026-03-06/resolve/main/duration_predictor.int8.onnx?download=true", "duration_predictor.int8.onnx", 1_521_526L),
                ModelDataFile("voice", "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-tts-int8-2026-03-06/resolve/main/voice.bin?download=true", "voice.bin", 517_168L),
                ModelDataFile("unicode_indexer", "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-tts-int8-2026-03-06/resolve/main/unicode_indexer.bin?download=true", "unicode_indexer.bin", 262_144L),
                ModelDataFile("tts_json", "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-tts-int8-2026-03-06/resolve/main/tts.json?download=true", "tts.json", 8_699L),
              ),
          )
        ),
      description =
        "Lightning-fast on-device text-to-speech using Supertonic (English, Korean, Spanish, Portuguese, French). Multiple built-in voices, ~66M params, fully offline.",
      shortDescription = "Fast multilingual text-to-speech",
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch {
      val engine = SupertonicEngine()
      fun fileFor(tag: String, fallback: String): String {
        val fileName = model.extraDataFiles.find { it.name == tag }?.downloadFileName ?: fallback
        return model.getPath(context, fileName)
      }
      val ok =
        engine.loadModel(
          durationPredictor = fileFor("duration_predictor", "duration_predictor.int8.onnx"),
          textEncoder = fileFor("text_encoder", "text_encoder.int8.onnx"),
          vectorEstimator = model.getPath(context, model.downloadFileName),
          vocoder = fileFor("vocoder", "vocoder.int8.onnx"),
          ttsJson = fileFor("tts_json", "tts.json"),
          unicodeIndexer = fileFor("unicode_indexer", "unicode_indexer.bin"),
          voiceStyle = fileFor("voice", "voice.bin"),
        )
      if (ok) {
        model.instance = engine
        onDone("")
      } else {
        onDone("Failed to load Supertonic model")
      }
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    (model.instance as? SupertonicEngine)?.release()
    model.instance = null
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    SupertonicScreen(modelManagerViewModel = myData.modelManagerViewModel)
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object SupertonicTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask = SupertonicTask()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SupertonicScreen(
  modelManagerViewModel: ModelManagerViewModel,
  viewModel: SupertonicViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val mmState by modelManagerViewModel.uiState.collectAsState()
  val model = mmState.selectedModel
  val ready = mmState.modelInitializationStatus[model.name]?.status == ModelInitializationStatusType.INITIALIZED
  val context = LocalContext.current

  if (!ready) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(
        "Download and initialize Supertonic to synthesize speech.",
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    return
  }

  viewModel.syncSpeakers(model)

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    OutlinedTextField(
      value = uiState.text,
      onValueChange = viewModel::onTextChange,
      label = { Text("Text to speak") },
      modifier = Modifier.fillMaxWidth(),
      minLines = 4,
    )

    Text("Voice (#${uiState.speakerId})", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      for (id in 0 until uiState.numSpeakers.coerceAtMost(20)) {
        FilterChip(
          selected = uiState.speakerId == id,
          onClick = { viewModel.onSpeakerChange(id) },
          label = { Text("V$id") },
        )
      }
    }

    Text("Speed: ${"%.2fx".format(uiState.speed)}", style = MaterialTheme.typography.labelLarge)
    Slider(
      value = uiState.speed,
      onValueChange = viewModel::onSpeedChange,
      valueRange = 0.6f..1.8f,
    )

    uiState.errorMessage?.let {
      Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      if (uiState.isSpeaking) {
        Button(
          onClick = viewModel::stop,
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
          Icon(Icons.Outlined.Stop, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text("Stop")
        }
      } else {
        Button(
          onClick = { viewModel.speak(context, model) },
          enabled = uiState.text.isNotBlank(),
        ) {
          Icon(Icons.Outlined.PlayArrow, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text("Speak")
        }
      }

      uiState.lastWavPath?.let { path ->
        val file = File(path)
        if (file.exists()) {
          OutlinedButton(onClick = { WavUtils.shareAudioFile(context, file) }) {
            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Share")
          }
          OutlinedButton(
            onClick = {
              WavUtils.copyToMusicExports(context, file, "supertonic_${System.currentTimeMillis()}.wav")
            }
          ) {
            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
          }
        }
      }
    }
    Spacer(Modifier.height(16.dp))
  }
}
