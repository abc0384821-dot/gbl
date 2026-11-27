# GBL 1.2.1 增量版：联机页紧凑化 + 取消可用 + 引擎内置（架构设计 & 任务分解）

> 架构师：高见远。基线 1.2（已三渠道发版）。本版 1.2.1，Gitee/GitHub **各自新建 v1.2.1**，绝不塞旧 release。
> 依据源码：`MultiplayerPage.java`(1421 行)、`TraversalProvider.java`、`RoomService.java`、`pack_1.2.py`、`build_1.2.py`、`theme.css`、`LauncherApp.java`。

## 0. 六条诉求 → 决策一览

| # | 诉求 | 决策（确定性结论） |
|---|---|---|
| 1 | 大厅待机页精简 | 待机 = **玩家一行 + [创建房间][加入房间] 两个 `btn-download` 绿按钮 + 版权脚注**。删常驻"正在扫描节点"大字与引擎条；过程态只在点按钮后出现，单行紧凑不撑高。 |
| 2 | 取消真能用 | `actionRow` 全部 8 个按钮**一次性 `setOnAction` 绑事件**（根因修复）；`cancelBtn`→新增 `doCancel()` 走 `RoomService.resetIdleAsync`（`/state/ide`）真正中断建房/加入并回待机。 |
| 3 | 删"缺少组件"提示条 | `missingBar` 字段 + `buildMissingBar()` + 全部 `setShown(missingBar,…)` 及 `updateEngineBar(…, missing)` 的 missing 形参**整块删除**。引擎内置后不再主动提示缺组件。 |
| 4 | 客人弹居中模态 | 删页内 `joinCard`/`buildJoinArea`，仿 `showGameModal` 写 `showInviteModal()`：标题"请输入房主提供的邀请码" + 输入框 + 必填/格式**红字** + [确定][取消]，取消真关模态；复用 `modalLayer` + `.card.mp-modal-card` + StackPane 居中。 |
| 5 | 整体 UI 收拾干净 | 删 1.2 遗留的死方法/死字段（probe/config/log/identity-card/bigRow/buildJoinArea），`refreshIdentity` 收敛为只喂 `playerLine`；样式只加 `.gbl-compact` 后代。 |
| 6 | 联机组件自动内置 | Terracotta `exe+DLL` **vendor 进仓库 `vendor/engines/terracotta/`**；`TraversalProvider` 解析顺序改为 **内置 `GBL/engines/terracotta/` → `~/.gbl` → 联网兜底**；打包只进**完整包**，更新包保持不带引擎（分层）。 |

---

## 1. 引擎内置：打包 & 启动策略（关键决策，确定性）

### 1.1 落点 & 优先级（改 `TraversalProvider`）

发行包内新增（完整包 zip 内）：
```
GBL/engines/terracotta/
  terracotta-0.4.2-windows-x86_64.exe   9,402,880 B  sha256 74c10568a7fea9c1d38cf8d2d4ca90baf1517f8e5a26c63d3349db70bc449796
  VCRUNTIME140.DLL                       97,656 B     sha256 475ab98b7722e965bd38c8fa6ed23502309582ccf294ff1061cb290c7988f0d1
  port.txt                                (空占位，预建，避免运行时写只读目录)
  .complete                               (预建标记)
```

`TraversalProvider` 新增：
- `static Path appRoot()`：与 `GameLauncher.appDir()` 同款——`ProcessHandle.current().info().command()` 的父目录（GBL.exe 所在），取不到退 `Paths.get("").toAbsolutePath()`（IDE 跑时）。
- `static final Path BUILTIN_ENGINE_DIR = appRoot().resolve("engines").resolve("terracotta")`。
- `resolveInstalledDir()` 解析顺序改为**三层**：
  1. `BUILTIN_ENGINE_DIR` 若 `isCompleteEngine()` 通过 → 直接返回（**离线冷启动即用**，最高优先）。
  2. 否则扫 `~/.gbl/multiplayer/engines/`（现状，服务**更新上来的老用户**与已装过者）。
  3. 否则 `null` → `ensureInstalledAsync()` 走**联网下载兜底**（主 Gitee / 备 CNB，SHA256 命中即用，坏包改名保留，零删除——全部保留）。
