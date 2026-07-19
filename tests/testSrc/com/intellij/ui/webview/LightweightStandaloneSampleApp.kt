// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.webview

import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.webview.api.WebViewAssetPath
import com.intellij.ui.webview.api.WebViewAssetRoot
import com.intellij.ui.webview.impl.SwingWebViewHostPanel
import com.intellij.ui.webview.impl.WebViewController
import com.intellij.ui.webview.impl.WebViewHostEventSink
import com.intellij.ui.webview.impl.mac.createMacWkWebViewController
import com.intellij.ui.webview.impl.windows.createWinWebViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.intellij.lang.annotations.Language
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicReference
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.SwingUtilities
import javax.swing.JToolBar
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.WindowConstants
import javax.swing.event.ListSelectionEvent

/**
 * Lightweight standalone sample app for manual smoke checks without plugin wiring.
 */
object LightweightStandaloneSampleApp {
  private const val RESOURCE_ROOT = "webview/views/sample-panel"
  private val ASSET_ROOT = WebViewAssetRoot.fromClasspath(LightweightStandaloneSampleApp::class.java, WebViewAssetPath.of(RESOURCE_ROOT))

  @JvmStatic
  fun main(args: Array<String>) {
    SwingUtilities.invokeLater {
      @Suppress("RAW_SCOPE_CREATION") // Standalone sample: no parent scope available.
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val hostPanelRef = AtomicReference<SwingWebViewHostPanel>()
      val eventSink = WebViewHostEventSink { event -> hostPanelRef.get()?.handleHostEvent(event) ?: false }
      val facade = when {
        SystemInfo.isMac -> createMacWkWebViewController(scope, emptyList(), eventSink)
        SystemInfo.isWindows -> createWinWebViewController(scope, hostEventSink = eventSink)
        else -> error("System WebView sample is supported only on macOS and Windows")
      }
      val hostPanel = SwingWebViewHostPanel(scope, facade).also(hostPanelRef::set)
      val statusLabel = JLabel("Ready")

      val frame = JFrame("IDE-like WebView Host Sample").apply {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        setSize(1180, 760)
        layout = BorderLayout()
        jMenuBar = createMenuBar(this, scope, facade, statusLabel)
        addWindowListener(object : WindowAdapter() {
          override fun windowClosing(event: WindowEvent) {
            scope.launch {
              facade.close()
              scope.cancel()
            }
          }
        })
      }

      val controls = JToolBar().apply {
        isFloatable = false
        add(JButton("Demo HTML").apply {
          toolTipText = "Load the built-in WebView controls demo"
          addActionListener { loadDemoHtml(scope, facade, statusLabel) }
        })
        add(JButton("Asset").apply {
          toolTipText = "Load the classpath sample asset"
          addActionListener { loadSampleFromResources(scope, facade, statusLabel) }
        })
        add(JButton("Swing Popup").apply {
          addActionListener { showSwingPopup(this) }
        })
        add(JButton("Dialog").apply {
          addActionListener {
            JOptionPane.showMessageDialog(frame, "Swing dialog over a hosted WebView", "Dialog", JOptionPane.INFORMATION_MESSAGE)
          }
        })
        add(statusLabel)
      }

      frame.add(controls, BorderLayout.NORTH)
      frame.add(createWorkbench(hostPanel, facade, statusLabel), BorderLayout.CENTER)
      frame.isVisible = true

      loadDemoHtml(scope, facade, statusLabel)
    }
  }

