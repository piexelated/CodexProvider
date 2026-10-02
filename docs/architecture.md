# Code layout

The plugin has one Gradle module. Its Kotlin sources are grouped by responsibility:

- `jetbrains/` contains the AI Assistant provider, feature preferences, persisted settings, settings UI, and quota publishing.
- `runtime/` coordinates account and model refresh, runtime selection, and text generation. `CodexSessionManager` owns the local process and session lifecycle.
- `protocol/` translates Codex app-server operations into JSON RPC requests and handles responses and notifications.
- `install/` finds, downloads, verifies, and caches the native Codex runtime.

The main request path is `CodexProvider` → `CodexRuntime` → `CodexSessionManager` → `CodexSession` → `JsonRpc`. Installation runs when the session manager needs an executable. The settings panel observes runtime state and triggers account or version actions through `CodexRuntime`.

`CodexGenerationGate` limits concurrent text requests and drains them before a session replacement. `CodexSessionManager.replace` initializes and validates a candidate before publishing it; validation failure preserves the working connection. `CodexFailure` translates protocol errors into fixed user guidance and safe numeric diagnostics.
