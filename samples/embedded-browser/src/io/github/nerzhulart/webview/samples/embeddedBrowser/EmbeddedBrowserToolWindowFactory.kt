// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.samples.embeddedBrowser

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.platform.util.coroutines.childScope
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import io.github.nerzhulart.webview.api.WebBrowserPanelOptions
import io.github.nerzhulart.webview.impl.engine.WebViewCreationOptions
import io.github.nerzhulart.webview.impl.engine.WebViewFeatures
import io.github.nerzhulart.webview.ui.EmbeddedBrowserPanelOptions
import io.github.nerzhulart.webview.ui.createEmbeddedBrowserPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.net.URI
import javax.swing.JComponent
import javax.swing.JPanel

internal class EmbeddedBrowserToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val contentScope = project.service<EmbeddedBrowserScopeService>()
      .coroutineScope
      .childScope("EmbeddedBrowser(${project.name})")
    val container = JPanel(BorderLayout()).apply {
      add(JBLabel("Loading browser..."), BorderLayout.CENTER)
    }
    val content = ContentFactory.getInstance().createContent(container, "", false)
    content.setDisposer(Disposable { contentScope.cancel() })
    toolWindow.contentManager.addContent(content)

    contentScope.launch(Dispatchers.EDT) {
      val component: JComponent = try {
        createEmbeddedBrowserPanel(
          scope = contentScope,
          options = EmbeddedBrowserPanelOptions(
            browserOptions = WebBrowserPanelOptions(
              initialUrl = URI("https://www.jetbrains.com"),
              webViewOptions = WebViewCreationOptions(
                debugName = "embedded-browser",
                features = WebViewFeatures.BROWSER.copy(elasticScrolling = true),
              ),
            ),
          ),
        )
      }
      catch (t: CancellationException) {
        throw t
      }
      catch (t: IllegalStateException) {
        ensureActive()
        JBLabel("Embedded browser unavailable: ${t.message ?: "Unknown error"}")
      }
      ensureActive()
      container.removeAll()
      container.add(component, BorderLayout.CENTER)
      container.revalidate()
      container.repaint()
    }
  }
}