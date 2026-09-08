// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview

import io.github.nerzhulart.webview.api.WebViewBrowserError
import io.github.nerzhulart.webview.api.WebViewBrowserState
import io.github.nerzhulart.webview.impl.engine.WebViewNavigationStateTracker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class WebViewNavigationStateTrackerTest {
  @Test
  fun initialState_isEmpty() {
    val tracker = WebViewNavigationStateTracker()

    assertEquals(WebViewBrowserState.EMPTY, tracker.state.value)
    assertNull(tracker.state.value.url)
    assertNull(tracker.state.value.title)
    assertFalse(tracker.state.value.isLoading)
    assertEquals(0.0, tracker.state.value.progress)
    assertFalse(tracker.state.value.canGoBack)
    assertFalse(tracker.state.value.canGoForward)
    assertNull(tracker.state.value.error)
  }

  @Test
  fun navigation_updatesOnlyTheFieldsReportedByEachCallback() {
    val tracker = WebViewNavigationStateTracker()
    val initialState = tracker.state.value
    var expected = WebViewBrowserState.EMPTY.copy(url = "https://example.com", isLoading = true)

    tracker.onNavigationStarted("https://example.com")
    assertEquals(expected, tracker.state.value)

    tracker.onProgressChanged(0.5)
    expected = expected.copy(progress = 0.5)
    assertEquals(expected, tracker.state.value)

    tracker.onTitleChanged("Example")
    expected = expected.copy(title = "Example")
    assertEquals(expected, tracker.state.value)

    tracker.onHistoryChanged(canGoBack = true, canGoForward = false)
    expected = expected.copy(canGoBack = true)
    assertEquals(expected, tracker.state.value)

    tracker.onUrlChanged("https://example.com/redirected")
    expected = expected.copy(url = "https://example.com/redirected")
    assertEquals(expected, tracker.state.value)

    tracker.onNavigationFinished("https://example.com/final", null)
    expected = expected.copy(url = "https://example.com/final", isLoading = false, progress = 1.0)
    assertEquals(expected, tracker.state.value)
    assertEquals(WebViewBrowserState.EMPTY, initialState)
  }

  @Test
  fun failedNavigation_recordsErrorUntilNextNavigationStarts() {
    val tracker = WebViewNavigationStateTracker()
    val url = "https://example.com/unavailable"
    val error = WebViewBrowserError(url = url, message = "Connection refused", code = -102)
    tracker.onNavigationStarted(url)
    tracker.onTitleChanged("Previous title")
    tracker.onHistoryChanged(canGoBack = true, canGoForward = true)
    tracker.onProgressChanged(0.25)

    tracker.onNavigationFinished(url, error)
    val failedState = tracker.state.value
    assertEquals(error, failedState.error)
    assertEquals(url, failedState.url)
    assertFalse(failedState.isLoading)
    assertEquals(1.0, failedState.progress)

    tracker.onTitleChanged("Failed page")
    tracker.onHistoryChanged(canGoBack = true, canGoForward = false)
    assertEquals(error, tracker.state.value.error)

    val beforeRetry = tracker.state.value
    tracker.onNavigationStarted("https://example.com/retry")
    assertEquals(
      beforeRetry.copy(url = "https://example.com/retry", isLoading = true, progress = 0.0, error = null),
      tracker.state.value,
    )
    assertEquals(error, failedState.error)
  }

  @Test
  fun nullUrls_preserveTheLastKnownUrl() {
    val tracker = WebViewNavigationStateTracker()
    tracker.onUrlChanged(null)
    assertEquals(WebViewBrowserState.EMPTY, tracker.state.value)

    tracker.onUrlChanged("https://example.com")
    val knownState = tracker.state.value
    tracker.onUrlChanged(null)
    assertEquals(knownState, tracker.state.value)

    tracker.onNavigationStarted(null)
    assertEquals(knownState.copy(isLoading = true), tracker.state.value)

    tracker.onNavigationFinished(null, null)
    assertEquals(knownState.copy(progress = 1.0), tracker.state.value)
  }

  @Test
  fun navigationWithoutAKnownUrl_canStartAndFinish() {
    val tracker = WebViewNavigationStateTracker()
    tracker.onNavigationStarted(null)
    assertEquals(WebViewBrowserState.EMPTY.copy(isLoading = true), tracker.state.value)

    val error = WebViewBrowserError(url = null, message = "Navigation failed")
    tracker.onNavigationFinished(null, error)
    assertEquals(WebViewBrowserState.EMPTY.copy(progress = 1.0, error = error), tracker.state.value)

    tracker.onNavigationStarted(null)
    assertEquals(WebViewBrowserState.EMPTY.copy(isLoading = true), tracker.state.value)
    tracker.onNavigationFinished(null, null)
    assertEquals(WebViewBrowserState.EMPTY.copy(progress = 1.0), tracker.state.value)
  }

  @Test
  fun titleAndHistory_canBeClearedWithoutChangingNavigationState() {
    val tracker = WebViewNavigationStateTracker()
    tracker.onNavigationStarted("https://example.com")
    tracker.onProgressChanged(0.75)
    tracker.onTitleChanged("Example")
    tracker.onHistoryChanged(canGoBack = true, canGoForward = true)
    val before = tracker.state.value

    tracker.onTitleChanged(null)
    assertEquals(before.copy(title = null), tracker.state.value)

    tracker.onHistoryChanged(canGoBack = false, canGoForward = true)
    assertEquals(before.copy(title = null, canGoBack = false), tracker.state.value)

    tracker.onHistoryChanged(canGoBack = false, canGoForward = false)
    assertEquals(before.copy(title = null, canGoBack = false, canGoForward = false), tracker.state.value)
  }

  @Test
  fun progress_isClampedToTheSupportedRange() {
    val tracker = WebViewNavigationStateTracker()
    tracker.onNavigationStarted("https://example.com")
    val before = tracker.state.value

    for ((progress, expected) in listOf(-0.5 to 0.0, 0.0 to 0.0, 0.5 to 0.5, 1.0 to 1.0, 1.5 to 1.0)) {
      tracker.onProgressChanged(progress)
      assertEquals(before.copy(progress = expected), tracker.state.value, "progress=$progress")
    }
  }

  @Test
  fun nonFiniteProgress_doesNotEscapeTheSupportedRange() {
    val tracker = WebViewNavigationStateTracker()
    tracker.onNavigationStarted("https://example.com")

    for (progress in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
      tracker.onProgressChanged(0.5)
      tracker.onProgressChanged(progress)
      assertTrue(tracker.state.value.progress in 0.0..1.0, "progress=$progress, state=${tracker.state.value}")
      assertTrue(tracker.state.value.isLoading)
      assertEquals("https://example.com", tracker.state.value.url)
    }
  }
}