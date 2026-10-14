// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl

import com.intellij.testFramework.runInEdtAndWait
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Color
import javax.swing.JPanel
import javax.swing.plaf.ColorUIResource

private const val FIRST_HOST_RGB = 0x123456
private const val SECOND_HOST_RGB = 0x654321

internal class WebViewHostBackgroundTest {

  @Test
  fun explicitHostBackgroundIsReportedAndFollowed() = withHostBackground { background, changes ->
    val host = JPanel().apply { this.background = Color(FIRST_HOST_RGB) }
    background.listenTo(host)

    assertEquals(listOf(opaque(FIRST_HOST_RGB)), changes)
    assertEquals(opaque(FIRST_HOST_RGB), background.argb)

    host.background = Color(SECOND_HOST_RGB)
    assertEquals(listOf(opaque(FIRST_HOST_RGB), opaque(SECOND_HOST_RGB)), changes)

    assertEquals(opaque(SECOND_HOST_RGB), background.refresh())
    assertEquals(2, changes.size, "an unchanged color is not reported again")
  }

  @Test
  fun lookAndFeelDefaultResolvesToCurrentPanelBackground() = withHostBackground { background, _ ->
    // A stale Look and Feel default left from a theme the host was detached through.
    val host = JPanel().apply { this.background = ColorUIResource(FIRST_HOST_RGB) }
    background.listenTo(host)

    assertEquals(opaque(UIUtil.getPanelBackground().rgb), background.argb)
  }

  @Test
  fun hostIsNotFollowedAfterStopListening() = withHostBackground { background, changes ->
    val host = JPanel().apply { this.background = Color(FIRST_HOST_RGB) }
    background.listenTo(host)
    background.stopListening()

    host.background = Color(SECOND_HOST_RGB)

    assertEquals(listOf(opaque(FIRST_HOST_RGB)), changes)
    assertEquals(opaque(SECOND_HOST_RGB), background.refresh(), "an explicit refresh still reads the host")
  }

  @Test
  fun hostCanBeGivenBeforeListening() = withHostBackground { background, changes ->
    val host = JPanel().apply { this.background = Color(FIRST_HOST_RGB) }

    assertEquals(opaque(FIRST_HOST_RGB), background.refresh(host))
    assertEquals(listOf(opaque(FIRST_HOST_RGB)), changes)
  }

  @Test
  fun translucentHostBackgroundBecomesOpaque() {
    @Suppress("UseJBColor")
    val translucent = Color(0x12, 0x34, 0x56, 0x40)
    assertEquals(opaque(FIRST_HOST_RGB), WebViewHostBackground.opaqueArgb(translucent))
  }

  private fun withHostBackground(action: (WebViewHostBackground, changes: List<Int>) -> Unit) {
    @Suppress("RAW_SCOPE_CREATION") // Test: no parent scope available without product code.
    val scope = CoroutineScope(SupervisorJob())
    try {
      runInEdtAndWait {
        val changes = mutableListOf<Int>()
        action(WebViewHostBackground(scope) { changes.add(it) }, changes)
      }
    }
    finally {
      scope.cancel()
    }
  }

  private fun opaque(rgb: Int): Int = 0xFF000000.toInt() or rgb
}
