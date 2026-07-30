// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
  id("org.jetbrains.intellij.platform")
  id("io.github.nerzhulart.webview.frontend")
}

group = "io.github.nerzhulart.webview.demo"
version = providers.gradleProperty("pluginVersion").get()

base {
  archivesName.set("webview-demo")
}

repositories {
  mavenCentral()
  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  intellijPlatform {
    intellijIdea(providers.gradleProperty("platformVersion")) {
      useInstaller = false
    }
    localPlugin(project(":"))
    localPlugin(project(":markdown-preview"))
    bundledPlugin("org.intellij.plugins.markdown")
  }
}

extensions.configure<KotlinJvmProjectExtension> {
  jvmToolchain(25)
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_25)
    freeCompilerArgs.addAll("-jvm-default=no-compatibility", "-progressive")
  }
  sourceSets.named("main") {
    kotlin.srcDir("src")
  }
}

sourceSets {
  named("main") {
    resources.srcDir("resources")
  }
}

intellijPlatform {
  pluginConfiguration {
    version = providers.gradleProperty("pluginVersion")
    ideaVersion {
      sinceBuild = "262.8665"
    }
  }
}

tasks {
  buildPlugin {
    archiveFileName.set("webview-demo-${providers.gradleProperty("pluginVersion").get()}.zip")
  }
}
