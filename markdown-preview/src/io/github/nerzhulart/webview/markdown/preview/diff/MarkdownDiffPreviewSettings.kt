// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview.diff

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Remembers the rendered/text choice of the diff toolbar toggle, so a newly opened Markdown diff starts in the mode
 * the user selected last time.
 */
@Service(Service.Level.APP)
@State(
  name = "MarkdownDiffPreviewSettings",
  storages = [Storage("markdown-lens.xml")],
  category = SettingsCategory.UI,
)
internal class MarkdownDiffPreviewSettings :
  SimplePersistentStateComponent<MarkdownDiffPreviewSettings.State>(State()) {

  var isRenderedMode: Boolean
    get() = state.renderedMode
    set(value) {
      state.renderedMode = value
    }

  internal class State : BaseState() {
    var renderedMode: Boolean by property(false)
  }

  companion object {
    fun getInstance(): MarkdownDiffPreviewSettings = service()
  }
}
