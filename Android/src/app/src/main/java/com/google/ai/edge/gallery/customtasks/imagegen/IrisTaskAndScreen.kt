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
import com.google.ai.edge.litert.TensorBuffer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.Random
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "IrisEngine"

private data class IrisGraphSpec(
  val role: String,
  val file: String,
  val accel: String,
  val precision: String,
  val signature: String,
  val inputs: List<String>,
  val outputs: List<Pair<String, Int>>,
  val threads: Int,
  val xnnpackCache: Boolean,
)

private class IrisCompiledHandle(
  val spec: IrisGraphSpec,
  val model: CompiledModel,
  val inBufs: Map<String, TensorBuffer>,
  val outBufs: Map<String, TensorBuffer>,
) {
  fun close() {
    inBufs.values.forEach { runCatching { it.close() } }
    outBufs.values.forEach { runCatching { it.close() } }
    runCatching { model.close() }
  }
}

class IrisEngine {
  private var env: Environment? = null
  private var modelDir: File? = null
  private var tokenizer: QwenBpeTokenizer? = null
  private var prefixIds: IntArray = IntArray(0)
  private var suffixIds: IntArray = IntArray(0)
  private var embedRaf: RandomAccessFile? = null
  private var embedBuf: ShortBuffer? = null
  private var hostFile: File? = null
  private val hostTensors = HashMap<String, Pair<Long, Int>>()
  private val teGraphs = ArrayList<IrisGraphSpec>()
  private lateinit var adapterGraph: IrisGraphSpec
  private val dualGraphs = ArrayList<IrisGraphSpec>()
  private val dualModRows = ArrayList<Pair<Int, Int>>()
  private val singleGraphs = ArrayList<IrisGraphSpec>()
  private val singleModRows = ArrayList<Pair<Int, Int>>()
  private lateinit var pixelGraph: IrisGraphSpec
  private val patchUnpackMap: IntArray = IntArray(1769472).also { map ->
    var k = 0
    for (by in 0 until 48) {
      for (bx in 0 until 48) {
        for (py in 0 until 16) {
          for (px in 0 until 16) {
            for (c in 0 until 3) {
              map[k++] = (bx shl 4) + px + (((by shl 4) + py) * 768) + (589824 * c)
            }
          }
        }
      }
    }
  }

  @Volatile var cancelled = false

  fun load(context: Context, dir: File): Boolean = try {
    close()
    modelDir = dir
    val cfgText = File(dir, "iris3b_config.json").readText(Charsets.UTF_8)
    val cfg = JSONObject(cfgText)

    // Unpack tokenizer bundle
    val tokDir = File(context.filesDir, "iris/tok").apply { mkdirs() }
    val tokBundle = JSONObject(File(dir, cfg.getString("tokenizer")).readText(Charsets.UTF_8))
    val tokFiles = tokBundle.getJSONObject("files")
    val fileKeys = tokFiles.keys()
    while (fileKeys.hasNext()) {
      val name = fileKeys.next()
      File(tokDir, name).writeText(tokFiles.getString(name), Charsets.UTF_8)
    }
    tokenizer = QwenBpeTokenizer.fromPlainFiles(
      vocabFile = File(tokDir, "qwen_vocab.txt"),
      mergesFile = File(tokDir, "qwen_merges.txt"),
      specialFile = File(tokDir, "qwen_added.txt"),
    )
    prefixIds = File(tokDir, "prompt_prefix_ids.txt").readText(Charsets.UTF_8)
      .split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()
    suffixIds = File(tokDir, "prompt_suffix_ids.txt").readText(Charsets.UTF_8)
      .split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()

    // Embed bf16 table
    val raf = RandomAccessFile(File(dir, cfg.getString("embed")), "r")
    embedRaf = raf
    embedBuf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0L, raf.length())
      .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

    // Host tensor offsets
    val hostObj = cfg.getJSONObject("host")
    hostFile = File(dir, hostObj.getString("file"))
    val tensorsObj = hostObj.getJSONObject("tensors")
    val tKeys = tensorsObj.keys()
    while (tKeys.hasNext()) {
      val name = tKeys.next()
      val tInfo = tensorsObj.getJSONObject(name)
      hostTensors[name] = tInfo.getLong("offset") to tInfo.getInt("count")
    }

