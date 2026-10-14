# Windows WebView2 Composition Hosting Plan

## Purpose

This plan introduces an opt-in `ICoreWebView2CompositionController` presentation path alongside
the existing windowed WebView2 controller. The first milestone is deliberately a minimal proof of
concept: prove with recorded frames that a DirectComposition visual clip contains an intentionally
oversized WebView surface inside the current Canvas bounds.

Do not begin input parity, accessibility, drag-and-drop, or production rollout until that clipping
gate passes. Composition hosting is useful here only if it fixes the observed right/bottom overflow
during top/left shrink.

The target audience is contributors maintaining `native/WinWebView2Bridge` and its Windows smoke
tests.

## Current evidence

The current hierarchy is structurally correct:

```text
Swing
  -> SunAwtCanvas
      -> IJWebView2ClipHost
          -> Chrome_WidgetWin_0
              -> Chrome_WidgetWin_1
                  -> Intermediate D3D Window / Chrome_RenderWidgetHostHWND
```

Runtime inspection nevertheless disproved the hard-clip assumption:

- shrinking only `IJWebView2ClipHost` to `200x400` while Chromium remained `418x891` did not
  visually crop the WebView;
- applying an explicit `HRGN` to the clip host, Chrome windows, `Intermediate D3D Window`, and
  `Chrome_RenderWidgetHostHWND` did not crop the composed frame;
- during automated top/left shrink, the observed order was Canvas, clip host, Chrome, then D3D;
  the D3D surface could remain one old size for roughly one frame after the Canvas and clip host
  had already shrunk;
- the current `apply_clip_geometry` shrink-first ordering therefore executes as intended, but an
  HWND parent or region does not constrain this WebView2 DirectComposition surface.

The intermediate bridge-owned HWND can still be useful as a stable WebView2 parent, parking unit,
DirectComposition target, and native input sink. It must not be described as a hard visual clip in
the windowed implementation.

## Goals

- Keep the current windowed controller implementation available and unchanged by default.
- Add a composition presentation path in the same DLL and shared WebView2 environment.
- Preserve the Kotlin/JNI API during the initial process-wide experiment.
- Put the effective clip in the DirectComposition visual tree, not in an HWND region.
- Keep browser logic shared: navigation, settings, JavaScript, RPC, assets, diagnostics, and
  accelerator callbacks must not be duplicated.
- Preserve the existing AWT-Windows command transport. No WebView2, DirectComposition, or raw HWND
  operation may move to Swing EDT.
- Make every rollout stage reversible by selecting the existing windowed path.

## Non-goals for the first proof of concept

- Mouse, touch, pen, cursor, or drag-and-drop parity.
- Accessibility or UI Automation integration.
- Production fallback or automatic mode selection.
- ARM64 packaging.
- Device-loss recovery.
- Floating-window, parking, or Canvas-peer recreation parity.
- Replacing the existing windowed implementation.
- Solving the separate first-frame gap on attach or reattach.

## Target architecture

The browser-facing state remains common while presentation-specific behavior is explicit:

```rust
enum PresentationMode {
    Windowed,
    CompositionPoc,
    Composition,
}

enum PresentationHost {
    Windowed(WindowedHost),
    Composition(CompositionHost),
}

struct NativeWebView {
    controller: Option<ICoreWebView2Controller>,
    webview: Option<ICoreWebView2>,
    presentation: PresentationHost,
    // Existing shared lifecycle, callbacks, scripts, assets, and diagnostics.
}
```

Prefer an enum over a trait object. The small closed set of implementations benefits from explicit
COM ownership and exhaustive lifecycle handling.

The shared controller initialization must converge after the asynchronous creation callback:

```text
Windowed:
  CreateCoreWebView2ControllerWithOptions
    -> ICoreWebView2Controller
    -> common finish_create

Composition:
  CreateCoreWebView2CompositionControllerWithOptions
    -> ICoreWebView2CompositionController
    -> QueryInterface(ICoreWebView2Controller)
    -> set RootVisualTarget
    -> common finish_create
```

