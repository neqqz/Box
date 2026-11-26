package com.google.ai.edge.gallery.customtasks.dewarp

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
import androidx.compose.material.icons.outlined.DocumentScanner
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

private const val TAG = "DewarpEngine"

class DewarpEngine {
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

  suspend fun dewarp(source: Bitmap, onProgress: (Float) -> Unit): Bitmap? =
    withContext(Dispatchers.Default) {
      val model = compiledModel ?: return@withContext null
      try {
        onProgress(0.1f)
        val scaled256 = Bitmap.createScaledBitmap(source, 256, 256, true)
        val n256 = 256 * 256
        val pixels = IntArray(n256)
        scaled256.getPixels(pixels, 0, 256, 0, 0, 256, 256)
        // BGR [0..1] NCHW input matching DewarpNet training
        val inputNchw = FloatArray(n256 * 3)
        for (i in 0 until n256) {
          val px = pixels[i]
          inputNchw[i] = (px and 0xFF) / 255f
          inputNchw[n256 + i] = ((px ushr 8) and 0xFF) / 255f
          inputNchw[2 * n256 + i] = ((px ushr 16) and 0xFF) / 255f
        }
        val inBufs = model.createInputBuffers()
        val outBufs = model.createOutputBuffers()
        val flowRaw = try {
          inBufs[0].writeFloat(inputNchw)
          model.run(inBufs, outBufs)
          outBufs[0].readFloat()
        } finally {
          CvUtils.closeBuffers(inBufs)
          CvUtils.closeBuffers(outBufs)
        }
        onProgress(0.5f)
        // Box-smooth 128x128 2-channel flow field with 3x3 average filter
        val smoothed = FloatArray(flowRaw.size)
        for (ch in 0 until 2) {
          val offset = ch shl 14 // 128 * 128 = 16384
          for (y in 0 until 128) {
            for (x in 0 until 128) {
              var acc = 0f
              var cnt = 0
              for (dy in -1..1) {
                val yy = (y + dy).coerceIn(0, 127)
                for (dx in -1..1) {
                  acc += flowRaw[offset + (yy shl 7) + (x + dx).coerceIn(0, 127)]
                  cnt++
                }
              }
              smoothed[offset + (y shl 7) + x] = acc / cnt
            }
          }
        }
        onProgress(0.6f)
        val out = warpByFlow(source, smoothed)
        onProgress(1f)
        out
      } catch (t: Throwable) {
        Log.e(TAG, "dewarp failed", t)
        null
      }
    }

  private fun warpByFlow(src: Bitmap, flow: FloatArray): Bitmap {
    val w = src.width
    val h = src.height
    val inPx = IntArray(w * h)
    src.getPixels(inPx, 0, w, 0, 0, w, h)
    val outPx = IntArray(w * h)

    fun sampleGrid(chOffset: Int, gx: Int, gx1: Int, gy: Int, gy1: Int, fx: Float, fy: Float): Float {
      val v00 = flow[chOffset + (gy shl 7) + gx]
      val v10 = flow[chOffset + (gy shl 7) + gx1]
      val v01 = flow[chOffset + (gy1 shl 7) + gx]
      val v11 = flow[chOffset + (gy1 shl 7) + gx1]
      val top = v00 + (v10 - v00) * fx
      val bot = v01 + (v11 - v01) * fx
      return top + (bot - top) * fy
    }

    fun bilerpCh(p00: Int, p10: Int, p01: Int, p11: Int, fx: Float, fy: Float, shift: Int): Int {
      val c00 = (p00 ushr shift) and 0xFF
      val c10 = (p10 ushr shift) and 0xFF
      val c01 = (p01 ushr shift) and 0xFF
      val c11 = (p11 ushr shift) and 0xFF
      val top = c00 + (c10 - c00) * fx
      val bot = c01 + (c11 - c01) * fx
      return (top + (bot - top) * fy).toInt().coerceIn(0, 255)
    }

    for (y in 0 until h) {
      val gfY = if (h == 1) 0f else y * 127f / (h - 1)
      val gy = gfY.toInt().coerceIn(0, 127)
      val gy1 = (gy + 1).coerceAtMost(127)
      val fy = gfY - gy
      for (x in 0 until w) {
        val gfX = if (w == 1) 0f else x * 127f / (w - 1)
        val gx = gfX.toInt().coerceIn(0, 127)
        val gx1 = (gx + 1).coerceAtMost(127)
        val fx = gfX - gx

        val sx = (sampleGrid(0, gx, gx1, gy, gy1, fx, fy) + 1f) * 0.5f * (w - 1)
        val sy = (sampleGrid(16384, gx, gx1, gy, gy1, fx, fy) + 1f) * 0.5f * (h - 1)
        outPx[y * w + x] =
          if (sx < 0f || sy < 0f || sx > w - 1f || sy > h - 1f) {
            Color.BLACK
          } else {
            val ix = sx.toInt().coerceIn(0, w - 1)
            val iy = sy.toInt().coerceIn(0, h - 1)
            val ix1 = (ix + 1).coerceAtMost(w - 1)
            val iy1 = (iy + 1).coerceAtMost(h - 1)
            val dx = sx - ix
            val dy = sy - iy
            val p00 = inPx[iy * w + ix]
            val p10 = inPx[iy * w + ix1]
            val p01 = inPx[iy1 * w + ix]
            val p11 = inPx[iy1 * w + ix1]
            Color.rgb(
              bilerpCh(p00, p10, p01, p11, dx, dy, 16),
              bilerpCh(p00, p10, p01, p11, dx, dy, 8),
              bilerpCh(p00, p10, p01, p11, dx, dy, 0),
            )
          }
      }
    }
    return Bitmap.createBitmap(outPx, w, h, Bitmap.Config.ARGB_8888)
  }

