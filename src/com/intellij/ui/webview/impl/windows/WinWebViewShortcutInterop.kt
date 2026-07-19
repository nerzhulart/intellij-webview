// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.webview.impl.windows

import java.awt.Component
import java.awt.EventQueue
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/**
 * Pure accelerator ownership policy. This is called synchronously on the Canvas owner AWT thread,
 * so it must not access EDT state, AWT queues, the action system, locks, or mutable routing state.
 */
internal object WinWebViewShortcutInterop {
  internal const val KEY_EVENT_KIND_KEY_DOWN: Int = 0
  internal const val KEY_EVENT_KIND_KEY_UP: Int = 1
  internal const val KEY_EVENT_KIND_SYSTEM_KEY_DOWN: Int = 2
  internal const val KEY_EVENT_KIND_SYSTEM_KEY_UP: Int = 3

  internal const val RESULT_HANDLED: Int = 1
  internal const val RESULT_BROWSER_ACCELERATOR_ENABLED: Int = 1 shl 1

  internal const val MODIFIER_SHIFT: Int = 1
  internal const val MODIFIER_CONTROL: Int = 1 shl 1
  internal const val MODIFIER_ALT: Int = 1 shl 2
  internal const val MODIFIER_META: Int = 1 shl 3

  fun routeAccelerator(virtualKey: Int, modifiers: Int): Int {
    val shortcut = Shortcut(virtualKey, modifiers and SUPPORTED_MODIFIERS)
    return if (shortcut in WEBVIEW_SHORTCUTS) RESULT_BROWSER_ACCELERATOR_ENABLED else RESULT_HANDLED
  }

  fun handleAccelerator(
    target: Component,
    eventQueue: EventQueue,
    keyEventKind: Int,
    virtualKey: Int,
    modifiers: Int,
    keyEventLParam: Int,
  ): Int {
    val routing = routeAccelerator(virtualKey, modifiers)
    if (routing == RESULT_BROWSER_ACCELERATOR_ENABLED) return routing

    createSwingKeyEvent(target, keyEventKind, virtualKey, modifiers, keyEventLParam)?.let(eventQueue::postEvent)
    return RESULT_HANDLED
  }

  internal fun createSwingKeyEvent(
    target: Component,
    keyEventKind: Int,
    virtualKey: Int,
    modifiers: Int,
    keyEventLParam: Int,
  ): KeyEvent? {
    val eventId = when (keyEventKind) {
      KEY_EVENT_KIND_KEY_DOWN, KEY_EVENT_KIND_SYSTEM_KEY_DOWN -> KeyEvent.KEY_PRESSED
      KEY_EVENT_KIND_KEY_UP, KEY_EVENT_KIND_SYSTEM_KEY_UP -> KeyEvent.KEY_RELEASED
      else -> return null
    }
    return KeyEvent(
      target,
      eventId,
      System.currentTimeMillis(),
      toAwtModifiers(modifiers),
      WINDOWS_TO_JAVA_KEY_CODES[virtualKey] ?: virtualKey,
      KeyEvent.CHAR_UNDEFINED,
      keyLocation(virtualKey, keyEventLParam),
    )
  }

  private fun toAwtModifiers(modifiers: Int): Int {
    return MODIFIER_MAPPINGS.fold(0) { result, (nativeMask, awtMask) ->
      if (modifiers and nativeMask == 0) result else result or awtMask
    }
  }

  private fun keyLocation(virtualKey: Int, keyEventLParam: Int): Int {
    val extended = keyEventLParam and EXTENDED_KEY_MASK != 0
    return when (virtualKey) {
      VK_LSHIFT, VK_LCONTROL, VK_LMENU, VK_LWIN -> KeyEvent.KEY_LOCATION_LEFT
      VK_RSHIFT, VK_RCONTROL, VK_RMENU, VK_RWIN -> KeyEvent.KEY_LOCATION_RIGHT
      VK_SHIFT -> KeyEvent.KEY_LOCATION_UNKNOWN
      VK_CONTROL, VK_MENU -> if (extended) KeyEvent.KEY_LOCATION_RIGHT else KeyEvent.KEY_LOCATION_LEFT
      in VK_NUMPAD0..VK_DIVIDE -> KeyEvent.KEY_LOCATION_NUMPAD
      else -> KeyEvent.KEY_LOCATION_STANDARD
    }
  }

  private data class Shortcut(val virtualKey: Int, val modifiers: Int)

  private val TEXT_NAVIGATION_KEYS: Set<Int> = setOf(
    VK_BACK,
    VK_END,
    VK_HOME,
    VK_LEFT,
    VK_UP,
    VK_RIGHT,
    VK_DOWN,
    VK_INSERT,
    VK_DELETE,
  )

  private val TEXT_NAVIGATION_MODIFIERS: Set<Int> = setOf(
    0,
    MODIFIER_SHIFT,
    MODIFIER_CONTROL,
    MODIFIER_CONTROL or MODIFIER_SHIFT,
  )

  private val TEXT_INPUT_SHORTCUTS: Set<Shortcut> = setOf(
    Shortcut(VK_TAB, 0),
    Shortcut(VK_TAB, MODIFIER_SHIFT),
    Shortcut(VK_RETURN, 0),
    Shortcut(VK_RETURN, MODIFIER_SHIFT),
    Shortcut(VK_PRIOR, 0),
    Shortcut(VK_PRIOR, MODIFIER_SHIFT),
    Shortcut(VK_NEXT, 0),
    Shortcut(VK_NEXT, MODIFIER_SHIFT),
  )

