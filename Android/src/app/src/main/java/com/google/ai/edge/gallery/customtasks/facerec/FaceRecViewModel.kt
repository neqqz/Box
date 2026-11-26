package com.google.ai.edge.gallery.customtasks.facerec

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.local.dao.FaceDao
import com.google.ai.edge.gallery.data.local.entities.FaceRecord
import com.google.mlkit.vision.facemesh.FaceMesh
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "FaceRecViewModel"
private const val PREFS_NAME = "facerec_prefs"
private const val KEY_THRESHOLD = "threshold"
private const val KEY_FRONT_CAMERA = "frontCamera"
private const val KEY_SHOW_PERCENT = "showPercent"
private const val KEY_SHOW_MESH = "showMesh"
private const val DEFAULT_THRESHOLD = 0.55f

enum class FaceRecTab {
  RECOGNIZE,
  LIVE,
  ENROLLED,
  ADD,
}

data class RecognizedFace(
  val crop: Bitmap,
  val name: String?,
  val similarity: Float,
)

data class PendingFace(
  val crop: Bitmap,
  val embedding: FloatArray,
  val suggestedName: String?,
  val similarity: Float,
)

data class EnrolledFaceUi(
  val id: Long,
  val name: String,
  val thumbnail: Bitmap?,
  val createdAt: Long,
)

data class FaceRecUiState(
  val tab: FaceRecTab = FaceRecTab.RECOGNIZE,
  val isProcessing: Boolean = false,
  val error: String? = null,
  val statusMessage: String? = null,
  val recognizeResults: List<RecognizedFace> = emptyList(),
  val pendingFaces: List<PendingFace> = emptyList(),
  val enrolled: List<EnrolledFaceUi> = emptyList(),
  val threshold: Float = DEFAULT_THRESHOLD,
  val frontCamera: Boolean = true,
  val showPercent: Boolean = true,
  val showMesh: Boolean = false,
)

