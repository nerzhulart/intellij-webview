// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { build } from "vite"
import { dirname } from "node:path"
import { fileURLToPath } from "node:url"
import { defineWebViewBridgeConfig, defineWebViewPlatformFeaturesConfig } from "@nerzhulart/intellij-webview-sdk/vite"

const webviewSrcDir = dirname(fileURLToPath(import.meta.url))
const outputRoot = process.env.WEBVIEW_OUTPUT_ROOT

await build(defineWebViewBridgeConfig({ webviewSrcDir, outDir: outputRoot }))
await build(defineWebViewPlatformFeaturesConfig({ webviewSrcDir, outDir: outputRoot }))
