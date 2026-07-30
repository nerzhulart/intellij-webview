// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl

import java.awt.Color
import javax.swing.JComponent
import javax.swing.JWindow

internal fun setTransparent(window: JWindow) {
  window.rootPane?.isDoubleBuffered = false
  (window.contentPane as JComponent).isDoubleBuffered = false
  @Suppress("UseJBColor")
  window.background = Color(1, 1, 1, 0)
}
