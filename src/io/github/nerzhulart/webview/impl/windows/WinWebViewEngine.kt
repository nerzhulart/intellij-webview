// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.windows

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.util.registry.Registry
import io.github.nerzhulart.webview.api.WebViewAssetPath
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.impl.SwingWebViewHostPanel
import io.github.nerzhulart.webview.impl.WebViewAssetResolver
import io.github.nerzhulart.webview.impl.WebViewAssetResponse
import io.github.nerzhulart.webview.impl.WEBVIEW_CONSOLE_NOTIFICATION_METHOD
import io.github.nerzhulart.webview.impl.resolveWebViewAssetUrl
import io.github.nerzhulart.webview.impl.webViewAssetCustomSchemeUrl
import io.github.nerzhulart.webview.impl.webViewAssetHttpsUrl
import io.github.nerzhulart.webview.impl.WebViewJsMessageReceiver
import io.github.nerzhulart.webview.impl.engine.WebViewEngine
import io.github.nerzhulart.webview.impl.engine.WebViewScript
import io.github.nerzhulart.webview.impl.traceWebViewPerf
import io.github.nerzhulart.webview.impl.traceWebViewPerfSince
import io.github.nerzhulart.webview.impl.webViewLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.annotations.ApiStatus
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

private val LOG = logger<WinWebViewEngine>()

