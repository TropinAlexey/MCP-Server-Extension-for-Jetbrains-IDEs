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

class BundlerToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Manages Ruby gems via Bundler. " +
            "action='install' installs dependencies from Gemfile. " +
            "action='update' updates gems (optional gem names). " +
            "action='add' adds a gem to Gemfile (requires name; optional version, group, source). " +
            "action='remove' removes a gem from Gemfile. " +
            "action='outdated' checks for outdated gems. " +
            "action='audit' checks for security vulnerabilities (bundler-audit). " +
            "action='list' lists installed gems. " +
            "action='exec' runs a command in the bundle context. " +
            "action='config' shows or sets bundler config. " +
            "action='show' shows gem info. " +
            "projectPath scopes to subdirectory (where Gemfile lives)."
    )
    suspend fun rider_bundler(
        @McpDescription("Action: install (default), update, add, remove, outdated, audit, list, exec, config, show") action: String = "install",
        @McpDescription("Gem name(s), comma-separated (required for add/remove/show)") name: String? = null,
        @McpDescription("Gem version constraint (e.g., '~> 1.0', '1.0.0')") version: String? = null,
        @McpDescription("Project-relative path to scope operation (omit = project root)") projectPath: String? = null,
        @McpDescription("Install in a specific group (add only, e.g., 'development', 'test')") group: String? = null,
        @McpDescription("Gem source (add only, e.g., 'https://rubygems.org', 'git://github.com/...')") source: String? = null,
        @McpDescription("Install without development gems (install only)") without: String? = null,
        @McpDescription("Additional Bundler arguments (e.g., '--without development', '--jobs=4')") extraArgs: String? = null,
        @McpDescription("Command to run with 'exec' (required for exec action)") command: String? = null
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        return when (action.lowercase()) {
            "install" -> install(workDir, without, extraArgs)
            "update" -> update(workDir, name, extraArgs)
            "add" -> {
                if (name.isNullOrBlank()) mcpFail("name is required for add")
                addGem(workDir, name, version, group, source, extraArgs)
            }
            "remove" -> {
                if (name.isNullOrBlank()) mcpFail("name is required for remove")
                removeGem(workDir, name, extraArgs)
            }
            "outdated" -> outdated(workDir, extraArgs)
            "audit" -> audit(workDir, extraArgs)
            "list" -> listGems(workDir, extraArgs)
            "exec" -> {
                if (command.isNullOrBlank()) mcpFail("command is required for exec")
                exec(workDir, command, extraArgs)
            }
            "config" -> config(workDir, extraArgs)
            "show" -> {
                if (name.isNullOrBlank()) mcpFail("name is required for show")
                showGem(workDir, name, extraArgs)
            }
            else -> mcpFail("Unknown action '$action'. Use: install, update, add, remove, outdated, audit, list, exec, config, show")
        }
    }

    private suspend fun install(workDir: String, without: String?, extraArgs: String?): String {
        val cmd = mutableListOf<String>("bundle", "install")
        without?.let { cmd.addAll(listOf("--without", it)) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 180)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "installed" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun update(workDir: String, name: String?, extraArgs: String?): String {
        val cmd = mutableListOf<String>("bundle", "update")
        name?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 180)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "updated" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun addGem(
        workDir: String, name: String?, version: String?, group: String?, source: String?, extraArgs: String?
    ): String {
        if (name.isNullOrBlank()) mcpFail("name is required for add")
        val cmd = mutableListOf<String>("bundle", "add")
        val gemSpec = when {
            version != null -> "$name:$version"
            else -> name
        }
        cmd.add(gemSpec!!)
        group?.let { cmd.addAll(listOf("--group", it)) }
        source?.let { cmd.addAll(listOf("--source", it)) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 180)
        if (exitCode != 0) mcpFail("bundle add failed:\n$output")
        return JsonObject(mapOf("status" to JsonPrimitive("added"), "gem" to JsonPrimitive(gemSpec!!))).toString()
    }

    private suspend fun removeGem(workDir: String, name: String?, extraArgs: String?): String {
        if (name.isNullOrBlank()) mcpFail("name is required for remove")
        val gems = name.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val cmd = mutableListOf<String>("bundle", "remove")
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        gems.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 120)
        if (exitCode != 0) mcpFail("bundle remove failed:\n$output")
        return JsonObject(mapOf("status" to JsonPrimitive("removed"), "gems" to JsonPrimitive(gems.joinToString(", ")))).toString()
    }

    private suspend fun outdated(workDir: String, extraArgs: String?): String {
        val cmd = listOf("bundle", "outdated", "--format=json")
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 120)
        val outdatedArray = JsonArray(
            try {
                val json = kotlinx.serialization.json.Json.decodeFromString<Map<String, Any>>(output)
                (json["gems"] as? List<*>)?.mapNotNull { gem ->
                    val g = gem as? Map<String, Any>
                    g?.let {
                        JsonObject(mapOf(
                            "name" to JsonPrimitive(it["name"]?.toString() ?: ""),
                            "current" to JsonPrimitive(it["current_version"]?.toString() ?: ""),
                            "latest" to JsonPrimitive(it["latest_version"]?.toString() ?: ""),
                            "groups" to JsonPrimitive(it["groups"]?.toString() ?: "")
                        ))
                    }
                } ?: emptyList()
            } catch (_: Exception) { emptyList() }
        )
        return JsonObject(mapOf("outdated" to outdatedArray)).toString()
    }

    private suspend fun audit(workDir: String, extraArgs: String?): String {
        val cmd = listOf("bundle", "audit", "check", "--format=json")
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 120)
        val vulnsArray = JsonArray(
            try {
                val json = kotlinx.serialization.json.Json.decodeFromString<Map<String, Any>>(output)
                (json["advisories"] as? List<*>)?.mapNotNull { adv ->
                    val advMap = adv as? Map<String, Any>
                    advMap?.let {
                        JsonObject(mapOf(
                            "gem" to JsonPrimitive(it["gem"]?.toString() ?: ""),
                            "version" to JsonPrimitive(it["version"]?.toString() ?: ""),
                            "advisory_id" to JsonPrimitive(it["advisory_id"]?.toString() ?: ""),
                            "title" to JsonPrimitive(it["title"]?.toString() ?: ""),
                            "url" to JsonPrimitive(it["url"]?.toString() ?: "")
                        ))
                    }
                } ?: emptyList()
            } catch (_: Exception) { emptyList() }
        )
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "clean" else "vulnerabilities_found"),
            "vulnerabilities" to JsonArray(vulnsArray)
        )).toString()
    }

    private suspend fun listGems(workDir: String, extraArgs: String?): String {
        val cmd = listOf("bundle", "list")
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 60)
        if (exitCode != 0) mcpFail("bundle list failed:\n$output")
        val gems = output.trim().split("\n").filter { it.startsWith("  * ") }.map { it.substring(4).trim() }
        val gemsArray = JsonArray(gems.map { JsonPrimitive(it) })
        return JsonObject(mapOf("gems" to gemsArray)).toString()
    }

    private suspend fun exec(workDir: String, command: String?, extraArgs: String?): String {
        val cmd = mutableListOf<String>("bundle", "exec")
        command?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        extraArgs?.split(" ")?.filter { it.isNotBlank() }?.forEach { cmd.add(it) }
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 180)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "ok" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun config(workDir: String, extraArgs: String?): String {
        val cmd = listOf("bundle", "config")
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 30)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "ok" else "failed"),
            "exitCode" to JsonPrimitive(exitCode),
            "output" to JsonPrimitive(output.trim())
        )).toString()
    }

    private suspend fun showGem(workDir: String, name: String?, extraArgs: String?): String {
        val cmd = listOf("bundle", "show", name!!)
        val (exitCode, output) = runCmd(workDir, cmd, timeout = 60)
        return JsonObject(mapOf(
            "status" to JsonPrimitive(if (exitCode == 0) "ok" else "failed"),
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

    private suspend fun runCmd(workDir: String, cmd: List<String>, timeout: Long = 180): Pair<Int, String> {
        val commandLine = com.intellij.execution.configurations.GeneralCommandLine(*cmd.toTypedArray())
            .withWorkDirectory(workDir)
            .withRedirectErrorStream(true)
        val result = withContext(Dispatchers.IO) {
            CapturingProcessHandler(commandLine).runProcess(TimeUnit.SECONDS.toMillis(timeout).toInt(), true)
        }
        if (result.isTimeout) return -1 to "Command timed out after ${timeout}s: ${cmd.joinToString(" ")}"
        return result.exitCode to result.stdout
    }
}