    val defaultThreads = cfg.optInt("cpu_threads", 4)
    fun parseSpec(obj: JSONObject): IrisGraphSpec {
      val ins = obj.getJSONArray("inputs").let { arr -> List(arr.length()) { arr.getString(it) } }
      val outs = obj.getJSONArray("outputs").let { arr ->
        List(arr.length()) {
          val pair = arr.getJSONArray(it)
          pair.getString(0) to pair.getInt(1)
        }
      }
      return IrisGraphSpec(
        role = obj.getString("role"),
        file = obj.getString("file"),
        accel = obj.optString("accel", "gpu"),
        precision = obj.getString("precision"),
        signature = obj.getString("signature"),
        inputs = ins,
        outputs = outs,
        threads = obj.optInt("threads", defaultThreads),
        xnnpackCache = obj.optBoolean("xnnpack_cache", false),
      )
    }

    fun parseModRows(arr: JSONArray, out: MutableList<Pair<Int, Int>>) {
      for (i in 0 until arr.length()) {
        val pair = arr.getJSONArray(i)
        out.add(pair.getInt(0) to pair.getInt(1))
      }
    }

    val graphs = cfg.getJSONObject("graphs")
    teGraphs.clear()
    graphs.getJSONArray("te").let { arr -> for (i in 0 until arr.length()) teGraphs.add(parseSpec(arr.getJSONObject(i))) }
    adapterGraph = parseSpec(graphs.getJSONObject("adapter"))
    dualGraphs.clear()
    graphs.getJSONArray("dual").let { arr -> for (i in 0 until arr.length()) dualGraphs.add(parseSpec(arr.getJSONObject(i))) }
    dualModRows.clear()
    parseModRows(graphs.getJSONArray("dual_mod_rows"), dualModRows)
    singleGraphs.clear()
    graphs.getJSONArray("single").let { arr -> for (i in 0 until arr.length()) singleGraphs.add(parseSpec(arr.getJSONObject(i))) }
    singleModRows.clear()
    parseModRows(graphs.getJSONArray("single_mod_rows"), singleModRows)
    pixelGraph = parseSpec(graphs.getJSONObject("pixel"))

