package com.aireview.settings

import com.aireview.model.ReviewMode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-level persistent settings for the AI Review plugin.
 * Stored in the IDE's configuration directory via PersistentStateComponent.
 */
@State(
    name = "com.aireview.settings.ReviewSettings",
    storages = [Storage("AiReviewSettings.xml")]
)
class ReviewSettings : PersistentStateComponent<ReviewSettings.State> {

    data class State(
        var claudeCliPath: String = "",
        var claudeModel: String = "",
        var defaultBaseRef: String = "origin/main",
        var defaultMode: String = ReviewMode.WORKTREE.name,
        var requestTimeoutSeconds: Int = 300,
        var maxDiffSizeBytes: Int = 500_000,
        var maxFileContentBytes: Int = 100_000,
        var sendFileContent: Boolean = true,
        var ghCliPath: String = "",
        var ghToken: String = "",
        var customReviewPrompt: String = ""
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): ReviewSettings =
            ApplicationManager.getApplication().getService(ReviewSettings::class.java)
    }
}
