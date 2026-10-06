# Development and compatibility checks

## Build and verify

Run `./gradlew check buildPlugin verifyPluginStructure` with Java 25 to download and build against pinned IDEA 2026.2.2 and AI Assistant 262.10315.187 dependencies. The plugin ZIP is written to `build/distributions/`. Dependency verification remains enabled, with checksums for the pinned platform archives on Linux, macOS, and Windows.

To use a local IDE explicitly:

```bash
./gradlew -PdownloadPlatform=false -PideaPath=/path/to/idea \
  -PaiAssistantPath=/path/to/ml-llm check buildPlugin verifyPluginStructure
```

Local installations and generated dependency metadata vary by machine. If verification rejects local artifacts, inspect them and generate checksums with `--write-verification-metadata sha256`; do not disable verification for the normal pinned build.

`check` runs two suites:

- `protocolTest` sends fixture JSON RPC requests to a fake Codex app server and checks account, model, quota, turn, cancellation, and installer behavior.
- The IntelliJ test runner loads this plugin with AI Assistant and calls the provider contract with fixture requests. It checks extension registration, connection status, model profiles, chat streaming, completion rejection, feature model preference, and settings entry points. It also enables the registered provider under AI Assistant's third-party acknowledgement, asks AI Assistant's own client to discover its model, and sends a dummy chat request through that client for every prompt ID in `CodexFeatures`. For each request it checks the bundled routing rule, candidate profiles, selected profile, fixture response, and receipt by the registered Codex provider. The run fails if a supported prompt has no rule, excludes Codex, selects a different profile, or fails to dispatch.

## Releases and diagnostics

Tagging a version runs the pinned cross-platform checks and builds a separate ZIP for the latest recorded Marketplace-compatible passing pair in each IDE branch. Each pair must pass `check buildPlugin verifyPluginStructure` again at the release commit before publication. Archives include their IDEA and AI Assistant versions, a SHA-256 file, and verification metadata. The release download index identifies every tested pair and its source commit. Plugin descriptors limit installation to the selected IDE branch.

Use `python3 scripts/release-artifacts.py matrix --manifest docs/compatibility-results.json` to inspect the release targets. Set `CODEX_COMPATIBILITY_OUTPUT_DIR` when running `compatibility-test.sh` to export a successfully tested ZIP before its isolated workspace is removed.

**Diagnostics → Log model responses to idea.log** in the provider settings enables response-body logging to `idea.log`, including Rename responses. It is persisted and disabled by default. Operational traces remain available without it; error guidance includes fixed recovery instructions while raw server errors, prompts, and sign-in URLs are omitted.

Account actions and runtime switches in the provider settings take effect immediately. Feature model overrides and response logging are preferences: they follow the settings page's **Apply** and **Cancel**.

Runtime updates download the selected version while the working session remains available. The swap waits for active text requests, initializes and validates the candidate account and model catalog, then commits the session, selected version, and catalog together. A failed or cancelled replacement closes only the candidate. The previous version stays selected, and **Try Again** retries a failed selection. Existing ACP chats reconnect to use a successfully switched executable.

## Shared runtime for Core and Codex chat

The Core and Instant selectors use this plugin's installed Codex runtime.
JetBrains installs the **Codex** agent's `codex-acp` adapter separately, with its
own CLI dependency. That dependency can lag behind the provider: locally,
CLI 0.158.0 omitted 6.1 Sol while the provider's CLI 0.159.2 exposed it.

On AI Assistant builds exposing `AcpProcessLauncher`, the plugin wraps that
launch service and supplies `CODEX_PATH` to the registry Codex adapter. Each
local chat launch resolves the provider's selected executable, so runtime
updates and pins apply to both paths. It delegates process creation and launch
policy to JetBrains and preserves the adapter arguments and other environment
settings. Explicit `CODEX_PATH` overrides and remote/WSL launches keep their own
runtime. Existing agent processes retain their executable and catalog until
the chat reconnects.

