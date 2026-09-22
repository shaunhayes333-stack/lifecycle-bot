#!/usr/bin/env python3
"""
V5.0.7216 — VAL REASSIGNMENT GUARD.

V5.0.7215 went red on three of these:

    Executor.kt:14604:66 Val cannot be reassigned
    Executor.kt:14605:62 Val cannot be reassigned
    Executor.kt:14606:76 Val cannot be reassigned

They were `ts.symbol = ...`, `ts.name = ...` and `ts.pairAddress = ...` inside a
new function taking `ts: TokenState`. I had "verified" those fields were mutable
by grepping Models.kt for `var symbol`, `var name`, `var pairAddress` and
finding all three — in a DIFFERENT class in the same file. In TokenState
(Models.kt:499) every one of them is a `val`, and that immutability is
load-bearing identity.

The lesson is not "grep more carefully". It is that a claim about a declaration
is checkable from source in under a second and CI was not checking it.

WHY SIMPLE NAMES ARE NOT ENOUGH. The first cut of this file indexed types by
simple name and dropped any name declared twice as ambiguous. It then passed
clean over the very code that had just failed to compile, because this module
declares TokenState TWICE:

    com.lifecyclebot.data.TokenState        (data class, Models.kt:499)
    com.lifecyclebot.engine.TokenState      (enum class, TradeAuthorizer.kt:40)

A guard that goes quiet on the exact case it was written for is worse than
none, so types are indexed by FULLY QUALIFIED name and each file's receivers
are resolved through its own imports, then its own package. That is how Kotlin
resolves them, and it is the only way to tell those two TokenStates apart.

WHAT IT CHECKS, AND WHAT IT DELIBERATELY DOES NOT. Full type inference is out of
scope and would be a liability — a guard that is sometimes wrong about working
code gets switched off. So:

  * Pass 1 indexes every TOP-LEVEL type by `package.Name` and the mutability of
    its own properties: primary-constructor `val`/`var` parameters, and
    body-level declarations at the type's own brace depth. Nested types' members
    are not attributed to the outer type.
  * Pass 2 resolves receivers whose type is stated EXPLICITLY — parameters
    `name: Type` and locals `val name: Type` — through the file's imports, then
    its package, then a unique module-wide match. Nothing is inferred from an
    initialiser.
  * It flags `name.prop = ...` and compound assignments where `prop` is a `val`
    of that resolved type.

Anything it cannot resolve with certainty it says nothing about: it will miss
some real errors, and it must never invent one. `?.` safe calls, `==`/`!=`/`>=`
comparisons, named arguments (`prop =` inside a call) and declarations
(`val prop =`) are all excluded.

Exit 1 on any hit. Every hit is a hard compile error.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app" / "src"

PACKAGE = re.compile(r"^\s*package\s+([\w.]+)")
IMPORT = re.compile(r"^\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?")
TYPE_HEAD = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:(?:public|private|internal|protected|abstract|open|sealed|data|value|inner|"
    r"annotation|enum|expect|actual|external|final)\s+)*"
    r"(?:class|object|interface)\s+([A-Z]\w*)"
)
PROP_DECL = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:(?:public|private|internal|protected|open|override|final|lateinit|const|"
    r"abstract)\s+)*"
    r"(val|var)\s+([a-z_]\w*)\s*[:=]"
)
FUN_HEAD = re.compile(r"^\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:\w+\s+)*fun\s+")
# `name: Type` or `name: some.pkg.Type` — the trailing segment is the simple name.
PARAM = re.compile(r"\b([a-z_]\w*)\s*:\s*(?:[\w.]*\.)?([A-Z]\w*)\b")
LOCAL_TYPED = re.compile(r"\bval\s+([a-z_]\w*)\s*:\s*(?:[\w.]*\.)?([A-Z]\w*)\b")
ASSIGN = re.compile(r"(?<![.\w?])([a-z_]\w*)\.([a-z_]\w*)\s*(=|\+=|-=|\*=|/=|%=)(?!=)")


def strip_line(line: str) -> str:
    """Drop the line comment and the bodies of string/char literals."""
    out = []
    i = 0
    n = len(line)
    while i < n:
        c = line[i]
        if c == "/" and i + 1 < n and line[i + 1] == "/":
            break
        if line.startswith('"""', i):
            j = line.find('"""', i + 3)
            i = n if j < 0 else j + 3
            continue
        if c == '"':
            i += 1
            while i < n:
                if line[i] == "\\":
                    i += 2
                    continue
                if line[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if c == "'":
            i += 1
            while i < n:
                if line[i] == "\\":
                    i += 2
                    continue
                if line[i] == "'":
                    i += 1
                    break
                i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def index_types(files):
    """
    Returns (props, by_simple):
      props[fqn]      -> {prop: 'val'|'var'}
      by_simple[name] -> set of fqns
    """
    props = {}
    by_simple = {}
    for path in files:
        lines = path.read_text(encoding="utf-8", errors="replace").split("\n")
        pkg = ""
        depth = 0
        paren = 0
        current = None          # fqn of the top-level type being collected
        body_depth = None       # brace depth of that type's own body
        in_block_comment = False

        for raw in lines:
            line = raw
            if in_block_comment:
                e = line.find("*/")
                if e < 0:
                    continue
                line = line[e + 2:]
                in_block_comment = False
            while True:
                s = line.find("/*")
                if s < 0:
                    break
                e = line.find("*/", s + 2)
                if e < 0:
                    line = line[:s]
                    in_block_comment = True
                    break
                line = line[:s] + " " + line[e + 2:]
            code = strip_line(line)

            mpk = PACKAGE.match(code)
            if mpk:
                pkg = mpk.group(1)

            mt = TYPE_HEAD.match(code)
            if mt and depth == 0:
                name = mt.group(1)
                current = f"{pkg}.{name}" if pkg else name
                body_depth = None
                props.setdefault(current, {})
                by_simple.setdefault(name, set()).add(current)
            elif mt and depth > 0:
                # A nested type: stop attributing properties to the outer type
                # until we come back out of it.
                current = current  # unchanged, but body_depth guard below skips
                pass

            # Collect properties: primary-constructor params (depth 0, inside
            # parens) or body members at the type's own body depth.
            if current is not None:
                mp = PROP_DECL.match(code)
                if mp:
                    in_ctor = depth == 0 and paren > 0
                    in_body = body_depth is not None and depth == body_depth
                    if in_ctor or in_body:
                        kind, prop = mp.group(1), mp.group(2)
                        prior = props[current].get(prop)
                        if prior is None:
                            props[current][prop] = kind
                        elif prior != kind:
                            props[current][prop] = "var"   # ambiguous: never report

            for ch in code:
                if ch == "(":
                    paren += 1
                elif ch == ")":
                    if paren > 0:
                        paren -= 1
                elif ch == "{":
                    depth += 1
                    if current is not None and body_depth is None and paren == 0:
                        body_depth = depth
                elif ch == "}":
                    depth -= 1
                    if body_depth is not None and depth < body_depth:
                        current = None
                        body_depth = None
    return props, by_simple


def resolve_map(path: Path, props, by_simple):
    """simple name -> fqn, using the file's imports then its package."""
    lines = path.read_text(encoding="utf-8", errors="replace").split("\n")
    pkg = ""
    aliases = {}
    for raw in lines[:400]:
        code = strip_line(raw)
        mpk = PACKAGE.match(code)
        if mpk:
            pkg = mpk.group(1)
            continue
        mi = IMPORT.match(code)
        if mi:
            fqn, alias = mi.group(1), mi.group(2)
            simple = alias or fqn.rsplit(".", 1)[-1]
            if fqn in props:
                aliases[simple] = fqn

    def resolve(simple):
        if simple in aliases:
            return aliases[simple]
        same_pkg = f"{pkg}.{simple}"
        if same_pkg in props:
            return same_pkg
        cands = by_simple.get(simple, set())
        if len(cands) == 1:
            return next(iter(cands))
        return None            # ambiguous — say nothing

    return resolve


def scan_file(path: Path, props, by_simple):
    """Yield (lineno, receiver, prop, fqn, text) for each val reassignment."""
    resolve = resolve_map(path, props, by_simple)
    hits = []
    lines = path.read_text(encoding="utf-8", errors="replace").split("\n")
    receivers = {}
    for lineno, raw in enumerate(lines, start=1):
        code = strip_line(raw)

        if FUN_HEAD.match(code):
            receivers = {}
        for name, simple in PARAM.findall(code):
            fqn = resolve(simple)
            if fqn:
                receivers[name] = fqn
        for name, simple in LOCAL_TYPED.findall(code):
            fqn = resolve(simple)
            if fqn:
                receivers[name] = fqn

        for recv, prop, _op in ASSIGN.findall(code):
            fqn = receivers.get(recv)
            if fqn is None:
                continue
            if props.get(fqn, {}).get(prop) == "val":
                hits.append((lineno, recv, prop, fqn, raw.strip()[:96]))
    return hits


def main() -> int:
    files = sorted(SRC.rglob("*.kt"))
    if not files:
        print("kotlin_val_assignment: no Kotlin sources found", file=sys.stderr)
        return 1

    props, by_simple = index_types(files)
    findings = []
    for f in files:
        for hit in scan_file(f, props, by_simple):
            findings.append((f,) + hit)

    if findings:
        print("kotlin_val_assignment: FAIL — assignment to a 'val' property:")
        for f, lineno, recv, prop, fqn, txt in findings:
            print(f"  {f.relative_to(ROOT.parent)}:{lineno}  {recv}.{prop} — "
                  f"{fqn}.{prop} is declared 'val'  |  {txt}")
        print()
        print("Kotlin cannot assign to a val. Either the property has to become a var")
        print("in its declaring class — which for an identity field like a mint or a")
        print("symbol is load-bearing immutability, not an obstacle — or the write does")
        print("not belong. 7215 lost a build to exactly this after a grep found")
        print("'var symbol' in the right FILE and the wrong CLASS.")
        return 1

    print(f"kotlin_val_assignment: {len(files)} file(s), {len(props)} type(s) indexed")
    print("kotlin_val_assignment: OK — no assignment to a val property")
    return 0


if __name__ == "__main__":
    sys.exit(main())
