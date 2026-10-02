#!/usr/bin/env python3
"""V5.0.7720 — fail the build when FinalDecisionGate.evaluate() grows.

WHY THIS EXISTS. evaluate() is the one method Android's ART verifier has
rejected, repeatedly, once it accumulates enough locals and branches, and
the rejection is a java.lang.VerifyError thrown when the class is loaded —
which, because BotService shares the UI process and starts the moment the
PIN is accepted, surfaces to the operator as "the app crashes the second the
password goes in" with no readable log:

  7415  emergency rollback to 7405 after a post-login crash
  7417  "7410 added several locals/branches here and Android ART rejected
        evaluate() at runtime with VerifyError" — pre-fanout cache extracted
  7629  Runtime Smoke Test: java.lang.VerifyError while ART verified
        FinalDecisionGate.evaluate — fanout branch extracted
  7715  Field Manual verdict (two locals + a FinalDecision(...) block)
        added inside evaluate(); post-login crash; 7716 reverted it;
        7717/7718 re-added the locals and crashed the same way

Declarations inside evaluate() at those points: 503 after 7417, 514 in the
working 7716, 516 in the crashing 7715/7718. The regression tests that
guarded 7417 and 7629 ran in the diagnostic (non-blocking) suite and one of
them had already gone stale, so nothing stopped 7715. This scan is in the
required validator list.

RULE. evaluate() may not have more `val`/`var` declarations or more
`return FinalDecision(` constructions than the pinned budget. New logic goes
in a private helper that returns FinalDecision? and is called as
`helper(...)?.let { return it }` — the 7417/7629/7720 pattern. Lowering the
budget after an extraction is encouraged; raising it needs an on-device
proof that the APK survives login.

Exit 1 on a breach, with the measured numbers.
"""
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
FDG = os.path.join(REPO, "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt")

# Pinned to the last build proven on the operator's device (5.0.7716).
MAX_DECLARATIONS = 514
MAX_FINAL_DECISION_RETURNS = 11

DECL = re.compile(r"\b(?:val|var)\s+[A-Za-z_]\w*")


def evaluate_body(src: str) -> str:
    i = src.index("    fun evaluate(\n        ts: TokenState,")
    j = src.index("{", src.index("): FinalDecision", i))
    depth = 0
    k = j
    while True:
        c = src[k]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                break
        k += 1
    return src[j:k]


def main() -> int:
    with open(FDG, encoding="utf-8") as f:
        src = f.read()
    body = evaluate_body(src)
    code = "\n".join(l for l in body.split("\n") if not l.strip().startswith("//"))
    decls = len(DECL.findall(code))
    returns = code.count("return FinalDecision(")
    print(f"fdg_evaluate_budget_scan: evaluate() declarations={decls} (max {MAX_DECLARATIONS}) "
          f"return FinalDecision(={returns} (max {MAX_FINAL_DECISION_RETURNS}) lines={body.count(chr(10))}")
    bad = []
    if decls > MAX_DECLARATIONS:
        bad.append(f"declarations {decls} > {MAX_DECLARATIONS}")
    if returns > MAX_FINAL_DECISION_RETURNS:
        bad.append(f"return FinalDecision( {returns} > {MAX_FINAL_DECISION_RETURNS}")
    if bad:
        print("fdg_evaluate_budget_scan: FAIL — " + "; ".join(bad))
        print("  evaluate() is at the ART verifier's limit (7415/7417/7629/7715 post-login crashes).")
        print("  Move the new logic into a private helper returning FinalDecision? and call it as")
        print("  helper(...)?.let { return it } with no new locals in evaluate().")
        return 1
    print("fdg_evaluate_budget_scan: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
