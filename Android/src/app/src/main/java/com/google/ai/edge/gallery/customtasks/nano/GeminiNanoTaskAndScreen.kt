package com.google.ai.edge.gallery.customtasks.nano

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.Summarize
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.local.ChatRepository
import com.google.ai.edge.gallery.data.local.entities.Conversation
import com.google.ai.edge.gallery.ui.common.MarkdownText
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.StreamingCallback
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.imagedescription.ImageDescriberOptions
import com.google.mlkit.genai.imagedescription.ImageDescription
import com.google.mlkit.genai.imagedescription.ImageDescriptionRequest
import com.google.mlkit.genai.imagedescription.ImageDescriptionResult
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreadingRequest
import com.google.mlkit.genai.proofreading.ProofreadingResult
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.rewriting.RewriterOptions
import com.google.mlkit.genai.rewriting.Rewriting
import com.google.mlkit.genai.rewriting.RewritingRequest
import com.google.mlkit.genai.rewriting.RewritingResult
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.SummarizationResult
import com.google.mlkit.genai.summarization.SummarizerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.Locale
import java.util.concurrent.ExecutionException
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

private const val TAG = "GeminiNanoHub"

private enum class NanoTool(
  val title: String,
  val subtitle: String,
  val icon: ImageVector,
  val requiresAiCore: Boolean,
) {
  SUMMARIZE("Summarize", "Condense articles & conversations into bullet points", Icons.Outlined.Summarize, true),
  PROOFREAD("Proofread", "Fix grammar and spelling errors", Icons.Outlined.Spellcheck, true),
  REWRITE("Rewrite", "Rephrase text in different tones or styles", Icons.Outlined.EditNote, true),
  DESCRIBE("Describe Image", "Generate a description of a photo", Icons.Outlined.Image, true),
  PROMPT("Custom Prompt", "Send a custom prompt to Gemini Nano", Icons.Outlined.AutoAwesome, true),
  CHAT("Nano Chat", "Multi-turn conversation with Gemini Nano via NPU/TPU", Icons.Outlined.ChatBubbleOutline, true),
  SPEECH("Speech → Text", "Transcribe speech using on-device model", Icons.Outlined.Mic, true),
  SCAN_TEXT("Scan Text", "Extract text from an image (OCR)", Icons.Outlined.DocumentScanner, false),
  SCAN_BARCODE("Scan Barcode", "Decode QR codes & barcodes", Icons.Outlined.QrCodeScanner, false),
  LABEL_IMAGE("Label Image", "Identify objects and scenes in a photo", Icons.Outlined.Label, false),
  DETECT_FACE("Detect Faces", "Find and analyse faces in an image", Icons.Outlined.Face, false),
  POSE("Pose Detection", "Track body pose landmarks", Icons.Outlined.AccessibilityNew, false),
  FACE_MESH("Face Mesh", "468-point face mesh overlay", Icons.Outlined.Grain, false),
}

data class NanoChatTurn(val text: String, val isUser: Boolean)

@HiltViewModel
class NanoChatViewModel @Inject constructor(
  private val chatRepository: ChatRepository,
) : ViewModel() {
  private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
  val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

  private val _activeConversation = MutableStateFlow<Conversation?>(null)
  val activeConversation: StateFlow<Conversation?> = _activeConversation.asStateFlow()

  val messages = mutableStateListOf<NanoChatTurn>()

  init {
    viewModelScope.launch {
      chatRepository.getConversationsByTask(BuiltInTaskId.GEMINI_NANO).collect { list ->
        _conversations.value = list
        if (_activeConversation.value == null && list.isNotEmpty()) {
          selectConversation(list.first())
        }
      }
    }
  }

  fun createNewChat() {
    viewModelScope.launch {
      val conv = chatRepository.createConversation(
        title = "Nano Chat",
        taskType = BuiltInTaskId.GEMINI_NANO,
        modelName = "Gemini Nano",
      )
      _activeConversation.value = conv
      messages.clear()
    }
  }

  fun selectConversation(conv: Conversation) {
    _activeConversation.value = conv
    viewModelScope.launch {
      val loaded = chatRepository.getMessagesSync(conv.id)
      messages.clear()
      loaded.forEach { msg ->
        messages.add(NanoChatTurn(text = msg.content, isUser = msg.role == "user"))
      }
    }
  }

  fun persistTurn(text: String, isUser: Boolean) {
    viewModelScope.launch {
      val conv = _activeConversation.value ?: chatRepository.createConversation(
        title = if (isUser) text.take(40).ifBlank { "Nano Chat" } else "Nano Chat",
        taskType = BuiltInTaskId.GEMINI_NANO,
        modelName = "Gemini Nano",
      ).also { _activeConversation.value = it }

      chatRepository.saveMessage(
        conversationId = conv.id,
        role = if (isUser) "user" else "assistant",
        content = text,
      )
      if (isUser && conv.title == "Nano Chat" && text.isNotBlank()) {
        val updated = conv.copy(title = text.take(48), updatedAt = System.currentTimeMillis())
        chatRepository.updateConversation(updated)
        _activeConversation.value = updated
      }
    }
  }

  fun deleteConversation(conv: Conversation) {
    viewModelScope.launch {
      val wasActive = _activeConversation.value?.id == conv.id
      chatRepository.deleteConversation(conv)
      if (wasActive) {
        val next = _conversations.value.firstOrNull { it.id != conv.id }
        _activeConversation.value = next
        messages.clear()
        if (next != null) {
          val loaded = chatRepository.getMessagesSync(next.id)
          loaded.forEach { msg ->
            messages.add(NanoChatTurn(text = msg.content, isUser = msg.role == "user"))
          }
        }
      }
    }
  }
}

