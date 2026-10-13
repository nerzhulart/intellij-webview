# Publish the WebView npm Packages

Use this maintainer guide to publish the WebView Runtime plugin ZIPs and their matching TypeScript packages from one verified build.

## Prerequisites

- An npm account named `nerzhulart` with a verified email and two-factor authentication.
- Permission to manage repository Actions secrets and the `npm` GitHub environment.
- Permission to configure trusted publishing for both personal-scope npm packages.

The release publishes:

- `@nerzhulart/intellij-webview-sdk`;
- `@nerzhulart/intellij-webview-sdk-testkit`.

## Release Workflow

Releases use the manually triggered **Build and publish release** workflow (`.github/workflows/build.yml`). Provide a SemVer version without a `v` prefix, for example `0.1.0-rc.0` or `0.1.0`.

The workflow runs these jobs:

1. **Build release artifacts** builds and verifies:
   - the runtime, demo, and Markdown preview plugin ZIPs;
   - both npm tarballs;
   - `npm-packages.json`, which records the version, source commit, filenames, and integrity values.
2. **Verify Runtime plugin** and **Verify Markdown plugin** run the IntelliJ Plugin Verifier (`verifyPlugin`) on the built ZIPs against the targeted IntelliJ Platform. These jobs are informational: they run in parallel with publishing, and a failure marks only the job itself, not the workflow.
3. **Publish release** uses the artifacts of the same run without rebuilding them. It creates or updates the GitHub Release `v<version>` with the runtime ZIP, publishes both npm packages, and uploads the runtime and Markdown preview plugins to JetBrains Marketplace.

Prerelease versions are published under the npm `next` dist-tag and the Marketplace `eap` channel, and the GitHub Release is marked as a prerelease. Stable versions are published under `latest` and the default Marketplace channel.

## Configure Publishing Credentials

Open the settings for each package on npmjs.com and add the same GitHub Actions Trusted Publisher:

| Setting | Value |
| --- | --- |
| GitHub user or organization | `nerzhulart` |
| Repository | `intellij-webview` |
| Workflow filename | `build.yml` |
| Environment | `npm` |

The workflow authenticates with short-lived OIDC credentials. Do not create a long-lived npm publish token.

Marketplace uploads use the `PUBLISH_TOKEN` repository secret, which must contain a JetBrains Marketplace permanent token.

## Publish a Release

1. Run **Build and publish release** with the version, for example `0.1.0`.
2. Verify the GitHub Release `v0.1.0` contains the runtime ZIP.
3. Verify both npm packages have version `0.1.0` under the `latest` dist-tag.
4. Check the **Verify Runtime plugin** and **Verify Markdown plugin** results and their `plugin-verifier-reports-*` artifacts.

## Retry a Failed Release

Use GitHub's **Re-run failed jobs** action on the same workflow run so that publishing reuses the already built artifacts. The npm step compares each existing registry artifact with the SHA-512 integrity recorded by the build:

- matching packages are skipped;
- missing packages are published;
- a matching name and version with different integrity stops the release permanently and requires a new version.

The GitHub Release step is also idempotent and replaces the runtime ZIP asset.
