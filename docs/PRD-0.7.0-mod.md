# GBL 0.7.0「Mod 三件套」增量 PRD

| 项 | 内容 |
|---|---|
| 文档类型 | 增量 PRD（简单档，不含竞品分析） |
| 产品 / 项目 | GBL Minecraft 启动器（包名 `com.mclauncher`，仓库 `mc-launcher`） |
| 目标版本 | **0.7.0**（功能版本，需动版本号；当前 0.6.1） |
| 技术栈 | JavaFX 21 UI（`ui\ShellView.java` 一套代码）+ 纯 Java 服务层；零第三方依赖（JDK 自带 `HttpClient` / `java.util.zip` / `MessageDigest` + 自研 `Json.java`） |
| 目标平台 | Win / macOS / Linux（当前本机只能出 Windows 包） |
| 设计蓝本 | `docs/HMCL-MOD-REFERENCE.md`（HMCL main 分支机制提炼，2026-10-13）。**P0/P1/P2 分级直接采用该文档第 5 节建议** |
| 撰写 | 许清楚（Product Manager） |
| 状态 | 待 team-lead 拍板「待确认问题」后进入架构阶段 |

---

## 1. 背景与问题定义

GBL 0.6.1 已具备：账号（离线 / LittleSkin / 微软）、官方版本下载与启动（直连 Mojang）、3D 皮肤预览、游戏列表、控制台、设置。

**问题**：目标用户以中国地区的离线 / LittleSkin 账号玩家为主，这批人基本都玩 mod，但 GBL 目前没有任何 mod 能力：

1. 想玩 Fabric / Quilt 整合包，必须自己拼 `version.json`，门槛极高；
2. 装 mod 要开浏览器去 Modrinth 下载、手动丢 `.minecraft/mods`，启停卸载全手工；
3. 朋友发的整合包 zip 无法一键导入；
4. 下载直连 Mojang 官方域名，国内网络下不稳定。

**0.7.0 用「Mod 三件套」一次性补上这条链路**：加载器一键安装 + Mod 管理 + 整合包导入。

---

## 2. 产品目标

| # | 目标 | 衡量口径 |
|---|---|---|
| G1 | **加载器零门槛**：任意已安装版本一键安装 Fabric / Quilt / LegacyFabric，用户全程不碰 version.json | 装完原版本直接可启动进游戏；可一键卸载还原 |
| G2 | **Mod 全生命周期管理**：搜索、安装、启用/禁用、卸载在启动器内闭环（数据源 Modrinth + 本地文件） | 装一个 mod 不用打开浏览器或文件管理器 |
| G3 | **整合包即开即玩**：`.mrpack` 导入后直接可启动（加载器、mod、配置一步到位） | 导入流程 ≤ 3 次点击；导入完成即出现在游戏列表且可启动 |

三个目标正交：G1 解决「跑不跑得起来」，G2 解决「日常管不管得过来」，G3 解决「分发」。

---

## 3. 范围

### In Scope（0.7.0）

| 范围 | 优先级 | 说明 |
|---|---|---|
| 元数据型加载器安装：Fabric / Quilt / LegacyFabric | P0 | 蓝本第 2 节 A 类：读 meta API 的 launcherMeta，拼 libraries + mainClass + args |
| Mod 安装（Modrinth 搜索/安装 + 本地文件导入） | P0 | 下载到 `.minecraft/mods` |
| Mod 列表 / 启用 / 禁用 / 卸载 | P0 | `.disabled` 改名法；jar 内元数据读取 |
| `.mrpack` 整合包导入 | P0 | overrides 解压 + files 下载校验 + 复用 P0 加载器安装 |
| 版本号 0.6.1 → 0.7.0 | P0 | 含 `GameDownloader.AGENT` 硬编码修正 |
| 自测扩充与回归 | P0 | 现有 106 项不破 + 新增 |
| Forge / NeoForge 安装器 | P1 | 蓝本第 2 节 B 类：install_profile processors 运行时，单项最大块 |
| CurseForge / MultiMC 整合包 | P1 | `instance.cfg` + `minecraftinstance.json` + `patches` |
| Mod 更新检测与更新 | P1 | 原需求提及，按蓝本分级后置（见待确认 Q2） |
| BMCLAPI 国内下载镜像 | P1 | 独立需求，叠加于现有下载链（见待确认 Q3） |
| LiteLoader / OptiFine / Cleanroom | P2 | 蓝本第 2 节 C 类 |
| 整合包导出 / 整合包更新 | P2 | 更新时按 sha1 判断用户是否改过文件 |

