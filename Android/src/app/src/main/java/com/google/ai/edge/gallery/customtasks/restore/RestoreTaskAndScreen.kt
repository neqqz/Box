package com.google.ai.edge.gallery.customtasks.restore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.compose.material.icons.outlined.AutoAwesome
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

private const val TAG = "RestoreEngine"
private const val TILE = 256
private const val OVERLAP = 32
private const val MARGIN = 16
private const val STRIDE = TILE - OVERLAP

class RestoreEngine {
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

  suspend fun restore(source: Bitmap, onProgress: (Float) -> Unit): Bitmap? =
    withContext(Dispatchers.Default) {
      val model = compiledModel ?: return@withContext null
      try {
        val w = source.width
        val h = source.height
        val padded = if (w < TILE || h < TILE) {
          Bitmap.createBitmap(maxOf(w, TILE), maxOf(h, TILE), Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(source, 0f, 0f, null)
          }
        } else {
          source
        }
        val pw = padded.width
        val ph = padded.height
        val outBmp = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val xs = tileOffsets(pw)
        val ys = tileOffsets(ph)
        val totalTiles = xs.size * ys.size
        var doneTiles = 0

        for (y in ys) {
          for (x in xs) {
            val crop = Bitmap.createBitmap(padded, x, y, TILE, TILE)
            val raw = runTile(model, crop)
            if (raw != null) {
              val leftPad = if (x == 0) 0 else MARGIN
              val topPad = if (y == 0) 0 else MARGIN
              val rightPad = if (x + TILE >= pw) 0 else MARGIN
              val bottomPad = if (y + TILE >= ph) 0 else MARGIN
              val validW = TILE - leftPad - rightPad
              val validH = TILE - topPad - bottomPad
              if (validW > 0 && validH > 0) {
                val nTile = TILE * TILE
                val rowBuf = IntArray(validW)
                for (row in 0 until validH) {
                  val ty = topPad + row
                  for (col in 0 until validW) {
                    val idx = (ty shl 8) + leftPad + col
                    rowBuf[col] = Color.rgb(
                      (raw[idx].coerceIn(0f, 1f) * 255f + 0.5f).toInt(),
                      (raw[nTile + idx].coerceIn(0f, 1f) * 255f + 0.5f).toInt(),
                      (raw[2 * nTile + idx].coerceIn(0f, 1f) * 255f + 0.5f).toInt(),
                    )
                  }
                  outBmp.setPixels(rowBuf, 0, validW, x + leftPad, y + topPad + row, validW, 1)
                }
              }
            }
            doneTiles++
            onProgress(doneTiles.toFloat() / totalTiles)
          }
        }
        if (pw == w && ph == h) outBmp else Bitmap.createBitmap(outBmp, 0, 0, w, h)
      } catch (t: Throwable) {
        Log.e(TAG, "restore failed", t)
        null
      }
    }

  private fun tileOffsets(limit: Int): List<Int> {
    if (limit <= TILE) return listOf(0)
    val offsets = mutableListOf<Int>()
    var cur = 0
    while (true) {
      offsets.add(cur)
      if (cur + TILE >= limit) break
      cur = minOf(cur + STRIDE, limit - TILE)
    }
    return offsets.distinct()
  }

  private fun runTile(model: CompiledModel, tile: Bitmap): FloatArray? {
    val n = TILE * TILE
    val px = IntArray(n)
    tile.getPixels(px, 0, TILE, 0, 0, TILE, TILE)
    val inputNchw = FloatArray(n * 3)
    for (i in 0 until n) {
      val c = px[i]
      inputNchw[i] = ((c ushr 16) and 0xFF) / 255f
      inputNchw[n + i] = ((c ushr 8) and 0xFF) / 255f
      inputNchw[2 * n + i] = (c and 0xFF) / 255f
    }
    val inBufs = model.createInputBuffers()
    val outBufs = model.createOutputBuffers()
    return try {
      inBufs[0].writeFloat(inputNchw)
      model.run(inBufs, outBufs)
      outBufs[0].readFloat()
    } finally {
      CvUtils.closeBuffers(inBufs)
      CvUtils.closeBuffers(outBufs)
    }
  }

