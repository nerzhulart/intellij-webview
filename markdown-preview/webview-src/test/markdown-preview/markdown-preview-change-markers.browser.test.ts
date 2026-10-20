// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { dirname, resolve } from "node:path"
import { fileURLToPath } from "node:url"
import { inflateSync } from "node:zlib"
import { startWebViewMockPreview, type WebViewMockPreviewServer } from "@nerzhulart/intellij-webview-sdk-testkit"

type Locator = {
  click(): Promise<void>
}

type Mouse = {
  click(x: number, y: number): Promise<void>
  move(x: number, y: number): Promise<void>
}

type Page = {
  goto(url: string): Promise<void>
  getByRole(role: string, options?: { name?: string | RegExp; exact?: boolean }): Locator
  evaluate<Result>(pageFunction: () => Result | Promise<Result>): Promise<Result>
  evaluate<Result, Arg>(pageFunction: (arg: Arg) => Result | Promise<Result>, arg: Arg): Promise<Result>
  waitForFunction<Arg>(pageFunction: (arg: Arg) => boolean, arg?: Arg): Promise<unknown>
  waitForSelector(selector: string): Promise<unknown>
  screenshot(options: { clip: { x: number; y: number; width: number; height: number } }): Promise<Uint8Array>
  mouse: Mouse
}

type TestApi = {
  (title: string, handler: (fixtures: { page: Page }) => Promise<void> | void): void
  beforeAll(handler: () => Promise<void> | void): void
  afterAll(handler: () => Promise<void> | void): void
  use(options: Record<string, unknown>): void
}

type ExpectApi = {
  (actual: boolean): { toBe(expected: boolean): void }
}

type PlaywrightTestModule = {
  expect: ExpectApi
  test: TestApi
}

interface PaintedMarker {
  color: string
  top: number
  height: number
}

interface MarkerState {
  hasClass: boolean
  css: string | null
  markers: PaintedMarker[]
  colors: { added: string; modified: string; removed: string }
  changedBlockTops: number[]
  scrollHeight: number
  clientHeight: number
  innerWidth: number
}

const playwrightTestPackage: string = "@playwright/test"
const { expect, test } = await import(playwrightTestPackage) as unknown as PlaywrightTestModule

const testDir = dirname(fileURLToPath(import.meta.url))
const webviewSrcDir = resolve(testDir, "../..")

// Headless Chromium hides scrollbars by default, so the classic scrollbar track with the markers would not be painted.
test.use({ launchOptions: { ignoreDefaultArgs: ["--hide-scrollbars"] } })

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

test("paints change markers into the scrollbar track in document order", async ({ page }) => {
  await openLongDiff(page)
  const state = await markerState(page)

  expect(state.hasClass).toBe(true)
  expect(state.markers.length === 4).toBe(true)
  expect(state.markers.map(marker => marker.color).join() === [
    state.colors.added,
    state.colors.modified,
    state.colors.removed,
    state.colors.added,
  ].join()).toBe(true)

  for (let index = 1; index < state.markers.length; index++) {
    expect(state.markers[index].top > state.markers[index - 1].top).toBe(true)
  }
  state.markers.forEach((marker, index) => {
    const expectedTop = state.changedBlockTops[index] / state.scrollHeight * state.clientHeight
    expect(Math.abs(marker.top - expectedTop) <= 2).toBe(true)
    expect(marker.height >= 3).toBe(true)
  })

  const bottomMarker = state.markers[3]
  const markerPixel = await pixelAt(page, state.innerWidth - 5, bottomMarker.top + bottomMarker.height / 2)
  expect(colorsClose(markerPixel, parseRgb(state.colors.added))).toBe(true)
  const markerEdgePixel = await pixelAt(page, state.innerWidth - 9, bottomMarker.top + bottomMarker.height / 2)
  expect(colorsClose(markerEdgePixel, parseRgb(state.colors.added))).toBe(true)

  const gapY = (state.markers[1].top + state.markers[2].top) / 2
  const gapPixel = await pixelAt(page, state.innerWidth - 5, gapY)
  expect(colorsClose(gapPixel, parseRgb(state.colors.added))).toBe(false)
  expect(colorsClose(gapPixel, parseRgb(state.colors.modified))).toBe(false)
})

