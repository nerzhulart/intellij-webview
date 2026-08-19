// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview

import com.intellij.jna.JnaLoader
import com.intellij.openapi.diagnostic.Logger
import com.intellij.testFramework.junit5.TestApplication
import io.github.nerzhulart.webview.impl.NativeBridgeLibraryAvailability
import io.github.nerzhulart.webview.impl.engine.WebViewEngine
import io.github.nerzhulart.webview.impl.engine.WebViewEngineCreationOptions
import io.github.nerzhulart.webview.impl.mac.MacWebViewEngine
import io.github.nerzhulart.webview.impl.mac.MacWebViewFirstResponderState
import io.github.nerzhulart.webview.impl.mac.MacWkWebViewEngineProvider
import io.github.nerzhulart.webview.impl.windows.WindowsWebView2EngineProvider
import io.github.nerzhulart.webview.impl.windows.winWebView2BridgeLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.awt.Desktop
import java.awt.Dimension
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@EnabledOnOs(OS.MAC, OS.WINDOWS)
@DisabledIfSystemProperty(named = "java.awt.headless", matches = "true")
@TestApplication
class WebViewFocusInteropRobotTest {

  private var frame: JFrame? = null
  private var scope: CoroutineScope? = null

  @BeforeEach
  fun setUp() {
    @Suppress("RAW_SCOPE_CREATION") // Test: no parent scope available without IJ platform
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    SwingUtilities.invokeAndWait {
      frame = JFrame("WebView Focus Interop Robot Test").apply {
        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        size = Dimension(640, 420)
        setLocation(80, 80)
        isVisible = true
        requestForeground(this)
        toFront()
      }
    }
  }

  @AfterEach
  fun tearDown() {
    scope?.cancel()
    SwingUtilities.invokeAndWait { frame?.dispose() }
    frame = null
    scope = null
  }

  @Test
  fun clickingWebViewClearsSwingFocusAndAllowsTypingBackInSwing(@TempDir tempDir: Path): Unit = runBlocking {
    val facade = createPlatformEngine(scope!!)
    try {
      WebViewFocusRobotTestSupport.runFocusInteropScenario(
        frame!!,
        scope!!,
        facade,
        tempDir,
      )
    }
    finally {
      facade.close()
    }
  }

