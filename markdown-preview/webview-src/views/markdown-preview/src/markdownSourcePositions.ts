// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { codeNodeFromPreNode, type HastNode } from "./markdownHastUtils"

export interface SourcePositionRange {
  startLine: number
  startColumn: number
  endLine: number
  endColumn: number
}

interface SourcePositionElement extends SourcePositionRange {
  element: HTMLElement
}

interface MarkdownChangedBlockDescriptor {
  kind: "ADDED" | "MODIFIED" | "REMOVED"
  startLine: number
  endLine: number
  inlineChanges?: readonly unknown[]
}

export interface ChangedBlockElements {
  kind: MarkdownChangedBlockDescriptor["kind"]
  startLine: number
  elements: HTMLElement[]
}

const sourcePositionPattern = /^(\d+):(\d+)-(\d+):(\d+)$/
const inlineChangesClassName = "has-vcs-inline-changes"
const inlineChangeSelector = "[data-vcs-inline]"
const sourceDecorationClassNames = ["is-source-selected", "is-vcs-added", "is-vcs-modified", "is-vcs-removed", inlineChangesClassName]
const sourceDecorationClassSelector = sourceDecorationClassNames.map(className => `.${className}`).join(", ")
const sourceDecorationBlockTagNames = new Set([
  "BLOCKQUOTE",
  "DD",
  "DETAILS",
  "DIV",
  "DL",
  "DT",
  "H1",
  "H2",
  "H3",
  "H4",
  "H5",
  "H6",
  "LI",
  "OL",
  "P",
  "PRE",
  "SECTION",
  "TABLE",
  "TBODY",
  "TD",
  "TFOOT",
  "TH",
  "THEAD",
  "TR",
  "UL",
])
const programmaticScrollQuietPeriodMs = 300
let scheduledScrollFrame: number | undefined
let programmaticScrollUntilMs = 0

export function scrollMarkdownPreviewToLine(line: number): void {
  cancelScheduledMarkdownPreviewScroll()
  markProgrammaticMarkdownPreviewScroll()
  scheduledScrollFrame = window.requestAnimationFrame(() => {
    scheduledScrollFrame = undefined
    scrollToSourceLine(line)
    markProgrammaticMarkdownPreviewScroll()
  })
}

export function markProgrammaticMarkdownPreviewScroll(): void {
  programmaticScrollUntilMs = performance.now() + programmaticScrollQuietPeriodMs
}

export function isProgrammaticMarkdownPreviewScroll(): boolean {
  return performance.now() < programmaticScrollUntilMs
}

/**
 * The 0-based source line of the innermost block that is still visible at the top of the viewport. Containers such as
 * tables, lists, and block quotes stay visible while any of their children is visible, so the topmost visible block is
 * refined to its first visible nested block; otherwise scrolling through a table would report the table start until the
 * whole table is scrolled past. Inline elements are not used for refinement, so a partially visible multi-line paragraph
 * keeps reporting its own start line.
 */
export function topVisibleSourceLine(): number | undefined {
  const contentElement = markdownContentElement()
  if (!contentElement) return undefined

  let visibleElement: SourcePositionElement | undefined
  for (const element of sourcePositionElements(contentElement)) {
    if (visibleElement) {
      if (!visibleElement.element.contains(element.element)) break
      if (!sourceDecorationBlockTagNames.has(element.element.tagName)) continue
    }
    if (element.element.getBoundingClientRect().bottom > 0) {
      visibleElement = element
    }
  }
  return visibleElement ? Math.max(0, visibleElement.startLine - 1) : undefined
}

export function cancelScheduledMarkdownPreviewScroll(): void {
  if (scheduledScrollFrame === undefined) return
  window.cancelAnimationFrame(scheduledScrollFrame)
  scheduledScrollFrame = undefined
}

export function decorateSourceBlocks(selection: SourcePositionRange | undefined, changes: MarkdownChangedBlockDescriptor[]): ChangedBlockElements[] {
  const contentElement = markdownContentElement()
  if (!contentElement) return []

  clearSourceDecorations(contentElement)
  const elements = sourceDecorationElements(contentElement)

  if (selection) {
    for (const element of elements) {
      if (sourceRangesIntersect(element, selection)) {
        element.element.classList.add("is-source-selected")
      }
    }
  }

  const changedBlocks: ChangedBlockElements[] = []
  for (const change of changes) {
    const className = changeClassName(change.kind)
    const changedElements = changedBlockElements(elements, change)
    const hasInlineChanges = (change.inlineChanges?.length ?? 0) > 0 && changedElements.some(containsInlineChange)
    for (const element of changedElements) {
      element.classList.add(className)
      if (hasInlineChanges) element.classList.add(inlineChangesClassName)
    }
    if (changedElements.length > 0) {
      changedBlocks.push({ kind: change.kind, startLine: change.startLine, elements: changedElements })
    }
  }
  return changedBlocks
}

export function clearSourceDecorations(root: HTMLElement | null = markdownContentElement()): void {
  if (!root) return
  root.querySelectorAll<HTMLElement>(sourceDecorationClassSelector).forEach(element => {
    element.classList.remove(...sourceDecorationClassNames)
  })
}

export function sourcePositionFromPreNode(node: unknown): SourcePositionRange | undefined {
  const prePosition = sourcePositionFromHastNode(node)
  if (prePosition) return prePosition

  return sourcePositionFromHastNode(codeNodeFromPreNode(node))
}

export function sourcePositionFromHastNode(node: unknown): SourcePositionRange | undefined {
  const value = (node as HastNode | undefined)?.properties?.dataSourcepos
  return typeof value === "string" ? parseSourcePosition(value) : undefined
}

