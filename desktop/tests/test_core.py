import json
from pathlib import Path
import tempfile
import unittest

from core import Sample, Store, Tracker, classify, empty_document, profile, validate_document


def event(media_id='android:123', category='completed', timestamp=1000):
    return dict(mediaId=media_id, category=category, playedMs=170_000,
                durationMs=180_000, timestamp=timestamp)


class ClassificationTests(unittest.TestCase):
    def test_boundaries(self):
        for position, duration, expected in [
            (-1, 180_000, None), (0, 0, None), (0, 180_000, 'fast'),
            (14_999, 180_000, 'fast'), (15_000, 180_000, 'partial'),
            (125_999, 180_000, 'partial'), (126_000, 180_000, 'completed'),
            (9_999, 20_000, 'fast'), (10_000, 20_000, 'completed'),
            (181_000, 180_000, 'completed')]:
            with self.subTest(position=position, duration=duration):
                self.assertEqual(expected, classify(position, duration))

    def test_lyrics_and_duplicate_polls_never_count(self):
        tracker = Tracker()
        for i in range(10):
            self.assertIsNone(tracker.observe(Sample('stable', i * 1000, 180_000, title=f'lyric {i}'), i))
        self.assertEqual('fast', tracker.observe(Sample('next', 0, 180_000), 10)['category'])
        self.assertIsNone(tracker.observe(Sample('next', 0, 180_000), 10.1))

    def test_no_id_no_record(self):
        tracker = Tracker()
        tracker.observe(Sample('old', 1000, 180_000), 1)
        self.assertIsNone(tracker.observe(Sample(None, 50_000, 180_000, title='not identity'), 2))
        self.assertIsNone(tracker.observe(Sample('new', 0, 180_000), 3))

    def test_pause_switch_does_not_count_as_skip(self):
        tracker = Tracker()
        tracker.observe(Sample('a', 8000, 180_000, playing=False), 1)
        self.assertIsNone(tracker.observe(Sample('b', 0, 180_000), 2))
        self.assertEqual(0, tracker.streak)

    def test_switching_players_is_not_a_song_skip(self):
        tracker = Tracker()
        tracker.observe(Sample('a', 8000, 180_000, source='player-a'), 1)
        self.assertIsNone(tracker.observe(Sample('b', 0, 180_000, source='player-b'), 2))
        self.assertEqual(0, tracker.streak)

    def test_stale_progress_and_negative_time_are_rejected(self):
        for end in (0, 16, 50):
            tracker = Tracker()
            tracker.observe(Sample('a', 8000, 180_000), 1)
            self.assertIsNone(tracker.finish(end))

    def test_four_skips_and_120_second_cooldown(self):
        tracker = Tracker()
        def skip(now):
            tracker.observe(Sample(str(now), 8000, 180_000), now)
            self.assertEqual('fast', tracker.finish(now + .1)['category'])
        for now in (1, 2, 3, 4):
            skip(now)
        self.assertEqual(1, tracker.analysis_count)  # also works within first 120s of boot
        for now in (5, 6, 7, 8):
            skip(now)
        self.assertEqual(1, tracker.analysis_count)
        self.assertIn('冷却', tracker.notice)
        skip(124)
        self.assertEqual(2, tracker.analysis_count)

    def test_completed_resets_streak(self):
        tracker = Tracker()
        tracker.observe(Sample('a', 1000, 180_000), 1)
        tracker.finish(2)
        tracker.observe(Sample('b', 170_000, 180_000), 3)
        tracker.finish(4)
        self.assertEqual(0, tracker.streak)

    def test_finish_is_idempotent_and_does_not_persist_title(self):
        tracker = Tracker()
        tracker.observe(Sample('a', 8000, 180_000, title='PRIVATE LYRIC'), 1)
        result = tracker.finish(2)
        self.assertNotIn('PRIVATE LYRIC', json.dumps(result))
        self.assertIsNone(tracker.finish(3))


