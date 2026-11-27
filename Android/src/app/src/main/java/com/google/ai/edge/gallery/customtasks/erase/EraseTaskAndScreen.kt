package com.google.ai.edge.gallery.customtasks.erase

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixNormal
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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

private const val TAG = "MiganEngine"
private const val MIGAN_DIM = 512

data class BrushStroke(
  val normalizedPoints: List<Offset>,
  val normalizedRadius: Float,
)

class MiganEngine {
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

  suspend fun inpaint(source: Bitmap, mask512: Bitmap): Bitmap? = withContext(Dispatchers.Default) {
    val model = compiledModel ?: return@withContext null
    try {
      val src512 = Bitmap.createScaledBitmap(source, MIGAN_DIM, MIGAN_DIM, true)
      val m512 = if (mask512.width == MIGAN_DIM && mask512.height == MIGAN_DIM) mask512
      else Bitmap.createScaledBitmap(mask512, MIGAN_DIM, MIGAN_DIM, true)

      val n = MIGAN_DIM * MIGAN_DIM
      val srcPx = IntArray(n)
      val maskPx = IntArray(n)
      src512.getPixels(srcPx, 0, MIGAN_DIM, 0, 0, MIGAN_DIM, MIGAN_DIM)
      m512.getPixels(maskPx, 0, MIGAN_DIM, 0, 0, MIGAN_DIM, MIGAN_DIM)

      // Input shape [1, 4, 512, 512]: ch0 = mask-0.5, ch1..3 = masked RGB in [-1, 1]
      val input4ch = FloatArray(n * 4)
      val keepMask = FloatArray(n)
      for (i in 0 until n) {
        val keep = if (((maskPx[i] ushr 16) and 0xFF) >= 128) 1f else 0f
        keepMask[i] = keep
        input4ch[i] = keep - 0.5f
        val px = srcPx[i]
        input4ch[n + i] = ((((px ushr 16) and 0xFF) / 127.5f) - 1f) * keep
        input4ch[2 * n + i] = ((((px ushr 8) and 0xFF) / 127.5f) - 1f) * keep
        input4ch[3 * n + i] = (((px and 0xFF) / 127.5f) - 1f) * keep
      }

      val inBufs = model.createInputBuffers()
      val outBufs = model.createOutputBuffers()
      val outRaw = try {
        inBufs[0].writeFloat(input4ch)
        model.run(inBufs, outBufs)
        outBufs[0].readFloat()
      } finally {
        CvUtils.closeBuffers(inBufs)
        CvUtils.closeBuffers(outBufs)
      }

      val outPx = IntArray(n)
      for (i in 0 until n) {
        val keep = keepMask[i]
        fun mix(v: Float, orig: Int): Int =
          if (keep >= 0.5f) orig else (((v * 0.5f + 0.5f).coerceIn(0f, 1f) * 255f) + 0.5f).toInt()
        val orig = srcPx[i]
        outPx[i] = Color.rgb(
          mix(outRaw[i], (orig ushr 16) and 0xFF),
          mix(outRaw[n + i], (orig ushr 8) and 0xFF),
          mix(outRaw[2 * n + i], orig and 0xFF),
        )
      }
      val out512 = Bitmap.createBitmap(outPx, MIGAN_DIM, MIGAN_DIM, Bitmap.Config.ARGB_8888)
      if (source.width == MIGAN_DIM && source.height == MIGAN_DIM) out512
      else Bitmap.createScaledBitmap(out512, source.width, source.height, true)
    } catch (t: Throwable) {
      Log.e(TAG, "inpaint failed", t)
      null
    }
  }

  fun close() {
    runCatching { compiledModel?.close() }
    compiledModel = null
  }
}

data class EraseUiState(
  val sourceBitmap: Bitmap? = null,
  val resultBitmap: Bitmap? = null,
  val brushFraction: Float = 0.04f,
  val isProcessing: Boolean = false,
  val saved: Boolean = false,
  val errorMessage: String? = null,
)

@HiltViewModel
class EraseViewModel @Inject constructor() : ViewModel() {
  private val _uiState = MutableStateFlow(EraseUiState())
  val uiState: StateFlow<EraseUiState> = _uiState.asStateFlow()

