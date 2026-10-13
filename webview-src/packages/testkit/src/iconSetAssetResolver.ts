// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { existsSync, readdirSync, readFileSync } from "node:fs"
import { dirname, isAbsolute, relative, resolve, sep } from "node:path"

export interface WebViewMockIconSetAssetResolverOptions {
  viewResourceRoot: string
  /** View source directory used to resolve not yet built `assets/<original-name>` resources. */
  viewSourceRoot?: string
}

export interface WebViewMockIconSetAssetResponse {
  statusCode: number
  contentType?: string
  headers?: Record<string, string>
  body: Buffer | string
}

const PLATFORM_ICON_SET_ID = "AllIcons"

const PLATFORM_ICON_PLACEHOLDER_SVG = `<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16"><rect x="2.5" y="2.5" width="11" height="11" rx="2" fill="none" stroke="#8C8C8C"/></svg>`

export function resolveWebViewMockIconSetAsset(
  requestPath: string,
  options: WebViewMockIconSetAssetResolverOptions,
): WebViewMockIconSetAssetResponse | undefined {
  const resolvedViewResourceRoot = resolve(options.viewResourceRoot)
  const platformRoots = platformIconResourceRoots(resolvedViewResourceRoot)
  const lookup: IconLookup = {
    viewResourceRoot: resolvedViewResourceRoot,
    viewSourceRoot: options.viewSourceRoot ? resolve(options.viewSourceRoot) : undefined,
    resourceRoots: iconResourceRoots(resolvedViewResourceRoot, platformRoots),
  }
  const route = parseIconRoute(requestPath)
  if (!route) return undefined

  if (!route.resourcePath) {
    return forbidden("Invalid icon path")
  }

  const actualResourcePath = route.flavor === "dark"
    ? darkIconResourcePath(lookup, route.resourcePath) ?? route.resourcePath
    : route.resourcePath
  const iconPath = iconFilePath(lookup, actualResourcePath)
  if (!iconPath) {
    // Platform icons live on the IntelliJ classpath and are available to the mock only inside a monorepo checkout.
    if (route.iconSetId === PLATFORM_ICON_SET_ID && platformRoots.length === 0) {
      return platformIconPlaceholder()
    }
    return notFound("Icon not found")
  }

  return {
    statusCode: 200,
    contentType: iconContentType(actualResourcePath),
    headers: { "Cache-Control": "no-cache" },
    body: readFileSync(iconPath),
  }
}

interface IconLookup {
  viewResourceRoot: string
  viewSourceRoot?: string
  resourceRoots: string[]
}

interface IconRoute {
  flavor: "light" | "dark"
  iconSetId?: string
  resourcePath?: string
}

function parseIconRoute(requestPath: string): IconRoute | undefined {
  const path = requestPath.split("?", 1)[0]?.replace(/^\/+/, "")
  if (path === "__ij-icons") return { flavor: "light" }
  if (!path?.startsWith("__ij-icons/")) return undefined

  const parts = path.split("/", 4)
  if (parts.length < 4 || parts[0] !== "__ij-icons") return { flavor: "light" }
  const [, iconSetId, flavor] = parts
  if (!isValidIconSetId(iconSetId)) return { flavor: "light" }
  if (flavor !== "light" && flavor !== "dark") return { flavor: "light" }

  const encodedResourcePath = path.substring(`__ij-icons/${iconSetId}/${flavor}/`.length)
  const resourcePath = decodeIconResourcePath(encodedResourcePath)
  return { flavor, iconSetId, resourcePath }
}

function iconResourceRoots(resolvedViewResourceRoot: string, platformRoots: readonly string[]): string[] {
  return Array.from(new Set([
    resolvedViewResourceRoot,
    resolve(resolvedViewResourceRoot, "../../.."),
    ...platformRoots,
  ]))
}

function platformIconResourceRoots(startPath: string): string[] {
  const roots: string[] = []
  let current = resolve(startPath)
  while (true) {
    addExistingRoot(roots, resolve(current, "platform/icons/src"))
    addExistingRoot(roots, resolve(current, "community/platform/icons/src"))

    const parent = dirname(current)
    if (parent === current) return roots
    current = parent
  }
}

