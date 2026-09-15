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

1. [Build the plugin](docs/development.md#build-and-verify) and find the ZIP in
   `build/distributions/`.
2. Open **Settings → Plugins → gear → Install Plugin from Disk**, select the ZIP,
   and restart if prompted.
3. Open **Settings → Tools → AI Assistant → Providers & API keys**, select
   **Codex Provider**, then **Sign in with ChatGPT**.
4. Complete browser sign-in, then assign models to **Core** and **Instant**.
   Apply the settings and start a conversation in AI Assistant.
