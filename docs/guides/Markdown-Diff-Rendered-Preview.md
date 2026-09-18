# Markdown Rendered Diff Preview

The Markdown Lens plugin (`markdown-preview` module) can show a diff of two Markdown documents as two rendered
previews instead of two raw-text editors. The feature is layered on top of the platform text diff, so the built-in
diff viewer, its toolbar, and its change navigation stay available.

## Using the rendered diff

1. Open a diff for a `*.md` file. The diff opens in the usual text mode.
2. Toggle **Rendered Markdown** in the diff toolbar. The diff content is replaced with two rendered previews:
   left is the "before" document, right is the "after" document.
3. Toggle the action again to return to the text diff. The chosen mode is remembered for the next opened Markdown diff.

In rendered mode:

- Added blocks are highlighted as added, modified blocks as modified, and deleted blocks are shown as a placeholder
  on the opposite side. Highlighting reuses the frontend decorations `is-vcs-added`, `is-vcs-modified`, and
  `markdownRemovedBlockPlaceholder`.
- **Next/Previous Difference** scrolls both previews to the next or previous changed block.
- Scrolling one preview scrolls the other one to the matching source line.
- If both sides are equal, the platform "contents are identical" notification is shown above the previews.
- Editing the local side re-renders the previews and recomputes the change blocks.

For a file that was added or deleted as a whole, the diff has a single non-empty side, so the rendered mode shows one
preview with the whole document highlighted: as added for a new file and as modified for a deleted one. Scroll
synchronization and change navigation are not applicable in that case.

## Disabling the feature

The Registry key `markdown.webview.diff.preview.enabled` (default `true`) turns the whole feature off. With the key
disabled no rendered-preview layer is installed and no WebView panel is created for diffs. WebView panels are also
created lazily, that is, only when the rendered mode is activated for the first time.

Non-Markdown diffs and three-side merge diffs are never touched.

## Implementation notes

- Entry point: `com.intellij.diff.DiffExtension` implemented by
  `io.github.nerzhulart.webview.markdown.preview.diff.MarkdownDiffPreviewExtension`. It supports both the two-side
  text viewer (`TwosideTextDiffViewer`) and the one-side viewer used for added and deleted files
  (`OnesideTextDiffViewer`); `MarkdownDiffSides` accepts an `EmptyContent` side and reports it as absent.
- Change blocks come from the platform `TwosideTextDiffProvider`
  (`DiffUtil.createTextDiffProvider(...)`), so user ignore and highlight policies are honored. That API is
  `@ApiStatus.Internal`: all usage is isolated in `MarkdownDiffPreviewController` and `MarkdownDiffChangeBlocks`, so
  a switch to the public `ComparisonManager` stays a local change.
- The toggle state is stored by `MarkdownDiffPreviewSettings`, an application-level light service implemented as a
  `SimplePersistentStateComponent` with the `markdown-lens.xml` storage, and is written only for an explicit user
  toggle. Restoring the mode for a newly opened diff and resetting the panel on dispose go through
  `MarkdownDiffPreviewController.applyRenderedMode(...)`, which does not touch the stored value, so closing a diff in
  rendered mode keeps the setting.
- Platform line ranges are 0-based and end-exclusive; the preview decorations use the 1-based, end-inclusive
  `data-sourcepos` scheme. The conversion lives in `MarkdownDiffChangeBlocks`.
- Scroll positions are reported from the page through `MarkdownPreviewHostApi.previewScrolled` and translated to the
  opposite side by `MarkdownDiffScrollSynchronizer`.
