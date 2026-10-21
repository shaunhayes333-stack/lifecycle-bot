"""Regression tests for the real second-start log-label loss."""
import unittest
from pathlib import Path
from runtime_liveness import has_marker, loop_count, since_epoch
from datetime import datetime, timezone


class RuntimeLivenessTest(unittest.TestCase):
    def test_tick_is_loop_evidence(self):
        self.assertTrue(has_marker("I/AATE.FORENSIC: 🧬[SCAN_CB] #8284 _loop  🧬[BOT_LOOP_TICK] n=1 watch=0 localTokens=0", "BOT_LOOP_TICK"))

    def test_real_running_loop_top_is_equivalent(self):
        self.assertTrue(has_marker("I/AATE.FORENSIC: 🧬[LOOP_TOP] #36350 loop=6 running=true loopJob=true scAlive=true", "BOT_LOOP_TICK"))

    def test_stopped_loop_is_not_evidence(self):
        self.assertFalse(has_marker("🧬[LOOP_TOP] #1 loop=6 running=false loopJob=true", "BOT_LOOP_TICK"))

    def test_inactive_job_is_not_evidence(self):
        self.assertFalse(has_marker("🧬[LOOP_TOP] #1 loop=6 running=true loopJob=false", "BOT_LOOP_TICK"))

    def test_zero_loop_is_not_evidence(self):
        self.assertEqual(0, loop_count("[LOOP_TOP] #1 loop=0 running=true loopJob=true\n[BOT_LOOP_TICK] n=0 watch=0"))

    def test_report_counter_is_not_runtime_proof(self):
        self.assertFalse(has_marker("BOT_LOOP_TICK=97 (loop iterations)\nWaiting for BOT_LOOP_TICK", "BOT_LOOP_TICK"))

    def test_bootstrap_still_requires_actual_requested_marker(self):
        self.assertTrue(has_marker("SERVICE_BOOTSTRAP_READY_6516", "SERVICE_BOOTSTRAP_READY_6516|BOT_LOOP_TICK"))
        self.assertFalse(has_marker("START_REQUESTED", "SERVICE_BOOTSTRAP_READY_6516|BOT_LOOP_TICK"))

    def test_no_loop_or_completed_window_is_invented(self):
        self.assertEqual(0, loop_count("EXECUTION_SPINE_WINDOW_STARTED_6662"))
        self.assertFalse(has_marker("[LOOP_TOP] #1 loop=6 running=true loopJob=true", "EXECUTION_SPINE_ACCEPTANCE_6647_OK"))

    def test_multiple_real_events_are_counted(self):
        self.assertEqual(2, loop_count("[BOT_LOOP_TICK] n=2 watch=5\n[LOOP_TOP] #7 loop=6 running=true loopJob=true"))

    def test_stop_ui_waits_for_running_not_queued_start(self):
        script = Path(__file__).with_name("runtime-test.sh").read_text()
        self.assertIn('if mode in ("id", "id-running")', script)
        self.assertIn('match = match and node.attrib.get("text", "").strip().lower() == "stop bot"', script)
        self.assertIn('ui_tap id-running btnToggle ui_stop_button.xml', script)
        self.assertIn('UI_TAP_TIMEOUT_S_7168=150', script)
        self.assertIn('ui_tap text "Stop bot" ui_stop_confirm.xml', script)
        self.assertIn('wait_log_marker "LIFECYCLE_STOP_COMPLETE"', script)

    def test_restart_cannot_reuse_first_session_loop(self):
        cutoff = datetime(2026, 10, 12, 7, 52, 30, tzinfo=timezone.utc).timestamp()
        old = "10-12 07:52:26.290 I/AATE: [BOT_LOOP_TICK] n=5 watch=0"
        start = "10-12 07:52:34.024 I/AATE: UI_START_DISPATCHED_6517"
        self.assertFalse(has_marker(since_epoch(old + "\n" + start, cutoff), "BOT_LOOP_TICK"))
        new = "10-12 07:52:35.000 I/AATE: [BOT_LOOP_TICK] n=1 watch=0"
        self.assertTrue(has_marker(since_epoch(old + "\n" + start + "\n" + new, cutoff), "BOT_LOOP_TICK"))

    def test_restart_cutoff_handles_year_boundary(self):
        cutoff = datetime(2026, 12, 31, 23, 59, 59, tzinfo=timezone.utc).timestamp()
        logs = "12-31 23:59:58.000 I/AATE: old\n01-01 00:00:00.000 I/AATE: new"
        self.assertEqual("01-01 00:00:00.000 I/AATE: new", since_epoch(logs, cutoff))


if __name__ == "__main__":
    unittest.main()
