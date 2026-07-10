# Focus, Activation, And Shortcuts

Status: plan.

This document describes the platform-neutral focus, activation, traversal, and shortcut policy. Platform backends report facts through `WebViewHostEvent` and implement native consequences behind `WebViewHostController`; common Swing code owns the guarded policy.

## Focus Model

### Focus entry from Swing

When Swing focus enters the WebView host through traversal or IDE request:

1. `SwingWebViewHostPanel` enters guarded focus transition.
2. `SwingWebViewHostPanel` requests focus on `hostController.component`.
3. The backend host facet observes focus on its own component/native view and performs the native focus consequence.
4. On Windows this means WebView2 `controller.MoveFocus(PROGRAMMATIC)` from the Windows backend host facet; on macOS this means `makeFirstResponder(wkWebView)` from the macOS backend host facet; on JCEF this means the browser component/JCEF focus path.
5. Do not call Swing focus request again from native callback.

Why:

- Swing initiated focus, so the backend should activate the native browser behind its mounted component.
- Calling back into Swing focus request from the same path risks recursion.

### Focus entry from WebView2

When WebView2 fires `GotFocus`:

1. Native bridge calls `onFocusGained`.
2. Kotlin sends `WebViewHostEvent.NativeFocusGained` to `WebViewHostEventSink`.
3. Swing host synchronizes focus owner to the Canvas if needed.
4. Do not request native focus again.

Why:

- WebView2 already has native focus.
- Swing only needs to mirror state for IDE focus managers, popups, key dispatch, and traversal.

### Focus leave from WebView2

When WebView2 fires `LostFocus`:

1. Native bridge calls `onFocusLost`.
2. Swing host checks whether permanent focus owner is still inside the host/backend component.
3. If focus left the host, page `leave` lifecycle runs.
4. Do not call `SetFocus(parent)` or any native focus transfer.

Why:

- The OS/AWT focus move has already happened or is in progress.
- The WebView2 controller is hosted on the Canvas HWND, so normal focus transfer away from that Canvas should make WebView2 lose native focus.
- Native code does not know whether focus should go to editor, popup, toolbar, or another window.

### Traversal out of WebView2

When WebView2 fires `MoveFocusRequested`:

1. Map WebView2 reason to forward/backward traversal.
2. Ask Swing host to perform guarded traversal out of WebView.
3. Mark the WebView2 event handled only when Swing accepted the traversal.
4. Do not call a Windows clear-focus API. The AWT focus transfer away from the Canvas should produce WebView2 `LostFocus`.

Why:

- Tab and Shift-Tab are focus traversal semantics, not browser text editing semantics when WebView asks to move focus.
- Swing owns the next/previous component policy.


## macOS Traversal

macOS does not currently have a WebView2-style `MoveFocusRequested` controller callback. Keep the common traversal policy here, but put WK-specific boundary detection and AppKit responder details in [macOS WKWebView host](macos-wkwebview-host.md).

## Mouse And Activation

Common activation handling should consume a platform-neutral `WebViewHostEvent.Activated(source)` event. Backends choose how to produce that event.

For Windows, remove native WndProc mouse hooks. The primary path should be WebView2 controller focus events plus host input processing:

1. `ICoreWebView2Controller.GotFocus`, `LostFocus`, and `MoveFocusRequested` drive focus state. Do not install `focusin` page listeners for normal focus bookkeeping.
2. `AllowHostInputProcessing(true)` gives AWT/JBR the first chance to observe host input before WebView2 receives it.
3. If the Canvas/AWT path receives a mouse press while WebView focus is active or entering, the Windows host facet sends `WebViewHostEvent.Activated(HOST_INPUT)` to `WebViewHostEventSink`.
4. Popup/menu closing should be verified against that host-input path before adding any page script fallback.

Why:

- WebView2 controller focus events already tell us when WebView2 focus enters or leaves the browser.
- `AllowHostInputProcessing(true)` is the WebView2-supported way to let the host process keyboard, mouse, touch, and pen input first.
- A document-start script would duplicate signals that the controller or host input path already provides, and can create extra activation events while focus is already inside the WebView.

Add a page-message fallback only after a Windows regression test proves that controller focus events plus host input processing cannot produce the event needed for popup/menu closing. Until that proof exists, do not inject any activation script. If the proof exists, keep that fallback narrow:

1. Inject only pointer activation listeners such as `pointerdown`, with `mousedown` as a fallback for older content paths.
2. Do not inject `focusin` for focus state; WebView2 `GotFocus`/`LostFocus` own that path.
3. Do not inject `keydown`/`keyup` for shortcut routing by default; `AllowHostInputProcessing` and shortcut arbitration own that path.
4. The script posts a typed message through `chrome.webview.postMessage`.
5. Existing native `WebMessageReceived` forwards the raw message to Kotlin.
6. Kotlin routes this message as private runtime infrastructure and sends `WebViewHostEvent.Activated(PAGE_POINTER_FALLBACK)` only if the same activation was not already observed through host input.

Fallback message shape should be typed and versioned, for example:

```json
{
  "kind": "webview.hostActivation",
  "source": "pointer",
  "button": 0,
  "modifiers": 0,
  "clientX": 120,
  "clientY": 48
}
```

Do not expose this as public page API. It is runtime infrastructure and should remain absent unless the primary controller/host-input path is insufficient.

For macOS, do not rely on a page pointer message for normal WKWebView mouse activation. `MacWkWebViewBackend` should receive native AppKit callbacks from the WKWebView subclass (`mouseDown:`, `rightMouseDown:`, `otherMouseDown:`), send `WebViewHostEvent.Activated(NATIVE_MOUSE)` to `WebViewHostEventSink`, and then let WebKit handle the original event through `super`.

## Shortcut Arbitration

### Priority order

When focus is inside WebView, shortcut ownership should be resolved in this order:

1. IDE hard-reserved shortcuts.
2. Page-registered WebView shortcuts.
3. WebView-owned editing/navigation shortcuts.
4. Browser accelerators with explicit IDE conflicts.
5. Unknown keys.

### IDE hard-reserved shortcuts

These are shortcuts that must stay IDE-global while WebView is focused, for example Search Everywhere and other global IDE gestures chosen by product policy.

Host behavior:

- consume in Swing/IDE before WebView2;
- do not forward to page;
- if WebView2 later reports the same browser accelerator, set browser accelerator disabled for that event.

Why:

- IDE-global commands must remain reachable even from embedded browser content.
- This class must be small. A broad IDE-first policy breaks web apps.

### Page-registered WebView shortcuts

The page can register shortcuts through Kotlin-TS RPC.

Host behavior:

- store registered shortcuts in the WebView host state;
- if the IDE keymap matches the same keystroke, bypass IDE dispatch;
- allow the event to continue to WebView2/page;
- unregister shortcuts on page unload/dispose.

Why:

- WebView content may be a real application with its own command palette/editor shortcuts.
- Registration makes ownership explicit instead of guessing from key combinations.

### WebView-owned editing/navigation shortcuts

These should pass to WebView2 by default:

- typing and IME;
- `Ctrl+C`, `Ctrl+V`, `Ctrl+X`, `Ctrl+A`, `Ctrl+Z`;
- arrow keys;
- Ctrl-arrows;
- Shift-selection variants;
- Home/End/PageUp/PageDown and selection variants;
- Delete/Backspace and word deletion variants when focus is in page content.

Host behavior:

- do not invoke IDE action for these while WebView focus is active;
- do not mark handled in WebView2;
- let WebView2/page handle them.

Why:

- WebView2/browser has correct text editing, selection, IME, and DOM event semantics.
- Reimplementing these in Swing would be wrong and fragile.

### Browser accelerators with IDE conflicts

Use `ICoreWebView2AcceleratorKeyPressedEventArgs2.SetIsBrowserAcceleratorKeyEnabled(false)` for a specific event when the IDE owns that accelerator.

Do not set `AreBrowserAcceleratorKeysEnabled(false)` globally.

Why:

- Global disabling is too broad.
- Per-event disabling allows browser-owned shortcuts to keep working where there is no IDE conflict.

### Bare Shift and double-Shift

Remove the old low-level keyboard hook.

Replacement path:

- `AllowHostInputProcessing(true)` should let AWT/JBR see bare Shift key down/up while WebView focus is active.
- Do not add a document-start key listener by default.
- Add a narrowly-scoped private page-message key fallback only if tests/manual regression prove that AWT/JBR still does not receive bare Shift through host input processing. Do not add it while `AllowHostInputProcessing(true)` satisfies the gesture path.
- If that fallback is added, Kotlin must deduplicate host-input and page-message Shift events.

Why:

- WebView2 `AcceleratorKeyPressed` does not cover bare Shift reliably enough for double-Shift gestures.
- Host input processing is the intended replacement for the old native hook; page key messages are only a fallback after evidence.
- A low-level Windows hook is too broad and belongs outside this WebView-specific hosting model.

