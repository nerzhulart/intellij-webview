# WebView UI Authoring Guide

Use this guide to add a local WebView page to an IntelliJ plugin that depends on the external WebView Runtime plugin.

## Use the Supported Stack

- Kotlin host: `createWebViewPanel(...)`, `WebViewPanelOptions`, and `WebViewAssetRoot.forView(...)`.
- Kotlin contracts: `WebViewApi`, `WebViewImplementable`, `WebViewCallable`, `WebViewApiId`, and `WebViewInterop`.
- TypeScript contracts: `apiId`, `webView.callable(...)`, and `webView.implement(...)` from `@jetbrains/intellij-webview`.
- Frontend sources: `webview-src/views/<view-id>`.
- Packaged assets: `webview/views/<view-id>` in the plugin classpath.

Feature code must not use raw method strings, `WebViewMessageBus`, or `window.__WVI__`. Those are runtime implementation details. Browser mocks may inspect `window.__WVI_MOCK__` only for bridge-contract assertions.

## Create a View

Use this layout in the plugin module that owns the UI:

```text
my-plugin/
  src/...
  webview-src/
    views/
      my-view/
        index.html
        src/main.ts
    package.json
    tsconfig.json
    build.ts
```

The standalone Gradle build writes generated resources under:

```text
build/generated-resources/webview/main/webview/views/my-view/
  index.html
  view.js
  styles.css
  assets/
```

Direct Bun builds use the ignored `resources/webview` tree for local iteration. Never commit or hand-edit generated WebView resources.

Use the shared Vite helpers:

```ts
import { dirname } from "node:path"
import { fileURLToPath } from "node:url"
import { build } from "vite"
import {
  defineWebViewViewConfigs,
  selectWebViewViewBuildEntries,
  withWebViewBuildWatch,
} from "@jetbrains/intellij-webview/vite"

const webviewSrcDir = dirname(fileURLToPath(import.meta.url))
const outputRoot = process.env.WEBVIEW_OUTPUT_ROOT
const selected = selectWebViewViewBuildEntries(["my-view"])

for (const config of defineWebViewViewConfigs({ webviewSrcDir, views: selected.views, outputRoot })) {
  await build(withWebViewBuildWatch(config, selected.watch))
}
```

The helper injects the runtime bridge before application code and emits stable filenames. Do not add `/__webview/*.js` tags manually.

## Host the View from Kotlin

Create the panel on EDT and let the supplied scope own its lifetime:

```kotlin
private val assetRoot = WebViewAssetRoot.forView("my-view")

@RequiresEdt
suspend fun createMyView(scope: CoroutineScope): WebViewPanel {
  return createWebViewPanel(
    scope = scope,
    options = WebViewPanelOptions(
      assetRoot = assetRoot,
      debugName = "My view",
    ),
  )
}
```

Add `panel.component` to the Swing hierarchy on EDT. Cancel or complete the owner scope to dispose the WebView. Do not create a competing manual cleanup path around `WebViewPanel.close()`.

Use the panel as the feature entry point:

- register APIs through `panel.interop.implement(...)`;
- obtain page proxies through `panel.interop.callable(...)`;
- keep Swing objects, services, mutable domain state, and validation authority in Kotlin;
- send only serializable DTOs across the bridge.

## Define a Typed Protocol

Declare the same namespace, methods, and DTO shapes on both sides.

Kotlin implements an API called from TypeScript:

```kotlin
@Serializable
data class OpenFileRequest(val path: String, val line: Int? = null)

@Serializable
data class OpenFileResult(val opened: Boolean)

interface EditorHostApi : WebViewImplementable {
  companion object {
    val ID: WebViewApiId<EditorHostApi> = WebViewApiId.of("editor.host")
  }

  suspend fun openFile(params: OpenFileRequest): OpenFileResult
}

panel.interop.implement(EditorHostApi.ID, editorHostApi)
```

TypeScript calls it:

