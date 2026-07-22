// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
  kotlin("jvm") version "2.4.10"
  kotlin("plugin.serialization") version "2.4.10"
  id("org.jetbrains.intellij.platform") version "2.18.1"
  id("org.jetbrains.intellij.platform.module") version "2.18.1" apply false
}

group = "com.intellij.platform.ui.webview"
version = providers.gradleProperty("pluginVersion").get()

base {
  archivesName.set("webview")
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
    pluginModule(project(":jcef"))
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
  prepareSandbox {
    from("lib/webview-native") {
      into(pluginName.map { "$it/lib/webview-native" })
    }
  }

  named<RunIdeTask>("runIde") {
    val allPluginsSandbox = project(":demo").tasks.named<PrepareSandboxTask>("prepareSandbox")
    sandboxDirectory.set(allPluginsSandbox.flatMap { it.sandboxDirectory })
    sandboxConfigDirectory.set(allPluginsSandbox.flatMap { it.sandboxConfigDirectory })
    sandboxPluginsDirectory.set(allPluginsSandbox.flatMap { it.sandboxPluginsDirectory })
    sandboxSystemDirectory.set(allPluginsSandbox.flatMap { it.sandboxSystemDirectory })
    sandboxLogDirectory.set(allPluginsSandbox.flatMap { it.sandboxLogDirectory })
    testSandbox.set(allPluginsSandbox.flatMap { it.testSandbox })
    dependsOn(allPluginsSandbox)
  }
}
