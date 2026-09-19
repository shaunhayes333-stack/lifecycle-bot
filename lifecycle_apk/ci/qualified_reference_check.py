#!/usr/bin/env python3
"""
V5.0.7078 — do the fully-qualified com.lifecyclebot.* references actually exist?

WHY THIS EXISTS. V5.0.7075 wrote:

    com.lifecyclebot.engine.ConfigStore.load(ctx)

ConfigStore is declared in com.lifecyclebot.data. One wrong package segment,
inside a `try { } catch (_: Throwable) { }` that cannot catch a compile error,
and builds 7075, 7076 and 7077 all failed on that single line — 54 minutes of CI
and three builds the operator never received.

None of the other validators could have caught it. They check brace balance,
comment balance, static-call shape, dead code, unit crossings and telemetry
placement. Not one of them resolves a symbol, and this environment has no
Android SDK, so "validators green" has never meant "it compiles". This closes
the specific gap that produced the failure: a fully-qualified name is a claim
about WHERE a declaration lives, and that claim is checkable from source alone.

WHAT IT CHECKS. For every `com.lifecyclebot.<pkg>.<Symbol>` written in Kotlin,
the package directory must contain a file declaring `<Symbol>` as an object,
class, interface, enum, annotation or typealias. A name whose declaration sits
in a DIFFERENT lifecyclebot package is the exact 7075 defect and is reported
with the package it is actually in, so the fix is the message.

WHAT IT DELIBERATELY DOES NOT CHECK, so it cannot produce false failures:
  · member names. `Foo.bar()` needs a type checker; only `Foo`'s home is tested.
  · nested and companion types. A `<Symbol>` not found as a top-level
    declaration anywhere in the module is SKIPPED rather than failed, because it
    may legitimately be nested, generated (R, BuildConfig) or an inner class.
    This validator only fails when the declaration is found somewhere else,
    which is unambiguous.
  · references in comments, since a package path named in prose is not a
    reference. Line comments and block comments are stripped first.
"""
import os
import re
import subprocess
import sys

MODULE_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(MODULE_ROOT, "app", "src", "main", "kotlin", "com", "lifecyclebot")
REPO_ROOT = os.path.dirname(MODULE_ROOT)

QUALIFIED = re.compile(r"\bcom\.lifecyclebot\.((?:[a-z_][A-Za-z0-9_]*\.)+)([A-Z][A-Za-z0-9_]*)")
DECL = re.compile(
    r"^\s*(?:@\w+\s+)*(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+|data\s+|value\s+|enum\s+|annotation\s+)*"
    r"(?:object|class|interface|typealias)\s+([A-Z][A-Za-z0-9_]*)",
    re.M,
)

# Generated or platform-provided names that have no declaring .kt file.
EXEMPT = {"R", "BuildConfig"}


def strip_comments(text):
    out = re.sub(r"//[^\n]*", "", text)
    # Kotlin block comments nest; a non-greedy strip is enough here because the
    # only cost of over- or under-stripping is a missed check, never a false one.
    return re.sub(r"/\*.*?\*/", "", out, flags=re.S)


def changed_files():
    try:
        base = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=REPO_ROOT,
            capture_output=True, text=True, check=True).stdout.strip()
        out = subprocess.run(
            ["git", "diff", "--name-only", base, "--", "*.kt"], cwd=REPO_ROOT,
            capture_output=True, text=True, check=True).stdout.split()
        staged = subprocess.run(
            ["git", "diff", "--cached", "--name-only", "--", "*.kt"], cwd=REPO_ROOT,
            capture_output=True, text=True, check=True).stdout.split()
        return sorted({p for p in out + staged})
    except Exception:
        return []


def main():
    scope = "--all" in sys.argv
    # Where every top-level declaration lives: name -> set of packages.
    homes = {}
    all_files = []
    for base, _d, names in os.walk(SRC):
        for n in names:
            if not n.endswith(".kt"):
                continue
            p = os.path.join(base, n)
            all_files.append(p)
            rel = os.path.relpath(base, SRC).replace(os.sep, ".")
            pkg = "" if rel == "." else rel
            try:
                text = open(p, encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            for sym in DECL.findall(text):
                homes.setdefault(sym, set()).add(pkg)

    if scope:
        targets = all_files
    else:
        targets = [os.path.join(REPO_ROOT, f) for f in changed_files()]
        targets = [t for t in targets if os.path.isfile(t) and t.startswith(SRC)]
        if not targets:
            targets = all_files

    problems = []
    checked = 0
    for p in targets:
        try:
            text = strip_comments(open(p, encoding="utf-8", errors="replace").read())
        except OSError:
            continue
        for pkg_dots, sym in set(QUALIFIED.findall(text)):
            pkg = pkg_dots.rstrip(".")
            if sym in EXEMPT:
                continue
            checked += 1
            where = homes.get(sym)
            if where is None:
                # Not a top-level declaration anywhere: nested, generated or
                # inner. Unverifiable without a type checker, so not a failure.
                continue
            if pkg in where:
                continue
            problems.append((os.path.relpath(p, REPO_ROOT), pkg, sym, sorted(where)))

    if problems:
        print("qualified_reference_check: FAIL — fully-qualified name(s) point at the wrong package\n")
        for rel, pkg, sym, where in sorted(problems):
            print("  %s" % rel)
            print("      written:  com.lifecyclebot.%s.%s" % (pkg, sym))
            print("      declared: %s" % ", ".join(
                "com.lifecyclebot.%s.%s" % (w, sym) if w else "com.lifecyclebot.%s" % sym
                for w in where))
        print("\nThis is the V5.0.7075 defect: `com.lifecyclebot.engine.ConfigStore`")
        print("against a declaration in com.lifecyclebot.data. It compiles to")
        print("`Unresolved reference` and no try/catch can save it.")
        return 1

    print("qualified_reference_check: %d qualified reference(s) checked in %d file(s)"
          % (checked, len(targets)))
    print("qualified_reference_check: OK — every lifecyclebot package path resolves")
    return 0


if __name__ == "__main__":
    sys.exit(main())
