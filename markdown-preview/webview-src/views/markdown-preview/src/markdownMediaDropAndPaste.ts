// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import type { ClipboardEvent as ReactClipboardEvent, DragEvent as ReactDragEvent } from "react"
import { serializeGfmTable } from "./markdownAstPatcher"
import type { MarkdownTextareaController } from "./markdownBlockEditor"
import type {
  MarkdownSaveImageAssetParams,
  MarkdownSaveImageAssetResultParams,
} from "./markdownPreviewTypes"

const IMAGE_EXTENSION_PATTERN = /\.(png|jpe?g|gif|webp|svg|bmp|avif)$/i
const SINGLE_URL_PATTERN = /^https?:\/\/\S+$/i

export async function handleMarkdownEditorPaste(
  event: ReactClipboardEvent<HTMLTextAreaElement>,
  controller: MarkdownTextareaController,
  onSaveImageAsset?: (params: MarkdownSaveImageAssetParams) => Promise<MarkdownSaveImageAssetResultParams>,
): Promise<boolean> {
  const clipboardData = event.clipboardData
  if (!clipboardData) return false

  const imageFiles = extractClipboardImageFiles(clipboardData)
  if (imageFiles.length > 0 && onSaveImageAsset) {
    event.preventDefault()
    await saveAndInsertImages(imageFiles, controller, onSaveImageAsset)
    return true
  }

  // @github/paste-markdown style: pasting a URL over a non-empty text selection wraps it as [selection](url).
  const plainText = clipboardData.getData("text/plain")
  const selectedText = controller.value.slice(controller.selectionStart, controller.selectionEnd)
  if (
    selectedText.length > 0 &&
    !SINGLE_URL_PATTERN.test(selectedText.trim()) &&
    SINGLE_URL_PATTERN.test(plainText.trim())
  ) {
    event.preventDefault()
    const url = plainText.trim()
    controller.replaceRange(
      controller.selectionStart,
      controller.selectionEnd,
      `[${selectedText}](${url})`,
      false,
    )
    return true
  }

  // @github/paste-markdown style: pasting HTML tables or TSV spreadsheet rows converts them to GFM tables.
  const htmlText = clipboardData.getData("text/html")
  const htmlTableMarkdown = htmlText ? convertHtmlTableToGfm(htmlText) : undefined
  if (htmlTableMarkdown) {
    event.preventDefault()
    controller.insertAtCursor(htmlTableMarkdown)
    return true
  }

  const tsvTableMarkdown = plainText ? convertTsvToGfmTable(plainText) : undefined
  if (tsvTableMarkdown) {
    event.preventDefault()
    controller.insertAtCursor(tsvTableMarkdown)
    return true
  }

  return false
}

export async function handleMarkdownEditorDrop(
  event: ReactDragEvent<HTMLTextAreaElement>,
  controller: MarkdownTextareaController,
  onSaveImageAsset?: (params: MarkdownSaveImageAssetParams) => Promise<MarkdownSaveImageAssetResultParams>,
): Promise<boolean> {
  const dataTransfer = event.dataTransfer
  if (!dataTransfer || !onSaveImageAsset) return false

  const imageFiles = Array.from(dataTransfer.files ?? []).filter(isImageFile)
  if (imageFiles.length === 0) return false

  event.preventDefault()
  event.stopPropagation()
  await saveAndInsertImages(imageFiles, controller, onSaveImageAsset)
  return true
}

function extractClipboardImageFiles(clipboardData: DataTransfer): File[] {
  const fromFiles = Array.from(clipboardData.files ?? []).filter(isImageFile)
  if (fromFiles.length > 0) return fromFiles

  const fromItems: File[] = []
  for (const item of Array.from(clipboardData.items ?? [])) {
    if (item.kind === "file" && item.type.startsWith("image/")) {
      const file = item.getAsFile()
      if (file) fromItems.push(file)
    }
  }
  return fromItems
}

function isImageFile(file: File): boolean {
  return file.type.startsWith("image/") || IMAGE_EXTENSION_PATTERN.test(file.name)
}

async function saveAndInsertImages(
  files: readonly File[],
  controller: MarkdownTextareaController,
  onSaveImageAsset: (params: MarkdownSaveImageAssetParams) => Promise<MarkdownSaveImageAssetResultParams>,
): Promise<void> {
  const references: string[] = []
  for (const file of files) {
    const base64Bytes = await readFileAsBase64(file)
    if (!base64Bytes) continue
    const result = await onSaveImageAsset({
      fileNameHint: file.name || "image.png",
      mimeType: file.type || "image/png",
      base64Bytes,
    })
    if (result.saved && result.relativePath) {
      const alt = deriveAltText(file.name)
      references.push(`![${alt}](${result.relativePath})`)
    }
  }
  if (references.length > 0) {
    controller.insertAtCursor(references.join("\n"))
  }
}

function deriveAltText(fileName: string): string {
  const trimmed = fileName.trim()
  if (!trimmed) return ""
  const withoutExt = trimmed.replace(/\.[^.]+$/, "")
  if (!withoutExt || withoutExt.toLowerCase() === "image" || withoutExt.toLowerCase() === "blob") {
    return ""
  }
  return withoutExt.replace(/[\[\]]/g, "")
}

function readFileAsBase64(file: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const result = typeof reader.result === "string" ? reader.result : ""
      const base64 = result.includes("base64,") ? result.slice(result.indexOf("base64,") + 7) : result
      resolve(base64)
    }
    reader.onerror = () => reject(reader.error ?? new Error("Failed to read image file"))
    reader.readAsDataURL(file)
  })
}

function convertHtmlTableToGfm(html: string): string | undefined {
  if (!/<table[\s>]/i.test(html)) return undefined
  try {
    const parser = new DOMParser()
    const doc = parser.parseFromString(html, "text/html")
    const tableElement = doc.querySelector("table")
    if (!tableElement) return undefined

    const rows = Array.from(tableElement.querySelectorAll("tr"))
      .map(tr => Array.from(tr.querySelectorAll("th, td")).map(cell => (cell.textContent ?? "").trim()))
      .filter(row => row.length > 0)
    if (rows.length === 0) return undefined

    const headers = rows[0]
    const bodyRows = rows.slice(1)
    return serializeGfmTable({
      headers,
      alignments: headers.map(() => null),
      rows: bodyRows,
    })
  }
  catch {
    return undefined
  }
}

function convertTsvToGfmTable(text: string): string | undefined {
  const normalized = text.replace(/\r\n/g, "\n").trim()
  if (!normalized.includes("\t")) return undefined
  const lines = normalized.split("\n")
  if (lines.length < 2) return undefined
  if (!lines.every(line => line.includes("\t"))) return undefined

  const matrix = lines.map(line => line.split("\t").map(cell => cell.trim()))
  const headers = matrix[0]
  if (!headers || headers.length < 2) return undefined
  return serializeGfmTable({
    headers,
    alignments: headers.map(() => null),
    rows: matrix.slice(1),
  })
}
