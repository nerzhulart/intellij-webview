// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ClipboardEvent as ReactClipboardEvent,
  type DragEvent as ReactDragEvent,
  type FocusEvent as ReactFocusEvent,
  type KeyboardEvent as ReactKeyboardEvent,
  type ReactNode,
} from "react"
import { MarkdownCodeBlockEditor } from "./MarkdownCodeBlockEditor"
import {
  MarkdownFormattingToolbar,
  applyMarkdownFormattingCommand,
  type MarkdownFormattingCommand,
} from "./MarkdownFormattingToolbar"
import { MarkdownFileLinkAutocomplete } from "./MarkdownFileLinkAutocomplete"
import { MarkdownTableEditor } from "./MarkdownTableEditor"
import { extractMarkdownSlice } from "./markdownAstPatcher"
import { handleMarkdownEditorDrop, handleMarkdownEditorPaste } from "./markdownMediaDropAndPaste"
import type {
  MarkdownApplyDocumentEditParams,
  MarkdownApplyDocumentEditResultParams,
  MarkdownSaveImageAssetParams,
  MarkdownSaveImageAssetResultParams,
  MarkdownSuggestFileLinksParams,
  MarkdownSuggestedFileLinksParams,
} from "./markdownPreviewTypes"
import { parseSourcePosition, positionKey, type SourcePositionRange } from "./markdownSourcePositions"

export type EditableMarkdownBlockKind = "text" | "table" | "code" | "mermaid"

export interface ActiveMarkdownEditBlock {
  key: string
  kind: EditableMarkdownBlockKind
  range: SourcePositionRange
  initialMarkdown: string
  language?: string
}

export interface MarkdownBlockEditorCommonProps {
  markdown: string
  contentVersion: number
  block: ActiveMarkdownEditBlock
  theme: "light" | "dark"
  onApplyDocumentEdit: (params: MarkdownApplyDocumentEditParams) => Promise<MarkdownApplyDocumentEditResultParams>
  onSaveImageAsset?: (params: MarkdownSaveImageAssetParams) => Promise<MarkdownSaveImageAssetResultParams>
  onSuggestFileLinks?: (params: MarkdownSuggestFileLinksParams) => Promise<MarkdownSuggestedFileLinksParams>
  onClose: () => void
}

export interface MarkdownTextEditorEnhancements {
  onPaste?: (
    event: ReactClipboardEvent<HTMLTextAreaElement>,
    context: MarkdownTextareaController,
  ) => boolean | Promise<boolean>
  onDrop?: (
    event: ReactDragEvent<HTMLTextAreaElement>,
    context: MarkdownTextareaController,
  ) => boolean | Promise<boolean>
  renderOverlay?: (context: MarkdownTextareaController) => ReactNode
}

export interface MarkdownTextareaController {
  value: string
  selectionStart: number
  selectionEnd: number
  textarea: HTMLTextAreaElement | null
  replaceRange: (start: number, end: number, replacement: string, selectReplacement?: boolean) => void
  insertAtCursor: (text: string) => void
}

export const EditableBlockContainerContext = createContext(false)

export function useIsInsideEditableBlockContainer(): boolean {
  return useContext(EditableBlockContainerContext)
}

export function createActiveEditBlock(
  markdown: string,
  range: SourcePositionRange,
  kind: EditableMarkdownBlockKind,
  language?: string,
): ActiveMarkdownEditBlock {
  return {
    key: positionKey(range),
    kind,
    range,
    initialMarkdown: extractMarkdownSlice(markdown, range),
    language,
  }
}

export function MarkdownBlockEditor(props: MarkdownBlockEditorProps) {
  if (props.block.kind === "table") {
    return <MarkdownTableEditor {...props} />
  }
  if (props.block.kind === "code" || props.block.kind === "mermaid") {
    return <MarkdownCodeBlockEditor {...props} />
  }
  const defaultEnhancements: MarkdownTextEditorEnhancements = props.enhancements ?? {
    onPaste: (event, controller) => handleMarkdownEditorPaste(event, controller, props.onSaveImageAsset),
    onDrop: (event, controller) => handleMarkdownEditorDrop(event, controller, props.onSaveImageAsset),
    renderOverlay: controller => (
      <MarkdownFileLinkAutocomplete
        controller={controller}
        onSuggestFileLinks={props.onSuggestFileLinks}
      />
    ),
  }
  return <MarkdownTextBlockEditor {...props} enhancements={defaultEnhancements} />
}

export interface MarkdownBlockEditorProps extends MarkdownBlockEditorCommonProps {
  enhancements?: MarkdownTextEditorEnhancements
}

interface MarkdownTextBlockEditorProps extends MarkdownBlockEditorCommonProps {
  enhancements?: MarkdownTextEditorEnhancements
}

