// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.webview.impl.windows

import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.webview.impl.NativeBridgeLibrary
import com.intellij.ui.webview.impl.webViewNativeArchDirectory
import org.jetbrains.annotations.ApiStatus

internal val winWebView2BridgeLibrary = NativeBridgeLibrary(
  displayName = "Windows WebView2 bridge DLL",
  logEvent = "win-webview2-load",
  relativePaths = listOf("lib/webview-native/win/${webViewNativeArchDirectory()}/win_webview2_bridge.dll"),
  rebuildHint = "Rebuild community/plugins/ui.webview/native/WinWebView2Bridge.",
  loadFailureHint = "Ensure the DLL matches the current JVM architecture and WebView2 runtime dependencies are installed. " +
                    "Rebuild community/plugins/ui.webview/native/WinWebView2Bridge.",
  pluginAnchorClass = WinWebView2BridgePluginAnchor::class.java,
)

private class WinWebView2BridgePluginAnchor

@ApiStatus.Internal
internal object WinWebView2Bridge {
  private const val EXPECTED_NATIVE_ABI_VERSION = "wvi-host-controller-v14"

  init {
    if (SystemInfo.isWindows) {
      loadNativeLibrary()
    }
  }

  @JvmStatic
  private external fun abiVersionNative(): String

  @JvmStatic
  private external fun createNative(
    hostHwnd: Long,
    userDataDir: String,
    documentStartScript: String,
    configurationFlags: Long,
    callbacks: Callbacks,
  ): Long

  @JvmStatic
  private external fun destroyNative(handle: Long)

  @JvmStatic
  private external fun setBoundsNative(handle: Long, x: Int, y: Int, width: Int, height: Int, scale: Double)

  @JvmStatic
  private external fun setVisibleNative(handle: Long, visible: Boolean)

  @JvmStatic
  private external fun focusNative(handle: Long)

  @JvmStatic
  private external fun loadUrlNative(handle: Long, url: String)

  @JvmStatic
  private external fun setVirtualHostNameToFolderMappingNative(handle: Long, hostName: String, folderPath: String)

  @JvmStatic
  private external fun loadHtmlNative(handle: Long, html: String, baseUrl: String?)

  @JvmStatic
  private external fun evaluateJavaScriptNative(handle: Long, evalId: Long, script: String)

  @JvmStatic
  private external fun callDevToolsProtocolMethodNative(handle: Long, callId: Long, methodName: String, paramsJson: String)

  @JvmStatic
  private external fun transferToJsNative(handle: Long, rawJson: String)

  @JvmStatic
  fun create(
    hostHwnd: Long,
    userDataDir: String,
    documentStartScript: String,
    configuration: WinWebView2Configuration,
    callbacks: Callbacks,
  ): Long {
    require(hostHwnd != 0L) { "WebView2 host HWND must not be 0" }
    return createNative(hostHwnd, userDataDir, documentStartScript, configuration.toNativeFlags(), callbacks)
  }

  fun destroy(handle: Long) = destroyNative(handle)
  fun setBounds(handle: Long, x: Int, y: Int, width: Int, height: Int, scale: Double) = setBoundsNative(handle, x, y, width, height, scale)
  fun setVisible(handle: Long, visible: Boolean) = setVisibleNative(handle, visible)
  fun focus(handle: Long) = focusNative(handle)
  fun loadUrl(handle: Long, url: String) = loadUrlNative(handle, url)
  fun setVirtualHostNameToFolderMapping(handle: Long, hostName: String, folderPath: String) =
    setVirtualHostNameToFolderMappingNative(handle, hostName, folderPath)

  fun loadHtml(handle: Long, html: String, baseUrl: String?) = loadHtmlNative(handle, html, baseUrl)
  fun evaluateJavaScript(handle: Long, evalId: Long, script: String) = evaluateJavaScriptNative(handle, evalId, script)
  fun callDevToolsProtocolMethod(handle: Long, callId: Long, methodName: String, paramsJson: String) =
    callDevToolsProtocolMethodNative(handle, callId, methodName, paramsJson)

  fun transferToJs(handle: Long, rawJson: String) = transferToJsNative(handle, rawJson)


  private fun loadNativeLibrary() {
    val libraryPath = winWebView2BridgeLibrary.load()
    winWebView2BridgeLibrary.verifyAbi(libraryPath, EXPECTED_NATIVE_ABI_VERSION, ::abiVersionNative)
  }

  internal interface Callbacks {
    fun onCreated(handle: Long)
    fun onCreateFailed(message: String)
    fun onDestroyed(handle: Long)
    fun onMessage(raw: String)
    fun onEvaluationResult(evalId: Long, result: String?)
    fun onEvaluationError(evalId: Long, message: String)
    fun onDevToolsProtocolMethodResult(callId: Long, result: String?, error: String?)
    fun onAcceleratorKeyPressed(keyEventKind: Int, virtualKey: Int, modifiers: Int, keyEventLParam: Int): Int
    fun onFocusGained()
    fun onFocusLost()
    fun onMoveFocusRequested(reason: Int): Boolean
    fun onLog(level: Int, message: String)
    fun onNativeDiagnostic(level: Int, event: String, message: String, data: String)
    fun resolveAsset(url: String): AssetResponse?
  }

