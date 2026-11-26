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
import kotlin.math.exp
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ZImageEngine"

class ZImageEngine {
  private var env: Environment? = null
  private var tokenizer: QwenBpeTokenizer? = null
  private var modelDir: File? = null
  private var embedRaf: RandomAccessFile? = null
  private var embedMap: MappedByteBuffer? = null
  private lateinit var capPadToken: FloatArray
  private lateinit var tembMlp: FloatArray
  @Volatile var cancelled = false

  fun load(context: Context, model: Model): Boolean = try {
    close()
    val dir = File(model.getPath(context, model.downloadFileName)).parentFile ?: return false
    modelDir = dir
    val fallbackDir = context.getExternalFilesDir(null)?.let {
      File(it, "FLUX_2_klein_4B/main")
    }
    val tokDir = listOfNotNull(dir, fallbackDir).firstOrNull {
      File(it, "qwen_vocab.txt").exists() && File(it, "qwen_embed_fp16.bin").exists()
    } ?: return false

    tokenizer = QwenBpeTokenizer.fromPlainFiles(
      File(tokDir, "qwen_vocab.txt"),
      File(tokDir, "qwen_merges.txt"),
      File(tokDir, "qwen_special.txt"),
    )
    val raf = RandomAccessFile(File(tokDir, "qwen_embed_fp16.bin"), "r")
    embedRaf = raf
    embedMap = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0L, raf.length()).also { it.order(ByteOrder.LITTLE_ENDIAN) }
    capPadToken = readAssetFloats(context, "zimage/zimage_cap_pad_token.bin")
    tembMlp = readAssetFloats(context, "zimage/zimage_temb_mlp.bin")
    require(capPadToken.size == 3840 && tembMlp.size == 525568)
    env = Environment.create()
    true
  } catch (t: Throwable) {
    Log.e(TAG, "load failed", t)
    close()
    false
  }

  private fun runGpuWithCpuFallback(fileName: String, inputs: List<FloatArray>): List<FloatArray> {
    val dir = modelDir ?: error("ZImageEngine not loaded")
    val environment = env ?: error("Environment closed")
    val gpuOpts = CompiledModel.Options(Accelerator.GPU).apply {
      gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
    }
    val cm = try {
      CompiledModel.create(File(dir, fileName).absolutePath, gpuOpts, environment)
    } catch (t: Throwable) {
      Log.w(TAG, "$fileName failed on GPU, retrying on CPU", t)
      CompiledModel.create(File(dir, fileName).absolutePath, CompiledModel.Options(Accelerator.CPU), environment)
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

  private fun rope3d(coords: Array<IntArray>): Pair<FloatArray, FloatArray> {
    val dims = intArrayOf(32, 48, 48)
    val cos = FloatArray(coords.size shl 6)
    val sin = FloatArray(coords.size shl 6)
    for (n in coords.indices) {
      var off = 0
      for (axis in 0 until 3) {
        val dim = dims[axis]
        val half = dim / 2
        for (i in 0 until half) {
          val theta = coords[n][axis].toDouble() / 256.0.pow((i * 2.0) / dim.toDouble())
          val idx = (n shl 6) + off + i
          cos[idx] = kotlin.math.cos(theta).toFloat()
          sin[idx] = kotlin.math.sin(theta).toFloat()
        }
        off += half
      }
    }
    return cos to sin
  }

  private fun computeTemb(t: Float): FloatArray {
    val fourier = FloatArray(256)
    for (i in 0 until 128) {
      val angle = t.toDouble() * 1000.0 * exp(-9.210340371976184 * i / 128.0)
      fourier[i] = kotlin.math.cos(angle).toFloat()
      fourier[128 + i] = kotlin.math.sin(angle).toFloat()
    }
    val hidden = FloatArray(1024)
    for (i in 0 until 1024) {
      var sum = tembMlp[262144 + i].toDouble()
      val row = i shl 8
      for (j in 0 until 256) {
        sum += tembMlp[row + j] * fourier[j]
      }
      hidden[i] = (sum / (exp(-sum) + 1.0)).toFloat()
    }
    val out = FloatArray(256)
    for (i in 0 until 256) {
      var sum = tembMlp[525312 + i].toDouble()
      val row = 263168 + (i shl 10)
      for (j in 0 until 1024) {
        sum += tembMlp[row + j] * hidden[j]
      }
      out[i] = sum.toFloat()
    }
    return out
  }

  fun generate(prompt: String, onProgress: (String, Float) -> Unit): Bitmap? {
    val tok = tokenizer ?: return null
    val map = embedMap ?: return null
    cancelled = false
    onProgress("Encoding prompt", 0.0f)

    val rawIds = tok.encode("<|im_start|>user\n$prompt<|im_end|>\n<|im_start|>assistant\n")
    val len = minOf(rawIds.size, 64)
    val ids = IntArray(64) { i -> if (i < len) rawIds[i] else 151643 }
    val emb = FloatArray(64 * 2560)
    for (i in 0 until 64) {
      val tokenId = ids[i].coerceIn(0, 151935)
      val off = tokenId * 5120
      for (d in 0 until 2560) {
        emb[i * 2560 + d] = Half.toFloat(map.getShort(off + (d shl 1)))
      }
    }
    val qwenOut = runGpuWithCpuFallback("qwen_enc.tflite", listOf(emb))[0]
    val capTokCount = minOf(rawIds.size, 32)
    val capIn = FloatArray(32 * 2560)
    System.arraycopy(qwenOut, 0, capIn, 0, capTokCount * 2560)

    val capCoords = Array(32) { i -> intArrayOf(i + 1, 0, 0) }
    val (capCos, capSin) = rope3d(capCoords)
    val imgCoords = Array(256) { i -> intArrayOf(33, i / 16, i % 16) }
    val (imgCos, imgSin) = rope3d(imgCoords)
    val joinedCos = imgCos + capCos
    val joinedSin = imgSin + capSin

    val rng = Random(System.currentTimeMillis())
    val latents = FloatArray(16384) { rng.nextGaussian().toFloat() }
    val sigmas = FloatArray(10)
    for (i in 0 until 9) {
      val d = 1.0 - (8.0 / 9.0) * i / 8.0
      sigmas[i] = ((3.0 * d) / (2.0 * d + 1.0)).toFloat()
    }
    sigmas[9] = 0f

    for (step in 0 until 9) {
      if (cancelled) return null
      val baseProg = step * (0.78f / 9f) + 0.15f
      onProgress("Step ${step + 1}/9", baseProg)
      val temb = computeTemb(1.0f - sigmas[step])

      val patches = FloatArray(16384)
      for (py in 0 until 16) {
        for (px in 0 until 16) {
          val pOff = ((py shl 4) + px) shl 6
          for (sy in 0 until 2) {
            for (sx in 0 until 2) {
              for (c in 0 until 16) {
                patches[pOff + (((sy shl 1) + sx) shl 4) + c] =
                  latents[(px shl 1) + sx + (c shl 10) + (((py shl 1) + sy) shl 5)]
              }
            }
          }
        }
      }

      val embc = runGpuWithCpuFallback("z_embc.tflite", listOf(capIn))[0]
      for (t in capTokCount until 32) {
        System.arraycopy(capPadToken, 0, embc, t * 3840, 3840)
      }
      val refc = runGpuWithCpuFallback("z_refc.tflite", listOf(embc, capCos, capSin))[0]
      if (cancelled) return null

      val embx = runGpuWithCpuFallback("z_embx.tflite", listOf(patches))[0]
      val refx = runGpuWithCpuFallback("z_refx.tflite", listOf(embx, imgCos, imgSin, temb))[0]
      if (cancelled) return null

      var joint = FloatArray(refx.size + refc.size)
      System.arraycopy(refx, 0, joint, 0, refx.size)
      System.arraycopy(refc, 0, joint, refx.size, refc.size)

      for (blk in 0 until 6) {
        if (cancelled) return null
        onProgress("Step ${step + 1}/9 · block ${blk + 1}/6", baseProg + blk * 0.01f)
        joint = runGpuWithCpuFallback("zc_main$blk.tflite", listOf(joint, joinedCos, joinedSin, temb))[0]
      }
      if (cancelled) return null

      val predPatches = runGpuWithCpuFallback("zc_final.tflite", listOf(joint, temb))[0]
      val dt = sigmas[step + 1] - sigmas[step]
      for (py in 0 until 16) {
        for (px in 0 until 16) {
          val pOff = ((py shl 4) + px) shl 6
          for (sy in 0 until 2) {
            for (sx in 0 until 2) {
              for (c in 0 until 16) {
                val latIdx = (px shl 1) + sx + (c shl 10) + (((py shl 1) + sy) shl 5)
                val v = predPatches[pOff + (((sy shl 1) + sx) shl 4) + c]
                latents[latIdx] += v * dt
              }
            }
          }
        }
      }
    }
    if (cancelled) return null

    onProgress("Decoding image", 0.95f)
    val scaled = FloatArray(16384) { i -> latents[i] / 0.3611f + 0.1159f }
    val rgb = runGpuWithCpuFallback("zvae.tflite", listOf(scaled))[0]
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

private fun zImgFile(size: Long, name: String, repo: String = "https://huggingface.co/litert-community/Z-Image-Turbo-LiteRT/resolve/main", path: String = name) =
  ModelDataFile(name = name, url = "$repo/$path?download=true", downloadFileName = name, sizeInBytes = size)

class ZImageTask @Inject constructor() : CustomTask {
  private val model = Model(
    name = "Z-Image Turbo 6B",
    info =
      "Alibaba Tongyi's Z-Image Turbo 6B diffusion transformer running on phone GPU via chunked " +
        "LiteRT graphs. 256×256 output in 9 steps (~10.6 GB download).",
    url = "https://huggingface.co/litert-community/Z-Image-Turbo-LiteRT/resolve/main/qwen_enc.tflite?download=true",
    sizeInBytes = 3_547_652_208L,
    downloadFileName = "qwen_enc.tflite",
    version = "main",
    extraDataFiles = listOf(
      zImgFile(308_272L, "z_embx.tflite"),
      zImgFile(363_386_160L, "z_refx.tflite"),
      zImgFile(9_906_096L, "z_embc.tflite"),
      zImgFile(355_025_040L, "z_refc.tflite"),
      zImgFile(908_480_960L, "zc_main0.tflite"),
      zImgFile(908_480_960L, "zc_main1.tflite"),
      zImgFile(908_480_960L, "zc_main2.tflite"),
      zImgFile(908_480_960L, "zc_main3.tflite"),
      zImgFile(908_480_960L, "zc_main4.tflite"),
      zImgFile(908_480_960L, "zc_main5.tflite"),
      zImgFile(1_295_936L, "zc_final.tflite"),
      zImgFile(50_139_872L, "zvae.tflite"),
      zImgFile(777_912_320L, "qwen_embed_fp16.bin", "https://huggingface.co/litert-community/FLUX.2-klein-4B-LiteRT/resolve/main", "tokenizer/qwen_embed_fp16.bin"),
      zImgFile(1_521_491L, "qwen_vocab.txt", "https://huggingface.co/litert-community/FLUX.2-klein-4B-LiteRT/resolve/main", "tokenizer/qwen_vocab.txt"),
      zImgFile(1_671_838L, "qwen_merges.txt", "https://huggingface.co/litert-community/FLUX.2-klein-4B-LiteRT/resolve/main", "tokenizer/qwen_merges.txt"),
      zImgFile(547L, "qwen_special.txt", "https://huggingface.co/litert-community/FLUX.2-klein-4B-LiteRT/resolve/main", "tokenizer/qwen_special.txt"),
    ),
    minDeviceMemoryInGb = 12,
    learnMoreUrl = "https://huggingface.co/litert-community/Z-Image-Turbo-LiteRT",
    showRunAgainButton = false,
    showBenchmarkButton = false,
  )

  override val task: Task = Task(
    id = BuiltInTaskId.Z_IMAGE,
    label = "Z-Image",
    category = Category.IMAGE_GEN,
    icon = Icons.Outlined.AutoAwesome,
    models = mutableListOf(model),
    description =
      "Text-to-image with Alibaba Tongyi's Z-Image-Turbo 6B diffusion transformer, running " +
        "fully on phone GPU via chunked LiteRT graphs. 256×256, 9 steps.",
    shortDescription = "Z-Image on-device text-to-image",
  )

  override fun initializeModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: (String) -> Unit) {
    coroutineScope.launch(Dispatchers.IO) {
      val engine = ZImageEngine()
      if (engine.load(context, model)) {
        model.instance = engine
        onDone("")
      } else {
        onDone("Failed to load Z-Image (check that all files finished downloading)")
      }
    }
  }

  override fun cleanUpModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: () -> Unit) {
    val engine = model.instance as? ZImageEngine
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
    val engine = model.instance as? ZImageEngine
    val scope = rememberCoroutineScope()

    var generating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LiteRtImageGenScreen(
      title = "Z-Image Turbo 6B · 256×256",
      filePrefix = "zimage",
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
object ZImageTaskModule {
  @Provides
  @IntoSet
  fun provideZImageTask(): CustomTask = ZImageTask()
}
