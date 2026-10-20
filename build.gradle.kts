// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.PublishPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.aware.RuntimeAware

plugins {
  kotlin("jvm") version "2.4.10"
  kotlin("plugin.serialization") version "2.4.10"
  id("org.jetbrains.intellij.platform") version "2.18.1"
  id("org.jetbrains.intellij.platform.module") version "2.18.1" apply false
  id("io.github.nerzhulart.webview.frontend")
}

group = "io.github.nerzhulart.webview"
version = providers.gradleProperty("pluginVersion").get()

val jetbrainsRuntimeCopy = layout.projectDirectory.dir(".intellijPlatform/jetbrains-runtime")

allprojects {
  tasks.withType<Test>().configureEach {
    outputs.doNotCacheIf("Test results must not be restored from the build cache") { true }
  }

  // An IDE resolved with `useInstaller = false` ships `plugins/jcef-plugin` without its native CEF bundle, so JCEF
  // never initializes in the sandbox IDE and every JCEF panel stays blank. The JetBrains Runtime with JCEF ships a
  // bundle for the same platform build in `lib` on Linux; `jcef.native.bundle.path` takes priority over the plugin one.
  tasks.withType<RunIdeTask>().configureEach {
    val runtimeLibDirectory = runtimeDirectory.dir("lib")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
      val bundle = runtimeLibDirectory.get().asFile
      if (bundle.resolve("libcef.so").isFile) listOf("-Djcef.native.bundle.path=${bundle.absolutePath}") else emptyList()
    })
  }

  // On Linux and Windows, Chromium creates its external extensions directory `extensions` next to libcef. Started from
  // the `jetbrainsRuntime` dependency, JCEF writes it into an immutable Gradle transform workspace. Every new Gradle
  // daemon then fails to resolve the runtime, and the IntelliJ Platform Gradle Plugin silently falls back to the Java
  // toolchain, usually the JDK the daemon runs on. A JBR of another build ships another CEF, and JCEF never starts in
  // runIde. So the IDE and the tests run on a mutable copy of the runtime that already has this directory. On macOS,
  // Chromium keeps the directory outside the runtime, and a Gradle copy would break the symlinks of its bundle.
  if (!System.getProperty("os.name").startsWith("Mac")) {
    afterEvaluate {
      val prepareJetBrainsRuntime = rootProject.tasks.named("prepareJetBrainsRuntime")
      tasks.matching { it is RuntimeAware }.configureEach {
        dependsOn(prepareJetBrainsRuntime)
        (this as RuntimeAware).runtimeDirectory.set(jetbrainsRuntimeCopy)
      }
      tasks.withType<Test>().configureEach {
        dependsOn(prepareJetBrainsRuntime)
      }
    }
  }

  // Tests load the whole IDE into one core classloader. In the obfuscated IntelliJ IDEA release builds (both the
  // installer and the Maven archive), the classes of `plugins/ultimate-plugin/lib/ultimate-plugin.jar` share names with
  // `lib/product-backend.jar`, so its only extension, a postStartupActivity `Z.Z.Z.Z.Z`, resolves to an interface from
  // product-backend.jar and every opened test project logs "Cannot create extension", which TestLoggerFactory turns into
  // a test failure.
  tasks.withType<PrepareSandboxTask>().matching { it.name == "prepareTestSandbox" }.configureEach {
    disabledPlugins.add("com.intellij.modules.ultimate")
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
  pluginVerification {
    // Internal API usages are checked in verifyPlugin.doLast against plugin-verifier-ignored-internal-api.txt:
    // Plugin Verifier's ignoredProblemsFile does not apply to internal API usages.
    failureLevel.set(
      listOf(
        VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
        VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
      ),
    )
    ides {
      current()
    }
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

  named<VerifyPluginTask>("verifyPlugin") {
    providers.gradleProperty("pluginArchiveFile").orNull?.let {
      archiveFile.set(layout.projectDirectory.file(it))
      setDependsOn(emptyList<Any>())
    }

    val reportsDirectory = verificationReportsDirectory
    val ignoredInternalApiFile = layout.projectDirectory.file("plugin-verifier-ignored-internal-api.txt")
    inputs.file(ignoredInternalApiFile)
    doFirst {
      reportsDirectory.get().asFile.deleteRecursively()
    }
    doLast {
      val ignoredPatterns = ignoredInternalApiFile.asFile.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { Regex(it) }
      val unexpectedUsages = reportsDirectory.get().asFile.walk()
        .filter { it.name == "internal-api-usages.txt" }
        .flatMap { it.readLines().asSequence() }
        .filter { usage -> usage.isNotBlank() && ignoredPatterns.none { it.containsMatchIn(usage) } }
        .toList()
      if (unexpectedUsages.isNotEmpty()) {
        throw GradleException(
          "Plugin Verifier found internal API usages missing from plugin-verifier-ignored-internal-api.txt:\n" +
            unexpectedUsages.joinToString("\n"),
        )
      }
    }
  }

  register<Sync>("prepareJetBrainsRuntime") {
    description = "Copies the JetBrains Runtime out of the immutable Gradle transform cache for running the IDE and tests."
    val jetbrainsRuntime = configurations.named("jetbrainsRuntime")
    from(jetbrainsRuntime.map { runtime -> runtime.singleFile.listFiles { file -> file.name.startsWith("jbr") }!!.single() })
    into(jetbrainsRuntimeCopy)
    doLast {
      listOf("lib", "bin").map { destinationDir.resolve(it) }
        .filter { directory -> directory.listFiles { file -> file.name.startsWith("libcef.") }.orEmpty().isNotEmpty() }
        .forEach { it.resolve("extensions").mkdirs() }
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
    // Forward the visual smoke scenario knobs from the Gradle CLI into the test JVM.
    listOf("webview.smoke.reattach.pauseMs", "webview.smoke.reattach.cycles").forEach { key ->
      providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
    // Flicker diagnostics: trace paint/placement window messages in the native bridge.
    providers.systemProperty("webview.win.paint.trace").orNull?.let {
      environment("WEBVIEW_WIN_PAINT_TRACE", it)
    }
  }

  register<JavaExec>("runWebViewSampleApp") {
    group = "webview"
    description = "Runs the standalone WebView sample app used for manual flicker checks."
    dependsOn(prepareTestNativeLibraries)
    mainClass.set("io.github.nerzhulart.webview.LightweightStandaloneSampleApp")
    classpath = sourceSets["test"].runtimeClasspath
    systemProperty("idea.dev.project.root", testIdeaRoot.get().asFile.absolutePath)
    systemProperty("java.awt.headless", "false")
    systemProperty("idea.log.trace.categories", "#io.github.nerzhulart.webview")
    // Flicker diagnostics: trace paint/placement window messages in the native bridge.
    providers.systemProperty("webview.win.paint.trace").orNull?.let {
      environment("WEBVIEW_WIN_PAINT_TRACE", it)
    }
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
