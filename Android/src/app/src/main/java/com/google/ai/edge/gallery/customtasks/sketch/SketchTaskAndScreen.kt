package com.google.ai.edge.gallery.customtasks.sketch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
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
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.google.ai.edge.litert.CompiledModel
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
import javax.inject.Inject

private const val TAG = "SketchEngine"

class SketchEngine {
  @Volatile var compiledModel: CompiledModel? = null

  fun load(path: String): Boolean {
    return try {
      close()
      compiledModel = CvUtils.loadCompiledModel(path)
      true
    } catch (t: Throwable) {
      Log.e(TAG, "loadModel failed", t)
      false
    }
  }

  suspend fun sketch(source: Bitmap, onProgress: (Float) -> Unit): Bitmap? =
    withContext(Dispatchers.Default) {
      val model = compiledModel ?: return@withContext null
      try {
        onProgress(0.1f)
        val scaled = Bitmap.createScaledBitmap(source, 512, 512, true)
        val n = 512 * 512
        val pixels = IntArray(n)
        scaled.getPixels(pixels, 0, 512, 0, 0, 512, 512)
        var maxCh = 0
        for (px in pixels) {
          val r = (px ushr 16) and 0xFF
          val g = (px ushr 8) and 0xFF
          val b = px and 0xFF
          if (r > maxCh) maxCh = r
          if (g > maxCh) maxCh = g
          if (b > maxCh) maxCh = b
        }
        val denom = if (maxCh == 0) 1f else maxCh.toFloat()
        val inputNchw = FloatArray(n * 3)
        for (i in 0 until n) {
          val px = pixels[i]
          inputNchw[i] = ((((px ushr 16) and 0xFF) / denom) - 0.485f) / 0.229f
          inputNchw[n + i] = ((((px ushr 8) and 0xFF) / denom) - 0.456f) / 0.224f
          inputNchw[2 * n + i] = (((px and 0xFF) / denom) - 0.406f) / 0.225f
        }
        val inBufs = model.createInputBuffers()
        val outBufs = model.createOutputBuffers()
        val raw = try {
          inBufs[0].writeFloat(inputNchw)
          model.run(inBufs, outBufs)
          outBufs[0].readFloat()
        } finally {
          CvUtils.closeBuffers(inBufs)
          CvUtils.closeBuffers(outBufs)
        }
        onProgress(0.75f)
        var minV = Float.MAX_VALUE
        var maxV = -Float.MAX_VALUE
        for (i in 0 until minOf(n, raw.size)) {
          if (raw[i] < minV) minV = raw[i]
          if (raw[i] > maxV) maxV = raw[i]
        }
        val span = (maxV - minV).takeIf { it > 1e-6f } ?: 1f
        val outPx = IntArray(n) { i ->
          val gray = ((1f - ((raw[i] - minV) / span)).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
          Color.rgb(gray, gray, gray)
        }
        val sketch512 = Bitmap.createBitmap(outPx, 512, 512, Bitmap.Config.ARGB_8888)
        onProgress(1f)
        Bitmap.createScaledBitmap(sketch512, source.width, source.height, true)
      } catch (t: Throwable) {
        Log.e(TAG, "sketch failed", t)
        null
      }
    }

  fun close() {
    runCatching { compiledModel?.close() }
    compiledModel = null
  }
}

data class SketchUiState(
  val sourceBitmap: Bitmap? = null,
  val resultBitmap: Bitmap? = null,
  val isProcessing: Boolean = false,
  val progress: Float = 0f,
  val saved: Boolean = false,
  val errorMessage: String? = null,
)

@HiltViewModel
class SketchViewModel @Inject constructor() : ViewModel() {
  private val _uiState = MutableStateFlow(SketchUiState())
  val uiState: StateFlow<SketchUiState> = _uiState.asStateFlow()

