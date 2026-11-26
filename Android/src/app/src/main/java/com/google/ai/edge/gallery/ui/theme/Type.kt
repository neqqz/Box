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

package com.google.ai.edge.gallery.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.R

val NunitoFontFamily =
  FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_extralight, FontWeight.ExtraLight),
    Font(R.font.nunito_light, FontWeight.Light),
    Font(R.font.nunito_medium, FontWeight.Medium),
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
    Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
    Font(R.font.nunito_black, FontWeight.Black),
  )

val AliceFontFamily = FontFamily(Font(R.font.alice_regular, FontWeight.Normal))

val CormorantGaramondFontFamily =
  FontFamily(
    Font(R.font.cormorant_garamond_light, FontWeight.Light),
    Font(R.font.cormorant_garamond_lightitalic, FontWeight.Light, FontStyle.Italic),
    Font(R.font.cormorant_garamond_regular, FontWeight.Normal),
    Font(R.font.cormorant_garamond_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.cormorant_garamond_medium, FontWeight.Medium),
    Font(R.font.cormorant_garamond_mediumitalic, FontWeight.Medium, FontStyle.Italic),
    Font(R.font.cormorant_garamond_semibold, FontWeight.SemiBold),
    Font(R.font.cormorant_garamond_semibolditalic, FontWeight.SemiBold, FontStyle.Italic),
    Font(R.font.cormorant_garamond_bold, FontWeight.Bold),
    Font(R.font.cormorant_garamond_bolditalic, FontWeight.Bold, FontStyle.Italic),
  )

val DotGothic16FontFamily = FontFamily(Font(R.font.dotgothic16_regular, FontWeight.Normal))

val IbmPlexMonoFontFamily =
  FontFamily(
    Font(R.font.ibm_plex_mono_thin, FontWeight.Thin),
    Font(R.font.ibm_plex_mono_thinitalic, FontWeight.Thin, FontStyle.Italic),
    Font(R.font.ibm_plex_mono_extralight, FontWeight.ExtraLight),
    Font(R.font.ibm_plex_mono_extralightitalic, FontWeight.ExtraLight, FontStyle.Italic),
    Font(R.font.ibm_plex_mono_light, FontWeight.Light),
    Font(R.font.ibm_plex_mono_lightitalic, FontWeight.Light, FontStyle.Italic),
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_mediumitalic, FontWeight.Medium, FontStyle.Italic),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_mono_semibolditalic, FontWeight.SemiBold, FontStyle.Italic),
    Font(R.font.ibm_plex_mono_bold, FontWeight.Bold),
    Font(R.font.ibm_plex_mono_bolditalic, FontWeight.Bold, FontStyle.Italic),
  )

val IbmPlexSerifFontFamily =
  FontFamily(
    Font(R.font.ibm_plex_serif_thin, FontWeight.Thin),
    Font(R.font.ibm_plex_serif_thinitalic, FontWeight.Thin, FontStyle.Italic),
    Font(R.font.ibm_plex_serif_extralight, FontWeight.ExtraLight),
    Font(R.font.ibm_plex_serif_extralightitalic, FontWeight.ExtraLight, FontStyle.Italic),
    Font(R.font.ibm_plex_serif_light, FontWeight.Light),
    Font(R.font.ibm_plex_serif_lightitalic, FontWeight.Light, FontStyle.Italic),
    Font(R.font.ibm_plex_serif_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_serif_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.ibm_plex_serif_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_serif_mediumitalic, FontWeight.Medium, FontStyle.Italic),
    Font(R.font.ibm_plex_serif_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_serif_semibolditalic, FontWeight.SemiBold, FontStyle.Italic),
    Font(R.font.ibm_plex_serif_bold, FontWeight.Bold),
    Font(R.font.ibm_plex_serif_bolditalic, FontWeight.Bold, FontStyle.Italic),
  )

val InstrumentSerifFontFamily =
  FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
  )

val PlayfairDisplayFontFamily =
  FontFamily(
    Font(R.font.playfair_display_regular, FontWeight.Normal),
    Font(R.font.playfair_display_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.playfair_display_medium, FontWeight.Medium),
    Font(R.font.playfair_display_mediumitalic, FontWeight.Medium, FontStyle.Italic),
    Font(R.font.playfair_display_semibold, FontWeight.SemiBold),
    Font(R.font.playfair_display_semibolditalic, FontWeight.SemiBold, FontStyle.Italic),
    Font(R.font.playfair_display_bold, FontWeight.Bold),
    Font(R.font.playfair_display_bolditalic, FontWeight.Bold, FontStyle.Italic),
    Font(R.font.playfair_display_extrabold, FontWeight.ExtraBold),
    Font(R.font.playfair_display_extrabolditalic, FontWeight.ExtraBold, FontStyle.Italic),
    Font(R.font.playfair_display_black, FontWeight.Black),
    Font(R.font.playfair_display_blackitalic, FontWeight.Black, FontStyle.Italic),
  )

