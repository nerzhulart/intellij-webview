# JCEF Host

Status: plan.

JCEF is the simplest backend shape because `JBCefBrowser` already exposes a Swing component. The target architecture should still present it through the same two facets as every other backend:

```kotlin
private class JcefWebViewBackend : WebViewRuntimeEngine, WebViewHostController
```

The runtime facet owns navigation, JavaScript evaluation, message transport, and close. The host facet owns the mounted Swing component and focus/layout behavior.

## Host Facet

`JcefWebViewBackend` exposes `WebViewHostController` over the existing `JBCefBrowser.component`. The same backend object may also be the `WebViewRuntimeEngine`.

Host behavior:

- `component` returns the browser Swing component that `SwingWebViewHostPanel` mounts.
- `applyLayout(params)` is minimal because ordinary Swing layout already positions the component.
- Focus entry delegates to `cefBrowser.uiComponent.requestFocusInWindow()` and `cefBrowser.setFocus(true)` when the Swing host initiated entry.
- `swingFocusMovedOutside(...)` may call `cefBrowser.setFocus(false)`.
- Page/native activation is normally a no-op because the browser is already in the normal AWT event path.

## Removed Shape

Do not keep `ComponentBackedWebViewEngine` for JCEF. A Swing component is host-facet state, not runtime-engine state. Common code should receive `WebViewRuntimeEngine` for page/runtime work and `WebViewHostController` for embedding, and it should never cast the runtime engine to a component-backed type.
