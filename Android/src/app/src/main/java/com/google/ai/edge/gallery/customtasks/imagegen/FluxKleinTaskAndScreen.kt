package com.google.ai.edge.gallery.customtasks.imagegen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.util.Half
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
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Random
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "FluxKleinEngine"

class FluxKleinEngine {
  private var env: Environment? = null
  private var tokenizer: QwenBpeTokenizer? = null
  private var modelDir: File? = null
  private var embedRaf: RandomAccessFile? = null
  private var embedMap: MappedByteBuffer? = null
  private lateinit var encCos: FloatArray
  private lateinit var encSin: FloatArray
  private lateinit var ditCos: FloatArray
  private lateinit var ditSin: FloatArray
  private lateinit var sigmas: FloatArray
  private lateinit var temb: Array<FloatArray>
  private lateinit var bnMean: FloatArray
  private lateinit var bnStd: FloatArray
  @Volatile var cancelled = false

  fun load(context: Context, dir: File): Boolean = try {
    close()
    modelDir = dir
    tokenizer = QwenBpeTokenizer.fromPlainFiles(
      File(dir, "qwen_vocab.txt"),
      File(dir, "qwen_merges.txt"),
      File(dir, "qwen_special.txt"),
    )
    val raf = RandomAccessFile(File(dir, "qwen_embed_fp16.bin"), "r")
    embedRaf = raf
    embedMap = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0L, raf.length()).order(ByteOrder.LITTLE_ENDIAN)
    encCos = readAssetFloats(context, "fluxklein/enc_cos.bin")
    encSin = readAssetFloats(context, "fluxklein/enc_sin.bin")
    ditCos = readAssetFloats(context, "fluxklein/dit_cos.bin")
    ditSin = readAssetFloats(context, "fluxklein/dit_sin.bin")
    sigmas = readAssetFloats(context, "fluxklein/sigmas.bin")
    val flatTemb = readAssetFloats(context, "fluxklein/temb.bin")
    temb = Array(4) { i ->
      FloatArray(3072) { j -> flatTemb[i * 3072 + j] }
    }
    bnMean = readAssetFloats(context, "fluxklein/bn_mean.bin")
    bnStd = readAssetFloats(context, "fluxklein/bn_std.bin")
    env = Environment.create()
    true
  } catch (t: Throwable) {
    Log.e(TAG, "load failed", t)
    close()
    false
  }

  private fun runGpuWithCpuFallback(fileName: String, inputs: List<FloatArray>): List<FloatArray> {
    val dir = modelDir ?: error("FluxKleinEngine not loaded")
    val environment = env ?: error("Environment closed")
    val gpuOpts = CompiledModel.Options(Accelerator.GPU).apply {
      gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
    }
    val cm = try {
      CompiledModel.create(File(dir, fileName).absolutePath(), gpuOpts, environment)
    } catch (t: Throwable) {
      Log.w(TAG, "$fileName failed on GPU, retrying on CPU", t)
      CompiledModel.create(File(dir, fileName).absolutePath(), CompiledModel.Options(Accelerator.CPU), environment)
    }
    val inBufs = cm.createInputBuffers()
    val outBufs = cm.createOutputBuffers()
    try {
      inputs.forEachIndexed { idx, data -> inBufs[idx].writeFloat(data) }
      cm.run(inBufs, outBufs)
      return outBufs.map { it.readFloat() }
    } finally {
      closeLiteRtBuffers(cm, inBufs, outBufs)
    }
  }

  private fun unpatchLatents(latents: FloatArray): FloatArray {
    val out = FloatArray(32768)
    for (c in 0 until 128) {
      val s = bnStd[c]
      val m = bnMean[c]
      val ch = c / 4
      val py = (c % 4) / 2
      val px = c % 2
      for (y in 0 until 16) {
        for (x in 0 until 16) {
          val srcIdx = (((y shl 4) + x) shl 7) + c
          val dstIdx = (x shl 1) + px + (ch shl 10) + (((y shl 1) + py) shl 5)
          out[dstIdx] = latents[srcIdx] * s + m
        }
      }
    }
    return out
  }

  fun generate(prompt: String, onProgress: (String, Float) -> Unit): Bitmap? {
    val tok = tokenizer ?: return null
    val map = embedMap ?: return null
    cancelled = false
    onProgress("Encoding prompt", 0.0f)

    val formatted = "<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
    val rawIds = tok.encode(formatted)
    val count = minOf(rawIds.size, 512)
    val ids = IntArray(512) { i -> if (i < count) rawIds[i] else 151643 }

    var encHidden = FloatArray(512 * 2560)
    for (t in 0 until 512) {
      val tokenId = ids[t].coerceIn(0, 151935)
      val baseOffset = tokenId * 5120
      val dstOffset = t * 2560
      for (d in 0 until 2560) {
        encHidden[dstOffset + d] = Half.toFloat(map.getShort(baseOffset + (d shl 1)))
      }
    }

    // Causal + padding attention mask (512x512 repeated across 32 heads)
    val maskSingle = FloatArray(262144)
    for (row in 0 until 512) {
      val rowOff = row shl 9
      for (col in 0 until 512) {
        maskSingle[rowOff + col] = if (col > row || col >= count) -Float.MAX_VALUE else 0f
      }
    }
    val encMask = FloatArray(8388608)
    for (h in 0 until 32) {
      System.arraycopy(maskSingle, 0, encMask, h shl 18, 262144)
    }

    val layerOutputs = ArrayList<FloatArray>(3)
    for (i in 0 until 3) {
      if (cancelled) return null
      onProgress("Text encoder ${i + 1}/3", i * 0.09f + 0.02f)
      encHidden = runGpuWithCpuFallback("ke_enc$i.tflite", listOf(encHidden, encMask, encCos, encSin))[0]
      layerOutputs.add(encHidden)
    }

    // Pack 3 layers * 2560 dims -> [512, 7680]
    val textCond = FloatArray(3932160)
    for (l in 0 until 3) {
      val src = layerOutputs[l]
      for (t in 0 until 512) {
        System.arraycopy(src, t * 2560, textCond, l * 2560 + t * 7680, 2560)
      }
    }

    val rng = Random(System.currentTimeMillis())
    val latents = FloatArray(32768) { rng.nextGaussian().toFloat() }

    for (step in 0 until 4) {
      if (cancelled) return null
      val baseProg = step * 0.16f + 0.3f
      onProgress("Step ${step + 1}/4 · prep", baseProg)
      val prepOut = runGpuWithCpuFallback("kc_prep.tflite", listOf(latents, textCond, temb[step]))
      var imgTok = prepOut[0]
      var txtTok = prepOut[1]
      val modImg = prepOut[2]
      val modTxt = prepOut[3]
      val modSingle = prepOut[4]

      for (d in 0 until 2) {
        if (cancelled) return null
        onProgress("Step ${step + 1}/4 · double ${d + 1}/2", baseProg + 0.03f + d * 0.02f)
        val dOut = runGpuWithCpuFallback("kc_double$d.tflite", listOf(imgTok, txtTok, ditCos, ditSin, modImg, modTxt))
        imgTok = dOut[0]
        txtTok = dOut[1]
      }

      var stream = FloatArray(txtTok.size + imgTok.size)
      System.arraycopy(txtTok, 0, stream, 0, txtTok.size)
      System.arraycopy(imgTok, 0, stream, txtTok.size, imgTok.size)

      for (s in 0 until 4) {
        if (cancelled) return null
        onProgress("Step ${step + 1}/4 · single ${s + 1}/4", baseProg + 0.08f + s * 0.015f)
        stream = runGpuWithCpuFallback("kc_single$s.tflite", listOf(stream, ditCos, ditSin, modSingle))[0]
      }
      if (cancelled) return null

      onProgress("Step ${step + 1}/4 · final", baseProg + 0.145f)
      val vel = runGpuWithCpuFallback("kc_final.tflite", listOf(stream, temb[step]))[0]
      val dt = sigmas[step + 1] - sigmas[step]
      for (i in 0 until 32768) {
        latents[i] = vel[i] * dt + latents[i]
      }
    }
    if (cancelled) return null

    onProgress("Decoding image", 0.95f)
    val rgb = runGpuWithCpuFallback("kv_vae.tflite", listOf(unpatchLatents(latents)))[0]
    onProgress("Done", 1.0f)
    val pixels = IntArray(65536)
    for (i in 0 until 65536) {
      val r = ((rgb[i] / 2f + 0.5f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
      val g = ((rgb[65536 + i] / 2f + 0.5f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
      val b = ((rgb[131072 + i] / 2f + 0.5f).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
      pixels[i] = AndroidColor.rgb(r, g, b)
    }
    return Bitmap.createBitmap(pixels, 256, 256, Bitmap.Config.ARGB_8888)
  }

  fun close() {
    tokenizer = null
    modelDir = null
    embedMap = null
    runCatching { embedRaf?.close() }
    embedRaf = null
    runCatching { env?.close() }
    env = null
  }
}

private fun kleinFile(size: Long, name: String, repoRel: String) = ModelDataFile(
  name = name,
  url = "https://huggingface.co/litert-community/FLUX.2-klein-4B-LiteRT/resolve/main/$repoRel?download=true",
  downloadFileName = name,
  sizeInBytes = size,
)

class FluxKleinTask @Inject constructor() : CustomTask {
  private val model = Model(
    name = "FLUX.2 klein 4B",
    info =
      "Black Forest Labs' FLUX.2 [klein] 4B diffusion transformer running on phone GPU via chunked " +
        "LiteRT graphs. Produces 256×256 images in 4 steps (~7.4 GB download).",
    url = "https://huggingface.co/litert-community/FLUX.2-klein-4B-LiteRT/resolve/main/ke_enc0.tflite?download=true",
    sizeInBytes = 912_190_032L,
    downloadFileName = "ke_enc0.tflite",
    version = "main",
    extraDataFiles = listOf(
      kleinFile(912_190_032L, "ke_enc1.tflite", "ke_enc1.tflite"),
      kleinFile(912_190_032L, "ke_enc2.tflite", "ke_enc2.tflite"),
      kleinFile(166_174_032L, "kc_prep.tflite", "kc_prep.tflite"),
      kleinFile(738_688_720L, "kc_double0.tflite", "kc_double0.tflite"),
      kleinFile(492_460_800L, "kc_double1.tflite", "kc_double1.tflite"),
      kleinFile(615_367_264L, "kc_single0.tflite", "kc_single0.tflite"),
      kleinFile(615_367_264L, "kc_single1.tflite", "kc_single1.tflite"),
      kleinFile(615_367_264L, "kc_single2.tflite", "kc_single2.tflite"),
      kleinFile(615_367_264L, "kc_single3.tflite", "kc_single3.tflite"),
      kleinFile(19_348_608L, "kc_final.tflite", "kc_final.tflite"),
      kleinFile(50_207_984L, "kv_vae.tflite", "kv_vae.tflite"),
      kleinFile(777_912_320L, "qwen_embed_fp16.bin", "tokenizer/qwen_embed_fp16.bin"),
      kleinFile(1_521_491L, "qwen_vocab.txt", "tokenizer/qwen_vocab.txt"),
      kleinFile(1_671_838L, "qwen_merges.txt", "tokenizer/qwen_merges.txt"),
      kleinFile(547L, "qwen_special.txt", "tokenizer/qwen_special.txt"),
    ),
    minDeviceMemoryInGb = 10,
    learnMoreUrl = "https://huggingface.co/litert-community/FLUX.2-klein-4B-LiteRT",
    showRunAgainButton = false,
    showBenchmarkButton = false,
  )

  override val task: Task = Task(
    id = BuiltInTaskId.FLUX_KLEIN,
    label = "FLUX.2 klein",
    category = Category.IMAGE_GEN,
    icon = Icons.Outlined.AutoAwesome,
    models = mutableListOf(model),
    description =
      "Text-to-image with Black Forest Labs' FLUX.2 [klein] 4B diffusion transformer, running " +
        "fully on phone GPU via chunked LiteRT graphs. 256×256, 4 steps.",
    shortDescription = "FLUX.2 on-device text-to-image",
    showRunAgainButton = false,
    showBenchmarkButton = false,
  )

  override fun initializeModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: (String) -> Unit) {
    coroutineScope.launch(Dispatchers.IO) {
      val dir = File(model.getPath(context, model.downloadFileName)).parentFile
      if (dir == null) {
        onDone("Failed to locate FLUX.2 klein files")
        return@launch
      }
      val engine = FluxKleinEngine()
      if (engine.load(context, dir)) {
        model.instance = engine
        onDone("")
      } else {
        onDone("Failed to load FLUX.2 klein (check that all files finished downloading)")
      }
    }
  }

  override fun cleanUpModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: () -> Unit) {
    val engine = model.instance as? FluxKleinEngine
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
    val engine = model.instance as? FluxKleinEngine
    val scope = rememberCoroutineScope()

    var generating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LiteRtImageGenScreen(
      title = "FLUX.2 klein 4B · 256×256",
      filePrefix = "fluxklein",
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
          if (bmp != null) bitmap = bmp else error = "Generation failed or was cancelled."
        }
      },
      onCancel = { engine?.cancelled = true },
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
object FluxKleinTaskModule {
  @Provides
  @IntoSet
  fun provideFluxKleinTask(): CustomTask = FluxKleinTask()
}
