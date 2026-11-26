package com.google.ai.edge.gallery.customtasks.identify

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.customtasks.cv.CvUtils
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import javax.inject.Inject
import kotlin.math.exp
import kotlin.math.roundToInt

private const val TAG = "IdentifyEngine"
private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

enum class IdentifyMode { MOBILENET, PLANTNET, DMCOUNT }

data class IdentifyPrediction(val label: String, val confidence: Float)

sealed interface IdentifyOutput {
  data class Labels(val predictions: List<IdentifyPrediction>) : IdentifyOutput
  data class CrowdCount(val estimatedPeople: Int, val rawSum: Float) : IdentifyOutput
}

class IdentifyEngine(private val mode: IdentifyMode, private val useImageNetNorm: Boolean) {
  @Volatile private var compiledModel: CompiledModel? = null
  @Volatile private var npuEnv: Environment? = null
  private var labels: List<String> = emptyList()

  fun load(context: Context, path: String): Boolean {
    return try {
      close()
      val isG5Npu = path.contains("g5", ignoreCase = true) || path.contains("tensor_g5", ignoreCase = true)
      compiledModel = if (isG5Npu) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        runCatching {
          val env = Environment.create(
            Environment.Option.DispatchLibraryDir to nativeDir,
            Environment.Option.CompilerPluginLibraryDir to nativeDir,
          )
          npuEnv = env
          CompiledModel.create(path, CompiledModel.Options(Accelerator.NPU), env)
        }.getOrElse { t ->
          Log.w(TAG, "NPU compile failed for $path; falling back to GPU/CPU", t)
          CvUtils.loadCompiledModel(path)
        }
      } else {
        CvUtils.loadCompiledModel(path)
      }
      labels = when (mode) {
        IdentifyMode.MOBILENET -> readAssetLines(context, "mobilenet_models/imagenet_labels.txt")
        IdentifyMode.PLANTNET -> readAssetLines(context, "plantrecognise_models/plant_labels.txt")
        IdentifyMode.DMCOUNT -> emptyList()
      }
      true
    } catch (t: Throwable) {
      Log.e(TAG, "loadModel failed", t)
      false
    }
  }

  suspend fun run(bitmap: Bitmap): IdentifyOutput? = withContext(Dispatchers.Default) {
    val model = compiledModel ?: return@withContext null
    try {
      when (mode) {
        IdentifyMode.DMCOUNT -> runDmCount(model, bitmap)
        IdentifyMode.PLANTNET -> runClassifier(model, centerCrop224(bitmap), imageNetNorm = true, prefix = "Species")
        IdentifyMode.MOBILENET -> runClassifier(model, Bitmap.createScaledBitmap(bitmap, 224, 224, true), imageNetNorm = useImageNetNorm, prefix = "Class")
      }
    } catch (t: Throwable) {
      Log.e(TAG, "inference failed", t)
      null
    }
  }

  private fun runClassifier(
    model: CompiledModel,
    bmp224: Bitmap,
    imageNetNorm: Boolean,
    prefix: String,
  ): IdentifyOutput.Labels {
    val n = 224 * 224
    val px = IntArray(n)
    bmp224.getPixels(px, 0, 224, 0, 0, 224, 224)
    val input = FloatArray(n * 3)
    for (i in 0 until n) {
      val c = px[i]
      val r = ((c ushr 16) and 0xFF)
      val g = ((c ushr 8) and 0xFF)
      val b = (c and 0xFF)
      if (imageNetNorm) {
        input[i] = (r / 255f - IMAGENET_MEAN[0]) / IMAGENET_STD[0]
        input[n + i] = (g / 255f - IMAGENET_MEAN[1]) / IMAGENET_STD[1]
        input[2 * n + i] = (b / 255f - IMAGENET_MEAN[2]) / IMAGENET_STD[2]
      } else {
        input[i] = r / 127.5f - 1f
        input[n + i] = g / 127.5f - 1f
        input[2 * n + i] = b / 127.5f - 1f
      }
    }
    val inBufs = model.createInputBuffers()
    val outBufs = model.createOutputBuffers()
    val raw = try {
      inBufs[0].writeFloat(input)
      model.run(inBufs, outBufs)
      outBufs[0].readFloat()
    } finally {
      CvUtils.closeBuffers(inBufs)
      CvUtils.closeBuffers(outBufs)
    }
    val probs = softmax(raw)
    val offset = if (probs.size == 1000 && labels.size == 1001) 1 else 0
    val top = probs.indices.sortedByDescending { probs[it] }.take(5)
    val predictions = top.map { idx ->
      val lbl = labels.getOrNull(idx + offset)?.trim().takeIf { !it.isNullOrEmpty() } ?: "$prefix $idx"
      IdentifyPrediction(lbl, probs[idx])
    }
    return IdentifyOutput.Labels(predictions)
  }

  private fun runDmCount(model: CompiledModel, source: Bitmap): IdentifyOutput.CrowdCount {
    val bmp512 = Bitmap.createScaledBitmap(source, 512, 512, true)
    val n = 512 * 512
    val px = IntArray(n)
    bmp512.getPixels(px, 0, 512, 0, 0, 512, 512)
    val input = FloatArray(n * 3)
    for (i in 0 until n) {
      val c = px[i]
      input[i] = (((c ushr 16) and 0xFF) / 255f - IMAGENET_MEAN[0]) / IMAGENET_STD[0]
      input[n + i] = (((c ushr 8) and 0xFF) / 255f - IMAGENET_MEAN[1]) / IMAGENET_STD[1]
      input[2 * n + i] = ((c and 0xFF) / 255f - IMAGENET_MEAN[2]) / IMAGENET_STD[2]
    }
    val inBufs = model.createInputBuffers()
    val outBufs = model.createOutputBuffers()
    val density = try {
      inBufs[0].writeFloat(input)
      model.run(inBufs, outBufs)
      outBufs[0].readFloat()
    } finally {
      CvUtils.closeBuffers(inBufs)
      CvUtils.closeBuffers(outBufs)
    }
    var sum = 0f
    for (v in density) {
      if (v > 0f) sum += v
    }
    return IdentifyOutput.CrowdCount(estimatedPeople = sum.roundToInt().coerceAtLeast(0), rawSum = sum)
  }

  private fun centerCrop224(src: Bitmap): Bitmap {
    val minSide = minOf(src.width, src.height)
    val cropped = Bitmap.createBitmap(src, (src.width - minSide) / 2, (src.height - minSide) / 2, minSide, minSide)
    return Bitmap.createScaledBitmap(cropped, 224, 224, true)
  }

  private fun softmax(logits: FloatArray): FloatArray {
    if (logits.isEmpty()) return logits
    val maxVal = logits.max()
    val exps = FloatArray(logits.size) { exp(logits[it] - maxVal) }
    val sum = exps.sum().takeIf { it > 0f } ?: 1f
    for (i in exps.indices) exps[i] /= sum
    return exps
  }

  private fun readAssetLines(context: Context, assetPath: String): List<String> =
    runCatching {
      BufferedReader(InputStreamReader(context.assets.open(assetPath))).use { it.readLines() }
    }.getOrDefault(emptyList())

  fun close() {
    runCatching { compiledModel?.close() }
    runCatching { npuEnv?.close() }
    compiledModel = null
    npuEnv = null
  }
}

