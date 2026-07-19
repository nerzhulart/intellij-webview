# Migration Plan

Status: plan.

This document describes the implementation sequence, tests, risks, and acceptance criteria for replacing the existing peer/container/overlay model with one-controller-per-WebView architecture.

## Implementation Process

### Non-negotiable rewrite rule

**Do not study the old implementation as an implementation guide. Do not copy, extract, rename, or refactor it. Delete it before starting the replacement.** The old code may be used only to identify the complete deletion boundary and to retain behavioral tests; it is not a source of design, APIs, ownership, or control flow for the new implementation.

Do not incrementally wrap, rename, extract from, or otherwise remodel the old implementation until it resembles this architecture. For each platform subsystem, delete the production implementation being replaced first, then write its replacement against the target contracts in this document.

For Windows specifically, do **not** start by editing `WinWebViewController` or the existing peer/container implementation. Delete every Windows-specific production layer except the native WebView2 library and its thin Kotlin JNI wrapper. The first replacement production implementation must be `WinWebViewController`: one controller for one AWT Canvas HWND, WebView2 controller creation on that HWND, no Rust-owned host window, and no native event loop. A patch is not a migration if it keeps both the old Rust-created container-HWND path and the new Canvas-HWND controller path as selectable, conditional, or fallback production code.

For macOS specifically, do **not** start by editing `MacWebViewEngine`, `MacNativeWebViewHostPeer`, or the existing overlay/focus implementation. Preserve its behavioral tests, then delete the old `NSWindow.contentView` overlay path and all macOS-specific hosting, layout, focus, input, and attach/detach policy selected for replacement. Only after that deletion should `MacWkWebViewController` be written from this document. Old and new macOS production paths must never coexist.

Target contracts may be introduced before platform code only when they are new, standalone declarations and do not adapt, delegate to, or preserve old production hosting APIs. They are a specification boundary, not a staging layer around the old implementation.

Definition of a new per-OS implementation:

- The old controller/engine/peer/hosting source files selected for replacement have been deleted before the new controller implementation is added.
- `WinWebViewController` or `MacWkWebViewController` is introduced as a new class with ownership and control flow derived from this document, not by renaming or moving an old class/file.
- The new controller has no dependency on deleted internal types, compatibility adapters, feature flags selecting legacy behavior, or fallback to the old native host path.
- Shared common contracts, behavioral tests, system framework bindings, and the explicitly retained Windows native library/Kotlin wrapper are the only reusable inputs. Old per-OS method bodies and state machines are not reusable inputs.
- Code review must reject a change that cannot demonstrate the relevant deletion checklist before the first new per-OS production implementation appears.

Process:

1. Identify the complete old production subsystem being replaced: peer API, Windows container HWND code, macOS `NSWindow.contentView` overlay hosting, component-backed engine branching, focus-clear hooks, and their private glue.
2. Preserve its tests as behavioral coverage, but do not preserve its production code to make tests compile.
3. Delete the old production subsystem as a whole.
4. Adapt tests only where they name a deleted internal API or assert an obsolete implementation detail; retain the behavioral assertions.
5. Write the new implementation directly against one `WebViewController` (Kotlin), one concrete platform controller, and the platform-neutral focus/activation events.
6. Add new tests for behavior that was previously unobservable or platform-specific, especially controller events and macOS host `NSView` ownership.

Rules:

- Do not keep compatibility shims for removed APIs unless a public API requires them. Internal peer APIs should be deleted, not deprecated.
- Do not edit the old implementation toward the new design. Delete it; the replacement is new code with new ownership and names.
- Do not leave an old and new hosting path coexisting behind flags, runtime detection, temporary branches, or fallback selection. This includes Windows container HWND creation, custom WndProc, `SetParent` attach/detach, `clearFocus`, and low-level input hooks.
- For Windows, retain only the native WebView2 library and its thin Kotlin JNI wrapper. Delete every other Windows-specific production class, function, helper, state holder, thread, event loop, and policy hook before implementing `WinWebViewController`.
- For macOS, delete the old overlay peer/controller and its AppKit hosting/focus/input/layout glue before implementing `MacWkWebViewController`. Existing platform binding primitives may remain, but old macOS policy and control flow must not be copied into the new controller.
- AWT's EDT/native event pump is the only Windows UI loop. The retained native library must not create a Windows message loop, dispatch loop, custom WndProc, or auxiliary UI thread.
- All Windows host lifecycle operations use documented `ICoreWebView2Controller` APIs. Navigation, scripts, messages, and settings use their corresponding documented `ICoreWebView2` APIs; no Win32 hosting/input/focus operation is allowed.
- A temporary compile failure after deletion is expected. Fix it by adapting callers and tests to the target contracts, never by restoring an old host path or adding an internal bridge back to it.
- Do not preserve old code as a fallback path. If the new macOS host `NSView` cannot be resolved reliably, treat that as a blocker.
- Do not add `isWindows`/`isMac`/controller-kind checks to common Swing code while porting. Add or adjust a platform-neutral controller event instead.
- For every selected implementation, create exactly one concrete `WebViewController` object. Do not introduce per-OS `*Engine`, `*HostController`, `*Backend`, tuple, or facet-delegate classes. JCEF is adapted to this contract but is not part of the Windows/macOS clean-room deletion rule.
- Tests should assert `WebViewController` behavior, not the old peer method names or old native side effects.

