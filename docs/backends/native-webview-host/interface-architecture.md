# Interface And Type Architecture

Status: plan.

The external user API stays unchanged: callers receive a `WebViewPanel` with one Swing `JComponent` to mount. Internally, each selected platform creates exactly one `WebViewController` (Kotlin) object. It fully owns one WebView's runtime, native host, focus, input, layout, and lifecycle. There is no separate engine, host controller, backend tuple, facet delegate, or second hierarchy. Platform implementations may have non-polymorphic private helpers and constructor callbacks into `SwingWebViewHostPanel`, but common code must not split those responsibilities into another common contract. Avoid late callback setters, nullable peer slots, and mutable attach/detach objects where construction order can express the dependency directly.

## Core controller

Replace the generic peer-style API with one controller contract. The common Swing layer sends platform-neutral facts and intentions; the selected platform controller decides the native consequences and also owns runtime operations:

```kotlin
// Kotlin type.
internal interface WebViewController {
  val component: Component
  val editShortcutPolicy: WebViewEditShortcutPolicy

  suspend fun loadFile(file: Path)
  suspend fun loadAsset(root: WebViewAssetRoot, entry: WebViewAssetPath, query: String?)
  suspend fun loadHtml(html: String, baseFile: Path?)
  suspend fun evaluateJavaScript(script: String): String?
  suspend fun close()
  suspend fun transferToJs(rawJson: String)
  fun connectMessageBus(receiver: WebViewJsMessageReceiver)

  fun applyLayout(params: WebViewHostLayoutParams)
  fun swingFocusMovedOutside(event: WebViewSwingFocusExit)
  fun handleEditShortcut(event: KeyEvent, command: WebViewEditCommand): Boolean
}

// Kotlin type.
internal fun interface WebViewHostEventSink {
  // Returns true only when the controller event needs an explicit handled/accepted result.
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
  NATIVE_MOUSE,
  NATIVE_FOCUS,
  WINDOW_REACTIVATION,
}
```

There is deliberately no `requestFocus()`, `focusWebView()`, `pageActivated()`, `releaseNativeFocusForSwing()`, `clearFocus()`, or `moveFirstResponderToParentView()` in this interface:

- Windows WebView2 loses focus when normal AWT focus transfer moves focus away from the heavyweight `Canvas` HWND that hosts the controller.
- JCEF should use its own component/AWT focus behavior.
- Focus entry starts with normal Swing focus on `controller.component`; the selected controller observes focus on its own component/native view and performs the native consequence internally.
- Page or native mouse activation is a callback into `SwingWebViewHostPanel` guarded host state, not a common command sent to the controller.
- `WebViewHostEventSink` is a callback port owned by `SwingWebViewHostPanel`, not another controller. It contains no focus policy; it only reports native facts back into guarded Swing state.
- macOS WKWebView first-responder cleanup is a private AppKit controller reaction to `swingFocusMovedOutside(...)`, not a common clear-focus command.
- Common code must not branch on `isWindows`, `isMac`, `isJcef`, heavyweight state, HWND availability, or AppKit first responder state. If behavior differs, implement it inside the selected `WebViewController`.

`WebViewHostEventSink.handle(event)` is the only callback entry point from a platform controller into guarded Swing state. The return value matters only for boundary traversal events, where the controller must know whether the native/page event was accepted.

| Event | Producer examples | Swing host reaction |
| --- | --- | --- |
| `WebViewHostEvent.NativeFocusGained` | Windows WebView2 `GotFocus`; macOS first responder enters WKWebView or WebKit descendant; JCEF/AWT focus gained if needed. | Mark native/browser focus as inside the host, synchronize IDE focus state without requesting native focus again, and run guarded focus-enter bookkeeping. |
| `WebViewHostEvent.NativeFocusLost` | Windows WebView2 `LostFocus`; macOS first responder leaves WKWebView or the host window deactivates; JCEF/AWT focus lost if needed. | Check the current permanent Swing focus owner and run page leave only when focus really left the WebView host. |
| `WebViewHostEvent.Activated(source)` | Windows WebView2 host-input processing; macOS WKWebView `mouseDown:`, `rightMouseDown:`, `otherMouseDown:`, native focus, or window reactivation. | Dispatch the normal Swing/AWT activation path that lets IDE popups and menus react naturally, mark browser-owned activation to suppress duplicate programmatic native focus, and mirror activation into guarded Swing state. |
| `WebViewHostEvent.MoveFocusRequested(direction)` | Windows WebView2 `MoveFocusRequested`; macOS private boundary detector or future AppKit traversal sentinel; page-side boundary detector where a native callback does not exist. | Ask Swing focus traversal to move to the previous/next component. Return `true` only when Swing accepted the traversal so the controller can mark the native/page event handled. |

