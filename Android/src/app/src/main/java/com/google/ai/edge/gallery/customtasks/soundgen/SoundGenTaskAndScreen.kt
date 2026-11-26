package com.google.ai.edge.gallery.customtasks.soundgen

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.audio.WavUtils
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDataFile
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.Random
import javax.inject.Inject
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "SoundGenEngine"
private const val SAMPLE_RATE_SG = 44100

/** Minimal SentencePiece protobuf reader supporting both Unigram (SoundGen) and BPE (SoundGen HD). */
class SentencePieceVocab private constructor(
  val pieces: HashMap<String, Int>,
  val scores: FloatArray,
  val byteFallbackIds: IntArray,
  val unkId: Int,
  val maxPieceLen: Int,
  val unkPenalty: Float,
) {
  companion object {
    fun load(file: File): SentencePieceVocab {
      val data = file.readBytes()
      val entries = ArrayList<Triple<String, Float, Int>>(64_000)
      var idx = 0

      fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (idx < data.size) {
          val b = data[idx++].toInt() and 0xFF
          result = result or ((b and 0x7F).toLong() shl shift)
          if ((b and 0x80) == 0) break
          shift += 7
        }
        return result
      }

      fun skipField(wireType: Int) {
        when (wireType) {
          0 -> readVarint()
          1 -> idx += 8
          2 -> idx += readVarint().toInt()
          5 -> idx += 4
        }
      }

      while (idx < data.size) {
        val tag = readVarint()
        val fieldNum = (tag ushr 3).toInt()
        val wireType = (tag and 7L).toInt()
        if (fieldNum == 1 && wireType == 2) {
          val msgEnd = idx + readVarint().toInt()
          var piece = ""
          var score = 0f
          var type = 1
          while (idx < msgEnd) {
            val subTag = readVarint()
            val sf = (subTag ushr 3).toInt()
            val sw = (subTag and 7L).toInt()
            when {
              sf == 1 && sw == 2 -> {
                val len = readVarint().toInt()
                piece = String(data, idx, len, Charsets.UTF_8)
                idx += len
              }
              sf == 2 && sw == 5 -> {
                val bits = ByteBuffer.wrap(data, idx, 4).order(ByteOrder.LITTLE_ENDIAN).int
                idx += 4
                score = Float.fromBits(bits)
              }
              sf == 3 && sw == 0 -> type = readVarint().toInt()
              else -> skipField(sw)
            }
          }
          idx = msgEnd
          entries.add(Triple(piece, score, type))
        } else {
          skipField(wireType)
        }
      }

      val map = HashMap<String, Int>(entries.size * 2)
      val scores = FloatArray(entries.size)
      val byteIds = IntArray(256) { -1 }
      var unk = 2
      var maxLen = 1
      var minScore = 0f

      for ((id, triple) in entries.withIndex()) {
        val (piece, sc, tp) = triple
        scores[id] = sc
        if (sc < minScore) minScore = sc
        if (tp == 2) {
          unk = id
        } else if (tp == 6 && piece.length == 6 && piece.startsWith("<0x") && piece.endsWith(">")) {
          piece.substring(3, 5).toIntOrNull(16)?.takeIf { it in 0..255 }?.let { byteIds[it] = id }
        } else if (piece.isNotEmpty()) {
          map[piece] = id
          if (piece.length > maxLen) maxLen = piece.length
        }
      }

      return SentencePieceVocab(
        pieces = map,
        scores = scores,
        byteFallbackIds = byteIds,
        unkId = unk,
        maxPieceLen = maxLen,
        unkPenalty = minScore - 10f,
      )
    }
  }

  fun encodeUnigram(prompt: String): IntArray {
    val norm = Normalizer.normalize(prompt, Normalizer.Form.NFKC).trim()
      .replace(Regex("\\s+"), " ")
      .replace(' ', '\u2581')
    val s = "\u2581$norm"
    val n = s.length
    if (n == 0) return intArrayOf(1)
    val bestScore = FloatArray(n + 1) { if (it == 0) 0f else -1e30f }
    val back = IntArray(n + 1) { -1 }
    val tokenAt = IntArray(n + 1) { unkId }

    for (i in 0 until n) {
      if (bestScore[i] <= -1e29f) continue
      val limit = min(maxPieceLen, n - i)
      for (len in 1..limit) {
        val id = pieces[s.substring(i, i + len)] ?: continue
        val cand = bestScore[i] + scores[id]
        if (cand > bestScore[i + len]) {
          bestScore[i + len] = cand
          back[i + len] = i
          tokenAt[i + len] = id
        }
      }
      val fb = bestScore[i] + unkPenalty
      if (fb > bestScore[i + 1]) {
        bestScore[i + 1] = fb
        back[i + 1] = i
        tokenAt[i + 1] = unkId
      }
    }

    val ids = ArrayList<Int>()
    var cur = n
    while (cur > 0) {
      ids.add(tokenAt[cur])
      cur = back[cur].coerceAtLeast(0)
    }
    ids.reverse()
    if (ids.lastOrNull() != 1) ids.add(1) // EOS
    return ids.toIntArray()
  }

  fun encodeBpe(prompt: String): IntArray {
    val s = prompt.replace(' ', '\u2581')
    if (s.isEmpty()) return IntArray(0)
    val tokens = ArrayList<String>(s.length)
    var i = 0
    while (i < s.length) {
      val next = i + Character.charCount(s.codePointAt(i))
      tokens.add(s.substring(i, next))
      i = next
    }
    while (tokens.size > 1) {
      var bestScore = Float.NEGATIVE_INFINITY
      var bestIdx = -1
      for (k in 0 until tokens.size - 1) {
        val id = pieces[tokens[k] + tokens[k + 1]] ?: continue
        val sc = scores[id]
        if (sc > bestScore) {
          bestScore = sc
          bestIdx = k
        }
      }
      if (bestIdx < 0) break
      tokens[bestIdx] = tokens[bestIdx] + tokens[bestIdx + 1]
      tokens.removeAt(bestIdx + 1)
    }
    val ids = ArrayList<Int>(tokens.size + 1)
    for (tok in tokens) {
      val id = pieces[tok]
      if (id != null) {
        ids.add(id)
      } else {
        for (b in tok.toByteArray(Charsets.UTF_8)) {
          val bid = byteFallbackIds[b.toInt() and 0xFF]
          ids.add(if (bid >= 0) bid else unkId)
        }
      }
    }
    return ids.toIntArray()
  }
}

