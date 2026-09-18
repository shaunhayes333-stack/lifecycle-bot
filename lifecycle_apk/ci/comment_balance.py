#!/usr/bin/env python3
"""V5.0.6979 — catch unbalanced NESTED block comments in Kotlin sources.

WHY THIS EXISTS
===============
V5.0.6977 broke the build with a single character sequence inside a KDoc:

    * structural rather than cosmetic: the restyle was applied to `res/layout/*.xml`
    *     across ui/*.kt

Kotlin block comments NEST. Unlike C and Java, a `/*` inside a comment opens a
new level. So each of those glob paths opened a nested comment, and the KDoc's
closing `*/` closed only the innermost one. The file's entire remainder became
comment text:

    e: .../ui/AateUi.kt:349:1 Unclosed comment
    e: .../ui/MainActivity.kt:4333:28 Unresolved reference: AateUi   (x40)

The pre-push check in use at the time balanced (), {} and [] across the diff
and reported net 0 — correctly, because the defect is not in those delimiters.
Nothing looked at comment depth, and with no local Android SDK the break was
only visible ~18 minutes later in CI.

Writing a file path with a wildcard inside a doc comment is a completely
natural thing to do while documenting a codebase, so this will happen again
unless something checks for it.

WHAT IT CHECKS
==============
Simulates Kotlin's scanner state machine over each file: block comments (with
nesting), line comments, string literals, raw strings and char literals, so a
`/*` inside a string or a `//` comment is not miscounted. Reports any file
whose block-comment depth is non-zero at EOF, and names the line where the
outermost unclosed comment began.

Exit code 1 if any file is unbalanced.
"""

from __future__ import annotations

import sys
from pathlib import Path


def unclosed_comment_line(text: str) -> int | None:
    """Return the 1-based line where an unclosed block comment starts, else None."""
    i, n = 0, len(text)
    line = 1
    depth = 0
    open_lines: list[int] = []

    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""

        if c == "\n":
            line += 1
            i += 1
            continue

        if depth > 0:
            if c == "/" and nxt == "*":
                depth += 1
                open_lines.append(line)
                i += 2
                continue
            if c == "*" and nxt == "/":
                depth -= 1
                open_lines.pop()
                i += 2
                continue
            i += 1
            continue

        # depth == 0 — ordinary code
        if c == "/" and nxt == "*":
            depth = 1
            open_lines.append(line)
            i += 2
            continue
        if c == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if text.startswith('"""', i):
            i += 3
            while i < n and not text.startswith('"""', i):
                if text[i] == "\n":
                    line += 1
                i += 1
            i += 3
            continue
        if c == '"':
            i += 1
            while i < n and text[i] != '"':
                if text[i] == "\\":
                    i += 1
                elif text[i] == "\n":
                    line += 1
                    break
                i += 1
            i += 1
            continue
        if c == "'":
            i += 1
            while i < n and text[i] != "'":
                if text[i] == "\\":
                    i += 1
                elif text[i] == "\n":
                    line += 1
                    break
                i += 1
            i += 1
            continue

        i += 1

    return open_lines[0] if depth > 0 else None


def main(argv: list[str]) -> int:
    roots = [Path(a) for a in argv[1:]] or [Path("lifecycle_apk/app/src")]
    files: list[Path] = []
    for r in roots:
        if r.is_file():
            files.append(r)
        else:
            files.extend(sorted(r.rglob("*.kt")))

    bad: list[tuple[Path, int]] = []
    for f in files:
        try:
            text = f.read_text(encoding="utf-8", errors="replace")
        except OSError as e:
            print(f"SKIP {f}: {e}")
            continue
        start = unclosed_comment_line(text)
        if start is not None:
            bad.append((f, start))

    print(f"comment_balance: scanned {len(files)} Kotlin files")
    if not bad:
        print("comment_balance: OK — every block comment closes")
        return 0

    print(f"comment_balance: {len(bad)} FILE(S) WITH AN UNCLOSED BLOCK COMMENT")
    for f, start in bad:
        print(f"  {f}:{start}  block comment opened here is never closed")
        print("    Kotlin block comments NEST — a '/*' inside a comment (e.g. a")
        print("    glob path like ui/*.kt) opens another level that needs its own '*/'.")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
