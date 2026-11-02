plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

dependencies {
    intellijPlatform {
        rider("2026.2.3.1") {
            useInstaller = false
        }
        bundledPlugin("com.intellij.mcpServer")
        bundledPlugin("com.intellij.database")
        // Rider-only classes are needed at compile time (SweaTools, ProfilingTools, etc.)
        // but loaded only in Rider via rider-only.xml (optional dependency).
        // In RustRover these classes are never instantiated.
        // RustRover-only classes (CargoTools) loaded via rust-only.xml.
        bundledModule("intellij.rider.rdclient.dotnet")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-parameters")
    }
}

tasks.withType<Test> {
    useJUnit()
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
        changeNotes = file("CHANGELOG.html").readText()
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        failureLevel = listOf(
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
        )
        ides {
            // An installed Rider when there is one (RIDER_PATH, else the default macOS location); otherwise
            // the build's own Rider artifact, so verifyPlugin runs on any OS and in CI without an install.
            val localRider = providers.environmentVariable("RIDER_PATH").orElse("/Applications/Rider.app/Contents").get()
            val hasLocalRider = file(localRider).exists()
            if (hasLocalRider) local(localRider)
            // Every Rider line the plugin claims (sinceBuild..EAP): the Marketplace verifier rejected
            // 1.2.7 on the 263 EAP, which the local IDE alone does not catch.
            // Rider ships as maven artifacts only (no installer): list versions explicitly and bump the EAP.
            val versions = when {
                providers.gradleProperty("verifyAllRiders").isPresent -> listOf("2026.2.3.1", "2026.3-EAP7-SNAPSHOT")
                !hasLocalRider -> listOf("2026.2.3.1")
                else -> emptyList()
            }
            for (version in versions) {
                create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.Rider, version) {
                    useInstaller = false
                }
            }
            // RustRover verification — only on macOS (no Linux Maven artifacts)
            if (System.getProperty("os.name").lowercase().startsWith("mac")) {
                for (version in versions) {
                    create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.RustRover, version) {
                        useInstaller = false
                    }
                }
            }
            // GoLand verification — only on macOS (no Linux Maven artifacts)
            if (System.getProperty("os.name").lowercase().startsWith("mac")) {
                for (version in versions) {
                    create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.GoLand, version) {
                        useInstaller = false
                    }
                }
            }
            // PyCharm verification — use PyCharm Professional (pycharmPC is discontinued since 2025.3)
            for (version in versions) {
                create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.PyCharm, version) {
                    useInstaller = false
                }
            }
            // WebStorm verification — only on macOS (no Linux Maven artifacts)
            if (System.getProperty("os.name").lowercase().startsWith("mac")) {
                for (version in versions) {
                    create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.WebStorm, version) {
                        useInstaller = false
                    }
                }
            }
            // IntelliJ IDEA Community (IC) is deprecated as of 2025.3, not available as verification target
            // Verify on Rider, RustRover, PyCharm, GoLand, and WebStorm only; universal tools work across all IDEs
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// Production guard: README tool table and @McpDescription blocks must stay in
// sync with the real @McpTool set. Fails the build on drift.
tasks.register("checkToolDocs") {
    group = "verification"
    description = "Asserts every rider_* tool is documented in README.md and description blocks stay within budget."
    val srcDir = projectDir.resolve("src/main/kotlin")
    val readmeFile = projectDir.resolve("README.md")
    doLast {
        val sources = srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val blockRe = Regex("suspend fun (rider_\\w+)")
        val strRe = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"", RegexOption.DOT_MATCHES_ALL)
        val tools = mutableMapOf<String, Int>()
        sources.forEach { f ->
            f.readText().split(Regex("@McpTool(?!\\w)")).drop(1).forEach { block ->
                val name = blockRe.find(block)?.groupValues?.get(1) ?: return@forEach
                val head = block.substringBefore("suspend fun")
                tools[name] = strRe.findAll(head).sumOf { it.groupValues[1].length }
            }
        }
        if (tools.isEmpty()) throw GradleException("checkToolDocs: no rider_* tools found")
        val readme = readmeFile.readText()
        val missing = tools.keys.filter { !readme.contains("`$it`") }
        if (missing.isNotEmpty()) throw GradleException("Tools missing from README.md: $missing")
        val over = tools.filter { it.value > 1100 }
        if (over.isNotEmpty()) throw GradleException("Description budget exceeded (1100 chars): $over")
        println("checkToolDocs: ${tools.size} tools documented, max block ${tools.values.max()} chars — OK")
    }
}

