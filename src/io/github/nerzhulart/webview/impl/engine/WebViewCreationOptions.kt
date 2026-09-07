// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.engine

import io.github.nerzhulart.webview.impl.CONSOLE_LOG_CATEGORY
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Experimental
data class WebViewCreationOptions(
  val engineKind: WebViewEngineKind = WebViewEngineKind.System,
  val requirements: WebViewEngineRequirements = WebViewEngineRequirements(),
  val debugName: String? = null,
  val consoleLogCategory: String = CONSOLE_LOG_CATEGORY,
  val features: WebViewFeatures = WebViewFeatures.APPLICATION,
)

@ApiStatus.Experimental
data class WebViewRuntimeInfo(
  val engineId: WebViewEngineId,
  val capabilities: WebViewEngineCapabilities,
  val displayName: String,
)

@ApiStatus.Experimental
data class WebViewEngineRequirements(
  val assetServing: Boolean = false,
  val messagePassing: Boolean = false,
  val interactiveInput: Boolean = false,
  val navigation: Boolean = false,
)

@ApiStatus.Experimental
data class WebViewEngineCapabilities(
  val assetServing: Boolean,
  val messagePassing: Boolean,
  val interactiveInput: Boolean,
  val navigation: Boolean = false,
) {
  // TODO: unused?
  fun satisfies(requirements: WebViewEngineRequirements): Boolean {
    return (!requirements.assetServing || assetServing) &&
           (!requirements.messagePassing || messagePassing) &&
           (!requirements.interactiveInput || interactiveInput) &&
           (!requirements.navigation || navigation)
  }

  internal fun missingRequirements(requirements: WebViewEngineRequirements): List<String> {
    return buildList {
      if (requirements.assetServing && !assetServing) add("assetServing")
      if (requirements.messagePassing && !messagePassing) add("messagePassing")
      if (requirements.interactiveInput && !interactiveInput) add("interactiveInput")
      if (requirements.navigation && !navigation) add("navigation")
    }
  }
}

/** Backend-specific options; unsupported native features are ignored. */
@ApiStatus.Experimental
data class WebViewFeatures(
  /** Injects the bundled-UI script that suppresses context menus and form autocomplete. */
  val applicationModeScript: Boolean,
  /** Enables Swing/page focus handover; requires the WebView SDK on the page. */
  val pageFocusInterop: Boolean,
  /** Native menus on Windows/JCEF. On macOS suppression requires [applicationModeScript]. */
  val contextMenu: Boolean,
  /** Native zoom controls on Windows and magnification on macOS. */
  val zoom: Boolean,
  /** Rubber-band scrolling on macOS. */
  val elasticScrolling: Boolean,
  /** History navigation gestures on macOS and Windows. */
  val swipeNavigation: Boolean,
  /** Browser keyboard shortcuts on Windows; may conflict with the IDE keymap. */
  val browserAcceleratorKeys: Boolean,
  /** Built-in network error pages on Windows. */
  val builtInErrorPage: Boolean,
  /** Credential storage on macOS, autofill and password saving on Windows. */
  val autofill: Boolean,
) {
  companion object {
    val APPLICATION: WebViewFeatures = WebViewFeatures(
      applicationModeScript = true,
      pageFocusInterop = true,
      contextMenu = false,
      zoom = false,
      elasticScrolling = false,
      swipeNavigation = false,
      browserAcceleratorKeys = false,
      builtInErrorPage = false,
      autofill = false,
    )
    val BROWSER: WebViewFeatures = APPLICATION.copy(
      applicationModeScript = false,
      pageFocusInterop = false,
      contextMenu = true,
      zoom = true,
      builtInErrorPage = true,
    )
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
