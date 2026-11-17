"""Platform-independent behavior rules; raw media titles never become identity."""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime
import json
import os
from pathlib import Path
import tempfile
import time
import uuid

POINTS = {'fast': -3, 'partial': -1, 'completed': 2}
LABELS = {'fast': '快速跳过', 'partial': '中途切歌', 'completed': '基本听完'}


def classify(position: int, duration: int) -> str | None:
    if duration <= 0 or position < 0:
        return None
    if position / duration >= .70 or duration - position <= 10_000:
        return 'completed'
    return 'fast' if position < 15_000 else 'partial'


@dataclass(frozen=True)
class Sample:
    media_id: str | None
    position: int
    duration: int
    playing: bool = True
    source: str = 'simulation'
    title: str = ''  # in-memory diagnostics only


class Tracker:
    def __init__(self):
        self.current: Sample | None = None
        self.observed_at: float | None = None
        self.streak = 0
        self.last_analysis: float | None = None
        self.notice = '等待有效记录'
        self.analysis_count = 0

    def reset_session(self):
        self.current = None
        self.observed_at = None
        self.streak = 0

    def observe(self, sample: Sample, now: float) -> dict | None:
        if not sample.media_id:
            self.reset_session()
            return None
        event = None
        if self.current and sample.source != self.current.source:
            self.reset_session()
        if self.current and sample.media_id != self.current.media_id:
            event = self.finish(now)
        self.current = sample
        self.observed_at = now
        return event

    def finish(self, now: float) -> dict | None:
        previous, observed_at = self.current, self.observed_at
        self.current = None
        self.observed_at = None
        if (previous is None or observed_at is None or
                not 0 <= now - observed_at < 15 or not previous.playing):
            self.streak = 0
            self.notice = '进度缺失、暂停或观察已过期，未推断听歌行为'
            return None
        category = classify(previous.position, previous.duration)
        if category is None:
            self.streak = 0
            self.notice = '进度或时长无效，未写入记录'
            return None
        self.streak = self.streak + 1 if category == 'fast' else 0
        self.notice = LABELS[category]
        if self.streak >= 4:
            if self.last_analysis is None or now - self.last_analysis >= 120:
                self.last_analysis = now
                self.analysis_count += 1
                self.streak = 0
                self.notice = '连续 4 次快跳，已触发行为分析（未控制播放）'
            else:
                self.notice = '连续快跳达到阈值，120 秒冷却中'
        return {
            'eventId': str(uuid.uuid4()), 'mediaId': previous.media_id,
            'playedMs': min(previous.position, previous.duration),
            'durationMs': previous.duration, 'category': category,
            'timestamp': int(time.time() * 1000), 'source': previous.source,
        }


