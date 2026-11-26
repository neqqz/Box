package com.google.ai.edge.gallery.customtasks.boxassist

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Matrix
import android.graphics.RectF
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.customtasks.sensevoice.SenseVoiceEngine
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDataFile
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.exp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "BoxAssist"

private val COCO_LABELS = listOf(
  "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
  "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog",
  "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella",
  "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball", "kite",
  "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket", "bottle",
  "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich",
  "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
  "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote",
  "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator", "book",
  "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush",
)

enum class BoxAssistMode(val label: String) {
  LIVE("Live"),
  READING("Read Text"),
  DESCRIBE("Describe"),
}

enum class DepthBand(val phrase: String) {
  VERY_CLOSE("very close"),
  CLOSE("close"),
  NEARBY("nearby"),
  UNKNOWN(""),
}

data class AssistDetection(
  val label: String,
  val score: Float,
  val box: RectF,
  val depthBand: DepthBand,
  val positionHint: String,
)

class YoloxMidasEngine {
  private var env: Environment? = null
  private var yoloxModel: CompiledModel? = null
  private var midasModel: CompiledModel? = null
  private val mutex = Mutex()

  private val anchors: Array<Triple<Int, Int, Int>> by lazy {
    val list = ArrayList<Triple<Int, Int, Int>>(8400)
    for (stride in intArrayOf(8, 16, 32)) {
      val grid = 640 / stride
      for (gy in 0 until grid) {
        for (gx in 0 until grid) {
          list.add(Triple(gx, gy, stride))
        }
      }
    }
    list.toTypedArray()
  }

  fun load(yoloxPath: String, midasPath: String): Boolean = try {
    close()
    val createdEnv = Environment.create()
    env = createdEnv
    yoloxModel = CompiledModel.create(yoloxPath, CompiledModel.Options(Accelerator.CPU), createdEnv)
    midasModel = CompiledModel.create(midasPath, CompiledModel.Options(Accelerator.CPU), createdEnv)
    true
  } catch (t: Throwable) {
    Log.e(TAG, "Failed to load Box Assist LiteRT models", t)
    close()
    false
  }

