// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { lazy, Suspense, useCallback, useEffect, useMemo, useState, type ChangeEvent, type MouseEvent, type ReactNode } from "react"
import ReactMarkdown, { type Components, type Options } from "react-markdown"
import { getPerfLogger } from "@nerzhulart/intellij-webview-sdk"
import rehypeRaw from "rehype-raw"
import rehypeSanitize from "rehype-sanitize"
import rehypeSlug from "rehype-slug"
import remarkEmoji from "remark-emoji"
import remarkFrontmatter from "remark-frontmatter"
import remarkGfm from "remark-gfm"
import { remarkAlert } from "remark-github-blockquote-alert"
import { FloatingMarkdownControls } from "./FloatingMarkdownControls"
import { MarkdownAgentContextPopover } from "./markdownAgentContextPopover"
import { patchTaskListItemCheckbox } from "./markdownAstPatcher"
import {
  MarkdownBlockEditor,
  MarkdownHoveredBlockEditTrigger,
  createActiveEditBlock,
  type ActiveMarkdownEditBlock,
  type EditableMarkdownBlockKind,
} from "./markdownBlockEditor"
import { setScrollbarChangeMarkers } from "./markdownChangeMarkers"
import { markdownDiagnosticDetails } from "./markdownDiagnostics"
import { codeNodeFromPreNode, hastText, type HastNode } from "./markdownHastUtils"
import { inlineChangeRanges, remarkInlineChanges } from "./markdownInlineChanges"
import type { MarkdownPageZoom } from "./markdownPageZoom"
import { collectPathLinkCandidates, renderPathLinks } from "./markdownPathLinks"
import type {
  MarkdownAddToAgentContextParams,
  MarkdownAddToAgentContextResultParams,
  MarkdownApplyDocumentEditParams,
  MarkdownApplyDocumentEditResultParams,
  MarkdownChangedBlockDescriptor,
  MarkdownCommandCandidate,
  MarkdownCommandDescriptor,
  MarkdownPreviewSettings,
  MarkdownNavigatePathLinkRequest,
  MarkdownResolvePathLinksRequest,
  MarkdownResolvedPathLinksResponse,
  MarkdownResolveRunCommandsRequest,
  MarkdownResolvedRunCommandsResponse,
  MarkdownRunCommandRequest,
  MarkdownSaveImageAssetParams,
  MarkdownSaveImageAssetResultParams,
  MarkdownSourceRange,
  MarkdownSuggestFileLinksParams,
  MarkdownSuggestedFileLinksParams,
} from "./markdownPreviewTypes"
import { classNames, codeToString } from "./markdownReactUtils"
import { markdownResourceSrc } from "./markdownResources"
import { frontmatterBlockFromPreNode, frontmatterLanguageFromPreNode, remarkFrontmatterBlocks, remarkSourcePositionAttributes } from "./markdownRemarkPlugins"
import {
  CodeFenceRunGutter,
  RunCommandButton,
  codeFenceCommandCandidates,
  createCommandLookup,
  findBlockCommand,
  findInlineCommand,
  findLineCommands,
  hasLanguageClass,
  inlineCommandCandidate,
  isMermaidCodeNode,
  uniqueCommandCandidates,
} from "./markdownRunCommands"
import { markdownSanitizeSchema } from "./markdownSanitizeSchema"
import {
  cancelScheduledMarkdownPreviewScroll,
  clearSourceDecorations,
  decorateSourceBlocks,
  parseSourcePosition,
  positionKey,
  scrollMarkdownPreviewToLine,
  sourcePositionFromHastNode,
  sourcePositionFromPreNode,
  type SourcePositionRange,
} from "./markdownSourcePositions"

export { scrollMarkdownPreviewToLine } from "./markdownSourcePositions"

