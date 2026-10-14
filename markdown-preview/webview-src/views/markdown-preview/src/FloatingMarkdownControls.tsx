// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { addWebViewFocusLeaveListener, AllIcons } from "@nerzhulart/intellij-webview-sdk"
import { useEffect, useMemo, useRef, useState, useSyncExternalStore, type ChangeEvent } from "react"
import type { MarkdownPageZoom } from "./markdownPageZoom"
import type { MarkdownPreviewSettings } from "./markdownPreviewTypes"
import { MARKDOWN_ZOOM_BUTTON_FACTOR } from "./MarkdownZoomControls"

interface FloatingMarkdownControlsProps {
  markdown: string
  settings: MarkdownPreviewSettings
  pageZoom: MarkdownPageZoom
  onSetFontSize: (fontSize: number) => void
}

interface TableOfContentsEntry {
  id: string
  text: string
  level: number
  element: HTMLElement
}

const headingSelector = "h1[id], h2[id], h3[id], h4[id], h5[id], h6[id]"
const activeHeadingTopOffset = 80
type FloatingPanel = "toc" | "settings"
// The page zoom slider is logarithmic, so that zooming in and out by the same factor moves it by the same distance.
const pageZoomSliderStep = 0.01
const pageZoomSnapLogRatio = 0.01

