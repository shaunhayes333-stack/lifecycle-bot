#!/usr/bin/env python3
"""
V5.0.7112 — ONE-WAY ADAPTIVE CONTROL SCAN.

The defect V5.0.7111 fixed was not a one-off. The entry score floor was composed
from terms that could every one of them only RAISE it, so the bar could tighten
on evidence and never loosen on evidence. V5.0.7091 had already removed the same
shape from ExplorationBudget, where a budget that decremented on refusals as well
as admissions sealed itself shut and could not reopen.

Both were found by reading. That does not scale, and both should have been found
by a scan, so this is the scan.

WHAT IT LOOKS FOR. An adaptive control — something the runtime computes from
evidence and applies to a gate, a size or a threshold — whose clamp permits
motion in only one direction:

    delta  clamped to [0, X]      can only ever ADD          (penalty-only)
    delta  clamped to [-X, 0]     can only ever SUBTRACT     (relief-only)
    mult   clamped to [1, X]      can only ever BOOST
    mult   clamped to [X, 1]      can only ever DAMP         (shrink-only)

A one-way control is not automatically wrong. A true hard floor should not be
liftable, and a safety clamp is meant to be asymmetric. What makes it a DEFECT is
when the thing being clamped is described as learned, tuned, adaptive or
evidence-driven — because then the system can observe that it was wrong in one
direction and is structurally unable to act on it.

So this reports, and ranks by how strongly the surrounding code claims to be
adaptive. It is a lead generator, not a verdict: every hit needs reading.

Exit code is 0 always. This is not a build gate — it is a sweep.
"""
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "kotlin")

# Names that say "this value was learned or adapted", as opposed to a constant
# safety bound. Presence of these near a one-way clamp is what promotes a hit.
ADAPTIVE_HINTS = (
    "learn", "tune", "tuned", "adapt", "expectancy", "realised", "realized",
    "outcome", "evidence", "wr", "winrate", "win_rate", "streak", "bandit",
    "policy", "conviction", "regime", "damper", "shaper", "score", "edge",
    "feedback", "closed-loop", "closedloop", "bias", "pwin", "ev",
)

# Names that say "this is a deliberate hard bound", which makes one-wayness
# expected rather than suspicious.
SAFETY_HINTS = (
    "hard", "floor", "ceiling", "cap", "max", "min", "limit", "clamp",
    "sanity", "guard", "absolute", "never", "invariant", "safety",
)

CONTROL_NAME = re.compile(
    r"\b(\w*(?:delta|mult|multiplier|raise|penalty|boost|damp|adjust|bias|"
    r"shape|scale|tighten|relax|nudge|weight|factor)\w*)\b",
    re.IGNORECASE,
)

# val x = <expr>.coerceIn(a, b)   /   .coerceAtLeast(a)   /   .coerceAtMost(b)
COERCE_IN = re.compile(r"\.coerceIn\(\s*([-\d._eE]+)\s*,\s*([-\d._eE]+)\s*\)")
COERCE_AT_LEAST = re.compile(r"\.coerceAtLeast\(\s*([-\d._eE]+)\s*\)")
COERCE_AT_MOST = re.compile(r"\.coerceAtMost\(\s*([-\d._eE]+)\s*\)")


