package com.github.tropin.ridermcp.cargo

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.util.Key
import kotlinx.serialization.json.*
import com.github.tropin.ridermcp.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

private val CRATE_NAME_RE = Regex("^[a-z][a-z0-9_-]*$")
private val VERSION_RE = Regex("^[0-9]+(\\.[0-9]+)*(-[a-zA-Z0-9.-]+)?(\\+[a-zA-Z0-9.-]+)?$")

private fun validateProjectPath(project: com.intellij.openapi.project.Project, path: String): String? {
    if (path.startsWith("-")) return "Project path must not start with '-'"
    val basePath = project.basePath ?: return "No project path"
    val resolved = Path.of(basePath).resolve(path).normalize()
    if (!resolved.startsWith(Path.of(basePath).normalize())) return "Project path escapes project directory"
    if (!resolved.toFile().exists()) return "Project file not found: $path"
    return null
}

class CargoToolset : McpToolset {

    @McpTool
    @McpDescription(
        "Manages Cargo packages and runs quality checks. " +
            "Actions: list/add/remove/update/tree/check/clippy/fmt/test/bench/doc/audit/deny/outdated/verify/publish/login/logout/owner. " +
            "list: shows deps (tree=true for tree); add/remove/update: mutate Cargo.toml; " +
            "check/clippy/fmt/test/bench/doc/audit/deny/outdated/verify/publish: run cargo commands; " +
            "login/logout/owner: registry auth. " +
            "Workspace: --package/--workspace/--exclude; Targets: --lib/--bin/--test/--bench/--example/--all-targets; " +
            "Registry: --registry/--token/--index. " +
            "Mutating actions mutate Cargo.toml/Cargo.lock — confirm first. " +
            "projectPath scopes to Cargo.toml. Not for building (use build_solution_start)."
    )
    suspend fun rider_cargo(
        @McpDescription("Action: list (default), add, remove, update, tree, check, clippy, fmt, test, bench, doc, audit, deny, outdated, verify, publish, login, logout, owner") action: String = "list",
        @McpDescription("Crate name, e.g. 'serde' or 'tokio' (required for add/remove/owner)") name: String? = null,
        @McpDescription("Project-relative Cargo.toml path to scope the operation (omit = workspace root)") projectPath: String? = null,
        @McpDescription("Crate version for add, e.g. '1.0.0' (omit = latest)") version: String? = null,
        @McpDescription("Features to enable for add, comma-separated (e.g. 'full,derive')") features: String? = null,
        @McpDescription("Package to update (update only, omit = all)") `package`: String? = null,
        @McpDescription("Show dependency tree (list only, default false)") tree: Boolean = false,
        @McpDescription("Auto-fix clippy lints (clippy only, default false)") fix: Boolean = false,
        @McpDescription("Check formatting only without writing (fmt only, default false)") check: Boolean = false,
        @McpDescription("Test/bench filter (test/bench only, e.g. 'my_test' or 'my_module::tests')") filter: String? = null,
        @McpDescription("Run tests with --no-run to only compile (test only, default false)") noRun: Boolean = false,
        // Workspace member filtering
        @McpDescription("Package(s) to operate on (comma-separated, supports wildcards). Overrides --workspace") pkg: String? = null,
        @McpDescription("Operate on all workspace members (default true for workspace root)") workspace: Boolean? = null,
        @McpDescription("Exclude packages from operation (comma-separated)") exclude: String? = null,
        // Target filtering
        @McpDescription("Build/test only the library target") lib: Boolean = false,
        @McpDescription("Build/test only binary targets (comma-separated names, or true for all)") bin: String? = null,
        @McpDescription("Build/test only test targets (comma-separated names, or true for all)") testTargets: String? = null,
        @McpDescription("Build/test only bench targets (comma-separated names, or true for all)") benchTargets: String? = null,
        @McpDescription("Build/test only example targets (comma-separated names, or true for all)") exampleTargets: String? = null,
        @McpDescription("Build/test all targets (default false)") allTargets: Boolean = false,
        // Registry/auth management
        @McpDescription("Registry to use (crates.io or private registry name from .cargo/config.toml)") registry: String? = null,
        @McpDescription("Token for registry authentication (login/publish/verify)") token: String? = null,
        @McpDescription("Registry index URL for private registries (used with token)") index: String? = null,
        @McpDescription("Owner action subcommand: add, remove, list (owner action only)") ownerSubcommand: String? = null,
        @McpDescription("Crate name for owner actions (required for owner action)") ownerCrate: String? = null,
        @McpDescription("Owner username to add/remove (owner action only)") ownerUser: String? = null,
        // Extra
        @McpDescription("Extra cargo flags (raw, appended as-is)") extraArgs: String? = null
    ): String {
        val project = coroutineContext.project
        projectPath?.let { validateProjectPath(project, it)?.let { err -> mcpFail(err) } }
        val workDir = projectPath?.let { Path.of(project.basePath!!, it).toString() } ?: project.basePath!!

        // Build workspace/target filtering args used by multiple actions
        val workspaceArgs = buildWorkspaceArgs(pkg, workspace, exclude)
        val targetArgs = buildTargetArgs(lib, bin, testTargets, benchTargets, exampleTargets, allTargets)
        val registryArgs = buildRegistryArgs(registry, token, index)
        val extraArgsList = extraArgs?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()

        return when (action.lowercase()) {
            "list" -> listPackages(workDir, tree, workspaceArgs, targetArgs)
            "add" -> managePackage(workDir, "add", name, version, features, workspaceArgs, targetArgs, registryArgs, extraArgsList)
            "remove" -> managePackage(workDir, "remove", name, null, null, workspaceArgs, targetArgs, registryArgs, extraArgsList)
            "update" -> updatePackages(workDir, `package`, workspaceArgs, targetArgs, registryArgs, extraArgsList)
            "tree" -> showTree(workDir, workspaceArgs, targetArgs)
            "check" -> runCheck(workDir, workspaceArgs, targetArgs, registryArgs, extraArgsList)
            "clippy" -> runClippy(workDir, fix, workspaceArgs, targetArgs, registryArgs, extraArgsList)
            "fmt" -> runFmt(workDir, check, workspaceArgs, targetArgs, registryArgs, extraArgsList)
            "test" -> runTests(workDir, filter, noRun, workspaceArgs, targetArgs, registryArgs, extraArgsList)
            "bench" -> runBench(workDir, filter, noRun, workspaceArgs, targetArgs, registryArgs, extraArgsList)
            "doc" -> runDoc(workDir, workspaceArgs, targetArgs, registryArgs, extraArgsList)
            "audit" -> runAudit(workDir, workspaceArgs, targetArgs, registryArgs, extraArgsList)
            "deny" -> runDeny(workDir, workspaceArgs, targetArgs, registryArgs, extraArgsList)
            "outdated" -> runOutdated(workDir, workspaceArgs, targetArgs, registryArgs, extraArgsList)
            "verify" -> runVerify(workDir, workspaceArgs, targetArgs, registryArgs, extraArgsList)
            "publish" -> runPublish(workDir, workspaceArgs, targetArgs, registryArgs, extraArgsList)
            "login" -> runLogin(workDir, token, registry, index, extraArgsList)
            "logout" -> runLogout(workDir, registry, extraArgsList)
            "owner" -> runOwner(workDir, ownerSubcommand, ownerCrate, ownerUser, token, registry, extraArgsList)
            else -> mcpFail("Unknown action '$action'. Use: list, add, remove, update, tree, check, clippy, fmt, test, bench, doc, audit, deny, outdated, verify, publish, login, logout, owner")
        }
    }

