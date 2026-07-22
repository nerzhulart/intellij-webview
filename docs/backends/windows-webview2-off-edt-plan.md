# Windows WebView2 Threading Follow-up

The WebView2 backend already runs controller operations on the dedicated `WebView2-Thread` with a blocking Win32 message loop. Bounds, visibility, focus, attachment, and navigation updates are coalesced before dispatch. This plan contains only the remaining threading work.

## Remaining Work

### Focus queue attachment

The WebView2 child HWND belongs to `WebView2-Thread`, while its AWT parent belongs to the EDT. Complete and validate Win32 input-queue attachment for focus transfers:

- capture the EDT Win32 thread ID without confusing it with `Thread.id`;
- attach the EDT and WebView2 input queues after a valid parent HWND is known;
- detach them during teardown and reattachment;
- preserve native typing, IME, shortcut routing, and Swing focus traversal;
- define failure logging and cleanup when either thread or window is already closing.

Audit callbacks that now arrive on `WebView2-Thread`. Any callback touching Swing must dispatch to EDT without blocking the native STA. Asset resolution and RPC enqueueing may remain off EDT when their contracts allow it.

### Native ownership invariant

The Rust bridge uses `Rc<RefCell<NativeWebView>>`, which is sound only when handle access remains on one OS thread. Record the creating thread and add debug assertions to handle access and destruction. Document the invariant next to the raw-handle conversion.

This item also closes the H1 finding in [Windows Native Bridge Review](windows-webview2-rust-review.md).

## Risks

- `AttachThreadInput` changes focus and key-state behavior for both queues; attach/detach order must be idempotent.
- A synchronous hop from a WebView2 callback to EDT can deadlock if EDT is waiting on native completion.
- Window teardown may race with queued focus work; stale HWND and handle operations must be ignored safely.

## Acceptance Criteria

- No WebView2 controller or raw-handle operation runs on EDT.
- Focus moves WebView → Swing and Swing → WebView in both Tab directions.
- Clicking editable page content focuses it without losing subsequent keyboard input.
- IDE shortcuts, browser editing keys, IME, and bare-Shift gestures retain current behavior.
- Host detach/reattach and close leave no attached input queues or posted native work.
- Rust debug builds detect access from a non-owner thread.
- Windows verification covers asset loading, bridge messages, focus, typing, shortcuts, detach/reattach, and teardown through a locally built plugin run.
