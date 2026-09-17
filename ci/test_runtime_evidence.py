import unittest
from runtime_evidence import inspect_log

START = 'EXECUTION_SPINE_WINDOW_STARTED_6662 atMs=100 source=accepted_runtime_start'
OK = 'AATE.ACCEPTANCE: EXECUTION_SPINE_ACCEPTANCE_6647_OK windowStartMs=100 durationMs=120000'


class RuntimeEvidenceTest(unittest.TestCase):
    def test_missing_report_cannot_pass(self):
        self.assertFalse(inspect_log(START)['passed'])

    def test_complete_exact_window_passes(self):
        self.assertTrue(inspect_log(START + '\n' + OK)['passed'])

    def test_short_window_cannot_pass(self):
        self.assertFalse(inspect_log(START + '\n' + OK.replace('120000', '119999'))['passed'])

    def test_prior_run_pass_cannot_cover_restart(self):
        self.assertFalse(inspect_log(START + '\n' + OK + '\n' + START.replace('100', '200'))['passed'])

    def test_failure_is_not_hidden_by_later_success(self):
        fail = OK.replace('_OK ', '_FAIL ') + ' failures=CASH_DELTA'
        result = inspect_log(START + '\n' + fail + '\n' + OK)
        self.assertFalse(result['passed'])
        self.assertIn('CASH_DELTA', result['failures'])

    def test_receipts_are_deduplicated_not_attempts(self):
        receipt = 'PAPER_TICKET_TERMINAL_OPEN_6514 ticketId=1:PAPER:mint:BUY:QUALITY:1:1 paper=true committed=true'
        result = inspect_log('\n'.join([receipt, receipt, 'PAPER_TICKET_DISPATCHED_6514', 'PAPER_BUY_OK']))
        self.assertEqual(1, result['canonical_paper_buys'])
        self.assertFalse(result['passed'])

    def test_uncommitted_or_live_rows_never_count_as_paper_buys(self):
        row = 'PAPER_TICKET_TERMINAL_OPEN_6514 ticketId=1:LIVE:mint:BUY:QUALITY:1:1 paper=false committed=true'
        self.assertEqual(0, inspect_log(row)['canonical_paper_buys'])
        row = row.replace(':LIVE:', ':PAPER:').replace('paper=false', 'paper=true').replace('committed=true', 'committed=false')
        self.assertEqual(0, inspect_log(row)['canonical_paper_buys'])

    def test_detail_is_carried_separately_from_failure_reasons(self):
        """V5.0.6888 — the FAIL witness detail must reach failure_details so it
        can be annotated, without ever appearing in `failures` (which the gate
        treats as the reason list)."""
        fail = (OK.replace('_OK ', '_FAIL ') + ' failures=PHANTOM_SIZED_ONLY'
                ' detail=safety=7,phantom=3,[QUALITY,missing=NO_INTENT=3]')
        result = inspect_log(START + '\n' + fail)
        self.assertFalse(result['passed'])
        self.assertIn('PHANTOM_SIZED_ONLY', result['failures'])
        self.assertEqual(
            ['safety=7,phantom=3,[QUALITY,missing=NO_INTENT=3]'],
            result['failure_details'],
        )
        self.assertNotIn('safety=7,phantom=3,[QUALITY,missing=NO_INTENT=3]', result['failures'])

    def test_passing_run_reports_no_failure_details(self):
        result = inspect_log(START + '\n' + OK)
        self.assertTrue(result['passed'])
        self.assertEqual([], result['failure_details'])

    def test_detail_suffix_does_not_corrupt_the_failure_list(self):
        """V5.0.6883 — the FAIL witness now carries observed values after
        `failures=`. The parser must still read the failure list as one token
        and must not mistake the detail's inner key=value pairs for fields."""
        fail = (OK.replace('_OK ', '_FAIL ') + ' failures=PHANTOM_SIZED_ONLY'
                ' detail=safety=7,v3=4,phantom=3,[QUALITY,n=3,missing=NO_INTENT=3]')
        result = inspect_log(START + '\n' + fail)
        self.assertFalse(result['passed'])
        self.assertIn('PHANTOM_SIZED_ONLY', result['failures'])
        self.assertNotIn('7', result['failures'])

    def test_old_unscoped_ok_is_not_accepted(self):
        self.assertFalse(inspect_log(START + '\nEXECUTION_SPINE_ACCEPTANCE_6647_OK durationMs=120000')['passed'])


if __name__ == '__main__':
    unittest.main()
