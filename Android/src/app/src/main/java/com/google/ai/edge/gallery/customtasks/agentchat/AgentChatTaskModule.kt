/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.customtasks.agentchat

import android.content.Context
import androidx.compose.runtime.Composable
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.customtasks.common.CustomTask
import com.google.ai.edge.gallery.customtasks.common.CustomTaskDataForBuiltinTask
import com.google.ai.edge.gallery.customtasks.mcp.McpToolSet
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Category
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.llmchat.LlmChatModelHelper
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.tool
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

const val PROMPT_WITH_MCP_TOOLS = """
You are an AI assistant that helps users by answering questions and completing tasks using skills and tools. For EVERY new task, request, or question, you MUST execute the following steps in exact order. You MUST NOT skip any steps.

CRITICAL RULE: You MUST execute all steps silently. Do NOT generate or output any internal thoughts, reasoning, explanations, or intermediate text at ANY step.

1. EVALUATE AND ROUTE:
   Determine if the request should be handled by a "Skill" (requires loading instructions) or directly by an "MCP Tool".
   - If it is a Skill: Go to Step 2.
   - If it is an MCP Tool: Go to Step 4.
   - If nothing is found, output "No skills or tools found" and stop.

--- SKILLS ---
___SKILLS___

--- MCP TOOLS ---
___TOOLS___

==================================================
FLOW A: SKILL EXECUTION
==================================================

2. Find the most relevant skill from the --- SKILLS --- list. You MUST NOT use `run_intent` or `runMcpTool` under any circumstances at this step.

3. Use the `load_skill` tool to read its instructions. Follow the skill's instructions exactly to complete the task.
   - You MUST NOT output any intermediate thoughts or status updates. No exceptions!
   - Output ONLY the final result when successful. It should contain a one-sentence summary of the action taken and the final result of the skill.
   - Stop here once Flow A is complete.

==================================================
FLOW B: MCP TOOL DIRECT EXECUTION
==================================================

4. Find the most relevant tool from the --- MCP TOOLS --- list.

5. Call the `runMcpTool` tool with the following parameters:
   - `toolName`: The name of the tool to run. Use the exact name from the list above. Do not hallucinate the name. Pay attention to casing and plurals.
   - `input`: The input JSON object that matches the tool's expected input schema.

6. Output ONLY the final result returned by the tool. You MUST NOT output any intermediate thoughts or status updates. No exceptions!
"""

class AgentChatTask @Inject constructor() : CustomTask {
  private val agentTools = AgentTools()
  private val mcpToolSet = McpToolSet().apply { agentTools = this@AgentChatTask.agentTools }

  override val task: Task =
    Task(
      id = BuiltInTaskId.LLM_AGENT_CHAT,
      label = "Agent Skills",
      category = Category.LLM,
      iconVectorResourceId = R.drawable.agent,
      newFeature = true,
      models = mutableListOf(),
      description = "Chat with on-device large language models with skills and tools",
      shortDescription = "Complete agentic tasks with chat",
      docUrl = "https://github.com/google-ai-edge/LiteRT-LM/blob/main/kotlin/README.md",
      sourceCodeUrl =
        "https://github.com/google-ai-edge/gallery/blob/main/Android/src/app/src/main/java/com/google/ai/edge/gallery/customtasks/agentchat/",
      textInputPlaceHolderRes = R.string.text_input_placeholder_llm_chat,
      defaultSystemPrompt =
        """
        You are an AI assistant that helps users by answering questions and completes tasks using skills. For EVERY new task or request or question, you MUST execute the following steps in exact order. You MUST NOT skip any steps.

        CRITICAL RULE: You MUST execute all steps silently. Do NOT generate or output any internal thoughts, reasoning, explanations, or intermediate text at ANY step.

        1. First, find the most relevant skill from the following list:

        ___SKILLS___

        After this step you MUST go to next step. You MUST NOT use `run_intent` under any circumstances at this step.

        2. If a relevant skill exists, use the `load_skill` tool to read its instructions. You MUST NOT use `run_intent` under any circumstances at this step.

        3. Follow the skill's instructions exactly to complete the task. You MUST NOT output any intermediate thoughts or status updates. No exceptions! Output ONLY the final result when successful. It should contain one-sentence summary of the action taken, and the final result of the skill.
        """
          .trimIndent(),
    )

  override fun initializeModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: (String) -> Unit,
  ) {
    coroutineScope.launch {
      mcpToolSet.mcpManagerViewModel?.ensureAllEnabledConnected()
      agentTools.skillManagerViewModel.loadSkills {
        val mcpPrompt = mcpToolSet.mcpManagerViewModel?.getToolsPromptSection().orEmpty()
        val hasSkills = agentTools.skillManagerViewModel.getSelectedSkills().isNotEmpty()
        val hasMcpTools = mcpPrompt.isNotEmpty()
        val systemInstruction: Contents? =
          when {
            !hasSkills && !hasMcpTools -> null
            hasMcpTools -> {
              val skillsText = agentTools.skillManagerViewModel.getSelectedSkillsNamesAndDescriptions()
              Contents.of(
                PROMPT_WITH_MCP_TOOLS.trimIndent()
                  .replace("___SKILLS___", skillsText)
                  .replace("___TOOLS___", mcpPrompt)
              )
            }
            else -> agentTools.skillManagerViewModel.getSystemPrompt(task.defaultSystemPrompt)
          }
        val activeTools =
          if (hasMcpTools) listOf(tool(agentTools), tool(mcpToolSet))
          else listOf(tool(agentTools))
        LlmChatModelHelper.initialize(
          context = context,
          model = model,
          supportImage = true,
          supportAudio = true,
          onDone = onDone,
          systemInstruction = systemInstruction,
          tools = activeTools,
          enableConversationConstrainedDecoding = true,
        )
      }
    }
  }

  override fun cleanUpModelFn(
    context: Context,
    coroutineScope: CoroutineScope,
    model: Model,
    onDone: () -> Unit,
  ) {
    LlmChatModelHelper.cleanUp(model = model, onDone = onDone)
  }

  @Composable
  override fun MainScreen(data: Any) {
    val myData = data as CustomTaskDataForBuiltinTask
    AgentChatScreen(
      task = task,
      modelManagerViewModel = myData.modelManagerViewModel,
      navigateUp = myData.onNavUp,
      agentTools = agentTools,
      mcpToolSet = mcpToolSet,
    )
  }
}

@Module
@InstallIn(SingletonComponent::class)
internal object AgentChatTaskModule {
  @Provides
  @IntoSet
  fun provideTask(): CustomTask {
    return AgentChatTask()
  }
}
