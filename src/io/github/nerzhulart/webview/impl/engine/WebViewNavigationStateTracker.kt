// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.engine

import io.github.nerzhulart.webview.api.WebViewBrowserError
import io.github.nerzhulart.webview.api.WebViewBrowserState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class WebViewNavigationStateTracker : WebViewNavigationListener {
  private val mutableState = MutableStateFlow(WebViewBrowserState.EMPTY)
  val state = mutableState.asStateFlow()

  override fun onNavigationStarted(url: String?) {
    mutableState.update { it.copy(url = url ?: it.url, isLoading = true, progress = 0.0, error = null) }
  }

  override fun onUrlChanged(url: String?) {
    if (url != null) mutableState.update { it.copy(url = url) }
  }

  override fun onTitleChanged(title: String?) {
    mutableState.update { it.copy(title = title) }
  }

  override fun onProgressChanged(progress: Double) {
    if (!progress.isNaN()) mutableState.update { it.copy(progress = progress.coerceIn(0.0, 1.0)) }
  }

  override fun onHistoryChanged(canGoBack: Boolean, canGoForward: Boolean) {
    mutableState.update { it.copy(canGoBack = canGoBack, canGoForward = canGoForward) }
  }

  override fun onNavigationFinished(url: String?, error: WebViewBrowserError?) {
    mutableState.update { it.copy(url = url ?: it.url, isLoading = false, progress = 1.0, error = error) }
  }
}