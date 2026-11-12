#!/usr/bin/env python3
"""V5.0.8537 - walk-forward replay of the learned edge model on an exported dataset.

Input: the JSONL the phone saves next to the report (Downloads/AATE/aate_edge_dataset_8537_*.jsonl), one labelled decision
per line: the coin's features at the decision, the cost, the lane, the on-device prediction, and the booked net % outcome.

The replay walks decisions in time order, releasing each training label only after its recorded arrival
(or the full label grace for older exports). Features, log-return targets and decay follow the phone model. It reports, per
lane, what buying only the model's picks would have earned against buying everything, so a feature or bin change can be
judged on recorded history before it ships.

Usage: python3 ci/edge_replay_8537.py DATASET.jsonl [--min-edge 1.0] [--warm 300] [--ridge 4.0]
Pure standard library. Bins mirror EdgeModel8537.kt (kept in step by Aate8537EdgeModelTest).
"""
import argparse
import json
import math
import sys
import heapq
import re

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
NEW_BUYER_EDGES = [1.0, 2.0, 5.0, 10.0]

NUMERIC = [("liq", LIQ_EDGES), ("mcap", MCAP_EDGES), ("age", AGE_EDGES), ("runup", RUNUP_EDGES), ("pxPeak", PEAK_EDGES),
           ("dd", DD_EDGES), ("bp", BP_EDGES), ("chg5m", CHG5_EDGES), ("chg1h", CHG1H_EDGES), ("volAccel", VOLACC_EDGES),
           ("top", TOP_EDGES), ("holderVel", HOLDERVEL_EDGES), ("txVel", TXVEL_EDGES), ("curve", CURVE_EDGES),
           ("turnover", TURNOVER_EDGES), ("newBuyers15s8666", NEW_BUYER_EDGES)]
LANES = ["SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED", "DIP_HUNTER", "QUALITY", "BLUECHIP",
         "TREASURY", "CASHGEN", "CYCLIC", "CORE"]
SOURCES = ["RAYDIUM_NEW_POOL", "PUMP_PORTAL", "PUMP_FUN_NEW", "PUMP_FUN_GRADUATE", "DEX_TRENDING", "DEX_BOOSTED",
           "WATCHLIST", "MARKET_HUNT", "SOLANA_BLUECHIP_WATCHLIST"]
PHASES = ["PRE_IGNITION", "IGNITION", "EXPANDING", "POST_PUMP_FADE"]
N_FLAGS = 7
FORGET = 0.9997
AUTH_MIN_N = 30

DIM = 1 + sum(len(e) + 2 for _, e in NUMERIC) + (len(PHASES) + 1) + (len(SOURCES) + 1) + (len(LANES) + 1) + N_FLAGS + 4


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
    x[o + 2] = 1.0 if row.get("sustainedDemand") is True else 0.0
    x[o + 3] = 1.0 if row.get("marketReclaim") is True else 0.0
    return {k: v for k, v in x.items() if v != 0.0}


class Ridge:
    def __init__(self, dim, lam):
        self.dim, self.lam = dim, lam
        self.xtx = [[0.0] * dim for _ in range(dim)]
        self.xty = [0.0] * dim
        self.count = 0
        self.w = None
        self.scale = 1.0

    def learn(self, x, y):
        self.scale *= FORGET
        items = list(x.items())
        for i, xi in items:
            self.xty[i] += xi * y / self.scale
            row = self.xtx[i]
            for j, xj in items:
                row[j] += xi * xj / self.scale
        self.count += 1

    def solve(self):
        d = self.dim
        a = [[v * self.scale for v in r] for r in self.xtx]
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
            z[i] = (self.xty[i] * self.scale - sum(L[i][k] * z[k] for k in range(i))) / L[i][i]
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


