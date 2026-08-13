// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { requireWebViewBridge } from "@nerzhulart/intellij-webview-sdk"
import { installWebViewPlatformFeatures } from "./platformFeatures"

installWebViewPlatformFeatures(requireWebViewBridge())
