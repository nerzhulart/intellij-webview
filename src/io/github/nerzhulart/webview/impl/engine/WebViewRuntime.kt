// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.engine

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.util.registry.RegistryManager
import com.intellij.platform.util.coroutines.childScope
import com.intellij.util.concurrency.annotations.RequiresEdt
import io.github.nerzhulart.webview.api.WebViewAssetPath
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.api.WebViewPanel
import io.github.nerzhulart.webview.api.WebViewPanelOptions
import io.github.nerzhulart.webview.impl.WebViewConsoleCapture
import io.github.nerzhulart.webview.impl.registerConsole
import io.github.nerzhulart.webview.impl.rpc.WebViewMessageBusImpl
import io.github.nerzhulart.webview.impl.traceWebViewPerf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus
import java.util.MissingResourceException

private val LOG = logger<WebViewRuntime>()

@ApiStatus.Internal
@Service(Service.Level.APP)
class WebViewRuntime {
  private var providersOverride: List<WebViewEngineProvider>? = null

  internal var providers: List<WebViewEngineProvider>
    get() = providersOverride ?: (defaultWebViewEngineProviders() + WebViewEngineProvider.EP_NAME.extensionList)
    set(value) {
      providersOverride = value
    }

  @RequiresEdt
  suspend fun createWebView(
    scope: CoroutineScope,
    options: WebViewCreationOptions = WebViewCreationOptions(),
  ): WebView {
    val preference = resolveEnginePreference(options.engineKind)
    return LOG.traceWebViewPerf(
      "webview.runtime.createWebView.total",
      "preference=$preference, debugName=${options.debugName.orEmpty()}",
    ) {
      val provider = selectProvider(
        preference = preference,
        requirements = options.requirements,
      )
      LOG.traceWebViewPerf(
        "webview.runtime.createWebView.provider",
        "provider=${provider.id}, preference=$preference, debugName=${options.debugName.orEmpty()}",
      ) {
        createWebViewSession(
          parentScope = scope,
          provider = provider,
          options = WebViewEngineCreationOptions(
            debugName = options.debugName,
          ),
          consoleLogCategory = options.consoleLogCategory,
        )
      }
    }
  }

  @RequiresEdt
  internal suspend fun createWebViewPanel(
    scope: CoroutineScope,
    options: WebViewPanelOptions,
  ): WebViewPanel {
    return LOG.traceWebViewPerf(
      "webview.panel.create.total",
      "viewId=${options.assetRoot.viewId}, debugName=${options.debugName.orEmpty()}",
    ) {
      val preference = resolveEnginePreference(WebViewEngineKind.System)
      val requirements = WebViewEngineRequirements(
        assetServing = true,
        messagePassing = true,
      )
      val provider = selectProvider(
        preference = preference,
        requirements = requirements,
      )
      val webView = LOG.traceWebViewPerf(
        "webview.panel.session.create",
        panelDiagnosticDetails(options, preference, provider.id),
      ) {
        createWebViewSession(
          parentScope = scope,
          provider = provider,
          options = WebViewEngineCreationOptions(debugName = options.debugName),
          consoleLogCategory = options.consoleLogCategory,
          initialPage = InitialWebViewPage(options.assetRoot, options.indexPath, options.query),
        )
      }
      WebViewPanel(webView, options.assetRoot, options.indexPath, options.query)
    }
  }

