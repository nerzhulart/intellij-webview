# IntelliJ WebView Testkit

This is an unofficial npm distribution of the browser mock and preview testkit from
[`nerzhulart/intellij-webview`](https://github.com/nerzhulart/intellij-webview).

Install it together with the matching SDK version through npm aliases:

```json
{
  "devDependencies": {
    "@jetbrains/intellij-webview": "npm:@nerzhulart/intellij-webview-sdk@{{VERSION}}",
    "@jetbrains/intellij-webview-testkit": "npm:@nerzhulart/intellij-webview-sdk-testkit@{{VERSION}}"
  }
}
```

Browser mocks import `defineWebViewMock` from `@jetbrains/intellij-webview-testkit`. Runnable preview scripts import `runWebViewMockPreview` from `@jetbrains/intellij-webview-testkit/node`.

Pin both packages to the exact version matching the WebView runtime plugin. Licensed under Apache-2.0.