export function FloatingMarkdownControls({ markdown, settings, pageZoom, onSetFontSize }: FloatingMarkdownControlsProps) {
  const [entries, setEntries] = useState<TableOfContentsEntry[]>([])
  const [openPanel, setOpenPanel] = useState<FloatingPanel | undefined>()
  const [activeId, setActiveId] = useState<string | undefined>()
  const railRef = useRef<HTMLDivElement>(null)
  const panelRef = useRef<HTMLElement>(null)
  const hasTableOfContents = entries.length >= 2
  const fontSizeOptions = useMemo(() => normalizedFontSizeOptions(settings), [settings])
  const currentFontSizeIndex = fontSizeIndex(settings.effectiveFontSize, fontSizeOptions)

  useEffect(() => {
    const nextEntries = collectTableOfContentsEntries()
    setEntries(nextEntries)
    setActiveId(nextEntries[0]?.id)
  }, [markdown])

  useEffect(() => {
    if (entries.length < 2) {
      setActiveId(undefined)
      return
    }

    let scheduledFrame: number | undefined
    const updateActiveHeading = (): void => {
      let active = entries[0].id
      for (const entry of entries) {
        if (entry.element.getBoundingClientRect().top <= activeHeadingTopOffset) {
          active = entry.id
        }
        else {
          break
        }
      }
      setActiveId(current => current === active ? current : active)
    }
    const scheduleUpdate = (): void => {
      if (scheduledFrame !== undefined) return
      scheduledFrame = window.requestAnimationFrame(() => {
        scheduledFrame = undefined
        updateActiveHeading()
      })
    }

    scheduleUpdate()
    window.addEventListener("scroll", scheduleUpdate, { passive: true })
    window.addEventListener("resize", scheduleUpdate)
    return () => {
      if (scheduledFrame !== undefined) window.cancelAnimationFrame(scheduledFrame)
      window.removeEventListener("scroll", scheduleUpdate)
      window.removeEventListener("resize", scheduleUpdate)
    }
  }, [entries])

  useEffect(() => {
    return addWebViewFocusLeaveListener(() => setOpenPanel(undefined))
  }, [])

  // Any page zoom change (pinch, Ctrl+wheel) shows the options panel with the current zoom and its reset button.
  // Re-opening an already open panel is a no-op state update, so pinch frames do not re-render the controls.
  useEffect(() => {
    return pageZoom.subscribe(() => setOpenPanel("settings"))
  }, [pageZoom])

  useEffect(() => {
    if (openPanel === undefined) return

    const handlePointerDown = (event: PointerEvent): void => {
      const target = event.target
      if (target instanceof Node && (panelRef.current?.contains(target) || railRef.current?.contains(target))) return
      // Dragging the page scrollbar keeps the panel open.
      const root = document.documentElement
      if (event.clientX >= root.clientWidth || event.clientY >= root.clientHeight) return
      setOpenPanel(undefined)
    }
    document.addEventListener("pointerdown", handlePointerDown, true)
    return () => document.removeEventListener("pointerdown", handlePointerDown, true)
  }, [openPanel])

  useEffect(() => {
    if (openPanel === undefined) return

    const handleKeyDown = (event: KeyboardEvent): void => {
      if (event.key === "Escape") setOpenPanel(undefined)
    }
    window.addEventListener("keydown", handleKeyDown)
    return () => window.removeEventListener("keydown", handleKeyDown)
  }, [openPanel])

  useEffect(() => {
    if (!hasTableOfContents && openPanel === "toc") setOpenPanel(undefined)
  }, [hasTableOfContents, openPanel])

  function togglePanel(panel: FloatingPanel): void {
    setOpenPanel(current => current === panel ? undefined : panel)
  }

  function closePanel(): void {
    setOpenPanel(undefined)
  }

  function scrollToEntry(entry: TableOfContentsEntry): void {
    const target = document.getElementById(entry.id)
    if (!target) return
    target.scrollIntoView({ block: "start", behavior: "smooth" })
    setActiveId(entry.id)
  }

  function requestFontSize(fontSize: number): void {
    const normalizedFontSize = closestFontSize(fontSize, fontSizeOptions)
    if (normalizedFontSize === settings.effectiveFontSize) return
    onSetFontSize(normalizedFontSize)
  }

  function handleFontSizeSliderChange(requestedIndex: number): void {
    const requestedFontSize = fontSizeOptions[requestedIndex]
    if (requestedFontSize !== undefined) requestFontSize(requestedFontSize)
  }

  return (
    <>
      <div ref={railRef} className="markdownFloatingRail" aria-label="Markdown preview controls">
        {hasTableOfContents && (
          <button
            type="button"
            className={classNames("markdownFloatingRailButton", openPanel === "toc" ? "is-active" : undefined)}
            title="Table of contents"
            aria-label="Show table of contents"
            aria-expanded={openPanel === "toc"}
            onClick={() => togglePanel("toc")}
          >
            <span className="markdownTocRailIcon" aria-hidden="true" />
          </button>
        )}
        <button
          type="button"
          className={classNames("markdownFloatingRailButton", openPanel === "settings" ? "is-active" : undefined)}
          title="Options"
          aria-label="Show preview options"
          aria-expanded={openPanel === "settings"}
          onClick={() => togglePanel("settings")}
        >
          <img className="markdownFloatingRailIcon" src={AllIcons.src("general/settings.svg")} alt="" draggable={false} />
        </button>
      </div>
      {openPanel === "toc" && hasTableOfContents && (
        <nav
          ref={panelRef}
          className="markdownFloatingPanel markdownTocPanel"
          aria-label="Table of contents"
        >
          <FloatingPanelHeader title="Contents" closeLabel="Collapse table of contents" onClose={closePanel} />
          <ol className="markdownTocList">
            {entries.map(entry => (
              <li key={entry.id} className="markdownTocItem">
                <button
                  type="button"
                  className={classNames("markdownTocLink", entry.id === activeId ? "is-active" : undefined)}
                  style={{ paddingLeft: `${8 + Math.max(0, entry.level - 1) * 12}px` }}
                  aria-current={entry.id === activeId ? "location" : undefined}
                  title={entry.text}
                  onClick={() => scrollToEntry(entry)}
                >
                  <span className="markdownTocText">{entry.text}</span>
                </button>
              </li>
            ))}
          </ol>
        </nav>
      )}
      {openPanel === "settings" && (
        <section
          ref={panelRef}
          className="markdownFloatingPanel markdownFontSettingsPanel"
          aria-label="Options"
        >
          <FloatingPanelHeader title="Options" closeLabel="Collapse preview options" onClose={closePanel} />
          <div className="markdownFontSettingsBody">
            <OptionSliderRow
              id="markdown-font-size-slider"
              label="Font size"
              valueText={`${settings.effectiveFontSize} px`}
              min={0}
              max={Math.max(0, fontSizeOptions.length - 1)}
              step={1}
              value={currentFontSizeIndex}
              disabled={fontSizeOptions.length < 2}
              onChange={handleFontSizeSliderChange}
              decrease={{
                label: "Decrease font size",
                disabled: currentFontSizeIndex <= 0,
                onClick: () => requestFontSize(fontSizeOptions[currentFontSizeIndex - 1] ?? settings.effectiveFontSize),
              }}
              increase={{
                label: "Increase font size",
                disabled: currentFontSizeIndex >= fontSizeOptions.length - 1,
                onClick: () => requestFontSize(fontSizeOptions[currentFontSizeIndex + 1] ?? settings.effectiveFontSize),
              }}
              reset={{
                label: "Reset font size",
                disabled: settings.effectiveFontSize === settings.defaultFontSize,
                onClick: () => requestFontSize(settings.defaultFontSize),
              }}
            />
            <PageZoomOptionRow pageZoom={pageZoom} />
          </div>
        </section>
      )}
    </>
  )
}

