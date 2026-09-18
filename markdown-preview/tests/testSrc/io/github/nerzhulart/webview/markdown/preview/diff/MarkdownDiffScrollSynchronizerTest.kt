// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview.diff

import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.fragments.LineFragmentImpl
import com.intellij.diff.util.Side
import junit.framework.TestCase

internal class MarkdownDiffScrollSynchronizerTest : TestCase() {
  fun `test lines before the first change are not shifted`() {
    val fragments = listOf(fragment(startLine1 = 5, endLine1 = 5, startLine2 = 5, endLine2 = 7))

    assertEquals(0, MarkdownDiffScrollSynchronizer.transferLine(fragments, Side.LEFT, 0))
    assertEquals(4, MarkdownDiffScrollSynchronizer.transferLine(fragments, Side.LEFT, 4))
  }

  fun `test lines after an insertion are shifted`() {
    val fragments = listOf(fragment(startLine1 = 5, endLine1 = 5, startLine2 = 5, endLine2 = 7))

    assertEquals(7, MarkdownDiffScrollSynchronizer.transferLine(fragments, Side.LEFT, 5))
    assertEquals(8, MarkdownDiffScrollSynchronizer.transferLine(fragments, Side.RIGHT, 10))
  }

  fun `test lines inside a modified block keep their relative position`() {
    val fragments = listOf(fragment(startLine1 = 2, endLine1 = 6, startLine2 = 2, endLine2 = 6))

    assertEquals(4, MarkdownDiffScrollSynchronizer.transferLine(fragments, Side.LEFT, 4))
    assertEquals(4, MarkdownDiffScrollSynchronizer.transferLine(fragments, Side.RIGHT, 4))
  }

  fun `test lines inside a shrunk block are clamped to the opposite block`() {
    val fragments = listOf(fragment(startLine1 = 1, endLine1 = 6, startLine2 = 1, endLine2 = 2))

    assertEquals(1, MarkdownDiffScrollSynchronizer.transferLine(fragments, Side.LEFT, 5))
  }

  fun `test deleted block maps to its position on the opposite side`() {
    val fragments = listOf(fragment(startLine1 = 3, endLine1 = 6, startLine2 = 3, endLine2 = 3))

    assertEquals(3, MarkdownDiffScrollSynchronizer.transferLine(fragments, Side.LEFT, 4))
    assertEquals(1, MarkdownDiffScrollSynchronizer.transferLine(fragments, Side.RIGHT, 1))
    assertEquals(6, MarkdownDiffScrollSynchronizer.transferLine(fragments, Side.RIGHT, 3))
  }

  fun `test change start lines are taken per side`() {
    val fragment = fragment(startLine1 = 3, endLine1 = 6, startLine2 = 8, endLine2 = 9)

    assertEquals(3, MarkdownDiffScrollSynchronizer.changeStartLine(fragment, Side.LEFT))
    assertEquals(8, MarkdownDiffScrollSynchronizer.changeStartLine(fragment, Side.RIGHT))
  }

  fun `test lines are unchanged without fragments`() {
    assertEquals(7, MarkdownDiffScrollSynchronizer.transferLine(emptyList(), Side.LEFT, 7))
  }

  private fun fragment(startLine1: Int, endLine1: Int, startLine2: Int, endLine2: Int): LineFragment {
    return LineFragmentImpl(startLine1, endLine1, startLine2, endLine2, 0, 0, 0, 0)
  }
}
