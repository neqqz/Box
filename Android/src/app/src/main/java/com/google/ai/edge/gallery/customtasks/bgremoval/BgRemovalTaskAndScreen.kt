package com.google.ai.edge.gallery.customtasks.bgremoval

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.google.ai.edge.litert.Accelerator
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
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

private const val U2NET_ASSET = "bgremoval_models/u2net_fp16.tflite"

class BgRemoverEngine(private val appContext: Context) {
  @Volatile private var compiledModel: CompiledModel? = null

  private fun getOrLoadModel(): CompiledModel {
    compiledModel?.let { return it }
    val assetMgr = appContext.assets
    val accelerators = listOf(arrayOf(Accelerator.GPU, Accelerator.CPU), arrayOf(Accelerator.CPU))
    for (acc in accelerators) {
      runCatching {
        val m = CompiledModel.create(assetMgr, U2NET_ASSET, CompiledModel.Options(*acc))
        compiledModel = m
        return m
      }
    }
    val staged = File(appContext.filesDir, U2NET_ASSET)
    if (!staged.isFile || staged.length() == 0L) {
      staged.parentFile?.mkdirs()
      assetMgr.open(U2NET_ASSET).use { input ->
        FileOutputStream(staged).use { output -> input.copyTo(output) }
      }
    }
    return CvUtils.loadCompiledModel(staged.absolutePath).also { compiledModel = it }
  }

  suspend fun removeBackground(source: Bitmap, trimEdges: Boolean = false): Bitmap? = withContext(Dispatchers.Default) {
    val model = runCatching { getOrLoadModel() }.getOrNull() ?: return@withContext null
    val inputBmp = Bitmap.createScaledBitmap(source, 320, 320, true)
    val n = 320 * 320
    val pixels = IntArray(n)
    inputBmp.getPixels(pixels, 0, 320, 0, 0, 320, 320)

    var maxChannel = 0
    for (px in pixels) {
      val r = (px ushr 16) and 0xFF
      val g = (px ushr 8) and 0xFF
      val b = px and 0xFF
      if (r > maxChannel) maxChannel = r
      if (g > maxChannel) maxChannel = g
      if (b > maxChannel) maxChannel = b
    }
    val denom = if (maxChannel == 0) 1f else maxChannel.toFloat()
    val inputNchw = FloatArray(n * 3)
    for (i in 0 until n) {
      val px = pixels[i]
      inputNchw[i] = ((((px ushr 16) and 0xFF) / denom) - 0.485f) / 0.229f
      inputNchw[n + i] = ((((px ushr 8) and 0xFF) / denom) - 0.456f) / 0.224f
      inputNchw[2 * n + i] = (((px and 0xFF) / denom) - 0.406f) / 0.225f
    }

    val inBufs = model.createInputBuffers()
    val outBufs = model.createOutputBuffers()
    val rawMask = try {
      inBufs[0].writeFloat(inputNchw)
      model.run(inBufs, outBufs)
      outBufs[0].readFloat()
    } finally {
      CvUtils.closeBuffers(inBufs)
      CvUtils.closeBuffers(outBufs)
    }
    if (rawMask.size < n) return@withContext null

    var minVal = Float.MAX_VALUE
    var maxVal = -Float.MAX_VALUE
    for (i in 0 until n) {
      val v = rawMask[i]
      if (v < minVal) minVal = v
      if (v > maxVal) maxVal = v
    }
    val span = (maxVal - minVal).takeIf { it > 1e-6f } ?: 1f
    val maskPixels = IntArray(n) { i ->
      val alpha = (((rawMask[i] - minVal) / span).coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
      Color.argb(alpha, 255, 255, 255)
    }
    val scaledMask = Bitmap.createScaledBitmap(
      Bitmap.createBitmap(maskPixels, 320, 320, Bitmap.Config.ARGB_8888),
      source.width,
      source.height,
      true,
    )

    val w = source.width
    val h = source.height
    val srcArr = IntArray(w * h)
    val mArr = IntArray(w * h)
    source.getPixels(srcArr, 0, w, 0, 0, w, h)
    scaledMask.getPixels(mArr, 0, w, 0, 0, w, h)
    val outArr = IntArray(w * h) { idx ->
      val alpha = (mArr[idx] ushr 24) and 0xFF
      (alpha shl 24) or (srcArr[idx] and 0x00FFFFFF)
    }
    val result = Bitmap.createBitmap(outArr, w, h, Bitmap.Config.ARGB_8888)
    if (trimEdges) trimTransparentEdges(result) else result
  }

  private fun trimTransparentEdges(bmp: Bitmap): Bitmap {
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    var minX = w
    var minY = h
    var maxX = -1
    var maxY = -1
    for (y in 0 until h) {
      val row = y * w
      for (x in 0 until w) {
        if (((px[row + x] ushr 24) and 0xFF) > 8) {
          if (x < minX) minX = x
          if (x > maxX) maxX = x
          if (y < minY) minY = y
          if (y > maxY) maxY = y
        }
      }
    }
    if (maxX < minX || maxY < minY) return bmp
    return Bitmap.createBitmap(bmp, minX, minY, maxX - minX + 1, maxY - minY + 1)
  }

  fun close() {
    runCatching { compiledModel?.close() }
    compiledModel = null
  }
}

data class BgRemovalUiState(
  val sourceBitmap: Bitmap? = null,
  val resultBitmap: Bitmap? = null,
  val isProcessing: Boolean = false,
  val trimEdges: Boolean = false,
  val saved: Boolean = false,
  val errorMessage: String? = null,
)

@HiltViewModel
class BgRemovalViewModel @Inject constructor() : ViewModel() {
  private val _uiState = MutableStateFlow(BgRemovalUiState())
  val uiState: StateFlow<BgRemovalUiState> = _uiState.asStateFlow()

