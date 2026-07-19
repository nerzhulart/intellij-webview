// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.webview

import com.intellij.jna.JnaLoader
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.webview.api.WebViewAssetRoot
import com.intellij.ui.webview.api.WebViewNotification
import com.intellij.ui.webview.impl.NativeBridgeLibraryAvailability
import com.intellij.ui.webview.impl.SwingWebViewHostPanel
import com.intellij.ui.webview.impl.WebViewController
import com.intellij.ui.webview.impl.WebViewHostEventSink
import com.intellij.ui.webview.impl.linux.LinuxWaylandWindowUtil
import com.intellij.ui.webview.impl.linux.LinuxWebKitBackend
import com.intellij.ui.webview.impl.linux.LinuxWebKitGtkBridge
import com.intellij.ui.webview.impl.linux.createLinuxWebKitController
import com.intellij.ui.webview.impl.linux.linuxWebKitGtkBridgeLibrary
import com.intellij.ui.webview.impl.mac.createMacWkWebViewController
import com.intellij.ui.webview.impl.rpc.WebViewMessageBusImpl
import com.intellij.ui.webview.impl.windows.createWinWebViewController
import com.intellij.ui.webview.impl.windows.winWebView2BridgeLibrary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestApplication
@DisabledIfSystemProperty(named = "java.awt.headless", matches = "true")
@Suppress("JSUnresolvedVariable")
internal class WebViewRuntimeSmokeTest {
  private var runtime: PlatformWebViewRuntime? = null
  private var frame: JFrame? = null
  private var scope: CoroutineScope? = null
  private var activeHost: SwingWebViewHostPanel? = null

  companion object {
    private val longRunningThreadsDisposable = Disposer.newDisposable("WebViewRuntimeSmokeTest long-running threads")

    @JvmStatic
    @BeforeAll
    fun setUpClass() {
      registerLongRunningThreads()
    }

    @JvmStatic
    @AfterAll
    fun tearDownClass() {
      if (linuxNativeBridgeAvailable()) {
        LinuxWebKitGtkBridge.shutdownRuntimeForTests()
      }
      Disposer.dispose(longRunningThreadsDisposable)
    }

    private fun registerLongRunningThreads() {
      val trackerClass = Class.forName("com.intellij.testFramework.common.ThreadLeakTracker")
      val method = trackerClass.getMethod("longRunningThreadCreated", Disposable::class.java, Array<String>::class.java)
      method.invoke(
        null,
        longRunningThreadsDisposable,
        arrayOf("AWT-Wayland", "WLKeyboard.KeyRepeatManager", "WebView2-Thread"),
      )
    }

    private fun linuxNativeBridgeAvailable(): Boolean {
      return linuxWebKitGtkBridgeLibrary.availability() is NativeBridgeLibraryAvailability.Available
    }
  }

