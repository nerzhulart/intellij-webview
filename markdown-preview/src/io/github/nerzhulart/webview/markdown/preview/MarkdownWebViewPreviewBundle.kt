// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val PATH_TO_BUNDLE = "messages.MarkdownWebViewPreviewBundle"

internal object MarkdownWebViewPreviewBundle : DynamicBundle(PATH_TO_BUNDLE) {
  @Nls
  @JvmStatic
  fun message(@PropertyKey(resourceBundle = PATH_TO_BUNDLE) key: String, vararg params: Any): String = getMessage(key, *params)
}
