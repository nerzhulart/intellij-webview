# WebView Focus and Tab Interop

Focus interop makes a WebView panel one Swing tab stop while preserving normal sequential focus inside the page.

## Behavior

- Entering the host forward focuses the first tabbable page element.
- Entering backward focuses the last tabbable page element.
- Tab or Shift+Tab at the corresponding page boundary returns focus to Swing.
- Pointer activation tells the host that focus is inside the page.
- Leaving the WebView sends a page `leave()` notification, blurs transient controls, and dispatches `wvi-focus-leave`.

The internal typed protocol uses the `webview.focus` namespace. Consumer views normally receive it automatically from the bundled runtime.

## Page Integration

Close transient page UI when WebView focus leaves:

```ts
import { addWebViewFocusLeaveListener } from "@nerzhulart/intellij-webview-sdk"

const dispose = addWebViewFocusLeaveListener(() => closeOpenPopup())
```

Components that own native or specialized Tab behavior may mark their boundary with `data-webview-focus-boundary`; the common scanner will not take over traversal inside it.

## Tabbable Elements

The runtime follows standard sequential focus rules for visible enabled controls, links, explicit `tabindex`, editable content, media controls, and open shadow roots. Positive `tabindex` values precede document-order zero values.

Closed shadow roots and iframe documents cannot be scanned by the common runtime. Their components must manage boundary behavior explicitly.

## Diagnostics

Host and page diagnostics use the `[wvi-focus]` prefix. Use them when investigating focus direction, active element selection, pointer activation, or a boundary exit.

Backend-specific native focus remains relevant, especially for cross-thread WebView2 HWNDs. See [Windows WebView2 Threading Follow-up](../backends/windows-webview2-off-edt-plan.md).
