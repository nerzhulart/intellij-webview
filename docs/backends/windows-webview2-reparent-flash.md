# Windows WebView2: what actually flashes on reattach

Measured with `WebViewReattachVisualSmokeTest` (`-Dwebview.smoke.reattach.pauseMs=900
-Dwebview.smoke.reattach.cycles=5`) after the placement synchronization was collapsed into a single
`HostState` snapshot. Frames: `build/reports/webview-flash/run-1787255106668`.

## The previous conclusion was wrong

Earlier handoffs concluded that `put_ParentWindow` and `put_IsVisible` rebuild the DirectComposition
presentation and flash the whole frame. That was measured with confounders (parking under a
different top-level via `GA_ROOTOWNER`, plus live visibility transitions).

Both confounders were gone at the time of that measurement, and the flash was unchanged:

- the controller is created once and `put_IsVisible` was never called;
- parking goes into a `WS_VISIBLE`, zero-sized holder window under the same root owner;
- a configuration was measured where **WebView2 receives no call at all after creation**: the
  controller lived inside a bridge-owned window, and only that plain window travelled between the
  Canvas peer and the parking window with `SetParent`/`SetWindowPos`.

`maxChangedFraction` stayed at `0.78`, and the flash is visible by eye in that configuration too.
So the reparent API is **not** the cause.

That measurement is what later justified deleting the bridge window and moving placement onto
`put_ParentWindow` + `SetBounds`; the reasoning and the full verdict list are in
[Raw Win32 Audit](windows-webview2-raw-winapi-audit.md).

## What the frames show

`21-45-07-704-after.png`, taken right after a reattach, contains two light-grey (`#EEEEEE`)
rectangles:

1. one exactly over the slot the host just moved into;
2. a canvas-sized "ghost" anchored at the top-left of the frame client area, overlapping unrelated
   Swing content.

Neither is painted by Swing: `SwingWebViewHostPanel` and the engine wrapper are `isOpaque = false`,
and the `Canvas` peer plus the controller default background are both set to `#1E1F22`. Both are the
freshly created AWT `Canvas` peer before anything has painted into it - AWT creates the HWND first
and lays it out afterwards, and the browser simply rides along inside it.

## Actual cause: the recreated AWT peer, not WebView2

`HeavyweightCanvasReattachFlashTest` reproduces the very same flash **without a WebView at all** -
no engine, no native bridge, no browser, just a heavyweight `Canvas` moved between two containers
the way a tool window moves its content:

| peer created with | `maxBrightFraction` | `maxChangedFraction` |
|---|---|---|
| the bounds it carried over (plain AWT behaviour) | `0.47` | `0.44` |
| empty, bounds restored after layout | `0.03` (window title bar only) | `0.00` |

The saved `canvas-plain-*` bright frames under `build/reports/webview-flash` show the familiar
`#EEEEEE` slab.

The mechanism: on `addNotify` AWT creates the HWND with the bounds the component still carries from
its previous parent, and that HWND is a child of the **frame**, so it materialises at `(0, 0)` of the
client area at full size. Nothing paints it until the enclosing containers are laid out one EDT event
later - that is the white rectangle over the whole window, and the browser is merely its passenger.

## The fix

Three independent things, all needed:

1. **The peer is born empty.** `WinWebViewEngine`'s `Canvas.addNotify` sets the bounds to `0x0` before
   `super.addNotify()` and restores them on the next EDT event, when the containers are laid out and
   the relative bounds map to the right place. A window with no area cannot show anything.
2. **The content is revealed after the layout settles.** A host with no area reads as
   `visible = false` in `WinWebViewEngine.readHostState`, so the reattached controller is hidden
   through `put_IsVisible` until the layout that gives the Canvas its real size. The native
   `reconcile` orders the calls "hide, reparent, resize, show", so the page is never presented at a
   size or position the host no longer has, and the reveal happens exactly once. There is no timer:
   the layout event is the gate.
3. **The Canvas reports its own geometry.** The controller is a child window of the `Canvas`, so the
   `Canvas` rectangle - not the one of `SwingWebViewHostPanel` - is what the page is clipped to, and
   the layout pass that finally sizes it produces no event the host panel listens to. `Canvas.reshape`
   therefore synchronizes the snapshot itself; without it the last thing the native side ever hears is
   the empty snapshot from `addNotify`.

Two ordering traps live in the same place:

- The layout pass of point 1 must be requested from the next EDT event, never from `addNotify`.
  `Container.addImpl` calls `addNotify` **before** it hands the constraint to the layout manager, so a
  `revalidate()` from there lays the container out as if the host had no place in it - and, worse, it
  leaves the whole tree valid, which swallows the `revalidate()` the caller does right after
  `add(...)`. In a live IDE the resizes that follow hide it; in a fixed-size test frame the host stays
  `0x0` forever, which is how `WebViewFocusInteropRobotTest` caught it.
  `WinWebViewEngineTest.canvasLaidOutAfterItsPeerWasCreatedReachesTheController` is the cheap guard:
  it fails the moment the deferred layout pass is gone.
- `Canvas.reshape` must skip the snapshot while the component is not displayable. The `setBounds` of
  point 1 runs before `super.addNotify()`, so there is no HWND yet, and a snapshot without one reads
  as "the peer is gone" - it would park a controller that never left the holder window.

Measured on the full WebView scenario afterwards: `maxChangedFraction=0.01`,
`maxBrightFraction=0.03`, no flash frames saved, and the page keeps its `requestAnimationFrame`
rhythm across every cycle.

## Instrumentation left in place

- `WebViewFlashRecorder` reports both metrics. The bright-pixel one matters: dark content replacing a
  dark background moves a lot of pixels without being visible, so "how much changed" alone reported a
  green `0.01` while the white slab was still there.
- `WebViewGeometryProbe` reports the placement **the page itself observes** - viewport size, on-screen
  position, `visibilityState` and animation-frame gaps. With honest visibility a hidden host reports
  `visibilityState=hidden` and stops receiving animation frames, which is the point: that is what
  lets Chromium freeze the renderer of a tool window nobody looks at.
- The native side traces `placement.reconcile` and `placement.park` - the Canvas rectangles, the
  frame, and the bounds WebView2 itself reports - behind `WEBVIEW_WIN_PAINT_TRACE=1`.

## What is still open

1. Between the reattach and the first frame of the shown controller there is still a short dark
   region. `SwingWebViewHostPanel` has the machinery for covering it - `snapshotImage`,
   `paintComponent`, `setSnapshotImage`, `clearSnapshotImage` - but `setSnapshotImage` has no
   callers.
2. Undocking a tool window into another top-level moves the controller between composition trees;
   that transition is only covered by the same hide/show ordering, not by anything specific.
3. Windowless hosting (`ICoreWebView2CompositionController`) does **not** solve the gap by itself:
   it is "no rendered frame yet", not "HWND moved".

## Re-measured after the raw Win32 cleanup

Placement has since moved onto `put_ParentWindow` + `SetBounds`
([Raw Win32 Audit](windows-webview2-raw-winapi-audit.md)) and the measurement was repeated with the
same parameters: `maxChangedFraction=0.01`, `maxBrightFraction=0.03`, no flash frames saved - the
numbers above are unchanged, confirming once more that the reparent API is not the cause.
