// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.TestApplication
import io.github.nerzhulart.webview.api.WebViewAssetPath
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.api.WebViewBrowserError
import io.github.nerzhulart.webview.api.WebViewBrowserState
import io.github.nerzhulart.webview.api.WebViewInterop
import io.github.nerzhulart.webview.impl.engine.WebView
import io.github.nerzhulart.webview.impl.engine.WebViewEngineCapabilities
import io.github.nerzhulart.webview.impl.engine.WebViewEngineId
import io.github.nerzhulart.webview.impl.engine.WebViewRuntimeInfo
import io.github.nerzhulart.webview.impl.engine.WebViewScriptResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.intellij.lang.annotations.Language
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.net.URI
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.time.Duration.Companion.seconds

@TestApplication
internal class EmbeddedBrowserToolbarTest {
  @Test
  fun historyActionsFollowStateAndInvokeWebView(): Unit = runToolbarTest {
    assertFalse(updateAction(toolbar.backAction).presentation.isEnabled)
    assertFalse(updateAction(toolbar.forwardAction).presentation.isEnabled)

    emitState(WebViewBrowserState(canGoBack = true))
    assertTrue(updateAction(toolbar.backAction).presentation.isEnabled)
    assertFalse(updateAction(toolbar.forwardAction).presentation.isEnabled)
    perform(toolbar.backAction)
    assertEquals(1, webView.goBackCalls)
    assertEquals(0, webView.goForwardCalls)

    emitState(WebViewBrowserState(canGoForward = true))
    assertFalse(updateAction(toolbar.backAction).presentation.isEnabled)
    assertTrue(updateAction(toolbar.forwardAction).presentation.isEnabled)
    perform(toolbar.forwardAction)
    assertEquals(1, webView.goBackCalls)
    assertEquals(1, webView.goForwardCalls)

    emitState(WebViewBrowserState(canGoBack = true, canGoForward = true))
    assertTrue(updateAction(toolbar.backAction).presentation.isEnabled)
    assertTrue(updateAction(toolbar.forwardAction).presentation.isEnabled)

    emitState(WebViewBrowserState.EMPTY)
    assertFalse(updateAction(toolbar.backAction).presentation.isEnabled)
    assertFalse(updateAction(toolbar.forwardAction).presentation.isEnabled)
    assertEquals(1, webView.goBackCalls)
    assertEquals(1, webView.goForwardCalls)
  }

  @Test
  fun reloadStopActionChangesPresentationAndCommandWithLoadingState(): Unit = runToolbarTest {
    val idle = updateAction(toolbar.reloadStopAction).presentation
    assertTrue(idle.isEnabled)
    assertEquals("Reload", idle.text)
    assertSame(AllIcons.Actions.Refresh, idle.icon)
    perform(toolbar.reloadStopAction)
    assertEquals(1, webView.reloadCalls)
    assertEquals(0, webView.stopCalls)

    emitState(WebViewBrowserState(isLoading = true))
    val loading = updateAction(toolbar.reloadStopAction).presentation
    assertTrue(loading.isEnabled)
    assertEquals("Stop", loading.text)
    assertSame(AllIcons.Actions.Suspend, loading.icon)
    perform(toolbar.reloadStopAction)
    assertEquals(1, webView.reloadCalls)
    assertEquals(1, webView.stopCalls)

    emitState(WebViewBrowserState(isLoading = false, progress = 1.0))
    val completed = updateAction(toolbar.reloadStopAction).presentation
    assertEquals("Reload", completed.text)
    assertSame(AllIcons.Actions.Refresh, completed.icon)
    perform(toolbar.reloadStopAction)
    assertEquals(2, webView.reloadCalls)
    assertEquals(1, webView.stopCalls)
  }

  @Test
  fun reloadStopActionUsesCurrentStateRatherThanLastPresentation(): Unit = runToolbarTest {
    val event = updateAction(toolbar.reloadStopAction)
    emitState(WebViewBrowserState(isLoading = true))
    toolbar.reloadStopAction.actionPerformed(event)
    yield()
    assertEquals(0, webView.reloadCalls)
    assertEquals(1, webView.stopCalls)

    val loadingEvent = updateAction(toolbar.reloadStopAction)
    emitState(WebViewBrowserState.EMPTY)
    toolbar.reloadStopAction.actionPerformed(loadingEvent)
    yield()
    assertEquals(1, webView.reloadCalls)
    assertEquals(1, webView.stopCalls)
  }

