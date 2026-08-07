// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.engine

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.registry.RegistryManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import io.github.nerzhulart.webview.api.WebViewAssetPath
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.api.WebViewInterop
import io.github.nerzhulart.webview.api.WebViewMessageRegistration
import io.github.nerzhulart.webview.api.WebViewNotification
import io.github.nerzhulart.webview.impl.CONSOLE_LOG_CATEGORY
import io.github.nerzhulart.webview.impl.SwingWebViewHostPanel
import io.github.nerzhulart.webview.impl.WebViewApplicationModeScripts
import io.github.nerzhulart.webview.impl.WebViewConsoleCapture
import io.github.nerzhulart.webview.impl.rpc.WebViewMessageBusImpl
import io.github.nerzhulart.webview.impl.traceWebViewPerf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.intellij.lang.annotations.Language
import org.jetbrains.annotations.ApiStatus
import java.nio.file.Path
import java.util.MissingResourceException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JComponent

private val LOG = logger<WebViewEngineProvider>()

@ApiStatus.Internal
interface WebViewEngineProvider {
  val id: WebViewEngineId
  val displayName: String
  val capabilities: WebViewEngineCapabilities

  fun selectionPriority(preference: WebViewEngineKind): Int?

  suspend fun availability(): WebViewEngineAvailability = availabilityBlocking()

  fun availabilityBlocking(): WebViewEngineAvailability

  suspend fun createWebView(
    webViewScope: CoroutineScope,
    options: WebViewEngineCreationOptions,
  ): CreatedWebView {
    return LOG.traceWebViewPerf(
      "webview.provider.createWebView.total",
      "provider=$id, debugName=${options.debugName.orEmpty()}",
    ) {
      val provider = this
      val consoleCapture = WebViewConsoleCapture(options.consoleLogCategory)
      val engine = LOG.traceWebViewPerf(
        "webview.provider.engine.create",
        "provider=$id, debugName=${options.debugName.orEmpty()}",
      ) {
        createEngine(webViewScope, options.withDocumentStartScript(WebViewConsoleCapture.DOCUMENT_START_SCRIPT))
      }
      val runtimeInfoValue = runtimeInfo(engine)
      val busSetup = LOG.traceWebViewPerf(
        "webview.provider.bus.setup",
        "provider=$id, debugName=${options.debugName.orEmpty()}",
      ) {
        val bus = WebViewMessageBusImpl(webViewScope, engine)
        bus.registerRuntimeInfoHandler(runtimeInfoValue)
        val consoleRegistration = consoleCapture.register(bus)
        val themeRegistration = bus.interop.registerThemeHandler()
        engine.connectMessageBus { rawJson -> bus.transferFromJs(rawJson) }
        WebViewBusSetup(bus, consoleRegistration, themeRegistration)
      }
      val bus = busSetup.bus
      val consoleRegistration = busSetup.consoleRegistration
      val themeRegistration = busSetup.themeRegistration
      val closed = AtomicBoolean(false)
      val closeOnScopeCompletion = AtomicReference<DisposableHandle?>(null)
      val createdWebView = object : WebView, CreatedWebView {
        private var hostComponent: SwingWebViewHostPanel? = null
        private var focusRegistration: WebViewMessageRegistration? = null
        private val firstAssetLoadLogged = AtomicBoolean(false)

        override val webView: WebView
          get() = this

        override val runtimeInfo: WebViewRuntimeInfo = runtimeInfoValue
        override val interop: WebViewInterop = bus.interop
        override val isHeavyweight: Boolean = engine.isHeavyweight

        override fun createHostComponent(): JComponent {
          hostComponent?.let { return it }

          val host = LOG.traceWebViewPerf(
            "webview.provider.hostComponent.create",
            "provider=${provider.id}, debugName=${options.debugName.orEmpty()}",
          ) {
            SwingWebViewHostPanel(
              webViewScope,
              engine,
              bus.interop.createWebViewFocusEntrySink(),
            )
          }
          focusRegistration = bus.interop.registerWebViewFocusExitHandler(host)
          hostComponent = host
          return host
        }

        override suspend fun loadFile(file: VirtualFile) {
          consoleCapture.setViewId(null)
          val path = file.toNioPathOrNull() ?: error("WebView can load only local files: ${file.presentableUrl}")
          engine.loadFile(path)
        }

        override suspend fun loadAsset(root: WebViewAssetRoot, entry: WebViewAssetPath, query: String?) {
          if (firstAssetLoadLogged.compareAndSet(false, true)) {
            LOG.traceWebViewPerf(
              "webview.provider.firstLoadAsset.enqueue",
              "provider=${provider.id}, viewId=${root.viewId}, entry=$entry, debugName=${options.debugName.orEmpty()}",
            ) {
              consoleCapture.setViewId(root.viewId)
              engine.loadAsset(root, entry, query.withWebViewTheme())
            }
          }
          else {
            consoleCapture.setViewId(root.viewId)
            engine.loadAsset(root, entry, query.withWebViewTheme())
          }
        }

        override suspend fun loadHtml(html: String) {
          consoleCapture.setViewId(null)
          engine.loadHtml(html)
        }

        override suspend fun evaluateJavaScript(script: String): WebViewScriptResult {
          return WebViewScriptResult(engine.evaluateJavaScript(script))
        }

        override suspend fun close() {
          if (!closed.compareAndSet(false, true)) return
          closeOnScopeCompletion.getAndSet(null)?.dispose()
          focusRegistration?.close()
          focusRegistration = null
          consoleRegistration.close()
          themeRegistration.close()
          bus.close()
          engine.close()
        }
      }
      closeOnScopeCompletion.set(webViewScope.coroutineContext.job.invokeOnCompletion {
        runCatching {
          runBlocking(NonCancellable) {
            createdWebView.close()
          }
        }.onFailure {
          LOG.warn("Failed to close WebView after its scope completed", it)
        }
      })
      createdWebView
    }
  }

