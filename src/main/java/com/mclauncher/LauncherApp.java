package com.mclauncher;

import com.mclauncher.ui.ShellView;
import javafx.application.Application;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 启动器入口。目前只负责装配窗口外壳，业务逻辑后续接入服务层。 */
public class LauncherApp extends Application {

    /** 版本号。界面状态栏与打包脚本的 APP_VERSION 都以此为准，改版本只改这一处。 */
    public static final String VERSION = "1.3.3";

    /** 构建批次号。用于自动更新判断新旧，用户无需关心。 */
    public static final String BUILD = "1.3.3";

    /** 界面展示版本号：带 v 前缀的完整版本号（如 v1.0.1），窗口标题与状态栏都用它。 */
    public static final String DISPLAY_VERSION = "v" + VERSION;

    /** 0.9.5 r1 指纹：构建门禁扫 runtime/lib/modules 常量池，必须是当版独有的字符串常量，勿改这串字。 */
    public static final String FP_095_R1 = "0.9.5 r1 自动更新";
    static { System.err.println("[app] " + FP_095_R1); }

    /** 0.9.6 r1 指纹：构建门禁扫 runtime/lib/modules 常量池，必须是当版独有的字符串常量，勿改这串字。 */
    public static final String FP_096_R1 = "0.9.6 r1 自动更新";
    static { System.err.println("[app] " + FP_096_R1); }

    /** 0.9.6 r2：自动更新改为「原地覆盖安装 + 重启」，不再新建时间戳目录。 */
    public static final String FP_096_R2 = "0.9.6 r2 覆盖安装";
    static { System.err.println("[app] " + FP_096_R2); }

    /** 0.9.6 r3：内容页工具条统一 + 按钮放大 + logo 加回边框 + 开机自启写真实 Run 键。 */
    public static final String FP_096_R3 = "0.9.6 r3 工具条统一";
    static { System.err.println("[app] " + FP_096_R3); }

    /** 0.9.6 r4：启动页按钮等比回归并右移 + logo 高对比白环 + 皮肤 UV 镜像 + 内容页单例去重 + 模组首屏补刷。 */
    public static final String FP_096_R4 = "0.9.6 r4 五项修复";
    static { System.err.println("[app] " + FP_096_R4); }

    /** 0.9.6 r5：logo 原样回归 0.9.5 原件 + 删除启动页横线 + 按钮按 0.9.5 原件尺寸恢复。勿改这串字。 */
    public static final String FP_096_R5 = "0.9.6 r5 启动页与logo回归0.9.5";
    static { System.err.println("[app] " + FP_096_R5); }

    /** 0.9.6 r6 指纹：开机自启正常显示主窗口 + Run 值自愈。构建门禁扫常量池，勿改这串字。 */
    public static final String FP_096_R6 = "0.9.6 r6 自启正常显示窗口";
    static { System.err.println("[app] " + FP_096_R6); }

    /** 0.9.7 r1 指纹：下拉框回归 0.9.5 原件 + 任务栏与托盘互斥。构建门禁扫常量池，勿改这串字。 */
    public static final String FP_097_R1 = "0.9.7 r1 下拉框回归与托盘任务栏互斥";
    static { System.err.println("[app] " + FP_097_R1); }

    /** 0.9.8 r1 指纹：自动更新修复 + 皮肤 2D 平涂 + 按钮反馈 + 删除后台化。勿改这串字。 */
    public static final String FP_098_R1 = "0.9.8 r1 自动更新修复与皮肤平涂";
    static { System.err.println("[app] " + FP_098_R1); }

    /** 0.9.8 r2 指纹：HMCL 3D 皮肤预览 + 托盘实例复用 + 同批次自动更新。勿改这串字。 */
    public static final String FP_098_R2 = "0.9.8 r2 HMCL皮肤与托盘复用";
    static { System.err.println("[app] " + FP_098_R2); }

    /** 0.9.8 r3 主指纹：构建门禁扫 runtime/lib/modules 常量池，零引用也留在常量池，勿改这串字。 */
    public static final String FP_098_R3 = "0.9.8 r3 托盘常驻与HMCL拖拽皮肤";
    static { System.err.println("[app] " + FP_098_R3); }

    /** 0.9.8 r4 主指纹：皮肤拖拽完全照抄 HMCL（kx/kz 权重 + Z 轴翻滚 + 修饰键单轴）。勿改这串字。 */
    public static final String FP_098_R4 = "0.9.8 r4 全抄HMCL拖拽三轴皮肤";
    static { System.err.println("[app] " + FP_098_R4); }

