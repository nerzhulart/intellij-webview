// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LOG = logger<MarkdownDocumentEditorSupport>()

internal object MarkdownDocumentEditorSupport {
  private const val COMMAND_GROUP_ID = "MarkdownPreviewInPlaceEdit"

  fun canEditDocument(
    project: Project?,
    virtualFile: VirtualFile?,
    isEditablePreview: Boolean = true,
  ): Boolean {
    if (!isEditablePreview) return false
    if (project == null || project.isDisposed) return false
    val validFile = virtualFile?.takeIf { it.isValid && !it.isDirectory && it.isWritable } ?: return false
    return ReadAction.compute<Boolean, RuntimeException> {
      if (project.isDisposed || !validFile.isValid) return@compute false
      val document = FileDocumentManager.getInstance().getDocument(validFile) ?: return@compute false
      document.isWritable
    }
  }

  suspend fun applyDocumentEdit(
    project: Project?,
    virtualFile: VirtualFile?,
    currentContentVersion: Int?,
    isEditablePreview: Boolean = true,
    params: MarkdownApplyDocumentEditParams,
  ): DocumentEditExecutionResult {
    if (!isEditablePreview || project == null || project.isDisposed || virtualFile == null || !virtualFile.isValid) {
      return DocumentEditExecutionResult(
        applied = false,
        errorMessage = "Document is not editable",
      )
    }
    if (currentContentVersion == null || params.contentVersion != currentContentVersion) {
      return DocumentEditExecutionResult(
        applied = false,
        conflict = true,
        errorMessage = "Document was modified externally",
      )
    }

    return withContext(Dispatchers.EDT) {
      applyDocumentEditOnEdt(
        project = project,
        virtualFile = virtualFile,
        currentContentVersion = currentContentVersion,
        isEditablePreview = isEditablePreview,
        params = params,
      )
    }
  }

  fun applyDocumentEditOnEdt(
    project: Project?,
    virtualFile: VirtualFile?,
    currentContentVersion: Int?,
    isEditablePreview: Boolean = true,
    params: MarkdownApplyDocumentEditParams,
  ): DocumentEditExecutionResult {
    if (!isEditablePreview || project == null || project.isDisposed || virtualFile == null || !virtualFile.isValid || !virtualFile.isWritable) {
      return DocumentEditExecutionResult(
        applied = false,
        errorMessage = "Document is not editable",
      )
    }
    if (currentContentVersion == null || params.contentVersion != currentContentVersion) {
      return DocumentEditExecutionResult(
        applied = false,
        conflict = true,
        errorMessage = "Document was modified externally",
      )
    }

    val document = FileDocumentManager.getInstance().getDocument(virtualFile)
    if (document == null || !document.isWritable) {
      return DocumentEditExecutionResult(
        applied = false,
        errorMessage = "Document is read-only",
      )
    }

    val textRange = resolveSourceRangeOffsets(document, params.range)
      ?: return DocumentEditExecutionResult(
        applied = false,
        errorMessage = "Invalid source range: ${params.range}",
      )

    val commandName = params.actionTitle.takeIf { it.isNotBlank() }
      ?: MarkdownWebViewPreviewBundle.message("markdown.preview.edit.command.name")

    return try {
      WriteCommandAction.writeCommandAction(project)
        .withName(commandName)
        .withGroupId(COMMAND_GROUP_ID)
        .run<RuntimeException> {
          document.replaceString(textRange.startOffset, textRange.endOffset, params.newMarkdown)
        }
      DocumentEditExecutionResult(
        applied = true,
        updatedMarkdown = document.text,
      )
    }
    catch (t: Throwable) {
      LOG.warn("Failed to apply Markdown preview document edit", t)
      DocumentEditExecutionResult(
        applied = false,
        errorMessage = t.message ?: "Failed to apply document edit",
      )
    }
  }

  /**
   * Converts a 1-based [MarkdownPreviewSourceRange] (`startLine:startColumn-endLine:endColumn`, where `endColumn` follows
   * `unist` 1-based exclusive column semantics) to 0-based UTF-16 offsets in [document].
   */
  fun resolveSourceRangeOffsets(document: Document, range: MarkdownPreviewSourceRange): TextRange? {
    if (range.startLine < 1 || range.startColumn < 1 || range.endLine < range.startLine || range.endColumn < 1) {
      return null
    }
    if (range.endLine == range.startLine && range.endColumn < range.startColumn) {
      return null
    }

    if (document.lineCount == 0) {
      return if (range.startLine == 1 && range.startColumn == 1 && range.endLine == 1 && range.endColumn == 1) {
        TextRange(0, 0)
      }
      else {
        null
      }
    }

    val startOffset = resolvePointOffset(document, range.startLine, range.startColumn) ?: return null
    val endOffset = resolvePointOffset(document, range.endLine, range.endColumn) ?: return null
    if (endOffset < startOffset) return null
    return TextRange(startOffset, endOffset)
  }

  private fun resolvePointOffset(document: Document, line: Int, column: Int): Int? {
    val lineCount = document.lineCount
    if (line == lineCount + 1 && column == 1) {
      return document.textLength
    }
    if (line !in 1..lineCount) return null

    val lineIndex = line - 1
    val lineStart = document.getLineStartOffset(lineIndex)
    val lineEnd = document.getLineEndOffset(lineIndex)
    val offset = lineStart + (column - 1)
    if (offset !in lineStart..lineEnd) return null
    return offset
  }

  internal data class DocumentEditExecutionResult(
    val applied: Boolean,
    val conflict: Boolean = false,
    val errorMessage: String? = null,
    val updatedMarkdown: String? = null,
  ) {
    fun toContractResult(): MarkdownApplyDocumentEditResultParams {
      return MarkdownApplyDocumentEditResultParams(
        applied = applied,
        conflict = conflict,
        errorMessage = errorMessage,
      )
    }
  }
}
