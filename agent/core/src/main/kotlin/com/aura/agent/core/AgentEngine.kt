package com.aura.agent.core

import com.aura.agent.actions.ActionExecutor
import com.aura.agent.actions.ActionGuard
import com.aura.agent.actions.GuardDecision
import com.aura.agent.memory.MemoryManager
import com.aura.ai.engine.LLMEngine
import com.aura.ai.engine.PromptBuilder
import com.aura.core.domain.model.*
import com.aura.core.domain.repository.ConversationRepository
import com.aura.core.domain.repository.GoalRepository
import com.aura.core.domain.usecase.RetrieveContextUseCase
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The central agent loop implementing the ReAct (Reason + Act) pattern.
 *
 * Flow per user message:
 *   1. Embed query → retrieve relevant memories + active goals.
 *   2. Build full prompt (system + context + history + query).
 *   3. LLM generates JSON response (thought + action OR thought + final_answer).
 *   4. Parser extracts action → ActionExecutor runs it.
 *   5. Observation is appended → LLM generates next step.
 *   6. Loop continues until "final_answer" or MAX_STEPS reached.
 *   7. Response stored as episodic memory.
 *
 * Streaming: each token is emitted as [AgentEvent.Token] so the UI can
 * render incrementally while the model generates.
 */
@Singleton
class AgentEngine @Inject constructor(
    private val llmEngine: LLMEngine,
    private val memoryManager: MemoryManager,
    private val actionExecutor: ActionExecutor,
    private val actionGuard: ActionGuard,
    private val promptBuilder: PromptBuilder,
    private val conversationRepository: ConversationRepository,
    private val goalRepository: GoalRepository,
    private val retrieveContext: RetrieveContextUseCase,
    private val responseParser: LLMResponseParser,
    private val userRulesStore: UserRulesStore,
) {
    companion object {
        private const val MAX_STEPS = 6
    }

    /**
     * Process a user message and stream agent events back to the caller.
     * Suspends until the agent reaches a final answer or an error.
     */
    fun process(userMessage: String): Flow<AgentEvent> = flow {
        if (!llmEngine.isReady) {
            emit(AgentEvent.Error("LLM model not loaded. Place Gemma-2B model in app files directory."))
            return@flow
        }

        emit(AgentEvent.Thinking)

        val queryEmbedding = llmEngine.embed(userMessage)
        val context = retrieveContext(queryEmbedding)
        val history = conversationRepository.getRecentMessages(10)
        val userRules = userRulesStore.getRules()

        var prompt = promptBuilder.buildAgentPrompt(
            userMessage = userMessage,
            conversationHistory = history,
            relevantMemories = context.relevantMemories,
            activeGoals = context.activeGoals,
            userRules = userRules,
        )

        var stepCount = 0
        var finalAnswer: String? = null

        while (stepCount < MAX_STEPS && finalAnswer == null) {
            stepCount++
            Timber.d("Agent step $stepCount/$MAX_STEPS")

            // Stream tokens from the LLM
            val responseBuilder = StringBuilder()
            llmEngine.generate(prompt).collect { token ->
                responseBuilder.append(token.text)
                emit(AgentEvent.Token(token.text))
            }
            val rawResponse = responseBuilder.toString().trim()
            Timber.d("LLM raw response: $rawResponse")

            // Parse the structured JSON response
            val step = responseParser.parse(rawResponse)
            emit(AgentEvent.Step(step))

            if (step.isFinal || step.finalAnswer != null) {
                finalAnswer = step.finalAnswer ?: rawResponse
                break
            }

            val action = step.action ?: run {
                // LLM produced non-JSON text — treat as final answer
                finalAnswer = rawResponse
                break
            }

            // Run through the safety sandbox before executing
            when (val decision = actionGuard.evaluate(action, userRules)) {
                is GuardDecision.Block -> {
                    Timber.w("Action blocked: ${decision.reason}")
                    // Inject the block reason as an observation so the LLM can adapt
                    prompt = promptBuilder.buildObservationPrompt(
                        prompt, action.tool.name,
                        "BLOCKED: ${decision.reason}"
                    )
                    continue
                }
                is GuardDecision.RequireConfirmation -> {
                    emit(AgentEvent.ConfirmationRequired(action))
                    return@flow
                }
                GuardDecision.Allow -> Unit
            }

            // Execute the action
            emit(AgentEvent.ExecutingAction(action))
            val result = actionExecutor.execute(action)
            emit(AgentEvent.ActionResult(result))

            // Append observation to prompt for next step
            prompt = promptBuilder.buildObservationPrompt(prompt, action.tool.name, result.output)
        }

        val answer = finalAnswer ?: "Je n'ai pas pu compléter la tâche après $MAX_STEPS étapes."
        emit(AgentEvent.FinalAnswer(answer))

        // Store the exchange in memory
        memoryManager.rememberEvent(
            text = "User: $userMessage\nAura: $answer",
            importance = 0.6f,
        )
    }.catch { e ->
        Timber.e(e, "AgentEngine error")
        emit(AgentEvent.Error(e.message ?: "Unknown error"))
    }

}

sealed interface AgentEvent {
    data object Thinking : AgentEvent
    data class Token(val text: String) : AgentEvent
    data class Step(val step: AgentStep) : AgentEvent
    data class ExecutingAction(val action: AgentAction) : AgentEvent
    data class ActionResult(val result: com.aura.core.domain.model.ActionResult) : AgentEvent
    data class ConfirmationRequired(val action: AgentAction) : AgentEvent
    data class FinalAnswer(val text: String) : AgentEvent
    data class Error(val message: String) : AgentEvent
}
