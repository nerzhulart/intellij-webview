// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { markdownTable } from "markdown-table"
import { fromMarkdown } from "mdast-util-from-markdown"
import { gfmFromMarkdown, gfmToMarkdown } from "mdast-util-gfm"
import { defaultHandlers, toMarkdown, type Handle } from "mdast-util-to-markdown"
import { gfm } from "micromark-extension-gfm"
import type { SourcePositionRange } from "./markdownSourcePositions"

export type TableColumnAlignment = "left" | "center" | "right" | null

export interface EditableGfmTable {
  headers: string[]
  alignments: TableColumnAlignment[]
  rows: string[][]
}

export interface MarkdownRangePatch {
  range: SourcePositionRange
  newMarkdown: string
}

interface MdastPoint {
  line: number
  column: number
  offset?: number
}

interface MdastPosition {
  start: MdastPoint
  end: MdastPoint
}

interface MdastNode {
  type: string
  value?: string
  checked?: boolean | null
  align?: TableColumnAlignment[] | null
  position?: MdastPosition
  children?: MdastNode[]
}

interface MdastRoot extends MdastNode {
  type: "root"
  children: MdastNode[]
}

/**
 * Resolves a 1-based `unist` `SourcePositionRange` (`startLine:startColumn-endLine:endColumn`, where `endColumn` is
 * 1-based exclusive) to 0-based UTF-16 `[startOffset, endOffset]` in `markdown`.
 */
export function resolveSourceRangeOffsets(
  markdown: string,
  range: SourcePositionRange,
): { startOffset: number, endOffset: number } | undefined {
  if (
    range.startLine < 1 ||
    range.startColumn < 1 ||
    range.endLine < range.startLine ||
    range.endColumn < 1 ||
    (range.endLine === range.startLine && range.endColumn < range.startColumn)
  ) {
    return undefined
  }

  const lineBounds = computeLineBounds(markdown)
  const startOffset = resolvePointOffset(lineBounds, markdown.length, range.startLine, range.startColumn)
  const endOffset = resolvePointOffset(lineBounds, markdown.length, range.endLine, range.endColumn)
  if (startOffset === undefined || endOffset === undefined || endOffset < startOffset) {
    return undefined
  }
  return { startOffset, endOffset }
}

export function extractMarkdownSlice(markdown: string, range: SourcePositionRange): string {
  const offsets = resolveSourceRangeOffsets(markdown, range)
  if (!offsets) return ""
  return markdown.slice(offsets.startOffset, offsets.endOffset)
}

export function applyMarkdownRangePatch(
  markdown: string,
  range: SourcePositionRange,
  newMarkdown: string,
): string | undefined {
  const offsets = resolveSourceRangeOffsets(markdown, range)
  if (!offsets) return undefined
  return markdown.slice(0, offsets.startOffset) + newMarkdown + markdown.slice(offsets.endOffset)
}

export function parseMarkdownAstSlice(markdownSlice: string): MdastRoot {
  return fromMarkdown(markdownSlice, {
    extensions: [gfm()],
    mdastExtensions: [gfmFromMarkdown()],
  }) as unknown as MdastRoot
}

/**
 * Serializes an `mdast` tree back to Markdown while preserving the exact source slices of all nodes that are not in
 * `dirtyNodes` and do not contain any dirty descendant.
 */
export function serializeMarkdownAstPreservingUnchanged(
  tree: MdastRoot,
  originalSlice: string,
  dirtyNodes: ReadonlySet<MdastNode>,
): string {
  const nodesWithDirtyDescendants = collectAncestorsOfDirtyNodes(tree, dirtyNodes)
  const customHandlers: Record<string, Handle> = {}

  for (const [nodeType, defaultHandler] of Object.entries(defaultHandlers as Record<string, Handle>)) {
    customHandlers[nodeType] = (node: unknown, parent: unknown, state: unknown, info: unknown) => {
      const mdastNode = node as MdastNode
      if (!dirtyNodes.has(mdastNode) && !nodesWithDirtyDescendants.has(mdastNode)) {
        const startOffset = mdastNode.position?.start?.offset
        const endOffset = mdastNode.position?.end?.offset
        if (typeof startOffset === "number" && typeof endOffset === "number" && endOffset >= startOffset) {
          return originalSlice.slice(startOffset, endOffset)
        }
      }
      return defaultHandler(node as never, parent as never, state as never, info as never)
    }
  }

  return toMarkdown(tree as never, {
    extensions: [gfmToMarkdown({ tablePipeAlign: true })],
    handlers: customHandlers,
  }).replace(/\n$/, "")
}