function addExistingRoot(roots: string[], candidate: string): void {
  if (existsSync(candidate)) roots.push(candidate)
}

function decodeIconResourcePath(encodedPath: string): string | undefined {
  let resourcePath: string
  try {
    resourcePath = encodedPath.split("/").map(segment => decodeURIComponent(segment)).join("/")
  }
  catch {
    return undefined
  }
  if (!resourcePath.endsWith(".svg") && !resourcePath.endsWith(".png")) return undefined
  if (resourcePath.startsWith("/") || resourcePath.includes("\\")) return undefined
  if (resourcePath.split("/").some(segment => !segment || segment === "." || segment === "..")) return undefined
  return resourcePath
}

function darkIconResourcePath(lookup: IconLookup, resourcePath: string): string | undefined {
  const extensionStart = resourcePath.lastIndexOf(".")
  const candidate = `${resourcePath.substring(0, extensionStart)}_dark${resourcePath.substring(extensionStart)}`
  return iconFilePath(lookup, candidate) ? candidate : undefined
}

function iconFilePath(lookup: IconLookup, resourcePath: string): string | undefined {
  return iconResourceFilePath(lookup.resourceRoots, resourcePath) ?? viewSourceAssetFilePath(lookup, resourcePath)
}

function viewSourceAssetFilePath(lookup: IconLookup, resourcePath: string): string | undefined {
  if (!lookup.viewSourceRoot || !existsSync(lookup.viewSourceRoot)) return undefined
  const assetName = builtViewAssetName(lookup, resourcePath)
  return assetName ? findFileByName(lookup.viewSourceRoot, assetName) : undefined
}

function builtViewAssetName(lookup: IconLookup, resourcePath: string): string | undefined {
  const roots = [lookup.viewResourceRoot, resolve(lookup.viewResourceRoot, "../../..")]
  for (const root of roots) {
    const segments = relative(lookup.viewResourceRoot, resolve(root, resourcePath)).split(sep)
    if (segments.length === 2 && segments[0] === "assets") return segments[1]
  }
  return undefined
}

function findFileByName(dir: string, fileName: string): string | undefined {
  const entries = readdirSync(dir, { withFileTypes: true })
  const file = entries.find(entry => entry.isFile() && entry.name === fileName)
  if (file) return resolve(dir, file.name)
  for (const entry of entries) {
    if (!entry.isDirectory() || entry.name === "node_modules" || entry.name.startsWith(".")) continue
    const nested = findFileByName(resolve(dir, entry.name), fileName)
    if (nested) return nested
  }
  return undefined
}

function iconResourceFilePath(resourceRoots: readonly string[], resourcePath: string): string | undefined {
  for (const resourceRoot of resourceRoots) {
    const candidatePath = resolve(resourceRoot, resourcePath)
    if (isInsideResourceRoot(resourceRoot, candidatePath) && existsSync(candidatePath)) return candidatePath
  }
  return undefined
}

function isInsideResourceRoot(resourceRoot: string, path: string): boolean {
  const relativePath = relative(resourceRoot, path)
  return relativePath === "" || (relativePath !== ".." && !relativePath.startsWith(`..${sep}`) && !isAbsolute(relativePath))
}

function isValidIconSetId(iconSetId: string): boolean {
  return /^[A-Za-z][A-Za-z0-9._-]*$/.test(iconSetId)
}

function iconContentType(path: string): string {
  return path.endsWith(".svg") ? "image/svg+xml" : "image/png"
}

function platformIconPlaceholder(): WebViewMockIconSetAssetResponse {
  return {
    statusCode: 200,
    contentType: "image/svg+xml",
    headers: { "Cache-Control": "no-cache", "X-WebView-Mock-Icon": "placeholder" },
    body: PLATFORM_ICON_PLACEHOLDER_SVG,
  }
}

function forbidden(message: string): WebViewMockIconSetAssetResponse {
  return { statusCode: 403, body: message }
}

function notFound(message: string): WebViewMockIconSetAssetResponse {
  return { statusCode: 404, body: message }
}
