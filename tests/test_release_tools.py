import hashlib
import io
import json
import subprocess
import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path

from scripts.release_tools import PAYLOADS, archive_files, check_path, package_release, verify_release


class ReleaseToolsTests(unittest.TestCase):
    def project(self, root):
        (root / '.gitignore').write_text('release/\n.cache/\ntarget/\ndist/\n')
        source = root / 'services/insurance-api/src/main/java/com/sam/insurance/ClaimsService.java'
        source.parent.mkdir(parents=True)
        source.write_text('class ClaimsService {}\n')
        subprocess.run(['git', 'init', '-q'], cwd=root, check=True)
        subprocess.run(['git', 'add', '.'], cwd=root, check=True)
        subprocess.run(['git', '-c', 'user.name=Release Test', '-c', 'user.email=release-test@example.invalid',
                        'commit', '-qm', 'fixture'], cwd=root, check=True)
        target = root / 'services/insurance-api/target'
        target.mkdir()
        with zipfile.ZipFile(target / 'insurance-api-0.2.0.jar', 'w') as jar:
            for name, body in {
                'META-INF/maven/com.sam/insurance-api/pom.properties': 'version=0.2.0\n',
                'BOOT-INF/classes/com/sam/insurance/ClaimsService.class': 'synthetic bytecode fixture',
            }.items():
                entry = zipfile.ZipInfo(name)
                entry.external_attr = 0o100644 << 16
                jar.writestr(entry, body)
        (target / 'bom.json').write_text(json.dumps({'bomFormat': 'CycloneDX', 'components': [{'name': 'fixture'}]}))
        dist = root / 'apps/claims-dashboard/dist'
        (dist / 'assets').mkdir(parents=True)
        (dist / 'index.html').write_text('<div>synthetic fixture</div>')
        (dist / 'assets/app.js').write_text('console.log("fixture")')

    def test_valid_candidate_is_verified_and_stale_files_are_preserved_outside_new_package(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.project(root)
            candidate = package_release(root)
            report = verify_release(root, candidate)
            self.assertTrue(report['source_matches_commit'])
            self.assertEqual(report['source_files'], 2)
            (candidate / 'stale-verification.json').write_text('old evidence')
            new = package_release(root)
            self.assertEqual({p.name for p in new.iterdir()}, PAYLOADS | {'SHA256SUMS'})
            self.assertEqual(len(list((root / '.cache/verification/release-packages').glob('*/stale-verification.json'))), 1)
            self.assertEqual(verify_release(root, new)['result'], 'passed')

    def test_source_tampering_is_rejected_even_when_checksums_are_recomputed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.project(root)
            candidate = package_release(root)
            path = candidate / 'turaco-insurance-0.2.0-source.tar.gz'
            files = archive_files(path)
            name = next(name for name in files if name.endswith('ClaimsService.java'))
            files[name] = b'changed after source commit\n'
            with tarfile.open(path, 'w:gz') as archive:
                for name, data in files.items():
                    member = tarfile.TarInfo(name)
                    member.size = len(data)
                    archive.addfile(member, io.BytesIO(data))
            (candidate / 'SHA256SUMS').write_text(''.join(
                hashlib.sha256((candidate / name).read_bytes()).hexdigest() + '  ' + name + '\n'
                for name in sorted(PAYLOADS)))
            with self.assertRaisesRegex(ValueError, 'Source contents'):
                verify_release(root, candidate)

    def test_git_line_ending_filters_do_not_change_canonical_source_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.project(root)
            name = 'services/insurance-api/mvnw.cmd'
            (root / '.gitattributes').write_text(name + ' text eol=crlf\n')
            (root / name).write_bytes(b'@echo off\nrem synthetic wrapper fixture\n')
            subprocess.run(['git', 'add', '.gitattributes', name], cwd=root, check=True)
            subprocess.run(['git', '-c', 'user.name=Release Test', '-c', 'user.email=release-test@example.invalid',
                            'commit', '-qm', 'line ending fixture'], cwd=root, check=True)
            candidate = package_release(root)
            contents = archive_files(candidate / 'turaco-insurance-0.2.0-source.tar.gz')
            canonical = subprocess.check_output(['git', 'show', 'HEAD:' + name], cwd=root)
            self.assertEqual(contents['turaco-insurance/' + name], canonical)
            self.assertEqual(verify_release(root, candidate)['result'], 'passed')

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
