// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview.diff

import com.intellij.diff.fragments.DiffFragment
import com.intellij.diff.fragments.DiffFragmentImpl
import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.fragments.LineFragmentImpl
import com.intellij.diff.util.Side
import io.github.nerzhulart.webview.markdown.preview.MarkdownChangedBlockDescriptor
import io.github.nerzhulart.webview.markdown.preview.MarkdownChangedBlockKind
import io.github.nerzhulart.webview.markdown.preview.MarkdownInlineChangeDescriptor
import junit.framework.TestCase

internal class MarkdownDiffChangeBlocksTest : TestCase() {
  fun `test added block is highlighted on the right and left alone on the left`() {
    val fragments = listOf(fragment(startLine1 = 2, endLine1 = 2, startLine2 = 2, endLine2 = 4))

    assertEquals(listOf(block(MarkdownChangedBlockKind.ADDED, 3, 4)), MarkdownDiffChangeBlocks.rightBlocks(fragments))
    assertEquals(emptyList<MarkdownChangedBlockDescriptor>(), MarkdownDiffChangeBlocks.leftBlocks(fragments))
  }

  fun `test removed block is highlighted on the left and left alone on the right`() {
    val fragments = listOf(fragment(startLine1 = 1, endLine1 = 3, startLine2 = 1, endLine2 = 1))

    assertEquals(listOf(block(MarkdownChangedBlockKind.REMOVED, 2, 3)), MarkdownDiffChangeBlocks.leftBlocks(fragments))
    assertEquals(emptyList<MarkdownChangedBlockDescriptor>(), MarkdownDiffChangeBlocks.rightBlocks(fragments))
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
      ),
      MarkdownDiffChangeBlocks.rightBlocks(fragments),
    )
    assertEquals(
      listOf(
        block(MarkdownChangedBlockKind.MODIFIED, 1, 1),
        block(MarkdownChangedBlockKind.REMOVED, 9, 10),
      ),
      MarkdownDiffChangeBlocks.leftBlocks(fragments),
    )
  }

  fun `test whole document is added on the right and removed on the left`() {
    assertEquals(
      listOf(block(MarkdownChangedBlockKind.ADDED, 1, 4)),
      MarkdownDiffChangeBlocks.wholeDocumentBlocks(4, Side.RIGHT),
    )
    assertEquals(
      listOf(block(MarkdownChangedBlockKind.REMOVED, 1, 4)),
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

  fun `test modified inner fragment becomes an inline change with absolute offsets on both sides`() {
    val fragments = listOf(
      fragment(
        startLine1 = 2, endLine1 = 3, startLine2 = 2, endLine2 = 3,
        startOffset1 = 10, endOffset1 = 40, startOffset2 = 12, endOffset2 = 45,
        innerFragments = listOf(DiffFragmentImpl(4, 9, 4, 11)),
      ),
    )

    assertEquals(
      listOf(block(MarkdownChangedBlockKind.MODIFIED, 3, 3, inline(MarkdownChangedBlockKind.MODIFIED, 14, 19))),
      MarkdownDiffChangeBlocks.leftBlocks(fragments),
    )
    assertEquals(
      listOf(block(MarkdownChangedBlockKind.MODIFIED, 3, 3, inline(MarkdownChangedBlockKind.MODIFIED, 16, 23))),
      MarkdownDiffChangeBlocks.rightBlocks(fragments),
    )
  }

  fun `test inserted inner fragment is an added inline change on the right only`() {
    val fragments = listOf(
      fragment(
        startLine1 = 0, endLine1 = 1, startLine2 = 0, endLine2 = 1,
        startOffset1 = 0, endOffset1 = 20, startOffset2 = 0, endOffset2 = 26,
        innerFragments = listOf(DiffFragmentImpl(5, 5, 5, 11)),
      ),
    )

    assertEquals(
      listOf(block(MarkdownChangedBlockKind.MODIFIED, 1, 1, inline(MarkdownChangedBlockKind.ADDED, 5, 11))),
      MarkdownDiffChangeBlocks.rightBlocks(fragments),
    )
    assertEquals(listOf(block(MarkdownChangedBlockKind.MODIFIED, 1, 1)), MarkdownDiffChangeBlocks.leftBlocks(fragments))
  }

  fun `test deleted inner fragment is a removed inline change on the left only`() {
    val fragments = listOf(
      fragment(
        startLine1 = 0, endLine1 = 1, startLine2 = 0, endLine2 = 1,
        startOffset1 = 30, endOffset1 = 56, startOffset2 = 30, endOffset2 = 50,
        innerFragments = listOf(DiffFragmentImpl(0, 6, 0, 0)),
      ),
    )

    assertEquals(
      listOf(block(MarkdownChangedBlockKind.MODIFIED, 1, 1, inline(MarkdownChangedBlockKind.REMOVED, 30, 36))),
      MarkdownDiffChangeBlocks.leftBlocks(fragments),
    )
    assertEquals(listOf(block(MarkdownChangedBlockKind.MODIFIED, 1, 1)), MarkdownDiffChangeBlocks.rightBlocks(fragments))
  }

  fun `test inner fragments keep their order`() {
    val fragments = listOf(
      fragment(
        startLine1 = 0, endLine1 = 1, startLine2 = 0, endLine2 = 1,
        startOffset1 = 0, endOffset1 = 30, startOffset2 = 0, endOffset2 = 30,
        innerFragments = listOf(DiffFragmentImpl(0, 3, 0, 4), DiffFragmentImpl(10, 10, 11, 15), DiffFragmentImpl(20, 25, 25, 25)),
      ),
    )

    assertEquals(
      listOf(
        block(
          MarkdownChangedBlockKind.MODIFIED, 1, 1,
          inline(MarkdownChangedBlockKind.MODIFIED, 0, 4),
          inline(MarkdownChangedBlockKind.ADDED, 11, 15),
        ),
      ),
      MarkdownDiffChangeBlocks.rightBlocks(fragments),
    )
    assertEquals(
      listOf(
        block(
          MarkdownChangedBlockKind.MODIFIED, 1, 1,
          inline(MarkdownChangedBlockKind.MODIFIED, 0, 3),
          inline(MarkdownChangedBlockKind.REMOVED, 20, 25),
        ),
      ),
      MarkdownDiffChangeBlocks.leftBlocks(fragments),
    )
  }

  fun `test fragment without inner fragments has no inline changes`() {
    val fragments = listOf(
      fragment(
        startLine1 = 0, endLine1 = 1, startLine2 = 0, endLine2 = 1,
        startOffset1 = 0, endOffset1 = 20, startOffset2 = 0, endOffset2 = 26,
        innerFragments = null,
      ),
    )

    assertEquals(listOf(block(MarkdownChangedBlockKind.MODIFIED, 1, 1)), MarkdownDiffChangeBlocks.rightBlocks(fragments))
    assertEquals(listOf(block(MarkdownChangedBlockKind.MODIFIED, 1, 1)), MarkdownDiffChangeBlocks.leftBlocks(fragments))
  }

  private fun fragment(startLine1: Int, endLine1: Int, startLine2: Int, endLine2: Int): LineFragment {
    return LineFragmentImpl(startLine1, endLine1, startLine2, endLine2, 0, 0, 0, 0)
  }

  private fun fragment(
    startLine1: Int, endLine1: Int, startLine2: Int, endLine2: Int,
    startOffset1: Int, endOffset1: Int, startOffset2: Int, endOffset2: Int,
    innerFragments: List<DiffFragment>?,
  ): LineFragment {
    return LineFragmentImpl(startLine1, endLine1, startLine2, endLine2, startOffset1, endOffset1, startOffset2, endOffset2, innerFragments)
  }

  private fun block(
    kind: MarkdownChangedBlockKind,
    startLine: Int,
    endLine: Int,
    vararg inlineChanges: MarkdownInlineChangeDescriptor,
  ): MarkdownChangedBlockDescriptor {
    return MarkdownChangedBlockDescriptor(kind, startLine, endLine, inlineChanges.toList())
  }

  private fun inline(kind: MarkdownChangedBlockKind, startOffset: Int, endOffset: Int): MarkdownInlineChangeDescriptor {
    return MarkdownInlineChangeDescriptor(kind, startOffset, endOffset)
  }
}
