 # ACP Chat Remaining Work

This backlog contains only unfinished assistant-ui features in the ACP Chat demo. Existing agent selection, streaming animation, mode/model controls, attachments, and slash commands are implemented.

## Message Actions

- Add Copy/Edit/Regenerate action bars with assistant-ui primitives.
- Implement edit by truncating after the edited user turn and re-prompting with rebuilt content blocks.
- Implement regenerate from the source user turn.
- Preserve attachments and custom prompt context when editing or regenerating.
- Add hover/focus-visible styling and browser coverage.

## Reply Branches

- Store regenerated assistant replies as siblings rather than replacing the active reply.
- Integrate `BranchPickerPrimitive` with a branch-aware repository.
- Keep in-place regeneration as an explicit temporary fallback if repository integration cannot land atomically.

## File Mentions

- Add a typed `listFiles` host API with bounded query and result limits.
- Resolve relative paths from the configured agent working directory or project root without escaping it.
- Add an `@` typeahead and staged mention chips.
- Send mentions as ACP `resource_link` content blocks and clear them after a successful prompt.
- Cover empty results, limits, cancellation, and path normalization.

## Quote Selection

- Add the assistant-ui selection toolbar and Quote action.
- Render and dismiss pending quotes in the composer and show sent quotes on user messages.
- Preserve quote metadata through the external-store conversion.
- Include the selected text and source message ID in the next ACP prompt.

## Comment on Selection

- Add a Comment action using `useSelectionToolbarInfo()`.
- Capture a comment in an inline selection UI and stage it as dismissible composer context.
- Convert staged selection/comment pairs into ACP content blocks and clear them only after send succeeds.

## Acceptance Criteria

- Edit and Regenerate produce valid ACP prompts without losing attachments or context.
- Branch controls switch between sibling replies and remain keyboard accessible.
- File lookup is bounded and cannot traverse outside the selected root.
- Quotes and comments survive render conversion and reach the agent exactly once.
- An agent without optional capabilities can still chat normally.
- `bun run typecheck` and the ACP Playwright smoke test pass from `demo/webview-src`.
- `./gradlew :demo:buildWebViewAssets :demo:buildPlugin` packages the updated view.
