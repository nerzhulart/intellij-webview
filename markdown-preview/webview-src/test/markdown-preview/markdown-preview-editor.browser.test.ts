// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { dirname, resolve } from "node:path"
import { fileURLToPath } from "node:url"
import { startWebViewMockPreview, type WebViewMockPreviewServer } from "@nerzhulart/intellij-webview-sdk-testkit"

type Locator = {
  click(): Promise<void>
  dblclick(): Promise<void>
  dispatchEvent(type: string, eventInit?: Record<string, unknown>): Promise<void>
  fill(value: string): Promise<void>
  first(): Locator
  hover(): Promise<void>
  press(key: string): Promise<void>
}

type Page = {
  goto(url: string): Promise<void>
  getByRole(role: string, options?: { name?: string | RegExp; exact?: boolean }): Locator
  locator(selector: string): Locator
  evaluate<Result>(pageFunction: () => Result | Promise<Result>): Promise<Result>
  evaluate<Result, Arg>(pageFunction: (arg: Arg) => Result | Promise<Result>, arg: Arg): Promise<Result>
  waitForFunction(pageFunction: () => boolean): Promise<unknown>
  waitForFunction<Arg>(pageFunction: (arg: Arg) => boolean, arg: Arg): Promise<unknown>
  waitForSelector(selector: string): Promise<unknown>
}

type TestApi = {
  (title: string, handler: (fixtures: { page: Page }) => Promise<void> | void): void
  beforeAll(handler: () => Promise<void> | void): void
  afterAll(handler: () => Promise<void> | void): void
}

type ExpectApi = {
  (actual: Locator): { toBeVisible(): Promise<void> }
  (actual: unknown): { toBe(expected: unknown): void; toEqual(expected: unknown): void }
}

type PlaywrightTestModule = {
  expect: ExpectApi
  test: TestApi
}

const playwrightTestPackage: string = "@playwright/test"
const { expect, test } = await import(playwrightTestPackage) as unknown as PlaywrightTestModule

const testDir = dirname(fileURLToPath(import.meta.url))
const webviewSrcDir = resolve(testDir, "../..")

let preview: WebViewMockPreviewServer | undefined

test.beforeAll(async () => {
  preview = await startWebViewMockPreview({
    webviewSrcDir,
    viewId: "markdown-preview",
    mock: resolve(webviewSrcDir, "views/markdown-preview/src/markdownPreviewMock.ts"),
  })
})

test.afterAll(async () => {
  await preview?.close()
})

test("toggles GFM task checkboxes and disables them when canEditDocument is false", async ({ page }) => {
  if (!preview) {
    throw new Error("Markdown preview mock preview server was not started")
  }
  await page.goto(preview.url)
  await setMockMarkdown(page, "# Task List\n\n- [ ] First task item\n- [x] Second completed task\n", true)
  await page.waitForSelector("li.task-list-item input[type='checkbox']")

  const initialStates = await page.evaluate(() => {
    return Array.from(document.querySelectorAll<HTMLInputElement>("li.task-list-item input[type='checkbox']"))
      .map(input => ({ checked: input.checked, disabled: input.disabled }))
  })
  expect(initialStates).toEqual([
    { checked: false, disabled: false },
    { checked: true, disabled: false },
  ])

  await page.locator("li.task-list-item input[type='checkbox']").first().click()
  await page.waitForFunction(() => {
    const first = document.querySelector<HTMLInputElement>("li.task-list-item input[type='checkbox']")
    return first?.checked === true
  })

  const lastEditCall = await lastApplyEditCall(page)
  expect(lastEditCall?.newMarkdown).toBe("- [x] First task item")
  expect(lastEditCall?.range).toEqual({
    startLine: 3,
    startColumn: 1,
    endLine: 3,
    endColumn: 22,
  })

  // When canEditDocument is false, task checkboxes are disabled.
  await setMockMarkdown(page, "# Read Only\n\n- [ ] Locked task\n", false)
  await page.waitForFunction(() => {
    const first = document.querySelector<HTMLInputElement>("li.task-list-item input[type='checkbox']")
    return first?.disabled === true
  })
})

