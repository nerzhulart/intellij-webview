// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview

import com.intellij.ide.IdeEventQueue
import com.intellij.jna.JnaLoader
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.api.WebViewPanelOptions
import io.github.nerzhulart.webview.api.createWebViewPanel
import io.github.nerzhulart.webview.impl.MacMainThreadDispatcher
import io.github.nerzhulart.webview.impl.SwingWebViewHostPanel
import io.github.nerzhulart.webview.impl.mac.ID
import io.github.nerzhulart.webview.impl.mac.MacObjectiveC
import io.github.nerzhulart.webview.impl.mac.WKWebViewBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyEvent
import java.util.Collections
import javax.swing.JComponent
import javax.swing.JFrame
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The macOS peer places every WKWebView into a clipping container painted with the host background.
 * A container that outlives its WebView stays on top of Swing as an opaque rectangle, so every
 * teardown order must remove it from the window content view.
 */
@EnabledOnOs(OS.MAC)
@DisabledIfSystemProperty(named = "java.awt.headless", matches = "true")
@TestApplication
internal class MacWebViewNativeContainerCleanupTest {

  @Test
  fun cancellingWebViewScopeWhileHostIsShowingReleasesNativeContainer(): Unit = runBlocking {
    runTeardownScenario { scope, frame, host ->
      // Diff viewers dispose their WebView scope before Swing removes the viewer component.
      scope.coroutineContext.job.cancelAndJoin()
      withContext(Dispatchers.EDT) { frame.removeHost(host) }
    }
  }

  @Test
  fun removingHostBeforeCancellingWebViewScopeReleasesNativeContainer(): Unit = runBlocking {
    runTeardownScenario { scope, frame, host ->
      withContext(Dispatchers.EDT) { frame.removeHost(host) }
      scope.coroutineContext.job.cancelAndJoin()
    }
  }

  @Test
  fun hidingFocusedHostRestoresWindowContentViewAsFirstResponder(): Unit = runBlocking {
    runTeardownScenario { _, frame, host ->
      withContext(Dispatchers.EDT) { host.isVisible = false }
      assertTrue(
        awaitWindowContentViewIsFirstResponder(frame),
        "Hiding a focused WKWebView host must restore NSWindow.contentView (AWTView) as firstResponder",
      )
      withContext(Dispatchers.EDT) { frame.removeHost(host) }
    }
  }

