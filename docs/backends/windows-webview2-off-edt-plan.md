# Windows WebView2 Threading Follow-up

There is no `WebView2-Thread`. Everything WebView2 runs on **AWT-Windows**, the thread that already
owns the `Canvas` HWND, so the controller and its parent window live on the same input queue by
construction.

## How commands get there

A process-wide `WH_GETMESSAGE` hook is installed on the AWT-Windows thread. Kotlin enqueues a typed
`NativeCommand` and wakes the thread with `PostThreadMessageW`; the hook drains the queue in message
order. The one place that needs to be synchronous - parking the controller before JBR destroys the
Canvas peer - sends a barrier message to the bridge's own holder window, whose `wndproc` drains the
queue before returning.

This is a deliberate choice, re-confirmed by
[Raw Win32 Audit](windows-webview2-raw-winapi-audit.md): a message-only window would need its own
window on that thread anyway, and a dedicated STA thread would bring back a cross-thread parent HWND
and `AttachThreadInput`.

## Closed by the current design

- **Focus queue attachment.** Not needed: the controller HWND and its AWT parent belong to the same
  thread, so no `AttachThreadInput` is involved. Focus is `SetFocus` plus the controller's own
  `MoveFocus`, and Tab traversal in both directions is covered by the runtime smoke tests.
- **Callbacks off EDT.** WebView2 callbacks arrive on AWT-Windows, not on a private STA, so the
  "dispatch to EDT without blocking the native STA" problem does not arise.

## Remaining Work

### Native ownership invariant

The Rust bridge uses `Rc<RefCell<NativeWebView>>`, which is sound only while handle access stays on
one OS thread. That holds today because every command is executed from the drain on AWT-Windows, but
nothing enforces it. Record the owning thread in `NativeWebView` and add debug assertions to handle
access and destruction, and document the invariant next to the raw-handle conversion.

This item also closes the H1 finding in [Windows Native Bridge Review](windows-webview2-rust-review.md).

## Acceptance Criteria

- No WebView2 controller or raw-handle operation runs on EDT.
- Focus moves WebView to Swing and Swing to WebView in both Tab directions.
- Clicking editable page content focuses it without losing subsequent keyboard input.
- IDE shortcuts, browser editing keys, IME, and bare-Shift gestures retain current behavior.
- Host detach/reattach and close leave no posted native work and no leaked HWNDs.
- Rust debug builds detect access from a non-owner thread.
- Windows verification covers asset loading, bridge messages, focus, typing, shortcuts,
  detach/reattach, and teardown through a locally built plugin run.
