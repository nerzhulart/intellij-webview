// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.TestLoggerFactory
import com.intellij.testFramework.junit5.RegistryKey
import com.intellij.testFramework.junit5.TestApplication
import io.github.nerzhulart.webview.api.WebBrowserPanelOptions
import io.github.nerzhulart.webview.api.WebViewAssetPath
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.api.WebViewBrowserError
import io.github.nerzhulart.webview.api.WebViewBrowserState
import io.github.nerzhulart.webview.impl.engine.WebViewCreationOptions
import io.github.nerzhulart.webview.impl.engine.WebViewEngineAvailability
import io.github.nerzhulart.webview.impl.engine.WebViewEngineCapabilities
import io.github.nerzhulart.webview.impl.engine.WebViewEngineId
import io.github.nerzhulart.webview.impl.engine.WebViewEngineKind
import io.github.nerzhulart.webview.impl.engine.WebViewEngineRequirements
import io.github.nerzhulart.webview.api.WebViewPanelOptions
import io.github.nerzhulart.webview.impl.engine.WebViewRuntime
import io.github.nerzhulart.webview.impl.WebViewApplicationModeScripts
import io.github.nerzhulart.webview.impl.WebViewConsoleCapture
import io.github.nerzhulart.webview.impl.SwingWebViewHostPanel
import io.github.nerzhulart.webview.impl.WebViewFocusEntrySink
import io.github.nerzhulart.webview.impl.WebViewJsMessageReceiver
import io.github.nerzhulart.webview.impl.engine.WebViewEngine
import io.github.nerzhulart.webview.impl.engine.WebViewEngineCreationOptions
import io.github.nerzhulart.webview.impl.engine.WebViewEngineProvider
import io.github.nerzhulart.webview.impl.engine.WebViewFeatures
import io.github.nerzhulart.webview.impl.engine.WebViewNavigationListener
import io.github.nerzhulart.webview.impl.engine.registerWebViewFocusExitHandler
import io.github.nerzhulart.webview.impl.rpc.WebViewMessageBusImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.net.URI
import java.nio.file.Path
import java.time.Instant
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.time.Duration.Companion.seconds

@TestApplication
internal class WebViewRuntimeTest {
  @Test
  fun createWebView_selectsProviderByPreferenceAndRequirements(): Unit = runWebViewTest {
    val rejected = FakeProvider(
      id = WebViewEngineId.SYSTEM_WINDOWS,
      capabilities = capabilities(assetServing = false),
      priority = 10,
    )
    val selected = FakeProvider(
      id = WebViewEngineId.JCEF,
      capabilities = capabilities(assetServing = true),
      priority = 20,
    )
    val runtime = WebViewRuntime().apply { providers = listOf(rejected, selected) }

    val webView = runtime.createWebView(
      scope = this,
      options = WebViewCreationOptions(
        requirements = WebViewEngineRequirements(assetServing = true),
      ),
    )

    assertEquals(WebViewEngineId.JCEF, webView.runtimeInfo.engineId)
    assertEquals(0, rejected.createCount)
    assertEquals(1, selected.createCount)
  }

  @Test
  fun createWebView_reportsCapabilitiesWhenNoProviderSatisfiesRequirements(): Unit = runWebViewTest {
    val runtime = WebViewRuntime().apply {
      providers = listOf(
        FakeProvider(
          id = WebViewEngineId.SYSTEM_WINDOWS,
          capabilities = capabilities(assetServing = false),
          priority = 10,
        ),
      )
    }

    val error = runCatching {
      runtime.createWebView(
        scope = this,
        options = WebViewCreationOptions(
          requirements = WebViewEngineRequirements(assetServing = true),
        ),
      )
    }.exceptionOrNull()

    assertTrue(error is IllegalStateException)
    assertTrue(error!!.message!!.contains("assetServing"), error.message)
  }

  @Test
  fun createWebView_continuesWhenProviderAvailabilityHasLinkageError(): Unit = runWebViewTest {
    val broken = FakeProvider(
      id = WebViewEngineId.SYSTEM_WINDOWS,
      capabilities = capabilities(assetServing = true),
      priority = 10,
      availabilityFailure = NoClassDefFoundError("com/intellij/ui/jcef/JBCefApp"),
    )
    val selected = FakeProvider(
      id = WebViewEngineId.JCEF,
      capabilities = capabilities(assetServing = true),
      priority = 20,
    )
    val runtime = WebViewRuntime().apply { providers = listOf(broken, selected) }

    val webView = runtime.createWebView(scope = this)

    assertEquals(WebViewEngineId.JCEF, webView.runtimeInfo.engineId)
    assertEquals(0, broken.createCount)
    assertEquals(1, selected.createCount)
  }

  @Test
  fun createWebView_reportsProviderAvailabilityLinkageError(): Unit = runWebViewTest {
    val runtime = WebViewRuntime().apply {
      providers = listOf(
        FakeProvider(
          id = WebViewEngineId.JCEF,
          capabilities = capabilities(assetServing = true),
          priority = 10,
          availabilityFailure = NoClassDefFoundError("com/intellij/ui/jcef/JBCefApp"),
        ),
      )
    }

    val error = runCatching { runtime.createWebView(scope = this) }.exceptionOrNull()

    assertTrue(error is IllegalStateException)
    assertTrue(error!!.message!!.contains("availability check failed: java.lang.NoClassDefFoundError"), error.message)
    assertTrue(error.message!!.contains("com/intellij/ui/jcef/JBCefApp"), error.message)
  }

