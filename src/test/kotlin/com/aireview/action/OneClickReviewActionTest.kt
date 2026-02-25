package com.aireview.action

import com.aireview.service.GitHubService
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for OneClickReviewAction notification message building logic.
 * Extracts and tests the message formatting independently of IntelliJ platform services.
 */
class OneClickReviewActionTest {

    /**
     * Replicates the message building logic from OneClickReviewAction.runFullPipeline()
     * to test formatting without requiring IntelliJ platform or subprocess execution.
     */
    private fun buildCompletionMessage(
        prCreated: Boolean,
        prNumber: Int,
        findingsCount: Int,
        publishResult: GitHubService.PublishResult
    ): String {
        return buildString {
            if (prCreated) {
                append("Created PR #$prNumber")
            } else {
                append("Updated PR #$prNumber")
            }
            append(" with $findingsCount finding(s)")
            if (publishResult.published > 0) {
                append(", ${publishResult.published} comment(s) published")
            }
            if (publishResult.skipped > 0) {
                append(", ${publishResult.skipped} duplicate(s) skipped")
            }
        }
    }

    @Test
    fun `message shows Created for new PR`() {
        val msg = buildCompletionMessage(
            prCreated = true, prNumber = 42, findingsCount = 3,
            publishResult = GitHubService.PublishResult(published = 3, skipped = 0)
        )
        assertTrue(msg.startsWith("Created PR #42"))
    }

    @Test
    fun `message shows Updated for existing PR`() {
        val msg = buildCompletionMessage(
            prCreated = false, prNumber = 7, findingsCount = 5,
            publishResult = GitHubService.PublishResult(published = 5, skipped = 0)
        )
        assertTrue(msg.startsWith("Updated PR #7"))
    }

    @Test
    fun `message includes findings count`() {
        val msg = buildCompletionMessage(
            prCreated = true, prNumber = 1, findingsCount = 10,
            publishResult = GitHubService.PublishResult(published = 10, skipped = 0)
        )
        assertTrue(msg.contains("with 10 finding(s)"))
    }

    @Test
    fun `message includes published count`() {
        val msg = buildCompletionMessage(
            prCreated = true, prNumber = 1, findingsCount = 3,
            publishResult = GitHubService.PublishResult(published = 3, skipped = 0)
        )
        assertTrue(msg.contains("3 comment(s) published"))
    }

    @Test
    fun `message includes skipped duplicates`() {
        val msg = buildCompletionMessage(
            prCreated = false, prNumber = 5, findingsCount = 8,
            publishResult = GitHubService.PublishResult(published = 5, skipped = 3)
        )
        assertTrue(msg.contains("5 comment(s) published"))
        assertTrue(msg.contains("3 duplicate(s) skipped"))
    }

    @Test
    fun `message omits published when zero`() {
        val msg = buildCompletionMessage(
            prCreated = false, prNumber = 5, findingsCount = 3,
            publishResult = GitHubService.PublishResult(published = 0, skipped = 3)
        )
        assertFalse(msg.contains("comment(s) published"))
        assertTrue(msg.contains("3 duplicate(s) skipped"))
    }

    @Test
    fun `message omits skipped when zero`() {
        val msg = buildCompletionMessage(
            prCreated = true, prNumber = 1, findingsCount = 2,
            publishResult = GitHubService.PublishResult(published = 2, skipped = 0)
        )
        assertTrue(msg.contains("2 comment(s) published"))
        assertFalse(msg.contains("duplicate(s) skipped"))
    }

    @Test
    fun `message with zero findings and zero publish`() {
        val msg = buildCompletionMessage(
            prCreated = true, prNumber = 1, findingsCount = 0,
            publishResult = GitHubService.PublishResult(published = 0, skipped = 0)
        )
        assertEquals("Created PR #1 with 0 finding(s)", msg)
    }

    @Test
    fun `message with all duplicates skipped`() {
        val msg = buildCompletionMessage(
            prCreated = false, prNumber = 99, findingsCount = 5,
            publishResult = GitHubService.PublishResult(published = 0, skipped = 5)
        )
        assertEquals("Updated PR #99 with 5 finding(s), 5 duplicate(s) skipped", msg)
    }

    @Test
    fun `message singular finding count`() {
        val msg = buildCompletionMessage(
            prCreated = true, prNumber = 1, findingsCount = 1,
            publishResult = GitHubService.PublishResult(published = 1, skipped = 0)
        )
        assertTrue(msg.contains("1 finding(s)"))
        assertTrue(msg.contains("1 comment(s) published"))
    }
}
