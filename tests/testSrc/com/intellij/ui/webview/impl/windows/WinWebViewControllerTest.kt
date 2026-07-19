// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.webview.impl.windows

import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.diagnostic.Logger
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.TestLoggerFactory
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.webview.api.WebViewAssetPath
import com.intellij.ui.webview.api.WebViewAssetRoot
import com.intellij.ui.webview.impl.WEBVIEW_ASSET_CUSTOM_SCHEME
import com.intellij.ui.webview.impl.WEBVIEW_ASSET_CUSTOM_SCHEME_HOST
import com.intellij.ui.webview.impl.WEBVIEW_ASSET_HTTPS_HOST
import com.intellij.ui.webview.impl.WebViewHostEvent
import com.intellij.ui.webview.impl.WebViewHostEventSink
import com.intellij.ui.webview.impl.engine.WebViewScript
import com.intellij.ui.webview.impl.webViewAssetCustomSchemeUrl
import com.intellij.ui.webview.impl.webViewAssetHttpsUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.EnumSet

internal class WinWebViewControllerTest {

  @Test
  fun zeroHostHwndFailsFast() {
    val scope = testScope()
    val controller = createTestEngine(scope, FakeWinWebView2Bridge())
    try {
      assertThrows(IllegalStateException::class.java) {
        controller.recreateOnHost(0)
      }
    }
    finally {
      closeEngine(controller, scope)
    }
  }

  @Test
  fun bridgeApiHasNoLegacyHostingOrClearFocusOperations() {
    val methodNames = WinWebView2BridgeApi::class.java.methods.mapTo(HashSet()) { it.name }
    assertFalse("attachToParent" in methodNames)
    assertFalse("detachFromParent" in methodNames)
    assertFalse("setHostWindow" in methodNames)
    assertFalse("clearFocus" in methodNames)
  }

  @Test
  fun webView2ConfigurationIsSuppliedByController() {
    val configuration = WinWebView2Configuration(
      allowHostInputProcessing = false,
      areDevToolsEnabled = true,
      areBrowserAcceleratorKeysEnabled = true,
    )
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val controller = WinWebViewController(
      scope,
      bridge,
      webView2Configuration = configuration,
      hostEventSink = WebViewHostEventSink { false },
      devToolsCpuProfilingEnabled = { false },
    )
    try {
      runInEdtAndWait { controller.recreateOnHost(42L) }

      assertEquals(listOf(configuration), bridge.configurations)
    }
    finally {
      closeEngine(controller, scope)
    }
  }

  @Test
  fun webView2ConfigurationEncodesEverySettingIndependently() {
    val allEnabled = WinWebView2Configuration(
      allowHostInputProcessing = true,
      isScriptEnabled = true,
      isWebMessageEnabled = true,
      areDefaultScriptDialogsEnabled = true,
      isStatusBarEnabled = true,
      areDevToolsEnabled = true,
      areDefaultContextMenusEnabled = true,
      areHostObjectsAllowed = true,
      isZoomControlEnabled = true,
      isBuiltInErrorPageEnabled = true,
      areBrowserAcceleratorKeysEnabled = true,
      isGeneralAutofillEnabled = true,
      isPasswordAutosaveEnabled = true,
      isSwipeNavigationEnabled = true,
    )

    assertEquals((1L shl 14) - 1, allEnabled.toNativeFlags())
  }

