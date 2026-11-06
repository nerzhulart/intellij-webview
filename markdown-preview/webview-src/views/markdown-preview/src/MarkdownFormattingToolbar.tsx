// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

export type MarkdownFormattingCommand =
  | "bold"
  | "italic"
  | "strikethrough"
  | "code"
  | "heading"
  | "quote"
  | "unordered-list"
  | "ordered-list"
  | "task-list"
  | "link"

export interface TextSelectionPatch {
  value: string
  selectionStart: number
  selectionEnd: number
}

interface FormattingToolbarProps {
  disabled?: boolean
  onCommand: (command: MarkdownFormattingCommand) => void
}

interface ToolbarItemDescriptor {
  command: MarkdownFormattingCommand
  label: string
  shortcutHint?: string
  glyph: string
}

const TOOLBAR_ITEMS: readonly ToolbarItemDescriptor[] = [
  { command: "bold", label: "Bold", shortcutHint: "Ctrl/Cmd+B", glyph: "B" },
  { command: "italic", label: "Italic", shortcutHint: "Ctrl/Cmd+I", glyph: "I" },
  { command: "strikethrough", label: "Strikethrough", shortcutHint: "Ctrl/Cmd+Shift+X", glyph: "S" },
  { command: "code", label: "Inline code", shortcutHint: "Ctrl/Cmd+E", glyph: "</>" },
  { command: "heading", label: "Heading", glyph: "H" },
  { command: "quote", label: "Quote", glyph: "❝" },
  { command: "unordered-list", label: "Bulleted list", glyph: "•" },
  { command: "ordered-list", label: "Numbered list", glyph: "1." },
  { command: "task-list", label: "Task list", glyph: "☑" },
  { command: "link", label: "Insert link", shortcutHint: "Ctrl/Cmd+K", glyph: "🔗" },
]

export function MarkdownFormattingToolbar({ disabled = false, onCommand }: FormattingToolbarProps) {
  return (
    <div className="markdownFormattingToolbar" role="toolbar" aria-label="Markdown formatting">
      {TOOLBAR_ITEMS.map(item => {
        const title = item.shortcutHint ? `${item.label} (${item.shortcutHint})` : item.label
        return (
          <button
            key={item.command}
            type="button"
            className={`markdownFormattingToolbarButton is-${item.command}`}
            title={title}
            aria-label={item.label}
            disabled={disabled}
            onMouseDown={event => {
              // Keep focus inside the editor textarea when clicking a toolbar action.
              event.preventDefault()
            }}
            onClick={() => onCommand(item.command)}
          >
            <span className="markdownFormattingToolbarGlyph" aria-hidden="true">{item.glyph}</span>
          </button>
        )
      })}
    </div>
  )
}

/**
 * Applies a `@github/markdown-toolbar-element`-style formatting operation to `(value, selectionStart, selectionEnd)`
 * and returns the updated text and selection offsets.
 */
export function applyMarkdownFormattingCommand(
  value: string,
  selectionStart: number,
  selectionEnd: number,
  command: MarkdownFormattingCommand,
): TextSelectionPatch {
  const start = Math.max(0, Math.min(selectionStart, value.length))
  const end = Math.max(start, Math.min(selectionEnd, value.length))

  switch (command) {
    case "bold":
      return toggleInlineWrapper(value, start, end, "**", "**", "bold text")
    case "italic":
      return toggleInlineWrapper(value, start, end, "*", "*", "italic text")
    case "strikethrough":
      return toggleInlineWrapper(value, start, end, "~~", "~~", "strikethrough text")
    case "code":
      return toggleCodeFormatting(value, start, end)
    case "link":
      return insertMarkdownLink(value, start, end)
    case "heading":
      return cycleHeadingPrefix(value, start, end)
    case "quote":
      return toggleLinePrefix(value, start, end, "> ")
    case "unordered-list":
      return toggleLinePrefix(value, start, end, "- ")
    case "ordered-list":
      return toggleOrderedListPrefix(value, start, end)
    case "task-list":
      return toggleLinePrefix(value, start, end, "- [ ] ")
  }
}

function toggleInlineWrapper(
  value: string,
  start: number,
  end: number,
  prefix: string,
  suffix: string,
  placeholder: string,
): TextSelectionPatch {
  const selected = value.slice(start, end)

  // Unwrap if the outer text immediately around the selection matches prefix/suffix.
  if (
    start >= prefix.length &&
    end + suffix.length <= value.length &&
    value.slice(start - prefix.length, start) === prefix &&
    value.slice(end, end + suffix.length) === suffix
  ) {
    const nextValue = value.slice(0, start - prefix.length) + selected + value.slice(end + suffix.length)
    return {
      value: nextValue,
      selectionStart: start - prefix.length,
      selectionEnd: end - prefix.length,
    }
  }

  // Unwrap if the selection itself starts with prefix and ends with suffix.
  if (
    selected.length >= prefix.length + suffix.length &&
    selected.startsWith(prefix) &&
    selected.endsWith(suffix)
  ) {
    const inner = selected.slice(prefix.length, selected.length - suffix.length)
    const nextValue = value.slice(0, start) + inner + value.slice(end)
    return {
      value: nextValue,
      selectionStart: start,
      selectionEnd: start + inner.length,
    }
  }

  const innerText = selected.length > 0 ? selected : placeholder
  const replacement = `${prefix}${innerText}${suffix}`
  const nextValue = value.slice(0, start) + replacement + value.slice(end)
  return {
    value: nextValue,
    selectionStart: start + prefix.length,
    selectionEnd: start + prefix.length + innerText.length,
  }
}

