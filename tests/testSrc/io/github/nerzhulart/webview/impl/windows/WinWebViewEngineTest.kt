// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.windows

import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.diagnostic.Logger
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.TestLoggerFactory
import com.intellij.testFramework.runInEdtAndWait
import io.github.nerzhulart.webview.api.WebViewAssetPath
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.impl.WEBVIEW_ASSET_CUSTOM_SCHEME
import io.github.nerzhulart.webview.impl.WEBVIEW_ASSET_CUSTOM_SCHEME_HOST
import io.github.nerzhulart.webview.impl.WEBVIEW_ASSET_HTTPS_HOST
import io.github.nerzhulart.webview.impl.SwingWebViewHostPanel
import io.github.nerzhulart.webview.impl.engine.WebViewScript
import io.github.nerzhulart.webview.impl.webViewAssetCustomSchemeUrl
import io.github.nerzhulart.webview.impl.webViewAssetHttpsUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Canvas
import java.awt.Component
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.JFrame
import kotlin.coroutines.CoroutineContext

internal class WinWebViewEngineTest {

  @Test
  fun nativeDiagnosticLevelsMapToLoggerLevels() {
    val factory = Logger.getFactory() as? TestLoggerFactory
    assertNotNull(factory, "WinWebViewEngineTest expects TestLoggerFactory")
    val marker = "win-webview2-diagnostic-${System.nanoTime()}"
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge)
    val engineLogger = Logger.getInstance(WinWebViewEngine::class.java)

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
        engine.syncHostState(42L, visible = false)
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
      assertEquals(emptyList<Visibility>(), bridge.visibility, "controller visibility must never be toggled")
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
      webViewDispatcher = SyncDispatcher,
    )
    try {
      runInEdtAndWait {
        engine.syncHostState(100L)
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
  fun closeDestroysHandleEvenWhenDispatchedTasksAreDelayed() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val dispatcher = QueuingDispatcher()
    val engine = createTestEngine(scope, bridge, debugName = "test", webViewDispatcher = dispatcher)
    runInEdtAndWait {
      engine.syncHostState(100L)
    }
    dispatcher.drain()
    runInEdtAndWait { bridge.callbacks.onCreated(bridge.createdHandles.last()) }

    val closeJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.close() }
    assertTrue(!closeJob.isCompleted, "close must wait for the queued native destroy")
    dispatcher.drain()
    runBlocking { closeJob.join() }

    assertEquals(listOf(1L), bridge.destroyedHandles)
    scope.cancel()
  }

  @Test
  fun closeWaitsUntilNativeConfirmsGlobalRefsWereReleased() {
    val bridge = FakeWinWebView2Bridge().apply { deferDestroyCompletion = true }
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge)

    val closeJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.close() }
    assertTrue(!closeJob.isCompleted, "close must wait for onDestroyed")
    assertEquals(listOf(1L), bridge.destroyedHandles)

    bridge.completeDestroy(1L)
    runBlocking { closeJob.join() }
    scope.cancel()
  }

  @Test
  fun windowsEngineExposesAHeavyweightCanvasHost() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createTestEngine(scope, bridge)
    try {
      assertTrue(engine.component.components.single() is Canvas)
    }
    finally {
      closeEngine(engine, scope)
    }
  }

  @Test
  fun concurrentCloseCallsWaitForOneNativeDestroy() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val dispatcher = QueuingDispatcher()
    val engine = createTestEngine(scope, bridge, debugName = "test", webViewDispatcher = dispatcher)
    runInEdtAndWait { engine.syncHostState(100L) }
    dispatcher.drain()
    runInEdtAndWait { bridge.callbacks.onCreated(bridge.createdHandles.single()) }

    val firstClose = scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.close() }
    val secondClose = scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.close() }
    assertTrue(!firstClose.isCompleted && !secondClose.isCompleted)
    assertEquals(1, dispatcher.pendingCount())

    dispatcher.drain()
    runBlocking {
      firstClose.join()
      secondClose.join()
    }

    assertEquals(listOf(1L), bridge.destroyedHandles)
    scope.cancel()
  }

  @Test
  fun closeDuringNativeCreationDestroysHandleOnce() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val dispatcher = QueuingDispatcher()
    val engine = createTestEngine(scope, bridge, debugName = "test", webViewDispatcher = dispatcher)
    runInEdtAndWait { engine.syncHostState(100L) }
    dispatcher.drain()
    val handle = bridge.createdHandles.single()

    val closeJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.close() }
    runInEdtAndWait { bridge.callbacks.onCreated(handle) }
    assertTrue(bridge.destroyedHandles.isEmpty(), bridge.destroyedHandles.toString())

    dispatcher.drain()
    runBlocking { closeJob.join() }

    assertEquals(listOf(handle), bridge.destroyedHandles)
    scope.cancel()
  }

  @Test
  fun createFailureDuringCloseDoesNotCompleteBeforeNativeDestroy() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val dispatcher = QueuingDispatcher()
    val engine = createTestEngine(scope, bridge, debugName = "test", webViewDispatcher = dispatcher)
    runInEdtAndWait { engine.syncHostState(100L) }
    dispatcher.drain()

    val closeJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { engine.close() }
    val logged = collectWarningsAndErrors {
      runInEdtAndWait { bridge.callbacks.onCreateFailed("creation failed") }
    }
    assertTrue(!closeJob.isCompleted, "native create failure must not bypass the queued destroy")
    assertTrue(logged.errors.any { it.contains("creation failed") }, logged.errors.toString())

    dispatcher.drain()
    runBlocking { closeJob.join() }

    assertEquals(bridge.createdHandles, bridge.destroyedHandles)
    scope.cancel()
  }

  @Test
  fun closeFailureLeavesEngineClosedAndIsReportedToEveryCaller() {
    val failure = IllegalStateException("destroy failed")
    val bridge = FakeWinWebView2Bridge().apply { destroyFailure = failure }
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge)

    val firstFailure = assertThrows(IllegalStateException::class.java) {
      runBlocking { engine.close() }
    }
    val repeatedFailure = assertThrows(IllegalStateException::class.java) {
      runBlocking { engine.close() }
    }

    assertEquals(failure.message, firstFailure.message)
    assertEquals(failure.message, repeatedFailure.message)
    assertEquals(listOf(1L), bridge.destroyedHandles)
    scope.cancel()
  }

  @Test
  fun hostStateSpamCoalescesIntoSingleApply() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val dispatcher = QueuingDispatcher()
    val engine = createTestEngine(scope, bridge, debugName = "test", webViewDispatcher = dispatcher)
    try {
      runInEdtAndWait { engine.syncHostState(100L) }
      dispatcher.drain()
      runInEdtAndWait { bridge.callbacks.onCreated(bridge.createdHandles.last()) }

      bridge.bounds.clear()
      runInEdtAndWait {
        repeat(10) { i ->
          engine.syncHostState(100L, i * 5, i * 5, 100 + i, 100 + i, 1.0)
        }
      }
      assertEquals(1, dispatcher.pendingCount(), "expected host state sync to coalesce into a single queued task")
      dispatcher.drain()
      assertEquals(1, bridge.bounds.size)
      assertEquals(Bounds(45, 45, 109, 109, 1.0), bridge.bounds[0].bounds)
    }
    finally {
      closeEngine(engine, dispatcher)
      scope.cancel()
    }
  }

  @Test
  fun attachToParentRespectsLatestParentOnce() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val dispatcher = QueuingDispatcher()
    val engine = createTestEngine(scope, bridge, debugName = "test", webViewDispatcher = dispatcher)
    try {
      runInEdtAndWait {
        engine.syncHostState(100L)
        engine.syncHostState(200L, 30, 40, 500, 400, 2.0)
      }
      dispatcher.drain()
      runInEdtAndWait { bridge.callbacks.onCreated(bridge.createdHandles.last()) }
      dispatcher.drain()

      assertEquals(listOf(200L), bridge.createParentHwnds,
                   "performCreate should pick up the latest parent set before it runs")
      assertEquals(emptyList<Long>(), bridge.attachParents,
                   "no follow-up bridge.attachToParent expected when create already used latest parent")
    }
    finally {
      closeEngine(engine, dispatcher)
      scope.cancel()
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
  fun createAppliesInitialBoundsAndNeverTogglesVisibility() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createTestEngine(scope, bridge, debugName = "test", webViewDispatcher = SyncDispatcher)
    try {
      runInEdtAndWait { engine.syncHostState(100L) }
      runInEdtAndWait { bridge.callbacks.onCreated(bridge.createdHandles.single()) }

      assertEquals(
        listOf("create:100", "bounds:1:10:20:300:200:1.5"),
        bridge.callOrder,
        "the controller is born on its parent, so only geometry is applied afterwards",
      )
    }
    finally {
      runBlocking { engine.close() }
      scope.cancel()
    }
  }

  @Test
  fun repeatedIdenticalHostStateProducesASingleNativeCall() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge)
    try {
      bridge.callOrder.clear()
      runInEdtAndWait { repeat(5) { engine.syncHostState(100L) } }

      assertEquals(emptyList<String>(), bridge.callOrder, "an unchanged snapshot must not reach the native side")

      runInEdtAndWait { engine.syncHostState(100L, width = 640) }
      assertEquals(listOf("bounds:1:10:20:640:200:1.5"), bridge.callOrder)
    }
    finally {
      closeEngine(engine, scope)
    }
  }

  @Test
  fun visibilityChangeKeepsTheParentAndNeverTogglesControllerVisibility() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge)
    try {
      bridge.attachParents.clear()
      bridge.visibility.clear()
      runInEdtAndWait {
        engine.syncHostState(100L, visible = false)
        engine.syncHostState(100L, visible = true)
      }

      assertEquals(emptyList<Long>(), bridge.attachParents, "visibility must not reparent the controller")
      assertEquals(emptyList<Visibility>(), bridge.visibility, "put_IsVisible must never be called")
    }
    finally {
      closeEngine(engine, scope)
    }
  }

  @Test
  fun deadPeerDoesNotSendVisibility() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge)
    try {
      bridge.visibility.clear()
      runInEdtAndWait {
        engine.detach()
        engine.syncHostState(0L, visible = false)
      }

      assertEquals(emptyList<Visibility>(), bridge.visibility)
    }
    finally {
      closeEngine(engine, scope)
    }
  }

  @Test
  fun displayableButNotShowingHostAttachesWithoutTogglingVisibility() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge, componentHwndResolver = { 200L })
    val host = SwingWebViewHostPanel(scope, engine)
    try {
      bridge.attachParents.clear()
      bridge.visibility.clear()
      runInEdtAndWait {
        host.setSize(300, 200)
        host.doLayout()
        engine.component.apply {
          setSize(300, 200)
          doLayout()
        }
        assertFalse(host.isShowing)
        engine.attach(host)
      }

      // "Not ready to show" is just visible=false in the snapshot: the controller still hangs on the
      // live canvas HWND, and its visibility is never toggled.
      assertEquals(listOf(200L), bridge.attachParents)
      assertEquals(emptyList<Visibility>(), bridge.visibility)
    }
    finally {
      closeEngine(engine, scope)
    }
  }

  @Test
  fun recreatedCanvasPeerReattachesParkedController() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    var canvasHwnd = 200L
    val engine = createActiveEngine(scope, bridge, componentHwndResolver = { canvasHwnd })
    var frame: JFrame? = null
    lateinit var canvas: Canvas
    try {
      runInEdtAndWait {
        val host = SwingWebViewHostPanel(scope, engine)
        frame = JFrame("WebView2 Canvas peer recreation test").apply {
          contentPane.add(host)
          setSize(500, 400)
          isVisible = true
          validate()
        }
        canvas = engine.component.components.single() as Canvas
        bridge.attachParents.clear()

        canvas.removeNotify()
        canvasHwnd = 300L
        canvas.addNotify()
      }

      assertEquals(listOf(300L), bridge.attachParents)
    }
    finally {
      runInEdtAndWait {
        frame?.dispose()
      }
      closeEngine(engine, scope)
    }
  }

  @Test
  fun reattachedControllerStaysHiddenUntilLayoutSettles() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge)
    try {
      val onNewPeer = WinWebViewEngine.HostState(300L, 0, 0, 640, 480, 1.0, true)

      assertFalse(
        engine.gateRevealAfterReattach(onNewPeer).visible,
        "a controller back from limbo has no frame yet, so the first snapshot on a new parent stays off screen",
      )

      Thread.sleep(300)
      assertTrue(
        engine.gateRevealAfterReattach(onNewPeer).visible,
        "the geometry repeated itself and the surface had time to present, so the content is revealed",
      )

      assertTrue(
        engine.gateRevealAfterReattach(onNewPeer.copy(width = 800)).visible,
        "a resize under a live peer is not re-gated",
      )
      // A plain Swing hide/show keeps the peer and its frame alive, so the gate must not delay it.
      assertFalse(engine.gateRevealAfterReattach(onNewPeer.copy(visible = false)).visible)
      assertTrue(engine.gateRevealAfterReattach(onNewPeer).visible)
    }
    finally {
      closeEngine(engine, scope)
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
      assertAssetResponse("first", requestAsset(firstBridge, 1L, url))
      assertAssetResponse("second", requestAsset(secondBridge, 1L, url))
    }
    finally {
      closeEngine(firstEngine, firstScope)
      closeEngine(secondEngine, secondScope)
    }
  }

  @Test
  fun deferredAssetRequestCompletesFromIoWithoutBlockingNativeCallback(@TempDir tempDir: Path) {
    Files.writeString(tempDir.resolve("index.html"), "deferred")
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge)
    try {
      runBlocking { engine.loadAsset(WebViewAssetRoot.fromDirectory(tempDir), WebViewAssetPath.indexHtml(), null) }
      bridge.callbacks.onAssetRequested(1L, 17L, webViewAssetCustomSchemeUrl(WebViewAssetPath.indexHtml()))

      val completion = runBlocking {
        withTimeout(1_000) {
          while (bridge.assetCompletions.isEmpty()) delay(10)
          bridge.assetCompletions.single()
        }
      }
      assertEquals(1L, completion.handle)
      assertEquals(17L, completion.requestId)
      assertAssetResponse("deferred", completion.response)
    }
    finally {
      closeEngine(engine, scope)
    }
  }

  @Test
  fun transferToJsWorksWhileWebViewIsHidden() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge)
    try {
      runInEdtAndWait { engine.syncHostState(100L, visible = false) }
      runBlocking { engine.transferToJs("{\"jsonrpc\":\"2.0\",\"method\":\"markdown.preview/contentChanged\"}") }

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
  fun transientFocusFailuresDoNotEscapeDispatcherTask() {
    val bridge = FakeWinWebView2Bridge()
    val scope = testScope()
    val engine = createActiveEngine(scope, bridge)
    try {
      bridge.focusFailure = IllegalStateException("focus failed")
      bridge.clearFocusFailure = IllegalStateException("clear focus failed")

      runInEdtAndWait {
        engine.requestFocus()
        engine.clearFocus()
      }

      assertEquals(listOf(1L), bridge.focusedHandles)
      assertEquals(listOf(1L), bridge.clearFocusedHandles)
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
    componentHwndResolver: (Component) -> Long? = WindowsHwndUtil::resolveComponentHwnd,
  ): WinWebViewEngine {
    val engine = createTestEngine(
      scope,
      bridge,
      debugName = "test",
      webViewDispatcher = SyncDispatcher,
      devToolsCpuProfilingEnabled = { devToolsCpuProfilingEnabled },
      customSchemeAssetLoadingEnabled = { customSchemeAssetLoadingEnabled },
      componentHwndResolver = componentHwndResolver,
    )
    runInEdtAndWait {
      engine.syncHostState(parentHwnd)
      bridge.callbacks.onCreated(bridge.createdHandles.last())
    }
    return engine
  }

  /** Drives the engine through its single placement contract without a real Swing hierarchy. */
  private fun WinWebViewEngine.syncHostState(
    parentHwnd: Long,
    x: Int = 10,
    y: Int = 20,
    width: Int = 300,
    height: Int = 200,
    scale: Double = 1.5,
    visible: Boolean = true,
  ) {
    requestHostState(WinWebViewEngine.HostState(parentHwnd, x, y, width, height, scale, visible))
  }

  // TODO: use scope of runBlocking in all tests
  private fun testScope(): CoroutineScope {
    @Suppress("RAW_SCOPE_CREATION") // Test scope has no parent fixture scope.
    return CoroutineScope(SupervisorJob())
  }

  /**
   * Runs every dispatched [Runnable] inline on the calling thread. The engine
   * uses `dispatcher.dispatch(...)` directly (not `launch`), so we cannot rely
   * on `Dispatchers.Unconfined` here — its `dispatch` throws.
   */
  private object SyncDispatcher : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
      block.run()
    }
  }

  /** Queues every dispatched runnable; runs them only on explicit [drain]. */
  private class QueuingDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
      synchronized(queue) { queue.addLast(block) }
    }

    fun pendingCount(): Int = synchronized(queue) { queue.size }

    fun drain() {
      while (true) {
        val next = synchronized(queue) { queue.removeFirstOrNull() } ?: return
        next.run()
      }
    }
  }

  private fun closeEngine(engine: WinWebViewEngine, scope: CoroutineScope) {
    runBlocking { engine.close() }
    runInEdtAndWait {}
    scope.cancel()
  }

  private fun closeEngine(engine: WinWebViewEngine, dispatcher: QueuingDispatcher) {
    runBlocking {
      val closeJob = launch(start = CoroutineStart.UNDISPATCHED) { engine.close() }
      dispatcher.drain()
      closeJob.join()
    }
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
    webViewDispatcher: CoroutineDispatcher = SyncDispatcher,
    devToolsCpuProfilingEnabled: () -> Boolean = { false },
    customSchemeAssetLoadingEnabled: () -> Boolean = { true },
    componentHwndResolver: (Component) -> Long? = WindowsHwndUtil::resolveComponentHwnd,
  ): WinWebViewEngine {
    return WinWebViewEngine(
      scope,
      bridge,
      debugName = debugName,
      documentStartScripts = documentStartScripts,
      webViewDispatcher = webViewDispatcher,
      devToolsCpuProfilingEnabled = devToolsCpuProfilingEnabled,
      customSchemeAssetLoadingEnabled = customSchemeAssetLoadingEnabled,
      componentHwndResolver = componentHwndResolver,
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
    val attachParents = mutableListOf<Long>()
    private val appliedParents = mutableMapOf<Long, Long>()
    val destroyedHandles = mutableListOf<Long>()
    val bounds = mutableListOf<BoundsRecord>()
    val visibility = mutableListOf<Visibility>()
    val htmlLoads = mutableListOf<HtmlLoad>()
    val urlLoads = mutableListOf<UrlLoad>()
    val jsTransfers = mutableListOf<JsTransfer>()
    val devToolsCalls = mutableListOf<DevToolsCall>()
    val assetCompletions = CopyOnWriteArrayList<AssetCompletion>()
    val documentStartScripts = mutableListOf<String>()
    val backgroundColors = mutableListOf<Int>()
    val focusedHandles = mutableListOf<Long>()
    val clearFocusedHandles = mutableListOf<Long>()
    val callOrder = mutableListOf<String>()
    var focusFailure: IllegalStateException? = null
    var clearFocusFailure: IllegalStateException? = null
    var deferProfilerStop: Boolean = false
    var deferDestroyCompletion: Boolean = false
    var destroyFailure: RuntimeException? = null
    var pendingProfilerStopCallId: Long? = null
    private var nextHandle = 1L

    override fun create(
      parentHwnd: Long,
      generation: Long,
      userDataDir: String,
      documentStartScript: String,
      backgroundColor: Int,
      callbacks: WinWebView2Bridge.Callbacks,
    ): Long {
      this.callbacks = callbacks
      backgroundColors.add(backgroundColor)
      documentStartScripts.add(documentStartScript)
      createParentHwnds.add(parentHwnd)
      callOrder.add("create:$parentHwnd")
      // The controller is born on this parent, so the native reconcile sees no parent change.
      return nextHandle++.also {
        createdHandles.add(it)
        appliedParents[it] = parentHwnd
      }
    }

    override fun destroy(handle: Long) {
      destroyedHandles.add(handle)
      callOrder.add("destroy:$handle")
      destroyFailure?.let { throw it }
      if (!deferDestroyCompletion) callbacks.onDestroyed(handle)
    }

    /**
     * Mirrors the native reconcile: the parent is re-applied only when it really changed, and the
     * controller visibility is never touched - [visibility] stays empty by construction.
     */
    override fun setHostState(
      handle: Long,
      parentHwnd: Long,
      x: Int,
      y: Int,
      width: Int,
      height: Int,
      scale: Double,
      visible: Boolean,
      generation: Long,
    ) {
      if (appliedParents.put(handle, parentHwnd) != parentHwnd) {
        attachParents.add(parentHwnd)
      }
      bounds.add(BoundsRecord(handle, Bounds(x, y, width, height, scale)))
      callOrder.add("bounds:$handle:$x:$y:$width:$height:$scale")
    }

    override fun parkBeforePeerDispose(handle: Long, hostHwnd: Long, parkingHwnd: Long, generation: Long): Boolean {
      appliedParents.remove(handle)
      return true
    }

    override fun focus(handle: Long) {
      focusedHandles.add(handle)
      focusFailure?.let { throw it }
    }

    override fun clearFocus(handle: Long) {
      clearFocusedHandles.add(handle)
      clearFocusFailure?.let { throw it }
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

    override fun completeAssetRequest(handle: Long, requestId: Long, response: WinWebView2Bridge.AssetResponse?) {
      assetCompletions.add(AssetCompletion(handle, requestId, response))
    }

    fun completeDevToolsCall(callId: Long, result: String?, error: String?) {
      callbacks.onDevToolsProtocolMethodResult(callId, result, error)
    }

    fun completeDestroy(handle: Long) {
      callbacks.onDestroyed(handle)
    }
  }

  private data class BoundsRecord(
    val handle: Long,
    val bounds: Bounds,
  )

  private data class AssetCompletion(
    val handle: Long,
    val requestId: Long,
    val response: WinWebView2Bridge.AssetResponse?,
  )

  private fun requestAsset(bridge: FakeWinWebView2Bridge, requestId: Long, url: String): WinWebView2Bridge.AssetResponse? {
    bridge.callbacks.onAssetRequested(1L, requestId, url)
    return runBlocking {
      withTimeout(1_000) {
        while (true) {
          bridge.assetCompletions.firstOrNull { it.requestId == requestId }?.let { return@withTimeout it.response }
          delay(10)
        }
        @Suppress("UNREACHABLE_CODE")
        null
      }
    }
  }
}
