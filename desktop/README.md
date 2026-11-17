# Resonance PC · Windows 实验版

本地 QQ 音乐伴随工具，不替换播放器，不上传听歌记录。

**手机是主产品，本程序是验证台。** 新增 **回放手机 JSON**：只读逐条回放
Android 导出的历史，查看每一步的 20/500 条统计，并标记行为类别与进度规则是否一致。
此操作不会将文件导入任一资料库，也不能还原当时的 Android 通知或后台状态。
两端使用同一份 `test-fixtures/listening-contract.json` 进行规则核对，详见
[`docs/TESTING_STRATEGY.md`](../docs/TESTING_STRATEGY.md)。

## 可以验证什么

- 真实 Windows 媒体会话：候选歌名、歌手、专辑、播放进度与暂停状态。
- 人工确认歌曲信息，手动开始 / 结束一首歌的行为记录。
- 最近 20 / 500 条单曲行为统计，最多保留 500 条历史。
- 四次连续快跳触发提示、120 秒冷却、歌词变化不触发换歌。
- 导入 Android 导出的 JSON，保留原始媒体 ID 和人工标签；重复导入不重复添加。
- 本地 schemaVersion 3 JSON 导出。模拟库与真实库完全分离。

Windows 的 GSMTC 媒体属性没有 Android 的稳定 MEDIA_ID。此版本不会根据
TITLE 或歌词变化自动认定换歌，也不会自动控制 QQ 音乐播放。
PC 与 Android 使用对应的行为规则和导出字段；当前是两个独立实现，
PC 测试通过不能替代 Android 编译、通知权限及后台服务验证。

## 使用免安装程序

打开 `Resonance-PC.exe`。首次启动可能需要几秒钟释放运行文件。
数据位于程序旁的 `data/library.json` 和 `data/demo.json`，请放在可写目录。

1. 不播放音乐也能验证：选择左侧 **模拟测试 · 独立数据**。
2. 点击 **模拟快跳** 四次，应显示“分析触发 1 次”。
3. 点击 **仅改变歌词**，历史条数应保持不变。
4. 点击中途 / 听完，查看历史及偏好分数变化。

真实模式：

1. 在 QQ 音乐播放歌曲；打开 **媒体诊断** 查看真实字段。
2. 核对 QQ 音乐界面，点击 **填入候选信息（待核实）**，再 **确认并保存**。
3. 点击 **开始本首记录**；请在切歌前点击 **结束本首记录**。
4. 首版按结束前的播放进度估计快跳 / 中途 / 听完，不等于准确收听时长。
5. 暂停、会话断开、读取超时和无效进度不会被推断为快跳。

退出时未结束的片段不自动结算。记录结束后可从列表重新选择已确认的歌曲。
在 QQ 音乐未向 Windows 提供媒体会话的客户端版本上，真实读取不可用，
可以继续使用模拟测试和导入 Android JSON。不会回退到抓取界面或私有接口。

导入会合并而非替换资料库，已有确认信息优先；保留最新 500 条行为。
旧版 Android 的手动标签可读，PC 没有自动将本地歌曲 ID 与 Android ID 匹配的功能。
Android 应用目前也没有 JSON 导入入口，PC 导出仅为兼容的数据文件。
损坏的本地文件会阻止启动且保持原样；不要删除原文件以尝试“修复”。

## 从源码运行和打包

Windows x64，Python 3.12–3.14（本次使用 3.14），包含 Tk。

```powershell
cd desktop
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-build.txt
.\.venv\Scripts\python.exe app.py
.\.venv\Scripts\python.exe app.py --demo
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
.\build.ps1 -Python .\.venv\Scripts\python.exe
```

测试 Windows 异步接口需要正常的本地 Windows 进程通信环境；若隔离环境使
asyncio 初始化一直等待，请在普通的本机开发终端运行，应用无需管理员权限。
`--data-dir PATH` 可指定独立的测试数据目录。`--probe` 可只读输出 QQ 音乐诊断。

结构：`core.py` 为行为和存储规则，`media.py` 为只读 Windows 适配器，
`app.py` 为原生桌面界面，`tests/` 为回归用例。

使用微软公开的 [Windows 媒体会话接口](https://learn.microsoft.com/en-us/uwp/api/windows.media.control.globalsystemmediatransportcontrolssessionmanager)。
其[媒体属性列表](https://learn.microsoft.com/en-us/uwp/api/windows.media.control.globalsystemmediatransportcontrolssessionmediaproperties)
没有稳定歌曲 ID。Python 绑定由 [PyWinRT](https://github.com/pywinrt/pywinrt) 提供。
