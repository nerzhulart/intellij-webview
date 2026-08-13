# Browser Previews and Tests

`@nerzhulart/intellij-webview-sdk-testkit` runs a WebView page in a normal browser with typed host mocks. It is intended for UI development and browser smoke tests; it does not replace native-backend or IDE integration tests.

## Layout

Keep preview code outside production resources:

```text
webview-src/
  views/<view-id>/
  test/<view-id>/
    mocks/default.ts
    preview.ts
    <view-id>.browser.test.ts
```

Do not put mocks in `resources/webview`.

## Define a Host Mock

```ts
import { apiId } from "@nerzhulart/intellij-webview-sdk"
import { defineWebViewMock } from "@nerzhulart/intellij-webview-sdk-testkit"
import type { SettingsHostApi } from "../../views/settings/src/api"

const settingsHostApiId = apiId<SettingsHostApi>()("settings.host")

export default defineWebViewMock(context => {
  context.host.implement(settingsHostApiId, {
    async load() {
      return { enabled: true }
    },
  })
})
```

Mock the typed contract, not raw method strings or `window.__WVI__`.

## Runnable Preview

Create `test/<view-id>/preview.ts`:

```ts
import { runWebViewMockPreview } from "@nerzhulart/intellij-webview-sdk-testkit/node"

await runWebViewMockPreview({
  importMetaUrl: import.meta.url,
  viewId: "settings",
  mock: "default",
  open: true,
})
```

Add a package script:

```json
{
  "scripts": {
    "preview:settings": "bun test/settings/preview.ts",
    "webview-preview": "bun ../../webview-src/packages/testkit/src/cli.ts"
  }
}
```

Then run either:

```shell
bun run preview:settings
bun webview-preview settings --mock default
```

Direct IDE Run requires Bun as the preferred JavaScript runtime. `runWebViewMockPreview(...)` resolves the package root from `import.meta.url`, so it must work from any process working directory.

## Playwright Smoke Test

Start the same preview with `startWebViewMockPreview(...)`, interact through user-visible locators, and close it in teardown. Assert rendered behavior first. Inspect `window.__WVI_MOCK__.calls` only when the test specifically verifies the bridge contract.

A useful smoke test covers:

- initial state supplied by the host mock;
- a user action and resulting UI state;
- the typed host call or page notification produced by that action;
- an empty or error mock when those states are meaningful.

## Scope

The testkit covers TypeScript integration, browser rendering, view logic, and typed RPC behavior. It does not prove native embedding, native focus, asset-scheme handling, or backend availability. Cover those in Kotlin/native tests on the applicable OS.

See the runnable example in `demo/webview-src/test/acp-chat`.
