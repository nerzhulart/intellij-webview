// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.windows

import com.intellij.openapi.util.SystemInfo
import io.github.nerzhulart.webview.impl.NativeBridgeLibrary
import io.github.nerzhulart.webview.impl.webViewNativeArchDirectory
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

/**
 * The JNI surface of the WebView2 bridge DLL. What each command means is documented on
 * [WinWebView2BridgeApi] - the same contract, seen by the engine and by the fakes in tests.
 *
 * Nothing here runs WebView2 on the calling thread. Every function pushes a typed command into the
 * queue of the thread that owns the Canvas HWND - AWT-Windows - and wakes it with
 * `PostThreadMessageW`; a `WH_GETMESSAGE` hook drains that queue there, so commands execute in the
 * order they were posted and always on the thread that owns both the window and the controller.
 * A command that cannot even be queued throws `IllegalStateException`; a command that fails while
 * executing arrives as a native diagnostic instead, because by then the caller is long gone.
 *
 * `handle` is the identity of a view: an opaque number minted by [create], never a pointer. It keys
 * a route table shared by all threads (owning thread, holder window) and a per-thread map of live
 * views on AWT-Windows, so a command addressed to a view that is already destroyed resolves to a
 * diagnostic instead of touching freed memory - which is what an asynchronous transport needs.
 * Kotlin uses the same number as identity: a callback carrying an older handle belongs to a view
 * that crash recovery has already replaced. `0` means "no view".
 *
 * `generation` counts Swing-side attachments. The native side drops a placement or park command
 * whose generation is older than the one it has already applied, so a command queued for a dead
 * peer can never undo a newer attachment.
 *
 * The signatures below and their Rust counterparts change together, so a mismatched DLL has to fail
 * on load rather than at the first call - hence the ABI sentinel.
 */
@ApiStatus.Internal
internal object WinWebView2Bridge {
  private const val EXPECTED_NATIVE_ABI_VERSION = "wvi-awt-canvas-host-v16"

  init {
    if (SystemInfo.isWindows) {
      loadNativeLibrary()
    }
  }

  /**
   * The ABI sentinel baked into the loaded DLL, read once by [loadNativeLibrary]. The `Native`
   * suffix marks the JNI boundary throughout this object: every public function below is a one-line
   * wrapper over exactly one of these declarations, so changing a signature here is an ABI change
   * and has to be paired with a bump of [EXPECTED_NATIVE_ABI_VERSION] and of its Rust counterpart.
   */
  @JvmStatic
  private external fun abiVersionNative(): String

  @JvmStatic
  private external fun createNative(
    parentHwnd: Long,
    generation: Long,
    userDataDir: String,
    documentStartScript: String,
    backgroundColor: Int,
    callbacks: Callbacks,
  ): Long

  @JvmStatic
  private external fun destroyNative(handle: Long)

  @JvmStatic
  private external fun setHostStateNative(
    handle: Long,
    parentHwnd: Long,
    width: Int,
    height: Int,
    visible: Boolean,
    generation: Long,
  )

  @JvmStatic
  private external fun parkBeforePeerDisposeNative(
    handle: Long,
    hostHwnd: Long,
    generation: Long,
  ): Boolean

  @JvmStatic
  private external fun focusNative(handle: Long)

  @JvmStatic
  private external fun clearFocusNative(handle: Long)

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
  private external fun completeAssetRequestNative(handle: Long, requestId: Long, response: AssetResponse?)

  /**
   * Mints the handle, registers a route to the thread owning [parentHwnd] and queues the creation
   * there; the handle is usable for queueing from the moment it returns, long before the controller
   * exists. [callbacks] is held by the native side for the whole life of the view, so it keeps the
   * engine alive too. What the arguments mean: [WinWebView2BridgeApi.create].
   *
   * @param parentHwnd HWND of the AWT `Canvas`; its owning thread becomes the thread of this view.
   * @param generation attachment counter of that Canvas, used to discard commands of a dead peer.
   * @param userDataDir WebView2 user data directory; views sharing it share one browser process.
   * @param documentStartScript script injected into every document before page scripts.
   * @param backgroundColor default controller background as `0xAARRGGBB`.
   * @param callbacks native-to-Kotlin side, kept by the native view until it is destroyed.
   * @return the handle addressing this view in every other call here.
   */
  fun create(
    parentHwnd: Long,
    generation: Long,
    userDataDir: String,
    documentStartScript: String,
    backgroundColor: Int,
    callbacks: Callbacks,
  ): Long = createNative(parentHwnd, generation, userDataDir, documentStartScript, backgroundColor, callbacks)

