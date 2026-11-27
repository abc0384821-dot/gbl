# GBL 1.2 联机页规格：回归 GBL 风格 + 修引擎失联误报

> 本版是**纠偏版**。1.1.9 把 Terracotta 联机窗口的背景图、配色、内部侧栏整套搬了进来，用户明确反对。
> 用户要的是：**只借 Terracotta 的排版布局（信息架构），视觉必须回归 GBL 原生风格**。

## 0. 用户原话（唯一权威）

> 没让你完全抄啊，只是抄排版UI，你为啥背景也抄了。先去掉这堆东西（图3），然后再去掉背景图（图4）。UI也烂成啥了。然后我不要什么网络体验、节点配置、日记与诊断这些的。风格也不像GBL。而且为什么一直提示异常。好好修bug，不懂就去参考，版本为1.2

四张现场截图：
- 图1 `clipboard-2026-11-23T09-11-35-199Z-60ad78e1.png`：浅色主题，双重侧栏 + MC 背景 + 毛玻璃卡 + 红色异常
- 图2 `clipboard-2026-11-23T09-11-35-204Z-271d7a0d.png`：深色主题，同上
- 图3 `clipboard-2026-11-23T09-11-35-207Z-13060d5a.png`：**要删掉的内部侧栏特写**——顶部「联机大厅」高亮、底部「1 未登录」账号卡、「启动游戏 26.3」「填写反馈表」，中间大片空白
- 图4 `clipboard-2026-11-23T09-11-35-210Z-be3eb33b.png`：**MC 背景图 + 左上角弹窗乱叠 + 红色异常**

## 1. 真 bug：引擎失联误报（已实证，非推断）

### 实证数据（2026-11-23 17:14 本机）
| 检查项 | 实测结果 |
|---|---|
| 引擎进程 | pid=8332 存活 |
| HTTP 端口 | 127.0.0.1:51247（端口文件 `{"port":51247}`，mtime 刚更新，正确） |
| `GET /meta` | 200，1.5ms，`{"version":"0.4.2","yggdrasil_port":13448,...}` |
| `GET /state` | 200，1.5ms，`{"index":8,"state":"waiting"}` |
| GBL 界面 | 仍红色 ex=6「联机引擎失联：进程已退出或连续请求无响应」，进度条停「正在初始化联机核心」 |

**结论：引擎完全正常，GBL 误报。**

### 根因链
1. `RoomService.isAlive()`(L463) → `TraversalProvider.isProcessAlive()`(L170) = `currentProcess != null && currentProcess.isAlive()`
2. `currentProcess` 只认**本次 JVM 内由 launchAsync 启动的那个进程对象**
3. 机器上已有昨天的老引擎进程在正常服务；GBL 本次又启一个短命新进程（单实例/端口冲突秒退）
4. `currentProcess` 指向已死进程 → `isAlive()=false`
5. `pollTick()`(L350) `if(!isAlive()) publishEngineLost()` → ex=6 → 红色「异常」
6. 用户点重试 → 再启短命进程 → 再报 → **死循环**

参考：HMCL `GeneralProvider.ofCommandLine()` 同样是 `--hmcl <portTransfer>`，但以 HTTP 服务可用性为准，不依赖自己启动的进程对象。

### 修复验收标准（必须保留鉴别力）
- **HTTP `/meta` 200 + 有效 version = 引擎可用**，即使 `currentProcess` 已死也不得报 ex=6
- `startAsync()` 前先探测已有引擎端口；`/meta` 已通就直接复用，不再启新进程
- 真失联（HTTP 不通且进程死）仍必须报 ex=6，红色可重试
- 真失败（节点不可达/端口冲突/版本不匹配/组件缺失）仍如实报错
- 1.1.9 的 ex=3/4 中性提示逻辑不动
- QA 必须有 buggy 反向探针：旧逻辑（只看 currentProcess）应被抓 FAIL

## 2. UI 删除清单（用户点名）

