# IntelliJ WebView Testkit

This package provides the browser mock and preview testkit from
[`nerzhulart/intellij-webview`](https://github.com/nerzhulart/intellij-webview).

Install it together with the matching SDK version:

```json
{
  "devDependencies": {
    "@nerzhulart/intellij-webview-sdk": "{{VERSION}}",
    "@nerzhulart/intellij-webview-sdk-testkit": "{{VERSION}}"
  }
}
```

Browser mocks import `defineWebViewMock` from `@nerzhulart/intellij-webview-sdk-testkit`. Runnable preview scripts import `runWebViewMockPreview` from `@nerzhulart/intellij-webview-sdk-testkit/node`.

Pin both packages to the exact version matching the WebView runtime plugin. Licensed under Apache-2.0.