class SoundGenEngine(val isHd: Boolean, val latentUnits: Int) {
  private var textModel: CompiledModel? = null
  private var coreModel: CompiledModel? = null
  private var decodeModel: CompiledModel? = null
  private var vocab: SentencePieceVocab? = null

  private val maxFrames: Int = latentUnits * 2048 // 524,288 for L256 / standard; 4,194,304 for L2048
  private val latentLen: Int = latentUnits * 64 // 16,384 for L256; 131,072 for L2048

  fun load(
    textPath: String,
    corePath: String,
    decodePath: String,
    vocabPath: String,
  ): Boolean {
    return try {
      release()
      textModel = CompiledModel.create(textPath, CompiledModel.Options(Accelerator.CPU))
      coreModel = CompiledModel.create(corePath, CompiledModel.Options(Accelerator.CPU))
      decodeModel = CompiledModel.create(decodePath, CompiledModel.Options(Accelerator.CPU))
      vocab = SentencePieceVocab.load(File(vocabPath))
      true
    } catch (t: Throwable) {
      Log.e(TAG, "Failed to load SoundGen models", t)
      release()
      false
    }
  }

  suspend fun generate(
    context: Context,
    prompt: String,
    durationSec: Float,
    seed: Long = System.nanoTime(),
    onProgress: (Float) -> Unit,
  ): String? = withContext(Dispatchers.Default) {
    val tModel = textModel ?: return@withContext null
    val cModel = coreModel ?: return@withContext null
    val dModel = decodeModel ?: return@withContext null
    val sp = vocab ?: return@withContext null

    val tokenIds = if (isHd) sp.encodeBpe(prompt) else sp.encodeUnigram(prompt)
    val seqLen = if (isHd) 256 else 128
    val inputIds = LongArray(seqLen)
    val attnMask = LongArray(seqLen)
    for (i in 0 until min(tokenIds.size, seqLen)) {
      inputIds[i] = tokenIds[i].toLong()
      attnMask[i] = 1L
    }

    val tIn = tModel.createInputBuffers()
    val tOut = tModel.createOutputBuffers()
    val cond0: FloatArray
    val cond2: FloatArray
    try {
      tIn[0].writeLong(inputIds)
      tIn[1].writeLong(attnMask)
      if (!isHd && tIn.size > 2) {
        tIn[2].writeFloat(floatArrayOf(durationSec))
      }
      tModel.run(tIn, tOut)
      cond0 = tOut[0].readFloat()
      cond2 = if (tOut.size > 2) tOut[2].readFloat() else tOut[1].readFloat()
    } finally {
      tIn.forEach { runCatching { it.close() } }
      tOut.forEach { runCatching { it.close() } }
    }
    onProgress(0.1f)

    // 8-step sigmoid schedule matching SoundGen / SoundGen HD
    val steps = 8
    val sigmas = FloatArray(steps + 1)
    sigmas[0] = 1f
    for (s in 1 until steps) {
      val v = -6f + (8f * s / steps)
      sigmas[s] = 1f / (exp(v) + 1f)
    }
    sigmas[steps] = 0f

    val rng = Random(seed)
    var latents = FloatArray(latentLen) { rng.nextGaussian().toFloat() }

    val cIn = cModel.createInputBuffers()
    val cOut = cModel.createOutputBuffers()
    try {
      cIn[0].writeFloat(cond0)
      cIn[1].writeFloat(cond2)
      for (step in 0 until steps) {
        val sigma = sigmas[step]
        val sigmaNext = sigmas[step + 1]
        cIn[2].writeFloat(latents)
        cIn[3].writeFloat(floatArrayOf(sigma))
        cModel.run(cIn, cOut)
        val pred = cOut[0].readFloat()
        val noiseRng = Random(seed + step + 4564L)
        val next = FloatArray(latentLen) { k ->
          val eps = noiseRng.nextGaussian().toFloat()
          (eps * sigmaNext) + (1f - sigmaNext) * (latents[k] - pred[k] * sigma)
        }
        latents = next
        onProgress(0.1f + 0.8f * (step + 1) / steps)
      }
    } finally {
      cIn.forEach { runCatching { it.close() } }
      cOut.forEach { runCatching { it.close() } }
    }

    val dIn = dModel.createInputBuffers()
    val dOut = dModel.createOutputBuffers()
    val audioPlanar: FloatArray
    try {
      dIn[0].writeFloat(latents)
      dModel.run(dIn, dOut)
      audioPlanar = dOut[0].readFloat()
    } finally {
      dIn.forEach { runCatching { it.close() } }
      dOut.forEach { runCatching { it.close() } }
    }
    onProgress(0.96f)

    val outDir = File(context.cacheDir, if (isHd) "soundgenhd" else "soundgen")
    outDir.mkdirs()
    val outFile = File(outDir, "soundgen_${System.currentTimeMillis()}.wav")
    val targetFrames = (durationSec * SAMPLE_RATE_SG).roundToInt().coerceIn(1, maxFrames)
    WavUtils.writeStereoPlanarFloatWav(
      file = outFile,
      planar = audioPlanar,
      totalFramesInPlanar = maxFrames,
      writeFrames = targetFrames,
      sampleRate = SAMPLE_RATE_SG,
      normalize = isHd,
    )
    onProgress(1f)
    outFile.absolutePath
  }

