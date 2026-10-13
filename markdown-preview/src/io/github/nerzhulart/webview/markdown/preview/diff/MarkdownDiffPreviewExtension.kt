// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.util.registry.Registry

internal const val MARKDOWN_DIFF_PREVIEW_REGISTRY_KEY: String = "markdown.webview.diff.preview.enabled"

private val LOG = logger<MarkdownDiffPreviewExtension>()

class MarkdownDiffPreviewExtension : DiffExtension() {
  override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
    if (!Registry.`is`(MARKDOWN_DIFF_PREVIEW_REGISTRY_KEY, true)) return
    val sides = MarkdownDiffSides.from(request) ?: return

    val textSettings = when (viewer) {
      is TwosideTextDiffViewer -> if (sides.isTwoSide) viewer.textSettings else return
      is OnesideTextDiffViewer -> if (sides.isTwoSide) return else viewer.textSettings
      else -> return
    }
    val viewerBase = viewer as? DiffViewerBase ?: return

    LOG.trace {
      "markdown.webview.diff.detected - viewer=${viewerBase.javaClass.simpleName}, " +
      "file=${sides.present.first().virtualFile.name}, twoSide=${sides.isTwoSide}"
    }
    MarkdownDiffPreviewController.install(viewerBase, textSettings, context, request, sides)
  }
}
