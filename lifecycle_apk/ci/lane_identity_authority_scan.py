#!/usr/bin/env python3
"""
V5.0.7115 — ONE LANE IDENTITY, ENFORCED.

CanonicalLaneIdentity6506's own header carries the operator mandate that created
it:

    "Create one canonical lane identity function at the boundary. Persist only
     canonical names. Legacy aliases migrate on read: BLUE_CHIP -> BLUECHIP."
    ...
    "any new lane alias found by future audits must be added here, NEVER
     hand-fixed at a call site."

It was hand-fixed at a call site twice, and the copies drifted:

    ExecutableOpenGate.canonicalLane      CASHGEN, CASH_GENERATION -> TREASURY
    Executor.canonicalExecutableLane      CASH_GENERATION          -> CASHGEN
    CanonicalLaneIdentity6506             (knew neither)

Lane strings are compared for EQUALITY to decide whether a sealed execution
authority belongs to the requester. Two normalisers that disagree therefore do
not produce a cosmetic naming wobble — they void sealed authority. The operator's
5.0.7113 snapshot measured the result at 1,251 SEALED_INTENT_REJECTED_LANE_-
MISMATCH_7096 against 66 EXEC_GATE allows.

This scan fails the build when a lane-alias fold appears outside the authority.
It looks for a `when`/`if` branch that maps a KNOWN lane alias literal to a
canonical lane literal — the exact shape of the two copies that existed — rather
than trying to recognise normalisation in general.

Exit 1 on a hit. It is a gate, not a sweep: a second alias table is not a lead
to triage, it is the defect.
"""
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src")
AUTHORITY = os.path.join("truth", "CanonicalLaneIdentity6506.kt")

# The alias keys the authority owns. A file that maps any of these to a lane
# name is normalising lane identity, which is the authority's job.
ALIASES = (
    "BLUE_CHIP", "BLUE-CHIP", "BLUE CHIP",
    "MOON_SHOT", "MOON-SHOT", "MOON SHOT",
    "SHIT_COIN", "SHIT-COIN", "SHIT COIN",
    "MANIP",
    "DIP",
    "SNIPER", "PROJECT",
    "CASH_GENERATION", "CASHGEN",
    "MICRO_CAP", "MICROCAP",
    "PROJECT-SNIPER",
)

CANON = (
    "BLUECHIP", "MOONSHOT", "SHITCOIN", "MANIPULATED", "DIP_HUNTER",
    "PROJECT_SNIPER", "CASHGEN", "TREASURY", "MICRO", "CORE",
)

# ── REVIEWED AND DELIBERATELY LOCAL ───────────────────────────────────────────
# Not every map from a lane-ish string to a lane name is a spelling fold. These
# were each read and found to be a CLASSIFICATION — several distinct things
# grouped into one bucket — which is local policy and does not belong in an
# alias table. Keyed by "<file basename>:<folds>-><target>"; deleting a line
# puts that hit back on the list, so the triage is auditable rather than a
# filter that quietly hides things.
REVIEWED_LOCAL = {
    # tradingMode/strategy name -> lane bucket. MICRO_CAP, PUMP_SNIPER,
    # PUMP_DUMP and PRESALE_SNIPE are four different strategies grouped under
    # SHITCOIN risk handling; LONG_HOLD/DIAMOND_HANDS/SLEEPER likewise under
    # BLUECHIP. Note MICRO_CAP -> SHITCOIN here CONTRADICTS the authority's
    # MICRO_CAP -> MICRO, which is exactly why this must not be folded in: the
    # two answer different questions.
    "HoldingLogicLayer.kt:MICRO_CAP->SHITCOIN": "strategy->lane classifier",
    "HoldingLogicLayer.kt:BLUE_CHIP->BLUECHIP": "strategy->lane classifier",
    # Executor's _behAsset is a BEHAVIOUR bucket for FluidLearningAI /
    # MetaCognitionAI, deliberately coarser than lane identity. Folding
    # CASHGEN into TREASURY for a learner's behaviour prior is a modelling
    # choice, not a claim about which lane owns the position — and it is not
    # compared against a sealed ticket's lane.
    "Executor.kt:BLUE_CHIP->BLUECHIP": "behaviour bucket for the learners",
    "Executor.kt:CASHGEN->TREASURY": "behaviour bucket for the learners",
    # pos.tradingMode, not a lane.
    "Executor.kt:BLUE_CHIP->BLUECHIP@tradingMode": "tradingMode, not a lane",
    # AdaptiveLaneReproof6684 folds PROJECT_SNIPER/SNIPER -> PRESALE_SNIPE, the
    # OPPOSITE direction to the authority. It is self-consistent: it writes and
    # reads its own persisted `targets`/`lastSeed` keys through this one
    # function, so no lookup can miss. Flipping it would orphan persisted keys
    # for no measured gain, so it is recorded rather than changed.
    "AdaptiveLaneReproof6684.kt:BLUE_CHIP->BLUECHIP": "self-consistent private key space",
    "AdaptiveLaneReproof6684.kt:SHIT_COIN->SHITCOIN": "self-consistent private key space",
    "AdaptiveLaneReproof6684.kt:MANIP->MANIPULATED": "self-consistent private key space",
    "AdaptiveLaneReproof6684.kt:DIP->DIP_HUNTER": "self-consistent private key space",
}

