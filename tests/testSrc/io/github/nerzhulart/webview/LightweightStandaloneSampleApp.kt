// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview

import com.intellij.openapi.util.SystemInfo
import io.github.nerzhulart.webview.api.WebViewAssetPath
import io.github.nerzhulart.webview.api.WebViewAssetRoot
import io.github.nerzhulart.webview.impl.engine.WebViewEngine
import io.github.nerzhulart.webview.impl.engine.WebViewEngineCreationOptions
import io.github.nerzhulart.webview.impl.engine.WebViewEngineProvider
import io.github.nerzhulart.webview.impl.SwingWebViewHostPanel
import io.github.nerzhulart.webview.impl.mac.MacWkWebViewEngineProvider
import io.github.nerzhulart.webview.impl.windows.WindowsWebView2EngineProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.intellij.lang.annotations.Language
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.HierarchyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * Lightweight standalone sample app for manual smoke checks without plugin wiring.
 *
 * The stand separates the two event flows the Windows engine has to serve:
 * - the heavy path: moving the host between slots destroys and recreates the AWT canvas peer;
 * - the light path: the `Visible` checkbox only flips Swing visibility, the peer stays alive.
 */
object LightweightStandaloneSampleApp {
  private const val RESOURCE_ROOT = "webview/views/sample-panel"
  private val ASSET_ROOT = WebViewAssetRoot.fromClasspath(LightweightStandaloneSampleApp::class.java, WebViewAssetPath.of(RESOURCE_ROOT))
  private val STAGE_BACKGROUND = Color(0x1E1F22)
  private val STAGE_FOREGROUND = Color(0xD0D4DA)
  private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss,SSS")

  @JvmStatic
  fun main(args: Array<String>) {
    SwingUtilities.invokeLater {
      @Suppress("RAW_SCOPE_CREATION") // Standalone sample: no parent scope available.
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val provider: WebViewEngineProvider = when {
        SystemInfo.isMac -> MacWkWebViewEngineProvider()
        SystemInfo.isWindows -> WindowsWebView2EngineProvider()
        else -> error("System WebView sample is supported only on macOS and Windows")
      }
      val webViewEngine = provider.createEngine(
        scope,
        WebViewEngineCreationOptions(
          debugName = null,
        ),
      )
      val hostPanel = SwingWebViewHostPanel(scope, webViewEngine)
      val stage = Stage(hostPanel)

      val frame = JFrame("WebView Lightweight Standalone Sample").apply {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        contentPane.background = STAGE_BACKGROUND
        contentPane.layout = BorderLayout()
        size = Dimension(1240, 760)
        setLocationRelativeTo(null)
        addWindowListener(object : WindowAdapter() {
          override fun windowClosing(event: WindowEvent) {
            scope.launch {
              webViewEngine.close()
              scope.cancel()
            }
          }
        })
      }

      frame.contentPane.add(buildControls(scope, webViewEngine, hostPanel, stage, frame), BorderLayout.NORTH)
      frame.contentPane.add(stage.root, BorderLayout.CENTER)
      frame.isVisible = true

      loadSampleFromResources(scope, webViewEngine, stage)
      WebViewGeometryProbe.startDraining(
        scope = scope,
        evaluate = { script -> webViewEngine.evaluateJavaScript(script) },
        sink = { line -> println(line) },
      )
    }
  }

