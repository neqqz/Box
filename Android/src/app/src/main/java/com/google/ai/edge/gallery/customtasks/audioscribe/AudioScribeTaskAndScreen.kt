package com.google.ai.edge.gallery.customtasks.audioscribe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.audio.AudioDecoder
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.whisper.WhisperEngine
import com.google.ai.edge.gallery.whisper.toFloat32
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
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
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val TAG = "AudioScribe"
private const val SEG_ARCHIVE_URL =
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-reverb-diarization-v1.tar.bz2"
private const val EMB_MODEL_URL =
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"

private val SCRIBE_WHISPER_LANGUAGES = listOf("auto" to "Auto", "en" to "EN", "ru" to "RU", "es" to "ES", "fr" to "FR", "de" to "DE", "zh" to "ZH", "ja" to "JA")

data class ScribeSegment(
  val startMs: Long,
  val endMs: Long,
  val speakerId: String,
  val text: String,
)

enum class ScribePhase {
  IDLE,
  RECORDING,
  TRANSCRIBING,
  DOWNLOADING_DIARIZATION,
  DIARIZING,
  DONE,
  ERROR,
}

data class AudioScribeUiState(
  val phase: ScribePhase = ScribePhase.IDLE,
  val language: String = "auto",
  val dialogueMode: Boolean = true,
  val segments: List<ScribeSegment> = emptyList(),
  val speakerNames: Map<String, String> = emptyMap(),
  val formattedTranscript: String = "",
  val downloadProgress: Float = 0f,
  val statusMessage: String = "",
  val errorMessage: String? = null,
)

@HiltViewModel
class AudioScribeViewModel @Inject constructor() : ViewModel() {
  private val _uiState = MutableStateFlow(AudioScribeUiState())
  val uiState: StateFlow<AudioScribeUiState> = _uiState.asStateFlow()

  private var recordJob: Job? = null
  private val pcmBuffer = mutableListOf<Short>()

  fun setLanguage(lang: String) = _uiState.update { it.copy(language = lang) }
  fun setDialogueMode(enabled: Boolean) = _uiState.update { it.copy(dialogueMode = enabled) }

  fun renameSpeaker(speakerId: String, newName: String) {
    val clean = newName.trim().ifEmpty { speakerId }
    _uiState.update { st ->
      val updatedMap = st.speakerNames + (speakerId to clean)
      st.copy(
        speakerNames = updatedMap,
        formattedTranscript = formatTranscript(st.segments, updatedMap),
      )
    }
  }

  fun clear() {
    _uiState.update {
      it.copy(
        phase = ScribePhase.IDLE,
        segments = emptyList(),
        speakerNames = emptyMap(),
        formattedTranscript = "",
        statusMessage = "",
        errorMessage = null,
      )
    }
  }

  fun startRecording() {
    val ph = _uiState.value.phase
    if (ph == ScribePhase.RECORDING || ph == ScribePhase.TRANSCRIBING || ph == ScribePhase.DIARIZING) return
    _uiState.update { it.copy(phase = ScribePhase.RECORDING, errorMessage = null, statusMessage = "") }
    pcmBuffer.clear()
    recordJob = viewModelScope.launch(Dispatchers.IO) {
      val minBuf = AudioRecord.getMinBufferSize(
        WhisperEngine.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
      ).coerceAtLeast(4096)
      val rec = AudioRecord(
        MediaRecorder.AudioSource.MIC,
        WhisperEngine.SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        minBuf,
      )
      val chunk = ShortArray(minBuf / 2)
      rec.startRecording()
      while (_uiState.value.phase == ScribePhase.RECORDING) {
        val read = rec.read(chunk, 0, chunk.size)
        if (read > 0) {
          synchronized(pcmBuffer) { for (i in 0 until read) pcmBuffer.add(chunk[i]) }
        }
      }
      runCatching { rec.stop() }
      runCatching { rec.release() }
    }
  }

