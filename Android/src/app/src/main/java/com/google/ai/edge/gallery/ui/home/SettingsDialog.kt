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

package com.google.ai.edge.gallery.ui.home

import android.app.LocaleManager
import android.app.UiModeManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.proto.Theme
import com.google.ai.edge.gallery.security.BiometricEncryptionManager
import com.google.ai.edge.gallery.security.PassphraseHolder
import com.google.ai.edge.gallery.security.SecurityUtils
import com.google.ai.edge.gallery.ui.common.ClickableLink
import com.google.ai.edge.gallery.ui.common.tos.AppTosDialog
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.AppFont
import com.google.ai.edge.gallery.ui.theme.CatppuccinAccent
import com.google.ai.edge.gallery.ui.theme.CatppuccinFlavor
import com.google.ai.edge.gallery.ui.theme.CustomThemeType
import com.google.ai.edge.gallery.ui.theme.DraculaAccent
import com.google.ai.edge.gallery.ui.theme.PtyxisExtendedPalette
import com.google.ai.edge.gallery.ui.theme.PtyxisPalette
import com.google.ai.edge.gallery.ui.theme.ThemeSettings
import com.google.ai.edge.gallery.ui.theme.labelSmallNarrow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

private val THEME_OPTIONS = listOf(Theme.THEME_AUTO, Theme.THEME_LIGHT, Theme.THEME_DARK)

// Language options matching APK: 7 locales + system default
private data class AppLanguage(val tag: String, val displayName: String)

private val APP_LANGUAGES = listOf(
  AppLanguage("", "System default"),
  AppLanguage("en", "English"),
  AppLanguage("fr", "Français"),
  AppLanguage("pt", "Português"),
  AppLanguage("pt-BR", "Português (Brasil)"),
  AppLanguage("de", "Deutsch"),
  AppLanguage("zh-CN", "中文 (简体)"),
)

// GGUF engine setting keys and labels (matching Box v3.5.7 LlamaCppModelHelper)
private data class GgufSetting(
  val key: String,
  val label: String,
  val description: String,
  val type: GgufSettingType,
  val options: List<Pair<String, Int>>? = null,
  val defaultInt: Int = 0,
  val defaultBool: Boolean = false,
)

private enum class GgufSettingType { INT_DROPDOWN, SWITCH }