# V5.0.7114 — REVIEWED AND CORRECT. Each of these was read in source and found
# to be a legitimate one-way bound. Recorded with its reason so the next run is
# the UNREAD remainder rather than the same list to re-triage, exactly as the
# unwired ledger works. Removing a line here puts the hit back on the list.
REVIEWED_CORRECT = {
    "LiveStylePivotRouter.kt:399":
        "release-to-baseline: maxOf(mult, qualityMult) restores a starved lane "
        "toward 1.0. Capping at 1.0 is what 'restore' means; pressing above "
        "baseline is a different decision made elsewhere.",
    "LiveStrategyTuner.kt:235":
        "paired with :238 — green boosts tp, red damps liquidity impact. The "
        "two conditionals are mutually exclusive states, so the tuner is "
        "two-sided; the scan sees each line alone.",
    "LiveStrategyTuner.kt:238": "paired with :235. See above.",
    "LiveStrategyTuner.kt:283": "inside the winner/runner PRESS branch — boost-only is the branch's purpose.",
    "LiveStrategyTuner.kt:339": "winner-press branch, reached only when the lane IS a winner.",
    "LiveStrategyTuner.kt:357": "winner-press branch. See :339.",
    "LiveStrategyTuner.kt:358": "winner-press branch. See :339.",
    "LiveProbabilityEngine.kt:399":
        "rugPenalty is subtracted alongside pnlEdge and solEdge, which ARE "
        "two-sided (coerceIn(-0.35,0.35) / (-0.30,0.28)). A penalty is "
        "one-sided by definition and its bonus half is a separate term.",
    "LiveProbabilityEngine.kt:400": "uncertaintyPenalty — same composition as :399.",
    "NarrativeDetector.kt:259":
        "scam-keyword detector. It reports red flags; only subtracting is its "
        "job, not a blocked direction. (Its positiveSignals field is hardcoded "
        "empty — that is a separate 'built and never populated' item, not a "
        "one-way control.)",
    "Executor.kt:17044":
        "defensiveMult is documented '[0.0, 1.0] and only shrink — never "
        "amplify'; amplification is a separate term (govMult * "
        "concentratorMult) under a 2.25 ceiling. Two-sided at the composite.",
    "CanonicalAssetEntryContract6551.kt:130":
        "confidence used as a FRACTION of an already-requested size. Capping "
        "at 1.0 is what 'a fraction of' means.",
    "UnifiedScorer.kt:450":
        "newLayerNegScale itself is a confidence RAMP to full trust [0.10, 1.0] "
        "and is correct. Its asymmetric APPLICATION was the defect and "
        "V5.0.7113 fixed it: fluidScale had `if (c.value >= 0) return c`, so "
        "twenty unproven layers could add all of their points and subtract a "
        "tenth. It now scales both signs.",
    "ShitCoinExpress.kt:604":
        "coerceIn(1, 4) floors an arb bonus at +1, so a cached opportunity is "
        "worth a point whatever its score. Minor and defensible — an arb "
        "existing is itself information — and there is no measured symptom. "
        "Recorded rather than changed.",
    "ShitCoinTraderAI.kt:1620": "same arb bonus as ShitCoinExpress:604.",
    "OrderSizeResolver6441.kt:291":
        "a SOL order size floored at 0. A negative size is nonsense, not a "
        "blocked direction.",
}


def num(tok):
    try:
        return float(tok.replace("_", ""))
    except Exception:
        return None


def classify(lo, hi):
    """Return a one-way description, or None when the clamp is two-sided."""
    if lo is None or hi is None:
        return None
    if lo == 0.0 and hi > 0.0:
        return "ADD_ONLY (delta cannot go negative)"
    if hi == 0.0 and lo < 0.0:
        return "SUBTRACT_ONLY (delta cannot go positive)"
    if lo == 1.0 and hi > 1.0:
        return "BOOST_ONLY (multiplier cannot damp)"
    if hi == 1.0 and lo < 1.0:
        return "DAMP_ONLY (multiplier cannot boost)"
    return None


def context(lines, i, span=6):
    lo = max(0, i - span)
    hi = min(len(lines), i + 2)
    return " ".join(lines[lo:hi]).lower()


