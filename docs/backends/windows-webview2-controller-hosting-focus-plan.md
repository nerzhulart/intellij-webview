# Native WebView Host Controller Plan

Status: split.

This plan was split into smaller documents under [native-webview-host](native-webview-host/index.md).

Non-negotiable implementation rule: Windows and macOS system WebView support must be written as new per-OS `WebViewController` implementations from this plan. Before implementing `WinWebViewController` or `MacWkWebViewController`, delete the old per-OS production code selected for replacement. Do not rename, wrap, subclass, copy, extract from, or incrementally refactor the old implementation, and do not keep old/new production paths behind flags or fallbacks. Existing behavioral tests remain and are adapted to the new API. Windows retains only the native WebView2 library and its thin Kotlin JNI wrapper.

Start with [Native WebView Host](native-webview-host/index.md), then use the task-specific documents:

- [Interface architecture](native-webview-host/interface-architecture.md)
- [Architecture diagrams](native-webview-host/architecture-diagrams.md)
- [Swing host panel](native-webview-host/swing-host-panel.md)
- [Focus, activation, and shortcuts](native-webview-host/focus-activation-shortcuts.md)
- [Windows WebView2 controller hosting](native-webview-host/windows-webview2-controller.md)
- [macOS WKWebView controller](native-webview-host/macos-wkwebview-host.md)
- [JCEF controller](native-webview-host/jcef-host.md)
- [Migration plan](native-webview-host/migration-plan.md)