@HiltViewModel
class FaceRecViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val faceDao: FaceDao,
) : ViewModel() {

  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val _uiState =
    MutableStateFlow(
      FaceRecUiState(
        threshold = prefs.getFloat(KEY_THRESHOLD, DEFAULT_THRESHOLD),
        frontCamera = prefs.getBoolean(KEY_FRONT_CAMERA, true),
        showPercent = prefs.getBoolean(KEY_SHOW_PERCENT, true),
        showMesh = prefs.getBoolean(KEY_SHOW_MESH, false),
      )
    )
  val uiState: StateFlow<FaceRecUiState> = _uiState.asStateFlow()

  private var cachedRecords: List<Pair<String, FloatArray>> = emptyList()

  init {
    refreshEnrolledFaces()
  }

  fun selectTab(tab: FaceRecTab) {
    _uiState.update { it.copy(tab = tab, error = null, statusMessage = null) }
  }

  fun setThreshold(value: Float) {
    val clamped = value.coerceIn(0.30f, 0.85f)
    prefs.edit().putFloat(KEY_THRESHOLD, clamped).apply()
    _uiState.update { it.copy(threshold = clamped) }
  }

  fun toggleFrontCamera() {
    val next = !_uiState.value.frontCamera
    prefs.edit().putBoolean(KEY_FRONT_CAMERA, next).apply()
    _uiState.update { it.copy(frontCamera = next) }
  }

  fun setShowPercent(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_SHOW_PERCENT, enabled).apply()
    _uiState.update { it.copy(showPercent = enabled) }
  }

  fun setShowMesh(enabled: Boolean) {
    prefs.edit().putBoolean(KEY_SHOW_MESH, enabled).apply()
    _uiState.update { it.copy(showMesh = enabled) }
  }

  fun clearMessages() {
    _uiState.update { it.copy(error = null, statusMessage = null) }
  }

  fun refreshEnrolledFaces() {
    viewModelScope.launch(Dispatchers.IO) {
      val records = faceDao.getAll()
      cachedRecords = records.map { rec -> rec.name to bytesToFloatArray(rec.embedding) }
      val uiList =
        records.map { rec ->
          val thumb =
            if (rec.thumbnail.isNotEmpty()) {
              BitmapFactory.decodeByteArray(rec.thumbnail, 0, rec.thumbnail.size)
            } else {
              null
            }
          EnrolledFaceUi(
            id = rec.id,
            name = rec.name,
            thumbnail = thumb,
            createdAt = rec.createdAt,
          )
        }
      _uiState.update { it.copy(enrolled = uiList) }
    }
  }

  fun recognizeFromUri(uri: Uri, engine: FaceEmbedEngine) {
    viewModelScope.launch(Dispatchers.IO) {
      _uiState.update {
        it.copy(isProcessing = true, error = null, statusMessage = null, recognizeResults = emptyList())
      }
      try {
        val bmp = engine.decodeOrientedBitmap(context, uri)
        if (bmp == null) {
          _uiState.update { it.copy(isProcessing = false, error = "Could not open image.") }
          return@launch
        }
        try {
          val faces = engine.detectAndEmbed(bmp)
          if (faces.isEmpty()) {
            _uiState.update {
              it.copy(isProcessing = false, statusMessage = "No faces detected in photo.")
            }
            return@launch
          }
          val results =
            faces.map { detected ->
              val (matchName, score) = findBestMatch(detected.embedding)
              RecognizedFace(crop = detected.crop, name = matchName, similarity = score)
            }
          _uiState.update { it.copy(isProcessing = false, recognizeResults = results) }
        } finally {
          bmp.recycle()
        }
      } catch (e: Exception) {
        Log.e(TAG, "recognizeFromUri failed", e)
        _uiState.update {
          it.copy(isProcessing = false, error = "Face recognition failed: ${e.message}")
        }
      }
    }
  }

  fun detectForEnrolment(uri: Uri, engine: FaceEmbedEngine) {
    viewModelScope.launch(Dispatchers.IO) {
      _uiState.update {
        it.copy(isProcessing = true, error = null, statusMessage = null, pendingFaces = emptyList())
      }
      try {
        val bmp = engine.decodeOrientedBitmap(context, uri)
        if (bmp == null) {
          _uiState.update { it.copy(isProcessing = false, error = "Could not open image.") }
          return@launch
        }
        try {
          val faces = engine.detectAndEmbed(bmp)
          if (faces.isEmpty()) {
            _uiState.update {
              it.copy(isProcessing = false, statusMessage = "No faces found in that photo.")
            }
            return@launch
          }
          val pending =
            faces.map { detected ->
              val (matchName, score) = findBestMatch(detected.embedding)
              PendingFace(
                crop = detected.crop,
                embedding = detected.embedding,
                suggestedName = matchName,
                similarity = score,
              )
            }
          _uiState.update { it.copy(isProcessing = false, pendingFaces = pending) }
        } finally {
          bmp.recycle()
        }
      } catch (e: Exception) {
        Log.e(TAG, "detectForEnrolment failed", e)
        _uiState.update { it.copy(isProcessing = false, error = "Detection failed: ${e.message}") }
      }
    }
  }

  fun recognizeLiveBitmap(bitmap: Bitmap, engine: FaceEmbedEngine) {
    viewModelScope.launch(Dispatchers.IO) {
      if (_uiState.value.isProcessing) {
        bitmap.recycle()
        return@launch
      }
      _uiState.update { it.copy(isProcessing = true) }
      try {
        val faces = engine.detectAndEmbed(bitmap)
        val results =
          faces.map { detected ->
            val (matchName, score) = findBestMatch(detected.embedding)
            RecognizedFace(crop = detected.crop, name = matchName, similarity = score)
          }
        _uiState.update { it.copy(isProcessing = false, recognizeResults = results) }
      } catch (e: Exception) {
        Log.w(TAG, "Live face frame error", e)
        _uiState.update { it.copy(isProcessing = false) }
      } finally {
        bitmap.recycle()
      }
    }
  }

  suspend fun detectLiveMeshes(bitmap: Bitmap, engine: FaceEmbedEngine): List<FaceMesh> {
    return try {
      engine.detectFaceMeshes(bitmap)
    } catch (_: Exception) {
      emptyList()
    }
  }

  fun savePendingFace(face: PendingFace, name: String) {
    val cleanName = name.trim()
    if (cleanName.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
      val embBytes = floatArrayToBytes(face.embedding)
      val thumbBytes = encodeThumbnail(face.crop)
      faceDao.insert(
        FaceRecord(
          name = cleanName,
          embedding = embBytes,
          thumbnail = thumbBytes,
          createdAt = System.currentTimeMillis(),
        )
      )
      _uiState.update { state ->
        state.copy(
          pendingFaces = state.pendingFaces.filterNot { it === face },
          statusMessage = "Saved $cleanName",
        )
      }
      refreshEnrolledFaces()
    }
  }

  fun renamePerson(oldName: String, newName: String) {
    val cleanNew = newName.trim()
    if (cleanNew.isEmpty() || cleanNew == oldName) return
    viewModelScope.launch(Dispatchers.IO) {
      faceDao.rename(oldName, cleanNew)
      refreshEnrolledFaces()
      _uiState.update { it.copy(statusMessage = "Renamed $oldName to $cleanNew") }
    }
  }

  fun deleteFace(id: Long) {
    viewModelScope.launch(Dispatchers.IO) {
      faceDao.deleteById(id)
      refreshEnrolledFaces()
    }
  }

  fun clearAllFaces() {
    viewModelScope.launch(Dispatchers.IO) {
      faceDao.deleteAll()
      refreshEnrolledFaces()
      _uiState.update { it.copy(statusMessage = "All saved faces cleared") }
    }
  }

  private fun findBestMatch(embedding: FloatArray): Pair<String?, Float> {
    val records = cachedRecords
    if (records.isEmpty()) return null to 0f
    var bestName: String? = null
    var bestSim = -1f
    for ((name, storedVec) in records) {
      val len = minOf(embedding.size, storedVec.size)
      var dot = 0f
      for (i in 0 until len) dot += embedding[i] * storedVec[i]
      if (dot > bestSim) {
        bestSim = dot
        bestName = name
      }
    }
    val threshold = _uiState.value.threshold
    return if (bestSim >= threshold && bestName != null) {
      bestName to bestSim
    } else {
      null to bestSim.coerceAtLeast(0f)
    }
  }

  private fun floatArrayToBytes(vec: FloatArray): ByteArray {
    val buf = ByteBuffer.allocate(vec.size * 4)
    buf.asFloatBuffer().put(vec)
    return buf.array()
  }

  private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
    val count = bytes.size / 4
    val out = FloatArray(count)
    ByteBuffer.wrap(bytes).asFloatBuffer().get(out)
    return out
  }

  private fun encodeThumbnail(crop: Bitmap): ByteArray {
    val maxDim = max(crop.width, crop.height)
    val thumb =
      if (maxDim > 160) {
        val scale = 160f / maxDim
        Bitmap.createScaledBitmap(
          crop,
          max(1, (crop.width * scale).toInt()),
          max(1, (crop.height * scale).toInt()),
          true,
        )
      } else {
        crop
      }
    val bos = ByteArrayOutputStream()
    thumb.compress(Bitmap.CompressFormat.JPEG, 85, bos)
    if (thumb !== crop) thumb.recycle()
    return bos.toByteArray()
  }
}
