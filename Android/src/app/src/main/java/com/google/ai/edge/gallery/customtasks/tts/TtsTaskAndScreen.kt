package com.google.ai.edge.gallery.customtasks.tts

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
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
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
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

private const val TAG = "BoxTtsEngine"

private fun piperModel(
  name: String,
  repoSlug: String,
  onnxFile: String,
  info: String,
  sizeBytes: Long,
): Model {
  val baseUrl = "https://huggingface.co/csukuangfj/vits-piper-$repoSlug/resolve/main"
  return Model(
    name = name,
    info = info,
    url = "$baseUrl/$onnxFile?download=true",
    sizeInBytes = sizeBytes,
    downloadFileName = onnxFile,
    version = "main",
    extraDataFiles =
      listOf(
        ModelDataFile(
          name = "tokens",
          url = "$baseUrl/tokens.txt?download=true",
          downloadFileName = "tokens.txt",
          sizeInBytes = 8_000L,
        )
      ),
  )
}

class BoxTtsEngine {
  @Volatile var tts: OfflineTts? = null
  @Volatile var sampleRate: Int = 22050

  suspend fun loadModel(
    context: Context,
    modelPath: String,
    tokensPath: String,
    voicesPath: String = "",
  ): Boolean = withContext(Dispatchers.IO) {
    try {
      release()
      val espeakDir = File(context.getExternalFilesDir(null), "espeak-ng-data")
      if (!espeakDir.exists() || espeakDir.listFiles().isNullOrEmpty()) {
        copyAssetTree(context, "espeak-ng-data", espeakDir)
      }
      // Backfill tokens.txt or voices.bin alongside the model if missing
      val modelFile = File(modelPath)
      val tokensFile = File(tokensPath)
      if (!tokensFile.exists()) {
        val fallbackUrl = if (voicesPath.isNotEmpty()) {
          "https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0/resolve/main/tokens.txt"
        } else {
          "https://huggingface.co/csukuangfj/vits-piper-en_US-amy-medium/resolve/main/tokens.txt"
        }
        downloadFile(fallbackUrl, tokensFile)
      }
      if (voicesPath.isNotEmpty()) {
        val voicesFile = File(voicesPath)
        if (!voicesFile.exists()) {
          downloadFile(
            "https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0/resolve/main/voices.bin",
            voicesFile,
          )
        }
      }

      val config =
        getOfflineTtsConfig(
          modelDir = modelFile.parentFile?.absolutePath ?: espeakDir.absolutePath,
          modelName = modelFile.name,
          lexicon = "",
          dataDir = espeakDir.absolutePath,
          voices = if (voicesPath.isNotEmpty()) File(voicesPath).name else "",
          dictDir = "",
          vocoder = "",
        )
      val instance = OfflineTts(null, config)
      tts = instance
      sampleRate = instance.sampleRate()
      true
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to load TTS model from $modelPath", t)
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
        Log.e(TAG, "Synthesize failed", t)
        null
      }
    }

  fun release() {
    runCatching { tts?.release() }
    tts = null
  }

  private fun copyAssetTree(context: Context, assetPath: String, destDir: File) {
    val children = context.assets.list(assetPath) ?: return
    if (children.isEmpty()) {
      destDir.parentFile?.mkdirs()
      context.assets.open(assetPath).use { input ->
        FileOutputStream(destDir).use { output -> input.copyTo(output) }
      }
      return
    }
    destDir.mkdirs()
    for (child in children) {
      copyAssetTree(context, "$assetPath/$child", File(destDir, child))
    }
  }

  private fun downloadFile(urlStr: String, target: File): Boolean =
    runCatching {
      target.parentFile?.mkdirs()
      val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Box-App/1.0")
      }
      conn.connect()
      if (conn.responseCode !in 200..299) {
        conn.disconnect()
        return@runCatching false
      }
      conn.inputStream.use { input ->
        FileOutputStream(target).use { output -> input.copyTo(output) }
      }
      conn.disconnect()
      true
    }.getOrDefault(false)
}

data class TtsUiState(
  val text: String = "",
  val isSpeaking: Boolean = false,
  val speakerId: Int = 0,
  val speed: Float = 1.0f,
  val numSpeakers: Int = 1,
  val lastWavPath: String? = null,
  val errorMessage: String? = null,
)

@dagger.hilt.android.lifecycle.HiltViewModel
class TtsViewModel @Inject constructor() : ViewModel() {
  private val _uiState = MutableStateFlow(TtsUiState())
  val uiState: StateFlow<TtsUiState> = _uiState.asStateFlow()

  private var playbackJob: Job? = null

  fun onTextChange(value: String) = _uiState.update { it.copy(text = value) }
  fun onSpeakerChange(id: Int) = _uiState.update { it.copy(speakerId = id) }
  fun onSpeedChange(speed: Float) = _uiState.update { it.copy(speed = speed) }

  fun syncSpeakers(model: Model) {
    val count = (model.instance as? BoxTtsEngine)?.tts?.numSpeakers()?.coerceAtLeast(1) ?: 1
    _uiState.update { it.copy(numSpeakers = count, speakerId = it.speakerId.coerceIn(0, count - 1)) }
  }

