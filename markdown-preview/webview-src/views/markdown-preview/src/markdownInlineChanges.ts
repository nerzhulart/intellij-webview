// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import type { MarkdownChangedBlockDescriptor, MarkdownChangedBlockKind, MarkdownInlineChangeDescriptor } from "./markdownPreviewTypes"

interface MarkdownSourcePoint {
  offset?: number
}

interface MarkdownNode {
  type?: string
  value?: string
  position?: {
    start?: MarkdownSourcePoint
    end?: MarkdownSourcePoint
  }
  data?: {
    hName?: string
    hProperties?: Record<string, unknown>
    [key: string]: unknown
  }
  children?: MarkdownNode[]
}

export interface RemarkInlineChangesOptions {
  /** The Markdown source the offsets of `ranges` refer to. */
  markdown: string
  /** Sorted, non-overlapping ranges, see `inlineChangeRanges`. */
  ranges: MarkdownInlineChangeDescriptor[]
}

/**
 * Fenced code is rewritten by syntax highlighting and Mermaid, HTML is parsed later by `rehype-raw`, so their changes
 * are shown by the highlighting of the whole block.
 */
const skippedNodeTypes = new Set(["code", "definition", "html", "math", "inlineMath", "toml", "yaml"])

/**
 * Inline nodes whose rendered text does not show their whole source: a change of a link URL, of an image source, or of
 * an inline code span is shown by highlighting the whole element.
 */