/**
 * Surgical AST patch for toggling a GFM task list item (`- [ ]` <-> `- [x]`) at `itemRange`.
 * Uses `mdast-util-from-markdown` + `mdast-util-gfm` to locate the exact task list item prefix before its first child
 * node so nested task lists and inline code containing `[ ]` are never touched.
 */
export function patchTaskListItemCheckbox(
  markdown: string,
  itemRange: SourcePositionRange,
  checked: boolean,
): MarkdownRangePatch | undefined {
  const slice = extractMarkdownSlice(markdown, itemRange)
  if (!slice) return undefined

  const ast = parseMarkdownAstSlice(slice)
  const taskItem = findFirstTaskListItem(ast)
  const replacementMarker = checked ? "[x]" : "[ ]"

  if (taskItem) {
    const itemStartOffset = taskItem.position?.start?.offset ?? 0
    const firstChildStartOffset = taskItem.children?.[0]?.position?.start?.offset ?? slice.length
    const prefixRegionEnd = Math.min(slice.length, Math.max(itemStartOffset, firstChildStartOffset))
    const prefixRegion = slice.slice(itemStartOffset, prefixRegionEnd)
    const markerMatch = prefixRegion.match(/\[([ xX])\]/)
    if (markerMatch && markerMatch.index !== undefined) {
      const markerOffsetInSlice = itemStartOffset + markerMatch.index
      const patchedSlice =
        slice.slice(0, markerOffsetInSlice) +
        replacementMarker +
        slice.slice(markerOffsetInSlice + 3)
      return {
        range: itemRange,
        newMarkdown: patchedSlice,
      }
    }
  }

  // Fallback for list items whose slice starts directly on the task line.
  const firstLineEnd = slice.indexOf("\n")
  const firstLine = firstLineEnd >= 0 ? slice.slice(0, firstLineEnd) : slice
  const fallbackMatch = firstLine.match(/^(\s*(?:[-*+]|\d+[.)])\s+)\[[ xX]\]/)
  if (!fallbackMatch) return undefined

  const markerIndex = fallbackMatch[1].length
  const patchedSlice =
    slice.slice(0, markerIndex) +
    replacementMarker +
    slice.slice(markerIndex + 3)
  return {
    range: itemRange,
    newMarkdown: patchedSlice,
  }
}

/**
 * Parses a GFM table slice into a structured 2D grid (`headers`, `alignments`, `rows`) using `mdast-util-from-markdown`
 * and `mdast-util-gfm`, preserving inline Markdown inside each cell via cell source offsets.
 */
export function parseGfmTableSlice(tableSlice: string): EditableGfmTable | undefined {
  const ast = parseMarkdownAstSlice(tableSlice)
  const tableNode = ast.children.find(node => node.type === "table")
  if (!tableNode || !tableNode.children || tableNode.children.length === 0) {
    return undefined
  }

  const rawRows = tableNode.children.map(rowNode => {
    const cells = rowNode.children ?? []
    return cells.map(cellNode => extractTableCellMarkdown(cellNode, tableSlice))
  })

  const columnCount = Math.max(
    1,
    tableNode.align?.length ?? 0,
    ...rawRows.map(row => row.length),
  )

  const normalizeRow = (row: string[]): string[] => {
    const normalized = row.slice(0, columnCount)
    while (normalized.length < columnCount) {
      normalized.push("")
    }
    return normalized
  }

  const headers = normalizeRow(rawRows[0] ?? [""])
  const rows = rawRows.slice(1).map(normalizeRow)
  const alignments: TableColumnAlignment[] = Array.from({ length: columnCount }, (_, index) => {
    const align = tableNode.align?.[index]
    return align === "left" || align === "center" || align === "right" ? align : null
  })

  return {
    headers,
    alignments,
    rows,
  }
}