private suspend fun <T> ListenableFuture<T>.awaitListenable(): T =
  suspendCancellableCoroutine { cont ->
    addListener(
      {
        if (isCancelled) {
          cont.cancel()
        } else {
          try {
            cont.resume(get())
          } catch (e: ExecutionException) {
            cont.resumeWithException(e.cause ?: e)
          } catch (t: Throwable) {
            cont.resumeWithException(t)
          }
        }
      },
      Runnable::run,
    )
    cont.invokeOnCancellation { cancel(false) }
  }

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitPlayTask(): T =
  suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> cont.resume(result) }
    addOnFailureListener { exc -> cont.resumeWithException(exc) }
    addOnCanceledListener { cont.cancel() }
  }

private fun decodeBitmapUri(context: Context, uri: Uri): Bitmap? =
  try {
    context.contentResolver.openInputStream(uri)?.use { stream ->
      val raw = BitmapFactory.decodeStream(stream) ?: return null
      val maxDim = maxOf(raw.width, raw.height)
      if (maxDim <= 1280) {
        raw
      } else {
        val scale = 1280f / maxDim
        Bitmap.createScaledBitmap(
          raw,
          (raw.width * scale).toInt().coerceAtLeast(1),
          (raw.height * scale).toInt().coerceAtLeast(1),
          true,
        )
      }
    }
  } catch (t: Throwable) {
    Log.w(TAG, "Failed to decode bitmap URI: $uri", t)
    null
  }

private fun imageProxyToBitmap(proxy: ImageProxy): Bitmap {
  val bmp = proxy.toBitmap()
  val rot = proxy.imageInfo.rotationDegrees
  if (rot == 0) return bmp
  val m = Matrix().apply { postRotate(rot.toFloat()) }
  return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}

private fun copyToClipboard(context: Context, label: String, text: String) {
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
  cm?.setPrimaryClip(ClipData.newPlainText(label, text))
}

class GeminiNanoTask @Inject constructor() : CustomTask {
  override val task: Task = Task(
    id = BuiltInTaskId.GEMINI_NANO,
    label = "Nano",
    category = Category.LLM,
    icon = Icons.Outlined.AutoAwesome,
    models = mutableListOf(),
    description =
      "On-device AI powered by ML Kit, running on the device's NPU/TPU — no internet required. " +
        "GenAI features (Summarize, Proofread, Rewrite, Chat, Describe, Prompt, Speech) use Gemini Nano " +
        "via AICore. Vision tools (Text, Barcode, Labels, Face, Pose, Face Mesh) work on all devices.",
    shortDescription = "ML Kit · NPU-accelerated on-device AI",
  )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: com.google.ai.edge.gallery.data.Model,
    onDone: (String) -> Unit,
  ) {
    onDone("")
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: com.google.ai.edge.gallery.data.Model,
    onDone: () -> Unit,
  ) {
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val taskData = data as CustomTaskData
    GeminiNanoHubScreen(bottomPadding = taskData.bottomPadding)
  }
}

@Module
@InstallIn(SingletonComponent::class)
object GeminiNanoTaskModule {
  @Provides
  @IntoSet
  fun provideGeminiNanoTask(): CustomTask = GeminiNanoTask()
}

@Composable
private fun GeminiNanoHubScreen(bottomPadding: androidx.compose.ui.unit.Dp) {
  var selectedTool by remember { mutableStateOf<NanoTool?>(null) }

  if (selectedTool == null) {
    val genAiTools = remember { NanoTool.entries.filter { it.requiresAiCore } }
    val visionTools = remember { NanoTool.entries.filter { !it.requiresAiCore } }
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(bottom = bottomPadding)
        .padding(horizontal = 16.dp),
    ) {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
      ) {
        item {
          Text(
            text = "Gemini Nano (AICore · NPU)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
        }
        items(genAiTools.chunked(2)) { pair ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            pair.forEach { tool ->
              NanoToolTile(tool = tool, modifier = Modifier.weight(1f)) { selectedTool = tool }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
          }
        }
        item {
          Spacer(Modifier.height(4.dp))
          Text(
            text = "ML Kit Vision (All Devices)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
        }
        items(visionTools.chunked(2)) { pair ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            pair.forEach { tool ->
              NanoToolTile(tool = tool, modifier = Modifier.weight(1f)) { selectedTool = tool }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
          }
        }
      }
    }
  } else {
    val tool = selectedTool!!
    when (tool) {
      NanoTool.SUMMARIZE -> NanoSummarizeContent(bottomPadding) { selectedTool = null }
      NanoTool.PROOFREAD -> NanoProofreadContent(bottomPadding) { selectedTool = null }
      NanoTool.REWRITE -> NanoRewriteContent(bottomPadding) { selectedTool = null }
      NanoTool.DESCRIBE -> NanoDescribeContent(bottomPadding) { selectedTool = null }
      NanoTool.PROMPT -> NanoPromptContent(bottomPadding) { selectedTool = null }
      NanoTool.CHAT -> NanoChatContent(bottomPadding) { selectedTool = null }
      NanoTool.SPEECH -> NanoSpeechContent(bottomPadding) { selectedTool = null }
      NanoTool.SCAN_TEXT,
      NanoTool.SCAN_BARCODE,
      NanoTool.LABEL_IMAGE,
      NanoTool.DETECT_FACE,
      NanoTool.POSE,
      NanoTool.FACE_MESH -> NanoVisionContent(tool = tool, bottomPadding = bottomPadding) {
        selectedTool = null
      }
    }
  }
}

