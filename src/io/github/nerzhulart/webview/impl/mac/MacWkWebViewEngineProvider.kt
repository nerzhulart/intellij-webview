// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.mac

import com.intellij.openapi.util.SystemInfo
import io.github.nerzhulart.webview.impl.engine.WebViewEngineAvailability
import io.github.nerzhulart.webview.impl.engine.WebViewEngineCapabilities
import io.github.nerzhulart.webview.impl.engine.WebViewEngineId
import io.github.nerzhulart.webview.impl.engine.WebViewEngineKind
import io.github.nerzhulart.webview.impl.WebViewEngineBridge
import io.github.nerzhulart.webview.impl.engine.WebViewEngineCreationOptions
import io.github.nerzhulart.webview.impl.engine.WebViewEngineProvider
import io.github.nerzhulart.webview.impl.host.NativeWebViewHostPeer
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
internal class MacWkWebViewEngineProvider : WebViewEngineProvider {
  override val id: WebViewEngineId = WebViewEngineId.SYSTEM_MACOS
  override val displayName: String = "WebKit"
  override val capabilities = WebViewEngineCapabilities(assetServing = true, messagePassing = true, swingEmbedding = true, interactiveInput = true)

  override fun selectionPriority(preference: WebViewEngineKind): Int? {
    return when (preference) {
      WebViewEngineKind.System -> PRIMARY_PRIORITY
      WebViewEngineKind.Jcef -> null
    }
  }

  override fun availabilityBlocking(): WebViewEngineAvailability {
    return if (SystemInfo.isMac) WebViewEngineAvailability.Available else WebViewEngineAvailability.Unavailable("macOS is required")
  }

  override fun createEngine(scope: CoroutineScope, options: WebViewEngineCreationOptions): WebViewEngineBridge {
    check(SystemInfo.isMac) { "System WebView is supported only on macOS" }
    val engine = createMacWebViewEngine(scope, options.documentStartScripts)
    engine.initialize()
    return engine
  }

  override fun createNativeHostPeer(scope: CoroutineScope, engine: WebViewEngineBridge): NativeWebViewHostPeer {
    return MacNativeWebViewHostPeer(scope, engine as MacWebViewEngine)
  }
}

private const val PRIMARY_PRIORITY = 10
