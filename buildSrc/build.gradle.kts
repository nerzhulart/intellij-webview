// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

plugins {
  `kotlin-dsl`
}

java {
  sourceCompatibility = JavaVersion.VERSION_24
  targetCompatibility = JavaVersion.VERSION_24
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_24)
  }
}

repositories {
  gradlePluginPortal()
}

gradlePlugin {
  plugins {
    create("webViewFrontend") {
      id = "io.github.nerzhulart.webview.frontend"
      implementationClass = "io.github.nerzhulart.webview.gradle.WebViewFrontendPlugin"
    }
  }
}