test("opens text block editor on double click or hover button, formats with toolbar and hotkeys, and suppresses agent context popover", async ({ page }) => {
  if (!preview) {
    throw new Error("Markdown preview mock preview server was not started")
  }
  await page.goto(preview.url)
  await setMockMarkdown(page, "# Editable Document\n\nHello world paragraph.\n", true)
  await page.waitForSelector(".markdownPreviewContent p")

  // Hovering a paragraph reveals the floating Edit block trigger.
  await page.locator(".markdownPreviewContent p").hover()
  await expect(page.getByRole("button", { name: "Edit block" })).toBeVisible()
  await page.getByRole("button", { name: "Edit block" }).click()

  await page.waitForSelector(".markdownBlockEditorTextarea")
  const initialDraft = await page.evaluate(() => {
    return document.querySelector<HTMLTextAreaElement>(".markdownBlockEditorTextarea")?.value ?? ""
  })
  expect(initialDraft).toBe("Hello world paragraph.")

  // Selecting text inside the active block editor must not trigger the Add to Agent Context popover.
  await page.evaluate(() => {
    const textarea = document.querySelector<HTMLTextAreaElement>(".markdownBlockEditorTextarea")
    if (!textarea) throw new Error("Missing editor textarea")
    textarea.focus()
    textarea.setSelectionRange(6, 11)
    document.dispatchEvent(new Event("selectionchange"))
    document.dispatchEvent(new MouseEvent("mouseup", { bubbles: true }))
  })
  expect(await page.evaluate(() => document.querySelector(".markdownAgentContextPopover") == null)).toBe(true)

  // Click Bold in formatting toolbar to wrap "world" in **world**.
  await page.getByRole("button", { name: "Bold", exact: true }).click()
  const afterBold = await page.evaluate(() => {
    return document.querySelector<HTMLTextAreaElement>(".markdownBlockEditorTextarea")?.value ?? ""
  })
  expect(afterBold).toBe("Hello **world** paragraph.")

  // Select "Hello" and press Control+i to format as italic.
  await page.evaluate(() => {
    const textarea = document.querySelector<HTMLTextAreaElement>(".markdownBlockEditorTextarea")!
    textarea.focus()
    textarea.setSelectionRange(0, 5)
  })
  await page.locator(".markdownBlockEditorTextarea").press("Control+i")
  const afterItalic = await page.evaluate(() => {
    return document.querySelector<HTMLTextAreaElement>(".markdownBlockEditorTextarea")?.value ?? ""
  })
  expect(afterItalic).toBe("*Hello* **world** paragraph.")

  // Commit with Control+Enter.
  await page.locator(".markdownBlockEditorTextarea").press("Control+Enter")
  await page.waitForFunction(() => {
    return document.querySelector(".markdownBlockEditor") == null &&
      document.querySelector(".markdownPreviewContent p strong")?.textContent === "world" &&
      document.querySelector(".markdownPreviewContent p em")?.textContent === "Hello"
  })

  // Double-clicking a heading opens the editor; pressing Escape cancels without applying.
  const callsBeforeCancel = await applyEditCallCount(page)
  await page.locator(".markdownPreviewContent h1").dblclick()
  await page.waitForSelector(".markdownBlockEditorTextarea")
  await page.locator(".markdownBlockEditorTextarea").fill("# Abandoned Title")
  await page.locator(".markdownBlockEditorTextarea").press("Escape")
  await page.waitForFunction(() => document.querySelector(".markdownBlockEditor") == null)
  expect(await page.evaluate(() => document.querySelector(".markdownPreviewContent h1")?.textContent)).toBe("Editable Document")
  expect(await applyEditCallCount(page)).toBe(callsBeforeCancel)
})

