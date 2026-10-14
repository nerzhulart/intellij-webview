// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview

import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

internal fun webViewLogTimestamp(millis: Long = System.currentTimeMillis()): String {
  return LocalTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("HH:mm:ss,SSS"))
}

/**
 * Captures a screen region in a tight loop and reports two numbers: how much of a frame changed at
 * once, and how much of it is near-white. The second one matters, because the flash we hunt is an
 * empty window surface over dark UI - dark content replacing a dark background changes a lot of
 * pixels without ever being visible as a flash, and vice versa.
 *
 * Brightness only describes a flash over dark UI. With [expectedRgb] the recorder also reports how
 * much of a frame is far from that color, which catches a dark frame over light UI just as well -
 * the region is expected to show nothing but the host background and a page painted with it.
 *
 * Frames crossing either threshold are written as PNGs; timestamps use the idea.log format so they
 * can be lined up with the native `geom.*` diagnostics.
 */
internal class WebViewFlashRecorder(
  private val screenRegion: Rectangle,
  private val reportName: String = "run-" + System.currentTimeMillis(),
  private val expectedRgb: Int? = null,
) {
  private val robot = Robot()
  private val outputDir: Path = Path.of("build", "reports", "webview-flash", reportName)

  @Volatile
  private var running = true
  private var samples = 0
  private var savedImages = 0
  private val events = StringBuilder()
  private val thread = Thread({ captureLoop() }, "webview-flash-recorder").apply { isDaemon = true }

  @Volatile
  var maxChangedFraction: Double = 0.0
    private set

  @Volatile
  var maxBrightFraction: Double = 0.0
    private set

  /** Largest share of a frame far from [expectedRgb]; stays `0` without it. */
  @Volatile
  var maxOffColorFraction: Double = 0.0
    private set

  fun start() {
    Files.createDirectories(outputDir)
    thread.start()
  }

  fun stopAndReport(): String {
    running = false
    thread.join(5_000)
    return buildString {
      append(
        "[flash-recorder] $reportName samples=$samples" +
          ", maxChangedFraction=${"%.2f".format(maxChangedFraction)}" +
          ", maxBrightFraction=${"%.2f".format(maxBrightFraction)}" +
          (if (expectedRgb != null) ", maxOffColorFraction=${"%.2f".format(maxOffColorFraction)}" else "") +
          ", dir=$outputDir\n"
      )
      append(events)
    }
  }

  private fun captureLoop() {
    var previous: BufferedImage? = null
    while (running) {
      val image = robot.createScreenCapture(screenRegion)
      val now = System.currentTimeMillis()
      val before = previous
      previous = image
      if (before == null) {
        ImageIO.write(image, "png", outputDir.resolve("first-frame.png").toFile())
        continue
      }
      samples++
      val fraction = changedFraction(before, image)
      if (fraction > maxChangedFraction) maxChangedFraction = fraction
      val bright = brightFraction(image)
      if (bright > maxBrightFraction) maxBrightFraction = bright
      // Over light UI every frame is bright; the off-color check below is the one that means a flash.
      if (expectedRgb == null && bright >= BRIGHT_THRESHOLD && savedImages < MAX_SAVED_IMAGES) {
        val brightFile = outputDir.resolve("${timestampForFileName(now)}-bright.png")
        ImageIO.write(image, "png", brightFile.toFile())
        savedImages++
        events.append("[flash-recorder] BRIGHT FRAME fraction=${"%.2f".format(bright)} at ${webViewLogTimestamp(now)} -> ${brightFile.fileName}\n")
      }
      if (expectedRgb != null) {
        val offColor = offColorFraction(image, expectedRgb)
        if (offColor > maxOffColorFraction) maxOffColorFraction = offColor
        if (offColor >= OFF_COLOR_THRESHOLD && savedImages < MAX_SAVED_IMAGES) {
          val offColorFile = outputDir.resolve("${timestampForFileName(now)}-off-color.png")
          ImageIO.write(image, "png", offColorFile.toFile())
          savedImages++
          events.append("[flash-recorder] OFF-COLOR FRAME fraction=${"%.2f".format(offColor)} at ${webViewLogTimestamp(now)} -> ${offColorFile.fileName}\n")
        }
      }
      if (fraction >= BIG_CHANGE_THRESHOLD && savedImages < MAX_SAVED_IMAGES) {
        val stamp = timestampForFileName(now)
        ImageIO.write(before, "png", outputDir.resolve("$stamp-before.png").toFile())
        ImageIO.write(image, "png", outputDir.resolve("$stamp-after.png").toFile())
        savedImages += 2
        events.append("[flash-recorder] BIG CHANGE fraction=${"%.2f".format(fraction)} at ${webViewLogTimestamp(now)} -> $stamp-*.png\n")
      }
    }
  }

  private fun changedFraction(a: BufferedImage, b: BufferedImage): Double {
    var changed = 0
    var total = 0
    var y = 0
    val height = minOf(a.height, b.height)
    val width = minOf(a.width, b.width)
    while (y < height) {
      var x = 0
      while (x < width) {
        val pa = a.getRGB(x, y)
        val pb = b.getRGB(x, y)
        if (colorDistance(pa, pb) > 60) changed++
        total++
        x += SAMPLE_STEP
      }
      y += SAMPLE_STEP
    }
    return if (total == 0) 0.0 else changed.toDouble() / total
  }

  /** Share of sampled pixels that are near-white; a window title bar alone stays well below the threshold. */
  private fun brightFraction(image: BufferedImage): Double {
    var bright = 0
    var total = 0
    var y = 0
    while (y < image.height) {
      var x = 0
      while (x < image.width) {
        val pixel = image.getRGB(x, y)
        val luminance = ((pixel ushr 16 and 0xFF) * 3 + (pixel ushr 8 and 0xFF) * 6 + (pixel and 0xFF)) / 10
        if (luminance > 200) bright++
        total++
        x += SAMPLE_STEP
      }
      y += SAMPLE_STEP
    }
    return if (total == 0) 0.0 else bright.toDouble() / total
  }

  private fun timestampForFileName(millis: Long): String {
    return LocalTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
      .format(DateTimeFormatter.ofPattern("HH-mm-ss-SSS"))
  }

  companion object {
    /** Share of sampled pixels that a viewer tells apart from [rgb]. */
    fun offColorFraction(image: BufferedImage, rgb: Int): Double {
      var off = 0
      var total = 0
      var y = 0
      while (y < image.height) {
        var x = 0
        while (x < image.width) {
          if (colorDistance(image.getRGB(x, y), rgb) > 60) off++
          total++
          x += SAMPLE_STEP
        }
        y += SAMPLE_STEP
      }
      return if (total == 0) 0.0 else off.toDouble() / total
    }

    fun colorDistance(a: Int, b: Int): Int {
      return kotlin.math.abs((a ushr 16 and 0xFF) - (b ushr 16 and 0xFF)) +
        kotlin.math.abs((a ushr 8 and 0xFF) - (b ushr 8 and 0xFF)) +
        kotlin.math.abs((a and 0xFF) - (b and 0xFF))
    }

    private const val SAMPLE_STEP = 8
    private const val BIG_CHANGE_THRESHOLD = 0.30
    private const val BRIGHT_THRESHOLD = 0.10
    private const val OFF_COLOR_THRESHOLD = 0.10
    private const val MAX_SAVED_IMAGES = 40
  }
}