    private suspend fun listPackages(workDir: String, tree: Boolean, workspaceArgs: List<String>, targetArgs: List<String>): String {
        val project = coroutineContext.project
        val cmdArgs = mutableListOf<String>()
        if (tree) {
            cmdArgs.add("tree")
        } else {
            cmdArgs.add("metadata")
            cmdArgs.add("--format-version=1")
        }
        cmdArgs.add("--manifest-path")
        cmdArgs.add(Path.of(workDir, "Cargo.toml").toString())
        cmdArgs.addAll(workspaceArgs)
        cmdArgs.addAll(targetArgs)

        val (exitCode, output) = runCargoSync(workDir, cmdArgs, timeout = 30)
        if (exitCode != 0) mcpFail("cargo ${if (tree) "tree" else "metadata"} failed:\n$output")
        return output.trim().ifEmpty { "No packages found" }
    }

    private suspend fun managePackage(
        workDir: String, op: String, name: String?, version: String?, features: String?,
        workspaceArgs: List<String>, targetArgs: List<String>, registryArgs: List<String>, extraArgs: List<String>
    ): String {
        if (name.isNullOrBlank()) mcpFail("name is required for action='$op'")
        val project = coroutineContext.project
        if (!name.matches(CRATE_NAME_RE)) mcpFail("Invalid crate name: $name (must be lowercase, hyphens/underscores only)")
        version?.let { if (!it.matches(VERSION_RE)) mcpFail("Invalid version: $it") }
        features?.let { if (it.contains(" ")) mcpFail("Features must be comma-separated without spaces") }

        val cmdArgs = mutableListOf<String>()
        cmdArgs.add(op)
        cmdArgs.add("--manifest-path")
        cmdArgs.add(Path.of(workDir, "Cargo.toml").toString())
        cmdArgs.addAll(workspaceArgs)
        cmdArgs.addAll(targetArgs)
        cmdArgs.addAll(registryArgs)
        cmdArgs.add(name)
        if (op == "add" && version != null) cmdArgs.addAll(listOf("--version", version))
        if (op == "add" && features != null && features.isNotBlank()) cmdArgs.addAll(listOf("--features", features))
        cmdArgs.addAll(extraArgs)

        val (exitCode, output) = runCargoSync(workDir, cmdArgs, timeout = 60)
        if (exitCode != 0) mcpFail(output.trim())
        return output.trim()
    }

