#!/usr/bin/env python3
"""V5.0.8432 — behaviour tests for the runner replay harness (pure)."""
import json
import os
import pathlib
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import runner_replay_8432 as rr  # noqa: E402


def trace(**kw):
    base = {"mint": "M" * 44, "symbol": "SYM", "lane": "MOONSHOT", "peakPct": 500.0, "held": False, "binding": "",
            "refusedBy": "", "fdgVerdict": "", "hardSafetyPassed": True, "exitDepthUsd": 5000.0, "expectedNetPct": 12.0,
            "route": "PUMP_CURVE"}
    base.update(kw)
    return base


class Classify(unittest.TestCase):
    def test_held_safety_data_wrong_and_unproven(self):
        self.assertEqual(rr.classify_8432(trace(held=True)), ("HELD", "held_entry"))
        self.assertEqual(rr.classify_8432(trace(binding="HARD_BLOCK_RUGCHECK_FAILED"))[0], "CORRECT_SAFETY")
        self.assertEqual(rr.classify_8432(trace(binding="DATA_INCOMPLETE_DEFER_8136"))[0], "DATA_GAP")
        self.assertEqual(rr.classify_8432(trace(binding="TIMING_ADVERSE_8136"))[0], "WRONGLY_REFUSED")
        # no positive net at the decision: the refusal was not proven wrong
        self.assertEqual(rr.classify_8432(trace(binding="TIMING_ADVERSE_8136", expectedNetPct=-2.0))[0], "CORRECT_UNPROVEN")
        self.assertEqual(rr.classify_8432(trace(binding="TIMING_ADVERSE_8136", exitDepthUsd=None))[0], "CORRECT_UNPROVEN")
        self.assertEqual(rr.classify_8432(trace(binding="TIMING_ADVERSE_8136", hardSafetyPassed=False))[0], "CORRECT_UNPROVEN")

    def test_door_stem_drops_lane_and_detail(self):
        self.assertEqual(rr.door_stem_8432("CANDIDATE_ADMISSION_8136_LIVE_LANE_REALISED_LOSING_8325@MOONSHOT x"),
                         "CANDIDATE_ADMISSION_8136_LIVE_LANE_REALISED_LOSING_8325")
        self.assertEqual(rr.door_stem_8432("ENTRY8114_LANE_REALISED_LOSING_BINDS_8329_MOONSHOT"), "ENTRY8114_LANE_REALISED_LOSING_BINDS_8329")
        self.assertEqual(rr.door_stem_8432(""), "UNKNOWN")


class Replay(unittest.TestCase):
    def test_roll_up_and_render(self):
        rows = [trace(symbol="A", peakPct=894.0, binding="NATIVE_NO_KNOWN_MANIPULATION_EVIDENCE"),
                trace(symbol="B", peakPct=421.0, lane="SHITCOIN", binding="NATIVE_MCAP_TOO_LOW"),
                trace(symbol="C", peakPct=120.0, binding="TIMING_ADVERSE_8136"),           # under 4x: not a runner
                trace(symbol="D", peakPct=747.0, held=True),
                trace(symbol="E", peakPct=531.0, binding="SAFETY_REFUSED_8136"),
                trace(symbol="F", peakPct=300.0, binding="DATA_INCOMPLETE_DEFER_8136")]
        r = rr.replay_8432(rows)
        self.assertEqual(len(r["runners"]), 5)
        self.assertEqual(r["byVerdict"], {"WRONGLY_REFUSED": 2, "HELD": 1, "CORRECT_SAFETY": 1, "DATA_GAP": 1})
        self.assertEqual(r["wrongByDoor"], {"NATIVE_NO_KNOWN_MANIPULATION_EVIDENCE": 1, "NATIVE_MCAP_TOO_LOW": 1})
        self.assertEqual(r["runners"][0]["symbol"], "A")
        self.assertEqual(rr.replay_8432(rows, lane="SHITCOIN")["byVerdict"], {"WRONGLY_REFUSED": 1})
        text = rr.render_8432(r)
        self.assertIn("runners(>= +300%)=5", text)
        self.assertIn("NATIVE_MCAP_TOO_LOW=1", text)
        self.assertIn("SHITCOIN: wrongly_refused=1", text)

    def test_loads_jsonl_and_skips_bad_lines(self):
        with tempfile.TemporaryDirectory() as d:
            p = os.path.join(d, "traces.jsonl")
            with open(p, "w", encoding="utf-8") as fh:
                fh.write(json.dumps(trace(symbol="A")) + "\n")
                fh.write("not json\n\n")
                fh.write(json.dumps(trace(symbol="B", peakPct=50.0)) + "\n")
            rows = rr.load_8432(p)
            self.assertEqual([x["symbol"] for x in rows], ["A", "B"])
            self.assertEqual(len(rr.replay_8432(rows)["runners"]), 1)


if __name__ == "__main__":
    unittest.main()
