package io.github.nerzhulart.webview.impl.engine

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import io.github.nerzhulart.webview.api.WebViewAssetPath
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.api.WebViewInterop
import io.github.nerzhulart.webview.impl.WebViewConsoleCapture
import io.github.nerzhulart.webview.impl.traceWebViewPerf
import javax.swing.JComponent

private val LOG = logger<WebViewSession>()

internal class WebViewSession(
    private val engine: WebViewEngine,
    private val consoleCapture: WebViewConsoleCapture,
    override val component: JComponent,
    override val interop: WebViewInterop,
    override val runtimeInfo: WebViewRuntimeInfo,
    private val debugName: String?,
) : WebView {
  override suspend fun loadFile(file: VirtualFile) {
    consoleCapture.setViewId(null)
    val path = file.toNioPathOrNull() ?: error("WebView can load only local files: ${file.presentableUrl}")
    engine.loadFile(path)
  }

  override suspend fun loadAsset(root: WebViewAssetRoot, entry: WebViewAssetPath, query: String?) {
    consoleCapture.setViewId(root.viewId)
    LOG.traceWebViewPerf(
      "webview.loadAsset.enqueue",
      "provider=${runtimeInfo.engineId}, viewId=${root.viewId}, entry=$entry, debugName=${debugName.orEmpty()}",
    ) {
      engine.loadAsset(root, entry, query.withWebViewTheme())
    }
  }

  override suspend fun loadHtml(html: String) {
    consoleCapture.setViewId(null)
    engine.loadHtml(html)
  }

  override suspend fun evaluateJavaScript(script: String): WebViewScriptResult {
    return WebViewScriptResult(engine.evaluateJavaScript(script))
  }
}