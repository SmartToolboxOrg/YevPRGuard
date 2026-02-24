package com.aireview.service

import com.aireview.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for FindingsManager business logic — serialization, persistence models,
 * and selection logic. These tests don't require IntelliJ platform since they
 * exercise the data models and serialization used by FindingsManager.
 */
class FindingsManagerLogicTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // --- PersistedSession serialization ---

    @Test
    fun `PersistedSession round-trip with AI findings`() {
        val session = PersistedSession(
            diffHash = "abc123",
            baseRef = "main",
            headRef = "HEAD",
            timestamp = System.currentTimeMillis(),
            findings = listOf(
                PersistedFinding(
                    finding = ReviewFinding(filePath = "a.kt", line = 1, message = "msg"),
                    source = "AI",
                    selected = true,
                    id = "id-1"
                )
            )
        )
        val jsonStr = json.encodeToString(session)
        val decoded = json.decodeFromString<PersistedSession>(jsonStr)
        assertEquals("abc123", decoded.diffHash)
        assertEquals(1, decoded.findings.size)
        assertEquals("AI", decoded.findings[0].source)
    }

    @Test
    fun `PersistedSession with mixed AI and MANUAL findings`() {
        val session = PersistedSession(
            diffHash = "hash1",
            baseRef = "main",
            headRef = "HEAD",
            timestamp = 1000L,
            findings = listOf(
                PersistedFinding(
                    finding = ReviewFinding(filePath = "a.kt", line = 1, message = "ai msg"),
                    source = "AI", selected = true, id = "1"
                ),
                PersistedFinding(
                    finding = ReviewFinding(filePath = "b.kt", line = 5, severity = "info", ruleId = "manual", message = "manual msg"),
                    source = "MANUAL", selected = false, id = "2"
                )
            )
        )
        val jsonStr = json.encodeToString(session)
        val decoded = json.decodeFromString<PersistedSession>(jsonStr)
        assertEquals(2, decoded.findings.size)
        assertEquals("AI", decoded.findings[0].source)
        assertTrue(decoded.findings[0].selected)
        assertEquals("MANUAL", decoded.findings[1].source)
        assertFalse(decoded.findings[1].selected)
    }

    @Test
    fun `PersistedSession with empty findings`() {
        val session = PersistedSession(
            diffHash = "empty", baseRef = "main", headRef = "HEAD",
            timestamp = 0L, findings = emptyList()
        )
        val jsonStr = json.encodeToString(session)
        val decoded = json.decodeFromString<PersistedSession>(jsonStr)
        assertTrue(decoded.findings.isEmpty())
    }

    // --- SelectableFinding behavior ---

    @Test
    fun `SelectableFinding toggle selection`() {
        val sf = SelectableFinding(
            ReviewFinding(filePath = "a.kt", line = 1, message = "msg")
        )
        assertTrue(sf.selected)
        sf.selected = false
        assertFalse(sf.selected)
        sf.selected = true
        assertTrue(sf.selected)
    }

    @Test
    fun `SelectableFinding edit message`() {
        val sf = SelectableFinding(
            ReviewFinding(filePath = "a.kt", line = 1, message = "original")
        )
        sf.finding = sf.finding.copy(message = "edited")
        assertEquals("edited", sf.finding.message)
        // filePath unchanged
        assertEquals("a.kt", sf.finding.filePath)
    }

    @Test
    fun `SelectableFinding IDs are unique`() {
        val ids = (1..100).map {
            SelectableFinding(ReviewFinding(filePath = "a.kt", line = it, message = "msg")).id
        }.toSet()
        assertEquals(100, ids.size)
    }

    @Test
    fun `SelectableFinding sources`() {
        val ai = SelectableFinding(
            ReviewFinding(filePath = "a.kt", line = 1, message = "msg"),
            source = FindingSource.AI
        )
        val manual = SelectableFinding(
            ReviewFinding(filePath = "a.kt", line = 1, message = "msg"),
            source = FindingSource.MANUAL
        )
        assertEquals(FindingSource.AI, ai.source)
        assertEquals(FindingSource.MANUAL, manual.source)
    }

    // --- Selection logic (simulated) ---

    @Test
    fun `selectAll marks all as selected`() {
        val findings = listOf(
            SelectableFinding(ReviewFinding(filePath = "a.kt", line = 1, message = "1"), selected = false),
            SelectableFinding(ReviewFinding(filePath = "b.kt", line = 2, message = "2"), selected = false),
            SelectableFinding(ReviewFinding(filePath = "c.kt", line = 3, message = "3"), selected = true)
        )
        findings.forEach { it.selected = true }
        assertTrue(findings.all { it.selected })
    }

    @Test
    fun `deselectAll marks all as deselected`() {
        val findings = listOf(
            SelectableFinding(ReviewFinding(filePath = "a.kt", line = 1, message = "1"), selected = true),
            SelectableFinding(ReviewFinding(filePath = "b.kt", line = 2, message = "2"), selected = true)
        )
        findings.forEach { it.selected = false }
        assertTrue(findings.none { it.selected })
    }

    @Test
    fun `filter selected findings`() {
        val findings = listOf(
            SelectableFinding(ReviewFinding(filePath = "a.kt", line = 1, message = "1"), selected = true),
            SelectableFinding(ReviewFinding(filePath = "b.kt", line = 2, message = "2"), selected = false),
            SelectableFinding(ReviewFinding(filePath = "c.kt", line = 3, message = "3"), selected = true)
        )
        val selected = findings.filter { it.selected }
        assertEquals(2, selected.size)
        assertEquals("a.kt", selected[0].finding.filePath)
        assertEquals("c.kt", selected[1].finding.filePath)
    }

    @Test
    fun `filter findings by file`() {
        val findings = listOf(
            SelectableFinding(ReviewFinding(filePath = "src/a.kt", line = 1, message = "1")),
            SelectableFinding(ReviewFinding(filePath = "src/b.kt", line = 2, message = "2")),
            SelectableFinding(ReviewFinding(filePath = "src/a.kt", line = 5, message = "3"))
        )
        val forFile = findings.filter {
            it.finding.filePath.replace('\\', '/') == "src/a.kt"
        }
        assertEquals(2, forFile.size)
    }

    @Test
    fun `group findings by file`() {
        val findings = listOf(
            SelectableFinding(ReviewFinding(filePath = "a.kt", line = 1, message = "1")),
            SelectableFinding(ReviewFinding(filePath = "b.kt", line = 2, message = "2")),
            SelectableFinding(ReviewFinding(filePath = "a.kt", line = 5, message = "3"))
        )
        val grouped = findings.map { it.finding }.groupBy { it.filePath }
        assertEquals(2, grouped.size)
        assertEquals(2, grouped["a.kt"]?.size)
        assertEquals(1, grouped["b.kt"]?.size)
    }

    // --- Manual comment creation ---

    @Test
    fun `manual comment finding has correct defaults`() {
        val finding = ReviewFinding(
            filePath = "src/Main.kt",
            line = 42,
            severity = "info",
            ruleId = "manual",
            message = "This looks wrong"
        )
        assertEquals("info", finding.severity)
        assertEquals("manual", finding.ruleId)
        assertEquals(Severity.INFO, finding.severityEnum)
    }

    @Test
    fun `remove manual comment only removes MANUAL source`() {
        val findings = mutableListOf(
            SelectableFinding(ReviewFinding(filePath = "a.kt", line = 1, message = "ai"), source = FindingSource.AI),
            SelectableFinding(ReviewFinding(filePath = "a.kt", line = 2, message = "manual"), source = FindingSource.MANUAL)
        )
        val manualId = findings[1].id
        findings.removeIf { it.id == manualId && it.source == FindingSource.MANUAL }
        assertEquals(1, findings.size)
        assertEquals(FindingSource.AI, findings[0].source)
    }

    @Test
    fun `remove manual comment does not remove AI finding with same id pattern`() {
        val findings = mutableListOf(
            SelectableFinding(ReviewFinding(filePath = "a.kt", line = 1, message = "ai"), source = FindingSource.AI),
            SelectableFinding(ReviewFinding(filePath = "a.kt", line = 2, message = "manual"), source = FindingSource.MANUAL)
        )
        val aiId = findings[0].id
        // Try removing with AI id but MANUAL filter -> should not remove
        findings.removeIf { it.id == aiId && it.source == FindingSource.MANUAL }
        assertEquals(2, findings.size)
    }

    // --- ReviewResult ---

    @Test
    fun `ReviewResult timestamp is auto-generated`() {
        val result = ReviewResult(
            findings = emptyList(),
            diffHash = "hash1",
            baseRef = "main",
            headRef = "HEAD"
        )
        assertTrue(result.timestamp > 0)
    }

    @Test
    fun `ReviewResult with explicit timestamp`() {
        val result = ReviewResult(
            findings = emptyList(),
            diffHash = "hash1",
            baseRef = "main",
            headRef = "HEAD",
            timestamp = 12345L
        )
        assertEquals(12345L, result.timestamp)
    }

    // --- Path normalization ---

    @Test
    fun `path normalization handles backslashes`() {
        val normalize = { path: String -> path.replace('\\', '/') }
        assertEquals("src/a.kt", normalize("src\\a.kt"))
        assertEquals("src/main/a.kt", normalize("src\\main\\a.kt"))
        assertEquals("a.kt", normalize("a.kt"))
    }

    @Test
    fun `path normalization handles mixed slashes`() {
        val normalize = { path: String -> path.replace('\\', '/') }
        assertEquals("src/main/a.kt", normalize("src\\main/a.kt"))
    }

    // --- PersistedState behavior ---

    @Test
    fun `PersistedState defaults`() {
        val state = FindingsManager.PersistedState()
        assertTrue(state.sessions.isEmpty())
        assertEquals("", state.lastDiffHash)
    }

    @Test
    fun `PersistedState is mutable`() {
        val state = FindingsManager.PersistedState()
        state.lastDiffHash = "abc"
        state.sessions["key"] = "value"
        assertEquals("abc", state.lastDiffHash)
        assertEquals("value", state.sessions["key"])
    }

    @Test
    fun `PersistedState sessions can hold multiple entries`() {
        val state = FindingsManager.PersistedState()
        for (i in 1..5) {
            state.sessions["hash$i"] = """{"diffHash":"hash$i"}"""
        }
        assertEquals(5, state.sessions.size)
    }

    // --- Edge cases ---

    @Test
    fun `PersistedFinding preserves all ReviewFinding fields`() {
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
        val pf = PersistedFinding(finding = finding, source = "AI", selected = true, id = "test-id")
        val jsonStr = json.encodeToString(pf)
        val decoded = json.decodeFromString<PersistedFinding>(jsonStr)
        assertEquals(42, decoded.finding.line)
        assertEquals(50, decoded.finding.endLine)
        assertEquals("null-safety", decoded.finding.ruleId)
        assertEquals("Add null check", decoded.finding.suggestion)
    }

    @Test
    fun `PersistedSession large findings list`() {
        val findings = (1..50).map { i ->
            PersistedFinding(
                finding = ReviewFinding(filePath = "file$i.kt", line = i, message = "msg$i"),
                source = if (i % 3 == 0) "MANUAL" else "AI",
                selected = i % 2 == 0,
                id = "id-$i"
            )
        }
        val session = PersistedSession(
            diffHash = "large", baseRef = "main", headRef = "HEAD",
            timestamp = 999L, findings = findings
        )
        val jsonStr = json.encodeToString(session)
        val decoded = json.decodeFromString<PersistedSession>(jsonStr)
        assertEquals(50, decoded.findings.size)
        assertEquals("MANUAL", decoded.findings[2].source) // index 2 = i=3
        assertFalse(decoded.findings[0].selected) // i=1, odd -> false
        assertTrue(decoded.findings[1].selected) // i=2, even -> true
    }
}
