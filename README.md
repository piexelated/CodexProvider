<div align="center">

# Codex Provider

**Use your ChatGPT account in JetBrains AI Assistant.**<br>
Sign in, choose a Codex model, and keep working in your IDE.

[![IDE compatibility](https://github.com/piexelated/CodexProvider/actions/workflows/compatibility.yml/badge.svg)](https://github.com/piexelated/CodexProvider/actions/workflows/compatibility.yml)
[![Release](https://img.shields.io/github/v/release/piexelated/CodexProvider?include_prereleases&label=release)](https://github.com/piexelated/CodexProvider/releases)
![IntelliJ IDEA 2025.3+](https://img.shields.io/badge/IntelliJ%20IDEA-2025.3%2B-000000?logo=intellijidea)
![AI Assistant](https://img.shields.io/badge/JetBrains-AI%20Assistant-7b61ff)

</div>

Codex Provider connects JetBrains AI Assistant to your ChatGPT account through the official Codex runtime. Pick models
for chat and supported text actions, with optional per-feature overrides. The runtime downloads automatically on first
use; no Node.js or separate server is needed.

## Requirements

- IntelliJ IDEA with the AI Assistant plugin, in one of the [tested pairs](#tested-intellij-idea-versions). The
  default build targets IDEA 2026.2.2 with AI Assistant `262.10315.187`.
- A ChatGPT account with Codex access

## Install

1. Download the ZIP for your exact IDE and AI Assistant pair from the
   [latest release](https://github.com/piexelated/CodexProvider/releases/latest), or
   [build the plugin](docs/development.md#build-and-verify). Each archive name includes both versions; the release
   notes list the tested pairs.
2. Open **Settings → Plugins → ⚙ → Install Plugin from Disk**, select the ZIP and restart if prompted.

## Use

Open **Settings → Tools → AI Assistant → Providers & API keys** and select **Codex Provider**.

1. Click **Sign In with ChatGPT** and finish signing in in the browser.
2. Under **Model Assignment**, pick Codex models for **Core features** and **Instant helpers**.
3. Apply, then start a conversation in AI Assistant.

| Setting | What it does |
|---|---|
| **Core features** | default model for chat, code generation, tests, reviews and other features |
| **Instant helpers** | default model for chat helpers: titles, classification and context summaries |
| **Feature models → Add Override…** | use a different Codex model for one feature, such as rename suggestions or merge conflicts |
| **Diagnostics → Log model responses to idea.log** | log full model responses; off by default |

Response logging stays on until you turn it off. Responses may contain project code or identifiers, so review the log
before sharing it. Normal logs leave out response bodies and raw protocol errors.

## Compatibility

Each IDE and AI Assistant pair gets its own plugin ZIP; other combinations are untested. Run
`scripts/compatibility-test.sh 2026.2.2 262.10315.187` to check a pair yourself in Docker. See
[development and compatibility checks](docs/development.md) for the test coverage and CI matrix.

### Tested IntelliJ IDEA versions

Historical checks at the listed test commits, not validation of the current source. Release notes list the exact pairs
re-tested for that release.

<!-- compatibility-table:start -->
| IntelliJ IDEA | AI Assistant | Verified | Test suite commit |
| --- | --- | --- | --- |
| 2026.2.3 | `262.10968.238` | 2026-10-23 | `e33f04a5af52` |
| 2026.2.2 | `262.10315.187` | 2026-09-25 | Unknown |
| 2026.1.5 | `261.27258.96` | 2026-10-14 | `3981d3cc03bb` |
| 2025.3.6.1 | `253.33813.55` | 2026-09-26 | Unknown |
| 2025.3.5 | `253.33514.22` | 2026-09-26 | Unknown |
| 2025.3.4.1 | `253.32098.101` | 2026-09-25 | Unknown |
<!-- compatibility-table:end -->

### Tested Codex CLI versions

Real app-server protocol checks at the listed test commits, using an isolated signed-out profile with no model
inference.

<!-- codex-cli-table:start -->
| Codex CLI | IntelliJ IDEA | AI Assistant | Verified | Test suite commit |
| --- | --- | --- | --- | --- |
| `0.166.0` | 2026.2.2 | `262.10315.187` | 2026-10-30 | `60d7f6ea0fe6` |
| `0.165.0` | 2026.2.2 | `262.10315.187` | 2026-10-28 | `396720cd9521` |
| `0.164.0` | 2026.2.2 | `262.10315.187` | 2026-10-23 | `e33f04a5af52` |
| `0.163.0` | 2026.2.2 | `262.10315.187` | 2026-10-16 | `8af23c38d1d8` |
| `0.162.1` | 2026.2.2 | `262.10315.187` | 2026-10-14 | `3981d3cc03bb` |
| `0.162.0` | 2026.2.2 | `262.10315.187` | 2026-10-14 | `3981d3cc03bb` |
| `0.161.0` | 2026.2.2 | `262.10315.187` | 2026-10-15 | `ebfeede78bf3` |
| `0.160.1` | 2026.2.2 | `262.10315.187` | 2026-10-15 | `ebfeede78bf3` |
| `0.157.1` | 2026.2.2 | `262.10315.187` | 2026-09-26 | Unknown |
| `0.157.0` | 2026.2.2 | `262.10315.187` | 2026-09-26 | Unknown |
<!-- codex-cli-table:end -->
