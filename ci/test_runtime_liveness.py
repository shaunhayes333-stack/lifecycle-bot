"""Regression tests for the real second-start log-label loss."""
import unittest
from runtime_liveness import has_marker, loop_count


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


if __name__ == "__main__":
    unittest.main()
