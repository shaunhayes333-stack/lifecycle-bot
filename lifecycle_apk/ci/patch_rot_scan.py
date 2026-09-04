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
TEST_SRC = ROOT / "app/src/test/kotlin"


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
    immutable_false_branch = re.compile(
        r"\bval\s+(\w+)\s*=\s*false\b[\s\S]{0,800}?\bif\s*\(\s*\1\s*\)"
    )
    immutable_true_guard = re.compile(
        r"\bval\s+(\w+)\s*=\s*true\b[\s\S]{0,800}?\bif\s*\(\s*\1\s*\)"
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
        if immutable_false_branch.search(text):
            errors.append(f"{rel}: immutable false flag feeds an unreachable branch")
        if immutable_true_guard.search(text):
            errors.append(f"{rel}: immutable true flag feeds a redundant branch wrapper")

    # A prior Golden Tape assertion required production to retain a deleted
    # constant-false branch, turning a correct source cleanup into a red build.
    # Reject positive test contracts for the proven dead-patch sentinels while
    # still allowing assertFalse guards that prevent their return.
    stale_contract_names = (
        "v3OwnsMemes",
        "floorPromotionRequested6511",
        "tickProfitLockEligible",
        "skipOnChainCheck",
        "rpcRescue",
        "deterministicProviderFailure",
    )
    for path in TEST_SRC.rglob("*.kt"):
        for line_no, line in enumerate(path.read_text(errors="replace").splitlines(), 1):
            if "assertTrue" in line and any(name in line for name in stale_contract_names):
                errors.append(
                    f"{path.relative_to(ROOT)}:{line_no}: test positively requires proven dead patch scaffolding"
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
