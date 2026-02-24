package com.aireview.service

import com.aireview.model.*
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Project-scoped service that holds the current set of review findings.
 *
 * Persists review sessions (AI findings + manual comments + selection states) to the
 * workspace file keyed by diff hash, so they are restored when the project reopens.
 *
 * Thread-safe: findings can be written from a background thread and read from EDT.
 *
 * Registered via plugin.xml <projectService> (NOT @Service annotation)
 * so that PersistentStateComponent works reliably.
 */
@State(
    name = "com.aireview.service.FindingsManager",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
class FindingsManager(private val project: Project) : PersistentStateComponent<FindingsManager.PersistedState> {

    private val log = Logger.getInstance(FindingsManager::class.java)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        private const val MAX_CACHE_SIZE = 20
        private const val MAX_PERSISTED_SESSIONS = 10

        fun getInstance(project: Project): FindingsManager =
            project.getService(FindingsManager::class.java)
    }

    /**
     * State class for IntelliJ XML persistence.
     * Must be a plain class (not data class) with no-arg constructor and mutable public fields
     * for IntelliJ's XmlSerializer to work correctly.
     */
    class PersistedState {
        var sessions: MutableMap<String, String> = mutableMapOf()
        var lastDiffHash: String = ""
    }

    private var persistedState = PersistedState()

    override fun getState(): PersistedState {
        saveCurrentSessionToState()
        return persistedState
    }

    override fun loadState(state: PersistedState) {
        persistedState = state
        log.info("loadState called: lastDiffHash=${state.lastDiffHash.take(8)}, sessions=${state.sessions.size}")
        if (state.lastDiffHash.isNotBlank()) {
            loadSessionIntoMemory(state.lastDiffHash)
        }
    }

    /**
     * Bounded LRU cache keyed by diff hash.
     */
    private val cache: LinkedHashMap<String, ReviewResult> = object : LinkedHashMap<String, ReviewResult>(
        MAX_CACHE_SIZE + 1, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReviewResult>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    @Volatile
    var currentResult: ReviewResult? = null
        private set

    @Volatile
    var currentDiffHash: String? = null
        private set

    private val selectableFindings = CopyOnWriteArrayList<SelectableFinding>()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Add a listener. If findings are already loaded (e.g. restored from persistence),
     * the listener is called immediately so the UI can populate.
     */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
        // If findings already exist (restored from disk before UI was ready), notify immediately
        if (selectableFindings.isNotEmpty()) {
            try {
                listener()
            } catch (e: Exception) {
                log.error("Error in initial findings listener callback", e)
            }
        }
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            try {
                listener()
            } catch (e: Exception) {
                log.error("Error in findings listener", e)
            }
        }
    }

    /**
     * Returns cached result if the diff hash matches.
     * Checks in-memory cache first, then persisted sessions on disk.
     */
    fun getCachedResult(diffHash: String): ReviewResult? {
        synchronized(cache) {
            cache[diffHash]?.let { return it }
        }
        // Try restoring from persisted disk state
        val session = deserializeSession(diffHash) ?: return null
        val result = ReviewResult(
            findings = session.findings.filter { it.source == FindingSource.AI.name }.map { it.finding },
            diffHash = session.diffHash,
            baseRef = session.baseRef,
            headRef = session.headRef,
            timestamp = session.timestamp
        )
        loadSessionIntoMemory(diffHash)
        synchronized(cache) {
            cache[diffHash] = result
        }
        return result
    }

    /**
     * Store new findings and notify listeners.
     * Merges with any persisted manual comments and selection states for this diff hash.
     */
    fun setFindings(result: ReviewResult) {
        saveCurrentSessionToState()

        currentResult = result
        currentDiffHash = result.diffHash
        synchronized(cache) {
            cache[result.diffHash] = result
        }

        // Check if we have a persisted session (to restore manual comments + selection)
        val restored = deserializeSession(result.diffHash)
        if (restored != null) {
            val restoredManuals = restored.findings
                .filter { it.source == FindingSource.MANUAL.name }
                .map { SelectableFinding(it.finding, FindingSource.MANUAL, it.selected, it.id) }

            selectableFindings.clear()
            selectableFindings.addAll(result.findings.map { finding ->
                val matchedPf = restored.findings.find { pf ->
                    pf.source == FindingSource.AI.name &&
                    pf.finding.filePath == finding.filePath &&
                    pf.finding.line == finding.line
                }
                val selected = matchedPf?.selected ?: true
                SelectableFinding(finding, FindingSource.AI, selected)
            })
            selectableFindings.addAll(restoredManuals)
        } else {
            val manualComments = selectableFindings.filter { it.source == FindingSource.MANUAL }
            selectableFindings.clear()
            selectableFindings.addAll(result.findings.map { SelectableFinding(it, FindingSource.AI) })
            selectableFindings.addAll(manualComments)
        }

        persistedState.lastDiffHash = result.diffHash
        saveCurrentSessionToState()

        log.info("Stored ${result.findings.size} findings (hash=${result.diffHash.take(8)})")
        notifyListeners()
    }

    fun clear() {
        currentResult = null
        currentDiffHash = null
        selectableFindings.clear()
        persistedState.lastDiffHash = ""
        notifyListeners()
    }

    fun getFindingsForFile(relativePath: String): List<ReviewFinding> {
        return selectableFindings
            .filter { normalizeSlashes(it.finding.filePath) == normalizeSlashes(relativePath) }
            .map { it.finding }
    }

    fun getFindingsByFile(): Map<String, List<ReviewFinding>> {
        return selectableFindings
            .map { it.finding }
            .groupBy { normalizeSlashes(it.filePath) }
    }

    fun addManualComment(filePath: String, line: Int, message: String) {
        val finding = ReviewFinding(
            filePath = filePath,
            line = line,
            severity = "info",
            ruleId = "manual",
            message = message
        )
        selectableFindings.add(SelectableFinding(finding, FindingSource.MANUAL))
        saveCurrentSessionToState()
        log.info("Added manual comment on $filePath:$line")
        notifyListeners()
    }

    fun removeManualComment(id: String) {
        val removed = selectableFindings.removeIf { it.id == id && it.source == FindingSource.MANUAL }
        if (removed) {
            saveCurrentSessionToState()
            log.info("Removed manual comment $id")
            notifyListeners()
        }
    }

    /**
     * Update the message of any finding (AI or manual) by its ID.
     */
    fun updateFindingMessage(id: String, newMessage: String) {
        selectableFindings.find { it.id == id }?.let { sf ->
            sf.finding = sf.finding.copy(message = newMessage)
            saveCurrentSessionToState()
            log.info("Updated finding message for $id")
            notifyListeners()
        }
    }

    fun toggleSelection(id: String) {
        selectableFindings.find { it.id == id }?.let {
            it.selected = !it.selected
            notifyListeners()
        }
    }

    fun selectAll() {
        selectableFindings.forEach { it.selected = true }
        notifyListeners()
    }

    fun deselectAll() {
        selectableFindings.forEach { it.selected = false }
        notifyListeners()
    }

    fun getSelectableFindings(): List<SelectableFinding> = selectableFindings.toList()

    fun getSelectableFindingsForFile(relativePath: String): List<SelectableFinding> {
        return selectableFindings.filter {
            normalizeSlashes(it.finding.filePath) == normalizeSlashes(relativePath)
        }
    }

    fun getSelectedFindings(): List<SelectableFinding> {
        return selectableFindings.filter { it.selected }
    }

    // --- Persistence helpers ---

    private fun saveCurrentSessionToState() {
        val diffHash = currentDiffHash ?: return
        if (selectableFindings.isEmpty()) return

        val result = currentResult
        val session = PersistedSession(
            diffHash = diffHash,
            baseRef = result?.baseRef ?: "",
            headRef = result?.headRef ?: "",
            timestamp = result?.timestamp ?: System.currentTimeMillis(),
            findings = selectableFindings.map { sf ->
                PersistedFinding(
                    finding = sf.finding,
                    source = sf.source.name,
                    selected = sf.selected,
                    id = sf.id
                )
            }
        )

        try {
            persistedState.sessions[diffHash] = json.encodeToString(session)
            // Evict oldest sessions if over limit
            while (persistedState.sessions.size > MAX_PERSISTED_SESSIONS) {
                val oldest = persistedState.sessions.keys.firstOrNull() ?: break
                if (oldest != diffHash) {
                    persistedState.sessions.remove(oldest)
                } else break
            }
        } catch (e: Exception) {
            log.warn("Failed to persist session: ${e.message}")
        }
    }

    private fun deserializeSession(diffHash: String): PersistedSession? {
        val sessionJson = persistedState.sessions[diffHash] ?: return null
        return try {
            json.decodeFromString<PersistedSession>(sessionJson)
        } catch (e: Exception) {
            log.warn("Failed to deserialize session for $diffHash: ${e.message}")
            persistedState.sessions.remove(diffHash)
            null
        }
    }

    private fun loadSessionIntoMemory(diffHash: String) {
        val session = deserializeSession(diffHash) ?: return

        currentDiffHash = session.diffHash
        currentResult = ReviewResult(
            findings = session.findings.filter { it.source == FindingSource.AI.name }.map { it.finding },
            diffHash = session.diffHash,
            baseRef = session.baseRef,
            headRef = session.headRef,
            timestamp = session.timestamp
        )

        selectableFindings.clear()
        selectableFindings.addAll(session.findings.map { pf ->
            SelectableFinding(
                finding = pf.finding,
                source = FindingSource.valueOf(pf.source),
                selected = pf.selected,
                id = pf.id
            )
        })

        synchronized(cache) {
            cache[session.diffHash] = currentResult!!
        }

        log.info("Restored ${selectableFindings.size} findings from disk (hash=${diffHash.take(8)})")
    }

    private fun normalizeSlashes(path: String): String = path.replace('\\', '/')
}
