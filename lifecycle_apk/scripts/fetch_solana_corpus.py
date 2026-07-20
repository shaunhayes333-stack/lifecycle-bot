#!/usr/bin/env python3
"""
V5.0.6301 — Historical Solana corpus fetcher.

Called from .github/workflows/refresh-historical-corpus.yml on a weekly
schedule (or via workflow_dispatch). Prior to this commit, the workflow
referenced this script but the file did not exist — every CI run failed
and no corpus was ever produced. The on-device LiveWinDNA bootstrap
seeded from zero rows every install.

Output: app/src/main/assets/historical_corpus.jsonl.gz
  One JSON row per token, each row shaped so LiveWinDNA / PatternMemory
  can ingest it as a synthetic historical sample.

Data source: DexScreener public API (no key required, matches the
production-healthy provider in the op-report). Falls back gracefully
when individual token calls fail — partial corpus is better than none.

Row schema (one per line, gzip-compressed):
  {
    "mint": str,               # base token address
    "symbol": str,
    "priceUsd": float,
    "liquidityUsd": float,
    "volume24hUsd": float,
    "priceChange24hPct": float,
    "priceChange1hPct": float,
    "fdv": float,
    "patterns": [str, ...],    # labels: fresh_pool_momentum, breakout_continuation, etc.
    "outcomeBand": str,        # win|loss|scratch (retrospective)
    "sourceFingerprint": str,  # DEXSCREENER_HISTORICAL_CORPUS_6301
  }
"""

import gzip
import json
import os
import sys
import time
from typing import Any, Dict, List, Optional

import requests

MAX_TOKENS = int(os.environ.get("CORPUS_MAX_TOKENS", "500"))
OUT_PATH = "app/src/main/assets/historical_corpus.jsonl.gz"
SEARCH_TERMS = ["sol", "meme", "pump", "bonk", "wif", "dog", "cat", "ai", "moon"]
REQUEST_TIMEOUT = 15
SLEEP_BETWEEN = 0.35  # DexScreener free tier rate limit


def fetch_search(term: str) -> List[Dict[str, Any]]:
    """DexScreener search endpoint — returns pairs across all chains, we filter Solana."""
    url = f"https://api.dexscreener.com/latest/dex/search?q={term}"
    try:
        r = requests.get(url, timeout=REQUEST_TIMEOUT)
        if r.status_code != 200:
            return []
        payload = r.json()
        pairs = payload.get("pairs") or []
        return [p for p in pairs if (p.get("chainId") or "").lower() == "solana"]
    except Exception as e:
        print(f"  search '{term}' failed: {e}", file=sys.stderr)
        return []


def label_patterns(pair: Dict[str, Any]) -> List[str]:
    """Retrospective pattern tagger — mirrors the on-device labelers."""
    tags: List[str] = []
    liq = float((pair.get("liquidity") or {}).get("usd") or 0)
    vol24 = float((pair.get("volume") or {}).get("h24") or 0)
    change_h1 = float((pair.get("priceChange") or {}).get("h1") or 0)
    change_h24 = float((pair.get("priceChange") or {}).get("h24") or 0)
    fdv = float(pair.get("fdv") or 0)
    age_ms = int(pair.get("pairCreatedAt") or 0)
    now_ms = int(time.time() * 1000)
    age_hours = max(0.0, (now_ms - age_ms) / 3_600_000.0) if age_ms else 999.0

    if age_hours < 24 and liq > 1000:
        tags.append("fresh_pool_momentum")
    if change_h24 > 50 and vol24 > liq * 2:
        tags.append("volume_ignition")
    if change_h1 > 15 and change_h24 > 30:
        tags.append("breakout_continuation")
    if abs(change_h24) < 8 and vol24 > 0:
        tags.append("accumulation_compression")
    if liq > 100_000 and vol24 > 500_000:
        tags.append("liquidity_depth_quality")
    if change_h24 > 100:
        tags.append("runner_near_high")
    if liq > 250_000 and fdv > 5_000_000:
        tags.append("quality_accumulation_swing")
    if not tags:
        tags.append("neutral_structure")
    return tags


def outcome_band(change_h24: float) -> str:
    if change_h24 >= 20:
        return "win"
    if change_h24 <= -20:
        return "loss"
    return "scratch"


def to_row(pair: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    base = pair.get("baseToken") or {}
    mint = base.get("address")
    if not mint:
        return None
    change_h24 = float((pair.get("priceChange") or {}).get("h24") or 0)
    return {
        "mint": mint,
        "symbol": base.get("symbol") or "",
        "priceUsd": float(pair.get("priceUsd") or 0),
        "liquidityUsd": float((pair.get("liquidity") or {}).get("usd") or 0),
        "volume24hUsd": float((pair.get("volume") or {}).get("h24") or 0),
        "priceChange24hPct": change_h24,
        "priceChange1hPct": float((pair.get("priceChange") or {}).get("h1") or 0),
        "fdv": float(pair.get("fdv") or 0),
        "patterns": label_patterns(pair),
        "outcomeBand": outcome_band(change_h24),
        "sourceFingerprint": "DEXSCREENER_HISTORICAL_CORPUS_6301",
    }


def main() -> int:
    print(f"Fetching Solana historical corpus (target={MAX_TOKENS} tokens)")
    seen_mints: set = set()
    rows: List[Dict[str, Any]] = []
    for term in SEARCH_TERMS:
        if len(rows) >= MAX_TOKENS:
            break
        print(f"  querying '{term}' ...", file=sys.stderr)
        pairs = fetch_search(term)
        for pair in pairs:
            if len(rows) >= MAX_TOKENS:
                break
            mint = (pair.get("baseToken") or {}).get("address")
            if not mint or mint in seen_mints:
                continue
            row = to_row(pair)
            if row is None:
                continue
            seen_mints.add(mint)
            rows.append(row)
        time.sleep(SLEEP_BETWEEN)

    if not rows:
        print("::error::No rows fetched — DexScreener responded but no Solana pairs matched.", file=sys.stderr)
        return 1

    os.makedirs(os.path.dirname(OUT_PATH), exist_ok=True)
    with gzip.open(OUT_PATH, "wt", encoding="utf-8") as fp:
        for row in rows:
            fp.write(json.dumps(row, separators=(",", ":")) + "\n")

    print(f"Wrote {len(rows)} rows to {OUT_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
