# WebView Frontend Build

WebView pages are built as static plugin resources. Production views do not require or start a local HTTP server.

## Source Layout

Each plugin module that owns WebView UI keeps a `webview-src` package:

```text
webview-src/
  package.json
  bun.lock
  build.ts
  views/<view-id>/
    index.html
    src/
```

The view ID must match the ID passed to `WebViewAssetRoot.forView(viewId)`.

## Gradle Pipeline

The repository-local `io.github.nerzhulart.webview.frontend` Gradle plugin is the production build entry point. It registers:

- `bunInstall`, which runs `bun install --frozen-lockfile` in the module package, using a default Homebrew installation when present and otherwise resolving `bun` from `PATH`;
- `buildWebViewAssets`, which runs the package `build` script with `WEBVIEW_OUTPUT_ROOT` set to `build/generated-resources/webview/main/webview`;
- a `processResources` dependency that packages the generated output into the plugin.

`buildPlugin` therefore builds frontend assets automatically. To build every view without assembling ZIPs, run:

```shell
./gradlew buildAllWebViewAssets
```

On Windows, use `gradlew.bat`.

Do not treat files generated directly under `resources/webview` as source. The authoritative packaged output is under each module's `build/generated-resources/webview/main` directory.

## Vite Entry Points

Use the helpers from `@nerzhulart/intellij-webview-sdk/vite` in `build.ts`:

```ts
import { build } from "vite"
import { dirname } from "node:path"
import { fileURLToPath } from "node:url"
import {
  defineWebViewViewConfigs,
  selectWebViewViewBuildEntries,
  withWebViewBuildWatch,
} from "@nerzhulart/intellij-webview-sdk/vite"

const webviewSrcDir = dirname(fileURLToPath(import.meta.url))
const selected = selectWebViewViewBuildEntries(["settings"])

for (const config of defineWebViewViewConfigs({
  webviewSrcDir,
  views: selected.views,
  outputRoot: process.env.WEBVIEW_OUTPUT_ROOT,
})) {
  await build(withWebViewBuildWatch(config, selected.watch))
}
```

Keep the package script simple:

```json
{
  "scripts": {
    "build": "bun run build.ts",
    "typecheck": "tsc -p tsconfig.json --noEmit"
  }
}
```

## Local Development

From a module's `webview-src` directory:

```shell
bun install --frozen-lockfile
bun run typecheck
bun run build
```

Use `@nerzhulart/intellij-webview-sdk-testkit` for browser previews and smoke tests. The preview server is a development tool only; production code must continue loading bundled assets through `createWebViewPanel(...)`.

## Build Invariants

- Bun, TypeScript, and Vite must resolve the same local packages.
- `bun.lock` is committed; `node_modules` and generated assets are not.
- A target SDK upgrade may require build configuration changes, but it does not change this frontend pipeline.
- Plugin ZIPs must contain the generated `webview/views/<view-id>` resources for every shipped view.

See [Frontend Dependency Resolution](WebView-Frontend-Dependency-Resolution.md) and [Standalone Development and Verification](../guides/Standalone-Development-and-Verification.md).
