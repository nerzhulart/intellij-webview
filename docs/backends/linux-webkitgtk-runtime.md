# Linux WebKitGTK Runtime

The WebKitGTK implementation is currently disabled and is not selected by `createWebViewPanel(...)`. Asset-backed WebView panels on Linux use JCEF.

## Existing Scaffold

The repository contains an experimental Rust/JNI bridge plus Kotlin engine and host-peer code. The Wayland path can render offscreen snapshots, evaluate JavaScript, and exchange bridge messages, but it does not provide interactive mouse or keyboard input and cannot serve bundled assets. The X11 path is not enabled.

`LinuxWebKitEngineProvider` therefore reports the backend as unavailable. This prevents a display-only implementation from being advertised as a usable WebView UI engine.

## Native Build

For development of the scaffold:

```shell
cargo build --manifest-path native/LinuxWebKitGtkBridge/Cargo.toml
```

The system must provide GTK3 and WebKitGTK 4.1 development libraries. A future packaged bridge belongs under `lib/webview-native/linux/<arch>/` and must match the ABI expected by `LinuxWebKitGtkBridge.kt`.

## Requirements Before Enablement

- interactive keyboard, mouse, focus, and IME behavior;
- bundled asset serving through the common resolver;
- tested Wayland and/or X11 embedding with explicit availability rules;
- lifecycle, reload, and teardown coverage on supported distributions;
- a packaged native library for every advertised architecture;
- parity with the typed bridge and focus behavior used by supported engines.

Until these criteria are met, Linux documentation and consumer examples must identify JCEF as the supported engine.
