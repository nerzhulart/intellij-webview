// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.webview

import com.intellij.ui.webview.impl.windows.WinWebViewShortcutInterop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Panel
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

class WinWebViewShortcutInteropTest {
  @Test
  fun `routes accelerators to Swing by default`() {
    assertSwing('S'.code, CONTROL or ALT)
    assertSwing(VK_F4, CONTROL)
    assertSwing(VK_F4, SHIFT)
    assertSwing('F'.code, CONTROL)
    assertSwing(VK_F3, 0)
    assertSwing(VK_F5, 0)
    assertSwing(VK_ESCAPE, 0)
  }

  @Test
  fun `keeps standard editing shortcuts in WebView`() {
    for (virtualKey in listOf('A'.code, 'C'.code, 'V'.code, 'X'.code, 'Y'.code, 'Z'.code)) {
      assertWebView(virtualKey, CONTROL)
    }
    assertWebView('Z'.code, CONTROL or SHIFT)
  }

  @Test
  fun `keeps text navigation and selection in WebView`() {
    for (virtualKey in listOf(VK_LEFT, VK_RIGHT, VK_HOME, VK_END, VK_BACK, VK_DELETE)) {
      assertWebView(virtualKey, CONTROL)
      assertWebView(virtualKey, CONTROL or SHIFT)
    }
    assertWebView(VK_LEFT, 0)
    assertWebView(VK_RIGHT, SHIFT)
    assertWebView(VK_PRIOR, 0)
    assertWebView(VK_NEXT, SHIFT)
  }

  @Test
  fun `keeps WebView traversal and activation keys in WebView`() {
    assertWebView(VK_TAB, 0)
    assertWebView(VK_TAB, SHIFT)
    assertWebView(VK_RETURN, 0)
    assertWebView(VK_RETURN, SHIFT)
  }

  @Test
  fun `does not broaden WebView allowlist with Alt or Meta`() {
    assertSwing(VK_LEFT, ALT)
    assertSwing(VK_RIGHT, META)
    assertSwing('C'.code, CONTROL or ALT)
    assertSwing('V'.code, CONTROL or META)
    assertSwing(VK_TAB, CONTROL)
    assertSwing(VK_NEXT, CONTROL)
  }

  @Test
  fun `posts Swing owned accelerator without waiting for EDT`() {
    val queue = RecordingEventQueue()

    val result = WinWebViewShortcutInterop.handleAccelerator(
      target = Panel(),
      eventQueue = queue,
      keyEventKind = WinWebViewShortcutInterop.KEY_EVENT_KIND_SYSTEM_KEY_DOWN,
      virtualKey = VK_F1,
      modifiers = ALT,
      keyEventLParam = 0,
    )

    assertEquals(WinWebViewShortcutInterop.RESULT_HANDLED, result)
    val event = queue.events.single() as KeyEvent
    assertEquals(KeyEvent.KEY_PRESSED, event.id)
    assertEquals(KeyEvent.VK_F1, event.keyCode)
    assertEquals(InputEvent.ALT_DOWN_MASK, event.modifiersEx)
  }

  @Test
  fun `does not post WebView owned accelerator`() {
    val queue = RecordingEventQueue()

    val result = WinWebViewShortcutInterop.handleAccelerator(
      target = Panel(),
      eventQueue = queue,
      keyEventKind = WinWebViewShortcutInterop.KEY_EVENT_KIND_KEY_DOWN,
      virtualKey = 'C'.code,
      modifiers = CONTROL,
      keyEventLParam = 0,
    )

    assertEquals(WinWebViewShortcutInterop.RESULT_BROWSER_ACCELERATOR_ENABLED, result)
    assertEquals(emptyList<AWTEvent>(), queue.events)
  }

  private fun assertSwing(virtualKey: Int, modifiers: Int) {
    assertEquals(
      WinWebViewShortcutInterop.RESULT_HANDLED,
      WinWebViewShortcutInterop.routeAccelerator(virtualKey, modifiers),
    )
  }

  private fun assertWebView(virtualKey: Int, modifiers: Int) {
    assertEquals(
      WinWebViewShortcutInterop.RESULT_BROWSER_ACCELERATOR_ENABLED,
      WinWebViewShortcutInterop.routeAccelerator(virtualKey, modifiers),
    )
  }

  private class RecordingEventQueue : EventQueue() {
    val events = ArrayList<AWTEvent>()

    override fun postEvent(event: AWTEvent) {
      events.add(event)
    }
  }

  private companion object {
    private const val SHIFT: Int = WinWebViewShortcutInterop.MODIFIER_SHIFT
    private const val CONTROL: Int = WinWebViewShortcutInterop.MODIFIER_CONTROL
    private const val ALT: Int = WinWebViewShortcutInterop.MODIFIER_ALT
    private const val META: Int = WinWebViewShortcutInterop.MODIFIER_META
    private const val VK_BACK: Int = 0x08
    private const val VK_TAB: Int = 0x09
    private const val VK_RETURN: Int = 0x0D
    private const val VK_ESCAPE: Int = 0x1B
    private const val VK_PRIOR: Int = 0x21
    private const val VK_NEXT: Int = 0x22
    private const val VK_END: Int = 0x23
    private const val VK_HOME: Int = 0x24
    private const val VK_LEFT: Int = 0x25
    private const val VK_RIGHT: Int = 0x27
    private const val VK_DELETE: Int = 0x2E
    private const val VK_F3: Int = 0x72
    private const val VK_F1: Int = 0x70
    private const val VK_F4: Int = 0x73
    private const val VK_F5: Int = 0x74
  }
}