  suspend fun detectAndEstimateDepth(
    bitmap: Bitmap,
    confThreshold: Float = 0.42f,
  ): List<AssistDetection> = mutex.withLock {
    val yolox = yoloxModel ?: return emptyList()
    val midas = midasModel ?: return emptyList()

    // 1. Letterbox to 640x640 RGB [0..255] NHWC
    val scale = minOf(640f / bitmap.width.coerceAtLeast(1), 640f / bitmap.height.coerceAtLeast(1))
    val newW = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val newH = (bitmap.height * scale).toInt().coerceAtLeast(1)
    val padX = (640f - newW) / 2f
    val padY = (640f - newH) / 2f
    val canvasBmp = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
    Canvas(canvasBmp).apply {
      drawColor(AndroidColor.rgb(114, 114, 114))
      val resized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
      drawBitmap(resized, padX, padY, null)
      if (resized != bitmap) resized.recycle()
    }
    val yPixels = IntArray(640 * 640)
    canvasBmp.getPixels(yPixels, 0, 640, 0, 0, 640, 640)
    canvasBmp.recycle()

    val yIn = FloatArray(640 * 640 * 3)
    for (i in yPixels.indices) {
      val p = yPixels[i]
      val base = i * 3
      yIn[base] = ((p shr 16) and 0xFF).toFloat()
      yIn[base + 1] = ((p shr 8) and 0xFF).toFloat()
      yIn[base + 2] = (p and 0xFF).toFloat()
    }

    val yInputs = yolox.createInputBuffers()
    val yOutputs = yolox.createOutputBuffers()
    val rawDetections = try {
      yInputs[0].writeFloat(yIn)
      yolox.run(yInputs, yOutputs)
      yOutputs[0].readFloat()
    } finally {
      yInputs.forEach { runCatching { it.close() } }
      yOutputs.forEach { runCatching { it.close() } }
    }

    // 2. Decode YOLOX predictions + NMS
    val n = minOf(rawDetections.size / 85, anchors.size)
    val candidates = ArrayList<AssistDetection>()
    val imgW = bitmap.width.toFloat()
    val imgH = bitmap.height.toFloat()
    fun sig(v: Float): Float = if (v in 0f..1f) v else 1f / (1f + exp(-v))

    for (i in 0 until n) {
      val off = i * 85
      val (gx, gy, stride) = anchors[i]
      val cx = (rawDetections[off] + gx) * stride
      val cy = (rawDetections[off + 1] + gy) * stride
      val bw = exp(rawDetections[off + 2].toDouble()).toFloat() * stride
      val bh = exp(rawDetections[off + 3].toDouble()).toFloat() * stride
      val obj = sig(rawDetections[off + 4])
      if (obj < confThreshold) continue
      var bestCls = 0
      var bestClsScore = 0f
      for (c in 0 until 80) {
        val sc = sig(rawDetections[off + 5 + c])
        if (sc > bestClsScore) {
          bestClsScore = sc
          bestCls = c
        }
      }
      val score = obj * bestClsScore
      if (score < confThreshold) continue
      val x1 = ((cx - bw / 2f - padX) / scale).coerceIn(0f, imgW)
      val y1 = ((cy - bh / 2f - padY) / scale).coerceIn(0f, imgH)
      val x2 = ((cx + bw / 2f - padX) / scale).coerceIn(0f, imgW)
      val y2 = ((cy + bh / 2f - padY) / scale).coerceIn(0f, imgH)
      val rect = RectF(x1, y1, x2, y2)
      if (rect.width() < 2f || rect.height() < 2f) continue
      val relX = rect.centerX() / imgW.coerceAtLeast(1f)
      val pos = when {
        relX < 0.34f -> "on your left"
        relX > 0.66f -> "on your right"
        else -> "ahead"
      }
      candidates.add(
        AssistDetection(
          label = COCO_LABELS.getOrElse(bestCls) { "object" },
          score = score,
          box = rect,
          depthBand = DepthBand.UNKNOWN,
          positionHint = pos,
        ),
      )
    }
    candidates.sortByDescending { it.score }
    val kept = ArrayList<AssistDetection>()
    for (cand in candidates) {
      if (kept.none { iou(it.box, cand.box) > 0.45f }) kept.add(cand)
      if (kept.size >= 12) break
    }
    if (kept.isEmpty()) return emptyList()

    // 3. Run MiDaS small 256x256 inverse depth
    val dBmp = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
    val dPixels = IntArray(256 * 256)
    dBmp.getPixels(dPixels, 0, 256, 0, 0, 256, 256)
    if (dBmp != bitmap) dBmp.recycle()

    val dIn = FloatArray(256 * 256 * 3)
    for (i in dPixels.indices) {
      val p = dPixels[i]
      val base = i * 3
      dIn[base] = (((p shr 16) and 0xFF) / 255f - 0.485f) / 0.229f
      dIn[base + 1] = (((p shr 8) and 0xFF) / 255f - 0.456f) / 0.224f
      dIn[base + 2] = ((p and 0xFF) / 255f - 0.406f) / 0.225f
    }
    val dInputs = midas.createInputBuffers()
    val dOutputs = midas.createOutputBuffers()
    val depthMap = try {
      dInputs[0].writeFloat(dIn)
      midas.run(dInputs, dOutputs)
      dOutputs[0].readFloat()
    } finally {
      dInputs.forEach { runCatching { it.close() } }
      dOutputs.forEach { runCatching { it.close() } }
    }
    if (depthMap.size < 256 * 256) return kept

    var minD = Float.MAX_VALUE
    var maxD = -Float.MAX_VALUE
    for (v in depthMap) {
      if (v < minD) minD = v
      if (v > maxD) maxD = v
    }
    val range = (maxD - minD).coerceAtLeast(1e-5f)

    return kept.map { det ->
      val dx1 = ((det.box.left / imgW) * 255f).toInt().coerceIn(0, 255)
      val dy1 = ((det.box.top / imgH) * 255f).toInt().coerceIn(0, 255)
      val dx2 = ((det.box.right / imgW) * 255f).toInt().coerceIn(dx1 + 1, 256)
      val dy2 = ((det.box.bottom / imgH) * 255f).toInt().coerceIn(dy1 + 1, 256)
      var sum = 0f
      var cnt = 0
      for (y in dy1 until dy2 step 2) {
        val row = y * 256
        for (x in dx1 until dx2 step 2) {
          sum += (depthMap[row + x] - minD) / range
          cnt++
        }
      }
      val avgInvDepth = if (cnt > 0) sum / cnt else 0f
      val band = when {
        avgInvDepth > 0.68f -> DepthBand.VERY_CLOSE
        avgInvDepth > 0.42f -> DepthBand.CLOSE
        else -> DepthBand.NEARBY
      }
      det.copy(depthBand = band)
    }
  }

  private fun iou(a: RectF, b: RectF): Float {
    val ix1 = maxOf(a.left, b.left)
    val iy1 = maxOf(a.top, b.top)
    val ix2 = minOf(a.right, b.right)
    val iy2 = minOf(a.bottom, b.bottom)
    val iw = (ix2 - ix1).coerceAtLeast(0f)
    val ih = (iy2 - iy1).coerceAtLeast(0f)
    val inter = iw * ih
    val union = a.width() * a.height() + b.width() * b.height() - inter
    return if (union > 0f) inter / union else 0f
  }

