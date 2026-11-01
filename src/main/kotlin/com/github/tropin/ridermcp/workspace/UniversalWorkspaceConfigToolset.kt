package com.github.tropin.ridermcp.workspace

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.github.tropin.ridermcp.SessionManager
import com.github.tropin.ridermcp.projectDir
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext
import java.nio.file.Path

class UniversalWorkspaceConfigToolset : McpToolset {

    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE)
    @McpTool
    @McpDescription(
        "Read-only snapshot of workspace configuration: project root, build output paths, run configurations, " +
            "and the last rider_* operation captured by this extension. " +
            "Provides IDE-appropriate information: .NET runnable projects in Rider, Maven/Gradle modules in IntelliJ, " +
            "Cargo targets in RustRover, CMake targets in CLion, etc. " +
            "Does NOT start any builds; properties come from IDE's project model and may be stale while indexing."
    )
    suspend fun rider_workspace_config(): String {
        val project = coroutineContext.project
        val projectDir = project.projectDir()

        // Get build output paths using platform APIs
        val buildOutputs = ApplicationManager.getApplication().runReadAction { getBuildOutputs(project) } as List<JsonObject>

        // Get run configurations
        val runConfigs = ApplicationManager.getApplication().runReadAction { getRunConfigurations(project) } as List<JsonObject>

        // Get last operation from session manager
        val lastOp = SessionManager.running().maxByOrNull { it.createdAt }?.let { running ->
            buildJsonObject {
                put("id", running.id)
                put("type", running.type)
                put("status", running.status)
            }
        } ?: SessionManager.listAll().filter { it.status != "running" }.maxByOrNull { it.createdAt }?.let { last ->
            buildJsonObject {
                put("id", last.id)
                put("type", last.type)
                put("status", last.status)
                last.metadata["configName"]?.let { put("configName", it) }
                last.metadata["configuration"]?.let { put("configuration", it) }
                last.metadata["platform"]?.let { put("platform", it) }
                last.metadata["runtimeIdentifier"]?.let { put("runtimeIdentifier", it) }
                last.metadata["publishDir"]?.let { put("publishDir", it) }
                last.metadata["publishProfile"]?.let { put("publishProfile", it) }
                last.metadata["pubxmlPath"]?.let { put("pubxmlPath", it) }
            }
        }

        // IDE-specific extensions
        val ideSpecific = ApplicationManager.getApplication().runReadAction {
            getIdeSpecificConfig(project)
        }

        val outputsArray: List<JsonObject> = buildOutputs
        val configsArray: List<JsonObject> = runConfigs
        val buildOutputsArray = kotlinx.serialization.json.JsonArray(outputsArray.map { it as kotlinx.serialization.json.JsonElement })
        val runConfigsArray = kotlinx.serialization.json.JsonArray(configsArray.map { it as kotlinx.serialization.json.JsonElement })
        val ideSpecificJson = if (ideSpecific != null) {
                kotlinx.serialization.json.JsonObject(mapOf("ideSpecific" to (ideSpecific as kotlinx.serialization.json.JsonElement)))
            } else {
                kotlinx.serialization.json.JsonPrimitive("none")
            }
        val result = kotlinx.serialization.json.buildJsonObject {
            put("projectDir", projectDir?.toString() ?: "unknown")
            put("ide", getIdeName())
            put("buildOutputs", buildOutputsArray)
            put("runConfigurations", runConfigsArray)
            if (lastOp != null) put("lastOperation", lastOp)
            put("ideSpecific", ideSpecificJson)
        }
        return result.toString()
    }

    private fun getBuildOutputs(project: Project): List<JsonObject> {
        val outputs = mutableListOf<JsonObject>()

        // Try to get compiler output directories
        val modules = ModuleManager.getInstance(project).modules
        for (module in modules) {
            val rootManager = ModuleRootManager.getInstance(module)
            val contentRoots = rootManager.contentRoots
            val sourceRoots = rootManager.getSourceRoots()
            
            // Get compiler output path using reflection to handle API differences
            val compilerOutputPath = try {
                val compilerOutput = rootManager.javaClass.getMethod("compilerOutput").invoke(rootManager) as? VirtualFile
                compilerOutput?.path
            } catch (_: Throwable) {
                // Fallback: try getCompilerOutputUrl
                try {
                    val url = rootManager.javaClass.getMethod("getCompilerOutputUrl").invoke(rootManager) as? String
                    url?.let { VfsUtilCore.urlToPath(it)?.toString() }
                } catch (_: Throwable) {
                    null
                }
            }

            if (compilerOutputPath != null && compilerOutputPath.isNotBlank()) {
                val contentRootPaths = contentRoots.map { it.path }.toList()
                val sourceRootPaths = sourceRoots.map { it.path }.toList()
                outputs.add(buildJsonObject {
                    put("module", module.name)
                    put("outputPath", compilerOutputPath)
                    put("contentRoots", buildJsonArray { contentRootPaths.forEach { add(it) } })
                    put("sourceRoots", buildJsonArray { sourceRootPaths.forEach { add(it) } })
                })
            }
        }

        // Project-level compiler output (if any) - use reflection
        val projectCompilerOutput = try {
            val url = ProjectRootManager.getInstance(project).javaClass.getMethod("getProjectCompilerOutputUrl").invoke(ProjectRootManager.getInstance(project)) as? String
            url?.let { VfsUtilCore.urlToPath(it)?.toString() }
        } catch (_: Throwable) {
            null
        }
        projectCompilerOutput?.let { path ->
            outputs.add(buildJsonObject {
                put("scope", "project")
                put("outputPath", path)
            })
        }

        return outputs
    }

    private fun getRunConfigurations(project: Project): List<JsonObject> {
        // Run configurations are accessed via RunManager
        val runManager = com.intellij.execution.RunManager.getInstance(project)
        return runManager.allSettings.map { config ->
            buildJsonObject {
                put("name", config.name)
                put("type", config.type?.id ?: "unknown")
                put("factoryName", config.getFactory()?.name ?: "")
            }
        }
    }

    private fun getIdeSpecificConfig(project: Project): JsonObject? {
        return when {
            isRiderProject(project) -> getRiderSpecificConfig(project)
            isIntelliJProject(project) -> getIntelliJSpecificConfig(project)
            isPyCharmProject(project) -> getPyCharmSpecificConfig(project)
            isGoLandProject(project) -> getGoLandSpecificConfig(project)
            isWebStormProject(project) -> getWebStormSpecificConfig(project)
            isCLionProject(project) -> getCLionSpecificConfig(project)
            isPhpStormProject(project) -> getPhpStormSpecificConfig(project)
            isRubyMineProject(project) -> getRubyMineSpecificConfig(project)
            isRustRoverProject(project) -> getRustRoverSpecificConfig(project)
            else -> null
        }
    }

    private fun isRiderProject(project: Project): Boolean {
        return try {
            Class.forName("com.jetbrains.rider.RiderBundle")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isIntelliJProject(project: Project): Boolean {
        return try {
            // Check for Maven/Gradle project files
            val baseDir = project.baseDir
            baseDir?.findFileByRelativePath("pom.xml") != null ||
            baseDir?.findFileByRelativePath("build.gradle") != null ||
            baseDir?.findFileByRelativePath("build.gradle.kts") != null ||
            baseDir?.findFileByRelativePath("settings.gradle") != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun isPyCharmProject(project: Project): Boolean {
        return try {
            Class.forName("com.jetbrains.python.PythonBundle")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isGoLandProject(project: Project): Boolean {
        return try {
            Class.forName("com.jetbrains.go.GoBundle")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isWebStormProject(project: Project): Boolean {
        return try {
            Class.forName("com.jetbrains.javascript.JSBundle")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isCLionProject(project: Project): Boolean {
        return try {
            Class.forName("com.jetbrains.cidr.CidrBundle")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isPhpStormProject(project: Project): Boolean {
        return try {
            Class.forName("com.jetbrains.php.PhpBundle")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isRubyMineProject(project: Project): Boolean {
        return try {
            Class.forName("org.jetbrains.plugins.ruby.RubyBundle")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isRustRoverProject(project: Project): Boolean {
        return try {
            Class.forName("org.rust.RustBundle")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun getIdeName(): String {
        return when {
            try { Class.forName("com.jetbrains.rider.RiderBundle"); true } catch (_: Throwable) { false } -> "Rider"
            try { Class.forName("com.jetbrains.python.PythonBundle"); true } catch (_: Throwable) { false } -> "PyCharm"
            try { Class.forName("com.jetbrains.go.GoBundle"); true } catch (_: Throwable) { false } -> "GoLand"
            try { Class.forName("com.jetbrains.javascript.JSBundle"); true } catch (_: Throwable) { false } -> "WebStorm"
            try { Class.forName("com.jetbrains.cidr.CidrBundle"); true } catch (_: Throwable) { false } -> "CLion"
            try { Class.forName("com.jetbrains.php.PhpBundle"); true } catch (_: Throwable) { false } -> "PhpStorm"
            try { Class.forName("org.jetbrains.plugins.ruby.RubyBundle"); true } catch (_: Throwable) { false } -> "RubyMine"
            try { Class.forName("org.rust.RustBundle"); true } catch (_: Throwable) { false } -> "RustRover"
            else -> "IntelliJ IDEA"
        }
    }

    // IDE-specific config providers
    private fun getRiderSpecificConfig(project: Project): JsonObject? {
        try {
            val solutionClass = Class.forName("com.jetbrains.rd.ide.model.Solution")
            val solution = Class.forName("com.jetbrains.rider.projectView.SolutionHostExtensionsKt")
                .getMethod("getSolution", Project::class.java)
                .invoke(null, project)
            val runnableModel = solution?.let { s ->
                Class.forName("com.jetbrains.rider.model.RunnableProjectsModel_PregeneratedKt")
                    .getMethod("getRunnableProjectsModel", solutionClass)
                    .invoke(null, s) as? com.jetbrains.rider.model.RunnableProjectsModel
            }

            val projectDir = project.projectDir()
            val projects = runnableModel?.projects?.valueOrNull?.map { rp ->
                projectConfig(projectDir, rp)
            } ?: emptyList()

            return if (projects.isNotEmpty()) {
                buildJsonObject {
                    putJsonArray("runnableProjects") {
                        projects.forEach { add(it) }
                    }
                }
            } else null
        } catch (_: Throwable) {
            null
        }
        return null // Explicit return for compiler
    }

    private fun projectConfig(projectDir: Path?, rp: com.jetbrains.rider.model.RunnableProject): JsonObject {
        val outputs = rp.projectOutputs
        val output = outputs.firstOrNull()
        val cfg = output?.configuration
        val tfm = output?.tfm?.presentableName ?: ""

        return buildJsonObject {
            put("name", rp.name)
            put("projectFile", rp.projectFilePath)
            put("kind", rp.kind.toString())
            cfg?.let {
                put("configuration", it.configuration)
                put("platform", it.platform)
            }
            put("targetFramework", tfm)
            output?.let {
                put("exePath", it.exePath)
                put("workingDirectory", it.workingDirectory)
            }
            putJsonArray("outputs") {
                outputs.forEach { o ->
                    addJsonObject {
                        put("tfm", o.tfm?.presentableName ?: "")
                        put("configuration", o.configuration?.configuration ?: "")
                        put("platform", o.configuration?.platform ?: "")
                        put("exePath", o.exePath)
                    }
                }
            }
            putJsonObject("properties") {
                put("Configuration", cfg?.configuration ?: "")
                if (cfg?.platform?.isNotBlank() == true) put("Platform", cfg.platform)
                put("TargetFramework", tfm)
            }
        }
    }

    private fun getIntelliJSpecificConfig(project: Project): JsonObject? {
        val baseDir = project.baseDir ?: return null
        
        val hasMaven = baseDir.findFileByRelativePath("pom.xml") != null
        val hasGradle = baseDir.findFileByRelativePath("build.gradle") != null ||
                        baseDir.findFileByRelativePath("build.gradle.kts") != null ||
                        baseDir.findFileByRelativePath("settings.gradle") != null ||
                        baseDir.findFileByRelativePath("settings.gradle.kts") != null

        if (!hasMaven && !hasGradle) return null

        return buildJsonObject {
            if (hasMaven) {
                put("buildSystem", "maven")
            }
            if (hasGradle) {
                put("buildSystem", "gradle")
            }
            // Try to find modules
            val modules = ModuleManager.getInstance(project).modules
            val mavenModules = modules.filter { it.getModuleTypeName()?.contains("MAVEN", ignoreCase = true) == true }
            val gradleModules = modules.filter { it.getModuleTypeName()?.contains("GRADLE", ignoreCase = true) == true }

            if (mavenModules.isNotEmpty()) {
                putJsonArray("mavenModules") {
                    mavenModules.forEach { m ->
                        addJsonObject {
                            put("name", m.name)
                            put("path", m.moduleFilePath)
                            put("type", "maven")
                        }
                    }
                }
            }
            if (gradleModules.isNotEmpty()) {
                putJsonArray("gradleModules") {
                    gradleModules.forEach { m ->
                        addJsonObject {
                            put("name", m.name)
                            put("path", m.moduleFilePath)
                            put("type", "gradle")
                        }
                    }
                }
            }
        }
    }

    private fun getPyCharmSpecificConfig(project: Project): JsonObject? {
        val baseDir = project.baseDir ?: return null
        
        // Check for Python project files
        val hasRequirements = baseDir.findFileByRelativePath("requirements.txt") != null
        val hasPyproject = baseDir.findFileByRelativePath("pyproject.toml") != null
        val hasPipenv = baseDir.findFileByRelativePath("Pipfile") != null
        val hasPoetry = baseDir.findFileByRelativePath("poetry.lock") != null

        if (!hasRequirements && !hasPyproject && !hasPipenv && !hasPoetry) return null

        return buildJsonObject {
            put("hasRequirementsTxt", hasRequirements)
            put("hasPyprojectToml", hasPyproject)
            put("hasPipenv", hasPipenv)
            put("hasPoetry", hasPoetry)
        }
    }

    private fun getGoLandSpecificConfig(project: Project): JsonObject? {
        val baseDir = project.baseDir ?: return null
        
        val goMod = baseDir.findFileByRelativePath("go.mod")
        val goWork = baseDir.findFileByRelativePath("go.work")
        
        if (goMod == null && goWork == null) return null

        return buildJsonObject {
            goMod?.let { put("goMod", it.path) }
            goWork?.let { put("goWork", it.path) }
        }
    }

    private fun getWebStormSpecificConfig(project: Project): JsonObject? {
        val baseDir = project.baseDir ?: return null
        
        val pkgJson = baseDir.findFileByRelativePath("package.json")
        val pnpmLock = baseDir.findFileByRelativePath("pnpm-lock.yaml")
        val yarnLock = baseDir.findFileByRelativePath("yarn.lock")
        val packageLock = baseDir.findFileByRelativePath("package-lock.json")

        if (pkgJson == null) return null

        var scriptsJson: JsonObject? = null
        try {
            val content = VfsUtilCore.loadText(pkgJson)
            val json = Json.decodeFromString<Map<String, Any>>(content)
            val scripts = json["scripts"] as? Map<String, String>
            if (scripts != null && scripts.isNotEmpty()) {
                scriptsJson = buildJsonObject {
                    scripts.forEach { (k, v) -> put(k, v) }
                }
            }
        } catch (_: Throwable) {}

        return buildJsonObject {
            put("packageJson", pkgJson.path)
            pnpmLock?.let { put("pnpmLock", it.path) }
            yarnLock?.let { put("yarnLock", it.path) }
            packageLock?.let { put("packageLock", it.path) }
            scriptsJson?.let { put("scripts", it) }
        }
    }

    private fun getCLionSpecificConfig(project: Project): JsonObject? {
        val baseDir = project.baseDir ?: return null
        
        val cmakeLists = baseDir.findFileByRelativePath("CMakeLists.txt")
        val cmakePresets = baseDir.findFileByRelativePath("CMakePresets.json")
        val cmakeUserPresets = baseDir.findFileByRelativePath("CMakeUserPresets.json")

        if (cmakeLists == null) return null

        return buildJsonObject {
            put("cmakeLists", cmakeLists.path)
            cmakePresets?.let { put("cmakePresets", it.path) }
            cmakeUserPresets?.let { put("cmakeUserPresets", it.path) }
        }
    }

    private fun getPhpStormSpecificConfig(project: Project): JsonObject? {
        val baseDir = project.baseDir ?: return null
        
        val composerJson = baseDir.findFileByRelativePath("composer.json")
        val composerLock = baseDir.findFileByRelativePath("composer.lock")

        if (composerJson == null) return null

        return buildJsonObject {
            put("composerJson", composerJson.path)
            composerLock?.let { put("composerLock", it.path) }
        }
    }

    private fun getRubyMineSpecificConfig(project: Project): JsonObject? {
        val baseDir = project.baseDir ?: return null
        
        val gemfile = baseDir.findFileByRelativePath("Gemfile")
        val gemfileLock = baseDir.findFileByRelativePath("Gemfile.lock")

        if (gemfile == null) return null

        return buildJsonObject {
            put("gemfile", gemfile.path)
            gemfileLock?.let { put("gemfileLock", it.path) }
        }
    }

    private fun getRustRoverSpecificConfig(project: Project): JsonObject? {
        val baseDir = project.baseDir ?: return null
        
        val cargoToml = baseDir.findFileByRelativePath("Cargo.toml")
        val cargoLock = baseDir.findFileByRelativePath("Cargo.lock")

        if (cargoToml == null) return null

        return buildJsonObject {
            put("cargoToml", cargoToml.path)
            cargoLock?.let { put("cargoLock", it.path) }
        }
    }
}