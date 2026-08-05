// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.mac

import com.intellij.ui.mac.foundation.Foundation
import com.intellij.ui.mac.foundation.ID
import com.intellij.ui.mac.foundation.MacUtil
import com.intellij.util.ui.update.DebouncedUpdates
import com.intellij.util.ui.update.UpdateQueue
import io.github.nerzhulart.webview.impl.MacMainThreadDispatcher
import io.github.nerzhulart.webview.impl.SwingWebViewHostPanel
import io.github.nerzhulart.webview.impl.WebViewEditCommand
import io.github.nerzhulart.webview.impl.WebViewLogger
import io.github.nerzhulart.webview.impl.WebViewShortcutRouter
import io.github.nerzhulart.webview.impl.WebViewShortcutRouting
import io.github.nerzhulart.webview.impl.host.NativeWebViewHostPeer
import io.github.nerzhulart.webview.impl.host.WebViewEditShortcutPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.annotations.ApiStatus
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import kotlin.time.Duration.Companion.milliseconds

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

@ApiStatus.Internal
internal class MacNativeWebViewHostPeer(
  private val scope: CoroutineScope,
  private val engine: MacWebViewEngine,
) : NativeWebViewHostPeer {

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

  /** Accessed only on the macOS main thread. */
  private var lastAppliedLayout: MacNativeLayout? = null

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
    if (attachmentRequested) return true

    engine.initialize()

    val contentView = resolveParentContentView(host) ?: return false
    val initialLayout = resolveLayout(host) ?: return false
    WebViewLogger.LOG.info("Attaching WKWebView host: layout=$initialLayout, showing=${host.isShowing}")

    // WKWebView reports bare modifier transitions through AppKit while it owns first responder.
    // The Swing host is the right boundary for mirroring them into AWT because it owns attach/detach lifecycle.
    engine.setModifierKeyHandler { event -> postModifierKeyEvent(host, event) }
    attachmentRequested = true
    val generation = ++attachmentGeneration
    hostHidden = true

    scope.launch(MacMainThreadDispatcher) {
      if (!engine.awaitReadyForAttachment() || !isCurrentAttachment(generation)) return@launch

      val clipView = WKWebViewBridge.createClippingContainer(contentView)
      if (!isCurrentAttachment(generation)) {
        WKWebViewBridge.releaseClippingContainer(clipView)
        return@launch
      }

      val webViewAttached = engine.attachToParent(clipView)
      if (!webViewAttached || !isCurrentAttachment(generation)) {
        engine.detachFromParent()
        WKWebViewBridge.releaseClippingContainer(clipView)
        return@launch
      }

      attachment = Attachment(contentView, clipView)
      lastAppliedLayout = null
      applyLayout(initialLayout)

      SwingUtilities.invokeLater {
        (host as? SwingWebViewHostPanel)?.let { hostPanel ->
          hostPanel.syncNativePeerWithSwingState()
          hostPanel.syncWebViewFocusWithSwingFocusOwner()
        }
      }
    }
    return true
  }

  override fun detach() {
    if (!attachmentRequested) return

    attachmentRequested = false
    attachmentGeneration++
    hostHidden = true
    engine.setModifierKeyHandler(null)
    scope.launch(MacMainThreadDispatcher) {
      releaseAttachment()
    }
  }

  override fun scheduleFrameUpdate(host: Component) {
    if (!attachmentRequested) return
    val layout = resolveLayout(host) ?: return
    resizeUpdates.queue(layout)
  }

  override fun hasNonEmptyNativeBounds(host: Component): Boolean {
    return resolveLayout(host)?.hasVisibleBounds == true
  }

  override fun updateVisibility(host: Component, hidden: Boolean) {
    if (!attachmentRequested) return
    hostHidden = hidden

    if (!hidden) {
      scheduleFrameUpdate(host)
    }

    scope.launch(MacMainThreadDispatcher) {
      updateNativeVisibility()
    }
  }

  override fun requestFocus() {
    if (!attachmentRequested) return

    scope.launch(MacMainThreadDispatcher) {
      if (attachment != null) {
        engine.requestFocus()
      }
    }
  }

  override fun clearFocus() {
    if (!attachmentRequested) return

    scope.launch(MacMainThreadDispatcher) {
      if (attachment != null) {
        engine.clearFocus()
      }
    }
  }

  override fun clearFocusForSwingFocusTransfer() {
    if (!attachmentRequested) return

    scope.launch(MacMainThreadDispatcher) {
      val focusTarget = attachment?.parentContentView ?: return@launch
      engine.makeFirstResponder(focusTarget)
    }
  }

  /**
   * Accepts the key press that matched the IDE shortcut and lets AppKit route the edit command to
   * WKWebView or its current private editor responder.
   */
  override fun handleWebViewShortcut(event: KeyEvent, command: WebViewEditCommand): Boolean {
    return attachmentRequested && event.id == KeyEvent.KEY_PRESSED && engine.performEditCommand(command)
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
      engine.setFrame(
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
  }

  private fun releaseAttachment() {
    val currentAttachment = attachment ?: return
    attachment = null
    lastAppliedLayout = null
    engine.detachFromParent()
    WKWebViewBridge.releaseClippingContainer(currentAttachment.clipView)
  }

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
    val nsWindow = MacUtil.getWindowFromJavaWindow(window)
    if (Foundation.isNil(nsWindow)) return null
    val contentView = Foundation.invoke(nsWindow, "contentView")
    return if (Foundation.isNil(contentView)) null else contentView
  }
}
