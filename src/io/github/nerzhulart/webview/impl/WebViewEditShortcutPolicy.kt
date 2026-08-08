package io.github.nerzhulart.webview.impl

import org.jetbrains.annotations.ApiStatus

/**
 * Backend-owned routing policy for WebView edit shortcuts that also exist as IDE actions.
 *
 * [SwingWebViewHostPanel.skipKeyEventDispatcher] evaluates this only while focus is inside the host.
 * The Swing panel acts as an IDE-dispatch gate; the backend decides whether the original native
 * event path is enough or whether an explicit platform edit command must be issued.
 */
@ApiStatus.Internal
enum class WebViewEditShortcutPolicy {
  /**
   * The Swing host does not intercept WebView edit shortcuts.
   *
   * Use this when the backend already gets browser-first shortcut handling, or when there is no
   * backend-specific WebView edit shortcut path.
   */
  NONE,

  /**
   * Keep IDE actions from consuming WebView edit shortcuts without invoking native peer code.
   *
   * The original key event continues through the backend's normal native/browser event path.
   */
  BYPASS_IDE_DISPATCHER,

  /**
   * Keep IDE actions from consuming WebView edit shortcuts and ask the native peer to run the
   * platform edit command explicitly.
   */
  HANDLE_IN_NATIVE_PEER,
}