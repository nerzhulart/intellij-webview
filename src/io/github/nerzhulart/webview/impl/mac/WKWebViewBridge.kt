// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.mac

import com.intellij.util.system.CpuArch
import io.github.nerzhulart.webview.impl.WEBVIEW_ASSET_CUSTOM_SCHEME
import io.github.nerzhulart.webview.impl.WebViewAssetResponse
import io.github.nerzhulart.webview.impl.WebViewEditCommand
import io.github.nerzhulart.webview.impl.WebViewLogger
import io.github.nerzhulart.webview.impl.engine.WebViewScript
import com.sun.jna.Callback
import com.sun.jna.Memory
import com.sun.jna.Pointer
import org.intellij.lang.annotations.Language
import org.jetbrains.annotations.ApiStatus
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent

/**
 * Low-level JNA bridge to macOS `WKWebView` via the local Objective-C runtime boundary.
 *
 * All methods in this object **must** be called on the macOS main thread.
 * The caller (typically [MacWebViewEngine]) is responsible for dispatching via
 * [io.github.nerzhulart.webview.impl.MacMainThreadDispatcher].
 *
 * Uses the system Objective-C runtime directly and does not depend on IntelliJ's Foundation wrapper.
 */
@ApiStatus.Internal
@Suppress("JSUnresolvedVariable")
internal object WKWebViewBridge {
  private fun getObjcClass(name: String): ID = MacObjectiveC.getObjcClass(name)
  private fun getProtocol(name: String): ID = MacObjectiveC.getProtocol(name)
  private fun invoke(receiver: ID, selector: String, vararg arguments: Any?): ID =
    MacObjectiveC.invoke(receiver, selector, *arguments)
  private fun isNil(id: ID?): Boolean = MacObjectiveC.isNil(id)
  private fun nsString(value: String?): ID = MacObjectiveC.nsString(value)
  private fun toStringViaUTF8(value: ID): String? = MacObjectiveC.toStringViaUTF8(value)
  private fun allocateObjcClassPair(superclass: ID, name: String): ID =
    MacObjectiveC.allocateObjcClassPair(superclass, name)
  private fun registerObjcClassPair(cls: ID) = MacObjectiveC.registerObjcClassPair(cls)
  private fun addProtocol(cls: ID, protocol: ID) = MacObjectiveC.addProtocol(cls, protocol)
  private fun addMethod(cls: ID, selector: String, callback: Callback, encoding: String) =
    MacObjectiveC.addMethod(cls, selector, callback, encoding)

  // region ObjC class names
  private const val CLS_WKWEBVIEW = "WKWebView"
  private const val CLS_WKWEBVIEW_CONFIGURATION = "WKWebViewConfiguration"
  private const val CLS_NSURL = "NSURL"
  private const val CLS_NSURLREQUEST = "NSURLRequest"
  private const val CLS_NSHTTPURL_RESPONSE = "NSHTTPURLResponse"
  private const val CLS_NSDATA = "NSData"
  private const val CLS_NSMUTABLE_DICTIONARY = "NSMutableDictionary"
  private const val CLS_NSOBJECT = "NSObject"
  private const val CLS_NSAPPLICATION = "NSApplication"
  private const val CLS_NSVIEW = "NSView"
  private const val CLS_NSGESTURE_RECOGNIZER = "NSGestureRecognizer"
  private const val CLS_WKUSER_SCRIPT = "WKUserScript"
  // endregion

  // region ObjC selectors (centralized, no scattered magic strings)
  private const val SEL_ALLOC = "alloc"
  private const val SEL_INIT = "init"
  private const val SEL_RELEASE = "release"

  // WKWebViewConfiguration
  private const val SEL_PREFERENCES = "preferences"
  private const val SEL_USER_CONTENT_CONTROLLER = "userContentController"
  private const val SEL_SET_URL_SCHEME_HANDLER_FOR_URL_SCHEME = "setURLSchemeHandler:forURLScheme:"

  // WKPreferences
  private const val SEL_SET_JAVA_SCRIPT_ENABLED = "setJavaScriptEnabled:"
  private const val SEL_SET_JAVA_SCRIPT_CAN_OPEN_WINDOWS_AUTOMATICALLY = "setJavaScriptCanOpenWindowsAutomatically:"

  // WKWebView
  private const val SEL_INIT_WITH_FRAME_CONFIGURATION = "initWithFrame:configuration:"
  private const val SEL_ACCEPTS_FIRST_MOUSE = "acceptsFirstMouse:"
  private const val SEL_FLAGS_CHANGED = "flagsChanged:"
  private const val SEL_LOAD_REQUEST = "loadRequest:"
  private const val SEL_LOAD_HTML_STRING_BASE_URL = "loadHTMLString:baseURL:"
  private const val SEL_EVALUATE_JAVASCRIPT = "evaluateJavaScript:completionHandler:"
  private const val SEL_WINDOW = "window"
  private const val SEL_SET_FRAME = "setFrame:"
  private const val SEL_SET_HIDDEN = "setHidden:"
  private const val SEL_SET_AUTORESIZING_MASK = "setAutoresizingMask:"
  private const val SEL_SET_ALLOWS_BACK_FORWARD_NAVIGATION_GESTURES = "setAllowsBackForwardNavigationGestures:"
  private const val SEL_SET_ALLOWS_MAGNIFICATION = "setAllowsMagnification:"
  private const val SEL_SET_PAGE_ZOOM = "setPageZoom:"
  private const val SEL_SET_INSPECTABLE = "setInspectable:"
  private const val SEL_SET_CAN_USE_CREDENTIAL_STORAGE = "_setCanUseCredentialStorage:"
  private const val SEL_SET_RUBBER_BANDING_ENABLED = "_setRubberBandingEnabled:"
  private const val SEL_SET_UI_DELEGATE = "setUIDelegate:"
  private const val SEL_REMOVE_FROM_SUPERVIEW = "removeFromSuperview"
  private const val SEL_COPY = "copy:"
  private const val SEL_PASTE = "paste:"
  private const val SEL_CUT = "cut:"
  private const val SEL_SELECT_ALL = "selectAll:"
  private const val SEL_UNDO = "undo:"
  private const val SEL_REDO = "redo:"

