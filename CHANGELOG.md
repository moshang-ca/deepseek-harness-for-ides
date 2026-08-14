<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# deepseek-harness-for-intellij Changelog

## [Unreleased]
### Added
- AI-Assistant-style chat tool window (DeepSeek Harness): bubble messages, streaming assistant replies, reasoning/thinking shown in gray italic, tool-call cards, and inline permission cards with Allow/Deny buttons.
- Migration from the ACP (stdio) transport to the dsh **web API** (HTTP RPC + WebSocket mux event stream), enabling full reasoning/tool-call process visibility.
- Automatic dsh web server lifecycle management (npm-installed `@deepseek-ai/dsh`, started on demand, stopped on project close).
- Sandbox mode dropdown in the chat input area with live switching (restarts the dsh server).
- Paper-plane send button embedded in the input area.
- Settings page (Tools -> DeepSeek Harness) with compact GridBagLayout: provider, model, DEEPSEEK_API_KEY, port, and sandbox mode.
- Pure Java implementation; Kotlin template code removed.

### Changed
- Replaced ACP transport with web API (`@deepseek-ai/dsh` web profile, default port 3080).
- Windows defaults to `danger-full-access` sandbox mode (Git Bash crashes under `workspace-write`'s restricted token).
- dsh child process inherits Git Bash directories prepended to PATH on Windows.
