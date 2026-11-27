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
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider
import com.jegly.offlineLLM.smollm.SmolLM
import kotlinx.coroutines.CoroutineScope

private const val TAG = "BoxLlamaCppModelHelper"

/**
 * Box: LlmModelHelper implementation backed by llama.cpp for GGUF models.
 * Routes through the smollm JNI bridge.
 */
object LlamaCppModelHelper : LlmModelHelper {

    // Indexed by model name
    private val engines: MutableMap<String, LlamaCppEngine> = mutableMapOf()

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

        engine.loadModel(
            modelPath = modelPath,
            params = params,
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

        Log.d(TAG, "Resetting conversation for ${model.name} (keeping model loaded)")

        engine.resetConversation(
            modelPath = modelPath,
            params = engine.lastLoadParams ?: SmolLM.InferenceParams(),
            systemPrompt = engine.lastSystemPrompt,
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

        engine.generateResponse(
            query = input,
            onToken = { partialResponse ->
                resultListener(partialResponse, false, null)
            },
            onComplete = { result ->
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
