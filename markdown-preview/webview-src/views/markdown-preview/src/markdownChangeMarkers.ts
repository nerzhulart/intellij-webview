// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import type { ChangedBlockElements } from "./markdownSourcePositions"

export interface ScrollMetrics {
  scrollTop: number
  scrollHeight: number
  clientHeight: number
}

export interface TrackMarker {
  kind: ChangedBlockElements["kind"]
  top: number
  height: number
  target: HTMLElement
}

type MarkerColors = Record<TrackMarker["kind"], string>

const minMarkerHeight = 3
const markerHorizontalInset = 1
const markersStyleId = "markdown-change-markers"
const markersClassName = "has-change-markers"
const markerHoverClassName = "is-over-change-marker"
const markerColorProperties: Record<TrackMarker["kind"], string> = {
  ADDED: "--markdown-vcs-added-border",
  MODIFIED: "--markdown-vcs-modified-border",
  REMOVED: "--markdown-vcs-removed-border",
}
const fallbackMarkerColors: MarkerColors = {
  ADDED: "#6DB083",
  MODIFIED: "#3574F0",
  REMOVED: "#C75450",
}

let installed = false
let changedBlocks: ChangedBlockElements[] = []
let trackMarkers: TrackMarker[] = []
let scheduledUpdateFrame: number | undefined
let appliedCss = ""
let pointerPosition: { x: number, y: number } | undefined

export function installScrollbarChangeMarkers(): void {
  if (installed) return
  installed = true

  if (typeof ResizeObserver !== "undefined") {
    const observer = new ResizeObserver(() => scheduleUpdate())
    observer.observe(document.documentElement)
    const contentElement = document.getElementById("content")
    if (contentElement) observer.observe(contentElement)
  }
  window.addEventListener("resize", scheduleUpdate, { passive: true })
  document.addEventListener("mousedown", handleTrackMouseDown, { capture: true })
  document.addEventListener("mousemove", handlePointerMove, { capture: true, passive: true })
  document.documentElement.addEventListener("mouseleave", handlePointerLeave, { passive: true })
  window.addEventListener("blur", handlePointerLeave)
  window.addEventListener("scroll", updateMarkerHover, { passive: true })
}

export function setScrollbarChangeMarkers(blocks: ChangedBlockElements[]): void {
  changedBlocks = blocks
  scheduleUpdate()
}

export function refreshScrollbarChangeMarkers(): void {
  scheduleUpdate()
}

/**
 * Navigates to the change under a click in the viewport scrollbar track. Engines that do not dispatch mouse events for
 * the native scrollbar keep the markers visual only. Clicks on the thumb are left to the native drag, and the navigation
 * is repeated in the next frame to override a native page step that `preventDefault` may not cancel. The scroll is not
 * marked as programmatic, so the opposite side of a two-side diff follows it through the regular scroll report.
 */
function handleTrackMouseDown(event: MouseEvent): void {
  if (event.button !== 0) return

  const marker = markerAtPointer(event.clientX, event.clientY)
  if (!marker) return

  event.preventDefault()
  const target = marker.target
  const navigate = (): void => {
    if (target.isConnected) target.scrollIntoView({ block: "start", behavior: "instant" })
  }
  navigate()
  window.requestAnimationFrame(navigate)
}

function handlePointerMove(event: MouseEvent): void {
  pointerPosition = { x: event.clientX, y: event.clientY }
  updateMarkerHover()
}

function handlePointerLeave(): void {
  pointerPosition = undefined
  updateMarkerHover()
}

/**
 * Shows the hand cursor over a marker. Chromium takes the cursor of a custom scrollbar from the hovered scrollbar part
 * or, in older versions, from the element under the scrollbar, so the class sets it for both. Engines that use the
 * native cursor for scrollbars keep the arrow.
 */
function updateMarkerHover(): void {
  const hovered = pointerPosition !== undefined && markerAtPointer(pointerPosition.x, pointerPosition.y) !== undefined
  document.documentElement.classList.toggle(markerHoverClassName, hovered)
}

/**
 * Finds the marker under a pointer in the viewport scrollbar track. The thumb covers the markers, so a pointer over the
 * thumb is left to the native drag.
 */
function markerAtPointer(clientX: number, clientY: number): TrackMarker | undefined {
  if (trackMarkers.length === 0) return undefined

  const root = document.documentElement
  if (clientX < root.clientWidth || clientY >= root.clientHeight) return undefined

  const thumb = thumbRange(viewportScrollMetrics())
  if (thumb.top <= clientY && clientY < thumb.bottom) return undefined

  return hitTestMarker(trackMarkers, clientY)
}

function scheduleUpdate(): void {
  if (scheduledUpdateFrame !== undefined) return
  scheduledUpdateFrame = window.requestAnimationFrame(() => {
    scheduledUpdateFrame = undefined
    updateMarkers()
  })
}

function updateMarkers(): void {
  const root = document.documentElement
  if (changedBlocks.length === 0) {
    clearMarkers()
    return
  }

  // The track is always shown while there are changes, so the class goes first: it changes the viewport width and
  // therefore the layout the markers are computed from.
  root.classList.add(markersClassName)
  const markers = layoutTrackMarkers(changedBlocks, viewportScrollMetrics())
  if (markers.length === 0) {
    clearMarkers()
    return
  }

  trackMarkers = markers
  applyCss(trackBackgroundCss(markers, resolveMarkerColors()))
  updateMarkerHover()
}

function clearMarkers(): void {
  trackMarkers = []
  document.documentElement.classList.remove(markersClassName)
  applyCss("")
  updateMarkerHover()
}