    /** 0.9.8 r5 主指纹：皮肤几何 / UV / 外层壳整套移植 HMCL。勿改这串字。 */
    public static final String FP_098_R5 = "0.9.8 r5 皮肤几何UV与外壳移植HMCL";
    static { System.err.println("[app] " + FP_098_R5); }

    /** 0.9.8 r6 主指纹：单实例文件锁 + 本地唤醒，托盘图标不再累积。勿改这串字。 */
    public static final String FP_098_R6 = "0.9.8 r6 单实例托盘图标不累积";
    static { System.err.println("[app] " + FP_098_R6); }

    /** 0.9.9 r1 主版本指纹：构建门禁扫 runtime/lib/modules 常量池，零引用也留在常量池，勿改这串字。 */
    public static final String FP_099_R1 = "0.9.9 r1 皮肤坏图不崩溃与光影启动";
    static { System.err.println("[app] " + FP_099_R1); }

    /** 0.9.9 r1 本批修复指纹：皮肤坏图不崩溃 / OptiFine 光影可启动 / 老版本 Java 选对 / 浅色下拉与键盘焦点。勿改这串字。 */
    public static final String FP_099_R1_FIX = "0.9.9 r1 皮肤坏图光影Java选对与浅色下拉焦点";
    static { System.err.println("[app] " + FP_099_R1_FIX); }

    /** 0.9.9 r1 更新链路指纹：点取消真正中断下载与安装、全局安装互斥。勿改这串字。 */
    public static final String FP_099_R1_UPDATE = "0.9.9 r1 更新取消真生效与安装互斥";
    static { System.err.println("[app] " + FP_099_R1_UPDATE); }

    /** 0.9.9 r2 主版本指纹：同版本重发，只升 BUILD，更新下载重试与失败提示。勿改这串字。 */
    public static final String FP_099_R2 = "0.9.9 r2 更新下载重试与失败提示";
    static { System.err.println("[app] " + FP_099_R2); }

    /** 0.9.9 r3 主版本指纹：装完新游戏后新手引导消失。构建门禁扫 class 常量池核验，勿改这串字。 */
    public static final String FP_099_R3 = "0.9.9 r3 装完游戏新手引导消失";
    static { System.err.println("[app] " + FP_099_R3); }

    /**
     * 0.9.9 r4 主版本指纹：修「别人一启动就报 53 个依赖库全没下来 / 下载游戏失败 / 更新不成功」。
     *
     * <p>三件事同源：国内镜像 BMCLAPI 现在对每个文件都回 302 跳去签名直链，而竞速那条
     * 连接是 Redirect.NEVER，302 被判成「这一路失败」，镜像整条路 100% 死；官方
     * libraries.minecraft.net 在国内多半连不上，于是两路全灭，要补的库一个都下不来。
     * 本版让竞速的两路各自跟随重定向，四个下载链路（依赖库 / 资源 / 游戏本体 / 内容文件）
     * 一起恢复；顺带把更新链路的真实失败原因送到界面上，并摘掉整窗阴影与结果网格位图缓存。
     * 构建门禁扫 class 常量池核验，勿改这串字。
     */
    public static final String FP_099_R4 = "0.9.9 r4 镜像302跟随与更新失败原因";
    static { System.err.println("[app] " + FP_099_R4); }

    /** 0.9.9 r5：取消首启自动建离线账号，账号改由用户在「账号」页自行添加。 */
    public static final String FP_099_R5_NO_AUTO_ACCOUNT = "0.9.9 r5 取消首启自动建离线账号";
    static { System.err.println("[app] " + FP_099_R5_NO_AUTO_ACCOUNT); }

    /** 0.9.9 r5：更新生效判定与弹窗去重修复。构建门禁扫常量池，勿改这串字。 */
    public static final String FP_099_R5_UPDATE_STAMP = "0.9.9 r5 更新生效判定与弹窗去重";
    static { System.err.println("[app] " + FP_099_R5_UPDATE_STAMP); }

    /** 0.9.9 r6：游戏启动流畅优化。构建门禁扫常量池，勿改这串字。 */
    public static final String FP_099_R6_LAUNCH = "0.9.9 r6 游戏启动流畅优化";
    static { System.err.println("[app] " + FP_099_R6_LAUNCH); }

    /** 0.9.9 r6：全链路无 bug 确认。构建门禁扫常量池，勿改这串字。 */
    public static final String FP_099_R6_STABLE = "0.9.9 r6 全链路无bug确认";
    static { System.err.println("[app] " + FP_099_R6_STABLE); }

