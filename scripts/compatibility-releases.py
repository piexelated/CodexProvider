#!/usr/bin/env python3
"""Discover released IDEA/AI pairs and maintain the tested-version table."""

import argparse
import hashlib
from datetime import date, datetime, timezone
from http import HTTPStatus
import json
from pathlib import Path
import re
import sys
from urllib.error import HTTPError
from urllib.parse import quote, urlparse
from urllib.request import Request, urlopen


IDE_RELEASES = "https://data.services.jetbrains.com/products/releases?code=IIU&latest=false&type=release"
PLUGIN_DOWNLOAD = "https://plugins.jetbrains.com/pluginManager?action=download&id=com.intellij.ml.llm&build=IU-{}"
CODEX_RELEASES = "https://api.github.com/repos/openai/codex/releases?per_page=30"
CLI_REFERENCE_IDEA = "2026.2.2"
CLI_REFERENCE_AI = "262.10315.187"
TABLE_START = "<!-- compatibility-table:start -->"
TABLE_END = "<!-- compatibility-table:end -->"
CLI_TABLE_START = "<!-- codex-cli-table:start -->"
CLI_TABLE_END = "<!-- codex-cli-table:end -->"
PLUGIN_VERSION = re.compile(r"/ml-llm-(\d+(?:\.\d+){2,3})\.zip$")