### Out of Scope（明确不做）

- 不重写 / 重构任何现有模块（账号、下载、启动、皮肤、游戏列表），只做增量扩展
- 不引入任何第三方库（依赖仅 JDK 自带 + 自研 `Json.java`）
- 不做 mod 配置文件编辑、不做 mod 源码级依赖解析（仅按蓝本读展示性元数据）
- 不做 macOS / Linux 打包（本机只能出 Windows 包）
- 不做 OptiFine（改写客户端 jar 型，P2）

---

## 4. 用户故事

| # | 故事 |
|---|---|
| US-1 | 作为离线账号玩家，我想给已装的 1.20.1 一键安装 Fabric，这样我能直接玩需要 Fabric 的 mod，而不用自己拼 version.json |
| US-2 | 作为 Mod 玩家，我想在启动器里搜「Sodium」并直接安装，这样不用开浏览器去 Modrinth 下载再手动丢 mods 文件夹 |
| US-3 | 作为整合包玩家，我想导入朋友发的 `.mrpack`，这样加载器、几十个 mod、配置一步到位，导完直接开玩 |
| US-4 | 作为怕麻烦的玩家，我想在界面上一键禁用 / 卸载 mod，这样不用去文件管理器里改文件名 |
| US-5 | 作为国内玩家，我想下载能走国内镜像（BMCLAPI），这样下游戏和依赖库不全靠运气 |

---

## 5. 需求池（P0/P1/P2）

> 优先级定义：P0 = 0.7.0 必做；P1 = 0.7.x 后续迭代；P2 = 锦上添花。
> 每条验收标准均为可客观检验的通过条件。

### 5.1 P0 需求（0.7.0 必做）

#### P0-1 版本号升级 0.6.1 → 0.7.0

| 项 | 内容 |
|---|---|
| 需求 | `LauncherApp.VERSION` 改为 `"0.7.0"`；`GameDownloader.AGENT` 当前硬编码 `"GBL-Minecraft-Launcher/0.6.1"`，改为引用 `VERSION` 拼接，杜绝版本号两处维护；窗口标题显示 v0.7.0 |
| 验收 | ① 启动后标题栏显示 `GBL · Minecraft Launcher v0.7.0`；② 源码内 `grep` 无残留硬编码 `0.6.1`（官网文案属发布物料，另计）；③ UA 字符串包含 0.7.0 |

#### P0-2 元数据型加载器一键安装（Fabric / Quilt / LegacyFabric）

机制对齐蓝本第 2 节 A 类 + 第 1 节「组件补丁」GBL 等效做法。

