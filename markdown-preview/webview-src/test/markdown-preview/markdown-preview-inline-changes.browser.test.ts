// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { dirname, resolve } from "node:path"
import { fileURLToPath } from "node:url"
import { startWebViewMockPreview, type WebViewMockPreviewServer } from "@nerzhulart/intellij-webview-sdk-testkit"

type Locator = {
  click(): Promise<void>
}

type Page = {
  goto(url: string): Promise<void>
  getByRole(role: string, options?: { name?: string | RegExp; exact?: boolean }): Locator
  evaluate<Result>(pageFunction: () => Result | Promise<Result>): Promise<Result>
  evaluate<Result, Arg>(pageFunction: (arg: Arg) => Result | Promise<Result>, arg: Arg): Promise<Result>
  waitForFunction<Arg>(pageFunction: (arg: Arg) => boolean, arg?: Arg): Promise<unknown>
  waitForSelector(selector: string): Promise<unknown>
}

type TestApi = {
  (title: string, handler: (fixtures: { page: Page }) => Promise<void> | void): void
  beforeAll(handler: () => Promise<void> | void): void
  afterAll(handler: () => Promise<void> | void): void
}

type ExpectApi = {
  (actual: unknown): { toBe(expected: unknown): void; toEqual(expected: unknown): void }
}

type PlaywrightTestModule = {
  expect: ExpectApi
  test: TestApi
}

interface InlineChange {
  kind: string | undefined
  text: string
}

