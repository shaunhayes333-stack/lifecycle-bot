#!/usr/bin/env python3
"""V5.0.8418 — behaviour tests for upgrade_data_access_8418 (root -> run-as -> package fallback)."""
import pathlib
import sys
import unittest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import upgrade_data_access_8418 as ua  # noqa: E402

PKG = "com.lifecyclebot.aate"


class Mode(unittest.TestCase):
    def test_root_wins(self):
        self.assertEqual(ua.choose_mode_8418("0\r\n", "10123"), ua.ROOT_8418)

    def test_run_as_without_root(self):
        self.assertEqual(ua.choose_mode_8418("2000\n", "10123\n"), ua.RUN_AS_8418)

    def test_package_when_neither(self):
        self.assertEqual(ua.choose_mode_8418("2000", "run-as: package not debuggable: com.lifecyclebot.aate"), ua.PACKAGE_8418)
        self.assertEqual(ua.choose_mode_8418("", None), ua.PACKAGE_8418)
        self.assertEqual(ua.choose_mode_8418(None, ""), ua.PACKAGE_8418)


class Listing(unittest.TestCase):
    def test_commands(self):
        self.assertEqual(ua.list_command_8418(ua.ROOT_8418, PKG, "databases"),
                         ["adb", "shell", "ls", "-1", "/data/data/com.lifecyclebot.aate/databases"])
        self.assertEqual(ua.list_command_8418(ua.RUN_AS_8418, PKG, "shared_prefs"),
                         ["adb", "shell", "run-as", PKG, "ls", "-1", "shared_prefs"])
        self.assertIsNone(ua.list_command_8418(ua.PACKAGE_8418, PKG, "databases"))

    def test_parse_drops_sidecars_and_errors(self):
        out = ua.parse_listing_8418("databases", "learning_kv.db\nlearning_kv.db-wal\nlearning_kv.db-journal\n\n"
                                    "shadow_cold_8397.db\r\n")
        self.assertEqual(out, {"databases/learning_kv.db", "databases/shadow_cold_8397.db"})
        self.assertEqual(ua.parse_listing_8418("databases", "run-as: package not debuggable: x"), set())
        # V5.0.8423 — a prefs commit in flight (<name>.xml.bak) is not a data file the upgrade must carry
        self.assertEqual(ua.parse_listing_8418("shared_prefs", "a.xml\na.xml.bak\nperps_positions_stocks.xml.bak\n"),
                         {"shared_prefs/a.xml"})
        self.assertEqual(ua.parse_listing_8418("databases", "ls: /data/data/x/databases: No such file or directory"), set())


class Diskstats(unittest.TestCase):
    DUMP = ('Latency: 1ms [512B Data Write]\nApp Size: 100\n'
            'Package Names: ["com.android.chrome","com.lifecyclebot.aate"]\n'
            'App Sizes: [1000,2000]\nApp Data Sizes: [300,4096]\nCache Sizes: [1,2]\n')

    def test_reads_the_package(self):
        self.assertEqual(ua.diskstats_data_bytes_8418(self.DUMP, PKG), 4096)

    def test_unknown_is_none_never_zero(self):
        self.assertIsNone(ua.diskstats_data_bytes_8418(self.DUMP, "com.other"))
        self.assertIsNone(ua.diskstats_data_bytes_8418("", PKG))
        self.assertIsNone(ua.diskstats_data_bytes_8418('Package Names: ["com.lifecyclebot.aate"]\nApp Data Sizes: [1,2]\n', PKG))


if __name__ == "__main__":
    unittest.main()