test("does not paint markers or force the scrollbar without changes", async ({ page }) => {
  await openLongDiff(page)
  await page.getByRole("button", { name: "Short markdown", exact: true }).click()
  await page.waitForFunction(() => document.getElementById("markdown-change-markers") == null)

  const state = await page.evaluate(() => ({
    hasClass: document.documentElement.classList.contains("has-change-markers"),
    overflowY: getComputedStyle(document.body).overflowY,
    hasVerticalScrollbar: window.innerWidth > document.documentElement.clientWidth,
  }))
  expect(state.hasClass).toBe(false)
  expect(state.overflowY === "auto").toBe(true)
  expect(state.hasVerticalScrollbar).toBe(false)
})

test("keeps the scrollbar track visible for a short document with changes", async ({ page }) => {
  await openPreview(page)
  await page.getByRole("button", { name: "Diff markdown", exact: true }).click()
  await page.waitForFunction(() => {
    return (document.getElementById("markdown-change-markers")?.textContent ?? "").split("linear-gradient").length - 1 === 4
  })

  const state = await page.evaluate(() => ({
    fitsViewport: document.documentElement.scrollHeight <= document.documentElement.clientHeight,
    hasVerticalScrollbar: window.innerWidth > document.documentElement.clientWidth,
  }))
  expect(state.fitsViewport).toBe(true)
  expect(state.hasVerticalScrollbar).toBe(true)
})

test("moves markers when the rendered content grows", async ({ page }) => {
  await openLongDiff(page)
  const initial = await markerState(page)

  await page.evaluate(() => {
    document.querySelector<HTMLElement>(".markdownPreview")!.style.paddingTop = "3000px"
  })
  await page.waitForFunction((initialCss) => document.getElementById("markdown-change-markers")?.textContent !== initialCss, initial.css)
  await waitForMarkersInSync(page)
  const grown = await markerState(page)

  expect(grown.markers.length === initial.markers.length).toBe(true)
  expect(grown.markers[0].top > initial.markers[0].top + 20).toBe(true)
  grown.markers.forEach((marker, index) => {
    const expectedTop = grown.changedBlockTops[index] / grown.scrollHeight * grown.clientHeight
    expect(Math.abs(marker.top - expectedTop) <= 2).toBe(true)
  })
})

test("moves markers after a font size change", async ({ page }) => {
  await openLongDiff(page)
  const initial = await markerState(page)

  await page.evaluate(() => document.documentElement.style.setProperty("--markdown-preview-font-size", "26px"))
  await page.waitForFunction((initialCss) => document.getElementById("markdown-change-markers")?.textContent !== initialCss, initial.css)
  await waitForMarkersInSync(page)
  const zoomed = await markerState(page)

  expect(zoomed.scrollHeight > initial.scrollHeight).toBe(true)
  expect(zoomed.markers.length === initial.markers.length).toBe(true)
  zoomed.markers.forEach((marker, index) => {
    const expectedTop = zoomed.changedBlockTops[index] / zoomed.scrollHeight * zoomed.clientHeight
    expect(Math.abs(marker.top - expectedTop) <= 2).toBe(true)
  })
})

test("re-resolves marker colors after a theme change", async ({ page }) => {
  await openLongDiff(page)
  const light = await markerState(page)

  await page.getByRole("button", { name: "Toggle theme", exact: true }).click()
  await page.waitForFunction(() => document.documentElement.dataset.theme === "dark")
  await page.waitForFunction((lightCss) => {
    const css = document.getElementById("markdown-change-markers")?.textContent
    return css != null && css !== lightCss
  }, light.css)
  const dark = await markerState(page)

  expect(dark.colors.added !== light.colors.added).toBe(true)
  expect(dark.markers[0].color === dark.colors.added).toBe(true)
})

