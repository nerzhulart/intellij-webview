// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.engine

import io.github.nerzhulart.webview.api.WebViewBrowserError
import org.jetbrains.annotations.ApiStatus

/** Main-frame navigation callbacks, delivered on the backend's thread. Implementations must not block. */
@ApiStatus.Experimental
interface WebViewNavigationListener {
  fun onNavigationStarted(url: String?)
  fun onUrlChanged(url: String?)
  fun onTitleChanged(title: String?)
  fun onProgressChanged(progress: Double)
  fun onHistoryChanged(canGoBack: Boolean, canGoForward: Boolean)
  fun onNavigationFinished(url: String?, error: WebViewBrowserError?)
}