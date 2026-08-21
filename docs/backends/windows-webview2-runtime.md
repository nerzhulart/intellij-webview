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
  process-wide `WH_GETMESSAGE` hook plus `PostThreadMessageW` carry native commands there; there is
  no separate WebView2 thread and no second message loop.
- Rust owns the shared WebView2 environment, controller, browser instance, native handlers, script
  callbacks, and exactly one window of its own: the zero-sized holder described below.
- Kotlin owns lifecycle state, asset resolution, RPC integration, host geometry, and shortcut routing.
- Placement is one immutable `HostState` snapshot (parent, size, visibility) sent through a single
  native command and applied by a single `reconcile`; focus and navigation updates are coalesced
  separately.
- `reconcile` is WebView2 API only: `put_ParentWindow` on a real parent change, `SetBounds` when the
  parent or the rectangle changed, `NotifyParentWindowPositionChanged` after a reparent. Hiding is
  not `put_IsVisible` - the controller keeps its size and is pushed below the client area, so the
  composition surface survives.
- The controller lives directly in the AWT `Canvas` HWND. When the peer is about to be destroyed it
  is re-parented into the holder window: one `WS_VISIBLE`, zero-sized child per top-level root,
  which keeps `IsWindowVisible` true so the page never goes hidden. Its `wndproc` handles just two
  messages - the park barrier and its own `WM_NCDESTROY`. See
  [Windows Reparent Flash Measurement](windows-webview2-reparent-flash.md) and
  [Raw Win32 Audit](windows-webview2-raw-winapi-audit.md).

The remaining focus/thread hardening work is tracked in [Windows WebView2 Threading Follow-up](windows-webview2-off-edt-plan.md).

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
