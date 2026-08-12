// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl

import org.jetbrains.annotations.ApiStatus

@ApiStatus.Experimental
fun interface WebViewJsMessageReceiver {
  fun transferFromJs(rawJson: String)
}