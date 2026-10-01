#!/usr/bin/env python3
"""Regression checks: reviewed wiring cannot survive a removed consumer call."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('estate', Path(__file__).with_name('super_intelligence_estate_audit_7655.py'))
estate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(estate)

class ReviewedEstateTest(unittest.TestCase):
    def test_production_manifest_has_twelve_distinct_proven_components(self):
        self.assertEqual(12, len(estate.load_reviewed()))

    def fixture(self, root):
        import json
        rows = json.loads(Path(estate.__file__).with_name('super_intelligence_reviewed_7686.json').read_text())
        for row in rows:
            path = root / row['path']
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('object ReviewedComponent {}')
            for proof in row['consumers']:
                path = root / proof['path']
                path.parent.mkdir(parents=True, exist_ok=True)
                existing = path.read_text() if path.exists() else ''
                path.write_text(existing + '\n' + proof['call'] + ')')
        return rows

    def test_removed_consumer_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = self.fixture(root)
            self.assertEqual(12, len(estate.load_reviewed(root)))
            proof = rows[0]['consumers'][0]
            (root / proof['path']).write_text('object Consumer {}')
            with self.assertRaisesRegex(ValueError, 'consumer call missing'):
                estate.load_reviewed(root)

    def test_comment_only_consumer_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = self.fixture(root)
            proof = rows[0]['consumers'][0]
            (root / proof['path']).write_text('// ' + proof['call'])
            with self.assertRaisesRegex(ValueError, 'consumer call missing'):
                estate.load_reviewed(root)

if __name__ == '__main__':
    unittest.main()
