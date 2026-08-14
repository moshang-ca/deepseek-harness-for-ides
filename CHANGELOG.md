<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# deepseek-harness-for-intellij Changelog

## [Unreleased]
### Added
- AI-Assistant-style chat tool window (DeepSeek Harness): bubble messages, streaming assistant replies, reasoning/thinking shown in gray italic, tool-call cards, and inline permission cards with Allow/Deny buttons.
- Migration from the ACP (stdio) transport to the dsh **web API** (HTTP RPC + WebSocket mux event stream), enabling full reasoning/tool-call process visibility.
- Automatic dsh web server lifecycle management (npm-installed `@deepseek-ai/dsh`, started on demand, stopped on project close).
- Sandbox mode dropdown in the chat input area with live switching (restarts the dsh server).
- Paper-plane send button embedded in the input area.
- Settings page (Tools → DeepSeek Harness) with compact GridBagLayout: provider, DEEPSEEK_API_KEY, and port.
- Pure Java implementation; Kotlin template code removed.
- Dynamic port scanning to avoid port conflicts when the default port is already in use.
- IDE-wide single dsh server instance shared across all projects.
- Session-level model catalog query and live model switching (`session.selectModel`).
- Question interaction support (`question/requested`) with user dialog for answering multiple-choice questions.
- Tool call streaming rendering: argument deltas, result display, running state, and error indicators.

### Changed
- Replaced ACP transport with web API (`@deepseek-ai/dsh` web profile, default port 3080).
- Windows defaults to `danger-full-access` sandbox mode (Git Bash crashes under `workspace-write`'s restricted token).
- dsh child process inherits Git Bash directories prepended to PATH on Windows.
- Refactored from per-project server to singleton IDE-wide server architecture.
- Removed model and sandboxMode fields from settings (model now per-session via dropdown; sandbox mode still configurable via dropdown in UI).
- Enhanced chat UI: status indicator dot, input placeholder and focus border, model selection dropdown, and refined message bubble styling.


### TODO
- [ ] Add token consumption visualization for each conversation turn.
- [ ] Improve UI/UX polish: refine message bubbles, input area, and overall visual consistency.