    /** 0.9.9 r11 指纹：更新改两阶段暂存，旧进程不再覆盖被 JVM 锁住的自身文件。勿改这串字。 */
    public static final String FP_099_R11_STAGED_UPDATE = "0.9.9 r11 两阶段更新不再锁死自身文件";
    static { System.err.println("[app] " + FP_099_R11_STAGED_UPDATE); }

    /** r11 滚动条指纹：长列表滚动条恢复可抓握尺寸。勿改这串字。 */
    public static final String FP_099_R11_SCROLLBAR = "0.9.9 r11 长列表滚动条可抓握尺寸";
    static { System.err.println("[app] " + FP_099_R11_SCROLLBAR); }

    /** r12 指纹：详情弹窗滚动条统一加粗（全局 .scroll-bar 规则、浅深一致）。勿改这串字。 */
    public static final String FP_099_R12_SCROLLBAR_ALL = "0.9.9 r12 详情弹窗滚动条统一加粗";
    static { System.err.println("[app] " + FP_099_R12_SCROLLBAR_ALL); }

    /** 0.9.9 r13 主指纹：浮动滚动条 skin（长列表 thumb 不再缩成小点）+ 下载队列重设计。勿改这串字。 */
    public static final String FP_099_R13 = "0.9.9 r13 浮动滚动条与下载队列重设计";
    static { System.err.println("[app] " + FP_099_R13); }

    /** GBL 1.0 正式版指纹：全功能稳定版，含浮动滚动条、下载队列重设计、更新暂存清理。勿改这串字。 */
    public static final String FP_1_0 = "GBL 1.0 正式版";
    static { System.err.println("[app] " + FP_1_0); }

    /** GBL 1.0.1 指纹：修复所有滚动条滑块越界（CSS 按方向设 min + skin 绝对定位）。勿改这串字。 */
    public static final String FP_1_0_1 = "GBL 1.0.1 滚动条越界修复";
    static { System.err.println("[app] " + FP_1_0_1); }

    /** 1.0.2 指纹：修复语义版本被误判补丁号导致自动更新失效。勿改这串字。 */
    public static final String FP_1_0_2 = "GBL 1.0.2 自动更新判断修复";
    static { System.err.println("[app] " + FP_1_0_2); }

    /** 1.0.3 指纹：自绘滚动条皮肤，拖动与定位共用原生同一套公式，不再乱跳。勿改这串字。 */
    public static final String FP_1_0_3 = "GBL 1.0.3 滚动条拖动跟手修复";
    static { System.err.println("[app] " + FP_1_0_3); }

    /** 1.0.4 指纹：隐藏水平滚动条不再绘制灰色横线（自绘皮肤尊重 visible/managed/parent 可见性）。勿改这串字。 */
    public static final String FP_1_0_4 = "GBL 1.0.4 隐藏滚动条横线修复";
    static { System.err.println("[app] " + FP_1_0_4); }

    /** 1.0.5 指纹：单 thumb 无原生残留 + 点击涟漪覆盖全场景 / 关闭即取消。勿改这串字。 */
    public static final String FP_1_0_5 = "GBL 1.0.5 单thumb与涟漪全场景";
    static { System.err.println("[app] " + FP_1_0_5); }

    /** 1.0.6 指纹：涟漪坐标修复（localToParent）+ 开弹窗前清涟漪 + 低配机流畅运行。勿改这串字。 */
    public static final String FP_1_0_6 = "GBL 1.0.6 涟漪坐标与卡顿修复";
    static { System.err.println("[app] " + FP_1_0_6); }

    /** 1.0.7 指纹：按钮特效开关（设置页可关，默认开）。勿改这串字。 */
    public static final String FP_1_0_7 = "GBL 1.0.7 按钮特效开关";
    static { System.err.println("[app] " + FP_1_0_7); }

    /** 1.0.8 指纹：整合包体验修复与导航图标优化。勿改这串字。 */
    public static final String FP_1_0_8 = "GBL 1.0.8 整合包体验与涟漪图标优化";
    static { System.err.println("[app] " + FP_1_0_8); }

    /** 1.0.9 指纹：启动卡死修复、流畅度优化与导航图标描边。勿改这串字。 */
    public static final String FP_1_0_9 = "GBL 1.0.9 启动卡死修复与流畅度图标优化";
    static { System.err.println("[app] " + FP_1_0_9); }

    /** 1.1 指纹：图标照抄参考图与开关 HMCL 平滑滑动动画。勿改这串字。 */
    public static final String FP_1_1 = "GBL 1.1 图标照抄与开关滑动动画";
    static { System.err.println("[app] " + FP_1_1); }

