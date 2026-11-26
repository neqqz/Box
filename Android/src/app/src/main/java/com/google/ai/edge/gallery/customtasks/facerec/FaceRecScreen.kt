package com.google.ai.edge.gallery.customtasks.facerec

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.Model
import com.google.mlkit.vision.facemesh.FaceMesh
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun FaceRecScreen(
  model: Model,
  customTaskData: CustomTaskData,
  viewModel: FaceRecViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val engine = (model.instance as? FaceEmbedEngine)

  Column(
    modifier =
      Modifier.fillMaxSize().padding(bottom = customTaskData.bottomPadding)
  ) {
    val tabs = listOf(
      FaceRecTab.RECOGNIZE to "Photo",
      FaceRecTab.LIVE to "Live",
      FaceRecTab.ENROLLED to "Saved (${uiState.enrolled.size})",
      FaceRecTab.ADD to "Enrol",
    )
    TabRow(selectedTabIndex = tabs.indexOfFirst { it.first == uiState.tab }.coerceAtLeast(0)) {
      tabs.forEach { (tab, title) ->
        Tab(
          selected = uiState.tab == tab,
          onClick = { viewModel.selectTab(tab) },
          text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
      }
    }

    uiState.error?.let { err ->
      Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
      ) {
        Text(
          text = err,
          color = MaterialTheme.colorScheme.onErrorContainer,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(12.dp),
        )
      }
    }

    uiState.statusMessage?.let { msg ->
      Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
      ) {
        Text(
          text = msg,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(12.dp),
        )
      }
    }

    if (engine == null) {
      Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
      }
      return@Column
    }

    when (uiState.tab) {
      FaceRecTab.RECOGNIZE ->
        RecognizePhotoTab(uiState = uiState, engine = engine, viewModel = viewModel)
      FaceRecTab.LIVE ->
        LiveCameraTab(uiState = uiState, engine = engine, viewModel = viewModel)
      FaceRecTab.ENROLLED ->
        EnrolledPeopleTab(uiState = uiState, viewModel = viewModel)
      FaceRecTab.ADD ->
        EnrolFacesTab(uiState = uiState, engine = engine, viewModel = viewModel)
    }
  }
}

@Composable
private fun RecognizePhotoTab(
  uiState: FaceRecUiState,
  engine: FaceEmbedEngine,
  viewModel: FaceRecViewModel,
) {
  val picker =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri != null) {
        viewModel.recognizeFromUri(uri, engine)
      }
    }

  LazyColumn(
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier.fillMaxSize(),
  ) {
    item {
      Button(
        onClick = {
          picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = !uiState.isProcessing,
      ) {
        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Pick photo to recognize faces")
      }
    }

    if (uiState.isProcessing) {
      item {
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
          Spacer(modifier = Modifier.width(10.dp))
          Text("Scanning…", style = MaterialTheme.typography.bodyMedium)
        }
      }
    }

    items(uiState.recognizeResults) { face ->
      RecognizedFaceCard(face = face, showPercent = uiState.showPercent)
    }
  }
}