  fun release() {
    runCatching { textModel?.close() }
    runCatching { coreModel?.close() }
    runCatching { decodeModel?.close() }
    textModel = null
    coreModel = null
    decodeModel = null
    vocab = null
  }
}

data class SoundGenUiState(
  val prompt: String = "",
  val durationSec: Int = 8,
  val isGenerating: Boolean = false,
  val progress: Float = 0f,
  val wavPath: String? = null,
  val errorMessage: String? = null,
)

@HiltViewModel
class SoundGenViewModel @Inject constructor() : ViewModel() {
  private val _uiState = MutableStateFlow(SoundGenUiState())
  val uiState: StateFlow<SoundGenUiState> = _uiState.asStateFlow()

  fun onPromptChange(value: String) = _uiState.update { it.copy(prompt = value) }
  fun onDurationChange(sec: Int) = _uiState.update { it.copy(durationSec = sec) }

  fun generate(context: Context, model: Model) {
    val engine = model.instance as? SoundGenEngine ?: run {
      _uiState.update { it.copy(errorMessage = "Model not loaded") }
      return
    }
    val prompt = _uiState.value.prompt.trim()
    if (prompt.isEmpty() || _uiState.value.isGenerating) return
    _uiState.update { it.copy(isGenerating = true, progress = 0f, errorMessage = null) }

    viewModelScope.launch {
      try {
        val wavPath = engine.generate(
          context = context,
          prompt = prompt,
          durationSec = _uiState.value.durationSec.toFloat(),
        ) { p ->
          _uiState.update { it.copy(progress = p) }
        }
        if (wavPath == null) {
          _uiState.update { it.copy(isGenerating = false, errorMessage = "Generation failed") }
        } else {
          _uiState.update { it.copy(isGenerating = false, progress = 1f, wavPath = wavPath) }
        }
      } catch (t: Throwable) {
        _uiState.update { it.copy(isGenerating = false, errorMessage = "Generation failed: ${t.message}") }
      }
    }
  }
}