  fun close() {
    runCatching { yoloxModel?.close() }
    runCatching { midasModel?.close() }
    runCatching { env?.close() }
    yoloxModel = null
    midasModel = null
    env = null
  }
}

class BoxAssistTask @Inject constructor() : CustomTask {
  private val model = Model(
    name = "Box Assist",
    info =
      "Spoken camera assistance for blind and low-vision users, fully on-device. Includes YOLOX-M " +
        "object detection and MiDaS depth sensing for Live mode, ML Kit OCR for Reading mode, and " +
        "optional installed Gemma vision models for Describe & Ask.",
    url = "https://huggingface.co/litert-community/yolox-m-litert/resolve/main/yolox_m.tflite?download=true",
    sizeInBytes = 51_042_192L,
    downloadFileName = "yolox_m.tflite",
    version = "main",
    extraDataFiles = listOf(
      ModelDataFile(
        name = "midas",
        url = "https://huggingface.co/litert-community/MiDaS-small/resolve/main/midas_small_256_fp16.tflite?download=true",
        downloadFileName = "midas_small_256_fp16.tflite",
        sizeInBytes = 33_507_904L,
      ),
    ),
    learnMoreUrl = "https://huggingface.co/litert-community/yolox-m-litert",
    showRunAgainButton = false,
    showBenchmarkButton = false,
  )

  override val task: Task = Task(
    id = BuiltInTaskId.BOX_ASSIST,
    label = "Box Assist",
    category = Category.LLM,
    icon = Icons.Outlined.Visibility,
    models = mutableListOf(model),
    description =
      "Camera assistance for blind and low-vision users — spoken, on-device, offline. " +
        "Live mode calls out objects and proximity; Read Text mode reads mail, labels and menus aloud; " +
        "Describe mode answers questions about what the camera sees.",
    shortDescription = "Spoken camera assistance",
    showRunAgainButton = false,
    showBenchmarkButton = false,
  )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch(Dispatchers.IO) {
      val yoloxPath = model.getPath(context, model.downloadFileName)
      val midasPath = model.getPath(context, "midas_small_256_fp16.tflite")
      if (!File(yoloxPath).exists() || !File(midasPath).exists()) {
        onDone("Box Assist models are missing. Please re-download.")
        return@launch
      }
      val engine = YoloxMidasEngine()
      if (engine.load(yoloxPath, midasPath)) {
        model.instance = engine
        onDone("")
      } else {
        onDone("Failed to load Box Assist models.")
      }
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    val engine = model.instance as? YoloxMidasEngine
    model.instance = null
    coroutineScope.launch(Dispatchers.IO) {
      engine?.close()
      onDone()
    }
  }

  @Composable
  override fun MainScreen(data: Any) {
    val taskData = data as CustomTaskData
    val uiState by taskData.modelManagerViewModel.uiState.collectAsState()
    BoxAssistScreen(
      model = uiState.selectedModel,
      modelManagerViewModel = taskData.modelManagerViewModel,
      bottomPadding = taskData.bottomPadding,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
object BoxAssistTaskModule {
  @Provides
  @IntoSet
  fun provideBoxAssistTask(): CustomTask = BoxAssistTask()
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitGmsTask(): T =
  suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
  }

@Composable
private fun BoxAssistScreen(
  model: Model,
  modelManagerViewModel: ModelManagerViewModel,
  bottomPadding: androidx.compose.ui.unit.Dp,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()
  val mmUiState by modelManagerViewModel.uiState.collectAsState()

  var hasCameraPerm by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
    )
  }
  val camPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    hasCameraPerm = it
  }
  LaunchedEffect(Unit) {
    if (!hasCameraPerm) camPermLauncher.launch(Manifest.permission.CAMERA)
  }

  var ttsReady by remember { mutableStateOf(false) }
  val tts = remember {
    var instance: TextToSpeech? = null
    instance = TextToSpeech(context) { st ->
      if (st == TextToSpeech.SUCCESS) {
        instance?.language = Locale.US
        ttsReady = true
      }
    }
    instance
  }
  val textRecognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
  val imageCapture = remember { ImageCapture.Builder().build() }

  DisposableEffect(Unit) {
    onDispose {
      runCatching { tts.stop() }
      runCatching { tts.shutdown() }
      runCatching { textRecognizer.close() }
    }
  }

