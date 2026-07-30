# Publish the WebView npm Packages

Use this maintainer guide to publish the WebView Runtime plugin ZIPs and their matching TypeScript packages from one verified build.

## Prerequisites

- An npm account named `nerzhulart` with a verified email and two-factor authentication.
- Permission to manage repository Actions secrets and the `npm` GitHub environment.
- Permission to configure trusted publishing for both personal-scope npm packages.

The release publishes:

- `@nerzhulart/webview-sdk`;
- `@nerzhulart/webview-testkit`.

## Build a Release Candidate

Run the **Build plugins** workflow and provide a SemVer version without a `v` prefix, for example `0.1.0-rc.0`. The workflow builds and verifies:

- the runtime, demo, and Markdown preview plugin ZIPs;
- both npm tarballs;
- `npm-packages.json`, which records the version, source commit, filenames, and integrity values.

Record the successful workflow run ID. The publish workflow accepts only artifacts from a successful **Build plugins** run.

## Bootstrap the npm Packages Once

Trusted Publisher settings become available only after each npm package exists. Use a temporary granular token for the first release:

1. Create a short-lived granular npm access token with read/write access to packages owned by `nerzhulart`.
2. Add it to the `npm` GitHub environment as `NPM_BOOTSTRAP_TOKEN`.
3. Run **Publish selected build** with the build run ID, tag `v0.1.0-rc.0`, `prerelease=true`, and `bootstrap_npm=true`.
4. Confirm that both packages were published with the `next` dist-tag.

Do not build or publish tarballs locally for bootstrap. The workflow publishes the artifacts already verified by the selected build.

## Enable Trusted Publishing

Open the settings for each package on npmjs.com and add the same GitHub Actions Trusted Publisher:

| Setting | Value |
| --- | --- |
| GitHub user or organization | `nerzhulart` |
| Repository | `intellij-webview` |
| Workflow filename | `release.yml` |
| Environment | `npm` |

After both configurations are saved:

1. delete the `NPM_BOOTSTRAP_TOKEN` GitHub secret;
2. revoke the granular token on npmjs.com;
3. use `bootstrap_npm=false` for every later release.

The workflow then authenticates with short-lived OIDC credentials. Do not create a replacement long-lived publish token.

## Publish a Normal Release

1. Run **Build plugins** with the stable version, for example `0.1.0`.
2. Run **Publish selected build** with its run ID, tag `v0.1.0`, `prerelease=false`, and `bootstrap_npm=false`.
3. Verify the GitHub Release contains three plugin ZIPs.
4. Verify both npm packages have version `0.1.0` under the `latest` dist-tag.

Prerelease versions must use `prerelease=true` and are published under `next`. Stable versions must use `prerelease=false` and are published under `latest`.

## Retry a Failed Release

Rerun **Publish selected build** with the same inputs and build run ID. The workflow compares each existing registry artifact with the SHA-512 integrity recorded by the build:

- matching packages are skipped;
- missing packages are published;
- a matching name and version with different integrity stops the release permanently and requires a new version.

The GitHub Release step is also idempotent and reconciles the three ZIP assets after npm publication succeeds.