  // NSWindow
  private const val SEL_FIRST_RESPONDER = "firstResponder"
  private const val SEL_MAKE_FIRST_RESPONDER = "makeFirstResponder:"

  // NSApplication
  private const val SEL_SHARED_APPLICATION = "sharedApplication"
  private const val SEL_SEND_ACTION_TO_FROM = "sendAction:to:from:"

  // NSView
  private const val SEL_INIT_WITH_FRAME = "initWithFrame:"
  private const val SEL_ADD_SUBVIEW = "addSubview:"
  private const val SEL_IS_DESCENDANT_OF = "isDescendantOf:"
  private const val SEL_SET_WANTS_LAYER = "setWantsLayer:"
  private const val SEL_LAYER = "layer"
  private const val SEL_SET_MASKS_TO_BOUNDS = "setMasksToBounds:"
  private const val SEL_ADD_GESTURE_RECOGNIZER = "addGestureRecognizer:"
  private const val SEL_REMOVE_GESTURE_RECOGNIZER = "removeGestureRecognizer:"

  // NSGestureRecognizer
  private const val SEL_SET_DELAYS_PRIMARY_MOUSE_BUTTON_EVENTS = "setDelaysPrimaryMouseButtonEvents:"
  private const val SEL_SET_DELAYS_SECONDARY_MOUSE_BUTTON_EVENTS = "setDelaysSecondaryMouseButtonEvents:"
  private const val SEL_SET_DELAYS_OTHER_MOUSE_BUTTON_EVENTS = "setDelaysOtherMouseButtonEvents:"
  private const val SEL_SET_STATE = "setState:"
  private const val SEL_MOUSE_DOWN = "mouseDown:"
  private const val SEL_RIGHT_MOUSE_DOWN = "rightMouseDown:"
  private const val SEL_OTHER_MOUSE_DOWN = "otherMouseDown:"

  // NSObject
  private const val SEL_RESPONDS_TO_SELECTOR = "respondsToSelector:"
  private const val SEL_DESCRIPTION = "description"

  // NSURL / NSURLRequest
  private const val SEL_URL_WITH_STRING = "URLWithString:"
  private const val SEL_REQUEST_WITH_URL = "requestWithURL:"
  private const val SEL_REQUEST = "request"
  private const val SEL_URL = "URL"
  private const val SEL_ABSOLUTE_STRING = "absoluteString"

  // NSURLResponse / NSData / WKURLSchemeTask
  private const val SEL_INIT_WITH_URL_STATUS_CODE_HTTP_VERSION_HEADER_FIELDS = "initWithURL:statusCode:HTTPVersion:headerFields:"
  private const val SEL_DATA_WITH_BYTES_LENGTH = "dataWithBytes:length:"
  private const val SEL_DICTIONARY = "dictionary"
  private const val SEL_SET_OBJECT_FOR_KEY = "setObject:forKey:"
  private const val SEL_DID_RECEIVE_RESPONSE = "didReceiveResponse:"
  private const val SEL_DID_RECEIVE_DATA = "didReceiveData:"
  private const val SEL_DID_FINISH = "didFinish"

  // WKUserContentController
  private const val SEL_ADD_USER_SCRIPT = "addUserScript:"
  private const val SEL_ADD_SCRIPT_MESSAGE_HANDLER = "addScriptMessageHandler:name:"
  private const val SEL_REMOVE_SCRIPT_MESSAGE_HANDLER = "removeScriptMessageHandlerForName:"

  // WKUserScript
  private const val SEL_INIT_WITH_SOURCE_INJECTION_TIME_FOR_MAIN_FRAME_ONLY = "initWithSource:injectionTime:forMainFrameOnly:"

  // WKScriptMessage
  private const val SEL_BODY = "body"

  // NSEvent
  private const val SEL_MODIFIER_FLAGS = "modifierFlags"
  private const val SEL_BUTTON_NUMBER = "buttonNumber"
  private const val SEL_KEY_CODE = "keyCode"
  // endregion

  /** Name used for the JS→JVM postMessage channel. JS calls: `window.webkit.messageHandlers.webviewIpc.postMessage(...)` */
  const val IPC_HANDLER_NAME = "webviewIpc"

  private const val WK_RECT_EDGE_NONE = 0L
  private const val WK_USER_SCRIPT_INJECTION_TIME_AT_DOCUMENT_START = 0L

  /**
   * Registered ObjC class acting as WKScriptMessageHandler. Created once, reused across instances.
   * The class name must be unique to avoid collisions with other ObjC runtime registrations.
   */
  private var messageHandlerClass: ID = ID.NIL

  /**
   * Callback reference kept alive to prevent GC while native code holds a function pointer.
   */
  @Suppress("unused") // prevent GC
  private var messageHandlerCallback: Callback? = null

  private var urlSchemeHandlerClass: ID = ID.NIL
  private var uiDelegateClass: ID = ID.NIL
  private var webViewClass: ID = ID.NIL
  private var nativeMouseRecognizerClass: ID = ID.NIL

  @Suppress("unused") // prevent GC
  private var urlSchemeStartCallback: Callback? = null

  @Suppress("unused") // prevent GC
  private var urlSchemeStopCallback: Callback? = null

  @Suppress("unused") // prevent GC
  private var uiDelegateCreateWebViewCallback: Callback? = null

  @Suppress("unused") // prevent GC
  private var webViewFlagsChangedCallback: Callback? = null

  @Suppress("unused") // prevent GC
  private var webViewAcceptsFirstMouseCallback: Callback? = null

  @Suppress("unused") // prevent GC
  private var nativePrimaryMouseDownCallback: Callback? = null

  @Suppress("unused") // prevent GC
  private var nativeSecondaryMouseDownCallback: Callback? = null

  @Suppress("unused") // prevent GC
  private var nativeOtherMouseDownCallback: Callback? = null

