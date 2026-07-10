# Architecture Diagrams

Status: plan.

The diagrams below use full type APIs in nodes and call names on edges. Every owned type node is marked with its source side: `<<Kotlin>>` for Kotlin-only types, `<<KotlinTsRpc>>` for mirrored Kotlin/TS RPC protocol types, `<<Rust>>` for native bridge implementation types, and `<<WebView2COM>>` for WebView2 COM types. JVM/Swing/JDK value types such as `Component`, `KeyEvent`, `String`, or `Boolean` are external library types and are not repeated as nodes. The diagrams describe the target architecture, not the current class layout.

## Creation and ownership

```mermaid
classDiagram
direction LR

class WebViewRuntime {
  <<Kotlin>>
  +suspend createWebView(scope: CoroutineScope, options: WebViewCreationOptions): WebView
  +createEngine(scope: CoroutineScope, engineKind: WebViewEngineKind, jcefNativeBundlePath: Path?): WebViewEngine
  +suspend createWebViewPanel(scope: CoroutineScope, options: WebViewPanelOptions): WebViewPanel
}

class WebViewEngineProvider {
  <<Kotlin>>
  +id: WebViewEngineId
  +displayName: String
  +capabilities: WebViewEngineCapabilities
  +selectionPriority(preference: WebViewEngineKind): Int?
  +suspend availability(): WebViewEngineAvailability
  +availabilityBlocking(): WebViewEngineAvailability
  +createBackend(scope: CoroutineScope, options: WebViewEngineCreationOptions, hostEvents: WebViewHostEventSink): WebViewBackend
}

class WebViewRuntimeEngine {
  <<Kotlin>>
  +suspend loadFile(file: Path)
  +suspend loadAsset(root: WebViewAssetRoot, entry: WebViewAssetPath, query: String?)
  +suspend loadHtml(html: String, baseFile: Path?)
  +suspend evaluateJavaScript(script: String): String?
  +suspend close()
  +suspend transferToJs(rawJson: String)
  +connectMessageBus(receiver: WebViewJsMessageReceiver)
}

class WebViewHostController {
  <<Kotlin>>
  +component: Component
  +editShortcutPolicy: WebViewEditShortcutPolicy
  +applyLayout(params: WebViewHostLayoutParams)
  +swingFocusMovedOutside(event: WebViewSwingFocusExit)
  +handleEditShortcut(event: KeyEvent, command: WebViewEditCommand): Boolean
}

class WebViewHostEventSink {
  <<Kotlin>>
  +handle(event: WebViewHostEvent): Boolean
}

class WebViewHostEvent {
  <<Kotlin>>
  NativeFocusGained
  NativeFocusLost
  Activated(source: WebViewHostActivationSource)
  MoveFocusRequested(direction: WebViewFocusDirection)
}

class WebViewBackend {
  <<Kotlin>>
  +runtimeEngine: WebViewRuntimeEngine
  +hostController: WebViewHostController
}

class CreatedWebViewHost {
  <<Kotlin>>
  +webView: WebView
  +runtimeEngine: WebViewRuntimeEngine
  +hostController: WebViewHostController
  +hostPanel: SwingWebViewHostPanel
  +close(): Unit
}

class WebViewPanel {
  <<Kotlin>>
  +webView: WebView
  +component: JComponent
  +interop: WebViewInterop
  +suspend reload()
  +suspend close()
}

WebViewRuntime --> WebViewEngineProvider : selectProvider(preference, requirements)
WebViewRuntime --> WebViewEngineProvider : createBackend(scope, options, hostEvents)
WebViewEngineProvider --> WebViewBackend : construct facets
WebViewBackend --> WebViewRuntimeEngine : runtimeEngine
WebViewBackend --> WebViewHostController : hostController
WebViewBackend --> WebViewHostEventSink : reports native facts
WebViewRuntime --> WebViewRuntimeEngine : connectMessageBus(receiver)
WebViewRuntime --> CreatedWebViewHost : construct(backend.runtimeEngine, backend.hostController, hostPanel)
CreatedWebViewHost --> WebViewPanel : expose(component = hostPanel)
WebViewPanel --> CreatedWebViewHost : close()
CreatedWebViewHost --> WebViewRuntimeEngine : close()
```

## Runtime transport and page API