  fun speak(text: String, queue: Boolean = false) {
    val clean = text.trim()
    if (clean.isEmpty() || !ttsReady) return
    tts.speak(
      clean,
      if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH,
      null,
      "assist-${System.currentTimeMillis()}",
    )
  }

  var mode by remember { mutableStateOf(BoxAssistMode.LIVE) }
  var detections by remember { mutableStateOf<List<AssistDetection>>(emptyList()) }
  var frameSize by remember { mutableStateOf(640 to 480) }
  var lastSpokenText by remember { mutableStateOf("Point the camera ahead.") }
  var customQuestion by remember { mutableStateOf("") }
  var isBusy by remember { mutableStateOf(false) }
  var lastLiveSpokenAt by remember { mutableStateOf(0L) }
  var latestFrame by remember { mutableStateOf<Bitmap?>(null) }

  // Find installed multimodal VLM if user downloaded one
  val installedVlm: Model? = remember(mmUiState) {
    mmUiState.tasks.flatMap { it.models }.firstOrNull { cand ->
      cand.llmSupportImage &&
        mmUiState.modelDownloadStatus[cand.name]?.status == ModelDownloadStatusType.SUCCEEDED
    }
  }

  val engine = model.instance as? YoloxMidasEngine

  val runReadText: (Bitmap) -> Unit = { bmp ->
    isBusy = true
    scope.launch {
      try {
        val result = textRecognizer.process(InputImage.fromBitmap(bmp, 0)).awaitGmsTask()
        val txt = result.text.trim()
        lastSpokenText = txt.ifEmpty { "No readable text found." }
        speak(lastSpokenText)
      } catch (t: Throwable) {
        lastSpokenText = "OCR error: ${t.message}"
        speak(lastSpokenText)
      } finally {
        isBusy = false
      }
    }
  }

