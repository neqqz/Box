package com.google.ai.edge.gallery.customtasks.mcp

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.security.AppLockManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "McpManagerVM"
private const val MCP_SERVERS_FILE = "mcp_servers.json"

@HiltViewModel
class McpManagerViewModel
@Inject
constructor(@ApplicationContext private val context: Context) : ViewModel() {

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
  }
  private val storageMutex = Mutex()
  private val clientsByUrl = ConcurrentHashMap<String, Client>()

  private val httpClient: HttpClient by lazy {
    HttpClient(Android) {
      install(ContentNegotiation) { json(json) }
      install(SSE)
    }
  }

  private val _uiState = MutableStateFlow(McpManagerUiState())
  val uiState: StateFlow<McpManagerUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch(Dispatchers.IO) {
      val servers = readSavedServers()
      _uiState.update {
        it.copy(mcpServers = servers.map { s -> McpServerState(mcpServer = s, client = clientsByUrl[s.url]) })
      }
    }
  }

  fun isBlockedByAdvancedProtection(): Boolean = AppLockManager.isAdvancedProtectionActive(context)

  fun clearError() {
    _uiState.update { it.copy(error = null) }
  }

  fun addOrRefreshServer(url: String) {
    val cleanUrl = url.trim()
    if (cleanUrl.isEmpty()) return
    if (isBlockedByAdvancedProtection()) {
      _uiState.update {
        it.copy(error = "MCP connections are blocked while Android Advanced Protection is active.")
      }
      return
    }

    viewModelScope.launch(Dispatchers.IO) {
      _uiState.update { it.copy(loadingMcpServer = true, error = null) }
      try {
        val (client, tools) = connectAndListTools(cleanUrl)
        clientsByUrl[cleanUrl] = client
        val serverInfo = client.serverVersion
        val existing = readSavedServers()
        val prevEnabledMap =
          existing
            .firstOrNull { it.url == cleanUrl }
            ?.tools
            ?.associate { it.name to it.enabled }
            ?: emptyMap()

        val updatedTools =
          tools.map { t ->
            val schemaStr = t.inputSchema?.toString().orEmpty()
            McpToolConfig(
              name = t.name,
              description = t.description.orEmpty(),
              inputSchema = schemaStr,
              enabled = prevEnabledMap[t.name] ?: true,
            )
          }

        val serverName = serverInfo?.name?.takeIf { it.isNotBlank() } ?: cleanUrl
        val serverVersion = serverInfo?.version.orEmpty()
        val newConfig =
          McpServerConfig(
            url = cleanUrl,
            name = serverName,
            version = serverVersion,
            description = "",
            enabled = true,
            tools = updatedTools,
          )

        val updatedList =
          if (existing.any { it.url == cleanUrl }) {
            existing.map { if (it.url == cleanUrl) newConfig else it }
          } else {
            existing + newConfig
          }
        writeSavedServers(updatedList)
        publishServerList(updatedList)
      } catch (e: Exception) {
        Log.e(TAG, "Failed connecting MCP server $cleanUrl", e)
        _uiState.update {
          it.copy(loadingMcpServer = false, error = "Connection failed: ${e.message ?: "Unknown error"}")
        }
      }
    }
  }

  fun setServerEnabled(url: String, enabled: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
      val updated = readSavedServers().map { if (it.url == url) it.copy(enabled = enabled) else it }
      if (!enabled) {
        clientsByUrl.remove(url)?.let { runCatching { it.close() } }
      }
      writeSavedServers(updated)
      publishServerList(updated)
      if (enabled && !isBlockedByAdvancedProtection()) {
        runCatching { ensureServerConnected(url) }
      }
    }
  }

  fun setToolEnabled(serverUrl: String, toolName: String, enabled: Boolean) {
    viewModelScope.launch(Dispatchers.IO) {
      val updated =
        readSavedServers().map { srv ->
          if (srv.url == serverUrl) {
            srv.copy(tools = srv.tools.map { t -> if (t.name == toolName) t.copy(enabled = enabled) else t })
          } else {
            srv
          }
        }
      writeSavedServers(updated)
      publishServerList(updated)
    }
  }

  fun removeServer(url: String) {
    viewModelScope.launch(Dispatchers.IO) {
      clientsByUrl.remove(url)?.let { runCatching { it.close() } }
      val updated = readSavedServers().filterNot { it.url == url }
      writeSavedServers(updated)
      publishServerList(updated)
    }
  }

  suspend fun ensureAllEnabledConnected() {
    if (isBlockedByAdvancedProtection()) {
      _uiState.update { it.copy(mcpServers = emptyList()) }
      return
    }
    withContext(Dispatchers.IO) {
      val servers = readSavedServers()
      _uiState.update { it.copy(loadingMcpServer = true) }
      val updatedStates =
        servers.map { srv ->
          if (!srv.enabled) {
            McpServerState(mcpServer = srv, client = null, error = null)
          } else {
            try {
              val client = ensureServerConnected(srv.url)
              McpServerState(mcpServer = srv, client = client, error = null)
            } catch (e: Exception) {
              Log.w(TAG, "Reconnect failed for ${srv.url}", e)
              McpServerState(mcpServer = srv, client = null, error = e.message)
            }
          }
        }
      _uiState.update { it.copy(mcpServers = updatedStates, loadingMcpServer = false) }
    }
  }

  fun getToolsPromptSection(): String {
    if (isBlockedByAdvancedProtection()) return ""
    val enabledTools =
      _uiState.value.mcpServers
        .filter { it.mcpServer.enabled }
        .flatMap { srv ->
          srv.mcpServer.tools.filter { it.enabled }.map { tool ->
            "- name: ${tool.name}\n  description: ${tool.description}\n  inputSchema: ${tool.inputSchema}"
          }
        }
    return enabledTools.joinToString("\n")
  }

  fun getEnabledToolsSignature(): String {
    if (isBlockedByAdvancedProtection()) return ""
    return _uiState.value.mcpServers
      .filter { it.mcpServer.enabled }
      .joinToString(";") { srv ->
        srv.mcpServer.url + ":" + srv.mcpServer.tools.filter { it.enabled }.joinToString(",") { it.name }
      }
  }

  suspend fun callTool(toolName: String, inputJson: String): Map<String, String> =
    withContext(Dispatchers.IO) {
      if (isBlockedByAdvancedProtection()) {
        return@withContext mapOf("error" to "Blocked by Android Advanced Protection", "status" to "failed")
      }
      val matchServer =
        _uiState.value.mcpServers.firstOrNull { state ->
          state.mcpServer.enabled && state.mcpServer.tools.any { it.enabled && it.name == toolName.trim() }
        }
          ?: return@withContext mapOf("error" to "MCP tool '$toolName' not found or disabled", "status" to "failed")

      return@withContext try {
        val client = ensureServerConnected(matchServer.mcpServer.url)
        val argsMap = parseJsonObjectToMap(inputJson)
        val result = client.callTool(name = toolName.trim(), arguments = argsMap)
        val textOutput =
          result?.content
            ?.filterIsInstance<TextContent>()
            ?.joinToString("\n") { it.text.orEmpty() }
            ?.ifBlank { result.content.toString() }
            ?: ""
        mapOf("result" to textOutput, "status" to "succeeded")
      } catch (e: Exception) {
        Log.e(TAG, "MCP tool '$toolName' execution failed", e)
        mapOf("error" to (e.message ?: "MCP call failed"), "status" to "failed")
      }
    }

  private suspend fun ensureServerConnected(url: String): Client {
    clientsByUrl[url]?.let { existing -> return existing }
    val (client, _) = connectAndListTools(url)
    clientsByUrl[url] = client
    return client
  }

  private suspend fun connectAndListTools(
    url: String
  ): Pair<Client, List<io.modelcontextprotocol.kotlin.sdk.types.Tool>> {
    val clientInfo = Implementation(name = "Box-Android", version = "3.5.7")
    val streamableClient = Client(clientInfo = clientInfo)
    try {
      val transport = StreamableHttpClientTransport(client = httpClient, url = url)
      streamableClient.connect(transport)
      val toolsResp = streamableClient.listTools()
      return streamableClient to toolsResp.tools
    } catch (streamableErr: Exception) {
      runCatching { streamableClient.close() }
      // Fall back to legacy SSE transport for older MCP servers.
      val sseClient = Client(clientInfo = clientInfo)
      try {
        val sseTransport = SseClientTransport(client = httpClient, urlString = url)
        sseClient.connect(sseTransport)
        val toolsResp = sseClient.listTools()
        return sseClient to toolsResp.tools
      } catch (sseErr: Exception) {
        runCatching { sseClient.close() }
        throw streamableErr
      }
    }
  }

  private fun parseJsonObjectToMap(raw: String): Map<String, Any?> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || trimmed == "{}") return emptyMap()
    return try {
      jsonObjectToMap(JSONObject(trimmed))
    } catch (_: Exception) {
      emptyMap()
    }
  }

  private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
    val map = LinkedHashMap<String, Any?>()
    val keys = obj.keys()
    while (keys.hasNext()) {
      val key = keys.next()
      val value = obj.get(key)
      map[key] =
        when (value) {
          JSONObject.NULL -> null
          is JSONObject -> jsonObjectToMap(value)
          is JSONArray -> jsonArrayToList(value)
          else -> value
        }
    }
    return map
  }

  private fun jsonArrayToList(arr: JSONArray): List<Any?> {
    return List(arr.length()) { idx ->
      when (val v = arr.get(idx)) {
        JSONObject.NULL -> null
        is JSONObject -> jsonObjectToMap(v)
        is JSONArray -> jsonArrayToList(v)
        else -> v
      }
    }
  }

  private suspend fun readSavedServers(): List<McpServerConfig> =
    storageMutex.withLock {
      val file = File(context.filesDir, MCP_SERVERS_FILE)
      if (!file.exists()) return@withLock emptyList()
      return@withLock try {
        json.decodeFromString<List<McpServerConfig>>(file.readText())
      } catch (e: Exception) {
        Log.w(TAG, "Failed parsing $MCP_SERVERS_FILE", e)
        emptyList()
      }
    }

  private suspend fun writeSavedServers(servers: List<McpServerConfig>) =
    storageMutex.withLock {
      val file = File(context.filesDir, MCP_SERVERS_FILE)
      file.writeText(json.encodeToString(servers))
    }

  private fun publishServerList(servers: List<McpServerConfig>) {
    _uiState.update { state ->
      state.copy(
        loadingMcpServer = false,
        mcpServers =
          servers.map { srv -> McpServerState(mcpServer = srv, client = clientsByUrl[srv.url]) },
      )
    }
  }

  override fun onCleared() {
    super.onCleared()
    kotlinx.coroutines.runBlocking {
      clientsByUrl.values.forEach { runCatching { it.close() } }
    }
    clientsByUrl.clear()
    runCatching { httpClient.close() }
  }
}
