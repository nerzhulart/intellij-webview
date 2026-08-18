package io.github.nerzhulart.webview.impl.rpc

import io.github.nerzhulart.webview.api.WebViewMessageBus
import io.github.nerzhulart.webview.impl.engine.WebViewRuntimeInfo
import io.github.nerzhulart.webview.impl.engine.WebViewRuntimeInfoPayload
import io.github.nerzhulart.webview.impl.engine.WebViewRuntimeNotifications
import io.github.nerzhulart.webview.impl.engine.isEngineOverlayEnabled

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
