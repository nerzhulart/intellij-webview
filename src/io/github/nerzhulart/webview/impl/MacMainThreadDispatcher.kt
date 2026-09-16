// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl

import io.github.nerzhulart.webview.impl.mac.MacObjectiveC
import kotlinx.coroutines.CoroutineDispatcher
import org.jetbrains.annotations.ApiStatus
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine dispatcher that executes blocks on the macOS AppKit main thread
 * via the local Objective-C runtime bridge.
 *
 * This is required because `WKWebView` has strict thread affinity — all API calls
 * (create, load, evaluate, destroy) must happen on the macOS main thread.
 */
@ApiStatus.Internal
internal object MacMainThreadDispatcher : CoroutineDispatcher() {
  override fun dispatch(context: CoroutineContext, block: Runnable) {
    MacObjectiveC.executeOnMainThread(block)
  }
}
