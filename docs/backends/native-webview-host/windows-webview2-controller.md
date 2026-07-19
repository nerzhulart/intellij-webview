# Windows WebView2 Controller Hosting

Status: plan.

This document describes `WinWebViewController`: WebView2 is created directly on the heavyweight Swing `Canvas` HWND, and the native bridge does not create a container window or reparent a live controller.

## Rewrite Boundary

Delete every existing Windows-specific production layer before writing the replacement, except for the native WebView2 library itself and its thin Kotlin JNI wrapper. In particular, do not retain or study the old Windows engine, peer, container HWND implementation, WndProc, event loop, focus hooks, input hooks, attach/detach flow, or their helper state as an implementation guide.

The replacement is written from this plan, with AWT as the only host event loop:

- the heavyweight `Canvas` is created, displayed, laid out, and focused by AWT/Swing on the EDT;
- the native library does not create or run a Windows message loop, dispatch loop, window procedure, or auxiliary host thread;
- native controller creation and every WebView2 hosting operation run on the AWT-owned native thread that owns the Canvas HWND;
- bounds, visibility, focus, traversal, host input, and controller lifetime use only documented WebView2 controller APIs; no Win32 hosting/input/focus operation is permitted;
- document navigation, scripts, messages, and settings use their documented `ICoreWebView2` COM APIs, because those operations are not controller methods. They remain part of the same WebView2 COM session, not a separate Windows-specific subsystem.

The native library is therefore a thin COM binding: it owns WebView2 COM references and forwards WebView2 callbacks to Kotlin. It does not own UI policy or an event pump.

### Owner-thread transport

`Canvas` HWND ownership and the Java EDT are different on Windows: AWT dispatches the HWND's Win32 messages on its
`AWT-Windows` thread. WebView2 requires every COM call and callback to remain on that owner STA thread. JNI calls from
the EDT therefore enqueue typed operations instead of calling COM directly.

The one permitted transport mechanism is a process-local, thread-specific `WH_GETMESSAGE` hook installed for the
owner thread obtained with `GetWindowThreadProcessId(hostHwnd)`. The bridge registers one private window message and
posts a task id to the Canvas HWND. When AWT removes that message from its existing queue, the hook replaces it with
`WM_NULL`, runs exactly that task, and immediately forwards every other message unchanged. If AWT has already destroyed
the old peer while a destroy task is pending, the bridge posts the same private message directly to that owner thread;
this still wakes only AWT's existing queue and the same hook. This is not a new pump: AWT remains the only code that
calls `GetMessage` and dispatches Windows messages.

The transport has these lifecycle rules:

- one hook and FIFO typed-task queue per owner thread; hooks and queues are removed after the final handle on that
  thread is destroyed;
- all COM references, controller callbacks, and the thread-local environment manager live solely on that owner thread;
- `create` returns an opaque handle after queueing initialization; bounds, visibility, loading, script execution, and
  destroy are serialized in the same queue;
- `destroy` acknowledges completion through `onDestroyed(handle)`. Kotlin creates a replacement Canvas-hosted
  controller only after that acknowledgement, then replays its saved state;
- hook installation, posting, and task failures report a creation/error diagnostic. A failed creation releases its
  handle and does not leave a live controller route.

This narrowly-scoped transport does **not** permit a custom WndProc, a `SetWindowSubclass` callback, a private
`GetMessage`/`DispatchMessage` loop, a dedicated WebView2 STA thread, a container HWND, or `SetParent`.

The same hook may observe, without consuming or modifying, `WM_KEYDOWN`/`WM_KEYUP` for bare `VK_SHIFT` addressed to a
registered Canvas HWND. WebView2 explicitly excludes bare Shift from `AcceleratorKeyPressed`, while AWT does not
publish these host-input messages as Java key events when WebView2 owns native focus. The hook forwards only this
modifier through the same non-blocking Kotlin `EventQueue.postEvent` path. It uses non-blocking `try_lock` lookups and
must skip the event rather than wait. No other keyboard message, key state machine, global hook, or input policy is
permitted in native code.

## Required API Reference

Before writing the replacement, read the decompiled WinForms WebView2 wrapper at `C:\Users\nerzh\AppData\Roaming\JetBrains\Rider2026.3\resharper-host\DecompilerCache\decompiler\8c08f3c4d2b44fba995c61b20206dce19868\1a\f9cdefa9\WebView2.cs`.

Use it to verify the intended Microsoft WebView2 lifecycle: controller creation, initialization ordering, controller event subscription, host focus/traversal callbacks, layout/visibility updates, and disposal. It is a reference for the system WebView2 API contract, not a base class or implementation to copy into the deleted Windows-specific layer.

## Problem

