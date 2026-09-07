// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.engine

import com.intellij.openapi.vfs.VirtualFile
import io.github.nerzhulart.webview.api.WebViewAssetPath
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.api.WebViewBrowserState
import io.github.nerzhulart.webview.api.WebViewInterop
import kotlinx.coroutines.flow.StateFlow
import org.intellij.lang.annotations.Language
import org.jetbrains.annotations.ApiStatus
import java.net.URI
import javax.swing.JComponent

@ApiStatus.Experimental
interface WebView {
  /**
   * Typed protocol facade for this WebView instance. Inert on pages without the WebView SDK.
   */
  val interop: WebViewInterop
  val runtimeInfo: WebViewRuntimeInfo
  val component: JComponent

  val browserState: StateFlow<WebViewBrowserState>
  val isBrowserNavigationSupported: Boolean

  /**
   * Loads an absolute [URI]; supported schemes depend on the engine.
   * Relative URIs are rejected. Unsupported browser commands are logged and ignored.
   */
  suspend fun loadUrl(url: URI)
  suspend fun goBack()
  suspend fun goForward()
  suspend fun reload()
  suspend fun stop()

  suspend fun loadFile(file: VirtualFile)

  suspend fun loadAsset(root: WebViewAssetRoot, entry: WebViewAssetPath = WebViewAssetPath.indexHtml(), query: String? = null)

  suspend fun loadHtml(@Language("HTML") html: String)

  suspend fun evaluateJavaScript(@Language("JavaScript") script: String): WebViewScriptResult
}

@ApiStatus.Experimental
data class WebViewScriptResult(val value: String?)