@ApiStatus.Internal
internal class WinWebViewEngine(
  parentScope: CoroutineScope,
  private val bridge: WinWebView2BridgeApi = NativeWinWebView2BridgeApi,
  private val debugName: String? = null,
  documentStartScripts: List<WebViewScript> = emptyList(),
  private val webViewDispatcher: CoroutineDispatcher = ImmediateWebViewDispatcher,
  private val devToolsCpuProfilingEnabled: () -> Boolean = { Registry.get(DEVTOOLS_CPU_PROFILING_REGISTRY_KEY).asBoolean() },
  private val customSchemeAssetLoadingEnabled: () -> Boolean = { Registry.get(WINDOWS_ASSET_CUSTOM_SCHEME_REGISTRY_KEY).asBoolean() },
  private val componentHwndResolver: (Component) -> Long? = WindowsHwndUtil::resolveComponentHwnd,
) : WebViewEngine {
  override val isHeavyweight: Boolean = true

  private val canvas = object : Canvas() {
    init {
      // The AWT peer erases with the component background; the default near-white brush is a
      // major part of the perceived flash while the native controller has no frame to present.
      background = java.awt.Color(HOST_BACKGROUND_RGB)
    }

    override fun addNotify() {
      // AWT creates the peer with whatever bounds the component carries over from its previous
      // parent, and the peer is a child of the frame HWND, so it appears at (0, 0) of the client
      // area in full size until Swing lays it out - a white rectangle over the whole UI for about
      // a hundred milliseconds. A peer born empty has nothing to show, and the layout that follows
      // gives it its real bounds.
      val beforePeerCreation = bounds
      setBounds(0, 0, 0, 0)
      super.addNotify()
      reattachControllerAfterPeerCreation()
      revalidate()
      // Being added to an already valid container produces no layout pass, and then nobody would
      // ever give the canvas its size back.
      SwingUtilities.invokeLater {
        if (isDisplayable && (width == 0 || height == 0) && !beforePeerCreation.isEmpty) {
          bounds = beforePeerCreation
        }
      }
    }

    override fun removeNotify() {
      parkControllerBeforePeerDisposal(this)
      super.removeNotify()
    }
  }

  override val component: JComponent = JPanel(BorderLayout()).apply {
    isOpaque = false
    isFocusable = true
    isRequestFocusEnabled = true
    add(canvas, BorderLayout.CENTER)
  }

  private enum class State { New, Creating, Active, Closing, Closed }

  private enum class FocusOp { Focus, Clear }

  private sealed interface PendingLoad {
    data class Url(val url: String) : PendingLoad
    data class Html(val html: String, val baseUrl: String?) : PendingLoad
  }

  private data class DevToolsCallResult(
    val result: String?,
    val error: String?,
  )

  /**
   * The whole Swing-side placement in one immutable snapshot. `parentHwnd == 0` means the AWT peer
   * is gone; `visible == false` means Swing does not show the host, which the native side expresses
   * as controller bounds only - `put_IsVisible` is never called.
   */
  internal data class HostState(
    val parentHwnd: Long,
    val width: Int,
    val height: Int,
    val visible: Boolean,
  )

  private val state = AtomicReference(State.New)
  private val closeCompleted = CompletableDeferred<Unit>()

  @Suppress("RAW_SCOPE_CREATION") // Intentional: engine manages its own child scope lifecycle with close()
  private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

  private val handleReady = AtomicReference(CompletableDeferred<Long>())
  private val nextEvalId = AtomicLong(0)
  private val pendingEvals = ConcurrentHashMap<Long, (String?) -> Unit>()
  private val pendingDevToolsCalls = ConcurrentHashMap<Long, (String?, String?) -> Unit>()
  private val activeAssetResolver = AtomicReference<WebViewAssetResolver?>(null)
  private val recoveryAttempts = ArrayDeque<Long>()
  private val documentStartScript = documentStartScripts.joinToString("\n;\n") { it.script }
  private val nativeCreateStartedAt = AtomicReference<TimeMark?>(null)
  private val firstLoadRequestedAt = AtomicReference<TimeMark?>(null)
  private val firstLoadApplied = AtomicBoolean(false)
  private val consoleFramesBeforeFirstNavigation = AtomicInteger(0)
  private val consoleCharsBeforeFirstNavigation = AtomicLong(0)
  private val firstNavigationCompleted = AtomicBoolean(false)
  private val consoleStartupSummaryLogged = AtomicBoolean(false)
  private val devToolsCpuProfileStartRequested = AtomicBoolean(false)
  private val devToolsCpuProfileStarted = AtomicBoolean(false)
  private val devToolsCpuProfileStopRequested = AtomicBoolean(false)

  @Volatile
  private var nativeHandle: Long = 0

  @Volatile
  private var inboundMessageHandler: (String) -> Unit = {}

  private val pendingLoad = AtomicReference<PendingLoad?>(null)
  private val loadScheduled = AtomicBoolean(false)

  @Volatile
  private var lastLoad: PendingLoad? = null

  private val pendingFocusOp = AtomicReference<FocusOp?>(null)
  private val focusScheduled = AtomicBoolean(false)

  /** The only placement channel: the latest requested snapshot, its coalescer and what native has. */
  private val pendingState = AtomicReference<HostState?>(null)
  private val syncScheduled = AtomicBoolean(false)

  @Volatile
  private var lastApplied: HostState? = null

  private val hostGeneration = AtomicLong(0)

  /** Reveal gate: the parent whose surface already holds a frame, plus the raw Swing snapshot. */
  @Volatile
  private var revealedParent: Long = 0

  @Volatile
  private var lastObservedState: HostState? = null

  @Volatile
  private var revealBlockedSince: TimeMark? = null

  private val revealRecheckScheduled = AtomicBoolean(false)

  private val currentParentHwnd: Long
    get() = lastApplied?.parentHwnd ?: 0

  @Volatile
  private var shortcutTarget: Component? = null

  @Volatile
  private var focusGainedHandler: () -> Unit = {}

  private var consecutiveRenderUnresponsiveCount = 0

  private val callbacks = object : WinWebView2Bridge.Callbacks {
    override fun onCreated(handle: Long) {
      val currentState = state.get()
      if (currentState != State.Creating || (nativeHandle != 0L && nativeHandle != handle)) {
        // close() owns the handle while it is in Closing state. Destroying it here as well
        // would race the queued close task and can double-release the native WebView2 state.
        if (currentState != State.Closing || nativeHandle != handle) {
          bridge.destroy(handle)
        }
        return
      }

      nativeHandle = handle
      state.set(State.Active)
      consecutiveRenderUnresponsiveCount = 0
      handleReady.get().complete(handle)
      nativeCreateStartedAt.getAndSet(null)?.let { startedAt ->
        LOG.traceWebViewPerfSince("win-webview2.create.untilReady", startedAt, diagnosticDetails())
      }
      startDevToolsCpuProfileIfNeeded(handle)
      applyPendingState(handle)
      LOG.webViewLifecycle("win-webview2-create", "WebView2 ready${diagnosticContext()}")
    }

    override fun onCreateFailed(message: String) {
      if (state.get() != State.Closing) {
        state.set(State.Closed)
        closeCompleted.complete(Unit)
      }
      handleReady.get().completeExceptionally(IllegalStateException(message))
      cancelPendingEvaluations()
      clearActiveAssetResolver()
      LOG.error("Failed to initialize WebView2${messageWithContext(message)}")
    }

    override fun onDestroyed(handle: Long) {
      if (nativeHandle != handle) return
      val wasClosing = state.get() == State.Closing
      nativeHandle = 0
      handleReady.get().cancel(CancellationException("Engine closed"))
      cancelPendingEvaluations()
      clearActiveAssetResolver()
      state.set(State.Closed)
      closeCompleted.complete(Unit)
      LOG.webViewLifecycle("win-webview2-close", "native cleanup complete")
      if (!wasClosing) {
        scope.cancel(CancellationException("Native WebView2 host was destroyed"))
      }
    }

    override fun onMessage(raw: String) {
      recordConsoleStartupFrame(raw)
      inboundMessageHandler(raw)
    }

    override fun onEvaluationResult(evalId: Long, result: String?) {
      pendingEvals.remove(evalId)?.invoke(result)
    }

    override fun onEvaluationError(evalId: Long, message: String) {
      LOG.warn("WebView2 JavaScript evaluation failed: $message")
      pendingEvals.remove(evalId)?.invoke(null)
    }

    override fun onDevToolsProtocolMethodResult(callId: Long, result: String?, error: String?) {
      pendingDevToolsCalls.remove(callId)?.invoke(result, error)
    }

    override fun onAcceleratorKeyPressed(keyEventKind: Int, virtualKey: Int, modifiers: Int, keyEventLParam: Int): Boolean {
      return WinWebViewShortcutInterop.handleAcceleratorKeyPressed(shortcutTarget, keyEventKind, virtualKey, modifiers, keyEventLParam)
    }

    override fun onFocusGained() {
      focusGainedHandler()
    }

    override fun onLog(level: Int, message: String) {
      when {
        level >= NATIVE_DIAGNOSTIC_ERROR -> LOG.error(message)
        level >= NATIVE_DIAGNOSTIC_WARN -> LOG.warn(message)
        else -> LOG.trace(message)
      }
    }

    override fun onNativeDiagnostic(level: Int, event: String, message: String, data: String) {
      if (event == NATIVE_EVENT_NAVIGATION_COMPLETED && firstNavigationCompleted.compareAndSet(false, true)) {
        logConsoleStartupSummary(event, force = true)
        scheduleDevToolsCpuProfileStopAfterFirstNavigation()
      }
      logNativeDiagnostic(level, event, message, data)
      when (event) {
        NATIVE_EVENT_PROCESS_FAILED_FATAL, NATIVE_EVENT_BROWSER_PROCESS_EXITED_FATAL -> invokeOnWebView {
          recoverAfterFatalNativeFailure(event, message, data)
        }
        NATIVE_EVENT_PROCESS_FAILED_UNRESPONSIVE -> invokeOnWebView {
          handleRenderProcessUnresponsive(message, data)
        }
      }
    }

    override fun onAssetRequested(handle: Long, requestId: Long, url: String) {
      scope.launch(Dispatchers.IO) {
        val response = runCatching { resolveAsset(url) }
          .onFailure { LOG.warn("Failed to resolve WebView2 asset${diagnosticContext()}: $url", it) }
          .getOrNull()
        runCatching { bridge.completeAssetRequest(handle, requestId, response) }
          .onFailure {
            LOG.trace("Dropping completed WebView2 asset request during teardown")
            LOG.trace(it)
          }
      }
    }

    private fun resolveAsset(url: String): WinWebView2Bridge.AssetResponse? {
      val response = resolveWebViewAssetUrl(url, activeAssetResolver.get(), "windows") ?: return null
      return response.toNativeAssetResponse()
    }
  }

  override fun setFromJsHandler(handler: WebViewJsMessageReceiver) {
    inboundMessageHandler = handler::transferFromJs
  }

  /**
   * The single placement request. Everything downstream is derived from [desired]: the native side
   * is told the whole state and reconciles it, so the caller never sequences attach/bounds/visibility.
   */
  internal fun requestHostState(desired: HostState) {
    pendingState.set(desired)
    if (desired.parentHwnd != 0L && state.compareAndSet(State.New, State.Creating)) {
      invokeOnWebView { performCreate() }
      return
    }
    val currentState = state.get()
    if (currentState == State.Creating || currentState == State.Active) {
      scheduleSync()
    }
  }

  override fun requestFocus() {
    if (currentParentHwnd == 0L) return
    if (state.get() != State.Active) return
    pendingFocusOp.set(FocusOp.Focus)
    scheduleFocusApply()
  }

  override fun clearFocus() {
    if (currentParentHwnd == 0L) return
    if (state.get() != State.Active) return
    pendingFocusOp.set(FocusOp.Clear)
    scheduleFocusApply()
  }

  internal fun setShortcutTarget(target: Component?) {
    shortcutTarget = target
  }

  internal fun setFocusGainedHandler(handler: (() -> Unit)?) {
    focusGainedHandler = handler ?: {}
  }

  override suspend fun loadFile(file: Path) {
    clearActiveAssetResolver()
    loadUrlInternal(file.toUri().toString())
  }

  override suspend fun loadAsset(root: WebViewAssetRoot, entry: WebViewAssetPath, query: String?) {
    activeAssetResolver.set(WebViewAssetResolver(root))
    val url = if (isCustomSchemeAssetLoadingEnabled()) {
      webViewAssetCustomSchemeUrl(entry, query)
    }
    else {
      webViewAssetHttpsUrl(entry, query)
    }
    loadUrlInternal(url)
  }

  override suspend fun loadHtml(html: String, baseFile: Path?) {
    clearActiveAssetResolver()
    loadHtmlInternal(html, baseFile?.toUri()?.toString())
  }

  private fun loadUrlInternal(url: String) {
    val load = PendingLoad.Url(url)
    recordFirstLoadRequested()
    pendingLoad.set(load)
    lastLoad = load
    if (state.get() == State.Closed) return
    scheduleLoadApply()
  }

  private fun loadHtmlInternal(html: String, baseUrl: String?) {
    val load = PendingLoad.Html(html, baseUrl)
    recordFirstLoadRequested()
    pendingLoad.set(load)
    lastLoad = load
    if (state.get() == State.Closed) return
    scheduleLoadApply()
  }

  override suspend fun evaluateJavaScript(script: String): String? {
    if (state.get() == State.New || state.get() == State.Closing || state.get() == State.Closed) return null
    val handle = awaitHandle() ?: return null
    if (state.get() != State.Active) return null

    val evalId = nextEvalId.incrementAndGet()
    return suspendCancellableCoroutine { continuation ->
      pendingEvals[evalId] = { result ->
        if (continuation.isActive) {
          continuation.resume(result)
        }
      }

      continuation.invokeOnCancellation {
        pendingEvals.remove(evalId)
      }

      invokeOnWebView {
        if (state.get() != State.Active) {
          pendingEvals.remove(evalId)?.invoke(null)
          return@invokeOnWebView
        }
        bridge.evaluateJavaScript(handle, evalId, script)
      }
    }
  }

  override suspend fun transferToJs(rawJson: String) {
    val handle = nativeHandle
    if (handle == 0L || state.get() != State.Active) return
    invokeOnWebView {
      if (state.get() != State.Active) return@invokeOnWebView
      try {
        bridge.transferToJs(handle, rawJson)
      }
      catch (t: IllegalStateException) {
        LOG.trace("Dropping WebView2 message while the view is not ready")
        LOG.trace(t)
      }
    }
  }

  override suspend fun close() {
    logConsoleStartupSummary("close", force = false)
    loop@ while (true) {
      when (val current = state.get()) {
        State.New -> {
          if (state.compareAndSet(State.New, State.Closed)) {
            scope.cancel()
            cancelPendingEvaluations()
            clearActiveAssetResolver()
            handleReady.get().cancel(CancellationException("Engine closed before initialization"))
            closeCompleted.complete(Unit)
            LOG.webViewLifecycle("win-webview2-close", "closed from New state")
            return
          }
        }
        State.Creating, State.Active -> {
          if (state.compareAndSet(current, State.Closing)) break@loop
        }
        State.Closing, State.Closed -> {
          LOG.webViewLifecycle("win-webview2-close", "already closing/closed, idempotent no-op")
          closeCompleted.await()
          return
        }
      }
    }

    withContext(NonCancellable) {
      closeOwnedHandle()
    }
  }

  private suspend fun closeOwnedHandle() {
    try {
      stopDevToolsCpuProfile("close")
      cancelPendingEvaluations()
      clearActiveAssetResolver()

      val handle = nativeHandle
      if (handle != 0L) {
        try {
          invokeOnWebView {
            try {
              bridge.destroy(handle)
            }
            catch (t: Throwable) {
              nativeHandle = 0
              handleReady.get().cancel(CancellationException("Engine close dispatch failed"))
              state.set(State.Closed)
              closeCompleted.completeExceptionally(t)
              throw t
            }
          }
        }
        catch (t: Throwable) {
          if (!closeCompleted.isCompleted) {
            nativeHandle = 0
            handleReady.get().cancel(CancellationException("Engine close dispatch failed"))
            state.set(State.Closed)
            closeCompleted.completeExceptionally(t)
          }
          throw t
        }
      }
      else {
        handleReady.get().cancel(CancellationException("Engine closed"))
        state.set(State.Closed)
        closeCompleted.complete(Unit)
      }
      closeCompleted.await()
    }
    finally {
      scope.cancel()
    }
  }

  private fun applyPendingState(handle: Long) {
    pendingState.get()?.let { applyHostState(handle, it) }
    when (val load = pendingLoad.getAndSet(null)) {
      is PendingLoad.Url -> applyLoad(handle, load)
      is PendingLoad.Html -> applyLoad(handle, load)
      null -> Unit
    }
  }

  private fun performCreate() {
    val desired = pendingState.get() ?: return
    if (desired.parentHwnd == 0L || state.get() == State.Closed) return
    try {
      LOG.webViewLifecycle("win-webview2-create", "initializing WebView2${diagnosticContext()}")
      nativeCreateStartedAt.set(TimeSource.Monotonic.markNow())
      val generation = hostGeneration.incrementAndGet()
      nativeHandle = LOG.traceWebViewPerf("win-webview2.bridge.create.call", diagnosticDetails()) {
        bridge.create(desired.parentHwnd, generation, userDataDir().toString(), documentStartScript, HOST_BACKGROUND_ARGB, callbacks)
      }
      // The controller is born on this parent, so only the geometry part of the snapshot is left.
      lastApplied = desired
      bridge.setHostState(
        nativeHandle,
        desired.parentHwnd,
        desired.width,
        desired.height,
        desired.visible,
        generation,
      )
    }
    catch (t: Throwable) {
      state.set(State.Closed)
      closeCompleted.complete(Unit)
      handleReady.get().completeExceptionally(t)
      cancelPendingEvaluations()
      clearActiveAssetResolver()
      LOG.error("Failed to start WebView2 initialization${diagnosticContext()}", t)
    }
  }

  private fun scheduleSync() {
    if (!syncScheduled.compareAndSet(false, true)) return
    invokeOnWebView {
      syncScheduled.set(false)
      val handle = nativeHandle
      if (handle == 0L || state.get() == State.Closed) return@invokeOnWebView
      val desired = pendingState.get() ?: return@invokeOnWebView
      applyHostState(handle, desired)
    }
  }

  /**
   * The only place native placement is touched. The parent is re-set exclusively when it really
   * changed, and visibility is expressed through geometry - `put_IsVisible` is never called.
   */
  private fun applyHostState(handle: Long, desired: HostState) {
    val previous = lastApplied
    if (previous == desired) return
    // A new parent needs a new generation so that a park racing with this attach loses.
    val generation = if (previous == null || previous.parentHwnd != desired.parentHwnd) {
      hostGeneration.incrementAndGet()
    }
    else {
      hostGeneration.get()
    }
    lastApplied = desired
    bridge.setHostState(
      handle,
      desired.parentHwnd,
      desired.width,
      desired.height,
      desired.visible,
      generation,
    )
  }

  private fun scheduleFocusApply() {
    if (!focusScheduled.compareAndSet(false, true)) return
    invokeOnWebView {
      focusScheduled.set(false)
      val handle = nativeHandle
      if (handle == 0L || state.get() != State.Active) {
        pendingFocusOp.set(null)
        return@invokeOnWebView
      }
      val focusOp = pendingFocusOp.getAndSet(null)
      try {
        when (focusOp) {
          FocusOp.Focus -> bridge.focus(handle)
          FocusOp.Clear -> bridge.clearFocus(handle)
          null -> Unit
        }
      }
      catch (e: IllegalStateException) {
        LOG.trace("Failed to apply WebView2 focus operation: operation=$focusOp${diagnosticContext()}")
        LOG.trace(e)
      }
    }
  }

  private fun scheduleLoadApply() {
    if (!loadScheduled.compareAndSet(false, true)) return
    invokeOnWebView {
      loadScheduled.set(false)
      val handle = nativeHandle
      if (handle == 0L || state.get() != State.Active) return@invokeOnWebView
      when (val load = pendingLoad.getAndSet(null)) {
        is PendingLoad.Url -> applyLoad(handle, load)
        is PendingLoad.Html -> applyLoad(handle, load)
        null -> Unit
      }
    }
  }

  private suspend fun awaitHandle(): Long? {
    val handle = nativeHandle
    if (handle != 0L && state.get() == State.Active) return handle

    return try {
      handleReady.get().await()
    }
    catch (_: Throwable) {
      null
    }
  }

  private fun logNativeDiagnostic(level: Int, event: String, message: String, data: String) {
    val formattedMessage = buildString {
      append("WebView2 native diagnostic")
      append(diagnosticContext())
      append(": ")
      append(event)
      if (message.isNotBlank()) {
        append(" - ")
        append(message)
      }
      if (data.isNotBlank()) {
        append(" [")
        append(data.replace('\n', ';'))
        append(']')
      }
    }
    when {
      level >= NATIVE_DIAGNOSTIC_ERROR -> LOG.error(formattedMessage)
      level >= NATIVE_DIAGNOSTIC_WARN -> LOG.warn(formattedMessage)
      else -> LOG.trace(formattedMessage)
    }
  }

  private fun handleRenderProcessUnresponsive(message: String, data: String) {
    consecutiveRenderUnresponsiveCount++
    if (consecutiveRenderUnresponsiveCount >= MAX_RENDER_UNRESPONSIVE_BEFORE_RECOVERY) {
      recoverAfterFatalNativeFailure(NATIVE_EVENT_PROCESS_FAILED_UNRESPONSIVE, message, data)
    }
  }

  private fun recoverAfterFatalNativeFailure(event: String, message: String, data: String) {
    val current = state.get()
    if (current == State.Closing || current == State.Closed) return

    val parentHwnd = currentParentHwnd
    if (parentHwnd == 0L) {
      failPermanently(event, message, data, IllegalStateException("Cannot recover WebView2 without a parent HWND"))
      return
    }
    if (!recordRecoveryAttempt()) {
      failPermanently(event, message, data, IllegalStateException("WebView2 recovery limit exceeded"))
      return
    }

    val oldHandle = nativeHandle
    nativeHandle = 0
    state.set(State.Creating)
    cancelPendingEvaluations()
    resetHandleReady("WebView2 is recovering after $event")
    pendingLoad.set(lastLoad)
    // The replacement controller is born on the same parent, so only its geometry has to be replayed.
    val placement = lastApplied
    lastApplied = null
    consecutiveRenderUnresponsiveCount = 0

    if (oldHandle != 0L) {
      runCatching { bridge.destroy(oldHandle) }
        .onFailure { LOG.error("Failed to destroy crashed WebView2 handle${diagnosticContext()}", it) }
    }

    try {
      LOG.webViewLifecycle("win-webview2-recovery", "recreating after $event${diagnosticContext()}")
      nativeCreateStartedAt.set(TimeSource.Monotonic.markNow())
      nativeHandle = LOG.traceWebViewPerf("win-webview2.bridge.create.call", "recovery=true, ${diagnosticDetails()}") {
        bridge.create(parentHwnd, hostGeneration.get(), userDataDir().toString(), documentStartScript, HOST_BACKGROUND_ARGB, callbacks)
      }
      lastApplied = placement
      placement?.let {
        bridge.setHostState(
          nativeHandle,
          it.parentHwnd,
          it.width,
          it.height,
          it.visible,
          hostGeneration.get(),
        )
      }
    }
    catch (t: Throwable) {
      failPermanently(event, message, data, t)
    }
  }

  private fun failPermanently(event: String, message: String, data: String, cause: Throwable) {
    val oldHandle = nativeHandle
    nativeHandle = 0
    state.set(State.Closed)
    closeCompleted.complete(Unit)
    cancelPendingEvaluations()
    clearActiveAssetResolver()
    scope.cancel()
    handleReady.get().completeExceptionally(cause)
    if (oldHandle != 0L) {
      runCatching { bridge.destroy(oldHandle) }
        .onFailure { LOG.error("Failed to destroy WebView2 handle after fatal failure${diagnosticContext()}", it) }
    }
    LOG.error("WebView2 engine closed after fatal native failure${diagnosticContext()}: $event - $message [$data]", cause)
  }

  private fun recordRecoveryAttempt(): Boolean {
    val now = System.currentTimeMillis()
    while (recoveryAttempts.isNotEmpty() && now - recoveryAttempts.first() > RECOVERY_WINDOW_MILLIS) {
      recoveryAttempts.removeFirst()
    }
    if (recoveryAttempts.size >= MAX_RECOVERY_ATTEMPTS) return false
    recoveryAttempts.addLast(now)
    return true
  }

  private fun resetHandleReady(reason: String) {
    val next = CompletableDeferred<Long>()
    val previous = handleReady.getAndSet(next)
    if (!previous.isCompleted) {
      previous.cancel(CancellationException(reason))
    }
  }

  private fun diagnosticContext(): String {
    return debugName?.let { " [$it]" }.orEmpty()
  }

  private fun messageWithContext(message: String): String {
    val context = diagnosticContext()
    return when {
      message.isBlank() -> context
      context.isBlank() -> ": $message"
      else -> "$context: $message"
    }
  }

  private fun cancelPendingEvaluations() {
    pendingEvals.keys.forEach { evalId ->
      pendingEvals.remove(evalId)?.invoke(null)
    }
    pendingDevToolsCalls.keys.forEach { callId ->
      pendingDevToolsCalls.remove(callId)?.invoke(null, "cancelled")
    }
  }

  private fun clearActiveAssetResolver() {
    activeAssetResolver.set(null)
  }

  private fun recordFirstLoadRequested() {
    firstLoadRequestedAt.compareAndSet(null, TimeSource.Monotonic.markNow())
  }

  private fun applyLoad(handle: Long, load: PendingLoad) {
    val firstLoad = firstLoadApplied.compareAndSet(false, true)
    if (firstLoad) {
      firstLoadRequestedAt.getAndSet(null)?.let { requestedAt ->
        LOG.traceWebViewPerfSince("win-webview2.firstLoad.waitBeforeNativeCall", requestedAt, loadDiagnosticDetails(load))
      }
    }

    if (firstLoad) {
      LOG.traceWebViewPerf("win-webview2.firstLoad.nativeCall", loadDiagnosticDetails(load)) {
        applyLoadToBridge(handle, load)
      }
    }
    else {
      applyLoadToBridge(handle, load)
    }
  }

  private fun applyLoadToBridge(handle: Long, load: PendingLoad) {
    when (load) {
      is PendingLoad.Url -> {
        startDevToolsCpuProfileIfNeeded(handle)
        bridge.loadUrl(handle, load.url)
      }
      is PendingLoad.Html -> bridge.loadHtml(handle, load.html, load.baseUrl)
    }
  }

  private fun startDevToolsCpuProfileIfNeeded(handle: Long) {
    if (!isDevToolsCpuProfilingEnabled()) return
    if (!devToolsCpuProfileStartRequested.compareAndSet(false, true)) return
    callDevToolsProtocolMethod(handle, "Profiler.enable", "{}") { _, enableError ->
      if (enableError != null) {
        LOG.warn("Failed to enable WebView2 DevTools CPU profiler${diagnosticContext()}: $enableError")
      }
    }
    devToolsCpuProfileStarted.set(true)
    callDevToolsProtocolMethod(handle, "Profiler.start", "{}") { _, startError ->
      if (startError != null) {
        LOG.warn("Failed to start WebView2 DevTools CPU profiler${diagnosticContext()}: $startError")
        return@callDevToolsProtocolMethod
      }
      LOG.trace { "Started WebView2 DevTools CPU profile${diagnosticContext()}" }
    }
  }

  private suspend fun stopDevToolsCpuProfile(reason: String) {
    if (!devToolsCpuProfileStarted.get()) return
    if (!devToolsCpuProfileStopRequested.compareAndSet(false, true)) return
    val handle = nativeHandle
    if (handle == 0L) return

    val stopResult = withTimeoutOrNull(DEVTOOLS_CPU_PROFILE_STOP_TIMEOUT_MILLIS) {
      callDevToolsProtocolMethodAwait(handle, "Profiler.stop", "{}")
    }
    when {
      stopResult == null -> {
        LOG.warn("Timed out waiting for WebView2 DevTools CPU profiler to stop${diagnosticContext()}")
      }
      stopResult.error != null -> {
        LOG.warn("Failed to stop WebView2 DevTools CPU profiler${diagnosticContext()}: ${stopResult.error}")
      }
      stopResult.result.isNullOrBlank() -> {
        LOG.warn("WebView2 DevTools CPU profiler returned empty result${diagnosticContext()}")
      }
      else -> writeDevToolsCpuProfile(stopResult.result, reason)
    }
  }

  private fun scheduleDevToolsCpuProfileStopAfterFirstNavigation() {
    if (!devToolsCpuProfileStarted.get()) return
    // TODO: Replace this coarse post-navigation snapshot with real startup profiling
    // that stops at first meaningful WebView content paint/readiness.
    scope.launch {
      delay(DEVTOOLS_CPU_PROFILE_POST_NAVIGATION_DELAY_MILLIS)
      stopDevToolsCpuProfile("post-navigation-delay")
    }
  }

  private suspend fun callDevToolsProtocolMethodAwait(handle: Long, methodName: String, paramsJson: String): DevToolsCallResult? {
    return suspendCancellableCoroutine { continuation ->
      val callId = callDevToolsProtocolMethod(handle, methodName, paramsJson) { result, error ->
        if (continuation.isActive) {
          continuation.resume(DevToolsCallResult(result, error))
        }
      }
      if (callId == null) {
        continuation.resume(null)
        return@suspendCancellableCoroutine
      }
      continuation.invokeOnCancellation {
        pendingDevToolsCalls.remove(callId)
      }
    }
  }

  private suspend fun writeDevToolsCpuProfile(result: String, reason: String) {
    withContext(Dispatchers.IO) {
      writeDevToolsCpuProfileBlocking(result, reason)
    }
  }

  private fun writeDevToolsCpuProfileBlocking(result: String, reason: String) {
    val profile = extractDevToolsCpuProfile(result)
    val directory = Path.of(PathManager.getLogPath(), "webview-cpu-profiles")
    val fileName = "webview2-${safeProfileName()}-${System.currentTimeMillis()}-$reason.cpuprofile"
    val file = directory.resolve(fileName)
    runCatching {
      Files.createDirectories(directory)
      Files.writeString(file, profile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }.onSuccess {
      LOG.info("Saved WebView2 DevTools CPU profile${diagnosticContext()}: $file")
    }.onFailure { t ->
      LOG.warn("Failed to save WebView2 DevTools CPU profile${diagnosticContext()}: ${t.message}")
    }
  }

  private fun isDevToolsCpuProfilingEnabled(): Boolean {
    return devToolsCpuProfilingEnabled()
  }

  private fun isCustomSchemeAssetLoadingEnabled(): Boolean {
    return customSchemeAssetLoadingEnabled()
  }

  private fun callDevToolsProtocolMethod(handle: Long, methodName: String, paramsJson: String, onResult: (String?, String?) -> Unit): Long? {
    val callId = nextEvalId.incrementAndGet()
    pendingDevToolsCalls[callId] = onResult
    invokeOnWebView {
      try {
        bridge.callDevToolsProtocolMethod(handle, callId, methodName, paramsJson)
      }
      catch (t: IllegalStateException) {
        pendingDevToolsCalls.remove(callId)?.invoke(null, t.message)
        LOG.warn("Failed to call WebView2 DevTools protocol method $methodName${diagnosticContext()}: ${t.message}")
      }
    }
    return callId
  }

  private fun extractDevToolsCpuProfile(result: String): String {
    val profileKey = "\"profile\""
    val keyIndex = result.indexOf(profileKey)
    if (keyIndex < 0) return result
    val objectStart = result.indexOf('{', keyIndex + profileKey.length)
    if (objectStart < 0) return result

    var depth = 0
    var inString = false
    var escaping = false
    for (index in objectStart until result.length) {
      val ch = result[index]
      if (escaping) {
        escaping = false
        continue
      }
      if (ch == '\\' && inString) {
        escaping = true
        continue
      }
      if (ch == '"') {
        inString = !inString
        continue
      }
      if (inString) continue
      when (ch) {
        '{' -> depth++
        '}' -> {
          depth--
          if (depth == 0) return result.substring(objectStart, index + 1)
        }
      }
    }
    return result
  }

  private fun safeProfileName(): String {
    val base = debugName.orEmpty().ifBlank { "webview" }
    return base.map { ch -> if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '-' }
      .joinToString("")
      .trim('-')
      .ifBlank { "webview" }
  }

  private fun recordConsoleStartupFrame(raw: String) {
    if (firstNavigationCompleted.get() || !raw.contains(WEBVIEW_CONSOLE_NOTIFICATION_METHOD)) return
    consoleFramesBeforeFirstNavigation.incrementAndGet()
    consoleCharsBeforeFirstNavigation.addAndGet(raw.length.toLong())
  }

  private fun logConsoleStartupSummary(reason: String, force: Boolean) {
    val frames = consoleFramesBeforeFirstNavigation.get()
    val chars = consoleCharsBeforeFirstNavigation.get()
    if (!force && frames == 0 && firstLoadRequestedAt.get() == null && !firstLoadApplied.get()) return
    if (!consoleStartupSummaryLogged.compareAndSet(false, true)) return
    LOG.trace { "perf: win-webview2.console.beforeFirstNavigationComplete = 0ms - frames=$frames, chars=$chars, reason=$reason, ${diagnosticDetails()}" }
  }

  private fun loadDiagnosticDetails(load: PendingLoad): String {
    return when (load) {
      is PendingLoad.Url -> "load=Url, urlChars=${load.url.length}, ${diagnosticDetails()}"
      is PendingLoad.Html -> "load=Html, htmlChars=${load.html.length}, ${diagnosticDetails()}"
    }
  }

  private fun diagnosticDetails(): String {
    return "debugName=${debugName.orEmpty()}"
  }

  private fun WebViewAssetResponse.toNativeAssetResponse(): WinWebView2Bridge.AssetResponse {
    return WinWebView2Bridge.AssetResponse(
      statusCode = statusCode,
      statusText = statusText,
      headers = WinWebView2Bridge.AssetResponse.headers(contentType, headers),
      bytes = bytes,
    )
  }

  private fun userDataDir(): Path = Path.of(PathManager.getSystemPath(), "webview2")

  /**
   * Invokes the JNI facade. Production JNI calls enqueue typed native operations for AWT-Windows;
   * the injectable dispatcher is retained only to make coalescing and delayed-destroy behavior testable.
   *
   * Dispatch is not tied to the engine scope, so `close()` can enqueue native destruction after
   * cancellation and wait for [WinWebView2Bridge.Callbacks.onDestroyed].
   */
  private fun invokeOnWebView(action: () -> Unit) {
    webViewDispatcher.dispatch(EmptyCoroutineContext, Runnable { action() })
  }

  /**
   * Peer section
   */

  override fun attach(host: Component): Boolean {
    val hostPanel = host as SwingWebViewHostPanel
    return LOG.traceWebViewPerf(
      "win-webview2.host.attach",
      "displayable=${host.isDisplayable}, showing=${host.isShowing}, size=${host.width}x${host.height}",
    ) {
      setShortcutTarget(host)
      setFocusGainedHandler { hostPanel.nativeWebViewFocusGained() }
      syncHostState(host)
      true
    }
  }

  override fun detach() {
    if (shortcutTarget == null) return
    // No hide here: the Canvas peer disposal already takes the content off screen, and an
    // explicit visibility transition is exactly what flashes the whole frame on the next reveal.
    setShortcutTarget(null)
    setFocusGainedHandler(null)
    pendingState.set(null)
    lastApplied = null
    resetRevealGate()
  }

  override fun syncHostState(host: Component) {
    if (shortcutTarget == null) return
    val desired = gateRevealAfterReattach(readHostState(host))
    val timedUpdate = measureTimedValue { requestHostState(desired) }
    LOG.traceWebViewPerf("win-webview2.host.syncHostState", timedUpdate.duration, hostStateDiagnosticDetails(desired))
  }

  /**
   * A controller returning from the holder window has no frame for the new size yet, and the
   * freshly created AWT peer is still at its pre-layout position - showing it right away is
   * exactly the flash.
   * So the first snapshot on a new parent goes out hidden (same size, pushed under the client area,
   * which is enough for Chromium to lay out and present), and the content is revealed on a later
   * sync, once the geometry has repeated itself and the surface had time to get a frame.
   */
  internal fun gateRevealAfterReattach(observed: HostState): HostState {
    val previous = lastObservedState
    lastObservedState = observed
    if (observed.parentHwnd == 0L) {
      resetRevealGate()
      return observed
    }
    // The surface stays alive under the same parent, so plain Swing hide/show needs no gating.
    if (observed.parentHwnd == revealedParent || !observed.visible) return observed

    val blockedSince = revealBlockedSince ?: TimeSource.Monotonic.markNow().also { revealBlockedSince = it }
    val settled = previous != null &&
                  previous.parentHwnd == observed.parentHwnd &&
                  previous.copy(visible = observed.visible) == observed &&
                  blockedSince.elapsedNow() >= REVEAL_SETTLE_DELAY
    if (!settled && blockedSince.elapsedNow() < REVEAL_MAX_WAIT) {
      scheduleRevealRecheck()
      return observed.copy(visible = false)
    }

    revealedParent = observed.parentHwnd
    revealBlockedSince = null
    return observed
  }

  /** Swing may have no more events to send, so the gate re-reads the host itself. */
  private fun scheduleRevealRecheck() {
    if (!revealRecheckScheduled.compareAndSet(false, true)) return
    scope.launch {
      delay(REVEAL_SETTLE_DELAY)
      SwingUtilities.invokeLater {
        revealRecheckScheduled.set(false)
        shortcutTarget?.let { syncHostState(it) }
      }
    }
  }

  private fun resetRevealGate() {
    revealedParent = 0
    lastObservedState = null
    revealBlockedSince = null
  }

  /**
   * Reads the whole Swing-side placement in one pass. A dead peer reads as `parentHwnd == 0`, and a
   * host Swing does not show - or one with an empty rectangle - simply reads as `visible = false`.
   */
  private fun readHostState(host: Component): HostState {
    val width = canvas.width
    val height = canvas.height
    return HostState(
      parentHwnd = componentHwndResolver(canvas) ?: 0L,
      width = width,
      height = height,
      visible = host.isShowing && canvas.isShowing && width > 0 && height > 0,
    )
  }

  private fun hostStateDiagnosticDetails(hostState: HostState): String {
    return "parent=0x${java.lang.Long.toHexString(hostState.parentHwnd)}, " +
           "size=${hostState.width}x${hostState.height}, visible=${hostState.visible}"
  }

  override fun clearFocusForSwingFocusTransfer() {
    clearFocus()
  }

  /**
   * Runs before JBR synchronously destroys the Canvas peer. The native bridge sends a bounded
   * barrier message to its own holder window, so the controller leaves the Canvas on AWT-Windows
   * before that HWND can receive WM_NCDESTROY.
   */
  private fun parkControllerBeforePeerDisposal(component: Component) {
    val handle = nativeHandle
    val generation = hostGeneration.get()
    if (handle == 0L || generation == 0L || state.get() == State.Closed) return
    val hostHwnd = WindowsHwndUtil.resolveComponentHwnd(component) ?: return
    val parked = runCatching {
      bridge.parkBeforePeerDispose(handle, hostHwnd, generation)
    }.onFailure {
      LOG.warn("Failed to park WebView2 before Canvas peer disposal${diagnosticContext()}", it)
    }.getOrDefault(false)
    if (!parked) {
      LOG.warn("WebView2 Canvas disposal barrier did not complete${diagnosticContext()}")
    }
    // The controller now hangs in the holder window, so nothing of the old placement is applied,
    // and its surface is frameless again - the next attach has to be gated before it is revealed.
    if (currentParentHwnd == hostHwnd) lastApplied = null
    resetRevealGate()
  }

  private fun reattachControllerAfterPeerCreation() {
    val host = shortcutTarget ?: return
    if (state.get() == State.Closed) return
    syncHostState(host)
  }


  private companion object {
    /** Shared by the Canvas peer brush and the controller default background, so that a frame
     * without page content is indistinguishable from the host behind it. */
    private const val HOST_BACKGROUND_RGB = 0x1E1F22
    private const val HOST_BACKGROUND_ARGB = 0xFF000000.toInt() or HOST_BACKGROUND_RGB
    private const val NATIVE_DIAGNOSTIC_WARN = 3
    private const val NATIVE_DIAGNOSTIC_ERROR = 4
    private const val MAX_RECOVERY_ATTEMPTS = 2
    private const val RECOVERY_WINDOW_MILLIS = 60_000L
    private const val MAX_RENDER_UNRESPONSIVE_BEFORE_RECOVERY = 2
    private const val NATIVE_EVENT_PROCESS_FAILED_FATAL = "process-failed.fatal"
    private const val NATIVE_EVENT_PROCESS_FAILED_UNRESPONSIVE = "process-failed.unresponsive"
    private const val NATIVE_EVENT_BROWSER_PROCESS_EXITED_FATAL = "browser-process-exited.fatal"
    private const val NATIVE_EVENT_NAVIGATION_COMPLETED = "navigation.completed"
    private const val DEVTOOLS_CPU_PROFILING_REGISTRY_KEY = "io.github.nerzhulart.webview.windows.devtools.cpu.profiling"
    private const val DEVTOOLS_CPU_PROFILE_STOP_TIMEOUT_MILLIS = 3_000L
    private const val DEVTOOLS_CPU_PROFILE_POST_NAVIGATION_DELAY_MILLIS = 2_000L
    private const val WINDOWS_ASSET_CUSTOM_SCHEME_REGISTRY_KEY = "io.github.nerzhulart.webview.windows.asset.custom.scheme.enabled"

    /** How long a reattached controller stays off screen: layout has to settle and a frame to arrive. */
    private val REVEAL_SETTLE_DELAY = 120.milliseconds

    /** A host that never stops moving is still shown eventually rather than staying invisible. */
    private val REVEAL_MAX_WAIT = 600.milliseconds
  }
}

@ApiStatus.Internal
internal fun createWinWebViewEngine(
  parentScope: CoroutineScope,
  debugName: String? = null,
  documentStartScripts: List<WebViewScript> = emptyList(),
): WinWebViewEngine {
  return WinWebViewEngine(parentScope, debugName = debugName, documentStartScripts = documentStartScripts)
}

private object ImmediateWebViewDispatcher : CoroutineDispatcher() {
  override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
    block.run()
  }
}