interface MarkdownPreviewAppProps {
  markdown: string
  scrollLine: number
  contentVersion: number
  changes: MarkdownChangedBlockDescriptor[]
  selection: MarkdownSourceRange | undefined
  settings: MarkdownPreviewSettings
  pageZoom: MarkdownPageZoom
  theme: "light" | "dark"
  onOpenLink: (href: string) => void
  onResolveRunCommands: (request: MarkdownResolveRunCommandsRequest) => Promise<MarkdownResolvedRunCommandsResponse>
  onRunCommand: (request: MarkdownRunCommandRequest) => void
  onResolvePathLinks: (request: MarkdownResolvePathLinksRequest) => Promise<MarkdownResolvedPathLinksResponse>
  onNavigatePathLink: (request: MarkdownNavigatePathLinkRequest) => void
  onSetFontSize: (fontSize: number) => void
  onAddToAgentContext: (params: MarkdownAddToAgentContextParams) => Promise<MarkdownAddToAgentContextResultParams>
  onApplyDocumentEdit?: (params: MarkdownApplyDocumentEditParams) => Promise<MarkdownApplyDocumentEditResultParams>
  onSaveImageAsset?: (params: MarkdownSaveImageAssetParams) => Promise<MarkdownSaveImageAssetResultParams>
  onSuggestFileLinks?: (params: MarkdownSuggestFileLinksParams) => Promise<MarkdownSuggestedFileLinksParams>
}

const emptyPathSet = new Set<string>()
const markdownLogger = getPerfLogger("markdown")
const LazyMarkdownImageBlock = lazy(() => import("./MarkdownImageBlock").then(module => ({ default: module.MarkdownImageBlock })))
const LazyMermaidBlock = lazy(() => import("./MermaidBlock").then(module => ({ default: module.MermaidBlock })))
type RehypePlugin = NonNullable<Options["rehypePlugins"]>[number]
type IdleCallbackHandle = number

type WindowWithIdleCallback = Window & {
  requestIdleCallback: ((callback: () => void, options?: { timeout?: number }) => IdleCallbackHandle) | undefined
  cancelIdleCallback: ((handle: IdleCallbackHandle) => void) | undefined
}

const baseRemarkPlugins: NonNullable<Options["remarkPlugins"]> = [
  remarkGfm,
  [remarkFrontmatter, ["yaml", "toml"]],
  remarkAlert,
  remarkFrontmatterBlocks,
  remarkSourcePositionAttributes,
]
const baseRehypePlugins: NonNullable<Options["rehypePlugins"]> = [
  rehypeRaw,
  rehypeSlug,
  [rehypeSanitize, markdownSanitizeSchema],
]

