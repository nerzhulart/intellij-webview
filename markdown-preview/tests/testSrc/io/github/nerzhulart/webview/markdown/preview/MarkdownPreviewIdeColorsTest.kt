// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview

import junit.framework.TestCase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Color
import javax.swing.UIManager

internal class MarkdownPreviewIdeColorsTest : TestCase() {
  fun `test opaque color is serialized as six hex digits`() {
    assertEquals("#0A7F00", Color(10, 127, 0).toCssHex())
  }

  fun `test translucent color keeps alpha as the last hex pair`() {
    assertEquals("#FF000080", Color(255, 0, 0, 128).toCssHex())
  }

  fun `test change colors payload has background and stripe per change kind`() {
    val payload = Json.encodeToJsonElement(
      MarkdownChangeColorsParams.serializer(),
      MarkdownChangeColorsParams(
        added = MarkdownChangeColorParams(background = "#294436", stripe = "#384C38"),
        modified = MarkdownChangeColorParams(background = "#385570", stripe = "#43698D"),
        removed = MarkdownChangeColorParams(background = "#484A4A", stripe = "#656E76"),
      ),
    ).jsonObject

    assertEquals(setOf("added", "modified", "removed"), payload.keys)
    val removed = payload.getValue("removed").jsonObject
    assertEquals("#484A4A", removed.getValue("background").jsonPrimitive.content)
    assertEquals("#656E76", removed.getValue("stripe").jsonPrimitive.content)
  }

  fun `test link colors follow the Link keys of the current UI theme`() {
    val keys = listOf("Link.activeForeground", "Link.hoverForeground", "Link.pressedForeground")
    val previous = keys.associateWith { UIManager.get(it) }
    try {
      UIManager.put("Link.activeForeground", Color(0x11, 0x22, 0x33))
      UIManager.put("Link.hoverForeground", Color(0x44, 0x55, 0x66))
      UIManager.put("Link.pressedForeground", Color(0x77, 0x88, 0x99))

      assertEquals(
        MarkdownLinkColorsParams(foreground = "#112233", hover = "#445566", pressed = "#778899"),
        currentMarkdownLinkColors(),
      )
    }
    finally {
      previous.forEach { (key, value) -> UIManager.put(key, value) }
    }
  }

  fun `test link colors payload has foreground hover and pressed`() {
    val payload = Json.encodeToJsonElement(
      MarkdownLinkColorsParams.serializer(),
      MarkdownLinkColorsParams(foreground = "#548AF7", hover = "#548AF7", pressed = "#2E55A3"),
    ).jsonObject

    assertEquals(setOf("foreground", "hover", "pressed"), payload.keys)
    assertEquals("#2E55A3", payload.getValue("pressed").jsonPrimitive.content)
  }
}
