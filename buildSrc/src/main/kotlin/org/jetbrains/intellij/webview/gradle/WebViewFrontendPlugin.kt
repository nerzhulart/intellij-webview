// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij.webview.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import javax.inject.Inject

private const val VERIFY_BUN_TASK_NAME = "verifyBun"
private const val BUN_INSTALL_TASK_NAME = "bunInstall"
private const val BUILD_WEBVIEW_ASSETS_TASK_NAME = "buildWebViewAssets"

@DisableCachingByDefault(because = "The task only validates the locally installed Bun executable")
abstract class VerifyBunVersionTask : DefaultTask() {
  @get:Input
  abstract val expectedVersion: Property<String>

  @get:Inject
  abstract val execOperations: ExecOperations

  @TaskAction
  fun verifyVersion() {
    val output = ByteArrayOutputStream()
    try {
      execOperations.exec {
        commandLine("bun", "--version")
        standardOutput = output
      }.assertNormalExitValue()
    }
    catch (error: Exception) {
      throw GradleException(
        "Bun ${expectedVersion.get()} is required to build WebView frontend assets. Install Bun and make it available on PATH.",
        error,
      )
    }

    val actualVersion = output.toString(Charsets.UTF_8.name()).trim()
    if (actualVersion != expectedVersion.get()) {
      throw GradleException("Bun ${expectedVersion.get()} is required, but $actualVersion is available on PATH.")
    }
  }
}

@DisableCachingByDefault(because = "Bun installs dependencies into the package-local node_modules directory")
abstract class BunInstallTask : DefaultTask() {
  @get:Internal
  abstract val webViewSrcDirectory: DirectoryProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val packageManifests: ConfigurableFileCollection

  @get:Input
  abstract val bunVersion: Property<String>

  @get:OutputFile
  abstract val markerFile: RegularFileProperty

  @get:Inject
  abstract val execOperations: ExecOperations

  @TaskAction
  fun installDependencies() {
    execOperations.exec {
      workingDir(webViewSrcDirectory.get().asFile)
      commandLine("bun", "install", "--frozen-lockfile")
    }.assertNormalExitValue()

    markerFile.get().asFile.apply {
      parentFile.mkdirs()
      writeText("bun=${bunVersion.get()}\n")
    }
  }
}

@DisableCachingByDefault(because = "The task invokes the package-local Vite build through Bun")
abstract class BuildWebViewAssetsTask : DefaultTask() {
  @get:Internal
  abstract val webViewSrcDirectory: DirectoryProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sourceFiles: ConfigurableFileCollection

  @get:Input
  abstract val bunVersion: Property<String>

  @get:OutputDirectory
  abstract val generatedResourcesDirectory: DirectoryProperty

  @get:Inject
  abstract val execOperations: ExecOperations

  @get:Inject
  abstract val fileSystemOperations: FileSystemOperations

  @TaskAction
  fun buildAssets() {
    val generatedResources = generatedResourcesDirectory.get().asFile
    fileSystemOperations.delete {
      delete(generatedResources)
    }
    generatedResources.mkdirs()

    execOperations.exec {
      workingDir(webViewSrcDirectory.get().asFile)
      environment("WEBVIEW_OUTPUT_ROOT", generatedResources.resolve("webview").absolutePath)
      commandLine("bun", "run", "build")
    }.assertNormalExitValue()
  }
}

abstract class BunInstallLock : BuildService<BuildServiceParameters.None>

class WebViewFrontendPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val rootProject = project.rootProject
    val webViewSrcDir = project.layout.projectDirectory.dir("webview-src")
    check(webViewSrcDir.asFile.isDirectory) {
      "The webview-frontend plugin requires ${webViewSrcDir.asFile}"
    }

    val expectedBunVersion = rootProject.providers.gradleProperty("bunVersion")
    val verifyBun = if (project == rootProject) {
      project.tasks.register(VERIFY_BUN_TASK_NAME, VerifyBunVersionTask::class.java) {
        group = "verification"
        description = "Verifies the Bun version used for WebView frontend builds."
        expectedVersion.set(expectedBunVersion)
      }
    }
    else {
      rootProject.tasks.named(VERIFY_BUN_TASK_NAME, VerifyBunVersionTask::class.java)
    }

    val installLock = project.gradle.sharedServices.registerIfAbsent("webViewBunInstallLock", BunInstallLock::class.java) {
      maxParallelUsages.set(1)
    }
    val bunInstall = project.tasks.register(BUN_INSTALL_TASK_NAME, BunInstallTask::class.java) {
      group = "webview"
      description = "Installs locked dependencies for ${project.path} WebView frontend sources."
      dependsOn(verifyBun)
      usesService(installLock)
      webViewSrcDirectory.set(webViewSrcDir)
      packageManifests.from(
        project.layout.projectDirectory.file("webview-src/package.json"),
        project.layout.projectDirectory.file("webview-src/bun.lock"),
        rootProject.fileTree("webview-src") {
          include("package.json", "packages/*/package.json")
        },
      )
      bunVersion.set(expectedBunVersion)
      markerFile.set(webViewSrcDir.file("node_modules/.gradle-bun-install"))
    }

    val buildWebViewAssets = project.tasks.register(BUILD_WEBVIEW_ASSETS_TASK_NAME, BuildWebViewAssetsTask::class.java) {
      group = "webview"
      description = "Builds ${project.path} WebView frontend assets for JVM resource packaging."
      dependsOn(bunInstall)
      webViewSrcDirectory.set(webViewSrcDir)
      sourceFiles.from(project.fileTree(webViewSrcDir) {
        exclude("node_modules/**", "playwright-report/**", "test-results/**")
      })
      if (project != rootProject) {
        sourceFiles.from(rootProject.fileTree("webview-src") {
          exclude("node_modules/**", "playwright-report/**", "test-results/**")
        })
      }
      bunVersion.set(expectedBunVersion)
      generatedResourcesDirectory.set(project.layout.buildDirectory.dir("generated-resources/webview/main"))
    }

    project.pluginManager.withPlugin("java") {
      project.tasks.named("processResources", ProcessResources::class.java) {
        dependsOn(buildWebViewAssets)
        from(buildWebViewAssets.flatMap { it.generatedResourcesDirectory })
      }
    }
  }
}
