// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview

import com.intellij.ide.DataManager
import com.intellij.model.Pointer
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.BaseProjectDirectories
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.platform.ide.navigation.NavigationOptions
import com.intellij.platform.ide.navigation.NavigationService
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Point
import java.nio.file.Path
import javax.swing.JComponent
import kotlin.math.min

// Keep this in sync with community/plugins/ui.webview/demo/src/io/github/nerzhulart/webview/demo/acp/AcpPathLinkResolver.kt; extraction is intentionally deferred.
internal class MarkdownPreviewPathLinkResolver(
  private val project: Project,
  private val scope: CoroutineScope,
) {
  suspend fun resolve(rawPath: String, sourceFile: VirtualFile?): List<PathNavigationTarget> {
    val parsedPath = parsePath(rawPath) ?: return emptyList()
    val absoluteFile = withContext(Dispatchers.IO) { findAbsoluteFile(parsedPath.path) }

    return readAction {
      val baseDirectories = project.getBaseDirectories()
      val sourceDirectory = sourceFile?.parent?.takeIf { it.isValid }
      val baseProjectDirectories = BaseProjectDirectories.getInstance(project)
      val fileIndex = ProjectFileIndex.getInstance(project)
      val psiManager = PsiManager.getInstance(project)
      val candidates = linkedMapOf<String, PathNavigationTarget>()

      fun add(file: VirtualFile) {
        if (!file.isValid || !isProjectFile(file, fileIndex, psiManager, baseProjectDirectories)) return
        candidates[file.path] = PathNavigationTarget(
          project = project,
          file = file,
          displayPath = displayPath(file, baseDirectories),
          line = parsedPath.line,
          column = parsedPath.column,
        )
      }

      findExactFiles(parsedPath.path, sourceDirectory, baseDirectories, absoluteFile).forEach(::add)
      findIndexedFiles(parsedPath.path, baseDirectories).forEach(::add)
      candidates.values.toList()
    }
  }

  suspend fun suggestRelativeFilePaths(
    query: String,
    sourceFile: VirtualFile?,
    limit: Int = DEFAULT_SUGGESTION_LIMIT,
  ): List<String> {
    if (limit <= 0) return emptyList()
    val maxResults = limit.coerceAtMost(MAX_SUGGESTION_LIMIT)
    val normalizedQuery = FileUtil.toSystemIndependentName(query.trim())
      .removePrefix("./")
      .trimStart('/')
    val lowerQuery = normalizedQuery.lowercase()

    return readAction {
      if (project.isDisposed) return@readAction emptyList()
      val baseDirectories = project.getBaseDirectories()
      val sourceDirectory = sourceFile?.parent?.takeIf { it.isValid }
      val baseProjectDirectories = BaseProjectDirectories.getInstance(project)
      val fileIndex = ProjectFileIndex.getInstance(project)
      val psiManager = PsiManager.getInstance(project)
      val matchedEntries = LinkedHashMap<String, SuggestedFileEntry>()

      fun consider(file: VirtualFile) {
        if (!file.isValid || file.isDirectory || file == sourceFile) return
        if (!isProjectFile(file, fileIndex, psiManager, baseProjectDirectories)) return

        val relativePath = relativePathFromSource(file, sourceDirectory, baseDirectories)
        if (relativePath.isBlank()) return
        val projectDisplayPath = displayPath(file, baseDirectories)
        if (lowerQuery.isNotEmpty()) {
          val matchesRelative = relativePath.lowercase().contains(lowerQuery)
          val matchesName = file.name.lowercase().contains(lowerQuery)
          val matchesDisplay = projectDisplayPath.lowercase().contains(lowerQuery)
          if (!matchesRelative && !matchesName && !matchesDisplay) return
        }

        matchedEntries.putIfAbsent(
          relativePath,
          SuggestedFileEntry(
            relativePath = relativePath,
            fileName = file.name,
          ),
        )
      }

      if (normalizedQuery.isNotEmpty()) {
        findExactFiles(normalizedQuery, sourceDirectory, baseDirectories, absoluteFile = null).forEach(::consider)
        findIndexedFiles(normalizedQuery, baseDirectories).forEach(::consider)
      }

      fileIndex.iterateContent { file ->
        consider(file)
        true
      }

      matchedEntries.values
        .sortedWith(suggestionComparator(lowerQuery))
        .take(maxResults)
        .map { it.relativePath }
    }
  }

  suspend fun navigate(rawPath: String, sourceFile: VirtualFile?, component: JComponent, clientX: Int, clientY: Int) {
    val candidates = resolve(rawPath, sourceFile)
    if (candidates.isEmpty()) return

    withContext(Dispatchers.EDT) {
      if (candidates.size == 1) {
        navigateTo(candidates.single(), component)
        return@withContext
      }

      val popup = JBPopupFactory.getInstance()
        .createPopupChooserBuilder(candidates)
        .setTitle(MarkdownWebViewPreviewBundle.message("markdown.preview.path.link.popup.title"))
        .setRenderer(textListCellRenderer { it.displayPath })
        .setItemChosenCallback { scope.launch { navigateTo(it, component) } }
        .createPopup()
      popup.show(RelativePoint(component, Point(clientX, clientY)))
    }
  }

  private suspend fun navigateTo(target: PathNavigationTarget, component: JComponent) {
    val request = readAction { target.navigationRequest() } ?: return
    withContext(Dispatchers.EDT) {
      val dataContext = DataManager.getInstance().getDataContext(component)
      project.serviceAsync<NavigationService>().navigate(request, NavigationOptions.defaultOptions().requestFocus(true), dataContext)
    }
  }

  private fun findExactFiles(
    path: String,
    sourceDirectory: VirtualFile?,
    baseDirectories: Set<VirtualFile>,
    absoluteFile: VirtualFile?,
  ): List<VirtualFile> {
    val normalizedPath = expandUserHome(FileUtil.toSystemIndependentName(path))
    if (normalizedPath.isEmpty()) return emptyList()

    return buildList {
      absoluteFile?.let { add(it) }
      if (!FileUtil.isAbsolute(normalizedPath)) {
        val relativePath = normalizedPath.removePrefix("./").trimStart('/')
        if (relativePath.isNotEmpty()) {
          sourceDirectory?.findFileByRelativePath(relativePath)?.let { add(it) }
          baseDirectories.mapNotNullTo(this) { it.findFileByRelativePath(relativePath) }
        }
      }
    }
      .distinctBy { it.path }
  }

  private fun findAbsoluteFile(path: String): VirtualFile? {
    val normalizedPath = expandUserHome(FileUtil.toSystemIndependentName(path))
    if (!FileUtil.isAbsolute(normalizedPath)) return null

    val nioPath = toNioPath(normalizedPath) ?: return null
    return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(nioPath)
  }

  private fun findIndexedFiles(path: String, baseDirectories: Set<VirtualFile>): List<VirtualFile> {
    val normalizedPath = FileUtil.toSystemIndependentName(path).trim('/')
    if (normalizedPath.isEmpty()) return emptyList()

    val fileName = normalizedPath.substringAfterLast('/')
    if (fileName.isBlank()) return emptyList()

    return FilenameIndex.getVirtualFilesByName(fileName, GlobalSearchScope.projectScope(project))
      .asSequence()
      .filter { matchesPathSuffix(it, normalizedPath, baseDirectories) }
      .distinctBy { it.path }
      .toList()
  }

  private fun matchesPathSuffix(file: VirtualFile, normalizedPath: String, baseDirectories: Set<VirtualFile>): Boolean {
    if (normalizedPath == file.name) return true
    val relativePath = baseDirectories.firstNotNullOfOrNull { VfsUtilCore.getRelativePath(file, it, '/') }
    return relativePath == normalizedPath || relativePath?.endsWith("/$normalizedPath") == true || file.path.endsWith("/$normalizedPath")
  }

  private fun isProjectFile(
    file: VirtualFile,
    fileIndex: ProjectFileIndex,
    psiManager: PsiManager,
    baseProjectDirectories: BaseProjectDirectories,
  ): Boolean {
    if (fileIndex.isUnderIgnored(file)) return false

    val psiElement = if (file.isDirectory) psiManager.findDirectory(file) else psiManager.findFile(file)
    return (psiElement != null && psiManager.isInProject(psiElement)) || baseProjectDirectories.contains(file)
  }

  private fun displayPath(file: VirtualFile, baseDirectories: Set<VirtualFile>): String {
    return baseDirectories.firstNotNullOfOrNull { VfsUtilCore.getRelativePath(file, it, '/') }
           ?: file.presentableUrl
  }

  private fun relativePathFromSource(
    file: VirtualFile,
    sourceDirectory: VirtualFile?,
    baseDirectories: Set<VirtualFile>,
  ): String {
    if (sourceDirectory != null) {
      VfsUtilCore.getRelativePath(file, sourceDirectory, '/')?.takeIf { it.isNotEmpty() }?.let { return it }
      VfsUtilCore.findRelativePath(sourceDirectory, file, '/')?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return displayPath(file, baseDirectories)
  }

  private fun suggestionComparator(lowerQuery: String): Comparator<SuggestedFileEntry> {
    return Comparator { first, second ->
      val firstRank = suggestionRank(first, lowerQuery)
      val secondRank = suggestionRank(second, lowerQuery)
      if (firstRank != secondRank) return@Comparator firstRank.compareTo(secondRank)

      val firstParentSteps = parentTraversalCount(first.relativePath)
      val secondParentSteps = parentTraversalCount(second.relativePath)
      if (firstParentSteps != secondParentSteps) return@Comparator firstParentSteps.compareTo(secondParentSteps)

      if (first.relativePath.length != second.relativePath.length) {
        return@Comparator first.relativePath.length.compareTo(second.relativePath.length)
      }
      first.relativePath.compareTo(second.relativePath, ignoreCase = true)
    }
  }

  private fun suggestionRank(entry: SuggestedFileEntry, lowerQuery: String): Int {
    if (lowerQuery.isEmpty()) return 0
    val lowerName = entry.fileName.lowercase()
    val lowerRelative = entry.relativePath.lowercase()
    return when {
      lowerName == lowerQuery || lowerRelative == lowerQuery -> 0
      lowerName.startsWith(lowerQuery) -> 1
      lowerRelative.startsWith(lowerQuery) -> 2
      else -> 3
    }
  }

  private fun parentTraversalCount(path: String): Int {
    var count = 0
    var rest = path
    while (rest.startsWith("../")) {
      count++
      rest = rest.removePrefix("../")
    }
    return count
  }

  private fun parsePath(rawPath: String): ParsedPath? {
    val trimmed = rawPath.trim().trim('`', '\'', '"')
    if (trimmed.isEmpty()) return null

    HASH_LINE_SUFFIX.matchEntire(trimmed)?.let { match ->
      return ParsedPath(
        path = match.groupValues[1],
        line = match.groupValues[2].toPositiveInt(),
        column = null,
      )
    }

    COLON_LOCATION_SUFFIX.matchEntire(trimmed)?.let { match ->
      return ParsedPath(
        path = match.groupValues[1],
        line = match.groupValues[2].toPositiveInt(),
        column = match.groupValues.getOrNull(3)?.toPositiveInt(),
      )
    }

    return ParsedPath(path = trimmed, line = null, column = null)
  }

  private fun String.toPositiveInt(): Int? = toIntOrNull()?.takeIf { it > 0 }

  private fun expandUserHome(path: String): String {
    if (path == "~") return FileUtil.toSystemIndependentName(System.getProperty("user.home"))
    if (!path.startsWith("~/")) return path
    return FileUtil.toSystemIndependentName(System.getProperty("user.home") + path.substring(1))
  }

  private fun toNioPath(path: String): Path? = runCatching { Path.of(path).normalize() }.getOrNull()

  internal data class PathNavigationTarget(
    val project: Project,
    val file: VirtualFile,
    @NlsSafe
    val displayPath: String,
    val line: Int?,
    val column: Int?,
  ) : NavigationTarget {
    override fun createPointer(): Pointer<PathNavigationTarget> {
      val filePath = file.path
      return Pointer {
        if (project.isDisposed) return@Pointer null
        LocalFileSystem.getInstance().findFileByPath(filePath)?.let {
          copy(file = it)
        }
      }
    }

    override fun computePresentation(): TargetPresentation = TargetPresentation.builder(displayPath).presentation()

    override fun navigationRequest(): NavigationRequest? {
      val validFile = file.takeIf { it.isValid } ?: return null
      if (validFile.isDirectory) {
        val directory = PsiManager.getInstance(project).findDirectory(validFile) ?: return null
        return NavigationRequest.directoryNavigationRequest(directory)
      }

      return NavigationRequest.sourceNavigationRequest(project, validFile, navigationOffset(validFile))
    }

    private fun navigationOffset(file: VirtualFile): Int {
      val line = line ?: return 0
      val document = FileDocumentManager.getInstance().getDocument(file) ?: return 0
      if (document.lineCount == 0) return 0

      val lineIndex = (line - 1).coerceIn(0, document.lineCount - 1)
      val lineStartOffset = document.getLineStartOffset(lineIndex)
      val lineEndOffset = document.getLineEndOffset(lineIndex)
      val columnOffset = ((column ?: 1) - 1).coerceAtLeast(0)
      return min(lineStartOffset + columnOffset, lineEndOffset)
    }
  }

  private data class ParsedPath(val path: String, val line: Int?, val column: Int?)

  private data class SuggestedFileEntry(
    val relativePath: String,
    val fileName: String,
  )

  private companion object {
    private const val DEFAULT_SUGGESTION_LIMIT = 20
    private const val MAX_SUGGESTION_LIMIT = 100
    private val HASH_LINE_SUFFIX = Regex("^(.+)#L(\\d+)$")
    private val COLON_LOCATION_SUFFIX = Regex("^(.+?):(\\d+)(?::(\\d+))?$")
  }
}
