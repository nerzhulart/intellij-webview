import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.io.File

plugins {
  kotlin("jvm") version "2.4.10"
  kotlin("plugin.serialization") version "2.4.10"
  id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "io.github.nerzhulart.webview.sample"
version = "0.1.0"

base {
  archivesName.set("hello-webview")
}

val webviewVersion = providers.gradleProperty("webviewVersion").get()
val webviewChannel = providers.gradleProperty("webviewPluginChannel").get()
val webViewSrcDirectory = layout.projectDirectory.dir("webview-src")
val generatedWebViewResources = layout.buildDirectory.dir("generated-resources/webview/main")
val bunExecutable = listOf(
  "/opt/homebrew/bin/bun",
  "/usr/local/bin/bun",
).firstOrNull { File(it).isFile } ?: "bun"

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
    plugin("io.github.nerzhulart.webview:$webviewVersion@$webviewChannel")
    testFramework(TestFrameworkType.Platform)
    testFramework(TestFrameworkType.JUnit5)
  }
  testImplementation(platform("org.junit:junit-bom:5.13.4"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("junit:junit:4.13.2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
    resources {
      srcDir("resources")
      exclude("webview/**")
    }
  }
}

intellijPlatform {
  pluginConfiguration {
    version = project.version.toString()
    ideaVersion {
      sinceBuild = "262.10315"
    }
  }
}

val bunInstall by tasks.registering(Exec::class) {
  workingDir(webViewSrcDirectory)
  inputs.files(
    webViewSrcDirectory.file("package.json"),
    webViewSrcDirectory.file("bun.lock"),
  )
  outputs.dir(webViewSrcDirectory.dir("node_modules"))
  commandLine(bunExecutable, "install", "--frozen-lockfile")
}

val buildWebViewAssets by tasks.registering(Exec::class) {
  dependsOn(bunInstall)
  workingDir(webViewSrcDirectory)
  inputs.dir(webViewSrcDirectory)
  outputs.dir(generatedWebViewResources)
  environment(
    "WEBVIEW_OUTPUT_ROOT",
    generatedWebViewResources.get().asFile.resolve("webview").absolutePath,
  )
  commandLine(bunExecutable, "run", "build")
}

tasks.processResources {
  dependsOn(buildWebViewAssets)
  from(generatedWebViewResources)
}

tasks.test {
  useJUnitPlatform()
  systemProperty("java.awt.headless", "false")
}