The composition visual hierarchy should be owned by the bridge:

```text
IJWebView2Surface HWND
  -> IDCompositionTarget
      -> container visual with rectangular clip
          -> WebView2 root-target visual
              -> WebView2 content
```

The existing per-view clip-host HWND should initially be repurposed as
`IJWebView2Surface`: a stable child of the Canvas, the DirectComposition target HWND, and later the
input window. There must be no visible Chromium child HWND responsible for presentation in the
composition path.

## Phase 1: minimal clipping proof of concept

### Question to answer

Does a rectangular clip on an application-owned DirectComposition visual prevent an intentionally
oversized composition-hosted WebView2 surface from drawing over the right Swing control and the
bottom panel?

No other result counts as success for this phase.

### Mode selection

Add one process-wide, opt-in environment variable:

```text
WEBVIEW_WIN_PRESENTATION=windowed
WEBVIEW_WIN_PRESENTATION=composition-poc
```

Rules:

- missing or invalid values select `windowed` and emit one warning for an invalid value;
- `windowed` remains the default;
- explicit `composition-poc` failure must fail WebView creation with a precise native diagnostic;
- do not silently fall back to `windowed`, because that would invalidate the experiment;
- read the mode once per process before the first controller is created.

### Minimal native resources

For one x64 WebView, create and retain:

- a D3D11 device with BGRA support;
- its DXGI device interface;
- an `IDCompositionDevice`;
- an `IDCompositionTarget` for `IJWebView2Surface`;
- a container/root visual;
- a WebView2 root-target visual;
- an `IDCompositionRectangleClip`;
- `ICoreWebView2CompositionController` plus its normal `ICoreWebView2Controller` interface;
- the composition-controller completion handler.

Add only the required Windows crate features, expected to include Direct3D 11, DXGI,
DirectComposition, and their shared types. Keep resource ownership per view in the PoC. Sharing a
device across views is a production optimization and must not complicate the clipping experiment.

### Creation sequence

Run the complete sequence on AWT-Windows using the existing hook transport:

1. Resolve the current Canvas and create or reuse its bridge-owned surface HWND.
2. Create the D3D11, DXGI, and DirectComposition objects.
3. Create the target and visual hierarchy for the surface HWND.
4. Cast the shared environment to `ICoreWebView2Environment10` and call
   `CreateCoreWebView2CompositionControllerWithOptions`. If the options path is unavailable, cast
   to `ICoreWebView2Environment3` and use `CreateCoreWebView2CompositionController` only if the
   current application behavior does not depend on a missing option.
5. In the completion callback, retain the composition controller and obtain its ordinary
   `ICoreWebView2Controller` interface.
6. Attach the WebView2 root visual with `SetRootVisualTarget`.
7. Install the rectangle clip on the application-owned container visual and commit the
   DirectComposition device.
8. Reuse the existing common `finish_create` path for settings, scripts, assets, navigation,
   messages, diagnostics, background color, and initial URL.

Do not hold a `RefCell` borrow during Win32, DirectComposition, or WebView2 COM calls.

### Deterministic forced-mismatch test

The PoC must make stale-size behavior permanent instead of hoping to capture a short race:

1. Start with a WebView controller viewport large enough to contain recognizable content.
2. Keep `controller.Bounds` at the original large size.
3. Set only the application-owned DirectComposition clip to `200x400`.
4. Commit the composition tree.
5. Record a frame after composition has settled.

Expected result:

- WebView pixels are visible only inside the `200x400` clip;
- the remaining Canvas area shows the configured host background;
- no WebView pixels appear over the adjacent Swing/WebView panel or below the bottom boundary;
- the controller must still report the deliberately larger bounds, proving the result came from
  the visual clip rather than a completed WebView resize.

If the full WebView remains visible, stop the project at this gate. Do not implement input or
production abstractions around a presentation path that cannot enforce the required boundary.

### Dynamic top/left shrink test

After the forced mismatch passes:

