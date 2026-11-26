package com.google.ai.edge.gallery.customtasks.imagegen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDataFile
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.io.File
import java.util.Random
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "BonsaiEngine"

class BonsaiEngine {
  private var env: Environment? = null
  private var tokenizer: QwenBpeTokenizer? = null
  private var modelDir: File? = null
  private lateinit var bnScale: FloatArray
  private lateinit var bnShift: FloatArray
  @Volatile var cancelled = false

  fun load(context: Context, dir: File): Boolean = try {
    close()
    modelDir = dir
    tokenizer = QwenBpeTokenizer.fromHuggingFaceJson(
      vocabJsonFile = File(dir, "bonsai_vocab.json"),
      mergesFile = File(dir, "bonsai_merges.txt"),
      addedTokensJsonFile = File(dir, "bonsai_added_tokens.json"),
    )
    bnScale = readAssetFloats(context, "bonsai/bn_scale.bin")
    bnShift = readAssetFloats(context, "bonsai/bn_shift.bin")
    require(bnScale.size == 128 && bnShift.size == 128) {
      "bn stats must be 128 floats, got ${bnScale.size}/${bnShift.size}"
    }
    env = Environment.create()
    true
  } catch (t: Throwable) {
    Log.e(TAG, "load failed", t)
    close()
    false
  }

  private fun runCpuGraph(fileName: String, inputs: List<Any>): List<FloatArray> {
    val dir = modelDir ?: error("BonsaiEngine not loaded")
    val environment = env ?: error("Environment closed")
    val cm = CompiledModel.create(File(dir, fileName).absolutePath(), CompiledModel.Options(Accelerator.CPU), environment)
    val inBufs = cm.createInputBuffers()
    val outBufs = cm.createOutputBuffers()
    try {
      inputs.forEachIndexed { idx, data ->
        when (data) {
          is FloatArray -> inBufs[idx].writeFloat(data)
          is IntArray -> inBufs[idx].writeInt(data)
          else -> throw IllegalArgumentException("Unsupported input buffer at $idx")
        }
      }
      cm.run(inBufs, outBufs)
      return outBufs.map { it.readFloat() }
    } finally {
      closeLiteRtBuffers(cm, inBufs, outBufs)
    }
  }

  private fun unpatchLatents(latents: FloatArray): FloatArray {
    val out = FloatArray(131072)
    for (c in 0 until 128) {
      val s = bnScale[c]
      val sh = bnShift[c]
      val ch = c / 4
      val py = (c % 4) / 2
      val px = c % 2
      for (y in 0 until 32) {
        for (x in 0 until 32) {
          val srcIdx = (((y shl 5) + x) shl 7) + c
          val dstIdx = (x shl 1) + px + (ch shl 12) + (((y shl 1) + py) shl 6)
          out[dstIdx] = latents[srcIdx] * s + sh
        }
      }
    }
    return out
  }