@Composable
private fun NanoToolTile(tool: NanoTool, modifier: Modifier = Modifier, onClick: () -> Unit) {
  Card(
    modifier = modifier.clickable(onClick = onClick),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    shape = RoundedCornerShape(16.dp),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Icon(
        imageVector = tool.icon,
        contentDescription = tool.title,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(26.dp),
      )
      Text(tool.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
      Text(
        tool.subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun NanoSubToolScaffold(
  title: String,
  featureStatus: Int?,
  isDownloading: Boolean,
  onDownload: (() -> Unit)?,
  snackbarHostState: SnackbarHostState,
  bottomPadding: androidx.compose.ui.unit.Dp,
  onBack: () -> Unit,
  content: @Composable (PaddingValues) -> Unit,
) {
  Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      Column {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
          }
          Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
          )
          StatusPill(featureStatus)
        }
        if (featureStatus == FeatureStatus.DOWNLOADABLE && onDownload != null) {
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                if (isDownloading) "Downloading AICore feature…" else "Feature needs one-time AICore download",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
              )
              if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
              } else {
                TextButton(onClick = onDownload) {
                  Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(4.dp))
                  Text("Download")
                }
              }
            }
          }
        } else if (featureStatus == FeatureStatus.UNAVAILABLE) {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
          ) {
            Text(
              "Gemini Nano AICore feature unavailable on this device/build.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.padding(10.dp),
            )
          }
        }
      }
    },
    modifier = Modifier.padding(bottom = bottomPadding).imePadding(),
    content = content,
  )
}

@Composable
private fun StatusPill(status: Int?) {
  val label = when (status) {
    FeatureStatus.AVAILABLE -> "Ready"
    FeatureStatus.DOWNLOADABLE -> "Download"
    FeatureStatus.DOWNLOADING -> "Downloading"
    FeatureStatus.UNAVAILABLE -> "Unavailable"
    else -> "Checking"
  }
  val bg = when (status) {
    FeatureStatus.AVAILABLE -> MaterialTheme.colorScheme.primaryContainer
    FeatureStatus.UNAVAILABLE -> MaterialTheme.colorScheme.errorContainer
    else -> MaterialTheme.colorScheme.surfaceContainerHighest
  }
  Surface(color = bg, shape = RoundedCornerShape(20.dp)) {
    Text(
      label,
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
    )
  }
}

private fun downloadFeatureWithPoll(
  scope: kotlinx.coroutines.CoroutineScope,
  checkStatus: suspend () -> Int,
  triggerDownload: (DownloadCallback) -> ListenableFuture<Void>,
  onStatusChange: (Int) -> Unit,
  onDownloadingChange: (Boolean) -> Unit,
  snackbar: SnackbarHostState,
) {
  onDownloadingChange(true)
  scope.launch {
    try {
      triggerDownload(object : DownloadCallback {
        override fun onDownloadCompleted() {
          onStatusChange(FeatureStatus.AVAILABLE)
          onDownloadingChange(false)
        }

        override fun onDownloadFailed(e: GenAiException) {
          onDownloadingChange(false)
          scope.launch { snackbar.showSnackbar("Download failed: ${e.message}") }
        }
      })
      for (i in 0 until 60) {
        delay(2000)
        val st = runCatching { checkStatus() }.getOrDefault(FeatureStatus.UNAVAILABLE)
        onStatusChange(st)
        if (st == FeatureStatus.AVAILABLE || st == FeatureStatus.UNAVAILABLE) {
          onDownloadingChange(false)
          break
        }
      }
    } catch (t: Throwable) {
      onDownloadingChange(false)
      snackbar.showSnackbar("Download error: ${t.message}")
    }
  }
}

@Composable
private fun NanoTextInputTool(
  title: String,
  placeholder: String,
  actionLabel: String,
  featureStatus: Int?,
  isDownloading: Boolean,
  onDownload: () -> Unit,
  minChars: Int = 1,
  output: String,
  isRunning: Boolean,
  snackbarHostState: SnackbarHostState,
  bottomPadding: androidx.compose.ui.unit.Dp,
  onBack: () -> Unit,
  extraControls: @Composable (() -> Unit)? = null,
  onRun: (String) -> Unit,
) {
  var input by remember { mutableStateOf("") }
  val context = LocalContext.current
  NanoSubToolScaffold(
    title = title,
    featureStatus = featureStatus,
    isDownloading = isDownloading,
    onDownload = onDownload,
    snackbarHostState = snackbarHostState,
    bottomPadding = bottomPadding,
    onBack = onBack,
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .consumeWindowInsets(innerPadding)
        .padding(16.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      extraControls?.invoke()
      OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 140.dp, max = 260.dp),
        label = { Text(placeholder) },
        supportingText = if (minChars > 1) {
          { Text("${input.length} / $minChars+ characters") }
        } else null,
      )
      Button(
        onClick = { onRun(input) },
        enabled = !isRunning &&
          input.trim().length >= minChars &&
          featureStatus != null &&
          featureStatus != FeatureStatus.UNAVAILABLE,
        modifier = Modifier.fillMaxWidth(),
      ) {
        if (isRunning) {
          CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
          Spacer(Modifier.width(8.dp))
          Text("Processing…")
        } else {
          Text(actionLabel)
        }
      }
      if (output.isNotEmpty()) {
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
          Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text("Result", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
              IconButton(onClick = { copyToClipboard(context, title, output) }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
              }
            }
            MarkdownText(text = output)
          }
        }
      }
    }
  }
}

@Composable
private fun NanoSummarizeContent(bottomPadding: androidx.compose.ui.unit.Dp, onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val client = remember {
    Summarization.getClient(
      SummarizerOptions.builder(context)
        .setInputType(SummarizerOptions.InputType.ARTICLE)
        .setOutputType(SummarizerOptions.OutputType.THREE_BULLETS)
        .setLanguage(SummarizerOptions.Language.ENGLISH)
        .build(),
    )
  }
  DisposableEffect(client) { onDispose { runCatching { client.close() } } }

  var status by remember { mutableStateOf<Int?>(null) }
  var downloading by remember { mutableStateOf(false) }
  var running by remember { mutableStateOf(false) }
  var output by remember { mutableStateOf("") }

  LaunchedEffect(client) {
    status = runCatching { client.checkFeatureStatus().awaitListenable() }
      .getOrDefault(FeatureStatus.UNAVAILABLE)
  }

  NanoTextInputTool(
    title = "Summarize",
    placeholder = "Paste an article or conversation (400+ characters)…",
    actionLabel = "Summarize",
    featureStatus = status,
    isDownloading = downloading,
    onDownload = {
      downloadFeatureWithPoll(
        scope,
        { client.checkFeatureStatus().awaitListenable() },
        { cb -> client.downloadFeature(cb) },
        { status = it },
        { downloading = it },
        snackbar,
      )
    },
    minChars = 50,
    output = output,
    isRunning = running,
    snackbarHostState = snackbar,
    bottomPadding = bottomPadding,
    onBack = onBack,
  ) { text ->
    running = true
    output = ""
    scope.launch {
      try {
        val sb = StringBuilder()
        val res: SummarizationResult = withContext(Dispatchers.IO) {
          val req = SummarizationRequest.builder(text).build()
          client.runInference(
            req,
            StreamingCallback { chunk ->
              sb.append(chunk)
              output = sb.toString()
            },
          ).awaitListenable()
        }
        output = res.summary.ifBlank { sb.toString() }
      } catch (t: Throwable) {
        snackbar.showSnackbar("Summarize error: ${t.message}")
      } finally {
        running = false
      }
    }
  }
}

