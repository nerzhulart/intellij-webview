// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JPanel

internal class MarkdownAgentContextSupportTest : BasePlatformTestCase() {
  override fun runInDispatchThread(): Boolean = false

  fun `test resolveMentionPath returns project-relative path for project file and absolute path for external file`() {
    val inProjectFile = myFixture.addFileToProject("docs/guide/CLAUDE.md", "# Guide").virtualFile
    assertEquals(
      "docs/guide/CLAUDE.md",
      MarkdownAgentContextSupport.resolveMentionPath(project, inProjectFile, inProjectFile.name),
    )

    val externalFile = LightVirtualFile("external.md", "# External")
    val resolvedExternal = MarkdownAgentContextSupport.resolveMentionPath(project, externalFile, externalFile.name)
    assertEquals("/external.md", resolvedExternal)
  }

  fun `test formatComposerQuote formats file path and markdown blockquote with comment`() {
    val quote = MarkdownAgentContextSupport.formatComposerQuote(
      mentionPath = "docs/CLAUDE.md",
      selectedText = "\nFirst line  \n\nSecond line\n",
      userComment = "Please explain this section",
      existingComposerText = "",
    )

    assertEquals(
      "Quoted from @file:docs/CLAUDE.md :\n> First line\n>\n> Second line\n\nPlease explain this section",
      quote,
    )
  }

  fun `test formatComposerQuote formats quote without comment and separates from existing composer text`() {
    val quote = MarkdownAgentContextSupport.formatComposerQuote(
      mentionPath = "/Users/test/outside/my guide.md",
      selectedText = "Single paragraph",
      userComment = "   ",
      existingComposerText = "Existing draft",
    )

    assertEquals(
      "\n\nQuoted from @file:`/Users/test/outside/my guide.md` :\n> Single paragraph\n\n",
      quote,
    )
  }

  fun `test appendQuoteToTarget inserts markdown quote into composer field and moves caret to end`() = runBlocking {
    withContext(Dispatchers.EDT) {
      val composerField = EditorTextField("", project, com.intellij.openapi.fileTypes.FileTypes.PLAIN_TEXT).apply {
        name = MarkdownAgentContextSupport.AIR_SESSION_PROMPT_INPUT_NAME
      }
      var activated = false
      val target = MarkdownAgentContextSupport.AirComposerTarget(
        title = "Fix bug session",
        composerField = composerField,
        selected = true,
        activate = { activated = true },
      )

      val firstAdded = MarkdownAgentContextSupport.appendQuoteToTarget(
        project = project,
        target = target,
        mentionPath = "docs/CLAUDE.md",
        selectedText = "Always propagate errors",
        userComment = "My text",
      )
      assertTrue(firstAdded)
      assertTrue(activated)
      assertEquals("Quoted from @file:docs/CLAUDE.md :\n> Always propagate errors\n\nMy text", composerField.text)

      val secondAdded = MarkdownAgentContextSupport.appendQuoteToTarget(
        project = project,
        target = target,
        mentionPath = "/opt/external/CLAUDE.md",
        selectedText = "Second quote",
        userComment = "",
      )
      assertTrue(secondAdded)
      assertEquals(
        "Quoted from @file:docs/CLAUDE.md :\n> Always propagate errors\n\nMy text\n\nQuoted from @file:/opt/external/CLAUDE.md :\n> Second quote\n\n",
        composerField.text,
      )
    }
  }

  fun `test formatChipBody formats quote with comment`() {
    val body = MarkdownAgentContextSupport.formatChipBody(
      fileName = "README.md",
      selectedText = "First line\nSecond line\n",
      userComment = "Please explain this section",
    )

    assertEquals(
      """
      Quoted from @file:README.md :
      > First line
      > Second line

      Comment: Please explain this section
      """.trimIndent(),
      body,
    )
  }

  fun `test formatChipBody formats quote without comment`() {
    val body = MarkdownAgentContextSupport.formatChipBody(
      fileName = "guide.md",
      selectedText = "Single paragraph",
      userComment = "   ",
    )

    assertEquals(
      """
      Quoted from @file:guide.md :
      > Single paragraph
      """.trimIndent(),
      body,
    )
  }

  fun `test formatComponentName appends comment marker only when comment is present`() {
    assertEquals("README.md (comment)", MarkdownAgentContextSupport.formatComponentName("README.md", "Check this"))
    assertEquals("README.md", MarkdownAgentContextSupport.formatComponentName("README.md", ""))
    assertEquals("README.md", MarkdownAgentContextSupport.formatComponentName("README.md", "  "))
    assertEquals("README.md", MarkdownAgentContextSupport.formatComponentName("README.md", null))
  }

