# WebView Controls

WebView Runtime provides two private frontend packages for IDE-styled UI.

## Web Components

`@nerzhulart/webview-controls` exposes 27 `jb-*` custom elements. Register all controls with one side-effect import:

```ts
import "@nerzhulart/webview-controls/define/all"
```

Then use standard HTML:

```html
<jb-field>
  <jb-label>Branch</jb-label>
  <jb-text-field value="main"></jb-text-field>
</jb-field>
<jb-button>Apply</jb-button>
```

For smaller bundles, import individual definitions such as `@nerzhulart/webview-controls/elements/button`. The package also exports `tokens.css`, `tokens.json`, `custom-elements.json`, and JSX typings.

Available elements include buttons, checkboxes, text and number inputs, select/combobox controls, radio groups, segmented controls, sliders, tabs, field composition, labels/help text, menus, disclosure, separators, spinners, text, and icons. The package source and `custom-elements.json` are the authoritative API list.

## React Controls

`@nerzhulart/webview-react-controls` provides React-specific composites and helpers for:

- control chrome;
- focus handling;
- menus;
- popovers;
- portals;
- selects;
- tooltips.

Import its styles and the components you use:

```tsx
import "@nerzhulart/webview-react-controls/styles.css"
import { JbSelect, JbSelectItem } from "@nerzhulart/webview-react-controls"
```

React and React DOM are peer dependencies. The React package can be used together with the `jb-*` elements; register the Web Components separately when a view renders them.

## Icons and Theme

Controls consume the theme variables installed by the common WebView runtime. For classpath icons, register a Kotlin `WebViewIconSet` and use the matching frontend `IconSet`; see [IconSet Loading](WebView-IconSet-Loading.md).

## Examples and Verification

The demo views `controls-showcase` and `react-controls-showcase` exercise the current public surface.

```shell
cd webview-src
bun run build:controls
bun run build:react-controls
```

Consumer views must also run their own typecheck and browser smoke tests.
