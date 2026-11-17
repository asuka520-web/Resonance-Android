"""Read-only Windows GSMTC adapter. Never uses metadata as a stable song ID."""
from __future__ import annotations

import asyncio
from datetime import datetime, timezone
import queue
import threading


async def snapshot(manager, selected: str = '') -> dict:
    sessions = list(manager.get_sessions())
    app_ids = [session.source_app_user_model_id for session in sessions]
    chosen = next((s for s in sessions if s.source_app_user_model_id == selected), None) if selected else next(
        (s for s in sessions if 'qqmusic' in s.source_app_user_model_id.lower() or 'QQ音乐' in s.source_app_user_model_id), None)
    result = dict(apps=app_ids, connected=False, title='', artist='', album='',
                  position=-1, duration=0, playing=False, source='', status='等待 QQ 音乐播放')
    if chosen is None:
        result['status'] = '未找到所选会话，请在 QQ 音乐播放，或从下拉框选择会话'
        return result
    properties = await asyncio.wait_for(chosen.try_get_media_properties_async(), timeout=4)
    playback = chosen.get_playback_info()
    timeline = chosen.get_timeline_properties()
    playing = playback is not None and int(playback.playback_status) == 4
    position, duration = -1, 0
    if timeline is not None:
        position = int((timeline.position - timeline.start_time).total_seconds() * 1000)
        duration = max(0, int((timeline.end_time - timeline.start_time).total_seconds() * 1000))
        # Extrapolate only fresh timestamps; a stale clock isn't listening proof.
        updated = timeline.last_updated_time
        age = (datetime.now(timezone.utc) - updated.astimezone(timezone.utc)).total_seconds()
        rate = playback.playback_rate if playback is not None else None
        if playing and 0 <= age <= 5:
            position += int(age * 1000 * (rate if rate is not None else 1.0))
        if duration > 0 and position >= 0:
            position = min(position, duration)
    result.update(connected=True, source=chosen.source_app_user_model_id,
        title=properties.title if properties else '', artist=properties.artist if properties else '',
        album=properties.album_title if properties else '', position=position, duration=duration,
        playing=playing, status='播放中' if playing else '暂停 / 已停止')
    return result


class MediaReader:
    def __init__(self):
        self.messages = queue.Queue(maxsize=1)
        self.stop = threading.Event()
        self.selected = ''
        self.thread = threading.Thread(target=self._thread, daemon=True)

    def start(self):
        self.thread.start()

    def _publish(self, value):
        try:
            self.messages.get_nowait()
        except queue.Empty:
            pass
        self.messages.put_nowait(value)

    def _thread(self):
        from winrt.runtime import init_apartment, uninit_apartment, ApartmentType
        initialized = False
        try:
            init_apartment(ApartmentType.MULTI_THREADED)
            initialized = True
            asyncio.run(self._run())
        except Exception as error:
            self._publish({'connected': False, 'apps': [], 'status': f'Windows 媒体接口不可用：{error}'})
        finally:
            if initialized:
                uninit_apartment()

    async def _run(self):
        from winrt.windows.media.control import GlobalSystemMediaTransportControlsSessionManager
        manager = None
        while not self.stop.is_set():
            try:
                if manager is None:
                    manager = await asyncio.wait_for(GlobalSystemMediaTransportControlsSessionManager.request_async(), 8)
                self._publish(await snapshot(manager, self.selected))
            except Exception as error:
                manager = None
                self._publish({'connected': False, 'apps': [], 'status': f'读取失败，将自动重试：{error}'})
            for _ in range(5):
                if self.stop.is_set():
                    return
                await asyncio.sleep(.25)
