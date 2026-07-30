# Standalone Development and Verification

Use this guide when changing WebView Runtime itself. The repository builds against a binary IntelliJ SDK and does not require an IntelliJ source checkout.

## Prerequisites

- A JDK whose major version matches both the selected binary IntelliJ SDK and the `jvmToolchain` declared in the Gradle build.
- The Bun version declared by `bunVersion` in `gradle.properties`.
- A local Gradle matching `gradle/wrapper/gradle-wrapper.properties` for the one-time wrapper bootstrap described below.
- Rust and native build tools only when changing a native bridge.

Do not copy a JDK version into additional documentation. When the target IDE changes, update these sources of truth together:

1. `platformVersion` in `gradle.properties`;
2. Gradle `jvmToolchain` and Kotlin `jvmTarget` declarations;
3. the Java runtime in `.github/workflows/build.yml`;
4. the IDE project SDK configuration.

## Bootstrap the Gradle Wrapper

The wrapper scripts and properties are committed, but `gradle-wrapper.jar` is not. In a clean checkout, use a locally installed Gradle whose version matches `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties`:

```shell
gradle wrapper --gradle-version 9.1.0 --distribution-type bin
```

This is the same bootstrap performed by the build workflow. After it succeeds, use `./gradlew` or `gradlew.bat` for all repository tasks. When the Gradle distribution changes, update the command here and the CI bootstrap together.

## Build Plugin Archives

From the repository root:

```shell
./gradlew buildPlugin :demo:buildPlugin :markdown-preview:buildPlugin
```

On Windows:

```powershell
.\gradlew.bat buildPlugin :demo:buildPlugin :markdown-preview:buildPlugin
```

Gradle downloads the binary IDE dependency, installs locked frontend dependencies, builds every WebView bundle, and packages three plugin ZIPs:

- runtime: `build/distributions/`;
- demo: `demo/build/distributions/`;
- Markdown preview: `markdown-preview/build/distributions/`.

Build only generated frontend resources with:

```shell
./gradlew buildAllWebViewAssets
```

Generated production resources are placed under each module's `build/generated-resources/webview/main` directory and are included by `processResources`. Do not commit direct Bun output under `resources/webview`.

## Run the Demo IDE

```shell
./gradlew runIde
```

The root task uses the demo sandbox, which installs the runtime, demo, and Markdown preview plugins together. Open the **WebView Demo** tool window to exercise the sample views.

## Work on Frontend Packages

Run commands from the package that owns the changed sources.

Shared runtime, build helpers, and controls:

```shell
cd webview-src
bun install --frozen-lockfile
bun run typecheck
bun test
bun run build
```

Demo views:

```shell
cd demo/webview-src
bun install --frozen-lockfile
bun run typecheck
bun run build
bun run test:browser
```

Markdown preview:

```shell
cd markdown-preview/webview-src
bun install --frozen-lockfile
bun run typecheck
bun run build
bun run test:browser
```

After changing a workspace package consumed through a local `file:` dependency, run `bun install` in each affected consumer before testing `node_modules`-based behavior.

## Preview a View Without the IDE

The ACP chat reference preview can be started from `demo/webview-src`:

```shell
bun run preview:acp-chat
```

Parameterized previews use:

```shell
bun webview-preview acp-chat --mock default
```

Preview scripts use Bun and `@nerzhulart/webview-testkit`; production view code stays free of mock branches.

## Build Native Bridges

### Windows WebView2

From the repository root on Windows:

```powershell
pwsh -File native/WinWebView2Bridge/build.ps1 -All
cargo check --manifest-path native/WinWebView2Bridge/Cargo.toml
```

The script copies release DLLs into `lib/webview-native/win/<arch>/`. Stop any IDE process that has loaded a DLL before replacing it.

### Linux WebKitGTK

```shell
cargo build --manifest-path native/LinuxWebKitGtkBridge/Cargo.toml
```

The Cargo command does not enable the backend. If developing the disabled scaffold locally, place the library under `lib/webview-native/linux/<arch>/` and use an explicit development-only provider change; normal Linux panels continue to use JCEF.

## Kotlin Test Sources

The `tests/` tree was retained during repository extraction, but its IDE test-framework dependencies are not wired into the standalone Gradle build. Do not document source-checkout test commands as runnable here.

Until standalone Kotlin tests are added:

- use the three-plugin Gradle build as the packaging check;
- use frontend unit and Playwright tests for browser behavior;
- use `runIde` for IDE integration smoke testing;
- run native checks on the matching host OS.

## CI and Releases

The manually triggered **Build plugins** workflow takes a release version and produces the three plugin ZIPs plus the matching SDK and testkit npm tarballs. It also installs those tarballs in a clean fixture and verifies their public exports, Vite build, and mock preview.

The **Publish selected build** workflow validates a successful build run, publishes its npm tarballs, and creates or reconciles the GitHub Release containing the exact three ZIPs. See [Publish the WebView npm Packages](Publish-NPM-Packages.md) for first-release authentication, trusted publishing, normal releases, and retries.

The workflows do not publish to JetBrains Marketplace.

## Documentation Verification

For documentation changes:

1. verify every referenced path exists;
2. check relative Markdown links and anchors;
3. ensure commands match current Gradle tasks and package scripts;
4. search live documentation for obsolete source-layout paths and unsupported commands;
5. run the full plugin build when setup and build instructions changed.
