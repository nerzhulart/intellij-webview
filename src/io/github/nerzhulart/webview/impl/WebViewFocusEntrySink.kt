// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl

import io.github.nerzhulart.webview.impl.engine.WebViewFocusDirection
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
interface WebViewFocusEntrySink {
  fun enterWebViewFocus(direction: WebViewFocusDirection)

  fun leaveWebViewFocus()
}
