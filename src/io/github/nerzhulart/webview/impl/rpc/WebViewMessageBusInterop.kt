// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.rpc

import io.github.nerzhulart.webview.api.WebViewCallable
import io.github.nerzhulart.webview.api.WebViewImplementable
import io.github.nerzhulart.webview.api.WebViewApiId
import io.github.nerzhulart.webview.api.WebViewInterop
import io.github.nerzhulart.webview.api.WebViewMessageRegistration

internal class WebViewMessageBusInterop(
  override val messageBus: WebViewMessageBusImpl,
) : WebViewInterop {
  override fun <T : WebViewImplementable> implement(id: WebViewApiId<T>, implementation: T): WebViewMessageRegistration {
    return messageBus.bindApiImplementation(id.apiClass, implementation, id.namespace)
  }

  override fun <T : WebViewCallable> callable(id: WebViewApiId<T>): T {
    return messageBus.createCallableProxy(id.apiClass, id.namespace)
  }
}
