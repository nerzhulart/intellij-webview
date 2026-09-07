// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.ui

import com.intellij.util.concurrency.annotations.RequiresEdt
import io.github.nerzhulart.webview.api.WebBrowserPanel
import io.github.nerzhulart.webview.api.WebBrowserPanelOptions
import io.github.nerzhulart.webview.api.createWebBrowserPanel
import io.github.nerzhulart.webview.impl.engine.WebView
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.annotations.ApiStatus
import java.awt.BorderLayout
import javax.swing.JPanel

@ApiStatus.Experimental
data class EmbeddedBrowserPanelOptions(
  val browserOptions: WebBrowserPanelOptions = WebBrowserPanelOptions(),
  val showNavigationButtons: Boolean = true,
  val showUrlField: Boolean = true,
)

/** Swing browser toolbar and native page surface. Created only by [createEmbeddedBrowserPanel]. */
@ApiStatus.Experimental
class EmbeddedBrowserPanel internal constructor(
  scope: CoroutineScope,
  val browserPanel: WebBrowserPanel,
  options: EmbeddedBrowserPanelOptions,
) : JPanel(BorderLayout()) {
  val webView: WebView get() = browserPanel.webView

  init {
    add(EmbeddedBrowserToolbar(scope, webView, options), BorderLayout.NORTH)
    add(browserPanel.component, BorderLayout.CENTER)
  }
}

/** Creates the complete browser on EDT. Cancel [scope] to dispose its page and toolbar subscription. */
@ApiStatus.Experimental
@RequiresEdt
suspend fun createEmbeddedBrowserPanel(
  scope: CoroutineScope,
  options: EmbeddedBrowserPanelOptions = EmbeddedBrowserPanelOptions(),
): EmbeddedBrowserPanel = EmbeddedBrowserPanel(scope, createWebBrowserPanel(scope, options.browserOptions), options)