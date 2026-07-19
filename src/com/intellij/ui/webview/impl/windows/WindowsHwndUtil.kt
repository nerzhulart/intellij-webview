// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.webview.impl.windows

import com.intellij.openapi.util.SystemInfoRt
import com.intellij.ui.webview.impl.WebViewLogger
import kotlinx.coroutines.CancellationException
import org.jetbrains.annotations.ApiStatus
import java.awt.Component
import javax.swing.SwingUtilities

@ApiStatus.Internal
internal object WindowsHwndUtil {
  fun resolveWindowHwnd(component: Component): Long? {
    if (!SystemInfoRt.isWindows) return null
    val window = SwingUtilities.getWindowAncestor(component) ?: return null
    return getHwnd(window)
  }

  fun resolveComponentHwnd(component: Component): Long? {
    if (!SystemInfoRt.isWindows) return null
    return getHwnd(component)
  }

  fun scale(component: Component): Double {
    return component.graphicsConfiguration?.defaultTransform?.scaleX?.takeIf { it > 0.0 } ?: 1.0
  }

  private fun getHwnd(component: Component): Long? {
    return try {
      val peerField = Component::class.java.getDeclaredField("peer")
      peerField.isAccessible = true
      val peer = peerField.get(component) ?: return null
      val getHWnd = peer.javaClass.methods.firstOrNull { it.name == "getHWnd" && it.parameterCount == 0 }
                    ?: return null
      getHWnd.invoke(peer) as? Long
    }
    catch (e: Exception) {
      if (e is CancellationException) throw e
      WebViewLogger.LOG.warn("Failed to resolve Windows HWND for WebView host", e)
      null
    }
  }
}
