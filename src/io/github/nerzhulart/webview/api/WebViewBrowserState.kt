// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.api

import org.jetbrains.annotations.ApiStatus

@ApiStatus.Experimental
data class WebViewBrowserState(
  val url: String? = null,
  val title: String? = null,
  val isLoading: Boolean = false,
  val progress: Double = 0.0,
  val canGoBack: Boolean = false,
  val canGoForward: Boolean = false,
  val error: WebViewBrowserError? = null,
) {
  companion object {
    val EMPTY: WebViewBrowserState = WebViewBrowserState()
  }
}

/** A network/navigation failure, not an HTTP error status. Cleared when the next navigation starts. */
@ApiStatus.Experimental
data class WebViewBrowserError(val url: String?, val message: String, val code: Int? = null)