@Composable
private fun NanoProofreadContent(bottomPadding: androidx.compose.ui.unit.Dp, onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val client = remember {
    Proofreading.getClient(
      ProofreaderOptions.builder(context)
        .setInputType(ProofreaderOptions.InputType.KEYBOARD)
        .setLanguage(ProofreaderOptions.Language.ENGLISH)
        .build(),
    )
  }
  DisposableEffect(client) { onDispose { runCatching { client.close() } } }

  var status by remember { mutableStateOf<Int?>(null) }
  var downloading by remember { mutableStateOf(false) }
  var running by remember { mutableStateOf(false) }
  var output by remember { mutableStateOf("") }

  LaunchedEffect(client) {
    status = runCatching { client.checkFeatureStatus().awaitListenable() }
      .getOrDefault(FeatureStatus.UNAVAILABLE)
  }

  NanoTextInputTool(
    title = "Proofread",
    placeholder = "Paste text to proofread…",
    actionLabel = "Proofread",
    featureStatus = status,
    isDownloading = downloading,
    onDownload = {
      downloadFeatureWithPoll(
        scope,
        { client.checkFeatureStatus().awaitListenable() },
        { cb -> client.downloadFeature(cb) },
        { status = it },
        { downloading = it },
        snackbar,
      )
    },
    output = output,
    isRunning = running,
    snackbarHostState = snackbar,
    bottomPadding = bottomPadding,
    onBack = onBack,
  ) { text ->
    running = true
    output = ""
    scope.launch {
      try {
        val sb = StringBuilder()
        val res: ProofreadingResult = withContext(Dispatchers.IO) {
          val req = ProofreadingRequest.builder(text).build()
          client.runInference(
            req,
            StreamingCallback { chunk ->
              sb.append(chunk)
              output = sb.toString()
            },
          ).awaitListenable()
        }
        output = res.results.firstOrNull()?.text ?: sb.toString()
      } catch (t: Throwable) {
        snackbar.showSnackbar("Proofread error: ${t.message}")
      } finally {
        running = false
      }
    }
  }
}

@Composable
private fun NanoRewriteContent(bottomPadding: androidx.compose.ui.unit.Dp, onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val tones = remember {
    listOf(
      "Rephrase" to RewriterOptions.OutputType.REPHRASE,
      "Friendly" to RewriterOptions.OutputType.FRIENDLY,
      "Professional" to RewriterOptions.OutputType.PROFESSIONAL,
      "Shorten" to RewriterOptions.OutputType.SHORTEN,
      "Elaborate" to RewriterOptions.OutputType.ELABORATE,
      "Emojify" to RewriterOptions.OutputType.EMOJIFY,
    )
  }
  var toneIdx by remember { mutableIntStateOf(0) }
  val client = remember(toneIdx) {
    Rewriting.getClient(
      RewriterOptions.builder(context)
        .setOutputType(tones[toneIdx].second)
        .setLanguage(RewriterOptions.Language.ENGLISH)
        .build(),
    )
  }
  DisposableEffect(client) { onDispose { runCatching { client.close() } } }

  var status by remember { mutableStateOf<Int?>(null) }
  var downloading by remember { mutableStateOf(false) }
  var running by remember { mutableStateOf(false) }
  var output by remember { mutableStateOf("") }

  LaunchedEffect(client) {
    status = runCatching { client.checkFeatureStatus().awaitListenable() }
      .getOrDefault(FeatureStatus.UNAVAILABLE)
  }

  NanoTextInputTool(
    title = "Rewrite",
    placeholder = "Paste text to rewrite…",
    actionLabel = "Rewrite (${tones[toneIdx].first})",
    featureStatus = status,
    isDownloading = downloading,
    onDownload = {
      downloadFeatureWithPoll(
        scope,
        { client.checkFeatureStatus().awaitListenable() },
        { cb -> client.downloadFeature(cb) },
        { status = it },
        { downloading = it },
        snackbar,
      )
    },
    output = output,
    isRunning = running,
    snackbarHostState = snackbar,
    bottomPadding = bottomPadding,
    onBack = onBack,
    extraControls = {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        tones.forEachIndexed { idx, (label, _) ->
          FilterChip(
            selected = idx == toneIdx,
            onClick = { toneIdx = idx },
            label = { Text(label) },
          )
        }
      }
    },
  ) { text ->
    running = true
    output = ""
    scope.launch {
      try {
        val sb = StringBuilder()
        val res: RewritingResult = withContext(Dispatchers.IO) {
          val req = RewritingRequest.builder(text).build()
          client.runInference(
            req,
            StreamingCallback { chunk ->
              sb.append(chunk)
              output = sb.toString()
            },
          ).awaitListenable()
        }
        output = res.results.firstOrNull()?.text ?: sb.toString()
      } catch (t: Throwable) {
        snackbar.showSnackbar("Rewrite error: ${t.message}")
      } finally {
        running = false
      }
    }
  }
}

