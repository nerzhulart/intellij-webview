# IntelliJ WebView TypeScript SDK

This package provides the TypeScript SDK from
[`nerzhulart/intellij-webview`](https://github.com/nerzhulart/intellij-webview).
It is versioned together with the WebView runtime plugin.

Install the package under its published name:

```json
{
  "devDependencies": {
    "@nerzhulart/webview-sdk": "{{VERSION}}"
  }
}
```

```ts
import { apiId, webView, type WebViewCallable } from "@nerzhulart/webview-sdk"
import { defineWebViewViewConfigs } from "@nerzhulart/webview-sdk/vite"
```

Pin the exact version matching the WebView runtime plugin. Do not use a floating semver range.

The package exposes the typed browser API, the Vite build helpers, and `tsconfig.view.json`. The low-level runtime bridge is supplied by the IDE and is not exported by this package.

Licensed under Apache-2.0.
