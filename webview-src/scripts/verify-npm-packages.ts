// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { mkdir, readFile, rm, writeFile } from "node:fs/promises"
import { dirname, resolve } from "node:path"
import { fileURLToPath } from "node:url"

interface PackageManifest {
  version: string
  packages: Array<{
    canonicalName: string
    publishedName: string
    file: string
  }>
}

const sourceRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..")
const repositoryRoot = resolve(sourceRoot, "..")
const packageOutputRoot = resolve(repositoryRoot, "build", "npm")
const fixtureRoot = resolve(packageOutputRoot, "verification")
const manifest = JSON.parse(await readFile(resolve(packageOutputRoot, "npm-packages.json"), "utf8")) as PackageManifest
const sdk = requiredPackage("@jetbrains/intellij-webview")
const testkit = requiredPackage("@jetbrains/intellij-webview-testkit")

await rm(fixtureRoot, { recursive: true, force: true })
await Promise.all([
  mkdir(resolve(fixtureRoot, "views", "smoke", "src"), { recursive: true }),
  mkdir(resolve(fixtureRoot, "test", "smoke", "mocks"), { recursive: true }),
])

await Promise.all([
  writeJson(resolve(fixtureRoot, "package.json"), {
    name: "intellij-webview-npm-verification",
    private: true,
    type: "module",
    scripts: {
      build: "node build.mjs",
      typecheck: "tsc -p tsconfig.json --noEmit",
      verify: "node verify.mjs",
    },
    devDependencies: {
      "@jetbrains/intellij-webview": localPackageSpec(sdk.file),
      "@jetbrains/intellij-webview-testkit": localPackageSpec(testkit.file),
      "@types/node": "^22.10.0",
      typescript: "^5.6.0",
      vite: "^8.0.0",
    },
  }),
  writeJson(resolve(fixtureRoot, "tsconfig.json"), {
    extends: "@jetbrains/intellij-webview/tsconfig.view.json",
    compilerOptions: {
      strict: true,
      types: ["node"],
    },
    include: ["views/**/*.ts", "test/**/*.ts", "verify-imports.ts"],
  }),
  writeFile(resolve(fixtureRoot, "views", "smoke", "index.html"), `<!doctype html>
<html>
<head><meta charset="UTF-8"><title>SDK package verification</title></head>
<body><script type="module" src="./src/main.ts"></script></body>
</html>
`, "utf8"),
  writeFile(resolve(fixtureRoot, "views", "smoke", "src", "main.ts"), `import { apiId, webView, type WebViewCallable } from "@jetbrains/intellij-webview"

interface VerificationHostApi extends WebViewCallable {
  ping(): Promise<string>
}

const verificationHostApiId = apiId<VerificationHostApi>()("verification.host")
void webView.callable(verificationHostApiId)
`, "utf8"),
  writeFile(resolve(fixtureRoot, "test", "smoke", "mocks", "default.ts"), `import { defineWebViewMock } from "@jetbrains/intellij-webview-testkit"

export default defineWebViewMock(() => {})
`, "utf8"),
  writeFile(resolve(fixtureRoot, "verify-imports.ts"), `import { apiId, type WebViewCallable } from "@jetbrains/intellij-webview"
import { defineWebViewViewConfig } from "@jetbrains/intellij-webview/vite"
import { defineWebViewMock, startWebViewMockPreview } from "@jetbrains/intellij-webview-testkit"
import { runWebViewMockPreview } from "@jetbrains/intellij-webview-testkit/node"
import { withWebViewMockBridge } from "@jetbrains/intellij-webview-testkit/vite"

void [apiId, defineWebViewViewConfig, defineWebViewMock, startWebViewMockPreview, runWebViewMockPreview, withWebViewMockBridge]
type Verification = WebViewCallable
`, "utf8"),
  writeFile(resolve(fixtureRoot, "build.mjs"), `import { dirname } from "node:path"
import { fileURLToPath } from "node:url"
import { build } from "vite"
import { defineWebViewViewConfig } from "@jetbrains/intellij-webview/vite"

const webviewSrcDir = dirname(fileURLToPath(import.meta.url))
await build(defineWebViewViewConfig({ webviewSrcDir, id: "smoke" }))
`, "utf8"),
  writeFile(resolve(fixtureRoot, "verify.mjs"), `import { dirname, resolve } from "node:path"
import { fileURLToPath } from "node:url"
import { apiId } from "@jetbrains/intellij-webview"
import { defineWebViewMock, startWebViewMockPreview } from "@jetbrains/intellij-webview-testkit"

const fixtureRoot = dirname(fileURLToPath(import.meta.url))
const id = apiId()("verification")
if (id.namespace !== "verification" || typeof defineWebViewMock !== "function") {
  throw new Error("SDK or testkit root export is unavailable")
}

const preview = await startWebViewMockPreview({
  webviewSrcDir: fixtureRoot,
  viewId: "smoke",
  mock: resolve(fixtureRoot, "test", "smoke", "mocks", "default.ts"),
})
try {
  const html = await fetch(preview.url).then(response => response.text())
  if (!html.includes("/__webview/wvi-bridge.js")) throw new Error("Preview HTML does not contain the common bridge")
  const bridge = await fetch(new URL("/__webview/wvi-bridge.js", preview.url)).then(response => response.text())
  if (!bridge.includes("window.__WVI__")) throw new Error("Packaged Vite helper did not serve the common bridge")
  const platformFeatures = await fetch(new URL("/__webview/wvi-platform-features.js", preview.url)).then(response => response.text())
  if (!platformFeatures.includes("installWebViewPlatformFeatures")) {
    throw new Error("Packaged Vite helper did not serve the common platform features")
  }
}
finally {
  await preview.close()
}
`, "utf8"),
])

await runCommand([npmExecutable(), "install", "--ignore-scripts"], fixtureRoot)
await runCommand([npmExecutable(), "run", "typecheck"], fixtureRoot)
await runCommand([npmExecutable(), "run", "build"], fixtureRoot)
await runCommand([npmExecutable(), "run", "verify"], fixtureRoot)

console.log(`Verified clean npm packages for ${manifest.version} in ${fixtureRoot}`)

function requiredPackage(canonicalName: string): PackageManifest["packages"][number] {
  const npmPackage = manifest.packages.find(candidate => candidate.canonicalName === canonicalName)
  if (!npmPackage) throw new Error(`Missing ${canonicalName} in npm-packages.json`)
  return npmPackage
}

function localPackageSpec(file: string): string {
  return `file:${resolve(packageOutputRoot, file).replace(/\\/g, "/")}`
}

async function writeJson(path: string, value: unknown): Promise<void> {
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, "utf8")
}

async function runCommand(command: string[], cwd: string): Promise<void> {
  const subprocess = Bun.spawn({ cmd: command, cwd, stdout: "inherit", stderr: "inherit" })
  const exitCode = await subprocess.exited
  if (exitCode !== 0) throw new Error(`${command.join(" ")} failed with exit code ${exitCode}`)
}

function npmExecutable(): string {
  return process.platform === "win32" ? "npm.cmd" : "npm"
}
