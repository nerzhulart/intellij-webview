// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.mac

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.util.ui.update.DebouncedUpdates
import com.intellij.util.ui.update.UpdateQueue
import io.github.nerzhulart.webview.api.WebViewAssetPath
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.impl.MacMainThreadDispatcher
import io.github.nerzhulart.webview.impl.SwingWebViewHostPanel
import io.github.nerzhulart.webview.impl.WebViewAssetResolver
import io.github.nerzhulart.webview.impl.WebViewAssetResponse
import io.github.nerzhulart.webview.impl.WebViewEditCommand
import io.github.nerzhulart.webview.impl.WebViewHostBackground
import io.github.nerzhulart.webview.impl.WebViewJsMessageReceiver
import io.github.nerzhulart.webview.impl.WebViewLogger
import io.github.nerzhulart.webview.impl.WebViewShortcutRouter
import io.github.nerzhulart.webview.impl.WebViewShortcutRouting
import io.github.nerzhulart.webview.impl.engine.WebViewEngine
import io.github.nerzhulart.webview.impl.engine.WebViewScript
import io.github.nerzhulart.webview.impl.WebViewEditShortcutPolicy
import io.github.nerzhulart.webview.impl.openWebViewPopupUrlExternally
import io.github.nerzhulart.webview.impl.resolveWebViewAssetUrl
import io.github.nerzhulart.webview.impl.traceWebViewPerf
import io.github.nerzhulart.webview.impl.webViewAssetCustomSchemeUrl
import io.github.nerzhulart.webview.impl.webViewLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.milliseconds

private val LOG = logger<MacWebViewEngine>()

private val nextEngineLogId = AtomicLong(0)

/**
 * macOS implementation of [WebViewEngine] backed by a native `WKWebView`.
 *
 * Lifecycle state machine: `New → Active → Closing → Closed`.
 *
 * All native operations are dispatched to the macOS main thread via [MacMainThreadDispatcher].
 * The engine creates a child [CoroutineScope] with [SupervisorJob] from the provided parent scope.
 */
