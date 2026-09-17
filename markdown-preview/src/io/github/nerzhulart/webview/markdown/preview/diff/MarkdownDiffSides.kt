// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview.diff

import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.contents.EmptyContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.util.Side
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import org.intellij.plugins.markdown.lang.MarkdownFileType

internal class MarkdownDiffSide(
  val side: Side,
  val content: DocumentContent,
  val title: String?,
) {
  val document: Document
    get() = content.document

  val virtualFile: VirtualFile by lazy(LazyThreadSafetyMode.NONE) {
    content.highlightFile ?: LightVirtualFile(fallbackFileName(), MarkdownFileType.INSTANCE, "")
  }

  private fun fallbackFileName(): String {
    val candidate = title
      ?.substringBefore('(')
      ?.trim()
      ?.substringAfterLast('/')
      ?.substringAfterLast('\\')
      .orEmpty()
    return if (candidate.endsWith(".md", ignoreCase = true) && candidate.length > ".md".length) candidate else "markdown.md"
  }
}

/**
 * Markdown sides of a diff request. A side is absent when the corresponding content is empty,
 * which is the case for a file that was added or deleted as a whole.
 */
internal class MarkdownDiffSides(
  val left: MarkdownDiffSide?,
  val right: MarkdownDiffSide?,
) {
  val present: List<MarkdownDiffSide> = listOfNotNull(left, right)

  val isTwoSide: Boolean
    get() = left != null && right != null

  fun side(side: Side): MarkdownDiffSide? = if (side.isLeft) left else right

  companion object {
    fun from(request: DiffRequest): MarkdownDiffSides? {
      val contentRequest = request as? ContentDiffRequest ?: return null
      val contents = contentRequest.contents
      if (contents.size != 2) return null
      if (contents.any { !isSupported(it) }) return null

      val titles = contentRequest.contentTitles
      val sides = MarkdownDiffSides(
        left = documentSide(Side.LEFT, contents[0], titles.getOrNull(0)),
        right = documentSide(Side.RIGHT, contents[1], titles.getOrNull(1)),
      )
      if (sides.present.isEmpty()) return null
      if (sides.present.none { isMarkdown(it.content) }) return null
      return sides
    }

    private fun isSupported(content: DiffContent): Boolean {
      return content is DocumentContent || content is EmptyContent
    }

    private fun documentSide(side: Side, content: DiffContent, title: String?): MarkdownDiffSide? {
      val documentContent = content as? DocumentContent ?: return null
      return MarkdownDiffSide(side, documentContent, title)
    }

    private fun isMarkdown(content: DocumentContent): Boolean {
      return content.contentType is MarkdownFileType || content.highlightFile?.fileType is MarkdownFileType
    }
  }
}
