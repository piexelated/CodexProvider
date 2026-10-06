import importlib.util
from datetime import date
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).with_name("compatibility-releases.py")
spec = importlib.util.spec_from_file_location("compatibility_releases", SCRIPT)
compatibility = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compatibility)


class CompatibilityReleaseTest(unittest.TestCase):
    def test_discovery_checks_new_ideas_and_new_ai_for_known_ideas(self):
        manifest = {
            "discover_releases_after": "2026-09-25",
            "results": [{"idea": "2026.1.5", "ai": "261.27258.48", "status": "passed"}],
        }
        releases = [
            {"version": "2025.3", "build": "253.28294.334", "date": "2025-12-01"},
            {"version": "2026.1.5", "build": "261.27258.48", "date": "2026-08-01"},
            {"version": "2026.2.3", "build": "262.10968.63", "date": "2026-09-26"},
            {"version": "2026.3", "build": "263.100.1", "date": "2026-10-01"},
        ]
        ai_by_build = {"261.27258.48": "261.27258.89", "262.10968.63": "262.10968.147"}
        candidates = compatibility.discover(
            manifest, releases, ai_by_build.get, date(2026, 9, 26), limit=8,
        )
        self.assertEqual(candidates, [
            {"idea": "2026.2.3", "ai": "262.10968.147"},
            {"idea": "2026.1.5", "ai": "261.27258.89"},
        ])

    def test_discovery_does_not_retest_a_pair_when_suite_revision_changes(self):
        manifest = {
            "discover_releases_after": "2026-09-25",
            "results": [{
                "idea": "2026.2.3", "ai": "262.10968.147", "status": "passed",
                "test_suite_revision": "a" * 40,
            }],
        }
        releases = [{"version": "2026.2.3", "build": "262.10968.63", "date": "2026-09-26"}]
        self.assertEqual(compatibility.discover(
            manifest, releases, lambda _: "262.10968.147", date(2026, 9, 26), limit=8,
        ), [])

    def test_cli_discovery_only_selects_unrecorded_stable_releases(self):
        manifest = {
            "discover_cli_after": "2026-09-25",
            "cli_results": [{"cli": "0.157.0", "status": "passed"}],
        }
        releases = [
            {"tag_name": "rust-v0.157.1", "published_at": "2026-09-26T01:02:31Z", "draft": False, "prerelease": False},
            {"tag_name": "rust-v0.157.0", "published_at": "2026-09-25T01:02:31Z", "draft": False, "prerelease": False},
            {"tag_name": "rust-v0.158.0-alpha.1", "published_at": "2026-09-26T02:00:00Z", "draft": False, "prerelease": True},
            {"tag_name": "rust-v0.156.0", "published_at": "2026-09-24T01:02:31Z", "draft": False, "prerelease": False},
        ]
        self.assertEqual(compatibility.discover_cli(manifest, releases, date(2026, 9, 26), 2), [
            {"kind": "cli", "idea": "2026.2.2", "ai": "262.10315.187", "cli": "0.157.1"},
        ])

    def test_table_only_lists_marketplace_compatible_passes_and_is_idempotent(self):
        manifest = {
            "results": [
                {"idea": "2025.3.6.1", "ai": "253.33813.83", "status": "passed", "tested_at": "2026-09-25", "marketplace_compatible": False},
                {"idea": "2025.3.6.1", "ai": "253.33813.55", "status": "passed", "tested_at": "2026-09-26", "marketplace_compatible": True, "test_suite_revision": "a" * 40},
                {"idea": "2025.3.4.1", "ai": "253.32098.101", "status": "passed", "tested_at": "2026-09-25"},
                {"idea": "2026.1.5", "ai": "261.27258.89", "status": "failed", "tested_at": "2026-09-26"},
            ],
            "cli_results": [
                {"idea": "2026.2.2", "ai": "262.10315.187", "cli": "0.157.1", "status": "passed", "tested_at": "2026-09-26", "test_suite_revision": "b" * 40},
                {"idea": "2026.2.2", "ai": "262.10315.187", "cli": "0.157.0", "status": "failed", "tested_at": "2026-09-26"},
            ],
        }
        with tempfile.TemporaryDirectory() as temporary:
            readme = Path(temporary) / "README.md"
            readme.write_text("# Project\n", encoding="utf-8")
            compatibility.update_readme(readme, manifest)
            first = readme.read_text(encoding="utf-8")
            compatibility.update_readme(readme, manifest)
            self.assertEqual(readme.read_text(encoding="utf-8"), first)
            self.assertIn("| 2025.3.6.1 | `253.33813.55` |", first)
            self.assertNotIn("253.33813.83", first)
            self.assertNotIn("2026.1.5", first)
            self.assertIn("| 2025.3.6.1 | `253.33813.55` | 2026-09-26 | `aaaaaaaaaaaa` |", first)
            self.assertIn("| 2025.3.4.1 | `253.32098.101` | 2026-09-25 | Unknown |", first)
            self.assertIn("| `0.157.1` | 2026.2.2 | `262.10315.187` | 2026-09-26 | `bbbbbbbbbbbb` |", first)
            self.assertNotIn("| `0.157.0` |", first)

    def test_only_complete_test_runs_are_recorded_as_passes(self):
        self.assertEqual(compatibility.classify("> Task :testClasses\nBUILD SUCCESSFUL", 0)[0], "error")
        self.assertEqual(compatibility.classify("> Task :test\n> Task :protocolTest\nBUILD SUCCESSFUL", 0)[0], "passed")
        self.assertEqual(compatibility.classify("> Task :compileKotlin FAILED\nBUILD FAILED", 1)[0], "failed")
        full_log = "> Task :test\n> Task :protocolTest\n> Task :codexCliTest\nPASS Codex CLI 0.157.1 app-server initialize\nBUILD SUCCESSFUL"
        self.assertEqual(compatibility.classify(full_log, 0, "0.157.1")[0], "passed")
        self.assertEqual(compatibility.classify(full_log.replace("PASS Codex CLI", "SKIP Codex CLI"), 0, "0.157.1")[0], "error")
        self.assertEqual(compatibility.classify("> Task :codexCliTest FAILED\nBUILD FAILED", 1, "0.157.1")[0], "failed")

    def test_result_records_full_test_suite_revision(self):
        with tempfile.TemporaryDirectory() as temporary:
            log = Path(temporary) / "compatibility.log"
            result = Path(temporary) / "result.json"
            log.write_text("> Task :test\n> Task :protocolTest\n> Task :codexCliTest\nPASS Codex CLI 0.157.1 app-server initialize\nBUILD SUCCESSFUL\n", encoding="utf-8")
            subprocess.run([
                sys.executable, str(SCRIPT), "result", "--idea", "2026.2.3", "--ai", "262.10968.147", "--cli", "0.157.1",
                "--test-suite-revision", "b" * 40, "--exit-code", "0", "--log", str(log),
                "--output", str(result),
            ], check=True, capture_output=True, text=True)
            recorded = json.loads(result.read_text(encoding="utf-8"))
            self.assertEqual(recorded["test_suite_revision"], "b" * 40)
            self.assertEqual(recorded["cli"], "0.157.1")
            self.assertEqual(recorded["status"], "passed")

    def test_merge_keeps_cli_and_ide_results_separate(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest_path = root / "manifest.json"
            readme = root / "README.md"
            results = root / "artifacts"
            results.mkdir()
            compatibility.write_json(manifest_path, {
                "schema": 2,
                "results": [{"idea": "2026.2.2", "ai": "262.10315.187", "status": "passed", "tested_at": "2026-09-25"}],
                "cli_results": [],
            })
            readme.write_text("# Project\n", encoding="utf-8")
            compatibility.write_json(results / "result.json", {
                "idea": "2026.2.2", "ai": "262.10315.187", "cli": "0.157.1",
                "status": "passed", "reason": "CLI app-server passed", "tested_at": "2026-09-26",
            })
            subprocess.run([
                sys.executable, str(SCRIPT), "merge", "--manifest", str(manifest_path),
                "--readme", str(readme), "--results-dir", str(results),
            ], check=True, capture_output=True, text=True)
            merged = compatibility.read_json(manifest_path)
            self.assertEqual(len(merged["results"]), 1)
            self.assertEqual([result["cli"] for result in merged["cli_results"]], ["0.157.1"])
            self.assertIn("| `0.157.1` |", readme.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