  /**
   * Marks the route closing before returning, so nothing else can be queued for this handle, and
   * queues the teardown itself. Idempotent, and safe to call for a handle whose creation is still
   * in flight. See [WinWebView2BridgeApi.destroy].
   *
   * @param handle view to release; an unknown one is answered with a diagnostic, not an exception.
   */
  fun destroy(handle: Long) = destroyNative(handle)

  /**
   * Queues one placement snapshot; the native side reconciles parent, bounds and visibility from it
   * in a single pass, and drops the snapshot outright if [generation] is older than the one already
   * applied. See [WinWebView2BridgeApi.setHostState].
   *
   * @param handle view to place.
   * @param parentHwnd current Canvas HWND, `0` once the peer is gone.
   * @param width Swing-side width in device pixels, a fallback for the real client rect.
   * @param height Swing-side height in device pixels, a fallback for the real client rect.
   * @param visible `false` hides the controller through `put_IsVisible`, so the page stops
   *   rendering while nobody looks at it.
   * @param generation attachment counter this snapshot belongs to.
   */
  fun setHostState(handle: Long, parentHwnd: Long, width: Int, height: Int, visible: Boolean, generation: Long) =
    setHostStateNative(handle, parentHwnd, width, height, visible, generation)

  /**
   * The only call in this object that blocks: it queues the park and then drives the queue through
   * a bounded barrier sent to the bridge holder window, so the controller leaves the Canvas HWND
   * before that HWND dies. See [WinWebView2BridgeApi.parkBeforePeerDispose].
   *
   * @param handle view to park.
   * @param hostHwnd Canvas HWND about to be destroyed; the holder is derived from its top-level root.
   * @param generation attachment counter of that Canvas; an older one loses to a newer attachment.
   * @return `false` when the barrier timed out or the park was dropped as stale.
   */
  fun parkBeforePeerDispose(handle: Long, hostHwnd: Long, generation: Long): Boolean =
    parkBeforePeerDisposeNative(handle, hostHwnd, generation)

  /**
   * Queues native focus into the page. See [WinWebView2BridgeApi.focus].
   *
   * @param handle view to focus.
   */
  fun focus(handle: Long) = focusNative(handle)

  /**
   * Queues the release of native focus back to the root window. See [WinWebView2BridgeApi.clearFocus].
   *
   * @param handle view to take the focus away from.
   */
  fun clearFocus(handle: Long) = clearFocusNative(handle)

  /**
   * Queues a navigation; the outcome arrives only as a diagnostic. See [WinWebView2BridgeApi.loadUrl].
   *
   * @param handle view to navigate.
   * @param url absolute URL, including the virtual host origin of bundled assets.
   */
  fun loadUrl(handle: Long, url: String) = loadUrlNative(handle, url)

  /**
   * Queues the virtual host mapping. It has to be applied before the navigation that relies on it,
   * which the queue guarantees as long as both are posted in that order.
   * See [WinWebView2BridgeApi.setVirtualHostNameToFolderMapping].
   *
   * @param handle view the mapping is registered on.
   * @param hostName host name to serve the folder under, without a scheme.
   * @param folderPath absolute local folder backing that host name.
   */
  fun setVirtualHostNameToFolderMapping(handle: Long, hostName: String, folderPath: String) =
    setVirtualHostNameToFolderMappingNative(handle, hostName, folderPath)

  /**
   * Queues a navigation to an in-memory document. See [WinWebView2BridgeApi.loadHtml].
   *
   * @param handle view to navigate.
   * @param html the document itself.
   * @param baseUrl crosses JNI for parity with the other backends and is ignored.
   */
  fun loadHtml(handle: Long, html: String, baseUrl: String?) = loadHtmlNative(handle, html, baseUrl)

  /**
   * Queues a script. See [WinWebView2BridgeApi.evaluateJavaScript].
   *
   * @param handle view to evaluate in.
   * @param evalId caller-minted id the evaluation callback carries back.
   * @param script source evaluated in the top frame.
   */
  fun evaluateJavaScript(handle: Long, evalId: Long, script: String) = evaluateJavaScriptNative(handle, evalId, script)

