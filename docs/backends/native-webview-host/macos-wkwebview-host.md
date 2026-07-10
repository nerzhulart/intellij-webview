# macOS WKWebView Host

Status: plan.

This document describes the target macOS WK backend. WKWebView should be embedded under a native `NSView` owned by the Swing host component, not overlaid under `NSWindow.contentView`. AppKit responder cleanup and native mouse activation stay private to the macOS backend host facet.

## macOS traversal note

Do not treat the Windows `MoveFocusRequested` path as a cross-platform focus-boundary API. On Windows, the target implementation should use WebView2's native `MoveFocusRequested` event for traversal out of the browser and should not depend on a page-side boundary `exit` call once that native path is wired.

WKWebView does not currently expose an equivalent controller-level callback that says "DOM focus traversal reached the browser boundary; move focus to the host application." AppKit's key-view loop works between `NSView`s, but the Swing components before and after the WebView are AWT/Swing focus owners, not AppKit key views that WKWebView can reliably choose through `nextKeyView`.

For macOS, the production path should therefore stay separate:

- keep Swing traversal policy in `SwingWebViewHostPanel`;
- enter WKWebView through private macOS host-facet focus (`makeFirstResponder(wkWebView)`);
- keep first-responder cleanup private to the macOS backend host facet;
- use a private runtime-owned boundary detector for WKWebView traversal exits until a native-only AppKit path is proven. This is a macOS traversal stopgap, not a Windows activation or shortcut model.

The WK boundary detector may reuse the existing focus interop implementation internally, but it must be treated as runtime infrastructure, not as a public product page API or a requirement for Windows. A future native-only macOS spike may try a WKWebView subclass with `insertTab:`, `insertBacktab:`, `keyDown:`, or an AppKit `nextKeyView` sentinel that reports back to Swing. That spike must prove that normal in-page Tab traversal, empty pages, text editors or `data-webview-focus-boundary="native"` regions, shadow DOM controls, and Swing components before/after the WebView all behave correctly before replacing the private boundary detector.

## macOS legacy overlay problem

The current macOS WK backend is not the macOS equivalent of the target Windows Canvas/HWND host. `MacNativeWebViewHostPeer` resolves the Java window, takes `NSWindow.contentView`, adds the `WKWebView` there, and mirrors the Swing host rectangle into an AppKit frame. The Swing host component is therefore a geometry and focus-policy anchor, not the native parent of the browser view.

This explains the mouse/focus asymmetry:

- clicking inside WKWebView is an AppKit event delivered to an overlaid `NSView`, not an AWT mouse event on the Swing host component;
- Swing focus can later move to another component while AppKit's first responder is still WKWebView or one of WebKit's private descendant views;
- a common `clearFocus()` or `releaseNativeFocusForSwing()` API would encode this overlay artifact into every backend, even though Windows should not need it after WebView2 is hosted on the Canvas HWND.

The target macOS implementation should delete this model, not wrap it in a second controller or keep it as a fallback. If a stable AWT-owned Cocoa host view cannot be obtained, the new macOS implementation is blocked and needs a JBR/JAWT solution; it should not silently fall back to `NSWindow.contentView` overlay hosting.

## macOS target hosting

The macOS implementation should make WKWebView a child of a native view owned by the Swing host component, not a rectangle overlaid under `NSWindow.contentView`. The target shape mirrors the Windows target at the concept level:

1. `MacWkWebViewBackend` exposes a heavyweight AWT host component, probably `Canvas` or an equivalent JBR-supported component, through its `WebViewHostController.component` facet.
2. On displayability, resolve the Cocoa `NSView` for that host component through a supported JAWT/JBR path.
3. Add WKWebView as a subview of that host `NSView`, with bounds relative to the host view and clipping/autoresizing controlled by the host view.
4. Treat the host `NSView` as the AppKit focus boundary for mouse activation and first-responder cleanup.
5. On AWT peer recreation, detach WKWebView from the old host view, attach it to the new host view, and replay current bounds/visibility/focus state deterministically.

This deletes the window-content overlay machinery: content-view attachment, anchor-relative frame calculation, frame rejection/retry logic, and first-responder cleanup to `NSWindow.contentView`. Layout becomes host-view-local, and mouse activation should mostly become natural AppKit behavior.

The .NET MAUI iOS backend is a useful reference for the desired shape, not a direct implementation recipe. `MauiWKWebView` subclasses `WKWebView`, and the iOS `WebViewHandler` creates that object as the platform view. The .NET `WKWebView` type is only a generated binding around the Objective-C class: on macOS the binding base type is `NSView`, on iOS/MacCatalyst it is `UIView`, and its constructor calls `initWithFrame:configuration:`. `MovedToWindow()` is the UIKit `didMoveToWindow` lifecycle hook inherited from `UIView`; the macOS equivalent is `NSView.viewDidMoveToWindow`. Mouse and first-responder hooks come from `NSResponder` selectors such as `mouseDown:`, `rightMouseDown:`, `otherMouseDown:`, `acceptsFirstResponder`, and `becomeFirstResponder`.

The transferable idea is therefore the ownership model: WKWebView should be the native view hosted by the UI framework, with lifecycle/responder hooks on that view. The non-transferable part is the platform plumbing: MAUI runs in UIKit/MacCatalyst, while the IDE needs an AppKit `NSView` owned by a Swing/AWT host component.

## macOS focus and input matrix

The macOS implementation should be driven by explicit focus/input invariants instead of one generic "clear focus" escape hatch.

Focus entry cases:

- Swing traversal or IDE command enters WebView: Swing first makes the host component its focus owner, then the macOS host calls `makeFirstResponder(wkWebView)`. This path may request native focus because Swing initiated the transition.
- Mouse click inside WKWebView: the WKWebView subclass reports native mouse activation before `super mouseDown:`/`rightMouseDown:`/`otherMouseDown:`. Swing mirrors focus state and closes IDE popups/menus, but should not issue a second `makeFirstResponder(wkWebView)` unless diagnostics prove AppKit did not focus WKWebView naturally.
- Window reactivation click inside WKWebView: treat it as browser-owned activation. Avoid forcing Swing focus in a way that can create WebKit `blur`/`focus` bounce after the page opens a click-triggered popup.
- Programmatic IDE focus request into WebView: use the same path as Swing traversal, but do not pretend it came from mouse activation.

Focus exit cases:

- Swing permanent focus owner moves to another Swing component in the same IDE window: if AppKit first responder is WKWebView or one of WebKit's private descendants, move first responder to the native host view. This is a macOS private cleanup path.
- Focus moves to another window or IDE popup window: do not blindly clear WKWebView first responder. Window activation/deactivation and popup ownership need separate handling so a click inside a popup does not blur the browser twice.
- Focus moves from one WebView host to another WebView host in the same Swing window: only the host that previously believed focus was inside should run cleanup. Sibling hosts observing the global permanent-focus-owner change must not clear the newly activated WebView.
- Detach/removeNotify: hide/detach native view first, then clear attachment-scoped callbacks. Do not leave AppKit event callbacks pointing at a detached Swing host.

Tab traversal cases:

- Tab or Shift-Tab entering WebView from Swing should focus the first or last tabbable page element. If the page has no tabbable element, traversal should immediately continue out in the same direction.
- Tab inside the page should stay in WebKit while DOM focus can move to the next/previous tabbable element.
- Tab at the last element and Shift-Tab at the first element should ask Swing to move focus out of the WebView host.
- Modified Tab (`Ctrl`, `Alt`, `Meta`) and Tab during IME composition must not be treated as focus traversal.
- Elements inside `data-webview-focus-boundary="native"` regions should keep Tab handling inside that native/page-owned boundary.
- Proper host-view embedding does not automatically solve DOM boundary detection. Replacing the runtime-owned boundary detector with native AppKit hooks requires proof that `insertTab:`, `insertBacktab:`, `keyDown:`, or key-view-loop sentinels only fire at the WebKit boundary and do not break ordinary DOM traversal, shadow DOM, text editing, or custom focus scopes.

Keyboard and shortcut cases:

- Normal text input, IME composition, selection, arrow navigation, and browser editing keys should stay browser-first while WKWebView is first responder.
- IDE hard-reserved shortcuts may be intercepted by Swing/IDE policy, but the allow list must stay small.
- WebView edit shortcuts that collide with IDE actions (`Cmd+C`, `Cmd+V`, `Cmd+X`, `Cmd+A`, undo/redo) should be routed through AppKit responder-chain edit actions only when AppKit first responder is WKWebView or a descendant.
- Bare modifier transitions can keep using the existing `flagsChanged:` observer pattern: observe and mirror what Swing needs, then always call `super` so WebKit keeps its modifier state.
- `performKeyEquivalent:`/Command-key handling is a separate axis from text `keyDown:`. Do not solve Tab traversal by broadly intercepting AppKit key equivalents.

Mouse cases:

- `mouseDown:`, `rightMouseDown:`, and `otherMouseDown:` should report activation and then call `super`; WebKit must still receive the real event.
- `acceptsFirstMouse:` may be relevant for inactive-window clicks. The spike should verify whether WKWebView already accepts the first click as intended before overriding it.
- `mouseDownCanMoveWindow` should not be changed for browser content unless a concrete titlebar/drag regression requires it.
- Pointer activation should close IDE popups and Swing menus once, before or during the browser click, without blocking AppKit for an unbounded time.
- Synthetic AWT mouse events are a compatibility tool for IDE popup/menu state, not a replacement for the native AppKit event delivered to WKWebView.

Lifecycle and geometry cases:

- Overlay backend: geometry is window-content-relative and must guard against invalid frames, zero-size frames, and anchor changes.
- Proper host-view backend: geometry should become host-view-local; clipping and z-order must come from the host `NSView`.
- `viewDidMoveToWindow` is useful for attachment-scoped lifecycle, diagnostics, and delayed work that requires a native window. It should not become a hidden layout/focus policy channel.
- Any callback stored in the shared Objective-C subclass must be keyed by WKWebView pointer and cleared on detach/release.

The implementation must prove these points before the legacy overlay code is removed:

- JBR exposes a stable Cocoa host view for the chosen heavyweight AWT component across add/remove, tab moves, tool-window moves, and peer recreation.
- WKWebView z-order, clipping, Retina scaling, and window fullscreen behavior follow the host component.
- Swing lightweight components, popups, glass pane overlays, and IDE menus do not render behind an unexpected native view.
- Clicking WKWebView updates IDE/Swing focus state without relying on public page messages.
- Moving focus from WKWebView to another Swing component clears AppKit first responder from WKWebView or its private descendants.
- Tab and Shift-Tab still enter and exit through Swing traversal policy. Proper native hosting alone does not replace the WK boundary detector unless the native-only AppKit traversal spike from the previous section also passes.
- Mouse activation, inactive-window click, right click, edit shortcuts, IME composition, bare modifier transitions, and page popup open/close behavior match or improve the current overlay backend.


## Mouse Activation

Do not rely on a page pointer message for normal WKWebView mouse activation. `MacWkWebViewBackend` should receive native AppKit callbacks from the WKWebView subclass (`mouseDown:`, `rightMouseDown:`, `otherMouseDown:`), send `WebViewHostEvent.Activated(NATIVE_MOUSE)` to `WebViewHostEventSink`, and then let WebKit handle the original event through `super`.