    private suspend fun updatePackages(workDir: String, targetPackage: String?, workspaceArgs: List<String>, targetArgs: List<String>, registryArgs: List<String>, extraArgs: List<String>): String {
        val project = coroutineContext.project
        val cmdArgs = mutableListOf("update")
        cmdArgs.add("--manifest-path")
        cmdArgs.add(Path.of(workDir, "Cargo.toml").toString())
        cmdArgs.addAll(workspaceArgs)
        cmdArgs.addAll(targetArgs)
        cmdArgs.addAll(registryArgs)
        targetPackage?.let { cmdArgs.addAll(listOf("--package", it)) }
        cmdArgs.addAll(extraArgs)
        val (exitCode, output) = runCargoSync(workDir, cmdArgs, timeout = 120)
        if (exitCode != 0) mcpFail("cargo update failed:\n$output")
        return output.trim()
    }

    private suspend fun showTree(workDir: String, workspaceArgs: List<String>, targetArgs: List<String>): String {
        return listPackages(workDir, true, workspaceArgs, targetArgs)
    }

    private suspend fun runCheck(workDir: String, workspaceArgs: List<String>, targetArgs: List<String>, registryArgs: List<String>, extraArgs: List<String>): String {
        val project = coroutineContext.project
        val cmdArgs = mutableListOf("check")
        cmdArgs.add("--manifest-path")
        cmdArgs.add(Path.of(workDir, "Cargo.toml").toString())
        cmdArgs.addAll(workspaceArgs)
        cmdArgs.addAll(targetArgs)
        cmdArgs.addAll(registryArgs)
        cmdArgs.addAll(extraArgs)
        val (exitCode, output) = runCargoSync(workDir, cmdArgs, timeout = 120)
        if (exitCode != 0) mcpFail("cargo check failed:\n$output")
        return output.trim().ifEmpty { "cargo check: OK" }
    }

