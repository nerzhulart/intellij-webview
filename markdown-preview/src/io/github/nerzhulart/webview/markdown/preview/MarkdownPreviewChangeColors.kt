// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview

import com.intellij.diff.util.TextDiffType
import io.github.nerzhulart.webview.api.WebViewApiId
import io.github.nerzhulart.webview.api.WebViewCallable
import kotlinx.serialization.Serializable
import java.awt.Color

/**
 * Delivers the `Diff & Merge` colors of the IDE color scheme to the changed blocks of the preview. It shares the
 * `markdown.preview` namespace with [MarkdownPreviewPageApi], so the payload types stay internal to this module.
 */
internal interface MarkdownPreviewChangeColorsPageApi : WebViewCallable {
  fun setChangeColors(params: MarkdownChangeColorsParams)

  companion object {
    val ID: WebViewApiId<MarkdownPreviewChangeColorsPageApi> = WebViewApiId.of("markdown.preview")
  }
}

@Serializable
internal data class MarkdownChangeColorsParams(
  val added: MarkdownChangeColorParams,
  val modified: MarkdownChangeColorParams,
  val removed: MarkdownChangeColorParams,
)

/**
 * [background] fills a changed block, [stripe] paints its left border and its scrollbar marker.
 */
@Serializable
internal data class MarkdownChangeColorParams(
  val background: String,
  val stripe: String,
)

/**
 * Reads the colors of the global scheme, which the diff viewer uses as well.
 */
internal fun currentMarkdownChangeColors(): MarkdownChangeColorsParams {
  return MarkdownChangeColorsParams(
    added = TextDiffType.INSERTED.toChangeColorParams(),
    modified = TextDiffType.MODIFIED.toChangeColorParams(),
    removed = TextDiffType.DELETED.toChangeColorParams(),
  )
}

private fun TextDiffType.toChangeColorParams(): MarkdownChangeColorParams {
  val background = getColor(null)
  return MarkdownChangeColorParams(
    background = background.toCssHex(),
    stripe = (getMarkerColor(null) ?: background).toCssHex(),
  )
}

internal fun Color.toCssHex(): String {
  val components = if (alpha < 255) listOf(red, green, blue, alpha) else listOf(red, green, blue)
  return components.joinToString(separator = "", prefix = "#") { it.toString(16).padStart(2, '0').uppercase() }
}
