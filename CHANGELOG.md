<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# deepseek-harness-for-intellij Changelog

## [Unreleased]

### Added
- Session history dropdown: switch between past sessions from the server.
- Per-message token usage display (uncached input / output / cache read with hit ratio) in assistant bubbles.
- Reasoning effort selector for models that support it (e.g. high / max).
- Cancel button (red square) on the send button to interrupt an active turn.
- `read-only` sandbox mode as the new default (safest; workspace-write / danger-full-access are opt-in).
- Session title auto-updates and appears in the history dropdown.
- Cumulative token usage shown in the status bar (`tokens: X.Xk`).
- Session list refresh on new session creation and title changes.

### Changed
- Sandbox mode selection now persists and restarts the server immediately.
- Improved layout: model selector and effort dropdown share the input row.
- Windows no longer defaults to `danger-full-access`; `read-only` is the safe default.

### TODO
- [x] Add token consumption visualization for each conversation turn.
- [ ] Improve UI/UX polish: refine message bubbles, input area, and overall visual consistency.