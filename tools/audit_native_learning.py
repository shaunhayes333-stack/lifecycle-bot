#!/usr/bin/env python3
"""Read-only export audit. Endpoint returns are research labels, never trade P&L.

One earliest decision per asset/lane/horizon; fixed cohorts, no optimized thresholds.
Usage: python tools/audit_native_learning.py DATASET.jsonl.gz RESEARCH.jsonl.gz
"""
import argparse
import collections
import gzip
import hashlib
import json
import math
import statistics


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def read(path):
    counts = collections.Counter()
    first = {}
    with gzip.open(path, "rt") as stream:
        for line in stream:
            counts["lines"] += 1
            try:
                r = json.loads(line)
            except (ValueError, TypeError):
                counts["malformed"] += 1
                continue
            if not isinstance(r, dict):
                counts["malformed"] += 1
                continue
            counts["quality:" + str(r.get("quality9043", "UNSTAMPED"))] += 1
            counts["age_known"] += number(r.get("age")) and r["age"] >= 0
            counts["setup_named"] += r.get("setup", "") not in ("", "NONE", "UNKNOWN", "NO_TRIGGER", "NO_SETUP", "UNCLASSIFIED", "ALLOW")
            counts["path9013"] += isinstance(r.get("path9013"), dict)
            counts["priceProof9043"] += isinstance(r.get("priceProof9043"), dict)
            counts["lostmark:" + str(r.get("horizonMs")) + ":" + str(r.get("quality9043", "UNSTAMPED"))] += "LOSTMARK" in str(r.get("reason", ""))
            if not all((r.get("mint"), r.get("lane"), number(r.get("t")), number(r.get("horizonMs")))):
                counts["identity_missing"] += 1
                continue
            key = (r["lane"], r["horizonMs"], r["mint"])
            # Pick before checking label quality or profitability: no later winner substitution.
            if key not in first or r["t"] < first[key]["t"]:
                first[key] = r
    with open(path, "rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return first, dict(counts), digest


def stats(rows):
    rows = sorted(rows, key=lambda r: (r["t"], r["mint"]))
    known = [r for r in rows if number(r.get("net"))]
    y = [r["net"] for r in known]
    split = rows[len(rows)//2]["t"] if rows else 0
    def mean(v):
        return round(statistics.mean(v), 4) if v else None
    return dict(assets=len(rows), priced=len(y), mean=mean(y), median=round(statistics.median(y), 4) if y else None,
                missing_minus100_stress=round((sum(y)-100*(len(rows)-len(y)))/len(rows), 4) if rows else None,
                ex_top3=mean(sorted(y)[:-3]) if len(y) > 3 else None,
                first_half=mean([r["net"] for r in known if r["t"] < split]),
                second_half=mean([r["net"] for r in known if r["t"] >= split]),
                positive=sum(v > 0 for v in y), doubled=sum(v >= 100 for v in y),
                age_known=sum(number(r.get("age")) and r["age"] >= 0 for r in rows))


def audit(path):
    first, counts, digest = read(path)
    groups = collections.defaultdict(list)
    for (lane, horizon, _), r in first.items():
        groups[(lane, horizon)].append(r)
    result = []
    for (lane, horizon), rows in sorted(groups.items()):
        cohorts = {
            "ALL_EARLIEST": rows,
            "PRICED_STAMP_EARLIEST": [r for r in rows if r.get("quality9043") == "PRICED"],
            "PRICED_WITHOUT_LOSTMARK": [r for r in rows if r.get("quality9043") == "PRICED" and "LOSTMARK" not in str(r.get("reason", ""))],
            "BASE_START": [r for r in rows if r.get("stage") == "BASE_START"],
            "LAUNCH_CONTINUATION": [r for r in rows if r.get("setup") == "LAUNCH_CONTINUATION"],
            "CAP_10K_20K": [r for r in rows if number(r.get("mcap")) and 10000 <= r["mcap"] < 20000],
            "AGE_UNDER_15M": [r for r in rows if number(r.get("age")) and 0 <= r["age"] < 15],
            "AGE_AT_LEAST_1D": [r for r in rows if number(r.get("age")) and r["age"] >= 1440],
        }
        for cohort, selected in cohorts.items():
            if selected:
                result.append(dict(lane=lane, horizon_ms=horizon, cohort=cohort, **stats(selected)))
    paired = []
    for lane in sorted({k[0] for k in first}):
        for horizon in (14400000, 86400000):
            pairs = []
            for (l, h, mint), early in first.items():
                if l != lane or h != 3600000 or early.get("stage") != "BASE_START":
                    continue
                later = first.get((l, horizon, mint))
                if later and early["t"] == later["t"] and number(early.get("net")) and number(later.get("net")):
                    pairs.append((early["net"], later["net"], "LOSTMARK" in str(later.get("reason", ""))))
            if pairs:
                paired.append(dict(lane=lane, later_horizon_ms=horizon, assets=len(pairs),
                                   mean_1h=statistics.mean(a for a, _, _ in pairs),
                                   mean_later=statistics.mean(b for _, b, _ in pairs),
                                   median_delta=statistics.median(b-a for a, b, _ in pairs),
                                   identical_values=sum(a == b for a, b, _ in pairs),
                                   later_lostmark=sum(lost for _, _, lost in pairs)))
    return dict(sha256=digest, raw=counts, unique_assets=len({k[2] for k in first}),
                earliest_decisions=len(first), cohorts=result, base_start_exact_decision_pairs=paired)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("files", nargs="+")
    args = parser.parse_args()
    print(json.dumps({"authority": "NONE", "labels": "historical endpoint research, not executed returns",
                      "files": {p.rsplit("/", 1)[-1]: audit(p) for p in args.files}}, indent=2))
