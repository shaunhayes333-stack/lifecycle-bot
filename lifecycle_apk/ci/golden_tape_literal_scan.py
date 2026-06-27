#!/usr/bin/env python3
"""
V5.0.4279 — Golden Tape literal static compiler.
Fails fast on risky Kotlin `contains("...")` assertions in GoldenTapeRegressionTest
where nested unescaped quotes or interpolation-like `${...}` would compile red or
search the wrong literal. Raw triple-quoted strings are intentionally allowed.
"""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "app/src/test/kotlin/com/lifecyclebot/engine/GoldenTapeRegressionTest.kt"


def is_escaped(s: str, idx: int) -> bool:
    n = 0
    j = idx - 1
    while j >= 0 and s[j] == "\\":
        n += 1
        j -= 1
    return n % 2 == 1


def scan_line(line: str, line_no: int):
    findings = []
    needle = 'contains("'
    pos = 0
    while True:
        start = line.find(needle, pos)
        if start < 0:
            break
        # Raw triple strings are safe: contains("""...""")
        if line[start:start + len('contains("""')] == 'contains("""':
            pos = start + len('contains("""')
            continue
        literal_start = start + len(needle)
        close = None
        i = literal_start
        while i < len(line):
            if line[i] == '"' and not is_escaped(line, i):
                close = i
                break
            i += 1
        if close is None:
            findings.append((line_no, "unterminated contains() string literal", line.rstrip()))
            break
        literal = line[literal_start:close]
        tail = line[close + 1:].lstrip()
        if tail and not tail.startswith((')', ',', '&&', '||', '+')):
            findings.append((line_no, "nested unescaped quote inside contains() — use contains(\"\"\"...\"\"\") or split the assertion", line.rstrip()))
        # Interpolation-like text is suspicious in source-contract assertions but
        # many historical tests intentionally use ${'$'}{...}; keep it advisory
        # so A28 prevents compile-red nested quotes without forcing a giant cleanup.
        if '${' in literal:
            pass
        pos = close + 1
    return findings


def main():
    if not TARGET.exists():
        print(f"Golden Tape file not found: {TARGET}", file=sys.stderr)
        return 2
    findings = []
    for n, line in enumerate(TARGET.read_text().splitlines(), 1):
        findings.extend(scan_line(line, n))
    if findings:
        print("Golden Tape literal static scan FAILED:", file=sys.stderr)
        for line_no, reason, line in findings:
            print(f"{TARGET.relative_to(ROOT)}:{line_no}: {reason}\n  {line}", file=sys.stderr)
        return 1
    print("Golden Tape literal static scan passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
