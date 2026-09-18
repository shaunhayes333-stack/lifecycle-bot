#!/usr/bin/env python3
"""V5.0.6990 — find intelligence that runs in PAPER but not in LIVE.

THE QUESTION THIS ANSWERS
=========================
The operator's: "real money trading is where we truly prove what we built has
true predictive algorithmic edge and not just fluff on paper."

A learner or shaper that is reachable only on the paper branch is exactly that
fluff: it looks like edge in every snapshot, and contributes nothing the moment
capital is real. There are ~1,218 mode-gated sites in this tree
(isPaper 121, isLive 64, paperMode 767, cfg.paperMode 266), which is far past
what anyone can eyeball, and V5.0.6988 showed the module that was supposed to
police it never measured anything.

WHAT IT LOOKS FOR
=================
Blocks guarded by a runtime-mode predicate whose body calls a named
intelligence authority, classified by which side of the branch they sit on:

  PAPER_ONLY   the call happens under a paper-true guard with no else branch
               -> candidate fluff: live never gets this contribution
  LIVE_ONLY    the call happens under a live-true guard
               -> not fluff, but paper telemetry overstates what paper proved
  GUARDED_BOTH both branches present -> deliberate, reported for completeness

HOW IT DECIDES A "BLOCK"
========================
Kotlin is brace-delimited, so once a guard line is found the body is taken by
brace matching from the first `{` on (or after) that line. Single-expression
guards with no braces take the remainder of the line plus the next line. This
is a heuristic scanner for triage, not a compiler: it is meant to shrink 1,218
sites to a reviewable list, and every hit is quoted with file:line so the
verdict comes from reading the code, never from the tool's say-so.

Exit code is always 0 — this reports, it does not gate.
"""

from __future__ import annotations

import os
import re
import sys
from collections import defaultdict

SRC = "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot"

# Named authorities from the operator's own pipeline dump. A call to one of
# these inside a mode-gated block is what makes the block interesting.
INTELLIGENCE = [
    "ForwardOutcomeModel", "UnifiedPolicyHead", "UnifiedExitPolicyHead",
    "LiveProbabilityEngine", "SemanticPatternGraph", "LosingPatternMemory",
    "AutonomousMetaPolicy", "SecondScorer", "RegimeDetector",
    "LaneExpectancyDamper", "TacticSwitcher", "StrategyHypothesisEngine",
    "LaneExitTuner", "EducationSubLayerAI", "FluidLearningAI",
    "AdaptiveLearning", "CollectiveLearning", "BrainConsensus",
    "PredictiveOracle", "LearnedAdmission", "EntryConviction",
    "MultiplierAttributionLedger", "CapitalEfficiencyBrain",
    "ExitCostMicrobrain", "CounterfactualReplayEngine",
    "ScoreExpectancyTracker", "ExecutionCostPredictorAI",
    "GrowthRewardShaper", "LearnerRewardBridge", "MemeCausalLearning",
    "RewardPurity", "CausalFeedback", "SentienceAutoTune",
    "StrategyLeaderboard", "LabUniverse", "ShadowLearningEngine",
    "TradeLessonRecorder", "PatternClassifier", "MovementPatternSignal",
    "SmartChartScanner", "HistoricalChartScanner", "BotBrain",
    "SuperBrain", "MetaCognition", "CrossTalk", "HiveMind",
]
INTEL_RE = re.compile(r"(?<![A-Za-z0-9_])(" + "|".join(map(re.escape, INTELLIGENCE)) + r")(?![A-Za-z0-9_])")

# Mode predicates, split by which mode makes the guard TRUE.
PAPER_TRUE = [
    r"isPaper\(\)", r"\bpaperMode\b(?!\s*=)", r"cfg\(\)\.paperMode",
    r"cfg\.paperMode", r"isPaperMode", r"RuntimeModeAuthority\.isPaper",
]
LIVE_TRUE = [
    r"isLive\(\)", r"RuntimeModeAuthority\.isLive", r"!\s*isPaper\(\)",
    r"!\s*paperMode", r"!\s*cfg\.paperMode",
]
PAPER_RE = re.compile("|".join(PAPER_TRUE))
LIVE_RE = re.compile("|".join(LIVE_TRUE))
IF_RE = re.compile(r"^\s*(\}\s*else\s+)?if\s*\(")


