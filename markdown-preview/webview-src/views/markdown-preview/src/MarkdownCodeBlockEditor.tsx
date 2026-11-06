// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { common, createLowlight } from "lowlight"
import {
  Suspense,
  lazy,
  useCallback,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactNode,
} from "react"
import { extractMarkdownSlice } from "./markdownAstPatcher"
import type { MarkdownBlockEditorCommonProps } from "./markdownBlockEditor"

const lowlight = createLowlight(common)
const LazyMermaidBlock = lazy(() => import("./MermaidBlock").then(module => ({ default: module.MermaidBlock })))

interface ParsedCodeFence {
  indent: string
  fence: string
  infoString: string
  language: string
  code: string
}

interface LowlightNode {
  type: string
  value?: string
  tagName?: string
  properties?: {
    className?: string[]
  }
  children?: LowlightNode[]
}

export function MarkdownCodeBlockEditor({
  markdown,
  contentVersion,
  block,
  theme,
  onApplyDocumentEdit,
  onClose,
}: MarkdownBlockEditorCommonProps) {
  const initialParsed = useMemo(() => parseCodeFenceSlice(block.initialMarkdown, block.language), [block.initialMarkdown, block.language])
  const [codeDraft, setCodeDraft] = useState(initialParsed.code)
  const [infoString, setInfoString] = useState(initialParsed.infoString)
  const [submitting, setSubmitting] = useState(false)
  const [conflictDetected, setConflictDetected] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | undefined>()

  const editorRootRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const submittingRef = useRef(false)
  const conflictRef = useRef(false)
  const initialMarkdownRef = useRef(block.initialMarkdown)

  conflictRef.current = conflictDetected

  const effectiveLanguage = useMemo(() => {
    const token = infoString.trim().split(/\s+/)[0] ?? ""
    return (token || initialParsed.language).toLowerCase()
  }, [infoString, initialParsed.language])

  const isMermaid = block.kind === "mermaid" || effectiveLanguage === "mermaid"

  useLayoutEffect(() => {
    const textarea = textareaRef.current
    if (!textarea) return
    textarea.focus()
    textarea.setSelectionRange(textarea.value.length, textarea.value.length)
  }, [])

  const serializeFence = useCallback((): string => {
    const normalizedCode = codeDraft.replace(/\r\n/g, "\n")
    const header = `${initialParsed.indent}${initialParsed.fence}${infoString}`
    const footer = `${initialParsed.indent}${initialParsed.fence}`
    return `${header}\n${normalizedCode}\n${footer}`
  }, [codeDraft, infoString, initialParsed.fence, initialParsed.indent])

  const applyChanges = useCallback(
    async (overrideVersion?: number): Promise<void> => {
      if (submittingRef.current) return
      const nextMarkdown = serializeFence()
      if (nextMarkdown === initialMarkdownRef.current && overrideVersion === undefined) {
        onClose()
        return
      }

      submittingRef.current = true
      setSubmitting(true)
      setErrorMessage(undefined)
      try {
        const result = await onApplyDocumentEdit({
          contentVersion: overrideVersion ?? contentVersion,
          range: block.range,
          newMarkdown: nextMarkdown,
          actionTitle: isMermaid ? "Edit Mermaid diagram" : "Edit code block",
        })
        if (result.applied) {
          initialMarkdownRef.current = nextMarkdown
          setConflictDetected(false)
          onClose()
          return
        }
        if (result.conflict) {
          setConflictDetected(true)
          setErrorMessage(result.errorMessage ?? "The document was modified externally while editing this block.")
          return
        }
        setErrorMessage(result.errorMessage ?? "Failed to apply code block changes.")
      }
      catch (error) {
        setErrorMessage(error instanceof Error ? error.message : "Failed to apply code block changes.")
      }
      finally {
        submittingRef.current = false
        setSubmitting(false)
      }
    },
    [block.range, contentVersion, isMermaid, onApplyDocumentEdit, onClose, serializeFence],
  )

  const highlightedNodes = useMemo(() => {
    return highlightCodeWithLowlight(codeDraft, effectiveLanguage)
  }, [codeDraft, effectiveLanguage])

  function handleKeyDown(event: ReactKeyboardEvent<HTMLTextAreaElement>): void {
    if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
      event.preventDefault()
      event.stopPropagation()
      void applyChanges()
      return
    }
    if (event.key === "Escape") {
      event.preventDefault()
      event.stopPropagation()
      onClose()
      return
    }
    if (event.key === "Tab" && !event.shiftKey && !event.metaKey && !event.ctrlKey && !event.altKey) {
      event.preventDefault()
      const textarea = event.currentTarget
      const start = textarea.selectionStart
      const end = textarea.selectionEnd
      const nextValue = codeDraft.slice(0, start) + "  " + codeDraft.slice(end)
      setCodeDraft(nextValue)
      requestAnimationFrame(() => {
        textarea.selectionStart = start + 2
        textarea.selectionEnd = start + 2
      })
    }
  }

  function handleReloadFromDocument(): void {
    const latestSlice = extractMarkdownSlice(markdown, block.range)
    initialMarkdownRef.current = latestSlice
    const reparsed = parseCodeFenceSlice(latestSlice, block.language)
    setCodeDraft(reparsed.code)
    setInfoString(reparsed.infoString)
    setConflictDetected(false)
    setErrorMessage(undefined)
  }

  return (
    <div
      ref={editorRootRef}
      className={`markdownBlockEditor is-code-editor${isMermaid ? " is-mermaid-editor" : ""}`}
      data-sourcepos={block.key}
      onDoubleClick={event => event.stopPropagation()}
    >
      <div className="markdownBlockEditorHeader">
        <div className="markdownCodeBlockEditorMeta">
          <span className="markdownCodeBlockEditorBadge">
            {isMermaid ? "Mermaid Diagram" : "Code Block"}
          </span>
          <input
            type="text"
            className="markdownCodeBlockLanguageInput"
            aria-label="Code block language"
            placeholder="language"
            value={infoString}
            disabled={submitting}
            onChange={event => setInfoString(event.currentTarget.value)}
          />
        </div>
        <div className="markdownBlockEditorActions">
          <button
            type="button"
            className="markdownBlockEditorButton is-primary"
            aria-label="Apply changes"
            title="Apply changes (Ctrl/Cmd+Enter)"
            disabled={submitting}
            onMouseDown={event => event.preventDefault()}
            onClick={() => void applyChanges()}
          >
            Apply
          </button>
          <button
            type="button"
            className="markdownBlockEditorButton"
            aria-label="Cancel editing"
            title="Cancel editing (Escape)"
            disabled={submitting}
            onMouseDown={event => event.preventDefault()}
            onClick={onClose}
          >
            Cancel
          </button>
        </div>
      </div>

      {conflictDetected && (
        <div className="markdownBlockEditorConflictBanner" role="alert">
          <span className="markdownBlockEditorConflictMessage">
            {errorMessage ?? "Document changed externally. Choose how to resolve:"}
          </span>
          <div className="markdownBlockEditorConflictActions">
            <button
              type="button"
              className="markdownBlockEditorButton is-primary"
              onMouseDown={event => event.preventDefault()}
              onClick={() => void applyChanges(contentVersion)}
            >
              Overwrite
            </button>
            <button
              type="button"
              className="markdownBlockEditorButton"
              onMouseDown={event => event.preventDefault()}
              onClick={handleReloadFromDocument}
            >
              Reload block
            </button>
          </div>
        </div>
      )}

      {!conflictDetected && errorMessage && (
        <div className="markdownBlockEditorErrorBanner" role="alert">
          {errorMessage}
        </div>
      )}

      <div className={`markdownCodeBlockEditorLayout${isMermaid ? " is-split" : ""}`}>
        <div className="markdownCodeBlockInputContainer">
          <pre className="markdownCodeBlockHighlightBackdrop" aria-hidden="true">
            <code className="hljs">{highlightedNodes}{"\n"}</code>
          </pre>
          <textarea
            ref={textareaRef}
            className="markdownCodeBlockEditorTextarea"
            aria-label={isMermaid ? "Edit Mermaid diagram code" : "Edit code block"}
            rows={Math.max(4, codeDraft.split("\n").length + 1)}
            spellCheck={false}
            value={codeDraft}
            disabled={submitting}
            onChange={event => {
              setCodeDraft(event.currentTarget.value)
              setErrorMessage(undefined)
            }}
            onKeyDown={handleKeyDown}
          />
        </div>
        {isMermaid && (
          <div className="markdownCodeBlockLivePreview" aria-label="Live Mermaid diagram preview">
            <div className="markdownCodeBlockLivePreviewTitle">Live Preview</div>
            <Suspense fallback={<div className="mermaidBlock isRendering">Rendering diagram...</div>}>
              <LazyMermaidBlock chart={codeDraft} theme={theme} />
            </Suspense>
          </div>
        )}
      </div>
    </div>
  )
}