  /**
   * Per-webview callback registry. Key = the ObjC `self` pointer of the handler instance.
   * Value = callback invoked with the message body string.
   */
  private val messageHandlerCallbacks = java.util.concurrent.ConcurrentHashMap<Long, (String) -> Unit>()

  private val urlSchemeHandlerCallbacks = java.util.concurrent.ConcurrentHashMap<Long, (String) -> WebViewAssetResponse?>()

  private val newWindowCallbacks = java.util.concurrent.ConcurrentHashMap<Long, (String) -> Unit>()

  // AppKit delivers bare modifier key transitions through `flagsChanged:` instead of `keyDown:`/`keyUp:`.
  // One ObjC subclass is shared by all WKWebView instances, so callbacks and last modifier state are keyed
  // by the native WebView pointer.
  private val modifierKeyCallbacks = java.util.concurrent.ConcurrentHashMap<Long, (ModifierKeyEvent) -> Unit>()
  private val modifierStateByWebView = java.util.concurrent.ConcurrentHashMap<Long, Int>()
  private val nativeMousePressedCallbacks = java.util.concurrent.ConcurrentHashMap<Long, (NativeMousePressedEvent) -> Unit>()

  // AWT-shaped payload produced by the native layer. Posting to AWT is intentionally left to the host peer,
  // which owns Swing component lifecycle and can choose the correct event source.
  internal data class ModifierKeyEvent(
    val id: Int,
    val keyCode: Int,
    val modifiersEx: Int,
    val keyLocation: Int,
  )

  internal data class NativeMousePressedEvent(
    val button: Int,
    val modifiersEx: Int,
  )

  /**
   * Creates and configures a new `WKWebView` instance.
   *
   * @param onMessage callback invoked on the main thread when JS calls `postMessage`
   * @param resolveAssetUrl callback invoked by the private URL scheme handler.
   * @param onNewWindowRequested callback invoked when WebKit asks for a secondary WebView.
   * @param onNativeMousePressed callback invoked when AppKit delivers a mouse press inside the WebView.
   * @return handles that must be passed to [release].
   */
  fun createWKWebView(
    onMessage: (String) -> Unit,
    resolveAssetUrl: (String) -> WebViewAssetResponse?,
    onNewWindowRequested: (String) -> Unit,
    onModifierKeyEvent: (ModifierKeyEvent) -> Unit,
    onNativeMousePressed: (NativeMousePressedEvent) -> Unit,
    documentStartScripts: List<WebViewScript> = emptyList(),
  ): WebViewHandles {
    // 1. Create WKWebViewConfiguration
    val configuration = invoke(invoke(getObjcClass(CLS_WKWEBVIEW_CONFIGURATION), SEL_ALLOC), SEL_INIT)

    // 2. Configure preferences
    val preferences = invoke(configuration, SEL_PREFERENCES)
    invoke(preferences, SEL_SET_JAVA_SCRIPT_ENABLED, true)
    invoke(preferences, SEL_SET_JAVA_SCRIPT_CAN_OPEN_WINDOWS_AUTOMATICALLY, false)

    // 3. Set up user content controller with message handler
    val userContentController = invoke(configuration, SEL_USER_CONTENT_CONTROLLER)
    documentStartScripts.forEach { script ->
      installDocumentStartUserScript(userContentController, script.script)
    }
    val handlerInstance = createAndRegisterMessageHandler(onMessage)
    invoke(userContentController, SEL_ADD_SCRIPT_MESSAGE_HANDLER, handlerInstance, nsString(IPC_HANDLER_NAME))

    val urlSchemeHandlerInstance = createAndRegisterUrlSchemeHandler(resolveAssetUrl)
    invoke(configuration, SEL_SET_URL_SCHEME_HANDLER_FOR_URL_SCHEME, urlSchemeHandlerInstance, nsString(WEBVIEW_ASSET_CUSTOM_SCHEME))

    // 4. Allocate WKWebView with zero frame (will be set when attached)
    // Use a tiny WKWebView subclass only to observe `flagsChanged:`. The override always calls super, so
    // WebKit keeps its normal first-responder and browser behavior.
    val webView = invoke(ensureWebViewClassRegistered(), SEL_ALLOC)
    val initializedWebView = invoke(webView, SEL_INIT_WITH_FRAME_CONFIGURATION,
                                    NSRect(0.0, 0.0, 0.0, 0.0), configuration)
    modifierKeyCallbacks[initializedWebView.toLong()] = onModifierKeyEvent
    configureWebViewApplicationMode(initializedWebView)
    val nativeMouseRecognizer = createAndRegisterNativeMouseRecognizer(onNativeMousePressed)
    invoke(initializedWebView, SEL_ADD_GESTURE_RECOGNIZER, nativeMouseRecognizer)
    val uiDelegateInstance = createAndRegisterUiDelegate(onNewWindowRequested)
    invoke(initializedWebView, SEL_SET_UI_DELEGATE, uiDelegateInstance)

    // 5. Keep Swing host geometry as the only frame source. The WebView is attached
    // to a host-owned clipping view, so AppKit autoresizing must not change its local frame.
    invoke(initializedWebView, SEL_SET_AUTORESIZING_MASK, 0)

    // 6. Release configuration (webview retains it)
    invoke(configuration, SEL_RELEASE)

    return WebViewHandles(
      webView = initializedWebView,
      messageHandler = handlerInstance,
      urlSchemeHandler = urlSchemeHandlerInstance,
      uiDelegate = uiDelegateInstance,
      nativeMouseRecognizer = nativeMouseRecognizer,
    )
  }

  fun attachToParent(webView: ID, parentNSView: ID) {
    invoke(parentNSView, SEL_ADD_SUBVIEW, webView)
  }

  fun contentView(window: ID): ID? {
    val contentView = invoke(window, "contentView")
    return if (isNil(contentView)) null else contentView
  }