def valid_label(row):
    if row.get("fill") is not True or not row.get("mint") or not math.isfinite(num(row.get("t"))) or num(row.get("t")) <= 0:
        return False
    lane = (row.get("lane") or "").strip().upper()
    # FieldManual7715 mandate order: CORE/BLUECHIP and CYCLIC keep their
    # 15-minute target even when their display lane has a CRYPTO prefix.
    patient = any(k in lane for k in ("CYCLIC", "MOONSHOT", "SHITCOIN", "SNIPER", "SNIPE", "MICRO", "MANIPULATED", "PUMP", "LAUNCH", "EXPRESS", "NARRATIVE", "CULT", "MOMENTUM", "AGGRESSIVE", "WHALE", "COPY", "DIP", "RANGE", "QUALITY", "BLUE", "CORE", "LONG_HOLD", "DEFENSIVE"))
    scalp = any(k in lane for k in ("TREASURY", "CASH", "PERP", "LEV", "FUNDING", "BASIS", "CARRY", "ARB", "CRYPTO", "MARKET", "FOREX", "METAL", "COMMOD", "STOCK", "UNIVERSE"))
    horizon = 300_000 if scalp and not patient else 900_000
    if not lane or row.get("horizonMs") != horizon:
        return False
    mint = row["mint"]
    if re.fullmatch(r"[1-9A-HJ-NP-Za-km-z]{32,44}", mint) and not mint.lower().endswith("pump") and row.get("venueScopeEpoch", 0) < 8660:
        return False
    net, cap = num(row.get("net")), num(row.get("mcap"))
    ceiling = 400.0 if not math.isfinite(cap) or cap <= 0 else 5000.0 if cap < 100_000 else 1200.0
    return math.isfinite(net) and net >= -100.0 and (net <= ceiling or (row.get("witness") is True and net <= 10_000))


def label_available_at(row):
    earliest = row["t"] + row["horizonMs"]
    observed = row.get("labelObservedAtMs")
    # Older exports omit arrival time: wait until the entire permitted label
    # grace has elapsed. Never assume a delayed label arrived at its horizon.
    return max(earliest, observed) if isinstance(observed, (int, float)) and math.isfinite(observed) else earliest + min(600_000, max(60_000, row["horizonMs"] // 2))


def replay_predictions(rows, args, ridge_factory=Ridge):
    glob = ridge_factory(DIM, args.ridge)
    lanes, since, pending = {}, {}, []
    for sequence, r in enumerate(sorted(rows, key=lambda row: row["t"])):
        while pending and pending[0][0] <= r["t"]:
            _, _, past = heapq.heappop(pending)
            lane = past["lane"].strip().upper()
            x = features(past)
            # The phone model (V5.0.8722) now learns the outcome the lane's exits bank and reads three feature crosses;
            # this offline replay still fits the log return on the single-feature bins.
            y = 100.0 * math.log1p(max(-99.9, past["net"]) / 100.0)
            for key, model in (("GLOBAL", glob), (lane, lanes.setdefault(lane, ridge_factory(DIM, args.ridge)))):
                model.learn(x, y)
                since[key] = since.get(key, 0) + 1
                if model.w is None or since[key] >= args.solve_every:
                    model.solve(); since[key] = 0
        lane = r["lane"].strip().upper()
        own = lanes.get(lane)
        model = own if own is not None and own.count >= args.lane_own else glob if glob.count >= args.warm else None
        log_prediction = model.predict(features(r)) if model is not None else float("nan")
        prediction = 100.0 * math.expm1(log_prediction / 100.0) if math.isfinite(log_prediction) else log_prediction
        yield r, prediction
        heapq.heappush(pending, (label_available_at(r), sequence, r))


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("dataset")
    ap.add_argument("--min-edge", type=float, default=1.0)
    ap.add_argument("--warm", type=int, default=300)
    ap.add_argument("--lane-own", type=int, default=600)
    ap.add_argument("--ridge", type=float, default=4.0)
    ap.add_argument("--solve-every", type=int, default=50)
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
            if valid_label(r):
                rows.append(r)
    if not rows:
        print("no eligible labelled rows")
        return 1
    tallies = {}
    for r, pred in replay_predictions(rows, args):
        lane = (r.get("lane") or "").strip().upper()
        y = r["net"]  # outcome statistics stay in uncapped economic percent
        t = tallies.setdefault(lane, Tally())
        t.all.append(y)
        if math.isfinite(pred):
            if pred >= args.min_edge:
                t.sel.append(y)
            elif pred < 0.0:
                t.neg.append(y)

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