/**
 * Serializes an `EditableGfmTable` back to cleanly aligned GFM Markdown table syntax using `markdown-table` /
 * `mdast-util-gfm`.
 */
export function serializeGfmTable(table: EditableGfmTable): string {
  const columnCount = Math.max(1, table.headers.length, ...table.rows.map(row => row.length))
  const sanitizeCell = (cell: string): string => {
    return cell
      .replace(/\r?\n/g, " ")
      .replace(/(?<!\\)\|/g, "\\|")
      .trim()
  }

  const normalizeRow = (row: readonly string[]): string[] => {
    return Array.from({ length: columnCount }, (_, index) => sanitizeCell(row[index] ?? ""))
  }

  const align = Array.from({ length: columnCount }, (_, index) => table.alignments[index] ?? null)
  const matrix = [
    normalizeRow(table.headers),
    ...table.rows.map(normalizeRow),
  ]

  return markdownTable(matrix, {
    align,
    alignDelimiters: true,
  })
}

function extractTableCellMarkdown(cellNode: MdastNode, tableSlice: string): string {
  const children = cellNode.children ?? []
  if (children.length > 0) {
    const firstStart = children[0].position?.start?.offset
    const lastEnd = children[children.length - 1].position?.end?.offset
    if (typeof firstStart === "number" && typeof lastEnd === "number" && lastEnd >= firstStart) {
      return tableSlice
        .slice(firstStart, lastEnd)
        .replace(/\\\|/g, "|")
        .trim()
    }
  }

  return toMarkdown({ type: "paragraph", children: children as never } as never, {
    extensions: [gfmToMarkdown()],
  }).trim()
}

function findFirstTaskListItem(node: MdastNode): MdastNode | undefined {
  if (node.type === "listItem" && typeof node.checked === "boolean") {
    return node
  }
  for (const child of node.children ?? []) {
    const found = findFirstTaskListItem(child)
    if (found) return found
  }
  return undefined
}

function collectAncestorsOfDirtyNodes(root: MdastNode, dirtyNodes: ReadonlySet<MdastNode>): Set<MdastNode> {
  const ancestors = new Set<MdastNode>()

  function visit(node: MdastNode): boolean {
    let hasDirtyDescendant = false
    for (const child of node.children ?? []) {
      if (visit(child)) {
        hasDirtyDescendant = true
      }
    }
    if (hasDirtyDescendant) {
      ancestors.add(node)
    }
    return hasDirtyDescendant || dirtyNodes.has(node)
  }

  visit(root)
  return ancestors
}

interface LineBound {
  startOffset: number
  endOffset: number
}

function computeLineBounds(text: string): LineBound[] {
  const bounds: LineBound[] = []
  let lineStart = 0
  for (let index = 0; index < text.length; index++) {
    if (text[index] === "\n") {
      const lineEnd = index > lineStart && text[index - 1] === "\r" ? index - 1 : index
      bounds.push({ startOffset: lineStart, endOffset: lineEnd })
      lineStart = index + 1
    }
  }
  bounds.push({ startOffset: lineStart, endOffset: text.length })
  return bounds
}

function resolvePointOffset(
  lineBounds: readonly LineBound[],
  totalLength: number,
  line: number,
  column: number,
): number | undefined {
  if (line === lineBounds.length + 1 && column === 1) {
    return totalLength
  }
  if (line < 1 || line > lineBounds.length) {
    return undefined
  }
  const bound = lineBounds[line - 1]
  const offset = bound.startOffset + (column - 1)
  return Math.min(Math.max(bound.startOffset, offset), bound.endOffset)
}
