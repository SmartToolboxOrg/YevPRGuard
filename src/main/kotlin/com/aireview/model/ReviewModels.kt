package com.aireview.model

import kotlinx.serialization.Serializable

/**
 * Mode of diff collection.
 */
enum class ReviewMode {
    WORKTREE,
    RANGE
}

/**
 * Severity levels matching the review service contract.
 */
enum class Severity {
    INFO, WARNING, ERROR;

    companion object {
        fun fromString(s: String): Severity = when (s.lowercase()) {
            "info" -> INFO
            "warning" -> WARNING
            "error" -> ERROR
            else -> INFO
        }
    }
}

/**
 * A single finding returned by the review service.
 */
@Serializable
data class ReviewFinding(
    val filePath: String,
    val line: Int? = null,
    val endLine: Int? = null,
    val severity: String = "info",
    val ruleId: String? = null,
    val message: String,
    val suggestion: String? = null,
    val suggestionPatch: String? = null
) {
    val severityEnum: Severity get() = Severity.fromString(severity)
    val lineRange: IntRange get() = (line ?: 0)..(endLine ?: line ?: 0)
}

/**
 * A file included in the review request payload.
 */
@Serializable
data class ReviewFileInfo(
    val path: String,
    val content: String? = null,
    val language: String? = null
)

/**
 * The request body sent to the review service.
 */
@Serializable
data class ReviewRequest(
    val projectName: String,
    val baseRef: String,
    val headRef: String,
    val mode: String,
    val diff: String,
    val files: List<ReviewFileInfo>
)

/**
 * Wrapper for the full set of results from one review run.
 */
data class ReviewResult(
    val findings: List<ReviewFinding>,
    val diffHash: String,
    val baseRef: String,
    val headRef: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Source of a finding: AI-generated or manually added by the user.
 */
enum class FindingSource { AI, MANUAL }

/**
 * In-memory wrapper that adds selection state and source tracking to a finding.
 */
data class SelectableFinding(
    var finding: ReviewFinding,
    val source: FindingSource = FindingSource.AI,
    var selected: Boolean = true,
    val id: String = java.util.UUID.randomUUID().toString()
)

/**
 * Serializable version of a SelectableFinding for disk persistence.
 */
@Serializable
data class PersistedFinding(
    val finding: ReviewFinding,
    val source: String,
    val selected: Boolean,
    val id: String
)

/**
 * Serializable review session stored on disk, keyed by diff hash.
 */
@Serializable
data class PersistedSession(
    val diffHash: String,
    val baseRef: String,
    val headRef: String,
    val timestamp: Long,
    val findings: List<PersistedFinding>
)
