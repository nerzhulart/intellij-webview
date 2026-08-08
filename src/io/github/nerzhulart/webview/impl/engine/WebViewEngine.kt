// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.engine

import io.github.nerzhulart.webview.api.WebViewAssetPath
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.impl.SwingWebViewHostPanel
import io.github.nerzhulart.webview.impl.WebViewEditCommand
import io.github.nerzhulart.webview.impl.WebViewJsMessageReceiver
import io.github.nerzhulart.webview.impl.WebViewEditShortcutPolicy
import org.intellij.lang.annotations.Language
import org.jetbrains.annotations.ApiStatus
import java.awt.Component
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.JComponent

/**
 * Platform-independent runtime engine for a native system WebView instance.
 *
 * All methods must be called from the EDT or a coroutine scope bound to the EDT,
 * unless documented otherwise. [evaluateJavaScript] is a suspend function that
 * internally dispatches to the native main thread.
 */
@ApiStatus.Internal
interface WebViewEngine {
  val isHeavyweight: Boolean

  /**
   * Cached component of the webview
   */
  val component: JComponent?
  suspend fun loadFile(file: Path)

  /**
   * Loads [entry] from [root] through the platform WebView asset handler and a virtual origin.
   */
  suspend fun loadAsset(root: WebViewAssetRoot, entry: WebViewAssetPath = WebViewAssetPath.indexHtml(), query: String? = null)

  suspend fun loadHtml(@Language("HTML") html: String, baseFile: Path? = null)

  /**
   * Evaluates [script] in the WebView's JavaScript context and returns the result as a string,
   * or `null` if the evaluation produces no result or the WebView is closed.
   */
  suspend fun evaluateJavaScript(@Language("JavaScript") script: String): String?

  suspend fun close()
  suspend fun transferToJs(rawJson: String)
  fun connectMessageBus(receiver: WebViewJsMessageReceiver)
//  fun requestWebViewFocus()
//  fun clearWebViewFocus()

  /**
   * Native Peer section
   */
  fun attach(host: Component): Boolean
  fun detach()
  fun scheduleFrameUpdate(host: Component)
  fun hasNonEmptyNativeBounds(host: Component): Boolean = SwingWebViewHostPanel.hasNonEmptyClippedBounds(host)
  fun updateVisibility(host: Component, hidden: Boolean)
  fun requestFocus()
  fun clearFocus()

  /**
   * Policy used by the Swing host for edit shortcuts while this peer owns WebView focus.
   *
   * Keeping the policy on the peer avoids OS branching in [SwingWebViewHostPanel] and makes each
   * backend declare whether it is browser-first, dispatcher-bypass, or native-command based.
   */
  val editShortcutPolicy: WebViewEditShortcutPolicy
    get() = WebViewEditShortcutPolicy.NONE

  /**
   * Called only for [WebViewEditShortcutPolicy.HANDLE_IN_NATIVE_PEER] after an IDE keymap shortcut
   * has matched a WebView edit command while focus is inside the host.
   *
   * Return `true` when the native peer accepted the event and Swing should consume it. Implementations
   * normally handle only `KEY_PRESSED`; release/typed events do not represent a platform edit command.
   */
  fun handleWebViewShortcut(event: KeyEvent, command: WebViewEditCommand): Boolean = false

  /**
   * Called when Swing focus moves outside the host. Backends may need a platform-specific
   * transfer that differs from clearing native focus completely.
   */
  fun clearFocusForSwingFocusTransfer() {
  }
}