- `launchAsync` 的 `pb.directory(installDir())` 不变：内置目录里 exe 与 DLL 同置，Windows 按 exe 目录找 DLL，天然可用；`PORT_FILE` 仍固定 `~/.gbl/multiplayer/terracotta.port`，与引擎位置无关。
- **只读安全**：内置目录在 zip 里可能只读。预建 `port.txt`/`.complete`，运行时对内置目录**零写入**（`ensureInstalled` 仅在第 3 层触发，且只写 `~/.gbl`）。

`RoomService.startAsync` 无需改：`findReusablePort` → `isInstalled()` → `ensureInstalledAsync` 三层全透明复用。

### 1.2 打包分层（`pack_1.2.1.py`）

- **引擎源 = `vendor/engines/terracotta/`（仓库内，逐字节固定）**。`build_1.2.1.py` 在 `deploy()` 末尾 `copytree` 把 `vendor/engines/terracotta/*` 拷到 `target/dist/GBL/engines/terracotta/`（覆盖写，零删除），并 **SHA256 双文件硬校验**，不符即拒构建（防引擎被换/损坏）。
- **完整包** `1.2.1-portable.zip`：`pack` 现有的"walk `dist/GBL` 加 `GBL/` 前缀"逻辑**不改**，`engines/` 自然被打进去；pack 末尾**新增**断言：`engines/terracotta/` 下 4 个文件必须都在 zip 里且 exe/DLL 哈希命中。
- **更新包** `1.2.1-update.zip`：现有 `UPDATE_FILES=("app/mc-launcher.jar","app/GBL.cfg","runtime/lib/modules")` 三件，**保持不带引擎**（且 L369 已硬拒 `.exe/.dll` 条目）。→ 更新上来的老用户：内置目录不存在 → 走 `~/.gbl`（多半已有）→ 再联网兜底，**无回归**。
- **95/98MB 门禁（确定性）**：`pack_1.2.py` 现用 `LIMIT = 98*1024*1024`（报错文案写 95MB，以 98MB 变量为准），Gitee 单附件硬上限 100MB。
  - 1.2 完整包实测 **91,856,414 B（≈87.6MB）**；引擎 9,500,536 B 原始，PE/DLL 经 zip-9 压缩比≈45–55% → **约 +4.5~5.5MB → 预计完整包 ≈ 92~93MB（最坏 95MB）**。
  - **结论：维持 `LIMIT=98MB`，不上调到 100MB**（留 ≥2MB 余量）。pack 跑完打印真实体积；若（极小概率）>98MB 被门禁拦下，兜底策略 = 完整包仍带引擎、把 `LIMIT` 精确抬到 `min(实测,99MB)` 且不破 Gitee 100MB 上限（而非放弃内置）。**不采用"更新包带引擎"**（更新包结构不允许 .exe/.dll）。
  - 发布前 QA 必跑一次实测体积记录进 spec（见 §5 QA-4）。

### 1.3 与"自动更新"的交互（明确）

- 引擎是**完整包一次性资产**，不随 update.zip 走。更新器只覆盖三关键文件；老引擎在 `~/.gbl` 或已内置者原地复用。
- 引擎版本升级（>0.4.2）时：改 `vendor/` 两份二进制 + `TraversalProvider` 的 `ASSET/SHA256/EXE_NAME` + pack 哈希常量，随**完整包**发新版；更新用户下次拉完整包（或联网兜底下新版）即升级。
- **无歧义点**：内置目录 `GBL/engines/` 与 `GBL/jre/`、`runtime/` 平级，互不冲突；`~/.gbl` 与内置独立，二者都有时**内置优先**（保证离线冷启动确定成功）。

---

## 2. UI 改动方案（`MultiplayerPage` + `theme.css`，类/方法级）

### 2.1 大厅卡重构（`buildHallCard()`）—— 三段式紧凑

拆成**三个互斥子区**（`setShown` 切换，沿用现有 `setShown/setShownIfChanged`）+ 常驻脚注：

