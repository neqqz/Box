package com.google.ai.edge.gallery.customtasks.upscale

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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.HighQuality
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

private const val TAG = "UpscaleEngine"
private const val IN_TILE = 128
private const val IN_OVERLAP = 16
private const val IN_MARGIN = 8
private const val IN_STRIDE = IN_TILE - IN_OVERLAP
private const val OUT_TILE = 512

class UpscaleEngine {
  @Volatile var compiledModel: CompiledModel? = null
  @Volatile var isNhwc: Boolean = true

  fun load(path: String): Boolean {
    return try {
      close()
      val isEdsr = path.contains("edsr", ignoreCase = true)
      isNhwc = !isEdsr
      compiledModel = CvUtils.loadCompiledModel(path, gpuOnly = isEdsr)
      true
    } catch (t: Throwable) {
      Log.e(TAG, "loadModel failed", t)
      false
    }
  }

  suspend fun upscale(source: Bitmap, onProgress: (Float) -> Unit): Bitmap? =
    withContext(Dispatchers.Default) {
      val model = compiledModel ?: return@withContext null
      try {
        val capped = if (maxOf(source.width, source.height) > 1024) {
          val scale = 1024f / maxOf(source.width, source.height)
          Bitmap.createScaledBitmap(source, (source.width * scale).toInt(), (source.height * scale).toInt(), true)
        } else {
          source
        }
        val w = capped.width
        val h = capped.height

        if (w <= IN_TILE && h <= IN_TILE) {
          val raw = runTile(model, Bitmap.createScaledBitmap(capped, IN_TILE, IN_TILE, true)) ?: return@withContext null
          val tileBmp = tileToBitmap(raw)
          onProgress(1f)
          return@withContext Bitmap.createScaledBitmap(tileBmp, w shl 2, h shl 2, true)
        }

        val outBmp = Bitmap.createBitmap(w shl 2, h shl 2, Bitmap.Config.ARGB_8888)
        val xs = tileOffsets(w)
        val ys = tileOffsets(h)
        val total = xs.size * ys.size
        var done = 0
        for (y in ys) {
          for (x in xs) {
            val raw = runTile(model, Bitmap.createBitmap(capped, x, y, IN_TILE, IN_TILE))
            if (raw != null) {
              val left = if (x == 0) 0 else IN_MARGIN
              val top = if (y == 0) 0 else IN_MARGIN
              val right = if (x + IN_TILE >= w) 0 else IN_MARGIN
              val bottom = if (y + IN_TILE >= h) 0 else IN_MARGIN
              blitScaledRegion(
                raw = raw,
                target = outBmp,
                dstX = (x + left) shl 2,
                dstY = (y + top) shl 2,
                srcX = left shl 2,
                srcY = top shl 2,
                width = (IN_TILE - left - right) shl 2,
                height = (IN_TILE - top - bottom) shl 2,
              )
            }
            done++
            onProgress(done.toFloat() / total)
          }
        }
        outBmp
      } catch (t: Throwable) {
        Log.e(TAG, "upscale failed", t)
        null
      }
    }

  private fun tileOffsets(limit: Int): List<Int> {
    if (limit <= IN_TILE) return listOf(0)
    val res = mutableListOf<Int>()
    var cur = 0
    while (true) {
      res.add(cur)
      if (cur + IN_TILE >= limit) break
      cur = minOf(cur + IN_STRIDE, limit - IN_TILE)
    }
    return res.distinct()
  }

  private fun runTile(model: CompiledModel, tile: Bitmap): FloatArray? {
    val n = IN_TILE * IN_TILE
    val px = IntArray(n)
    tile.getPixels(px, 0, IN_TILE, 0, 0, IN_TILE, IN_TILE)
    val input = FloatArray(n * 3)
    if (isNhwc) {
      for (i in 0 until n) {
        val c = px[i]
        val base = i * 3
        input[base] = ((c ushr 16) and 0xFF) / 255f
        input[base + 1] = ((c ushr 8) and 0xFF) / 255f
        input[base + 2] = (c and 0xFF) / 255f
      }
    } else {
      for (i in 0 until n) {
        val c = px[i]
        input[i] = ((c ushr 16) and 0xFF) / 255f
        input[n + i] = ((c ushr 8) and 0xFF) / 255f
        input[2 * n + i] = (c and 0xFF) / 255f
      }
    }
    val inBufs = model.createInputBuffers()
    val outBufs = model.createOutputBuffers()
    return try {
      inBufs[0].writeFloat(input)
      model.run(inBufs, outBufs)
      outBufs[0].readFloat()
    } finally {
      CvUtils.closeBuffers(inBufs)
      CvUtils.closeBuffers(outBufs)
    }
  }

  private fun toByte(v: Float): Int = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

  private fun tileToBitmap(raw: FloatArray): Bitmap {
    val n = OUT_TILE * OUT_TILE
    val px = IntArray(n)
    if (isNhwc) {
      for (i in 0 until n) {
        val base = i * 3
        px[i] = Color.rgb(toByte(raw[base]), toByte(raw[base + 1]), toByte(raw[base + 2]))
      }
    } else {
      for (i in 0 until n) {
        px[i] = Color.rgb(toByte(raw[i]), toByte(raw[n + i]), toByte(raw[2 * n + i]))
      }
    }
    return Bitmap.createBitmap(px, OUT_TILE, OUT_TILE, Bitmap.Config.ARGB_8888)
  }

