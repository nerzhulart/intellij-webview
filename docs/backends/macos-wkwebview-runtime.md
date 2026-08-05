# macOS WKWebView Runtime

On macOS, the system engine provider embeds WKWebView in the Swing host and reports support for bundled assets, typed messaging, Swing embedding, and interactive input.

## Runtime

- AppKit and WKWebView operations run on the macOS main thread.
- The Swing host owns component geometry and lifecycle on the EDT.
- Each attached host owns a clipping `NSView` under `NSWindow.contentView`; the `WKWebView` is a child of that container.
- The engine serves bundled resources through a WKURL scheme handler and an isolated virtual origin.
- Page-to-host messaging uses a WK script message handler; host-to-page delivery uses JavaScript evaluation.

```text
NSWindow.contentView
└── clipping NSView
    └── WKWebView
```

The clipping container follows the visible Swing rectangle and masks the full-size `WKWebView` to its bounds. It is window-owned because a JBR `Canvas` supplies a `CALayer`, not a Canvas-owned `NSView` suitable for parenting `WKWebView`.

Consumer code uses `createWebViewPanel(...)`; the native bridge is internal.

## Application Behavior

WKWebView is configured as an embedded application surface:

- JavaScript and the message bridge stay enabled.
- user-opened browser windows, navigation gestures, page magnification, and developer entry points are disabled where WKWebView exposes a setting;
- rubber-band overscroll and credential storage are disabled with guarded selectors when available;
- a document-start script suppresses the default context menu and disables browser input-assist hints in light DOM and open shadow roots.

Closed shadow roots remain owned by their components and must configure their own input behavior.

## Constraints

- AppKit main and AWT EDT may be the same thread on some JBR configurations and distinct on others; code must not assume either identity.
- Guarded private selectors are best effort and must not make WebView creation fail when absent.
- Browser page zoom is disabled. A graph, canvas, or similar component may implement local zoom inside the page.

Verify native behavior on a macOS host through `./gradlew runIde` and exercise asset loading, bridge calls, focus traversal, typing, reload, and teardown.