  @Test
  fun progressAndErrorFollowNavigationLifecycle(): Unit = runToolbarTest {
    assertFalse(toolbar.progressBar.isVisible)
    assertFalse(toolbar.errorLabel.isVisible)

    emitState(WebViewBrowserState(url = "https://example.com", isLoading = true))
    assertTrue(toolbar.progressBar.isVisible)
    assertTrue(toolbar.progressBar.isIndeterminate)
    assertEquals(0, toolbar.progressBar.value)

    emitState(webView.browserState.value.copy(progress = 0.42))
    assertTrue(toolbar.progressBar.isVisible)
    assertFalse(toolbar.progressBar.isIndeterminate)
    assertEquals(42, toolbar.progressBar.value)

    emitState(webView.browserState.value.copy(
      isLoading = false,
      error = WebViewBrowserError("https://example.com", "Connection failed", -1),
    ))
    assertFalse(toolbar.progressBar.isVisible)
    assertTrue(toolbar.errorLabel.isVisible)
    assertEquals("Connection failed", toolbar.errorLabel.text)

    emitState(WebViewBrowserState(url = "https://example.org", isLoading = true))
    assertTrue(toolbar.progressBar.isVisible)
    assertTrue(toolbar.progressBar.isIndeterminate)
    assertFalse(toolbar.errorLabel.isVisible)
    assertEquals("", toolbar.errorLabel.text)

    emitState(webView.browserState.value.copy(isLoading = false, progress = 1.0))
    assertFalse(toolbar.progressBar.isVisible)
    assertFalse(toolbar.progressBar.isIndeterminate)
    assertEquals(100, toolbar.progressBar.value)
    assertFalse(toolbar.errorLabel.isVisible)
  }

  @Test
  fun initialStateIsRenderedWithoutAnotherEmission(): Unit = runToolbarTest(
    initialState = WebViewBrowserState(
      url = "https://example.com/initial",
      canGoBack = true,
      error = WebViewBrowserError("https://example.com/initial", "Initial failure"),
    ),
  ) {
    assertEquals("https://example.com/initial", toolbar.urlField.text)
    assertTrue(updateAction(toolbar.backAction).presentation.isEnabled)
    assertFalse(toolbar.progressBar.isVisible)
    assertTrue(toolbar.errorLabel.isVisible)
    assertEquals("Initial failure", toolbar.errorLabel.text)
  }

  @Test
  fun normalizeBrowserInputHandlesBlankHostsAndExplicitSchemes() {
    val cases = listOf(
      "" to null,
      " \t\n " to null,
      "example.com" to "https://example.com",
      "  example.com/path?q=1#section  " to "https://example.com/path?q=1#section",
      "localhost:8080/path" to "https://localhost:8080/path",
      "example.com:8443" to "https://example.com:8443",
      " http://example.com/path " to "http://example.com/path",
      "https://example.com" to "https://example.com",
      "about:blank" to "about:blank",
    )
    for ((input, expected) in cases) {
      assertEquals(expected, normalizeBrowserInput(input), "Input: $input")
    }
  }

  @Test
  fun enterLoadsNormalizedUrlAndIgnoresBlankInput(): Unit = runToolbarTest {
    focusUrlField(true)
    for (input in listOf("", " \t ")) {
      toolbar.urlField.text = input
      toolbar.urlField.postActionEvent()
      yield()
      assertTrue(webView.loadedUrls.isEmpty())
    }

    val cases = listOf(
      "  example.com/path  " to "https://example.com/path",
      "localhost:8080" to "https://localhost:8080",
      " http://example.org " to "http://example.org",
      "https://example.net" to "https://example.net",
      "https://example.net/a%20b?q=%2F#fragment" to "https://example.net/a%20b?q=%2F#fragment",
      "about:blank" to "about:blank",
    )
    val expectedUrls = mutableListOf<URI>()
    for ((input, expected) in cases) {
      toolbar.urlField.text = input
      toolbar.urlField.postActionEvent()
      yield()
      expectedUrls.add(URI(expected))
      assertEquals(expectedUrls, webView.loadedUrls)

      emitState(WebViewBrowserState(url = "$expected#redirected"))
      assertEquals("$expected#redirected", toolbar.urlField.text)
    }
  }

