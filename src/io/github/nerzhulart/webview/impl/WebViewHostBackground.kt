// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.asDisposable
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.annotations.ApiStatus
import java.awt.Color
import java.awt.Component
import java.beans.PropertyChangeListener
import javax.swing.plaf.UIResource

/**
 * The color a native WebView shows while it has no page frame of its own - before the first paint,
 * on a reattach, under a page without a background. It is the background of the panel that hosts
 * the view, so such a frame is indistinguishable from the host in a light theme as well as in a
 * dark one; a hard-coded color always flashes in one of them.
 *
 * The value is re-read on [listenTo], on a `background` change of the host, on a Look and Feel
 * change and on [refresh]; a changed one is passed to [onChanged] on the EDT. [argb] may be read
 * from any thread.
 *
 * @param scope engine scope; the Look and Feel subscription ends with it.
 * @param onChanged applies a changed color, as opaque `0xAARRGGBB`, to the native view.
 */
@ApiStatus.Internal
class WebViewHostBackground(scope: CoroutineScope, private val onChanged: (argb: Int) -> Unit) {
  /** The current host background as opaque `0xAARRGGBB`. */
  @Volatile
  var argb: Int = opaqueArgb(resolveHostBackground(null))
    private set

  /** The host [listenTo] got last; it stays the color source while the view is detached. EDT-only. */
  private var host: Component? = null

  private val hostListener = PropertyChangeListener { refresh() }

  init {
    // A theme switch repaints the page through the theme bridge; the native background has to
    // follow it, or the next frame without page content - a reattach, a reload - shows the old theme.
    ApplicationManager.getApplication()?.messageBus?.connect(scope.asDisposable())
      ?.subscribe(LafManagerListener.TOPIC, LafManagerListener { refresh() })
  }

  /** Starts following an explicitly set background of [host] and re-reads the color right away. */
  fun listenTo(host: Component) {
    stopListening()
    this.host = host
    host.addPropertyChangeListener(BACKGROUND_PROPERTY, hostListener)
    refresh()
  }

  /** Stops following the background of the host; [refresh] still reads it. */
  fun stopListening() {
    host?.removePropertyChangeListener(BACKGROUND_PROPERTY, hostListener)
  }

  /**
   * Re-reads the background of [host], passes it to [onChanged] when it changed and returns it.
   * [host] is the one from [listenTo] unless the caller knows the host before the view is attached.
   */
  fun refresh(host: Component? = this.host): Int {
    val newArgb = opaqueArgb(resolveHostBackground(host))
    if (newArgb != argb) {
      argb = newArgb
      onChanged(newArgb)
    }
    return argb
  }

  companion object {
    private const val BACKGROUND_PROPERTY = "background"

    /**
     * The background of the panel that hosts the view. A Look and Feel default is a [UIResource]
     * that only the next `updateUI` replaces, and a host detached during a theme switch misses that
     * call, so for it the current `Panel.background` is used - the value it is about to get anyway.
     */
    fun resolveHostBackground(host: Component?): Color {
      val explicit = host?.background
      return if (explicit == null || explicit is UIResource) UIUtil.getPanelBackground() else explicit
    }

    /** Native views take only a fully opaque default background, or a fully transparent one. */
    fun opaqueArgb(color: Color): Int = 0xFF000000.toInt() or (color.rgb and 0xFFFFFF)
  }
}