    /** 1.1.1 指纹：启动异常路径 FX 线程违规与看门狗兜底修复。勿改这串字。 */
    public static final String FP_1_1_1 = "GBL 1.1.1 启动校验卡顿修复";
    static { System.err.println("[app] " + FP_1_1_1); }

    /** 1.1.2 指纹：Java 精确匹配自动下载、整合包启停与三导航图标重画。勿改这串字。 */
    public static final String FP_1_1_2 = "GBL 1.1.2 Java匹配下载与整合包启停";
    static { System.err.println("[app] " + FP_1_1_2); }

    /** 1.1.3 指纹：Java 提速、整合包独立与光影封面。勿改这串字。 */
    public static final String FP_1_1_3 = "GBL 1.1.3 Java提速整合包独立与光影封面";
    static { System.err.println("[app] " + FP_1_1_3); }

    /** 1.1.4 指纹：多人联机（HMCL 陶瓦式房间、邀请码、L2 局域网发现）。勿改这串字。 */
    public static final String FP_1_1_4 = "GBL 1.1.4 多人联机陶瓦式房间";
    static { System.err.println("[app] " + FP_1_1_4); }

    /** 1.1.5 指纹：界面收敛与流畅度修复。勿改这串字。 */
    public static final String FP_1_1_5 = "GBL 1.1.5 界面收敛与流畅度修复";
    static { System.err.println("[app] " + FP_1_1_5); }

    /** 1.1.6 指纹：多人联机网络受限文案优化（CGNAT 场景给可操作建议而非致命错误）。勿改这串字。 */
    public static final String FP_1_1_6 = "GBL 1.1.6 多人联机文案优化";
    static { System.err.println("[app] " + FP_1_1_6); }
    /** 1.1.7 指纹：多人联机页 UI 重写（抄 HMCL 风格）+ exceptionType=3 卡死修复。勿改这串字。 */
    public static final String FP_1_1_7 = "GBL 1.1.7 多人联机页 UI 重写与卡顿修复";
    static { System.err.println("[app] " + FP_1_1_7); }
    /** 1.1.8 指纹：多人联机页按 HMCL ComponentList 骨架重写（分组卡/两行体检/结构化身份）。勿改这串字。 */
    public static final String FP_1_1_8 = "GBL 1.1.8 多人联机页 HMCL 风格重写";
    static { System.err.println("[app] " + FP_1_1_8); }
    /**
     * 1.1.9 指纹：多人联机页按 Terracotta 陶瓦联机窗口重写（紫色侧栏 / 毛玻璃大厅卡 /
     * 房主客人两大宽行），并修复 CGNAT、中继、游戏内已开局域网仍误报「异常」的问题。勿改这串字。
     */
    public static final String FP_1_1_9 = "GBL 1.1.9 多人联机页 Terracotta 风格重写与局域网误报修复";
    static { System.err.println("[app] " + FP_1_1_9); }
    /** 1.2 指纹：联机页回归 GBL 风格 + 引擎失联误报修复。勿改这串字。 */
    public static final String FP_1_2 = "GBL 1.2 联机页回归 GBL 风格与引擎失联误报修复";
    static { System.err.println("[app] " + FP_1_2); }
    /**
     * 1.2.1 指纹：联机大厅卡三段紧凑化（空闲/忙/完成互斥切换）+ 八个动作按钮全部接上
     * setOnAction（修复「取消」按钮点了没反应）+ 取消走 resetIdleAsync + 邀请弹窗取代
     * 内联拼房输入 + 内置 Terracotta 引擎二进制（三级解析：内置 → ~/.gbl → 联网兜底）。勿改这串字。
     */
    public static final String FP_1_2_1 = "GBL 1.2.1 联机页紧凑化·取消可用·引擎内置";
    static { System.err.println("[app] " + FP_1_2_1); }
    /**
     * 1.2.2 指纹：恢复「我想当房主 / 我想当客人」两大宽行且常驻可见；删除 license 脚注；
     * 客人入口直接弹邀请码模态（不检查游戏）；两个模态紧凑居中；版本升 1.2.2。勿改这串字。
     */
    public static final String FP_1_2_2 = "GBL 1.2.2 恢复我想当房主客人宽行·删license·修二次启动·模态紧凑";
    static { System.err.println("[app] " + FP_1_2_2); }
    /**
     * 1.2.3 指纹：联机页重设计为「卡顶常驻就绪条 + 两个大宽行」，整行删除玩家身份行，
     * 浅色 / 深色两套主题全部做对，弹窗红字文案对齐参考视频（必填项 / 邀请码错误），
     * 不重新引入任何版权授权说明段落。勿改这串字。
     */
    public static final String FP_1_2_3 = "GBL 1.2.3 联机页重设计·删玩家行·就绪条·双主题弹窗";
    static { System.err.println("[app] " + FP_1_2_3); }
    /**
     * 1.2.4 指纹：短提示统一回窗口左下角；半透明遮罩只盖操作区（顶栏和左侧导航仍可操作）；
     * 房间成功态显示 HMCL 风格玩家列表；关闭/退出房间真正生效；扫描节点提速；涟漪切页清。勿改这串字。
     */
    public static final String FP_1_2_4 = "GBL 1.2.4 提示左下·遮罩仅操作区·玩家列表·关房真生效·扫描提速·涟漪切页清";
    /**
     * 1.2.5 指纹：短提示回右下；涟漪只在联机页内部弹层取消；联机弹窗 GBL 深灰；
     * 紧凑弹窗 + 遮罩铺满操作区 + Forge/NeoForge 跨机下载修复 + 弹层跨页隐藏恢复。
     */
    public static final String FP_1_2_5 =
            "GBL 1.2.5 提示右下·涟漪仅联机内部·深色GBL配色·弹窗紧凑·遮罩铺满·Forge下载修复·弹层跨页恢复";
    /**
     * 1.2.6 指纹：弹窗高度由内容撑开（游戏检查/邀请码不再留大片空白）；
     * 扫描忙态改横向紧凑卡（不再竖向拉满）；弹层跨主菜单恢复换全局登记表（废世代计数）；
     * UI 结构对齐 HMCL、配色仍 GBL。勿改这串字。
     */
    public static final String FP_1_2_6 =
            "GBL 1.2.6 弹窗内容自适应·忙态横向紧凑·弹层全局登记表跨页恢复·HMCL结构GBL配色";
    static { System.err.println("[app] " + FP_1_2_4);
        System.err.println("[app] " + FP_1_2_5);
        System.err.println("[app] " + FP_1_2_6); }

