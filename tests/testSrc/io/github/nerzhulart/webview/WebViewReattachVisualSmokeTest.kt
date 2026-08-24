// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.junit5.TestApplication
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.api.WebViewPanel
import io.github.nerzhulart.webview.api.WebViewPanelOptions
import io.github.nerzhulart.webview.api.createWebViewPanel
import io.github.nerzhulart.webview.impl.engine.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds


/**
 * Visual smoke test for the detach -> pause -> reattach WebView host flow.
 *
 * With default pauses it is a fast regression test: the page must survive host reattachment
 * without a reload (JS state and DOM are preserved) and must resume rendering afterwards.
 *
 * To watch the flow (and any flicker) with your own eyes, run it with longer pauses:
 * `-Dwebview.smoke.reattach.pauseMs=1500 -Dwebview.smoke.reattach.cycles=4`.
 */
@TestApplication
@DisabledIfSystemProperty(named = "java.awt.headless", matches = "true")
@Suppress("JSUnresolvedVariable")
internal class WebViewReattachVisualSmokeTest {
  @Test
  fun webViewHost_survivesDetachPauseReattachCycles(): Unit = runBlocking {
    assumeFalse(GraphicsEnvironment.isHeadless(), "java.awt.headless=true")

    @Suppress("RAW_SCOPE_CREATION") // Smoke test owns a short-lived WebView scope.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var frame: JFrame? = null

    try {
      val panel = createPanelOrSkip(scope)
      val host = withContext(Dispatchers.EDT) {
        panel.component.apply { preferredSize = Dimension(560, 560) }
      }
      val stage = withContext(Dispatchers.EDT) { Stage(host) }
      frame = showFrame(stage)

      assertTrue(waitUntilShowing(host), "WebView host did not become showing in slot A")
      waitForJavaScriptResult(
        webView = panel.webView,
        script = "window.__WEBVIEW_SMOKE_EXECUTED__ === true ? 'ok' : 'pending'",
        expected = "ok",
        description = "Smoke page script did not execute before the reattach scenario",
      )

      val token = "reattach-${System.nanoTime()}"
      waitForJavaScriptResult(
        webView = panel.webView,
        script = installVisualProbeScript(token),
        expected = "installed",
        description = "Visual probe (token + rAF animation) was not installed",
      )

      waitForJavaScriptResult(
        webView = panel.webView,
        script = WebViewGeometryProbe.INSTALL_SCRIPT,
        expected = "installed",
        description = "Geometry probe (size/position/visibility reported by the page) was not installed",
      )
      val geometryProbe = WebViewGeometryProbe.startDraining(
        scope = scope,
        evaluate = { script -> panel.webView.evaluateJavaScript(script).value },
        sink = { line -> println(line) },
      )

      val recorder = withContext(Dispatchers.EDT) {
        val location = frame!!.locationOnScreen
        WebViewFlashRecorder(java.awt.Rectangle(location.x, location.y, frame.width, frame.height))
      }
      recorder.start()
      try {

      val pause = pauseMillis()
      repeat(cycles()) { cycle ->
        val target = if (stage.hostInSlotA()) "B" else "A"

        stage.setStatus("cycle ${cycle + 1}: detached, pausing ${pause}ms")
        println("[flash-recorder] cycle ${cycle + 1} detach at ${webViewLogTimestamp()}")
        withContext(Dispatchers.EDT) { stage.detachHost() }
        delay(pause.milliseconds)

        stage.setStatus("cycle ${cycle + 1}: attached to slot $target, pausing ${pause}ms — watch for flicker")
        println("[flash-recorder] cycle ${cycle + 1} attach at ${webViewLogTimestamp()}")
        withContext(Dispatchers.EDT) { stage.attachHostToOtherSlot() }
        assertTrue(waitUntilShowing(host), "WebView host did not become showing in slot $target (cycle ${cycle + 1})")
        delay(pause.milliseconds)

        waitForJavaScriptResult(
          webView = panel.webView,
          script = "window.__REATTACH_VISUAL__ ? window.__REATTACH_VISUAL__.token : 'missing'",
          expected = token,
          description = "JS state was lost after reattach (page reloaded?) in cycle ${cycle + 1}",
        )
        assertTrue(
          waitForAnimationFrameProgress(panel.webView),
          "requestAnimationFrame did not resume after reattach in cycle ${cycle + 1}",
        )
      }
      stage.setStatus("done")
      }
      finally {
        geometryProbe.cancel()
        println(recorder.stopAndReport())
      }
    }
    finally {
      runCatching { disposeFrame(frame) }
      scope.coroutineContext.job.cancelAndJoin()
    }
  }

