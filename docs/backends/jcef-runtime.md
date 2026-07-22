# JCEF Runtime

JCEF is the cross-OS fallback browser engine and the default asset-capable engine on Linux.

## Availability and Selection

The optional `intellij.platform.ui.webview.jcef` content module registers `JcefEngineProvider`. It is available only when JBCEF can create a browser and CEF is not shutting down.

The provider reports these capabilities:

| Capability | Supported |
| --- | --- |
| Bundled asset serving | Yes |
| Typed message bridge | Yes |
| Swing embedding | Yes |
| Interactive input | Yes |

JCEF is selected when it is explicitly requested internally, when a system browser engine is unavailable, or by default for asset-backed Linux panels. Rendering mode remains owned by JBCEF configuration; WebView Runtime does not expose separate windowed and off-screen JCEF backends.

## Assets

Asset-backed pages use the virtual origin `https://ij-webview-assets.local/`. CEF request handlers delegate to the active `WebViewAssetResolver`, including common runtime files such as the bridge script. No files are extracted and no localhost server is started.

## Messaging

The native transport uses the JCEF query handler for page-to-host frames and JavaScript evaluation for host-to-page frames. JSON-RPC state remains in the common message-bus implementation so every engine exposes the same typed API.

The raw transport names are internal details. Consumer plugins should use `WebViewInterop`, `WebViewApiId`, and `@jetbrains/intellij-webview`.

## Constraints

- JBCEF registry settings can make the engine unavailable.
- Remote or out-of-process behavior is not enabled by this plugin.
- Browser features such as arbitrary navigation, downloads, authentication dialogs, and context menus are not consumer APIs.

Build the JCEF content module as part of the runtime plugin ZIP:

```shell
./gradlew buildPlugin
```
