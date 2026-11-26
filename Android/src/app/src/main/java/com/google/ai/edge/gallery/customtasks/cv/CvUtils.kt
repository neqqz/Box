package com.google.ai.edge.gallery.customtasks.cv

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "CvUtils"

object CvUtils {

  fun loadCompiledModel(
    path: String,
    gpuOnly: Boolean = false,
    preferGpu: Boolean = true,
  ): CompiledModel {
    if (gpuOnly) {
      return CompiledModel.create(path, CompiledModel.Options(Accelerator.GPU))
    }
    if (preferGpu) {
      try {
        return CompiledModel.create(path, CompiledModel.Options(Accelerator.GPU, Accelerator.CPU))
      } catch (t: Throwable) {
        Log.w(TAG, "GPU compile failed for $path; falling back to CPU", t)
      }
    }
    return CompiledModel.create(path, CompiledModel.Options(Accelerator.CPU))
  }

  fun decodeScaledBitmap(context: Context, uri: Uri, maxDim: Int = 2048): Bitmap? {
    return runCatching {
      context.contentResolver.openInputStream(uri)?.use { stream ->
        val raw = BitmapFactory.decodeStream(stream) ?: return null
        val curMax = maxOf(raw.width, raw.height)
        if (curMax <= maxDim) raw
        else {
          val scale = maxDim.toFloat() / curMax.toFloat()
          Bitmap.createScaledBitmap(
            raw,
            (raw.width * scale).toInt().coerceAtLeast(1),
            (raw.height * scale).toInt().coerceAtLeast(1),
            true,
          )
        }
      }
    }.getOrNull()
  }

  fun closeBuffers(buffers: List<TensorBuffer>) {
    for (buf in buffers) {
      runCatching { buf.close() }
    }
  }

  suspend fun saveBitmapToPictures(
    context: Context,
    bitmap: Bitmap,
    subdir: String,
    prefix: String,
  ): Boolean = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, "${prefix}_${System.currentTimeMillis()}.png")
      put(MediaStore.Images.Media.MIME_TYPE, "image/png")
      put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$subdir")
      put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
    try {
      val ok = resolver.openOutputStream(uri)?.use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
      } ?: false
      if (!ok) throw IOException("PNG encode failed")
      values.clear()
      values.put(MediaStore.Images.Media.IS_PENDING, 0)
      resolver.update(uri, values, null, null)
      true
    } catch (t: Throwable) {
      Log.e(TAG, "saveBitmapToPictures failed", t)
      resolver.delete(uri, null, null)
      false
    }
  }

  suspend fun shareBitmap(context: Context, bitmap: Bitmap, prefix: String) {
    val file = withContext(Dispatchers.IO) {
      val dir = File(context.cacheDir, "shared_images").apply { mkdirs() }
      val out = File(dir, "${prefix}_${System.currentTimeMillis()}.png")
      FileOutputStream(out).use { fos -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos) }
      out
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "image/png"
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }
}
