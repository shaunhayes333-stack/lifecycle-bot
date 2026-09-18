#!/usr/bin/env python3
"""Triage the remaining unwired ledger entries by SHAPE before reading each one.

At audit depth the majority of remaining entries are not defects — they are thin
accessors over state a live sibling already owns, stubs, or superseded APIs.
Reading all 84 in full is the expensive way to discover that. This classifies by
declaration shape so the BLOCK-bodied ones (which are where real logic lives) can
be read first, and the one-liners cleared in bulk.

Shapes:
  ONE-LINER    expression body — almost always `= someField` or a lookup
  THIN-RETURN  block body whose first statement is a return of a field/lookup
  BLOCK        real body — read these
"""
import csv, os, re, sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot"
MAJOR = ("A_PREDICT", "B_RISK", "C_EXIT")


def main():
    rows = list(csv.DictReader(open(os.path.join(REPO, "ci/UNWIRED_LEDGER.tsv")), delimiter="\t"))
    out = []
    for r in rows:
        if r["tier"] not in MAJOR:
            continue
        path = os.path.join(REPO, SRC, r["file"])
        if not os.path.exists(path):
            continue
        src = open(path, encoding="utf-8", errors="replace").read().splitlines()
        for i, line in enumerate(src):
            if not re.search(r"\bfun\s+" + re.escape(r["function"]) + r"\s*\(", line):
                continue
            body = line.strip()
            if "=" in body and not body.rstrip().endswith("{"):
                kind = "ONE-LINER"
            else:
                nxt = " ".join(x.strip() for x in src[i + 1:i + 4])
                kind = "THIN-RETURN" if nxt.startswith("return ") else "BLOCK"
            out.append((kind, r["owner"], r["function"], body[:100]))
            break
    out.sort()
    for kind, owner, fn, body in out:
        print(f"[{kind:<12}] {owner}.{fn}")
        if kind != "BLOCK":
            print(f"                {body}")
    from collections import Counter
    print(f"\nremaining in major tiers: {len(out)}  {dict(Counter(k for k, *_ in out))}")


if __name__ == "__main__":
    sys.exit(main())
