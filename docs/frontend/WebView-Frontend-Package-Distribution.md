# Frontend Package Distribution

WebView Runtime releases produce two public TypeScript packages alongside the three plugin ZIPs. Source manifests under `webview-src` remain private; the release build creates clean ESM tarballs containing compiled JavaScript, TypeScript declarations, package metadata, and only the runtime assets required by the public build helpers.

## Package Coordinates

The npm artifacts and their source manifests use the project owner's npm scope directly:

```json
{
  "devDependencies": {
    "@nerzhulart/intellij-webview-sdk": "0.1.0",
    "@nerzhulart/intellij-webview-sdk-testkit": "0.1.0"
  }
}
```

The controls and React controls packages remain private and are not part of this distribution.

## Versioning Contract

The plugin ZIP, SDK package, and testkit package built by one release have the same SemVer version. Consumers must pin that exact version; floating ranges and npm tags can select a frontend API newer than the installed runtime plugin.

Frontend-to-plugin compatibility and plugin-to-IDE compatibility are separate:

1. The npm package version identifies the matching WebView Runtime plugin API and wire protocol.
2. The plugin descriptor identifies the supported IntelliJ-based IDE build range.

Changing the target IDE without changing the frontend API does not require a separate frontend version.

## Public Package Surface

`@nerzhulart/intellij-webview-sdk` provides:

- `.` — typed browser APIs such as `apiId` and `webView`;
- `./vite` — supported Vite configuration helpers;
- `./tsconfig.view.json` — the shared view compiler configuration;
- package-local bridge assets used only by the Vite development server.

The low-level `./runtime` entry is intentionally not published. The installed runtime plugin supplies and injects the bridge in IDE-hosted views.

`@nerzhulart/intellij-webview-sdk-testkit` provides:

- `.` — mock definitions and preview server API;
- `./node` — runnable preview helpers;
- `./vite` — mock-bridge Vite integration;
- the `webview-preview` Bun executable.

## Release Guarantees

The build workflow creates plugin ZIPs and npm tarballs from one commit and version. Before upload, a clean fixture installs the tarballs, typechecks all public exports, builds a view, starts a testkit preview outside the checkout, and requests the packaged bridge asset.

The release workflow validates the selected build SHA, version, package names, and SHA-512 integrity. A retry skips an existing npm version only when its registry integrity matches the selected tarball; npm versions are never overwritten.

Runtime capability negotiation is not implemented yet. Exact version matching is therefore the compatibility requirement.