test("paints changed blocks and markers with the IDE diff colors", async ({ page }) => {
  await openLongDiff(page)
  const fallback = await markerState(page)

  await page.getByRole("button", { name: "IDE diff colors", exact: true }).click()
  await waitForMarkerCssChange(page, fallback.css)
  const ide = await markerState(page)

  expect(ide.colors.added === "rgb(46, 139, 87)").toBe(true)
  expect(ide.colors.modified === "rgb(74, 127, 181)").toBe(true)
  expect(ide.colors.removed === "rgb(138, 143, 148)").toBe(true)
  expect(ide.markers.map(marker => marker.color).join() === [
    ide.colors.added,
    ide.colors.modified,
    ide.colors.removed,
    ide.colors.added,
  ].join()).toBe(true)
  expect(await blockBackground(page, ".is-vcs-added") === "rgb(41, 68, 54)").toBe(true)
  expect(await blockBackground(page, ".is-vcs-modified") === "rgb(56, 85, 112)").toBe(true)
  expect(await blockBackground(page, ".is-vcs-removed") === "rgb(72, 74, 74)").toBe(true)

  const bottomMarker = ide.markers[3]
  const markerPixel = await pixelAt(page, ide.innerWidth - 5, bottomMarker.top + bottomMarker.height / 2)
  expect(colorsClose(markerPixel, [46, 139, 87])).toBe(true)
})

test("repaints markers when only the IDE diff colors change", async ({ page }) => {
  await openLongDiff(page)
  const fallback = await markerState(page)
  const colorsButton = page.getByRole("button", { name: "IDE diff colors", exact: true })

  await colorsButton.click()
  await waitForMarkerCssChange(page, fallback.css)
  const first = await markerState(page)
  await colorsButton.click()
  await waitForMarkerCssChange(page, first.css)
  const second = await markerState(page)

  expect(await page.evaluate(() => document.documentElement.dataset.theme) === "light").toBe(true)
  expect(second.markers[0].color === "rgb(176, 92, 230)").toBe(true)
  expect(second.markers[1].color === "rgb(224, 168, 46)").toBe(true)
  expect(second.markers[2].color === "rgb(230, 69, 95)").toBe(true)
  expect(await blockBackground(page, ".is-vcs-added") === "rgb(61, 43, 92)").toBe(true)
})

test("navigates to a change on a click on its scrollbar marker", async ({ page }) => {
  await openLongDiff(page)
  await waitForProgrammaticScrollQuietPeriod(page)
  const state = await markerState(page)
  const initialScrolledCalls = await methodCallCount(page, "markdown.preview/previewScrolled")

  const bottomMarker = state.markers[3]
  await page.mouse.click(state.innerWidth - 5, bottomMarker.top + bottomMarker.height / 2)
  await page.waitForFunction(() => {
    const blocks = document.querySelectorAll(".is-vcs-added")
    const box = blocks[blocks.length - 1]?.getBoundingClientRect()
    return box != null && window.scrollY > 0 && box.top >= 0 && box.bottom <= window.innerHeight
  })
  await page.waitForFunction((initialCalls) => {
    const mock = (window as Window & {
      __WVI_MOCK__?: { calls: { byMethod(method: string): readonly unknown[] } }
    }).__WVI_MOCK__
    return (mock?.calls.byMethod("markdown.preview/previewScrolled").length ?? 0) > initialCalls
  }, initialScrolledCalls)

  const bottomBlockText = await page.evaluate(() => {
    const blocks = document.querySelectorAll(".is-vcs-added")
    return blocks[blocks.length - 1]?.textContent ?? ""
  })
  expect(bottomBlockText === "Added paragraph near the bottom.").toBe(true)
})

test("does not navigate on a click on the scrollbar thumb", async ({ page }) => {
  await openLongDiff(page)
  await waitForProgrammaticScrollQuietPeriod(page)
  const state = await markerState(page)
  const initialScrollY = await page.evaluate(() => window.scrollY)
  const thumbLength = Math.max(24, state.clientHeight * state.clientHeight / state.scrollHeight)
  const thumbTop = initialScrollY / (state.scrollHeight - state.clientHeight) * (state.clientHeight - thumbLength)
  const topMarker = state.markers[0]
  expect(topMarker.top > thumbTop + 2 && topMarker.top + topMarker.height < thumbTop + thumbLength - 2).toBe(true)

  await page.mouse.click(state.innerWidth - 5, topMarker.top + topMarker.height / 2)
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))

  expect(await page.evaluate(() => window.scrollY) === initialScrollY).toBe(true)
})