  @RequiresEdt
  private suspend fun createWebViewSession(
    parentScope: CoroutineScope,
    provider: WebViewEngineProvider,
    options: WebViewEngineCreationOptions,
    consoleLogCategory: String,
    initialPage: InitialWebViewPage? = null,
  ): WebViewSession {
    parentScope.ensureActive()
    val debugName = options.debugName ?: provider.displayName
    val viewScope = parentScope.childScope("WebView: $debugName")
    val ready = CompletableDeferred<WebViewSession>(viewScope.coroutineContext.job)

    viewScope.launch(
      context = CoroutineName("WebView lifetime: $debugName"),
      start = CoroutineStart.UNDISPATCHED,
    ) {
      try {
        val engine = LOG.traceWebViewPerf(
          "webview.engine.create",
          "provider=${provider.id}, debugName=${options.debugName.orEmpty()}",
        ) {
          provider.createEngine(
            viewScope,
            options.withDocumentStartScript(WebViewConsoleCapture.DOCUMENT_START_SCRIPT),
          )
        }
        try {
          val bus = WebViewMessageBusImpl(viewScope, engine)
          try {
            val runtimeInfo = WebViewRuntimeInfo(provider.id, provider.capabilities, provider.displayName)
            val consoleCapture = bus.registerConsole(consoleLogCategory)
            bus.registerRuntimeInfoHandler(runtimeInfo)
            bus.interop.installThemeBridge(viewScope)

            val host = withContext(Dispatchers.EDT) {
              LOG.traceWebViewPerf(
                "webview.host.create",
                "provider=${provider.id}, debugName=${options.debugName.orEmpty()}",
              ) {
                engine.createHostComponent(viewScope, bus.interop.createWebViewFocusEntrySink())
              }
            }
            try {
              bus.interop.registerWebViewFocusExitHandler(host)
              val session = WebViewSession(
                engine = engine,
                consoleCapture = consoleCapture,
                component = host,
                interop = bus.interop,
                runtimeInfo = runtimeInfo,
                debugName = options.debugName,
              )
              ready.complete(session)
              awaitCancellation()
            }
            finally {
              withContext(NonCancellable + Dispatchers.EDT) {
                runCatching { host.close() }
                  .onFailure { LOG.warn("Failed to close WebView Swing host: $debugName", it) }
              }
            }
          }
          finally {
            runCatching { bus.close() }
              .onFailure { LOG.warn("Failed to close WebView message bus: $debugName", it) }
          }
        }
        finally {
          withContext(NonCancellable) {
            runCatching { engine.close() }
              .onFailure { LOG.warn("Failed to close WebView engine: $debugName", it) }
          }
        }
      }
      catch (failure: Throwable) {
        if (!ready.completeExceptionally(failure)) throw failure
      }
    }

    return try {
      val session = ready.await()
      viewScope.ensureActive()
      initialPage?.let { page ->
        session.loadAsset(page.root, page.entry, page.query)
      }
      session
    }
    catch (failure: Throwable) {
      viewScope.cancel("WebView creation failed", failure)
      withContext(NonCancellable) {
        viewScope.coroutineContext.job.join()
      }
      throw failure
    }
  }

  internal suspend fun selectProvider(
    preference: WebViewEngineKind,
    requirements: WebViewEngineRequirements = WebViewEngineRequirements(),
  ): WebViewEngineProvider {
    val diagnostics = ArrayList<String>()
    val candidates = candidateProviders(preference)
    logSelectionStart(preference, requirements, candidates)
    return LOG.traceWebViewPerf(
      "webview.provider.select",
      "preference=$preference, requirements=$requirements, candidates=${candidates.size}",
    ) {
      if (candidates.isEmpty()) {
        diagnostics += "no candidate providers"
      }
      for ((provider, priority) in candidates) {
        val missingRequirements = provider.capabilities.missingRequirements(requirements)
        if (missingRequirements.isNotEmpty()) {
          diagnostics += "${provider.id} rejected: missing ${missingRequirements.joinToString()}"
          logProviderRejected(preference, provider, priority, "missing ${missingRequirements.joinToString()}")
          continue
        }
        when (val availability = LOG.traceWebViewPerf(
          "webview.provider.availability",
          "provider=${provider.id}, preference=$preference, priority=$priority",
        ) { availability(provider) }) {
          WebViewEngineAvailability.Available -> {
            logProviderSelected(preference, provider, priority)
            return@traceWebViewPerf provider
          }
          is WebViewEngineAvailability.Unavailable -> {
            diagnostics += "${provider.id} unavailable: ${availability.reason}"
            logProviderRejected(preference, provider, priority, "unavailable: ${availability.reason}")
          }
        }
      }
      failSelection(preference, requirements, diagnostics)
    }
  }

  private fun panelDiagnosticDetails(
    options: WebViewPanelOptions,
    preference: WebViewEngineKind,
    providerId: WebViewEngineId,
  ): String {
    return "provider=$providerId, preference=$preference, viewId=${options.assetRoot.viewId}, " +
           "index=${options.indexPath}, debugName=${options.debugName.orEmpty()}"
  }

