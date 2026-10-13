// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.intellij.plugins.markdown.lang.MarkdownFileType

internal class MarkdownDiffSidesTest : BasePlatformTestCase() {
  fun `test detects two markdown document contents`() {
    val sides = MarkdownDiffSides.from(request(markdownContent("# Before\n"), markdownContent("# After\n")))

    assertNotNull(sides)
    assertTrue(sides!!.isTwoSide)
    assertEquals("# Before\n", sides.left!!.document.text)
    assertEquals("# After\n", sides.right!!.document.text)
    assertEquals("left.md", sides.left!!.title)
    assertEquals("right.md", sides.right!!.title)
  }

  fun `test detects an added file as a right side only diff`() {
    val sides = MarkdownDiffSides.from(request(DiffContentFactory.getInstance().createEmpty(), markdownContent("# After\n")))

    assertNotNull(sides)
    assertFalse(sides!!.isTwoSide)
    assertNull(sides.left)
    assertEquals("# After\n", sides.right!!.document.text)
    assertEquals(listOf(sides.right), sides.present)
  }

  fun `test detects a deleted file as a left side only diff`() {
    val sides = MarkdownDiffSides.from(request(markdownContent("# Before\n"), DiffContentFactory.getInstance().createEmpty()))

    assertNotNull(sides)
    assertFalse(sides!!.isTwoSide)
    assertNull(sides.right)
    assertEquals("# Before\n", sides.left!!.document.text)
  }

  fun `test ignores a diff with two empty contents`() {
    val empty = DiffContentFactory.getInstance().createEmpty()

    assertNull(MarkdownDiffSides.from(request(empty, empty)))
  }

  fun `test detects markdown when only one side has markdown type`() {
    val sides = MarkdownDiffSides.from(
      request(DiffContentFactory.getInstance().create(project, "", PlainTextFileType.INSTANCE), markdownContent("# After\n"))
    )

    assertNotNull(sides)
    assertTrue(sides!!.isTwoSide)
  }

  fun `test ignores non markdown contents`() {
    val content = DiffContentFactory.getInstance().create(project, "text\n", PlainTextFileType.INSTANCE)

    assertNull(MarkdownDiffSides.from(request(content, content)))
  }

  fun `test ignores one side and three side requests`() {
    val content = markdownContent("# Markdown\n")

    assertNull(MarkdownDiffSides.from(SimpleDiffRequest(null, listOf(content), listOf("only.md"))))
    assertNull(MarkdownDiffSides.from(SimpleDiffRequest(null, listOf(content, content, content), listOf("a.md", "b.md", "c.md"))))
  }

  fun `test uses highlight file when available`() {
    val file = myFixture.addFileToProject("docs/readme.md", "# Readme\n").virtualFile
    val content = DiffContentFactory.getInstance().createDocument(project, file)!!

    val sides = MarkdownDiffSides.from(request(content, content))

    assertEquals(file, sides!!.right!!.virtualFile)
  }

  fun `test falls back to light markdown file without highlight file`() {
    val sides = MarkdownDiffSides.from(
      SimpleDiffRequest(null, listOf(markdownContent("# A\n"), markdownContent("# B\n")), listOf("readme.md (Local)", null))
    )

    assertNull(sides!!.left!!.content.highlightFile)
    assertEquals("readme.md", sides.left!!.virtualFile.name)
    assertEquals(MarkdownFileType.INSTANCE, sides.left!!.virtualFile.fileType)
    assertEquals("markdown.md", sides.right!!.virtualFile.name)
  }

  private fun markdownContent(text: String): DiffContent {
    return DiffContentFactory.getInstance().create(project, text, MarkdownFileType.INSTANCE)
  }

  private fun request(left: DiffContent, right: DiffContent): SimpleDiffRequest {
    return SimpleDiffRequest(null, left, right, "left.md", "right.md")
  }
}
