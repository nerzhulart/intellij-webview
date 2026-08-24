// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.engine

import com.intellij.openapi.extensions.ExtensionPointName
import io.github.nerzhulart.webview.impl.WebViewApplicationModeScripts
import kotlinx.coroutines.CoroutineScope
import org.intellij.lang.annotations.Language
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Experimental
interface WebViewEngineProvider {
  val id: WebViewEngineId
  val displayName: String
  val capabilities: WebViewEngineCapabilities

  fun selectionPriority(preference: WebViewEngineKind): Int?

  suspend fun availability(): WebViewEngineAvailability

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

@ApiStatus.Experimental
data class WebViewEngineCreationOptions(
  val debugName: String?,
  val documentStartScripts: List<WebViewScript> = listOf(WebViewApplicationModeScripts.DOCUMENT_START_SCRIPT),
) {
  fun withDocumentStartScript(script: WebViewScript): WebViewEngineCreationOptions {
    return copy(documentStartScripts = documentStartScripts + script)
  }
}


@ApiStatus.Experimental
data class WebViewScript(
  @Language("JavaScript")
  val script: String,
)