interface FloatingPanelHeaderProps {
  title: string
  closeLabel: string
  onClose: () => void
}

function FloatingPanelHeader({ title, closeLabel, onClose }: FloatingPanelHeaderProps) {
  return (
    <div className="markdownFloatingPanelHeader">
      <span className="markdownFloatingPanelTitle">{title}</span>
      <button
        type="button"
        className="markdownFloatingPanelCloseButton"
        title="Collapse"
        aria-label={closeLabel}
        onClick={onClose}
      >
        <span className="markdownFloatingPanelCloseIcon" aria-hidden="true" />
      </button>
    </div>
  )
}

interface PageZoomOptionRowProps {
  pageZoom: MarkdownPageZoom
}

// Subscribes to the page zoom only while the options panel is open, so pinch zoom does not re-render the controls.
function PageZoomOptionRow({ pageZoom }: PageZoomOptionRowProps) {
  const zoom = useSyncExternalStore(pageZoom.subscribe, pageZoom.zoom)
  const zoomPercent = Math.round(zoom * 100)

  function requestZoom(requestedZoom: number): void {
    const snappedZoom = Math.abs(Math.log(requestedZoom)) < pageZoomSnapLogRatio ? 1 : requestedZoom
    pageZoom.setZoom(Math.min(pageZoom.maxZoom, Math.max(pageZoom.minZoom, snappedZoom)))
  }

  return (
    <OptionSliderRow
      id="markdown-page-zoom-slider"
      label="Zoom"
      valueText={`${zoomPercent}%`}
      min={Math.log2(pageZoom.minZoom)}
      max={Math.log2(pageZoom.maxZoom)}
      step={pageZoomSliderStep}
      value={Math.log2(zoom)}
      disabled={pageZoom.maxZoom <= pageZoom.minZoom}
      onChange={value => requestZoom(2 ** value)}
      decrease={{
        label: "Zoom out page",
        disabled: zoom <= pageZoom.minZoom,
        onClick: () => requestZoom(zoom / MARKDOWN_ZOOM_BUTTON_FACTOR),
      }}
      increase={{
        label: "Zoom in page",
        disabled: zoom >= pageZoom.maxZoom,
        onClick: () => requestZoom(zoom * MARKDOWN_ZOOM_BUTTON_FACTOR),
      }}
      reset={{
        label: "Reset page zoom",
        disabled: zoom === 1,
        onClick: () => requestZoom(1),
      }}
    />
  )
}

