// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
  id("org.jetbrains.intellij.platform.module")
}

repositories {
  mavenCentral()
  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  implementation(project(":"))
  testImplementation(platform("org.junit:junit-bom:5.13.4"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("junit:junit:4.13.2")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")

  intellijPlatform {
    intellijIdea(providers.gradleProperty("platformVersion")) {
      useInstaller = false
    }
    bundledModule("intellij.libraries.jcef")
    bundledModule("intellij.platform.ui.jcef")
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
}

tasks {
  test {
    useJUnitPlatform()
  }

  jar {
    archiveFileName.set("io.github.nerzhulart.webview.jcef.jar")
  }

  composedJar {
    archiveFileName.set("io.github.nerzhulart.webview.jcef.jar")
  }
}