@Composable
private fun NanoDescribeContent(bottomPadding: androidx.compose.ui.unit.Dp, onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val client = remember {
    ImageDescription.getClient(ImageDescriberOptions.builder(context).build())
  }
  DisposableEffect(client) { onDispose { runCatching { client.close() } } }

  var status by remember { mutableStateOf<Int?>(null) }
  var downloading by remember { mutableStateOf(false) }
  var running by remember { mutableStateOf(false) }
  var bitmap by remember { mutableStateOf<Bitmap?>(null) }
  var output by remember { mutableStateOf("") }
  var useCamera by remember { mutableStateOf(false) }

  LaunchedEffect(client) {
    status = runCatching { client.checkFeatureStatus().awaitListenable() }
      .getOrDefault(FeatureStatus.UNAVAILABLE)
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      scope.launch {
        bitmap = withContext(Dispatchers.IO) { decodeBitmapUri(context, uri) }
        output = ""
      }
    }
  }

  val describeBitmap: (Bitmap) -> Unit = { bmp ->
    running = true
    output = ""
    scope.launch {
      try {
        val sb = StringBuilder()
        val res: ImageDescriptionResult = withContext(Dispatchers.IO) {
          val req = ImageDescriptionRequest.builder(bmp).build()
          client.runInference(
            req,
            StreamingCallback { chunk ->
              sb.append(chunk)
              output = sb.toString()
            },
          ).awaitListenable()
        }
        output = res.description.ifBlank { sb.toString() }
      } catch (t: Throwable) {
        snackbar.showSnackbar("Describe error: ${t.message}")
      } finally {
        running = false
      }
    }
  }

  NanoSubToolScaffold(
    title = "Describe Image",
    featureStatus = status,
    isDownloading = downloading,
    onDownload = {
      downloadFeatureWithPoll(
        scope,
        { client.checkFeatureStatus().awaitListenable() },
        { cb -> client.downloadFeature(cb) },
        { status = it },
        { downloading = it },
        snackbar,
      )
    },
    snackbarHostState = snackbar,
    bottomPadding = bottomPadding,
    onBack = onBack,
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .consumeWindowInsets(innerPadding)
        .padding(16.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { useCamera = false; picker.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) {
          Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Pick Photo")
        }
        OutlinedButton(onClick = { useCamera = !useCamera }, modifier = Modifier.weight(1f)) {
          Icon(Icons.Rounded.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text(if (useCamera) "Hide Camera" else "Camera")
        }
      }
      if (useCamera) {
        NanoCameraCaptureBox(
          enabled = !running,
          onCaptured = { bmp ->
            bitmap = bmp
            useCamera = false
            describeBitmap(bmp)
          },
        )
      }
      bitmap?.let { bmp ->
        Image(
          bitmap = bmp.asImageBitmap(),
          contentDescription = null,
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .clip(RoundedCornerShape(14.dp)),
          contentScale = ContentScale.Fit,
        )
        Button(
          onClick = { describeBitmap(bmp) },
          enabled = !running && status != FeatureStatus.UNAVAILABLE,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(if (running) "Describing…" else "Describe Image")
        }
      }
      if (output.isNotEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
          MarkdownText(text = output, modifier = Modifier.padding(14.dp))
        }
      }
    }
  }
}

@Composable
private fun NanoPromptContent(bottomPadding: androidx.compose.ui.unit.Dp, onBack: () -> Unit) {
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val model = remember { Generation.getClient() }
  DisposableEffect(model) { onDispose { runCatching { model.close() } } }

  var status by remember { mutableStateOf<Int?>(null) }
  var downloading by remember { mutableStateOf(false) }
  var running by remember { mutableStateOf(false) }
  var output by remember { mutableStateOf("") }

  LaunchedEffect(model) {
    status = runCatching { model.checkStatus() }.getOrDefault(FeatureStatus.UNAVAILABLE)
  }

  NanoTextInputTool(
    title = "Custom Prompt",
    placeholder = "Ask Gemini Nano anything…",
    actionLabel = "Generate",
    featureStatus = status,
    isDownloading = downloading,
    onDownload = {
      downloading = true
      scope.launch {
        try {
          model.download().collect { dl ->
            if (dl is DownloadStatus.DownloadCompleted) {
              status = FeatureStatus.AVAILABLE
              downloading = false
            } else if (dl is DownloadStatus.DownloadFailed) {
              downloading = false
              snackbar.showSnackbar("Download failed: ${dl.e.message}")
            }
          }
        } catch (t: Throwable) {
          downloading = false
          snackbar.showSnackbar("Download error: ${t.message}")
        }
      }
    },
    output = output,
    isRunning = running,
    snackbarHostState = snackbar,
    bottomPadding = bottomPadding,
    onBack = onBack,
  ) { prompt ->
    running = true
    output = ""
    scope.launch {
      try {
        val sb = StringBuilder()
        model.generateContentStream(prompt).collect { chunk ->
          val part = chunk.candidates.firstOrNull()?.text.orEmpty()
          sb.append(part)
          output = sb.toString()
        }
      } catch (t: Throwable) {
        snackbar.showSnackbar("Prompt error: ${t.message}")
      } finally {
        running = false
      }
    }
  }
}

