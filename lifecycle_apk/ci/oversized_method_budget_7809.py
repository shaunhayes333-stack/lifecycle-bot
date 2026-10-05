#!/usr/bin/env python3
"""V5.0.7809 — fail the build when any oversized Kotlin method grows.

WHY THIS EXISTS. 5.0.7807 crashed on the PIN screen with
  java.lang.VerifyError: Verifier rejected class FinalDecisionGate:
  FinalDecision FinalDecisionGate.evaluate(...)
after a single extra call expression was added inside evaluate().
fdg_evaluate_budget_scan.py (7720) counts only val/var declarations and
FinalDecision(...) constructions, so a declaration-free call passed it.
The same ART limit applies to every method of this size — BotService
processTokenCycle/botLoop, Executor liveBuy/liveSell/recordTrade — and the
CI compiler cannot see it; only the device can.

RULE. Every method whose code body exceeds MIN_LINES non-comment lines is
pinned to its code-token count in oversized_method_budget_7809.json, taken
from 5.0.7808 (proven to boot on the operator's device). A pinned method may
shrink but never grow. New logic goes in a private helper called with one
line that REPLACES an existing line, or in a separate object. A method that
newly crosses MIN_LINES also fails.

`--rebaseline` rewrites the JSON (only after an on-device boot proof).
Exit 1 on a breach.
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(os.path.dirname(HERE), "app", "src", "main", "kotlin")
BASELINE = os.path.join(HERE, "oversized_method_budget_7809.json")
MIN_LINES = 600

TOKEN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*|\d+(?:\.\d+)?|[^\s\w]")
FUN = re.compile(r"\bfun\s+(?:<[^>{}]*>\s*)?(?:[\w.?<>]+\.)?([A-Za-z_]\w*)\s*\(")


def strip(src: str) -> str:
    """Blank comments and string-literal text; keep template code, braces and newlines."""
    n = len(src)
    out = []

    def code(i: int, stop_at_brace: bool) -> int:
        depth = 0
        while i < n:
            c = src[i]
            if src.startswith("//", i):
                j = src.find("\n", i)
                i = n if j < 0 else j
                continue
            if src.startswith("/*", i):
                j = src.find("*/", i + 2)
                j = n if j < 0 else j + 2
                out.append("\n" * src.count("\n", i, j))
                i = j
                continue
            if src.startswith('"""', i):
                i = string(i + 3, raw=True)
                continue
            if c == '"':
                i = string(i + 1, raw=False)
                continue
            if c == "'":
                j = i + 1
                while j < n and src[j] != "'" and src[j] != "\n":
                    j += 2 if src[j] == "\\" else 1
                out.append("' '")
                i = j + 1
                continue
            if c == "{":
                depth += 1
            elif c == "}":
                if stop_at_brace and depth == 0:
                    return i + 1
                depth -= 1
            out.append(c)
            i += 1
        return i

    def string(i: int, raw: bool) -> int:
        out.append('"')
        while i < n:
            if raw and src.startswith('"""', i):
                j = i + 3
                while j < n and src[j] == '"':
                    j += 1
                out.append('"')
                return j
            c = src[i]
            if not raw and c == "\\":
                i += 2
                continue
            if not raw and (c == '"' or c == "\n"):
                out.append('"')
                return i + 1
            if src.startswith("${", i):
                out.append(" ")
                i = code(i + 2, stop_at_brace=True)
                out.append(" ")
                continue
            if c == "\n":
                out.append("\n")
            i += 1
        return i

    code(0, stop_at_brace=False)
    return "".join(out)


def methods(path: str):
    src = strip(open(path, encoding="utf-8").read())
    seen = {}
    for m in FUN.finditer(src):
        name = m.group(1)
        # find the body: first '{' or '=' after the parameter list closes
        k = m.end() - 1
        depth = 0
        while k < len(src):
            if src[k] == "(":
                depth += 1
            elif src[k] == ")":
                depth -= 1
                if depth == 0:
                    break
            k += 1
        j = k + 1
        while j < len(src) and src[j] not in "{=\n;":
            j += 1
        # skip return-type lines that wrap
        while j < len(src) and src[j] == "\n":
            nxt = src[j + 1:j + 200].lstrip()
            if nxt.startswith("{") or nxt.startswith(":"):
                j = src.index(nxt[0], j)
                while j < len(src) and src[j] not in "{=;":
                    j += 1
                break
            break
        if j >= len(src) or src[j] != "{":
            continue
        depth, e = 0, j
        while e < len(src):
            if src[e] == "{":
                depth += 1
            elif src[e] == "}":
                depth -= 1
                if depth == 0:
                    break
            e += 1
        body = src[j:e + 1]
        lines = sum(1 for l in body.split("\n") if l.strip())
        if lines < MIN_LINES:
            continue
        idx = seen.get(name, 0)
        seen[name] = idx + 1
        key = f"{os.path.relpath(path, SRC)}::{name}#{idx}"
        yield key, lines, len(TOKEN.findall(body))


def measure():
    res = {}
    for root, _, files in os.walk(SRC):
        for f in files:
            if f.endswith(".kt"):
                for key, lines, toks in methods(os.path.join(root, f)):
                    res[key] = {"lines": lines, "tokens": toks}
    return res


def main() -> int:
    cur = measure()
    if "--rebaseline" in sys.argv:
        with open(BASELINE, "w", encoding="utf-8") as fh:
            json.dump(dict(sorted(cur.items())), fh, indent=1)
        print(f"oversized_method_budget: rebaselined {len(cur)} methods")
        return 0
    base = json.load(open(BASELINE, encoding="utf-8"))
    bad = []
    for key, v in sorted(cur.items()):
        b = base.get(key)
        if b is None:
            bad.append(f"  NEW OVERSIZED {key}: {v['lines']} lines / {v['tokens']} tokens (limit {MIN_LINES} lines)")
        elif v["tokens"] > b["tokens"]:
            bad.append(f"  GREW {key}: tokens {b['tokens']} -> {v['tokens']} (+{v['tokens'] - b['tokens']})")
    print(f"oversized_method_budget: {len(cur)} oversized methods checked against {len(base)} pinned")
    if bad:
        print("oversized_method_budget: FAIL — ART rejects oversized methods at class load (5.0.7807 VerifyError).")
        print("Move new logic into a private helper / separate object; a pinned method may only shrink.")
        print("\n".join(bad))
        return 1
    print("oversized_method_budget: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