interface BlockState {
  classes: string[]
  background: string
  inlineChanges: InlineChange[]
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

test("highlights changed words inside a pale changed paragraph", async ({ page }) => {
  await openInlineDiff(page)
  const paragraph = await blockState(page, "p", "The quick")

  expect(paragraph.classes.includes("is-vcs-modified")).toBe(true)
  expect(paragraph.classes.includes("has-vcs-inline-changes")).toBe(true)
  expect(paragraph.inlineChanges).toEqual([
    { kind: "MODIFIED", text: "brown" },
    { kind: "ADDED", text: "keeps running" },
  ])
})

test("maps escaped text to the changed source characters", async ({ page }) => {
  await openInlineDiff(page)
  const paragraph = await blockState(page, "p", "Escaped")

  expect(paragraph.inlineChanges).toEqual([{ kind: "MODIFIED", text: "*stars*" }])
})

test("highlights a link with a changed URL and an inline code span as a whole", async ({ page }) => {
  await openInlineDiff(page)
  const link = await blockState(page, "p", "See the")
  const code = await blockState(page, "p", "Run")

  expect(link.inlineChanges).toEqual([{ kind: "MODIFIED", text: "release notes" }])
  expect(await page.evaluate(() => document.querySelector("a[data-vcs-inline]")?.textContent ?? "")).toBe("release notes")
  expect(link.classes.includes("has-vcs-inline-changes")).toBe(true)
  expect(code.inlineChanges).toEqual([{ kind: "MODIFIED", text: "gradle build" }])
  expect(await page.evaluate(() => document.querySelector("code[data-vcs-inline]")?.textContent ?? "")).toBe("gradle build")
  expect(code.classes.includes("has-vcs-inline-changes")).toBe(true)
})

test("keeps the whole highlighting of a changed fenced code block", async ({ page }) => {
  await openInlineDiff(page)
  const codeBlock = await blockState(page, "pre", "val answer")

  expect(codeBlock.classes.includes("is-vcs-modified")).toBe(true)
  expect(codeBlock.classes.includes("has-vcs-inline-changes")).toBe(false)
  expect(codeBlock.inlineChanges).toEqual([])
  expect(codeBlock.background).toBe(await resolvedBackground(page, "--markdown-vcs-modified-bg"))
})

test("paints changed words with the full color and their block with the pale one", async ({ page }) => {
  await openInlineDiff(page)
  const fallbackFull = await resolvedBackground(page, "--markdown-vcs-modified-bg")
  const fallbackPale = await resolvedBackground(page, "--markdown-vcs-modified-ignored-bg")

  expect(fallbackFull === fallbackPale).toBe(false)
  expect((await blockState(page, "p", "The quick")).background).toBe(fallbackPale)
  expect(await inlineBackground(page, "MODIFIED")).toBe(fallbackFull)
  expect(await inlineBackground(page, "ADDED")).toBe(await resolvedBackground(page, "--markdown-vcs-added-bg"))
  expect(await page.evaluate(() => getComputedStyle(document.querySelector("code[data-vcs-inline]")!).backgroundColor)).toBe(fallbackFull)

  await page.getByRole("button", { name: "IDE diff colors", exact: true }).click()
  await page.waitForFunction(() => document.documentElement.style.getPropertyValue("--markdown-diff-modified-ignored-bg") !== "")

  expect((await blockState(page, "p", "The quick")).background).toBe("rgb(42, 53, 66)")
  expect(await inlineBackground(page, "MODIFIED")).toBe("rgb(56, 85, 112)")
  expect(await inlineBackground(page, "ADDED")).toBe("rgb(41, 68, 54)")
  expect(await page.evaluate(() => getComputedStyle(document.querySelector("code[data-vcs-inline]")!).backgroundColor)).toBe("rgb(56, 85, 112)")
})

test("drops inline highlighting for blocks without inline changes", async ({ page }) => {
  await openInlineDiff(page)
  await page.getByRole("button", { name: "Diff markdown", exact: true }).click()
  await page.waitForFunction(() => document.querySelector(".markdownPreviewContent h1")?.textContent === "Diff Markdown")
  await page.waitForSelector(".is-vcs-modified")

  const state = await page.evaluate(() => ({
    inlineChanges: document.querySelectorAll("[data-vcs-inline]").length,
    paleBlocks: document.querySelectorAll(".has-vcs-inline-changes").length,
  }))
  expect(state).toEqual({ inlineChanges: 0, paleBlocks: 0 })
})

async function openInlineDiff(page: Page): Promise<void> {
  if (!preview) {
    throw new Error("Markdown preview mock preview server was not started")
  }
  await page.goto(preview.url)
  await page.waitForSelector(".markdownPreviewContent")
  await page.getByRole("button", { name: "Inline diff", exact: true }).click()
  await page.waitForFunction(() => {
    return document.querySelector(".markdownPreviewContent h1")?.textContent === "Inline Diff Markdown"
      && document.querySelectorAll(".has-vcs-inline-changes").length === 4
  })
}

function blockState(page: Page, tagName: string, textPrefix: string): Promise<BlockState> {
  return page.evaluate(({ blockTagName, prefix }) => {
    const block = Array.from(document.querySelectorAll<HTMLElement>(`.markdownPreviewContent ${blockTagName}`))
      .find(element => (element.textContent ?? "").startsWith(prefix))
    if (!block) throw new Error(`Missing ${blockTagName} block starting with ${prefix}`)
    return {
      classes: Array.from(block.classList),
      background: getComputedStyle(block).backgroundColor,
      inlineChanges: Array.from(block.querySelectorAll<HTMLElement>("[data-vcs-inline]")).map(element => ({
        kind: element.dataset.vcsInline,
        text: element.textContent ?? "",
      })),
    }
  }, { blockTagName: tagName, prefix: textPrefix })
}

function inlineBackground(page: Page, kind: string): Promise<string> {
  return page.evaluate((changeKind) => {
    const element = document.querySelector<HTMLElement>(`span[data-vcs-inline="${changeKind}"]`)
    return element ? getComputedStyle(element).backgroundColor : ""
  }, kind)
}

/**
 * Resolves a color custom property of the preview the same way the browser resolves the background of an element.
 */
function resolvedBackground(page: Page, property: string): Promise<string> {
  return page.evaluate((propertyName) => {
    const probe = document.createElement("span")
    probe.style.background = `var(${propertyName})`
    document.querySelector(".markdownPreview")!.append(probe)
    const color = getComputedStyle(probe).backgroundColor
    probe.remove()
    return color
  }, property)
}