    private suspend fun runClippy(workDir: String, fix: Boolean, workspaceArgs: List<String>, targetArgs: List<String>, registryArgs: List<String>, extraArgs: List<String>): String {
        val project = coroutineContext.project
        val cmdArgs = mutableListOf("clippy")
        if (fix) cmdArgs.add("--fix")
        cmdArgs.add("--manifest-path")
        cmdArgs.add(Path.of(workDir, "Cargo.toml").toString())
        cmdArgs.addAll(workspaceArgs)
        cmdArgs.addAll(targetArgs)
        cmdArgs.addAll(registryArgs)
        cmdArgs.addAll(extraArgs)
        val (exitCode, output) = runCargoSync(workDir, cmdArgs, timeout = 180)
        if (exitCode != 0) mcpFail("cargo clippy failed:\n$output")
        return output.trim().ifEmpty { "cargo clippy: OK" }
    }

    private suspend fun runFmt(workDir: String, check: Boolean, workspaceArgs: List<String>, targetArgs: List<String>, registryArgs: List<String>, extraArgs: List<String>): String {
        val project = coroutineContext.project
        val cmdArgs = mutableListOf("fmt")
        if (check) cmdArgs.add("--check")
        cmdArgs.add("--manifest-path")
        cmdArgs.add(Path.of(workDir, "Cargo.toml").toString())
        cmdArgs.addAll(workspaceArgs)
        cmdArgs.addAll(targetArgs)
        cmdArgs.addAll(registryArgs)
        cmdArgs.addAll(extraArgs)
        val (exitCode, output) = runCargoSync(workDir, cmdArgs, timeout = 60)
        if (exitCode != 0) mcpFail("cargo fmt failed:\n$output")
        return output.trim().ifEmpty { "cargo fmt: OK" }
    }