  @Test
  @DisabledOnOs(OS.WINDOWS, disabledReason = "WebView2 does not report bare modifier transitions; the WH_KEYBOARD_LL fallback was removed")
  fun modifierDoubleClickInsideWebViewReachesAwt(@TempDir tempDir: Path): Unit = runBlocking {
    val engine = createPlatformEngine(scope!!)
    try {
      WebViewFocusRobotTestSupport.runModifierDoubleClickShortcutScenario(
        frame!!,
        scope!!,
        engine,
        tempDir,
      )
    }
    finally {
      engine.close()
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  fun browserTextNavigationShortcutsStayInsideWebView(): Unit = runBlocking {
    val tempDir = Files.createTempDirectory("webview-text-navigation-test")
    val engine = createPlatformEngine(scope!!)
    try {
      WebViewFocusRobotTestSupport.runBrowserTextNavigationScenario(
        frame!!,
        scope!!,
        engine,
        tempDir,
      )
    }
    finally {
      engine.close()
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  fun altF1InsideWebViewReachesAwtWithAltModifier(): Unit = runBlocking {
    val tempDir = Files.createTempDirectory("webview-alt-f1-test")
    val engine = createPlatformEngine(scope!!)
    try {
      WebViewFocusRobotTestSupport.runAltF1ShortcutScenario(
        frame!!,
        scope!!,
        engine,
        tempDir,
      )
    }
    finally {
      engine.close()
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  fun altF4InsideWebViewClosesFrame(): Unit = runBlocking {
    val tempDir = Files.createTempDirectory("webview-alt-f4-test")
    val engine = createPlatformEngine(scope!!)
    try {
      WebViewFocusRobotTestSupport.runAltF4WindowCloseScenario(
        frame!!,
        scope!!,
        engine,
        tempDir,
      )
    }
    finally {
      engine.close()
      tempDir.toFile().deleteRecursively()
    }
  }

  @Test
  @EnabledOnOs(OS.MAC)
  fun returningToSwingMovesMacFirstResponderOutsideWebView(@TempDir tempDir: Path): Unit = runBlocking {
    ensureJna()
    val facade = createMacEngine(scope!!)
    try {
      WebViewFocusRobotTestSupport.runMacFirstResponderFocusTransferScenario(
        frame = frame!!,
        scope = scope!!,
        engine = facade,
        tempDir = tempDir,
        assertNativeFocusInsideWebView = { assertFirstResponderInsideWebView(facade) },
        assertNativeFocusReadyForSwingTyping = { assertFirstResponderOutsideWebView(facade) },
      )
    }
    finally {
      facade.close()
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  fun selectingTextInWebViewWithoutTabbablesDoesNotBounceFocusBackToSwing(@TempDir tempDir: Path): Unit = runBlocking {
    val engine = createPlatformEngine(scope!!)
    try {
      WebViewFocusRobotTestSupport.runNonTabbableSelectionScenario(
        frame!!,
        scope!!,
        engine,
        tempDir,
      )
    }
    finally {
      engine.close()
    }
  }

  @Test
  @EnabledOnOs(OS.WINDOWS)
  fun clickingBadComboPopupThenSwingFieldClosesPopupAndMovesFocusBack(@TempDir tempDir: Path): Unit = runBlocking {
    val engine = createPlatformEngine(scope!!)
    try {
      WebViewFocusRobotTestSupport.runBadComboPopupThenSwingRefocusScenario(
        frame!!,
        scope!!,
        engine,
        tempDir,
      )
    }
    finally {
      engine.close()
    }
  }

  private fun createPlatformEngine(scope: CoroutineScope): WebViewEngine {
    val osName = System.getProperty("os.name", "")
    return when {
      osName.startsWith("Mac", ignoreCase = true) -> {
        createMacEngine(scope)
      }
      osName.startsWith("Windows", ignoreCase = true) -> {
        assumeTrue(nativeBridgeAvailable(), "WinWebView2Bridge DLL is not built; run community/plugins/ui.webview/native/WinWebView2Bridge/build.ps1")
        WindowsWebView2EngineProvider().createEngine(scope, webViewEngineCreationOptions())
      }
      else -> error("Unsupported OS for WebView focus interop Robot test: $osName")
    }
  }

  private fun createMacEngine(scope: CoroutineScope): MacWebViewEngine {
    ensureJna()
    return MacWkWebViewEngineProvider().createEngine(scope, webViewEngineCreationOptions()) as MacWebViewEngine
  }

  private fun webViewEngineCreationOptions(): WebViewEngineCreationOptions {
    return WebViewEngineCreationOptions(
      debugName = null,
    )
  }

  private fun ensureJna() {
    if (!JnaLoader.isLoaded()) {
      JnaLoader.load(Logger.getInstance(WebViewFocusInteropRobotTest::class.java))
    }
  }

  private fun nativeBridgeAvailable(): Boolean {
    return winWebView2BridgeLibrary.availability() is NativeBridgeLibraryAvailability.Available
  }

  private suspend fun assertFirstResponderInsideWebView(engine: MacWebViewEngine) {
    var lastState: MacWebViewFirstResponderState? = null
    val matched = withTimeoutOrNull(2.seconds) {
      while (true) {
        lastState = engine.firstResponderState()
        val state = lastState
        if (state != null && state.hasResponder && state.isInsideWebView) return@withTimeoutOrNull true
        delay(50.milliseconds)
      }
    } == true
    assertTrue(matched, "macOS first responder did not move inside WKWebView; lastState=$lastState")
  }

  private suspend fun assertFirstResponderOutsideWebView(engine: MacWebViewEngine) {
    var lastState: MacWebViewFirstResponderState? = null
    val matched = withTimeoutOrNull(2.seconds) {
      while (true) {
        lastState = engine.firstResponderState()
        val state = lastState
        if (state != null && state.hasResponder && !state.isInsideWebView) return@withTimeoutOrNull true
        delay(50.milliseconds)
      }
    } == true
    assertTrue(matched, "macOS first responder did not move back outside WKWebView; lastState=$lastState")
  }

  private fun requestForeground(frame: JFrame) {
    runCatching {
      Desktop.getDesktop().requestForeground(true)
    }
    frame.isAlwaysOnTop = true
    frame.toFront()
    frame.isAlwaysOnTop = false
  }
}