private fun makeSoundGenHdTask(
  id: String,
  label: String,
  shortDesc: String,
  longDesc: String,
  modelName: String,
  ditFile: String,
  ditBytes: Long,
  decFile: String,
  decBytes: Long,
  latentUnits: Int,
): CustomTask = object : CustomTask {
  override val task: Task = Task(
    id = id,
    label = label,
    category = Category.VOICE,
    icon = Icons.Outlined.MusicNote,
    models = mutableListOf(
      Model(
        name = modelName,
        info = longDesc,
        url = "https://huggingface.co/jegly/noise/resolve/main/$ditFile",
        sizeInBytes = ditBytes,
        downloadFileName = "sghd_core.litert",
        version = "v1.0.0",
        extraDataFiles = listOf(
          ModelDataFile("sghd_decode.litert", "https://huggingface.co/jegly/noise/resolve/main/$decFile", "sghd_decode.litert", decBytes),
          ModelDataFile("sghd_text.litert", "https://huggingface.co/jegly/noise/resolve/main/t5gemma_enc_int8.tflite", "sghd_text.litert", 286_972_704L),
          ModelDataFile("sghd_vocab.spm", "https://huggingface.co/jegly/noise/resolve/main/tokenizer.model", "sghd_vocab.spm", 4_241_003L),
        ),
      )
    ),
    description = longDesc,
    shortDescription = shortDesc,
  )

  override fun initializeModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: (String) -> Unit) {
    coroutineScope.launch(Dispatchers.Default) {
      val engine = SoundGenEngine(isHd = true, latentUnits = latentUnits)
      val ok = engine.load(
        textPath = model.getPath(context, "sghd_text.litert"),
        corePath = model.getPath(context, model.downloadFileName),
        decodePath = model.getPath(context, "sghd_decode.litert"),
        vocabPath = model.getPath(context, "sghd_vocab.spm"),
      )
      if (ok) {
        model.instance = engine
        onDone("")
      } else {
        onDone("Failed to load $label models")
      }
    }
  }

  override fun cleanUpModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: () -> Unit) {
    (model.instance as? SoundGenEngine)?.release()
    model.instance = null
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    SoundGenScreen(modelManagerViewModel = myData.modelManagerViewModel, maxDurationSec = if (latentUnits > 256) 180 else 24)
  }
}