export function positionKey(sourcePosition: SourcePositionRange): string {
  return `${sourcePosition.startLine}:${sourcePosition.startColumn}-${sourcePosition.endLine}:${sourcePosition.endColumn}`
}

function scrollToSourceLine(line: number): void {
  const contentElement = markdownContentElement()
  if (!contentElement) return

  const targetLine = Math.max(1, line + 1)
  const target = findElementForLine(sourcePositionElements(contentElement), targetLine)
  if (target) {
    target.scrollIntoView({ block: "start", behavior: "instant" })
  }
  else if (targetLine === 1) {
    window.scrollTo({ top: 0, behavior: "instant" })
  }
}

function markdownContentElement(): HTMLElement | null {
  return document.querySelector<HTMLElement>(".markdownPreviewContent") ?? document.getElementById("content")
}

function sourcePositionElements(root: HTMLElement): SourcePositionElement[] {
  return Array.from(root.querySelectorAll<HTMLElement>("[data-sourcepos]"))
    .map(element => {
      const sourcePosition = parseSourcePosition(element.dataset.sourcepos)
      return sourcePosition ? { element, ...sourcePosition } : undefined
    })
    .filter((element): element is SourcePositionElement => element !== undefined)
}

function sourceDecorationElements(root: HTMLElement): SourcePositionElement[] {
  const elements: SourcePositionElement[] = []
  const seenTargets = new Set<HTMLElement>()
  for (const sourcePosition of sourcePositionElements(root)) {
    const target = sourceDecorationTarget(sourcePosition.element)
    if (!target || seenTargets.has(target)) continue

    seenTargets.add(target)
    elements.push({ ...sourcePosition, element: target })
  }
  return elements
}

function sourceDecorationTarget(element: HTMLElement): HTMLElement | undefined {
  if (sourceDecorationBlockTagNames.has(element.tagName)) return element
  if (element.tagName === "CODE" && element.parentElement?.tagName === "PRE") return element.parentElement
  return undefined
}

/**
 * A block whose inline changes are not rendered, for example in a fenced code block or in a standalone image, keeps the
 * full highlighting, so the change stays visible.
 */
function containsInlineChange(element: HTMLElement): boolean {
  return element.matches(inlineChangeSelector) || element.querySelector(inlineChangeSelector) !== null
}

function sourceRangesIntersect(first: SourcePositionRange, second: SourcePositionRange): boolean {
  if (first.endLine < second.startLine || second.endLine < first.startLine) return false
  if (first.endLine === second.startLine && first.endColumn < second.startColumn) return false
  if (second.endLine === first.startLine && second.endColumn < first.startColumn) return false
  return true
}

function sourceLinesIntersect(sourcePosition: SourcePositionRange, change: MarkdownChangedBlockDescriptor): boolean {
  return sourcePosition.startLine <= change.endLine && change.startLine <= sourcePosition.endLine
}

function sourceLinesContained(sourcePosition: SourcePositionRange, change: MarkdownChangedBlockDescriptor): boolean {
  return change.startLine <= sourcePosition.startLine && sourcePosition.endLine <= change.endLine
}

/**
 * Picks the elements that represent a changed line range without highlighting enclosing containers: an element that is
 * only partially covered by the change (for example, a table with a couple of inserted rows) is highlighted only when
 * none of its descendants can represent the change more precisely, and an element fully covered by the change is
 * highlighted once at its outermost fully covered level.
 */
function changedBlockElements(elements: SourcePositionElement[], change: MarkdownChangedBlockDescriptor): HTMLElement[] {
  const intersecting = elements.filter(element => sourceLinesIntersect(element, change))
  const contained = intersecting.filter(element => sourceLinesContained(element, change))
  return intersecting
    .filter(element => {
      if (sourceLinesContained(element, change)) {
        return !contained.some(other => other !== element && other.element.contains(element.element))
      }
      return !intersecting.some(other => other !== element && element.element.contains(other.element))
    })
    .map(element => element.element)
}

/**
 * A removed block is highlighted in the preview that still contains the text, so a two-side diff needs no placeholder
 * in the opposite preview.
 */
function changeClassName(kind: MarkdownChangedBlockDescriptor["kind"]): string {
  switch (kind) {
    case "ADDED":
      return "is-vcs-added"
    case "REMOVED":
      return "is-vcs-removed"
    default:
      return "is-vcs-modified"
  }
}

function parseSourcePosition(sourcePosition: string | undefined): SourcePositionRange | undefined {
  const match = sourcePosition?.match(sourcePositionPattern)
  if (!match) return undefined

  return {
    startLine: Number(match[1]),
    startColumn: Number(match[2]),
    endLine: Number(match[3]),
    endColumn: Number(match[4]),
  }
}

function findElementForLine(elements: SourcePositionElement[], targetLine: number): HTMLElement | undefined {
  let containingElement: SourcePositionElement | undefined
  let nextElement: SourcePositionElement | undefined
  let previousElement: SourcePositionElement | undefined

  for (const element of elements) {
    if (element.startLine <= targetLine && targetLine <= element.endLine) {
      if (!containingElement || lineSpan(element) < lineSpan(containingElement)) {
        containingElement = element
      }
      continue
    }
    if (element.startLine > targetLine) {
      nextElement = element
      break
    }
    previousElement = element
  }

  return containingElement?.element ?? nextElement?.element ?? previousElement?.element
}

function lineSpan(element: SourcePositionElement): number {
  return element.endLine - element.startLine
}