  @Test
  fun explicitFocusRequestFocusesWebView2AndNativeCallbackNotifiesHost() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val events = ArrayList<WebViewHostEvent>()
    val controller = WinWebViewController(
      scope,
      bridge,
      hostEventSink = WebViewHostEventSink { event ->
        events.add(event)
        true
      },
      devToolsCpuProfilingEnabled = { false },
    )
    try {
      runInEdtAndWait {
        controller.recreateOnHost(42L)
        bridge.callbacks.onCreated(bridge.createdHandles.single())
        controller.requestWebViewFocus()
        bridge.callbacks.onFocusGained()
      }

      assertEquals(listOf(WebViewHostEvent.NativeFocusGained), events)
      assertEquals(listOf(bridge.createdHandles.single()), bridge.focusedHandles)
    }
    finally {
      closeEngine(controller, scope)
    }
  }

  @Test
  fun nativeDiagnosticLevelsMapToLoggerLevels() {
    val factory = Logger.getFactory() as? TestLoggerFactory
    assertNotNull(factory, "WinWebViewControllerTest expects TestLoggerFactory")
    val marker = "win-webview2-diagnostic-${System.nanoTime()}"
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge)
    val engineLogger = Logger.getInstance(WinWebViewController::class.java)

    val logged = collectWarningsAndErrors {
      engineLogger.setLevel(LogLevel.TRACE)
      try {
        runInEdtAndWait {
          bridge.callbacks.onNativeDiagnostic(0, "$marker-trace", "trace message", "trace=data")
          bridge.callbacks.onNativeDiagnostic(1, "$marker-debug", "debug message", "debug=data")
          bridge.callbacks.onNativeDiagnostic(2, "$marker-info", "info message", "info=data")
          bridge.callbacks.onNativeDiagnostic(3, "$marker-warn", "warn message", "warn=data")
          bridge.callbacks.onNativeDiagnostic(4, "$marker-error", "error message", "error=data")
        }
      }
      finally {
        engineLogger.setLevel(LogLevel.INFO)
      }
    }

    try {
      val buffer = factory!!.toBuffer()
      assertTrue(buffer.contains("FINER") && buffer.contains("$marker-trace"), buffer)
      assertTrue(buffer.contains("FINER") && buffer.contains("$marker-debug"), buffer)
      assertTrue(buffer.contains("FINER") && buffer.contains("$marker-info"), buffer)
      assertTrue(logged.warnings.any { it.contains("$marker-warn") }, logged.warnings.toString())
      assertTrue(logged.errors.any { it.contains("$marker-error") }, logged.errors.toString())
    }
    finally {
      closeEngine(engine, scope)
    }
  }

  @Test
  fun fatalProcessFailureLogsErrorRecreatesAndReplaysState() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge, parentHwnd = 42L)

    val logged = collectWarningsAndErrors {
      runInEdtAndWait {
        engine.setHidden(true)
      }
      runBlocking { engine.loadHtml("<html>last</html>", null) }
      runInEdtAndWait {
        bridge.callbacks.onNativeDiagnostic(4, "process-failed.fatal", "render process crashed", "exitCode=1")
        bridge.callbacks.onCreated(bridge.createdHandles.last())
      }
    }

    try {
      assertTrue(logged.errors.any { it.contains("process-failed.fatal") }, logged.errors.toString())
      assertEquals(listOf(42L, 42L), bridge.createParentHwnds)
      assertEquals(listOf(1L), bridge.destroyedHandles)
      assertTrue(bridge.htmlLoads.any { it.handle == 2L && it.html == "<html>last</html>" }, bridge.htmlLoads.toString())
      assertTrue(bridge.bounds.any { it.handle == 2L && it.bounds == Bounds(10, 20, 300, 200, 1.5) }, bridge.bounds.toString())
      assertTrue(bridge.visibility.any { it.handle == 2L && !it.visible }, bridge.visibility.toString())
    }
    finally {
      closeEngine(engine, scope)
    }
  }

  @Test
  fun createPassesDocumentStartScriptToNativeBridge() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createTestEngine(
      scope,
      bridge,
      debugName = "test",
      documentStartScripts = listOf(WebViewScript("first"), WebViewScript("second")),
    )
    try {
      runInEdtAndWait {
        engine.recreateOnHost(100L, 10, 20, 300, 200, 1.5)
      }

      assertEquals(listOf("first\n;\nsecond"), bridge.documentStartScripts)
      runInEdtAndWait { bridge.callbacks.onCreated(bridge.createdHandles.single()) }
    }
    finally {
      closeEngine(engine, scope)
    }
  }

  @Test
  fun secondRenderUnresponsiveDiagnosticRecreatesEngine() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge)

    val logged = collectWarningsAndErrors {
      runInEdtAndWait {
        bridge.callbacks.onNativeDiagnostic(4, "process-failed.unresponsive", "render process is unresponsive", "count=1")
        assertEquals(listOf(1L), bridge.createdHandles)

        bridge.callbacks.onNativeDiagnostic(4, "process-failed.unresponsive", "render process is unresponsive", "count=2")
        bridge.callbacks.onCreated(bridge.createdHandles.last())
      }
    }

    try {
      assertTrue(logged.errors.any { it.contains("process-failed.unresponsive") }, logged.errors.toString())
      assertEquals(listOf(1L, 2L), bridge.createdHandles)
      assertEquals(listOf(1L), bridge.destroyedHandles)
    }
    finally {
      closeEngine(engine, scope)
    }
  }

  @Test
  fun recoveryLimitLogsErrorAndClosesEngine() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge)

    val logged = collectWarningsAndErrors {
      runInEdtAndWait {
        bridge.callbacks.onNativeDiagnostic(4, "process-failed.fatal", "first crash", "")
        bridge.callbacks.onCreated(bridge.createdHandles.last())
        bridge.callbacks.onNativeDiagnostic(4, "process-failed.fatal", "second crash", "")
        bridge.callbacks.onCreated(bridge.createdHandles.last())
        bridge.callbacks.onNativeDiagnostic(4, "process-failed.fatal", "third crash", "")
      }
    }

    try {
      assertEquals(listOf(1L, 2L, 3L), bridge.createdHandles)
      assertEquals(listOf(1L, 2L, 3L), bridge.destroyedHandles)
      assertTrue(logged.errors.any { it.contains("WebView2 engine closed after fatal native failure") }, logged.errors.toString())
      assertTrue(logged.errors.any { it.contains("third crash") }, logged.errors.toString())
      assertNull(runBlocking { engine.evaluateJavaScript("1") })
    }
    finally {
      closeEngine(engine, scope)
    }
  }

  @Test
  fun closeDestroysHandle() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createTestEngine(scope, bridge, debugName = "test")
    runInEdtAndWait {
      engine.recreateOnHost(100L, 10, 20, 300, 200, 1.5)
      bridge.callbacks.onCreated(bridge.createdHandles.last())
    }

    runBlocking { engine.close() }
    runInEdtAndWait {}

    assertEquals(listOf(1L), bridge.destroyedHandles)
    scope.cancel()
  }

  @Test
  fun hostHwndChangeDestroysAndRecreatesController() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge)
    try {
      runInEdtAndWait {
        engine.recreateOnHost(200L, 30, 40, 500, 400, 2.0)
        bridge.callbacks.onCreated(bridge.createdHandles.last())
      }

      assertEquals(listOf(100L, 200L), bridge.createParentHwnds)
      assertEquals(listOf(1L), bridge.destroyedHandles)
      assertTrue(bridge.bounds.any { it.handle == 2L && it.bounds == Bounds(30, 40, 500, 400, 2.0) })
    }
    finally {
      closeEngine(engine, scope)
    }
  }

  @Test
  fun hostHwndChangeWaitsForNativeDestroyBeforeCreatingReplacement() {
    val bridge = FakeWinWebView2Bridge().apply { autoDestroyCallback = false }
    val scope = testScope()
    val controller = createActiveEngine(scope, bridge)
    try {
      runInEdtAndWait {
        controller.recreateOnHost(200L, 30, 40, 500, 400, 2.0)
      }

      assertEquals(listOf(100L), bridge.createParentHwnds)
      assertEquals(listOf(1L), bridge.destroyedHandles)

      runInEdtAndWait {
        bridge.callbacks.onDestroyed(1L)
      }

      assertEquals(listOf(100L, 200L), bridge.createParentHwnds)
      runInEdtAndWait {
        bridge.callbacks.onCreated(bridge.createdHandles.last())
      }
    }
    finally {
      bridge.autoDestroyCallback = true
      closeEngine(controller, scope)
    }
  }

  @Test
  fun closeWaitsForDevToolsCpuProfileStopBeforeDestroy() = runBlocking {
    val bridge = FakeWinWebView2Bridge()
    bridge.deferProfilerStop = true
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge, devToolsCpuProfilingEnabled = true)
    var closeJob: Job? = null
    try {
      assertTrue(bridge.devToolsCalls.any { it.methodName == "Profiler.start" }, bridge.devToolsCalls.toString())

      closeJob = launch(start = CoroutineStart.UNDISPATCHED) {
        engine.close()
      }
      assertNotNull(bridge.pendingProfilerStopCallId)
      assertTrue(bridge.destroyedHandles.isEmpty(), bridge.destroyedHandles.toString())
    }
    finally {
      bridge.pendingProfilerStopCallId?.let { callId ->
        bridge.completeDevToolsCall(callId, """{"profile":{"nodes":[],"samples":[],"timeDeltas":[]}}""", null)
      }
      closeJob?.join()
      scope.cancel()
    }

    val stopIndex = bridge.callOrder.indexOf("devtools:Profiler.stop")
    val destroyIndex = bridge.callOrder.indexOf("destroy:1")
    assertTrue(stopIndex >= 0, bridge.callOrder.toString())
    assertTrue(destroyIndex > stopIndex, bridge.callOrder.toString())
  }

  @Test
  fun createAppliesInitialBoundsBeforeFirstVisibilityAndKeepsHiddenUntilCreated() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createTestEngine(scope, bridge, debugName = "test")
    try {
      runInEdtAndWait { engine.recreateOnHost(100L, 10, 20, 300, 200, 1.5) }
      runInEdtAndWait { bridge.callbacks.onCreated(bridge.createdHandles.single()) }

      assertEquals(
        listOf("create:100", "bounds:1:10:20:300:200:1.5", "visible:1:false", "visible:1:true"),
        bridge.callOrder,
      )
    }
    finally {
      runBlocking { engine.close() }
      scope.cancel()
    }
  }

  @Test
  fun loadAssetUsesCustomSchemeUrlByDefault(@TempDir tempDir: Path) {
    Files.writeString(tempDir.resolve("index.html"), "custom")
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge)
    try {
      runBlocking { engine.loadAsset(WebViewAssetRoot.fromDirectory(tempDir), WebViewAssetPath.indexHtml(), null) }
      runInEdtAndWait {}

      assertEquals(
        listOf(UrlLoad(1L, webViewAssetCustomSchemeUrl(WebViewAssetPath.indexHtml()))),
        bridge.urlLoads,
      )
      assertTrue(bridge.urlLoads.single().url.startsWith("$WEBVIEW_ASSET_CUSTOM_SCHEME://$WEBVIEW_ASSET_CUSTOM_SCHEME_HOST/"))
    }
    finally {
      closeEngine(engine, scope)
    }
  }

  @Test
  fun loadAssetUsesHttpsUrlWhenCustomSchemeIsDisabled(@TempDir tempDir: Path) {
    Files.writeString(tempDir.resolve("index.html"), "legacy")
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge, customSchemeAssetLoadingEnabled = false)
    try {
      runBlocking { engine.loadAsset(WebViewAssetRoot.fromDirectory(tempDir), WebViewAssetPath.indexHtml(), null) }
      runInEdtAndWait {}

      assertEquals(
        listOf(UrlLoad(1L, webViewAssetHttpsUrl(WebViewAssetPath.indexHtml()))),
        bridge.urlLoads,
      )
      assertTrue(bridge.urlLoads.single().url.startsWith("https://$WEBVIEW_ASSET_HTTPS_HOST/"))
    }
    finally {
      closeEngine(engine, scope)
    }
  }

  @Test
  fun sameCustomSchemeUrlResolvesAgainstEachActiveEngineRoot(@TempDir tempDir: Path) {
    val firstRoot = Files.createDirectory(tempDir.resolve("first"))
    val secondRoot = Files.createDirectory(tempDir.resolve("second"))
    Files.writeString(firstRoot.resolve("index.html"), "first")
    Files.writeString(secondRoot.resolve("index.html"), "second")

    val firstBridge = FakeWinWebView2Bridge()
    val secondBridge = FakeWinWebView2Bridge()
    val firstScope = testScope()
    val secondScope = testScope()
    val firstEngine = createActiveEngine(firstScope, firstBridge)
    val secondEngine = createActiveEngine(secondScope, secondBridge)
    try {
      runBlocking {
        firstEngine.loadAsset(WebViewAssetRoot.fromDirectory(firstRoot), WebViewAssetPath.indexHtml(), null)
        secondEngine.loadAsset(WebViewAssetRoot.fromDirectory(secondRoot), WebViewAssetPath.indexHtml(), null)
      }

      val url = webViewAssetCustomSchemeUrl(WebViewAssetPath.indexHtml())
      assertAssetResponse("first", firstBridge.callbacks.resolveAsset(url))
      assertAssetResponse("second", secondBridge.callbacks.resolveAsset(url))
    }
    finally {
      closeEngine(firstEngine, firstScope)
      closeEngine(secondEngine, secondScope)
    }
  }

  @Test
  fun transferToJsWorksWhileWebViewIsHidden() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge)
    try {
      runInEdtAndWait { engine.setHidden(true) }
      runBlocking { engine.transferToJs("{\"jsonrpc\":\"2.0\",\"method\":\"markdown.preview/contentChanged\"}") }
      runInEdtAndWait {}

      assertEquals(
        listOf(JsTransfer(1L, "{\"jsonrpc\":\"2.0\",\"method\":\"markdown.preview/contentChanged\"}")),
        bridge.jsTransfers,
      )
    }
    finally {
      closeEngine(engine, scope)
    }
  }

  @Test
  fun transientFocusFailureDoesNotEscapeDispatcherTask() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge)
    try {
      bridge.focusFailure = IllegalStateException("focus failed")

      runInEdtAndWait {
        engine.requestWebViewFocus()
      }

      assertEquals(listOf(1L), bridge.focusedHandles)
    }
    finally {
      closeEngine(engine, scope)
    }
  }

  private fun createActiveEngine(
    scope: CoroutineScope,
    bridge: FakeWinWebView2Bridge,
    parentHwnd: Long = 100L,
    devToolsCpuProfilingEnabled: Boolean = false,
    customSchemeAssetLoadingEnabled: Boolean = true,
  ): WinWebViewController {
    val engine = createTestEngine(
      scope,
      bridge,
      debugName = "test",
      devToolsCpuProfilingEnabled = { devToolsCpuProfilingEnabled },
      customSchemeAssetLoadingEnabled = { customSchemeAssetLoadingEnabled },
    )
    runInEdtAndWait {
      engine.recreateOnHost(parentHwnd, 10, 20, 300, 200, 1.5)
      bridge.callbacks.onCreated(bridge.createdHandles.last())
    }
    return engine
  }

  private fun testScope(): CoroutineScope {
    @Suppress("RAW_SCOPE_CREATION") // Test scope has no parent fixture scope.
    return CoroutineScope(SupervisorJob())
  }

  private fun closeEngine(engine: WinWebViewController, scope: CoroutineScope) {
    runBlocking { engine.close() }
    runInEdtAndWait {}
    scope.cancel()
  }

  private fun assertAssetResponse(expectedContent: String, response: WinWebView2Bridge.AssetResponse?) {
    assertNotNull(response)
    assertEquals(200, response!!.statusCode)
    assertEquals(expectedContent, response.bytes.toString(StandardCharsets.UTF_8))
  }

  private fun createTestEngine(
    scope: CoroutineScope,
    bridge: FakeWinWebView2Bridge,
    debugName: String? = "test",
    documentStartScripts: List<WebViewScript> = emptyList(),
    devToolsCpuProfilingEnabled: () -> Boolean = { false },
    customSchemeAssetLoadingEnabled: () -> Boolean = { true },
  ): WinWebViewController {
    return WinWebViewController(
      scope,
      bridge,
      debugName = debugName,
      documentStartScripts = documentStartScripts,
      hostEventSink = WebViewHostEventSink { false },
      devToolsCpuProfilingEnabled = devToolsCpuProfilingEnabled,
      customSchemeAssetLoadingEnabled = customSchemeAssetLoadingEnabled,
    )
  }

  private fun collectWarningsAndErrors(action: () -> Unit): LoggedMessages {
    val errors = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    val token = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
      override fun processError(
        category: String,
        message: String,
        details: Array<out String>,
        t: Throwable?,
      ): Set<LoggedErrorProcessor.Action> {
        errors.add(message)
        return EnumSet.noneOf(LoggedErrorProcessor.Action::class.java)
      }

      override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
        warnings.add(message)
        return false
      }
    })
    try {
      action()
    }
    finally {
      token.finish()
    }
    return LoggedMessages(warnings, errors)
  }

  private data class LoggedMessages(
    val warnings: List<String>,
    val errors: List<String>,
  )

  private data class Bounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val scale: Double,
  )

  private data class HtmlLoad(
    val handle: Long,
    val html: String,
    val baseUrl: String?,
  )

  private data class UrlLoad(
    val handle: Long,
    val url: String,
  )

  private data class Visibility(
    val handle: Long,
    val visible: Boolean,
  )

  private data class JsTransfer(
    val handle: Long,
    val rawJson: String,
  )

  private data class DevToolsCall(
    val handle: Long,
    val callId: Long,
    val methodName: String,
    val paramsJson: String,
  )

  private class FakeWinWebView2Bridge : WinWebView2BridgeApi {
    lateinit var callbacks: WinWebView2Bridge.Callbacks
      private set

    val createdHandles = mutableListOf<Long>()
    val createParentHwnds = mutableListOf<Long>()
    val destroyedHandles = mutableListOf<Long>()
    val bounds = mutableListOf<BoundsRecord>()
    val visibility = mutableListOf<Visibility>()
    val htmlLoads = mutableListOf<HtmlLoad>()
    val urlLoads = mutableListOf<UrlLoad>()
    val jsTransfers = mutableListOf<JsTransfer>()
    val devToolsCalls = mutableListOf<DevToolsCall>()
    val documentStartScripts = mutableListOf<String>()
    val configurations = mutableListOf<WinWebView2Configuration>()
    val focusedHandles = mutableListOf<Long>()
    val callOrder = mutableListOf<String>()
    var focusFailure: IllegalStateException? = null
    var deferProfilerStop: Boolean = false
    var autoDestroyCallback: Boolean = true
    var pendingProfilerStopCallId: Long? = null
    private var nextHandle = 1L

    override fun create(
      parentHwnd: Long,
      userDataDir: String,
      documentStartScript: String,
      configuration: WinWebView2Configuration,
      callbacks: WinWebView2Bridge.Callbacks,
    ): Long {
      this.callbacks = callbacks
      configurations.add(configuration)
      documentStartScripts.add(documentStartScript)
      createParentHwnds.add(parentHwnd)
      callOrder.add("create:$parentHwnd")
      return nextHandle++.also { createdHandles.add(it) }
    }

    override fun destroy(handle: Long) {
      destroyedHandles.add(handle)
      callOrder.add("destroy:$handle")
      if (autoDestroyCallback) {
        callbacks.onDestroyed(handle)
      }
    }

    override fun setBounds(handle: Long, x: Int, y: Int, width: Int, height: Int, scale: Double) {
      bounds.add(BoundsRecord(handle, Bounds(x, y, width, height, scale)))
      callOrder.add("bounds:$handle:$x:$y:$width:$height:$scale")
    }

    override fun setVisible(handle: Long, visible: Boolean) {
      visibility.add(Visibility(handle, visible))
      callOrder.add("visible:$handle:$visible")
    }

    override fun focus(handle: Long) {
      focusedHandles.add(handle)
      focusFailure?.let { throw it }
    }

    override fun loadUrl(handle: Long, url: String) {
      urlLoads.add(UrlLoad(handle, url))
    }

    override fun setVirtualHostNameToFolderMapping(handle: Long, hostName: String, folderPath: String) {
    }

    override fun loadHtml(handle: Long, html: String, baseUrl: String?) {
      htmlLoads.add(HtmlLoad(handle, html, baseUrl))
    }

    override fun evaluateJavaScript(handle: Long, evalId: Long, script: String) {
    }

    override fun callDevToolsProtocolMethod(handle: Long, callId: Long, methodName: String, paramsJson: String) {
      devToolsCalls.add(DevToolsCall(handle, callId, methodName, paramsJson))
      callOrder.add("devtools:$methodName")
      if (methodName == "Profiler.stop" && deferProfilerStop) {
        pendingProfilerStopCallId = callId
      }
      else {
        callbacks.onDevToolsProtocolMethodResult(callId, "{}", null)
      }
    }

    override fun transferToJs(handle: Long, rawJson: String) {
      jsTransfers.add(JsTransfer(handle, rawJson))
    }

    fun completeDevToolsCall(callId: Long, result: String?, error: String?) {
      callbacks.onDevToolsProtocolMethodResult(callId, result, error)
    }
  }

  private data class BoundsRecord(
    val handle: Long,
    val bounds: Bounds,
  )
}
