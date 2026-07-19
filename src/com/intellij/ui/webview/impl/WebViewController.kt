// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.webview.impl

import com.intellij.ui.webview.api.WebViewAssetPath
import com.intellij.ui.webview.api.WebViewAssetRoot
import com.intellij.ui.webview.impl.engine.WebViewFocusDirection
import org.intellij.lang.annotations.Language
import org.jetbrains.annotations.ApiStatus
import java.awt.Component
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.nio.file.Path

@ApiStatus.Internal
enum class WebViewEditShortcutPolicy {
  NONE,
  BYPASS_IDE_DISPATCHER,
  HANDLE_IN_NATIVE_PEER,
}

@ApiStatus.Internal
interface WebViewController {
  val component: Component
  val editShortcutPolicy: WebViewEditShortcutPolicy

  suspend fun loadFile(file: Path)
  suspend fun loadAsset(root: WebViewAssetRoot, entry: WebViewAssetPath, query: String?)
  suspend fun loadHtml(@Language("HTML") html: String, baseFile: Path?)
  suspend fun evaluateJavaScript(@Language("JavaScript") script: String): String?
  suspend fun close()
  suspend fun transferToJs(rawJson: String)
  fun connectMessageBus(receiver: WebViewJsMessageReceiver)
  fun applyLayout(params: WebViewHostLayoutParams)
  fun requestWebViewFocus() {}
  fun swingFocusMovedOutside(event: WebViewSwingFocusExit)
  fun handleEditShortcut(event: KeyEvent, command: WebViewEditCommand): Boolean
}

@ApiStatus.Internal
fun interface WebViewJsMessageReceiver {
  fun transferFromJs(rawJson: String)
}

@ApiStatus.Internal
fun interface WebViewHostEventSink {
  fun handle(event: WebViewHostEvent): Boolean
}

@ApiStatus.Internal
data class WebViewHostLayoutParams(
  val displayable: Boolean,
  val showing: Boolean,
  val boundsInWindow: Rectangle,
  val clippedBoundsInWindow: Rectangle,
  val scale: Double,
)

@ApiStatus.Internal
data class WebViewSwingFocusExit(
  val newOwner: Component?,
  val sameWindow: Boolean,
)

@ApiStatus.Internal
sealed interface WebViewHostEvent {
  data object NativeFocusGained : WebViewHostEvent
  data class MoveFocusRequested(val direction: WebViewFocusDirection) : WebViewHostEvent
}