# ── NAMED PARALLEL AUTHORITIES ────────────────────────────────────────────────
# The `when`-branch sweep below finds INLINE copies. It does not find a whole
# object built around its own `mapOf(alias to canon)` table, and there are three
# of those, each documented as the lane authority:
#
#   CanonicalLaneIdentity6506  "one canonical lane identity function"
#   LaneAlias                  "Every downstream ledger, cap map, execution lock,
#                               and journal write ... should route through
#                               [normalize] first"           (3 folds)
#   LaneIdentityNormalizer6459 "Canonicalize at ingestion so origin lane is
#                               immutable"                   (8 folds)
#
# and they disagree. The sharpest one: 6459 folds MICROCAP -> MICRO_CAP while
# 6506 folds both MICROCAP and MICRO_CAP -> MICRO, i.e. 6459 canonicalises
# TOWARD the name 6506 canonicalises AWAY from. 6459 also stops at
# RESALE_SNIPE -> PRESALE_SNIPE where 6506 continues to PROJECT_SNIPER.
#
# These are NOT folded into 6506 here. LaneAlias keys ledger/cap/lock identity
# and 6459 keys persisted per-lane counters, so changing either one's output
# renames live persisted state — that is its own change with its own reasoning,
# not a drive-by de-duplication. They are recorded so the count is known and so a
# FOURTH one cannot appear unnoticed.
# And a FOURTH, found by this scan on its first run: CanonicalIdentityModel6464,
# in the same truth/ package as 6459 and 6506, folds
#
#     "BLUECHIP"      to "BLUE_CHIP"        <- the inverse of all three others
#     "PRESALE_SNIPE" to "RESALE_SNIPE"     <- the inverse of 6459's fold
#
# i.e. two functions in one package are exact inverses of each other on the same
# two inputs, so the canonical name of a lane depends on which one ran last.
KNOWN_PARALLEL_AUTHORITIES = {
    "LaneAlias.kt": "ledger/cap/lock ownership identity — renaming its output "
                    "would re-key live persisted positions (V5.0.6312 §9)",
    "LaneIdentityNormalizer6459.kt": "keys persisted per-lane rewrite counters; "
                                     "folds MICROCAP -> MICRO_CAP, the OPPOSITE "
                                     "direction to the authority",
    "CanonicalIdentityModel6464.kt": "folds BLUECHIP -> BLUE_CHIP and "
                                     "PRESALE_SNIPE -> RESALE_SNIPE, the INVERSE "
                                     "of 6506 and of 6459 respectively",
}

# Lane vocabulary. A `mapOf("A" to "B")` table is only a LANE authority if it
# actually names lanes — PortfolioHeatAI maps tickers to beta buckets in exactly
# this syntax and is not one.
LANE_VOCAB = {
    "BLUECHIP", "BLUE_CHIP", "BLUE_CHIPS", "SHITCOIN", "SHIT_COIN",
    "SHITCOIN_EXPRESS", "MOONSHOT", "MOON_SHOT", "PROJECT_SNIPER",
    "PROJECTSNIPER", "SNIPER", "SNIPE", "PRESALE_SNIPE", "RESALE_SNIPE",
    "PRE_SALE_SNIPE", "DIP_HUNTER", "DIPHUNTER", "MANIPULATED", "MANIP",
    "TREASURY", "CASHGEN", "CASH_GEN", "CASH_GENERATION", "QUALITY", "EXPRESS",
    "CYCLIC", "CYCLIC_TREND", "CORE", "STANDARD", "V3_CORE", "MICRO",
    "MICRO_CAP", "MICROCAP", "WHALE_FOLLOW", "WHALEFOLLOW", "COPYTRADE",
    "COPY_TRADE", "WALLET_COPY", "WALLET_RECOVERED", "MOMENTUM_SWING",
    "MOMENTUMSWING",
}
PARALLEL_TABLE = re.compile(r'"([A-Z_\- ]{3,})"\s+to\s+"([A-Z_]{3,})"')

