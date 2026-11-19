#!/usr/bin/env python3
"""V5.0.8418 — behaviour tests for runtime_device_checks_8418.py parsers."""
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import runtime_device_checks_8418 as dc  # noqa: E402

SRC_MAIN = HERE.parent / "lifecycle_apk" / "app" / "src" / "main"
FOCUS = "  mCurrentFocus=Window{1a2b u0 com.lifecyclebot.aate/com.lifecyclebot.ui.%s}\n"


def dump(*nodes):
    body = "".join(f'<node package="{p}" resource-id="{r}" text="{t}" bounds="[0,0][10,10]" enabled="true"/>' for p, r, t in nodes)
    return f'<?xml version="1.0"?><hierarchy rotation="0"><node package="com.lifecyclebot.aate" resource-id="" text="">{body}</node></hierarchy>'


class Screens(unittest.TestCase):
    def test_layout_screen_needs_its_own_ids(self):
        ok, _ = dc.screen_verdict(dump(("com.lifecyclebot.aate", "com.lifecyclebot.aate:id/btnClearAlerts", "Clear")),
                                  FOCUS % "AlertsActivity", "AlertsActivity", SRC_MAIN)
        self.assertTrue(ok)
        ok, why = dc.screen_verdict(dump(("com.lifecyclebot.aate", "com.lifecyclebot.aate:id/somethingElse", "x")),
                                    FOCUS % "AlertsActivity", "AlertsActivity", SRC_MAIN)
        self.assertFalse(ok, why)

    def test_wrong_focus_or_foreign_package_fails(self):
        d = dump(("com.lifecyclebot.aate", "com.lifecyclebot.aate:id/btnClearAlerts", "Clear"))
        self.assertFalse(dc.screen_verdict(d, FOCUS % "MainActivity", "AlertsActivity", SRC_MAIN)[0])
        self.assertFalse(dc.screen_verdict(d, "mCurrentFocus=Window{x u0 com.android.launcher3/.Launcher}", "AlertsActivity", SRC_MAIN)[0])
        foreign = '<hierarchy><node package="com.android.systemui" resource-id="" text="x"/></hierarchy>'
        self.assertFalse(dc.screen_verdict(foreign, FOCUS % "AlertsActivity", "AlertsActivity", SRC_MAIN)[0])

    def test_splash_may_hand_over_to_main(self):
        d = dump(("com.lifecyclebot.aate", "com.lifecyclebot.aate:id/btnToggle", "Start Bot"))
        self.assertTrue(dc.screen_verdict(d, FOCUS % "MainActivity", "SplashActivity", SRC_MAIN)[0])

    def test_programmatic_screen_needs_text(self):
        self.assertTrue(dc.screen_verdict(dump(("com.lifecyclebot.aate", "", "Tuning")), FOCUS % "TuningActivity", "TuningActivity", SRC_MAIN)[0])
        self.assertFalse(dc.screen_verdict(dump(("com.lifecyclebot.aate", "", "")), FOCUS % "TuningActivity", "TuningActivity", SRC_MAIN)[0])

    def test_find_node(self):
        d = dump(("com.lifecyclebot.aate", "com.lifecyclebot.aate:id/btnToggle", "Start Bot"))
        self.assertEqual(dc.find_node(d, "id", "btnToggle"), (5, 5, "Start Bot"))
        self.assertIsNone(dc.find_node(d, "text", "Stop bot"))


class Logs(unittest.TestCase):
    def test_ticks_and_crashes(self):
        log = ("10-28 10:00:00.000 I/X( 1): 🧬 [BOT_LOOP_TICK] n=7 watch=3\n"
               "10-28 10:00:01.000 I/X( 1): [BOT_LOOP_TICK] n=9 watch=3\n")
        self.assertEqual(dc.ticks(log), [7, 9])
        self.assertEqual(dc.crash_lines(log), [])
        crash = "E/AndroidRuntime( 1): FATAL EXCEPTION: main\nE/AndroidRuntime( 1): Process: com.lifecyclebot.aate, PID: 1\n"
        self.assertEqual(len(dc.crash_lines(crash)), 1)
        self.assertTrue(dc.crash_lines("E/ActivityManager( 1): ANR in com.lifecyclebot.aate (x)\n"))
        # an unrelated process crash does not count against the app
        self.assertEqual(dc.crash_lines("E/AndroidRuntime( 1): FATAL EXCEPTION: main\nE/AndroidRuntime( 1): Process: com.android.phone, PID: 1\n"), [])

    def test_bootstrap(self):
        line = "I/F: 🧬[LIFECYCLE] #4 CANONICAL_BOOTSTRAP_READY_6515  events=8192 positions=0 duplicateMints=0 durMs=12 thread=x"
        self.assertEqual(dc.bootstrap(line), {"events": 8192, "positions": 0, "duplicateMints": 0})
        dup = "I/F: CANONICAL_BOOTSTRAP_READY_6515  events=8192 positions=63 duplicateMints=1 dupLots8820=ABC:PAPER:2x0.1 durMs=9 thread=x"
        self.assertEqual(dc.bootstrap(dup), {"events": 8192, "positions": 63, "duplicateMints": 1, "dupLots": "ABC:PAPER:2x0.1"})
        self.assertIsNone(dc.bootstrap("nothing"))

    def test_report(self):
        good = ("      store (§8324 restore without reset): bootSeasoned=true calibrationLanes=4 restored=kv seats=3\n"
                "  Explore shadow (§8347): restored8355=2 expiredOnRestore8355=0 waitsRestored8355=1 \n")
        self.assertTrue(dc.report_verdict(good, True)[0])
        self.assertFalse(dc.report_verdict(good.splitlines()[1], False)[0])
        self.assertFalse(dc.report_verdict(good.splitlines()[0], False)[0])