1. Let the visual clip follow the current `GetClientRect(Canvas)` on each observed Canvas
   `WM_SIZE`.
2. On shrink, update the visual clip and call `IDCompositionDevice::Commit` before
   `controller.SetBounds`.
3. Add a PoC-only option that intentionally leaves controller bounds at the maximum observed size
   during shrink. This simulates an indefinitely stale WebView2 surface.
4. Rapidly and slowly resize the top-level window from the left and top edges.
5. Exercise the nested splitter divider as a separate geometry source.
6. Record the window at a minimum of 60 frames per second while also recording the Canvas screen
   rectangle, visual clip, controller bounds, and commit result for each geometry generation.

Use a high-contrast test page and sibling panels so WebView pixels outside the permitted rectangle
are unambiguous. Do not treat successful HRESULTs as visual proof.

### PoC diagnostics

Behind an opt-in trace flag, record:

- selected presentation mode;
- Canvas HWND and client/screen rectangle;
- surface HWND and parent;
- controller bounds;
- visual clip rectangle;
- geometry generation;
- DirectComposition commit HRESULT and duration;
- thread ID for creation, geometry, callback, and destruction;
- whether forced stale controller bounds are enabled.

Warnings are reserved for real errors. Do not log every `WM_SIZE` at WARN level.

### PoC acceptance gate

The PoC passes only when all of the following are true:

- the forced `200x400` mismatch is visibly clipped in a recorded settled frame;
- controller bounds are demonstrably larger than the clip in that frame;
- recorded top/left shrink frames contain no WebView pixels to the right or below the current
  Canvas boundary;
- splitter shrink produces the same result;
- the windowed default still starts and behaves as before;
- x64 `cargo fmt --check`, `cargo check`, focused Rust tests, `git diff --check`, and the relevant
  plugin build pass;
- the x64 DLL copied into the sandbox is byte-identical to the DLL that was built and tested.

Successful creation, `SetRootVisualTarget`, `Commit`, or `SetBounds` calls alone do not satisfy the
gate.

### PoC failure outcomes

Record one of these explicit outcomes:

- `PASS`: visual clip contains both permanent forced mismatch and live shrink;
- `FAIL_RENDER`: composition controller or visual target does not render in the AWT child target;
- `FAIL_CLIP`: composition renders but escapes the application-owned visual clip;
- `FAIL_SYNC`: static forced mismatch clips, but live clip updates still expose an out-of-bounds
  frame;
- `BLOCKED_RUNTIME`: required WebView2 or Windows graphics interfaces are unavailable.

For `FAIL_SYNC`, evaluate `WaitForCommitCompletion` only as a measured PoC experiment. Do not add a
blocking compositor wait to production without latency, deadlock, and interactive-resize evidence.

## Phase 2: parallel presentation implementation

Begin this phase only after Phase 1 is `PASS`.

### Isolate presentation behavior

Introduce explicit windowed and composition state while leaving all browser behavior common.
Presentation-specific operations should cover:

- asynchronous controller creation;
- attach and parent changes;
- geometry and visual clip;
- controller visibility;
- parking;
- native input dispatch;
- composition resource reset;
- destruction.

Avoid a fork of the full `NativeWebView` implementation. In particular, do not duplicate
`finish_create`, navigation handlers, WebResourceRequested handling, document-start scripts, or
Java callbacks.

### Windowed path

- Preserve current controller creation and behavior.
- Keep it as the default and rollback path.
- Correct comments and documentation that call the intermediate HWND a hard clip.
- Retain the stable parent/parking behavior only where it still has demonstrated value.

### Composition path

- Promote PoC resources into `CompositionHost` with explicit ownership.
- Normalize the composition callback into the same common controller initialization.
- Make visual clip and bounds generation-aware.
- Decide, based on measurements, whether the D3D/DirectComposition device is per-view or shared on
  AWT-Windows. Targets, visuals, clips, cursor state, and input state remain per-view.
- Keep explicit composition mode fail-fast until parity is proven.