@Composable
private fun NanoChatContent(
  bottomPadding: androidx.compose.ui.unit.Dp,
  viewModel: NanoChatViewModel = hiltViewModel(),
  onBack: () -> Unit,
) {
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val model = remember { Generation.getClient() }
  DisposableEffect(model) { onDispose { runCatching { model.close() } } }

  val conversations by viewModel.conversations.collectAsState()
  val activeConv by viewModel.activeConversation.collectAsState()
  var status by remember { mutableStateOf<Int?>(null) }
  var downloading by remember { mutableStateOf(false) }
  var running by remember { mutableStateOf(false) }
  var input by remember { mutableStateOf("") }
  var streamingReply by remember { mutableStateOf("") }
  val listState = rememberLazyListState()

  LaunchedEffect(model) {
    status = runCatching { model.checkStatus() }.getOrDefault(FeatureStatus.UNAVAILABLE)
  }

  LaunchedEffect(viewModel.messages.size, streamingReply) {
    if (viewModel.messages.isNotEmpty()) {
      listState.animateScrollToItem((viewModel.messages.size - 1).coerceAtLeast(0))
    }
  }

  NanoSubToolScaffold(
    title = activeConv?.title ?: "Nano Chat",
    featureStatus = status,
    isDownloading = downloading,
    onDownload = {
      downloading = true
      scope.launch {
        try {
          model.download().collect { dl ->
            if (dl is DownloadStatus.DownloadCompleted) {
              status = FeatureStatus.AVAILABLE
              downloading = false
            } else if (dl is DownloadStatus.DownloadFailed) {
              downloading = false
            }
          }
        } catch (_: Throwable) {
          downloading = false
        }
      }
    },
    snackbarHostState = snackbar,
    bottomPadding = bottomPadding,
    onBack = onBack,
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .consumeWindowInsets(innerPadding)
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState())
          .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FilterChip(
          selected = false,
          onClick = { viewModel.createNewChat() },
          label = { Text("New Chat") },
          leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
        )
        conversations.take(8).forEach { conv ->
          FilterChip(
            selected = conv.id == activeConv?.id,
            onClick = { viewModel.selectConversation(conv) },
            label = { Text(conv.title.take(18)) },
            trailingIcon = {
              Icon(
                Icons.Rounded.Close,
                contentDescription = "Delete",
                modifier = Modifier.size(16.dp).clickable { viewModel.deleteConversation(conv) },
              )
            },
          )
        }
      }

      LazyColumn(
        state = listState,
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
      ) {
        items(viewModel.messages) { turn ->
          NanoChatBubble(turn.text, turn.isUser)
        }
        if (streamingReply.isNotEmpty()) {
          item { NanoChatBubble(streamingReply, isUser = false) }
        } else if (running) {
          item {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
              Text(
                "Thinking…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedTextField(
          value = input,
          onValueChange = { input = it },
          modifier = Modifier.weight(1f),
          shape = RoundedCornerShape(24.dp),
          placeholder = { Text("Message") },
          maxLines = 4,
        )
        FilledIconButton(
          onClick = {
            val prompt = input.trim()
            if (prompt.isEmpty() || running) return@FilledIconButton
            input = ""
            viewModel.messages.add(NanoChatTurn(prompt, isUser = true))
            viewModel.persistTurn(prompt, isUser = true)
            val contextPrompt = buildString {
              viewModel.messages.takeLast(8).forEach {
                append(if (it.isUser) "User: " else "Assistant: ")
                appendLine(it.text)
              }
              append("Assistant:")
            }
            running = true
            streamingReply = ""
            scope.launch {
              try {
                val sb = StringBuilder()
                model.generateContentStream(contextPrompt).collect { chunk ->
                  val part = chunk.candidates.firstOrNull()?.text.orEmpty()
                  sb.append(part)
                  streamingReply = sb.toString()
                }
                val finalText = sb.toString().trim()
                streamingReply = ""
                if (finalText.isNotEmpty()) {
                  viewModel.messages.add(NanoChatTurn(finalText, isUser = false))
                  viewModel.persistTurn(finalText, isUser = false)
                }
              } catch (t: Throwable) {
                streamingReply = ""
                snackbar.showSnackbar("Chat error: ${t.message}")
              } finally {
                running = false
              }
            }
          },
          enabled = input.isNotBlank() && !running && status != FeatureStatus.UNAVAILABLE,
        ) {
          Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
        }
      }
    }
  }
}

@Composable
private fun NanoChatBubble(text: String, isUser: Boolean) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
  ) {
    Surface(
      color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
      shape = RoundedCornerShape(16.dp),
      modifier = Modifier.widthIn(max = 320.dp),
    ) {
      if (isUser) {
        Text(text = text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
      } else {
        MarkdownText(text = text, modifier = Modifier.padding(12.dp))
      }
    }
  }
}

