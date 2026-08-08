// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.linux

import com.intellij.openapi.util.SystemInfo
import io.github.nerzhulart.webview.impl.engine.WebViewEngine
import io.github.nerzhulart.webview.impl.engine.WebViewEngineAvailability
import io.github.nerzhulart.webview.impl.engine.WebViewEngineCapabilities
import io.github.nerzhulart.webview.impl.engine.WebViewEngineId
import io.github.nerzhulart.webview.impl.engine.WebViewEngineKind
import io.github.nerzhulart.webview.impl.engine.WebViewEngineCreationOptions
import io.github.nerzhulart.webview.impl.engine.WebViewEngineProvider
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
internal class LinuxWebKitEngineProvider : WebViewEngineProvider {
  override val id: WebViewEngineId = WebViewEngineId.SYSTEM_LINUX
  override val displayName: String = "WebKit"
  override val capabilities = WebViewEngineCapabilities(assetServing = false, messagePassing = true, swingEmbedding = true, interactiveInput = false)

  override fun selectionPriority(preference: WebViewEngineKind): Int? {
    return when (preference) {
      WebViewEngineKind.System -> null
      WebViewEngineKind.Jcef -> null
    }
  }

  override suspend fun availability(): WebViewEngineAvailability {
    if (!SystemInfo.isLinux) return WebViewEngineAvailability.Unavailable("Linux is required")
    return WebViewEngineAvailability.Unavailable("Linux WebKitGTK WebView is disabled")
  }

  override fun createEngine(scope: CoroutineScope, options: WebViewEngineCreationOptions): WebViewEngine {
    error("Linux WebKitGTK WebView is disabled")
  }
}
