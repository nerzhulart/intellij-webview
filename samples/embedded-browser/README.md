# Embedded Browser Sample

An IntelliJ plugin subproject demonstrating `createEmbeddedBrowserPanel(...)` in the right-anchored **Embedded Browser** tool window. It opens `https://www.jetbrains.com` with the runtime's Swing navigation toolbar and native browser surface.

## Run

From the repository root, after the root build includes `:samples:embedded-browser` and routes its shared sandbox through this sample's `prepareSandbox`:

```sh
./gradlew runIde
```

Open a project in the sandbox IDE, then select **View | Tool Windows | Embedded Browser**. The window does not activate automatically.

The sample can also launch its own sandbox directly from the repository root:

```sh
./gradlew :samples:embedded-browser:runIde
```

This is a subproject of the repository build, not a separate standalone Gradle build. It uses the root `pluginVersion`, `platformVersion`, and JDK 25 toolchain conventions.

## Implementation

- `EmbeddedBrowserPanelOptions.browserOptions` sets the initial URL and `WebViewCreationOptions.debugName = "embedded-browser"`.
- `WebViewFeatures.BROWSER.copy(elasticScrolling = true)` demonstrates customizing the browser preset; elastic scrolling applies on supported backends.
- The project-level `EmbeddedBrowserScopeService` receives an IDE-managed `CoroutineScope`. Each content gets a child scope, cancelled by its content disposer; project disposal also cancels it.
- Browser creation and Swing updates run on EDT. Cancellation is rethrown before handling `IllegalStateException`, and cancellation checks prevent a cancelled creation from updating the container. An initialization `IllegalStateException` is displayed with `JBLabel`.
- No frontend plugin, WebView asset build, local HTTP server, page SDK, or custom bridge is needed in this sample.

## Sandbox dependencies

The sample declares `localPlugin(project(":"))` for the runtime. It also includes `:demo`, `:markdown-preview`, and the bundled `org.intellij.plugins.markdown` plugin so its `prepareSandbox` can serve the root all-samples `runIde` without modifying the demo. Only the runtime is a required dependency in this sample's plugin descriptor; the others are sandbox companions. Their existing asset builds may still run when preparing the shared sandbox.

The parent integration must include `:samples:embedded-browser` in root settings and point root `runIde` at this sample's `prepareSandbox`, including its sandbox directory properties and task dependency. JCEF is already packaged as the root runtime's optional `:jcef` module; no separate JCEF plugin dependency is needed here.

## Manual smoke checks

1. Open **Embedded Browser** and verify the JetBrains page and navigation toolbar appear.
2. Enter another HTTPS URL, navigate back and forward, and reload. Check that the URL and navigation button states follow the page.
3. Close the project while the browser is initializing or loading; confirm there is no cancellation error or late UI update. Reopen a project and verify a fresh browser content is created.
4. With no compatible browser engine available, verify an initialization failure shows **Embedded browser unavailable** instead of leaving the loading label.

Windows validation requires the native Windows v19 DLL rebuild. That rebuild is deferred to the user on another machine; this sample does not rebuild or replace native binaries.