  @Test
  @RegistryKey(key = "io.github.nerzhulart.webview.engine", value = "JCEF")
  fun createWebView_appliesRegistryOverride(): Unit = runWebViewTest {
    val autoProvider = FakeProvider(
      id = WebViewEngineId.SYSTEM_MACOS,
      capabilities = capabilities(assetServing = true),
      priorities = mapOf(WebViewEngineKind.System to 10),
    )
    val overrideProvider = FakeProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = true),
      priorities = mapOf(WebViewEngineKind.Jcef to 10),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(autoProvider, overrideProvider) }

    val webView = runtime.createWebView(scope = this)

    assertEquals(WebViewEngineId.JCEF, webView.runtimeInfo.engineId)
    assertEquals("JCEF", webView.runtimeInfo.displayName)
    assertEquals(0, autoProvider.createCount)
    assertEquals(1, overrideProvider.createCount)
  }

  @Test
  @RegistryKey(key = "io.github.nerzhulart.webview.debug.engine.overlay", value = "true")
  fun createWebView_exposesRuntimeInfoToCommonBridge(): Unit = runWebViewTest {
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }

    val webView = runtime.createWebView(scope = this)

    val messageBus = webView.interop.messageBus as WebViewMessageBusImpl
    messageBus.transferFromJs("""{"jsonrpc":"2.0","method":"$RUNTIME_INFO_REQUEST_METHOD"}""")
    val delivered = withTimeout(5.seconds) { provider.engine.delivered.receive() }
    assertTrue(delivered.contains("\"method\":\"$RUNTIME_INFO_METHOD\""), delivered)
    assertTrue(delivered.contains("\"displayName\":\"JCEF\""), delivered)
    assertTrue(delivered.contains("\"overlayVisible\":true"), delivered)
  }

  @Test
  fun createWebView_installsApplicationModeDocumentStartScript(): Unit = runWebViewTest {
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }

    val webView = runtime.createWebView(scope = this)

    val script = provider.creationOptions.single().documentStartScripts.first().script
    assertEquals(WebViewFeatures.APPLICATION, provider.creationOptions.single().features)
    assertNotNull(provider.engine.lastFocusEntrySink)
    assertEquals(WebViewBrowserState.EMPTY, webView.browserState.value)
    assertEquals(WebViewApplicationModeScripts.DOCUMENT_START_SCRIPT.script, script)
    assertTrue(script.contains("contextmenu"), script)
    assertTrue(script.contains("MutationObserver"), script)
  }

  @Test
  fun createWebView_installsConsoleCaptureDocumentStartScript(): Unit = runWebViewTest {
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }

    val webView = runtime.createWebView(scope = this)

    val script = provider.creationOptions.single().documentStartScripts.last().script
    assertEquals(WebViewConsoleCapture.DOCUMENT_START_SCRIPT.script, script)
    assertTrue(script.contains("$/webview/console"), script)
    assertTrue(script.contains("Date.now"), script)
    assertTrue(script.contains("window.chrome.webview.postMessage"), script)
    assertTrue(script.contains("window.webkit.messageHandlers"), script)
    assertTrue(script.contains("__wviJcefQuery"), script)
  }

  @Test
  fun createEngine_installsApplicationModeDocumentStartScript(): Unit = runWebViewTest {
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }

    val engine = runtime.selectProvider(preference = WebViewEngineKind.Jcef).createEngine(scope = this, webViewEngineCreationOptions())
    try {
      assertEquals(
        listOf(WebViewApplicationModeScripts.DOCUMENT_START_SCRIPT),
        provider.creationOptions.single().documentStartScripts,
      )
    }
    finally {
      engine.close()
    }
  }

  @Test
  fun createWebView_logsConsoleNotificationsThroughRuntimeLogger(): Unit = runWebViewTest {
    val loggerFactory = Logger.getFactory() as? TestLoggerFactory
    assertNotNull(loggerFactory, "WebViewRuntimeTest expects TestLoggerFactory")
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }
    val category = "#wvtest"
    val viewId = "runtimeConsole"
    val marker = "runtime-console-${System.nanoTime()}"
    val jsTimeEpochMs = 1_782_995_696_789L

    val webView = runtime.createWebView(
      scope = this,
      options = WebViewCreationOptions(consoleLogCategory = category),
    )
    webView.loadAsset(WebViewAssetRoot.forView(WebViewRuntimeTest::class.java, viewId))
    provider.engine.transferFromJs(
      """
        {
          "jsonrpc": "2.0",
          "method": "$CONSOLE_LOG_METHOD",
          "params": {
            "method": "log",
            "jsTimeEpochMs": $jsTimeEpochMs,
            "args": ["$marker", "payload"]
          }
        }
      """.trimIndent(),
    )

    val log = awaitLog(loggerFactory!!, marker)
    assertTrue(log.contains("$category.$viewId"), log)
    assertTrue(log.contains("[js=${Instant.ofEpochMilli(jsTimeEpochMs)}] $marker payload"), log)
  }

  @Test
  fun createPanel_logsConsoleNotificationsThroughConfiguredCategory(): Unit = runWebViewTest {
    val loggerFactory = Logger.getFactory() as? TestLoggerFactory
    assertNotNull(loggerFactory, "WebViewRuntimeTest expects TestLoggerFactory")
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }
    val category = "#wvtest.panel"
    val viewId = "panelConsole"
    val assetRoot = WebViewAssetRoot.forView(WebViewRuntimeTest::class.java, viewId)
    val marker = "panel-console-${System.nanoTime()}"
    val jsTimeEpochMs = 1_782_995_696_789L
    val testScope = this

    withContext(Dispatchers.EDT) {
      runtime.createWebViewPanel(
        scope = testScope,
        options = WebViewPanelOptions(
          assetRoot = assetRoot,
          consoleLogCategory = category,
        ),
      )
    }
    provider.engine.transferFromJs(
      """
        {
          "jsonrpc": "2.0",
          "method": "$CONSOLE_LOG_METHOD",
          "params": {
            "method": "log",
            "jsTimeEpochMs": $jsTimeEpochMs,
            "args": ["$marker", "payload"]
          }
        }
      """.trimIndent(),
    )

    val log = awaitLog(loggerFactory!!, marker)
    assertTrue(log.contains("$category.$viewId"), log)
    assertTrue(log.contains("[js=${Instant.ofEpochMilli(jsTimeEpochMs)}] $marker payload"), log)
  }

  @Test
  fun createWebView_appendsThemeQueryToAssetLoads(): Unit = runWebViewTest {
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }
    val assetRoot = WebViewAssetRoot.fromClasspath(WebViewRuntimeTest::class.java, WebViewAssetPath.of("webview/views/smoke"))

    val webView = runtime.createWebView(scope = this)
    webView.loadAsset(assetRoot, query = "foo=bar")

    val query = provider.engine.lastAssetQuery
    assertNotNull(query)
    assertTrue(query!!.startsWith("foo=bar&__webviewTheme="), query)
  }

  @Test
  fun createWebView_exposesThemeToCommonBridge(): Unit = runWebViewTest {
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }

    val webView = runtime.createWebView(scope = this)

    val messageBus = webView.interop.messageBus as WebViewMessageBusImpl
    messageBus.transferFromJs("""{"jsonrpc":"2.0","method":"$THEME_REQUEST_METHOD"}""")
    val delivered = withTimeout(5.seconds) { provider.engine.delivered.receive() }
    assertTrue(delivered.contains("\"method\":\"$THEME_CHANGED_METHOD\""), delivered)
    assertTrue(delivered.contains("\"theme\":"), delivered)
    assertTrue(delivered.contains("\"fonts\":"), delivered)
    assertTrue(delivered.contains("\"ui\":"), delivered)
    assertTrue(delivered.contains("\"editor\":"), delivered)
    assertTrue(delivered.contains("\"families\":"), delivered)
    assertTrue(delivered.contains("\"sizes\":"), delivered)
    assertTrue(delivered.contains("\"h0\":"), delivered)
    assertTrue(delivered.contains("\"medium\":"), delivered)
    assertTrue(delivered.contains("\"mini\":"), delivered)
    assertTrue(delivered.contains("\"ligatures\":"), delivered)
  }

  @Test
  fun createWebView_wrapsHeavyweightAndLightweightEnginesInSwingHost(): Unit = runWebViewTest {
    val heavyweightProvider = FakeEngineProvider(
      id = WebViewEngineId.SYSTEM_WINDOWS,
      displayName = "WebView2",
      capabilities = capabilities(assetServing = true),
      isHeavyweight = true,
    )
    val lightweightProvider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = true),
      component = JPanel(),
    )

    val heavyweightWebView = WebViewRuntime().apply { providers = listOf(heavyweightProvider) }.createWebView(this)
    val lightweightWebView = WebViewRuntime().apply { providers = listOf(lightweightProvider) }.createWebView(this)

    assertTrue(heavyweightWebView.component is SwingWebViewHostPanel)
    assertTrue(lightweightWebView.component is SwingWebViewHostPanel)
    assertEquals(0, heavyweightWebView.component.componentCount)
    assertSame(lightweightProvider.engine.component, lightweightWebView.component.getComponent(0))
  }

  @Test
  fun createWebView_closesEngineOnceWhenScopeCancellationIsRepeated(): Unit = runWebViewTest {
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }
    @Suppress("RAW_SCOPE_CREATION")
    val webViewScope = CoroutineScope(SupervisorJob())
    runtime.createWebView(scope = webViewScope)

    webViewScope.coroutineContext.job.cancelAndJoin()
    webViewScope.coroutineContext.job.cancelAndJoin()

    assertEquals(1, provider.engine.closeCount)
  }

  @Test
  fun createWebView_scopeCompletionWaitsForEngineClose(): Unit = runWebViewTest {
    val closeStarted = CompletableDeferred<Unit>()
    val closeGate = CompletableDeferred<Unit>()
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = true),
      closeStarted = closeStarted,
      closeGate = closeGate,
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }
    @Suppress("RAW_SCOPE_CREATION")
    val webViewScope = CoroutineScope(SupervisorJob())
    runtime.createWebView(webViewScope)

    val cancellation = launch {
      webViewScope.coroutineContext.job.cancelAndJoin()
    }
    try {
      closeStarted.await()
      assertFalse(cancellation.isCompleted)

      closeGate.complete(Unit)
      cancellation.join()
      assertEquals(1, provider.engine.closeCount)
    }
    finally {
      closeGate.complete(Unit)
      webViewScope.cancel()
      cancellation.join()
    }
  }

  @Test
  fun createPanel_requiresAssetServingAndCreatesProviderHostComponent(): Unit = runWebViewTest {
    val testScope = this
    val provider = FakeProvider(
      id = WebViewEngineId.JCEF,
      capabilities = capabilities(assetServing = true),
      priority = 10,
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }
    val assetRoot = WebViewAssetRoot.fromClasspath(WebViewRuntimeTest::class.java, WebViewAssetPath.of("webview/views/smoke"))

    val panel = withContext(Dispatchers.EDT) {
      runtime.createWebViewPanel(
        scope = testScope,
        options = WebViewPanelOptions(assetRoot = assetRoot),
      )
    }

    assertSame(panel.webView.component, panel.component)
    assertTrue(panel.component is SwingWebViewHostPanel)
    assertEquals(1, provider.engine.loadAssetCount)
    assertEquals(WebViewAssetPath.indexHtml(), provider.engine.lastAssetPath)
  }

  @Test
  fun createPanel_closesEngineWhenScopeCompletes(): Unit = runWebViewTest {
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }
    val assetRoot = WebViewAssetRoot.fromClasspath(WebViewRuntimeTest::class.java, WebViewAssetPath.of("webview/views/smoke"))
    @Suppress("RAW_SCOPE_CREATION")
    val webViewScope = CoroutineScope(SupervisorJob())
    try {
      withContext(Dispatchers.EDT) {
        runtime.createWebViewPanel(
          scope = webViewScope,
          options = WebViewPanelOptions(assetRoot = assetRoot),
        )
      }

      webViewScope.coroutineContext.job.cancelAndJoin()

      assertEquals(1, provider.engine.closeCount)
    }
    finally {
      webViewScope.cancel()
    }
  }

  @Test
  fun createWebView_doesNotCreateEngineForCancelledScope(): Unit = runWebViewTest {
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }
    @Suppress("RAW_SCOPE_CREATION")
    val cancelledScope = CoroutineScope(SupervisorJob()).also { it.cancel() }

    val failure = runCatching { runtime.createWebView(cancelledScope) }.exceptionOrNull()

    assertTrue(failure is CancellationException)
    assertEquals(0, provider.creationOptions.size)
  }

  @Test
  fun createWebView_closesEngineWhenHostCreationFails(): Unit = runWebViewTest {
    val expectedFailure = IllegalStateException("host failed")
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = true),
      hostCreationFailure = expectedFailure,
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }

    val failure = runCatching { runtime.createWebView(this) }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertEquals(expectedFailure.message, failure?.message)
    assertTrue(coroutineContext.job.isActive)
    assertEquals(1, provider.engine.closeCount)
  }

  @Test
  fun createPanel_closesSessionWhenInitialAssetLoadFailsWithoutCancellingOwner(): Unit = runWebViewTest {
    val expectedFailure = IllegalStateException("load failed")
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = true),
      loadAssetFailure = expectedFailure,
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }
    val assetRoot = WebViewAssetRoot.fromClasspath(WebViewRuntimeTest::class.java, WebViewAssetPath.of("webview/views/smoke"))

    val failure = runCatching {
      runtime.createWebViewPanel(this, WebViewPanelOptions(assetRoot = assetRoot))
    }.exceptionOrNull()

    assertSame(expectedFailure, failure)
    assertTrue(coroutineContext.job.isActive)
    assertEquals(1, provider.engine.closeCount)
  }

  @Test
  fun navigationCapability_isOptInAndParticipatesInRequirements() {
    val unsupported = capabilities(assetServing = true)
    val requirements = WebViewEngineRequirements(navigation = true)

    assertFalse(unsupported.navigation)
    assertFalse(WebViewEngineRequirements().navigation)
    assertTrue(unsupported.satisfies(WebViewEngineRequirements()))
    assertFalse(unsupported.satisfies(requirements))
    assertEquals(listOf("navigation"), unsupported.missingRequirements(requirements))
    assertTrue(unsupported.copy(navigation = true).satisfies(requirements))
    assertTrue(unsupported.copy(navigation = true).missingRequirements(requirements).isEmpty())
  }

  @Test
  fun features_presetsAndCreationDefaultsAreConservative() {
    val application = WebViewFeatures(
      applicationModeScript = true,
      pageFocusInterop = true,
      contextMenu = false,
      zoom = false,
      elasticScrolling = false,
      swipeNavigation = false,
      browserAcceleratorKeys = false,
      builtInErrorPage = false,
      autofill = false,
    )
    val browser = application.copy(
      applicationModeScript = false,
      pageFocusInterop = false,
      contextMenu = true,
      zoom = true,
      builtInErrorPage = true,
    )

    assertEquals(application, WebViewFeatures.APPLICATION)
    assertEquals(browser, WebViewFeatures.BROWSER)
    assertEquals(application, WebViewCreationOptions().features)
    assertEquals(browser, WebBrowserPanelOptions().webViewOptions.features)
    assertNull(WebBrowserPanelOptions().initialUrl)
    assertEquals(application, WebViewEngineCreationOptions(debugName = null).features)
    assertEquals(
      listOf(WebViewApplicationModeScripts.DOCUMENT_START_SCRIPT),
      WebViewEngineCreationOptions(debugName = null).documentStartScripts,
    )
    assertTrue(WebViewEngineCreationOptions(debugName = null, features = browser).documentStartScripts.isEmpty())
  }

  @Test
  fun createWebBrowserPanel_requiresNavigationAndLoadsInitialUrlWithBrowserDefaults(): Unit = runWebViewTest {
    val rejected = FakeProvider(
      id = WebViewEngineId.SYSTEM_WINDOWS,
      capabilities = capabilities(assetServing = true),
      priority = 10,
    )
    val selected = FakeProvider(
      id = WebViewEngineId.JCEF,
      capabilities = capabilities(assetServing = false, navigation = true),
      priority = 20,
    )
    val runtime = WebViewRuntime().apply { providers = listOf(rejected, selected) }
    val initialUrl = URI("https://example.com/start%20page?query=%2F#section")
    selected.engine.onLoadUrl = { url ->
      assertEquals(1, selected.engine.hostCreationCount)
      checkNotNull(selected.engine.listener).onNavigationStarted(url.toString())
    }

    val panel = runtime.createWebBrowserPanel(this, WebBrowserPanelOptions(initialUrl = initialUrl))

    assertEquals(selected.id, panel.webView.runtimeInfo.engineId)
    assertEquals(0, rejected.createCount)
    assertEquals(1, selected.createCount)
    assertTrue(panel.webView.isBrowserNavigationSupported)
    assertSame(panel.webView.component, panel.component)
    assertTrue(panel.component is SwingWebViewHostPanel)
    assertEquals(WebViewFeatures.BROWSER, selected.engine.creationOptions!!.features)
    assertEquals(listOf(WebViewConsoleCapture.DOCUMENT_START_SCRIPT), selected.engine.creationOptions!!.documentStartScripts)
    assertNull(selected.engine.lastFocusEntrySink)
    assertNotNull(selected.engine.listenerAtHostCreation)
    assertSame(selected.engine.listener, selected.engine.listenerAtHostCreation)
    assertSame(initialUrl, selected.engine.lastUrl)
    assertEquals(1, selected.engine.loadUrlCount)
    assertEquals(0, selected.engine.loadAssetCount)
    assertEquals(WebViewBrowserState.EMPTY.copy(url = initialUrl.toString(), isLoading = true), panel.webView.browserState.value)
  }

  @Test
  fun createWebBrowserPanel_preservesExplicitRequirementsEngineKindAndFeatures(): Unit = runWebViewTest {
    val candidates = listOf(
      capabilities(assetServing = false, navigation = true),
      capabilities(assetServing = true, navigation = true).copy(messagePassing = false),
      capabilities(assetServing = true, navigation = true).copy(interactiveInput = false),
      capabilities(assetServing = true, navigation = false),
      capabilities(assetServing = true, navigation = true),
    ).mapIndexed { index, capabilities ->
      FakeProvider(
        id = WebViewEngineId("candidate-$index"),
        capabilities = capabilities,
        priorities = mapOf(WebViewEngineKind.Jcef to index),
      )
    }
    val runtime = WebViewRuntime().apply { providers = candidates }
    val features = WebViewFeatures.BROWSER.copy(elasticScrolling = true, contextMenu = false, autofill = true)
    val options = WebViewCreationOptions(
      engineKind = WebViewEngineKind.Jcef,
      requirements = WebViewEngineRequirements(assetServing = true, messagePassing = true, interactiveInput = true),
      debugName = "custom-browser",
      features = features,
    )

    val panel = runtime.createWebBrowserPanel(this, WebBrowserPanelOptions(webViewOptions = options))

    assertEquals(candidates.last().id, panel.webView.runtimeInfo.engineId)
    assertEquals(listOf(0, 0, 0, 0, 1), candidates.map { it.createCount })
    assertEquals(features, candidates.last().engine.creationOptions!!.features)
    assertEquals(options.debugName, candidates.last().engine.creationOptions!!.debugName)
    assertEquals(listOf(WebViewConsoleCapture.DOCUMENT_START_SCRIPT), candidates.last().engine.creationOptions!!.documentStartScripts)
    assertFalse(options.requirements.navigation)
  }

  @Test
  fun createWebBrowserPanel_reportsMissingNavigationWithoutCreatingEngineOrCancellingOwner(): Unit = runWebViewTest {
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }

    val failure = runCatching {
      runtime.createWebBrowserPanel(this, WebBrowserPanelOptions(webViewOptions = WebViewCreationOptions()))
    }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertTrue(failure!!.message!!.contains("missing navigation"), failure.message)
    assertTrue(coroutineContext.job.isActive)
    assertTrue(provider.creationOptions.isEmpty())
    assertEquals(0, provider.engine.hostCreationCount)
    assertEquals(0, provider.engine.closeCount)
  }

  @Test
  fun createWebBrowserPanel_withoutInitialUrlDoesNotLoadAndCapturesHostCreationEvents(): Unit = runWebViewTest {
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = false, navigation = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }
    provider.engine.onHostCreation = {
      checkNotNull(provider.engine.listener).onTitleChanged("Initial host title")
    }

    val panel = runtime.createWebBrowserPanel(this, WebBrowserPanelOptions())

    assertEquals(1, provider.engine.hostCreationCount)
    assertEquals(0, provider.engine.loadUrlCount)
    assertEquals(0, provider.engine.loadAssetCount)
    assertNull(provider.engine.lastUrl)
    assertEquals(WebViewBrowserState.EMPTY.copy(title = "Initial host title"), panel.webView.browserState.value)
  }

  @Test
  fun createPanel_keepsApplicationFeaturesScriptsAndFocusInterop(): Unit = runWebViewTest {
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = true, navigation = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }
    val assetRoot = WebViewAssetRoot.forView(WebViewRuntimeTest::class.java, "smoke")

    val panel = runtime.createWebViewPanel(this, WebViewPanelOptions(assetRoot = assetRoot))

    assertEquals(WebViewFeatures.APPLICATION, provider.creationOptions.single().features)
    assertEquals(
      listOf(WebViewApplicationModeScripts.DOCUMENT_START_SCRIPT, WebViewConsoleCapture.DOCUMENT_START_SCRIPT),
      provider.creationOptions.single().documentStartScripts,
    )
    assertNotNull(provider.engine.lastFocusEntrySink)
    assertEquals(WebViewBrowserState.EMPTY, panel.webView.browserState.value)
    assertEquals(1, provider.engine.loadAssetCount)
    assertEquals(0, provider.engine.loadUrlCount)
  }

  @Test
  fun createWebView_configuresApplicationScriptAndFocusInteropIndependently(): Unit = runWebViewTest {
    for (applicationModeScript in listOf(false, true)) {
      for (pageFocusInterop in listOf(false, true)) {
        val provider = FakeEngineProvider(
          id = WebViewEngineId.JCEF,
          displayName = "JCEF",
          capabilities = capabilities(assetServing = true),
        )
        val runtime = WebViewRuntime().apply { providers = listOf(provider) }
        val features = WebViewFeatures.BROWSER.copy(
          applicationModeScript = applicationModeScript,
          pageFocusInterop = pageFocusInterop,
          swipeNavigation = true,
        )

        val webView = runtime.createWebView(this, WebViewCreationOptions(features = features))

        assertEquals(features, provider.creationOptions.single().features)
        val expectedScripts = if (applicationModeScript) {
          listOf(WebViewApplicationModeScripts.DOCUMENT_START_SCRIPT, WebViewConsoleCapture.DOCUMENT_START_SCRIPT)
        }
        else {
          listOf(WebViewConsoleCapture.DOCUMENT_START_SCRIPT)
        }
        assertEquals(expectedScripts, provider.creationOptions.single().documentStartScripts)
        assertEquals(pageFocusInterop, provider.engine.lastFocusEntrySink != null)
        val registration = runCatching {
          webView.interop.registerWebViewFocusExitHandler(webView.component as SwingWebViewHostPanel)
        }
        if (pageFocusInterop) {
          assertTrue(registration.exceptionOrNull() is IllegalStateException)
          assertTrue(registration.exceptionOrNull()!!.message!!.contains("already registered"))
        }
        else {
          registration.getOrThrow().close()
        }
      }
    }
  }

  @Test
  fun browserCommands_delegateToEngineAndGuardEachHistoryDirection(): Unit = runWebViewTest {
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = true, navigation = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }
    val webView = runtime.createWebView(this)

    assertTrue(webView.isBrowserNavigationSupported)
    assertEquals(webView.runtimeInfo.capabilities.navigation, webView.isBrowserNavigationSupported)
    assertEquals(WebViewBrowserState.EMPTY, webView.browserState.value)
    webView.goBack()
    webView.goForward()
    assertEquals(0, provider.engine.goBackCalls)
    assertEquals(0, provider.engine.goForwardCalls)

    webView.loadUrl(URI("https://example.com/next"))
    webView.reload()
    webView.stop()
    assertEquals(URI("https://example.com/next"), provider.engine.lastUrl)
    assertEquals(1, provider.engine.loadUrlCount)
    assertEquals(1, provider.engine.reloadCalls)
    assertEquals(1, provider.engine.stopCalls)
    assertEquals(WebViewBrowserState.EMPTY, webView.browserState.value)

    val listener = checkNotNull(provider.engine.listener)
    listener.onHistoryChanged(canGoBack = true, canGoForward = false)
    webView.goBack()
    webView.goForward()
    assertEquals(1, provider.engine.goBackCalls)
    assertEquals(0, provider.engine.goForwardCalls)

    listener.onHistoryChanged(canGoBack = false, canGoForward = true)
    webView.goBack()
    webView.goForward()
    assertEquals(1, provider.engine.goBackCalls)
    assertEquals(1, provider.engine.goForwardCalls)

    listener.onHistoryChanged(canGoBack = true, canGoForward = true)
    webView.goBack()
    webView.goForward()
    assertEquals(2, provider.engine.goBackCalls)
    assertEquals(2, provider.engine.goForwardCalls)

    listener.onHistoryChanged(canGoBack = false, canGoForward = false)
    webView.goBack()
    webView.goForward()
    assertEquals(2, provider.engine.goBackCalls)
    assertEquals(2, provider.engine.goForwardCalls)
  }

  @Test
  fun browserState_tracksEngineCallbacksAndClearsErrorsOnNextNavigation(): Unit = runWebViewTest {
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = false, navigation = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }
    val panel = runtime.createWebBrowserPanel(this, WebBrowserPanelOptions())
    val state = panel.webView.browserState
    val listener = checkNotNull(provider.engine.listener)
    assertEquals(WebViewBrowserState.EMPTY, state.value)

    listener.onNavigationStarted("https://example.com")
    listener.onUrlChanged("https://example.com/redirected")
    listener.onTitleChanged("Example")
    listener.onProgressChanged(0.5)
    listener.onHistoryChanged(canGoBack = true, canGoForward = false)
    val loadingState = WebViewBrowserState.EMPTY.copy(
      url = "https://example.com/redirected",
      title = "Example",
      isLoading = true,
      progress = 0.5,
      canGoBack = true,
    )
    assertEquals(loadingState, state.value)

    val error = WebViewBrowserError(url = loadingState.url, message = "Connection refused", code = -102)
    listener.onNavigationFinished(null, error)
    assertEquals(loadingState.copy(isLoading = false, progress = 1.0, error = error), state.value)

    listener.onNavigationStarted("https://example.com/retry")
    assertEquals(loadingState.copy(url = "https://example.com/retry", progress = 0.0), state.value)
    listener.onNavigationFinished("https://example.com/final", null)
    assertEquals(loadingState.copy(url = "https://example.com/final", isLoading = false, progress = 1.0), state.value)
    assertSame(state, panel.webView.browserState)
  }

  @Test
  fun loadUrl_acceptsAbsoluteUrisAndRejectsRelativeOrMalformedInputBeforeCallingEngine(): Unit = runWebViewTest {
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = false, navigation = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }
    val webView = runtime.createWebBrowserPanel(this, WebBrowserPanelOptions()).webView
    val urls = listOf(
      "https://example.com/path?q=value#fragment",
      "https://example.com/a%20b/%2F?q=%2520#part%201",
      "https://example.com/страница?q=текст",
      "about:blank",
      "file:///tmp/example.html",
      "custom-scheme:resource",
    )

    for (url in urls) {
      val uri = URI(url)
      webView.loadUrl(uri)
      assertSame(uri, provider.engine.lastUrl)
      assertEquals(url, provider.engine.lastUrl.toString())
    }
    for (url in listOf("", "example.com", "/relative", "//example.com", "https://exa mple.com", "https://example.com/%zz")) {
      val failure = runCatching { webView.loadUrl(URI(url)) }.exceptionOrNull()
      assertTrue(failure is IllegalArgumentException || failure is java.net.URISyntaxException, "url=$url, failure=$failure")
      assertEquals(urls.size, provider.engine.loadUrlCount)
      assertEquals(URI(urls.last()), provider.engine.lastUrl)
    }
    assertEquals(WebViewBrowserState.EMPTY, webView.browserState.value)
    assertTrue(coroutineContext.job.isActive)
    assertEquals(0, provider.engine.closeCount)
  }

  @Test
  fun unsupportedBrowserCommands_warnWithoutCallingEngineOrChangingEmptyState(): Unit = runWebViewTest {
    val provider = FakeEngineProvider(
      id = WebViewEngineId("unsupported-browser-${System.nanoTime()}"),
      displayName = "Unsupported browser",
      capabilities = capabilities(assetServing = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }
    provider.engine.onHostCreation = {
      provider.engine.listener?.onNavigationStarted("https://example.com/unexpected")
    }
    val webView = runtime.createWebView(this)

    assertFalse(webView.isBrowserNavigationSupported)
    assertEquals(webView.runtimeInfo.capabilities.navigation, webView.isBrowserNavigationSupported)
    assertNull(provider.engine.listener)
    assertNull(provider.engine.listenerAtHostCreation)
    val warnings = mutableListOf<String>()
    val token = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
      override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
        if (!message.contains(provider.id.value)) return super.processWarn(category, message, t)
        warnings.add(message)
        return false
      }
    })
    try {
      webView.loadUrl(URI("https://example.com"))
      webView.loadUrl(URI("relative/path"))
      webView.goBack()
      webView.goForward()
      webView.reload()
      webView.stop()
    }
    finally {
      token.finish()
    }

    assertNull(provider.engine.lastUrl)
    assertEquals(0, provider.engine.loadUrlCount)
    assertEquals(0, provider.engine.goBackCalls)
    assertEquals(0, provider.engine.goForwardCalls)
    assertEquals(0, provider.engine.reloadCalls)
    assertEquals(0, provider.engine.stopCalls)
    assertEquals(WebViewBrowserState.EMPTY, webView.browserState.value)
    val commands = listOf("loadUrl", "loadUrl", "goBack", "goForward", "reload", "stop")
    assertEquals(commands.size, warnings.size, warnings.toString())
    for ((command, warning) in commands.zip(warnings)) {
      assertTrue(warning.contains(command) && warning.contains("not supported") && warning.contains(provider.id.value), warning)
    }
  }

  @Test
  fun createWebBrowserPanel_closesEngineOnceAndClearsListenerWhenOwnerIsCancelled(): Unit = runWebViewTest {
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = false, navigation = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }
    @Suppress("RAW_SCOPE_CREATION")
    val browserScope = CoroutineScope(SupervisorJob(coroutineContext.job))
    runtime.createWebBrowserPanel(browserScope, WebBrowserPanelOptions(initialUrl = URI("https://example.com")))
    assertNotNull(provider.engine.listener)

    browserScope.coroutineContext.job.cancelAndJoin()
    browserScope.coroutineContext.job.cancelAndJoin()

    assertEquals(1, provider.engine.closeCount)
    assertNull(provider.engine.listener)
    assertTrue(coroutineContext.job.isActive)
  }

  @Test
  fun createWebBrowserPanel_rejectsCancelledOwnerBeforeCreatingEngine(): Unit = runWebViewTest {
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = false, navigation = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }
    @Suppress("RAW_SCOPE_CREATION")
    val cancelledScope = CoroutineScope(SupervisorJob()).also { it.cancel() }

    val failure = runCatching {
      runtime.createWebBrowserPanel(cancelledScope, WebBrowserPanelOptions(initialUrl = URI("https://example.com")))
    }.exceptionOrNull()

    assertTrue(failure is CancellationException)
    assertTrue(provider.creationOptions.isEmpty())
    assertEquals(0, provider.engine.hostCreationCount)
    assertEquals(0, provider.engine.loadUrlCount)
    assertEquals(0, provider.engine.closeCount)
  }

  @Test
  fun createWebBrowserPanel_closesEngineWhenHostCreationFailsBeforeInitialUrlLoad(): Unit = runWebViewTest {
    val expectedFailure = IllegalStateException("browser host failed")
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = false, navigation = true),
      hostCreationFailure = expectedFailure,
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }

    val failure = runCatching {
      runtime.createWebBrowserPanel(this, WebBrowserPanelOptions(initialUrl = URI("https://example.com")))
    }.exceptionOrNull()

    assertTrue(failure is IllegalStateException)
    assertEquals(expectedFailure.message, failure?.message)
    assertTrue(coroutineContext.job.isActive)
    assertNotNull(provider.engine.listenerAtHostCreation)
    assertNull(provider.engine.listener)
    assertEquals(0, provider.engine.loadUrlCount)
    assertEquals(1, provider.engine.closeCount)
  }

  @Test
  fun createWebBrowserPanel_closesSessionWhenInitialUrlLoadFailsWithoutCancellingOwner(): Unit = runWebViewTest {
    for (expectedFailure in listOf(IllegalStateException("URL load failed"), CancellationException("URL load cancelled"))) {
      val provider = FakeEngineProvider(
        id = WebViewEngineId.JCEF,
        displayName = "JCEF",
        capabilities = capabilities(assetServing = false, navigation = true),
        loadUrlFailure = expectedFailure,
      )
      val runtime = WebViewRuntime().apply { providers = listOf(provider) }

      val failure = runCatching {
        runtime.createWebBrowserPanel(this, WebBrowserPanelOptions(initialUrl = URI("https://example.com")))
      }.exceptionOrNull()

      assertSame(expectedFailure, failure)
      assertTrue(coroutineContext.job.isActive)
      assertEquals(1, provider.engine.hostCreationCount)
      assertEquals(1, provider.engine.loadUrlCount)
      assertEquals(1, provider.engine.closeCount)
      assertNull(provider.engine.listener)
    }
  }

  @Test
  fun createWebBrowserPanel_closesSessionWhenInitialUrlIsInvalid(): Unit = runWebViewTest {
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = false, navigation = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }

    val failure = runCatching {
      runtime.createWebBrowserPanel(this, WebBrowserPanelOptions(initialUrl = URI("relative/path")))
    }.exceptionOrNull()

    assertTrue(failure is IllegalArgumentException)
    assertTrue(coroutineContext.job.isActive)
    assertEquals(1, provider.engine.hostCreationCount)
    assertEquals(0, provider.engine.loadUrlCount)
    assertEquals(1, provider.engine.closeCount)
    assertNull(provider.engine.listener)
  }

  @Test
  fun createWebBrowserPanel_honorsExplicitApplicationFeaturesAndConsoleCategory(): Unit = runWebViewTest {
    val loggerFactory = Logger.getFactory() as? TestLoggerFactory
    assertNotNull(loggerFactory, "WebViewRuntimeTest expects TestLoggerFactory")
    val provider = FakeEngineProvider(
      id = WebViewEngineId.JCEF,
      displayName = "JCEF",
      capabilities = capabilities(assetServing = false, navigation = true),
    )
    val runtime = WebViewRuntime().apply { providers = listOf(provider) }
    val category = "#wvtest.browser"
    val marker = "browser-console-${System.nanoTime()}"
    val panel = runtime.createWebBrowserPanel(
      this,
      WebBrowserPanelOptions(
        initialUrl = URI("about:blank"),
        webViewOptions = WebViewCreationOptions(consoleLogCategory = category),
      ),
    )

    assertEquals(WebViewFeatures.APPLICATION, provider.creationOptions.single().features)
    assertEquals(
      listOf(WebViewApplicationModeScripts.DOCUMENT_START_SCRIPT, WebViewConsoleCapture.DOCUMENT_START_SCRIPT),
      provider.creationOptions.single().documentStartScripts,
    )
    assertNotNull(provider.engine.lastFocusEntrySink)
    assertTrue(panel.webView.isBrowserNavigationSupported)
    assertEquals(URI("about:blank"), provider.engine.lastUrl)
    provider.engine.transferFromJs(
      """
        {
          "jsonrpc": "2.0",
          "method": "$CONSOLE_LOG_METHOD",
          "params": {
            "method": "log",
            "jsTimeEpochMs": 1782995696789,
            "args": ["$marker"]
          }
        }
      """.trimIndent(),
    )

    val log = awaitLog(loggerFactory!!, marker)
    assertTrue(log.lineSequence().any { it.contains(category) && it.contains(marker) }, log)
  }

  private class FakeProvider(
    override val id: WebViewEngineId,
    override val displayName: String = id.value,
    override val capabilities: WebViewEngineCapabilities,
    private val priority: Int? = null,
    private val priorities: Map<WebViewEngineKind, Int> = emptyMap(),
    private val availability: WebViewEngineAvailability = WebViewEngineAvailability.Available,
    private val availabilityFailure: LinkageError? = null,
  ) : WebViewEngineProvider {
    val engine = CapturingEngine()
    var createCount = 0
      private set

    override fun selectionPriority(preference: WebViewEngineKind): Int? = priorities[preference] ?: priority

    override suspend fun availability(): WebViewEngineAvailability {
      availabilityFailure?.let { throw it }
      return availability
    }

    override fun createEngine(scope: CoroutineScope, options: WebViewEngineCreationOptions): WebViewEngine {
      createCount++
      engine.creationOptions = options
      return engine
    }
  }

  private class FakeEngineProvider(
    override val id: WebViewEngineId,
    override val displayName: String,
    override val capabilities: WebViewEngineCapabilities,
    isHeavyweight: Boolean = false,
    component: JComponent? = null,
    loadAssetFailure: Throwable? = null,
    hostCreationFailure: Throwable? = null,
    closeStarted: CompletableDeferred<Unit>? = null,
    closeGate: CompletableDeferred<Unit>? = null,
    loadUrlFailure: Throwable? = null,
  ) : WebViewEngineProvider {
    val engine = CapturingEngine(
      isHeavyweight,
      component,
      loadAssetFailure,
      hostCreationFailure,
      closeStarted,
      closeGate,
      loadUrlFailure,
    )
    val creationOptions = mutableListOf<WebViewEngineCreationOptions>()

    override fun selectionPriority(preference: WebViewEngineKind): Int = 10

    override suspend fun availability(): WebViewEngineAvailability = WebViewEngineAvailability.Available

    override fun createEngine(scope: CoroutineScope, options: WebViewEngineCreationOptions): WebViewEngine {
      creationOptions.add(options)
      engine.creationOptions = options
      return engine
    }
  }

  private class CapturingEngine(
    override val isHeavyweight: Boolean = false,
    override val component: JComponent? = null,
    private val loadAssetFailure: Throwable? = null,
    private val hostCreationFailure: Throwable? = null,
    private val closeStarted: CompletableDeferred<Unit>? = null,
    private val closeGate: CompletableDeferred<Unit>? = null,
    private val loadUrlFailure: Throwable? = null,
  ) : WebViewEngine {
    val delivered = Channel<String>(Channel.UNLIMITED)
    private var messageReceiver: WebViewJsMessageReceiver? = null
    var creationOptions: WebViewEngineCreationOptions? = null
    var listener: WebViewNavigationListener? = null
      private set
    var listenerAtHostCreation: WebViewNavigationListener? = null
      private set
    var lastFocusEntrySink: WebViewFocusEntrySink? = null
      private set
    var hostCreationCount = 0
      private set
    var onHostCreation: (() -> Unit)? = null
    var onLoadUrl: ((URI) -> Unit)? = null
    var lastUrl: URI? = null
      private set
    var loadUrlCount = 0
      private set
    var goBackCalls = 0
      private set
    var goForwardCalls = 0
      private set
    var reloadCalls = 0
      private set
    var stopCalls = 0
      private set
    var lastAssetQuery: String? = null
      private set
    var lastAssetPath: WebViewAssetPath? = null
      private set
    var loadAssetCount = 0
      private set
    var closeCount = 0
      private set

    override fun createHostComponent(scope: CoroutineScope, focusEntrySink: WebViewFocusEntrySink?): SwingWebViewHostPanel {
      hostCreationCount++
      lastFocusEntrySink = focusEntrySink
      listenerAtHostCreation = listener
      hostCreationFailure?.let { throw it }
      onHostCreation?.invoke()
      return super<WebViewEngine>.createHostComponent(scope, focusEntrySink)
    }

    override fun setNavigationListener(listener: WebViewNavigationListener?) {
      this.listener = listener
    }

    override suspend fun loadUrl(url: URI) {
      loadUrlCount++
      lastUrl = url
      loadUrlFailure?.let { throw it }
      onLoadUrl?.invoke(url)
    }

    override suspend fun goBack() {
      goBackCalls++
    }

    override suspend fun goForward() {
      goForwardCalls++
    }

    override suspend fun reload() {
      reloadCalls++
    }

    override suspend fun stop() {
      stopCalls++
    }

    override suspend fun loadFile(file: Path) {
    }

    override suspend fun loadAsset(root: WebViewAssetRoot, entry: WebViewAssetPath, query: String?) {
      loadAssetCount++
      lastAssetPath = entry
      lastAssetQuery = query
      loadAssetFailure?.let { throw it }
    }

    override suspend fun loadHtml(html: String, baseFile: Path?) {
    }

    override suspend fun evaluateJavaScript(script: String): String? = null

    override suspend fun transferToJs(rawJson: String) {
      delivered.send(rawJson)
    }

    override fun setFromJsHandler(handler: WebViewJsMessageReceiver) {
      messageReceiver = handler
    }

    override fun attach(host: Component): Boolean {
      return true
    }

    override fun detach() {
    }

    override fun syncHostState(host: Component) {
    }

    override fun requestFocus() {
    }

    override fun clearFocus() {
    }

    override suspend fun close() {
      closeCount++
      closeStarted?.complete(Unit)
      closeGate?.await()
      delivered.close()
    }

    fun transferFromJs(rawJson: String) {
      checkNotNull(messageReceiver) { "WebView message bus is not connected" }.transferFromJs(rawJson)
    }
  }

  private suspend fun awaitLog(loggerFactory: TestLoggerFactory, marker: String): String {
    return withTimeout(5.seconds) {
      // TODO: what is this? what is toBuffer and what is awaitLog?
      var log = loggerFactory.toBuffer()
      while (!log.contains(marker)) {
        delay(10)
        log = loggerFactory.toBuffer()
      }
      log
    }
  }

  private fun runWebViewTest(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking {
    @Suppress("RAW_SCOPE_CREATION")
    val webViewScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      withContext(Dispatchers.EDT) {
        block(webViewScope)
      }
    }
    finally {
      webViewScope.coroutineContext.job.cancelAndJoin()
    }
  }

  private fun capabilities(assetServing: Boolean, navigation: Boolean = false): WebViewEngineCapabilities {
    return WebViewEngineCapabilities(
      assetServing = assetServing,
      messagePassing = true,
      interactiveInput = true,
      navigation = navigation,
    )
  }

  private fun webViewEngineCreationOptions(): WebViewEngineCreationOptions {
    return WebViewEngineCreationOptions(
      debugName = null,
    )
  }

  private companion object {
    const val RUNTIME_INFO_REQUEST_METHOD: String = "$/webview/runtimeInfoRequest"
    const val RUNTIME_INFO_METHOD: String = "$/webview/runtimeInfo"
    const val CONSOLE_LOG_METHOD: String = "$/webview/console"
    const val THEME_REQUEST_METHOD: String = "webview.theme/themeRequest"
    const val THEME_CHANGED_METHOD: String = "webview.theme/themeChanged"
  }
}
