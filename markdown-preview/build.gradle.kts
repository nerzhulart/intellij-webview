import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
  id("org.jetbrains.intellij.platform")
}

group = "org.intellij.plugins.markdown.webview"
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
  intellijPlatform {
    intellijIdea(providers.gradleProperty("platformVersion")) {
      useInstaller = false
    }
    localPlugin(project(":"))
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
    kotlin.srcDirs("src", "sdk-compat/src")
    kotlin.exclude("org/intellij/plugins/markdown/webview/preview/MarkdownRunCommandSession.kt")
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
    archiveFileName.set("markdown-webview-preview-${providers.gradleProperty("pluginVersion").get()}.zip")
  }
}
