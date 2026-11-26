package com.google.ai.edge.gallery.customtasks.imagegen

import java.io.File
import java.text.Normalizer
import java.util.regex.Pattern
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure-Kotlin Qwen / GPT-2 byte-level BPE tokenizer supporting both plain text
 * (`qwen_vocab.txt`, `qwen_merges.txt`, `qwen_special.txt`) and HuggingFace JSON
 * (`bonsai_vocab.json`, `bonsai_merges.txt`, `bonsai_added_tokens.json` or Iris's `qwen_added.txt`).
 */
class QwenBpeTokenizer private constructor(
  private val idToToken: Array<String>,
  private val tokenToId: Map<String, Int>,
  private val mergeRanks: Map<Long, Int>,
  private val byteToUnicodeChar: IntArray,
  private val specialTokens: List<Pair<String, Int>>,
) {

  fun encode(text: String): List<Int> {
    if (text.isEmpty()) return emptyList()
    val out = ArrayList<Int>(text.length / 2 + 8)
    var pos = 0
    while (pos < text.length) {
      val specialMatch = findSpecialAt(text, pos)
      if (specialMatch == null) {
        var nextPos = pos + 1
        while (nextPos < text.length && findSpecialAt(text, nextPos) == null) {
          nextPos++
        }
        encodeOrdinary(text.substring(pos, nextPos), out)
        pos = nextPos
      } else {
        out.add(specialMatch.second)
        pos += specialMatch.first.length
      }
    }
    return out
  }

  private fun findSpecialAt(text: String, pos: Int): Pair<String, Int>? {
    for (pair in specialTokens) {
      if (text.startsWith(pair.first, pos)) return pair
    }
    return null
  }

  private fun encodeOrdinary(text: String, out: MutableList<Int>) {
    val norm = Normalizer.normalize(text, Normalizer.Form.NFC)
    val matcher = PRETOKENIZE_PATTERN.matcher(norm)
    var idx = 0
    while (idx < norm.length) {
      if (!matcher.find(idx)) {
        out.addAll(bpePiece(norm.substring(idx)))
        return
      }
      if (matcher.start() > idx) {
        out.addAll(bpePiece(norm.substring(idx, matcher.start())))
      }
      out.addAll(bpePiece(matcher.group()))
      idx = matcher.end()
    }
  }

  private fun bpePiece(piece: String): List<Int> {
    val ids = ArrayList<Int>(piece.length * 2)
    for (b in piece.toByteArray(Charsets.UTF_8)) {
      val ch = String(Character.toChars(byteToUnicodeChar[b.toInt() and 0xFF]))
      ids.add(tokenToId[ch] ?: 0)
    }
    while (ids.size > 1) {
      var bestRank = Int.MAX_VALUE
      var bestIdx = -1
      for (i in 0 until ids.size - 1) {
        val key = (ids[i].toLong() shl 32) or (ids[i + 1].toLong() and 0xFFFFFFFFL)
        val rank = mergeRanks[key] ?: continue
        if (rank < bestRank) {
          bestRank = rank
          bestIdx = i
        }
      }
      if (bestIdx < 0) break
      val merged = idToToken[ids[bestIdx]] + idToToken[ids[bestIdx + 1]]
      val mergedId = tokenToId[merged] ?: break
      ids[bestIdx] = mergedId
      ids.removeAt(bestIdx + 1)
    }
    return ids
  }

  companion object {
    private val PRETOKENIZE_PATTERN: Pattern = Pattern.compile(
      "'(?:[sS\u017F]|[tT]|[rR][eE]|[vV][eE]|[mM]|[lL][lL]|[dD])" +
        "|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+" +
        "|\\p{N}" +
        "| ?[^\\t\\n\\u000B\\f\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\p{L}\\p{N}]+[\\r\\n]*" +
        "|[\\t\\n\\u000B\\f\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]*[\\r\\n]+" +
        "|[\\t\\n\\u000B\\f\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+(?![^\\t\\n\\u000B\\f\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000])" +
        "|[\\t\\n\\u000B\\f\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+",
    )

    private fun buildByteTable(): IntArray {
      val keep = HashSet<Int>(256)
      for (c in 33..126) keep.add(c)
      for (c in 161..172) keep.add(c)
      for (c in 174..255) keep.add(c)
      val out = IntArray(256)
      var extra = 0
      for (b in 0 until 256) {
        if (keep.contains(b)) {
          out[b] = b
        } else {
          out[b] = 256 + extra
          extra++
        }
      }
      return out
    }

    fun fromPlainFiles(
      vocabFile: File,
      mergesFile: File,
      specialFile: File?,
    ): QwenBpeTokenizer {
      val vocabList = vocabFile.readLines(Charsets.UTF_8)
      val idToTok = vocabList.toTypedArray()
      val tokToId = HashMap<String, Int>(vocabList.size * 2)
      idToTok.forEachIndexed { idx, tok -> tokToId[tok] = idx }
      val specials = ArrayList<Pair<String, Int>>()
      if (specialFile != null && specialFile.exists()) {
        for (line in specialFile.readLines(Charsets.UTF_8)) {
          if (line.isBlank()) continue
          val tab = line.lastIndexOf('\t')
          if (tab > 0) {
            val s = line.substring(0, tab)
            val id = line.substring(tab + 1).trim().toIntOrNull()
            if (id != null) {
              specials.add(s to id)
              tokToId[s] = id
            }
          } else {
            val sp = line.trim().split(' ')
            if (sp.size == 2) {
              val id = sp[1].toIntOrNull()
              if (id != null) {
                specials.add(sp[0] to id)
                tokToId[sp[0]] = id
              }
            }
          }
        }
      }
      specials.sortByDescending { it.first.length }
      val ranks = parseMerges(mergesFile, tokToId)
      return QwenBpeTokenizer(idToTok, tokToId, ranks, buildByteTable(), specials)
    }

    fun fromHuggingFaceJson(
      vocabJsonFile: File,
      mergesFile: File,
      addedTokensJsonFile: File?,
    ): QwenBpeTokenizer {
      val vocabObj = JSONObject(vocabJsonFile.readText(Charsets.UTF_8))
      val tokToId = HashMap<String, Int>(vocabObj.length() * 2)
      var maxId = 0
      val keys = vocabObj.keys()
      while (keys.hasNext()) {
        val tok = keys.next()
        val id = vocabObj.getInt(tok)
        tokToId[tok] = id
        if (id > maxId) maxId = id
      }
      val specials = ArrayList<Pair<String, Int>>()
      if (addedTokensJsonFile != null && addedTokensJsonFile.exists()) {
        val raw = addedTokensJsonFile.readText(Charsets.UTF_8).trim()
        if (raw.startsWith("{")) {
          val obj = JSONObject(raw)
          val k = obj.keys()
          while (k.hasNext()) {
            val tok = k.next()
            val id = obj.getInt(tok)
            tokToId[tok] = id
            specials.add(tok to id)
            if (id > maxId) maxId = id
          }
        } else if (raw.startsWith("[")) {
          val arr = JSONArray(raw)
          for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val tok = item.optString("content", "")
            val id = item.optInt("id", -1)
            if (tok.isNotEmpty() && id >= 0) {
              tokToId[tok] = id
              specials.add(tok to id)
              if (id > maxId) maxId = id
            }
          }
        }
      }
      specials.sortByDescending { it.first.length }
      val idToTok = Array(maxId + 1) { "" }
      for ((tok, id) in tokToId) {
        if (id in idToTok.indices) idToTok[id] = tok
      }
      val ranks = parseMerges(mergesFile, tokToId)
      return QwenBpeTokenizer(idToTok, tokToId, ranks, buildByteTable(), specials)
    }

    private fun parseMerges(mergesFile: File, tokToId: Map<String, Int>): Map<Long, Int> {
      val ranks = HashMap<Long, Int>(262144)
      var rank = 0
      mergesFile.forEachLine(Charsets.UTF_8) { line ->
        if (line.isBlank() || line.startsWith("#version:")) return@forEachLine
        val sp = line.indexOf(' ')
        if (sp <= 0) return@forEachLine
        val a = tokToId[line.substring(0, sp)]
        val b = tokToId[line.substring(sp + 1)]
        if (a != null && b != null) {
          val key = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)
          ranks[key] = rank++
        }
      }
      return ranks
    }
  }
}
