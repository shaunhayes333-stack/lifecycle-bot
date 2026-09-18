#!/usr/bin/env python3
"""V5.0.7038 — fail the build on `SomeClass.method(...)` where SomeClass is a
`class`, not an `object`.

WHY THIS EXISTS. Three defects in one session, all the same mistake: grep a
NAME, act on it, never check what DECLARES it.

  7017  read pos.entryMcapUsd — that field is on Trade, not Position. I had
        grepped the field name and not its owner.
  7032  added parameters to finalizeSell and used them at a recordSell call
        site 260 lines away, inside applyFanoutAfterClaim — a different
        function. Two unresolved references.
  7036  called BotBrain.getBlendedWinRate() statically. BotBrain is a class;
        BotService constructs one locally and exposes no singleton. My grep was
        `^object X|^class X` and I never looked at which branch matched.

Each cost an 18-minute round trip. The compiler catches every one of them, so
this only saves the cycle — but at three in a row the cycle is the expensive
part.

WHAT IT CHECKS. For each Kotlin file changed in this diff, every `Name.member(`
where Name is declared exactly once in the repo as a non-object class. Kotlin
allows that only through a companion object, so the companion is checked for
the member before anything is reported.

DELIBERATELY NARROW. Only changed files, only unambiguous single-declaration
names, and it skips any name that appears in the same file as a local val/var,
a parameter, or an import alias — all of which make `Name.member(` legitimate.
A guard that cries wolf gets switched off, and then it is worth nothing.
"""
import os
import re
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = "lifecycle_apk/app/src/main/kotlin"

DECL_RE = re.compile(r"^\s*(?:@\w+\s+)*(?:public\s+|internal\s+|abstract\s+|sealed\s+|open\s+|data\s+)*"
                     r"(object|class|interface|enum class)\s+([A-Z][A-Za-z0-9_]*)")
# Group 1 is an optional fully-qualified package prefix. A call written as
# com.lifecyclebot.engine.BotBrain.getBlendedWinRate() needs no import and is
# resolvable on its own — the first version of this check only understood
# imports and same-package, so it missed the EXACT form the 7036 bug was
# written in. Capturing the prefix is what makes the receiver unambiguous.
CALL_RE = re.compile(r"(?:([a-z][\w.]*)\.)?\b([A-Z][A-Za-z0-9_]*)\.([a-z][A-Za-z0-9_]*)\s*\(")


def declarations():
    """name -> list of (kind, path). Built once over the whole source tree."""
    out = {}
    for root, _dirs, files in os.walk(os.path.join(REPO, SRC)):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            p = os.path.join(root, fn)
            try:
                lines = open(p, encoding="utf-8").read().split("\n")
            except Exception:
                continue
            for line in lines:
                m = DECL_RE.match(line)
                if m:
                    out.setdefault(m.group(2), []).append((m.group(1), p))
    return out


def companion_members(path, cls):
    """Members reachable statically via `cls`'s companion object.

    The companion's block is found by BRACE MATCHING, not by scanning a window.
    Both cheaper approaches were tried and both were wrong in opposite
    directions: a 4,000-character window missed BotService's companion and
    produced a false POSITIVE, and scanning to end-of-file made BotBrain's
    companion (declared at line 182 of a long class) swallow the entire class
    body, producing a false NEGATIVE on the very bug this check exists for.
    """
    try:
        text = open(path, encoding="utf-8").read()
    except Exception:
        return set()
    out = set()
    for m in re.finditer(r"companion\s+object[^{]*\{", text):
        i = m.end() - 1
        depth, j = 0, i
        while j < len(text):
            if text[j] == "{":
                depth += 1
            elif text[j] == "}":
                depth -= 1
                if depth == 0:
                    break
            j += 1
        block = text[i:j]
        out |= set(re.findall(r"\bfun\s+([a-z][A-Za-z0-9_]*)", block))
        out |= set(re.findall(r"\b(?:val|var)\s+([a-z][A-Za-z0-9_]*)", block))
    return out


