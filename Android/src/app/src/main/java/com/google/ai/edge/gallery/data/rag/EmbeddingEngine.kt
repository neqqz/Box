package com.google.ai.edge.gallery.data.rag

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import org.tensorflow.lite.Interpreter

private const val TAG = "EmbeddingEngine"
private const val MODEL_ASSET_PATH = "embedding/all-MiniLM-L6-v2.tflite"
private const val VOCAB_ASSET_PATH = "embedding/vocab.txt"
private const val MAX_SEQ_LEN = 128
private const val EMBEDDING_DIM = 384

private const val TOKEN_PAD = 0
private const val TOKEN_UNK = 100
private const val TOKEN_CLS = 101
private const val TOKEN_SEP = 102

/**
 * Local WordPiece tokenizer + all-MiniLM-L6-v2 TFLite sentence embedding engine.
 * Produces L2-normalized 384-dimensional float vectors offline on device.
 */
@Singleton
class EmbeddingEngine @Inject constructor(@ApplicationContext private val context: Context) {

  private val interpreterLock = Any()

  private val vocab: Map<String, Int> by lazy {
    context.assets.open(VOCAB_ASSET_PATH).bufferedReader(Charsets.UTF_8).use { reader ->
      val map = HashMap<String, Int>(32768)
      var idx = 0
      reader.forEachLine { line ->
        map[line] = idx
        idx++
      }
      map
    }
  }

  private val interpreter: Interpreter by lazy {
    val modelBytes = context.assets.open(MODEL_ASSET_PATH).use { it.readBytes() }
    val buffer =
      ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder()).apply {
        put(modelBytes)
        rewind()
      }
    val options = Interpreter.Options().apply { numThreads = 4 }
    Interpreter(buffer, options).also { interp ->
      // Model graph inputs: input[0] = serving_default_inputs_1:0 (input_ids),
      // input[1] = serving_default_inputs:0 (attention_mask), both shape [1, seqLen].
      interp.resizeInput(0, intArrayOf(1, MAX_SEQ_LEN))
      interp.resizeInput(1, intArrayOf(1, MAX_SEQ_LEN))
      interp.allocateTensors()
    }
  }

  fun embed(text: String): FloatArray {
    val (inputIds, attentionMask) = tokenize(text)
    val inputs = arrayOf<Any>(arrayOf(inputIds), arrayOf(attentionMask))
    val outputBuffer = Array(1) { FloatArray(EMBEDDING_DIM) }
    val outputs = mapOf<Int, Any>(0 to outputBuffer)

    synchronized(interpreterLock) { interpreter.runForMultipleInputsOutputs(inputs, outputs) }

    return l2Normalize(outputBuffer[0])
  }

  private fun tokenize(text: String): Pair<IntArray, IntArray> {
    val wordPieces = ArrayList<Int>(MAX_SEQ_LEN)
    wordPieces.add(TOKEN_CLS)

    val normalized = text.lowercase()
    val basicTokens = basicTokenize(normalized)
    outer@ for (token in basicTokens) {
      if (wordPieces.size >= MAX_SEQ_LEN - 1) break
      val pieces = wordPieceTokenize(token)
      for (pieceId in pieces) {
        if (wordPieces.size >= MAX_SEQ_LEN - 1) break@outer
        wordPieces.add(pieceId)
      }
    }
    wordPieces.add(TOKEN_SEP)

    val inputIds = IntArray(MAX_SEQ_LEN) { TOKEN_PAD }
    val attentionMask = IntArray(MAX_SEQ_LEN) { 0 }
    val count = minOf(wordPieces.size, MAX_SEQ_LEN)
    for (i in 0 until count) {
      inputIds[i] = wordPieces[i]
      attentionMask[i] = 1
    }
    return inputIds to attentionMask
  }

  private fun basicTokenize(text: String): List<String> {
    val tokens = ArrayList<String>()
    val current = StringBuilder()
    for (ch in text) {
      when {
        ch.isWhitespace() -> {
          if (current.isNotEmpty()) {
            tokens.add(current.toString())
            current.clear()
          }
        }
        isPunctuation(ch) -> {
          if (current.isNotEmpty()) {
            tokens.add(current.toString())
            current.clear()
          }
          tokens.add(ch.toString())
        }
        else -> current.append(ch)
      }
    }
    if (current.isNotEmpty()) {
      tokens.add(current.toString())
    }
    return tokens
  }

  private fun wordPieceTokenize(word: String): List<Int> {
    if (word.length > 100) return listOf(TOKEN_UNK)
    vocab[word]?.let {
      return listOf(it)
    }

    val outputTokens = ArrayList<Int>()
    var start = 0
    while (start < word.length) {
      var end = word.length
      var curSubStrId: Int? = null
      while (start < end) {
        val sub = if (start == 0) word.substring(start, end) else "##" + word.substring(start, end)
        val id = vocab[sub]
        if (id != null) {
          curSubStrId = id
          break
        }
        end--
      }
      if (curSubStrId == null) {
        return listOf(TOKEN_UNK)
      }
      outputTokens.add(curSubStrId)
      start = end
    }
    return outputTokens
  }

  private fun isPunctuation(ch: Char): Boolean {
    val cp = ch.code
    if ((cp in 33..47) || (cp in 58..64) || (cp in 91..96) || (cp in 123..126)) {
      return true
    }
    val type = Character.getType(ch)
    return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
      type == Character.DASH_PUNCTUATION.toInt() ||
      type == Character.START_PUNCTUATION.toInt() ||
      type == Character.END_PUNCTUATION.toInt() ||
      type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
      type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
      type == Character.OTHER_PUNCTUATION.toInt()
  }

  private fun l2Normalize(vec: FloatArray): FloatArray {
    var sumSq = 0.0f
    for (v in vec) sumSq += v * v
    val norm = sqrt(sumSq)
    if (norm <= 1e-12f) return vec
    val out = FloatArray(vec.size)
    val inv = 1.0f / norm
    for (i in vec.indices) out[i] = vec[i] * inv
    return out
  }

  fun close() {
    synchronized(interpreterLock) {
      try {
        interpreter.close()
      } catch (e: Exception) {
        Log.w(TAG, "Error closing embedding interpreter", e)
      }
    }
  }

  companion object {
    fun floatArrayToBytes(vec: FloatArray): ByteArray {
      val buffer = ByteBuffer.allocate(vec.size * 4).order(ByteOrder.LITTLE_ENDIAN)
      for (v in vec) buffer.putFloat(v)
      return buffer.array()
    }

    fun bytesToFloatArray(bytes: ByteArray): FloatArray {
      val count = bytes.size / 4
      val out = FloatArray(count)
      val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      for (i in 0 until count) out[i] = buffer.getFloat()
      return out
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
      val n = minOf(a.size, b.size)
      if (n == 0) return 0f
      var dot = 0.0f
      var normA = 0.0f
      var normB = 0.0f
      for (i in 0 until n) {
        dot += a[i] * b[i]
        normA += a[i] * a[i]
        normB += b[i] * b[i]
      }
      val denom = sqrt(normA) * sqrt(normB)
      return if (denom > 1e-12f) dot / denom else 0f
    }
  }
}
