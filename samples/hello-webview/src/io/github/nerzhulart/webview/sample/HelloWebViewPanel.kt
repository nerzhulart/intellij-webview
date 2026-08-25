package io.github.nerzhulart.webview.sample

import com.intellij.util.concurrency.annotations.RequiresEdt
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.api.WebViewPanelOptions
import io.github.nerzhulart.webview.api.createWebViewPanel
import kotlinx.coroutines.CoroutineScope
import javax.swing.JComponent

internal class HelloWebViewPanel private constructor(
  val component: JComponent,
) {
  companion object {
    @RequiresEdt
    suspend fun create(
      scope: CoroutineScope,
      hostApi: HelloHostApi,
    ): HelloWebViewPanel {
      val panel = createWebViewPanel(
        scope = scope,
        options = WebViewPanelOptions(
          assetRoot = WebViewAssetRoot.forView("hello"),
          debugName = "Hello WebView",
        ),
      )
      panel.interop.implement(HelloHostApi.ID, hostApi)
      return HelloWebViewPanel(panel.component)
    }
  }
}