private val GGUF_SETTINGS = listOf(
  GgufSetting(
    key = "llama_context_size",
    label = "Context size",
    description = "Tokens of conversation kept in memory. Auto caps by model and device RAM.",
    type = GgufSettingType.INT_DROPDOWN,
    options = listOf("Auto" to 0, "2048" to 2048, "4096" to 4096, "8192" to 8192, "16384" to 16384, "32768" to 32768),
    defaultInt = 0,
  ),
  GgufSetting(
    key = "llama_num_threads",
    label = "CPU threads",
    description = "Threads used while generating. Auto uses performance cores only.",
    type = GgufSettingType.INT_DROPDOWN,
    options = listOf("Auto" to 0, "2" to 2, "4" to 4, "6" to 6, "8" to 8),
    defaultInt = 0,
  ),
  GgufSetting(
    key = "llama_gpu_layers",
    label = "GPU layers",
    description = "Model layers offloaded to GPU when accelerator is GPU. Lower if a model fails on GPU.",
    type = GgufSettingType.INT_DROPDOWN,
    options = listOf("All (99)" to 99, "64" to 64, "48" to 48, "32" to 32, "24" to 24, "16" to 16, "8" to 8, "0 (CPU only)" to 0),
    defaultInt = 99,
  ),
  GgufSetting(
    key = "llama_use_mmap",
    label = "Memory-map model file (mmap)",
    description = "Off (default): read fully into RAM at load for faster first response. On: stream from storage.",
    type = GgufSettingType.SWITCH,
    defaultBool = false,
  ),
  GgufSetting(
    key = "llama_use_mlock",
    label = "Lock model in memory (mlock)",
    description = "Prevent model weights from being swapped out. Reserves RAM while loaded.",
    type = GgufSettingType.SWITCH,
    defaultBool = false,
  ),
  GgufSetting(
    key = "llama_kv_cache_q8",
    label = "Q8 KV cache",
    description = "Halves the memory used by long conversations. Turn off if a model fails to load.",
    type = GgufSettingType.SWITCH,
    defaultBool = false,
  ),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
  curThemeOverride: Theme,
  modelManagerViewModel: ModelManagerViewModel,
  onDismissed: () -> Unit,
) {
  var selectedTheme by remember { mutableStateOf(curThemeOverride) }
  var hfToken by remember { mutableStateOf(modelManagerViewModel.getTokenStatusAndData().data) }
  val dateFormatter = remember {
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
      .withZone(ZoneId.systemDefault())
      .withLocale(Locale.getDefault())
  }
  var customHfToken by remember { mutableStateOf("") }
  var isFocused by remember { mutableStateOf(false) }
  val focusRequester = remember { FocusRequester() }
  val interactionSource = remember { MutableInteractionSource() }
  var showTos by remember { mutableStateOf(false) }
  var showLicenses by remember { mutableStateOf(false) }

  Dialog(
    onDismissRequest = onDismissed,
    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
  ) {
    val focusManager = LocalFocusManager.current
    Card(
      modifier =
        Modifier.fillMaxWidth(0.92f)
          .fillMaxHeight(0.9f)
          .clickable(
            interactionSource = interactionSource,
            indication = null,
          ) {
            focusManager.clearFocus()
          },
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        // Dialog title and subtitle.
        Column {
          Text(
            "Settings",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp),
          )
          Text(
            "App version: ${BuildConfig.VERSION_NAME}",
            style = labelSmallNarrow,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.offset(y = (-6).dp),
          )
        }

        Column(
          modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          val context = LocalContext.current
          var expandAppearance by remember { mutableStateOf(true) }
          var expandSecurity by remember { mutableStateOf(false) }
          var expandNetwork by remember { mutableStateOf(false) }
          var expandChat by remember { mutableStateOf(false) }
          var expandDownloads by remember { mutableStateOf(false) }
          var expandGguf by remember { mutableStateOf(false) }
          var expandBoxAssist by remember { mutableStateOf(false) }
          var expandAbout by remember { mutableStateOf(false) }

          SettingsAccordionSection(
            title = "Appearance",
            expanded = expandAppearance,
            onToggle = { expandAppearance = !expandAppearance },
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
              // ---- Theme base switcher (Auto / Light / Dark) ----
              Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Text(
                  "Theme",
                  style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                )
                MultiChoiceSegmentedButtonRow {
                  THEME_OPTIONS.forEachIndexed { index, theme ->
                    SegmentedButton(
                      shape =
                        SegmentedButtonDefaults.itemShape(index = index, count = THEME_OPTIONS.size),
                      onCheckedChange = {
                        selectedTheme = theme
                        ThemeSettings.themeOverride.value = theme
                        modelManagerViewModel.saveThemeOverride(theme)
                        val uiModeManager =
                          context.applicationContext.getSystemService(Context.UI_MODE_SERVICE)
                            as UiModeManager
                        if (theme == Theme.THEME_AUTO) {
                          uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
                        } else if (theme == Theme.THEME_LIGHT) {
                          uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
                        } else {
                          uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
                        }
                      },
                      checked = theme == selectedTheme,
                      label = { Text(themeLabel(theme)) },
                    )
                  }
                }
              }

              // ---- Custom Theme Picker ----
              CustomThemeSection(context)

              // ---- Font Picker ----
              FontPickerSection(context)

              // ---- Language Switcher ----
              LanguageSwitcherSection(context)
            }
          }

          SettingsAccordionSection(
            title = "Privacy & Security",
            expanded = expandSecurity,
            onToggle = { expandSecurity = !expandSecurity },
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
              // ---- Biometric lock toggle ----
              val dbEncEnabled by BiometricEncryptionManager.isEnabledFlow.collectAsState()
              Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Text(
                  "Biometric lock",
                  style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                )
                Text(
                  if (dbEncEnabled) "Covered by database encryption — disable that first to use this."
                  else "Require biometric authentication to access the app.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val biometricLockEnabled = remember {
                  mutableStateOf(com.google.ai.edge.gallery.security.AppLockManager.isBiometricLockEnabled())
                }
                Row(
                  modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    if (biometricLockEnabled.value) "Enabled" else "Disabled",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (dbEncEnabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    else MaterialTheme.colorScheme.onSurface,
                  )
                  androidx.compose.material3.Switch(
                    checked = biometricLockEnabled.value,
                    enabled = !dbEncEnabled,
                    onCheckedChange = {
                      com.google.ai.edge.gallery.security.AppLockManager.setBiometricLockEnabled(context, it)
                      biometricLockEnabled.value = it
                    },
                  )
                }
              }

              // ---- Allow screenshots toggle ----
              Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Text(
                  "Allow screenshots",
                  style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                )
                Text(
                  "Allow the app to appear in screenshots and screen recordings. Off by default for privacy.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val screenshotsEnabled = remember {
                  mutableStateOf(com.google.ai.edge.gallery.security.AppLockManager.isScreenshotsEnabled())
                }
                Row(
                  modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    if (screenshotsEnabled.value) "Enabled" else "Disabled",
                    style = MaterialTheme.typography.bodyMedium,
                  )
                  androidx.compose.material3.Switch(
                    checked = screenshotsEnabled.value,
                    onCheckedChange = {
                      com.google.ai.edge.gallery.security.AppLockManager.setScreenshotsEnabled(context, it)
                      screenshotsEnabled.value = it
                    },
                  )
                }
              }

              // ---- Biometric database encryption toggle ----
              BiometricEncryptionSection(context)

              // ---- Privacy notice ----
              Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Text(
                  "Privacy",
                  style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                )
                Text(
                  "Box is a privacy-focused fork of Google AI Edge Gallery. " +
                    "Chat history is encrypted with SQLCipher. " +
                    "Biometric authentication protects app access. " +
                    "Not affiliated with Google.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }

          SettingsAccordionSection(
            title = "Network & Tools",
            expanded = expandNetwork,
            onToggle = { expandNetwork = !expandNetwork },
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
              // ---- Offline mode toggle ----
              Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Text(
                  "Offline mode",
                  style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                )
                Text(
                  "Block all network requests. Models must be pre-downloaded.",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val offlineEnabled = com.google.ai.edge.gallery.security.OfflineMode.isEnabled.collectAsState()
                Row(
                  modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    if (offlineEnabled.value) "Enabled" else "Disabled",
                    style = MaterialTheme.typography.bodyMedium,
                  )
                  androidx.compose.material3.Switch(
                    checked = offlineEnabled.value,
                    onCheckedChange = {
                      com.google.ai.edge.gallery.security.OfflineMode.setEnabled(context, it)
                    },
                  )
                }
              }

              // ---- HF Token management ----
              Column(
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(
                  "HuggingFace access token",
                  style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                )
                val curHfToken = hfToken
                if (curHfToken != null && curHfToken.accessToken.isNotEmpty()) {
                  Text(
                    curHfToken.accessToken.substring(0, min(16, curHfToken.accessToken.length)) + "...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Text(
                    "Expires at: ${dateFormatter.format(Instant.ofEpochMilli(curHfToken.expiresAtMs))}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                } else {
                  Text(
                    "Not available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  Text(
                    "The token will be automatically retrieved when a gated model is downloaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                  OutlinedButton(
                    onClick = {
                      modelManagerViewModel.clearAccessToken()
                      hfToken = null
                    },
                    enabled = curHfToken != null,
                  ) {
                    Text("Clear")
                  }
                  val handleSaveToken = {
                    modelManagerViewModel.saveAccessToken(
                      accessToken = customHfToken,
                      refreshToken = "",
                      expiresAt = System.currentTimeMillis() + 1000L * 60 * 60 * 24 * 365 * 10,
                    )
                    hfToken = modelManagerViewModel.getTokenStatusAndData().data
                    focusManager.clearFocus()
                  }
                  BasicTextField(
                    value = customHfToken,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { handleSaveToken() }),
                    modifier =
                      Modifier.fillMaxWidth()
                        .padding(top = 4.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { isFocused = it.isFocused },
                    onValueChange = { customHfToken = it },
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                  ) { innerTextField ->
                    Box(
                      modifier =
                        Modifier.border(
                            width = if (isFocused) 2.dp else 1.dp,
                            color =
                              if (isFocused) MaterialTheme.colorScheme.primary
                              else MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                          )
                          .height(40.dp),
                      contentAlignment = Alignment.CenterStart,
                    ) {
                      Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                          if (customHfToken.isEmpty()) {
                            Text(
                              "Enter token manually",
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                              style = MaterialTheme.typography.bodySmall,
                            )
                          }
                          innerTextField()
                        }
                        if (customHfToken.isNotEmpty()) {
                          IconButton(modifier = Modifier.offset(x = 1.dp), onClick = handleSaveToken) {
                            Icon(
                              Icons.Rounded.CheckCircle,
                              contentDescription = stringResource(R.string.cd_done_icon),
                            )
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }

          SettingsAccordionSection(
            title = "Chat",
            expanded = expandChat,
            onToggle = { expandChat = !expandChat },
          ) {
            ChatPrefsSection(context)
          }

          SettingsAccordionSection(
            title = "Downloads & Updates",
            expanded = expandDownloads,
            onToggle = { expandDownloads = !expandDownloads },
          ) {
            DownloadsUpdatesSection(context)
          }

          SettingsAccordionSection(
            title = "GGUF Models",
            expanded = expandGguf,
            onToggle = { expandGguf = !expandGguf },
          ) {
            GgufSettingsSection(context)
          }

          SettingsAccordionSection(
            title = "Box Assist",
            expanded = expandBoxAssist,
            onToggle = { expandBoxAssist = !expandBoxAssist },
          ) {
            BoxAssistSettingsSection(context)
          }

          SettingsAccordionSection(
            title = "About",
            expanded = expandAbout,
            onToggle = { expandAbout = !expandAbout },
          ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Text(
                  "Third-party libraries",
                  style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                )
                OutlinedButton(onClick = { showLicenses = true }) {
                  Text("View licenses")
                }
              }
              Column(
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(
                  "Developed by Jegly",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  "Box fork version: ${BuildConfig.VERSION_NAME}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ClickableLink(
                  url = "https://github.com/jegly",
                  linkText = "github.com/jegly",
                  modifier = Modifier.padding(top = 2.dp),
                )
                ClickableLink(
                  url = "https://www.jegly.xyz",
                  linkText = "www.jegly.xyz",
                  modifier = Modifier.padding(top = 2.dp),
                )
              }
              Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Text(
                  "Privacy & Legal",
                  style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                )
                ClickableLink(
                  url = "https://ai.google.dev/gemma/terms",
                  linkText = stringResource(R.string.tos_dialog_title_gemma),
                  modifier = Modifier.padding(top = 4.dp),
                )
                ClickableLink(
                  url = "https://ai.google.dev/gemma/prohibited_use_policy",
                  linkText = stringResource(R.string.settings_dialog_gemma_prohibited_use_policy),
                  modifier = Modifier.padding(top = 8.dp),
                )
              }
            }
          }
        }

        // Button row.
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.End,
        ) {
          Button(onClick = { onDismissed() }) { Text("Close") }
        }
      }
    }
  }

  if (showTos) {
    AppTosDialog(onTosAccepted = { showTos = false }, viewingMode = true)
  }

  if (showLicenses) {
    LicensesDialog(onDismiss = { showLicenses = false })
  }
}

