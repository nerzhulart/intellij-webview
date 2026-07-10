# Migration Plan

Status: plan.

This document describes the implementation sequence, tests, risks, and acceptance criteria for moving from the existing peer/container/overlay model to the host-controller architecture.

## Implementation Process

Do not incrementally wrap the old implementation until it looks like the new architecture. Before implementing a subsystem described in this document, remove the code that is being replaced and rebuild it against the target contracts in this document.

Process:

1. Identify the old subsystem being replaced: peer API, Windows container HWND code, macOS `NSWindow.contentView` overlay hosting, component-backed engine branching, focus-clear hooks, or shortcut routing.
2. Delete the production code for that subsystem.
3. Keep existing tests as behavioral coverage.
4. Adapt tests only where they refer to removed APIs or old implementation details.
5. Implement the new code directly against `WebViewHostController` (Kotlin), `WebViewRuntimeEngine` (Kotlin), concrete platform backends, and the platform-neutral focus/activation events.
6. Add new tests for behavior that was previously unobservable or platform-specific, especially backend host-facet events and macOS host `NSView` ownership.

Rules:

- Do not keep compatibility shims for removed APIs unless a public API requires them. Internal peer APIs should be deleted, not deprecated.
- Do not preserve old code as a fallback path. If the new macOS host `NSView` cannot be resolved reliably, treat that as a blocker.
- Do not add `isWindows`/`isMac`/backend-kind checks to common Swing code while porting. Add or adjust a platform-neutral backend host event instead.
- Tests should assert behavior and `WebViewHostController`/backend-facet contracts, not the old peer method names or old native side effects.

## Migration Steps

### Step 1: Introduce immutable host-facet wiring

- Add `WebViewHostController` (Kotlin), `WebViewHostLayoutParams` (Kotlin), `WebViewSwingFocusExit` (Kotlin), `WebViewHostEventSink` (Kotlin), and `WebViewHostEvent` (Kotlin).
- Remove `ComponentBackedWebViewEngine` (Kotlin); expose a JCEF host facet through `WebViewHostController` (Kotlin) instead of letting the runtime engine expose a Swing component.
- Replace or narrow `WebViewEngineBridge` (Kotlin) to a runtime transport contract such as `WebViewRuntimeEngine` (Kotlin), without `isHeavyweight` or component-backed branching.
- Build platform backend wiring once with `val` collaborators.
- Move late callback setters and peer mutable attach state into constructor-provided backend collaborators owned by the backend host facet.
- Keep `WebViewFocusDirection` (Kotlin/TS RPC) as a protocol value only; do not add platform-specific direction behavior.
- Replace OS/backend checks in `SwingWebViewHostPanel` with platform-neutral host events.

Why first:

- It gives the refactoring a small target API before removing old peer methods.
- It prevents preserving `clearFocus` or `focusComponent` only because the old peer exposed them.

### Step 2: Rename concepts and remove parent/container language

- Rename `currentParentHwnd` to `currentHostHwnd`.
- Rename `attachToParent` calls to host-HWND creation/recreation flow.
- Update diagnostics from `parentHwnd` to `hostHwnd`.

Why after Step 1:

- It prevents new code from continuing the old mental model.
- It makes reviews easier because parent/container references stand out as leftovers.

### Step 3: Controller options creation

- Add WebView2 environment cast to `ICoreWebView2Environment10`.
- Create controller options.
- Set `AllowHostInputProcessing(true)` through `ICoreWebView2ControllerOptions4`.
- Create the controller with `CreateCoreWebView2ControllerWithOptions`.

Why before removing keyboard fallback:

- It gives the host a supported input path before deleting the low-level hook.

### Step 4: Remove native container window

- Delete `create_container_hwnd`.
- Delete custom WndProc.
- Delete `GWLP_USERDATA` callback storage.
- Delete `SetParent`, `ShowWindow`, `SetWindowPos`, and `DestroyWindow` usage for hosting.
- Store only `host_hwnd`.

Why after options:

- The replacement input path is already in place.

### Step 5: Replace attach/detach

- Remove JNI attach/detach parent methods.
- Do not add `setHostWindow(handle, hostHwnd)`, `SetParentWindow`, or any equivalent live-controller reparent operation.
- Kotlin detach should hide/close/destroy through controller lifecycle, not unparent an HWND.
- Recreate WebView2 on a new Canvas HWND when the AWT peer changes, then replay deterministic backend state.

Why:

- The normal path creates WebView2 on the resolved Canvas HWND.
- Moving between Canvas peers is a host lifecycle transition, not a separate bridge API operation.
- Detach should not mutate AWT-owned windows.

### Step 6: Focus callbacks

- Add `LostFocus` and `MoveFocusRequested`.
- Keep `GotFocus`.
- Remove Windows native `clearFocus`.
- Route focus events into the guarded host state in `SwingWebViewHostPanel`.

Why:

- Focus state transitions become observable WebView2 events instead of Win32 side effects.

### Step 7: Activation path verification