  @Test
  fun modifierFlagsChangedInsideFocusedWebViewSynchronizesWithAwtViewOnRelease(): Unit = runBlocking {
    runTeardownScenario { _, frame, host ->
      val recordedEvents = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())
      val disposable = Disposer.newDisposable("MacWebViewNativeContainerCleanupTest.modifierFlagsChanged")
      IdeEventQueue.getInstance().addPreprocessor(
        IdeEventQueue.EventDispatcher { event ->
          if (event is KeyEvent && (event.id == KeyEvent.KEY_PRESSED || event.id == KeyEvent.KEY_RELEASED)) {
            recordedEvents.add(event.id to event.keyCode)
          }
          false
        },
        disposable,
      )
      try {
        val nsWindow = withContext(Dispatchers.EDT) { MacObjectiveC.windowFromAwt(frame) }
        val dispatchDiag = withContext(MacMainThreadDispatcher) {
          val firstResponder = MacObjectiveC.invoke(nsWindow, "firstResponder")
          val pressEvent = createModifierFlagsChangedEvent(
            keyCode = MAC_KEY_LEFT_CONTROL,
            keyDown = true,
            modifierFlags = NSEVENT_MODIFIER_FLAG_CONTROL,
          )
          val eventType = MacObjectiveC.invoke(pressEvent, "type").toLong()
          val eventFlags = MacObjectiveC.invoke(pressEvent, "modifierFlags").toLong()
          val eventKeyCode = MacObjectiveC.invoke(pressEvent, "keyCode").toLong()
          val responderDesc = MacObjectiveC.toStringViaUTF8(MacObjectiveC.invoke(firstResponder, "description"))
          MacObjectiveC.invoke(firstResponder, "flagsChanged:", pressEvent)
          "responder=$responderDesc, type=$eventType, flags=0x${java.lang.Long.toHexString(eventFlags)}, keyCode=$eventKeyCode"
        }

        val sawSinglePress = withTimeoutOrNull(2.seconds) {
          while (true) {
            val snapshot = synchronized(recordedEvents) { recordedEvents.toList() }
            if (snapshot.contains(KeyEvent.KEY_PRESSED to KeyEvent.VK_CONTROL)) return@withTimeoutOrNull true
            delay(20.milliseconds)
          }
        } == true
        assertTrue(sawSinglePress, "Ctrl press inside WKWebView did not reach AWT ($dispatchDiag); events=$recordedEvents")
        val pressCount = synchronized(recordedEvents) {
          recordedEvents.count { it == (KeyEvent.KEY_PRESSED to KeyEvent.VK_CONTROL) }
        }
        assertEquals(1, pressCount, "Ctrl press inside WKWebView must not be duplicated; events=$recordedEvents")

        // Simulate popup/Swing focus transfer (such as Ctrl+Tab Switcher opening) before Ctrl is released.
        withContext(Dispatchers.EDT) {
          (host as SwingWebViewHostPanel).clearWebViewFocusForSwingFocusTransfer()
        }
        assertTrue(
          awaitWindowContentViewIsFirstResponder(frame),
          "Focus transfer did not restore AWTView as firstResponder before releasing Ctrl",
        )

        withContext(MacMainThreadDispatcher) {
          val contentView = checkNotNull(WKWebViewBridge.contentView(nsWindow))
          val releaseEvent = createModifierFlagsChangedEvent(
            keyCode = MAC_KEY_LEFT_CONTROL,
            keyDown = false,
            modifierFlags = 0L,
          )
          MacObjectiveC.invoke(contentView, "flagsChanged:", releaseEvent)
        }

        val sawRelease = withTimeoutOrNull(2.seconds) {
          while (true) {
            val snapshot = synchronized(recordedEvents) { recordedEvents.toList() }
            if (snapshot.contains(KeyEvent.KEY_RELEASED to KeyEvent.VK_CONTROL)) return@withTimeoutOrNull true
            delay(20.milliseconds)
          }
        } == true
        assertTrue(
          sawRelease,
          "Ctrl release on AWTView after pressing Ctrl inside WKWebView was lost (sLastModifiers out of sync); events=$recordedEvents",
        )
      }
      finally {
        Disposer.dispose(disposable)
        withContext(Dispatchers.EDT) { frame.removeHost(host) }
      }
    }
  }

  private suspend fun runTeardownScenario(teardown: suspend (CoroutineScope, JFrame, JComponent) -> Unit) {
    if (!JnaLoader.isLoaded()) {
      JnaLoader.load(Logger.getInstance(MacWebViewNativeContainerCleanupTest::class.java))
    }

    @Suppress("RAW_SCOPE_CREATION") // Test owns the WebView lifetime to reproduce a specific teardown order.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val frame = withContext(Dispatchers.EDT) {
      JFrame("WebView Native Container Cleanup").apply {
        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        contentPane.layout = BorderLayout()
        size = Dimension(640, 420)
        setLocation(80, 80)
        isVisible = true
      }
    }
    try {
      val baseline = contentSubviewCount(frame)
      val panel = runCatching {
        withContext(Dispatchers.EDT) {
          createWebViewPanel(
            scope = scope,
            options = WebViewPanelOptions(
              assetRoot = WebViewAssetRoot.forView("smoke"),
              debugName = "MacWebViewNativeContainerCleanupTest",
            ),
          )
        }
      }.getOrElse { t ->
        assumeTrue(false, "No WebView engine is available: ${t::class.java.name}: ${t.message}")
        throw t
      }
      val host = withContext(Dispatchers.EDT) {
        panel.component.also { component ->
          frame.contentPane.add(component, BorderLayout.CENTER)
          frame.contentPane.revalidate()
        }
      }

      val attached = awaitContentSubviewCount(frame) { it > baseline }
      assertTrue(attached != null, "WKWebView clipping container was not attached to the window content view")

      withContext(Dispatchers.EDT) {
        (host as SwingWebViewHostPanel).requestWebViewFocus()
      }
      assertTrue(
        awaitWebViewIsFirstResponder(frame),
        "WKWebView did not become NSWindow.firstResponder before teardown",
      )

      teardown(scope, frame, host)

      val released = awaitContentSubviewCount(frame) { it == baseline }
      assertEquals(baseline, released ?: contentSubviewCount(frame), "WKWebView clipping container leaked in the window content view")
      assertTrue(
        awaitWindowContentViewIsFirstResponder(frame),
        "Teardown left NSWindow.firstResponder outside AWTView (causes NSBeep on subsequent Swing key presses)",
      )
    }
    finally {
      scope.coroutineContext.job.cancelAndJoin()
      withContext(Dispatchers.EDT) { frame.dispose() }
    }
  }

  private fun JFrame.removeHost(host: JComponent) {
    contentPane.remove(host)
    contentPane.revalidate()
    contentPane.repaint()
  }

  private suspend fun awaitWebViewIsFirstResponder(frame: JFrame): Boolean {
    val nsWindow = withContext(Dispatchers.EDT) { MacObjectiveC.windowFromAwt(frame) }
    return withTimeoutOrNull(5.seconds) {
      while (true) {
        val isWebViewResponder = withContext(MacMainThreadDispatcher) {
          val contentView = WKWebViewBridge.contentView(nsWindow) ?: return@withContext false
          val firstResponder = MacObjectiveC.invoke(nsWindow, "firstResponder")
          !MacObjectiveC.isNil(firstResponder) && firstResponder != nsWindow && firstResponder != contentView
        }
        if (isWebViewResponder) return@withTimeoutOrNull true
        delay(50.milliseconds)
      }
    } == true
  }

  private suspend fun awaitWindowContentViewIsFirstResponder(frame: JFrame): Boolean {
    val nsWindow = withContext(Dispatchers.EDT) { MacObjectiveC.windowFromAwt(frame) }
    return withTimeoutOrNull(5.seconds) {
      while (true) {
        val isContentViewResponder = withContext(MacMainThreadDispatcher) {
          val contentView = WKWebViewBridge.contentView(nsWindow) ?: return@withContext false
          val firstResponder = MacObjectiveC.invoke(nsWindow, "firstResponder")
          firstResponder == contentView
        }
        if (isContentViewResponder) return@withTimeoutOrNull true
        delay(50.milliseconds)
      }
    } == true
  }

  private fun createModifierFlagsChangedEvent(keyCode: Short, keyDown: Boolean, modifierFlags: Long): ID {
    val cgEvent = CoreGraphics.INSTANCE.CGEventCreateKeyboardEvent(null, keyCode, keyDown)
    check(cgEvent != Pointer.NULL) { "CGEventCreateKeyboardEvent returned NULL" }
    try {
      CoreGraphics.INSTANCE.CGEventSetFlags(cgEvent, modifierFlags)
      val nsEvent = MacObjectiveC.invoke(MacObjectiveC.getObjcClass("NSEvent"), "eventWithCGEvent:", cgEvent)
      check(!MacObjectiveC.isNil(nsEvent)) { "NSEvent.eventWithCGEvent returned nil" }
      return nsEvent
    }
    finally {
      CoreGraphics.INSTANCE.CFRelease(cgEvent)
    }
  }

  private interface CoreGraphics : Library {
    fun CGEventCreateKeyboardEvent(source: Pointer?, virtualKey: Short, keyDown: Boolean): Pointer
    fun CGEventSetFlags(event: Pointer, flags: Long)
    fun CFRelease(cfTypeRef: Pointer)

    companion object {
      val INSTANCE: CoreGraphics = Native.load("ApplicationServices", CoreGraphics::class.java)
    }
  }

  private companion object {
    const val MAC_KEY_LEFT_CONTROL: Short = 59
    const val NSEVENT_MODIFIER_FLAG_CONTROL: Long = 1L shl 18
  }

  private suspend fun awaitContentSubviewCount(frame: JFrame, predicate: (Long) -> Boolean): Long? {
    return withTimeoutOrNull(5.seconds) {
      var count = contentSubviewCount(frame)
      while (!predicate(count)) {
        delay(50.milliseconds)
        count = contentSubviewCount(frame)
      }
      count
    }
  }

  private suspend fun contentSubviewCount(frame: JFrame): Long {
    val nsWindow = withContext(Dispatchers.EDT) { MacObjectiveC.windowFromAwt(frame) }
    check(!MacObjectiveC.isNil(nsWindow)) { "AWT frame has no NSWindow" }
    return withContext(MacMainThreadDispatcher) {
      val contentView = checkNotNull(WKWebViewBridge.contentView(nsWindow)) { "NSWindow has no content view" }
      MacObjectiveC.invoke(MacObjectiveC.invoke(contentView, "subviews"), "count").toLong()
    }
  }
}
