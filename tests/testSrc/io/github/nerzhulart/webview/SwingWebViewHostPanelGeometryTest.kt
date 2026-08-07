// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview

import io.github.nerzhulart.webview.api.WebViewAssetPath
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.impl.engine.WebViewFocusDirection
import io.github.nerzhulart.webview.impl.SwingWebViewHostPanel
import io.github.nerzhulart.webview.impl.WebViewFocusEntrySink
import io.github.nerzhulart.webview.impl.WebViewJsMessageReceiver
import io.github.nerzhulart.webview.impl.engine.WebViewEngine
import io.github.nerzhulart.webview.impl.mac.MacNativeLayout
import io.github.nerzhulart.webview.impl.mac.calculateMacNativeLayout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Dimension
import java.awt.event.FocusEvent
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.Disabled
import javax.swing.JComponent
import javax.swing.JRootPane
import javax.swing.JPanel
import javax.swing.SwingUtilities

class SwingWebViewHostPanelGeometryTest {
  companion object {
    @JvmStatic
    @BeforeAll
    fun initializeFocusToolkit() {
      // Wayland starts its persistent keyboard-repeat thread on the first focus request.
      SwingUtilities.invokeAndWait { JPanel().requestFocusInWindow() }
    }
  }

  @Test
  fun calculateHostBounds_usesAnchorCoordinatesForNestedHost() {
    val fakeWindow = JPanel(null).apply {
      size = Dimension(500, 340)
    }
    val contentPane = JPanel(null).apply {
      setBounds(0, 24, 500, 300)
    }
    fakeWindow.add(contentPane)

    val topFiller = JPanel().apply {
      setBounds(0, 0, 500, 40)
    }
    val centerPanel = JPanel(null).apply {
      setBounds(0, 40, 500, 220)
    }
    val bottomFiller = JPanel().apply {
      setBounds(0, 260, 500, 40)
    }
    contentPane.add(topFiller)
    contentPane.add(centerPanel)
    contentPane.add(bottomFiller)

    val nestedPanel = JPanel(null).apply {
      setBounds(30, 12, 260, 140)
    }
    centerPanel.add(nestedPanel)

    val host = JPanel().apply {
      setBounds(17, 9, 123, 67)
    }
    nestedPanel.add(host)

    val bounds = SwingWebViewHostPanel.calculateHostBounds(host, contentPane)
    assertEquals(SwingWebViewHostPanel.NativeBounds(47, 61, 123, 67), bounds)

    val hostOriginInWindow = SwingUtilities.convertPoint(host, 0, 0, fakeWindow)
    assertEquals(85, hostOriginInWindow.y)
    assertNotEquals(bounds.y, hostOriginInWindow.y)
  }

  @Test
  fun calculateMacNativeLayout_usesFullFrameWhenUnclipped() {
    val bounds = SwingWebViewHostPanel.NativeBounds(47, 61, 123, 67)

    val layout = calculateMacNativeLayout(bounds, bounds, anchorHeight = 300)

    assertEquals(
      MacNativeLayout(
        containerFrame = SwingWebViewHostPanel.NativeFrame(47.0, 172.0, 123.0, 67.0),
        webViewFrame = SwingWebViewHostPanel.NativeFrame(0.0, 0.0, 123.0, 67.0),
      ),
      layout,
    )
    assertTrue(layout.hasVisibleBounds)
  }

  @Test
  fun calculateMacNativeLayout_clipsLeftAndTop() {
    val layout = calculateMacNativeLayout(
      fullBounds = SwingWebViewHostPanel.NativeBounds(5, 10, 100, 80),
      clippedBounds = SwingWebViewHostPanel.NativeBounds(20, 25, 85, 65),
      anchorHeight = 200,
    )

    assertEquals(SwingWebViewHostPanel.NativeFrame(20.0, 110.0, 85.0, 65.0), layout.containerFrame)
    assertEquals(SwingWebViewHostPanel.NativeFrame(-15.0, 0.0, 100.0, 80.0), layout.webViewFrame)
  }

