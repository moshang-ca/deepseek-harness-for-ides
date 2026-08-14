# deepseek-harness-for-intellij

![Build](https://github.com/moshang-ca/deepseek-harness-for-intellij/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

An IntelliJ Platform plugin that integrates [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) (dsh) into the IDE through the **Agent Client Protocol (ACP)** — the same integration approach used by IntelliJ AI Assistant.

## Features

- **Chat tool window** (`deepseek-harness-chat`, right side): send messages and stream assistant replies.
- **Automatic dsh lifecycle**: the plugin installs and starts a dsh ACP server on demand (`@deepseek-ai/dsh-acp-demo`) and stops it when the project closes.
- **Settings** (Settings → Tools → DeepSeek Harness): configure provider, model, and `DEEPSEEK_API_KEY`.

## Requirements

- **Node.js** (`node` + `npm`) on `PATH` — used to install and run the dsh ACP server.
- IntelliJ IDEA **2025.2** or later.
- A DeepSeek API key (set in the plugin settings, or exported as `DEEPSEEK_API_KEY`).

## Usage

1. Install the plugin.
2. Open Settings → Tools → DeepSeek Harness and set your provider/model (defaults: `deepseek-official` / `deepseek-v4-pro`) and API key.
3. Open the **DeepSeek Harness** tool window (bottom-right icon, or View → Tool Windows).
4. Type a message and press **Send**. Assistant replies stream in.
5. **New Session** starts a fresh conversation.

The first send triggers an `npm install` of the dsh packages into a temp directory, which can take a minute on a cold start.

## Installation

- **IDE plugin system**: Settings/Preferences → Plugins → Marketplace → search for "deepseek-harness-for-intellij" → Install.
- **Manually**: download the latest release and install from disk.

## Development

```sh
./gradlew runIde      # run the plugin in a sandboxed IDE
./gradlew test        # run unit tests (ACP JSON-RPC layer)
./gradlew buildPlugin # build the distributable zip
```

The build is pure Java (Java 21 toolchain). The ACP client in `acp/` is unit-tested against a fake server over piped streams; `dsh/` manages the dsh process.

---

Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