def changed_files(base):
    try:
        out = subprocess.run(["git", "diff", "--name-only", base, "--", SRC],
                             cwd=REPO, capture_output=True, text=True, check=True).stdout
    except subprocess.CalledProcessError:
        return None
    return [f for f in out.split() if f.endswith(".kt")]


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "HEAD~1"
    files = changed_files(base)
    if files is None:
        print("static_call_check: SKIPPED — base '%s' unavailable; no check performed" % base)
        return 0
    if not files:
        print("static_call_check: no Kotlin files changed in this change")
        return 0

    decls = declarations()
    findings = []
    for rel in files:
        p = os.path.join(REPO, rel)
        if not os.path.exists(p):
            continue
        text = open(p, encoding="utf-8").read()
        # Strip comments and strings so prose cannot produce a finding.
        body = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        body = re.sub(r"//.*", "", body)
        body = re.sub(r'"""".*?"""', "", body, flags=re.S)
        body = re.sub(r'"(?:[^"\\]|\\.)*"', '""', body)
        # Names bound locally in this file are never a static receiver.
        local = set(re.findall(r"\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)", body))
        local |= set(re.findall(r"([A-Za-z_][A-Za-z0-9_]*)\s*:\s*[A-Z]", body))
        local |= set(re.findall(r"import\s+[\w.]+\s+as\s+(\w+)", text))
        # RESOLUTION, not heuristics. A repo declaration is only the thing being
        # called if Kotlin can actually see it from here: same package, or
        # explicitly imported. LiveTradeLogActivity calls Intent.createChooser()
        # on android.content.Intent while the repo separately declares an
        # unrelated `class Intent` in MemeExecutionIntent6621.kt — different
        # package, not imported, so it is not the receiver. Guessing from import
        # shapes missed the wildcard case; asking whether the symbol resolves
        # does not.
        this_pkg = (re.search(r"^package\s+([\w.]+)", text, re.M) or [None, ""])[1] \
            if re.search(r"^package\s+([\w.]+)", text, re.M) else ""
        imported_fqns = set(re.findall(r"^import\s+([\w.]+)", text, re.M))
        for prefix, name, member in set(CALL_RE.findall(body)):
            # An explicit package prefix resolves the receiver by itself; a bare
            # name only resolves via same-package or an import.
            if not prefix and name in local:
                continue
            d = decls.get(name)
            if not d or len(d) != 1:
                continue          # unknown, or ambiguous — say nothing
            kind, path = d[0]
            if kind == "object":
                continue
            if kind in ("interface", "enum class"):
                continue
            # Is the repo declaration even visible from this file?
            try:
                decl_pkg = (re.search(r"^package\s+([\w.]+)",
                                      open(path, encoding="utf-8").read(), re.M) or [None, ""])[1]
            except Exception:
                decl_pkg = ""
            resolvable = (
                prefix == decl_pkg
                or (not prefix and decl_pkg == this_pkg)
                or (not prefix and ("%s.%s" % (decl_pkg, name)) in imported_fqns)
            )
            if not resolvable:
                continue
            if member in companion_members(path, name):
                continue
            findings.append((rel, name, member, os.path.relpath(path, REPO)))

    print("static_call_check: %d changed Kotlin file(s) scanned" % len(files))
    if findings:
        print("")
        print("static_call_check: FAIL — static call on a `class` (needs an instance):")
        for rel, name, member, decl in sorted(set(findings)):
            print("  %s\n      %s.%s()  —  %s is declared `class` in %s" % (rel, name, member, name, decl))
        print("")
        print("7017, 7032 and 7036 were all this mistake: grep the name, act on it,")
        print("never check what declares it. Use an instance, add a companion, or")
        print("drop the call.")
        return 1

    print("static_call_check: OK — no static calls onto non-object classes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
