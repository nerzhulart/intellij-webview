package io.github.nerzhulart.webview.demo

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.util.coroutines.childScope
import io.github.nerzhulart.webview.demo.acp.AcpChatPanel
import io.github.nerzhulart.webview.demo.markdown.linkgraph.MarkdownLinkGraphPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import javax.swing.JComponent

@Service(Service.Level.PROJECT)
internal class WebViewDemoProjectService(
  private val project: Project,
  val coroutineScope: CoroutineScope,
) {
  companion object {
    fun getInstance(project: Project): WebViewDemoProjectService = project.service()
  }

  fun createSamplePanelContent(): WebViewDemoContent = createContent("WebViewDemoSamplePanel(${project.name})") { scope ->
    WebViewDemoPanel(scope).component
  }

  fun createControlsShowcaseContent(): WebViewDemoContent = createContent("WebViewDemoControlsShowcase(${project.name})") { scope ->
    WebViewControlsShowcasePanel(project, scope).component
  }

  fun createReactControlsShowcaseContent(): WebViewDemoContent = createContent("WebViewDemoReactControlsShowcase(${project.name})") { scope ->
    WebViewReactControlsShowcasePanel(scope).component
  }

  fun createUiDslShowcaseContent(): WebViewDemoContent = createContent("WebViewDemoUiDslShowcase(${project.name})") { scope ->
    WebViewUiDslShowcasePanel(project, scope).component
  }

  fun createMarkdownLinkGraphContent(): WebViewDemoContent = createContent("WebViewDemoMarkdownLinkGraph(${project.name})") { scope ->
    MarkdownLinkGraphPanel(project, scope).component
  }

  fun createAcpChatContent(): WebViewDemoContent = createContent("WebViewDemoAcpChat(${project.name})") { scope ->
    AcpChatPanel(project, scope).component
  }

  private fun createContent(
    scopeName: String,
    createPanel: (CoroutineScope) -> JComponent,
  ): WebViewDemoContent {
    val contentScope = coroutineScope.childScope(scopeName)
    val component = createPanel(contentScope)
    return WebViewDemoContent(
      component = component,
      disposer = Disposable { contentScope.cancel() },
    )
  }
}

internal data class WebViewDemoContent(
  val component: JComponent,
  val disposer: Disposable,
)
