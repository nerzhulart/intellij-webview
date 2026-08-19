// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.demo.markdown.linkgraph

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.api.WebViewPanelOptions
import io.github.nerzhulart.webview.api.createWebViewPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

internal class MarkdownLinkGraphPanel(
  private val project: Project,
  private val scope: CoroutineScope,
) {
  val component: JComponent = JPanel(BorderLayout())

  init {
    loadWebView()
  }

  private fun loadWebView() {
    scope.launch {
      try {
        withContext(Dispatchers.EDT) {
          createWebViewPanel(
            scope = scope,
            options = WebViewPanelOptions(
              assetRoot = ASSET_ROOT,
              debugName = "Markdown link graph",
            ),
          ).also { webViewPanel ->
            webViewPanel.interop.implement(MarkdownLinkGraphHostApi.ID, MarkdownLinkGraphHostApiImpl(project))
            webViewPanel.reload()
            component.add(webViewPanel.component, BorderLayout.CENTER)
            component.revalidate()
            component.repaint()
          }
        }
      }
      catch (t: CancellationException) {
        throw t
      }
      catch (t: Throwable) {
        LOG.warn("Failed to load Markdown link graph WebView", t)
      }
    }
  }

  companion object {
    private val LOG = logger<MarkdownLinkGraphPanel>()
    private val ASSET_ROOT = WebViewAssetRoot.forView("markdown-link-graph")
  }
}
