// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { addWebViewFocusLeaveListener } from "@nerzhulart/intellij-webview-sdk"
import { useCallback, useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from "react"
import type { MarkdownAddToAgentContextParams, MarkdownAddToAgentContextResultParams } from "./markdownPreviewTypes"

interface MarkdownAgentContextPopoverProps {
  contentVersion: number
  enabled: boolean
  onAddToAgentContext: (params: MarkdownAddToAgentContextParams) => Promise<MarkdownAddToAgentContextResultParams>
}

interface SelectionHighlightRect {
  top: number
  left: number
  width: number
  height: number
}

interface SelectionPopoverAnchor {
  selectedText: string
  range: Range
  top: number
  left: number
  highlightRects: SelectionHighlightRect[]
}

const POPOVER_WIDTH = 280
const POPOVER_HEIGHT_ESTIMATE = 116
const VIEWPORT_MARGIN = 12

export function MarkdownAgentContextPopover({
  contentVersion,
  enabled,
  onAddToAgentContext,
}: MarkdownAgentContextPopoverProps) {
  const [anchor, setAnchor] = useState<SelectionPopoverAnchor | undefined>()
  const [comment, setComment] = useState("")
  const [submitting, setSubmitting] = useState(false)
  const popoverRef = useRef<HTMLDivElement>(null)
  const anchorRef = useRef<SelectionPopoverAnchor | undefined>()
  const interactingWithPopoverRef = useRef(false)

  const updateAnchorState = useCallback((next: SelectionPopoverAnchor | undefined) => {
    anchorRef.current = next
    setAnchor(next)
  }, [])

  const closePopover = useCallback(() => {
    interactingWithPopoverRef.current = false
    updateAnchorState(undefined)
    setComment("")
    setSubmitting(false)
  }, [updateAnchorState])

  useEffect(() => {
    closePopover()
  }, [contentVersion, enabled, closePopover])

  useEffect(() => {
    if (!enabled) return

    const updateSelectionFromDom = (event?: Event): void => {
      if (interactingWithPopoverRef.current) {
        return
      }
      if (event?.target instanceof Node && popoverRef.current?.contains(event.target)) {
        return
      }
      const activeElement = document.activeElement
      if (activeElement && popoverRef.current?.contains(activeElement)) {
        return
      }

      const nextAnchor = readPreviewSelectionAnchor()
      if (!nextAnchor) {
        if (event?.type === "selectionchange" && anchorRef.current !== undefined) {
          return
        }
        closePopover()
        return
      }

      if (anchorRef.current?.selectedText !== nextAnchor.selectedText) {
        setComment("")
      }
      updateAnchorState(nextAnchor)
    }

    const releasePopoverInteraction = (): void => {
      window.requestAnimationFrame(() => {
        interactingWithPopoverRef.current = false
      })
    }

    document.addEventListener("selectionchange", updateSelectionFromDom)
    document.addEventListener("mouseup", updateSelectionFromDom)
    document.addEventListener("keyup", updateSelectionFromDom)
    window.addEventListener("pointerup", releasePopoverInteraction, true)
    window.addEventListener("pointercancel", releasePopoverInteraction, true)
    return () => {
      document.removeEventListener("selectionchange", updateSelectionFromDom)
      document.removeEventListener("mouseup", updateSelectionFromDom)
      document.removeEventListener("keyup", updateSelectionFromDom)
      window.removeEventListener("pointerup", releasePopoverInteraction, true)
      window.removeEventListener("pointercancel", releasePopoverInteraction, true)
    }
  }, [enabled, closePopover, updateAnchorState])

  useEffect(() => {
    if (!anchor) return

    let scheduledFrame: number | undefined
    const refreshAnchorGeometry = (): void => {
      const current = anchorRef.current
      if (!current) return
      const refreshed = computeSelectionPopoverAnchor(current.range, current.selectedText)
      if (!refreshed) {
        closePopover()
        return
      }
      updateAnchorState(refreshed)
    }
    const scheduleGeometryRefresh = (): void => {
      if (scheduledFrame !== undefined) return
      scheduledFrame = window.requestAnimationFrame(() => {
        scheduledFrame = undefined
        refreshAnchorGeometry()
      })
    }

    const handlePointerDown = (event: PointerEvent): void => {
      const target = event.target
      if (target instanceof Node && popoverRef.current?.contains(target)) {
        interactingWithPopoverRef.current = true
        return
      }
      const root = document.documentElement
      if (event.clientX >= root.clientWidth || event.clientY >= root.clientHeight) {
        return
      }
      interactingWithPopoverRef.current = false
      const activeElement = document.activeElement
      if (activeElement instanceof HTMLElement && popoverRef.current?.contains(activeElement)) {
        activeElement.blur()
      }
      window.getSelection()?.removeAllRanges()
      closePopover()
    }

    const handleKeyDown = (event: KeyboardEvent): void => {
      if (event.key === "Escape") {
        event.preventDefault()
        window.getSelection()?.removeAllRanges()
        closePopover()
      }
    }

    document.addEventListener("pointerdown", handlePointerDown, true)
    window.addEventListener("keydown", handleKeyDown)
    window.addEventListener("scroll", scheduleGeometryRefresh, { passive: true, capture: true })
    window.addEventListener("resize", scheduleGeometryRefresh)
    return () => {
      if (scheduledFrame !== undefined) {
        window.cancelAnimationFrame(scheduledFrame)
      }
      document.removeEventListener("pointerdown", handlePointerDown, true)
      window.removeEventListener("keydown", handleKeyDown)
      window.removeEventListener("scroll", scheduleGeometryRefresh, true)
      window.removeEventListener("resize", scheduleGeometryRefresh)
    }
  }, [anchor, closePopover, updateAnchorState])

  useEffect(() => {
    return addWebViewFocusLeaveListener(() => closePopover())
  }, [closePopover])

  if (!enabled || !anchor) return null

  async function handleSubmit(): Promise<void> {
    if (!anchor || submitting) return
    setSubmitting(true)
    try {
      const result = await onAddToAgentContext({
        selectedText: anchor.selectedText,
        comment: comment.trim(),
      })
      if (result.added) {
        window.getSelection()?.removeAllRanges()
        closePopover()
      }
    }
    finally {
      setSubmitting(false)
    }
  }

  function handleTextareaKeyDown(event: ReactKeyboardEvent<HTMLTextAreaElement>): void {
    if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
      event.preventDefault()
      void handleSubmit()
    }
    else if (event.key === "Escape") {
      event.preventDefault()
      window.getSelection()?.removeAllRanges()
      closePopover()
    }
  }

  return (
    <>
      <div className="markdownAgentContextSelectionLayer" aria-hidden="true">
        {anchor.highlightRects.map((rect, index) => (
          <div
            key={`${index}:${rect.top}:${rect.left}:${rect.width}:${rect.height}`}
            className="markdownAgentContextSelectionHighlight"
            style={{
              top: `${rect.top}px`,
              left: `${rect.left}px`,
              width: `${rect.width}px`,
              height: `${rect.height}px`,
            }}
          />
        ))}
      </div>
      <div
        ref={popoverRef}
        className="markdownAgentContextPopover"
        role="dialog"
        aria-label="Add quote to agent context"
        style={{ top: `${anchor.top}px`, left: `${anchor.left}px` }}
        onPointerDownCapture={() => {
          interactingWithPopoverRef.current = true
        }}
        onMouseDown={event => {
          if (!(event.target instanceof HTMLTextAreaElement)) {
            event.preventDefault()
          }
        }}
      >
        <textarea
          className="markdownAgentContextCommentInput"
          aria-label="Comment for agent context"
          placeholder="Add a comment (optional)..."
          rows={2}
          value={comment}
          disabled={submitting}
          onChange={event => setComment(event.currentTarget.value)}
          onKeyDown={handleTextareaKeyDown}
        />
        <div className="markdownAgentContextActions">
          <button
            type="button"
            className="markdownAgentContextSubmitButton"
            disabled={submitting}
            onClick={() => void handleSubmit()}
          >
            Add to Agent Context
          </button>
        </div>
      </div>
    </>
  )
}

