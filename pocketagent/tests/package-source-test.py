#!/usr/bin/env python3
"""Exercise source packaging on a disposable fixture, never the live release tree."""
import importlib.util
from pathlib import Path
import tempfile
import zipfile

source = Path(__file__).resolve().parents[1] / 'package-source.py'
spec = importlib.util.spec_from_file_location('pocketagent_source_package', source)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
with tempfile.TemporaryDirectory(prefix='pocketagent-source-package-test-') as temporary:
    root = Path(temporary) / 'fixture'
    keep = ['app/AndroidManifest.xml', 'build.sh', 'app/src/com/pocketagent/mobile/DeskActivity.java',
            'app/lib/arm64-v8a/libproot.so', 'OPEN_SOURCE_NOTICES.md', 'app/assets/setup.sh',
            'app/res/values/strings.xml', 'tests/sample-test.py', 'docs/setup.md', 'package-source.py',
            'tools/make_brand_icons.py', 'branding/mark.svg', 'history/releases.md']
    blocked = ['.signing/private.jks', 'app/assets/.env', 'app/assets/id_ed25519',
               'app/assets/credentials.json', 'app/assets/__pycache__/helper.pyc',
               'app/assets/toolchains/secret.txt', 'build/cache.java', 'tests/.env.production']
    for name in keep + blocked:
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('source-fixture' if name in keep else 'EXCLUDED-PRIVATE-FIXTURE')
    outside = Path(temporary) / 'outside.txt'
    outside.write_text('EXCLUDED-PRIVATE-FIXTURE')
    (root / 'app/assets/link.txt').symlink_to(outside)
    result = Path(temporary) / 'fixture-source.zip'
    module.package(root, result)
    with zipfile.ZipFile(result) as archive:
        assert set(archive.namelist()) == {'pocketagent/' + name for name in keep}, archive.namelist()
        for name in archive.namelist():
            assert b'EXCLUDED-PRIVATE-FIXTURE' not in archive.read(name)
    try:
        module.package(root, result)
    except ValueError:
        pass
    else:
        raise AssertionError('Existing deliverable was overwritten')
print('PASS source packaging: complete allowed source; signing, credentials, cache and symlinks excluded')