- Remove `onBeforeMouseFocus`.
- Verify that WebView2 `GotFocus`/`LostFocus` plus `AllowHostInputProcessing(true)` produce the activation and AWT event flow needed to close IDE/Swing popups.
- Add a private document-start pointer fallback only if that verification fails.
- Keep focus and keyboard out of the fallback unless separate tests prove a gap in WebView2 controller events or host input processing.

Why:

- The old WndProc mouse activation path must be removed.
- The replacement should first use WebView2 controller and host-input APIs already enabled for the Windows backend.
- WebMessage fallback is acceptable only as private runtime infrastructure for a proven missing pointer signal.

### Step 8: Shortcut registry and arbitration

- Add a WebView-owned shortcut registry on the Kotlin side.
- Wire Kotlin-TS RPC for page shortcut registration.
- Update Swing dispatcher policy for WebView focus.
- Use per-event browser accelerator disabling for IDE conflicts.
- Remove old low-level Shift fallback.

Why last:

- Shortcut behavior depends on host input processing and focus state. It must not depend on a page-message fallback unless Step 7 produced a concrete failing regression that required one.

### Step 9: macOS embedded WK host

- Implement `MacWkWebViewBackend` as the only target macOS WK backend, exposing host behavior through `WebViewHostController` (Kotlin).
- Use a heavyweight AWT host component and resolve that component's Cocoa `NSView`.
- Attach WKWebView as a subview of the host `NSView`; do not attach it to `NSWindow.contentView`.
- Replace window-content-relative frame code with host-view-local bounds.
- Move AppKit mouse activation, first-responder cleanup, `flagsChanged:`, edit-command routing, and `viewDidMoveToWindow` handling into macOS platform code.
- Treat missing or unstable host `NSView` resolution as a blocker, not as a fallback to overlay hosting.

Why separate:

- This removes the current macOS overlay model instead of preserving it as another strategy.
- It keeps AppKit responder behavior in the macOS backend host facet and leaves common Swing code OS-agnostic.

## Test Plan

### Unit tests

Update the Windows backend tests, including the existing `WinWebViewEngineTest` if it has not been renamed yet:

- `create` with `hostHwnd = 0` fails fast.
- create uses controller options with `AllowHostInputProcessing = true`.
- no fake bridge method exists for `attachToParent` or `detachFromParent`.
- host HWND change destroys the old handle and recreates WebView2 on the new Canvas HWND.
- recreation replays:
  - last load;
  - last bounds;
  - current visibility;
  - pre-existing configured document-start scripts, if any;
  - asset resolver state.
- detach/close calls controller visibility/close paths only.
- bounds apply through controller bounds only.
- backend host facet receives full immutable layout params and applies only changed native state internally.
- engine/runtime tests no longer branch on `ComponentBackedWebViewEngine` or engine `isHeavyweight`; backend host-facet tests own those decisions.
- Swing host tests verify focus entry requests focus on `hostController.component`, focus exit calls `hostController.swingFocusMovedOutside(...)`, and activation is handled through `WebViewHostEventSink.handle(WebViewHostEvent.Activated(...))`.
- Common tests do not assert Windows/macOS-specific native side effects; those belong to backend host-facet tests.
- macOS backend tests cover host `NSView` resolution failure as a hard failure/blocker, not an overlay fallback.

Update focus tests:

- WebView2 `GotFocus` syncs Swing focus without native focus recursion.
- WebView2 `LostFocus` triggers page leave only when focus leaves host.
- WebView2 `MoveFocusRequested` exits forward/backward through Swing traversal.
- Swing focus lost from the Canvas does not call Windows native clear focus.
- focus transfer from Canvas to a Swing control makes WebView2 emit `LostFocus` and stops WebView caret activity.

Update shortcut tests:

- WebView-owned edit shortcuts bypass IDE dispatcher.
- Ctrl-arrows and selection/navigation shortcuts stay WebView-owned.
- page-registered shortcut bypasses IDE action even if keymap matches.
- IDE hard-reserved shortcut is consumed by host.
- browser accelerator conflict disables browser accelerator for that event only.
- double-Shift reaches IDE gesture path without the old low-level hook.

Update activation tests:

- WebView2 focus events plus host-input mouse events activate guarded Swing host state.
- Popup/menu closing works without the old WndProc hook.
- Activation is guarded from focus reentrancy.
- If a page pointer fallback is added, page `pointerdown` messages activate Swing host state only when host input did not already report the same activation.

### Integration/manual checks

Windows:

- Search Everywhere closes when clicking inside WebView.
- Run Anything closes when clicking inside WebView.
- Swing context menu closes when clicking inside WebView.
- WebView page popup does not flicker or close from extra blur/focus.
- Tab and Shift-Tab enter and exit WebView.
- Moving focus from WebView to another Swing control stops the WebView caret without a Windows clear-focus call.
- Ctrl+C/V/X/A/Z work inside editable WebView content.
- Ctrl-arrows and Shift-selection work inside editable WebView content.
- page-registered shortcuts work inside WebView even when IDE keymap has the same keystroke.
- IDE hard-reserved shortcuts still work inside WebView.
- double-Shift works inside WebView.
- Markdown preview tab switching no longer flashes WebView at 0,0 or fullscreen.