  @Test
  fun calculateMacNativeLayout_clipsRightAndBottomWithNegativeChildOffset() {
    val layout = calculateMacNativeLayout(
      fullBounds = SwingWebViewHostPanel.NativeBounds(30, 220, 500, 100),
      clippedBounds = SwingWebViewHostPanel.NativeBounds(30, 260, 470, 40),
      anchorHeight = 340,
    )

    assertEquals(SwingWebViewHostPanel.NativeFrame(30.0, 40.0, 470.0, 40.0), layout.containerFrame)
    assertEquals(SwingWebViewHostPanel.NativeFrame(0.0, -20.0, 500.0, 100.0), layout.webViewFrame)
  }

  @Test
  fun calculateMacNativeLayout_marksEmptyClipAsHidden() {
    val layout = calculateMacNativeLayout(
      fullBounds = SwingWebViewHostPanel.NativeBounds(20, 30, 100, 80),
      clippedBounds = SwingWebViewHostPanel.NativeBounds(120, 30, 0, 80),
      anchorHeight = 300,
    )

    assertFalse(layout.hasVisibleBounds)
  }

  @Test
  fun calculateMacNativeLayout_acceptsHostPartiallyOutsideWindow() {
    val layout = calculateMacNativeLayout(
      fullBounds = SwingWebViewHostPanel.NativeBounds(-20, -10, 100, 80),
      clippedBounds = SwingWebViewHostPanel.NativeBounds(0, 0, 80, 70),
      anchorHeight = 300,
    )

    assertEquals(SwingWebViewHostPanel.NativeFrame(0.0, 230.0, 80.0, 70.0), layout.containerFrame)
    assertEquals(SwingWebViewHostPanel.NativeFrame(-20.0, 0.0, 100.0, 80.0), layout.webViewFrame)
    assertTrue(layout.hasVisibleBounds)
  }

  @Test
  fun calculateMacNativeLayout_keepsLogicalCoordinatesWithoutHiDpiScaling() {
    val layout = calculateMacNativeLayout(
      fullBounds = SwingWebViewHostPanel.NativeBounds(11, 13, 101, 79),
      clippedBounds = SwingWebViewHostPanel.NativeBounds(17, 19, 89, 67),
      anchorHeight = 257,
    )

    assertEquals(SwingWebViewHostPanel.NativeFrame(17.0, 171.0, 89.0, 67.0), layout.containerFrame)
    assertEquals(SwingWebViewHostPanel.NativeFrame(-6.0, -6.0, 101.0, 79.0), layout.webViewFrame)
  }

  @Test
  fun calculateClippedBounds_usesTopLeftWindowClientCoordinatesForNestedHost() {
    val rootPane = JPanel(null).apply {
      size = Dimension(500, 340)
    }
    val toolbar = JPanel().apply {
      setBounds(0, 0, 500, 40)
    }
    val contentPane = JPanel(null).apply {
      setBounds(0, 40, 500, 300)
    }
    rootPane.add(toolbar)
    rootPane.add(contentPane)

    val centerPanel = JPanel(null).apply {
      setBounds(0, 40, 500, 220)
    }
    contentPane.add(centerPanel)

    val nestedPanel = JPanel(null).apply {
      setBounds(30, 12, 260, 140)
    }
    centerPanel.add(nestedPanel)

    val host = JPanel().apply {
      setBounds(17, 9, 123, 67)
    }
    nestedPanel.add(host)

    val bounds = SwingWebViewHostPanel.calculateClippedBounds(host, rootPane)
    assertEquals(SwingWebViewHostPanel.NativeBounds(47, 101, 123, 67), bounds)
  }

  @Test
  fun calculateClippedBounds_doesNotClipToAnchorBounds() {
    val rootPane = JPanel(null).apply {
      size = Dimension(220, 320)
    }
    val host = JPanel().apply {
      setBounds(20, 40, 300, 200)
    }
    rootPane.add(host)

    val bounds = SwingWebViewHostPanel.calculateClippedBounds(host, rootPane)
    assertEquals(SwingWebViewHostPanel.NativeBounds(20, 40, 300, 200), bounds)
  }