@ApiStatus.Internal
internal class MacWebViewEngine(
  parentScope: CoroutineScope,
  private val documentStartScripts: List<WebViewScript> = emptyList(),
) : WebViewEngine {
  override val isHeavyweight: Boolean = true
  // TODO: should we return some wrapper component for it?
  override val component: JComponent?
    get() = null

  private companion object {
    const val EVAL_PREFIX = "__eval__:"
    const val EVAL_ERROR_PREFIX = "__eval_err__:"
  }

  private enum class State { New, Active, Closing, Closed }

  private val state = AtomicReference(State.New)

  /** Correlates lifecycle and native attachment trace lines of one engine. */
  private val logId = nextEngineLogId.incrementAndGet()

  @Suppress("RAW_SCOPE_CREATION") // Intentional: engine manages its own child scope lifecycle with close()
  private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

  @Volatile
  private var handles: WKWebViewBridge.WebViewHandles? = null

  @Volatile
  private var inboundMessageHandler: (String) -> Unit = {}

  // Attachment-scoped callback. The native bridge only observes AppKit events; the host decides when
  // they may be forwarded to AWT and clears the callback on detach.
  @Volatile
  private var modifierKeyHandler: (WKWebViewBridge.ModifierKeyEvent) -> Unit = {}

  @Volatile
  private var nativeMousePressedHandler: (WKWebViewBridge.NativeMousePressedEvent) -> Unit = {}

  private val handlesReady = CompletableDeferred<WKWebViewBridge.WebViewHandles>()

  private val nextEvalId = AtomicLong(0)
  private val nextOutgoingLogId = AtomicLong(0)
  private val nextIncomingLogId = AtomicLong(0)
  private val pendingEvals = ConcurrentHashMap<Long, (String?) -> Unit>()
  private val activeAssetResolver = AtomicReference<WebViewAssetResolver?>(null)

  /**
   * Initializes the native WKWebView. Must be called before any other engine method.
   * Called internally when the host panel attaches.
   */
  fun initialize() {
    while (true) {
      when (val current = state.get()) {
        State.New -> {
          if (!state.compareAndSet(State.New, State.Active)) continue

          LOG.webViewLifecycle("create", "engine=$logId, initializing WKWebView")
          scope.launch(MacMainThreadDispatcher) {
            try {
              val createdHandles = LOG.traceWebViewPerf("wkwebview-create") {
                WKWebViewBridge.createWKWebView(
                  onMessage = { message -> handleIncomingMessage(message) },
                  resolveAssetUrl = this@MacWebViewEngine::resolveAssetUrl,
                  onNewWindowRequested = this@MacWebViewEngine::openNewWindowRequest,
                  onModifierKeyEvent = { event -> modifierKeyHandler(event) },
                  onNativeMousePressed = { event -> nativeMousePressedHandler(event) },
                  documentStartScripts = documentStartScripts,
                )
              }

              if (state.get() != State.Active || !handlesReady.complete(createdHandles)) {
                WKWebViewBridge.release(createdHandles)
                handlesReady.cancel(CancellationException("Engine was closed during initialization"))
                return@launch
              }

              handles = createdHandles
              LOG.webViewLifecycle("create", "engine=$logId, WKWebView ready")
            }
            catch (t: Throwable) {
              handlesReady.completeExceptionally(t)
              state.set(State.Closed)
              LOG.warn("Failed to initialize WKWebView", t)
            }
          }
          return
        }
        State.Active -> return
        State.Closing, State.Closed -> {
          LOG.warn("initialize() ignored: engine is $current")
          return
        }
      }
    }
  }

  override fun setFromJsHandler(handler: WebViewJsMessageReceiver) {
    inboundMessageHandler = handler::transferFromJs
  }

  internal fun setModifierKeyHandler(handler: ((WKWebViewBridge.ModifierKeyEvent) -> Unit)?) {
    modifierKeyHandler = handler ?: {}
  }

  private fun setNativeMousePressedHandler(handler: ((WKWebViewBridge.NativeMousePressedEvent) -> Unit)?) {
    nativeMousePressedHandler = handler ?: {}
  }

  override suspend fun loadFile(file: Path) {
    clearActiveAssetResolver()
    loadUrlInternal(file.toUri().toString())
  }

  override suspend fun loadAsset(root: WebViewAssetRoot, entry: WebViewAssetPath, query: String?) {
    activeAssetResolver.set(WebViewAssetResolver(root))
    loadUrlInternal(webViewAssetCustomSchemeUrl(entry, query))
  }

  override suspend fun loadHtml(html: String, baseFile: Path?) {
    clearActiveAssetResolver()
    loadHtmlInternal(html, baseFile?.toUri()?.toString())
  }

  private fun loadUrlInternal(url: String) {
    ensureInitialized()
    if (state.get() != State.Active) return

    scope.launch(MacMainThreadDispatcher) {
      val wv = awaitWebViewId() ?: return@launch
      if (state.get() != State.Active) return@launch
      WKWebViewBridge.loadUrl(wv, url)
    }
  }

  private fun loadHtmlInternal(html: String, baseUrl: String?) {
    ensureInitialized()
    if (state.get() != State.Active) return

    scope.launch(MacMainThreadDispatcher) {
      val wv = awaitWebViewId() ?: return@launch
      if (state.get() != State.Active) return@launch
      WKWebViewBridge.loadHtml(wv, html, baseUrl)
    }
  }

  override suspend fun evaluateJavaScript(script: String): String? {
    ensureInitialized()
    if (state.get() != State.Active) return null

    val evalId = nextEvalId.incrementAndGet()

    return suspendCancellableCoroutine { continuation ->
      pendingEvals[evalId] = { result ->
        if (continuation.isActive) {
          continuation.resumeWith(Result.success(result))
        }
      }

      continuation.invokeOnCancellation {
        pendingEvals.remove(evalId)
      }

      scope.launch(MacMainThreadDispatcher) {
        val wv = awaitWebViewId()
        if (wv == null || state.get() != State.Active) {
          pendingEvals.remove(evalId)?.invoke(null)
          return@launch
        }

        WKWebViewBridge.evaluateJavaScript(wv, script, evalId)
      }
    }
  }

  /**
   * Transfers a raw JSON-RPC frame to JS runtime.
   */
  override suspend fun transferToJs(rawJson: String) {
    ensureInitialized()
    if (state.get() != State.Active) return

    scope.launch(MacMainThreadDispatcher) {
      val wv = awaitWebViewId() ?: return@launch
      if (state.get() != State.Active) return@launch
      logOutgoingToJavaScript(rawJson)
      WKWebViewBridge.transferToJs(wv, rawJson)
    }
  }

  override suspend fun close() {
    clearActiveAssetResolver()
    setModifierKeyHandler(null)
    setNativeMousePressedHandler(null)
    while (true) {
      when (state.get()) {
        State.New -> {
          if (state.compareAndSet(State.New, State.Closed)) {
            scope.cancel()
            cancelPendingEvaluations()
            handlesReady.cancel(CancellationException("Engine closed before initialization"))
            LOG.webViewLifecycle("close", "engine=$logId, closed from New state")
            return
          }
        }
        State.Active -> {
          if (state.compareAndSet(State.Active, State.Closing)) {
            break
          }
        }
        State.Closing, State.Closed -> {
          LOG.webViewLifecycle("close", "engine=$logId, already closing/closed, idempotent no-op")
          return
        }
      }
    }

    LOG.webViewLifecycle(
      "close",
      "engine=$logId, state=${state.get()}, attachmentRequested=$attachmentRequested, generation=$attachmentGeneration",
    )
    cancelPendingEvaluations()

    scope.cancel()

    // Post native cleanup directly on macOS main thread — not through the cancelled scope.
    val currentHandles = handles
    handles = null
    if (currentHandles != null) {
      MacObjectiveC.executeOnMainThread {
        WKWebViewBridge.release(currentHandles)
        // Covers a close without a preceding detach: the painted container must not outlive the WebView.
        releaseAttachment("close")
        handlesReady.cancel(CancellationException("Engine closed"))
        state.set(State.Closed)
        LOG.webViewLifecycle("close", "engine=$logId, native cleanup complete")
      }
    }
    else {
      handlesReady.cancel(CancellationException("Engine closed"))
      state.set(State.Closed)
      LOG.webViewLifecycle("close", "engine=$logId, no native handles to release")
    }
  }

  /**
   * Waits until the native WKWebView can be attached.
   */
  internal suspend fun awaitReadyForAttachment(): Boolean {
    return awaitWebViewId() != null && state.get() == State.Active
  }

  /**
   * Attaches the initialized native WKWebView as a subview of [parentNSView].
   * Must be called on the macOS main thread.
   */
  internal fun attachToParent(parentNSView: ID): Boolean {
    val wv = handles?.webView ?: return false
    if (state.get() != State.Active) return false
    WKWebViewBridge.attachToParent(wv, parentNSView)
    return true
  }

  /**
   * Detaches the native WKWebView from its superview.
   * Must be called on the macOS main thread.
   */
  internal fun detachFromParent() {
    val wv = handles?.webView ?: return
    WKWebViewBridge.detachFromParent(wv)
  }

  /**
   * Updates the native WKWebView frame to the given bounds.
   * Must be called on the macOS main thread.
   */
  internal fun setFrame(x: Double, y: Double, w: Double, h: Double) {
    val wv = handles?.webView ?: return
    WKWebViewBridge.setFrame(wv, x, y, w, h)
  }

  /**
   * Sets the visibility of the native WKWebView.
   * Must be called on the macOS main thread.
   */
  internal fun setHidden(hidden: Boolean) {
    val wv = handles?.webView ?: return
    WKWebViewBridge.setHidden(wv, hidden)
  }


  internal fun makeFirstResponder(nativeView: ID) {
    WKWebViewBridge.makeFirstResponder(nativeView)
  }

  internal fun performEditCommand(command: WebViewEditCommand): Boolean {
    val wv = handles?.webView ?: return false
    if (state.get() != State.Active) return false

    scope.launch(MacMainThreadDispatcher) {
      if (state.get() == State.Active) {
        WKWebViewBridge.performEditCommand(wv, command)
      }
    }
    return true
  }

  internal suspend fun firstResponderState(): MacWebViewFirstResponderState? {
    if (state.get() != State.Active) return null

    return withContext(MacMainThreadDispatcher) {
      val wv = handles?.webView ?: return@withContext null
      if (state.get() == State.Active) WKWebViewBridge.firstResponderState(wv) else null
    }
  }

  private fun cancelPendingEvaluations() {
    pendingEvals.keys.forEach { evalId ->
      pendingEvals.remove(evalId)?.invoke(null)
    }
  }

  private fun clearActiveAssetResolver() {
    activeAssetResolver.set(null)
  }

  private fun resolveAssetUrl(url: String): WebViewAssetResponse? {
    return resolveWebViewAssetUrl(url, activeAssetResolver.get(), "mac")
  }

  private fun openNewWindowRequest(url: String) {
    openWebViewPopupUrlExternally(url)
  }

  private suspend fun awaitWebViewId(): ID? {
    handles?.webView?.let { return it }

    return try {
      handlesReady.await().webView
    }
    catch (_: Throwable) {
      null
    }
  }

  private fun ensureInitialized() {
    if (state.get() == State.New) {
      initialize()
    }
  }

  private fun handleIncomingMessage(message: String) {
    logIncomingFromJavaScript(message)
    if (tryCompleteEvaluation(message)) return
    inboundMessageHandler(message)
  }

  private fun logOutgoingToJavaScript(rawJson: String) {
    val count = nextOutgoingLogId.incrementAndGet()
    LOG.trace { "Delivering WebView message to JS #$count (${rawJson.length} chars)" }
  }

  private fun logIncomingFromJavaScript(message: String) {
    val count = nextIncomingLogId.incrementAndGet()
    LOG.trace { "Received WebView message from JS #$count (${message.length} chars)" }
  }

  private fun tryCompleteEvaluation(message: String): Boolean {
    val isError = message.startsWith(EVAL_ERROR_PREFIX)
    val prefix = when {
      isError -> EVAL_ERROR_PREFIX
      message.startsWith(EVAL_PREFIX) -> EVAL_PREFIX
      else -> return false
    }

    val rest = message.removePrefix(prefix)
    val colonIdx = rest.indexOf(':')
    if (colonIdx < 0) return false

    val evalId = rest.substring(0, colonIdx).toLongOrNull() ?: return false
    val value = rest.substring(colonIdx + 1)
    pendingEvals.remove(evalId)?.invoke(if (isError) null else value)
    return true
  }

  /**
   * Peer's section
   */

  private data class Attachment(
    val parentContentView: ID,
    val clipView: ID,
  )

  @Volatile
  private var attachmentRequested = false

  @Volatile
  private var attachmentGeneration = 0L

  @Volatile
  private var hostHidden = true

  /** Accessed only on the macOS main thread. */
  private var attachment: Attachment? = null

  /**
   * Paints the clipping container behind the transparent WKWebView, so that a frame without page
   * content is indistinguishable from the host panel in any theme.
   */
  private val hostBackground = WebViewHostBackground(scope) { argb ->
    // The main thread runs these in order, so the last change wins.
    scope.launch(MacMainThreadDispatcher) {
      attachment?.let { WKWebViewBridge.setContainerBackgroundColor(it.clipView, argb) }
    }
  }

  /** Accessed only on the macOS main thread. */
  private var lastAppliedLayout: MacNativeLayout? = null

  /** Diagnostics only, accessed on the macOS main thread: the last visibility pushed to the container. */
  private var lastNativeHidden: Boolean? = null

  /**
   * Throttle queue for resize/move coalescing. The first event arms a 16 ms timer;
   * subsequent events collapse into the latest layout without starving continuous drags.
   */
  private val resizeUpdates: UpdateQueue<MacNativeLayout> = DebouncedUpdates
    .forScope<MacNativeLayout>(scope, "webview-native-frame", 16.milliseconds)
    .withContext(MacMainThreadDispatcher)
    .runLatest { layout -> applyLayout(layout) }

  /**
   * WKWebView edit shortcuts are AppKit actions, not key events that can be safely replayed after
   * the IDE dispatcher sees them. The peer therefore dispatches commands through the responder chain.
   */
  override val editShortcutPolicy: WebViewEditShortcutPolicy = WebViewEditShortcutPolicy.HANDLE_IN_NATIVE_PEER

  override fun attach(host: Component): Boolean {
    if (attachmentRequested) {
      traceAttachment("attach.skip") { "host=${host.nativeLogId()}, already requested, generation=$attachmentGeneration" }
      return true
    }

    initialize()

    val contentView = resolveParentContentView(host) ?: run {
      traceAttachment("attach.skip") { "host=${host.nativeLogId()}, no NSWindow content view, showing=${host.isShowing}" }
      return false
    }
    val initialLayout = resolveLayout(host) ?: run {
      traceAttachment("attach.skip") { "host=${host.nativeLogId()}, no layout anchor, showing=${host.isShowing}" }
      return false
    }
    WebViewLogger.LOG.info("Attaching WKWebView host: layout=$initialLayout, showing=${host.isShowing}")

    attachmentRequested = true
    val generation = ++attachmentGeneration
    traceAttachment("attach.request") {
      "host=${host.nativeLogId()}, generation=$generation, state=${state.get()}, scopeActive=${scope.isActive}, " +
      "contentView=${contentView.nativeLogId()}"
    }
    hostHidden = true
    // The Swing host is the right boundary for mirroring native input into AWT because it owns
    // attach/detach lifecycle. Both callbacks are invalidated by this attachment generation.
    setModifierKeyHandler { event -> postModifierKeyEvent(host, event) }
    setNativeMousePressedHandler { event -> postNativeMousePressed(host, generation, event) }
    hostBackground.listenTo(host)

    val attachJob = scope.launch(MacMainThreadDispatcher) {
      if (!awaitReadyForAttachment() || !isCurrentAttachment(generation)) {
        traceAttachment("attach.abandon") {
          "generation=$generation, currentGeneration=$attachmentGeneration, requested=$attachmentRequested, " +
          "state=${state.get()}, before container creation"
        }
        return@launch
      }

      val clipView = WKWebViewBridge.createClippingContainer(contentView)
      if (!isCurrentAttachment(generation)) {
        WKWebViewBridge.releaseClippingContainer(clipView)
        traceAttachment("attach.abandon") {
          "generation=$generation, currentGeneration=$attachmentGeneration, released fresh container ${clipView.nativeLogId()}, " +
          "contentSubviews=${contentSubviews(contentView)}"
        }
        return@launch
      }
      WKWebViewBridge.setContainerBackgroundColor(clipView, hostBackground.argb)

      val webViewAttached = attachToParent(clipView)
      if (!webViewAttached || !isCurrentAttachment(generation)) {
        detachFromParent()
        WKWebViewBridge.releaseClippingContainer(clipView)
        traceAttachment("attach.abandon") {
          "generation=$generation, currentGeneration=$attachmentGeneration, webViewAttached=$webViewAttached, " +
          "released fresh container ${clipView.nativeLogId()}, contentSubviews=${contentSubviews(contentView)}"
        }
        return@launch
      }

      attachment = Attachment(contentView, clipView)
      lastAppliedLayout = null
      lastNativeHidden = null
      traceAttachment("attach.done") {
        "generation=$generation, container=${clipView.nativeLogId()}, contentView=${contentView.nativeLogId()}, " +
        "contentSubviews=${contentSubviews(contentView)}"
      }
      applyLayout(initialLayout)

      SwingUtilities.invokeLater {
        (host as? SwingWebViewHostPanel)?.let { hostPanel ->
          hostPanel.syncNativePeerWithSwingState()
          hostPanel.syncWebViewFocusWithSwingFocusOwner()
        }
      }
    }
    attachJob.invokeOnCompletion { cause ->
      // A launch cancelled before it was dispatched never runs its body, so only this callback sees it.
      if (cause != null) {
        traceAttachment("attach.cancelled") { "generation=$generation, state=${state.get()}, cause=$cause" }
      }
    }
    return true
  }

  override fun detach() {
    if (!attachmentRequested) {
      traceAttachment("detach.skip") { "not requested, generation=$attachmentGeneration" }
      return
    }

    attachmentRequested = false
    attachmentGeneration++
    traceAttachment("detach.request") {
      "generation=$attachmentGeneration, state=${state.get()}, scopeActive=${scope.isActive}"
    }
    hostHidden = true
    setModifierKeyHandler(null)
    setNativeMousePressedHandler(null)
    hostBackground.stopListening()
    // Not through the scope: the host is usually closed after the WebView scope is cancelled, and a
    // cancelled launch never runs, leaving the painted clipping container over Swing.
    MacObjectiveC.executeOnMainThread {
      releaseAttachment("detach")
    }
  }

  override fun syncHostState(host: Component) {
    if (!attachmentRequested) return
    if (!scope.isActive) {
      // Native geometry and visibility updates go through the scope and are dropped from here on.
      traceAttachment("sync.dropped") {
        "host=${host.nativeLogId()}, scope cancelled, showing=${host.isShowing}, state=${state.get()}"
      }
    }
    // Geometry and visibility are one snapshot: an empty layout already reads as hidden in
    // updateNativeVisibility, so the only extra bit to carry is whether Swing shows the host.
    hostHidden = !host.isShowing
    resolveLayout(host)?.let { resizeUpdates.queue(it) }

    scope.launch(MacMainThreadDispatcher) {
      updateNativeVisibility()
    }
  }

  override fun requestFocus() {
    if (!attachmentRequested) return

    scope.launch(MacMainThreadDispatcher) {
      if (attachment != null) {
        val wv = handles?.webView ?: return@launch
        WKWebViewBridge.requestFocus(wv)
      }
    }
  }

  override fun clearFocus() {
    if (!attachmentRequested) return

    scope.launch(MacMainThreadDispatcher) {
      if (attachment != null) {
        val wv = handles?.webView ?: return@launch
        WKWebViewBridge.clearFocus(wv)
      }
    }
  }

  override fun clearFocusForSwingFocusTransfer() {
    if (!attachmentRequested) return

    scope.launch(MacMainThreadDispatcher) {
      val focusTarget = attachment?.parentContentView ?: return@launch
      makeFirstResponder(focusTarget)
    }
  }

  /**
   * Accepts the key press that matched the IDE shortcut and lets AppKit route the edit command to
   * WKWebView or its current private editor responder.
   */
  override fun handleWebViewShortcut(event: KeyEvent, command: WebViewEditCommand): Boolean {
    return attachmentRequested && event.id == KeyEvent.KEY_PRESSED && performEditCommand(command)
  }

  /**
   * Mirrors native modifier-only transitions into the AWT event queue without moving focus out of WKWebView.
   * The shared router keeps this path limited to bare Shift/Ctrl gesture candidates; browser edit shortcuts
   * and normal WebKit handling stay untouched.
   */
  private fun postModifierKeyEvent(host: Component, event: WKWebViewBridge.ModifierKeyEvent) {
    if (!attachmentRequested || !host.isShowing) return

    val eventSource = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow ?: host
    val keyEvent = KeyEvent(
      eventSource,
      event.id,
      System.currentTimeMillis(),
      event.modifiersEx,
      event.keyCode,
      KeyEvent.CHAR_UNDEFINED,
      event.keyLocation,
    )
    if (WebViewShortcutRouter.route(keyEvent) != WebViewShortcutRouting.FORWARD_TO_IDE_KEEP_BROWSER_HANDLING) return

    Toolkit.getDefaultToolkit().systemEventQueue.postEvent(keyEvent)
  }

  private fun postNativeMousePressed(
    host: Component,
    generation: Long,
    event: WKWebViewBridge.NativeMousePressedEvent,
  ) {
    val currentAttachment = isCurrentAttachment(generation)
    WebViewLogger.LOG.debug(
      "Received WKWebView native mouse down: event=$event, currentAttachment=$currentAttachment, hostShowing=${host.isShowing}",
    )
    if (!currentAttachment || !host.isShowing) return
    (host as? SwingWebViewHostPanel)?.nativeWebViewMousePressed(event.button, event.modifiersEx)
  }

  private fun applyLayout(layout: MacNativeLayout) {
    val currentAttachment = attachment ?: return
    if (layout != lastAppliedLayout) {
      lastAppliedLayout = layout
      val containerFrame = layout.containerFrame
      WKWebViewBridge.setFrame(
        currentAttachment.clipView,
        containerFrame.x,
        containerFrame.y,
        containerFrame.width,
        containerFrame.height,
      )
      val webViewFrame = layout.webViewFrame
      setFrame(
        webViewFrame.x,
        webViewFrame.y,
        webViewFrame.width,
        webViewFrame.height,
      )
      WebViewLogger.LOG.debug("Applying WKWebView host layout: $layout")
    }

    updateNativeVisibility()
  }

  private fun updateNativeVisibility() {
    val currentAttachment = attachment ?: return
    val hidden = hostHidden || lastAppliedLayout?.hasVisibleBounds != true
    WKWebViewBridge.setHidden(currentAttachment.clipView, hidden)
    if (hidden != lastNativeHidden) {
      lastNativeHidden = hidden
      traceAttachment("visibility") {
        "container=${currentAttachment.clipView.nativeLogId()}, hidden=$hidden, hostHidden=$hostHidden, " +
        "visibleBounds=${lastAppliedLayout?.hasVisibleBounds}"
      }
    }
  }

  private fun releaseAttachment(reason: String) {
    val currentAttachment = attachment ?: run {
      traceAttachment("release.skip") { "reason=$reason, no attachment" }
      return
    }
    attachment = null
    lastAppliedLayout = null
    lastNativeHidden = null
    detachFromParent()
    WKWebViewBridge.releaseClippingContainer(currentAttachment.clipView)
    traceAttachment("release.done") {
      "reason=$reason, container=${currentAttachment.clipView.nativeLogId()}, " +
      "contentView=${currentAttachment.parentContentView.nativeLogId()}, " +
      "contentSubviews=${contentSubviews(currentAttachment.parentContentView)}"
    }
  }

  private inline fun traceAttachment(event: String, details: () -> String) {
    LOG.trace { "native-attachment: engine=$logId $event - ${details()}" }
  }

  /** Must be called on the macOS main thread. */
  private fun contentSubviews(contentView: ID): String {
    return runCatching { WKWebViewBridge.subviewCount(contentView).toString() }.getOrElse { "?" }
  }

  private fun ID.nativeLogId(): String = "0x" + java.lang.Long.toHexString(toLong())

  private fun Component.nativeLogId(): String = Integer.toHexString(System.identityHashCode(this))

  private fun isCurrentAttachment(generation: Long): Boolean {
    return attachmentRequested && attachmentGeneration == generation
  }

  private fun resolveLayout(host: Component): MacNativeLayout? {
    val anchor = SwingWebViewHostPanel.resolveAnchor(host) ?: return null
    val fullBounds = SwingWebViewHostPanel.calculateHostBounds(host, anchor)
    val clippedBounds = SwingWebViewHostPanel.calculateClippedBounds(host, anchor)
    return calculateMacNativeLayout(fullBounds, clippedBounds, anchor.height)
  }

  private fun resolveParentContentView(host: Component): ID? {
    val window = SwingUtilities.getWindowAncestor(host) ?: return null
    val nsWindow = MacObjectiveC.windowFromAwt(window)
    if (MacObjectiveC.isNil(nsWindow)) return null
    return WKWebViewBridge.contentView(nsWindow)
  }


}

