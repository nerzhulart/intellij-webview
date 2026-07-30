// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

import { createHash } from "node:crypto"
import { copyFile, mkdir, readFile, rm, writeFile } from "node:fs/promises"
import { dirname, resolve } from "node:path"
import { fileURLToPath } from "node:url"

const sourceRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..")
const repositoryRoot = resolve(sourceRoot, "..")
const outputRoot = resolve(repositoryRoot, "build", "npm")
const stagingRoot = resolve(outputRoot, "staging")
const sdkStage = resolve(stagingRoot, "webview-sdk")
const testkitStage = resolve(stagingRoot, "webview-testkit")
const version = requiredPackageVersion()
const commitSha = await resolveCommitSha()

await rm(outputRoot, { recursive: true, force: true })
await Promise.all([
  mkdir(resolve(sdkStage, "dist", "api"), { recursive: true }),
  mkdir(resolve(sdkStage, "dist", "vite"), { recursive: true }),
  mkdir(resolve(sdkStage, "runtime-assets"), { recursive: true }),
  mkdir(resolve(testkitStage, "dist"), { recursive: true }),
])

await buildRuntimeAssets()
await emitDeclarations()
await buildJavaScript()
await writePackageFiles()

const packages = await Promise.all([
  packPackage({
    canonicalName: "@nerzhulart/webview-sdk",
    publishedName: "@nerzhulart/webview-sdk",
    stage: sdkStage,
    requiredFiles: [
      "dist/api/index.js",
      "dist/vite/index.js",
      "types/api/src/index.d.ts",
      "types/build/src/index.d.ts",
      "runtime-assets/wvi-bridge.js",
      "runtime-assets/wvi-platform-features.js",
      "tsconfig.view.json",
      "LICENSE",
      "README.md",
    ],
  }),
  packPackage({
    canonicalName: "@nerzhulart/webview-testkit",
    publishedName: "@nerzhulart/webview-testkit",
    stage: testkitStage,
    requiredFiles: [
      "dist/index.js",
      "dist/node.js",
      "dist/vite.js",
      "dist/cli.js",
      "types/index.d.ts",
      "types/node.d.ts",
      "types/vite.d.ts",
      "LICENSE",
      "README.md",
    ],
  }),
])

const manifest = {
  schemaVersion: 1,
  version,
  commitSha,
  packages: packages.sort((left, right) => left.publishedName.localeCompare(right.publishedName)),
}
await writeJson(resolve(outputRoot, "npm-packages.json"), manifest)

console.log(`Created npm packages for ${version}:`)
for (const npmPackage of manifest.packages) {
  console.log(`  ${npmPackage.publishedName}: ${npmPackage.file}`)
}

async function buildRuntimeAssets(): Promise<void> {
  await runCommand([bunExecutable(), "run", "build:bridge"], {
    cwd: sourceRoot,
    env: {
      ...process.env,
      WEBVIEW_OUTPUT_ROOT: resolve(sdkStage, "runtime-assets"),
    },
  })
}

async function emitDeclarations(): Promise<void> {
  await runCommand([
    bunExecutable(),
    "x",
    "tsc",
    "-p",
    resolve(sourceRoot, "tsconfig.npm-sdk.json"),
    "--declarationDir",
    resolve(sdkStage, "types"),
  ], { cwd: sourceRoot })
  await runCommand([
    bunExecutable(),
    "x",
    "tsc",
    "-p",
    resolve(sourceRoot, "tsconfig.npm-testkit.json"),
    "--declarationDir",
    resolve(testkitStage, "types"),
  ], { cwd: sourceRoot })
}

async function buildJavaScript(): Promise<void> {
  await Promise.all([
    checkedBunBuild({
      entrypoints: [resolve(sourceRoot, "packages", "api", "src", "index.ts")],
      outdir: resolve(sdkStage, "dist", "api"),
      target: "browser",
    }),
    checkedBunBuild({
      entrypoints: [resolve(sourceRoot, "packages", "build", "src", "index.ts")],
      outdir: resolve(sdkStage, "dist", "vite"),
      target: "node",
      packages: "external",
    }),
    checkedBunBuild({
      entrypoints: [
        resolve(sourceRoot, "packages", "testkit", "src", "index.ts"),
        resolve(sourceRoot, "packages", "testkit", "src", "node.ts"),
        resolve(sourceRoot, "packages", "testkit", "src", "vite.ts"),
        resolve(sourceRoot, "packages", "testkit", "src", "cli.ts"),
      ],
      outdir: resolve(testkitStage, "dist"),
      target: "node",
      packages: "external",
      external: ["@nerzhulart/webview-sdk", "@nerzhulart/webview-sdk/*", "vite"],
      splitting: true,
      naming: {
        entry: "[name].[ext]",
        chunk: "chunks/[name]-[hash].[ext]",
        asset: "assets/[name]-[hash].[ext]",
      },
    }),
  ])
}

