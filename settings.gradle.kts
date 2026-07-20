// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
pluginManagement {
  repositories {
    gradlePluginPortal()
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
  }
}

rootProject.name = "webview"

include(":jcef")
include(":demo")
include(":markdown-preview")
