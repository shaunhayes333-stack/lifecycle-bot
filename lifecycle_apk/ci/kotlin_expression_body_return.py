#!/usr/bin/env python3
"""
V5.0.7113 — EXPRESSION-BODY `return` GUARD.

V5.0.7111 and V5.0.7112 were both red for four minutes of CI each, on one line:

    e: LaneEntryFloorTuner7111.kt:175:30
       Returns are not allowed for functions with expression body.
       Use block body in '{...}'

    fun statusLine7111(lanes: List<String>): String = try {
        if (lanes.isEmpty()) return "lanes=0"      // <- illegal
        ...
    } catch (t: Throwable) { ... }

Every other CI validator in this directory is a Python scan over Kotlin SOURCE.
None of them compile anything, so a pure syntax error sails through all of them
and is only caught 4 minutes into the Gradle build — and then 7112 stacked on the
same broken file and burned another 4.

This is not a general Kotlin parser and does not pretend to be. It catches the
one mistake that actually happened, which is the mistake most likely to happen
again here: this codebase writes a lot of

    fun statusLine(): String = try { ... } catch (_: Throwable) { "unavailable" }

status accessors, and reaching for an early `return` inside one is natural and
always wrong.

Exits 1 on a hit. It is a gate, not a sweep — the compiler would reject it
anyway, and rejecting it in one second instead of four minutes is the point.
"""
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src")

# fun name(...): Type = try {        /  = when {  /  = buildString... {
EXPR_BODY = re.compile(
    r"^(\s*)(?:internal\s+|private\s+|public\s+|protected\s+|override\s+)*"
    r"fun\s+\w+\s*\(.*\)\s*:\s*[\w<>?, .]+\s*=\s*(?:try\s*\{|when\s*\{|buildString[^{]*\{)\s*$"
)
# `return@label` is a lambda return and is perfectly legal here.
BARE_RETURN = re.compile(r"\breturn\b(?!@)")


def main():
    bad = []
    scanned = 0
    for base, _dirs, files in os.walk(ROOT):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(base, fn)
            scanned += 1
            try:
                lines = open(path, encoding="utf-8", errors="replace").read().split("\n")
            except Exception:
                continue
            for i, line in enumerate(lines):
                if not EXPR_BODY.match(line):
                    continue
                depth = 1
                j = i + 1
                while j < len(lines) and depth > 0:
                    stripped = lines[j].strip()
                    if not stripped.startswith("//"):
                        if BARE_RETURN.search(lines[j]) and depth > 0:
                            rel = os.path.relpath(path, os.path.join(ROOT, "..", "..", ".."))
                            bad.append((rel, j + 1, lines[i].strip()[:70], stripped[:80]))
                            break
                    depth += lines[j].count("{") - lines[j].count("}")
                    j += 1

    print(f"kotlin_expression_body_return: {scanned} Kotlin file(s) scanned")
    if not bad:
        print("kotlin_expression_body_return: OK — no expression body contains a bare return")
        return 0
    print("\nkotlin_expression_body_return: FAIL — 'Returns are not allowed for")
    print("functions with expression body'. Use a block body '{ ... }'.\n")
    for path, ln, decl, src in bad:
        print(f"  {path}:{ln}")
        print(f"        declared: {decl}")
        print(f"        return:   {src}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