const wholeNodeTypes = new Set(["footnoteReference", "image", "imageReference", "inlineCode"])
const linkNodeTypes = new Set(["link", "linkReference"])
const entityPattern = /^&(?:#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,31});/

export function inlineChangeRanges(changes: readonly MarkdownChangedBlockDescriptor[]): MarkdownInlineChangeDescriptor[] {
  return changes
    .flatMap(change => change.inlineChanges ?? [])
    .filter(range => range.endOffset > range.startOffset)
    .sort((first, second) => first.startOffset - second.startOffset)
}

/**
 * Wraps the changed parts of text nodes into `span[data-vcs-inline]` elements and marks inline elements whose change is
 * not visible as text with the `data-vcs-inline` attribute. The value of the attribute is the change kind.
 */
export function remarkInlineChanges(options: RemarkInlineChangesOptions) {
  return (tree: unknown) => {
    if (options.ranges.length === 0) return
    markChildren(tree as MarkdownNode, options)
  }
}

function markChildren(parent: MarkdownNode, options: RemarkInlineChangesOptions): boolean {
  const children = parent.children
  if (!children) return false

  let marked = false
  const nextChildren: MarkdownNode[] = []
  for (const child of children) {
    if (child.type === "text") {
      const parts = splitTextNode(child, options)
      if (parts) {
        nextChildren.push(...parts)
        marked = true
      }
      else {
        nextChildren.push(child)
      }
      continue
    }
    if (markNode(child, options)) marked = true
    nextChildren.push(child)
  }
  parent.children = nextChildren
  return marked
}

function markNode(node: MarkdownNode, options: RemarkInlineChangesOptions): boolean {
  if (node.type && skippedNodeTypes.has(node.type)) return false

  const start = node.position?.start?.offset
  const end = node.position?.end?.offset
  const ranges = start !== undefined && end !== undefined ? intersectingRanges(options.ranges, start, end) : undefined
  if (ranges?.length === 0) return false

  if (ranges && node.type && wholeNodeTypes.has(node.type)) {
    markWholeNode(node, ranges[0].kind)
    return true
  }
  const marked = markChildren(node, options)
  if (!marked && ranges && node.type && linkNodeTypes.has(node.type)) {
    markWholeNode(node, ranges[0].kind)
    return true
  }
  return marked
}

function markWholeNode(node: MarkdownNode, kind: MarkdownChangedBlockKind): void {
  node.data ??= {}
  node.data.hProperties = {
    ...node.data.hProperties,
    dataVcsInline: kind,
  }
}

function splitTextNode(node: MarkdownNode, options: RemarkInlineChangesOptions): MarkdownNode[] | undefined {
  const value = node.value ?? ""
  const start = node.position?.start?.offset
  const end = node.position?.end?.offset
  if (value.length === 0 || start === undefined || end === undefined) return undefined

  const ranges = intersectingRanges(options.ranges, start, end)
  if (ranges.length === 0) return undefined

  const sourceOffsets = alignTextWithSource(value, options.markdown, start, end)
  if (!sourceOffsets) return [inlineChangeNode(ranges[0].kind, value)]

  const parts: MarkdownNode[] = []
  let segmentStart = 0
  let segmentKind = changeKindAt(ranges, sourceOffsets[0])
  let marked = false
  for (let index = 1; index <= value.length; index++) {
    const kind = index < value.length ? changeKindAt(ranges, sourceOffsets[index]) : undefined
    if (index < value.length && kind === segmentKind) continue

    const segment = value.slice(segmentStart, index)
    if (segmentKind) {
      parts.push(inlineChangeNode(segmentKind, segment))
      marked = true
    }
    else {
      parts.push({ type: "text", value: segment })
    }
    segmentStart = index
    segmentKind = kind
  }
  return marked ? parts : undefined
}

function inlineChangeNode(kind: MarkdownChangedBlockKind, value: string): MarkdownNode {
  return {
    type: "inlineChange",
    data: {
      hName: "span",
      hProperties: { dataVcsInline: kind },
    },
    children: [{ type: "text", value }],
  }
}

/**
 * Maps every character of the parsed text to its source offset. The parsed text drops backslash escapes, the indentation
 * of continuation lines and trailing spaces, and decodes character references, so the source characters that have no
 * match in the text are skipped. Returns `undefined` when the text cannot be matched with its source.
 */
function alignTextWithSource(value: string, markdown: string, start: number, end: number): number[] | undefined {
  const offsets = new Array<number>(value.length)
  let sourceIndex = start
  for (let index = 0; index < value.length; index++) {
    const character = value[index]
    while (sourceIndex < end && markdown[sourceIndex] !== character) {
      const entity = markdown[sourceIndex] === "&" ? markdown.slice(sourceIndex, end).match(entityPattern) : null
      if (entity) break
      sourceIndex++
    }
    if (sourceIndex >= end) return undefined

    if (markdown[sourceIndex] === character) {
      offsets[index] = sourceIndex++
      continue
    }

    const entityLength = markdown.slice(sourceIndex, end).match(entityPattern)?.[0].length ?? 1
    offsets[index] = sourceIndex
    if (isHighSurrogate(character) && index + 1 < value.length) {
      offsets[++index] = sourceIndex
    }
    sourceIndex += entityLength
  }
  return offsets
}

function isHighSurrogate(character: string): boolean {
  const code = character.charCodeAt(0)
  return code >= 0xD800 && code <= 0xDBFF
}

function changeKindAt(ranges: readonly MarkdownInlineChangeDescriptor[], offset: number): MarkdownChangedBlockKind | undefined {
  return ranges.find(range => range.startOffset <= offset && offset < range.endOffset)?.kind
}

function intersectingRanges(ranges: readonly MarkdownInlineChangeDescriptor[], start: number, end: number): MarkdownInlineChangeDescriptor[] {
  const result: MarkdownInlineChangeDescriptor[] = []
  for (let index = firstRangeEndingAfter(ranges, start); index < ranges.length && ranges[index].startOffset < end; index++) {
    result.push(ranges[index])
  }
  return result
}

/**
 * The ranges are sorted and do not overlap, so their end offsets are sorted as well.
 */
function firstRangeEndingAfter(ranges: readonly MarkdownInlineChangeDescriptor[], offset: number): number {
  let low = 0
  let high = ranges.length
  while (low < high) {
    const middle = (low + high) >>> 1
    if (ranges[middle].endOffset <= offset) {
      low = middle + 1
    }
    else {
      high = middle
    }
  }
  return low
}
