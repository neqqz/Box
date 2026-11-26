package com.google.ai.edge.gallery.customtasks.mcp

import com.google.ai.edge.gallery.common.SkillProgressAgentAction
import com.google.ai.edge.gallery.customtasks.agentchat.AgentTools
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class McpToolSet : ToolSet {
  var mcpManagerViewModel: McpManagerViewModel? = null
  var agentTools: AgentTools? = null

  @Tool(description = "Run a MCP tool")
  fun runMcpTool(
    @ToolParam(description = "The name of the tool to run.") toolName: String,
    @ToolParam(description = "The parameters passed to tool as input") input: String,
  ): Map<String, String> {
    val vm =
      mcpManagerViewModel
        ?: return mapOf("error" to "MCP manager not initialized", "status" to "failed")

    agentTools?.sendAgentAction(
      SkillProgressAgentAction(
        label = "Running MCP tool \"$toolName\"",
        inProgress = true,
        addItemTitle = "MCP: $toolName",
        addItemDescription = "Input: $input",
      )
    )

    val result = runBlocking(Dispatchers.IO) { vm.callTool(toolName = toolName, inputJson = input) }

    val statusLabel =
      if (result["status"] == "succeeded") "MCP tool \"$toolName\" finished"
      else "MCP tool \"$toolName\" failed"
    agentTools?.sendAgentAction(
      SkillProgressAgentAction(
        label = statusLabel,
        inProgress = false,
      )
    )

    return result
  }
}