function parseCodeFenceSlice(rawSlice: string, fallbackLanguage?: string): ParsedCodeFence {
  const normalized = rawSlice.replace(/\r\n/g, "\n")
  const lines = normalized.split("\n")
  const firstLine = lines[0] ?? ""
  const openMatch = firstLine.match(/^(\s*)(`{3,}|~{3,})(.*)$/)
  if (!openMatch) {
    return {
      indent: "",
      fence: "```",
      infoString: fallbackLanguage ?? "",
      language: fallbackLanguage ?? "",
      code: normalized,
    }
  }

  const indent = openMatch[1]
  const fence = openMatch[2]
  const infoString = openMatch[3].trim()
  const language = infoString.split(/\s+/)[0] || fallbackLanguage || ""

  let endLineIndex = lines.length
  if (lines.length > 1) {
    const lastLine = lines[lines.length - 1] ?? ""
    const closePattern = new RegExp(`^\\s*${fence[0]}{${fence.length},}\\s*$`)
    if (closePattern.test(lastLine)) {
      endLineIndex = lines.length - 1
    }
  }

  const code = lines.slice(1, endLineIndex).join("\n")
  return {
    indent,
    fence,
    infoString,
    language,
    code,
  }
}

function highlightCodeWithLowlight(code: string, language: string): ReactNode {
  try {
    const normalizedLang = language.trim().toLowerCase()
    const tree = normalizedLang && lowlight.registered(normalizedLang)
      ? lowlight.highlight(normalizedLang, code)
      : lowlight.highlightAuto(code)
    return renderLowlightChildren((tree as unknown as LowlightNode).children ?? [], "hl")
  }
  catch {
    return code
  }
}

function renderLowlightChildren(nodes: readonly LowlightNode[], keyPrefix: string): ReactNode[] {
  return nodes.map((node, index) => {
    const key = `${keyPrefix}-${index}`
    if (node.type === "text") {
      return node.value ?? ""
    }
    if (node.type === "element") {
      const Tag = (node.tagName || "span") as "span"
      const className = node.properties?.className?.join(" ")
      return (
        <Tag key={key} className={className}>
          {renderLowlightChildren(node.children ?? [], key)}
        </Tag>
      )
    }
    return null
  })
}
