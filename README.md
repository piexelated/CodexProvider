<h1 align="center">Codex Provider</h1>

<p align="center">
  Use your ChatGPT account in JetBrains AI Assistant.<br>
  Sign in, choose a Codex model, and keep working in your IDE.
</p>

Codex Provider connects JetBrains AI Assistant to your ChatGPT account through
the official Codex runtime. Choose models for chat and supported text actions,
with optional per-feature overrides. The runtime downloads automatically on
first use; no Node.js or separate server is needed.

## Quick start

You need IntelliJ IDEA with AI Assistant and a ChatGPT account with Codex access.
The default build targets IDEA 2026.2.2 and AI Assistant `262.10315.187`.

1. Download the ZIP for your exact IDE and AI Assistant pair from the
   [latest release](https://github.com/piexelated/CodexProvider/releases/latest),
   or [build the plugin](docs/development.md#build-and-verify). Release archives
   include both versions in their filenames; the release notes list tested pairs.
2. Open **Settings → Plugins → gear → Install Plugin from Disk**, select the ZIP,
   and restart if prompted.
3. Open **Settings → Tools → AI Assistant → Providers & API keys**, select
   **Codex Provider**, then **Sign In with ChatGPT**.
4. Complete browser sign-in, then assign models to **Core** and **Instant**.
   To use a different model for a single feature, open **Feature Models**.
   Apply the settings and start a conversation in AI Assistant.

## Diagnostics

Turn on **Diagnostics → Log model responses to idea.log** in the Codex provider
settings to log model responses. It is off by default and persists until you
turn it off. Responses
may contain project code or identifiers; review the log before sharing it.
Normal operational logs omit response bodies and raw protocol errors.

## Compatibility testing

Run `scripts/compatibility-test.sh 2026.2.2 262.10315.187` to check an exact
IDE and AI Assistant version pair in Docker. See [development and compatibility
checks](docs/development.md) for the test coverage and CI matrix.

## Tested IntelliJ IDEA versions

These are historical checks at the listed test commits, not validation of the current source. Each pair builds its own plugin ZIP; other combinations are untested. Release notes list the exact pairs re-tested for that release.

<!-- compatibility-table:start -->
| IntelliJ IDEA | AI Assistant | Verified | Test suite commit |
| --- | --- | --- | --- |
| 2026.2.3 | `262.10968.147` | 2026-09-26 | Unknown |
| 2026.2.2 | `262.10315.187` | 2026-09-25 | Unknown |
| 2026.1.5 | `261.27258.89` | 2026-09-26 | Unknown |
| 2025.3.6.1 | `253.33813.55` | 2026-09-26 | Unknown |
| 2025.3.5 | `253.33514.22` | 2026-09-26 | Unknown |
| 2025.3.4.1 | `253.32098.101` | 2026-09-25 | Unknown |
<!-- compatibility-table:end -->

## Tested Codex CLI versions

These are historical real app-server protocol checks at the listed test commits. The check uses an isolated signed-out profile and performs no model inference.

<!-- codex-cli-table:start -->
| Codex CLI | IntelliJ IDEA | AI Assistant | Verified | Test suite commit |
| --- | --- | --- | --- | --- |
| `0.157.1` | 2026.2.2 | `262.10315.187` | 2026-09-26 | Unknown |
| `0.157.0` | 2026.2.2 | `262.10315.187` | 2026-09-26 | Unknown |
<!-- codex-cli-table:end -->