def profile(events: list[dict], latest: int) -> dict:
    scores: dict[str, list[int]] = defaultdict(lambda: [0, 0])
    counts = dict.fromkeys(POINTS, 0)
    for event in events[-latest:] if latest > 0 else []:
        category = event.get('category')
        media_id = event.get('mediaId')
        if not media_id or category not in POINTS:
            continue
        counts[category] += 1
        scores[media_id][0] += POINTS[category]
        scores[media_id][1] += 1
    total = sum(counts.values())
    return dict(total=total, counts=counts, scores=dict(scores),
                completionPercent=counts['completed'] * 100 // total if total else 0)


def empty_document() -> dict:
    return dict(schemaVersion=3, events=[], songLabels={}, songInfo={},
                settings={'auto': False}, platform='windows')


def validate_document(value: object) -> dict:
    if not isinstance(value, dict) or value.get('schemaVersion', 3) not in (1, 2, 3):
        raise ValueError('不是支持的 Resonance JSON（版本 1–3）')
    events = value.get('events')
    if not isinstance(events, list):
        raise ValueError('events 必须是列表；原文件不会被覆盖')
    for event in events:
        if (not isinstance(event, dict) or not isinstance(event.get('mediaId'), str)
                or not event['mediaId'].strip() or event.get('category') not in POINTS):
            raise ValueError('听歌记录格式无效')
        for key in ('playedMs', 'durationMs', 'timestamp'):
            if type(event.get(key)) is not int or event[key] < 0:
                raise ValueError(f'听歌记录中的 {key} 无效')
    labels, info = value.get('songLabels', {}), value.get('songInfo', {})
    if not isinstance(labels, dict) or not isinstance(info, dict):
        raise ValueError('歌曲信息格式无效')
    for key, name in labels.items():
        if not isinstance(key, str) or not key.strip() or not isinstance(name, str):
            raise ValueError('歌曲标签格式无效')
    for key, song in info.items():
        if (not isinstance(key, str) or not key.strip() or not isinstance(song, dict)
                or not isinstance(song.get('name'), str) or not song['name'].strip()):
            raise ValueError('已确认歌曲信息格式无效')
        for field in ('artist', 'album', 'source'):
            if field in song and not isinstance(song[field], str):
                raise ValueError('歌曲字段格式无效')
    settings = value.get('settings', {'auto': False})
    if not isinstance(settings, dict) or type(settings.get('auto', False)) is not bool:
        raise ValueError('设置格式无效')
    result = empty_document()
    result.update(value)
    result.update(schemaVersion=3, events=events[-500:], songLabels=labels,
                  songInfo=info, settings=settings)
    return result


def atomic_json(path: Path, value: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=path.name + '.', suffix='.tmp', dir=path.parent)
    try:
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


class Store:
    def __init__(self, path: Path):
        self.path = path
        self.data = validate_document(json.loads(path.read_text(encoding='utf-8-sig'))) if path.exists() else empty_document()
        self._lock = None

    def lock(self):
        """Prevent two UI instances from overwriting the same portable library."""
        import msvcrt
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = self.path.with_suffix('.lock').open('a+b')
        self._lock.seek(0, 2)
        if self._lock.tell() == 0:
            self._lock.write(b'0')
            self._lock.flush()
        self._lock.seek(0)
        try:
            msvcrt.locking(self._lock.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            self._lock.close()
            self._lock = None
            raise RuntimeError('这个数据文件已被另一个窗口使用，请先关闭那个窗口。')
        # Reload under the lock, closing the race between construction and lock.
        if self.path.exists():
            self.data = validate_document(json.loads(self.path.read_text(encoding='utf-8-sig')))

    def close(self):
        if self._lock:
            self._lock.close()
            self._lock = None

    def save(self):
        atomic_json(self.path, self.data)

    def append(self, event: dict):
        self.data['events'] = (self.data['events'] + [event])[-500:]
        self.save()

    def name(self, media_id: str) -> str:
        return self.data['songInfo'].get(media_id, {}).get('name') or self.data['songLabels'].get(media_id) or media_id

    def confirm(self, name: str, artist: str = '', album: str = '', media_id: str | None = None) -> str:
        if not name.strip():
            raise ValueError('请先填写并核实歌曲名称')
        media_id = media_id or 'pc:' + str(uuid.uuid4())
        self.data['songInfo'][media_id] = dict(mediaId=media_id, name=name.strip(),
            artist=artist.strip(), album=album.strip(), source='user-confirmed',
            savedAt=int(time.time() * 1000))
        self.data['songLabels'][media_id] = name.strip()
        self.save()
        return media_id

    def exported(self, simulation: bool = False) -> dict:
        return dict(schemaVersion=3, events=self.data['events'],
                    songLabels=self.data['songLabels'], songInfo=self.data['songInfo'],
                    exportedAt=int(time.time() * 1000), platform='windows', simulation=simulation)

    def merge(self, document: object) -> int:
        imported = validate_document(document)
        if imported.get('simulation') or any(e.get('source') == 'simulation' for e in imported['events']):
            raise ValueError('模拟记录不能导入真实资料库')
        # Android v3 has no eventId. Preserve distinct real observations and make
        # repeated imports idempotent using the existing event fields.
        def identity(event):
            return tuple(event.get(key) for key in ('mediaId', 'timestamp', 'playedMs', 'durationMs', 'category'))
        existing = list(self.data['events'])
        seen = {identity(e) for e in existing}
        added = 0
        for event in imported['events']:
            key = identity(event)
            if key not in seen:
                existing.append(event)
                seen.add(key)
                added += 1
        self.data['events'] = sorted(existing, key=lambda e: e['timestamp'])[-500:]
        for field in ('songLabels', 'songInfo'):
            for key, value in imported[field].items():
                self.data[field].setdefault(key, value)
        self.save()
        return added


def event_time(event: dict) -> str:
    try:
        return datetime.fromtimestamp(event['timestamp'] / 1000).strftime('%m-%d %H:%M:%S')
    except (ValueError, OSError, OverflowError):
        return '未知时间'
