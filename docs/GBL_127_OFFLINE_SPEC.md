# GBL 1.2.7 离线可用 / 更新失败不弹框 规格与验收口径

版本：`VERSION = BUILD = "1.2.7"`；指纹 `FP_1_2_7 = "GBL 1.2.7 更新失败不弹框·离线可用·检查静默降级·联网失败不阻断"`。

本版采用团队倾向的方案：**启动自动检查失败 = 非阻断右下角短提示（不弹任何模态框）**；
「已是最新版」保持静默；用户主动点更新后的**安装**失败仍保留可读反馈（用户主动触发、
点「取消」或点遮罩即可关闭、绝不自己反复弹出），两种失败路径彻底分离。

---

## A. 启动自动检查失败 = 静默降级为非阻断 toast（核心）

### 根因（逐字坐标，1.2.6 原样）

1. `LauncherApp.java:375-379`：`if (SettingsStore.get().isAutoUpdate()) UpdateController.startAsyncCheck(shell);`
2. `UpdateController.java:64-67`：`UpdateChecker.checkAsync(ctrl::onFound, host::showUpdateFailed)`
3. `UpdateChecker.java:107-127`：后台线程任何失败（超时 / HTTP / JSON / Content-Type / TLS handshake）
   都在 FX 线程 `onFailed.accept(reason)`；「已是最新」不回调。
4. `UpdateController.java:107-116`：安装失败回调也调 `host.showUpdateFailed(...)`。
5. `ShellView.java:6531-6580`：`showUpdateFailed` 里 `showOverlayLayer(updateDialog, "update-dialog")`
   拉起模态遮罩，标题「这次没更新成功」，红色技术串堆在 stage，露出重试 / 手动下载按钮 —— 用户截图。

### 修法（最终调用链）

`UpdateUi` 新增 `default void onAutoCheckFailed(String reason){}`；
`startAsyncCheck` 改为 `UpdateChecker.checkAsync(ctrl::onFound, host::onAutoCheckFailed)`；
`ShellView.onAutoCheckFailed` 只调 `toast("离线模式：暂时连不上更新服务器，已跳过检查")`。

### 必须出现（字符串 / 调用链）

| 位置 | 必须出现 |
| --- | --- |
| `UpdateController.java` | `default void onAutoCheckFailed(String reason) {}` |
| `UpdateController.java` | `UpdateChecker.checkAsync(ctrl::onFound, host::onAutoCheckFailed);` |
| `UpdateController.java` | 指纹 `FP_1_2_7_SILENT_CHECK` 且 static 块打印 |
| `ShellView.java` | `@Override public void onAutoCheckFailed(String reason)` |
| `ShellView.java` | `toast("离线模式：暂时连不上更新服务器，已跳过检查")` |
| `ShellView.java` | `Platform.isFxApplicationThread()` 保护 ObservableList 变更 |
| `UpdateChecker.java` | `onFailed != null && !f.latest` |
| `LauncherApp.java` | `FP_1_2_7` + static 块打印 |

### 必须消失（针对启动检查失败路径）

| 位置 | 必须消失 |
| --- | --- |
| `UpdateController.startAsyncCheck` 方法体 | `host::showUpdateFailed`（改回即 FAIL） |
| `ShellView.onAutoCheckFailed` 方法体 | `showOverlayLayer`、`updateDialog` |
| 启动失败提示文案 | 「这次没更新成功」「你还是 X (X)，文件没有被改动」红色技术串 |

### 验证口径

- 门禁 `K-A` 组取 **方法体**（不是全文字符串）判调用链：`startAsyncCheck` 含 `host::onAutoCheckFailed`
  且不含 `showUpdateFailed`；`onAutoCheckFailed` 含 `toast(` 且不含 `showOverlayLayer` / `updateDialog`。
