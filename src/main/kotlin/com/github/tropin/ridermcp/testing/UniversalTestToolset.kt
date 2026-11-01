package com.github.tropin.ridermcp.testing

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

class UniversalTestToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Universal test runner across all IDEs. " +
            "action='run' executes tests (returns sessionId for polling). " +
            "action='run_and_wait' blocks until completion (timeoutMs max 300000). " +
            "action='list' lists available tests. " +
            "action='coverage' runs with coverage. " +
            "action='rerun_failed' reruns failed tests. " +
            "auto-detects test framework: dotnet test (Rider), go test (GoLand), pytest (PyCharm), " +
            "phpunit (PhpStorm), rspec (RubyMine), jest/vitest/mocha/playwright (WebStorm). " +
            "projectPath scopes to subdirectory."
    )
    suspend fun rider_test(
        @McpDescription("Action: run (default), run_and_wait, list, coverage, rerun_failed") action: String = "run",
        @McpDescription("Test filter/pattern (e.g., class name, method name, file pattern)") filter: String? = null,
        @McpDescription("Project-relative path to scope operation (omit = project root)") projectPath: String? = null,
        @McpDescription("Test framework to use (auto-detected if omitted): dotnet, go, pytest, phpunit, rspec, jest, vitest, mocha, playwright") framework: String? = null,
        @McpDescription("Additional framework-specific arguments") extraArgs: String? = null,
        @McpDescription("Run with coverage") coverage: Boolean = false,
        @McpDescription("Coverage output format") coverageFormat: String? = null,
        @McpDescription("Timeout in ms for run_and_wait (default 120000)") timeoutMs: Int = 120000,
        @McpDescription("Session ID for run_and_wait/rerun_failed (from previous run)") sessionId: String? = null
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        val fw = framework?.lowercase() ?: detectFramework(workDir)

        return when (action.lowercase()) {
            "run" -> runTests(fw, workDir, filter, extraArgs, coverage, coverageFormat, wait = false)
            "run_and_wait" -> runTests(fw, workDir, filter, extraArgs, coverage, coverageFormat, wait = true, timeoutMs = timeoutMs)
            "list" -> listTests(fw)
            "coverage" -> runCoverage(fw, filter, extraArgs, coverageFormat)
            "rerun_failed" -> rerunFailed(fw, filter, extraArgs, sessionId)
            else -> mcpFail("Unknown action '$action'. Use: run, run_and_wait, list, coverage, rerun_failed")
        }
    }

    private fun detectFramework(workDir: String): String {
        val files = Path.of(workDir).toFile().listFiles() ?: return "unknown"
        return when {
            files.any { it.name == "pom.xml" || it.name == "build.gradle" || it.name == "build.gradle.kts" } -> "dotnet"
            files.any { it.name == "go.mod" } -> "go"
            files.any { it.name == "pyproject.toml" || it.name == "pytest.ini" || it.name == "requirements.txt" } -> "pytest"
            files.any { it.name == "composer.json" } -> "phpunit"
            files.any { it.name == "Gemfile" } -> "rspec"
            files.any { it.name == "package.json" } -> {
                val pkgJson = Path.of(workDir, "package.json").toFile()
                if (pkgJson.exists()) {
                    try {
                        val content = pkgJson.readText()
                        val json = kotlinx.serialization.json.Json.decodeFromString<Map<String, Any>>(content)
                        val scripts = json["scripts"] as? Map<String, Any> ?: return "jest"
                        if (scripts.keys.any { it.contains("vitest") }) return "vitest"
                        if (scripts.keys.any { it.contains("playwright") }) return "playwright"
                        if (scripts.keys.any { it.contains("mocha") }) return "mocha"
                    } catch (_: Exception) {}
                }
                return "jest"
            }
            else -> "unknown"
        }
    }

    private suspend fun runTests(
        framework: String, workDir: String, filter: String?, extraArgs: String?,
        coverage: Boolean, coverageFormat: String?, wait: Boolean, timeoutMs: Int = 120000
    ): String {
        val (cmd, fwWorkDir) = buildCmd(framework, filter, extraArgs, coverage, coverageFormat)

        if (wait) {
            val (exitCode, output) = runCmd(fwWorkDir, cmd, timeout = (timeoutMs / 1000 + 10).toLong())
            val exitCodeLong: Long = exitCode.toLong()
            return JsonObject(mapOf(
                "status" to JsonPrimitive(if (exitCode == 0) "passed" else "failed"),
                "exitCode" to JsonPrimitive(exitCodeLong),
                "output" to JsonPrimitive(output.trim())
            )).toString()
        }

        // Async run with session polling
        val session = SessionManager.create("universal_test")
        val asyncCmd = buildAsyncCmd(framework, filter, extraArgs, coverage)
        try {
            val commandLine = com.intellij.execution.configurations.GeneralCommandLine(*asyncCmd.toTypedArray())
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

    private suspend fun buildCmd(
        framework: String, filter: String?, extraArgs: String?,
        coverage: Boolean, coverageFormat: String?
    ): Pair<List<String>, String> {
        val project = coroutineContext.project
        val baseDir = project.basePath!!

        return when (framework) {
            "dotnet" -> {
                val cmd = mutableListOf<String>("dotnet", "test")
                if (coverage) cmd.add("--collect:\"XPlat Code Coverage\"")
                filter?.let { cmd.addAll(listOf("--filter", it)) }
                extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
                cmd to project.basePath!!
            }
            "go" -> {
                val cmd = mutableListOf<String>("go", "test")
                if (coverage) cmd.addAll(listOf("-coverprofile=coverage.out"))
                filter?.let { cmd.add("-run"); cmd.add(it) }
                extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
                cmd.add("./...")
                cmd to project.basePath!!
            }
            "pytest" -> {
                val cmd = mutableListOf<String>("./vendor/bin/pytest", "-v")
                filter?.let { cmd.add("-k"); cmd.add(it) }
                if (coverage) cmd.addAll(listOf("--cov", "--cov-report=term"))
                extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
                cmd to project.basePath!!
            }
            "phpunit" -> {
                val cmd = mutableListOf<String>("./vendor/bin/phpunit")
                filter?.let { cmd.addAll(listOf("--filter", it)) }
                if (coverage) cmd.add("--coverage-text")
                extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
                cmd to coroutineContext.project.basePath!!
            }
            "rspec" -> {
                val cmd = mutableListOf<String>("./vendor/bin/rspec")
                filter?.let { cmd.add("-e"); cmd.add(it) }
                if (coverage) cmd.addAll(listOf("--format", "json"))
                extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
                cmd to coroutineContext.project.basePath!!
            }
            "jest", "vitest", "mocha", "playwright" -> {
                val runner = when (framework) {
                    "vitest" -> listOf("npx", "vitest", "run")
                    "playwright" -> listOf("npx", "playwright", "test")
                    "mocha" -> listOf("npx", "mocha")
                    else -> listOf("npx", "jest")
                }
                val cmd = mutableListOf<String>()
                cmd.addAll(runner)
                filter?.let { cmd.add("-t"); cmd.add(it) }
                extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
                if (coverage) cmd.add("--coverage")
                cmd to coroutineContext.project.basePath!!
            }
            else -> mcpFail("Unknown framework '$framework'. Supported: dotnet, go, pytest, phpunit, rspec, jest, vitest, mocha, playwright")
        }
    }

    private fun buildAsyncCmd(framework: String, filter: String?, extraArgs: String?, coverage: Boolean): List<String> {
        val cmd = mutableListOf<String>()
        when (framework) {
            "dotnet" -> cmd.addAll(listOf("dotnet", "test"))
            "go" -> cmd.addAll(listOf("go", "test"))
            "pytest" -> cmd.addAll(listOf("./vendor/bin/pytest"))
            "phpunit" -> cmd.addAll(listOf("./vendor/bin/phpunit"))
            "rspec" -> cmd.addAll(listOf("./vendor/bin/rspec"))
            "jest", "vitest", "mocha", "playwright" -> {
                val runner = when (framework) {
                    "vitest" -> listOf("npx", "vitest", "run")
                    "playwright" -> listOf("npx", "playwright", "test")
                    "mocha" -> listOf("npx", "mocha")
                    else -> listOf("npx", "jest")
                }
                cmd.addAll(runner)
            }
            else -> mcpFail("Unknown framework '$framework'")
        }
        if (coverage) {
            when (framework) {
                "dotnet" -> cmd.add("--collect:\"XPlat Code Coverage\"")
                "go" -> cmd.addAll(listOf("-coverprofile=coverage.out"))
                "pytest" -> cmd.addAll(listOf("--cov", "--cov-report=term"))
                "phpunit" -> cmd.add("--coverage-text")
                "rspec" -> cmd.addAll(listOf("--format", "json"))
            }
        }
        filter?.let { cmd.add("-e"); cmd.add(it) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        return cmd
    }

    private suspend fun runCoverage(framework: String, filter: String?, extraArgs: String?, coverageFormat: String?): String {
        val cmd = mutableListOf<String>()
        when (framework) {
            "dotnet" -> cmd.addAll(listOf("dotnet", "test", "--collect:\"XPlat Code Coverage\""))
            "go" -> cmd.addAll(listOf("go", "test", "-coverprofile=coverage.out"))
            "pytest" -> cmd.addAll(listOf("./vendor/bin/pytest", "--cov", "--cov-report=term"))
            "phpunit" -> cmd.addAll(listOf("./vendor/bin/phpunit", "--coverage-text"))
            "rspec" -> cmd.addAll(listOf("./vendor/bin/rspec", "--format", "json"))
            else -> mcpFail("Coverage not supported for framework: $framework")
        }
        filter?.let { cmd.add("-e"); cmd.add(it) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 180)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "passed" else "failed"),
            "exitCode" to JsonPrimitive(exitCode.toLong()),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun listTests(framework: String): String {
        val (cmd, _) = buildCmd(framework, null, null, coverage = false, coverageFormat = null)
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 30)
        return JsonObject(mapOf("output" to JsonPrimitive(output.trim()))).toString()
    }

    private suspend fun rerunFailed(framework: String, filter: String?, extraArgs: String?, sessionId: String?): String {
        val cmd = mutableListOf<String>()
        when (framework) {
            "dotnet" -> cmd.addAll(listOf("dotnet", "test", "--filter", "FullyQualifiedName~Failed"))
            "go" -> cmd.addAll(listOf("go", "test", "-run", "Failed"))
            "pytest" -> cmd.addAll(listOf("./vendor/bin/pytest", "--lf"))
            "phpunit" -> cmd.addAll(listOf("./vendor/bin/phpunit", "--filter=failed"))
            "rspec" -> cmd.addAll(listOf("./vendor/bin/rspec", "--only-failures"))
            else -> mcpFail("Rerun failed not supported for framework: $framework")
        }
        val (exitCode, output) = runCmd(coroutineContext.project.basePath!!, cmd, timeout = 300)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "passed" else "failed"),
            "exitCode" to JsonPrimitive(exitCode.toLong()),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun runCmd(workDir: String, cmd: List<String>, timeout: Long = 300): Pair<Int, String> {
        val commandLine = com.intellij.execution.configurations.GeneralCommandLine(*cmd.toTypedArray())
            .withWorkDirectory(coroutineContext.project.basePath!!)
            .withRedirectErrorStream(true)
        val result = withContext(Dispatchers.IO) {
            CapturingProcessHandler(commandLine).runProcess(TimeUnit.SECONDS.toMillis(timeout).toInt(), true)
        }
        if (result.isTimeout) return -1 to "Command timed out after ${timeout}s: ${cmd.joinToString(" ")}"
        return result.exitCode to result.stdout
    }

    private fun validateProjectPath(project: com.intellij.openapi.project.Project, path: String): String? {
        if (path.startsWith("-")) return "Project path must not start with '-'"
        val basePath = project.basePath ?: return "No project path"
        val resolved = Path.of(basePath).resolve(path).normalize()
        if (!resolved.startsWith(Path.of(basePath).normalize())) return "Project path escapes project directory"
        if (!resolved.toFile().exists()) return "Project file not found: $path"
        return null
    }
}