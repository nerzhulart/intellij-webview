// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.BaseProjectDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFileBase
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Component
import java.util.Collections
import java.util.IdentityHashMap
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

private val LOG = logger<MarkdownAgentContextSupport>()

internal object MarkdownAgentContextSupport {
  @Suppress("UnresolvedPluginConfigReference")
  const val ACTION_ID: String = "AgentPrompt.AddToAgentContext"
  const val ACTION_PLACE: String = "WebviewMarkdownQuote"
  const val AIR_SESSION_PROMPT_INPUT_NAME: String = "air-session-prompt-input"

  internal data class AirComposerTarget(
    val title: String,
    val composerField: EditorTextField,
    val selected: Boolean,
    val activate: () -> Unit,
  )

  fun isAvailable(project: Project?): Boolean {
    return project != null && !project.isDisposed && ActionManager.getInstance()?.getAction(ACTION_ID) != null
  }

  fun resolveMentionPath(
    project: Project?,
    virtualFile: VirtualFile?,
    fallbackFileName: String,
  ): String {
    val targetFile = (virtualFile as? LightVirtualFileBase)?.originalFile ?: virtualFile
    if (targetFile != null) {
      val independentPath = FileUtil.toSystemIndependentName(targetFile.path)
      val basePath = project?.basePath?.let(FileUtil::toSystemIndependentName)?.trimEnd('/')
      if (!basePath.isNullOrEmpty() && FileUtil.isAncestor(basePath, independentPath, true)) {
        val relative = FileUtil.getRelativePath(basePath, independentPath, '/')
          ?: independentPath.removePrefix(basePath).trimStart('/')
        if (relative.isNotEmpty()) {
          LOG.trace {
            "markdown.webview.agentContext.resolveMentionPath - strategy=projectBasePath, " +
            "virtualFile=${virtualFile?.path}, targetFile=${targetFile.path}, resolved=$relative"
          }
          return relative
        }
      }
      if (project != null && !project.isDisposed) {
        val baseDir = runCatching { BaseProjectDirectories.getInstance(project).getBaseDirectoryFor(targetFile) }.getOrNull()
        if (baseDir != null && VfsUtilCore.isAncestor(baseDir, targetFile, true)) {
          val relative = VfsUtilCore.getRelativePath(targetFile, baseDir, '/')
          if (!relative.isNullOrEmpty()) {
            LOG.trace {
              "markdown.webview.agentContext.resolveMentionPath - strategy=baseProjectDirectories, " +
              "baseDir=${baseDir.path}, targetFile=${targetFile.path}, resolved=$relative"
            }
            return relative
          }
        }
      }
      if (independentPath.isNotEmpty()) {
        LOG.trace {
          "markdown.webview.agentContext.resolveMentionPath - strategy=externalAbsolutePath, " +
          "virtualFile=${virtualFile?.path}, targetFile=${targetFile.path}, resolved=$independentPath"
        }
        return independentPath
      }
      val resolvedName = targetFile.name.takeIf { it.isNotBlank() } ?: fallbackFileName
      LOG.trace {
        "markdown.webview.agentContext.resolveMentionPath - strategy=targetFileName, " +
        "virtualFile=${virtualFile?.path}, resolved=$resolvedName"
      }
      return resolvedName
    }
    LOG.trace {
      "markdown.webview.agentContext.resolveMentionPath - strategy=fallbackFileName, " +
      "virtualFile=null, resolved=$fallbackFileName"
    }
    return fallbackFileName
  }

  fun formatMentionToken(mentionPath: String): String {
    val normalized = mentionPath.trim().ifEmpty { "Markdown.md" }
    return if (normalized.any { it.isWhitespace() || it == '`' }) {
      "@file:`$normalized`"
    }
    else {
      "@file:$normalized"
    }
  }

