from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path
import queue
import sys
import time
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
import uuid

from core import Store, Tracker, Sample, profile, LABELS, atomic_json, event_time
from media import MediaReader
from replay import PhoneReplay, ReplayWindow

BG = '#101820'
PANEL = '#192530'
INK = '#edf5f3'
MUTED = '#9eafb9'
GREEN = '#63e0b5'


def clock(ms):
    if ms < 0:
        return '--:--'
    seconds = ms // 1000
    return f'{seconds // 60:02d}:{seconds % 60:02d}'


class App:
    def __init__(self, root, data_dir, demo=False):
        self.root = root
        self.root.title('Resonance PC · 手机验证台')
        self.root.geometry('1100x840')
        self.root.minsize(960, 760)
        self.root.configure(bg=BG)
        self.stores = {'live': Store(data_dir / 'library.json'), 'demo': Store(data_dir / 'demo.json')}
        for store in self.stores.values():
            store.lock()
        self.trackers = {'live': Tracker(), 'demo': Tracker()}
        self.mode = 'demo' if demo else 'live'
        self.live = {'connected': False, 'apps': [], 'status': '正在连接 Windows 媒体接口'}
        self.live_received = 0.0
        self.manual_id = None
        self.manual_source = ''
        self.selected_id = None
        self.library_ids = []
        self.demo_id = 'demo:' + str(uuid.uuid4())
        self.demo_clock = 1.0
        self.demo_number = 1
        self.demo_title_revision = 0
        self.reader = MediaReader()
        self.setup_style()
        self.create_ui()
        self.reader.start()
        self.root.protocol('WM_DELETE_WINDOW', self.close)
        self.refresh_mode()
        self.root.after(250, self.tick)

    @property
    def store(self):
        return self.stores[self.mode]

    @property
    def tracker(self):
        return self.trackers[self.mode]

    def setup_style(self):
        style = ttk.Style()
        style.theme_use('clam')
        style.configure('.', font=('Microsoft YaHei UI', 10), background=PANEL, foreground=INK)
        style.configure('TFrame', background=BG)
        style.configure('Card.TFrame', background=PANEL)
        style.configure('TLabel', background=PANEL, foreground=INK)
        style.configure('Muted.TLabel', foreground=MUTED)
        style.configure('Hero.TLabel', font=('Microsoft YaHei UI', 23, 'bold'))
        style.configure('Title.TLabel', font=('Microsoft YaHei UI', 15, 'bold'))
        style.configure('TButton', padding=(12, 9), background='#2a414d', foreground=INK, borderwidth=0)
        style.map('TButton', background=[('active', '#3b5964'), ('disabled', '#26333d')], foreground=[('disabled', '#6b7d88')])
        style.configure('Accent.TButton', background=GREEN, foreground='#102921')
        style.map('Accent.TButton', background=[('active', '#91f1d0'), ('disabled', '#26333d')], foreground=[('disabled', '#6b7d88')])
        style.configure('TEntry', fieldbackground='#0e1c25', foreground=INK, padding=8)
        style.configure('TCombobox', fieldbackground='#233540', foreground=INK, padding=6)
        style.map('TCombobox', fieldbackground=[('readonly', '#233540')], foreground=[('readonly', INK)])
        style.configure('TNotebook', background=BG, borderwidth=0)
        style.configure('TNotebook.Tab', background='#233540', padding=(18, 10))
        style.map('TNotebook.Tab', background=[('selected', '#335249')], foreground=[('selected', GREEN)])
        style.configure('Treeview', background=PANEL, fieldbackground=PANEL, foreground=INK, rowheight=32, borderwidth=0)
        style.configure('Treeview.Heading', background='#233540', foreground=MUTED, padding=8)
        style.map('Treeview', background=[('selected', '#345b51')])

    def text(self, parent, value, style=None, **kwargs):
        item = ttk.Label(parent, text=value, style=style or 'TLabel', **kwargs)
        return item

    def create_ui(self):
        rail = tk.Frame(self.root, bg='#0b141c', width=190, padx=22, pady=28)
        rail.pack(side='left', fill='y')
        rail.pack_propagate(False)
        tk.Label(rail, text='◉', bg='#0b141c', fg=GREEN, font=('Segoe UI', 40)).pack(anchor='w')
        tk.Label(rail, text='RESONANCE', bg='#0b141c', fg=INK, font=('Segoe UI', 14, 'bold')).pack(anchor='w', pady=(8, 0))
        tk.Label(rail, text='共鸣 · 手机验证台', bg='#0b141c', fg=MUTED, font=('Microsoft YaHei UI', 10)).pack(anchor='w', pady=(6, 36))
        ttk.Button(rail, text='QQ 音乐 · 真实模式', command=lambda: self.switch('live')).pack(fill='x', pady=6)
        ttk.Button(rail, text='模拟测试 · 独立数据', command=lambda: self.switch('demo')).pack(fill='x', pady=6)
        tk.Label(rail, text='本地记录\n人工确认歌曲\n短期 / 长期偏好\nAndroid JSON 导入', justify='left', bg='#0b141c', fg=MUTED,
                 font=('Microsoft YaHei UI', 10), pady=24).pack(anchor='w')
        tk.Label(rail, text='数据只保存在本机\n尚未接入推荐曲库', justify='left', bg='#0b141c', fg=MUTED,
                 font=('Microsoft YaHei UI', 9)).pack(side='bottom', anchor='w')
        body = ttk.Frame(self.root, padding=(26, 22))
        body.pack(side='left', fill='both', expand=True)
        header = ttk.Frame(body)
        header.pack(fill='x')
        self.heading = tk.Label(header, bg=BG, fg=INK, font=('Microsoft YaHei UI', 24, 'bold'), anchor='w')
        self.heading.pack(side='left')
        self.badge = tk.Label(header, bg='#234439', fg=GREEN, padx=12, pady=6, font=('Microsoft YaHei UI', 10))
        self.badge.pack(side='right')
        self.subheading = tk.Label(body, bg=BG, fg=MUTED, anchor='w', font=('Microsoft YaHei UI', 10))
        self.subheading.pack(fill='x', pady=(8, 18))
        card = ttk.Frame(body, style='Card.TFrame', padding=18)
        card.pack(fill='x')
        self.current_title = self.text(card, '等待播放', 'Title.TLabel', wraplength=760)
        self.current_title.pack(anchor='w')
        self.playback = self.text(card, '', 'Muted.TLabel')
        self.playback.pack(anchor='w', pady=(10, 6))
        self.progress = ttk.Progressbar(card, maximum=100)
        self.progress.pack(fill='x', pady=(0, 10))
        self.notice = self.text(card, '', wraplength=760)
        self.notice.pack(anchor='w')
        self.notebook = ttk.Notebook(body)
        self.notebook.pack(fill='both', expand=True, pady=(20, 12))
        self.record_tab = ttk.Frame(self.notebook, style='Card.TFrame', padding=18)
        self.stats_tab = ttk.Frame(self.notebook, style='Card.TFrame', padding=18)
        self.history_tab = ttk.Frame(self.notebook, style='Card.TFrame', padding=18)
        self.diagnostics_tab = ttk.Frame(self.notebook, style='Card.TFrame', padding=18)
        for tab, name in [(self.record_tab, '记录与标注'), (self.stats_tab, '偏好分析'),
                          (self.history_tab, '听歌记录'), (self.diagnostics_tab, '媒体诊断')]:
            self.notebook.add(tab, text=name)
        self.create_record_tab()
        self.stats_summary = self.text(self.stats_tab, '', wraplength=740)
        self.stats_summary.pack(anchor='w', pady=(0, 12))
        self.stats_table = self.table(self.stats_tab, ('窗口', '歌曲', '行为分', '次数'), (110, 360, 90, 70))
        self.text(self.stats_tab, '启发式评分：听完 +2 / 中途 -1 / 快跳 -3；不是曲风识别或推荐结果。', 'Muted.TLabel').pack(anchor='w', pady=(12, 0))
        self.history_table = self.table(self.history_tab, ('时间', '歌曲', '行为', '进度'), (130, 320, 110, 110))
        diag_top = ttk.Frame(self.diagnostics_tab, style='Card.TFrame')
        diag_top.pack(fill='x', pady=(0, 12))
        self.text(diag_top, 'Windows 媒体会话').pack(side='left')
        self.sessions = ttk.Combobox(diag_top, state='readonly', width=52)
        self.sessions.pack(side='left', padx=12)
        self.sessions.bind('<<ComboboxSelected>>', self.select_session)
        self.diagnostics = tk.Text(self.diagnostics_tab, bg='#0e1c25', fg=INK, relief='flat', padx=14, pady=14,
                                   font=('Microsoft YaHei UI', 10), height=12, wrap='word')
        self.diagnostics.pack(fill='both', expand=True)
        footer = ttk.Frame(body)
        footer.pack(fill='x')
        ttk.Button(footer, text='导出 JSON', command=lambda: self.safe(self.export_json)).pack(side='right', padx=(8, 0))
        self.import_button = ttk.Button(footer, text='导入 Android JSON', command=lambda: self.safe(self.import_json))
        self.import_button.pack(side='right')
        ttk.Button(footer, text='回放手机 JSON', command=lambda: self.safe(self.open_replay)).pack(side='right', padx=8)
        self.footer = tk.Label(footer, bg=BG, fg=MUTED, font=('Microsoft YaHei UI', 9), anchor='w')
        self.footer.pack(side='left')

    def table(self, parent, columns, widths):
        frame = ttk.Frame(parent, style='Card.TFrame')
        frame.pack(fill='both', expand=True)
        tree = ttk.Treeview(frame, columns=columns, show='headings', height=6)
        for column, width in zip(columns, widths):
            tree.heading(column, text=column)
            tree.column(column, width=width, minwidth=65)
        scrollbar = ttk.Scrollbar(frame, command=tree.yview)
        tree.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side='right', fill='y')
        tree.pack(side='left', fill='both', expand=True)
        return tree

    def create_record_tab(self):
        tab = self.record_tab
        library = ttk.Frame(tab, style='Card.TFrame')
        library.pack(fill='x', pady=(0, 12))
        self.text(library, '已确认歌曲').pack(side='left')
        self.library = ttk.Combobox(library, state='readonly', width=43)
        self.library.pack(side='left', padx=10)
        self.library.bind('<<ComboboxSelected>>', self.select_song)
        ttk.Button(library, text='新建标注', command=self.new_song).pack(side='right')
        fields = ttk.Frame(tab, style='Card.TFrame')
        fields.pack(fill='x')
        fields.columnconfigure(1, weight=1)
        self.fields = {}
        for row, (key, label) in enumerate([('name', '歌曲名称'), ('artist', '歌手'), ('album', '专辑')]):
            self.text(fields, label).grid(row=row, column=0, sticky='w', padx=(0, 16), pady=5)
            variable = tk.StringVar()
            self.fields[key] = variable
            ttk.Entry(fields, textvariable=variable).grid(row=row, column=1, sticky='ew', pady=5)
        buttons = ttk.Frame(tab, style='Card.TFrame')
        buttons.pack(fill='x', pady=12)
        ttk.Button(buttons, text='填入候选信息（待核实）', command=self.copy_candidates).pack(side='left')
        ttk.Button(buttons, text='确认并保存', style='Accent.TButton', command=lambda: self.safe(self.confirm)).pack(side='left', padx=10)
        self.live_buttons = ttk.Frame(tab, style='Card.TFrame')
        self.start_button = ttk.Button(self.live_buttons, text='开始本首记录', command=lambda: self.safe(self.begin))
        self.start_button.pack(side='left')
        self.end_button = ttk.Button(self.live_buttons, text='结束本首记录', command=lambda: self.safe(self.finish))
        self.end_button.pack(side='left', padx=10)
        self.demo_buttons = ttk.Frame(tab, style='Card.TFrame')
        for text, kind in [('模拟快跳 · 8 秒', 'fast'), ('模拟中途 · 60 秒', 'partial'), ('模拟听完 · 170 秒', 'completed'), ('仅改变歌词', 'lyric')]:
            ttk.Button(self.demo_buttons, text=text, command=lambda k=kind: self.safe(lambda: self.simulate(k))).pack(side='left', padx=(0, 8))
        self.record_hint = self.text(tab, '', 'Muted.TLabel', wraplength=730)
        self.record_hint.pack(side='bottom', anchor='w', pady=(10, 0))

    def safe(self, action):
        try:
            action()
        except Exception as error:
            messagebox.showerror('操作未完成', str(error), parent=self.root)

    def switch(self, mode):
        if mode == self.mode:
            return
        if self.manual_id:
            messagebox.showinfo('正在记录', '请先结束当前记录，再切换模式。', parent=self.root)
            return
        self.mode = mode
        self.selected_id = None
        self.refresh_mode()

    def refresh_mode(self):
        demo = self.mode == 'demo'
        self.heading.configure(text='播放行为实验室' if demo else '让每一次聆听留下线索')
        self.badge.configure(text='模拟测试 · 不混入真实记录' if demo else '本地连接 · 只读媒体会话')
        self.subheading.configure(text='无需播放音乐，直接验证分类、歌词去重与连续快跳。' if demo else '打开 QQ 音乐播放一首歌，从媒体诊断开始验证。')
        self.live_buttons.pack_forget()
        self.demo_buttons.pack_forget()
        (self.demo_buttons if demo else self.live_buttons).pack(fill='x', pady=(0, 8))
        self.import_button.configure(state='disabled' if demo else 'normal')
        self.record_hint.configure(text='这只是测试数据。连续点击 4 次快跳可检查分析触发；改变歌词不应新增历史。' if demo else
            'Windows 未提供稳定歌曲 ID：请确认歌曲后手动开始，并在切歌前结束记录。暂停 / 断开期间不会推断跳歌。')
        self.footer.configure(text='模拟库 · 独立存储' if demo else '真实库 · 仅保存在本机')
        self.refresh_library()
        self.load_fields()
        self.refresh_tables()
        self.render()

    def refresh_library(self):
        self.library_ids = sorted(set(self.store.data['songLabels']) | set(self.store.data['songInfo']), key=self.store.name)
        self.library['values'] = [f'{self.store.name(key)} · {key[-8:]}' for key in self.library_ids]
        if self.selected_id in self.library_ids:
            self.library.current(self.library_ids.index(self.selected_id))
        else:
            self.library.set('选择已确认歌曲，或新建标注')

    def load_fields(self):
        key = self.demo_id if self.mode == 'demo' else self.selected_id
        song = self.store.data['songInfo'].get(key, {})
        for field, variable in self.fields.items():
            variable.set(song.get(field, self.store.data['songLabels'].get(key, '') if field == 'name' else ''))

    def select_song(self, _event=None):
        index = self.library.current()
        if index >= 0:
            self.selected_id = self.library_ids[index]
            self.load_fields()

    def new_song(self):
        self.selected_id = None
        self.library.set('新建歌曲标注')
        for field in self.fields.values():
            field.set('')

    def copy_candidates(self):
        sample = self.sample_view()
        self.fields['name'].set(sample.get('title', ''))
        self.fields['artist'].set(sample.get('artist', ''))
        self.fields['album'].set(sample.get('album', ''))

    def confirm(self):
        self.selected_id = self.store.confirm(**{key: value.get() for key, value in self.fields.items()},
                                             media_id=self.demo_id if self.mode == 'demo' else self.selected_id)
        self.refresh_library()
        self.refresh_tables()
        self.render()

    def begin(self):
        if self.manual_id:
            raise ValueError('当前已有记录，请先结束本首。')
        if not self.selected_id:
            raise ValueError('请先人工确认歌曲信息，或从已确认歌曲中选择。')
        if not self.live.get('connected') or time.monotonic() - self.live_received >= 5:
            raise ValueError('当前没有可用的 QQ 音乐会话，请先开始播放。')
        if not self.live.get('playing') or self.live.get('duration', 0) <= 0 or self.live.get('position', -1) < 0:
            raise ValueError('需要正在播放且有有效进度 / 时长的会话。')
        self.manual_id = self.selected_id
        self.manual_source = self.live['source']
        self.update_manual()
        self.tracker.notice = '正在记录；请在切歌前点击“结束本首记录”'
        self.render()

    def update_manual(self):
        value = self.live
        if self.manual_id and value.get('connected') and value.get('source') == self.manual_source:
            self.tracker.observe(Sample(self.manual_id, value['position'], value['duration'], value['playing'],
                                        source='windows-manual:' + self.manual_source), time.monotonic())

    def finish(self):
        if not self.manual_id:
            raise ValueError('还没有开始记录。')
        if time.monotonic() - self.live_received >= 5:
            self.tracker.reset_session()
        event = self.tracker.finish(time.monotonic())
        self.manual_id = None
        if event:
            self.store.append(event)
        self.refresh_tables()
        self.render()

    def simulate(self, kind):
        if self.mode != 'demo':
            return
        self.demo_clock += 1
        if kind == 'lyric':
            self.demo_title_revision += 1
            current = self.tracker.current
            self.tracker.observe(Sample(self.demo_id, current.position if current else 0, 180_000,
                title=f'模拟歌词 {self.demo_title_revision}'), self.demo_clock)
            self.tracker.notice = '仅候选歌词发生变化：没有新增听歌记录'
        else:
            position = {'fast': 8_000, 'partial': 60_000, 'completed': 170_000}[kind]
            self.tracker.observe(Sample(self.demo_id, position, 180_000), self.demo_clock)
            self.demo_id = 'demo:' + str(uuid.uuid4())
            event = self.tracker.observe(Sample(self.demo_id, 0, 180_000), self.demo_clock + .1)
            if event:
                self.store.append(event)
            self.demo_number += 1
            self.selected_id = None
            self.load_fields()
            self.refresh_library()
        self.refresh_tables()
        self.render()

    def select_session(self, _event=None):
        if self.manual_id:
            messagebox.showinfo('正在记录', '请先结束记录，再更换会话。', parent=self.root)
            self.sessions.set(self.manual_source)
            return
        selected = self.sessions.get()
        self.reader.selected = selected
        # Invalidate the previous source immediately, before the worker responds.
        self.live = {'connected': False, 'apps': self.live.get('apps', []), 'status': '正在切换媒体会话'}
        self.render()

    def sample_view(self):
        if self.mode == 'demo':
            current = self.tracker.current
            return dict(connected=True, title=f'模拟曲目 {self.demo_number:02d} / 歌词片段 {self.demo_title_revision}',
                        artist='测试歌手', album='演示资料', duration=180_000,
                        position=current.position if current else 0, playing=True, source='独立模拟源', status='模拟测试')
        return self.live

    def render(self):
        value = self.sample_view()
        key = self.demo_id if self.mode == 'demo' else self.manual_id or self.selected_id
        name = self.store.name(key) if key and (key in self.store.data['songLabels'] or key in self.store.data['songInfo']) else ''
        self.current_title.configure(text=name or ('模拟曲目 · 尚未人工标注' if self.mode == 'demo' else '未绑定歌曲 · 候选信息见媒体诊断'))
        position, duration = value.get('position', -1), value.get('duration', 0)
        self.playback.configure(text=f"{value.get('status', '')}    {clock(position)} / {clock(duration)}")
        self.progress['value'] = min(100, max(0, position / duration * 100)) if duration > 0 else 0
        self.notice.configure(text=f'{self.tracker.notice}  ·  连续快跳 {self.tracker.streak} / 4  ·  分析触发 {self.tracker.analysis_count} 次')
        self.start_button.configure(state='disabled' if self.manual_id else 'normal')
        self.end_button.configure(state='normal' if self.manual_id else 'disabled')
        self.sessions['values'] = self.live.get('apps', [])
        if not self.reader.selected and self.live.get('source'):
            self.sessions.set(self.live['source'])
        diagnostic = '\n'.join([
            f"来源：{value.get('source') or '尚无会话'}",
            '稳定歌曲 ID：模拟源提供测试 ID' if self.mode == 'demo' else '稳定歌曲 ID：Windows 接口未提供，真实记录需人工绑定',
            f"TITLE（未验证）：{value.get('title', '')}",
            f"ARTIST（未验证）：{value.get('artist', '')}",
            f"ALBUM（未验证）：{value.get('album', '')}",
            f"进度：{position} ms / {duration} ms",
            f"记录状态：{'人工记录中' if self.manual_id else '未在真实模式记录'}",
            '', '候选字段不会自动保存为歌名。只在内存中显示，不写入历史或 JSON 导出。',
            '如果 QQ 音乐未出现在会话列表中，本版无法从该客户端读取播放信息。',
        ])
        self.diagnostics.configure(state='normal')
        self.diagnostics.delete('1.0', 'end')
        self.diagnostics.insert('1.0', diagnostic)
        self.diagnostics.configure(state='disabled')

    def refresh_tables(self):
        events = self.store.data['events']
        self.history_table.delete(*self.history_table.get_children())
        for event in reversed(events):
            self.history_table.insert('', 'end', values=(event_time(event), self.store.name(event['mediaId']),
                LABELS[event['category']], clock(event['playedMs']) + ' / ' + clock(event['durationMs'])))
        self.stats_table.delete(*self.stats_table.get_children())
        summaries = []
        for latest, label in [(20, '短期 / 20'), (500, '长期 / 500')]:
            result = profile(events, latest)
            summaries.append(f"{label}：{result['total']} 条，听完率 {result['completionPercent']}%")
            for key, (score, count) in sorted(result['scores'].items(), key=lambda pair: -pair[1][0]):
                self.stats_table.insert('', 'end', values=(label, self.store.name(key), score, count))
        self.stats_summary.configure(text='\n'.join(summaries) + '\n少于 5 条时样本不足；分数仅反映已记录的单曲行为。')

    def export_json(self):
        filename = filedialog.asksaveasfilename(parent=self.root, title='导出本地听歌记录',
            defaultextension='.json', initialfile='Resonance-demo.json' if self.mode == 'demo' else 'Resonance-PC.json',
            filetypes=[('JSON', '*.json')])
        if filename:
            target = Path(filename).resolve()
            if target in [store.path.resolve() for store in self.stores.values()]:
                raise ValueError('导出文件不能覆盖正在使用的资料库。请另选文件名。')
            atomic_json(target, self.store.exported(self.mode == 'demo'))
            self.tracker.notice = 'JSON 已导出到你选择的位置'
            self.render()

    def import_json(self):
        filename = filedialog.askopenfilename(parent=self.root, title='导入 Android 导出的 JSON', filetypes=[('JSON', '*.json')])
        if filename:
            added = self.store.merge(json.loads(Path(filename).read_text(encoding='utf-8-sig')))
            self.tracker.notice = f'导入完成：新增 {added} 条；相同记录不重复导入'
            self.refresh_library()
            self.refresh_tables()
            self.render()

    def open_replay(self):
        filename = filedialog.askopenfilename(parent=self.root, title='只读回放手机导出的 JSON', filetypes=[('JSON', '*.json')])
        if filename:
            ReplayWindow(self.root, PhoneReplay.load(Path(filename)), Path(filename).name)

    def tick(self):
        changed = False
        try:
            value = self.reader.messages.get_nowait()
            self.live = value
            self.live_received = time.monotonic()
            changed = True
            if self.manual_id:
                if not value.get('connected') or value.get('source') != self.manual_source:
                    self.trackers['live'].reset_session()
                    self.manual_id = None
                    self.trackers['live'].notice = '会话已断开，当前未完成记录已停止；没有推断跳歌'
                else:
                    self.update_manual()
        except queue.Empty:
            pass
        if self.manual_id and time.monotonic() - self.live_received >= 5:
            self.trackers['live'].reset_session()
            self.manual_id = None
            self.trackers['live'].notice = '媒体读取超时，未完成记录已停止'
            changed = True
        if changed:
            self.render()
        self.root.after(250, self.tick)

    def close(self):
        self.reader.stop.set()
        for store in self.stores.values():
            store.close()
        self.root.destroy()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--demo', action='store_true')
    parser.add_argument('--data-dir', type=Path)
    parser.add_argument('--probe', action='store_true')
    args = parser.parse_args()
    if args.probe:
        from winrt.windows.media.control import GlobalSystemMediaTransportControlsSessionManager as Manager
        from media import snapshot
        async def probe():
            manager = await asyncio.wait_for(Manager.request_async(), 8)
            print(json.dumps(await snapshot(manager), ensure_ascii=False, indent=2))
        asyncio.run(probe())
        return
    base = Path(sys.executable).parent if getattr(sys, 'frozen', False) else Path(__file__).parent
    root = tk.Tk()
    try:
        App(root, args.data_dir or base / 'data', args.demo)
    except Exception as error:
        root.withdraw()
        messagebox.showerror('无法打开资料库', f'{error}\n\n原文件未被覆盖。请保留文件以便恢复。', parent=root)
        root.destroy()
        return
    root.mainloop()


if __name__ == '__main__':
    main()
