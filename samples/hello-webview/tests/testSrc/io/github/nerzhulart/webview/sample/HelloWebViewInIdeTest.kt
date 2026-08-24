package io.github.nerzhulart.webview.sample

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Robot
import java.awt.event.InputEvent
import javax.swing.JFrame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@TestApplication
@DisabledIfSystemProperty(named = "java.awt.headless", matches = "true")
internal class HelloWebViewInIdeTest {
  @Test
  fun robotClick_reachesKotlinHandler(): Unit = runBlocking {
    assumeFalse(GraphicsEnvironment.isHeadless(), "java.awt.headless=true")

    @Suppress("RAW_SCOPE_CREATION") // Test owns the WebView scope and cancels it in finally.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val buttonClicked = CompletableDeferred<Unit>()
    var frame: JFrame? = null

    try {
      val panel = createPanelOrSkip(scope, object : HelloHostApi {
        override suspend fun buttonClicked() {
          buttonClicked.complete(Unit)
        }
      })
      val hostFrame = showHost(panel.component)
      frame = hostFrame
      assertTrue(waitUntilShowing(panel.component, 5.seconds), "WebView panel did not become visible")

      val robot = createRobotOrSkip()
      assumeTrue(activateFrame(hostFrame, 5.seconds), "Robot test window could not be activated")
      val center = componentCenterOnScreen(panel.component)
      val handlerReached = withTimeoutOrNull(30.seconds) {
        while (!buttonClicked.isCompleted) {
          robot.click(center)
          withTimeoutOrNull(500.milliseconds) {
            buttonClicked.await()
          }
        }
        true
      } == true
      assertTrue(handlerReached, "Robot clicks did not reach HelloHostApi.buttonClicked within 30 seconds")
    }
    finally {
      disposeFrame(frame)
      scope.coroutineContext.job.cancelAndJoin()
    }
  }

  private suspend fun createPanelOrSkip(
    scope: CoroutineScope,
    hostApi: HelloHostApi,
  ): HelloWebViewPanel {
    return runCatching {
      withContext(Dispatchers.EDT) {
        HelloWebViewPanel.create(scope, hostApi)
      }
    }.getOrElse { t ->
      assumeTrue(false, "No WebView engine is available: ${t.message ?: t.javaClass.name}")
      throw t
    }
  }

  private suspend fun showHost(host: Component): JFrame {
    return withContext(Dispatchers.EDT) {
      JFrame("Hello WebView Robot Test").apply {
        defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        contentPane.layout = BorderLayout()
        contentPane.add(host, BorderLayout.CENTER)
        size = Dimension(480, 320)
        setLocation(80, 80)
        isVisible = true
        requestForeground(this)
      }
    }
  }

  private suspend fun activateFrame(frame: JFrame, timeout: Duration): Boolean {
    return withTimeoutOrNull(timeout) {
      while (true) {
        val active = withContext(Dispatchers.EDT) {
          requestForeground(frame)
          frame.isActive && frame.isFocused
        }
        if (active) return@withTimeoutOrNull true
        delay(100.milliseconds)
      }
    } == true
  }

  private fun requestForeground(frame: JFrame) {
    runCatching { Desktop.getDesktop().requestForeground(true) }
    frame.isAlwaysOnTop = true
    frame.toFront()
    frame.isAlwaysOnTop = false
  }

  private fun createRobotOrSkip(): Robot {
    return runCatching {
      Robot().apply { autoDelay = 50 }
    }.getOrElse { t ->
      assumeTrue(false, "AWT Robot is unavailable: ${t.message ?: t.javaClass.name}")
      throw t
    }
  }

  private suspend fun componentCenterOnScreen(component: Component): Point {
    return withContext(Dispatchers.EDT) {
      val location = component.locationOnScreen
      Point(location.x + component.width / 2, location.y + component.height / 2)
    }
  }

  private fun Robot.click(point: Point) {
    mouseMove(point.x, point.y)
    mousePress(InputEvent.BUTTON1_DOWN_MASK)
    mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    waitForIdle()
  }

  private suspend fun disposeFrame(frame: JFrame?) {
    if (frame != null) {
      withContext(Dispatchers.EDT) { frame.dispose() }
    }
  }

  private suspend fun waitUntilShowing(component: Component, timeout: Duration): Boolean {
    return withTimeoutOrNull(timeout) {
      while (true) {
        if (withContext(Dispatchers.EDT) { component.isShowing }) return@withTimeoutOrNull true
        delay(100.milliseconds)
      }
    } == true
  }
}