```mermaid
classDiagram
direction LR

class WebView {
  <<Kotlin>>
  +runtimeInfo: WebViewRuntimeInfo
  +interop: WebViewInterop
  +suspend loadFile(file: VirtualFile)
  +suspend loadAsset(root: WebViewAssetRoot, entry: WebViewAssetPath, query: String?)
  +suspend loadHtml(html: String)
  +suspend evaluateJavaScript(script: String): WebViewScriptResult
  +suspend close()
}

class WebViewMessageBusImpl {
  <<Kotlin>>
  +interop: WebViewInterop
  +transferFromJs(rawJson: String)
  +suspend transferToJs(rawJson: String)
  +close()
}

class WebViewRuntimeEngine {
  <<Kotlin>>
  +suspend loadFile(file: Path)
  +suspend loadAsset(root: WebViewAssetRoot, entry: WebViewAssetPath, query: String?)
  +suspend loadHtml(html: String, baseFile: Path?)
  +suspend evaluateJavaScript(script: String): String?
  +suspend close()
  +suspend transferToJs(rawJson: String)
  +connectMessageBus(receiver: WebViewJsMessageReceiver)
}

class WebViewJsMessageReceiver {
  <<Kotlin>>
  +transferFromJs(rawJson: String)
}

class WebViewFocusHostApi {
  <<KotlinTsRpc>>
  +activated()
  +exit(params: WebViewFocusExit)
}

class WebViewFocusPageApi {
  <<KotlinTsRpc>>
  +enter(params: WebViewFocusEntry)
  +leave()
}

class WebViewFocusEntry {
  <<KotlinTsRpc>>
  +direction: WebViewFocusDirection
}

class WebViewFocusExit {
  <<KotlinTsRpc>>
  +direction: WebViewFocusDirection
}

class WebViewFocusDirection {
  <<KotlinTsRpc>>
  <<enumeration>>
  FORWARD
  BACKWARD
}

WebView --> WebViewRuntimeEngine : loadFile(path)
WebView --> WebViewRuntimeEngine : loadAsset(root, entry, query)
WebView --> WebViewRuntimeEngine : loadHtml(html, baseFile)
WebView --> WebViewRuntimeEngine : evaluateJavaScript(script)
WebView --> WebViewRuntimeEngine : close()
WebViewMessageBusImpl --> WebViewRuntimeEngine : transferToJs(rawJson)
WebViewRuntimeEngine --> WebViewJsMessageReceiver : transferFromJs(rawJson)
WebViewMessageBusImpl --> WebViewFocusHostApi : implement(ID, hostApi)
WebViewMessageBusImpl --> WebViewFocusPageApi : callable(ID).enter(params)
WebViewMessageBusImpl --> WebViewFocusPageApi : callable(ID).leave()
WebViewFocusPageApi --> WebViewFocusEntry : enter(params)
WebViewFocusHostApi --> WebViewFocusExit : exit(params)
```

This diagram shows the Kotlin/TS RPC transport surface. It does not make page-side focus-boundary messages the universal native focus mechanism. Windows must use WebView2 controller focus and traversal events for the paths they cover; macOS may keep a private runtime-owned boundary detector only until a native AppKit-only traversal path is proven.

## Swing layout and focus policy

```mermaid
classDiagram
direction LR

class SwingWebViewHostPanel {
  <<Kotlin>>
  +component: JComponent
  +guardedFocusState
  +focusTraversalPolicy
  +popupMenuActivationPolicy
  +shortcutDispatchGate
  +layoutStateReader
}

class WebViewHostController {
  <<Kotlin>>
  +component: Component
  +editShortcutPolicy: WebViewEditShortcutPolicy
  +applyLayout(params: WebViewHostLayoutParams)
  +swingFocusMovedOutside(event: WebViewSwingFocusExit)
  +handleEditShortcut(event: KeyEvent, command: WebViewEditCommand): Boolean
}

class WebViewHostEventSink {
  <<Kotlin>>
  +handle(event: WebViewHostEvent): Boolean
}

class WebViewHostEvent {
  <<Kotlin>>
  NativeFocusGained
  NativeFocusLost
  Activated(source: WebViewHostActivationSource)
  MoveFocusRequested(direction: WebViewFocusDirection)
}

class WebViewHostLayoutParams {
  <<Kotlin>>
  +displayable: Boolean
  +showing: Boolean
  +boundsInWindow: Rectangle
  +clippedBoundsInWindow: Rectangle
  +scale: Double
}

class WebViewFocusDirection {
  <<KotlinTsRpc>>
  <<enumeration>>
  FORWARD
  BACKWARD
}

SwingWebViewHostPanel --> WebViewHostController : component
SwingWebViewHostPanel --> WebViewHostLayoutParams : readCurrentLayoutParams()
SwingWebViewHostPanel --> WebViewHostController : applyLayout(params)
SwingWebViewHostPanel --> WebViewHostController : component.requestFocusInWindow()
SwingWebViewHostPanel --> WebViewHostController : swingFocusMovedOutside(event)
SwingWebViewHostPanel --> WebViewHostController : handleEditShortcut(event, command)
SwingWebViewHostPanel --> WebViewHostEventSink : owns callback port
WebViewHostController --> WebViewHostEventSink : handle(event)
WebViewHostEventSink --> WebViewHostEvent : receives event
WebViewHostEventSink --> SwingWebViewHostPanel : guarded state update
SwingWebViewHostPanel --> SwingWebViewHostPanel : exitByTraversal(direction)
SwingWebViewHostPanel --> SwingWebViewHostPanel : focusNext/PreviousComponent(hostPanel)
```