  fun formatComposerQuote(
    mentionPath: String,
    selectedText: String,
    userComment: String?,
    existingComposerText: CharSequence = "",
  ): String {
    val quoteLines = selectedText.lines()
      .map(String::trimEnd)
      .dropWhile(String::isEmpty)
      .dropLastWhile(String::isEmpty)
    if (quoteLines.isEmpty()) return ""

    val separator = when {
      existingComposerText.isEmpty() -> ""
      existingComposerText.endsWith("\n\n") ||
      (existingComposerText.toString().isBlank() && existingComposerText.endsWith('\n')) -> ""
      existingComposerText.endsWith('\n') -> "\n"
      else -> "\n\n"
    }
    val mention = formatMentionToken(mentionPath)
    val trimmedComment = userComment?.trim().orEmpty()
    return buildString {
      append(separator)
      appendLine("Quoted from $mention :")
      quoteLines.forEach { line ->
        if (line.isEmpty()) appendLine(">") else appendLine("> $line")
      }
      appendLine()
      if (trimmedComment.isNotEmpty()) {
        append(trimmedComment)
      }
    }
  }

  fun formatChipBody(
    fileName: String,
    selectedText: String,
    userComment: String?,
  ): String {
    val mention = formatMentionToken(fileName)
    return buildString {
      appendLine("Quoted from $mention :")
      selectedText.trim('\r', '\n').trimEnd().lines().forEach { appendLine("> $it") }
      if (!userComment.isNullOrBlank()) {
        appendLine()
        append("Comment: ${userComment.trim()}")
      }
    }.trimEnd()
  }

  fun formatComponentName(fileName: String, userComment: String?): String {
    return if (!userComment.isNullOrBlank()) "$fileName (comment)" else fileName
  }

  suspend fun addQuoteToAgentContext(
    project: Project?,
    fileName: String,
    selectedText: String,
    userComment: String?,
    contextComponent: Component? = null,
    virtualFile: VirtualFile? = null,
  ): Boolean {
    if (project == null || project.isDisposed || selectedText.isBlank()) {
      LOG.trace {
        "markdown.webview.agentContext.addQuote.skipped - " +
        "project=${project?.name}, disposed=${project?.isDisposed}, selectedBlank=${selectedText.isBlank()}"
      }
      return false
    }
    val action = ActionManager.getInstance()?.getAction(ACTION_ID)
    if (action == null) {
      LOG.trace { "markdown.webview.agentContext.addQuote.skipped - actionNotFound=$ACTION_ID" }
      return false
    }
    val mentionPath = withContext(Dispatchers.IO) {
      readAction {
        if (project.isDisposed) fileName else resolveMentionPath(project, virtualFile, fileName)
      }
    }

    return withContext(Dispatchers.EDT) {
      if (project.isDisposed) {
        LOG.trace { "markdown.webview.agentContext.addQuote.skipped - projectDisposedOnEdt=${project.name}" }
        return@withContext false
      }

      val openTargets = collectOpenAirComposerTargets(project)
      LOG.trace {
        "markdown.webview.agentContext.addQuote - project=${project.name}, fileName=$fileName, " +
        "mentionPath=$mentionPath, openTargets=${openTargets.size}, " +
        "selectedChars=${selectedText.length}, hasComment=${!userComment.isNullOrBlank()}"
      }
      when {
        openTargets.size == 1 -> {
          appendQuoteToTarget(project, openTargets.single(), mentionPath, selectedText, userComment)
        }
        openTargets.size > 1 -> {
          showTargetChooserPopup(project, openTargets, mentionPath, selectedText, userComment, contextComponent)
          true
        }
        else -> {
          val chipBody = formatChipBody(mentionPath, selectedText, userComment)
          val componentName = formatComponentName(mentionPath, userComment)
          val sourceComponent = resolveSourceComponent(contextComponent, componentName)
          val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(PlatformCoreDataKeys.CONTEXT_COMPONENT, sourceComponent)
            .add(PlatformCoreDataKeys.SELECTED_ITEM, chipBody)
            .addNull(CommonDataKeys.EDITOR)
            .addNull(CommonDataKeys.HOST_EDITOR)
            .addNull(CommonDataKeys.VIRTUAL_FILE)
            .addNull(CommonDataKeys.VIRTUAL_FILE_ARRAY)
            .addNull(CommonDataKeys.PSI_FILE)
            .addNull(CommonDataKeys.PSI_ELEMENT)
            .addNull(PlatformDataKeys.TOOL_WINDOW)
            .build()

          val event = AnActionEvent.createEvent(
            action,
            dataContext,
            null,
            ACTION_PLACE,
            ActionUiKind.NONE,
            null,
          )
          val result = ActionUtil.performAction(action, event)
          val performed = result.isPerformed
          LOG.trace {
            "markdown.webview.agentContext.performActionFallback - actionId=$ACTION_ID, " +
            "mentionPath=$mentionPath, componentName=$componentName, " +
            "sourceComponent=${sourceComponent.javaClass.simpleName}, performed=$performed"
          }
          performed
        }
      }
    }
  }

