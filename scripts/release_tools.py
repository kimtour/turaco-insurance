"""Build and inspect synthetic release candidates without trusting working-copy files."""
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import tarfile
import tempfile
from uuid import uuid4
import zipfile

VERSION = '0.2.0'
ROOT = Path(__file__).resolve().parents[1]
PAYLOADS = {f'turaco-insurance-{VERSION}-source.tar.gz', f'insurance-api-{VERSION}.jar',
            f'claims-dashboard-{VERSION}.tar.gz', 'java-sbom.cdx.json', 'manifest.json'}
FORBIDDEN = {'.git', '.cache', '.venv', '__pycache__', '.pytest_cache', 'node_modules', 'target', 'release', 'test-results'}


def git(root, *args):
    return subprocess.check_output(['git', *args], cwd=root)


def check_path(name):
    if not name or '\0' in name or '\\' in name or re.match(r'^[A-Za-z]:', name):
        raise ValueError('Unsafe archive path')
    path = PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts or FORBIDDEN.intersection(path.parts):
        raise ValueError('Unsafe or excluded archive path')
    if any((p == '.env' or p.startswith('.env.')) and p != '.env.example' for p in path.parts):
        raise ValueError('Private environment file in source')
    if path.suffix in {'.key', '.pem', '.pid', '.sqlite', '.sqlite3', '.db', '.log'}:
        raise ValueError('Private/runtime file in archive')


def archive_files(path):
    files = {}
    with tarfile.open(path, 'r:gz') as archive:
        for member in archive:
            check_path(member.name)
            if member.isdir():
                continue
            if not member.isfile() or member.name in files:
                raise ValueError('Archive contains a link, special file or duplicate')
            files[member.name] = archive.extractfile(member).read()
    return files


def verify_release(root, directory):
    root, directory = Path(root), Path(directory)
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError('Release candidate must be a real directory')
    if {p.name for p in directory.iterdir()} != PAYLOADS | {'SHA256SUMS'}:
        raise ValueError('Release inventory differs from the required six files')
    for name in PAYLOADS | {'SHA256SUMS'}:
        path = directory / name
        if path.is_symlink() or not path.is_file():
            raise ValueError('Release candidate contains a missing or non-regular file')
    hashes = {}
    for line in (directory / 'SHA256SUMS').read_text().splitlines():
        match = re.fullmatch(r'([a-f0-9]{64})  (.+)', line)
        if not match or match[2] not in PAYLOADS or match[2] in hashes:
            raise ValueError('Invalid or duplicate checksum inventory')
        digest, name = match.groups()
        file = directory / name
        if file.is_symlink() or not file.is_file() or hashlib.sha256(file.read_bytes()).hexdigest() != digest:
            raise ValueError('Release checksum mismatch')
        hashes[name] = digest
    if set(hashes) != PAYLOADS:
        raise ValueError('Missing checksum entry')
    manifest = json.loads((directory / 'manifest.json').read_text())
    commit = manifest.get('commit') if isinstance(manifest, dict) else None
    if (not isinstance(manifest, dict) or manifest.get('version') != VERSION
            or manifest.get('classification') != 'synthetic-local-reference'
            or manifest.get('live_payments') is not False
            or not isinstance(commit, str) or not re.fullmatch(r'[a-f0-9]{40}', commit)):
        raise ValueError('Invalid release identity/classification')
    source = archive_files(directory / f'turaco-insurance-{VERSION}-source.tar.gz')
    tracked = git(root, 'ls-tree', '-r', '--name-only', '-z', commit).decode().strip('\0').split('\0')
    if set(source) != {'turaco-insurance/' + name for name in tracked}:
        raise ValueError('Source inventory differs from the named commit')
    for name in tracked:
        if source['turaco-insurance/' + name] != git(root, 'show', commit + ':' + name):
            raise ValueError('Source contents differ from the named commit')
    ui = archive_files(directory / f'claims-dashboard-{VERSION}.tar.gz')
    if 'claims-dashboard/index.html' not in ui or not any('/assets/' in name for name in ui):
        raise ValueError('Dashboard production build is missing')
    with zipfile.ZipFile(directory / f'insurance-api-{VERSION}.jar') as jar:
        entries = jar.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)):
            raise ValueError('Java application archive contains duplicate paths')
        for entry in entries:
            check_path(entry.filename)
            mode = entry.external_attr >> 16
            if stat.S_ISLNK(mode) or (mode and not (entry.is_dir() or stat.S_ISREG(mode))):
                raise ValueError('Java application archive contains a link or special file')
        properties = jar.read('META-INF/maven/com.sam/insurance-api/pom.properties').decode()
        if (f'version={VERSION}' not in properties.splitlines()
                or 'BOOT-INF/classes/com/sam/insurance/ClaimsService.class' not in names):
            raise ValueError('Java application/version is missing')
        own = [jar.read(name) for name in jar.namelist() if name.startswith('BOOT-INF/classes/') and not name.endswith('/')]
    sbom = json.loads((directory / 'java-sbom.cdx.json').read_text())
    components = sbom.get('components') if isinstance(sbom, dict) else None
    if (not isinstance(sbom, dict) or sbom.get('bomFormat') != 'CycloneDX'
            or not isinstance(components, list) or not components
            or any(not isinstance(component, dict) for component in components)):
        raise ValueError('Dependency inventory is missing')
    # Never print values. This bounded check supplements source provenance, not a universal secret scan.
    secrets = []
    environment = root / '.env'
    if environment.exists():
        for line in environment.read_text().splitlines():
            if '=' not in line or line.startswith('#'):
                continue
            key, value = line.split('=', 1)
            if any(part in key for part in ('PASSWORD', 'TOKEN', 'SECRET', 'API_KEY', 'ENCRYPTION_KEY')) and len(value) >= 16:
                secrets.append(value.encode())
    contents = list(source.values()) + list(ui.values()) + own + [(directory / name).read_bytes() for name in PAYLOADS]
    if any(secret in content for secret in secrets for content in contents):
        raise ValueError('Generated local secret value found; value withheld')
    return {'result': 'passed', 'manifest': manifest, 'sha256': hashes, 'source_files': len(source),
            'dashboard_files': len(ui), 'sbom_components': len(components),
            'source_matches_commit': True, 'archive_exclusions': 'passed', 'known_local_secret_matches': 0,
            'scope': 'Synthetic artifact integrity/provenance; not production certification or an exhaustive credential scan'}