test("shows the hand cursor only over a marker outside the thumb", async ({ page }) => {
  await openLongDiff(page)
  const state = await markerState(page)
  const isOverMarker = (): Promise<boolean> => page.evaluate(() => document.documentElement.classList.contains("is-over-change-marker"))

  const bottomMarker = state.markers[3]
  await page.mouse.move(state.innerWidth - 5, bottomMarker.top + bottomMarker.height / 2)
  expect(await isOverMarker()).toBe(true)
  expect(await page.evaluate(() => getComputedStyle(document.documentElement).cursor) === "pointer").toBe(true)

  const gapY = (state.markers[1].top + state.markers[2].top) / 2
  await page.mouse.move(state.innerWidth - 5, gapY)
  expect(await isOverMarker()).toBe(false)

  await page.mouse.move(state.innerWidth - 5, bottomMarker.top + bottomMarker.height / 2)
  await page.mouse.move(state.innerWidth / 2, bottomMarker.top + bottomMarker.height / 2)
  expect(await isOverMarker()).toBe(false)

  const topMarker = state.markers[0]
  await page.mouse.move(state.innerWidth - 5, topMarker.top + topMarker.height / 2)
  expect(await isOverMarker()).toBe(false)
})

async function openPreview(page: Page): Promise<void> {
  if (!preview) {
    throw new Error("Markdown preview mock preview server was not started")
  }
  await page.goto(preview.url)
  await page.waitForSelector(".markdownPreviewContent")
}

async function openLongDiff(page: Page): Promise<void> {
  await openPreview(page)
  await page.getByRole("button", { name: "Long diff markdown", exact: true }).click()
  await page.waitForFunction(() => {
    const css = document.getElementById("markdown-change-markers")?.textContent ?? ""
    return document.querySelectorAll(".markdownPreviewContent h2").length === 60 && css.split("linear-gradient").length - 1 === 4
  })
  await waitForMarkersInSync(page)
}

/**
 * Transient content, such as the off-screen Mermaid render container of the previous document, changes the document
 * height for a moment, so the markers are compared with the current geometry until they stay in sync for a while.
 */
async function waitForMarkersInSync(page: Page): Promise<void> {
  const markersInSync = (): boolean => {
    const css = document.getElementById("markdown-change-markers")?.textContent ?? ""
    const positions = css.match(/background-position:\s*([^;]+);/)?.[1]?.split(",") ?? []
    const tops = positions.map(position => Number.parseFloat(position.trim().split(/\s+/)[1]))
    const root = document.documentElement
    const expectedTops = Array.from(document.querySelectorAll<HTMLElement>(".is-vcs-added, .is-vcs-modified, .is-vcs-removed"))
      .map(element => (element.getBoundingClientRect().top + window.scrollY) / root.scrollHeight * root.clientHeight)
      .sort((left, right) => left - right)
    return tops.length > 0 && tops.length === expectedTops.length && tops.every((top, index) => Math.abs(top - expectedTops[index]) <= 1)
  }
  for (let attempt = 0; attempt < 10; attempt++) {
    await page.waitForFunction(markersInSync)
    await page.evaluate(() => new Promise(resolve => setTimeout(resolve, 300)))
    if (await page.evaluate(markersInSync)) return
  }
  throw new Error("Scrollbar change markers do not settle")
}

function waitForMarkerCssChange(page: Page, previousCss: string | null): Promise<unknown> {
  return page.waitForFunction((css) => {
    const current = document.getElementById("markdown-change-markers")?.textContent
    return current != null && current !== css
  }, previousCss)
}

function blockBackground(page: Page, selector: string): Promise<string> {
  return page.evaluate((blockSelector) => {
    const block = document.querySelector<HTMLElement>(blockSelector)
    return block ? getComputedStyle(block).backgroundColor : ""
  }, selector)
}

function methodCallCount(page: Page, method: string): Promise<number> {
  return page.evaluate((methodName) => {
    const mock = (window as Window & {
      __WVI_MOCK__?: { calls: { byMethod(method: string): readonly unknown[] } }
    }).__WVI_MOCK__
    return mock?.calls.byMethod(methodName).length ?? 0
  }, method)
}

/**
 * The preview ignores scroll events for a short period after a host-requested scroll, such as the initial one.
 */
function waitForProgrammaticScrollQuietPeriod(page: Page): Promise<unknown> {
  return page.evaluate(() => new Promise(resolve => setTimeout(resolve, 400)))
}

