#!/usr/bin/env python3
"""Repository-wide guards for proven patch-stacking failure shapes.

This intentionally scans all production Kotlin, not a single hot file.  Add a
rule only after a concrete rot pattern is proven, so CI rejects regressions
without pretending every repeated strategy implementation is a defect.
"""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/kotlin"


def main() -> int:
    errors: list[str] = []
    files = list(SRC.rglob("*.kt"))

    terminal_mode_rot = re.compile(
        r"if\s*\(!position\.isPaper\)[\s\S]{0,400}?"
        r"if\s*\(isPaperMode\.get\(\)\)\s*\"paper\"\s*else\s*\"live\""
    )
    stale_merge_dead_branch = re.compile(
        r"val\s+(\w+)\s*=\s*false[\s\S]{0,500}?"
        r"if\s*\(\1\)[\s\S]{0,300}?stale merge context",
        re.IGNORECASE,
    )

    for path in files:
        text = path.read_text(errors="replace")
        rel = path.relative_to(ROOT)
        if terminal_mode_rot.search(text):
            errors.append(
                f"{rel}: live terminal re-reads mutable global paper mode; use immutable position mode"
            )
        if stale_merge_dead_branch.search(text):
            errors.append(
                f"{rel}: constant-false stale-merge branch retained in production"
            )

    artifacts = [
        p.relative_to(ROOT) for p in SRC.rglob("*")
        if p.is_file() and (p.name.endswith((".bak", ".old", "~")) or " copy." in p.name.lower())
    ]
    for artifact in artifacts:
        errors.append(f"{artifact}: backup/copy source artifact can create duplicate implementation drift")

    if errors:
        print("Patch-rot scan FAILED:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(f"Patch-rot scan passed ({len(files)} production Kotlin files)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