  private fun failSelection(
    preference: WebViewEngineKind,
    requirements: WebViewEngineRequirements,
    diagnostics: List<String>,
  ): Nothing {
    val message = buildSelectionFailureMessage(preference, requirements, diagnostics)
    LOG.warn("WebView engine applicability failed: $message")
    error(message)
  }

  private suspend fun availability(provider: WebViewEngineProvider): WebViewEngineAvailability {
    return try {
      provider.availability()
    }
    catch (e: LinkageError) {
      availabilityFailed(provider, e)
    }
  }


  private fun availabilityFailed(provider: WebViewEngineProvider, e: LinkageError): WebViewEngineAvailability.Unavailable {
    val reason = buildString {
      append("availability check failed: ")
      append(e.javaClass.name)
      e.message?.let { message ->
        append(": ")
        append(message)
      }
    }
    LOG.warn("WebView engine availability check failed for provider=${provider.id}", e)
    return WebViewEngineAvailability.Unavailable(reason)
  }

  private fun candidateProviders(preference: WebViewEngineKind): List<Pair<WebViewEngineProvider, Int>> {
    return providers.mapNotNull { provider ->
      val priority = provider.selectionPriority(preference) ?: return@mapNotNull null
      provider to priority
    }.sortedBy { it.second }
  }

  private fun logSelectionStart(
    preference: WebViewEngineKind,
    requirements: WebViewEngineRequirements,
    candidates: List<Pair<WebViewEngineProvider, Int>>,
  ) {
    val candidateText = candidates.joinToString { (provider, priority) ->
      "${provider.id}(priority=$priority, capabilities=${provider.capabilities})"
    }.ifEmpty { "none" }
    LOG.trace { "Checking WebView engine applicability: preference=$preference, requirements=$requirements, candidates=$candidateText" }
  }

  private fun logProviderRejected(
    preference: WebViewEngineKind,
    provider: WebViewEngineProvider,
    priority: Int,
    reason: String,
  ) {
    LOG.trace { "WebView engine applicability rejected: preference=$preference, provider=${provider.id}, priority=$priority, reason=$reason" }
  }

  private fun logProviderSelected(
    preference: WebViewEngineKind,
    provider: WebViewEngineProvider,
    priority: Int,
  ) {
    LOG.trace { "WebView engine applicability selected: preference=$preference, provider=${provider.id}, priority=$priority" }
  }

  private fun resolveEnginePreference(requestedPreference: WebViewEngineKind): WebViewEngineKind {
    return readRegistryEnginePreference() ?: requestedPreference
  }

  private fun buildSelectionFailureMessage(
    preference: WebViewEngineKind,
    requirements: WebViewEngineRequirements,
    diagnostics: List<String>,
  ): String {
    return buildString {
      append("No WebView engine satisfies preference=")
      append(preference)
      append(", requirements=")
      append(requirements)
      if (diagnostics.isNotEmpty()) {
        append(". Diagnostics: ")
        append(diagnostics.joinToString("; "))
      }
    }
  }

  private fun readRegistryEnginePreference(): WebViewEngineKind? {
    val value = readRegistryEngineOverrideValue()?.trim() ?: return null
    return when (value.uppercase()) {
      "", "SYSTEM" -> WebViewEngineKind.System
      "JCEF" -> WebViewEngineKind.Jcef
      else -> error("Unsupported $WEBVIEW_ENGINE_REGISTRY_KEY value '$value'. Expected SYSTEM or JCEF")
    }
  }

  private fun readRegistryEngineOverrideValue(): String? {
    return try {
      val registryValue = RegistryManager.getInstance().get(WEBVIEW_ENGINE_REGISTRY_KEY)
      registryValue.selectedOption ?: registryValue.asString()
    }
    catch (_: MissingResourceException) {
      null
    }
  }

  companion object {
    private const val WEBVIEW_ENGINE_REGISTRY_KEY = "io.github.nerzhulart.webview.engine"

    @JvmStatic
    fun getInstance(): WebViewRuntime = service()
  }
}

private data class InitialWebViewPage(
  val root: WebViewAssetRoot,
  val entry: WebViewAssetPath,
  val query: String?,
)
