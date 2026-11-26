package com.google.ai.edge.gallery.customtasks.rag

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.local.entities.RagDocumentEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RagScreen(
  customTaskData: CustomTaskData,
  viewModel: RagViewModel = hiltViewModel(),
) {
  val mode by viewModel.mode.collectAsState()

  // Intercept CustomTaskScreen top-bar back when inside RAG Chat so Back returns to Library.
  DisposableEffect(customTaskData, mode) {
    if (mode is RagScreenMode.Chat) {
      customTaskData.setCustomNavigateUpCallback { viewModel.openLibrary() }
    } else {
      customTaskData.setCustomNavigateUpCallback(null)
    }
    onDispose { customTaskData.setCustomNavigateUpCallback(null) }
  }

  BackHandler(enabled = mode is RagScreenMode.Chat) { viewModel.openLibrary() }

  when (val curMode = mode) {
    is RagScreenMode.Library -> RagLibraryView(customTaskData = customTaskData, viewModel = viewModel)
    is RagScreenMode.Chat ->
      RagChatView(customTaskData = customTaskData, scopeDocumentId = curMode.scopeDocumentId, viewModel = viewModel)
  }
}

@Composable
private fun RagLibraryView(
  customTaskData: CustomTaskData,
  viewModel: RagViewModel,
) {
  val documents by viewModel.documents.collectAsState()
  val importProgress by viewModel.importProgress.collectAsState()
  val modelManagerViewModel = customTaskData.modelManagerViewModel

  var docPendingDelete by remember { mutableStateOf<RagDocumentEntity?>(null) }

  val pdfPicker =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        viewModel.importPdf(uri)
      }
    }

  Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
      AnimatedVisibility(visible = importProgress != null) {
        importProgress?.let { progress ->
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(
                  text = progress.stage,
                  style = MaterialTheme.typography.bodyMedium,
                  color =
                    if (progress.error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                  modifier = Modifier.weight(1f),
                )
                if (progress.fraction >= 1f || progress.error != null) {
                  TextButton(onClick = { viewModel.clearImportProgress() }) { Text("Dismiss") }
                }
              }
              if (progress.error == null && progress.fraction < 1f) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                  progress = { progress.fraction.coerceIn(0f, 1f) },
                  modifier = Modifier.fillMaxWidth(),
                )
              }
            }
          }
        }
      }

      RagModelSelectorBar(
        modelManagerViewModel = modelManagerViewModel,
        viewModel = viewModel,
      )

      if (documents.isEmpty() && importProgress == null) {
        Box(
          modifier = Modifier.fillMaxSize().padding(32.dp),
          contentAlignment = Alignment.Center,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
              imageVector = Icons.AutoMirrored.Outlined.LibraryBooks,
              contentDescription = null,
              modifier = Modifier.size(56.dp),
              tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
              text = "No documents yet",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = "Import a PDF to index it on your device and ask questions offline.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else {
        LazyColumn(
          contentPadding =
            PaddingValues(
              start = 16.dp,
              end = 16.dp,
              top = 12.dp,
              bottom = customTaskData.bottomPadding + 88.dp,
            ),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          if (documents.size > 1) {
            item(key = "__ask_all__") {
              OutlinedButton(
                onClick = { viewModel.openChat(scopeDocumentId = null) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
              ) {
                Icon(Icons.Rounded.Forum, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Ask across all ${documents.size} documents")
              }
            }
          }

          items(documents, key = { it.id }) { doc ->
            RagDocumentCard(
              doc = doc,
              onAskClick = { viewModel.openChat(scopeDocumentId = doc.id) },
              onDeleteClick = { docPendingDelete = doc },
            )
          }
        }
      }
    }

    ExtendedFloatingActionButton(
      onClick = { pdfPicker.launch(arrayOf("application/pdf", "text/plain")) },
      icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
      text = { Text("Import PDF") },
      modifier =
        Modifier.align(Alignment.BottomEnd)
          .padding(end = 16.dp, bottom = customTaskData.bottomPadding + 24.dp),
    )
  }

  docPendingDelete?.let { target ->
    AlertDialog(
      onDismissRequest = { docPendingDelete = null },
      title = { Text("Delete document?") },
      text = {
        Text("\"${target.title}\" and all its indexed chunks (${target.chunkCount}) will be removed.")
      },
      confirmButton = {
        TextButton(
          onClick = {
            viewModel.deleteDocument(target.id)
            docPendingDelete = null
          }
        ) {
          Text("Delete", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { docPendingDelete = null }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun RagDocumentCard(
  doc: RagDocumentEntity,
  onAskClick: () -> Unit,
  onDeleteClick: () -> Unit,
) {
  val dateStr = remember(doc.createdAt) {
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(doc.createdAt))
  }
  Card(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onAskClick),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Outlined.Description,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(32.dp),
      )
      Spacer(modifier = Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = doc.title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "${doc.chunkCount} chunks · $dateStr",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      IconButton(onClick = onDeleteClick) {
        Icon(
          imageVector = Icons.Rounded.DeleteOutline,
          contentDescription = "Delete",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RagChatView(
  customTaskData: CustomTaskData,
  scopeDocumentId: String?,
  viewModel: RagViewModel,
) {
  val documents by viewModel.documents.collectAsState()
  val messages by viewModel.messages.collectAsState()
  val isGenerating by viewModel.isGenerating.collectAsState()
  val listState = rememberLazyListState()
  var inputText by remember { mutableStateOf("") }

  val scopeTitle =
    remember(scopeDocumentId, documents) {
      if (scopeDocumentId == null) {
        "All Documents"
      } else {
        documents.firstOrNull { it.id == scopeDocumentId }?.title ?: "Document Q&A"
      }
    }

  LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
    if (messages.isNotEmpty()) {
      listState.animateScrollToItem(messages.lastIndex)
    }
  }

  Column(modifier = Modifier.fillMaxSize().imePadding()) {
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceVariant)
          .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Outlined.FormatListBulleted,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.width(6.dp))
      Text(
        text = scopeTitle,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }

    RagModelSelectorBar(
      modelManagerViewModel = customTaskData.modelManagerViewModel,
      viewModel = viewModel,
    )

    LazyColumn(
      modifier = Modifier.weight(1f).fillMaxWidth(),
      state = listState,
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(messages, key = { it.id }) { msg ->
        val align = if (msg.isUser) Alignment.End else Alignment.Start
        val bubbleBg =
          if (msg.isUser) MaterialTheme.colorScheme.primaryContainer
          else MaterialTheme.colorScheme.surfaceContainerHigh
        val bubbleFg =
          if (msg.isUser) MaterialTheme.colorScheme.onPrimaryContainer
          else MaterialTheme.colorScheme.onSurface
        val bubbleShape =
          RoundedCornerShape(
            topStart = 16.dp,
            topEnd = 16.dp,
            bottomStart = if (msg.isUser) 16.dp else 4.dp,
            bottomEnd = if (msg.isUser) 4.dp else 16.dp,
          )

        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
          Box(
            modifier =
              Modifier.widthIn(max = 320.dp)
                .clip(bubbleShape)
                .background(bubbleBg)
                .padding(horizontal = 14.dp, vertical = 10.dp)
          ) {
            Text(
              text = msg.text.ifEmpty { if (!msg.isUser && isGenerating) "…" else "" },
              style = MaterialTheme.typography.bodyMedium,
              color = bubbleFg,
            )
          }

          if (msg.sources.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(4.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              msg.sources.distinctBy { it.documentTitle to it.chunkOrdinal }.forEach { src ->
                SuggestionChip(
                  onClick = {},
                  label = {
                    Text(
                      text = "${src.documentTitle} #${src.chunkOrdinal}",
                      style = MaterialTheme.typography.labelSmall,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                    )
                  },
                )
              }
            }
          }
        }
      }
    }

    Row(
      modifier =
        Modifier.fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 8.dp)
          .padding(bottom = customTaskData.bottomPadding),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedTextField(
        value = inputText,
        onValueChange = { inputText = it },
        modifier = Modifier.weight(1f),
        enabled = !isGenerating,
        shape = RoundedCornerShape(24.dp),
        maxLines = 4,
        placeholder = { Text("Ask about your documents…") },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions =
          KeyboardActions(
            onSend = {
              if (inputText.isNotBlank() && !isGenerating) {
                viewModel.sendQuery(inputText, customTaskData.modelManagerViewModel)
                inputText = ""
              }
            }
          ),
      )
      Spacer(modifier = Modifier.width(8.dp))
      if (isGenerating) {
        FilledIconButton(
          onClick = { viewModel.stopGeneration() },
          modifier = Modifier.size(48.dp),
          colors =
            IconButtonDefaults.filledIconButtonColors(
              containerColor = MaterialTheme.colorScheme.errorContainer
            ),
        ) {
          Icon(Icons.Rounded.Stop, contentDescription = "Stop")
        }
      } else {
        FilledIconButton(
          onClick = {
            if (inputText.isNotBlank()) {
              viewModel.sendQuery(inputText, customTaskData.modelManagerViewModel)
              inputText = ""
            }
          },
          enabled = inputText.isNotBlank(),
          modifier = Modifier.size(48.dp),
        ) {
          Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
        }
      }
    }
  }
}

@Composable
private fun RagModelSelectorBar(
  modelManagerViewModel: com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel,
  viewModel: RagViewModel,
) {
  val uiState by modelManagerViewModel.uiState.collectAsState()
  val selectedModelName by viewModel.selectedModelName.collectAsState()
  val readyModels =
    remember(uiState.modelDownloadStatus, uiState.tasks) {
      viewModel.getReadyLlmModels(modelManagerViewModel)
    }
  if (readyModels.isEmpty()) return

  val activeName =
    readyModels.firstOrNull { it.name == selectedModelName }?.name ?: readyModels.first().name
  var expanded by remember { mutableStateOf(false) }

  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = Icons.Outlined.SmartToy,
      contentDescription = null,
      modifier = Modifier.size(16.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.width(6.dp))
    Text(
      text = "Model:",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.width(8.dp))
    Box {
      AssistChip(
        onClick = { expanded = true },
        label = { Text(activeName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
      )
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        readyModels.forEach { model ->
          DropdownMenuItem(
            text = { Text(model.name) },
            onClick = {
              viewModel.selectModel(model.name)
              expanded = false
            },
          )
        }
      }
    }
  }
}