// ======== Custom Theme Picker ========

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomThemeSection(context: Context) {
  val currentTheme by ThemeSettings.customTheme
  val currentFlavor by ThemeSettings.catppuccinFlavor
  val currentCatAccent by ThemeSettings.catppuccinAccent
  val currentDracAccent by ThemeSettings.draculaAccent
  val currentPtyxis by ThemeSettings.ptyxisPalette
  val currentPtyxisExt by ThemeSettings.ptyxisPaletteExtended
  val currentMono by ThemeSettings.monochromeAccents

  Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
    Text(
      "Custom theme",
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
    )
    Text(
      "Override the base theme with a custom color palette.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(4.dp))

    // Theme type chips
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      CustomThemeType.entries.forEach { type ->
        FilterChip(
          selected = currentTheme == type,
          onClick = { ThemeSettings.setCustomTheme(context, type) },
          label = { Text(type.label, style = MaterialTheme.typography.labelMedium) },
        )
      }
    }

    // Catppuccin sub-pickers
    AnimatedVisibility(visible = currentTheme == CustomThemeType.CATPPUCCIN) {
      Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text("Flavor", style = MaterialTheme.typography.labelMedium)
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          CatppuccinFlavor.entries.forEach { flavor ->
            FilterChip(
              selected = currentFlavor == flavor,
              onClick = { ThemeSettings.setCatppuccinFlavor(context, flavor) },
              label = { Text(flavor.displayName, style = MaterialTheme.typography.labelSmall) },
            )
          }
        }
        Text("Accent", style = MaterialTheme.typography.labelMedium)
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          CatppuccinAccent.entries.forEach { accent ->
            FilterChip(
              selected = currentCatAccent == accent,
              onClick = { ThemeSettings.setCatppuccinAccent(context, accent) },
              label = { Text(accent.displayName, style = MaterialTheme.typography.labelSmall) },
            )
          }
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Monochrome accents", style = MaterialTheme.typography.bodySmall)
          androidx.compose.material3.Switch(
            checked = currentMono,
            onCheckedChange = { ThemeSettings.setMonochromeAccents(context, it) },
          )
        }
      }
    }

    // Dracula sub-pickers
    AnimatedVisibility(visible = currentTheme == CustomThemeType.DRACULA) {
      Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text("Accent", style = MaterialTheme.typography.labelMedium)
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          DraculaAccent.entries.forEach { accent ->
            FilterChip(
              selected = currentDracAccent == accent,
              onClick = { ThemeSettings.setDraculaAccent(context, accent) },
              label = { Text(accent.displayName, style = MaterialTheme.typography.labelSmall) },
            )
          }
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text("Monochrome accents", style = MaterialTheme.typography.bodySmall)
          androidx.compose.material3.Switch(
            checked = currentMono,
            onCheckedChange = { ThemeSettings.setMonochromeAccents(context, it) },
          )
        }
      }
    }

    // Ptyxis palette picker
    AnimatedVisibility(visible = currentTheme == CustomThemeType.PTYXIS) {
      Column(modifier = Modifier.padding(top = 8.dp)) {
        Text("Terminal palette", style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(4.dp))
        DropdownPicker(
          label = "Palette",
          options = PtyxisPalette.entries.map { it.displayName },
          selected = currentPtyxis.displayName,
          onSelect = { idx ->
            ThemeSettings.setPtyxisPalette(context, PtyxisPalette.entries[idx])
          },
        )
      }
    }

    // Ptyxis+ palette picker
    AnimatedVisibility(visible = currentTheme == CustomThemeType.PTYXIS_EXTENDED) {
      Column(modifier = Modifier.padding(top = 8.dp)) {
        Text("Terminal palette (extended)", style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(4.dp))
        DropdownPicker(
          label = "Palette",
          options = PtyxisExtendedPalette.entries.map { it.displayName },
          selected = currentPtyxisExt.displayName,
          onSelect = { idx ->
            ThemeSettings.setPtyxisExtendedPalette(context, PtyxisExtendedPalette.entries[idx])
          },
        )
      }
    }
  }
}

