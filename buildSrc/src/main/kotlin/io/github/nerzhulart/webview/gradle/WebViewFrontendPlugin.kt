// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package io.github.nerzhulart.webview.gradle

import org.gradle.api.DefaultTask
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
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskAction
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.File
import javax.inject.Inject

private const val BUN_INSTALL_TASK_NAME = "bunInstall"
private const val BUILD_WEBVIEW_ASSETS_TASK_NAME = "buildWebViewAssets"

@DisableCachingByDefault(because = "Bun installs dependencies into the package-local node_modules directory")
abstract class BunInstallTask : DefaultTask() {
  @get:Internal
  abstract val webViewSrcDirectory: DirectoryProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val packageManifests: ConfigurableFileCollection

  @get:Input
  abstract val bunExecutable: Property<String>

  @get:OutputFile
  abstract val markerFile: RegularFileProperty

  @get:Inject
  abstract val execOperations: ExecOperations

  @TaskAction
  fun installDependencies() {
    execOperations.exec {
      workingDir(webViewSrcDirectory.get().asFile)
      commandLine(bunExecutable.get(), "install", "--frozen-lockfile")
    }.assertNormalExitValue()

    markerFile.get().asFile.apply {
      parentFile.mkdirs()
      writeText("installed\n")
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
  abstract val bunExecutable: Property<String>

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
      commandLine(bunExecutable.get(), "run", "build")
    }.assertNormalExitValue()
  }
}

abstract class BunInstallLock : BuildService<BuildServiceParameters.None>

class WebViewFrontendPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val rootProject = project.rootProject
    val webViewSrcDir = project.layout.projectDirectory.dir("webview-src")
    check(webViewSrcDir.asFile.isDirectory) {
      "The io.github.nerzhulart.webview.frontend plugin requires ${webViewSrcDir.asFile}"
    }

    val bunExecutable = listOf(
      "/opt/homebrew/bin/bun",
      "/usr/local/bin/bun",
    ).firstOrNull { File(it).isFile } ?: "bun"

    val installLock = project.gradle.sharedServices.registerIfAbsent("webViewBunInstallLock", BunInstallLock::class.java) {
      maxParallelUsages.set(1)
    }
    val bunInstall = project.tasks.register(BUN_INSTALL_TASK_NAME, BunInstallTask::class.java) {
      group = "webview"
      description = "Installs locked dependencies for ${project.path} WebView frontend sources."
      usesService(installLock)
      webViewSrcDirectory.set(webViewSrcDir)
      packageManifests.from(
        project.layout.projectDirectory.file("webview-src/package.json"),
        project.layout.projectDirectory.file("webview-src/bun.lock"),
        rootProject.fileTree("webview-src") {
          include("package.json", "packages/*/package.json")
        },
      )
      this.bunExecutable.set(bunExecutable)
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
      this.bunExecutable.set(bunExecutable)
      generatedResourcesDirectory.set(project.layout.buildDirectory.dir("generated-resources/webview/main"))
    }

    project.pluginManager.withPlugin("java") {
      // Direct Bun builds use the ignored resources/webview tree; Gradle packages only its own generated output.
      project.extensions.getByType(SourceSetContainer::class.java)
        .named(SourceSet.MAIN_SOURCE_SET_NAME) {
          resources.exclude("webview/**")
        }
      project.tasks.named("processResources", ProcessResources::class.java) {
        dependsOn(buildWebViewAssets)
        from(buildWebViewAssets.flatMap { it.generatedResourcesDirectory })
      }
    }
  }
}