    /**
     * 1.2.7 指纹：启动自动检查更新失败改为右下角非阻断短提示，绝不再弹「这次没更新成功」
     * 模态框；无网络时 GBL 本体功能完全可用，仅联网功能不可用；检查失败静默降级。
     * R126 及更早指纹一条不删，本版独立新增。勿改这串字。
     */
    public static final String FP_1_2_7 =
            "GBL 1.2.7 更新失败不弹框·离线可用·检查静默降级·联网失败不阻断";
    static { System.err.println("[app] " + FP_1_2_7); }

    /**
     * 1.2.8 指纹：开关滑动动画播完再重建；版本行直接显示前置摘要；
     * 自导入皮肤可选宽臂 / 细臂并回写持久化。1.2.7 及更早指纹一条不删。勿改这串字。
     */
    public static final String FP_1_2_8 =
            "GBL 1.2.8 滑动可见·版本前置摘要·自定义皮肤臂宽";
    static { System.err.println("[app] " + FP_1_2_8); }

    /**
     * 1.2.9 指纹：HMCL 式下载确认框，点版本先确认，框内按分区逐条列前置模组；
     * 前置区限高滚动，1 条与 70+ 条都不会拉长列表。历史指纹一条不删。勿改这串字。
     */
    public static final String FP_1_2_9 =
            "GBL 1.2.9 HMCL式下载确认框·逐条前置清单·限高不拉长";
    static { System.err.println("[app] " + FP_1_2_9); }

    /**
     * 1.3.0 指纹：前置卡「查看」跳模组页并关掉下载确认框；启动前扫描已装模组的 Java 要求，
     * 与 Minecraft 本身要求取最大值，精确选 Java / 必要时自动下载。历史指纹一条不删。勿改这串字。
     */
    public static final String FP_1_3_0 =
            "GBL 1.3.0 查看跳模组页并关确认框·整合包Java版本自动适配";
    static { System.err.println("[app] " + FP_1_3_0); }

    /**
     * 1.3.1 指纹：细臂预览修复 / 动画流畅化 / 窗口自适应 / 联机皮肤头像 / 界面精简。
     * 独立真实字符串常量（进 class 常量池），历史指纹一条不删。勿改这串字。
     */
    public static final String FP_1_3_1 =
            "GBL 1.3.1 细臂预览修复·动画流畅化·窗口自适应·联机皮肤头像·界面精简";
    static { System.err.println("[app] " + FP_1_3_1); }