function readPreviewSelectionAnchor(): SelectionPopoverAnchor | undefined {
  const selection = window.getSelection()
  if (!selection || selection.isCollapsed || selection.rangeCount === 0) return undefined

  const selectedText = selection.toString().trim()
  if (selectedText.length === 0) return undefined

  const range = selection.getRangeAt(0).cloneRange()
  return computeSelectionPopoverAnchor(range, selectedText)
}

function computeSelectionPopoverAnchor(range: Range, selectedText: string): SelectionPopoverAnchor | undefined {
  const previewContent = document.querySelector<HTMLElement>(".markdownPreviewContent")
  if (!previewContent) return undefined
  if (!isNodeInside(range.startContainer, previewContent) || !isNodeInside(range.endContainer, previewContent)) {
    return undefined
  }

  const rect = resolveSelectionRect(range, previewContent)
  const highlightRects = collectSelectionHighlightRects(range, rect)
  const viewportWidth = window.innerWidth || document.documentElement.clientWidth || 800
  const viewportHeight = window.innerHeight || document.documentElement.clientHeight || 600

  const preferredTop = rect.bottom + 8
  const top = preferredTop + POPOVER_HEIGHT_ESTIMATE <= viewportHeight - VIEWPORT_MARGIN
    ? Math.max(VIEWPORT_MARGIN, preferredTop)
    : Math.max(VIEWPORT_MARGIN, rect.top - POPOVER_HEIGHT_ESTIMATE - 8)
  const left = Math.min(
    Math.max(VIEWPORT_MARGIN, rect.left),
    Math.max(VIEWPORT_MARGIN, viewportWidth - POPOVER_WIDTH - VIEWPORT_MARGIN),
  )

  return {
    selectedText,
    range,
    top: Math.round(top),
    left: Math.round(left),
    highlightRects,
  }
}