/**
 * Factory function for creating a macOS WebView
 */
@ApiStatus.Internal
internal fun createMacWebViewEngine(
  parentScope: CoroutineScope,
  documentStartScripts: List<WebViewScript> = emptyList(),
): MacWebViewEngine {
  return MacWebViewEngine(parentScope, documentStartScripts)
}

@ApiStatus.Internal
internal data class MacWebViewFirstResponderState(
  val hasResponder: Boolean,
  val isInsideWebView: Boolean,
  val responderDescription: String,
)

internal data class MacNativeLayout(
  val containerFrame: SwingWebViewHostPanel.NativeFrame,
  val webViewFrame: SwingWebViewHostPanel.NativeFrame,
) {
  val hasVisibleBounds: Boolean
    get() = containerFrame.width > 0.0 && containerFrame.height > 0.0
}

internal fun calculateMacNativeLayout(
  fullBounds: SwingWebViewHostPanel.NativeBounds,
  clippedBounds: SwingWebViewHostPanel.NativeBounds,
  anchorHeight: Int,
): MacNativeLayout {
  val containerFrame = SwingWebViewHostPanel.NativeFrame(
    x = clippedBounds.x.toDouble(),
    y = (anchorHeight - clippedBounds.y - clippedBounds.height).toDouble(),
    width = clippedBounds.width.toDouble(),
    height = clippedBounds.height.toDouble(),
  )
  val webViewFrame = SwingWebViewHostPanel.NativeFrame(
    x = (fullBounds.x - clippedBounds.x).toDouble(),
    y = (clippedBounds.y + clippedBounds.height - fullBounds.y - fullBounds.height).toDouble(),
    width = fullBounds.width.toDouble(),
    height = fullBounds.height.toDouble(),
  )
  return MacNativeLayout(containerFrame, webViewFrame)
}
