// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { dirname } from "node:path"
import { fileURLToPath } from "node:url"
import { defineWebViewViewConfig } from "@nerzhulart/intellij-webview-sdk/vite"

const webviewSrcDir = dirname(fileURLToPath(import.meta.url))

export default defineWebViewViewConfig({ webviewSrcDir, id: "sample-panel" })
