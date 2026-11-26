package com.google.ai.edge.gallery.customtasks.facerec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.facemesh.FaceMesh
import com.google.mlkit.vision.facemesh.FaceMeshDetection
import com.google.mlkit.vision.facemesh.FaceMeshDetector
import com.google.mlkit.vision.facemesh.FaceMeshDetectorOptions
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

private const val TAG = "FaceEmbedEngine"
private const val INPUT_SIZE = 112
private const val EMBEDDING_DIM = 192

data class DetectedAlignedFace(
  val crop: Bitmap,
  val embedding: FloatArray,
  val boundingBox: Rect,
)

class FaceEmbedEngine {

  companion object {
    val engineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    // Canonical 112x112 reference targets for left eye, right eye, and mouth midpoint.
    private val CANONICAL_DST_POINTS =
      floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.14f, 92.28f)
  }

  private var compiledModel: CompiledModel? = null

  private val faceDetector: FaceDetector by lazy {
    FaceDetection.getClient(
      FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setMinFaceSize(0.1f)
        .build()
    )
  }

  private val faceMeshDetector: FaceMeshDetector by lazy {
    FaceMeshDetection.getClient(
      FaceMeshDetectorOptions.Builder()
        .setUseCase(FaceMeshDetectorOptions.FACE_MESH)
        .build()
    )
  }

  suspend fun loadModel(modelPath: String): Boolean =
    withContext(engineDispatcher) {
      try {
        compiledModel?.close()
      } catch (_: Throwable) {
      }
      compiledModel = null

      val targetFile =
        File(modelPath).let { f ->
          if (f.isDirectory) {
            f.walkTopDown().firstOrNull { it.isFile && it.extension.equals("tflite", ignoreCase = true) }
          } else if (f.isFile) {
            f
          } else {
            null
          }
        }

      if (targetFile == null || !targetFile.exists()) {
        Log.e(TAG, "No .tflite found at $modelPath")
        return@withContext false
      }

      return@withContext try {
        compiledModel = CompiledModel.create(targetFile.absolutePath, CompiledModel.Options(Accelerator.CPU))
        true
      } catch (t: Throwable) {
        Log.e(TAG, "Failed to load MobileFaceNet model", t)
        false
      }
    }

  suspend fun close() {
    withContext(engineDispatcher) {
      try {
        compiledModel?.close()
      } catch (_: Throwable) {
      }
      compiledModel = null
    }
  }

  suspend fun detectAndEmbed(bitmap: Bitmap): List<DetectedAlignedFace> {
    val faces = detectFaces(bitmap)
    if (faces.isEmpty()) return emptyList()

    val results = ArrayList<DetectedAlignedFace>(faces.size)
    for (face in faces) {
      val aligned = alignAndCropFace(bitmap, face)
      val emb = computeEmbedding(aligned)
      if (emb != null) {
        results.add(DetectedAlignedFace(crop = aligned, embedding = emb, boundingBox = face.boundingBox))
      } else {
        aligned.recycle()
      }
    }
    return results
  }

  suspend fun detectFaceMeshes(bitmap: Bitmap): List<FaceMesh> {
    val image = InputImage.fromBitmap(bitmap, 0)
    return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
      faceMeshDetector
        .process(image)
        .addOnSuccessListener { meshes -> if (cont.isActive) cont.resume(meshes) }
        .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    }
  }

  private suspend fun detectFaces(bitmap: Bitmap): List<Face> {
    val image = InputImage.fromBitmap(bitmap, 0)
    return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
      faceDetector
        .process(image)
        .addOnSuccessListener { faces -> if (cont.isActive) cont.resume(faces) }
        .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    }
  }

  private suspend fun computeEmbedding(alignedBitmap: Bitmap): FloatArray? =
    withContext(engineDispatcher) {
      val model = compiledModel ?: return@withContext null
      try {
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        alignedBitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        val inputFloats = FloatArray(INPUT_SIZE * INPUT_SIZE * 3)
        var outIdx = 0
        for (px in pixels) {
          val r = ((px shr 16) and 0xFF)
          val g = ((px shr 8) and 0xFF)
          val b = (px and 0xFF)
          inputFloats[outIdx++] = (r - 127.5f) / 128.0f
          inputFloats[outIdx++] = (g - 127.5f) / 128.0f
          inputFloats[outIdx++] = (b - 127.5f) / 128.0f
        }

        val inputBuffers = model.createInputBuffers()
        val outputBuffers = model.createOutputBuffers()
        try {
          inputBuffers[0].writeFloat(inputFloats)
          model.run(inputBuffers, outputBuffers)
          val raw = outputBuffers[0].readFloat()
          if (raw.isEmpty()) return@withContext null
          return@withContext l2Normalize(raw)
        } finally {
          inputBuffers.forEach { runCatching { it.close() } }
          outputBuffers.forEach { runCatching { it.close() } }
        }
      } catch (t: Throwable) {
        Log.e(TAG, "Embedding inference failed", t)
        null
      }
    }

  private fun alignAndCropFace(source: Bitmap, face: Face): Bitmap {
    val out = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
    val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
    val mouthLeft = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
    val mouthRight = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position

    if (leftEye != null && rightEye != null && mouthLeft != null && mouthRight != null) {
      // Note: ML Kit LEFT_EYE is subject's left (image right), RIGHT_EYE is subject's right (image left).
      // Swap by x-coordinate so src[0] is image-left eye and src[1] is image-right eye matching CANONICAL_DST_POINTS.
      val (imgLeftEye, imgRightEye) =
        if (leftEye.x <= rightEye.x) leftEye to rightEye else rightEye to leftEye
      val mouthMidX = (mouthLeft.x + mouthRight.x) * 0.5f
      val mouthMidY = (mouthLeft.y + mouthRight.y) * 0.5f
      val src =
        floatArrayOf(
          imgLeftEye.x,
          imgLeftEye.y,
          imgRightEye.x,
          imgRightEye.y,
          mouthMidX,
          mouthMidY,
        )
      val matrix = Matrix()
      if (matrix.setPolyToPoly(src, 0, CANONICAL_DST_POINTS, 0, 3)) {
        canvas.drawBitmap(source, matrix, paint)
        return out
      }
    }

    // Fallback: expanded bounding box (1.15x) + eye-angle rotation.
    val box = face.boundingBox
    val cx = box.exactCenterX()
    val cy = box.exactCenterY()
    val size = max(box.width(), box.height()) * 1.15f
    val matrix = Matrix()
    matrix.postTranslate(-cx, -cy)
    if (leftEye != null && rightEye != null) {
      val (imgLeftEye, imgRightEye) =
        if (leftEye.x <= rightEye.x) leftEye to rightEye else rightEye to leftEye
      val angleDeg =
        Math.toDegrees(atan2((imgRightEye.y - imgLeftEye.y).toDouble(), (imgRightEye.x - imgLeftEye.x).toDouble())).toFloat()
      matrix.postRotate(-angleDeg)
    }
    val scale = if (size > 1f) INPUT_SIZE / size else 1f
    matrix.postScale(scale, scale)
    matrix.postTranslate(INPUT_SIZE * 0.5f, INPUT_SIZE * 0.5f)
    canvas.drawBitmap(source, matrix, paint)
    return out
  }

  private fun l2Normalize(vec: FloatArray): FloatArray {
    var sumSq = 0.0f
    for (v in vec) sumSq += v * v
    val norm = sqrt(sumSq)
    if (norm <= 1e-12f) return vec
    val inv = 1.0f / norm
    return FloatArray(vec.size) { i -> vec[i] * inv }
  }

  fun decodeOrientedBitmap(context: Context, uri: Uri, maxDim: Int = 1600): Bitmap? {
    val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
    if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

    var sampleSize = 1
    while (
      boundsOpts.outWidth / sampleSize > maxDim * 2 ||
        boundsOpts.outHeight / sampleSize > maxDim * 2
    ) {
      sampleSize *= 2
    }

    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val rawBitmap =
      context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
        ?: return null

    val orientation =
      try {
        context.contentResolver.openInputStream(uri)?.use {
          ExifInterface(it).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
          )
        } ?: ExifInterface.ORIENTATION_NORMAL
      } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
      }

    val matrix = Matrix()
    when (orientation) {
      ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
      ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
      ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
    }

    val oriented =
      if (!matrix.isIdentity) {
        Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
          .also { if (it !== rawBitmap) rawBitmap.recycle() }
      } else {
        rawBitmap
      }

    val largest = max(oriented.width, oriented.height)
    if (largest <= maxDim) return oriented
    val scale = maxDim.toFloat() / largest
    val scaledW = max(1, (oriented.width * scale).toInt())
    val scaledH = max(1, (oriented.height * scale).toInt())
    return Bitmap.createScaledBitmap(oriented, scaledW, scaledH, true).also {
      if (it !== oriented) oriented.recycle()
    }
  }
}
