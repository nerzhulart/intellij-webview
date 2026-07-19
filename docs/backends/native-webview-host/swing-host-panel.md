# Swing Host Panel

Status: plan.

This document describes the platform-neutral Swing adapter. It should know about Swing state, guarded focus policy, and immutable layout params, but not about HWND, NSView, WebView2, WKWebView, or JCEF internals.


## Layout state

`applyLayout` receives the whole desired Swing layout state every time a relevant Swing event occurs. If only visibility, bounds, scale, or displayability changed, the panel still recomputes a full immutable value and passes it to the controller:

```kotlin
// Kotlin type.
internal data class WebViewHostLayoutParams(
  val displayable: Boolean,
  val showing: Boolean,
  val boundsInWindow: Rectangle,
  val clippedBoundsInWindow: Rectangle,
  val scale: Double,
)
```

`applyLayout` may be called from `addNotify`, `removeNotify`, resize/move/showing changes, ancestor movement, and DPI-related state changes. The method must be idempotent. Platform controllers own their own coalescing and diffing: for example, Windows may wait until the Canvas HWND exists, create or recreate the native WebView2 handle for that HWND, and then apply bounds and visibility; macOS resolves and tracks the host component's Cocoa `NSView`; JCEF may treat most layout updates as no-ops.

## Swing host panel

`SwingWebViewHostPanel` should be an OS-agnostic Swing adapter:

- mount `controller.component`;
- read Swing state into `WebViewHostLayoutParams` (Kotlin);
- call `controller.applyLayout(params)` on relevant Swing state changes;
- request Swing focus on `controller.component` when Swing/IDE initiated focus entry into WebView;
- call `controller.swingFocusMovedOutside(event)` when Swing permanent focus moves out of the host;
- call `controller.handleEditShortcut(event, command)` according to `controller.editShortcutPolicy`;
- own the guarded focus state machine, popup/menu closing policy, host activation callbacks, and page enter/leave notifications;
- never choose platform-specific HWND/NSView focus fallback behavior.

`WebViewFocusDirection` (Kotlin/TS RPC) can remain as the current page/Swing protocol value for Tab and Shift-Tab traversal. Do not add direction-specific platform controller behavior for now; Swing focus policy may use the value to choose `focusNextComponent` or `focusPreviousComponent`.

## `displayable` vs `showing`

- `displayable` means AWT has created a native peer for the host component. On Windows this is the point where a heavyweight `Canvas` can have an HWND; on macOS it is the point where the controller may try to resolve the host `NSView`.
- `showing` means the component is displayable, visible, and all of its ancestors are visible enough for Swing to consider it on screen.
- The controller should normally show native browser content only when both are true and a non-empty clipped bounds value has been applied.
- Losing `showing` should hide the native browser content. Losing `displayable` is stronger: native host handles may be invalid and the controller may need to detach or destroy/recreate platform state.

## Target Architecture

### Swing side

`SwingWebViewHostPanel` mounts the `WebViewController` created by the selected provider.

For Windows, the controller-owned Canvas is:

- the Swing child embedded into `SwingWebViewHostPanel`;
- the Swing focus component for traversal;
- the only host HWND passed to WebView2;
- the component used for natural AWT host-input handling when WebView activation should let IDE popups or Swing menus react as they do for ordinary Swing components.

`SwingWebViewHostPanel` remains OS-agnostic:

- it mounts `controller.component`;
- it sends full immutable `WebViewHostLayoutParams` values to `controller.applyLayout(params)`;
- it runs the guarded focus state machine;
- it handles host activation, native focus events, Swing focus gained/lost, and page enter/leave;
- it requests Swing focus on `controller.component` for Swing-initiated focus entry;
- it reports Swing focus exit through `controller.swingFocusMovedOutside(event)`;
- it receives host activation callbacks from controller code and handles them in guarded Swing state;
- it does not branch on Windows/macOS/Linux.
