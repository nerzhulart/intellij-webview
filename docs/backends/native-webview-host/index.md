# Native WebView Host

Status: plan.

This document set describes the target architecture for native WebView hosting in Swing. The external user API stays unchanged: callers receive a `WebViewPanel` with one Swing component to mount. Internally, the design removes peer-style mutable hosting and moves platform-specific HWND/NSView/JCEF behavior behind the selected backend's host facet.

Read these documents by task:

- [Interface architecture](interface-architecture.md): target Kotlin contracts, immutable backend wiring, and removed abstractions.
- [Architecture diagrams](architecture-diagrams.md): type-level diagrams with full APIs in nodes and call names on edges.
- [Swing host panel](swing-host-panel.md): common Swing adapter responsibilities, layout params, and focus-neutral host state.
- [Focus, activation, and shortcuts](focus-activation-shortcuts.md): cross-platform focus model, activation events, traversal, and shortcut ownership.
- [Windows WebView2 controller hosting](windows-webview2-controller.md): Canvas HWND hosting, native bridge API, WebView2 controller callbacks, geometry, visibility, and host input.
- [macOS WKWebView host](macos-wkwebview-host.md): target host `NSView` embedding, AppKit responder behavior, and macOS-specific focus/input invariants.
- [JCEF host](jcef-host.md): JCEF-specific host facet shape over the existing Swing component.
- [Migration plan](migration-plan.md): implementation order, tests, risks, and acceptance criteria.
