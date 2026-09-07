// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.api

import com.intellij.util.concurrency.annotations.RequiresEdt
import io.github.nerzhulart.webview.impl.engine.WebView
import io.github.nerzhulart.webview.impl.engine.WebViewCreationOptions
import io.github.nerzhulart.webview.impl.engine.WebViewFeatures
import io.github.nerzhulart.webview.impl.engine.WebViewRuntime
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.annotations.ApiStatus
import java.net.URI
import javax.swing.JComponent

@ApiStatus.Experimental
data class WebBrowserPanelOptions(
  /** An absolute URI to load on creation, or `null` to leave navigation to the caller. */
  val initialUrl: URI? = null,
  val webViewOptions: WebViewCreationOptions = WebViewCreationOptions(features = WebViewFeatures.BROWSER),
)

/** Arbitrary-URL surface without a toolbar. [component] is the real WebView's Swing control. */
@ApiStatus.Experimental
class WebBrowserPanel internal constructor(val webView: WebView) {
  val component: JComponent get() = webView.component
}

/** Creates a browser whose lifetime is owned by [scope]; cancel the scope to dispose it. */
@ApiStatus.Experimental
@RequiresEdt
suspend fun createWebBrowserPanel(
  scope: CoroutineScope,
  options: WebBrowserPanelOptions = WebBrowserPanelOptions(),
): WebBrowserPanel = WebViewRuntime.getInstance().createWebBrowserPanel(scope, options)