  fun processUri(context: Context, uri: Uri, model: Model) {
    val engine = model.instance as? SketchEngine ?: run {
      _uiState.update { it.copy(errorMessage = "Model not loaded") }
      return
    }
    val bmp = CvUtils.decodeScaledBitmap(context, uri, maxDim = 2048) ?: run {
      _uiState.update { it.copy(errorMessage = "Could not load image") }
      return
    }
    _uiState.update { it.copy(sourceBitmap = bmp, resultBitmap = null, isProcessing = true, progress = 0f, saved = false, errorMessage = null) }
    viewModelScope.launch {
      val out = engine.sketch(bmp) { p -> _uiState.update { it.copy(progress = p) } }
      _uiState.update {
        it.copy(
          isProcessing = false,
          resultBitmap = out,
          errorMessage = if (out == null) "Sketch generation failed" else null,
        )
      }
    }
  }

  fun save(context: Context) {
    val bmp = _uiState.value.resultBitmap ?: return
    viewModelScope.launch {
      val ok = CvUtils.saveBitmapToPictures(context, bmp, "Sketches", "sketch")
      _uiState.update { it.copy(saved = ok, errorMessage = if (!ok) "Save failed" else null) }
    }
  }
}

class SketchTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.SKETCH,
      label = "Sketch",
      category = Category.IMAGE_GEN,
      icon = Icons.Outlined.Brush,
      models =
        mutableListOf(
          Model(
            name = "Portrait Sketch",
            info = "Redraws a portrait photo as a pencil line drawing. Works best on a single well-lit face (168 MB · Apache-2.0).",
            url = "https://huggingface.co/litert-community/U2Net-Portrait-Sketch-LiteRT/resolve/2a684be57bc7cc104806868f89ff561f81a2e172/portrait.tflite?download=true",
            sizeInBytes = 176_124_376L,
            downloadFileName = "portrait.tflite",
            version = "2a684be57bc7cc104806868f89ff561f81a2e172",
          )
        ),
      description =
        "Turn a portrait photo into a pencil line drawing, on-device and fully offline. Works best on a single, well-lit face looking at the camera.",
      shortDescription = "Portrait photo to pencil sketch",
    )

  override fun initializeModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: (String) -> Unit) {
    coroutineScope.launch(Dispatchers.Default) {
      val engine = SketchEngine()
      if (engine.load(model.getPath(context))) {
        model.instance = engine
        onDone("")
      } else {
        onDone("Failed to load Portrait Sketch")
      }
    }
  }

  override fun cleanUpModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: () -> Unit) {
    (model.instance as? SketchEngine)?.close()
    model.instance = null
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    SketchScreen(modelManagerViewModel = myData.modelManagerViewModel)
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object SketchTaskModule {
  @Provides @IntoSet fun provideTask(): CustomTask = SketchTask()
}

@Composable
fun SketchScreen(
  modelManagerViewModel: ModelManagerViewModel,
  viewModel: SketchViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsState()
  val mmState by modelManagerViewModel.uiState.collectAsState()
  val model = mmState.selectedModel
  val ready = mmState.modelInitializationStatus[model.name]?.status == ModelInitializationStatusType.INITIALIZED
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) viewModel.processUri(context, uri, model)
  }

  if (!ready) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("Download and initialize Portrait Sketch to draw photos as sketches.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
      Text("Pick portrait")
    }
    if (state.isProcessing) {
      LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
    }
    state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    val shown = state.resultBitmap ?: state.sourceBitmap
    if (shown != null && !state.isProcessing) {
      Image(bitmap = shown.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(360.dp))
    }
    state.resultBitmap?.let { out ->
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { viewModel.save(context) }) {
          Icon(Icons.Outlined.Download, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text(if (state.saved) "Saved" else "Save PNG")
        }
        OutlinedButton(onClick = { scope.launch { CvUtils.shareBitmap(context, out, "sketch") } }) {
          Icon(Icons.Outlined.Share, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text("Share")
        }
      }
    }
  }
}
