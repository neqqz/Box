package com.google.ai.edge.gallery.runtime

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.DEFAULT_TEMPERATURE
import com.google.ai.edge.gallery.data.DEFAULT_TOPK
import com.google.ai.edge.gallery.data.DEFAULT_TOPP
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.engine.LlamaCppEngine
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.InternalJsonTool
import com.google.ai.edge.litertlm.ToolProvider
import com.jegly.offlineLLM.smollm.SmolLM
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

private const val TAG = "BoxLlamaCppModelHelper"
private const val MAX_AGENT_TOOL_STEPS = 6

/**
 * Box: LlmModelHelper implementation backed by llama.cpp for GGUF models.
 * Routes through the smollm JNI bridge.
 */
object LlamaCppModelHelper : LlmModelHelper {

    // Indexed by model name
    private val engines: MutableMap<String, LlamaCppEngine> = mutableMapOf()
    private val toolsByModel: MutableMap<String, Map<String, InternalJsonTool>> = mutableMapOf()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun extractTools(tools: List<ToolProvider>): Map<String, InternalJsonTool> {
        if (tools.isEmpty()) return emptyMap()
        val result = linkedMapOf<String, InternalJsonTool>()
        for (provider in tools) {
            try {
                val method = provider.javaClass.methods.firstOrNull {
                    it.name.startsWith("provideTools") && it.parameterCount == 0
                } ?: continue
                @Suppress("UNCHECKED_CAST")
                val provided = method.invoke(provider) as? Map<String, InternalJsonTool>
                if (provided != null) {
                    result.putAll(provided)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to extract tools from ${provider.javaClass.simpleName}", e)
            }
        }
        return result
    }

    private fun buildSystemPrompt(
        systemInstruction: Contents?,
        tools: Map<String, InternalJsonTool>,
    ): String {
        val base = systemInstruction?.contents
            ?.filterIsInstance<Content.Text>()
            ?.joinToString("\n") { it.text }
            ?.trim()
            .orEmpty()
        if (tools.isEmpty()) return base

        val toolDefs = tools.entries.joinToString("\n") { (name, tool) ->
            "- $name: ${tool.getToolDescription()}"
        }
        val toolInstruction = """
            |You have access to the following tools:
            |$toolDefs
            |
            |When you need to call a tool, output ONLY a <tool_call> block with JSON:
            |<tool_call>{"name": "<tool_name>", "arguments": {<arguments>}}</tool_call>
            |Wait for the tool result before giving your final answer.
        """.trimMargin()
        return if (base.isEmpty()) toolInstruction else "$base\n\n$toolInstruction"
    }

    private fun parseToolCall(response: String): Pair<String, JsonObject>? {
        val regex = Regex("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(response)
        val jsonStr = match?.groupValues?.getOrNull(1)
            ?: response.trim().let { trimmed ->
                if (trimmed.startsWith("{") && trimmed.endsWith("}") && trimmed.contains("\"name\"")) trimmed
                else null
            }
            ?: return null
        return try {
            val obj = json.parseToJsonElement(jsonStr).jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return null
            val args = obj["arguments"]?.jsonObject ?: JsonObject(emptyMap())
            Pair(name, args)
        } catch (_: Exception) {
            null
        }
    }

    override fun initialize(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        onDone: (String) -> Unit,
        systemInstruction: Contents?,
        tools: List<ToolProvider>,
        enableConversationConstrainedDecoding: Boolean,
        coroutineScope: CoroutineScope?,
    ) {
        val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
        val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
        val temperature = model.getFloatConfigValue(
            key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE
        )
        val accelerator = model.getStringConfigValue(
            key = ConfigKeys.ACCELERATOR, defaultValue = Accelerator.CPU.label
        )
        val prefs = context.getSharedPreferences("box_settings", Context.MODE_PRIVATE)
        val nGpuLayers = if (accelerator == Accelerator.GPU.label) {
            prefs.getInt("llama_gpu_layers", 99)
        } else {
            0
        }

        val modelPath = model.getPath(context = context)
        Log.d(TAG, "Initializing llama.cpp engine for: $modelPath (nGpuLayers=$nGpuLayers)")

        val engine = LlamaCppEngine()
        engines[model.name] = engine
        engine.loadBackends(context.applicationInfo.nativeLibraryDir ?: "")

        val availableProcessors = Runtime.getRuntime().availableProcessors()
        val configuredThreads = prefs.getInt("llama_num_threads", 0)
        val numThreads = if (configuredThreads <= 0) {
            (availableProcessors / 2).coerceIn(4, 8)
        } else {
            configuredThreads.coerceAtMost(availableProcessors)
        }
        val configuredCtx = prefs.getInt("llama_context_size", 0)

        val params = SmolLM.InferenceParams(
            temperature = temperature,
            topP = topP,
            topK = topK,
            contextSize = if (configuredCtx > 0) configuredCtx.toLong() else null,
            numThreads = numThreads,
            useMmap = prefs.getBoolean("llama_use_mmap", false),
            useMlock = prefs.getBoolean("llama_use_mlock", false),
            nGpuLayers = nGpuLayers,
            numThreadsBatch = availableProcessors,
            kvCacheQ8 = prefs.getBoolean("llama_kv_cache_q8", false),
        )

        val resolvedTools = extractTools(tools)
        toolsByModel[model.name] = resolvedTools
        val systemPrompt = buildSystemPrompt(systemInstruction, resolvedTools)

        engine.loadModel(
            modelPath = modelPath,
            params = params,
            systemPrompt = systemPrompt,
            onSuccess = {
                // Store a marker so the ViewModel knows the model is ready
                model.instance = engine
                onDone("")
            },
            onError = { e ->
                Log.e(TAG, "Failed to load GGUF model", e)
                onDone(e.message ?: "Failed to load GGUF model")
            }
        )
    }

    override fun resetConversation(
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemInstruction: Contents?,
        tools: List<ToolProvider>,
        enableConversationConstrainedDecoding: Boolean,
    ) {
        val engine = engines[model.name] ?: return
        val modelPath = engine.lastModelPath ?: return

        val resolvedTools = extractTools(tools)
        toolsByModel[model.name] = resolvedTools
        val systemPrompt = buildSystemPrompt(systemInstruction, resolvedTools)

        Log.d(TAG, "Resetting conversation for ${model.name} (keeping model loaded)")

        engine.resetConversation(
            modelPath = modelPath,
            params = engine.lastLoadParams ?: SmolLM.InferenceParams(),
            systemPrompt = systemPrompt,
            onSuccess = {
                // Update model instance reference
                model.instance = engine
                Log.d(TAG, "Conversation reset complete for ${model.name}")
            },
            onError = { e ->
                Log.e(TAG, "Failed to reset conversation for ${model.name}", e)
            },
        )
    }

    override fun cleanUp(model: Model, onDone: () -> Unit) {
        toolsByModel.remove(model.name)
        val engine = engines.remove(model.name)
        engine?.unloadModel()
        model.instance = null
        onDone()
        Log.d(TAG, "Clean up done for ${model.name}")
    }

    override fun stopResponse(model: Model) {
        val engine = engines[model.name]
        engine?.stopGeneration()
    }

    override fun runInference(
        model: Model,
        input: String,
        resultListener: ResultListener,
        cleanUpListener: CleanUpListener,
        onError: (message: String) -> Unit,
        images: List<Bitmap>,
        audioClips: List<ByteArray>,
        coroutineScope: CoroutineScope?,
        extraContext: Map<String, String>?,
    ) {
        val engine = engines[model.name]
        if (engine == null || !engine.isModelLoaded.get()) {
            onError("llama.cpp engine not initialized for ${model.name}")
            return
        }

        // Note: llama.cpp text-only — images/audio not supported in this path
        if (images.isNotEmpty()) {
            Log.w(TAG, "Image input not supported with llama.cpp engine, ignoring ${images.size} images")
        }

        val activeTools = toolsByModel[model.name].orEmpty()
        if (activeTools.isEmpty()) {
            engine.generateResponse(
                query = input,
                onToken = { partialResponse ->
                    resultListener(partialResponse, false, null)
                },
                onComplete = { _ ->
                    // Send the final delta (empty string) with done=true
                    resultListener("", true, null)
                },
                onCancelled = {
                    resultListener("", true, null)
                },
                onError = { e ->
                    Log.e(TAG, "Inference error", e)
                    onError(e.message ?: "Inference error")
                }
            )
            return
        }

        val scope = coroutineScope ?: CoroutineScope(Dispatchers.Default)
        scope.launch(Dispatchers.Default) {
            var currentPrompt = input
            for (step in 0 until MAX_AGENT_TOOL_STEPS) {
                var cancelled = false
                var error: Exception? = null
                val response = suspendCancellableCoroutine<String> { cont ->
                    val acc = StringBuilder()
                    engine.generateResponse(
                        query = currentPrompt,
                        onToken = { token -> acc.append(token) },
                        onComplete = { result ->
                            if (cont.isActive) cont.resume(result.response.ifEmpty { acc.toString() })
                        },
                        onCancelled = {
                            cancelled = true
                            if (cont.isActive) cont.resume(acc.toString())
                        },
                        onError = { e ->
                            error = e
                            if (cont.isActive) cont.resume("")
                        },
                    )
                }
                if (error != null) {
                    Log.e(TAG, "Agent inference error", error)
                    onError(error?.message ?: "Inference error")
                    return@launch
                }
                if (cancelled) {
                    if (response.isNotEmpty()) resultListener(response, false, null)
                    resultListener("", true, null)
                    return@launch
                }
                val toolCall = parseToolCall(response)
                if (toolCall == null) {
                    if (response.isNotEmpty()) resultListener(response, false, null)
                    resultListener("", true, null)
                    return@launch
                }
                val (toolName, args) = toolCall
                val tool = activeTools[toolName]
                val toolResultStr = if (tool == null) {
                    "{\"error\": \"Unknown tool: $toolName\"}"
                } else {
                    try {
                        kotlinx.coroutines.withContext(Dispatchers.IO) {
                            tool.execute(args)?.toString() ?: "{}"
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Tool execution failed: $toolName", e)
                        "{\"error\": \"${e.message}\"}"
                    }
                }
                currentPrompt = "Tool \"$toolName\" result:\n$toolResultStr\n\nContinue with the next tool call if needed, or provide the final answer."
            }
            resultListener("Reached maximum tool steps.", false, null)
            resultListener("", true, null)
        }
    }

    /**
     * Replay prior conversation turns into the loaded llama.cpp context and prewarm KV cache.
     */
    fun loadHistory(model: Model, history: List<Pair<Boolean, String>>) {
        if (history.isEmpty()) return
        val engine = engines[model.name] ?: return
        if (!engine.isModelLoaded.get()) return

        val maxTokens = model.getIntConfigValue(key = ConfigKeys.MAX_TOKENS, defaultValue = 1024)
        val budget = (maxTokens * 0.7).toInt()
        val trimmed = history.toMutableList()
        while (trimmed.isNotEmpty()) {
            val estimatedTokens = trimmed.sumOf { (_, text) -> (text.length / 4) + 6 }
            if (estimatedTokens <= budget) break
            trimmed.removeAt(0)
        }
        if (trimmed.isEmpty()) return

        for ((isUser, content) in trimmed) {
            engine.addHistoryMessage(if (isUser) "user" else "assistant", content)
        }
        engine.prewarmHistoryAsync()
    }
}
