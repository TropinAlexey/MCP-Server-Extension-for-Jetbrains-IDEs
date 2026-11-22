package com.github.tropin.ridermcp.admin

import com.intellij.ide.InvalidateCacheService
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.project
import com.intellij.openapi.application.ApplicationManager
import kotlinx.serialization.json.*
import kotlin.coroutines.coroutineContext

class AdminToolset : McpToolset {

    @McpToolHints(destructiveHint = McpToolHintValue.TRUE)
    @McpTool
    @McpDescription("Invalidates Rider caches and restarts the IDE (returns immediately; all rider_* sessions die). Requires confirm=true. Nuclear option for stale state only: wrong highlighting, missing references, broken completion, stuck indexing, phantom errors. Do NOT use for normal build/test failures — use the built-in build_solution_start / rider_tests + rider_tool_window(windowId='Problems') first.")
    suspend fun rider_invalidate_caches(
        @McpDescription("Must be true to confirm this destructive action") confirm: Boolean = false
    ): String {
        if (!confirm) {
            return buildJsonObject {
                put("status", "confirmation_required")
                put("error", "This action restarts the IDE and kills all sessions. Pass confirm=true to proceed.")
            }.toString()
        }
        val project = coroutineContext.project
        ApplicationManager.getApplication().invokeLater {
            InvalidateCacheService.invalidateCachesAndRestart(project)
        }
        return buildJsonObject { put("status", "invalidating_and_restarting") }.toString()
    }
}