function markerState(page: Page): Promise<MarkerState> {
  return page.evaluate(() => {
    const resolveColor = (property: string): string => {
      const probe = document.createElement("span")
      probe.style.color = getComputedStyle(document.getElementById("content")!).getPropertyValue(property).trim()
      document.body.append(probe)
      const color = getComputedStyle(probe).color
      probe.remove()
      return color
    }
    const splitLayers = (value: string): string[] => {
      const layers: string[] = []
      let depth = 0
      let current = ""
      for (const char of value) {
        if (char === "(") depth++
        if (char === ")") depth--
        if (char === "," && depth === 0) {
          layers.push(current.trim())
          current = ""
          continue
        }
        current += char
      }
      if (current.trim().length > 0) layers.push(current.trim())
      return layers
    }
    const declaration = (css: string, name: string): string => css.match(new RegExp(`${name}:\\s*([^;]+);`))?.[1] ?? ""

    const css = document.getElementById("markdown-change-markers")?.textContent ?? null
    const images = splitLayers(declaration(css ?? "", "background-image"))
    const sizes = splitLayers(declaration(css ?? "", "background-size"))
    const positions = splitLayers(declaration(css ?? "", "background-position"))
    const markers = images.map((image, index) => {
      const colorProbe = document.createElement("span")
      colorProbe.style.color = image.replace(/^linear-gradient\(/, "").replace(/\)$/, "").split(/,(?![^(]*\))/)[0].trim()
      document.body.append(colorProbe)
      const color = getComputedStyle(colorProbe).color
      colorProbe.remove()
      return {
        color,
        top: Number.parseFloat(positions[index].split(/\s+/)[1]),
        height: Number.parseFloat(sizes[index].replace(/^calc\([^)]*\)\s*/, "")),
      }
    })
    const changedBlockTops = Array.from(document.querySelectorAll<HTMLElement>(".is-vcs-added, .is-vcs-modified, .is-vcs-removed"))
      .map(element => element.getBoundingClientRect().top + window.scrollY)
      .sort((left, right) => left - right)
    return {
      hasClass: document.documentElement.classList.contains("has-change-markers"),
      css,
      markers,
      colors: {
        added: resolveColor("--markdown-vcs-added-border"),
        modified: resolveColor("--markdown-vcs-modified-border"),
        removed: resolveColor("--markdown-vcs-removed-border"),
      },
      changedBlockTops,
      scrollHeight: document.documentElement.scrollHeight,
      clientHeight: document.documentElement.clientHeight,
      innerWidth: window.innerWidth,
    }
  })
}

async function pixelAt(page: Page, x: number, y: number): Promise<[number, number, number]> {
  const png = await page.screenshot({ clip: { x: Math.floor(x), y: Math.floor(y), width: 1, height: 1 } })
  return firstPngPixel(Buffer.from(png))
}

/**
 * Decodes the first pixel of a PNG screenshot. Every PNG row filter predicts zero for the first pixel of the first row,
 * so the raw bytes after the filter type byte are the pixel itself.
 */
function firstPngPixel(png: Buffer): [number, number, number] {
  let offset = 8
  let colorType = 6
  const idat: Buffer[] = []
  while (offset < png.length) {
    const length = png.readUInt32BE(offset)
    const type = png.toString("ascii", offset + 4, offset + 8)
    const data = png.subarray(offset + 8, offset + 8 + length)
    if (type === "IHDR") colorType = data[9]
    if (type === "IDAT") idat.push(data)
    offset += 12 + length
  }
  const raw = inflateSync(Buffer.concat(idat))
  if (colorType !== 2 && colorType !== 6) {
    throw new Error(`Unsupported PNG color type ${colorType}`)
  }
  return [raw[1], raw[2], raw[3]]
}

function parseRgb(color: string): [number, number, number] {
  const [red, green, blue] = (color.match(/\d+(\.\d+)?/g) ?? []).map(Number)
  return [red ?? 0, green ?? 0, blue ?? 0]
}

function colorsClose(first: [number, number, number], second: [number, number, number], tolerance = 6): boolean {
  return first.every((channel, index) => Math.abs(channel - second[index]) <= tolerance)
}
