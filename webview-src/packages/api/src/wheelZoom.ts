// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

// A pixel wheel delta this large is a mouse notch or a legacy Ctrl+wheel pinch emulation (Windows Precision
// Touchpad without DirectManipulation), not a per-frame trackpad pinch delta.
const COARSE_WHEEL_DELTA = 50
const COARSE_WHEEL_NOTCH = 100
const COARSE_STEP_FACTOR = 1.2
const MAX_COARSE_NOTCHES = 3
// Fine deltas arriving faster than this form a continuous pinch stream and are applied without easing.
const CONTINUOUS_WHEEL_INTERVAL_MS = 30
const EASING_TIME_CONSTANT_MS = 60
const EASING_DONE_LOG_RATIO = 0.002

/** CSS custom property on `<html>` that holds the page zoom of {@link installWebViewPageZoom}. */
export const WEBVIEW_PAGE_ZOOM_PROPERTY = "--wvi-page-zoom"

/** Viewport point that stays in place while the scale changes, usually the pointer of the wheel event. */
export interface WebViewZoomAnchor {
  clientX: number
  clientY: number
}

export interface WebViewWheelZoomTarget {
  scale(): number
  scaleTo(scale: number, anchor: WebViewZoomAnchor): void
  scaleExtent(): readonly [number, number]
}

export interface WebViewWheelZoom {
  /** Stops a running eased zoom, e.g. before a toolbar button changes the scale. */
  cancel(): void
  dispose(): void
}

export interface WebViewPageZoomOptions {
  minZoom?: number
  maxZoom?: number
  onZoomChanged?: (zoom: number) => void
}

export interface WebViewPageZoom extends WebViewWheelZoom {
  zoom(): number
  setZoom(zoom: number, anchor?: WebViewZoomAnchor): void
}

/**
 * Handles Ctrl+wheel (trackpad pinch) zoom on `element`.
 * Dense fine-grained streams (macOS pinch, Windows touchpads that report pixel deltas) are applied as is;
 * coarse or sparse steps (mouse wheel notches, Windows pinch emulation) are eased towards the target scale
 * so that they do not jump. Events already cancelled by a nested zoomable component are ignored.
 */
export function attachWebViewWheelZoom(element: EventTarget, target: WebViewWheelZoomTarget): WebViewWheelZoom {
  let targetScale: number | undefined
  let anchor: WebViewZoomAnchor = { clientX: 0, clientY: 0 }
  let frame = 0
  let lastFrameTime = 0
  let lastWheelTime = Number.NEGATIVE_INFINITY

  function cancel(): void {
    if (frame) cancelAnimationFrame(frame)
    frame = 0
    targetScale = undefined
  }

  function ease(time: number): void {
    frame = 0
    if (targetScale === undefined) return
    const currentScale = target.scale()
    const logRatio = Math.log(targetScale / currentScale)
    if (Math.abs(logRatio) < EASING_DONE_LOG_RATIO) {
      target.scaleTo(targetScale, anchor)
      targetScale = undefined
      return
    }
    const elapsed = Math.max(time - lastFrameTime, 0)
    lastFrameTime = time
    target.scaleTo(currentScale * Math.exp(logRatio * (1 - Math.exp(-elapsed / EASING_TIME_CONSTANT_MS))), anchor)
    frame = requestAnimationFrame(ease)
  }

  function onWheel(event: WheelEvent): void {
    if (!event.ctrlKey || event.defaultPrevented) return
    event.preventDefault()

    const [minScale, maxScale] = target.scaleExtent()
    const fromScale = targetScale ?? target.scale()
    const nextScale = Math.min(maxScale, Math.max(minScale, fromScale * 2 ** wheelZoomExponent(event)))
    anchor = { clientX: event.clientX, clientY: event.clientY }

    const continuous = !isCoarseWheel(event) && event.timeStamp - lastWheelTime < CONTINUOUS_WHEEL_INTERVAL_MS
    lastWheelTime = event.timeStamp
    if (continuous) {
      cancel()
      target.scaleTo(nextScale, anchor)
      return
    }

    targetScale = nextScale
    if (!frame) {
      lastFrameTime = performance.now()
      frame = requestAnimationFrame(ease)
    }
  }

  element.addEventListener("wheel", onWheel as EventListener, { passive: false })
  element.addEventListener("pointerdown", cancel)
  return {
    cancel,
    dispose() {
      cancel()
      element.removeEventListener("wheel", onWheel as EventListener)
      element.removeEventListener("pointerdown", cancel)
    },
  }
}

