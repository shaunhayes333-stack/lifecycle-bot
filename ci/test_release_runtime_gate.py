import unittest
from release_runtime_gate import verdict


class ReleaseRuntimeGateTest(unittest.TestCase):
    def run_record(self, **overrides):
        return dict(id=1, head_sha='candidate', head_branch='main', event='push',
                    status='completed', conclusion='success', **overrides)

    def test_runtime_starts_independently_before_publisher_waits(self):
        from pathlib import Path
        root = Path(__file__).resolve().parent.parent
        runtime = (root / '.github/workflows/runtime-test.yml').read_text()
        self.assertIn('on:\n  push:\n    branches: [ main, master ]', runtime)
        self.assertNotIn('workflow_run:', runtime)
        build = (root / '.github/workflows/build.yml').read_text()
        self.assertLess(build.index('name: Require successful runtime validation'),
                        build.index("name: AATE_v${{ steps.aate_build.outputs.version_name }}"))

    def test_exact_main_push_success_is_required(self):
        self.assertEqual('PASS', verdict([self.run_record()], 'candidate'))
        for field, value in [('head_sha', 'old'), ('head_branch', 'repair'), ('event', 'pull_request')]:
            record = self.run_record()
            record[field] = value
            self.assertEqual('WAIT', verdict([record], 'candidate'))

    def test_missing_or_running_evidence_cannot_publish(self):
        self.assertEqual('WAIT', verdict([], 'candidate'))
        record = self.run_record()
        record.update(status='in_progress', conclusion=None)
        self.assertEqual('WAIT', verdict([record], 'candidate'))

    def test_every_unsuccessful_terminal_refuses_publication(self):
        for conclusion in ['failure', 'cancelled', 'timed_out', 'skipped', 'neutral', None]:
            record = self.run_record()
            record['conclusion'] = conclusion
            self.assertEqual('FAIL', verdict([record], 'candidate'))

    def test_older_success_cannot_hide_a_newer_failure(self):
        older = self.run_record()
        newer = dict(older, id=2, conclusion='failure')
        self.assertEqual('FAIL', verdict([newer, older], 'candidate'))


if __name__ == '__main__':
    unittest.main()