  private fun blitScaledRegion(
    raw: FloatArray,
    target: Bitmap,
    dstX: Int,
    dstY: Int,
    srcX: Int,
    srcY: Int,
    width: Int,
    height: Int,
  ) {
    if (width <= 0 || height <= 0) return
    val n = OUT_TILE * OUT_TILE
    val rowBuf = IntArray(width)
    for (row in 0 until height) {
      val sy = srcY + row
      for (col in 0 until width) {
        val idx = (sy shl 9) + srcX + col
        rowBuf[col] = if (isNhwc) {
          val base = idx * 3
          Color.rgb(toByte(raw[base]), toByte(raw[base + 1]), toByte(raw[base + 2]))
        } else {
          Color.rgb(toByte(raw[idx]), toByte(raw[n + idx]), toByte(raw[2 * n + idx]))
        }
      }
      target.setPixels(rowBuf, 0, width, dstX, dstY + row, width, 1)
    }
  }

  fun close() {
    runCatching { compiledModel?.close() }
    compiledModel = null
  }
}

data class UpscaleUiState(
  val sourceBitmap: Bitmap? = null,
  val resultBitmap: Bitmap? = null,
  val isProcessing: Boolean = false,
  val progress: Float = 0f,
  val saved: Boolean = false,
  val errorMessage: String? = null,
)

@HiltViewModel
class UpscaleViewModel @Inject constructor() : ViewModel() {
  private val _uiState = MutableStateFlow(UpscaleUiState())
  val uiState: StateFlow<UpscaleUiState> = _uiState.asStateFlow()

  fun processUri(context: Context, uri: Uri, model: Model) {
    val engine = model.instance as? UpscaleEngine ?: run {
      _uiState.update { it.copy(errorMessage = "Model not loaded") }
      return
    }
    val bmp = CvUtils.decodeScaledBitmap(context, uri, maxDim = 2048) ?: run {
      _uiState.update { it.copy(errorMessage = "Could not load image") }
      return
    }
    _uiState.update { it.copy(sourceBitmap = bmp, resultBitmap = null, isProcessing = true, progress = 0f, saved = false, errorMessage = null) }
    viewModelScope.launch {
      val out = engine.upscale(bmp) { p -> _uiState.update { it.copy(progress = p) } }
      _uiState.update {
        it.copy(
          isProcessing = false,
          resultBitmap = out,
          errorMessage = if (out == null) "Upscaling failed" else null,
        )
      }
    }
  }

  fun save(context: Context) {
    val bmp = _uiState.value.resultBitmap ?: return
    viewModelScope.launch {
      val ok = CvUtils.saveBitmapToPictures(context, bmp, "Upscaled", "upscaled")
      _uiState.update { it.copy(saved = ok, errorMessage = if (!ok) "Save failed" else null) }
    }
  }
}

class UpscaleTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.UPSCALE,
      label = "Upscale",
      category = Category.EXPERIMENTAL,
      icon = Icons.Outlined.HighQuality,
      models =
        mutableListOf(
          Model(name = "XLSR (fast, 4×)", displayName = "Fastest 4× super-resolution (~118 KB, bundled).", info = "", sizeInBytes = 118_352L, downloadFileName = "xlsr.tflite", version = "v0.54.0"),
          Model(name = "Real-ESRGAN General (balanced, 4×)", displayName = "Balanced quality and speed (~4.9 MB, bundled).", info = "", sizeInBytes = 4_878_684L, downloadFileName = "real_esrgan_general_x4v3.tflite", version = "v0.54.0"),
          Model(name = "Real-ESRGAN x4plus (quality, 4×)", displayName = "Highest-quality 4× upscaling (~67 MB, bundled).", info = "", sizeInBytes = 67_031_032L, downloadFileName = "real_esrgan_x4plus.tflite", version = "v0.54.0"),
          Model(name = "EDSR x4 (GPU, 4×)", displayName = "Requires GPU delegate (~7.7 MB, bundled).", info = "", sizeInBytes = 7_714_836L, downloadFileName = "edsr.tflite", version = "v1.0.0"),
        ),
      description =
        "Enhance and enlarge any photo on-device with AI super-resolution (4×). Pick an image, upscale it, and save the result — fully offline.",
      shortDescription = "AI image upscaling (super-resolution)",
    )

  override fun initializeModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: (String) -> Unit) {
    coroutineScope.launch(Dispatchers.Default) {
      val engine = UpscaleEngine()
      if (engine.load(model.getPath(context))) {
        model.instance = engine
        onDone("")
      } else {
        onDone("Failed to load ${model.name}")
      }
    }
  }

  override fun cleanUpModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: () -> Unit) {
    (model.instance as? UpscaleEngine)?.close()
    model.instance = null
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    UpscaleScreen(modelManagerViewModel = myData.modelManagerViewModel)
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object UpscaleTaskModule {
  @Provides @IntoSet fun provideTask(): CustomTask = UpscaleTask()
}

@Composable
fun UpscaleScreen(
  modelManagerViewModel: ModelManagerViewModel,
  viewModel: UpscaleViewModel = hiltViewModel(),
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
      Text("Initialize an Upscale model to enlarge photos 4×.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
      Text("Pick image to upscale 4×")
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
        OutlinedButton(onClick = { scope.launch { CvUtils.shareBitmap(context, out, "upscaled") } }) {
          Icon(Icons.Outlined.Share, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text("Share")
        }
      }
    }
  }
}
