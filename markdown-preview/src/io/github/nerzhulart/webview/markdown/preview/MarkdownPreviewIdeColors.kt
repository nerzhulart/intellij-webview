// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview

import com.intellij.diff.util.TextDiffType
import com.intellij.util.ui.JBUI
import io.github.nerzhulart.webview.api.WebViewApiId
import io.github.nerzhulart.webview.api.WebViewCallable
import kotlinx.serialization.Serializable
import java.awt.Color

/**
 * Delivers the IDE colors the preview mirrors: the `Diff & Merge` colors of the color scheme for the changed blocks and
 * the `Link.*` colors of the UI theme for the hyperlinks. It shares the `markdown.preview` namespace with
 * [MarkdownPreviewPageApi], so the payload types stay internal to this module.
 */
internal interface MarkdownPreviewIdeColorsPageApi : WebViewCallable {
  fun setChangeColors(params: MarkdownChangeColorsParams)

  fun setLinkColors(params: MarkdownLinkColorsParams)

  companion object {
    val ID: WebViewApiId<MarkdownPreviewIdeColorsPageApi> = WebViewApiId.of("markdown.preview")
  }
}

@Serializable
internal data class MarkdownChangeColorsParams(
  val added: MarkdownChangeColorParams,
  val modified: MarkdownChangeColorParams,
  val removed: MarkdownChangeColorParams,
)

/**
 * [background] fills a changed block, [stripe] paints its scrollbar marker. [ignoredBackground] is
 * the pale color that fills a block with inline changes, while [background] fills its changed words, as in the diff
 * editor.
 */
@Serializable
internal data class MarkdownChangeColorParams(
  val background: String,
  val stripe: String,
  val ignoredBackground: String,
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
    ignoredBackground = getIgnoredColor(null).toCssHex(),
  )
}

/**
 * [foreground] paints a link, [hover] a link under the mouse, [pressed] a link being clicked.
 */
@Serializable
internal data class MarkdownLinkColorsParams(
  val foreground: String,
  val hover: String,
  val pressed: String,
)

/**
 * Reads the `Link.activeForeground`, `Link.hoverForeground` and `Link.pressedForeground` colors of the current UI theme.
 */
internal fun currentMarkdownLinkColors(): MarkdownLinkColorsParams {
  return MarkdownLinkColorsParams(
    foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED.toCssHex(),
    hover = JBUI.CurrentTheme.Link.Foreground.HOVERED.toCssHex(),
    pressed = JBUI.CurrentTheme.Link.Foreground.PRESSED.toCssHex(),
  )
}

internal fun Color.toCssHex(): String {
  val components = if (alpha < 255) listOf(red, green, blue, alpha) else listOf(red, green, blue)
  return components.joinToString(separator = "", prefix = "#") { it.toString(16).padStart(2, '0').uppercase() }
}