test("edits GFM table cells, adds rows, toggles column alignment, and commits formatted markdown table", async ({ page }) => {
  if (!preview) {
    throw new Error("Markdown preview mock preview server was not started")
  }
  await page.goto(preview.url)
  await setMockMarkdown(
    page,
    "# Table Test\n\n| Name | Status |\n| --- | --- |\n| Alpha | Ready |\n",
    true,
  )
  await page.waitForSelector(".markdownPreviewContent table")

  await page.locator(".markdownPreviewContent table").dblclick()
  await page.waitForSelector(".markdownTableEditorGrid")

  await page.getByRole("textbox", { name: "Row 1 Column 2" }).fill("Shipped")
  await page.getByRole("button", { name: "Add table row" }).click()
  await page.getByRole("textbox", { name: "Row 2 Column 1" }).fill("Beta")
  await page.getByRole("textbox", { name: "Row 2 Column 2" }).fill("In Progress")
  await page.getByRole("button", { name: /Column 2 alignment/ }).click()

  await page.getByRole("button", { name: "Apply changes" }).click()
  await page.waitForFunction(() => {
    const rows = document.querySelectorAll(".markdownPreviewContent tbody tr")
    return document.querySelector(".markdownBlockEditor") == null && rows.length === 2
  })

  const lastEdit = await lastApplyEditCall(page)
  expect(lastEdit?.newMarkdown.includes("| Alpha | Shipped")).toBe(true)
  expect(lastEdit?.newMarkdown.includes("| Beta  | In Progress")).toBe(true)
  expect(lastEdit?.newMarkdown.includes(":---")).toBe(true)
})

test("repairs broken Mermaid diagram using live preview in code block editor", async ({ page }) => {
  if (!preview) {
    throw new Error("Markdown preview mock preview server was not started")
  }
  await page.goto(preview.url)
  await setMockMarkdown(
    page,
    "# Mermaid Repair\n\n```mermaid\nflowchart LR\n  Broken -->\n```\n",
    true,
  )
  await page.waitForSelector(".mermaidBlock.hasError")

  await page.getByRole("button", { name: "Edit diagram" }).click()
  await page.waitForSelector(".markdownBlockEditor.is-mermaid-editor")

  const codeEditor = page.getByRole("textbox", { name: "Edit Mermaid diagram code" })
  await codeEditor.fill("flowchart LR\n  FixedNode[Fixed] --> WorkingNode[Working]")

  // Wait for the live preview inside the editor to render the repaired SVG diagram.
  await page.waitForSelector(".markdownCodeBlockLivePreview svg.mermaidSvg")

  await page.getByRole("button", { name: "Apply changes" }).click()
  await page.waitForFunction(() => {
    return document.querySelector(".markdownBlockEditor") == null &&
      document.querySelector(".markdownPreviewContent .mermaidBlock.isInteractive svg.mermaidSvg") != null
  })

  const lastEdit = await lastApplyEditCall(page)
  expect(lastEdit?.newMarkdown).toBe("```mermaid\nflowchart LR\n  FixedNode[Fixed] --> WorkingNode[Working]\n```")
})

