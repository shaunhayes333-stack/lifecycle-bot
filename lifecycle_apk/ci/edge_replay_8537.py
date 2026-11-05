#!/usr/bin/env python3
"""V5.0.8537 - walk-forward replay of the learned edge model on an exported dataset.

Input: the JSONL the phone saves next to the report (Downloads/AATE/aate_edge_dataset_8537_*.jsonl), one labelled decision
per line: the coin's features at the decision, the cost, the lane, the on-device prediction, and the booked net % outcome.

The replay walks the rows in decision order. For each row it predicts with the model trained ONLY on earlier rows, scores
that prediction against the outcome, and then learns the row (the same prequential rule the phone uses). It reports, per
lane, what buying only the model's picks would have earned against buying everything, so a feature or bin change can be
judged on recorded history before it ships.

Usage: python3 ci/edge_replay_8537.py DATASET.jsonl [--min-edge 1.0] [--warm 300] [--ridge 4.0]
Pure standard library. Bins mirror EdgeModel8537.kt (kept in step by Aate8537EdgeModelTest).
"""
import argparse
import json
import math
import sys

LIQ_EDGES = [3000.0, 10000.0, 50000.0, 250000.0]
MCAP_EDGES = [10000.0, 50000.0, 250000.0, 1000000.0, 5000000.0]
AGE_EDGES = [5.0, 30.0, 120.0, 720.0, 4320.0]
RUNUP_EDGES = [10.0, 40.0, 100.0, 300.0]
PEAK_EDGES = [0.5, 0.75, 0.9, 0.97]
DD_EDGES = [5.0, 15.0, 35.0, 60.0]
BP_EDGES = [45.0, 55.0, 65.0, 80.0]
CHG5_EDGES = [-10.0, -2.0, 2.0, 10.0, 30.0]
CHG1H_EDGES = [-30.0, -5.0, 5.0, 30.0, 100.0]
VOLACC_EDGES = [30.0, 55.0, 80.0]
TOP_EDGES = [10.0, 20.0, 35.0]
HOLDERVEL_EDGES = [0.5, 2.0, 6.0, 15.0]
TXVEL_EDGES = [2.0, 10.0, 40.0, 150.0]
CURVE_EDGES = [0.2, 0.5, 0.8, 0.98]
TURNOVER_EDGES = [0.1, 0.5, 2.0, 6.0]

NUMERIC = [("liq", LIQ_EDGES), ("mcap", MCAP_EDGES), ("age", AGE_EDGES), ("runup", RUNUP_EDGES), ("pxPeak", PEAK_EDGES),
           ("dd", DD_EDGES), ("bp", BP_EDGES), ("chg5m", CHG5_EDGES), ("chg1h", CHG1H_EDGES), ("volAccel", VOLACC_EDGES),
           ("top", TOP_EDGES), ("holderVel", HOLDERVEL_EDGES), ("txVel", TXVEL_EDGES), ("curve", CURVE_EDGES),
           ("turnover", TURNOVER_EDGES)]
LANES = ["SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED", "DIP_HUNTER", "QUALITY", "BLUECHIP",
         "TREASURY", "CASHGEN", "CYCLIC", "CORE"]
SOURCES = ["RAYDIUM_NEW_POOL", "PUMP_PORTAL", "PUMP_FUN_NEW", "PUMP_FUN_GRADUATE", "DEX_TRENDING", "DEX_BOOSTED",
           "WATCHLIST", "MARKET_HUNT", "SOLANA_BLUECHIP_WATCHLIST"]
PHASES = ["PRE_IGNITION", "IGNITION", "EXPANDING", "POST_PUMP_FADE"]
N_FLAGS = 7
Y_MIN, Y_MAX = -60.0, 200.0
AUTH_MIN_N = 30

DIM = 1 + sum(len(e) + 2 for _, e in NUMERIC) + (len(PHASES) + 1) + (len(SOURCES) + 1) + (len(LANES) + 1) + N_FLAGS + 2


def num(v):
    return v if isinstance(v, (int, float)) and math.isfinite(v) else float("nan")


def bin_of(v, edges):
    if not math.isfinite(v):
        return 0
    for i, e in enumerate(edges):
        if v < e:
            return i + 1
    return len(edges) + 1


def source_bucket(s):
    s = (s or "").upper()
    for i, name in enumerate(SOURCES):
        if name in s:
            return i
    return len(SOURCES)


def features(row):
    """Sparse feature vector {index: value}, the same layout as EdgeModel8537.vectorOf."""
    x = {0: 1.0}
    o = 1
    for key, edges in NUMERIC:
        x[o + bin_of(num(row.get(key)), edges)] = 1.0
        o += len(edges) + 2
    ph = (row.get("phase") or "").strip().upper()
    x[o + (PHASES.index(ph) if ph in PHASES else len(PHASES))] = 1.0
    o += len(PHASES) + 1
    x[o + source_bucket(row.get("src"))] = 1.0
    o += len(SOURCES) + 1
    ln = (row.get("lane") or "").strip().upper()
    x[o + (LANES.index(ln) if ln in LANES else len(LANES))] = 1.0
    o += len(LANES) + 1
    flags = int(row.get("flags") or 0)
    for b in range(N_FLAGS):
        if flags & (1 << b):
            x[o + b] = 1.0
    o += N_FLAGS
    cost = num(row.get("cost"))
    x[o] = min(3.0, max(0.0, cost / 10.0)) if math.isfinite(cost) else 0.5
    v3 = num(row.get("v3"))
    x[o + 1] = min(1.5, max(-1.0, v3 / 100.0)) if math.isfinite(v3) else 0.0
    return {k: v for k, v in x.items() if v != 0.0}


