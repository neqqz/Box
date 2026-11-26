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

import android.content.Context
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import com.google.ai.edge.gallery.proto.Theme

object ThemeSettings {
  const val PREFS_NAME = "box_settings"

  val themeOverride = mutableStateOf(Theme.THEME_AUTO)
  val customTheme = mutableStateOf(CustomThemeType.SYSTEM)
  val catppuccinFlavor = mutableStateOf(CatppuccinFlavor.MOCHA)
  val catppuccinAccent = mutableStateOf(CatppuccinAccent.MAUVE)
  val draculaAccent = mutableStateOf(DraculaAccent.PURPLE)
  val ptyxisPalette = mutableStateOf(PtyxisPalette.NORD)
  val ptyxisPaletteExtended = mutableStateOf(PtyxisExtendedPalette.SOLARIZED)
  val monochromeAccents = mutableStateOf(false)

  val appFont = mutableStateOf(AppFont.NUNITO)
  val customFont
    get() = appFont
  val chatFont = mutableStateOf<AppFont?>(null)
  val quickActionsFont = mutableStateOf<AppFont?>(null)
  val categoryTabsFont = mutableStateOf<AppFont?>(null)
  val fontScale = mutableFloatStateOf(1.0f)

  fun initFromPrefs(context: Context, savedThemeOverride: Theme = themeOverride.value) {
    themeOverride.value = savedThemeOverride
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    customTheme.value = CustomThemeType.fromId(prefs.getString("custom_theme", "system"))
    catppuccinFlavor.value = CatppuccinFlavor.fromId(prefs.getString("catppuccin_flavor", "mocha"))
    catppuccinAccent.value = CatppuccinAccent.fromId(prefs.getString("catppuccin_accent", "mauve"))
    draculaAccent.value = DraculaAccent.fromId(prefs.getString("dracula_accent", "purple"))
    ptyxisPalette.value = PtyxisPalette.fromId(prefs.getString("ptyxis_palette", "nord"))
    ptyxisPaletteExtended.value = PtyxisExtendedPalette.fromId(
      prefs.getString("ptyxis_palette_extended", "solarized")
    )
    monochromeAccents.value = prefs.getBoolean("monochrome_accents", false)

    val appFontKey = prefs.getString("app_font", null)
      ?: prefs.getString("custom_font", "nunito")
    appFont.value = AppFont.fromKey(appFontKey)
    chatFont.value = prefs.getString("chat_font", "")?.takeIf { it.isNotEmpty() }?.let(AppFont::fromKey)
    quickActionsFont.value =
      prefs.getString("quick_actions_font", "")?.takeIf { it.isNotEmpty() }?.let(AppFont::fromKey)
    categoryTabsFont.value =
      prefs.getString("category_tabs_font", "")?.takeIf { it.isNotEmpty() }?.let(AppFont::fromKey)
    fontScale.floatValue = prefs.getFloat("font_scale", 1.0f).coerceIn(0.8f, 1.4f)
  }

  fun setCustomTheme(context: Context, type: CustomThemeType) {
    customTheme.value = type
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
      .putString("custom_theme", type.id)
      .apply()
  }

  fun setCatppuccinFlavor(context: Context, flavor: CatppuccinFlavor) {
    catppuccinFlavor.value = flavor
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
      .putString("catppuccin_flavor", flavor.id)
      .apply()
  }

  fun setCatppuccinAccent(context: Context, accent: CatppuccinAccent) {
    catppuccinAccent.value = accent
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
      .putString("catppuccin_accent", accent.id)
      .apply()
  }

  fun setDraculaAccent(context: Context, accent: DraculaAccent) {
    draculaAccent.value = accent
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
      .putString("dracula_accent", accent.id)
      .apply()
  }

  fun setPtyxisPalette(context: Context, palette: PtyxisPalette) {
    ptyxisPalette.value = palette
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
      .putString("ptyxis_palette", palette.id)
      .apply()
  }

  fun setPtyxisExtendedPalette(context: Context, palette: PtyxisExtendedPalette) {
    ptyxisPaletteExtended.value = palette
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
      .putString("ptyxis_palette_extended", palette.id)
      .apply()
  }

  fun setMonochromeAccents(context: Context, enabled: Boolean) {
    monochromeAccents.value = enabled
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
      .putBoolean("monochrome_accents", enabled)
      .apply()
  }

  fun setAppFont(context: Context, font: AppFont) {
    appFont.value = font
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
      .putString("app_font", font.key)
      .apply()
  }

  fun setChatFont(context: Context, font: AppFont?) {
    chatFont.value = font
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
      .putString("chat_font", font?.key ?: "")
      .apply()
  }

  fun setQuickActionsFont(context: Context, font: AppFont?) {
    quickActionsFont.value = font
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
      .putString("quick_actions_font", font?.key ?: "")
      .apply()
  }

  fun setCategoryTabsFont(context: Context, font: AppFont?) {
    categoryTabsFont.value = font
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
      .putString("category_tabs_font", font?.key ?: "")
      .apply()
  }

  fun setFontScale(context: Context, scale: Float, persist: Boolean) {
    val clamped = scale.coerceIn(0.8f, 1.4f)
    fontScale.floatValue = clamped
    if (persist) {
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        .putFloat("font_scale", clamped)
        .apply()
    }
  }
}