  private fun createWorkbench(hostPanel: SwingWebViewHostPanel, facade: WebViewController, statusLabel: JLabel): JSplitPane {
    val model = DefaultListModel<String>().apply {
      addElement("Welcome")
      addElement("Form controls")
      addElement("Popup playground")
      addElement("Scrolling document")
    }
    val list = JList(model).apply {
      border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
      addListSelectionListener { event: ListSelectionEvent ->
        if (!event.valueIsAdjusting) {
          statusLabel.text = "Selected: ${selectedValue ?: "none"}"
        }
      }
    }
    val left = JPanel(BorderLayout()).apply {
      preferredSize = Dimension(230, 400)
      add(JLabel("Project"), BorderLayout.NORTH)
      add(JScrollPane(list), BorderLayout.CENTER)
    }

    val notes = JTextArea("Swing notes panel\n\nTry focus traversal, Ctrl+C/V in the WebView, and popups from both sides.").apply {
      lineWrap = true
      wrapStyleWord = true
    }
    val inspector = JPanel(BorderLayout()).apply {
      preferredSize = Dimension(260, 400)
      add(JLabel("Inspector"), BorderLayout.NORTH)
      add(JScrollPane(notes), BorderLayout.CENTER)
      add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        add(JCheckBox("Flag"))
        add(JComboBox(arrayOf("Debug", "Preview", "Release")))
      }, BorderLayout.SOUTH)
    }

    val center = JPanel(BorderLayout()).apply {
      add(JTextField("Swing search field above the WebView"), BorderLayout.NORTH)
      add(hostPanel, BorderLayout.CENTER)
    }
    val rightSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, inspector).apply {
      resizeWeight = 1.0
    }
    return JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, rightSplit).apply {
      resizeWeight = 0.0
    }
  }

  private fun createMenuBar(frame: JFrame, scope: CoroutineScope, facade: WebViewController, statusLabel: JLabel): JMenuBar {
    return JMenuBar().apply {
      add(JMenu("File").apply {
        add(JMenuItem("Load Demo HTML").apply {
          addActionListener { loadDemoHtml(scope, facade, statusLabel) }
        })
        add(JMenuItem("About").apply {
          addActionListener {
            JOptionPane.showMessageDialog(frame, "Standalone WebView hosting smoke app", "About", JOptionPane.INFORMATION_MESSAGE)
          }
        })
      })
      add(JMenu("View").apply {
        add(JMenuItem("Show Swing Popup").apply {
          addActionListener { showSwingPopup(frame) }
        })
      })
    }
  }

  private fun showSwingPopup(invoker: java.awt.Component) {
    JPopupMenu().apply {
      add(JMenuItem("Popup action"))
      add(JMenuItem("Another action"))
      show(invoker, 12, invoker.height.coerceAtLeast(20))
    }
  }

  private fun loadSampleFromResources(scope: CoroutineScope, facade: WebViewController, statusLabel: JLabel) {
    scope.launch {
      try {
        facade.loadAsset(ASSET_ROOT, WebViewAssetPath.indexHtml(), null)
        SwingUtilities.invokeLater {
          statusLabel.text = "Loaded: $RESOURCE_ROOT"
        }
      }
      catch (t: Throwable) {
        facade.loadHtml(FALLBACK_HTML, null)
        SwingUtilities.invokeLater {
          statusLabel.text = "Fallback HTML loaded (${t::class.java.simpleName})"
        }
      }
    }
  }

  private fun loadDemoHtml(scope: CoroutineScope, facade: WebViewController, statusLabel: JLabel) {
    scope.launch {
      facade.loadHtml(FALLBACK_HTML, null)
      SwingUtilities.invokeLater {
        statusLabel.text = "Demo HTML loaded"
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
        :root { color-scheme: light dark; }
        body { font-family: -apple-system, BlinkMacSystemFont, Segoe UI, sans-serif; margin: 0; background: #f7f8fa; color: #1f2328; }
        header { display: flex; align-items: center; gap: 10px; padding: 12px 16px; border-bottom: 1px solid #d8dee4; background: #ffffff; }
        main { display: grid; grid-template-columns: minmax(240px, 1fr) 320px; gap: 14px; padding: 14px; }
        section { min-width: 0; }
        input, button, select, textarea { font: inherit; padding: 7px 9px; margin: 4px 4px 4px 0; }
        textarea { width: min(680px, 100%); height: 90px; box-sizing: border-box; }
        .row { margin: 8px 0; }
        .panel { border: 1px solid #d8dee4; background: #ffffff; padding: 12px; }
        .list { margin-top: 12px; max-height: 280px; overflow-y: auto; border: 1px solid #d8dee4; background: #fbfbfc; }
        .item { padding: 8px; border-bottom: 1px solid #eaeef2; }
        #popup, #context { position: fixed; display: none; z-index: 10; min-width: 190px; border: 1px solid #9da7b1; background: white; box-shadow: 0 8px 22px #0002; padding: 8px; }
        #context button { display: block; width: 100%; text-align: left; background: transparent; border: 0; }
        @media (prefers-color-scheme: dark) {
          body { background: #1f2328; color: #d4d4d4; }
          header, .panel, #popup, #context { background: #25292e; border-color: #444c56; }
          .list { background: #1f2328; border-color: #444c56; }
          .item { border-bottom-color: #373e47; }
        }
      </style>
    </head>
    <body oncontextmenu="openContext(event)">
      <header>
        <strong>WebView editor tab</strong>
        <button onclick="openPopup(event)">Custom popup</button>
        <button onclick="document.querySelector('dialog').showModal()">HTML dialog</button>
        <select><option>Preview</option><option>Inspect</option><option>Debug</option></select>
        <span id="status">Ready</span>
      </header>
      <main>
        <section class="panel">
          <div class="row">
            <input id="input" type="text" placeholder="Type here to test keyboard input">
            <button onclick="readInput()">Read input</button>
          </div>
          <div class="row">
            <textarea placeholder="Textarea for selection, copy, paste, and focus checks"></textarea>
          </div>
          <div class="list" id="list"></div>
        </section>
        <aside class="panel">
          <label><input type="checkbox"> Web checkbox</label>
          <p>Right-click anywhere in the WebView for a custom context menu.</p>
          <button onclick="document.getElementById('status').textContent = 'Button clicked at ' + new Date().toLocaleTimeString()">Update status</button>
        </aside>
      </main>
      <div id="popup">
        <strong>Custom Web popup</strong>
        <p>Focus should stay stable while this opens.</p>
        <button onclick="closePopup()">Close</button>
      </div>
      <div id="context">
        <button onclick="pickContext('Refactor')">Refactor</button>
        <button onclick="pickContext('Run')">Run</button>
        <button onclick="pickContext('Close')">Close</button>
      </div>
      <dialog>
        <form method="dialog">
          <p>Native HTML dialog inside WebView.</p>
          <button>Close</button>
        </form>
      </dialog>
      <script>
        function readInput() {
          document.getElementById('status').textContent = document.getElementById('input').value || 'Empty input';
        }
        function openPopup(event) {
          const popup = document.getElementById('popup');
          popup.style.display = 'block';
          popup.style.left = event.clientX + 'px';
          popup.style.top = event.clientY + 8 + 'px';
        }
        function closePopup() {
          document.getElementById('popup').style.display = 'none';
        }
        function openContext(event) {
          event.preventDefault();
          const menu = document.getElementById('context');
          menu.style.display = 'block';
          menu.style.left = event.clientX + 'px';
          menu.style.top = event.clientY + 'px';
        }
        function pickContext(action) {
          document.getElementById('status').textContent = action + ' selected';
          document.getElementById('context').style.display = 'none';
        }
        document.addEventListener('click', event => {
          if (!event.target.closest('#context')) document.getElementById('context').style.display = 'none';
        });
        const list = document.getElementById('list');
        for (let i = 1; i <= 100; i++) {
          const item = document.createElement('div');
          item.className = 'item';
          item.textContent = 'Scrollable item ' + i;
          list.appendChild(item);
        }
      </script>
    </body>
    </html>
  """.trimIndent()
}
