// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { apiId, type WebViewCallable, type WebViewImplementable } from "@nerzhulart/intellij-webview-sdk"
import { defineWebViewMock } from "@nerzhulart/intellij-webview-sdk-testkit"
import type {
  MarkdownAddToAgentContextParams,
  MarkdownAddToAgentContextResultParams,
  MarkdownChangeColors,
  MarkdownChangedBlockDescriptor,
  MarkdownCommandDescriptor,
  MarkdownInlineChangeDescriptor,
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

interface MarkdownSelectionChangedParams {
  selection?: MarkdownSourceRange | null
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

interface MarkdownOpenLinkParams {
  href: string
}

interface MarkdownPreviewScrolledParams {
  line: number
}

const markdownPreviewPageApiId = apiId<MarkdownPreviewPageApi>()("markdown.preview")
const markdownPreviewHostApiId = apiId<MarkdownPreviewHostApi>()("markdown.preview")
const changes: MarkdownChangedBlockDescriptor[] = [
  { kind: "ADDED", startLine: 9, endLine: 13 },
  { kind: "MODIFIED", startLine: 20, endLine: 23 },
]
const diffChanges: MarkdownChangedBlockDescriptor[] = [
  { kind: "ADDED", startLine: 5, endLine: 5 },
  { kind: "MODIFIED", startLine: 7, endLine: 7 },
  { kind: "REMOVED", startLine: 9, endLine: 9 },
  { kind: "ADDED", startLine: 14, endLine: 14 },
]
const resolvedPathLinks = new Set(["docs/guide.md:12", "src/Main.kt", "src\\WindowsPath.kt", "index.h", "index.html", "style.css", "requirements.txt", "my_django_project/", "my_django_app/"])
const longDiff = longDiffMarkdown()
const defaultFontSize = 13
// Distinct palettes in the style of the `Diff & Merge` colors of an IDE color scheme, so tests can tell them apart.
const ideChangeColorPalettes: MarkdownChangeColors[] = [
  {
    added: { background: "#294436", stripe: "#2E8B57", ignoredBackground: "#1F2E27" },
    modified: { background: "#385570", stripe: "#4A7FB5", ignoredBackground: "#2A3542" },
    removed: { background: "#484A4A", stripe: "#8A8F94", ignoredBackground: "#353636" },
  },
  {
    added: { background: "#3D2B5C", stripe: "#B05CE6", ignoredBackground: "#2C2340" },
    modified: { background: "#5C4A1E", stripe: "#E0A82E", ignoredBackground: "#3F3519" },
    removed: { background: "#5C1E2B", stripe: "#E6455F", ignoredBackground: "#3F1A22" },
  },
]
// Distinct palettes in the style of the `Link.*` colors of an IDE UI theme.
const ideLinkColorPalettes: MarkdownLinkColors[] = [
  { foreground: "#2E8B57", hover: "#1F6E43", pressed: "#145230" },
  { foreground: "#B05CE6", hover: "#8E3DC4", pressed: "#6A2696" },
]
const fontSizeOptions = [8, 9, 10, 11, 12, defaultFontSize, 14, 16, 18, 20, 22, 24, 26, 28, 36, 48, 72]

export default defineWebViewMock(({ host, page, theme }) => {
  const toolbar = installMockToolbar()
  let contentVersion = 1
  let currentMarkdown = sampleMarkdown
  let currentChanges = changes
  let currentFontSize = defaultFontSize
  let ideChangeColorPaletteIndex = 0
  let ideLinkColorPaletteIndex = 0

  function updatePreview(): void {
    page.callable(markdownPreviewPageApiId).contentChanged({
      markdown: currentMarkdown,
      scrollLine: 0,
      settings: currentSettings(),
      contentVersion: contentVersion++,
      changes: currentChanges,
    })
    page.callable(markdownPreviewPageApiId).selectionChanged({
      selection: { startLine: 16, startColumn: 1, endLine: 16, endColumn: 17 },
    })
  }

  toolbar.toggleThemeButton.addEventListener("click", () => {
    const nextTheme = document.documentElement.dataset.theme === "dark" ? "light" : "dark"
    theme.set(nextTheme)
    updatePreview()
  })

  toolbar.shortMarkdownButton.addEventListener("click", () => {
    currentMarkdown = shortMarkdown
    updatePreview()
  })

  toolbar.dollarMarkdownButton.addEventListener("click", () => {
    currentMarkdown = ordinaryDollarMarkdown
    updatePreview()
  })

  toolbar.diffMarkdownButton.addEventListener("click", () => {
    currentMarkdown = diffMarkdown
    currentChanges = diffChanges
    updatePreview()
  })

  toolbar.longDiffMarkdownButton.addEventListener("click", () => {
    currentMarkdown = longDiff.markdown
    currentChanges = longDiff.changes
    updatePreview()
  })

  toolbar.inlineDiffMarkdownButton.addEventListener("click", () => {
    const inlineDiff = inlineDiffMarkdown()
    currentMarkdown = inlineDiff.markdown
    currentChanges = inlineDiff.changes
    updatePreview()
  })

  toolbar.mermaid12MarkdownButton.addEventListener("click", () => {
    currentMarkdown = mermaid12Markdown
    currentChanges = []
    updatePreview()
  })

  toolbar.ideDiffColorsButton.addEventListener("click", () => {
    const palette = ideChangeColorPalettes[ideChangeColorPaletteIndex]
    ideChangeColorPaletteIndex = (ideChangeColorPaletteIndex + 1) % ideChangeColorPalettes.length
    page.callable(markdownPreviewPageApiId).setChangeColors(palette)
  })

  toolbar.ideLinkColorsButton.addEventListener("click", () => {
    const palette = ideLinkColorPalettes[ideLinkColorPaletteIndex]
    ideLinkColorPaletteIndex = (ideLinkColorPaletteIndex + 1) % ideLinkColorPalettes.length
    page.callable(markdownPreviewPageApiId).setLinkColors(palette)
  })

  theme.set("light")
  host.implement(markdownPreviewHostApiId, {
    async pageReady() {
      updatePreview()
    },
    async openLink(params) {
      toolbar.log.textContent = `open ${params.href}`
    },
    async resolveRunCommands(request) {
      const commands: MarkdownCommandDescriptor[] = request.candidates
        .filter(candidate => candidate.rawCommand.trim().length > 0)
        .map(candidate => ({
          id: candidate.id,
          kind: candidate.kind,
          startLine: candidate.startLine,
          startColumn: candidate.startColumn,
          endLine: candidate.endLine,
          endColumn: candidate.endColumn,
          title: candidate.kind === "BLOCK" ? `Run ${candidate.language ?? "block"}` : `Run ${candidate.rawCommand.trim()}`,
          firstLineCommandId: candidate.firstLineCommandId,
        }))
      return { commands }
    },
    async runCommand(params) {
      toolbar.log.textContent = `run ${params.id}`
    },
    async resolvePathLinks(request) {
      return {
        resolvedIds: request.candidates
          .filter(candidate => resolvedPathLinks.has(candidate.rawPath))
          .map(candidate => candidate.id),
      }
    },
    async navigatePathLink(params) {
      toolbar.log.textContent = JSON.stringify({ kind: "navigatePathLink", ...params })
    },
    async setFontSize(params) {
      const normalizedFontSize = closestFontSize(params.fontSize, fontSizeOptions)
      toolbar.log.textContent = JSON.stringify({ kind: "setFontSize", fontSize: normalizedFontSize })
      if (normalizedFontSize === currentFontSize) return

      currentFontSize = normalizedFontSize
      updatePreview()
    },
    async previewScrolled(params) {
      toolbar.log.textContent = JSON.stringify({ kind: "previewScrolled", ...params })
    },
    async pageZoomChanged(params) {
      toolbar.log.textContent = JSON.stringify({ kind: "pageZoomChanged", ...params })
    },
    async addToAgentContext(params) {
      toolbar.log.textContent = JSON.stringify({ kind: "addToAgentContext", ...params })
      return { added: true }
    },
  })

  function currentSettings(): MarkdownPreviewSettings {
    return {
      fontSize: currentFontSize === defaultFontSize ? null : currentFontSize,
      effectiveFontSize: currentFontSize,
      defaultFontSize,
      fontSizeOptions,
      canAddToAgentContext: true,
    }
  }
})

function installMockToolbar(): {
  toggleThemeButton: HTMLButtonElement,
  shortMarkdownButton: HTMLButtonElement,
  dollarMarkdownButton: HTMLButtonElement,
  diffMarkdownButton: HTMLButtonElement,
  longDiffMarkdownButton: HTMLButtonElement,
  inlineDiffMarkdownButton: HTMLButtonElement,
  mermaid12MarkdownButton: HTMLButtonElement,
  ideDiffColorsButton: HTMLButtonElement,
  ideLinkColorsButton: HTMLButtonElement,
  log: HTMLSpanElement,
} {
  const content = document.getElementById("content")
  if (!content) {
    throw new Error("Missing Markdown preview content element")
  }

  const toolbar = document.createElement("div")
  toolbar.className = "markdownPreviewMockToolbar"
  toolbar.innerHTML = `<strong>Markdown Preview Mock</strong>`

  const toggleThemeButton = document.createElement("button")
  toggleThemeButton.type = "button"
  toggleThemeButton.textContent = "Toggle theme"
  toolbar.append(toggleThemeButton)

  const shortMarkdownButton = document.createElement("button")
  shortMarkdownButton.type = "button"
  shortMarkdownButton.textContent = "Short markdown"
  toolbar.append(shortMarkdownButton)

  const dollarMarkdownButton = document.createElement("button")
  dollarMarkdownButton.type = "button"
  dollarMarkdownButton.textContent = "Dollar markdown"
  toolbar.append(dollarMarkdownButton)

  const diffMarkdownButton = document.createElement("button")
  diffMarkdownButton.type = "button"
  diffMarkdownButton.textContent = "Diff markdown"
  toolbar.append(diffMarkdownButton)

  const longDiffMarkdownButton = document.createElement("button")
  longDiffMarkdownButton.type = "button"
  longDiffMarkdownButton.textContent = "Long diff markdown"
  toolbar.append(longDiffMarkdownButton)

  const inlineDiffMarkdownButton = document.createElement("button")
  inlineDiffMarkdownButton.type = "button"
  inlineDiffMarkdownButton.textContent = "Inline diff"
  toolbar.append(inlineDiffMarkdownButton)

  const mermaid12MarkdownButton = document.createElement("button")
  mermaid12MarkdownButton.type = "button"
  mermaid12MarkdownButton.textContent = "Mermaid 12"
  toolbar.append(mermaid12MarkdownButton)

  const ideDiffColorsButton = document.createElement("button")
  ideDiffColorsButton.type = "button"
  ideDiffColorsButton.textContent = "IDE diff colors"
  toolbar.append(ideDiffColorsButton)

  const ideLinkColorsButton = document.createElement("button")
  ideLinkColorsButton.type = "button"
  ideLinkColorsButton.textContent = "IDE link colors"
  toolbar.append(ideLinkColorsButton)

  const log = document.createElement("span")
  log.id = "mock-run-log"
  toolbar.append(log)

  content.before(toolbar)
  installMockToolbarStyles()
  return {
    toggleThemeButton,
    shortMarkdownButton,
    dollarMarkdownButton,
    diffMarkdownButton,
    longDiffMarkdownButton,
    inlineDiffMarkdownButton,
    mermaid12MarkdownButton,
    ideDiffColorsButton,
    ideLinkColorsButton,
    log,
  }
}

/**
 * Changed blocks with inline changes, as the IDE diff reports them for the `By word` highlighting: changed words of a
 * paragraph, a changed link URL, a changed inline code span, escaped text, and a change in a fenced code block.
 */
function inlineDiffMarkdown(): { markdown: string, changes: MarkdownChangedBlockDescriptor[] } {
  const markdown = inlineDiffMarkdownText
  const inline = (kind: MarkdownInlineChangeDescriptor["kind"], text: string): MarkdownInlineChangeDescriptor => {
    const startOffset = markdown.indexOf(text)
    if (startOffset < 0) throw new Error(`Missing inline change text: ${text}`)
    return { kind, startOffset, endOffset: startOffset + text.length }
  }
  return {
    markdown,
    changes: [
      { kind: "MODIFIED", startLine: 5, endLine: 5, inlineChanges: [inline("MODIFIED", "brown"), inline("ADDED", "keeps running")] },
      { kind: "MODIFIED", startLine: 7, endLine: 7, inlineChanges: [inline("MODIFIED", "new-notes")] },
      { kind: "MODIFIED", startLine: 9, endLine: 9, inlineChanges: [inline("MODIFIED", "build")] },
      { kind: "MODIFIED", startLine: 11, endLine: 11, inlineChanges: [inline("MODIFIED", "\\*stars\\*")] },
      { kind: "MODIFIED", startLine: 14, endLine: 14, inlineChanges: [inline("MODIFIED", "42")] },
    ],
  }
}

/**
 * A document that is several screens long with changes near the top, in the middle, and near the bottom, so the
 * scrollbar change markers have distinct positions.
 */
function longDiffMarkdown(): { markdown: string, changes: MarkdownChangedBlockDescriptor[] } {
  const sectionCount = 60
  const changedSections = new Map<number, { kind: MarkdownChangedBlockDescriptor["kind"], text: string }>([
    [2, { kind: "ADDED", text: "Added paragraph near the top." }],
    [30, { kind: "MODIFIED", text: "Modified paragraph in the middle." }],
    [45, { kind: "REMOVED", text: "Removed paragraph below the middle." }],
    [58, { kind: "ADDED", text: "Added paragraph near the bottom." }],
  ])
  const lines = ["# Long Diff Markdown", ""]
  const changes: MarkdownChangedBlockDescriptor[] = []
  for (let section = 1; section <= sectionCount; section++) {
    lines.push(`## Section ${section}`, "")
    const change = changedSections.get(section)
    lines.push(change?.text ?? `Unchanged paragraph ${section} keeps the document long enough to scroll through several screens.`)
    if (change) {
      changes.push({ kind: change.kind, startLine: lines.length, endLine: lines.length })
    }
    lines.push("")
  }
  return { markdown: lines.join("\n"), changes }
}

function installMockToolbarStyles(): void {
  const style = document.createElement("style")
  style.textContent = `
    .markdownPreviewMockToolbar {
      position: sticky;
      z-index: 1;
      top: 0;
      display: flex;
      align-items: center;
      gap: 12px;
      min-height: 36px;
      padding: 0 12px;
      border-bottom: 1px solid var(--ij-border, #d0d7de);
      background: var(--ij-bg-panel, #f6f8fa);
      color: var(--ij-text-primary, #24292f);
      font: 13px system-ui, sans-serif;
    }

    .markdownPreviewMockToolbar button {
      height: 24px;
      padding: 0 10px;
    }

    #mock-run-log {
      color: var(--ij-text-secondary, #6a737d);
    }
  `
  document.head.append(style)
}

function closestFontSize(fontSize: number, options: number[]): number {
  return options.reduce((closest, candidate) => {
    return Math.abs(candidate - fontSize) < Math.abs(closest - fontSize) ? candidate : closest
  }, options[0] ?? fontSize)
}

const sampleMarkdown = `---
project: IntelliJ IDEA Platform
languages: [Kotlin, Java]
framework: IntelliJ Platform SDK
build-system: Bazel
repository: monorepo
---

# Markdown Preview Mock

This page runs the real Markdown preview entry through the WebView testkit mock bridge.

[External link](https://www.jetbrains.com), [Local anchor link](#-локальная-навигация-раздела), an inline command: \`echo inline\`, and a path \`docs/guide.md:12\`.

Inline math \\(a+b\\) and display math $$c=d$$.

## Images

![Zoomable preview](images/zoomable-preview.svg)

Inline image: ![Inline preview](images/inline-preview.svg) stays inline.

[![Linked preview](images/linked-preview.svg)](https://www.jetbrains.com)

## Runnable Code

\`\`\`bash
echo first line
cat src/Main.kt
cat src\\WindowsPath.kt
cat missing/Nope.kt
echo second line
\`\`\`

## Project Tree

\`\`\`text
.venv/
app/
  init.py
  main.py
  routes.py
templates/
  index.html
static/
style.css
requirements.txt
\`\`\`

## Django Tree

\`\`\`
my_django_project/
  manage.py
  ...
  my_django_app/
    migrations/
    templates/
    models.py
    urls.py
    ...
\`\`\`

## Mermaid

\`\`\`mermaid
flowchart LR
  A[src/Mermaid.kt] --> B[Preview]
\`\`\`

## Table

| Item | State |
| --- | --- |
| Icons | AllIcons |
| Theme | Toggleable |

## 📌 Локальная навигация раздела

Anchor target with a Unicode heading.

> [!NOTE]
> Ship it with :rocket: emoji and GitHub alerts.
`

const mermaid12Markdown = `# Mermaid 12 & Markdown Enhancements

> [!NOTE]
> Modern Mermaid 12 diagrams and :rocket: emoji shortcodes.

> [!WARNING]
> Check dark mode contrast across diagrams.

\`\`\`mermaid
---
config:
  layout: tidy-tree
---
mindmap
  root((Architecture))
    Frontend
    Backend
\`\`\`

\`\`\`mermaid
zenuml
  title AuthService
  Client->Server.login() {
    return token
  }
\`\`\`

\`\`\`mermaid
usecase-beta
  actor dev(Developer)
  dev --> uc1(Render Mermaid 12)
\`\`\`

\`\`\`mermaid
packet-beta
  0-7: "Header"
  8-15: "Payload"
\`\`\`

\`\`\`mermaid
treeView-beta
  "src"
    "MermaidBlock.tsx"
    "MarkdownPreviewApp.tsx"
\`\`\`

\`\`\`mermaid
flowchart LR
  A@{ icon: "lucide:server", label: "Gateway" } --> B@{ shape: person, label: "User" }
\`\`\`
`

const shortMarkdown = `# Short Markdown

This mock document has no table of contents.
`

const ordinaryDollarMarkdown = `# Dollar Markdown

This document mentions $5, $10, and an escaped \\$placeholder without math.
`

const inlineDiffMarkdownText = `# Inline Diff Markdown

Unchanged intro paragraph.

The quick brown fox jumps over the lazy dog and keeps running.

See the [release notes](https://example.com/new-notes) for details.

Run \`gradle build\` before pushing.

Escaped \\*stars\\* stay literal here.

\`\`\`kotlin
val answer = 42
\`\`\`
`

const diffMarkdown = `# Diff Markdown

Unchanged intro paragraph.

Added paragraph line.

Modified paragraph line.

Removed paragraph line.

| Action | Keybinding |
| --- | --- |
| Stage | Ctrl+S |
| Added row | Ctrl+A |
| Commit | Ctrl+K |
`