## One controller, one implementation hierarchy

`WebViewController` (Kotlin) owns both page/runtime work (navigation, asset loading, JavaScript evaluation, message transport, close) and mounted UI work (Swing component, native host attachment, layout, focus reactions, visibility, and shortcut routing). `ComponentBackedWebViewEngine` and `WebViewEngineBridge` are removed; do not retain `isHeavyweight` or any component-backed engine branch.

`WebViewController` does not extend a separate `WebViewEngine` implementation contract. Existing names such as `WebViewEngineKind`, `WebViewEngineId`, capabilities, and provider selection may remain as common selection metadata, but no per-OS `*Engine` implementation exists. Provider creation returns `WebViewController` directly.

Each provider creates and returns exactly one selected-platform controller:

```kotlin
// Kotlin types.
private class WinWebViewController : WebViewController
private class MacWkWebViewController : WebViewController
private class JcefWebViewController : WebViewController
```

Provider construction has one required operation: `createController(...): WebViewController` (Kotlin). There is no `createEngine`, `createBackend`, `createNativeHostPeer`, default stub controller, or compatibility delegation between old and new creation APIs.

Do not create per-OS `*Engine`, `*HostController`, `*Backend`, or facet-delegate classes, public or private. Non-polymorphic private helpers are allowed only for narrow native/JNI/AppKit/WebView2 calls; they do not implement `WebViewController` and do not own an independent lifecycle. The controller owns all shared native state, callbacks, and lifecycle.

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

## No common platform branching

Common code may branch on platform-neutral state owned by Swing, such as whether focus is currently inside the host, whether an activation is reentrant, or whether a focus traversal direction is forward/backward. It must not branch on operating system, controller kind, native handle type, or responder/window implementation details.

Bad common-code questions:

- `if (SystemInfo.isMac) clearFirstResponder()`;
- `if (engine.isHeavyweight) installSpecialTraversal()`;
- `if (hostHwnd != 0) useWindowsFocusPath()`;
- `if (firstResponderIsInsideWebView) makeFirstResponder(parent)`.

The replacement is either ordinary Swing focus on the hosted component or a platform-neutral event:

- `controller.component.requestFocusInWindow()` for focus entry initiated by Swing/IDE;
- `controller.swingFocusMovedOutside(WebViewSwingFocusExit(...))` with `WebViewSwingFocusExit` (Kotlin);
- `WebViewHostEventSink.handle(WebViewHostEvent.Activated(...))` with `WebViewHostEvent` (Kotlin), delivered by controller callbacks;
- `controller.handleEditShortcut(event, command)`.

The selected controller owns native consequences behind its component: it may observe component focus, native focus, native mouse, or runtime messages and then choose a no-op, WebView2 controller call, AppKit responder-chain operation, JCEF component method, or future platform-specific implementation.

## Platform controllers

- `WinWebViewController` (Kotlin) owns one heavyweight `Canvas`. The Canvas is the only Swing child mounted into `SwingWebViewHostPanel` (Kotlin), the focus component for AWT traversal, and the only host HWND passed to WebView2. The controller resolves the Canvas HWND, converts layout params to Windows host bounds, and applies them through private WebView2 calls.
- `MacWkWebViewController` (Kotlin) owns one heavyweight AWT host component, resolves that component's Cocoa `NSView`, attaches WKWebView as a subview of that host `NSView`, applies host-view-local bounds/visibility, owns AppKit main-thread dispatch, and handles AppKit responder cleanup privately. It must not attach WKWebView to `NSWindow.contentView`.
- `JcefWebViewController` (Kotlin) owns the existing `JBCefBrowser.component`. It keeps `applyLayout` minimal, delegates focus entry to `cefBrowser.uiComponent.requestFocusInWindow()` and `cefBrowser.setFocus(true)`, and may use `swingFocusMovedOutside(...)` to call `cefBrowser.setFocus(false)`. Page/native activation is normally a no-op because the browser is already a Swing component in the normal AWT event path.