The JetBrains launcher implementation is Kotlin-internal. Construction is
isolated in `CodexAcpProcessLauncher`, and an IDE contract test verifies the
service override and runtime handoff. Older AI Assistant targets without the
launcher API build the text provider without this integration.

## Test a specific IDE and AI Assistant pair in Docker

Docker Engine must be running. From the repository root:

```bash
scripts/compatibility-test.sh 2026.2.2 262.10315.187
scripts/compatibility-test.sh 2025.3.6.1 253.33813.55
scripts/compatibility-test.sh 2026.2.2 262.10315.187 21 0.157.1
```

The optional third argument sets the Java bytecode target (`17`, `21`, or `25`); it defaults to `21`. The optional fourth argument selects an exact stable Codex CLI release. The script builds a Java 25 container, downloads the exact IDE and AI Assistant versions through the IntelliJ Platform Gradle plugin, and runs `check buildPlugin verifyPluginStructure`. With a CLI version, it also uses the plugin's installer to download that official release and runs `codexCliTest` against its real app-server. Downloaded artifacts are cached per version pair under `~/.cache/codex-jetbrains-compat/gradle-pairs/` (or `$XDG_CACHE_HOME/codex-jetbrains-compat/gradle-pairs/`). The first run of each pair needs several gigabytes of disk space and can take significantly longer than cached runs.

Multiple local version pairs can execute at once. Each invocation copies the current source, including uncommitted source files, into a separate temporary workspace and uses a separate Gradle cache, so its build output and IDE test sandbox are isolated. Repeated runs of the same pair share a cache and are serialized. Successful runs remove their workspace; failed runs print its path and retain it for inspection. For example:

```bash
scripts/compatibility-test.sh 2026.2.2 262.10315.187 > /tmp/compat-262.log 2>&1 & first=$!
scripts/compatibility-test.sh 2025.3.6.1 253.33813.55 > /tmp/compat-253.log 2>&1 & second=$!
wait "$first"; first_status=$?
wait "$second"; second_status=$?
printf '262 exit: %s; 253 exit: %s\n' "$first_status" "$second_status"
```

The [IDE compatibility workflow](../.github/workflows/compatibility.yml) runs five passing representative pairs for pull requests and pushes to `master`. Changes that only touch Markdown or `docs/` skip it, and a new push to a pull request cancels that pull request's running checks. A lint job validates the Gradle wrapper, runs shellcheck on `scripts/`, and tests the release and discovery tooling. Workflow actions are pinned to commit SHAs and kept current by Dependabot. Use its **Run workflow** action to test another exact pair without installing either product locally.

One representative pair also runs `scripts/verify-compatibility-suite.sh` with Codex CLI 0.157.1. It first checks that the unmodified source passes against the real CLI, then changes the Rename prompt ID in an isolated test workspace and checks that the suite fails specifically because AI Assistant has no bundled routing rule for it. Run it locally with `scripts/verify-compatibility-suite.sh 2026.2.2 262.10315.187 0.157.1`. The source tree is not changed by this fault injection.

### Automatic release discovery