# "MANIP" -> "MANIPULATED"      /  "DIP", "DIP_HUNTER" -> "DIP_HUNTER"
#   left side: one or more quoted literals, at least one a known alias
#   right side: a quoted known canonical lane
BRANCH = re.compile(r'((?:"[A-Z_\- ]+"\s*,\s*)*"[A-Z_\- ]+")\s*->\s*"([A-Z_]+)"')
QUOTED = re.compile(r'"([A-Z_\- ]+)"')


def rel_of(path):
    return os.path.relpath(path, os.path.join(ROOT, "..", "..", ".."))


def main():
    hits = []
    reviewed = []
    parallel = []
    unknown_parallel = []
    scanned = 0
    for base, _dirs, files in os.walk(ROOT):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(base, fn)
            if path.replace("\\", "/").endswith(AUTHORITY.replace("\\", "/")):
                continue
            scanned += 1
            try:
                text = open(path, encoding="utf-8", errors="replace").read()
            except Exception:
                continue
            lines = text.split("\n")

            # A whole parallel alias TABLE (two or more `"ALIAS" to "CANON"`
            # pairs) is a named second authority, not an inline fold. Test
            # sources are exempt: a test asserting the authority's folds holds
            # the expected pairs by definition.
            in_main = os.sep + "main" + os.sep in path
            lane_pairs = [
                (k, v) for k, v in PARALLEL_TABLE.findall(text)
                if k.replace("-", "_").replace(" ", "_") in LANE_VOCAB or v in LANE_VOCAB
            ]
            if in_main and len(lane_pairs) >= 2:
                if fn in KNOWN_PARALLEL_AUTHORITIES:
                    parallel.append((rel_of(path), fn, KNOWN_PARALLEL_AUTHORITIES[fn]))
                else:
                    unknown_parallel.append((rel_of(path), fn))

            for i, line in enumerate(lines):
                stripped = line.strip()
                if stripped.startswith("//") or stripped.startswith("*"):
                    continue
                m = BRANCH.search(line)
                if not m:
                    continue
                target = m.group(2)
                if target not in CANON:
                    continue
                keys = QUOTED.findall(m.group(1))
                # An identity branch ("DIP_HUNTER" -> "DIP_HUNTER") is a
                # passthrough, not a fold. Require a key that is a real alias
                # AND differs from the target.
                offenders = [k for k in keys if k in ALIASES and k != target]
                if not offenders:
                    continue
                rel = rel_of(path)
                # A reviewed classifier is recorded, not failed. Match on the
                # first offending key so multi-key branches key stably.
                base_key = f"{fn}:{offenders[0]}->{target}"
                if base_key in REVIEWED_LOCAL:
                    reviewed.append((rel, i + 1, base_key, REVIEWED_LOCAL[base_key]))
                    continue
                if f"{base_key}@tradingMode" in REVIEWED_LOCAL and "tradingMode" in line:
                    key = f"{base_key}@tradingMode"
                    reviewed.append((rel, i + 1, key, REVIEWED_LOCAL[key]))
                    continue
                hits.append((rel, i + 1, ", ".join(offenders), target, stripped[:88]))

    print(f"lane_identity_authority_scan: {scanned} Kotlin file(s) scanned "
          f"(authority excluded)")
    if reviewed:
        print(f"lane_identity_authority_scan: {len(reviewed)} reviewed-local "
              f"classifier(s), not alias folds:")
        for path, ln, key, why in reviewed:
            print(f"    {path}:{ln}  {key}  — {why}")
    if parallel:
        print(f"lane_identity_authority_scan: {len(parallel)} KNOWN parallel "
              f"lane authority object(s) — recorded, not yet unified:")
        for path, fn, why in parallel:
            print(f"    {path}  — {why}")

    if unknown_parallel:
        print("\nlane_identity_authority_scan: FAIL — an UNRECORDED lane-alias table")
        print("exists outside CanonicalLaneIdentity6506. There are already three")
        print("objects normalising lane identity and they disagree; a fourth must be")
        print("either folded into the authority or recorded in")
        print("KNOWN_PARALLEL_AUTHORITIES with the reason it cannot be.\n")
        for path, fn in unknown_parallel:
            print(f"  {path}")
        return 1

    if not hits:
        print("lane_identity_authority_scan: OK — no unreviewed inline lane-alias "
              "fold, and no unrecorded alias table")
        return 0

    print("\nlane_identity_authority_scan: FAIL — a lane-alias fold exists outside")
    print("CanonicalLaneIdentity6506. Lane names are compared for EQUALITY to match a")
    print("sealed execution authority to its requester; a second normaliser that")
    print("disagrees voids that authority silently (V5.0.7115, 1251 events).")
    print("Move the fold into CanonicalLaneIdentity6506.aliases and delegate.\n")
    for path, ln, keys, target, src in hits:
        print(f"  {path}:{ln}")
        print(f"        folds:  {keys}  ->  {target}")
        print(f"        source: {src}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
