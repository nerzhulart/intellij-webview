# WebView Frontend View Model Patterns

Treat the WebView page as a client of a typed plugin API. Kotlin owns IDE state and privileged operations; the frontend owns presentation state and browser interactions.

## Contract Boundary

Define small serializable DTOs and typed APIs on both sides. Keep the namespace stable and match it exactly:

```kotlin
interface SettingsHostApi : WebViewImplementable {
  companion object {
    val ID = WebViewApiId.of<SettingsHostApi>("settings.host")
  }

  suspend fun load(): SettingsSnapshot
  suspend fun save(request: SaveSettingsRequest)
}
```

```ts
interface SettingsHostApi extends WebViewCallable {
  load(): Promise<SettingsSnapshot>
  save(request: SaveSettingsRequest): Promise<void>
}

const settingsHostApiId = apiId<SettingsHostApi>()("settings.host")
const host = webView.callable(settingsHostApiId)
```

Do not mirror IDE services, mutable model objects, or framework stores across the bridge.

## State Patterns

| State shape | Recommended protocol |
| --- | --- |
| Initial page state | One `load()` snapshot call |
| User command | Host method with a small request DTO |
| Occasional host change | Page API notification implemented with `webView.implement(...)` |
| Long-running operation | Start/cancel host methods plus progress notifications |
| Editable form | Local draft, explicit save, then authoritative host snapshot |
| Large collection | Query/filter/page on the host; send view DTOs only |

Prefer a snapshot followed by incremental notifications when state changes after startup. Include stable IDs and enough ordering/version information to reject stale updates when calls can overlap.

## Frontend Ownership

The frontend may own:

- selection, expanded rows, tabs, and temporary input;
- optimistic presentation state that can be reconciled with a host response;
- derived values used only for rendering.

Kotlin should own:

- project and IDE state;
- filesystem, process, editor, and service access;
- permissions and validation;
- operation lifetime and cancellation.

## Lifecycle

Register page implementations during module startup and dispose the returned registration with the view lifecycle. Let the `CoroutineScope` passed to `createWebViewPanel(...)` own Kotlin-side work. On reload, rebuild transient frontend state from host snapshots instead of relying on the old page instance.

## Testing

Test reducers and view-model functions without a browser where possible. Use typed testkit mocks for RPC behavior and Playwright for meaningful user flows. Native and lifecycle behavior still requires Kotlin/native coverage.
