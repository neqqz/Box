package com.google.ai.edge.gallery.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Utilities for encoding WAV PCM audio, playing float PCM via [AudioTrack], and sharing audio files. */
object WavUtils {

  fun encodeMonoFloatToWavBytes(samples: FloatArray, sampleRate: Int): ByteArray {
    val pcmSize = samples.size * 2
    val out = ByteArrayOutputStream(44 + pcmSize)
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    writeWavHeader(header, pcmDataBytes = pcmSize, channels = 1, sampleRate = sampleRate, bitsPerSample = 16)
    out.write(header.array())
    val body = ByteBuffer.allocate(pcmSize).order(ByteOrder.LITTLE_ENDIAN)
    for (s in samples) {
      val clamped = s.coerceIn(-1f, 1f)
      body.putShort((clamped * 32767f).roundToInt().toShort())
    }
    out.write(body.array())
    return out.toByteArray()
  }

  fun writeMonoFloatWav(file: File, samples: FloatArray, sampleRate: Int) {
    file.parentFile?.mkdirs()
    val bytes = encodeMonoFloatToWavBytes(samples, sampleRate)
    FileOutputStream(file).use { it.write(bytes) }
  }

  fun writeStereoPlanarFloatWav(
    file: File,
    planar: FloatArray,
    totalFramesInPlanar: Int,
    writeFrames: Int,
    sampleRate: Int,
    normalize: Boolean = false,
  ) {
    file.parentFile?.mkdirs()
    val frames = writeFrames.coerceIn(1, totalFramesInPlanar)
    val scale = if (normalize) {
      var peak = 0f
      for (v in planar) {
        val av = kotlin.math.abs(v)
        if (av > peak) peak = av
      }
      if (peak > 1e-6f) 1f / peak else 1f
    } else {
      1f
    }
    val dataBytes = frames * 4
    RandomAccessFile(file, "rw").use { raf ->
      raf.setLength(0L)
      val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
      writeWavHeader(header, pcmDataBytes = dataBytes, channels = 2, sampleRate = sampleRate, bitsPerSample = 16)
      raf.write(header.array())
      val pcm = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
      for (i in 0 until frames) {
        val l = (planar[i] * scale).coerceIn(-1f, 1f)
        val r = (planar[totalFramesInPlanar + i] * scale).coerceIn(-1f, 1f)
        pcm.putShort((l * 32767f).roundToInt().toShort())
        pcm.putShort((r * 32767f).roundToInt().toShort())
      }
      raf.write(pcm.array())
    }
  }

  private fun writeWavHeader(
    buf: ByteBuffer,
    pcmDataBytes: Int,
    channels: Int,
    sampleRate: Int,
    bitsPerSample: Int,
  ) {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = (channels * bitsPerSample / 8).toShort()
    buf.put("RIFF".toByteArray(Charsets.US_ASCII))
    buf.putInt(36 + pcmDataBytes)
    buf.put("WAVE".toByteArray(Charsets.US_ASCII))
    buf.put("fmt ".toByteArray(Charsets.US_ASCII))
    buf.putInt(16)
    buf.putShort(1.toShort()) // PCM
    buf.putShort(channels.toShort())
    buf.putInt(sampleRate)
    buf.putInt(byteRate)
    buf.putShort(blockAlign)
    buf.putShort(bitsPerSample.toShort())
    buf.put("data".toByteArray(Charsets.US_ASCII))
    buf.putInt(pcmDataBytes)
  }

  suspend fun playMonoFloat(samples: FloatArray, sampleRate: Int) = withContext(Dispatchers.IO) {
    if (samples.isEmpty()) return@withContext
    val minBuf = AudioTrack.getMinBufferSize(
      sampleRate,
      AudioFormat.CHANNEL_OUT_MONO,
      AudioFormat.ENCODING_PCM_FLOAT,
    ).coerceAtLeast(samples.size * 4)

    val track = AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .build()
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
          .setSampleRate(sampleRate)
          .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
          .build()
      )
      .setBufferSizeInBytes(minBuf)
      .setTransferMode(AudioTrack.MODE_STATIC)
      .build()

    try {
      track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
      track.play()
      while (currentCoroutineContext().isActive && track.playbackHeadPosition < samples.size) {
        delay(30L)
      }
      runCatching { track.stop() }
    } finally {
      runCatching { track.release() }
    }
  }

  fun shareAudioFile(context: Context, file: File, mimeType: String = "audio/wav") {
    val authority = "${context.packageName}.provider"
    val uri = FileProvider.getUriForFile(context, authority, file)
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = mimeType
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
      Intent.createChooser(intent, "Share audio").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
  }

  fun copyToMusicExports(context: Context, srcFile: File, targetFileName: String): File? {
    return runCatching {
      val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir
      dir.mkdirs()
      val dst = File(dir, targetFileName)
      srcFile.copyTo(dst, overwrite = true)
      dst
    }.getOrNull()
  }
}
