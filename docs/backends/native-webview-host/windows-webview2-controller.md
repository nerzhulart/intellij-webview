# Windows WebView2 Controller Hosting

Status: plan.

This document describes the Windows-specific backend target: WebView2 is created directly on the heavyweight Swing `Canvas` HWND, and the native bridge does not create a container window or reparent a live controller.

## Problem

The current Windows implementation grew around an extra native child `HWND` created by the Rust bridge. That container window owns a custom WndProc, is parented under a Swing host, and is used for focus, mouse, bounds, visibility, and keyboard fallback hooks.

This creates several problems:

- There are two hosting concepts at once: Swing heavyweight `Canvas` HWND and Rust-created child container HWND.
- Reparenting through Win32 `SetParent` can race with WebView2 controller state and fail with transient HRESULTs.
- Bounds and visibility are split between Win32 window APIs and WebView2 controller APIs.
- Focus ownership is unclear: native code tries to move focus to parent HWNDs, while Swing also runs its own focus state machine.
- Popup/menu closing depends on synthetic behavior around native mouse/focus entry instead of a clear WebView2-to-Swing event path.
- Shortcut handling depends partly on WebView2 `AcceleratorKeyPressed`, partly on Swing dispatch, and partly on a low-level native bare-Shift fallback.

The new model should have one Windows hosting surface: the Swing heavyweight `Canvas` HWND. WebView2 should be hosted directly on that HWND through `ICoreWebView2Controller`.

## Design Principles

### Use WebView2 APIs before Win32 hooks

The WebView2 SDK already exposes APIs for the behavior we need:

- host window: `CreateCoreWebView2ControllerWithOptions(hostHwnd, ...)`;
- geometry: `Bounds`, `SetBounds`, `SetBoundsAndZoomFactor`, `NotifyParentWindowPositionChanged`;
- visibility: `IsVisible`, `SetIsVisible`;
- focus: `MoveFocus`, `GotFocus`, `LostFocus`, `MoveFocusRequested`;
- host input processing: `ICoreWebView2ControllerOptions4.AllowHostInputProcessing`;
- web/native interop: `AddScriptToExecuteOnDocumentCreated`, `WebMessageReceived`, `PostWebMessageAsJson`;
- shortcut arbitration: `AcceleratorKeyPressed`, `ICoreWebView2AcceleratorKeyPressedEventArgs2.IsBrowserAcceleratorKeyEnabled`.

Using these APIs keeps WebView2 state inside WebView2 and avoids pretending that the IDE owns WebView2's child windows.

References:

- [WebView2 features and APIs](https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/overview-features-apis)
- [ICoreWebView2Controller](https://learn.microsoft.com/en-us/microsoft-edge/webview2/reference/win32/icorewebview2controller)
- [ICoreWebView2ControllerOptions4.AllowHostInputProcessing](https://learn.microsoft.com/en-us/microsoft-edge/webview2/reference/win32/icorewebview2controlleroptions4#put_allowhostinputprocessing)
- [ICoreWebView2AcceleratorKeyPressedEventArgs2](https://learn.microsoft.com/en-us/microsoft-edge/webview2/reference/win32/icorewebview2acceleratorkeypressedeventargs2)
- [ICoreWebView2Settings3.AreBrowserAcceleratorKeysEnabled](https://learn.microsoft.com/en-us/microsoft-edge/webview2/reference/win32/icorewebview2settings3)


## Target Architecture

### Kotlin Windows backend

`WinWebView2Backend` should track `currentHostHwnd`, not `currentParentHwnd`.

There is no happy-path `SetParent`, `SetParentWindow`, or `setHostWindow` step in the target design. Kotlin resolves the heavyweight Canvas HWND first and passes it to native creation:

```text
Canvas HWND -> bridge.create(hostHwnd, ...) -> CreateCoreWebView2ControllerWithOptions(hostHwnd, ...)
```

The stored `currentHostHwnd` is only for detecting AWT peer recreation. It is not an input for reparenting a live WebView2 controller.

The backend host lifecycle becomes:

1. The `WinWebView2Backend` host facet resolves the Canvas HWND.
2. The backend records the host HWND, bounds, visibility, and scale in backend-private state.
3. If no native handle exists, the backend creates WebView2 with this host HWND.
4. If a handle exists and the Canvas HWND changed, the backend destroys the old native handle and recreates WebView2 on the new host HWND.
5. Visibility and bounds are applied only through WebView2 controller APIs.

Why recreate on host HWND change:

- The normal path creates WebView2 after resolving the target Canvas HWND, so no parent-window update API is needed.
- Canvas peer recreation is a host-lifetime transition, not a WebView2 controller movement operation.
- Recreate keeps the bridge API smaller and avoids a second host-update path. The backend must replay deterministic runtime state such as last load, bounds, visibility, virtual-host mappings, asset resolver state, and any pre-existing configured document-start scripts. This replay list must not imply an activation script exists by default.

### Rust native bridge

The Rust bridge should own only WebView2 objects and callback tokens:

- environment;
- controller;
- webview;
- WebView2 event handlers;
- pending execute-script/devtools handlers;
- Java callback reference;
- current host HWND value for diagnostics and controller operations.

It must not own or create a native container HWND.

Remove:

- `create_container_hwnd`;
- container WndProc;
- `GWLP_USERDATA` callback storage;
- native `SetParent`/`DestroyWindow`/`ShowWindow`/`SetWindowPos`;
- native `attachToParentNative` and `detachFromParentNative`;

## API Changes

### Native bridge API

Rename semantics from parent to host.

Preferred Kotlin API shape:

```kotlin
interface WinWebView2BridgeApi {
  fun create(hostHwnd: Long, userDataDir: String, documentStartScript: String, callbacks: WinWebView2Bridge.Callbacks): Long
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

Keep existing callbacks where possible and add focused callbacks for controller-level events:

```kotlin
interface Callbacks {
  fun onCreated(handle: Long)
  fun onCreateFailed(message: String)
  fun onMessage(raw: String)
  fun onAcceleratorKeyPressed(keyEventKind: Int, virtualKey: Int, modifiers: Int, keyEventLParam: Int): Boolean
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

Why:

- `onBeforeMouseFocus` was a WndProc-specific event.
- The new model should not expose old native message timing as an API concept.
- Windows activation should be expressed through WebView2 controller focus events and host input processing first; WebMessage activation is private fallback infrastructure only after a regression proves that primary path is insufficient.

### Swing host controller API

Replace `NativeWebViewHostPeer` with `WebViewHostController`.

```kotlin
internal interface WebViewHostController {
  val component: Component
  val editShortcutPolicy: WebViewEditShortcutPolicy

  fun applyLayout(params: WebViewHostLayoutParams)
  fun swingFocusMovedOutside(event: WebViewSwingFocusExit)
  fun handleEditShortcut(event: KeyEvent, command: WebViewEditCommand): Boolean
}
```

Do not add a generic `clearFocus`, `releaseFocus`, `focusComponent`, `hostHwnd`, or `hostNSView` method:

- the backend component is the only mounted/focus component the Swing host needs;
- Windows WebView2 has no controller-level clear-focus API;
- Windows focus leave should happen through normal AWT focus transfer away from the Canvas HWND;
- macOS first-responder cleanup must be implemented as a private reaction to `swingFocusMovedOutside(...)`;
- native handles are resolved and stored by the selected backend, not passed through common Swing code.

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

Do not put default no-op implementations on this interface. Each backend host facet should implement every method explicitly. If a hook is intentionally a no-op for a backend, leave an empty implementation with a short comment explaining why that backend has no native work for that event.

## WebView2 Controller Creation

Use `ICoreWebView2Environment10` when available:

1. Cast environment to `ICoreWebView2Environment10`.
2. Call `CreateCoreWebView2ControllerOptions()`.
3. Cast options to `ICoreWebView2ControllerOptions4`.
4. Set `AllowHostInputProcessing(true)`.
5. Call `CreateCoreWebView2ControllerWithOptions(hostHwnd, options, handler)`.

If `ICoreWebView2Environment10` or `ICoreWebView2ControllerOptions4` is unavailable:

- fail creation with a clear diagnostic, or
- use a product-policy compatibility mode only if old WebView2 runtimes must remain supported.

Preferred default: fail clearly. This change depends on controller options for correct input arbitration.

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

Continue computing scaled pixel bounds in Kotlin/native and call:

- `SetBounds(RECT { left = 0, top = 0, right = scaledWidth, bottom = scaledHeight })`;
- `NotifyParentWindowPositionChanged()`.

This is closest to the current implementation and lowest-risk.

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

Engine visibility rule:

```text
nativeVisible = params.displayable && params.showing && state == Active && currentHostHwnd != 0 && visibleBoundsApplied
```

When the host is hidden or detached, call `SetIsVisible(false)` before clearing committed host state.