## Phase 3: native input and focus parity

In composition mode, `IJWebView2Surface` becomes the native input HWND. Its window procedure runs
on AWT-Windows because the surface is created on that thread through the existing
`WH_GETMESSAGE`/`PostThreadMessageW` transport.

### Mouse and pointer input

Translate and forward:

- move, left/right/middle/X button down/up/double-click;
- vertical and horizontal wheel messages;
- modifier/button virtual-key state;
- client coordinates in physical pixels;
- mouse capture and release for drag selection;
- mouse leave tracking;
- pointer events for touch and pen using `ICoreWebView2PointerInfo`.

Forward through `SendMouseInput` and `SendPointerInput`. Do not round-trip raw input through Swing
EDT. Avoid double delivery of mouse events synthesized from touch.

Before forwarding, validate the point against the current Canvas/visual clip, not merely the
possibly stale surface HWND rectangle.

### Cursor

- Subscribe to composition-controller cursor changes.
- Apply the reported cursor in `WM_SETCURSOR`.
- Remove the handler before releasing the composition controller.

### Focus, keyboard, and IME

- Focus the surface/parent HWND and call the existing `MoveFocus` path.
- Retain the existing accelerator callback and Swing shortcut policy.
- Do not synthesize normal browser key input from Java; composition controller has explicit mouse
  and pointer injection APIs but no general `SendKeyInput` equivalent.
- Verify typing, selection, dead keys, AltGr, IME composition, Tab traversal, bare Shift gestures,
  and IDE accelerators.

## Phase 4: drag-and-drop and accessibility

### Drag-and-drop

- Register the composition surface for OLE drag-and-drop.
- Forward `DragEnter`, `DragOver`, `DragLeave`, and `Drop` through
  `ICoreWebView2CompositionController3`.
- Preserve effects, modifier state, coordinates, and COM object lifetimes.
- Revoke registration before surface destruction or parking where required.

### UI Automation

- Obtain `AutomationProvider` from `ICoreWebView2CompositionController2`.
- Return the provider through the correct `WM_GETOBJECT` path.
- Verify screen-reader navigation, focus reporting, and automation bounds after resize and
  reattach.

Do not declare input parity without both keyboard/IME and accessibility checks.

## Phase 5: lifecycle and graphics hardening

### Attach and visibility

- Attach or reparent the surface beneath the current Canvas.
- Set controller bounds and visual clip before showing the controller.
- Commit the visual tree before reporting the view ready where visual state is required.
- Keep honest WebView2 visibility behavior for hidden Swing hosts.

### Parking and reattach

Parking order:

1. hide the controller;
2. set the visual clip to `0x0` and commit;
3. detach or neutralize the root visual if necessary;
4. move the stable surface HWND to the existing holder;
5. complete the synchronous park barrier before AWT destroys the Canvas peer.

Reattach order:

1. move the surface under the new Canvas;
2. recreate the DirectComposition target/visual tree if the target does not safely survive
   reparenting or a top-level-window transition;
3. restore the root visual target;
4. apply current bounds and clip;
5. commit;
6. show the controller.

Test same-frame reattach and movement between different floating top-level windows separately.

### Device loss

- Detect D3D device removal and DirectComposition commit failures.
- Recreate D3D/DXGI/DirectComposition resources and rebind `RootVisualTarget` where supported.
- Preserve the existing `CoreWebView2` instance when only presentation resources are lost.
- Emit bounded diagnostics with HRESULT and device-removal reason.

### Destruction

The intended order is:

1. stop accepting native input;
2. remove cursor, drag-and-drop, and accessibility integration;
3. clear `RootVisualTarget`;
4. release WebView2 handlers and controllers;
5. clear the DirectComposition target root;
6. release clip, visuals, target, and device references;
7. destroy only the bridge-owned surface HWND;
8. leave the AWT-owned Canvas untouched.

As elsewhere in the bridge, snapshot COM and HWND resources before teardown calls and hold no
`RefCell` borrow across those calls.

