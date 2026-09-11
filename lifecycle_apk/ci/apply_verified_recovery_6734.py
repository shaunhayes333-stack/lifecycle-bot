"""Apply the exact reviewed 6734 source edit manifest; abort on any drift."""
import base64
import gzip
import hashlib
import json
import os
from pathlib import Path

EXPECTED = '4fc66a254b2487656d68e5cb4f7890c6f0e6eae8722bc353e27b24f2780fd191'
ROOT = Path.cwd().resolve()
PARTS = ROOT / 'lifecycle_apk/ci/recovery_6734'
encoded = ''.join((PARTS / f'part{i}.b64').read_text().strip() for i in range(8))
raw = gzip.decompress(base64.b64decode(encoded, validate=True))
if hashlib.sha256(raw).hexdigest() != EXPECTED:
    raise SystemExit('Recovery manifest digest mismatch; no source written')
manifest = json.loads(raw)
assert manifest['version'] == '5.0.6734'
contracts_raw = (PARTS / 'contracts.json').read_bytes()
if hashlib.sha256(contracts_raw).hexdigest() != 'a54f3ee884d273885275c9827046cafd50bdc722e10793da82a19e1c96f8da1d':
    raise SystemExit('Contract amendment digest mismatch; no source written')
contracts = json.loads(contracts_raw)
assert contracts['version'] == manifest['version']
manifest['files'].extend(contracts['files'])
raw = json.dumps(manifest, ensure_ascii=False, separators=(',', ':')).encode('utf-8')

def blob(data):
    return hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()

pending = []
seen = set()
for entry in manifest['files']:
    rel = entry['path']
    if rel in seen or rel.startswith(('/', '.git')) or '..' in Path(rel).parts:
        raise SystemExit('Unsafe or duplicate source path: ' + rel)
    seen.add(rel)
    path = ROOT / rel
    if not path.resolve().is_relative_to(ROOT) or path.is_symlink():
        raise SystemExit('Unsafe resolved path: ' + rel)
    before = path.read_bytes() if path.exists() else None
    actual = blob(before) if before is not None else None
    if actual != entry['before']:
        raise SystemExit(f'Source drift: {rel}: expected {entry["before"]}, got {actual}; no source written')
    after = None
    if entry['after'] is not None:
        lines = before.decode('utf-8').splitlines(keepends=True) if before is not None else []
        boundary = len(lines)
        for start, end, replacement in reversed(entry['edits']):
            if not 0 <= start <= end <= boundary:
                raise SystemExit('Invalid edit range: ' + rel)
            lines[start:end] = replacement.splitlines(keepends=True)
            boundary = start
        after = ''.join(lines).encode('utf-8')
        if blob(after) != entry['after']:
            raise SystemExit('Result digest mismatch: ' + rel + '; no source written')
    pending.append((path, after))

# Every before/after hash has been validated before the first mutation.
for path, after in pending:
    if after is None:
        path.unlink()
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(after)
out = Path(os.environ.get('RUNNER_TEMP', '/tmp')) / 'aate-recovery'
out.mkdir(parents=True, exist_ok=True)
(out / 'manifest.json').write_bytes(raw)
(out / 'paths.txt').write_text('\n'.join(entry['path'] for entry in manifest['files']) + '\n')
print(f'Applied {len(pending)} reviewed files; manifest SHA256={EXPECTED}')
