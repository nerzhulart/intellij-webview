# Windows WebView2: what actually flashes on reattach

Measured with `WebViewReattachVisualSmokeTest` (`-Dwebview.smoke.reattach.pauseMs=900
-Dwebview.smoke.reattach.cycles=5`) after the placement synchronization was collapsed into a single
`HostState` snapshot. Frames: `build/reports/webview-flash/run-1787255106668`.

## The previous conclusion was wrong

Earlier handoffs concluded that `put_ParentWindow` and `put_IsVisible` rebuild the DirectComposition
presentation and flash the whole frame. That was measured with confounders (parking under a
different top-level via `GA_ROOTOWNER`, plus live visibility transitions).

Both confounders are gone now, and the flash is unchanged:

- the controller is created once and `put_IsVisible` is never called;
- parking goes into a `WS_VISIBLE`, zero-sized limbo window under the same root owner;
- a configuration was measured where **WebView2 receives no call at all after creation**: the
  controller lives inside a bridge-owned window, and only that plain window travels between the
  Canvas peer and the limbo with `SetParent`/`SetWindowPos`.

`maxChangedFraction` stayed at `0.78`, and the flash is visible by eye in that configuration too.
So the reparent API is **not** the cause.

## What the frames show

`21-45-07-704-after.png`, taken right after a reattach, contains two light-grey (`#EEEEEE`)
rectangles:

1. one exactly over the slot the host just moved into;
2. a canvas-sized "ghost" anchored at the top-left of the frame client area, overlapping unrelated
   Swing content.

Neither is painted by Swing: `SwingWebViewHostPanel` and the engine wrapper are `isOpaque = false`,
and the `Canvas` peer plus the controller default background are both set to `#1E1F22`. Both are the
freshly created AWT `Canvas` peer before anything has painted into it - AWT creates the HWND first
and lays it out afterwards, and the bridge window simply follows it.

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

Two independent things, both needed:

1. **The peer is born empty.** `WinWebViewEngine`'s `Canvas.addNotify` sets the bounds to `0x0` before
   `super.addNotify()` and restores them on the next EDT event, when the containers are laid out and
   the relative bounds map to the right place. A window with no area cannot show anything.
2. **The content is revealed after the layout settles.** `WinWebViewEngine.gateRevealAfterReattach`
   holds the first snapshot on a new parent at `visible = false`, which the native `reconcile`
   expresses as "same size, pushed under the client area": the window is back in the composition tree
   and Chromium lays out and presents into it, but nothing of it is on screen. It is revealed on a
   later sync, once the geometry repeated itself and `REVEAL_SETTLE_DELAY` passed;
   `scheduleRevealRecheck` re-reads the host itself, because Swing is not obliged to send another
   event. A plain hide/show under a live peer is not gated - the surface still holds its frame.

Measured on the full WebView scenario afterwards: `maxChangedFraction=0.01`,
`maxBrightFraction=0.03`, no flash frames saved, and the page keeps its `requestAnimationFrame`
rhythm across every cycle.

## Instrumentation left in place

- `WebViewFlashRecorder` reports both metrics. The bright-pixel one matters: dark content replacing a
  dark background moves a lot of pixels without being visible, so "how much changed" alone reported a
  green `0.01` while the white slab was still there.
- `WebViewGeometryProbe` reports the placement **the page itself observes** - viewport size, on-screen
  position, `visibilityState` and animation-frame gaps - which is how the reveal gate was verified
  (`screen=(834,850)` while gated, `screen=(834,189)` about 120 ms later, size never changing).
- The native side traces `geom.reconcile.before` / `geom.reconcile.after` / `geom.park` with the real
  screen rectangles of the bridge window, the Canvas, the frame and the Chromium widget.

## What is still open

1. There is still a short dark region while the gate is closed. `SwingWebViewHostPanel` has the
   machinery for covering it - `snapshotImage`, `paintComponent`, `setSnapshotImage`,
   `clearSnapshotImage` - but `setSnapshotImage` has no callers.
2. Undocking a tool window into another top-level moves the controller between composition trees;
   that transition is not covered by the gate.
3. Windowless hosting (`ICoreWebView2CompositionController`) does **not** solve the gap by itself:
   it is "no rendered frame yet", not "HWND moved".