async function checkedBunBuild(options: Parameters<typeof Bun.build>[0]): Promise<void> {
  const result = await Bun.build({
    bundle: true,
    format: "esm",
    minify: false,
    sourcemap: "none",
    ...options,
  })
  if (!result.success) {
    throw new Error(result.logs.map(log => log.message).join("\n"))
  }
}

async function writePackageFiles(): Promise<void> {
  await Promise.all([
    writeJson(resolve(sdkStage, "package.json"), sdkPackageJson()),
    writeJson(resolve(testkitStage, "package.json"), testkitPackageJson()),
    copyFile(resolve(sourceRoot, "tsconfig.view.json"), resolve(sdkStage, "tsconfig.view.json")),
    copyFile(resolve(repositoryRoot, "LICENSE"), resolve(sdkStage, "LICENSE")),
    copyFile(resolve(repositoryRoot, "LICENSE"), resolve(testkitStage, "LICENSE")),
    writePackageReadme(resolve(sourceRoot, "npm", "README.sdk.md"), resolve(sdkStage, "README.md")),
    writePackageReadme(resolve(sourceRoot, "npm", "README.testkit.md"), resolve(testkitStage, "README.md")),
  ])
}

async function writePackageReadme(templatePath: string, outputPath: string): Promise<void> {
  const template = await readFile(templatePath, "utf8")
  await writeFile(outputPath, template.replaceAll("{{VERSION}}", version), "utf8")
}

function sdkPackageJson(): Record<string, unknown> {
  return {
    name: "@nerzhulart/webview-sdk",
    version,
    description: "TypeScript SDK for IntelliJ WebView",
    type: "module",
    license: "Apache-2.0",
    repository: repositoryMetadata("webview-src"),
    homepage: "https://github.com/nerzhulart/intellij-webview#readme",
    bugs: { url: "https://github.com/nerzhulart/intellij-webview/issues" },
    keywords: ["intellij", "jetbrains", "webview", "typescript"],
    sideEffects: false,
    exports: {
      ".": {
        types: "./types/api/src/index.d.ts",
        import: "./dist/api/index.js",
        default: "./dist/api/index.js",
      },
      "./vite": {
        types: "./types/build/src/index.d.ts",
        import: "./dist/vite/index.js",
        default: "./dist/vite/index.js",
      },
      "./tsconfig.view.json": "./tsconfig.view.json",
    },
    files: ["dist", "types", "runtime-assets", "tsconfig.view.json", "README.md", "LICENSE"],
    peerDependencies: { vite: "^8.0.0" },
    peerDependenciesMeta: { vite: { optional: true } },
    engines: { node: "^20.19.0 || >=22.12.0" },
    publishConfig: { access: "public", registry: "https://registry.npmjs.org/" },
  }
}

function testkitPackageJson(): Record<string, unknown> {
  return {
    name: "@nerzhulart/webview-testkit",
    version,
    description: "Browser mock and preview testkit for the IntelliJ WebView TypeScript SDK",
    type: "module",
    license: "Apache-2.0",
    repository: repositoryMetadata("webview-src/packages/testkit"),
    homepage: "https://github.com/nerzhulart/intellij-webview#readme",
    bugs: { url: "https://github.com/nerzhulart/intellij-webview/issues" },
    keywords: ["intellij", "jetbrains", "webview", "testing", "vite"],
    sideEffects: false,
    exports: {
      ".": {
        types: "./types/index.d.ts",
        import: "./dist/index.js",
        default: "./dist/index.js",
      },
      "./node": {
        types: "./types/node.d.ts",
        import: "./dist/node.js",
        default: "./dist/node.js",
      },
      "./vite": {
        types: "./types/vite.d.ts",
        import: "./dist/vite.js",
        default: "./dist/vite.js",
      },
    },
    bin: { "webview-preview": "./dist/cli.js" },
    files: ["dist", "types", "README.md", "LICENSE"],
    peerDependencies: {
      "@nerzhulart/webview-sdk": version,
      vite: "^8.0.0",
    },
    engines: {
      bun: ">=1.3.14",
      node: "^20.19.0 || >=22.12.0",
    },
    publishConfig: { access: "public", registry: "https://registry.npmjs.org/" },
  }
}

function repositoryMetadata(directory: string): Record<string, string> {
  return {
    type: "git",
    url: "git+https://github.com/nerzhulart/intellij-webview.git",
    directory,
  }
}

