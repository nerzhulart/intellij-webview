// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.charset.StandardCharsets
import java.util.Base64

internal class MarkdownDocumentEditorSupportTest : BasePlatformTestCase() {
  fun `test resolves 1-based unist source ranges including trailing line without newline`() {
    val file = myFixture.addFileToProject(
      "docs/readme.md",
      "# Title\n\n- [ ] First task\n- [ ] Second task",
    ).virtualFile
    val document = FileDocumentManager.getInstance().getDocument(file)!!

    val headingRange = MarkdownDocumentEditorSupport.resolveSourceRangeOffsets(
      document,
      MarkdownPreviewSourceRange(startLine = 1, startColumn = 1, endLine = 1, endColumn = 8),
    )
    assertNotNull(headingRange)
    assertEquals("# Title", document.getText(headingRange!!))

    val lastLineRange = MarkdownDocumentEditorSupport.resolveSourceRangeOffsets(
      document,
      MarkdownPreviewSourceRange(startLine = 4, startColumn = 1, endLine = 4, endColumn = 18),
    )
    assertNotNull(lastLineRange)
    assertEquals("- [ ] Second task", document.getText(lastLineRange!!))

    val invalidRange = MarkdownDocumentEditorSupport.resolveSourceRangeOffsets(
      document,
      MarkdownPreviewSourceRange(startLine = 10, startColumn = 1, endLine = 10, endColumn = 5),
    )
    assertNull(invalidRange)
  }

  fun `test applies range edit on EDT and updates document text`() {
    val file = myFixture.addFileToProject(
      "docs/readme.md",
      "# Heading\n\nParagraph text\n",
    ).virtualFile
    val document = FileDocumentManager.getInstance().getDocument(file)!!

    assertTrue(MarkdownDocumentEditorSupport.canEditDocument(project, file, isEditablePreview = true))
    assertFalse(MarkdownDocumentEditorSupport.canEditDocument(project, file, isEditablePreview = false))

    val result = MarkdownDocumentEditorSupport.applyDocumentEditOnEdt(
      project = project,
      virtualFile = file,
      currentContentVersion = 3,
      isEditablePreview = true,
      params = MarkdownApplyDocumentEditParams(
        contentVersion = 3,
        range = MarkdownPreviewSourceRange(startLine = 3, startColumn = 1, endLine = 3, endColumn = 15),
        newMarkdown = "Updated **bold** paragraph",
        actionTitle = "Edit paragraph",
      ),
    )

    assertTrue(result.applied)
    assertFalse(result.conflict)
    assertEquals("# Heading\n\nUpdated **bold** paragraph\n", document.text)
    assertEquals(document.text, result.updatedMarkdown)
  }

  fun `test rejects stale contentVersion with conflict flag`() {
    val file = myFixture.addFileToProject("docs/readme.md", "# Heading\n").virtualFile
    val document = FileDocumentManager.getInstance().getDocument(file)!!

    val result = MarkdownDocumentEditorSupport.applyDocumentEditOnEdt(
      project = project,
      virtualFile = file,
      currentContentVersion = 5,
      isEditablePreview = true,
      params = MarkdownApplyDocumentEditParams(
        contentVersion = 4,
        range = MarkdownPreviewSourceRange(startLine = 1, startColumn = 1, endLine = 1, endColumn = 10),
        newMarkdown = "# Stale",
      ),
    )

    assertFalse(result.applied)
    assertTrue(result.conflict)
    assertEquals("# Heading\n", document.text)
  }

  fun `test rejects edit when document is read-only or preview is non-editable`() {
    val file = myFixture.addFileToProject("docs/readme.md", "# Heading\n").virtualFile
    val document = FileDocumentManager.getInstance().getDocument(file)!!
    document.setReadOnly(true)
    try {
      assertFalse(MarkdownDocumentEditorSupport.canEditDocument(project, file, isEditablePreview = true))

      val result = MarkdownDocumentEditorSupport.applyDocumentEditOnEdt(
        project = project,
        virtualFile = file,
        currentContentVersion = 1,
        isEditablePreview = true,
        params = MarkdownApplyDocumentEditParams(
          contentVersion = 1,
          range = MarkdownPreviewSourceRange(startLine = 1, startColumn = 1, endLine = 1, endColumn = 10),
          newMarkdown = "# Blocked",
        ),
      )

      assertFalse(result.applied)
      assertFalse(result.conflict)
      assertEquals("# Heading\n", document.text)
    }
    finally {
      document.setReadOnly(false)
    }
  }

  fun `test saves pasted image assets into assets directory with collision suffix`() {
    val file = myFixture.addFileToProject("docs/readme.md", "# Docs\n").virtualFile
    val payloadBytes = "fake-png-binary".toByteArray(StandardCharsets.UTF_8)
    val base64 = Base64.getEncoder().encodeToString(payloadBytes)

    val firstResult = MarkdownImageAssetSaver.saveImageAssetOnEdt(
      project = project,
      markdownFile = file,
      isEditablePreview = true,
      params = MarkdownSaveImageAssetParams(
        fileNameHint = "diagram.png",
        mimeType = "image/png",
        base64Bytes = "data:image/png;base64,$base64",
      ),
    )

    assertTrue(firstResult.saved)
    assertEquals("assets/diagram.png", firstResult.relativePath)

    val secondResult = MarkdownImageAssetSaver.saveImageAssetOnEdt(
      project = project,
      markdownFile = file,
      isEditablePreview = true,
      params = MarkdownSaveImageAssetParams(
        fileNameHint = "diagram.png",
        mimeType = "image/png",
        base64Bytes = base64,
      ),
    )

    assertTrue(secondResult.saved)
    assertEquals("assets/diagram-1.png", secondResult.relativePath)

    val savedFile = file.parent.findFileByRelativePath("assets/diagram.png")
    assertNotNull(savedFile)
    assertEquals("fake-png-binary", String(savedFile!!.contentsToByteArray(), StandardCharsets.UTF_8))
    assertEquals("![Diagram](assets/diagram.png)", MarkdownImageAssetSaver.formatMarkdownImageReference("assets/diagram.png", "Diagram"))
  }
}
