# AI Review - IntelliJ IDEA Plugin

A GitHub PR-style code review plugin for JetBrains IDEs, powered by [Claude Code CLI](https://docs.anthropic.com/en/docs/claude-code/overview).

## Features

- **AI-powered code review** — sends git diffs to Claude for analysis via Claude Code CLI
- **Inline annotations** — findings appear as gutter icons, editor highlights, and tooltips
- **Quick-fix suggestions** — apply AI-suggested patches directly from the editor (Alt+Enter)
- **Findings tree** — tool window listing findings grouped by file and severity
- **Manual review comments** — add your own comments on any line
- **Publish to GitHub** — push selected findings as PR review comments via [GitHub CLI](https://cli.github.com/)
- **Generate PR descriptions** — auto-generate a PR title and body from your diff
- **Caching** — results cached by diff hash to avoid redundant requests
- **Background execution** — all operations run off the EDT with progress and cancellation

## Prerequisites

- IntelliJ IDEA 2024.1+ (Community or Ultimate, or any compatible JetBrains IDE)
- JDK 17+
- Git available on PATH
- [Claude Code CLI](https://docs.anthropic.com/en/docs/claude-code/overview) — installed and authenticated (`claude login`)
- [GitHub CLI (gh)](https://cli.github.com/) — installed and authenticated (`gh auth login`) — required for PR publishing

## Setup

### Install from JetBrains Marketplace

1. In your IDE: **Settings → Plugins → Marketplace** → search for **AI Review**
2. Click **Install** and restart the IDE

### Install from disk

1. Download the latest release from [Releases](https://github.com/SmartToolboxOrg/YevPRGuard/releases)
2. In your IDE: **Settings → Plugins → gear icon → Install Plugin from Disk** → select the zip

### Build from source

```bash
./gradlew buildPlugin
```

The distributable zip will be at `build/distributions/ai-review-plugin-1.0.0.zip`.

### Run in development (sandbox IDE)

```bash
./gradlew runIde
```

## Configuration

Open **Settings → Tools → AI Review** to configure:

| Setting          | Description                                                    |
|------------------|----------------------------------------------------------------|
| Claude CLI path  | Path to `claude` binary (auto-detected from common locations)  |
| Claude model     | Model to use for reviews (optional)                            |
| GitHub CLI path  | Path to `gh` binary (auto-detected from common locations)      |
| GitHub token     | `GH_TOKEN` for authentication (alternative to `gh auth login`) |
| Default base ref | Git ref to diff against (default: `origin/main`)               |
| Request timeout  | Claude CLI timeout in seconds (default: 300)                   |

## Usage

### Run a review

- **Tools → AI Review → Run Review** — review your full diff against the base branch
- Right-click in editor → **Run AI Review for This File** — review only the current file
- Or use the **AI Review** tool window (bottom panel) to select mode and base ref

### Apply suggestions

When a finding includes a suggestion patch, press **Alt+Enter** on the highlighted line and select **Apply AI suggestion**.

### Publish to GitHub

1. Run a review on a branch that has an open PR
2. Select findings in the tool window
3. **Tools → AI Review → Publish to GitHub PR**

### Generate PR description

**Tools → AI Review → Generate PR Description** — generates a title and body from your diff using Claude.

## Project Structure

```
src/main/kotlin/com/aireview/
  model/          - Data classes (findings, requests)
  service/        - Claude Code CLI, GitHub CLI, Git diff, findings manager
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

1. Open this project in IntelliJ IDEA
2. Run the `runIde` Gradle task with debugger attached
3. In the sandbox IDE, open a git-tracked project and use the plugin

## License

[Apache License 2.0](LICENSE)
