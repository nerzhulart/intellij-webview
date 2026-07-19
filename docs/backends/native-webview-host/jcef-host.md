# JCEF WebView Controller

Status: plan.

JCEF is the simplest controller shape because `JBCefBrowser` already exposes a Swing component. The target architecture still uses the same single `WebViewController` as every other platform:

```kotlin
// Kotlin type.
private class JcefWebViewController : WebViewController
```

`JcefWebViewController` owns navigation, JavaScript evaluation, message transport, close, the mounted Swing component, and focus/layout behavior.

## Controller

`JcefWebViewController` is the complete controller for one JCEF WebView. It exposes `WebViewController.component` over the existing `JBCefBrowser.component`.

Host behavior:

- `component` returns the browser Swing component that `SwingWebViewHostPanel` mounts.
- `applyLayout(params)` is minimal because ordinary Swing layout already positions the component.
- Focus entry delegates to `cefBrowser.uiComponent.requestFocusInWindow()` and `cefBrowser.setFocus(true)` when the Swing host initiated entry.
- `swingFocusMovedOutside(...)` may call `cefBrowser.setFocus(false)`.
- Page/native activation is normally a no-op because the browser is already in the normal AWT event path.

## Removed Shape

Do not keep `ComponentBackedWebViewEngine` for JCEF. The JCEF Swing component is state of `JcefWebViewController`; common code receives the one `WebViewController` for both page/runtime work and embedding, and never casts it to a component-backed subtype.