export function MarkdownTextBlockEditor({
  markdown,
  contentVersion,
  block,
  onApplyDocumentEdit,
  onClose,
  enhancements,
}: MarkdownTextBlockEditorProps) {
  const [draft, setDraft] = useState(block.initialMarkdown)
  const [selectionState, setSelectionState] = useState<{ start: number, end: number }>({
    start: block.initialMarkdown.length,
    end: block.initialMarkdown.length,
  })
  const [submitting, setSubmitting] = useState(false)
  const [conflictDetected, setConflictDetected] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | undefined>()
  const [isDragOver, setIsDragOver] = useState(false)

  const editorRootRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const draftRef = useRef(draft)
  const initialMarkdownRef = useRef(block.initialMarkdown)
  const submittingRef = useRef(false)
  const conflictRef = useRef(false)
  const pendingSelectionRef = useRef<{ start: number, end: number } | undefined>()

  draftRef.current = draft
  conflictRef.current = conflictDetected

  useLayoutEffect(() => {
    const textarea = textareaRef.current
    if (!textarea) return
    textarea.focus()
    const length = textarea.value.length
    textarea.setSelectionRange(length, length)
    adjustTextareaHeight(textarea)
  }, [])

  useLayoutEffect(() => {
    const textarea = textareaRef.current
    if (!textarea) return
    adjustTextareaHeight(textarea)
    const pendingSelection = pendingSelectionRef.current
    if (pendingSelection) {
      pendingSelectionRef.current = undefined
      textarea.focus()
      textarea.setSelectionRange(pendingSelection.start, pendingSelection.end)
      setSelectionState(pendingSelection)
    }
  }, [draft])

  const replaceRange = useCallback(
    (start: number, end: number, replacement: string, selectReplacement = false) => {
      const current = draftRef.current
      const clampedStart = Math.max(0, Math.min(start, current.length))
      const clampedEnd = Math.max(clampedStart, Math.min(end, current.length))
      const nextValue = current.slice(0, clampedStart) + replacement + current.slice(clampedEnd)
      const nextCaretEnd = clampedStart + replacement.length
      const nextCaretStart = selectReplacement ? clampedStart : nextCaretEnd
      pendingSelectionRef.current = { start: nextCaretStart, end: nextCaretEnd }
      setDraft(nextValue)
      setErrorMessage(undefined)
    },
    [],
  )

  const insertAtCursor = useCallback(
    (text: string) => {
      const textarea = textareaRef.current
      const start = textarea ? textarea.selectionStart : draftRef.current.length
      const end = textarea ? textarea.selectionEnd : start
      replaceRange(start, end, text, false)
    },
    [replaceRange],
  )

  const applyChanges = useCallback(
    async (overrideVersion?: number): Promise<void> => {
      if (submittingRef.current) return
      const nextMarkdown = draftRef.current
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
          actionTitle: "Edit Markdown block",
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
        setErrorMessage(result.errorMessage ?? "Failed to apply changes to document.")
      }
      catch (error) {
        setErrorMessage(error instanceof Error ? error.message : "Failed to apply changes to document.")
      }
      finally {
        submittingRef.current = false
        setSubmitting(false)
      }
    },
    [block.range, contentVersion, onApplyDocumentEdit, onClose],
  )

  useEffect(() => {
    const handleOutsideMouseDown = (event: MouseEvent): void => {
      const target = event.target
      if (!(target instanceof Node)) return
      if (editorRootRef.current?.contains(target)) return
      if (submittingRef.current || conflictRef.current) return

      if (draftRef.current !== initialMarkdownRef.current) {
        void applyChanges()
      }
      else {
        onClose()
      }
    }

    document.addEventListener("mousedown", handleOutsideMouseDown, true)
    return () => {
      document.removeEventListener("mousedown", handleOutsideMouseDown, true)
    }
  }, [applyChanges, onClose])

  function handleCommand(command: MarkdownFormattingCommand): void {
    const textarea = textareaRef.current
    const start = textarea ? textarea.selectionStart : selectionState.start
    const end = textarea ? textarea.selectionEnd : selectionState.end
    const patch = applyMarkdownFormattingCommand(draftRef.current, start, end, command)
    pendingSelectionRef.current = { start: patch.selectionStart, end: patch.selectionEnd }
    setDraft(patch.value)
    setErrorMessage(undefined)
  }

  function handleKeyDown(event: ReactKeyboardEvent<HTMLTextAreaElement>): void {
    if (event.defaultPrevented) return

    const isMod = event.metaKey || event.ctrlKey
    if (isMod && event.key === "Enter") {
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

    if (isMod && !event.altKey) {
      const lowerKey = event.key.toLowerCase()
      if (lowerKey === "b" && !event.shiftKey) {
        event.preventDefault()
        handleCommand("bold")
        return
      }
      if (lowerKey === "i" && !event.shiftKey) {
        event.preventDefault()
        handleCommand("italic")
        return
      }
      if (lowerKey === "k" && !event.shiftKey) {
        event.preventDefault()
        handleCommand("link")
        return
      }
      if (lowerKey === "e" && !event.shiftKey) {
        event.preventDefault()
        handleCommand("code")
        return
      }
      if (lowerKey === "x" && event.shiftKey) {
        event.preventDefault()
        handleCommand("strikethrough")
        return
      }
    }
  }

  function handleContainerBlur(event: ReactFocusEvent<HTMLDivElement>): void {
    const nextFocused = event.relatedTarget
    if (!nextFocused || !(nextFocused instanceof Node)) return
    if (editorRootRef.current?.contains(nextFocused)) return
    if (submittingRef.current || conflictRef.current) return

    if (draftRef.current !== initialMarkdownRef.current) {
      void applyChanges()
    }
    else {
      onClose()
    }
  }

  function handleReloadFromDocument(): void {
    const latestSlice = extractMarkdownSlice(markdown, block.range)
    initialMarkdownRef.current = latestSlice
    setDraft(latestSlice)
    setConflictDetected(false)
    setErrorMessage(undefined)
  }

  const controller: MarkdownTextareaController = {
    value: draft,
    selectionStart: selectionState.start,
    selectionEnd: selectionState.end,
    textarea: textareaRef.current,
    replaceRange,
    insertAtCursor,
  }

  return (
    <div
      ref={editorRootRef}
      className={`markdownBlockEditor is-text-editor${isDragOver ? " is-drag-over" : ""}`}
      data-sourcepos={block.key}
      onBlur={handleContainerBlur}
      onDoubleClick={event => event.stopPropagation()}
    >
      <div className="markdownBlockEditorHeader">
        <MarkdownFormattingToolbar disabled={submitting} onCommand={handleCommand} />
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

      <div className="markdownBlockEditorBody">
        <textarea
          ref={textareaRef}
          className="markdownBlockEditorTextarea"
          aria-label="Edit Markdown block"
          value={draft}
          disabled={submitting}
          onChange={event => {
            const target = event.currentTarget
            setDraft(target.value)
            setSelectionState({ start: target.selectionStart, end: target.selectionEnd })
            setErrorMessage(undefined)
          }}
          onSelect={event => {
            const target = event.currentTarget
            setSelectionState({ start: target.selectionStart, end: target.selectionEnd })
          }}
          onKeyDown={handleKeyDown}
          onPaste={event => {
            if (enhancements?.onPaste) {
              void enhancements.onPaste(event, controller)
            }
          }}
          onDragOver={event => {
            if (event.dataTransfer?.types?.includes("Files")) {
              event.preventDefault()
              setIsDragOver(true)
            }
          }}
          onDragLeave={() => setIsDragOver(false)}
          onDrop={event => {
            setIsDragOver(false)
            if (enhancements?.onDrop) {
              void enhancements.onDrop(event, controller)
            }
          }}
        />
        {enhancements?.renderOverlay?.(controller)}
      </div>
    </div>
  )
}

