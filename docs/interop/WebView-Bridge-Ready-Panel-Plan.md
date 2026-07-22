# WebView Bridge Readiness

Status: active. `createWebViewPanel(...)` and `WebViewPanel.reload()` are suspend functions, but the common message bus does not yet wait for a newly loaded page to install its bridge. Markdown preview still has a feature-specific `pageReady` workaround.

## Required Contract

- The TypeScript runtime sends one internal `$/webview/bridgeReady` notification only after the bridge object is installed.
- `createWebViewPanel(...)` returns after the first page reaches that state.
- `reload()` resets readiness and waits for the next page.
- Host-to-page frames queued before readiness are delivered afterwards in order.
- Closing or cancelling the owner scope releases all readiness waiters.

`$/webview/runtimeInfoRequest` remains independent and must not be reused as the readiness signal.

## Remaining Implementation

1. Add and test the internal TypeScript notification after bridge installation.
2. Make `WebViewMessageBusImpl` own a readiness state per navigation and gate outgoing delivery.
3. Reset the state before every asset, file, or HTML load.
4. Add a `createWebViewPanel(..., configure)` overload so host APIs needed during page startup can be registered after bus creation but before the first load.
5. Move Markdown preview startup registration into that block and remove its transport-level `pageReady` workaround. Retain feature readiness only where it represents rendered/domain state.

Backend-specific buffering may remain as defense in depth, but it is not the shared readiness contract.

## Acceptance Criteria

- Startup page calls can reach host implementations registered by `configure`.
- Early host notifications are neither dropped nor delivered to the previous page.
- Reload requires a new ready signal.
- A page that never installs the runtime bridge remains cancellable and does not leak a waiter.
- Runtime-info, theme, typed call, notification, and pending-notification tests remain green.
- Frontend typechecks and plugin ZIP builds pass.
