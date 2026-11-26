package com.google.ai.edge.gallery.data.rag

import android.net.Uri
import com.google.ai.edge.gallery.data.local.dao.RagChunkDao
import com.google.ai.edge.gallery.data.local.dao.RagDocumentDao
import com.google.ai.edge.gallery.data.local.entities.RagChunkEntity
import com.google.ai.edge.gallery.data.local.entities.RagDocumentEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class ImportProgress(
  val stage: String,
  val fraction: Float = 0f,
  val error: String? = null,
)

data class RetrievedChunk(
  val documentId: String,
  val documentTitle: String,
  val ordinal: Int,
  val text: String,
  val score: Float,
)

@Singleton
class RagRepository
@Inject
constructor(
  private val documentParser: DocumentParser,
  private val embeddingEngine: EmbeddingEngine,
  private val ragDocumentDao: RagDocumentDao,
  private val ragChunkDao: RagChunkDao,
) {

  fun observeDocuments(): Flow<List<RagDocumentEntity>> = ragDocumentDao.observeAll()

  suspend fun getDocumentById(documentId: String): RagDocumentEntity? =
    ragDocumentDao.getById(documentId)

  suspend fun deleteDocument(documentId: String) {
    val doc = ragDocumentDao.getById(documentId) ?: return
    ragDocumentDao.delete(doc)
  }

  suspend fun importDocument(
    uri: Uri,
    title: String,
    onProgress: (ImportProgress) -> Unit,
  ): Result<RagDocumentEntity> =
    withContext(Dispatchers.IO) {
      try {
        onProgress(ImportProgress("Reading PDF…", 0.05f))
        onProgress(ImportProgress("Extracting text…", 0.15f))
        val extracted = documentParser.extractText(uri)
        if (extracted.isNullOrBlank()) {
          val msg = "No extractable text found in PDF."
          onProgress(ImportProgress("Import failed: $msg", 0f, error = msg))
          return@withContext Result.failure(IllegalStateException(msg))
        }

        val chunks = withContext(Dispatchers.Default) { documentParser.chunkText(extracted) }
        if (chunks.isEmpty()) {
          val msg = "Document produced 0 text chunks."
          onProgress(ImportProgress("Import failed: $msg", 0f, error = msg))
          return@withContext Result.failure(IllegalStateException(msg))
        }

        onProgress(ImportProgress("Splitting into ${chunks.size} chunks…", 0.25f))

        val docId = UUID.randomUUID().toString()
        val chunkEntities =
          withContext(Dispatchers.Default) {
            chunks.mapIndexed { idx, chunkText ->
              val frac = 0.25f + 0.70f * ((idx + 1).toFloat() / chunks.size)
              onProgress(ImportProgress("Embedding chunk ${idx + 1}/${chunks.size}…", frac))
              val vec = embeddingEngine.embed(chunkText)
              RagChunkEntity(
                documentId = docId,
                ordinal = idx,
                text = chunkText,
                embedding = EmbeddingEngine.floatArrayToBytes(vec),
              )
            }
          }

        val docEntity =
          RagDocumentEntity(
            id = docId,
            title = title,
            uri = uri.toString(),
            createdAt = System.currentTimeMillis(),
            chunkCount = chunkEntities.size,
          )
        ragDocumentDao.insert(docEntity)
        ragChunkDao.insertAll(chunkEntities)
        onProgress(ImportProgress("Done", 1f))
        Result.success(docEntity)
      } catch (e: Exception) {
        val msg = e.message ?: e::class.java.simpleName
        onProgress(ImportProgress("Import failed: $msg", 0f, error = msg))
        Result.failure(e)
      }
    }

  suspend fun retrieveTopChunks(
    query: String,
    scopeDocumentId: String? = null,
    topK: Int = 4,
  ): List<RetrievedChunk> =
    withContext(Dispatchers.Default) {
      val chunks =
        withContext(Dispatchers.IO) {
          if (scopeDocumentId != null) {
            ragChunkDao.getForDocuments(listOf(scopeDocumentId))
          } else {
            ragChunkDao.getAll()
          }
        }
      if (chunks.isEmpty()) return@withContext emptyList()

      val queryVec = embeddingEngine.embed(query)
      val docTitles = HashMap<String, String>()
      withContext(Dispatchers.IO) {
        for (chunk in chunks) {
          if (!docTitles.containsKey(chunk.documentId)) {
            docTitles[chunk.documentId] =
              ragDocumentDao.getById(chunk.documentId)?.title ?: "Document"
          }
        }
      }

      chunks
        .map { chunk ->
          val chunkVec = EmbeddingEngine.bytesToFloatArray(chunk.embedding)
          val score = EmbeddingEngine.cosineSimilarity(queryVec, chunkVec)
          RetrievedChunk(
            documentId = chunk.documentId,
            documentTitle = docTitles[chunk.documentId] ?: "Document",
            ordinal = chunk.ordinal,
            text = chunk.text,
            score = score,
          )
        }
        .sortedByDescending { it.score }
        .take(topK)
    }
}