interface HoveredEditableBlockTarget {
  key: string
  kind: EditableMarkdownBlockKind
  range: SourcePositionRange
  language?: string
  top: number
  left: number
}

interface MarkdownHoveredBlockEditTriggerProps {
  enabled: boolean
  activeBlockKey: string | undefined
  onStartEdit: (range: SourcePositionRange, kind: EditableMarkdownBlockKind, language?: string) => void
}

/**
 * Renders a lightweight floating "Edit block" button outside `.markdownPreviewContent` when hovering over an editable
 * block, and opens the block editor on double-click inside `.markdownPreviewContent`.
 * Keeping this trigger outside `.markdownPreviewContent` preserves the exact DOM structure and `textContent` of all
 * rendered Markdown blocks.
 */
export function MarkdownHoveredBlockEditTrigger({
  enabled,
  activeBlockKey,
  onStartEdit,
}: MarkdownHoveredBlockEditTriggerProps) {
  const [hovered, setHovered] = useState<HoveredEditableBlockTarget | undefined>()
  const hoveredElementRef = useRef<HTMLElement | null>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!enabled || activeBlockKey !== undefined) {
      hoveredElementRef.current = null
      setHovered(undefined)
      return
    }

    const updateFromElement = (element: HTMLElement | null): void => {
      hoveredElementRef.current = element
      if (!element) {
        setHovered(undefined)
        return
      }
      const range = parseSourcePosition(element.dataset.sourcepos)
      const kind = (element.dataset.editableBlock as EditableMarkdownBlockKind | undefined) ?? "text"
      const language = element.dataset.editableLanguage
      if (!range) {
        setHovered(undefined)
        return
      }
      const rect = element.getBoundingClientRect()
      if (rect.width <= 0 && rect.height <= 0) {
        setHovered(undefined)
        return
      }
      const top = Math.max(4, Math.round(rect.top + window.scrollY + 4))
      const left = Math.max(4, Math.round(rect.right + window.scrollX - 30))
      setHovered({
        key: positionKey(range),
        kind,
        range,
        language,
        top,
        left,
      })
    }

    const handleMouseMove = (event: MouseEvent): void => {
      const target = event.target
      if (!(target instanceof Node)) return
      if (triggerRef.current?.contains(target)) return

      const previewContent = document.querySelector<HTMLElement>(".markdownPreviewContent")
      if (!previewContent || !previewContent.contains(target)) {
        updateFromElement(null)
        return
      }

      const element = target instanceof HTMLElement ? target : target.parentElement
      if (!element || element.closest(".markdownBlockEditor")) {
        updateFromElement(null)
        return
      }

      const editableBlock = findOutermostEditableBlock(element, previewContent)
      updateFromElement(editableBlock)
    }

    const handleDoubleClick = (event: MouseEvent): void => {
      const target = event.target
      if (!(target instanceof Node)) return
      const previewContent = document.querySelector<HTMLElement>(".markdownPreviewContent")
      if (!previewContent || !previewContent.contains(target)) return

      const element = target instanceof HTMLElement ? target : target.parentElement
      if (!element || isInteractivePreviewElement(element)) return

      const editableBlock = findOutermostEditableBlock(element, previewContent)
      if (!editableBlock) return

      const range = parseSourcePosition(editableBlock.dataset.sourcepos)
      if (!range) return
      const kind = (editableBlock.dataset.editableBlock as EditableMarkdownBlockKind | undefined) ?? "text"
      const language = editableBlock.dataset.editableLanguage

      event.preventDefault()
      event.stopPropagation()
      window.getSelection()?.removeAllRanges()
      onStartEdit(range, kind, language)
    }

    const handleScrollOrResize = (): void => {
      if (hoveredElementRef.current) {
        if (!document.body.contains(hoveredElementRef.current)) {
          updateFromElement(null)
        }
        else {
          updateFromElement(hoveredElementRef.current)
        }
      }
    }

    document.addEventListener("mousemove", handleMouseMove, { passive: true })
    document.addEventListener("dblclick", handleDoubleClick)
    window.addEventListener("scroll", handleScrollOrResize, { passive: true })
    window.addEventListener("resize", handleScrollOrResize)
    return () => {
      document.removeEventListener("mousemove", handleMouseMove)
      document.removeEventListener("dblclick", handleDoubleClick)
      window.removeEventListener("scroll", handleScrollOrResize)
      window.removeEventListener("resize", handleScrollOrResize)
    }
  }, [activeBlockKey, enabled, onStartEdit])

  if (!enabled || activeBlockKey !== undefined || !hovered) return null

  return (
    <button
      ref={triggerRef}
      type="button"
      className="markdownBlockEditTrigger"
      aria-label="Edit block"
      title="Edit block (or double-click block)"
      data-target-sourcepos={hovered.key}
      style={{
        top: `${hovered.top}px`,
        left: `${hovered.left}px`,
      }}
      onClick={event => {
        event.preventDefault()
        event.stopPropagation()
        window.getSelection()?.removeAllRanges()
        onStartEdit(hovered.range, hovered.kind, hovered.language)
      }}
    >
      ✎
    </button>
  )
}

function isInteractivePreviewElement(element: HTMLElement): boolean {
  return (
    element.closest(
      "a, button, input, textarea, select, summary, .markdownZoomToolbar, .markdownRunButton, .markdownPathLink, .markdownBlockEditor, .mermaidViewport",
    ) != null
  )
}

function findOutermostEditableBlock(element: HTMLElement, previewContent: HTMLElement): HTMLElement | null {
  let current = element.closest<HTMLElement>("[data-editable-block][data-sourcepos]")
  if (!current || !previewContent.contains(current)) return null

  while (current) {
    const parentEditable = current.parentElement?.closest<HTMLElement>("[data-editable-block][data-sourcepos]")
    if (parentEditable && previewContent.contains(parentEditable)) {
      current = parentEditable
    }
    else {
      break
    }
  }
  return current
}

function adjustTextareaHeight(textarea: HTMLTextAreaElement): void {
  textarea.style.height = "auto"
  const nextHeight = Math.max(76, Math.min(520, textarea.scrollHeight + 2))
  textarea.style.height = `${nextHeight}px`
}
