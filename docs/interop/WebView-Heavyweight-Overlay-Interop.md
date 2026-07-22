# WebView Heavyweight Overlay Interop

Native browser hosts can appear above lightweight Swing painting. WebView Runtime provides an internal facade for balloons and other `HwFacadeJPanel`-based overlays that intersect a native WebView host.

## Current Behavior

- `SwingWebViewHostPanel` registers visible native hosts in `WebViewHeavyweightHostRegistry`.
- `WebViewHwFacadeProvider` detects overlap and creates a transparent popup window for the target Swing overlay.
- The facade paints through an alpha-cleared back buffer to avoid rectangular shadow artifacts.
- Mouse press, drag, release, click, motion, and wheel events are redispatched to the original enabled Swing target.
- All registry and facade state is EDT-owned.

The behavior is controlled by `ide.webview.heavyweight.hwfacade.enabled` and is enabled by default.

## Scope

The implementation covers existing `HwFacadeJPanel` consumers such as notification balloons. It does not claim complete support for every menu, popup, hint, or arbitrary layered-pane component.

Do not add a broad overlay abstraction without a concrete failing UI path. A new integration should first prove overlap, z-order, input, focus, and teardown behavior with the WebView host involved.

## Verification

For changes, validate:

- overlapping and non-overlapping host detection;
- hidden, detached, zero-size, descendant, and other-window cases;
- transparent painting without a rectangular border;
- nested and disabled-component mouse redispatch;
- facade cleanup when either overlay or WebView host disappears.

The repository contains focused Kotlin tests under `tests/testSrc`, but the standalone Gradle build does not yet register them as executable test tasks. Run the demo plugin locally for visual and input verification until that wiring exists.
