// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview

import com.intellij.jna.JnaLoader
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.impl.IdeGlassPaneImpl
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.OnePixelSplitter
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.api.WebViewNotification
import io.github.nerzhulart.webview.api.WebViewPanel
import io.github.nerzhulart.webview.api.WebViewPanelOptions
import io.github.nerzhulart.webview.api.createWebViewPanel
import io.github.nerzhulart.webview.impl.engine.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Point
import java.awt.Robot
import java.awt.event.InputEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@TestApplication
@EnabledOnOs(OS.MAC, OS.WINDOWS, OS.LINUX)
@DisabledIfSystemProperty(named = "java.awt.headless", matches = "true")
@Suppress("JSUnresolvedVariable")
internal class WebViewRuntimeSmokeTest {
  private var frame: JFrame? = null
  private var scope: CoroutineScope? = null

  companion object {
    private val longRunningThreadsDisposable = Disposer.newDisposable("WebViewRuntimeSmokeTest long-running threads")

    @JvmStatic
    @BeforeAll
    fun setUpClass() {
      if (SystemInfo.isMac && !JnaLoader.isLoaded()) {
        JnaLoader.load(Logger.getInstance(WebViewRuntimeSmokeTest::class.java))
      }
      registerLongRunningThreads()
    }

    @JvmStatic
    @AfterAll
    fun tearDownClass() {
      Disposer.dispose(longRunningThreadsDisposable)
    }

    private fun registerLongRunningThreads() {
      val trackerClass = Class.forName("com.intellij.testFramework.common.ThreadLeakTracker")
      val method = trackerClass.getMethod("longRunningThreadCreated", Disposable::class.java, Array<String>::class.java)
      method.invoke(
        null,
        longRunningThreadsDisposable,
        arrayOf("AWT-Wayland", "WLKeyboard.KeyRepeatManager"),
      )
    }
  }

  @BeforeEach
  fun setUp() {
    @Suppress("RAW_SCOPE_CREATION") // Test: no parent scope available without product code.
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    runBlocking(Dispatchers.EDT) {
      frame = JFrame("WebView Runtime Smoke Test").apply {
        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        contentPane.layout = BorderLayout()
        size = Dimension(400, 300)
        isVisible = true
        toFront()
        requestFocus()
      }
    }
  }

  @AfterEach
  fun tearDown(): Unit = runBlocking {
    scope?.coroutineContext?.job?.cancelAndJoin()
    withContext(Dispatchers.EDT) { frame?.dispose() }
    frame = null
    scope = null
  }

  @Test
  fun evaluateJavaScript_returnsResult(): Unit = runSmokeTest {
    val panel = createPanel(scope!!)
    attach(panel)
    panel.webView.loadHtml(/*language=HTML*/ "<html><body>test</body></html>")

    waitForJavaScript(panel.webView, "1 + 1", "2", "JavaScript evaluation did not return the expected result")
  }

  @Test
  fun loadHtml_beforeAttach_isAppliedAfterAttach(): Unit = runSmokeTest {
    val panel = createPanel(scope!!)
    panel.webView.loadHtml(/*language=HTML*/ "<html><body>queued-before-attach</body></html>")

    attach(panel)

    waitForJavaScript(
      panel.webView,
      "document.body.textContent.trim() === 'queued-before-attach'",
      "true",
      "HTML queued before host attachment was not applied",
    )
  }

  @Test
  fun loadAsset_servesDirectoryBundle(@TempDir tempDir: Path): Unit = runSmokeTest {
    Files.writeString(tempDir.resolve("index.html"), ASSET_SMOKE_HTML)
    Files.writeString(tempDir.resolve("view.js"), /*language=JavaScript*/ "window.__assetValue = 'from-asset';")

    val panel = createPanel(scope!!)
    attach(panel)
    panel.webView.loadAsset(WebViewAssetRoot.fromDirectory(tempDir))

    waitForJavaScript(
      panel.webView,
      """
        window.__assetValue === 'from-asset' && window.__WVI__ && Boolean(window.__WVI__.transport())
      """.trimIndent(),
      "true",
      "Asset directory bundle did not load through the WebView asset handler",
    )
  }