class Ridge:
    def __init__(self, dim, lam):
        self.dim, self.lam = dim, lam
        self.xtx = [[0.0] * dim for _ in range(dim)]
        self.xty = [0.0] * dim
        self.count = 0
        self.w = None

    def learn(self, x, y):
        items = list(x.items())
        for i, xi in items:
            self.xty[i] += xi * y
            row = self.xtx[i]
            for j, xj in items:
                row[j] += xi * xj
        self.count += 1

    def solve(self):
        d = self.dim
        a = [r[:] for r in self.xtx]
        for i in range(d):
            a[i][i] += 1e-3 if i == 0 else self.lam
        L = [[0.0] * d for _ in range(d)]
        for i in range(d):
            for j in range(i + 1):
                s = a[i][j] - sum(L[i][k] * L[j][k] for k in range(j))
                if i == j:
                    if s <= 0:
                        return
                    L[i][i] = math.sqrt(s)
                else:
                    L[i][j] = s / L[j][j]
        z = [0.0] * d
        for i in range(d):
            z[i] = (self.xty[i] - sum(L[i][k] * z[k] for k in range(i))) / L[i][i]
        w = [0.0] * d
        for i in range(d - 1, -1, -1):
            w[i] = (z[i] - sum(L[k][i] * w[k] for k in range(i + 1, d))) / L[i][i]
        self.w = w

    def predict(self, x):
        if self.w is None:
            return float("nan")
        return sum(self.w[i] * v for i, v in x.items())


class Tally:
    def __init__(self):
        self.all = []
        self.sel = []
        self.neg = []

    @staticmethod
    def stat(v):
        n = len(v)
        if n == 0:
            return 0, float("nan"), float("nan"), float("nan")
        m = sum(v) / n
        var = sum((a - m) ** 2 for a in v) / n
        se = math.sqrt(var / n) if n > 1 else float("inf")
        return n, m, se, sum(1 for a in v if a > 0) / n


def fmt(v):
    return "%+.2f%%" % v if math.isfinite(v) else "-"


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("dataset")
    ap.add_argument("--min-edge", type=float, default=1.0)
    ap.add_argument("--warm", type=int, default=300)
    ap.add_argument("--lane-own", type=int, default=600)
    ap.add_argument("--ridge", type=float, default=4.0)
    ap.add_argument("--solve-every", type=int, default=200)
    args = ap.parse_args(argv)

    rows = []
    with open(args.dataset, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                r = json.loads(line)
            except ValueError:
                continue
            # V5.0.8546 - an unfillable entry ("fill": false) is research coverage, never a training row
            if r.get("fill", True) is False:
                continue
            if isinstance(r.get("net"), (int, float)) and math.isfinite(r["net"]):
                rows.append(r)
    rows.sort(key=lambda r: r.get("t", 0))
    if not rows:
        print("no labelled rows")
        return 1

    glob = Ridge(DIM, args.ridge)
    lanes, tallies, since = {}, {}, {}
    for r in rows:
        x = features(r)
        lane = (r.get("lane") or "").strip().upper()
        own = lanes.get(lane)
        if own is not None and own.count >= args.lane_own:
            pred = own.predict(x)
        elif glob.count >= args.warm:
            pred = glob.predict(x)
        else:
            pred = float("nan")
        y = min(Y_MAX, max(Y_MIN, r["net"]))
        t = tallies.setdefault(lane, Tally())
        t.all.append(y)
        if math.isfinite(pred):
            if pred >= args.min_edge:
                t.sel.append(y)
            elif pred < 0.0:
                t.neg.append(y)
        glob.learn(x, y)
        lanes.setdefault(lane, Ridge(DIM, args.ridge)).learn(x, y)
        for key, m in (("GLOBAL", glob), (lane, lanes[lane])):
            since[key] = since.get(key, 0) + 1
            if m.w is None or since[key] >= args.solve_every:
                m.solve()
                since[key] = 0

    print("edge replay 8537: rows=%d dim=%d minEdge=+%.1f%% warm=%d laneOwn=%d ridge=%.1f" %
          (len(rows), DIM, args.min_edge, args.warm, args.lane_own, args.ridge))
    print("%-16s %7s %9s %7s %9s %9s %6s %9s %s" % ("lane", "all.n", "all.mean", "pick.n", "pick.mean", "pick.se", "win", "avoid", "verdict"))
    total_sel, total_all = [], []
    for lane in sorted(tallies):
        t = tallies[lane]
        an, am, _, _ = Tally.stat(t.all)
        sn, sm, se, sw = Tally.stat(t.sel)
        _, nm, _, _ = Tally.stat(t.neg)
        auth = sn >= AUTH_MIN_N and math.isfinite(sm) and math.isfinite(se) and sm - se > 0 and (not math.isfinite(am) or sm > am)
        print("%-16s %7d %9s %7d %9s %9s %5s %9s %s" % (lane, an, fmt(am), sn, fmt(sm), fmt(se),
              ("%.0f%%" % (sw * 100)) if math.isfinite(sw) else "-", fmt(nm), "AUTHORITY" if auth else "learning"))
        total_sel += t.sel
        total_all += t.all
    _, am, _, _ = Tally.stat(total_all)
    sn, sm, se, _ = Tally.stat(total_sel)
    print("ALL lanes: everything %s per decision; model picks n=%d %s +/- %s (lift %s)" %
          (fmt(am), sn, fmt(sm), fmt(se), fmt(sm - am) if math.isfinite(sm) and math.isfinite(am) else "-"))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
