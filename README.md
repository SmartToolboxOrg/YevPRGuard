# AI Review - IntelliJ IDEA Plugin

A GitHub PR-style code review plugin for IntelliJ IDEA, powered by an external AI review service.

## Features

- **Git diff collection** - Compares working tree vs base branch, or an explicit commit range
- **External review service integration** - Sends diffs to a configurable HTTP endpoint
- **Findings tree** - Tool window listing findings grouped by file and severity
- **Inline annotations** - Gutter icons and editor highlights on lines with findings
- **Quick-fix suggestions** - Apply suggestion patches directly from the editor (Alt+Enter)
- **Caching** - Results cached by diff hash to avoid redundant requests
- **Background execution** - All network/git operations run off the EDT with progress and cancellation

## Prerequisites

- IntelliJ IDEA 2024.1+ (Community or Ultimate)
- JDK 17+
- Git available on PATH
- A running review service (see [Review Service Contract](#review-service-contract))

## Setup

### Build

```bash
./gradlew build
```

### Run in development (sandbox IDE)

```bash
./gradlew runIde
```

This launches a sandboxed IntelliJ instance with the plugin installed.

### Install from disk

1. Build the plugin: `./gradlew buildPlugin`
2. The distributable zip is at `build/distributions/ai-review-plugin-1.0.0.zip`
3. In IntelliJ: Settings > Plugins > gear icon > Install Plugin from Disk > select the zip

## Configuration

Open **Settings > Tools > AI Review** to configure:

| Setting | Default | Description |
|---------|---------|-------------|
| Service endpoint | `http://localhost:8080/review` | URL of the review service |
| Default base ref | `origin/main` | Git ref to diff against |
| Request timeout | 120s | HTTP request timeout |
| Max diff size | 500KB | Diffs larger than this are truncated |
| Max file content | 100KB | Per-file content limit |
| Max retries | 2 | Retry count for transient failures |
| Send file content | true | Include file content in requests |

## Usage

### From the tool window

1. Open the **AI Review** tool window (bottom panel)
2. Select mode: **Working Tree** or **Commit Range**
3. Set the base ref (e.g., `origin/main`, `develop`, a commit SHA)
4. Click **Run Review**
5. Double-click a finding to navigate to the file and line

### From the menu

- **Tools > AI Review > Run Review** - runs a full review with default settings
- Right-click in editor > **Run AI Review for This File** - reviews only the current file

### Applying suggestions

When a finding includes a suggestion patch, press **Alt+Enter** on the highlighted line and select **Apply AI suggestion**. If automatic application fails, a diff preview dialog opens for manual review.

## Review Service Contract

The plugin sends a POST request with this JSON body:

```json
{
  "projectName": "my-project",
  "baseRef": "origin/main",
  "headRef": "HEAD",
  "mode": "WORKTREE",
  "diff": "unified diff text...",
  "files": [
    {
      "path": "src/main/java/Foo.java",
      "content": "file content or null",
      "language": "JAVA"
    }
  ]
}
```

The service must return a JSON array of findings:

```json
[
  {
    "filePath": "src/main/java/Foo.java",
    "line": 42,
    "endLine": 44,
    "severity": "warning",
    "ruleId": "null-check",
    "message": "Potential null pointer dereference",
    "suggestion": "Add a null check before accessing .getName()",
    "suggestionPatch": "--- a/src/main/java/Foo.java\n+++ b/src/main/java/Foo.java\n@@ -41,3 +41,5 @@\n ...\n"
  }
]
```

### Finding fields

| Field | Required | Description |
|-------|----------|-------------|
| `filePath` | yes | Relative path from repo root |
| `line` | yes | 1-based line number (new file version) |
| `endLine` | no | End line for multi-line findings |
| `severity` | no | `info`, `warning`, or `error` (default: `info`) |
| `ruleId` | no | Optional rule identifier |
| `message` | yes | Human-readable finding description |
| `suggestion` | no | Short text suggestion |
| `suggestionPatch` | no | Unified diff patch for auto-apply |

## Project Structure

```
src/main/kotlin/com/aireview/
  model/          - Data classes (findings, requests)
  service/        - Git diff, HTTP client, findings manager, review runner
  settings/       - Persistent configuration
  ui/             - Tool window factory and panel
  annotator/      - Line markers and external annotator
  quickfix/       - Apply-suggestion quick fix
  action/         - Menu and context actions

src/main/resources/
  META-INF/plugin.xml   - Plugin descriptor
  icons/                - SVG icons
```

## Development

### Debug

1. Open this project in IntelliJ IDEA
2. Run the `runIde` Gradle task with debugger attached
3. In the sandbox IDE, open a git-tracked project and use the plugin

### Architecture notes

- **FindingsManager** is the central project-scoped service that holds current results. The tool window, annotators, and line markers all read from it.
- **ReviewRunner** orchestrates the flow: git diff -> cache check -> HTTP call -> store findings. Runs as a cancellable background task.
- **ExternalAnnotator** is used for highlights because findings come from an external source (not PSI analysis).
- **LineMarkerProvider** provides gutter icons, operating on PSI leaf elements.
- All network and git operations run off the EDT via `ProgressManager.run(Backgroundable)`.