data class IdentifyUiState(
  val sourceBitmap: Bitmap? = null,
  val output: IdentifyOutput? = null,
  val isProcessing: Boolean = false,
  val errorMessage: String? = null,
)

@HiltViewModel
class IdentifyViewModel @Inject constructor() : ViewModel() {
  private val _uiState = MutableStateFlow(IdentifyUiState())
  val uiState: StateFlow<IdentifyUiState> = _uiState.asStateFlow()

  fun analyzeUri(context: Context, uri: Uri, model: Model) {
    val engine = model.instance as? IdentifyEngine ?: run {
      _uiState.update { it.copy(errorMessage = "Model not loaded") }
      return
    }
    val bmp = CvUtils.decodeScaledBitmap(context, uri, maxDim = 2048) ?: run {
      _uiState.update { it.copy(errorMessage = "Could not load image") }
      return
    }
    _uiState.update { it.copy(sourceBitmap = bmp, output = null, isProcessing = true, errorMessage = null) }
    viewModelScope.launch {
      val res = engine.run(bmp)
      _uiState.update {
        it.copy(
          isProcessing = false,
          output = res,
          errorMessage = if (res == null) "Classification failed" else null,
        )
      }
    }
  }
}

class IdentifyTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.MOBILENET,
      label = "Identify",
      category = Category.EXPERIMENTAL,
      icon = Icons.Outlined.ImageSearch,
      models =
        mutableListOf(
          Model("MobileNet V2 (fast)", "Identifies 1,000 everyday objects (~3.9 MB, bundled).", "", 3_887_792L, "mobilenet_v2_int8.tflite", "v1.0.0"),
          Model("MobileNet V2 (Tensor G5 NPU)", "Pixel 10 Tensor G5 NPU build with automatic fallback (~8.4 MB, bundled).", "", 8_382_816L, "mobilenet_v2_g5.tflite", "v1.0.0"),
          Model("MobileNet V3 Large", "Higher accuracy across 1,000 ImageNet classes (~5.8 MB, bundled).", "", 5_808_416L, "mobilenet_v3_large_dynamic_wi8_afp32.tflite", "v1.0.0"),
          Model("MobileNet V3 Large (Tensor G5 NPU)", "Higher-accuracy Tensor G5 NPU build (~13.6 MB, bundled).", "", 13_619_632L, "mobilenet_v3_large_Google_Tensor_G5_apply_plugin.tflite", "v1.0.0"),
          Model("PlantNet-300K ResNet18", "Identifies 1,081 plant species with botanical names (~47 MB, bundled).", "", 46_942_600L, "plantnet.tflite", "v1.0.0"),
          Model("DM-Count Crowd", "Estimates crowd headcount in photos (~86 MB, bundled).", "", 86_027_724L, "dmcount.tflite", "v1.0.0"),
        ),
      description =
        "Identify what's in a photo on-device: 1000 ImageNet objects (MobileNet V2/V3), 1081 plant species (PlantNet-300K), or a crowd headcount (DM-Count). Fully offline.",
      shortDescription = "On-device image classification",
    )

  override fun initializeModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: (String) -> Unit) {
    coroutineScope.launch(Dispatchers.Default) {
      val file = model.downloadFileName
      val mode = when {
        file.contains("plantnet", ignoreCase = true) -> IdentifyMode.PLANTNET
        file.contains("dmcount", ignoreCase = true) -> IdentifyMode.DMCOUNT
        else -> IdentifyMode.MOBILENET
      }
      val engine = IdentifyEngine(mode = mode, useImageNetNorm = file.contains("v3", ignoreCase = true))
      if (engine.load(context, model.getPath(context))) {
        model.instance = engine
        onDone("")
      } else {
        onDone("Failed to load ${model.name}")
      }
    }
  }

  override fun cleanUpModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: () -> Unit) {
    (model.instance as? IdentifyEngine)?.close()
    model.instance = null
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    IdentifyScreen(modelManagerViewModel = myData.modelManagerViewModel)
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object IdentifyTaskModule {
  @Provides @IntoSet fun provideTask(): CustomTask = IdentifyTask()
}

@Composable
fun IdentifyScreen(
  modelManagerViewModel: ModelManagerViewModel,
  viewModel: IdentifyViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsState()
  val mmState by modelManagerViewModel.uiState.collectAsState()
  val model = mmState.selectedModel
  val ready = mmState.modelInitializationStatus[model.name]?.status == ModelInitializationStatusType.INITIALIZED
  val context = LocalContext.current
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) viewModel.analyzeUri(context, uri, model)
  }

  if (!ready) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("Initialize an Identify model to classify photos or count crowds.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    return
  }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
      Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
      Spacer(Modifier.width(8.dp))
      Text("Pick photo")
    }

    state.sourceBitmap?.let { bmp ->
      Image(bitmap = bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(280.dp))
    }

    if (state.isProcessing) {
      CircularProgressIndicator()
    }

    state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    when (val out = state.output) {
      is IdentifyOutput.CrowdCount -> {
        OutlinedCard(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${out.estimatedPeople}", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("estimated people", style = MaterialTheme.typography.titleMedium)
          }
        }
      }
      is IdentifyOutput.Labels -> {
        OutlinedCard(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            out.predictions.forEach { pred ->
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                  Text(pred.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                  Text("${(pred.confidence * 100f).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                LinearProgressIndicator(progress = { pred.confidence.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
              }
            }
          }
        }
      }
      null -> {}
    }
  }
}
