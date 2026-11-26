package com.google.ai.edge.gallery.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.google.ai.edge.gallery.whisper.WhisperEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

private const val TAG = "AudioDecoder"
private const val TIMEOUT_US = 10_000L

/**
 * Decodes any Android-supported audio/video [Uri] into mono 16 kHz float32 PCM samples
 * suitable for Whisper and Sherpa-ONNX speech recognizers.
 */
object AudioDecoder {

  suspend fun decodeTo16kMonoFloat(context: Context, uri: Uri): FloatArray =
    withContext(Dispatchers.IO) {
      val extractor = MediaExtractor()
      try {
        extractor.setDataSource(context, uri, null)
        var trackIndex = -1
        var mime = ""
        var sampleRate = WhisperEngine.SAMPLE_RATE
        var channels = 1

        for (i in 0 until extractor.trackCount) {
          val format = extractor.getTrackFormat(i)
          val m = format.getString(MediaFormat.KEY_MIME)
          if (m != null && m.startsWith("audio/")) {
            trackIndex = i
            mime = m
            sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
              format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
              WhisperEngine.SAMPLE_RATE
            }
            channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
              format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else {
              1
            }
            break
          }
        }

        if (trackIndex < 0) {
          Log.w(TAG, "No audio track found in $uri")
          return@withContext FloatArray(0)
        }

        extractor.selectTrack(trackIndex)
        val inputFormat = extractor.getTrackFormat(trackIndex)
        val codec = MediaCodec.createDecoderByType(mime)
        val monoSamples = ArrayList<Float>(16000 * 30)

        try {
          codec.configure(inputFormat, null, null, 0)
          codec.start()
          val bufferInfo = MediaCodec.BufferInfo()
          var inputDone = false
          var outputDone = false
          var actualSampleRate = sampleRate
          var actualChannels = channels

          while (!outputDone) {
            if (!inputDone) {
              val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
              if (inIdx >= 0) {
                val inputBuffer = codec.getInputBuffer(inIdx)
                if (inputBuffer != null) {
                  val sampleSize = extractor.readSampleData(inputBuffer, 0)
                  if (sampleSize < 0) {
                    codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                  } else {
                    codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                    extractor.advance()
                  }
                } else {
                  codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                  inputDone = true
                }
              }
            }

            val outIdx = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
              outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val outFmt = codec.outputFormat
                if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                  actualSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                  actualChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                }
              }
              outIdx >= 0 -> {
                val outBuf = codec.getOutputBuffer(outIdx)
                if (outBuf != null && bufferInfo.size > 0) {
                  val outFmt = codec.getOutputFormat(outIdx)
                  val encoding = if (outFmt.containsKey("pcm-encoding")) {
                    outFmt.getInteger("pcm-encoding")
                  } else {
                    2 // ENCODING_PCM_16BIT
                  }
                  outBuf.position(bufferInfo.offset)
                  outBuf.limit(bufferInfo.offset + bufferInfo.size)
                  outBuf.order(ByteOrder.LITTLE_ENDIAN)

                  if (encoding == 4) { // ENCODING_PCM_FLOAT
                    val floatBuf = outBuf.asFloatBuffer()
                    val arr = FloatArray(floatBuf.remaining())
                    floatBuf.get(arr)
                    val frames = arr.size / actualChannels
                    for (f in 0 until frames) {
                      var sum = 0f
                      for (c in 0 until actualChannels) {
                        sum += arr[(f * actualChannels + c).coerceAtMost(arr.lastIndex)]
                      }
                      monoSamples.add(sum / actualChannels)
                    }
                  } else {
                    val shortBuf = outBuf.asShortBuffer()
                    val arr = ShortArray(shortBuf.remaining())
                    shortBuf.get(arr)
                    val frames = arr.size / actualChannels
                    for (f in 0 until frames) {
                      var sum = 0f
                      for (c in 0 until actualChannels) {
                        sum += arr[(f * actualChannels + c).coerceAtMost(arr.lastIndex)]
                      }
                      monoSamples.add((sum / actualChannels) / 32768f)
                    }
                  }
                }
                codec.releaseOutputBuffer(outIdx, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                  outputDone = true
                }
              }
            }
          }

          val raw = FloatArray(monoSamples.size) { monoSamples[it] }
          return@withContext if (actualSampleRate == WhisperEngine.SAMPLE_RATE || raw.isEmpty()) {
            raw
          } else {
            resampleLinear(raw, actualSampleRate, WhisperEngine.SAMPLE_RATE)
          }
        } finally {
          runCatching { codec.stop() }
          runCatching { codec.release() }
        }
      } finally {
        runCatching { extractor.release() }
      }
    }

  private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
    if (srcRate <= 0 || srcRate == dstRate || input.isEmpty()) return input
    val ratio = srcRate.toDouble() / dstRate.toDouble()
    val outLen = (input.size / ratio).toInt().coerceAtLeast(1)
    return FloatArray(outLen) { i ->
      val srcPos = i * ratio
      val idx0 = srcPos.toInt().coerceIn(0, input.lastIndex)
      val idx1 = (idx0 + 1).coerceIn(0, input.lastIndex)
      val frac = (srcPos - idx0).toFloat()
      input[idx0] * (1f - frac) + input[idx1] * frac
    }
  }
}
