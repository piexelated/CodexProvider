#!/usr/bin/env python3
"""Choose release targets and label freshly tested plugin archives."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
from urllib.parse import quote
import zipfile

CONFIG_FILE = re.compile(r'config-file="([^"]+)"')


def release_pairs(manifest: dict) -> list[dict]:
    branches = {}
    for result in manifest["results"]:
        if result["status"] != "passed" or result.get("marketplace_compatible") is False:
            continue
        branch = result["ai"].split(".")[0]
        key = tuple(int(part) for part in result["idea"].split(".")), tuple(int(part) for part in result["ai"].split("."))
        if branch not in branches or key > branches[branch][0]:
            branches[branch] = (key, {"idea": result["idea"], "ai": result["ai"]})
    if not branches:
        raise ValueError("No passing release targets are recorded")
    return [branches[branch][1] for branch in sorted(branches, key=int)]


def missing_plugin_configs(archive: Path) -> list[str]:
    """Lists config files the plugin descriptor references but its jar does not contain."""
    with zipfile.ZipFile(archive) as plugin:
        for name in plugin.namelist():
            if not name.endswith(".jar"):
                continue
            with zipfile.ZipFile(io.BytesIO(plugin.read(name))) as jar:
                entries = set(jar.namelist())
                if "META-INF/plugin.xml" not in entries:
                    continue
                descriptor = jar.read("META-INF/plugin.xml").decode("utf-8")
                return [config for config in CONFIG_FILE.findall(descriptor) if f"META-INF/{config}" not in entries]
    raise ValueError(f"{archive.name} contains no plugin descriptor")


def bundle(source: Path, output: Path, idea: str, ai: str, revision: str) -> Path:
    archives = list(source.glob("*.zip"))
    if len(archives) != 1:
        raise ValueError(f"Expected one freshly tested archive, found {len(archives)}")
    archive = archives[0]
    missing = missing_plugin_configs(archive)
    if missing:
        raise ValueError(f"{archive.name} references missing plugin config files: {', '.join(missing)}")
    output.mkdir(parents=True, exist_ok=True)
    name = f"{archive.stem}-idea-{idea}-ai-{ai}.zip"
    destination = output / name
    destination.write_bytes(archive.read_bytes())
    checksum = hashlib.sha256(destination.read_bytes()).hexdigest()
    (output / f"{name}.sha256").write_text(f"{checksum}  {name}\n", encoding="utf-8")
    (output / f"{name}.json").write_text(json.dumps({
        "archive": name, "idea": idea, "ai": ai, "test_suite_revision": revision,
    }, indent=2) + "\n", encoding="utf-8")
    return destination


def release_notes(artifacts: Path, repository: str, tag: str) -> str:
    results = [json.loads(path.read_text(encoding="utf-8")) for path in sorted(artifacts.glob("*.zip.json"))]
    if not results:
        raise ValueError("No tested release artifacts were found")
    lines = ["Install the ZIP matching your IntelliJ IDEA and AI Assistant pair using **Settings → Plugins → Install Plugin from Disk**.",
             "", "Each archive passed the full compatibility suite at the release commit. Other version pairs are untested.", "",
             "| IntelliJ IDEA | AI Assistant | Plugin ZIP | Test suite commit |", "| --- | --- | --- | --- |"]
    for result in results:
        archive = result["archive"]
        if not (artifacts / archive).is_file() or not (artifacts / f"{archive}.sha256").is_file():
            raise ValueError(f"Missing release archive or checksum: {archive}")
        url = f"https://github.com/{repository}/releases/download/{quote(tag, safe='')}/{quote(archive, safe='')}"
        lines.append(f"| {result['idea']} | `{result['ai']}` | [Download]({url}) | `{result['test_suite_revision'][:12]}` |")
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    matrix = sub.add_parser("matrix")
    matrix.add_argument("--manifest", type=Path, required=True)
    package = sub.add_parser("bundle")
    package.add_argument("--source", type=Path, required=True)
    package.add_argument("--output", type=Path, required=True)
    package.add_argument("--idea", required=True)
    package.add_argument("--ai", required=True)
    package.add_argument("--revision", required=True)
    notes = sub.add_parser("notes")
    notes.add_argument("--artifacts", type=Path, required=True)
    notes.add_argument("--repository", required=True)
    notes.add_argument("--tag", required=True)
    notes.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "matrix":
        print(json.dumps({"include": release_pairs(json.loads(args.manifest.read_text(encoding="utf-8")))}))
    elif args.command == "bundle":
        if not re.fullmatch(r"[0-9a-f]{40}", args.revision):
            parser.error("--revision must be a full Git commit SHA")
        bundle(args.source, args.output, args.idea, args.ai, args.revision)
    else:
        args.output.write_text(release_notes(args.artifacts, args.repository, args.tag), encoding="utf-8")


if __name__ == "__main__":
    main()
