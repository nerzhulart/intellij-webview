import { dirname } from "node:path"
import { fileURLToPath } from "node:url"
import { build } from "vite"
import { defineWebViewViewConfigs, selectWebViewViewBuildEntries, withWebViewBuildWatch } from "@nerzhulart/intellij-webview-sdk/vite"

const webviewSrcDir = dirname(fileURLToPath(import.meta.url))
const outputRoot = process.env.WEBVIEW_OUTPUT_ROOT
const selectedViews = selectWebViewViewBuildEntries(["hello"])

for (const config of defineWebViewViewConfigs({ webviewSrcDir, views: selectedViews.views, outputRoot })) {
  await build(withWebViewBuildWatch(config, selectedViews.watch) as unknown as Parameters<typeof build>[0])
}