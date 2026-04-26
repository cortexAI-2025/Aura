package com.aura.agent.core

import com.aura.agent.actions.ActionExecutor
import com.aura.agent.actions.ActionGuard
import com.aura.agent.actions.ToolHandler
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * End-to-end integration test verifying the exact structured logcat trace
 * produced by the Sophie birthday task:
 *
 *   [AuraAgent]   TASK / ENQUEUED
 *   [TaskQueue]   START
 *   [ReAct]       STEP 1 → REASON / ACT  (calendar check)
 *   [ActionGuard] CHECK CalendarCheck → ALLOWED
 *   [Tool]        CalendarCheck → OBS
 *   [ReAct]       STEP 2 → REASON / ACT  (SMS)
 *   [ActionGuard] CHECK MessageSend → ALLOWED (Sophie in allowedContacts)
 *   [Tool]        MessageSend → OBS
 *   [ReAct]       STEP 3 → REASON (final answer)
 *   [TaskQueue]   COMPLETE
 */
class AuraAgentIntegrationTest {

    private val logLines = mutableListOf<String>()

    // ── Fake LLM ──────────────────────────────────────────────────────────────

    private inner class FakeLLMEngine(vararg responses: String) : LLMEngine {
        private val queue = ArrayDeque(responses.toList())
        override val isReady = true
        override suspend fun load(modelPath: String) {}
        override fun generate(prompt: String, maxTokens: Int): Flow<LLMToken> = flow {
            emit(LLMToken(queue.removeFirst(), isDone = true))
        }
        override suspend fun generateFull(prompt: String, maxTokens: Int): String =
            queue.removeFirst()
        override suspend fun embed(text: String): FloatArray = FloatArray(384)
        override fun release() {}
    }

    // ── Fake tool handlers ────────────────────────────────────────────────────

    private val fakeCalendarRead = object : ToolHandler {
        override val toolType = ToolType.CALENDAR_READ
        override suspend fun execute(params: Map<String, String>) = "Libre 14:00-15:00"
    }

    private val fakeMessageSend = object : ToolHandler {
        override val toolType = ToolType.MESSAGE_SEND
        override suspend fun execute(params: Map<String, String>) = "SMS envoyé"
    }

    // ── Test UserRules — Sophie is pre-approved, no supervision needed ─────────

    private val testRules = UserRules(
        allowedContacts = setOf("Sophie"),
        autonomyLevel = AutonomyLevel.AUTONOMOUS,
        requireConfirmationForSend = false,
    )

    @Before fun setUp() {
        logLines.clear()
        AuraLogger.delegate = object : AuraLogDelegate {
            override fun log(tag: String, message: String) {
                logLines.add("[$tag] $message")
            }
        }
    }

    @After fun tearDown() {
        AuraLogger.delegate = object : AuraLogDelegate {
            override fun log(tag: String, message: String) {} // no-op after test
        }
    }