| 项 | 内容 |
|---|---|
| 需求描述 | 对游戏列表选中版本，安装 / 卸载 Fabric、Quilt、LegacyFabric。**不改原版 JSON**：生成「带加载器的版本 JSON」——libraries 追加加载器库、mainClass 覆盖、按需追加游戏参数，并记录「该版本已装 X 加载器」标记 |
| 详细规则 | ① 版本列表来源：`meta.fabricmc.net` / `meta.quiltmc.org` / `meta.legacyfabric.net`（实现前按蓝本第 0 节补抓 `*RemoteVersion.java` 复核 URL 与解析）；② 基于**完整版** version.json 合并：`launcherMeta.libraries.common + libraries.server` 逐条转 Library 追加；`mainClass`（可能是对象，取 `.client`）覆盖；有 `launchwrapper` 时取其 `tweakers.client[0]` 追加游戏参数 `--tweakClass <tweaker>`；额外追加 intermediary + loader 两个 maven 库（Fabric: `maven.fabricmc.net`；Quilt: `org.quiltmc` → `maven.quiltmc.org`，`net.fabricmc` → fabricmc maven；LegacyFabric: `meta.legacyfabric.net` 对应 maven）；③ 前置校验：原版 mainClass 必须为 vanilla（`net.minecraft.client.main.Main`），否则报「与现有配置不兼容」；目标版本已装加载器时阻断并提示先卸载（行为见待确认 Q5）；④ 落盘前把原版 JSON 备份为 `<id>.vanilla.json`；合并结果同时写 `version.json` 与 `<id>.json`（GameScanner / VersionJson 认这两个文件）；在 JSON 写自定义标记（如 `gblLoader: {type, version}`）供 UI 与启动链路识别；⑤ 卸载 = 从备份还原 JSON + 清标记；⑥ 库文件下载复用 `LibFetcher`（已支持按 libraries 条目下载） |
| 验收 | ① 给 1.20.1 装 Fabric 后：该版本 JSON 含加载器 libraries、mainClass 为 loader 客户端主类、`gblLoader` 标记正确；② 游戏列表点启动能进游戏，无「缺库 / 缺主类」报错；③ 卸载后 JSON 与备份逐字节一致，可再次安装；④ 对已装 Forge 的版本（未来 P1）安装 Fabric 被阻断并有明确提示；⑤ 加载器版本下拉默认选中最新稳定版 |

#### P0-3 Mod 列表与启停 / 卸载

| 项 | 内容 |
|---|---|
| 需求描述 | 新服务类 `ModStore`：扫描 `.minecraft/mods`（`Platform.minecraftDir().resolve("mods")`），含一级子目录（Forge/Quilt 等按蓝本第 3 节支持子目录）；识别扩展名 `jar` / `zip` / `litemod`；读 jar 内元数据（按优先级：`fabric.mod.json` / `quilt.mod.json` / `mcmod.info` / `META-INF/mods.toml` / `litemod.json`）取 mod id / 名称 / 适配加载器；启用 = 去掉 `.disabled` 后缀，禁用 = 追加 `.disabled`；卸载 = `Platform.recycle`（回收站，已有能力） |
| 详细规则 | ① 纯服务层不碰 UI，回调线程自行转 FX 线程（沿用现有约定）；② 元数据读不到时显示文件名，不报错不隐藏；③ `.disabled` 文件在列表中显示且标注「已禁用」；④ 卸载对启用/禁用文件均生效 |
| 验收 | ① 放入测试 jar 后列表出现且显示 jar 内 mod 名称；② 禁用后磁盘文件名为 `*.jar.disabled`，开关状态与磁盘一致；③ 卸载后文件进 Windows 回收站（非 Windows 进软回收站）；④ 切换游戏列表当前版本时 mod 列表跟随刷新 |

#### P0-4 Modrinth 搜索与安装