interface PackOptions {
  canonicalName: string
  publishedName: string
  stage: string
  requiredFiles: string[]
}

interface PackedPackage {
  canonicalName: string
  publishedName: string
  file: string
  integrity: string
  sha256: string
}

interface NpmPackResult {
  filename: string
  files: Array<{ path: string }>
}

async function packPackage(options: PackOptions): Promise<PackedPackage> {
  const dryRun = parseNpmPackResult(await runCommand([
    npmExecutable(),
    "pack",
    options.stage,
    "--dry-run",
    "--json",
  ], { cwd: sourceRoot }))
  validatePackageContents(options, dryRun.files.map(file => file.path))

  const packed = parseNpmPackResult(await runCommand([
    npmExecutable(),
    "pack",
    options.stage,
    "--pack-destination",
    outputRoot,
    "--json",
  ], { cwd: sourceRoot }))
  const archive = resolve(outputRoot, packed.filename)
  const bytes = await readFile(archive)
  return {
    canonicalName: options.canonicalName,
    publishedName: options.publishedName,
    file: packed.filename,
    integrity: `sha512-${createHash("sha512").update(bytes).digest("base64")}`,
    sha256: createHash("sha256").update(bytes).digest("hex"),
  }
}

function validatePackageContents(options: PackOptions, files: string[]): void {
  for (const requiredFile of options.requiredFiles) {
    if (!files.includes(requiredFile)) {
      throw new Error(`${options.publishedName} is missing required package file ${requiredFile}`)
    }
  }
  const forbiddenFile = files.find(file =>
    (!file.startsWith("types/") && file.includes("/src/")) ||
    file.endsWith(".test.js") ||
    file.endsWith(".test.ts") ||
    (file.endsWith(".ts") && !file.endsWith(".d.ts")),
  )
  if (forbiddenFile) {
    throw new Error(`${options.publishedName} contains forbidden source/test file ${forbiddenFile}`)
  }
}

function parseNpmPackResult(stdout: string): NpmPackResult {
  const parsed = JSON.parse(stdout) as NpmPackResult[]
  if (!Array.isArray(parsed) || parsed.length !== 1 || !parsed[0]?.filename || !Array.isArray(parsed[0].files)) {
    throw new Error(`Unexpected npm pack output: ${stdout}`)
  }
  return parsed[0]
}

async function writeJson(path: string, value: unknown): Promise<void> {
  await mkdir(dirname(path), { recursive: true })
  await writeFile(path, `${JSON.stringify(value, null, 2)}\n`, "utf8")
}

async function resolveCommitSha(): Promise<string> {
  const configured = process.env.GITHUB_SHA?.trim()
  if (configured) return configured
  return (await runCommand(["git", "rev-parse", "HEAD"], { cwd: repositoryRoot })).trim()
}

function requiredPackageVersion(): string {
  const configured = process.env.NPM_PACKAGE_VERSION?.trim()
  if (!configured) {
    throw new Error("NPM_PACKAGE_VERSION is required, for example NPM_PACKAGE_VERSION=0.1.0-rc.0 bun run pack:npm")
  }
  const semver = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/
  const match = semver.exec(configured)
  if (!match) {
    throw new Error(`NPM_PACKAGE_VERSION must be a valid SemVer version, got ${configured}`)
  }
  const prerelease = match[4]
  if (prerelease?.split(".").some(identifier => /^\d+$/.test(identifier) && identifier.length > 1 && identifier.startsWith("0"))) {
    throw new Error(`NPM_PACKAGE_VERSION contains a numeric prerelease identifier with a leading zero: ${configured}`)
  }
  return configured
}

interface CommandOptions {
  cwd: string
  env?: Record<string, string | undefined>
}

async function runCommand(command: string[], options: CommandOptions): Promise<string> {
  const subprocess = Bun.spawn({
    cmd: command,
    cwd: options.cwd,
    env: options.env,
    stdout: "pipe",
    stderr: "pipe",
  })
  const [stdout, stderr, exitCode] = await Promise.all([
    new Response(subprocess.stdout).text(),
    new Response(subprocess.stderr).text(),
    subprocess.exited,
  ])
  if (exitCode !== 0) {
    throw new Error(`${command.join(" ")} failed with exit code ${exitCode}\n${stdout}${stderr}`)
  }
  if (stderr.trim()) process.stderr.write(stderr)
  return stdout
}

function bunExecutable(): string {
  return process.platform === "win32" ? "bun.exe" : "bun"
}

function npmExecutable(): string {
  return process.platform === "win32" ? "npm.cmd" : "npm"
}
