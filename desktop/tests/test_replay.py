import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from replay import PhoneReplay, ReplayWindow
import test_app
from test_core import event


class ReplayTests(unittest.TestCase):
    def test_prefix_profiles_and_seek_boundaries(self):
        data = dict(schemaVersion=3, events=[event(timestamp=i) for i in range(25)])
        replay = PhoneReplay(data)
        self.assertEqual(0, replay.state()['long']['total'])
        replay.seek(24)
        self.assertEqual(20, replay.state()['short']['total'])
        self.assertEqual(24, replay.state()['long']['total'])
        self.assertEqual(24, replay.state()['matched'])
        replay.seek(999)
        self.assertEqual(25, replay.cursor)
        self.assertEqual(0, replay.seek(-9)['cursor'])

    def test_mismatch_and_unknown_are_reported_without_rewriting(self):
        incorrect = event(category='fast')  # 170/180 seconds does not mean fast
        unknown = event(timestamp=2000)
        unknown['durationMs'] = 0
        data = dict(events=[incorrect, unknown])
        original = copy.deepcopy(data)
        replay = PhoneReplay(data)
        state = replay.seek(2)
        self.assertEqual(1, state['mismatched'])
        self.assertEqual(1, state['unknown'])
        self.assertEqual(original, data)
        self.assertEqual('fast', replay.events[0]['category'])

    def test_names_come_from_confirmations_not_title(self):
        data = dict(events=[event()], songLabels={'android:123': 'Legacy manual name'},
                    songInfo={'other': dict(name='Confirmed', artist='A')})
        replay = PhoneReplay(data)
        self.assertEqual('Legacy manual name', replay.name('android:123'))
        self.assertEqual('Confirmed', replay.name('other'))
        self.assertEqual('unknown', replay.name('unknown'))

    def test_load_never_modifies_source_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'android.json'
            path.write_text(json.dumps(dict(events=[event()])), encoding='utf-8')
            original = path.read_bytes()
            PhoneReplay.load(path).seek(1)
            self.assertEqual(original, path.read_bytes())

    def test_empty_replay(self):
        self.assertEqual(0, PhoneReplay(dict(events=[])).seek(100)['total'])


class ReplayUiTests(unittest.TestCase):
    setUp = test_app.AppTests.setUp
    tearDown = test_app.AppTests.tearDown
    def test_open_replay_does_not_import_into_either_library(self):
        source = Path(self.temporary.name) / 'phone-export.json'
        source.write_text(json.dumps(dict(events=[event()])), encoding='utf-8')
        before = {mode: copy.deepcopy(store.data) for mode, store in self.app.stores.items()}
        with patch('app.filedialog.askopenfilename', return_value=str(source)):
            self.app.open_replay()
        self.assertEqual(before, {mode: store.data for mode, store in self.app.stores.items()})
        self.assertFalse((source.parent / 'library.json').exists())
        self.assertFalse((source.parent / 'demo.json').exists())

    def test_replay_controls_change_summary_without_writing_data(self):
        replay = PhoneReplay(dict(events=[event()]))
        window = ReplayWindow(self.root, replay, 'fixture.json')
        window.seek(1)
        self.assertEqual(1, replay.cursor)
        self.assertIn('1 / 1', window.summary.cget('text'))
        window.seek(0)
        self.assertEqual(0, replay.cursor)
        window.toggle()
        self.assertEqual(1, replay.cursor)
        window.toggle()
        window.close()
