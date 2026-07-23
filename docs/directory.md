# WebView Runtime Documentation

WebView Runtime is an independently installed plugin for IntelliJ-based IDEs. Use this page to choose the shortest document for the job at hand.

## Start Here

- [WebView UI Authoring Guide](guides/WebView-UI-Authoring-Guide.md) — create, host, preview, and test a WebView UI.
- [Standalone Development and Verification](guides/Standalone-Development-and-Verification.md) — build this repository, run the demo IDE, and verify changes.
- [Publish the WebView npm Packages](guides/Publish-NPM-Packages.md) — bootstrap npm publishing, enable OIDC, and release matching ZIPs and packages.
- [Runtime Architecture](architecture/WebView-Runtime-Architecture.md) — understand the public plugin boundary and internal engine selection.

## How-to Guides

- [WebView UI Authoring Guide](guides/WebView-UI-Authoring-Guide.md)
- [Standalone Development and Verification](guides/Standalone-Development-and-Verification.md)
- [Publish the WebView npm Packages](guides/Publish-NPM-Packages.md)
- [Coding Guides](guides/Coding-Guides.md)
- [Kotlin Reactive Stream Ownership](guides/kotlin-reactive-stream-ownership-guideline.md)

## Reference

### Runtime and protocols

- [Runtime Architecture](architecture/WebView-Runtime-Architecture.md)
- [JSON-RPC Runtime](architecture/WebView-JsonRpc-Design.md)
- [Typed Kotlin/TypeScript APIs](architecture/WebView-TS-RPC-API-Design.md)

### Frontend

- [Frontend Build](frontend/WebView-Frontend-Build-Strategy.md)
- [Dependency Resolution](frontend/WebView-Frontend-Dependency-Resolution.md)
- [Frontend Package Distribution](frontend/WebView-Frontend-Package-Distribution.md)
- [Browser Testkit](frontend/WebView-Frontend-Testability.md)
- [View Model Patterns](frontend/WebView-Frontend-View-Model-Patterns.md)
- [Frontend Framework Policy](frontend/WebView-Frontend-Framework-Policy.md)
- [WebView Controls](frontend/WebView-Controls.md)
- [IconSet Loading](frontend/WebView-IconSet-Loading.md)

### Browser backends and IDE interop

- [macOS WKWebView](backends/macos-wkwebview-runtime.md)
- [Windows WebView2](backends/windows-webview2-runtime.md)
- [Linux WebKitGTK](backends/linux-webkitgtk-runtime.md)
- [JCEF](backends/jcef-runtime.md)
- [Focus and Tab Interop](interop/WebView-Focus-Tab-Interop.md)
- [Heavyweight Overlay Interop](interop/WebView-Heavyweight-Overlay-Interop.md)

## Active Plans and Reviews

These documents describe unfinished work only:

- [Architecture Cleanup](architecture/WebView-Architecture-Cleanup-Plan.md)
- [Bridge Readiness](interop/WebView-Bridge-Ready-Panel-Plan.md)
- [Windows WebView2 Off-EDT Work](backends/windows-webview2-off-edt-plan.md)
- [Windows Native Bridge Review](backends/windows-webview2-rust-review.md)

The ACP chat demo keeps its remaining feature backlog next to the view at `demo/webview-src/views/acp-chat/PLAN.md`.

## Documentation Policy

- Describe the runtime as an external plugin, never as an IDE-provided component.
- Treat `com.intellij.platform.ui.webview` as a technical plugin ID only.
- Keep implementation history in Git, not in live documentation.
- Keep completed behavior in reference documents and unfinished work in plans.
- Use commands that work from this standalone repository.
