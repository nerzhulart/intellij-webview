// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.util.ui.JBUI
import io.github.nerzhulart.webview.api.WebViewBrowserState
import io.github.nerzhulart.webview.impl.engine.WebView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.net.URI
import javax.swing.AbstractAction
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

internal class EmbeddedBrowserToolbar(
  private val scope: CoroutineScope,
  private val webView: WebView,
  options: EmbeddedBrowserPanelOptions,
) : JPanel(BorderLayout()) {
  private var urlFieldFocused = false
  private var urlFieldEditing = false
  private var renderingUrl = false

  val backAction = object : DumbAwareAction("Back", "Go back", AllIcons.Actions.Back) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = webView.browserState.value.canGoBack
    }
    override fun actionPerformed(e: AnActionEvent) { runCommand { webView.goBack() } }
  }
  val forwardAction = object : DumbAwareAction("Forward", "Go forward", AllIcons.Actions.Forward) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
      e.presentation.isEnabled = webView.browserState.value.canGoForward
    }
    override fun actionPerformed(e: AnActionEvent) { runCommand { webView.goForward() } }
  }
  val reloadStopAction = object : DumbAwareAction("Reload", "Reload page", AllIcons.Actions.Refresh) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
      val loading = webView.browserState.value.isLoading
      e.presentation.text = if (loading) "Stop" else "Reload"
      e.presentation.description = if (loading) "Stop loading" else "Reload page"
      e.presentation.icon = if (loading) AllIcons.Actions.Suspend else AllIcons.Actions.Refresh
    }
    override fun actionPerformed(e: AnActionEvent) {
      runCommand { if (webView.browserState.value.isLoading) webView.stop() else webView.reload() }
    }
  }

  val urlField = ExtendableTextField()
  val progressBar = JProgressBar(0, 100).apply {
    preferredSize = Dimension(0, JBUI.scale(3))
    minimumSize = Dimension(0, JBUI.scale(3))
    maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(3))
    alignmentX = LEFT_ALIGNMENT
    isBorderPainted = false
  }
  val errorLabel = JBLabel().apply {
    border = JBUI.Borders.empty(3, 6)
    alignmentX = LEFT_ALIGNMENT
    setCopyable(true)
  }
  private val navigationToolbar = if (options.showNavigationButtons) {
    ActionManager.getInstance().createActionToolbar(
      "WebView.EmbeddedBrowser", DefaultActionGroup(backAction, forwardAction, reloadStopAction), true,
    ).apply { targetComponent = this@EmbeddedBrowserToolbar }
  } else null

  init {
    val controls = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
      border = JBUI.Borders.empty(2, 4)
      navigationToolbar?.let { add(it.component, BorderLayout.WEST) }
      urlField.isVisible = options.showUrlField
      if (options.showUrlField) add(urlField, BorderLayout.CENTER)
      isVisible = options.showNavigationButtons || options.showUrlField
    }
    add(controls, BorderLayout.NORTH)
    add(JPanel().apply {
      layout = BoxLayout(this, BoxLayout.Y_AXIS)
      add(progressBar)
      add(errorLabel)
    }, BorderLayout.CENTER)

    urlField.accessibleContext.accessibleName = "Page URL"
    urlField.addFocusListener(object : FocusAdapter() {
      override fun focusGained(e: FocusEvent) { urlFieldFocused = true }
      override fun focusLost(e: FocusEvent) {
        urlFieldFocused = false
        restoreUrl()
      }
    })
    urlField.document.addDocumentListener(object : DocumentListener {
      override fun insertUpdate(e: DocumentEvent) = edited()
      override fun removeUpdate(e: DocumentEvent) = edited()
      override fun changedUpdate(e: DocumentEvent) = edited()
      private fun edited() {
        if (urlFieldFocused && !renderingUrl) urlFieldEditing = true
      }
    })
    urlField.addActionListener {
      val url = normalizeBrowserInput(urlField.text) ?: return@addActionListener
      runCommand {
        val uri = URI(url)
        urlFieldEditing = false
        webView.loadUrl(uri)
      }
    }
    urlField.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "restoreBrowserUrl")
    urlField.actionMap.put("restoreBrowserUrl", object : AbstractAction() {
      override fun actionPerformed(e: ActionEvent) { restoreUrl() }
    })
    render(webView.browserState.value)
    scope.launch(Dispatchers.EDT) {
      try {
        webView.browserState.collect { render(it) }
      }
      finally {
        withContext(NonCancellable + Dispatchers.EDT) {
          progressBar.isIndeterminate = false
          progressBar.isVisible = false
          // Aqua also animates determinate progress; uninstall its UI when the owner is disposed.
          progressBar.setUI(null)
        }
      }
    }
  }

  private fun render(state: WebViewBrowserState) {
    if (!urlFieldEditing) setUrlText(state.url.orEmpty())
    progressBar.isVisible = state.isLoading
    progressBar.isIndeterminate = state.isLoading && state.progress <= 0.0
    progressBar.value = (state.progress * 100).toInt().coerceIn(0, 100)
    errorLabel.text = state.error?.message.orEmpty()
    errorLabel.isVisible = state.error != null
    navigationToolbar?.updateActionsImmediately()
    revalidate()
    repaint()
  }

  private fun restoreUrl() {
    urlFieldEditing = false
    setUrlText(webView.browserState.value.url.orEmpty())
  }

  private fun setUrlText(text: String) {
    if (urlField.text == text) return
    renderingUrl = true
    try { urlField.text = text }
    finally { renderingUrl = false }
  }

  private fun runCommand(command: suspend () -> Unit) {
    scope.launch(Dispatchers.EDT) {
      try { command() }
      catch (e: CancellationException) { throw e }
      catch (e: Exception) {
        errorLabel.text = e.message ?: "Browser command failed"
        errorLabel.isVisible = true
        revalidate()
      }
    }
  }
}

internal fun normalizeBrowserInput(text: String): String? {
  val input = text.trim().takeIf { it.isNotEmpty() } ?: return null
  val hasScheme = BROWSER_URL_SCHEME.containsMatchIn(input) && !BROWSER_HOST_PORT.containsMatchIn(input)
  return if (hasScheme) input else "https://$input"
}

private val BROWSER_URL_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
private val BROWSER_HOST_PORT = Regex("^[^/:]+:[0-9]+(?:[/?#]|$)")