function toggleCodeFormatting(value: string, start: number, end: number): TextSelectionPatch {
  const selected = value.slice(start, end)
  if (selected.includes("\n")) {
    return toggleInlineWrapper(value, start, end, "```\n", "\n```", "code")
  }
  return toggleInlineWrapper(value, start, end, "`", "`", "code")
}

function insertMarkdownLink(value: string, start: number, end: number): TextSelectionPatch {
  const selected = value.slice(start, end)
  const isUrl = /^https?:\/\/\S+$/i.test(selected.trim())

  if (isUrl) {
    const labelPlaceholder = "link text"
    const replacement = `[${labelPlaceholder}](${selected.trim()})`
    const nextValue = value.slice(0, start) + replacement + value.slice(end)
    return {
      value: nextValue,
      selectionStart: start + 1,
      selectionEnd: start + 1 + labelPlaceholder.length,
    }
  }

  const label = selected.length > 0 ? selected : "link text"
  const urlPlaceholder = "url"
  const replacement = `[${label}](${urlPlaceholder})`
  const nextValue = value.slice(0, start) + replacement + value.slice(end)
  const urlStart = start + label.length + 3
  return {
    value: nextValue,
    selectionStart: urlStart,
    selectionEnd: urlStart + urlPlaceholder.length,
  }
}

function cycleHeadingPrefix(value: string, start: number, end: number): TextSelectionPatch {
  const { lineStart, lineEnd } = enclosingLineSpan(value, start, end)
  const block = value.slice(lineStart, lineEnd)
  const lines = block.split("\n")
  const transformedLines = lines.map(line => {
    const match = line.match(/^(#{1,6})\s+(.*)$/)
    if (!match) {
      return `# ${line}`
    }
    const level = match[1].length
    const content = match[2]
    if (level >= 3) {
      return content
    }
    return `${"#".repeat(level + 1)} ${content}`
  })
  const replacement = transformedLines.join("\n")
  const nextValue = value.slice(0, lineStart) + replacement + value.slice(lineEnd)
  return {
    value: nextValue,
    selectionStart: lineStart,
    selectionEnd: lineStart + replacement.length,
  }
}

function toggleLinePrefix(value: string, start: number, end: number, prefix: string): TextSelectionPatch {
  const { lineStart, lineEnd } = enclosingLineSpan(value, start, end)
  const block = value.slice(lineStart, lineEnd)
  const lines = block.split("\n")
  const everyLineHasPrefix = lines.every(line => line.startsWith(prefix))
  const transformedLines = lines.map(line => {
    if (everyLineHasPrefix) {
      return line.slice(prefix.length)
    }
    return `${prefix}${line}`
  })
  const replacement = transformedLines.join("\n")
  const nextValue = value.slice(0, lineStart) + replacement + value.slice(lineEnd)
  return {
    value: nextValue,
    selectionStart: lineStart,
    selectionEnd: lineStart + replacement.length,
  }
}

function toggleOrderedListPrefix(value: string, start: number, end: number): TextSelectionPatch {
  const { lineStart, lineEnd } = enclosingLineSpan(value, start, end)
  const block = value.slice(lineStart, lineEnd)
  const lines = block.split("\n")
  const everyLineNumbered = lines.every(line => /^\d+\.\s+/.test(line))
  const transformedLines = lines.map((line, index) => {
    if (everyLineNumbered) {
      return line.replace(/^\d+\.\s+/, "")
    }
    return `${index + 1}. ${line.replace(/^[-*+]\s+/, "")}`
  })
  const replacement = transformedLines.join("\n")
  const nextValue = value.slice(0, lineStart) + replacement + value.slice(lineEnd)
  return {
    value: nextValue,
    selectionStart: lineStart,
    selectionEnd: lineStart + replacement.length,
  }
}

function enclosingLineSpan(value: string, start: number, end: number): { lineStart: number, lineEnd: number } {
  const lineStart = value.lastIndexOf("\n", Math.max(0, start - 1)) + 1
  const nextNewline = value.indexOf("\n", end)
  const lineEnd = nextNewline < 0 ? value.length : nextNewline
  return { lineStart, lineEnd }
}
