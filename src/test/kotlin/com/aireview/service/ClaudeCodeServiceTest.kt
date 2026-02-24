package com.aireview.service

import com.aireview.model.ReviewFinding
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for ClaudeCodeService JSON parsing logic.
 * Uses reflection to test private parsing methods.
 */
class ClaudeCodeServiceTest {

    private val service = ClaudeCodeService()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Use reflection to call private parseResponse/parseFindingsFromText
    private fun parseFindingsFromText(text: String): List<ReviewFinding> {
        val method = ClaudeCodeService::class.java.getDeclaredMethod("parseFindingsFromText", String::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(service, text) as List<ReviewFinding>
    }

    private fun parseResponse(stdout: String): List<ReviewFinding> {
        val method = ClaudeCodeService::class.java.getDeclaredMethod("parseResponse", String::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(service, stdout) as List<ReviewFinding>
    }

    @Test
    fun `parseFindingsFromText parses valid JSON array`() {
        val findings = listOf(
            ReviewFinding(filePath = "a.kt", line = 1, severity = "error", message = "Bug found")
        )
        val jsonStr = json.encodeToString(findings)
        val result = parseFindingsFromText(jsonStr)
        assertEquals(1, result.size)
        assertEquals("Bug found", result[0].message)
    }

    @Test
    fun `parseFindingsFromText handles empty array`() {
        val result = parseFindingsFromText("[]")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseFindingsFromText strips markdown fences`() {
        val findings = listOf(
            ReviewFinding(filePath = "a.kt", line = 1, message = "msg")
        )
        val wrapped = "```json\n${json.encodeToString(findings)}\n```"
        val result = parseFindingsFromText(wrapped)
        assertEquals(1, result.size)
    }

    @Test
    fun `parseFindingsFromText extracts array via regex fallback`() {
        val preamble = "Here are the findings:\n"
        val findings = listOf(
            ReviewFinding(filePath = "a.kt", line = 1, message = "msg")
        )
        val text = preamble + json.encodeToString(findings) + "\nEnd."
        val result = parseFindingsFromText(text)
        assertEquals(1, result.size)
    }

    @Test
    fun `parseResponse handles CLI JSON envelope`() {
        val findings = listOf(
            ReviewFinding(filePath = "a.kt", line = 1, message = "msg")
        )
        val innerJson = json.encodeToString(findings)
        val envelope = """{"result":"${innerJson.replace("\"", "\\\"")}","type":"text"}"""
        val result = parseResponse(envelope)
        assertEquals(1, result.size)
    }

    @Test
    fun `parseResponse handles raw JSON output`() {
        val findings = listOf(
            ReviewFinding(filePath = "a.kt", line = 1, message = "msg")
        )
        val result = parseResponse(json.encodeToString(findings))
        assertEquals(1, result.size)
    }

    @Test
    fun `parseResponse returns empty for blank input`() {
        val result = parseResponse("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parseFindingsFromText handles multiple findings`() {
        val findings = listOf(
            ReviewFinding(filePath = "a.kt", line = 1, severity = "error", message = "msg1"),
            ReviewFinding(filePath = "b.kt", line = 5, severity = "warning", message = "msg2"),
            ReviewFinding(filePath = "c.kt", line = 10, severity = "info", message = "msg3")
        )
        val result = parseFindingsFromText(json.encodeToString(findings))
        assertEquals(3, result.size)
        assertEquals("error", result[0].severity)
        assertEquals("warning", result[1].severity)
        assertEquals("info", result[2].severity)
    }

    @Test
    fun `parseFindingsFromText preserves all fields`() {
        val finding = ReviewFinding(
            filePath = "src/Foo.kt",
            line = 42,
            endLine = 50,
            severity = "warning",
            ruleId = "null-safety",
            message = "Potential NPE",
            suggestion = "Add null check",
            suggestionPatch = "--- a\n+++ b"
        )
        val result = parseFindingsFromText(json.encodeToString(listOf(finding)))
        assertEquals(1, result.size)
        val r = result[0]
        assertEquals("src/Foo.kt", r.filePath)
        assertEquals(42, r.line)
        assertEquals(50, r.endLine)
        assertEquals("null-safety", r.ruleId)
        assertEquals("Add null check", r.suggestion)
    }

    @Test
    fun `parseFindingsFromText throws on unparseable input`() {
        try {
            parseFindingsFromText("This is not JSON at all")
            fail("Expected ClaudeCliException")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.cause is ClaudeCliException)
        }
    }

    // --- PR Description tests ---

    @Test
    fun `PrDescription data class holds values`() {
        val desc = PrDescription("Add auth", "## Summary\n- Added auth")
        assertEquals("Add auth", desc.title)
        assertEquals("## Summary\n- Added auth", desc.description)
    }

    @Test
    fun `buildPrDescriptionPrompt includes diff and title`() {
        val prompt = service.buildPrDescriptionPrompt("diff content here", emptyList(), "My PR Title")
        assertTrue(prompt.contains("diff content here"))
        assertTrue(prompt.contains("My PR Title"))
        assertTrue(prompt.contains("\"title\""))
        assertTrue(prompt.contains("\"description\""))
    }

    @Test
    fun `buildPrDescriptionPrompt includes JSON schema instructions`() {
        val prompt = service.buildPrDescriptionPrompt("diff", emptyList(), "title")
        assertTrue(prompt.contains("Return ONLY a JSON object"))
        assertTrue(prompt.contains("## Summary"))
        assertTrue(prompt.contains("## Changes"))
        assertTrue(prompt.contains("mermaid"))
    }

    @Test
    fun `buildPrDescriptionPrompt includes findings when present`() {
        val findings = listOf(
            ReviewFinding(filePath = "a.kt", line = 10, severity = "error", message = "Bug here")
        )
        val prompt = service.buildPrDescriptionPrompt("diff", findings, "title")
        assertTrue(prompt.contains("<review-findings>"))
        assertTrue(prompt.contains("[error] a.kt:10"))
        assertTrue(prompt.contains("Bug here"))
    }

    @Test
    fun `buildPrDescriptionPrompt omits findings section when empty`() {
        val prompt = service.buildPrDescriptionPrompt("diff", emptyList(), "title")
        assertFalse(prompt.contains("<review-findings>"))
    }

    @Test
    fun `parsePrDescriptionResponse parses CLI envelope`() {
        val innerJson = """{"title":"Fix auth bug","description":"## Summary\n- Fixed bug"}"""
        val envelope = """{"result":"${innerJson.replace("\"", "\\\"")}","type":"text"}"""
        val method = ClaudeCodeService::class.java.getDeclaredMethod(
            "parsePrDescriptionResponse", String::class.java, String::class.java
        )
        method.isAccessible = true
        val result = method.invoke(service, envelope, "fallback") as PrDescription
        assertEquals("Fix auth bug", result.title)
        assertTrue(result.description.contains("Summary"))
    }

    @Test
    fun `parsePrDescriptionResponse returns fallback on blank input`() {
        val method = ClaudeCodeService::class.java.getDeclaredMethod(
            "parsePrDescriptionResponse", String::class.java, String::class.java
        )
        method.isAccessible = true
        val result = method.invoke(service, "", "Fallback Title") as PrDescription
        assertEquals("Fallback Title", result.title)
        assertEquals("", result.description)
    }
}
