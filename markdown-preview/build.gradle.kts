import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PublishPluginTask

plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
  id("org.jetbrains.intellij.platform")
  id("io.github.nerzhulart.webview.frontend")
}

group = "io.github.nerzhulart.webview.markdown.preview"
version = providers.gradleProperty("pluginVersion").get()

base {
  archivesName.set("markdown-webview-preview")
}

repositories {
  mavenCentral()
  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  testImplementation("junit:junit:4.13.2")

  intellijPlatform {
    intellijIdea(providers.gradleProperty("platformVersion")) {
      useInstaller = false
    }
    localPlugin(project(":"))
    bundledPlugin("org.intellij.plugins.markdown")
    testFramework(TestFrameworkType.Platform)
  }
}

extensions.configure<KotlinJvmProjectExtension> {
  jvmToolchain(25)
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_25)
    freeCompilerArgs.addAll("-jvm-default=no-compatibility", "-progressive")
  }
  sourceSets.named("main") {
    kotlin.srcDirs("src", "sdk-compat/src")
    kotlin.exclude("io/github/nerzhulart/webview/markdown/preview/MarkdownRunCommandSession.kt")
  }
  sourceSets.named("test") {
    kotlin.srcDir("tests/testSrc")
    kotlin.exclude("io/github/nerzhulart/webview/markdown/preview/MarkdownRunCommandSessionTest.kt")
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
  publishing {
    channels.set(
      providers.gradleProperty("pluginVersion").map { pluginVersion ->
        listOf(if ('-' in pluginVersion.substringBefore('+')) "eap" else "default")
      },
    )
  }
}

tasks {
  named<PublishPluginTask>("publishPlugin") {
    providers.gradleProperty("pluginArchiveFile").orNull?.let {
      archiveFile.set(rootProject.layout.projectDirectory.file(it))
      setDependsOn(emptyList<Any>())
    }
  }

  buildPlugin {
    archiveFileName.set("markdown-webview-preview-${providers.gradleProperty("pluginVersion").get()}.zip")
  }
}