The [release discovery workflow](../.github/workflows/compatibility-discovery.yml) runs daily at 04:37 UTC or on demand. It reads [JetBrains' stable IDEA release feed](https://data.services.jetbrains.com/products/releases?code=IIU&latest=false&type=release) and asks [JetBrains Marketplace's compatible-plugin download endpoint](https://plugins.jetbrains.com/docs/marketplace/plugin-update-download.html) for the latest AI Assistant build compatible with each IDEA build. It checks previously tested IDE releases for newer AI Assistant builds and new stable IDE releases dated after 25 September 2026. It also checks [OpenAI Codex's GitHub releases](https://github.com/openai/codex/releases) for stable CLI releases from 25 September 2026 onward. It tests up to eight unrecorded IDE/AI pairs and two unrecorded CLI releases per run, with at most five parallel jobs; the rest remain eligible for the next run. Discovery only tests releases that have no recorded result. Keeping the supported pairs passing against new source is the job of the IDE compatibility and release workflows, so a source change does not queue every recorded release for re-testing.

Each job stores its result and full log as a workflow artifact. Results are historical evidence at their recorded commit; they do not certify untested current source. New results include the full Git commit SHA of the source and test suite used by that job (`test_suite_revision`). Earlier local results have no trustworthy revision, so the table shows **Unknown** for them. Passing and deterministic compilation or test failures are added to [compatibility-results.json](compatibility-results.json). Download and infrastructure errors leave the release unrecorded so a later run can retry. Runs on the default branch commit the result file and generated supported-version tables in the README directly to that branch when either file changes. The write job uses GitHub Actions' `contents: write` permission. It does not publish a plugin build.

Run discovery locally without downloading any IDE binaries:

```bash
python3 scripts/compatibility-releases.py discover \
  --manifest docs/compatibility-results.json \
  --output /tmp/compatibility-candidates.json
```

The README IDE table lists only exact IDEA and AI Assistant pairs that passed and whose AI Assistant build Marketplace declares compatible with that IDE build. Historical harness passes with a Marketplace build mismatch stay in the result file, but are not listed as supported. The separate CLI table lists real CLI releases tested with IDEA 2026.2.2 and AI Assistant 262.10315.187. A CLI pass verifies binary installation and the signed-out app-server protocol (`initialize`, `account/read`, `model/list`, `thread/start`) using the plugin's launch arguments. It does not imply a successful authenticated model response or that every CLI version was tested with every IDE pair.

### Version survey (25–26 September 2026)

The script builds a separate plugin ZIP for each pair; these results do not mean one ZIP can be installed across the whole range.

| IntelliJ IDEA | AI Assistant | Result |
| --- | --- | --- |
| 2025.1.7.2 | 251.29188.72 | Fails to compile: older provider API |
| 2025.2.6.3 | 252.28539.97 | Fails to compile: older provider API |
| 2025.3 | 253.28294.360 | Fails to compile: older provider API |
| 2025.3.4.1 | 253.32098.101 | Passes full Docker check |
| 2025.3.5 | 253.33514.17 | Passes full Docker check |
| 2025.3.5 | 253.33514.22 | Passes full Docker check |
| 2025.3.6.1 | 253.33813.55 | Passes full Docker check; Marketplace-compatible |
| 2025.3.6.1 | 253.33813.83 | Passes harness, but Marketplace restricts this AI build to IDEA build 253.33813.83 |
| 2026.1.5 | 261.27258.48 | Passes full Docker check |
| 2026.1.5 | 261.27258.89 | Passes full Docker check |
| 2026.2.2 | 262.10315.187 | Passes full Docker check |
| 2026.2.3 | 262.10968.63 | Passes full Docker check |
| 2026.2.3 | 262.10968.147 | Passes full Docker check |

The tested lower boundary lies between IDEA 2025.3 and 2025.3.4.1; intermediate 2025.3 patches were not tested. The passing rows each completed all 27 supported prompt routes using fixture requests.

The Docker run uses `--dependency-verification off` because the existing Gradle verification file contains hashes for known artifacts, while this command is meant to accept arbitrary released versions. The normal local Gradle command still uses dependency verification.

These are headless contract, routing, and protocol checks. They cover upstream provider discovery, profile selection, and chat request dispatch for all 27 advertised prompt IDs, including Rename suggestions, for the selected AI Assistant binary. They do not click through each feature's IDE action or verify that an action still emits the same prompt ID; they also do not perform browser sign-in or paid model inference. A passing result establishes that AI Assistant's tested routing path sends a fixture request to Codex for every advertised prompt, and that the provider methods work with fixture responses for that version pair.
