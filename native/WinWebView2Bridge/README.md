# Windows WebView2 Native Bridge

This Rust `cdylib` implements the Windows WebView2 backend used by the external WebView Runtime plugin. The built library is `win_webview2_bridge.dll`.

## Prerequisites

- Windows with PowerShell 7 (`pwsh`);
- Rust installed through `rustup` with an MSVC toolchain;
- Visual Studio 2022 Build Tools or Community with Desktop development with C++ and a Windows SDK;
- ARM64 MSVC cross-build tools when building `aarch64-pc-windows-msvc` from an x64 host.

Install both Rust targets before an all-architecture build:

```powershell
rustup target add x86_64-pc-windows-msvc aarch64-pc-windows-msvc
```

The bridge links the static WebView2 loader, so it does not need a separate loader DLL.

## Build

Run from the repository root:

```powershell
pwsh -File native/WinWebView2Bridge/build.ps1 -All
```

In IntelliJ IDEA, enable the **Shell Script** plugin, select the shared **Build Windows Native Libraries**
run configuration, and click **Run**. It runs this script with `-All` without Java or Gradle.
The interpreter path uses the per-user Windows app alias for PowerShell 7; if PowerShell is installed
elsewhere, change **Interpreter path** in **Run | Edit Configurations** to your `pwsh.exe`.

For one architecture:

```powershell
pwsh -File native/WinWebView2Bridge/build.ps1 -Target x86_64-pc-windows-msvc
pwsh -File native/WinWebView2Bridge/build.ps1 -Target aarch64-pc-windows-msvc
```

Cargo output is written beneath `native/WinWebView2Bridge/target/<rust-target>/release/`. The script copies runtime artifacts to:

```text
lib/webview-native/win/x86_64/win_webview2_bridge.dll
lib/webview-native/win/aarch64/win_webview2_bridge.dll
```

Stop any IDE process that has loaded the DLL before copying; Windows locks loaded libraries.

## ARM64 Toolchain

The script locates `vcvarsall.bat` and uses the `x64_arm64` environment when possible. If discovery fails, initialize that Visual Studio developer environment manually and rerun the ARM64 command.

## ABI Changes

The source requires ABI `wvi-awt-canvas-host-v19`. `createNative` keeps the existing argument order, with `features: Int` inserted **between `backgroundColor` and `callbacks`**:

```text
createNative(parentHwnd, generation, userDataDir, documentStartScript, backgroundColor, features, callbacks)
```

Feature bits are `CONTEXT_MENU=1`, `ZOOM=2`, `SWIPE=4`, `ACCELERATORS=8`, `ERROR_PAGE=16`, and `AUTOFILL=32`. A zero mask preserves application-mode settings; unrelated settings remain unchanged.

The ABI adds `goBackNative`, `goForwardNative`, `reloadNative`, and `stopNative`, each taking the native handle. The callback object must implement:

```text
onNavigationStarting(String)
onNavigationCompleted(Boolean, Int)
onSourceChanged(String)
onDocumentTitleChanged(String)
onHistoryChanged(Boolean, Boolean)
```

Only the current WebView2 navigation ID can emit a completion callback. Cancellation is forwarded as `onNavigationCompleted(false, 14)` (`COREWEBVIEW2_WEB_ERROR_STATUS_OPERATION_CANCELED`); Kotlin must finish loading without creating a browser/network error for this status. Navigation event subscriptions are removed on destruction and on partial subscription failure.

The Kotlin loader rejects a DLL whose ABI sentinel differs from its expected value. Whenever JNI methods, callback signatures, or native/Kotlin boundary semantics change:

1. update the native ABI value in `src/lib.rs`;
2. update the matching value in `WinWebView2Bridge.kt`;
3. rebuild both architectures;
4. verify the runtime ZIP contains both updated DLLs.

### Required v19 DLL rebuild

The v19 source was checked on macOS against both Windows MSVC targets. The release build could not link because `link.exe` is unavailable, so the bundled DLLs were **not** rebuilt. Do not ship the existing DLLs with the v19 Kotlin bridge: the ABI check must reject them.

On Windows with the prerequisites above, stop any IDE using the DLLs and run these exact commands from the repository root:

```powershell
rustup target add x86_64-pc-windows-msvc aarch64-pc-windows-msvc
pwsh -File native/WinWebView2Bridge/build.ps1 -All
```

This rebuilds and copies both architectures to `lib/webview-native/win/`. Both DLLs and Kotlin's `EXPECTED_NATIVE_ABI_VERSION` must agree on `wvi-awt-canvas-host-v19` before integrated validation or packaging.

## Verification

```powershell
cargo fmt --manifest-path native/WinWebView2Bridge/Cargo.toml --check
cargo check --manifest-path native/WinWebView2Bridge/Cargo.toml --target x86_64-pc-windows-msvc --tests --locked
cargo check --manifest-path native/WinWebView2Bridge/Cargo.toml --target aarch64-pc-windows-msvc --tests --locked
cargo test --manifest-path native/WinWebView2Bridge/Cargo.toml --target x86_64-pc-windows-msvc --locked
pwsh -File native/WinWebView2Bridge/build.ps1 -All
.\gradlew.bat buildPlugin
```

The navigation-ID state tests can also run on macOS/Linux without WebView2. From the repository root, after a Cargo check has created the target directory:

```sh
rustc --edition=2021 --test native/WinWebView2Bridge/src/navigation.rs -o native/WinWebView2Bridge/target/navigation-tests
native/WinWebView2Bridge/target/navigation-tests
```

These tests cover stale, duplicate, redirected, and unidentified navigations. A host-only `cargo check` on macOS/Linux does **not** validate the Windows bridge body because `src/lib.rs` is gated by `cfg(target_os = "windows")`; use the explicit Windows targets above. Cross-target checks do not link a DLL or execute WebView2/JNI interaction tests.

Use `./gradlew runIde` for Windows interaction checks. The standalone Gradle build does not currently register the Kotlin smoke-test sources under `tests/` as executable test tasks.

Runtime behavior is documented in [Windows WebView2 Runtime](../../docs/backends/windows-webview2-runtime.md).