  fun stopRecordingAndTranscribe(context: Context, model: Model) {
    _uiState.update { it.copy(phase = ScribePhase.TRANSCRIBING, statusMessage = "Transcribing…") }
    viewModelScope.launch {
      recordJob?.join()
      val samples = synchronized(pcmBuffer) {
        val out = pcmBuffer.toShortArray().toFloat32()
        pcmBuffer.clear()
        out
      }
      processAudio(context, model, samples, "No audio recorded")
    }
  }

  fun transcribeUri(context: Context, uri: Uri, model: Model) {
    _uiState.update { it.copy(phase = ScribePhase.TRANSCRIBING, statusMessage = "Decoding audio…", errorMessage = null) }
    viewModelScope.launch {
      val samples = AudioDecoder.decodeTo16kMonoFloat(context, uri)
      processAudio(context, model, samples, "Could not decode audio file")
    }
  }

  private suspend fun processAudio(
    context: Context,
    model: Model,
    samples: FloatArray,
    emptyError: String,
  ) {
    if (samples.isEmpty()) {
      _uiState.update { it.copy(phase = ScribePhase.ERROR, errorMessage = emptyError) }
      return
    }
    val engine = model.instance as? WhisperEngine ?: run {
      _uiState.update { it.copy(phase = ScribePhase.ERROR, errorMessage = "Whisper model not loaded") }
      return
    }

    _uiState.update { it.copy(phase = ScribePhase.TRANSCRIBING, statusMessage = "Transcribing…") }
    val rawSegments = engine.transcribeWithSegments(samples, _uiState.value.language)
    val nonEmpty = rawSegments.filter { it.text.isNotBlank() }
    if (nonEmpty.isEmpty()) {
      _uiState.update { it.copy(phase = ScribePhase.ERROR, errorMessage = "No speech recognized") }
      return
    }

    if (!_uiState.value.dialogueMode) {
      val mapped = nonEmpty.map { ScribeSegment(it.startMs, it.endMs, "Speaker", it.text.trim()) }
      val names = mapOf("Speaker" to "Speaker")
      _uiState.update {
        it.copy(
          phase = ScribePhase.DONE,
          segments = mapped,
          speakerNames = names,
          formattedTranscript = formatTranscript(mapped, names),
          statusMessage = "",
        )
      }
      return
    }

    val modelDir = File(context.getExternalFilesDir(null), "__diar_models")
    val segFile = File(modelDir, "seg.onnx")
    val embFile = File(modelDir, "emb.onnx")

    if (!segFile.exists() || !embFile.exists()) {
      _uiState.update {
        it.copy(
          phase = ScribePhase.DOWNLOADING_DIARIZATION,
          downloadProgress = 0f,
          statusMessage = "Downloading diarization models… 0%",
        )
      }
      val ok = ensureDiarizationModels(modelDir, segFile, embFile) { p ->
        _uiState.update {
          it.copy(
            downloadProgress = p,
            statusMessage = "Downloading diarization models… ${(p * 100).toInt()}%",
          )
        }
      }
      if (!ok) {
        _uiState.update { it.copy(phase = ScribePhase.ERROR, errorMessage = "Failed to download diarization models") }
        return
      }
    }

    _uiState.update { it.copy(phase = ScribePhase.DIARIZING, statusMessage = "Identifying speakers…") }
    val diarSegments = runDiarization(segFile.absolutePath, embFile.absolutePath, samples)

    val merged = nonEmpty.map { ws ->
      val speakerLabel = if (diarSegments.isEmpty()) {
        "SPEAKER_00"
      } else {
        assignSpeaker(ws.startMs / 1000f, ws.endMs / 1000f, diarSegments)
      }
      ScribeSegment(ws.startMs, ws.endMs, speakerLabel, ws.text.trim())
    }
    val names = merged.map { it.speakerId }.distinct().associateWith { it }
    _uiState.update {
      it.copy(
        phase = ScribePhase.DONE,
        segments = merged,
        speakerNames = names,
        formattedTranscript = formatTranscript(merged, names),
        statusMessage = "",
      )
    }
  }

