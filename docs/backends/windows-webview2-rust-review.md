# Windows Native Bridge Review

This review tracks unresolved findings in `native/WinWebView2Bridge/src/lib.rs`. Remove an item when its fix and regression coverage land.

## Priority Findings

| ID | Remaining issue | Required outcome |
| --- | --- | --- |
| C2 | IPC and accelerator callbacks hold a `RefCell` borrow while invoking Kotlin | Clone the callback owner, release the borrow, then cross JNI so reentrant bridge calls cannot fail silently |
| C3 | `js_string_literal` leaves most U+0000..U+001F characters unescaped | Emit valid JavaScript/JSON escapes and add a control-character transfer test |
| H1 | Raw `Rc<RefCell<_>>` handles rely on single-thread access without a native assertion | Record and assert the owning OS thread; coordinate with the threading follow-up |
| H2 | `web_message_token` is not removed explicitly during destroy | Store it as an optional token and unregister it symmetrically |
| H3 | Native attach hides the child window and relies on Kotlin to restore visibility | Restore the recorded visibility or make the native contract explicit and covered |

## Additional Cleanup

- Consolidate repeated mutable borrows in creation-failure cleanup.
- Report asset-handler borrow failures instead of silently dropping the error.
- Use human-readable Windows error formatting.
- Pair or explicitly document COM initialization lifetime on the dedicated thread.
- Make attach/detach error handling consistent.
- Register the container window class once.
- Log failed host-to-page script delivery.
- Add a defensive `Drop` path or prove that explicit destruction owns every exit.

These are native implementation concerns; they must not change the consumer Kotlin or TypeScript API.

## Verification

Run on Windows:

```powershell
cargo fmt --manifest-path native/WinWebView2Bridge/Cargo.toml --check
cargo check --manifest-path native/WinWebView2Bridge/Cargo.toml --target x86_64-pc-windows-msvc
pwsh -File native/WinWebView2Bridge/build.ps1 -All
.\gradlew.bat buildPlugin
```

Then use `runIde` to exercise creation failure, page-to-host and host-to-page messages, asset requests, keyboard handling, focus, detach/reattach, and repeated close. The standalone Gradle build does not yet expose the Kotlin smoke sources as test tasks; wiring them is required before replacing manual runtime coverage with a documented command.

## Acceptance Criteria

- All priority findings are removed from the source pattern and covered where practical.
- JNI exceptions and Rust panics cannot unwind across FFI or disappear without a diagnostic.
- Native and Kotlin ABI sentinels match for both shipped architectures.
- Repeated create/close and forced creation failures do not leak HWNDs, handlers, or native views.
