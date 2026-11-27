# 设计文档

GBL 自己的架构、需求和界面规格文档。文件名里的数字是版本号，越新的版本功能越全，看新版就够，老版留着看演进过程。

文档里的类名、字段名和 `src/main/java` 下的真实代码一一对应，改代码时顺手把文档同步掉。

## 架构与需求

- [architecture-1.1.5.md](architecture-1.1.5.md) —— 1.1.5 整体架构：界面引擎、状态流转、模块划分
- [ARCH-0.7.0-mod.md](ARCH-0.7.0-mod.md) —— 0.7.0 增量架构：Mod 三件套（Fabric / Quilt / Legacy Fabric）
- [ARCH-0.8-forge-neoforge.md](ARCH-0.8-forge-neoforge.md) —— 0.8 增量架构：新增 Forge 与 NeoForge 安装
- [ARCH-0.8.7-content.md](ARCH-0.8.7-content.md) —— 0.8.7 增量架构：模组 / 整合包 / 资源包体系
- [ARCH-0.8.8-content.md](ARCH-0.8.8-content.md) —— 0.8.8 增量架构：内容详情、前置依赖、加载器版本
- [PRD-0.7.0-mod.md](PRD-0.7.0-mod.md) —— 0.7.0 需求：Mod 功能范围
- [PRD-0.8.4-curseforge.md](PRD-0.8.4-curseforge.md) —— 0.8.4 需求：CurseForge 数据源
- [PRD-0.8.7-content.md](PRD-0.8.7-content.md) —— 0.8.7 需求：模组与整合包管理
- [PRD-0.8.8-content.md](PRD-0.8.8-content.md) —— 0.8.8 需求：装完看得见、前置可选、详情对齐
- [prd-1.1.5.md](prd-1.1.5.md) —— 1.1.5 需求：界面统一与联机页收敛
- [GBL_121_TASKS.json](GBL_121_TASKS.json) —— 1.2.1 任务拆分（给开发和 QA 用的清单）
- [GBL_121_UI_ENGINE_SPEC.md](GBL_121_UI_ENGINE_SPEC.md) —— 1.2.1 界面引擎规格：渲染、动效、控件
- [GBL_127_OFFLINE_SPEC.md](GBL_127_OFFLINE_SPEC.md) —— 1.2.7 离线模式规格
- [GBL_STYLE_UI_SPEC_12.md](GBL_STYLE_UI_SPEC_12.md) —— 界面样式规范：间距、字号、配色 token

## 图

- [class-diagram-0.8.7.mermaid](class-diagram-0.8.7.mermaid) —— 0.8.7 类图
- [class-diagram-1.1.5.mermaid](class-diagram-1.1.5.mermaid) —— 1.1.5 类图
- [class-diagram.mermaid](class-diagram.mermaid) —— 早期类图
- [sequence-diagram-0.8.7.mermaid](sequence-diagram-0.8.7.mermaid) —— 0.8.7 关键流程时序图
- [sequence-diagram-1.1.5.mermaid](sequence-diagram-1.1.5.mermaid) —— 1.1.5 关键流程时序图
- [sequence-diagram.mermaid](sequence-diagram.mermaid) —— 早期时序图

上面这些 `.mermaid` 是 Mermaid 源码，GitHub、Gitee、VS Code 都能直接渲染。
