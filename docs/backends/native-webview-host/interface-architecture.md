# Interface And Type Architecture

Status: plan.

The external user API stays unchanged: callers receive a `WebViewPanel` with one Swing `JComponent` to mount. Internally, host creation should return immutable wiring: one backend, runtime and host facets, and message-bus registrations are constructed once and stored as `val`s. `WebViewHostController` is the only common-facing host-control contract for hosting, focus, activation, and shortcuts. It is a facet contract, not a requirement to create concrete per-platform `*HostController` classes. Platform implementations may have private collaborators and constructor callbacks into `SwingWebViewHostPanel`, but common code must not split those responsibilities into additional common controller contracts. Avoid late callback setters, nullable peer slots, and mutable attach/detach objects where construction order can express the dependency directly.

## Core host controller

Replace the generic peer-style API with a host controller contract that every backend can implement meaningfully. The common Swing layer sends platform-neutral facts and intentions; the selected backend's host facet decides the native consequences:

```kotlin
// Kotlin type.
internal interface WebViewHostController {
  val component: Component
  val editShortcutPolicy: WebViewEditShortcutPolicy

  fun applyLayout(params: WebViewHostLayoutParams)
  fun swingFocusMovedOutside(event: WebViewSwingFocusExit)
  fun handleEditShortcut(event: KeyEvent, command: WebViewEditCommand): Boolean
}

// Kotlin type.
internal fun interface WebViewHostEventSink {
  // Returns true only when the backend event needs an explicit handled/accepted result.
  fun handle(event: WebViewHostEvent): Boolean
}

// Kotlin type.
internal data class WebViewSwingFocusExit(
  val newOwner: Component?,
  val sameWindow: Boolean,
)

// Kotlin type.
internal sealed interface WebViewHostEvent {
  data object NativeFocusGained : WebViewHostEvent
  data object NativeFocusLost : WebViewHostEvent
  data class Activated(val source: WebViewHostActivationSource) : WebViewHostEvent
  data class MoveFocusRequested(val direction: WebViewFocusDirection) : WebViewHostEvent // Kotlin/TS RPC type.
}

// Kotlin type.
internal enum class WebViewHostActivationSource {
  HOST_INPUT,
  PAGE_POINTER_FALLBACK,
  NATIVE_MOUSE,
  NATIVE_FOCUS,
  WINDOW_REACTIVATION,
}
```

There is deliberately no `requestFocus()`, `focusWebView()`, `pageActivated()`, `releaseNativeFocusForSwing()`, `clearFocus()`, or `moveFirstResponderToParentView()` in this interface:

- Windows WebView2 loses focus when normal AWT focus transfer moves focus away from the heavyweight `Canvas` HWND that hosts the controller.
- JCEF should use its own component/AWT focus behavior.
- Focus entry starts with normal Swing focus on `hostController.component`; the backend host facet observes focus on its own component/native view and performs the native consequence internally.
- Page or native mouse activation is a callback into `SwingWebViewHostPanel` guarded host state, not a common command sent to the backend.
- `WebViewHostEventSink` is a callback port owned by `SwingWebViewHostPanel`, not another controller. It contains no focus policy; it only reports native facts back into guarded Swing state.
- macOS WKWebView first-responder cleanup is a private AppKit host-facet reaction to `swingFocusMovedOutside(...)`, not a common clear-focus command.
- Common code must not branch on `isWindows`, `isMac`, `isJcef`, heavyweight state, HWND availability, or AppKit first responder state. If behavior differs, express it through `WebViewHostController` and implement it inside the selected backend's host facet.

`WebViewHostEventSink.handle(event)` is the only callback entry point from a backend host facet into guarded Swing state. The return value matters only for boundary traversal events, where the backend must know whether the native/page event was accepted.

