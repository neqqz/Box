package com.google.ai.edge.gallery.customtasks.rag

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
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

class RagTask @Inject constructor() : CustomTask {
  override val task: Task =
    Task(
      id = BuiltInTaskId.RAG,
      label = "Document Q&A",
      category = Category.LLM,
      icon = Icons.AutoMirrored.Outlined.LibraryBooks,
      models = mutableListOf(),
      description =
        "Import PDFs and ask questions about them. Documents are embedded locally using MiniLM and answered by your loaded AI model.",
      shortDescription = "Ask questions about PDFs",
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    onDone("")
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    onDone()
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskData
    RagScreen(customTaskData = myData)
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object RagTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask {
    return RagTask()
  }
}