  private fun assignSpeaker(
    startSec: Float,
    endSec: Float,
    diarSegments: Array<OfflineSpeakerDiarizationSegment>,
  ): String {
    val overlapBySpeaker = LinkedHashMap<Int, Float>()
    for (ds in diarSegments) {
      val overlap = max(0f, min(endSec, ds.end) - max(startSec, ds.start))
      if (overlap > 0f) {
        overlapBySpeaker[ds.speaker] = (overlapBySpeaker[ds.speaker] ?: 0f) + overlap
      }
    }
    val chosen = if (overlapBySpeaker.isNotEmpty()) {
      overlapBySpeaker.maxBy { it.value }.key
    } else {
      val mid = (startSec + endSec) / 2f
      diarSegments.minByOrNull { abs((it.start + it.end) / 2f - mid) }?.speaker ?: 0
    }
    return "SPEAKER_%02d".format(chosen)
  }

  private suspend fun runDiarization(
    segPath: String,
    embPath: String,
    samples: FloatArray,
  ): Array<OfflineSpeakerDiarizationSegment> = withContext(Dispatchers.Default) {
    try {
      val config = OfflineSpeakerDiarizationConfig(
        segmentation = OfflineSpeakerSegmentationModelConfig(
          pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segPath),
          numThreads = 2,
          provider = "cpu",
        ),
        embedding = SpeakerEmbeddingExtractorConfig(
          model = embPath,
          numThreads = 2,
          provider = "cpu",
        ),
        clustering = FastClusteringConfig(numClusters = -1, threshold = 0.5f),
      )
      val diar = OfflineSpeakerDiarization(null, config)
      val out = diar.process(samples)
      diar.release()
      out
    } catch (t: Throwable) {
      Log.e(TAG, "Diarization failed", t)
      emptyArray()
    }
  }

  private suspend fun ensureDiarizationModels(
    dir: File,
    segFile: File,
    embFile: File,
    onProgress: (Float) -> Unit,
  ): Boolean = withContext(Dispatchers.IO) {
    dir.mkdirs()
    if (!segFile.exists()) {
      val tarFile = File(dir, "seg.tar.bz2")
      val downloaded = downloadFile(SEG_ARCHIVE_URL, tarFile) { onProgress(it * 0.5f) }
      if (!downloaded) return@withContext false
      val extracted = extractOnnxFromTarBz2(tarFile, segFile)
      tarFile.delete()
      if (!extracted) return@withContext false
    }
    if (!embFile.exists()) {
      val downloaded = downloadFile(EMB_MODEL_URL, embFile) { onProgress(0.5f + it * 0.5f) }
      if (!downloaded) return@withContext false
    }
    segFile.exists() && embFile.exists()
  }

  private fun downloadFile(urlStr: String, target: File, onProgress: (Float) -> Unit): Boolean =
    runCatching {
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
      val total = conn.contentLengthLong.coerceAtLeast(1L)
      conn.inputStream.use { input ->
        FileOutputStream(target).use { output ->
          val buf = ByteArray(65536)
          var readTotal = 0L
          while (true) {
            val n = input.read(buf)
            if (n == -1) break
            output.write(buf, 0, n)
            readTotal += n
            onProgress((readTotal.toFloat() / total.toFloat()).coerceIn(0f, 1f))
          }
        }
      }
      conn.disconnect()
      true
    }.getOrElse {
      Log.e(TAG, "Download failed: $urlStr", it)
      target.delete()
      false
    }

  private fun extractOnnxFromTarBz2(tarBz2File: File, destOnnx: File): Boolean =
    runCatching {
      FileInputStream(tarBz2File).use { fis ->
        BufferedInputStream(fis).use { bis ->
          BZip2CompressorInputStream(bis).use { bzip ->
            TarArchiveInputStream(bzip).use { tar ->
              var entry = tar.nextEntry
              while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith(".onnx")) {
                  FileOutputStream(destOnnx).use { out -> tar.copyTo(out) }
                  return@runCatching true
                }
                entry = tar.nextEntry
              }
              false
            }
          }
        }
      }
    }.getOrDefault(false)

  private fun formatTranscript(segments: List<ScribeSegment>, names: Map<String, String>): String {
    val grouped = mutableListOf<Pair<String, MutableList<String>>>()
    for (seg in segments) {
      val txt = seg.text.trim()
      if (txt.isEmpty()) continue
      val display = names[seg.speakerId] ?: seg.speakerId
      if (grouped.isNotEmpty() && grouped.last().first == display) {
        grouped.last().second.add(txt)
      } else {
        grouped.add(display to mutableListOf(txt))
      }
    }
    return grouped.joinToString("\n\n") { (spk, lines) -> "$spk:\n${lines.joinToString(" ")}" }
  }
}