  val runDescribe: (Bitmap, String) -> Unit = { bmp, question ->
    val vlm = installedVlm
    if (vlm == null) {
      val fallback = detections.take(3).joinToString(", ") {
        "${it.label} ${it.depthBand.phrase} ${it.positionHint}".trim()
      }.ifEmpty { "Download a Gemma vision model in AI Chat / Ask Image to enable full scene Q&A." }
      lastSpokenText = fallback
      speak(fallback)
    } else {
      isBusy = true
      lastSpokenText = "Analyzing scene…"
      scope.launch(Dispatchers.IO) {
        var initOk = vlm.instance != null
        if (!initOk) {
          val msg = suspendCancellableCoroutine<String> { cont ->
            vlm.runtimeHelper.initialize(
              context = context,
              model = vlm,
              supportImage = true,
              supportAudio = false,
              onDone = { err -> cont.resume(err) },
              coroutineScope = scope,
            )
          }
          initOk = msg.isEmpty() && vlm.instance != null
          if (!initOk) {
            withContext(Dispatchers.Main) {
              lastSpokenText = "Vision model failed: $msg"
              speak(lastSpokenText)
              isBusy = false
            }
            return@launch
          }
        }
        val sb = StringBuilder()
        var spokenCursor = 0
        val prompt = question.ifBlank {
          "Describe what is directly in front of the user concisely in 2 short sentences."
        }
        vlm.runtimeHelper.runInference(
          model = vlm,
          input = prompt,
          resultListener = { partial, done ->
            sb.append(partial)
            val full = sb.toString()
            lastSpokenText = full
            val boundary = maxOf(
              full.lastIndexOf('.'),
              full.lastIndexOf('!'),
              full.lastIndexOf('?'),
            )
            if (boundary > spokenCursor + 8) {
              val chunk = full.substring(spokenCursor, boundary + 1)
              spokenCursor = boundary + 1
              speak(chunk, queue = true)
            }
            if (done) {
              val tail = full.substring(spokenCursor).trim()
              if (tail.isNotEmpty()) speak(tail, queue = true)
              isBusy = false
            }
          },
          cleanUpListener = {},
          onError = { err ->
            lastSpokenText = "Error: $err"
            speak(lastSpokenText)
            isBusy = false
          },
          images = listOf(bmp),
          coroutineScope = scope,
        )
      }
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(bottom = bottomPadding)
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // Mode chips
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      BoxAssistMode.entries.forEach { m ->
        FilterChip(
          selected = mode == m,
          onClick = {
            mode = m
            speak("${m.label} mode")
          },
          label = { Text(m.label) },
          modifier = Modifier.weight(1f),
        )
      }
    }

    // Camera viewfinder + detection overlay
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .clip(RoundedCornerShape(16.dp))
        .background(Color.Black),
    ) {
      if (!hasCameraPerm) {
        Button(
          onClick = { camPermLauncher.launch(Manifest.permission.CAMERA) },
          modifier = Modifier.align(Alignment.Center),
        ) {
          Text("Grant Camera Permission")
        }
      } else {
        AndroidView(
          factory = { ctx ->
            PreviewView(ctx).also { previewView ->
              val executor = Executors.newSingleThreadExecutor()
              val future = ProcessCameraProvider.getInstance(ctx)
              future.addListener(
                {
                  val provider = future.get()
                  val preview = Preview.Builder().build().apply {
                    surfaceProvider = previewView.surfaceProvider
                  }
                  var lastAnalyzeAt = 0L
                  val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .apply {
                      setAnalyzer(executor) { proxy ->
                        val now = System.currentTimeMillis()
                        if (mode == BoxAssistMode.LIVE && engine != null && now - lastAnalyzeAt >= 450L) {
                          lastAnalyzeAt = now
                          val raw = proxy.toBitmap()
                          val rot = proxy.imageInfo.rotationDegrees
                          val bmp = if (rot != 0) {
                            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                          } else raw
                          latestFrame = bmp
                          frameSize = bmp.width to bmp.height
                          scope.launch {
                            val res = engine.detectAndEstimateDepth(bmp)
                            detections = res
                            val t = System.currentTimeMillis()
                            if (res.isNotEmpty() && t - lastLiveSpokenAt >= 2600L) {
                              lastLiveSpokenAt = t
                              val top = res.first()
                              val article = if (top.label.firstOrNull()?.lowercaseChar() in listOf('a', 'e', 'i', 'o', 'u')) "an" else "a"
                              val phrase = buildString {
                                append(article).append(' ').append(top.label)
                                if (top.depthBand.phrase.isNotEmpty()) append(", ").append(top.depthBand.phrase)
                                append(' ').append(top.positionHint)
                              }
                              lastSpokenText = phrase
                              speak(phrase)
                            }
                          }
                        }
                        proxy.close()
                      }
                    }
                  runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                      lifecycleOwner,
                      CameraSelector.DEFAULT_BACK_CAMERA,
                      preview,
                      imageCapture,
                      analyzer,
                    )
                  }
                },
                ContextCompat.getMainExecutor(ctx),
              )
            }
          },
          modifier = Modifier.fillMaxSize(),
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
          val (fw, fh) = frameSize
          val sx = size.width / fw.coerceAtLeast(1)
          val sy = size.height / fh.coerceAtLeast(1)
          detections.forEach { d ->
            val col = when (d.depthBand) {
              DepthBand.VERY_CLOSE -> Color(0xFFFF5252)
              DepthBand.CLOSE -> Color(0xFFFFD740)
              else -> Color(0xFF00E5FF)
            }
            drawRect(
              color = col,
              topLeft = Offset(d.box.left * sx, d.box.top * sy),
              size = Size(d.box.width() * sx, d.box.height() * sy),
              style = Stroke(width = 4f),
            )
          }
        }
      }
    }

    if (isBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

    // Spoken caption card
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
      Row(
        modifier = Modifier.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = lastSpokenText,
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { speak(lastSpokenText) }) {
          Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "Repeat")
        }
        IconButton(onClick = { tts.stop() }) {
          Icon(Icons.Rounded.Stop, contentDescription = "Stop")
        }
      }
    }

    if (mode == BoxAssistMode.DESCRIBE) {
      OutlinedTextField(
        value = customQuestion,
        onValueChange = { customQuestion = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Optional question about what's ahead…") },
        singleLine = true,
      )
    }

    if (mode != BoxAssistMode.LIVE) {
      Button(
        onClick = {
          imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
              override fun onCaptureSuccess(image: ImageProxy) {
                val raw = image.toBitmap()
                val rot = image.imageInfo.rotationDegrees
                val bmp = if (rot != 0) {
                  Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
                } else raw
                image.close()
                if (mode == BoxAssistMode.READING) runReadText(bmp) else runDescribe(bmp, customQuestion)
              }

              override fun onError(exception: ImageCaptureException) {
                lastSpokenText = "Capture failed: ${exception.message}"
                speak(lastSpokenText)
              }
            },
          )
        },
        enabled = !isBusy && hasCameraPerm,
        modifier = Modifier.fillMaxWidth().height(54.dp),
      ) {
        Icon(
          if (mode == BoxAssistMode.READING) Icons.Rounded.MenuBook else Icons.Rounded.AutoAwesome,
          contentDescription = null,
        )
        Spacer(Modifier.width(8.dp))
        Text(if (mode == BoxAssistMode.READING) "Capture & Read Aloud" else "Capture & Describe")
      }
    }
  }
}
