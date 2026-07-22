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

The Kotlin loader rejects a DLL whose ABI sentinel differs from its expected value. Whenever JNI methods, callback signatures, or native/Kotlin boundary semantics change:

1. update the native ABI value in `src/lib.rs`;
2. update the matching value in `WinWebView2Bridge.kt`;
3. rebuild both architectures;
4. verify the runtime ZIP contains both updated DLLs.

## Verification

```powershell
cargo fmt --manifest-path native/WinWebView2Bridge/Cargo.toml --check
cargo check --manifest-path native/WinWebView2Bridge/Cargo.toml --target x86_64-pc-windows-msvc
pwsh -File native/WinWebView2Bridge/build.ps1 -All
.\gradlew.bat buildPlugin
```

Use `./gradlew runIde` for Windows interaction checks. The standalone Gradle build does not currently register the Kotlin smoke-test sources under `tests/` as executable test tasks.

Runtime behavior is documented in [Windows WebView2 Runtime](../../docs/backends/windows-webview2-runtime.md).