// ======== Font Picker ========

@Composable
private fun FontPickerSection(context: Context) {
  val currentAppFont by ThemeSettings.appFont
  val currentChatFont by ThemeSettings.chatFont
  val currentQuickActionsFont by ThemeSettings.quickActionsFont
  val currentCategoryTabsFont by ThemeSettings.categoryTabsFont
  val currentScale by ThemeSettings.fontScale

  Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
    Text(
      "Fonts",
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
    )
    Text(
      "Customize fonts for different UI areas.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(6.dp))

    val fontOptions = AppFont.entries.map { it.displayName }
    val fontOptionsWithDefault = listOf("Same as app") + fontOptions

    // App font
    DropdownPicker(
      label = "App font",
      options = fontOptions,
      selected = currentAppFont.displayName,
      onSelect = { idx -> ThemeSettings.setAppFont(context, AppFont.entries[idx]) },
    )
    Spacer(modifier = Modifier.height(6.dp))

    // Chat font
    DropdownPicker(
      label = "Chat font",
      options = fontOptionsWithDefault,
      selected = currentChatFont?.displayName ?: "Same as app",
      onSelect = { idx ->
        ThemeSettings.setChatFont(context, if (idx == 0) null else AppFont.entries[idx - 1])
      },
    )
    Spacer(modifier = Modifier.height(6.dp))

    // Quick actions font
    DropdownPicker(
      label = "Quick actions font",
      options = fontOptionsWithDefault,
      selected = currentQuickActionsFont?.displayName ?: "Same as app",
      onSelect = { idx ->
        ThemeSettings.setQuickActionsFont(context, if (idx == 0) null else AppFont.entries[idx - 1])
      },
    )
    Spacer(modifier = Modifier.height(6.dp))

    // Category tabs font
    DropdownPicker(
      label = "Category tabs font",
      options = fontOptionsWithDefault,
      selected = currentCategoryTabsFont?.displayName ?: "Same as app",
      onSelect = { idx ->
        ThemeSettings.setCategoryTabsFont(context, if (idx == 0) null else AppFont.entries[idx - 1])
      },
    )
    Spacer(modifier = Modifier.height(8.dp))

    // Text size slider
    var sliderValue by remember { mutableFloatStateOf(currentScale) }
    Text(
      "Text size: ${((sliderValue * 100).roundToInt())}%",
      style = MaterialTheme.typography.bodySmall,
    )
    Slider(
      value = sliderValue,
      onValueChange = {
        sliderValue = it
        ThemeSettings.setFontScale(context, it, persist = false)
      },
      onValueChangeFinished = {
        ThemeSettings.setFontScale(context, sliderValue, persist = true)
      },
      valueRange = 0.8f..1.4f,
      steps = 11,
    )
  }
}