export function MarkdownPreviewApp({
  markdown,
  scrollLine,
  contentVersion,
  changes,
  selection,
  settings,
  pageZoom,
  theme,
  onOpenLink,
  onResolveRunCommands,
  onRunCommand,
  onResolvePathLinks,
  onNavigatePathLink,
  onSetFontSize,
  onAddToAgentContext,
  onApplyDocumentEdit,
  onSaveImageAsset,
  onSuggestFileLinks,
}: MarkdownPreviewAppProps) {
  const canEditDocument = Boolean(settings.canEditDocument) && onApplyDocumentEdit !== undefined
  const [activeEditBlock, setActiveEditBlock] = useState<ActiveMarkdownEditBlock | undefined>()
  const handleStartEdit = useCallback(
    (range: SourcePositionRange, kind: EditableMarkdownBlockKind, language?: string) => {
      if (!canEditDocument) return
      setActiveEditBlock(createActiveEditBlock(markdown, range, kind, language))
    },
    [canEditDocument, markdown],
  )
  useEffect(() => {
    if (!canEditDocument && activeEditBlock !== undefined) {
      setActiveEditBlock(undefined)
    }
  }, [activeEditBlock, canEditDocument])
  const commandCandidates: MarkdownCommandCandidate[] = []
  const pathLinkCandidates = useMemo(() => {
    const startedAtMs = performance.now()
    const candidates = collectPathLinkCandidates(markdown)
    markdownLogger.perfSince("pathLinks.collect", startedAtMs, markdownDiagnosticDetails(markdown, contentVersion, `candidates=${candidates.length}`))
    return candidates
  }, [contentVersion, markdown])
  const [resolvedCommands, setResolvedCommands] = useState<{ contentVersion: number, commands: MarkdownCommandDescriptor[] }>({
    contentVersion: -1,
    commands: [],
  })
  const [resolvedPathLinks, setResolvedPathLinks] = useState<{ contentVersion: number, rawPaths: ReadonlySet<string> }>({
    contentVersion: -1,
    rawPaths: emptyPathSet,
  })
  const commandsReady = resolvedCommands.contentVersion === contentVersion
  const pathLinksReady = resolvedPathLinks.contentVersion === contentVersion
  const commands = commandsReady ? resolvedCommands.commands : []
  const resolvedRawPaths = pathLinksReady ? resolvedPathLinks.rawPaths : emptyPathSet
  const commandLookup = createCommandLookup(commands)
  const remarkPlugins = useMemo<Options["remarkPlugins"]>(() => {
    const ranges = inlineChangeRanges(changes)
    if (ranges.length === 0) return [...baseRemarkPlugins, remarkEmoji]
    return [...baseRemarkPlugins, [remarkInlineChanges, { markdown, ranges }], remarkEmoji]
  }, [changes, markdown])
  const [rehypeHighlightState, setRehypeHighlightState] = useState<{ contentVersion: number, plugin: RehypePlugin } | undefined>()
  const rehypeHighlightPlugin = rehypeHighlightState?.contentVersion === contentVersion ? rehypeHighlightState.plugin : undefined
  const rehypePlugins = useMemo<Options["rehypePlugins"]>(() => {
    if (!rehypeHighlightPlugin) return baseRehypePlugins
    return [
      ...baseRehypePlugins,
      [rehypeHighlightPlugin, { detect: true, plainText: ["mermaid", "text", "txt"] }],
    ] as Options["rehypePlugins"]
  }, [rehypeHighlightPlugin])
  function renderEditableBlock(
    node: unknown,
    kind: EditableMarkdownBlockKind,
    renderDefault: (editableProps: Record<string, string | undefined>) => ReactNode,
    language?: string,
  ): ReactNode {
    const sourcePosition = kind === "code" || kind === "mermaid"
      ? sourcePositionFromPreNode(node) ?? sourcePositionFromHastNode(node)
      : sourcePositionFromHastNode(node)
    const key = sourcePosition ? positionKey(sourcePosition) : undefined
    if (canEditDocument && activeEditBlock && key === activeEditBlock.key && onApplyDocumentEdit) {
      return (
        <MarkdownBlockEditor
          key={`editor:${activeEditBlock.key}`}
          markdown={markdown}
          contentVersion={contentVersion}
          block={activeEditBlock}
          theme={theme}
          onApplyDocumentEdit={onApplyDocumentEdit}
          onSaveImageAsset={onSaveImageAsset}
          onSuggestFileLinks={onSuggestFileLinks}
          onClose={() => setActiveEditBlock(undefined)}
        />
      )
    }

    const editableProps: Record<string, string | undefined> = canEditDocument && key
      ? {
          "data-editable-block": kind,
          "data-editable-language": language,
        }
      : {}
    return renderDefault(editableProps)
  }

  function handleTaskCheckboxChange(event: ChangeEvent<HTMLInputElement>, node: unknown): void {
    if (!canEditDocument || !onApplyDocumentEdit) return
    event.stopPropagation()
    const liElement = event.currentTarget.closest<HTMLElement>("li[data-sourcepos]")
    const itemRange = parseSourcePosition(liElement?.dataset.sourcepos) ?? sourcePositionFromHastNode(node)
    if (!itemRange) return

    const patch = patchTaskListItemCheckbox(markdown, itemRange, event.currentTarget.checked)
    if (!patch) return

    void onApplyDocumentEdit({
      contentVersion,
      range: patch.range,
      newMarkdown: patch.newMarkdown,
      actionTitle: "Toggle task item",
    })
  }

  const components: Components = {
    a({ href, children, ...props }) {
      function handleClick(event: MouseEvent<HTMLAnchorElement>): void {
        if (!href) return
        event.preventDefault()
        const localFragment = localAnchorFragment(href)
        if (localFragment !== undefined) {
          navigateToLocalAnchor(localFragment)
          return
        }
        onOpenLink(href)
      }

      return <a {...props} href={href} onClick={handleClick}>{children}</a>
    },
    input({ node, type, checked, disabled, className, ...props }) {
      if (type === "checkbox") {
        if (canEditDocument) {
          return (
            <input
              {...props}
              type="checkbox"
              checked={Boolean(checked)}
              disabled={false}
              className={classNames(className, "markdownTaskCheckbox", "is-editable")}
              onClick={event => event.stopPropagation()}
              onDoubleClick={event => event.stopPropagation()}
              onChange={event => handleTaskCheckboxChange(event, node)}
            />
          )
        }
        return <input {...props} type="checkbox" checked={Boolean(checked)} disabled={Boolean(disabled ?? true)} className={className} />
      }
      return <input {...props} type={type} checked={checked} disabled={disabled} className={className} />
    },
    img({ src, alt, ...props }) {
      return <img {...props} src={markdownResourceSrc(src)} alt={alt} />
    },
    h1({ node, className, children, ...props }) {
      return renderEditableBlock(node, "text", editableProps => <h1 {...props} {...editableProps} className={className}>{children}</h1>)
    },
    h2({ node, className, children, ...props }) {
      return renderEditableBlock(node, "text", editableProps => <h2 {...props} {...editableProps} className={className}>{children}</h2>)
    },
    h3({ node, className, children, ...props }) {
      return renderEditableBlock(node, "text", editableProps => <h3 {...props} {...editableProps} className={className}>{children}</h3>)
    },
    h4({ node, className, children, ...props }) {
      return renderEditableBlock(node, "text", editableProps => <h4 {...props} {...editableProps} className={className}>{children}</h4>)
    },
    h5({ node, className, children, ...props }) {
      return renderEditableBlock(node, "text", editableProps => <h5 {...props} {...editableProps} className={className}>{children}</h5>)
    },
    h6({ node, className, children, ...props }) {
      return renderEditableBlock(node, "text", editableProps => <h6 {...props} {...editableProps} className={className}>{children}</h6>)
    },
    blockquote({ node, className, children, ...props }) {
      return renderEditableBlock(node, "text", editableProps => <blockquote {...props} {...editableProps} className={className}>{children}</blockquote>)
    },
    ul({ node, className, children, ...props }) {
      return renderEditableBlock(node, "text", editableProps => <ul {...props} {...editableProps} className={className}>{children}</ul>)
    },
    ol({ node, className, children, ...props }) {
      return renderEditableBlock(node, "text", editableProps => <ol {...props} {...editableProps} className={className}>{children}</ol>)
    },
    div({ node, className, children, ...props }) {
      if (className?.split(/\s+/).includes("markdown-alert")) {
        return renderEditableBlock(node, "text", editableProps => <div {...props} {...editableProps} className={className}>{children}</div>)
      }
      return <div {...props} className={className}>{children}</div>
    },
    table({ node, className, children, ...props }) {
      return renderEditableBlock(node, "table", editableProps => <table {...props} {...editableProps} className={className}>{children}</table>)
    },
    p({ node, className, children, ...props }) {
      const image = standaloneImageFromParagraphNode(node)
      if (image) {
        const imageSrc = markdownResourceSrc(image.src) ?? image.src
        return (
          <Suspense fallback={<p {...props} className={className}><img src={imageSrc} alt={image.alt ?? ""} title={image.title} /></p>}>
            <LazyMarkdownImageBlock {...props} className={className} src={imageSrc} alt={image.alt} title={image.title} />
          </Suspense>
        )
      }
      return renderEditableBlock(node, "text", editableProps => <p {...props} {...editableProps} className={className}>{children}</p>)
    },
    pre({ node, className, children, ...props }) {
      const frontmatterLanguage = frontmatterLanguageFromPreNode(node)
      if (frontmatterLanguage) {
        const frontmatterBlock = frontmatterBlockFromPreNode(node)
        if (!frontmatterBlock) return null

        const sourcePosition = sourcePositionFromPreNode(node)
        return (
          <section className="frontmatterBlock" data-sourcepos={sourcePosition ? positionKey(sourcePosition) : undefined}>
            <details className="frontmatterMetadata">
              <summary>Frontmatter metadata</summary>
              <dl>
                {frontmatterBlock.metadata.map((entry, index) => (
                  <div className="frontmatterMetadataEntry" key={`${entry.key}-${index}`}>
                    <dt>{entry.key}</dt>
                    <dd>{entry.value}</dd>
                  </div>
                ))}
              </dl>
            </details>
          </section>
        )
      }
      const sourcePosition = sourcePositionFromPreNode(node)
      const codeNode = codeNodeFromPreNode(node)
      const isMermaidFence = codeNode ? isMermaidCodeNode(codeNode) : false
      const codeLanguage = codeNode ? codeLanguageFromNode(codeNode) : undefined
      const key = sourcePosition ? positionKey(sourcePosition) : undefined
      if (canEditDocument && activeEditBlock && key === activeEditBlock.key && onApplyDocumentEdit) {
        return (
          <MarkdownBlockEditor
            key={`editor:${activeEditBlock.key}`}
            markdown={markdown}
            contentVersion={contentVersion}
            block={activeEditBlock}
            theme={theme}
            onApplyDocumentEdit={onApplyDocumentEdit}
            onSaveImageAsset={onSaveImageAsset}
            onSuggestFileLinks={onSuggestFileLinks}
            onClose={() => setActiveEditBlock(undefined)}
          />
        )
      }
      const codeFenceCandidates = sourcePosition && codeNode && !isMermaidFence ? codeFenceCommandCandidates(sourcePosition, codeNode) : []
      commandCandidates.push(...codeFenceCandidates)
      if (isMermaidFence && codeNode) {
        const mermaidCode = hastText(codeNode).replace(/\n$/, "")
        return (
          <Suspense fallback={<div className="mermaidBlock isRendering">Rendering diagram...</div>}>
            <LazyMermaidBlock
              chart={mermaidCode}
              theme={theme}
              sourcePosition={sourcePosition}
              onEditDiagram={
                canEditDocument && sourcePosition
                  ? () => handleStartEdit(sourcePosition, "mermaid", "mermaid")
                  : undefined
              }
            />
          </Suspense>
        )
      }
      const editableCodeProps: Record<string, string | undefined> = canEditDocument && key
        ? {
            "data-sourcepos": key,
            "data-editable-block": "code",
            "data-editable-language": codeLanguage,
          }
        : {}
      const blockCommand = sourcePosition ? findBlockCommand(commandLookup, sourcePosition) : undefined
      const lineCommands = sourcePosition ? findLineCommands(commandLookup, sourcePosition, blockCommand?.firstLineCommandId) : []
      const hasCommandGutter = codeFenceCandidates.length > 0 || blockCommand || lineCommands.length > 0
      if (!hasCommandGutter) {
        return <pre className={className} {...props} {...editableCodeProps}>{children}</pre>
      }
      return (
        <div className="codeFenceWithCommands">
          <pre className={className} {...props} {...editableCodeProps}>{children}</pre>
          <CodeFenceRunGutter
            contentVersion={contentVersion}
            sourcePosition={sourcePosition}
            blockCommand={blockCommand}
            lineCommands={lineCommands}
            onRunCommand={onRunCommand}
          />
        </div>
      )
    },
    code({ node, className, children, ...props }) {
      const code = codeToString(children).replace(/\n$/, "")
      const sourcePosition = sourcePositionFromHastNode(node)
      if (className?.split(/\s+/).includes("language-mermaid")) {
        return (
          <Suspense fallback={<div className="mermaidBlock isRendering">Rendering diagram...</div>}>
            <LazyMermaidBlock
              chart={code}
              theme={theme}
              sourcePosition={sourcePosition}
              onEditDiagram={
                canEditDocument && sourcePosition
                  ? () => handleStartEdit(sourcePosition, "mermaid", "mermaid")
                  : undefined
              }
            />
          </Suspense>
        )
      }
      if (sourcePosition && !hasLanguageClass(className)) {
        commandCandidates.push(inlineCommandCandidate(sourcePosition, code))
      }
      const inlineCommand = sourcePosition ? findInlineCommand(commandLookup, sourcePosition) : undefined
      const linkedChildren = renderPathLinks(children, resolvedRawPaths, "code", contentVersion, onNavigatePathLink)
      if (inlineCommand) {
        return (
          <code className={className} {...props}>
            <RunCommandButton contentVersion={contentVersion} command={inlineCommand} variant="inline" onRunCommand={onRunCommand} />
            {linkedChildren}
          </code>
        )
      }
      return <code className={className} {...props}>{linkedChildren}</code>
    },
  }

  useEffect(() => {
    if (pathLinkCandidates.length === 0) {
      setResolvedPathLinks({ contentVersion, rawPaths: emptyPathSet })
      return
    }

    let cancelled = false
    setResolvedPathLinks({ contentVersion: -1, rawPaths: emptyPathSet })
    const cancelIdle = executeWhenIdle(() => {
      const startedAtMs = performance.now()
      void onResolvePathLinks({ contentVersion, candidates: pathLinkCandidates }).then(response => {
        if (cancelled) return
        const resolvedIds = new Set(response.resolvedIds)
        markdownLogger.perfSince(
          "pathLinks.resolve",
          startedAtMs,
          markdownDiagnosticDetails(markdown, contentVersion, `candidates=${pathLinkCandidates.length}, resolved=${resolvedIds.size}`),
        )
        setResolvedPathLinks({
          contentVersion,
          rawPaths: new Set(pathLinkCandidates.filter(candidate => resolvedIds.has(candidate.id)).map(candidate => candidate.rawPath)),
        })
      }).catch(() => {
        if (!cancelled) setResolvedPathLinks({ contentVersion, rawPaths: emptyPathSet })
      })
    })
    return () => {
      cancelled = true
      cancelIdle()
    }
  }, [contentVersion, onResolvePathLinks, pathLinkCandidates])

  useEffect(() => {
    let cancelled = false
    setResolvedCommands({ contentVersion: -1, commands: [] })
    const candidates = uniqueCommandCandidates(commandCandidates)
    if (candidates.length === 0) {
      setResolvedCommands({ contentVersion, commands: [] })
      return
    }

    const cancelIdle = executeWhenIdle(() => {
      const startedAtMs = performance.now()
      void onResolveRunCommands({ contentVersion, candidates }).then(response => {
        if (cancelled) return
        setResolvedCommands({ contentVersion, commands: response.commands })
        markdownLogger.perfSince(
          "runCommands.resolve",
          startedAtMs,
          markdownDiagnosticDetails(markdown, contentVersion, `candidates=${candidates.length}, resolved=${response.commands.length}`),
        )
      })
    })
    return () => {
      cancelled = true
      cancelIdle()
    }
  }, [contentVersion, onResolveRunCommands])

  useEffect(() => {
    if (!markdownMayNeedSyntaxHighlighting(markdown) || rehypeHighlightState?.contentVersion === contentVersion) return

    let cancelled = false
    const cancelIdle = executeWhenIdle(() => {
      void import("rehype-highlight").then(module => {
        if (!cancelled) {
          setRehypeHighlightState({ contentVersion, plugin: module.default as RehypePlugin })
        }
      })
    })
    return () => {
      cancelled = true
      cancelIdle()
    }
  }, [contentVersion, markdown, rehypeHighlightState])

  useEffect(() => {
    if (!commandsReady || !pathLinksReady || !markdownMayContainLatex(markdown)) return

    let cancelled = false
    const cancelIdle = executeWhenIdle(() => {
      const startedAtMs = performance.now()
      void import("./markdownLatex").then(({ renderMarkdownLatex }) => {
        if (cancelled) return
        renderMarkdownLatex()
        markdownLogger.perfSince("latex.render", startedAtMs, markdownDiagnosticDetails(markdown, contentVersion))
      })
    })
    return () => {
      cancelled = true
      cancelIdle()
    }
  }, [commandsReady, contentVersion, markdown, pathLinksReady, theme])

  useEffect(() => {
    scrollMarkdownPreviewToLine(scrollLine)
    return cancelScheduledMarkdownPreviewScroll
  }, [markdown, scrollLine])

  // Deferred enhancements (path links, run commands, syntax highlighting) re-render the content and drop
  // decoration classes, so the decorations are re-applied after every render.
  useEffect(() => {
    setScrollbarChangeMarkers(decorateSourceBlocks(selection, changes))
    return clearSourceDecorations
  })

  return (
    <>
      <div key={contentVersion} className="markdownPreviewContent webview-selectable-text">
        <ReactMarkdown
          remarkPlugins={remarkPlugins}
          rehypePlugins={rehypePlugins}
          components={components}
          urlTransform={url => url}
        >
          {markdown}
        </ReactMarkdown>
      </div>
      <FloatingMarkdownControls markdown={markdown} settings={settings} pageZoom={pageZoom} onSetFontSize={onSetFontSize} />
      <MarkdownHoveredBlockEditTrigger
        enabled={canEditDocument}
        activeBlockKey={activeEditBlock?.key}
        onStartEdit={handleStartEdit}
      />
      <MarkdownAgentContextPopover
        contentVersion={contentVersion}
        enabled={Boolean(settings.canAddToAgentContext) && activeEditBlock === undefined}
        onAddToAgentContext={onAddToAgentContext}
      />
    </>
  )
}

