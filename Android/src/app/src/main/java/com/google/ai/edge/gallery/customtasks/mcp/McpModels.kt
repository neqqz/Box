package com.google.ai.edge.gallery.customtasks.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import kotlinx.serialization.Serializable

@Serializable
data class McpToolConfig(
  val name: String,
  val description: String = "",
  val inputSchema: String = "",
  val enabled: Boolean = true,
  val alwaysAllow: Boolean = false,
)

@Serializable
data class McpServerConfig(
  val url: String,
  val name: String = "",
  val version: String = "",
  val description: String = "",
  val enabled: Boolean = true,
  val tools: List<McpToolConfig> = emptyList(),
)

data class McpServerState(
  val mcpServer: McpServerConfig,
  val client: Client? = null,
  val error: String? = null,
)

data class McpManagerUiState(
  val mcpServers: List<McpServerState> = emptyList(),
  val loadingMcpServer: Boolean = false,
  val error: String? = null,
)
