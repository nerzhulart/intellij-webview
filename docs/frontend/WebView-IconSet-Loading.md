# WebView Icon Sets

An icon set exposes SVG or PNG classpath resources to one WebView asset root. Kotlin fixes the source classloader; TypeScript generates theme-aware asset URLs.

## Register on Kotlin Side

Use a stable ID and an owner class from the plugin that contains the resources:

```kotlin
val assetRoot = WebViewAssetRoot.forView("chat")
  .withIconSets(
    WebViewIconSet.of("ChatIcons", ChatPanel::class.java),
    WebViewIconSet.allIcons(),
  )
```

`WebViewIconSet.of(id, owner)` resolves resources from `owner.classLoader`. IDs must match `[A-Za-z][A-Za-z0-9._-]*`, and duplicate IDs on one asset root are rejected.

## Use in TypeScript

Define the same ID:

```ts
import { AllIcons, IconSet } from "@nerzhulart/intellij-webview-sdk"

const ChatIcons = IconSet.define("ChatIcons")

const send = ChatIcons.src("icons/send.svg")
const refresh = AllIcons.src("expui/actions/forceRefresh.svg")
```

`AllIcons` matches `WebViewIconSet.allIcons()`. A custom `IconSet.define("ChatIcons")` must have a corresponding Kotlin registration with exactly that ID.

## Resolution

Generated URLs use the internal `./__ij-icons/<id>/<theme>/<path>` route. The runtime validates the set ID and resource path, then resolves a light or dark resource from the registered classloader. Supported source formats are SVG and PNG.

Resource paths must be relative, normalized, and free of traversal or URL syntax. Missing or unregistered resources return an asset error; they do not fall back to arbitrary files.

## Browser Preview

The testkit resolves the same icon URL shape for browser previews. Keep mock-visible icon resources beneath the view resource root and use the real `IconSet` API in production code; do not add a preview-only icon branch.

## Verification

- Verify both light and dark themes.
- Verify the same icon in a browser preview and an installed plugin.
- Keep icon-set IDs stable because they are part of the frontend/Kotlin contract.