| Event | Producer examples | Swing host reaction |
| --- | --- | --- |
| `WebViewHostEvent.NativeFocusGained` | Windows WebView2 `GotFocus`; macOS first responder enters WKWebView or WebKit descendant; JCEF/AWT focus gained if needed. | Mark native/browser focus as inside the host, synchronize IDE focus state without requesting native focus again, and run guarded focus-enter bookkeeping. |
| `WebViewHostEvent.NativeFocusLost` | Windows WebView2 `LostFocus`; macOS first responder leaves WKWebView or the host window deactivates; JCEF/AWT focus lost if needed. | Check the current permanent Swing focus owner and run page leave only when focus really left the WebView host. |
| `WebViewHostEvent.Activated(source)` | Windows host-input processing, or a narrowly-scoped page pointer fallback only after a regression proves host input is insufficient; macOS WKWebView `mouseDown:`, `rightMouseDown:`, `otherMouseDown:`, native focus, or window reactivation. | Dispatch the normal Swing/AWT activation path that lets IDE popups and menus react naturally, mark browser-owned activation to suppress duplicate programmatic native focus, and mirror activation into guarded Swing state. |
| `WebViewHostEvent.MoveFocusRequested(direction)` | Windows WebView2 `MoveFocusRequested`; macOS private boundary detector or future AppKit traversal sentinel; page-side boundary detector where a native callback does not exist. | Ask Swing focus traversal to move to the previous/next component. Return `true` only when Swing accepted the traversal so the backend can mark the native/page event handled. |

## Engine and transport contracts

The engine contract should not expose Swing embedding details. Remove `ComponentBackedWebViewEngine`: a JCEF browser component is a host-facet concern, not a reason for the runtime engine abstraction to know about Swing components.

The current `WebViewEngineBridge` shape should be replaced or narrowed to a runtime/transport contract without UI properties:

```kotlin
// Kotlin type.
internal interface WebViewRuntimeEngine : WebViewEngine {
  suspend fun transferToJs(rawJson: String)
  fun connectMessageBus(receiver: WebViewJsMessageReceiver)
}
```

Do not keep `isHeavyweight` on the engine. Heavyweight/lightweight behavior belongs to `WebViewHostController` and the host registry policy. The runtime facet owns page/runtime operations: navigation, asset loading, JavaScript evaluation, native message transport, and close. The host facet owns Swing/native embedding: component, layout, focus entry, platform visibility, and host-window attachment.

JCEF may still use one implementation object internally if that is simpler, because `JBCefBrowser` naturally owns both runtime behavior and a Swing component. The important boundary is at creation: common code receives `WebViewRuntimeEngine` for runtime work and `WebViewHostController` for Swing embedding and never casts the runtime engine to a component-backed type.

## Backend facets, not parallel class hierarchies

Keep `WebViewRuntimeEngine` (Kotlin) and `WebViewHostController` (Kotlin) as separate contracts because they describe different responsibilities:

- `WebViewRuntimeEngine` owns page/runtime work: navigation, asset loading, JavaScript evaluation, message transport, and close;
- `WebViewHostController` owns mounted UI work: Swing component, native host attachment, layout, focus reactions behind its component, and shortcut routing.

This is not two per-OS implementation hierarchies. A provider should create one concrete backend for the selected implementation and expose two common facets from it:

```kotlin
// Kotlin type.
internal data class WebViewBackend(
  val runtimeEngine: WebViewRuntimeEngine,
  val hostController: WebViewHostController,
)
```

`WebViewBackend` is only an immutable value/tuple returned by the provider. It is not a controller, not a service, and not another polymorphic hierarchy. The two properties may point to the same backend object if that is the cleanest implementation:

```kotlin
// Kotlin types.
private class WinWebView2Backend : WebViewRuntimeEngine, WebViewHostController
private class MacWkWebViewBackend : WebViewRuntimeEngine, WebViewHostController
private class JcefWebViewBackend : WebViewRuntimeEngine, WebViewHostController
```

They may also point to private backend-owned facet delegates if that makes the native API easier to isolate. Those delegates are implementation details inside one backend object/session, not provider-selected class families. The host facet must not delegate host operations through the public `WebViewRuntimeEngine` facet, because that contract intentionally has no host operations. Runtime work and host work may share backend-private state, native handles, callbacks, and lifecycle ownership, but common code must treat them only as two facets returned by `WebViewBackend`.

## Layout state

`applyLayout` receives the whole desired Swing layout state every time a relevant Swing event occurs. If only visibility, bounds, scale, or displayability changed, the panel still recomputes a full immutable value and passes it to the backend:

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

`applyLayout` may be called from `addNotify`, `removeNotify`, resize/move/showing changes, ancestor movement, and DPI-related state changes. The method must be idempotent. Platform backends own their own coalescing and diffing: for example, Windows may wait until the Canvas HWND exists, create or recreate the native WebView2 handle for that HWND, and then apply bounds and visibility; macOS resolves and tracks the host component's Cocoa `NSView`; JCEF may treat most layout updates as no-ops.