@Composable
private fun LiveCameraTab(
  uiState: FaceRecUiState,
  engine: FaceEmbedEngine,
  viewModel: FaceRecViewModel,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val scope = rememberCoroutineScope()

  var hasCameraPermission by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    )
  }
  val permLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      hasCameraPermission = granted
    }

  LaunchedEffect(Unit) {
    if (!hasCameraPermission) {
      permLauncher.launch(Manifest.permission.CAMERA)
    }
  }

  if (!hasCameraPermission) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.Videocam, contentDescription = null, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text("Camera permission is needed for live face recognition.")
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
          Text("Grant camera permission")
        }
      }
    }
    return
  }

  var liveMeshes by remember { mutableStateOf<List<FaceMesh>>(emptyList()) }
  var frameSize by remember { mutableStateOf(Pair(1, 1)) }
  val busyFlag = remember { AtomicBoolean(false) }
  var lastRunTime by remember { mutableStateOf(0L) }
  val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

  DisposableEffect(Unit) {
    onDispose { analysisExecutor.shutdownNow() }
  }

  Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
    Box(
      modifier =
        Modifier.weight(1f)
          .fillMaxWidth()
          .clip(RoundedCornerShape(16.dp))
          .background(Color.Black)
    ) {
      AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } },
        update = { previewView ->
          val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
          cameraProviderFuture.addListener(
            {
              val provider = cameraProviderFuture.get()
              val preview =
                Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
              val analyzer =
                ImageAnalysis.Builder()
                  .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                  .build()
              analyzer.setAnalyzer(analysisExecutor) { proxy: ImageProxy ->
                val now = System.currentTimeMillis()
                if (now - lastRunTime < 650L || !busyFlag.compareAndSet(false, true)) {
                  proxy.close()
                  return@setAnalyzer
                }
                lastRunTime = now
                val rotated = proxyToOrientedBitmap(proxy, mirror = uiState.frontCamera)
                proxy.close()
                if (rotated == null) {
                  busyFlag.set(false)
                  return@setAnalyzer
                }
                frameSize = rotated.width to rotated.height
                scope.launch {
                  try {
                    if (uiState.showMesh) {
                      liveMeshes = viewModel.detectLiveMeshes(rotated, engine)
                    } else if (liveMeshes.isNotEmpty()) {
                      liveMeshes = emptyList()
                    }
                    viewModel.recognizeLiveBitmap(rotated, engine)
                  } finally {
                    busyFlag.set(false)
                  }
                }
              }

              val selector =
                if (uiState.frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA

              try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, selector, preview, analyzer)
              } catch (_: Exception) {
              }
            },
            ContextCompat.getMainExecutor(context),
          )
        },
      )

      if (uiState.showMesh && liveMeshes.isNotEmpty()) {
        val primaryColor = MaterialTheme.colorScheme.primary
        Canvas(modifier = Modifier.fillMaxSize()) {
          val fw = frameSize.first.toFloat().coerceAtLeast(1f)
          val fh = frameSize.second.toFloat().coerceAtLeast(1f)
          val sx = size.width / fw
          val sy = size.height / fh
          for (mesh in liveMeshes) {
            for (pt in mesh.allPoints) {
              drawCircle(
                color = primaryColor,
                radius = 2.5f,
                center = Offset(pt.position.x * sx, pt.position.y * sy),
              )
            }
          }
        }
      }

      IconButton(
        onClick = { viewModel.toggleFrontCamera() },
        modifier =
          Modifier.align(Alignment.TopEnd)
            .padding(12.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f)),
      ) {
        Icon(Icons.Rounded.Cameraswitch, contentDescription = "Switch camera", tint = Color.White)
      }
    }

    Spacer(modifier = Modifier.height(10.dp))

    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      FilterChip(
        selected = uiState.showMesh,
        onClick = { viewModel.setShowMesh(!uiState.showMesh) },
        label = { Text("Face mesh overlay") },
      )
      FilterChip(
        selected = uiState.showPercent,
        onClick = { viewModel.setShowPercent(!uiState.showPercent) },
        label = { Text("Show match %") },
      )
    }

    if (uiState.recognizeResults.isNotEmpty()) {
      Spacer(modifier = Modifier.height(8.dp))
      uiState.recognizeResults.take(3).forEach { res ->
        RecognizedFaceCard(face = res, showPercent = uiState.showPercent)
        Spacer(modifier = Modifier.height(6.dp))
      }
    }
  }
}

@Composable
private fun EnrolFacesTab(
  uiState: FaceRecUiState,
  engine: FaceEmbedEngine,
  viewModel: FaceRecViewModel,
) {
  val picker =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri != null) {
        viewModel.detectForEnrolment(uri, engine)
      }
    }

  val knownNames = remember(uiState.enrolled) { uiState.enrolled.map { it.name }.distinct() }

  LazyColumn(
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier.fillMaxSize(),
  ) {
    item {
      Button(
        onClick = {
          picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = !uiState.isProcessing,
      ) {
        Icon(Icons.Rounded.PersonAdd, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Pick photo with face to enrol")
      }
    }

    items(uiState.pendingFaces) { pending ->
      PendingFaceCard(
        face = pending,
        knownNames = knownNames,
        onSave = { name -> viewModel.savePendingFace(pending, name) },
      )
    }
  }
}

@Composable
private fun PendingFaceCard(
  face: PendingFace,
  knownNames: List<String>,
  onSave: (String) -> Unit,
) {
  var nameInput by remember(face) { mutableStateOf(face.suggestedName ?: "") }

  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
          bitmap = face.crop.asImageBitmap(),
          contentDescription = "Detected face",
          modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)),
          contentScale = ContentScale.Crop,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
          OutlinedTextField(
            value = nameInput,
            onValueChange = { nameInput = it },
            label = { Text("Person's name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }

      if (knownNames.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          knownNames.take(4).forEach { existing ->
            FilterChip(
              selected = nameInput == existing,
              onClick = { nameInput = existing },
              label = { Text(existing, maxLines = 1) },
            )
          }
        }
      }

      Button(
        onClick = { onSave(nameInput) },
        enabled = nameInput.isNotBlank(),
        modifier = Modifier.align(Alignment.End),
      ) {
        Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Save face")
      }
    }
  }
}