  @Test
  fun calculateClippedBounds_doesNotClipToRootPaneContentBounds() {
    val contentPane = JPanel(null).apply {
      setBounds(0, 0, 220, 320)
    }
    val rootPane = JRootPane().apply {
      this.contentPane = contentPane
      size = Dimension(220, 320)
    }
    val host = JPanel().apply {
      setBounds(20, 40, 300, 200)
    }
    contentPane.add(host)

    val bounds = SwingWebViewHostPanel.calculateClippedBounds(host, rootPane)
    assertEquals(SwingWebViewHostPanel.NativeBounds(20, 40, 300, 200), bounds)
  }

  @Test
  fun calculateClippedBounds_clipsRightAndBottomToAncestorBounds() {
    val rootPane = JPanel(null).apply {
      size = Dimension(500, 340)
    }
    val contentPane = JPanel(null).apply {
      setBounds(0, 40, 500, 260)
    }
    val bottomToolbar = JPanel().apply {
      setBounds(0, 300, 500, 40)
    }
    rootPane.add(contentPane)
    rootPane.add(bottomToolbar)

    val host = JPanel().apply {
      setBounds(30, 220, 500, 100)
    }
    contentPane.add(host)

    val bounds = SwingWebViewHostPanel.calculateClippedBounds(host, rootPane)
    assertEquals(SwingWebViewHostPanel.NativeBounds(30, 260, 470, 40), bounds)
  }

  @Test
  fun calculateClippedBounds_clipsLeftAndTopToAncestorBounds() {
    val rootPane = JPanel(null).apply {
      size = Dimension(500, 340)
    }
    val contentPane = JPanel(null).apply {
      setBounds(20, 40, 460, 260)
    }
    rootPane.add(contentPane)

    val host = JPanel().apply {
      setBounds(-15, -25, 100, 80)
    }
    contentPane.add(host)

    val bounds = SwingWebViewHostPanel.calculateClippedBounds(host, rootPane)
    assertEquals(SwingWebViewHostPanel.NativeBounds(20, 40, 85, 55), bounds)
  }

  @Test
  fun calculateClippedBounds_doesNotClipRightAndBottomToTrailingSiblings() {
    val rootPane = JPanel(null).apply {
      size = Dimension(500, 340)
    }
    val contentPane = JPanel(null).apply {
      setBounds(0, 0, 500, 340)
    }
    rootPane.add(contentPane)

    val host = JPanel().apply {
      setBounds(20, 40, 430, 260)
    }
    val rightToolbar = JPanel().apply {
      setBounds(360, 40, 40, 260)
    }
    val bottomToolbar = JPanel().apply {
      setBounds(20, 240, 340, 60)
    }
    contentPane.add(host)
    contentPane.add(rightToolbar)
    contentPane.add(bottomToolbar)

    val bounds = SwingWebViewHostPanel.calculateClippedBounds(host, rootPane)
    assertEquals(SwingWebViewHostPanel.NativeBounds(20, 40, 430, 260), bounds)
  }

  @Test
  fun calculateClippedBounds_doesNotClipLeftAndTopToLeadingSiblings() {
    val rootPane = JPanel(null).apply {
      size = Dimension(500, 340)
    }
    val contentPane = JPanel(null).apply {
      setBounds(0, 0, 500, 340)
    }
    rootPane.add(contentPane)

    val host = JPanel().apply {
      setBounds(20, 20, 260, 220)
    }
    val leftToolbar = JPanel().apply {
      setBounds(0, 20, 40, 220)
    }
    val topToolbar = JPanel().apply {
      setBounds(40, 0, 240, 60)
    }
    contentPane.add(host)
    contentPane.add(leftToolbar)
    contentPane.add(topToolbar)

    val bounds = SwingWebViewHostPanel.calculateClippedBounds(host, rootPane)
    assertEquals(SwingWebViewHostPanel.NativeBounds(20, 20, 260, 220), bounds)
  }

  @Test
  fun calculateClippedBounds_ignoresInvisibleSiblings() {
    val rootPane = JPanel(null).apply {
      size = Dimension(500, 340)
    }
    val contentPane = JPanel(null).apply {
      setBounds(0, 0, 500, 340)
    }
    rootPane.add(contentPane)

    val host = JPanel().apply {
      setBounds(20, 40, 430, 260)
    }
    val overlay = JPanel().apply {
      setBounds(360, 40, 40, 260)
      isVisible = false
    }
    contentPane.add(host)
    contentPane.add(overlay)

    val bounds = SwingWebViewHostPanel.calculateClippedBounds(host, rootPane)
    assertEquals(SwingWebViewHostPanel.NativeBounds(20, 40, 430, 260), bounds)
  }

