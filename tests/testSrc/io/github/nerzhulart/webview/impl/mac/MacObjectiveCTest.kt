// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl.mac

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@EnabledOnOs(OS.MAC)
internal class MacObjectiveCTest {
  @Test
  fun resolvesObjectiveCClassesAndRoundTripsStrings() {
    assertFalse(MacObjectiveC.isNil(MacObjectiveC.getObjcClass("NSObject")))

    val value = "WebView — 日本語 — 🚀"
    assertEquals(value, MacObjectiveC.toStringViaUTF8(MacObjectiveC.nsString(value)))
  }

  @Test
  fun dispatchesToAppKitMainThread() {
    val completed = CountDownLatch(1)
    val ranOnMainThread = AtomicBoolean()

    MacObjectiveC.executeOnMainThread {
      val nsThread = MacObjectiveC.getObjcClass("NSThread")
      ranOnMainThread.set(MacObjectiveC.invoke(nsThread, "isMainThread").booleanValue())
      completed.countDown()
    }

    assertTrue(completed.await(10, TimeUnit.SECONDS), "AppKit main-thread callback did not run")
    assertTrue(ranOnMainThread.get(), "Callback did not run on the AppKit main thread")
  }
}