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

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.protobuf)
  alias(libs.plugins.hilt.application)
  alias(libs.plugins.ksp)
  kotlin("kapt")
}

fun getProps(propName: String): String {
  val envProps = System.getenv("LOCAL_PROPERTIES")
  if (envProps != null) {
    val props = Properties()
    props.load(ByteArrayInputStream(Base64.getDecoder().decode(envProps)))
    props.getProperty(propName)?.let { return it }
  }
  val file = rootProject.file("local.properties")
  if (file.exists()) {
    val props = Properties()
    file.inputStream().use { props.load(it) }
    props.getProperty(propName)?.let { return it }
  }
  return ""
}

android {
  namespace = "com.google.ai.edge.gallery"
  compileSdk {
    version = release(37) { minorApiLevel = 2 }
  }

  defaultConfig {
    applicationId = "com.box.gallery"
    minSdk = 33
    targetSdk = 37
    versionCode = 51
    versionName = "3.5.7"

    // Needed for HuggingFace auth workflows.
    // Use the scheme of the "Redirect URLs" in HuggingFace app.
    manifestPlaceholders["appAuthRedirectScheme"] =
        "REPLACE_WITH_YOUR_REDIRECT_SCHEME_IN_HUGGINGFACE_APP"
    // No applicationName placeholder needed - AndroidManifest.xml directly references the class

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    ndk {
      abiFilters += listOf("arm64-v8a")
    }
  }

  signingConfigs {
    create("release") {
      storeFile = file("release.keystore")
      storePassword = getProps("KEYSTORE_PASS")
      keyAlias = getProps("ALIAS_NAME")
      keyPassword = getProps("ALIAS_PASS")
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig =
          if (getProps("KEYSTORE_PASS").isNotEmpty()) signingConfigs.getByName("release")
          else signingConfigs.getByName("debug")
    }
  }
  packaging {
    jniLibs {
      useLegacyPackaging = true
      pickFirsts += "lib/**/libLiteRt.so"
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlin.reflect)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.webkit)
  implementation(libs.litertlm)
  implementation(libs.litert)
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.camerax.video)
  implementation(libs.openid.appauth)
  implementation(libs.androidx.splashscreen)
  implementation(libs.protobuf.javalite)
  implementation(libs.hilt.android)
  implementation(libs.hilt.navigation.compose)
  implementation(libs.androidx.exifinterface)
  implementation(libs.moshi.kotlin)

  // Box: Biometric authentication (StrongBox)
  implementation(libs.androidx.biometric)

  // Box: Encrypted Room database for chat persistence
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  ksp(libs.androidx.room.compiler)
  implementation(libs.sqlcipher.android)
  implementation(libs.sqlite)

  // Box: llama.cpp native inference module for GGUF models
  implementation(project(":smollm"))

  // Box: stable-diffusion.cpp native inference for image generation
  implementation(project(":stablediffusion"))
  implementation(project(":whisper"))

  // Box: Material 3 adaptive navigation
  implementation(libs.androidx.material3.adaptive.navigation.suite)
  implementation(libs.androidx.material3.window.size)

  // Box: ML Kit GenAI
  implementation(libs.mlkit.genai.prompt)
  implementation(libs.mlkit.genai.image.description)
  implementation(libs.mlkit.genai.proofreading)
  implementation(libs.mlkit.genai.rewriting)
  implementation(libs.mlkit.genai.summarization)
  implementation(libs.mlkit.genai.speech)

  // Box: ML Kit Vision
  implementation(libs.mlkit.barcode)
  implementation(libs.mlkit.face.detection)
  implementation(libs.mlkit.face.mesh)
  implementation(libs.mlkit.image.labeling)
  implementation(libs.mlkit.pose.detection)
  implementation(libs.mlkit.text.recognition)

  // Box: Networking, MCP, KaTeX
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.android)
  implementation(libs.ktor.client.content.negotiation)
  implementation(libs.ktor.serialization.json)
  implementation(libs.mcp.kotlin.sdk)
  implementation(libs.katex.core)

  // Box: PDF, compression, image loading, documents
  implementation(libs.pdfbox.android)
  implementation(libs.commons.compress)
  implementation(libs.coil.compose)
  implementation(libs.androidx.documentfile)

  kapt(libs.hilt.android.compiler)
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.hilt.android.testing)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
  ksp(libs.moshi.kotlin.codegen)
}

protobuf {
  protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
  generateProtoTasks { all().forEach { it.plugins { create("java") { option("lite") } } } }
}