  fun close() {
    runCatching { compiledModel?.close() }
    compiledModel = null
  }
}

data class RestoreUiState(
  val sourceBitmap: Bitmap? = null,
  val resultBitmap: Bitmap? = null,
  val isProcessing: Boolean = false,
  val progress: Float = 0f,
  val saved: Boolean = false,
  val errorMessage: String? = null,
)

@HiltViewModel
class RestoreViewModel @Inject constructor() : ViewModel() {
  private val _uiState = MutableStateFlow(RestoreUiState())
  val uiState: StateFlow<RestoreUiState> = _uiState.asStateFlow()

  fun processUri(context: Context, uri: Uri, model: Model) {
    val engine = model.instance as? RestoreEngine ?: run {
      _uiState.update { it.copy(errorMessage = "Model not loaded") }
      return
    }
    val bmp = CvUtils.decodeScaledBitmap(context, uri, maxDim = 2048) ?: run {
      _uiState.update { it.copy(errorMessage = "Could not load image") }
      return
    }
    _uiState.update { it.copy(sourceBitmap = bmp, resultBitmap = null, isProcessing = true, progress = 0f, saved = false, errorMessage = null) }
    viewModelScope.launch {
      val out = engine.restore(bmp) { p -> _uiState.update { it.copy(progress = p) } }
      _uiState.update {
        it.copy(
          isProcessing = false,
          resultBitmap = out,
          errorMessage = if (out == null) "Restore failed" else null,
        )
      }
    }
  }

  fun save(context: Context) {
    val bmp = _uiState.value.resultBitmap ?: return
    viewModelScope.launch {
      val ok = CvUtils.saveBitmapToPictures(context, bmp, "Restored", "restored")
      _uiState.update { it.copy(saved = ok, errorMessage = if (!ok) "Save failed" else null) }
    }
  }
}

class RestoreTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.RESTORE,
      label = "Restore",
      category = Category.EXPERIMENTAL,
      icon = Icons.Outlined.AutoAwesome,
      models =
        mutableListOf(
          Model(
            name = "Deblur (motion blur)",
            info = "Sharpens photos ruined by camera shake or a moving subject (GoPro NAFNet, bundled).",
            url = "",
            sizeInBytes = 38_314_240L,
            downloadFileName = "nafnet_gopro_fp16.tflite",
            version = "v1.0.0",
          ),
          Model(
            name = "Denoise (low light)",
            info = "Cleans up speckled grain in dim-light photos without smearing detail (SIDD NAFNet, bundled).",
            url = "",
            sizeInBytes = 62_454_048L,
            downloadFileName = "nafnet_sidd_fp16.tflite",
            version = "v1.0.0",
          ),
        ),
      description =
        "Repair a photo on-device — remove motion blur from a shaky shot, or clean the grain out of a low-light one. Fully offline.",
      shortDescription = "Deblur and denoise photos",
    )

  override fun initializeModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: (String) -> Unit) {
    coroutineScope.launch(Dispatchers.Default) {
      val engine = RestoreEngine()
      if (engine.load(model.getPath(context))) {
        model.instance = engine
        onDone("")
      } else {
        onDone("Failed to load ${model.name}")
      }
    }
  }

  override fun cleanUpModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: () -> Unit) {
    (model.instance as? RestoreEngine)?.close()
    model.instance = null
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    RestoreScreen(modelManagerViewModel = myData.modelManagerViewModel)
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object RestoreTaskModule {
  @Provides @IntoSet fun provideTask(): CustomTask = RestoreTask()
}

@Composable
fun RestoreScreen(
  modelManagerViewModel: ModelManagerViewModel,
  viewModel: RestoreViewModel = hiltViewModel(),
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
      Text("Initialize a Restore model to deblur or denoise photos.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        OutlinedButton(onClick = { scope.launch { CvUtils.shareBitmap(context, out, "restored") } }) {
          Icon(Icons.Outlined.Share, contentDescription = null)
          Spacer(Modifier.width(6.dp))
          Text("Share")
        }
      }
    }
  }
}