Windows deletion checklist, before `WinWebViewController` production code is written:

- Rust-created container HWND creation/destruction and its custom WndProc are gone.
- `GWLP_USERDATA` callback storage, `SetParent`, and Win32 placement/visibility calls used to host WebView2 are gone.
- JNI attach/detach/reparent methods and Windows `clearFocus` are gone.
- Existing Windows host implementation and its mutable peer/container state are gone rather than renamed or reused.
- Every old Windows-specific production class/helper is gone, except the native WebView2 library and its thin Kotlin JNI wrapper.
- No native Windows message loop, dispatch loop, custom WndProc, or auxiliary UI thread remains.
- No common or platform code still selects that deleted path.

macOS deletion checklist, before `MacWkWebViewController` production code is written:

- `MacNativeWebViewHostPeer` and the old macOS engine/controller selected for replacement are gone, not renamed.
- `NSWindow.contentView` overlay attachment and window-relative geometry/retry policy are gone.
- Old macOS focus, mouse, keyboard, responder-cleanup, and mutable attach/detach policy are gone.
- No overlay fallback, feature flag, or runtime selector can reach the deleted path.
- Retained tests describe behavior only and do not instantiate deleted internal types.

## Migration Steps

### Step 1: Define the standalone target contracts

- Add `WebViewController` (Kotlin), `WebViewHostLayoutParams` (Kotlin), `WebViewSwingFocusExit` (Kotlin), `WebViewHostEventSink` (Kotlin), and `WebViewHostEvent` (Kotlin).
- Remove `ComponentBackedWebViewEngine` (Kotlin) and `WebViewEngineBridge` (Kotlin); one `WebViewController` owns both JCEF runtime work and its Swing component, without `isHeavyweight` or component-backed branching.
- Replace provider construction with one required `createController(...): WebViewController` (Kotlin) method. Delete `createBackend`, `createEngine`, `createNativeHostPeer`, `WebViewBackend`, and `StubWebViewHostController`; do not keep default provider implementations or compatibility delegation between old and new creation APIs.
- This step defines common declarations only; do not implement a per-OS controller while its old implementation still exists.
- Require each future controller to receive constructor-provided `val` collaborators. Do not move old late callback setters or mutable peer state into the new classes.
- Keep `WebViewFocusDirection` (Kotlin/TS RPC) as a protocol value only; do not add platform-specific direction behavior.
- Replace OS/controller checks in `SwingWebViewHostPanel` with platform-neutral host events.

Why first:

- It gives the new code a small target API without turning the old peer methods into a migration boundary.
- It prevents preserving `clearFocus` or `focusComponent` only because the old peer exposed them.

### Step 2: Delete the Windows hosting subsystem

- Delete the old Windows production host before adding its replacement: container HWND creation, custom WndProc, `GWLP_USERDATA` callback storage, parent attach/detach, reparenting, native `clearFocus`, low-level input hooks, native event/message loop, and auxiliary UI thread.
- Delete all old Windows engine/peer classes, helpers, and mutable state. Retain only the native WebView2 library and its thin Kotlin JNI wrapper; do not rename or reuse the deleted code.
- Adapt retained tests to the standalone target contracts; do not retain internal compatibility APIs to satisfy them.

Why now:

- It makes the old path unavailable before the replacement is written.
- It prevents a partial migration where the new Canvas HWND model delegates to or falls back to a container HWND.

### Step 3: Write `WinWebViewController` and the bridge

