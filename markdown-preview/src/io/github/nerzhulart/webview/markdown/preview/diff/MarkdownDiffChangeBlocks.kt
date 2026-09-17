// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview.diff

import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.util.Side
import io.github.nerzhulart.webview.markdown.preview.MarkdownChangedBlockDescriptor
import io.github.nerzhulart.webview.markdown.preview.MarkdownChangedBlockKind

/**
 * Converts platform line fragments (0-based, end-exclusive line ranges) into preview change blocks
 * (1-based, end-inclusive line ranges, matching the `data-sourcepos` scheme).
 */
internal object MarkdownDiffChangeBlocks {
  fun blocks(fragments: List<LineFragment>, side: Side): List<MarkdownChangedBlockDescriptor> {
    return fragments.map { fragment -> block(fragment, side) }
  }

  fun leftBlocks(fragments: List<LineFragment>): List<MarkdownChangedBlockDescriptor> = blocks(fragments, Side.LEFT)

  fun rightBlocks(fragments: List<LineFragment>): List<MarkdownChangedBlockDescriptor> = blocks(fragments, Side.RIGHT)

  /**
   * Marks the whole document as changed, which is the case for a file that was added or deleted as a whole.
   */
  fun wholeDocumentBlocks(lineCount: Int, side: Side): List<MarkdownChangedBlockDescriptor> {
    if (lineCount <= 0) return emptyList()

    val kind = if (side.isLeft) MarkdownChangedBlockKind.MODIFIED else MarkdownChangedBlockKind.ADDED
    return listOf(MarkdownChangedBlockDescriptor(kind, 1, lineCount))
  }

  private fun block(fragment: LineFragment, side: Side): MarkdownChangedBlockDescriptor {
    val startLine = startLine(fragment, side)
    val endLine = endLine(fragment, side)
    if (startLine == endLine) {
      return MarkdownChangedBlockDescriptor(MarkdownChangedBlockKind.REMOVED, startLine + 1, startLine + 1)
    }

    val opposite = side.other()
    val oppositeIsEmpty = startLine(fragment, opposite) == endLine(fragment, opposite)
    val kind = if (!side.isLeft && oppositeIsEmpty) MarkdownChangedBlockKind.ADDED else MarkdownChangedBlockKind.MODIFIED
    return MarkdownChangedBlockDescriptor(kind, startLine + 1, endLine)
  }

  private fun startLine(fragment: LineFragment, side: Side): Int {
    return if (side.isLeft) fragment.startLine1 else fragment.startLine2
  }

  private fun endLine(fragment: LineFragment, side: Side): Int {
    return if (side.isLeft) fragment.endLine1 else fragment.endLine2
  }
}