```
card(.card.mp-hall-card):
  idleBox   (VBox8)  ← WAITING/UNKNOWN 才显示
     playerLine            "玩家：X（来源）"   .setting-desc   （req1 玩家一行）
     idleBtns HBox10       [createBtn(btn-download)] [joinRoomBtn(btn-download)]
  busyBox   (VBox6)  ← *_SCANNING/*_STARTING/*_CONNECTING 才显示（紧凑不撑高）
     engineBar (thin, 8px) + statusText("正在创建房间…/正在连接房间…")
     cancelBtn(btn-ghost)  ← 真能用（doCancel）
  doneBox   (VBox8)  ← HOST_OK/GUEST_OK/EXCEPTION 显示
     statusHead + inviteRow + actionRow(copy/close/exit/retry/reset/cancel) + lanBar
  license   (常驻)  "基于 Terracotta…· EasyTier…"
card.getChildren().addAll(idleBox, busyBox, doneBox, license)
```

删除（从卡片移除并删方法）：顶部 `engine` 大字状态条、三段 `desc`、`bigRows`（两大宽行）、`buildJoinArea`（页内展开）、`buildMissingBar`（req3）、`buildProbe/Config/Log`（1.2 已不挂）。
- **保留 `lanBar`**（房间建好后的中性局域网引导，功能性，`applyState` 里 `LANBridge.needsLanOpen` 驱动）放入 doneBox。
- 待机默认不显示 engine 大字与 desc → 满足 req1「紧凑不撑高」。

`applyButtons(s)` / `applyState` 重写为按**状态组**切三段：
- WAITING/UNKNOWN → `idleBox` 显示，其余隐藏。
- 4 个 busy 态 → `busyBox` 显示（`cancelBtn` 可见），`idleBox/doneBox` 隐藏。
- HOST_OK/GUEST_OK/EXCEPTION → `doneBox` 显示，`idleBox/busyBox` 隐藏。

### 2.2 按钮事件绑定（根因修复，req2）

`buildHallCard()` 里加完控件后**统一绑一次**（可见性由 `applyButtons` 控，绑定只绑一次）：
```
createBtn.setOnAction(e -> startHostFlow());
joinRoomBtn.setOnAction(e -> startGuestFlow());   // req4：改走 showInviteModal
copyBtn  .setOnAction(e -> doCopy());
closeBtn .setOnAction(e -> stopRoom(true));
exitBtn  .setOnAction(e -> stopRoom(false));
resetBtn .setOnAction(e -> doReset());
cancelBtn.setOnAction(e -> doCancel());
retryBtn .setOnAction(e -> doRetry());
```
新增 `doCancel()`：
```
// 中断当前建房/加入，回待机：引擎 GET /state/ide（保留进程，下次可直接用）
RoomService.get().resetIdleAsync((ok,error)->runFx(() -> {
   if (error!=null || !Boolean.TRUE.equals(ok)) showToast("取消失败："+shortError(error));
}));
```
`resetIdleAsync` 在未 running 时会 fail，但 `cancelBtn` 只在 busy 态（running=true）可见，逻辑自洽。

### 2.3 客人邀请码居中模态（`showInviteModal()`，req4）

替换 `startGuestFlow()` 里的 `this::revealJoin` → `this::showInviteModal`（仍经 `confirmGameThen` 先判游戏是否启动）。仿 `showGameModal` 写法：
```
Label icon("?");  title="请输入房主提供的邀请码";  sub="把房主给的邀请码粘进来（格式 U/XXXX-XXXX-XXXX-XXXX）"
TextField modalCode (prompt 粘贴邀请码, .gbl-input, maxWidth 撑满)
Label  modalErr (.chip-error, 默认隐藏)
[确定 btn-primary/.mp-modal-btn] [取消 btn-ghost/.mp-modal-btn]
card .card.mp-modal-card → modalLayer.setAll(card), StackPane.CENTER, setShown(modalLayer,true)
确定: code=InviteCode.normalize(…); if(!isValid){ modalErr.setText(必填/格式红字); show; 停留 }
     else { dismiss(); 走 doJoinByCode(code)（内部 lastWasCreate=false; guestRoomAsync） }
取消: dismiss()  →  真关模态（modalLayer 清 + 隐藏 + pendingModalAction=null）
```
`doJoin()` 保留作"确定"的执行体，改为接收模态输入值（新增 `doJoinByCode(String)`，`doJoin` 变薄壳或直接用模态值）。必填红字：空输入 →「邀请码不能为空」，非法 → 现有 `codeError` 文案。