class Cli(unittest.TestCase):
    def run_cli(self, *args):
        return subprocess.run([sys.executable, str(HERE / "runtime_device_checks_8418.py"), *args], capture_output=True, text=True)

    def test_heapmap(self):
        good = ("AATE heap map (§8866) version=5.0.8866 taken=2026-11-19 16:00:00 uptimeS=600 heapUsedMb=180 heapMaxMb=512 dumpMs=2100 why=device-check\n"
                "format=JAVA PROFILE 1.0.3 idSize=4 file=210.0MB status=COMPLETE passes=14 readMs=41000\n"
                "objects=2100000 bytes=160.0MB classes=31000 samples=9000 layoutMismatch=0 unknownClass=0\n"
                "heaps: app=160.0MB/2100000 zygote=20.0MB/300000\n\n"
                "-- what holds the bytes (9000 objects sampled by size; 160.0MB represented, 120.0MB traced to a static field or a root) --\n"
                "     38.2MB  23.9% n=2001   static com.lifecyclebot.engine.Foo.rows > java.util.ArrayList.elementData\n")
        ok, detail = dc.heapmap_verdict(good)
        self.assertTrue(ok, detail)
        self.assertIn("staticOwners=1", detail)
        stopped = good.replace("status=COMPLETE", "status=STOPPED").replace("objects=2100000", "stopped=unknown heap record 0x91 at offset 4096 (segment 0x1c)\nobjects=2100000")
        ok, detail = dc.heapmap_verdict(stopped)
        self.assertFalse(ok)
        self.assertIn("0x91", detail)
        self.assertFalse(dc.heapmap_verdict(good.replace("layoutMismatch=0", "layoutMismatch=400000"))[0])
        self.assertFalse(dc.heapmap_verdict(good.replace("120.0MB traced", "10.0MB traced"))[0])
        self.assertFalse(dc.heapmap_verdict(good.replace("objects=2100000", "objects=900"))[0])
        self.assertFalse(dc.heapmap_verdict("something else")[0])
        # 8867: garbage is left out of the traced share's denominator
        with_garbage = good.replace("160.0MB represented, 120.0MB traced", "300.0MB represented, 40.0MB traced") \
            .replace("traced to a static field or a root) --\n", "traced to a static field or a root) --\n   GARBAGE rows (140.0MB sampled) are objects\n")
        ok, detail = dc.heapmap_verdict(with_garbage)
        self.assertTrue(ok, detail)
        self.assertIn("traced=25%", detail)

    def test_record_enforce_and_dup(self):
        with tempfile.TemporaryDirectory() as d:
            r = str(pathlib.Path(d, "r.json"))
            self.run_cli("record", r, "screens", "PASS", "ok")
            self.assertEqual(self.run_cli("enforce", r, "--require", "screens").returncode, 0)
            self.assertEqual(self.run_cli("enforce", r, "--require", "screens", "restart").returncode, 1)
            self.run_cli("record", r, "restart", "FAIL", "boom")
            self.assertEqual(self.run_cli("enforce", r, "--require", "screens", "restart").returncode, 1)
            a, b = pathlib.Path(d, "a"), pathlib.Path(d, "b")
            a.write_text("PAPER_TICKET_DISPATCHED_6514  attemptId=X1 mint=m\n")
            b.write_text("PAPER_TICKET_DISPATCHED_6514  attemptId=X2 mint=m\nPAPER_TICKET_RESUMED_6548 attemptId=X1\n")
            self.assertEqual(self.run_cli("dup-dispatch", str(a), str(b)).returncode, 0)
            b.write_text("PAPER_TICKET_DISPATCHED_6514  attemptId=X1 mint=m\n")
            self.assertEqual(self.run_cli("dup-dispatch", str(a), str(b)).returncode, 1)
            self.assertEqual(json.loads(pathlib.Path(r).read_text())["restart"]["status"], "FAIL")


if __name__ == "__main__":
    unittest.main(verbosity=1)