- Read `C:\Users\nerzh\AppData\Roaming\JetBrains\Rider2026.3\resharper-host\DecompilerCache\decompiler\8c08f3c4d2b44fba995c61b20206dce19868\1a\f9cdefa9\WebView2.cs` before implementing this step. Use the decompiled WinForms wrapper to verify the Microsoft WebView2 controller lifecycle and event ordering, not as code to port.
- Create the AWT `Canvas` and execute controller creation/callback handling on its AWT-owned native thread; do not create a native loop or thread.
- Add WebView2 environment cast to `ICoreWebView2Environment10`.
- Create controller options.
- Set `AllowHostInputProcessing(true)` through `ICoreWebView2ControllerOptions4`.
- Create the controller with `CreateCoreWebView2ControllerWithOptions`.

Why after deletion:

- There is now only one production hosting path to implement and test.

### Step 4: Implement Canvas-host lifecycle

- Store `currentHostHwnd` only in the new controller to detect AWT peer recreation.
- Create WebView2 directly on the resolved Canvas HWND.
- Apply bounds, visibility, focus, traversal, and host input through WebView2 controller APIs only.
- Do not create a native container, reparent a live controller, create a native loop, or use Win32 placement/visibility/input/focus APIs for WebView2 hosting.

Why separate:

- It makes host recreation a lifecycle of the new controller, not an attach/detach variation of the deleted path.

### Step 5: Define recreation and close behavior

- The new JNI wrapper surface must not define attach/detach parent methods; they were deleted in Step 2.
- Do not add `setHostWindow(handle, hostHwnd)`, `SetParentWindow`, or any equivalent live-controller reparent operation.
- Kotlin detach should hide/close/destroy through controller lifecycle, not unparent an HWND.
- Recreate WebView2 on a new Canvas HWND when the AWT peer changes, then replay deterministic controller state.

Why:

- The normal path creates WebView2 on the resolved Canvas HWND.
- Moving between Canvas peers is a host lifecycle transition, not a separate bridge API operation.
- Detach should not mutate AWT-owned windows.

### Step 6: Implement focus callbacks

- Define new `GotFocus`, `LostFocus`, and `MoveFocusRequested` callback bindings from the WebView2 controller events.
- Do not reintroduce Windows native `clearFocus`; it was deleted in Step 2.
- Route focus events into the guarded host state in `SwingWebViewHostPanel`.

Why:

- Focus state transitions become observable WebView2 events instead of Win32 side effects.

### Step 7: Verify activation path

- Do not reintroduce the deleted `onBeforeMouseFocus`/WndProc activation concept.
- Verify that WebView2 `GotFocus`/`LostFocus` plus `AllowHostInputProcessing(true)` produce the activation and AWT event flow needed to close IDE/Swing popups.
- Do not add document-start pointer/focus/key listeners or WebMessage activation fallback. If verification fails, the Windows implementation is blocked until the WebView2 controller/AWT path is corrected.

Why:

- The old WndProc mouse activation path must be removed.
- The replacement should first use WebView2 controller and host-input APIs already enabled for `WinWebViewController`.
- Page scripts and Win32 hooks are not acceptable activation substitutes.

### Step 8: Implement shortcut registry and arbitration

- Add a WebView-owned shortcut registry on the Kotlin side.
- Wire Kotlin-TS RPC for page shortcut registration.
- Update Swing dispatcher policy for WebView focus.
- Use per-event browser accelerator disabling for IDE conflicts.
- Do not reintroduce the deleted low-level Shift hook.

Why last:

- Shortcut behavior depends only on host input processing, WebView2 accelerator events, and guarded Swing state. It must not depend on page-message or low-level hook fallback.

### Step 9: Delete the macOS overlay implementation

- Keep behavioral tests, then delete the old macOS production implementation covered by the macOS deletion checklist.
- Do not create compatibility adapters, renamed peers, or an overlay fallback to keep old internal APIs compiling.
- Adapt callers and tests to the target `WebViewController` API after deletion.

Why before implementation:

- It guarantees that the new controller cannot delegate to or copy the old overlay path.
- It makes failure to obtain a proper AWT-owned `NSView` a visible blocker instead of a reason to restore legacy hosting.

### Step 10: Write `MacWkWebViewController`

- Implement `MacWkWebViewController` as the only target macOS WK controller.
- Use a heavyweight AWT host component and resolve that component's Cocoa `NSView`.
- Attach WKWebView as a subview of the host `NSView`; do not attach it to `NSWindow.contentView`.
- Implement host-view-local bounds from scratch; the deleted window-content-relative frame code is not a source implementation.
- Implement AppKit mouse activation, first-responder cleanup, `flagsChanged:`, edit-command routing, and `viewDidMoveToWindow` handling inside the new controller without porting old control flow.
- Treat missing or unstable host `NSView` resolution as a blocker, not as a fallback to overlay hosting.