  fun speak(context: Context, model: Model) {
    val engine = model.instance as? BoxTtsEngine ?: run {
      _uiState.update { it.copy(errorMessage = "Voice model not loaded") }
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
      val wavFile = File(context.cacheDir, "tts_last.wav")
      withContext(Dispatchers.IO) { WavUtils.writeMonoFloatWav(wavFile, samples, engine.sampleRate) }
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

class TtsTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.TTS,
      label = "Text to Speech",
      category = Category.VOICE,
      icon = Icons.Outlined.RecordVoiceOver,
      models =
        mutableListOf(
          piperModel("Amy (US English)", "en_US-amy-medium", "en_US-amy-medium.onnx", "Warm female US English voice (~63 MB). Medium-quality Piper synthesis.", 66_322_432L),
          piperModel("Ryan (US English)", "en_US-ryan-high", "en_US-ryan-high.onnx", "Clear male US English voice (~121 MB). High-quality Piper synthesis.", 126_877_696L),
          piperModel("Lessac (US English)", "en_US-lessac-medium", "en_US-lessac-medium.onnx", "Clear neutral US English voice (~63 MB). Medium-quality Piper synthesis.", 63_201_425L),
          piperModel("Cori (British English)", "en_GB-cori-high", "en_GB-cori-high.onnx", "Female British English voice (~114 MB). High-quality Piper synthesis.", 119_537_664L),
          piperModel("Alan (British English)", "en_GB-alan-low", "en_GB-alan-low.onnx", "Male British English voice (~63 MB). Fast Piper synthesis.", 63_104_662L),
          piperModel("LibriTTS-R (US English)", "en_US-libritts_r-medium", "en_US-libritts_r-medium.onnx", "116-speaker US English voice (~111 MB). Medium-quality Piper synthesis.", 116_000_000L),
          piperModel("HFC Female (US English)", "en_US-hfc_female-medium", "en_US-hfc_female-medium.onnx", "Female US English voice (~65 MB).", 65_000_000L),
          piperModel("HFC Male (US English)", "en_US-hfc_male-medium", "en_US-hfc_male-medium.onnx", "Male US English voice (~65 MB).", 65_000_000L),
          piperModel("Arctic (US English)", "en_US-arctic-medium", "en_US-arctic-medium.onnx", "18-speaker US English voice (~68 MB).", 68_000_000L),
          piperModel("Thorsten (German)", "de_DE-thorsten-high", "de_DE-thorsten-high.onnx", "Male German voice (~121 MB). High-quality Piper synthesis.", 121_000_000L),
          piperModel("UPMC (French)", "fr_FR-upmc-medium", "fr_FR-upmc-medium.onnx", "2-speaker French voice (~65 MB).", 65_000_000L),
          piperModel("MLS 10246 (Spanish)", "es_ES-mls_10246-low", "es_ES-mls_10246-low.onnx", "Spanish voice (~28 MB). Fast synthesis.", 28_000_000L),
          piperModel("Huayan (Chinese)", "zh_CN-huayan-medium", "zh_CN-huayan-medium.onnx", "Mandarin Chinese voice (~63 MB).", 63_000_000L),
          Model(
            name = "Kokoro (English)",
            info = "American & British voices via Kokoro 82M (~326 MB model + ~28 MB voices). Select voice by speaker ID.",
            url = "https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0/resolve/main/model.onnx",
            sizeInBytes = 325_560_556L,
            downloadFileName = "model.onnx",
            version = "v1_0",
            extraDataFiles =
              listOf(
                ModelDataFile("voices", "https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0/resolve/main/voices.bin", "voices.bin", 28_000_000L),
                ModelDataFile("tokens", "https://huggingface.co/csukuangfj/kokoro-multi-lang-v1_0/resolve/main/tokens.txt", "tokens.txt", 8_000L),
              ),
          ),
        ),
      description =
        "On-device text-to-speech via Sherpa-ONNX. Pick a voice and the model downloads in one tap. Audio is synthesised fully offline.",
      shortDescription = "On-device TTS",
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch {
      val engine = BoxTtsEngine()
      val isKokoro = model.name.contains("Kokoro", ignoreCase = true)
      val ok =
        engine.loadModel(
          context = context,
          modelPath = model.getPath(context, model.downloadFileName),
          tokensPath = model.getPath(context, "tokens.txt"),
          voicesPath = if (isKokoro) model.getPath(context, "voices.bin") else "",
        )
      if (ok) {
        model.instance = engine
        onDone("")
      } else {
        onDone("Failed to load TTS model")
      }
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    (model.instance as? BoxTtsEngine)?.release()
    model.instance = null
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    TtsScreen(modelManagerViewModel = myData.modelManagerViewModel)
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object TtsTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask = TtsTask()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TtsScreen(
  modelManagerViewModel: ModelManagerViewModel,
  viewModel: TtsViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val mmState by modelManagerViewModel.uiState.collectAsState()
  val model = mmState.selectedModel
  val ready = mmState.modelInitializationStatus[model.name]?.status == ModelInitializationStatusType.INITIALIZED
  val context = LocalContext.current

  if (!ready) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(
        "Download and initialize a TTS voice to speak text offline.",
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

    if (uiState.numSpeakers > 1) {
      Text("Speaker ID (#${uiState.speakerId} of ${uiState.numSpeakers})", style = MaterialTheme.typography.labelLarge)
      Slider(
        value = uiState.speakerId.toFloat(),
        onValueChange = { viewModel.onSpeakerChange(it.toInt()) },
        valueRange = 0f..(uiState.numSpeakers - 1).toFloat(),
      )
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
              WavUtils.copyToMusicExports(context, file, "tts_${System.currentTimeMillis()}.wav")
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
