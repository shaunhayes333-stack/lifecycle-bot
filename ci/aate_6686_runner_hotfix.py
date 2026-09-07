from pathlib import Path

p = Path('ci/aate_6686_live_authority_repair.py')
s = p.read_text()
old = '                        identity = saved.positionId.ifBlank { "persisted" },'
new = '                        identity = "persisted:${saved.savedAt}",'
if old not in s:
    raise SystemExit('6686 runner hotfix anchor missing')
p.write_text(s.replace(old, new, 1))
print('6686 runner persistence identity corrected')
