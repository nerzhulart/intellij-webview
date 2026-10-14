// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { installWebViewPageZoom } from "@nerzhulart/intellij-webview-sdk"

/** Page zoom of the rendered markdown, observable by the floating options panel without re-rendering the content. */
export interface MarkdownPageZoom {
  readonly minZoom: number
  readonly maxZoom: number
  zoom(): number
  setZoom(zoom: number): void
  subscribe(listener: () => void): () => void
}

const MIN_PAGE_ZOOM = 1
const MAX_PAGE_ZOOM = 4

export function installMarkdownPageZoom(): MarkdownPageZoom {
  const listeners = new Set<() => void>()
  const pageZoom = installWebViewPageZoom({
    minZoom: MIN_PAGE_ZOOM,
    maxZoom: MAX_PAGE_ZOOM,
    onZoomChanged: () => listeners.forEach(listener => listener()),
  })
  return {
    minZoom: MIN_PAGE_ZOOM,
    maxZoom: MAX_PAGE_ZOOM,
    zoom: () => pageZoom.zoom(),
    setZoom: zoom => pageZoom.setZoom(zoom),
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
}
