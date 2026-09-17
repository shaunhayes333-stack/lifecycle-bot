#!/usr/bin/env python3
"""Pre-push gate: catch identifiers this working diff DELETED that the tree still references.

V5.0.6946 shipped a compile error of exactly this shape: the DEXPAPRIKA constant was
removed from the DataSource enum while three `DataSource.DEXPAPRIKA` references survived.
Brace/paren balance cannot see that. This can.

Usage: removed_ref_check.py [git-rev]   (default: working tree vs HEAD)
Exit 1 if a deleted declaration still has live references.
"""
import re
import subprocess
import sys

REPO = "/home/user/lifecycle-bot"
SRC = "lifecycle_apk/app/src/main/kotlin"

# Declarations we care about. Enum constants are the dangerous invisible case.
DECL = re.compile(
    r"\b(?:fun|val|var|object|class|interface|const\s+val)\s+([A-Za-z_][A-Za-z0-9_]*)"
)
# A bare `FOO, BAR,` line inside an enum body — no keyword to key off.
ENUMLINE = re.compile(r"^\s*(?:[A-Z][A-Z0-9_]{2,}\s*,\s*)+[A-Z][A-Z0-9_]{2,}\s*,?\s*(?://.*)?$")


def diff(rev):
    args = ["git", "-C", REPO, "diff", "-U0"]
    if rev:
        args.append(rev)
    return subprocess.run(args, capture_output=True, text=True).stdout


def main():
    rev = sys.argv[1] if len(sys.argv) > 1 else None
    removed, added = set(), set()
    for line in diff(rev).splitlines():
        if line.startswith(("+++", "---", "@@")):
            continue
        if line[:1] not in "+-":
            continue
        body = line[1:]
        bucket = added if line[0] == "+" else removed
        # Member-level only. A `val mint` at 12 spaces is a local inside the
        # function being deleted, not a declaration anything else can reference.
        indent = len(body) - len(body.lstrip(" "))
        if indent <= 4:
            for m in DECL.finditer(body):
                bucket.add(m.group(1))
        if ENUMLINE.match(body):
            for tok in re.findall(r"[A-Z][A-Z0-9_]{2,}", body):
                bucket.add(tok)

    gone = removed - added
    if not gone:
        print("removed_ref_check: nothing deleted, clean")
        return 0

    bad = 0
    for name in sorted(gone):
        # Find surviving references that are real call/member syntax, not prose.
        pat = r"(?<![A-Za-z0-9_])" + re.escape(name) + r"(?![A-Za-z0-9_])"
        if rev and ".." in rev:
            # Retro-check a commit: search THAT commit's tree, not the working one.
            cmd = ["git", "-C", REPO, "grep", "-nP", pat, rev.split("..")[-1], "--", SRC + "/**/*.kt"]
            fields = 4
        else:
            cmd = ["grep", "-rnP", pat, "--include=*.kt", SRC]
            fields = 3
        proc = subprocess.run(cmd, cwd=REPO, capture_output=True, text=True)
        if proc.returncode not in (0, 1):
            print(f"removed_ref_check: grep FAILED for {name}: {proc.stderr.strip()[:200]}")
            return 2
        out = proc.stdout.splitlines()
        live = []
        for hit in out:
            parts = hit.split(":", fields - 1)
            if len(parts) < fields:
                continue
            text = parts[-1]
            t = text.strip()
            if t.startswith(("//", "*", "/*")):
                continue
            if DECL.search(text) or ENUMLINE.match(text):
                continue  # a surviving declaration of the same name
            live.append(hit)
        if live:
            bad += 1
            print(f"\nDELETED but still referenced: {name}")
            for hit in live[:8]:
                print("   " + hit[:160])
            if len(live) > 8:
                print(f"   ... and {len(live) - 8} more")

    if bad:
        print(f"\nremoved_ref_check: FAIL — {bad} deleted identifier(s) still referenced")
        return 1
    print(f"removed_ref_check: {len(gone)} identifier(s) deleted, no surviving references")
    return 0


if __name__ == "__main__":
    sys.exit(main())
