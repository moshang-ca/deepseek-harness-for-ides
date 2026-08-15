# deepseek-harness-for-jetbrain-ides

![Build](https://github.com/moshang-ca/deepseek-harness-for-intellij/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/com.github.moshangca.dsh.svg)](https://plugins.jetbrains.com/plugin/com.github.moshangca.dsh)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/com.github.moshangca.dsh.svg)](https://plugins.jetbrains.com/plugin/com.github.moshangca.dsh)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)

An IntelliJ Platform plugin that integrates [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (dsh) into the IDE as a chat tool window. It launches a local dsh **web-profile** server and talks to its HTTP API.

## Features

- **Chat tool window** (`deepseek-harness-chat`, right side): streamed assistant replies with collapsible reasoning blocks, tool-call cards (collapsible, with arguments and results), approval prompts, and interactive `ask_user_question` dialogs.
- **Automatic dsh lifecycle**: the plugin installs `@deepseek-ai/dsh@next` into the JetBrains common-data directory (shared by all JetBrains IDEs, e.g. `%APPDATA%\JetBrains\deepseek-harness-for-intellij` on Windows) and starts the web server on demand. **One server per IDE** is shared by all open projects; each project gets its own session, and the server stops when the last project using it closes. If the base port is taken (e.g. by another IDE), the plugin scans forward for a free one.
- **Model selector** above the input box: populated from the server's model catalog, switches the current session live.
- **Sandbox mode selector**: switch between `workspace-write` and `danger-full-access` (restarts the server).
- **Status bar** with a live server-status dot, and a **New Session** button (top-right).
- **Settings** (Settings → Tools → DeepSeek Harness): provider, `DEEPSEEK_API_KEY`, and base port.

## How it works

The plugin does **not** use the Agent Client Protocol. It runs the dsh runtime with `node bin.js web --port N` and speaks the harness's web-profile API:

- **Unary RPC** over `POST /api/<method>` (`host.describe`, `session.create`, `session.prompt`, `session.cancel`, `session.history`, `session.models`, `session.selectModel`, `credentials.*`, ...) using `client-request` / `client-response` envelopes correlated by `rpcId`.
- **Event stream** over `/api/events.mux` (WebSocket) for streaming chunks (`text-delta`, `reasoning-delta`, `tool-call-delta`), tool calls/results, approvals (`approval/requested` / `approval/resolved`), and questions (`question/requested`), routed to the owning project by `sessionId`.

The API key is injected through the harness **credentials service** (`credentials.set`) after the server starts, so changes take effect without a server restart.

## Requirements

- **Node.js** (`node` + `npm`) on `PATH` — used to install and run the dsh runtime.
- IntelliJ IDEA **2025.2** or later.
- A DeepSeek API key (set in plugin settings, or exported as `DEEPSEEK_API_KEY`).

## Usage

1. Install the plugin.
2. Open Settings → Tools → DeepSeek Harness and set your API key (optionally the provider and base port).
3. Open the **DeepSeek Harness** tool window (bottom-right icon, or View → Tool Windows).
4. Pick a model from the selector above the input box.
5. Type a message and press **Enter** or the **Send** button.

The first message triggers an `npm install` of the dsh packages into the shared JetBrains directory (progress is shown in a modal dialog); later starts are near-instant.

## Installation

- **IDE plugin system**: Settings/Preferences → Plugins → Marketplace → search for "deepseek-harness-for-intellij" → Install.
- **Manually**: download the latest release and install from disk.

## Development

```sh
./gradlew runIde      # run the plugin in a sandboxed IDE
./gradlew buildPlugin # build the distributable zip
```

Pure Java 21 (IntelliJ Platform Gradle Plugin, target IDEA 2025.2.6.2). Layered as:

- `dsh/` — process lifecycle (npm install, web-server start with port scan, readiness probe)
- `api/` — HTTP RPC client and the mux WebSocket event stream
- `services/` — `DshServerManager` (the one-per-IDE server and session routing) and `DshProjectService` (per-project session)
- `ui/` — chat panel, message bubbles, question dialog

---

Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
