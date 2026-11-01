package com.github.tropin.ridermcp.ruby

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlin.coroutines.coroutineContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class RuboCopToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Runs RuboCop for Ruby linting and formatting. " +
            "action='lint' lints files (optional pattern, returns offenses). " +
            "action='fix' auto-fixes offenses. " +
            "action='check' checks if files pass linting (exit code 0/1). " +
            "action='config' shows resolved config for a file. " +
            "action='list' lists available cops. " +
            "action='generate' generates configuration files. " +
            "pattern specifies files to lint (e.g., 'app/**/*.rb', 'lib/**/*.rb'). " +
            "configFile overrides config file path. " +
            "projectPath scopes to subdirectory."
    )
    suspend fun rider_rubocop(
        @McpDescription("Action: lint (default), fix, check, config, list, generate") action: String = "lint",
        @McpDescription("File pattern to lint (e.g., 'app/**/*.rb', 'lib/**/*.rb')") pattern: String = "**/*.rb",
        @McpDescription("Project-relative path to scope operation (omit = project root)") projectPath: String? = null,
        @McpDescription("Custom RuboCop config file path") configFile: String? = null,
        @McpDescription("Output format: json, simple, progress, clang, files, quiet, offenses (lint/check only)") format: String = "json",
        @McpDescription("Only show cops with names matching pattern") only: String? = null,
        @McpDescription("Exclude cops with names matching pattern") except: String? = null,
        @McpDescription("Cache results for faster subsequent runs") cache: Boolean = true,
        @McpDescription("Fail fast on first offense") failFast: Boolean = false,
        @McpDescription("Auto-correct offenses (fix action)") autoCorrect: Boolean = false,
        @McpDescription("Safe auto-correct only (fix action)") safeAutoCorrect: Boolean = false
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        return when (action.lowercase()) {
            "lint" -> runLint(pattern, format, failFast, cache)
            "fix" -> runFix(pattern)
            "check" -> runCheck(pattern)
            "config" -> showConfig()
            "list" -> listCops()
            "generate" -> generateConfig()
            else -> mcpFail("Unknown action '$action'. Use: lint, fix, check, config, list, generate")
        }
    }

    private suspend fun runLint(
        pattern: String, format: String, failFast: Boolean, cache: Boolean
    ): String {
        val cmd = mutableListOf<String>("./vendor/bin/rubocop", pattern)
        cmd.add("--format")
        cmd.add(format)
        if (failFast) cmd.add("--fail-fast")
        if (!cache) cmd.add("--no-cache")
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 120)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "clean" else "issues_found"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun runFix(pattern: String): String {
        val cmd = listOf("./vendor/bin/rubocop", pattern, "--auto-correct")
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 120)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "fixed" else "issues_remain"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun runCheck(pattern: String): String {
        val cmd = listOf("./vendor/bin/rubocop", pattern)
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 60)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "pass" else "fail"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun showConfig(): String {
        val cmd = listOf("rubocop", "--show-cops")
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 30)
        if (exitCode != 0) mcpFail("rubocop --show-cops failed:\n$output")
        return output.trim()
    }

    private suspend fun listCops(): String {
        val cmd = listOf("rubocop", "--show-cops", "--format=json")
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 60)
        if (exitCode != 0) mcpFail("rubocop --show-cops failed:\n$output")
        return output.trim()
    }

    private suspend fun generateConfig(): String {
        val cmd = listOf("rubocop", "--auto-gen-config")
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 60)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "generated" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private fun validateProjectPath(project: com.intellij.openapi.project.Project, path: String): String? {
        if (path.startsWith("-")) return "Project path must not start with '-'"
        val basePath = project.basePath ?: return "No project path"
        val resolved = Path.of(basePath).resolve(path).normalize()
        if (!resolved.startsWith(Path.of(basePath).normalize())) return "Project path escapes project directory"
        if (!resolved.toFile().exists()) return "Project file not found: $path"
        return null
    }

    private suspend fun runCmd(workDir: String, cmd: List<String>, timeout: Long = 60): Pair<Int, String> {
        val commandLine = com.intellij.execution.configurations.GeneralCommandLine(*cmd.toTypedArray())
            .withWorkDirectory(coroutineContext.project.basePath!!)
            .withRedirectErrorStream(true)
        val result = withContext(Dispatchers.IO) {
            CapturingProcessHandler(commandLine).runProcess(TimeUnit.SECONDS.toMillis(timeout).toInt(), true)
        }
        if (result.isTimeout) return -1 to "Command timed out after ${timeout}s: ${cmd.joinToString(" ")}"
        return result.exitCode to result.stdout
    }
}
