// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64

private val LOG = logger<MarkdownImageAssetSaver>()

internal object MarkdownImageAssetSaver {
  private const val ASSETS_DIRECTORY_NAME = "assets"
  private const val DEFAULT_STEM = "image"
  private const val DEFAULT_EXTENSION = "png"

  private val ALLOWED_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "avif")

  private val MIME_EXTENSION_MAP = mapOf(
    "image/png" to "png",
    "image/jpeg" to "jpg",
    "image/jpg" to "jpg",
    "image/gif" to "gif",
    "image/webp" to "webp",
    "image/svg+xml" to "svg",
    "image/bmp" to "bmp",
    "image/avif" to "avif",
  )

  suspend fun saveImageAsset(
    project: Project?,
    markdownFile: VirtualFile?,
    isEditablePreview: Boolean = true,
    params: MarkdownSaveImageAssetParams,
  ): MarkdownSaveImageAssetResultParams {
    return withContext(Dispatchers.EDT) {
      saveImageAssetOnEdt(
        project = project,
        markdownFile = markdownFile,
        isEditablePreview = isEditablePreview,
        params = params,
      )
    }
  }

  fun saveImageAssetOnEdt(
    project: Project?,
    markdownFile: VirtualFile?,
    isEditablePreview: Boolean = true,
    params: MarkdownSaveImageAssetParams,
  ): MarkdownSaveImageAssetResultParams {
    if (!isEditablePreview || project == null || project.isDisposed) {
      return MarkdownSaveImageAssetResultParams(
        saved = false,
        errorMessage = "Document is not editable",
      )
    }

    val parentDirectory = markdownFile?.parent?.takeIf { it.isValid && it.isDirectory && it.isWritable }
      ?: return MarkdownSaveImageAssetResultParams(
        saved = false,
        errorMessage = "Markdown file directory is not writable",
      )

    val bytes = decodeBase64Payload(params.base64Bytes)
      ?: return MarkdownSaveImageAssetResultParams(
        saved = false,
        errorMessage = "Invalid base64 image payload",
      )
    if (bytes.isEmpty()) {
      return MarkdownSaveImageAssetResultParams(
        saved = false,
        errorMessage = "Image payload is empty",
      )
    }

    val (stem, extension) = resolveStemAndExtension(params.fileNameHint, params.mimeType)
    val commandName = MarkdownWebViewPreviewBundle.message("markdown.preview.edit.save.image.command.name")

    return try {
      var relativePath: String? = null
      WriteCommandAction.writeCommandAction(project)
        .withName(commandName)
        .run<Exception> {
          val assetsDir = VfsUtil.createDirectoryIfMissing(parentDirectory, ASSETS_DIRECTORY_NAME)
            ?: throw IllegalStateException("Failed to create '$ASSETS_DIRECTORY_NAME' directory")
          val targetFileName = uniqueFileName(assetsDir, stem, extension)
          val file = assetsDir.createChildData(this, targetFileName)
          file.setBinaryContent(bytes)
          relativePath = "$ASSETS_DIRECTORY_NAME/$targetFileName"
        }
      MarkdownSaveImageAssetResultParams(
        saved = true,
        relativePath = relativePath,
      )
    }
    catch (t: Throwable) {
      LOG.warn("Failed to save pasted Markdown image asset", t)
      MarkdownSaveImageAssetResultParams(
        saved = false,
        errorMessage = t.message ?: "Failed to save image asset",
      )
    }
  }

  fun formatMarkdownImageReference(relativePath: String, altText: String = ""): String {
    val sanitizedAlt = altText.replace("[", "\\[").replace("]", "\\]")
    return "![$sanitizedAlt]($relativePath)"
  }

  private fun decodeBase64Payload(rawPayload: String): ByteArray? {
    val trimmed = rawPayload.trim()
    if (trimmed.isEmpty()) return null
    val base64Content = if (trimmed.contains("base64,")) {
      trimmed.substringAfter("base64,").trim()
    }
    else {
      trimmed
    }
    if (base64Content.isEmpty()) return null
    return try {
      Base64.getMimeDecoder().decode(base64Content)
    }
    catch (_: IllegalArgumentException) {
      null
    }
  }

  private fun resolveStemAndExtension(fileNameHint: String, mimeType: String): Pair<String, String> {
    val cleanName = fileNameHint.trim().substringAfterLast('/').substringAfterLast('\\')
    val dotIndex = cleanName.lastIndexOf('.')
    val rawStem = if (dotIndex > 0) cleanName.substring(0, dotIndex) else cleanName
    val rawExtension = if (dotIndex >= 0 && dotIndex + 1 < cleanName.length) {
      cleanName.substring(dotIndex + 1).lowercase()
    }
    else {
      ""
    }

    val normalizedMime = mimeType.trim().lowercase().substringBefore(';')
    val extension = when {
      rawExtension in ALLOWED_EXTENSIONS -> if (rawExtension == "jpeg") "jpg" else rawExtension
      normalizedMime in MIME_EXTENSION_MAP -> MIME_EXTENSION_MAP.getValue(normalizedMime)
      else -> DEFAULT_EXTENSION
    }

    val sanitizedStem = rawStem
      .replace(Regex("[^a-zA-Z0-9._-]+"), "-")
      .trim('-', '.', '_')
      .take(64)
      .ifEmpty { DEFAULT_STEM }
      .let { if (it.equals("blob", ignoreCase = true)) DEFAULT_STEM else it }

    return sanitizedStem to extension
  }

  private fun uniqueFileName(assetsDir: VirtualFile, stem: String, extension: String): String {
    val initialCandidate = "$stem.$extension"
    if (assetsDir.findChild(initialCandidate) == null) {
      return initialCandidate
    }
    var index = 1
    while (true) {
      val candidate = "$stem-$index.$extension"
      if (assetsDir.findChild(candidate) == null) {
        return candidate
      }
      index++
    }
  }
}