test("saves pasted and dropped images into assets and autocompletes project file links", async ({ page }) => {
  if (!preview) {
    throw new Error("Markdown preview mock preview server was not started")
  }
  await page.goto(preview.url)
  await setMockMarkdown(page, "# Media and Links\n\nInitial paragraph.\n", true)
  await page.waitForSelector(".markdownPreviewContent p")

  await page.locator(".markdownPreviewContent p").dblclick()
  await page.waitForSelector(".markdownBlockEditorTextarea")

  // Paste an image file into the block editor textarea.
  await page.evaluate(() => {
    const textarea = document.querySelector<HTMLTextAreaElement>(".markdownBlockEditorTextarea")!
    textarea.focus()
    textarea.setSelectionRange(textarea.value.length, textarea.value.length)
    const dataTransfer = new DataTransfer()
    dataTransfer.items.add(new File(["png-bytes"], "architecture.png", { type: "image/png" }))
    const pasteEvent = new ClipboardEvent("paste", {
      bubbles: true,
      cancelable: true,
      clipboardData: dataTransfer,
    })
    textarea.dispatchEvent(pasteEvent)
  })

  await page.waitForFunction(() => {
    const value = document.querySelector<HTMLTextAreaElement>(".markdownBlockEditorTextarea")?.value ?? ""
    return value.includes("![architecture](assets/architecture.png)")
  })

  // Drag and drop an image file onto the block editor textarea.
  await page.evaluate(() => {
    const textarea = document.querySelector<HTMLTextAreaElement>(".markdownBlockEditorTextarea")!
    textarea.focus()
    textarea.setSelectionRange(textarea.value.length, textarea.value.length)
    const dataTransfer = new DataTransfer()
    dataTransfer.items.add(new File(["png-bytes-2"], "screenshot.png", { type: "image/png" }))
    const dropEvent = new DragEvent("drop", {
      bubbles: true,
      cancelable: true,
      dataTransfer,
    })
    textarea.dispatchEvent(dropEvent)
  })

  await page.waitForFunction(() => {
    const value = document.querySelector<HTMLTextAreaElement>(".markdownBlockEditorTextarea")?.value ?? ""
    return value.includes("![screenshot](assets/screenshot-1.png)")
  })

  // Type `[Guide](guide` to trigger project file link autocomplete.
  await page.evaluate(() => {
    const textarea = document.querySelector<HTMLTextAreaElement>(".markdownBlockEditorTextarea")!
    const nextValue = "Check [Guide](guide"
    const valueSetter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value")?.set
    valueSetter?.call(textarea, nextValue)
    textarea.focus()
    textarea.setSelectionRange(nextValue.length, nextValue.length)
    textarea.dispatchEvent(new Event("input", { bubbles: true }))
    textarea.dispatchEvent(new Event("select", { bubbles: true }))
  })

  await page.waitForSelector(".markdownFileLinkAutocomplete")
  await page.getByRole("option", { name: "docs/guide.md" }).click()

  const valueAfterAutocomplete = await page.evaluate(() => {
    return document.querySelector<HTMLTextAreaElement>(".markdownBlockEditorTextarea")?.value ?? ""
  })
  expect(valueAfterAutocomplete).toBe("Check [Guide](docs/guide.md)")

  // Apply and verify the host received the updated markdown.
  await page.getByRole("button", { name: "Apply changes" }).click()
  await page.waitForFunction(() => document.querySelector(".markdownBlockEditor") == null)
  const lastEdit = await lastApplyEditCall(page)
  expect(lastEdit?.newMarkdown).toBe("Check [Guide](docs/guide.md)")
})

async function setMockMarkdown(page: Page, markdown: string, canEditDocument: boolean): Promise<void> {
  await page.evaluate(({ nextMarkdown, editable }) => {
    const win = window as unknown as {
      __WVI_MOCK__: { calls: { clear(): void } }
      __MARKDOWN_PREVIEW_MOCK_SET_CONTENT__?: (markdown: string, editable?: boolean) => void
    }
    win.__WVI_MOCK__.calls.clear()
    win.__MARKDOWN_PREVIEW_MOCK_SET_CONTENT__?.(nextMarkdown, editable)
  }, { nextMarkdown: markdown, editable: canEditDocument })
}

function applyEditCallCount(page: Page): Promise<number> {
  return page.evaluate(() => {
    const mock = (window as Window & {
      __WVI_MOCK__?: { calls: { byMethod(method: string): readonly unknown[] } }
    }).__WVI_MOCK__
    return mock?.calls.byMethod("markdown.preview/applyDocumentEdit").length ?? 0
  })
}

function lastApplyEditCall(page: Page): Promise<{
  contentVersion: number
  range: { startLine: number, startColumn: number, endLine: number, endColumn: number }
  newMarkdown: string
} | undefined> {
  return page.evaluate(() => {
    const mock = (window as Window & {
      __WVI_MOCK__?: { calls: { byMethod(method: string): readonly { params: unknown }[] } }
    }).__WVI_MOCK__
    const calls = mock?.calls.byMethod("markdown.preview/applyDocumentEdit") ?? []
    return calls[calls.length - 1]?.params as {
      contentVersion: number
      range: { startLine: number, startColumn: number, endLine: number, endColumn: number }
      newMarkdown: string
    } | undefined
  })
}