private fun whisperScribeModel(name: String, file: String, info: String, bytes: Long) =
  Model(
    name = name,
    info = info,
    url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file?download=true",
    sizeInBytes = bytes,
    downloadFileName = file,
    version = "main",
  )

class AudioScribeTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.AUDIO_SCRIBE,
      label = "Whisper Scribe",
      category = Category.VOICE,
      icon = Icons.Outlined.Subtitles,
      models =
        mutableListOf(
          whisperScribeModel("Whisper Tiny", "ggml-tiny-q5_1.bin", "Fastest multilingual (~32 MB).", 32_200_000L),
          whisperScribeModel("Whisper Tiny (English-only)", "ggml-tiny.en-q5_1.bin", "Fastest English (~32 MB).", 31_700_000L),
          whisperScribeModel("Whisper Base", "ggml-base-q5_1.bin", "Fast multilingual (~63 MB).", 62_607_974L),
          whisperScribeModel("Whisper Base (English-only)", "ggml-base.en-q5_1.bin", "Fast English (~58 MB).", 57_800_000L),
          whisperScribeModel("Whisper Small", "ggml-small-q5_1.bin", "Better multilingual accuracy (~199 MB).", 199_229_440L),
          whisperScribeModel("Whisper Small (English-only)", "ggml-small.en-q8_0.bin", "Better English accuracy (~190 MB).", 190_000_000L),
          whisperScribeModel("Whisper Medium", "ggml-medium-q5_0.bin", "High accuracy (~539 MB).", 539_000_000L),
          whisperScribeModel("Whisper Medium (English-only)", "ggml-medium.en-q5_0.bin", "High English accuracy (~514 MB).", 514_000_000L),
          whisperScribeModel("Whisper Large-v3-Turbo", "ggml-large-v3-turbo-q5_0.bin", "Near large-v3 accuracy (~574 MB).", 574_000_000L),
          whisperScribeModel("Whisper Large-v3", "ggml-large-v3.bin", "Highest accuracy (~3.1 GB).", 3_094_000_000L),
        ),
      description =
        "Record or import audio, transcribe with Whisper, and optionally identify speakers (diarization). Dialogue mode automatically downloads the Sherpa-ONNX speaker models (~60 MB) on first use.",
      shortDescription = "Transcribe + speaker diarization",
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch {
      val engine = WhisperEngine()
      val ok = engine.loadModel(model.getPath(context, model.downloadFileName))
      if (ok) {
        model.instance = engine
        onDone("")
      } else {
        onDone("Failed to load Whisper model")
      }
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    (model.instance as? WhisperEngine)?.freeModel()
    model.instance = null
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    AudioScribeScreen(modelManagerViewModel = myData.modelManagerViewModel)
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object AudioScribeTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask = AudioScribeTask()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioScribeScreen(
  modelManagerViewModel: ModelManagerViewModel,
  viewModel: AudioScribeViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val mmState by modelManagerViewModel.uiState.collectAsState()
  val model = mmState.selectedModel
  val ready = mmState.modelInitializationStatus[model.name]?.status == ModelInitializationStatusType.INITIALIZED
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current

  var hasPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  val permLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
  val filePicker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) viewModel.transcribeUri(context, uri, model)
    }

  var editingSpeakerId by remember { mutableStateOf<String?>(null) }
  var editingSpeakerDraft by remember { mutableStateOf("") }

  if (!ready) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(
        "Download and initialize a Whisper model to start Whisper Scribe.",
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    return
  }

  Column(
    modifier = Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      SCRIBE_WHISPER_LANGUAGES.forEach { (code, label) ->
        FilterChip(
          selected = uiState.language == code,
          onClick = { viewModel.setLanguage(code) },
          label = { Text(label) },
        )
      }
    }

    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column {
        Text("Dialogue Mode (Speaker Diarization)", style = MaterialTheme.typography.titleSmall)
        Text("Identifies who is speaking (~60 MB first download)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Switch(checked = uiState.dialogueMode, onCheckedChange = viewModel::setDialogueMode)
    }

    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      when (uiState.phase) {
        ScribePhase.TRANSCRIBING, ScribePhase.DIARIZING -> {
          CircularProgressIndicator(Modifier.size(32.dp))
          Text(uiState.statusMessage, style = MaterialTheme.typography.bodyMedium)
        }
        ScribePhase.DOWNLOADING_DIARIZATION -> {
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(uiState.statusMessage, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { uiState.downloadProgress }, modifier = Modifier.fillMaxWidth())
          }
        }
        ScribePhase.RECORDING -> {
          Button(
            onClick = { viewModel.stopRecordingAndTranscribe(context, model) },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          ) {
            Icon(Icons.Outlined.Stop, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Stop recording")
          }
        }
        else -> {
          Button(
            onClick = {
              if (!hasPermission) permLauncher.launch(Manifest.permission.RECORD_AUDIO)
              else viewModel.startRecording()
            }
          ) {
            Icon(Icons.Outlined.Mic, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (!hasPermission) "Grant mic" else "Record")
          }
          OutlinedButton(onClick = { filePicker.launch(arrayOf("audio/*")) }) {
            Icon(Icons.Outlined.AudioFile, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Import file")
          }
        }
      }
    }

    uiState.errorMessage?.let {
      Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    if (uiState.segments.isNotEmpty()) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
          uiState.speakerNames.forEach { (id, name) ->
            AssistChip(
              onClick = {
                editingSpeakerId = id
                editingSpeakerDraft = name
              },
              label = { Text(name) },
              leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
          }
        }
        Row {
          IconButton(onClick = { clipboard.setText(AnnotatedString(uiState.formattedTranscript)) }) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy transcript")
          }
          IconButton(onClick = viewModel::clear) {
            Icon(Icons.Outlined.Delete, contentDescription = "Clear")
          }
        }
      }

      LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(uiState.segments) { seg ->
          val displaySpeaker = uiState.speakerNames[seg.speakerId] ?: seg.speakerId
          OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(displaySpeaker, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Text(
                  "%02d:%02d".format(seg.startMs / 60000L, (seg.startMs / 1000L) % 60L),
                  style = MaterialTheme.typography.labelSmall,
                  fontFamily = FontFamily.Monospace,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Text(seg.text, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
      }
    }
  }

  editingSpeakerId?.let { id ->
    AlertDialog(
      onDismissRequest = { editingSpeakerId = null },
      title = { Text("Rename $id") },
      text = {
        OutlinedTextField(
          value = editingSpeakerDraft,
          onValueChange = { editingSpeakerDraft = it },
          label = { Text("Speaker name") },
          singleLine = true,
        )
      },
      confirmButton = {
        TextButton(onClick = {
          viewModel.renameSpeaker(id, editingSpeakerDraft)
          editingSpeakerId = null
        }) { Text("Save") }
      },
      dismissButton = {
        TextButton(onClick = { editingSpeakerId = null }) { Text("Cancel") }
      },
    )
  }
}
