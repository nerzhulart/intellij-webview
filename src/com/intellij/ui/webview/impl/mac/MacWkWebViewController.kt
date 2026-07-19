// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.webview.impl.mac

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.ui.mac.foundation.ID
import com.intellij.ui.webview.api.WebViewAssetPath
import com.intellij.ui.webview.api.WebViewAssetRoot
import com.intellij.ui.webview.impl.MacMainThreadDispatcher
import com.intellij.ui.webview.impl.WebViewAssetResolver
import com.intellij.ui.webview.impl.WebViewAssetResponse
import com.intellij.ui.webview.impl.WebViewController
import com.intellij.ui.webview.impl.WebViewEditCommand
import com.intellij.ui.webview.impl.WebViewEditShortcutPolicy
import com.intellij.ui.webview.impl.WebViewHostEventSink
import com.intellij.ui.webview.impl.WebViewHostLayoutParams
import com.intellij.ui.webview.impl.WebViewJsMessageReceiver
import com.intellij.ui.webview.impl.WebViewSwingFocusExit
import com.intellij.ui.webview.impl.engine.WebViewScript
import com.intellij.ui.webview.impl.openWebViewPopupUrlExternally
import com.intellij.ui.webview.impl.resolveWebViewAssetUrl
import com.intellij.ui.webview.impl.traceWebViewPerf
import com.intellij.ui.webview.impl.webViewAssetCustomSchemeUrl
import com.intellij.ui.webview.impl.webViewLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus
import java.awt.Canvas
import java.awt.Component
import java.awt.event.KeyEvent
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val LOG = logger<MacWkWebViewController>()

/**
 * macOS controller for one WKWebView hosted by one heavyweight AWT component.
 *
 * Lifecycle state machine: `New → Active → Closing → Closed`.
 *
 * All native operations are dispatched to the macOS main thread via [MacMainThreadDispatcher].
 * The engine creates a child [CoroutineScope] with [SupervisorJob] from the provided parent scope.
 */
@ApiStatus.Internal
internal class MacWkWebViewController(
  parentScope: CoroutineScope,
  private val documentStartScripts: List<WebViewScript> = emptyList(),
  @Suppress("unused") private val hostEventSink: WebViewHostEventSink,
) : WebViewController {

  override val component: Component = Canvas().apply {
    isFocusable = true
  }

  override val editShortcutPolicy: WebViewEditShortcutPolicy = WebViewEditShortcutPolicy.HANDLE_IN_NATIVE_PEER

  private companion object {
    const val EVAL_PREFIX = "__eval__:"
    const val EVAL_ERROR_PREFIX = "__eval_err__:"
  }

  private enum class State { New, Active, Closing, Closed }

  private val state = AtomicReference(State.New)

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

          LOG.webViewLifecycle("create", "initializing WKWebView")
          scope.launch(MacMainThreadDispatcher) {
            try {
              val createdHandles = LOG.traceWebViewPerf("wkwebview-create") {
                WKWebViewBridge.createWKWebView(
                  onMessage = { message -> handleIncomingMessage(message) },
                  resolveAssetUrl = this@MacWkWebViewController::resolveAssetUrl,
                  onNewWindowRequested = this@MacWkWebViewController::openNewWindowRequest,
                  onModifierKeyEvent = { event -> modifierKeyHandler(event) },
                  documentStartScripts = documentStartScripts,
                )
              }

              if (state.get() != State.Active || !handlesReady.complete(createdHandles)) {
                WKWebViewBridge.release(createdHandles)
                handlesReady.cancel(CancellationException("Engine was closed during initialization"))
                return@launch
              }

              handles = createdHandles
              LOG.webViewLifecycle("create", "WKWebView ready")
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

  override fun connectMessageBus(receiver: WebViewJsMessageReceiver) {
    inboundMessageHandler = receiver::transferFromJs
  }

  internal fun setModifierKeyHandler(handler: ((WKWebViewBridge.ModifierKeyEvent) -> Unit)?) {
    modifierKeyHandler = handler ?: {}
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
    while (true) {
      when (state.get()) {
        State.New -> {
          if (state.compareAndSet(State.New, State.Closed)) {
            scope.cancel()
            cancelPendingEvaluations()
            handlesReady.cancel(CancellationException("Engine closed before initialization"))
            LOG.webViewLifecycle("close", "closed from New state")
            return
          }
        }
        State.Active -> {
          if (state.compareAndSet(State.Active, State.Closing)) {
            break
          }
        }
        State.Closing, State.Closed -> {
          LOG.webViewLifecycle("close", "already closing/closed, idempotent no-op")
          return
        }
      }
    }

    LOG.webViewLifecycle("close", "state=${state.get()}")
    cancelPendingEvaluations()

    scope.cancel()

    // Post native cleanup directly on macOS main thread — not through the cancelled scope.
    val currentHandles = handles
    handles = null
    if (currentHandles != null) {
      com.intellij.ui.mac.foundation.Foundation.executeOnMainThread(false, false) {
        WKWebViewBridge.release(currentHandles)
        handlesReady.cancel(CancellationException("Engine closed"))
        state.set(State.Closed)
        LOG.webViewLifecycle("close", "native cleanup complete")
      }
    }
    else {
      handlesReady.cancel(CancellationException("Engine closed"))
      state.set(State.Closed)
    }
  }

  /**
   * Attaches the native WKWebView as a subview of [parentNSView].
   * Must be called on the macOS main thread.
   */
  internal suspend fun attachToParent(parentNSView: ID) {
    val wv = awaitWebViewId() ?: return
    if (state.get() != State.Active) return
    WKWebViewBridge.attachToParent(wv, parentNSView)
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

  override fun requestWebViewFocus() {
    val wv = handles?.webView ?: return
    WKWebViewBridge.requestFocus(wv)
  }

  internal fun makeFirstResponder(nativeView: ID) {
    WKWebViewBridge.makeFirstResponder(nativeView)
  }

  internal fun clearFocus() {
    val wv = handles?.webView ?: return
    WKWebViewBridge.clearFocus(wv)
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

  override fun applyLayout(params: WebViewHostLayoutParams) {
    // Host NSView resolution and attachment remain private to the macOS controller.
    if (params.displayable) initialize()
    setHidden(!params.showing || params.clippedBoundsInWindow.isEmpty)
  }

  override fun swingFocusMovedOutside(event: WebViewSwingFocusExit) {
    if (event.sameWindow) clearFocus()
  }

  override fun handleEditShortcut(event: KeyEvent, command: WebViewEditCommand): Boolean {
    return event.id == KeyEvent.KEY_PRESSED && performEditCommand(command)
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

}

/**
 * Factory function for creating a macOS WebView engine.
 */
@ApiStatus.Internal
internal fun createMacWkWebViewController(
  parentScope: CoroutineScope,
  documentStartScripts: List<WebViewScript> = emptyList(),
  hostEventSink: WebViewHostEventSink,
): WebViewController {
  return MacWkWebViewController(parentScope, documentStartScripts, hostEventSink)
}

@ApiStatus.Internal
internal data class MacWebViewFirstResponderState(
  val hasResponder: Boolean,
  val isInsideWebView: Boolean,
  val responderDescription: String,
)