## Swing host panel

`SwingWebViewHostPanel` should be an OS-agnostic Swing adapter:

- mount `hostController.component`;
- read Swing state into `WebViewHostLayoutParams` (Kotlin);
- call `hostController.applyLayout(params)` on relevant Swing state changes;
- request Swing focus on `hostController.component` when Swing/IDE initiated focus entry into WebView;
- call `hostController.swingFocusMovedOutside(event)` when Swing permanent focus moves out of the host;
- call `hostController.handleEditShortcut(event, command)` according to `hostController.editShortcutPolicy`;
- own the guarded focus state machine, popup/menu closing policy, host activation callbacks, and page enter/leave notifications;
- never choose platform-specific HWND/NSView focus fallback behavior.

`WebViewFocusDirection` (Kotlin/TS RPC) can remain as the current page/Swing protocol value for Tab and Shift-Tab traversal. Do not add direction-specific platform backend behavior for now; Swing focus policy may use the value to choose `focusNextComponent` or `focusPreviousComponent`.

## No common platform branching

Common code may branch on platform-neutral state owned by Swing, such as whether focus is currently inside the host, whether an activation is reentrant, or whether a focus traversal direction is forward/backward. It must not branch on operating system, backend kind, native handle type, or responder/window implementation details.

Bad common-code questions:

- `if (SystemInfo.isMac) clearFirstResponder()`;
- `if (engine.isHeavyweight) installSpecialTraversal()`;
- `if (hostHwnd != 0) useWindowsFocusPath()`;
- `if (firstResponderIsInsideWebView) makeFirstResponder(parent)`.

The replacement is either ordinary Swing focus on the hosted component or a platform-neutral event:

- `hostController.component.requestFocusInWindow()` for focus entry initiated by Swing/IDE;
- `hostController.swingFocusMovedOutside(WebViewSwingFocusExit(...))` with `WebViewSwingFocusExit` (Kotlin);
- `WebViewHostEventSink.handle(WebViewHostEvent.Activated(...))` with `WebViewHostEvent` (Kotlin), delivered by backend host/runtime callbacks;
- `hostController.handleEditShortcut(event, command)`.

The selected backend's host facet owns native consequences behind its component: it may observe component focus, native focus, native mouse, or runtime messages and then choose a no-op, WebView2 controller call, AppKit responder-chain operation, JCEF component method, or future backend-specific implementation.

## Platform backend host facets

- `WinWebView2Backend` (Kotlin) exposes a `WebViewHostController` (Kotlin) host facet that owns one heavyweight `Canvas`. The Canvas is the only Swing child mounted into `SwingWebViewHostPanel` (Kotlin), the focus component for AWT traversal, and the only host HWND passed to WebView2. The backend resolves the Canvas HWND, converts layout params to Windows host bounds, and applies them through backend-private WebView2 state/bridge calls.
- `MacWkWebViewBackend` (Kotlin) exposes a `WebViewHostController` (Kotlin) host facet that owns one heavyweight AWT host component, resolves that component's Cocoa `NSView`, attaches WKWebView as a subview of that host `NSView`, applies host-view-local bounds/visibility, owns AppKit main-thread dispatch, and handles AppKit responder cleanup privately. It must not attach WKWebView to `NSWindow.contentView`.
- `JcefWebViewBackend` (Kotlin) exposes a `WebViewHostController` (Kotlin) host facet over the existing `JBCefBrowser.component`. The same backend object may also be the `WebViewRuntimeEngine` (Kotlin). It keeps `applyLayout` minimal, delegates focus entry to `cefBrowser.uiComponent.requestFocusInWindow()` and `cefBrowser.setFocus(true)`, and may use `swingFocusMovedOutside(...)` to call `cefBrowser.setFocus(false)`. Page/native activation is normally a no-op for JCEF because the browser is already a Swing component in the normal AWT event path.

There should be no common-visible `WinWebView2HostController`, `MacWkWebViewHostController`, or `JcefWebViewHostController` class family. If a backend uses private host-facet delegates internally, they stay private implementation details of that backend and share the backend-owned native session.