  fun createClippingContainer(parentNSView: ID): ID {
    val clipView = invoke(
      invoke(getObjcClass(CLS_NSVIEW), SEL_ALLOC),
      SEL_INIT_WITH_FRAME,
      NSRect(0.0, 0.0, 0.0, 0.0),
    )
    invoke(clipView, SEL_SET_WANTS_LAYER, true)
    val layer = invoke(clipView, SEL_LAYER)
    if (!isNil(layer)) {
      invoke(layer, SEL_SET_MASKS_TO_BOUNDS, true)
    }
    invoke(clipView, SEL_SET_AUTORESIZING_MASK, 0)
    invoke(clipView, SEL_SET_HIDDEN, true)
    invoke(parentNSView, SEL_ADD_SUBVIEW, clipView)
    return clipView
  }

  fun releaseClippingContainer(clipView: ID) {
    invoke(clipView, SEL_REMOVE_FROM_SUPERVIEW)
    invoke(clipView, SEL_RELEASE)
  }

  fun detachFromParent(webView: ID) {
    invoke(webView, SEL_REMOVE_FROM_SUPERVIEW)
  }

  fun loadUrl(webView: ID, url: String) {
    val nsUrl = invoke(getObjcClass(CLS_NSURL), SEL_URL_WITH_STRING, nsString(url))
    val request = invoke(getObjcClass(CLS_NSURLREQUEST), SEL_REQUEST_WITH_URL, nsUrl)
    invoke(webView, SEL_LOAD_REQUEST, request)
  }

  fun loadHtml(webView: ID, html: String, baseUrl: String?) {
    val nsBaseUrl = if (baseUrl != null) {
      invoke(getObjcClass(CLS_NSURL), SEL_URL_WITH_STRING, nsString(baseUrl))
    }
    else {
      ID.NIL
    }
    invoke(webView, SEL_LOAD_HTML_STRING_BASE_URL, nsString(html), nsBaseUrl)
  }

  /**
   * Evaluates JavaScript in the WebView and reports the result through the message handler channel.
   *
   * The message payload format is one of:
   * - `__eval__:<evalId>:<value>`
   * - `__eval_err__:<evalId>:<error>`
   *
   * [evalId] is provided by the engine and scoped per WebView instance.
   */
  fun evaluateJavaScript(webView: ID, script: String, evalId: Long) {
    @Language("JavaScript")
    val taggedScript = """
      (function() {
        try {
          const __result = eval(${escapeJsString(script)});
          window.webkit.messageHandlers[${escapeJsString(IPC_HANDLER_NAME)}].postMessage('__eval__:$evalId:' + String(__result));
        } catch(e) {
          window.webkit.messageHandlers[${escapeJsString(IPC_HANDLER_NAME)}].postMessage('__eval_err__:$evalId:' + e.message);
        }
      })();
    """.trimIndent()

    executeJavaScript(webView, taggedScript)
  }

  /**
   * Executes JavaScript without routing result/error back into the bridge channel.
   */
  fun executeJavaScript(webView: ID, script: String) {
    invoke(webView, SEL_EVALUATE_JAVASCRIPT, nsString(script), ID.NIL)
  }

  /**
   * Transfers raw JSON-RPC frame into the JS runtime ingress.
   */
  fun transferToJs(webView: ID, rawJson: String) {
    @Language("JavaScript")
    val script = "window.__WVI__ && window.__WVI__.__deliver(${escapeJsString(rawJson)});"
    executeJavaScript(webView, script)
  }

  fun setFrame(view: ID, x: Double, y: Double, w: Double, h: Double) {
    invoke(view, SEL_SET_FRAME, NSRect(x, y, w, h))
  }

  fun setHidden(view: ID, hidden: Boolean) {
    invoke(view, SEL_SET_HIDDEN, hidden)
  }

  fun requestFocus(webView: ID) {
    makeFirstResponder(webView)
  }

  fun makeFirstResponder(view: ID) {
    val window = invoke(view, SEL_WINDOW)
    if (!isNil(window)) {
      invoke(window, SEL_MAKE_FIRST_RESPONDER, view)
    }
  }

  fun clearFocus(webView: ID) {
    val window = invoke(webView, SEL_WINDOW)
    if (!isNil(window)) {
      invoke(window, SEL_MAKE_FIRST_RESPONDER, ID.NIL)
    }
  }

  /**
   * Dispatches an AppKit edit action through `NSApplication.sendAction(_:to:from:)`.
   *
   * `to = nil` preserves normal responder-chain routing, including WebKit's private editor
   * responders. The first-responder containment check prevents a command from leaking to another
   * native control in the same window when Swing focus state is stale.
   */
  fun performEditCommand(webView: ID, command: WebViewEditCommand): Boolean {
    val selector = editCommandSelector(command) ?: return false
    if (!firstResponderIsInsideWebView(webView)) return false
    val application = invoke(getObjcClass(CLS_NSAPPLICATION), SEL_SHARED_APPLICATION)
    return invoke(application, SEL_SEND_ACTION_TO_FROM, selector, ID.NIL, ID.NIL).booleanValue()
  }

  fun firstResponderState(webView: ID): MacWebViewFirstResponderState {
    val window = invoke(webView, SEL_WINDOW)
    if (isNil(window)) {
      return MacWebViewFirstResponderState(hasResponder = false, isInsideWebView = false, responderDescription = "<no window>")
    }

    val firstResponder = invoke(window, SEL_FIRST_RESPONDER)
    if (isNil(firstResponder)) {
      return MacWebViewFirstResponderState(hasResponder = false, isInsideWebView = false, responderDescription = "<nil>")
    }

    val isWebViewResponder = firstResponder == webView
    val isDescendantOfWebView = isDescendantOfWebView(firstResponder, webView)
    return MacWebViewFirstResponderState(
      hasResponder = true,
      isInsideWebView = isWebViewResponder || isDescendantOfWebView,
      responderDescription = describeResponder(firstResponder),
    )
  }