  internal fun collectOpenAirComposerTargets(project: Project): List<AirComposerTarget> {
    if (project.isDisposed) return emptyList()
    val seenFields = Collections.newSetFromMap(IdentityHashMap<EditorTextField, Boolean>())
    val targets = ArrayList<AirComposerTarget>()

    val editorManager = FileEditorManager.getInstance(project)
    if (editorManager != null) {
      val selectedEditors = editorManager.selectedEditors.toSet()
      for (fileEditor in editorManager.allEditors) {
        val composerField = findAirComposerField(fileEditor.component)
                            ?: (fileEditor.preferredFocusedComponent as? EditorTextField)
                              ?.takeIf { it.name == AIR_SESSION_PROMPT_INPUT_NAME }
                            ?: continue
        if (!seenFields.add(composerField)) continue
        val editorFile = fileEditor.file
        val title = editorFile?.presentableName?.takeIf { it.isNotBlank() }
                    ?: fileEditor.name.takeIf { it.isNotBlank() }
                    ?: "Air Session"
        val isSelected = fileEditor in selectedEditors || UIUtil.isFocusAncestor(fileEditor.component)
        targets += AirComposerTarget(
          title = title,
          composerField = composerField,
          selected = isSelected,
          activate = {
            if (editorFile != null && editorManager.isFileOpen(editorFile)) {
              editorManager.openFile(editorFile, true)
            }
            IdeFocusManager.getInstance(project).requestFocusInProject(composerField, project)
          },
        )
      }
    }

    val toolWindowManager = ToolWindowManager.getInstance(project)
    for (toolWindowId in toolWindowManager.toolWindowIds) {
      val toolWindow = toolWindowManager.getToolWindow(toolWindowId) ?: continue
      val contentManager = toolWindow.contentManagerIfCreated ?: continue
      val selectedContent = contentManager.selectedContent
      for (content in contentManager.contents) {
        val composerField = findAirComposerField(content.component)
                            ?: (content.preferredFocusableComponent as? EditorTextField)
                              ?.takeIf { it.name == AIR_SESSION_PROMPT_INPUT_NAME }
                            ?: continue
        if (!seenFields.add(composerField)) continue
        val title = content.displayName?.takeIf { it.isNotBlank() }
                    ?: content.tabName?.takeIf { it.isNotBlank() }
                    ?: toolWindow.stripeTitle.takeIf { it.isNotBlank() }
                    ?: toolWindow.id
        val isSelected = (toolWindow.isVisible && content === selectedContent) || UIUtil.isFocusAncestor(content.component)
        targets += AirComposerTarget(
          title = title,
          composerField = composerField,
          selected = isSelected,
          activate = {
            if (contentManager.selectedContent !== content) {
              contentManager.setSelectedContent(content, true)
            }
            toolWindow.activate(
              { IdeFocusManager.getInstance(project).requestFocusInProject(composerField, project) },
              true,
            )
          },
        )
      }
    }

    val (selectedTargets, otherTargets) = targets.partition { it.selected }
    val orderedTargets = selectedTargets + otherTargets
    LOG.trace {
      "markdown.webview.agentContext.collectTargets - count=${orderedTargets.size}, " +
      "targets=${orderedTargets.joinToString { "${it.title}(selected=${it.selected})" }}"
    }
    return orderedTargets
  }

