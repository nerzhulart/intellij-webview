# Embedded Browser Guide

Use browser mode to embed an arbitrary URL in an IntelliJ plugin. It needs no bundled assets, local HTTP server, production JavaScript, or TypeScript SDK integration. The consumer plugin still depends on the external WebView Runtime plugin; see [Install and Depend on the Runtime](../../README.md#install-and-depend-on-the-runtime).

For a plugin-owned page with bundled assets and typed Kotlin/TypeScript contracts, use the [WebView UI Authoring Guide](WebView-UI-Authoring-Guide.md) instead. `WebViewPanel`, `WebViewPanelOptions`, and `createWebViewPanel(...)` are unchanged; `WebViewPanel.webView` remains internal.

## Choose the Surface

| API | Surface and access |
| --- | --- |
| `createWebBrowserPanel(scope, options)` | Returns `WebBrowserPanel` without a toolbar. Its `component` is the actual `webView.component`, not a toolbar wrapper. Its public `webView` provides browser commands and state. |
| `createEmbeddedBrowserPanel(scope, options)` | Returns `EmbeddedBrowserPanel`, a `JPanel` containing a Swing toolbar above `browserPanel.component`. Exposes `browserPanel` and the same raw `webView`. |
| `WebView` | Public experimental interface with navigation methods directly on it; there is no separate browser controller. |

`EmbeddedBrowserPanel` is factory-only: its constructor is internal. The factory creates its browser; there is no public overload that wraps an existing `WebBrowserPanel` or `WebView`. Add the returned `EmbeddedBrowserPanel` itself to your Swing layout, or add `WebBrowserPanel.component` when building your own controls.

These APIs are experimental. `WebView`, `WebViewCreationOptions`, `WebViewFeatures`, and the engine selection types intentionally remain in `io.github.nerzhulart.webview.impl.engine` despite being public experimental types. Browser facade/state types are in `api`; Swing controls are in `ui`.

## Create a Browser Without a Toolbar

```kotlin
import com.intellij.util.concurrency.annotations.RequiresEdt
import io.github.nerzhulart.webview.api.WebBrowserPanel
import io.github.nerzhulart.webview.api.WebBrowserPanelOptions
import io.github.nerzhulart.webview.api.createWebBrowserPanel
import kotlinx.coroutines.CoroutineScope
import java.net.URI

@RequiresEdt
suspend fun createDocumentationBrowser(scope: CoroutineScope): WebBrowserPanel {
  return createWebBrowserPanel(
    scope = scope,
    options = WebBrowserPanelOptions(initialUrl = URI("https://www.jetbrains.com")),
  )
}
```

`initialUrl` is a `java.net.URI?` and defaults to `null`: no initial URL is requested. Supply an absolute URI here or later through `browser.webView.loadUrl(URI("https://www.jetbrains.com"))`; the raw API does not add a scheme for you. Relative URIs are rejected, and supported schemes depend on the engine. For local files, use `Path.toUri()`.

`WebBrowserPanelOptions.webViewOptions` accepts the complete `WebViewCreationOptions`: `engineKind`, `requirements`, `debugName`, `consoleLogCategory`, and `features`. Its default is `WebViewCreationOptions(features = WebViewFeatures.BROWSER)`.

## Create the Toolbar Panel and Override Features

```kotlin
import com.intellij.util.concurrency.annotations.RequiresEdt
import io.github.nerzhulart.webview.api.WebBrowserPanelOptions
import io.github.nerzhulart.webview.impl.engine.WebViewCreationOptions
import io.github.nerzhulart.webview.impl.engine.WebViewEngineKind
import io.github.nerzhulart.webview.impl.engine.WebViewEngineRequirements
import io.github.nerzhulart.webview.impl.engine.WebViewFeatures
import io.github.nerzhulart.webview.ui.EmbeddedBrowserPanel
import io.github.nerzhulart.webview.ui.EmbeddedBrowserPanelOptions
import io.github.nerzhulart.webview.ui.createEmbeddedBrowserPanel
import kotlinx.coroutines.CoroutineScope
import java.net.URI

@RequiresEdt
suspend fun createDocumentationPanel(scope: CoroutineScope): EmbeddedBrowserPanel {
  return createEmbeddedBrowserPanel(
    scope = scope,
    options = EmbeddedBrowserPanelOptions(
      browserOptions = WebBrowserPanelOptions(
        initialUrl = URI("https://www.jetbrains.com"),
        webViewOptions = WebViewCreationOptions(
          engineKind = WebViewEngineKind.System,
          requirements = WebViewEngineRequirements(interactiveInput = true),
          debugName = "Documentation browser",
          features = WebViewFeatures.BROWSER.copy(zoom = false, contextMenu = false),
        ),
      ),
      showNavigationButtons = true,
      showUrlField = true,
    ),
  )
}
```

`initialUrl` belongs inside `browserOptions`, not directly in `EmbeddedBrowserPanelOptions`. Both toolbar visibility options default to `true`; hiding both still leaves loading/error feedback. Use the bare facade when you want only the page surface.

**Preserve the browser preset when replacing creation options.** A plain `WebViewCreationOptions()` defaults to `WebViewFeatures.APPLICATION`, not `BROWSER`. Set `features = WebViewFeatures.BROWSER` explicitly, then use `.copy(...)` for individual overrides. In the example, `contextMenu = false` disables native menus on Windows/JCEF but is ignored on macOS while `applicationModeScript = false`; see the matrix below.

The toolbar provides Back, Forward, Reload/Stop, a URL field, a thin loading indicator, and an inline error label. Back/Forward follow history availability; Reload becomes Stop while loading. Enter trims input, ignores blank input, adds `https://` to an address without a scheme, and parses it as a URI. Invalid URI syntax is shown inline without requesting navigation; the input remains editable. It is an address field, not a search box. State updates do not overwrite an address while the user is editing it; Escape or loss of focus restores the current URL.

## Commands, State, and Lifetime

Call the suspend methods directly on `browser.webView` or `embedded.webView`:

| Method/property | Contract |
| --- | --- |
| `loadUrl(url: URI)` | Requests an absolute URI; relative URIs throw `IllegalArgumentException` before navigation. |
| `goBack()` / `goForward()` | No-op if `canGoBack` / `canGoForward` is false. |
| `reload()` / `stop()` | Reloads the current page / stops its current load. |
| `browserState` | `StateFlow<WebViewBrowserState>` containing the latest snapshot, not a stream of every browser event. |
| `isBrowserNavigationSupported` | Mirrors `runtimeInfo.capabilities.navigation`. |

`WebViewBrowserState` contains nullable `url` and `title`, `isLoading`, `progress` in `0.0..1.0`, `canGoBack`, `canGoForward`, and nullable `error`. `WebViewBrowserError` has `url`, `message`, and an optional native `code`. Network/navigation failures such as DNS failure appear in `error`; an HTTP error status alone is not a navigation error. A new navigation clears the previous error. Stop/cancel is not reported as a network error. Native progress precision varies by backend; do not assume byte-level progress or that a command's return means the page finished loading.

State/error URLs remain `String?`, as reported by the browser: native callbacks do not parse them as URIs. `WebViewEngine.loadUrl` also accepts `URI`: the session validates `isAbsolute` and passes the original URI to the engine. Inside each engine, `toString()` prepares the argument for the existing native navigation queue, preserving escaping; native bridges and `CefBrowser.loadURL` still receive strings. Native bridge contracts and ABI are unchanged. Migrating existing callers requires wrapping string literals in `URI(...)` (or passing an existing URI) for both `loadUrl` and `initialUrl`; custom engines must update their `loadUrl` override to accept `URI`.

For custom Swing state rendering, collect in the owning scope on EDT:

```kotlin
import com.intellij.openapi.application.EDT
import io.github.nerzhulart.webview.api.WebBrowserPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.swing.JLabel

fun observeBrowserTitle(scope: CoroutineScope, browser: WebBrowserPanel, label: JLabel) {
  scope.launch(Dispatchers.EDT) {
    browser.webView.browserState.collect { state ->
      label.text = state.title ?: state.url.orEmpty()
    }
  }
}
```

- Call both suspend factories on EDT, for example from `scope.launch(Dispatchers.EDT)`. An annotation documents this requirement; it does not switch dispatchers. Add/remove Swing components and render state on EDT too.
- Supply a scope with a cancellable job owned by the tool-window content or other owning UI. Cancel it when that owner is disposed. Do not use an unbounded global scope or cancel a shared project/service scope merely to close one browser.
- The scope owns the native view, host, and toolbar subscription. There is no separate panel `close()` method. Merely removing the Swing component does not cancel its scope; do not retain or issue commands to a disposed view.
- Navigation is suspendable; launch it from UI actions rather than blocking EDT with `runBlocking`. Observe `browserState` for completion/error feedback.

`webView.interop` still exists but is inert on pages without the WebView SDK. Browser mode does not install an application SDK into arbitrary sites. `BROWSER` disables SDK-dependent page-focus handover and the application-mode document-start script; native focus remains available, and runtime console capture remains installed. No application JavaScript or asset build is needed to browse. If you control a page and intentionally add interop, use the typed contracts in the authoring guide rather than raw bridge calls.

## Backend Selection and Applicability

The browser factory, including the one used by `EmbeddedBrowserPanel`, **forces `requirements.navigation = true`**, preserving other supplied requirements. This applies even if you override the feature preset with `APPLICATION` or explicitly pass `navigation = false`. `BROWSER` is a behavior preset, not itself a capability requirement. Selection rejects providers with missing capabilities or unavailable runtimes and throws `IllegalStateException` with selection diagnostics if no suitable provider remains.

The following describes implementation applicability, **not a claim of real Windows/Linux runtime verification**:

| Backend | Browser navigation/state | Availability caveat |
| --- | --- | --- |
| macOS WKWebView | Implemented; `navigation = true` | Requires macOS; use the manual checklist below. |
| Windows WebView2 | Implemented in Kotlin/Rust source; `navigation = true` | Requires a matching ABI v19 DLL rebuild on Windows before integrated use. |
| JCEF | Implemented; `navigation = true` | Requires an available JCEF runtime/provider; used for supported Linux panels. |
| Linux WebKitGTK | Unsupported; `navigation = false` | Provider is disabled, not a browser fallback. Native feature flags are not implemented. |

At the raw `WebView` level, browser commands on an unsupported engine are logged and ignored, and `browserState` stays `WebViewBrowserState.EMPTY`. This defensive no-op behavior does not make the disabled WebKitGTK provider selectable: browser-panel creation still requires navigation support.

### Feature Presets and Native Flags

`APPLICATION` preserves bundled-UI behavior. `BROWSER` is deliberately conservative: elastic scrolling, swipe history gestures, browser accelerator keys, and autofill remain off to avoid unwanted behavior or IDE shortcut conflicts. Opt in with `WebViewFeatures.BROWSER.copy(...)` when appropriate. Flags are best-effort backend settings, not a cross-platform policy or security boundary; an ignored flag leaves backend behavior unchanged.

| Feature | `APPLICATION` | `BROWSER` | macOS WKWebView | Windows WebView2 | JCEF |
| --- | --- | --- | --- | --- | --- |
| `applicationModeScript` | `true` | `false` | Document-start context-menu/form-assist suppression | Same runtime script | Same runtime script |
| `pageFocusInterop` | `true` | `false` | SDK-dependent Swing/page focus handover | Same runtime handover | Same runtime handover |
| `contextMenu` | `false` | `true` | No independent native switch; see caveat below | `AreDefaultContextMenusEnabled` | Context-menu suppression handler when false |
| `zoom` | `false` | `true` | `allowsMagnification` | `IsZoomControlEnabled` | Ignored |
| `elasticScrolling` | `false` | `false` | `_setRubberBandingEnabled:` when available | Ignored | Ignored |
| `swipeNavigation` | `false` | `false` | `allowsBackForwardNavigationGestures` | `IsSwipeNavigationEnabled` when available | Ignored |
| `browserAcceleratorKeys` | `false` | `false` | Ignored | `AreBrowserAcceleratorKeysEnabled` when available | Ignored |
| `builtInErrorPage` | `false` | `true` | Ignored | `IsBuiltInErrorPageEnabled` | Ignored |
| `autofill` | `false` | `false` | Credential storage via `_setCanUseCredentialStorage:` when available | General autofill and password autosave when available | Ignored |

WebKitGTK is disabled and its native flags are unsupported/ignored; it is excluded from the operational columns above. Optional native selectors/interfaces depend on the installed engine version. The two runtime flags are not native switches: `pageFocusInterop = true` requires SDK participation, while `applicationModeScript = true` injects suppression behavior regardless of the native flags.

**macOS context-menu caveat:** with `applicationModeScript = false`, `contextMenu = false` is ignored. macOS has no independent native menu setting in this implementation. Turning `applicationModeScript` on suppresses context menus, but also applies bundled-UI form-assist restrictions; it is not a menu-only workaround. Conversely, `contextMenu = true` cannot undo script-level suppression when `applicationModeScript = true`, on any backend.

Browser mode is not a full browser shell: no tabs, bookmarks, history-list UI, downloads UI, or navigation allow/deny policy is added. New-window requests retain the existing external-browser handling rather than opening embedded tabs.

### Windows ABI v19 Rebuild Required

The Kotlin bridge and Rust source require `wvi-awt-canvas-host-v19`. The bundled DLLs are unchanged: the current macOS environment has no MSVC `link.exe`, so it cannot produce the required Windows release DLLs. **Rebuild both x64 and ARM64 DLLs on your Windows machine** using the [Windows bridge rebuild instructions](../../native/WinWebView2Bridge/README.md#required-v19-dll-rebuild), then package the matching files from `lib/webview-native/win/`.

Do not ship the old DLLs with the v19 Kotlin bridge or bypass the ABI check; mismatched DLLs must be rejected. Source checks or fake-engine tests are not evidence of a working Windows or Linux browser. Real Windows/Linux verification remains required.

## Run the Sample and Check macOS Manually

The [embedded-browser sample](../../samples/embedded-browser/README.md) provides the **Embedded Browser** tool window. With the sample's root sandbox integration in place, launch from the repository root:

```shell
./gradlew runIde
```

Root `runIde` includes the sample plugin; open its tool window in the sandbox IDE. Use `gradlew.bat` on Windows. The current checkout lacks `gradle/wrapper/gradle-wrapper.jar`, and `gradlew` is not executable locally: bootstrap the wrapper as described in [Standalone Development and Verification](Standalone-Development-and-Verification.md#bootstrap-the-gradle-wrapper), or use an installed matching Gradle with `gradle runIde`. The root sandbox build may build other bundled UI demos even though this browser sample needs no production web assets.

This is a manual checklist, not a record of scenarios already verified:

1. **URL entry:** open a reachable HTTPS URL, then enter a bare host such as `example.com`. Confirm Enter navigates with `https://`; blank input does nothing. Confirm the native page can receive mouse and keyboard focus.
2. **Redirects and history:** navigate to a known redirecting URL and check that the address field settles on the final URL. Follow a same-window link, use Back and Forward, and check the rendered page, address, and history-button enabled states together.
3. **Reload and Stop:** reload a page and check the loading indicator and Reload/Stop transition. Start a sufficiently slow load, click Stop, and confirm loading ends without a cancellation error. Navigate again to confirm the browser remains usable.
4. **DNS error and recovery:** navigate to `https://nonexistent.invalid`. Confirm the inline error appears and loading ends. Navigate to a reachable URL and confirm the error clears. An HTTP 404 alone should not be treated as a DNS/navigation error.
5. **Address editing:** while a page is loading or redirecting, focus the URL field and type a replacement address without submitting it. State updates must not overwrite the edit. Check Escape and focus loss restore the current URL; Enter submits the edited address.
6. **Page editing and native flags:** use a page with an editable field; check typing, selection, copy/paste, undo/redo, and focus transfer to/from the IDE. With default `BROWSER`, check context menus and magnification, and check that swipe history and elastic scrolling remain disabled where supported. For overrides, apply the platform matrix, especially the macOS context-menu caveat.
7. **Disposal:** dispose the owning content/window while a load is active and cancel its scope. Check that the native view disappears, loading/state subscriptions stop, and no late-callback errors appear in the IDE log. Recreate the content and confirm a fresh browser works. Merely hiding a tool window is not necessarily disposal.