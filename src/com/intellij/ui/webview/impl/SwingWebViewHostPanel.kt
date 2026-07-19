// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.webview.impl

import com.intellij.ide.KeyboardAwareFocusOwner
import com.intellij.openapi.util.Disposer
import com.intellij.ui.webview.impl.engine.WebViewFocusDirection
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.EDT
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.annotations.ApiStatus
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Graphics
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.ContainerAdapter
import java.awt.event.ContainerEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyBoundsListener
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities

/**
 * Swing host panel that mounts the selected [WebViewController].
 *
 * The native WebView is attached in [addNotify] when the panel joins a displayable Swing
 * hierarchy, and detached in [removeNotify] when the panel is removed. The first native show
 * is delayed until Swing has a showing, non-empty host rectangle. Resize and
 * visibility events are forwarded to the native view with coalescing to avoid redundant native calls.
 *
 * **Threading**: Must be created and used on the EDT. The [scope] is used for
 * coroutine-based lifecycle management; native calls are internally dispatched
 * to the owning native UI thread.
 */
@ApiStatus.Internal
internal class SwingWebViewHostPanel(
  val scope: CoroutineScope,
  private val controller: WebViewController,
  private val focusEntrySink: WebViewFocusEntrySink? = null,
) : JPanel(BorderLayout()), SwingWebViewHost, KeyboardAwareFocusOwner {

  internal data class NativeFrame(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
  )

  internal data class NativeBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
  )

  private data class ModifierKeyEventSnapshot(
    val keyCode: Int,
    val eventId: Int,
    val nanoTime: Long,
  )

  internal companion object {

    fun calculateNativeFrame(host: Component, anchor: Component): NativeFrame {
      val hostOrigin = SwingUtilities.convertPoint(host, 0, 0, anchor)
      val width = host.width.toDouble()
      val height = host.height.toDouble()
      val flippedY = anchor.height.toDouble() - hostOrigin.y.toDouble() - height

      return NativeFrame(
        x = hostOrigin.x.toDouble(),
        y = flippedY,
        width = width,
        height = height,
      )
    }

    fun calculateWindowsBounds(host: Component, anchor: Component): NativeBounds {
      val hostOrigin = SwingUtilities.convertPoint(host, 0, 0, anchor)
      val visibleClip = calculateVisibleClip(host, anchor, hostOrigin)
      return NativeBounds(
        x = visibleClip.left,
        y = visibleClip.top,
        width = (visibleClip.right - visibleClip.left).coerceAtLeast(0),
        height = (visibleClip.bottom - visibleClip.top).coerceAtLeast(0),
      )
    }

    private data class VisibleClip(
      val left: Int,
      val top: Int,
      val right: Int,
      val bottom: Int,
    )

    private fun calculateVisibleClip(host: Component, anchor: Component, hostOrigin: Point): VisibleClip {
      var left = hostOrigin.x
      var top = hostOrigin.y
      var right = hostOrigin.x + host.width
      var bottom = hostOrigin.y + host.height

      for (component in host.selfAndAncestorsUntil(anchor)) {
        val parent = component.parent ?: continue
        val parentOrigin = SwingUtilities.convertPoint(parent, 0, 0, anchor)
        if (!parent.isWindowsRootBoundary(anchor)) {
          left = maxOf(left, parentOrigin.x)
          top = maxOf(top, parentOrigin.y)
          right = minOf(right, parentOrigin.x + parent.width)
          bottom = minOf(bottom, parentOrigin.y + parent.height)
        }

      }
      return VisibleClip(left, top, right, bottom)
    }

    private fun Component.selfAndAncestorsUntil(anchor: Component): Sequence<Component> {
      return generateSequence(this) { component -> component.parent }
        .takeWhile { component -> component !== anchor }
    }

    private fun Component.isWindowsRootBoundary(anchor: Component): Boolean {
      if (this === anchor) return true
      return anchor is JRootPane && (this === anchor.contentPane || this === anchor.layeredPane || this === anchor.glassPane)
    }

    internal fun resolveAnchor(component: Component): Component? {
      val window = SwingUtilities.getWindowAncestor(component) ?: return null
      return if (window is RootPaneContainer) window.contentPane else window
    }

    internal fun resolveWindowsAnchor(component: Component): Component? {
      val window = SwingUtilities.getWindowAncestor(component) ?: return null
      return if (window is RootPaneContainer) window.rootPane else window
    }

    internal fun hasNonEmptyClippedBounds(host: Component): Boolean {
      val anchor = resolveWindowsAnchor(host) ?: return false
      val bounds = calculateWindowsBounds(host, anchor)
      return bounds.width > 0 && bounds.height > 0
    }
  }

  override val component: JComponent
    get() = this

  override fun skipKeyEventDispatcher(event: KeyEvent): Boolean {
    val policy = controller.editShortcutPolicy
    if (policy == WebViewEditShortcutPolicy.NONE || !focusInsideHost) return false

    val command = WebViewEditCommand.matchingCommand(event.keyCode, event.modifiersEx, WebViewEditCommand.DEFAULTS) ?: return false
    // Returning true only keeps the IDE dispatcher out of this shortcut. The backend policy decides
    // whether the original native event path handles it or an explicit native command is required.
    if (policy == WebViewEditShortcutPolicy.HANDLE_IN_NATIVE_PEER && controller.handleEditShortcut(event, command)) {
      event.consume()
    }
    return true
  }

  private var hierarchyListener: HierarchyListener? = null
  private var hierarchyBoundsListener: HierarchyBoundsListener? = null
  private var ancestorContainerListener: ContainerAdapter? = null
  private val ancestorContainersWithListener = ArrayList<Container>()
  private var listenersInstalled = false
  private var snapshotImage: BufferedImage? = null
  private var focusInsideHost = false
  private var focusSyncInProgress = false
  private var heavyweightRegistration: com.intellij.openapi.Disposable? = null
  private var lastHostModifierKeyEvent = ModifierKeyEventSnapshot(0, 0, 0L)

  private val hostModifierKeyListener = object : KeyAdapter() {
    override fun keyPressed(e: KeyEvent) = rememberHostModifierKeyEvent(e)
    override fun keyReleased(e: KeyEvent) = rememberHostModifierKeyEvent(e)
  }

  private val controllerFocusListener = object : FocusAdapter() {
    override fun focusGained(e: FocusEvent) = handleSwingFocusGained(e)
    override fun focusLost(e: FocusEvent) = handleSwingFocusLost(e)
  }

  private fun handleSwingFocusGained(e: FocusEvent) {
    if (e.isTemporary) return
    val wasFocusInside = focusInsideHost
    focusInsideHost = true
    if (wasFocusInside || focusSyncInProgress) return

    focusSyncInProgress = true
    try {
      controller.requestWebViewFocus()
      e.cause.toWebViewFocusDirection()?.let { focusEntrySink?.enterWebViewFocus(it) }
    }
    finally {
      focusSyncInProgress = false
    }
  }

  private fun handleSwingFocusLost(e: FocusEvent) {
    if (e.isTemporary || containsFocusComponent(e.oppositeComponent)) return
    deactivateWebView(e.oppositeComponent)
  }

  init {
    // Native heavyweight WebViews cover the panel; painting the default grey
    // Panel.background would flash through transient gaps during live resize.
    isOpaque = false
    isFocusable = false
    isRequestFocusEnabled = false
    controller.component.addFocusListener(controllerFocusListener)
    controller.component.addKeyListener(hostModifierKeyListener)
    add(controller.component, BorderLayout.CENTER)
  }

  private val resizeListener = object : ComponentAdapter() {
    override fun componentResized(e: ComponentEvent) = syncHostLayoutFromSwing()
    override fun componentMoved(e: ComponentEvent) = syncHostLayoutFromSwing()
    override fun componentShown(e: ComponentEvent) = syncHostLayoutFromSwing()
    override fun componentHidden(e: ComponentEvent) = syncHostLayoutFromSwing()
  }

  @RequiresEdt
  override fun addNotify() {
    super.addNotify()
    installListeners()
    ensureHeavyweightRegistered()
    syncHostLayoutFromSwing()
  }

  @RequiresEdt
  override fun removeNotify() {
    unregisterHeavyweight()
    syncHostLayoutFromSwing()
    uninstallListeners()
    super.removeNotify()
  }

  @RequiresEdt
  @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
  override fun reshape(x: Int, y: Int, w: Int, h: Int) {
    super.reshape(x, y, w, h)
    syncHostLayoutFromSwing()
  }

  @RequiresEdt
  override fun paintComponent(g: Graphics) {
    super.paintComponent(g)
    val image = snapshotImage ?: return
    g.drawImage(image, 0, 0, width, height, null)
  }

  @RequiresEdt
  private fun syncHostLayoutFromSwing() {
    controller.applyLayout(createHostLayoutParams())
    notifyHeavyweightChanged()
  }

  @RequiresEdt
  private fun ensureHeavyweightRegistered() {
    if (heavyweightRegistration != null || !isDisplayable) return
    heavyweightRegistration = WebViewHeavyweightHostRegistry.register(this)
  }

  @RequiresEdt
  private fun unregisterHeavyweight() {
    heavyweightRegistration?.let { Disposer.dispose(it) }
    heavyweightRegistration = null
  }

  @RequiresEdt
  private fun notifyHeavyweightChanged() {
    heavyweightRegistration?.let {
      WebViewHeavyweightHostRegistry.componentChanged(this)
    }
  }

  private fun createHostLayoutParams(): WebViewHostLayoutParams {
    val anchor = resolveWindowsAnchor(this)
    val bounds = if (anchor == null) {
      Rectangle()
    }
    else {
      val origin = SwingUtilities.convertPoint(this, 0, 0, anchor)
      Rectangle(origin.x, origin.y, width, height)
    }
    val clippedBounds = if (anchor == null) {
      Rectangle()
    }
    else {
      val nativeBounds = calculateWindowsBounds(this, anchor)
      Rectangle(nativeBounds.x, nativeBounds.y, nativeBounds.width, nativeBounds.height)
    }
    val scale = graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
    return WebViewHostLayoutParams(
      displayable = controller.component.isDisplayable,
      showing = controller.component.isShowing,
      boundsInWindow = bounds,
      clippedBoundsInWindow = clippedBounds,
      scale = scale,
    )
  }

  override fun requestWebViewFocus() {
    if (containsFocusComponent(KeyboardFocusManager.getCurrentKeyboardFocusManager().permanentFocusOwner)) {
      controller.requestWebViewFocus()
    }
    else {
      controller.component.requestFocusInWindow()
    }
  }

  override fun clearWebViewFocus() {
    deactivateWebView(KeyboardFocusManager.getCurrentKeyboardFocusManager().permanentFocusOwner)
  }

  internal fun handleHostEvent(event: WebViewHostEvent): Boolean {
    if (!EDT.isCurrentThreadEdt() && event !is WebViewHostEvent.MoveFocusRequested) {
      SwingUtilities.invokeLater {
        handleHostEvent(event)
      }
      return true
    }
    if (!EDT.isCurrentThreadEdt()) {
      var handled = false
      SwingUtilities.invokeAndWait {
        handled = handleHostEvent(event)
      }
      return handled
    }

    return when (event) {
      WebViewHostEvent.NativeFocusGained -> {
        if (!focusSyncInProgress && !containsFocusComponent(KeyboardFocusManager.getCurrentKeyboardFocusManager().permanentFocusOwner)) {
          focusSyncInProgress = true
          controller.component.requestFocusInWindow()
          SwingUtilities.invokeLater {
            focusSyncInProgress = false
          }
        }
        true
      }
      is WebViewHostEvent.MoveFocusRequested -> {
        if (isShowing) {
          val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
          when (event.direction) {
            WebViewFocusDirection.FORWARD -> focusManager.focusNextComponent(controller.component)
            WebViewFocusDirection.BACKWARD -> focusManager.focusPreviousComponent(controller.component)
          }
        }
        true
      }
    }
  }

  private fun rememberHostModifierKeyEvent(event: KeyEvent) {
    if ((event.id == KeyEvent.KEY_PRESSED || event.id == KeyEvent.KEY_RELEASED) &&
        (event.keyCode == KeyEvent.VK_SHIFT || event.keyCode == KeyEvent.VK_CONTROL)) {
      lastHostModifierKeyEvent = ModifierKeyEventSnapshot(event.keyCode, event.id, System.nanoTime())
    }
  }

  private fun installListeners() {
    if (listenersInstalled) return
    addComponentListener(resizeListener)
    val listener = HierarchyListener { e ->
      if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
        syncHostLayoutFromSwing()
      }
    }
    hierarchyListener = listener
    addHierarchyListener(listener)
    val boundsListener = object : HierarchyBoundsAdapter() {
      override fun ancestorMoved(e: HierarchyEvent) = syncHostLayoutFromSwing()
      override fun ancestorResized(e: HierarchyEvent) = syncHostLayoutFromSwing()
    }
    hierarchyBoundsListener = boundsListener
    addHierarchyBoundsListener(boundsListener)
    installAncestorContainerListeners()
    listenersInstalled = true
  }

  private fun uninstallListeners() {
    if (!listenersInstalled) return
    removeComponentListener(resizeListener)
    hierarchyListener?.let {
      removeHierarchyListener(it)
      hierarchyListener = null
    }
    hierarchyBoundsListener?.let {
      removeHierarchyBoundsListener(it)
      hierarchyBoundsListener = null
    }
    uninstallAncestorContainerListeners()
    listenersInstalled = false
  }

  private fun installAncestorContainerListeners() {
    val listener = object : ContainerAdapter() {
      override fun componentAdded(e: ContainerEvent) = syncHostLayoutFromSwing()
      override fun componentRemoved(e: ContainerEvent) = syncHostLayoutFromSwing()
    }
    ancestorContainerListener = listener
    generateSequence(this as Component?) { it.parent }
      .filterIsInstance<Container>()
      .forEach { container ->
        container.addContainerListener(listener)
        ancestorContainersWithListener.add(container)
      }
  }

  private fun uninstallAncestorContainerListeners() {
    val listener = ancestorContainerListener ?: return
    ancestorContainersWithListener.forEach { container ->
      container.removeContainerListener(listener)
    }
    ancestorContainersWithListener.clear()
    ancestorContainerListener = null
  }

  private fun deactivateWebView(newOwner: Component?) {
    val wasFocused = focusInsideHost
    focusInsideHost = false
    if (wasFocused) {
      focusEntrySink?.leaveWebViewFocus()
    }
    focusSyncInProgress = true
    try {
      val hostWindow = SwingUtilities.getWindowAncestor(this)
      val sameWindow = hostWindow != null && newOwner != null && SwingUtilities.getWindowAncestor(newOwner) == hostWindow
      controller.swingFocusMovedOutside(WebViewSwingFocusExit(newOwner, sameWindow))
    }
    finally {
      focusSyncInProgress = false
    }
  }

  private fun containsFocusComponent(component: Component?): Boolean {
    return component === this || component != null && SwingUtilities.isDescendingFrom(component, this)
  }

  private fun FocusEvent.Cause.toWebViewFocusDirection(): WebViewFocusDirection? {
    return when (this) {
      FocusEvent.Cause.TRAVERSAL_FORWARD -> WebViewFocusDirection.FORWARD
      FocusEvent.Cause.TRAVERSAL_BACKWARD -> WebViewFocusDirection.BACKWARD
      else -> null
    }
  }

}
