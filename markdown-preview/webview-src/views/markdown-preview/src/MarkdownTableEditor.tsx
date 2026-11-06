// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
} from "react"
import {
  extractMarkdownSlice,
  parseGfmTableSlice,
  serializeGfmTable,
  type EditableGfmTable,
  type TableColumnAlignment,
} from "./markdownAstPatcher"
import type { MarkdownBlockEditorCommonProps } from "./markdownBlockEditor"

const ALIGNMENT_CYCLE: readonly TableColumnAlignment[] = [null, "left", "center", "right"]

export function MarkdownTableEditor({
  markdown,
  contentVersion,
  block,
  onApplyDocumentEdit,
  onClose,
}: MarkdownBlockEditorCommonProps) {
  const initialParsed = parseGfmTableSlice(block.initialMarkdown) ?? {
    headers: ["Column 1", "Column 2"],
    alignments: [null, null],
    rows: [["", ""]],
  }

  const [mode, setMode] = useState<"grid" | "raw">("grid")
  const [table, setTable] = useState<EditableGfmTable>(initialParsed)
  const [rawMarkdown, setRawMarkdown] = useState(block.initialMarkdown)
  const [activeCell, setActiveCell] = useState<{ row: number, col: number }>({ row: 0, col: 0 })
  const [submitting, setSubmitting] = useState(false)
  const [conflictDetected, setConflictDetected] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | undefined>()

  const editorRootRef = useRef<HTMLDivElement>(null)
  const cellInputsRef = useRef<Map<string, HTMLInputElement>>(new Map())
  const pendingFocusCellRef = useRef<{ row: number, col: number } | undefined>({ row: 0, col: 0 })
  const submittingRef = useRef(false)
  const conflictRef = useRef(false)
  const initialMarkdownRef = useRef(block.initialMarkdown)

  conflictRef.current = conflictDetected

  useEffect(() => {
    const target = pendingFocusCellRef.current
    if (!target || mode !== "grid") return
    pendingFocusCellRef.current = undefined
    const input = cellInputsRef.current.get(cellKey(target.row, target.col))
    if (input) {
      input.focus()
      input.select()
    }
  })

  const currentSerializedMarkdown = useCallback((): string => {
    return mode === "raw" ? rawMarkdown : serializeGfmTable(table)
  }, [mode, rawMarkdown, table])

  const applyChanges = useCallback(
    async (overrideVersion?: number): Promise<void> => {
      if (submittingRef.current) return
      const nextMarkdown = currentSerializedMarkdown()
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
          actionTitle: "Edit Markdown table",
        })
        if (result.applied) {
          initialMarkdownRef.current = nextMarkdown
          setConflictDetected(false)
          onClose()
          return
        }
        if (result.conflict) {
          setConflictDetected(true)
          setErrorMessage(result.errorMessage ?? "The document was modified externally while editing this table.")
          return
        }
        setErrorMessage(result.errorMessage ?? "Failed to apply table changes.")
      }
      catch (error) {
        setErrorMessage(error instanceof Error ? error.message : "Failed to apply table changes.")
      }
      finally {
        submittingRef.current = false
        setSubmitting(false)
      }
    },
    [block.range, contentVersion, currentSerializedMarkdown, onApplyDocumentEdit, onClose],
  )

  useEffect(() => {
    const handleOutsideMouseDown = (event: MouseEvent): void => {
      const target = event.target
      if (!(target instanceof Node)) return
      if (editorRootRef.current?.contains(target)) return
      if (submittingRef.current || conflictRef.current) return

      const nextMarkdown = currentSerializedMarkdown()
      if (nextMarkdown !== initialMarkdownRef.current) {
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
  }, [applyChanges, currentSerializedMarkdown, onClose])

  function updateHeader(colIndex: number, value: string): void {
    setTable(current => {
      const headers = [...current.headers]
      headers[colIndex] = value
      return { ...current, headers }
    })
  }

  function updateCell(rowIndex: number, colIndex: number, value: string): void {
    setTable(current => {
      const rows = current.rows.map((row, idx) => {
        if (idx !== rowIndex) return row
        const nextRow = [...row]
        nextRow[colIndex] = value
        return nextRow
      })
      return { ...current, rows }
    })
  }

  function cycleColumnAlignment(colIndex: number): void {
    setTable(current => {
      const alignments = [...current.alignments]
      const currentAlign = alignments[colIndex] ?? null
      const nextIndex = (ALIGNMENT_CYCLE.indexOf(currentAlign) + 1) % ALIGNMENT_CYCLE.length
      alignments[colIndex] = ALIGNMENT_CYCLE[nextIndex]
      return { ...current, alignments }
    })
  }

  function addRow(afterBodyRowIndex?: number): void {
    setTable(current => {
      const colCount = current.headers.length
      const emptyRow = Array.from({ length: colCount }, () => "")
      const insertIndex = afterBodyRowIndex !== undefined
        ? Math.min(current.rows.length, Math.max(0, afterBodyRowIndex + 1))
        : current.rows.length
      const rows = [
        ...current.rows.slice(0, insertIndex),
        emptyRow,
        ...current.rows.slice(insertIndex),
      ]
      pendingFocusCellRef.current = { row: insertIndex + 1, col: 0 }
      setActiveCell({ row: insertIndex + 1, col: 0 })
      return { ...current, rows }
    })
  }

  function removeRow(): void {
    setTable(current => {
      if (current.rows.length === 0) return current
      const targetBodyIndex = activeCell.row > 0
        ? Math.min(current.rows.length - 1, activeCell.row - 1)
        : current.rows.length - 1
      const rows = current.rows.filter((_, idx) => idx !== targetBodyIndex)
      const nextRow = Math.min(activeCell.row, rows.length)
      pendingFocusCellRef.current = { row: nextRow, col: activeCell.col }
      setActiveCell({ row: nextRow, col: activeCell.col })
      return { ...current, rows }
    })
  }

  function addColumn(): void {
    setTable(current => {
      const insertCol = Math.min(current.headers.length, Math.max(0, activeCell.col + 1))
      const headers = [
        ...current.headers.slice(0, insertCol),
        `Column ${current.headers.length + 1}`,
        ...current.headers.slice(insertCol),
      ]
      const alignments = [
        ...current.alignments.slice(0, insertCol),
        null,
        ...current.alignments.slice(insertCol),
      ]
      const rows = current.rows.map(row => [
        ...row.slice(0, insertCol),
        "",
        ...row.slice(insertCol),
      ])
      pendingFocusCellRef.current = { row: activeCell.row, col: insertCol }
      setActiveCell({ row: activeCell.row, col: insertCol })
      return { headers, alignments, rows }
    })
  }

  function removeColumn(): void {
    setTable(current => {
      if (current.headers.length <= 1) return current
      const removeCol = Math.min(current.headers.length - 1, Math.max(0, activeCell.col))
      const headers = current.headers.filter((_, idx) => idx !== removeCol)
      const alignments = current.alignments.filter((_, idx) => idx !== removeCol)
      const rows = current.rows.map(row => row.filter((_, idx) => idx !== removeCol))
      const nextCol = Math.min(removeCol, headers.length - 1)
      pendingFocusCellRef.current = { row: activeCell.row, col: nextCol }
      setActiveCell({ row: activeCell.row, col: nextCol })
      return { headers, alignments, rows }
    })
  }

  function toggleMode(): void {
    if (mode === "grid") {
      setRawMarkdown(serializeGfmTable(table))
      setMode("raw")
    }
    else {
      const parsed = parseGfmTableSlice(rawMarkdown)
      if (parsed) {
        setTable(parsed)
        setErrorMessage(undefined)
        setMode("grid")
      }
      else {
        setErrorMessage("Raw Markdown is not a valid GFM table.")
      }
    }
  }

  function handleCellKeyDown(
    event: ReactKeyboardEvent<HTMLInputElement>,
    gridRow: number,
    gridCol: number,
  ): void {
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
    if (event.key === "Tab") {
      event.preventDefault()
      const totalRows = 1 + table.rows.length
      const totalCols = table.headers.length
      const flatIndex = gridRow * totalCols + gridCol + (event.shiftKey ? -1 : 1)
      if (flatIndex < 0) return
      if (flatIndex >= totalRows * totalCols) {
        addRow()
        return
      }
      const nextRow = Math.floor(flatIndex / totalCols)
      const nextCol = flatIndex % totalCols
      setActiveCell({ row: nextRow, col: nextCol })
      const input = cellInputsRef.current.get(cellKey(nextRow, nextCol))
      input?.focus()
      input?.select()
    }
  }

  function handleReloadFromDocument(): void {
    const latestSlice = extractMarkdownSlice(markdown, block.range)
    initialMarkdownRef.current = latestSlice
    setRawMarkdown(latestSlice)
    const reparsed = parseGfmTableSlice(latestSlice)
    if (reparsed) setTable(reparsed)
    setConflictDetected(false)
    setErrorMessage(undefined)
  }

  return (
    <div
      ref={editorRootRef}
      className="markdownBlockEditor is-table-editor"
      data-sourcepos={block.key}
      onDoubleClick={event => event.stopPropagation()}
    >
      <div className="markdownBlockEditorHeader">
        <div className="markdownTableEditorToolbar" role="toolbar" aria-label="Table editing controls">
          {mode === "grid" && (
            <>
              <button
                type="button"
                className="markdownBlockEditorButton"
                aria-label="Add table row"
                disabled={submitting}
                onMouseDown={event => event.preventDefault()}
                onClick={() => addRow(activeCell.row > 0 ? activeCell.row - 1 : undefined)}
              >
                + Row
              </button>
              <button
                type="button"
                className="markdownBlockEditorButton"
                aria-label="Remove table row"
                disabled={submitting || table.rows.length === 0}
                onMouseDown={event => event.preventDefault()}
                onClick={removeRow}
              >
                - Row
              </button>
              <button
                type="button"
                className="markdownBlockEditorButton"
                aria-label="Add table column"
                disabled={submitting}
                onMouseDown={event => event.preventDefault()}
                onClick={addColumn}
              >
                + Column
              </button>
              <button
                type="button"
                className="markdownBlockEditorButton"
                aria-label="Remove table column"
                disabled={submitting || table.headers.length <= 1}
                onMouseDown={event => event.preventDefault()}
                onClick={removeColumn}
              >
                - Column
              </button>
            </>
          )}
          <button
            type="button"
            className="markdownBlockEditorButton"
            aria-label={mode === "grid" ? "Switch to raw Markdown" : "Switch to table grid"}
            disabled={submitting}
            onMouseDown={event => event.preventDefault()}
            onClick={toggleMode}
          >
            {mode === "grid" ? "Raw Markdown" : "Visual Grid"}
          </button>
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

      <div className="markdownBlockEditorBody">
        {mode === "grid" ? (
          <div className="markdownTableEditorGridWrapper">
            <table className="markdownTableEditorGrid">
              <thead>
                <tr className="markdownTableEditorAlignRow">
                  {table.headers.map((_, colIndex) => {
                    const align = table.alignments[colIndex] ?? null
                    return (
                      <th key={`align-${colIndex}`}>
                        <button
                          type="button"
                          className="markdownTableEditorAlignButton"
                          aria-label={`Column ${colIndex + 1} alignment: ${align ?? "auto"}`}
                          title={`Toggle column ${colIndex + 1} alignment (current: ${align ?? "auto"})`}
                          disabled={submitting}
                          onMouseDown={event => event.preventDefault()}
                          onClick={() => cycleColumnAlignment(colIndex)}
                        >
                          Align: {alignmentLabel(align)}
                        </button>
                      </th>
                    )
                  })}
                </tr>
                <tr>
                  {table.headers.map((headerValue, colIndex) => (
                    <th key={`header-${colIndex}`}>
                      <input
                        ref={element => {
                          const key = cellKey(0, colIndex)
                          if (element) cellInputsRef.current.set(key, element)
                          else cellInputsRef.current.delete(key)
                        }}
                        type="text"
                        className="markdownTableEditorCellInput is-header"
                        aria-label={`Header ${colIndex + 1}`}
                        style={{ textAlign: table.alignments[colIndex] ?? "left" }}
                        value={headerValue}
                        disabled={submitting}
                        onFocus={() => setActiveCell({ row: 0, col: colIndex })}
                        onChange={event => updateHeader(colIndex, event.currentTarget.value)}
                        onKeyDown={event => handleCellKeyDown(event, 0, colIndex)}
                      />
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {table.rows.map((row, rowIndex) => (
                  <tr key={`row-${rowIndex}`}>
                    {row.map((cellValue, colIndex) => (
                      <td key={`cell-${rowIndex}-${colIndex}`}>
                        <input
                          ref={element => {
                            const key = cellKey(rowIndex + 1, colIndex)
                            if (element) cellInputsRef.current.set(key, element)
                            else cellInputsRef.current.delete(key)
                          }}
                          type="text"
                          className="markdownTableEditorCellInput"
                          aria-label={`Row ${rowIndex + 1} Column ${colIndex + 1}`}
                          style={{ textAlign: table.alignments[colIndex] ?? "left" }}
                          value={cellValue}
                          disabled={submitting}
                          onFocus={() => setActiveCell({ row: rowIndex + 1, col: colIndex })}
                          onChange={event => updateCell(rowIndex, colIndex, event.currentTarget.value)}
                          onKeyDown={event => handleCellKeyDown(event, rowIndex + 1, colIndex)}
                        />
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <textarea
            className="markdownBlockEditorTextarea"
            aria-label="Edit raw Markdown table"
            rows={Math.max(4, rawMarkdown.split("\n").length + 1)}
            value={rawMarkdown}
            disabled={submitting}
            onChange={event => {
              setRawMarkdown(event.currentTarget.value)
              setErrorMessage(undefined)
            }}
            onKeyDown={event => {
              if ((event.metaKey || event.ctrlKey) && event.key === "Enter") {
                event.preventDefault()
                void applyChanges()
              }
              else if (event.key === "Escape") {
                event.preventDefault()
                onClose()
              }
            }}
          />
        )}
      </div>
    </div>
  )
}

function cellKey(gridRow: number, gridCol: number): string {
  return `${gridRow}:${gridCol}`
}

function alignmentLabel(align: TableColumnAlignment): string {
  switch (align) {
    case "left":
      return "← Left"
    case "center":
      return "↔ Center"
    case "right":
      return "→ Right"
    default:
      return "Auto"
  }
}