```ts
import { apiId, webView, type WebViewCallable } from "@jetbrains/intellij-webview"

interface EditorHostApi extends WebViewCallable {
  openFile(params: { path: string; line?: number }): Promise<{ opened: boolean }>
}

const editorHostApiId = apiId<EditorHostApi>()("editor.host")
const hostApi = webView.callable(editorHostApiId)
await hostApi.openFile({ path: "src/Main.kt" })
```

For Kotlin-to-page notifications, Kotlin declares `WebViewCallable` and TypeScript declares `WebViewImplementable`.

Protocol rules:

- define a namespace once with `WebViewApiId.of(...)` and `apiId(...)`;
- keep method names identical; the wire name is `namespace/methodName`;
- use `suspend`/`Promise` for request-response methods;
- use `Unit`/`void` for notifications;
- allow zero or one serializable parameter object;
- do not overload protocol methods.

See [Typed Kotlin/TypeScript APIs](../architecture/WebView-TS-RPC-API-Design.md) for the full contract.

## Keep Frontend State Local

Treat the page as a separate browser process boundary:

```text
Kotlin domain state -> serializable DTOs -> frontend store -> pure projections -> UI
```

UI components may read the store and invoke typed protocol functions. Projection functions and getters must stay pure and must not hide RPC calls.

## Preview with a Mock Host

Put mocks and preview code beside frontend tests:

```text
webview-src/test/my-view/
  mocks/default.ts
  preview.ts
  my-view.browser.test.ts
```

Define browser-side host behavior with `defineWebViewMock(...)` from `@jetbrains/intellij-webview-testkit`. Production view code continues to import only `@jetbrains/intellij-webview`.

A runnable preview entry point looks like this:

```ts
import { runWebViewMockPreview } from "@jetbrains/intellij-webview-testkit/node"

await runWebViewMockPreview({
  importMetaUrl: import.meta.url,
  viewId: "my-view",
  mock: "default",
  open: true,
})
```

Add an explicit Bun script:

```json
{
  "scripts": {
    "preview:my-view": "bun test/my-view/preview.ts"
  }
}
```

For parameterized CLI previews:

```shell
bun webview-preview my-view --mock default
```

Preview resolution must not depend on the process working directory. Fix testkit `server.fs.allow` roots if Vite rejects a view outside its serving allow-list.

## Add a Playwright Smoke Test

Start the preview from the test and interact through user-visible locators:

```ts
import { expect, test } from "@playwright/test"
import { startWebViewMockPreview } from "@jetbrains/intellij-webview-testkit"

test("runs the view", async ({ page }) => {
  const preview = await startWebViewMockPreview({
    webviewSrcDir,
    viewId: "my-view",
    mock: mockFile,
  })

  try {
    await page.goto(preview.url)
    await page.getByRole("button", { name: "Run" }).click()
    await expect(page.getByText("Done")).toBeVisible()
  }
  finally {
    await preview.close()
  }
})
```

Use `window.__WVI_MOCK__.calls` only when asserting a bridge call that cannot be observed through rendered state.

## Handle Focus Leaving the Page

Close transient browser UI when Swing focus leaves the WebView:

```ts
import { addWebViewFocusLeaveListener } from "@jetbrains/intellij-webview"

const dispose = addWebViewFocusLeaveListener(() => closePopup())
```

Dispose the listener with the component or view. Shared controls already apply the correct behavior to their own inputs, menus, and popups.

## Use IDE-Style Browser Behavior

Treat the page as application UI, not a browser tab. Do not rely on browser context menus, page zoom, navigation gestures, autofill prompts, or browser shortcuts. Implement graph, canvas, or image zoom as component state rather than changing the page scale.

Use ordinary `console.*` calls for diagnostics. The runtime forwards them to the IDE log and preserves the original browser console output.

## Use Icons and Controls

- [WebView Controls](../frontend/WebView-Controls.md) documents the shared Web Components and React controls.
- [IconSet Loading](../frontend/WebView-IconSet-Loading.md) documents classloader-backed light/dark icons.
- [Browser Testkit](../frontend/WebView-Frontend-Testability.md) documents previews, mocks, and call assertions.

Use `demo/` as the reference implementation for current asset builds, hosting, typed protocols, and browser tests.