interface OptionButtonAction {
  label: string
  disabled: boolean
  onClick: () => void
}

interface OptionSliderRowProps {
  id: string
  label: string
  valueText: string
  min: number
  max: number
  step: number
  value: number
  disabled: boolean
  onChange: (value: number) => void
  decrease: OptionButtonAction
  increase: OptionButtonAction
  reset: OptionButtonAction
}

/** One options line: label, decrease button, slider, increase button, current value, and reset button. */
function OptionSliderRow({ id, label, valueText, min, max, step, value, disabled, onChange, decrease, increase, reset }: OptionSliderRowProps) {
  function handleChange(event: ChangeEvent<HTMLInputElement>): void {
    const requestedValue = Number(event.currentTarget.value)
    if (Number.isFinite(requestedValue)) onChange(requestedValue)
  }

  return (
    <div className="markdownFontSetting" role="group" aria-label={`${label} controls`}>
      <label className="markdownFontSizeSliderLabel" htmlFor={id}>{label}</label>
      <FontSizeButton icon="expui/image/zoomOut.svg" {...decrease} />
      <input
        id={id}
        type="range"
        className="markdownFontSizeSlider"
        min={min}
        max={max}
        step={step}
        value={value}
        disabled={disabled}
        aria-valuetext={valueText}
        onChange={handleChange}
      />
      <FontSizeButton icon="expui/image/zoomIn.svg" {...increase} />
      <output className="markdownFontSizeValue" htmlFor={id}>{valueText}</output>
      <FontSizeButton icon="expui/image/actualZoom.svg" bordered {...reset} />
    </div>
  )
}

interface FontSizeButtonProps extends OptionButtonAction {
  icon: string
  /** Draws a permanent outline, like an IntelliJ action button with a border. */
  bordered?: boolean
}

function FontSizeButton({ icon, bordered = false, label, disabled, onClick }: FontSizeButtonProps) {
  return (
    <button
      type="button"
      className={bordered ? "markdownFontSizeButton is-bordered" : "markdownFontSizeButton"}
      title={label}
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
    >
      <img src={AllIcons.src(icon)} alt="" draggable={false} />
    </button>
  )
}

function collectTableOfContentsEntries(): TableOfContentsEntry[] {
  const contentElement = document.getElementById("content")
  if (!contentElement) return []

  return Array.from(contentElement.querySelectorAll<HTMLElement>(headingSelector))
    .map(heading => {
      const text = normalizeHeadingText(heading.textContent ?? "")
      if (!heading.id || !text || heading.classList.contains("sr-only") || heading.closest(".footnotes")) return undefined
      return {
        id: heading.id,
        text,
        level: headingLevel(heading),
        element: heading,
      }
    })
    .filter((entry): entry is TableOfContentsEntry => entry !== undefined)
}

function headingLevel(heading: HTMLElement): number {
  const level = Number(heading.tagName.substring(1))
  return Number.isFinite(level) ? Math.min(6, Math.max(1, level)) : 1
}

function normalizeHeadingText(text: string): string {
  return text.replace(/\s+/g, " ").trim()
}

function normalizedFontSizeOptions(settings: MarkdownPreviewSettings): number[] {
  return Array.from(new Set([...settings.fontSizeOptions, settings.effectiveFontSize, settings.defaultFontSize]
    .filter(value => Number.isFinite(value) && value > 0)))
    .sort((left, right) => left - right)
}

function fontSizeIndex(fontSize: number, options: number[]): number {
  return Math.max(0, options.indexOf(closestFontSize(fontSize, options)))
}

function closestFontSize(fontSize: number, options: number[]): number {
  return options.reduce((closest, candidate) => {
    return Math.abs(candidate - fontSize) < Math.abs(closest - fontSize) ? candidate : closest
  }, options[0] ?? fontSize)
}

function classNames(...names: Array<string | undefined>): string | undefined {
  const className = names.filter(Boolean).join(" ")
  return className || undefined
}
