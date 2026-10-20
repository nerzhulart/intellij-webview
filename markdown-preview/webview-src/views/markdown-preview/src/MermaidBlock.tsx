// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { useEffect, useRef, useState } from "react"
import { select } from "d3-selection"
import { zoom, zoomIdentity, type D3ZoomEvent, type ZoomBehavior } from "d3-zoom"
import type mermaidApi from "mermaid"
import {
  MARKDOWN_ZOOM_BUTTON_FACTOR,
  MARKDOWN_ZOOM_SCALE_EXTENT,
  MarkdownZoomToolbar,
  fitToContentTransform,
  shouldHandleZoomEvent,
} from "./MarkdownZoomControls"
import { attachMarkdownWheelZoom, type MarkdownWheelZoom } from "./markdownWheelZoom"

interface MermaidBlockProps {
  chart: string
  theme: "light" | "dark"
}

type RenderState =
  | { kind: "rendering" }
  | { kind: "rendered", svg: string }
  | { kind: "error", message: string }

let mermaidBlockId = 0
let mermaidRenderId = 0
let mermaidModule: Promise<Mermaid> | undefined
const PRESERVED_SVG_TAGS = new Set(["defs", "style", "title", "desc", "metadata", "marker"])

export function MermaidBlock({ chart, theme }: MermaidBlockProps) {
  const hostId = useRef(`markdown-preview-mermaid-${++mermaidBlockId}`)
  const [state, setState] = useState<RenderState>({ kind: "rendering" })

  useEffect(() => {
    let cancelled = false
    const renderId = `${hostId.current}-${++mermaidRenderId}`

    // Keep the previous diagram visible while the new theme renders to avoid flicker.
    setState(current => current.kind === "rendered" ? current : { kind: "rendering" })
    loadMermaid()
      .then(mermaid => {
        configureMermaid(mermaid, theme)
        return mermaid.render(renderId, chart)
      })
      .then(({ svg }) => {
        if (!cancelled) {
          setState({ kind: "rendered", svg })
        }
      })
      .catch(error => {
        if (!cancelled) {
          setState({ kind: "error", message: error instanceof Error ? error.message : "Failed to render Mermaid diagram" })
        }
      })

    return () => {
      cancelled = true
    }
  }, [chart, theme])

  if (state.kind === "rendered") {
    return <RenderedMermaidDiagram svg={state.svg} />
  }
  if (state.kind === "error") {
    return (
      <div className="mermaidBlock hasError">
        <div className="mermaidError">{state.message}</div>
        <pre><code>{chart}</code></pre>
      </div>
    )
  }
  return <div className="mermaidBlock isRendering">Rendering diagram...</div>
}