  private fun buildControls(
    scope: CoroutineScope,
    webViewEngine: WebViewEngine,
    hostPanel: SwingWebViewHostPanel,
    stage: Stage,
    frame: JFrame,
  ): JPanel {
    val slotA = JRadioButton("Slot A", true)
    val slotB = JRadioButton("Slot B")
    val detached = JRadioButton("Detached")
    ButtonGroup().apply {
      add(slotA)
      add(slotB)
      add(detached)
    }
    slotA.addActionListener { stage.moveHostTo(Stage.Target.SLOT_A) }
    slotB.addActionListener { stage.moveHostTo(Stage.Target.SLOT_B) }
    detached.addActionListener { stage.moveHostTo(Stage.Target.DETACHED) }

    val visible = JCheckBox("Visible", true).apply {
      addActionListener { hostPanel.isVisible = isSelected }
    }
    val alwaysOnTop = JCheckBox("Always on top").apply {
      addActionListener { frame.isAlwaysOnTop = isSelected }
    }
    val reload = JButton("Reload sample HTML").apply {
      addActionListener { loadSampleFromResources(scope, webViewEngine, stage) }
    }

    return JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
      background = STAGE_BACKGROUND
      add(themed(JLabel("Host:")))
      add(themed(slotA))
      add(themed(slotB))
      add(themed(detached))
      add(themed(visible))
      add(themed(alwaysOnTop))
      add(reload)
    }
  }

  private fun <T : Component> themed(component: T): T = component.apply {
    background = STAGE_BACKGROUND
    foreground = STAGE_FOREGROUND
  }

  private fun loadSampleFromResources(scope: CoroutineScope, facade: WebViewEngine, stage: Stage) {
    scope.launch {
      try {
        facade.loadAsset(ASSET_ROOT)
        SwingUtilities.invokeLater { stage.log("loaded: $RESOURCE_ROOT") }
      }
      catch (t: Throwable) {
        facade.loadHtml(FALLBACK_HTML)
        SwingUtilities.invokeLater { stage.log("fallback HTML loaded (${t::class.java.simpleName})") }
      }
      // A reload drops the page state, so the probe is reinstalled with every load.
      facade.evaluateJavaScript(WebViewGeometryProbe.INSTALL_SCRIPT)
    }
  }

  /** Two side-by-side slots the host is moved between, plus a status line for eyeball runs. */
  private class Stage(private val host: SwingWebViewHostPanel) {
    enum class Target { SLOT_A, SLOT_B, DETACHED }

    private val statusLabel = JLabel("starting")
    private val slotA = createSlot("Slot A")
    private val slotB = createSlot("Slot B")
    private var current: JPanel? = slotA

    val root: JPanel = JPanel(BorderLayout(0, 8)).apply {
      background = STAGE_BACKGROUND
      border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
      add(statusLabel.apply { foreground = STAGE_FOREGROUND }, BorderLayout.SOUTH)
      add(JPanel(GridLayout(1, 2, 12, 0)).apply {
        background = STAGE_BACKGROUND
        add(slotA)
        add(slotB)
      }, BorderLayout.CENTER)
    }

    init {
      slotA.add(host, BorderLayout.CENTER)
      host.addHierarchyListener { event ->
        if (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L) {
          log("peer ${if (host.isDisplayable) "created" else "disposed"}")
        }
        if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
          log("showing=${host.isShowing}")
        }
      }
    }

    fun moveHostTo(target: Target) {
      current?.let {
        it.remove(host)
        it.revalidate()
        it.repaint()
      }
      current = when (target) {
        Target.SLOT_A -> slotA
        Target.SLOT_B -> slotB
        Target.DETACHED -> null
      }
      current?.let {
        it.add(host, BorderLayout.CENTER)
        it.revalidate()
        it.repaint()
      }
      log("host -> ${target.name.lowercase()}")
    }

    fun log(text: String) {
      val timestamp = LocalTime.ofInstant(Instant.now(), ZoneId.systemDefault()).format(TIMESTAMP_FORMAT)
      statusLabel.text = "$timestamp $text"
      println("[sample-app] $timestamp $text")
    }

    private fun createSlot(title: String): JPanel {
      return JPanel(BorderLayout()).apply {
        background = STAGE_BACKGROUND
        border = BorderFactory.createTitledBorder(
          BorderFactory.createLineBorder(Color(0x3C3F45)),
          title,
        ).apply { titleColor = STAGE_FOREGROUND }
      }
    }
  }

  @Language("HTML")
  private val FALLBACK_HTML = """
    <!DOCTYPE html>
    <html lang="en">
    <head>
      <meta charset="UTF-8">
      <meta name="viewport" content="width=device-width, initial-scale=1.0">
      <title>WebView Lightweight Sample</title>
      <style>
        body { font-family: -apple-system, sans-serif; margin: 16px; background: #1e1e1e; color: #d4d4d4; }
        input, button { padding: 8px; margin-right: 8px; }
        .list { margin-top: 12px; max-height: 260px; overflow-y: auto; border: 1px solid #444; }
        .item { padding: 8px; border-bottom: 1px solid #333; }
        .bar { margin-top: 12px; width: 60%; height: 14px; background: #1f2937; position: relative; overflow: hidden; border-radius: 7px; }
        .knob { position: absolute; top: 0; left: 0; width: 20%; height: 100%; background: #4f9cf9; border-radius: 7px; }
      </style>
    </head>
    <body>
      <h2>WebView lightweight sample</h2>
      <input id="input" type="text" placeholder="Type here to test keyboard input">
      <button onclick="document.getElementById('status').textContent = document.getElementById('input').value || 'Empty input'">Read input</button>
      <span id="status">Ready</span>
      <div class="bar"><div class="knob" id="knob"></div></div>
      <div id="frames">frames: 0</div>
      <div class="list" id="list"></div>
      <script>
        const list = document.getElementById('list');
        for (let i = 1; i <= 100; i++) {
          const item = document.createElement('div');
          item.className = 'item';
          item.textContent = 'Scrollable item ' + i;
          list.appendChild(item);
        }
        const knob = document.getElementById('knob');
        const frames = document.getElementById('frames');
        let count = 0;
        function tick() {
          count++;
          knob.style.left = ((count % 100) * 0.8) + '%';
          frames.textContent = 'frames: ' + count;
          requestAnimationFrame(tick);
        }
        requestAnimationFrame(tick);
      </script>
    </body>
    </html>
  """.trimIndent()
}
