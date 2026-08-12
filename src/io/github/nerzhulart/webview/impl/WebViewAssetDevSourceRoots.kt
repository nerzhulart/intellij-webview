// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.impl

import com.intellij.openapi.application.ApplicationManager
import io.github.nerzhulart.webview.api.WebViewAssetSource
import java.nio.file.Files
import java.nio.file.Path

internal class WebViewAssetDevSourceRoots {
  fun find(source: WebViewAssetSource.Classpath): Path? {
    if (!isDevSourceFallbackEnabled()) return null

    val explicitRoot = source.devSourceRoot ?: return null
    if (Files.isDirectory(explicitRoot)) return explicitRoot
    WebViewLogger.LOG.warn("Configured WebView asset dev source root does not exist: $explicitRoot")
    return null
  }

  private fun isDevSourceFallbackEnabled(): Boolean {
    if (java.lang.Boolean.getBoolean("io.github.nerzhulart.webview.assets.disable.source.fallback")) return false
    if (java.lang.Boolean.getBoolean("io.github.nerzhulart.webview.assets.use.source.dir")) return true

    val application = runCatching { ApplicationManager.getApplication() }.getOrNull()
    return application?.isUnitTestMode == true
  }
}
