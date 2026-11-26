package com.google.ai.edge.gallery.data.rag

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val TAG = "DocumentParser"
private const val EXTRACT_TIMEOUT_MS = 120_000L

@Singleton
class DocumentParser @Inject constructor(@ApplicationContext private val context: Context) {

  private val pdfBoxInitialized = AtomicBoolean(false)

  private fun ensurePdfBoxInitialized() {
    if (pdfBoxInitialized.compareAndSet(false, true)) {
      PDFBoxResourceLoader.init(context)
    }
  }

  fun resolveDisplayName(uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
      cursor ->
      if (cursor.moveToFirst()) {
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0) {
          val raw = cursor.getString(idx)
          if (!raw.isNullOrBlank()) {
            return raw.removeSuffix(".pdf")
          }
        }
      }
    }
    val segment = uri.lastPathSegment?.substringAfterLast('/')
    return if (!segment.isNullOrBlank()) segment.removeSuffix(".pdf") else "Document"
  }

  suspend fun extractText(uri: Uri): String? =
    withContext(Dispatchers.IO) {
      val mime = context.contentResolver.getType(uri).orEmpty()
      val isTextLike = mime.startsWith("text/") && !mime.contains("pdf")

      val stream = context.contentResolver.openInputStream(uri)
      if (stream == null) {
        Log.w(TAG, "Cannot open stream for URI: $uri")
        return@withContext null
      }

      withTimeout(EXTRACT_TIMEOUT_MS) {
        stream.use { input ->
          val rawText =
            if (isTextLike) {
              BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            } else {
              ensurePdfBoxInitialized()
              PDDocument.load(input).use { doc -> PDFTextStripper().getText(doc) }
            }
          normalizeText(rawText).ifBlank { null }
        }
      }
    }

  private fun normalizeText(raw: String): String {
    return raw
      .replace(Regex("[ \\t]+"), " ")
      .replace(Regex("\\n{3,}"), "\n\n")
      .trim()
  }

  fun chunkText(text: String, maxChars: Int = 800, overlap: Int = 120): List<String> {
    if (text.isBlank()) return emptyList()
    val chunks = ArrayList<String>()
    var start = 0
    val len = text.length
    val minBreakOffset = maxChars / 2

    while (start < len) {
      var end = minOf(start + maxChars, len)
      if (end < len) {
        // Prefer breaking on paragraph, sentence end, or whitespace rather than mid-word.
        val paraBreak = text.lastIndexOf("\n\n", end)
        val sentenceBreak = text.lastIndexOf(". ", end)
        val wordBreak = text.lastIndexOf(' ', end)
        end =
          when {
            paraBreak > start + minBreakOffset -> paraBreak + 2
            sentenceBreak > start + minBreakOffset -> sentenceBreak + 2
            wordBreak > start + minBreakOffset -> wordBreak + 1
            else -> end
          }
      }
      val slice = text.substring(start, end).trim()
      if (slice.isNotEmpty()) {
        chunks.add(slice)
      }
      if (end >= len) break
      val nextStart = maxOf(start + 1, end - overlap)
      start = nextStart
    }
    return chunks
  }
}
