// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { createRoot } from "react-dom/client"
import {
  apiId,
  getPerfLogger,
  webView,
  webViewTheme,
  type WebViewCallable,
  type WebViewImplementable,
} from "@nerzhulart/intellij-webview-sdk"
import { MarkdownPreviewApp, scrollMarkdownPreviewToLine } from "./MarkdownPreviewApp"
import { installScrollbarChangeMarkers, refreshScrollbarChangeMarkers, setScrollbarChangeMarkers } from "./markdownChangeMarkers"
import { markdownDiagnosticDetails } from "./markdownDiagnostics"
import { installMarkdownPageZoom } from "./markdownPageZoom"
import {
  decorateSourceBlocks,
  isProgrammaticMarkdownPreviewScroll,
  markProgrammaticMarkdownPreviewScroll,
  topVisibleSourceLine,
} from "./markdownSourcePositions"
import type {
  MarkdownAddToAgentContextParams,
  MarkdownAddToAgentContextResultParams,
  MarkdownChangeColors,
  MarkdownChangedBlockDescriptor,
  MarkdownLinkColors,
  MarkdownNavigatePathLinkRequest,
  MarkdownPreviewSettings,
  MarkdownResolvePathLinksRequest,
  MarkdownResolvedPathLinksResponse,
  MarkdownResolveRunCommandsRequest,
  MarkdownResolvedRunCommandsResponse,
  MarkdownRunCommandRequest,
  MarkdownSetFontSizeRequest,
  MarkdownSourceRange,
} from "./markdownPreviewTypes"

interface MarkdownContentChangedParams {
  markdown: string
  scrollLine: number
  settings: MarkdownPreviewSettings
  contentVersion: number
  changes: MarkdownChangedBlockDescriptor[]
}

interface MarkdownScrollToLineParams {
  line: number
}

interface MarkdownPageZoomParams {
  zoom: number
}

interface MarkdownPreviewPageApi extends WebViewImplementable {
  contentChanged(params: MarkdownContentChangedParams): void
  scrollToLine(params: MarkdownScrollToLineParams): void
  selectionChanged(params: MarkdownSelectionChangedParams): void
  setPageZoom(params: MarkdownPageZoomParams): void
  setChangeColors(params: MarkdownChangeColors): void
  setLinkColors(params: MarkdownLinkColors): void
}

interface MarkdownSelectionChangedParams {
  selection?: MarkdownSourceRange | null
}

interface MarkdownPreviewHostApi extends WebViewCallable {
  pageReady(): Promise<void>
  openLink(params: MarkdownOpenLinkParams): Promise<void>
  resolveRunCommands(params: MarkdownResolveRunCommandsRequest): Promise<MarkdownResolvedRunCommandsResponse>
  runCommand(params: MarkdownRunCommandRequest): Promise<void>
  resolvePathLinks(params: MarkdownResolvePathLinksRequest): Promise<MarkdownResolvedPathLinksResponse>
  navigatePathLink(params: MarkdownNavigatePathLinkRequest): Promise<void>
  setFontSize(params: MarkdownSetFontSizeRequest): Promise<void>
  previewScrolled(params: MarkdownPreviewScrolledParams): Promise<void>
  pageZoomChanged(params: MarkdownPageZoomChangedParams): Promise<void>
  addToAgentContext(params: MarkdownAddToAgentContextParams): Promise<MarkdownAddToAgentContextResultParams>
}

interface MarkdownPageZoomChangedParams {
  zoom: number
  sequence: number
}

interface MarkdownPreviewScrolledParams {
  line: number
}

interface MarkdownOpenLinkParams {
  href: string
}

const markdownPreviewPageApiId = apiId<MarkdownPreviewPageApi>()("markdown.preview")
const markdownPreviewHostApi = webView.callable(apiId<MarkdownPreviewHostApi>()("markdown.preview"))
const markdownLogger = getPerfLogger("markdown")
const cssHexColorPattern = /^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$/
const contentElement = requiredElement<HTMLElement>("content")
const root = createRoot(contentElement)
let markdown = ""
let scrollLine = 0
let contentVersion = -1
let changes: MarkdownChangedBlockDescriptor[] = []
let selection: MarkdownSourceRange | undefined
let theme = webViewTheme.current
let previewSettings = defaultPreviewSettings()
let reportedScrollLine = -1
let scheduledScrollReportFrame: number | undefined
let reportedPageZoom = 1
let scheduledPageZoomReportFrame: number | undefined
let pageZoomReportSequence = 0

