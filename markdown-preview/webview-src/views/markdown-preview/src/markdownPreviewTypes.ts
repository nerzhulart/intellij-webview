// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

export interface MarkdownCommandDescriptor {
  id: string
  kind: MarkdownCommandKind
  startLine: number
  startColumn: number
  endLine: number
  endColumn: number
  title: string
  firstLineCommandId?: string
}

export type MarkdownCommandKind = "BLOCK" | "LINE" | "INLINE"

export interface MarkdownCommandCandidate {
  id: string
  kind: MarkdownCommandKind
  startLine: number
  startColumn: number
  endLine: number
  endColumn: number
  rawCommand: string
  language?: string
  firstLineCommandId?: string
}

export interface MarkdownResolveRunCommandsRequest {
  contentVersion: number
  candidates: MarkdownCommandCandidate[]
}

export interface MarkdownResolvedRunCommandsResponse {
  commands: MarkdownCommandDescriptor[]
}

export type MarkdownChangedBlockKind = "ADDED" | "MODIFIED" | "REMOVED"

/**
 * `inlineChanges` lists the changed text inside the block when the IDE diff highlights differences by word or by symbol.
 */
export interface MarkdownChangedBlockDescriptor {
  kind: MarkdownChangedBlockKind
  startLine: number
  endLine: number
  inlineChanges?: MarkdownInlineChangeDescriptor[]
}

/**
 * A changed text range as 0-based, end-exclusive UTF-16 offsets in the whole Markdown document.
 */
export interface MarkdownInlineChangeDescriptor {
  kind: MarkdownChangedBlockKind
  startOffset: number
  endOffset: number
}

/**
 * `Diff & Merge` colors of the IDE color scheme as `#RRGGBB` or `#RRGGBBAA`: `background` fills a changed block,
 * `stripe` paints its left border and its scrollbar marker. `ignoredBackground` is the pale color that fills a block
 * with inline changes, while `background` fills its changed words.
 */
export interface MarkdownChangeColor {
  background: string
  stripe: string
  ignoredBackground?: string
}

export interface MarkdownChangeColors {
  added: MarkdownChangeColor
  modified: MarkdownChangeColor
  removed: MarkdownChangeColor
}

/**
 * `Link.*` colors of the IDE UI theme as `#RRGGBB` or `#RRGGBBAA`: `foreground` paints a link, `hover` a link under
 * the mouse, `pressed` a link being clicked.
 */
export interface MarkdownLinkColors {
  foreground: string
  hover: string
  pressed: string
}

export interface MarkdownPreviewSettings {
  fontSize?: number | null
  effectiveFontSize: number
  defaultFontSize: number
  fontSizeOptions: number[]
}

export interface MarkdownRunCommandRequest {
  contentVersion: number
  id: string
  clientX: number
  clientY: number
}

export interface MarkdownPathLinkCandidate {
  id: string
  rawPath: string
}

export interface MarkdownResolvePathLinksRequest {
  contentVersion: number
  candidates: MarkdownPathLinkCandidate[]
}

export interface MarkdownResolvedPathLinksResponse {
  resolvedIds: string[]
}

export interface MarkdownNavigatePathLinkRequest {
  contentVersion: number
  rawPath: string
  clientX: number
  clientY: number
}

export interface MarkdownSetFontSizeRequest {
  fontSize: number
}

export interface MarkdownSourceRange {
  startLine: number
  startColumn: number
  endLine: number
  endColumn: number
}