  @Suppress("unused")
  internal class AssetResponse(
    val statusCode: Int,
    val statusText: String,
    val headers: String,
    val bytes: ByteArray,
  ) {
    companion object {
      fun headers(contentType: String, headers: Map<String, String>): String {
        return buildString {
          appendHeader("Content-Type", contentType)
          for ((name, value) in headers) {
            if (name.equals("Content-Type", ignoreCase = true)) continue
            appendHeader(name, value)
          }
        }
      }

      private fun StringBuilder.appendHeader(name: String, value: String) {
        append(sanitizeHeaderPart(name))
        append(": ")
        append(sanitizeHeaderPart(value))
        append("\r\n")
      }

      private fun sanitizeHeaderPart(value: String): String {
        return value.replace('\r', ' ').replace('\n', ' ')
      }
    }
  }
}

@ApiStatus.Internal
internal data class WinWebView2Configuration(
  val allowHostInputProcessing: Boolean = true,
  val isScriptEnabled: Boolean = true,
  val isWebMessageEnabled: Boolean = true,
  val areDefaultScriptDialogsEnabled: Boolean = false,
  val isStatusBarEnabled: Boolean = false,
  val areDevToolsEnabled: Boolean = false,
  val areDefaultContextMenusEnabled: Boolean = false,
  val areHostObjectsAllowed: Boolean = false,
  val isZoomControlEnabled: Boolean = false,
  val isBuiltInErrorPageEnabled: Boolean = false,
  val areBrowserAcceleratorKeysEnabled: Boolean = false,
  val isGeneralAutofillEnabled: Boolean = false,
  val isPasswordAutosaveEnabled: Boolean = false,
  val isSwipeNavigationEnabled: Boolean = false,
) {
  internal fun toNativeFlags(): Long {
    var flags = 0L
    values.forEachIndexed { index, enabled ->
      if (enabled) flags = flags or (1L shl index)
    }
    return flags
  }

  private val values: List<Boolean>
    get() = listOf(
      allowHostInputProcessing,
      isScriptEnabled,
      isWebMessageEnabled,
      areDefaultScriptDialogsEnabled,
      isStatusBarEnabled,
      areDevToolsEnabled,
      areDefaultContextMenusEnabled,
      areHostObjectsAllowed,
      isZoomControlEnabled,
      isBuiltInErrorPageEnabled,
      areBrowserAcceleratorKeysEnabled,
      isGeneralAutofillEnabled,
      isPasswordAutosaveEnabled,
      isSwipeNavigationEnabled,
    )
}

@ApiStatus.Internal
internal interface WinWebView2BridgeApi {
  fun create(
    hostHwnd: Long,
    userDataDir: String,
    documentStartScript: String,
    configuration: WinWebView2Configuration,
    callbacks: WinWebView2Bridge.Callbacks,
  ): Long
  fun destroy(handle: Long)
  fun setBounds(handle: Long, x: Int, y: Int, width: Int, height: Int, scale: Double)
  fun setVisible(handle: Long, visible: Boolean)
  fun focus(handle: Long)
  fun loadUrl(handle: Long, url: String)
  fun setVirtualHostNameToFolderMapping(handle: Long, hostName: String, folderPath: String)
  fun loadHtml(handle: Long, html: String, baseUrl: String?)
  fun evaluateJavaScript(handle: Long, evalId: Long, script: String)
  fun callDevToolsProtocolMethod(handle: Long, callId: Long, methodName: String, paramsJson: String)
  fun transferToJs(handle: Long, rawJson: String)
}

@ApiStatus.Internal
internal object NativeWinWebView2BridgeApi : WinWebView2BridgeApi {
  override fun create(
    hostHwnd: Long,
    userDataDir: String,
    documentStartScript: String,
    configuration: WinWebView2Configuration,
    callbacks: WinWebView2Bridge.Callbacks,
  ): Long = WinWebView2Bridge.create(hostHwnd, userDataDir, documentStartScript, configuration, callbacks)

  override fun destroy(handle: Long) = WinWebView2Bridge.destroy(handle)
  override fun setBounds(handle: Long, x: Int, y: Int, width: Int, height: Int, scale: Double) =
    WinWebView2Bridge.setBounds(handle, x, y, width, height, scale)

  override fun setVisible(handle: Long, visible: Boolean) = WinWebView2Bridge.setVisible(handle, visible)
  override fun focus(handle: Long) = WinWebView2Bridge.focus(handle)
  override fun loadUrl(handle: Long, url: String) = WinWebView2Bridge.loadUrl(handle, url)
  override fun setVirtualHostNameToFolderMapping(handle: Long, hostName: String, folderPath: String) =
    WinWebView2Bridge.setVirtualHostNameToFolderMapping(handle, hostName, folderPath)

  override fun loadHtml(handle: Long, html: String, baseUrl: String?) = WinWebView2Bridge.loadHtml(handle, html, baseUrl)
  override fun evaluateJavaScript(handle: Long, evalId: Long, script: String) = WinWebView2Bridge.evaluateJavaScript(handle, evalId, script)
  override fun callDevToolsProtocolMethod(handle: Long, callId: Long, methodName: String, paramsJson: String) =
    WinWebView2Bridge.callDevToolsProtocolMethod(handle, callId, methodName, paramsJson)

  override fun transferToJs(handle: Long, rawJson: String) = WinWebView2Bridge.transferToJs(handle, rawJson)
}