webView.implement(markdownPreviewPageApiId, {
  contentChanged(params) {
    const startedAtMs = performance.now()
    const details = markdownDiagnosticDetails(
      params.markdown,
      params.contentVersion,
      `scrollLine=${params.scrollLine}, changes=${params.changes?.length ?? 0}`,
    )
    markdownLogger.event("contentChanged.received", details)
    markdown = params.markdown
    scrollLine = params.scrollLine
    contentVersion = params.contentVersion
    previewSettings = normalizePreviewSettings(params.settings)
    applyPreviewSettings(previewSettings)
    changes = params.changes ?? []
    renderPreview("contentChanged", startedAtMs, details)
  },
  scrollToLine(params) {
    scrollLine = params.line
    scrollMarkdownPreviewToLine(scrollLine)
  },
  selectionChanged(params) {
    selection = params.selection ?? undefined
    setScrollbarChangeMarkers(decorateSourceBlocks(selection, changes))
  },
  setPageZoom(params) {
    // The zoom keeps the viewport center in place by scrolling, which must not drive the scroll of the zoom source back.
    markProgrammaticMarkdownPreviewScroll()
    pageZoom.setZoom(params.zoom, "host")
    reportedPageZoom = pageZoom.zoom()
  },
  setChangeColors(params) {
    applyChangeColors(params)
    refreshScrollbarChangeMarkers()
  },
  setLinkColors(params) {
    applyLinkColors(params)
  },
})

applyTheme(theme)
installScrollbarChangeMarkers()
// Trackpad pinch and Ctrl+wheel magnify the rendered markdown (`.markdownPreviewContent`) without re-layout, not the floating controls.
const pageZoom = installMarkdownPageZoom()
pageZoom.subscribe(source => {
  if (source === "user") schedulePageZoomReport()
})
renderPreview("initial")
window.addEventListener("scroll", scheduleScrollReport, { passive: true })
webViewTheme.onChanged(nextTheme => {
  theme = nextTheme
  applyTheme(nextTheme)
  renderPreview("themeChanged")
  refreshScrollbarChangeMarkers()
})
void markdownPreviewHostApi.pageReady()

function renderPreview(reason = "render", startedAtMs = performance.now(), details = markdownDiagnosticDetails(markdown, contentVersion)): void {
  root.render(
    <MarkdownPreviewApp
      markdown={markdown}
      scrollLine={scrollLine}
      contentVersion={contentVersion}
      changes={changes}
      selection={selection}
      settings={previewSettings}
      pageZoom={pageZoom}
      theme={theme}
      onOpenLink={openMarkdownLink}
      onResolveRunCommands={resolveMarkdownRunCommands}
      onRunCommand={runMarkdownCommand}
      onResolvePathLinks={resolveMarkdownPathLinks}
      onNavigatePathLink={navigateMarkdownPathLink}
      onSetFontSize={setMarkdownFontSize}
      onAddToAgentContext={addMarkdownToAgentContext}
    />
  )
  requestAnimationFrame(() => {
    markdownLogger.perfSince("render.afterFrame", startedAtMs, `reason=${reason}, ${details}`)
  })
}

function scheduleScrollReport(): void {
  if (scheduledScrollReportFrame !== undefined) return
  scheduledScrollReportFrame = window.requestAnimationFrame(() => {
    scheduledScrollReportFrame = undefined
    reportScrollPosition()
  })
}

function reportScrollPosition(): void {
  if (isProgrammaticMarkdownPreviewScroll()) return

  const line = topVisibleSourceLine()
  if (line === undefined || line === reportedScrollLine) return

  reportedScrollLine = line
  void markdownPreviewHostApi.previewScrolled({ line })
}

// A pinch changes the zoom on every frame, so the host gets at most one update per frame with the latest zoom.
function schedulePageZoomReport(): void {
  if (scheduledPageZoomReportFrame !== undefined) return
  scheduledPageZoomReportFrame = window.requestAnimationFrame(() => {
    scheduledPageZoomReportFrame = undefined
    const zoom = pageZoom.zoom()
    if (zoom === reportedPageZoom) return

    reportedPageZoom = zoom
    void markdownPreviewHostApi.pageZoomChanged({ zoom, sequence: pageZoomReportSequence++ })
  })
}