class ProfileTests(unittest.TestCase):
    def test_short_long_windows_and_points(self):
        events = [event(category='completed', timestamp=i) for i in range(25)]
        events[-1] = event(category='fast', timestamp=24)
        short, long = profile(events, 20), profile(events, 500)
        self.assertEqual(20, short['total'])
        self.assertEqual([35, 20], short['scores']['android:123'])
        self.assertEqual(25, long['total'])
        self.assertEqual(95, short['completionPercent'])

    def test_empty_and_invalid_events(self):
        self.assertEqual(0, profile([], 20)['completionPercent'])
        self.assertEqual(0, profile([{}], 20)['total'])
        self.assertEqual(0, profile([event()], 0)['total'])


class StorageTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.path = Path(self.temporary.name) / 'library.json'

    def tearDown(self):
        self.temporary.cleanup()

    def test_confirm_restore_legacy_labels_and_export(self):
        store = Store(self.path)
        key = store.confirm('  Verified name  ', 'Artist', 'Album')
        store.append(event(key))
        restored = Store(self.path)
        self.assertEqual('Verified name', restored.name(key))
        exported = restored.exported()
        self.assertEqual(3, exported['schemaVersion'])
        self.assertEqual('user-confirmed', exported['songInfo'][key]['source'])
        self.assertIn(key, exported['songLabels'])
        self.assertNotIn('title', exported)

    def test_history_cap_preserves_latest(self):
        store = Store(self.path)
        store.data['events'] = [event(timestamp=i) for i in range(500)]
        store.append(event(timestamp=500))
        self.assertEqual(500, len(store.data['events']))
        self.assertEqual(1, store.data['events'][0]['timestamp'])

    def test_android_import_is_idempotent_preserves_existing_confirmation(self):
        store = Store(self.path)
        store.confirm('Local confirmed', media_id='android:123')
        source = dict(schemaVersion=3, events=[event()], songLabels={'android:123': 'Android label'},
            songInfo={'android:123': dict(name='Android info', artist='A', album='B')})
        self.assertEqual(1, store.merge(source))
        self.assertEqual(0, store.merge(source))
        self.assertEqual('Local confirmed', Store(self.path).name('android:123'))

    def test_legacy_label_only_import(self):
        store = Store(self.path)
        store.merge(dict(events=[event()], songLabels={'android:123': 'Old manual label'}))
        self.assertEqual('Old manual label', store.name('android:123'))

    def test_corrupt_file_never_overwritten(self):
        original = '{ broken JSON'
        self.path.write_text(original, encoding='utf-8')
        with self.assertRaises(json.JSONDecodeError):
            Store(self.path)
        self.assertEqual(original, self.path.read_text(encoding='utf-8'))

    def test_bad_import_is_atomic(self):
        store = Store(self.path)
        store.confirm('keep me')
        original = self.path.read_bytes()
        source = dict(schemaVersion=3, events=[event(), {'mediaId': 'broken'}])
        with self.assertRaises(ValueError):
            store.merge(source)
        self.assertEqual(original, self.path.read_bytes())

    def test_rejects_invalid_schemas_and_fields(self):
        for change in ({'schemaVersion': 99}, {'events': {}}, {'settings': []}, {'songInfo': {'a': {'name': 1}}}):
            data = empty_document()
            data.update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate_document(data)

    def test_simulation_cannot_enter_real_library(self):
        store = Store(self.path)
        simulated = event()
        simulated['source'] = 'simulation'
        for data in (dict(events=[simulated]), dict(events=[], simulation=True)):
            with self.assertRaises(ValueError):
                store.merge(data)
        self.assertFalse(self.path.exists())

    def test_separate_libraries(self):
        live = Store(self.path)
        demo = Store(self.path.with_name('demo.json'))
        demo.append(event())
        self.assertEqual([], live.data['events'])
        self.assertFalse(self.path.exists())

    def test_second_writer_is_locked(self):
        first, second = Store(self.path), Store(self.path)
        first.lock()
        try:
            with self.assertRaises(RuntimeError):
                second.lock()
        finally:
            first.close()
        second.lock()
        second.close()


if __name__ == '__main__':
    unittest.main()
