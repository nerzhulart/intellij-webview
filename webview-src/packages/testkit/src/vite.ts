// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { existsSync } from "node:fs"
import { dirname, resolve } from "node:path"
import { fileURLToPath } from "node:url"
import type { Alias, AliasOptions, Plugin, UserConfig } from "vite"
import { mockWebViewBridgeBrowserScript } from "./browserScript"

const testkitSrcDir = dirname(fileURLToPath(import.meta.url))
const testkitEntryExtension = fileURLToPath(import.meta.url).endsWith(".ts") ? ".ts" : ".js"

export interface WebViewMockBridgeViteOptions {
  mock: string
}

export function withWebViewMockBridge(config: UserConfig, options: WebViewMockBridgeViteOptions): UserConfig {
  const mockPath = resolve(options.mock)
  return {
    ...config,
    resolve: {
      ...config.resolve,
      alias: [...testkitPackageAliases(), ...asAliasArray(config.resolve?.alias)],
    },
    plugins: [webViewMockBridgePlugin(mockPath), ...asPluginArray(config.plugins)],
    server: {
      ...config.server,
      fs: {
        ...config.server?.fs,
        allow: fileSystemAllowList(config, mockPath, platformFeaturesEntryPath()),
      },
    },
  }
}

function fileSystemAllowList(config: UserConfig, mockPath: string, platformFeaturesPath: string | undefined): string[] {
  const allow = [
    ...(config.server?.fs?.allow ?? []),
    ...(typeof config.root === "string" ? [resolve(config.root)] : []),
    ...(platformFeaturesPath ? [dirname(platformFeaturesPath)] : []),
    dirname(mockPath),
    process.cwd(),
  ]
  return Array.from(new Set(allow))
}

/**
 * The platform features (theming, focus interop, zoom and selection guards) are served by the IDE in production.
 * Mock previews have to install them on top of the mock bridge, before any theme-aware view code runs.
 */
function platformFeaturesEntryPath(): string | undefined {
  const candidates = [
    resolve(testkitSrcDir, `../../impl/src/platformFeaturesEntry${testkitEntryExtension}`),
    resolve(testkitSrcDir, `../impl/platformFeaturesEntry${testkitEntryExtension}`),
  ]
  return candidates.find(candidate => existsSync(candidate))
}

function webViewMockBridgePlugin(mockPath: string): Plugin {
  const mockUrl = `/@fs/${normalizePath(mockPath)}`
  return {
    name: "webview-mock-bridge",
    enforce: "pre",
    transformIndexHtml(html) {
      return injectMockEntry(html)
    },
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const path = req.url?.split("?", 1)[0]
        if (path === "/__webview/wvi-bridge.js") {
          res.statusCode = 200
          res.setHeader("Content-Type", "text/javascript; charset=utf-8")
          res.end(mockWebViewBridgeBrowserScript())
          return
        }
        if (path === "/__webview-test/mock-entry.js") {
          const platformFeaturesPath = platformFeaturesEntryPath()
          const platformFeaturesImport = platformFeaturesPath
            ? `import ${JSON.stringify(`/@fs/${normalizePath(platformFeaturesPath)}`)};\n`
            : ""
          res.statusCode = 200
          res.setHeader("Content-Type", "text/javascript; charset=utf-8")
          res.end(`${platformFeaturesImport}import mock from ${JSON.stringify(mockUrl)};\nwindow.__WVI_MOCK__.apply(mock);\n`)
          return
        }
        next()
      })
    },
  }
}

function injectMockEntry(html: string): string {
  if (html.includes("/__webview-test/mock-entry.js")) {
    return html
  }
  const mockEntryPath = "/__webview-test/" + "mock-entry.js"
  const mockScript = `<script type="module" src="${mockEntryPath}"></script>`
  const transformed = html.replace(
    /<script\s+type=(["'])module\1\s+src=(["'])\.\/src\//i,
    match => `${mockScript}\n${match}`,
  )
  if (transformed !== html) {
    return transformed
  }
  return html.replace(/<\/body>/i, `${mockScript}\n</body>`)
}

function asPluginArray(plugins: UserConfig["plugins"]): Plugin[] {
  if (!plugins) {
    return []
  }
  return Array.isArray(plugins) ? plugins.filter((plugin): plugin is Plugin => Boolean(plugin) && !Array.isArray(plugin)) : [plugins as Plugin]
}

function testkitPackageAliases(): Alias[] {
  return [
    { find: /^@nerzhulart\/webview-testkit$/, replacement: resolve(testkitSrcDir, `index${testkitEntryExtension}`) },
    { find: /^@nerzhulart\/webview-testkit\/vite$/, replacement: resolve(testkitSrcDir, `vite${testkitEntryExtension}`) },
  ]
}

function asAliasArray(alias: AliasOptions | undefined): Alias[] {
  if (!alias) {
    return []
  }
  if (Array.isArray(alias)) {
    return alias.slice()
  }
  return Object.entries(alias).map(([find, replacement]) => ({ find, replacement }))
}

function normalizePath(path: string): string {
  return path.replace(/\\/g, "/")
}
