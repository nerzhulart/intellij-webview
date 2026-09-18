// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview.diff

import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.fragments.LineFragmentImpl
import com.intellij.diff.util.Side
import io.github.nerzhulart.webview.markdown.preview.MarkdownChangedBlockDescriptor
import io.github.nerzhulart.webview.markdown.preview.MarkdownChangedBlockKind
import junit.framework.TestCase

internal class MarkdownDiffChangeBlocksTest : TestCase() {
  fun `test added block is highlighted on the right and marked as removed placeholder on the left`() {
    val fragments = listOf(fragment(startLine1 = 2, endLine1 = 2, startLine2 = 2, endLine2 = 4))

    assertEquals(listOf(block(MarkdownChangedBlockKind.ADDED, 3, 4)), MarkdownDiffChangeBlocks.rightBlocks(fragments))
    assertEquals(listOf(block(MarkdownChangedBlockKind.REMOVED, 3, 3)), MarkdownDiffChangeBlocks.leftBlocks(fragments))
  }

  fun `test removed block is highlighted on the left and marked as removed placeholder on the right`() {
    val fragments = listOf(fragment(startLine1 = 1, endLine1 = 3, startLine2 = 1, endLine2 = 1))

    assertEquals(listOf(block(MarkdownChangedBlockKind.MODIFIED, 2, 3)), MarkdownDiffChangeBlocks.leftBlocks(fragments))
    assertEquals(listOf(block(MarkdownChangedBlockKind.REMOVED, 2, 2)), MarkdownDiffChangeBlocks.rightBlocks(fragments))
  }

  fun `test modified block is highlighted on both sides`() {
    val fragments = listOf(fragment(startLine1 = 0, endLine1 = 2, startLine2 = 0, endLine2 = 3))

    assertEquals(listOf(block(MarkdownChangedBlockKind.MODIFIED, 1, 2)), MarkdownDiffChangeBlocks.leftBlocks(fragments))
    assertEquals(listOf(block(MarkdownChangedBlockKind.MODIFIED, 1, 3)), MarkdownDiffChangeBlocks.rightBlocks(fragments))
  }

  fun `test multiple fragments keep their order`() {
    val fragments = listOf(
      fragment(startLine1 = 0, endLine1 = 1, startLine2 = 0, endLine2 = 1),
      fragment(startLine1 = 4, endLine1 = 4, startLine2 = 4, endLine2 = 6),
      fragment(startLine1 = 8, endLine1 = 10, startLine2 = 10, endLine2 = 10),
    )

    assertEquals(
      listOf(
        block(MarkdownChangedBlockKind.MODIFIED, 1, 1),
        block(MarkdownChangedBlockKind.ADDED, 5, 6),
        block(MarkdownChangedBlockKind.REMOVED, 11, 11),
      ),
      MarkdownDiffChangeBlocks.rightBlocks(fragments),
    )
    assertEquals(
      listOf(
        block(MarkdownChangedBlockKind.MODIFIED, 1, 1),
        block(MarkdownChangedBlockKind.REMOVED, 5, 5),
        block(MarkdownChangedBlockKind.MODIFIED, 9, 10),
      ),
      MarkdownDiffChangeBlocks.leftBlocks(fragments),
    )
  }

  fun `test whole document is added on the right and modified on the left`() {
    assertEquals(
      listOf(block(MarkdownChangedBlockKind.ADDED, 1, 4)),
      MarkdownDiffChangeBlocks.wholeDocumentBlocks(4, Side.RIGHT),
    )
    assertEquals(
      listOf(block(MarkdownChangedBlockKind.MODIFIED, 1, 4)),
      MarkdownDiffChangeBlocks.wholeDocumentBlocks(4, Side.LEFT),
    )
  }

  fun `test empty document produces no whole document block`() {
    assertEquals(emptyList<MarkdownChangedBlockDescriptor>(), MarkdownDiffChangeBlocks.wholeDocumentBlocks(0, Side.RIGHT))
  }

  fun `test no fragments produce no blocks`() {
    assertEquals(emptyList<MarkdownChangedBlockDescriptor>(), MarkdownDiffChangeBlocks.rightBlocks(emptyList()))
    assertEquals(emptyList<MarkdownChangedBlockDescriptor>(), MarkdownDiffChangeBlocks.leftBlocks(emptyList()))
  }

  private fun fragment(startLine1: Int, endLine1: Int, startLine2: Int, endLine2: Int): LineFragment {
    return LineFragmentImpl(startLine1, endLine1, startLine2, endLine2, 0, 0, 0, 0)
  }

  private fun block(kind: MarkdownChangedBlockKind, startLine: Int, endLine: Int): MarkdownChangedBlockDescriptor {
    return MarkdownChangedBlockDescriptor(kind, startLine, endLine)
  }
}
