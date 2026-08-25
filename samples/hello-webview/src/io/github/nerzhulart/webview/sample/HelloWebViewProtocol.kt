package io.github.nerzhulart.webview.sample

import io.github.nerzhulart.webview.api.WebViewApiId
import io.github.nerzhulart.webview.api.WebViewImplementable

interface HelloHostApi : WebViewImplementable {
  companion object {
    val ID: WebViewApiId<HelloHostApi> = WebViewApiId.of("hello.host")
  }

  suspend fun buttonClicked()
}