def package_release(root=ROOT):
    root = Path(root)
    if git(root, 'status', '--porcelain', '--untracked-files=all').strip():
        raise ValueError('Commit or remove working-tree changes before packaging; source must match HEAD')
    commit = git(root, 'rev-parse', 'HEAD').decode().strip()
    tracked = git(root, 'ls-tree', '-r', '--name-only', '-z', commit).decode().strip('\0').split('\0')
    if not any(name.endswith('/ClaimsService.java') for name in tracked):
        raise ValueError('Expected application source is missing')
    for name in tracked:
        check_path(name)
    output = root / 'release'
    if output.is_symlink():
        raise ValueError('Release output directory must not be a symlink')
    output.mkdir(exist_ok=True)
    if not output.is_dir():
        raise ValueError('Release output path must be a directory')
    target = output / VERSION
    if target.is_symlink():
        raise ValueError('Release destination must not be a symlink')
    jar = root / f'services/insurance-api/target/insurance-api-{VERSION}.jar'
    sbom = root / 'services/insurance-api/target/bom.json'
    dashboard = root / 'apps/claims-dashboard/dist'
    if any(path.is_symlink() or not path.is_file() for path in (jar, sbom)):
        raise ValueError('Build the Java application and SBOM before packaging')
    if dashboard.is_symlink() or not dashboard.is_dir():
        raise ValueError('Build the dashboard before packaging')
    with tempfile.TemporaryDirectory(prefix='.candidate-', dir=output) as temporary:
        stage = Path(temporary)
        archive = git(root, 'archive', '--format=tar', '--prefix=turaco-insurance/', commit)
        with tarfile.open(stage / f'turaco-insurance-{VERSION}-source.tar.gz', 'w:gz') as packed:
            with tarfile.open(fileobj=io.BytesIO(archive), mode='r:') as source:
                for member in source:
                    if member.isdir():
                        continue
                    check_path(member.name)
                    if not member.isfile():
                        raise ValueError('Tracked links/special files cannot be packaged')
                    packed.addfile(member, source.extractfile(member))
        shutil.copyfile(jar, stage / f'insurance-api-{VERSION}.jar')
        shutil.copyfile(sbom, stage / 'java-sbom.cdx.json')
        with tarfile.open(stage / f'claims-dashboard-{VERSION}.tar.gz', 'w:gz') as archive:
            archive.add(dashboard, arcname='claims-dashboard')
        (stage / 'manifest.json').write_text(json.dumps({'version': VERSION, 'commit': commit,
            'classification': 'synthetic-local-reference', 'live_payments': False}, indent=2) + '\n')
        (stage / 'SHA256SUMS').write_text(''.join(hashlib.sha256((stage / name).read_bytes()).hexdigest() + '  ' + name + '\n' for name in sorted(PAYLOADS)))
        verify_release(root, stage)
        # Preserve prior candidates, but never mix stale verification files into a new package.
        if target.exists():
            backup = root / '.cache/verification/release-packages' / uuid4().hex
            backup.parent.mkdir(parents=True, exist_ok=True)
            target.rename(backup)
        shutil.move(str(stage), str(target))
    return target
