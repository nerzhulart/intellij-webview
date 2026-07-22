# Typed Kotlin and TypeScript WebView APIs

Consumer plugins define WebView protocols as matching Kotlin and TypeScript interfaces. The runtime derives wire method names, serializers, dispatch entries, and proxies from those declarations.

## Roles

| Role | Meaning |
| --- | --- |
| `WebViewImplementable` | Implemented on this side; receives calls or notifications |
| `WebViewCallable` | Implemented on the other side; invoked through a proxy |

Use opposite roles for the same interface across the boundary.

```text
Kotlin implements -> Kotlin WebViewImplementable, TypeScript WebViewCallable
TypeScript implements -> Kotlin WebViewCallable, TypeScript WebViewImplementable
```

## API IDs

Declare one typed ID per protocol interface.

Kotlin:

```kotlin
interface EditorHostApi : WebViewImplementable {
  companion object {
    val ID: WebViewApiId<EditorHostApi> = WebViewApiId.of("editor.host")
  }

  suspend fun openFile(params: OpenFileRequest): OpenFileResult
}
```

TypeScript:

```ts
interface EditorHostApi extends WebViewCallable {
  openFile(params: OpenFileRequest): Promise<OpenFileResult>
}

export const editorHostApiId = apiId<EditorHostApi>()("editor.host")
```

Namespaces may contain ASCII letters, digits, `_`, `-`, and `.`. They must not start or end with `.` or `/`. The wire method is always `namespace/methodName`.

## Implement an API in Kotlin

```kotlin
@Serializable
data class OpenFileRequest(val path: String, val line: Int? = null)

@Serializable
data class OpenFileResult(val opened: Boolean)

class EditorHostApiImpl : EditorHostApi {
  override suspend fun openFile(params: OpenFileRequest): OpenFileResult {
    return OpenFileResult(opened = true)
  }
}

val registration = panel.interop.implement(EditorHostApi.ID, EditorHostApiImpl())
```

Close the returned registration if it has a shorter lifetime than the panel.

## Call Kotlin from TypeScript

```ts
import { apiId, webView, type WebViewCallable } from "@jetbrains/intellij-webview"

type OpenFileRequest = { path: string; line?: number }
type OpenFileResult = { opened: boolean }

interface EditorHostApi extends WebViewCallable {
  openFile(params: OpenFileRequest): Promise<OpenFileResult>
}

const editorHostApiId = apiId<EditorHostApi>()("editor.host")
const host = webView.callable(editorHostApiId)
const result = await host.openFile({ path: "src/Main.kt" })
```

An `AbortSignal` may be supplied by lower-level bridge call options when a caller needs cancellation. Normal feature wrappers should hide that detail behind domain functions.

## Notify TypeScript from Kotlin

Kotlin callable methods are notifications and therefore non-suspend `Unit` methods:

```kotlin
@Serializable
data class SelectionChanged(val start: Int, val end: Int)

interface EditorPageApi : WebViewCallable {
  companion object {
    val ID: WebViewApiId<EditorPageApi> = WebViewApiId.of("editor.page")
  }

  fun selectionChanged(params: SelectionChanged)
}

panel.interop.callable(EditorPageApi.ID).selectionChanged(selection)
```

TypeScript implements the matching protocol:

```ts
import { apiId, webView, type WebViewImplementable } from "@jetbrains/intellij-webview"

interface EditorPageApi extends WebViewImplementable {
  selectionChanged(params: { start: number; end: number }): void
}

const editorPageApiId = apiId<EditorPageApi>()("editor.page")
const registration = webView.implement(editorPageApiId, {
  selectionChanged(selection) {
    store.setSelection(selection)
  },
})
```

## Method Rules

- Protocol types must be interfaces.
- Runtime reflection uses public methods declared directly in the Kotlin interface.
- A method has zero value parameters or one non-null serializable parameter object.
- Kotlin incoming calls are `suspend` and may return a serializable result.
- Kotlin outgoing methods are currently notification-only: non-suspend and `Unit`.
- TypeScript request-response methods return `Promise<Result>`.
- TypeScript notification methods return `void`.
- Overloads and unsupported property members are rejected.

## DTO Rules

Cross-boundary DTOs contain serialized values only:

- primitives and nullable fields where the serializer permits them;
- lists and maps;
- stable IDs;
- nested DTOs.

Do not send Swing components, services, `VirtualFile`, domain entities, coroutine types, or reactive streams.

## Protocol Module Pattern

Keep IDs, DTOs, interfaces, and small callable wrappers together. UI components import this module instead of using raw bridge globals or method strings.

```ts
export function editorHostApi() {
  return webView.callable(editorHostApiId)
}
```

Browser mocks implement the same typed IDs through `@jetbrains/intellij-webview-testkit`; production code does not switch into a mock mode.

## Review Checklist

- Kotlin and TypeScript use the same namespace.
- Method names and DTO JSON shapes match.
- Roles are opposite across the boundary.
- Every call uses `suspend`/`Promise`.
- Every notification uses `Unit`/`void`.
- Feature code contains no raw wire method strings.
- Registration lifetime is bounded by the owning view or scope.
