// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import type { WebViewBridge } from "@nerzhulart/intellij-webview-sdk"
import { installWebViewBrowserZoomGuard } from "./browserZoomGuard"
import { installWebViewDefaultTextSelectionGuard } from "./defaultTextSelectionGuard"
import { installWebViewFocusInterop } from "./focusInterop"
import { installIJTheming } from "./theme"

export function installWebViewPlatformFeatures(bridge: WebViewBridge): void {
  installWebViewBrowserZoomGuard(bridge.transport())
  installWebViewDefaultTextSelectionGuard()
  installIJTheming(bridge)
  installWebViewFocusInterop(bridge)
}
