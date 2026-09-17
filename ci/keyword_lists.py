#!/usr/bin/env python3
"""Find every keyword list that classifies an EXIT REASON, and test it against
the vocabulary the code actually emits.

Two independent instances of the same defect shipped in this codebase:
  6951  SellAmountAuthority     "CATASTROPHE" never matches catastrophic_gap_guard_*
  6963  RecoveredHoldGuard      the same wrong word, gating a 15-minute hold

Both were written from memory of the vocabulary rather than against it. This
enumerates the rest before a third one is found the expensive way.
"""
import os
import re
import subprocess

REPO = "/home/user/lifecycle-bot"
SRC = "lifecycle_apk/app/src/main/kotlin/com/lifecyclebot"

# The reasons riskCheck and the exit paths actually emit, harvested from
# `return "..."` in Executor plus the tags the sell paths construct.
RISK_REASONS = [
    "stop_loss", "trailing_stop", "liquidity_drain", "liquidity_collapse",
    "dev_dump", "whale_dump", "velocity_dump", "accelerating_loss",
    "reflex_abort", "reflex_liq_drain", "crosstalk_coordinated_dump",
    "gemini_immediate_exit", "catastrophic_gap_guard_thin<$800",
    "learned_rug_pattern", "PROTECTIVE_EXIT_STOP_6450",
    "TICK_HARD_FLOOR_-35PCT", "STALE_QUOTE_EMERGENCY_25PCT_BACKSTOP",
    "STARTUP_SWEEP_HARD_FLOOR_-96PCT", "fluid_stop_loss",
    "trailing_fluid_loss", "entry_protect_loss",
]

# A chain of r.contains("X") / reason.contains("X"), or a listOf("X", "Y") of
# SHOUTY tokens. Both shapes appear in the codebase.
CONTAINS = re.compile(r'\.contains\(\s*"([A-Z][A-Z0-9_]{2,})"')
LISTOF = re.compile(r'listOf\(\s*((?:"[A-Z][A-Z0-9_]{2,}"\s*,\s*)+"[A-Z][A-Z0-9_]{2,}")\s*,?\s*\)')


def enclosing_fun(lines, idx):
    for i in range(idx, -1, -1):
        m = re.match(r"\s*(?:private |internal |public )?fun\s+([A-Za-z_][A-Za-z0-9_]*)", lines[i])
        if m:
            return m.group(1)
    return "?"


def main():
    out = subprocess.run(
        ["grep", "-rln", "-e", r'\.contains("[A-Z]', "-e", "listOf(", "--include=*.kt", SRC],
        cwd=REPO, capture_output=True, text=True,
    )
    findings = []
    for rel in out.stdout.splitlines():
        path = os.path.join(REPO, rel)
        try:
            lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
        except OSError:
            continue
        # Collect contains-chains: consecutive lines each holding a token.
        i = 0
        while i < len(lines):
            toks, start = [], i
            while i < len(lines) and CONTAINS.search(lines[i]):
                toks += CONTAINS.findall(lines[i])
                i += 1
            if len(toks) >= 4:
                findings.append((rel, start + 1, enclosing_fun(lines, start), toks))
            else:
                i = start + 1
        for n, line in enumerate(lines, 1):
            m = LISTOF.search(line)
            if m:
                toks = re.findall(r'"([A-Z][A-Z0-9_]{2,})"', m.group(1))
                if len(toks) >= 4:
                    findings.append((rel, n, enclosing_fun(lines, n - 1), toks))

    print(f"Keyword lists of >=4 SHOUTY tokens: {len(findings)}\n")
    suspect = 0
    for rel, line, fn, toks in findings:
        missed = [r for r in RISK_REASONS if not any(t in r.upper() for t in toks)]
        hit = len(RISK_REASONS) - len(missed)
        # A list that matches SOME risk reasons is plausibly classifying them.
        # One that matches none is about something else entirely (ignore it).
        if hit == 0:
            continue
        suspect += 1
        print(f"{rel}:{line}  fun {fn}()   matches {hit}/{len(RISK_REASONS)}")
        print(f"   tokens: {','.join(toks)}")
        if missed:
            print(f"   MISSES: {', '.join(missed)}")
        print()
    print(f"lists that classify exit reasons: {suspect}")


if __name__ == "__main__":
    main()