function RenderedMermaidDiagram({ svg }: { svg: string }) {
  const hostRef = useRef<HTMLDivElement>(null)
  const svgRef = useRef<SVGSVGElement | null>(null)
  const zoomBehaviorRef = useRef<ZoomBehavior<SVGSVGElement, unknown> | null>(null)
  const wheelZoomRef = useRef<MarkdownWheelZoom | null>(null)

  useEffect(() => {
    const host = hostRef.current
    if (!host) return

    host.innerHTML = svg
    const svgElement = host.querySelector<SVGSVGElement>("svg")
    if (!svgElement) {
      return () => {
        host.innerHTML = ""
      }
    }

    prepareSvg(svgElement, "mermaidSvg")
    const panZoomGroup = wrapSvgContent(svgElement, "mermaidPanZoom")
    fitSvgViewBoxToContent(svgElement, panZoomGroup)
    applyNaturalSvgSize(svgElement)
    svgRef.current = svgElement

    const zoomBehavior = zoom<SVGSVGElement, unknown>()
      .filter(shouldHandleZoomEvent)
      .scaleExtent(MARKDOWN_ZOOM_SCALE_EXTENT)
      .on("zoom", (event: D3ZoomEvent<SVGSVGElement, unknown>) => {
        panZoomGroup.setAttribute("transform", event.transform.toString())
      })
    zoomBehaviorRef.current = zoomBehavior

    const svgSelection = select<SVGSVGElement, unknown>(svgElement)
    svgSelection.call(zoomBehavior)
    svgSelection.call(zoomBehavior.transform, zoomIdentity)
    const wheelZoom = attachMarkdownWheelZoom(svgElement, zoomBehavior)
    wheelZoomRef.current = wheelZoom

    return () => {
      wheelZoom.dispose()
      svgSelection.on(".zoom", null)
      host.innerHTML = ""
      svgRef.current = null
      zoomBehaviorRef.current = null
      wheelZoomRef.current = null
    }
  }, [svg])

  function zoomBy(factor: number): void {
    const svgElement = svgRef.current
    const zoomBehavior = zoomBehaviorRef.current
    if (!svgElement || !zoomBehavior) return
    wheelZoomRef.current?.cancel()
    select<SVGSVGElement, unknown>(svgElement).call(zoomBehavior.scaleBy, factor)
  }

  function resetZoom(): void {
    const svgElement = svgRef.current
    const zoomBehavior = zoomBehaviorRef.current
    if (!svgElement || !zoomBehavior) return
    wheelZoomRef.current?.cancel()
    select<SVGSVGElement, unknown>(svgElement).call(zoomBehavior.transform, zoomIdentity)
  }

  function fitToContent(): void {
    const svgElement = svgRef.current
    const zoomBehavior = zoomBehaviorRef.current
    const viewport = hostRef.current
    if (!svgElement || !zoomBehavior || !viewport) return
    const viewBox = svgElement.viewBox.baseVal
    if (!viewBox) return
    // The SVG element clips its content, so the fitted diagram must stay within both the viewport and the SVG box.
    const fitted = fitToContentTransform(
      Math.min(viewport.clientWidth, viewBox.width),
      Math.min(viewport.clientHeight, viewBox.height),
      viewBox.width,
      viewBox.height,
    )
    if (!fitted) return
    viewport.scrollLeft = 0
    viewport.scrollTop = 0
    wheelZoomRef.current?.cancel()
    // The transform applies in viewBox units, so shift it by the viewBox origin to keep the content centered.
    const transform = zoomIdentity
      .translate(fitted.x + viewBox.x * (1 - fitted.k), fitted.y + viewBox.y * (1 - fitted.k))
      .scale(fitted.k)
    select<SVGSVGElement, unknown>(svgElement).call(zoomBehavior.transform, transform)
  }

  return (
    <div className="mermaidBlock isInteractive">
      <div className="mermaidViewport" ref={hostRef} />
      <MarkdownZoomToolbar
        targetLabel="diagram"
        className="mermaidToolbar"
        buttonClassName="mermaidToolbarButton"
        onZoomOut={() => zoomBy(1 / MARKDOWN_ZOOM_BUTTON_FACTOR)}
        onResetZoom={resetZoom}
        onZoomIn={() => zoomBy(MARKDOWN_ZOOM_BUTTON_FACTOR)}
        onFitToContent={fitToContent}
      />
    </div>
  )
}

function prepareSvg(svgElement: SVGSVGElement, className: string): void {
  svgElement.classList.add(className)
  svgElement.setAttribute("preserveAspectRatio", "xMidYMid meet")
  if (!svgElement.hasAttribute("viewBox")) {
    const width = svgDimension(svgElement.getAttribute("width"))
    const height = svgDimension(svgElement.getAttribute("height"))
    if (width && height) svgElement.setAttribute("viewBox", `0 0 ${width} ${height}`)
  }
  svgElement.removeAttribute("width")
  svgElement.removeAttribute("height")
  svgElement.style.removeProperty("width")
  svgElement.style.removeProperty("height")
  svgElement.style.removeProperty("max-width")
}

function wrapSvgContent(svgElement: SVGSVGElement, className: string): SVGGElement {
  for (const child of Array.from(svgElement.children)) {
    if (child.tagName.toLowerCase() === "g" && child.classList.contains(className)) return child as SVGGElement
  }

  const group = document.createElementNS("http://www.w3.org/2000/svg", "g")
  group.setAttribute("class", className)
  for (const child of Array.from(svgElement.childNodes)) {
    if (child.nodeType !== Node.ELEMENT_NODE) continue
    const element = child as Element
    if (PRESERVED_SVG_TAGS.has(element.tagName.toLowerCase())) continue
    group.appendChild(element)
  }
  svgElement.appendChild(group)
  return group
}

function fitSvgViewBoxToContent(svgElement: SVGSVGElement, contentElement: SVGGraphicsElement): void {
  try {
    const box = contentElement.getBBox()
    if (box.width <= 0 || box.height <= 0) return
    const padding = 24
    svgElement.setAttribute("viewBox", `${box.x - padding} ${box.y - padding} ${box.width + padding * 2} ${box.height + padding * 2}`)
  }
  catch {
    // Some SVG fragments cannot be measured until attached; keep Mermaid's viewBox in that case.
  }
}

