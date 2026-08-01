// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview

import com.intellij.jna.JnaLoader
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.registry.RegistryManager
import com.intellij.testFramework.junit5.TestApplication
import io.github.nerzhulart.webview.impl.engine.WebView
import io.github.nerzhulart.webview.api.WebViewPanel
import io.github.nerzhulart.webview.api.WebViewPanelOptions
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.api.createWebViewPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestApplication
@DisabledIfSystemProperty(named = "java.awt.headless", matches = "true")
@Suppress("JSUnresolvedVariable")
internal class WebViewInIdeSmokeTest {
  @Test
  fun webViewPanel_loadsResourcePage_andExecutesJavaScript(): Unit = runBlocking {
    assumeFalse(GraphicsEnvironment.isHeadless(), "java.awt.headless=true")

    @Suppress("RAW_SCOPE_CREATION") // Smoke test owns a short-lived WebView scope.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var panel: WebViewPanel? = null
    var frame: JFrame? = null

    try {
      val smokePanel = createPanelOrSkip(scope)
      panel = smokePanel
      val host = withContext(Dispatchers.EDT) {
        smokePanel.component.apply {
          preferredSize = Dimension(480, 320)
        }
      }
      frame = showHost(host)

      waitForJavaScriptResult(
        webView = smokePanel.webView,
        script = "window.__WEBVIEW_SMOKE_EXECUTED__ === true ? 'ok' : 'pending'",
        expected = "ok",
        description = "Smoke page script did not execute",
      )
      waitForJavaScriptResult(
        webView = smokePanel.webView,
        script = "(function() { const root = document.getElementById('smoke-root'); return root ? root.textContent : 'missing'; })()",
        expected = "webview smoke ready",
        description = "Smoke page DOM marker did not update",
      )
    }
    finally {
      runCatching { panel?.close() }
      runCatching { disposeFrame(frame) }
      scope.cancel()
    }
  }

  private suspend fun createPanelOrSkip(scope: CoroutineScope): WebViewPanel {
    val assetRoot = WebViewAssetRoot.forView("smoke")
    return runCatching {
      withContext(Dispatchers.EDT) {
        createWebViewPanel(
          scope = scope,
          options = WebViewPanelOptions(
            assetRoot = assetRoot,
            debugName = "WebViewInIdeSmokeTest",
          ),
        )
      }
    }
      .getOrElse { t ->
        assumeTrue(false, smokeFailureMessage("No WebView engine is available", lastError = t))
        throw t
      }
  }

  private suspend fun showHost(host: Component): JFrame {
    val frame = withContext(Dispatchers.EDT) {
      JFrame(HOST_TITLE).apply {
        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        contentPane.layout = BorderLayout()
        contentPane.add(host, BorderLayout.CENTER)
        size = Dimension(480, 320)
        isVisible = true
      }
    }
    assertTrue(waitUntilShowing(host, 5.seconds), smokeFailureMessage("WebView host component did not become showing"))
    return frame
  }

  private suspend fun waitForJavaScriptResult(
    webView: WebView,
    @Language("JavaScript") script: String,
    expected: String,
    description: String,
  ): String {
    var lastResult: String? = null
    var lastError: Throwable? = null
    val completedWithinTimeout = withTimeoutOrNull(SMOKE_TIMEOUT) {
      while (true) {
        runCatching { webView.evaluateJavaScript(script).value }
          .onSuccess { result ->
            lastError = null
            lastResult = result
            if (javaScriptResultMatches(result, expected)) return@withTimeoutOrNull true
          }
          .onFailure { t ->
            lastError = t
            lastResult = null
          }
        delay(100.milliseconds)
      }
    } == true
    val matched = completedWithinTimeout || javaScriptResultMatches(lastResult, expected)

    assertTrue(matched, smokeFailureMessage(description, lastResult, lastError))
    return lastResult ?: ""
  }

  private fun javaScriptResultMatches(result: String?, expected: String): Boolean {
    if (result == expected) return true
    if (result == null) return false
    return runCatching { Json.parseToJsonElement(result).jsonPrimitive.content == expected }
      .getOrDefault(false)
  }

  private suspend fun disposeFrame(frame: JFrame?) {
    if (frame == null) return
    withContext(Dispatchers.EDT) { frame.dispose() }
  }

  private suspend fun waitUntilShowing(component: Component, timeout: Duration): Boolean {
    return withTimeoutOrNull(timeout) {
      while (true) {
        if (readOnEdt { component.isShowing }) return@withTimeoutOrNull true
        delay(100.milliseconds)
      }
    } == true
  }

  private suspend fun <T> readOnEdt(action: () -> T): T {
    return withContext(Dispatchers.EDT) { action() }
  }

  private fun smokeFailureMessage(reason: String, lastResult: String? = null, lastError: Throwable? = null): String {
    return buildString {
      append(reason)
      append(" (engine=")
      append(selectedEngineId())
      append(", os=")
      append(System.getProperty("os.name"))
      append(' ')
      append(System.getProperty("os.version"))
      append(", hostTitle=")
      append(HOST_TITLE)
      if (lastResult != null) {
        append(", lastJsResult=")
        append(lastResult)
      }
      if (lastError != null) {
        append(", lastJsError=")
        append(lastError::class.java.name)
        append(": ")
        append(lastError.message)
      }
      append(')')
    }
  }

  private fun selectedEngineId(): String {
    return runCatching {
      val registryValue = RegistryManager.getInstance().get(WEBVIEW_ENGINE_REGISTRY_KEY)
      (registryValue.selectedOption ?: registryValue.asString()).ifBlank { "AUTO" }
    }.getOrDefault("AUTO")
  }

  private companion object {
    private val longRunningThreadsDisposable = Disposer.newDisposable("WebViewInIdeSmokeTest long-running threads")
    private const val HOST_TITLE = "WebView Smoke Test"
    private const val WEBVIEW_ENGINE_REGISTRY_KEY = "io.github.nerzhulart.webview.engine"
    private val SMOKE_TIMEOUT = 20.seconds

    @JvmStatic
    @BeforeAll
    fun setUpClass() {
      if (SystemInfo.isMac && !JnaLoader.isLoaded()) {
        JnaLoader.load(Logger.getInstance(WebViewInIdeSmokeTest::class.java))
      }
      val trackerClass = Class.forName("com.intellij.testFramework.common.ThreadLeakTracker")
      val method = trackerClass.getMethod("longRunningThreadCreated", Disposable::class.java, Array<String>::class.java)
      method.invoke(null, longRunningThreadsDisposable, arrayOf("WebView2-Thread"))
    }

    @JvmStatic
    @AfterAll
    fun tearDownClass() {
      Disposer.dispose(longRunningThreadsDisposable)
    }
  }
}
