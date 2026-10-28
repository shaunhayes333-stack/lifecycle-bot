#!/usr/bin/env python3
"""Fail on new unit failures or missing tests against the recorded main run.

The baseline is evidence, not a claim that historical failures are acceptable
forever. Required release tests run separately and must all pass. This check
also runs the complete historical suite and reports every inherited failure.
"""
import argparse
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

# 8090 replaces two source-contract tests with assertions for the repaired
# attribution and HOLD semantics. Both replacements are mandatory release tests.
RENAMED_TESTS = {
    # 8417 strengthens the old paper-only terminal contract to one canonical publisher in both modes.
    ('com.lifecyclebot.engine.Aate6679PaperOutcomeAuthorityTest', 'perps paper terminal is canonical and legacy publishers are live only'):
        ('com.lifecyclebot.engine.Aate6679PaperOutcomeAuthorityTest', 'perps terminal economics have one canonical publisher in both modes'),
    ('com.lifecyclebot.engine.Aate8082CausalPipelineTest', 'exactStrategyCountsEachPositionOnce'):
        ('com.lifecyclebot.engine.Aate8082CausalPipelineTest', 'incompleteExactIdentityDoesNotConsumeTerminalCredit'),
    ('com.lifecyclebot.engine.Aate8082CausalPipelineTest', 'exitCortexIsGradedByTheVerifiedCanonicalClose'):
        ('com.lifecyclebot.engine.Aate8082CausalPipelineTest', 'closeRecordsActualEconomicsAndPreservesFutureHoldSamples'),
}

def stable_message(message):
    # Runtime-generated IDs and source offsets differ on every run. Preserve
    # the assertion, reason, lane and economic values; remove only diagnostics.
    message = message.split(' active=ExecutionIntent(', 1)[0]
    message = re.sub(r'RaceBuy6512\d+', 'RaceBuy6512<ID>', message)
    message = re.sub(r'generatedAtMs=\d+', 'generatedAtMs=<TIME>', message)
    message = re.sub(r':\d{7,}:\d+(?=>|,)', ':<VERSION>:<COUNTER>', message)
    if message.startswith('java.lang.StringIndexOutOfBoundsException:'):
        message = re.sub(r'\b(begin|end|length) \d+', r'\1 <OFFSET>', message)
    return message


def compare(baseline, results):
    allowed = {(f['class'], f['test'], f['type'], stable_message(f['message']))
               for f in baseline['failures']}
    expected = {(name, test) for name, tests in baseline['suites'].items()
                for test in tests}
    observed, failed, skipped = set(), [], set()
    for path in Path(results).glob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        for case in suite.findall('testcase'):
            identity = (case.get('classname', suite.get('name', '')), case.get('name', ''))
            observed.add(identity)
            if case.find('skipped') is not None:
                skipped.add(identity)
            for error in list(case.findall('failure')) + list(case.findall('error')):
                failed.append((*identity, error.get('type', ''), error.get('message', '')))
    # Neither filtering the full suite nor adding @Ignore can manufacture a pass.
    covered = observed | {old for old, new in RENAMED_TESTS.items() if new in observed}
    missing = expected - covered
    newly_skipped = skipped & (expected | set(RENAMED_TESTS.values()))
    unexpected = [failure for failure in failed
                  if (*failure[:3], stable_message(failure[3])) not in allowed]
    return observed, failed, missing, newly_skipped, unexpected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline')
    parser.add_argument('results')
    args = parser.parse_args()
    baseline = json.loads(Path(args.baseline).read_text())
    observed, failed, missing, skipped, unexpected = compare(baseline, args.results)
    print(f"Full suite: {len(observed)} tests; {len(failed)} failures; "
          f"{len(unexpected)} new/changed failures; {len(missing)} missing; {len(skipped)} skipped baseline tests")
    print(f"Baseline: main {baseline['source_commit']} / Actions {baseline['source_run']}")
    for failure in unexpected:
        print('NEW FAILURE:', *failure)
    for identity in sorted(missing):
        print('MISSING TEST:', *identity)
    for identity in sorted(skipped):
        print('SKIPPED TEST:', *identity)
    # 8094 — job logs are not readable from the build agent and GitHub keeps only
    # ~10 error annotations per step, which the inherited failures consumed (8093).
    # Annotate the delta itself first so the cause of a red run is always visible.
    def esc(text):
        return str(text).replace('%', '%25').replace('\r', ' ').replace('\n', ' ')[:600]
    shown = 0
    for cls, test, typ, msg in unexpected:
        if shown >= 8:
            break
        print(f"::error title=NEW {cls.split('.')[-1]}.{test}::{esc(typ)} {esc(stable_message(msg))}")
        shown += 1
    for cls, test in sorted(missing)[:max(0, 8 - shown)]:
        print(f"::error title=MISSING {cls.split('.')[-1]}.{test}::test absent from the full suite")
        shown += 1
    if failed:
        print(f"::warning::{len(failed)} full-suite failures remain; see complete test reports. "
              "The baseline comparison does not declare these tests fixed.")
    return int(bool(unexpected or missing or skipped or not observed))


if __name__ == '__main__':
    sys.exit(main())
