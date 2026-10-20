#!/usr/bin/env python3
"""Publish only after the main-branch runtime workflow passes for this exact SHA."""
import argparse
import json
import subprocess
import time


def verdict(runs, sha):
    matching = [r for r in runs if r.get('head_sha') == sha
                and r.get('head_branch') == 'main' and r.get('event') == 'push']
    if not matching:
        return 'WAIT'
    run = max(matching, key=lambda r: int(r['id']))
    if run.get('status') != 'completed':
        return 'WAIT'
    return 'PASS' if run.get('conclusion') == 'success' else 'FAIL'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('repository')
    parser.add_argument('sha')
    parser.add_argument('--timeout-seconds', type=int, default=2700)
    args = parser.parse_args()
    endpoint = (f'repos/{args.repository}/actions/workflows/runtime-test.yml/runs'
                f'?head_sha={args.sha}&branch=main&event=push&per_page=100')
    deadline = time.monotonic() + args.timeout_seconds
    while time.monotonic() < deadline:
        response = subprocess.run(['gh', 'api', endpoint], capture_output=True, text=True, timeout=45)
        if response.returncode:
            raise SystemExit('Runtime workflow evidence unavailable; APK publication refused')
        result = verdict(json.loads(response.stdout)['workflow_runs'], args.sha)
        if result == 'PASS':
            print(f'PASS: main runtime workflow completed successfully for {args.sha}', flush=True)
            return
        if result == 'FAIL':
            raise SystemExit(f'Runtime workflow failed for {args.sha}; APK publication refused')
        print(f'Waiting for main runtime evidence for {args.sha}', flush=True)
        time.sleep(15)
    raise SystemExit('Runtime evidence timed out; APK publication refused')


if __name__ == '__main__':
    main()