  /**
   * Releases the native WKWebView and its associated message handler.
   * Must be called on the macOS main thread.
   */
  fun release(handles: WebViewHandles) {
    // 1. Remove the message handler from user content controller to break retain cycle
    val configuration = invoke(handles.webView, "configuration")
    val ucc = invoke(configuration, SEL_USER_CONTENT_CONTROLLER)
    invoke(ucc, SEL_REMOVE_SCRIPT_MESSAGE_HANDLER, nsString(IPC_HANDLER_NAME))
    invoke(handles.webView, SEL_SET_UI_DELEGATE, ID.NIL)
    invoke(handles.webView, SEL_REMOVE_GESTURE_RECOGNIZER, handles.nativeMouseRecognizer)

    // 2. Detach from superview
    invoke(handles.webView, SEL_REMOVE_FROM_SUPERVIEW)

    // 3. Unregister message callback
    messageHandlerCallbacks.remove(handles.messageHandler.toLong())
    urlSchemeHandlerCallbacks.remove(handles.urlSchemeHandler.toLong())
    newWindowCallbacks.remove(handles.uiDelegate.toLong())
    modifierKeyCallbacks.remove(handles.webView.toLong())
    modifierStateByWebView.remove(handles.webView.toLong())
    nativeMousePressedCallbacks.remove(handles.nativeMouseRecognizer.toLong())

    // 4. Release native objects
    invoke(handles.messageHandler, SEL_RELEASE)
    invoke(handles.urlSchemeHandler, SEL_RELEASE)
    invoke(handles.uiDelegate, SEL_RELEASE)
    invoke(handles.nativeMouseRecognizer, SEL_RELEASE)
    invoke(handles.webView, SEL_RELEASE)
  }

  private fun createAndRegisterNativeMouseRecognizer(onMousePressed: (NativeMousePressedEvent) -> Unit): ID {
    val recognizerClass = ensureNativeMouseRecognizerClassRegistered()
    val recognizer = invoke(invoke(recognizerClass, SEL_ALLOC), SEL_INIT)
    nativeMousePressedCallbacks[recognizer.toLong()] = onMousePressed
    invoke(recognizer, SEL_SET_DELAYS_PRIMARY_MOUSE_BUTTON_EVENTS, false)
    invoke(recognizer, SEL_SET_DELAYS_SECONDARY_MOUSE_BUTTON_EVENTS, false)
    invoke(recognizer, SEL_SET_DELAYS_OTHER_MOUSE_BUTTON_EVENTS, false)
    return recognizer
  }

  @Synchronized
  private fun ensureNativeMouseRecognizerClassRegistered(): ID {
    if (!ID.NIL.equals(nativeMouseRecognizerClass)) return nativeMouseRecognizerClass

    val superclass = getObjcClass(CLS_NSGESTURE_RECOGNIZER)
    val cls = allocateObjcClassPair(superclass, "IdeaWKNativeMouseRecognizer")
    val primaryMouseDownCallback = createNativeMouseDownCallback(superclass, SEL_MOUSE_DOWN)
    val secondaryMouseDownCallback = createNativeMouseDownCallback(superclass, SEL_RIGHT_MOUSE_DOWN)
    val otherMouseDownCallback = createNativeMouseDownCallback(superclass, SEL_OTHER_MOUSE_DOWN)
    nativePrimaryMouseDownCallback = primaryMouseDownCallback
    nativeSecondaryMouseDownCallback = secondaryMouseDownCallback
    nativeOtherMouseDownCallback = otherMouseDownCallback
    addMethod(cls, SEL_MOUSE_DOWN, primaryMouseDownCallback, "v@:@")
    addMethod(cls, SEL_RIGHT_MOUSE_DOWN, secondaryMouseDownCallback, "v@:@")
    addMethod(cls, SEL_OTHER_MOUSE_DOWN, otherMouseDownCallback, "v@:@")
    registerObjcClassPair(cls)
    nativeMouseRecognizerClass = cls
    return cls
  }

  private fun createNativeMouseDownCallback(superclass: ID, mouseDownSelector: String): Callback {
    return object : Callback {
      @Suppress("unused", "UNUSED_PARAMETER") // called from native
      fun callback(self: ID, selector: Pointer, event: ID) {
        invokeSuperMouseEvent(self, superclass, mouseDownSelector, event)
        // This recognizer only observes the press. Failing immediately keeps it out of WebKit's
        // gesture arbitration while the original down/drag/up stream continues to the WebView.
        invoke(self, SEL_SET_STATE, NS_GESTURE_RECOGNIZER_STATE_FAILED)

        val button = macMouseButton(invoke(event, SEL_BUTTON_NUMBER).toInt()) ?: return
        val modifiersEx = macModifierFlagsToJavaModifiers(invoke(event, SEL_MODIFIER_FLAGS).toLong())
        WebViewLogger.LOG.debug("Observed WKWebView native mouse down: button=$button, modifiersEx=$modifiersEx")
        nativeMousePressedCallbacks[self.toLong()]?.invoke(NativeMousePressedEvent(button, modifiersEx))
      }
    }
  }

  // region WKWebView subclass registration
  //
  // This is the macOS-specific fallback for modifier-only shortcuts while focus is inside WKWebView.
  // Pure AWT events do not reliably see Shift/Ctrl transitions in that state, but AppKit does.
  // We observe only left/right Shift and Control, translate the state transition to a Java KeyEvent shape,
  // and leave the original AppKit event unconsumed.