| 项 | 内容 |
|---|---|
| 需求描述 | 新服务类 `ModrinthClient`：`GET api.modrinth.com/v2/search`，facets 固定带 `project_type:mod` + `versions:<当前MC版本>`，当前版本已装加载器时追加 `categories:<loader>`；安装 = `GET /v2/project/<id>/version`（按 game_versions / loaders 过滤，取最新兼容版）→ `files[].url`（cdn.modrinth.com）下载到 mods 目录，验 sha1 |
| 详细规则 | ① 网络统一 `HttpClient` + 默认 ProxySelector（纯直连模式不变，与 `Net.java` 现状一致）；② 限速自律：Modrinth 300 req/min，搜索请求串行，不做预取翻页；③ 下载失败不留半成品（`.part` 落盘、失败删除，沿用 GameDownloader 约定）；④ 同名文件已存在时跳过并提示；⑤ 安装成功后写本地 mod 索引（见 P1-3 的前置：索引结构 0.7.0 就落，更新功能 P1 再做——存放位置见待确认 Q4） |
| 验收 | ① 搜「sodium」结果全部兼容当前 MC 版本与加载器，展示标题/简介/下载量/图标；② 安装后文件落 mods 目录且 sha1 与 Modrinth 声明一致；③ 校验失败有明确错误提示且 mods 目录无残留损坏文件；④ 无加载器的原版版本搜索时不带 categories 过滤且可正常安装 vanilla 兼容 mod |

#### P0-5 本地 Mod 文件导入

| 项 | 内容 |
|---|---|
| 需求描述 | Mod 页提供「导入本地文件」按钮，文件选择器（`.jar` / `.zip` / `.litemod`）→ 复制到 mods 目录；复制后刷新列表 |
| 验收 | ① 选中本地 jar 导入后出现在 mod 列表；② 目标已存在同名文件时提示并跳过；③ 复制失败（占用/权限）有提示且不崩溃 |

#### P0-6 `.mrpack` 整合包导入

机制对齐蓝本第 4 节。

| 项 | 内容 |
|---|---|
| 需求描述 | 新服务类 `ModpackImporter`：导入 `.mrpack`（zip）。流程：① 读 `modrinth.index.json`（自研 `Json.java` 可解析嵌套对象/数组；`fileSize` 为 Double 需 `longValue()` 转换）；② 展示确认卡：整合包名 / summary / MC 版本（`dependencies.minecraft`）/ 加载器及版本（`dependencies` 键 `fabric-loader`/`quilt-loader`/`forge`/`neoforge`）/ 文件数 / 总大小预估；③ 用户确认后：确保 MC 基础版本已安装（未装则复用 `GameDownloader` 下载）；④ 安装对应加载器（复用 P0-2；Forge/NeoForge 依赖项遇 P1 未实现时明确提示）；⑤ 解压 `overrides/` + `client-overrides/` 到 `.minecraft` 根；⑥ 按 `files[]` 逐条下载到各自 `path`（相对 `.minecraft`），校验 sha512（无 sha512 退 sha1）；`env.client == "optional"` 的默认跳过；⑦ 落 `modrinth.index.json` 到 `.minecraft` 根（供 P2 更新/导出） |
| 详细规则 | ① 导入过程可取消，取消后清理半成品（下载中的 `.part` 删除，不写索引）；② 覆盖已存在文件前保留旧文件（先备份再覆盖或直接覆盖——由架构定，PRD 要求不产生静默数据丢失）；③ 总大小 = 各 `fileSize` 之和；④ 并行下载 ≤ 4（与 LibFetcher PARALLEL 对齐） |
| 验收 | ① 用含 Fabric 依赖的公开 `.mrpack` 导入后：该 MC 版本带 Fabric 可启动；mods 目录文件齐全且 sha512 全部通过；overrides 内容（如配置/资源）落位；② 确认卡展示的文件数与总大小和 index 一致；③ 中途取消后再次导入可正常完成（无脏状态）；④ index 中 `env.client=="optional"` 文件被跳过且有日志/提示 |

#### P0-7 UI 集成（改造「游戏管理」占位页为「Mod 中心」）

信息架构草案见第 6 节。