  fun runtimeInfo(engine: WebViewEngine): WebViewRuntimeInfo {
    return WebViewRuntimeInfo(id, capabilities, displayName)
  }

  fun createEngine(
    scope: CoroutineScope,
    options: WebViewEngineCreationOptions,
  ): WebViewEngine

  interface CreatedWebView {
    val webView: WebView

    val isHeavyweight: Boolean

    fun createHostComponent(): JComponent
  }

  companion object {
    @JvmField
    val EP_NAME: ExtensionPointName<WebViewEngineProvider> =
      ExtensionPointName.create("io.github.nerzhulart.webview.webViewEngineProvider")
  }
}

private data class WebViewBusSetup(
  val bus: WebViewMessageBusImpl,
  val consoleRegistration: WebViewMessageRegistration,
  val themeRegistration: WebViewMessageRegistration,
)

@ApiStatus.Internal
data class WebViewEngineCreationOptions(
  val strictPreference: Boolean,
  val jcefNativeBundlePath: Path?,
  val debugName: String?,
  val consoleLogCategory: String = CONSOLE_LOG_CATEGORY,
  val documentStartScripts: List<WebViewScript> = listOf(WebViewApplicationModeScripts.DOCUMENT_START_SCRIPT),
) {
  fun withDocumentStartScript(script: WebViewScript): WebViewEngineCreationOptions {
    return copy(documentStartScripts = documentStartScripts + script)
  }
}

@ApiStatus.Internal
data class WebViewScript(
  @Language("JavaScript")
  val script: String,
)

private fun WebViewMessageBusImpl.registerRuntimeInfoHandler(runtimeInfo: WebViewRuntimeInfo) {
  registerNotificationHandler(WebViewRuntimeNotifications.runtimeInfoRequest) { _, _ ->
    notify(
      WebViewRuntimeNotifications.runtimeInfo,
      WebViewRuntimeInfoPayload(
        displayName = runtimeInfo.displayName,
        overlayVisible = isEngineOverlayEnabled(),
      ),
    )
  }
}

private fun isEngineOverlayEnabled(): Boolean {
  return try {
    RegistryManager.getInstance().get(WEBVIEW_ENGINE_OVERLAY_REGISTRY_KEY).asBoolean()
  }
  catch (_: MissingResourceException) {
    false
  }
}

private class WebViewRuntimeNotification<Params : Any>(
  override val method: String,
  override val paramsSerializer: KSerializer<Params>,
) : WebViewNotification<Params>

@Serializable
private object EmptyWebViewRuntimePayload

@Serializable
private data class WebViewRuntimeInfoPayload(
  val displayName: String,
  val overlayVisible: Boolean,
)

private object WebViewRuntimeNotifications {
  val runtimeInfoRequest = WebViewRuntimeNotification("$/webview/runtimeInfoRequest", EmptyWebViewRuntimePayload.serializer())
  val runtimeInfo = WebViewRuntimeNotification("$/webview/runtimeInfo", WebViewRuntimeInfoPayload.serializer())
}

private const val WEBVIEW_ENGINE_OVERLAY_REGISTRY_KEY = "io.github.nerzhulart.webview.debug.engine.overlay"