### 2.4 `theme.css` 收口（只加 `.gbl-compact` 后代）

新增/微调（限 `.gbl-compact`）：
- `.gbl-compact .mp-idle-box`（padding/居中）、`.gbl-compact .mp-idle-buttons .btn-download`（两绿按钮间距/宽度）。
- `.gbl-compact .mp-busy-line`（单行紧凑：engine 8px 条 + 小字 statusText + 取消）。
- `.gbl-compact .mp-invite-modal`（输入框 + 红字 + 双按钮排布；复用现有 `.mp-modal-card/.mp-modal-title/.mp-modal-btn`）。
- 删/不再引用：`.mp-missing`（随 missingBar 删除）。其余 1.2 已留的 `mp-*` 保持不动（HMCL 只对齐骨架，不做像素级 CSS，铁律遵守）。

### 2.5 冗余清理（req5，`build*`/字段）

删除死方法与对应字段：`buildProbeSection/probeRow`、`buildConfigSection`、`buildLogCard`、`buildJoinArea`、`buildMissingBar`、`bigRow`；字段 `probe*、nodeMode、customNodesField、logArea、logPane、joinCard、joinArea、codeField、codeError、avatarBox/avatarText、identityName/Source/Badge、relayWarn、ARROW 常量、Icons 引用`。
`refreshIdentity()` 收敛为**只 set `playerLine`**（idleBox 用），删 identity 卡片相关 set。
（此为 P1 可选项：不动它也能跑，但 req5 明确要"删冗余去乱"，建议做。）

---

## 3. 有序任务列表

> 规则：≤5 任务；第一个任务=项目基础设施（发布管线脚本 + 入口版本 + 依赖声明）；按功能模块分组；T02/T03 尽量只依赖 T01。

### T01 · 发布管线 & 版本基线（P0，必须）
- **涉及文件**：
  - `src/main/java/com/mclauncher/LauncherApp.java`（`VERSION`/`BUILD` → `1.2.1`，新增 `FP_1_2_1 = "GBL 1.2.1 联机页紧凑化·取消可用·引擎内置"` + static 打印）
  - `tools/build_1.2.1.py`、`tools/pack_1.2.1.py`、`tools/preflight_1.2.1.py`、`tools/check_1.2.1_logic.py`、`tools/publish_1.2.1.py`、`tools/publish_github_1.2.1.py`、`tools/push_site_1.2.1.py`、`tools/verify_live_1.2.1.py`（**从 1.2 派生**，非从零）
- **做什么**：
  1. `LauncherApp` 版本号 `1.2`→`1.2.1`，埋 `FP_1_2_1` 指纹（进常量池，build 门禁可扫）。
  2. 派生 8 个 1.2.1 门禁脚本：`build` 的 `read_version/read_build` 期望 `1.2.1`、CHECKS 追加 FP_1_2_1 并**逐字保留历代指纹**；`pack` 的 `OLD_LINK_RE` 覆盖到 `1.2`、`NEW_LINK=v1.2.1/1.2.1-portable.zip`、`version.json` 主字段指 `1.2.1-update.zip`、`full*` 指 `1.2.1-portable.zip`、`LIMIT` 维持 98MB；`preflight/check` R 字典追加 1.2.1 段、接 group_a/group_b rels 并集 + items() 逐条循环 2 处；`verify_live` FORBID 放 1.2 旧值、EXPECT 与本版官网四页逐字一致；官网四页 + `website/version.json` 纯 CRLF。
  3. **三渠道独立 release**：`publish_1.2.1.py`(Gitee) 与 `publish_github_1.2.1.py` 各自 `create_release v1.2.1`，**绝不塞 1.2 旧 release**；旧 v1.2 资产原封保留。
- **依赖**：无（首任务）。