  @Synchronized
  private fun ensureWebViewClassRegistered(): ID {
    if (!ID.NIL.equals(webViewClass)) return webViewClass

    val superclass = getObjcClass(CLS_WKWEBVIEW)
    val cls = allocateObjcClassPair(superclass, "IdeaWKWebView")

    // IDE popups can temporarily make their own window key. Accept the activation click so it
    // still reaches WebKit and the passive mouse observer on the first press.
    val acceptsFirstMouseCallback = object : Callback {
      @Suppress("unused", "UNUSED_PARAMETER") // called from native
      fun callback(self: ID, selector: Pointer, event: ID): Byte {
        WebViewLogger.LOG.debug("WKWebView accepted first mouse: event=$event")
        return 1
      }
    }
    webViewAcceptsFirstMouseCallback = acceptsFirstMouseCallback

    val flagsChangedCallback = object : Callback {
      @Suppress("unused", "UNUSED_PARAMETER") // called from native
      fun callback(self: ID, selector: Pointer, event: ID) {
        try {
          handleFlagsChanged(self, event)
        }
        finally {
          // Do not consume or short-circuit the AppKit event. WKWebView still needs the original
          // modifier state for selection, text editing, WebKit internals, and browser shortcuts.
          invokeSuperFlagsChanged(self, superclass, event)
        }
      }
    }
    webViewFlagsChangedCallback = flagsChangedCallback

    addMethod(cls, SEL_ACCEPTS_FIRST_MOUSE, acceptsFirstMouseCallback, "${objectiveCBooleanType()}@:@")
    addMethod(cls, SEL_FLAGS_CHANGED, flagsChangedCallback, "v@:@")

    registerObjcClassPair(cls)
    webViewClass = cls
    return cls
  }

  private fun handleFlagsChanged(webView: ID, event: ID) {
    val keyEvent = modifierKeyEvent(webView, event) ?: return
    // AppKit can deliver modifier changes to windows that are not currently owned by this WebView.
    // Forward only when WKWebView, or one of its private descendants, is the active first responder.
    if (!firstResponderIsInsideWebView(webView)) return
    modifierKeyCallbacks[webView.toLong()]?.invoke(keyEvent)
  }

  private fun modifierKeyEvent(webView: ID, event: ID): ModifierKeyEvent? {
    val keyCode = invoke(event, SEL_KEY_CODE).toInt()
    val javaKeyCode = macModifierKeyCodeToJavaKeyCode(keyCode) ?: return null
    val currentModifiers = macModifierFlagsToJavaModifiers(invoke(event, SEL_MODIFIER_FLAGS).toLong())
    val previousModifiers = modifierStateByWebView.put(webView.toLong(), currentModifiers) ?: 0
    val modifierMask = when (javaKeyCode) {
      KeyEvent.VK_SHIFT -> InputEvent.SHIFT_DOWN_MASK
      KeyEvent.VK_CONTROL -> InputEvent.CTRL_DOWN_MASK
      else -> return null
    }
    if ((previousModifiers and modifierMask) == (currentModifiers and modifierMask)) return null

    // `flagsChanged:` is state-based. Compare with the previous modifier mask to synthesize the
    // press/release edge expected by IntelliJ's AWT shortcut dispatch.
    return ModifierKeyEvent(
      id = if (currentModifiers and modifierMask != 0) KeyEvent.KEY_PRESSED else KeyEvent.KEY_RELEASED,
      keyCode = javaKeyCode,
      modifiersEx = currentModifiers,
      keyLocation = macModifierKeyLocation(keyCode),
    )
  }

  private fun macModifierKeyCodeToJavaKeyCode(keyCode: Int): Int? {
    return when (keyCode) {
      MAC_KEY_LEFT_SHIFT, MAC_KEY_RIGHT_SHIFT -> KeyEvent.VK_SHIFT
      MAC_KEY_LEFT_CONTROL, MAC_KEY_RIGHT_CONTROL -> KeyEvent.VK_CONTROL
      else -> null
    }
  }

  private fun macModifierKeyLocation(keyCode: Int): Int {
    return when (keyCode) {
      MAC_KEY_LEFT_SHIFT, MAC_KEY_LEFT_CONTROL -> KeyEvent.KEY_LOCATION_LEFT
      MAC_KEY_RIGHT_SHIFT, MAC_KEY_RIGHT_CONTROL -> KeyEvent.KEY_LOCATION_RIGHT
      else -> KeyEvent.KEY_LOCATION_UNKNOWN
    }
  }

  private fun macModifierFlagsToJavaModifiers(flags: Long): Int {
    var result = 0
    if (flags and NSEVENT_MODIFIER_FLAG_SHIFT != 0L) result = result or InputEvent.SHIFT_DOWN_MASK
    if (flags and NSEVENT_MODIFIER_FLAG_CONTROL != 0L) result = result or InputEvent.CTRL_DOWN_MASK
    if (flags and NSEVENT_MODIFIER_FLAG_OPTION != 0L) result = result or InputEvent.ALT_DOWN_MASK
    if (flags and NSEVENT_MODIFIER_FLAG_COMMAND != 0L) result = result or InputEvent.META_DOWN_MASK
    return result
  }

  private fun macMouseButton(buttonNumber: Int): Int? {
    return when (buttonNumber) {
      0 -> MouseEvent.BUTTON1
      1 -> MouseEvent.BUTTON3
      2 -> MouseEvent.BUTTON2
      in 3..19 -> buttonNumber + 1
      else -> null
    }
  }

  private fun invokeSuperFlagsChanged(receiver: ID, superclass: ID, event: ID) {
    MacObjectiveC.invokeSuper(receiver, superclass, SEL_FLAGS_CHANGED, event)
  }

  private fun invokeSuperMouseEvent(receiver: ID, superclass: ID, mouseSelector: String, event: ID) {
    MacObjectiveC.invokeSuper(receiver, superclass, mouseSelector, event)
  }

  // endregion

  // region Message handler class registration

  private fun installDocumentStartUserScript(userContentController: ID, @Language("JavaScript") source: String) {
    val userScript = invoke(
      invoke(getObjcClass(CLS_WKUSER_SCRIPT), SEL_ALLOC),
      SEL_INIT_WITH_SOURCE_INJECTION_TIME_FOR_MAIN_FRAME_ONLY,
      nsString(source),
      WK_USER_SCRIPT_INJECTION_TIME_AT_DOCUMENT_START,
      false,
    )
    invoke(userContentController, SEL_ADD_USER_SCRIPT, userScript)
    invoke(userScript, SEL_RELEASE)
  }

