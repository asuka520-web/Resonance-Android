import json
from pathlib import Path
import unittest

from core import Tracker, Sample, classify, profile


CONTRACT = Path(__file__).resolve().parents[2] / 'test-fixtures' / 'listening-contract.json'


class AndroidContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.contract = json.loads(CONTRACT.read_text(encoding='utf-8'))

    def test_shared_classification_boundaries(self):
        for case in self.contract['classifications']:
            with self.subTest(case=case):
                self.assertEqual(case['expected'], classify(case['positionMs'], case['durationMs']))

    def test_shared_observation_sequences(self):
        for case in self.contract['cases']:
            with self.subTest(case=case['name']):
                tracker, actual = Tracker(), []
                for step in case['steps']:
                    if step.get('disconnect'):
                        tracker.reset_session()
                        continue
                    result = tracker.observe(Sample(step['id'], step['positionMs'], step.get('durationMs', 180000),
                        step.get('playing', True), title=step.get('title', '')), step['atMs'] / 1000)
                    if result:
                        actual.append({key: result[key] for key in ('mediaId', 'playedMs', 'durationMs', 'category')})
                self.assertEqual(case['events'], actual)
                self.assertEqual(case['streak'], tracker.streak)
                self.assertEqual(case['analysisCount'], tracker.analysis_count)

    def test_shared_preference_scores(self):
        for case in self.contract['profiles']:
            with self.subTest(case=case['name']):
                actual = profile(case['events'], case['latest'])
                self.assertEqual(case['total'], actual['total'])
                self.assertEqual(case['completionPercent'], actual['completionPercent'])
                self.assertEqual(case['scores'], actual['scores'])
