// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview.diff

import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.util.Side

/**
 * Translates a source line of one diff side into the matching source line of the opposite side,
 * and enumerates the changed blocks for "next/previous change" navigation.
 *
 * All line numbers are 0-based, as in the platform diff fragments.
 */
internal object MarkdownDiffScrollSynchronizer {
  fun transferLine(fragments: List<LineFragment>, side: Side, line: Int): Int {
    var shift = 0
    for (fragment in fragments) {
      val start = startLine(fragment, side)
      val end = endLine(fragment, side)
      val oppositeStart = startLine(fragment, side.other())
      val oppositeEnd = endLine(fragment, side.other())

      if (line < start) break
      if (line < end) return (oppositeStart + (line - start)).coerceAtMost(maxOf(oppositeStart, oppositeEnd - 1))

      shift += (oppositeEnd - oppositeStart) - (end - start)
    }
    return (line + shift).coerceAtLeast(0)
  }

  fun changeStartLine(fragment: LineFragment, side: Side): Int = startLine(fragment, side)

  private fun startLine(fragment: LineFragment, side: Side): Int {
    return if (side.isLeft) fragment.startLine1 else fragment.startLine2
  }

  private fun endLine(fragment: LineFragment, side: Side): Int {
    return if (side.isLeft) fragment.endLine1 else fragment.endLine2
  }
}
