// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.windows

import com.intellij.openapi.util.SystemInfo
import io.github.nerzhulart.webview.impl.engine.WebViewEngineAvailability
import io.github.nerzhulart.webview.impl.engine.WebViewEngineCapabilities
import io.github.nerzhulart.webview.impl.engine.WebViewEngineId
import io.github.nerzhulart.webview.impl.engine.WebViewEngineKind
import io.github.nerzhulart.webview.impl.NativeBridgeLibraryAvailability
import io.github.nerzhulart.webview.impl.engine.WebViewEngine
import io.github.nerzhulart.webview.impl.engine.WebViewEngineCreationOptions
import io.github.nerzhulart.webview.impl.engine.WebViewEngineProvider
import io.github.nerzhulart.webview.impl.host.NativeWebViewHostPeer
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
internal class WindowsWebView2EngineProvider : WebViewEngineProvider {
  override val id: WebViewEngineId = WebViewEngineId.SYSTEM_WINDOWS
  override val displayName: String = "WebView2"
  override val capabilities = WebViewEngineCapabilities(assetServing = true, messagePassing = true, swingEmbedding = true, interactiveInput = true)

  override fun selectionPriority(preference: WebViewEngineKind): Int? {
    return when (preference) {
      WebViewEngineKind.System -> PRIMARY_PRIORITY
      WebViewEngineKind.Jcef -> null
    }
  }

  override fun availabilityBlocking(): WebViewEngineAvailability {
    if (!SystemInfo.isWindows) return WebViewEngineAvailability.Unavailable("Windows is required")
    return when (val availability = winWebView2BridgeLibrary.availability()) {
      is NativeBridgeLibraryAvailability.Available -> WebViewEngineAvailability.Available
      is NativeBridgeLibraryAvailability.Missing -> WebViewEngineAvailability.Unavailable(availability.message)
    }
  }

  override fun createEngine(scope: CoroutineScope, options: WebViewEngineCreationOptions): WebViewEngine {
    check(SystemInfo.isWindows) { "System WebView is supported only on Windows" }
    return createWinWebViewEngine(scope, options.debugName, options.documentStartScripts)
  }

  override fun createNativeHostPeer(scope: CoroutineScope, engine: WebViewEngine): NativeWebViewHostPeer {
    return WinNativeWebViewHostPeer(engine as WinWebViewEngine)
  }
}

private const val PRIMARY_PRIORITY = 10