  // TODO: Fails after ComponentBackedEngine is removed
  @Test
  fun componentBackedEngine_isMountedDirectlyAndReceivesFocusDelegation() {
    val engine = FakeComponentBackedEngine()
    @Suppress("RAW_SCOPE_CREATION") // Test scope has no parent in this pure Swing geometry test.
    val scope = CoroutineScope(SupervisorJob())
    try {
      val host = SwingWebViewHostPanel(scope, engine)

      assertEquals(1, host.componentCount)
      assertSame(engine.component, host.getComponent(0))
      assertTrue(host.isFocusable)
      assertTrue(host.isRequestFocusEnabled)
      assertTrue(host.isFocusCycleRoot)
      assertTrue(host.isFocusTraversalPolicyProvider)
      assertSame(engine.component, host.focusTraversalPolicy.getDefaultComponent(host))

      host.requestWebViewFocus()
      host.clearWebViewFocus()

      assertEquals(1, engine.requestFocusCount)
      assertEquals(1, engine.clearFocusCount)
    }
    finally {
      scope.cancel()
    }
  }

  // TODO: fails after removal of Peers
  @Test
  fun swingFocusTransfer_clearsComponentBackedEngineFocus() {
    val engine = FakeComponentBackedEngine()
    @Suppress("RAW_SCOPE_CREATION") // Test scope has no parent in this pure Swing geometry test.
    val scope = CoroutineScope(SupervisorJob())
    try {
      val host = SwingWebViewHostPanel(scope, engine)

      host.clearWebViewFocusForSwingFocusTransfer()

      assertEquals(1, engine.clearFocusCount)
    }
    finally {
      scope.cancel()
    }
  }

  @Test
  fun swingFocusTransfer_usesNativePeerTransferHookWithoutExplicitClear() {
    val engine = FakeNativeEngine()
    @Suppress("RAW_SCOPE_CREATION") // Test scope has no parent in this pure Swing geometry test.
    val scope = CoroutineScope(SupervisorJob())
    try {
      val host = SwingWebViewHostPanel(
        scope = scope,
        engine = engine,
      )

      host.clearWebViewFocusForSwingFocusTransfer()
      host.clearWebViewFocus()

      assertEquals(1, engine.clearFocusForSwingTransferCount)
      assertEquals(1, engine.clearFocusCount)
    }
    finally {
      scope.cancel()
    }
  }

  @Test
  fun swingHostFocusRequest_skipsForcedFallbackForMouseActivation() {
    val engine = FakeNativeEngine()
    @Suppress("RAW_SCOPE_CREATION") // Test scope has no parent in this pure Swing geometry test.
    val scope = CoroutineScope(SupervisorJob())
    try {
      val host = SwingWebViewHostPanel(
        scope = scope,
        engine = engine,
      ).apply {
        // Force requestFocusInWindow() to fail so the test covers the fallback branch without
        // involving the platform focus manager or a native window.
        isFocusable = false
      }

      assertFalse(host.requestSwingFocusForWebViewActivation(allowForcedFocusFallback = false))
    }
    finally {
      scope.cancel()
    }
  }

  @Test
  fun swingHostFocusRequest_keepsForcedFallbackForNativeFocusRequests() {
    val engine = FakeNativeEngine()
    // TODO: use runBlocking and its scope in all tests
    @Suppress("RAW_SCOPE_CREATION") // Test scope has no parent in this pure Swing geometry test.
    val scope = CoroutineScope(SupervisorJob())
    try {
      val host = SwingWebViewHostPanel(
        scope = scope,
        engine = engine,
      ).apply {
        // Force requestFocusInWindow() to fail so the test covers the fallback branch without
        // involving the platform focus manager or a native window.
        isFocusable = false
      }

      assertTrue(host.requestSwingFocusForWebViewActivation(allowForcedFocusFallback = true))
    }
    finally {
      scope.cancel()
    }
  }