@Composable
private fun NanoSpeechContent(bottomPadding: androidx.compose.ui.unit.Dp, onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  val recognizer = remember {
    SpeechRecognition.getClient(speechRecognizerOptions { locale = Locale.US })
  }
  DisposableEffect(recognizer) {
    onDispose {
      scope.launch { runCatching { recognizer.stopRecognition() } }
      runCatching { recognizer.close() }
    }
  }

  var status by remember { mutableStateOf<Int?>(null) }
  var downloading by remember { mutableStateOf(false) }
  var listening by remember { mutableStateOf(false) }
  var finalText by remember { mutableStateOf("") }
  var partialText by remember { mutableStateOf("") }
  var listenJob by remember { mutableStateOf<Job?>(null) }

  LaunchedEffect(recognizer) {
    status = runCatching { recognizer.checkStatus() }.getOrDefault(FeatureStatus.UNAVAILABLE)
  }

  val startMic: () -> Unit = {
    listening = true
    partialText = ""
    listenJob = scope.launch {
      try {
        val req = speechRecognizerRequest { audioSource = AudioSource.fromMic() }
        recognizer.startRecognition(req).collect { resp ->
          when (resp) {
            is SpeechRecognizerResponse.PartialTextResponse -> partialText = resp.text
            is SpeechRecognizerResponse.FinalTextResponse -> {
              finalText += resp.text + " "
              partialText = ""
            }
            is SpeechRecognizerResponse.CompletedResponse -> {
              partialText = ""
              listening = false
            }
            is SpeechRecognizerResponse.ErrorResponse -> {
              partialText = ""
              listening = false
              snackbar.showSnackbar("Speech error: ${resp.e.message}")
            }
          }
        }
      } catch (t: Throwable) {
        listening = false
        snackbar.showSnackbar("Speech error: ${t.message}")
      }
    }
  }

  val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
    if (ok) startMic() else scope.launch { snackbar.showSnackbar("Microphone permission needed") }
  }

  NanoSubToolScaffold(
    title = "Speech → Text",
    featureStatus = status,
    isDownloading = downloading,
    onDownload = {
      downloading = true
      scope.launch {
        try {
          recognizer.download().collect { dl ->
            if (dl is DownloadStatus.DownloadCompleted) {
              status = FeatureStatus.AVAILABLE
              downloading = false
            } else if (dl is DownloadStatus.DownloadFailed) {
              downloading = false
              snackbar.showSnackbar("Download failed: ${dl.e.message}")
            }
          }
        } catch (t: Throwable) {
          downloading = false
          snackbar.showSnackbar("Download error: ${t.message}")
        }
      }
    },
    snackbarHostState = snackbar,
    bottomPadding = bottomPadding,
    onBack = onBack,
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Button(
        onClick = {
          if (listening) {
            listenJob?.cancel()
            scope.launch { runCatching { recognizer.stopRecognition() } }
            listening = false
          } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startMic()
          } else {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
          }
        },
        enabled = status != FeatureStatus.UNAVAILABLE,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Icon(if (listening) Icons.Rounded.Stop else Icons.Outlined.Mic, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (listening) "Stop Listening" else "Start Listening")
      }
      if (partialText.isNotEmpty()) {
        Text(partialText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
      }
      Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
        Column(modifier = Modifier.padding(14.dp).verticalScroll(rememberScrollState())) {
          Text(finalText.ifBlank { "Transcript will appear here…" }, style = MaterialTheme.typography.bodyLarge)
        }
      }
      if (finalText.isNotBlank()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { finalText = "" }) { Text("Clear") }
          Button(onClick = { copyToClipboard(context, "Transcript", finalText) }) { Text("Copy") }
        }
      }
    }
  }
}

private data class VisionOverlayState(
  val textResult: String = "",
  val rects: List<android.graphics.Rect> = emptyList(),
  val points: List<Pair<Float, Float>> = emptyList(),
  val lines: List<Pair<Pair<Float, Float>, Pair<Float, Float>>> = emptyList(),
)

