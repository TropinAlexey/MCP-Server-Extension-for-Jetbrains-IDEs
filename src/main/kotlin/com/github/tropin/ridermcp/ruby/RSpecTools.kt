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
import com.github.tropin.ridermcp.SessionManager
import kotlin.coroutines.coroutineContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class RSpecToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Runs RSpec tests. " +
            "action='run' executes tests (optional pattern filter, returns sessionId for polling). " +
            "action='run_and_wait' blocks until completion (timeoutMs max 300000). " +
            "action='list' lists tests without running. " +
            "action='bisect' bisects failing tests. " +
            "projectPath scopes to subdirectory (where spec files live)."
    )
    suspend fun rider_rspec(
        @McpDescription("Action: run (default), run_and_wait, list, bisect") action: String = "run",
        @McpDescription("Test filter pattern (e.g., 'MySpec', 'spec/models/user_spec.rb:10')") pattern: String? = null,
        @McpDescription("Project-relative path to scope operation (omit = project root)") projectPath: String? = null,
        @McpDescription("RSpec file or directory pattern (e.g., 'spec/models/**/*.rb')") filePattern: String? = null,
        @McpDescription("Additional RSpec arguments (e.g., '--format documentation', '--tag focus')") extraArgs: String? = null,
        @McpDescription("Timeout in ms for run_and_wait (default 120000)") timeoutMs: Int = 120000,
        @McpDescription("Session ID for run_and_wait (from previous run)") sessionId: String? = null
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        return when (action.lowercase()) {
            "run" -> runTests(workDir, pattern, filePattern, extraArgs, wait = false)
            "run_and_wait" -> runTests(workDir, pattern, filePattern, extraArgs, wait = true, timeoutMs = timeoutMs)
            "list" -> listTests(workDir, pattern)
            "bisect" -> bisect(workDir, pattern, extraArgs)
            else -> mcpFail("Unknown action '$action'. Use: run, run_and_wait, list, bisect")
        }
    }

    private suspend fun runTests(
        workDir: String, pattern: String?, filePattern: String?, extraArgs: String?,
        wait: Boolean, timeoutMs: Int = 120000
    ): String {
        val cmd = mutableListOf<String>("./vendor/bin/rspec")
        pattern?.let { cmd.addAll(listOf("-e", it)) }
        filePattern?.let { cmd.add(it) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }

        if (wait) {
            val (exitCode, output) = runCmd(workDir, cmd, timeout = (timeoutMs / 1000 + 10).toLong())
            val exitCodeLong: Long = exitCode.toLong()
            return JsonObject(mapOf(
                "status" to JsonPrimitive(if (exitCode == 0) "passed" else "failed"),
                "exitCode" to JsonPrimitive(exitCodeLong),
                "output" to JsonPrimitive(output.trim())
            )).toString()
        }

        // Async run with session polling
        val session = SessionManager.create("rspec_test")
        try {
            val cmd = mutableListOf<String>("./vendor/bin/rspec")
            pattern?.let { cmd.addAll(listOf("-e", it)) }
            filePattern?.let { cmd.add(it) }
            extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
            val commandLine = com.intellij.execution.configurations.GeneralCommandLine(*cmd.toTypedArray())
                .withWorkDirectory(coroutineContext.project.basePath!!)
                .withRedirectErrorStream(true)
            session.appendLine("Running: ${commandLine.commandLineString}")
            val handler = com.intellij.execution.process.OSProcessHandler(commandLine)
            session.tag = handler
            handler.addProcessListener(object : com.intellij.execution.process.ProcessListener {
                override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                    val text = event.text.trimEnd('\n', '\r')
                    if (text.isNotEmpty()) session.appendLine(text)
                }
                override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                    session.exitCode = event.exitCode
                    session.status = if (event.exitCode == 0) "passed" else "failed"
                }
            })
            handler.startNotify()
        } catch (e: Exception) {
            session.status = "failed"
            session.appendLine("Error: ${e.message}")
        }
        return JsonObject(mapOf("sessionId" to JsonPrimitive(session.id))).toString()
    }

    private suspend fun listTests(workDir: String, pattern: String?): String {
        val cmd = mutableListOf<String>("./vendor/bin/rspec", "--dry-run", "--format=json")
        pattern?.let { cmd.addAll(listOf("-e", it)) }
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 30)
        if (exitCode != 0) mcpFail("rspec list failed:\n$output")
        val testsArray = JsonArray(
            try {
                val json = kotlinx.serialization.json.Json.decodeFromString<Map<String, Any>>(output)
                (json["examples"] as? List<*>)?.mapNotNull { ex ->
                    val exMap = ex as? Map<String, Any>
                    exMap?.let {
                        JsonObject(mapOf(
                            "id" to JsonPrimitive(it["id"]?.toString() ?: ""),
                            "description" to JsonPrimitive(it["description"]?.toString() ?: ""),
                            "file_path" to JsonPrimitive(it["file_path"]?.toString() ?: ""),
                            "line_number" to JsonPrimitive(it["line_number"]?.toString() ?: "")
                        ))
                    }
                } ?: emptyList()
            } catch (_: Exception) { emptyList() }
        )
        return JsonObject(mapOf("tests" to testsArray)).toString()
    }

    private suspend fun bisect(workDir: String, pattern: String?, extraArgs: String?): String {
        val cmd = mutableListOf<String>("./vendor/bin/rspec", "--bisect")
        pattern?.let { cmd.addAll(listOf("-e", it)) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 300)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "passed" else "failed"),
            "exitCode" to JsonPrimitive(exitCode.toLong()),
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
