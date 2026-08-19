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
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
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
  private val webViewDispatcher: CoroutineDispatcher = WebView2Dispatcher.coroutineDispatcher,
  private val devToolsCpuProfilingEnabled: () -> Boolean = { Registry.get(DEVTOOLS_CPU_PROFILING_REGISTRY_KEY).asBoolean() },
  private val customSchemeAssetLoadingEnabled: () -> Boolean = { Registry.get(WINDOWS_ASSET_CUSTOM_SCHEME_REGISTRY_KEY).asBoolean() },
) : WebViewEngine {
  override val isHeavyweight: Boolean = true

  // TODO: return real canvas when attaching to real Canvas is implemented
  override val component: JComponent?
    get() = null

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

  private data class PendingBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val scale: Double,
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

  private val pendingBounds = AtomicReference<PendingBounds?>(null)
  private val boundsScheduled = AtomicBoolean(false)

  @Volatile
  private var lastBounds: PendingBounds? = null

  private val visibilityScheduled = AtomicBoolean(false)
  private val pendingFocusOp = AtomicReference<FocusOp?>(null)
  private val focusScheduled = AtomicBoolean(false)
  private val pendingAttachParent = AtomicLong(0)
  private val attachScheduled = AtomicBoolean(false)

  @Volatile
  private var currentParentHwnd: Long = 0

  @Volatile
  private var shortcutTarget: Component? = null

  @Volatile
  private var focusGainedHandler: () -> Unit = {}

  @Volatile
  private var beforeMouseFocusHandler: () -> Unit = {}

  @Volatile
  private var hidden = false

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

    override fun onBeforeMouseFocus() {
      beforeMouseFocusHandler()
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

    override fun resolveAsset(url: String): WinWebView2Bridge.AssetResponse? {
      val response = resolveWebViewAssetUrl(url, activeAssetResolver.get(), "windows") ?: return null
      return response.toNativeAssetResponse()
    }
  }

  override fun setFromJsHandler(handler: WebViewJsMessageReceiver) {
    inboundMessageHandler = handler::transferFromJs
  }

  internal fun attachToParent(parentHwnd: Long) {
    currentParentHwnd = parentHwnd
    pendingAttachParent.set(parentHwnd)
    if (state.compareAndSet(State.New, State.Creating)) {
      invokeOnWebView { performCreate() }
      return
    }
    val currentState = state.get()
    if (currentState == State.Creating || currentState == State.Active) {
      scheduleAttachApply()
    }
  }

  internal fun attachToParent(parentHwnd: Long, x: Int, y: Int, width: Int, height: Int, scale: Double) {
    pendingBounds.set(PendingBounds(x, y, width, height, scale))
    attachToParent(parentHwnd)
  }

  internal fun detachFromParent() {
    val handle = nativeHandle
    if (handle == 0L || state.get() == State.Closed) return
    invokeOnWebView { bridge.detachFromParent(handle) }
  }

  internal fun setBounds(x: Int, y: Int, width: Int, height: Int, scale: Double) {
    pendingBounds.set(PendingBounds(x, y, width, height, scale))
    if (state.get() == State.Closed) return
    scheduleBoundsApply()
  }

  internal fun setHidden(hidden: Boolean) {
    this.hidden = hidden
    if (state.get() == State.Closed) return
    scheduleVisibilityApply()
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

  internal fun setBeforeMouseFocusHandler(handler: (() -> Unit)?) {
    beforeMouseFocusHandler = handler ?: {}
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
            var destroyFailure: Throwable? = null
            try {
              nativeHandle = 0
              bridge.destroy(handle)
            }
            catch (t: Throwable) {
              destroyFailure = t
              throw t
            }
            finally {
              handleReady.get().cancel(CancellationException("Engine closed"))
              state.set(State.Closed)
              destroyFailure?.let(closeCompleted::completeExceptionally) ?: closeCompleted.complete(Unit)
              LOG.webViewLifecycle("win-webview2-close", "native cleanup complete")
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
    applyAttachmentState(handle)
    when (val load = pendingLoad.getAndSet(null)) {
      is PendingLoad.Url -> applyLoad(handle, load)
      is PendingLoad.Html -> applyLoad(handle, load)
      null -> Unit
    }
  }

  private fun applyAttachmentState(handle: Long) {
    pendingBounds.getAndSet(null)?.let {
      lastBounds = it
      applyBounds(handle, it)
    }
    applyVisibility(handle)
  }

  private fun performCreate() {
    val parent = pendingAttachParent.getAndSet(0)
    if (parent == 0L || state.get() == State.Closed) return
    try {
      LOG.webViewLifecycle("win-webview2-create", "initializing WebView2${diagnosticContext()}")
      nativeCreateStartedAt.set(TimeSource.Monotonic.markNow())
      nativeHandle = LOG.traceWebViewPerf("win-webview2.bridge.create.call", diagnosticDetails()) {
        bridge.create(parent, userDataDir().toString(), documentStartScript, callbacks)
      }
      applyAttachmentState(nativeHandle)
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

  private fun scheduleAttachApply() {
    if (!attachScheduled.compareAndSet(false, true)) return
    invokeOnWebView {
      attachScheduled.set(false)
      val handle = nativeHandle
      if (handle == 0L || state.get() == State.Closed) return@invokeOnWebView
      val parent = pendingAttachParent.getAndSet(0)
      if (parent == 0L) return@invokeOnWebView
      bridge.attachToParent(handle, parent)
      applyAttachmentState(handle)
    }
  }

  private fun applyBounds(handle: Long, bounds: PendingBounds) {
    bridge.setBounds(handle, bounds.x, bounds.y, bounds.width, bounds.height, bounds.scale)
  }

  private fun scheduleBoundsApply() {
    if (!boundsScheduled.compareAndSet(false, true)) return
    invokeOnWebView {
      boundsScheduled.set(false)
      val handle = nativeHandle
      if (handle == 0L || state.get() == State.Closed) return@invokeOnWebView
      val bounds = pendingBounds.getAndSet(null) ?: return@invokeOnWebView
      lastBounds = bounds
      applyBounds(handle, bounds)
    }
  }

  private fun scheduleVisibilityApply() {
    if (!visibilityScheduled.compareAndSet(false, true)) return
    invokeOnWebView {
      visibilityScheduled.set(false)
      val handle = nativeHandle
      if (handle == 0L || state.get() == State.Closed) return@invokeOnWebView
      applyVisibility(handle)
    }
  }

  private fun applyVisibility(handle: Long) {
    bridge.setVisible(handle, !hidden && state.get() == State.Active)
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
    lastBounds?.let { pendingBounds.set(it) }
    consecutiveRenderUnresponsiveCount = 0

    if (oldHandle != 0L) {
      runCatching { bridge.destroy(oldHandle) }
        .onFailure { LOG.error("Failed to destroy crashed WebView2 handle${diagnosticContext()}", it) }
    }

    try {
      LOG.webViewLifecycle("win-webview2-recovery", "recreating after $event${diagnosticContext()}")
      nativeCreateStartedAt.set(TimeSource.Monotonic.markNow())
      nativeHandle = LOG.traceWebViewPerf("win-webview2.bridge.create.call", "recovery=true, ${diagnosticDetails()}") {
        bridge.create(parentHwnd, userDataDir().toString(), documentStartScript, callbacks)
      }
      bridge.setVisible(nativeHandle, !hidden)
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
   * Schedules [action] for execution on the dedicated WebView2 STA thread.
   *
   * Returns immediately; safe to call from EDT or any other thread.
   * Tasks are delivered in FIFO order via `PostThreadMessageW` and are not
   * tied to the engine's coroutine scope — they will run even if the scope
   * is cancelled, which is required to let `close()` actually destroy the
   * native handle.
   */
  private fun invokeOnWebView(action: () -> Unit) {
    webViewDispatcher.dispatch(EmptyCoroutineContext, Runnable { action() })
  }

  /**
   * Peer section
   */

  private var attached = false
  private var hostHidden = true
  private var lastAppliedFrame: AppliedFrame? = null

  override fun attach(host: Component): Boolean {
    val hostPanel = host as SwingWebViewHostPanel
    return LOG.traceWebViewPerf(
      "win-webview2.host.attach",
      "displayable=${host.isDisplayable}, showing=${host.isShowing}, size=${host.width}x${host.height}",
    ) {
      setShortcutTarget(host)
      setBeforeMouseFocusHandler { hostPanel.activateWebViewFocusFromNativeMouse() }
      setFocusGainedHandler { hostPanel.nativeWebViewFocusGained() }
      attached = true
      hostHidden = true
      lastAppliedFrame = null
      setHidden(true)
      when (updateFrame(host)) {
        FrameUpdateResult.Applied -> Unit
        FrameUpdateResult.Deferred -> setHidden(true)
        FrameUpdateResult.Failed -> {
          attached = false
          setShortcutTarget(null)
          setBeforeMouseFocusHandler(null)
          setFocusGainedHandler(null)
          return@traceWebViewPerf false
        }
      }
      true
    }
  }

  override fun detach() {
    if (!attached) return
    detachFromParent()
    setShortcutTarget(null)
    setBeforeMouseFocusHandler(null)
    setFocusGainedHandler(null)
    attached = false
    hostHidden = true
    currentParentHwnd = 0
    lastAppliedFrame = null
  }

  override fun scheduleFrameUpdate(host: Component) {
    if (!attached) return
    updateFrame(host)
  }

  private fun updateFrame(host: Component): FrameUpdateResult {
    val timedUpdate = measureTimedValue {
      val parentHwnd = WindowsHwndUtil.resolveWindowHwnd(host)
      if (parentHwnd == null) {
        return@measureTimedValue FrameUpdate(FrameUpdateResult.Failed, "reason=no-parent-hwnd")
      }
      val anchor = SwingWebViewHostPanel.resolveWindowsAnchor(host)
      if (anchor == null) {
        return@measureTimedValue FrameUpdate(FrameUpdateResult.Failed, "reason=no-anchor")
      }
      val bounds = SwingWebViewHostPanel.calculateClippedBounds(host, anchor)
      val scale = WindowsHwndUtil.scale(host)
      val frame = AppliedFrame(bounds, scale)
      if (!isReadyForNativeFrame(bounds)) {
        lastAppliedFrame = null
        setHidden(true)
        return@measureTimedValue FrameUpdate(FrameUpdateResult.Deferred, frameDiagnosticDetails(bounds, scale))
      }

      if (parentHwnd != currentParentHwnd) {
        currentParentHwnd = parentHwnd
        lastAppliedFrame = frame
        LOG.traceWebViewPerf(
          "win-webview2.host.updateFrame.attachToParent",
          frameDiagnosticDetails(bounds, scale),
        ) {
          attachToParent(parentHwnd, bounds.x, bounds.y, bounds.width, bounds.height, scale)
        }
        updateNativeVisibility()
        return@measureTimedValue FrameUpdate(FrameUpdateResult.Applied, frameDiagnosticDetails(bounds, scale))
      }
      val frameChanged = frame != lastAppliedFrame
      lastAppliedFrame = frame
      if (frameChanged) {
        setBounds(bounds.x, bounds.y, bounds.width, bounds.height, scale)
      }
      updateNativeVisibility()
      FrameUpdate(FrameUpdateResult.Applied, "frameChanged=$frameChanged, ${frameDiagnosticDetails(bounds, scale)}")
    }
    val update = timedUpdate.value
    LOG.traceWebViewPerf("win-webview2.host.updateFrame", timedUpdate.duration, "result=${update.result}, ${update.details}")
    return update.result
  }

  private fun frameDiagnosticDetails(bounds: SwingWebViewHostPanel.NativeBounds, scale: Double): String {
    return "bounds=${bounds.x},${bounds.y} ${bounds.width}x${bounds.height}, scale=$scale"
  }

  private fun updateNativeVisibility() {
    setHidden(hostHidden)
  }

  private fun isReadyForNativeFrame(bounds: SwingWebViewHostPanel.NativeBounds): Boolean {
    return bounds.width > 0 && bounds.height > 0
  }

  override fun updateVisibility(host: Component, hidden: Boolean) {
    if (!attached) return
    hostHidden = hidden
    if (hidden) {
      setHidden(true)
      return
    }

    if (updateFrame(host) != FrameUpdateResult.Applied) {
      setHidden(true)
    }
  }

  override fun clearFocusForSwingFocusTransfer() {
    clearFocus()
  }

  private data class AppliedFrame(
    val bounds: SwingWebViewHostPanel.NativeBounds,
    val scale: Double,
  )

  private data class FrameUpdate(
    val result: FrameUpdateResult,
    val details: String,
  )

  private enum class FrameUpdateResult {
    Applied,
    Deferred,
    Failed,
  }


  private companion object {
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