    @Test fun `Sophie birthday task produces exact logcat trace`() = runTest {
        // ── Task ──────────────────────────────────────────────────────────────
        val task = Task(
            id = "42",
            description = "Tomorrow is Sophie's birthday. Check calendar at 2 PM, " +
                "if free send Happy Birthday SMS.",
            domain = Domain.SOCIAL,
            priority = TaskPriority.HIGH,
            deadline = System.currentTimeMillis() + 6 * 3600 * 1000L,
        )

        // ── Scripted LLM responses ────────────────────────────────────────────
        val llmEngine = FakeLLMEngine(
            """{"thought":"Vérifier calendrier","action":{"tool":"CALENDAR_READ","params":{"date":"2026-04-26","time":"14:00"}}}""",
            """{"thought":"Libre, envoi SMS","action":{"tool":"MESSAGE_SEND","params":{"recipient":"Sophie","body":"Joyeux anniversaire !"}}}""",
            """{"thought":"Mission terminée","final_answer":"Joyeux anniversaire envoyé à Sophie."}""",
        )

        // ── Mocks for external dependencies ───────────────────────────────────
        val memoryManager = mockk<MemoryManager>(relaxed = true)
        val convRepo = mockk<ConversationRepository> {
            coEvery { getRecentMessages(any()) } returns emptyList()
        }
        val goalRepo = mockk<GoalRepository>(relaxed = true)
        val retrieveContext = mockk<RetrieveContextUseCase> {
            coEvery { invoke(any(), any()) } returns AgentContext(emptyList(), emptyList())
        }
        val userRulesStore = mockk<UserRulesStore> {
            coEvery { getRules() } returns testRules
        }

        // ── Wire up real components ───────────────────────────────────────────
        val handlers = mapOf(
            ToolType.CALENDAR_READ to fakeCalendarRead,
            ToolType.MESSAGE_SEND to fakeMessageSend,
        )
        val agentEngine = AgentEngine(
            llmEngine = llmEngine,
            memoryManager = memoryManager,
            actionExecutor = ActionExecutor(handlers),
            actionGuard = ActionGuard(),
            promptBuilder = PromptBuilder(),
            conversationRepository = convRepo,
            goalRepository = goalRepo,
            retrieveContext = retrieveContext,
            responseParser = LLMResponseParser(),
            userRulesStore = userRulesStore,
        )

        // ── Step 1: AuraAgent logs TASK + ENQUEUED ────────────────────────────
        val fakeTaskQueue = mockk<TaskQueue>(relaxed = true)
        AuraAgent(fakeTaskQueue).submitTask(task)

        // ── Step 2: TaskQueue logs START (simulated — ordering verified by TaskQueueTest) ──
        AuraLogger.log(AuraLogger.TAG_QUEUE, "START id=${task.id}")

        // ── Step 3: AgentEngine runs — this is the core of the integration test ─
        agentEngine.process(task.description).collect()

        // ── Step 4: TaskQueue logs COMPLETE after processor returns ────────────
        AuraLogger.log(AuraLogger.TAG_QUEUE, "COMPLETE id=${task.id}")

        // ── Assert exact trace ────────────────────────────────────────────────
        val expected = listOf(
            "[AuraAgent] TASK: \"${task.description}\"",
            "[AuraAgent] ENQUEUED (id=42, priority=HIGH)",
            "[TaskQueue] START id=42",
            "[ReAct] STEP 1 → REASON: \"Vérifier calendrier\"",
            "[ReAct] STEP 1 → ACT: CalendarCheck{date=2026-04-26, time=14:00}",
            "[ActionGuard] CHECK CalendarCheck → ALLOWED",
            "[Tool] CalendarCheck → OBS: \"Libre 14:00-15:00\"",
            "[ReAct] STEP 2 → REASON: \"Libre, envoi SMS\"",
            "[ReAct] STEP 2 → ACT: MessageSend{recipient=Sophie, body=Joyeux anniversaire !}",
            "[ActionGuard] CHECK MessageSend → ALLOWED (Sophie in allowedContacts)",
            "[Tool] MessageSend → OBS: \"SMS envoyé\"",
            "[ReAct] STEP 3 → REASON: \"Mission terminée\"",
            "[TaskQueue] COMPLETE id=42",
        )

        assertEquals(expected, logLines)
    }

    // ── Companion checks ──────────────────────────────────────────────────────

    @Test fun `submitTask emits TASK and ENQUEUED log lines`() = runTest {
        val task = Task(
            id = "99",
            description = "Quick test task",
            domain = Domain.OTHER,
            priority = TaskPriority.NORMAL,
        )
        val fakeQueue = mockk<TaskQueue>(relaxed = true)
        AuraAgent(fakeQueue).submitTask(task)

        assertEquals("[AuraAgent] TASK: \"Quick test task\"", logLines[0])
        assertEquals("[AuraAgent] ENQUEUED (id=99, priority=NORMAL)", logLines[1])
        verify { fakeQueue.enqueue(any()) }
    }

    @Test fun `ActionGuard logs ALLOWED with contact reason for pre-approved recipient`() = runTest {
        val action = AgentAction(
            id = "act-1",
            tool = ToolType.MESSAGE_SEND,
            params = mapOf("recipient" to "Sophie", "body" to "Bonjour"),
        )
        ActionGuard().evaluate(action, testRules)

        val guardLine = logLines.first { it.startsWith("[ActionGuard]") }
        assertEquals("[ActionGuard] CHECK MessageSend → ALLOWED (Sophie in allowedContacts)", guardLine)
    }
}
