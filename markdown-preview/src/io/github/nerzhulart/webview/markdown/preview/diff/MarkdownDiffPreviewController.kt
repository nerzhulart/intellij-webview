// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.fragments.LineFragment
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.tools.util.DiffNotifications
import com.intellij.diff.tools.util.PrevNextDifferenceIterable
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.base.TextDiffSettingsHolder.TextDiffSettings
import com.intellij.diff.tools.util.text.TwosideTextDiffProvider
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.DiffUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiCompatibleDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
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
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import io.github.nerzhulart.webview.markdown.preview.WebViewMarkdownPreviewPanel
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JPanel

private const val RENDERED_MODE_PROPERTY = "markdown.webview.diff.preview.rendered"
private const val CONTENT_UPDATE_DELAY_MS = 200

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
  private val renderedPanel = RenderedPanel().apply {
    add(notificationsPanel, BorderLayout.NORTH)
  }

  private val differenceIterable = MarkdownDiffDifferenceIterable()

  private val contentUpdateAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

  private val textDiffProvider: TwosideTextDiffProvider? = if (sides.isTwoSide) {
    DiffUtil.createTextDiffProvider(project, request, textSettings, { scheduleChangesUpdate() }, this)
  }
  else {
    null
  }

  private var previews: Previews? = null

  private var changesRequestId: Int = 0

  private var currentChangeIndex: Int = -1

  private var isSyncingScroll: Boolean = false

  var fragments: List<LineFragment> = emptyList()
    private set

  var isRenderedMode: Boolean = false
    private set

  val isAvailable: Boolean
    get() = diffPanel != null && textComponent != null

  fun setRenderedMode(rendered: Boolean) {
    if (isRenderedMode == rendered) return
    val diffPanel = diffPanel ?: return
    val textComponent = textComponent ?: return

    isRenderedMode = rendered
    properties()?.setValue(RENDERED_MODE_PROPERTY, rendered)

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
      catch (e: ProcessCanceledException) {
        null
      }
      catch (t: Throwable) {
        LOG.warn("Failed to compute Markdown diff change blocks", t)
        null
      }
      contentUpdateAlarm.addRequest({ applyChanges(requestId, computed) }, 0)
    }
  }

  private fun applyChanges(requestId: Int, computed: List<LineFragment>?) {
    if (requestId != changesRequestId || !isRenderedMode) return

    fragments = computed.orEmpty()
    currentChangeIndex = -1
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
    currentChangeIndex = -1
    for (preview in previews.all) {
      val blocks = MarkdownDiffChangeBlocks.wholeDocumentBlocks(preview.side.document.lineCount, preview.side.side)
      preview.panel.setChangedBlocks(blocks)
    }
    setNotification(null)
  }

  private fun scrollToChange(index: Int) {
    val fragment = fragments.getOrNull(index) ?: return
    val previews = previews ?: return

    currentChangeIndex = index
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

  private fun properties(): PropertiesComponent? {
    return if (project != null) PropertiesComponent.getInstance(project) else PropertiesComponent.getInstance()
  }

  override fun dispose() {
    if (isRenderedMode) {
      setRenderedMode(false)
    }
  }

  private inner class RenderedPanel : JPanel(BorderLayout()), UiCompatibleDataProvider {
    override fun uiDataSnapshot(sink: DataSink) {
      sink[DiffDataKeys.PREV_NEXT_DIFFERENCE_ITERABLE] = differenceIterable
    }
  }

  private inner class MarkdownDiffDifferenceIterable : PrevNextDifferenceIterable {
    override fun canGoPrev(): Boolean = fragments.isNotEmpty() && currentChangeIndex != 0

    override fun canGoNext(): Boolean = fragments.isNotEmpty() && currentChangeIndex < fragments.lastIndex

    override fun goPrev() {
      val index = if (currentChangeIndex <= 0) fragments.lastIndex else currentChangeIndex - 1
      scrollToChange(index)
    }

    override fun goNext() {
      scrollToChange((currentChangeIndex + 1).coerceAtMost(fragments.lastIndex))
    }
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
      installToggleAction(request, controller)

      if (controller.properties()?.getBoolean(RENDERED_MODE_PROPERTY, false) == true) {
        controller.setRenderedMode(true)
      }
    }

    private fun installToggleAction(request: DiffRequest, controller: MarkdownDiffPreviewController) {
      val actions = request.getUserData(DiffUserDataKeys.CONTEXT_ACTIONS)
        .orEmpty()
        .filterNot { it is ToggleMarkdownDiffPreviewAction }
      request.putUserData(
        DiffUserDataKeys.CONTEXT_ACTIONS,
        actions + listOf<AnAction>(ToggleMarkdownDiffPreviewAction(controller)),
      )
    }
  }
}
