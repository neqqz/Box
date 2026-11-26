package com.google.ai.edge.gallery.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.ai.edge.gallery.ui.common.ClickableLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ThirdPartyLicenseType(
  val displayName: String,
  val assetPath: String?,
  val url: String?,
) {
  APACHE_2("Apache License 2.0", "licenses/Apache-2.0.txt", null),
  MIT("MIT License", "licenses/MIT.txt", null),
  BSD_3("BSD 3-Clause License", "licenses/BSD-3-Clause.txt", null),
  OFL_1_1("SIL Open Font License 1.1", "licenses/OFL-1.1.txt", null),
  GPL_3("GNU GPL v3.0 or later", "licenses/GPL-3.0-or-later.txt", null),
  MLKIT_TOS("ML Kit Terms of Service", null, "https://developers.google.com/ml-kit/terms"),
  ANDROID_SDK("Android Software Development Kit License", null, "https://developer.android.com/studio/terms"),
}

data class ThirdPartyLibrary(
  val name: String,
  val license: ThirdPartyLicenseType,
  val copyright: String,
)

private val THIRD_PARTY_LIBRARIES = listOf(
  ThirdPartyLibrary("llama.cpp", ThirdPartyLicenseType.MIT, "Copyright (c) 2023-2026 The ggml authors"),
  ThirdPartyLibrary("whisper.cpp", ThirdPartyLicenseType.MIT, "Copyright (c) 2023-2026 The ggml authors"),
  ThirdPartyLibrary("ggml", ThirdPartyLicenseType.MIT, "Copyright (c) 2023-2026 The ggml authors"),
  ThirdPartyLibrary("stable-diffusion.cpp", ThirdPartyLicenseType.MIT, "Copyright (c) 2023 leejet"),
  ThirdPartyLibrary("Vulkan-Headers", ThirdPartyLicenseType.APACHE_2, "Copyright 2015-2023 The Khronos Group Inc."),
  ThirdPartyLibrary("SPIRV-Headers", ThirdPartyLicenseType.MIT, "Copyright (c) 2015-2024 The Khronos Group Inc."),
  ThirdPartyLibrary("LiteRT", ThirdPartyLicenseType.APACHE_2, "Copyright Google LLC"),
  ThirdPartyLibrary("LiteRT-LM", ThirdPartyLicenseType.APACHE_2, "Copyright Google LLC"),
  ThirdPartyLibrary("iris-3b Turbo engine (vendored in ui/iris)", ThirdPartyLicenseType.APACHE_2, "Copyright 2026 Speridlabs and the iris-3b-Turbo-LiteRT authors"),
  ThirdPartyLibrary("U²-Net background-removal model (bundled, litert-community/U-2-Net)", ThirdPartyLicenseType.APACHE_2, "Copyright (c) 2020 Xuebin Qin"),
  ThirdPartyLibrary("sherpa-onnx", ThirdPartyLicenseType.APACHE_2, "Copyright The k2-fsa / sherpa-onnx authors"),
  ThirdPartyLibrary("eSpeak NG (espeak-ng-data)", ThirdPartyLicenseType.GPL_3, "Copyright (C) 2005-2014 Jonathan Duddington; (C) 2015-2017 Reece H. Dunn and contributors"),
  ThirdPartyLibrary("ML Kit (GenAI, vision and text APIs)", ThirdPartyLicenseType.MLKIT_TOS, "Copyright Google LLC"),
  ThirdPartyLibrary("Google Play services client libraries", ThirdPartyLicenseType.ANDROID_SDK, "Copyright Google LLC"),
  ThirdPartyLibrary("AndroidX Core, Activity, Lifecycle, Splashscreen", ThirdPartyLicenseType.APACHE_2, "Copyright The Android Open Source Project"),
  ThirdPartyLibrary("Jetpack Compose (UI, Material 3, Material Icons, Adaptive)", ThirdPartyLicenseType.APACHE_2, "Copyright The Android Open Source Project"),
  ThirdPartyLibrary("Navigation Compose", ThirdPartyLicenseType.APACHE_2, "Copyright The Android Open Source Project"),
  ThirdPartyLibrary("Room", ThirdPartyLicenseType.APACHE_2, "Copyright The Android Open Source Project"),
  ThirdPartyLibrary("SQLite (androidx.sqlite)", ThirdPartyLicenseType.APACHE_2, "Copyright The Android Open Source Project"),
  ThirdPartyLibrary("WorkManager", ThirdPartyLicenseType.APACHE_2, "Copyright The Android Open Source Project"),
  ThirdPartyLibrary("DataStore", ThirdPartyLicenseType.APACHE_2, "Copyright The Android Open Source Project"),
  ThirdPartyLibrary("CameraX", ThirdPartyLicenseType.APACHE_2, "Copyright The Android Open Source Project"),
  ThirdPartyLibrary("Biometric", ThirdPartyLicenseType.APACHE_2, "Copyright The Android Open Source Project"),
  ThirdPartyLibrary("Security Crypto", ThirdPartyLicenseType.APACHE_2, "Copyright The Android Open Source Project"),
  ThirdPartyLibrary("WebKit", ThirdPartyLicenseType.APACHE_2, "Copyright The Android Open Source Project"),
  ThirdPartyLibrary("ExifInterface", ThirdPartyLicenseType.APACHE_2, "Copyright The Android Open Source Project"),
  ThirdPartyLibrary("DocumentFile", ThirdPartyLicenseType.APACHE_2, "Copyright The Android Open Source Project"),
  ThirdPartyLibrary("Hilt Navigation Compose", ThirdPartyLicenseType.APACHE_2, "Copyright The Android Open Source Project"),
  ThirdPartyLibrary("Kotlin Standard Library and Reflection", ThirdPartyLicenseType.APACHE_2, "Copyright JetBrains s.r.o. and contributors"),
  ThirdPartyLibrary("kotlinx.coroutines", ThirdPartyLicenseType.APACHE_2, "Copyright JetBrains s.r.o. and contributors"),
  ThirdPartyLibrary("kotlinx.serialization", ThirdPartyLicenseType.APACHE_2, "Copyright JetBrains s.r.o. and contributors"),
  ThirdPartyLibrary("Ktor", ThirdPartyLicenseType.APACHE_2, "Copyright JetBrains s.r.o. and contributors"),
  ThirdPartyLibrary("Dagger / Hilt", ThirdPartyLicenseType.APACHE_2, "Copyright The Dagger Authors"),
  ThirdPartyLibrary("Guava", ThirdPartyLicenseType.APACHE_2, "Copyright The Guava Authors"),
  ThirdPartyLibrary("Gson", ThirdPartyLicenseType.APACHE_2, "Copyright Google Inc."),
  ThirdPartyLibrary("Protocol Buffers (protobuf-javalite)", ThirdPartyLicenseType.BSD_3, "Copyright Google LLC"),
  ThirdPartyLibrary("Moshi", ThirdPartyLicenseType.APACHE_2, "Copyright Square, Inc."),
  ThirdPartyLibrary("SQLCipher for Android", ThirdPartyLicenseType.BSD_3, "Copyright (c) Zetetic LLC"),
  ThirdPartyLibrary("Apache Commons Compress", ThirdPartyLicenseType.APACHE_2, "Copyright The Apache Software Foundation"),
  ThirdPartyLibrary("PdfBox-Android", ThirdPartyLicenseType.APACHE_2, "Copyright The Apache Software Foundation and Tom Roush"),
  ThirdPartyLibrary("AppAuth for Android", ThirdPartyLicenseType.APACHE_2, "Copyright The AppAuth for Android Authors"),
  ThirdPartyLibrary("Compose Richtext", ThirdPartyLicenseType.APACHE_2, "Copyright Halil Ozercan and contributors"),
  ThirdPartyLibrary("KotlinTeX (katex-core)", ThirdPartyLicenseType.MIT, "Copyright The KotlinTeX authors"),
  ThirdPartyLibrary("Model Context Protocol Kotlin SDK", ThirdPartyLicenseType.MIT, "Copyright The MCP Kotlin SDK authors"),
  ThirdPartyLibrary("Nunito", ThirdPartyLicenseType.OFL_1_1, "Copyright 2014 The Nunito Project Authors"),
  ThirdPartyLibrary("Press Start 2P", ThirdPartyLicenseType.OFL_1_1, "Copyright 2012 The Press Start 2P Project Authors"),
  ThirdPartyLibrary("DotGothic16", ThirdPartyLicenseType.OFL_1_1, "Copyright 2020 The DotGothic16 Project Authors"),
  ThirdPartyLibrary("IBM Plex Mono / IBM Plex Serif", ThirdPartyLicenseType.OFL_1_1, "Copyright 2017 IBM Corp."),
  ThirdPartyLibrary("Alice", ThirdPartyLicenseType.OFL_1_1, "Copyright 2011 The Alice Project Authors"),
  ThirdPartyLibrary("Cormorant Garamond", ThirdPartyLicenseType.OFL_1_1, "Copyright 2015 The Cormorant Project Authors"),
  ThirdPartyLibrary("Instrument Serif", ThirdPartyLicenseType.OFL_1_1, "Copyright 2022 The Instrument Serif Project Authors"),
  ThirdPartyLibrary("Playfair Display", ThirdPartyLicenseType.OFL_1_1, "Copyright 2017 The Playfair Display Project Authors"),
  ThirdPartyLibrary("Quicksand", ThirdPartyLicenseType.OFL_1_1, "Copyright 2019 The Quicksand Project Authors"),
  ThirdPartyLibrary("Space Grotesk", ThirdPartyLicenseType.OFL_1_1, "Copyright 2020 The Space Grotesk Project Authors"),
  ThirdPartyLibrary("Turret Road", ThirdPartyLicenseType.OFL_1_1, "Copyright 2018 The Turret Road Project Authors"),
  ThirdPartyLibrary("Viaoda Libre", ThirdPartyLicenseType.OFL_1_1, "Copyright 2020 The Viaodalibre Project Authors"),
)

