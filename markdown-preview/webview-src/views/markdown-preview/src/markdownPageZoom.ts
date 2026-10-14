// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { installWebViewPageZoom } from "@nerzhulart/intellij-webview-sdk"

/**
 * Who changed the page zoom: `user` for pinch, Ctrl+wheel and the options panel, `host` for a zoom applied by the IDE,
 * e.g. mirrored from the opposite preview of a two-side diff. Host changes are not reported back to the IDE.
 */
export type MarkdownPageZoomSource = "user" | "host"

/** Page zoom of the rendered markdown, observable by the floating options panel without re-rendering the content. */
export interface MarkdownPageZoom {
  readonly minZoom: number
  readonly maxZoom: number
  zoom(): number
  setZoom(zoom: number, source?: MarkdownPageZoomSource): void
  subscribe(listener: (source: MarkdownPageZoomSource) => void): () => void
}

const MIN_PAGE_ZOOM = 1
const MAX_PAGE_ZOOM = 4

export function installMarkdownPageZoom(): MarkdownPageZoom {
  const listeners = new Set<(source: MarkdownPageZoomSource) => void>()
  // `installWebViewPageZoom` reports a change synchronously from `setZoom`, so every other change comes from the user.
  let changeSource: MarkdownPageZoomSource = "user"
  const pageZoom = installWebViewPageZoom({
    minZoom: MIN_PAGE_ZOOM,
    maxZoom: MAX_PAGE_ZOOM,
    onZoomChanged: () => {
      const source = changeSource
      listeners.forEach(listener => listener(source))
    },
  })
  return {
    minZoom: MIN_PAGE_ZOOM,
    maxZoom: MAX_PAGE_ZOOM,
    zoom: () => pageZoom.zoom(),
    setZoom(zoom, source = "user") {
      changeSource = source
      try {
        pageZoom.setZoom(zoom)
      }
      finally {
        changeSource = "user"
      }
    },
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
}