  @Test
  fun traversalFocusEntry_requestsWebViewFocusAndNotifiesPageDirection() {
    val engine = FakeComponentBackedEngine()
    val focusEntrySink = RecordingFocusEntrySink()
    @Suppress("RAW_SCOPE_CREATION") // Test scope has no parent in this pure Swing geometry test.
    val scope = CoroutineScope(SupervisorJob())
    try {
      val host = SwingWebViewHostPanel(scope, engine, focusEntrySink)

      host.focusListeners.forEach { listener ->
        listener.focusGained(FocusEvent(host, FocusEvent.FOCUS_GAINED, false, null, FocusEvent.Cause.TRAVERSAL_FORWARD))
      }

      assertEquals(1, engine.requestFocusCount)
      assertEquals(listOf(WebViewFocusDirection.FORWARD), focusEntrySink.entries)
    }
    finally {
      scope.cancel()
    }
  }

  @Test
  fun repeatedFocusEntry_requestsWebViewFocusWithoutRepeatingPageBoundaryEntry() {
    val engine = FakeComponentBackedEngine()
    val focusEntrySink = RecordingFocusEntrySink()
    @Suppress("RAW_SCOPE_CREATION") // Test scope has no parent in this pure Swing geometry test.
    val scope = CoroutineScope(SupervisorJob())
    try {
      val host = SwingWebViewHostPanel(scope, engine, focusEntrySink)

      host.focusListeners.forEach { listener ->
        listener.focusGained(FocusEvent(host, FocusEvent.FOCUS_GAINED, false, null, FocusEvent.Cause.TRAVERSAL_FORWARD))
      }
      host.focusListeners.forEach { listener ->
        listener.focusGained(FocusEvent(host, FocusEvent.FOCUS_GAINED, false, null, FocusEvent.Cause.TRAVERSAL_BACKWARD))
      }

      assertEquals(2, engine.requestFocusCount)
      assertEquals(listOf(WebViewFocusDirection.FORWARD), focusEntrySink.entries)
    }
    finally {
      scope.cancel()
    }
  }

  @Test
  fun swingMouseFocusEntry_requestsNativeWebViewFocusWithoutPageBoundary() {
    val engine = FakeComponentBackedEngine()
    val focusEntrySink = RecordingFocusEntrySink()
    @Suppress("RAW_SCOPE_CREATION") // Test scope has no parent in this pure Swing geometry test.
    val scope = CoroutineScope(SupervisorJob())
    try {
      val host = SwingWebViewHostPanel(scope, engine, focusEntrySink)

      host.focusListeners.forEach { listener ->
        listener.focusGained(FocusEvent(host, FocusEvent.FOCUS_GAINED, false, null, FocusEvent.Cause.MOUSE_EVENT))
      }

      assertEquals(1, engine.requestFocusCount)
      assertEquals(emptyList<WebViewFocusDirection>(), focusEntrySink.entries)
    }
    finally {
      scope.cancel()
    }
  }

  // TODO: fails after removal of Peers
  @Test
  fun swingFocusTransfer_notifiesPageLeaveAndClearsNativeFocusOnce() {
    val engine = FakeComponentBackedEngine()
    val focusEntrySink = RecordingFocusEntrySink()
    @Suppress("RAW_SCOPE_CREATION") // Test scope has no parent in this pure Swing geometry test.
    val scope = CoroutineScope(SupervisorJob())
    try {
      val host = SwingWebViewHostPanel(scope, engine, focusEntrySink)
      val outsideComponent = JPanel()

      host.focusListeners.forEach { listener ->
        listener.focusGained(FocusEvent(host, FocusEvent.FOCUS_GAINED, false, outsideComponent, FocusEvent.Cause.MOUSE_EVENT))
      }
      host.focusListeners.forEach { listener ->
        listener.focusLost(FocusEvent(host, FocusEvent.FOCUS_LOST, false, outsideComponent, FocusEvent.Cause.MOUSE_EVENT))
      }
      host.focusListeners.forEach { listener ->
        listener.focusLost(FocusEvent(host, FocusEvent.FOCUS_LOST, false, outsideComponent, FocusEvent.Cause.MOUSE_EVENT))
      }

      assertEquals(1, focusEntrySink.leaveCount)
      assertEquals(1, engine.clearFocusCount)
    }
    finally {
      scope.cancel()
    }
  }