    env = Environment.create()
    true
  } catch (t: Throwable) {
    Log.e(TAG, "load failed", t)
    close()
    false
  }

  private fun readHostTensor(name: String): FloatArray {
    val (offset, count) = hostTensors[name] ?: error("Missing host tensor: $name")
    val file = hostFile ?: error("Host file missing")
    RandomAccessFile(file, "r").use { raf ->
      val bytes = ByteArray(count * 4)
      raf.seek(offset)
      raf.readFully(bytes)
      val fb = java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
      return FloatArray(count).also { fb.get(it) }
    }
  }

  private fun compileGraph(spec: IrisGraphSpec): IrisCompiledHandle {
    val dir = modelDir ?: error("IrisEngine not loaded")
    val environment = env ?: error("Environment closed")
    val modelFile = File(dir, spec.file)
    val options = if (spec.accel == "cpu") {
      val cacheFile = if (spec.xnnpackCache) File(dir, "${spec.file}.xnnpack_cache").absolutePath else null
      CompiledModel.Options(Accelerator.CPU).apply {
        cpuOptions = CompiledModel.CpuOptions(
          numThreads = spec.threads,
          xnnPackWeightCachePath = cacheFile,
        )
      }
    } else {
      val prec = when (spec.precision) {
        "fp16" -> CompiledModel.GpuOptions.Precision.FP16
        "fp32" -> CompiledModel.GpuOptions.Precision.FP32
        else -> CompiledModel.GpuOptions.Precision.DEFAULT
      }
      CompiledModel.Options(Accelerator.GPU).apply {
        gpuOptions = CompiledModel.GpuOptions(precision = prec)
      }
    }
    val cm = CompiledModel.create(modelFile.absolutePath, options, environment)
    val inBufs = LinkedHashMap<String, TensorBuffer>()
    val outBufs = LinkedHashMap<String, TensorBuffer>()
    try {
      spec.inputs.forEach { name -> inBufs[name] = cm.createInputBuffer(name, spec.signature) }
      spec.outputs.forEach { (name, _) -> outBufs[name] = cm.createOutputBuffer(name, spec.signature) }
      return IrisCompiledHandle(spec, cm, inBufs, outBufs)
    } catch (t: Throwable) {
      inBufs.values.forEach { runCatching { it.close() } }
      outBufs.values.forEach { runCatching { it.close() } }
      runCatching { cm.close() }
      throw t
    }
  }

  private fun runGraph(spec: IrisGraphSpec, inputs: List<FloatArray>): List<FloatArray> {
    if (cancelled) throw IllegalStateException("Cancelled")
    val handle = compileGraph(spec)
    try {
      spec.inputs.forEachIndexed { i, name ->
        handle.inBufs.getValue(name).writeFloat(inputs[i])
      }
      handle.model.run(handle.inBufs, handle.outBufs, spec.signature)
      return spec.outputs.map { (name, _) -> handle.outBufs.getValue(name).readFloat() }
    } finally {
      handle.close()
    }
  }

  fun generate(prompt: String, onProgress: (String, Float) -> Unit): Bitmap? {
    val tok = tokenizer ?: return null
    val embShort = embedBuf ?: return null
    cancelled = false
    val totalUnits = teGraphs.size + 1 + (dualGraphs.size + singleGraphs.size + 1) * 4
    var completedUnits = 0
    fun tick(label: String) {
      completedUnits++
      onProgress(label, (completedUnits.toFloat() / totalUnits.coerceAtLeast(1)).coerceIn(0f, 1f))
    }

    onProgress("Tokenizing prompt", 0.0f)
    val encoded = tok.encode(prompt)
    val maxPromptLen = 300 - suffixIds.size
    val used = minOf(encoded.size, maxPromptLen)
    val tokenIds = IntArray(334) { 151643 }
    System.arraycopy(prefixIds, 0, tokenIds, 0, minOf(prefixIds.size, 334))
    for (i in 0 until used) {
      tokenIds[prefixIds.size + i] = encoded[i]
    }
    System.arraycopy(suffixIds, 0, tokenIds, prefixIds.size + used, minOf(suffixIds.size, 334 - (prefixIds.size + used)))
    val totalSeqLen = prefixIds.size + used + suffixIds.size

    // BF16 -> FP32 embedding lookup
    var hidden = FloatArray(334 * 2560)
    for (t in 0 until 334) {
      val base = tokenIds[t].coerceIn(0, 151935) * 2560
      val dst = t * 2560
      for (d in 0 until 2560) {
        hidden[dst + d] = Float.intBitsToFloat(embShort.get(base + d).toInt() shl 16)
      }
    }

    val causalMask1 = FloatArray(334 * 334)
    for (r in 0 until 334) {
      val row = r * 334
      for (c in 0 until 334) {
        causalMask1[row + c] = if (c > r || c >= totalSeqLen) -30000f else 0f
      }
    }
    val causalMask = FloatArray(32 * 334 * 334)
    for (h in 0 until 32) {
      System.arraycopy(causalMask1, 0, causalMask, h * 111556, 111556)
    }

    val promptKeep = FloatArray(300) { i -> if (i + 34 < totalSeqLen) 1f else 0f }
    val teRopeCos = readHostTensor("te_rope_cos")
    val teRopeSin = readHostTensor("te_rope_sin")
    val packedTe = FloatArray(300 * 12 * 2560)

    teGraphs.forEachIndexed { idx, spec ->
      if (cancelled) return null
      tick("Text encoder ${idx + 1}/${teGraphs.size}")
      hidden = runGraph(spec, listOf(hidden, causalMask, teRopeCos, teRopeSin))[0]
      for (t in 0 until 300) {
        val w = promptKeep[t]
        val src = (t + 34) * 2560
        val dst = (t * teGraphs.size + idx) * 2560
        for (d in 0 until 2560) packedTe[dst + d] = hidden[src + d] * w
      }
    }
    if (cancelled) return null

    // Adapter attention bias (20 heads x 300x300)
    val adMask1 = FloatArray(90000)
    for (r in 0 until 300) {
      val row = r * 300
      for (c in 0 until 300) {
        adMask1[row + c] = if (promptKeep[c] != 0f || r == c) 0f else -30000f
      }
    }
    val adMask = FloatArray(20 * 90000)
    for (h in 0 until 20) System.arraycopy(adMask1, 0, adMask, h * 90000, 90000)
    tick("Adapter")
    val y0 = runGraph(adapterGraph, listOf(packedTe, adMask, readHostTensor("y_pos")))[0]

    val sigmas = readHostTensor("sigmas")
    val cosJ = readHostTensor("cos_j")
    val sinJ = readHostTensor("sin_j")
    val cosI = readHostTensor("cos_i")
    val sinI = readHostTensor("sin_i")
    val pixPos = readHostTensor("pix_pos")

    val rng = Random(System.currentTimeMillis())
    val latentLen = 2304 * 768
    var latents = FloatArray(latentLen) { rng.nextGaussian().toFloat() }
    val x0Pred = FloatArray(latentLen)

    for (step in sigmas.indices) {
      if (cancelled) return null
      val mods = readHostTensor("mods_s$step")
      val tEmb = readHostTensor("t_emb_s$step")
      var xStream = latents
      var yStream = y0

      dualGraphs.forEachIndexed { dIdx, spec ->
        if (cancelled) return null
        tick("Step ${step + 1}/${sigmas.size} · dual ${dIdx + 1}/${dualGraphs.size}")
        val (r0, r1) = dualModRows[dIdx]
        val modSlice = mods.copyOfRange(r0 * 2560, r1 * 2560)
        val outs = runGraph(spec, listOf(xStream, yStream, cosJ, sinJ, modSlice))
        xStream = outs.first { it.size == 5898240 }
        yStream = outs.first { it.size == 768000 }
      }

      var joint = FloatArray(yStream.size + xStream.size)
      System.arraycopy(yStream, 0, joint, 0, yStream.size)
      System.arraycopy(xStream, 0, joint, yStream.size, xStream.size)

      singleGraphs.forEachIndexed { sIdx, spec ->
        if (cancelled) return null
        tick("Step ${step + 1}/${sigmas.size} · single ${sIdx + 1}/${singleGraphs.size}")
        val (r0, r1) = singleModRows[sIdx]
        val modSlice = mods.copyOfRange(r0 * 2560, r1 * 2560)
        joint = runGraph(spec, listOf(joint, cosJ, sinJ, modSlice))[0]
      }
      if (cancelled) return null

      tick("Step ${step + 1}/${sigmas.size} · pixel")
      val xTail = joint.copyOfRange(768000, joint.size)
      val vPred = runGraph(pixelGraph, listOf(xTail, tEmb, latents, pixPos, cosI, sinI))[0]
      val sigmaCur = sigmas[step]
      val nextSigma = sigmas.getOrNull(step + 1)
      for (i in 0 until latentLen) {
        x0Pred[i] = latents[i] - vPred[i] * sigmaCur
      }
      if (nextSigma != null) {
        val w1 = 1f - sigmaCur
        val w2 = 1f - nextSigma
        val next = FloatArray(latentLen)
        for (i in 0 until latentLen) {
          val eps = (latents[i] - w1 * x0Pred[i]) / sigmaCur
          next[i] = nextSigma * eps + w2 * x0Pred[i]
        }
        latents = next
      }
    }

    onProgress("Done", 1.0f)
    val clamped = FloatArray(latentLen) { i -> x0Pred[i].coerceIn(-1f, 1f) }
    val reassembled = FloatArray(latentLen)
    for (i in patchUnpackMap.indices) {
      reassembled[patchUnpackMap[i]] = clamped[i]
    }
    fun toU8(f: Float): Int = ((f + 1f) * 127.5f + 0.5f).toInt().coerceIn(0, 255)
    val pixels = IntArray(589824) { p ->
      AndroidColor.rgb(
        toU8(reassembled[p]),
        toU8(reassembled[589824 + p]),
        toU8(reassembled[1179648 + p]),
      )
    }
    return Bitmap.createBitmap(pixels, 768, 768, Bitmap.Config.ARGB_8888)
  }

  fun close() {
    tokenizer = null
    modelDir = null
    embedBuf = null
    hostFile = null
    runCatching { embedRaf?.close() }
    embedRaf = null
    runCatching { env?.close() }
    env = null
  }
}

