#!/usr/bin/env python3
"""
V5.0.7655 - source-level census for AATE's intelligence estate.

Report-only. It scans Kotlin source filenames/content and classifies likely
intelligence components so new brains cannot quietly disappear from architecture
reviews. It never edits source and never changes trading behaviour.
"""
from __future__ import annotations
from pathlib import Path
import csv, json, re, sys

ROOT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "kotlin"
OUT_DIR = Path(__file__).resolve().parents[1] / "audits"
TOKENS = re.compile(r"(AI|Brain|Scanner|Council|CrossTalk|Cognition|Sentience|Intelligence|Predictor|Oracle|Model|Learning|Policy|Agent|Expert|Reasoner|Planner)", re.I)

DIRECT = {
    "AICrossTalk", "AsyncGeminiNarrativeCache6478", "ArbScannerAI",
    "ExistingIntelligenceContext7650", "SuperIntelligenceEstate7654",
}
UPSTREAM_ROOTS = {
    "UnifiedScorer", "SpecialistBrainBridge7542", "BrainConsensusBridge6329",
    "UltimateEdgeEngine", "SuperWorldModel7634", "PredictiveEntryOracle6915",
}
SAFETY_WORDS = ("Rug", "Safety", "Freeze", "HardBlock", "Veto", "Blacklist")
BACKGROUND_WORDS = ("Sentience", "PilotCouncil", "Lab", "Research", "Reflection")
REPORT_WORDS = ("Operator", "Sentinel", "Digest", "Registry", "Report")

def load_reviewed(root: Path = ROOT) -> dict:
    """Reviewed ancestry is valid only while its production consumer calls exist.

    Missing proof fails CI rather than silently declaring an amputated component
    wired. This manifest classifies source wiring, never runtime acceptance.
    """
    manifest = Path(__file__).with_name("super_intelligence_reviewed_7686.json")
    reviewed = {}
    for row in json.loads(manifest.read_text()):
        path = row["path"]
        if path in reviewed or not (root / path).is_file():
            raise ValueError("invalid reviewed estate path: " + path)
        if not row["reason"] or not row["consumers"]:
            raise ValueError("review missing ancestry/consumer proof: " + path)
        for proof in row["consumers"]:
            consumer = root / proof["path"]
            if proof["path"] == path or not consumer.is_file():
                raise ValueError("invalid estate consumer: " + proof["path"])
            # Strip comments so a retired call left in prose cannot pass proof.
            code = re.sub(r"/\*.*?\*/|//[^\n]*", "", consumer.read_text(), flags=re.S)
            if proof["call"] not in code:
                raise ValueError("estate consumer call missing: " + proof["call"])
        reviewed[path] = row
    return reviewed

def object_name(text: str, path: Path) -> str:
    m = re.search(r"\b(?:object|class)\s+([A-Za-z0-9_]+)", text)
    return m.group(1) if m else path.stem

def classify(path: Path, text: str, name: str, roots_text: str) -> str:
    if name in DIRECT:
        return "DIRECT_SUPER_OR_CACHE"
    if "LayerBrain.register(" in text:
        return "LAYER_BRAIN_LEARNED_UPSTREAM"
    if name in UPSTREAM_ROOTS:
        return "SUPER_UPSTREAM_ROOT"
    if re.search(r"\b" + re.escape(name) + r"\b", roots_text):
        return "REPRESENTED_BY_AGGREGATOR"
    if any(w.lower() in name.lower() for w in SAFETY_WORDS):
        return "SAFETY_SOVEREIGN_REVIEW"
    if any(w.lower() in name.lower() for w in BACKGROUND_WORDS):
        return "BACKGROUND_RESEARCH_REVIEW"
    if any(w.lower() in name.lower() for w in REPORT_WORDS):
        return "REPORT_INFRASTRUCTURE"
    if any(x in text for x in ("OkHttpClient", "SharedHttpClient", "Request.Builder(", "GeminiCopilot.rawText(")):
        return "PROVIDER_BACKED_REVIEW"
    if any(x in text for x in ("cached", "peek", "snapshot", "statusLine")):
        return "CACHE_OR_READBACK_REVIEW"
    return "UNCLASSIFIED_INTELLIGENCE_REVIEW"

def main() -> int:
    reviewed = load_reviewed()
    files = sorted(ROOT.rglob("*.kt"))
    candidates = [p for p in files if TOKENS.search(p.name)]

    root_paths = [
        ROOT / "com/lifecyclebot/v3/scoring/UnifiedScorer.kt",
        ROOT / "com/lifecyclebot/engine/SpecialistBrainBridge7542.kt",
        ROOT / "com/lifecyclebot/engine/BrainConsensusBridge6329.kt",
        ROOT / "com/lifecyclebot/engine/ExistingIntelligenceContext7650.kt",
        ROOT / "com/lifecyclebot/engine/SuperIntelligenceEstate7654.kt",
        ROOT / "com/lifecyclebot/engine/SuperWorldModel7634.kt",
    ]
    roots_text = "\n".join(p.read_text(errors="ignore") for p in root_paths if p.exists())

    rows = []
    counts = {}
    for p in candidates:
        text = p.read_text(errors="ignore")
        name = object_name(text, p)
        review = reviewed.get(str(p.relative_to(ROOT)))
        cls = review["classification"] if review else classify(p, text, name, roots_text)
        counts[cls] = counts.get(cls, 0) + 1
        rows.append((name, cls, str(p.relative_to(ROOT)), "LayerBrain.register(" in text))

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    tsv = OUT_DIR / "super_intelligence_estate_census_7655.tsv"
    with tsv.open("w", newline="") as f:
        w = csv.writer(f, delimiter="\t", lineterminator="\n")
        w.writerow(["name", "classification", "path", "layer_brain"])
        w.writerows(rows)

    summary = {
        "all_kotlin": len(files),
        "intelligence_candidates": len(candidates),
        "classifications": dict(sorted(counts.items())),
        "unclassified": counts.get("UNCLASSIFIED_INTELLIGENCE_REVIEW", 0),
        "source_reviewed_with_consumer_proof": len(reviewed),
    }
    (OUT_DIR / "super_intelligence_estate_census_7655.json").write_text(json.dumps(summary, indent=2) + "\n")
    print("SUPER_INTELLIGENCE_ESTATE_CENSUS_7655", json.dumps(summary, sort_keys=True))

    if len(candidates) < 150:
        print("ERROR: census unexpectedly found fewer than 150 intelligence candidates", file=sys.stderr)
        return 2
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
