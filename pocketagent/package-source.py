#!/usr/bin/env python3
"""Package reproducible PocketAgent source, without signing material or build/runtime caches.

Usage: python3 package-source.py /absolute/path/PocketAgent-source.zip
Run only after the source freeze. The APK's private signing key is never included.
"""
import argparse
import os
from pathlib import Path
import stat
import zipfile

EXCLUDED_DIRS = {'.git', '.signing', '__pycache__', 'node_modules', '.venv', '.tooling',
                 'build', 'android-tools', 'toolchains', '.ssh', '.aws', '.kube'}
SECRET_NAMES = {'.env', '.npmrc', '.netrc', '.git-credentials', 'credentials.json',
                'id_rsa', 'id_ed25519', 'id_ecdsa', 'id_dsa'}
SECRET_SUFFIXES = {'.jks', '.keystore', '.p12', '.pfx', '.pem', '.key', '.apk', '.pyc', '.pyo'}

def included(path):
    for part in path.parts:
        lower = part.lower()
        if lower in EXCLUDED_DIRS or lower in SECRET_NAMES or lower.startswith('.env.'):
            return False
        if lower.startswith('service-account') and lower.endswith('.json'):
            return False
    return path.suffix.lower() not in SECRET_SUFFIXES

def package(root, destination):
    root = root.resolve()
    destination = destination.resolve()
    if destination.exists():
        raise ValueError('Choose a new archive filename; existing files are not overwritten.')
    roots = [root / name for name in ('app', 'tests', 'docs', 'tools', 'branding', 'history')]
    roots.extend(path for path in root.iterdir() if path.is_file()
                 and (path.suffix in {'.md', '.sh', '.py'} or path.name in {'LICENSE', 'NOTICE', '.gitignore', '.gitattributes'}))
    selected = []
    for start in roots:
        if not start.exists():
            continue
        if start.is_file():
            selected.append(start)
            continue
        for folder, directories, filenames in os.walk(start, followlinks=False):
            directories[:] = sorted(name for name in directories
                                    if included((Path(folder) / name).relative_to(root))
                                    and not (Path(folder) / name).is_symlink())
            selected.extend(Path(folder) / name for name in sorted(filenames))
    selected = sorted(set(path for path in selected if included(path.relative_to(root))
                          and not path.is_symlink() and path.is_file()))
    for required in ['app/AndroidManifest.xml', 'build.sh', 'app/src/com/pocketagent/mobile/DeskActivity.java',
                     'app/lib/arm64-v8a/libproot.so', 'OPEN_SOURCE_NOTICES.md']:
        if root / required not in selected:
            raise ValueError('Required source component missing: ' + required)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + '.tmp')
    try:
        with zipfile.ZipFile(temporary, 'x', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for path in selected:
                relative = path.relative_to(root)
                info = zipfile.ZipInfo('pocketagent/' + relative.as_posix())
                info.date_time = (2026, 1, 1, 0, 0, 0)
                info.external_attr = (stat.S_IFREG | (0o755 if os.access(path, os.X_OK) else 0o644)) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, path.read_bytes())
            # Keep repository CI next to the app folder when the archive is extracted.
            workflow = root.parent / '.github/workflows/pocketagent.yml'
            if workflow.is_file() and not workflow.is_symlink():
                info = zipfile.ZipInfo('.github/workflows/pocketagent.yml')
                info.date_time = (2026, 1, 1, 0, 0, 0)
                info.external_attr = (stat.S_IFREG | 0o644) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, workflow.read_bytes())
        os.link(temporary, destination)  # Publish without overwriting an existing deliverable.
    finally:
        temporary.unlink(missing_ok=True)
    print(f'Saved {destination.name}: {len(selected)} source files, {destination.stat().st_size} bytes')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination', type=Path)
    arguments = parser.parse_args()
    package(Path(__file__).parent, arguments.destination)
