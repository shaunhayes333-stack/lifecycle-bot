"""Compare observed first-hour outcomes, using the earliest decision per mint in each cohort."""
import argparse
import collections
import csv
import json
import statistics
import sys


def summarize(rows):
    cohorts = collections.defaultdict(dict)
    for r in rows:
        if not r.get("hourElapsed"):
            continue
        key = r["lane"], r["setup"], {None: "all", 0: "refused", 1: "admitted"}[r["admitted"]]
        prior = cohorts[key].get(r["mint"])
        if prior is None or r["decisionMs"] < prior["decisionMs"]:
            cohorts[key][r["mint"]] = r
    for key, by_mint in sorted(cohorts.items()):
        values = list(by_mint.values())
        nets = [r["hourNetPct"] for r in values if r["hourNetPct"] is not None]
        peaks = [r["observedPeakSolPerToken"] / r["entrySolPerToken"] for r in values
                 if r["entrySolPerToken"] and r["observedPeakSolPerToken"]]
        yield (*key, len(values), len(nets), statistics.mean(nets) if nets else None,
               statistics.median(nets) if nets else None, len(peaks),
               sum(p >= 5 for p in peaks), sum(p >= 10 for p in peaks))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("export")
    args = parser.parse_args()
    writer = csv.writer(sys.stdout)
    writer.writerow(["lane", "setup", "decision", "mature_mints", "priced_hour_ends",
                     "mean_net_pct", "median_net_pct", "observed_paths", "observed_5x", "observed_10x"])
    with open(args.export, encoding="utf-8") as source:
        writer.writerows(summarize(json.loads(line) for line in source))