  fun close() {
    runCatching { compiledModel?.close() }
    compiledModel = null
  }
}

data class DewarpUiState(
  val sourceBitmap: Bitmap? = null,
  val resultBitmap: Bitmap? = null,
  val isProcessing: Boolean = false,
  val progress: Float = 0f,
  val saved: Boolean = false,
  val errorMessage: String? = null,
)

@HiltViewModel
class DewarpViewModel @Inject constructor() : ViewModel() {
  private val _uiState = MutableStateFlow(DewarpUiState())
  val uiState: StateFlow<DewarpUiState> = _uiState.asStateFlow()

  fun processUri(context: Context, uri: Uri, model: Model) {
    val engine = model.instance as? DewarpEngine ?: run {
      _uiState.update { it.copy(errorMessage = "Model not loaded") }
      return
    }
    val bmp = CvUtils.decodeScaledBitmap(context, uri, maxDim = 2048) ?: run {
      _uiState.update { it.copy(errorMessage = "Could not load image") }
      return
    }
    _uiState.update { it.copy(sourceBitmap = bmp, resultBitmap = null, isProcessing = true, progress = 0f, saved = false, errorMessage = null) }
    viewModelScope.launch {
      val out = engine.dewarp(bmp) { p -> _uiState.update { it.copy(progress = p) } }
      _uiState.update {
        it.copy(
          isProcessing = false,
          resultBitmap = out,
          errorMessage = if (out == null) "Flatten failed" else null,
        )
      }
    }
  }

  fun save(context: Context) {
    val bmp = _uiState.value.resultBitmap ?: return
    viewModelScope.launch {
      val ok = CvUtils.saveBitmapToPictures(context, bmp, "Flattened", "flattened")
      _uiState.update { it.copy(saved = ok, errorMessage = if (!ok) "Save failed" else null) }
    }
  }
}

class DewarpTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.DEWARP,
      label = "Flatten",
      category = Category.EXPERIMENTAL,
      icon = Icons.Outlined.DocumentScanner,
      models =
        mutableListOf(
          Model(
            name = "DewarpNet",
            info = "Flattens a photo of a curved or crumpled page so the text sits straight again (180 MB · MIT).",
            url = "https://huggingface.co/litert-community/DewarpNet-LiteRT/resolve/63b5b2342e7ed0268537b198bb1ba66db6c2eb90/dewarp.tflite?download=true",
            sizeInBytes = 188_852_928L,
            downloadFileName = "dewarp.tflite",
            version = "63b5b2342e7ed0268537b198bb1ba66db6c2eb90",
          )
        ),
      description =
        "Straighten a photo of a curved or crumpled page so the text lies flat again — handy for receipts, book pages and forms shot at an angle.",
      shortDescription = "Flatten photographed documents",
    )

  override fun initializeModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: (String) -> Unit) {
    coroutineScope.launch(Dispatchers.Default) {
      val engine = DewarpEngine()
      if (engine.load(model.getPath(context))) {
        model.instance = engine
        onDone("")
      } else {
        onDone("Failed to load DewarpNet")
      }
    }
  }

  override fun cleanUpModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: () -> Unit) {
    (model.instance as? DewarpEngine)?.close()
    model.instance = null
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    DewarpScreen(modelManagerViewModel = myData.modelManagerViewModel)
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object DewarpTaskModule {
  @Provides @IntoSet fun provideTask(): CustomTask = DewarpTask()
}

@Composable
fun DewarpScreen(
  modelManagerViewModel: ModelManagerViewModel,
  viewModel: DewarpViewModel = hiltViewModel(),
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
      Text("Download and initialize DewarpNet to flatten photos.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
      Text("Pick document photo")
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
        OutlinedButton(onClick = { scope.launch { CvUtils.shareBitmap(context, out, "flattened") } }) {
          Icon(Icons.Outlined.Share, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text("Share")
        }
      }
    }
  }
}
