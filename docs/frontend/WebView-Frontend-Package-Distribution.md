# Frontend Package Distribution

The TypeScript packages in `webview-src` are private and are not currently published to a package registry. Repository modules consume them through local `file:` dependencies.

## Versioning Policy

The following packages form one compatibility set and must be sourced from the same WebView Runtime plugin release:

- `@jetbrains/intellij-webview`
- `@jetbrains/intellij-webview-controls`
- `@jetbrains/intellij-webview-react-controls`
- `@jetbrains/intellij-webview-testkit`

Their public package version, once published, must follow the external plugin version. Do not version the bridge package from the target IDE build number.

There are two separate compatibility questions:

1. **Frontend-to-plugin compatibility:** the TypeScript package set must match the installed WebView Runtime plugin API and wire protocol.
2. **Plugin-to-IDE compatibility:** the plugin ZIP declares the supported IntelliJ-based IDE build range.

Changing the target IDE without changing the frontend API does not require a new frontend protocol version, although it still produces a new plugin build.

## Current Consumption

Until registry publication exists, consumers must use a checkout or source artifact from the same release tag:

```json
{
  "dependencies": {
    "@jetbrains/intellij-webview": "file:../webview-runtime/webview-src",
    "@jetbrains/intellij-webview-controls": "file:../webview-runtime/webview-src/packages/controls"
  }
}
```

Commit the consumer's lockfile, but do not commit `node_modules`.

## Release Requirements

A distributable package set must:

- carry the external plugin release version in every package manifest;
- preserve the documented package exports;
- include TypeScript declarations and runtime/build sources required by consumers;
- be verified with a clean install, typecheck, build, testkit preview, and one browser smoke test;
- document its required WebView Runtime plugin version separately from the supported IDE build range.

GitHub Releases currently publish three plugin ZIPs only. Publishing frontend package artifacts remains distribution work, not a completed capability.
