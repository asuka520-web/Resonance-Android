from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
import unittest

from media import snapshot


class Session:
    def __init__(self, source, playing=True):
        self.source_app_user_model_id = source
        self.reads = 0
        self.playing = playing

    async def try_get_media_properties_async(self):
        self.reads += 1
        return SimpleNamespace(title='candidate lyric', artist='candidate artist', album_title='candidate album')

    def get_playback_info(self):
        return SimpleNamespace(playback_status=4 if self.playing else 5, playback_rate=1.)

    def get_timeline_properties(self):
        return SimpleNamespace(position=timedelta(seconds=25), start_time=timedelta(seconds=5),
            end_time=timedelta(seconds=185), last_updated_time=datetime.now(timezone.utc) - timedelta(seconds=1))


class MediaTests(unittest.IsolatedAsyncioTestCase):
    async def test_does_not_read_other_players_by_default(self):
        chrome = Session('chrome.exe')
        result = await snapshot(SimpleNamespace(get_sessions=lambda: [chrome]))
        self.assertFalse(result['connected'])
        self.assertEqual(['chrome.exe'], result['apps'])
        self.assertEqual(0, chrome.reads)

    async def test_qq_candidates_are_not_identity(self):
        qq = Session('QQMusic.exe')
        result = await snapshot(SimpleNamespace(get_sessions=lambda: [qq]))
        self.assertTrue(result['connected'])
        self.assertEqual('candidate lyric', result['title'])
        self.assertNotIn('media_id', result)
        self.assertNotIn('mediaId', result)
        self.assertEqual(180_000, result['duration'])
        self.assertTrue(21_000 <= result['position'] < 21_500)

    async def test_pause_does_not_advance_position(self):
        qq = Session('QQMusic.exe', playing=False)
        result = await snapshot(SimpleNamespace(get_sessions=lambda: [qq]))
        self.assertFalse(result['playing'])
        self.assertEqual(20_000, result['position'])

    async def test_explicit_source_selection_and_missing_session(self):
        browser = Session('chrome.exe')
        manager = SimpleNamespace(get_sessions=lambda: [browser])
        self.assertTrue((await snapshot(manager, 'chrome.exe'))['connected'])
        self.assertFalse((await snapshot(manager, 'missing.exe'))['connected'])
        self.assertEqual(1, browser.reads)
