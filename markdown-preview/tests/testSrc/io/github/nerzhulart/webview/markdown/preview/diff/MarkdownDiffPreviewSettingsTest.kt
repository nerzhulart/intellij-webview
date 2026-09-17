// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview.diff

import com.intellij.testFramework.fixtures.BasePlatformTestCase

internal class MarkdownDiffPreviewSettingsTest : BasePlatformTestCase() {
  override fun tearDown() {
    try {
      MarkdownDiffPreviewSettings.getInstance().isRenderedMode = false
    }
    finally {
      super.tearDown()
    }
  }

  fun `test text mode is used by default`() {
    assertFalse(MarkdownDiffPreviewSettings().isRenderedMode)
    assertFalse(MarkdownDiffPreviewSettings.getInstance().isRenderedMode)
  }

  fun `test rendered mode is remembered by the application service`() {
    val settings = MarkdownDiffPreviewSettings.getInstance()

    settings.isRenderedMode = true

    assertTrue(MarkdownDiffPreviewSettings.getInstance().isRenderedMode)

    settings.isRenderedMode = false

    assertFalse(MarkdownDiffPreviewSettings.getInstance().isRenderedMode)
  }

  fun `test rendered mode survives a state round trip`() {
    val saved = MarkdownDiffPreviewSettings().apply { isRenderedMode = true }

    val restored = MarkdownDiffPreviewSettings().apply { loadState(saved.state) }

    assertTrue(restored.isRenderedMode)
  }
}