def main():
    hits = []
    reviewed_count = [0]
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
            for i, raw in enumerate(lines):
                line = raw.strip()
                if line.startswith("//") or line.startswith("*"):
                    continue
                verdict = None
                m = COERCE_IN.search(line)
                if m:
                    verdict = classify(num(m.group(1)), num(m.group(2)))
                elif COERCE_AT_LEAST.search(line):
                    v = num(COERCE_AT_LEAST.search(line).group(1))
                    if v == 0.0:
                        verdict = "ADD_ONLY (floored at 0, no relief possible)"
                elif COERCE_AT_MOST.search(line):
                    v = num(COERCE_AT_MOST.search(line).group(1))
                    if v == 1.0:
                        verdict = "DAMP_ONLY (capped at 1.0, cannot press)"
                if not verdict:
                    continue

                # The control must be the thing being ASSIGNED or passed by
                # name, not merely a word appearing somewhere on the line. That
                # distinction is what separates a real one-way control from a
                # probability being normalised next to one.
                assign = re.match(r"\s*(?:val|var)\s+(\w+)\s*=", raw) or \
                         re.match(r"\s*(\w+)\s*=\s*", raw)
                name = assign.group(1) if assign else None
                if not name or not CONTROL_NAME.fullmatch(name):
                    continue

                # Normalising a percentage or a confidence into [0,1] or [-1,1]
                # is a unit conversion, not an adaptive control with a blocked
                # direction. Drop it.
                if re.search(r"/\s*[\d._]+\s*\)?\s*\.coerceIn\(\s*-?[01]\.0\s*,\s*1\.0\s*\)", line):
                    continue

                # ── V5.0.7114 — false-positive classes, each learned by reading a
                # hit from the first run and finding it correct. Recorded here so
                # the next run is signal rather than a list to re-triage.
                #
                # 1. A QUANTITY floored at zero. A negative order size, SOL amount
                #    or token count is nonsense, not a blocked direction.
                #    (OrderSizeResolver6441:291, ExecutableEntryAuthority6450:156.)
                if re.search(r"\.coerceAtLeast\(\s*0(\.0)?\s*\)", line) and \
                   re.search(r"(?i)(sol|size|amount|qty|quantity|notional|requested|shaped|spend)", name):
                    continue

                # 2. A CONFIDENCE OR PROBABILITY USED AS A FRACTION of a requested
                #    quantity — `size = requested * confidence.coerceIn(0.35, 1.0)`.
                #    Capping at 1.0 is what "a fraction of" means; pressing above
                #    the request is a different decision made elsewhere.
                #    (CanonicalAssetEntryContract6551:130.)
                if re.search(r"(?i)(confidence|probability|pwin|conviction)\b", line) and \
                   re.search(r"\.coerceIn\(\s*0?\.\d+\s*,\s*1\.0\s*\)", line):
                    continue

                # 3. A term the code itself calls a PENALTY, subtracted alongside
                #    separate two-sided bonus terms. A penalty is one-sided by
                #    definition. Demoted rather than dropped — a penalty with NO
                #    matching bonus anywhere is still worth a look, so it stays on
                #    the list but sorts below everything else.
                #    (LiveProbabilityEngine:399-400, where pnlEdge and solEdge are
                #    the two-sided halves.)
                penalty_term = bool(re.search(r"(?i)penalty|shave|veto", name))

                # 4. THE CLAMP IS ON A CALL ARGUMENT, not on the assigned value.
                #    `val shape = f(score.coerceIn(0, 100))` bounds the SCORE
                #    going in, and says nothing about `shape`. Detect by paren
                #    depth between the `=` and the `.coerceIn`.
                #    (Executor:12829, FinalDecisionGate:3301.)
                eq = raw.find("=")
                cpos = raw.find(".coerceIn")
                if cpos < 0:
                    cpos = raw.find(".coerceAtLeast")
                if cpos < 0:
                    cpos = raw.find(".coerceAtMost")
                if eq >= 0 and cpos > eq:
                    seg = raw[eq + 1:cpos]
                    if seg.count("(") - seg.count(")") > 0:
                        continue

                # 5. A PERCENTAGE OR SCORE clamped to its own natural range.
                #    [0, 100] on something called a score or a confidence is the
                #    domain of the quantity, not a blocked direction.
                if re.search(r"\.coerceIn\(\s*0(\.0)?\s*,\s*100(\.0)?\s*\)", line) and \
                   re.search(r"(?i)(score|confidence|pct|percent)", name):
                    continue

                # 6. A WEIGHT, RATE, RATIO OR SHARE clamped to [0, 1]. A
                #    proportion is bounded by being a proportion.
                #    (AateDecisionEnvelope6512:39, CanonicalEntryPipeline6398:57,
                #    EducationSubLayerAI:1872, PaperSeededPrior6991:161.)
                if re.search(r"\.coerceIn\(\s*0\.0\s*,\s*1\.0\s*\)", line) and \
                   re.search(r"(?i)(weight|rate|ratio|share|fraction|prob)", name):
                    continue

                ctx = context(lines, i)
                adaptive = sum(1 for h in ADAPTIVE_HINTS if h in ctx)
                safety = sum(1 for h in SAFETY_HINTS if h in name.lower())
                if adaptive == 0:
                    continue  # not claimed to be learned; a plain bound

                rel = os.path.relpath(path, os.path.join(ROOT, "..", "..", "..", ".."))
                if f"{os.path.basename(path)}:{i + 1}" in REVIEWED_CORRECT:
                    reviewed_count[0] += 1
                    continue
                rank = adaptive - safety - (10 if penalty_term else 0)
                hits.append((rank, rel, i + 1, name, verdict, line[:120]))

    hits.sort(key=lambda h: -h[0])
    print(f"one_way_control_scan: {scanned} Kotlin files scanned")
    print(f"one_way_control_scan: {reviewed_count[0]} reviewed-and-correct (see REVIEWED_CORRECT)")
    print(f"one_way_control_scan: {len(hits)} UNREAD adaptive control(s) that can only move one way\n")
    for score, path, ln, name, verdict, src in hits:
        print(f"  [{score}] {path}:{ln}")
        print(f"        {name} -> {verdict}")
        print(f"        {src}")
    print("\nRead every hit. A one-way clamp is correct for a true safety bound and")
    print("a defect for anything learned — the system can observe it was wrong in")
    print("the blocked direction and be structurally unable to act on it.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
