// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { useEffect, useRef, useState, type CSSProperties, type HTMLAttributes } from "react"
import { select } from "d3-selection"
import { zoom, zoomIdentity, type D3ZoomEvent, type ZoomBehavior } from "d3-zoom"
import { classNames } from "./markdownReactUtils"
import {
  MARKDOWN_ZOOM_BUTTON_FACTOR,
  MARKDOWN_ZOOM_SCALE_EXTENT,
  MarkdownZoomToolbar,
  fitToContentTransform,
  shouldHandleZoomEvent,
} from "./MarkdownZoomControls"
import { attachMarkdownWheelZoom, type MarkdownWheelZoom } from "./markdownWheelZoom"

interface MarkdownImageBlockProps extends HTMLAttributes<HTMLDivElement> {
  src: string
  alt?: string
  title?: string
}

export function MarkdownImageBlock({ src, alt, title, className, style, ...props }: MarkdownImageBlockProps) {
  const viewportRef = useRef<HTMLDivElement>(null)
  const imageRef = useRef<HTMLImageElement>(null)
  const zoomBehaviorRef = useRef<ZoomBehavior<HTMLDivElement, unknown> | null>(null)
  const wheelZoomRef = useRef<MarkdownWheelZoom | null>(null)
  const [naturalSize, setNaturalSize] = useState<{ width: number, height: number } | undefined>()

  useEffect(() => {
    const viewport = viewportRef.current
    const image = imageRef.current
    if (!viewport || !image) return

    const zoomBehavior = zoom<HTMLDivElement, unknown>()
      .filter(shouldHandleZoomEvent)
      .scaleExtent(MARKDOWN_ZOOM_SCALE_EXTENT)
      .on("zoom", (event: D3ZoomEvent<HTMLDivElement, unknown>) => {
        image.style.transform = `translate(${event.transform.x}px, ${event.transform.y}px) scale(${event.transform.k})`
      })
    zoomBehaviorRef.current = zoomBehavior

    const viewportSelection = select<HTMLDivElement, unknown>(viewport)
    viewportSelection.call(zoomBehavior)
    viewportSelection.call(zoomBehavior.transform, zoomIdentity)
    const wheelZoom = attachMarkdownWheelZoom(viewport, zoomBehavior)
    wheelZoomRef.current = wheelZoom

    return () => {
      wheelZoom.dispose()
      viewportSelection.on(".zoom", null)
      image.style.removeProperty("transform")
      zoomBehaviorRef.current = null
      wheelZoomRef.current = null
    }
  }, [src])

  useEffect(() => {
    updateNaturalSize()
  }, [src])

  function zoomBy(factor: number): void {
    const viewport = viewportRef.current
    const zoomBehavior = zoomBehaviorRef.current
    if (!viewport || !zoomBehavior) return
    wheelZoomRef.current?.cancel()
    select<HTMLDivElement, unknown>(viewport).call(zoomBehavior.scaleBy, factor)
  }

  function resetZoom(): void {
    const viewport = viewportRef.current
    const zoomBehavior = zoomBehaviorRef.current
    if (!viewport || !zoomBehavior) return
    wheelZoomRef.current?.cancel()
    select<HTMLDivElement, unknown>(viewport).call(zoomBehavior.transform, zoomIdentity)
  }

  // By default the image is laid out to fit the column, so 1:1 needs a scale of natural width over laid-out width.
  function actualSize(): void {
    const viewport = viewportRef.current
    const image = imageRef.current
    const zoomBehavior = zoomBehaviorRef.current
    if (!viewport || !image || !zoomBehavior || image.offsetWidth <= 0 || image.naturalWidth <= 0) return
    const [minScale, maxScale] = MARKDOWN_ZOOM_SCALE_EXTENT
    const scale = Math.min(maxScale, Math.max(minScale, image.naturalWidth / image.offsetWidth))
    viewport.scrollLeft = 0
    viewport.scrollTop = 0
    wheelZoomRef.current?.cancel()
    select<HTMLDivElement, unknown>(viewport).call(zoomBehavior.transform, zoomIdentity.scale(scale))
  }

  function fitToContent(): void {
    const viewport = viewportRef.current
    const image = imageRef.current
    const zoomBehavior = zoomBehaviorRef.current
    if (!viewport || !image || !zoomBehavior) return
    const transform = fitToContentTransform(viewport.clientWidth, viewport.clientHeight, image.offsetWidth, image.offsetHeight)
    if (!transform) return
    viewport.scrollLeft = 0
    viewport.scrollTop = 0
    wheelZoomRef.current?.cancel()
    select<HTMLDivElement, unknown>(viewport).call(zoomBehavior.transform, transform)
  }

  function updateNaturalSize(): void {
    const image = imageRef.current
    const width = image?.naturalWidth ?? 0
    const height = image?.naturalHeight ?? 0
    setNaturalSize(width > 0 && height > 0 ? { width, height } : undefined)
  }

  const blockStyle = naturalSize === undefined
    ? style
    : ({
      ...style,
      "--markdown-image-natural-width": `${naturalSize.width}px`,
      "--markdown-image-natural-height": `${naturalSize.height}px`,
    } as CSSProperties)

  return (
    <div {...props} className={classNames("markdownImageBlock", "isInteractive", className)} style={blockStyle}>
      <div className="markdownImageViewport" ref={viewportRef}>
        <img className="markdownImage" ref={imageRef} src={src} alt={alt ?? ""} title={title} draggable={false} onLoad={updateNaturalSize} />
      </div>
      <MarkdownZoomToolbar
        targetLabel="image"
        className="markdownImageToolbar"
        buttonClassName="markdownImageToolbarButton"
        onZoomOut={() => zoomBy(1 / MARKDOWN_ZOOM_BUTTON_FACTOR)}
        onResetZoom={actualSize}
        onZoomIn={() => zoomBy(MARKDOWN_ZOOM_BUTTON_FACTOR)}
        onFitToContent={fitToContent}
      />
    </div>
  )
}
