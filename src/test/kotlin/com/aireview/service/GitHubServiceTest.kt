package com.aireview.service

import com.aireview.model.FindingSource
import com.aireview.model.ReviewFinding
import com.aireview.model.SelectableFinding
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for GitHubService JSON payload construction logic.
 * Uses the same buildJsonObject approach as the production code.
 */
class GitHubServiceTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Replicate the payload construction logic from GitHubService.publishReview()
     * to verify JSON structure without needing a real gh CLI.
     */
    private fun buildReviewPayload(
        findings: List<SelectableFinding>,
        reviewBody: String = ""
    ): JsonObject {
        return buildJsonObject {
            put("body", reviewBody)
            put("event", "COMMENT")
            putJsonArray("comments") {
                for (sf in findings) {
                    val finding = sf.finding
                    val body = GitHubService.buildCommentBody(sf)
                    addJsonObject {
                        put("path", finding.filePath)
                        put("line", finding.line)
                        put("side", "RIGHT")
                        put("body", body)
                    }
                }
            }
        }
    }

    @Test
    fun `buildReviewPayload creates valid JSON structure`() {
        val finding = ReviewFinding(filePath = "a.kt", line = 1, message = "Bug found", severity = "info")
        val sf = SelectableFinding(finding = finding)
        val payload = buildReviewPayload(listOf(sf))

        assertEquals("COMMENT", payload["event"]?.jsonPrimitive?.content)

        val comments = payload["comments"]?.jsonArray
        assertNotNull(comments)
        assertEquals(1, comments!!.size)

        val comment = comments[0].jsonObject
        assertEquals("a.kt", comment["path"]?.jsonPrimitive?.content)
        assertEquals(1, comment["line"]?.jsonPrimitive?.int)
        assertEquals("RIGHT", comment["side"]?.jsonPrimitive?.content)
        assertEquals("Bug found", comment["body"]?.jsonPrimitive?.content)
    }

    @Test
    fun `buildReviewPayload handles multiple findings`() {
        val findings = listOf(
            SelectableFinding(ReviewFinding(filePath = "a.kt", line = 1, message = "msg1")),
            SelectableFinding(ReviewFinding(filePath = "b.kt", line = 5, message = "msg2")),
            SelectableFinding(ReviewFinding(filePath = "c.kt", line = 10, message = "msg3"))
        )
        val payload = buildReviewPayload(findings)
        val comments = payload["comments"]?.jsonArray
        assertEquals(3, comments?.size)
    }

    @Test
    fun `buildReviewPayload includes suggestion in body`() {
        val finding = ReviewFinding(
            filePath = "a.kt", line = 1, message = "Issue",
            suggestion = "Fix this way"
        )
        val payload = buildReviewPayload(listOf(SelectableFinding(finding = finding)))
        val body = payload["comments"]?.jsonArray?.get(0)?.jsonObject?.get("body")?.jsonPrimitive?.content
        assertTrue(body!!.contains("**Suggestion:** Fix this way"))
    }

    @Test
    fun `buildReviewPayload includes suggestion patch as code block`() {
        val finding = ReviewFinding(
            filePath = "a.kt", line = 1, message = "Issue",
            suggestionPatch = "val x = 1"
        )
        val payload = buildReviewPayload(listOf(SelectableFinding(finding = finding)))
        val body = payload["comments"]?.jsonArray?.get(0)?.jsonObject?.get("body")?.jsonPrimitive?.content
        assertTrue(body!!.contains("```suggestion\nval x = 1\n```"))
    }

    @Test
    fun `buildReviewPayload escapes special characters in JSON`() {
        val finding = ReviewFinding(
            filePath = "a.kt", line = 1,
            message = "String with \"quotes\" and \\backslashes and\nnewlines"
        )
        val payload = buildReviewPayload(listOf(SelectableFinding(finding = finding)))
        val jsonStr = payload.toString()
        assertTrue(jsonStr.isNotBlank())
        val reparsed = json.parseToJsonElement(jsonStr)
        val body = reparsed.jsonObject["comments"]?.jsonArray?.get(0)?.jsonObject?.get("body")?.jsonPrimitive?.content
        assertTrue(body!!.contains("\"quotes\""))
        assertTrue(body.contains("\\backslashes"))
        assertTrue(body.contains("\n"))
    }

    @Test
    fun `buildReviewPayload handles empty findings list`() {
        val payload = buildReviewPayload(emptyList())
        val comments = payload["comments"]?.jsonArray
        assertEquals(0, comments?.size)
    }

    @Test
    fun `buildReviewPayload uses custom review body`() {
        val payload = buildReviewPayload(emptyList(), "Custom review body")
        assertEquals("Custom review body", payload["body"]?.jsonPrimitive?.content)
    }

    @Test
    fun `buildReviewPayload with suggestion and patch combined`() {
        val finding = ReviewFinding(
            filePath = "a.kt", line = 42,
            message = "Potential NPE",
            suggestion = "Add null check",
            suggestionPatch = "val x = y ?: return"
        )
        val payload = buildReviewPayload(listOf(SelectableFinding(finding = finding)))
        val body = payload["comments"]?.jsonArray?.get(0)?.jsonObject?.get("body")?.jsonPrimitive?.content!!
        assertTrue(body.contains("Potential NPE"))
        assertTrue(body.contains("**Suggestion:** Add null check"))
        assertTrue(body.contains("```suggestion\nval x = y ?: return\n```"))
    }

    @Test
    fun `PrInfo data class holds correct values`() {
        val pr = GitHubService.PrInfo(
            number = 42,
            title = "Fix bug",
            headBranch = "feature/fix",
            baseBranch = "main"
        )
        assertEquals(42, pr.number)
        assertEquals("Fix bug", pr.title)
        assertEquals("feature/fix", pr.headBranch)
        assertEquals("main", pr.baseBranch)
        assertEquals("", pr.body)
    }

    @Test
    fun `PrInfo includes body field`() {
        val pr = GitHubService.PrInfo(
            number = 7,
            title = "Add feature",
            headBranch = "feat",
            baseBranch = "main",
            body = "## Summary\n- Added feature X"
        )
        assertEquals("## Summary\n- Added feature X", pr.body)
    }

    @Test
    fun `PrInfo body defaults to empty string`() {
        val pr = GitHubService.PrInfo(1, "t", "h", "b")
        assertEquals("", pr.body)
    }

    @Test
    fun `PrInfo supports copy`() {
        val pr = GitHubService.PrInfo(1, "title", "head", "base")
        val copy = pr.copy(number = 2)
        assertEquals(1, pr.number)
        assertEquals(2, copy.number)
    }

    @Test
    fun `GitHubCliException preserves message`() {
        val ex = GitHubCliException("test error")
        assertEquals("test error", ex.message)
    }

    @Test
    fun `GitHubCliException preserves cause`() {
        val cause = RuntimeException("root")
        val ex = GitHubCliException("wrapper", cause)
        assertEquals("wrapper", ex.message)
        assertEquals("root", ex.cause?.message)
    }

    @Test
    fun `buildReviewPayload handles unicode in message`() {
        val finding = ReviewFinding(
            filePath = "a.kt", line = 1,
            message = "Unicode: \u00e9\u00e8\u00ea \u2603 \u2764"
        )
        val payload = buildReviewPayload(listOf(SelectableFinding(finding = finding)))
        val jsonStr = payload.toString()
        val reparsed = json.parseToJsonElement(jsonStr)
        val body = reparsed.jsonObject["comments"]?.jsonArray?.get(0)?.jsonObject?.get("body")?.jsonPrimitive?.content
        assertTrue(body!!.contains("\u00e9"))
        assertTrue(body.contains("\u2603"))
    }

    // --- Severity badge tests ---

    @Test
    fun `buildCommentBody adds Critical badge for error severity`() {
        val sf = SelectableFinding(ReviewFinding(filePath = "a.kt", line = 1, severity = "error", message = "Bad thing"))
        val body = GitHubService.buildCommentBody(sf)
        assertTrue(body.startsWith("**[Critical]** Bad thing"))
    }

    @Test
    fun `buildCommentBody adds Major badge for warning severity`() {
        val sf = SelectableFinding(ReviewFinding(filePath = "a.kt", line = 1, severity = "warning", message = "Meh thing"))
        val body = GitHubService.buildCommentBody(sf)
        assertTrue(body.startsWith("**[Major]** Meh thing"))
    }

    @Test
    fun `buildCommentBody has no badge for info severity`() {
        val sf = SelectableFinding(ReviewFinding(filePath = "a.kt", line = 1, severity = "info", message = "Small thing"))
        val body = GitHubService.buildCommentBody(sf)
        assertEquals("Small thing", body)
    }

    @Test
    fun `buildCommentBody includes suggestion and patch with badge`() {
        val sf = SelectableFinding(ReviewFinding(
            filePath = "a.kt", line = 1, severity = "error",
            message = "NPE risk", suggestion = "Add null check", suggestionPatch = "x ?: return"
        ))
        val body = GitHubService.buildCommentBody(sf)
        assertTrue(body.startsWith("**[Critical]** NPE risk"))
        assertTrue(body.contains("**Suggestion:** Add null check"))
        assertTrue(body.contains("```suggestion\nx ?: return\n```"))
    }

    @Test
    fun `review body passes through reviewBody parameter only`() {
        val findings = listOf(
            SelectableFinding(ReviewFinding(filePath = "a.kt", line = 1, severity = "error", message = "Bug")),
            SelectableFinding(ReviewFinding(filePath = "b.kt", line = 2, severity = "info", message = "Nit"))
        )
        val payload = buildReviewPayload(findings)
        val body = payload["body"]?.jsonPrimitive?.content!!
        assertEquals("", body)
    }

    @Test
    fun `review body contains only custom text when provided`() {
        val findings = listOf(
            SelectableFinding(ReviewFinding(filePath = "a.kt", line = 1, severity = "warning", message = "Issue"))
        )
        val payload = buildReviewPayload(findings, "LGTM with comments")
        val body = payload["body"]?.jsonPrimitive?.content!!
        assertEquals("LGTM with comments", body)
    }
}
