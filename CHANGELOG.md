<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# deepseek-harness-for-intellij Changelog

## [0.0.2] - 2026-08-16

### Added
- Right-click on the session history dropdown to rename the current session.
- Markdown rendering for assistant messages (code blocks, lists, etc.).
- Plugin icon.
- Session rename API integration (`session.rename`).

### Changed
- Server startup now runs as a background task (no longer blocks the UI).
- Input focus border uses a fixed blue color instead of the theme's "Focus.color"
  (which is red in some dark themes).
- History dropdown refresh preserves the active session selection.
- History rebuild skips system-prompt snapshots (runtime context injected by dsh).

### Fixed
- Assistant message text no longer truncated to a single line.
- Approval cards are removed from the transcript once resolved.
- Reasoning text now selectable/copyable.