  @BeforeEach
  fun setUp() {
    val selectedRuntime = currentRuntimeOrSkip()
    selectedRuntime.assumeAvailable()
    runtime = selectedRuntime

    @Suppress("RAW_SCOPE_CREATION") // Test: no parent scope available without product code.
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    SwingUtilities.invokeAndWait {
      frame = JFrame("${selectedRuntime.id} WebView Runtime Smoke Test").apply {
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
  fun tearDown() {
    scope?.cancel()
    SwingUtilities.invokeAndWait { frame?.dispose() }
    frame = null
    scope = null
    runtime = null
  }

  @Test
  fun evaluateJavaScript_returnsResult(): Unit = runSmokeTest {
    val engine = createEngine()
    try {
      attach(engine)
      engine.loadHtml(/*language=HTML*/ "<html><body>test</body></html>")

      waitForJavaScript(engine, "1 + 1", "2", "JavaScript evaluation did not return the expected result")
    }
    finally {
      engine.close()
    }
  }

  @Test
  fun loadHtml_beforeAttach_isAppliedAfterAttach(): Unit = runSmokeTest {
    val engine = createEngine()
    try {
      engine.loadHtml(/*language=HTML*/ "<html><body>queued-before-attach</body></html>")

      attach(engine)

      waitForJavaScript(
        engine,
        "document.body.textContent.trim() === 'queued-before-attach'",
        "true",
        "HTML queued before host attachment was not applied",
      )
    }
    finally {
      engine.close()
    }
  }

  @Test
  fun loadAsset_servesDirectoryBundle(@TempDir tempDir: Path): Unit = runSmokeTest {
    assumeTrue(currentRuntime().supportsAssetServing, "The current WebView runtime does not support asset serving")
    Files.writeString(tempDir.resolve("index.html"), ASSET_SMOKE_HTML)
    Files.writeString(tempDir.resolve("view.js"), /*language=JavaScript*/ "window.__assetValue = 'from-asset';")

    val engine = createEngine()
    try {
      attach(engine)

      engine.loadAsset(WebViewAssetRoot.fromDirectory(tempDir))

      waitForJavaScript(
        engine,
        """
          window.__assetValue === 'from-asset' && window.__WVI__ && Boolean(window.__WVI__.transport())
        """.trimIndent(),
        "true",
        "Asset directory bundle did not load through the WebView asset handler",
      )
    }
    finally {
      engine.close()
    }
  }

  @Test
  fun close_isIdempotent(): Unit = runSmokeTest {
    val engine = createEngine()
    try {
      attach(engine)
      engine.loadHtml(/*language=HTML*/ "<html><body>close</body></html>")
      waitForJavaScript(engine, "document.body.textContent.trim() === 'close'", "true", "Close preflight page did not load")

      engine.close()
      engine.close()
    }
    finally {
      engine.close()
    }
  }

  @Test
  fun evaluateJavaScript_afterClose_returnsNull(): Unit = runSmokeTest {
    val engine = createEngine()
    try {
      attach(engine)
      engine.loadHtml(/*language=HTML*/ "<html><body>close</body></html>")
      waitForJavaScript(engine, "document.body.textContent.trim() === 'close'", "true", "Close preflight page did not load")

      engine.close()

      assertNull(engine.evaluateJavaScript(/*language=JavaScript*/ "1 + 1"))
    }
    finally {
      engine.close()
    }
  }

  @Test
  fun facade_replays_document_after_host_detach_reattach(): Unit = runSmokeTest {
    val engine = createEngine()
    try {
      attach(engine)

      engine.loadHtml(/*language=HTML*/ "<html><body>phase1</body></html>")
      waitForJavaScript(engine, "document.body.textContent.trim() === 'phase1'", "true", "Initial page did not load")
      assertEquals("true", engine.evaluateJavaScript(/*language=JavaScript*/ "window.__wviReattachMarker = 'alive'; true"))

      SwingUtilities.invokeAndWait {
        frame!!.contentPane.removeAll()
        frame!!.revalidate()
      }
      delay(300.milliseconds)

      attach(engine)

      waitForJavaScript(engine, "document.body.textContent.trim() === 'phase1'", "true", "Page state was not retained after reattach")
      assertEquals("false", engine.evaluateJavaScript("window.__wviReattachMarker === 'alive'"))
      assertEquals("4", engine.evaluateJavaScript(/*language=JavaScript*/ "2 + 2"))
    }
    finally {
      engine.close()
    }
  }

  @Test
  fun webMessageReceived_reachesBus(): Unit = runSmokeTest {
    val engine = createEngine()
    val bus = WebViewMessageBusImpl(scope!!, engine)
    engine.connectMessageBus { rawJson -> bus.transferFromJs(rawJson) }
    val ready = CompletableDeferred<Unit>()
    bus.registerNotificationHandler(ReadyNotification) { _, _ ->
      ready.complete(Unit)
    }

    try {
      attach(engine)

      engine.loadHtml(messageSmokeHtml(ReadyNotification.method))

      withTimeout(5.seconds) {
        ready.await()
      }
    }
    finally {
      bus.close()
      engine.close()
    }
  }

  @Test
  fun applicationMode_preventsContextMenuDefault(): Unit = runSmokeTest {
    assumeTrue(currentRuntime() is MacRuntime, "Application-mode DOM hardening smoke is currently WKWebView-specific")
    val engine = createEngine()
    try {
      attach(engine)
      engine.loadHtml(/*language=HTML*/ "<html><body>menu target</body></html>")

      waitForJavaScript(
        engine,
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
    finally {
      engine.close()
    }
  }

  @Test
  @Suppress("HtmlDeprecatedAttribute")
  fun applicationMode_disablesInputAssistForFormControls(): Unit = runSmokeTest {
    assumeTrue(currentRuntime() is MacRuntime, "Application-mode DOM hardening smoke is currently WKWebView-specific")
    val engine = createEngine()
    try {
      attach(engine)
      engine.loadHtml(
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
        engine,
        """
          (function() {
            const existing = document.getElementById('existing');
            return inputAssistState(existing) === 'off|off|off|false|false';

            function inputAssistState(element) {
              const attributeNames = ['autocomplete', 'autocorrect', 'autocapitalize', 'spellcheck'];
              return attributeNames.map(name => element.getAttribute(name)).join('|') + '|' + String(element.spellcheck);
            }
          })()
        """.trimIndent(),
        "true",
        "Initial form control input assist attributes were not disabled",
      )

      assertEquals(
        "created",
        engine.evaluateJavaScript(
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
        ),
      )

      waitForJavaScript(
        engine,
        """
          (function() {
            return [
              inputAssistState(document.getElementById('existing')),
              inputAssistState(document.getElementById('dynamic')),
              inputAssistState(document.getElementById('shadow-host').shadowRoot.getElementById('shadow'))
            ].join(';') === 'off|off|off|false|false;off|off|off|false|false;off|off|off|false|false';

            function inputAssistState(element) {
              const attributeNames = ['autocomplete', 'autocorrect', 'autocapitalize', 'spellcheck'];
              return attributeNames.map(name => element.getAttribute(name)).join('|') + '|' + String(element.spellcheck);
            }
          })()
        """.trimIndent(),
        "true",
        "Dynamic and shadow-root form control input assist attributes were not disabled",
      )
    }
    finally {
      engine.close()
    }
  }

  @Test
  fun evaluateJavaScript_returnsResultForNestedHost(): Unit = runSmokeTest {
    assumeTrue(currentRuntime() is MacRuntime, "Nested host smoke preserves the existing WKWebView regression coverage")
    val engine = createEngine()
    try {
      SwingUtilities.invokeAndWait {
        val host = createHost(engine)
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
              add(host, BorderLayout.CENTER)
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

      engine.loadHtml(/*language=HTML*/ "<html><body>nested</body></html>")

      waitForJavaScript(engine, "document.body.textContent.trim() === 'nested'", "true", "Nested host page did not load")
    }
    finally {
      engine.close()
    }
  }

  @Test
  fun createAndClose_100times_noLeak(): Unit = runSmokeTest {
    assumeTrue(currentRuntime() is MacRuntime, "Repeated create/close smoke preserves the existing WKWebView regression coverage")
    repeat(100) { index ->
      @Suppress("RAW_SCOPE_CREATION") // Test: each iteration owns a short-lived WebView scope.
      val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      var localHost: SwingWebViewHostPanel? = null
      val engine = currentRuntime().createController(
        localScope,
        WebViewHostEventSink { event -> localHost?.handleHostEvent(event) ?: false },
      )
      try {
        SwingUtilities.invokeAndWait {
          val host = SwingWebViewHostPanel(localScope, engine)
          localHost = host
          frame!!.contentPane.add(host)
          frame!!.revalidate()
        }
        delay(100.milliseconds)
      }
      finally {
        engine.close()
        localScope.cancel()
        SwingUtilities.invokeAndWait {
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

  @Test
  fun waylandSnapshotHost_paintsLoadedHtml(): Unit = runSmokeTest {
    assumeTrue(currentRuntime() is LinuxRuntime, "Wayland snapshot rendering smoke is Linux-specific")
    val engine = createEngine()
    val host = createHost(engine)
    try {
      SwingUtilities.invokeAndWait {
        frame!!.contentPane.removeAll()
        frame!!.contentPane.add(host, BorderLayout.CENTER)
        frame!!.revalidate()
        frame!!.repaint()
      }

      engine.loadHtml(greenHtml())

      waitForPaintedRgb(host, 0x12AB34)
    }
    finally {
      engine.close()
    }
  }

  private fun attach(engine: WebViewController): SwingWebViewHostPanel {
    lateinit var host: SwingWebViewHostPanel
    SwingUtilities.invokeAndWait {
      host = createHost(engine)
      frame!!.contentPane.add(host, BorderLayout.CENTER)
      frame!!.revalidate()
      frame!!.repaint()
    }
    return host
  }

  private fun createHost(engine: WebViewController): SwingWebViewHostPanel {
    return SwingWebViewHostPanel(scope!!, engine).also { activeHost = it }
  }

  private fun createEngine(): WebViewController {
    return currentRuntime().createController(
      scope!!,
      WebViewHostEventSink { event -> activeHost?.handleHostEvent(event) ?: false },
    )
  }

  private fun currentRuntime(): PlatformWebViewRuntime {
    return checkNotNull(runtime) { "WebView runtime was not initialized for the current test" }
  }

  private suspend fun WebViewController.loadHtml(@Language("HTML") html: String) {
    loadHtml(html, null)
  }

  private suspend fun WebViewController.loadAsset(root: WebViewAssetRoot) {
    loadAsset(root, com.intellij.ui.webview.api.WebViewAssetPath.indexHtml(), null)
  }

  private fun runSmokeTest(action: suspend CoroutineScope.() -> Unit): Unit = runBlocking {
    withTimeout(15.seconds) {
      action()
    }
  }

  private suspend fun waitForJavaScript(
    engine: WebViewController,
    @Language("JavaScript") script: String,
    expected: String,
    description: String,
  ) {
    var lastResult: String? = null
    val matched = withTimeoutOrNull(5.seconds) {
      while (true) {
        lastResult = engine.evaluateJavaScript(script)
        if (lastResult == expected) return@withTimeoutOrNull true
        delay(100.milliseconds)
      }
    } == true
    assertTrue(matched, "$description; expected=$expected, lastResult=$lastResult, runtime=${currentRuntime().id}")
  }

  private suspend fun waitForPaintedRgb(host: Component, expectedRgb: Int) {
    var lastRgb: Int? = null
    val matched = withTimeoutOrNull(5.seconds) {
      while (true) {
        val rgb = CompletableDeferred<Int>()
        SwingUtilities.invokeLater {
          val image = BufferedImage(host.width, host.height, BufferedImage.TYPE_INT_ARGB)
          val graphics = image.createGraphics()
          try {
            host.paint(graphics)
            rgb.complete(image.getRGB(host.width / 2, host.height / 2) and 0x00FFFFFF)
          }
          finally {
            graphics.dispose()
          }
        }
        lastRgb = rgb.await()
        if (lastRgb == expectedRgb) return@withTimeoutOrNull true
        delay(100.milliseconds)
      }
    } == true
    assertTrue(matched, "Host did not paint the expected WebView snapshot color; expectedRgb=$expectedRgb, lastRgb=$lastRgb")
  }

  private sealed interface PlatformWebViewRuntime {
    val id: String
    val supportsAssetServing: Boolean

    fun assumeAvailable()

    fun createController(scope: CoroutineScope, hostEventSink: WebViewHostEventSink): WebViewController
  }

  private object MacRuntime : PlatformWebViewRuntime {
    override val id: String = "macOS"
    override val supportsAssetServing: Boolean = true

    override fun assumeAvailable() {
      if (!JnaLoader.isLoaded()) {
        JnaLoader.load(Logger.getInstance(WebViewRuntimeSmokeTest::class.java))
      }
    }

    override fun createController(scope: CoroutineScope, hostEventSink: WebViewHostEventSink): WebViewController =
      createMacWkWebViewController(scope, emptyList(), hostEventSink)
  }

  private object WindowsRuntime : PlatformWebViewRuntime {
    override val id: String = "Windows"
    override val supportsAssetServing: Boolean = true

    override fun assumeAvailable() {
      assumeTrue(
        winWebView2BridgeLibrary.availability() is NativeBridgeLibraryAvailability.Available,
        "WinWebView2Bridge DLL is not built; run community/plugins/ui.webview/native/WinWebView2Bridge/build.ps1",
      )
    }

    override fun createController(scope: CoroutineScope, hostEventSink: WebViewHostEventSink): WebViewController =
      createWinWebViewController(scope, hostEventSink = hostEventSink)
  }

  private object LinuxRuntime : PlatformWebViewRuntime {
    override val id: String = "Linux WebKitGTK Wayland"
    override val supportsAssetServing: Boolean = false

    override fun assumeAvailable() {
      assumeTrue(
        LinuxWaylandWindowUtil.isSupportedToolkit(),
        "Linux WebKitGTK WebView smoke tests require WLToolkit/Wayland",
      )
      assumeTrue(
        linuxNativeBridgeAvailable(),
        "LinuxWebKitGtkBridge is not built; run cargo build in community/plugins/ui.webview/native/LinuxWebKitGtkBridge",
      )
    }

    override fun createController(scope: CoroutineScope, hostEventSink: WebViewHostEventSink): WebViewController =
      createLinuxWebKitController(scope, LinuxWebKitBackend.WaylandSnapshot)
  }

  private fun currentRuntimeOrSkip(): PlatformWebViewRuntime {
    return when {
      SystemInfo.isMac -> MacRuntime
      SystemInfo.isWindows -> WindowsRuntime
      SystemInfo.isLinux -> LinuxRuntime
      else -> {
        assumeTrue(false, "WebView runtime smoke tests support macOS, Windows, and Linux only")
        error("Unsupported OS for WebView runtime smoke tests: ${System.getProperty("os.name")}")
      }
    }
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

  @Language("HTML")
  private fun greenHtml(): String = """
    <html>
    <body style="margin: 0; width: 100vw; height: 100vh; background: rgb(18, 171, 52);"></body>
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