- buggy 反向：把 `host::onAutoCheckFailed` 改回 `host::showUpdateFailed` → `K-A` 必须 FAIL；
  把钩子实现改成 `showOverlayLayer(updateDialog, ...)` → `K-A` 必须 FAIL。
- 真机：发起模拟检查失败后 `ShellView.isUpdateDialogVisibleForTests() == false`，
  `ShellView.lastToastTextForTests()` 等于离线提示文案，`toastBox` 里可见气泡但鼠标穿透。

---

## B. 用户主动更新失败 与 启动检查失败 分开

### 处理差异（必须说清）

| 路径 | 触发者 | 反馈方式 | 是否模态 |
| --- | --- | --- | --- |
| 启动自动检查失败 | 系统 | 右下角非阻断 toast（A） | **绝不** |
| 用户主动点「更新」后的安装失败 | 用户 | `showUpdateFailed` 在已打开的对话框内显示可读原因 + 「重试」+「打开官网手动下载」 | 对话框本就由用户打开；点「取消」或点遮罩即关；不自动重弹 |

选择保留安装失败对话框的理由：它只在用户主动点「更新」后出现，取消路径完整
（`updateDlgCancelBtn.setOnAction` → `requestCancelUpdate()` + `updateOnCancel.run()`），
且 `requestCancelUpdate()` 会真正中断下载；不会像启动自动检查那样在用户毫无操作时自己弹出来。
两条路径靠**不同钩子**分离，`showUpdateFailed` 在 `UpdateController` 中只剩安装失败一个调用点。

### 必须出现 / 必须消失

- 必须出现：`host.showUpdateFailed(readableReason(t))` 且仅在 `onUpdate()` 方法体内；
  `ctrl.count("showUpdateFailed(") == 1`；`updateDlgCancelBtn.setOnAction` 取消链完整。
- 必须消失：`startAsyncCheck` 内任何 `showUpdateFailed` 引用（含注释以外的真实调用）。

### 验证口径

门禁 `K-B` 组 + QA 验「安装失败弹框能正常点取消关闭、不会反复自己弹出」。

---

## C. 离线可用（无网冷启动，本地功能照常）

### 必须出现 / 必须消失

| 项 | 必须出现 | 必须消失 |
| --- | --- | --- |
| 清单同步 | `refreshManifest` 后台线程 + `applyManifest(result)` 静默落地 | `requestManifestSync` / `refreshManifest` 内 `Alert` / `showOverlayLayer` |
| 本地功能 | `switchPageViaNavForTests` 走 `navItemByPage.get(target)` 真实 handler；`restoreOverlaysForPage(page)` | 本地页面切换依赖 `UpdateChecker` |
| QA 钩子 | `isUpdateDialogVisibleForTests()` / `lastToastTextForTests()` / `toastCountForTests()` / `simulateAutoCheckFailureForTests(String)` | — |

### 无网模拟方法（QA 可用任一）

1. **应用内开关（推荐）**：调用 `ShellView.simulateAutoCheckFailureForTests(reason)` 或
   `UpdateController.simulateAutoCheckFailureForTests(host, reason)`，直接走与真实后台失败
   完全相同的非阻断钩子，不需改系统代理。
2. **系统代理指向无效地址**：`https_proxy=http://127.0.0.1:9` 后冷启动，
   `UpdateChecker` 两个源都会超时/连接失败并回传非阻断钩子；`VersionManifest.fetch()` 失败静默落地。

### 无网下必须仍可操作

启动已安装 MC、联机大厅 UI（切页/按钮可点）、游戏管理、设置、账号切换、已下载内容管理。
联网功能（检查更新、下载游戏/依赖、联机匹配服务）在无网时不可用属预期。

### 验证口径

真机探针 `QaOffline127Probe`：真实 ShellView + shown Scene，模拟检查失败后逐项断言
模态不可见 / toast 文案正确 / 切页与本地按钮可点；证据行 `OFFLINE-MODAL=false`、
`OFFLINE-TOAST=...`、`OFFLINE-NAV=...` 写入交付摘要。

