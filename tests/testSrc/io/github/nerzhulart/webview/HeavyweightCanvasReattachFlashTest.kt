// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.GridLayout
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * Isolates the flash from WebView2 entirely: there is no engine, no native bridge and no browser
 * here, only a heavyweight [Canvas] moved between two containers exactly the way a tool window
 * moves its content.
 *
 * When AWT recreates the peer, it creates the HWND with the bounds the component still carries from
 * its previous parent, and that HWND is a child of the frame - so it appears at (0, 0) of the client
 * area at full size, painted by nobody, until the next layout pass moves it. That is the white
 * rectangle over the whole window, and it is reproducible without a single WebView2 call.
 *
 * The test measures both variants: a plain canvas, and one whose peer is created empty and gets its
 * bounds back once the containers are laid out - the same trick `WinWebViewEngine` uses.
 */
internal class HeavyweightCanvasReattachFlashTest {
  @Test
  fun recreatedHeavyweightPeerFlashesWhiteUnlessItStartsEmpty() {
    assumeFalse(GraphicsEnvironment.isHeadless(), "java.awt.headless=true")

    val plain = measureReattachFlash(peerStartsEmpty = false, reportName = "canvas-plain-" + System.currentTimeMillis())
    val guarded = measureReattachFlash(peerStartsEmpty = true, reportName = "canvas-guarded-" + System.currentTimeMillis())

    println("[canvas-flash] plainPeer=${"%.2f".format(plain)}, emptyPeer=${"%.2f".format(guarded)}")
    assumeTrue(
      plain >= FLASH_LIMIT,
      "A plain heavyweight peer did not flash in this environment (bright=$plain), nothing to compare against",
    )
    assertAll(
      { assertTrue(guarded < FLASH_LIMIT) { "A peer created empty still flashed white (bright=$guarded)" } },
      { assertTrue(guarded < plain) { "Creating the peer empty did not reduce the flash ($guarded vs $plain)" } },
    )
  }

  private fun measureReattachFlash(peerStartsEmpty: Boolean, reportName: String): Double {
    val canvas = ProbeCanvas(peerStartsEmpty)
    val host = JPanel(BorderLayout()).apply {
      background = STAGE_BACKGROUND
      add(canvas, BorderLayout.CENTER)
    }
    val slotA = createSlot()
    val slotB = createSlot()
    var currentSlot = slotA
    val frame = onEdt {
      JFrame("Heavyweight Canvas Reattach Flash").apply {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        contentPane.background = STAGE_BACKGROUND
        contentPane.layout = GridLayout(1, 2, 12, 0)
        contentPane.add(slotA)
        contentPane.add(slotB)
        setSize(1240, 760)
        setLocationRelativeTo(null)
        isAlwaysOnTop = true
        slotA.add(host, BorderLayout.CENTER)
        isVisible = true
        toFront()
      }
    }
    Thread.sleep(SETTLE_MILLIS)

    val recorder = onEdt {
      val location = frame.locationOnScreen
      WebViewFlashRecorder(Rectangle(location.x, location.y, frame.width, frame.height), reportName)
    }
    recorder.start()
    try {
      repeat(CYCLES) {
        val target = if (currentSlot === slotA) slotB else slotA
        onEdt {
          currentSlot.remove(host)
          currentSlot.revalidate()
          currentSlot.repaint()
        }
        Thread.sleep(PAUSE_MILLIS)
        onEdt {
          target.add(host, BorderLayout.CENTER)
          target.revalidate()
          target.repaint()
        }
        currentSlot = target
        Thread.sleep(PAUSE_MILLIS)
      }
    }
    finally {
      println(recorder.stopAndReport())
      onEdt { frame.dispose() }
    }
    return recorder.maxBrightFraction
  }

  private fun createSlot(): JPanel {
    return JPanel(BorderLayout()).apply {
      background = STAGE_BACKGROUND
      border = BorderFactory.createLineBorder(Color(0x3C3F45))
    }
  }

  private fun <T> onEdt(block: () -> T): T {
    var result: T? = null
    SwingUtilities.invokeAndWait { result = block() }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  /** The same peer-creation strategy `WinWebViewEngine` applies to its Canvas, switchable for the comparison. */
  private class ProbeCanvas(private val peerStartsEmpty: Boolean) : Canvas() {
    init {
      background = STAGE_BACKGROUND
    }

    override fun addNotify() {
      if (!peerStartsEmpty) {
        super.addNotify()
        return
      }
      val beforePeerCreation = bounds
      setBounds(0, 0, 0, 0)
      super.addNotify()
      revalidate()
      SwingUtilities.invokeLater {
        if (isDisplayable && (width == 0 || height == 0) && !beforePeerCreation.isEmpty) {
          bounds = beforePeerCreation
        }
      }
    }
  }

  private companion object {
    private val STAGE_BACKGROUND = Color(0x1E1F22)

    /** The window title bar is bright on its own, so the limit sits well above it. */
    private const val FLASH_LIMIT = 0.10
    private const val CYCLES = 4
    private const val PAUSE_MILLIS = 700L
    private const val SETTLE_MILLIS = 1_200L
  }
}