Why separate:

- The current macOS overlay model was removed in Step 9 and cannot survive as another strategy.
- It keeps AppKit responder behavior in `MacWkWebViewController` and leaves common Swing code OS-agnostic.

### Step 11: Adapt JCEF

- Implement the unified `JcefWebViewController` surface over the existing `JBCefBrowser` instance.
- Do not create separate JCEF runtime-engine and host-controller implementations.
- Keep JCEF behavior in the ordinary Swing/AWT path; platform-specific no-op methods must be explicit and commented.

## Test Plan

### Unit tests

Update the retained Windows behavioral tests and add controller tests for the new implementation:

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
- controller receives full immutable layout params and applies only changed native state internally.
- tests no longer branch on `ComponentBackedWebViewEngine` or `isHeavyweight`; controller tests own those decisions.
- Swing host tests verify focus entry requests focus on `controller.component`, focus exit calls `controller.swingFocusMovedOutside(...)`, and activation is handled through `WebViewHostEventSink.handle(WebViewHostEvent.Activated(...))`.
- Common tests do not assert Windows/macOS-specific native side effects; those belong to platform controller tests.
- macOS controller tests cover host `NSView` resolution failure as a hard failure/blocker, not an overlay fallback.

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
- No Windows page pointer/key fallback is present.

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
- WKWebView first-responder cleanup, if needed, stays inside `MacWkWebViewController` and does not become another common API.
- WKWebView is attached to the host component's native `NSView`, not to `NSWindow.contentView`; clipping, z-order, and focus behavior satisfy the retained behavioral tests without retaining or consulting the legacy overlay implementation.
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

### Canvas HWND may change

Risk:

- AWT/JBR may recreate the Canvas peer, so the controller may observe a different host HWND after `addNotify`, tab moves, tool-window moves, or DPI/display transitions.

Mitigation:

- Treat this as a host-lifetime transition.
- Log the old and new host HWND values for diagnostics.
- Destroy the old WebView2 controller and create a new one with `CreateCoreWebView2ControllerWithOptions(newHostHwnd, ...)`.
- Replay deterministic controller state: last load request, bounds, visibility, virtual-host mappings, asset resolver state, pre-existing configured document-start scripts if any, and any other configuration that belongs to the WebView session contract. Activation/key fallback scripts do not exist in the Windows design.

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
- Keep native mouse activation and private first-responder cleanup inside `MacWkWebViewController`; do not expose them as another common API.
- Replay attachment state deterministically after every host-view change, the same way the Windows plan replays WebView2 state after host HWND changes.

## Acceptance Criteria

The change is complete when:

- Windows WebView2 no longer creates or owns a native container HWND.
- Rust bridge no longer calls Win32 hosting APIs for WebView placement or visibility.
- Apart from the retained native WebView2 library and its thin Kotlin JNI wrapper, no old Windows-specific production code remains.
- No old macOS overlay/peer/controller production code remains; `MacWkWebViewController` is a new implementation written after deletion.
- Windows hosting runs on the AWT-owned Canvas thread with no native message loop, dispatch loop, custom WndProc, or auxiliary UI thread.
- Bounds, visibility, focus, traversal, host input, and controller lifecycle use WebView2 controller APIs only; no Win32 operation implements any of those responsibilities.
- Windows native bridge has no `attachToParent`, `detachFromParent`, `setHostWindow`, or `clearFocus` API.
- WebView2 controller is created with `AllowHostInputProcessing(true)`.
- Common Swing code has no OS/controller/native-handle branching for hosting, focus, activation, or shortcut behavior.
- Providers construct one `WebViewController` directly and have no `createBackend`, per-OS `createEngine`, native-peer factory, stub host controller, or default compatibility implementation.
- Platform-specific focus and input differences are expressed inside the selected `WebViewController`.
- Every selected platform implementation is one `WebViewController`, with no platform `*Engine`, `*HostController`, `*Backend`, tuple, or facet-delegate class.
- macOS WKWebView is attached to the host component's native `NSView`, not to `NSWindow.contentView`.
- Windows focus transitions use WebView2 focus events and Swing guarded policy.
- Popup/menu closing works through WebView2 focus events and natural AWT host-input activation, with no page-message or Win32-hook fallback.
- WebView-owned shortcuts and page-registered shortcuts work inside WebView.
- IDE hard-reserved shortcuts still work inside WebView.
- Tests and manual regressions listed above pass.
