package com.google.ai.edge.gallery.ui.imagegen

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.NumberSliderConfig
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.stablediffusion.StableDiffusion
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private fun sdModel(
    name: String,
    modelId: String,
    modelFile: String,
    description: String,
    sizeInBytes: Long,
    defaultSteps: Int = 20,
    defaultCfg: Float = 7.5f,
): Model = Model(
    name = name,
    info = description,
    url = "https://huggingface.co/$modelId/resolve/main/$modelFile?download=true",
    sizeInBytes = sizeInBytes,
    downloadFileName = modelFile,
    version = "main",
    configs = mutableListOf(
        NumberSliderConfig(
            key = ConfigKey("sd_steps", "Steps"),
            sliderMin = 1f,
            sliderMax = 50f,
            defaultValue = defaultSteps.toFloat(),
            valueType = ValueType.INT,
            needReinitialization = false,
        ),
        NumberSliderConfig(
            key = ConfigKey("sd_cfg", "CFG Scale"),
            sliderMin = 0f,
            sliderMax = 20f,
            defaultValue = defaultCfg,
            valueType = ValueType.FLOAT,
            needReinitialization = false,
        ),
    ),
    learnMoreUrl = "https://huggingface.co/$modelId",
    showBenchmarkButton = false,
    showRunAgainButton = false,
)

class ImageGenTask @Inject constructor() : CustomTask {
    override val task: Task = Task(
        id = BuiltInTaskId.IMAGE_GEN,
        label = "Image Gen",
        category = Category.IMAGE_GEN,
        icon = Icons.Outlined.AutoAwesome,
        description = "Generate images from text prompts using on-device Stable Diffusion",
        shortDescription = "On-device image generation",
        models = mutableListOf(
            sdModel(
                name = "LCM-SSD-1B Q4_K",
                modelId = "OlegSkutte/LCM-SSD-1B-GGUF",
                modelFile = "lcm-ssd-1b.q4_k.gguf",
                description = "Fast and capable (~2.2 GB). Trimmed SDXL with consistency distillation — real results in 4–8 steps. Keep CFG at 1.",
                sizeInBytes = 2_233_561_184L,
                defaultSteps = 4,
                defaultCfg = 1.0f,
            ),
            sdModel(
                name = "SDXL-Lightning 4-step Q4_0",
                modelId = "mzwing/SDXL-Lightning-GGUF",
                modelFile = "sdxl_lightning_4step.q4_0.gguf",
                description = "ByteDance's fast SDXL distillation (~2.8 GB). High quality in exactly 4 steps. Keep CFG at 0.",
                sizeInBytes = 2_769_361_920L,
                defaultSteps = 4,
                defaultCfg = 0.0f,
            ),
            sdModel(
                name = "SDXL-Turbo Q4_0",
                modelId = "gpustack/stable-diffusion-xl-1.0-turbo-GGUF",
                modelFile = "stable-diffusion-xl-1.0-turbo-Q4_0.gguf",
                description = "Fast SDXL in 1–4 steps (~4.2 GB). Vivid, detailed results. Keep CFG at 0 — higher values break this model.",
                sizeInBytes = 4_230_696_960L,
                defaultSteps = 4,
                defaultCfg = 0.0f,
            ),
            sdModel(
                name = "SDXL Base Q4_0",
                modelId = "gpustack/stable-diffusion-xl-base-1.0-GGUF",
                modelFile = "stable-diffusion-xl-base-1.0-Q4_0.gguf",
                description = "Full SDXL foundation model (~3.9 GB). Best overall quality with native 1024² resolution. Takes 20–30 steps.",
                sizeInBytes = 3_940_010_720L,
                defaultSteps = 25,
                defaultCfg = 7.0f,
            ),
            sdModel(
                name = "SD 1.5 Q4_0",
                modelId = "second-state/stable-diffusion-v1-5-GGUF",
                modelFile = "stable-diffusion-v1-5-pruned-emaonly-Q4_0.gguf",
                description = "Classic Stable Diffusion 1.5 (~2.1 GB). Reliable all-rounder — handles most art styles and prompts well. Best at 512² resolution.",
                sizeInBytes = 2_127_000_000L,
            ),
            sdModel(
                name = "SD 1.5 Q8_0",
                modelId = "second-state/stable-diffusion-v1-5-GGUF",
                modelFile = "stable-diffusion-v1-5-pruned-emaonly-Q8_0.gguf",
                description = "Higher-precision SD 1.5 (~4.0 GB). Sharper detail than Q4_0 — worth the extra size if you have the RAM.",
                sizeInBytes = 4_028_000_000L,
            ),
        ),
    )

    override fun initializeModelFn(
        context: Context,
        coroutineScope: CoroutineScope,
        model: Model,
        onDone: (String) -> Unit,
    ) {
        coroutineScope.launch(Dispatchers.Default) {
            val sd = StableDiffusion()
            val modelPath = model.getPath(context)
            val nThreads = Runtime.getRuntime().availableProcessors().coerceIn(4, 8)
            val ok = sd.loadModel(modelPath, nThreads)
            if (ok) {
                model.instance = sd
                onDone("")
            } else {
                onDone("Failed to load Stable Diffusion model from $modelPath")
            }
        }
    }

    override fun cleanUpModelFn(
        context: Context,
        coroutineScope: CoroutineScope,
        model: Model,
        onDone: () -> Unit,
    ) {
        val sd = model.instance as? StableDiffusion ?: run {
            onDone()
            return
        }
        coroutineScope.launch(Dispatchers.Default) {
            sd.freeModel()
            onDone()
        }
    }

    @Composable
    override fun MainScreen(data: Any) {
        val myData = data as CustomTaskData
        ImageGenScreen(modelManagerViewModel = myData.modelManagerViewModel)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal object ImageGenTaskModule {
    @Provides
    @IntoSet
    fun provideTask(): CustomTask = ImageGenTask()
}
