// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview.diff

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.actionSystem.ToggleAction
import io.github.nerzhulart.webview.markdown.preview.MarkdownWebViewPreviewBundle

internal class ToggleMarkdownDiffPreviewAction(
  private val controller: MarkdownDiffPreviewController,
) : ToggleAction(
  MarkdownWebViewPreviewBundle.message("markdown.diff.preview.toggle.action.text"),
  MarkdownWebViewPreviewBundle.message("markdown.diff.preview.toggle.action.description"),
  AllIcons.Actions.PreviewDetails,
), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

  override fun isSelected(e: AnActionEvent): Boolean = controller.isRenderedMode

  override fun setSelected(e: AnActionEvent, state: Boolean) {
    controller.setRenderedMode(state)
  }
}
