// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.engine

import com.intellij.openapi.util.registry.RegistryManager
import io.github.nerzhulart.webview.api.WebViewMessageBus
import io.github.nerzhulart.webview.api.WebViewNotification
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.util.MissingResourceException

internal fun WebViewMessageBus.registerRuntimeInfoHandler(runtimeInfo: WebViewRuntimeInfo) {
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
