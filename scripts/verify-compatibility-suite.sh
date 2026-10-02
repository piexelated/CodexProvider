#!/usr/bin/env bash
set -euo pipefail

if (( $# < 2 || $# > 3 )); then
    echo "Usage: $0 IDEA_VERSION AI_ASSISTANT_VERSION [CODEX_CLI_VERSION]" >&2
    exit 2
fi

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
positive_log="$(mktemp)"
negative_log="$(mktemp)"
cleanup() {
    rm -f -- "$positive_log" "$negative_log"
}
trap cleanup EXIT

echo "Checking that the unmodified compatibility suite passes..."
positive_args=("$1" "$2")
if (( $# == 3 )); then
    positive_args+=(21 "$3")
fi
if ! "$repo_dir/scripts/compatibility-test.sh" "${positive_args[@]}" > "$positive_log" 2>&1; then
    cat "$positive_log"
    echo "Compatibility suite self-check failed: the control run did not pass." >&2
    exit 1
fi
echo "Control run passed."

echo "Checking that a missing Rename routing rule is detected..."
if CODEX_COMPATIBILITY_SELF_TEST_MISSING_RENAME_RULE=1 \
    "$repo_dir/scripts/compatibility-test.sh" "$1" "$2" > "$negative_log" 2>&1; then
    cat "$negative_log"
    echo "Compatibility suite self-check failed: the broken Rename route passed." >&2
    exit 1
fi

python3 - "$negative_log" <<'PY'
from pathlib import Path
import re
import shutil
import sys

log = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
match = re.search(r"(?m)^Compatibility workspace retained for inspection: (.+)$", log)
if not match:
    raise SystemExit("Compatibility suite self-check failed: no failed test workspace was retained.\n" + log[-6000:])
workspace = Path(match.group(1))
expected = "AI Assistant has no bundled routing rule for rename (ij.refactorings.name-suggestion.self-test-missing-rule)"
test_results = workspace / "build/test-results/test"
xml_files = list(test_results.glob("TEST-*.xml"))
if not xml_files or not any(expected in path.read_text(encoding="utf-8") for path in xml_files):
    raise SystemExit("Compatibility suite self-check failed for a reason other than the injected Rename route.\n" + log[-6000:])
print("PASS: the compatibility suite rejected the missing Rename routing rule.")
if not workspace.name.startswith("workspace.") or workspace.parent.name != "codex-jetbrains-compat":
    raise SystemExit(f"Refusing to remove unexpected compatibility workspace: {workspace}")
shutil.rmtree(workspace)
PY
