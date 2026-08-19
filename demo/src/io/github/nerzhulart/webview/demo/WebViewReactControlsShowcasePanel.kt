// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.demo

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.api.WebViewPanelOptions
import io.github.nerzhulart.webview.api.createWebViewPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

private const val REACT_CONTROLS_VIEW_ID = "react-controls-showcase"

private val REACT_CONTROLS_ASSET_ROOT = WebViewAssetRoot.forView(REACT_CONTROLS_VIEW_ID)
private val LOG = logger<WebViewReactControlsShowcasePanel>()

internal class WebViewReactControlsShowcasePanel(
  private val scope: CoroutineScope,
) {
  val component: JComponent = JPanel(BorderLayout()).apply {
    minimumSize = Dimension(400, 300)
    preferredSize = Dimension(900, 650)
  }

  init {
    loadShowcase()
  }

  private fun loadShowcase() {
    scope.launch {
      try {
        val createdPanel = withContext(Dispatchers.EDT) {
          createWebViewPanel(
            scope = scope,
            options = WebViewPanelOptions(
              assetRoot = REACT_CONTROLS_ASSET_ROOT,
              debugName = "WebView React controls showcase",
            ),
          )
        }

        withContext(Dispatchers.EDT) {
          component.add(createdPanel.component, BorderLayout.CENTER)
          component.revalidate()
          component.repaint()
        }
      }
      catch (t: Throwable) {
        LOG.warn("Failed to load WebView React controls showcase", t)
      }
    }
  }
}

