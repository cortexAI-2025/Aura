package com.aura.agent.core

import com.aura.core.domain.model.ToolType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LLMResponseParserTest {
    private lateinit var parser: LLMResponseParser

    @Before fun setUp() { parser = LLMResponseParser() }

    @Test fun `parses final_answer correctly`() {
        val raw = """{"thought":"User wants a summary.","final_answer":"Here is the summary."}"""
        val step = parser.parse(raw)
        assertTrue(step.isFinal)
        assertEquals("Here is the summary.", step.finalAnswer)
        assertNull(step.action)
    }

    @Test fun `parses action with params`() {
        val raw = """{"thought":"I need to check the calendar.","action":{"tool":"CALENDAR_READ","params":{"days_ahead":"7"}},"requires_confirmation":false}"""
        val step = parser.parse(raw)
        assertFalse(step.isFinal)
        assertNotNull(step.action)
        assertEquals(ToolType.CALENDAR_READ, step.action?.tool)
        assertEquals("7", step.action?.params?.get("days_ahead"))
    }

    @Test fun `treats malformed JSON as final answer`() {
        val raw = "Bonjour ! Comment puis-je vous aider ?"
        val step = parser.parse(raw)
        assertTrue(step.isFinal)
        assertEquals(raw, step.finalAnswer)
    }

    @Test fun `extracts JSON embedded in preamble text`() {
        val raw = """Sure! {"thought":"ok","final_answer":"Done."}"""
        val step = parser.parse(raw)
        assertTrue(step.isFinal)
        assertEquals("Done.", step.finalAnswer)
    }

    @Test fun `maps unknown tool to UNKNOWN`() {
        val raw = """{"thought":"...","action":{"tool":"MAKE_COFFEE","params":{}},"requires_confirmation":false}"""
        val step = parser.parse(raw)
        assertEquals(ToolType.UNKNOWN, step.action?.tool)
    }

    @Test fun `requires_confirmation flag parsed correctly`() {
        val raw = """{"thought":"Sending message","action":{"tool":"MESSAGE_SEND","params":{"to":"Alice","body":"hi"}},"requires_confirmation":true}"""
        val step = parser.parse(raw)
        assertTrue(step.action?.requiresConfirmation == true)
    }
}