| 要删的东西 | 代码位置 | 用户依据 |
|---|---|---|
| 内容区内部侧栏（196px） | `buildScene()` 的 `shell.setLeft(sidebar)` + `buildSidebar()` 整块 | 图3「先去掉这堆东西」；GBL 主窗口已有左侧导航和左下角账号卡，重复 |
| MC 风景背景图 | `mp-scene-bg` Region + theme.css L3421 `url("mp-landscape.png")` | 「你为啥背景也抄了」「然后再去掉背景图」 |
| 毛玻璃 mp-glass | `buildHallCard()` 的 `card.getStyleClass().addAll("mp-glass",...)` | 「风格也不像GBL」 |
| 网络体检卡 | `buildContent()` L415 的 `buildProbeSection()` | 「我不要什么网络体验」 |
| 节点配置卡 | `buildContent()` L416 的 `buildConfigSection()` | 「节点配置」 |
| 日志与诊断卡 | `buildContent()` L416 的 `buildLogCard()` | 「日记与诊断」 |
| 页内身份卡（孤零零「1」） | `buildSidebar()` 内账号卡 | 图1/2；GBL 全局已有账号卡，页内不再重复 |

## 3. UI 保留清单（Terracotta 的排版布局——用户要的是这个）

- 「联机大厅」页标题
- 核心状态条（修 bug 后正确显示「联机核心已就绪」）
- 3 段说明文字
- **「我想当房主 / 我想当客人」两个大宽行**：左图标 + 主标题 + 灰副标题 + 右箭头，整行可点
  - 房主 → createRoom
  - 客人 → 展开邀请码输入 → 「加入」按钮 doJoin（1.1.9 修的 `joinBtn.setOnAction` 必须保留）
- 状态区（邀请码、加入输入、重试/返回待机按钮）
- 版权脚注

## 4. 风格对齐 GBL（不是 Terracotta）

复用 theme.css 已有的 GBL 原生样式类：
`.card`(L198) / `.card-title`(L436) / `.page-title`(L732) / `.page-desc`(L739) / `.content-scroll`(L173) / `.btn-ghost`(L400) / `.btn-download`(L926) / `.chip`(L71) / `.setting-title`(L510) / `.setting-desc`

- 浅色主题 → GBL 浅色卡片；深色主题 → GBL 深色卡片，两套都要像 GBL
- 内容区 VBox + ScrollPane 铺满，**不要 BorderPane 左侧栏**
- 背景为 GBL 原生纯色/主题渐变，无任何图片
- 「您似乎忘记启动游戏了」弹窗保留功能，改 GBL 原生对话框样式，不得堆在左上角乱叠（图4）

## 5. 版本 1.2 与跨版本门禁要点

- VERSION/BUILD = `1.2`，DISPLAY_VERSION 自动 `v1.2`
- 新增 `FP_1_2`，保留 FP_1_1_7/1_1_8/1_1_9 历史指纹
- ★★ **1.1.9（三段）→ 1.2（两段）位数变化**：版本比较一律逐段数值比较（缺段补0），禁用尾段≥N / 字符串前缀启发式；改门禁必须 buggy 反向鉴别
- check 新增 R120 独立字典（R107~R119 全保留），接进 group_a/group_b 的 rels 并集**和 items() 逐条循环各 2 处**
- pack `OLD_LINK_RE` 覆盖至 v1.1.9，`NEW_LINK=v1.2/1.2-portable.zip`，无 `v旧/新文件名` 混合坏链
- verify_live FORBID 只放 1.1.9 旧值；EXPECT 与本版四页逐字一致
- 官网四页 + version.json 纯 CRLF 同步

## 6. QA 检查点（Round 1 必验）

1. **误报修复鉴别力**：HTTP 通 + currentProcess 死 → 不报 ex=6；真失联（HTTP 不通且进程死）→ 仍报 ex=6。必须 buggy mock 反向证明探针有效。
2. **删除到位**：页面运行时节点树里无内部侧栏、无 `mp-scene-bg`/背景图、无网络体检/节点配置/日志卡。
3. **保留到位**：两大宽行可点，房主→createRoom，客人→展开输入→加入按钮 doJoin（`joinBtn.onAction=true`）。
4. **风格对齐**：渲染截图人工看，GBL 原生卡片、无 MC 背景、无毛玻璃。
5. **FX 线程**：shown Scene 运行时探针，父子重组无 CME，runFx 跨线程不抛。
6. **门禁与指纹**：build/pack/preflight/check --strict-web 全绿；FP_1_2 在 modules 常量池可扫到（先解压 jimage）；历史指纹全保留；buggy 反向鉴别有效。
7. **换行符**：MultiplayerPage.java 纯 LF；theme.css 与官网四页纯 CRLF。
8. **1.2 跨版本门禁**：语义版本比较正确，无「尾段≥N」启发式残留；官网下载链接全为 `v1.2/1.2-*.zip`，无混合坏链。
