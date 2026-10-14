// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { createServer as createNetServer } from "node:net"
import { resolve } from "node:path"
import process from "node:process"
import { createServer, type Plugin, type UserConfig } from "vite"
import { defineWebViewViewConfig } from "@nerzhulart/intellij-webview-sdk/vite"
import { resolveWebViewMockIconSetAsset } from "./iconSetAssetResolver"
import { withWebViewMockBridge } from "./vite"
import type { StartWebViewMockPreviewOptions, WebViewMockPreviewServer } from "./core"

export async function startWebViewMockPreview(options: StartWebViewMockPreviewOptions): Promise<WebViewMockPreviewServer> {
  const port = options.port ?? await findAvailablePort()
  const viewConfig = defineWebViewViewConfig({
    webviewSrcDir: options.webviewSrcDir,
    id: options.viewId,
  })
  const config = withWebViewMockBridge({
    ...viewConfig,
    plugins: [webViewMockIconSetPlugin(viewConfig), ...asPluginArray(viewConfig.plugins)],
  }, {
    mock: options.mock,
  })
  const server = await createServer({
    ...config,
    cacheDir: config.cacheDir ?? parallelTestWorkerCacheDir(options),
    server: {
      ...config.server,
      host: "127.0.0.1",
      port,
      strictPort: true,
    },
  })
  await server.listen()
  const address = server.httpServer?.address()
  const actualPort = typeof address === "object" && address ? address.port : port
  if (typeof actualPort !== "number") {
    await server.close()
    throw new Error("Vite did not expose a preview server port")
  }
  return {
    url: `http://127.0.0.1:${actualPort}/`,
    close() {
      return server.close()
    },
  }
}

function webViewMockIconSetPlugin(config: UserConfig): Plugin {
  const viewResourceRoot = typeof config.build?.outDir === "string" ? config.build.outDir : undefined
  const viewSourceRoot = typeof config.root === "string" ? config.root : undefined
  return {
    name: "webview-mock-icon-sets",
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (!viewResourceRoot || !req.url) {
          next()
          return
        }

        const response = resolveWebViewMockIconSetAsset(req.url, { viewResourceRoot, viewSourceRoot })
        if (!response) {
          next()
          return
        }

        res.statusCode = response.statusCode
        if (response.contentType) res.setHeader("Content-Type", response.contentType)
        for (const [name, value] of Object.entries(response.headers ?? {})) {
          res.setHeader(name, value)
        }
        res.end(response.body)
      })
    },
  }
}

/**
 * Parallel test workers start one preview server each. Sharing Vite's default dependency cache makes cold
 * optimizer runs overwrite each other's `deps` and reload pages mid-test, so every worker gets its own cache.
 */
function parallelTestWorkerCacheDir(options: StartWebViewMockPreviewOptions): string | undefined {
  const workerIndex = process.env.TEST_PARALLEL_INDEX
  if (!workerIndex || !/^\d+$/.test(workerIndex)) return undefined
  return resolve(options.webviewSrcDir, "node_modules", ".vite", `webview-mock-${options.viewId}-worker-${workerIndex}`)
}

function asPluginArray(plugins: UserConfig["plugins"]): Plugin[] {
  if (!plugins) return []
  return Array.isArray(plugins) ? plugins.filter((plugin): plugin is Plugin => Boolean(plugin) && !Array.isArray(plugin)) : [plugins as Plugin]
}

function findAvailablePort(): Promise<number> {
  return new Promise<number>((resolve, reject) => {
    const server = createNetServer()
    server.unref()
    server.on("error", reject)
    server.listen(0, "127.0.0.1", () => {
      const address = server.address()
      server.close(() => {
        if (typeof address === "object" && address) {
          resolve(address.port)
        }
        else {
          reject(new Error("Node did not expose an available preview port"))
        }
      })
    })
  }).catch((error) => {
    throw new Error("Cannot allocate WebView mock preview port: " + errorText(error))
  })
}

function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}