  fun setImage(context: Context, uri: Uri) {
    val bmp = CvUtils.decodeScaledBitmap(context, uri, maxDim = 2048) ?: run {
      _uiState.update { it.copy(errorMessage = "Could not load image") }
      return
    }
    _uiState.update { it.copy(sourceBitmap = bmp, resultBitmap = null, saved = false, errorMessage = null) }
  }

  fun setBrushFraction(v: Float) = _uiState.update { it.copy(brushFraction = v) }

  fun erase(model: Model, strokes: List<BrushStroke>) {
    val src = _uiState.value.sourceBitmap ?: return
    val engine = model.instance as? MiganEngine ?: run {
      _uiState.update { it.copy(errorMessage = "Model not loaded") }
      return
    }
    if (strokes.isEmpty()) return

    val mask = Bitmap.createBitmap(MIGAN_DIM, MIGAN_DIM, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(mask)
    canvas.drawColor(Color.WHITE) // White = keep (>= 128), Black = erase
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = Color.BLACK
      style = Paint.Style.STROKE
      strokeCap = Paint.Cap.ROUND
      strokeJoin = Paint.Join.ROUND
    }
    for (stroke in strokes) {
      if (stroke.normalizedPoints.isEmpty()) continue
      paint.strokeWidth = stroke.normalizedRadius * 2f * MIGAN_DIM
      val path = android.graphics.Path()
      val first = stroke.normalizedPoints.first()
      path.moveTo(first.x * MIGAN_DIM, first.y * MIGAN_DIM)
      for (i in 1 until stroke.normalizedPoints.size) {
        val pt = stroke.normalizedPoints[i]
        path.lineTo(pt.x * MIGAN_DIM, pt.y * MIGAN_DIM)
      }
      if (stroke.normalizedPoints.size == 1) {
        canvas.drawCircle(first.x * MIGAN_DIM, first.y * MIGAN_DIM, stroke.normalizedRadius * MIGAN_DIM, Paint(paint).apply { style = Paint.Style.FILL })
      } else {
        canvas.drawPath(path, paint)
      }
    }

    _uiState.update { it.copy(isProcessing = true, errorMessage = null, saved = false) }
    viewModelScope.launch {
      val out = engine.inpaint(src, mask)
      _uiState.update {
        it.copy(
          isProcessing = false,
          resultBitmap = out,
          errorMessage = if (out == null) "Inpainting failed" else null,
        )
      }
    }
  }

  fun save(context: Context) {
    val bmp = _uiState.value.resultBitmap ?: return
    viewModelScope.launch {
      val ok = CvUtils.saveBitmapToPictures(context, bmp, "Erased", "migan_erase")
      _uiState.update { it.copy(saved = ok, errorMessage = if (!ok) "Save failed" else null) }
    }
  }
}

class EraseTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.MIGAN,
      label = "Erase",
      category = Category.IMAGE_GEN,
      icon = Icons.Outlined.AutoFixNormal,
      models =
        mutableListOf(
          Model(
            name = "MI-GAN 512 (Places2)",
            info = "Removes unwanted objects from photos. Paint over whatever you want gone (~16 MB, bundled).",
            url = "",
            sizeInBytes = 16_312_640L,
            downloadFileName = "migan_fp16.tflite",
            version = "v1.0.0",
          )
        ),
      description =
        "Remove unwanted objects from photos on-device with MI-GAN (Places2). Paint over anything — people, wires, trash — and fill the area cleanly offline.",
      shortDescription = "On-device object removal (inpainting)",
    )

  override fun initializeModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: (String) -> Unit) {
    coroutineScope.launch(Dispatchers.Default) {
      val engine = MiganEngine()
      if (engine.load(model.getPath(context))) {
        model.instance = engine
        onDone("")
      } else {
        onDone("Failed to load inpainting model")
      }
    }
  }

  override fun cleanUpModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: () -> Unit) {
    (model.instance as? MiganEngine)?.close()
    model.instance = null
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    EraseScreen(modelManagerViewModel = myData.modelManagerViewModel)
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object EraseTaskModule {
  @Provides @IntoSet fun provideTask(): CustomTask = EraseTask()
}

@Composable
fun EraseScreen(
  modelManagerViewModel: ModelManagerViewModel,
  viewModel: EraseViewModel = hiltViewModel(),
) {
  val state by viewModel.uiState.collectAsState()
  val mmState by modelManagerViewModel.uiState.collectAsState()
  val model = mmState.selectedModel
  val ready = mmState.modelInitializationStatus[model.name]?.status == ModelInitializationStatusType.INITIALIZED
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val strokes = remember { mutableStateListOf<BrushStroke>() }
  val activePoints = remember { mutableStateListOf<Offset>() }

  val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    if (uri != null) {
      strokes.clear()
      activePoints.clear()
      viewModel.setImage(context, uri)
    }
  }

  if (!ready) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("Initialize MI-GAN to remove objects from photos.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    return
  }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
        Spacer(Modifier.width(6.dp))
        Text("Pick photo")
      }
      if (state.sourceBitmap != null) {
        IconButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) {
          Icon(Icons.Outlined.Undo, contentDescription = "Undo stroke")
        }
        IconButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) {
          Icon(Icons.Outlined.Clear, contentDescription = "Clear mask")
        }
      }
    }

    state.sourceBitmap?.let { src ->
      val displayBmp = state.resultBitmap ?: src
      val aspect = (src.width.toFloat() / src.height.toFloat()).coerceIn(0.4f, 2.5f)
      Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
        contentAlignment = Alignment.Center,
      ) {
        Image(
          bitmap = displayBmp.asImageBitmap(),
          contentDescription = null,
          contentScale = ContentScale.FillBounds,
          modifier = Modifier.fillMaxSize(),
        )
        if (state.resultBitmap == null) {
          ComposeCanvas(
            modifier = Modifier.fillMaxSize().pointerInput(src, state.brushFraction) {
              detectDragGestures(
                onDragStart = { offset ->
                  activePoints.clear()
                  activePoints.add(Offset((offset.x / size.width).coerceIn(0f, 1f), (offset.y / size.height).coerceIn(0f, 1f)))
                },
                onDrag = { change, _ ->
                  val pt = change.position
                  activePoints.add(Offset((pt.x / size.width).coerceIn(0f, 1f), (pt.y / size.height).coerceIn(0f, 1f)))
                },
                onDragEnd = {
                  if (activePoints.isNotEmpty()) {
                    strokes.add(BrushStroke(activePoints.toList(), state.brushFraction))
                    activePoints.clear()
                  }
                },
              )
            }
          ) {
            fun drawStroke(pts: List<Offset>, normRadius: Float) {
              if (pts.isEmpty()) return
              val pxRadius = normRadius * size.width
              val color = androidx.compose.ui.graphics.Color.Red.copy(alpha = 0.45f)
              if (pts.size == 1) {
                drawCircle(color, radius = pxRadius, center = Offset(pts[0].x * size.width, pts[0].y * size.height))
              } else {
                val path = Path().apply {
                  moveTo(pts[0].x * size.width, pts[0].y * size.height)
                  for (i in 1 until pts.size) lineTo(pts[i].x * size.width, pts[i].y * size.height)
                }
                drawPath(path, color = color, style = Stroke(width = pxRadius * 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
              }
            }
            strokes.forEach { drawStroke(it.normalizedPoints, it.normalizedRadius) }
            if (activePoints.isNotEmpty()) drawStroke(activePoints, state.brushFraction)
          }
        }
      }

      Text("Brush size", style = MaterialTheme.typography.labelLarge)
      Slider(value = state.brushFraction, onValueChange = viewModel::setBrushFraction, valueRange = 0.015f..0.12f)

      if (state.isProcessing) {
        CircularProgressIndicator()
      } else if (state.resultBitmap == null) {
        Button(onClick = { viewModel.erase(model, strokes.toList()) }, enabled = strokes.isNotEmpty()) {
          Text("Erase painted area")
        }
      } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = { viewModel.save(context) }) {
            Icon(Icons.Outlined.Download, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (state.saved) "Saved" else "Save PNG")
          }
          OutlinedButton(onClick = { scope.launch { CvUtils.shareBitmap(context, state.resultBitmap!!, "erased") } }) {
            Icon(Icons.Outlined.Share, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Share")
          }
        }
      }
    }

    state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
  }
}
