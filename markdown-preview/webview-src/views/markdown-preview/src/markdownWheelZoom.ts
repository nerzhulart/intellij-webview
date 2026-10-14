// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { attachWebViewWheelZoom, type WebViewWheelZoom } from "@nerzhulart/intellij-webview-sdk"
import { pointer, select } from "d3-selection"
import { zoomTransform, type ZoomBehavior } from "d3-zoom"

export type MarkdownWheelZoom = WebViewWheelZoom

/** Handles Ctrl+wheel (trackpad pinch) zoom of a d3-zoom element instead of d3-zoom's own wheel handler. */
export function attachMarkdownWheelZoom<E extends Element>(element: E, behavior: ZoomBehavior<E, unknown>): MarkdownWheelZoom {
  const selection = select<E, unknown>(element)
  return attachWebViewWheelZoom(element, {
    scale: () => zoomTransform(element).k,
    scaleTo: (scale, anchor) => selection.call(behavior.scaleTo, scale, pointer(anchor, element)),
    scaleExtent: () => behavior.scaleExtent(),
  })
}
