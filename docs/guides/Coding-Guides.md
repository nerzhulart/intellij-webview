# WebView Runtime Coding Guide

These conventions apply to the external WebView Runtime plugin and its demo/consumer plugins.

## Public API

- Keep consumer-facing APIs small, typed, and annotated `@ApiStatus.Experimental` while the contract is unstable.
- Prefer immutable serializable DTOs and explicit result/error types.
- Keep engine providers, native bridges, transport objects, and backend selection internal.
- New feature code starts with `createWebViewPanel(...)`, `WebViewAssetRoot.forView(...)`, and typed `WebViewInterop`.
- Do not expose raw message strings or direct `window.__WVI__` access as feature APIs.

## Ownership and Lifecycle

- The `CoroutineScope` passed to `createWebViewPanel(...)` owns panel work; cancel it to stop handlers and dispose the view.
- Use structured concurrency. Do not use `GlobalScope` or block coroutine threads.
- Dispose typed API registrations with the page/panel lifecycle.
- Follow [Kotlin Reactive Stream Ownership](kotlin-reactive-stream-ownership-guideline.md) for streams and long-lived producers.

## Threads

WebView code crosses distinct owners:

- EDT owns Swing components and `createWebViewPanel(...)` calls.
- macOS main owns WKWebView and AppKit operations.
- AWT-Windows owns every WebView2 controller operation; it already owns the `Canvas` HWND the
  controller lives in, and there is no separate WebView2 thread.
- JCEF follows JBCEF's threading contracts.

Never block EDT waiting for native completion. Native callbacks must dispatch before touching Swing. On macOS, AppKit main and EDT may be the same thread or different threads depending on JBR startup; code must work in both cases.

Document thread affinity on properties and methods whose type does not make it obvious.

## Native and Embedded Languages

- Keep JNI/JNA boundary types narrow and validate native ABI versions.
- Preserve cleanup on every partial-creation and cancellation path.
- Mark Kotlin, Java, and Rust string literals containing HTML or JavaScript with `@Language` or an IntelliLang marker where supported.
- Do not add native behavior that differs silently between architectures.

## Frontend

- Keep bridge DTOs independent of React, Lit, DOM nodes, and frontend stores.
- Use bundled assets and the Gradle frontend pipeline for production.
- Keep mocks and previews under `webview-src/test/<view-id>`.
- Reinstall local `file:` dependencies after changing a consumed package.

## Review and Tests

- Test behavior, cancellation, ordering, error propagation, reload, and teardown.
- Add Playwright coverage for meaningful browser flows.
- Verify native work on the affected OS and architecture.
- Keep commands in documentation runnable from this repository; do not reference deleted build tools or source layouts.
