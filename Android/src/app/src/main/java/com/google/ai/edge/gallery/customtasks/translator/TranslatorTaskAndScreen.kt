package com.google.ai.edge.gallery.customtasks.translator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VolumeOff
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.audio.WavUtils
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.whisper.WhisperEngine
import com.google.ai.edge.gallery.whisper.toFloat32
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume

private const val TAG = "AGTranslatorViewModel"

data class TranslatorLanguage(val label: String, val tag: String)

val TRANSLATOR_LANGUAGES = listOf(
  TranslatorLanguage("English", "en"),
  TranslatorLanguage("Spanish", "es"),
  TranslatorLanguage("French", "fr"),
  TranslatorLanguage("German", "de"),
  TranslatorLanguage("Italian", "it"),
  TranslatorLanguage("Portuguese", "pt"),
  TranslatorLanguage("Chinese (Mandarin)", "zh"),
  TranslatorLanguage("Japanese", "ja"),
  TranslatorLanguage("Korean", "ko"),
  TranslatorLanguage("Hindi", "hi"),
  TranslatorLanguage("Arabic", "ar"),
  TranslatorLanguage("Russian", "ru"),
  TranslatorLanguage("Vietnamese", "vi"),
  TranslatorLanguage("Thai", "th"),
  TranslatorLanguage("Indonesian", "id"),
  TranslatorLanguage("Turkish", "tr"),
  TranslatorLanguage("Polish", "pl"),
  TranslatorLanguage("Dutch", "nl"),
  TranslatorLanguage("Ukrainian", "uk"),
  TranslatorLanguage("Filipino", "fil"),
  TranslatorLanguage("Greek", "el"),
  TranslatorLanguage("Hebrew", "he"),
  TranslatorLanguage("Swedish", "sv"),
  TranslatorLanguage("Czech", "cs"),
)

enum class SpeakerSide { A, B }

enum class TranslatorPhase { IDLE, RECORDING, TRANSLATING }

data class TranslatorTurn(
  val id: Long,
  val side: SpeakerSide,
  val sourceLabel: String,
  val targetLabel: String,
  val targetTag: String,
  val translatedText: String = "",
  val isStreaming: Boolean = false,
  val error: String? = null,
)

data class TranslatorUiState(
  val langA: TranslatorLanguage = TRANSLATOR_LANGUAGES[0],
  val langB: TranslatorLanguage = TRANSLATOR_LANGUAGES[1],
  val phase: TranslatorPhase = TranslatorPhase.IDLE,
  val activeSide: SpeakerSide? = null,
  val turns: List<TranslatorTurn> = emptyList(),
  val hint: String = "",
  val autoMode: Boolean = false,
  val speakEnabled: Boolean = true,
)

@HiltViewModel
class TranslatorViewModel @Inject constructor() : ViewModel() {
  private val _uiState = MutableStateFlow(TranslatorUiState())
  val uiState: StateFlow<TranslatorUiState> = _uiState.asStateFlow()

  private var recordJob: Job? = null
  private val pcmBuffer = mutableListOf<Short>()
  private var nextId = 1L
  private var generation = 0
  private var tts: TextToSpeech? = null
  private var ttsReady = false
  private var pendingSpeak: Triple<String, String, SpeakerSide>? = null

  fun loadSavedLanguages(context: Context) {
    val prefs = context.getSharedPreferences("box_settings", Context.MODE_PRIVATE)
    val aTag = prefs.getString("translator_lang_a", "en") ?: "en"
    val bTag = prefs.getString("translator_lang_b", "es") ?: "es"
    val langA = TRANSLATOR_LANGUAGES.find { it.tag == aTag } ?: TRANSLATOR_LANGUAGES[0]
    val langB = TRANSLATOR_LANGUAGES.find { it.tag == bTag } ?: TRANSLATOR_LANGUAGES[1]
    _uiState.update { it.copy(langA = langA, langB = langB) }
  }

  fun setLanguage(context: Context, side: SpeakerSide, language: TranslatorLanguage) {
    _uiState.update {
      if (side == SpeakerSide.A) it.copy(langA = language) else it.copy(langB = language)
    }
    context.getSharedPreferences("box_settings", Context.MODE_PRIVATE)
      .edit()
      .putString(if (side == SpeakerSide.A) "translator_lang_a" else "translator_lang_b", language.tag)
      .apply()
  }

