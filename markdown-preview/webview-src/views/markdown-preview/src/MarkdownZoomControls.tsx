// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { AllIcons } from "@nerzhulart/intellij-webview-sdk"
import { zoomIdentity, type ZoomTransform } from "d3-zoom"
import { classNames } from "./markdownReactUtils"

export const MARKDOWN_ZOOM_SCALE_EXTENT: [number, number] = [0.25, 4]
export const MARKDOWN_ZOOM_BUTTON_FACTOR = 1.2

interface MarkdownZoomToolbarProps {
  targetLabel: string
  className?: string
  buttonClassName?: string
  onZoomOut: () => void
  onResetZoom: () => void
  onZoomIn: () => void
  onFitToContent: () => void
}

export function MarkdownZoomToolbar({ targetLabel, className, buttonClassName, onZoomOut, onResetZoom, onZoomIn, onFitToContent }: MarkdownZoomToolbarProps) {
  const normalizedTargetLabel = targetLabel.toLowerCase()
  const accessibleTargetLabel = `${normalizedTargetLabel.charAt(0).toUpperCase()}${normalizedTargetLabel.slice(1)}`
  const buttonClass = classNames("markdownZoomToolbarButton", buttonClassName)

  return (
    <div className={classNames("markdownZoomToolbar", className)} aria-label={`${accessibleTargetLabel} zoom controls`}>
      <button type="button" className={buttonClass} aria-label={`Zoom out ${normalizedTargetLabel}`} title="Zoom out" onClick={onZoomOut}>
        <img src={AllIcons.src("graph/zoomOut.svg")} alt="" draggable={false} />
      </button>
      <button type="button" className={buttonClass} aria-label={`Zoom in ${normalizedTargetLabel}`} title="Zoom in" onClick={onZoomIn}>
        <img src={AllIcons.src("graph/zoomIn.svg")} alt="" draggable={false} />
      </button>
      <button type="button" className={buttonClass} aria-label={`Reset ${normalizedTargetLabel} to actual size`} title="Actual size" onClick={onResetZoom}>
        <img src={AllIcons.src("expui/image/actualZoom.svg")} alt="" draggable={false} />
      </button>
      <button type="button" className={buttonClass} aria-label={`Fit ${normalizedTargetLabel} to content`} title="Fit to content" onClick={onFitToContent}>
        <img src={AllIcons.src("expui/image/fitContent.svg")} alt="" draggable={false} />
      </button>
    </div>
  )
}

/** Returns the transform that scales content of the given size to fit the viewport and centers it. */
export function fitToContentTransform(viewportWidth: number, viewportHeight: number, contentWidth: number, contentHeight: number): ZoomTransform | undefined {
  if (contentWidth <= 0 || contentHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) return undefined
  const [minScale, maxScale] = MARKDOWN_ZOOM_SCALE_EXTENT
  const scale = Math.min(maxScale, Math.max(minScale, Math.min(viewportWidth / contentWidth, viewportHeight / contentHeight)))
  return zoomIdentity
    .translate((viewportWidth - contentWidth * scale) / 2, (viewportHeight - contentHeight * scale) / 2)
    .scale(scale)
}

export function shouldHandleZoomEvent(event: Event): boolean {
  // Ctrl+wheel (trackpad pinch) zoom is handled by attachMarkdownWheelZoom; plain wheel must keep page scrolling.
  return event.type !== "wheel"
}