@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Scaffold(
      modifier = Modifier.fillMaxSize(),
    ) { innerPadding ->
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding)
          .padding(horizontal = 16.dp),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "Third-party libraries",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
          )
          IconButton(onClick = onDismiss) {
            Icon(Icons.Rounded.Close, contentDescription = "Close")
          }
        }

        Text(
          text = "Box is built on these open-source projects. Some of them include further " +
            "components under their own licenses. AI models are not covered here — each one " +
            "is under the license set by its publisher.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()

        LazyColumn(
          modifier = Modifier.fillMaxSize(),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          item { Spacer(modifier = Modifier.height(4.dp)) }
          items(THIRD_PARTY_LIBRARIES, key = { it.name }) { entry ->
            LicenseEntryCard(entry)
          }
          item { Spacer(modifier = Modifier.height(16.dp)) }
        }
      }
    }
  }
}

@Composable
private fun LicenseEntryCard(entry: ThirdPartyLibrary) {
  var expanded by rememberSaveable(entry.name) { mutableStateOf(false) }

  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = entry.name,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = entry.copyright,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        text = entry.license.displayName,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
      )
      TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Hide license" else "Show license")
      }
      if (expanded) {
        val assetPath = entry.license.assetPath
        val url = entry.license.url
        if (assetPath != null) {
          LicenseAssetText(assetPath)
        } else if (url != null) {
          ClickableLink(url = url, linkText = url)
        }
      }
    }
  }
}

@Composable
private fun LicenseAssetText(assetPath: String) {
  val context = LocalContext.current
  var text by remember(assetPath) { mutableStateOf("") }

  LaunchedEffect(assetPath) {
    text = withContext(Dispatchers.IO) {
      try {
        context.assets.open(assetPath).bufferedReader().use { it.readText() }
      } catch (_: Exception) {
        "License text unavailable."
      }
    }
  }

  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(top = 4.dp),
  )
}