    private suspend fun runTests(
        workDir: String, filter: String?, noRun: Boolean,
        workspaceArgs: List<String>, targetArgs: List<String>, registryArgs: List<String>, extraArgs: List<String>
    ): String {
        val project = coroutineContext.project
        val session = SessionManager.create("cargo_test")
        val cmdArgs = mutableListOf("test")
        if (noRun) cmdArgs.add("--no-run")
        filter?.let { cmdArgs.add(it) }
        cmdArgs.add("--manifest-path")
        cmdArgs.add(Path.of(workDir, "Cargo.toml").toString())
        cmdArgs.addAll(workspaceArgs)
        cmdArgs.addAll(targetArgs)
        cmdArgs.addAll(registryArgs)
        cmdArgs.addAll(extraArgs)

        try {
            val cargoCmd = findCargo(project)
            val cmd = com.intellij.execution.configurations.GeneralCommandLine(cargoCmd)
                .withWorkDirectory(workDir)
                .withParameters(*cmdArgs.toTypedArray())
                .withRedirectErrorStream(true)
            session.appendLine("Running: ${cmd.commandLineString}")
            val handler = OSProcessHandler(cmd)
            session.tag = handler
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val text = event.text.trimEnd('\n', '\r')
                    if (text.isNotEmpty()) session.appendLine(text)
                }
                override fun processTerminated(event: ProcessEvent) {
                    session.exitCode = event.exitCode
                    session.status = if (event.exitCode == 0) "succeeded" else "failed"
                }
            })
            handler.startNotify()
        } catch (e: Exception) {
            session.status = "failed"
            session.appendLine("Error: ${e.message}")
        }
        return buildJsonObject { put("sessionId", session.id) }.toString()
    }

    private suspend fun runBench(
        workDir: String, filter: String?, noRun: Boolean,
        workspaceArgs: List<String>, targetArgs: List<String>, registryArgs: List<String>, extraArgs: List<String>
    ): String {
        val project = coroutineContext.project
        val session = SessionManager.create("cargo_bench")
        val cmdArgs = mutableListOf("bench")
        if (noRun) cmdArgs.add("--no-run")
        filter?.let { cmdArgs.add(it) }
        cmdArgs.add("--manifest-path")
        cmdArgs.add(Path.of(workDir, "Cargo.toml").toString())
        cmdArgs.addAll(workspaceArgs)
        cmdArgs.addAll(targetArgs)
        cmdArgs.addAll(registryArgs)
        cmdArgs.addAll(extraArgs)

        try {
            val cargoCmd = findCargo(project)
            val cmd = com.intellij.execution.configurations.GeneralCommandLine(cargoCmd)
                .withWorkDirectory(workDir)
                .withParameters(*cmdArgs.toTypedArray())
                .withRedirectErrorStream(true)
            session.appendLine("Running: ${cmd.commandLineString}")
            val handler = OSProcessHandler(cmd)
            session.tag = handler
            handler.addProcessListener(object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val text = event.text.trimEnd('\n', '\r')
                    if (text.isNotEmpty()) session.appendLine(text)
                }
                override fun processTerminated(event: ProcessEvent) {
                    session.exitCode = event.exitCode
                    session.status = if (event.exitCode == 0) "succeeded" else "failed"
                }
            })
            handler.startNotify()
        } catch (e: Exception) {
            session.status = "failed"
            session.appendLine("Error: ${e.message}")
        }
        return buildJsonObject { put("sessionId", session.id) }.toString()
    }

    private suspend fun runDoc(workDir: String, workspaceArgs: List<String>, targetArgs: List<String>, registryArgs: List<String>, extraArgs: List<String>): String {
        val cmdArgs = mutableListOf("doc")
        cmdArgs.add("--manifest-path")
        cmdArgs.add(Path.of(workDir, "Cargo.toml").toString())
        cmdArgs.addAll(workspaceArgs)
        cmdArgs.addAll(targetArgs)
        cmdArgs.addAll(registryArgs)
        cmdArgs.addAll(extraArgs)
        val (exitCode, output) = runCargoSync(workDir, cmdArgs, timeout = 120)
        if (exitCode != 0) mcpFail("cargo doc failed:\n$output")
        return output.trim().ifEmpty { "cargo doc: OK" }
    }

    private suspend fun runAudit(workDir: String, workspaceArgs: List<String>, targetArgs: List<String>, registryArgs: List<String>, extraArgs: List<String>): String {
        val cmdArgs = mutableListOf("audit")
        cmdArgs.add("--manifest-path")
        cmdArgs.add(Path.of(workDir, "Cargo.toml").toString())
        cmdArgs.addAll(workspaceArgs)
        cmdArgs.addAll(targetArgs)
        cmdArgs.addAll(registryArgs)
        cmdArgs.addAll(extraArgs)
        val (exitCode, output) = runCargoSync(workDir, cmdArgs, timeout = 60)
        if (exitCode != 0) mcpFail("cargo audit failed:\n$output")
        return output.trim().ifEmpty { "cargo audit: OK" }
    }

    private suspend fun runDeny(workDir: String, workspaceArgs: List<String>, targetArgs: List<String>, registryArgs: List<String>, extraArgs: List<String>): String {
        val cmdArgs = mutableListOf("deny")
        cmdArgs.add("--manifest-path")
        cmdArgs.add(Path.of(workDir, "Cargo.toml").toString())
        cmdArgs.addAll(workspaceArgs)
        cmdArgs.addAll(targetArgs)
        cmdArgs.addAll(registryArgs)
        cmdArgs.addAll(extraArgs)
        val (exitCode, output) = runCargoSync(workDir, cmdArgs, timeout = 180)
        if (exitCode != 0) mcpFail("cargo deny failed:\n$output")
        return output.trim().ifEmpty { "cargo deny: OK" }
    }

    private suspend fun runOutdated(workDir: String, workspaceArgs: List<String>, targetArgs: List<String>, registryArgs: List<String>, extraArgs: List<String>): String {
        val cmdArgs = mutableListOf("outdated")
        cmdArgs.add("--manifest-path")
        cmdArgs.add(Path.of(workDir, "Cargo.toml").toString())
        cmdArgs.addAll(workspaceArgs)
        cmdArgs.addAll(targetArgs)
        cmdArgs.addAll(registryArgs)
        cmdArgs.addAll(extraArgs)
        val (exitCode, output) = runCargoSync(workDir, cmdArgs, timeout = 120)
        if (exitCode != 0) mcpFail("cargo outdated failed:\n$output")
        return output.trim().ifEmpty { "cargo outdated: OK" }
    }

    private suspend fun runVerify(workDir: String, workspaceArgs: List<String>, targetArgs: List<String>, registryArgs: List<String>, extraArgs: List<String>): String {
        val cmdArgs = mutableListOf("publish", "--dry-run")
        cmdArgs.add("--manifest-path")
        cmdArgs.add(Path.of(workDir, "Cargo.toml").toString())
        cmdArgs.addAll(workspaceArgs)
        cmdArgs.addAll(targetArgs)
        cmdArgs.addAll(registryArgs)
        cmdArgs.addAll(extraArgs)
        val (exitCode, output) = runCargoSync(workDir, cmdArgs, timeout = 120)
        if (exitCode != 0) mcpFail("cargo publish --dry-run failed:\n$output")
        return output.trim().ifEmpty { "cargo verify: OK (dry-run successful)" }
    }

    private suspend fun runPublish(workDir: String, workspaceArgs: List<String>, targetArgs: List<String>, registryArgs: List<String>, extraArgs: List<String>): String {
        val cmdArgs = mutableListOf("publish")
        cmdArgs.add("--manifest-path")
        cmdArgs.add(Path.of(workDir, "Cargo.toml").toString())
        cmdArgs.addAll(workspaceArgs)
        cmdArgs.addAll(targetArgs)
        cmdArgs.addAll(registryArgs)
        cmdArgs.addAll(extraArgs)
        val (exitCode, output) = runCargoSync(workDir, cmdArgs, timeout = 120)
        if (exitCode != 0) mcpFail("cargo publish failed:\n$output")
        return output.trim().ifEmpty { "cargo publish: OK" }
    }

    private suspend fun runLogin(workDir: String, token: String?, registry: String?, index: String?, extraArgs: List<String>): String {
        if (token.isNullOrBlank()) mcpFail("token is required for login")
        val cmdArgs = mutableListOf("login")
        token?.let { cmdArgs.add("--token"); cmdArgs.add(it) }
        registry?.let { cmdArgs.add("--registry"); cmdArgs.add(it) }
        index?.let { cmdArgs.add("--index"); cmdArgs.add(it) }
        cmdArgs.addAll(extraArgs)
        val (exitCode, output) = runCargoSync(workDir, cmdArgs, timeout = 30)
        if (exitCode != 0) mcpFail("cargo login failed:\n$output")
        return output.trim().ifEmpty { "cargo login: OK" }
    }

    private suspend fun runLogout(workDir: String, registry: String?, extraArgs: List<String>): String {
        val cmdArgs = mutableListOf("logout")
        registry?.let { cmdArgs.add("--registry"); cmdArgs.add(it) }
        cmdArgs.addAll(extraArgs)
        val (exitCode, output) = runCargoSync(workDir, cmdArgs, timeout = 30)
        if (exitCode != 0) mcpFail("cargo logout failed:\n$output")
        return output.trim().ifEmpty { "cargo logout: OK" }
    }

    private suspend fun runOwner(
        workDir: String, subcommand: String?, crate: String?, user: String?,
        token: String?, registry: String?, extraArgs: List<String>
    ): String {
        if (subcommand.isNullOrBlank()) mcpFail("owner subcommand is required (add, remove, list)")
        if (crate.isNullOrBlank()) mcpFail("crate name is required for owner action")
        val cmdArgs = mutableListOf("owner")
        cmdArgs.add(subcommand)
        cmdArgs.add(crate)
        user?.let { cmdArgs.add(it) }
        token?.let { cmdArgs.add("--token"); cmdArgs.add(it) }
        registry?.let { cmdArgs.add("--registry"); cmdArgs.add(it) }
        cmdArgs.addAll(extraArgs)
        val (exitCode, output) = runCargoSync(workDir, cmdArgs, timeout = 60)
        if (exitCode != 0) mcpFail("cargo owner failed:\n$output")
        return output.trim().ifEmpty { "cargo owner $subcommand: OK" }
    }

    // Helper functions for building common argument groups

    companion object {
        @JvmStatic
        internal fun buildWorkspaceArgs(pkg: String?, workspace: Boolean?, exclude: String?): List<String> {
        val args = mutableListOf<String>()
        pkg?.let { 
            it.split(",").forEach { p -> args.add("--package"); args.add(p.trim()) }
        }
        if (workspace == true) args.add("--workspace")
        else if (workspace == false) args.add("--no-workspace")
        exclude?.let { 
            it.split(",").forEach { e -> args.add("--exclude"); args.add(e.trim()) }
        }
        return args
    }

    @JvmStatic
    internal fun buildTargetArgs(
        lib: Boolean, bin: String?, testTargets: String?, benchTargets: String?,
        exampleTargets: String?, allTargets: Boolean
    ): List<String> {
        val args = mutableListOf<String>()
        if (allTargets) {
            args.add("--all-targets")
            return args
        }
        if (lib) args.add("--lib")
        bin?.let { 
            if (it.lowercase() == "true") args.add("--bins")
            else it.split(",").forEach { b -> args.add("--bin"); args.add(b.trim()) }
        }
        testTargets?.let { 
            if (it.lowercase() == "true") args.add("--tests")
            else it.split(",").forEach { t -> args.add("--test"); args.add(t.trim()) }
        }
        benchTargets?.let { 
            if (it.lowercase() == "true") args.add("--benches")
            else it.split(",").forEach { b -> args.add("--bench"); args.add(b.trim()) }
        }
        exampleTargets?.let { 
            if (it.lowercase() == "true") args.add("--examples")
            else it.split(",").forEach { e -> args.add("--example"); args.add(e.trim()) }
        }
        return args
    }

    @JvmStatic
    internal fun buildRegistryArgs(registry: String?, token: String?, index: String?): List<String> {
        val args = mutableListOf<String>()
        registry?.let { args.add("--registry"); args.add(it) }
        token?.let { args.add("--token"); args.add(it) }
        index?.let { args.add("--index"); args.add(it) }
        return args
    }

    // Timeouts stay under the ~60 s MCP call limit; the handler kills the process on timeout.
    private suspend fun runCargoSync(workDir: String, args: List<String>, timeout: Long = 30): Pair<Int, String> {
        val project = coroutineContext.project
        val cargoCmd = findCargo(project)
        val cmd = com.intellij.execution.configurations.GeneralCommandLine(cargoCmd)
            .withWorkDirectory(workDir)
            .withParameters(*args.toTypedArray())
            .withRedirectErrorStream(true)
        val result = withContext(Dispatchers.IO) {
            CapturingProcessHandler(cmd).runProcess(TimeUnit.SECONDS.toMillis(timeout).toInt(), true)
        }
        if (result.isTimeout) return -1 to "Command timed out after ${timeout}s: cargo ${args.joinToString(" ")}"
        return result.exitCode to result.stdout
    }

    private fun findCargo(project: com.intellij.openapi.project.Project): String {
        // Use the cargo from the Rust toolchain configured in the IDE
        // Try to find cargo via rustup or system PATH
        val env = System.getenv()
        val cargoHome = env["CARGO_HOME"] ?: env["RUSTUP_HOME"]?.let { "$it/cargo" } ?: System.getProperty("user.home") + "/.cargo"
        val cargoBin = Path.of(cargoHome, "bin", if (System.getProperty("os.name").startsWith("Windows")) "cargo.exe" else "cargo")
        if (cargoBin.toFile().exists()) return cargoBin.toString()
        // Fallback to PATH
        return "cargo"
    }
}
}