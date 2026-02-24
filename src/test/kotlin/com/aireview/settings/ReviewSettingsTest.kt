package com.aireview.settings

import com.aireview.model.ReviewMode
import org.junit.Assert.*
import org.junit.Test

class ReviewSettingsTest {

    @Test
    fun `State has correct defaults`() {
        val state = ReviewSettings.State()
        assertEquals("", state.claudeCliPath)
        assertEquals("", state.claudeModel)
        assertEquals("origin/main", state.defaultBaseRef)
        assertEquals(ReviewMode.WORKTREE.name, state.defaultMode)
        assertEquals(300, state.requestTimeoutSeconds)
        assertEquals(500_000, state.maxDiffSizeBytes)
        assertEquals(100_000, state.maxFileContentBytes)
        assertTrue(state.sendFileContent)
        assertEquals("", state.ghCliPath)
        assertEquals("", state.ghToken)
        assertEquals("", state.customReviewPrompt)
    }

    @Test
    fun `State fields are mutable`() {
        val state = ReviewSettings.State()
        state.claudeCliPath = "/usr/local/bin/claude"
        state.claudeModel = "opus"
        state.defaultBaseRef = "origin/develop"
        state.requestTimeoutSeconds = 60
        state.maxDiffSizeBytes = 100_000
        state.maxFileContentBytes = 50_000
        state.sendFileContent = false
        state.ghCliPath = "/usr/local/bin/gh"
        state.ghToken = "ghp_testtoken123"
        state.customReviewPrompt = "Focus on security"

        assertEquals("/usr/local/bin/claude", state.claudeCliPath)
        assertEquals("opus", state.claudeModel)
        assertEquals("origin/develop", state.defaultBaseRef)
        assertEquals(60, state.requestTimeoutSeconds)
        assertFalse(state.sendFileContent)
        assertEquals("ghp_testtoken123", state.ghToken)
        assertEquals("Focus on security", state.customReviewPrompt)
    }

    @Test
    fun `State is a data class with copy support`() {
        val state = ReviewSettings.State(claudeModel = "sonnet")
        val copy = state.copy(claudeModel = "opus")
        assertEquals("sonnet", state.claudeModel)
        assertEquals("opus", copy.claudeModel)
    }
}