function openMarkdownLink(href: string): void {
  void markdownPreviewHostApi.openLink({ href })
}

function resolveMarkdownRunCommands(request: MarkdownResolveRunCommandsRequest): Promise<MarkdownResolvedRunCommandsResponse> {
  return markdownPreviewHostApi.resolveRunCommands(request)
}

function runMarkdownCommand(request: MarkdownRunCommandRequest): void {
  void markdownPreviewHostApi.runCommand(request)
}

function resolveMarkdownPathLinks(request: MarkdownResolvePathLinksRequest): Promise<MarkdownResolvedPathLinksResponse> {
  return markdownPreviewHostApi.resolvePathLinks(request)
}

function navigateMarkdownPathLink(request: MarkdownNavigatePathLinkRequest): void {
  void markdownPreviewHostApi.navigatePathLink(request)
}

function setMarkdownFontSize(fontSize: number): void {
  if (fontSize === previewSettings.effectiveFontSize) return
  void markdownPreviewHostApi.setFontSize({ fontSize })
}

function addMarkdownToAgentContext(params: MarkdownAddToAgentContextParams): Promise<MarkdownAddToAgentContextResultParams> {
  return markdownPreviewHostApi.addToAgentContext(params)
}

function applyTheme(theme: "light" | "dark"): void {
  document.documentElement.dataset.theme = theme
}

function applyPreviewSettings(settings: MarkdownPreviewSettings): void {
  const fontSize = settings.fontSize
  if (typeof fontSize === "number" && Number.isFinite(fontSize) && fontSize > 0) {
    document.documentElement.style.setProperty("--markdown-preview-font-size", `${fontSize}px`)
  }
  else {
    document.documentElement.style.removeProperty("--markdown-preview-font-size")
  }
}

function applyChangeColors(colors: MarkdownChangeColors): void {
  const style = document.documentElement.style
  for (const kind of ["added", "modified", "removed"] as const) {
    const color = colors[kind]
    setColorProperty(style, `--markdown-diff-${kind}-bg`, color?.background)
    setColorProperty(style, `--markdown-diff-${kind}-stripe`, color?.stripe)
    setColorProperty(style, `--markdown-diff-${kind}-ignored-bg`, color?.ignoredBackground)
  }
}

function applyLinkColors(colors: MarkdownLinkColors): void {
  const style = document.documentElement.style
  setColorProperty(style, "--markdown-ide-link", colors.foreground)
  setColorProperty(style, "--markdown-ide-link-hover", colors.hover)
  setColorProperty(style, "--markdown-ide-link-pressed", colors.pressed)
}

function setColorProperty(style: CSSStyleDeclaration, property: string, value: unknown): void {
  if (typeof value === "string" && cssHexColorPattern.test(value)) {
    style.setProperty(property, value)
  }
  else {
    style.removeProperty(property)
  }
}

function defaultPreviewSettings(): MarkdownPreviewSettings {
  const fontSize = 13
  return {
    fontSize: null,
    effectiveFontSize: fontSize,
    defaultFontSize: fontSize,
    fontSizeOptions: [fontSize],
    canAddToAgentContext: false,
  }
}

function normalizePreviewSettings(settings: MarkdownPreviewSettings): MarkdownPreviewSettings {
  const effectiveFontSize = positiveFiniteNumber(settings.effectiveFontSize) ?? previewSettings.effectiveFontSize
  const defaultFontSize = positiveFiniteNumber(settings.defaultFontSize) ?? previewSettings.defaultFontSize
  const rawFontSizeOptions = Array.isArray(settings.fontSizeOptions) ? settings.fontSizeOptions : []
  const fontSizeOptions = uniqueSortedNumbers([...rawFontSizeOptions, effectiveFontSize, defaultFontSize])
  return {
    fontSize: settings.fontSize,
    effectiveFontSize,
    defaultFontSize,
    fontSizeOptions,
    canAddToAgentContext: Boolean(settings.canAddToAgentContext),
  }
}

function positiveFiniteNumber(value: unknown): number | undefined {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? value : undefined
}

function uniqueSortedNumbers(values: number[]): number[] {
  return Array.from(new Set(values.filter(value => Number.isFinite(value) && value > 0))).sort((left, right) => left - right)
}

function requiredElement<T extends HTMLElement>(id: string): T {
  const element = document.getElementById(id)
  if (!element) {
    throw new Error(`Missing element #${id}`)
  }
  return element as T
}
