#!/usr/bin/env bash
set -euo pipefail

if (( $# < 2 || $# > 4 )); then
    echo "Usage: $0 IDEA_VERSION AI_ASSISTANT_VERSION [JVM_TARGET] [CODEX_CLI_VERSION]" >&2
    echo "Example: $0 2026.2.2 262.10315.187 21 0.157.1" >&2
    exit 2
fi

idea_version="$1"
ai_version="$2"
jvm_target="${3:-21}"
cli_version="${4:-}"
if [[ ! "$idea_version" =~ ^[0-9]{4}\.[0-9]+(\.[0-9]+)*$ ]]; then
    echo "Invalid IDEA version: $idea_version" >&2
    exit 2
fi
if [[ ! "$ai_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(\.[0-9]+)?$ ]]; then
    echo "Invalid AI Assistant version: $ai_version" >&2
    exit 2
fi
if [[ ! "$jvm_target" =~ ^(17|21|25)$ ]]; then
    echo "JVM target must be 17, 21, or 25." >&2
    exit 2
fi
if [[ -n "$cli_version" && ! "$cli_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Invalid Codex CLI version: $cli_version" >&2
    exit 2
fi

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
workspace_parent="${XDG_CACHE_HOME:-$HOME/.cache}/codex-jetbrains-compat"
cache_dir="$workspace_parent/gradle-pairs/$idea_version-$ai_version-$jvm_target"
mkdir -p "$cache_dir"
workspace_dir="$(mktemp -d "$workspace_parent/workspace.XXXXXXXX")"
cleanup() {
    status=$?
    if (( status == 0 )); then
        rm -rf -- "$workspace_dir"
    else
        echo "Compatibility workspace retained for inspection: $workspace_dir" >&2
    fi
}
trap cleanup EXIT

# A separate source snapshot gives every run its own Gradle build output and
# IntelliJ test sandbox. Keep local edits, including untracked source files.
tar -C "$repo_dir" \
    --exclude='./.git' \
    --exclude='./.gradle' \
    --exclude='./.kotlin' \
    --exclude='./.intellijPlatform' \
    --exclude='./build' \
    --exclude='./node_modules' \
    --exclude='./.env' \
    --exclude='./.bridge' \
    --exclude='./.idea' \
    -cf - . | tar -C "$workspace_dir" -xf -
if [[ "${CODEX_COMPATIBILITY_SELF_TEST_MISSING_RENAME_RULE:-}" == "1" ]]; then
    python3 - "$workspace_dir/src/main/kotlin/dev/piexel/codex/jetbrains/CodexFeatures.kt" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
source = path.read_text(encoding="utf-8")
original = '"ij.refactorings.name-suggestion"'
if source.count(original) != 1:
    raise SystemExit("Rename prompt ID changed; compatibility self-test mutation needs updating")
path.write_text(source.replace(original, '"ij.refactorings.name-suggestion.self-test-missing-rule"'), encoding="utf-8")
PY
fi
selinux_mount_suffix=""
if command -v getenforce >/dev/null 2>&1 && [[ "$(getenforce)" == "Enforcing" ]]; then
    selinux_mount_suffix=":z"
fi
gradle_tasks=(check buildPlugin verifyPluginStructure)
if [[ "${CODEX_COMPATIBILITY_SELF_TEST_MISSING_RENAME_RULE:-}" == "1" ]]; then
    # Run the upstream routing check itself. protocolTest also recognizes the
    # missing prompt, but would stop the build before the IDE test runs.
    gradle_tasks=(test)
fi
gradle_properties=()
if [[ -n "$cli_version" ]]; then
    gradle_properties+=("-PcodexCliVersion=$cli_version")
    gradle_tasks+=(codexCliTest)
fi

docker build \
    --file "$repo_dir/scripts/Dockerfile.compatibility" \
    --tag codex-jetbrains-compat:java25 \
    "$repo_dir/scripts"

docker run --rm \
    --user "$(id -u):$(id -g)" \
    --volume "$workspace_dir:/workspace$selinux_mount_suffix" \
    --volume "$cache_dir:/gradle-cache$selinux_mount_suffix" \
    --env CI=true \
    codex-jetbrains-compat:java25 \
    flock /gradle-cache/.compatibility.lock \
    ./gradlew --no-daemon --console=plain \
      -PdownloadPlatform=true \
      "-PideaVersion=$idea_version" \
      "-PaiAssistantVersion=$ai_version" \
      "-PjvmTarget=$jvm_target" \
      "${gradle_properties[@]}" \
      --dependency-verification off \
      "${gradle_tasks[@]}"

# Export only archives built by a successful check in this isolated workspace.
if [[ -n "${CODEX_COMPATIBILITY_OUTPUT_DIR:-}" ]]; then
    mkdir -p -- "$CODEX_COMPATIBILITY_OUTPUT_DIR"
    shopt -s nullglob
    archives=("$workspace_dir"/build/distributions/*.zip)
    if (( ${#archives[@]} != 1 )); then
        echo "Expected exactly one tested plugin ZIP, found ${#archives[@]}." >&2
        exit 1
    fi
    cp -- "${archives[0]}" "$CODEX_COMPATIBILITY_OUTPUT_DIR/"
fi