private fun irisFile(size: Long, name: String) = ModelDataFile(
  name = name,
  url = "https://huggingface.co/litert-community/iris-3b-Turbo-LiteRT/resolve/907ef7cdfbd476cfd37118fe3fc26741a00ea881/model/$name?download=true",
  downloadFileName = name,
  sizeInBytes = size,
)

class IrisTask @Inject constructor() : CustomTask {
  private val model = Model(
    name = "iris-3b Turbo",
    info =
      "Speridlabs' iris-3b Turbo generates 768×768 images directly in 4 pixel-space diffusion steps " +
        "on phone GPU (~13.7 GB download; recommended 12+ GB RAM).",
    url = "https://huggingface.co/litert-community/iris-3b-Turbo-LiteRT/resolve/907ef7cdfbd476cfd37118fe3fc26741a00ea881/model/iris3b_config.json?download=true",
    sizeInBytes = 19_020L,
    downloadFileName = "iris3b_config.json",
    version = "907ef7cdfbd476cfd37118fe3fc26741a00ea881",
    extraDataFiles = listOf(
      irisFile(500_935_504L, "iris_dit_adapter_f16w_dit.tflite"),
      irisFile(908_769_344L, "iris_dit_dual0_f16w_dit.tflite"),
      irisFile(904_826_192L, "iris_dit_dual1_f16w_dit.tflite"),
      irisFile(603_218_928L, "iris_dit_dual2_f16w_dit.tflite"),
      irisFile(472_337_056L, "iris_dit_pixel_f16w_dit.tflite"),
      irisFile(904_848_944L, "iris_dit_single0_f16w_dit.tflite"),
      irisFile(904_848_944L, "iris_dit_single1_f16w_dit.tflite"),
      irisFile(603_233_696L, "iris_dit_single2_f16w_dit.tflite"),
      irisFile(777_912_320L, "iris_embed_bf16.f32"),
      irisFile(58_658_832L, "iris_host.f32"),
      irisFile(403_776_672L, "iris_te_c00_f16w.tflite"),
      irisFile(605_663_872L, "iris_te_c01_f16w.tflite"),
      irisFile(605_663_872L, "iris_te_c02_f16w.tflite"),
      irisFile(605_663_872L, "iris_te_c03_f16w.tflite"),
      irisFile(605_663_872L, "iris_te_c04_f16w.tflite"),
      irisFile(605_663_872L, "iris_te_c05_f16w.tflite"),
      irisFile(605_663_872L, "iris_te_c06_f16w.tflite"),
      irisFile(605_663_872L, "iris_te_c07_f16w.tflite"),
      irisFile(605_663_872L, "iris_te_c08_f16w.tflite"),
      irisFile(605_663_872L, "iris_te_c09_f16w.tflite"),
      irisFile(605_663_872L, "iris_te_c10_f16w.tflite"),
      irisFile(605_663_872L, "iris_te_c11_f16w.tflite"),
      irisFile(6_649_288L, "iris_tokenizer.json"),
    ),
    minDeviceMemoryInGb = 12,
    learnMoreUrl = "https://huggingface.co/litert-community/iris-3b-Turbo-LiteRT",
    showRunAgainButton = false,
    showBenchmarkButton = false,
  )

