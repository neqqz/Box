package com.google.ai.edge.gallery.customtasks.rag

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.local.entities.RagDocumentEntity
import com.google.ai.edge.gallery.data.rag.DocumentParser
import com.google.ai.edge.gallery.data.rag.ImportProgress
import com.google.ai.edge.gallery.data.rag.RagRepository
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val TAG = "RagViewModel"

sealed interface RagScreenMode {
  data object Library : RagScreenMode
  data class Chat(val scopeDocumentId: String? = null) : RagScreenMode
}

data class RagSource(
  val documentTitle: String,
  val chunkOrdinal: Int,
  val score: Float,
)

data class RagChatMessage(
  val id: Long,
  val isUser: Boolean,
  val text: String,
  val sources: List<RagSource> = emptyList(),
)

@HiltViewModel
class RagViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val ragRepository: RagRepository,
  private val documentParser: DocumentParser,
) : ViewModel() {

  val documents: StateFlow<List<RagDocumentEntity>> =
    ragRepository.observeDocuments().stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = emptyList(),
    )

  private val _mode = MutableStateFlow<RagScreenMode>(RagScreenMode.Library)
  val mode: StateFlow<RagScreenMode> = _mode.asStateFlow()

  private val _importProgress = MutableStateFlow<ImportProgress?>(null)
  val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()

  private val _messages = MutableStateFlow<List<RagChatMessage>>(emptyList())
  val messages: StateFlow<List<RagChatMessage>> = _messages.asStateFlow()

  private val _isGenerating = MutableStateFlow(false)
  val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

  private val _selectedModelName = MutableStateFlow("")
  val selectedModelName: StateFlow<String> = _selectedModelName.asStateFlow()

  private val messageIdGen = AtomicLong(1L)
  private var queryJob: Job? = null
  private var loadedModel: Model? = null

  fun openLibrary() {
    queryJob?.cancel()
    _isGenerating.value = false
    _mode.value = RagScreenMode.Library
  }

  fun openChat(scopeDocumentId: String? = null) {
    _messages.value = emptyList()
    _mode.value = RagScreenMode.Chat(scopeDocumentId = scopeDocumentId)
  }

  fun importPdf(uri: Uri) {
    try {
      context.contentResolver.takePersistableUriPermission(
        uri,
        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
      )
    } catch (_: SecurityException) {
    }
    val title = documentParser.resolveDisplayName(uri)
    viewModelScope.launch {
      ragRepository.importDocument(uri = uri, title = title) { progress ->
        _importProgress.value = progress
      }
    }
  }

  fun clearImportProgress() {
    _importProgress.value = null
  }

  fun deleteDocument(documentId: String) {
    viewModelScope.launch { ragRepository.deleteDocument(documentId) }
  }

  fun selectModel(modelName: String) {
    if (_selectedModelName.value != modelName) {
      cleanupLoadedModel()
      _selectedModelName.value = modelName
    }
  }

  fun stopGeneration() {
    queryJob?.cancel()
    loadedModel?.let { model ->
      try {
        model.runtimeHelper.stopResponse(model)
      } catch (e: Exception) {
        Log.w(TAG, "Error stopping RAG generation", e)
      }
    }
    _isGenerating.value = false
  }

  fun sendQuery(question: String, modelManagerViewModel: ModelManagerViewModel) {
    val trimmed = question.trim()
    if (trimmed.isEmpty() || _isGenerating.value) return
    val chatMode = _mode.value as? RagScreenMode.Chat ?: return

    val readyModels = getReadyLlmModels(modelManagerViewModel)
    if (readyModels.isEmpty()) {
      _messages.update {
        it +
          RagChatMessage(
            id = messageIdGen.getAndIncrement(),
            isUser = false,
            text = "No AI model found. Download a model in AI Chat first, then return here.",
          )
      }
      return
    }

    val chosenModel =
      readyModels.firstOrNull { it.name == _selectedModelName.value } ?: readyModels.first()
    _selectedModelName.value = chosenModel.name

    val userMsg =
      RagChatMessage(id = messageIdGen.getAndIncrement(), isUser = true, text = trimmed)
    val assistantId = messageIdGen.getAndIncrement()
    val placeholderMsg = RagChatMessage(id = assistantId, isUser = false, text = "")
    _messages.update { it + userMsg + placeholderMsg }
    _isGenerating.value = true

    queryJob?.cancel()
    queryJob =
      viewModelScope.launch {
        try {
          val retrieved =
            ragRepository.retrieveTopChunks(
              query = trimmed,
              scopeDocumentId = chatMode.scopeDocumentId,
              topK = 4,
            )

          val sources =
            retrieved.map { chunk ->
              RagSource(
                documentTitle = chunk.documentTitle,
                chunkOrdinal = chunk.ordinal + 1,
                score = chunk.score,
              )
            }
          updateAssistantMessage(assistantId) { msg -> msg.copy(sources = sources) }

          val ready = ensureModelInitialized(chosenModel)
          if (!ready) {
            updateAssistantMessage(assistantId) { msg ->
              msg.copy(text = "Failed to initialize model ${chosenModel.name}.")
            }
            _isGenerating.value = false
            return@launch
          }

          val prompt = buildRagPrompt(question = trimmed, retrieved = retrieved)
          runInferenceSuspend(chosenModel, prompt, assistantId)
        } catch (e: Exception) {
          Log.e(TAG, "RAG query failed", e)
          updateAssistantMessage(assistantId) { msg ->
            msg.copy(text = "Error answering question: ${e.message ?: "Unknown error"}")
          }
        } finally {
          _isGenerating.value = false
        }
      }
  }

  fun getReadyLlmModels(modelManagerViewModel: ModelManagerViewModel): List<Model> {
    val state = modelManagerViewModel.uiState.value
    val llmTask = modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_CHAT) ?: return emptyList()
    return llmTask.models.filter { model ->
      val status = state.modelDownloadStatus[model.name]?.status
      status == ModelDownloadStatusType.SUCCEEDED && File(model.getPath(context)).exists()
    }
  }

  private suspend fun ensureModelInitialized(model: Model): Boolean {
    if (loadedModel?.name == model.name && model.instance != null) {
      return true
    }
    if (loadedModel != null && loadedModel?.name != model.name) {
      cleanupLoadedModel()
    }
    if (model.instance != null) {
      loadedModel = model
      return true
    }

    return withContext(Dispatchers.Default) {
      suspendCancellableCoroutine { cont ->
        model.runtimeHelper.initialize(
          context = context,
          model = model,
          supportImage = false,
          supportAudio = false,
          onDone = { err ->
            val ok = err.isEmpty() && model.instance != null
            if (ok) {
              loadedModel = model
            } else {
              Log.e(TAG, "Failed to init RAG LLM ${model.name}: $err")
            }
            if (cont.isActive) cont.resume(ok)
          },
        )
      }
    }
  }

  private suspend fun runInferenceSuspend(model: Model, prompt: String, assistantId: Long) {
    withContext(Dispatchers.Default) {
      val accumulated = StringBuilder()
      suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
          try {
            model.runtimeHelper.stopResponse(model)
          } catch (_: Exception) {
          }
        }
        model.runtimeHelper.runInference(
          model = model,
          input = prompt,
          resultListener = { partial, done, _ ->
            if (partial.isNotEmpty()) {
              accumulated.append(partial)
              val text = accumulated.toString()
              updateAssistantMessage(assistantId) { it.copy(text = text) }
            }
            if (done && cont.isActive) {
              cont.resume(Unit)
            }
          },
          cleanUpListener = {},
          onError = { errMsg ->
            updateAssistantMessage(assistantId) { msg ->
              msg.copy(
                text =
                  if (accumulated.isNotEmpty()) accumulated.toString() + "\n\n" + errMsg else errMsg
              )
            }
            if (cont.isActive) cont.resume(Unit)
          },
        )
      }
    }
  }

  private fun buildRagPrompt(
    question: String,
    retrieved: List<com.google.ai.edge.gallery.data.rag.RetrievedChunk>,
  ): String {
    if (retrieved.isEmpty()) {
      return question
    }
    val contextBlock =
      retrieved.joinToString("\n\n") { chunk ->
        "[Source: ${chunk.documentTitle} — Excerpt #${chunk.ordinal + 1}]\n${chunk.text}"
      }
    return """
      |Answer the user's question using the document excerpts below. If the answer is not contained in the excerpts, clearly state what is missing instead of guessing.
      |
      |Context from uploaded documents:
      |$contextBlock
      |
      |Question: $question
      |Answer:
      """.trimMargin()
  }

  private fun updateAssistantMessage(
    messageId: Long,
    transform: (RagChatMessage) -> RagChatMessage,
  ) {
    _messages.update { list ->
      list.map { if (it.id == messageId) transform(it) else it }
    }
  }

  private fun cleanupLoadedModel() {
    val model = loadedModel ?: return
    loadedModel = null
    try {
      model.runtimeHelper.cleanUp(model = model, onDone = {})
    } catch (e: Exception) {
      Log.w(TAG, "Error cleaning up RAG model ${model.name}", e)
    }
  }

  override fun onCleared() {
    super.onCleared()
    queryJob?.cancel()
    cleanupLoadedModel()
  }
}
