package com.github.tropin.ridermcp.cargo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CargoToolsTest {

    @Test
    fun `buildWorkspaceArgs with pkg only`() {
        val args = CargoToolset.buildWorkspaceArgs(pkg = "my-crate", workspace = null, exclude = null)
        assertEquals(listOf("--package", "my-crate"), args)
    }

    @Test
    fun `buildWorkspaceArgs with multiple pkgs`() {
        val args = CargoToolset.buildWorkspaceArgs(pkg = "crate1,crate2", workspace = null, exclude = null)
        assertEquals(listOf("--package", "crate1", "--package", "crate2"), args)
    }

    @Test
    fun `buildWorkspaceArgs with workspace true`() {
        val args = CargoToolset.buildWorkspaceArgs(pkg = null, workspace = true, exclude = null)
        assertEquals(listOf("--workspace"), args)
    }

    @Test
    fun `buildWorkspaceArgs with workspace false`() {
        val args = CargoToolset.buildWorkspaceArgs(pkg = null, workspace = false, exclude = null)
        assertEquals(listOf("--no-workspace"), args)
    }

    @Test
    fun `buildWorkspaceArgs with exclude`() {
        val args = CargoToolset.buildWorkspaceArgs(pkg = null, workspace = null, exclude = "excluded1,excluded2")
        assertEquals(listOf("--exclude", "excluded1", "--exclude", "excluded2"), args)
    }

    @Test
    fun `buildWorkspaceArgs with all options`() {
        val args = CargoToolset.buildWorkspaceArgs(pkg = "a,b", workspace = true, exclude = "x,y")
        assertEquals(listOf("--package", "a", "--package", "b", "--workspace", "--exclude", "x", "--exclude", "y"), args)
    }

    @Test
    fun `buildWorkspaceArgs with empty`() {
        val args = CargoToolset.buildWorkspaceArgs(pkg = null, workspace = null, exclude = null)
        assertEquals(emptyList(), args)
    }

    @Test
    fun `buildTargetArgs lib only`() {
        val args = CargoToolset.buildTargetArgs(lib = true, bin = null, testTargets = null, benchTargets = null, exampleTargets = null, allTargets = false)
        assertEquals(listOf("--lib"), args)
    }

    @Test
    fun `buildTargetArgs bin all`() {
        val args = CargoToolset.buildTargetArgs(lib = false, bin = "true", testTargets = null, benchTargets = null, exampleTargets = null, allTargets = false)
        assertEquals(listOf("--bins"), args)
    }

    @Test
    fun `buildTargetArgs bin specific`() {
        val args = CargoToolset.buildTargetArgs(lib = false, bin = "bin1,bin2", testTargets = null, benchTargets = null, exampleTargets = null, allTargets = false)
        assertEquals(listOf("--bin", "bin1", "--bin", "bin2"), args)
    }

    @Test
    fun `buildTargetArgs test all`() {
        val args = CargoToolset.buildTargetArgs(lib = false, bin = null, testTargets = "true", benchTargets = null, exampleTargets = null, allTargets = false)
        assertEquals(listOf("--tests"), args)
    }

    @Test
    fun `buildTargetArgs test specific`() {
        val args = CargoToolset.buildTargetArgs(lib = false, bin = null, testTargets = "test1,test2", benchTargets = null, exampleTargets = null, allTargets = false)
        assertEquals(listOf("--test", "test1", "--test", "test2"), args)
    }

    @Test
    fun `buildTargetArgs bench all`() {
        val args = CargoToolset.buildTargetArgs(lib = false, bin = null, testTargets = null, benchTargets = "true", exampleTargets = null, allTargets = false)
        assertEquals(listOf("--benches"), args)
    }

    @Test
    fun `buildTargetArgs bench specific`() {
        val args = CargoToolset.buildTargetArgs(lib = false, bin = null, testTargets = null, benchTargets = "bench1,bench2", exampleTargets = null, allTargets = false)
        assertEquals(listOf("--bench", "bench1", "--bench", "bench2"), args)
    }

    @Test
    fun `buildTargetArgs example all`() {
        val args = CargoToolset.buildTargetArgs(lib = false, bin = null, testTargets = null, benchTargets = null, exampleTargets = "true", allTargets = false)
        assertEquals(listOf("--examples"), args)
    }

    @Test
    fun `buildTargetArgs example specific`() {
        val args = CargoToolset.buildTargetArgs(lib = false, bin = null, testTargets = null, benchTargets = null, exampleTargets = "ex1,ex2", allTargets = false)
        assertEquals(listOf("--example", "ex1", "--example", "ex2"), args)
    }

    @Test
    fun `buildTargetArgs all targets`() {
        val args = CargoToolset.buildTargetArgs(lib = false, bin = null, testTargets = null, benchTargets = null, exampleTargets = null, allTargets = true)
        assertEquals(listOf("--all-targets"), args)
    }

    @Test
    fun `buildTargetArgs mixed`() {
        val args = CargoToolset.buildTargetArgs(
            lib = true,
            bin = "bin1",
            testTargets = "test1",
            benchTargets = "bench1",
            exampleTargets = "ex1",
            allTargets = false
        )
        assertEquals(listOf("--lib", "--bin", "bin1", "--test", "test1", "--bench", "bench1", "--example", "ex1"), args)
    }

    @Test
    fun `buildTargetArgs all targets overrides others`() {
        val args = CargoToolset.buildTargetArgs(
            lib = true,
            bin = "bin1",
            testTargets = "test1",
            benchTargets = "bench1",
            exampleTargets = "ex1",
            allTargets = true
        )
        assertEquals(listOf("--all-targets"), args)
    }

    @Test
    fun `buildTargetArgs empty`() {
        val args = CargoToolset.buildTargetArgs(lib = false, bin = null, testTargets = null, benchTargets = null, exampleTargets = null, allTargets = false)
        assertEquals(emptyList(), args)
    }

    @Test
    fun `buildRegistryArgs with all`() {
        val args = CargoToolset.buildRegistryArgs(registry = "my-registry", token = "secret-token", index = "https://my-index.com")
        assertEquals(listOf("--registry", "my-registry", "--token", "secret-token", "--index", "https://my-index.com"), args)
    }

    @Test
    fun `buildRegistryArgs with registry only`() {
        val args = CargoToolset.buildRegistryArgs(registry = "my-registry", token = null, index = null)
        assertEquals(listOf("--registry", "my-registry"), args)
    }

    @Test
    fun `buildRegistryArgs with token only`() {
        val args = CargoToolset.buildRegistryArgs(registry = null, token = "secret-token", index = null)
        assertEquals(listOf("--token", "secret-token"), args)
    }

    @Test
    fun `buildRegistryArgs with index only`() {
        val args = CargoToolset.buildRegistryArgs(registry = null, token = null, index = "https://my-index.com")
        assertEquals(listOf("--index", "https://my-index.com"), args)
    }

    @Test
    fun `buildRegistryArgs empty`() {
        val args = CargoToolset.buildRegistryArgs(registry = null, token = null, index = null)
        assertEquals(emptyList(), args)
    }
}