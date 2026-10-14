// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview

import com.intellij.jna.JnaLoader
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.junit5.TestApplication
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.api.WebViewPanel
import io.github.nerzhulart.webview.api.WebViewPanelOptions
import io.github.nerzhulart.webview.api.createWebViewPanel
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
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import javax.swing.JFrame
import javax.swing.JPanel
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One scenario for every engine the platform picks - WebView2, WKWebView, JCEF: a view opened in a
 * panel of a given background must not show any other color until its page paints, in a light theme
 * as well as in a dark one.
 *
 * The page is painted with the host color too, so the region under the view is expected to keep that
 * color on every captured frame; anything else is a frame of the engine itself - a hard-coded native
 * background, an empty native surface, a white `about:blank`.
 *
 * A screen capture only proves something when it shows the frame the test drives - a runner without
 * a real display or without the screen recording permission captures the desktop, and a window of
 * the user can cover the frame. So the test is skipped when the empty slot is not seen in the host
 * color before the view opens, or when the marker the page paints afterwards is not seen.
 */
@TestApplication
@DisabledIfSystemProperty(named = "java.awt.headless", matches = "true")
@Suppress("JSUnresolvedVariable")
internal class WebViewHostBackgroundVisualTest {
  @Test
  fun lightHost_viewOpensWithoutForeignFrames() {
    assertViewOpensInHostColor(LIGHT_HOST_RGB, "light")
  }

  @Test
  fun darkHost_viewOpensWithoutForeignFrames() {
    assertViewOpensInHostColor(DARK_HOST_RGB, "dark")
  }

  private fun assertViewOpensInHostColor(hostRgb: Int, themeName: String): Unit = runBlocking {
    assumeFalse(GraphicsEnvironment.isHeadless(), "java.awt.headless=true")

    @Suppress("RAW_SCOPE_CREATION") // Test owns a short-lived WebView scope.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val hostColor = Color(hostRgb)
    var frame: JFrame? = null
    try {
      val slot = withContext(Dispatchers.EDT) { JPanel(BorderLayout()).apply { background = hostColor } }
      frame = showFrame(slot, hostColor)
      // The window itself fades in on some desktops; only the view opening is under test.
      delay(SETTLE_DELAY)

      val region = withContext(Dispatchers.EDT) { slot.regionOnScreen() }
      val emptySlotOffColor = WebViewFlashRecorder.offColorFraction(Robot().createScreenCapture(region), hostRgb)
      assumeTrue(emptySlotOffColor < OFF_COLOR_LIMIT) {
        "The empty slot is not on screen (offColor=${"%.2f".format(emptySlotOffColor)}): another window covers it, or the capture shows the desktop"
      }
      val recorder = WebViewFlashRecorder(region, "host-background-$themeName-" + System.currentTimeMillis(), expectedRgb = hostRgb)
      val panel: WebViewPanel
      recorder.start()
      try {
        panel = createPanelOrSkip(scope, hostRgb)
        withContext(Dispatchers.EDT) {
          // Explicit, so the host keeps this color whatever the Look and Feel of the test application is.
          panel.component.background = hostColor
          slot.add(panel.component, BorderLayout.CENTER)
          slot.revalidate()
          slot.repaint()
        }
        waitForJavaScriptResult(
          panel = panel,
          script = "window.__HOST_BACKGROUND_PAINTED__ === true ? 'ok' : 'pending'",
          expected = "ok",
          description = "The page did not paint",
        )
        // A late native frame - a resize after the first paint, a second surface - counts too.
        delay(AFTER_PAINT_DELAY)
      }
      finally {
        println(recorder.stopAndReport())
      }

      val engine = panel.webView.runtimeInfo.engineId
      waitForJavaScriptResult(panel, SHOW_MARKER_SCRIPT, "shown", "The marker was not added")
      assumeTrue(
        awaitMarkerOnScreen(region),
        "The screen capture does not show the WebView (engine=$engine), so it shows nothing the test drives",
      )
      assertTrue(recorder.maxOffColorFraction < OFF_COLOR_LIMIT) {
        "The $themeName view showed a frame of another color while opening (engine=$engine, " +
          "maxOffColorFraction=${"%.2f".format(recorder.maxOffColorFraction)}, host=#${"%06x".format(hostRgb)}); " +
          "the frames are in build/reports/webview-flash"
      }
    }
    finally {
      runCatching { disposeFrame(frame) }
      scope.coroutineContext.job.cancelAndJoin()
    }
  }