  /**
   * Queues a CDP call. See [WinWebView2BridgeApi.callDevToolsProtocolMethod].
   *
   * @param handle view to call on.
   * @param callId caller-minted id [Callbacks.onDevToolsProtocolMethodResult] carries back.
   * @param methodName CDP method, for example `Page.enable`.
   * @param paramsJson method parameters as a JSON object, `{}` when there are none.
   */
  fun callDevToolsProtocolMethod(handle: Long, callId: Long, methodName: String, paramsJson: String) =
    callDevToolsProtocolMethodNative(handle, callId, methodName, paramsJson)

  /**
   * Queues a host-to-page message as raw JSON. See [WinWebView2BridgeApi.transferToJs].
   *
   * @param handle view to deliver into.
   * @param rawJson the envelope, passed to the page bridge as it is.
   */
  fun transferToJs(handle: Long, rawJson: String) = transferToJsNative(handle, rawJson)

  /**
   * Queues the answer to a deferred asset request. Callable from any thread, which is the point: the
   * asset is resolved off AWT-Windows while the native request waits. See
   * [WinWebView2BridgeApi.completeAssetRequest].
   *
   * @param handle view the request came from.
   * @param requestId id of the pending [Callbacks.onAssetRequested]; a stale one is a no-op.
   * @param response the asset, or `null` to release the request and let WebView2 resolve the URL.
   */
  fun completeAssetRequest(handle: Long, requestId: Long, response: AssetResponse?) =
    completeAssetRequestNative(handle, requestId, response)

  /**
   * Loads the DLL and refuses it unless its sentinel matches [EXPECTED_NATIVE_ABI_VERSION]. A DLL
   * left over from an older build would otherwise link and then read the wrong arguments off the
   * stack, so the mismatch has to fail here rather than at the first call.
   */
  private fun loadNativeLibrary() {
    val libraryPath = winWebView2BridgeLibrary.load()
    winWebView2BridgeLibrary.verifyAbi(libraryPath, EXPECTED_NATIVE_ABI_VERSION, ::abiVersionNative)
  }

  /**
   * The native-to-Kotlin direction. Every method is called on AWT-Windows, the thread that owns the
   * controller, with the JVM attached for the duration of the call - so an implementation must not
   * block: the same thread drives the Windows input queue of the whole IDE frame.
   */
  internal interface Callbacks {
    /**
     * The controller exists on the Canvas it was created for; commands for this handle are live from now on.
     *
     * @param handle the same value [WinWebView2BridgeApi.create] returned, or an older one when
     *   crash recovery has already replaced the view - which is how it is told apart.
     */
    fun onCreated(handle: Long)

    /**
     * Creation failed for good: no handle ever becomes usable and no [onDestroyed] follows.
     *
     * @param message native failure text, already carrying the `HRESULT` where there is one.
     */
    fun onCreateFailed(message: String)

    /**
     * The native view is gone and its handle stops resolving; handles are never reused.
     *
     * @param handle the view that was released.
     */
    fun onDestroyed(handle: Long)

    /**
     * A message posted by the page.
     *
     * @param raw the JSON envelope exactly as the page sent it, not yet parsed or validated.
     */
    fun onMessage(raw: String)

    /**
     * Result of [WinWebView2BridgeApi.evaluateJavaScript].
     *
     * @param evalId the id the evaluation was queued with.
     * @param result the value as JSON, `null` when the script produced nothing.
     */
    fun onEvaluationResult(evalId: Long, result: String?)

    /**
     * The evaluation never produced a value; the pending call has to be completed anyway.
     *
     * @param evalId the id the evaluation was queued with; no [onEvaluationResult] follows for it.
     * @param message native failure text.
     */
    fun onEvaluationError(evalId: Long, message: String)

    /**
     * Answer to [WinWebView2BridgeApi.callDevToolsProtocolMethod].
     *
     * @param callId the id the call was queued with.
     * @param result CDP result as JSON, `null` when the call failed.
     * @param error failure text, `null` when the call succeeded; exactly one of the two is set.
     */
    fun onDevToolsProtocolMethodResult(callId: Long, result: String?, error: String?)