tasks.findByName("check")?.dependsOn("checkToolDocs")

// Generate CHANGELOG.md from conventional commits
tasks.register("generateChangelog") {
    group = "documentation"
    description = "Generates CHANGELOG.md from conventional commits since last tag"
    doLast {
        // Get last tag using git describe, fallback to first commit
        val lastTag = try {
            "git describe --tags --abbrev=0".runCommand(projectDir).trim()
        } catch (e: Exception) {
            "git log --format='%H' --reverse".runCommand(projectDir).lines().firstOrNull() ?: ""
        }
        val range = if (lastTag.isNotEmpty()) "$lastTag..HEAD" else "HEAD"

        val log = "git log $range --pretty=format:%h|%s --reverse"
            .runCommand(projectDir)

        val changelog = StringBuilder()
            .append("# Changelog\n\n")
            .append("All notable changes to this project will be documented in this file.\n\n")
            .append("The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),\n")
            .append("and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).\n\n")

        val commitsByType = mutableMapOf<String, MutableList<String>>()
        val typeOrder = listOf("feat", "fix", "perf", "refactor", "docs", "test", "build", "ci", "chore", "revert")
        val typeLabels = mapOf(
            "feat" to "Features",
            "fix" to "Bug Fixes",
            "perf" to "Performance Improvements",
            "refactor" to "Refactoring",
            "docs" to "Documentation",
            "test" to "Tests",
            "build" to "Build System",
            "ci" to "Continuous Integration",
            "chore" to "Chores",
            "revert" to "Reverts"
        )

        log.lines().forEach { line ->
            val parts = line.split("|", limit = 2)
            if (parts.size >= 2) {
                val hash = parts[0]
                val msg = parts[1].trim()
                
                // Try conventional commit format first
                val typeMatch = Regex("^(?:v\\d+\\.\\d+\\.\\d+:)?(\\w+)(?:\\([^)]+\\))?:\\s*(.+)").matchEntire(msg)
                // Fallback for version-only commits (e.g., "v2.11.0: Universal test runner")
                val versionMatch = Regex("^v\\d+\\.\\d+\\.\\d+:\\s*(.+)").matchEntire(msg)
                
                val typeDesc = if (typeMatch != null) {
                    typeMatch.groupValues[1] to typeMatch.groupValues[2]
                } else if (versionMatch != null) {
                    "feat" to versionMatch.groupValues[1]
                } else {
                    null
                }
                
                typeDesc?.let { (type, desc) ->
                    val entry = "- $desc ($hash)"
                    commitsByType.getOrPut(type) { mutableListOf() }.add(entry)
                }
            }
        }

        typeOrder.forEach { type ->
            commitsByType[type]?.let { entries ->
                val label = typeLabels[type] ?: type.replaceFirstChar { it.uppercase() }
                changelog.append("## $label\n\n")
                entries.forEach { changelog.append("$it\n") }
                changelog.append("\n")
            }
        }

        changelog.append("---\n\n*Generated by Gradle task `generateChangelog`*")
        file("CHANGELOG.md").writeText(changelog.toString())
        println("Generated CHANGELOG.md")
    }
}

private fun String.runCommand(workDir: File): String {
    val process = ProcessBuilder(*this.split(" ").toTypedArray())
        .directory(workDir)
        .redirectErrorStream(true)
        .start()
    val inputStream = process.inputStream
    val result = StringBuilder()
    inputStream.bufferedReader().use { reader ->
        reader.forEachLine { result.append(it).append('\n') }
    }
    process.waitFor()
    return result.toString().trim()
}

// Isolated Rider for automated evals (notes/evals/auto/evals.py sandbox up): its own sandbox config,
// so global data sources of the developer's IDE (production servers) never reach eval sessions,
// and it always runs the plugin built from the current tree.
val runIdeEvals by intellijPlatformTesting.runIde.registering {
    // The developer's installed Rider (same version as daily work), with the task's own sandbox config.
    providers.environmentVariable("EVAL_RIDER_PATH").orElse(providers.environmentVariable("RIDER_PATH")).orElse("/Applications/Rider.app").get()
        .let { path -> if (file(path).exists()) localPath = file(path) }
    task {
        args(
            providers.environmentVariable("EVAL_FIXTURE_SLN")
                .orElse(System.getProperty("user.home") + "/Code/rider-mcp-eval-fixture/EvalSlim.sln")
                .get()
        )
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf("-Didea.trust.all.projects=true", "-Djb.consents.confirmation.enabled=false", "-Djb.privacy.policy.text=<!--999.999-->")
        }
    }
}