macOS:

- Existing focus and popup/menu regressions still pass.
- Clicking inside WKWebView activates the Swing host focus state and closes active IDE popups/menus.
- Inactive-window click inside WKWebView activates the IDE window and browser without a visible WebKit blur/focus bounce.
- Right click inside WKWebView activates host state and closes conflicting IDE popups/menus without breaking WebKit context-menu behavior.
- Moving focus from WKWebView to another Swing component in the same IDE window moves AppKit first responder outside WKWebView.
- Moving focus between two WebView hosts in the same Swing window does not let the losing host clear the gaining host's AppKit first responder.
- Tab and Shift-Tab enter WKWebView, traverse inside DOM, and exit to the previous/next Swing component only at the page boundary.
- Modified Tab and Tab during IME composition stay browser/page-owned.
- `Cmd+C/V/X/A/Z` and selection/navigation shortcuts work inside editable WKWebView content when AppKit first responder is inside WKWebView.
- Bare Shift/Ctrl modifier transitions still reach IDE gesture tracking without consuming the AppKit event.
- The macOS mouse activation callback, if added, is native/private host infrastructure and does not become public page API.
- WKWebView first-responder cleanup, if needed, stays inside the macOS backend/private hook and does not become a common `WebViewHostController` method.
- WKWebView is attached to the host component's native `NSView`, not to `NSWindow.contentView`, and clipping/z-order/focus behavior are verified against the legacy overlay backend before that code is deleted.
- No Windows controller-only assumptions leak into `SwingWebViewHostPanel`.

## Risks And Mitigations

### `AllowHostInputProcessing` changes event timing

Risk:

- `AcceleratorKeyPressed` may be delivered differently from the old path.
- Swing may see keys that previously only WebView2 saw.

Mitigation:

- Keep WebView-owned shortcut allow rules broad.
- Add tests for edit/navigation keys.
- Keep IDE hard-reserved list small.

### Optional page activation fallback can duplicate host input events

Risk:

- If a page-message fallback is added, pointer activation might arrive both through AWT host input and WebMessage.

Mitigation:

- Do not add the fallback until a test proves it is needed.
- Use existing focus reentrancy guard.
- Add short activation deduplication by event kind/time if needed.
- Activation should be idempotent.

### Canvas HWND may change

Risk:

- AWT/JBR may recreate the Canvas peer, so the backend may observe a different host HWND after `addNotify`, tab moves, tool-window moves, or DPI/display transitions.

Mitigation:

- Treat this as a host-lifetime transition.
- Log the old and new host HWND values for diagnostics.
- Destroy the old WebView2 controller and create a new one with `CreateCoreWebView2ControllerWithOptions(newHostHwnd, ...)`.
- Replay deterministic backend state: last load request, bounds, visibility, virtual-host mappings, asset resolver state, pre-existing configured document-start scripts if any, and any other configuration that belongs to the WebView session contract. Activation/key fallback scripts are not part of this state unless a regression test explicitly introduced them.

### DPI behavior may still be wrong

Risk:

- Removing Win32 container positioning may not fully fix all scaling issues.

Mitigation:

- Keep the first patch on existing scaling semantics.
- Add separate follow-up for `BoundsMode/RasterizationScale` if mixed-DPI bugs remain.

### macOS host `NSView` may not be stable

Risk:

- The chosen heavyweight AWT host component may not expose a stable Cocoa `NSView` across JBR peer recreation, tab moves, or tool-window reparenting.
- If WKWebView is attached to the wrong native view, z-order, clipping, fullscreen, and focus behavior can regress more severely than in the legacy overlay backend.

Mitigation:

- Treat missing or unstable host `NSView` resolution as a blocker for the new macOS implementation, not as a reason to keep a silent overlay fallback.
- Keep native mouse activation and private first-responder cleanup inside `MacWkWebViewBackend`; do not expose them as common API.
- Replay attachment state deterministically after every host-view change, the same way the Windows plan replays WebView2 state after host HWND changes.

## Acceptance Criteria

The change is complete when:

- Windows WebView2 no longer creates or owns a native container HWND.
- Rust bridge no longer calls Win32 hosting APIs for WebView placement or visibility.
- Windows native bridge has no `attachToParent`, `detachFromParent`, `setHostWindow`, or `clearFocus` API.
- WebView2 controller is created with `AllowHostInputProcessing(true)`.
- Common Swing code has no OS/backend/native-handle branching for hosting, focus, activation, or shortcut behavior.
- Platform-specific focus and input differences are expressed through the selected backend's `WebViewHostController` facet.
- macOS WKWebView is attached to the host component's native `NSView`, not to `NSWindow.contentView`.
- Windows focus transitions use WebView2 focus events and Swing guarded policy.
- Popup/menu closing works through WebView2 focus events and natural AWT host-input activation; page-message activation is used only if required by a regression test.
- WebView-owned shortcuts and page-registered shortcuts work inside WebView.
- IDE hard-reserved shortcuts still work inside WebView.
- Tests and manual regressions listed above pass.
