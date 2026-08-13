// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { dirname } from "node:path"
import { fileURLToPath } from "node:url"
import { build } from "vite"
import { defineWebViewViewConfigs, selectWebViewViewBuildEntries, withWebViewBuildWatch } from "@nerzhulart/intellij-webview-sdk/vite"

const webviewSrcDir = dirname(fileURLToPath(import.meta.url))
const outputRoot = process.env.WEBVIEW_OUTPUT_ROOT
const selectedViews = selectWebViewViewBuildEntries([
  "sample-panel",
  "controls-showcase",
  "react-controls-showcase",
  "ui-dsl-showcase",
  "markdown-link-graph",
  "acp-chat",
])

for (const config of defineWebViewViewConfigs({ webviewSrcDir, views: selectedViews.views, outputRoot })) {
  await build(withWebViewBuildWatch(config, selectedViews.watch) as unknown as Parameters<typeof build>[0])
}
