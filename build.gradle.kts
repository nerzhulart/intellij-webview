// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.PublishPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
  kotlin("jvm") version "2.4.10"
  kotlin("plugin.serialization") version "2.4.10"
  id("org.jetbrains.intellij.platform") version "2.18.1"
  id("org.jetbrains.intellij.platform.module") version "2.18.1" apply false
  id("io.github.nerzhulart.webview.frontend")
}

group = "io.github.nerzhulart.webview"
version = providers.gradleProperty("pluginVersion").get()

allprojects {
  tasks.withType<Test>().configureEach {
    outputs.doNotCacheIf("Test results must not be restored from the build cache") { true }
  }
}

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
  testImplementation(platform("org.junit:junit-bom:5.13.4"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("junit:junit:4.13.2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")

  intellijPlatform {
    intellijIdea(providers.gradleProperty("platformVersion")) {
      useInstaller = false
    }
    pluginModule(project(":jcef"))
    testFramework(TestFrameworkType.Platform)
    testFramework(TestFrameworkType.JUnit5)
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
  sourceSets.named("test") {
    kotlin.srcDir("tests/testSrc")
  }
}

sourceSets {
  named("main") {
    resources.srcDir("resources")
  }
  named("test") {
    resources.srcDir("tests/testResources")
  }
}

intellijPlatform {
  pluginConfiguration {
    version = providers.gradleProperty("pluginVersion")
    ideaVersion {
      sinceBuild = "262.8665"
    }
  }
  publishing {
    channels.set(
      providers.gradleProperty("pluginVersion").map { pluginVersion ->
        listOf(if ('-' in pluginVersion.substringBefore('+')) "eap" else "default")
      },
    )
  }
}

tasks {
  val testIdeaRoot = layout.buildDirectory.dir("test-idea-root")

  named<PublishPluginTask>("publishPlugin") {
    providers.gradleProperty("pluginArchiveFile").orNull?.let {
      archiveFile.set(layout.projectDirectory.file(it))
      setDependsOn(emptyList<Any>())
    }
  }

  val prepareTestNativeLibraries by registering(Sync::class) {
    from("lib/webview-native")
    into(testIdeaRoot.map { it.dir("community/plugins/ui.webview/lib/webview-native") })
  }

  test {
    dependsOn(prepareTestNativeLibraries)
    useJUnitPlatform()
    systemProperty("idea.dev.project.root", testIdeaRoot.get().asFile.absolutePath)
    systemProperty("java.awt.headless", "false")
    systemProperty("idea.log.trace.categories", "#io.github.nerzhulart.webview")
  }

  register("buildAllWebViewAssets") {
    group = "webview"
    description = "Builds WebView frontend assets for all plugin modules."
    dependsOn(
      ":buildWebViewAssets",
      ":demo:buildWebViewAssets",
      ":markdown-preview:buildWebViewAssets",
    )
  }

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
