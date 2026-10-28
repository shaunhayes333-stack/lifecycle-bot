import importlib.util
import pathlib
import tempfile
import unittest


spec = importlib.util.spec_from_file_location('delta', pathlib.Path(__file__).with_name('unit_regression_delta.py'))
delta = importlib.util.module_from_spec(spec)
spec.loader.exec_module(delta)


class RenamedTerminalContractTest(unittest.TestCase):
    cls = 'com.lifecyclebot.engine.Aate6679PaperOutcomeAuthorityTest'
    old = 'perps paper terminal is canonical and legacy publishers are live only'
    new = 'perps terminal economics have one canonical publisher in both modes'

    def compare(self, body=None):
        baseline = {'failures': [], 'suites': {self.cls: [self.old]}}
        with tempfile.TemporaryDirectory() as folder:
            if body is not None:
                pathlib.Path(folder, 'TEST-terminal.xml').write_text(
                    f'<testsuite name="{self.cls}"><testcase classname="{self.cls}" name="{self.new}">{body}</testcase></testsuite>')
            return delta.compare(baseline, folder)

    def test_strengthened_successful_test_covers_old_identity(self):
        observed, failed, missing, skipped, unexpected = self.compare('')
        self.assertIn((self.cls, self.new), observed)
        self.assertFalse(failed or missing or skipped or unexpected)

    def test_replacement_failure_is_not_allowed(self):
        _, _, missing, _, unexpected = self.compare('<failure type="AssertionError" message="canonical proof missing"/>')
        self.assertFalse(missing)
        self.assertEqual(1, len(unexpected))

    def test_replacement_cannot_be_skipped(self):
        _, _, _, skipped, _ = self.compare('<skipped/>')
        self.assertIn((self.cls, self.new), skipped)

    def test_missing_replacement_still_blocks_release(self):
        _, _, missing, _, _ = self.compare()
        self.assertIn((self.cls, self.old), missing)


if __name__ == '__main__':
    unittest.main()
