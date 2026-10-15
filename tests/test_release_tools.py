import hashlib
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path

from scripts.release_tools import PAYLOADS, archive_files, check_path, package_release, verify_release


class ReleaseToolsTests(unittest.TestCase):
    def make_candidate(self, directory):
        directory.mkdir()
        for name in PAYLOADS:
            (directory / name).write_bytes(b"candidate")
        sums = "".join(
            f"{hashlib.sha256((directory / name).read_bytes()).hexdigest()}  {name}\n"
            for name in sorted(PAYLOADS)
        )
        (directory / "SHA256SUMS").write_text(sums)
        return directory

    def test_archive_paths_reject_traversal_platform_separators_and_runtime_files(self):
        for name in ("../secret", r"folder\..\secret", ".env.local", "target/build.log"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                check_path(name)

    def test_archive_reader_rejects_links(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "source.tar.gz"
            with tarfile.open(path, "w:gz") as archive:
                link = tarfile.TarInfo("turaco-insurance/linked-file")
                link.type = tarfile.SYMTYPE
                link.linkname = "../../outside"
                archive.addfile(link)

            with self.assertRaisesRegex(ValueError, "link"):
                archive_files(path)

    def test_verifier_rejects_symlinked_candidate_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = self.make_candidate(root / "candidate")
            outside = root / "outside.json"
            outside.write_text("{}")
            (directory / "manifest.json").unlink()
            (directory / "manifest.json").symlink_to(outside)

            with self.assertRaisesRegex(ValueError, "non-regular"):
                verify_release(root, directory)

    def test_verifier_rejects_checksum_mismatch(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            directory = self.make_candidate(root / "candidate")
            (directory / "manifest.json").write_text("{}")

            with self.assertRaisesRegex(ValueError, "checksum"):
                verify_release(root, directory)

    def test_packager_rejects_tracked_and_untracked_worktree_changes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "services/insurance-api/src/main/java/ClaimsService.java"
            source.parent.mkdir(parents=True)
            source.write_text("class ClaimsService {}\n")
            subprocess.run(["git", "init", "-q"], cwd=root, check=True)
            subprocess.run(["git", "add", "."], cwd=root, check=True)
            subprocess.run(
                [
                    "git",
                    "-c",
                    "user.name=Release Test",
                    "-c",
                    "user.email=release-test@example.invalid",
                    "commit",
                    "-qm",
                    "fixture",
                ],
                cwd=root,
                check=True,
            )

            untracked = root / "untracked.txt"
            untracked.write_text("not part of HEAD\n")
            with self.assertRaisesRegex(ValueError, "working-tree changes"):
                package_release(root)

            untracked.unlink()
            source.write_text("changed source\n")
            with self.assertRaisesRegex(ValueError, "working-tree changes"):
                package_release(root)


if __name__ == "__main__":
    unittest.main()