class SoundGenTask @Inject constructor() : CustomTask {
  override val task: Task = Task(
    id = BuiltInTaskId.SOUNDGEN,
    label = "SoundGen",
    category = Category.VOICE,
    icon = Icons.Outlined.MusicNote,
    models = mutableListOf(
      Model(
        name = "SoundGen",
        info = "On-device text-to-audio — describe a sound or piece of music and generate it offline. Downloads ~1.1GB on first use.",
        url = "https://huggingface.co/jegly/audio/resolve/main/dit_model.tflite",
        sizeInBytes = 344_293_232L,
        downloadFileName = "sg_core.litert",
        version = "v1.0.0",
        extraDataFiles = listOf(
          ModelDataFile("sg_text.litert", "https://huggingface.co/jegly/audio/resolve/main/conditioners_float32.tflite", "sg_text.litert", 440_190_572L),
          ModelDataFile("sg_decode.litert", "https://huggingface.co/jegly/audio/resolve/main/autoencoder_model.tflite", "sg_decode.litert", 312_588_244L),
          ModelDataFile("sg_vocab.spm", "https://huggingface.co/jegly/audio/resolve/main/spiece.model", "sg_vocab.spm", 791_656L),
        ),
      )
    ),
    description = "Describe a sound or piece of music and generate it on-device — fully offline. Choose a length, then play, export or share the result.",
    shortDescription = "On-device text-to-audio",
  )

