// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.demo

import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.api.WebViewIconSet
import io.github.nerzhulart.webview.api.WebViewNotification
import io.github.nerzhulart.webview.api.WebViewPanelOptions
import io.github.nerzhulart.webview.api.createWebViewPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

private const val UI_DSL_SHOWCASE_VIEW_ID = "ui-dsl-showcase"
private const val UI_DSL_SHOWCASE_SOURCE_PATH = "community/plugins/ui.webview/demo/webview-src/views/ui-dsl-showcase/src/main.tsx"

private val UI_DSL_SHOWCASE_ASSET_ROOT = WebViewAssetRoot
  .forView(UI_DSL_SHOWCASE_VIEW_ID)
  .withIconSets(WebViewIconSet.allIcons())

private val LOG = logger<WebViewUiDslShowcasePanel>()

internal class WebViewUiDslShowcasePanel(
  private val project: Project,
  private val scope: CoroutineScope,
) {
  val component: JComponent = JPanel(BorderLayout()).apply {
    minimumSize = Dimension(400, 300)
    preferredSize = Dimension(900, 650)
    add(JBLabel(WebViewDemoBundle.message("webview.ui.dsl.showcase.loading"), SwingConstants.CENTER), BorderLayout.CENTER)
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
              assetRoot = UI_DSL_SHOWCASE_ASSET_ROOT,
              debugName = "WebView UI DSL showcase",
            ),
          )
        }

        createdPanel.interop.messageBus.registerNotificationHandler(UiDslShowcaseNotifications.openSource) { _, _ ->
          withContext(Dispatchers.EDT) {
            openUiDslShowcaseSource(project)
          }
        }

        withContext(Dispatchers.EDT) {
          component.removeAll()
          component.add(createdPanel.component, BorderLayout.CENTER)
          component.revalidate()
          component.repaint()
        }
      }
      catch (t: Throwable) {
        LOG.warn("Failed to load WebView UI DSL showcase", t)
        withContext(Dispatchers.EDT) {
          component.removeAll()
          component.add(JBLabel(WebViewDemoBundle.message("webview.ui.dsl.showcase.load.failed"), SwingConstants.CENTER), BorderLayout.CENTER)
          component.revalidate()
          component.repaint()
        }
      }
    }
  }
}

@Serializable
private class EmptyUiDslShowcaseEvent

private class UiDslShowcaseNotification<Params : Any>(
  override val method: String,
  override val paramsSerializer: KSerializer<Params>,
) : WebViewNotification<Params>

private object UiDslShowcaseNotifications {
  val openSource = UiDslShowcaseNotification("demo/uiDslShowcase/openSource", EmptyUiDslShowcaseEvent.serializer())
}

private fun openUiDslShowcaseSource(project: Project) {
  val basePath = project.basePath ?: return
  val sourcePath = Path.of(basePath).resolve(UI_DSL_SHOWCASE_SOURCE_PATH)
  val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourcePath)
  if (virtualFile == null) {
    LOG.warn("WebView UI DSL showcase source file not found: $sourcePath")
    return
  }
  OpenFileDescriptor(project, virtualFile).navigate(true)
}