val PressStart2PFontFamily = FontFamily(Font(R.font.press_start_2p_regular, FontWeight.Normal))

val QuicksandFontFamily =
  FontFamily(
    Font(R.font.quicksand_light, FontWeight.Light),
    Font(R.font.quicksand_regular, FontWeight.Normal),
    Font(R.font.quicksand_medium, FontWeight.Medium),
    Font(R.font.quicksand_semibold, FontWeight.SemiBold),
    Font(R.font.quicksand_bold, FontWeight.Bold),
  )

val SpaceGroteskFontFamily =
  FontFamily(
    Font(R.font.space_grotesk_light, FontWeight.Light),
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
  )

val TurretRoadFontFamily =
  FontFamily(
    Font(R.font.turret_road_extralight, FontWeight.ExtraLight),
    Font(R.font.turret_road_light, FontWeight.Light),
    Font(R.font.turret_road_regular, FontWeight.Normal),
    Font(R.font.turret_road_medium, FontWeight.Medium),
    Font(R.font.turret_road_bold, FontWeight.Bold),
    Font(R.font.turret_road_extrabold, FontWeight.ExtraBold),
  )

val ViaodaLibreFontFamily = FontFamily(Font(R.font.viaoda_libre_regular, FontWeight.Normal))

enum class AppFont(val key: String, val displayName: String, val fontFamily: FontFamily) {
  NUNITO("nunito", "Nunito (default)", NunitoFontFamily),
  ALICE("alice", "Alice", AliceFontFamily),
  CORMORANT_GARAMOND("cormorant_garamond", "Cormorant Garamond", CormorantGaramondFontFamily),
  DOTGOTHIC16("dotgothic16", "DotGothic16", DotGothic16FontFamily),
  IBM_PLEX_MONO("ibm_plex_mono", "IBM Plex Mono", IbmPlexMonoFontFamily),
  IBM_PLEX_SERIF("ibm_plex_serif", "IBM Plex Serif", IbmPlexSerifFontFamily),
  INSTRUMENT_SERIF("instrument_serif", "Instrument Serif", InstrumentSerifFontFamily),
  PLAYFAIR_DISPLAY("playfair_display", "Playfair Display", PlayfairDisplayFontFamily),
  PRESS_START_2P("press_start_2p", "Press Start 2P", PressStart2PFontFamily),
  QUICKSAND("quicksand", "Quicksand", QuicksandFontFamily),
  SPACE_GROTESK("space_grotesk", "Space Grotesk", SpaceGroteskFontFamily),
  TURRET_ROAD("turret_road", "Turret Road", TurretRoadFontFamily),
  VIAODA_LIBRE("viaoda_libre", "Viaoda Libre", ViaodaLibreFontFamily);

  companion object {
    fun fromKey(key: String?): AppFont = entries.firstOrNull { it.key == key } ?: NUNITO
    fun fromKeyOrNull(key: String?): AppFont? = entries.firstOrNull { it.key == key }
  }
}

val appFontFamily = NunitoFontFamily

val baseline = Typography()

private fun TextUnit.scaled(scale: Float): TextUnit =
  if (this == TextUnit.Unspecified || scale == 1.0f) this else this * scale