    /**
     * A key the page is about to receive. Returning `true` means the IDE consumed it, so WebView2
     * must not hand it to the page - this is where IDE shortcuts win over browser ones.
     *
     * @param keyEventKind `COREWEBVIEW2_KEY_EVENT_KIND`: key down, key up, system key down, system
     *   key up - mirrored as the `KEY_EVENT_KIND_*` constants of `WinWebViewShortcutInterop`.
     * @param virtualKey Windows virtual key code.
     * @param modifiers bridge-owned bit mask (`MODIFIER_SHIFT`, `MODIFIER_CONTROL`, `MODIFIER_ALT`,
     *   `MODIFIER_META`) sampled natively from the keyboard state; WebView2 does not report one.
     * @param keyEventLParam the `lParam` of the original message, the source of the extended-key bit
     *   that separates the left and right modifier keys.
     * @return `true` to swallow the key, `false` to let the page have it.
     */
    fun onAcceleratorKeyPressed(keyEventKind: Int, virtualKey: Int, modifiers: Int, keyEventLParam: Int): Boolean

    /** The page took the focus by itself, typically by a click, so Swing has to give up its focus owner. */
    fun onFocusGained()

    /**
     * A plain native log line.
     *
     * @param level `0` trace, `1` debug, `2` info, `3` warn, `4` error - the same scale as
     *   [onNativeDiagnostic].
     * @param message the line itself, already formatted.
     */
    fun onLog(level: Int, message: String)

    /**
     * A structured native event.
     *
     * @param level severity on the same scale as [onLog].
     * @param event stable dotted id the engine matches on, such as a process failure or a
     *   navigation milestone.
     * @param message human-readable summary of that event.
     * @param data payload as newline-separated `key=value` lines, empty when there is none.
     */
    fun onNativeDiagnostic(level: Int, event: String, message: String, data: String)

    /**
     * The page asked for an asset and the native request is deferred until
     * [WinWebView2BridgeApi.completeAssetRequest] answers with the same request id. Resolving may
     * happen on any thread; until it does, that single request hangs, not the browser.
     *
     * @param handle view whose page issued the request.
     * @param requestId id to answer with; every request has to be answered, if only with `null`.
     * @param url the requested URL, under either the custom scheme or the virtual host origin.
     */
    fun onAssetRequested(handle: Long, requestId: Long, url: String)
  }

  /**
   * A resolved asset, read by the native side field by field.
   *
   * @property statusCode HTTP status handed to WebView2, for example `200` or `404`.
   * @property statusText reason phrase paired with [statusCode].
   * @property headers one CRLF-separated block, as built by the companion helper of the same name.
   * @property bytes the body; empty for a status that has none.
   */
  @Suppress("unused")
  internal class AssetResponse(
    val statusCode: Int,
    val statusText: String,
    val headers: String,
    val bytes: ByteArray,
  ) {
    companion object {
      /**
       * Builds the CRLF-separated block for the `headers` field of an [AssetResponse].
       *
       * @param contentType authoritative content type, written first.
       * @param headers the rest; an entry repeating [contentType] is dropped rather than duplicated.
       */
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

      /** A stray CR or LF would end the header block early, so it is neutralized rather than escaped. */
      private fun sanitizeHeaderPart(value: String): String {
        return value.replace('\r', ' ').replace('\n', ' ')
      }
    }
  }
}

/**
 * The command surface of the WebView2 bridge, as the engine and the fakes in tests see it. The
 * meaning of `handle`, `generation` and the queued nature of every call is described on
 * [WinWebView2Bridge]; only [parkBeforePeerDispose] is synchronous, and it says why.
 */
@ApiStatus.Internal
internal interface WinWebView2BridgeApi {
  /**
   * Registers a view on the thread owning [parentHwnd] - the AWT `Canvas` HWND - and queues the
   * creation of the environment and the controller there. The handle is returned immediately;
   * readiness arrives as [WinWebView2Bridge.Callbacks.onCreated] or
   * [WinWebView2Bridge.Callbacks.onCreateFailed].
   *
   * @param parentHwnd HWND of the Canvas the controller is created in; its owning thread becomes
   *   the thread of this view, and every later command for the handle runs there.
   * @param generation attachment counter of that Canvas, carried so that a command queued for an
   *   older attachment cannot undo a newer one.
   * @param userDataDir WebView2 user data directory; views sharing it share one environment and one
   *   browser process, which is what keeps a second view cheap.
   * @param documentStartScript script injected into every document before page scripts, so the page
   *   bridge exists before anything can use it.
   * @param backgroundColor default controller background as `0xAARRGGBB`, kept equal to the Canvas
   *   brush so that a controller with no frame yet is indistinguishable from the host behind it.
   * @param callbacks the native-to-Kotlin side; the native view holds it for its whole life.
   * @return the handle identifying this view, usable for queueing before the controller exists.
   */
  fun create(
    parentHwnd: Long,
    generation: Long,
    userDataDir: String,
    documentStartScript: String,
    backgroundColor: Int,
    callbacks: WinWebView2Bridge.Callbacks,
  ): Long