interface MarkdownImageDescriptor {
  src: string
  alt?: string
  title?: string
}

function standaloneImageFromParagraphNode(node: unknown): MarkdownImageDescriptor | undefined {
  const children = (node as HastNode | undefined)?.children ?? []
  const contentChildren = children.filter(child => !isWhitespaceTextNode(child))
  if (contentChildren.length !== 1) return undefined

  const imageNode = contentChildren[0]
  if (imageNode.tagName !== "img") return undefined

  const src = stringProperty(imageNode.properties?.src)
  if (!src) return undefined

  return {
    src,
    alt: stringProperty(imageNode.properties?.alt),
    title: stringProperty(imageNode.properties?.title),
  }
}

function localAnchorFragment(href: string): string | undefined {
  if (href.startsWith("#")) return href.slice(1)

  try {
    const url = new URL(href, window.location.href)
    if (url.origin === window.location.origin && url.pathname === window.location.pathname && url.search === window.location.search && url.hash.startsWith("#")) {
      return url.hash.slice(1)
    }
  }
  catch {
  }
  return undefined
}

function navigateToLocalAnchor(fragment: string): void {
  if (fragment.length === 0) {
    updateLocationHash("#")
    window.scrollTo({ top: 0, left: 0 })
    return
  }

  const target = findLocalAnchorTarget(decodeFragment(fragment))
  updateLocationHash(`#${fragment}`)
  target?.scrollIntoView({ block: "start" })
}

