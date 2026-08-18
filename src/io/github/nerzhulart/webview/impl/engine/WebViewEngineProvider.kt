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
import io.github.nerzhulart.webview.api.WebViewNotification
import io.github.nerzhulart.webview.impl.CONSOLE_LOG_CATEGORY
import io.github.nerzhulart.webview.impl.SwingWebViewHostPanel
import io.github.nerzhulart.webview.impl.WebViewApplicationModeScripts
import io.github.nerzhulart.webview.impl.WebViewConsoleCapture
import io.github.nerzhulart.webview.impl.registerConsole
import io.github.nerzhulart.webview.impl.rpc.WebViewMessageBusImpl
import io.github.nerzhulart.webview.impl.rpc.registerRuntimeInfoHandler
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

@ApiStatus.Experimental
interface WebViewEngineProvider {
  val id: WebViewEngineId
  val displayName: String
  val capabilities: WebViewEngineCapabilities

  fun selectionPriority(preference: WebViewEngineKind): Int?

  suspend fun availability(): WebViewEngineAvailability

  suspend fun createWebView(
    webViewScope: CoroutineScope,
    options: WebViewEngineCreationOptions,
  ): WebView {
    return LOG.traceWebViewPerf(
      "webview.provider.createWebView.total",
      "provider=$id, debugName=${options.debugName.orEmpty()}",
    ) {

      val engine = LOG.traceWebViewPerf("webview.provider.engine.create", "provider=$id, debugName=${options.debugName.orEmpty()}",
      ) {
        createEngine(webViewScope, options.withDocumentStartScript(WebViewConsoleCapture.DOCUMENT_START_SCRIPT))
      }
      val runtimeInfo = WebViewRuntimeInfo(id, capabilities, displayName)

      val bus = WebViewMessageBusImpl(webViewScope, engine)

      // TODO: extract some createMessageBus method
      val consoleCapture = bus.registerConsole(options.consoleLogCategory)
      bus.registerRuntimeInfoHandler(runtimeInfo)
      bus.interop.registerThemeHandler()

      return@traceWebViewPerf WebViewImpl(engine, webViewScope, runtimeInfo, bus, consoleCapture, options.debugName)
    }
  }

  fun createEngine(
    scope: CoroutineScope,
    options: WebViewEngineCreationOptions,
  ): WebViewEngine


  companion object {
    @JvmField
    val EP_NAME: ExtensionPointName<WebViewEngineProvider> =
      ExtensionPointName.create("io.github.nerzhulart.webview.webViewEngineProvider")
  }
}

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

class WebViewImpl internal constructor(
  private val engine: WebViewEngine,
  private val webViewScope: CoroutineScope,
  override val runtimeInfo: WebViewRuntimeInfo,
  private val bus: WebViewMessageBusImpl,
  private val consoleCapture: WebViewConsoleCapture,
  private val debugName: String?,
) : WebView {
  private var hostComponent: SwingWebViewHostPanel? = null
  private val firstAssetLoadLogged = AtomicBoolean(false)

  private val closed = AtomicBoolean(false)
  private val closeOnScopeCompletion = AtomicReference<DisposableHandle?>(null)

  override val interop: WebViewInterop = bus.interop
  override val isHeavyweight: Boolean = engine.isHeavyweight

  init {
    closeOnScopeCompletion.set(webViewScope.coroutineContext.job.invokeOnCompletion {
      runCatching {
        // TODO it's complete bullshit to call close in runBlocking. Need to come up how to make it in the coroutine way
        runBlocking(NonCancellable) {
          close()
        }
      }.onFailure {
        LOG.warn("Failed to close WebView after its scope completed", it)
      }
    })
  }
  override fun createHostComponent(): JComponent {
    hostComponent?.let { return it }

    val host = LOG.traceWebViewPerf(
      "webview.provider.hostComponent.create",
      "provider=${runtimeInfo.engineId}, debugName=${debugName}",
    ) {
      SwingWebViewHostPanel(
        webViewScope,
        engine,
        bus.interop.createWebViewFocusEntrySink(),
      )
    }
    bus.interop.registerWebViewFocusExitHandler(host)
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
        "provider=${runtimeInfo.engineId}, viewId=${root.viewId}, entry=$entry, debugName=${debugName}",
      ) {
        consoleCapture.setViewId(root.viewId)
        engine.loadAsset(root, entry, query.withWebViewTheme())
      }
    } else {
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
    // here we disable disposing on completion registered on init block
    closeOnScopeCompletion.getAndSet(null)?.dispose()
    bus.close()
    engine.close()
  }
}


@ApiStatus.Experimental
data class WebViewScript(
  @Language("JavaScript")
  val script: String,
)


internal fun isEngineOverlayEnabled(): Boolean {
  return try {
    RegistryManager.getInstance().get(WEBVIEW_ENGINE_OVERLAY_REGISTRY_KEY).asBoolean()
  }
  catch (_: MissingResourceException) {
    false
  }
}

class WebViewRuntimeNotification<Params : Any>(
  override val method: String,
  override val paramsSerializer: KSerializer<Params>,
) : WebViewNotification<Params>

@Serializable
internal object EmptyWebViewRuntimePayload

@Serializable
internal data class WebViewRuntimeInfoPayload(
  val displayName: String,
  val overlayVisible: Boolean,
)

internal object WebViewRuntimeNotifications {
  val runtimeInfoRequest = WebViewRuntimeNotification("$/webview/runtimeInfoRequest", EmptyWebViewRuntimePayload.serializer())
  val runtimeInfo = WebViewRuntimeNotification("$/webview/runtimeInfo", WebViewRuntimeInfoPayload.serializer())
}

private const val WEBVIEW_ENGINE_OVERLAY_REGISTRY_KEY = "io.github.nerzhulart.webview.debug.engine.overlay"
