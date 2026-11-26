/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.ui.theme.customColors
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.CodeBlockStyle
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.string.RichTextStringStyle
import io.github.darriousliu.katex.core.MTMathView
import io.github.darriousliu.katex.core.MTMathViewMode
import io.github.darriousliu.katex.core.MTTextAlignment

private sealed interface MarkdownSegment {
  data class RichTextSegment(val text: String) : MarkdownSegment
  data class DisplayMathSegment(val latex: String) : MarkdownSegment
}

private sealed interface InlineMathPart {
  data class TextPart(val text: String) : InlineMathPart
  data class MathPart(val latex: String) : InlineMathPart
}

private val CODE_MATH_BLOCK_REGEX = Regex("```(?:latex|math)\\s*([\\s\\S]*?)```")
private val LATEX_ENV_REGEX =
  Regex("\\\\begin\\{(equation|align|gather|multline|eqnarray)\\*?\\}([\\s\\S]*?)\\\\end\\{(?:equation|align|gather|multline|eqnarray)\\*?\\}")
private val BRACKET_DISPLAY_MATH_REGEX = Regex("\\\\\\[([\\s\\S]*?)\\\\\\]")
private val DISPLAY_DOLLAR_REGEX = Regex("\\$\\$([\\s\\S]*?)\\$\\$")
private val INLINE_DOLLAR_REGEX = Regex("\\$((?:[^$]|\\\\\\$)+?)\\$")
private val INLINE_PAREN_MATH_REGEX = Regex("\\\\\\((.+?)\\\\\\)")

/**
 * Normalizes escaped newlines and standardizes fenced `latex`/`math` blocks, `\[...\]`,
 * `\(...\)`, and standard LaTeX math environments into `$...$` / `$$...$$`.
 */
private fun preprocessLatexMarkdown(raw: String): String {
  var out = raw
    .replace("\\\r\n", "\n")
    .replace(Regex("(?<!\\\\)\\\\\\n"), "\n")
    .replace("\\\r", "\r")
    .replace("\nabla", "\\nabla")
  out = CODE_MATH_BLOCK_REGEX.replace(out) { m -> "\n$$\n${m.groupValues[1].trim()}\n$$\n" }
  out = LATEX_ENV_REGEX.replace(out) { m -> "\n$$\n${m.groupValues[2].trim()}\n$$\n" }
  out = BRACKET_DISPLAY_MATH_REGEX.replace(out) { m -> "\n$$\n${m.groupValues[1].trim()}\n$$\n" }
  out = INLINE_PAREN_MATH_REGEX.replace(out) { m -> "$${m.groupValues[1].trim()}$" }
  return out
}

/** Splits preprocessed text into non-math Markdown segments and display `$$...$$` math blocks. */
private fun splitDisplayMath(text: String): List<MarkdownSegment> {
  val result = mutableListOf<MarkdownSegment>()
  var cursor = 0
  for (match in DISPLAY_DOLLAR_REGEX.findAll(text)) {
    if (match.range.first > cursor) {
      val prefix = text.substring(cursor, match.range.first)
      if (prefix.isNotBlank()) result += MarkdownSegment.RichTextSegment(prefix)
    }
    val latex = match.groupValues[1].trim()
    if (latex.isNotEmpty()) {
      result += MarkdownSegment.DisplayMathSegment(latex)
    }
    cursor = match.range.last + 1
  }
  if (cursor < text.length) {
    val tail = text.substring(cursor)
    if (tail.isNotBlank()) result += MarkdownSegment.RichTextSegment(tail)
  }
  return result
}

private fun splitInlineMath(paragraph: String): List<InlineMathPart> {
  val parts = mutableListOf<InlineMathPart>()
  var cursor = 0
  for (match in INLINE_DOLLAR_REGEX.findAll(paragraph)) {
    if (match.range.first > cursor) {
      parts += InlineMathPart.TextPart(paragraph.substring(cursor, match.range.first))
    }
    val latex = match.groupValues[1].trim()
    if (latex.isNotEmpty()) {
      parts += InlineMathPart.MathPart(latex)
    }
    cursor = match.range.last + 1
  }
  if (cursor < paragraph.length) {
    parts += InlineMathPart.TextPart(paragraph.substring(cursor))
  }
  return parts
}