@Composable
private fun NanoVisionContent(
  tool: NanoTool,
  bottomPadding: androidx.compose.ui.unit.Dp,
  onBack: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  var bitmap by remember { mutableStateOf<Bitmap?>(null) }
  var running by remember { mutableStateOf(false) }
  var useCamera by remember { mutableStateOf(false) }
  var overlay by remember { mutableStateOf(VisionOverlayState()) }

  val textClient = remember(tool) {
    if (tool == NanoTool.SCAN_TEXT) TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) else null
  }
  val barcodeClient = remember(tool) {
    if (tool == NanoTool.SCAN_BARCODE) BarcodeScanning.getClient() else null
  }
  val labelClient = remember(tool) {
    if (tool == NanoTool.LABEL_IMAGE) ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) else null
  }
  val faceClient = remember(tool) {
    if (tool == NanoTool.DETECT_FACE) {
      FaceDetection.getClient(
        FaceDetectorOptions.Builder()
          .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
          .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
          .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
          .setMinFaceSize(0.04f)
          .build(),
      )
    } else null
  }
  val poseClient = remember(tool) {
    if (tool == NanoTool.POSE) {
      PoseDetection.getClient(
        PoseDetectorOptions.Builder()
          .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
          .build(),
      )
    } else null
  }
  val meshClient = remember(tool) {
    if (tool == NanoTool.FACE_MESH) {
      FaceMeshDetection.getClient(
        FaceMeshDetectorOptions.Builder()
          .setUseCase(FaceMeshDetectorOptions.FACE_MESH)
          .build(),
      )
    } else null
  }

  DisposableEffect(tool) {
    onDispose {
      runCatching { textClient?.close() }
      runCatching { barcodeClient?.close() }
      runCatching { labelClient?.close() }
      runCatching { faceClient?.close() }
      runCatching { poseClient?.close() }
      runCatching { meshClient?.close() }
    }
  }

  val analyzeBitmap: (Bitmap) -> Unit = { bmp ->
    running = true
    overlay = VisionOverlayState()
    scope.launch {
      try {
        val img = InputImage.fromBitmap(bmp, 0)
        overlay = withContext(Dispatchers.Default) {
          when (tool) {
            NanoTool.SCAN_TEXT -> {
              val res = textClient!!.process(img).awaitPlayTask()
              val boxes = res.textBlocks.mapNotNull { it.boundingBox }
              VisionOverlayState(
                textResult = res.text.ifBlank { "No text detected." },
                rects = boxes,
              )
            }
            NanoTool.SCAN_BARCODE -> {
              val codes = barcodeClient!!.process(img).awaitPlayTask()
              val boxes = codes.mapNotNull { it.boundingBox }
              val desc = if (codes.isEmpty()) {
                "No barcodes detected."
              } else {
                codes.joinToString("\n") { bc ->
                  bc.rawValue ?: bc.displayValue ?: "(binary payload)"
                }
              }
              VisionOverlayState(textResult = desc, rects = boxes)
            }
            NanoTool.LABEL_IMAGE -> {
              val labels = labelClient!!.process(img).awaitPlayTask()
              val desc = if (labels.isEmpty()) {
                "No labels identified."
              } else {
                labels.joinToString("\n") { lbl ->
                  "${lbl.text} — ${(lbl.confidence * 100).toInt()}%"
                }
              }
              VisionOverlayState(textResult = desc)
            }
            NanoTool.DETECT_FACE -> {
              val faces = faceClient!!.process(img).awaitPlayTask()
              val boxes = faces.map { it.boundingBox }
              val summary = if (faces.isEmpty()) {
                "No faces detected."
              } else {
                faces.mapIndexed { idx, f ->
                  val smile = f.smilingProbability?.let { " · smile ${(it * 100).toInt()}%" } ?: ""
                  "Face #${idx + 1}: ${f.boundingBox.width()}×${f.boundingBox.height()}$smile"
                }.joinToString("\n")
              }
              VisionOverlayState(textResult = summary, rects = boxes)
            }
            NanoTool.POSE -> {
              val pose = poseClient!!.process(img).awaitPlayTask()
              val pairs = listOf(
                PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
                PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
                PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
                PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
                PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
                PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
                PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
                PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
                PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
                PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
                PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
                PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE,
              )
              val lines = pairs.mapNotNull { (a, b) ->
                val la = pose.getPoseLandmark(a)
                val lb = pose.getPoseLandmark(b)
                if (la != null && lb != null) (la.position.x to la.position.y) to (lb.position.x to lb.position.y) else null
              }
              val pts = pose.allPoseLandmarks.map { it.position.x to it.position.y }
              VisionOverlayState(
                textResult = "Detected ${pose.allPoseLandmarks.size} pose landmarks",
                points = pts,
                lines = lines,
              )
            }
            NanoTool.FACE_MESH -> {
              val meshes = meshClient!!.process(img).awaitPlayTask()
              val pts = meshes.flatMap { m -> m.allPoints.map { it.position.x to it.position.y } }
              VisionOverlayState(
                textResult = "Detected ${meshes.size} face mesh(es), ${pts.size} vertices",
                points = pts,
                rects = meshes.map { it.boundingBox },
              )
            }
            else -> VisionOverlayState()
          }
        }
      } catch (t: Throwable) {
        snackbar.showSnackbar("Vision error: ${t.message}")
      } finally {
        running = false
      }
    }
  }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) {
      scope.launch {
        val decoded = withContext(Dispatchers.IO) { decodeBitmapUri(context, uri) }
        if (decoded != null) {
          bitmap = decoded
          useCamera = false
          analyzeBitmap(decoded)
        }
      }
    }
  }

  NanoSubToolScaffold(
    title = tool.title,
    featureStatus = FeatureStatus.AVAILABLE,
    isDownloading = false,
    onDownload = null,
    snackbarHostState = snackbar,
    bottomPadding = bottomPadding,
    onBack = onBack,
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .consumeWindowInsets(innerPadding)
        .padding(16.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { useCamera = false; picker.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) {
          Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Pick Photo")
        }
        OutlinedButton(onClick = { useCamera = !useCamera }, modifier = Modifier.weight(1f)) {
          Icon(Icons.Rounded.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text(if (useCamera) "Hide Camera" else "Camera")
        }
      }
      if (useCamera) {
        NanoCameraCaptureBox(
          enabled = !running,
          onCaptured = { bmp ->
            bitmap = bmp
            useCamera = false
            analyzeBitmap(bmp)
          },
        )
      }
      bitmap?.let { bmp ->
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(bmp.width.toFloat() / bmp.height.coerceAtLeast(1))
            .clip(RoundedCornerShape(14.dp)),
        ) {
          Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
          )
          Canvas(modifier = Modifier.fillMaxSize()) {
            val sx = size.width / bmp.width.coerceAtLeast(1)
            val sy = size.height / bmp.height.coerceAtLeast(1)
            overlay.rects.forEach { r ->
              drawRect(
                color = Color(0xFF00E5FF),
                topLeft = Offset(r.left * sx, r.top * sy),
                size = Size(r.width() * sx, r.height() * sy),
                style = Stroke(width = 4f),
              )
            }
            overlay.lines.forEach { (a, b) ->
              drawLine(
                color = Color(0xFF76FF03),
                start = Offset(a.first * sx, a.second * sy),
                end = Offset(b.first * sx, b.second * sy),
                strokeWidth = 4f,
              )
            }
            overlay.points.forEach { (px, py) ->
              drawCircle(
                color = Color(0xFFFFD600),
                radius = 4f,
                center = Offset(px * sx, py * sy),
              )
            }
          }
        }
      }
      if (running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      if (overlay.textResult.isNotEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
          Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text("Output", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
              IconButton(onClick = { copyToClipboard(context, tool.title, overlay.textResult) }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
              }
            }
            Text(overlay.textResult, style = MaterialTheme.typography.bodyMedium)
          }
        }
      }
    }
  }
}

@Composable
private fun NanoCameraCaptureBox(
  enabled: Boolean,
  onCaptured: (Bitmap) -> Unit,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var hasPerm by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
    )
  }
  val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    hasPerm = it
  }
  LaunchedEffect(Unit) {
    if (!hasPerm) permLauncher.launch(Manifest.permission.CAMERA)
  }
  if (!hasPerm) {
    Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
      Text("Grant Camera Permission")
    }
    return
  }

  val imageCapture = remember { ImageCapture.Builder().build() }
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(260.dp)
      .clip(RoundedCornerShape(14.dp))
      .background(Color.Black),
  ) {
    AndroidView(
      factory = { ctx ->
        PreviewView(ctx).also { previewView ->
          val future = ProcessCameraProvider.getInstance(ctx)
          future.addListener(
            {
              val provider = future.get()
              val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
              }
              runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                  lifecycleOwner,
                  CameraSelector.DEFAULT_BACK_CAMERA,
                  preview,
                  imageCapture,
                )
              }
            },
            ContextCompat.getMainExecutor(ctx),
          )
        }
      },
      modifier = Modifier.fillMaxSize(),
    )
    FilledIconButton(
      onClick = {
        imageCapture.takePicture(
          ContextCompat.getMainExecutor(context),
          object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
              val bmp = imageProxyToBitmap(image)
              image.close()
              onCaptured(bmp)
            }

            override fun onError(exception: ImageCaptureException) {
              Log.w(TAG, "Capture failed", exception)
            }
          },
        )
      },
      enabled = enabled,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(12.dp)
        .size(56.dp),
    ) {
      Icon(Icons.Rounded.CameraAlt, contentDescription = "Capture")
    }
  }
}
