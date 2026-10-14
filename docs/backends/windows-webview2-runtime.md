# Windows WebView2 Runtime

On Windows, the system engine provider embeds Microsoft Edge WebView2 through the Rust/JNI bridge in `native/WinWebView2Bridge`.

## Capabilities

| Capability | Supported |
| --- | --- |
| Bundled asset serving | Yes |
| Typed message bridge | Yes |
| Swing embedding | Yes |
| Interactive input | Yes |

The provider is available when the plugin-local native DLL can be loaded and its ABI matches the Kotlin bridge.

## Runtime

- Everything WebView2 runs on the AWT-Windows thread, which already owns the `Canvas` HWND. A
  process-wide `WH_GETMESSAGE` hook plus `PostThreadMessageW` carry native commands there, and a
  `WH_CALLWNDPROC` hook observes the Canvas `WM_SIZE`; there is no separate WebView2 thread or
  second message loop.
- Rust owns the shared WebView2 environment, controller, browser instance, native handlers, script
  callbacks, one clip-host child HWND per view, and the zero-sized holders described below.
- Kotlin owns lifecycle state, asset resolution, RPC integration, host geometry, and shortcut routing.
- Placement is one immutable `HostState` snapshot (parent, size, visibility) sent through a single
  native command and applied by a single `reconcile`; focus and navigation updates are coalesced
  separately.
- The native hierarchy is `Swing -> AWT Canvas HWND -> IJWebView2ClipHost HWND -> WebView2`. The
  controller keeps the same clip-host parent for its whole lifetime. `reconcile` moves only that
  plain child HWND between a Canvas and its parking holder, then applies controller visibility and
  bounds.
- Canvas `WM_SIZE` synchronously resizes the clip host with `SetWindowPos` as well as calling
  `SetBounds`. On a shrink the HWND changes first, which reduces the stale-size interval. Runtime
  inspection showed that neither the parent HWND nor an explicit window region is a hard visual
  clip for WebView2's DirectComposition surface. A zero-sized Canvas makes the clip host `0x0`
  while preserving the controller's last positive bounds.
- Hiding is honest: a host Swing does not show reports `visibilityState=hidden` to the page, stops
  getting animation frames and lets Chromium freeze its renderer. This is why a tool window nobody
  looks at costs nothing.
- When a Canvas peer is about to be destroyed, the controller is hidden, the clip host is shrunk to
  `0x0`, and that clip host is re-parented into one `WS_VISIBLE`, zero-sized holder per top-level
  root. The holder only has to outlive the peer; its `wndproc` handles the park barrier and its own
  `WM_NCDESTROY`. See
  [Windows Reparent Flash Measurement](windows-webview2-reparent-flash.md) and
  [Raw Win32 Audit](windows-webview2-raw-winapi-audit.md).

The remaining focus/thread hardening work is tracked in [Windows WebView2 Threading Follow-up](windows-webview2-off-edt-plan.md).

## Composition Clipping Proof of Concept

The default remains the windowed controller described above. An x64-only experiment can instead
create an `ICoreWebView2CompositionController` and place its root visual below an
application-owned rectangular DirectComposition clip:

```powershell
$env:WEBVIEW_WIN_PRESENTATION = 'composition-poc'
$env:WEBVIEW_WIN_COMPOSITION_POC_CLIP = '200x400'
$env:WEBVIEW_WIN_PAINT_TRACE = '1'
./gradlew runIde
```

`WEBVIEW_WIN_COMPOSITION_POC_CLIP` forces the settled-frame mismatch test: the controller keeps its
normal larger bounds while the visual clip remains `200x400`. Remove that variable and set
`WEBVIEW_WIN_COMPOSITION_POC_STALE_BOUNDS=1` for live resize testing with controller bounds held at
the maximum observed size. Restart the process after changing any of these variables; presentation
mode is read once before the first controller is created.

This path is a clipping experiment, not an alternative production backend. It intentionally has no
mouse, pointer, drag-and-drop, accessibility, parking, or device-loss parity. Explicit
`composition-poc` failures fail view creation and never fall back to the windowed controller. Do
not describe the experiment as successful until recorded frames meet the acceptance gate in
[Windows WebView2 Composition Hosting Plan](windows-webview2-composition-controller-plan.md).

## Assets

Asset-backed pages use the `ij-webview-asset://assets/` custom scheme. A per-view `WebResourceRequested` handler delegates requests to Kotlin's `WebViewAssetResolver`. A registry-controlled HTTPS origin remains an internal rollback path.

Do not replace this with extracted files or a localhost server.

## Keyboard and Focus

WebView2 keeps native browser editing, IME, dead-key, and text-navigation behavior. The bridge forwards IDE accelerators and unhandled system keys to the appropriate AWT path. Bare Shift has dedicated native handling for IDE gestures.

Do not route all WebView keystrokes through Swing; doing so breaks browser editing behavior.

## Application Behavior

WebView2 is configured as an embedded application surface. Default context menus, page zoom, status UI, browser accelerator keys, autofill/password prompts, navigation gestures, and elastic overscroll are disabled where supported. JavaScript, web messages, and explicit host-controlled diagnostics remain enabled.

Component-local zoom is allowed; only browser page zoom is suppressed.

## Native Build

See [WinWebView2Bridge README](../../native/WinWebView2Bridge/README.md). From the repository root:

```powershell
pwsh -File native/WinWebView2Bridge/build.ps1 -All
```

The script updates `lib/webview-native/win/<arch>/win_webview2_bridge.dll`. Stop any IDE process that has loaded the DLL before replacing it.