  override val task: Task = Task(
    id = BuiltInTaskId.IRIS,
    label = "iris-3b",
    category = Category.IMAGE_GEN,
    icon = Icons.Outlined.AutoAwesome,
    models = mutableListOf(model),
    description =
      "Text-to-image with Speridlabs' iris-3b Turbo pixel-space diffusion transformer on phone GPU " +
        "via 20 LiteRT graphs. 768×768, 4 steps.",
    shortDescription = "iris-3b on-device text-to-image",
    showRunAgainButton = false,
    showBenchmarkButton = false,
  )

  override fun initializeModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: (String) -> Unit) {
    coroutineScope.launch(Dispatchers.IO) {
      val dir = File(model.getPath(context, model.downloadFileName)).parentFile
      if (dir == null) {
        onDone("Failed to locate iris-3b files")
        return@launch
      }
      val engine = IrisEngine()
      if (engine.load(context, dir)) {
        model.instance = engine
        onDone("")
      } else {
        onDone("Failed to load iris-3b (check that all files finished downloading)")
      }
    }
  }

  override fun cleanUpModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: () -> Unit) {
    val engine = model.instance as? IrisEngine
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
    val engine = model.instance as? IrisEngine
    val scope = rememberCoroutineScope()

    var generating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LiteRtImageGenScreen(
      title = "iris-3b Turbo · 768×768",
      filePrefix = "iris",
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
object IrisTaskModule {
  @Provides
  @IntoSet
  fun provideIrisTask(): CustomTask = IrisTask()
}