// ======== Language Switcher ========

@Composable
private fun LanguageSwitcherSection(context: Context) {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

  val localeManager = context.getSystemService(LocaleManager::class.java) ?: return
  val currentLocales = localeManager.applicationLocales
  val currentTag = if (currentLocales.isEmpty) "" else currentLocales.get(0)?.toLanguageTag() ?: ""
  val selectedIdx = APP_LANGUAGES.indexOfFirst { it.tag == currentTag }.coerceAtLeast(0)

  Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
    Text(
      "Language",
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
    )
    Text(
      "Change the app display language. Requires restart.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(4.dp))
    DropdownPicker(
      label = "Language",
      options = APP_LANGUAGES.map { it.displayName },
      selected = APP_LANGUAGES[selectedIdx].displayName,
      onSelect = { idx ->
        val tag = APP_LANGUAGES[idx].tag
        localeManager.applicationLocales =
          if (tag.isEmpty()) LocaleList.getEmptyLocaleList()
          else LocaleList.forLanguageTags(tag)
      },
    )
  }
}

// ======== Accordion & New v3.5.7 Sections ========

@Composable
private fun SettingsAccordionSection(
  title: String,
  expanded: Boolean,
  onToggle: () -> Unit,
  content: @Composable () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .border(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
      )
      .padding(horizontal = 12.dp, vertical = 10.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().clickable { onToggle() },
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
      )
      Icon(
        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
        contentDescription = if (expanded) "Collapse" else "Expand",
      )
    }
    AnimatedVisibility(visible = expanded) {
      Column(modifier = Modifier.padding(top = 10.dp)) {
        content()
      }
    }
  }
}