  /** Two side-by-side slots the host is moved between, plus a status line for eyeball runs. */
  private class Stage(private val host: Component) {
    private val statusLabel = JLabel("starting")
    private val slotA = createSlot("Slot A")
    private val slotB = createSlot("Slot B")

    val root: JPanel = JPanel(BorderLayout(0, 8)).apply {
      background = STAGE_BACKGROUND
      border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
      add(statusLabel.apply { foreground = Color(0xD0D4DA) }, BorderLayout.NORTH)
      add(JPanel(GridLayout(1, 2, 12, 0)).apply {
        background = STAGE_BACKGROUND
        add(slotA)
        add(slotB)
      }, BorderLayout.CENTER)
    }

    private var lastSlot: JPanel = slotA

    init {
      slotA.add(host, BorderLayout.CENTER)
    }

    fun hostInSlotA(): Boolean = lastSlot === slotA

    fun detachHost() {
      lastSlot.remove(host)
      lastSlot.revalidate()
      lastSlot.repaint()
    }

    fun attachHostToOtherSlot() {
      val slot = if (lastSlot === slotA) slotB else slotA
      slot.add(host, BorderLayout.CENTER)
      slot.revalidate()
      slot.repaint()
      lastSlot = slot
    }

    suspend fun setStatus(text: String) {
      withContext(Dispatchers.EDT) { statusLabel.text = text }
    }