| 项 | 内容 |
|---|---|
| 需求描述 | 侧边栏「游戏管理」页（现为占位页，`Page.GAMEMGMT`）改造为「Mod 中心」，页内三个 tab：**加载器 / Mod / 整合包**。UI 集成进 `ui\ShellView.java`，沿用现有页面骨架（ScrollPane + tab 容器 + toastBox 反馈 + 下载队列风格进度行） |
| 详细规则 | ① **兼容自测**：`nav-gamemgmt` 类名、`page-title` 类名、页面切换逻辑不许改（`Snapshot.java` 有点击 `nav-gamemgmt` 与读取 `page-title` 的既有用例）；② 页面文案可改（如标题改「Mod 中心」，见待确认 Q6）；③ 未选中游戏时显示空态引导（去游戏列表选择），不白屏 |
| 验收 | ① 现有 106 项自测全绿；② 三个 tab 可切换且各自空态/有数据态渲染正确；③ 加载器安装 / mod 安装 / 整合包导入均有可见进度与完成/失败反馈 |

#### P0-8 自测扩充与回归

| 项 | 内容 |
|---|---|
| 需求描述 | 在 `Snapshot.java` 新增 mod 相关自测项（Mod 中心页渲染、mods 空态、加载器卡片渲染、tab 切换），并保证既有 106 项不破 |
| 验收 | ① `python tools/run_selftest.py` 浅色 + 深色两趟全绿；② 用例总数 ≥ 106 + 新增项；③ 新增项覆盖 P0-2/P0-3/P0-6 的界面可达性与状态展示 |

### 5.2 P1 需求（后续迭代）

| ID | 需求 | 要点与验收（概要） |
|---|---|---|
| P1-1 | **Forge / NeoForge 安装器** | 蓝本 B 类：下载 installer jar → 读 `install_profile.json`：含 `install` + `versionInfo`（旧版）直接拼版本 JSON；含 `spec`（新版）实现 processors 运行时（下载 maven 产物、跑命令/脚本、改写客户端 jar、生成 libraries + versionInfo，对标 HMCL `ForgeNewInstallTask` ≈ 20KB，单项最大块）。NeoForge 复用，靠 `profile` 字段 / `META-INF/NEOFORGE.RSA` 区分。版本校验 `profile.minecraft` 必须等于目标 MC 版本。**Java 来源见待确认 Q7**。验收：装完 Forge 的版本可直接启动；安装失败有明确阶段提示 |
| P1-2 | **CurseForge / MultiMC 整合包导入** | `instance.cfg` + `minecraftinstance.json` + `patches/` 另一套格式。验收：常见 CF 导出 zip 导入后可启动 |
| P1-3 | **Mod 更新检测与更新** | 基于 P0-4 落下的本地索引（projectId/versionId/filename）调 `/v2/project/<id>/version` 比对，列表显示「有新版本」，一键更新（下载新版 → 删旧文件 → 更新索引）。验收：旧版本显示更新标识；更新后文件与索引均为新版 |
| P1-4 | **BMCLAPI 国内下载镜像** | 独立叠加：`SettingsStore` 新增下载源字段（官方 / BMCLAPI，默认官方）；新增 `Mirror` URL 重写helper，作用于现有下载链——`VersionManifest` 清单、`GameDownloader` 客户端 jar、`LibFetcher` libraries、assets（规则：`launchermeta.mojang.com` / `libraries.minecraft.net` / `piston-meta.mojang.com` / `resources.download.minecraft.net` → `bmclapi2.bangbang93.com` 对应路径）；镜像失败自动回落官方源重试一次。**不覆盖** loader meta API 与 Modrinth（蓝本第 5 节：这些国内多数可直连）。验收：切到 BMCLAPI 后日志中 Mojang 域名请求实际命中镜像域；切回恢复直连；镜像挂起时回落且不阻断下载。是否提前到 P0 见待确认 Q3 |

### 5.3 P2 需求

| ID | 需求 | 要点 |
|---|---|---|
| P2-1 | LiteLoader / OptiFine / Cleanroom 安装 | 蓝本 C 类：单库 + `--tweakClass`；OptiFine 需改写客户端 jar |
| P2-2 | 整合包导出 | 反向产出 `.mrpack`（index + overrides） |
| P2-3 | 整合包更新 | 重导入时按 sha1 判断用户是否改过文件，改过不覆盖 |
| P2-4 | 官网 features 页补「Mod 三件套」介绍 | 发布物料，随版本发布 |