function applyNaturalSvgSize(svgElement: SVGSVGElement): void {
  const viewBox = svgElement.viewBox.baseVal
  if (!viewBox || viewBox.width <= 0 || viewBox.height <= 0) return
  svgElement.style.width = `${viewBox.width}px`
  svgElement.style.height = `${viewBox.height}px`
}

function svgDimension(value: string | null): number | undefined {
  if (!value) return undefined
  const dimension = Number.parseFloat(value)
  return Number.isFinite(dimension) && dimension > 0 ? dimension : undefined
}

function loadMermaid(): Promise<Mermaid> {
  mermaidModule ||= import("mermaid").then(module => module.default)
  return mermaidModule
}

function configureMermaid(mermaid: Mermaid, theme: "light" | "dark"): void {
  const isLight = theme === "light"
  const panel = cssVariable("--ij-bg-panel", isLight ? "#F7F8F9" : "#212326")
  const panelAlt = cssVariable("--ij-bg-panel-alt", isLight ? "#FFFFFF" : "#26282C")
  const border = cssVariable("--ij-border-strong", isLight ? "#D1D3D9" : "#40434A")
  const textPrimary = cssVariable("--ij-text-primary", isLight ? "#000000" : "#D1D3D9")
  const textSecondary = cssVariable("--ij-text-secondary", "#73767C")
  const accent = cssVariable("--ij-accent", "#3871E1")
  const font = cssVariable("--ij-font", "Inter, Segoe UI, -apple-system, BlinkMacSystemFont, Helvetica Neue, sans-serif")
  const palette = isLight ? LIGHT_PALETTE : DARK_PALETTE
  const scale: Record<string, string> = {}
  palette.scale.forEach((color, index) => {
    scale[`cScale${index}`] = color.fill
    scale[`cScalePeer${index}`] = color.border
    scale[`cScaleLabel${index}`] = textPrimary
    scale[`pie${index + 1}`] = color.solid
    scale[`git${index}`] = color.solid
    scale[`gitInv${index}`] = color.fill
    scale[`gitBranchLabel${index}`] = isLight ? "#FFFFFF" : "#000000"
    scale[`cScaleInv${index}`] = color.border
    scale[`fillType${index}`] = color.fill
  })

  mermaid.initialize({
    startOnLoad: false,
    theme: "base",
    securityLevel: "strict",
    suppressErrorRendering: true,
    themeVariables: {
      fontFamily: font,
      fontSize: bodyFontSize(),
      darkMode: !isLight,
      background: panel,
      ...scale,
      primaryColor: palette.primary.fill,
      primaryBorderColor: palette.primary.border,
      primaryTextColor: textPrimary,
      secondaryColor: palette.secondary.fill,
      secondaryBorderColor: palette.secondary.border,
      secondaryTextColor: textPrimary,
      tertiaryColor: palette.tertiary.fill,
      tertiaryBorderColor: palette.tertiary.border,
      tertiaryTextColor: textPrimary,
      mainBkg: palette.primary.fill,
      clusterBkg: panelAlt,
      clusterBorder: border,
      pieStrokeColor: panel,
      pieOuterStrokeColor: border,
      pieSectionTextColor: isLight ? "#FFFFFF" : "#000000",
      pieTitleTextColor: textPrimary,
      pieLegendTextColor: textPrimary,
      lineColor: textSecondary,
      textColor: textPrimary,
      titleColor: textPrimary,
      nodeBorder: palette.primary.border,
      edgeLabelBackground: panel,
      signalColor: textPrimary,
      actorBorder: palette.primary.border,
      actorBkg: palette.primary.fill,
      actorTextColor: textPrimary,
      actorLineColor: textSecondary,
      labelBoxBkgColor: palette.secondary.fill,
      labelBoxBorderColor: palette.secondary.border,
      noteBkgColor: palette.note.fill,
      noteBorderColor: palette.note.border,
      noteTextColor: textPrimary,
      labelTextColor: textPrimary,
      loopTextColor: textPrimary,
      sequenceNumberColor: isLight ? "#FFFFFF" : "#000000",
      classText: textPrimary,
      nodeTextColor: textPrimary,
      stateLabelColor: textPrimary,
      altBackground: panelAlt,
      compositeBackground: panelAlt,
      compositeTitleBackground: panel,
      attributeBackgroundColorOdd: panel,
      attributeBackgroundColorEven: panelAlt,
      taskTextColor: textPrimary,
      taskTextDarkColor: textPrimary,
      taskTextOutsideColor: textPrimary,
      sectionBkgColor: palette.primary.fill,
      altSectionBkgColor: panel,
      sectionBkgColor2: palette.secondary.fill,
      taskBkgColor: palette.primary.fill,
      taskBorderColor: palette.primary.border,
      activeTaskBkgColor: palette.tertiary.fill,
      activeTaskBorderColor: palette.tertiary.border,
      doneTaskBkgColor: palette.secondary.fill,
      doneTaskBorderColor: palette.secondary.border,
      gridColor: border,
      todayLineColor: accent,
      relationColor: textSecondary,
      relationLabelColor: textPrimary,
      relationLabelBackground: panel,
      activationBkgColor: palette.tertiary.fill,
      activationBorderColor: accent,
    },
    themeCSS: `
      .node rect,
      .node circle,
      .node ellipse,
      .node polygon,
      .node path {
        rx: 4px;
        ry: 4px;
      }
      .label,
      .edgeLabel,
      .cluster-label,
      .messageText {
        color: ${textPrimary};
        fill: ${textPrimary};
        font-family: ${font};
      }
      .edgeLabel,
      .edgeLabel p,
      .edgeLabel span {
        background: ${panel};
        color: ${textPrimary};
      }
      .flowchart-link,
      .messageLine0,
      .messageLine1 {
        stroke: ${textSecondary};
      }
      .marker {
        fill: ${textSecondary};
        stroke: ${textSecondary};
      }
    `,
  })
}