  override fun initializeModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: (String) -> Unit) {
    coroutineScope.launch(Dispatchers.Default) {
      val engine = SoundGenEngine(isHd = false, latentUnits = 256)
      val ok = engine.load(
        textPath = model.getPath(context, "sg_text.litert"),
        corePath = model.getPath(context, model.downloadFileName),
        decodePath = model.getPath(context, "sg_decode.litert"),
        vocabPath = model.getPath(context, "sg_vocab.spm"),
      )
      if (ok) {
        model.instance = engine
        onDone("")
      } else {
        onDone("Failed to load SoundGen")
      }
    }
  }

  override fun cleanUpModelFn(context: Context, coroutineScope: CoroutineScope, model: Model, onDone: () -> Unit) {
    (model.instance as? SoundGenEngine)?.release()
    model.instance = null
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    SoundGenScreen(modelManagerViewModel = myData.modelManagerViewModel, maxDurationSec = 11)
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object SoundGenTasksModule {
  @Provides @IntoSet fun provideSoundGenTask(): CustomTask = SoundGenTask()

  @Provides @IntoSet
  fun provideSoundGenHdTask(): CustomTask = makeSoundGenHdTask(
    id = BuiltInTaskId.SOUNDGEN_HD,
    label = "SoundGen HD",
    shortDesc = "Higher-quality text-to-audio (~24s)",
    longDesc = "Higher-quality on-device text-to-audio — generate offline (up to ~24s). Downloads ~2.1GB on first use.",
    modelName = "SoundGen HD",
    ditFile = "dit_L256_int8.tflite",
    ditBytes = 1_468_553_968L,
    decFile = "ae_dec_L256_int8.tflite",
    decBytes = 434_121_120L,
    latentUnits = 256,
  )

  @Provides @IntoSet
  fun provideSoundGenHdLongTask(): CustomTask = makeSoundGenHdTask(
    id = BuiltInTaskId.SOUNDGEN_HD_LONG,
    label = "SoundGen HD Long",
    shortDesc = "Long-form text-to-audio (~3 min)",
    longDesc = "Same high quality, much longer clips — up to ~3 minutes, fully offline. Downloads ~2.1GB on first use.",
    modelName = "SoundGen HD Long",
    ditFile = "dit_L2048_int8.tflite",
    ditBytes = 1_469_012_720L,
    decFile = "ae_dec_L2048_int8.tflite",
    decBytes = 447_063_056L,
    latentUnits = 2048,
  )
}

@Composable
fun SoundGenScreen(
  modelManagerViewModel: ModelManagerViewModel,
  maxDurationSec: Int,
  viewModel: SoundGenViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val mmState by modelManagerViewModel.uiState.collectAsState()
  val model = mmState.selectedModel
  val ready = mmState.modelInitializationStatus[model.name]?.status == ModelInitializationStatusType.INITIALIZED
  val context = LocalContext.current

  var isPlaying by remember { mutableStateOf(false) }
  val playerHolder = remember { mutableStateOf<MediaPlayer?>(null) }
  DisposableEffect(Unit) {
    onDispose {
      runCatching { playerHolder.value?.release() }
      playerHolder.value = null
    }
  }

  if (!ready) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(
        "Download and initialize ${model.name} to generate audio on-device.",
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    return
  }

  val clampedDuration = uiState.durationSec.coerceIn(1, maxDurationSec)
  if (clampedDuration != uiState.durationSec) {
    viewModel.onDurationChange(clampedDuration)
  }

  Column(
    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    OutlinedTextField(
      value = uiState.prompt,
      onValueChange = viewModel::onPromptChange,
      label = { Text("Describe sound or music") },
      placeholder = { Text("e.g. Warm acoustic guitar melody with soft rain in the background") },
      modifier = Modifier.fillMaxWidth(),
      minLines = 3,
    )

    Text("Length: ${clampedDuration}s", style = MaterialTheme.typography.labelLarge)
    Slider(
      value = clampedDuration.toFloat(),
      onValueChange = { viewModel.onDurationChange(it.roundToInt()) },
      valueRange = 1f..maxDurationSec.toFloat(),
    )

    if (uiState.isGenerating) {
      LinearProgressIndicator(progress = { uiState.progress }, modifier = Modifier.fillMaxWidth())
      Text("Generating audio… ${(uiState.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
    } else {
      Button(
        onClick = { viewModel.generate(context, model) },
        enabled = uiState.prompt.isNotBlank(),
      ) {
        Text("Generate audio")
      }
    }

    uiState.errorMessage?.let {
      Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }

    uiState.wavPath?.let { path ->
      val file = File(path)
      if (file.exists()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Button(onClick = {
            if (isPlaying) {
              runCatching { playerHolder.value?.stop(); playerHolder.value?.release() }
              playerHolder.value = null
              isPlaying = false
            } else {
              runCatching { playerHolder.value?.release() }
              val mp = MediaPlayer().apply {
                setDataSource(path)
                setOnCompletionListener {
                  isPlaying = false
                  runCatching { release() }
                  playerHolder.value = null
                }
                prepare()
                start()
              }
              playerHolder.value = mp
              isPlaying = true
            }
          }) {
            Icon(if (isPlaying) Icons.Outlined.Stop else Icons.Outlined.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (isPlaying) "Stop" else "Play")
          }
          OutlinedButton(onClick = { WavUtils.shareAudioFile(context, file) }) {
            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Share")
          }
          OutlinedButton(onClick = {
            WavUtils.copyToMusicExports(context, file, "soundgen_${System.currentTimeMillis()}.wav")
          }) {
            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
          }
        }
      }
    }
    Spacer(Modifier.height(16.dp))
  }
}
