package com.google.ai.edge.gallery.customtasks.mcp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.ai.edge.gallery.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpManagerBottomSheet(
  mcpManagerViewModel: McpManagerViewModel,
  onDismiss: (toolsChanged: Boolean) -> Unit,
) {
  val uiState by mcpManagerViewModel.uiState.collectAsState()
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val initialSignature = remember { mcpManagerViewModel.getEnabledToolsSignature() }
  val blockedByAdvProtection = remember { mcpManagerViewModel.isBlockedByAdvancedProtection() }

  var serverUrlInput by remember { mutableStateOf("") }
  var searchQuery by remember { mutableStateOf("") }

  ModalBottomSheet(
    onDismissRequest = {
      onDismiss(initialSignature != mcpManagerViewModel.getEnabledToolsSignature())
    },
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          Icons.Rounded.Hub,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = stringResource(R.string.manage_mcp_servers),
          style = MaterialTheme.typography.titleLarge,
          modifier = Modifier.weight(1f),
        )
        IconButton(
          onClick = {
            onDismiss(initialSignature != mcpManagerViewModel.getEnabledToolsSignature())
          }
        ) {
          Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_icon))
        }
      }

      if (blockedByAdvProtection) {
        Surface(
          color = MaterialTheme.colorScheme.errorContainer,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
          Text(
            text = "External MCP servers are disabled while Android Advanced Protection is active.",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
          value = serverUrlInput,
          onValueChange = { serverUrlInput = it },
          label = { Text("MCP server URL (HTTP/SSE)") },
          placeholder = { Text("http://127.0.0.1:3000/mcp") },
          singleLine = true,
          enabled = !blockedByAdvProtection && !uiState.loadingMcpServer,
          modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
          onClick = {
            mcpManagerViewModel.addOrRefreshServer(serverUrlInput)
            serverUrlInput = ""
          },
          enabled = !blockedByAdvProtection && !uiState.loadingMcpServer && serverUrlInput.isNotBlank(),
        ) {
          if (uiState.loadingMcpServer) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
          } else {
            Icon(Icons.Rounded.Add, contentDescription = null)
          }
        }
      }

      uiState.error?.let { err ->
        Surface(
          color = MaterialTheme.colorScheme.errorContainer,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
          Text(
            text = err,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(10.dp),
          )
        }
      }

      if (uiState.mcpServers.isNotEmpty()) {
        OutlinedTextField(
          value = searchQuery,
          onValueChange = { searchQuery = it },
          placeholder = { Text("Filter servers or tools…") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
      }

      val q = searchQuery.trim().lowercase()
      val filtered =
        uiState.mcpServers.filter { st ->
          q.isEmpty() ||
            st.mcpServer.name.lowercase().contains(q) ||
            st.mcpServer.url.lowercase().contains(q) ||
            st.mcpServer.tools.any { it.name.lowercase().contains(q) }
        }

      LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().height(340.dp),
      ) {
        if (filtered.isEmpty()) {
          item {
            Text(
              text = "No MCP servers added yet.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(16.dp),
            )
          }
        } else {
          items(filtered, key = { it.mcpServer.url }) { state ->
            McpServerCard(
              state = state,
              onToggleServer = { enabled -> mcpManagerViewModel.setServerEnabled(state.mcpServer.url, enabled) },
              onRefresh = { mcpManagerViewModel.addOrRefreshServer(state.mcpServer.url) },
              onDelete = { mcpManagerViewModel.removeServer(state.mcpServer.url) },
              onToggleTool = { toolName, enabled ->
                mcpManagerViewModel.setToolEnabled(state.mcpServer.url, toolName, enabled)
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun McpServerCard(
  state: McpServerState,
  onToggleServer: (Boolean) -> Unit,
  onRefresh: () -> Unit,
  onDelete: () -> Unit,
  onToggleTool: (String, Boolean) -> Unit,
) {
  val srv = state.mcpServer
  Card(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = srv.name.ifBlank { srv.url },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = srv.url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        IconButton(onClick = onRefresh) {
          Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
        }
        IconButton(onClick = onDelete) {
          Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove")
        }
        Switch(checked = srv.enabled, onCheckedChange = onToggleServer)
      }

      if (srv.tools.isNotEmpty() && srv.enabled) {
        Spacer(modifier = Modifier.height(6.dp))
        srv.tools.forEach { tool ->
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .clickable { onToggleTool(tool.name, !tool.enabled) }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Checkbox(
              checked = tool.enabled,
              onCheckedChange = { onToggleTool(tool.name, it) },
            )
            Column(modifier = Modifier.weight(1f)) {
              Text(text = tool.name, style = MaterialTheme.typography.bodyMedium)
              if (tool.description.isNotBlank()) {
                Text(
                  text = tool.description,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
        }
      }
    }
  }
}