fun typographyFor(fontFamily: FontFamily, scale: Float = 1.0f): Typography =
  Typography(
    displayLarge = baseline.displayLarge.copy(fontFamily = fontFamily, fontSize = baseline.displayLarge.fontSize.scaled(scale), lineHeight = baseline.displayLarge.lineHeight.scaled(scale)),
    displayMedium = baseline.displayMedium.copy(fontFamily = fontFamily, fontSize = baseline.displayMedium.fontSize.scaled(scale), lineHeight = baseline.displayMedium.lineHeight.scaled(scale)),
    displaySmall = baseline.displaySmall.copy(fontFamily = fontFamily, fontSize = baseline.displaySmall.fontSize.scaled(scale), lineHeight = baseline.displaySmall.lineHeight.scaled(scale)),
    headlineLarge = baseline.headlineLarge.copy(fontFamily = fontFamily, fontSize = baseline.headlineLarge.fontSize.scaled(scale), lineHeight = baseline.headlineLarge.lineHeight.scaled(scale)),
    headlineMedium = baseline.headlineMedium.copy(fontFamily = fontFamily, fontSize = baseline.headlineMedium.fontSize.scaled(scale), lineHeight = baseline.headlineMedium.lineHeight.scaled(scale)),
    headlineSmall = baseline.headlineSmall.copy(fontFamily = fontFamily, fontSize = baseline.headlineSmall.fontSize.scaled(scale), lineHeight = baseline.headlineSmall.lineHeight.scaled(scale)),
    titleLarge = baseline.titleLarge.copy(fontFamily = fontFamily, fontSize = baseline.titleLarge.fontSize.scaled(scale), lineHeight = baseline.titleLarge.lineHeight.scaled(scale)),
    titleMedium = baseline.titleMedium.copy(fontFamily = fontFamily, fontSize = baseline.titleMedium.fontSize.scaled(scale), lineHeight = baseline.titleMedium.lineHeight.scaled(scale)),
    titleSmall = baseline.titleSmall.copy(fontFamily = fontFamily, fontSize = baseline.titleSmall.fontSize.scaled(scale), lineHeight = baseline.titleSmall.lineHeight.scaled(scale)),
    bodyLarge = baseline.bodyLarge.copy(fontFamily = fontFamily, fontSize = baseline.bodyLarge.fontSize.scaled(scale), lineHeight = baseline.bodyLarge.lineHeight.scaled(scale)),
    bodyMedium = baseline.bodyMedium.copy(fontFamily = fontFamily, fontSize = baseline.bodyMedium.fontSize.scaled(scale), lineHeight = baseline.bodyMedium.lineHeight.scaled(scale)),
    bodySmall = baseline.bodySmall.copy(fontFamily = fontFamily, fontSize = baseline.bodySmall.fontSize.scaled(scale), lineHeight = baseline.bodySmall.lineHeight.scaled(scale)),
    labelLarge = baseline.labelLarge.copy(fontFamily = fontFamily, fontSize = baseline.labelLarge.fontSize.scaled(scale), lineHeight = baseline.labelLarge.lineHeight.scaled(scale)),
    labelMedium = baseline.labelMedium.copy(fontFamily = fontFamily, fontSize = baseline.labelMedium.fontSize.scaled(scale), lineHeight = baseline.labelMedium.lineHeight.scaled(scale)),
    labelSmall = baseline.labelSmall.copy(fontFamily = fontFamily, fontSize = baseline.labelSmall.fontSize.scaled(scale), lineHeight = baseline.labelSmall.lineHeight.scaled(scale)),
  )

val AppTypography = typographyFor(appFontFamily)

val titleMediumNarrow =
  baseline.titleMedium.copy(fontFamily = appFontFamily, letterSpacing = 0.0.sp)

val titleSmaller =
  baseline.titleSmall.copy(
    fontFamily = appFontFamily,
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold,
  )

val labelSmallNarrow = baseline.labelSmall.copy(fontFamily = appFontFamily, letterSpacing = 0.0.sp)

val labelSmallNarrowMedium =
  baseline.labelSmall.copy(
    fontFamily = appFontFamily,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.0.sp,
  )

val bodySmallNarrow = baseline.bodySmall.copy(fontFamily = appFontFamily, letterSpacing = 0.0.sp)

val bodySmallMediumNarrow =
  baseline.bodySmall.copy(fontFamily = appFontFamily, letterSpacing = 0.0.sp, fontSize = 14.sp)

val bodySmallMediumNarrowBold =
  baseline.bodySmall.copy(
    fontFamily = appFontFamily,
    letterSpacing = 0.0.sp,
    fontSize = 14.sp,
    fontWeight = FontWeight.Bold,
  )

val homePageTitleStyle =
  baseline.displayMedium.copy(
    fontFamily = appFontFamily,
    fontSize = 48.sp,
    lineHeight = 48.sp,
    letterSpacing = -1.sp,
    fontWeight = FontWeight.Medium,
  )

val bodyLargeNarrow = baseline.bodyLarge.copy(letterSpacing = 0.2.sp)

val headlineLargeMedium = baseline.headlineLarge.copy(fontWeight = FontWeight.Medium)

val emptyStateTitle = baseline.headlineSmall.copy(fontSize = 37.sp, lineHeight = 50.sp)
val emptyStateContent = baseline.headlineSmall.copy(fontSize = 16.sp, lineHeight = 22.sp)
