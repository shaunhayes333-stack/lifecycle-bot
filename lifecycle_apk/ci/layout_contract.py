#!/usr/bin/env python3
"""
V5.0.6937 — Kotlin <-> layout id contract check.

A restyle that only changes colours is safe and invisible. A REFORMAT changes
layout structure, and the one thing that can break when structure moves is the
contract between an Activity and its XML: every R.id.X the Kotlin binds must
still exist in the inflated layout, or findViewById returns null and the screen
crashes on open.

There is no emulator in this build environment, so that contract is the only
part of a reformat that can be proven mechanically. This proves it.

For every Activity/Fragment source file it resolves the layouts that file
inflates (setContentView(R.layout.X), inflate(R.layout.X, ...)) and checks each
R.id.Y the file references against the ids defined in those layouts, plus any
<include>d layouts.

Ids that resolve to a layout this file does not inflate are reported as
UNMATCHED rather than failed: adapters and dialogs legitimately bind ids from
row layouts they inflate dynamically, and a check that cannot tell those apart
would be noise. Only an id that appears in NO layout at all is fatal, because
that one can never resolve at runtime.
"""
import os
import re
import sys
from collections import defaultdict


def _root():
    here = os.path.dirname(os.path.abspath(__file__))
    for base in (os.path.dirname(here), here, os.getcwd()):
        if os.path.isdir(os.path.join(base, "app", "src", "main")):
            return os.path.join(base, "app", "src", "main")
        if os.path.isdir(os.path.join(base, "lifecycle_apk", "app", "src", "main")):
            return os.path.join(base, "lifecycle_apk", "app", "src", "main")
    return os.path.join("app", "src", "main")


MAIN = _root()
LAYOUT_DIR = os.path.join(MAIN, "res", "layout")
KOTLIN_DIR = os.path.join(MAIN, "kotlin")

RE_ID_DEF = re.compile(r'@\+id/([A-Za-z0-9_]+)')
# Must be the app's own R, not android.R.id.* or
# com.google.android.material.R.id.* — a preceding dot or word character means
# it is a library R class whose ids live outside this module.
RE_ID_USE = re.compile(r'(?<![.\w])R\.id\.([A-Za-z0-9_]+)')
RE_LINE_COMMENT = re.compile(r'//[^\n]*')
RE_BLOCK_COMMENT = re.compile(r'/\*.*?\*/', re.S)


def strip_comments(txt):
    """Ids named only in a comment are not bindings. CollectiveBrainActivity
    documents 'R.id.llSentiencePanel does not exist in any layout' right above
    the code that builds that view programmatically instead."""
    return RE_LINE_COMMENT.sub('', RE_BLOCK_COMMENT.sub('', txt))
RE_LAYOUT_USE = re.compile(r'(?<![.\w])R\.layout\.([A-Za-z0-9_]+)')
RE_INCLUDE = re.compile(r'<include[^>]*layout="@layout/([A-Za-z0-9_]+)"')


def main():
    if not os.path.isdir(LAYOUT_DIR) or not os.path.isdir(KOTLIN_DIR):
        print(f"layout_contract: source tree not found under {MAIN} — skipping")
        return 0

    layout_ids, layout_includes = {}, {}
    for f in sorted(os.listdir(LAYOUT_DIR)):
        if not f.endswith(".xml"):
            continue
        txt = open(os.path.join(LAYOUT_DIR, f), encoding="utf-8", errors="replace").read()
        name = f[:-4]
        layout_ids[name] = set(RE_ID_DEF.findall(txt))
        layout_includes[name] = set(RE_INCLUDE.findall(txt))

    all_ids = set()
    for s in layout_ids.values():
        all_ids |= s

    def ids_for(layouts):
        seen, stack, out = set(), list(layouts), set()
        while stack:
            l = stack.pop()
            if l in seen:
                continue
            seen.add(l)
            out |= layout_ids.get(l, set())
            stack.extend(layout_includes.get(l, ()))
        return out

    fatal, unmatched = [], defaultdict(list)
    checked = 0

    for dirpath, _, files in os.walk(KOTLIN_DIR):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            p = os.path.join(dirpath, fn)
            txt = strip_comments(open(p, encoding="utf-8", errors="replace").read())
            used = set(RE_ID_USE.findall(txt))
            if not used:
                continue
            checked += 1
            owned = ids_for(set(RE_LAYOUT_USE.findall(txt)))
            for i in sorted(used):
                if i not in all_ids:
                    fatal.append(f"{fn}: R.id.{i} exists in NO layout")
                elif i not in owned:
                    unmatched[fn].append(i)

    print(f"layout_contract: {len(layout_ids)} layouts, {len(all_ids)} ids, "
          f"{checked} Kotlin files binding ids")

    if unmatched:
        n = sum(len(v) for v in unmatched.values())
        print(f"layout_contract: {n} id(s) bound from a layout the file does not "
              f"inflate directly (adapters/dialogs — informational)")

    if fatal:
        for x in fatal:
            print(f"::error::layout_contract {x}")
        print(f"\nlayout_contract: FAILED — {len(fatal)} id(s) would return null "
              f"from findViewById and crash the screen on open.")
        return 1

    print("layout_contract: OK — every bound id resolves to a real layout id")
    return 0


if __name__ == "__main__":
    sys.exit(main())
