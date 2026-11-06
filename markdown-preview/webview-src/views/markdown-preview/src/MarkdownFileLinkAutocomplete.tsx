// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { useEffect, useState } from "react"
import type { MarkdownTextareaController } from "./markdownBlockEditor"
import type {
  MarkdownSuggestFileLinksParams,
  MarkdownSuggestedFileLinksParams,
} from "./markdownPreviewTypes"

export interface FileLinkTriggerMatch {
  kind: "markdown-link" | "at-mention"
  query: string
  replaceStart: number
  replaceEnd: number
}

interface MarkdownFileLinkAutocompleteProps {
  controller: MarkdownTextareaController
  onSuggestFileLinks?: (params: MarkdownSuggestFileLinksParams) => Promise<MarkdownSuggestedFileLinksParams>
}

export function MarkdownFileLinkAutocomplete({
  controller,
  onSuggestFileLinks,
}: MarkdownFileLinkAutocompleteProps) {
  const trigger = onSuggestFileLinks
    ? detectFileLinkTrigger(controller.value, controller.selectionStart, controller.selectionEnd)
    : undefined
  const [suggestions, setSuggestions] = useState<string[]>([])
  const [selectedIndex, setSelectedIndex] = useState(0)

  useEffect(() => {
    if (!trigger || !onSuggestFileLinks) {
      setSuggestions([])
      setSelectedIndex(0)
      return
    }

    let cancelled = false
    const timer = window.setTimeout(() => {
      void onSuggestFileLinks({ query: trigger.query, limit: 15 })
        .then(response => {
          if (cancelled) return
          setSuggestions(response.paths ?? [])
          setSelectedIndex(0)
        })
        .catch(() => {
          if (!cancelled) setSuggestions([])
        })
    }, 60)

    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [onSuggestFileLinks, trigger?.kind, trigger?.query, trigger?.replaceStart])

  useEffect(() => {
    const textarea = controller.textarea
    if (!textarea || !trigger || suggestions.length === 0) return

    const handleKeyDown = (event: KeyboardEvent): void => {
      if (event.key === "ArrowDown") {
        event.preventDefault()
        event.stopPropagation()
        setSelectedIndex(current => (current + 1) % suggestions.length)
      }
      else if (event.key === "ArrowUp") {
        event.preventDefault()
        event.stopPropagation()
        setSelectedIndex(current => (current - 1 + suggestions.length) % suggestions.length)
      }
      else if (event.key === "Enter" || event.key === "Tab") {
        if (!event.metaKey && !event.ctrlKey) {
          event.preventDefault()
          event.stopPropagation()
          const chosen = suggestions[selectedIndex] ?? suggestions[0]
          if (chosen) {
            applyFileLinkSuggestion(controller, trigger, chosen)
          }
        }
      }
    }

    textarea.addEventListener("keydown", handleKeyDown, true)
    return () => {
      textarea.removeEventListener("keydown", handleKeyDown, true)
    }
  }, [controller, selectedIndex, suggestions, trigger])

  if (!trigger || suggestions.length === 0) return null

  return (
    <div
      className="markdownFileLinkAutocomplete"
      role="listbox"
      aria-label="Project file suggestions"
    >
      {suggestions.map((path, index) => (
        <button
          key={path}
          type="button"
          role="option"
          aria-selected={index === selectedIndex}
          className={`markdownFileLinkOption${index === selectedIndex ? " is-selected" : ""}`}
          onMouseDown={event => {
            event.preventDefault()
          }}
          onMouseEnter={() => setSelectedIndex(index)}
          onClick={() => applyFileLinkSuggestion(controller, trigger, path)}
        >
          {path}
        </button>
      ))}
    </div>
  )
}

export function detectFileLinkTrigger(
  value: string,
  selectionStart: number,
  selectionEnd: number,
): FileLinkTriggerMatch | undefined {
  if (selectionStart !== selectionEnd || selectionStart < 0 || selectionStart > value.length) {
    return undefined
  }

  const lineStart = value.lastIndexOf("\n", Math.max(0, selectionStart - 1)) + 1
  const beforeCursorInLine = value.slice(lineStart, selectionStart)

  // Match `[label](query` or `![alt](query` right before the cursor.
  const markdownLinkMatch = beforeCursorInLine.match(/!?\[[^\]\n]*\]\(([^)\s]*)$/)
  if (markdownLinkMatch) {
    const query = markdownLinkMatch[1]
    if (/^(https?:|mailto:|#)/i.test(query)) {
      return undefined
    }
    return {
      kind: "markdown-link",
      query,
      replaceStart: selectionStart - query.length,
      replaceEnd: selectionStart,
    }
  }

  // Match `@query` at the start of a line or after whitespace.
  const atMentionMatch = beforeCursorInLine.match(/(?:^|\s)@([^\s[\]()]*)$/)
  if (atMentionMatch) {
    const query = atMentionMatch[1]
    const atIndex = selectionStart - query.length - 1
    return {
      kind: "at-mention",
      query,
      replaceStart: atIndex,
      replaceEnd: selectionStart,
    }
  }

  return undefined
}

export function applyFileLinkSuggestion(
  controller: MarkdownTextareaController,
  trigger: FileLinkTriggerMatch,
  selectedPath: string,
): void {
  if (trigger.kind === "markdown-link") {
    const hasClosingParen = controller.value[trigger.replaceEnd] === ")"
    const replacement = hasClosingParen ? selectedPath : `${selectedPath})`
    controller.replaceRange(trigger.replaceStart, trigger.replaceEnd, replacement, false)
    return
  }

  const fileName = selectedPath.split("/").pop() || selectedPath
  controller.replaceRange(
    trigger.replaceStart,
    trigger.replaceEnd,
    `[${fileName}](${selectedPath})`,
    false,
  )
}
