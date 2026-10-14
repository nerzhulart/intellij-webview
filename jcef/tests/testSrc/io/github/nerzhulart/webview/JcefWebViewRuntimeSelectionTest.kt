// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.ui.UIUtil
import io.github.nerzhulart.webview.impl.engine.WebViewEngineId
import io.github.nerzhulart.webview.impl.engine.WebViewEngineKind
import io.github.nerzhulart.webview.impl.engine.WebViewRuntime
import io.github.nerzhulart.webview.impl.engine.WebViewEngineProvider
import io.github.nerzhulart.webview.impl.jcef.JcefEngineProvider
import io.github.nerzhulart.webview.impl.jcef.JcefWebViewEngine
import io.github.nerzhulart.webview.impl.jcef.createJcefWebViewEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cef.CefApp
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import javax.swing.JPanel
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestApplication
internal class JcefWebViewRuntimeSelectionTest {
  @Test
  fun jcefProvider_participatesInSystemEngineSelectionWithPlatformPriority() {
    val provider = JcefEngineProvider()

    assertEquals(10, provider.selectionPriority(WebViewEngineKind.Jcef))
    assertEquals(if (SystemInfo.isLinux) 0 else 100, provider.selectionPriority(WebViewEngineKind.System))
  }

  @Test
  fun createWebView_usesJcefProviderWhenRegistryOverrideIsEnabledProgrammatically(@TestDisposable disposable: Disposable): Unit = runBlocking {
    registerJcefWebViewEngineProviders(disposable)
    withProgrammaticJcefOverride {
      assumeTrue(JBCefApp.isSupported(), "JCEF is not supported in this environment")

      @Suppress("RAW_SCOPE_CREATION") // Test: no parent scope available without product code.
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val webView = withContext(Dispatchers.EDT) {
          WebViewRuntime.getInstance().createWebView(scope)
        }

        assertEquals(WebViewEngineId.JCEF, webView.runtimeInfo.engineId)
      }
      finally {
        scope.coroutineContext.job.cancelAndJoin()
      }
    }
  }

  @Test
  fun jcefEngine_placeholderPageAndComponentFollowHostBackground(): Unit = runBlocking {
    withProgrammaticJcefOverride {
      assumeTrue(JBCefApp.isSupported(), "JCEF is not supported in this environment")

      @Suppress("RAW_SCOPE_CREATION") // Test: no parent scope available without product code.
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      try {
        val engine = withContext(Dispatchers.EDT) { createJcefWebViewEngine(scope) }
        try {
          val panel = UIUtil.getPanelBackground()
          assertEquals(
            "rgb(${panel.red}, ${panel.green}, ${panel.blue})",
            awaitPageBackground(engine),
            "Chromium shows the placeholder page until the real one paints, so it has the host background",
          )

          withContext(Dispatchers.EDT) {
            @Suppress("UseJBColor")
            val host = JPanel().apply { background = Color(0x123456) }
            engine.attach(host)
            assertEquals(host.background.rgb, engine.component.background.rgb)

            @Suppress("UseJBColor")
            val changed = Color(0x654321)
            host.background = changed
            assertEquals(host.background.rgb, engine.component.background.rgb)
            engine.detach()
          }
        }
        finally {
          engine.close()
        }
      }
      finally {
        scope.coroutineContext.job.cancelAndJoin()
      }
    }
  }

  /** `null` until the placeholder page has finished loading, so the evaluation is retried. */
  private suspend fun awaitPageBackground(engine: JcefWebViewEngine): String? {
    return withTimeoutOrNull(20.seconds) {
      var background: String? = null
      while (background == null) {
        background = engine.evaluateJavaScript("getComputedStyle(document.documentElement).backgroundColor")
        if (background == null) delay(100.milliseconds)
      }
      background
    }
  }

  private fun registerJcefWebViewEngineProviders(parentDisposable: Disposable) {
    val extensionArea = ApplicationManager.getApplication().extensionArea
    val shouldDeclareWebViewEngineProviderEp = !extensionArea.hasExtensionPoint(WebViewEngineProvider.EP_NAME)
    if (shouldDeclareWebViewEngineProviderEp) {
      extensionArea.registerExtensionPoint(
        WebViewEngineProvider.EP_NAME.name,
        WebViewEngineProvider::class.java.name,
        ExtensionPoint.Kind.INTERFACE,
        true,
      )
    }

    val providersDisposable = Disposer.newDisposable("JCEF WebView engine providers")
    Disposer.register(parentDisposable) {
      Disposer.dispose(providersDisposable)
      if (shouldDeclareWebViewEngineProviderEp) {
        extensionArea.unregisterExtensionPoint(WebViewEngineProvider.EP_NAME.name)
      }
    }

    extensionArea.getExtensionPoint(WebViewEngineProvider.EP_NAME)
      .registerExtension(JcefEngineProvider(), providersDisposable)
  }

  private suspend fun withProgrammaticJcefOverride(action: suspend () -> Unit) {
    val webViewEngine = Registry.get(WEBVIEW_ENGINE_REGISTRY_KEY)
    val headless = Registry.get(JCEF_HEADLESS_REGISTRY_KEY)
    val osr = Registry.get(JCEF_OFFSCREEN_REGISTRY_KEY)
    val testMode = Registry.get(JCEF_TEST_MODE_REGISTRY_KEY)
    try {
      webViewEngine.setValue(JCEF_ENGINE_VALUE)
      headless.setValue(true)
      osr.setValue(true)
      testMode.setValue(true)
      action()
    }
    finally {
      webViewEngine.resetToDefault()
      headless.resetToDefault()
      osr.resetToDefault()
      testMode.resetToDefault()
    }
  }

  private companion object {
    private const val WEBVIEW_ENGINE_REGISTRY_KEY = "io.github.nerzhulart.webview.engine"
    private const val JCEF_ENGINE_VALUE = "JCEF"
    private const val JCEF_HEADLESS_REGISTRY_KEY = "ide.browser.jcef.headless.enabled"
    private const val JCEF_OFFSCREEN_REGISTRY_KEY = "ide.browser.jcef.osr.enabled"
    private const val JCEF_TEST_MODE_REGISTRY_KEY = "ide.browser.jcef.testMode.enabled"

    @JvmStatic
    @AfterAll
    fun shutdownJcefApp(): Unit = runBlocking {
      val cefApp = CefApp.getInstanceIfAny() ?: return@runBlocking
      cefApp.dispose()
      withTimeoutOrNull(2.seconds) {
        while (CefApp.getState() != CefApp.CefAppState.TERMINATED) {
          delay(50.milliseconds)
        }
      }
    }
  }
}