@Composable
private fun ChatPrefsSection(context: Context) {
  val prefs = remember { context.getSharedPreferences(ThemeSettings.PREFS_NAME, Context.MODE_PRIVATE) }
  var showTps by remember { mutableStateOf(prefs.getBoolean("chat_show_tps", false)) }
  var restoreMemory by remember { mutableStateOf(prefs.getBoolean("chat_restore_memory", true)) }

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Show tokens per second", style = MaterialTheme.typography.bodySmall)
        Text(
          "Display generation speed below each response.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      androidx.compose.material3.Switch(
        checked = showTps,
        onCheckedChange = {
          showTps = it
          prefs.edit().putBoolean("chat_show_tps", it).apply()
        },
      )
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Restore chat memory", style = MaterialTheme.typography.bodySmall)
        Text(
          "Re-feed recent conversation context when reopening a saved chat.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      androidx.compose.material3.Switch(
        checked = restoreMemory,
        onCheckedChange = {
          restoreMemory = it
          prefs.edit().putBoolean("chat_restore_memory", it).apply()
        },
      )
    }
  }
}

@Composable
private fun DownloadsUpdatesSection(context: Context) {
  val prefs = remember { context.getSharedPreferences(ThemeSettings.PREFS_NAME, Context.MODE_PRIVATE) }
  var wifiOnly by remember { mutableStateOf(prefs.getBoolean("downloads_wifi_only", false)) }
  var autoUpdate by remember { mutableStateOf(prefs.getBoolean("auto_update_check", true)) }

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Download models on Wi-Fi only", style = MaterialTheme.typography.bodySmall)
        Text(
          "Wait for an unmetered network instead of using mobile data.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      androidx.compose.material3.Switch(
        checked = wifiOnly,
        onCheckedChange = {
          wifiOnly = it
          prefs.edit().putBoolean("downloads_wifi_only", it).apply()
        },
      )
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Check for updates automatically", style = MaterialTheme.typography.bodySmall)
        Text(
          "Check GitHub for a newer Box release when the app opens.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      androidx.compose.material3.Switch(
        checked = autoUpdate,
        onCheckedChange = {
          autoUpdate = it
          prefs.edit().putBoolean("auto_update_check", it).apply()
        },
      )
    }
  }
}

