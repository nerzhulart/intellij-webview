// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview

import com.intellij.jna.JnaLoader
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.testFramework.junit5.TestApplication
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.api.WebViewPanelOptions
import io.github.nerzhulart.webview.api.createWebViewPanel
import io.github.nerzhulart.webview.impl.MacMainThreadDispatcher
import io.github.nerzhulart.webview.impl.mac.MacObjectiveC
import io.github.nerzhulart.webview.impl.mac.WKWebViewBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JFrame
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The macOS peer places every WKWebView into a clipping container painted with the host background.
 * A container that outlives its WebView stays on top of Swing as an opaque rectangle, so every
 * teardown order must remove it from the window content view.
 */
@EnabledOnOs(OS.MAC)
@DisabledIfSystemProperty(named = "java.awt.headless", matches = "true")
@TestApplication
internal class MacWebViewNativeContainerCleanupTest {

  @Test
  fun cancellingWebViewScopeWhileHostIsShowingReleasesNativeContainer(): Unit = runBlocking {
    runTeardownScenario { scope, frame, host ->
      // Diff viewers dispose their WebView scope before Swing removes the viewer component.
      scope.coroutineContext.job.cancelAndJoin()
      withContext(Dispatchers.EDT) { frame.removeHost(host) }
    }
  }

  @Test
  fun removingHostBeforeCancellingWebViewScopeReleasesNativeContainer(): Unit = runBlocking {
    runTeardownScenario { scope, frame, host ->
      withContext(Dispatchers.EDT) { frame.removeHost(host) }
      scope.coroutineContext.job.cancelAndJoin()
    }
  }

  private suspend fun runTeardownScenario(teardown: suspend (CoroutineScope, JFrame, JComponent) -> Unit) {
    if (!JnaLoader.isLoaded()) {
      JnaLoader.load(Logger.getInstance(MacWebViewNativeContainerCleanupTest::class.java))
    }

    @Suppress("RAW_SCOPE_CREATION") // Test owns the WebView lifetime to reproduce a specific teardown order.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val frame = withContext(Dispatchers.EDT) {
      JFrame("WebView Native Container Cleanup").apply {
        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        contentPane.layout = BorderLayout()
        size = Dimension(640, 420)
        setLocation(80, 80)
        isVisible = true
      }
    }
    try {
      val baseline = contentSubviewCount(frame)
      val panel = runCatching {
        withContext(Dispatchers.EDT) {
          createWebViewPanel(
            scope = scope,
            options = WebViewPanelOptions(
              assetRoot = WebViewAssetRoot.forView("smoke"),
              debugName = "MacWebViewNativeContainerCleanupTest",
            ),
          )
        }
      }.getOrElse { t ->
        assumeTrue(false, "No WebView engine is available: ${t::class.java.name}: ${t.message}")
        throw t
      }
      val host = withContext(Dispatchers.EDT) {
        panel.component.also { component ->
          frame.contentPane.add(component, BorderLayout.CENTER)
          frame.contentPane.revalidate()
        }
      }

      val attached = awaitContentSubviewCount(frame) { it > baseline }
      assertTrue(attached != null, "WKWebView clipping container was not attached to the window content view")

      teardown(scope, frame, host)

      val released = awaitContentSubviewCount(frame) { it == baseline }
      assertEquals(baseline, released ?: contentSubviewCount(frame), "WKWebView clipping container leaked in the window content view")
    }
    finally {
      scope.coroutineContext.job.cancelAndJoin()
      withContext(Dispatchers.EDT) { frame.dispose() }
    }
  }

  private fun JFrame.removeHost(host: JComponent) {
    contentPane.remove(host)
    contentPane.revalidate()
    contentPane.repaint()
  }

  private suspend fun awaitContentSubviewCount(frame: JFrame, predicate: (Long) -> Boolean): Long? {
    return withTimeoutOrNull(5.seconds) {
      var count = contentSubviewCount(frame)
      while (!predicate(count)) {
        delay(50.milliseconds)
        count = contentSubviewCount(frame)
      }
      count
    }
  }

  private suspend fun contentSubviewCount(frame: JFrame): Long {
    val nsWindow = withContext(Dispatchers.EDT) { MacObjectiveC.windowFromAwt(frame) }
    check(!MacObjectiveC.isNil(nsWindow)) { "AWT frame has no NSWindow" }
    return withContext(MacMainThreadDispatcher) {
      val contentView = checkNotNull(WKWebViewBridge.contentView(nsWindow)) { "NSWindow has no content view" }
      MacObjectiveC.invoke(MacObjectiveC.invoke(contentView, "subviews"), "count").toLong()
    }
  }
}