    private fun createSlot(title: String): JPanel {
      return JPanel(BorderLayout()).apply {
        background = STAGE_BACKGROUND
        border = BorderFactory.createTitledBorder(
          BorderFactory.createLineBorder(Color(0x3C3F45)),
          title,
        ).apply { titleColor = Color(0xD0D4DA) }
      }
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
            debugName = "WebViewReattachVisualSmokeTest",
          ),
        )
      }
    }.getOrElse { t ->
      assumeTrue(false, "No WebView engine is available: ${t::class.java.name}: ${t.message}")
      throw t
    }
  }

  private suspend fun showFrame(stage: Stage): JFrame {
    return withContext(Dispatchers.EDT) {
      JFrame("WebView Reattach Visual Smoke").apply {
        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        contentPane.background = STAGE_BACKGROUND
        contentPane.layout = BorderLayout()
        contentPane.add(stage.root, BorderLayout.CENTER)
        size = Dimension(1240, 760)
        setLocationRelativeTo(null)
        // Keep the stage above other windows: the flash recorder captures the screen region, and
        // eyeball runs must actually see the frame (toFront alone loses to the foreground lock).
        isAlwaysOnTop = true
        isVisible = true
        toFront()
      }
    }
  }

  /** Installs a token for reload detection and a rAF-driven animation so a frozen/blank view is obvious. */
  @Language("JavaScript")
  private fun installVisualProbeScript(token: String): String = """
    (function() {
      if (window.__REATTACH_VISUAL__) return 'installed';
      window.__REATTACH_VISUAL__ = { token: '$token', frames: 0 };
      const overlay = document.createElement('div');
      overlay.id = 'reattach-visual-overlay';
      overlay.style.cssText = 'position:fixed;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;background:#101418;color:#9fd0ff;font:700 40px monospace;z-index:9999;';
      const counter = document.createElement('div');
      const bar = document.createElement('div');
      bar.style.cssText = 'margin-top:24px;width:60%;height:14px;background:#1f2937;position:relative;overflow:hidden;border-radius:7px;';
      const knob = document.createElement('div');
      knob.style.cssText = 'position:absolute;top:0;left:0;width:20%;height:100%;background:#4f9cf9;border-radius:7px;';
      bar.appendChild(knob);
      overlay.appendChild(counter);
      overlay.appendChild(bar);
      document.body.appendChild(overlay);
      const started = performance.now();
      function tick(now) {
        window.__REATTACH_VISUAL__.frames++;
        counter.textContent = 'frame ' + window.__REATTACH_VISUAL__.frames;
        knob.style.left = ((Math.sin((now - started) / 400) * 0.5 + 0.5) * 80) + '%';
        requestAnimationFrame(tick);
      }
      requestAnimationFrame(tick);
      return 'installed';
    })()
  """.trimIndent()

  private suspend fun waitForAnimationFrameProgress(webView: WebView): Boolean {
    @Language("JavaScript")
    val framesScript = "window.__REATTACH_VISUAL__ ? String(window.__REATTACH_VISUAL__.frames) : 'missing'"
    val before = webView.evaluateJavaScript(framesScript).value?.toLongOrNullLenient() ?: return false
    return withTimeoutOrNull(SMOKE_TIMEOUT) {
      while (true) {
        val current = webView.evaluateJavaScript(framesScript).value?.toLongOrNullLenient()
        if (current != null && current > before) return@withTimeoutOrNull true
        delay(100.milliseconds)
      }
    } == true
  }

  private fun String.toLongOrNullLenient(): Long? {
    return toLongOrNull()
      ?: runCatching { Json.parseToJsonElement(this).jsonPrimitive.content.toLongOrNull() }.getOrNull()
  }

  private suspend fun waitForJavaScriptResult(
    webView: WebView,
    @Language("JavaScript") script: String,
    expected: String,
    description: String,
  ) {
    var lastResult: String? = null
    var lastError: Throwable? = null
    val matched = withTimeoutOrNull(SMOKE_TIMEOUT) {
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

    assertTrue(matched, "$description (lastResult=$lastResult, lastError=${lastError?.let { "${it::class.java.name}: ${it.message}" }})")
  }

  private fun javaScriptResultMatches(result: String?, expected: String): Boolean {
    if (result == expected) return true
    if (result == null) return false
    return runCatching { Json.parseToJsonElement(result).jsonPrimitive.content == expected }
      .getOrDefault(false)
  }

  private suspend fun waitUntilShowing(component: Component): Boolean {
    return withTimeoutOrNull(5.seconds) {
      while (true) {
        if (withContext(Dispatchers.EDT) { component.isShowing }) return@withTimeoutOrNull true
        delay(50.milliseconds)
      }
    } == true
  }

  private suspend fun disposeFrame(frame: JFrame?) {
    if (frame == null) return
    withContext(Dispatchers.EDT) { frame.dispose() }
  }

  private fun pauseMillis(): Long {
    return System.getProperty(PAUSE_PROPERTY)?.toLongOrNull()?.coerceIn(0, 30_000) ?: DEFAULT_PAUSE_MILLIS
  }

  private fun cycles(): Int {
    return System.getProperty(CYCLES_PROPERTY)?.toIntOrNull()?.coerceIn(1, 50) ?: DEFAULT_CYCLES
  }

  private companion object {
    private val STAGE_BACKGROUND = Color(0x1E1F22)
    private val SMOKE_TIMEOUT = 20.seconds
    private const val PAUSE_PROPERTY = "webview.smoke.reattach.pauseMs"
    private const val CYCLES_PROPERTY = "webview.smoke.reattach.cycles"
    private const val DEFAULT_PAUSE_MILLIS = 1000L
    private const val DEFAULT_CYCLES = 5
  }
}