  /**
   * Queues teardown of the controller, its handlers and the native view. The handle stops resolving
   * at once, so anything still queued for it is dropped with a diagnostic;
   * [WinWebView2Bridge.Callbacks.onDestroyed] confirms the release.
   *
   * @param handle view to destroy; destroying it twice, or destroying one whose creation is still
   *   in flight, is harmless.
   */
  fun destroy(handle: Long)

  /**
   * The single placement command: the native side reconciles the whole state at once, so the caller
   * never sequences attach, bounds and visibility itself.
   *
   * @param handle view to place.
   * @param parentHwnd the current Canvas HWND, `0` once the peer is gone - then the reconcile does
   *   nothing rather than guessing a parent.
   * @param width Swing-side width in device pixels; only a fallback, because the reconcile prefers
   *   the real client rect of [parentHwnd].
   * @param height Swing-side height in device pixels, a fallback in the same sense as [width].
   * @param visible `false` is applied as `put_IsVisible(false)`: the widget goes hidden, Chromium
   *   stops rendering the page and its renderer becomes a candidate for freezing. The reveal is
   *   ordered after the geometry, so the page is never presented at a size the host no longer has.
   * @param generation the attachment this snapshot describes; an older one is dropped in favour of
   *   the applied one.
   */
  fun setHostState(handle: Long, parentHwnd: Long, width: Int, height: Int, visible: Boolean, generation: Long)

  /**
   * Moves the controller out of the Canvas HWND before JBR destroys the peer - a child window would
   * otherwise be destroyed together with its parent. Called from `Canvas.removeNotify`, and unlike
   * everything else here it blocks: the bridge sends a bounded barrier to its own holder window,
   * whose `wndproc` runs the queued park on AWT-Windows before the Canvas can get `WM_NCDESTROY`.
   *
   * The controller is hidden through `put_IsVisible` and lands in the holder - a zero-sized child
   * of the same top-level - so a host nobody can see costs nothing to keep alive.
   *
   * @param handle view to park.
   * @param hostHwnd the Canvas HWND about to die; its top-level root decides which holder is used.
   * @param generation the attachment being torn down; an older one loses to a newer attachment that
   *   is already queued.
   * @return `false` when the barrier timed out or the park was dropped as stale - the controller
   *   stays valid either way, and the next [setHostState] places it again.
   */
  fun parkBeforePeerDispose(handle: Long, hostHwnd: Long, generation: Long): Boolean

  /**
   * Focuses the Canvas HWND and moves WebView2 focus into the page.
   *
   * @param handle view whose page should own the focus.
   */
  fun focus(handle: Long)

  /**
   * Pushes the focus up to the root window, so Swing can take its focus owner back without the page
   * holding it.
   *
   * @param handle view to take the focus away from.
   */
  fun clearFocus(handle: Long)

  /**
   * Starts a navigation; how it ended arrives as a navigation diagnostic, not as a return value.
   *
   * @param handle view to navigate.
   * @param url absolute URL; bundled assets are addressed through the origin registered by
   *   [setVirtualHostNameToFolderMapping].
   */
  fun loadUrl(handle: Long, url: String)

  /**
   * Serves a local folder under a virtual host name, which is how bundled assets get a real https
   * origin instead of `file://`.
   *
   * @param handle view the mapping applies to; it is per view, not per environment.
   * @param hostName host name without a scheme, the authority of the resulting origin.
   * @param folderPath absolute local folder served under [hostName].
   */
  fun setVirtualHostNameToFolderMapping(handle: Long, hostName: String, folderPath: String)

