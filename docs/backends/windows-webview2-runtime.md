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

- `WebView2Dispatcher` owns one dedicated STA thread and a blocking Win32 message loop.
- Rust owns the shared WebView2 environment, controller, browser instance, child HWND, native handlers, and script callbacks.
- Kotlin owns lifecycle state, asset resolution, RPC integration, host geometry, and shortcut routing.
- Hot-path bounds, visibility, focus, attachment, and navigation updates are coalesced before dispatch.

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
