package com.aireview.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReviewModelsTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // --- Severity ---

    @Test
    fun `fromString maps known severities`() {
        assertEquals(Severity.INFO, Severity.fromString("info"))
        assertEquals(Severity.WARNING, Severity.fromString("warning"))
        assertEquals(Severity.ERROR, Severity.fromString("error"))
    }

    @Test
    fun `fromString is case insensitive`() {
        assertEquals(Severity.ERROR, Severity.fromString("ERROR"))
        assertEquals(Severity.WARNING, Severity.fromString("Warning"))
    }

    @Test
    fun `fromString defaults to INFO for unknown`() {
        assertEquals(Severity.INFO, Severity.fromString("unknown"))
        assertEquals(Severity.INFO, Severity.fromString(""))
    }

    // --- ReviewFinding ---

    @Test
    fun `ReviewFinding severityEnum delegates to Severity`() {
        val f = ReviewFinding(filePath = "a.kt", line = 1, severity = "error", message = "msg")
        assertEquals(Severity.ERROR, f.severityEnum)
    }

    @Test
    fun `ReviewFinding lineRange with endLine`() {
        val f = ReviewFinding(filePath = "a.kt", line = 5, endLine = 10, message = "msg")
        assertEquals(5..10, f.lineRange)
    }

    @Test
    fun `ReviewFinding lineRange without endLine`() {
        val f = ReviewFinding(filePath = "a.kt", line = 5, message = "msg")
        assertEquals(5..5, f.lineRange)
    }

    @Test
    fun `ReviewFinding default severity is info`() {
        val f = ReviewFinding(filePath = "a.kt", line = 1, message = "msg")
        assertEquals("info", f.severity)
        assertEquals(Severity.INFO, f.severityEnum)
    }

    @Test
    fun `ReviewFinding serialization round-trip`() {
        val f = ReviewFinding(
            filePath = "src/main/Foo.kt",
            line = 42,
            endLine = 50,
            severity = "warning",
            ruleId = "null-safety",
            message = "Potential NPE",
            suggestion = "Add null check",
            suggestionPatch = "--- a.kt\n+++ b.kt"
        )
        val jsonStr = json.encodeToString(f)
        val decoded = json.decodeFromString<ReviewFinding>(jsonStr)
        assertEquals(f, decoded)
    }

    @Test
    fun `ReviewFinding deserialization ignores unknown keys`() {
        val jsonStr = """{"filePath":"a.kt","line":1,"message":"msg","unknownField":"value"}"""
        val f = json.decodeFromString<ReviewFinding>(jsonStr)
        assertEquals("a.kt", f.filePath)
        assertEquals(1, f.line)
        assertEquals("msg", f.message)
    }

    @Test
    fun `ReviewFinding deserialization with minimal fields`() {
        val jsonStr = """{"filePath":"a.kt","line":1,"message":"msg"}"""
        val f = json.decodeFromString<ReviewFinding>(jsonStr)
        assertNull(f.endLine)
        assertNull(f.ruleId)
        assertNull(f.suggestion)
        assertNull(f.suggestionPatch)
        assertEquals("info", f.severity)
    }

    @Test
    fun `ReviewFinding list serialization`() {
        val findings = listOf(
            ReviewFinding(filePath = "a.kt", line = 1, message = "msg1"),
            ReviewFinding(filePath = "b.kt", line = 2, severity = "error", message = "msg2")
        )
        val jsonStr = json.encodeToString(findings)
        val decoded = json.decodeFromString<List<ReviewFinding>>(jsonStr)
        assertEquals(2, decoded.size)
        assertEquals("a.kt", decoded[0].filePath)
        assertEquals("error", decoded[1].severity)
    }

    // --- ReviewFileInfo ---

    @Test
    fun `ReviewFileInfo serialization`() {
        val info = ReviewFileInfo(path = "a.kt", content = "fun main(){}", language = "KOTLIN")
        val jsonStr = json.encodeToString(info)
        val decoded = json.decodeFromString<ReviewFileInfo>(jsonStr)
        assertEquals(info, decoded)
    }

    @Test
    fun `ReviewFileInfo with null content`() {
        val info = ReviewFileInfo(path = "a.kt")
        val jsonStr = json.encodeToString(info)
        val decoded = json.decodeFromString<ReviewFileInfo>(jsonStr)
        assertNull(decoded.content)
        assertNull(decoded.language)
    }

    // --- ReviewRequest ---

    @Test
    fun `ReviewRequest serialization`() {
        val req = ReviewRequest(
            projectName = "test",
            baseRef = "main",
            headRef = "HEAD",
            mode = "WORKTREE",
            diff = "diff content",
            files = listOf(ReviewFileInfo(path = "a.kt"))
        )
        val jsonStr = json.encodeToString(req)
        val decoded = json.decodeFromString<ReviewRequest>(jsonStr)
        assertEquals(req, decoded)
    }

    // --- ReviewResult ---

    @Test
    fun `ReviewResult stores findings and metadata`() {
        val findings = listOf(ReviewFinding(filePath = "a.kt", line = 1, message = "msg"))
        val result = ReviewResult(findings = findings, diffHash = "abc123", baseRef = "main", headRef = "HEAD")
        assertEquals(1, result.findings.size)
        assertEquals("abc123", result.diffHash)
        assertTrue(result.timestamp > 0)
    }

    // --- FindingSource ---

    @Test
    fun `FindingSource enum values`() {
        assertEquals(2, FindingSource.values().size)
        assertNotNull(FindingSource.valueOf("AI"))
        assertNotNull(FindingSource.valueOf("MANUAL"))
    }

    // --- SelectableFinding ---

    @Test
    fun `SelectableFinding default values`() {
        val finding = ReviewFinding(filePath = "a.kt", line = 1, message = "msg")
        val sf = SelectableFinding(finding = finding)
        assertEquals(FindingSource.AI, sf.source)
        assertTrue(sf.selected)
        assertTrue(sf.id.isNotBlank())
    }

    @Test
    fun `SelectableFinding unique IDs`() {
        val finding = ReviewFinding(filePath = "a.kt", line = 1, message = "msg")
        val sf1 = SelectableFinding(finding = finding)
        val sf2 = SelectableFinding(finding = finding)
        assertNotEquals(sf1.id, sf2.id)
    }

    @Test
    fun `SelectableFinding mutable selection`() {
        val finding = ReviewFinding(filePath = "a.kt", line = 1, message = "msg")
        val sf = SelectableFinding(finding = finding)
        assertTrue(sf.selected)
        sf.selected = false
        assertFalse(sf.selected)
    }

    @Test
    fun `SelectableFinding mutable finding for edit`() {
        val finding = ReviewFinding(filePath = "a.kt", line = 1, message = "original")
        val sf = SelectableFinding(finding = finding)
        sf.finding = sf.finding.copy(message = "edited")
        assertEquals("edited", sf.finding.message)
    }

    // --- PersistedFinding ---

    @Test
    fun `PersistedFinding serialization round-trip`() {
        val pf = PersistedFinding(
            finding = ReviewFinding(filePath = "a.kt", line = 1, message = "msg"),
            source = "AI",
            selected = true,
            id = "test-id"
        )
        val jsonStr = json.encodeToString(pf)
        val decoded = json.decodeFromString<PersistedFinding>(jsonStr)
        assertEquals(pf, decoded)
    }

    // --- PersistedSession ---

    @Test
    fun `PersistedSession serialization round-trip`() {
        val session = PersistedSession(
            diffHash = "abc123",
            baseRef = "main",
            headRef = "HEAD",
            timestamp = 12345L,
            findings = listOf(
                PersistedFinding(
                    finding = ReviewFinding(filePath = "a.kt", line = 1, message = "msg"),
                    source = "AI",
                    selected = true,
                    id = "id1"
                ),
                PersistedFinding(
                    finding = ReviewFinding(filePath = "b.kt", line = 2, message = "manual"),
                    source = "MANUAL",
                    selected = false,
                    id = "id2"
                )
            )
        )
        val jsonStr = json.encodeToString(session)
        val decoded = json.decodeFromString<PersistedSession>(jsonStr)
        assertEquals(session.diffHash, decoded.diffHash)
        assertEquals(2, decoded.findings.size)
        assertEquals("MANUAL", decoded.findings[1].source)
        assertFalse(decoded.findings[1].selected)
    }

    // --- ReviewMode ---

    @Test
    fun `ReviewMode enum values`() {
        assertEquals(2, ReviewMode.values().size)
        assertNotNull(ReviewMode.valueOf("WORKTREE"))
        assertNotNull(ReviewMode.valueOf("RANGE"))
    }
}