The current Windows implementation grew around an extra native child `HWND` created by the Rust bridge. That container window owns a custom WndProc, is parented under a Swing host, and is used for focus, mouse, bounds, visibility, and keyboard fallback hooks.

This creates several problems:

- There are two hosting concepts at once: Swing heavyweight `Canvas` HWND and Rust-created child container HWND.
- Reparenting through Win32 `SetParent` can race with WebView2 controller state and fail with transient HRESULTs.
- Bounds and visibility are split between Win32 window APIs and WebView2 controller APIs.
- Focus ownership is unclear: native code tries to move focus to parent HWNDs, while Swing also runs its own focus state machine.
- Popup/menu closing depends on synthetic behavior around native mouse/focus entry instead of a clear WebView2-to-Swing event path.
- Shortcut handling depends partly on WebView2 `AcceleratorKeyPressed`, partly on Swing dispatch, and partly on a low-level native bare-Shift fallback.

The new model has one Windows hosting surface: the Swing heavyweight `Canvas` HWND. WebView2 must be hosted directly on that HWND through `ICoreWebView2Controller`.

## Design Principles

### Use WebView2 controller APIs exclusively for hosting

The WebView2 SDK already exposes APIs for the behavior we need:

- host window: `CreateCoreWebView2ControllerWithOptions(hostHwnd, ...)`;
- geometry: `Bounds`, `SetBounds`, `SetBoundsAndZoomFactor`, `NotifyParentWindowPositionChanged`;
- visibility: `IsVisible`, `SetIsVisible`;
- focus: `MoveFocus`, `GotFocus`, `LostFocus`, `MoveFocusRequested`;
- host input processing: `ICoreWebView2ControllerOptions4.AllowHostInputProcessing`;
- web/native interop: `AddScriptToExecuteOnDocumentCreated`, `WebMessageReceived`, `PostWebMessageAsJson`;
- shortcut arbitration: `AcceleratorKeyPressed`, `ICoreWebView2AcceleratorKeyPressedEventArgs2.IsBrowserAcceleratorKeyEnabled`.

Using these APIs keeps host state inside WebView2 and avoids pretending that the IDE owns WebView2's child windows. There is no permitted fallback to Win32 for hosting, geometry, visibility, focus, mouse activation, keyboard dispatch, or lifecycle management.

References:

- [WebView2 features and APIs](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/overview-features-apis)
- [ICoreWebView2Controller](https://learn.microsoft.com/en-us/microsoft-edge/webview2/reference/win32/icorewebview2controller)
- [ICoreWebView2ControllerOptions4.AllowHostInputProcessing](https://learn.microsoft.com/en-us/microsoft-edge/webview2/reference/win32/icorewebview2controlleroptions4#put_allowhostinputprocessing)
- [ICoreWebView2AcceleratorKeyPressedEventArgs2](https://learn.microsoft.com/en-us/microsoft-edge/webview2/reference/win32/icorewebview2acceleratorkeypressedeventargs2)
- [ICoreWebView2Settings3.AreBrowserAcceleratorKeysEnabled](https://learn.microsoft.com/en-us/microsoft-edge/webview2/reference/win32/icorewebview2settings3)


## Target Architecture

### `WinWebViewController`

The new `WinWebViewController` stores `currentHostHwnd`. Do not rename or carry over `currentParentHwnd` from the deleted implementation.

There is no happy-path `SetParent`, `SetParentWindow`, or `setHostWindow` step in the target design. Kotlin resolves the heavyweight Canvas HWND first and passes it to native creation:

```text
Canvas HWND -> bridge.create(hostHwnd, ...) -> CreateCoreWebView2ControllerWithOptions(hostHwnd, ...)
```

The stored `currentHostHwnd` is only for detecting AWT peer recreation. It is not an input for reparenting a live WebView2 controller.

The controller lifecycle becomes:

1. `WinWebViewController` resolves the Canvas HWND.
2. The controller records the host HWND, bounds, visibility, and scale in controller-private state.
3. If no native handle exists, the controller creates WebView2 with this host HWND.
4. If a handle exists and the Canvas HWND changed, the controller destroys the old native handle and recreates WebView2 on the new host HWND.
5. Visibility and bounds are applied only through WebView2 controller APIs.

Why recreate on host HWND change:

- The normal path creates WebView2 after resolving the target Canvas HWND, so no parent-window update API is needed.
- Canvas peer recreation is a host-lifetime transition, not a WebView2 controller movement operation.
- Recreate keeps the bridge API smaller and avoids a second host-update path. The controller must replay deterministic runtime state such as last load, bounds, visibility, virtual-host mappings, asset resolver state, and product-required document-start scripts. Windows activation/focus/key fallback scripts do not exist and are never part of replay state.

### Native WebView2 library and Kotlin wrapper

The retained native WebView2 library and its thin Kotlin wrapper should expose only the COM operations required by `WinWebViewController`. The native library owns only WebView2 objects and callback tokens:

- environment;
- controller;
- webview;
- WebView2 event handlers;
- pending execute-script/devtools handlers;
- Java callback reference;
- current host HWND value for diagnostics and controller operations.

It must not own or create a native container HWND, native event/message loop, auxiliary UI thread, custom window procedure, or UI policy. Its calls are made by the AWT-owned thread and invoke the documented WebView2 COM interfaces directly.

Remove:

- `create_container_hwnd`;
- container WndProc;
- `GWLP_USERDATA` callback storage;
- native `SetParent`/`DestroyWindow`/`ShowWindow`/`SetWindowPos`;
- native `attachToParentNative` and `detachFromParentNative`;
- any native event loop, `GetMessage`/`DispatchMessage` loop, or thread used to pump WebView2 independently of AWT;
- every old Windows-specific Kotlin/Rust production helper other than the retained native library and its thin Kotlin wrapper.

## New API

### Native bridge API

Define the wrapper API from scratch in host terms. Do not rename the deleted parent/attach API in place and do not preserve its implementation behind new names.

Preferred Kotlin API shape:

```kotlin
// Kotlin type.
interface WinWebView2BridgeApi {
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
```

Remove from Windows bridge API:

- `attachToParent(handle, parentHwnd)`;
- `detachFromParent(handle)`;
- `clearFocus(handle)`;
- `setHostWindow(handle, hostHwnd)`.

`create(hostHwnd = 0)` should fail fast with a clear error. A zero host HWND is not a fallback mode anymore.

### Native callbacks

Define the callback surface required by the new controller. A callback is included because the new design needs it, not because the old implementation exposed it:

```kotlin
// Kotlin type.
interface Callbacks {
  fun onCreated(handle: Long)
  fun onCreateFailed(message: String)
  fun onMessage(raw: String)
  fun onAcceleratorKeyPressed(keyEventKind: Int, virtualKey: Int, modifiers: Int, keyEventLParam: Int): Int
  fun onFocusGained()
  fun onFocusLost()
  fun onMoveFocusRequested(reason: Int): Boolean
  fun onLog(level: Int, message: String)
  fun onNativeDiagnostic(level: Int, event: String, message: String, data: String)
  fun resolveAsset(url: String): AssetResponse?
}
```

Remove:

- `onBeforeMouseFocus`.

`WinWebView2Configuration` is an immutable Kotlin value passed at creation. It owns every
`ICoreWebView2ControllerOptions` and `ICoreWebView2Settings*` value applied by the bridge, including
`AllowHostInputProcessing`, script/web-message support, dialogs, status bar, DevTools, context menus, host objects,
zoom controls, built-in error pages, browser accelerator keys, autofill/password saving, and swipe navigation.
Rust must not select product defaults for these settings.

Accelerator arbitration returns two independent bits: whether the event is handled by the host and whether
`ICoreWebView2AcceleratorKeyPressedEventArgs2.IsBrowserAcceleratorKeyEnabled` remains enabled for that event.
The native bridge applies both values synchronously before returning from `AcceleratorKeyPressed`.

`AllowHostInputProcessing` does not guarantee that AWT publishes a Java key event for an accelerator received by
WebView2. The Kotlin policy is therefore an immutable exact-match allowlist: text editing, navigation, selection,
traversal, and activation keys remain in WebView2; every other accelerator is posted once to a controller-owned AWT
event queue and marked handled in WebView2. `EventQueue.postEvent` is the only permitted delivery operation here.
The synchronous `AWT-Windows` callback must not wait for the EDT, call `invokeAndWait`, query `Toolkit`, access the
action system, acquire application locks, or keep mutable routing state.

Why:

- `onBeforeMouseFocus` was a WndProc-specific event.
- The new model should not expose old native message timing as an API concept.
- Windows activation is expressed only through WebView2 controller focus events and `AllowHostInputProcessing`/AWT host input. Do not add WebMessage/page-script activation fallback; failure of this path is a blocker for the Windows implementation.

### `WebViewController` API

Replace `NativeWebViewHostPeer` and the separate runtime engine contract with one `WebViewController`.

```kotlin
// Kotlin type.
internal interface WebViewController {
  val component: Component
  val editShortcutPolicy: WebViewEditShortcutPolicy

  suspend fun loadFile(file: Path)
  suspend fun loadAsset(root: WebViewAssetRoot, entry: WebViewAssetPath, query: String?)
  suspend fun loadHtml(html: String, baseFile: Path?)
  suspend fun evaluateJavaScript(script: String): String?
  suspend fun close()
  suspend fun transferToJs(rawJson: String)
  fun connectMessageBus(receiver: WebViewJsMessageReceiver)

  fun applyLayout(params: WebViewHostLayoutParams)
  fun swingFocusMovedOutside(event: WebViewSwingFocusExit)
  fun handleEditShortcut(event: KeyEvent, command: WebViewEditCommand): Boolean
}
```

Do not add a generic `clearFocus`, `releaseFocus`, `focusComponent`, `hostHwnd`, or `hostNSView` method:

- the controller component is the only mounted/focus component the Swing host needs;
- Windows WebView2 has no controller-level clear-focus API;
- Windows focus leave should happen through normal AWT focus transfer away from the Canvas HWND;
- macOS first-responder cleanup must be implemented as a private reaction to `swingFocusMovedOutside(...)`;
- native handles are resolved and stored by the selected controller, not passed through common Swing code.

Common code should expose facts and intentions, not native operations:

```kotlin
internal data class WebViewSwingFocusExit(
  val newOwner: Component?,
  val sameWindow: Boolean,
)

internal sealed interface WebViewHostEvent {
  data object NativeFocusGained : WebViewHostEvent
  data object NativeFocusLost : WebViewHostEvent
  data class Activated(val source: WebViewHostActivationSource) : WebViewHostEvent
  data class MoveFocusRequested(val direction: WebViewFocusDirection) : WebViewHostEvent
}
```

The event list should grow only when a new platform-neutral fact is needed. Do not add a method or event that names a platform primitive, such as `setParentHwnd`, `moveFirstResponderToContentView`, `clearNativeFocus`, or `installMacTabHook`.

Do not put default no-op implementations on this interface. Each platform controller implements every method explicitly. If a hook is intentionally a no-op, leave an empty implementation with a short comment explaining why that platform has no native work for that event.

## WebView2 Controller Creation

Require `ICoreWebView2Environment10`:

1. Cast environment to `ICoreWebView2Environment10`.
2. Call `CreateCoreWebView2ControllerOptions()`.
3. Cast options to `ICoreWebView2ControllerOptions4`.
4. Set `AllowHostInputProcessing(true)`.
5. Call `CreateCoreWebView2ControllerWithOptions(hostHwnd, options, handler)`.

If `ICoreWebView2Environment10` or `ICoreWebView2ControllerOptions4` is unavailable, fail creation with a clear diagnostic. Do not add a compatibility hosting path; this design depends on controller options for correct input arbitration.

Why enable `AllowHostInputProcessing`:

- Host/AWT receives keyboard, mouse, touch, and pen input before WebView2.
- If host does not handle the event, WebView2 receives it.
- This allows IDE-global shortcuts and double-Shift gestures to work without a low-level native keyboard hook.

Why this does not mean IDE owns all shortcuts:

- The Swing key dispatcher must decline WebView-owned keys.
- WebView2 still receives unhandled input.


## Geometry And DPI

Only WebView2 controller APIs should apply geometry.

The implementation should choose one scaling model and document it in code/tests:

### Option A: pixel bounds

Compute scaled pixel bounds in the new controller and call:

- `SetBounds(RECT { left = 0, top = 0, right = scaledWidth, bottom = scaledHeight })`;
- `NotifyParentWindowPositionChanged()`.

This is the recommended initial coordinate model because it requires only controller bounds and explicit scale conversion.

### Option B: WebView2 rasterization scale

Use `ICoreWebView2Controller3`:

- `SetBoundsMode(...)`;
- `SetRasterizationScale(scale)`;
- `SetBounds(...)` in the matching coordinate mode.

This may reduce custom DPI math, but it requires careful testing on mixed-DPI monitors.

Recommended first step: keep Option A unless current flicker/0,0 behavior is proven to come from scale math. Do not combine a hosting rewrite with a DPI-model rewrite unless necessary.

Why:

- The reported flicker is more likely caused by stale parent/container geometry and visibility ordering.
- Removing container `SetWindowPos` already eliminates one source of wrong 0,0/fullscreen flashes.

## Visibility

Only call:

- `controller.SetIsVisible(visible)`.

Do not call:

- `ShowWindow(hostHwnd)`;
- `ShowWindow(containerHwnd)`.

Why:

- The host Canvas is a Swing/AWT component. Swing owns its visibility.
- WebView2 controller visibility is the supported way to show/hide WebView2 content.
- Hiding the host HWND from native code can desynchronize AWT and native state.

Controller visibility rule:

```text
nativeVisible = params.displayable && params.showing && state == Active && currentHostHwnd != 0 && visibleBoundsApplied
```

When the host is hidden or detached, call `SetIsVisible(false)` before clearing committed host state.

