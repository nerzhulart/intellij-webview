# IntelliJ WebView TypeScript SDK

This package provides the TypeScript SDK from
[`nerzhulart/intellij-webview`](https://github.com/nerzhulart/intellij-webview).
It is versioned together with the WebView runtime plugin.

Keep the canonical `@jetbrains/intellij-webview` import in application code and install this distribution through an npm alias:

```json
{
  "devDependencies": {
    "@jetbrains/intellij-webview": "npm:@nerzhulart/intellij-webview-sdk@{{VERSION}}"
  }
}
```

```ts
import { apiId, webView, type WebViewCallable } from "@jetbrains/intellij-webview"
import { defineWebViewViewConfigs } from "@jetbrains/intellij-webview/vite"
```

Pin the exact version matching the WebView runtime plugin. Do not use a floating semver range.

The package exposes the typed browser API, the Vite build helpers, and `tsconfig.view.json`. The low-level runtime bridge is supplied by the IDE and is not exported by this package.

Licensed under Apache-2.0.
