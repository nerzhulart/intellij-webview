// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.DiffNotifications
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.base.TextDiffSettingsHolder.TextDiffSettings
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.tools.util.text.TwosideTextDiffProvider
import com.intellij.diff.util.DiffUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.Constraints
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.Wrapper
import com.intellij.diff.util.Side
import com.intellij.openapi.diagnostic.rethrowControlFlowException
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.nerzhulart.webview.markdown.preview.WebViewMarkdownPreviewPanel
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JPanel

private const val CONTENT_UPDATE_DELAY_MS = 200

/**
 * The place of the right part of the diff toolbar created by `DiffRequestProcessor`.
 */
private const val DIFF_RIGHT_TOOLBAR_PLACE = "DiffRightToolbar"

private val LOG = logger<MarkdownDiffPreviewController>()

internal class MarkdownDiffPreviewController private constructor(
  private val project: Project?,
  viewerComponent: JComponent,
  request: ContentDiffRequest,
  textSettings: TextDiffSettings,
  private val sides: MarkdownDiffSides,
) : Disposable {
  private val diffPanel: JPanel? = viewerComponent as? JPanel
  private val textComponent: Component? = (diffPanel?.layout as? BorderLayout)?.getLayoutComponent(BorderLayout.CENTER)

  private val notificationsPanel = Wrapper()
  private val renderedPanel = JPanel(BorderLayout()).apply {
    add(notificationsPanel, BorderLayout.NORTH)
  }

  private val contentUpdateAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

  private val textDiffProvider: TwosideTextDiffProvider? = if (sides.isTwoSide) {
    DiffUtil.createTextDiffProvider(project, request, textSettings, { scheduleChangesUpdate() }, this)
  }
  else {
    null
  }

  private var previews: Previews? = null

  private var changesRequestId: Int = 0

  private var isSyncingScroll: Boolean = false

  private val toggleAction = ToggleMarkdownDiffPreviewAction(this)

  private var rightToolbar: ActionToolbar? = null

  var fragments: List<LineFragment> = emptyList()
    private set

  var isRenderedMode: Boolean = false
    private set

  val isAvailable: Boolean
    get() = diffPanel != null && textComponent != null

  /**
   * Switches the mode on a user request, so the choice is remembered for the next opened Markdown diff.
   */
  fun setRenderedMode(rendered: Boolean) {
    MarkdownDiffPreviewSettings.getInstance().isRenderedMode = rendered
    applyRenderedMode(rendered)
  }

  private fun applyRenderedMode(rendered: Boolean) {
    if (isRenderedMode == rendered) return
    val diffPanel = diffPanel ?: return
    val textComponent = textComponent ?: return

    isRenderedMode = rendered

    if (rendered) {
      renderedPanel.add(previews().component, BorderLayout.CENTER)
      diffPanel.remove(textComponent)
      diffPanel.add(renderedPanel, BorderLayout.CENTER)
      updateContent()
      scheduleChangesUpdate()
    }
    else {
      diffPanel.remove(renderedPanel)
      diffPanel.add(textComponent, BorderLayout.CENTER)
    }
    diffPanel.revalidate()
    diffPanel.repaint()
  }

  fun setNotification(notification: JComponent?) {
    notificationsPanel.setContent(notification)
    renderedPanel.revalidate()
    renderedPanel.repaint()
  }

  private fun previews(): Previews {
    previews?.let { return it }

    val all = sides.present.map { createPreview(it) }
    val component = if (all.size == 2) {
      JBSplitter(false, "MarkdownDiffPreview.Splitter", 0.5f).apply {
        firstComponent = all[0].component
        secondComponent = all[1].component
      }
    }
    else {
      all.single().component
    }
    return Previews(all, component).also { previews = it }
  }

  private fun createPreview(side: MarkdownDiffSide): Preview {
    val panel = WebViewMarkdownPreviewPanel(project, side.virtualFile)
    Disposer.register(this, panel)
    panel.scrollListener = { line -> syncScroll(side.side, line) }
    panel.pageZoomListener = { zoom -> syncPageZoom(side.side, zoom) }

    val component = JPanel(BorderLayout()).apply {
      side.title?.let { title ->
        add(JBLabel(title).apply { border = JBUI.Borders.empty(2, 6) }, BorderLayout.NORTH)
      }
      add(panel.component, BorderLayout.CENTER)
    }
    return Preview(side, panel, component)
  }

  private fun scheduleContentUpdate() {
    if (!isRenderedMode) return
    contentUpdateAlarm.cancelAllRequests()
    contentUpdateAlarm.addRequest({ updateContent() }, CONTENT_UPDATE_DELAY_MS)
  }

  private fun updateContent() {
    if (!isRenderedMode) return
    val previews = previews ?: return
    for (preview in previews.all) {
      preview.panel.setHtml(preview.side.document.text, 0, preview.side.virtualFile)
    }
  }

  private fun scheduleChangesUpdate() {
    if (!isRenderedMode) return

    val textDiffProvider = textDiffProvider
    if (textDiffProvider == null) {
      applyWholeContentChange()
      return
    }

    val text1 = sides.left?.document?.text.orEmpty()
    val text2 = sides.right?.document?.text.orEmpty()
    val requestId = ++changesRequestId
    ApplicationManager.getApplication().executeOnPooledThread {
      val computed = try {
        textDiffProvider.compare(text1, text2, EmptyProgressIndicator())
      }
      catch (t: Throwable) {
        rethrowControlFlowException(t)
        LOG.warn("Failed to compute Markdown diff change blocks", t)
        null
      }
      contentUpdateAlarm.addRequest({ applyChanges(requestId, computed) }, 0)
    }
  }

  /**
   * The provider follows the `Highlighting` setting of the viewer: it fills the inner fragments of [computed] for the
   * by word and by symbol policies, which become inline changes of the blocks, and returns `null` for `Do not highlight`.
   * A `null` result, which a failed comparison gives as well, leaves the previews without changed blocks.
   */
  private fun applyChanges(requestId: Int, computed: List<LineFragment>?) {
    if (requestId != changesRequestId || !isRenderedMode) return

    fragments = computed.orEmpty()
    val previews = previews ?: return
    for (preview in previews.all) {
      preview.panel.setChangedBlocks(MarkdownDiffChangeBlocks.blocks(fragments, preview.side.side))
    }
    setNotification(if (computed != null && computed.isEmpty()) DiffNotifications.createEqualContents() else null)
  }

  /**
   * A file that was added or deleted as a whole has a single side, so its content is entirely new or entirely gone.
   */
  private fun applyWholeContentChange() {
    val previews = previews ?: return

    fragments = emptyList()
    for (preview in previews.all) {
      val blocks = MarkdownDiffChangeBlocks.wholeDocumentBlocks(preview.side.document.lineCount, preview.side.side)
      preview.panel.setChangedBlocks(blocks)
    }
    setNotification(null)
  }

  /**
   * The text viewer stays alive while hidden, so its own previous/next difference actions keep working in rendered mode
   * and move the caret of the hidden editor to the start line of the target change. The previews follow that caret.
   */
  private fun onEditorCaretMoved(side: Side, line: Int) {
    if (!isRenderedMode) return
    val previews = previews ?: return

    val fragment = fragments.find { MarkdownDiffScrollSynchronizer.changeStartLine(it, side) == line } ?: return
    for (preview in previews.all) {
      preview.panel.requestScrollToLine(MarkdownDiffScrollSynchronizer.changeStartLine(fragment, preview.side.side))
    }
  }

  private fun syncScroll(side: Side, line: Int) {
    if (isSyncingScroll) return
    val previews = previews ?: return

    val opposite = previews.of(side.other()) ?: return

    isSyncingScroll = true
    try {
      val oppositeLine = MarkdownDiffScrollSynchronizer.transferLine(fragments, side, line)
      opposite.panel.requestScrollToLine(oppositeLine)
    }
    finally {
      isSyncingScroll = false
    }
  }

  /**
   * The opposite preview applies the zoom without reporting it back, so the previews do not echo intermediate pinch zooms.
   * Its scroll position follows through the scroll synchronization once the zoom of the source preview moves its content.
   */
  private fun syncPageZoom(side: Side, zoom: Double) {
    val previews = previews ?: return
    previews.of(side.other())?.panel?.requestPageZoom(zoom)
  }

  private fun installDocumentListeners() {
    val listener = object : DocumentListener {
      override fun documentChanged(event: DocumentEvent) {
        scheduleContentUpdate()
        scheduleChangesUpdate()
      }
    }
    for (side in sides.present) {
      side.document.addDocumentListener(listener, this)
    }
  }

  private fun installCaretListeners(editors: Map<Side, Editor>) {
    for ((side, editor) in editors) {
      editor.caretModel.addCaretListener(object : CaretListener {
        override fun caretPositionChanged(event: CaretEvent) {
          onEditorCaretMoved(side, event.newPosition.line)
        }
      }, this)
    }
  }

  /**
   * The diff API has no extension point for the right toolbar, so the action is added to the toolbar group
   * after `DiffRequestProcessor` has built the toolbar for this viewer. The processor clears the group itself
   * when it switches to another request.
   */
  private fun installToggleAction(viewerComponent: JComponent) {
    val toolbar = findRightToolbar(viewerComponent)
    val group = toolbar?.actionGroup as? DefaultActionGroup
    if (toolbar == null || group == null) {
      LOG.warn("Markdown diff preview toggle is not installed: the diff right toolbar is not found")
      return
    }
    if (!group.containsAction(toggleAction)) {
      group.addAction(toggleAction, Constraints.FIRST)
    }
    rightToolbar = toolbar
    toolbar.updateActionsAsync()
  }

  private fun uninstallToggleAction() {
    val toolbar = rightToolbar ?: return
    rightToolbar = null
    val group = toolbar.actionGroup as? DefaultActionGroup ?: return
    if (group.containsAction(toggleAction)) {
      group.remove(toggleAction)
      toolbar.updateActionsAsync()
    }
  }

  override fun dispose() {
    uninstallToggleAction()
    applyRenderedMode(false)
  }

  private class Preview(
    val side: MarkdownDiffSide,
    val panel: WebViewMarkdownPreviewPanel,
    val component: JComponent,
  )

  private class Previews(
    val all: List<Preview>,
    val component: JComponent,
  ) {
    fun of(side: Side): Preview? = all.find { it.side.side == side }
  }

  companion object {
    fun install(
      viewer: DiffViewerBase,
      textSettings: TextDiffSettings,
      context: DiffContext,
      request: DiffRequest,
      sides: MarkdownDiffSides,
    ) {
      val contentRequest = request as? ContentDiffRequest ?: return
      val controller = MarkdownDiffPreviewController(context.project, viewer.component, contentRequest, textSettings, sides)
      if (!controller.isAvailable) {
        LOG.warn("Markdown diff preview is not installed: unexpected diff viewer component ${viewer.component.javaClass.name}")
        return
      }

      Disposer.register(viewer, controller)
      controller.installDocumentListeners()
      controller.installCaretListeners(editors(viewer, sides))
      // The viewer is created before the diff toolbar is built, so the toolbar is ready only on the next EDT event.
      ApplicationManager.getApplication().invokeLater(
        { controller.installToggleAction(viewer.component) },
        ModalityState.any(),
        { viewer.isDisposed },
      )

      if (MarkdownDiffPreviewSettings.getInstance().isRenderedMode) {
        controller.applyRenderedMode(true)
      }
    }

    private fun editors(viewer: DiffViewerBase, sides: MarkdownDiffSides): Map<Side, Editor> {
      return when (viewer) {
        is TwosideTextDiffViewer -> sides.present.associate { it.side to viewer.getEditor(it.side) }
        is OnesideTextDiffViewer -> mapOf(sides.present.single().side to viewer.editor)
        else -> emptyMap()
      }
    }

    /**
     * `DiffRequestProcessor` puts its toolbar and the viewer into the same main panel, so the right toolbar of this
     * viewer is the one in the nearest parent that has any. Other diff processors in the same window are further away.
     */
    private fun findRightToolbar(viewerComponent: JComponent): ActionToolbar? {
      return UIUtil.uiParents(viewerComponent, true).firstNotNullOfOrNull { parent ->
        UIUtil.uiTraverser(parent)
          .filter(ActionToolbar::class.java)
          .find { it.place == DIFF_RIGHT_TOOLBAR_PLACE }
      }
    }
  }
}