    /**
     * 1.3.2 指纹：撤销 1.3.1 新增的装饰动画（涟漪回 420ms、切页回淡入、去掉 hover 放大），
     * 1.3.1 的其它修复全部保留；源码从本版起同步发到 Gitee / GitHub，仓库不含密钥与 HMCL 源码。
     * 独立真实字符串常量（进 class 常量池），历史指纹一条不删。勿改这串字。
     */
    public static final String FP_1_3_2 =
            "GBL 1.3.2 恢复原版动画·源码双仓库发布·密钥不入仓";
    static { System.err.println("[app] " + FP_1_3_2); }

    /**
     * 1.3.3 指纹：设置页「外观」自定义主题（壁纸 / 文字颜色 / 主题色 / 自绘调色盘），
     * 以及宝可梦整合包那种「找不到精确 Java、自动下载又失败」的场景改用本机更高的 Java 启动。
     * 独立真实字符串常量（进 class 常量池），历史指纹一条不删。勿改这串字。
     */
    public static final String FP_1_3_3 =
            "GBL 1.3.3 自定义主题·Java下载失败兜底·源码双仓";
    static { System.err.println("[app] " + FP_1_3_3); }

    private static final double MIN_WIDTH = 680;
    private static final double MIN_HEIGHT = 460;

    /** 主窗口 Stage：静默启动时先收好，供托盘唤回主窗口复用。 */
    private static Stage primaryStage;
    /** 主窗口是否已初始化过：防止重复点托盘时反复跑代理探测 / 建场景等重活。 */
    private static boolean uiInitialized = false;

    @Override
    public void start(Stage stage) {
        // 0.9.6 r3：先把传入的 Stage 收好，供托盘唤回主窗口复用。
        primaryStage = stage;

        // 0.9.9 r11：独立安装脚本写下的结果标记，下一次启动时显示（失败时可见，不再静默）。
        javafx.application.Platform.runLater(LauncherApp::showDeferredUpdateResult);

        // 0.9.6 r6 A3：启动即自愈旧版开机自启 Run 值（仅当值含 --silent 才重写，幂等）。
        BootAutostart.refreshIfStale();

        boolean silent = getParameters().getRaw().contains("--silent");
        if (silent) {
            // 0.9.8 r6：开机自启（--silent）照常显示主窗口、占用任务栏；这条启动路径不主动
            // 创建托盘图标。0.9.8 r3 起图标一旦创建就常驻，窗口可见时也不摘除，只有
            // 「关闭到托盘」那一下会 ensureIcon，真退出才 dispose（见 ShellView.closeWindow）。
            System.err.println("[app] 开机自启（--silent）：显示主窗口并占用任务栏，不挂托盘");
            // 关掉「最后一个窗口关闭就退出」：支持「关闭到托盘」，窗口 hide 后进程仍常驻托盘。
            // 本类在 com.mclauncher 包内，Platform 必须写全限定 javafx.application.Platform，
            // 裸写会被解析成 com.mclauncher.Platform 导致编译失败。
            javafx.application.Platform.setImplicitExit(false);
            initAndShowStage(primaryStage);
            return;
        }

        // 正常启动：同样关掉 implicitExit，保证「关闭到托盘」与托盘菜单弹出/收起都不会误退出进程。
        javafx.application.Platform.setImplicitExit(false);
        initAndShowStage(primaryStage);
    }

    /**
     * 1.2.7：读独立安装脚本留下的结果标记，失败时只弹右下角非阻断短提示。
     *
     * <p>旧版用 {@code Alert.showAndWait()} 原生模态框，和自绘更新框叠加、会锁住整个窗口，
     * 是用户「卡住」的另一来源。现在绝不弹任何原生框；标记读后照旧改名留档
     * （单文件 move，不删除任何东西，符合零删除铁律）。
     */
    private static void showDeferredUpdateResult() {
        try {
            Path p = Platform.appDir().resolve("app").resolve("gbl-update-result.txt");
            if (!Files.isRegularFile(p)) {
                return;
            }
            String text = Files.readString(p, StandardCharsets.UTF_8).trim();
            try {
                Files.move(p, p.resolveSibling("gbl-update-result-read-" + System.nanoTime() + ".txt"));
            } catch (Exception ignore) {
                // 改名失败也无所谓：标记极小，下次启动最多再提示一次，不影响启动
            }
            if (!text.startsWith("FAIL")) {
                return;
            }
            String reason = text.length() > 4 ? text.substring(4).trim() : "";
            // 1.2.7：技术原因（连不上服务器 / TLS handshake…）只写日志，不堆给用户。
            System.err.println("[update] 上次安装结果标记: " + reason);
            ShellView.toastGlobal("上次自动更新未完成，已跳过；可在设置中检查更新");
        } catch (Exception ignore) {
            // 结果提示是辅助信息，绝不能因为它影响启动
        }
    }