---

## 6. UI 草案（信息架构，文字描述）

### 6.1 整体结构

```
侧边栏「游戏管理」（入口文案改「Mod 中心」，nav-gamemgmt 类名不变）
└─ Mod 中心页
   ├─ 顶部：页面标题 + 当前游戏选择器（默认 SettingsStore.currentGame；未选 → 空态引导）
   ├─ 加载器状态条：当前版本已装加载器（如「Fabric 0.15.7」）或「原版 · 未安装加载器」
   └─ 三个 Tab：
      ├─ Tab1「加载器」：Fabric / Quilt / LegacyFabric 三张卡片
      │    每卡：加载器版本下拉（默认最新稳定）+「安装 / 卸载」按钮 + 已装状态徽标
      ├─ Tab2「Mod」：
      │    ├─ 搜索框（Modrinth，自动带 MC 版本 + 加载器过滤）+「导入本地文件」按钮
      │    ├─ 搜索结果列表：图标 / 标题 / 简介 / 下载量 / 「安装」按钮
      │    └─ 已装 Mod 列表：名称 / mod id / 适配加载器 / 启用开关 / 更新标识(P1) / 「卸载」
      └─ Tab3「整合包」：
           ├─ 「导入整合包」按钮（文件选择器 .zip / .mrpack）
           ├─ 导入确认卡：名称 / MC 版本 / 加载器及版本 / 文件数 / 总大小
           └─ 导入进度区：下载 n/N（文件级进度条）+ 解压阶段 + 完成 / 失败反馈
```

### 6.2 关键界面行为

| 界面 | 行为 |
|---|---|
| 当前游戏选择器 | 切换后整页数据（加载器状态 / mod 列表）刷新；来源 = `GameScanner.scan()` 已装版本 |
| 加载器卡片 | 安装前校验不过时按钮置灰并附原因（非 vanilla 主类 / 已装其他加载器）；安装中显示进度，完成刷新状态条 |
| Mod 搜索 | 输入防抖后串行请求；结果点击「安装」后该行进「安装中」，完成移入已装列表 |
| 已装 Mod 开关 | 即时改名 + 列表状态刷新；卸载走确认（同游戏列表删除的二次确认风格） |
| 整合包导入 | 全流程一个后台任务 + 一条总进度；失败可重试；完成后 toast + 游戏列表刷新 |
| 空态 | 无 mods 目录 / 无 mod 时显示引导文案（「还没有 mod，去搜索或导入整合包」） |

### 6.3 反馈与文案口径

- 进度 / 状态提示沿用 `toastBox`（v0.4.1 机制）与下载队列进度行样式，不造第二套反馈组件。
- 所有错误提示说人话且带下一步动作（如「meta 接口暂时连不上，稍后重试或检查网络」），不甩异常堆栈。

---

## 7. 技术约束与实现要点（供架构阶段消歧）