  fun generate(prompt: String, onProgress: (String, Float) -> Unit): Bitmap? {
    val tok = tokenizer ?: return null
    cancelled = false
    onProgress("Encoding prompt", 0.0f)
    val formatted = "<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
    val rawIds = tok.encode(formatted)
    val count = minOf(rawIds.size, 256)
    val ids = IntArray(256) { i -> if (i < count) rawIds[i] else 151643 }
    val mask = IntArray(256) { i -> if (i < count) 1 else 0 }
    if (cancelled) return null

    onProgress("Text encoder", 0.05f)
    val textCond = runCpuGraph("textenc_int4.tflite", listOf(ids, mask))[0]
    val sigmas = FloatArray(5)
    for (i in 0 until 4) {
      val d = i * (-0.75) / 3.0 + 1.0
      sigmas[i] = (7.619339666088855 / ((1.0 / d) - 1.0 + 7.619339666088855)).toFloat()
    }
    sigmas[4] = 0f

    val rng = Random(System.currentTimeMillis())
    val latents = FloatArray(131072) { rng.nextGaussian().toFloat() }
    for (step in 0 until 4) {
      if (cancelled) return null
      onProgress("Step ${step + 1}/4", (step * 0.7f / 4f) + 0.2f)
      val pred = runCpuGraph(
        "dit_gpu_int4b32.tflite",
        listOf(latents, textCond, floatArrayOf(sigmas[step]), bnScale, bnShift),
      )[0]
      val dt = sigmas[step + 1] - sigmas[step]
      for (i in latents.indices) {
        latents[i] = pred[i] * dt + latents[i]
      }
    }
    if (cancelled) return null

    onProgress("Decoding image", 0.93f)
    val rgb = runCpuGraph("vae_dec_fp32.tflite", listOf(unpatchLatents(latents)))[0]
    onProgress("Done", 1.0f)
    val pixels = IntArray(262144)
    for (i in 0 until 262144) {
      val r = ((rgb[i] / 2f + 0.5f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
      val g = ((rgb[262144 + i] / 2f + 0.5f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
      val b = ((rgb[524288 + i] / 2f + 0.5f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
      pixels[i] = AndroidColor.rgb(r, g, b)
    }
    return Bitmap.createBitmap(pixels, 512, 512, Bitmap.Config.ARGB_8888)
  }

  fun close() {
    tokenizer = null
    modelDir = null
    runCatching { env?.close() }
    env = null
  }
}

private fun bonsaiFile(size: Long, name: String, repoRel: String) = ModelDataFile(
  name = name,
  url = "https://huggingface.co/litert-community/Bonsai-Image-ternary-4B/resolve/main/$repoRel?download=true",
  downloadFileName = name,
  sizeInBytes = size,
)

class BonsaiTask @Inject constructor() : CustomTask {
  private val model = Model(
    name = "Bonsai Image 4B",
    info =
      "**Recommended.** Bonsai Image 4B — a ternary-weight build of FLUX.2 [klein] 4B converted to " +
        "three LiteRT graphs. 512×512 output, 4 steps, guidance-free. Runs on the CPU — expect a " +
        "couple of minutes per image.",
    url = "https://huggingface.co/litert-community/Bonsai-Image-ternary-4B/resolve/main/dit_gpu_int4b32.tflite?download=true",
    sizeInBytes = 2_268_520_368L,
    downloadFileName = "dit_gpu_int4b32.tflite",
    version = "main",
    extraDataFiles = listOf(
      bonsaiFile(1_798_100_240L, "textenc_int4.tflite", "textenc_int4.tflite"),
      bonsaiFile(198_831_180L, "vae_dec_fp32.tflite", "vae_dec_fp32.tflite"),
      bonsaiFile(2_776_833L, "bonsai_vocab.json", "tokenizer/vocab.json"),
      bonsaiFile(1_671_853L, "bonsai_merges.txt", "tokenizer/merges.txt"),
      bonsaiFile(707L, "bonsai_added_tokens.json", "tokenizer/added_tokens.json"),
    ),
    minDeviceMemoryInGb = 8,
    learnMoreUrl = "https://huggingface.co/litert-community/Bonsai-Image-ternary-4B",
    showRunAgainButton = false,
    showBenchmarkButton = false,
  )

  override val task: Task = Task(
    id = BuiltInTaskId.BONSAI,
    label = "Bonsai Image",
    category = Category.IMAGE_GEN,
    icon = Icons.Outlined.AutoAwesome,
    models = mutableListOf(model),
    description =
      "Text-to-image with Bonsai Image 4B, a ternary-weight build of FLUX.2 [klein] running on " +
        "CPU via three LiteRT graphs. 512×512, 4 steps.",
    shortDescription = "512×512 on-device text-to-image",
    showRunAgainButton = false,
    showBenchmarkButton = false,
  )

  override fun initializeModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: (String) -> Unit) {
    coroutineScope.launch(Dispatchers.IO) {
      val dir = File(model.getPath(context, model.downloadFileName)).parentFile
      if (dir == null) {
        onDone("Failed to locate Bonsai Image files")
        return@launch
      }
      val engine = BonsaiEngine()
      if (engine.load(context, dir)) {
        model.instance = engine
        onDone("")
      } else {
        onDone("Failed to load Bonsai Image (check that all files finished downloading)")
      }
    }
  }

  override fun cleanUpModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: () -> Unit) {
    val engine = model.instance as? BonsaiEngine
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
    val model = uiState.selectedModel
    val engine = model.instance as? BonsaiEngine
    val scope = rememberCoroutineScope()

    var generating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LiteRtImageGenScreen(
      title = "Bonsai Image 4B · 512×512",
      filePrefix = "bonsai",
      placeholder = "Describe the image you want to generate…",
      isGenerating = generating,
      statusText = status,
      progress = progress,
      resultBitmap = bitmap,
      errorMessage = error,
      bottomPadding = taskData.bottomPadding,
      onGenerate = { prompt ->
        val eng = engine ?: run {
          error = "Model engine is not initialized."
          return@LiteRtImageGenScreen
        }
        generating = true
        error = null
        status = "Starting…"
        progress = 0f
        scope.launch {
          val bmp = withContext(Dispatchers.Default) {
            runCatching {
              eng.generate(prompt) { st, p ->
                status = st
                progress = p
              }
            }.getOrNull()
          }
          generating = false
          if (bmp != null) {
            bitmap = bmp
          } else {
            error = "Generation failed or was cancelled."
          }
        }
      },
      onCancel = { engine?.cancelled = true },
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
object BonsaiTaskModule {
  @Provides
  @IntoSet
  fun provideBonsaiTask(): CustomTask = BonsaiTask()
}