  /**
   * Navigates to an in-memory document.
   *
   * @param handle view to navigate.
   * @param html the document source.
   * @param baseUrl kept for parity with the other backends and ignored: WebView2 has no equivalent,
   *   so relative URLs in [html] do not resolve against it.
   */
  fun loadHtml(handle: Long, html: String, baseUrl: String?)

  /**
   * Evaluates a script in the top frame.
   *
   * @param handle view to evaluate in.
   * @param evalId caller-minted id returned by [WinWebView2Bridge.Callbacks.onEvaluationResult] or
   *   [WinWebView2Bridge.Callbacks.onEvaluationError]; it has to be unique among pending calls.
   * @param script the source; its value comes back as JSON.
   */
  fun evaluateJavaScript(handle: Long, evalId: Long, script: String)

  /**
   * Calls a DevTools Protocol method.
   *
   * @param handle view to call on.
   * @param callId caller-minted id returned by
   *   [WinWebView2Bridge.Callbacks.onDevToolsProtocolMethodResult].
   * @param methodName CDP method, domain included, for example `Page.enable`.
   * @param paramsJson method parameters as a JSON object; `{}` when the method takes none.
   */
  fun callDevToolsProtocolMethod(handle: Long, callId: Long, methodName: String, paramsJson: String)

  /**
   * Delivers a host-to-page message into the page bridge.
   *
   * @param handle view to deliver into.
   * @param rawJson the envelope, handed to the page as it is; the bridge does not inspect it.
   */
  fun transferToJs(handle: Long, rawJson: String)

  /**
   * Answers a deferred [WinWebView2Bridge.Callbacks.onAssetRequested].
   *
   * @param handle view the request came from.
   * @param requestId the id that request carried; an unknown or already answered one is a no-op.
   * @param response the asset, or `null` to release the request without substituting anything,
   *   leaving WebView2 to resolve the URL on its own.
   */
  fun completeAssetRequest(handle: Long, requestId: Long, response: WinWebView2Bridge.AssetResponse?)
}

/** The production implementation: plain delegation to the JNI object, so tests can replace the whole surface. */
@ApiStatus.Internal
internal object NativeWinWebView2BridgeApi : WinWebView2BridgeApi {
  override fun create(
    parentHwnd: Long,
    generation: Long,
    userDataDir: String,
    documentStartScript: String,
    backgroundColor: Int,
    callbacks: WinWebView2Bridge.Callbacks,
  ): Long = WinWebView2Bridge.create(parentHwnd, generation, userDataDir, documentStartScript, backgroundColor, callbacks)

  override fun destroy(handle: Long) = WinWebView2Bridge.destroy(handle)
  override fun setHostState(
    handle: Long,
    parentHwnd: Long,
    width: Int,
    height: Int,
    visible: Boolean,
    generation: Long,
  ) = WinWebView2Bridge.setHostState(handle, parentHwnd, width, height, visible, generation)

  override fun parkBeforePeerDispose(handle: Long, hostHwnd: Long, generation: Long): Boolean =
    WinWebView2Bridge.parkBeforePeerDispose(handle, hostHwnd, generation)
  override fun focus(handle: Long) = WinWebView2Bridge.focus(handle)
  override fun clearFocus(handle: Long) = WinWebView2Bridge.clearFocus(handle)
  override fun loadUrl(handle: Long, url: String) = WinWebView2Bridge.loadUrl(handle, url)
  override fun setVirtualHostNameToFolderMapping(handle: Long, hostName: String, folderPath: String) =
    WinWebView2Bridge.setVirtualHostNameToFolderMapping(handle, hostName, folderPath)

  override fun loadHtml(handle: Long, html: String, baseUrl: String?) = WinWebView2Bridge.loadHtml(handle, html, baseUrl)
  override fun evaluateJavaScript(handle: Long, evalId: Long, script: String) = WinWebView2Bridge.evaluateJavaScript(handle, evalId, script)
  override fun callDevToolsProtocolMethod(handle: Long, callId: Long, methodName: String, paramsJson: String) =
    WinWebView2Bridge.callDevToolsProtocolMethod(handle, callId, methodName, paramsJson)

  override fun transferToJs(handle: Long, rawJson: String) = WinWebView2Bridge.transferToJs(handle, rawJson)
  override fun completeAssetRequest(handle: Long, requestId: Long, response: WinWebView2Bridge.AssetResponse?) =
    WinWebView2Bridge.completeAssetRequest(handle, requestId, response)
}
