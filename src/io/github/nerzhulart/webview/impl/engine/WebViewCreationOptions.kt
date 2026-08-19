// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.engine

import io.github.nerzhulart.webview.impl.CONSOLE_LOG_CATEGORY
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
data class WebViewCreationOptions(
  val engineKind: WebViewEngineKind = WebViewEngineKind.System,
  val requirements: WebViewEngineRequirements = WebViewEngineRequirements(),
  val debugName: String? = null,
  val consoleLogCategory: String = CONSOLE_LOG_CATEGORY,
)

@ApiStatus.Internal
data class WebViewRuntimeInfo(
  val engineId: WebViewEngineId,
  val capabilities: WebViewEngineCapabilities,
  val displayName: String,
)

@ApiStatus.Internal
data class WebViewEngineRequirements(
  val assetServing: Boolean = false,
  val messagePassing: Boolean = false,
  val interactiveInput: Boolean = false,
)

@ApiStatus.Experimental
data class WebViewEngineCapabilities(
  val assetServing: Boolean,
  val messagePassing: Boolean,
  val interactiveInput: Boolean,
) {
  // TODO: unused?
  fun satisfies(requirements: WebViewEngineRequirements): Boolean {
    return (!requirements.assetServing || assetServing) &&
           (!requirements.messagePassing || messagePassing) &&
           (!requirements.interactiveInput || interactiveInput)
  }

  internal fun missingRequirements(requirements: WebViewEngineRequirements): List<String> {
    return buildList {
      if (requirements.assetServing && !assetServing) add("assetServing")
      if (requirements.messagePassing && !messagePassing) add("messagePassing")
      if (requirements.interactiveInput && !interactiveInput) add("interactiveInput")
    }
  }
}

@ApiStatus.Experimental
@JvmInline
value class WebViewEngineId(val value: String) {
  override fun toString(): String = value

  companion object {
    val SYSTEM_MACOS: WebViewEngineId = WebViewEngineId("SYSTEM_MACOS")
    val SYSTEM_WINDOWS: WebViewEngineId = WebViewEngineId("SYSTEM_WINDOWS")
    val SYSTEM_LINUX: WebViewEngineId = WebViewEngineId("SYSTEM_LINUX")
    val JCEF: WebViewEngineId = WebViewEngineId("JCEF")
  }
}

@ApiStatus.Experimental
sealed class WebViewEngineAvailability private constructor() {
  data object Available : WebViewEngineAvailability()
  data class Unavailable(val reason: String) : WebViewEngineAvailability()
}