@Composable
private fun EnrolledPeopleTab(
  uiState: FaceRecUiState,
  viewModel: FaceRecViewModel,
) {
  var renameTarget by remember { mutableStateOf<EnrolledFaceUi?>(null) }
  var confirmClearAll by remember { mutableStateOf(false) }

  LazyColumn(
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
    modifier = Modifier.fillMaxSize(),
  ) {
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
      ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "Match threshold: ${(uiState.threshold * 100).roundToInt()}%",
            style = MaterialTheme.typography.titleSmall,
          )
          Slider(
            value = uiState.threshold,
            onValueChange = { viewModel.setThreshold(it) },
            valueRange = 0.35f..0.80f,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text("Show similarity percentage", style = MaterialTheme.typography.bodyMedium)
            Switch(
              checked = uiState.showPercent,
              onCheckedChange = { viewModel.setShowPercent(it) },
            )
          }
          if (uiState.enrolled.isNotEmpty()) {
            OutlinedButton(
              onClick = { confirmClearAll = true },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text("Clear all saved faces", color = MaterialTheme.colorScheme.error)
            }
          }
        }
      }
    }

    if (uiState.enrolled.isEmpty()) {
      item {
        Box(
          modifier = Modifier.fillMaxWidth().padding(32.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = "No faces enrolled yet. Switch to Enrol to teach Box a face.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      items(uiState.enrolled, key = { it.id }) { person ->
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (person.thumbnail != null) {
              Image(
                bitmap = person.thumbnail.asImageBitmap(),
                contentDescription = person.name,
                modifier = Modifier.size(52.dp).clip(CircleShape),
                contentScale = ContentScale.Crop,
              )
            } else {
              Icon(
                Icons.Rounded.Face,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
              )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
              text = person.name,
              style = MaterialTheme.typography.titleMedium,
              modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { renameTarget = person }) {
              Icon(Icons.Rounded.Edit, contentDescription = "Rename")
            }
            IconButton(onClick = { viewModel.deleteFace(person.id) }) {
              Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete")
            }
          }
        }
      }
    }
  }

  renameTarget?.let { person ->
    var newName by remember(person) { mutableStateOf(person.name) }
    AlertDialog(
      onDismissRequest = { renameTarget = null },
      title = { Text("Rename person") },
      text = {
        OutlinedTextField(
          value = newName,
          onValueChange = { newName = it },
          singleLine = true,
          label = { Text("Name") },
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            viewModel.renamePerson(person.name, newName)
            renameTarget = null
          }
        ) {
          Text("Save")
        }
      },
      dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
    )
  }

  if (confirmClearAll) {
    AlertDialog(
      onDismissRequest = { confirmClearAll = false },
      title = { Text("Clear all saved faces?") },
      text = { Text("All enrolled face embeddings will be permanently removed from this device.") },
      confirmButton = {
        TextButton(
          onClick = {
            viewModel.clearAllFaces()
            confirmClearAll = false
          }
        ) {
          Text("Clear all", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun RecognizedFaceCard(face: RecognizedFace, showPercent: Boolean) {
  Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Image(
        bitmap = face.crop.asImageBitmap(),
        contentDescription = face.name ?: "Unknown face",
        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)),
        contentScale = ContentScale.Crop,
      )
      Spacer(modifier = Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = face.name ?: "Unknown person",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        if (showPercent && face.similarity > 0f) {
          Text(
            text = "Match: ${(face.similarity * 100).roundToInt()}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

private fun proxyToOrientedBitmap(proxy: ImageProxy, mirror: Boolean): Bitmap? {
  return try {
    val src = proxy.toBitmap()
    val rot = proxy.imageInfo.rotationDegrees.toFloat()
    val m = Matrix()
    if (rot != 0f) m.postRotate(rot)
    if (mirror) m.postScale(-1f, 1f)
    if (m.isIdentity) {
      src
    } else {
      Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).also {
        if (it !== src) src.recycle()
      }
    }
  } catch (_: Exception) {
    null
  }
}
