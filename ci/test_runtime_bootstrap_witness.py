import unittest

from runtime_bootstrap_witness import (annotations, rejected_class,
                                       service_failure_type, terminal_failure)

VERIFIER = ("java.lang.VerifyError: Verifier rejected class com.lifecyclebot.engine.TreasuryWalletManager: "
            "void com.lifecyclebot.engine.TreasuryWalletManager.init(android.content.Context) failed to verify: "
            "[0x5A] copy1 v3<-v257 type=Undefined cat=1")
FAILED_LINE = ("10-15 19:30:01.000 I/AATE_FORENSIC( 123): \U0001F9EC[LIFECYCLE] #88 SERVICE_BOOTSTRAP_FAILED_6516  "
               "type=VerifyError msg=Verifier rejected class com.lifecyclebot.engine.TreasuryWalletManager: x durMs=9")
BLOCKED_LINE = ("10-15 19:31:01.000 I/AATE_FORENSIC( 123): START_BLOCKED_SERVICE_BOOTSTRAP_FAILED_6516  "
                "failure=VerifyError: " + VERIFIER + " action=surface_failure")


class BootstrapWitnessTest(unittest.TestCase):
    def test_failure_type_is_the_exception_not_the_verifier_register_type(self):
        text = "\n".join([FAILED_LINE, BLOCKED_LINE])
        self.assertEqual(service_failure_type(text), "VerifyError")

    def test_start_blocked_echo_alone_is_not_a_failure_line(self):
        self.assertEqual(service_failure_type(BLOCKED_LINE), "")

    def test_rejected_class_is_named(self):
        self.assertEqual(rejected_class(BLOCKED_LINE), "com.lifecyclebot.engine.TreasuryWalletManager")

    def test_terminal_failure_requires_no_ready(self):
        self.assertTrue(terminal_failure(FAILED_LINE))
        self.assertFalse(terminal_failure(FAILED_LINE + "\nSERVICE_BOOTSTRAP_READY_6516  durMs=1"))
        self.assertFalse(terminal_failure("SERVICE_BOOTSTRAP_PHASE_6516  phase=CORE_STORES_READY"))

    def test_failure_before_the_last_ui_start_does_not_count(self):
        stale = FAILED_LINE + "\nUI_START_DISPATCHED_6517 source=ui\nSERVICE_BOOTSTRAP_PHASE_6516  phase=CANONICAL_READY"
        self.assertFalse(terminal_failure(stale))
        self.assertTrue(terminal_failure("UI_START_DISPATCHED_6517 source=ui\n" + FAILED_LINE))

    def test_annotations_name_step_class_and_stall(self):
        text = "\n".join([
            FAILED_LINE, BLOCKED_LINE,
            "W/AATE_BOOTSTRAP: SERVICE_BOOTSTRAP_STALL_8144 phase=CORE_STORES_READY stalledMs=20004 thread=t "
            "state=RUNNABLE at=com.lifecyclebot.engine.TreasuryWalletManager.init(TreasuryWalletManager.kt:65)",
            "E/AATE_BOOTSTRAP: SERVICE_BOOTSTRAP_FAILED_STEP_8144 phase=CORE_STORES_READY sincePhaseMs=40 "
            "type=VerifyError rejected=com.lifecyclebot.engine.TreasuryWalletManager at=x",
        ])
        joined = "\n".join(annotations(text))
        self.assertIn("BOOTSTRAP_FAILURE_8144::type=VerifyError", joined)
        self.assertIn("BOOTSTRAP_REJECTED_CLASS_8144::com.lifecyclebot.engine.TreasuryWalletManager", joined)
        self.assertIn("BOOTSTRAP_FAILED_STEP_8144::SERVICE_BOOTSTRAP_FAILED_STEP_8144 phase=CORE_STORES_READY", joined)
        self.assertIn("BOOTSTRAP_STALL_8144::SERVICE_BOOTSTRAP_STALL_8144 phase=CORE_STORES_READY", joined)

    def test_clean_capture_has_no_annotations(self):
        self.assertEqual(annotations("SERVICE_BOOTSTRAP_READY_6516  durMs=1200"), [])


if __name__ == "__main__":
    unittest.main()
