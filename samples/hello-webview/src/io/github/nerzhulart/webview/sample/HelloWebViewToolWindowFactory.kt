package io.github.nerzhulart.webview.sample

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.platform.util.coroutines.childScope
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.JPanel

internal class HelloWebViewToolWindowFactory : ToolWindowFactory, DumbAware {
  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val contentScope = project.service<HelloWebViewProjectService>()
      .coroutineScope
      .childScope("HelloWebView(${project.name})")
    val container = JPanel(BorderLayout())
    val content = ContentFactory.getInstance().createContent(container, "", false)
    content.setDisposer(Disposable { contentScope.cancel() })
    toolWindow.contentManager.addContent(content)

    contentScope.launch {
      try {
        val panel = withContext(Dispatchers.EDT) {
          HelloWebViewPanel.create(contentScope, IgnoreHelloHostApi)
        }
        withContext(Dispatchers.EDT) {
          container.add(panel.component, BorderLayout.CENTER)
          container.revalidate()
          container.repaint()
        }
      }
      catch (t: CancellationException) {
        throw t
      }
      catch (t: Throwable) {
        LOG.warn("Failed to load Hello WebView", t)
      }
    }
  }

  private companion object {
    private val LOG = logger<HelloWebViewToolWindowFactory>()
  }
}

@Service(Service.Level.PROJECT)
private class HelloWebViewProjectService(
  val coroutineScope: CoroutineScope,
)

private object IgnoreHelloHostApi : HelloHostApi {
  override suspend fun buttonClicked() = Unit
}