> `LauncherApp.checkLastUpdateStamp()` 与 `showDeferredUpdateResult()` 只在本地标记文件存在
> 且表明上次**安装**未生效时才弹一次 Alert，与网络无关；无网冷启动（无标记）不弹。

---

## D. 版本 / 门禁 / 官网 / 文档

- `VERSION/BUILD = "1.2.7"`；`FP_1_2_7` 新增并 static 打印进常量池；R126 及更早指纹、历史卡片、
  历史门禁断言一条不删；新增 R127 独立字典，接进 group_a/group_b 各 2 处聚合。
- 工具全部从 `*_1.2.6.py` 派生：`build/pack/preflight/check/verify_live/publish/publish_github/
  push_site/_run_with_gh_token/qa_buggy_reverse`，另有 `apply_127_source.py` / `derive_127.py`。
- 逐字核验：publish `TAG/VER/BODY`、publish_github `BODY`、PATCH body、push_site `MESSAGE`、
  verify `EXPECT/FORBID`、check 指纹字典与聚合、pack `OLD_LINK_RE/NEW_LINK`、
  preflight `PREV_VERSION = "1.2.6"`。
- 本版发布说明**不得**把「弹窗内容自适应 / 忙态横向 / 弹层登记表 / HMCL结构」当成本版内容
  （历史卡片 / 「历史全部保留」段里保留可以）。
- 官网四页 + `version.json` 同步 1.2.7，下载链 `v1.2.7/1.2.7-*.zip` 无混合串；
  四页 HTML 与 version.json 纯 CRLF、`MultiplayerPage.java` 纯 LF、`theme.css` 纯 CRLF、
  `ShellView.java` 纯 CRLF，按 bytes 保持。
- 1.2.6 三项回归断言保留：弹窗尺寸内容自适应、忙态横向紧凑卡、弹层全局登记表跨页恢复。
- buggy 反向三条：① 启动检查失败改回 `showUpdateFailed` → FAIL；② 自动检查失败弹模态框 → FAIL；
  ③ 无网降级缺失（失败仍回调弹窗 / 阻断）→ FAIL。断言看**最终行为 / 调用链**而非字符串存在。

---

## E. 追加修复：失败弹窗三个出口都能退出（确凿 bug）

### 根因
`showUpdateFailed(reason)` 直接 `showOverlayLayer(updateDialog,"update-dialog")` 把框拉出来，
但**从未设置 `updateOnCancel`**（该回调只在 `showUpdateDialog` 里赋值）。失败时
`UpdateInstaller.isInstalling()==false` → `requestCancelUpdate()` 空操作；`updateOnCancel` 为 null
（或上次残留的 runnable 不会关框）→ 取消按钮与遮罩点击两个分支都不关框，用户点「取消」纹丝不动。

### 修法
- `showUpdateFailed` 的 FX 线程块内绑定 `ShellView.this.updateOnCancel = ShellView.this::closeUpdateDialog;`
- 取消按钮 / 遮罩 handler 统一改为 `cancelOrCloseUpdateDialog()`：**无条件先 `closeUpdateDialog()`**，
  再 `requestCancelUpdate()`，再跑业务回调；重复点击幂等、不抛异常。
- 手动下载按钮改走 `openManualDownload()`：**先关框**再开浏览器；QA fire 时用
  `suppressOpenBrowserForTests` 抑制真实浏览器启动，只验关框。
- 所有操作都在 FX 线程（按钮 handler 本身在 FX；`showUpdateFailed` 已有 `Platform.runLater`）。

### 必须出现 / 必须消失 / 验证
- 必须出现：`this::closeUpdateDialog` 在 `showUpdateFailed` 方法体；`cancelOrCloseUpdateDialog` 内
  `closeUpdateDialog();` 早于 `requestCancelUpdate();`；三出口 QA 钩子
  `clickUpdateCancelForTests / clickUpdateBackdropForTests / clickUpdateManualLinkForTests`。