@Composable
private fun BoxAssistSettingsSection(context: Context) {
  val prefs = remember { context.getSharedPreferences(ThemeSettings.PREFS_NAME, Context.MODE_PRIVATE) }
  var speechRate by remember { mutableFloatStateOf(prefs.getFloat("boxassist_speech_rate", 0.0f)) }
  var hazardsOnly by remember { mutableStateOf(prefs.getBoolean("boxassist_hazards_only", false)) }
  var haptics by remember { mutableStateOf(prefs.getBoolean("boxassist_haptics", true)) }
  var threshold by remember { mutableFloatStateOf(prefs.getFloat("boxassist_score_threshold", 0.3f)) }
  var selectedVoice by remember { mutableStateOf(prefs.getString("tts_voice_name", null) ?: "") }
  var availableVoices by remember { mutableStateOf<List<String>>(emptyList()) }

  androidx.compose.runtime.DisposableEffect(context) {
    var ttsInstance: android.speech.tts.TextToSpeech? = null
    ttsInstance = android.speech.tts.TextToSpeech(context) { status ->
      if (status == android.speech.tts.TextToSpeech.SUCCESS) {
        val names = ttsInstance?.voices
          ?.filter { !it.isNetworkConnectionRequired }
          ?.map { it.name }
          ?.sorted()
          ?: emptyList()
        availableVoices = names
      }
    }
    onDispose {
      runCatching { ttsInstance.shutdown() }
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(
      "Spoken camera assistance for blind and low-vision users. Applied next time you open Box Assist.",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (availableVoices.isNotEmpty()) {
      val voiceOptions = listOf("System default") + availableVoices
      DropdownPicker(
        label = "Voice",
        options = voiceOptions,
        selected = selectedVoice.ifBlank { "System default" },
        onSelect = { idx ->
          if (idx == 0) {
            selectedVoice = ""
            prefs.edit().remove("tts_voice_name").apply()
          } else {
            selectedVoice = availableVoices[idx - 1]
            prefs.edit().putString("tts_voice_name", selectedVoice).apply()
          }
        },
      )
    }
    Column {
      Text(
        if (speechRate <= 0.01f) "Speech rate: System" else "Speech rate: ${String.format("%.2f", speechRate)}×",
        style = MaterialTheme.typography.bodySmall,
      )
      Slider(
        value = speechRate,
        onValueChange = {
          speechRate = it
          prefs.edit().putFloat("boxassist_speech_rate", it).apply()
        },
        valueRange = 0.0f..2.0f,
      )
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Announce hazards only", style = MaterialTheme.typography.bodySmall)
        Text(
          "Live mode calls out only people, vehicles and obstacles.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      androidx.compose.material3.Switch(
        checked = hazardsOnly,
        onCheckedChange = {
          hazardsOnly = it
          prefs.edit().putBoolean("boxassist_hazards_only", it).apply()
        },
      )
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Haptic feedback", style = MaterialTheme.typography.bodySmall)
        Text(
          "Vibration cues for listening, thinking and hazards.",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      androidx.compose.material3.Switch(
        checked = haptics,
        onCheckedChange = {
          haptics = it
          prefs.edit().putBoolean("boxassist_haptics", it).apply()
        },
      )
    }
    Column {
      Text(
        "Live callout threshold: ${(threshold * 100).roundToInt()}%",
        style = MaterialTheme.typography.bodySmall,
      )
      Slider(
        value = threshold,
        onValueChange = {
          threshold = it
          prefs.edit().putFloat("boxassist_score_threshold", it).apply()
        },
        valueRange = 0.15f..0.7f,
      )
    }
  }
}

// ======== GGUF Settings ========

@Composable
private fun GgufSettingsSection(context: Context) {
  val prefs = remember { context.getSharedPreferences(ThemeSettings.PREFS_NAME, Context.MODE_PRIVATE) }

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Text(
      "Engine options for imported GGUF models (llama.cpp). Applied the next time a GGUF model loads.",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    GGUF_SETTINGS.forEach { setting ->
      when (setting.type) {
        GgufSettingType.INT_DROPDOWN -> {
          val currentVal = prefs.getInt(setting.key, setting.defaultInt)
          val options = setting.options!!
          val selectedLabel = options.firstOrNull { it.second == currentVal }?.first ?: currentVal.toString()
          Column {
            Text(setting.label, style = MaterialTheme.typography.bodySmall)
            Text(
              setting.description,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(2.dp))
            DropdownPicker(
              label = setting.label,
              options = options.map { it.first },
              selected = selectedLabel,
              onSelect = { idx ->
                prefs.edit().putInt(setting.key, options[idx].second).apply()
              },
            )
          }
        }
        GgufSettingType.SWITCH -> {
          var checked by remember(setting.key) {
            mutableStateOf(prefs.getBoolean(setting.key, setting.defaultBool))
          }
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(setting.label, style = MaterialTheme.typography.bodySmall)
              Text(
                setting.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Spacer(modifier = Modifier.width(8.dp))
            androidx.compose.material3.Switch(
              checked = checked,
              onCheckedChange = {
                checked = it
                prefs.edit().putBoolean(setting.key, it).apply()
              },
            )
          }
        }
      }
    }
  }
}

// ======== Shared Dropdown Picker ========

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownPicker(
  label: String,
  options: List<String>,
  selected: String,
  onSelect: (Int) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = !expanded },
  ) {
    OutlinedTextField(
      value = selected,
      onValueChange = {},
      readOnly = true,
      singleLine = true,
      label = { Text(label, style = MaterialTheme.typography.labelSmall) },
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
      textStyle = MaterialTheme.typography.bodySmall,
    )
    ExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      options.forEachIndexed { index, text ->
        DropdownMenuItem(
          text = { Text(text, style = MaterialTheme.typography.bodySmall) },
          onClick = {
            onSelect(index)
            expanded = false
          },
        )
      }
    }
  }
}

