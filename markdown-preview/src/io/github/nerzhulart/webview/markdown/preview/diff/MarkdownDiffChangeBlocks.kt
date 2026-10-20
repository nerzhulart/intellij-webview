// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview.diff

import com.intellij.diff.fragments.DiffFragment
import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.util.Side
import io.github.nerzhulart.webview.markdown.preview.MarkdownChangedBlockDescriptor
import io.github.nerzhulart.webview.markdown.preview.MarkdownChangedBlockKind
import io.github.nerzhulart.webview.markdown.preview.MarkdownInlineChangeDescriptor

/**
 * Converts platform line fragments (0-based, end-exclusive line ranges) into preview change blocks
 * (1-based, end-inclusive line ranges, matching the `data-sourcepos` scheme).
 *
 * The inner fragments of a line fragment, which the diff provider computes for the `By word`, `By word, split` and
 * `By symbol` highlighting policies, become the inline changes of the block. Their offsets are relative to the start
 * of the line fragment, the inline changes carry absolute document offsets.
 */
internal object MarkdownDiffChangeBlocks {
  fun blocks(fragments: List<LineFragment>, side: Side): List<MarkdownChangedBlockDescriptor> {
    return fragments.mapNotNull { fragment -> block(fragment, side) }
  }

  fun leftBlocks(fragments: List<LineFragment>): List<MarkdownChangedBlockDescriptor> = blocks(fragments, Side.LEFT)

  fun rightBlocks(fragments: List<LineFragment>): List<MarkdownChangedBlockDescriptor> = blocks(fragments, Side.RIGHT)

  /**
   * Marks the whole document as changed, which is the case for a file that was added or deleted as a whole.
   */
  fun wholeDocumentBlocks(lineCount: Int, side: Side): List<MarkdownChangedBlockDescriptor> {
    if (lineCount <= 0) return emptyList()

    val kind = if (side.isLeft) MarkdownChangedBlockKind.REMOVED else MarkdownChangedBlockKind.ADDED
    return listOf(MarkdownChangedBlockDescriptor(kind, 1, lineCount))
  }

  /**
   * Returns `null` for a fragment that covers no lines on [side]: such a change is already visible as added or removed
   * content in the opposite preview, so a two-side diff does not need an empty placeholder for it.
   */
  private fun block(fragment: LineFragment, side: Side): MarkdownChangedBlockDescriptor? {
    val startLine = startLine(fragment, side)
    val endLine = endLine(fragment, side)
    if (startLine == endLine) return null

    val opposite = side.other()
    val oppositeIsEmpty = startLine(fragment, opposite) == endLine(fragment, opposite)
    val kind = when {
      !oppositeIsEmpty -> MarkdownChangedBlockKind.MODIFIED
      side.isLeft -> MarkdownChangedBlockKind.REMOVED
      else -> MarkdownChangedBlockKind.ADDED
    }
    return MarkdownChangedBlockDescriptor(kind, startLine + 1, endLine, inlineChanges(fragment, side))
  }

  /**
   * Skips an inner fragment that covers no text on [side]: the opposite side shows it as inserted or deleted text.
   */
  private fun inlineChanges(fragment: LineFragment, side: Side): List<MarkdownInlineChangeDescriptor> {
    val innerFragments = fragment.innerFragments ?: return emptyList()
    val fragmentStartOffset = startOffset(fragment, side)
    return innerFragments.mapNotNull { inner ->
      val startOffset = startOffset(inner, side)
      val endOffset = endOffset(inner, side)
      if (startOffset == endOffset) return@mapNotNull null

      val oppositeIsEmpty = startOffset(inner, side.other()) == endOffset(inner, side.other())
      val kind = when {
        !oppositeIsEmpty -> MarkdownChangedBlockKind.MODIFIED
        side.isLeft -> MarkdownChangedBlockKind.REMOVED
        else -> MarkdownChangedBlockKind.ADDED
      }
      MarkdownInlineChangeDescriptor(kind, fragmentStartOffset + startOffset, fragmentStartOffset + endOffset)
    }
  }

  private fun startOffset(fragment: DiffFragment, side: Side): Int {
    return if (side.isLeft) fragment.startOffset1 else fragment.startOffset2
  }

  private fun endOffset(fragment: DiffFragment, side: Side): Int {
    return if (side.isLeft) fragment.endOffset1 else fragment.endOffset2
  }

  private fun startLine(fragment: LineFragment, side: Side): Int {
    return if (side.isLeft) fragment.startLine1 else fragment.startLine2
  }

  private fun endLine(fragment: LineFragment, side: Side): Int {
    return if (side.isLeft) fragment.endLine1 else fragment.endLine2
  }
}