- 必须消失：取消按钮 / 遮罩里「先 requestCancelUpdate 再跑可能为空的 updateOnCancel」的旧写法。
- 门禁 `K-E` 断言调用链；buggy 反向「不绑定取消回调」必须 FAIL；
  真机探针逐出口 fire 后 `isUpdateDialogVisibleForTests()==false`，重复 fire 无异常。

## F. 追加修复：启动期两处原生模态 Alert 清零

### 根因
`LauncherApp.showDeferredUpdateResult()` 用 `Alert.showAndWait()`（标题「更新结果」/「这次自动更新没有成功」），
由 `start()` 的 `Platform.runLater` 触发，读 `app/gbl-update-result.txt` 的 FAIL 标记后弹原生模态框；
`checkLastUpdateStamp()` 也有 `Alert.show()`（标题「上次更新没有生效」）。两者与自绘框叠加，同为「卡住」来源。

### 修法
- 两处**全部删除原生 Alert 调用**（`new Alert(` / `showAndWait()` / `a.show();` 均不再出现），
  改为 `ShellView.toastGlobal(...)` 右下角非阻断短提示；shell 尚未创建时只写日志，绝不弹窗。
- 技术原因（连不上服务器 / handshake / 失败标记原文）**只写 `System.err` 日志**，不堆给用户；
  给用户的只有一句友好文案：「上次自动更新没能完成，已跳过；本地功能照常可用，需要时到设置里检查更新」。
- 失败标记照旧 `Files.move` 改名留档（`gbl-update-result-read-<nano>.txt`），零删除不变。
- QA 钩子 `ShellView.simulateDeferredUpdateFailureForTests(reason)` 走真实 `toastGlobal`。

### 门禁 / 真机
`K-E` 断言无原生 Alert 调用 + 失败标记改名仍在 + toastGlobal 有 FX 线程保护；
buggy 反向把 `new Alert(...).showAndWait()` 塞回 → FAIL。真机探针模拟启动期失败后模态不可见、toast 文案友好。

## G. 代理 / 网络环境说明与加固

「官网源：连不上服务器」「GitHub源：Remote host terminated the handshake」是 **TLS 被阻断的典型表现**，
属于国内部分网络访问 Cloudflare Pages / GitHub 需要代理的环境问题，**不是 GBL 逻辑错误**。
`UpdateChecker` 已配置 `.proxy(ProxyRoute.selector())`，跟随设置页「出海线路」选择的代理。

加固口径：
1. 检查失败一律静默降级 —— 已覆盖启动自动检查（`onAutoCheckFailed`）、设置页手动检查
   （`versionRow` 只在文字里显示结果）、清单同步（`refreshManifest` 静默落地）、
   启动期结果反馈（`showDeferredUpdateResult` / `checkLastUpdateStamp` 改 toast，见 F）。
2. 无代理或代理不通时启动不被卡住、无任何模态框（后台线程 20s 超时 + 3 次重试后自动放弃）。
3. 右下角提示只给友好文案，技术串只进日志。
4. 无网可用的是 GBL 本体（启动游戏、联机大厅 UI、游戏管理、设置、账号、已下载内容）；
   检查更新、下载游戏与依赖、联机匹配服务在无网时静默不可用，属预期。

## 本地验收命令（全部必须绿）

```
python tools/gbl_compile.py
python tools/build_1.2.7.py
python tools/pack_1.2.7.py
python tools/preflight_1.2.7.py
python tools/check_1.2.7_logic.py --strict-web
python tools/qa_buggy_reverse_1.2.7.py
python tools/run_qa_offline_127.py       # 自写真机探针
```

build 与 selftest 不并行（terracotta 锁 target）；不改 `.minecraft/`、`GBL/jre/`；
不删任何 zip / 旧目录；构建用唯一时间戳新目录；**不发布到 Gitee / GitHub / 官网**。