private fun cleanMathForKatex(latex: String): String {
  return latex
    .replace(Regex("\\\\label\\{[^}]*\\}"), "")
    .replace(Regex("\\\\tag\\{[^}]*\\}"), "")
    .replace(Regex("&\\s*"), "")
    .trim()
}

/** Composable function to display Markdown-formatted text with KaTeX math rendering. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarkdownText(
  text: String,
  modifier: Modifier = Modifier,
  smallFontSize: Boolean = false,
  textColor: Color = MaterialTheme.colorScheme.onSurface,
  linkColor: Color = MaterialTheme.customColors.linkColor,
) {
  val fontSize =
    if (smallFontSize) MaterialTheme.typography.bodyMedium.fontSize
    else MaterialTheme.typography.bodyLarge.fontSize
  val lineHeight = fontSize * if (smallFontSize) 1.4f else 1.5f
  val richTextStyle = remember(linkColor) {
    RichTextStyle(
      codeBlockStyle =
        CodeBlockStyle(
          textStyle =
            TextStyle(
              fontSize = 12.sp,
              fontFamily = FontFamily.Monospace,
              lineHeight = 16.8.sp,
            )
        ),
      stringStyle =
        RichTextStringStyle(linkStyle = TextLinkStyles(style = SpanStyle(color = linkColor))),
    )
  }
  val segments = remember(text) { splitDisplayMath(preprocessLatexMarkdown(text)) }

  ProvideTextStyle(
    value =
      TextStyle(
        fontSize = fontSize,
        lineHeight = lineHeight,
        color = textColor,
        letterSpacing = 0.2.sp,
      )
  ) {
    if (segments.isEmpty()) {
      RichText(modifier = modifier, style = richTextStyle) { Markdown(content = text) }
    } else {
      Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.forEach { segment ->
          when (segment) {
            is MarkdownSegment.DisplayMathSegment -> {
              val cleaned = cleanMathForKatex(segment.latex)
              if (cleaned.isNotEmpty()) {
                Surface(
                  modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                  color = MaterialTheme.colorScheme.surfaceContainerLow,
                  shape = MaterialTheme.shapes.small,
                ) {
                  Box(
                    modifier =
                      Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                  ) {
                    MTMathView(
                      latex = cleaned,
                      fontSize = fontSize * 1.15f,
                      textColor = textColor,
                      mode = MTMathViewMode.KMTMathViewModeDisplay,
                      textAlignment = MTTextAlignment.KMTTextAlignmentCenter,
                      displayErrorInline = false,
                    )
                  }
                }
              }
            }
            is MarkdownSegment.RichTextSegment -> {
              // Avoid splitting inside fenced code blocks when searching for inline `$...$`
              val hasInlineMath =
                !segment.text.contains("```") && INLINE_DOLLAR_REGEX.containsMatchIn(segment.text)
              if (!hasInlineMath) {
                RichText(style = richTextStyle) { Markdown(content = segment.text) }
              } else {
                val paragraphs = segment.text.split("\n\n")
                paragraphs.forEach { para ->
                  if (INLINE_DOLLAR_REGEX.containsMatchIn(para)) {
                    FlowRow(
                      horizontalArrangement = Arrangement.spacedBy(2.dp),
                      verticalArrangement = Arrangement.Center,
                    ) {
                      splitInlineMath(para).forEach { part ->
                        when (part) {
                          is InlineMathPart.TextPart -> {
                            RichText(style = richTextStyle) { Markdown(content = part.text) }
                          }
                          is InlineMathPart.MathPart -> {
                            val cleaned = cleanMathForKatex(part.latex)
                            if (cleaned.isNotEmpty()) {
                              MTMathView(
                                latex = cleaned,
                                fontSize = fontSize,
                                textColor = textColor,
                                mode = MTMathViewMode.KMTMathViewModeText,
                                textAlignment = MTTextAlignment.KMTTextAlignmentLeft,
                                displayErrorInline = false,
                              )
                            }
                          }
                        }
                      }
                    }
                  } else if (para.isNotBlank()) {
                    RichText(style = richTextStyle) { Markdown(content = para) }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
