# WebView Frontend Framework Policy

WebView Runtime does not require a frontend framework. The typed bridge, theme integration, icons, build helpers, and testkit work with plain TypeScript, Web Components, React, or another Vite-compatible stack.

## Shared UI

Use Web Components for reusable framework-neutral controls. `@jetbrains/intellij-webview-controls` is implemented with Lit but exposes standard `jb-*` custom elements; consumers do not need to adopt Lit as their application framework.

Use `@jetbrains/intellij-webview-react-controls` when a React view needs React-specific composite behavior such as menus, popovers, selects, portals, focus helpers, or tooltips. It complements the Web Components package instead of replacing it.

## Selection Guidance

| Need | Recommended choice |
| --- | --- |
| Small page with limited state | TypeScript and Web Components |
| Shared controls used by multiple frameworks | `@jetbrains/intellij-webview-controls` |
| Existing React feature or complex React state | React plus the React controls package |
| Browser preview and host mocks | Framework of choice plus the testkit |

## Boundary Rules

- Keep `WebViewApi` contracts and DTOs independent of UI framework state.
- Keep bridge registration in a small boundary module.
- Do not expose React objects, DOM nodes, or framework stores over JSON-RPC.
- Avoid adding a second component library for a control already covered by the shared packages unless the feature has a concrete unmet requirement.
- Import only the controls used by the view, or use `define/all` when bundle size and registration cost are acceptable.

See [WebView Controls](WebView-Controls.md) and [View Model Patterns](WebView-Frontend-View-Model-Patterns.md).
