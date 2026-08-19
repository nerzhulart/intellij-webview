# WebView JSON-RPC Runtime

This reference describes the internal message bus used by WebView Runtime. Consumer code should use the typed APIs documented in [Typed Kotlin/TypeScript APIs](WebView-TS-RPC-API-Design.md).

## Transport Contract

The native engine transports complete JSON strings. The message bus owns parsing, dispatch, cancellation, response correlation, and bounded queues.

Supported JSON-RPC 2.0 frame kinds:

- request with `id`, `method`, and optional `params`;
- notification with `method` and optional `params`;
- success response with `id` and `result`;
- error response with `id` and `error`.

Frames without `"jsonrpc": "2.0"` are invalid.

Typed protocol methods use wire names in this form:

```text
namespace/methodName
```

## Calls and Notifications

TypeScript-to-Kotlin request-response methods are registered through `WebViewInterop.implement(...)`. A `suspend` Kotlin method becomes a JSON-RPC call handler and its return value becomes the response result.

Non-suspend `Unit` Kotlin methods become notification handlers. Kotlin-to-TypeScript callable interfaces currently support notifications only; their methods must be non-suspend and return `Unit`.

`WebViewMessageBus` exposes low-level notification descriptors as an escape hatch. New feature contracts should not use it directly.

## Cancellation

The bridge uses the reserved notification:

```text
$/cancelRequest
```

Its payload contains the request ID and an optional message. The bus cancels the matching incoming handler job. Closing the internal bus cancels all active incoming calls and detaches transport processing.

## Error Codes

| Code | Meaning |
| --- | --- |
| `-32600` | Invalid frame |
| `-32601` | Method not found |
| `-32602` | Invalid parameters |
| `-32603` | Internal error |
| `-32800` | Cancelled |

Unexpected handler failures are converted to an internal error and logged. Notification failures are logged without poisoning later messages.

## Queueing and Lifecycle

Incoming and outgoing frame channels have a capacity of 128. Native callbacks cannot suspend, so inbound overflow is logged and dropped. Outgoing notifications apply coroutine back-pressure.

The owner scope controls the complete lifecycle. Cancelling it stops frame production, cancels handlers, and closes the internal bus and engine.

## Reserved Methods

Methods beginning with `$/` belong to runtime infrastructure. Current examples include cancellation, browser console forwarding, theme/runtime coordination, and readiness-related traffic. Feature protocols must not claim this prefix.

## Required Behavior Coverage

Maintain coverage for:

- request/response success and typed serialization failures;
- method-not-found and handler errors;
- remote cancellation and scope-cancellation cleanup;
- duplicate method registration;
- unknown response IDs;
- notification failure isolation;
- namespace and method validation;
- bounded-queue behavior.

Kotlin test sources for this behavior are retained under `tests/`, but are not yet wired into the standalone Gradle build. See [Standalone Development and Verification](../guides/Standalone-Development-and-Verification.md).
