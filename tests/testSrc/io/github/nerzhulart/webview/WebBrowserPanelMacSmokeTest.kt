// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview

import com.intellij.jna.JnaLoader
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.testFramework.junit5.TestApplication
import io.github.nerzhulart.webview.api.WebBrowserPanelOptions
import io.github.nerzhulart.webview.api.WebViewBrowserState
import io.github.nerzhulart.webview.impl.engine.WebView
import io.github.nerzhulart.webview.impl.engine.WebViewEngineId
import io.github.nerzhulart.webview.ui.EmbeddedBrowserPanelOptions
import io.github.nerzhulart.webview.ui.createEmbeddedBrowserPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.awt.BorderLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFrame

@TestApplication
@EnabledOnOs(OS.MAC)
@DisabledIfSystemProperty(named = "java.awt.headless", matches = "true")
internal class WebBrowserPanelMacSmokeTest {
  @Test
  fun embeddedBrowser_navigatesLocalUrlsAndRecoversFromError(@TempDir directory: Path): Unit = runBlocking {
    if (!JnaLoader.isLoaded()) JnaLoader.load(Logger.getInstance(WebBrowserPanelMacSmokeTest::class.java))
    Files.writeString(directory.resolve("first.html"), /*language=HTML*/ "<html><head><title>First</title></head><body>First page</body></html>")
    Files.writeString(directory.resolve("second.html"), /*language=HTML*/ "<html><head><title>Second</title></head><body>Second page</body></html>")
    val firstUrl = directory.resolve("first.html").toUri()
    val secondUrl = directory.resolve("second.html").toUri()
    @Suppress("RAW_SCOPE_CREATION") // A test owns the complete browser lifetime.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var frame: JFrame? = null
    try {
      val panel = withContext(Dispatchers.EDT) {
        createEmbeddedBrowserPanel(scope, EmbeddedBrowserPanelOptions(browserOptions = WebBrowserPanelOptions(initialUrl = firstUrl))).also {
          frame = JFrame("Embedded Browser Smoke Test").apply {
            contentPane.add(it, BorderLayout.CENTER)
            setSize(640, 400)
            isVisible = true
          }
        }
      }
      val webView = panel.webView
      assertEquals(WebViewEngineId.SYSTEM_MACOS, webView.runtimeInfo.engineId)
      assertSame(webView.component, panel.browserPanel.component)
      assertTrue(webView.isBrowserNavigationSupported)
      awaitPage(webView, "First", "first.html")

      webView.loadUrl(secondUrl)
      assertTrue(awaitPage(webView, "Second", "second.html").canGoBack)
      webView.goBack()
      assertTrue(awaitPage(webView, "First", "first.html").canGoForward)
      webView.goForward()
      awaitPage(webView, "Second", "second.html")

      webView.evaluateJavaScript(/*language=JavaScript*/ "window.__browserReloadMarker = true")
      webView.reload()
      withTimeout(15_000) {
        while (webView.evaluateJavaScript(/*language=JavaScript*/ "typeof window.__browserReloadMarker === 'undefined'").value != "true") {
          delay(50)
        }
      }
      awaitPage(webView, "Second", "second.html")
      webView.stop()

      webView.loadUrl(directory.resolve("missing.html").toUri())
      withTimeout(15_000) { webView.browserState.first { it.error != null && !it.isLoading } }
      webView.loadUrl(firstUrl)
      awaitPage(webView, "First", "first.html")
    }
    finally {
      scope.coroutineContext.job.cancelAndJoin()
      withContext(Dispatchers.EDT) { frame?.dispose() }
    }
  }

  private suspend fun awaitPage(webView: WebView, title: String, file: String): WebViewBrowserState = withTimeout(15_000) {
    webView.browserState.first {
      it.url?.endsWith(file) == true && it.title == title && !it.isLoading && it.progress == 1.0 && it.error == null
    }
  }
}