function applyCss(css: string): void {
  if (css === appliedCss) return
  appliedCss = css

  let style = document.getElementById(markersStyleId) as HTMLStyleElement | null
  if (css.length === 0) {
    style?.remove()
  }
  else {
    if (!style) {
      style = document.createElement("style")
      style.id = markersStyleId
      document.head.append(style)
    }
    style.textContent = css
  }
  recreateViewportScrollbar()
}

/**
 * Chromium and WebKit do not always repaint custom scrollbar parts after a style sheet change, so the viewport
 * scrollbar is dropped and recreated by a synchronous layout with the viewport overflow temporarily hidden.
 */
function recreateViewportScrollbar(): void {
  const root = document.documentElement
  const scrollingElement = document.scrollingElement ?? root
  const { scrollTop, scrollLeft } = scrollingElement
  const previousOverflowY = root.style.overflowY
  root.style.overflowY = "hidden"
  void root.offsetHeight
  root.style.overflowY = previousOverflowY
  void root.offsetHeight
  if (scrollingElement.scrollTop !== scrollTop || scrollingElement.scrollLeft !== scrollLeft) {
    scrollingElement.scrollTop = scrollTop
    scrollingElement.scrollLeft = scrollLeft
  }
}

function viewportScrollMetrics(): ScrollMetrics {
  const root = document.documentElement
  const scrollingElement = document.scrollingElement ?? root
  return {
    scrollTop: scrollingElement.scrollTop,
    scrollHeight: scrollingElement.scrollHeight,
    clientHeight: root.clientHeight,
  }
}

function resolveMarkerColors(): MarkerColors {
  const source = document.getElementById("content") ?? document.documentElement
  const style = getComputedStyle(source)
  const colors = { ...fallbackMarkerColors }
  for (const kind of Object.keys(markerColorProperties) as TrackMarker["kind"][]) {
    const value = style.getPropertyValue(markerColorProperties[kind]).trim()
    if (value.length > 0) colors[kind] = value
  }
  return colors
}

/**
 * Maps the rendered geometry of the changed blocks onto the scrollbar track, so images, diagrams, and formulas that
 * change the rendered height of the document keep the markers aligned with the blocks they represent.
 */
export function layoutTrackMarkers(blocks: ChangedBlockElements[], metrics: ScrollMetrics): TrackMarker[] {
  const { scrollTop, scrollHeight, clientHeight } = metrics
  if (scrollHeight <= 0 || clientHeight <= 0) return []

  const scale = clientHeight / Math.max(scrollHeight, clientHeight)
  const markers: TrackMarker[] = []
  for (const block of blocks) {
    let docTop = Number.POSITIVE_INFINITY
    let docBottom = Number.NEGATIVE_INFINITY
    let target: HTMLElement | undefined
    for (const element of block.elements) {
      if (!element.isConnected) continue
      const rect = element.getBoundingClientRect()
      if (rect.width === 0 && rect.height === 0) continue
      if (rect.top + scrollTop < docTop) {
        docTop = rect.top + scrollTop
        target = element
      }
      docBottom = Math.max(docBottom, rect.bottom + scrollTop)
    }
    if (!target) continue

    const top = clamp(docTop * scale, 0, clientHeight - minMarkerHeight)
    const height = Math.min(Math.max(minMarkerHeight, (docBottom - docTop) * scale), clientHeight - top)
    markers.push({ kind: block.kind, top, height, target })
  }
  return markers.sort((left, right) => left.top - right.top)
}

/**
 * Paints every marker as its own background layer of the viewport scrollbar track, so overlapping markers need no
 * merging. Concrete colors are used because custom properties are not reliably inherited by the scrollbar parts of the
 * viewport across engines.
 */
export function trackBackgroundCss(markers: TrackMarker[], colors: MarkerColors): string {
  if (markers.length === 0) return ""

  const images = markers.map(marker => `linear-gradient(${colors[marker.kind]}, ${colors[marker.kind]})`)
  const sizes = markers.map(marker => `calc(100% - ${markerHorizontalInset * 2}px) ${px(marker.height)}`)
  const positions = markers.map(marker => `${markerHorizontalInset}px ${px(marker.top)}`)
  return `html::-webkit-scrollbar-track,\nbody::-webkit-scrollbar-track {\n`
    + `  background-image: ${images.join(", ")};\n`
    + `  background-size: ${sizes.join(", ")};\n`
    + `  background-position: ${positions.join(", ")};\n`
    + `  background-repeat: no-repeat;\n`
    + `}\n`
}

/**
 * Approximates the native thumb position: the thumb length is proportional to the visible part of the document but not
 * shorter than the `min-height` of `::-webkit-scrollbar-thumb`.
 */
export function thumbRange(metrics: ScrollMetrics, minThumb = 24): { top: number, bottom: number } {
  const { scrollTop, scrollHeight, clientHeight } = metrics
  if (scrollHeight <= clientHeight || clientHeight <= 0) return { top: 0, bottom: 0 }

  const length = Math.min(clientHeight, Math.max(minThumb, clientHeight * clientHeight / scrollHeight))
  const maxScrollTop = scrollHeight - clientHeight
  const top = clamp(scrollTop / maxScrollTop, 0, 1) * (clientHeight - length)
  return { top, bottom: top + length }
}

export function hitTestMarker(markers: TrackMarker[], y: number, tolerance = 2): TrackMarker | undefined {
  let bestMarker: TrackMarker | undefined
  let bestDistance = Number.POSITIVE_INFINITY
  for (const marker of markers) {
    const distance = y < marker.top ? marker.top - y : y > marker.top + marker.height ? y - marker.top - marker.height : 0
    if (distance <= tolerance && distance < bestDistance) {
      bestMarker = marker
      bestDistance = distance
    }
  }
  return bestMarker
}

function px(value: number): string {
  return `${Math.round(value * 100) / 100}px`
}

function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), Math.max(min, max))
}