  private suspend fun createPanelOrSkip(scope: CoroutineScope, hostRgb: Int): WebViewPanel {
    return runCatching {
      withContext(Dispatchers.EDT) {
        createWebViewPanel(
          scope = scope,
          options = WebViewPanelOptions(
            assetRoot = WebViewAssetRoot.forView("host-background"),
            query = "background=${"%06x".format(hostRgb)}",
            debugName = "WebViewHostBackgroundVisualTest",
          ),
        )
      }
    }.getOrElse { t ->
      assumeTrue(false, "No WebView engine is available: ${t::class.java.name}: ${t.message}")
      throw t
    }
  }

  private suspend fun showFrame(slot: JPanel, hostColor: Color): JFrame {
    return withContext(Dispatchers.EDT) {
      JFrame("WebView Host Background").apply {
        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        contentPane.background = hostColor
        contentPane.layout = BorderLayout()
        contentPane.add(slot, BorderLayout.CENTER)
        size = Dimension(640, 480)
        setLocationRelativeTo(null)
        // The recorder captures the screen region, so nothing may cover the frame.
        isAlwaysOnTop = true
        isVisible = true
        toFront()
      }
    }
  }

  /** The slot without its edges, where window chrome and anti-aliasing of the frame never reach. */
  private fun JPanel.regionOnScreen(): Rectangle {
    val location = locationOnScreen
    return Rectangle(location.x + REGION_INSET, location.y + REGION_INSET, width - 2 * REGION_INSET, height - 2 * REGION_INSET)
  }

  private suspend fun awaitMarkerOnScreen(region: Rectangle): Boolean {
    val robot = Robot()
    return withTimeoutOrNull(MARKER_TIMEOUT) {
      while (true) {
        val pixel = robot.getPixelColor(region.centerX.toInt(), region.centerY.toInt()).rgb
        if (WebViewFlashRecorder.colorDistance(pixel, MARKER_RGB) < MARKER_TOLERANCE) return@withTimeoutOrNull true
        delay(100.milliseconds)
      }
    } == true
  }

  private suspend fun waitForJavaScriptResult(
    panel: WebViewPanel,
    @Language("JavaScript") script: String,
    expected: String,
    description: String,
  ) {
    var lastResult: String? = null
    var lastError: Throwable? = null
    val matched = withTimeoutOrNull(PAGE_TIMEOUT) {
      while (true) {
        runCatching { panel.webView.evaluateJavaScript(script).value }
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

  private suspend fun disposeFrame(frame: JFrame?) {
    if (frame == null) return
    withContext(Dispatchers.EDT) { frame.dispose() }
  }

  private companion object {
    /** New UI Light and Dark panel backgrounds. */
    private const val LIGHT_HOST_RGB = 0xF7F8F9
    private const val DARK_HOST_RGB = 0x1E1F22
    private const val MARKER_RGB = 0xFF00FF

    /** A sampled pixel counts as off-color farther than `60` from the host; this share of them is a visible flash. */
    private const val OFF_COLOR_LIMIT = 0.05
    private const val MARKER_TOLERANCE = 90
    private const val REGION_INSET = 8
    private val SETTLE_DELAY = 1_200.milliseconds
    private val AFTER_PAINT_DELAY = 1_000.milliseconds
    private val PAGE_TIMEOUT = 20.seconds
    private val MARKER_TIMEOUT = 5.seconds

    @Language("JavaScript")
    private val SHOW_MARKER_SCRIPT = """
      (function() {
        if (!document.getElementById('host-background-marker')) {
          const marker = document.createElement('div');
          marker.id = 'host-background-marker';
          marker.style.cssText = 'position:fixed;inset:25%;background:#ff00ff;';
          document.body.appendChild(marker);
        }
        return 'shown';
      })()
    """.trimIndent()

    @JvmStatic
    @BeforeAll
    fun setUpClass() {
      if (SystemInfo.isMac && !JnaLoader.isLoaded()) {
        JnaLoader.load(Logger.getInstance(WebViewHostBackgroundVisualTest::class.java))
      }
    }
  }
}
