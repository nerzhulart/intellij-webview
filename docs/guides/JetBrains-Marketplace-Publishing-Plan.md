# JetBrains Marketplace Publishing

Status: partially implemented. The `publish` job of the **Build and publish release** workflow (`.github/workflows/build.yml`) already uploads the Runtime and Markdown Lens ZIPs with `publishPlugin` and the `PUBLISH_TOKEN` secret. The per-plugin matrix, the existing-version check, and the protected `marketplace` environment described below are not implemented yet.

## Goal

Extend the existing **Build and publish release** workflow so that it uploads the already verified Runtime and Markdown Lens ZIPs to JetBrains Marketplace. The workflow must not rebuild plugin archives, and the Demo plugin must not be uploaded to Marketplace.

## Plugin Identity

The Marketplace listings will use the personal `nerzhulart` vendor profile and the following XML IDs:

| Distribution | Marketplace XML ID |
| --- | --- |
| WebView Runtime | `io.github.nerzhulart.webview` |
| Markdown Lens | `io.github.nerzhulart.markdown` |
| WebView Demo | `io.github.nerzhulart.webview.demo` |

Use the same `io.github.nerzhulart.webview` namespace for project-owned Kotlin and Java packages, module names, generated dependency descriptors, consumer examples, and documentation.

The Demo XML ID is renamed to keep distributed metadata under the same personal namespace, but its ZIP is not uploaded to Marketplace.

## Release Workflow

Replace the Marketplace step of the `publish` job in `.github/workflows/build.yml` with a Marketplace matrix job that runs after the existing publish job succeeds. Use one matrix entry per published plugin so that a partial failure can be retried without resending the successful plugin.

Each matrix entry must:

1. Download the `webview-plugin-zips` artifact from the same workflow run.
2. Use the validated release version input.
3. Find exactly one expected archive:
   - Runtime: `webview-<version>.zip`;
   - Markdown Lens: `markdown-webview-preview-<version>.zip`.
4. Select the Marketplace channel from the version:
   - stable SemVer versions use the default channel;
   - prerelease versions use the `eap` channel.
5. Query the corresponding Marketplace repository feed by XML ID. If that channel already contains the exact version, report it as already published and exit successfully.
6. Otherwise, upload the archive with `POST https://plugins.jetbrains.com/api/updates/upload`, an `Authorization: Bearer` header, and multipart `xmlId` and `file` fields. Include `channel=eap` only for prereleases.
7. Write the XML ID, version, channel, and upload result to the GitHub job summary without printing the token.

Configure the matrix with `fail-fast: false`. Do not automatically retry the upload POST after an ambiguous network failure because the Marketplace may already have accepted it. Retry only the failed matrix job after checking its Marketplace listing.

## Credentials and Bootstrap

Create a protected GitHub environment named `marketplace`, restrict it to the `master` branch, and store a Marketplace permanent token as `JETBRAINS_MARKETPLACE_TOKEN`. Expose the secret only to the Marketplace job. No plugin signing key or certificate is added in this phase.

JetBrains Marketplace requires the first version of each plugin to be uploaded manually. Bootstrap publishing as follows:

1. Create the `nerzhulart` Marketplace vendor profile.
2. Download the Runtime and Markdown Lens ZIPs for the first release version from a **Build and publish release** run.
3. Use **Upload plugin** to create both listings with the XML IDs above, the repository's Apache 2.0 license, the source repository URL, and the names and categories from their descriptors.
4. Use the `eap` custom channel when the bootstrap version is a prerelease; otherwise use the default channel.
5. Wait until both versions are visible in the corresponding repository feed.
6. Run **Build and publish release** for the same version. The Marketplace jobs must detect the manual versions and skip their uploads while the existing npm and GitHub release steps complete normally.

All later releases use the same workflow and require no manual Marketplace upload.

## Failure Handling

- A missing token, missing listing, unexpected archive count, or version mismatch fails before upload.
- An existing version in the selected channel is treated as success.
- Marketplace moderation is asynchronous; a successful API response means that an update was accepted, not necessarily that it is already publicly visible.
- If only one matrix entry fails, use GitHub's **Re-run failed jobs** action. Do not dispatch a new full release solely to retry that plugin.
- Existing npm and GitHub Release retry behavior remains unchanged.

## Acceptance Criteria

- All three built plugins contain the new XML IDs and correct Runtime dependency references.
- A prerelease publishes Runtime and Markdown Lens to `eap`; Demo appears only in the GitHub Release.
- A stable release publishes Runtime and Markdown Lens to the default Marketplace channel.
- The Marketplace jobs use the exact ZIPs produced by the build job of the same run and never rebuild them.
- A repeated run skips versions already present in the selected channel.
- One failed plugin upload can be retried independently.
- The Marketplace token never appears in logs or release artifacts.
- Plugin author signing remains explicitly out of scope for this phase.