/**
 * Enables smooth Ctrl+wheel / trackpad pinch zoom of the whole page content, independent of the browser page zoom
 * that WebView hosts keep disabled. The zoom is published as {@link WEBVIEW_PAGE_ZOOM_PROPERTY} on `<html>`;
 * the view applies it to the content that should scale and keeps fixed overlays outside of that content.
 * For a native-like magnification without re-layout use `scale: var(--wvi-page-zoom, none); transform-origin: 0 0`;
 * the scaled box extends the page scroll area, so keep `minZoom` at 1. `zoom: var(--wvi-page-zoom, 1)` instead
 * re-wraps the content to the viewport width. The content under the pointer stays in place while zooming.
 */
export function installWebViewPageZoom(options: WebViewPageZoomOptions = {}): WebViewPageZoom {
  const root = document.documentElement
  const minZoom = options.minZoom ?? 1
  const maxZoom = options.maxZoom ?? 3
  let currentZoom = 1

  function setZoom(zoom: number, anchor: WebViewZoomAnchor = viewportCenter()): void {
    const nextZoom = Math.min(maxZoom, Math.max(minZoom, zoom))
    if (nextZoom === currentZoom) return

    const anchorElement = document.elementFromPoint(anchor.clientX, anchor.clientY)
    const before = anchorElement?.getBoundingClientRect()
    currentZoom = nextZoom
    if (currentZoom === 1) root.style.removeProperty(WEBVIEW_PAGE_ZOOM_PROPERTY)
    else root.style.setProperty(WEBVIEW_PAGE_ZOOM_PROPERTY, String(currentZoom))
    if (anchorElement && before) {
      const after = anchorElement.getBoundingClientRect()
      window.scrollBy(anchorShift(anchor.clientX, before.left, before.width, after.left, after.width), anchorShift(anchor.clientY, before.top, before.height, after.top, after.height))
    }
    options.onZoomChanged?.(currentZoom)
  }

  const wheelZoom = attachWebViewWheelZoom(document, {
    scale: () => currentZoom,
    scaleTo: setZoom,
    scaleExtent: () => [minZoom, maxZoom],
  })
  return {
    zoom: () => currentZoom,
    setZoom(zoom, anchor) {
      wheelZoom.cancel()
      setZoom(zoom, anchor)
    },
    cancel: wheelZoom.cancel,
    dispose() {
      wheelZoom.dispose()
      root.style.removeProperty(WEBVIEW_PAGE_ZOOM_PROPERTY)
    },
  }
}

function isCoarseWheel(event: WheelEvent): boolean {
  return event.deltaMode !== WheelEvent.DOM_DELTA_PIXEL || Math.abs(event.deltaY) >= COARSE_WHEEL_DELTA
}

function wheelZoomExponent(event: WheelEvent): number {
  if (isCoarseWheel(event)) {
    const notches = event.deltaMode === WheelEvent.DOM_DELTA_PIXEL ? Math.abs(event.deltaY) / COARSE_WHEEL_NOTCH : 1
    return -Math.sign(event.deltaY) * Math.log2(COARSE_STEP_FACTOR) * Math.min(MAX_COARSE_NOTCHES, Math.max(1, notches))
  }
  // Same as d3-zoom's default wheelDelta for ctrlKey pixel deltas, so trackpad pinch keeps its native feel.
  return -event.deltaY * 0.02
}

function viewportCenter(): WebViewZoomAnchor {
  return { clientX: window.innerWidth / 2, clientY: window.innerHeight / 2 }
}

/** Scroll offset that brings the same relative point of the anchor element back under `client`. */
function anchorShift(client: number, beforeStart: number, beforeSize: number, afterStart: number, afterSize: number): number {
  const fraction = beforeSize > 0 ? (client - beforeStart) / beforeSize : 0
  return afterStart + fraction * afterSize - client
}
