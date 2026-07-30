// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.engine

import io.github.nerzhulart.webview.impl.linux.LinuxWebKitEngineProvider
import io.github.nerzhulart.webview.impl.mac.MacWkWebViewEngineProvider
import io.github.nerzhulart.webview.impl.windows.WindowsWebView2EngineProvider

internal fun defaultWebViewEngineProviders(): List<WebViewEngineProvider> {
  return listOf(
    MacWkWebViewEngineProvider(),
    WindowsWebView2EngineProvider(),
    LinuxWebKitEngineProvider(),
  )
}