  @Test
  fun facade_survives_host_detach_reattach(): Unit = runSmokeTest {
    val panel = createPanel(scope!!)
    attach(panel)
    panel.webView.loadHtml(/*language=HTML*/ "<html><body>phase1</body></html>")
    waitForJavaScript(panel.webView, "document.body.textContent.trim() === 'phase1'", "true", "Initial page did not load")
    assertEquals(
      "true",
      javaScriptResultContent(panel.webView.evaluateJavaScript(/*language=JavaScript*/ "window.__wviReattachMarker = 'alive'; true").value),
    )

    withContext(Dispatchers.EDT) {
      frame!!.contentPane.removeAll()
      frame!!.revalidate()
    }
    delay(300.milliseconds)

    attach(panel)

    waitForJavaScript(panel.webView, "document.body.textContent.trim() === 'phase1'", "true", "Page state was not retained after reattach")
    waitForJavaScript(panel.webView, "window.__wviReattachMarker === 'alive'", "true", "JavaScript state was not retained after reattach")
    assertEquals("4", javaScriptResultContent(panel.webView.evaluateJavaScript(/*language=JavaScript*/ "2 + 2").value))
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  fun facade_survives_move_from_disposed_floating_window(): Unit = runSmokeTest {
    val panel = createPanel(scope!!)
    attach(panel)
    panel.webView.loadHtml(/*language=HTML*/ "<html><body>floating-window-state</body></html>")
    waitForJavaScript(
      panel.webView,
      /*language=JavaScript*/ "document.body.textContent.trim() === 'floating-window-state'",
      "true",
      "Initial page did not load",
    )
    assertEquals(
      "true",
      javaScriptResultContent(
        panel.webView.evaluateJavaScript(/*language=JavaScript*/ "window.__wviFloatingMarker = 'alive'; true").value,
      ),
    )

    val floating = withContext(Dispatchers.EDT) {
      JDialog(frame!!, "WebView Runtime Floating Host", false).apply {
        contentPane.layout = BorderLayout()
        size = Dimension(400, 300)
        frame!!.contentPane.remove(panel.component)
        frame!!.revalidate()
        frame!!.repaint()
        contentPane.add(panel.component, BorderLayout.CENTER)
        isVisible = true
      }
    }

    try {
      waitForJavaScript(
        panel.webView,
        /*language=JavaScript*/ "window.__wviFloatingMarker === 'alive'",
        "true",
        "JavaScript state was not retained after moving to a floating window",
      )

      withContext(Dispatchers.EDT) {
        floating.contentPane.remove(panel.component)
        floating.dispose()
        frame!!.contentPane.add(panel.component, BorderLayout.CENTER)
        frame!!.revalidate()
        frame!!.repaint()
      }

      waitForJavaScript(
        panel.webView,
        /*language=JavaScript*/ "window.__wviFloatingMarker === 'alive'",
        "true",
        "JavaScript state was not retained after docking and disposing the floating window",
      )
    }
    finally {
      withContext(Dispatchers.EDT) { floating.dispose() }
    }
  }

  @Test
  fun webMessageReceived_reachesBus(): Unit = runSmokeTest {
    val panel = createPanel(scope!!)
    val ready = CompletableDeferred<Unit>()
    val registration = panel.interop.messageBus.registerNotificationHandler(ReadyNotification) { _, _ ->
      ready.complete(Unit)
    }

    try {
      attach(panel)
      panel.webView.loadHtml(messageSmokeHtml(ReadyNotification.method))

      withTimeout(5.seconds) {
        ready.await()
      }
    }
    finally {
      registration.close()
    }
  }

  @Test
  fun applicationMode_preventsContextMenuDefault(): Unit = runSmokeTest {
    val panel = createPanel(scope!!)
    attach(panel)
    panel.webView.loadHtml(/*language=HTML*/ "<html><body>menu target</body></html>")

    waitForJavaScript(
      panel.webView,
      """
        (function() {
          const event = new MouseEvent('contextmenu', { bubbles: true, cancelable: true });
          document.body.dispatchEvent(event);
          return String(event.defaultPrevented);
        })()
      """.trimIndent(),
      "true",
      "Application mode did not prevent the default context menu",
    )
  }

  @Test
  @Suppress("HtmlDeprecatedAttribute")
  fun applicationMode_disablesInputAssistForFormControls(): Unit = runSmokeTest {
    val panel = createPanel(scope!!)
    attach(panel)
    panel.webView.loadHtml(
      /*language=HTML*/
      """
        <html>
        <body>
          <input id="existing" autocomplete="email" autocorrect="on" autocapitalize="sentences" spellcheck="true">
        </body>
        </html>
      """.trimIndent(),
    )

    waitForJavaScript(
      panel.webView,
      """
        (function() {
          const existing = document.getElementById('existing');
          return inputAssistState(existing);

          function inputAssistState(element) {
            const attributeNames = ['autocomplete', 'autocorrect', 'autocapitalize', 'spellcheck'];
            return attributeNames.map(name => element.getAttribute(name)).join('|') + '|' + String(element.spellcheck);
          }
        })()
      """.trimIndent(),
      "off|off|off|false|false",
      "Initial form control input assist attributes were not disabled",
    )

    assertEquals(
      "created",
      javaScriptResultContent(
        panel.webView.evaluateJavaScript(
          /*language=JavaScript*/
          """
          (function() {
            const dynamic = document.createElement('input');
            dynamic.id = 'dynamic';
            setInputAssistEnabled(dynamic);
            document.body.appendChild(dynamic);

            const host = document.createElement('div');
            host.id = 'shadow-host';
            document.body.appendChild(host);

            const shadow = host.attachShadow({ mode: 'open' });
            const shadowInput = document.createElement('input');
            shadowInput.id = 'shadow';
            setInputAssistEnabled(shadowInput);
            shadow.appendChild(shadowInput);

            return 'created';

            function setInputAssistEnabled(element) {
              element.setAttribute('autocomplete', 'email');
              element.setAttribute('autocorrect', 'on');
              element.setAttribute('autocapitalize', 'sentences');
              element.setAttribute('spellcheck', 'true');
              element.spellcheck = true;
            }
          })()
          """.trimIndent(),
        ).value,
      ),
    )

    waitForJavaScript(
      panel.webView,
      """
        (function() {
          return [
            inputAssistState(document.getElementById('existing')),
            inputAssistState(document.getElementById('dynamic')),
            inputAssistState(document.getElementById('shadow-host').shadowRoot.getElementById('shadow'))
          ].join(';');

          function inputAssistState(element) {
            const attributeNames = ['autocomplete', 'autocorrect', 'autocapitalize', 'spellcheck'];
            return attributeNames.map(name => element.getAttribute(name)).join('|') + '|' + String(element.spellcheck);
          }
        })()
      """.trimIndent(),
      "off|off|off|false|false;off|off|off|false|false;off|off|off|false|false",
      "Dynamic and shadow-root form control input assist attributes were not disabled",
    )

    val eventResult = javaScriptResultContent(
      panel.webView.evaluateJavaScript(
        /*language=JavaScript*/
        """
        (function() {
          const existing = document.getElementById('existing');
          const dynamic = document.getElementById('dynamic');
          const shadowInput = document.getElementById('shadow-host').shadowRoot.getElementById('shadow');
          for (const element of [existing, dynamic, shadowInput]) {
            setInputAssistEnabled(element);
          }

          const eventLog = [];
          document.addEventListener('focusin', function() { eventLog.push('focusin'); }, { once: true });
          document.addEventListener('beforeinput', function() { eventLog.push('beforeinput'); }, { once: true });
          document.addEventListener('input', function() { eventLog.push('input'); }, { once: true });

          const focusEvent = new Event('focusin', { bubbles: true, composed: true });
          const beforeInputEvent = new Event('beforeinput', { bubbles: true, composed: true, cancelable: true });
          const inputEvent = new Event('input', { bubbles: true, composed: true });
          shadowInput.dispatchEvent(focusEvent);
          shadowInput.dispatchEvent(beforeInputEvent);
          shadowInput.dispatchEvent(inputEvent);

          window['__inputAssistEventState'] = eventLog.join(',') + '|' + String(beforeInputEvent.defaultPrevented) + '|' + String(inputEvent.defaultPrevented);
          return window['__inputAssistEventState'];

          function setInputAssistEnabled(element) {
            element.setAttribute('autocomplete', 'email');
            element.setAttribute('autocorrect', 'on');
            element.setAttribute('autocapitalize', 'sentences');
            element.setAttribute('spellcheck', 'true');
            element.spellcheck = true;
          }
        })()
        """.trimIndent(),
      ).value,
    )
    assertEquals("focusin,beforeinput,input|false|false", eventResult)

    waitForJavaScript(
      panel.webView,
      """
        (function() {
          return [
            inputAssistState(document.getElementById('existing')),
            inputAssistState(document.getElementById('dynamic')),
            inputAssistState(document.getElementById('shadow-host').shadowRoot.getElementById('shadow')),
            window['__inputAssistEventState']
          ].join(';');

          function inputAssistState(element) {
            const attributeNames = ['autocomplete', 'autocorrect', 'autocapitalize', 'spellcheck'];
            return attributeNames.map(name => element.getAttribute(name)).join('|') + '|' + String(element.spellcheck);
          }
        })()
      """.trimIndent(),
      "off|off|off|false|false;off|off|off|false|false;off|off|off|false|false;focusin,beforeinput,input|false|false",
      "Event-driven input assist hardening did not restore the disabled state",
    )
  }

  @Test
  fun evaluateJavaScript_returnsResultForNestedHost(): Unit = runSmokeTest {
    val panel = createPanel(scope!!)
    withContext(Dispatchers.EDT) {
      frame!!.contentPane.removeAll()
      frame!!.contentPane.add(JPanel(BorderLayout()).apply {
        add(JPanel().apply {
          preferredSize = Dimension(10, 24)
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout()).apply {
          add(JPanel().apply {
            preferredSize = Dimension(16, 10)
          }, BorderLayout.WEST)
          add(JPanel(BorderLayout()).apply {
            add(panel.component, BorderLayout.CENTER)
          }, BorderLayout.CENTER)
          add(JPanel().apply {
            preferredSize = Dimension(12, 10)
          }, BorderLayout.EAST)
        }, BorderLayout.CENTER)
        add(JPanel().apply {
          preferredSize = Dimension(10, 28)
        }, BorderLayout.SOUTH)
      }, BorderLayout.CENTER)
      frame!!.revalidate()
    }

    panel.webView.loadHtml(/*language=HTML*/ "<html><body>nested</body></html>")

    waitForJavaScript(panel.webView, "document.body.textContent.trim() === 'nested'", "true", "Nested host page did not load")
  }

  @Test
  @EnabledOnOs(OS.MAC, OS.WINDOWS)
  fun clickingWebView_closesOpenSwingPopupMenu(): Unit = runSmokeTest {
    val panel = createPanel(scope!!)
    val popupMenu = JPopupMenu().apply {
      add(JMenuItem("Popup item"))
    }
    val popupAnchor = JButton("Popup anchor")

    withContext(Dispatchers.EDT) {
      frame!!.contentPane.removeAll()
      frame!!.contentPane.add(popupAnchor, BorderLayout.NORTH)
      frame!!.contentPane.add(panel.component, BorderLayout.CENTER)
      frame!!.size = Dimension(640, 420)
      frame!!.setLocation(80, 80)
      frame!!.validate()
      runCatching { Desktop.getDesktop().requestForeground(true) }
      frame!!.isAlwaysOnTop = true
      frame!!.toFront()
      frame!!.requestFocus()
      frame!!.isAlwaysOnTop = false
    }

    panel.webView.loadHtml(/*language=HTML*/ "<html><body>Click target</body></html>")
    waitForJavaScript(
      panel.webView,
      "document.body?.textContent?.trim() === 'Click target'",
      "true",
      "Swing popup smoke page did not load",
    )
    assertTrue(waitUntilShowing(popupAnchor), "Swing popup anchor did not become showing")
    assertTrue(waitUntilShowing(panel.component), "WebView host component did not become showing")
    assumeTrue(waitUntil({ frame!!.isActive && frame!!.isFocused }), "AWT Robot test window could not be activated")

    val robot = createRobotOrSkip()
    withContext(Dispatchers.EDT) {
      popupMenu.show(popupAnchor, 0, popupAnchor.height)
    }
    assertTrue(waitUntil { popupMenu.isVisible }, "Swing popup menu did not open")

    clickCenter(robot, panel.component)
    assertTrue(
      waitUntil { !popupMenu.isVisible },
      "Clicking the WebView must close an open Swing popup menu",
    )
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  fun editorPreviewSplitter_resizesWebViewPreviewInBothDirections(): Unit = runSmokeTest {
    val panel = createPanel(scope!!)
    val splitter = withContext(Dispatchers.EDT) {
      frame!!.glassPane = IdeGlassPaneImpl(frame!!.rootPane)
      OnePixelSplitter(false, 0.5f).apply {
        firstComponent = JPanel()
        secondComponent = panel.component
        frame!!.contentPane.removeAll()
        frame!!.contentPane.add(this, BorderLayout.CENTER)
        frame!!.size = Dimension(1_000, 600)
        frame!!.setLocation(80, 80)
        frame!!.validate()
        runCatching { Desktop.getDesktop().requestForeground(true) }
        frame!!.isAlwaysOnTop = true
        frame!!.toFront()
        frame!!.requestFocus()
      }
    }

    withTimeout(5.seconds) {
      while (withContext(Dispatchers.EDT) { !splitter.divider.isShowing || panel.component.width < 300 }) {
        delay(50.milliseconds)
      }
    }

    val robot = Robot().apply {
      autoDelay = 20
      isAutoWaitForIdle = true
    }
    val initialPreviewWidth = withContext(Dispatchers.EDT) { panel.component.width }

    dragDivider(robot, splitter, deltaX = -150)
    val expandedPreviewWidth = withContext(Dispatchers.EDT) { panel.component.width }
    assertTrue(
      expandedPreviewWidth > initialPreviewWidth + 80,
      "Dragging the divider left must expand the WebView preview; initial=$initialPreviewWidth, expanded=$expandedPreviewWidth",
    )

    dragDivider(robot, splitter, deltaX = 300)
    val shrunkPreviewWidth = withContext(Dispatchers.EDT) { panel.component.width }
    assertTrue(
      shrunkPreviewWidth < initialPreviewWidth - 80,
      "Dragging the divider right must shrink the WebView preview below its initial width; " +
      "initial=$initialPreviewWidth, expanded=$expandedPreviewWidth, shrunk=$shrunkPreviewWidth",
    )
  }

  @Test
  fun createAndCancel_100times_noLeak(): Unit = runBlocking {
    withTimeout(2.minutes) {
      repeat(100) { index ->
        @Suppress("RAW_SCOPE_CREATION") // Test: each iteration owns a short-lived WebView scope.
        val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val panel = createPanel(localScope)
        try {
          attach(panel)
          delay(100.milliseconds)
        }
        finally {
          localScope.coroutineContext.job.cancelAndJoin()
          withContext(Dispatchers.EDT) {
            frame!!.contentPane.removeAll()
            frame!!.revalidate()
          }
        }

        if (index % 10 == 9) {
          System.gc()
          delay(50.milliseconds)
        }
      }
    }
  }

  private suspend fun createPanel(panelScope: CoroutineScope): WebViewPanel {
    return withContext(Dispatchers.EDT) {
      createWebViewPanel(
        scope = panelScope,
        options = WebViewPanelOptions(
          assetRoot = WebViewAssetRoot.forView(WebViewRuntimeSmokeTest::class.java, "smoke"),
          debugName = "WebViewRuntimeSmokeTest",
        ),
      )
    }
  }

  private suspend fun attach(panel: WebViewPanel) {
    withContext(Dispatchers.EDT) {
      frame!!.contentPane.add(panel.component, BorderLayout.CENTER)
      frame!!.revalidate()
      frame!!.repaint()
    }
  }

  private suspend fun dragDivider(robot: Robot, splitter: OnePixelSplitter, deltaX: Int) {
    val (start, screenDeltaX) = withContext(Dispatchers.EDT) {
      val location = splitter.divider.locationOnScreen
      val start = Point(location.x + splitter.divider.width / 2, location.y + splitter.divider.height / 2)
      val scaleX = splitter.graphicsConfiguration.defaultTransform.scaleX
      start to (deltaX * scaleX).roundToInt()
    }
    robot.mouseMove(start.x, start.y)
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
    repeat(10) { index ->
      robot.mouseMove(start.x + screenDeltaX * (index + 1) / 10, start.y)
    }
    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    robot.waitForIdle()
  }

  private fun createRobotOrSkip(): Robot {
    return runCatching {
      Robot().apply {
        autoDelay = 20
        isAutoWaitForIdle = true
      }
    }.getOrElse { error ->
      assumeTrue(false, "AWT Robot is unavailable: ${error.message}")
      throw error
    }
  }

  private suspend fun clickCenter(robot: Robot, component: Component) {
    val center = withContext(Dispatchers.EDT) {
      val location = component.locationOnScreen
      Point(location.x + component.width / 2, location.y + component.height / 2)
    }
    robot.mouseMove(center.x, center.y)
    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    robot.waitForIdle()
  }

  private suspend fun waitUntilShowing(component: Component): Boolean {
    return waitUntil { component.isShowing }
  }

  private suspend fun waitUntil(condition: () -> Boolean): Boolean {
    return withTimeoutOrNull(5.seconds) {
      while (true) {
        if (withContext(Dispatchers.EDT) { condition() }) return@withTimeoutOrNull true
        delay(50.milliseconds)
      }
    } == true
  }

  private fun runSmokeTest(action: suspend CoroutineScope.() -> Unit): Unit = runBlocking {
    withTimeout(30.seconds) {
      action()
    }
  }

  private suspend fun waitForJavaScript(
    webView: WebView,
    @Language("JavaScript") script: String,
    expected: String,
    description: String,
  ) {
    var lastResult: String? = null
    var lastError: Throwable? = null
    val completedWithinTimeout = withTimeoutOrNull(10.seconds) {
      while (true) {
        runCatching { webView.evaluateJavaScript(script).value }
          .onSuccess { result ->
            lastError = null
            lastResult = result
            if (javaScriptResultMatches(result, expected)) return@withTimeoutOrNull true
          }
          .onFailure { error ->
            lastError = error
            lastResult = null
          }
        delay(100.milliseconds)
      }
    } == true
    val matched = completedWithinTimeout || javaScriptResultMatches(lastResult, expected)
    assertTrue(
      matched,
      "$description; expected=$expected, lastResult=$lastResult, lastError=${lastError?.message}, runtime=${webView.runtimeInfo.engineId}",
    )
  }

  private fun javaScriptResultMatches(result: String?, expected: String): Boolean {
    return javaScriptResultContent(result) == expected
  }

  private fun javaScriptResultContent(result: String?): String? {
    if (result == null) return null
    return runCatching { Json.parseToJsonElement(result).jsonPrimitive.content }
      .getOrDefault(result)
  }

  @Language("HTML")
  private fun messageSmokeHtml(method: String): String = """
    <html>
    <body>
      <script>
        (function() {
          const rawJson = JSON.stringify({jsonrpc: "2.0", method: "$method", params: {}});
          if (window.chrome && window.chrome.webview && typeof window.chrome.webview.postMessage === "function") {
            window.chrome.webview.postMessage(rawJson);
            return;
          }
          const webkitHandlers = window.webkit && window.webkit.messageHandlers;
          const webkitHandler = webkitHandlers && webkitHandlers.webviewIpc;
          if (webkitHandler && typeof webkitHandler.postMessage === "function") {
            webkitHandler.postMessage(rawJson);
            return;
          }
          if (typeof window.__wviJcefQuery === "function") {
            window.__wviJcefQuery({ request: rawJson });
          }
        })();
      </script>
    </body>
    </html>
  """.trimIndent()

  @Serializable
  private class EmptyWebViewPayload

  private object ReadyNotification : WebViewNotification<EmptyWebViewPayload> {
    override val method: String = "test/ready"
    override val paramsSerializer = EmptyWebViewPayload.serializer()
  }
}

@Suppress("HtmlUnknownTarget")
@Language("HTML")
private val ASSET_SMOKE_HTML: String = """
  <html>
  <body>
    <script src="/__webview/wvi-bridge.js"></script>
    <script src="./view.js"></script>
  </body>
  </html>
""".trimIndent()
