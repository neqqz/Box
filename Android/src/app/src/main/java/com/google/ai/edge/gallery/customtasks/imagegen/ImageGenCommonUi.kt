package com.google.ai.edge.gallery.customtasks.imagegen

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.ai.edge.litert.CompiledModel
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun readAssetFloats(context: Context, assetPath: String): FloatArray =
  context.assets.open(assetPath).use { input: InputStream ->
    val bytes = input.readBytes()
    val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    FloatArray(fb.remaining()).also { fb.get(it) }
  }

internal fun closeLiteRtBuffers(model: CompiledModel, inputs: List<com.google.ai.edge.litert.TensorBuffer>, outputs: List<com.google.ai.edge.litert.TensorBuffer>) {
  inputs.forEach { runCatching { it.close() } }
  outputs.forEach { runCatching { it.close() } }
  runCatching { model.close() }
}

internal suspend fun saveBitmapToPictures(
  context: Context,
  prefix: String,
  bitmap: Bitmap,
): Boolean = withContext(Dispatchers.IO) {
  runCatching {
    val fileName = "${prefix}_${System.currentTimeMillis()}.png"
    val values = ContentValues().apply {
      put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
      put(MediaStore.Images.Media.MIME_TYPE, "image/png")
      put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/LocalDiffusion")
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
      ?: return@withContext false
    context.contentResolver.openOutputStream(uri)?.use { out ->
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    } ?: return@withContext false
    true
  }.getOrDefault(false)
}

internal fun shareBitmap(context: Context, prefix: String, bitmap: Bitmap) {
  runCatching {
    val dir = File(context.cacheDir, "shared_images").apply { mkdirs() }
    val file = File(dir, "${prefix}_${System.currentTimeMillis()}.png")
    file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
      type = "image/png"
      putExtra(Intent.EXTRA_STREAM, uri)
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share generated image"))
  }
}

@Composable
internal fun LiteRtImageGenScreen(
  title: String,
  filePrefix: String,
  placeholder: String,
  isGenerating: Boolean,
  statusText: String,
  progress: Float,
  resultBitmap: Bitmap?,
  errorMessage: String?,
  bottomPadding: Dp,
  onGenerate: (String) -> Unit,
  onCancel: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val snackbar = remember { SnackbarHostState() }
  var prompt by remember { mutableStateOf("") }

  Scaffold(
    snackbarHost = { SnackbarHost(snackbar) },
    modifier = Modifier.padding(bottom = bottomPadding),
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .padding(16.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(title, style = MaterialTheme.typography.titleMedium)

      OutlinedTextField(
        value = prompt,
        onValueChange = { prompt = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(placeholder) },
        minLines = 3,
        maxLines = 5,
        enabled = !isGenerating,
      )

      if (isGenerating) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              statusText,
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onCancel) {
              Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text("Cancel")
            }
          }
        }
      } else {
        Button(
          onClick = { onGenerate(prompt.trim()) },
          enabled = prompt.isNotBlank(),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text("Generate")
        }
      }

      if (!errorMessage.isNullOrBlank()) {
        Card(
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            errorMessage,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
          )
        }
      }

      if (resultBitmap != null) {
        Image(
          bitmap = resultBitmap.asImageBitmap(),
          contentDescription = prompt,
          modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp)),
          contentScale = ContentScale.Fit,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(
            onClick = {
              scope.launch {
                val ok = saveBitmapToPictures(context, filePrefix, resultBitmap)
                snackbar.showSnackbar(if (ok) "Saved to Pictures/LocalDiffusion" else "Save failed")
              }
            },
            modifier = Modifier.weight(1f),
          ) {
            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Save")
          }
          OutlinedButton(
            onClick = { shareBitmap(context, filePrefix, resultBitmap) },
            modifier = Modifier.weight(1f),
          ) {
            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Share")
          }
        }
      }
      Spacer(Modifier.height(8.dp))
    }
  }
}