`WebViewHostEventSink` in this diagram means constructor-provided lambdas or an anonymous callback object owned by `SwingWebViewHostPanel` (Kotlin). It is not a second common controller contract. Its only job is to let backend host facets report native focus, host/native activation, optional evidence-driven page pointer fallback events, and native traversal requests back into the guarded Swing host state.

## Platform backend implementations

```mermaid
classDiagram
direction LR

class WebViewBackend {
  <<Kotlin>>
  +runtimeEngine: WebViewRuntimeEngine
  +hostController: WebViewHostController
}

class WebViewRuntimeEngine {
  <<Kotlin>>
  +suspend loadFile(file: Path)
  +suspend loadAsset(root: WebViewAssetRoot, entry: WebViewAssetPath, query: String?)
  +suspend loadHtml(html: String, baseFile: Path?)
  +suspend evaluateJavaScript(script: String): String?
  +suspend close()
  +suspend transferToJs(rawJson: String)
  +connectMessageBus(receiver: WebViewJsMessageReceiver)
}

class WebViewHostController {
  <<Kotlin>>
  +component: Component
  +editShortcutPolicy: WebViewEditShortcutPolicy
  +applyLayout(params: WebViewHostLayoutParams)
  +swingFocusMovedOutside(event: WebViewSwingFocusExit)
  +handleEditShortcut(event: KeyEvent, command: WebViewEditCommand): Boolean
}

class WebViewHostEventSink {
  <<Kotlin>>
  +handle(event: WebViewHostEvent): Boolean
}

class WinWebView2Backend {
  <<Kotlin>>
  +runtimeEngineFacet: WebViewRuntimeEngine
  +hostControllerFacet: WebViewHostController
  -canvas: Canvas
  -hostHwnd: Long
  -webView2Handle: Long
}

class MacWkWebViewBackend {
  <<Kotlin>>
  +runtimeEngineFacet: WebViewRuntimeEngine
  +hostControllerFacet: WebViewHostController
  -hostComponent: Component
  -hostNSView: ID
  -wkWebView: ID
}

class JcefWebViewBackend {
  <<Kotlin>>
  +runtimeEngineFacet: WebViewRuntimeEngine
  +hostControllerFacet: WebViewHostController
  -browserComponent: JComponent
}

WebViewBackend --> WebViewRuntimeEngine : runtimeEngine
WebViewBackend --> WebViewHostController : hostController
WebViewRuntimeEngine <|.. WinWebView2Backend
WebViewHostController <|.. WinWebView2Backend
WebViewRuntimeEngine <|.. MacWkWebViewBackend
WebViewHostController <|.. MacWkWebViewBackend
WebViewRuntimeEngine <|.. JcefWebViewBackend
WebViewHostController <|.. JcefWebViewBackend
WinWebView2Backend --> WebViewBackend : returned as facets
MacWkWebViewBackend --> WebViewBackend : returned as facets
JcefWebViewBackend --> WebViewBackend : returned as facets
WinWebView2Backend --> WebViewHostEventSink : native focus/activation/traversal facts
MacWkWebViewBackend --> WebViewHostEventSink : native focus/activation/traversal facts
JcefWebViewBackend --> WebViewHostEventSink : native focus facts when needed
```

`WinWebView2Backend`, `MacWkWebViewBackend`, and `JcefWebViewBackend` are target implementation examples, not a new public/common hierarchy. A backend can implement both facets directly, as shown above, or return private facet delegates over the same backend-owned native session. Private delegates are intentionally omitted from the diagram because they do not define the architecture. Host-only native operations such as attaching to an HWND/NSView, changing WebView2 bounds, AppKit first-responder cleanup, or JCEF focus calls stay private to the selected backend and never appear on `WebViewRuntimeEngine`.

## Windows native bridge