function findLocalAnchorTarget(id: string): HTMLElement | null {
  const normalizedId = id.replace(/^-+/, "")
  return document.getElementById(id)
    ?? document.getElementById(normalizedId)
    ?? document.getElementById(`user-content-${id}`)
    ?? document.getElementById(`user-content-${normalizedId}`)
}

function decodeFragment(fragment: string): string {
  try {
    return decodeURIComponent(fragment)
  }
  catch {
    return fragment
  }
}

function updateLocationHash(href: string): void {
  if (!window.history) return
  try {
    window.history.pushState(null, "", href)
  }
  catch {
  }
}

function isWhitespaceTextNode(node: HastNode): boolean {
  return node.tagName === undefined && (node.value ?? "").trim().length === 0
}

function stringProperty(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined
}

function codeLanguageFromNode(codeNode: HastNode): string | undefined {
  const rawClass = codeNode.properties?.className
  const classes = Array.isArray(rawClass)
    ? rawClass.filter((item): item is string => typeof item === "string")
    : typeof rawClass === "string"
      ? rawClass.split(/\s+/)
      : []
  const langClass = classes.find(name => name.startsWith("language-"))
  return langClass?.slice("language-".length)
}

function markdownMayNeedSyntaxHighlighting(markdown: string): boolean {
  return /(^|\n)(```|~~~| {4}|\t|<pre\b|<code\b)/.test(markdown)
}

function markdownMayContainLatex(markdown: string): boolean {
  return /\\\([\s\S]*?\\\)|\\\[[\s\S]*?\\\]|\\begin\{[A-Za-z*]+}/.test(markdown) || markdownMayContainDollarLatex(markdown)
}

function markdownMayContainDollarLatex(markdown: string): boolean {
  for (let index = 0; index < markdown.length; index++) {
    if (markdown[index] !== "$" || isEscaped(markdown, index)) continue

    const displayMath = markdown[index + 1] === "$"
    const delimiter = displayMath ? "$$" : "$"
    const contentStart = index + delimiter.length
    if (!displayMath && (contentStart >= markdown.length || /\s|\d/.test(markdown[contentStart]))) {
      index = contentStart - 1
      continue
    }

    const contentEnd = unescapedIndexOf(markdown, delimiter, contentStart)
    if (contentEnd < 0) {
      index = contentStart - 1
      continue
    }
    if (!displayMath && (contentEnd === contentStart || /\s/.test(markdown[contentEnd - 1]))) {
      index = contentEnd + delimiter.length - 1
      continue
    }
    return true
  }
  return false
}

function unescapedIndexOf(text: string, search: string, startIndex: number): number {
  let index = text.indexOf(search, startIndex)
  while (index >= 0 && isEscaped(text, index)) {
    index = text.indexOf(search, index + search.length)
  }
  return index
}

function isEscaped(text: string, index: number): boolean {
  let backslashCount = 0
  for (let offset = index - 1; offset >= 0 && text[offset] === "\\"; offset--) {
    backslashCount++
  }
  return backslashCount % 2 === 1
}

function executeWhenIdle(callback: () => void): () => void {
  let cancelled = false
  let animationFrameHandle: number | undefined
  let idleCallbackHandle: IdleCallbackHandle | undefined
  let timeoutHandle: number | undefined

  function run(): void {
    if (!cancelled) callback()
  }

  animationFrameHandle = requestAnimationFrame(() => {
    animationFrameHandle = undefined
    if (cancelled) return

    const idleWindow = window as WindowWithIdleCallback
    if (idleWindow.requestIdleCallback) {
      idleCallbackHandle = idleWindow.requestIdleCallback(() => {
        idleCallbackHandle = undefined
        run()
      }, { timeout: 1000 })
    }
    else {
      timeoutHandle = window.setTimeout(() => {
        timeoutHandle = undefined
        run()
      }, 0)
    }
  })

  return () => {
    cancelled = true
    if (animationFrameHandle !== undefined) cancelAnimationFrame(animationFrameHandle)
    const idleWindow = window as WindowWithIdleCallback
    if (idleCallbackHandle !== undefined) idleWindow.cancelIdleCallback?.(idleCallbackHandle)
    if (timeoutHandle !== undefined) clearTimeout(timeoutHandle)
  }
}
