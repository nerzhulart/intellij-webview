# IntelliJ WebView Testkit

This package provides the browser mock and preview testkit from
[`nerzhulart/intellij-webview`](https://github.com/nerzhulart/intellij-webview).

Install it together with the matching SDK version:

```json
{
  "devDependencies": {
    "@nerzhulart/webview-sdk": "{{VERSION}}",
    "@nerzhulart/webview-testkit": "{{VERSION}}"
  }
}
```

Browser mocks import `defineWebViewMock` from `@nerzhulart/webview-testkit`. Runnable preview scripts import `runWebViewMockPreview` from `@nerzhulart/webview-testkit/node`.

Pin both packages to the exact version matching the WebView runtime plugin. Licensed under Apache-2.0.
