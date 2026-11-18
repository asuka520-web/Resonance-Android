# Resonance Android 设计系统验收

主题令牌在 `ResonanceTheme.kt`。共用组件在 `ResonanceComponents.kt`，弹层与对话框共用主题里的 `SheetScaffold` 和 `ResonanceDialog`。时长、曲线和帧预算相关的动效配置在 `ResonanceMotion.kt` 的 `Motion` 对象。Android 启动背景和图标使用 `res/values/styles.xml` 的系统窗口色。

| 页面 | 复用的组件 | 本地提交 |
| --- | --- | --- |
| 为你及主题 | TopBar、SectionHeader、PrimaryButton、OutlineButton、TextAction、ScoreCard、TrackRow、Dock、SandPlate | `431af3d` |
| 发现 | SearchField、GenreTile、TrackRow、Dock、EmptyState、LoadingState、ErrorState | `c6b47fd` |
| 收藏及歌单详情 | SearchField、SectionHeader、TrackRow、SandPlate、EmptyState、LoadingState、ErrorState | `631a32c` |
| 我的 | SectionHeader、ScoreCard、SandPlate、OutlineButton、Dock | `f8845b0` |
| 调整这次选歌 | SheetScaffold、ScoreRow、SandPlate、PrimaryButton、TextAction | `98bfe34` |
| 全部标签分数 | SheetScaffold、SearchField、ScoreRow、SignedScoreBar、ResonanceChip、TextAction | `cf9c4e6` |
| 清零确认 | ResonanceDialog、SandPlate、PrimaryButton、TextAction | `0502ecb` |
| 歌曲详情 | SheetScaffold、SectionHeader、OutlineButton、TextAction | `771a454` |
| 歌曲反馈 | SheetScaffold、SectionHeader、ResonanceChip、EmptyState、PrimaryButton | `063a5bd` |
| 换向记录及轻提示 | SheetScaffold、SectionHeader、EmptyState、共用提示动效 | `6037450` |
| 完整播放器 | SandPlate、Dock、ArtworkTransition、IconAction、PrimaryButton、ErrorState | `5afb925` |
| QQ 连接与授权 | SheetScaffold、SectionHeader、LoadingState、ErrorState、TextAction | `b9fc0d9` |
| 旧版监听与记录 | ResonanceTheme、SectionHeader、PrimaryButton、OutlineButton、EmptyState | `10a405a` |
| 旧版曲库查询及录音核对 | SectionHeader、ResonanceDialog、LoadingState、EmptyState、ErrorState、PrimaryButton、OutlineButton | `2901267` |

## 手机上的验证步骤

| 场景 | 操作和预期 |
| --- | --- |
| 导航与「为你」 | 连续切换四个底栏栏目，正文交叉淡入，导航胶囊滑动。播放时留在「为你」看微震，再将大板滚出视口，只有可见播放器控件继续更新。 |
| 发现 | 聚焦搜索框，看砂白描边。选择网格中的曲风和「本机未听」，检查歌曲行与筛选同步。「发现更多」确实在读取时，加载占位反复落沙。 |
| 收藏与歌单 | 同步收藏、展开「管理」并试补查。点「读取歌单」，进入一张尚未打开的歌单，观察内容淡入上移。点「返回收藏」观察反向过渡，歌单占位会用已取得的首曲生成图案。 |
| 我的 | 用测试曲库播放和反馈，让几个标签产生分数，再进入「我的」，看前三个已评分标签生成的图谱及其粒子密度。确认陪伴时长、播放范围、冷却、在线补全和账号入口都可用。 |
| 完整播放器 | 播放一首歌，点击迷你播放器封面或歌名。观察封面和共振板约 320ms 的共享过渡。暂停观察沙粒停止，拖动刻度尺检查真实进度。可用系统返回、点击外部或下拖手柄关闭。 |
| 调整这次选歌 | 拖动一条标签滑杆，观察预览清晰度即时变化；再按两侧按钮切换「避开／不限／想听」。点「应用并推荐」后检查条件摘要。关闭面板会丢弃未应用的草稿。 |
| 全部标签分数 | 从「标签分数」页签或分数摘要进入。用正分、负分、零分筛选和名称搜索。点底部「清除当前所有分数」，先取消，重开再确认，观察沙粒散开消失；只有确认才会清零。 |
| 歌曲详情和反馈 | 在收藏或发现点歌曲右侧详情按钮。打开「多来这种」或「暂时少放」，勾选曲风并确认，检查对应分值。收藏到 QQ 的按钮仍沿用原授权与确认流程。 |
| 换向历史 | 在「我的」点「最近换向记录」或「预览切歌提示」。核对有记录和无记录两种界面；真实四连跳记录仍以播放器确认状态为准。 |
| QQ 授权 | 点「连接 QQ 音乐」或「管理账号 / 更新授权」。加载中可看到落沙，网络失败时可重试。通过「完成」返回，官方网页和授权步骤由原 WebView 提供。 |
| 旧版监听与录音核对 | 在「我的」打开「旧版监听与记录」。展开媒体诊断和最近记录；有可信歌名和歌手时查曲风，确认核对窗口的按钮和布局。Android 系统的分享和权限窗口由系统提供。 |
| 系统动画设置 | 把系统动画时长设为关闭，重复切页、展开播放、拖动、清零和加载。界面直接到终态。锁屏、切到后台或把沙粒组件滚出可视区后，检查它停止接收帧。 |

播放器和首页大板优先使用同屏沙粒预算。静态小图案按剩余额度分配，不需要单独的帧循环。录音权限、业务规则和推荐算法沿用原版本。