| # | 要点 |
|---|---|
| T1 | **增量最小变更**：不改账号 / 下载 / 启动 / 皮肤 / 游戏列表现有逻辑；新服务类全部放 `com.mclauncher` 包（建议：`LoaderInstaller`、`ModStore`、`ModrinthClient`、`ModpackImporter`、`Mirror`），UI 集成进 `ui\ShellView.java` |
| T2 | **加载器集成点 = 版本 JSON**：GBL 无 HMCL 的 patch 系统，等效做法是把加载器 libraries / mainClass / args 合并进目标版本 JSON 落盘；`LibFetcher`（下载）与 `GameLauncher`（拼 classpath）已按 version json 的 libraries 工作，无需改动 |
| T3 | **完整版 JSON 出口**：现有本地 `<id>.json` 是精简版（无 libraries/assetIndex），加载器安装必须基于完整版合并。`VersionJson` 现有 `load()/cached()` 返回解析后 record，**需补一个「完整原始 JSON Map」出口**（本地→缓存→网络三级补齐逻辑已有，复用）——列为架构阶段必办项 |
| T4 | **JSON 能力**：`Json.java` 已支持嵌套对象/数组；mrpack 的 `fileSize` 等整数按 Double 解析，需 `longValue()` 转换；sha512/sha1 校验用 JDK `MessageDigest`；读 jar 元数据用 `java.util.zip`。**不引第三方库** |
| T5 | **网络**：统一 `HttpClient` + 默认 `ProxySelector`（`Net.java` 纯直连模式不变）；对外 UA 统一带 GBL 标识（复用 `GameDownloader.AGENT` 机制） |
| T6 | **目录约定**：mods 目录 = `Platform.minecraftDir().resolve("mods")`（便携模式下即 `GBL/.minecraft/mods`）；mrpack 的 files path 与 overrides 均相对 `.minecraft` 根解析 |
| T7 | **mod 本地索引**：安装 Modrinth mod 时记录 projectId / versionId / filename（供 P1 更新检测）；存放位置见待确认 Q4，**不写进 `.minecraft`**（保证便携 zip 干净） |
| T8 | **自测兼容**：`nav-gamemgmt` / `page-title` / `page-blank-title` 等既有类名与 `Snapshot.java` 既有点击路径不许破坏 |
| T9 | **实现前补抓**：各加载器 `*RemoteVersion.java`（版本枚举 API 的准确 URL 与解析）、Forge processors 细节（`ForgeNewInstallTask.java` + `ForgeNewInstallProfile.java`），HMCL 源码在 `C:\Temp\hmcl\` |

---

## 8. 风险与对策

| # | 风险 | 影响 | 对策 |
|---|---|---|---|
| R1 | loader meta API（meta.fabricmc.net 等）国内可达性或结构变化 | 加载器装不上 | 实现前补抓 `*RemoteVersion.java` 复核；失败重试 + 明确报错；BMCLAPI（P1-4）不覆盖这些域，直连为主 |
| R2 | `VersionJson` 无完整原始 JSON 出口 | P0-2 无法基于完整版合并 | T3 已列为架构必办；若阻塞，降级方案为安装时现拉一次官方 version.json |
| R3 | Modrinth 限速 300 req/min | 搜索/安装被限流 | 搜索串行 + 防抖；安装走 CDN 直链；失败指数退避重试 |
| R4 | `.mrpack` files 下载中断 / 校验失败 | 整合包半成品 | `.part` 落盘 + 失败删除；整包可重试；取消时清理（P0-6 验收③） |
| R5 | 老版本 MC（无加载器支持的版本） | 安装入口误用 | 入口按版本禁用 + 提示；mainClass 非 vanilla 阻断（P0-2 规则③） |
| R6 | 自测回归（页面改造） | 既有 106 项挂彩 | T8 类名不动；P0-8 新增用例；两趟主题全跑 |
| R7 | Forge processors（P1）需在进程内跑 Java 命令、可能改写客户端 jar | P1 延期风险 | P1 独立迭代；提前定 Java 来源（Q7）；旧版 Forge（versionInfo 直拼）可先行落地降低风险 |
| R8 | 整合包 overrides 覆盖用户已有文件 | 数据丢失 | 覆盖前备份 / 明确提示（P0-6 规则②），不静默覆盖 |
| R9 | 版本号两处硬编码（VERSION 与 AGENT） | 发布口径不一致 | P0-1 统一为引用 VERSION |

---

## 9. 待确认问题（需 team-lead / 用户拍板）

| # | 问题 | 建议（PM 倾向） |
|---|---|---|
| Q1 | **加载器安装形态**：就地升级目标版本 JSON（备份 `<id>.vanilla.json`，写回合并版 + `gblLoader` 标记，卸载可还原）vs 新建独立版本目录（如 `1.20.1-fabric`，游戏列表多一项） | **就地升级**：最小变更、不污染游戏列表、复用 LibFetcher/GameLauncher 零改动；与蓝本「组件补丁」等效 |
| Q2 | **P0 是否包含「Mod 更新检测与更新」**？蓝本 P0 未含（原需求提过） | 按蓝本放 **P1**；P0 先把索引结构落下（T7），P1 只做功能层。如用户坚持可提升到 P0 |
| Q3 | **BMCLAPI 镜像放 P0 还是 P1**？目标用户全在国内，官方域名直连不稳 | 建议 **P1-4 按原计划**，但若 0.7.0 发布前希望「国内下载全程顺畅」，可把 Mojang 域名镜像部分提入 P0（工作量约 1 个设置项 + 1 个重写 helper + 4 处接入点）。请拍板 |
| Q4 | **mod 本地索引存放位置**：`~/.gbl/cache/mods/<versionId>/index.json`（记录 projectId/versionId/filename） | 推荐此方案：不写进 `.minecraft`，便携 zip 保持干净；`<versionId>` 维度与游戏列表 currentGame 对齐 |
| Q5 | **已装加载器的版本再装另一个**：P0 行为 = 阻断并提示先卸载 vs 直接替换 | **阻断 + 提示先卸载**（替换的语义复杂，卸载已可还原，不值得在 P0 做） |
| Q6 | **入口与页面命名**：「游戏管理」入口文案是否改为「Mod 中心」？页面标题同步改 | 推荐改为「Mod 中心」（功能已是 mod 三件套；`nav-gamemgmt` 类名不动以保自测） |
| Q7 | **Forge/NeoForge（P1）processors 的 Java 来源**：GBL 自带 JRE（jpackage 镜像）还是系统 `java` | 建议**自带 JRE**（便携定位决定，不依赖用户环境）；此问题影响 P1 设计，请提前定 |

---

## 10. 交付与验收总口

- 代码：新服务类 + `ShellView.java` 增量集成；版本号 0.7.0
- 文档：本 PRD + `docs/HMCL-MOD-REFERENCE.md`（蓝本，不动）
- 质量口：`tools/run_selftest.py` 两趟全绿，总数 ≥ 106 + 新增；P0 各条验收标准逐条过
- 发布口：Windows 便携 zip（本机唯一可出包平台）；官网文案属 P2-4

---

## 11. 决策记录（team-lead 汇总，用户拍板，2026-10-13）

| # | 问题 | 决策 |
|---|---|---|
| Q1 | 加载器安装形态 | **就地升级目标版本 JSON**：备份 `<id>.vanilla.json`，合并写回 + `gblLoader` 标记，卸载从备份还原；不新建独立版本目录 |
| Q2 | Mod 更新检测 | **留 P1**；P0 仅落地 mod 本地索引结构（T7） |
| Q3 | BMCLAPI | **Mojang 域名镜像部分提入 P0**（由 P1-4 拆分而来）：P0 含 `SettingsStore` 下载源字段 + `Mirror` 重写 helper + 4 接入点（`VersionManifest` / `GameDownloader` 客户端 jar / `LibFetcher` libraries / `AssetFetcher` assets）+ 失败回落官方源；Modrinth 与加载器 meta 仍直连（留 P1） |
| Q4 | mod 索引位置 | `~/.gbl/cache/mods/<versionId>/index.json`，不写进 `.minecraft`（便携 zip 保持干净） |
| Q5 | 已装加载器再装另一个 | 阻断 + 提示先卸载 |
| Q6 | 入口/页面命名 | 「游戏管理」→「Mod 中心」（`nav-gamemgmt` 类名不动以保自测） |
| Q7 | Forge processors Java 来源 | 自带 JRE（P1 设计前提） |

> **P0 现为 9 项**：原 8 项 + BMCLAPI Mojang 域名镜像。架构阶段按此范围设计。