### T02 · 引擎内置（vendor + TraversalProvider + build/pack 接线）（P0，必须）
- **涉及文件**：
  - `vendor/engines/terracotta/terracotta-0.4.2-windows-x86_64.exe`、`VCRUNTIME140.DLL`、`port.txt`、`.complete`（**逐字节固定二进制 + 预建占位**）
  - `src/main/java/com/mclauncher/multiplayer/TraversalProvider.java`（内置解析 + 离线兜底）
  - `tools/build_1.2.1.py`（deploy 末尾拷引擎 + SHA256 双校验）、`tools/pack_1.2.1.py`（完整包含引擎断言 + 更新包保持无引擎 + 98MB 门禁）
- **做什么**：
  1. 落地 `vendor/engines/terracotta/`（§1.1 两份 SHA256 定死）。
  2. `TraversalProvider`：加 `appRoot()`/`BUILTIN_ENGINE_DIR`；`resolveInstalledDir()` 三层（内置→`~/.gbl`→联网），内置只读零写入，联网兜底/坏包保留/零删除全保留。
  3. `build_1.2.1.deploy()` 把 vendor 引擎拷进 `dist/GBL/engines/terracotta/` 并 SHA256 双文件校验；`pack_1.2.1` 完整包断言 4 文件在包内且哈希命中，更新包维持 3 件无引擎，`LIMIT=98MB`。
- **依赖**：T01（需 `LauncherApp=1.2.1` 供 build 校验通过）。

### T03 · 联机页 UI 重构（P0，必须）
- **涉及文件**：
  - `src/main/java/com/mclauncher/ui/MultiplayerPage.java`（三段式卡片 + 按钮绑事件 + 客人模态 + 删 missingBar）
  - `src/main/resources/com/mclauncher/theme.css`（`.gbl-compact` 后代新增，删 `.mp-missing`）
- **做什么**（§2 全量）：
  1. `buildHallCard()` 重构 idle/busy/done 三段 + 常驻 license；删大字引擎条/三段 desc/两大宽行/页内 joinCard/missingBar。
  2. `actionRow` 8 按钮**全部 `setOnAction`**；新增 `doCancel()`（`resetIdleAsync` 真中断回待机）。
  3. 新增 `showInviteModal()`（居中模态、必填/格式红字、取消真关），`startGuestFlow` 改走模态；`doJoin` 接模态输入。
  4. `applyState`/`applyButtons` 按状态组切三段；`updateEngineBar` 去 missing 形参。
  5. `theme.css` 只加 `.gbl-compact` 下 `mp-idle-box/mp-busy-line/mp-invite-modal` 等，删 `.mp-missing`。
  - **FX 线程铁律**：worker 只做数据/IO；改 `getChildren()`（modalLayer setAll/clear、toast add/remove、三段 show/hide）一律 `runFx`；看门狗不信任布尔位，判真实界面态。
- **依赖**：T01（版本基线）。与 T02 可并行（不碰同一文件）。

### T04 · 冗余清理 & 样式收口（P1，推荐）
- **涉及文件**：`MultiplayerPage.java`（删死方法/死字段，`refreshIdentity` 收敛为只喂 playerLine）、`theme.css`（mp-* 死样式清收）。
- **做什么**：删 §2.5 列的死方法（probe/config/log/joinArea/missingBar/bigRow）与孤立字段；保留 lanBar/relay 功能性逻辑。**若 P0 时间紧可后置，不影响功能**。
- **依赖**：T03（在 T03 重构完成后清理，避免重复改动同一方法）。

### T05 · 门禁探针 & QA 接线（P0，必须）
- **涉及文件**：`tools/check_1.2.1_logic.py`（buggy 反向探针）、`tools/preflight_1.2.1.py`、`docs/` QA 清单（写入 §5）。
- **做什么**：
  1. buggy 反向探针：旧逻辑（只看 `currentProcess`）应被抓 FAIL；**取消不中断**旧行为反向鉴别；**内置缺失时联网兜底**路径鉴别。
  2. 静态审计：`actionRow` 8 按钮全 `onAction≠null`；`showInviteModal` 取消路径确实 `setShown(modalLayer,false)+clear`。
  3. 跑全链：`build→pack→preflight→publish(gitee/github)→push_site→verify_live --strict-web`，记录完整包实测体积（§5 QA-4）。