// ======== Biometric Encryption Section ========

@Composable
private fun BiometricEncryptionSection(context: Context) {
    val activity = remember(context) {
        var ctx: android.content.Context = context
        while (ctx is android.content.ContextWrapper && ctx !is FragmentActivity) {
            ctx = ctx.baseContext
        }
        ctx as? FragmentActivity
    }
    var isEnabled by remember { mutableStateOf(BiometricEncryptionManager.isEnabled(context)) }
    var hardwareLevel by remember {
        mutableStateOf(if (isEnabled) BiometricEncryptionManager.getHardwareLevel() else "")
    }
    var showEnableDialog by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(
            "Biometric database encryption",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
        )
        Text(
            "Protect the database key with biometrics. If your biometrics change, you may lose access to chat history.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isEnabled && hardwareLevel.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (hardwareLevel == "StrongBox") Icons.Rounded.Security else Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 4.dp),
                )
                Text(
                    "Protected by $hardwareLevel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (statusText.isNotEmpty()) {
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (isEnabled) "Enabled" else "Disabled",
                style = MaterialTheme.typography.bodyMedium,
            )
            androidx.compose.material3.Switch(
                checked = isEnabled,
                onCheckedChange = {
                    statusText = ""
                    if (it) showEnableDialog = true else showDisableDialog = true
                },
            )
        }
    }

    if (showEnableDialog) {
        AlertDialog(
            onDismissRequest = { showEnableDialog = false },
            title = { Text("Enable biometric encryption?") },
            text = {
                Text(
                    "Your database key will be encrypted with your biometrics. " +
                    "If you change or remove your biometrics, you will lose access to your chat history.\n\n" +
                    "Biometric lock will be disabled automatically (database encryption already protects app access).\n\n" +
                    "Export important chats before enabling."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showEnableDialog = false
                    if (activity == null) return@TextButton
                    BiometricEncryptionManager.promptEncrypt(
                        activity = activity,
                        onSuccess = { cipher ->
                            val plain = SecurityUtils.getOrCreatePlainPassphrase(context)
                            BiometricEncryptionManager.storeEncryptedPassphrase(context, cipher, plain)
                            SecurityUtils.clearPlainPassphrase(context)
                            PassphraseHolder.set(plain)
                            isEnabled = true
                            hardwareLevel = BiometricEncryptionManager.getHardwareLevel()
                            com.google.ai.edge.gallery.security.AppLockManager.setBiometricLockEnabled(context, false)
                        },
                        onFailure = { _, _ -> statusText = "Authentication failed" },
                        onError = { _, msg -> statusText = msg.toString() },
                    )
                }) {
                    Text("Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEnableDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showDisableDialog) {
        AlertDialog(
            onDismissRequest = { showDisableDialog = false },
            title = { Text("Disable biometric encryption?") },
            text = { Text("Authenticate to confirm. The database key will be stored without biometric protection.") },
            confirmButton = {
                TextButton(onClick = {
                    showDisableDialog = false
                    if (activity == null) return@TextButton
                    BiometricEncryptionManager.promptDecrypt(
                        activity = activity,
                        context = context,
                        onSuccess = { cipher ->
                            val plain = BiometricEncryptionManager.decryptPassphrase(context, cipher)
                            SecurityUtils.storePlainPassphrase(context, plain)
                            BiometricEncryptionManager.disable(context)
                            PassphraseHolder.clear()
                            isEnabled = false
                            hardwareLevel = ""
                        },
                        onFailure = { _, _ -> statusText = "Authentication failed" },
                        onError = { _, msg -> statusText = msg.toString() },
                    )
                }) {
                    Text("Disable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableDialog = false }) { Text("Cancel") }
            },
        )
    }
}

private fun themeLabel(theme: Theme): String {
  return when (theme) {
    Theme.THEME_AUTO -> "Auto"
    Theme.THEME_LIGHT -> "Light"
    Theme.THEME_DARK -> "Dark"
    else -> "Unknown"
  }
}