interface MermaidSwatch {
  fill: string
  border: string
  solid: string
}

interface MermaidPalette {
  primary: MermaidSwatch
  secondary: MermaidSwatch
  tertiary: MermaidSwatch
  note: MermaidSwatch
  scale: MermaidSwatch[]
}

const LIGHT_SWATCHES: Record<string, MermaidSwatch> = {
  blue: { fill: "#E3ECFC", border: "#3871E1", solid: "#3871E1" },
  green: { fill: "#E2F5E6", border: "#3E9B4F", solid: "#4DAA5E" },
  purple: { fill: "#EFE6FB", border: "#8C5AD6", solid: "#9B6BE0" },
  orange: { fill: "#FDEBDC", border: "#D9792B", solid: "#E8893A" },
  teal: { fill: "#DDF3F3", border: "#2A9A9A", solid: "#30A8A8" },
  pink: { fill: "#FBE4EE", border: "#C94F84", solid: "#D95C93" },
  yellow: { fill: "#FDF4D3", border: "#C9A227", solid: "#E0B532" },
  red: { fill: "#FCE3E3", border: "#D14B4B", solid: "#E05555" },
}

const DARK_SWATCHES: Record<string, MermaidSwatch> = {
  blue: { fill: "#25324D", border: "#548AF7", solid: "#548AF7" },
  green: { fill: "#233B2A", border: "#57A866", solid: "#5FB86F" },
  purple: { fill: "#352A4A", border: "#A57BEA", solid: "#A57BEA" },
  orange: { fill: "#45321F", border: "#E0914A", solid: "#E89A55" },
  teal: { fill: "#1F3A3B", border: "#3FB2B2", solid: "#45BDBD" },
  pink: { fill: "#45263A", border: "#E06C9F", solid: "#E57BAA" },
  yellow: { fill: "#40391F", border: "#D6B443", solid: "#DDBE50" },
  red: { fill: "#472729", border: "#E06666", solid: "#E57373" },
}

function mermaidPalette(swatches: Record<string, MermaidSwatch>): MermaidPalette {
  return {
    primary: swatches.blue,
    secondary: swatches.green,
    tertiary: swatches.purple,
    note: swatches.yellow,
    scale: [swatches.blue, swatches.green, swatches.purple, swatches.orange, swatches.teal, swatches.pink, swatches.yellow, swatches.red],
  }
}

const LIGHT_PALETTE = mermaidPalette(LIGHT_SWATCHES)
const DARK_PALETTE = mermaidPalette(DARK_SWATCHES)

function bodyFontSize(): string {
  const content = document.querySelector(".markdownPreviewContent")
  const measured = content ? Number.parseFloat(getComputedStyle(content).fontSize) : Number.NaN
  if (Number.isFinite(measured) && measured > 0) return `${measured}px`
  return cssVariable("--ij-font-size", "13px")
}

function cssVariable(name: string, fallback: string): string {
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback
  return value.replace(/^#([0-9a-fA-F]{6})[0-9a-fA-F]{2}$/, "#$1")
}

type Mermaid = typeof mermaidApi