    /**
     * 装配并显示主窗口：代理探测 / 索引回填 / 场景 / 自动更新检查都在这里。
     * 正常启动与静默启动共用这一条路径，避免逻辑分叉；成功后才把 uiInitialized 置位（C5 懒建失败可重试）。
     */
    private static void initAndShowStage(Stage stage) {
        // 出海域名（Modrinth / CurseForge / GitHub / 各 maven）在后台先测一次线路：
        // 本机实测 cdn.modrinth.com 直连 13.6 KB/s、走 Clash 混合口 2.06 MB/s，差 150 倍。
        // 只影响这些域名，微软登录 / Mojang / BMCLAPI 仍按原样直连。
        ProxyRoute.prefetch();

        // 0.9.1 r3：老版本装的模组索引没有 requiresTitles 字段，报「缺前置」时只会显示
        // 一串 Modrinth 项目 ID（如 AANobbMI）。后台 best-effort 反查真名并回写，
        // 不阻塞窗口、失败静默；离线内置映射负责「立刻可见」，这里负责补齐长尾。
        ModIndex.backfillTitlesAsync();

        // 0.9.9 r5：不再首启自动建离线账号，账号一律由用户在「账号」页自行添加。

        // 自绘标题栏：最小化 / 最大化 / 关闭都在 UI 里实现（ShellView），不用系统窗口装饰。
        // 必须用 TRANSPARENT 而不是 UNDECORATED：UNDECORATED 的官方定义就是「solid white background」，
        // 窗口四角那块底色是白的，圆角外一圈就会露出白边（这也是自绘窗口最常见的假圆角）。
        // TRANSPARENT 才是真透明窗口，配合 scene.setFill(TRANSPARENT) 四角直接透桌面，外轮廓才真是圆弧。
        stage.initStyle(StageStyle.TRANSPARENT);
        // C4：系统窗口标题改中文文案（ShellView 自绘标题栏由 w1 改成同款，本文件不碰 ShellView）。
        stage.setTitle("GBL 启动器 " + DISPLAY_VERSION);
        ShellView shell = new ShellView();
        stage.setScene(shell.createScene(stage));
        stage.setResizable(true);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.centerOnScreen();
        stage.show();
        // 0.9.8：万一上次更新其实没覆盖成功，别再让界面默默停在旧版本号。
        checkLastUpdateStamp(stage);
        // C5：窗口显示成功后再置位，避免「先置位后初始化」在懒建失败时把窗口卡死。
        uiInitialized = true;
        // 0.9.6：自动更新总开关关掉就不查；开了才启动后异步查一次更新
        // （发现新版本弹 HMCL 风格对话框；失败 / 无更新静默）。
        if (SettingsStore.get().isAutoUpdate()) {
            UpdateController.startAsyncCheck(shell);
        }
    }

    /**
     * 0.9.8：读上次自动更新写下的版本标记。
     *
     * <p>老版本最坑的一点不是「更新失败」，而是「下载完、进度走满、也重启了，界面还是旧版本号」，
     * 全程没有一句解释。现在覆盖成功后会把目标版本记下来；若下次启动发现「记下来的版本比
     * 正在跑的还新」，就说明那次覆盖没真正生效，明确告诉用户出了什么事、下一步该做什么。
     */
    private static void checkLastUpdateStamp(Stage owner) {
        Path p;
        try {
            p = com.mclauncher.Platform.appDir().resolve("app").resolve("gbl-last-update.txt");
            if (!Files.isRegularFile(p)) {
                return;
            }
            String raw = Files.readString(p, StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) {
                return;
            }
            // 0.9.9 r5：标记升级为「version build」二元组（空格分隔，前 version 后 build），
            // 写侧由更新链路同步改；无空格的是 r4 及之前的老标记，照旧兼容读取。
            int sp = raw.indexOf(' ');
            String stampVersion = sp >= 0 ? raw.substring(0, sp).trim() : raw;
            String stampBuild = sp >= 0 ? raw.substring(sp + 1).trim() : null;  // 可能为空串
            // 目标批次取 build 号与补丁号折成的 build 号两者较大者：任何一个说没装上就告警。
            int byBuild = (stampBuild != null && !stampBuild.isEmpty())
                    ? buildNumber(stampBuild) : 0;
            int patch = patchAfter(stampVersion, VERSION);
            int byPatch = patch >= 0 ? patch + 1 : 0;   // 补丁号 N 对应 build N+1
            int target = Math.max(byBuild, byPatch);
            boolean effective = target > 0
                    ? target <= buildNumber(BUILD)
                    : compareVersion(stampVersion, VERSION) <= 0;  // 两者都解析不出 → 保守清空
            if (effective) {
                // 更新确实生效了。零删除：把标记覆盖写空，不删文件。
                Files.writeString(p, "", StandardCharsets.UTF_8);
                return;
            }
            final String wanted = stampVersion;
            // 1.2.7：同样去掉原生模态 Alert，改右下角非阻断短提示，不阻塞启动。
            System.err.println("[update] 上次更新标记未生效: " + wanted);
            ShellView.toastGlobal("上次自动更新未完成，已跳过；可在设置中检查更新");
        } catch (Exception ex) {
            System.err.println("[update] 读上次更新标记失败（不影响使用）: " + ex);
        }
    }