  @Test
  fun invalidInputShowsErrorWithoutNavigationAndCanBeCorrected(): Unit = runToolbarTest {
    focusUrlField(true)
    for (input in listOf("https://exa mple.com", "https://example.com/%zz", "http://[invalid")) {
      toolbar.urlField.text = input
      toolbar.urlField.postActionEvent()
      yield()
      assertTrue(webView.loadedUrls.isEmpty(), "Invalid input must not reach WebView: $input")
      assertTrue(toolbar.errorLabel.isVisible)
      assertTrue(toolbar.errorLabel.text.isNotBlank())
      assertTrue(scope.coroutineContext.job.isActive)

      emitState(WebViewBrowserState(url = "https://example.com/current", title = input))
      assertEquals(input, toolbar.urlField.text, "Invalid input must remain editable through state updates")
    }

    toolbar.urlField.text = "example.com/corrected"
    toolbar.urlField.postActionEvent()
    yield()
    assertEquals(listOf(URI("https://example.com/corrected")), webView.loadedUrls)
    emitState(WebViewBrowserState(url = "https://example.com/corrected", isLoading = true))
    assertFalse(toolbar.errorLabel.isVisible)
    assertEquals("https://example.com/corrected", toolbar.urlField.text)
  }

  @Test
  fun urlFollowsRedirectsUnlessUserIsEditing(): Unit = runToolbarTest {
    emitState(WebViewBrowserState(url = "https://example.com"))
    assertEquals("https://example.com", toolbar.urlField.text)

    focusUrlField(true)
    emitState(WebViewBrowserState(url = "https://example.com/redirect"))
    assertEquals("https://example.com/redirect", toolbar.urlField.text)
    emitState(WebViewBrowserState(url = "https://example.com/final"))
    assertEquals("https://example.com/final", toolbar.urlField.text)

    focusUrlField(false)
    emitState(WebViewBrowserState.EMPTY)
    assertEquals("", toolbar.urlField.text)
    assertTrue(webView.loadedUrls.isEmpty())
  }

  @Test
  fun focusedEditsSurviveRedirectsUntilFocusLost(): Unit = runToolbarTest {
    emitState(WebViewBrowserState(url = "https://example.com"))
    focusUrlField(true)
    toolbar.urlField.text = "my-unsubmitted-host.test"

    emitState(WebViewBrowserState(url = "https://example.com/redirect", isLoading = true, progress = 0.25))
    assertEquals("my-unsubmitted-host.test", toolbar.urlField.text)
    assertEquals(25, toolbar.progressBar.value)
    emitState(WebViewBrowserState(url = "https://example.com/final", progress = 1.0))
    assertEquals("my-unsubmitted-host.test", toolbar.urlField.text)

    focusUrlField(false)
    assertEquals("https://example.com/final", toolbar.urlField.text)
    emitState(WebViewBrowserState(url = "https://example.org"))
    assertEquals("https://example.org", toolbar.urlField.text)
    assertTrue(webView.loadedUrls.isEmpty())
  }

