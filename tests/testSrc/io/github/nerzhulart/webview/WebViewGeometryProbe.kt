// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.intellij.lang.annotations.Language
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reports the placement the page itself observes: viewport size, on-screen position, visibility and
 * the animation frame rhythm. The Swing and native sides only know what they asked for - this is the
 * other end of the pipe, so a flash can be attributed to a resize, to a move, or to a missing frame.
 *
 * Entries are buffered inside the page and drained from Kotlin, because during a reattach the page
 * may be throttled and its own console output would arrive out of order.
 */
internal object WebViewGeometryProbe {
  private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss,SSS")
  private val JSON = Json { ignoreUnknownKeys = true }

  @Language("JavaScript")
  val INSTALL_SCRIPT: String = """
    (function() {
      if (window.__WV_GEOM__) return 'installed';
      const state = {
        log: [],
        frames: 0,
        lastFrame: performance.now(),
        sx: window.screenX,
        sy: window.screenY,
        w: window.innerWidth,
        h: window.innerHeight,
        dpr: window.devicePixelRatio,
      };
      window.__WV_GEOM__ = state;
      function push(kind, extra) {
        state.log.push({
          t: Math.round(performance.timeOrigin + performance.now()),
          kind: kind,
          w: window.innerWidth,
          h: window.innerHeight,
          sx: window.screenX,
          sy: window.screenY,
          dpr: window.devicePixelRatio,
          vis: document.visibilityState,
          frames: state.frames,
          extra: extra || '',
        });
        if (state.log.length > 500) state.log.shift();
      }
      push('install');
      window.addEventListener('resize', function() { push('resize'); });
      document.addEventListener('visibilitychange', function() { push('visibility'); });
      if (window.ResizeObserver) {
        new ResizeObserver(function(entries) {
          const rect = entries[0].contentRect;
          push('observer', Math.round(rect.width) + 'x' + Math.round(rect.height));
        }).observe(document.documentElement);
      }
      function frame(now) {
        state.frames++;
        const gap = now - state.lastFrame;
        state.lastFrame = now;
        if (gap > 200) push('frame-gap', Math.round(gap) + 'ms');
        requestAnimationFrame(frame);
      }
      requestAnimationFrame(frame);
      setInterval(function() {
        if (window.screenX !== state.sx || window.screenY !== state.sy) {
          state.sx = window.screenX;
          state.sy = window.screenY;
          push('move');
        }
        if (window.innerWidth !== state.w || window.innerHeight !== state.h) {
          state.w = window.innerWidth;
          state.h = window.innerHeight;
          push('size');
        }
        if (window.devicePixelRatio !== state.dpr) {
          state.dpr = window.devicePixelRatio;
          push('dpr');
        }
      }, 30);
      return 'installed';
    })()
  """.trimIndent()

  @Language("JavaScript")
  private val DRAIN_SCRIPT: String = """
    (function() {
      const state = window.__WV_GEOM__;
      if (!state) return '[]';
      const drained = state.log;
      state.log = [];
      return JSON.stringify(drained);
    })()
  """.trimIndent()

  /**
   * Polls the buffer and hands every entry to [sink] as a single line. [evaluate] is the raw
   * JavaScript evaluation of the engine under test, so both the panel API and the engine API fit.
   */
  fun startDraining(
    scope: CoroutineScope,
    interval: Duration = 150.milliseconds,
    evaluate: suspend (String) -> String?,
    sink: (String) -> Unit,
  ): Job = scope.launch {
    while (isActive) {
      runCatching { evaluate(DRAIN_SCRIPT) }
        .getOrNull()
        ?.let { raw -> formatEntries(raw).forEach(sink) }
      delay(interval)
    }
  }

  fun formatEntries(rawResult: String): List<String> {
    val payload = unwrapJsonString(rawResult) ?: return emptyList()
    val entries = runCatching { JSON.parseToJsonElement(payload).jsonArray }.getOrNull() ?: return emptyList()
    return entries.map { element ->
      val entry = element.jsonObject
      fun field(name: String): String = entry[name]?.jsonPrimitive?.content.orEmpty()
      val millis = field("t").toLongOrNull() ?: System.currentTimeMillis()
      val stamp = LocalTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()).format(TIMESTAMP_FORMAT)
      val extra = field("extra").takeIf { it.isNotEmpty() }?.let { " extra=$it" }.orEmpty()
      "[wv-geom] $stamp kind=${field("kind")} size=${field("w")}x${field("h")}" +
        " screen=(${field("sx")},${field("sy")}) dpr=${field("dpr")}" +
        " vis=${field("vis")} frames=${field("frames")}$extra"
    }
  }

  /** `evaluateJavaScript` may return the raw string or a JSON-quoted one, depending on the backend. */
  private fun unwrapJsonString(rawResult: String): String? {
    val trimmed = rawResult.trim()
    if (trimmed.isEmpty() || trimmed == "null") return null
    if (trimmed.startsWith("[")) return trimmed
    return runCatching { JSON.parseToJsonElement(trimmed).jsonPrimitive.content }.getOrNull()
  }
}
