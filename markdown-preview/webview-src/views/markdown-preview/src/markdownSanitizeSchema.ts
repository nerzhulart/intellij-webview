// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { defaultSchema, type Options } from "rehype-sanitize"

const defaultAttributes = defaultSchema.attributes || {}

export const markdownSanitizeSchema: Options = {
  ...defaultSchema,
  tagNames: unique([
    ...(defaultSchema.tagNames || []),
    "abbr",
    "br",
    "col",
    "colgroup",
    "details",
    "kbd",
    "mark",
    "path",
    "section",
    "summary",
    "sub",
    "sup",
    "svg",
  ]),
  attributes: {
    ...defaultAttributes,
    "*": mergeAttributes("*", [["dataSourcepos"], ["dataVcsInline", "ADDED", "MODIFIED", "REMOVED"]]),
    a: mergeAttributes("a", [["ariaLabel"], ["dataFootnoteBackref"]]),
    code: mergeAttributes("code", [["className", /^language-./, "frontmatterCode", "no-highlight", "nohighlight"], ["dataFrontmatter", "yaml", "toml"]]),
    details: mergeAttributes("details", [["open"]]),
    div: mergeAttributes("div", [["className", "markdown-alert", /^markdown-alert-(note|tip|important|warning|caution)$/], ["dir"]]),
    h2: mergeAttributes("h2", [["className", "sr-only"]]),
    input: mergeAttributes("input", [["checked"], ["disabled"], ["type", "checkbox"]]),
    li: mergeAttributes("li", [["className", "task-list-item"]]),
    p: mergeAttributes("p", [["className", "markdown-alert-title"], ["dir"]]),
    path: mergeAttributes("path", [["d"]]),
    section: mergeAttributes("section", [["className", "footnotes"], ["dataFootnotes"]]),
    svg: mergeAttributes("svg", [["ariaHidden"], ["className", "octicon"], ["height"], ["viewBox"], ["width"]]),
    ul: mergeAttributes("ul", [["className", "contains-task-list"]]),
  } as Options["attributes"],
  protocols: {
    ...defaultSchema.protocols,
    href: mergeProtocols("href", ["file"]),
    src: mergeProtocols("src", ["file"]),
  },
}

function mergeAttributes(tagName: string, additions: Array<unknown>): Array<unknown> {
  return [...((defaultAttributes as Record<string, Array<unknown>>)[tagName] || []), ...additions]
}

function unique<T>(values: Array<T>): Array<T> {
  return Array.from(new Set(values))
}

function mergeProtocols(attributeName: string, additions: Array<string>): Array<string> {
  return unique([...(defaultSchema.protocols?.[attributeName] || []), ...additions])
}
