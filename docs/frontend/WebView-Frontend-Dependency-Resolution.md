# WebView Frontend Dependency Resolution

The WebView TypeScript packages are private workspace packages. This repository resolves them from local source; it does not ask Bun to download JetBrains WebView packages from npm.

## Consumer Package

Use local `file:` dependencies whose paths point to a checkout or packaged source artifact from the same WebView Runtime release:

```json
{
  "dependencies": {
    "@nerzhulart/webview-sdk": "file:../../webview-src",
    "@nerzhulart/webview-controls": "file:../../webview-src/packages/controls",
    "@nerzhulart/webview-react-controls": "file:../../webview-src/packages/react-controls",
    "@nerzhulart/webview-testkit": "file:../../webview-src/packages/testkit"
  }
}
```

Use only the packages the view needs. The testkit is a development dependency unless runnable previews are intentionally shipped with the source package.

After a locally referenced package changes, run `bun install` again in each consuming `webview-src` package. Bun materializes `file:` dependencies in `node_modules`; existing materialization does not update itself merely because source files changed.

## Resolver Alignment

Three tools must agree:

1. Bun resolves package manifests and installs dependencies.
2. TypeScript resolves declarations during `typecheck`.
3. Vite resolves runtime imports during the bundle build.

Prefer package `exports` and `file:` dependencies for normal imports. Add `compilerOptions.paths` only for source-level development cases such as the private testkit entry points:

```json
{
  "compilerOptions": {
    "paths": {
      "@nerzhulart/webview-testkit": ["../../webview-src/packages/testkit/src/index.ts"],
      "@nerzhulart/webview-testkit/node": ["../../webview-src/packages/testkit/src/node.ts"]
    }
  }
}
```

Do not add production aliases to a deleted source-layout path or a globally installed package.

## Runtime and Test Imports

- Production view code imports `@nerzhulart/webview-sdk` and optional control packages.
- Browser mocks import `defineWebViewMock` from `@nerzhulart/webview-testkit`.
- Runnable preview scripts and Node-side tests may import `@nerzhulart/webview-testkit/node`.
- Production view code must not import the `/node` entry point.

## Verification

From every consuming package:

```shell
bun install --frozen-lockfile
bun run typecheck
bun run build
```

If source and `node_modules` behavior disagree, reinstall before changing Vite or production code.