function collectSelectionHighlightRects(range: Range, fallbackRect: DOMRect): SelectionHighlightRect[] {
  const rawRects: DOMRect[] = []
  const textNodes = collectIntersectingTextNodes(range)
  if (textNodes.length > 0) {
    const scratchRange = document.createRange()
    for (const textNode of textNodes) {
      const textLength = textNode.nodeValue?.length ?? 0
      const startOffset = textNode === range.startContainer ? Math.min(range.startOffset, textLength) : 0
      const endOffset = textNode === range.endContainer ? Math.min(range.endOffset, textLength) : textLength
      if (endOffset <= startOffset) continue
      const slice = textNode.nodeValue?.slice(startOffset, endOffset) ?? ""
      if (!/[^\r\n]/.test(slice)) continue
      scratchRange.setStart(textNode, startOffset)
      scratchRange.setEnd(textNode, endOffset)
      for (const rect of Array.from(scratchRange.getClientRects())) {
        if (rect.width > 0.5 && rect.height > 0.5) {
          rawRects.push(rect)
        }
      }
    }
  }

  if (rawRects.length === 0) {
    for (const rect of Array.from(range.getClientRects())) {
      if (rect.width > 0.5 && rect.height > 0.5) {
        rawRects.push(rect)
      }
    }
  }

  if (rawRects.length === 0 && (fallbackRect.width > 0.5 && fallbackRect.height > 0.5)) {
    rawRects.push(fallbackRect)
  }

  return mergeLineHighlightRects(rawRects)
}

function collectIntersectingTextNodes(range: Range): Text[] {
  const root = range.commonAncestorContainer
  if (root.nodeType === Node.TEXT_NODE) {
    return [root as Text]
  }
  const result: Text[] = []
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  let current = walker.nextNode()
  while (current) {
    if (current.nodeType === Node.TEXT_NODE && range.intersectsNode(current)) {
      result.push(current as Text)
    }
    current = walker.nextNode()
  }
  return result
}

function mergeLineHighlightRects(rects: readonly DOMRect[]): SelectionHighlightRect[] {
  if (rects.length === 0) return []
  const sorted = [...rects].sort((first, second) => {
    if (Math.abs(first.top - second.top) > 3) {
      return first.top - second.top
    }
    return first.left - second.left
  })

  const merged: Array<{ top: number, left: number, right: number, bottom: number }> = []
  for (const rect of sorted) {
    const previous = merged[merged.length - 1]
    if (previous) {
      const prevHeight = previous.bottom - previous.top
      const verticalOverlap = Math.min(previous.bottom, rect.bottom) - Math.max(previous.top, rect.top)
      const minHeight = Math.min(prevHeight, rect.height)
      if (verticalOverlap > minHeight * 0.5 && rect.left <= previous.right + 4) {
        previous.top = Math.min(previous.top, rect.top)
        previous.left = Math.min(previous.left, rect.left)
        previous.right = Math.max(previous.right, rect.right)
        previous.bottom = Math.max(previous.bottom, rect.bottom)
        continue
      }
    }
    merged.push({
      top: rect.top,
      left: rect.left,
      right: rect.right,
      bottom: rect.bottom,
    })
  }

  return merged.map(box => ({
    top: Math.round(box.top),
    left: Math.round(box.left),
    width: Math.max(1, Math.round(box.right - box.left)),
    height: Math.max(1, Math.round(box.bottom - box.top)),
  }))
}

function resolveSelectionRect(range: Range, fallbackElement: HTMLElement): DOMRect {
  const boundingRect = range.getBoundingClientRect()
  if (boundingRect.width > 0 || boundingRect.height > 0) {
    return boundingRect
  }
  const clientRect = range.getClientRects().item(0)
  if (clientRect && (clientRect.width > 0 || clientRect.height > 0)) {
    return clientRect
  }
  const anchorElement = range.startContainer instanceof HTMLElement
    ? range.startContainer
    : range.startContainer.parentElement
  return (anchorElement ?? fallbackElement).getBoundingClientRect()
}

function isNodeInside(node: Node, container: HTMLElement): boolean {
  const element = node instanceof HTMLElement ? node : node.parentElement
  return element !== null && container.contains(element)
}