def strip_comments(text: str) -> str:
    """Blank out comments WITHOUT changing line count.

    The first version of this deleted `/* ... */` outright, which collapsed
    multi-line comments and shifted every subsequent line number. Each reported
    hit then pointed at unrelated code — a tool that lies about where the thing
    is, which is worse than no tool. Replace a block comment with the same
    number of newlines so file:line stays truthful.
    """
    def blank(m: re.Match) -> str:
        return "\n" * m.group(0).count("\n")

    text = re.sub(r"/\*.*?\*/", blank, text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def block_after(lines: list[str], start: int) -> tuple[str, int]:
    """Return (body, end_line_index) for the brace block starting at/after start."""
    depth = 0
    started = False
    out: list[str] = []
    for i in range(start, min(start + 400, len(lines))):
        ln = lines[i]
        out.append(ln)
        for ch in ln:
            if ch == "{":
                depth += 1
                started = True
            elif ch == "}":
                depth -= 1
        if started and depth <= 0:
            return "\n".join(out), i
    if not started:
        # Braceless single-expression guard.
        return "\n".join(lines[start:start + 2]), min(start + 1, len(lines) - 1)
    return "\n".join(out), min(start + 400, len(lines) - 1)


def has_else(lines: list[str], end_idx: int) -> bool:
    for i in range(end_idx, min(end_idx + 3, len(lines))):
        if re.search(r"\belse\b", lines[i]):
            return True
    return False


def main() -> int:
    root = sys.argv[1] if len(sys.argv) > 1 else SRC
    findings: dict[str, list[tuple[str, int, str, str]]] = defaultdict(list)
    scanned = 0

    for dirpath, _dirs, files in os.walk(root):
        for name in sorted(files):
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            try:
                raw = open(path, encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            scanned += 1
            lines = strip_comments(raw).splitlines()
            for i, line in enumerate(lines):
                if not IF_RE.match(line):
                    continue
                is_paper = bool(PAPER_RE.search(line))
                is_live = bool(LIVE_RE.search(line))
                # `!isPaper()` matches both; live wins.
                if is_live:
                    is_paper = False
                if not (is_paper or is_live):
                    continue
                body, end = block_after(lines, i)
                hits = sorted(set(INTEL_RE.findall(body)))
                if not hits:
                    continue
                if is_paper:
                    kind = "GUARDED_BOTH" if has_else(lines, end) else "PAPER_ONLY"
                else:
                    kind = "GUARDED_BOTH" if has_else(lines, end) else "LIVE_ONLY"
                findings[kind].append((path, i + 1, line.strip()[:110], ",".join(hits)))

    # ── pass 2: mode-KEYED stores ──────────────────────────────────────────
    #
    # The branch-skip class above turns out to be rare and mostly deliberate.
    # The class that actually bites is a learner whose STORE KEY embeds the
    # runtime mode: it accumulates happily in paper and resolves to zero
    # samples the instant the flip happens. V5.0.6988 found two that way by
    # hand (ForwardOutcomeModel, ExecutableEntryAuthority6450) and V5.0.6990 a
    # third (ColdStreakDamper). Hand-hunting does not scale, so match the
    # shape: a string built from a paper/live discriminator used as a key.
    # The first cut of this matched `"PAPER" else "LIVE"` anywhere and returned
    # 103 hits, nearly all of them log lines and UI badges — a mode word in a
    # message is not a store key. Require BOTH a mode discriminator AND
    # evidence the result is used as a key, and exclude the display paths
    # explicitly. Over-broad is the same failure as silent: it buries the three
    # real ones in noise.
    keyed = []
    # Two shapes, because the mode reaches a key two ways.
    #   inline      "${if (isPaper) "PAPER" else "LIVE"}|..."
    #   parameter   fun cohortKey(mode: String, ...) = "$mode|..."
    # The second is how ExecutableEntryAuthority6450 does it, and a rule that
    # only matched the first would have reported a clean sweep while missing a
    # confirmed bug. Parameterised keys also cover legitimate uses (inventory,
    # idempotency, mirrors), so these are triage rows to read, not verdicts.
    mode_disc_re = re.compile(
        r'(?:isPaper|paperMode)\s*\)\s*"PAPER"\s*else\s*"LIVE"'
        r'|(?:isPaper|paperMode)\s*\)\s*"P"\s*else\s*"L"'
        r'|modeTag\w*\(\s*isPaper'
        r'|"\$\{?mode(?:\.\w+\(\))?\}?\|'
        r'|"\$\{mode[^}]*\}\|'
        # ExecutableEntryAuthority6450 wraps it once more:
        #   "${normalizedMode(mode)}|${normalizedLane(lane)}"
        # so match any interpolation whose expression MENTIONS mode, not just
        # the bare identifier.
        r'|"\$\{[^}]*\bmode\b[^}]*\}\|'
    )
    key_use_re = re.compile(
        r'\bfun\s+(?:key|cohortKey|bucketKey|fineKey|coarseKey)\w*\s*\('
        r'|\b(?:key|cohortKey|bucketKey|modeKey|storeKey)\w*\s*='
        r'|\b(?:getOrPut|computeIfAbsent|putIfAbsent)\s*\('
        r'|\breturn\s+"'
    )
    display_re = re.compile(
        r'ErrorLogger|ForensicLogger|Log\.[diwev]\(|\.text\s*=|append\(|addLog\('
        r'|Toast|setText|sb\.|buildString|println'
    )
    for dirpath, _dirs, files in os.walk(root):
        for name in sorted(files):
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            try:
                raw = open(path, encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            lines = strip_comments(raw).splitlines()
            for i, line in enumerate(lines):
                if not mode_disc_re.search(line):
                    continue
                if display_re.search(line):
                    continue
                # The key use may be on this line or the one above (a `fun key(`
                # header with the expression on the next line).
                ctx = line + "\n" + (lines[i - 1] if i > 0 else "")
                if not key_use_re.search(ctx):
                    continue
                keyed.append((os.path.relpath(path, root), i + 1, line.strip()[:120]))

    print(f"live_gap_audit: scanned {scanned} Kotlin files\n")
    print(f"=== MODE_KEYED_STORE candidates: {len(keyed)}")
    print("    (a key built from paper/live — the learner resets on the flip)")
    for rel, ln, txt in keyed:
        print(f"  {rel}:{ln}\n      {txt}")
    print()

    for kind in ("PAPER_ONLY", "LIVE_ONLY", "GUARDED_BOTH"):
        rows = findings.get(kind, [])
        print(f"=== {kind}: {len(rows)}")
        if kind == "GUARDED_BOTH":
            print("    (both branches present — deliberate; not listed)\n")
            continue
        for path, ln, cond, hits in rows:
            rel = os.path.relpath(path, root)
            print(f"  {rel}:{ln}")
            print(f"      guard: {cond}")
            print(f"      intel: {hits}")
        print()
    print("Every hit above is a CANDIDATE. Read the code before believing the tool.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
