package com.google.ai.edge.gallery.customtasks.facerec

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskData
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FaceRecTask @Inject constructor() : CustomTask {

  private val bundledModel =
    Model(
      name = "MobileFaceNet",
      info =
        "Recognises faces you have taught it. Enrol someone once and Box can spot them in later photos — it stores a small mathematical fingerprint of the face, not the picture itself, and everything stays on your phone. Tiny at about 5 MB and built into the app.",
      url = "",
      sizeInBytes = 5233552L,
      downloadFileName = "mobilefacenet.tflite",
      version = "v1",
    )

  override val task: Task =
    Task(
      id = BuiltInTaskId.FACE_REC,
      label = "Face Recognition",
      category = Category.IMAGE_GEN,
      icon = Icons.Outlined.Face,
      models = mutableListOf(bundledModel),
      description =
        "Save, name, and recognize faces — fully on-device. Detects faces with ML Kit and matches them with a MobileFaceNet embedding. Everything (including the face data) stays encrypted on your device and never leaves it.",
      shortDescription = "On-device face recognition",
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch(Dispatchers.IO) {
      val engine = FaceEmbedEngine()
      val ok = engine.loadModel(model.getPath(context))
      if (ok) {
        model.instance = engine
        onDone("")
      } else {
        onDone("Failed to load face-recognition model")
      }
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    val engine = model.instance as? FaceEmbedEngine
    model.instance = null
    coroutineScope.launch(Dispatchers.IO) {
      engine?.close()
      onDone()
    }
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    FaceRecScreen(model = bundledModel, customTaskData = myData)
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object FaceRecTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask {
    return FaceRecTask()
  }
}
