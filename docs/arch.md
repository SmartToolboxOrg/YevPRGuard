# Architecture

## Overview

AI Review is an IntelliJ IDEA plugin that provides AI-powered code review using the Claude CLI. It analyzes git diffs, sends them to Claude for review, and displays findings as inline editor annotations with the ability to manage, edit, and publish comments to GitHub PRs.

## High-Level Architecture

```mermaid
graph TB
    subgraph IntelliJ IDE
        A[Actions<br/>RunReview, AddManualComment, PublishToGitHub]
        B[Tool Window<br/>ReviewToolWindowPanel]
        C[Annotators<br/>ExternalAnnotator, LineMarkerProviders]
        D[Settings<br/>ReviewSettingsConfigurable]
    end

    subgraph Services
        E[FindingsManager<br/>State + Persistence]
        F[ReviewRunner<br/>Orchestration]
        G[ClaudeCodeService<br/>CLI Integration]
        H[GitDiffService<br/>Git Operations]
        I[GitHubService<br/>gh CLI Integration]
    end

    subgraph External
        J[Claude CLI]
        K[Git]
        L[GitHub CLI - gh]
    end

    A --> F
    A --> E
    A --> I
    B --> E
    C --> E
    D --> E
    F --> G
    F --> H
    F --> E
    G --> J
    H --> K
    I --> L
```

## Component Details

### Actions Layer

```mermaid
graph LR
    subgraph User Triggers
        M1[Tools Menu]
        M2[Editor Context Menu]
        M3[Gutter Context Menu]
        M4[Tool Window Buttons]
    end

    subgraph Actions
        A1[RunReviewAction]
        A2[RunReviewCurrentFileAction]
        A3[AddManualCommentAction]
        A4[PublishToGitHubAction]
    end

    M1 --> A1
    M1 --> A2
    M2 --> A3
    M3 --> A3
    M4 --> A4
```

### Data Flow

```mermaid
sequenceDiagram
    participant User
    participant Action
    participant ReviewRunner
    participant GitDiffService
    participant ClaudeCodeService
    participant FindingsManager
    participant ToolWindow
    participant Annotators

    User->>Action: Trigger Review
    Action->>ReviewRunner: run(baseRef, mode)
    ReviewRunner->>GitDiffService: getDiff()
    GitDiffService-->>ReviewRunner: diff + files
    ReviewRunner->>FindingsManager: getCachedResult(hash)
    alt Cache Miss
        ReviewRunner->>ClaudeCodeService: review(request)
        ClaudeCodeService-->>ReviewRunner: List<ReviewFinding>
    end
    ReviewRunner->>FindingsManager: setFindings(result)
    FindingsManager->>ToolWindow: notify listeners
    FindingsManager->>Annotators: notify listeners
    ToolWindow-->>User: Show findings tree
    Annotators-->>User: Show inline annotations
```

### Persistence

```mermaid
graph TB
    subgraph Runtime
        SF[SelectableFinding<br/>in-memory list]
        CR[currentResult]
        CH[LRU Cache<br/>max 20 entries]
    end

    subgraph Disk - workspace.xml
        PS[PersistedState]
        SS[sessions Map<br/>diffHash -> JSON]
        LH[lastDiffHash]
    end

    SF -->|saveCurrentSession| PS
    PS -->|loadState| SF
    CR --> CH
    SS --> PS
    LH --> PS
```

FindingsManager implements `PersistentStateComponent<PersistedState>` and stores sessions in the workspace file. Each session is serialized as JSON keyed by diff hash. On project reopen, the last session is automatically restored.

### Review Modes

| Mode | Description | Base Ref |
|------|-------------|----------|
| WORKTREE | Uncommitted changes vs base | Configurable (default: `origin/main`) |
| RANGE | Commit range diff | User-specified refs |

### GitHub Publishing

```mermaid
sequenceDiagram
    participant User
    participant ToolWindow
    participant PublishAction
    participant GitHubService
    participant gh CLI

    User->>ToolWindow: Select findings + click Publish
    ToolWindow->>PublishAction: publishFindings(selected)
    PublishAction->>GitHubService: detectCurrentPr()
    GitHubService->>gh CLI: gh pr view --json
    gh CLI-->>GitHubService: PrInfo
    PublishAction->>User: Confirm dialog
    User->>PublishAction: Yes
    PublishAction->>GitHubService: publishReview(pr, findings)
    GitHubService->>gh CLI: gh api repos/.../reviews POST
    gh CLI-->>GitHubService: Success
    GitHubService-->>User: Notification
```

## Key Design Decisions

1. **No `@Service` annotation on FindingsManager** - Registered via plugin.xml only to avoid dual-instance issue with PersistentStateComponent
2. **Plain class for PersistedState** - IntelliJ's XML serializer requires JavaBean-style classes, not Kotlin data classes
3. **CopyOnWriteArrayList for findings** - Thread-safe reads from EDT, writes from background threads
4. **kotlinx.serialization for JSON** - Both for Claude CLI response parsing and GitHub API payload construction (prevents injection)
5. **Bounded LRU cache** - Max 20 in-memory results, max 10 persisted sessions to limit workspace file growth
6. **Immediate listener callback** - `addListener()` fires immediately if findings exist, solving the timing issue where loadState runs before UI initialization

## Package Structure

```
com.aireview/
  action/         # AnAction implementations (menu items, shortcuts)
  annotator/      # ExternalAnnotator + LineMarkerProviders (editor integration)
  model/          # Data classes: ReviewFinding, SelectableFinding, etc.
  quickfix/       # IntentionAction for applying suggestions
  service/        # Business logic: FindingsManager, ReviewRunner, ClaudeCodeService, etc.
  settings/       # Plugin settings (PersistentStateComponent)
  ui/             # Tool window panel, dialogs
  util/           # PathUtil for safe path resolution
```