  private fun configureWebViewApplicationMode(webView: ID) {
    invoke(webView, SEL_SET_ALLOWS_BACK_FORWARD_NAVIGATION_GESTURES, false)
    invoke(webView, SEL_SET_ALLOWS_MAGNIFICATION, false)
    invokeIfResponds(webView, SEL_SET_PAGE_ZOOM, 1.0, "setPageZoom:")
    invokeIfResponds(webView, SEL_SET_INSPECTABLE, true, "setInspectable:")
    invokeIfResponds(webView, SEL_SET_CAN_USE_CREDENTIAL_STORAGE, false, "_setCanUseCredentialStorage:")
    invokeIfResponds(webView, SEL_SET_RUBBER_BANDING_ENABLED, WK_RECT_EDGE_NONE, "_setRubberBandingEnabled:")
  }

  private fun invokeIfResponds(target: ID, selector: String, value: Any, settingName: String) {
    if (!respondsTo(target, selector)) return
    try {
      invoke(target, selector, value)
    }
    catch (t: Throwable) {
      WebViewLogger.LOG.debug("Failed to apply WKWebView application-mode setting $settingName", t)
    }
  }

  private fun respondsTo(target: ID, selector: String): Boolean {
    return !isNil(target) && invoke(target, SEL_RESPONDS_TO_SELECTOR, nativeSelector(selector)).booleanValue()
  }

  private fun createAndRegisterMessageHandler(onMessage: (String) -> Unit): ID {
    ensureMessageHandlerClassRegistered()

    val instance = invoke(invoke(messageHandlerClass, SEL_ALLOC), SEL_INIT)
    messageHandlerCallbacks[instance.toLong()] = onMessage
    return instance
  }

  @Synchronized
  private fun ensureMessageHandlerClassRegistered() {
    if (!ID.NIL.equals(messageHandlerClass)) return

    val superclass = getObjcClass(CLS_NSOBJECT)
    val cls = allocateObjcClassPair(superclass, "IdeaWKMessageHandler")

    val protocol = getProtocol("WKScriptMessageHandler")
    if (!isNil(protocol)) {
      addProtocol(cls, protocol)
    }

    // Implement userContentController:didReceiveScriptMessage:
    // Type encoding: v@:@@ (void, self, _cmd, WKUserContentController, WKScriptMessage)
    val callback = object : Callback {
      @Suppress("unused", "UNUSED_PARAMETER") // called from native
      fun callback(self: ID, selector: Pointer, controller: ID, message: ID) {
        val body = invoke(message, SEL_BODY)
        val bodyString = toStringViaUTF8(body)
        if (bodyString != null) {
          val handler = messageHandlerCallbacks[self.toLong()]
          handler?.invoke(bodyString)
        }
      }
    }
    messageHandlerCallback = callback // prevent GC

    addMethod(cls, "userContentController:didReceiveScriptMessage:", callback, "v@:@@")

    registerObjcClassPair(cls)
    messageHandlerClass = cls
  }

  // endregion

  // region UI delegate class registration

  private fun createAndRegisterUiDelegate(onNewWindowRequested: (String) -> Unit): ID {
    ensureUiDelegateClassRegistered()

    val instance = invoke(invoke(uiDelegateClass, SEL_ALLOC), SEL_INIT)
    newWindowCallbacks[instance.toLong()] = onNewWindowRequested
    return instance
  }

  @Synchronized
  private fun ensureUiDelegateClassRegistered() {
    if (!ID.NIL.equals(uiDelegateClass)) return

    val superclass = getObjcClass(CLS_NSOBJECT)
    val cls = allocateObjcClassPair(superclass, "IdeaWKUiDelegate")

    val protocol = getProtocol("WKUIDelegate")
    if (!isNil(protocol)) {
      addProtocol(cls, protocol)
    }

    val createWebViewCallback = object : Callback {
      @Suppress("unused", "UNUSED_PARAMETER") // called from native
      fun callback(self: ID, selector: Pointer, webView: ID, configuration: ID, navigationAction: ID, windowFeatures: ID): ID {
        val url = urlFromNavigationAction(navigationAction)
        if (url != null) {
          newWindowCallbacks[self.toLong()]?.invoke(url)
        }
        return ID.NIL
      }
    }
    uiDelegateCreateWebViewCallback = createWebViewCallback

    addMethod(
      cls,
      "webView:createWebViewWithConfiguration:forNavigationAction:windowFeatures:",
      createWebViewCallback,
      "@@:@@@@",
    )

    registerObjcClassPair(cls)
    uiDelegateClass = cls
  }

  private fun urlFromNavigationAction(navigationAction: ID): String? {
    val request = invoke(navigationAction, SEL_REQUEST)
    return urlFromRequest(request)
  }

  // endregion

  // region URL scheme handler class registration

  private fun createAndRegisterUrlSchemeHandler(resolve: (String) -> WebViewAssetResponse?): ID {
    ensureUrlSchemeHandlerClassRegistered()

    val instance = invoke(invoke(urlSchemeHandlerClass, SEL_ALLOC), SEL_INIT)
    urlSchemeHandlerCallbacks[instance.toLong()] = resolve
    return instance
  }

  @Synchronized
  private fun ensureUrlSchemeHandlerClassRegistered() {
    if (!ID.NIL.equals(urlSchemeHandlerClass)) return

    val superclass = getObjcClass(CLS_NSOBJECT)
    val cls = allocateObjcClassPair(superclass, "IdeaWKUrlSchemeHandler")

    val protocol = getProtocol("WKURLSchemeHandler")
    if (!isNil(protocol)) {
      addProtocol(cls, protocol)
    }

    val startCallback = object : Callback {
      @Suppress("unused", "UNUSED_PARAMETER") // called from native
      fun callback(self: ID, selector: Pointer, webView: ID, task: ID) {
        val response = urlFromSchemeTask(task)?.let { url -> urlSchemeHandlerCallbacks[self.toLong()]?.invoke(url) }
                       ?: WebViewAssetResponse.notFound("WebView asset URL not found")
        sendSchemeTaskResponse(task, response)
      }
    }
    val stopCallback = object : Callback {
      @Suppress("unused", "UNUSED_PARAMETER") // called from native
      fun callback(self: ID, selector: Pointer, webView: ID, task: ID) {
      }
    }
    urlSchemeStartCallback = startCallback
    urlSchemeStopCallback = stopCallback

    addMethod(cls, "webView:startURLSchemeTask:", startCallback, "v@:@@")
    addMethod(cls, "webView:stopURLSchemeTask:", stopCallback, "v@:@@")

    registerObjcClassPair(cls)
    urlSchemeHandlerClass = cls
  }