  private var engine: BgRemoverEngine? = null

  fun selectUri(context: Context, uri: Uri) {
    val src = CvUtils.decodeScaledBitmap(context, uri, maxDim = 2048) ?: run {
      _uiState.update { it.copy(errorMessage = "Could not load image") }
      return
    }
    _uiState.update { it.copy(sourceBitmap = src, resultBitmap = null, isProcessing = false, saved = false, errorMessage = null) }
  }

  fun setTrimEdges(enabled: Boolean) {
    _uiState.update { it.copy(trimEdges = enabled) }
  }

  fun removeBackground(context: Context) {
    val src = _uiState.value.sourceBitmap ?: return
    val trim = _uiState.value.trimEdges
    _uiState.update { it.copy(isProcessing = true, saved = false, errorMessage = null) }
    viewModelScope.launch {
      val e = engine ?: BgRemoverEngine(context.applicationContext).also { engine = it }
      val out = e.removeBackground(src, trimEdges = trim)
      _uiState.update {
        it.copy(
          isProcessing = false,
          resultBitmap = out,
          errorMessage = if (out == null) "Background removal failed" else null,
        )
      }
    }
  }

  fun reset() {
    _uiState.update { it.copy(resultBitmap = null, saved = false, errorMessage = null) }
  }

  fun save(context: Context) {
    val bmp = _uiState.value.resultBitmap ?: return
    viewModelScope.launch {
      val ok = CvUtils.saveBitmapToPictures(context, bmp, "BgRemoved", "bgremoved")
      _uiState.update { it.copy(saved = ok, errorMessage = if (!ok) "Failed to save image" else null) }
    }
  }

  override fun onCleared() {
    engine?.close()
    engine = null
    super.onCleared()
  }
}

class BgRemovalTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.BACKGROUND_REMOVAL,
      label = "Background Removal",
      category = Category.IMAGE_GEN,
      icon = Icons.Outlined.AutoFixHigh,
      models = mutableListOf(),
      description =
        "Remove image backgrounds on-device with the U²-Net model built into the app. Works fully offline, no Google Play services needed.",
      shortDescription = "Remove image backgrounds",
    )

  override fun initializeModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: (String) -> Unit) = onDone("")
  override fun cleanUpModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: () -> Unit) = onDone()

  @Composable
  override fun MainScreen(data: Any) {
    BgRemovalScreen()
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object BgRemovalTaskModule {
  @Provides @IntoSet fun provideTask(): CustomTask = BgRemovalTask()
}

@Composable
private fun CheckerboardBackground(modifier: Modifier = Modifier) {
  val light = MaterialTheme.colorScheme.surfaceContainerLowest
  val dark = MaterialTheme.colorScheme.surfaceContainerHigh
  Canvas(modifier = modifier) {
    val cellSize = 12.dp.toPx()
    val cols = (size.width / cellSize).toInt() + 1
    val rows = (size.height / cellSize).toInt() + 1
    for (r in 0 until rows) {
      for (c in 0 until cols) {
        drawRect(
          color = if ((r + c) % 2 == 0) light else dark,
          topLeft = Offset(c * cellSize, r * cellSize),
          size = Size(cellSize, cellSize),
        )
      }
    }
  }
}

@Composable
fun BgRemovalScreen(viewModel: BgRemovalViewModel = hiltViewModel()) {
  val state by viewModel.uiState.collectAsState()
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) viewModel.selectUri(context, uri)
  }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    if (state.sourceBitmap == null) {
      OutlinedButton(
        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        modifier = Modifier.fillMaxWidth().height(240.dp),
        shape = RoundedCornerShape(12.dp),
      ) {
        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Pick image")
      }
    } else {
      val shown = state.resultBitmap ?: state.sourceBitmap!!
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
      ) {
        Column(
          modifier = Modifier.padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Box(
            modifier =
              Modifier.fillMaxWidth()
                .aspectRatio(shown.width.toFloat() / shown.height.toFloat().coerceAtLeast(1f))
                .clip(RoundedCornerShape(8.dp)),
          ) {
            if (state.resultBitmap != null) {
              CheckerboardBackground(modifier = Modifier.matchParentSize())
            }
            Image(
              bitmap = shown.asImageBitmap(),
              contentDescription = if (state.resultBitmap != null) "Result" else "Source",
              contentScale = ContentScale.Fit,
              modifier = Modifier.matchParentSize(),
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            OutlinedButton(
              onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
              enabled = !state.isProcessing,
            ) {
              Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("Pick another")
            }
            if (state.resultBitmap != null) {
              OutlinedButton(onClick = { viewModel.save(context) }) {
                Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (state.saved) "Saved" else "Save")
              }
              OutlinedButton(
                onClick = { scope.launch { CvUtils.shareBitmap(context, state.resultBitmap!!, "bgremoval_share") } },
              ) {
                Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share")
              }
            }
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          Icons.Outlined.ContentCut,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
          "Trim transparent edges",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        Switch(
          checked = state.trimEdges,
          onCheckedChange = { viewModel.setTrimEdges(it) },
          enabled = !state.isProcessing,
        )
      }

      Button(
        onClick = { viewModel.removeBackground(context) },
        enabled = !state.isProcessing,
        modifier = Modifier.fillMaxWidth(),
      ) {
        if (state.isProcessing) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
          Spacer(Modifier.width(8.dp))
          Text("Removing…")
        } else {
          Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text("Remove background")
        }
      }

      if (state.resultBitmap != null) {
        OutlinedButton(
          onClick = { viewModel.reset() },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Reset")
        }
      }
    }

    state.errorMessage?.let {
      Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
  }
}