def suite_fingerprint(root: Path) -> str:
    """Exclude generated results/docs so the discovery bot cannot trigger a retest loop."""
    paths = [root / name for name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", ".github/workflows/compatibility.yml")]
    for directory in ("src", "scripts", "gradle"):
        paths.extend(path for path in (root / directory).rglob("*")
                     if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc")
    digest = hashlib.sha256()
    for path in sorted(paths):
        if path.is_file():
            digest.update(str(path.relative_to(root)).encode() + b"\0" + path.read_bytes() + b"\0")
    return digest.hexdigest()


def version_key(value: str) -> tuple[int, ...]:
    return tuple(int(part) for part in value.split("."))


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def request_json(url: str) -> dict:
    request = Request(url, headers={"User-Agent": "CodexJetBrainsBridge-compatibility/1.0"})
    with urlopen(request, timeout=30) as response:
        return json.load(response)


def matching_ai_version(idea_build: str) -> str | None:
    request = Request(
        PLUGIN_DOWNLOAD.format(quote(idea_build)),
        method="HEAD",
        headers={"User-Agent": "CodexJetBrainsBridge-compatibility/1.0"},
    )
    try:
        with urlopen(request, timeout=30) as response:
            final_url = response.url
    except HTTPError as error:
        if error.code in (HTTPStatus.NOT_FOUND, HTTPStatus.NO_CONTENT):
            return None
        raise
    match = PLUGIN_VERSION.search(urlparse(final_url).path)
    if not match:
        raise ValueError(f"Marketplace returned an unexpected plugin URL for {idea_build}: {final_url}")
    version = match.group(1)
    if version_key(version)[0] != version_key(idea_build)[0]:
        raise ValueError(f"Marketplace selected AI Assistant {version} for IDEA build {idea_build}")
    return version


def choose_idea_releases(releases: list[dict], manifest: dict, today: date) -> list[dict]:
    known_ideas = {result["idea"] for result in manifest["results"]}
    scan_after = date.fromisoformat(manifest["discover_releases_after"])
    selected = {}
    for release in releases:
        version = release.get("version", "")
        build = release.get("build", "")
        released = date.fromisoformat(release["date"])
        if (
            re.fullmatch(r"20\d{2}\.\d+(?:\.\d+)*", version)
            and re.fullmatch(r"\d+(?:\.\d+)+", build)
            and released <= today
            and (version in known_ideas or released > scan_after)
        ):
            selected[version] = release
    return sorted(selected.values(), key=lambda release: version_key(release["version"]), reverse=True)


def discover(manifest: dict, releases: list[dict], resolve_ai, today: date, limit: int, fingerprint: str | None = None) -> list[dict]:
    seen = {(result["idea"], result["ai"]) for result in manifest["results"]
            if fingerprint is None or result.get("test_suite_fingerprint") == fingerprint}
    candidates = []
    for release in choose_idea_releases(releases, manifest, today):
        ai = resolve_ai(release["build"])
        if ai is None:
            print(f"No compatible AI Assistant release for IDEA {release['version']} ({release['build']})", file=sys.stderr)
            continue
        if (release["version"], ai) not in seen:
            candidates.append({"idea": release["version"], "ai": ai})
    return candidates[:limit]


def discover_cli(manifest: dict, releases: list[dict], today: date, limit: int, fingerprint: str | None = None) -> list[dict]:
    seen = {result["cli"] for result in manifest.get("cli_results", [])
            if fingerprint is None or result.get("test_suite_fingerprint") == fingerprint}
    scan_after = date.fromisoformat(manifest.get("discover_cli_after", "2026-09-25"))
    versions = []
    for release in releases:
        tag = release.get("tag_name", "")
        published = release.get("published_at")
        if release.get("draft") or release.get("prerelease") or not published:
            continue
        if not re.fullmatch(r"rust-v\d+\.\d+\.\d+", tag):
            continue
        if not scan_after <= date.fromisoformat(published[:10]) <= today:
            continue
        version = tag.removeprefix("rust-v")
        if version not in seen:
            versions.append(version)
    return [
        {"kind": "cli", "idea": CLI_REFERENCE_IDEA, "ai": CLI_REFERENCE_AI, "cli": version}
        for version in sorted(set(versions), key=version_key, reverse=True)[:limit]
    ]


def render_table(manifest: dict) -> str:
    latest_pass = {}
    for result in manifest["results"]:
        if result["status"] != "passed" or result.get("marketplace_compatible") is False:
            continue
        idea = result["idea"]
        if idea not in latest_pass or version_key(result["ai"]) > version_key(latest_pass[idea]["ai"]):
            latest_pass[idea] = result
    lines = [
        TABLE_START,
        "| IntelliJ IDEA | AI Assistant | Verified | Test suite commit |",
        "| --- | --- | --- | --- |",
    ]
    for idea in sorted(latest_pass, key=version_key, reverse=True):
        result = latest_pass[idea]
        revision = result.get("test_suite_revision")
        revision_label = f"`{revision[:12]}`" if revision else "Unknown"
        lines.append(f"| {idea} | `{result['ai']}` | {result['tested_at']} | {revision_label} |")
    lines.append(TABLE_END)
    return "\n".join(lines)


def render_cli_table(manifest: dict) -> str:
    lines = [
        CLI_TABLE_START,
        "| Codex CLI | IntelliJ IDEA | AI Assistant | Verified | Test suite commit |",
        "| --- | --- | --- | --- | --- |",
    ]
    for result in sorted(manifest.get("cli_results", []), key=lambda row: version_key(row["cli"]), reverse=True):
        if result["status"] != "passed":
            continue
        revision = result.get("test_suite_revision")
        revision_label = f"`{revision[:12]}`" if revision else "Unknown"
        lines.append(f"| `{result['cli']}` | {result['idea']} | `{result['ai']}` | {result['tested_at']} | {revision_label} |")
    lines.append(CLI_TABLE_END)
    return "\n".join(lines)


def update_readme(readme: Path, manifest: dict) -> None:
    content = readme.read_text(encoding="utf-8")
    table = render_table(manifest)
    if TABLE_START in content or TABLE_END in content:
        if content.count(TABLE_START) != 1 or content.count(TABLE_END) != 1:
            raise ValueError("README compatibility table markers must each occur once")
        start = content.index(TABLE_START)
        end = content.index(TABLE_END) + len(TABLE_END)
        if end < start:
            raise ValueError("README compatibility table markers are out of order")
        content = content[:start] + table + content[end:]
    else:
        content = content.rstrip() + "\n\n## Tested IntelliJ IDEA versions\n\n"
        content += "These exact IDE and AI Assistant pairs passed the Docker compatibility checks. "
        content += "Each pair builds its own plugin ZIP; other combinations are untested.\n\n"
        content += table + "\n"
    if manifest.get("cli_results") or CLI_TABLE_START in content or CLI_TABLE_END in content:
        cli_table = render_cli_table(manifest)
        if CLI_TABLE_START in content or CLI_TABLE_END in content:
            if content.count(CLI_TABLE_START) != 1 or content.count(CLI_TABLE_END) != 1:
                raise ValueError("README Codex CLI table markers must each occur once")
            start = content.index(CLI_TABLE_START)
            end = content.index(CLI_TABLE_END) + len(CLI_TABLE_END)
            if end < start:
                raise ValueError("README Codex CLI table markers are out of order")
            content = content[:start] + cli_table + content[end:]
        else:
            content = content.rstrip() + "\n\n## Tested Codex CLI versions\n\n"
            content += "These CLI releases passed the real app-server protocol check with the listed IDE and AI Assistant pair. "
            content += "The check uses an isolated signed-out profile and performs no model inference.\n\n"
            content += cli_table + "\n"
    readme.write_text(content, encoding="utf-8")


def classify(log: str, exit_code: int, cli: str = "") -> tuple[str, str]:
    ran_test = re.search(r"(?m)^> Task :test(?:\s|$)", log) is not None
    ran_protocol = re.search(r"(?m)^> Task :protocolTest(?:\s|$)", log) is not None
    ran_cli = not cli or (re.search(r"(?m)^> Task :codexCliTest(?:\s|$)", log) is not None
                          and f"PASS Codex CLI {cli} app-server" in log)
    if exit_code == 0 and "BUILD SUCCESSFUL" in log and ran_test and ran_protocol and ran_cli:
        return "passed", "Full Docker compatibility check passed"
    for task in ("compileKotlin", "compileTestKotlin", "test", "protocolTest", "verifyPluginStructure", "buildPlugin", "codexCliTest"):
        if f"> Task :{task} FAILED" in log:
            return "failed", f"Gradle task {task} failed against the selected binaries"
    return "error", f"Compatibility run ended with exit code {exit_code}; inspect its log"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    discover_command = commands.add_parser("discover")
    discover_command.add_argument("--manifest", type=Path, required=True)
    discover_command.add_argument("--output", type=Path, required=True)
    discover_command.add_argument("--github-output", type=Path)
    discover_command.add_argument("--limit", type=int, default=8)

    result_command = commands.add_parser("result")
    result_command.add_argument("--idea", required=True)
    result_command.add_argument("--ai", required=True)
    result_command.add_argument("--cli", default="")
    result_command.add_argument("--test-suite-revision", required=True)
    result_command.add_argument("--test-suite-fingerprint")
    result_command.add_argument("--exit-code", type=int, required=True)
    result_command.add_argument("--log", type=Path, required=True)
    result_command.add_argument("--output", type=Path, required=True)

    merge_command = commands.add_parser("merge")
    merge_command.add_argument("--manifest", type=Path, required=True)
    merge_command.add_argument("--readme", type=Path, required=True)
    merge_command.add_argument("--results-dir", type=Path, required=True)
    merge_command.add_argument("--summary", type=Path)

    render_command = commands.add_parser("render")
    render_command.add_argument("--manifest", type=Path, required=True)
    render_command.add_argument("--readme", type=Path, required=True)

    args = parser.parse_args()
    if args.command == "discover":
        if args.limit < 1:
            parser.error("--limit must be positive")
        manifest = read_json(args.manifest)
        release_data = request_json(IDE_RELEASES)
        releases = release_data["IIU"]
        if not releases:
            raise ValueError("JetBrains returned no IDEA releases")
        today = datetime.now(timezone.utc).date()
        fingerprint = suite_fingerprint(Path(__file__).resolve().parents[1])
        idea_candidates = discover(manifest, releases, matching_ai_version, today, args.limit, fingerprint)
        cli_candidates = discover_cli(manifest, request_json(CODEX_RELEASES), today, limit=2, fingerprint=fingerprint)
        candidates = [{"kind": "ide", **candidate} for candidate in idea_candidates] + cli_candidates
        matrix = {"include": candidates}
        write_json(args.output, matrix)
        if args.github_output:
            with args.github_output.open("a", encoding="utf-8") as output:
                output.write("matrix=" + json.dumps(matrix, separators=(",", ":")) + "\n")
                output.write(f"count={len(candidates)}\n")
                output.write(f"fingerprint={fingerprint}\n")
        for candidate in candidates:
            print(f"DISCOVERED IDEA {candidate['idea']} / AI Assistant {candidate['ai']}" +
                  (f" / Codex CLI {candidate['cli']}" if candidate['kind'] == 'cli' else ""))
        print(f"{len(candidates)} untested compatibility checks")
    elif args.command == "result":
        if not re.fullmatch(r"[0-9a-f]{40}", args.test_suite_revision):
            parser.error("--test-suite-revision must be a full 40-character Git commit SHA")
        status, reason = classify(args.log.read_text(encoding="utf-8", errors="replace"), args.exit_code, args.cli)
        result = {
            "idea": args.idea,
            "ai": args.ai,
            "marketplace_compatible": True,
            "status": status,
            "reason": reason,
            "tested_at": datetime.now(timezone.utc).date().isoformat(),
            "test_suite_revision": args.test_suite_revision,
        }
        if args.test_suite_fingerprint:
            if not re.fullmatch(r"[0-9a-f]{64}", args.test_suite_fingerprint):
                parser.error("--test-suite-fingerprint must be a SHA-256 digest")
            result["test_suite_fingerprint"] = args.test_suite_fingerprint
        if args.cli:
            if not re.fullmatch(r"\d+\.\d+\.\d+", args.cli):
                parser.error("--cli must be a stable Codex CLI version")
            result["cli"] = args.cli
        write_json(args.output, result)
        print(f"IDEA {args.idea} / AI Assistant {args.ai}: {status} ({reason})")
    elif args.command == "render":
        update_readme(args.readme, read_json(args.manifest))
    elif args.command == "merge":
        manifest = read_json(args.manifest)
        result_files = sorted(args.results_dir.rglob("result.json"))
        if not result_files:
            raise ValueError("No compatibility result artifacts were downloaded")
        results = [read_json(path) for path in result_files]
        lines = ["## JetBrains compatibility discovery", ""]
        for result in results:
            label = f"IDEA {result['idea']} / AI Assistant {result['ai']}"
            if result.get("cli"):
                label += f" / Codex CLI {result['cli']}"
            lines.append(f"- {label}: {result['status']} — {result['reason']}")
        if args.summary:
            with args.summary.open("a", encoding="utf-8") as summary:
                summary.write("\n".join(lines) + "\n")
        if any(result["status"] == "error" for result in results):
            raise ValueError("At least one compatibility run had an infrastructure error; results were not published")
        ide_results = [result for result in results if not result.get("cli")]
        cli_results = [result for result in results if result.get("cli")]
        by_pair = {(result["idea"], result["ai"]): result for result in manifest["results"]}
        by_pair.update({(result["idea"], result["ai"]): result for result in ide_results})
        manifest["results"] = sorted(
            by_pair.values(), key=lambda result: (version_key(result["idea"]), version_key(result["ai"])),
        )
        by_cli = {result["cli"]: result for result in manifest.get("cli_results", [])}
        by_cli.update({result["cli"]: result for result in cli_results})
        manifest["cli_results"] = sorted(by_cli.values(), key=lambda result: version_key(result["cli"]))
        manifest["schema"] = 2
        write_json(args.manifest, manifest)
        update_readme(args.readme, manifest)


if __name__ == "__main__":
    main()
