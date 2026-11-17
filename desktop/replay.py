"""Read-only replay of exported phone events; never writes to the PC library."""
from __future__ import annotations

import copy
import json
from pathlib import Path
import tkinter as tk
from tkinter import ttk

from core import LABELS, classify, event_time, profile, validate_document


class PhoneReplay:
    def __init__(self, document):
        self.document = copy.deepcopy(validate_document(document))
        self.events = self.document['events']
        self.cursor = 0

    @classmethod
    def load(cls, path: Path):
        return cls(json.loads(path.read_text(encoding='utf-8-sig')))

    def name(self, media_id):
        return (self.document['songInfo'].get(media_id, {}).get('name') or
                self.document['songLabels'].get(media_id) or media_id)

    def seek(self, count):
        self.cursor = max(0, min(len(self.events), int(count)))
        return self.state()

    def state(self):
        prefix = self.events[:self.cursor]
        matched = mismatched = unknown = 0
        for event in prefix:
            expected = classify(event['playedMs'], event['durationMs'])
            if expected is None:
                unknown += 1
            elif expected == event['category']:
                matched += 1
            else:
                mismatched += 1
        return dict(cursor=self.cursor, total=len(self.events), matched=matched,
                    mismatched=mismatched, unknown=unknown,
                    short=profile(prefix, 20), long=profile(prefix, 500))


class ReplayWindow:
    def __init__(self, parent, replay: PhoneReplay, filename=''):
        self.replay = replay
        self.window = tk.Toplevel(parent)
        self.window.title('手机记录回放 · 只读')
        self.window.geometry('1000x720')
        self.window.minsize(840, 620)
        self.window.configure(bg='#101820')
        self.running = False
        self.after_id = None
        self.window.protocol('WM_DELETE_WINDOW', self.close)
        body = ttk.Frame(self.window, style='Card.TFrame', padding=20)
        body.pack(fill='both', expand=True)
        ttk.Label(body, text='手机记录回放', style='Title.TLabel').pack(anchor='w')
        ttk.Label(body, text=f'{filename} · 只读查看，不导入或改写当前资料库',
                  style='Muted.TLabel').pack(anchor='w', pady=(8, 4))
        ttk.Label(body, text='按手机已记录的事件逐条查看统计。此文件不包含逐秒会话，不能还原通知监听过程。',
                  wraplength=900, style='Muted.TLabel').pack(anchor='w', pady=(0, 14))
        controls = ttk.Frame(body, style='Card.TFrame')
        controls.pack(fill='x')
        for text, action in [
            ('回到开头', lambda: self.seek(0)),
            ('上一条', lambda: self.seek(self.replay.cursor - 1)),
            ('下一条', lambda: self.seek(self.replay.cursor + 1)),
            ('查看全部', lambda: self.seek(len(self.replay.events)))
        ]:
            ttk.Button(controls, text=text, command=action).pack(side='left', padx=(0, 8))
        self.play_button = ttk.Button(controls, text='自动逐条回放', command=self.toggle)
        self.play_button.pack(side='right')
        self.summary = ttk.Label(body, wraplength=900)
        self.summary.pack(anchor='w', pady=14)
        frame = ttk.Frame(body, style='Card.TFrame')
        frame.pack(fill='both', expand=True)
        columns = ('顺序', '时间', '歌曲', '手机记录', '规则核对')
        self.table = ttk.Treeview(frame, columns=columns, show='headings', height=10)
        for key, width in zip(columns, (50, 130, 290, 110, 130)):
            self.table.heading(key, text=key)
            self.table.column(key, width=width, minwidth=50)
        scrollbar = ttk.Scrollbar(frame, command=self.table.yview)
        self.table.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side='right', fill='y')
        self.table.pack(side='left', fill='both', expand=True)
        for i, event in enumerate(replay.events):
            computed = classify(event['playedMs'], event['durationMs'])
            check = '无法复核' if computed is None else '一致' if computed == event['category'] else f'不同：{LABELS[computed]}'
            self.table.insert('', 'end', iid=str(i + 1), values=(i + 1, event_time(event),
                replay.name(event['mediaId']), LABELS[event['category']], check))
        self.table.bind('<<TreeviewSelect>>', self.selected)
        self.ranking = ttk.Label(body, wraplength=900)
        self.ranking.pack(anchor='w', pady=(12, 0))
        self.render()

    def selected(self, _event=None):
        selection = self.table.selection()
        if selection:
            cursor = int(selection[0])
            if cursor != self.replay.cursor:
                self.replay.seek(cursor)
                self.render()

    def seek(self, count):
        self.replay.seek(count)
        if self.replay.cursor:
            self.table.selection_set(str(self.replay.cursor))
            self.table.see(str(self.replay.cursor))
        else:
            self.table.selection_remove(*self.table.selection())
        self.render()

    def render(self):
        state = self.replay.state()
        short, long = state['short'], state['long']
        self.summary.configure(text=(
            f"已回放 {state['cursor']} / {state['total']} 条  ·  规则一致 {state['matched']} 条 / 不同 {state['mismatched']} 条 / 无法复核 {state['unknown']} 条\n"
            f"短期：{short['total']} 条，听完率 {short['completionPercent']}%    长期：{long['total']} 条，听完率 {long['completionPercent']}%"))
        top = sorted(long['scores'].items(), key=lambda item: -item[1][0])[:3]
        self.ranking.configure(text='截至当前的单曲行为分：' + ('；'.join(
            f'{self.replay.name(key)} {values[0]:+d} 分 / {values[1]} 次' for key, values in top) or '暂无记录'))

    def toggle(self):
        if self.running:
            self.running = False
            if self.after_id is not None:
                self.window.after_cancel(self.after_id)
                self.after_id = None
            self.play_button.configure(text='自动逐条回放')
            return
        if self.replay.cursor == len(self.replay.events):
            self.seek(0)
        self.running = True
        self.play_button.configure(text='暂停回放')
        self.advance()

    def advance(self):
        self.after_id = None
        if not self.running:
            return
        if self.replay.cursor >= len(self.replay.events):
            self.running = False
            self.play_button.configure(text='自动逐条回放')
            return
        self.seek(self.replay.cursor + 1)
        self.after_id = self.window.after(650, self.advance)

    def close(self):
        self.running = False
        if self.after_id is not None:
            self.window.after_cancel(self.after_id)
        self.window.destroy()
