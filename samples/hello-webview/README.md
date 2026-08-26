# Hello WebView Consumer Sample

This standalone plugin consumes the published WebView Runtime and npm SDK to provide one **Hello WebView** tool window. Its bundled page has one full-area **Click me** button; the local IDE test clicks that button with `java.awt.Robot` and waits for the typed Kotlin handler.

## Prerequisites

- JDK 25 and Gradle 9.1.0.
- Bun installed through Homebrew or available on `PATH`.
- A graphical desktop and an available WebView engine for the UI test.

The test is intentionally skipped when the process is headless, a WebView panel cannot be created, or `java.awt.Robot` is unavailable. It does not prepare or test native libraries.

## Published SDK Dependencies

The IDE runtime dependency is:

```kotlin
plugin("io.github.nerzhulart.webview:0.7.1-eap.3@eap")
```

The `@eap` suffix selects the Marketplace EAP channel. Drop it after the same version is released on the default channel. The frontend package is pinned to the matching version in `webview-src/package.json` and locked in `webview-src/bun.lock`.

To update the sample for a new SDK release:

1. Change `webviewVersion` in `gradle.properties`.
2. Change `webviewPluginChannel` when the release channel changes.
3. Update `@nerzhulart/intellij-webview-sdk` in `webview-src/package.json` to the same version.
4. Run `bun install` in `webview-src` and commit the updated `bun.lock`.

## Local Commands

Run these commands from `samples/hello-webview`:

```shell
gradle buildPlugin
gradle test
gradle runIde
```

`buildPlugin` resolves the published Marketplace plugin and npm SDK, then creates the plugin ZIP. `runIde` starts a sandbox IDE; open **Hello WebView** to see the button.