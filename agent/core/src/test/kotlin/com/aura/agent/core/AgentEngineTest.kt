package com.aura.agent.core

import app.cash.turbine.test
import com.aura.agent.actions.ActionExecutor
import com.aura.agent.actions.ActionGuard
import com.aura.agent.memory.MemoryManager
import com.aura.ai.engine.LLMEngine
import com.aura.ai.engine.LLMToken
import com.aura.ai.engine.PromptBuilder
import com.aura.core.common.AuraLogDelegate
import com.aura.core.common.AuraLogger
import com.aura.core.domain.model.*
import com.aura.core.domain.repository.ConversationRepository
import com.aura.core.domain.repository.GoalRepository
import com.aura.core.domain.usecase.AgentContext
import com.aura.core.domain.usecase.RetrieveContextUseCase
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentEngineTest {
    private lateinit var llmEngine: LLMEngine
    private lateinit var memoryManager: MemoryManager
    private lateinit var actionExecutor: ActionExecutor
    private lateinit var promptBuilder: PromptBuilder
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var goalRepository: GoalRepository
    private lateinit var retrieveContext: RetrieveContextUseCase
    private lateinit var responseParser: LLMResponseParser
    private lateinit var userRulesStore: UserRulesStore
    private lateinit var agentEngine: AgentEngine

    @Before fun setUp() {
        llmEngine = mockk()
        memoryManager = mockk(relaxed = true)
        actionExecutor = mockk()
        promptBuilder = PromptBuilder()
        conversationRepository = mockk(relaxed = true)
        goalRepository = mockk(relaxed = true)
        retrieveContext = mockk()
        responseParser = LLMResponseParser()
        userRulesStore = mockk()

        // Silence AuraLogger during unit tests
        AuraLogger.delegate = object : AuraLogDelegate {
            override fun log(tag: String, message: String) {}
        }

        agentEngine = AgentEngine(
            llmEngine = llmEngine,
            memoryManager = memoryManager,
            actionExecutor = actionExecutor,
            actionGuard = ActionGuard(),
            promptBuilder = promptBuilder,
            conversationRepository = conversationRepository,
            goalRepository = goalRepository,
            retrieveContext = retrieveContext,
            responseParser = responseParser,
            userRulesStore = userRulesStore,
        )
    }

    @After fun tearDown() {
        AuraLogger.delegate = object : AuraLogDelegate {
            override fun log(tag: String, message: String) {}
        }
    }

    @Test fun `emits error when LLM not ready`() = runTest {
        every { llmEngine.isReady } returns false

        agentEngine.process("Hello").test {
            val event = awaitItem()
            assertTrue(event is AgentEvent.Error)
            awaitComplete()
        }
    }

    @Test fun `emits FinalAnswer on simple response`() = runTest {
        every { llmEngine.isReady } returns true
        coEvery { llmEngine.embed(any()) } returns FloatArray(384)
        coEvery { retrieveContext(any(), any()) } returns AgentContext(emptyList(), emptyList())
        coEvery { conversationRepository.getRecentMessages(any()) } returns emptyList()
        coEvery { userRulesStore.getRules() } returns UserRules()
        every { llmEngine.generate(any(), any()) } returns flowOf(
            LLMToken("""{"thought":"Simple answer.","final_answer":"Voici la réponse."}""", true)
        )

        agentEngine.process("Bonjour").test {
            awaitItem() // Thinking
            awaitItem() // Token
            val step = awaitItem()
            assertTrue(step is AgentEvent.Step)
            val answer = awaitItem()
            assertTrue(answer is AgentEvent.FinalAnswer)
            assertEquals("Voici la réponse.", (answer as AgentEvent.FinalAnswer).text)
            awaitComplete()
        }
    }

    @Test fun `executes action and then emits final answer`() = runTest {
        every { llmEngine.isReady } returns true
        coEvery { llmEngine.embed(any()) } returns FloatArray(384)
        coEvery { retrieveContext(any(), any()) } returns AgentContext(emptyList(), emptyList())
        coEvery { conversationRepository.getRecentMessages(any()) } returns emptyList()
        coEvery { userRulesStore.getRules() } returns UserRules(autonomyLevel = AutonomyLevel.AUTONOMOUS)
        coEvery { actionExecutor.execute(any()) } returns ActionResult(
            "id", ToolType.CALENDAR_READ, true, "No events."
        )

        var callCount = 0
        every { llmEngine.generate(any(), any()) } answers {
            callCount++
            if (callCount == 1) {
                flowOf(LLMToken("""{"thought":"Check calendar.","action":{"tool":"CALENDAR_READ","params":{"days_ahead":"3"}},"requires_confirmation":false}""", true))
            } else {
                flowOf(LLMToken("""{"thought":"Calendar clear.","final_answer":"Vous n'avez aucun événement."}""", true))
            }
        }

        val events = mutableListOf<AgentEvent>()
        agentEngine.process("Qu'est-ce que j'ai ce week-end ?").collect { events.add(it) }

        assertTrue(events.any { it is AgentEvent.ExecutingAction })
        assertTrue(events.any { it is AgentEvent.FinalAnswer })
    }
}
