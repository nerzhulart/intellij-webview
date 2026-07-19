# Native WebView Host

Status: plan.

This document set describes the target architecture for native WebView hosting in Swing. The external user API stays unchanged: callers receive a `WebViewPanel` with one Swing component to mount. Internally, the design removes peer-style mutable hosting and places all runtime, HWND/NSView/JCEF, focus, input, and lifecycle behavior behind one selected `WebViewController` (Kotlin).

## Implementation Invariant

Windows and macOS system WebView support must be implemented as new per-OS controllers from this document set. Before work starts on either controller, delete the old per-OS production implementation selected for replacement. Do not rename, subclass, wrap, extract from, copy, or incrementally refactor the old classes into the new design. Existing behavioral tests stay and may be adapted to the new API.

- Windows: write a new `WinWebViewController` (Kotlin). Retain only the native WebView2 library and its thin Kotlin JNI wrapper; delete all other old Windows-specific production code first.
- macOS: write a new `MacWkWebViewController` (Kotlin). Delete the old `NSWindow.contentView` overlay peer/controller and its AppKit hosting, focus, input, and layout glue first.
- JCEF: it is not an OS-specific system-WebView implementation and is not subject to the clean-room per-OS rewrite. Adapt it to the single `JcefWebViewController` (Kotlin) contract without introducing a second engine/host hierarchy.

No change is acceptable if old and new per-OS production paths coexist, even temporarily behind a flag or fallback.

Read these documents by task:

- [Interface architecture](interface-architecture.md): the single target `WebViewController` (Kotlin) contract and removed abstractions.
- [Architecture diagrams](architecture-diagrams.md): type-level diagrams with full APIs in nodes and call names on edges.
- [Swing host panel](swing-host-panel.md): common Swing adapter responsibilities, layout params, and focus-neutral host state.
- [Focus, activation, and shortcuts](focus-activation-shortcuts.md): cross-platform focus model, activation events, traversal, and shortcut ownership.
- [Windows WebView2 controller hosting](windows-webview2-controller.md): Canvas HWND hosting, native bridge API, WebView2 controller callbacks, geometry, visibility, and host input.
- [macOS WKWebView controller](macos-wkwebview-host.md): target host `NSView` embedding, AppKit responder behavior, and macOS-specific focus/input invariants.
- [JCEF controller](jcef-host.md): `JcefWebViewController` shape over the existing Swing component.
- [Migration plan](migration-plan.md): implementation order, tests, risks, and acceptance criteria.
