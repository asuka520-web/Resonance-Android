from pathlib import Path
import queue
import tempfile
import threading
import time
import tkinter as tk
import unittest
from unittest.mock import patch

from app import App
from core import Store


class FakeReader:
    def __init__(self):
        self.messages = queue.Queue()
        self.stop = threading.Event()
        self.selected = ''

    def start(self):
        pass


class AppTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.reader_patch = patch('app.MediaReader', FakeReader)
        self.reader_patch.start()
        self.root = tk.Tk()
        self.root.withdraw()
        self.app = App(self.root, Path(self.temporary.name))

    def tearDown(self):
        self.app.close()
        self.reader_patch.stop()
        self.temporary.cleanup()

    def prepare_live(self):
        self.app.live = dict(connected=True, source='QQMusic.exe', position=8000,
                            duration=180000, playing=True, title='UNVERIFIED LYRIC', apps=['QQMusic.exe'])
        self.app.live_received = time.monotonic()
        self.app.fields['name'].set('Confirmed fixture song')
        self.app.confirm()

    def test_manual_start_end_persistence_and_no_raw_title(self):
        self.prepare_live()
        self.app.begin()
        self.app.finish()
        document = Store(Path(self.temporary.name) / 'library.json').data
        self.assertEqual(1, len(document['events']))
        self.assertEqual('fast', document['events'][0]['category'])
        self.assertNotIn('UNVERIFIED LYRIC', str(document))
        with self.assertRaises(ValueError):
            self.app.finish()

    def test_paused_record_is_not_fast_skip(self):
        self.prepare_live()
        self.app.begin()
        self.app.live['playing'] = False
        self.app.update_manual()
        self.app.finish()
        self.assertEqual([], self.app.store.data['events'])

    def test_unconfirmed_and_stale_sources_cannot_start(self):
        with self.assertRaises(ValueError):
            self.app.begin()
        self.prepare_live()
        self.app.live_received -= 10
        with self.assertRaises(ValueError):
            self.app.begin()

    def test_demo_actions_and_lyrics_leave_live_unchanged(self):
        self.app.switch('demo')
        for _ in range(4):
            self.app.simulate('fast')
        self.assertEqual(1, self.app.tracker.analysis_count)
        self.app.simulate('lyric')
        self.assertEqual(4, len(self.app.store.data['events']))
        self.app.switch('live')
        self.assertEqual([], self.app.store.data['events'])

    def test_disconnect_stops_manual_record_without_event(self):
        self.prepare_live()
        self.app.begin()
        self.app.reader.messages.put(dict(connected=False, apps=[], status='断开'))
        self.app.tick()
        self.assertIsNone(self.app.manual_id)
        self.assertEqual([], self.app.store.data['events'])