  fun swapLanguages(context: Context) {
    val st = _uiState.value
    setLanguage(context, SpeakerSide.A, st.langB)
    setLanguage(context, SpeakerSide.B, st.langA)
  }

  fun toggleSpeakEnabled() = _uiState.update { it.copy(speakEnabled = !it.speakEnabled) }
  fun toggleAutoMode() = _uiState.update { it.copy(autoMode = !it.autoMode) }
  fun clearTurns() = _uiState.update { it.copy(turns = emptyList(), hint = "") }

  fun findReadyAudioModel(modelManagerViewModel: ModelManagerViewModel): Model? {
    val mmState = modelManagerViewModel.uiState.value
    val candidates = mmState.tasks.flatMap { it.models }.distinctBy { it.name }
    val audioDownloaded = candidates.firstOrNull { model ->
      model.llmSupportAudio &&
        mmState.modelDownloadStatus[model.name]?.status == ModelDownloadStatusType.SUCCEEDED
    }
    return audioDownloaded ?: candidates.firstOrNull { model ->
      model.llmSupportAudio && model.instance != null
    }
  }

  fun startSpeaking(context: Context, side: SpeakerSide, modelManagerViewModel: ModelManagerViewModel) {
    if (_uiState.value.phase != TranslatorPhase.IDLE) return
    val model = findReadyAudioModel(modelManagerViewModel)
    if (model == null) {
      _uiState.update {
        it.copy(hint = "Download a Gemma audio-capable model (e.g. Gemma 3n) in Ask Audio / AI Chat first.")
      }
      return
    }
    generation++
    _uiState.update { it.copy(phase = TranslatorPhase.RECORDING, activeSide = side, hint = "") }
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
      while (_uiState.value.phase == TranslatorPhase.RECORDING) {
        val read = rec.read(chunk, 0, chunk.size)
        if (read > 0) {
          synchronized(pcmBuffer) { for (i in 0 until read) pcmBuffer.add(chunk[i]) }
        }
      }
      runCatching { rec.stop() }
      runCatching { rec.release() }
    }
  }

  fun stopAndTranslate(context: Context, modelManagerViewModel: ModelManagerViewModel) {
    val state = _uiState.value
    val side = state.activeSide ?: return
    val model = findReadyAudioModel(modelManagerViewModel) ?: run {
      _uiState.update { it.copy(phase = TranslatorPhase.IDLE, activeSide = null, hint = "No Gemma audio model installed.") }
      return
    }
    val gen = generation
    val srcLang = if (side == SpeakerSide.A) state.langA else state.langB
    val dstLang = if (side == SpeakerSide.A) state.langB else state.langA
    _uiState.update { it.copy(phase = TranslatorPhase.TRANSLATING) }

    viewModelScope.launch {
      recordJob?.join()
      val samples = synchronized(pcmBuffer) {
        val arr = pcmBuffer.toShortArray().toFloat32()
        pcmBuffer.clear()
        arr
      }
      if (samples.isEmpty()) {
        _uiState.update { it.copy(phase = TranslatorPhase.IDLE, activeSide = null, hint = "No speech captured.") }
        return@launch
      }

      val wavBytes = WavUtils.encodeMonoFloatToWavBytes(samples, WhisperEngine.SAMPLE_RATE)
      val turnId = nextId++
      _uiState.update { st ->
        st.copy(
          turns = st.turns + TranslatorTurn(
            id = turnId,
            side = side,
            sourceLabel = srcLang.label,
            targetLabel = dstLang.label,
            targetTag = dstLang.tag,
            isStreaming = true,
          )
        )
      }

      val ok = ensureModelLoaded(context, model)
      if (!ok || gen != generation) {
        updateTurn(turnId) { it.copy(isStreaming = false, error = "Failed to initialize ${model.name}") }
        _uiState.update { it.copy(phase = TranslatorPhase.IDLE, activeSide = null) }
        return@launch
      }

      val prompt =
        "You are a professional interpreter. The attached audio is someone speaking ${srcLang.label}. " +
          "Translate what they said into ${dstLang.label}. " +
          "Reply with ONLY the ${dstLang.label} translation of the speech — no explanations, no transcript, no quotation marks."
      val sb = StringBuilder()
      val helper = model.runtimeHelper
      withContext(Dispatchers.Default) {
        helper.runInference(
          model = model,
          input = prompt,
          resultListener = { partial, done, _ ->
            if (gen != generation) return@runInference
            if (partial.isNotEmpty()) sb.append(partial)
            val current = sb.toString().trim()
            updateTurn(turnId) { it.copy(translatedText = current, isStreaming = !done) }
            if (done) {
              _uiState.update { it.copy(phase = TranslatorPhase.IDLE, activeSide = null) }
              if (current.isNotEmpty() && _uiState.value.speakEnabled) {
                speakOut(context, current, dstLang, side)
              }
            }
          },
          cleanUpListener = {},
          onError = { err ->
            Log.e(TAG, "translate inference error: $err")
            updateTurn(turnId) { it.copy(isStreaming = false, error = err) }
            _uiState.update { it.copy(phase = TranslatorPhase.IDLE, activeSide = null) }
          },
          images = emptyList(),
          audioClips = listOf(wavBytes),
          coroutineScope = viewModelScope,
          extraContext = null,
        )
      }
    }
  }

  private suspend fun ensureModelLoaded(context: Context, model: Model): Boolean {
    if (model.instance != null) return true
    return suspendCancellableCoroutine { cont ->
      model.runtimeHelper.initialize(
        context = context.applicationContext,
        model = model,
        supportImage = model.llmSupportImage,
        supportAudio = model.llmSupportAudio,
        onDone = { err ->
          if (cont.isActive) cont.resume(err.isEmpty() && model.instance != null)
        },
      )
    }
  }

  private fun updateTurn(turnId: Long, transform: (TranslatorTurn) -> TranslatorTurn) {
    _uiState.update { st ->
      st.copy(turns = st.turns.map { if (it.id == turnId) transform(it) else it })
    }
  }

  private fun speakOut(context: Context, text: String, lang: TranslatorLanguage, side: SpeakerSide) {
    if (tts == null) {
      pendingSpeak = Triple(text, lang.tag, side)
      tts = TextToSpeech(context.applicationContext) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
          pendingSpeak?.let { (t, tag, _) -> speakInternal(t, tag) }
          pendingSpeak = null
        }
      }
      return
    }
    if (ttsReady) speakInternal(text, lang.tag)
  }

  private fun speakInternal(text: String, tag: String) {
    val engine = tts ?: return
    val res = engine.setLanguage(Locale.forLanguageTag(tag))
    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
      val label = TRANSLATOR_LANGUAGES.find { it.tag == tag }?.label ?: tag
      _uiState.update { it.copy(hint = "No $label voice installed — showing text only.") }
      return
    }
    engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "translator_${System.currentTimeMillis()}")
  }

  override fun onCleared() {
    generation++
    runCatching { tts?.stop(); tts?.shutdown() }
    tts = null
    super.onCleared()
  }
}

class TranslatorTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.TRANSLATOR,
      label = "Live Translator",
      category = Category.VOICE,
      icon = Icons.Outlined.Translate,
      models = mutableListOf(),
      description =
        "Two people, two languages — Box interprets both ways. Tap your button, speak, and the other person reads and hears it in their language. Powered by your installed Gemma audio model, fully offline.",
      shortDescription = "Speak across languages",
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) = onDone("")

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) = onDone()

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    TranslatorScreen(modelManagerViewModel = myData.modelManagerViewModel)
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object TranslatorTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask = TranslatorTask()
}

@Composable
fun TranslatorScreen(
  modelManagerViewModel: ModelManagerViewModel,
  viewModel: TranslatorViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val context = LocalContext.current

  androidx.compose.runtime.LaunchedEffect(Unit) {
    viewModel.loadSavedLanguages(context)
  }

  var hasPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  val permLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }

  Column(
    modifier = Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Top bar: Language A <-> Language B + controls
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      LanguagePickerButton(
        language = uiState.langA,
        onSelect = { viewModel.setLanguage(context, SpeakerSide.A, it) },
        modifier = Modifier.weight(1f),
      )
      IconButton(onClick = { viewModel.swapLanguages(context) }) {
        Icon(Icons.Outlined.SwapHoriz, contentDescription = "Swap languages")
      }
      LanguagePickerButton(
        language = uiState.langB,
        onSelect = { viewModel.setLanguage(context, SpeakerSide.B, it) },
        modifier = Modifier.weight(1f),
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
          selected = uiState.speakEnabled,
          onClick = viewModel::toggleSpeakEnabled,
          leadingIcon = {
            Icon(
              if (uiState.speakEnabled) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
          },
          label = { Text("Speak out") },
        )
      }
      if (uiState.turns.isNotEmpty()) {
        IconButton(onClick = viewModel::clearTurns) {
          Icon(Icons.Outlined.Delete, contentDescription = "Clear conversation")
        }
      }
    }

    if (uiState.hint.isNotEmpty()) {
      Text(uiState.hint, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    LazyColumn(
      modifier = Modifier.weight(1f).fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(uiState.turns, key = { it.id }) { turn ->
        OutlinedCard(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
              "${turn.sourceLabel} → ${turn.targetLabel}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.SemiBold,
            )
            if (turn.error != null) {
              Text(turn.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } else if (turn.translatedText.isEmpty() && turn.isStreaming) {
              CircularProgressIndicator(Modifier.size(20.dp))
            } else {
              Text(turn.translatedText, style = MaterialTheme.typography.bodyLarge)
            }
          }
        }
      }
    }

    // Bottom dual push-to-talk row
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      SpeakerButton(
        label = uiState.langA.label,
        isActive = uiState.activeSide == SpeakerSide.A,
        phase = uiState.phase,
        hasPermission = hasPermission,
        onGrantPermission = { permLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        onStart = { viewModel.startSpeaking(context, SpeakerSide.A, modelManagerViewModel) },
        onStop = { viewModel.stopAndTranslate(context, modelManagerViewModel) },
        modifier = Modifier.weight(1f),
      )
      SpeakerButton(
        label = uiState.langB.label,
        isActive = uiState.activeSide == SpeakerSide.B,
        phase = uiState.phase,
        hasPermission = hasPermission,
        onGrantPermission = { permLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        onStart = { viewModel.startSpeaking(context, SpeakerSide.B, modelManagerViewModel) },
        onStop = { viewModel.stopAndTranslate(context, modelManagerViewModel) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun LanguagePickerButton(
  language: TranslatorLanguage,
  onSelect: (TranslatorLanguage) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }
  androidx.compose.foundation.layout.Box(modifier) {
    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
      Text(language.label, maxLines = 1)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
      TRANSLATOR_LANGUAGES.forEach { item ->
        DropdownMenuItem(
          text = { Text(item.label) },
          onClick = {
            onSelect(item)
            expanded = false
          },
        )
      }
    }
  }
}

@Composable
private fun SpeakerButton(
  label: String,
  isActive: Boolean,
  phase: TranslatorPhase,
  hasPermission: Boolean,
  onGrantPermission: () -> Unit,
  onStart: () -> Unit,
  onStop: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isRecordingHere = phase == TranslatorPhase.RECORDING && isActive
  val isTranslatingHere = phase == TranslatorPhase.TRANSLATING && isActive
  Button(
    onClick = {
      if (!hasPermission) onGrantPermission()
      else if (isRecordingHere) onStop()
      else onStart()
    },
    enabled = phase == TranslatorPhase.IDLE || isRecordingHere,
    colors = if (isRecordingHere) {
      ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
    } else {
      ButtonDefaults.buttonColors()
    },
    modifier = modifier,
  ) {
    when {
      isRecordingHere -> {
        Icon(Icons.Outlined.Stop, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("Stop ($label)", maxLines = 1)
      }
      isTranslatingHere -> {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(6.dp))
        Text("Translating…", maxLines = 1)
      }
      else -> {
        Icon(Icons.Outlined.Mic, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
      }
    }
  }
}