  @Test
  fun swingFocusGain_duringNativeSynchronizationDoesNotRequestNativeFocus() {
    val engine = FakeComponentBackedEngine()
    @Suppress("RAW_SCOPE_CREATION") // Test scope has no parent in this pure Swing geometry test.
    val scope = CoroutineScope(SupervisorJob())
    try {
      val host = SwingWebViewHostPanel(scope, engine)
      val focusSyncField = SwingWebViewHostPanel::class.java.getDeclaredField("focusSyncInProgress").apply {
        isAccessible = true
      }
      focusSyncField.setBoolean(host, true)

      host.focusListeners.forEach { listener ->
        listener.focusGained(FocusEvent(host, FocusEvent.FOCUS_GAINED, false, null, FocusEvent.Cause.UNKNOWN))
      }

      assertEquals(0, engine.requestFocusCount)
      assertFalse(focusSyncField.getBoolean(host))
    }
    finally {
      scope.cancel()
    }
  }

  private class RecordingFocusEntrySink : WebViewFocusEntrySink {
    val entries = ArrayList<WebViewFocusDirection>()
    var leaveCount = 0
      private set

    override fun enterWebViewFocus(direction: WebViewFocusDirection) {
      entries += direction
    }

    override fun leaveWebViewFocus() {
      leaveCount++
    }
  }

  // TODO: merge with FakeComponentBacked? control behavior with flags?
  private class FakeNativeEngine : WebViewEngine {
    override val isHeavyweight: Boolean = false
    override val component: JComponent?
      get() = null

    override suspend fun loadFile(file: Path) {
    }

    override suspend fun loadAsset(root: WebViewAssetRoot, entry: WebViewAssetPath, query: String?) {
    }

    override suspend fun loadHtml(html: String, baseFile: Path?) {
    }

    override suspend fun evaluateJavaScript(script: String): String? = null

    override suspend fun transferToJs(rawJson: String) {
    }

    override fun connectMessageBus(receiver: WebViewJsMessageReceiver) {
    }

    override suspend fun close() {
    }

    /**
     * From Peer's section
     */

    private val attachResult: Boolean = true

    var attachCount = 0
      private set
    var detachCount = 0
      private set
    var clearFocusCount = 0
      private set
    var clearFocusForSwingTransferCount = 0
      private set

    override fun attach(host: Component): Boolean {
      attachCount++
      return attachResult
    }

    override fun detach() {
      detachCount++
    }

    override fun scheduleFrameUpdate(host: Component) {
    }

    override fun updateVisibility(host: Component, hidden: Boolean) {
    }

    override fun requestFocus() {
    }

    override fun clearFocus() {
      clearFocusCount++
    }

    override fun clearFocusForSwingFocusTransfer() {
      clearFocusForSwingTransferCount++
    }

  }

  // Merge with Fake?
  private class FakeComponentBackedEngine : WebViewEngine {
    override val isHeavyweight: Boolean
      get() = false
    override val component: JComponent = JPanel()
    var requestFocusCount = 0
      private set
    var clearFocusCount = 0
      private set

    override suspend fun loadFile(file: Path) {
    }

    override suspend fun loadAsset(root: WebViewAssetRoot, entry: WebViewAssetPath, query: String?) {
    }

    override suspend fun loadHtml(html: String, baseFile: Path?) {
    }

    override suspend fun evaluateJavaScript(script: String): String? = null

    override suspend fun transferToJs(rawJson: String) {
    }

    override fun connectMessageBus(receiver: WebViewJsMessageReceiver) {
    }

    override fun attach(host: Component): Boolean {
      return true
    }

    override fun detach() {
    }

    override fun scheduleFrameUpdate(host: Component) {
    }

    override fun updateVisibility(host: Component, hidden: Boolean) {
    }

    override suspend fun close() {
    }

    override fun requestFocus() {
      requestFocusCount++
    }

    override fun clearFocus() {
      clearFocusCount++
    }
  }
}
