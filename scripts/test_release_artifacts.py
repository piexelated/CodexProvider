import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("release_artifacts", Path(__file__).with_name("release-artifacts.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseArtifactsTest(unittest.TestCase):
    def test_selects_latest_passing_marketplace_pair_per_branch(self):
        manifest = {"results": [
            {"idea": "2025.3.5", "ai": "253.1.2", "status": "passed"},
            {"idea": "2025.3.6.1", "ai": "253.3.4", "status": "passed", "marketplace_compatible": False},
            {"idea": "2025.3.6.1", "ai": "253.2.3", "status": "passed"},
            {"idea": "2026.2.2", "ai": "262.1.2", "status": "passed"},
            {"idea": "2026.2.3", "ai": "262.3.4", "status": "failed"},
        ]}
        self.assertEqual(release.release_pairs(manifest), [
            {"idea": "2025.3.6.1", "ai": "253.2.3"},
            {"idea": "2026.2.2", "ai": "262.1.2"},
        ])
        with self.assertRaises(ValueError):
            release.release_pairs({"results": []})

    def test_packages_tested_archive_with_checksum_and_download_index(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            with zipfile.ZipFile(source / "CodexProvider-1.0.zip", "w") as archive:
                archive.writestr("plugin.txt", "fixture plugin")
            output = root / "release"
            artifact = release.bundle(source, output, "2026.2.2", "262.1.2", "a" * 40)
            self.assertEqual(artifact.read_bytes(), (source / "CodexProvider-1.0.zip").read_bytes())
            checksum = release.hashlib.sha256(artifact.read_bytes()).hexdigest()
            self.assertEqual((output / f"{artifact.name}.sha256").read_text(), f"{checksum}  {artifact.name}\n")
            notes = release.release_notes(output, "owner/repo", "v1.0")
            self.assertIn(f"https://github.com/owner/repo/releases/download/v1.0/{artifact.name}", notes)
            self.assertIn("`aaaaaaaaaaaa`", notes)
            artifact.unlink()
            with self.assertRaises(ValueError):
                release.release_notes(output, "owner/repo", "v1.0")

    def test_rejects_missing_or_ambiguous_build_outputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(ValueError):
                release.bundle(root, root / "output", "2026.2.2", "262.1.2", "a" * 40)
            (root / "a.zip").touch()
            (root / "b.zip").touch()
            with self.assertRaises(ValueError):
                release.bundle(root, root / "output", "2026.2.2", "262.1.2", "a" * 40)


if __name__ == "__main__":
    unittest.main()
