# Codex JetBrains Bridge

Use your ChatGPT subscription for JetBrains AI Assistant's text features through
the official `@openai/codex-sdk`.

JetBrains' existing UI sends requests to a local OpenAI-compatible endpoint. The
bridge translates them into Codex SDK turns and returns the answers. JetBrains
still owns the editor prompt, rename popup, and accept/discard controls.

This is a standalone provider adapter, not an IntelliJ plugin ZIP. It uses the
provider interface we chose instead of modifying JetBrains internals. Keep it
running while using the IDE. Automatic completion and next-edit suggestions are
outside this project's scope.

## Start

Requires Node.js 22.9 or later. The pinned SDK includes its Codex CLI runtime; a
separate global CLI installation is not needed.

```sh
cd /home/piexel/IdeaProjects/CodexJetBrainsBridge
npm ci
npm run login
npm run doctor
npm start
```

`login` uses Codex's official browser sign-in. Sign in with the ChatGPT account
whose subscription you want to use. By default, credentials and Codex state live
in `.bridge/codex/`, separate from your other Codex profiles. The bridge requires
ChatGPT authentication and strips API-key environment variables from its Codex
process. It does not read or forward ChatGPT access tokens itself.

In a second terminal, obtain the **local provider token**:

```sh
cd /home/piexel/IdeaProjects/CodexJetBrainsBridge
npm run token
```

This token authenticates JetBrains to the local bridge. It is not an OpenAI API
key and does not enable API billing. Keep it private; anyone who has it and can
reach the local port can send requests using your subscription.

## Configure JetBrains

In **Settings → Tools → AI Assistant → Providers & API keys**:

1. Under **Third-party AI providers**, choose **OpenAI-compatible**.
2. Set the URL to `http://127.0.0.1:31337/v1`.
3. Paste the output token from `npm run token` into **API Key**.
4. Disable **Tool calling**.
5. Click **Test Connection**, then apply the settings.
6. Under **Models Assignment**, select `codex-bridge` for **Core features** and
   `codex-bridge-fast` for **Instant helpers**.

Both aliases use Codex's default model unless you configure model IDs. The `fast`
alias is a separate assignment target; it does not imply a faster model by itself.
Use `.env` to set `BRIDGE_MODEL` and optionally `BRIDGE_HELPER_MODEL` to model IDs
available to your ChatGPT account. The bridge does not impersonate GPT-4o or any
other model to work around model selection.

Choose a context-window value supported by your configured model. Start with
JetBrains' default if you are unsure. Do not select this provider under the
separate **AI Completion** settings.

JetBrains documents Core features as including in-editor generation and commit
messages, and Instant helpers as including rename suggestions. This project has
not yet been verified with those live IDE actions. A successful Test Connection
only establishes model discovery, not generation or feature routing.

## Verify

Offline tests:

```sh
npm test
```

These exercise real HTTP requests, SSE framing, schema forwarding, authentication,
limits, errors, cancellation, and the official SDK's subprocess transport. The
transport test uses an offline CLI fixture, not a live model. It does not spend
subscription usage. That one test skips Windows because its fixture is a Unix
executable script.

After login, with `npm start` running:

```sh
npm run smoke
```

This makes three small subscription requests: structured Java name suggestions,
a streamed code replacement, and a follow-up using previous messages. `doctor`
checks authentication only; `smoke` also checks model access and inference.

Then verify in a scratch Java file in the IDE:

1. Select a small method, press `Ctrl+\`, and ask for a small edit. Check that the
   change appears in the editor and that accept/discard works. Menu names and
   shortcuts can vary with the IDE version and keymap.
2. Rename a symbol with `Shift+F6`; check that AI suggestions appear. Ensure
   **Provide AI-generated name suggestions** is enabled.
3. Generate a commit message or documentation, then inspect the result.
4. Watch the bridge terminal for a `completed` request using the assigned alias.
   If there is no request, that action is not reaching this provider. Check model
   assignment and the IDE's selected agent rather than assuming it used Codex.
5. Cancel a generation and check for a `cancelled` entry.

The installed AI Assistant inspected during development was `262.10315.187`, on
IntelliJ IDEA `2026.2.2`. Its release notes say in-editor generation uses the
default agent. If an editor action routes to an agent instead of the configured
Core model, it will not pass through this bridge. Live verification is required
to establish coverage for that build; this adapter does not override agent routing.

## Configuration

Copy `.env.example` to `.env` if you need overrides. Restart after changes.

| Variable | Default | Purpose |
| --- | --- | --- |
| `BRIDGE_PORT` | `31337` | Local listening port |
| `BRIDGE_MODEL` | Codex default | Model behind `codex-bridge` |
| `BRIDGE_HELPER_MODEL` | Same as main model | Model behind `codex-bridge-fast` |
| `BRIDGE_REASONING_EFFORT` | `low` | Default reasoning effort |
| `BRIDGE_TIMEOUT_MS` | `120000` | Generation deadline |
| `BRIDGE_MAX_CONCURRENT` | `2` | Active requests; extra requests get HTTP 429 |
| `BRIDGE_CODEX_HOME` | `.bridge/codex` | Dedicated profile and authentication state |
| `BRIDGE_CODEX_PATH` | SDK-bundled runtime | Optional native Codex executable |
| `BRIDGE_TOKEN` | Generated | Optional token, at least 24 non-whitespace characters |

Use a dedicated Codex home. If you point the bridge at an existing home, you also
trust that home's configuration, plugins, MCP servers, and defaults. Some tool
features are disabled explicitly, but this is not an isolation boundary for an
arbitrary customized Codex configuration. A CLI shell wrapper that changes
`CODEX_HOME` can also defeat profile selection; use the bundled runtime or a native
executable instead.

The bridge runs Codex in a temporary neutral directory with a read-only sandbox,
no approval prompts, and web search and shell tools disabled. It supplies its own
brief instructions and disables project instruction loading. The IDE must send
the context needed to answer each request. Returned code is text for the IDE to
apply, not a request for Codex to edit the project.

## Supported protocol and limits

- `GET /v1/models`, `GET /v1/models/{id}`, and `POST /v1/chat/completions`.
  The same paths without `/v1` work for clients that handle the prefix themselves.
- Text messages in system, developer, user, and assistant roles, including arrays
  of text parts. History is sent afresh for each request; there is no shared chat
  state between IDE features or projects.
- Normal JSON responses and SSE responses with heartbeats, usage, and `[DONE]`.
  **Output arrives after the turn finishes, not token by token.** This avoids
  inserting Codex progress messages into replacement code.
- `response_format: json_schema` forwards the schema to the SDK. `json_object`
  requests valid JSON and checks that the result is an object. Schema enforcement
  belongs to Codex; the bridge also checks JSON syntax.
- The SDK accepts a single turn prompt, so role messages are serialized as an
  ordered JSON transcript. This is not identical to native Chat Completions role
  semantics. Exact prompt behavior needs live feature checks.
- `temperature`, `top_p`, token limits, penalties, `seed`, and metadata sent by
  clients are accepted but **not enforced**, because this SDK interface does not
  expose equivalent controls. Token usage comes from Codex and includes its own
  instructions. There is a 2 MiB request limit and a 4 MiB final-answer limit.
- Tool calling, images/audio, stop sequences, multiple alternatives, logprobs,
  embeddings, the Responses endpoint, FIM completion, and edit prediction are not
  implemented. Unsupported capabilities return errors instead of fake results.
- No automatic retry or silent fallback to another provider. Upstream failures
  produce errors; partial output is never marked as a successful edit.

The server binds only to `127.0.0.1`, requires its token on every endpoint, checks
the Host header, and rejects browser Origin headers. It is for a local IDE, not
a public service or remote IDE backend. Request logs contain IDs, aliases,
status, and elapsed time, not code or credentials. Codex itself may retain session
data inside its home; disabling `history.jsonl` does not disable all session
storage. Treat `.bridge/` as private and do not commit it.

## Troubleshooting

- **Sign-in required:** run `npm run login`, then `npm run doctor` using the same
  `.env` settings. An API-key login is deliberately rejected.
- **HTTP 401 from the bridge:** check the local provider token, not your ChatGPT
  password or access token.
- **HTTP 429:** the two default request slots are occupied. Retry shortly or
  adjust the concurrency limit. This status is the bridge's busy response.
- **HTTP 502:** check ChatGPT sign-in, selected model access, and subscription
  limits. `npm run smoke` reproduces inference without relying on IDE routing.
  The bridge deliberately does not expose raw upstream errors, which may include
  request or account data.
- **HTTP 504:** increase the timeout or choose a faster model. The timed-out SDK
  turn is cancelled.
- **Feature unavailable or no bridge request:** check both model assignments and
  whether that action uses the default agent. Choosing a model in chat alone is
  not sufficient.
- **No text appears immediately:** output is buffered until Codex finishes;
  heartbeats keep streaming requests active.
- **Port already in use:** change `BRIDGE_PORT` and update the IDE URL.

Stop the bridge with `Ctrl+C`. It cancels active turns and removes its temporary
working directory. No IDE settings are changed automatically.

## Sources

- [Official Codex SDK](https://learn.chatgpt.com/docs/codex-sdk)
- [Codex authentication](https://learn.chatgpt.com/docs/auth)
- [Codex configuration](https://learn.chatgpt.com/docs/config-file/config-reference)
- [JetBrains custom models and feature assignment](https://www.jetbrains.com/help/ai-assistant/use-custom-models.html)
- [JetBrains in-editor generation](https://www.jetbrains.com/help/ai-assistant/code-generation.html)