  private val EDITING_SHORTCUTS: Set<Shortcut> = setOf(
    Shortcut('A'.code, MODIFIER_CONTROL),
    Shortcut('C'.code, MODIFIER_CONTROL),
    Shortcut('V'.code, MODIFIER_CONTROL),
    Shortcut('X'.code, MODIFIER_CONTROL),
    Shortcut('Y'.code, MODIFIER_CONTROL),
    Shortcut('Z'.code, MODIFIER_CONTROL),
    Shortcut('Z'.code, MODIFIER_CONTROL or MODIFIER_SHIFT),
  )

  private val WEBVIEW_SHORTCUTS: Set<Shortcut> = buildSet {
    for (virtualKey in TEXT_NAVIGATION_KEYS) {
      for (modifiers in TEXT_NAVIGATION_MODIFIERS) {
        add(Shortcut(virtualKey, modifiers))
      }
    }
    addAll(TEXT_INPUT_SHORTCUTS)
    addAll(EDITING_SHORTCUTS)
  }

  private val MODIFIER_MAPPINGS: List<Pair<Int, Int>> = listOf(
    MODIFIER_SHIFT to InputEvent.SHIFT_DOWN_MASK,
    MODIFIER_CONTROL to InputEvent.CTRL_DOWN_MASK,
    MODIFIER_ALT to InputEvent.ALT_DOWN_MASK,
    MODIFIER_META to InputEvent.META_DOWN_MASK,
  )

  private val WINDOWS_TO_JAVA_KEY_CODES: Map<Int, Int> = mapOf(
    VK_RETURN to KeyEvent.VK_ENTER,
    VK_PRIOR to KeyEvent.VK_PAGE_UP,
    VK_NEXT to KeyEvent.VK_PAGE_DOWN,
    VK_INSERT to KeyEvent.VK_INSERT,
    VK_DELETE to KeyEvent.VK_DELETE,
    VK_SNAPSHOT to KeyEvent.VK_PRINTSCREEN,
    VK_HELP to KeyEvent.VK_HELP,
    VK_LSHIFT to KeyEvent.VK_SHIFT,
    VK_RSHIFT to KeyEvent.VK_SHIFT,
    VK_LCONTROL to KeyEvent.VK_CONTROL,
    VK_RCONTROL to KeyEvent.VK_CONTROL,
    VK_LMENU to KeyEvent.VK_ALT,
    VK_RMENU to KeyEvent.VK_ALT,
    VK_LWIN to KeyEvent.VK_META,
    VK_RWIN to KeyEvent.VK_META,
    VK_APPS to KeyEvent.VK_CONTEXT_MENU,
    VK_OEM_1 to KeyEvent.VK_SEMICOLON,
    VK_OEM_PLUS to KeyEvent.VK_EQUALS,
    VK_OEM_COMMA to KeyEvent.VK_COMMA,
    VK_OEM_MINUS to KeyEvent.VK_MINUS,
    VK_OEM_PERIOD to KeyEvent.VK_PERIOD,
    VK_OEM_2 to KeyEvent.VK_SLASH,
    VK_OEM_3 to KeyEvent.VK_BACK_QUOTE,
    VK_OEM_4 to KeyEvent.VK_OPEN_BRACKET,
    VK_OEM_5 to KeyEvent.VK_BACK_SLASH,
    VK_OEM_6 to KeyEvent.VK_CLOSE_BRACKET,
    VK_OEM_7 to KeyEvent.VK_QUOTE,
    VK_OEM_102 to KeyEvent.VK_LESS,
  )

  private const val SUPPORTED_MODIFIERS: Int = MODIFIER_SHIFT or MODIFIER_CONTROL or MODIFIER_ALT or MODIFIER_META
  private const val EXTENDED_KEY_MASK: Int = 1 shl 24
  private const val VK_BACK: Int = 0x08
  private const val VK_TAB: Int = 0x09
  private const val VK_RETURN: Int = 0x0D
  private const val VK_PRIOR: Int = 0x21
  private const val VK_NEXT: Int = 0x22
  private const val VK_END: Int = 0x23
  private const val VK_HOME: Int = 0x24
  private const val VK_LEFT: Int = 0x25
  private const val VK_UP: Int = 0x26
  private const val VK_RIGHT: Int = 0x27
  private const val VK_DOWN: Int = 0x28
  private const val VK_INSERT: Int = 0x2D
  private const val VK_DELETE: Int = 0x2E
  private const val VK_SNAPSHOT: Int = 0x2C
  private const val VK_HELP: Int = 0x2F
  private const val VK_SHIFT: Int = 0x10
  private const val VK_CONTROL: Int = 0x11
  private const val VK_MENU: Int = 0x12
  private const val VK_LWIN: Int = 0x5B
  private const val VK_RWIN: Int = 0x5C
  private const val VK_APPS: Int = 0x5D
  private const val VK_NUMPAD0: Int = 0x60
  private const val VK_DIVIDE: Int = 0x6F
  private const val VK_LSHIFT: Int = 0xA0
  private const val VK_RSHIFT: Int = 0xA1
  private const val VK_LCONTROL: Int = 0xA2
  private const val VK_RCONTROL: Int = 0xA3
  private const val VK_LMENU: Int = 0xA4
  private const val VK_RMENU: Int = 0xA5
  private const val VK_OEM_1: Int = 0xBA
  private const val VK_OEM_PLUS: Int = 0xBB
  private const val VK_OEM_COMMA: Int = 0xBC
  private const val VK_OEM_MINUS: Int = 0xBD
  private const val VK_OEM_PERIOD: Int = 0xBE
  private const val VK_OEM_2: Int = 0xBF
  private const val VK_OEM_3: Int = 0xC0
  private const val VK_OEM_4: Int = 0xDB
  private const val VK_OEM_5: Int = 0xDC
  private const val VK_OEM_6: Int = 0xDD
  private const val VK_OEM_7: Int = 0xDE
  private const val VK_OEM_102: Int = 0xE2
}
