// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.nerzhulart.webview.markdown.preview

import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.intellij.plugins.markdown.extensions.jcef.commandRunner.RunnerPlace

/**
 * SDK-compatible Markdown command runner facade.
 *
 * The Markdown command-runner implementation is compiled as part of the Markdown plugin and exposes Kotlin-internal
 * APIs that are unavailable to an external plugin. WebView preview continues to render Markdown, but does not expose
 * runnable-command actions in the external SDK build.
 */
internal class MarkdownRunCommandSession private constructor() {
  val descriptors: List<MarkdownCommandDescriptor> = emptyList()

  fun command(id: String): MarkdownRunCommand? = null

  fun lineCommand(id: String?): MarkdownRunCommand.Line? = null

  fun executeLine(
    command: MarkdownRunCommand.LineCommand,
    place: RunnerPlace,
    executor: Executor = DefaultRunExecutor.getRunExecutorInstance(),
  ): Boolean = false

  fun executeBlock(
    command: MarkdownRunCommand.Block,
    executor: Executor = DefaultRunExecutor.getRunExecutorInstance(),
  ): Boolean = false

  companion object {
    val EMPTY = MarkdownRunCommandSession()

    fun resolve(
      project: Project?,
      virtualFile: VirtualFile?,
      candidates: List<MarkdownCommandCandidate>,
    ): MarkdownRunCommandSession = EMPTY
  }
}

internal sealed interface MarkdownRunCommand {
  val descriptor: MarkdownCommandDescriptor

  data class Block(
    override val descriptor: MarkdownCommandDescriptor,
  ) : MarkdownRunCommand

  data class Line(
    override val descriptor: MarkdownCommandDescriptor,
    val command: LineCommand,
  ) : MarkdownRunCommand

  data class LineCommand(
    val rawCommand: String,
    val command: String,
    val title: String,
  )
}