  private fun urlFromSchemeTask(task: ID): String? {
    val request = invoke(task, SEL_REQUEST)
    return urlFromRequest(request)
  }

  private fun urlFromRequest(request: ID): String? {
    if (isNil(request)) return null
    val url = invoke(request, SEL_URL)
    if (isNil(url)) return null
    return toStringViaUTF8(invoke(url, SEL_ABSOLUTE_STRING))
  }

  private fun sendSchemeTaskResponse(task: ID, responseData: WebViewAssetResponse) {
    val request = invoke(task, SEL_REQUEST)
    val url = invoke(request, SEL_URL)
    val response = invoke(
      invoke(getObjcClass(CLS_NSHTTPURL_RESPONSE), SEL_ALLOC),
      SEL_INIT_WITH_URL_STATUS_CODE_HTTP_VERSION_HEADER_FIELDS,
      url,
      responseData.statusCode.toLong(),
      nsString("HTTP/1.1"),
      responseHeaders(responseData),
    )
    val data = dataWithBytes(responseData.bytes)
    invoke(task, SEL_DID_RECEIVE_RESPONSE, response)
    invoke(task, SEL_DID_RECEIVE_DATA, data)
    invoke(task, SEL_DID_FINISH)
    invoke(response, SEL_RELEASE)
  }

  private fun dataWithBytes(bytes: ByteArray): ID {
    if (bytes.isEmpty()) {
      return invoke(getObjcClass(CLS_NSDATA), SEL_DATA_WITH_BYTES_LENGTH, ID.NIL, 0L)
    }
    val memory = Memory(bytes.size.toLong())
    memory.write(0, bytes, 0, bytes.size)
    return invoke(getObjcClass(CLS_NSDATA), SEL_DATA_WITH_BYTES_LENGTH, memory, bytes.size.toLong())
  }

  private fun responseHeaders(responseData: WebViewAssetResponse): ID {
    val headers = invoke(getObjcClass(CLS_NSMUTABLE_DICTIONARY), SEL_DICTIONARY)
    invoke(headers, SEL_SET_OBJECT_FOR_KEY, nsString(responseData.contentType), nsString("Content-Type"))
    for ((name, value) in responseData.headers) {
      invoke(headers, SEL_SET_OBJECT_FOR_KEY, nsString(value), nsString(name))
    }
    return headers
  }

  // endregion

  // region Utilities

  private fun escapeJsString(s: String): String {
    val escaped = s.replace("\\", "\\\\")
      .replace("'", "\\'")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
      .replace("\r", "\\r")
      .replace("\t", "\\t")
    return "'$escaped'"
  }

  private fun describeResponder(responder: ID): String {
    return toStringViaUTF8(invoke(responder, SEL_DESCRIPTION)) ?: "<unknown>"
  }

  private fun editCommandSelector(command: WebViewEditCommand): Pointer? {
    return when (command) {
      WebViewEditCommand.COPY -> nativeSelector(SEL_COPY)
      WebViewEditCommand.PASTE -> nativeSelector(SEL_PASTE)
      WebViewEditCommand.CUT -> nativeSelector(SEL_CUT)
      WebViewEditCommand.SELECT_ALL -> nativeSelector(SEL_SELECT_ALL)
      WebViewEditCommand.UNDO -> nativeSelector(SEL_UNDO)
      WebViewEditCommand.REDO -> nativeSelector(SEL_REDO)
      else -> null
    }
  }

  /**
   * Requires AppKit's current first responder to be this WebView or one of its private subviews
   * before we send a responder-chain edit action on behalf of the Swing host.
   */
  private fun firstResponderIsInsideWebView(webView: ID): Boolean {
    val window = invoke(webView, SEL_WINDOW)
    if (isNil(window)) return false

    val firstResponder = invoke(window, SEL_FIRST_RESPONDER)
    return !isNil(firstResponder) && isInsideWebView(firstResponder, webView)
  }

  private fun isInsideWebView(responder: ID, webView: ID): Boolean {
    return responder == webView || isDescendantOfWebView(responder, webView)
  }

  private fun isDescendantOfWebView(responder: ID, webView: ID): Boolean {
    return responder != webView &&
           invoke(responder, SEL_RESPONDS_TO_SELECTOR, nativeSelector(SEL_IS_DESCENDANT_OF)).booleanValue() &&
           invoke(responder, SEL_IS_DESCENDANT_OF, webView).booleanValue()
  }

  private const val NSEVENT_MODIFIER_FLAG_SHIFT = 1L shl 17
  private const val NSEVENT_MODIFIER_FLAG_CONTROL = 1L shl 18
  private const val NSEVENT_MODIFIER_FLAG_OPTION = 1L shl 19
  private const val NSEVENT_MODIFIER_FLAG_COMMAND = 1L shl 20
  private const val MAC_KEY_LEFT_SHIFT = 56
  private const val MAC_KEY_LEFT_CONTROL = 59
  private const val MAC_KEY_RIGHT_SHIFT = 60
  private const val MAC_KEY_RIGHT_CONTROL = 62
  private const val NS_GESTURE_RECOGNIZER_STATE_FAILED = 5L
  private fun nativeSelector(name: String): Pointer = MacObjectiveC.selector(name)

  private fun objectiveCBooleanType(): String = if (CpuArch.isIntel64()) "c" else "B"

  // endregion

  data class WebViewHandles(
    val webView: ID,
    val messageHandler: ID,
    val urlSchemeHandler: ID,
    val uiDelegate: ID,
    val nativeMouseRecognizer: ID,
  )
}