- **依赖**：T01+T02+T03（在源码/引擎/管线齐备后收口）。

**"必须做"**：T01、T02、T03、T05。 **可选**：T04（P1）。

---

## 4. 精炼任务列表 JSON（供转工程师）

见 `docs/GBL_121_TASKS.json`。

---

## 5. QA 检查点（Round 1 必验）

1. **取消真中断**：建房/加入进行中点「取消」→ 状态回 WAITING、引擎 `/state`=waiting、进程仍存活可复用；连点不崩。buggy 反向：不绑 cancelBtn 的旧代码必须被抓。
2. **客人模态取消能关**：「加入房间」→ 居中模态；空输入点确定 → 红字「不能为空」停留；非法码 → 红字格式提示；点取消 → 模态真关（`modalLayer` 隐藏且 children 清空）；合法码 → 关闭并进入 GUEST_CONNECTING。
3. **引擎内置冷启动离线**：干净环境（无 `~/.gbl`、断网）跑完整包 → 点创建房间**无需联网**即起引擎、`/meta` 200；`TraversalProvider.isInstalled()` 命中**内置目录**（日志可验）。断网 + 无 `~/.gbl` 不报"缺少组件"。
4. **包体积**：`1.2.1-portable.zip` 实测 ≤ 98MB（预期 92~93MB）；`1.2.1-update.zip` 恰好 3 条目、**无 .exe/.dll**、< 98MB；Gitee 单附件 < 100MB。记录真实字节数入 spec。
5. **FX 线程**：shown 场景运行时探针，三段 show/hide + modal setAll/clear + toast add/remove **无 CME**；`runFx` 跨线程不抛；worker 线程不改 JavaFX 树。
6. **按钮全绑**：静态审计 `actionRow` 8 按钮 `onAction` 全非 null；复制/关闭/退出/返回待机/重试均可用。
7. **门禁与指纹**：build/pack/preflight/check `--strict-web` 全绿；`FP_1_2_1` 在 modules 常量池可扫（先解 jimage）；历代指纹逐字保留；buggy 反向鉴别有效。
8. **换行符 & 渠道**：`MultiplayerPage.java` 纯 LF；`theme.css` 与官网四页纯 CRLF；Gitee/GitHub **各自新建 v1.2.1**，旧 v1.2 不动；官网下载链全为 `v1.2.1/1.2.1-*.zip` 无混合坏链。
9. **零删除**：构建用唯一时间戳新目录；`.minecraft/`、`GBL/jre/` 未被触碰；引擎坏包改名保留不删。

---

## 6. 待明确 / 风险

| 项 | 状态 | 处置 |
|---|---|---|
| 95/98MB 是否顶破 | **低风险** | 完整包 87.6MB + 引擎压缩 ≈ 92~93MB < 98MB < 100MB。build 完**实测记录**（QA-4）；万一 >98MB，`LIMIT` 精确抬到 min(实测,99MB) 且不破 Gitee 100MB，**不放弃内置**。 |
| 更新包是否带引擎 | **明确不带** | 更新包结构（3 件、拒 .exe/.dll、避文件锁）不允许；更新用户走 `~/.gbl` 或联网兜底，**无回归**。引擎升级随完整包走。 |
| 内置目录只读 | 已解 | 预建 `port.txt/.complete`，运行时对内置目录零写入；`ensureInstalled` 仅第 3 层且只写 `~/.gbl`。 |
| 更新用户没装过引擎 + 断网 | **已知边界** | 走不到内置（更新包不带）也无 `~/.gbl` 且断网 → 引擎不可用。属可接受边缘（1.2 同款行为）；联网即自愈。**不在本版扩大范围**。 |
| `bigRow`/`Icons` 删除连带 | 已解 | 删 bigRow 后 `Icons`/`ARROW` 无引用，T03/T04 一并清；编译门禁兜底。 |
| 版本位数 1.2→1.2.1 | 已解 | 逐段数值比较（缺段补 0），禁尾段启发式；门禁脚本 R 字典 1.2.1 独立段。 |