  fun `test resolveSourceComponent ensures component reports isShowing true`() {
    val fallbackComponent = MarkdownAgentContextSupport.resolveSourceComponent(null, "README.md")
    assertTrue(fallbackComponent.isShowing)
    assertEquals("README.md", fallbackComponent.name)

    val hiddenComponent = JPanel()
    val resolvedForHidden = MarkdownAgentContextSupport.resolveSourceComponent(hiddenComponent, "README.md (comment)")
    assertTrue(resolvedForHidden.isShowing)
    assertEquals("README.md (comment)", resolvedForHidden.name)

    val showingComponent = object : JPanel() {
      override fun isShowing(): Boolean = true
    }
    val resolvedForShowing = MarkdownAgentContextSupport.resolveSourceComponent(showingComponent, "guide.md")
    assertSame(showingComponent, resolvedForShowing)
    assertTrue(resolvedForShowing.isShowing)
    assertEquals("guide.md", resolvedForShowing.name)
  }

  fun `test addQuoteToAgentContext invokes registered action with selected item and showing component`() = runBlocking {
    val actionManager = ActionManager.getInstance()
    val previousAction = actionManager.getAction(MarkdownAgentContextSupport.ACTION_ID)
    var capturedSelectedItem: Any? = null
    var capturedComponent: Component? = null
    var capturedProjectName: String? = null
    var hadEditorOrFile = false

    val testAction = object : AnAction() {
      override fun actionPerformed(e: AnActionEvent) {
        capturedSelectedItem = e.getData(PlatformCoreDataKeys.SELECTED_ITEM)
        capturedComponent = e.getData(PlatformCoreDataKeys.CONTEXT_COMPONENT)
        capturedProjectName = e.project?.name
        hadEditorOrFile = e.getData(CommonDataKeys.EDITOR) != null || e.getData(CommonDataKeys.VIRTUAL_FILE) != null
      }
    }

    if (previousAction != null) {
      actionManager.unregisterAction(MarkdownAgentContextSupport.ACTION_ID)
    }
    actionManager.registerAction(MarkdownAgentContextSupport.ACTION_ID, testAction)
    try {
      assertTrue(MarkdownAgentContextSupport.isAvailable(project))

      val childComponent = object : JPanel() {
        override fun isShowing(): Boolean = true
      }
      val parentWithFile = object : JPanel(BorderLayout()), UiDataProvider {
        override fun isShowing(): Boolean = true
        override fun uiDataSnapshot(sink: DataSink) {
          sink[CommonDataKeys.VIRTUAL_FILE] = LightVirtualFile("architecture.md", "# Architecture")
        }
      }.apply {
        add(childComponent, BorderLayout.CENTER)
      }

      val added = MarkdownAgentContextSupport.addQuoteToAgentContext(
        project = project,
        fileName = "architecture.md",
        selectedText = " WebView bridge contract\nSecond line ",
        userComment = "Verify RPC types",
        contextComponent = childComponent,
      )

      assertNotNull(parentWithFile)
      assertTrue(added)
      assertEquals(project.name, capturedProjectName)
      assertNotNull(capturedComponent)
      assertTrue(capturedComponent!!.isShowing)
      assertEquals("architecture.md (comment)", capturedComponent!!.name)
      assertFalse(hadEditorOrFile)
      assertEquals(
        """
        Quoted from @file:architecture.md :
        >  WebView bridge contract
        > Second line

        Comment: Verify RPC types
        """.trimIndent(),
        capturedSelectedItem,
      )
    }
    finally {
      actionManager.unregisterAction(MarkdownAgentContextSupport.ACTION_ID)
      if (previousAction != null) {
        actionManager.registerAction(MarkdownAgentContextSupport.ACTION_ID, previousAction)
      }
    }
  }

  fun `test addQuoteToAgentContext returns false when action is unavailable or selection is blank`() = runBlocking {
    val actionManager = ActionManager.getInstance()
    val previousAction = actionManager.getAction(MarkdownAgentContextSupport.ACTION_ID)
    if (previousAction != null) {
      actionManager.unregisterAction(MarkdownAgentContextSupport.ACTION_ID)
    }
    try {
      assertFalse(MarkdownAgentContextSupport.isAvailable(project))
      assertFalse(
        MarkdownAgentContextSupport.addQuoteToAgentContext(
          project = project,
          fileName = "README.md",
          selectedText = "Some text",
          userComment = null,
        ),
      )
    }
    finally {
      if (previousAction != null) {
        actionManager.registerAction(MarkdownAgentContextSupport.ACTION_ID, previousAction)
      }
    }
  }
}