  internal fun appendQuoteToTarget(
    project: Project,
    target: AirComposerTarget,
    mentionPath: String,
    selectedText: String,
    userComment: String?,
  ): Boolean {
    val document = target.composerField.document
    val quoteText = formatComposerQuote(
      mentionPath = mentionPath,
      selectedText = selectedText,
      userComment = userComment,
      existingComposerText = document.charsSequence,
    )
    if (quoteText.isEmpty()) {
      LOG.trace {
        "markdown.webview.agentContext.appendQuoteToTarget.skipped - " +
        "target=${target.title}, mentionPath=$mentionPath, reason=emptyQuote"
      }
      return false
    }

    WriteCommandAction.runWriteCommandAction(project) {
      document.insertString(document.textLength, quoteText)
    }
    target.composerField.editor?.let { editor ->
      editor.caretModel.moveToOffset(document.textLength)
      editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
    }
    target.activate()
    LOG.trace {
      "markdown.webview.agentContext.appendQuoteToTarget - target=${target.title}, selected=${target.selected}, " +
      "mentionPath=$mentionPath, insertedChars=${quoteText.length}, documentLength=${document.textLength}"
    }
    return true
  }

  private fun showTargetChooserPopup(
    project: Project,
    targets: List<AirComposerTarget>,
    mentionPath: String,
    selectedText: String,
    userComment: String?,
    contextComponent: Component?,
  ) {
    val popup = JBPopupFactory.getInstance()
      .createPopupChooserBuilder(targets)
      .setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
      .setSelectedValue(targets.first(), true)
      .setRenderer(
        object : SimpleListCellRenderer<AirComposerTarget>() {
          override fun customize(
            list: javax.swing.JList<out AirComposerTarget>,
            value: AirComposerTarget?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
          ) {
            text = value?.title.orEmpty()
            icon = AllIcons.Toolwindows.ToolWindowMessages
          }
        }
      )
      .setItemChosenCallback { chosen ->
        if (!project.isDisposed) {
          LOG.trace {
            "markdown.webview.agentContext.targetChosen - target=${chosen.title}, mentionPath=$mentionPath"
          }
          appendQuoteToTarget(project, chosen, mentionPath, selectedText, userComment)
        }
      }
      .createPopup()

    val showingComponent = (contextComponent as? JComponent)?.takeIf { it.isShowing }
    LOG.trace {
      "markdown.webview.agentContext.showTargetChooserPopup - " +
      "targets=${targets.joinToString { it.title }}, showingOnComponent=${showingComponent != null}"
    }
    if (showingComponent != null) {
      popup.showInCenterOf(showingComponent)
    }
    else {
      val dataContext = SimpleDataContext.getProjectContext(project)
      popup.showInBestPositionFor(dataContext)
    }
  }

  private fun findAirComposerField(root: Component?): EditorTextField? {
    if (root == null) return null
    return UIUtil.findComponentsOfType(root as? JComponent ?: return null, EditorTextField::class.java)
      .firstOrNull { it.name == AIR_SESSION_PROMPT_INPUT_NAME }
  }

  internal fun resolveSourceComponent(contextComponent: Component?, componentName: String): Component {
    if (contextComponent != null && contextComponent.isShowing) {
      contextComponent.name = componentName
      return contextComponent
    }
    return object : JPanel() {
      override fun isShowing(): Boolean = true
    }.apply {
      name = componentName
    }
  }
}