```mermaid
classDiagram
direction LR

class WinWebView2Backend {
  <<Kotlin>>
  +applyLayout(params: WebViewHostLayoutParams)
  +swingFocusMovedOutside(event: WebViewSwingFocusExit)
  +handleEditShortcut(event: KeyEvent, command: WebViewEditCommand): Boolean
  +suspend loadFile(file: Path)
  +suspend loadAsset(root: WebViewAssetRoot, entry: WebViewAssetPath, query: String?)
  +suspend loadHtml(html: String, baseFile: Path?)
  +suspend evaluateJavaScript(script: String): String?
  +suspend transferToJs(rawJson: String)
  +connectMessageBus(receiver: WebViewJsMessageReceiver)
  +suspend close()
}

class WinWebView2BridgeApi {
  <<Kotlin>>
  +create(hostHwnd: Long, userDataDir: String, documentStartScript: String, callbacks: Callbacks): Long
  +destroy(handle: Long)
  +setBounds(handle: Long, x: Int, y: Int, width: Int, height: Int, scale: Double)
  +setVisible(handle: Long, visible: Boolean)
  +focus(handle: Long)
  +loadUrl(handle: Long, url: String)
  +setVirtualHostNameToFolderMapping(handle: Long, hostName: String, folderPath: String)
  +loadHtml(handle: Long, html: String, baseUrl: String?)
  +evaluateJavaScript(handle: Long, evalId: Long, script: String)
  +callDevToolsProtocolMethod(handle: Long, callId: Long, methodName: String, paramsJson: String)
  +transferToJs(handle: Long, rawJson: String)
}

class Callbacks {
  <<Kotlin>>
  +onCreated(handle: Long)
  +onCreateFailed(message: String)
  +onMessage(raw: String)
  +onAcceleratorKeyPressed(keyEventKind: Int, virtualKey: Int, modifiers: Int, keyEventLParam: Int): Boolean
  +onFocusGained()
  +onFocusLost()
  +onMoveFocusRequested(reason: Int): Boolean
  +onLog(level: Int, message: String)
  +onNativeDiagnostic(level: Int, event: String, message: String, data: String)
  +resolveAsset(url: String): AssetResponse?
}

class RustWebView2Bridge {
  <<Rust>>
  +create(hostHwnd, userDataDir, documentStartScript, callbacks)
  +destroy(handle)
  +setBounds(handle, x, y, width, height, scale)
  +setVisible(handle, visible)
  +focus(handle)
  +loadUrl(handle, url)
  +loadHtml(handle, html, baseUrl)
  +evaluateJavaScript(handle, evalId, script)
  +callDevToolsProtocolMethod(handle, callId, methodName, paramsJson)
  +transferToJs(handle, rawJson)
}

class ICoreWebView2Controller {
  <<WebView2COM>>
  +CreateCoreWebView2ControllerWithOptions(hostHwnd, options, handler)
  +SetBounds(bounds)
  +SetIsVisible(visible)
  +MoveFocus(PROGRAMMATIC)
  +add_GotFocus(handler)
  +add_LostFocus(handler)
  +add_MoveFocusRequested(handler)
  +add_AcceleratorKeyPressed(handler)
}

WinWebView2Backend --> WinWebView2BridgeApi : create(hostHwnd, userDataDir, documentStartScript, callbacks)
WinWebView2Backend --> WinWebView2BridgeApi : setBounds(handle, x, y, width, height, scale)
WinWebView2Backend --> WinWebView2BridgeApi : setVisible(handle, visible)
WinWebView2Backend --> WinWebView2BridgeApi : focus(handle)
WinWebView2Backend --> WinWebView2BridgeApi : loadUrl(handle, url)
WinWebView2Backend --> WinWebView2BridgeApi : loadHtml(handle, html, baseUrl)
WinWebView2Backend --> WinWebView2BridgeApi : evaluateJavaScript(handle, evalId, script)
WinWebView2Backend --> WinWebView2BridgeApi : transferToJs(handle, rawJson)
WinWebView2BridgeApi --> RustWebView2Bridge : JNI native calls
RustWebView2Bridge --> ICoreWebView2Controller : CreateCoreWebView2ControllerWithOptions(hostHwnd, options, handler)
RustWebView2Bridge --> ICoreWebView2Controller : SetBounds(bounds)
RustWebView2Bridge --> ICoreWebView2Controller : SetIsVisible(visible)
RustWebView2Bridge --> ICoreWebView2Controller : MoveFocus(PROGRAMMATIC)
ICoreWebView2Controller --> Callbacks : onFocusGained()
ICoreWebView2Controller --> Callbacks : onFocusLost()
ICoreWebView2Controller --> Callbacks : onMoveFocusRequested(reason)
ICoreWebView2Controller --> Callbacks : onAcceleratorKeyPressed(...)
Callbacks --> WinWebView2Backend : apply callback state
```