    /** 按点分段的数字比较版本号；非数字段按 0 处理，绝不因格式怪异抛异常打断启动。 */
    private static int compareVersion(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xv = i < x.length ? digits(x[i]) : 0;
            int yv = i < y.length ? digits(y[i]) : 0;
            if (xv != yv) {
                return Integer.compare(xv, yv);
            }
        }
        return 0;
    }

    private static int digits(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        try {
            return sb.length() == 0 ? 0 : Integer.parseInt(sb.toString());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** "r5" → 5；格式非法或溢出返回 0，绝不因标记格式怪异打断启动。 */
    private static int buildNumber(String b) {
        if (b == null || b.length() < 2 || b.charAt(0) != 'r') {
            return 0;
        }
        String nums = b.substring(1);
        for (int i = 0; i < nums.length(); i++) {
            char c = nums.charAt(i);
            if (c < '0' || c > '9') {
                return 0;
            }
        }
        try {
            return Integer.parseInt(nums);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * v 必须恰好是 head + "." + 纯数字（如 "0.9.9.3" 相对 "0.9.9"）才返回该补丁号，否则 -1。
     * 用来把老标记里的补丁号折成 build 序号，避免把同版本重发误报成「上次更新没生效」。
     */
    private static int patchAfter(String v, String head) {
        if (v == null || head == null || !v.startsWith(head + ".")) {
            return -1;
        }
        String tail = v.substring(head.length() + 1);
        if (tail.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
        }
        try {
            return Integer.parseInt(tail);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /**
     * 用户点托盘时唤回主窗口：初始化成功后重复点只把窗口提到前面。
     * 由 javafx.application.Platform.runLater 切回 FX 线程调用。
     */
    public static void showMainWindow() {
        if (uiInitialized) {
            // 四连唤回保持不变：先清掉「收进托盘时顺带标上的最小化」再 show，否则 Windows 上唤不回来。
            primaryStage.setIconified(false);
            primaryStage.show();
            primaryStage.toFront();
            primaryStage.requestFocus();
            // 0.9.8 r3：托盘图标常驻，窗口回到前台也不摘除 —— 图标永不插拔最不容易错乱
            // （微信 / QQ / Discord / Steam 都是这个做法）；removeIcon 现在只收起托盘菜单。
            TraySupport.removeIcon();
            return;
        }
        // C5：懒建失败可重试 —— initAndShowStage 成功后才会把 uiInitialized 置 true，
        // 失败就保持 false，下次点托盘再试，不再「先置位后初始化」把窗口卡死。
        try {
            initAndShowStage(primaryStage);
            TraySupport.removeIcon();
        } catch (Throwable ex) {
            uiInitialized = false;
            System.err.println("[app] 懒建主窗口失败，保留可重试: " + ex);
        }
    }

    public static void main(String[] args) {
        // 0.9.8 r6：进程层单实例。第二个 GBL.exe 把「叫出主窗口」转交首实例后直接退出，
        // 绝不建窗口、绝不进 JavaFX、绝不挂托盘图标，通知区就不会越积越多。
        // 自动更新拉起的新进程带 --gbl-restart：旧进程此刻仍持锁，必须跳过「转发后退出」，
        // 改为最多 1 秒重试拿锁，等旧进程退出后由新进程接替成为首实例。
        boolean restart = false;
        for (String arg : args) {
            if (SingleInstance.RESTART_ARG.equals(arg)) {
                restart = true;
                break;
            }
        }
        if (restart) {
            SingleInstance.acquireForRestart();
            launch(args);
            return;
        }
        if (!SingleInstance.acquireOrForward()) {
            return;
        }
        launch(args);
    }
}