  @Test
  fun escapeRestoresLatestUrlAndResumesUpdatesWhileStillFocused(): Unit = runToolbarTest {
    emitState(WebViewBrowserState(url = "https://example.com"))
    focusUrlField(true)
    toolbar.urlField.text = "discard-this-edit.test"
    emitState(WebViewBrowserState(url = "https://example.com/redirect"))
    assertEquals("discard-this-edit.test", toolbar.urlField.text)

    val key = toolbar.urlField.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0))
    assertNotNull(key, "Escape must be bound on the URL field")
    val action = toolbar.urlField.actionMap.get(key)
    assertNotNull(action)
    action.actionPerformed(ActionEvent(toolbar.urlField, ActionEvent.ACTION_PERFORMED, "Escape"))
    assertEquals("https://example.com/redirect", toolbar.urlField.text)

    emitState(WebViewBrowserState(url = "https://example.com/final"))
    assertEquals("https://example.com/final", toolbar.urlField.text)
    toolbar.urlField.text = "another-edit.test"
    emitState(WebViewBrowserState(url = "https://example.org"))
    assertEquals("another-edit.test", toolbar.urlField.text)
    assertTrue(webView.loadedUrls.isEmpty())
  }

  @Test
  fun optionsIndependentlyHideNavigationAndUrlControls() {
    for (showNavigation in listOf(true, false)) {
      for (showUrl in listOf(true, false)) {
        runToolbarTest(EmbeddedBrowserPanelOptions(showNavigationButtons = showNavigation, showUrlField = showUrl)) {
          val controls = (toolbar.layout as BorderLayout).getLayoutComponent(BorderLayout.NORTH) as JPanel
          val layout = controls.layout as BorderLayout
          assertEquals(showNavigation, layout.getLayoutComponent(BorderLayout.WEST) != null)
          assertEquals(showUrl, toolbar.urlField.isVisible)
          if (showUrl) {
            assertSame(toolbar.urlField, layout.getLayoutComponent(BorderLayout.CENTER))
          }
          else {
            assertNull(toolbar.urlField.parent)
          }
          assertEquals(showNavigation || showUrl, controls.isVisible)

          emitState(WebViewBrowserState(isLoading = true, progress = 0.5))
          assertTrue(toolbar.progressBar.isVisible)
          assertEquals(50, toolbar.progressBar.value)
        }
      }
    }
  }

  @Test
  fun backgroundStateEmissionsUpdateSwingOnEdt(): Unit = runToolbarTest {
    val notificationsOnEdt = mutableListOf<Boolean>()
    toolbar.urlField.document.addDocumentListener(object : DocumentListener {
      override fun insertUpdate(e: DocumentEvent) { notificationsOnEdt.add(SwingUtilities.isEventDispatchThread()) }
      override fun removeUpdate(e: DocumentEvent) { notificationsOnEdt.add(SwingUtilities.isEventDispatchThread()) }
      override fun changedUpdate(e: DocumentEvent) { notificationsOnEdt.add(SwingUtilities.isEventDispatchThread()) }
    })
    toolbar.progressBar.addChangeListener { notificationsOnEdt.add(SwingUtilities.isEventDispatchThread()) }
    toolbar.errorLabel.addPropertyChangeListener("text") { notificationsOnEdt.add(SwingUtilities.isEventDispatchThread()) }

    emitState(WebViewBrowserState(
      url = "https://example.com",
      progress = 0.5,
      error = WebViewBrowserError("https://example.com", "Failed"),
    ))
    assertEquals("https://example.com", toolbar.urlField.text)
    assertEquals(50, toolbar.progressBar.value)
    assertEquals("Failed", toolbar.errorLabel.text)
    assertTrue(notificationsOnEdt.isNotEmpty())
    assertTrue(notificationsOnEdt.all { it }, "All Swing changes must run on EDT")
  }

  @Test
  fun cancellingScopeStopsStateCollection(): Unit = runToolbarTest {
    emitState(WebViewBrowserState(url = "https://example.com", isLoading = true, progress = 0.35))
    assertEquals("https://example.com", toolbar.urlField.text)
    assertEquals(35, toolbar.progressBar.value)
    assertTrue(webView.browserState.subscriptionCount.value > 0)

    scope.coroutineContext.job.cancelAndJoin()
    assertEquals(0, webView.browserState.subscriptionCount.value)
    assertFalse(toolbar.progressBar.isVisible, "Disposal hides the progress indicator")
    assertFalse(toolbar.progressBar.isIndeterminate, "Disposal stops the Swing animation timer")
    emitState(WebViewBrowserState(
      url = "https://example.org",
      progress = 1.0,
      error = WebViewBrowserError("https://example.org", "After cancellation"),
    ))
    assertEquals("https://example.com", toolbar.urlField.text)
    assertFalse(toolbar.progressBar.isVisible)
    assertEquals(35, toolbar.progressBar.value)
    assertFalse(toolbar.errorLabel.isVisible)
  }

  @Test
  fun cancellingScopeStopsIndeterminateAnimation(): Unit = runToolbarTest {
    emitState(WebViewBrowserState(isLoading = true, progress = 0.0))
    assertTrue(toolbar.progressBar.isIndeterminate)
    assertTrue(toolbar.progressBar.isVisible)
    scope.coroutineContext.job.cancelAndJoin()
    assertFalse(toolbar.progressBar.isIndeterminate)
    assertFalse(toolbar.progressBar.isVisible)
    assertEquals(0, webView.browserState.subscriptionCount.value)
  }

  private fun runToolbarTest(
    options: EmbeddedBrowserPanelOptions = EmbeddedBrowserPanelOptions(),
    initialState: WebViewBrowserState = WebViewBrowserState.EMPTY,
    block: suspend ToolbarFixture.() -> Unit,
  ): Unit = runBlocking {
    withTimeout(10.seconds) {
      withContext(Dispatchers.EDT) {
        val ownerJob = Job(coroutineContext.job)
        @Suppress("RAW_SCOPE_CREATION")
        val scope = CoroutineScope(coroutineContext + ownerJob)
        try {
          val webView = FakeWebView(initialState)
          val toolbar = EmbeddedBrowserToolbar(scope, webView, options)
          webView.browserState.subscriptionCount.first { it > 0 }
          yield()
          val fixture = ToolbarFixture(scope, webView, toolbar)
          try {
            fixture.block()
          }
          finally {
            // Release synthetic focus to stop the Swing caret timer, even when assertions fail.
            fixture.focusUrlField(false)
          }
        }
        finally {
          ownerJob.cancelAndJoin()
        }
      }
    }
  }

  private class ToolbarFixture(
    val scope: CoroutineScope,
    val webView: FakeWebView,
    val toolbar: EmbeddedBrowserToolbar,
  ) {
    suspend fun emitState(state: WebViewBrowserState) {
      withContext(Dispatchers.Default) { webView.browserState.value = state }
      yield()
    }

    fun updateAction(action: AnAction): AnActionEvent {
      assertEquals(ActionUpdateThread.EDT, action.actionUpdateThread)
      return AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, DataContext.EMPTY_CONTEXT).also {
        action.update(it)
      }
    }

    suspend fun perform(action: AnAction) {
      val event = updateAction(action)
      assertTrue(event.presentation.isEnabled)
      action.actionPerformed(event)
      yield()
    }

    fun focusUrlField(focused: Boolean) {
      // A headless field cannot become the real AWT focus owner.
      val event = FocusEvent(toolbar.urlField, if (focused) FocusEvent.FOCUS_GAINED else FocusEvent.FOCUS_LOST)
      for (listener in toolbar.urlField.focusListeners) {
        if (focused) listener.focusGained(event) else listener.focusLost(event)
      }
    }
  }

  private class FakeWebView(initialState: WebViewBrowserState) : WebView {
    override val browserState = MutableStateFlow(initialState)
    override val isBrowserNavigationSupported = true
    override val component: JComponent = JPanel()
    override val runtimeInfo = WebViewRuntimeInfo(
      engineId = WebViewEngineId("toolbar-test"),
      capabilities = WebViewEngineCapabilities(
        assetServing = false,
        messagePassing = false,
        interactiveInput = true,
        navigation = true,
      ),
      displayName = "Toolbar test",
    )
    override val interop: WebViewInterop get() = error("Toolbar must not use page interop")

    val loadedUrls = mutableListOf<URI>()
    var goBackCalls = 0
    var goForwardCalls = 0
    var reloadCalls = 0
    var stopCalls = 0

    override suspend fun loadUrl(url: URI) { loadedUrls.add(url) }
    override suspend fun goBack() { goBackCalls++ }
    override suspend fun goForward() { goForwardCalls++ }
    override suspend fun reload() { reloadCalls++ }
    override suspend fun stop() { stopCalls++ }

    override suspend fun loadFile(file: VirtualFile): Unit = error("Unexpected file navigation")
    override suspend fun loadAsset(root: WebViewAssetRoot, entry: WebViewAssetPath, query: String?): Unit = error("Unexpected asset navigation")
    override suspend fun loadHtml(@Language("HTML") html: String): Unit = error("Unexpected HTML navigation")
    override suspend fun evaluateJavaScript(@Language("JavaScript") script: String): WebViewScriptResult = error("Unexpected script evaluation")
  }
}