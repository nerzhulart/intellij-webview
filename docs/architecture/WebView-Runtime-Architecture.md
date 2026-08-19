# WebView Runtime Architecture

WebView Runtime is an external plugin that embeds browser-backed UI into Swing surfaces. Consumer plugins use a small public API; engine discovery, native integration, asset routing, and raw JSON-RPC traffic remain internal.

## Public Plugin Boundary

Consumer code uses:

- `createWebViewPanel(...)` to create a Swing-hosted page;
- `WebViewPanel` for the component, typed interop, and reload operation;
- `WebViewPanelOptions` to select an asset root, entry point, query, debug name, and console category;
- `WebViewAssetRoot.forView(viewId)` for the standard bundled layout;
- `WebViewApiId`, `WebViewImplementable`, `WebViewCallable`, and `WebViewInterop` for cross-boundary contracts;
- `WebViewIconSet` and scoped asset providers for optional resource extensions.

The public entry point is scope-owned:

```kotlin
val panel = createWebViewPanel(
  scope = featureScope,
  options = WebViewPanelOptions(
    assetRoot = WebViewAssetRoot.forView("my-view"),
    debugName = "My view",
  ),
)
```

Creation must run on EDT. Cancel or complete `featureScope` when the owning UI is disposed.

## Internal Runtime

`WebViewRuntime` performs four operations:

1. collect default and extension-provided `WebViewEngineProvider` implementations;
2. filter them by required capabilities and engine preference;
3. create the selected engine, message bus, console capture, theme bridge, and Swing host;
4. load the initial asset and keep the engine, message bus, and Swing host bound to an internal child of the owner scope.

The session has no parallel `close()` protocol. Cancelling the owner scope closes the Swing host, message bus, and engine in that order; creation failures cancel only the internal view scope.

`WebView`, `WebViewEngine`, provider contracts, and host peers are internal. Consumer plugins must not cast to an engine or select a backend with OS checks.

## Engine Selection

`createWebViewPanel(...)` requires asset serving and message passing. Swing hosting is part of the engine contract itself: every engine creates a non-null `SwingWebViewHostPanel`, whether its backend is a native heavyweight peer or an embedded Swing component. Available providers are ordered by preference and report explicit availability diagnostics.

| Provider | Use |
| --- | --- |
| `SYSTEM_MACOS` | WKWebView on macOS |
| `SYSTEM_WINDOWS` | WebView2 on Windows |
| `JCEF` | Cross-OS fallback; default asset-backed backend on Linux |
| `SYSTEM_LINUX` | Disabled WebKitGTK implementation scaffold; never selected |

The `io.github.nerzhulart.webview.engine` registry value selects the `SYSTEM` or `JCEF` preference. `JCEF` excludes system providers; `SYSTEM` keeps the platform provider first and JCEF as its configured fallback.

## Asset Loading

`WebViewAssetRoot.forView("my-view")` resolves classpath resources below:

```text
webview/views/my-view/
```

Backends expose those bytes through a virtual origin. Pages do not navigate to `file:` URLs and consumer plugins do not start HTTP servers.

`WebViewAssetResolver` applies the same path normalization, MIME lookup, scoped-provider routing, icon routing, and common runtime asset handling to every asset-capable backend.

Common assets under `/__webview/` are runtime-owned. The frontend Vite helper inserts them into generated HTML; feature bundles do not carry their own bridge implementation.

## Messaging

Each WebView owns one `WebViewMessageBusImpl` and one `WebViewInterop` facade. The bus handles JSON-RPC frames, queues, cancellation, and dispatch. The facade reflects typed Kotlin interfaces and exposes callable proxies.

Feature code uses only typed contracts:

```text
Kotlin WebViewImplementable <-> TypeScript WebViewCallable
Kotlin WebViewCallable    <-> TypeScript WebViewImplementable
```

See [JSON-RPC Runtime](WebView-JsonRpc-Design.md) and [Typed Kotlin/TypeScript APIs](WebView-TS-RPC-API-Design.md).

## Browser Console Capture

Every runtime-created page forwards supported `console.*` calls to IDE loggers while preserving the original browser console call. Asset-backed views append the sanitized view ID to the configured logger category.

Feature code uses ordinary browser logging and must not register the reserved `$/webview/console` notification.

## Backend Boundaries

- `impl/mac` — WKWebView and AppKit integration.
- `impl/windows` plus `native/WinWebView2Bridge` — WebView2 and HWND integration.
- `impl/linux` plus `native/LinuxWebKitGtkBridge` — experimental WebKitGTK integration.
- `jcef` — optional JCEF engine content module.
- `impl/host` — Swing/native host synchronization.
- `impl/rpc` — JSON-RPC parsing, dispatch, and typed binding.

Backend-specific behavior stays behind these internal boundaries.

## Verification

Use the checks in [Standalone Development and Verification](../guides/Standalone-Development-and-Verification.md). The standalone build packages all three plugins; browser tests cover frontend flows; backend smoke testing uses `runIde` on the matching OS.
