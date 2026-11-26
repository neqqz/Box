package com.google.ai.edge.gallery.customtasks.sensevoice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDataFile
import com.google.ai.edge.gallery.data.Task
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

class SenseVoiceTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.SENSE_VOICE,
      label = "SenseVoice",
      category = Category.VOICE,
      icon = Icons.Outlined.GraphicEq,
      models =
        mutableListOf(
          Model(
            name = "SenseVoice (multilingual)",
            info =
              "Turns speech into text, in Chinese, English, Japanese, Korean and Cantonese. Around five times faster than Whisper (~234 MB).",
            url =
              "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx?download=true",
            sizeInBytes = 234_000_000L,
            downloadFileName = "model.int8.onnx",
            version = "2024-07-17",
            extraDataFiles =
              listOf(
                ModelDataFile(
                  name = "tokens",
                  url =
                    "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt?download=true",
                  downloadFileName = "tokens.txt",
                  sizeInBytes = 309_000L,
                )
              ),
          )
        ),
      description =
        "Fast multilingual speech-to-text using SenseVoice (Chinese, English, Japanese, Korean, Cantonese). Roughly 5x faster than Whisper and fully offline.",
      shortDescription = "Fast multilingual speech-to-text (~5x Whisper)",
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch {
      val engine = SenseVoiceEngine()
      val ok =
        engine.loadModel(
          modelPath = model.getPath(context, model.downloadFileName),
          tokensPath = model.getPath(context, "tokens.txt"),
        )
      if (ok) {
        model.instance = engine
        onDone("")
      } else {
        onDone("Failed to load SenseVoice model")
      }
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    (model.instance as? SenseVoiceEngine)?.release()
    model.instance = null
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    SenseVoiceScreen(modelManagerViewModel = myData.modelManagerViewModel)
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object SenseVoiceTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask = SenseVoiceTask()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SenseVoiceScreen(
  modelManagerViewModel: com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel,
  viewModel: SenseVoiceViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val mmState by modelManagerViewModel.uiState.collectAsState()
  val selectedModel = mmState.selectedModel
  val initStatus = mmState.modelInitializationStatus[selectedModel.name]
  val modelReady =
    initStatus?.status ==
      com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType.INITIALIZED
  val context = LocalContext.current
  val clipboard = LocalClipboardManager.current

  var hasPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  val permLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      hasPermission = it
    }
  val filePicker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) viewModel.transcribeUri(context, uri, selectedModel)
    }

  Column(
    modifier = Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (!modelReady) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
          "Download and initialize SenseVoice to transcribe speech.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
      return
    }

    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      SENSE_VOICE_LANGUAGES.forEach { (code, label) ->
        FilterChip(
          selected = uiState.language == code,
          onClick = { viewModel.setLanguage(context, selectedModel, code) },
          label = { Text(label) },
        )
      }
    }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(
        selected = uiState.useItn,
        onClick = { viewModel.toggleItn(context, selectedModel) },
        label = { Text("Punctuation (ITN)") },
      )
      FilterChip(
        selected = uiState.showEmotion,
        onClick = { viewModel.toggleShowEmotion() },
        label = { Text("Emotion") },
      )
      FilterChip(
        selected = uiState.showEvents,
        onClick = { viewModel.toggleShowEvents() },
        label = { Text("Events") },
      )
    }

    when {
      uiState.isTranscribing || uiState.isApplyingSettings -> {
        CircularProgressIndicator(Modifier.size(64.dp))
        Text(
          if (uiState.isApplyingSettings) "Applying settings…" else "Transcribing…",
          style = MaterialTheme.typography.bodyMedium,
        )
      }
      uiState.isRecording -> {
        Button(
          onClick = { viewModel.stopRecordingAndTranscribe(selectedModel) },
          modifier = Modifier.size(72.dp),
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
          Icon(Icons.Outlined.Stop, contentDescription = "Stop", modifier = Modifier.size(32.dp))
        }
        Text("Recording… tap to stop", color = MaterialTheme.colorScheme.error)
      }
      else -> {
        Button(
          onClick = {
            if (!hasPermission) permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            else viewModel.startRecording()
          },
          modifier = Modifier.size(72.dp),
        ) {
          Icon(Icons.Outlined.Mic, contentDescription = "Record", modifier = Modifier.size(32.dp))
        }
        OutlinedButton(onClick = { filePicker.launch(arrayOf("audio/*")) }) {
          Icon(Icons.Outlined.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text("Import audio file")
        }
      }
    }

    uiState.errorMessage?.let {
      Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    if (uiState.transcripts.isNotEmpty()) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("Transcripts", style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = { viewModel.clearTranscripts() }) {
          Icon(Icons.Outlined.Delete, contentDescription = "Clear all")
        }
      }
      LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(uiState.transcripts) { item ->
          OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                  if (item.language.isNotEmpty()) {
                    AssistChip(onClick = {}, label = { Text(item.language.uppercase()) })
                  }
                  if (uiState.showEmotion && item.emotion.isNotEmpty()) {
                    AssistChip(onClick = {}, label = { Text(item.emotion) })
                  }
                  if (uiState.showEvents && item.event.isNotEmpty()) {
                    AssistChip(onClick = {}, label = { Text(item.event) })
                  }
                }
                IconButton(onClick = { clipboard.setText(AnnotatedString(item.text)) }) {
                  Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
                }
              }
              Text(item.text, style = MaterialTheme.typography.bodyMedium)
            }
          }
        }
      }
    } else {
      Spacer(Modifier.height(8.dp))
    }
  }
}