## Phase 6: selection and rollout

### Initial selection

Keep the process-wide environment variable through development:

```text
WEBVIEW_WIN_PRESENTATION=windowed
WEBVIEW_WIN_PRESENTATION=composition
```

This keeps the JNI ABI unchanged and allows a full application restart to provide a clean A/B
comparison.

### Per-view A/B mode

Only after the composition path is stable, optionally add a presentation mode to native creation
so one demo window can host windowed and composition views side by side. This requires:

- a Kotlin configuration value;
- a new `createNative` argument;
- a native/Kotlin ABI sentinel update;
- tests proving both controller types can share one WebView2 environment.

Do not expose this as a public application API until the long-term support policy is decided.

### Automatic mode

An eventual `auto` mode may select composition only when all required interfaces and graphics
resources are available. Automatic fallback must emit a diagnostic and clearly state that the
windowed fallback does not provide the same clipping guarantee.

Do not add `auto` while composition failures are still under active investigation; fail-fast is
more useful during development.

## Verification matrix

### Visual clipping

- static forced controller/clip mismatch;
- fast and slow left-edge shrink;
- fast and slow top-edge shrink;
- top-left corner shrink;
- nested horizontal and vertical splitter shrink;
- nearly zero width and height followed by growth;
- adjacent right control and bottom panel with high-contrast colors;
- normal DPI and mixed-DPI monitors.

### Input

- click, double-click, context click, middle and X buttons;
- vertical/horizontal/high-resolution wheel;
- drag selection inside and outside the surface;
- capture loss;
- touch and pen where hardware is available;
- cursor changes over text, links, resize cursors, and page-defined cursors;
- keyboard editing, dead keys, AltGr, IME, Tab, Shift gestures, and IDE shortcuts;
- OLE drag-and-drop;
- UI Automation and screen reader focus.

### Lifecycle

- hidden/showing hosts;
- divider resize;
- Canvas peer destroy/recreate;
- detach/reattach;
- floating tool-window creation, movement, and close;
- movement between monitors and DPI changes;
- browser process failure;
- simulated graphics-device loss where practical;
- repeated create/destroy without HWND, COM, hook, or GPU-resource leaks.

### Build and packaging

After production code begins, run at least:

```powershell
cargo fmt --manifest-path native/WinWebView2Bridge/Cargo.toml --check
cargo check --manifest-path native/WinWebView2Bridge/Cargo.toml --target x86_64-pc-windows-msvc
cargo test --manifest-path native/WinWebView2Bridge/Cargo.toml --target x86_64-pc-windows-msvc
pwsh -File native/WinWebView2Bridge/build.ps1 -All
./gradlew buildPlugin
git diff --check
```

Rebuild and copy x64 and ARM64 DLLs only after the implementation reaches the production phases.
The Phase 1 PoC intentionally validates x64 first and must not replace the shipping ARM64 DLL.

## Documentation updates required after the PoC

If the PoC passes:

- document the two presentation modes in `windows-webview2-runtime.md`;
- describe the visual tree, input ownership, and fallback behavior;
- update the reparent/flash document so composition hosting is not presented as a solution to the
  unrelated first-frame gap;
- remove the disproved claim that a bridge-owned HWND is a synchronous hard clip for a windowed
  WebView2 DirectComposition surface;
- record the final visual evidence and test environment.

If the PoC fails, document the failure outcome and remove the composition experiment without
disturbing the current default implementation.

## Definition of done

Composition hosting may become the default only when:

- DirectComposition clipping is proven by recorded forced-mismatch and live-resize frames;
- no right/bottom overflow is observed in the complete visual matrix;
- mouse, pointer, cursor, keyboard, IME, drag-and-drop, and accessibility reach parity;
- parking, reattach, floating windows, DPI transitions, and destruction are stable;
- device-loss behavior is defined and tested;
- both x64 and ARM64 artifacts build and load;
- the windowed path remains available as an explicit rollback mode;
- runtime documentation describes the actual guarantees and limitations of both modes.

