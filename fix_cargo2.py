#!/usr/bin/env python3

with open('src/main/kotlin/com/github/tropin/ridermcp/cargo/CargoTools.kt', 'r') as f:
    content = f.read()

# 1. Fix buildTargetArgs to return early when allTargets
old_buildTargetArgs = '''    private fun buildTargetArgs(
        lib: Boolean, bin: String?, testTargets: String?, benchTargets: String?,
        exampleTargets: String?, allTargets: Boolean
    ): List<String> {
        val args = mutableListOf<String>()
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
        if (allTargets) args.add("--all-targets")
        return args
    }'''

new_buildTargetArgs = '''    @JvmStatic
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
    }'''

content = content.replace(old_buildTargetArgs, new_buildTargetArgs)

# 2. Make helper functions in companion object
old_helper = '''    // Helper functions for building common argument groups

    private fun buildWorkspaceArgs(pkg: String?, workspace: Boolean?, exclude: String?): List<String> {
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

    private fun buildTargetArgs(
        lib: Boolean, bin: String?, testTargets: String?, benchTargets: String?,
        exampleTargets: String?, allTargets: Boolean
    ): List<String> {
        val args = mutableListOf<String>()
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
        if (allTargets) args.add("--all-targets")
        return args
    }

    private fun buildRegistryArgs(registry: String?, token: String?, index: String?): List<String> {
        val args = mutableListOf<String>()
        registry?.let { args.add("--registry"); args.add(it) }
        token?.let { args.add("--token"); args.add(it) }
        index?.let { args.add("--index"); args.add(it) }
        return args
    }

    private fun validateProjectPath(project: com.intellij.openapi.project.Project, path: String): String? {
        if (path.startswith("-")) return "Project path must not start with '-'"
        val basePath = project.basePath ?: return "No project path"
        val resolved = Path.of(basePath).resolve(path).normalize()
        if (!resolved.startsWith(Path.of(basePath).normalize())) return "Project path escapes project directory"
        if (!resolved.toFile().exists()) return "Project file not found: $path"
        return null
    }

    # Timeouts stay under the ~60 s MCP call limit; the handler kills the process on timeout.
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
        if (result.isTimeout) return -1 to "Command timed out after ${timeout}s: cargo ${args.joinToString(\" \")}"
        return result.exitCode to result.stdout
    }

    private fun findCargo(project: com.intellij.openapi.project.Project): String {
        # Use the cargo from the Rust toolchain configured in the IDE
        # Try to find cargo via rustup or system PATH
        val env = System.getenv()
        val cargoHome = env["CARGO_HOME"] ?: env["RUSTUP_HOME"]?.let { "$it/cargo" } ?: System.getProperty("user.home") + "/.cargo"
        val cargoBin = Path.of(cargoHome, "bin", if (System.getProperty("os.name").startsWith("Windows")) "cargo.exe" else "cargo")
        if (cargoBin.toFile().exists()) return cargoBin.toString()
        # Fallback to PATH
        return "cargo"
    }'''

new_helper = '''// Helper functions for building common argument groups

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

        @JvmStatic
        internal fun validateProjectPath(project: com.intellij.openapi.project.Project, path: String): String? {
            if (path.startswith("-")) return "Project path must not start with '-'"
            val basePath = project.basePath ?: return "No project path"
            val resolved = Path.of(basePath).resolve(path).normalize()
            if (!resolved.startsWith(Path.of(basePath).normalize())) return "Project path escapes project directory"
            if (!resolved.toFile().exists()) return "Project file not found: $path"
            return null
        }
    }

    # Timeouts stay under the ~60 s MCP call limit; the handler kills the process on timeout.
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
        if (result.isTimeout) return -1 to "Command timed out after ${timeout}s: cargo ${args.joinToString(\" \")}"
        return result.exitCode to result.stdout
    }

    private fun findCargo(project: com.intellij.openapi.project.Project): String {
        # Use the cargo from the Rust toolchain configured in the IDE
        # Try to find cargo via rustup or system PATH
        val env = System.getenv()
        val cargoHome = env["CARGO_HOME"] ?: env["RUSTUP_HOME"]?.let { "$it/cargo" } ?: System.getProperty("user.home") + "/.cargo"
        val cargoBin = Path.of(cargoHome, "bin", if (System.getProperty("os.name").startsWith("Windows")) "cargo.exe" else "cargo")
        if (cargoBin.toFile().exists()) return cargoBin.toString()
        # Fallback to PATH
        return "cargo"
    }'''

content = content.replace(old_helper, new_helper)

with open('src/main/kotlin/com/github/tropin/ridermcp/cargo/CargoTools.kt', 'w') as f:
    f.write(content)
print('Done')