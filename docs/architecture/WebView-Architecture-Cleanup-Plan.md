# WebView Runtime Architecture Cleanup

Status: active internal cleanup. This plan lists only work that remains in the current tree; it does not change the supported consumer API shape.

## Remaining Work

| Item | Current state | Target |
| --- | --- | --- |
| Engine message transport | `WebViewEngineBridge` combines `WebViewEngine`, a callback receiver, and `transferToJs` | Replace the asymmetric callback/send pair with one first-class internal transport |
| Theme file naming | `WebViewThemeBridge.kt` contains helper functions rather than a bridge type | Rename around its actual theme responsibility |
| Message bus facade | `WebViewMessageBusInterop` delegates to `WebViewMessageBusImpl` | Let the implementation provide both internal bus behavior and `WebViewInterop` directly |
| Provider creation | `WebViewEngineProvider.createWebView` owns a large default implementation | Extract creation orchestration from the provider contract |
| Runtime metadata | `runtimeInfo(engine)` ignores its engine parameter | Expose immutable provider metadata directly |
| Availability | Providers expose suspend and blocking variants | Reduce to one internal availability contract that fits selection call sites |
| Registration type | Public experimental APIs return `WebViewMessageRegistration` | Decide whether to adopt `AutoCloseable` consistently |
| Low-level calls | `WebViewMessageBus` exposes notifications, while request-response is reached through typed interop | Decide whether a public low-level call descriptor is still needed |
| Internal engine factory | Public exposure is gone, but the internal factory/runtime helpers remain | Remove unused duplication after tests no longer require it |
| Engine interfaces | `WebViewEngine` and `WebViewEngineBridge` remain separate internal types | Merge after transport extraction |
| Message context | `WebViewMessageContext` contains only the method name | Add required metadata or replace it with the narrower value |

`NativeWebViewHostPeer` already has responsibility and shortcut-policy KDoc; no additional cleanup item is needed there.

## Constraints

- Keep `createWebViewPanel`, `WebViewPanel`, asset APIs, and typed `WebViewInterop` behavior stable.
- Keep native bridge types separate from engine abstractions; `Bridge` remains valid for JNI/JNA boundaries.
- Preserve owner-scope cancellation and cleanup ordering.
- Preserve JSON-RPC wire compatibility and browser testkit behavior.
- Do not expose engine selection or backend types to consumer plugins.

## Acceptance Criteria

- Internal type names describe one responsibility each.
- Engine creation has one orchestration path.
- Message transport has explicit ownership, back-pressure, and close semantics.
- Existing typed protocols, frontend tests, plugin packaging, and backend smoke flows continue to work.
- The reference documents are updated when an item lands; completed items are removed from this plan.
