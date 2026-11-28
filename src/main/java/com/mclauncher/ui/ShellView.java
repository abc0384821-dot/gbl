package com.mclauncher.ui;

import com.mclauncher.GameEntry;
import com.mclauncher.GameLauncher;
import com.mclauncher.GameScanner;
import com.mclauncher.LauncherApp;
import com.mclauncher.LibFetcher;
import com.mclauncher.LoaderInstaller;
import com.mclauncher.LoaderType;
// 别 import com.mclauncher.Platform：这文件里 Platform 这简名已被 javafx.application 占了，
// 两个同名 import 会让所有 Platform.xxx 变成「引用不明确」。要用就写全限定。
import com.mclauncher.ModpackImporter;
import com.mclauncher.ModpackRegistry;
import com.mclauncher.ModIndex;
import com.mclauncher.ModrinthClient;
import com.mclauncher.ModStore;
import com.mclauncher.VersionManifest;
import com.mclauncher.GameDownloader;
import com.mclauncher.DownloadCenter;
import com.mclauncher.FileFetch;
import com.mclauncher.CurseForgeClient;
import com.mclauncher.ContentStore;
import com.mclauncher.AdaptedMc;
import com.mclauncher.UpdateController;
import com.mclauncher.UpdateInfo;
import com.mclauncher.UpdateChecker;
import com.mclauncher.UpdateInstaller;
import com.mclauncher.ProxyRoute;
import com.mclauncher.BootAutostart;
import com.mclauncher.SettingsStore;
import com.mclauncher.ThemeStore;
import com.mclauncher.TraySupport;
import com.mclauncher.SingleInstance;
import com.mclauncher.AccountStore;
import com.mclauncher.LittleSkinAuth;
import com.mclauncher.LittleSkinSkin;
import com.mclauncher.SkinStore;
import com.mclauncher.SkinRender;
import com.mclauncher.Skin3D;
import com.mclauncher.LaunchProgress;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Point2D;
import com.mclauncher.GameEntry;
import com.mclauncher.GameScanner;

import javafx.scene.CacheHint;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Alert;
import javafx.scene.control.ProgressBar;
import java.nio.file.Path;
import java.nio.file.Paths;
import javafx.scene.control.TextField;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Tooltip;
import java.nio.file.Files;
import java.io.ByteArrayInputStream;
import com.mclauncher.MicrosoftAuth;
import com.mclauncher.Net;
import com.mclauncher.CoverCodecs;
import com.mclauncher.ModAliases;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.FlowPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * 启动器界面外壳：顶栏 + 侧边导航 + 内容区 + 状态栏。
 * 纯 UI，不接任何启动器业务逻辑；响应式只通过 root 宽度监听切换布局。
 */
public final class ShellView implements UpdateController.UpdateUi {

    /** 0.9.8 r1 指纹：按钮涟漪与按下反馈 + 删除等磁盘操作后台化。构建门禁扫常量池，勿改这串字。 */
    public static final String FP_098_UI = "0.9.8 r1 按钮涟漪与删除后台化";
    static { System.err.println("[ui] " + FP_098_UI); }
    /** 0.9.8 r3 指纹：界面文案精简。构建门禁扫 class 常量池核验，勿改这串字。 */
    public static final String FP_098_R3_UI = "0.9.8 r3 界面文案精简";
    static { System.err.println("[ui] " + FP_098_R3_UI); }

    private static final double NAV_WIDE = 232;
    private static final double NAV_NARROW = 76;
    private static final double NAV_BREAK = 900;    // 侧边栏收成图标条的断点
    // 游戏行菜单的框大小：定位时要拿它算「会不会越出窗口」，写死比问布局早一帧
    private static final double MENU_W = 196;
    private static final double MENU_H = 156;
    private static final double STACK_BREAK = 700;  // 内容卡片改竖排的断点
    private static final double INITIAL_WIDTH = 1180;
    private static final double INITIAL_HEIGHT = 760;

    /**
     * 窗口圆角，也是圆角裁剪弧的半径（两层嵌套时外圈正好多出一圈描边，见 .frame）。
     * 圆角外面透什么由 StageStyle.TRANSPARENT + 透明 Scene 决定（见 LauncherApp），这里不要补实色：
     * 补实色的话那块实色的外轮廓是直角，窗口四角看着依旧是尖的。
     */
    private static final double CORNER = 16;
    // 0.9.5 r1：库页（模组/整合包/资源包/光影/世界）切换动画每帧重渲整窗三重模糊阴影，是卡顿根因
    // （shell 整窗挂 SHADOW，而子节点效果会被 frameClip 在 729 行附近 bindClip 裁掉，
    // 所以不能把阴影挪到子节点——会丢）。安全做法：保留悬浮窗窗影观感，但把模糊从
    // THREE_PASS_BOX / 半径 26 降级为更便宜的 ONE_PASS_BOX / 半径 14 / 偏移 (0,6)，每帧成本大幅下降。
    /** 贴边时收掉投影：最大化窗口的影子会拖在屏幕外，看着像一圈脏边。 */
    // 0.9.9 r4 起不再使用，保留常量以维持常量池稳定（构建门禁扫常量池）。
    private static final DropShadow SHADOW =
            new DropShadow(BlurType.ONE_PASS_BOX, Color.web("#0b1a2b"), 14, 0.18, 0, 6);

    private static final int WIN_BTN_W = 46;
    private static final int WIN_BTN_H = 34;
    private static final double WIN_BTN_R = 6;
    private static final double WIN_ICON = 18;
    /** 自绘标题栏边缘的可拖拽宽度。 */
    private static final int EDGE = 6;
    private static final int EDGE_W = 1;
    private static final int EDGE_E = 2;
    private static final int EDGE_N = 4;
    private static final int EDGE_S = 8;

    /** 「恢复默认」的两段文案：同一按钮点两次才算确认，三秒不点自己弹回去。 */
    private static final String RESET_LABEL = "恢复默认";
    private static final String RESET_ARMED = "再点一次确认";

    private static final Color STROKE_IDLE = Color.web("#667085");
    private static final Color STROKE_HOVER = Color.web("#101828");
    private static final Color BTN_HOVER_BG = Color.web("#ecf0f5");
    private static final Color CLOSE_HOVER_BG = Color.web("#e5484d");
    private static final Color CLOSE_HOVER_STROKE = Color.WHITE;

    /**
     * 弹层的深色样式表，只在深色主题下挂到弹层 scene 上（内容见 {@code popup-dark.css}）。
     * 用资源 URL 而不是文件名，凑齐模块路径下的那个 file: URL 才认得出重复挂载。
     */
    // 走绝对路径：和 theme.css 一样（模块路径下相对名会拿不到，getResource 直接返回 null）
    private static final String POPUP_DARK_CSS =
            ShellView.class.getResource("/com/mclauncher/popup-dark.css").toExternalForm();

    /**
     * 0.9.4：弹层统一样式表。浅色 / 深色都给 ComboBox 弹层 scene 挂上 theme.css，
     * 修掉浅色下弹层既不挂 theme.css 也不挂 popup-dark.css、露出系统默认滚动条的问题。
     * 用与主 Scene（:714-715）相同的资源 URL，模块路径下才认得出重复挂载。
     */
    private static final String POPUP_THEME_CSS =
            ShellView.class.getResource("/com/mclauncher/theme.css").toExternalForm();

    /** 0.9.4 指纹：适配版本胶囊。构建门禁扫 class 常量池核验，勿改这串字。 */
    private static final String FP_094_ADAPTED_MC = "0.9.4 适配版本胶囊";
    /** 0.9.4 指纹：启动页底部操作条。 */
    private static final String FP_094_ACTIONBAR = "0.9.4 启动页底部操作条";
    /** 0.9.4 指纹：弹层统一样式表。 */
    private static final String FP_094_POPUP_CSS = "0.9.4 弹层统一样式表";
    static {
        System.err.println("[shell] 0.9.4: " + FP_094_ADAPTED_MC + " / " + FP_094_ACTIONBAR
                + " / " + FP_094_POPUP_CSS);
    }

    /** 1.1.5 指纹：界面收敛与流畅度修复。构建门禁扫 class 常量池，勿改这串字。 */
    private static final String FP_1_1_5_MOTION = "GBL 1.1.5 界面收敛与流畅度修复";
    static {
        System.err.println("[shell] 1.1.5: " + FP_1_1_5_MOTION);
    }

    /** 0.9.4 r2 指纹组：常量池硬校验，勿改这几串字。 */
    private static final String FP_094_R2_WORLD = "0.9.4 r2 世界停用开关";
    private static final String FP_094_R2_COVER = "0.9.4 r2 禁用内容封面归一";
    private static final String FP_094_R2_PERF = "0.9.4 r2 内容页动效减负";
    private static final String FP_094_R2_HERO = "0.9.4 r2 启动页贴底留白";
    private static final String FP_094_R2_SCROLL = "0.9.4 r2 滚动条照 HMCL";
    static {
        System.err.println("[shell] 0.9.4 r2: " + FP_094_R2_WORLD + " / " + FP_094_R2_COVER
                + " / " + FP_094_R2_PERF + " / " + FP_094_R2_HERO + " / " + FP_094_R2_SCROLL);
    }

    /** 0.9.6 r4 指纹：内容页单例去重，构建门禁扫 class 常量池核验，勿改这串字。 */
    private static final String R4_SHELL_FINGERPRINT = "0.9.6 r4 内容页单例去重";
    /** 0.9.6 r4 指纹：启动页按钮等比回归。 */
    private static final String R4_HERO_FINGERPRINT  = "0.9.6 r4 启动页按钮等比回归";

    /** 0.9.6 r5 指纹：启动页横线删除、按钮数值回归 0.9.5。构建门禁扫 class 常量池核验，勿改这串字。 */
    private static final String FP_096_R5_HERO = "0.9.6 r5 启动页横线删除";

    /** 0.9.6 r6 指纹：输入框样式与错误提示大众化。构建门禁扫 class 常量池核验，勿改这串字。 */
    private static final String FP_096_R6_SHELL = "0.9.6 r6 输入框样式与错误提示大众化";
    static {
        System.err.println("[shell] 0.9.6 r6: " + FP_096_R6_SHELL);
    }

    /** 0.9.7 r1 指纹：新手上手引导。构建门禁扫 class 常量池核验，勿改这串字。 */
    private static final String FP_097_GUIDE = "0.9.7 r1 新手上手引导";
    /** 0.9.7 r1 指纹：线路与更新可见化。构建门禁扫 class 常量池核验，勿改这串字。 */
    private static final String FP_097_SHELL_VISIBLE = "0.9.7 r1 线路与更新可见化";
    static {
        System.err.println("[shell] 0.9.7 r1: " + FP_097_GUIDE + " / " + FP_097_SHELL_VISIBLE);
    }

    /** 0.9.9 r3 指纹：装完新游戏后新手引导卡消失。构建门禁扫 class 常量池核验，勿改这串字。 */
    private static final String FP_099_R3_GUIDE = "0.9.9 r3 装完游戏引导卡消失";
    static { System.err.println("[shell] " + FP_099_R3_GUIDE); }

    /** 0.9.9 r4 指纹：摘掉整窗阴影与结果网格整块位图缓存。构建门禁扫常量池，勿改这串字。 */
    private static final String FP_099_R4_PERF = "0.9.9 r4 摘整窗阴影与网格位图缓存";
    static { System.err.println("[shell] " + FP_099_R4_PERF); }

    private static final String FP_099_R5_FAIL_DLG = "0.9.9 r5 更新失败不再伪装成发现更新";
    static { System.err.println("[shell] " + FP_099_R5_FAIL_DLG); }

    /** 0.9.9 r6 指纹：离线皮肤导入导出与失败提示。构建门禁扫常量池，勿改这串字。 */
    private static final String FP_099_R6_SKIN_UI = "0.9.9 r6 离线皮肤导入导出与失败提示";
    static { System.err.println("[shell] " + FP_099_R6_SKIN_UI); }

    /** 0.9.9 r8 指纹：在线整合包安装前明确告知模组数量。构建门禁扫常量池，勿改这串字。 */
    private static final String FP_099_R8_MODPACK_CONFIRM = "0.9.9 r8 整合包安装前明确告知模组数量";
    static { System.err.println("[ui] " + FP_099_R8_MODPACK_CONFIRM); }

    /** 0.9.9 r9 指纹：整合包安装确认卡按前置依赖分组展示。构建门禁扫常量池，勿改这串字。 */
    private static final String FP_099_R9_MODPACK_DEPS = "0.9.9 r9 整合包前置依赖分组展示";
    static { System.err.println("[ui] " + FP_099_R9_MODPACK_DEPS); }

    /** 0.9.9 r9 覆盖：整合包详情弹窗展示可折叠前置依赖。构建门禁扫常量池，勿改这串字。 */
    private static final String FP_099_R9_DEPS_IN_DETAIL = "0.9.9 r9 整合包详情弹窗展示前置依赖可折叠";
    static { System.err.println("[ui] " + FP_099_R9_DEPS_IN_DETAIL); }

    /** 0.9.9 r10 指纹：前置依赖 HMCL 卡片直显免确认安装。构建门禁扫常量池，勿改这串字。 */
    private static final String FP_099_R10 = "0.9.9 r10 前置依赖HMCL卡片直显免确认安装";
    static { System.err.println("[ui] " + FP_099_R10); }

    // ---------------------------------------------------------------- 1.0.8 涟漪白名单 / 依赖兜底池
    /**
     * 1.0.8：只有「用户一眼就认成按钮」的语义类名才有涟漪。
     * 纯 Pane / VBox / HBox / FlowPane / ScrollPane 即使挂了鼠标处理器也不算按钮，
     * 这是 0.9.8「凡是能点的都播」误伤详情大区块的根因。
     */
    private static final Set<String> RIPPLE_STYLE_CLASSES = Set.of(
            "nav-item", "user-card", "tab-btn", "filter-chip", "mod-card",
            "menu-item", "comp-card", "skin-cell");
    /** 1.0.8：这些类名（版本列表行 / 前置依赖卡 / 弹层背景）永不播涟漪，命中即整下跳过。 */
    private static final Set<String> NO_RIPPLE_STYLE_CLASSES = Set.of(
            "no-ripple", "version-row", "mod-version-row", "dep-card",
            "mod-detail-card", "dlg-backdrop", "launch-backdrop",
            // 1.1：开关只播自己的滑动/渐变动画，绝不播涟漪（含「按钮特效」设置开关）
            "toggle-switch");
    /**
     * 1.0.8：依赖卡逐 id 兜底共用的有界线程池（4 daemon 线程）——
     * 几十个前置项目也只排队，不会一页开几十条线程把机器拖慢。
     */
    private static final java.util.concurrent.ExecutorService DEP_FALLBACK_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "gbl-dep-fallback");
                t.setDaemon(true);
                return t;
            });

    private static final String WEBSITE_URL = "https://gbl-website.pages.dev/";

    private final BorderPane root = new BorderPane();
    private StackPane shell;
    private final Rectangle frameClip = new Rectangle();
    private final VBox nav = new VBox(6);
    private final Region navSpacer = new Region();
    private final HBox lowerWide = new HBox(16);
    private final VBox lowerNarrow = new VBox(16);
    private final HBox heroBodyWide = new HBox(20);
    private final VBox heroBodyNarrow = new VBox(14);
    // ---------------------------------------------------------------- 设置页（v0.2）

    /** 当前生效的设置。不可变：改一项走 withXxx 换一份新的，别就地 mutate。 */
    private SettingsStore settings = SettingsStore.load();
    /** 窗口被收进托盘后要拿它把窗口叫回来（hide 没销毁窗口，stage 还活着）。 */
    private Stage stage;
    /** 深色主题当前有没有挂上（SYSTEM 模式下跟着系统变，所以每次都要重算）。 */
    private boolean themeDark;
    // ------------------------------------------------------------ 自定义外观（1.3.3）
    /** 背景 / 壁纸 / 主题色 / 文字色的持久化，独立落在 {@code ~/.gbl/theme.json}，不动 config.json。 */
    private ThemeStore themeStore = ThemeStore.load();
    /** 外观运行时：壁纸层、固定蒙版、运行时样式表。Scene 建好之后才有值。 */
    private ThemeRuntime themeRuntime;
    /** 设置页「背景」三选一药丸（深色 / 浅色 / 自定义壁纸）。 */
    /** 主题下拉下面那条壁纸条（选到「自定义壁纸」才显示）。 */
    private VBox wallpaperStripHost;
    /** 程序回读药丸状态时压住点击逻辑，避免"刷新界面"反过来改配置。 */
    private boolean syncingAppearance;
    /** 壁纸行右侧的状态文字（文件名 / 未选择）。 */
    private Label wallpaperStateLabel;
    /** 两枚颜色预览点与它们的取值文字。 */
    private Region textColorSwatch;
    private Region accentColorSwatch;
    private Label textColorValueLabel;
    private Label accentColorValueLabel;
    /** 两个内联展开的调色面板宿主（默认收起、不占位）。 */
    private VBox textPickerHost;
    private VBox accentPickerHost;
    private ColorPickerPanel textPicker;
    private ColorPickerPanel accentPicker;
    /** 外观落盘的防抖：拖动色环时不该每帧写一次 theme.json。 */
    private PauseTransition themeSaveDebounce;
    /** 顶栏那枚「正在保存 / 已保存」的小胶囊。 */
    private Label settingsStatus;
    private final List<Node> settingsGroupButtons = new ArrayList<>();
    private StackPane settingsGroupBody;
    private VBox appearanceGroup;
    private VBox windowGroup;
    private VBox advancedGroup;
    private ComboBox<String> themeBox;
    private ToggleSwitch motionToggle;
    /** 1.0.7：按钮特效开关（点按钮播涟漪）。做成字段是为了 refreshSettingsUi 能回读。 */
    private ToggleSwitch buttonEffectsToggle;
    /** 1.1.2：自动下载 Java 开关（缺精确主版本 JRE 时自动下载）。字段为了恢复默认回读。 */
    private ToggleSwitch autoDownloadJavaToggle;
    private ToggleSwitch trayToggle;
    private ToggleSwitch bootAutostartToggle;
    /** 0.9.6 r3：开机自启开关的程序回填 / 失败回滚闸门，避免 setSelected 反向触发 setEnabled 回环。 */
    private final boolean[] bootAutostartGuard = new boolean[1];
    private ToggleSwitch autoUpdateToggle;
    private ToggleSwitch closeAfterLaunchToggle;
    private TextField gameMemoryField;
    private TextField concurrentDownloadsField;
    private ComboBox<String> downloadSourceSelect;

    /** 0.9.7：设置页「出海线路」当前线路文字（重新测速后就地刷新，不重建整页）。 */
    private Label lineStatusLabel;
    /** 0.9.7：设置页「版本与更新」的检查结果文字。 */
    private Label versionResult;
    private TextField cfKeyField;
    private TextField javaPathField;
    private PauseTransition settingsSaveDebounce;

    /** 各内容页叠在一层里，切页只改 visible/managed（页面自己负责撑满高度）。 */
    private final StackPane pages = new StackPane();
    /** 右下角那条临时提示的容器（v0.4.1 原型，1.2.5 纠正回右下角）。层本身不挡点击，气泡也不挡。 */
    private VBox toastBox;
    /** 1.2.7 QA：最近一条 toast 文案（含启动检查失败的离线提示）。 */
    private static volatile String lastToastTextForTests = "";
    /** 1.2.7 QA：最近构造的 ShellView，供静态方法读真实 shown 场景的节点读数。 */
    private static volatile ShellView lastCreatedForTests;
    /** 1.2.4：只盖 BorderPane center 的模态宿主；顶栏/左侧导航/状态栏不在其中，照常可操作。 */
    private StackPane centerHost;
    private StackPane modalHost;
    /** 1.2.6：全局弹层登记表。跨主菜单隐藏/恢复的唯一判据是「用户有没有真关闭」。 */
    private final OverlayRegistry overlayRegistry = new OverlayRegistry();
    /** 1.2.6：导航项按页索引，供 QA 走真实点击路径切页。 */
    private final Map<Page, HBox> navItemByPage = new EnumMap<>(Page.class);
    /**
     * 侧边栏当前停在哪一页。连点同一个入口时拿它挡住重播淡入（见 selectNavItem）。
     * 初始由 buildPages 里的 showPage(Page.HOME) 落值。
     */
    private Page currentPage;
    private ScrollPane homePage;
    /** 启动页新手引导卡：装完任意游戏后从页面上移除，并置空。 */
    private Node firstRunGuideCard;
    // v0.3 起下载页也和首页、设置页同构：内容会长，得能滚，所以是 ScrollPane 不是 VBox
    private ScrollPane downloadPage;
    private ScrollPane settingsPage;
    // v0.3.1 游戏列表页与「游戏管理」占位页
    private ScrollPane gameListPage;
    /** 「游戏管理」v0.7.0 起是「Mod 中心」（三 tab：加载器 / Mod / 整合包）。 */
    private ScrollPane gameManagePage;
    private ScrollPane consolePage;
    /** 多人联机页与其实体：切页钩子经 multiplayerView 回调，lastShownPage 记录上一页。
     *  1.2.5：页面节点改为 StackPane（遮罩与 center 同边界，不受 ScrollPane viewport 内缩）。 */
    private StackPane multiplayerPage;
    private MultiplayerPage multiplayerView;
    private Page lastShownPage;

    // ---------------------------------------------------------------- Mod 中心（v0.7.0）

    /** 状态条那句话：「Fabric 0.15.7 · 已安装」/「原版 · 未安装加载器」。 */
    private Label modCenterStatus;
    /** 游戏管理页状态卡那句话。 */
    private Label gameMgmtStatus;

    /** 页头「当前游戏」下拉：磁盘上扫出来的游戏 id。 */
    private ComboBox<String> modGameBox;
    /** 页头「下载源」下拉（官方 / BMCLAPI），落 {@link SettingsStore}。 */
    private ComboBox<String> modSourceBox;
    /** 程序在改下拉选项时压住监听器：不让「回填选项」被当成「用户改了选择」。 */
    private boolean modGameBoxSyncing;
    /** Mod 中心自己盯的游戏；null = 没选（整页空态引导）。 */
    private String modCenterGameId;
    /** 加载器 tab 各卡上的版本下拉 / 状态 / 主按钮，按加载器类型记。 */
    private final Map<LoaderType, ComboBox<String>> modLoaderBoxes = new LinkedHashMap<>();
    private final Map<LoaderType, Label> modLoaderStates = new LinkedHashMap<>();
    private final Map<LoaderType, Button> modLoaderButtons = new LinkedHashMap<>();
    /** 0.8.8 R-08：失败三态——卡内常驻错误 Label + 「重试」Button，按加载器类型记。 */
    private final Map<LoaderType, Label> modLoaderErrors = new LinkedHashMap<>();
    private final Map<LoaderType, Button> modLoaderRetries = new LinkedHashMap<>();
    /** 加载器版本列表缓存 + 正在拉的标记：切页不重复打接口；换游戏才作废。 */
    private final Map<LoaderType, List<LoaderInstaller.LoaderVersion>> modLoaderVersions =
            new LinkedHashMap<>();
    private final Set<LoaderType> modVersionsLoading = new LinkedHashSet<>();
    private String modVersionsGame;
    /** Mod 中心内容区统一宽度：工具栏 / 分类行 / 结果网格都按它封顶，避免互相撑开。 */
    private static final double MOD_CONTENT_WIDTH = 1140;

    /** Mod tab：搜索框 + 排序 + 分类 + 封面卡片网格（已装模组已迁到「游戏管理」）。 */
    private TextField modSearchField;
    private FlowPane modResultBox;
    private Label modResultCount;
    private PauseTransition modSearchDebounce;
        /** 排序下拉（下载量 / 最新发布 / 最近更新）。 */
        private ComboBox<String> modSortBox;
    /** 分类胶囊行（0.8.6 起 FlowPane：CF 分类多、名字长，必须能换行）+ 当前选中分类。 */
    private FlowPane modCategoryRow;
    private List<String> modSelectedCats;
    /** 工具栏与源切换药丸：提为字段，切换数据源时显式保证常驻并同步选中态。 */
    private HBox modToolsBox;
    private HBox modSourceToggleBox;
    /** 模组数据源模式：0=仅 Modrinth，1=仅 CurseForge，2=两者合并（r4 首屏默认合并）。 */
    private int modSourceMode = 2;
    /** CurseForge 相关字段（与 Modrinth 平行，互不污染）。 */
    private HBox cfCategoryRow;
    private List<Long> cfSelectedCats;
    private PauseTransition cfSearchDebounce;
    /** 0.9.4 r2：有上限 LRU 封面缓存（约两个 tab 的量），accessOrder=true 让 get 也刷新顺序；
     *  只 FX 线程访问、不加锁；超出上限逐出最久未用的一张，五个内容 tab 逛下来
     *  不再让 200+ 张 Image 跨 tab 常驻。 */
    private static final int COVER_CACHE_MAX = 260;
    private final Map<String, Image> coverCache = new LinkedHashMap<String, Image>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > COVER_CACHE_MAX;
        }
    };
    /** 纯代码生成的占位封面缓存（按像素边长），失败时保证 ImageView 占位不塌陷。 */
    private static final Map<Integer, Image> COVER_PLACEHOLDERS =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 模组详情弹窗（0.8.6 D3：分区小标题 + 分隔线 + 固定底部状态条）。 */
    private Node modLayer;

    // ---------------------------------------------------------------- 1.2.9：HMCL 式下载确认框
    /** 确认框模态层：自带遮罩只盖操作区（modalHost），与详情弹窗的 modDetailDeps 完全独立。 */
    private Node installConfirmLayer;
    private Label installConfirmTitle;
    private HBox installConfirmVersionBar;
    /** 确认框自己的前置容器（限高可滚动）；切版本 / 重复弹框都不碰详情弹窗的 modDetailDeps。 */
    private VBox installConfirmDeps;
    private ScrollPane installConfirmScroll;
    private Button installConfirmOk;
    private Button installConfirmCancel;
    /** 待确认的请求：点「安装」才交给原 dispatchInstall 安装链。 */
    private InstallCtx installConfirmCtx;
    private Button installConfirmBtn;
    /** 一次性放行令牌：确认框「安装」回调二次进入 dispatchInstall 时跳过弹框。 */
    private boolean installConfirmApproved;
    /** 每次弹框自增；在飞的反查回填过期整份丢弃，不串卡。 */
    private int installConfirmSeq;
    private ImageView modDetailCover;
    private Label modDetailTitle;
    private Label modDetailSource;
    private Label modDetailMeta;
    private FlowPane modDetailTags;
    private HBox modDetailStats;
    private Label modDetailDesc;
    private Label modDetailExtra;
    private HBox modDetailLinks;
    private VBox modDetailVersions;
    private Label modDetailVersionHead;
    private Label modDetailStatus;
    /** 详情弹窗：前置依赖板块（必需 / 可选 / 不兼容）。0.8.5 新增，模仿 HMCL 提醒下载前置。 */
    private VBox modDetailDeps;
    /** r9 覆盖：§4 前置依赖标题，可点击折叠/展开，文案带数量。 */
    private Label modDepsTitle;
    /** r10：§4 前置依赖卡片默认直显（HMCL 风格，无需点标题展开）。 */
    private boolean modDepsExpanded = true;
    /** r9 覆盖：§4 当前前置依赖条数（标题计数用）。 */
    private int modDepsCount;
    /** r9 覆盖：§4 前置依赖限高滚动容器（展开后 75+ 张卡在内部滚动，不撑爆详情弹窗）。 */
    private ScrollPane modDepsScroll;

    // ---------------------------------------------------------------- 0.8.7 阶段A：内容 tab + 版本级依赖

    // 0.8.8：内容类型（ContentStore.Kind）不再用全局字段分派，改由 InstallCtx.kind 随每次安装请求携带。
    /**
     * 详情弹窗当前选中的 Modrinth 版本：既是依赖区联动的数据源，也是依赖标题反查的令牌
     * （切版本后，在飞的反查结果用它校验，过期不回填）。
     */
    private ModrinthClient.Version selectedVersion;
    /**
     * 依赖区渲染令牌（0.8.8 ④）：四类弹窗（含 CF / 内容 / 整合包）共用。每重建一次依赖区
     * 自增，异步反查标题回来时校验，过期整份丢弃——CF 弹窗没有 Modrinth Version 对象，
     * 不能复用 {@link #selectedVersion} 做令牌。
     */
    private int depsRenderToken;
    /** 三类内容详情的版本分组兜底组名（gameVersions 空但 loaders 有值的光影/资源包版本归入此组）。 */
    private static final String FALLBACK_MC_GROUP = "未标注 Minecraft 版本";
    // ---------------------------------------------------------------- 0.9.1 浏览加载提速 / 不冻结

    /** 结果缓存最多留这么多份查询条件，超出逐出最旧的一条（LinkedHashMap 保插入序）。 */
    private static final int RESULT_CACHE_MAX = 32;
    /**
     * 内容 / 模组浏览结果的内存缓存：key = 查询条件（query|context|sort|cats|tab|源），
     * value 是上一批成功的数据（不是 Node——JavaFX 节点不能跨父复用）。
     * 只缓存非空结果；命中就直接分帧渲染并秒开，不再打网络。所有读写都在 FX 线程。
     */
    private final LinkedHashMap<String, List<ContentHit>> contentResultCache = new LinkedHashMap<>();
    private final LinkedHashMap<String, List<UnifiedMod>> modResultCache = new LinkedHashMap<>();
    /** 每次刷新递增：工作线程记下发起时的序号，回填时比对，过期结果整份丢弃。 */
    private volatile long contentRefreshSeq;
    private volatile long modRefreshSeq;
    /** 0.9.4 r2 B1：整合包浏览刷新的生成序号（此前该方法没有 seq，一帧内 add 48 张卡）。 */
    private volatile long modpackRefreshSeq;
    /** 0.9.4 r2 A3：切下载 tab 时递增；在飞的异步回填发现 epoch 已变就整份丢弃，
     *  不再往已移出场景图的旧控件里塞节点。volatile 保证工作线程读得到 FX 线程的写入。 */
    private volatile long contentEpoch = 0;
    /** 0.9.4 r2 B2：当前可见的下载 tab 下标；refreshModCenter 据此只刷对应的一个方法。 */
    private int currentDownloadTab = 0;
    /** 0.9.4 r2 B2：本次进入模组 tab，refreshModCenter 是否因控件未建而未刷成（需首屏补一次）。 */
    private boolean modTabMergePending;
    /** 当前正在播的切页淡入：新淡入前先停旧的，避免连点账号卡时 Timeline 叠加导致抖动。 */
    private Timeline pageFade;
    /** 0.9.4 r2 C1：淡入期间临时开缓存的 page；淡入被 stop 打断时 onFinished 不触发，
     *  靠它在下一次 fadeIn 复位，绝不留常驻 cache。 */
    private Node fadeCacheOwner;

    /** 内容页控件（切 tab 整块重建，留单例引用给后台线程回填）。 */
    private int contentTabIndex = -1;
    private ContentStore.Kind contentKind;
    private TextField contentSearchField;
    private ComboBox<String> contentSortBox;
    private FlowPane contentResultBox;
    private Label contentResultCount;
    private PauseTransition contentSearchDebounce;
    /** 游戏管理页：已装加载器 / 已装模组 / 管理整合包 三段容器（卡片头 + body，body 每次刷新重填）。 */
    private VBox gameMgmtLoaderBox;
    private VBox gameMgmtModsBox;
    private VBox gameMgmtPackBox;
    private VBox gameMgmtPackBody;
    private VBox gameMgmtLoaderBody;
    private VBox gameMgmtModsBody;
    // 0.8.7：资源包 / 光影 / 世界 三个本地内容管理段（走 ContentStore，全局可管，与当前游戏无关）
    private VBox gameMgmtResourcePackBody;
    private VBox gameMgmtShaderBody;
    private VBox gameMgmtWorldBody;
    /** 世界版本后台刷新序号：切页 / 再次刷新时让旧后台结果作废，不回填到已重填的段。 */
    private volatile int worldVersionSeq;
    private VBox gameMgmtResourcePackBox;
    private VBox gameMgmtShaderBox;
    private VBox gameMgmtWorldBox;
    /** 整合包导入进行中的那一行（阶段文字 + 取消按钮）。 */
    private HBox modImportRow;
    private Label modImportStage;
    private final java.util.concurrent.atomic.AtomicBoolean modpackCancelled =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** 整合包 tab：Modrinth 浏览（搜索 / 排序 / 分类 / 封面卡片）+ 本地 .mrpack 导入。 */
    private TextField modpackSearchField;
    private FlowPane modpackResultBox;
    private Label modpackResultCount;
    private PauseTransition modpackSearchDebounce;
    private ComboBox<String> modpackSortBox;
    /** 0.8.6 D3：与 D2 模组页对齐，分类行改 FlowPane 可换行。 */
    private FlowPane modpackCategoryRow;
    private List<String> modpackSelectedCats;
    private VBox heroLeft;
    private VBox heroRight;
    private Label heroTitle;
    private Label heroDesc;
    private final List<PlayCardBody> playBodies = new ArrayList<>();
    private final VBox userCard = new VBox(8);
    private Label userName;

    // ---------------------------------------------------------------- 下载页（v0.3）

    /** 下载页顶部那一排 tab，位置固定 = 它在 DOWNLOAD_TABS 里的下标。 */
    private final List<Node> downloadTabs = new ArrayList<>();
    /** 7 个 tab 共用这块容器，切 tab 只换它的子节点。 */
    private VBox downloadTabBody;
    /** 搜索框。筛选条每次重建都用它，所以留引用。 */
    private TextField versionSearch;
    /** 「共 N 个版本」：实时数，跟着搜索和类型筛选走。 */
    private Label versionCount;
    private VBox versionList;
    /** 类型筛选胶囊：整份换类名，所以按类型记下来比按下标稳。 */
    private final List<FilterChip> filterChips = new ArrayList<>();
    /**
     * 队列的静态影子，给自测看——镜子照的是 {@link GameDownloader} 的任务表。
     *
     * <p>队列归服务层管（排到第 4 个得等、正在下的得报进度、下完得落盘给 {@code GameScanner} 扫），
     * 界面自己记一份「排了哪些」只会跟它打架（v0.4 把队列搬进 {@link GameDownloader}）。
     *
     * <p>{@code keySet()} 是活视图——界面上加了跟着多、移出跟着少，不用两边同步。
     * 页面建好时指一次就一直有效（整个程序只起一个 ShellView）。
     *
     * <p><b>忘了在 {@code buildPages()} 里指一次，自测就会一直读到 {@code Set.of()}。</b>
     */
    /**
     * 直接透服务层，不再经一道镜像。
     *
     * <p>v0.4 返工过一次：中间套了个 {@code queueSnapshot}，在 {@code buildPages()} 里指一次
     * {@code GameDownloader.jobIds()}。表面上是活视图（{@code keySet()} 会跟），
     * 结果自测那几步一直读到 {@code []}——而同一时刻「队列 2 行」是对的。
     * 少一层就少一处能错位的地方：这里给的就是 {@code jobIds()} 本身。
     */
    static Set<String> queuedIds() {
        return GameDownloader.jobIds();
    }
    /** 当前选中的版本号，null 表示没选。列表整体重建后靠它把绿条贴回原处。 */
    private String selectedVersionId;
    /** 队列页那句「队列里 N 项」。 */
    private Label queueCount;
    /** 队列行的容器。增删改都靠它整份换。 */
    private VBox queueRowBox;
    /** 0.9.0 R-04：游戏管理页状态卡左侧块图标（随当前游戏类型切换）。 */
    private Node gameMgmtStatusIcon;
    /** 0.9.0 R-04：当前游戏条目（刷新管理页时用来取类型块图标）。 */
    private GameEntry currentGameEntry;
    /** 当前这屏的版本：默认「正式版 + 最近 30 快照」，打开「显示全部」换成全量。 */
    private List<VersionManifest.OfficialVersion> versionRows = List.of();
    /** 官方这次比上次多出来的版本 id，行上挂一枚「新」徽标。 */
    private Set<String> officialFresh = Set.of();
    /** 状态条那句话：在线 / 离线用缓存 / 官方新增 N 个版本。 */
    private Label manifestState;
    /**
     * 状态条那句话存在哪儿——**不能只存控件上**。
     *
     * <p>切走再切回「游戏」页，整块 tab body 会重建，状态条是一根新 Label；
     * 只把它写在新控件上的话，重建后那根新 Label 还是出厂的「正在取官方清单…」，
     * 而列表底下早就铺满了——用户看着「正在取」其实是取完了（v0.3.5 自测就抓在这一句）。
     */
    private String manifestSummary = "正在取官方清单…";
    /** 「显示全部 917 个版本」那个开关。留引用是为了列表刷新时别把它一起冲掉。 */
    private ToggleSwitch showAllSwitch;
    /** 开关拨到「全部」了没有—存字段不存控件：切 tab 会重建控件，只存控件上的话拨过就白拨。 */
    private boolean showAllAll = false;
    /** 有一趟官方清单同步正在飞（见 {@link #requestManifestSync}）。 */
    private boolean manifestFetching;
    /** 同步在飞期间又喊了一声，落地后要补跑的那趟。 */
    private boolean manifestAgain;

    // ---------------------------------------------------------------- 游戏列表（v0.3.1）

    /** 游戏行的容器。刷新时整份换子节点，自测也数它有几行。 */
    private VBox gameRowBox;
    /** 「还没安装游戏」那块空态。 */
    private Node gameEmptyBox;
    /**
     * 当前选中的游戏 id，null 表示没选。和 {@code SettingsStore.currentGame} 同源：
     * 界面只认这一个字段，写盘去 store，避免「内存里选着、配置里存的不是同一个」。
     */
    private String currentGameId;
    /** 会去动磁盘的那种操作（改名 / 复制 / 删除），失败要能统一接住。 */
    private interface GameOp {
        void run() throws IOException;
    }

    /**
     * 0.9.8：后台磁盘任务。返回 true 表示成了，false / 抛异常表示没成，
     * 界面线程上分别回调 onOk / onFail，绝不把「等系统回收站」的时间压到界面上。
     */
    @FunctionalInterface
    private interface DiskTask {
        boolean run() throws IOException;
    }

    /**
     * 游戏行菜单。**必须自绘，不能用 JavaFX 的 {@code ContextMenu}**：
     * ContextMenu 是 PopupControl，弹层根节点不继承 shell 上的 {@code dark} 类，
     * {@code .dark .context-menu} 这类选择器永远命中不了，深色必然失效（v0.3 的
     * ComboBox 下拉踩的是同一个坑）。自绘之后跟下载确认弹层同一套视觉，深浅都稳。
     *
     * <p>同为静态引用，原因是 Snapshot 的任务是枚举常量、处在静态上下文里碰不到实例字段。
     */
    static Node gameMenu;
    static VBox gameMenuBody;
    /** 菜单里那张卡片（用户眼里那个「框」）：v0.3.3 起关不关菜单按它的矩形判断。 */
    static VBox gameMenuCard;

    /**
     * 删除确认 / 重命名输入共用的那一个弹层（同一个卡，body 每次打开重填）。
     * 拆成两个弹层是重复代码；合成一个只需要 {@code promptMode} 告诉它现在是什么身份。
     *
     * <p>同样是静态引用给 Snapshot 用（枚举常量在静态上下文里碰不到实例字段）。
     */
    /** 静态是为了 Snapshot：它的任务是枚举常量，处在静态上下文里碰不到实例字段。 */
    static Node gamePrompt;
    static VBox gamePromptBody;
    /** 弹层里那张卡：打开时只换它的子节点，别靠一层层 getChildren().get(n) 去摸回来。 */
    private VBox promptCard;
    /** 当前这个弹层是「重命名」还是「删除」，决定确定按钮做什么、长什么样。 */
    private String promptMode;
    private GameEntry promptGame;
    /** 重命名时那个输入框；回车直接确认，别让用户去够按钮。 */
    private TextField promptField;

    /**
     * 「最近游玩」卡里那一对空态 / 列表容器。宽窄两版卡各要一份：
     * 同一个 Node 不能进两个父节点，所以 refresh 必须遍历所有份，不能只改第一份。
     */
    private record PlayCardBody(VBox empty, VBox rows) {
    }

    /**
     * 当前可启动的版本，null 表示「还没有游戏」。
     *
     * 现在界面读不到「装了哪些版本」，这个字段恒为 null，界面就停在空态：
     * Hero 只出文案，不给「启动游戏 / 打开游戏目录」——没游戏的时候摆两个点不动的按钮只会更糟。
     * 等接上版本扫描，服务层拿到结果调一次 {@link #setCurrentVersion(String)} 就行。
     */
    private String currentVersion = null;
    /** 「最近游玩」有没有记录，现在恒为 false（接上启动流程后由 setHasPlayHistory 喂）。 */
    private boolean hasPlayHistory = false;

    // ---------------------------------------------------------------- 启动（v0.4.7 接上真服务层）
    // 服务层只管「拼命令 + 拉进程 + 攒输出」，这里只管把状态摆到界面上：
    // 准备阶段必须放后台线程（要读磁盘、补元信息、解压 natives，堵住 UI 就是整个窗口假死）

    /** 当前这个游戏的进程；null 表示没在跑，以此判定「能不能再点一次启动」。 */
    private GameLauncher.Session launchSession = null;
    /** Hero 上那颗启动 / 停止按钮：改成字段，运行期才能按会话状态改文案 / 图标 / 颜色。 */
    private Button heroPlay;
    /** 三态：启动准备中（launching）压住按钮，避免账号回调把文案冲回「启动游戏」导致重复启动。 */
    private volatile boolean launching = false;
    private boolean consoleStalled = false;   // 「卡住了」这句只说一次，别每帧刷
    /**
     * 这一轮是不是我们点「停止游戏」主动收掉的。
     *
     * <p>主动 destroy 出来的退出码是 1（Windows 上 TerminateProcess 就是 1），
     * 不认这个标志的话每次点停止都会被 {@link GameLauncher#explain} 报成
     * 「游戏异常退出（退出码 1）」——用户主动停个游戏，界面却骂它崩了，很冤。
     */
    private boolean launchStopped = false;
    /**
     * 游戏是否已经「真正起来」过一次（日志里出现过 Reloading ResourceManager / OpenAL initialized）。
     * 只有在它变 true 之后，启动器才收起/关闭——这样点启动后游戏卡在加载界面或刚起来就崩，
     * 启动器会**保持可见**让你看到错误，而不是一闪收起（v0.4.13）。
     */
    private boolean gameStarted = false;
    /**
     * 点了「停止游戏」之后再等多久（秒）才真的把进程拍死。
     *
     * <p>MC 正常收尾两三秒就干净退出；可它锁在驱动／网络原生调用里时，等下去只换来
     * 「用户自己去任务管理器强杀」。这里替他强杀并留一句话——启动器能做的事别推给用户（v0.4.10）
     */
    private static final long STOP_FORCE_AFTER_SECONDS = 8L;
    /** 把进程输出刷到控制台的泵；进程一停就停，别让 AnimationTimer 一直转。 */
    private javafx.animation.AnimationTimer outputPump = null;
    private TextArea consoleArea;
    private Label consoleStatus;
    private Label consoleReason;
    private Button consoleStop;
    private Button consoleCopy;
    private Button consoleDiag;

    // 启动加载层（v0.5）：点「启动游戏」到游戏真正起来之间盖住界面的一张卡片，
    // 挡操作、能取消。见 buildLaunchOverlay / startGame
    private Node launchLayer = null;

    // 账号（v0.5.2）：当前选中的账号决定进游戏用谁的身份；无账号则进不了游戏
    private AccountStore accountStore = AccountStore.load();
    private ScrollPane accountPage;
    private VBox accountRows;
    private Node accountPrompt;
    private VBox accountPromptCard;
    private TextField accountPromptField = new TextField();

    // 外置（little skin）账号登录弹窗
    private Node externalPrompt;
    private VBox externalPromptCard;
    private TextField externalUserField = new TextField();
    private PasswordField externalPassField = new PasswordField();
    private Label externalError = new Label();
    private Button externalOkBtn;
    private Button externalCancelBtn;

    // 头像（账号卡 + 账户行都用皮肤头渲染）
    private ImageView userHead;

    // 皮肤管理弹窗（v0.6.0：大界面 + 3D 预览 + 官方默认 + 自定义皮肤库）
    private Node skinPrompt;
    private VBox skinPromptCard;
    // 0.9.8 r2：皮肤预览按 HMCL 方式回到立体 3D（Skin3D），只留一盏纯白环境光。
    private Skin3D skin3d;                          // 每次打开新建，关闭时 stop 自转动画
    private Pane skin3dBox;                         // 预览容器（沿用旧名，避免碰布局代码）
    private String skinPendingSource = "steve";    // 当前预览选中的皮肤来源（内置 key / custom / gallery:xxx）
    private SkinStore.Custom skinPendingCustom;     // 选中「我的皮肤」里的某条时非空
    private String skinPendingModel = "classic";   // 当前预览模型的 classic/slim
    private Button skinClassicBtn, skinSlimBtn;    // 模型分段切换按钮（每次打开重建）

    // 1.1.3：LITTLESKIN 账号的服务器真实皮肤（按 uuid 存内存，后台拉取、主线程渲染）
    private final Map<String, LittleSkinSkin.Texture> littleSkinTextures = new HashMap<>();
    private final Set<String> littleSkinInFlight = new HashSet<>();
    private final AtomicBoolean littleSkinFailToasted = new AtomicBoolean(false);  // 失败只提示一次
    private ImageView littleSkinServerHead;   // 皮肤管理「服务器皮肤」只读卡片的头像
    private Label littleSkinServerDetail;     // 该卡片的模型说明（刷新回来后同步更新）
    private String littleSkinServerUuid;      // 该卡片当前展示的 uuid（刷新回来后对号入座）

    // 微软账号登录弹窗（设备码流）
    private Node microsoftPrompt;
    private VBox microsoftPromptCard;
    private Label microsoftCode = new Label();
    private Hyperlink microsoftUriLink = new Hyperlink();
    private Label microsoftStatus = new Label();
    private Label microsoftError = new Label();
    private Button microsoftCloseBtn;
    /** 用户中途关掉弹窗时置 true，让后台轮询线程尽快退出。 */
    private volatile boolean microsoftAborted = false;
    private Label launchTitle = null;

    // 安装新游戏弹窗（HMCL 风格：实例名 + 8 张组件卡）
    private Node installLayer;
    private VBox installCard;

    // 自动更新 UI（实现 UpdateController.UpdateUi）：中央白卡对话框挂 shell，
    // 深色提示条 0.9.8 起改挂底部状态栏左侧，不再压标题栏窗口按钮。
    // 两者默认隐藏，可反复 show / hide。结构 build 一次、show 时填文案与回调。
    private Node updateDialog;            // 居中白卡对话框层（遮罩 + 卡），setVisible 切换
    private Region updateDlgBackdrop;     // 1.2.7：失败弹窗点遮罩出口，QA 可真实 fire
    private HBox updateTip;               // 状态栏左侧的「发现更新」小胶囊（0.9.8 从右上角移来）
    private HBox statusBar;               // 底部状态栏，提示条挂它左侧、版本号挂它右侧
    private Label updateDlgTitle;         // 对话框标题「发现更新 X」
    private Label updateDlgBody;          // 对话框正文
    private Button updateDlgUpdateBtn;    // 「更新」按钮（下载中禁用 + 改文案）
    private Button updateDlgCancelBtn;    // 「取消」按钮
    private Button updateDlgManualLink;   // 失败时追加的「打开官网手动下载」出口（默认隐藏）
    private ProgressBar updateDlgBar;     // 进度条
    private Label updateDlgStage;         // 阶段文案行
    private boolean updateFailedPending;  // r2：失败提示待清标志，用户点「更新」重试时清掉
    /** r2：更新失败可读文案：什么失败 + 为什么 + 下一步能做什么。 */
    private static final String UPDATE_FAILED_MSG = "更新失败，原版本与设置未变。检查网络后重试，或到官网下载完整安装包。";
    private Label updateTipLabel;         // 提示条文案
    private Pane updateTipClose;          // 提示条上的 × 关闭按钮
    /**
     * 0.9.8 按钮涟漪层：只挂在 shell 最上层一次，鼠标穿透、不参与布局。
     * 每颗按钮按下时临时往里丢一个 420ms 的小圆再自动摘掉，
     * 不给任何列表卡片挂监听器 —— 列表重建一万次，这里也只有一个节点容器。
     */
    private Pane rippleLayer;
    /**
     * 1.0.5：正在播放的涟漪 Timeline。关窗 / 关弹层 / 切页时统一 stop，
     * 不让特效在已经消失的位置继续跑到 420ms 结束。
     */
    private final Set<Timeline> activeRipples = new HashSet<>();
    /** 1.3.1：右键菜单 catcher 跟随实际内容区，不再写死 INITIAL_WIDTH/HEIGHT。 */
    private Region menuCatcher;
    /** 0.9.8：同一批删除里连着调好几次「刷新游戏管理」时，合并成一次全量重建。 */
    private PauseTransition gameManageRefreshDebounce;
    /** 1.1.3：1.1.2 遗留的 .gbl-disabled 文件只迁移一次，别每进一次管理页就扫一遍。 */
    private boolean legacyPackRestoreDone;
    private Runnable updateOnUpdate;      // 暂存「更新」回调
    private Runnable updateOnCancel;      // 暂存「取消」回调
    private TextField installNameField = new TextField();
    private Label installStatus = new Label();
    private Button installRunBtn;
    private VersionManifest.OfficialVersion installVersion;
    private String installMcVersion;
    /** 0.9.0 R-04：安装弹窗里 Minecraft 卡的块图标节点，随选中版本类型替换。 */
    private Node installMcIcon;
    private LoaderType installLoader = null;
    private final List<LoaderType> INSTALL_LOADERS =
            List.of(LoaderType.FABRIC, LoaderType.QUILT, LoaderType.FORGE, LoaderType.NEOFORGE,
                    LoaderType.OPTIFINE);
    private final Map<LoaderType, ComboBox<String>> installLoaderBoxes = new java.util.HashMap<>();
    private final Map<LoaderType, Pane> installLoaderCards = new java.util.HashMap<>();
    private final Map<String, Pane> installCompCards = new java.util.HashMap<>();
    private final Map<String, Label> installCompSub = new java.util.HashMap<>();
    private final Map<String, Label> installCompBadge = new java.util.HashMap<>();
    /** 安装弹窗里随加载器选择变化的「中性预提示」（OptiFine 兼容性等，不含版本阈值）。 */
    private Label installLoaderHint;
    private Label launchStage = null;
    private Region launchFill = null;
    /** 进度条那截 fill 的宽度：绑这个比例，别去改 prefWidth（绑死就再也长不动）。 */
    private final DoubleProperty launchRatio = new SimpleDoubleProperty(0.12);
    private final java.util.concurrent.atomic.AtomicBoolean launchCancelled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /**
     * 1.2：启动准备看门狗改为「进度心跳新鲜度」判定，不再按 90 秒墙钟误杀。
     * 补依赖 / 补资源 / 全量校验都可能合理超过 90 秒；只要进度帧在推进就不算卡死。
     * 只有连续 5 分钟没有任何进度/阶段推进才认定真卡住。
     */
    private static final int LAUNCH_STALL_TIMEOUT_SECONDS = 300;
    /** 1.2：看门狗巡检间隔：每轮检查心跳，心跳新鲜就重置计时、绝不 interrupt worker。 */
    private static final int LAUNCH_WATCHDOG_POLL_SECONDS = 5;
    /** 1.2：最后一次收到启动进度帧 / 阶段推进的时间戳（System.nanoTime）。 */
    private final java.util.concurrent.atomic.AtomicLong lastLaunchProgressAt =
            new java.util.concurrent.atomic.AtomicLong(0L);
    /** 1.0.9：超时守卫。超时线程 CAS 成功后才收尾，worker 迟到返回不会重复弹错。 */
    private final java.util.concurrent.atomic.AtomicBoolean launchTimedOut =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 1.0.9：本轮启动令牌。用户取消 / 开了新一轮后，上一轮的超时线程不再动界面。 */
    private long launchEpoch = 0;
    /** 1.0.9：准备阶段是否已经走到「拉进程 / 明确失败」，防止游戏已跑起来才误报超时。 */
    private volatile boolean launchPrepareDone = false;
    private Thread launchWorker = null;
    /** r4：启动对话框是否还在订阅 LaunchProgress（关闭后置 false，丢弃迟到的进度帧）。 */
    private volatile boolean launchProgressActive = false;
    /** r4：启动不确定态（ratio<0）的呼吸动画，关闭对话框时必须 stop()。 */
    private Timeline launchIndeterminate = null;

    private final Label navSection = new Label("主菜单");
    private final SVGPath maxIcon = Icons.strokeScaled(Icons.WIN_MAX, WIN_ICON, 1.6);
    private final SVGPath restoreIcon = Icons.strokeScaled(Icons.WIN_RESTORE, WIN_ICON, 1.6);
    private final List<HBox> navItems = new ArrayList<>();
    private final List<Label> navLabels = new ArrayList<>();

    /** stage 传进来是因为自绘标题栏要自己控制最小化 / 最大化 / 关闭。 */
    public Scene createScene(Stage stage) {
        // 1.2.7 QA：记下真实实例，静态方法据此读 update-dialog / toast 的实际状态。
        lastCreatedForTests = this;
        // r4 U8：在初始化最前面显式注册 TwelveMonkeys WebPImageReaderSpi，确保首次封面
        // 解码前 CoverCodecs 的 SPI 已就位（install() 幂等，不改 LauncherApp / module-info）。
        CoverCodecs.install();
        // 托盘那一路要拿 stage 把窗口叫回来（收进托盘时只是 hide，没销毁）
        this.stage = stage;
        HBox appBar = buildAppBar(stage);
        buildNav();
        buildLower();
        buildPages();
        statusBar = buildStatusBar();

        root.getStyleClass().add("frame");   // 字体 / 默认前景色由 .frame 给，别让 CSS 挂个没用上的类
        root.setTop(appBar);
        root.setLeft(nav);
        centerHost = new StackPane();
        centerHost.getStyleClass().add("gbl-center-host");
        centerHost.getChildren().add(pages);
        modalHost = new StackPane();
        modalHost.getStyleClass().addAll("gbl-compact", "gbl-modal-host");
        modalHost.setPickOnBounds(false);
        // 1.3.1 修复轮：定宽弹层不得把 centerHost 撑大，否则 900/700 断点永不触发
        modalHost.setMinSize(0,0);
        centerHost.getChildren().add(modalHost);
        root.setCenter(centerHost);
        // 1.3.1 修复轮：catcher 的绑定已移进 buildGameMenu()——此处 menuCatcher 还是 null，
        // 放在这里只会是死代码（真机 bound=false / prefWidth 仍是 1180）。
        root.setBottom(statusBar);

        applyResponsive(INITIAL_WIDTH);
        root.widthProperty().addListener((obs, oldVal, newVal) -> applyResponsive(newVal.doubleValue()));

        // shell 只负责投影，圆角由 root 自己裁：root 的背景带圆角 + 一圈 1px 描边（.frame），
        // 中间不再夹一层「灰色留白框」，那层实色会把外轮廓撑回直角、四角又变尖。
        // 这一层不能再往外套容器：多包一层 StackPane 会把 scene 顶大到 root 的 pref size，窗口尺寸不受控。
        shell = new StackPane(root);
        shell.getStyleClass().addAll("shell", "window");
        // 锁住出厂尺寸：shell 挂了投影，effect 的 extent 会把 scene 顶大一圈（1220x800 而非 1180x760），
        // 定住 pref/min 之后 scene 就不会再自动长，窗口尺寸才收得住
        shell.setPrefSize(INITIAL_WIDTH, INITIAL_HEIGHT);
        // 1.3.1：与 LauncherApp 的 Stage 最小尺寸（680×460）对齐。原先 1180×760 会把
        // scene 根顶住，窗口拖窄后内容被裁，且 root.width 恒 >=1180 → 900/700 断点永不触发。
        shell.setMinSize(680, 460);
        // Rectangle 的 arc* 是椭圆的直径，不是半径：传半径会让圆弧只有一半大，
        // 与背景的 CornerRadii 对不上，两层弧不同心、角上会多露出一小片直角
        frameClip.setArcWidth(CORNER * 2);
        frameClip.setArcHeight(CORNER * 2);
        // 【圆角白边 bug 的修复，v0.1.6】裁剪必须和投影挂在 shell 这同一个节点上，不能挂到 root。
        // 渐变实测（tools/corner_raw.py：刷洋红壁纸 + dxcam 抓真实窗口像素 + A/B 差分）：
        //   投影挂在 shell 上时，裁剪只要落在 shell 的任何后代（root、或中间层 content）上，
        //   被裁掉的那块角就会漏出一整片「不透明的浅灰直角三角形平台」，实测 RGB(244,244,244)，
        //   边界严丝合缝跟着圆弧走 —— 这就是用户看到的「圆角外面一圈白边」。
        // 逐项排除过：不是 Scene fill、root 背景、root 描边、root 子节点、shell 背景、投影；
        // 把裁剪提到 shell 之后四角重新是真透明（实测透出洋红桌面）。
        // root 自己不裁：它的圆角由 .frame 的 -fx-background-radius/-fx-border-radius 给，
        // 内容（标题栏、侧栏、状态栏）的直角则由 shell 这一层裁剪一起修圆。
        bindClip(shell, frameClip);
        // 0.9.9 r4 起不再给整窗挂 DropShadow（原因：整窗模糊是切页/滚动/封面回填的公共乘数，
        // 成本随屏幕缩放倍率平方增长；窗口轮廓由 .frame 自带的圆角 + 1px 描边表达，观感不塌）。
        shell.setEffect(null);
        // 游戏行菜单与「改名 / 删除」弹层挂在 shell 上（盖整窗）：
        // 只盖内容区的话侧边栏还亮着，看着像没真挡住
        shell.getChildren().add(buildGameMenu());
        // 1.2.4：所有半透明遮罩确认弹层只覆盖操作区；游戏行透明菜单仍挂 shell（无黑幕）。
        modalHost.getChildren().add(buildGamePrompt());
        modalHost.getChildren().add(buildAccountPrompt());
        modalHost.getChildren().add(buildExternalPrompt());
        modalHost.getChildren().add(buildMicrosoftPrompt());
        modalHost.getChildren().add(buildSkinPrompt());
        modalHost.getChildren().add(buildInstallDialog());
        modalHost.getChildren().add(buildModDetailDialog());
        // 自动更新 UI：对话框（遮罩 + 卡）同样只盖操作区。
        // 0.9.8：提示条不再挂 shell / TOP_RIGHT —— 那一块正好是标题栏的最小化/最大化/关闭，
        // 会整条盖住并截获点击（用户报的「挡操作」）。现在作为状态栏左侧的一枚小胶囊，
        // 与右下角版本号分居两端，不盖任何按钮、不压内容区。
        modalHost.getChildren().add(buildUpdateDialog());
        statusBar.getChildren().add(0, buildUpdateTip());
        // 启动加载层挂在 shell 上（不是某一页）：启动是全窗正事，
        // 压在内容区上只盖住列表，看着还像界面没换完
        modalHost.getChildren().add(buildLaunchOverlay());
        // 1.2.9：确认框压在最后（z 序最高），祖先 modalHost 带 gbl-compact，
        // 遮罩只盖操作区，顶栏 / 左导航照常可点。
        modalHost.getChildren().add(buildInstallConfirmDialog());
        // 1.3.1 修复轮：初始隐藏的弹层必须 unmanaged——否则定宽大卡仍参与 StackPane
        // 尺寸计算，把 centerHost 撑到 >=700，root.width 恒 >=934 → 900/700 断点永不触发。
        for (Node hiddenLayer : modalHost.getChildren()) {
            hiddenLayer.setManaged(false);
        }
        // 0.9.8：按钮涟漪层压在内容之上、toast 之下。整窗只此一层，鼠标完全穿透，
        // 不参与布局、默认不可见；每颗按钮按下时临时丢一个 420ms 的小圆进去。
        rippleLayer = new Pane();
        rippleLayer.setMouseTransparent(true);
        rippleLayer.setVisible(false);
        shell.getChildren().add(rippleLayer);
        shell.getChildren().add(buildToastLayer());
        refreshFrameStyle(stage.isMaximized());
        stage.maximizedProperty().addListener((obs, ov, nv) -> refreshFrameStyle(nv));

        // 主题：深色彩套在 shell 上，CSS 里用后代选择器覆盖，root 自己不用再动
        applyTheme();
        // 「跟随系统」在系统主题切换后，得等下一次窗口重新拿到焦点才重算——
        // 不做轮询，系统主题本来也不是每秒变一次
        stage.focusedProperty().addListener((obs, ov, nv) -> {
            if (nv && settings.theme() == SettingsStore.ThemeMode.SYSTEM) {
                SystemTheme.refresh();
                applyTheme();
            }
        });
        // 0.9.8 r6：JVM 真退出时统一走 shutdown()（摘托盘图标 + 释放单实例锁 / 唤醒通道）；
        // 三条显式退出路径同样走 shutdown()，幂等可重复调用。
        Runtime.getRuntime().addShutdownHook(new Thread(SingleInstance::shutdown, "gbl-tray-cleanup"));
        // 1.1.4：JVM 兜底强杀联机子进程（terminateNow 幂等），原 hook 的 SingleInstance 逻辑不动
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> com.mclauncher.multiplayer.RoomService.get().terminateNow(), "gbl-mp-cleanup"));

        Scene scene = new Scene(shell, INITIAL_WIDTH, INITIAL_HEIGHT);
        // 圆角外面（四角）必须真透桌面：StageStyle.TRANSPARENT 的窗口底色本身就是透明的，
        // Scene 再填实色的话，那块实色外轮廓是直角，四角看着依旧是尖的
        scene.setFill(Color.TRANSPARENT);
        scene.getStylesheets().add(
                getClass().getResource("/com/mclauncher/theme.css").toExternalForm());
        // 1.3.3 自定义外观：壁纸层 / 固定蒙版 / 运行时样式表。
        // 必须在 theme.css 挂上之后建——映射出来的样式表要追加在它后面，同名规则才赢。
        // 也必须在上面的子节点（菜单 / 涟漪层 / toast）都加完之后建：它要插到 shell 的第 0 层。
        themeRuntime = new ThemeRuntime(shell, scene, themeStore);
        themeRuntime.attach(themeDark);
        // 0.9.9 r13：先试装一次，再等首帧 layout 后补一次（ScrollPane 的原生 scroll-bar
        // 要首帧才建出来）。installAll 幂等，详情弹窗（shell 内遮罩、同一 scene）一并覆盖。
        FloatScrollBarSkin.installAll(shell);
        javafx.application.Platform.runLater(this::installFloatScrollBars);
        // 行菜单（临时浮层，不压暗界面）的「点外面就收起」：
        // 挂在 scene 上比挂节点上稳，点哪都能收；且这一下会照常往下传给界面，
        // 所以点左侧导航是「收菜单 + 切页」一气呵成，不会白点一下。
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (gameMenu != null && gameMenu.isVisible() && outsideOfMenu(gameMenu, e)) {
                closeGameMenu();
            }
            playButtonRipple(e);
        });
        installResizer(stage, scene);
        // 自测开关（-Dgbl.snapshot=...）：本机 GDI 抓不到 JavaFX 画布，只能让应用自己出图。
        // 传 root 而不是 shell：shell 挂着投影，Node.snapshot 会把 effect 的 extent 一起拍进来
        // （尺寸被撑大 52px），四角就变成了「投影里」而不是「圆角外」，验收结论会跟着歪
        Snapshot.maybeDumpSequence(root);
        return scene;
    }

    /**
     * 0.9.8：HMCL 式按钮涟漪。只在 scene 的 MOUSE_PRESSED 过滤器里触发，
     * 不给任何按钮逐个挂监听器 —— 列表重建一万次，这里也只有一个涟漪层，
     * 不会像逐卡挂监听那样越用越卡、也不会误伤单例节点。
     *
         * <p>涟漪用圆角矩形裁在按钮自身范围内，420ms 后自动摘除；
         * 1.2：涟漪只由「按钮特效」开关控制；「界面动效」只管切页淡入/弹层入场，
         * 两个开关正交，互不再连带关闭。
     * 1.0.6：修三件事——①坐标改用 localToParent(shell) 对齐 rippleLayer（它是 shell 子节点）；
     *       ②每 holder 加 clip 裁剪到按钮范围，避免溢出；③单 KeyFrame+pauseOnHint
     *       替代 5 KeyFrame+多巡检帧，低配机不再卡顿；④开弹窗前取消所有活跃涟漪。
     */
    private void playButtonRipple(MouseEvent e) {
        // 1.2 修复：删掉 settings.reduceMotion() 这道耦合门。
        // 「界面动效」只管切页淡入/弹层入场；「按钮特效」独立且唯一地管制涟漪。
        // 关界面动效不再连按钮特效一起关；开按钮特效在界面动效关闭时仍然生效。
        if (rippleLayer == null || !settings.buttonEffects()
                || e.getButton() != MouseButton.PRIMARY
                || rippleLayer.getChildren().size() > 12) {
            return;
        }
        Node hit = e.getTarget() instanceof Node ? (Node) e.getTarget() : null;
        // 1.0.5/1.0.6：向上找第一个「用户真的能点」的节点
        // 1.0.8：先过禁区（详情弹窗的版本行 / 前置依赖卡）——碰到就整下不播，
        // 但禁区里的真 Button（如「查看」）会先被命中，不受这条限制。
        Node host = null;
        for (Node n = hit; n != null; n = n.getParent()) {
            // 1.1：开关是涟漪禁区（toggle-switch 已在 NO_RIPPLE_STYLE_CLASSES）。
            // 1.0.8 的「ToggleSwitch 宿主优先锁定」已撤掉：那会让点开关仍然铺一层涟漪，
            // 与 HMCL 式纯滑动效果冲突；命中禁区直接整下跳过，零涟漪。
            if (hasNoRippleClass(n)) {
                host = null;
                break;
            }
            if (isRippleTarget(n)) {
                host = n;
                break;
            }
        }
        if (host == null) {
            return;   // 点空气：一路找到场景根都没有可点击节点，不播
        }
        // 1.0.6: 先转场景坐标，再转回shell局部坐标
        Bounds sceneBounds = host.localToScene(host.getLayoutBounds());
        Point2D origin = shell.sceneToLocal(sceneBounds.getMinX(), sceneBounds.getMinY());
        double bx = origin.getX();
        double by = origin.getY();
        double w = sceneBounds.getWidth();
        double h = sceneBounds.getHeight();
        // 1.0.8：双保险——开关的涟漪尺寸永远锁在它自己那枚 34x20 的量级，
        // 即使宿主被父级缩放/变换带大，也不会向下铺成矩形。
        if (host instanceof ToggleSwitch) {
            w = Math.min(w, 40);
            h = Math.min(h, 24);
        }
        // 1.0.6：下限放宽让小图标按钮也有涟漪；整屏级大容器仍跳过，免得糊满一屏
        if (w < 14 || h < 12 || w > 900 || h > 500) {
            return;
        }
        // 1.0.6：鼠标位置换算成相对 shell 局部坐标（涟漪层坐标系）
        Point2D sp = shell.sceneToLocal(e.getSceneX(), e.getSceneY());
        double dx = sp.getX() - bx;
        double dy = sp.getY() - by;
        if (dx < 0 || dy < 0 || dx > w || dy > h) {
            return;   // 鼠标实际落点在按钮之外（含 clip 外的情况），不播
        }

        Pane holder = new Pane();
        holder.setTranslateX(bx);
        holder.setTranslateY(by);
        holder.setMinSize(w, h);
        holder.setPrefSize(w, h);
        holder.setMaxSize(w, h);
        holder.setMouseTransparent(true);
        // 1.0.6：clip 裁到按钮矩形，涟漪绝不溢出宿主边界
        // 1.0.8：圆角按宿主真实形状来 —— 开关 / 胶囊取半高，绝不露出深色方块
        double arc = rippleArcFor(host, w, h);
        Rectangle clip = new Rectangle(w, h);
        clip.setArcWidth(arc);
        clip.setArcHeight(arc);
        holder.setClip(clip);

        // 1.3.3：高光颜色跟着主题色走（深色主题仍是白光，浅色主题用主题色的淡圈）
        Circle dot = new Circle(dx, dy, 1, rippleColor());
        holder.getChildren().add(dot);
        rippleLayer.getChildren().add(holder);
        rippleLayer.setVisible(true);

        double radius = Math.hypot(Math.max(dx, w - dx), Math.max(dy, h - dy)) + 6;
        final Node rippleHost = host;
        final Timeline[] rippleRef = new Timeline[1];
        // 1.0.6：单 KeyFrame 替代 5 KeyFrame；宿主消失由 pauseOnHint 兜底
        rippleRef[0] = new Timeline(
                new KeyFrame(Duration.millis(420),
                        new KeyValue(dot.radiusProperty(), radius),
                        new KeyValue(dot.opacityProperty(), 0)));
        rippleRef[0].setOnFinished(ev -> removeRipple(rippleRef[0], holder));
        // 1.0.6：宿主离场立即停止，不等到 420ms
        activeRipples.add(rippleRef[0]);
        rippleRef[0].play();
    }

    /**
     * 1.0.6：开弹层前取消正在播放的涟漪（历史职责保留）。
     * 1.2.5 纠偏：<b>只用于联机页内部</b>打开/关闭游戏检查、邀请码、忙态弹层；
     * ShellView 的主菜单切页（账号/下载/启动等父界面）不再清涟漪，允许 420ms 完整播完。
     */
    private void cancelActiveRipplesBeforeOpenModal() {
        cancelAllRipples();
    }

    /**
     * 1.0.5：可点击判定；1.0.8 收窄为白名单 —— 只认用户真的会当成「按钮」的东西。
     * 老规则「挂了鼠标处理器就算」会把详情弹窗里的版本列表行 / 前置依赖卡整块当按钮，
     * 于是点一下铺出盖住半屏的巨型涟漪，这是用户投诉的直接根因。
     */
    private static boolean isRippleTarget(Node n) {
        if (n == null || n.isDisabled() || !n.isVisible()) {
            return false;
        }
        if (n instanceof ButtonBase || n instanceof Hyperlink) {
            return true;   // 自带 action 的天然按钮；1.1 起 ToggleSwitch 不算（它有自己的滑动动画）
        }
        if (hasNoRippleClass(n)) {
            return false;
        }
        // Control 家族的 onAction 没有统一 getter，按已知控件类型补上。
        if (n instanceof javafx.scene.control.TextField tf && tf.getOnAction() != null) {
            return true;
        }
        if (n instanceof javafx.scene.control.ComboBoxBase<?> cb && cb.getOnAction() != null) {
            return true;
        }
        // 1.0.8：纯布局容器即使挂了处理器也不算按钮；只有明确语义类名的卡片 / 导航项才播。
        if (n instanceof Pane || n instanceof FlowPane || n instanceof ScrollPane
                || n instanceof StackPane || n instanceof BorderPane) {
            boolean semantic = false;
            for (String cls : n.getStyleClass()) {
                if (RIPPLE_STYLE_CLASSES.contains(cls)) {
                    semantic = true;
                    break;
                }
            }
            if (!semantic) {
                return false;
            }
        }
        return n.getOnMouseClicked() != null || n.getOnMousePressed() != null;
    }

    /** 1.0.8：命中禁区类名就直接不播（版本行 / 依赖卡 / 详情壳 / 弹层背景）。 */
    private static boolean hasNoRippleClass(Node n) {
        if (n == null) {
            return false;
        }
        for (String cls : n.getStyleClass()) {
            if (NO_RIPPLE_STYLE_CLASSES.contains(cls)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 1.0.8：裁切圆角贴合宿主真实形状——开关 / 胶囊取半高（半高就是完整圆头），
     * Region 有背景圆角就认背景；都不明确时，小控件仍按胶囊，大卡片保留小圆角矩形。
     * holder 的 translate 与 clip 仍以宿主左上角 (0,0) 为原点，不改坐标口径。
     */
    private static double rippleArcFor(Node host, double w, double h) {
        final double half = Math.min(w, h) / 2.0;
        if (host instanceof ToggleSwitch) {
            return half;   // 34×20 的开关：半高 10 = 完整胶囊，绝不露出深色方块
        }
        if (host instanceof Region region) {
            javafx.scene.layout.Background bg = region.getBackground();
            if (bg != null) {
                for (javafx.scene.layout.BackgroundFill fill : bg.getFills()) {
                    javafx.scene.layout.CornerRadii radii = fill.getRadii();
                    if (radii == null) {
                        continue;
                    }
                    double r = Math.max(
                            Math.max(radii.getTopLeftHorizontalRadius(),
                                    radii.getTopRightHorizontalRadius()),
                            Math.max(radii.getBottomLeftHorizontalRadius(),
                                    radii.getBottomRightHorizontalRadius()));
                    if (r > 0.5) {
                        return Math.min(half, r);
                    }
                }
            }
        }
        if (h <= 40 && w <= h * 2.2) {
            return half;   // 小尺寸近方 / 近胶囊控件：整颗圆头最贴
        }
        return Math.min(18, half);
    }

    /** 1.0.5：宿主离场（被移出场景树，或自身/任一祖先被隐藏）时涟漪必须立刻收工。 */
    private static boolean rippleHostGone(Node host) {
        for (Node n = host; n != null; n = n.getParent()) {
            if (n.getScene() == null || !n.isVisible()) {
                return true;
            }
        }
        return false;
    }

    /** 1.0.5：摘掉一条涟漪。stop() 不触发 onFinished，故这里自己清 holder 与可见性。 */
    private void removeRipple(Timeline timeline, Node holder) {
        if (timeline != null) {
            timeline.stop();
            activeRipples.remove(timeline);
        }
        if (holder != null) {
            rippleLayer.getChildren().remove(holder);
        }
        if (rippleLayer.getChildren().isEmpty()) {
            rippleLayer.setVisible(false);
        }
    }

    /**
     * 1.0.5：关闭界面时取消所有正在播放的涟漪。
     * 统一在关窗 / 关弹层的收口处调用，不给每个按钮的 onAction 逐个挂。
     */
    /** 1.2.5：仅供联机页内部弹层调用；不做全局切页清理。 */
    private void cancelAllRipplesForMultiplayer() {
        cancelActiveRipplesBeforeOpenModal();
    }

    /** QA 钩子：当前活跃涟漪数（关联机弹层/关按钮特效后必须为 0；父页切换可非 0）。 */
    public int activeRippleCount() {
        return activeRipples.size();
    }

    private void cancelAllRipples() {
        if (rippleLayer == null) {
            return;
        }
        // 复制集合再遍历：stop() 会阻止 onFinished 触发、不改集合，复制更稳，杜绝 CME。
        for (Timeline t : new ArrayList<>(activeRipples)) {
            t.stop();
        }
        rippleLayer.getChildren().clear();
        rippleLayer.setVisible(false);
        activeRipples.clear();
    }

    /** 窗口尺寸变了，圆角矩形裁剪跟着改尺寸（现在它挂在 shell 上，不是 root 上）。 */
    private static void bindClip(Region target, Rectangle clip) {
        clip.widthProperty().bind(target.widthProperty());
        clip.heightProperty().bind(target.heightProperty());
        target.setClip(clip);
    }

    /**
     * 贴边（最大化）时把圆角和投影一起收掉：窗口已经占满屏幕，留着弧线和影子都会变成脏边。
     * 圆角改的是挂在 shell 上的那道裁剪弧；shell 这一层的裁剪同时把内容（标题栏 / 侧栏 /
     * 状态栏）的直角一起修圆，所以 root 自己不用再挂裁剪。
     */
    private void refreshFrameStyle(boolean maximized) {
        shell.setEffect(null);
        double arc = maximized ? 0 : CORNER * 2;   // clip 认的是椭圆直径，不是半径
        frameClip.setArcWidth(arc);
        frameClip.setArcHeight(arc);
    }

    // ---------------------------------------------------------------- 顶栏

    /**
     * 自绘标题栏：logo + 应用名在左，状态 pill 与 最小化/最大化/关闭 在右。
     * 这条栏同时兼做窗口拖拽区，双击切换最大化。
     */
    private HBox buildAppBar(Stage stage) {
        Rectangle mark = new Rectangle(24, 24);
        mark.setArcWidth(16);   // arc* 是直径：24 配 16 才是半径 8 的圆角，不是 4
        mark.setArcHeight(16);
        // 填充色交给 .brand-mark（CSS）—— Java 侧 setFill 会盖过 CSS，深色主题就没法改它
        mark.getStyleClass().add("brand-mark");

        // 0.9.4：标题栏换成新 logo 资源 logo_24.png；资源由发布流程生成，缺失时兜底回旧画法，绝不 NPE。
        Image logoImg = null;
        try (InputStream in = ShellView.class.getResourceAsStream("/com/mclauncher/logo_24.png")) {
            if (in != null) {
                logoImg = new Image(in);
            }
        } catch (Exception ignored) {
            // 资源缺失 / 解码失败一律回落旧画法
        }
        Node brandNode;
        if (logoImg == null) {
            brandNode = new StackPane(mark, Icons.create(Icons.LOGO, 13));
        } else {
            ImageView logoView = new ImageView(logoImg);
            logoView.setFitWidth(24);
            logoView.setFitHeight(24);
            logoView.setPreserveRatio(true);
            logoView.setSmooth(true);
            brandNode = logoView;
        }
        StackPane brand = new StackPane(brandNode);
        brand.getStyleClass().add("brand");
        brand.setPrefSize(24, 24);

        Label title = new Label("GBL 启动器 "
                + com.mclauncher.LauncherApp.DISPLAY_VERSION);
        title.getStyleClass().add("app-title");

        HBox left = new HBox(10);
        left.setAlignment(Pos.CENTER_LEFT);
        left.getChildren().addAll(brand, title);

        HBox right = new HBox(4);
        right.setAlignment(Pos.CENTER_RIGHT);
        right.getChildren().addAll(buildStatusPill(), buildWebButton(), buildWindowButtons(stage));

        HBox bar = new HBox(10);
        bar.getStyleClass().add("app-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        // 光标交给 scene 决定（普通区域默认箭头、贴边 6px 内是缩放），只在按住拖动时临时变移动光标
        Region titleFiller = filler();
        HBox.setHgrow(titleFiller, Priority.ALWAYS);
        bar.getChildren().addAll(left, titleFiller, right);
        installTitleBarDrag(bar, stage);
        return bar;
    }

    /** 最小化 / 最大化（或还原）/ 关闭三个自绘按钮（不用 Button：默认皮肤会往按钮上画焦点环和底色）。 */
    private HBox buildWindowButtons(Stage stage) {
        HBox box = new HBox(2);
        box.setAlignment(Pos.CENTER_RIGHT);

        Pane min = winBtn("win-btn", Icons.strokeScaled(Icons.WIN_MIN, WIN_ICON, 2.0),
                BTN_HOVER_BG, STROKE_HOVER);
        min.setOnMouseClicked(e -> stage.setIconified(true));

        // 最大化 / 还原两个图标必须放在同一个容器里切换可见性：只切可见性的话
        // 最大化时按钮上唯一挂着的图形被隐藏，按钮会整块变空白（还原图标还是个没挂上去的孤儿节点）
        restoreIcon.setVisible(false);
        StackPane maxGraphic = new StackPane(maxIcon, restoreIcon);
        maxGraphic.setPrefSize(WIN_ICON, WIN_ICON);
        maxGraphic.setMinSize(WIN_ICON, WIN_ICON);
        StackPane.setAlignment(maxIcon, Pos.CENTER);
        StackPane.setAlignment(restoreIcon, Pos.CENTER);
        // 单独给最大化一个类名：Snapshot 自测要靠 "win-btn-max" 精确点到它（前缀匹配会先命中最小化）
        Pane max = winBtn("win-btn win-btn-max", maxGraphic, BTN_HOVER_BG, STROKE_HOVER);
        max.setOnMouseClicked(e -> {
            if (System.getProperty("gbl.debug.max") != null) {
                System.out.println("[dbg] win-btn-max click count="
                        + e.getClickCount() + " source=" + e.getSource()
                        + " x=" + (int) e.getX() + " y=" + (int) e.getY()
                        + " target=" + e.getTarget());
                new Exception("MAXBTN CLICK").printStackTrace();
            }
            stage.setMaximized(!stage.isMaximized());
        });
        stage.maximizedProperty().addListener((obs, ov, nv) -> {
            maxIcon.setVisible(!nv);
            restoreIcon.setVisible(nv);
        });

        Pane close = winBtn("win-btn win-btn-close", Icons.strokeScaled(Icons.WIN_CLOSE, WIN_ICON, 1.8),
                CLOSE_HOVER_BG, CLOSE_HOVER_STROKE);
        // 关不关得掉、收不收进托盘由设置决定（见 closeWindow）
        close.setOnMouseClicked(e -> closeWindow(stage));

        box.getChildren().addAll(min, max, close);
        return box;
    }

    /**
     * 关闭按钮真正干的事：默认直接关；「关闭时收进托盘」开启后只 hide（窗口还在，只是不显示），
     * 并且确保托盘图标存在，不然点了 × 会把整个启动器一起带走，用户连回来的路都没有。
     */
    private void closeWindow(Stage stage) {
        cancelAllRipples();   // 1.0.5：真正关窗前先掐掉所有正在播的涟漪
        if (settings.closeToTray() && TraySupport.supported()) {
            // 0.9.8 r3：窗口收起来只 hide；托盘图标由 ensureIcon 幂等复用并常驻，
            // 窗口重新可见时也不摘除（见 restoreFromTray / TraySupport.removeIcon）。
            TraySupport.ensureIcon(this::restoreFromTray, Platform::exit);
            stage.hide();
            return;
        }
        stage.close();
        // 1.1.4：真退出才收尾联机子进程（托盘分支已 return 不会走到这）；null 回调在 RoomService 内部判空安全
        com.mclauncher.multiplayer.RoomService.get().shutdownAsync(null);
        // 0.9.7：LauncherApp.start 统一 setImplicitExit(false)（为了支持关闭到托盘），
        // 所以「真的关闭」必须自己叫停 FX 运行时，否则窗口没了进程还在，任务管理器里留个僵尸。
        // 0.9.8 r6：这是真退出，走统一收口（摘常驻托盘图标 + 释放单实例锁 / 唤醒通道）。
        // ensureIcon(this::restoreFromTray, Platform::exit) 的退出回调链保持不动。
        SingleInstance.shutdown();
        javafx.application.Platform.exit();
    }

    /** 托盘图标被点：从 hide 状态叫回窗口。setIconified(false) 是必须的，收进托盘那一下可能顺带标了最小化。 */
    private void restoreFromTray() {
        Stage target = stage;
        if (target == null) {
            return;
        }
        Platform.runLater(() -> {
            target.setIconified(false);
            target.show();
            target.toFront();
            target.requestFocus();
            // 0.9.8 r3：托盘图标常驻，窗口回到任务栏也只是收起托盘菜单；
            // 图标不摘除、不重建，任务栏与托盘可以同时在（与微信 / QQ 一致）。
            TraySupport.removeIcon();
        });
    }

    /** 自绘窗口按钮：整块透明，hover 底色与图标颜色都由 Java 侧填，不交给默认皮肤。 */
    private static Pane winBtn(String styleClass, Node graphic, Color hoverBg, Color hoverStroke) {
        Rectangle bg = new Rectangle();
        // arc* 是椭圆直径：要半径 6 的圆角得给 12，之前给 6 等于半径 3、看着就是个方角块
        bg.setArcWidth(WIN_BTN_R * 2);
        bg.setArcHeight(WIN_BTN_R * 2);
        bg.setFill(Color.TRANSPARENT);

        Pane pane = new StackPane(bg);
        // 按空格拆开逐条 add：whole-string add 会把 "win-btn win-btn-close" 当成一个类名，
        // 之后按 "win-btn-close" 精确匹配永远落空（Snapshot 自测就是这么点按钮的）
        for (String token : styleClass.split("\\s+")) {
            if (!token.isEmpty()) {
                pane.getStyleClass().add(token);   // 单参 add：多参会编译成 String→int 的怪错误
            }
        }
        pane.setPrefSize(WIN_BTN_W, WIN_BTN_H);
        pane.setMinSize(WIN_BTN_W, WIN_BTN_H);
        StackPane.setAlignment(graphic, Pos.CENTER);
        bg.widthProperty().bind(pane.widthProperty());
        bg.heightProperty().bind(pane.heightProperty());
        pane.getChildren().add(graphic);

        paintStroke(graphic, STROKE_IDLE);
        pane.setOnMouseEntered(e -> {
            bg.setFill(hoverBg);
            paintStroke(graphic, hoverStroke);
        });
        pane.setOnMouseExited(e -> {
            bg.setFill(Color.TRANSPARENT);
            paintStroke(graphic, STROKE_IDLE);
        });
        return pane;
    }

    /** graphic 可能是裸 SVGPath，也可能是装了两个图标的 StackPane（最大化按钮）。 */
    private static void paintStroke(Node graphic, Color color) {
        if (graphic instanceof SVGPath) {
            ((SVGPath) graphic).setStroke(color);
        } else if (graphic instanceof Pane) {
            for (Node child : ((Pane) graphic).getChildren()) {
                paintStroke(child, color);
            }
        }
    }

    /** 标题栏拖动窗口；双击（非按钮上）切换最大化。最大化时交给系统去 snap，不再自己挪。 */
    private static void installTitleBarDrag(HBox bar, Stage stage) {
        final double[] grab = new double[2];
        bar.setOnMousePressed(e -> {
            bar.setCursor(Cursor.MOVE);
            if (stage.isMaximized()) return;
            grab[0] = e.getScreenX() - stage.getX();
            grab[1] = e.getScreenY() - stage.getY();
        });
        bar.setOnMouseDragged(e -> {
            if (stage.isMaximized()) return;
            stage.setX(e.getScreenX() - grab[0]);
            stage.setY(e.getScreenY() - grab[1]);
        });
        bar.setOnMouseReleased(e -> bar.setCursor(null));
        bar.setOnMouseClicked(e -> {
            if (e.getButton() != MouseButton.PRIMARY || e.getClickCount() != 2) return;
            if (isOverWindowButton(e.getPickResult().getIntersectedNode())) return;
            stage.setMaximized(!stage.isMaximized());
        });
    }

    private static boolean isOverWindowButton(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n.getStyleClass().contains("win-btn")) return true;
        }
        return false;
    }

    private HBox buildStatusPill() {
        HBox pill = new HBox(7);
        pill.getStyleClass().add("status-pill");
        pill.setAlignment(Pos.CENTER_LEFT);
        Rectangle dot = new Rectangle(8, 8);
        dot.setArcWidth(8);
        dot.setArcHeight(8);
        // 颜色归 .dot（CSS），这里只管形状
        dot.getStyleClass().add("dot");
        Label text = new Label("就绪");
        text.getStyleClass().add("status-text");
        pill.getChildren().addAll(dot, text);
        return pill;
    }

    /** 标题栏「访问官网」按钮：点击用系统默认浏览器打开 GBL 官网（WEBSITE_URL）。 */
    private Pane buildWebButton() {
        Pane btn = winBtn("win-btn win-btn-web",
                Icons.strokeScaled(Icons.GLOBE, WIN_ICON, 1.8),
                BTN_HOVER_BG, STROKE_HOVER);
        btn.setOnMouseClicked(e -> openInBrowser(WEBSITE_URL));
        Tooltip.install(btn, new Tooltip("访问 GBL 官网"));
        return btn;
    }

    // ---------------------------------------------------------------- 侧边栏

    private void buildNav() {
        nav.getStyleClass().add("side-bar");
        nav.setPadding(new Insets(16, 12, 14, 12));

        navSection.getStyleClass().add("nav-section");
        nav.getChildren().add(navSection);

        // v0.1.4 起删掉「版本」：那个入口对应的版本管理还没做，摆着只会点开什么都没有
        List<NavItem> items = List.of(
                new NavItem("启动", Icons.NAV_LAUNCH, Page.HOME),
                // 1.0.8：只用 NAV_* 专用图标，PLAY / DOWNLOAD / CUBE 等老常量仍被封面兜底等处复用，绝不改字
                new NavItem("下载", Icons.NAV_DOWNLOAD, Page.DOWNLOAD),
                // 游戏列表紧跟着下载：下载完了的游戏正好落进这个列表，动线上是连着的
                new NavItem("游戏列表", Icons.NAV_GAMES, Page.GAMELIST),
                // 游戏管理：当前游戏的状态 + 安装新游戏入口（HMCL 式安装弹窗的入口也在这儿）
                new NavItem("游戏管理", Icons.NAV_MANAGE, Page.GAMEMGMT),
                new NavItem("控制台", Icons.NAV_CONSOLE, Page.CONSOLE),
                new NavItem("多人联机", Icons.NAV_MULTIPLAYER, Page.MULTIPLAYER),
                new NavItem("设置", Icons.NAV_SETTINGS, Page.SETTINGS));
                // v0.5.3 起侧边栏不放「账号」入口：走右下角账号卡（openAccountPage）

        VBox list = new VBox(3);
        for (int i = 0; i < items.size(); i++) {
            NavItem spec = items.get(i);
            HBox item = navItem(spec.text(), spec.icon(), spec.page());
            if (i == 0) {
                item.getStyleClass().add("selected");
            }
            // 给能切页的项一个独立类名：Snapshot 自测要点名点这一项切页
            // （nav-item 是四项的公共类名，点不准会命中控制台），设置项同理
            if (spec.page() == Page.DOWNLOAD) {
                item.getStyleClass().add("nav-download");
            } else if (spec.page() == Page.GAMELIST) {
                item.getStyleClass().add("nav-gamelist");
            } else if (spec.page() == Page.GAMEMGMT) {
                item.getStyleClass().add("nav-gamemgmt");
            } else if (spec.page() == Page.SETTINGS) {
                item.getStyleClass().add("nav-settings");
            } else if (spec.page() == Page.CONSOLE) {
                item.getStyleClass().add("nav-console");
            } else if (spec.page() == Page.MULTIPLAYER) {
                item.getStyleClass().add("nav-multiplayer");
            } else if (spec.page() == Page.HOME) {
                item.getStyleClass().add("nav-home");
            }
            navItems.add(item);
            navItemByPage.put(spec.page(), item);   // 1.2.6：QA 真实点击路径按页索引
            navLabels.add((Label) item.getChildren().get(1));
            list.getChildren().add(item);
        }
        nav.getChildren().add(list);

        VBox.setVgrow(navSpacer, Priority.ALWAYS);
        nav.getChildren().add(navSpacer);

        userHead = new ImageView();
        userHead.setFitWidth(34);
        userHead.setFitHeight(34);
        userHead.setSmooth(false);
        userName = new Label("无账号");
        userName.getStyleClass().add("user-name");
        userCard.getChildren().addAll(userHead, userName);
        userCard.setAlignment(Pos.CENTER_LEFT);
        userCard.setPadding(new Insets(10, 12, 10, 12));
        userCard.getStyleClass().add("user-card");
        // v0.5.3：这张卡就是账号页的入口（侧边栏没有「账号」项了）
        userCard.setCursor(Cursor.HAND);
        userCard.setOnMouseClicked(e -> openAccountPage());
        nav.getChildren().add(userCard);
        refreshUserChip();
    }

    private HBox navItem(String text, String icon, Page page) {
        HBox box = new HBox(12);
        box.getStyleClass().add("nav-item");
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(9, 12, 9, 12));
        box.setOnMouseClicked(e -> selectNavItem(box, page));

        SVGPath iconNode = Icons.createNav(icon, 20);   // 1.0.8：导航专用 even-odd 填充，细节能镂空
        iconNode.getStyleClass().add("nav-icon");
        Label label = new Label(text);
        label.getStyleClass().add("nav-label");
        box.getChildren().addAll(iconNode, label);
        return box;
    }

    private void selectNavItem(HBox target, Page page) {
        for (HBox item : navItems) {
            item.getStyleClass().remove("selected");
        }
        target.getStyleClass().add("selected");
        if (page == null) {
            // 未接功能的入口不切页：内容区保持当前页，避免「点了设置、页面却莫名变了」
            return;
        }
        // 连点同一个入口（比如一直点「启动」）不该一遍遍重播淡入：
        // 那一趟动画等于什么都没换，只是把页面从头闪一遍，很晃眼。
        if (page == currentPage) {
            return;
        }
        // 1.2.6 根因：这里绝不能先写 currentPage——showPage 用 currentPage != page
        // 判断「切走」，提前赋值会让真实导航点击路径永远跳过 hideOverlaysForPage，
        // 弹层既不会隐藏也不会恢复（1.2.5 在真机上「没修好」的根因）。
        showPage(page);
    }

    /** 点右下角账号卡打开账号页（侧边栏不再放账号入口，v0.5.3 起走这里）。 */
    private void openAccountPage() {
        // 0.9.1：与 selectNavItem 同口径——已经在账号页就别再 showPage + fadeIn，
        // 否则一直点左下角账号卡会一遍遍重播淡入，动画还会叠在一起抖。
        if (currentPage == Page.ACCOUNT) {
            return;
        }
        for (HBox item : navItems) {
            item.getStyleClass().remove("selected");
        }
        showPage(Page.ACCOUNT);
        refreshAccountPage();
    }

    /** 右下角账号卡文案：没账号显示「无账号」，有账号显示「离线账号 · 名字」。 */
    private void refreshUserChip() {
        if (userName == null) {
            return;
        }
        AccountStore.Account cur = accountStore.current();
        if (cur == null) {
            userName.setText("无账号");
        } else if (cur.type == AccountStore.Type.LITTLESKIN) {
            userName.setText("外置账号 · " + cur.name);
        } else if (cur.type == AccountStore.Type.MICROSOFT) {
            userName.setText("微软账号 · " + cur.name);
        } else {
            userName.setText("离线账号 · " + cur.name);
        }
        refreshUserHead();
        // 1.1.3：外置账号切到当前 / 首次进账号页时，后台拉一次服务器皮肤（命中 24h 缓存秒回）
        if (cur != null) {
            prefetchLittleSkinHead(cur, false);
        }
    }

    /** 账号卡头像：按当前账号皮肤渲染头部（无账号用默认史蒂夫）。 */
    private void refreshUserHead() {
        if (userHead == null) {
            return;
        }
        AccountStore.Account cur = accountStore.current();
        LittleSkinSkin.Texture tex = littleSkinTexture(cur);
        userHead.setImage(tex != null ? SkinRender.head(tex.png) : SkinRender.headOf(cur));
    }

    /** 通用：账号皮肤头部 ImageView（正方形、像素清晰）。 */
    private ImageView skinHeadView(AccountStore.Account acc, int size) {
        LittleSkinSkin.Texture tex = littleSkinTexture(acc);
        ImageView iv = new ImageView(tex != null ? SkinRender.head(tex.png) : SkinRender.headOf(acc));
        iv.setFitWidth(size);
        iv.setFitHeight(size);
        iv.setSmooth(false);
        return iv;
    }

    /** 1.1.3：取内存里已拉到的 LITTLESKIN 真实皮肤；非外置账号 / 尚未拉到返回 null。 */
    private LittleSkinSkin.Texture littleSkinTexture(AccountStore.Account acc) {
        if (acc == null || acc.type != AccountStore.Type.LITTLESKIN
                || acc.uuid == null || acc.uuid.isBlank()) {
            return null;
        }
        return littleSkinTextures.get(acc.uuid);
    }

    /**
     * 1.1.3：后台预取外置账号的服务器皮肤，成功后回主线程刷账号卡头像 / 账号页 / 皮肤管理预览。
     * 已拉到的不重复拉；失败保持默认头像，只在手动刷新时弹一次提示，绝不刷屏。
     */
    private void prefetchLittleSkinHead(AccountStore.Account acc, boolean force) {
        if (acc == null || acc.type != AccountStore.Type.LITTLESKIN
                || acc.uuid == null || acc.uuid.isBlank()) {
            return;
        }
        final String uuid = acc.uuid;
        synchronized (littleSkinInFlight) {
            if (littleSkinInFlight.contains(uuid) || (!force && littleSkinTextures.containsKey(uuid))) {
                return;
            }
            littleSkinInFlight.add(uuid);
        }
        Thread t = new Thread(() -> {
            LittleSkinSkin.Texture result = LittleSkinSkin.fetchTexture(acc, force);
            synchronized (littleSkinInFlight) {
                littleSkinInFlight.remove(uuid);
            }
            try {
                Platform.runLater(() -> {
                    if (result == null) {
                        // 一段连续失败只提示一次，避免断网时每次刷新账号页都弹
                        if (littleSkinFailToasted.compareAndSet(false, true)) {
                            toast("没能从 LittleSkin 拉到皮肤，先用默认头像，稍后再试。");
                        }
                        return;
                    }
                    littleSkinFailToasted.set(false);
                    littleSkinTextures.put(uuid, result);
                    refreshUserHead();
                    refreshAccountPage();
                    // 皮肤管理只读卡片正开着且是这个账号 → 同步刷新它的头像
                    if (littleSkinServerHead != null && uuid.equals(littleSkinServerUuid)) {
                        littleSkinServerHead.setImage(SkinRender.head(result.png));
                        if (littleSkinServerDetail != null) {
                            littleSkinServerDetail.setText("当前模型："
                                    + ("slim".equals(result.model) ? "细臂（Alex）" : "经典（Steve）"));
                        }
                    }
                });
            } catch (IllegalStateException ex) {
                // 关窗过程中 toolkit 已停，忽略这最后一次回主线程
                System.out.println("[littleskin-skin] 界面已关闭，丢弃刷新回调");
            }
        }, "gbl-littleskin-skin");
        t.setDaemon(true);
        t.start();
    }

    // ---------------------------------------------------------------- 内容区

    /** 侧边栏里能真正切页的项；null 表示这个入口还没接功能（点了只高亮，内容区不动）。 */
    // v0.4.7 起「控制台」不再是个点不动的入口（游戏能真跑起来了，输出得有地方看）
    private enum Page { HOME, DOWNLOAD, GAMELIST, GAMEMGMT, CONSOLE, MULTIPLAYER, SETTINGS, ACCOUNT }   // 顺序必须和 buildPages 的添加顺序一致

    /** 侧边栏一项：文案 + 图标 + 要跳的页（page 为 null 表示暂未接功能）。 */
    private record NavItem(String text, String icon, Page page) {
    }

    private void buildPages() {
        homePage = buildContentScroll();
        downloadPage = buildDownloadPage();
        gameListPage = buildGameListPage();
        gameManagePage = buildGameManagePage();
        consolePage = buildConsolePage();
        multiplayerView = new MultiplayerPage();
        multiplayerView.setToastSink(ShellView.this::toast);   // 1.2.5：统一窗口右下角
        // 1.2.5：联机页内部打开/关闭弹层才清涟漪；父界面切页不接这个 hook。
        multiplayerView.setOverlayRippleCancel(this::cancelAllRipplesForMultiplayer);
        // 1.2.6：联机页自己的模态/忙态遮罩不在 modalHost 里，必须登记到全局表才能跨页恢复。
        multiplayerView.bindOverlayRegistry(overlayRegistry, Page.MULTIPLAYER.ordinal());
        multiplayerPage = multiplayerView.getView();
        settingsPage = buildSettingsPage();
        accountPage = buildAccountPage();
        pages.getChildren().addAll(
                homePage, downloadPage, gameListPage, gameManagePage, consolePage, multiplayerPage, settingsPage,
                accountPage);
        // 进度出口必须在这些页面都建好之后再挂：回调会去刷新游戏列表和版本列表，
        // 挂早了第一次回调就可能打在还没建起来的容器上
        GameDownloader.DEFAULT.setSink(item -> javafx.application.Platform.runLater(
                () -> onDownloadUpdate(item)));
        // 0.9.1：内容任务（模组 / 整合包 / 资源包 / 光影 / 世界 / 加载器）的 Sink 也在这里注册。
        // 原先挂在 buildQueueTab() 里，而「下载队列」是懒加载的：用户从内容页装东西却从没打开过
        // 队列页时 sink 为 null，emit() 直接 return，DONE 行永远留在队列里不自动移出。
        // 和上面那条并列放在 buildPages 末尾 → 应用启动即注册，与是否打开过队列页无关。
        DownloadCenter.DEFAULT.setSink(it -> Platform.runLater(() -> onContentUpdate(it)));
        showPage(Page.HOME);
    }

    /**
     * 进度到了界面上：先把状态记下来，再决定要重画哪几块。
     *
     * <p>刷新不是无脑全刷——版本列表那一屏 194 行，进度每 220 毫秒吐一次，
     * 每次都整屏重建的话只是进度条在动，却把整个列表反复重画（看着卡的其实是这个）。
     * 所以只在「状态变了」时才重画版本行，进度数字只往队列那几行走。
     */
    private final Map<String, GameDownloader.State> downloadStates = new java.util.HashMap<>();

    private void onDownloadUpdate(GameDownloader.Item item) {
        GameDownloader.State state = item.state();
        GameDownloader.State before = downloadStates.get(item.id());
        boolean changed = before != state;
        downloadStates.put(item.id(), state);

        if (state == GameDownloader.State.DONE) {
            // 下完落盘了，游戏列表里那一行得跟着长出来（扫盘才是真相，不往内存里塞）
            refreshGames();
            System.out.println("[download] 「" + item.id() + "」" + item.note());
        } else if (state == GameDownloader.State.FAILED) {
            System.err.println("[download] 「" + item.id() + "」下不下来：" + item.note());
        }
        if (changed) {
            refreshVersionList();   // 按钮文案（下载 / 下载中 / 已安装 / 重试）要跟着变
        }
        // 只在「这一轮真下完」时报完成：before != null 说明它进过队列，
        // 而「已经装好了」那条是磁盘上早就有的短路，不该恭喜一遍
        if (changed && state == GameDownloader.State.DONE && before != null
                && !"已经装好了".equals(item.note())) {
            toast(item.id() + "下载完成！");
        }
        // 下完的东西不用一直挂在队列里占地方（v0.4.2）：先让这行「装好了 · 36 MB」
        // 亮一眼，再自己走人。重排同一个 id 时这里重复挂一次是无害的（里面按 id 去重）
        if (state == GameDownloader.State.DONE && !"已经装好了".equals(item.note())) {
            scheduleQueueDrop(item.id());
        }
        refreshQueueList();         // 队列行就那么几条，进度条随便刷
    }

    /** 下完这一项在队列里留多久。太短看不见「装好了」，太长队列就攒着不走了。 */
    private static final Duration QUEUE_DROP_DELAY = Duration.seconds(1.5);

    /** 已经排过自动移出的 id，防止同一个任务挂两层定时器。 */
    private final java.util.Set<String> dropScheduled = new java.util.LinkedHashSet<>();

    /**
     * 排一个「下完就移出队列」的定时器。
     *
     * <p>移出走 {@link GameDownloader#remove}，它顺手 {@code pump()} 把下一个排队项顶上去——
     * 队列该自己往前走，不要等人去点「清空」。
     */
    private void scheduleQueueDrop(String id) {
        scheduleQueueDrop(id, () -> dropDoneItem(id));
    }

    /**
     * 排一个「下完就移出队列」的定时器，到点执行给定 drop。
     * 原版游戏与内容下载共用同一套去重与延时逻辑（1.5s 让「装好了」亮一眼）。
     */
    private void scheduleQueueDrop(String id, Runnable drop) {
        if (!dropScheduled.add(id)) {
            return;
        }
        PauseTransition hold = new PauseTransition(QUEUE_DROP_DELAY);
        hold.setOnFinished(e -> {
            dropScheduled.remove(id);
            javafx.application.Platform.runLater(drop);
        });
        hold.play();
    }

    /**
     * 0.9.0 修复：内容（模组 / 整合包 / 资源包 / 光影 / 世界）装完（DONE）也像原版游戏一样
     * 自动移出下载队列——对齐用户预期。CANCELLED（手动移出 / 取消）不重复处理。
     */
    private void onContentUpdate(DownloadCenter.Item item) {
        if (item.state() == DownloadCenter.State.DONE) {
            scheduleQueueDrop(item.id(), () -> dropContentDone(item.id()));
        }
        refreshQueueList();
    }

    /** 到点把装完的内容项从队列拿掉（与 {@link #dropDoneItem} 对称）。 */
    private void dropContentDone(String id) {
        DownloadCenter.Item item = DownloadCenter.DEFAULT.item(id);
        if (item == null || item.state() != DownloadCenter.State.DONE) {
            return;   // 已经不在队列里 / 又被重排，别插手
        }
        DownloadCenter.DEFAULT.remove(id);
        refreshQueueList();
        System.out.println("[download] 「" + id + "」装好了，自动移出下载队列（内容队列还有 "
                + DownloadCenter.DEFAULT.items().size() + " 项）");
    }

    /**
     * 把下完那一项从队列里拿掉。
     *
     * <p>到点还得再看一眼状态：这 1.5 秒里用户可能手动「移出」过（任务已经没了），
     * 也可能又点了一次「下载」重排了同一版——两种情况都别去动它。
     */
    private void dropDoneItem(String id) {
        dropScheduled.remove(id);
        GameDownloader.Item item = GameDownloader.DEFAULT.item(id);
        if (item == null || item.state() != GameDownloader.State.DONE) {
            return;   // 已经不在队列里 / 又被重排，别插手
        }
        GameDownloader.DEFAULT.remove(id);
        refreshVersionList();      // 行上那颗按钮重新可点（别的版本还能下）
        refreshQueueList();        // 这一行从页面上拿掉
        System.out.println("[download] 「" + id + "」装好了，自动移出下载队列（队列还有 "
                + GameDownloader.DEFAULT.items().size() + " 项）");
    }

    // -------------------------------------------------------------- 左下角临时提示（v0.4.1）

    /**
     * 1.2.5 提示层：纠正回 v0.4.1 的窗口右下角，跟着窗口走而不是跟着某一页走；
     * 深灰黑圆角长条 + 白字沿用既有 .toast，2s + 淡出、整层鼠标穿透。
     * 整层鼠标穿透，不盖顶栏/导航/内容按钮；深浅主题沿用 .toast 原样式。
     */
    private Node buildToastLayer() {
        toastBox = new VBox(8);
        toastBox.setAlignment(Pos.BOTTOM_RIGHT);
        toastBox.setPadding(new Insets(0, 18, 18, 0));
        // StackPane 会按可用空间把子节点抻满，锁成 pref 尺寸它才肯老老实实贴着左下角（v0.4.1 原形）
        toastBox.setMaxWidth(Region.USE_PREF_SIZE);
        toastBox.setMaxHeight(Region.USE_PREF_SIZE);
        // 别挡点击：底下那片界面照旧能点（v0.3.2 的遮罩教训：能挡的那层必须是透明的）
        toastBox.setMouseTransparent(true);
        StackPane layer = new StackPane(toastBox);
        StackPane.setAlignment(toastBox, Pos.BOTTOM_RIGHT);
        layer.setPickOnBounds(false);
        layer.setMouseTransparent(true);
        return layer;
    }

    /** 弹一条提示，2 秒后自己淡出消失。已在 FX 线程上；不是的话自己送过来。 */
    private void toast(String text) {
        if (toastBox == null) {
            return;
        }
        Label bubble = new Label(text);
        bubble.getStyleClass().add("toast");
        bubble.setMouseTransparent(true);
        bubble.setPickOnBounds(false);
        System.out.println("[toast] " + text);
        lastToastTextForTests = text;
        toastBox.getChildren().add(bubble);
        PauseTransition life = new PauseTransition(Duration.seconds(2));
        life.setOnFinished(e -> {
            FadeTransition fade = new FadeTransition(Duration.millis(240), bubble);
            fade.setFromValue(1.0);
            fade.setToValue(0.0);
            fade.setOnFinished(ev -> toastBox.getChildren().remove(bubble));
            fade.play();
        });
        life.play();
    }

    private void showPage(Page page) {
        // 1.2.5 纠偏：切换父界面（主菜单的账号/下载/启动等）允许按钮特效完整播完。
        // 全局 showPage 与「离开联机页」都不得清涟漪；
        // 只有联机页内部打开/关闭弹层才由 MultiplayerPage 的 hook 清涟漪。
        if (currentPage != null && currentPage != page) {
            hideOverlaysForPage(currentPage);   // 1.2.5：隐藏但不 dispose 当前页弹层
        }
        // 初始进入也走这里，先把当前页记下来，selectNavItem 的「连点不重播」才有基准
        currentPage = page;
        // 多人联机页切页钩子：用 lastShownPage 保证「离开只收一次、切到只唤一次」，不在 for 里对每个非当前页调 onHidden
        if (lastShownPage == Page.MULTIPLAYER && page != Page.MULTIPLAYER && multiplayerView != null) {
            multiplayerView.onHidden();   // 只退订/停 toast 动画，不清涟漪
        }
        // 顺序与 Page 枚举一一对应，下标直接取 ordinal
        Node[] all = {homePage, downloadPage, gameListPage, gameManagePage, consolePage, multiplayerPage, settingsPage,
                accountPage};
        for (int i = 0; i < all.length; i++) {
            boolean on = page.ordinal() == i;
            all[i].setVisible(on);
            all[i].setManaged(on);
        }
        // 进游戏管理页刷新状态卡
        if (page == Page.GAMEMGMT) {
            refreshGameManage();
        }
        // 首次切到多人页时后台拉起联机引擎（非阻塞），构造期绝不下发 / 下载
        if (page == Page.MULTIPLAYER && multiplayerView != null) {
            // 1.2.6：onShown 内部会刷新状态（可能顺手关忙态），必须先冻结弹层可见性，
            // 等恢复完成后再解冻——否则 1.2.5 的「假通过」会原样复发。
            multiplayerView.setOverlayRefreshSuspended(true);
            multiplayerView.onShown();
            restoreOverlaysForPage(page);   // 1.2.6：登记表仍在 → 原样恢复（含输入内容/状态）
            multiplayerView.setOverlayRefreshSuspended(false);
        } else {
            restoreOverlaysForPage(page);   // 1.2.6：ShellView 确认弹窗/安装弹窗/启动遮罩同样恢复
        }
        lastShownPage = page;
        fadeIn(all[page.ordinal()]);
    }

    // ------------------------------------------------ 1.2.6 弹层跨页隐藏/恢复（全局登记表）

    /**
     * 切走：把该页仍登记且仍打开的弹层隐藏（不是关闭），保存内容。
     * 不再遍历 modalHost 子树——联机页自己的模态/忙态、skinPrompt 等都能覆盖。
     */
    private void hideOverlaysForPage(Page page) {
        overlayRegistry.hidePage(page.ordinal());
    }

    /** 切回：对仍在登记表里的层恢复可见性并还原内容。已注销（真关闭）的自然不复活。 */
    private void restoreOverlaysForPage(Page page) {
        overlayRegistry.restorePage(page.ordinal());
    }

    /**
     * 1.2.6 统一打开弹层：先显示再登记，页面归属取当前页。
     * 名字唯一（重复 show 幂等覆盖），close 时必须调 {@link #hideOverlayLayer}。
     */
    private void showOverlayLayer(Node layer, String name) {
        if (layer == null) {
            return;
        }
        layer.setVisible(true);
        layer.setManaged(true);
        int ordinal = currentPage == null ? Page.HOME.ordinal() : currentPage.ordinal();
        overlayRegistry.register(ordinal, name, layer, new OverlayRegistry.FlagController(true));
    }

    /** 1.2.6 统一关闭弹层：先注销再隐藏，切回绝不复活。 */
    private void hideOverlayLayer(Node layer, String name) {
        if (layer == null) {
            return;
        }
        overlayRegistry.unregister(name);
        layer.setVisible(false);
        layer.setManaged(false);   // 1.3.1 修复轮：真关闭后不再参与 centerHost 尺寸计算
    }

    /** QA 钩子：按页名切页（Page 是 private 枚举，测试用字符串名）。 */
    public void switchPageForTests(String pageName) {
        showPage(Page.valueOf(pageName));
    }

    /**
     * 1.2.6 QA 钩子：走<b>真实导航点击路径</b>切页（不直接调用 showPage）。
     * 账号页没有侧栏项，走右下角账号卡的真实 handler。
     */
    public void switchPageViaNavForTests(String pageName) {
        Page target = Page.valueOf(pageName);
        if (target == Page.ACCOUNT) {
            if (userCard.getOnMouseClicked() != null) {
                userCard.getOnMouseClicked().handle(null);
            }
            return;
        }
        HBox item = navItemByPage.get(target);
        if (item != null && item.getOnMouseClicked() != null) {
            item.getOnMouseClicked().handle(null);
        }
    }

    /** QA 钩子：全局登记表当前层数量。 */
    public int overlayRegistrySizeForTests() {
        return overlayRegistry.size();
    }

    /** QA 钩子：某页登记层摘要（name:open/hidden|…）。 */
    public String registeredOverlaysForPageForTests(String pageName) {
        return overlayRegistry.summaryForPage(Page.valueOf(pageName).ordinal());
    }

    /** QA 钩子：拿到真实联机页实例（几何探针用）。 */
    public MultiplayerPage multiplayerViewForTests() {
        return multiplayerView;
    }

    /** QA 钩子：打开安装新游戏弹窗（真实构建路径，不发起网络请求）。 */
    public void openInstallDialogForTests() {
        openInstallDialog(new VersionManifest.OfficialVersion(
                "1.21.1", VersionManifest.Kind.RELEASE, Instant.now(), ""));
    }

    /** QA 钩子：安装新游戏卡片实际布局宽高 [w, h]。 */
    public double[] installCardBoundsForTests() {
        if (installCard == null) {
            return new double[]{0, 0};
        }
        return new double[]{installCard.getLayoutBounds().getWidth(),
                installCard.getLayoutBounds().getHeight()};
    }

    /** QA 钩子：某页当前有没有被切换隐藏的层。 */
    public boolean hasOverlaySnapshotForTests(String pageName) {
        return overlayRegistry.hasHiddenForPage(Page.valueOf(pageName).ordinal());
    }

    /** QA 钩子：某页被切换隐藏的层数量。 */
    public int overlayLayerCountForTests(String pageName) {
        return overlayRegistry.hiddenCountForPage(Page.valueOf(pageName).ordinal());
    }

    /** 1.2.7 QA：update-dialog 模态遮罩此刻是否可见（真实节点读数）。 */
    public static boolean isUpdateDialogVisibleForTests() {
        ShellView s = lastCreatedForTests;
        return s != null && s.updateDialog != null && s.updateDialog.isVisible();
    }

    /** 1.2.7 QA：最近一条右下角短提示文案。 */
    public static String lastToastTextForTests() {
        return lastToastTextForTests;
    }

    /** 1.2.7 QA：当前还挂着几条 toast 气泡（验证自动消失）。 */
    public static int toastCountForTests() {
        ShellView s = lastCreatedForTests;
        return s == null || s.toastBox == null ? 0 : s.toastBox.getChildren().size();
    }

    /** 1.2.7 QA：模拟一次启动检查失败，走与真实链路完全相同的非阻断钩子。 */
    public static void simulateAutoCheckFailureForTests(String reason) {
        ShellView s = lastCreatedForTests;
        if (s != null) {
            s.onAutoCheckFailed(reason);
        }
    }

    /**
     * 1.2.7：全局非阻断短提示，供 LauncherApp 启动期（更新结果 / 更新标记）反馈使用。
     * shell 尚未建好时只记日志，绝不弹原生 Alert；FX 线程保护 ObservableList 变更。
     */
    public static void toastGlobal(String text) {
        Runnable r = () -> {
            ShellView s = lastCreatedForTests;
            if (s != null) {
                s.toast(text);
            } else {
                System.out.println("[toast-global] " + text);
            }
        };
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    /** 1.2.7 QA：模拟启动期更新失败反馈（走真实 toastGlobal，绝不弹原生框）。 */
    public static void simulateDeferredUpdateFailureForTests(String reason) {
        System.out.println("[deferred] " + reason);
        toastGlobal("上次自动更新未完成，已跳过；可在设置中检查更新");
    }

    /** 1.2.7 QA：走真实取消按钮 fire。 */
    public void clickUpdateCancelForTests() {
        if (updateDlgCancelBtn != null) {
            updateDlgCancelBtn.fire();
        }
    }

    /** 1.2.7 QA：走真实遮罩 handler。 */
    public void clickUpdateBackdropForTests() {
        if (updateDlgBackdrop != null && updateDlgBackdrop.getOnMouseClicked() != null) {
            updateDlgBackdrop.getOnMouseClicked().handle(null);
        }
    }

    /** 1.2.7 QA：走真实手动下载 handler（抑制浏览器启动，只验关框）。 */
    public void clickUpdateManualLinkForTests() {
        suppressOpenBrowserForTests = true;
        try {
            if (updateDlgManualLink != null && updateDlgManualLink.getOnAction() != null) {
                updateDlgManualLink.getOnAction().handle(null);
            }
        } finally {
            suppressOpenBrowserForTests = false;
        }
    }

    /**
     * 切页淡入。reduceMotion 打开时直接落终态——无障碍开关不能只是个摆设，
     * 挂了以后动效必须真的停。
     */
    private void fadeIn(Node page) {
        // 0.9.1：先把上一趟没播完的淡入停掉，再播新的。连点切页时两条 Timeline 会
        // 同时改同一个 opacityProperty，页面忽明忽暗看着就是「抖」。
        if (pageFade != null) {
            pageFade.stop();
            pageFade = null;
        }
        // 0.9.4 r2 C1：上一趟淡入若被 stop 打断，onFinished 不会触发，这里复位它的临时 cache。
        if (fadeCacheOwner != null) {
            fadeCacheOwner.setCache(false);
            fadeCacheOwner = null;
        }
        if (settings.reduceMotion()) {
            page.setOpacity(1.0);
            return;
        }
        page.setOpacity(0.0);
        // 0.9.4 r2 C1：淡入期间临时开缓存。shell 根节点挂着全局 SHADOW，子树每帧变化会
        // 触发整窗重合成；只在 160ms 淡入里缓存，播完立刻关掉（常驻 cache 反而更慢）。
        // 1.1.5：联机页有活跃状态刷新与折叠动画，临时整页缓存会叠成合成卡顿；
        // 只跳过这一页的临时 cache，其余页与 stop / onFinished 复位逻辑不动。
        if (page != multiplayerPage) {
            page.setCache(true);
            page.setCacheHint(CacheHint.SPEED);
            fadeCacheOwner = page;
        }
        Timeline fade = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(page.opacityProperty(), 0.0)),
                new KeyFrame(Duration.millis(160), new KeyValue(page.opacityProperty(), 1.0)));
        fade.setOnFinished(e -> {
            page.setCache(false);
            if (fadeCacheOwner == page) {
                fadeCacheOwner = null;
            }
        });
        pageFade = fade;
        fade.play();
    }

    // ------------------------------------------------------- 下载页 / 版本列表（v0.3）

    /**
     * 下载页顶部那一排 tab，顺序就是用户看到的从左到右。
     *
     * <p>「游戏」（下标 0）是官方真实版本清单，「下载队列」（下标 7）是用户自己排的队——
     * 两个名字必须能一眼分清：前者是「有什么可下的」，后者是「我打算下哪些」。
     * 资源包 / 光影 / 世界（下标 4/5/6）自 0.8.7 起是真实内容页，走 {@link #buildContentTab(int)}。
     *
     * <p>这八项是整页的形状，不是八个页面——侧边导航只有四条，下载页内部自己切。
     */
    private static final List<String> DOWNLOAD_TABS = List.of(
            "游戏", "加载器", "模组", "整合包", "资源包", "光影", "世界", "下载队列");

    /** 类型筛选胶囊：节点 + 它代表的类型（换状态靠类名，不能靠下标认人）。 */
    private record FilterChip(Pane node, VersionManifest.Kind kind) {
    }

    /**
     * 下载页：顶部一排 tab，下面是当前 tab 的内容。
     *
     * <p>返回 ScrollPane 而不是 VBox：版本列表有 11 条，装不下就得滚，
     * 和首页、设置页同构（都是 {@code content-scroll}），自测按类名认页也才认得出。
     */
    private ScrollPane buildDownloadPage() {
        VBox page = new VBox(16);
        page.getStyleClass().add("download-page");
        page.setPadding(new Insets(20));
        page.setMaxWidth(1180);

        HBox tabs = new HBox(8);
        tabs.getStyleClass().add("tab-bar");
        for (String name : DOWNLOAD_TABS) {
            Pane tab = tabBtn(name);
            // 自测按「第 n 个 tab-btn」点、showDownloadTab 也靠这个列表认下标，
            // 只塞进 tab 条不登记 = 七个 tab 全是死的（v0.3 初版就漏在这里）
            downloadTabs.add(tab);
            tabs.getChildren().add(tab);
        }

        downloadTabBody = new VBox(16);
        // tab 条和 body 必须显式挂进 page，缺一行整页就是空白
        page.getChildren().addAll(tabs, downloadTabBody);
        // 打开就自动同步：v0.4.2 没有「检查更新」按钮可点，这一次就是「每次打开都会更新」
        showDownloadTab(0);
        // 启动时先拿缓存顶上（applyVersionScope 读的是缓存），再在后台同步成最新的
        applyVersionScope();

        StackPage wrap = new StackPage(page);
        ScrollPane scroll = new ScrollPane(wrap);
        scroll.getStyleClass().add("content-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        return scroll;
    }

    /**
     * 切 tab。整块 body 换掉，不叠八层页面——同一块区域同时存在八份内容只会互相挡。
     *
     * <p>状态类名整份 {@code setAll}：{@code getStyleClass()} 是允许重复的集合，
     * 只 remove 一个的话连点会把 {@code selected} 攒成一片。
     */
    private void showDownloadTab(int index) {
        // 0.9.4 r2 B2/A3：先记下当前可见 tab（refreshModCenter 据此只刷一个），
        // 再令切页 epoch 自增，所有在飞的异步回填回来时都会发现过期并整份丢弃。
        currentDownloadTab = index;
        contentEpoch++;
        // 0.8.8：切下载 tab 清空选中版本令牌（kind 已随 InstallCtx 走，无全局残留）。
        resetDetailState();
        // 回到「游戏」这一页就顺手把官方清单拉一遍（v0.4.2 把「检查更新」按钮换成了这个）：
        // 打开就自动更新，不用用户自己去点什么。
        if (index == 0) {
            requestManifestSync();
        }
        for (int i = 0; i < downloadTabs.size(); i++) {
            Node tab = downloadTabs.get(i);
            // 下标就是顺序，别按文字认：以后加 tab 不用回头改自测
            final int at = i;
            tab.setOnMouseClicked(e -> showDownloadTab(at));
            tab.getStyleClass().setAll("tab-btn");
            if (i == index) {
                tab.getStyleClass().add("selected");
            }
        }
        downloadTabBody.getChildren().setAll(bodyOfDownloadTab(index));
    }

    /**
     * 某个 tab 要装什么。
     *
     * <p>路由必须收在这里、并且最后一个 tab 要指到 {@link #buildQueueTab()}——
     * 曾经这里只判 {@code index == 0}，最后那个 tab 点下去弹出的是「功能还没做」的空页，
     * 队列明明排了东西却一页都看不到（v0.3.5 首跑自测抓的就是这个）。
     *
     * <p>下标 1..6 = 加载器 / 模组 / 整合包 / 资源包 / 光影 / 世界，自 0.9.6 r3 起
     * 全部共用同一条 {@link #modToolbar()} 工具条（当前游戏 / 下载源 / 状态文字 / 导入行）；
     * 三个内容页不再自建简化头部。进这六块前先 {@link #refreshModCenter()} 懒刷新
     * （磁盘扫 + 回填单例下拉 + 状态文字），工具条随 body 一起挂上去。
     * {@link #showDownloadTab(int)} 用 {@code setAll} 保证同一时刻只有一个 body 在场景树里，
     * 因此复用单例节点不会撞 parent。
     */
    private Node bodyOfDownloadTab(int index) {
        // r13：内容 tab 的 body 是懒建的，首帧之后才进场景；补一次浮动滚动条安装（幂等）。
        javafx.application.Platform.runLater(this::installFloatScrollBars);
        int last = DOWNLOAD_TABS.size() - 1;
        if (index == 0) {
            return buildGameTab();
        }
        if (index == last) {
            return buildQueueTab();
        }
        // 0.9.6 r3：1 / 2 / 3 = 加载器 / 模组 / 整合包（原 Mod 中心三块，v0.7.0 并入），
        // 4 / 5 / 6 = 资源包 / 光影 / 世界（0.8.7 阶段A 起的真实内容页）。
        // r3 起这六个 tab 统一套 modToolbar()，三个内容页共用同一工具条，不再走简化头部；
        // 进入前统一 refreshModCenter() 回填单例下拉初值与状态文字。
        if (index >= 1 && index <= 6) {
            // 0.9.6 r4：先把 4 个单例从旧父容器摘下，否则第 2 次起进入 1..6 tab 会因
            // duplicate children added 构建中断、页面停在上一 tab（详见 detachModToolbarSingletons）。
            detachModToolbarSingletons();
            refreshModCenter();
            Node body = switch (index) {
                case 1 -> buildLoaderTab();
                case 2 -> buildModTab();
                case 3 -> buildModpackTab();
                default -> buildContentTab(index);
            };
            return new VBox(12, modToolbar(), body);
        }
        return buildTabEmpty(DOWNLOAD_TABS.get(index));
    }

    /**
     * 0.9.6 r3 起下载页 1..6 六个 tab 共用的工具条：当前游戏 + 下载源 + 状态。
     *
     * <p>节点字段（{@code modGameBox / modSourceBox / modCenterStatus / modImportRow}）是复用的单例，
     * 每次切 tab 重新拼一层容器即可——JavaFX 节点从旧父容器里自动摘下。
     * r3 起资源包 / 光影 / 世界 三个内容页也走这里，工具条与模组/整合包完全一致。
     */
    private Node modToolbar() {
        System.err.println("[096r3] 0.9.6 r3 工具条统一");
        initModControls();
        Label gameLabel = new Label("当前游戏");
        gameLabel.getStyleClass().add("page-desc");
        Label sourceLabel = new Label("下载源");
        sourceLabel.getStyleClass().add("page-desc");
        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox top = new HBox(10, gameLabel, modGameBox, sourceLabel, modSourceBox, gap,
                modCenterStatus);
        top.setAlignment(Pos.CENTER_LEFT);
        // 0.9.6 r3 A5（零回归版）：整合包导入进度行只属于「整合包」tab，三个内容页（4..6）
        // 不把它挂进工具条的第二行，避免「正在导入整合包」的进度串到资源包/光影/世界页。
        // 用「不挂载」而不是 setVisible(false)/setManaged(false)：modImportRow 是单例，它的
        // visible/managed 由 runModpackImport() 在导入开始 / finally 各设一次；若在内容页强行
        // 隐藏，导入中途切到内容页再切回整合包 tab 时进度行不会恢复，形成回归。
        // 不挂载既不改单例状态，切回整合包 tab 时本方法会重新 new VBox 把它挂回，进度原样可见。
        VBox bar = (currentDownloadTab >= 4 && currentDownloadTab <= 6)
                ? new VBox(8, top)
                : new VBox(8, top, modImportRow);
        bar.getStyleClass().addAll("card", "mod-toolbar");
        bar.setPadding(new Insets(12, 16, 12, 16));
        return bar;
    }

    /** 0.9.6 r4：单例控件每次进入 tab 前先从旧父节点摘下，
     *  否则 JavaFX 21 对已有 parent 的节点会抛 duplicate children added，
     *  导致第 2 次起进入 1..6 tab 直接构建中断、页面停在上一 tab。 */
    private void detachModToolbarSingletons() {
        for (Node n : new Node[]{modGameBox, modSourceBox, modCenterStatus, modImportRow}) {
            if (n != null && n.getParent() instanceof Pane p) p.getChildren().remove(n);
        }
    }

    /**
     * 一枚 tab 药丸。
     *
     * <p>刻意不用 {@code seg-btn}：设置页那两个分组药丸是自测按「第 n 个 seg-btn」点的
     * （{@code WIN_GROUP_CLICK}），共用类名的话「窗口行为」会被 tab 顶掉，自测就点错人了。
     */
    private static Pane tabBtn(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("tab-btn-label");

        HBox button = new HBox(label);
        button.getStyleClass().add("tab-btn");
        button.setCursor(Cursor.HAND);
        button.setAlignment(Pos.CENTER);
        button.setPadding(new Insets(0, 14, 0, 14));
        button.setPrefHeight(34);
        button.setMinHeight(34);
        button.setFocusTraversable(true);
        return button;
    }

    /** 「游戏」页：搜索 + 类型筛选 + 版本列表。搜索和筛选都不改顺序，顺序由日期定。 */
    private Node buildGameTab() {
        filterChips.clear();

        VBox bar = new VBox(10);
        bar.getStyleClass().addAll("card", "filter-bar");
        bar.setPadding(new Insets(14, 16, 14, 16));

        versionSearch = new TextField();
        versionSearch.setPromptText("搜索版本号 / 版本名称");
        versionSearch.getStyleClass().add("version-search");
        versionSearch.setPrefHeight(34);
        versionSearch.setMinHeight(34);
        HBox.setHgrow(versionSearch, Priority.ALWAYS);
        versionSearch.textProperty().addListener((obs, ov, nv) -> refreshVersionList());

        HBox chips = new HBox(8);
        chips.setAlignment(Pos.CENTER_LEFT);
        for (VersionManifest.Kind kind : VersionManifest.Kind.values()) {
            FilterChip chip = filterChip(kind);
            filterChips.add(chip);
            chips.getChildren().add(chip.node());
        }

        versionCount = new Label();
        versionCount.getStyleClass().add("result-count");
        Region tail = filler();
        HBox.setHgrow(tail, Priority.ALWAYS);

        HBox second = new HBox(12);
        second.setAlignment(Pos.CENTER_LEFT);
        second.getChildren().addAll(chips, tail, versionCount);
        bar.getChildren().addAll(versionSearch, second);

        bar.getChildren().addAll(buildManifestBar());

        versionList = new VBox(2);
        refreshVersionList();

        VBox body = new VBox(14);
        body.getChildren().addAll(bar, versionList);
        return body;
    }

    /**
     * 状态条：这一屏的数据从哪来、什么时候同步的。
     *
     * <p>「显示全部版本」这个开关也挤在这一行——它控制的是同一批数据的展现粒度，
     * 离版本列表近才不会看着像两个功能。
     *
     * <p>v0.4.2 把「检查更新」这枚按钮删了：清单每次打开都自己同步（见
     * {@link #requestManifestSync}），再摆一枚按钮就是让人去点一个本来就会自动发生的事。
     *
     * <p>状态必须是文字而不是图标：离线时列表还在（用的是缓存），
     * 用户得靠这句话知道自己看的是多久以前的。
     */
    private List<Node> buildManifestBar() {
        // 初值取上一次的结果，不写死「正在取」：切走再切回「游戏」页时整块 body 会重建，
        // 写死的话状态条永远停在「正在取官方清单…」，而底下的列表早就满了（v0.3.5 自测抓到）
        manifestState = new Label(manifestSummary);
        manifestState.getStyleClass().add("manifest-state");
        HBox.setHgrow(manifestState, Priority.ALWAYS);

        ToggleSwitch all = new ToggleSwitch(false);
        all.getStyleClass().add("show-all");
        // 初值必须走 setSelected 而不是构造参数：和状态条一个病根，
        // 「显示全部」也得跨 body 重建留住，切走再切回来不该变回几十条
        all.setSelected(showAllAll);
        Label allLabel = new Label("显示全部 ");
        allLabel.getStyleClass().add("switch-label");
        HBox allBox = new HBox(8);
        allBox.setAlignment(Pos.CENTER_LEFT);
        allBox.getChildren().addAll(all, allLabel);
        // 开关的状态存在控件自己身上（setSelected 而不是字段），刷新列表时才读得到
        all.selectedProperty().addListener((obs, ov, nv) -> {
            showAllAll = nv;   // 记下来，body 重建时才有得填
            applyVersionScope();
        });
        showAllSwitch = all;

        HBox status = new HBox(12);
        status.setAlignment(Pos.CENTER_LEFT);
        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);
        status.getChildren().addAll(manifestState, gap, allBox);
        return List.of(status);
    }

    /**
     * 按「显示全部 / 只露最近」重新取这一屏的版本。
     *
     * <p>两个口径都走服务层：默认视图是「正式版 + 最近 30 快照」，全量是 900+ 条；
     * 界面只认 {@link #versionRows}，换口径不碰渲染。
     */
    private void applyVersionScope() {
        boolean all = showAllSwitch != null && showAllSwitch.getSelected();
        List<VersionManifest.OfficialVersion> next =
                all ? VersionManifest.allView() : VersionManifest.defaultView();
        // 拿到 0 条就别把旧的那份掀了：后台刚同步完清单、正好这一帧被下载完成那一下
        // 触发的整屏刷新插进来，就会看到 194 行「唰」地空成一张「没有匹配的版本」卡
        // （v0.4 自测连着两次撞见：上一项下完，下一行根本找不到「下载」按钮）
        if (next == null || next.isEmpty()) {
            System.err.println("[download] 官方清单这次拿到 0 条，界面照旧用上一份，不清空");
            refreshVersionList();
            return;
        }
        versionRows = next;
        refreshVersionList();
    }

    /**
     * 请求同步一次官方清单。
     *
     * <p>「每次打开都自动更新」就是走到这里（启动那一次由 {@code buildDownloadPage} 触发，
     * 之后每次回到「游戏」tab 也来一次）。换页换得勤的时候可能连着喊好几声，
     * 所以这里做一次合并：同步在飞就记一笔「还要再来一次」，等这趟落地再补跑——
     * 否则连切几下就是七八条请求一起往 Official 打。
     */
    private void requestManifestSync() {
        if (manifestFetching) {
            manifestAgain = true;
            return;
        }
        refreshManifest(false);
    }

    /**
     * 拉一次官方清单，顺手把界面刷成新数据。
     *
     * <p>网络在后台线程里跑，拿回来再 {@code runLater} 扔回 FX 线程——
     * 直接同步拉的话，断网那 8 秒整个窗口是死的，连「正在取」都看不到。
     */
    private void refreshManifest(boolean manual) {
        manifestFetching = true;
        manifestSummary = "正在取官方清单…";
        if (manifestState != null) {
            manifestState.setText(manifestSummary);
        }
        Thread worker = new Thread(() -> {
            VersionManifest.Result result = VersionManifest.fetch();
            javafx.application.Platform.runLater(() -> applyManifest(result));
        }, "gbl-manifest");
        // 后台任务绝不能拦住 JVM 退出，也别让它背着的异常冲掉主流程
        worker.setDaemon(true);
        worker.start();
        if (manual) {
            System.out.println("[manifest] 用户点了检查更新，后台拉取中");
        }
    }

    /** 把取回来的清单落到界面上：版本列表 + 状态条 + 「新」徽标。 */
    private void applyManifest(VersionManifest.Result result) {
        officialFresh = result.fresh();
        applyVersionScope();
        String summary = result.summary();
        // 状态串留在字段上：body 重建后新那根 Label 要照着它填，不能从「正在取」起步
        manifestSummary = summary;
        if (manifestState != null) {
            manifestState.setText(summary);
        }
        System.out.println("[manifest] " + result.state() + "：" + summary
                + "，本屏 " + versionRows.size() + " 条");
        manifestFetching = false;
        // 这一趟是「顺便再拉一次」换来的：落地之后赶紧把那一趟补上，
        // 否则上次点回来时喊的那声会被吞掉，界面上停的还是旧结果
        if (manifestAgain) {
            manifestAgain = false;
            refreshManifest(false);
        }
    }

    /** 一枚类型筛选胶囊（多选）。一个都没选 = 不过滤，别空手把列表清成 0 条。 */
    private FilterChip filterChip(VersionManifest.Kind kind) {
        Label label = new Label(kind.label());
        label.getStyleClass().add("filter-chip-label");

        HBox chip = new HBox(label);
        chip.getStyleClass().add("filter-chip");
        chip.setCursor(Cursor.HAND);
        chip.setAlignment(Pos.CENTER);
        chip.setPadding(new Insets(5, 12, 5, 12));

        chip.setOnMouseClicked(e -> {
            boolean on = !chip.getStyleClass().contains("on");
            chip.getStyleClass().setAll("filter-chip");   // 整份换，别攒
            if (on) {
                chip.getStyleClass().add("on");
            }
            refreshVersionList();
        });
        return new FilterChip(chip, kind);
    }

    private Set<VersionManifest.Kind> selectedTypes() {
        Set<VersionManifest.Kind> on = new LinkedHashSet<>();
        for (FilterChip chip : filterChips) {
            if (chip.node().getStyleClass().contains("on")) {
                on.add(chip.kind());
            }
        }
        return on;
    }

    /** 按当前关键词 + 类型重刷列表，并把「共 N 个版本」一起数出来。 */
    private void refreshVersionList() {
        String query = versionSearch.getText();
        query = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Set<VersionManifest.Kind> types = selectedTypes();

        List<Node> rows = new ArrayList<>();
        for (VersionManifest.OfficialVersion v : versionRows) {
            if (!types.isEmpty() && !types.contains(v.kind())) {
                continue;
            }
            // 真实清单只有版本号，搜索就按版本号来——再拿类型中文名去匹配会把
            // 「 Legacy 」这种词命中一堆谁也没见过的行
            if (!query.isEmpty() && !v.id().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            rows.add(versionRow(v));
        }
        versionList.getChildren().setAll(rows);
        versionCount.setText("共 " + rows.size() + " 个版本");
        if (rows.isEmpty()) {
            versionList.getChildren().add(emptyResult());
        }
        // 列表整个重建过，选中态得按 id 重新贴回去，不然刷新一下绿条就没了
        highlightVersionRow(selectedVersionId);
    }

    /** 绿色选中条贴到指定版本那一行上。行节点把 id 挂在 {@code UserData} 上。 */
    private void highlightVersionRow(String id) {
        for (Node row : versionList.getChildren()) {
            // v0.3.2 踩过：selected 是往类名表里 add 的，不清干净连点会攒成一片
            row.getStyleClass().remove("selected");
            if (id != null && id.equals(row.getUserData())) {
                row.getStyleClass().add("selected");
            }
        }
    }

    /**
     * 一行官方版本。整行点击＝选中（左边的绿条 + 浅绿底），右边的「下载」才是真正动手的地方。
     *
     * <p>v0.3.5 起这里的数据全是 Mojang 官方清单：真实版本号、真实类型、真实发布日期。
     * 行上没有「体积」——官方清单只给客户端地址，体积得下载完才知道，
     * 拿「约 246 MB」这种编出来的数字糊一行比留空更糟。
     *
     * <p>v0.4 这枚按钮是真下载：点一下就排进 {@link GameDownloader} 的队列，
     * 按钮自己按状态翻成「下载中 / 已安装 / 重试」。状态只从 {@link GameDownloader}
     * 读，界面不另存一份「排了哪些」——两份对不上比没有更糟。
     */
    private Node versionRow(VersionManifest.OfficialVersion v) {
        HBox row = new HBox(12);
        row.getStyleClass().add("version-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 14, 10, 16));
        row.setCursor(Cursor.HAND);
        // 选中态靠 id 认，不是靠行对象：列表整个重建过，对象早就不是原来那个了
        row.setUserData(v.id());

        VBox text = new VBox(2);
        Label number = new Label(v.id());
        number.getStyleClass().add("ver-num");
        text.getChildren().add(number);

        Label badge = new Label(v.kind().label());
        badge.getStyleClass().addAll("type-badge", "type-" + cssKind(v.kind()));

        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);

        List<Node> middle = new ArrayList<>();
        if (officialFresh.contains(v.id())) {
            Label fresh = new Label("新");
            fresh.getStyleClass().add("fresh-badge");
            middle.add(fresh);
        }
        Label date = new Label(dateText(v));
        date.getStyleClass().add("ver-date");

        String id = v.id();
        GameDownloader.Item queued = GameDownloader.DEFAULT.item(id);
        // 磁盘上装没装也要看：只看本轮队列的话，官方启动器装过的版本（正式版居多）
        // 这颗按钮还写着「下载」，点下去却在 installed() 那儿直接 return——
        // 一点反应都没有，用户看到的就是「正式版下载不了」（v0.4.1 修的就是这个）
        boolean onDisk = GameDownloader.installed(id);
        String label = downloadActionLabel(id, queued);
        Button add = new Button(label);
        add.getStyleClass().addAll("btn-download");
        add.setMinHeight(30);
        add.setMinWidth(96);
        // 磁盘上有了 / 正在下 / 下砸了，这一刻都不该再让人点：点了只会骗他以为又排了一个
        if (onDisk || (queued != null && queued.state() != GameDownloader.State.QUEUED)) {
            add.getStyleClass().add("queued");
            add.setDisable(true);
        }
        add.setOnMouseClicked(e -> {
            // 不 consume：翻选中态本来就是整行的职责，这一下顺带把当前行选上不算错
            selectVersionRow(id);
            if (GameDownloader.installed(id)) {
                return;   // 磁盘上已经有了，别为一次重复点击去动网络
            }
            openInstallDialog(v);

        });

        // 徽标那几项数量不定（「新」有时有有时没有），先攒成 List 再整批挂，
        // 别为了可变的项数去写两份 addAll
        middle.add(date);
        row.getChildren().addAll(versionBlockIcon(v.kind(), 20), text, badge, gap);
        row.getChildren().addAll(middle);
        row.getChildren().add(add);
        row.setOnMouseClicked(e -> selectVersionRow(id));
        return row;
    }

    /**
     * 行上那枚按钮该写什么。
     *
     * <p>没排过就是「下载」——v0.4 全站把「加入队列」换成「下载」，这句话说的是动作，
     * 不是「排个队等着」（那句是 v0.3.5 的口径，按钮按下去真的就在下了）。
     * 排过又砸了的还叫「下载」有点狠，给个「重试」，让人一眼看见可以再点一次。
     */
    private static String downloadActionLabel(String id, GameDownloader.Item item) {
        // 磁盘上那份优先：官方启动器装过的版本这轮没进过队列（item == null），
        // 只按 item 判会写回「下载」，而点它又是静默 return——按钮得先说实话。
        if (GameDownloader.installed(id)) {
            return "已安装";
        }
        if (item == null) {
            return "下载";
        }
        return switch (item.state()) {
            case QUEUED -> "排队中";
            case DOWNLOADING -> "下载中";
            case DONE -> "已安装";
            case FAILED -> "重试";
            case CANCELLED -> "下载";
        };
    }

    /** 类型名直接拼进 CSS 类名（{@code .type-} + 小写名）。 */
    private static String cssKind(VersionManifest.Kind kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    /** 选中行：按版本号记，绿条由 {@link #highlightVersionRow} 贴。 */
    private void selectVersionRow(String id) {
        selectedVersionId = id;
        highlightVersionRow(id);
    }

    /** 官方清单有没有发布时间。没有就老实写「时间未知」，别编一个日期。 */
    private static String dateText(VersionManifest.OfficialVersion v) {
        // 全限定：ShellView 里 Platform 这简名属于 javafx.application 那个
        return v.shown() == null ? "时间未知"
                : com.mclauncher.Platform.formatDate(v.shown().toEpochMilli());
    }

    /** 搜索/筛选把列表清空时的空结果：给一句能照做的提示，而不是干瘪的「无结果」。 */
    private Node emptyResult() {
        VBox box = new VBox(10);
        box.getStyleClass().addAll("card", "page-blank");

        SVGPath icon = Icons.create(Icons.DOWNLOAD, 32);
        icon.getStyleClass().add("page-blank-icon");
        Label title = new Label("没有匹配的版本");
        title.getStyleClass().add("page-blank-title");
        Label desc = new Label("换个关键词，或者把上方的类型筛选清掉再试。");
        desc.getStyleClass().add("page-blank-desc");
        desc.setWrapText(true);

        box.getChildren().addAll(icon, title, desc);
        return box;
    }

    /**
     * 六个还没做的数据源各自的空态卡。共用一张，只换标题里的名字——
     *「还没实现」就说还没实现，摆个界面对不上什么，比空白更让人明白。
     */
    private Node buildTabEmpty(String name) {
        VBox box = new VBox(14);
        box.getStyleClass().addAll("card", "page-blank");
        VBox.setVgrow(box, Priority.ALWAYS);

        SVGPath icon = Icons.create(Icons.DOWNLOAD, 40);
        icon.getStyleClass().add("page-blank-icon");
        Label title = new Label("「" + name + "」还没有内容");
        title.getStyleClass().add("page-blank-title");
        Label desc = new Label("这里暂时还没有内容，过一阵再来看看。");
        desc.getStyleClass().add("page-blank-desc");
        desc.setWrapText(true);

        Button back = new Button("回去看看「游戏」");
        back.getStyleClass().add("btn-ghost");
        back.setMinHeight(36);
        back.setOnMouseClicked(e -> showDownloadTab(0));

        box.getChildren().addAll(icon, title, desc, back);
        return box;
    }

    // ================================================================ 0.8.7 A-1：资源包 / 光影 / 世界 内容页

    /** tab 下标 → ContentStore.Kind。 */
    private static ContentStore.Kind contentKindOfTab(int index) {
        return switch (index) {
            case 4 -> ContentStore.Kind.RESOURCEPACK;
            case 5 -> ContentStore.Kind.SHADER;
            default -> ContentStore.Kind.WORLD;   // 6
        };
    }

    /** tab 下标 → 中文别名搜索的 context（绝不能复用不带 context 的旧 expand）。 */
    private static String contentContextOfTab(int index) {
        return switch (index) {
            case 4 -> "resourcepack";
            case 5 -> "shader";
            default -> "world";
        };
    }

    /** 版本区底部状态条：把落盘目录与光影的运行前提说清楚。 */
    private static String contentStatusHint(ContentStore.Kind kind) {
        return switch (kind) {
            case RESOURCEPACK -> "点「安装」就会装进游戏";
            case SHADER -> "点「安装」会把光影包放进 shaderpacks/ 目录；需要 OptiFine 或 Iris 才生效";
            case WORLD -> "点「安装」会解压世界到 saves/ 目录";
        };
    }

    /** 顶部说明（含数据源口径；世界 tab 另给一枚「来源：CurseForge」标签）。 */
    private static String contentTabIntro(int index) {
        return switch (index) {
            case 4 -> "逛资源包：按下载量 / 发布时间排序，挑封面点开选版本直接装到 resourcepacks/。"
                    + "来源：Modrinth（资源包）+ CurseForge。";
            case 5 -> "逛光影包：挑封面点开选版本直接装到 shaderpacks/。光影需要 OptiFine 或 Iris 才生效。"
                    + "来源：Modrinth（光影）+ CurseForge。";
            default -> "逛世界存档：挑封面点开选版本，解压到 saves/。来源：CurseForge。";
        };
    }

    /**
     * 资源包 / 光影 / 世界 三个 tab 的真实内容页（0.8.7 A-1）。
     * 骨架对齐 {@link #buildModTab()}：搜索 + 排序 + 结果网格 + 刷新 + 打开文件夹 + 空态引导；
     * 按 0.8.7 修订**不做分类胶囊**——Modrinth / CurseForge 分类体系不同，且
     * {@code CurseForgeClient.categories()} 固定 classId=CLASS_ID_MODS，混用会串出模组分类，留到 P1。
     * 0.9.6 r3：页头工具条改由 {@link #bodyOfDownloadTab(int)} 统一套 {@link #modToolbar()}，
     * 本方法只负责搜索 / 排序 / 结果网格，不再自建「当前游戏」头部。
     */
    private Node buildContentTab(int index) {
        // 0.9.9 P3：重建前停掉上一次的搜索防抖，避免旧定时器再打一次无用接口
        if (contentSearchDebounce != null) {
            contentSearchDebounce.stop();
        }
        ContentStore.Kind kind = contentKindOfTab(index);
        contentTabIndex = index;
        contentKind = kind;

        VBox box = new VBox(12);
        box.getStyleClass().add("mod-tab-body");
        box.setMaxWidth(MOD_CONTENT_WIDTH);

        Label intro = new Label(contentTabIntro(index));
        intro.getStyleClass().add("page-desc");
        intro.setWrapText(true);

        // 世界 tab 顶部单独一句「来源：CurseForge」（契约要求，别混进说明文字里）
        Label worldSource = index == 6 ? tagChip("来源：CurseForge", "tag-source") : null;

        contentSearchField = new TextField();
        contentSearchField.getStyleClass().add("version-search");
        contentSearchField.setPromptText("搜「" + DOWNLOAD_TABS.get(index) + "」…（支持中文别名）");
        contentSearchField.setPrefHeight(34);
        contentSearchField.setMaxWidth(420);
        HBox.setHgrow(contentSearchField, Priority.ALWAYS);
        Button search = new Button("搜索");
        search.getStyleClass().add("btn-download");
        search.setMinHeight(34);
        search.setOnAction(e -> {
            if (contentSearchDebounce != null) {
                contentSearchDebounce.stop();
            }
            refreshContentTab();
        });
        contentSearchField.setOnAction(e -> {
            if (contentSearchDebounce != null) {
                contentSearchDebounce.stop();
            }
            refreshContentTab();
        });
        contentSearchDebounce = new PauseTransition(Duration.millis(400));
        contentSearchDebounce.setOnFinished(e -> refreshContentTab());
        contentSearchField.textProperty().addListener((obs, oldText, newText) -> {
            if (newText != null && !newText.isBlank()) {
                contentSearchDebounce.playFromStart();
            } else if (contentSearchDebounce != null) {
                contentSearchDebounce.stop();
                if (contentResultBox != null) {
                    refreshContentTab();
                }
            }
        });

        contentSortBox = new ComboBox<>();
        wireDarkPopup(contentSortBox);   // 0.9.1：深色下拉补底，12 个下拉统一口径
        contentSortBox.getStyleClass().add("setting-select");
        contentSortBox.getItems().setAll("下载量", "最新发布", "最近更新");
        contentSortBox.getSelectionModel().selectFirst();
        contentSortBox.setPrefWidth(120);
        contentSortBox.setOnAction(e -> refreshContentTab());

        Button refresh = new Button("刷新");
        refresh.getStyleClass().add("btn-ghost");
        refresh.setMinHeight(34);
        refresh.setOnAction(e -> refreshContentTab());

        Button openFolder = new Button("打开文件夹");
        openFolder.getStyleClass().add("btn-ghost");
        openFolder.setMinHeight(34);
        openFolder.setOnAction(e -> {
            try {
                Files.createDirectories(ContentStore.dir(kind));
            } catch (IOException ignored) {
                // reveal 对不存在的路径自己打日志，不崩
            }
            com.mclauncher.Platform.reveal(ContentStore.dir(kind));
        });

        HBox tools = new HBox(10, contentSearchField, search, contentSortBox, refresh, openFolder);
        tools.setAlignment(Pos.CENTER_LEFT);
        tools.getStyleClass().add("mod-tools");
        tools.setMaxWidth(MOD_CONTENT_WIDTH);

        contentResultCount = new Label("正在加载「" + DOWNLOAD_TABS.get(index) + "」…");
        contentResultCount.getStyleClass().add("page-desc");
        contentResultBox = new FlowPane();
        contentResultBox.getStyleClass().add("mod-grid");
        contentResultBox.setHgap(12);
        contentResultBox.setVgap(12);
        contentResultBox.setPrefWrapLength(MOD_CONTENT_WIDTH);
        contentResultBox.setMaxWidth(MOD_CONTENT_WIDTH);
        // 0.9.9 r4：撤掉整块位图缓存。网格高度 3200~6400px 在 150% 缩放下达 8~16M 像素、超 d3d 的 4096 纹理上限，
        // 且 hover / 封面回填都会让整张位图重画；整窗阴影摘掉后这笔缓存的边际收益为负。
        contentResultBox.setCache(false);

        // 0.9.6 r3 A2：工具条（含「当前游戏」下拉）已由 bodyOfDownloadTab 统一套 modToolbar()，
        // 这里不再自建局部 gameBox / gameBar；全局当前游戏的切换由 modGameBox 的监听器
        // 在内容 tab 分支里走 setCurrentGame() + showDownloadTab()（见 initModControls）。
        if (worldSource != null) {
            box.getChildren().addAll(intro, worldSource, tools,
                    contentResultCount, contentResultBox);
        } else {
            box.getChildren().addAll(intro, tools,
                    contentResultCount, contentResultBox);
        }
        refreshContentTab();
        return box;
    }

    /** 0.9.1：往结果缓存里放一份成功结果（非空才缓存），超过上限逐出最旧的一条。 */
    private static <T> void cacheResult(LinkedHashMap<String, List<T>> cache,
                                        String key, List<T> value) {
        if (value == null || value.isEmpty()) {
            return;   // 空结果 / 失败不缓存，下次还能重试
        }
        cache.remove(key);   // 重新插到末尾，保证逐出的是最旧的一条
        cache.put(key, value);
        while (cache.size() > RESULT_CACHE_MAX) {
            String oldest = cache.keySet().iterator().next();
            cache.remove(oldest);
        }
    }

    /**
     * 0.9.1：把一批结果按每批 12 张、跨帧追加到结果容器。
     *
     * <p>一帧塞 48 张卡（每张 3~5 个控件）会让 FX 线程整帧都在布局，用户点什么都没反应；
     * 分帧后点击事件能插进来。第一批用 {@code setAll} 原子换掉上一批卡片（不留空白帧），
     * 后续批次 {@code addAll}。每批开头用 {@code stillValid} 复核「这次结果还算不算数」，
     * 用户切 tab / 又输了一次关键词就停掉后面的批次，不再白建卡。
     * 不用 Thread.sleep / join，绝不卡 FX 线程。
     */
    private <T> void appendCardsInBatches(FlowPane container, List<T> data,
                                          Function<T, Node> cardFactory,
                                          BooleanSupplier stillValid) {
        final int batchSize = 12;
        // 1.0.9：首批只挂 24 张（两批跨帧），其余等滚动接近底部再挂。以前一次把 48 张
        // 全塞进 3200~6400px 的网格，每帧都要重画全部可见控件，滚动必掉帧；整块
        // setCache(true) 又超 D3D 4096 纹理上限，所以走「视口懒挂载 + 卡片级缓存」。
        ScrollPane owner = findScrollPane(container);
        final int[] cap = {Math.min(data.size(), batchSize * 2)};
        final int[] cursor = {0};
        final boolean[] busy = {false};

        Runnable mount = new Runnable() {
            @Override
            public void run() {
                if (busy[0] || !stillValid.getAsBoolean() || cursor[0] >= cap[0]) {
                    return;
                }
                busy[0] = true;
                int end = Math.min(cursor[0] + batchSize, cap[0]);
                List<Node> batch = new ArrayList<>(end - cursor[0]);
                for (int i = cursor[0]; i < end; i++) {
                    Node card = cardFactory.apply(data.get(i));
                    if (card instanceof Region region) {
                        // 每张卡单独缓存（约 250x140 的小纹理），离 4096 纹理上限很远；
                        // 缓存整块 FlowPane 才是历史上一撤再撤的大纹理坑。
                        region.setCache(true);
                    }
                    batch.add(card);
                }
                if (cursor[0] == 0) {
                    container.getChildren().setAll(batch);   // 原子换掉旧卡，不会有空白帧
                } else {
                    container.getChildren().addAll(batch);
                }
                cursor[0] = end;
                busy[0] = false;
                if (cursor[0] < cap[0]) {
                    PauseTransition next = new PauseTransition(Duration.millis(1));
                    next.setOnFinished(e -> run());   // 同一批内仍跨帧，点击能插进来
                    next.play();
                }
            }
        };
        mount.run();   // 第一批同帧立刻出

        if (owner != null) {
            if (cursor[0] < data.size()) {
                attachLazyCards(owner, data, cap, cursor, busy, mount, stillValid);
            }
        } else {
            // 缓存命中会在页面组装完成前同步回填：下一帧再找一次 ScrollPane。
            // 找到了就走懒挂载；确实没有滚动容器才退回「一次挂满」的老行为。
            Platform.runLater(() -> {
                if (!stillValid.getAsBoolean()) {
                    return;
                }
                ScrollPane later = findScrollPane(container);
                if (later == null) {
                    cap[0] = data.size();
                    mount.run();
                } else if (cursor[0] < data.size()) {
                    attachLazyCards(later, data, cap, cursor, busy, mount, stillValid);
                }
            });
        }
    }

    /** 1.0.9：滚动接近底部时再放行下一批，stillValid 失效时监听器自己摘掉，不堆积。 */
    private <T> void attachLazyCards(ScrollPane owner, List<T> data,
                                     int[] cap, int[] cursor, boolean[] busy,
                                     Runnable mount, BooleanSupplier stillValid) {
        javafx.beans.value.ChangeListener<Number>[] ref =
                new javafx.beans.value.ChangeListener[1];
        ref[0] = (obs, oldV, newV) -> {
            if (!stillValid.getAsBoolean() || cursor[0] >= data.size()) {
                owner.vvalueProperty().removeListener(ref[0]);
                return;
            }
            // 接近底部再追加一批：DOM 节点数始终有上界，滚多远都不会堆到几千个控件
            if (!busy[0] && owner.getVvalue() >= 0.70) {
                cap[0] = Math.min(data.size(), cap[0] + 12);
                mount.run();
            }
        };
        owner.vvalueProperty().addListener(ref[0]);
    }

    /** 1.0.9：从结果网格向上找承载它的 ScrollPane，找不到时下一帧再找 / 退回一次挂满。 */
    private static ScrollPane findScrollPane(Node node) {
        javafx.scene.Parent parent = node == null ? null : node.getParent();
        while (parent != null) {
            if (parent instanceof ScrollPane sp) {
                return sp;
            }
            parent = parent.getParent();
        }
        return null;
    }

    /** 0.9.1：把内容结果渲染到结果栏（缓存命中与网络回填共用同一套校验 + 分批逻辑）。 */
    private void renderContentHits(long seq, int index, ContentStore.Kind kind,
                                   String label, String query, String context,
                                   List<ContentHit> hits) {
        FlowPane container = contentResultBox;
        if (seq != contentRefreshSeq || container == null || contentTabIndex != index) {
            return;   // 结果过期 / 页已重建，整份丢弃（缓存命中也绕不过这个判断）
        }
        if (hits.isEmpty()) {
            contentResultCount.setText(query.isBlank()
                    ? "暂时没有推荐内容，稍后重试" : "没搜到，换个关键词");
            container.getChildren().setAll(modCenterEmpty(
                    query.isBlank()
                            ? "暂时没有可用的「" + label + "」"
                            : "没有匹配的「" + label + "」，换个关键词，或点「打开文件夹」手动放入文件"));
            return;
        }
        String hint = ModAliases.hint(query, context);
        contentResultCount.setText((query.isBlank() ? "推荐" : "搜索结果")
                + " · " + hits.size() + " 个"
                + (hint.isEmpty() ? "" : "（" + hint + "）"));
        appendCardsInBatches(container, hits,
                h -> contentCoverCard(kind, h),
                () -> seq == contentRefreshSeq && contentTabIndex == index
                        && contentResultBox == container);
    }

    /** 搜 / 排 都走这里：两源合并去重，结果栏带中文别名提示（context 版）。 */
    private void refreshContentTab() {
        if (contentResultBox == null || contentSearchField == null || contentKind == null) {
            return;   // 这一页还没建出来
        }
        // 0.9.1：不再在发请求前 clear()——旧卡片一直留到新结果回来，用户看到的是
        // 「旧内容还在、后台在刷新」，而不是瞬间空白 +「正在加载…」像死机。
        final long seq = ++contentRefreshSeq;   // 本次刷新的生成序号
        final long epochAtStart = contentEpoch;   // 0.9.4 r2 A3：切页后旧结果整份丢弃
        final int index = contentTabIndex;
        final ContentStore.Kind kind = contentKind;
        final String context = contentContextOfTab(index);
        final String query = contentSearchField.getText() == null
                ? "" : contentSearchField.getText().trim();
        final String sort = contentSortBox == null ? "下载量" : contentSortBox.getValue();
        final String label = DOWNLOAD_TABS.get(index);
        // 查询条件做 key：tab 下标 + context + query + sort（内容页没有分类胶囊）
        final String cacheKey = index + "|" + context + "|" + query + "|" + sort;
        List<ContentHit> cached = contentResultCache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            // 命中缓存直接秒开，不再发网络请求；仍走同一套 seq / tab 校验与分帧渲染。
            renderContentHits(seq, index, kind, label, query, context, cached);
            return;
        }
        contentResultCount.setText(query.isBlank()
                ? "正在加载推荐「" + label + "」…"
                : "正在搜「" + query + "」…");
        Thread work = new Thread(() -> {
            try {
                List<ContentHit> hits = searchContent(index, query, context, sort);
                javafx.application.Platform.runLater(() -> {
                    // 切 tab / 连续输入后旧线程回填：序号或下标对不上就整份丢弃
                    if (seq != contentRefreshSeq || contentResultBox == null
                            || contentTabIndex != index) {
                        return;
                    }
                    // 0.9.4 r2 A3：附加 epoch 校验（不替换 seq）——切页令在飞结果作废
                    if (epochAtStart != contentEpoch) return;
                    cacheResult(contentResultCache, cacheKey, hits);
                    renderContentHits(seq, index, kind, label, query, context, hits);
                });
            } catch (IOException ex) {
                javafx.application.Platform.runLater(() -> {
                    if (seq != contentRefreshSeq || contentResultBox == null
                            || contentTabIndex != index) {
                        return;
                    }
                    // 0.9.4 r2 A3：切页后错误回填也一律丢弃
                    if (epochAtStart != contentEpoch) return;
                    contentResultCount.setText("加载失败：" + ex.getMessage());
                    contentResultBox.getChildren().setAll(
                            modCenterEmpty("网络抽风了，稍后重试"));
                });
            }
        }, "gbl-content");
        work.setDaemon(true);
        work.start();
    }

    /**
     * 三 tab 的数据源（0.8.7 实测固化）：
     * <ul>
     *   <li>资源包 / 光影：Modrinth（project_type=resourcepack / shader）+ CurseForge（classId=12 / 6552），
     *       两源按规范化项目名去重合并；</li>
     *   <li>世界：只 CurseForge（classId=17）——Modrinth 没有 world 类型，不假装有。</li>
     * </ul>
     * 单源失败不阻断另一源；两源全失败才抛。
     */
    private static List<ContentHit> searchContent(int index, String query, String context,
                                                  String sort) throws IOException {
        List<ContentHit> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();   // 跨源按规范化项目名去重
        boolean anyOk = false;
        IOException last = null;

        // 资源包 / 光影：Modrinth + CurseForge 两源合并；世界：只 CurseForge。
        // 0.9.0 提速第五轮：两源并发抓取，互不阻塞，谁先回谁先填。
        int classId = switch (index) {
            case 4 -> CurseForgeClient.CLASS_ID_RESOURCE_PACKS;
            case 5 -> CurseForgeClient.CLASS_ID_SHADERS;
            default -> CurseForgeClient.CLASS_ID_WORLDS;
        };
        final List<CurseForgeClient.Hit>[] cfBox = new List[1];
        final IOException[] cfErr = {null};
        Thread cfThread = null;
        if (index == 4 || index == 5) {
            final int cid = classId;
            cfThread = new Thread(() -> {
                try {
                    cfBox[0] = searchContentCurseForge(query, context, cid, 48, sort);
                } catch (IOException ex) {
                    cfErr[0] = ex;
                }
            }, "gbl-content-cf");
            cfThread.setDaemon(true);
            cfThread.start();
        }

        if (index == 4 || index == 5) {
            String projectType = index == 4 ? "resourcepack" : "shader";
            try {
                for (ModrinthClient.Hit h :
                        searchContentModrinth(query, context, projectType, 48, sort)) {
                    if (seen.add(normName(h.title()))) {
                        out.add(ContentHit.ofModrinth(h));
                    }
                }
                anyOk = true;
            } catch (IOException ex) {
                last = ex;
            }
        }

        if (cfThread != null) {
            try {
                cfThread.join(15_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            List<CurseForgeClient.Hit> cfHits = cfBox[0] != null
                    ? cfBox[0]
                    : searchContentCurseForge(query, context, classId, 48, sort);
            for (CurseForgeClient.Hit h : cfHits) {
                if (seen.add(normName(h.name()))) {
                    out.add(ContentHit.ofCurseForge(h));
                }
            }
            anyOk = true;
        } catch (IOException ex) {
            last = ex;
        }

        if (!anyOk && last != null) {
            throw last;
        }
        return out;
    }

    /** 带 context 的 Modrinth 别名搜索（逐词按 projectId 去重，带 project_type）。 */
    private static List<ModrinthClient.Hit> searchContentModrinth(
            String query, String context, String projectType, int limit, String sort)
            throws IOException {
        List<ModrinthClient.Hit> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        IOException last = null;
        int ok = 0;
        for (String q : ModAliases.expand(query, context)) {
            try {
                for (ModrinthClient.Hit h : ModrinthClient.search(
                        q, null, null, limit, sort, java.util.List.of(), projectType)) {
                    if (seen.add(String.valueOf(h.projectId()))) {
                        out.add(h);
                    }
                }
                ok++;
            } catch (IOException ex) {
                last = ex;
            }
        }
        if (ok == 0 && last != null) {
            throw last;
        }
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** 带 context 的 CurseForge 别名搜索（逐词按 modId 去重，带 classId 的 6 参重载）。 */
    private static List<CurseForgeClient.Hit> searchContentCurseForge(
            String query, String context, int classId, int limit, String sort)
            throws IOException {
        List<CurseForgeClient.Hit> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        IOException last = null;
        int ok = 0;
        for (String q : ModAliases.expand(query, context)) {
            try {
                for (CurseForgeClient.Hit h : CurseForgeClient.search(
                        q, null, sort, null, limit, classId)) {
                    if (seen.add(String.valueOf(h.modId()))) {
                        out.add(h);
                    }
                }
                ok++;
            } catch (IOException ex) {
                last = ex;
            }
        }
        if (ok == 0 && last != null) {
            throw last;
        }
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** 跨源去重用的项目名归一：小写 + 去空格。 */
    private static String normName(String s) {
        return (s == null ? "" : s).toLowerCase(Locale.ROOT).replace(" ", "").trim();
    }

    /** 内容卡片统一数据（两源字段口径对齐，source 供卡片标注来源）。 */
    private record ContentHit(String source, String projectId, long modId, String title,
                              String summary, String iconUrl, String author, long downloads,
                              List<String> categories, String dateUpdated, String mcVersion) {
        static ContentHit ofModrinth(ModrinthClient.Hit h) {
            return new ContentHit("Modrinth", h.projectId(), 0L,
                    text(h.title()), text(h.description()), h.iconUrl(), text(h.author()),
                    h.downloads(), h.categories() == null ? List.of() : h.categories(),
                    text(h.dateUpdated()), text(h.mcVersion()));
        }

        static ContentHit ofCurseForge(CurseForgeClient.Hit h) {
            return new ContentHit("CurseForge", null, h.modId(),
                    text(h.name()), text(h.summary()), h.iconUrl(), text(h.author()),
                    h.downloads(), h.categories() == null ? List.of() : h.categories(),
                    text(h.dateUpdated()), text(h.mcVersion()));
        }
    }

    private static CardSpec specOf(ContentHit h) {
        return new CardSpec(h.title(), h.summary(), h.author(), h.source(),
                h.categories(), h.downloads(), h.dateUpdated(), h.mcVersion(),
                loaderOf(h.categories()));
    }

    /** 内容卡片：封面一律走现有 loadCover（WebP 解码 + 缓存 + 异步限流），绝不 new Image(url)。 */
    private Node contentCoverCard(ContentStore.Kind kind, ContentHit hit) {
        ImageView cover = new ImageView();
        cover.getStyleClass().add("mod-cover");
        cover.setFitWidth(64);
        cover.setFitHeight(64);
        loadCover(cover, hit.iconUrl(), 112);
        return modCardView(cover, specOf(hit), false, () -> openContentDialog(kind, hit));
    }

    /**
     * 打开内容详情弹窗：复用 {@link #buildModDetailDialog()} 的全局单例。
     * 0.8.8：kind 不再写全局字段，而是随每个版本行的 InstallCtx 传给 {@link #dispatchInstall}。
     * 版本行一律用 {@link #versionRowView(VersionRowSpec)} 渲染；内容弹窗有依赖数据即渲染依赖区。
     */
    private void openContentDialog(ContentStore.Kind kind, ContentHit hit) {
        if (modLayer == null) {
            return;
        }
        resetDetailState();

        loadCover(modDetailCover, hit.iconUrl(), 144);
        modDetailTitle.setText(hit.title());
        modDetailSource.setText(hit.source());
        modDetailMeta.setText("by " + (hit.author().isBlank() ? "未知作者" : hit.author())
                + " · " + hit.source());
        modDetailDesc.setText(hit.summary());
        modDetailExtra.setVisible(false);
        modDetailExtra.setManaged(false);
        modDetailTags.getChildren().clear();
        modDetailStats.getChildren().clear();
        modDetailStats.getChildren().add(statLabel("↓ " + humanDownloads(hit.downloads())));
        modDetailLinks.getChildren().clear();
        modDetailDeps.setVisible(false);       // 内容页不显示前置依赖（A-2 只做模组详情）
        modDetailVersions.getChildren().clear();
        modDetailVersionHead.setText("版本列表 · 正在加载…");
        modDetailStatus.setText("正在加载详情…");
        cancelActiveRipplesBeforeOpenModal();   // 1.0.6：开模组详情页前先清涟漪
        showOverlayLayer(modLayer, "mod-detail");
        javafx.application.Platform.runLater(this::installFloatScrollBars);
        Thread work = new Thread(() -> {
            try {
                if ("CurseForge".equals(hit.source())) {
                    CurseForgeClient.Project p = CurseForgeClient.project(hit.modId());
                    List<CurseForgeClient.File> files = CurseForgeClient.files(hit.modId());
                    javafx.application.Platform.runLater(() ->
                            populateContentDialogCf(hit, p, files, kind));
                } else {
                    ModrinthClient.Project p = ModrinthClient.project(hit.projectId());
                    List<ModrinthClient.Version> vers =
                            ModrinthClient.allVersions(hit.projectId());
                    javafx.application.Platform.runLater(() ->
                            populateContentDialogMr(hit, p, vers, kind));
                }
            } catch (IOException ex) {
                javafx.application.Platform.runLater(() ->
                        modDetailStatus.setText("详情加载失败：" + ex.getMessage()));
            }
        }, "gbl-content-detail");
        work.setDaemon(true);
        work.start();
    }

    private void populateContentDialogMr(ContentHit hit, ModrinthClient.Project p,
                                         List<ModrinthClient.Version> vers,
                                         ContentStore.Kind kind) {
        loadCover(modDetailCover, p.iconUrl() != null ? p.iconUrl() : null, 144);
        modDetailTitle.setText(text(p.title()).isEmpty() ? hit.title() : p.title());
        modDetailDesc.setText(text(p.description()).isEmpty() ? hit.summary() : p.description());
        fillContentTags(p.categories());
        // 0.8.9 R-02：与模组 / 整合包详情同一套「按 Minecraft 版本分组 + 推荐」渲染，
        // 不再把版本行平铺成一个没有组头的长列表。
        List<ModrinthClient.Version> list =
                vers == null ? List.<ModrinthClient.Version>of() : vers;
        // 封面取值口与模组弹窗一致：详情接口的 Project.iconUrl 比搜索 Hit 可靠
        final String cover = p != null && p.iconUrl() != null && !p.iconUrl().isBlank()
                ? p.iconUrl() : hit.iconUrl();
        fillGroupedVersions(list,
                v -> v.gameVersions(),
                v -> contentVersionRowMr(hit, v, kind, cover));
        modDetailStatus.setText(contentStatusHint(kind));
    }

    private void populateContentDialogCf(ContentHit hit, CurseForgeClient.Project p,
                                         List<CurseForgeClient.File> files,
                                         ContentStore.Kind kind) {
        loadCover(modDetailCover, p.iconUrl() != null ? p.iconUrl() : null, 144);
        modDetailTitle.setText(text(p.name()).isEmpty() ? hit.title() : p.name());
        modDetailDesc.setText(text(p.summary()).isEmpty() ? hit.summary() : p.summary());
        fillContentTags(p.categories());
        List<CurseForgeClient.File> list =
                files == null ? List.<CurseForgeClient.File>of() : files;
        // 封面取值口与模组弹窗一致：详情接口的 Project.iconUrl 比搜索 Hit 可靠
        final String cover = p != null && p.iconUrl() != null && !p.iconUrl().isBlank()
                ? p.iconUrl() : hit.iconUrl();
        fillGroupedVersions(list,
                f -> f.gameVersions(),
                f -> contentVersionRowCf(hit, f, kind, cover));
        modDetailStatus.setText(contentStatusHint(kind));
    }

    /**
     * 0.8.9 R-02：资源包 / 光影 / 世界三类内容详情共用的「按 Minecraft 版本分组」渲染器。
     * 组头（Minecraft 版本 + 推荐徽章 + 该组版本数）、组排序、版本行样式全部与模组 /
     * 整合包详情（populateModDialog / populateCfDialog / 整合包弹窗）保持同一套口径：
     * 命中 {@link #effectiveGameId()} 的组排最前并标「推荐」，其余按版本号倒序，
     * 「未标注」兜底组永远垫底。
     *
     * <p>没标 MC 版本的内容通常是全版本通用，归到当前游戏组并标推荐；连当前游戏都
     * 没有时才落 {@link #FALLBACK_MC_GROUP}。只负责画版本区——安装仍走各版本行自带的
     * InstallCtx.kind，落盘目录完全不受影响。
     */
    private <T> void fillGroupedVersions(List<T> items,
                                         java.util.function.Function<T, List<String>> gamesOf,
                                         java.util.function.Function<T, Node> rowOf) {
        final String eg = effectiveGameId();   // 0.8.9：推荐判定只用这一个取值口
        Map<String, List<T>> groups = new LinkedHashMap<>();
        for (T item : items) {
            List<String> games = gamesOf.apply(item);
            boolean placed = false;
            if (games != null) {
                for (String g : games) {
                    if (g == null || g.isBlank() || isLoaderGameToken(g)) {
                        continue;   // CF 的 gameVersions 混着加载器名，不当 MC 版本分组
                    }
                    groups.computeIfAbsent(g, k -> new ArrayList<>()).add(item);
                    placed = true;
                }
            }
            if (!placed) {
                String bucket = eg == null || eg.isBlank() ? FALLBACK_MC_GROUP : eg;
                groups.computeIfAbsent(bucket, k -> new ArrayList<>()).add(item);
            }
        }
        List<String> keys = new ArrayList<>(groups.keySet());
        keys.sort((a, b) -> {
            if (a.equals(eg)) return -1;
            if (b.equals(eg)) return 1;
            boolean fa = FALLBACK_MC_GROUP.equals(a), fb = FALLBACK_MC_GROUP.equals(b);
            if (fa != fb) return fa ? 1 : -1;
            return com.mclauncher.AppVersion.compare(b, a);
        });
        boolean anyRecommend = eg != null && groups.containsKey(eg);
        modDetailVersionHead.setText("版本列表 · 共 " + items.size()
                + " 个 · 按 Minecraft 版本分组");
        modDetailVersions.getChildren().clear();
        for (int i = 0; i < keys.size(); i++) {
            String g = keys.get(i);
            boolean recommend = g.equals(eg) || (!anyRecommend && i == 0);
            modDetailVersions.getChildren().add(groupHeader(g, recommend, groups.get(g).size()));
            VBox grpBox = new VBox(6);
            for (T item : groups.get(g)) {
                grpBox.getChildren().add(rowOf.apply(item));
            }
            modDetailVersions.getChildren().add(grpBox);
        }
    }

    /** CF 的 gameVersions 里混着的加载器 / 环境名，不能拿来当 Minecraft 版本组头。 */
    private static boolean isLoaderGameToken(String g) {
        return switch (g.toLowerCase(Locale.ROOT)) {
            case "forge", "fabric", "neoforge", "quilt", "legacyfabric", "legacy fabric",
                 "optifine", "iris", "minecraft", "cleanroom", "rift", "liteloader",
                 "bukkit", "spigot", "paper" -> true;
            default -> false;
        };
    }

    private void fillContentTags(List<String> cats) {
        modDetailTags.getChildren().clear();
        List<String> list = cats == null ? List.of() : cats;
        for (String c : list) {
            modDetailTags.getChildren().add(tagChip(catLabel(c), "tag-cat"));
        }
        if (modDetailTags.getChildren().isEmpty()) {
            modDetailTags.getChildren().add(tagChip("未分类", "tag-cat"));
        }
    }

    /** Modrinth 内容版本行：安装回调统一经 {@link #dispatchInstall} 按随行携带的 kind 分派。 */
    private Node contentVersionRowMr(ContentHit hit, ModrinthClient.Version v,
                                     ContentStore.Kind kind, String coverUrl) {
        ModrinthClient.File f = v.primaryFile();
        String type = v.versionType() == null ? "other" : v.versionType();
        return versionRowView(new VersionRowSpec(
                shortTypeCn(type), "tag-" + type,
                text(v.name()),
                v.loaders() == null ? List.of() : v.loaders(),
                modDate(v.datePublished()),
                f == null ? 0L : f.size(),
                f == null ? "" : text(f.filename()),
                List.of(), "安装", false,
                btn -> dispatchInstall(new InstallCtx(v, null, false, hit.title(), kind, coverUrl), btn),
                mrDepSummary(v)));
    }

    /** CurseForge 内容版本行：安装回调统一经 {@link #dispatchInstall} 按随行携带的 kind 分派。 */
    private Node contentVersionRowCf(ContentHit hit, CurseForgeClient.File f,
                                     ContentStore.Kind kind, String coverUrl) {
        String type = cfTypeKey(f.releaseType());
        return versionRowView(new VersionRowSpec(
                shortTypeCn(type), "tag-" + type,
                text(f.displayName()),
                loadersFromGameVersions(f.gameVersions()),
                modDate(f.fileDate()), f.fileLength(), text(f.fileName()),
                List.of(), "安装", false,
                btn -> dispatchInstall(new InstallCtx(null,
                        new CfRef(hit.modId(), f), false, hit.title(), kind, coverUrl), btn),
                cfDepSummary(f)));
    }

    /**
     * 内容下载失败的统一提示：说清「什么东西失败了 + 为什么 + 用户下一步能做什么」。
     * why 可能是 FileFetch.lastFailure() 返回的真实原因，也可能是我们主动抛错时给的原因词；
     * 按 why 内容分类给完整、可操作、全中文的一句话。why 为 null 一律走兜底，绝不对 null 调 contains。
     */
    private static String contentFailureMessage(ContentStore.Kind kind, String name, String why) {
        // 项目名用「」包起来；拿不到名字时退成通用说法
        String item = (name == null || name.isBlank()) ? "这个文件" : "「" + name + "」";
        // 空值降级：lastFailure() 没有记录时返回 null，统一当空串处理，避免 NPE
        String w = (why == null) ? "" : why;

        // 拿不到下载地址（作者没开放直链 / CurseForge 未开放直链）
        if (w.contains("下载地址") || w.contains("downloadUrl") || w.contains("直链")
                || w.contains("没有可下载") || w.contains("无 URL") || w.contains("no url")) {
            return item + "的作者未开放直链，启动器无法下载。"
                    + "可换一个版本，或到项目页手动下载 .zip 后用「本地导入」安装。";
        }
        // 302 / 跳转跟随失败
        if (w.contains("302") || w.contains("跳转") || w.contains("重定向") || w.contains("redirect")) {
            return item + "下载失败：文件服务器返回了跳转，启动器跟随跳转没有成功。"
                    + "请检查网络或代理设置，或者切换下载线路后重试。";
        }
        // 链接过期 / 服务端错误（403 / 404 / 5xx）
        if (w.contains("403") || w.contains("404") || w.contains("500") || w.contains("502")
                || w.contains("503") || w.contains("504") || w.contains("5xx") || w.contains("HTTP 5")) {
            return item + "下载失败：下载链接可能已经过期或暂时打不开。"
                    + "请回到版本列表重新点一次「安装」刷新链接；如果还是不行，换一个版本试试。";
        }
        // 超时 / 连接失败
        if (w.contains("超时") || w.contains("timed out") || w.contains("timeout")
                || w.contains("连接") || w.contains("connect") || w.contains("网络")) {
            String bigFile = (kind == ContentStore.Kind.WORLD) ? "世界 / 整合包" : "整合包 / 大型内容";
            return item + "下载失败：网络不稳定或下载线路不通，" + bigFile + "的压缩包体积较大，容易中途断开。"
                    + "请检查网络、切换下载线路，并尽量保持网络稳定后重试。";
        }
        // why 为空或没命中以上任何分类：通用但完整的中文兜底
        return item + "下载安装未完成。请检查网络后重试，或换一个版本；"
                + "也可到项目页手动下载 .zip 后用「本地导入」安装。";
    }

    private static String resolveCfUrl(long modId, CurseForgeClient.File f) throws IOException {
        String url = f.downloadUrl();
        if (url == null || url.isBlank()) {
            url = CurseForgeClient.downloadUrl(modId, f.fileId());
        }
        if (url == null || url.isBlank()) {
            throw new IOException(contentFailureMessage(null, f.displayName(), "没有可用的下载地址"));
        }
        return url;
    }

    /**
     * 内容落盘实现（由 {@link #dispatchInstall} 的内容分支调用）：
     * FileFetch.download 拉到临时文件 → readAllBytes → ContentStore。
     * 不新写 HTTP 客户端；失败不留半成品（FileFetch 管 .part、临时文件 finally 删、ContentStore 原子落盘）。
     * 按钮给「进行中 / 成功 / 失败」三态；光影安装后补一句 OptiFine / Iris 前提。
     */
    private void installContentBytes(java.util.concurrent.Callable<String> urlResolver,
                                     String fileName, String worldName, Button btn,
                                     ContentStore.Kind kind, String coverUrl,
                                     String sha1, long expectedSize, String mcVersion) {
        btn.setDisable(true);
        btn.setText("安装中…");
        modDetailStatus.setText("正在安装…");
        // 0.9.0 R-01/R-02：资源包 / 光影 / 世界统一进 DownloadCenter，异常一律收敛成 FAILED。
        DownloadCenter.Category cat = switch (kind) {
            case RESOURCEPACK -> DownloadCenter.Category.RESOURCEPACK;
            case SHADER -> DownloadCenter.Category.SHADER;
            case WORLD -> DownloadCenter.Category.WORLD;
            default -> DownloadCenter.Category.MOD;
        };
        final java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean();
        DownloadCenter.DEFAULT.submit(worldName, cat, coverUrl,
            (DownloadCenter.Progress p) -> {
                final DownloadCenter.Progress byteProgress = (done, len) -> {
                    try {
                        p.onProgress(done, len);
                    } catch (java.util.concurrent.CancellationException ce) {
                        cancelled.set(true);   // 把「队列移出」镜像给 FileFetch 的取消旗标
                        throw ce;              // ★ 必须继续上抛，否则 runJob 会误判成 DONE
                    }
                };
                // r4：起始心跳带预期大小（服务层给的 size / fileLength），避免 0/0 不确定态；
                // 传 0 时仍按不确定态处理，由 FileFetch listener 用真实 Content-Length 覆盖。
                byteProgress.onProgress(0, expectedSize);
                // 记录下载阶段最后一帧，供 WORLD 解压阶段续着发心跳，不让进度条回跳。
                final long[] lastFrame = {0, 0};
                Path tmp = null;
                try {
                    String url = urlResolver.call();
                    if (url == null || url.isBlank()) {
                        throw new IOException(contentFailureMessage(kind, worldName, "没有可用的下载地址"));
                    }
                    tmp = Files.createTempFile("gbl-content-", ".tmp");
                    // r4：把 sha1 传给 FileFetch（Modrinth 有、CurseForge 无则 null），
                    // 第 6 参非 null 转发真实字节，共用 cancelled。
                    boolean ok = FileFetch.download(url, tmp, sha1, null,
                            cancelled, (done, len) -> {
                                lastFrame[0] = done;
                                lastFrame[1] = len;
                                byteProgress.onProgress(done, len);
                            });
                    if (!ok) {
                        throw new IOException(contentFailureMessage(kind, worldName, FileFetch.lastFailure()));
                    }
                    byte[] bytes = Files.readAllBytes(tmp);
                    // r4 worldInstallProgress：WORLD 走「解压到 saves/」这条路，
                    // ContentStore.installWorld 是同步静默解压，补首尾进度帧 + 完成帧，
                    // 让下载阶段与解压阶段都有帧、看门狗不误判、完成后明确推进到 DONE。
                    Path installed = kind == ContentStore.Kind.WORLD
                            ? worldInstallProgress(worldName, bytes, byteProgress,
                                    lastFrame[0], lastFrame[1])
                            : ContentStore.installFile(kind, fileName, bytes);
                    javafx.application.Platform.runLater(() -> {
                        btn.setDisable(false);
                        btn.setText("安装");
                        if (kind == ContentStore.Kind.SHADER) {
                            modDetailStatus.setText("装好了：" + installed.getFileName()
                                    + "；需要 OptiFine 或 Iris 才生效");
                            toast("装好了；需要 OptiFine 或 Iris 才生效");
                        } else {
                            modDetailStatus.setText("装好了：" + installed.getFileName());
                            toast("装好了：" + installed.getFileName());
                        }
                        // 0.9.2 r2：安装成功把封面 URL 落盘，供管理页封面回退（失败绝不影响安装结果）。
                        rememberContentCover(installed.getFileName().toString(), coverUrl);
                        // 0.9.4：安装成功把「适配 MC 版本」落盘，供管理页版本胶囊回退（新装才写，老条目靠兜底）。
                        AdaptedMc.remember(installed.getFileName().toString(), mcVersion);
                        // 0.8.8 ③：内容安装成功也无条件刷新游戏管理（内容三段全局可管，刷新即重填）。
                        refreshGameManage();
                    });
                    return installed;
                } catch (Exception ex) {
                    // 下载失败在抛出时已经是完整中文（见 contentFailureMessage）；
                    // 这里不再叠「没装上：」前缀，弹窗和下载队列直接显示同一句，两处一致。
                    // 极少数异常没有说明文字时补一句通用中文兜底，别露出空白或英文类名。
                    String msg = ex.getMessage();
                    final String friendly = (msg == null || msg.isBlank())
                            ? contentFailureMessage(kind, worldName, null)
                            : msg;
                    javafx.application.Platform.runLater(() -> {
                        btn.setDisable(false);
                        btn.setText("安装");
                        modDetailStatus.setText(friendly);
                        toast(friendly);
                    });
                    throw ex;
                } finally {
                    if (tmp != null) {
                        try {
                            Files.deleteIfExists(tmp);
                        } catch (IOException ignored) {
                            // 临时文件清理失败不影响安装结果
                        }
                    }
                }
            });
    }

    /**
     * r4 worldInstallProgress：WORLD 安装的「解压到 saves/」阶段进度接线。
     *
     * <p>{@link ContentStore#installWorld} 是同步解压（大世界包可能几十秒没有任何字节帧），
     * 这里在解压前后各补一帧：起始帧刷新 DownloadCenter 的进度新鲜度（看门狗不会误判卡死），
     * 完成帧把 done 推到 total=100%，随后 runJob 收敛成 DONE → scheduleQueueDrop 自动移出队列。
     * total<=0（服务层没给 Content-Length）时用真实 zip 字节数兜底，避免出现 0/0。
     * 完成回调里绝不调 {@link ContentStore#setEnabled}（WORLD 会抛 UnsupportedOperationException）。
     */
    private static Path worldInstallProgress(String worldName, byte[] bytes,
                                             DownloadCenter.Progress byteProgress,
                                             long doneBytes, long totalBytes) throws IOException {
        final long total = totalBytes > 0 ? totalBytes : bytes.length;
        final long done = Math.min(Math.max(doneBytes, 0), total);
        byteProgress.onProgress(done, total);      // 解压阶段起始帧（续着下载进度，不回跳）
        Path installed = ContentStore.installWorld(worldName, bytes);
        byteProgress.onProgress(total, total);    // 解压完成帧：明确推进到 100%
        return installed;
    }

    /**
     * 重置详情弹窗状态机：0.8.8 起只清「选中版本令牌」（含依赖标题反查令牌）。
     * 内容类型（ContentStore.Kind）已随 InstallCtx 走，不再有全局开关需要清；
     * 关闭弹窗 / 切下载 tab / 换游戏版本 / 打开任一详情弹窗前都调一次，避免旧反查结果回填。
     */
    private void resetDetailState() {
        selectedVersion = null;
        // r9 覆盖：§4 前置依赖回到折叠态，标题/卡片先隐藏，等 populateDeps 填出数量再显示
        modDepsExpanded = false;
        modDepsCount = 0;
        if (modDepsTitle != null) {
            modDepsTitle.setVisible(false);
            modDepsTitle.setManaged(false);
        }
        if (modDetailDeps != null) {
            modDetailDeps.setVisible(false);
        }
    }

    // ------------------------------------------------------------ 下载队列（v0.3.5）

    /**
     * 「下载队列」页：一行一项，按顺序，v0.4 起每一项真在/net在下的就是它。
     *
     * <p>这页原来叫「下载列表」，改名改到这里——名字成了「队列」，东西就得真像个队列：
     * 有先后、有状态、能移出。v0.4 终于下得动了，所以每一行有真的百分比和速率，
     * 不再是 v0.3.5 那句「待下载 · 开发中」。
     *
     * <p>数据是 {@link GameDownloader} 的任务表，界面不另存一份「排了哪些」：
     * 队列归服务层，界面只负责把它画出来（两份对不上比没有更糟）。
     */
    /** 0.9.9 r13：给所有长列表滚动条换浮动 skin（幂等；详情弹窗与懒建页面可重复调用）。 */
    private void installFloatScrollBars() {
        FloatScrollBarSkin.installAll(shell);
    }

    private Node buildQueueTab() {
        VBox page = new VBox(16);
        page.getStyleClass().add("queue-page");
        page.setPadding(new Insets(20));
        page.setMaxWidth(1180);

        queueCount = new Label();
        queueCount.getStyleClass().add("queue-count");

        Button clear = new Button("清空队列");
        // 多挂一个类名：队列里同时有「清空队列」和「移出」两个 ghost 按钮，
        // 自测点的时候必须能单独认出「移出」那一个（v0.3.5 首跑认错，一下把整队清了）
        clear.getStyleClass().addAll("btn-ghost", "queue-clear");
        clear.setMinHeight(34);
        clear.setOnMouseClicked(e -> {
            GameDownloader.DEFAULT.clear();   // 正在下的也会掐断，队列这才叫清空
            DownloadCenter.DEFAULT.clear();   // 0.9.0 R-03：内容任务队列一并清空
            refreshVersionList();
            refreshQueueList();
            System.out.println("[download] 队列已清空");
        });

        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox head = new HBox(10);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(queueCount, gap, clear);

        // 0.9.0 R-03：内容安装任务也进同一条队列。
        // 0.9.1：Sink 注册已上移到 buildPages()（与 GameDownloader 并列，启动即生效），
        // 这里不再重复注册——懒加载的队列页不该决定「装完是否自动移出」。
        queueRowBox = new VBox(0);
        refreshQueueList();

        VBox list = new VBox(0);
        list.getStyleClass().addAll("card", "queue-list");
        list.getChildren().add(queueRowBox);
        page.getChildren().addAll(head, list);
        return page;
    }

    /** 队列行缓存：按 id 复用整行节点，避免每帧整表重建导致进度条左右晃动（0.9.0 修复）。 */
    private final Map<String, QueueRowCtl> queueRowCache = new java.util.LinkedHashMap<>();

    /** 一条队列行的可控句柄：复用节点 + 只更新进度/状态，根治抖动。 */
    private static final class QueueRowCtl {
        final String id;
        final HBox row;
        final Label no;
        final Region fill;
        final Label pct;      // r13：百分比文字叠在轨道右端
        final Label sub;      // r13：来源 · 已下/总量 · 速率
        final Label status;   // r13：只显状态/速率，不再固定 120px
        final SimpleDoubleProperty ratio;
        final boolean content;
        javafx.animation.Timeline indet;   // r13：不确定态脉动动画（每行一条）
        QueueRowCtl(String id, HBox row, Label no, Region fill, Label pct, Label sub, Label status,
                    SimpleDoubleProperty ratio, boolean content) {
            this.id = id; this.row = row; this.no = no; this.fill = fill;
            this.pct = pct; this.sub = sub; this.status = status;
            this.ratio = ratio; this.content = content;
        }
    }

    /** 把进度百分比（或不确定态 -1）应用到已存在的行进度条，不再重建节点。 */
    private static void applyProgress(QueueRowCtl ctl, int percent) {
        if (percent < 0) {
            // r13：不再编造 14% 假进度；ratio=0 + 脉动条，明确「在动，只是不知道多少」
            ctl.ratio.set(0.0);
            if (!ctl.fill.getStyleClass().contains("queue-bar-indet")) {
                ctl.fill.getStyleClass().add("queue-bar-indet");
            }
            if (ctl.pct != null) {
                ctl.pct.setText("…");
            }
            startQueueIndeterminate(ctl);
        } else {
            int p = Math.max(0, Math.min(100, percent));   // r13：ratio clamp[0,1]
            ctl.ratio.set(p / 100.0);
            ctl.fill.getStyleClass().remove("queue-bar-indet");
            if (ctl.pct != null) {
                ctl.pct.setText(p + "%");
            }
            stopQueueIndeterminate(ctl);
        }
    }

    /** r13：不确定态脉动（JavaFX CSS 无 @keyframes，Timeline 驱动 opacity 呼吸）。 */
    private static void startQueueIndeterminate(QueueRowCtl ctl) {
        if (ctl.indet == null) {
            ctl.indet = new javafx.animation.Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(ctl.fill.opacityProperty(), 1.0)),
                    new KeyFrame(Duration.millis(650), new KeyValue(ctl.fill.opacityProperty(), 0.3)));
            ctl.indet.setCycleCount(javafx.animation.Animation.INDEFINITE);
            ctl.indet.setAutoReverse(true);
        }
        ctl.indet.play();
    }

    /** r13：退出不确定态时停动画、恢复不透明度。 */
    private static void stopQueueIndeterminate(QueueRowCtl ctl) {
        if (ctl.indet != null) {
            ctl.indet.stop();
        }
        ctl.fill.setOpacity(1.0);
    }

    /** 队列行数。进出队列、进度变了都要重刷：版本列表和队列页两块内容互相依赖。 */
    /**
     * 队列行数。进出队列、进度变了都要重刷：版本列表和队列页两块内容互相依赖。
     *
     * <p>0.9.0 抖动修复：不再每帧整表 {@code setAll} 重建——按 id 复用 {@link QueueRowCtl}
     * 的整行节点，只就地更新进度条比例 / 状态文案 / 序号，再把节点实例原样交回
     * {@code queueRowBox}（JavaFX 复用同一节点不销毁重建，进度条绑定因此稳定不晃）。
     */
    private void refreshQueueList() {
        if (queueRowBox == null) {
            return;   // 队列页还没建过（用户在「游戏」页就加完了），没得刷
        }
        List<QueueRowCtl> desired = new ArrayList<>();
        int at = 1;
        // 原版游戏下载任务（GameDownloader）
        for (GameDownloader.Item item : GameDownloader.DEFAULT.items()) {
            String key = "game:" + item.id();   // r13：与内容任务分前缀，避免裸 id 撞车互相覆盖行
            QueueRowCtl ctl = queueRowCache.get(key);
            if (ctl == null) {
                ctl = buildGameQueueRow(at, item);
                queueRowCache.put(key, ctl);
            } else {
                ctl.no.setText(at + ".");
                ctl.row.getStyleClass().setAll("queue-row", "game-row",
                        "queue-" + item.state().name().toLowerCase(Locale.ROOT));
                applyProgress(ctl, item.percent());
                ctl.sub.setText(gameQueueSubText(item));
                ctl.status.setText(queueStatusText(item));
                ctl.status.getStyleClass().setAll("queue-status",
                        "queue-status-" + item.state().name().toLowerCase(Locale.ROOT));
            }
            desired.add(ctl);
            at++;
        }
        // 0.9.0 R-03：内容安装任务（DownloadCenter：模组 / 整合包 / 资源包 / 光影 / 世界）
        for (DownloadCenter.Item item : DownloadCenter.DEFAULT.items()) {
            String key = "content:" + item.id();   // r13：与游戏任务分前缀
            QueueRowCtl ctl = queueRowCache.get(key);
            if (ctl == null) {
                ctl = buildContentQueueRow(at, item);
                queueRowCache.put(key, ctl);
            } else {
                ctl.no.setText(at + ".");
                ctl.row.getStyleClass().setAll("queue-row", "content-row",
                        "queue-" + item.state().name().toLowerCase(Locale.ROOT));
                applyProgress(ctl, item.percent());
                ctl.sub.setText(contentQueueSubText(item));
                ctl.status.setText(contentQueueStatusText(item));
                ctl.status.getStyleClass().setAll("queue-status",
                        "queue-status-" + item.state().name().toLowerCase(Locale.ROOT));
            }
            desired.add(ctl);
            at++;
        }
        // 清掉已不在队列里的行（完成被自动移出 / 手动移出后，缓存也跟着清）
        java.util.Set<String> live = new java.util.HashSet<>();
        for (QueueRowCtl c : desired) {
            live.add(c.id);
        }
        queueRowCache.entrySet().removeIf(e -> {
            if (live.contains(e.getKey())) {
                return false;
            }
            stopQueueIndeterminate(e.getValue());   // r13：行被移出时停掉脉动动画
            return true;
        });
        // 最后一行别拖那条分隔线：线会横在卡片圆角上，看着像行没和框对齐（v0.4.1）
        for (QueueRowCtl c : desired) {
            c.row.getStyleClass().remove("last");
        }
        if (!desired.isEmpty()) {
            desired.get(desired.size() - 1).row.getStyleClass().add("last");
        }
        List<Node> rows = new ArrayList<>();
        for (QueueRowCtl c : desired) {
            rows.add(c.row);
        }
        queueRowBox.getChildren().setAll(rows);
        int gCount = GameDownloader.DEFAULT.items().size();
        int cCount = DownloadCenter.DEFAULT.items().size();
        int running = GameDownloader.DEFAULT.active() + DownloadCenter.DEFAULT.active();
        if (queueCount != null) {
            queueCount.setText((gCount + cCount) == 0 ? "还没有排队的版本"
                    : "队列里 " + (gCount + cCount) + " 项"
                            + (running > 0 ? "，正在下 " + running + " 个" : ""));
        }
        if (rows.isEmpty()) {
            queueRowBox.getChildren().add(queueEmpty());
        }
    }

    /**
     * 构建（首次）一条原版游戏队列行并返回可复用句柄。进度条比例走持久
     * {@link SimpleDoubleProperty} 绑定，后续只改比例、不重建节点，根除进度条晃动。
     */
    private QueueRowCtl buildGameQueueRow(int order, GameDownloader.Item item) {
        HBox row = new HBox(12);
        row.getStyleClass().setAll("queue-row", "game-row",
                "queue-" + item.state().name().toLowerCase(Locale.ROOT));

        Label no = new Label(order + ".");
        no.getStyleClass().add("queue-order");
        no.setMinWidth(28);

        // r13：来源 / 类型标识前置（游戏行也补上），标题不再用 ver-num 版本号字体
        Label kind;
        if (item.kind() != null) {
            kind = new Label(item.kind().label());
            kind.getStyleClass().addAll("type-badge", "type-" + cssKind(item.kind()));
        } else {
            kind = new Label("游戏");
            kind.getStyleClass().addAll("type-badge");
        }

        // r13 两段式：左 VBox 吃满剩余宽（标题 + 副信息 + 进度条），右固定列（状态 + 移出）
        VBox main = new VBox(4);
        HBox.setHgrow(main, Priority.ALWAYS);
        Label name = new Label(item.id());
        name.getStyleClass().add("queue-name");
        Label sub = new Label(gameQueueSubText(item));
        sub.getStyleClass().add("queue-sub");

        // r4 历史指纹：这串字符串必须在 class 常量池里（构建门禁按字节扫），勿删。
        System.out.println("[r4] " + "r4 进度条宽度解耦" + "：r13 进度条改吃满剩余宽，历史指纹保留");
        StackPane track = new StackPane();
        track.setAlignment(Pos.CENTER_LEFT);
        track.getStyleClass().addAll("queue-progress", "queue-bar");
        track.setPrefHeight(8);
        track.setMinHeight(8);
        track.setMaxHeight(8);
        track.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(track, Priority.ALWAYS);   // r13：吃满左侧剩余宽，不再 ×0.35 写死宽度

        Region fill = new Region();
        fill.setMinWidth(0);
        fill.setMaxWidth(Region.USE_PREF_SIZE);
        fill.getStyleClass().add("queue-progress-fill");
        fill.prefHeightProperty().bind(track.heightProperty());
        SimpleDoubleProperty ratio = new SimpleDoubleProperty(
                Math.max(0, Math.min(100, item.percent())) / 100.0);
        fill.prefWidthProperty().bind(track.widthProperty().multiply(ratio));
        track.getChildren().add(fill);
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);

        Label pct = new Label("");
        pct.getStyleClass().add("queue-pct");
        StackPane.setAlignment(pct, Pos.CENTER_RIGHT);
        track.getChildren().add(pct);

        main.getChildren().addAll(name, sub, track);

        VBox side = new VBox(4);
        side.setAlignment(Pos.CENTER_RIGHT);
        Label status = new Label(queueStatusText(item));
        status.getStyleClass().addAll("queue-status",
                "queue-status-" + item.state().name().toLowerCase(Locale.ROOT));
        Button out = new Button("移出");
        out.getStyleClass().addAll("btn-ghost", "queue-out");
        out.setMinHeight(30);
        out.setMinWidth(64);
        out.setOnMouseClicked(e -> {
            GameDownloader.DEFAULT.remove(item.id());
            refreshVersionList();
            refreshQueueList();
            System.out.println("[download] 「" + item.id() + "」已移出队列");
        });
        side.getChildren().addAll(status, out);

        row.getChildren().addAll(no, kind, main, side);
        QueueRowCtl ctl = new QueueRowCtl("game:" + item.id(), row, no, fill, pct, sub, status, ratio, false);
        applyProgress(ctl, item.percent());
        return ctl;
    }

    /**
     * 0.9.0 R-03 + 修复：内容安装队列的一行（来自 DownloadCenter）。
     * 封面优先用真实项目封面 URL（Modrinth / CurseForge 的 icon_url，经 {@link #loadCover}
     * 异步解码 WebP）；无 URL 才回退类别资源图标 / CUBE。节点复用、只更新进度，根除抖动。
     */
    private QueueRowCtl buildContentQueueRow(int order, DownloadCenter.Item item) {
        HBox row = new HBox(12);
        row.getStyleClass().setAll("queue-row", "content-row",
                "queue-" + item.state().name().toLowerCase(Locale.ROOT));

        Label no = new Label(order + ".");
        no.getStyleClass().add("queue-order");
        no.setMinWidth(28);

        // 封面：真实项目封面 URL 走 loadCover 异步加载（支持 WebP）；否则回退类别图标。
        // 行节点缓存复用，这里只在「首次建行」调一次，不会每帧重复拉图。
        Node icon;
        String ic = item.icon();
        if (ic != null && (ic.startsWith("http://") || ic.startsWith("https://"))) {
            ImageView cover = new ImageView();
            cover.getStyleClass().addAll("comp-icon", "loader-icon");
            cover.setFitWidth(20);
            cover.setFitHeight(20);
            loadCover(cover, ic, 40);
            icon = cover;
        } else {
            icon = contentIcon(ic != null ? ic : item.category().icon(), 20);
        }

        // r13 两段式：左 VBox 吃满剩余宽（标题 + 副信息 + 进度条），右固定列（状态 + 移出）
        VBox main = new VBox(4);
        HBox.setHgrow(main, Priority.ALWAYS);
        Label name = new Label(item.title());
        name.getStyleClass().add("queue-name");
        Label sub = new Label(contentQueueSubText(item));
        sub.getStyleClass().add("queue-sub");

        // r4 历史指纹：这串字符串必须在 class 常量池里（构建门禁按字节扫），勿删。
        System.out.println("[r4] " + "r4 进度条宽度解耦" + "：r13 进度条改吃满剩余宽，历史指纹保留");
        StackPane track = new StackPane();
        track.setAlignment(Pos.CENTER_LEFT);
        track.getStyleClass().addAll("queue-progress", "queue-bar");
        track.setPrefHeight(8);
        track.setMinHeight(8);
        track.setMaxHeight(8);
        track.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(track, Priority.ALWAYS);   // r13：吃满左侧剩余宽，不再 ×0.35 写死宽度

        Region fill = new Region();
        fill.setMinWidth(0);
        fill.setMaxWidth(Region.USE_PREF_SIZE);
        fill.getStyleClass().add("queue-progress-fill");
        fill.prefHeightProperty().bind(track.heightProperty());
        SimpleDoubleProperty ratio = new SimpleDoubleProperty(
                Math.max(0, Math.min(100, item.percent())) / 100.0);
        fill.prefWidthProperty().bind(track.widthProperty().multiply(ratio));
        track.getChildren().add(fill);
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);

        Label pct = new Label("");
        pct.getStyleClass().add("queue-pct");
        StackPane.setAlignment(pct, Pos.CENTER_RIGHT);
        track.getChildren().add(pct);

        main.getChildren().addAll(name, sub, track);

        VBox side = new VBox(4);
        side.setAlignment(Pos.CENTER_RIGHT);
        Label status = new Label(contentQueueStatusText(item));
        status.getStyleClass().addAll("queue-status",
                "queue-status-" + item.state().name().toLowerCase(Locale.ROOT));
        Button out = new Button("移出");
        out.getStyleClass().addAll("btn-ghost", "queue-out");
        out.setMinHeight(30);
        out.setMinWidth(64);
        out.setOnMouseClicked(e -> {
            DownloadCenter.DEFAULT.remove(item.id());
            refreshQueueList();
        });
        side.getChildren().addAll(status, out);

        row.getChildren().addAll(no, icon, main, side);
        QueueRowCtl ctl = new QueueRowCtl("content:" + item.id(), row, no, fill, pct, sub, status, ratio, true);
        applyProgress(ctl, item.percent());
        return ctl;
    }

    /** r13：副信息「来源 · 已下/总量 · 速率」，放在标题下面那一行。 */
    private static String gameQueueSubText(GameDownloader.Item item) {
        String src = item.kind() == null ? "原版游戏" : item.kind().label();
        String size = item.totalBytes() > 0
                ? humanSize(item.doneBytes()) + " / " + humanSize(item.totalBytes())
                : (item.doneBytes() > 0 ? humanSize(item.doneBytes()) : "—");
        String speed = item.bytesPerSecond() > 0 ? GameScanner.sizeText(item.bytesPerSecond()) + "/s" : "";
        return src + " · " + size + (speed.isEmpty() ? "" : " · " + speed);
    }

    /** r13：内容任务副信息。DownloadCenter.Item 没有速率字段，只给来源与已下/总量。 */
    private static String contentQueueSubText(DownloadCenter.Item item) {
        String src = item.category() == null ? "内容" : item.category().label();
        String size = item.totalBytes() > 0
                ? humanSize(item.doneBytes()) + " / " + humanSize(item.totalBytes())
                : (item.doneBytes() > 0 ? humanSize(item.doneBytes()) : "—");
        return src + " · " + size;
    }

    /** 内容队列行右侧状态文案（r13：百分比已进进度条，这里只留状态）。 */
    private static String contentQueueStatusText(DownloadCenter.Item item) {
        return switch (item.state()) {
            case QUEUED -> "排队中";
            case DOWNLOADING -> "下载中";
            case DONE -> item.note();
            case FAILED -> item.note();
            case CANCELLED -> "已取消";
        };
    }

    /** 队列行右侧状态（r13：百分比在进度条右端，这里只显速率 / 结果）。 */
    private static String queueStatusText(GameDownloader.Item item) {
        String speed = item.bytesPerSecond() > 0 ? GameScanner.sizeText(item.bytesPerSecond()) + "/s" : "";
        return switch (item.state()) {
            case QUEUED -> "排队中";
            case DOWNLOADING -> speed.isEmpty() ? "下载中" : speed;
            case DONE -> item.note();
            case FAILED -> item.note();
            case CANCELLED -> "已取消";
        };
    }

    /**
     * 队列空态：就一句「队列还是空的」。
     *
     * <p>v0.4 这框里只留这一行——以前那句「到『游戏』页挑几个版本，点右边的『加入队列』就排这儿了」
     * 和「去『游戏』页挑版本」那枚按钮都删了：队列页本来就挂在「游戏」tab 那排的末尾，
     * 就在旁边，不需要再用文字指一遍路；留着反而像没做完。
     * <b>框不删</b>——空队列还得有个地方说「空的」，那句话就该落在这一块上。
     */
    private Node queueEmpty() {
        VBox box = new VBox(12);
        box.getStyleClass().addAll("queue-empty");
        // v0.4.1 把这块撑高一点：一行字孤零零贴在薄薄一条卡片里看着像没做完
        box.setPadding(new Insets(56, 0, 56, 0));
        box.setAlignment(Pos.CENTER);

        Label title = new Label("队列还是空的");
        title.getStyleClass().add("game-empty-title");

        box.getChildren().addAll(title);
        return box;
    }

    private static HBox dialogLine(String key, String value) {
        Label keyNode = new Label(key);
        keyNode.getStyleClass().add("dlg-key");
        keyNode.setMinWidth(56);
        Label valueNode = new Label(value);
        valueNode.getStyleClass().add("dlg-val");

        HBox line = new HBox(10);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getChildren().addAll(keyNode, valueNode);
        return line;
    }

    // ------------------------------------------------------- 游戏列表 / 游戏管理（v0.3.1）

    /**
     * 游戏列表页：一张卡，卡里「行」和「空态」换着显示。
     *
     * <p>数据从 {@link GameScanner} 扫盘拿，界面自己不另外记一份「装了哪些游戏」——
     * 内存里存一份假的、磁盘上却没有，改名和复制会开始骗人。
     */
    private ScrollPane buildGameListPage() {
        VBox page = new VBox(16);
        page.getStyleClass().add("game-page");
        page.setPadding(new Insets(20));
        page.setMaxWidth(1180);

        Label title = new Label("游戏列表");
        title.getStyleClass().add("page-title");
        Label desc = new Label("点一行就是把它设为当前游戏，行右边的「…」是更多操作。");
        desc.getStyleClass().add("page-desc");

        gameRowBox = new VBox(0);
        VBox list = new VBox(0);
        list.getStyleClass().addAll("card", "game-list");
        list.getChildren().add(gameRowBox);

        gameEmptyBox = buildGameEmpty();
        // 行列表和空态占同一个纵向位置：同一时刻只让一份参与布局，
        // 两份都 visible/managed 会把卡片顶成双倍高
        VBox body = new VBox(0);
        body.getChildren().addAll(list, gameEmptyBox);

        page.getChildren().addAll(title, desc, body);
        StackPage wrap = new StackPage(page);
        ScrollPane scroll = new ScrollPane(wrap);
        scroll.getStyleClass().add("content-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        // 摆进页面之后才刷新：行要读 currentGameId，那时配置已经读完了
        currentGameId = settings.currentGame();
        refreshGames();
        return scroll;
    }

    /**
     * 空态。
     *
     * <p>v0.4 删掉了「载入示例游戏」那枚按钮和 {@code GameScanner.seedSamples()}：
     * 那三行示例是往列表里塞假数据的权宜之计（虽然是真目录、能真改名 / 复制 / 删除），
     * 真下载接上之后，列表里该出现的就是真下载下来的游戏——示例留着会跟真的抢位置，
     * 也让人分不清哪一行是真的（v0.4 说的「把游戏列表的假数据换掉」就是这个）。
     *
     * <p>删法不是「从界面上藏起来」：{@code GameScanner.seedSamples} 整个方法一起删，
     * 磁盘上也不再往用户的 versions 里塞东西。扫盘这条数据源从头到尾只有一种——
     * 磁盘上真实存在过的。
     */
    private Node buildGameEmpty() {
        VBox box = new VBox(12);
        box.getStyleClass().add("game-empty");   // 自测靠这个类名认「空态还在不在」
        box.setAlignment(Pos.CENTER);
        box.setFillWidth(false);
        box.setPadding(new Insets(30, 16, 30, 16));

        SVGPath icon = Icons.create(Icons.CUBE, 30);
        icon.getStyleClass().add("empty-icon");
        Label main = new Label("还没安装游戏");
        main.getStyleClass().add("game-empty-title");
        Label sub = new Label("到「下载」页挑一个版本点「下载」，装好的游戏就出现在这里");
        sub.getStyleClass().add("page-desc");

        Button back = new Button("去「下载」页挑版本");
        back.getStyleClass().add("btn-ghost");
        back.setMinHeight(36);
        back.setOnMouseClicked(e -> showDownloadTab(0));

        box.getChildren().addAll(icon, main, sub, back);
        return box;
    }

    /**
     * 一行游戏。左边的圆点是「当前游戏」的开关，点整行也是同一个意思。
     *
     * <p>圆点用 {@link Circle} 而不是图标按钮：它是状态（哪个是当前的），不是动作，
     * 换类名最直白，深色下的描边也由 CSS 管。
     */
    private Node gameRow(GameEntry game) {
        boolean current = game.id().equals(currentGameId);

        HBox row = new HBox(10);
        // 类名整份 setAll：getStyleClass() 允许重复，只 remove 一个会攒出脏类名
        row.getStyleClass().setAll("game-row");
        if (current) {
            row.getStyleClass().add("selected");
        }
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(9, 12, 9, 12));
        row.setCursor(Cursor.HAND);
        HBox.setHgrow(row, Priority.ALWAYS);

        Circle dot = new Circle(5);
        dot.getStyleClass().setAll("game-dot");
        if (current) {
            dot.getStyleClass().add("on");
        }
        row.getChildren().add(dot);

        Label name = new Label(game.id());
        name.getStyleClass().add("game-name");
        row.getChildren().add(name);

        // 类型徽标沿用下载页那套配色：类名是 "type-" + 类型后缀
        Label type = new Label(game.type().label());
        type.getStyleClass().setAll("type-badge", "type-" + game.type().cssSuffix());
        row.getChildren().add(type);

        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);
        row.getChildren().add(gap);

        Label size = new Label(GameScanner.sizeText(game.sizeBytes()));
        size.getStyleClass().add("game-meta");
        row.getChildren().add(size);

        Label date = new Label(game.date() == null ? "—" : game.date());
        date.getStyleClass().add("game-meta");
        row.getChildren().add(date);

        // 三点：自绘 Pane，不用 Button —— 默认皮肤会往按钮上画灰方块和焦点环
        SVGPath moreIcon = Icons.create(Icons.MORE, 16);
        moreIcon.getStyleClass().add("game-more-icon");
        Pane more = new Pane(moreIcon);
        more.getStyleClass().add("game-more");
        more.setCursor(Cursor.HAND);
        more.setOnMouseClicked(e -> {
            // 别让这一下顺带把「设为当前游戏」也点了
            e.consume();
            openGameMenu(game, more);
        });
        row.getChildren().add(more);
        if (game.type() != null) {
            row.getChildren().add(0, versionBlockIcon(gameTypeToVm(game.type().name()), 20));
        }

        row.setOnMouseClicked(e -> {
            // 右键开菜单：菜单是临时的，跟「设为当前游戏」是两件事
            if (e.getButton() == MouseButton.SECONDARY) {
                openGameMenu(game, row);
                return;
            }
            setCurrentGame(game.id());
        });
        return row;
    }

    /**
     * 重扫一遍并把列表画出来。
     *
     * <p>顺带纠正配置：存着那条游戏磁盘上已经没了（手删的、被别的启动器删的），
     * 这里清成 null。磁盘才是真相，配置不该反过来骗界面。
     */
    private void refreshGames() {
        List<GameEntry> games = GameScanner.scan();
        // 0.9.9 r3：装完任意新游戏后，新手引导卡立即消失（幂等）
        if (!games.isEmpty() && firstRunGuideCard != null) {
            javafx.scene.Parent p = firstRunGuideCard.getParent();
            if (p instanceof Pane pane) {
                pane.getChildren().remove(firstRunGuideCard);
            }
            firstRunGuideCard = null;
        }
        if (currentGameId != null && games.stream().noneMatch(g -> g.id().equals(currentGameId))) {
            currentGameId = null;
        }
        // v0.4.7：磁盘上明明装了游戏、配置里却没设当前游戏时，默认挑扫出来的第一个
        // （scan 已经按发行日倒序）。不然首页 Hero 永远停在「还没有游戏」，
        // 「启动游戏」这颗按钮等于没有 —— 启动流程跑不起来比界面空一句文案严重得多
        if (currentGameId == null && !games.isEmpty()) {
            setCurrentGame(games.get(0).id());
            return;   // setCurrentGame 里会再走一遍这里，这轮别重复画
        }
        currentGameEntry = games.stream()
                .filter(g -> g.id().equals(currentGameId))
                .findFirst().orElse(null);
        gameRowBox.getChildren().setAll(games.stream().map(this::gameRow).toList());

        boolean empty = games.isEmpty();
        gameEmptyBox.setVisible(empty);
        gameEmptyBox.setManaged(empty);
        // 首页 Hero 跟不跟手全由 setCurrentVersion 一个入口决定，这里不自己改文案
        setCurrentVersion(currentGameId);
    }

    /**
     * 下载页「浏览与安装目标」的单一取值入口（0.8.8 Q3 方案 b）。
     *
     * <p><b>纯读</b>：不写任何字段、不调 {@link #setCurrentGame(String)}、不
     * {@code settings.save()}、不 {@link #refreshGames()}、不发首页通知、不跨
     * {@code .minecraft} 共享状态。只回答「这次安装 / 这次读取该落到哪个游戏」，
     * 绝不回写 {@link #currentGameId} / {@link #modCenterGameId}。
     *
     * <p>优先级（命中即返回）：
     * <ol>
     *   <li>工具条显式选中值 {@link #modCenterGameId}（{@code modGameBoxSyncing} 已区分
     *       用户改选择与程序回填，非 null 即有效目标，无需另加显式标记字段）；</li>
     *   <li>全局当前游戏 {@link #currentGameId}；</li>
     *   <li>磁盘第一个游戏 {@link GameScanner#scan()}（已按发行日倒序）；</li>
     *   <li>null —— 由调用方给「可见提示 + 兜底引导」，不得静默 return。</li>
     * </ol>
     *
     * <p>与 {@link #refreshModCenter()} 的单向兜底（:5030-5035）语义一致，但本方法不写字段，
     * 且明确不复用 {@code setCurrentGame()}（4 个副作用 + {@code refreshGames():2730}
     * 在 null 时把磁盘第一个强制上位，与「不选 / 浏览」语义冲突）。
     */
    private String effectiveGameId() {
        if (modCenterGameId != null) return modCenterGameId;
        if (currentGameId != null) return currentGameId;
        List<GameEntry> games = GameScanner.scan();
        return games.isEmpty() ? null : games.get(0).id();
    }

    /**
     * 把某个游戏设为当前游戏。只做三件事——写配置、重画列表、通知首页——
     * 首页的 Hero 与「最近游玩」全靠 {@link #setCurrentVersion} 一个入口跟着变，
     * 不去直接摸 heroTitle 之类。
     */
    private void setCurrentGame(String id) {
        boolean same = (id == null) ? currentGameId == null : id.equals(currentGameId);
        if (same) {
            return;   // 又点了同一行：不写盘不重画，别为一次无效点击去动磁盘
        }
        currentGameId = id;
        // 0.8.7：换游戏版本也重置详情状态机（同「切下载 tab」，避免残留类型串台）。
        resetDetailState();
        settings = settings.withCurrentGame(id);
        settings.save();
        modCenterGameId = null;                 // 清掉页面级钉死，让 effectiveGameId() 回落到新的 currentGameId
        if (modGameBox != null) {
            refreshModCenter();                // 回填 modGameBox + 状态文字 + 刷新四个 tab
        }
        refreshGames();
    }

    // ---------------------------------------------------------------- 行菜单 + 删除/重命名弹层

    /**
     * 游戏行菜单，挂在 {@code shell} 上（不是内容区）——只盖住内容区的话侧边栏还亮着，
     * 看着像没真挡住，跟下载确认弹层一个道理。
     */
    private Node buildGameMenu() {
        VBox card = new VBox(4);
        card.getStyleClass().add("game-menu");
        card.setPadding(new Insets(6, 0, 6, 0));

        // 捕获点击的这层是「全透明 + 事件穿透」，不是遮罩（v0.3.2 黑幕 bug）。
        // 以前这里直接复用下载确认弹层那套 .dlg-backdrop（#0b1a2b91，57% 深蓝），
        // 一开菜单整窗连左边栏一起被压暗一大片 —— 右键个游戏像把窗口关掉了。
        // 右键菜单本来就不该压暗界面，所以底色全透明；「点外面收起」挪到 scene 事件
        // 过滤器里（见 createScene），点外面既收菜单、那一下也照常落到界面上。
        Region catcher = new Region();
        catcher.getStyleClass().add("menu-catch");
        // 穿透：菜单张开时别让这层把底下界面的点击全吃掉，
        // 否则点「启动」只是把菜单收起来、页面却不切，看着就像界面卡住了
        catcher.setMouseTransparent(true);
        // catcher 是 Region，而 Pane 不替子节点算尺寸，得自己给 pref 才盖得住整窗
        catcher.setPrefWidth(INITIAL_WIDTH);
        catcher.setPrefHeight(INITIAL_HEIGHT);
        menuCatcher = catcher;
        // 1.3.1 修复轮：catcher 赋值后才能绑定——createScene 里绑的是 null，纯死代码。
        if (menuCatcher != null) {
            menuCatcher.prefWidthProperty().bind(centerHost.widthProperty());
            menuCatcher.prefHeightProperty().bind(centerHost.heightProperty());
        }

        gameMenuBody = new VBox(0);
        card.getChildren().add(gameMenuBody);
        gameMenuCard = card;   // 给 outsideOfMenu 和自测用：用户「点框里面 / 框外面」问的是这张卡

        // 用 Pane 而不是 StackPane：Pane 不布局子节点，菜单位置由 layoutX/Y 说了算。
        // StackPane 会把 card 抻满整层（下载确认弹层踩过），位置就只能靠 translate 倒了
        Pane layer = new Pane(catcher, card);
        layer.getStyleClass().add("dlg-layer");
        layer.setVisible(false);
        // Esc 收起：菜单是临时的，别让它赖在屏幕上
        layer.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                closeGameMenu();
            }
        });
        gameMenu = layer;
        return layer;
    }

    /** 弹在触发点旁边，越界往回收；离底太近就整个翻到上面去。 */
    private void openGameMenu(GameEntry game, Node anchor) {
        gameMenuBody.getChildren().setAll(
                menuItem("查看游戏文件夹", false, () -> menuAction("open", game)),
                menuItem("重命名", false, () -> menuAction("rename", game)),
                menuItem("复制游戏", false, () -> menuAction("copy", game)),
                menuItem("删除游戏", true, () -> menuAction("remove", game)));
        // 分隔线插在「复制游戏」和「删除游戏」中间：不可逆的那个该跟常规操作隔开
        gameMenuBody.getChildren().add(2, menuSeparator());

        var at = anchor.localToScene(0.0, 0.0);
        double x = at.getX() + anchor.getLayoutBounds().getWidth() - MENU_W;
        double y = at.getY();
        double roomW = centerHost.getWidth() > 0 ? centerHost.getWidth() : INITIAL_WIDTH;
        double roomH = centerHost.getHeight() > 0 ? centerHost.getHeight() : INITIAL_HEIGHT;
        x = Math.max(12, Math.min(x, roomW - MENU_W - 12));
        if (y + MENU_H > roomH - 12) {
            y = Math.max(12, y - MENU_H);
        }
        // 1.3.1 修复轮：翻转后 y 仍可能为负（真机 -63），统一夹进 [12, roomH-MENU_H-12]
        y = Math.max(12, Math.min(y, roomH - MENU_H - 12));
        Node card = gameMenuBody.getParent();
        card.setLayoutX(x);
        card.setLayoutY(y);
        gameMenu.setVisible(true);
    }

    private void closeGameMenu() {
        cancelAllRipples();   // 1.0.5
        if (gameMenu != null) {
            gameMenu.setVisible(false);
        }
    }

    /**
     * 这一下按下，是不是落在行菜单的「框」外面。
     *
     * <p>判的是<b>坐标</b>而不是「事件 target 是不是菜单的子孙」，两个都要：
     * 菜单是一张 {@code Pane}（捕获层 + 卡片），点在卡片外的空白处时命中的可能是 Pane
     * 自己（属于菜单内部），按节点判就永远收不起来；而捕获层的尺寸是写死的 pref，
     * 窗口一最大化（1600x1000）就盖不满整窗，剩下那圈按节点判仍是「菜单内部」。
     * 「点非框里面的区域来关闭」问的是几何，所以几何说话。
     */
    private static boolean outsideOfMenu(Node menu, MouseEvent e) {
        // 参照物是**卡片**（用户在意的那张「框」）而不是整层：菜单那层是张 Pane，
        // 捕获层 + 卡片铺满整窗，拿它当参照物，「在它外面」就等价于「在窗口外面」，
        // 菜单永远关不掉（v0.3.3 就是这么踩的）；拿不到卡片才退回整层。
        Node frame = gameMenuCard == null ? menu : gameMenuCard;
        Bounds box = frame.localToScene(frame.getLayoutBounds());
        double x = e.getSceneX();
        double y = e.getSceneY();
        return x < box.getMinX() || x > box.getMaxX()
                || y < box.getMinY() || y > box.getMaxY();
    }

    /**
     * 菜单项。动作直接绑在闭包里，不按文字或下标去认——
     * 「第 3 项是不是删除」这种判断，改一次菜单顺序就全错了。
     */
    private static Node menuItem(String text, boolean danger, Runnable action) {
        Label label = new Label(text);
        // 类名按 setAll 整份换：getStyleClass() 允许重复，add("a b") 会把整串当成一个类名
        label.getStyleClass().setAll(danger ? "menu-danger" : "menu-label");

        Pane item = new HBox(label);
        item.getStyleClass().add("menu-item");
        item.setCursor(Cursor.HAND);
        item.setOnMouseClicked(e -> action.run());
        return item;
    }

    private static Node menuSeparator() {
        Region line = new Region();
        line.getStyleClass().add("menu-sep");
        line.setPrefHeight(1);
        line.setMinHeight(1);
        return line;
    }

    private void menuAction(String action, GameEntry game) {
        closeGameMenu();
        switch (action) {
            case "open" -> runDiskOp(() -> GameScanner.openFolder(game.id()), "打开文件夹");
            case "rename" -> openPrompt("rename", game);
            // 副本名字带空格会被 GameScanner 的 id 校验挡掉，这里就老实用 "-copy"
            case "copy" -> runDiskOp(() -> GameScanner.copy(game.id(), game.id() + "-copy"), "复制游戏");
            case "remove" -> openPrompt("delete", game);
            default -> { }
        }
    }

    /**
     * 0.9.8：把「碰磁盘」的等待搬出界面线程。
     *
     * <p>删除游戏 / 卸载模组 / 卸载内容这些操作以前全在 JavaFX 界面线程上跑，
     * 其中「移到回收站」要起 PowerShell 并最多等 4 秒，点一下整窗就僵住，
     * 用户看到的就是「删除东西不流畅」。现在点击立刻返回，后台做完再回主线程刷新。
     * 后台任务一律守护线程，关窗不会被它吊住。
     */
    private void runDiskTask(DiskTask task, String what, Runnable onOk, Runnable onFail) {
        Thread t = new Thread(() -> {
            boolean ok;
            try {
                ok = task.run();
            } catch (IOException | RuntimeException ex) {
                System.err.println("[disk] " + what + "失败: " + ex);
                ok = false;
            }
            final boolean result = ok;
            Platform.runLater(() -> {
                try {
                    if (result) {
                        if (onOk != null) {
                            onOk.run();
                        }
                    } else if (onFail != null) {
                        onFail.run();
                    }
                } catch (RuntimeException ex) {
                    System.err.println("[disk] 刷新界面失败: " + ex);
                }
            });
        }, "gbl-disk-op");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 0.9.8：游戏管理页一次重建要清空并重填六段卡片，删除流程里却常常连着调好几次。
     * 1.2.8：防抖由 80ms 提到 260ms——ToggleSwitch 的滑动是 200ms，
     * 提前 getChildren().clear() 会把开关摘出场景树，动画一帧都看不到。
     * 260ms 同时是「动画播完再重建」的余量，多次请求仍合并成一次重建。
     */
    private void scheduleGameManageRefresh() {
        if (gameManageRefreshDebounce == null) {
            gameManageRefreshDebounce = new PauseTransition(Duration.millis(260));
            gameManageRefreshDebounce.setOnFinished(e -> refreshGameManage());
        }
        gameManageRefreshDebounce.playFromStart();
    }

    /**
     * 1.2.8：失败回滚开关时临时屏蔽业务监听器，避免回滚动作再写一次盘。
     * 调用方用 Platform.runLater 延后到 ToggleSwitch.toggleByUser() 的 animateTo 启动之后，
     * 否则外层 animateTo 会把滑块重新推向失败的那一侧。
     */
    private static void rollbackToggle(ToggleSwitch toggle, boolean value, boolean[] guard) {
        guard[0] = true;
        try {
            toggle.setSelected(value);
        } finally {
            guard[0] = false;
        }
    }

    /**
     * 1.0.9：乐观删除的记录——同帧把行从父容器摘掉，后台真正删文件；失败再放回原位。
     */
    private record DetachedRow(Node node, Pane parent, int index) {
    }

    /** 同帧摘行。PowerShell 回收站冷启动 0.5~1.5 秒，这段等待必须藏到后台。 */
    private DetachedRow detachRow(Node row) {
        javafx.scene.Parent parent = row == null ? null : row.getParent();
        if (parent instanceof Pane pane) {
            int index = pane.getChildren().indexOf(row);
            pane.getChildren().remove(row);
            return new DetachedRow(row, pane, index);
        }
        return null;
    }

    /** 删除失败：原父节点还在用就放回原位置；页面已重建就不抢着塞旧节点。 */
    private void restoreDetachedRow(DetachedRow detached) {
        if (detached == null || detached.node() == null || detached.parent() == null) {
            return;
        }
        if (detached.node().getParent() != null) {
            return;
        }
        javafx.collections.ObservableList<Node> children = detached.parent().getChildren();
        children.add(Math.min(Math.max(detached.index(), 0), children.size()), detached.node());
    }

    /** 动磁盘的操作统一出口：失败只记日志，刷新后界面照常，不弹错误框把人吓住。 */
    private void runDiskOp(GameOp op, String what) {
        runDiskTask(() -> {
            op.run();
            return true;
        }, what, this::refreshGames, this::refreshGames);
    }

    /**
     * 删除确认与重命名输入共用的一张卡：{@code promptMode} 决定它现在是哪种身份。
     * 分成两个弹层是复制粘贴，合成一个只多一个字符串字段。
     */
    private Node buildGamePrompt() {
        VBox card = new VBox(16);
        card.getStyleClass().addAll("card", "dlg-card");
        card.setPadding(new Insets(22, 24, 20, 24));
        // 定宽：StackPane 会按可用空间抻子节点，不锁宽内容一长卡片就糊满整窗
        card.setMinWidth(380);
        card.setPrefWidth(440);
        promptCard = card;

        Region backdrop = new Region();
        backdrop.getStyleClass().add("dlg-backdrop");
        backdrop.setOnMouseClicked(e -> closePrompt());

        VBox shell = new VBox(card);
        shell.setAlignment(Pos.CENTER);
        shell.setFillWidth(false);

        StackPane layer = new StackPane(backdrop, shell);
        layer.getStyleClass().add("dlg-layer");
        layer.setVisible(false);
        layer.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                closePrompt();
            }
        });
        gamePrompt = layer;
        return layer;
    }

    /**
     * 添加离线账号的弹层：和 {@link #buildGamePrompt} 同款结构（backdrop + 居中卡），
     * 但字段独立，不碰游戏重命名 / 删除那条逻辑。
     */
    private Node buildAccountPrompt() {
        VBox card = new VBox(16);
        card.getStyleClass().addAll("card", "dlg-card");
        card.setPadding(new Insets(22, 24, 20, 24));
        card.setMinWidth(380);
        card.setPrefWidth(440);
        accountPromptCard = card;

        Region backdrop = new Region();
        backdrop.getStyleClass().add("dlg-backdrop");
        backdrop.setOnMouseClicked(e -> closeAccountPrompt());

        VBox shell = new VBox(card);
        shell.setAlignment(Pos.CENTER);
        shell.setFillWidth(false);

        StackPane layer = new StackPane(backdrop, shell);
        layer.getStyleClass().add("dlg-layer");
        layer.setVisible(false);
        layer.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                closeAccountPrompt();
            }
        });
        accountPrompt = layer;
        return layer;
    }

    private void openAccountPrompt() {
        accountPromptField.setText("");
        Label title = new Label("添加离线账号");
        title.getStyleClass().add("dlg-title");
        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Pane close = winBtn("dlg-close", Icons.strokeScaled(Icons.WIN_CLOSE, WIN_ICON, 1.6),
                BTN_HOVER_BG, STROKE_HOVER);
        close.setOnMouseClicked(e -> closeAccountPrompt());
        HBox head = new HBox(8);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(title, gap, close);

        Label hint = new Label("名字用字母、数字或下划线，"
                + "别超过 16 个字符。");
        hint.getStyleClass().add("setting-desc");
        hint.setWrapText(true);
        hint.setMaxWidth(420);
        accountPromptField.getStyleClass().add("prompt-field");
        // 回车就是确认：为一个名字专门去够按钮没道理
        accountPromptField.setOnAction(e -> confirmAccountPrompt());

        VBox body = new VBox(10);
        body.getChildren().addAll(hint, accountPromptField);

        Button cancel = new Button("取消");
        cancel.getStyleClass().add("btn-ghost");
        cancel.setMinHeight(36);
        cancel.setMinWidth(88);
        cancel.setOnMouseClicked(e -> closeAccountPrompt());
        Button ok = new Button("添加");
        ok.getStyleClass().add("btn-download");
        ok.setMinHeight(36);
        ok.setMinWidth(88);
        ok.setOnMouseClicked(e -> confirmAccountPrompt());
        Region tail = filler();
        HBox.setHgrow(tail, Priority.ALWAYS);
        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.getChildren().addAll(cancel, tail, ok);

        accountPromptCard.getChildren().setAll(head, body, buttons);
        cancelActiveRipplesBeforeOpenModal();   // 1.0.6
        showOverlayLayer(accountPrompt, "account-prompt");
        accountPromptField.requestFocus();
    }

    private void closeAccountPrompt() {
        cancelAllRipples();   // 1.0.5
        if (accountPrompt != null) {
            hideOverlayLayer(accountPrompt, "account-prompt");
        }
    }

    private void confirmAccountPrompt() {
        String name = accountPromptField.getText() == null ? "" : accountPromptField.getText().trim();
        if (name.isEmpty()) {
            return;   // 空名不让加：游戏里连个显示名都没有没意义
        }
        closeAccountPrompt();
        accountStore = accountStore.withAdded(AccountStore.newOffline(name));
        accountStore.save();
        refreshAccountPage();
        refreshHero();
        toast("已添加离线账号 " + name);
    }

    // ---------------------------------------------------------------- 外置（little skin）账号登录弹窗

    private Node buildExternalPrompt() {
        VBox card = new VBox(16);
        card.getStyleClass().addAll("card", "dlg-card");
        card.setPadding(new Insets(22, 24, 20, 24));
        card.setMinWidth(380);
        card.setPrefWidth(440);
        externalPromptCard = card;

        Region backdrop = new Region();
        backdrop.getStyleClass().add("dlg-backdrop");
        backdrop.setOnMouseClicked(e -> closeExternalPrompt());

        VBox shellBox = new VBox(card);
        shellBox.setAlignment(Pos.CENTER);
        shellBox.setFillWidth(false);

        StackPane layer = new StackPane(backdrop, shellBox);
        layer.getStyleClass().add("dlg-layer");
        layer.setVisible(false);
        layer.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                closeExternalPrompt();
            }
        });
        externalPrompt = layer;
        return layer;
    }

    private void openExternalPrompt() {
        externalUserField.setText("");
        externalPassField.setText("");
        externalError.setText("");
        externalError.setVisible(false);
        Label title = new Label("添加外置账号（LittleSkin）");
        title.getStyleClass().add("dlg-title");
        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Pane close = winBtn("dlg-close", Icons.strokeScaled(Icons.WIN_CLOSE, WIN_ICON, 1.6),
                BTN_HOVER_BG, STROKE_HOVER);
        close.setOnMouseClicked(e -> closeExternalPrompt());
        HBox head = new HBox(8);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(title, gap, close);

        Label hint = new Label("用 LittleSkin 的账号密码登录。登录后单机照常玩；"
                + "想联机需要服务器也支持 LittleSkin。");
        hint.getStyleClass().add("setting-desc");
        hint.setWrapText(true);
        hint.setMaxWidth(420);

        externalUserField.getStyleClass().add("prompt-field");
        externalUserField.setPromptText("用户名 / 邮箱");
        externalPassField.getStyleClass().add("prompt-field");
        externalPassField.setPromptText("密码");
        // 在密码框按回车即提交
        externalPassField.setOnAction(e -> confirmExternalPrompt());

        Label userLbl = new Label("用户名");
        userLbl.getStyleClass().add("setting-desc");
        Label passLbl = new Label("密码");
        passLbl.getStyleClass().add("setting-desc");
        externalError.getStyleClass().add("dlg-error");
        externalError.setWrapText(true);
        externalError.setMaxWidth(420);
        externalError.setStyle("-fx-text-fill:#d92d20;");
        externalError.setVisible(false);

        VBox body = new VBox(10);
        body.getChildren().addAll(hint, userLbl, externalUserField, passLbl, externalPassField,
                externalError);

        externalCancelBtn = new Button("取消");
        externalCancelBtn.getStyleClass().add("btn-ghost");
        externalCancelBtn.setMinHeight(36);
        externalCancelBtn.setMinWidth(88);
        externalCancelBtn.setOnMouseClicked(e -> closeExternalPrompt());
        externalOkBtn = new Button("登录");
        externalOkBtn.getStyleClass().add("btn-download");
        externalOkBtn.setMinHeight(36);
        externalOkBtn.setMinWidth(88);
        externalOkBtn.setOnMouseClicked(e -> confirmExternalPrompt());
        Region tail = filler();
        HBox.setHgrow(tail, Priority.ALWAYS);
        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.getChildren().addAll(externalCancelBtn, tail, externalOkBtn);

        externalPromptCard.getChildren().setAll(head, body, buttons);
        cancelActiveRipplesBeforeOpenModal();   // 1.0.6
        showOverlayLayer(externalPrompt, "external-prompt");
        externalUserField.requestFocus();
    }

    private void closeExternalPrompt() {
        cancelAllRipples();   // 1.0.5
        if (externalPrompt != null) {
            hideOverlayLayer(externalPrompt, "external-prompt");
        }
    }

    /** 后台线程打 LittleSkin 登录，成功存储外置账号、失败在弹窗内提示。 */
    private void confirmExternalPrompt() {
        String user = externalUserField.getText() == null ? "" : externalUserField.getText().trim();
        String pass = externalPassField.getText() == null ? "" : externalPassField.getText();
        if (user.isEmpty() || pass.isEmpty()) {
            externalError.setText("用户名和密码都要填");
            externalError.setVisible(true);
            return;
        }
        // 登录中：禁用按钮、改文案，网络请求放后台线程，别卡界面
        externalError.setVisible(false);
        externalOkBtn.setDisable(true);
        externalCancelBtn.setDisable(true);
        externalOkBtn.setText("登录中…");

        new Thread(() -> {
            try {
                LittleSkinAuth.Result r = LittleSkinAuth.authenticate(user, pass);
                javafx.application.Platform.runLater(() -> {
                    closeExternalPrompt();
                    accountStore = accountStore.withAdded(
                            AccountStore.newLittleSkin(r.name, r.uuid, r.accessToken));
                    accountStore.save();
                    refreshAccountPage();
                    refreshHero();
                    toast("已添加外置账号 " + r.name);
                });
            } catch (Exception ex) {
                String msg = ex.getMessage();
                javafx.application.Platform.runLater(() -> {
                    externalOkBtn.setDisable(false);
                    externalCancelBtn.setDisable(false);
                    externalOkBtn.setText("登录");
                    externalError.setText(msg == null ? "登录失败" : msg);
                    externalError.setVisible(true);
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- 微软账号登录弹窗（设备码流）

    private Node buildMicrosoftPrompt() {
        VBox card = new VBox(16);
        card.getStyleClass().addAll("card", "dlg-card");
        card.setPadding(new Insets(22, 24, 20, 24));
        card.setMinWidth(400);
        card.setPrefWidth(460);
        microsoftPromptCard = card;

        Region backdrop = new Region();
        backdrop.getStyleClass().add("dlg-backdrop");
        backdrop.setOnMouseClicked(e -> closeMicrosoftPrompt());

        VBox shellBox = new VBox(card);
        shellBox.setAlignment(Pos.CENTER);
        shellBox.setFillWidth(false);

        StackPane layer = new StackPane(backdrop, shellBox);
        layer.getStyleClass().add("dlg-layer");
        layer.setVisible(false);
        layer.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                closeMicrosoftPrompt();
            }
        });
        microsoftPrompt = layer;
        return layer;
    }

    private void openMicrosoftPrompt() {
        // 诊断：确认弹窗节点已挂载到 shell（排查「点了没反应」）
        System.out.println("[inspect] openMicrosoftPrompt: microsoftPrompt="
                + (microsoftPrompt != null) + " inShell="
                + (shell != null && microsoftPrompt != null && shell.getChildren().contains(microsoftPrompt)));
        microsoftAborted = false;
        microsoftCode.setText("");
        microsoftCode.setVisible(false);
        microsoftUriLink.setText("");
        microsoftUriLink.setVisible(false);
        microsoftStatus.setText("正在获取登录码…（需要能访问微软，国内网络可能要多等一会儿）");
        microsoftError.setText("");
        microsoftError.setVisible(false);

        Label title = new Label("添加微软账号");
        title.getStyleClass().add("dlg-title");
        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Pane close = winBtn("dlg-close", Icons.strokeScaled(Icons.WIN_CLOSE, WIN_ICON, 1.6),
                BTN_HOVER_BG, STROKE_HOVER);
        close.setOnMouseClicked(e -> closeMicrosoftPrompt());
        HBox head = new HBox(8);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(title, gap, close);

        Label hint = new Label("点「打开登录页」在浏览器里登录，再输入下面这串代码。"
                + "成功后本窗口会自动关闭。");
        hint.getStyleClass().add("setting-desc");
        hint.setWrapText(true);
        hint.setMaxWidth(440);

        // 设备码：大字显示 + 一键复制
        microsoftCode.getStyleClass().add("ms-code");
        microsoftCode.setVisible(false);
        HBox codeRow = new HBox(10);
        codeRow.setAlignment(Pos.CENTER_LEFT);
        Button copyBtn = new Button("复制代码");
        copyBtn.getStyleClass().add("btn-ghost");
        copyBtn.setMinHeight(34);
        copyBtn.setMinWidth(88);
        copyBtn.setOnAction(e -> {
            String code = microsoftCode.getText();
            if (code != null && !code.isBlank()) {
                ClipboardContent cc = new ClipboardContent();
                cc.putString(code);
                Clipboard.getSystemClipboard().setContent(cc);
                toast("已复制登录代码");
            }
        });
        codeRow.getChildren().addAll(microsoftCode, codeRowGap(), copyBtn);

        // 登录链接：点一下用本机浏览器打开
        microsoftUriLink.getStyleClass().add("ms-link");
        microsoftUriLink.setVisible(false);
        microsoftUriLink.setOnAction(e -> openInBrowser(microsoftUriLink.getText()));

        microsoftStatus.getStyleClass().add("setting-desc");
        microsoftStatus.setWrapText(true);
        microsoftStatus.setMaxWidth(440);

        microsoftError.getStyleClass().add("dlg-error");
        microsoftError.setWrapText(true);
        microsoftError.setMaxWidth(440);
        microsoftError.setStyle("-fx-text-fill:#d92d20;");
        microsoftError.setVisible(false);

        VBox body = new VBox(10);
        body.getChildren().addAll(hint, codeRow, microsoftUriLink, microsoftStatus, microsoftError);

        microsoftCloseBtn = new Button("关闭");
        microsoftCloseBtn.getStyleClass().add("btn-ghost");
        microsoftCloseBtn.setMinHeight(36);
        microsoftCloseBtn.setMinWidth(88);
        microsoftCloseBtn.setOnMouseClicked(e -> closeMicrosoftPrompt());
        Region tail = filler();
        HBox.setHgrow(tail, Priority.ALWAYS);
        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.getChildren().addAll(tail, microsoftCloseBtn);

        microsoftPromptCard.getChildren().setAll(head, body, buttons);
        // 置顶到所有 overlay 之上，避免被其它层盖住而「点了没反应」
        if (shell != null && microsoftPrompt != null) {
            shell.getChildren().remove(microsoftPrompt);
            shell.getChildren().add(microsoftPrompt);
        }
        cancelActiveRipplesBeforeOpenModal();   // 1.0.6
        showOverlayLayer(microsoftPrompt, "microsoft-prompt");

        // 后台：申请设备码 →（UI 展示）→ 轮询直到用户在浏览器完成登录
        new Thread(() -> {
            try {
                MicrosoftAuth.DeviceCode dc = MicrosoftAuth.requestDeviceCode();
                javafx.application.Platform.runLater(() -> {
                    microsoftCode.setText(dc.userCode);
                    microsoftCode.setVisible(true);
                    microsoftUriLink.setText(dc.verificationUri);
                    microsoftUriLink.setVisible(true);
                    microsoftStatus.setText("请用浏览器打开上面的链接、输入这串代码完成登录（"
                            + dc.expiresIn + " 秒内有效）");
                });
                MicrosoftAuth.MinecraftAccount mc = MicrosoftAuth.completeLogin(dc,
                        status -> javafx.application.Platform.runLater(() -> microsoftStatus.setText(status)),
                        () -> microsoftAborted);
                javafx.application.Platform.runLater(() -> {
                    closeMicrosoftPrompt();
                    accountStore = accountStore.withAdded(
                            AccountStore.newMicrosoft(mc.name, mc.uuid, mc.accessToken,
                                    mc.refreshToken, mc.expiresAt));
                    accountStore.save();
                    refreshAccountPage();
                    refreshHero();
                    toast("已添加微软账号 " + mc.name);
                });
            } catch (Exception ex) {
                final String msg = ex.getMessage();
                javafx.application.Platform.runLater(() -> {
                    if (microsoftAborted) {
                        return;   // 用户主动关了弹窗，不弹错
                    }
                    microsoftStatus.setText("登录未完成");
                    microsoftError.setText(msg == null ? "登录失败" : msg);
                    microsoftError.setVisible(true);
                });
            }
        }, "gbl-ms-login").start();
    }

    /** code 行里的弹性占位，把复制按钮顶到右侧一点。 */
    private Region codeRowGap() {
        Region r = filler();
        r.setMinWidth(6);
        return r;
    }

    private void closeMicrosoftPrompt() {
        cancelAllRipples();   // 1.0.5
        microsoftAborted = true;   // 让后台轮询线程尽快退出
        if (microsoftPrompt != null) {
            hideOverlayLayer(microsoftPrompt, "microsoft-prompt");
        }
    }

    // ---------------------------------------------------------------- 皮肤管理弹窗

    /**
     * 皮肤管理弹窗骨架（和微软登录弹窗同构）：底层 backdrop 挡住主界面，点 backdrop 关闭；
     * 卡片里的内容在 {@link #openSkinManager} 时按账号类型现填。这是个大界面：左边 3D 预览、右边皮肤列表。
     */
    private Node buildSkinPrompt() {
        VBox card = new VBox(16);
        card.getStyleClass().addAll("card", "dlg-card");
        card.setPadding(new Insets(22, 24, 20, 24));
        // 1.3.1 修复轮：窄窗口不再用 760x560 硬下限撑爆卡片（QA：680x460 时「应用」按钮被裁）
        card.setMinWidth(320);
        card.setPrefWidth(820);
        card.setMinHeight(240);
        if (shell != null) {
            card.maxWidthProperty().bind(shell.widthProperty().subtract(48));
            card.maxHeightProperty().bind(shell.heightProperty().subtract(48));
        }
        skinPromptCard = card;

        Region backdrop = new Region();
        backdrop.getStyleClass().add("dlg-backdrop");
        backdrop.setOnMouseClicked(e -> closeSkinManager());

        VBox shellBox = new VBox(card);
        shellBox.setAlignment(Pos.CENTER);
        shellBox.setFillWidth(false);

        StackPane layer = new StackPane(backdrop, shellBox);
        layer.getStyleClass().add("dlg-layer");
        layer.setVisible(false);
        layer.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                closeSkinManager();
            }
        });
        skinPrompt = layer;
        return layer;
    }

    /**
     * 1.1.3：LITTLESKIN 账号的「服务器皮肤」只读卡片。
     * 只展示从服务器拉到的真实皮肤 + 说明 + 刷新按钮；没有本地导入 / 导出 / 应用 / 恢复默认。
     */
    private void openLittleSkinServerSkin(AccountStore.Account acc) {
        Label title = new Label("服务器皮肤 · " + acc.name);
        title.getStyleClass().add("dlg-title");
        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Pane close = winBtn("dlg-close", Icons.strokeScaled(Icons.WIN_CLOSE, WIN_ICON, 1.6),
                BTN_HOVER_BG, STROKE_HOVER);
        close.setOnMouseClicked(e -> closeSkinManager());
        HBox head = new HBox(8);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(title, gap, close);

        LittleSkinSkin.Texture tex = littleSkinTexture(acc);
        littleSkinServerUuid = acc.uuid;
        littleSkinServerHead = new ImageView(
                tex != null ? SkinRender.head(tex.png) : SkinRender.headOf(acc));
        littleSkinServerHead.setFitWidth(128);
        littleSkinServerHead.setFitHeight(128);
        littleSkinServerHead.setSmooth(false);
        VBox preview = new VBox(8, littleSkinServerHead);
        preview.setAlignment(Pos.CENTER);
        preview.setMinWidth(240);

        Label desc = new Label("皮肤由 LittleSkin 服务器管理，请到 littleskin.cn 更换；点刷新可重新拉取。");
        desc.getStyleClass().add("setting-desc");
        desc.setWrapText(true);
        desc.setMaxWidth(520);

        littleSkinServerDetail = new Label(tex == null
                ? "尚未取到服务器皮肤，暂时显示默认头像；点「刷新」重试。"
                : "当前模型：" + ("slim".equals(tex.model) ? "细臂（Alex）" : "经典（Steve）"));
        littleSkinServerDetail.getStyleClass().add("setting-desc");

        Button refreshBtn = new Button("刷新");
        refreshBtn.getStyleClass().add("btn-ghost");
        refreshBtn.setMinHeight(34);
        refreshBtn.setMinWidth(88);
        refreshBtn.setOnAction(e -> {
            System.out.println("[littleskin-skin] 手动刷新 uuid=" + acc.uuid);
            prefetchLittleSkinHead(acc, true);
        });

        VBox body = new VBox(14, preview, desc, littleSkinServerDetail, refreshBtn);
        body.setAlignment(Pos.TOP_CENTER);
        body.setPadding(new Insets(8, 0, 0, 0));
        ScrollPane serverBodyScroll = new ScrollPane(body);
        serverBodyScroll.getStyleClass().add("content-scroll");
        serverBodyScroll.setFitToWidth(true);
        serverBodyScroll.setMaxHeight(Region.USE_PREF_SIZE);
        VBox.setVgrow(serverBodyScroll, Priority.ALWAYS);
        skinPromptCard.getChildren().setAll(head, serverBodyScroll);

        if (shell != null && skinPrompt != null) {
            shell.getChildren().remove(skinPrompt);
            shell.getChildren().add(skinPrompt);
        }
        cancelActiveRipplesBeforeOpenModal();
        showOverlayLayer(skinPrompt, "skin-prompt");
        System.out.println("[littleskin-skin] 打开服务器皮肤卡片 uuid=" + acc.uuid);
        // 打开时后台拉一次（force=false：24h 缓存命中就秒回，过期才联网）
        prefetchLittleSkinHead(acc, false);
    }

    /** 打开某账号的皮肤管理界面：按该账号当前记录初始化选择，并置顶显示（大界面 + 3D 预览）。 */
    private void openSkinManager(AccountStore.Account acc) {
        System.out.println("[inspect] openSkinManager: acc=" + acc.name
                + " type=" + acc.type + " inShell="
                + (shell != null && skinPrompt != null && shell.getChildren().contains(skinPrompt)));

        // 1.1.3：外置账号皮肤由服务器管，走只读「服务器皮肤」卡片，不进本地换肤界面
        if (acc.type == AccountStore.Type.LITTLESKIN) {
            openLittleSkinServerSkin(acc);
            return;
        }

        // 用该账号当前皮肤记录初始化「待定选择」
        SkinStore.Entry cur = SkinStore.get(acc);
        if ("custom".equals(cur.source) && cur.file != null) {
            skinPendingCustom = SkinStore.listCustoms(acc).stream()
                    .filter(c -> c.file.equals(cur.file)).findFirst().orElse(null);
            skinPendingSource = skinPendingCustom != null ? "custom" : cur.source;
        } else {
            skinPendingCustom = null;
            skinPendingSource = cur.source; // steve | alex | ari | … | gallery:xxx
        }
        skinPendingModel = SkinStore.model(acc);

        Label title = new Label("皮肤管理 · " + acc.name);
        title.getStyleClass().add("dlg-title");
        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Pane close = winBtn("dlg-close", Icons.strokeScaled(Icons.WIN_CLOSE, WIN_ICON, 1.6),
                BTN_HOVER_BG, STROKE_HOVER);
        close.setOnMouseClicked(e -> closeSkinManager());
        HBox head = new HBox(8);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(title, gap, close);

        // ---- 左：皮肤预览（0.9.8 r2 起按 HMCL 方式做立体 3D 预览）----
        skin3dBox = new StackPane();
        skin3dBox.getStyleClass().add("skin-3d-box");
        skin3dBox.setMinSize(300, 340);
        skin3dBox.setPrefSize(300, 340);
        skin3dBox.setMaxSize(300, 340);
        try {
            skin3d = new Skin3D(resolvePendingBytes(acc), skinPendingModel);
            skin3dBox.getChildren().add(skin3d.subScene());
        } catch (IOException ex) {
            Label err = new Label("皮肤预览没加载出来，换张图片试试");
            err.getStyleClass().add("setting-desc");
            skin3dBox.getChildren().add(err);
        }
        Label hint = new Label("按住鼠标拖动，可以转着看");
        hint.getStyleClass().add("setting-desc");
        hint.setAlignment(Pos.CENTER);
        VBox left = new VBox(8, skin3dBox, hint);
        left.setAlignment(Pos.CENTER);
        left.setFillWidth(false);

        // ---- 右：皮肤列表 ----
        VBox right = new VBox(12);
        right.setMinWidth(380);
        right.setPrefWidth(420);

        // 1.2.8：恢复宽臂 / 细臂分段按钮（0.9.8 r5 曾因官方皮肤误用下线）。
        // 只对「我的皮肤」里自己导入的皮肤开放；官方 / gallery 仍由
        // SkinStore.officialModel / SkinRender.isSlim 自动判定，按钮统一在 syncModelBtns() 禁用。
        Label officialTitle = new Label("官方默认");
        officialTitle.getStyleClass().add("setting-label");

        FlowPane grid = new FlowPane(10, 10);
        grid.getStyleClass().add("skin-grid");
        // 内置官方（英文名）+ 用户扩展目录
        Map<String, String> builtins = SkinStore.builtinDefaults();
        for (Map.Entry<String, String> kv : builtins.entrySet()) {
            addSkinCell(grid, acc, kv.getKey(), kv.getValue());
        }
        for (Map.Entry<String, String> kv : SkinStore.galleryDefaults().entrySet()) {
            addSkinCell(grid, acc, kv.getKey(), kv.getValue());
        }

        VBox customList = new VBox(8);
        customList.setFillWidth(true);
        fillCustomList(customList, acc);

        Button importBtn = new Button("导入自己的 PNG");
        importBtn.getStyleClass().add("btn-ghost");
        importBtn.setMinHeight(34);
        importBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("选择皮肤图片");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG 图片", "*.png"));
            java.io.File f = fc.showOpenDialog(shell.getScene().getWindow());
            if (f != null) {
                try {
                    byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                    // 0.9.9 P1：先校验 PNG 尺寸。太小的图会让头像渲染读脸/帽像素时越界抛异常，
                    // 且 addCustom 一旦写盘，之后每次打开皮肤管理都会崩，功能被永久锁死。
                    // PNG 头：0..7 签名、12..15 为 IHDR、16..19 宽、20..23 高（均大端）。
                    if (bytes.length < 24
                            || (bytes[0] & 0xFF) != 0x89 || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G'
                            || bytes[12] != 'I' || bytes[13] != 'H' || bytes[14] != 'D' || bytes[15] != 'R') {
                        toast("这张图片不是有效的 PNG，请换一张完整皮肤再试。");
                        return;
                    }
                    int skinW = ((bytes[16] & 0xFF) << 24) | ((bytes[17] & 0xFF) << 16)
                            | ((bytes[18] & 0xFF) << 8) | (bytes[19] & 0xFF);
                    int skinH = ((bytes[20] & 0xFF) << 24) | ((bytes[21] & 0xFF) << 16)
                            | ((bytes[22] & 0xFF) << 8) | (bytes[23] & 0xFF);
                    if (skinW < 48 || skinH < 32) {
                        toast("这张图片太小了，不是有效的 Minecraft 皮肤（至少要 48×32 像素）。请换一张完整皮肤再试。");
                        return;
                    }
                    String m = SkinRender.isSlim(bytes) ? "slim" : "classic";
                    String name = f.getName().replaceFirst("(?i)\\.png$", "");
                    SkinStore.Custom c = SkinStore.addCustom(acc, bytes, name, m);
                    skinPendingCustom = c;
                    skinPendingSource = "custom";
                    skinPendingModel = m;
                    syncModelBtns();
                    fillCustomList(customList, acc);
                    refreshSkinPreview(acc);
                    toast("已加入「我的皮肤」，点「应用」设为当前");
                } catch (IOException ex) {
                    toast("读取图片失败：" + ex.getMessage());
                }
            }
        });

        Label customTitle = new Label("我的皮肤");
        customTitle.getStyleClass().add("setting-label");

        // 1.2.8：宽臂 / 细臂切换紧贴「我的皮肤」列表；官方皮肤不改模型，只置灰提示。
        HBox modelRow = new HBox(8);
        modelRow.setAlignment(Pos.CENTER_LEFT);
        Label modelHint = new Label("手臂");
        modelHint.getStyleClass().add("setting-desc");
        skinClassicBtn = new Button("宽臂");
        skinSlimBtn = new Button("细臂");
        for (Button b : new Button[]{skinClassicBtn, skinSlimBtn}) {
            b.getStyleClass().add("btn-ghost");
            b.setMinHeight(30);
        }
        skinClassicBtn.setOnAction(e -> chooseSkinModel(acc, "classic"));
        skinSlimBtn.setOnAction(e -> chooseSkinModel(acc, "slim"));
        modelRow.getChildren().addAll(modelHint, skinClassicBtn, skinSlimBtn);

        ScrollPane rightScroll = new ScrollPane(right);
        rightScroll.getStyleClass().add("skin-scroll");
        rightScroll.setFitToWidth(true);
        // 高度跟随左列（3D 预览区），两边等高；之前写死 420，内容一少下面就一片空白
        rightScroll.prefHeightProperty().bind(left.heightProperty());
        rightScroll.setMaxHeight(Region.USE_PREF_SIZE);

        // 1.2.8：宽臂 / 细臂按钮已恢复，禁用态由 syncModelBtns() 按 skinPendingCustom 统一判定。
        // 0.9.9 r6：离线账号可把皮肤打包发给朋友 / 从朋友的包导入。追加在列表末尾，既有按钮顺序不动。
        HBox friendSkinRow = new HBox(10);
        Button exportSkinBtn = new Button("导出皮肤包");
        exportSkinBtn.getStyleClass().add("btn-ghost");
        exportSkinBtn.setMinHeight(34);
        Button importFriendSkinBtn = new Button("导入皮肤包");
        importFriendSkinBtn.getStyleClass().add("btn-ghost");
        importFriendSkinBtn.setMinHeight(34);
        friendSkinRow.getChildren().addAll(exportSkinBtn, importFriendSkinBtn);
        boolean offlineSkinAcc = acc.type == AccountStore.Type.OFFLINE;
        exportSkinBtn.setDisable(!offlineSkinAcc);
        importFriendSkinBtn.setDisable(!offlineSkinAcc);
        if (!offlineSkinAcc) {
            String why = "只有离线账号需要导出或导入皮肤，这个账号的皮肤由服务器管理。";
            exportSkinBtn.setTooltip(new Tooltip(why));
            importFriendSkinBtn.setTooltip(new Tooltip(why));
        }
        exportSkinBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("导出皮肤包");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("皮肤礼包 zip", "*.zip"));
            fc.setInitialFileName("gbl-skin-" + acc.name + ".zip");
            java.io.File f = fc.showSaveDialog(shell.getScene().getWindow());
            if (f == null) {
                return;
            }
            try {
                SkinStore.exportSkinBundle(acc, f.toPath());
                toast("皮肤包已导出");
            } catch (Exception ex) {
                toast("皮肤导出失败：没能生成皮肤文件（" + skinBundleReason(ex)
                        + "）。请换一个保存位置、确认磁盘有空间后再试。");
            }
        });
        importFriendSkinBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("导入皮肤包");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("皮肤礼包 zip", "*.zip"));
            java.io.File f = fc.showOpenDialog(shell.getScene().getWindow());
            if (f == null) {
                return;
            }
            try {
                SkinStore.importSkinBundle(acc, f.toPath());
                fillCustomList(customList, acc);
                refreshSkinPreview(acc);
                refreshUserHead();
                refreshAccountPage();
                toast("皮肤已导入，重新启动游戏生效");
            } catch (Exception ex) {
                toast("皮肤导入失败：这个文件没法用（" + skinBundleReason(ex)
                        + "）。请选择完整的皮肤包（.zip）重试。");
            }
        });

        right.getChildren().addAll(officialTitle, grid,
                importBtn, customTitle, modelRow, customList, friendSkinRow);

        HBox content = new HBox(20, left, rightScroll);
        content.setAlignment(Pos.TOP_CENTER);

        Label status = new Label();
        status.getStyleClass().add("setting-desc");
        status.setWrapText(true);
        status.setMaxWidth(760);
        status.setStyle("-fx-text-fill:#d92d20;");
        status.setVisible(false);

        // 微软账号的「应用」=上传到 Mojang，语义不同就写清楚，避免用户以为只是本地换肤
        Button applyBtn = new Button(acc.type == AccountStore.Type.MICROSOFT ? "上传并应用" : "应用");
        applyBtn.getStyleClass().add("btn-primary");
        applyBtn.setMinHeight(38);
        applyBtn.setMinWidth(120);
        applyBtn.setOnAction(e -> applySkin(acc, status, applyBtn));

        Button resetBtn = new Button("恢复默认");
        resetBtn.getStyleClass().add("btn-ghost");
        resetBtn.setMinHeight(36);
        resetBtn.setMinWidth(96);
        resetBtn.setOnAction(e -> {
            SkinStore.reset(acc);
            skinPendingSource = "steve";
            skinPendingCustom = null;
            skinPendingModel = "classic";
            syncModelBtns();
            highlightSelection(grid, customList);
            refreshSkinPreview(acc);
            refreshUserHead();
            refreshAccountPage();
            toast("已恢复默认皮肤（Steve）");
        });

        Region tail = filler();
        HBox.setHgrow(tail, Priority.ALWAYS);
        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.getChildren().addAll(tail, resetBtn, applyBtn);

        // 1.3.1 修复轮：主体包 ScrollPane——内容多时滚动，底部「应用」按钮永远在卡内
        ScrollPane skinBodyScroll = new ScrollPane(content);
        skinBodyScroll.getStyleClass().add("content-scroll");
        skinBodyScroll.setFitToWidth(true);
        skinBodyScroll.setMaxHeight(Region.USE_PREF_SIZE);
        VBox.setVgrow(skinBodyScroll, Priority.ALWAYS);
        skinPromptCard.getChildren().setAll(head, skinBodyScroll, status, buttons);
        syncModelBtns();
        highlightSelection(grid, customList);
        // 置顶到所有 overlay 之上，避免被其它层盖住而「点了没反应」
        if (shell != null && skinPrompt != null) {
            shell.getChildren().remove(skinPrompt);
            shell.getChildren().add(skinPrompt);
        }
        cancelActiveRipplesBeforeOpenModal();   // 1.0.6
        showOverlayLayer(skinPrompt, "skin-prompt");
    }

    /** 网格里加一个皮肤格子（官方默认或扩展目录）。 */
    private void addSkinCell(FlowPane grid, AccountStore.Account acc, String key, String label) {
        VBox cell = new VBox(4);
        cell.getStyleClass().add("skin-cell");
        cell.setAlignment(Pos.CENTER);
        cell.setUserData(key);
        ImageView hv = new ImageView();
        final byte[][] hold = {null};
        try {
            hold[0] = key.startsWith("gallery:") ? SkinStore.galleryBytes(key) : SkinStore.officialBytes(key);
            hv.setImage(SkinRender.head(hold[0]));
        } catch (IOException ignore) {
            hv.setImage(SkinRender.headOf(acc));
        }
        hv.setFitWidth(48);
        hv.setFitHeight(48);
        hv.setSmooth(false);
        Label nm = new Label(label);
        nm.getStyleClass().add("setting-desc");
        cell.getChildren().addAll(hv, nm);
        cell.setOnMouseClicked(e -> {
            skinPendingSource = key;
            skinPendingCustom = null;
            skinPendingModel = (hold[0] != null && key.startsWith("gallery:"))
                    ? (SkinRender.isSlim(hold[0]) ? "slim" : "classic")
                    : SkinStore.officialModel(key);
            syncModelBtns();
            highlightSelection(grid, null);
            refreshSkinPreview(acc);
        });
        grid.getChildren().add(cell);
    }

    /** 重填「我的皮肤」列表。 */
    private void fillCustomList(VBox box, AccountStore.Account acc) {
        box.getChildren().clear();
        List<SkinStore.Custom> list = SkinStore.listCustoms(acc);
        if (list.isEmpty()) {
            Label empty = new Label("还没有导入的皮肤。点上方「导入自己的 PNG」添加，会保存在本机可反复选用。");
            empty.getStyleClass().add("setting-desc");
            empty.setWrapText(true);
            empty.setMaxWidth(400);
            box.getChildren().add(empty);
            return;
        }
        for (SkinStore.Custom c : list) {
            HBox row = new HBox(10);
            row.getStyleClass().addAll("skin-cell", "skin-custom-row");
            row.setAlignment(Pos.CENTER_LEFT);
            row.setUserData(c.file);
            ImageView hv = new ImageView();
            try {
                hv.setImage(SkinRender.head(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(c.file))));
            } catch (IOException ignore) {
                hv.setImage(SkinRender.headOf(acc));
            }
            hv.setFitWidth(32);
            hv.setFitHeight(32);
            hv.setSmooth(false);
            Label nm = new Label(c.name);
            nm.getStyleClass().add("account-name");
            Region g = filler();
            HBox.setHgrow(g, Priority.ALWAYS);
            Button del = new Button("删除");
            del.getStyleClass().add("btn-danger");
            del.setMinHeight(28);
            del.setOnAction(e -> {
                // 0.9.8：删一条只摘这一行。以前是 clear() 后把整张表重建一遍，
                // 每条皮肤都要重新 readAllBytes + 解 PNG 画头像，攒多了点一下就明显顿。
                runDiskTask(() -> {
                    SkinStore.removeCustom(acc, c);
                    return true;
                }, "删除皮肤 " + c.name,
                        () -> {
                            box.getChildren().remove(row);
                            if (box.getChildren().isEmpty()) {
                                fillCustomList(box, acc);   // 只剩空列表时补回空态提示
                            }
                        },
                        () -> toast("「" + c.name + "」没删掉，可能被别的程序占着"));
                if (skinPendingCustom != null && skinPendingCustom.file.equals(c.file)) {
                    skinPendingCustom = null;
                    skinPendingSource = "steve";
                    skinPendingModel = "classic";
                    syncModelBtns();
                    refreshSkinPreview(acc);
                }
            });
            row.getChildren().addAll(hv, nm, g, del);
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getTarget() != del) {
                    skinPendingCustom = c;
                    skinPendingSource = "custom";
                    skinPendingModel = c.model;
                    syncModelBtns();
                    highlightSelection(null, box);
                    refreshSkinPreview(acc);
                }
            });
            box.getChildren().add(row);
        }
    }

    /**
     * 1.2.8：模型分段按钮的选中态 + 可用态唯一同步口。
     * 仅 skinPendingCustom != null（自导入皮肤）可用；官方 / gallery 一律禁用，
     * 保持 SkinStore.officialModel / SkinRender.isSlim 的自动判定不被回归。
     */
    private void syncModelBtns() {
        if (skinClassicBtn == null || skinSlimBtn == null) {
            return;
        }
        boolean custom = skinPendingCustom != null;
        skinClassicBtn.setDisable(!custom);
        skinSlimBtn.setDisable(!custom);
        String tip = custom
                ? "切换后立即重建 3D 预览；点「应用」保存到这张皮肤。"
                : "只有选中「我的皮肤」里自己导入的皮肤，才能手动选宽臂 / 细臂。";
        skinClassicBtn.setTooltip(new Tooltip(tip));
        skinSlimBtn.setTooltip(new Tooltip(tip));
        skinClassicBtn.getStyleClass().remove("seg-on");
        skinSlimBtn.getStyleClass().remove("seg-on");
        (skinPendingModel.equals("slim") ? skinSlimBtn : skinClassicBtn).getStyleClass().add("seg-on");
    }

    /** 1.2.8：切换自导入皮肤的手臂模型；写回皮肤库，重开弹窗仍是这个模型。 */
    private void chooseSkinModel(AccountStore.Account acc, String model) {
        if (skinPendingCustom == null) {
            return;
        }
        SkinStore.setCustomModel(acc, skinPendingCustom, model);
        skinPendingModel = model;
        syncModelBtns();
        refreshSkinPreview(acc);
    }

    /** 根据当前待定选择，高亮官方网格与「我的皮肤」列表中的对应项。 */
    private void highlightSelection(FlowPane grid, VBox customList) {
        if (grid != null) {
            for (Node n : grid.getChildren()) {
                boolean on = skinPendingCustom == null
                        && n.getUserData() != null && n.getUserData().equals(skinPendingSource);
                if (on) {
                    n.getStyleClass().add("sel");
                } else {
                    n.getStyleClass().remove("sel");
                }
            }
        }
        if (customList != null) {
            for (Node n : customList.getChildren()) {
                boolean on = skinPendingCustom != null
                        && n.getUserData() != null && n.getUserData().equals(skinPendingCustom.file);
                if (on) {
                    n.getStyleClass().add("sel");
                } else {
                    n.getStyleClass().remove("sel");
                }
            }
        }
    }

    /** 解出当前待定选择对应的皮肤字节。 */
    private byte[] resolvePendingBytes(AccountStore.Account acc) throws IOException {
        if (skinPendingCustom != null) {
            return java.nio.file.Files.readAllBytes(java.nio.file.Path.of(skinPendingCustom.file));
        }
        if (SkinStore.builtinDefaults().containsKey(skinPendingSource)) {
            return SkinStore.officialBytes(skinPendingSource);
        }
        if (skinPendingSource.startsWith("gallery:")) {
            return SkinStore.galleryBytes(skinPendingSource);
        }
        return SkinStore.defaultBytes("steve");
    }

    /** 把当前待定选择反映到皮肤预览。 */
    private void refreshSkinPreview(AccountStore.Account acc) {
        if (skin3d == null) {
            return;
        }
        try {
            skin3d.setSkin(resolvePendingBytes(acc), skinPendingModel);
        } catch (IOException ex) {
            try {
                skin3d.setSkin(SkinStore.defaultBytes("steve"), "classic");
            } catch (IOException ignore) {
            }
        }
    }

    /** 应用皮肤：微软=上传到 Mojang（全局真实生效）；离线=本地保存，启动游戏时由 LocalSkinServer 注入。 */
    private void applySkin(AccountStore.Account acc, Label status, Button applyBtn) {
        byte[] bytes;
        try {
            bytes = resolvePendingBytes(acc);
        } catch (IOException ex) {
            status.setStyle("-fx-text-fill:#d92d20;");
            status.setText("读取皮肤数据失败：" + ex.getMessage());
            status.setVisible(true);
            return;
        }
        if (acc.type == AccountStore.Type.MICROSOFT) {
            status.setStyle("-fx-text-fill:#8b93a1;");
            status.setText("正在上传到 Mojang（国内可能需要代理 / 直连）…");
            status.setVisible(true);
            // 上传期间禁用按钮，防手抖重复上传
            applyBtn.setDisable(true);
            applyBtn.setText("正在应用…");
            String model = skinPendingModel;
            new Thread(() -> {
                try {
                    // 上传前先看令牌是否快过期：拿过期令牌上传只会 401，用户还以为是皮肤有问题。
                    // 0.8.6 D6：刷新失败不再直接判死刑——网络/服务端瞬态照样用已缓存令牌试一次上传；
                    // 令牌真失效时 uploadSkin 自己抛 TOKEN_INVALID，由下面统一给准确提示。
                    AccountStore.Account acc2 = acc;
                    if (MicrosoftAuth.needsRefresh(acc2.expiresAt)) {
                        try {
                            MicrosoftAuth.MinecraftAccount mc = MicrosoftAuth.refresh(acc2.refreshToken);
                            acc2 = acc2.withToken(mc.accessToken, mc.refreshToken, mc.expiresAt);
                            accountStore = accountStore.withTokenRefreshed(
                                    acc2.id, mc.accessToken, mc.refreshToken, mc.expiresAt);
                            accountStore.save();
                        } catch (Exception rex) {
                            MicrosoftAuth.RefreshKind rk = MicrosoftAuth.classify(rex);
                            System.out.println("[msoauth] 皮肤上传前刷新失败 kind=" + rk
                                    + "（" + msoRefreshFailureText(rk)
                                    + "），改用已缓存令牌继续尝试上传");
                        }
                    }
                    MicrosoftAuth.uploadSkin(acc2.accessToken, bytes, model);
                    // 上传成功：本地也存一份，让启动器头像跟着显示
                    SkinStore.setCustom(acc, bytes, model);
                    Platform.runLater(() -> {
                        status.setStyle(successTextStyle());
                        status.setText("已上传到 Mojang，游戏内全局生效（别人也看得到）；启动器头像也已更新。");
                        status.setVisible(true);
                        applyBtn.setDisable(false);
                        applyBtn.setText("上传并应用");
                        refreshUserHead();
                        refreshAccountPage();
                        toast("皮肤已上传");
                    });
                } catch (Exception ex) {
                    MicrosoftAuth.RefreshKind rk = MicrosoftAuth.classify(ex);
                    String detail = ex.getMessage() == null ? "" : ex.getMessage();
                    String tip = rk == MicrosoftAuth.RefreshKind.TOKEN_INVALID
                            ? "微软登录已失效，请到账号页重新登录微软账号后再上传（不用删除账号）。"
                            : "（" + msoRefreshFailureText(rk) + "）可稍后重试。";
                    Platform.runLater(() -> {
                        status.setStyle("-fx-text-fill:#d92d20;");
                        status.setText("上传失败：" + detail + "。" + tip);
                        status.setVisible(true);
                        applyBtn.setDisable(false);
                        applyBtn.setText("上传并应用");
                    });
                }
            }).start();
        } else {
            // 离线账号：本地保存 + 启动游戏时由 LocalSkinServer 注入（仅本机可见）
            try {
                if (skinPendingCustom != null) {
                    SkinStore.selectCustom(acc, skinPendingCustom);
                } else {
                    SkinStore.setDefault(acc, skinPendingSource);
                }
                refreshUserHead();
                refreshAccountPage();
                status.setStyle(successTextStyle());
                status.setText("已保存本地皮肤（启动游戏时注入，仅本机可见）。");
                status.setVisible(true);
                toast("皮肤已保存");
            } catch (Exception ex) {
                status.setStyle("-fx-text-fill:#d92d20;");
                status.setText("保存失败：" + ex.getMessage());
                status.setVisible(true);
            }
        }
    }

    /** 把皮肤礼包导出/导入的底层失败翻译成用户能看懂的一句话，不把异常类名或堆栈甩到界面上。 */
    private static String skinBundleReason(Exception ex) {
        String msg = ex == null ? null : ex.getMessage();
        if (msg == null || msg.isBlank()) {
            return "文件读不出来，可能已经损坏";
        }
        String low = msg.toLowerCase(Locale.ROOT);
        if (low.contains("zip") || low.contains("entry") || low.contains("closed")) {
            return "文件不是完整的皮肤礼包";
        }
        if (low.contains("denied") || low.contains("access") || low.contains("permission")) {
            return "没有读取或保存这个位置的权限";
        }
        return msg.length() > 60 ? msg.substring(0, 60) + "…" : msg;
    }

    private void closeSkinManager() {
        cancelAllRipples();   // 1.0.5
        // 1.1.3：服务器皮肤卡片的引用及时摘掉，后台返回时就不会刷到已关闭的界面
        littleSkinServerHead = null;
        littleSkinServerDetail = null;
        littleSkinServerUuid = null;
        if (skin3d != null) {
            skin3d.stop();
            skin3d = null;
        }
        if (skinPrompt != null) {
            hideOverlayLayer(skinPrompt, "skin-prompt");
        }
    }

    /** 用本机默认浏览器打开一个网址（优先 Desktop.browse，失败走系统命令兜底）。 */
    private void openInBrowser(String uri) {
        if (uri == null || uri.isBlank()) {
            return;
        }
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(uri));
                return;
            }
        } catch (Throwable ignored) {
            // Desktop 不可用（如无显示环境）就走系统命令兜底
        }
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                Runtime.getRuntime().exec(new String[]{"rundll32", "url.dll,FileProtocolHandler", uri});
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", uri});
            } else {
                Runtime.getRuntime().exec(new String[]{"xdg-open", uri});
            }
        } catch (Throwable ignored) {
            // 兜底也失败就什么都不做，用户还能手动复制链接
        }
    }

    /**
     * 启动加载层：准备阶段（补依赖库、补资源、解压 natives）盖在整窗上的一张卡。
     *
     * <p><b>为什么要这层：</b>以前点「启动游戏」只有控制台上一行小字在动，
     * 那几秒看着跟界面卡死没区别——第一次装的版本要现补几十个库、三千多个资源，
     * 干等两三分钟却没有任何「它在动」的证据，用户第二次点就会以为启动器坏了。
     * 这层给的是「正在动 + 动到哪了 + 现在可以撤」三件事。
     *
     * <p><b>挡得住，但只认两个出口：</b>底层 backdrop 是实打实的 Region（不穿透鼠标），
     * 底下的按钮点不着；可「挡死」不等于「关不掉」——{@code 取消} 和 Esc 都能撤。
     * 这里**不接「点外面关掉」**：启动是要花时间的正事，手一抖点到边上就把 begun 掐了，
     * 比多按一下取消按钮难受得多（v0.3.2 那个复用遮罩黑幕的教训也在这：遮罩归遮罩，
     * 出口得单独给）。
     */
    private Node buildLaunchOverlay() {
        VBox card = new VBox(14);
        card.getStyleClass().addAll("card", "launch-card");
        card.setPadding(new Insets(22, 24, 20, 24));
        card.setMinWidth(340);
        card.setPrefWidth(420);
        card.setAlignment(Pos.TOP_CENTER);

        VBox head = new VBox(6);
        launchTitle = new Label("正在启动");
        launchTitle.getStyleClass().add("launch-title");
        launchStage = new Label("正在准备…");
        launchStage.getStyleClass().add("launch-stage");
        launchStage.setWrapText(true);
        head.getChildren().addAll(launchTitle, launchStage);

        // 进度条跟队列那行一个画法：轨道 + fill，fill 的宽高都绑轨道。
        // 高度不给就是 0（Pane 摆 child 按 child 自己的 pref 宽高），屏幕上根本看不见；
        // 宽度绑比例 property，进度从外部改 ratio 就行，别去重新设 prefWidth。
        // r4 进度条宽度解耦：给 track 一个与 launchFill 无关的固定 prefWidth，切断
        // 「fill.prefWidth ← track.width × ratio」的自引用环（VBox 按 fillWidth 再把轨道撑满
        // 卡片，但 track 的 pref 不再由子节点计算，ratio 变化不会回灌成灰轨变宽）。
        StackPane track = new StackPane();
        track.setAlignment(Pos.CENTER_LEFT);
        track.getStyleClass().add("queue-progress");
        track.setPrefHeight(6);
        track.setMinHeight(6);
        track.setMaxWidth(Double.MAX_VALUE);
        track.setPrefWidth(360);
        launchFill = new Region();
        launchFill.setMinWidth(0);
        launchFill.setMaxWidth(Region.USE_PREF_SIZE);
        launchFill.getStyleClass().addAll("queue-progress-fill", "launch-progress-fill");
        launchFill.prefHeightProperty().bind(track.heightProperty());
        launchFill.prefWidthProperty().bind(track.widthProperty().multiply(launchRatio));
        track.getChildren().add(launchFill);
        StackPane.setAlignment(launchFill, Pos.CENTER_LEFT);

        Label tip = new Label("第一次启动要补依赖库和资源，可能要几分钟；不放心可以点取消。");
        tip.getStyleClass().add("launch-tip");
        tip.setWrapText(true);

        Button cancel = new Button("取消");
        cancel.getStyleClass().addAll("btn-ghost", "launch-cancel");
        cancel.setMinHeight(30);
        cancel.setMinWidth(88);
        cancel.setOnAction(e -> cancelLaunch());

        card.getChildren().addAll(head, track, tip, cancel);

        // 卡片包一层居中 VBox：StackPane 会把直接子节点抻满整窗，
        // 不这么包，卡会被拉成整窗宽（下载确认弹层踩过同一个坑）
        VBox wrap = new VBox(card);
        wrap.setAlignment(Pos.CENTER);
        wrap.setFillWidth(false);

        Region backdrop = new Region();
        backdrop.getStyleClass().add("launch-backdrop");

        StackPane layer = new StackPane(backdrop, wrap);
        layer.getStyleClass().add("launch-layer");
        layer.setVisible(false);
        layer.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                cancelLaunch();
            }
        });
        launchLayer = layer;
        return layer;
    }

    /**
     * r4 启动进度数值出口：把 UI 订阅到服务层 {@link LaunchProgress} 总线。
     * 打开启动对话框时挂 Sink、关闭对话框时 {@link LaunchProgress#clear()}，一对一不泄漏。
     * 服务层在后台线程 emit，这里统一用 {@code Platform.runLater} 切回 FX 线程，并用
     * {@code launchProgressActive} + 节点判活丢弃「对话框已关」之后迟到的帧。
     */
    private void wireLaunchProgress() {
        launchProgressActive = true;
        // r4 指纹：字符串字面量（与 vanilla 组 GameLauncher 的落地重复无害，注释不进常量池）
        System.out.println("[r4] " + "r4 启动进度数值出口" + "：UI 已订阅 LaunchProgress 数值总线");
        LaunchProgress.setSink((stage, done, total, bytes, ratio) -> {
                    // 1.2：服务层每来一帧就刷新心跳（后台线程，AtomicLong 安全）；
                    // 持续补库/资源时看门狗每轮都看到新鲜心跳，绝不误杀下载中的 worker。
                    lastLaunchProgressAt.set(System.nanoTime());
                    Platform.runLater(() -> {
                    if (!launchProgressActive || launchStage == null || launchFill == null) {
                        return;   // 对话框已关 / 节点已重建：丢弃迟到帧，不碰旧 UI
                    }
                    if (ratio >= 0) {
                        // 确定态：数值比例驱动 fill 宽度，文案带文件数与人类可读字节。
                        stopLaunchIndeterminate();
                        launchRatio.set(Math.max(0.0, Math.min(1.0, ratio)));
                        // 收尾帧「游戏已退出」ratio=1.0 但 done/total=-1：别把 -1/-1 印到界面上
                        launchStage.setText(done >= 0 && total > 0
                                ? stage + " (" + done + "/" + total + ", " + humanSize(bytes) + ")"
                                : stage);
                    } else {
                        // 不确定态（补依赖准备 / JVM 已拉起）：挂 .indeterminate 类 + 呼吸动画，
                        // 不把进度条定格在那个固定小绿块上。
                        launchStage.setText(stage);
                        startLaunchIndeterminate();
                    }
                    });
                });
    }

    /** r4：不确定态脉动动画。JavaFX CSS 不支持 @keyframes，用 Timeline 驱动 opacity 呼吸。 */
    private void startLaunchIndeterminate() {
        if (launchFill == null) {
            return;
        }
        if (!launchFill.getStyleClass().contains("indeterminate")) {
            launchFill.getStyleClass().add("indeterminate");
        }
        if (launchIndeterminate == null) {
            launchIndeterminate = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(launchFill.opacityProperty(), 1.0)),
                    new KeyFrame(Duration.millis(650),
                            new KeyValue(launchFill.opacityProperty(), 0.3)));
            launchIndeterminate.setCycleCount(javafx.animation.Animation.INDEFINITE);
            launchIndeterminate.setAutoReverse(true);
        }
        launchIndeterminate.play();
    }

    /** r4：退出不确定态 / 关闭对话框时停动画、摘样式类、恢复不透明度。 */
    private void stopLaunchIndeterminate() {
        if (launchIndeterminate != null) {
            launchIndeterminate.stop();
        }
        if (launchFill != null) {
            launchFill.getStyleClass().remove("indeterminate");
            launchFill.setOpacity(1.0);
        }
    }

    /** 打开加载层；阶段文字和进度先复位，免得上一次启动的「39/61」挂在上面。 */
    private void openLaunchOverlay(String versionId) {
        if (launchLayer == null) {
            return;
        }
        launchTitle.setText("正在启动 " + versionId);
        launchStage.setText("正在准备…");
        launchRatio.set(0.12);
        // 1.2：新一轮启动从这一刻开始计「无进度」宽限，避免沿用上一轮的旧心跳。
        lastLaunchProgressAt.set(System.nanoTime());
        stopLaunchIndeterminate();       // 上次若停在不确定态，先复位动画
        wireLaunchProgress();            // r4：订阅服务层数值进度总线（关闭时 clear）
        cancelActiveRipplesBeforeOpenModal();   // 1.0.6：开启动层前先清涟漪
        showOverlayLayer(launchLayer, "launch-overlay");
    }

    /** 收起加载层：进程拉起来（或准备失败）之后调用。 */
    private void closeLaunchOverlay() {
        cancelAllRipples();   // 1.0.5
        if (launchLayer != null) {
            hideOverlayLayer(launchLayer, "launch-overlay");
        }
        // r4：所有关闭路径（正常 / 取消 / 异常）都从这里退订，避免 Sink 泄漏持有旧 UI。
        launchProgressActive = false;
        LaunchProgress.clear();
        stopLaunchIndeterminate();
        launching = false;
        refreshLaunchButton();
    }

    /** 点「取消」/ 按 Esc：中止这一轮启动，界面立刻还给用户。 */
    private void cancelLaunch() {
        if (launchCancelled.compareAndSet(false, true)) {
            launchEpoch++;   // 1.0.9：上一轮的超时线程立即作废
            System.out.println("[launch] 用户取消了这次启动");
        }
        closeLaunchOverlay();
        launching = false;
        refreshLaunchButton();
        if (launchWorker != null) {
            // 打断的是准备线程：它要么在跑网络请求、要么在跑文件 IO，
            // 打断不一定能立刻停下，但**新一轮启动不会再拉起进程**——这才是不卡界面的关键
            launchWorker.interrupt();
        }
    }

    /** 每次打开重填标题区：head 每次重建一份挂在卡上，旧的那份连着旧事件一起丢掉。 */
    private void openPrompt(String mode, GameEntry game) {
        promptMode = mode;
        promptGame = game;

        Label title = new Label(mode.equals("rename") ? "重命名游戏" : "删除游戏");
        title.getStyleClass().add("dlg-title");
        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);
        // 右上角那枚 × 走自绘 Pane：默认皮肤会往按钮上画灰方块和焦点环
        Pane close = winBtn("dlg-close", Icons.strokeScaled(Icons.WIN_CLOSE, WIN_ICON, 1.6),
                BTN_HOVER_BG, STROKE_HOVER);
        close.setOnMouseClicked(e -> closePrompt());
        HBox head = new HBox(8);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(title, gap, close);

        gamePromptBody = new VBox(10);
        gamePromptBody.getChildren().addAll(promptContent());

        Button cancel = new Button("取消");
        cancel.getStyleClass().add("btn-ghost");
        cancel.setMinHeight(36);
        cancel.setMinWidth(88);
        cancel.setOnMouseClicked(e -> closePrompt());

        boolean rename = "rename".equals(mode);
        Button ok = new Button(rename ? "重命名" : "删除");
        ok.getStyleClass().add(rename ? "btn-download" : "btn-danger");
        ok.setMinHeight(36);
        ok.setMinWidth(88);
        ok.setOnMouseClicked(e -> promptConfirm());

        Region tail = filler();
        HBox.setHgrow(tail, Priority.ALWAYS);
        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.getChildren().addAll(cancel, tail, ok);

        promptCard.getChildren().setAll(head, gamePromptBody, buttons);
        showOverlayLayer(gamePrompt, "game-prompt");
    }

    // ---------------------------------------------------------------- 安装新游戏弹窗（HMCL 风格）

    /** 弹出「安装新游戏」：实例名 + 8 张组件卡（Minecraft / Forge / NeoForge / OptiFine / Fabric / Fabric API / Quilt / QSL），右下「安装」。 */
    private Node buildInstallDialog() {
        // 1.2.6：宽度收窄到 560（上限 580），高度完全由内容撑开（八卡紧凑等高，≤460）。
        VBox card = new VBox(6);
        card.getStyleClass().addAll("card", "install-card");
        card.setPadding(new Insets(12, 18, 10, 18));
        card.setMinWidth(Region.USE_PREF_SIZE);
        card.setPrefWidth(560);
        card.setMaxWidth(580);
        card.setMinHeight(Region.USE_PREF_SIZE);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        installCard = card;

        Label title = new Label("安装新游戏");
        title.getStyleClass().add("dlg-title");
        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Pane close = winBtn("dlg-close", Icons.strokeScaled(Icons.WIN_CLOSE, WIN_ICON, 1.6),
                BTN_HOVER_BG, STROKE_HOVER);
        close.setOnMouseClicked(e -> closeInstallDialog());
        HBox head = new HBox(12, title, gap, close);
        head.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label("实例名");
        nameLabel.getStyleClass().add("page-desc");
        installNameField.getStyleClass().add("prompt-field");
        installNameField.setPrefHeight(34);
        installNameField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(installNameField, Priority.ALWAYS);
        VBox nameBox = new VBox(6, nameLabel, installNameField);

        VBox colA = new VBox(6);   // 1.2.6：八张加载器卡紧凑等高，行距 6
        VBox colB = new VBox(6);
        Pane mc = installCompCard("minecraft", "Minecraft", "必装", installMcVersion, false, false, null);
        Pane forge = installCompCard(LoaderType.FORGE.name(), "Forge", "可选", null, true, false,
                () -> selectInstallLoader(LoaderType.FORGE));
        Pane neo = installCompCard(LoaderType.NEOFORGE.name(), "NeoForge", "可选", null, true, false,
                () -> selectInstallLoader(LoaderType.NEOFORGE));
        Pane opt = installCompCard("optifine", "OptiFine", "可选", null, true, false,
                () -> selectInstallLoader(LoaderType.OPTIFINE));
        Pane fabric = installCompCard(LoaderType.FABRIC.name(), "Fabric", "可选", null, true, false,
                () -> selectInstallLoader(LoaderType.FABRIC));
        Pane fabricApi = installCompCard("fabric-api", "Fabric API", "随 Fabric 自动安装", null, false, false, null);
        Pane quilt = installCompCard(LoaderType.QUILT.name(), "Quilt", "可选", null, true, false,
                () -> selectInstallLoader(LoaderType.QUILT));
        Pane qsl = installCompCard("qsl", "QSL", "随 Quilt 自动安装", null, false, false, null);
        colA.getChildren().addAll(mc, neo, fabric, quilt);
        colB.getChildren().addAll(forge, opt, fabricApi, qsl);
        HBox grid = new HBox(10, colA, colB);
        grid.getStyleClass().add("install-grid");
        HBox.setHgrow(colA, Priority.ALWAYS);
        HBox.setHgrow(colB, Priority.ALWAYS);

        // Fabric / Quilt 卡内嵌版本下拉（默认隐藏，选中才露）
        // 0.9.1：这 5 个下拉原先没挂弹层深色样式表，展开后是透明的，统统接到 wireDarkPopup。
        ComboBox<String> fabricBox = new ComboBox<>();
        wireDarkPopup(fabricBox);
        fabricBox.getStyleClass().add("setting-select");
        fabricBox.setVisible(false);
        fabricBox.setManaged(false);   // 1.2.5：隐藏控件不占 VBox 高度（卡片下移根因）
        fabricBox.setPrefWidth(180);
        fabricBox.setMaxWidth(Double.MAX_VALUE);
        installLoaderBoxes.put(LoaderType.FABRIC, fabricBox);
        installLoaderCards.put(LoaderType.FABRIC, fabric);
        ((VBox) fabric).getChildren().add(fabricBox);
        ComboBox<String> quiltBox = new ComboBox<>();
        wireDarkPopup(quiltBox);
        quiltBox.getStyleClass().add("setting-select");
        quiltBox.setVisible(false);
        quiltBox.setManaged(false);   // 1.2.5：隐藏控件不占 VBox 高度（卡片下移根因）
        quiltBox.setPrefWidth(180);
        quiltBox.setMaxWidth(Double.MAX_VALUE);
        installLoaderBoxes.put(LoaderType.QUILT, quiltBox);
        installLoaderCards.put(LoaderType.QUILT, quilt);
        ((VBox) quilt).getChildren().add(quiltBox);
        ComboBox<String> forgeBox = new ComboBox<>();
        wireDarkPopup(forgeBox);
        forgeBox.getStyleClass().add("setting-select");
        forgeBox.setVisible(false);
        forgeBox.setManaged(false);   // 1.2.5：隐藏控件不占 VBox 高度（卡片下移根因）
        forgeBox.setPrefWidth(180);
        forgeBox.setMaxWidth(Double.MAX_VALUE);
        installLoaderBoxes.put(LoaderType.FORGE, forgeBox);
        installLoaderCards.put(LoaderType.FORGE, forge);
        ((VBox) forge).getChildren().add(forgeBox);
        ComboBox<String> neoBox = new ComboBox<>();
        wireDarkPopup(neoBox);
        neoBox.getStyleClass().add("setting-select");
        neoBox.setVisible(false);
        neoBox.setManaged(false);   // 1.2.5：隐藏控件不占 VBox 高度（卡片下移根因）
        neoBox.setPrefWidth(180);
        neoBox.setMaxWidth(Double.MAX_VALUE);
        installLoaderBoxes.put(LoaderType.NEOFORGE, neoBox);
        installLoaderCards.put(LoaderType.NEOFORGE, neo);
        ((VBox) neo).getChildren().add(neoBox);
        // OptiFine 版本下拉：可编辑——列表拉不到时用户可手填「HD_U/I5」这类版本串（D6 降级）
        ComboBox<String> optifineBox = new ComboBox<>();
        wireDarkPopup(optifineBox);
        optifineBox.getStyleClass().add("setting-select");
        optifineBox.setVisible(false);
        optifineBox.setManaged(false);   // 1.2.5：隐藏控件不占 VBox 高度（卡片下移根因）
        optifineBox.setPrefWidth(180);
        optifineBox.setMaxWidth(Double.MAX_VALUE);
        optifineBox.setEditable(true);
        optifineBox.setPromptText("版本（可手动填写，例如 HD U I5）");
        installLoaderBoxes.put(LoaderType.OPTIFINE, optifineBox);
        installLoaderCards.put(LoaderType.OPTIFINE, opt);
        ((VBox) opt).getChildren().add(optifineBox);

        installStatus.getStyleClass().add("page-desc");

        installLoaderHint = new Label();
        installLoaderHint.getStyleClass().add("optifine-scope-hint");
        installLoaderHint.setWrapText(true);
        installLoaderHint.setVisible(false);
        installLoaderHint.setManaged(false);   // 1.2.6：隐藏提示不占高度，卡高由内容真实撑开

        Button cancel = new Button("取消");
        cancel.getStyleClass().add("btn-ghost");
        cancel.setMinHeight(36);
        cancel.setOnAction(e -> closeInstallDialog());
        installRunBtn = new Button("安装");
        installRunBtn.getStyleClass().add("btn-download");
        installRunBtn.setMinHeight(36);
        installRunBtn.setPrefWidth(96);
        installRunBtn.setOnAction(e -> runInstall());
        Region tail = filler();
        HBox.setHgrow(tail, Priority.ALWAYS);
        HBox foot = new HBox(12, tail, cancel, installRunBtn);
        foot.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().addAll(head, nameBox, grid, installStatus, installLoaderHint, foot);

        Region backdrop = new Region();
        backdrop.getStyleClass().add("dlg-backdrop");
        backdrop.setOnMouseClicked(e -> closeInstallDialog());
        VBox shell = new VBox(card);
        shell.setAlignment(Pos.CENTER);
        shell.setFillWidth(false);
        StackPane layer = new StackPane(backdrop, shell);
        layer.getStyleClass().add("dlg-layer");
        layer.setVisible(false);
        layer.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                closeInstallDialog();
            }
        });
        installLayer = layer;
        return layer;
    }

    /** 0.8.9 R-03：HMCL 风格加载器图标资源目录（fabric=纸 / forge=铁砧 / neoforge=狐狸 …）。 */
    private static final String LOADER_ICON_DIR = "/com/mclauncher/loaders/";

    /** 组件卡 key / LoaderType.name() → 图标文件名；三处 UI 共用同一张表，不各写一份。 */
    private static String loaderIconFile(String key) {
        if (key == null) {
            return null;
        }
        return switch (key) {
            case "FABRIC", "fabric", "fabric-api" -> "fabric.png";
            case "FORGE", "forge" -> "forge.png";
            case "NEOFORGE", "neoforge" -> "neoforge.png";
            case "QUILT", "quilt", "qsl" -> "quilt.png";
            case "OPTIFINE", "optifine" -> "optifine.png";
            case "LEGACY_FABRIC", "legacyfabric" -> "legacyfabric.png";
            case "minecraft" -> "grass.png";
            case "cleanroom" -> "cleanroom.png";
            default -> null;
        };
    }

    /**
     * 0.8.9 R-03：所有出现模组加载器的地方都带加载器图片（下载页加载器 tab、
     * 安装新游戏弹窗、游戏管理页已装加载器行）。资源从模块内 classpath 读，
     * 由 build 脚本从 src/main/resources 打进 jar；缺图 / 解码失败时回退到原来的
     * CUBE 矢量图标，绝不因资源问题让整张卡消失或抛异常。
     */
    private static Node loaderIconNode(LoaderType type, String key, double size) {
        String file = type == null ? null : loaderIconFile(type.name());
        if (file == null) {
            file = loaderIconFile(key);
        }
        if (file != null) {
            try (java.io.InputStream in =
                         ShellView.class.getResourceAsStream(LOADER_ICON_DIR + file)) {
                if (in != null) {
                    ImageView iv = new ImageView(new Image(in));
                    iv.setFitWidth(size);
                    iv.setFitHeight(size);
                    iv.setPreserveRatio(true);
                    iv.setSmooth(true);
                    iv.getStyleClass().addAll("comp-icon", "loader-icon");
                    return iv;
                }
            } catch (Exception ignore) {
                // 落到下面的矢量兜底
            }
        }
        SVGPath fallback = Icons.create(Icons.CUBE, size);
        fallback.getStyleClass().add("comp-icon");
        return fallback;
    }

    /** 0.9.0 R-04：版本 Kind → 块图标文件名（与下载页类型徽标一套口径）。 */
    private static String loaderIconFile(VersionManifest.Kind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case RELEASE -> "grass.png";
            case SNAPSHOT -> "command.png";
            case APRIL -> "april_fools.png";
            case LEGACY -> "craft_table.png";
        };
    }

    /**
     * 0.9.0 R-04：游戏版本块图标（下载页版本行 / 安装弹窗 Minecraft 卡 / 游戏行 / 管理页状态卡）。
     * 复用 classpath 读取 + Icons.CUBE 兜底，缺图 / 解码失败不抛、不 NPE。
     */
    private static Node versionBlockIcon(VersionManifest.Kind kind, double size) {
        String file = loaderIconFile(kind);
        if (file != null) {
            try (java.io.InputStream in =
                         ShellView.class.getResourceAsStream(LOADER_ICON_DIR + file)) {
                if (in != null) {
                    ImageView iv = new ImageView(new Image(in));
                    iv.setFitWidth(size);
                    iv.setFitHeight(size);
                    iv.setPreserveRatio(true);
                    iv.setSmooth(true);
                    iv.getStyleClass().addAll("comp-icon", "loader-icon");
                    return iv;
                }
            } catch (Exception ignore) {
                // 落到矢量兜底
            }
        }
        SVGPath fallback = Icons.create(Icons.CUBE, size);
        fallback.getStyleClass().add("comp-icon");
        return fallback;
    }

    /** 0.9.0 R-06：内容管理行左侧图标（世界 / 资源 / 光影），缺图回退 CUBE。 */
    private static Node contentIcon(String file, double size) {
        if (file != null) {
            try (java.io.InputStream in =
                         ShellView.class.getResourceAsStream(LOADER_ICON_DIR + file)) {
                if (in != null) {
                    ImageView iv = new ImageView(new Image(in));
                    iv.setFitWidth(size);
                    iv.setFitHeight(size);
                    iv.setPreserveRatio(true);
                    iv.setSmooth(true);
                    iv.getStyleClass().addAll("comp-icon", "loader-icon");
                    return iv;
                }
            } catch (Exception ignore) {
                // 落到矢量兜底
            }
        }
        SVGPath fallback = Icons.create(Icons.CUBE, size);
        fallback.getStyleClass().add("comp-icon");
        return fallback;
    }

    /** 从磁盘任意路径加载封面图（如世界存档的 icon.png），失败回退 CUBE。 */
    private static Node contentIconPath(Path file, double size) {
        if (file != null) {
            try {
                Image img = new Image(file.toUri().toString(), size, size, true, true);
                if (!img.isError()) {
                    ImageView iv = new ImageView(img);
                    iv.setFitWidth(size);
                    iv.setFitHeight(size);
                    iv.setPreserveRatio(true);
                    iv.setSmooth(true);
                    iv.getStyleClass().addAll("comp-icon", "loader-icon");
                    return iv;
                }
            } catch (Exception ignore) {
                // 落到矢量兜底
            }
        }
        SVGPath fallback = Icons.create(Icons.CUBE, size);
        fallback.getStyleClass().add("comp-icon");
        return fallback;
    }

    /** 0.9.0 R-04：GameEntry.Kind 名（RELEASE/SNAPSHOT/APRIL/LEGACY）→ VersionManifest.Kind。
     *  （GameEntry.Kind 是包级私有，跨包只能拿它的 name() 字符串来映射，不能直接写类型名。） */
    private static VersionManifest.Kind gameTypeToVm(String kindName) {
        if (kindName == null) {
            return null;
        }
        return switch (kindName) {
            case "RELEASE" -> VersionManifest.Kind.RELEASE;
            case "SNAPSHOT" -> VersionManifest.Kind.SNAPSHOT;
            case "APRIL" -> VersionManifest.Kind.APRIL;
            case "LEGACY" -> VersionManifest.Kind.LEGACY;
            default -> null;
        };
    }

    /** 一张组件卡：标题 + 副标题（状态 / 版本徽标）+ 可选点击。0.8.9：图标换加载器图片。 */
    private VBox installCompCard(String key, String title, String sub, String lockVersion,
                                 boolean selectable, boolean disabled, Runnable onClick) {
        VBox card = new VBox(6);   // 1.2.5：组件卡内部间距收紧，八卡等高
        card.getStyleClass().addAll("comp-card");
        if (disabled) {
            card.getStyleClass().add("disabled");
        }
        card.setPadding(new Insets(9, 12, 9, 12));
        card.setCursor(selectable ? Cursor.HAND : Cursor.DEFAULT);
        card.setFillWidth(true);

        HBox top = new HBox(9);
        top.setAlignment(Pos.CENTER_LEFT);
        Node icon = loaderIconNode(null, key, 20);
        if ("minecraft".equals(key)) {
            installMcIcon = icon;
        }
        Label t = new Label(title);
        t.getStyleClass().add("comp-title");
        Region g = filler();
        HBox.setHgrow(g, Priority.ALWAYS);
        top.getChildren().addAll(icon, t, g);
        if (lockVersion != null) {
            Label badge = new Label(lockVersion);
            badge.getStyleClass().add("comp-badge");
            top.getChildren().add(badge);
            installCompBadge.put(key, badge);
        }

        Label s = new Label(sub);
        s.getStyleClass().add("comp-sub");
        installCompSub.put(key, s);

        card.getChildren().addAll(top, s);
        installCompCards.put(key, card);
        if (selectable && onClick != null) {
            card.setOnMouseClicked(e -> onClick.run());
        }
        return card;
    }

    /** 打开弹窗：锁死 Minecraft 卡为当前点的版本，重置加载器选择。 */
    private void openInstallDialog(VersionManifest.OfficialVersion v) {
        installVersion = v;
        installMcVersion = v.id();
        installNameField.setText(v.id());
        installLoader = null;
        for (LoaderType lt : INSTALL_LOADERS) {
            Pane card = installLoaderCards.get(lt);
            if (card != null) {
                card.getStyleClass().remove("selected");
            }
            ComboBox<String> box = installLoaderBoxes.get(lt);
            if (box != null) {
                box.setVisible(false);
                box.setManaged(false);   // 1.2.5：隐藏控件不占 VBox 高度（卡片下移根因）
                box.getItems().clear();
                box.setDisable(true);
            }
        }
        Label mcBadge = installCompBadge.get("minecraft");
        if (mcBadge != null) {
            mcBadge.setText(v.id());
        }
        // 0.9.0 R-04：Minecraft 卡图标随选中版本类型切换（RELEASE/SNAPSHOT/APRIL/LEGACY）
        if (installMcIcon != null && installMcIcon.getParent() instanceof HBox mcTop) {
            int mcIdx = mcTop.getChildren().indexOf(installMcIcon);
            if (mcIdx >= 0) {
                Node mcNewIcon = versionBlockIcon(v.kind(), 20);
                mcTop.getChildren().set(mcIdx, mcNewIcon);
                installMcIcon = mcNewIcon;
            }
        }
        Label fa = installCompSub.get("fabric-api");
        if (fa != null) {
            fa.setText("随 Fabric 自动安装");
        }
        Label qs = installCompSub.get("qsl");
        if (qs != null) {
            qs.setText("随 Quilt 自动安装");
        }
        installStatus.setText("");
        cancelActiveRipplesBeforeOpenModal();   // 1.0.6
        if (installLayer != null) {
            showOverlayLayer(installLayer, "install-dialog");
        }
    }

    private void closeInstallDialog() {
        cancelAllRipples();   // 1.0.5
        if (installLayer != null) {
            hideOverlayLayer(installLayer, "install-dialog");
        }
    }

    // ---------------------------------------------------------- 自动更新 UI（UpdateController.UpdateUi）

    /**
     * 中央白卡「发现更新 X」对话框（仿 buildInstallDialog 的 dlg-backdrop + dlg-layer 结构）：
     * 深色半透明遮罩 + 居中白卡，含标题 / 正文 / 进度区（初始隐藏）/ 更新 + 取消 两按钮。
     * 结构 build 一次，show 时再填文案与回调并 reset 进度区；setVisible 反复切换。
     */
    private Node buildUpdateDialog() {
        VBox card = new VBox(14);
        card.getStyleClass().addAll("card", "update-dialog");
        card.setPadding(new Insets(20, 22, 18, 22));
        card.setMaxWidth(420);

        updateDlgTitle = new Label("发现更新");
        updateDlgTitle.getStyleClass().add("dlg-title");

        updateDlgBody = new Label();
        updateDlgBody.getStyleClass().add("page-desc");
        updateDlgBody.setWrapText(true);

        // 进度区：阶段文案 + 进度条，初始都隐藏，updateProgress 时再露
        updateDlgStage = new Label();
        updateDlgStage.getStyleClass().add("page-desc");
        updateDlgStage.setWrapText(true);
        updateDlgStage.setVisible(false);
        updateDlgBar = new ProgressBar(0);
        updateDlgBar.getStyleClass().add("update-progress");
        updateDlgBar.setMaxWidth(Double.MAX_VALUE);
        updateDlgBar.setVisible(false);
        VBox progressBox = new VBox(8, updateDlgStage, updateDlgBar);

        updateDlgUpdateBtn = new Button("更新");
        updateDlgUpdateBtn.getStyleClass().add("btn-play");
        updateDlgUpdateBtn.setMinHeight(46);
        updateDlgUpdateBtn.setMaxWidth(Double.MAX_VALUE);
        updateDlgCancelBtn = new Button("取消");
        updateDlgCancelBtn.getStyleClass().add("btn-ghost");
        updateDlgCancelBtn.setMinHeight(46);
        updateDlgCancelBtn.setMaxWidth(Double.MAX_VALUE);
        Region tail = filler();
        HBox.setHgrow(tail, Priority.ALWAYS);
        // 0.9.9 r6：失败时的一键手动下载出口，平时隐藏，showUpdateFailed 里再显示。
        updateDlgManualLink = new Button("打开官网手动下载");
        updateDlgManualLink.getStyleClass().add("btn-ghost");
        updateDlgManualLink.setMinHeight(46);
        updateDlgManualLink.setVisible(false);
        updateDlgManualLink.setManaged(false);
        // 1.2.7：手动下载也必须关框退出；QA fire 时抑制真实浏览器启动。
        updateDlgManualLink.setOnAction(e -> openManualDownload());
        HBox foot = new HBox(12, tail, updateDlgCancelBtn, updateDlgUpdateBtn, updateDlgManualLink);
        foot.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().addAll(updateDlgTitle, updateDlgBody, progressBox, foot);

        // 遮罩：点遮罩 = 取消（与 buildInstallDialog 一致）
        Region backdrop = updateDlgBackdrop = new Region();
        backdrop.getStyleClass().add("dlg-backdrop");
        // 1.2.7：无条件先关框（失败弹窗路径下这里以前是空操作，点遮罩纹丝不动）。
        backdrop.setOnMouseClicked(e -> cancelOrCloseUpdateDialog());
        VBox center = new VBox(card);
        center.setAlignment(Pos.CENTER);
        center.setFillWidth(false);
        StackPane layer = new StackPane(backdrop, center);
        layer.getStyleClass().add("dlg-layer");
        layer.setVisible(false);
        updateDialog = layer;
        return layer;
    }

    /**
     * 0.9.8：状态栏左侧的「发现更新」小胶囊。
     *
     * <p>旧版挂在 shell 顶层、TOP_RIGHT，一条 322×46 的深色浮层正好盖住标题栏右侧
     * 最小化/最大化/关闭三个按钮，还会截获点击（点关闭变成重新弹更新对话框）。
     * 现在它是状态栏的普通子节点：隐藏时连布局空位都不占，不挡窗口按钮、
     * 不压内容区、也不吃底下任何控件的点击。
     */
    private HBox buildUpdateTip() {
        HBox bar = new HBox(6);
        bar.getStyleClass().addAll("update-tip", "update-tip-inline");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        // 隐藏时一并撤掉布局占位，状态栏左侧不会常年空一块
        bar.managedProperty().bind(bar.visibleProperty());

        Region dot = new Region();
        dot.getStyleClass().add("update-tip-dot");
        dot.setPrefSize(6, 6);
        dot.setMinSize(6, 6);
        dot.setMaxSize(6, 6);

        updateTipLabel = new Label();
        updateTipLabel.setMaxWidth(300);

        updateTipClose = winBtn("update-tip-close",
                Icons.strokeScaled(Icons.WIN_CLOSE, 11, 1.6), BTN_HOVER_BG, STROKE_HOVER);
        updateTipClose.setPrefSize(20, 20);
        updateTipClose.setMinSize(20, 20);
        updateTipClose.setMaxSize(20, 20);

        bar.getChildren().addAll(dot, updateTipLabel, updateTipClose);
        bar.setVisible(false);   // 默认隐藏，showUpdateTip 时才露
        updateTip = bar;
        return bar;
    }

    @Override
    public void showUpdateDialog(UpdateInfo info, Runnable onUpdate, Runnable onCancel) {
        this.updateOnUpdate = onUpdate;
        this.updateOnCancel = onCancel;
        String x = info.version() + " · " + info.build();
        updateDlgTitle.setText("发现更新 " + x);
        updateDlgBody.setText("发现新版本，现在更新吗？"
                + "更新会自动下载并重启，游戏存档不会丢。");
        // 重置进度区：取消后再开 / 失败重开都要回到初始态（按钮可点、进度条隐藏归零）
        updateDlgBar.setProgress(0);
        updateDlgBar.setVisible(false);
        updateDlgStage.setText("");
        updateDlgStage.setVisible(false);
        updateDlgStage.getStyleClass().remove("update-error");
        updateDlgStage.setStyle("");
        updateFailedPending = false;
        // 每次重新打开都收起上次失败才出现的手动下载出口，保持初始态干净。
        if (updateDlgManualLink != null) {
            updateDlgManualLink.setVisible(false);
            updateDlgManualLink.setManaged(false);
        }
        // 0.9.9 P2：下载进行中（取消尚未生效）时不要重置成可重复点的「更新」态
        if (!UpdateInstaller.isInstalling()) {
            updateDlgUpdateBtn.setDisable(false);
            updateDlgUpdateBtn.setText("更新");
        }
        updateDlgUpdateBtn.setOnAction(e -> {
            // r2：清掉上次失败提示，确保只显示一次、不污染本次下载
            if (updateFailedPending) {
                updateFailedPending = false;
                updateDlgStage.setText("");
                updateDlgStage.setVisible(false);
                updateDlgStage.getStyleClass().remove("update-error");
                updateDlgStage.setStyle("");
            }
            if (updateOnUpdate != null) {
                updateOnUpdate.run();
            }
        });
        // 1.2.7：取消无条件先关框，不再依赖安装中状态或调用方是否绑定回调。
        updateDlgCancelBtn.setOnAction(e -> cancelOrCloseUpdateDialog());
        cancelActiveRipplesBeforeOpenModal();   // 1.0.6
        showOverlayLayer(updateDialog, "update-dialog");
    }

    @Override
    public void closeUpdateDialog() {
        cancelAllRipples();   // 1.0.5
        if (updateDialog != null) {
            hideOverlayLayer(updateDialog, "update-dialog");
        }
    }

    @Override
    public void showUpdateFailed(String reason) {
        // 0.9.9 r4：优先显示安装器回传的真实失败原因（写不进文件 / 包不完整 / 下载卡死…），
        // 只有原因为空时才回退到下面那句通用文案 UPDATE_FAILED_MSG。布局与样式一概不动。
        // 直接操作已存在的对话框节点，避免改 showUpdateDialog 的复杂状态机。
        // 0.9.9 r6：服务层用这个前缀标记「文件已覆盖但自动重启失败」，不能对用户谎报「文件没改动」。
        final boolean installedNotRestarted = reason != null && reason.contains("[阶段:已安装但未重启]");
        updateFailedPending = true;
        Platform.runLater(() -> {
            // 1.2.7 根因：失败弹窗从未绑定 updateOnCancel，点取消两个分支都空转。
            // 这里把取消回调绑到关框本身，三个出口都能退出（重复点击幂等）。
            ShellView.this.updateOnCancel = ShellView.this::closeUpdateDialog;
            // 1.2.7 兜底：失败弹窗可能不经过 showUpdateDialog 独立触发，这里必须自己绑定取消 handler，否则按钮无 handler 点了没反应。
            if (updateDlgCancelBtn != null) {
                updateDlgCancelBtn.setOnAction(e -> cancelOrCloseUpdateDialog());
            }
            if (updateDialog != null) {
                showOverlayLayer(updateDialog, "update-dialog");
            }
            if (updateDlgTitle != null) {
                updateDlgTitle.setText("这次没更新成功");
            }
            if (updateDlgBody != null) {
                if (installedNotRestarted) {
                    updateDlgBody.setText("新版本已经装好了，只是自动重启没成功。请手动关掉 GBL 再重新打开就行。");
                } else {
                    updateDlgBody.setText("仍是 " + LauncherApp.VERSION + " (" + LauncherApp.BUILD + ")，文件没有被改动。");
                }
            }
            if (updateDlgStage != null) {
                String shown = reason != null && !reason.isBlank() ? reason : UPDATE_FAILED_MSG;
                if (installedNotRestarted) {
                    // 内部阶段标记不原样甩给用户，去掉后再展示剩余原因。
                    shown = shown.replace("[阶段:已安装但未重启]", "").trim();
                    if (shown.isBlank()) {
                        shown = "请手动关闭 GBL 再重新打开，即可完成更新。";
                    }
                }
                updateDlgStage.setText(shown);
                if (!updateDlgStage.getStyleClass().contains("update-error")) {
                    updateDlgStage.getStyleClass().add("update-error");
                }
                // 内联红色，无需改 theme.css 即可保证文案醒目可见
                updateDlgStage.setStyle("-fx-text-fill: #ff6b6b; -fx-font-weight: bold;");
                updateDlgStage.setVisible(true);
            }
            // 下载失败后恢复按钮，文案改「重试」，用户可直接点它重试
            if (updateDlgUpdateBtn != null) {
                updateDlgUpdateBtn.setDisable(false);
                updateDlgUpdateBtn.setText("重试");
            }
            // 0.9.9 r6：任何失败都留一条一键手动下载官网的路。
            if (updateDlgManualLink != null) {
                updateDlgManualLink.setVisible(true);
                updateDlgManualLink.setManaged(true);
            }
        });
    }

    /**
     * 1.2.7：启动自动检查失败的非阻断钩子（{@link UpdateController.UpdateUi#onAutoCheckFailed}）。
     *
     * <p>这里绝不再 showOverlayLayer(updateDialog) —— 那是用户截图里卡住操作的模态框。
     * 只弹一条右下角短提示：沿用 .toast 深灰黑圆角气泡、白字、鼠标穿透、2s 淡出、
     * dR/dB≈18px；技术原因（超时 / TLS handshake / HTTP 状态）不原样堆给用户。
     * 即使后台线程直接调进来，也先切回 FX 线程再动 ObservableList。
     */
    @Override
    public void onAutoCheckFailed(String reason) {
        Runnable show = () -> toast("离线模式：暂时连不上更新服务器，已跳过检查");
        if (javafx.application.Platform.isFxApplicationThread()) {
            show.run();
        } else {
            javafx.application.Platform.runLater(show);
        }
    }

    /**
     * 1.2.7：更新弹框「取消按钮 / 点遮罩」的统一出口。
     *
     * <p>旧版先 requestCancelUpdate() 再跑 updateOnCancel：失败弹窗路径下安装早已结束，
     * 而 updateOnCancel 又从未在 showUpdateFailed 里绑定，两个分支都空转，点取消纹丝不动。
     * 现在无条件先关框，再尝试中断下载，再跑业务回调；重复点击幂等、不抛异常。
     */
    private void cancelOrCloseUpdateDialog() {
        closeUpdateDialog();
        requestCancelUpdate();
        if (updateOnCancel != null) {
            updateOnCancel.run();
        }
    }

    /** 1.2.7：手动下载出口先关框再开浏览器；QA fire 时只验关框不真的启动浏览器。 */
    private static boolean suppressOpenBrowserForTests;

    private void openManualDownload() {
        closeUpdateDialog();
        if (!suppressOpenBrowserForTests) {
            openInBrowser(WEBSITE_URL + "download.html");
        }
    }

    /** 0.9.9 P2：点「取消」时真正打断下载；仅在确有下载在进行时才请求，避免误清后续安装的取消标志。 */
    private void requestCancelUpdate() {
        if (UpdateInstaller.isInstalling()) {
            UpdateInstaller.requestCancel();
            toast("正在取消更新…");
        }
    }

    @Override
    public void showUpdateTip(UpdateInfo info, Runnable onTipClick, Runnable onTipClose) {
        String x = info.version() + " · " + info.build();
        updateTipLabel.setText("发现更新 " + x + " · 点击更新");
        // 点主体（非 ×）→ onTipClick
        updateTip.setOnMouseClicked(e -> {
            if (updateOnTipClickPending != null) {
                updateOnTipClickPending.run();
            }
        });
        // 点 × → onTipClose，并吞掉事件避免冒泡到主体又触发 onTipClick
        updateTipClose.setOnMouseClicked(e -> {
            e.consume();
            if (updateOnTipClosePending != null) {
                updateOnTipClosePending.run();
            }
        });
        this.updateOnTipClickPending = onTipClick;
        this.updateOnTipClosePending = onTipClose;
        cancelActiveRipplesBeforeOpenModal();   // 1.0.6
        updateTip.setVisible(true);
    }

    @Override
    public void closeUpdateTip() {
        cancelAllRipples();   // 1.0.5
        if (updateTip != null) {
            updateTip.setVisible(false);
        }
    }

    @Override
    public void updateProgress(double fraction, String stage) {
        // 阶段文案：stage 非空才更新并露出来（fraction == -1 时只更新文案、不动进度条）
        if (updateDlgStage != null && stage != null && !stage.isBlank()) {
            updateDlgStage.setText(stage);
            updateDlgStage.setVisible(true);
        }
        // 进度条：fraction >= 0（0..1）才推进；并把「更新」按钮禁用 + 改文案「更新中…」
        if (fraction >= 0 && updateDlgBar != null) {
            updateDlgBar.setVisible(true);
            updateDlgBar.setProgress(fraction);
            if (updateDlgUpdateBtn != null) {
                updateDlgUpdateBtn.setDisable(true);
                updateDlgUpdateBtn.setText("更新中…");
            }
        }
    }

    // 提示条的点击 / 关闭回调（show 时暂存，bar / × 的 handler 复用）
    private Runnable updateOnTipClickPending;
    private Runnable updateOnTipClosePending;

    /** 单选加载器：点已选中的那个会取消；选中时懒加载版本列表。 */
    private void selectInstallLoader(LoaderType type) {
        installLoader = (installLoader == type) ? null : type;
        for (LoaderType lt : INSTALL_LOADERS) {
            Pane card = installLoaderCards.get(lt);
            if (card != null) {
                card.getStyleClass().remove("selected");
                if (lt == installLoader) {
                    card.getStyleClass().add("selected");
                }
            }
            ComboBox<String> box = installLoaderBoxes.get(lt);
            if (box != null) {
                boolean show = lt == installLoader;
                box.setVisible(show);
                box.setManaged(show);   // 1.2.5：visible/managed 成对，选中卡才撑高度
            }
        }
        Label fa = installCompSub.get("fabric-api");
        if (fa != null) {
            fa.setText(installLoader == LoaderType.FABRIC ? "将随 Fabric 安装" : "随 Fabric 自动安装");
        }
        Label qs = installCompSub.get("qsl");
        if (qs != null) {
            qs.setText(installLoader == LoaderType.QUILT ? "将随 Quilt 安装" : "随 Quilt 自动安装");
        }
        if (installLoader != null) {
            ComboBox<String> box = installLoaderBoxes.get(installLoader);
            if (box != null && box.getItems().isEmpty()) {
                loadInstallLoaderVersions(installLoader, box);
            }
        }
        // 中性预提示（H10）：随加载器选择变化，不含版本阈值；OptiFine 与 Fabric/Forge 系互斥/不自动组合
        if (installLoaderHint != null) {
            String hint = null;
            if (installLoader != null) {
                switch (installLoader) {
                    case OPTIFINE -> hint = "OptiFine 是独立加载器，与 Fabric/Quilt 不兼容，与 Forge 系也不自动组合。"
                            + "想要光影/模组兼容，Fabric 系建议改用 Iris + Sodium。";
                    case FABRIC, QUILT -> hint = "Fabric/Quilt 与 OptiFine 不兼容；想要光影效果建议装 Iris + Sodium。";
                    case FORGE, NEOFORGE -> hint = "Forge/NeoForge 与 OptiFine 不会自动组合，需手动另行配置。";
                    default -> hint = null;
                }
            }
            installLoaderHint.setVisible(hint != null);
            installLoaderHint.setManaged(hint != null);   // 1.2.6：成对切换，隐藏不占高度
            if (hint != null) {
                installLoaderHint.setText(hint);
            }
        }
    }

    /**
     * 弹窗里拉加载器版本列表。0.8.8：game 仍用「将新建的 MC 版本」{@code installMcVersion}
     * （非 {@link #effectiveGameId()}），服务层仍只调 {@code LoaderInstaller.versions(type, game)}；
     * 三态文案（LOADING / EMPTY / FAILED）与加载器 tab 口径一致。
     */
    private void loadInstallLoaderVersions(LoaderType type, ComboBox<String> box) {
        String game = installMcVersion;
        if (game == null) {
            return;
        }
        box.getItems().setAll("正在拉取版本…");
        box.setValue("正在拉取版本…");
        box.setDisable(true);
        Thread fetch = new Thread(() -> {
            try {
                List<LoaderInstaller.LoaderVersion> list = LoaderInstaller.versions(type, game);
                javafx.application.Platform.runLater(() -> {
                    if (list == null || list.isEmpty()) {
                        // EMPTY ≠ FAILED：与加载器 tab 同口径
                        box.getItems().setAll("暂无兼容版本");
                        box.setValue("暂无兼容版本");
                        box.setDisable(true);
                        return;
                    }
                    box.getItems().setAll(
                            list.stream().map(LoaderInstaller.LoaderVersion::version).toList());
                    int at = 0;
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).stable()) {
                            at = i;
                            break;
                        }
                    }
                    box.getSelectionModel().select(at);
                    box.setDisable(false);
                });
            } catch (IOException ex) {
                javafx.application.Platform.runLater(() -> {
                    System.err.println("[install] 加载器版本拉取失败: " + ex.getMessage());
                    if (type == LoaderType.OPTIFINE) {
                        // D6 降级：列表拉不到，清空后让用户手填版本串（box 已 setEditable）
                        box.getItems().clear();
                        box.setValue(null);
                        box.setPromptText("版本列表加载失败，可以手动填写版本号，或检查网络、换个下载源后重试");
                        box.setDisable(false);
                    } else {
                        box.getItems().setAll("版本列表加载失败");
                        box.setValue("版本列表加载失败");
                        box.setDisable(true);
                    }
                });
            }
        }, "gbl-install");
        fetch.setDaemon(true);
        fetch.start();
    }

    /** 点「安装」：校验 → 排 MC 下载（已装则跳过）→ 链式装加载器 + API。 */
    private void runInstall() {
        String id = installMcVersion;
        if (id == null) {
            toast("没有要安装的版本");
            return;
        }
        String name = installNameField.getText().trim();
        if (name.isEmpty()) {
            installStatus.setText("先给实例起个名字");
            return;
        }
        LoaderType loader = installLoader;
        String loaderVer = null;
        if (loader != null) {
            ComboBox<String> box = installLoaderBoxes.get(loader);
            loaderVer = box == null ? null : box.getValue();
            if (loaderVer == null || loaderVer.isBlank() || loaderVer.startsWith("加载中")
                    || loaderVer.startsWith("版本列表")) {
                installStatus.setText("先选一个 " + loader.label() + " 版本");
                return;
            }
        }
        closeInstallDialog();
        if (GameDownloader.installed(id)) {
            installChainAfterMc(id, loader, loaderVer);
        } else {
            toast(id + " 开始下载（进度见下载队列）");
            GameDownloader.DEFAULT.submit(id, installVersion.kind(), installVersion.shown(),
                    installVersion.url());
            pollMcDownload(id, loader, loaderVer);
        }
    }

    /** 轮询 MC 下载直到落盘，再串加载器安装。 */
    private void pollMcDownload(String id, LoaderType loader, String loaderVer) {
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < 900; i++) {   // ~6 分钟
                    GameDownloader.Item it = GameDownloader.DEFAULT.item(id);
                    GameDownloader.State st = it == null ? null : it.state();
                    if (st == GameDownloader.State.DONE) {
                        break;
                    }
                    if (st == GameDownloader.State.FAILED) {
                        javafx.application.Platform.runLater(() -> toast(id + " 下载失败，没装成"));
                        return;
                    }
                    Thread.sleep(400);
                }
                if (!GameDownloader.installed(id)) {
                    javafx.application.Platform.runLater(() -> toast(id + " 下载似乎没完成，稍后重试"));
                    return;
                }
                installChainAfterMc(id, loader, loaderVer);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, "gbl-install");
        t.setDaemon(true);
        t.start();
    }

    /** MC 落盘后：装加载器（若有），再装 Fabric API / QSL（若选了对应加载器）。 */
    private void installChainAfterMc(String id, LoaderType loader, String loaderVer) {
        if (loader == null) {
            javafx.application.Platform.runLater(() -> {
                toast(id + " 安装完成（原版）");
                finishInstall(id);
            });
            return;
        }
        Thread t = new Thread(() -> {
            try {
                final String lv = loaderVer;
                String derived = LoaderInstaller.install(id, loader, lv, new LoaderInstaller.Listener() {
                    @Override
                    public void onStage(String text) {
                        javafx.application.Platform.runLater(() -> toast(text));
                    }
                });
                installApiIfNeeded(id, loader);
                // 安装器型（Forge/NeoForge）装完多出一个独立派生 profile，当前游戏要切过去
                final String finalId = (derived != null) ? derived : id;
                javafx.application.Platform.runLater(() -> {
                    toast(id + " + " + loader.label() + " " + lv + " 安装完成");
                    finishInstall(finalId);
                });
            } catch (IOException ex) {
                javafx.application.Platform.runLater(() -> toast(
                        loader.label() + " 没装上：" + ex.getMessage()));
            }
        }, "gbl-install");
        t.setDaemon(true);
        t.start();
    }

    /** 装对应加载器的 API（Fabric -> fabric-api，Quilt -> qsl）；安装器型（Forge/NeoForge）无独立 API，跳过。
     * 失败不阻断游戏安装。
     *
     * <p>0.8.8 Q3 契约说明：这里的 {@code id} 是「安装新游戏」链里<b>将新建的 MC 版本</b>
     * （同 §3.2 第 22 项 {@code installMcVersion} 语义），既是 {@code versions(slug, id, loader)}
     * 的 MC 版本过滤条件，也是 {@code ModIndex} 的索引标签；此刻新游戏还没被
     * {@code setCurrentGame()} 上位，若改读 {@link #effectiveGameId()} 会把 API 索引打到
     * 旧游戏/工具条游戏、与刚装进 {@code id} 的加载器错配，破坏 0.8.7 已验收的安装链。
     * 故此处保留 {@code id}，不并入 effectiveGameId() 收口（已在交付报告登记为契约偏差）。 */
    private void installApiIfNeeded(String id, LoaderType loader) {
        if (loader == null || loader.isInstaller()) {
            return;
        }
        String slug = (loader == LoaderType.FABRIC) ? "fabric-api" : "qsl";
        try {
            List<ModrinthClient.File> files = ModrinthClient.versions(slug, id, loader);
            if (files.isEmpty()) {
                System.err.println("[install] 「" + slug + "」没有兼容版本，跳过");
                return;
            }
            ModrinthClient.install(files.get(0), id, new AtomicBoolean(false), null);
            System.out.println("[install] 已装 " + slug);
        } catch (IOException ex) {
            System.err.println("[install] 「" + slug + "」没装成（不影响启动器）：" + ex.getMessage());
        }
    }

    /** 装完收尾：设当前游戏 + 刷新各相关视图。 */
    private void finishInstall(String id) {
        setCurrentGame(id);
        refreshGames();
        refreshVersionList();
        refreshQueueList();
        refreshGameManage();
        refreshModCenter();
    }

    private void closePrompt() {
        cancelAllRipples();   // 1.0.5
        if (gamePrompt != null) {
            hideOverlayLayer(gamePrompt, "game-prompt");
        }
    }

    /**
     * 游戏管理页状态卡：显示当前选中的游戏 + 它的加载器状态。
     *
     * <p>{@code showPage(Page.GAMEMGMT)} 与装完新游戏收尾都会调——保证进页 / 装完即刻同步。
     * 逻辑直接复用 {@link #refreshModCenterStatus()} 那套「未选 / 原版 / 已装」三态，只是盯的是全局当前游戏。
     */
    /** 游戏管理页的两段（已装加载器 / 已装模组）共用的卡片壳：标题 + 描述 + body 容器。 */
    private VBox buildMgmtSection(String titleText, String descText, VBox body) {
        Label title = new Label(titleText);
        title.getStyleClass().add("mgmt-section-title");
        Label desc = new Label(descText);
        desc.getStyleClass().add("page-desc");
        desc.setWrapText(true);
        desc.setMaxWidth(760);
        VBox card = new VBox(10, title, desc, body);
        card.getStyleClass().addAll("card", "mgmt-section");
        card.setPadding(new Insets(14, 16, 14, 16));
        return card;
    }

    /**
     * 游戏管理页状态 + 两段（已装加载器 / 已装模组）。
     *
     * <p>当前游戏状态条 + 已装加载器段 + 已装模组段都在这里同步——进页 / 切当前游戏 /
     * 卸模组 / 卸加载器之后都走这一条，保证三处不会各说各话。
     */
    private void refreshGameManage() {
        if (gameMgmtStatus == null) {
            return;
        }
        restoreLegacyDisabledPacks();   // 1.1.3：后台迁移 1.1.2 遗留的禁用文件，不挡界面
        // 0.8.8 Q3：管理页与「这次装到哪」统一取值口，否则「装进 B、却去 A 的管理页看」自然看不见。
        final String game = effectiveGameId();
        // 0.9.0 R-04：状态卡左侧块图标随当前游戏类型更新（缺图回退 CUBE，不 NPE）
        if (gameMgmtStatusIcon != null) {
            String gk = currentGameEntry == null ? null : currentGameEntry.type().name();
            VersionManifest.Kind vk = gameTypeToVm(gk);
            Node ni = versionBlockIcon(vk, 28);
            if (gameMgmtStatusIcon.getParent() instanceof HBox pr) {
                int i = pr.getChildren().indexOf(gameMgmtStatusIcon);
                if (i >= 0) {
                    pr.getChildren().set(i, ni);
                }
            }
            gameMgmtStatusIcon = ni;
        }
        // 三段 body 每次重填：先清空，后面按当前游戏情况往里塞
        if (gameMgmtLoaderBody != null) {
            gameMgmtLoaderBody.getChildren().clear();
        }
        if (gameMgmtModsBody != null) {
            gameMgmtModsBody.getChildren().clear();
        }
        if (gameMgmtPackBody != null) {
            gameMgmtPackBody.getChildren().clear();
        }
        if (gameMgmtResourcePackBody != null) {
            gameMgmtResourcePackBody.getChildren().clear();
        }
        if (gameMgmtShaderBody != null) {
            gameMgmtShaderBody.getChildren().clear();
        }
        if (gameMgmtWorldBody != null) {
            gameMgmtWorldBody.getChildren().clear();
        }
        // 管理整合包段：列出已导入的整合包（与当前游戏无关，全局可管）
        if (gameMgmtPackBody != null) {
            List<ModpackRegistry.Pack> packs = ModpackRegistry.all();
            if (packs.isEmpty()) {
                gameMgmtPackBody.getChildren().add(
                        modCenterEmpty("还没导入过整合包。去「整合包」页挑一个 .mrpack 导入吧。"));
            } else {
                for (ModpackRegistry.Pack pk : packs) {
                    gameMgmtPackBody.getChildren().add(packRow(pk));
                }
            }
        }
        // 0.8.7：资源包 / 光影 / 世界 三段本地内容（全局可管，与当前游戏无关，故在选游戏判断前填）
        refreshContentMgmtSection(ContentStore.Kind.RESOURCEPACK, gameMgmtResourcePackBody,
                "还没装资源包。去「资源包」页挑一个装上吧。");
        refreshContentMgmtSection(ContentStore.Kind.SHADER, gameMgmtShaderBody,
                "还没装光影。去「光影」页挑一个装上吧（需要 OptiFine 或 Iris 才生效）。");
        refreshContentMgmtSection(ContentStore.Kind.WORLD, gameMgmtWorldBody,
                "还没导入世界。去「世界」页挑一个装上吧。");
        if (game == null) {
            gameMgmtStatus.setText("还没有当前游戏：去「游戏列表」选一个，或去「下载」装一个。");
            if (gameMgmtLoaderBody != null) {
                gameMgmtLoaderBody.getChildren().add(
                        modCenterEmpty("先选一个游戏，才能看它装了什么加载器。"));
            }
            if (gameMgmtModsBody != null) {
                gameMgmtModsBody.getChildren().add(
                        modCenterEmpty("先选一个游戏，才能看它装了哪些模组。"));
            }
            return;
        }
        LoaderInstaller.Status status = null;
        String broken = null;
        try {
            status = LoaderInstaller.status(game);
        } catch (IOException ex) {
            broken = ex.getMessage();
        }
        if (broken != null) {
            gameMgmtStatus.setText(game + " · 加载器状态读不了（" + broken + "）");
        } else if (status == null) {
            gameMgmtStatus.setText(game + " · 原版 · 未安装加载器");
        } else {
            gameMgmtStatus.setText(game + " · " + status.type().label() + " "
                    + status.loaderVersion() + " · 已安装");
        }

        // 已装加载器段：有就给一行（类型 + 版本 + 更换 / 卸载），没有给引导去装
        if (gameMgmtLoaderBody != null) {
            if (broken != null) {
                gameMgmtLoaderBody.getChildren().add(
                        modCenterEmpty("加载器状态读不了（" + broken + "）"));
            } else if (status == null) {
                Button go = new Button("去装加载器");
                go.setGraphic(Icons.create(Icons.DOWNLOAD, 15));
                go.setGraphicTextGap(9);
                go.getStyleClass().add("btn-download");
                go.setMinHeight(36);
                go.setOnAction(e -> {
                    showPage(Page.DOWNLOAD);
                    showDownloadTab(1);
                });
                gameMgmtLoaderBody.getChildren().addAll(
                        new Label(game + " · 原版 · 还没装加载器"), go);
            } else {
                gameMgmtLoaderBody.getChildren().add(loaderRow(status));
            }
        }

        // 已装模组段：扫 mods 目录，复用 modRow（带封面 + 启停 + 卸载）
        if (gameMgmtModsBody != null) {
            List<ModStore.Mod> mods = ModStore.scan();
            // 索引里记的 filename → projectId，封面异步拉图标用
            Map<String, String> pidByFile = new HashMap<>();
            for (ModIndex.Entry e : ModIndex.entries(game)) {
                if (e.projectId() != null) {
                    pidByFile.put(e.filename(), e.projectId());
                }
            }
            if (mods.isEmpty()) {
                gameMgmtModsBody.getChildren().add(
                        modCenterEmpty("这个游戏还没装任何模组。去「模组」页挑一个装上吧。"));
            } else {
                for (ModStore.Mod m : mods) {
                    gameMgmtModsBody.getChildren().add(modRow(m, pidByFile.get(m.fileName())));
                }
            }
        }
    }

    // ---------------------------------------------------------------- 0.8.7 本地内容管理（资源包 / 光影 / 世界）

    /** 把某类本地内容扫出来填进对应管理段；scan 读不坏不抛，目录没有就返回空列表。 */
    private void refreshContentMgmtSection(ContentStore.Kind kind, VBox body, String emptyMsg) {
        if (body == null) {
            return;
        }
        body.getChildren().clear();
        List<ContentStore.Item> items = ContentStore.scan(kind);
        if (items.isEmpty()) {
            body.getChildren().add(modCenterEmpty(emptyMsg));
            return;
        }
        if (kind == ContentStore.Kind.WORLD) {
            // 世界版本要读 gzip 压缩的 level.dat（NBT），属重 IO：放后台线程批量解析，
            // 回 FX 线程再构造行。scan 阶段不碰 NBT，FX 线程也不做重 IO。
            body.getChildren().add(modCenterEmpty("正在读取世界版本…"));
            final int token = ++worldVersionSeq;
            Thread t = new Thread(() -> {
                Map<String, String> versions = new HashMap<>();
                for (ContentStore.Item it : items) {
                    versions.put(it.fileName(), ContentStore.worldVersion(it.path()));
                }
                Platform.runLater(() -> {
                    if (token != worldVersionSeq) {
                        return;   // 已被更新的刷新取代，别把旧结果回填到新段里
                    }
                    body.getChildren().clear();
                    for (ContentStore.Item it : items) {
                        body.getChildren().add(
                                contentMgmtRow(kind, it, versions.get(it.fileName())));
                    }
                });
            }, "gbl-world-version");
            t.setDaemon(true);
            t.start();
            return;
        }
        for (ContentStore.Item it : items) {
            body.getChildren().add(contentMgmtRow(kind, it, it.version()));
        }
    }

    /**
     * 一行本地内容（资源包 / 光影 / 世界）：0.9.2 起与 {@link #modRow} 完全同构 ——
     * R1 头部（40×40 封面 / 名称 + 类别与版本胶囊 / 启停开关 / 卸载）+ R2 分隔线 +
     * R3 统计行（文件 / 大小 / 位置 / 启用态 / 打开目录）。
     *
     * <p>世界存档没有启停语义：{@code ContentStore.setEnabled} 对 saves 目录会抛
     * {@link UnsupportedOperationException}（RuntimeException，catch IOException 抓不到）。
     * 0.9.4 r2：说明文字已写明「无需启停」，WORLD 分支不再创建 ToggleSwitch —— 不再放
     * 一个永远禁用、点了没反应的绿色开关；非 WORLD 分支才创建开关并挂启停监听。
     * 「打开目录」从头部降级到统计行末尾，头部与模组行严格一致，功能不丢。
     */
    private Node contentMgmtRow(ContentStore.Kind kind, ContentStore.Item it, String version) {
        VBox row = new VBox(0);
        row.getStyleClass().addAll("card", "mod-row");
        row.setPadding(new Insets(10, 14, 10, 14));

        // R1 头部：40×40 封面 | 名称 + 类别/版本胶囊 | 启停开关 | 卸载
        HBox head = new HBox(12);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setMinHeight(40);
        ImageView cover = new ImageView();
        cover.getStyleClass().add("mod-cover");
        cover.setFitWidth(40);
        cover.setFitHeight(40);
        cover.setPreserveRatio(true);
        cover.setSmooth(true);
        Rectangle coverClip = new Rectangle(40, 40);
        coverClip.setArcWidth(10);
        coverClip.setArcHeight(10);
        cover.setClip(coverClip);
        // r4：封面统一走 loadContentCoverAsync——占位图先上，本地 zip / icon.png 的同步 IO
        // 与远程回退都挪到后台线程，异常一律兜底回落占位图，绝不堵 FX 线程、不污染整批封面。
        cover.setImage(coverPlaceholder(40));
        loadContentCoverAsync(cover, it, kind);

        Label name = new Label(it.displayName() != null && !it.displayName().isBlank()
                ? it.displayName() : it.fileName());
        name.getStyleClass().add("card-title");
        String kindLabel = kind == ContentStore.Kind.WORLD ? "世界存档"
                : (kind == ContentStore.Kind.SHADER ? "光影包" : "材质包");
        VBox info = new VBox(3);
        HBox chips = new HBox(6,
                tagChip(kindLabel, "tag-loader-sm"),
                versionTag(AdaptedMc.resolve(stripDisabled(it.fileName()), null, null, version)));
        chips.setAlignment(Pos.CENTER_LEFT);
        info.getChildren().addAll(name, chips);

        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Button remove = new Button("卸载");
        remove.getStyleClass().add("btn-danger");
        remove.setMinHeight(32);
        remove.setOnAction(e -> {
            DetachedRow detached = detachRow(row);
            runDiskTask(
                    () -> ContentStore.uninstall(it.path()),
                    "卸载 " + it.fileName(),
                    this::scheduleGameManageRefresh,
                    () -> {
                        restoreDetachedRow(detached);
                        toast("卸载失败：文件可能被游戏占用，关掉游戏再试一次");
                    });
        });
        // 0.9.8：卸载进回收站要等系统响应，别让整窗跟着一起定住。
        // 0.9.4 r2：世界不支持启停（ContentStore.Kind.WORLD 的 setEnabled 会抛
        // UnsupportedOperationException），说明文字已写明，不再放一个永远禁用、点了没反应的绿色开关。
        if (kind == ContentStore.Kind.WORLD) {
            head.getChildren().addAll(cover, info, gap, remove);
        } else {
            ToggleSwitch enable = new ToggleSwitch(it.enabled());
            final boolean[] rollingBack = {false};
            enable.selectedProperty().addListener((obs, was, now) -> {
                if (rollingBack[0]) {
                    return;
                }
                try {
                    ContentStore.setEnabled(it.path(), now);
                } catch (IOException ex) {
                    toast("启停失败：" + ex.getMessage());
                    Platform.runLater(() -> rollbackToggle(enable, !now, rollingBack));
                }
                // 1.2.8：260ms 防抖 = 滑动播完再重建，不再把 200ms 动画截成 80ms
                scheduleGameManageRefresh();
            });
            head.getChildren().addAll(cover, info, gap, enable, remove);
        }

        // R3 统计行：与 modRow 同款；「打开目录」降级到这里，字号跟随统计行
        HBox stats = new HBox(10);
        stats.setAlignment(Pos.CENTER_LEFT);
        stats.getStyleClass().add("mod-card-stats");
        Hyperlink open = new Hyperlink("打开目录");
        open.getStyleClass().add("mod-stat");
        open.setOnAction(e -> com.mclauncher.Platform.reveal(ContentStore.dir(kind)));
        stats.getChildren().addAll(
                statLabel("文件 " + text(it.fileName())),
                statLabel("大小 " + LibFetcher.human(it.sizeBytes())),
                statLabel("位置 " + ContentStore.dir(kind).getFileName() + "/"),
                kind == ContentStore.Kind.WORLD
                        ? tagChip("无需启停", "tag-other")
                        : tagChip(it.enabled() ? "已启用" : "已禁用",
                                it.enabled() ? "tag-release" : "tag-other"),
                open);
        row.getChildren().addAll(head, sepLine(), stats);
        return row;
    }

    /**
     * 资源包 / 光影的本地封面：zip 或目录里的 {@code pack.png}，取前 2 MiB 字节解码。
     *
     * <p>zip 先认根目录的 {@code pack.png}，找不到就取第一个文件名以 {@code pack.png}
     * 结尾的条目（覆盖放在子目录里的那种包）；目录形式直接找目录下的 {@code pack.png}。
     * 0.9.2 r2：光影包 zip 里通常没有 pack.png（只有 shaders/*.fsh/.vsh/.properties），
     * 上面都找不到时再扫一遍 zip / 目录下任意 png/jpg（≤2 MiB），优先 preview/logo/icon/cover/pack，
     * 免得管理项永远是灰方块。r4：任何找不到 / 打不开 / 解码失败一律返回 {@code null}，
     * 由调用方 {@link #loadContentCoverAsync} 统一回落占位图（引用比较分不清「新占位图」
     * 与「真封面」，故用 null 作唯一失败哨兵）。同步 zip IO 已由调用方移出 FX 线程。
     * 1.1.3：shaderSafe=true 时光影改走受限扫描——只认根/浅层 preview/logo/icon/cover/pack
     * 命名、排除 shaders/ 子目录，兼顾「整合包前置光影有封面」与「0.9.3 r2 绿块不回归」。
     */
    /** 0.9.3 r2 构建指纹：光影包不再盲扫 zip 内部纹理当封面，回落 Modrinth 官方 icon。 */
    private static final String COVER_SHADER_FIX = "0.9.3 r2 光影包跳过本地纹理";
    /** 1.1.3 构建指纹：光影包改为受限封面扫描；历史 0.9.3 r2 指纹上面的 COVER_SHADER_FIX 保持原值不动。 */
    private static final String COVER_SHADER_FIX_113 = "1.1.3 光影包受限封面扫描";
    static {
        // static print 保证两个指纹常量都真正编译进 class 常量池（不被内联优化掉）
        System.err.println("[cover] " + COVER_SHADER_FIX);
        System.err.println("[cover] " + COVER_SHADER_FIX_113);
    }

    private static Image localPackCover(Path path, int px, boolean allowScan, boolean shaderSafe) {
        final int maxBytes = 2 * 1024 * 1024;
        if (path == null) {
            return null;   // r4：失败哨兵，调用方回落占位图
        }
        try {
            byte[] bytes = null;
            if (Files.isDirectory(path)) {
                Path png = path.resolve("pack.png");
                if (Files.isRegularFile(png) && Files.size(png) <= maxBytes) {
                    bytes = Files.readAllBytes(png);
                } else if (allowScan) {
                    try (var stream = Files.list(path)) {
                        Path img = stream.filter(Files::isRegularFile)
                                .filter(p -> shaderSafe
                                        ? isShaderCoverImage(p.getFileName().toString())
                                        : isCoverImageName(p.getFileName().toString()))
                                .filter(p -> {
                                    try {
                                        return Files.size(p) <= maxBytes;
                                    } catch (IOException ignored) {
                                        return false;
                                    }
                                })
                                .findFirst().orElse(null);
                        if (img != null) {
                            bytes = Files.readAllBytes(img);
                        }
                    }
                }
            } else if (Files.isRegularFile(path)) {
                try (ZipFile zip = new ZipFile(path.toFile())) {
                    ZipEntry entry = zip.getEntry("pack.png");
                    if (entry == null) {
                        var it = zip.entries();
                        while (it.hasMoreElements()) {
                            ZipEntry candidate = it.nextElement();
                            String n = candidate.getName();
                            if (n != null && !candidate.isDirectory()
                                    && n.replace('\\', '/').endsWith("pack.png")) {
                                entry = candidate;
                                break;
                            }
                        }
                    }
                    // 1.1.3：pack.png 找不到时扫 zip 内「像封面的」图片兜底。光影包(zip 通常无 pack.png)
                    // 改走受限扫描 shaderSafe：只认 preview/logo/icon/cover/pack 命名且排除 shaders/ 目录，
                    // 绝不收未命名纹理，避免 0.9.3 r2 的噪声/法线图当封面绿块回归；0.9.3 r2 的 allowScan=false
                    // 全关会让整合包前置光影永远灰方块，故改为「受限扫描 + 远端 icon」双保险。
                    if (entry == null && allowScan) {
                        ZipEntry preferred = null, fallbackImg = null;
                        var it2 = zip.entries();
                        while (it2.hasMoreElements()) {
                            ZipEntry candidate = it2.nextElement();
                            String n = candidate.getName();
                            if (n == null || candidate.isDirectory()
                                    || !(shaderSafe ? isShaderCoverImage(n) : isCoverImageName(n))) {
                                continue;
                            }
                            long sz = candidate.getSize();
                            // 受限模式下名字已限定为封面，放宽 2048 下限（真实封面可能很小）；
                            // 普通模式保留原过滤，继续挡掉微型纹理。
                            if (sz > maxBytes || (!shaderSafe && sz > 0 && sz < 2048)) {
                                continue;
                            }
                            String lower = n.toLowerCase(Locale.ROOT);
                            boolean hinted = lower.contains("preview") || lower.contains("logo")
                                    || lower.contains("icon") || lower.contains("cover")
                                    || lower.contains("pack");
                            if (hinted && preferred == null) {
                                preferred = candidate;
                            } else if (!hinted && fallbackImg == null && !shaderSafe) {
                                fallbackImg = candidate;
                            }
                        }
                        entry = preferred != null ? preferred : fallbackImg;
                    }
                    if (entry != null && entry.getSize() <= maxBytes) {
                        try (var in = zip.getInputStream(entry)) {
                            bytes = in.readNBytes(maxBytes);   // getSize() 为 -1 时靠 readNBytes 限流
                        }
                    }
                }
            }
            if (bytes != null && bytes.length > 0) {
                Image img = new Image(new ByteArrayInputStream(bytes), px, px, true, true);
                if (!img.isError()) {
                    return img;
                }
            }
        } catch (Exception ex) {
            // 不是 zip / 条目读坏 / 解码失败：统一返回 null，管理功能不受影响
            System.err.println("[content] 读本地封面失败: " + ex);
        }
        return null;   // r4：失败哨兵，调用方回落占位图
    }

    /** 内容封面 URL 落盘文件（安装时记、管理页回退读）。 */
    private static final Path CONTENT_COVERS_FILE =
            Paths.get(System.getProperty("user.home"), ".gbl", "content-covers.properties");

    /** 封面图扩展名判定（忽略大小写）：zip 条目 / 目录文件扫描共用。 */
    private static boolean isCoverImageName(String n) {
        if (n == null) {
            return false;
        }
        String s = n.toLowerCase(Locale.ROOT);
        return s.endsWith(".png") || s.endsWith(".jpg") || s.endsWith(".jpeg");
    }

    /**
     * 1.1.3：光影包受限封面判定——只认根/浅层的 preview / logo / icon / cover / pack 命名图片，
     * 明确排除 shaders/ 子目录与任意未命名纹理。这样整合包前置光影自带的 preview.png 能显示，
     * 而 shaders/noise.png、法线图这类内部纹理不会被当成封面（0.9.3 r2 绿块 bug 不回归）。
     */
    private static boolean isShaderCoverImage(String name) {
        if (!isCoverImageName(name)) {
            return false;
        }
        String s = name.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (s.contains("shaders/") || s.contains("shader/")) {
            return false;
        }
        String[] parts = s.split("/");
        if (parts.length > 2) {   // 仅根目录与一级子目录，深层资源一律不认
            return false;
        }
        String base = parts[parts.length - 1];
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        return stem.equals("preview") || stem.equals("logo") || stem.equals("icon")
                || stem.equals("cover") || stem.equals("pack");
    }

    /** 0.9.4 r2：管理页传来的禁用文件名带 .zip.disabled，落盘元数据用的是安装时的原名，
     *  两者必须归一到同一个 key；大小写不敏感、null 安全。
     *  1.1.3：1.1.2 整合包启停改成 .gbl-disabled（xxx.zip.gbl-disabled），旧版只 strip .disabled，
     *  导致 query 清洗、落盘 URL key、版本解析三处全部失配；这里一并归一。 */
    private static String stripDisabled(String f) {
        if (f == null) return null;
        String s = f.trim();
        s = s.replaceFirst("(?i)\\.gbl-disabled$", "");
        return s.replaceFirst("(?i)\\.disabled$", "");
    }

    /** 安装成功后把封面 URL 记到磁盘，供管理页封面回退；任何失败都吞掉，绝不影响安装结果。 */
    private static void rememberContentCover(String fileName, String url) {
        if (fileName == null || url == null || url.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(CONTENT_COVERS_FILE.getParent());
            Properties props = new Properties();
            if (Files.isRegularFile(CONTENT_COVERS_FILE)) {
                try (var in = Files.newInputStream(CONTENT_COVERS_FILE)) {
                    props.load(in);
                }
            }
            props.setProperty(stripDisabled(fileName), url);
            try (var out = Files.newOutputStream(CONTENT_COVERS_FILE)) {
                props.store(out, "GBL installed content cover urls");
            }
        } catch (Exception ignored) {
            // 封面落盘失败绝不影响安装结果
        }
    }

    /** 读安装时落盘的封面 URL；文件不存在 / 读坏 / 没这个 key 都返回 null。 */
    private static String contentCoverUrl(String fileName) {
        if (fileName == null) {
            return null;
        }
        try {
            if (!Files.isRegularFile(CONTENT_COVERS_FILE)) {
                return null;
            }
            Properties props = new Properties();
            try (var in = Files.newInputStream(CONTENT_COVERS_FILE)) {
                props.load(in);
            }
            String url = props.getProperty(fileName);
            if (url == null) {
                url = props.getProperty(stripDisabled(fileName));
            }
            return url;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * r4：管理页内容封面的统一异步入口。占位图先上，后台线程依次尝试
     * ①本地封面（WORLD 的 icon.png / 资源包·光影的 pack.png zip IO）、②安装时落盘的封面 URL、
     * ③按名搜远程项目图标。全程带异常兜底，任何一步失败都保持占位图，不会污染整批封面；
     * 本地 zip / 图片同步 IO 一律放在后台线程，避免把 FX 线程按死。
     */
    private void loadContentCoverAsync(ImageView view, ContentStore.Item it, ContentStore.Kind kind) {
        final Image placeholder = coverPlaceholder(40);
        try {
            view.setImage(placeholder);   // 先占坑，离线 / 读图失败也绝不会是空框
            final Path path = it.path();
            final String fileName = it.fileName();
            String q = it.displayName() != null && !it.displayName().isBlank()
                    ? it.displayName() : fileName;
            if (q != null) {
                // 0.9.4 r2：先去掉 .disabled 归一到启用项的 query，再做原有的
                // 扩展名与末尾版本后缀清洗，避免「Sodium 0.9.3-beta」这类名字搜不中项目。
                q = stripDisabled(q)
                     .replaceFirst("(?i)\\.(zip|jar|png|jpe?g|disabled)$", "")
                     .replaceFirst("(?i)[\\s_-]+v?\\d+(?:\\.\\d+){0,3}(?:[-+][\\w.]+)?\\s*$", "")
                     .trim();
            }
            final String query = q;
            // r4：Modrinth 没有 world 这个 project_type，传 "world" 服务层不认（400 / 搜不中）。
            // 用合法值 "mod" 兜底，世界封面主要靠本地 icon.png 与落盘 URL。
            final String projectType = switch (kind) {
                case SHADER -> "shader";
                case RESOURCEPACK -> "resourcepack";
                case WORLD -> "mod";
                default -> "mod";
            };
            Thread t = new Thread(() -> {
                Image local = null;
                String url = null;
                try {
                    // ① 本地封面：同步 zip / 图片 IO 都在本后台线程，不碰 FX 线程
                    if (kind == ContentStore.Kind.WORLD) {
                        Path iconFile = path == null ? null : path.resolve("icon.png");
                        if (iconFile != null && Files.isRegularFile(iconFile)) {
                            Image world = new Image(iconFile.toUri().toString());
                            if (!world.isError()) {
                                local = world;
                            }
                        }
                    } else {
                        // 1.1.3：光影也走受限封面扫描（只认 preview/logo/icon/cover/pack 命名、
                        // 排除 shaders/），取代 0.9.3 r2 的 allowScan=false 全关——
                        // 否则整合包前置光影有 preview.png 也永远灰方块。
                        Image img = localPackCover(path, 40, true, kind == ContentStore.Kind.SHADER);
                        if (img != null) {   // r4：null = 没有本地封面，继续走远端回退
                            local = img;
                        }
                    }
                    if (local == null) {
                        // ② 安装时落盘的封面 URL
                        url = contentCoverUrl(fileName);
                        // ③ 仍没有才按名搜远程项目图标
                        if ((url == null || url.isBlank()) && query != null && !query.isBlank()) {
                            List<ModrinthClient.Hit> hits = ModrinthClient.search(
                                    query, null, null, 1, "下载量", java.util.List.of(), projectType);
                            if (!hits.isEmpty()) {
                                url = hits.get(0).iconUrl();
                                if (url != null && !url.isBlank()) {
                                    rememberContentCover(fileName, url);
                                }
                            }
                        }
                    }
                } catch (Exception ex) {
                    // 目录不存在 / 无写权限 / 中文路径 / 解码失败：统一保持占位图
                    System.err.println("[content] 封面回退异常，保持占位图: " + ex);
                }
                final Image localFinal = local;
                final String urlFinal = url;
                Platform.runLater(() -> {
                    if (localFinal != null) {
                        view.setImage(localFinal);
                    } else if (urlFinal != null && !urlFinal.isBlank()) {
                        loadCover(view, urlFinal, 40);   // loadCover 自身异步
                    }
                    // 两者都没有：保持最初那张占位图
                });
            }, "gbl-content-cover");
            t.setDaemon(true);
            t.start();
        } catch (Exception ex) {
            // r4：入口层再兜一道，绝不让异常抛出去污染 CompletableFuture 链 / 整批封面
            System.err.println("[content] 封面入口异常，保持占位图: " + ex);
            Platform.runLater(() -> view.setImage(placeholder));
        }
    }

    /** 字节数 → 人类可读（B / KB / MB / GB）。 */
    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        int u = (int) (Math.log(bytes) / Math.log(1024));
        String[] unit = {"KB", "MB", "GB", "TB"};
        double v = bytes / Math.pow(1024, u);
        return String.format("%.1f %s", v, unit[u - 1]);
    }

    /** 已装加载器的一行：类型 + 版本 + 更换（跳下载页加载器 tab）/ 卸载。 */
    private Node loaderRow(LoaderInstaller.Status status) {
        HBox row = new HBox(12);
        row.getStyleClass().addAll("card", "mod-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 14, 10, 14));

        Label name = new Label(status.type().label());
        name.getStyleClass().add("card-title");
        Label meta = new Label("加载器版本 " + status.loaderVersion());
        meta.getStyleClass().add("page-desc");
        VBox text = new VBox(2, name, meta);

        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);

        Button change = new Button("更换");
        change.getStyleClass().add("btn-ghost");
        change.setMinHeight(32);
        change.setOnAction(e -> {
            showPage(Page.DOWNLOAD);
            showDownloadTab(1);
        });
        Button remove = new Button("卸载");
        remove.getStyleClass().add("btn-danger");
        remove.setMinHeight(32);
        remove.setOnAction(e -> runLoaderUninstall(effectiveGameId(), status.type()));
        Node icon = loaderIconNode(status.type(), status.type().name(), 28);
        row.getChildren().addAll(icon, text, gap, change, remove);
        return row;
    }

    /**
     * 一行已装整合包：0.9.2 起与 {@link #modRow} 同构 ——
     * R1 头部（40×40 封面 / 名称 + 加载器与版本胶囊 / 已导入 / 卸载）+ R2 分隔线 +
     * R3 统计行（文件数 / MC 版本 / 加载器 / 已导入 / 打开文件夹）。
     *
     * <p>1.1.3 按 HMCL 的结论移除整合包级整体开关：前置模组是 mods/ 里逐条独立的 jar，
     * 启停与删除请到各自的模组 / 光影页单独操作，这里只留一个静态「已导入」标记。
     * 「打开文件夹」按用户要求从头部降级到统计行末尾。
     */
    private Node packRow(ModpackRegistry.Pack pk) {
        VBox row = new VBox(0);
        row.getStyleClass().addAll("card", "mod-row");
        row.setPadding(new Insets(10, 14, 10, 14));

        // R1 头部
        HBox head = new HBox(12);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setMinHeight(40);
        // 0.8.6 P0：先上纯代码占位图保证图片区一定可见，再按包名去 Modrinth 尽力搜图标，
        // 搜不到就留占位图。0.9.2 补 mod-cover 样式类，与模组行封面完全一致。
        ImageView cover = new ImageView();
        cover.getStyleClass().add("mod-cover");
        cover.setFitWidth(40);
        cover.setFitHeight(40);
        cover.setPreserveRatio(true);
        cover.setSmooth(true);
        Rectangle coverClip = new Rectangle(40, 40);
        coverClip.setArcWidth(10);
        coverClip.setArcHeight(10);
        cover.setClip(coverClip);
        cover.setImage(coverPlaceholder(40));
        loadPackCover(cover, pk.name());

        String loader = pk.loaderType() == null ? "原版" : pk.loaderType();
        VBox info = new VBox(3);
        Label name = new Label(pk.name());
        name.getStyleClass().add("card-title");
        // 类别胶囊用加载器；版本胶囊显示该整合包的适配 MC 版本（登记簿 mcVersion 优先），
        // 不再随「当前游戏」切换（0.9.4）。
        HBox chips = new HBox(6,
                tagChip(loader, "tag-loader-sm"),
                versionTag(AdaptedMc.resolve(pk.name(), null, pk.mcVersion(), null)));
        chips.setAlignment(Pos.CENTER_LEFT);
        info.getChildren().addAll(name, chips);

        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);
        // 1.1.3：不再给整合包整体开关——模组 / 光影各自在自己的页面里启停、删除。
        Button remove = new Button("卸载");
        remove.getStyleClass().add("btn-danger");
        remove.setMinHeight(32);
        remove.setOnAction(e -> uninstallModpack(pk, detachRow(row)));
        head.getChildren().addAll(cover, info, gap, remove);

        // R3 统计行
        int fileCount = pk.files() == null ? 0 : pk.files().size();
        HBox stats = new HBox(10);
        stats.setAlignment(Pos.CENTER_LEFT);
        stats.getStyleClass().add("mod-card-stats");
        Hyperlink open = new Hyperlink("打开文件夹");
        open.getStyleClass().add("mod-stat");
        open.setOnAction(e -> {
            Path dir = com.mclauncher.Platform.minecraftDir();
            if (!Files.exists(dir)) {
                toast("游戏目录还不存在：" + dir);
                return;
            }
            com.mclauncher.Platform.reveal(dir);
        });
        stats.getChildren().addAll(
                statLabel("文件 " + fileCount + " 个"),
                statLabel("MC " + text(pk.mcVersion())),
                statLabel("加载器 " + loader),
                tagChip("已导入", "tag-release"),
                open);
        row.getChildren().addAll(head, sepLine(), stats);
        return row;
    }

    /**
     * 已装整合包封面：登记簿只存了包名，没有 projectId / iconUrl。
     * 按包名去 Modrinth 搜一个 modpack 图标（尽力而为），失败就保留占位图。
     * 与已装模组 {@link #loadInstalledCover} 的降级策略一致，不动服务层数据结构。
     */
    private void loadPackCover(ImageView view, String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                List<ModrinthClient.Hit> hits = ModrinthClient.search(
                        name, null, null, 1, "下载量", java.util.List.of(), "modpack");
                if (!hits.isEmpty()) {
                    String url = hits.get(0).iconUrl();
                    if (url != null && !url.isBlank()) {
                        javafx.application.Platform.runLater(() -> loadCover(view, url, 40));
                    }
                }
            } catch (IOException ex) {
                // 搜不到 / 断网就留占位图，不影响管理功能
            }
        }, "gbl-pack-cover");
        t.setDaemon(true);
        t.start();
    }

    /** 1.1.3：移除整合包登记；模组 / 光影等用户文件一律保留（删包留模组）。 */
    private void uninstallModpack(ModpackRegistry.Pack pk, DetachedRow detached) {
        // 以前这里会把 pk.files() 里的文件逐个删掉，用户删完包发现 mods 里几十个前置模组
        // 全没了。现在只移除登记；没有别的包时把旧清单改名留档，避免启动前的检查照着
        // 旧清单报「缺 N 个模组」。全程后台、零删除，乐观摘行 / 回滚机制保留。
        final Path root = com.mclauncher.Platform.minecraftDir();
        runDiskTask(() -> {
            try {
                ModpackRegistry.remove(pk.name());
            } catch (IOException ex) {
                System.err.println("[modpack] 移除登记失败: " + ex);
            }
            if (ModpackRegistry.all().isEmpty()) {
                try {
                    Path archived = ModpackRegistry.archiveIndex(root);
                    if (archived != null) {
                        System.out.println("[modpack] 旧整合包清单已留档: "
                                + archived.getFileName());
                    }
                } catch (IOException ex) {
                    System.err.println("[modpack] 旧清单留档失败（不影响移除登记）: " + ex);
                }
            } else {
                System.out.println("[modpack] 还有其它整合包登记，保留 modrinth.index.json 不动");
            }
            return true;
        }, "移除整合包 " + pk.name(),
                () -> {
                    toast("已移除整合包「" + pk.name()
                            + "」的记录（模组/光影文件已保留，可在各自的模组/光影页单独启停删除）");
                    scheduleGameManageRefresh();
                },
                () -> {
                    restoreDetachedRow(detached);
                    toast("移除整合包「" + pk.name() + "」没成功，请关掉游戏后重试");
                });
    }

    /**
     * 1.1.3：迁移 1.1.2 遗留的整合包禁用态。
     *
     * <p>老版本用整体开关把 pk.files() 里的文件改成了 .gbl-disabled，用户升级后会
     * 莫名其妙少一堆模组。这里在后台把文件改回原名并持久化 enabled=true，
     * 只跑一次、不删任何文件、不阻塞 JavaFX 界面线程。
     */
    private void restoreLegacyDisabledPacks() {
        if (legacyPackRestoreDone) {
            return;
        }
        legacyPackRestoreDone = true;
        runDiskTask(() -> {
            List<ModpackRegistry.Pack> pending = new java.util.ArrayList<>();
            for (ModpackRegistry.Pack pk : ModpackRegistry.all()) {
                if (!pk.enabled()) {
                    pending.add(pk);
                }
            }
            if (pending.isEmpty()) {
                return true;
            }
            int changed = 0;
            Path root = com.mclauncher.Platform.minecraftDir();
            for (ModpackRegistry.Pack pk : pending) {
                ModpackRegistry.EnabledResult r = ModpackRegistry.applyEnabled(root, pk, true);
                changed += r.changed();
                try {
                    ModpackRegistry.setEnabled(pk.name(), true);
                } catch (IOException ex) {
                    System.err.println("[modpack] 恢复「" + pk.name() + "」启用状态失败: " + ex);
                }
            }
            System.out.println("[modpack] 1.1.2 禁用态迁移完成：恢复 " + changed
                    + " 个文件、" + pending.size() + " 条登记");
            return true;
        }, "恢复整合包文件",
                this::scheduleGameManageRefresh,
                () -> System.err.println("[modpack] 禁用态迁移失败，下次启动再试"));
    }

    /**
     * 懒建 Mod 中心三块共用的单例控件（{@code modGameBox / modSourceBox / modCenterStatus / modImportRow}）。
     *
     * <p>0.9.6 r3 起每次进下载页 1..6 号 tab 都走 {@link #modToolbar()}，但控件只建一次——
     * JavaFX 节点是单例，重新挂新父即可。下拉监听器里用 {@code modGameBoxSyncing} 压住程序回填，
     * 不让「重设选项」被当成「用户改了选择」。
     */
    private void initModControls() {
        if (modGameBox == null) {
            modGameBox = new ComboBox<>();
            wireDarkPopup(modGameBox);   // 0.9.1：深色下拉补底
            modGameBox.getStyleClass().add("setting-select");
            modGameBox.setPromptText("选游戏");
            modGameBox.setOnAction(e -> {
                if (modGameBoxSyncing) {
                    return;   // 程序在回填选项，不是用户改选择
                }
                String keep = modGameBox.getValue();
                if (java.util.Objects.equals(keep, modCenterGameId)) {
                    return;
                }
                boolean gameChanged = !java.util.Objects.equals(modCenterGameId, keep);
                modCenterGameId = keep;
                if (gameChanged) {
                    modLoaderVersions.clear();   // 换游戏了，上一个的版本列表作废
                }
                refreshModCenterStatus();
                // 0.9.6 r3 A3：三个内容 tab（4..6）共用本单例下拉。原来内容页自建的局部
                // gameBox 做法是 setCurrentGame(v) + showDownloadTab(index)（写全局当前游戏并
                // 重绘当前 tab），这里按当前激活的下载 tab 分流，保持行为等价；
                // modGameBoxSyncing 已在 refreshModCenter() 回填处压住，不会递归触发本监听器。
                if (currentDownloadTab >= 4 && currentDownloadTab <= 6) {
                    setCurrentGame(keep);
                    showDownloadTab(currentDownloadTab);
                } else {
                    refreshLoaderTab();
                    refreshModTab();
                    refreshModpackTab();
                }
            });
        }
        if (modSourceBox == null) {
            modSourceBox = new ComboBox<>();
            wireDarkPopup(modSourceBox);   // 0.9.1：深色下拉补底
            modSourceBox.getStyleClass().add("setting-select");
            modSourceBox.getItems().setAll(
                    java.util.Arrays.stream(SettingsStore.DownloadSource.values())
                            .map(SettingsStore.DownloadSource::label)
                            .toList());
            modSourceBox.getSelectionModel().select(settings.downloadSource().ordinal());
            modSourceBox.setOnAction(e -> switchDownloadSource());
        }
        if (modCenterStatus == null) {
            modCenterStatus = new Label();
            modCenterStatus.getStyleClass().add("page-desc");
            modCenterStatus.setWrapText(true);
        }
        if (modImportRow == null) {
            modImportRow = modImportRow();
        }
    }

    /** 这个弹层此刻的身份决定它长什么样。 */
    private List<Node> promptContent() {
        if ("rename".equals(promptMode)) {
            Label hint = new Label("新名字只能用字母、数字、点、下划线和连字符，磁盘上的文件夹会一起改名。");
            hint.getStyleClass().add("setting-desc");
            promptField = new TextField(promptGame.id());
            promptField.getStyleClass().add("prompt-field");
            // 回车就是确认：为一个名字专门去够按钮没道理
            promptField.setOnAction(e -> promptConfirm());
            return List.of(hint, promptField);
        }
        Label warn = new Label("删掉会进回收站，还能捡回来。");
        warn.getStyleClass().add("setting-desc");
        return List.of(
                dialogLine("游戏", promptGame.id()),
                dialogLine("大小", GameScanner.sizeText(promptGame.sizeBytes())),
                dialogLine("位置", GameScanner.versionsDir().getParent().toString()),
                warn);
    }

    private void promptConfirm() {
        String mode = promptMode;
        GameEntry game = promptGame;
        closePrompt();
        if ("rename".equals(mode)) {
            String next = promptField.getText().trim();
            if (next.isEmpty()) {
                return;   // 空名字不让改：文件夹名空着会让它下面所有东西都找不着
            }
            runDiskOp(() -> GameScanner.rename(game.id(), next), "重命名");
        } else {
            runDiskOp(() -> GameScanner.remove(game.id()), "删除");
        }
    }

    /**
     * Mod 中心（v0.7.0）：原「游戏管理」占位页改造而来，侧边栏入口与类名不动。
     *
     * <p>页面骨架（架构 §1.5）：页头（标题 + 下载源 + 当前游戏）→ 状态条 → tab 条
     * （加载器 / Mod / 整合包）→ tab body。三个 tab 的<strong>构建不发任何网络请求</strong>——
     * 加载器版本下拉先进「加载中…」，由后台线程拉列表后回填；网络不可用时显示
     * 「版本列表加载失败，稍后重试」，不卡界面。未选游戏时整页给空态引导卡。
     *
     * <p>进度与反馈沿用 {@link #toast} + 一行阶段文字（导入整合包那条带取消），
     * 不造第二套反馈组件。
     */
    private ScrollPane buildGameManagePage() {
        VBox page = new VBox(14);
        page.getStyleClass().addAll("game-manage-page");
        page.setPadding(new Insets(20));
        page.setMaxWidth(1180);

        Label title = new Label("游戏管理");
        title.getStyleClass().add("page-title");
        Label desc = new Label("这里管理当前选中的游戏。"
                + "装新的、装加载器、管模组都在「下载」页。");
        desc.getStyleClass().add("setting-desc");
        desc.setWrapText(true);
        desc.setMaxWidth(720);

        // 当前游戏状态卡：显示当前选中的游戏 + 加载器状态，顺手给个开文件夹入口
        gameMgmtStatus = new Label();
        gameMgmtStatus.getStyleClass().add("page-desc");
        gameMgmtStatus.setWrapText(true);
        Button folder = new Button("打开文件夹");
        folder.setGraphic(Icons.create(Icons.FOLDER, 15));
        folder.setGraphicTextGap(9);
        folder.getStyleClass().add("btn-ghost");
        folder.setMinHeight(34);
        folder.setOnAction(e -> {
            if (currentGameId != null) {
                runDiskOp(() -> GameScanner.openFolder(currentGameId), "打开文件夹");
            } else {
                toast("还没有当前游戏");
            }
        });
        gameMgmtStatusIcon = versionBlockIcon(null, 28);
        VBox statusInner = new VBox(10, gameMgmtStatus, folder);
        statusInner.getStyleClass().addAll("card", "mgmt-status-card");
        statusInner.setPadding(new Insets(14, 16, 14, 16));
        HBox statusRow = new HBox(12, gameMgmtStatusIcon, statusInner);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        // 安装新游戏入口：跳到下载页游戏 tab（实例名 / 组件选择在 HMCL 式安装弹窗里）
        Button installNew = new Button("安装新游戏");
        installNew.setGraphic(Icons.create(Icons.DOWNLOAD, 15));
        installNew.setGraphicTextGap(9);
        installNew.getStyleClass().add("btn-download");
        installNew.setMinHeight(38);
        installNew.setOnAction(e -> showPage(Page.DOWNLOAD));
        Label guide = new Label("装加载器去「下载」页，管模组去「模组」页，导整合包去「整合包」页。");
        guide.getStyleClass().add("page-desc");
        guide.setWrapText(true);
        VBox entryCard = new VBox(10, installNew, guide);
        entryCard.getStyleClass().addAll("card", "mgmt-entry-card");
        entryCard.setPadding(new Insets(14, 16, 14, 16));

        // 已安装加载器段：类型 + 版本 + 更换 / 卸载（更换跳下载页加载器 tab）
        gameMgmtLoaderBody = new VBox(10);
        gameMgmtLoaderBox = buildMgmtSection("已安装加载器",
                "当前游戏装着的加载器（Fabric / Forge / Quilt 等）。要换一种先卸载、再装新的。",
                gameMgmtLoaderBody);
        // 已安装模组段：带封面 + 启停 + 卸载，复用 modRow
        gameMgmtModsBody = new VBox(10);
        gameMgmtModsBox = buildMgmtSection("已安装模组",
                "装进这个游戏的模组都在这里管——启停、卸载都不用去「模组」页。",
                gameMgmtModsBody);
        // 管理整合包段：列出已导入的 Modrinth 整合包，支持卸载（0.8.5 新增）
        gameMgmtPackBody = new VBox(10);
        gameMgmtPackBox = buildMgmtSection("管理整合包",
                "导入过的 Modrinth 整合包都在这里——可一键打开游戏目录或卸载它带来的文件。",
                gameMgmtPackBody);

        // 0.8.7：资源包 / 光影 / 世界 三个本地内容管理段（全局可管，与当前游戏无关）
        gameMgmtResourcePackBody = new VBox(10);
        gameMgmtResourcePackBox = buildMgmtSection("资源包",
                "装进 .minecraft/resourcepacks 的材质包都在这里：启停、卸载、打开目录。",
                gameMgmtResourcePackBody);
        gameMgmtShaderBody = new VBox(10);
        gameMgmtShaderBox = buildMgmtSection("光影",
                "装进 .minecraft/shaderpacks 的光影包都在这里。光影需要 OptiFine 或 Iris 才生效。",
                gameMgmtShaderBody);
        gameMgmtWorldBody = new VBox(10);
        gameMgmtWorldBox = buildMgmtSection("世界",
                "导入到 .minecraft/saves 的存档都在这里：卸载、打开目录（不支持启停）。",
                gameMgmtWorldBody);

        page.getChildren().addAll(title, desc, statusRow, entryCard,
                gameMgmtLoaderBox, gameMgmtModsBox, gameMgmtPackBox,
                gameMgmtResourcePackBox, gameMgmtShaderBox, gameMgmtWorldBox);
        refreshGameManage();


        StackPage wrap = new StackPage(page);
        ScrollPane scroll = new ScrollPane(wrap);
        scroll.getStyleClass().add("content-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        return scroll;
    }

    // ---------------------------------------------------------------- Mod 中心：切 tab

    /**
     * 切 Mod 中心的 tab。整块 body 换掉，不叠三层页面——同一块区域同时存在三份内容只会互相挡。
     *
     * <p>状态类名整份 {@code setAll}：{@code getStyleClass()} 是允许重复的集合，
     * 只 remove 一个的话连点会把 {@code selected} 攒成一片（下载页同款教训）。
     */
    /** 空态引导卡：复用下载页那套 page-blank 样式，不新造 CSS。 */
    private static Node modCenterEmpty(String hint) {
        VBox card = new VBox(8);
        card.getStyleClass().addAll("card", "page-blank");
        card.setAlignment(Pos.CENTER);
        Label title = new Label(hint);
        title.getStyleClass().add("page-blank-title");
        title.setWrapText(true);
        card.getChildren().add(title);
        return card;
    }

    // ---------------------------------------------------------------- Mod 中心：进场刷新

    /**
     * 进场懒刷新：重扫游戏列表、加载器状态、mod 列表（架构 §1.5）。
     *
     * <p>{@code showPage(Page.GAMEMGMT)} 时调——游戏列表页改了当前游戏后切过来即同步。
     * 只读本地 + 起后台线程拉加载器版本，构建本身不发网络。
     */
    private void refreshModCenter() {
        initModControls();   // 确保单例控件已建（首次进页时 modToolbar 还没跑，box 还是 null）
        if (modGameBox == null) {
            return;   // 极端情况：控件没建出来，别往下走
        }
        List<GameEntry> games = GameScanner.scan();
        String keep = modCenterGameId;
        final String previous = keep;   // 判「还在不在磁盘上」要用声明时的值，别被后面的回填搅了
        boolean stale = previous == null
                || games.stream().noneMatch(g -> g.id().equals(previous));
        if (stale) {
            // 0.8.8 Q3 第 12 项：此处是「页面级目标初始化」，保留全局 currentGameId 作兜底、
            // 再兜磁盘第一个，不并入安装/读取的 effectiveGameId() 收口；
            // 其优先级与 effectiveGameId() 完全一致（显式选中 → 全局 → 磁盘第一个），故不重复实现。
            // 选择跟全局当前游戏走；它也没有就挑磁盘上第一个（scan 已按发行日倒序）
            final String fallback = currentGameId;
            boolean fallbackGone = fallback == null
                    || games.stream().noneMatch(g -> g.id().equals(fallback));
            keep = fallbackGone ? (games.isEmpty() ? null : games.get(0).id()) : fallback;
        }
        modGameBoxSyncing = true;
        try {
            modGameBox.getItems().setAll(games.stream().map(GameEntry::id).toList());
            if (keep == null) {
                modGameBox.getSelectionModel().clearSelection();
            } else {
                modGameBox.setValue(keep);
            }
        } finally {
            modGameBoxSyncing = false;
        }
        boolean gameChanged = !java.util.Objects.equals(modCenterGameId, keep);
        modCenterGameId = keep;
        if (gameChanged) {
            // 换游戏了，上一个游戏的加载器版本列表作废（不同 MC 版本列表不一样）
            modLoaderVersions.clear();
        }
        refreshModCenterStatus();
        // 0.9.4 r2 B2：不再无条件三连刷新——访问一次「模组」tab 会同时跑本方法里的
        // refreshModTab 与 buildModTab → mergeBothSourcesOnFirstEnter 里那一次，起两组
        // 不取消的线程、旧 seq 立刻作废。只刷「当前可见的下载 tab」对应的一个方法；
        // 切到任意 tab 时 bodyOfDownloadTab 都会再调本方法，所以不会让其它 tab 少数据。
        // 0.9.6 r3：default 对下标 4~6 内容 tab 保持「不刷列表」是对的——工具条统一后
        // 这三个 tab 同样会走本方法回填单例下拉与状态文字，但内容列表与当前游戏无关，
        // 仍由 buildContentTab() / refreshContentTab() 自己刷，无需在这里补。
        modTabMergePending = false;
        switch (currentDownloadTab) {
            case 1 -> refreshLoaderTab();
            case 3 -> refreshModpackTab();
            // 0.9.6 r4：模组(2) 不在工具条刷新阶段发 refreshModTab —— 此刻 modResultBox 仍是
            // 上一次的旧容器（已脱离场景），缓存命中会渲进旧容器导致新页空白；且同一次进入连发两次
            // 会 ++modRefreshSeq 让先发的 seq 立刻作废、重复起两组线程。首屏与后续刷新统一交给
            // buildModTab() 控件建完后的 Platform.runLater 补刷（全文件唯一触发点）。
            default -> { /* 游戏 / 队列 / 内容 tab：这里不刷 */ }
        }
    }

    /** 状态条那句话。未选游戏 / 原版 / 已装加载器三种态。 */
    private void refreshModCenterStatus() {
        if (modCenterStatus == null) {
            return;
        }
        if (modCenterGameId == null) {
            modCenterStatus.setText("还没有游戏：先去游戏列表装一个，或在上方「当前游戏」里选一个");
            return;
        }
        LoaderInstaller.Status status = null;
        String broken = null;
        try {
            status = LoaderInstaller.status(modCenterGameId);
        } catch (IOException ex) {
            broken = ex.getMessage();
        }
        if (broken != null) {
            modCenterStatus.setText(modCenterGameId + " · 加载器状态读不了（" + broken + "）");
        } else if (status == null) {
            modCenterStatus.setText(modCenterGameId + " · 原版 · 未安装加载器");
        } else {
            modCenterStatus.setText(modCenterGameId + " · " + status.type().label() + " "
                    + status.loaderVersion() + " · 已安装");
        }
    }

    /** 下载源下拉切档：落设置、即时生效（Mirror 每次都现读，不用重启）。 */
    private void switchDownloadSource() {
        int at = modSourceBox.getSelectionModel().getSelectedIndex();
        SettingsStore.DownloadSource source = SettingsStore.DownloadSource.values()
                [Math.max(0, Math.min(at, SettingsStore.DownloadSource.values().length - 1))];
        settings = settings.withDownloadSource(source);
        commitSettings();
        toast("下载源已切换到「" + source.label() + "」");
        // 0.8.8 R-08/Q4：换源后加载器版本可能不同，清全部缓存 + guard 并重拉一次
        modLoaderVersions.clear();
        modVersionsLoading.clear();
        refreshLoaderTab();
    }

    // ---------------------------------------------------------------- Mod 中心：加载器 tab

    /**
     * 加载器 tab：三张卡（Fabric / Quilt / LegacyFabric），一张卡 = 状态 + 版本下拉 + 安装/卸载。
     *
     * <p><strong>构建时不发网络</strong>：版本下拉初始「加载中…」，由
     * {@link #ensureLoaderVersions} 起后台线程拉，回来在 FX 线程回填。
     */
    private Node buildLoaderTab() {
        VBox box = new VBox(12);
        box.getStyleClass().add("mod-tab-body");
        for (LoaderType type : LoaderType.values()) {
            box.getChildren().add(loaderCard(type));
        }
        // 0.8.8 R-07：refreshModCenter() 在建卡前跑、被空 map 守卫跳过；建完卡后再刷一次，
        // 保证首次进页就发版本请求（不依赖 modGameBox.onAction，也不调换 refreshModCenter 顺序）。
        refreshLoaderTab();
        return box;
    }

    /**
     * 一张加载器卡（0.8.8 R-08 三态）：LOADING「正在拉取版本…」/ READY 版本列表 /
     * EMPTY「暂无兼容版本」/ FAILED 常驻错误 + 重试。5 卡独立、互不阻塞。
     */
    private Node loaderCard(LoaderType type) {
        VBox card = new VBox(10);
        card.getStyleClass().addAll("card", "loader-card");
        card.setPadding(new Insets(14, 16, 14, 16));

        Label name = new Label(type.label());
        name.getStyleClass().add("card-title");
        Label state = new Label("未安装");
        state.getStyleClass().add("page-desc");

        ComboBox<String> versions = new ComboBox<>();
        versions.getStyleClass().add("setting-select");
        versions.setPrefWidth(240);
        versions.getItems().add("正在拉取版本…");
        versions.setValue("正在拉取版本…");   // 0.8.8 R-07：可见文案，不再是空白块
        versions.setDisable(true);
        wireDarkPopup(versions);   // 0.9.1：统一走辅助方法，和其它 11 个下拉同口径

        Button action = new Button("安装");
        action.getStyleClass().add("btn-download");
        action.setMinHeight(34);
        action.setOnAction(e -> onLoaderAction(type));

        Label error = new Label();
        error.getStyleClass().add("loader-card-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);

        Button retry = new Button("重试");
        retry.getStyleClass().add("loader-card-retry");
        retry.setVisible(false);
        retry.setManaged(false);
        retry.setOnAction(e -> retryLoaderVersions(type));

        Node icon = loaderIconNode(type, type.name(), 28);   // 0.8.9 R-03：加载器图片
        HBox row = new HBox(12, icon, name, state);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(row, Priority.ALWAYS);
        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox tail = new HBox(10, versions, action);
        tail.setAlignment(Pos.CENTER_RIGHT);
        card.getChildren().addAll(row, tail, error, retry);

        modLoaderBoxes.put(type, versions);
        modLoaderStates.put(type, state);
        modLoaderButtons.put(type, action);
        modLoaderErrors.put(type, error);
        modLoaderRetries.put(type, retry);
        return card;
    }

    /** 统一切一张卡的错误/重试三态（READY / EMPTY 都 failed=false）。 */
    private void setLoaderCardState(LoaderType type, boolean failed, String message) {
        Label err = modLoaderErrors.get(type);
        Button retry = modLoaderRetries.get(type);
        if (err != null) {
            err.setVisible(failed);
            err.setManaged(failed);
            if (failed) {
                err.setText("版本加载失败：" + (message == null || message.isBlank()
                        ? "请检查网络或换个下载源" : message) + "。点「重试」再拉一次。");
            }
        }
        if (retry != null) {
            retry.setVisible(failed);
            retry.setManaged(failed);
        }
    }

    /** 把版本列表（含空列表）回填到某张卡：READY / EMPTY 两种终态。 */
    private void applyLoaderVersions(LoaderType type, ComboBox<String> box,
                                     List<LoaderInstaller.LoaderVersion> list) {
        List<LoaderInstaller.LoaderVersion> safe = list == null ? List.of() : list;
        setLoaderCardState(type, false, null);
        if (safe.isEmpty()) {
            // EMPTY ≠ FAILED：LegacyFabric 对某些 MC 版本不支持属正常，不是错误
            box.getItems().setAll("暂无兼容版本");
            box.setValue("暂无兼容版本");
            box.setDisable(true);
            return;
        }
        box.getItems().setAll(safe.stream()
                .map(LoaderInstaller.LoaderVersion::version).toList());
        int at = 0;
        for (int i = 0; i < safe.size(); i++) {
            if (safe.get(i).stable()) {
                at = i;   // 最新在前，第一个 stable 就是最新稳定版
                break;
            }
        }
        box.getSelectionModel().select(at);
        box.setDisable(false);
    }

    /** 刷新各张卡：状态文案、按钮身份（安装 / 卸载）、版本下拉三态。 */
    private void refreshLoaderTab() {
        String game = effectiveGameId();   // 0.8.8 Q3：读哪个游戏的已装加载器，统一取值口
        for (LoaderType type : LoaderType.values()) {
            Label state = modLoaderStates.get(type);
            Button action = modLoaderButtons.get(type);
            ComboBox<String> box = modLoaderBoxes.get(type);
            if (state == null || action == null || box == null) {
                continue;   // 这一页还没建出来
            }
            setLoaderCardState(type, false, null);   // 先清旧 FAILED 态；失败会在本轮重拉后再显示
            if (game == null) {
                state.setText("未安装");
                action.setText("安装");
                action.setDisable(true);
                box.getItems().setAll("正在拉取版本…");
                box.setValue("正在拉取版本…");
                box.setDisable(true);
                continue;
            }
            LoaderInstaller.Status status = null;
            try {
                status = LoaderInstaller.status(game);
            } catch (IOException ex) {
                state.setText("状态读不了");
                action.setDisable(true);
                continue;
            }
            boolean installed = status != null && status.type() == type;
            state.setText(installed ? "已安装 " + status.loaderVersion() : "未安装");
            action.setText(installed ? "卸载" : "安装");
            action.setDisable(false);
            if (installed) {
                // 装着的加载器先看版本再谈别的：下拉锁住在装的那一版，想换版本先卸载
                box.getItems().setAll(status.loaderVersion());
                box.getSelectionModel().selectFirst();
                box.setDisable(true);
            } else {
                box.setDisable(false);
                ensureLoaderVersions(type, box);
            }
        }
    }

    /**
     * 后台拉这个加载器的版本列表（0.8.8 R-08 三态）：
     * 缓存命中直接回填（重建卡后不必重打接口）；否则 LOADING「正在拉取版本…」→ 后台拉 →
     * READY（默认最新稳定版）/ EMPTY「暂无兼容版本」/ FAILED（卡内常驻错误 + 重试）。
     *
     * <p>{@code modVersionsLoading.remove(type)} 放在 {@code finally}、{@code catch} 覆盖
     * {@code Exception}（修「非 IOException 异常永久卡死 guard」）。单卡 FAILED 不影响其他 4 卡。
     * 作废校验与 {@link #effectiveGameId()} 比对（原与 modCenterGameId 比对，已废弃）。
     */
    private void ensureLoaderVersions(LoaderType type, ComboBox<String> box) {
        List<LoaderInstaller.LoaderVersion> cached = modLoaderVersions.get(type);
        if (cached != null) {
            applyLoaderVersions(type, box, cached);   // 缓存命中：直接回填，不重打接口
            return;
        }
        if (modVersionsLoading.contains(type)) {
            return;   // 正在拉，避免重复请求
        }
        String game = effectiveGameId();   // 0.8.8 Q3：安装/读取目标统一取值口
        if (game == null) {
            return;
        }
        modVersionsLoading.add(type);
        // LOADING：下拉显示可见文案，隐藏错误 / 重试
        box.getItems().setAll("正在拉取版本…");
        box.setValue("正在拉取版本…");
        box.setDisable(true);
        setLoaderCardState(type, false, null);
        Thread fetch = new Thread(() -> {
            try {
                List<LoaderInstaller.LoaderVersion> list = LoaderInstaller.versions(type, game);
                javafx.application.Platform.runLater(() -> {
                    if (!game.equals(effectiveGameId())) {
                        return;   // 切游戏了，这次结果作废（guard 已在 finally 清，下次 refresh 会重拉）
                    }
                    modLoaderVersions.put(type, list);
                    applyLoaderVersions(type, box, list);
                });
            } catch (Exception ex) {
                javafx.application.Platform.runLater(() -> {
                    // FAILED：常驻可见错误 + 重试；EMPTY 不走这里
                    box.getItems().setAll("版本列表加载失败");
                    box.setValue("版本列表加载失败");
                    box.setDisable(true);
                    setLoaderCardState(type, true, ex.getMessage());
                    System.err.println("[loader] 版本列表拉取失败: " + ex.getMessage());
                });
            } finally {
                // 0.8.8 R-08：必须 finally 清，否则非 IOException 异常会让该卡永远「加载中」
                modVersionsLoading.remove(type);
            }
        }, "gbl-loader");
        fetch.setDaemon(true);
        fetch.start();
    }

    /** 重试某张卡：清该卡缓存 + guard、置回 LOADING、重调 {@link #ensureLoaderVersions}。 */
    private void retryLoaderVersions(LoaderType type) {
        modLoaderVersions.remove(type);
        modVersionsLoading.remove(type);
        ComboBox<String> box = modLoaderBoxes.get(type);
        if (box == null) {
            return;
        }
        box.getItems().setAll("正在拉取版本…");
        box.setValue("正在拉取版本…");
        box.setDisable(true);
        setLoaderCardState(type, false, null);
        ensureLoaderVersions(type, box);
    }

    /** 卡上那颗按钮：装着 → 卸载；没装 → 用下拉里选中的版本安装。 */
    private void onLoaderAction(LoaderType type) {
        String game = effectiveGameId();   // 0.8.8 Q3：安装/读取目标统一取值口
        if (game == null) {
            toast("先选一个游戏");
            return;
        }
        Button action = modLoaderButtons.get(type);
        if (action != null && "卸载".equals(action.getText())) {
            runLoaderUninstall(game, type);
            return;
        }
        ComboBox<String> box = modLoaderBoxes.get(type);
        String version = box == null ? null : box.getValue();
        // 0.8.8 R-08：LOADING / EMPTY / FAILED 三种占位文案都不是可安装版本
        if (version == null || version.isBlank()
                || version.startsWith("正在拉取")
                || version.startsWith("加载中")
                || version.startsWith("版本列表")
                || version.equals("暂无兼容版本")) {
            toast("先选一个 " + type.label() + " 版本");
            return;
        }
        runLoaderInstall(game, type, version);
    }

    /** 后台装加载器：阶段文字进状态条，完成 / 失败都走 toast + 局部刷新。 */
    private void runLoaderInstall(String game, LoaderType type, String version) {
        modCenterStatus.setText("正在安装 " + type.label() + " " + version + " …");
        final String title = type.label() + " " + version;
        // 0.9.0 R-01/R-02：加载器安装也进 DownloadCenter；任何异常（含非 IOException）
        // 都收敛成 FAILED，不再卡在「正在安装…」。
        // 0.9.1：改走专用 Category.LOADER，且图标按 LoaderType.id() 取对应 png（fabric.png /
        // quilt.png / forge.png / neoforge.png / optifine.png …）。原先硬编码 MOD + chest.png，
        // 队列里每行都显示「模组」加一个真箱子，和装的是什么完全对不上。
        DownloadCenter.DEFAULT.submit(title, DownloadCenter.Category.LOADER, type.id() + ".png",
            (DownloadCenter.Progress p) -> {
                try {
                    LoaderInstaller.install(game, type, version, new LoaderInstaller.Listener() {
                        @Override
                        public void onStage(String text) {
                            javafx.application.Platform.runLater(() -> modCenterStatus.setText(text));
                        }

                        @Override
                        public void onLibProgress(int done, int total) {
                            p.onProgress(done, total);
                            javafx.application.Platform.runLater(() -> modCenterStatus.setText(
                                    "正在下载 " + type.label() + " 依赖库…（" + done + "/" + total + "）"));
                        }
                    });
                    javafx.application.Platform.runLater(() -> {
                        toast(type.label() + " " + version + " 安装完成");
                        refreshModCenterStatus();
                        refreshLoaderTab();
                        refreshModTab();
                    });
                    return java.nio.file.Paths.get(version);
                } catch (Exception ex) {
                    if (ex instanceof java.util.concurrent.CancellationException) {
                        javafx.application.Platform.runLater(() -> modCenterStatus.setText("已取消安装"));
                    } else {
                        javafx.application.Platform.runLater(() -> {
                            modCenterStatus.setText("安装失败：" + ex.getMessage());
                            toast(type.label() + " 没装上：" + ex.getMessage());
                        });
                    }
                    throw ex;
                }
            });
    }

    /** 后台卸载加载器：还原原版 JSON，完成 / 失败同样 toast + 刷新。 */
    private void runLoaderUninstall(String game, LoaderType type) {
        modCenterStatus.setText("正在卸载 " + type.label() + " …");
        Thread work = new Thread(() -> {
            try {
                LoaderInstaller.uninstall(game, new LoaderInstaller.Listener() {
                    @Override
                    public void onStage(String text) {
                        javafx.application.Platform.runLater(() -> modCenterStatus.setText(text));
                    }
                });
                javafx.application.Platform.runLater(() -> {
                    toast(type.label() + " 已卸载，还原成原版");
                    refreshModCenterStatus();
                    refreshLoaderTab();
                    refreshModTab();
                    refreshGameManage();
                });
            } catch (IOException ex) {
                javafx.application.Platform.runLater(() -> {
                    modCenterStatus.setText("卸载失败：" + ex.getMessage());
                    toast(type.label() + " 没卸掉：" + ex.getMessage());
                    refreshGameManage();
                });
            }
        }, "gbl-loader");
        work.setDaemon(true);
        work.start();
    }

    // ---------------------------------------------------------------- Mod 中心：Mod tab

    /**
     * Mod tab：上面搜 Modrinth，下面管已装的 mod。
     *
     * <p>搜索带 300ms 防抖（架构 D4 限速自律：Modrinth 300 req/min，不预取翻页）；
     * 加载器过滤跟着当前游戏走（原版不过滤）。安装走
     * {@link ModrinthClient#versions} 取最新兼容版 → {@link ModrinthClient#install}。
     */
    private Node buildModTab() {
        // 0.9.9 P3：重建前停掉上一次的搜索防抖，避免旧定时器再打一次无用接口
        if (modSearchDebounce != null) {
            modSearchDebounce.stop();
        }
        VBox box = new VBox(12);
        box.getStyleClass().add("mod-tab-body");
        box.setMaxWidth(MOD_CONTENT_WIDTH);

        modSearchField = new TextField();
        modSearchField.setPromptText(modSourcePrompt());
        modSearchField.getStyleClass().add("version-search");
        modSearchField.setPrefHeight(34);
        // 0.8.6：搜索框不能无限吃宽度，否则 CF 分类行一撑开就把右侧源切换药丸挤出视口
        modSearchField.setMaxWidth(520);
        HBox.setHgrow(modSearchField, Priority.ALWAYS);
        Button search = new Button("搜索");
        search.getStyleClass().add("btn-download");
        search.setMinHeight(34);
        search.setOnAction(e -> {
            if (modSearchDebounce != null) {
                modSearchDebounce.stop();
            }
            searchMods();
        });
        modSearchField.setOnAction(e -> {
            if (modSearchDebounce != null) {
                modSearchDebounce.stop();
            }
            searchMods();
        });
        modSearchDebounce = new PauseTransition(Duration.millis(400));
        modSearchDebounce.setOnFinished(e -> searchMods());
        modSearchField.textProperty().addListener((obs, oldText, newText) -> {
            if (newText != null && !newText.isBlank()) {
                modSearchDebounce.playFromStart();
            } else if (modSearchDebounce != null) {
                // 清空 → 立刻回到推荐列表
                modSearchDebounce.stop();
                if (modResultBox != null) {
                    refreshModTab();
                }
            }
        });

        modSortBox = new ComboBox<>();
        wireDarkPopup(modSortBox);   // 0.9.1：深色下拉补底
        modSortBox.getStyleClass().add("setting-select");
        modSortBox.getItems().setAll("下载量", "最新发布", "最近更新");
        modSortBox.getSelectionModel().selectFirst();
        modSortBox.setPrefWidth(120);
        modSortBox.setOnAction(e -> refreshModTab());

        HBox tools = new HBox(10, modSearchField, search, modSortBox, modSourceToggle());
        tools.setAlignment(Pos.CENTER_LEFT);
        tools.getStyleClass().add("mod-tools");
        tools.setMaxWidth(MOD_CONTENT_WIDTH);
        modToolsBox = tools;

        // 0.8.6：分类行改 FlowPane（可换行）。原来 HBox 不换行，CF 几十个英文长名分类
        // 会把整行撑到几千像素，连带把工具栏 / 结果网格撑开 → 按钮消失、列数漂移。
        modCategoryRow = new FlowPane();
        modCategoryRow.getStyleClass().add("mod-cat-row");
        modCategoryRow.setHgap(8);
        modCategoryRow.setVgap(8);
        modCategoryRow.setPrefWrapLength(MOD_CONTENT_WIDTH);
        modCategoryRow.setMaxWidth(MOD_CONTENT_WIDTH);
        modSelectedCats = new ArrayList<>();
        cfSelectedCats = new ArrayList<>();
        // 切 tab 会重建整页：这里必须按当前数据源重建分类行，否则 mode=1 时会挂回
        // Modrinth 的中文分类，出现「按钮显示 CurseForge、分类却是 Modrinth」的不一致。
        if (modSourceMode == 1) {
            buildCfCategoryChips();
        } else {
            buildModCategoryChips();
        }

        modResultCount = new Label("正在加载推荐模组…");
        modResultCount.getStyleClass().add("page-desc");
        modResultBox = new FlowPane();
        modResultBox.getStyleClass().add("mod-grid");
        modResultBox.setHgap(12);
        modResultBox.setVgap(12);
        modResultBox.setPrefWrapLength(MOD_CONTENT_WIDTH);
        modResultBox.setMaxWidth(MOD_CONTENT_WIDTH);
        // 0.9.9 r4：撤掉整块位图缓存。网格高度 3200~6400px 在 150% 缩放下达 8~16M 像素、超 d3d 的 4096 纹理上限，
        // 且 hover / 封面回填都会让整张位图重画；整窗阴影摘掉后这笔缓存的边际收益为负。
        modResultBox.setCache(false);

        box.getChildren().addAll(tools, modCategoryRow, modResultCount, modResultBox);
        // 0.9.6 r4：控件建完（modResultBox 在 7100 已 new）后在 FX 队列尾补刷一次，
        // 缓存命中也能渲到新容器；不再依赖「进程内首次」的脆弱开关。
        javafx.application.Platform.runLater(() -> {
            if (modSourceMode == 1) refreshCfTab(); else refreshModTab();
        });
        return box;
    }

    /**
     * r4 双源首屏合并：进入模组 tab 时主动触发一次刷新。
     *
     * <p>{@code bodyOfDownloadTab} 先跑 {@link #refreshModCenter()}（此时 modResultBox 还没建，
     * {@link #refreshModTab()} / {@link #refreshCfTab()} 都被 null 守卫跳过），再 buildModTab()
     * 建控件。这里在控件齐备后补一次刷新，首屏就有结果，不必手动切一次模组库。
     *
     * <p>合并模式（2）由 refreshModTab 并发拉 Modrinth + CurseForge 并按项目 id 去重合并；
     * 仅 CurseForge 模式（1）转调 refreshCfTab（历史铁律：两个刷新方法都覆盖）；
     * 单源模式（0）走 refreshModTab。缓存 key / generation seq / 不清空旧卡片 /
     * 每批 12 张跨帧 / 封面解码移出 FX 线程全部沿用既有链路，不另起炉灶。
     */
    private void mergeBothSourcesOnFirstEnter() {
        if (modResultBox == null || modSearchField == null) {
            return;   // 控件还没建（极端时序），交给下一次切 tab
        }
        // 0.9.4 r2 B2：本次进入若已在 refreshModCenter 里刷过一遍，就不再触发，
        // 避免同一次进入起两组不取消的线程、旧 seq 立刻作废。只有「首次进入、
        // refreshModCenter 因控件未建被 null 守卫跳过」才补这一次。合并逻辑本身保留。
        if (!modTabMergePending) {
            return;
        }
        modTabMergePending = false;
        // r4 指纹：字符串字面量（注释不进 class 常量池），首屏即触发两源并发合并的实证
        System.out.println("[r4] " + "r4 双源首屏合并" + "：首屏默认两源合并已触发");
        if (modSourceMode == 1) {
            refreshCfTab();
        } else {
            refreshModTab();
        }
    }

    /** r4 双源首屏合并：按 (source, id) 去重，避免两源 / 分页结果里同一项目重复建卡。 */
    private static List<UnifiedMod> dedupModsById(List<UnifiedMod> in) {
        Set<String> seen = new java.util.LinkedHashSet<>();
        List<UnifiedMod> out = new ArrayList<>(in.size());
        for (UnifiedMod m : in) {
            if (seen.add(m.source() + "\u0000" + m.id())) {
                out.add(m);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 分类胶囊：两源共用的统一渲染器

    /**
     * 一条分类胶囊的规格。三个来源共用同一套 chip 结构与点击逻辑：
     * <ul>
     *   <li>{@code slug}    → Modrinth facet，进 {@code modSelectedCats}；</li>
     *   <li>{@code cfId}    → CurseForge categoryId，进 {@code cfSelectedCats}；</li>
     *   <li>{@code keyword} → CF 接口不可用时的兜底，降级成按英文关键词搜索。</li>
     * </ul>
     */
    private record CategoryChip(String label, String slug, Long cfId, String keyword) {
        static CategoryChip modrinth(String label, String slug) {
            return new CategoryChip(label, slug, null, null);
        }

        static CategoryChip curseForge(String label, long id) {
            return new CategoryChip(label, null, id, null);
        }

        static CategoryChip fallback(String label, String keyword) {
            return new CategoryChip(label, null, null, keyword);
        }
    }

    /** 统一重建分类行：Modrinth / CurseForge 都走这里，保证两个源下 UI 完全一致。 */
    private void rebuildCategoryChips(List<CategoryChip> specs, boolean curseForge) {
        if (modCategoryRow == null) {
            return;
        }
        modCategoryRow.getChildren().clear();
        for (CategoryChip spec : specs) {
            Label chip = new Label(spec.label());
            chip.getStyleClass().addAll("filter-chip", "mod-cat-chip");
            chip.setUserData(spec);
            if (isCategorySelected(spec, curseForge)) {
                chip.getStyleClass().add("on");
            }
            chip.setOnMouseClicked(e -> toggleCategoryChip(chip, spec, curseForge));
            modCategoryRow.getChildren().add(chip);
        }
    }

    private boolean isCategorySelected(CategoryChip spec, boolean curseForge) {
        if (!curseForge && spec.slug() != null && modSelectedCats != null) {
            return modSelectedCats.contains(spec.slug());
        }
        if (curseForge && spec.cfId() != null && cfSelectedCats != null) {
            return cfSelectedCats.contains(spec.cfId());
        }
        if (curseForge && spec.keyword() != null && modSearchField != null) {
            return spec.keyword().equals(modSearchField.getText());
        }
        return false;
    }

    private void toggleCategoryChip(Label chip, CategoryChip spec, boolean curseForge) {
        boolean on = chip.getStyleClass().contains("on");
        if (on) {
            chip.getStyleClass().remove("on");
            if (!curseForge && spec.slug() != null && modSelectedCats != null) {
                modSelectedCats.remove(spec.slug());
            } else if (curseForge && spec.cfId() != null && cfSelectedCats != null) {
                cfSelectedCats.remove(spec.cfId());
            } else if (curseForge && spec.keyword() != null && modSearchField != null
                    && spec.keyword().equals(modSearchField.getText())) {
                modSearchField.clear();
                return;   // clear 的监听器自己会刷新
            }
        } else {
            chip.getStyleClass().add("on");
            if (!curseForge && spec.slug() != null) {
                if (modSelectedCats == null) {
                    modSelectedCats = new ArrayList<>();
                }
                if (!modSelectedCats.contains(spec.slug())) {
                    modSelectedCats.add(spec.slug());
                }
            } else if (curseForge && spec.cfId() != null) {
                if (cfSelectedCats == null) {
                    cfSelectedCats = new ArrayList<>();
                }
                if (!cfSelectedCats.contains(spec.cfId())) {
                    cfSelectedCats.add(spec.cfId());
                }
            } else if (curseForge && spec.keyword() != null) {
                // 兜底分类没有真实 categoryId：降级成按英文关键词搜（复用现有搜索链路）
                if (modSearchField != null) {
                    modSearchField.setText(spec.keyword());
                }
                toast("分类列表暂时不可用，已按「" + spec.label() + "」搜索");
                return;   // setText 的防抖自己会刷新，别重复触发
            }
        }
        refreshModTab();
    }

    /** Modrinth 分类胶囊：14 个中文硬编码，走统一渲染器。 */
    private void buildModCategoryChips() {
        String[][] defs = {
                {"优化", "optimization"}, {"实用", "utility"}, {"冒险", "adventure"},
                {"装饰", "decoration"}, {"库", "library"}, {"管理", "management"},
                {"世界生成", "worldgen"}, {"存储", "storage"}, {"食物", "food"},
                {"装备", "equipment"}, {"魔法", "magic"}, {"科技", "technology"},
                {"社交", "social"}, {"杂项", "misc"}};
        List<CategoryChip> specs = new ArrayList<>();
        for (String[] d : defs) {
            specs.add(CategoryChip.modrinth(d[0], d[1]));
        }
        rebuildCategoryChips(specs, false);
    }

    /** 当前选中的分类 slug 列表（副本，避免外部改到内部状态）。 */
    private List<String> selectedModCategories() {
        return modSelectedCats == null ? List.of() : new ArrayList<>(modSelectedCats);
    }

    // ---------------------------------------------------------------- 模组数据源：Modrinth / CurseForge 切换

    /** 数据源切换药丸（Modrinth / CurseForge / 两者合并）：提为字段，三个源下常驻。 */
    private HBox modSourceToggle() {
        HBox hb = new HBox(6);
        hb.getStyleClass().add("source-toggle");
        hb.getChildren().addAll(
                sourceButton("Modrinth", 0),
                sourceButton("CurseForge", 1),
                sourceButton("两者合并", 2));
        modSourceToggleBox = hb;
        syncModSourceToggle();
        return hb;
    }

    private Button sourceButton(String text, int mode) {
        Button b = new Button(text);
        b.getStyleClass().add("src-btn");
        b.setUserData(mode);
        b.setMinHeight(34);
        b.setPrefHeight(34);
        b.setMaxHeight(34);
        b.setOnAction(e -> selectModSource(mode));
        return b;
    }

    /** 按 {@code modSourceMode} 重算三个按钮的选中态（不依赖闭包，任何时刻都能调）。 */
    private void syncModSourceToggle() {
        if (modSourceToggleBox == null) {
            return;
        }
        for (Node n : modSourceToggleBox.getChildren()) {
            boolean on = n.getUserData() instanceof Integer mode && mode == modSourceMode;
            if (on) {
                if (!n.getStyleClass().contains("on")) {
                    n.getStyleClass().add("on");
                }
            } else {
                n.getStyleClass().remove("on");
            }
        }
    }

    /** 保证源切换药丸还在工具栏里（切 tab 重建 tools 时也不会丢）。 */
    private void ensureModSourceToggle() {
        if (modToolsBox == null) {
            return;
        }
        if (modSourceToggleBox == null) {
            modToolsBox.getChildren().add(modSourceToggle());
        } else if (!modToolsBox.getChildren().contains(modSourceToggleBox)) {
            modToolsBox.getChildren().add(modSourceToggleBox);
        }
        syncModSourceToggle();
    }

    private void selectModSource(int mode) {
        if (modSourceMode == mode) {
            return;
        }
        modSourceMode = mode;
        switchModSource();
    }

    /** 当前数据源对应的搜索框提示：初建 tab 与切源共用一份，杜绝两处文案打架。 */
    private String modSourcePrompt() {
        return switch (modSourceMode) {
            case 1 -> "搜索 CurseForge 上的 mod（输入即搜，回车立即搜）";
            case 2 -> "搜索 Modrinth + CurseForge 上的 mod（输入即搜）";
            default -> "搜索 Modrinth 上的 mod（输入即搜，回车立即搜）";
        };
    }

    /** 切换数据源：重置选中分类、重建分类胶囊、保证源切换按钮常驻、刷新。 */
    private void switchModSource() {
        // 先重置选中列表并重建分类，再 clear()：clear 会触发文本框监听器立刻刷新一次
        if (modSourceMode == 1) {
            cfSelectedCats = new ArrayList<>();
            buildCfCategoryChips();
        } else {
            modSelectedCats = new ArrayList<>();
            // 仅 Modrinth 或 两者合并：都用 Modrinth 分类胶囊（合并模式分类只作用于 Modrinth 一侧）
            buildModCategoryChips();
        }
        if (modSearchField != null) {
            modSearchField.clear();
            modSearchField.setPromptText(modSourcePrompt());
        }
        ensureModSourceToggle();
        refreshModTab();
    }

    /**
     * CurseForge 分类胶囊：先<b>同步</b>落一组中文兜底分类（不留空行），再后台拉真实分类；
     * 成功后校验 mode 再替换，失败打日志并保留兜底 + 一枚「重试分类」。
     */
    private void buildCfCategoryChips() {
        rebuildCategoryChips(cfFallbackChips(), true);
        Thread t = new Thread(() -> {
            try {
                List<CurseForgeClient.Category> cats = CurseForgeClient.categories();
                List<CategoryChip> specs = new ArrayList<>();
                for (CurseForgeClient.Category c : cats) {
                    if (specs.size() >= 12) {
                        break;   // 只取前 12 个，避免分类行过长
                    }
                    specs.add(CategoryChip.curseForge(cfCatCn(c.name()), c.id()));
                }
                javafx.application.Platform.runLater(() -> {
                    if (modSourceMode != 1) {
                        return;   // 防竞态：已切回 Modrinth / 合并，别往回写
                    }
                    if (specs.isEmpty()) {
                        System.err.println("[cf] 分类列表为空，保留中文兜底分类");
                        return;
                    }
                    rebuildCategoryChips(specs, true);
                });
            } catch (IOException ex) {
                System.err.println("[cf] 分类拉取失败，使用中文兜底分类: " + ex.getMessage());
                javafx.application.Platform.runLater(() -> {
                    if (modSourceMode != 1) {
                        return;
                    }
                    rebuildCategoryChips(cfFallbackChips(), true);
                    if (modCategoryRow != null) {
                        Label retry = new Label("重试分类");
                        retry.getStyleClass().addAll("filter-chip", "mod-cat-chip", "mod-cat-retry");
                        retry.setOnMouseClicked(e -> buildCfCategoryChips());
                        modCategoryRow.getChildren().add(retry);
                    }
                });
            }
        }, "gbl-cf-cat");
        t.setDaemon(true);
        t.start();
    }

    /** CF 接口不可用时的同步中文兜底分类；点击降级为按英文关键词搜索（不编造 categoryId）。 */
    private static List<CategoryChip> cfFallbackChips() {
        return List.of(
                CategoryChip.fallback("优化", "performance"),
                CategoryChip.fallback("科技", "technology"),
                CategoryChip.fallback("魔法", "magic"),
                CategoryChip.fallback("冒险", "adventure"),
                CategoryChip.fallback("世界生成", "worldgen"),
                CategoryChip.fallback("装饰", "decoration"),
                CategoryChip.fallback("实用", "utility"),
                CategoryChip.fallback("库", "library"),
                CategoryChip.fallback("地图", "map"),
                CategoryChip.fallback("装备", "equipment"));
    }

    /** CurseForge 分类名 → 中文（认不到就原样显示英文，不硬翻）。 */
    private static String cfCatCn(String name) {
        if (name == null || name.isBlank()) {
            return "未命名";
        }
        return switch (name) {
            case "API and Library" -> "库 / API";
            case "Armor, Tools, and Weapons" -> "装备 / 工具";
            case "Adventure and RPG" -> "冒险 / RPG";
            case "Technology" -> "科技";
            case "Magic" -> "魔法";
            case "World Gen" -> "世界生成";
            case "Cosmetic" -> "装饰";
            case "Food" -> "食物";
            case "Utility & QoL" -> "实用";
            case "Map and Information" -> "地图 / 信息";
            case "Mobs" -> "生物";
            case "Storage" -> "存储";
            case "Performance" -> "优化";
            case "Server Utility" -> "服务器工具";
            case "Addons" -> "附属";
            default -> name;
        };
    }

    /** CurseForge 当前选中的分类 id 列表（副本）。 */
    private List<Long> selectedCfCategories() {
        return cfSelectedCats == null ? List.of() : new ArrayList<>(cfSelectedCats);
    }

    /**
     * HMCL 式中文别名搜索：把「钠」扩展成「钠 / Sodium」逐条去搜，按项目 id 去重合并。
     * 英文 / 数字查询原样只搜一次，行为不变；任一关键词成功即出结果，全失败才抛 IOException。
     */
    private static List<ModrinthClient.Hit> searchModrinthAliased(
            String query, int limit, String sort, List<String> cats, String projectType)
            throws IOException {
        List<ModrinthClient.Hit> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        IOException last = null;
        int ok = 0;
        for (String q : ModAliases.expand(query)) {
            try {
                for (ModrinthClient.Hit h :
                        ModrinthClient.search(q, null, null, limit, sort, cats, projectType)) {
                    if (seen.add(String.valueOf(h.projectId()))) {
                        out.add(h);
                    }
                }
                ok++;
            } catch (IOException ex) {
                last = ex;
            }
        }
        if (ok == 0 && last != null) {
            throw last;
        }
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** CurseForge 侧的同一套别名扩展（modId 去重）。 */
    private static List<CurseForgeClient.Hit> searchCurseForgeAliased(
            String query, String sort, List<Long> cats, int limit) throws IOException {
        List<CurseForgeClient.Hit> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        IOException last = null;
        int ok = 0;
        for (String q : ModAliases.expand(query)) {
            try {
                for (CurseForgeClient.Hit h : CurseForgeClient.search(q, null, sort, cats, limit)) {
                    if (seen.add(String.valueOf(h.modId()))) {
                        out.add(h);
                    }
                }
                ok++;
            } catch (IOException ex) {
                last = ex;
            }
        }
        if (ok == 0 && last != null) {
            throw last;
        }
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** 结果栏里显示「搜中文其实替你搜了什么」，让别名功能可见。 */
    private static String aliasHint(String query) {
        String h = ModAliases.hint(query);
        return h.isEmpty() ? "" : "（" + h + "）";
    }

    /**
     * 0.9.1：把 CF-only 结果渲染到结果栏（缓存命中与网络回填共用同一套校验 + 分批逻辑）。
     * 文案保留 CF 专属标注；卡片走 {@code modCard(UnifiedMod)} —— 与合并模式里的 CF 卡片
     * 是同一渲染路径（{@code specOf(UnifiedMod)} 与 {@code specOf(CurseForgeClient.Hit)} 字段口径一致）。
     */
    private void renderCfHits(long seq, int modeAtStart, String query,
                              List<UnifiedMod> result) {
        FlowPane container = modResultBox;
        if (seq != modRefreshSeq || container == null || modSourceMode != modeAtStart) {
            return;   // 结果过期 / 已切源 / 页已重建，整份丢弃
        }
        if (result.isEmpty()) {
            modResultCount.setText(query.isBlank() ? "CurseForge 暂时没有推荐模组，稍后重试"
                    : "CurseForge 没搜到，换个关键词");
            container.getChildren().setAll(modCenterEmpty(
                    query.isBlank() ? "CurseForge 暂时没有推荐模组" : "没有匹配的模组"));
            return;
        }
        modResultCount.setText((query.isBlank() ? "推荐模组" : "搜索结果")
                + " · " + result.size() + " 个（CurseForge）" + aliasHint(query));
        appendCardsInBatches(container, result,
                this::modCard,
                () -> seq == modRefreshSeq && modResultBox == container
                        && modSourceMode == modeAtStart);
    }

    /** 刷新 CurseForge 模组浏览页（与 refreshModTab 平行）。 */
    private void refreshCfTab() {
        if (modResultBox == null || modSearchField == null) {
            return;
        }
        // 0.9.1：与 refreshModTab / refreshContentTab 同口径——发请求前不再 clear()，
        // 旧卡片留到新结果回来再原子替换；seq / 缓存 / 分批全部复用同一套。
        final long seq = ++modRefreshSeq;
        final long epochAtStart = contentEpoch;   // 0.9.4 r2 A3：切页后旧结果整份丢弃
        final int modeAtStart = modSourceMode;
        String query = modSearchField.getText() == null ? "" : modSearchField.getText().trim();
        List<Long> cats = selectedCfCategories();
        String sort = modSortBox == null ? "下载量" : modSortBox.getValue();
        // 0.9.1：第二段固定 "cf" 专属段，末段再钉一个 "cf-only"，与 refreshModTab 的
        // "mod|query|sort|cats|mode" 双重隔离——后者末段恒为数字 mode，前者恒为 cf-only；
        // 即便用户在搜索框输入含竖线的字符串，两个命名空间也绝不会撞串。
        final String cacheKey = "mod|cf|" + query + "|" + sort + "|" + cats + "|cf-only";
        List<UnifiedMod> cached = modResultCache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            renderCfHits(seq, modeAtStart, query, cached);   // 命中缓存直接秒开，不发请求
            return;
        }
        modResultCount.setText(query.isBlank() ? "正在加载推荐模组（CurseForge）…"
                : "正在搜「" + query + "」（CurseForge）…");
        Thread work = new Thread(() -> {
            try {
                List<CurseForgeClient.Hit> hits =
                        searchCurseForgeAliased(query, sort, cats, 48);
                // 转成 UnifiedMod 才能复用 modResultCache / modCard；映射与 refreshModTab
                // 合并模式里的 CF 分支逐字一致，onOpen 仍指向 openCfDialog。
                List<UnifiedMod> result = new ArrayList<>(hits.size());
                for (CurseForgeClient.Hit h : hits) {
                    result.add(new UnifiedMod("CurseForge", String.valueOf(h.modId()),
                            h.name(), h.summary(), h.downloads(), h.iconUrl(),
                            h.author(), h.categories(), h.dateUpdated(), h.mcVersion(),
                            () -> openCfDialog(h)));
                }
                javafx.application.Platform.runLater(() -> {
                    // 序号 / 数据源 / 容器引用任一不符就整份丢弃，避免请求在途时切源串结果
                    if (seq != modRefreshSeq || modResultBox == null
                            || modSourceMode != modeAtStart) {
                        return;
                    }
                    // 0.9.4 r2 A3：附加 epoch 校验（不替换 seq）——切页令在飞结果作废
                    if (epochAtStart != contentEpoch) return;
                    cacheResult(modResultCache, cacheKey, result);
                    renderCfHits(seq, modeAtStart, query, result);
                });
            } catch (IOException ex) {
                javafx.application.Platform.runLater(() -> {
                    if (seq != modRefreshSeq || modResultBox == null
                            || modSourceMode != modeAtStart) {
                        return;
                    }
                    // 0.9.4 r2 A3：切页后错误回填也一律丢弃
                    if (epochAtStart != contentEpoch) return;
                    modResultCount.setText("加载失败：" + ex.getMessage());
                    modResultBox.getChildren().setAll(
                            modCenterEmpty("CurseForge 加载失败，稍后重试"));
                });
            }
        }, "gbl-cf");
        work.setDaemon(true);
        work.start();
    }

    /** 一张 CurseForge 模组卡片：D3 统一分区渲染，封面固定 64×64。 */
    private Node cfCoverCard(CurseForgeClient.Hit hit) {
        ImageView cover = new ImageView();
        cover.getStyleClass().add("mod-cover");
        cover.setFitWidth(64);
        cover.setFitHeight(64);
        loadCover(cover, hit.iconUrl(), 112);
        return modCardView(cover, specOf(hit), false, () -> openCfDialog(hit));
    }

    /** 打开 CurseForge 详情弹窗：复用 modLayer 壳。 */
    private void openCfDialog(CurseForgeClient.Hit hit) {
        if (modLayer == null) {
            return;
        }
        // 0.8.8 ①：补 resetDetailState()——看过内容/模组弹窗再开 CF 模组弹窗，
        // 清掉上一份选中版本令牌（Kind 已随 InstallCtx 走，不会再把 .jar 分派进内容目录）。
        resetDetailState();
        loadCover(modDetailCover, hit.iconUrl(), 144);
        modDetailTitle.setText(hit.name());
        modDetailSource.setText("CurseForge");
        modDetailMeta.setText("by " + (text(hit.author()).isBlank() ? "未知作者" : hit.author())
                + " · CurseForge");
        modDetailDesc.setText(text(hit.summary()));
        modDetailExtra.setVisible(false);
        modDetailExtra.setManaged(false);
        modDetailTags.getChildren().clear();
        modDetailStats.getChildren().clear();
        modDetailStats.getChildren().add(statLabel("↓ " + humanDownloads(hit.downloads())));
        modDetailLinks.getChildren().clear();
        modDetailDeps.setVisible(false);
        modDetailVersions.getChildren().clear();
        modDetailVersionHead.setText("版本列表 · 正在加载…");
        modDetailStatus.setText("正在加载详情…");
        cancelActiveRipplesBeforeOpenModal();   // 1.0.6
        showOverlayLayer(modLayer, "mod-detail");
        javafx.application.Platform.runLater(this::installFloatScrollBars);
        Thread work = new Thread(() -> {
            try {
                CurseForgeClient.Project p = CurseForgeClient.project(hit.modId());
                List<CurseForgeClient.File> files = CurseForgeClient.files(hit.modId());
                javafx.application.Platform.runLater(() -> populateCfDialog(hit, p, files));
            } catch (IOException ex) {
                javafx.application.Platform.runLater(() ->
                        modDetailStatus.setText("详情加载失败：" + ex.getMessage()));
            }
        }, "gbl-cf-detail");
        work.setDaemon(true);
        work.start();
    }

    /** 填充 CurseForge 详情弹窗（D3：§1 头部 / §2 简介 / §3 链接 / §5 版本 / §6 状态）。 */
    private void populateCfDialog(CurseForgeClient.Hit hit, CurseForgeClient.Project p,
                                  List<CurseForgeClient.File> files) {
        long modId = p.modId();
        loadCover(modDetailCover, p.iconUrl() != null ? p.iconUrl() : null, 144);
        modDetailTitle.setText(p.name());
        modDetailSource.setText("CurseForge");
        modDetailMeta.setText("by " + (text(hit.author()).isBlank() ? "未知作者" : hit.author())
                + " · CurseForge");
        modDetailDesc.setText(text(p.summary()));

        // §1 标签区：分类 tags │ 竖分隔 │ 加载器 tags 分组
        modDetailTags.getChildren().clear();
        List<String> cats = p.categories() == null ? List.of() : p.categories();
        for (String c : cats) {
            modDetailTags.getChildren().add(tagChip(cfCatCn(c), "tag-cat"));
        }
        java.util.LinkedHashSet<String> loaders = new java.util.LinkedHashSet<>();
        for (CurseForgeClient.File f : files) {
            loaders.addAll(loadersFromGameVersions(f.gameVersions()));
        }
        if (!cats.isEmpty() && !loaders.isEmpty()) {
            modDetailTags.getChildren().add(tagSep());
        }
        for (String l : loaders) {
            modDetailTags.getChildren().add(tagChip(modLoaderCn(l), "tag-loader"));
        }
        if (modDetailTags.getChildren().isEmpty()) {
            modDetailTags.getChildren().add(tagChip("未分类", "tag-cat"));
        }

        // §1 统计行：下载量 / 更新时间 / MC 版本
        modDetailStats.getChildren().clear();
        modDetailStats.getChildren().add(statLabel("↓ " + humanDownloads(p.downloads())));
        String rel = relativeDate(hit.dateUpdated());
        if (!rel.isBlank()) {
            modDetailStats.getChildren().add(statLabel("↻ " + rel));
        }
        if (!text(hit.mcVersion()).isBlank()) {
            modDetailStats.getChildren().add(statLabel("MC " + hit.mcVersion()));
        }

        // §2 补充信息：CF 模组没有「包含 N 个模组」语义，留空
        modDetailExtra.setVisible(false);
        modDetailExtra.setManaged(false);

        // §3 相关链接
        modDetailLinks.getChildren().clear();
        if (p.websiteUrl() != null && !p.websiteUrl().isBlank()) {
            Hyperlink hl = new Hyperlink("CurseForge");
            hl.getStyleClass().add("link-btn");
            hl.setOnAction(e -> openExternal(p.websiteUrl()));
            modDetailLinks.getChildren().add(hl);
        }
        // §4 前置依赖：CF 接口不提供，保持隐藏
        modDetailDeps.setVisible(false);

        // §5 版本区：按 MC 版本分组，组头带推荐徽章与版本数
        modDetailVersionHead.setText("版本列表 · 共 " + files.size() + " 个 · 按 Minecraft 版本分组");
        modDetailVersions.getChildren().clear();
        Map<String, List<CurseForgeClient.File>> groups = new LinkedHashMap<>();
        for (CurseForgeClient.File f : files) {
            List<String> games = f.gameVersions() == null ? List.of() : f.gameVersions();
            for (String g : games) {
                if (g == null || g.isBlank()) {
                    continue;
                }
                groups.computeIfAbsent(g, k -> new ArrayList<>()).add(f);
            }
        }
        List<String> keys = new ArrayList<>(groups.keySet());
        final String eg = effectiveGameId();   // 0.8.8 Q5：推荐判定用单一取值口，只算一次
        keys.sort((a, b) -> {
            if (a.equals(eg)) {
                return -1;
            }
            if (b.equals(eg)) {
                return 1;
            }
            return com.mclauncher.AppVersion.compare(b, a);
        });
        for (String g : keys) {
            boolean recommend = g.equals(eg);
            modDetailVersions.getChildren().add(groupHeader(g, recommend, groups.get(g).size()));
            VBox grpBox = new VBox(6);
            for (CurseForgeClient.File f : groups.get(g)) {
                grpBox.getChildren().add(cfVersionRow(modId, f, dialogCover(p, hit)));
            }
            modDetailVersions.getChildren().add(grpBox);
        }
        // §6 底部状态条（固定在卡片底部，不随 §5 滚动）
        modDetailStatus.setText("点「安装」会把这个版本装进游戏");
    }

    /**
     * CurseForge 一行版本：D3 统一渲染器。
     * 相比旧版补齐「稳定/测试/快照徽章」「加载器徽章」「文件大小」「文件名」四项。
     */
    private Node cfVersionRow(long modId, CurseForgeClient.File f, String coverUrl) {
        String type = cfTypeKey(f.releaseType());
        return versionRowView(new VersionRowSpec(
                shortTypeCn(type), "tag-" + type,
                text(f.displayName()),
                loadersFromGameVersions(f.gameVersions()),
                modDate(f.fileDate()), f.fileLength(), text(f.fileName()),
                List.of(), "安装", false,
                btn -> dispatchInstall(new InstallCtx(null, new CfRef(modId, f),
                        false, f.displayName(), null, coverUrl), btn),
                cfDepSummary(f)));
    }

    /**
     * 加载封面：圆角裁切 + 缓存 + 失败占位图。
     *
     * <p><b>0.8.6 P0 修复：</b>原来把圆角半径当构造参数传进四参 {@code Rectangle(w,h,10,10)}，
     * JavaFX 的四参重载是 {@code (x, y, width, height)}，不是「宽高 + 圆角半径」，于是 clip
     * 落在 {@code (w,h)-(w+10,h+10)}，与 ImageView 可见区零交集，所有封面被裁成透明。
     * 这正是 0.8.5（换成 HttpClient 拉字节）与 0.8.6（重试 / host 回退）两次改下载层
     * 都不见效的原因：断点在渲染层，不在取数层。
     *
     * <p>同时补占位图：image 为 null 时 ImageView 的 layoutBounds 是 0，卡片会整块左移，
     * 表现为「连图片区域都看不到」。占位图纯代码生成，不依赖资源文件（jpackage 不会漏拷）。
     */
    private void loadCover(ImageView view, String url, double size) {
        double w = view.getFitWidth() > 0 ? view.getFitWidth() : size;
        double h = view.getFitHeight() > 0 ? view.getFitHeight() : size;
        Rectangle clip = new Rectangle(w, h);
        clip.setArcWidth(10);
        clip.setArcHeight(10);
        view.setClip(clip);

        // 先认领本次加载 + 上占位图：固定占位不塌陷，也防止旧请求回填覆盖新项目
        final String token = url == null || url.isBlank() ? "" : url;
        final String key = "gbl.cover.url";
        view.getProperties().put(key, token);
        view.setImage(coverPlaceholder((int) Math.round(Math.max(w, h))));

        if (token.isEmpty()) {
            System.err.println("[cover] 没有封面 URL，使用占位图");
            return;
        }
        Image cached = coverCache.get(token);
        if (cached != null) {
            view.setImage(cached);
            return;
        }
        // 0.9.4 r2 A2/A3：提交下载前记下 epoch，回填时若已切页 / view 已离场就整份丢弃。
        final long epochAtStart = contentEpoch;
        // 交给 Net 的有界线程池排队（不再每张图起一条线程、更不会因抢不到名额被直接丢弃）
        Net.downloadImageAsync(token, data -> {
            // 0.9.1 P0：WebP 解码从 FX 线程挪到下载回调线程。一批结果 48 张卡，
            // 每张都在 Platform.runLater 里同步 CoverCodecs.decode 会把 FX 线程按死，
            // 用户点什么都没反应。这里只把解好的 Image 送回 FX 线程做校验 + setImage。
            Image decoded = null;
            if (data != null && data.length > 0) {
                try {
                    // 0.9.6：切页 / 离开该内容 tab 之后才回来的封面，解码阶段就直接放弃，
                    // 不白费 CPU 去解一张用户已经看不到了的图（与下面 runLater 里的 epoch 校验互补）。
                    if (epochAtStart != contentEpoch) {
                        return;
                    }
                    // CoverCodecs 处理 Modrinth 的 WebP（JavaFX 21 原生不认）
                    decoded = CoverCodecs.decode(data, size);
                } catch (RuntimeException ex) {
                    System.err.println("[cover] 图片解码异常: " + token + " / " + ex.getMessage());
                }
            }
            final Image img = decoded;
            javafx.application.Platform.runLater(() -> {
                // view 归属校验 / data 判空都在 FX 线程复核：getProperties() 属于场景图对象，
                // 旧请求的回填绝不能盖掉新项目。
                if (!token.equals(view.getProperties().get(key))) {
                    return;   // 这个 view 已经被更新的加载接走了
                }
                if (data == null || data.length == 0) {
                    System.err.println("[cover] 下载失败，保持占位图: " + token);
                    return;
                }
                if (img == null) {
                    System.err.println("[cover] 图片解码失败，保持占位图: " + token);
                    return;
                }
                // 0.9.4 r2 A2/A3：切页期间回填的一律丢弃——epoch 已过期说明发起时的页面
                // 已被换掉，view.getScene()==null 说明节点已被移出场景图；两者任一成立就
                // 不写缓存、不 setImage，避免给已离场的封面白建 Image / 白触发重绘。
                if (epochAtStart != contentEpoch) return;
                if (view.getScene() == null) return;
                coverCache.put(token, img);
                view.setImage(img);
            });
        });
    }

    /**
     * 纯代码生成的灰底占位封面（按边长缓存），不依赖任何资源文件。
     * 画一个方框轮廓 + 中心小方块，表达「这里本该有张图」。
     */
    private static Image coverPlaceholder(int px) {
        int n = Math.max(8, px);
        return COVER_PLACEHOLDERS.computeIfAbsent(n, side -> {
            WritableImage img = new WritableImage(side, side);
            PixelWriter pw = img.getPixelWriter();
            final int bg = 0xFFEDF1F6;
            final int fg = 0xFFC3CCDA;
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) {
                    pw.setArgb(x, y, bg);
                }
            }
            int m = Math.max(2, side / 5);
            int line = Math.max(1, side / 32);
            for (int y = m; y < side - m; y++) {
                for (int x = m; x < side - m; x++) {
                    boolean border = x < m + line || y < m + line
                            || x >= side - m - line || y >= side - m - line;
                    if (border) {
                        pw.setArgb(x, y, fg);
                    }
                }
            }
            int c = side / 2;
            int r = Math.max(2, side / 10);
            for (int y = c - r; y <= c + r; y++) {
                for (int x = c - r; x <= c + r; x++) {
                    if (x >= 0 && y >= 0 && x < side && y < side) {
                        pw.setArgb(x, y, fg);
                    }
                }
            }
            return img;
        });
    }

    /** 已知 projectId → 后台拉项目图标再填封面（已装模组没有现成 iconUrl 时用）。 */
    private void loadCoverAsync(ImageView view, String projectId, double size) {
        Thread t = new Thread(() -> {
            try {
                ModrinthClient.Project p = ModrinthClient.project(projectId);
                if (p != null && p.iconUrl() != null) {
                    javafx.application.Platform.runLater(() -> loadCover(view, p.iconUrl(), size));
                }
            } catch (IOException ex) {
                // 拉不到图标就留空封面，不影响功能
            }
        }, "gbl-cover");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 已装模组的封面：按来源尽力拉图（0.8.5 补全——之前 CF 安装 / 手动放进去的 mod 都没有图）。
     * <ul>
     *   <li>projectId 是纯数字 → CurseForge 项目图标；</li>
     *   <li>projectId 是字母数字（Modrinth）→ 按 projectId 拉图标；</li>
     *   <li>没有任何索引 → 用 mod 的 modId / 显示名去 Modrinth 搜一个封面（尽力而为）。</li>
     * </ul>
     */
    private void loadInstalledCover(ImageView view, ModStore.Mod mod, String projectId) {
        if (projectId != null && projectId.matches("\\d+")) {
            // CurseForge：用数字 modId 拉项目图标
            Thread t = new Thread(() -> {
                try {
                    CurseForgeClient.Project p = CurseForgeClient.project(Long.parseLong(projectId));
                    if (p != null && p.iconUrl() != null) {
                        javafx.application.Platform.runLater(() -> loadCover(view, p.iconUrl(), 80));
                    }
                } catch (IOException ex) {
                    // 拉不到就留空封面
                }
            }, "gbl-cover");
            t.setDaemon(true);
            t.start();
            return;
        }
        if (projectId != null) {
            // Modrinth：按 projectId 拉图标（沿用原逻辑）
            loadCoverAsync(view, projectId, 80);
            return;
        }
        // 未知来源：按 modId / 显示名去 Modrinth 搜一个封面（尽力而为，搜不到就留空）
        String q = (mod.modId() != null && !mod.modId().isBlank())
                ? mod.modId() : mod.displayName();
        if (q == null || q.isBlank()) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                List<ModrinthClient.Hit> hits =
                        ModrinthClient.search(q, null, null, 1, "下载量", java.util.List.of());
                if (!hits.isEmpty() && hits.get(0).iconUrl() != null) {
                    String url = hits.get(0).iconUrl();
                    javafx.application.Platform.runLater(() -> loadCover(view, url, 80));
                }
            } catch (IOException ex) {
                // 搜不到就留空封面
            }
        }, "gbl-cover");
        t.setDaemon(true);
        t.start();
    }

    /** 分类 slug → 中文（认不到就原样显示）。 */
    private static String modCatCn(String cat) {
        if (cat == null) {
            return "";
        }
        return switch (cat) {
            case "optimization" -> "优化";
            case "utility" -> "实用";
            case "adventure" -> "冒险";
            case "decoration" -> "装饰";
            case "library" -> "库";
            case "management" -> "管理";
            case "worldgen" -> "世界生成";
            case "storage" -> "存储";
            case "food" -> "食物";
            case "equipment" -> "装备";
            case "magic" -> "魔法";
            case "technology" -> "科技";
            case "social" -> "社交";
            case "economy" -> "经济";
            case "furniture" -> "家具";
            case "gameplay" -> "玩法";
            case "audio" -> "音频";
            case "cosmetic" -> "外观";
            case "transportation" -> "交通";
            case "cursed" -> "诅咒";
            case "misc" -> "杂项";
            case "fabric" -> "Fabric";
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            case "quilt" -> "Quilt";
            case "liteloader" -> "LiteLoader";
            default -> cat;
        };
    }

    /** 加载器 slug → 中文标签（认不到就原样显示）。 */
    private static String modLoaderCn(String l) {
        if (l == null) {
            return "";
        }
        return switch (l) {
            case "fabric" -> "Fabric";
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            case "quilt" -> "Quilt";
            case "liteloader" -> "LiteLoader";
            case "rift" -> "Rift";
            default -> l;
        };
    }

    /** 版本类型 → 中文标签 + 样式类后缀。 */
    private static String modTypeCn(String t) {
        if (t == null) {
            return "其他";
        }
        return switch (t) {
            case "release" -> "稳定版本";
            case "beta" -> "测试版本";
            case "alpha" -> "快照版本";
            default -> t;
        };
    }

    /** 把 Modrinth 的 ISO 时间压成「2026-08-29 04:55」。 */
    private static String modDate(String iso) {
        if (iso == null || iso.length() < 16) {
            return "";
        }
        // 2026-08-29T04:55:02.000Z
        return iso.substring(0, 10) + " " + iso.substring(11, 16);
    }

    /** 打开外部链接（Modrinth / 项目官网等）。 */
    private void openExternal(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
        } catch (Exception ex) {
            toast("打不开链接：" + ex.getMessage());
        }
    }

    /**
     * 刷新模组页：不依赖搜索框——没输入就拉「推荐模组」（按下载量排，跨全部版本），
     * 输入了就按关键词搜（同样跨全部版本，HMCL 式全局浏览）。结果以带封面的卡片网格展示，
     * 点卡片打开详情弹窗。
     */
    /**
     * 刷新模组浏览页。
     * 模式 1（仅 CurseForge）→ 走 {@link #refreshCfTab()}；
     * 模式 0（仅 Modrinth）/ 模式 2（两者合并）→ 拉 Modrinth，合并模式再追加 CurseForge 结果。
     */
    private void refreshModTab() {
        // 0.9.1：序号放在最前面，连模式 1（转 CurseForge）也让在飞的旧批次失效。
        final long seq = ++modRefreshSeq;
        final long epochAtStart = contentEpoch;   // 0.9.4 r2 A3：切页后旧结果整份丢弃
        final int modeAtStart = modSourceMode;
        if (modSourceMode == 1) {
            refreshCfTab();
            return;
        }
        if (modResultBox == null || modSearchField == null) {
            return;   // 这一页还没建出来
        }
        // 0.9.1：不再在发请求前 clear()，旧卡片留到新结果回来再原子替换，避免空白「卡死」观感。
        String query = modSearchField.getText() == null ? "" : modSearchField.getText().trim();
        List<String> cats = selectedModCategories();
        String sort = modSortBox == null ? "下载量" : modSortBox.getValue();
        final boolean both = modSourceMode == 2;
        // 查询条件做 key：query | sort | 分类 | 数据源模式（context 恒为 "mod"）
        final String cacheKey = "mod|" + query + "|" + sort + "|" + cats + "|" + modeAtStart;
        List<UnifiedMod> cached = modResultCache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            renderModHits(seq, modeAtStart, query, both, cached);   // 命中缓存直接秒开，不发请求
            return;
        }
        modResultCount.setText(query.isBlank() ? "正在加载推荐模组…" : "正在搜「" + query + "」…");
        Thread work = new Thread(() -> {
            try {
                // 合并模式：先让 CurseForge 在后台飞，与 Modrinth 主请求并行（0.9.0 提速第五轮）
                final List<CurseForgeClient.Hit>[] cfBox = new List[1];
                final IOException[] cfErr = {null};
                Thread cfThread = null;
                if (both) {
                    cfThread = new Thread(() -> {
                        try {
                            cfBox[0] = searchCurseForgeAliased(query, sort, null, 48);
                        } catch (IOException ex) {
                            cfErr[0] = ex;
                        }
                    }, "gbl-cf-bg");
                    cfThread.setDaemon(true);
                    cfThread.start();
                }
                List<ModrinthClient.Hit> mrHits =
                        searchModrinthAliased(query, 48, sort, cats, "mod");
                List<UnifiedMod> merged = new ArrayList<>();
                for (ModrinthClient.Hit h : mrHits) {
                    merged.add(new UnifiedMod("Modrinth", h.projectId(), h.title(),
                            h.description(), h.downloads(), h.iconUrl(), h.author(),
                            h.categories(), h.dateUpdated(), h.mcVersion(),
                            () -> openModDialog(h)));
                }
                if (both) {
                    // 合并模式：等后台 CurseForge 收尾，失败不阻断 Modrinth 结果
                    try {
                        if (cfThread != null) {
                            cfThread.join(15_000);   // 工作线程里的等待，保留不动
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    if (cfBox[0] != null) {
                        for (CurseForgeClient.Hit h : cfBox[0]) {
                            merged.add(new UnifiedMod("CurseForge", String.valueOf(h.modId()),
                                    h.name(), h.summary(), h.downloads(), h.iconUrl(),
                                    h.author(), h.categories(), h.dateUpdated(), h.mcVersion(),
                                    () -> openCfDialog(h)));
                        }
                    } else if (cfErr[0] != null) {
                        System.err.println("[mod] 合并搜索 CurseForge 失败（不阻断 Modrinth）: "
                                + cfErr[0].getMessage());
                    }
                }
                List<UnifiedMod> result = dedupModsById(merged);   // r4：两源按项目 id 去重合并
                javafx.application.Platform.runLater(() -> {
                    // 0.9.1：序号 / 数据源对不上就丢弃，避免快速切 tab / 连续输入时旧结果覆盖新结果
                    if (seq != modRefreshSeq || modResultBox == null
                            || modSourceMode != modeAtStart) {
                        return;
                    }
                    // 0.9.4 r2 A3：附加 epoch 校验（不替换 seq）——切页令在飞结果作废
                    if (epochAtStart != contentEpoch) return;
                    cacheResult(modResultCache, cacheKey, result);
                    renderModHits(seq, modeAtStart, query, both, result);
                });
            } catch (IOException ex) {
                javafx.application.Platform.runLater(() -> {
                    if (seq != modRefreshSeq || modResultBox == null
                            || modSourceMode != modeAtStart) {
                        return;
                    }
                    // 0.9.4 r2 A3：切页后错误回填也一律丢弃
                    if (epochAtStart != contentEpoch) return;
                    modResultCount.setText("加载失败：" + ex.getMessage());
                    modResultBox.getChildren().setAll(
                            modCenterEmpty("网络抽风了，稍后重试"));
                });
            }
        }, "gbl-mod");
        work.setDaemon(true);
        work.start();
    }

    /** 0.9.1：把模组结果渲染到结果栏（缓存命中与网络回填共用同一套校验 + 分批逻辑）。 */
    private void renderModHits(long seq, int modeAtStart, String query, boolean both,
                               List<UnifiedMod> result) {
        FlowPane container = modResultBox;
        if (seq != modRefreshSeq || container == null || modSourceMode != modeAtStart) {
            return;   // 结果过期 / 数据源已切 / 页已重建，整份丢弃
        }
        if (result.isEmpty()) {
            modResultCount.setText(query.isBlank() ? "暂时没有推荐模组，稍后重试"
                    : "没搜到，换个关键词");
            container.getChildren().setAll(modCenterEmpty(
                    query.isBlank() ? "暂时没有推荐模组" : "没有匹配的模组"));
            return;
        }
        modResultCount.setText((query.isBlank() ? "推荐模组" : "搜索结果")
                + " · " + result.size() + " 个"
                + (both ? "（Modrinth + CurseForge 合并）" : "")
                + aliasHint(query));
        appendCardsInBatches(container, result,
                this::modCard,
                () -> seq == modRefreshSeq && modResultBox == container
                        && modSourceMode == modeAtStart);
    }

    /**
     * 合并搜索用的统一卡片数据。0.8.6 D3 起补齐作者 / 分类 / 更新时间 / MC 版本 / 来源，
     * 与 ModrinthClient.Hit、CurseForgeClient.Hit 的字段口径完全一致。
     */
    private record UnifiedMod(String source, String id, String title, String summary,
                              long downloads, String iconUrl, String author,
                              List<String> categories, String dateUpdated, String mcVersion,
                              Runnable onOpen) {
    }

    /** 统一的模组卡片（Modrinth / CurseForge 通用）：交给 D3 统一渲染器。 */
    private Node modCard(UnifiedMod m) {
        ImageView cover = new ImageView();
        cover.getStyleClass().add("mod-cover");
        cover.setFitWidth(64);
        cover.setFitHeight(64);
        loadCover(cover, m.iconUrl(), 112);
        return modCardView(cover, specOf(m), false, m.onOpen());
    }

    /** 一张 Modrinth 模组卡片：交给 D3 统一渲染器。 */
    private Node modCoverCard(ModrinthClient.Hit hit) {
        ImageView cover = new ImageView();
        cover.getStyleClass().add("mod-cover");
        cover.setFitWidth(64);
        cover.setFitHeight(64);
        loadCover(cover, hit.iconUrl(), 112);
        return modCardView(cover, specOf(hit), false, () -> openModDialog(hit));
    }

    /** 一行已装 mod（游戏管理页）：D3 分区 —— R1 头部 / R2 分隔线 / R3 统计行。 */
    private Node modRow(ModStore.Mod mod, String projectId) {
        VBox row = new VBox(0);
        row.getStyleClass().addAll("card", "mod-row");
        row.setPadding(new Insets(10, 14, 10, 14));

        // R1 头部：40×40 封面 | 名称 + 加载器徽章 | 启停开关 | 卸载
        HBox head = new HBox(12);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setMinHeight(40);
        ImageView cover = new ImageView();
        cover.getStyleClass().add("mod-cover");
        cover.setFitWidth(40);
        cover.setFitHeight(40);
        loadInstalledCover(cover, mod, projectId);
        String loader = mod.loader() == null ? "加载器未知" : mod.loader();
        VBox info = new VBox(3);
        Label name = new Label(mod.displayName());
        name.getStyleClass().add("card-title");
        // 0.9.1 r3：版本胶囊紧挨在「fabric」加载器胶囊的右边，两枚同款胶囊并排，一眼可读。
        // 尺寸由 CSS 统一，CENTER_LEFT 再保证垂直居中，不会出现一大一小 / 上下错位。
        // 0.9.4：显示该模组安装时所选的适配 MC 版本，不再随「当前游戏」切换。
        HBox chips = new HBox(6,
                tagChip(loader, "tag-loader-sm"),
                versionTag(AdaptedMc.resolve(stripDisabled(mod.fileName()), null, null, null)));
        chips.setAlignment(Pos.CENTER_LEFT);
        info.getChildren().addAll(name, chips);

        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);
        // 用基名（去 .disabled）而不是 mod.file()：开关/卸载每次都按基名从磁盘重新定位，
        // 避免持有改名前的旧路径（0.8.5 修「反复切换后启用态丢失」）。
        String baseName = ModStore.stripDisabled(mod.fileName());
        ToggleSwitch enable = new ToggleSwitch(mod.enabled());
        final boolean[] rollingBack = {false};
        enable.selectedProperty().addListener((obs, was, now) -> {
            if (rollingBack[0]) {
                return;
            }
            setModEnabled(baseName, now, enable, rollingBack);
        });
        Button remove = new Button("卸载");
        remove.getStyleClass().add("btn-danger");
        remove.setMinHeight(32);
        remove.setOnAction(e -> uninstallMod(baseName, detachRow(row)));
        head.getChildren().addAll(cover, info, gap, enable, remove);

        // R2 分隔线
        Region sep = sepLine();

        // R3 统计行：版本 / 文件名 / 大小 / 加载器 / 位置 / 启用态
        String subdir = mod.subdir() == null || mod.subdir().isBlank() ? "" : mod.subdir() + "/";
        HBox stats = new HBox(10);
        stats.setAlignment(Pos.CENTER_LEFT);
        stats.getStyleClass().add("mod-card-stats");
        // 0.9.1 r3：版本已挪到头部、与加载器胶囊并排，统计行不再重复一遍
        stats.getChildren().addAll(
                statLabel("文件 " + text(mod.fileName())),
                statLabel("大小 " + LibFetcher.human(mod.sizeBytes())),
                statLabel("加载器 " + loader),
                statLabel("位置 mods/" + subdir),
                tagChip(mod.enabled() ? "已启用" : "已禁用",
                        mod.enabled() ? "tag-release" : "tag-other"));
        row.getChildren().addAll(head, sep, stats);
        return row;
    }

    /**
     * 启停一个 mod：改名大法（.disabled 后缀）。
     * 按基名从磁盘重新定位当前文件，避免持有改名前的旧路径（0.8.5 修启用态丢失）；
     * 成功/失败后都刷新列表，让开关与磁盘状态严格一致。
     */
    private void setModEnabled(String baseName, boolean on,
                               ToggleSwitch toggle, boolean[] rollingBack) {
        Path file = ModStore.resolve(baseName);
        if (file == null) {
            toast("找不到「" + baseName + "」了，可能已经被移除");
            Platform.runLater(() -> rollbackToggle(toggle, !on, rollingBack));
            scheduleGameManageRefresh();
            return;
        }
        try {
            ModStore.setEnabled(file, on);
            toast((on ? "已启用 " : "已禁用 ") + baseName);
            // 1.2.8：这里绝不重建——200ms 滑动还没播完，重建会把开关摘出场景树
            scheduleGameManageRefresh();
        } catch (IOException ex) {
            toast("启停失败：" + ex.getMessage());
            Platform.runLater(() -> rollbackToggle(toggle, !on, rollingBack));   // 磁盘没改成，开关拨回原值
            scheduleGameManageRefresh();
        }
    }

    /** 卸载一个 mod：进回收站 + 摘索引。失败（被占用等）说人话。 */
    private void uninstallMod(String baseName, DetachedRow detached) {
        Path file = ModStore.resolve(baseName);
        if (file == null) {
            toast("找不到「" + baseName + "」了，可能已经被移除");
            scheduleGameManageRefresh();
            return;
        }
        final String fileName = file.getFileName().toString();
        final String key = effectiveGameId();   // 0.8.8 Q3：卸载目标与安装/读取统一取值口
        runDiskTask(() -> {
            if (!ModStore.uninstall(file)) {
                return false;
            }
            if (key != null) {
                try {
                    ModIndex.remove(key, fileName);
                } catch (IOException ex) {
                    // 索引少摘一条不影响卸载本身，P1 更新检测对不上的条目自然跳过
                    System.err.println("[mod] 摘索引失败（不影响卸载）: " + ex.getMessage());
                }
            }
            return true;
        }, "卸载模组 " + fileName,
                () -> {
                    toast("已卸载 " + fileName + "（在回收站里还能捡回来）");
                    // 0.9.8：不再连着 refreshGameManage() + refreshModTab() 各全量重建一遍。
                    // 「模组中心」列的是在线项目，本地卸载并不改变它，刷了纯属白卡一下。
                    scheduleGameManageRefresh();
                },
                () -> {
                    restoreDetachedRow(detached);
                    toast("「" + fileName + "」移不进回收站（可能被游戏或别的程序占着）");
                });
    }

    /** 触发刷新模组页（搜索框回车 / 按钮 / 分类 / 排序都走这）。 */
    private void searchMods() {
        refreshModTab();
    }

    // ---------------------------------------------------------------- 模组详情弹窗（HMCL 风格）

    /**
     * 构建模组 / 整合包详情弹窗（D3 分区版）：遮罩 + 760 宽居中卡片。
     * §1 头部（96×96 封面 / 标题 / 作者·来源 / 标签 / 统计）
     * ── 分隔线 ── §2 简介 / §3 相关链接 / §4 前置依赖
     * ── 分隔线 ── §5 版本区（唯一可滚动） ── 分隔线 ── §6 固定底部状态条。
     */
    private Node buildModDetailDialog() {
        VBox card = new VBox(0);
        card.getStyleClass().addAll("card", "mod-detail-card");
        card.setPadding(new Insets(18, 20, 16, 20));
        // 1.3.1 修复轮：窄窗口下定宽卡不溢出（内部 §5/§4 已有 ScrollPane 管高度）
        card.setMinWidth(320);
        card.setPrefWidth(760);
        card.setMaxWidth(800);
        if (shell != null) {
            card.maxWidthProperty().bind(shell.widthProperty().subtract(48));
        }
        // 1.3.1 修复轮 P0：窄窗口纵向溢出——高上限跟随场景，头部/底部条常驻
        card.setMinHeight(240);
        if (shell != null) {
            card.maxHeightProperty().bind(shell.heightProperty().subtract(48));
        }

        // §1 头部：96×96 封面 | 标题 + 来源徽章 / by 作者 / 标签 / 统计 | close 固定右上
        HBox head = new HBox(14);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setMinHeight(104);
        modDetailCover = new ImageView();
        modDetailCover.getStyleClass().add("mod-detail-cover");
        modDetailCover.setFitWidth(96);
        modDetailCover.setFitHeight(96);
        VBox info = new VBox(6);
        HBox titleRow = new HBox(8);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        modDetailTitle = new Label();
        modDetailTitle.getStyleClass().add("dlg-title");
        modDetailTitle.setTextOverrun(OverrunStyle.ELLIPSIS);
        modDetailTitle.setMaxWidth(500);
        modDetailSource = tagChip("来源", "tag-source");
        titleRow.getChildren().addAll(modDetailTitle, modDetailSource);
        modDetailMeta = new Label();
        modDetailMeta.getStyleClass().addAll("page-desc", "mod-detail-meta");
        modDetailTags = new FlowPane(6, 4);
        modDetailTags.getStyleClass().add("mod-tags");
        modDetailStats = new HBox(12);
        modDetailStats.setAlignment(Pos.CENTER_LEFT);
        modDetailStats.getStyleClass().add("mod-detail-stats");
        info.getChildren().addAll(titleRow, modDetailMeta, modDetailTags, modDetailStats);
        Region hgap = filler();
        HBox.setHgrow(hgap, Priority.ALWAYS);
        Pane close = winBtn("dlg-close", Icons.strokeScaled(Icons.WIN_CLOSE, WIN_ICON, 1.6),
                BTN_HOVER_BG, STROKE_HOVER);
        close.setOnMouseClicked(e -> closeModDialog());
        head.getChildren().addAll(modDetailCover, info, hgap, close);

        // §2 简介区（整合包在此追加「包含 N 个模组」摘要）
        VBox intro = new VBox(6);
        intro.getStyleClass().add("mod-section");
        modDetailDesc = new Label();
        modDetailDesc.getStyleClass().add("mod-detail-desc");
        modDetailDesc.setWrapText(true);
        modDetailExtra = new Label();
        modDetailExtra.getStyleClass().add("mod-detail-extra");
        modDetailExtra.setWrapText(true);
        modDetailExtra.setVisible(false);
        modDetailExtra.setManaged(false);
        intro.getChildren().addAll(sectionTitle("简介"), modDetailDesc, modDetailExtra);

        // §3 相关链接区（保留原有 Hyperlink 逻辑）
        VBox linksSection = new VBox(6);
        linksSection.getStyleClass().add("mod-section");
        modDetailLinks = new HBox(8);
        modDetailLinks.getStyleClass().add("mod-links");
        linksSection.getChildren().addAll(sectionTitle("相关链接"), modDetailLinks);

        // §4 前置依赖区（r10：HMCL 风格卡片默认直显，标题只作醒目分区标题、不再可点折叠）
        modDepsTitle = sectionTitle("前置依赖");
        modDepsTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        modDepsTitle.setVisible(false);
        modDepsTitle.setManaged(false);
        modDetailDeps = new VBox(8);
        modDetailDeps.getStyleClass().add("mod-deps");
        modDetailDeps.setVisible(false);
        modDetailDeps.managedProperty().bind(modDetailDeps.visibleProperty());
        // r10：75+ 张 dep-card 在这个限高容器里滚动，详情弹窗本身不被撑爆
        modDepsScroll = new ScrollPane(modDetailDeps);
        modDepsScroll.getStyleClass().add("content-scroll");
        modDepsScroll.setFitToWidth(true);
        modDepsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        modDepsScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        modDepsScroll.setMaxHeight(460);
        // 显隐完全跟随 content：无依赖时不留占位
        modDepsScroll.visibleProperty().bind(modDetailDeps.visibleProperty());
        modDepsScroll.managedProperty().bind(modDetailDeps.visibleProperty());

        // §5 版本区（唯一可滚动区域）
        VBox versionSection = new VBox(6);
        versionSection.getStyleClass().add("mod-section");
        modDetailVersionHead = new Label("版本列表");
        modDetailVersionHead.getStyleClass().addAll("mod-section-title", "mod-version-head");
        modDetailVersions = new VBox(8);
        ScrollPane scroll = new ScrollPane(modDetailVersions);
        scroll.getStyleClass().add("content-scroll");
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(300);
        scroll.setMinHeight(220);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        versionSection.getChildren().addAll(modDetailVersionHead, scroll);

        // §6 底部状态条：固定在卡片底部，不随 §5 滚动
        HBox statusBar = new HBox(10);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.getStyleClass().add("mod-detail-status-bar");
        modDetailStatus = new Label();
        modDetailStatus.getStyleClass().add("page-desc");
        modDetailStatus.setWrapText(true);
        modDetailStatus.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(modDetailStatus, Priority.ALWAYS);
        Button closeBtn = new Button("关闭");
        closeBtn.getStyleClass().add("btn-ghost");
        closeBtn.setMinHeight(30);
        closeBtn.setOnAction(e -> closeModDialog());
        statusBar.getChildren().addAll(modDetailStatus, closeBtn);

        // 1.3.1 修复轮 P0：§2~§5 收进 fitToWidth 滚动体；§1 头部与 §6 状态/关闭条不滚动，
        // 窄窗口下关闭按钮始终留在 scene 内可达。内层 ScrollPane 保留原上限，宽窗口观感不变。
        VBox detailBody = new VBox(0);
        detailBody.getStyleClass().add("mod-detail-body");
        detailBody.getChildren().addAll(sepLine(), intro, linksSection, modDepsTitle,
                modDepsScroll, sepLine(), versionSection);
        ScrollPane bodyScroll = new ScrollPane(detailBody);
        bodyScroll.getStyleClass().add("content-scroll");
        bodyScroll.setFitToWidth(true);
        bodyScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        bodyScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        bodyScroll.setMinHeight(0);
        VBox.setVgrow(bodyScroll, Priority.ALWAYS);
        card.getChildren().addAll(head, bodyScroll, sepLine(), statusBar);

        Region backdrop = new Region();
        backdrop.getStyleClass().add("dlg-backdrop");
        backdrop.setOnMouseClicked(e -> closeModDialog());
        VBox shellBox = new VBox(card);
        shellBox.setAlignment(Pos.CENTER);
        shellBox.setFillWidth(false);
        StackPane layer = new StackPane(backdrop, shellBox);
        layer.getStyleClass().add("dlg-layer");
        layer.setVisible(false);
        layer.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                closeModDialog();
            }
        });
        modLayer = layer;
        return layer;
    }

    /** 打开模组详情弹窗：先填骨架 + 封面，再后台拉项目详情与全部版本。 */
    private void openModDialog(ModrinthClient.Hit hit) {
        if (modLayer == null) {
            return;
        }
        // 0.8.8：打开模组弹窗先重置状态机（清选中版本令牌）。
        // 落盘分派不看全局开关，模组/整合包行的 InstallCtx.kind 恒为 null（走 mods/）。
        resetDetailState();
        loadCover(modDetailCover, hit.iconUrl(), 144);
        modDetailTitle.setText(hit.title());
        modDetailSource.setText("Modrinth");
        modDetailMeta.setText("by " + (text(hit.author()).isBlank() ? "未知作者" : hit.author())
                + " · Modrinth");
        modDetailDesc.setText(text(hit.description()));
        modDetailExtra.setVisible(false);
        modDetailExtra.setManaged(false);
        modDetailTags.getChildren().clear();
        modDetailStats.getChildren().clear();
        modDetailStats.getChildren().add(statLabel("↓ " + humanDownloads(hit.downloads())));
        modDetailLinks.getChildren().clear();
        modDetailDeps.setVisible(false);
        modDetailVersions.getChildren().clear();
        modDetailVersionHead.setText("版本列表 · 正在加载…");
        modDetailStatus.setText("正在加载详情…");
        cancelActiveRipplesBeforeOpenModal();   // 1.0.6
        showOverlayLayer(modLayer, "mod-detail");
        javafx.application.Platform.runLater(this::installFloatScrollBars);
        Thread work = new Thread(() -> {
            try {
                ModrinthClient.Project p = ModrinthClient.project(hit.projectId());
                List<ModrinthClient.Version> vers = ModrinthClient.allVersions(hit.projectId());
                javafx.application.Platform.runLater(() -> populateModDialog(hit, p, vers));
            } catch (IOException ex) {
                javafx.application.Platform.runLater(() ->
                        modDetailStatus.setText("详情加载失败：" + ex.getMessage()));
            }
        }, "gbl-mod-detail");
        work.setDaemon(true);
        work.start();
    }

    /** 填充详情弹窗（D3 分区）：§1 头部 / §2 简介 / §3 链接 / §4 依赖 / §5 版本 / §6 状态。 */
    private void populateModDialog(ModrinthClient.Hit hit, ModrinthClient.Project p,
                                   List<ModrinthClient.Version> vers) {
        loadCover(modDetailCover, p.iconUrl() != null ? p.iconUrl() : null, 144);
        modDetailTitle.setText(p.title());
        modDetailSource.setText("Modrinth");
        modDetailMeta.setText("by " + (text(hit.author()).isBlank() ? "未知作者" : hit.author())
                + " · Modrinth");
        modDetailDesc.setText(text(p.description()));

        // §1 标签区：分类 tags │ 竖分隔 │ 加载器 tags 分组
        modDetailTags.getChildren().clear();
        List<String> cats = p.categories() == null ? List.of() : p.categories();
        for (String c : cats) {
            String cn = catLabel(c);
            modDetailTags.getChildren().add(tagChip(cn.isEmpty() ? c : cn, "tag-cat"));
        }
        java.util.LinkedHashSet<String> loaders = new java.util.LinkedHashSet<>();
        for (ModrinthClient.Version v : vers) {
            if (v.loaders() != null) {
                loaders.addAll(v.loaders());
            }
        }
        if (!cats.isEmpty() && !loaders.isEmpty()) {
            modDetailTags.getChildren().add(tagSep());
        }
        for (String l : loaders) {
            modDetailTags.getChildren().add(tagChip(modLoaderCn(l), "tag-loader"));
        }
        if (modDetailTags.getChildren().isEmpty()) {
            modDetailTags.getChildren().add(tagChip("未分类", "tag-cat"));
        }

        // §1 统计行
        modDetailStats.getChildren().clear();
        modDetailStats.getChildren().add(statLabel("↓ " + humanDownloads(p.downloads())));
        String rel = relativeDate(hit.dateUpdated());
        if (!rel.isBlank()) {
            modDetailStats.getChildren().add(statLabel("↻ " + rel));
        }
        if (!text(hit.mcVersion()).isBlank()) {
            modDetailStats.getChildren().add(statLabel("MC " + hit.mcVersion()));
        }

        // §2 补充信息：模组详情不显示「包含 N 个模组」
        modDetailExtra.setVisible(false);
        modDetailExtra.setManaged(false);

        // §3 相关链接（保留原有 Hyperlink 逻辑）
        modDetailLinks.getChildren().clear();
        String modrinthUrl = "https://modrinth.com/mod/" + (p.slug() != null ? p.slug() : p.id());
        Hyperlink mrl = new Hyperlink("Modrinth");
        mrl.getStyleClass().add("link-btn");
        mrl.setOnAction(e -> openExternal(modrinthUrl));
        modDetailLinks.getChildren().add(mrl);
        if (p.links() != null) {
            for (Map.Entry<String, String> e : p.links().entrySet()) {
                String key = e.getKey();
                if (key.equals("issues") || key.equals("source") || key.equals("wiki")
                        || key.equals("website") || key.equals("discord")) {
                    Hyperlink hl = new Hyperlink(modCatLinkLabel(key));
                    hl.getStyleClass().add("link-btn");
                    hl.setOnAction(ev -> openExternal(e.getValue()));
                    modDetailLinks.getChildren().add(hl);
                }
            }
        }

        // §4 前置依赖板块（0.8.7 A-2：版本级依赖 + 三分类，随选中版本联动）
        // 先隐藏；下面版本行渲染完默认选中第一个版本再刷新，未选版本时不残留任何内容。
        modDetailDeps.setVisible(false);

        // §5 版本区：按 MC 版本分组，组头带推荐徽章与版本数；版本行可点选联动 §4
        boolean installed = isModInstalled(p.id());
        modDetailVersionHead.setText("版本列表 · 共 " + vers.size() + " 个 · 按 Minecraft 版本分组"
                + (installed ? " · 当前游戏已安装" : ""));
        modDetailVersions.getChildren().clear();
        Map<String, List<ModrinthClient.Version>> groups = new LinkedHashMap<>();
        for (ModrinthClient.Version v : vers) {
            List<String> games = v.gameVersions() == null ? List.of() : v.gameVersions();
            for (String g : games) {
                if (g == null || g.isBlank()) {
                    continue;
                }
                groups.computeIfAbsent(g, k -> new ArrayList<>()).add(v);
            }
        }
        List<String> keys = new ArrayList<>(groups.keySet());
        final String eg = effectiveGameId();   // 0.8.8 Q5：推荐判定用单一取值口，只算一次
        keys.sort((a, b) -> {
            if (a.equals(eg)) {
                return -1;
            }
            if (b.equals(eg)) {
                return 1;
            }
            return com.mclauncher.AppVersion.compare(b, a);
        });
        final List<Node> versionRows = new ArrayList<>();
        final List<ModrinthClient.Version> rowVersions = new ArrayList<>();
        for (String g : keys) {
            boolean recommend = g.equals(eg);
            modDetailVersions.getChildren().add(groupHeader(g, recommend, groups.get(g).size()));
            VBox grpBox = new VBox(6);
            for (ModrinthClient.Version v : groups.get(g)) {
                Node row = versionRow(v, false, installed, dialogCover(p, hit));
                final ModrinthClient.Version fv = v;
                row.setCursor(Cursor.HAND);
                row.setOnMouseClicked(e -> selectModVersion(fv, row));
                grpBox.getChildren().add(row);
                versionRows.add(row);
                rowVersions.add(v);
            }
            modDetailVersions.getChildren().add(grpBox);
        }
        // 默认选中第一个版本：打开弹窗即有依赖提示；切换版本时 selectModVersion 会整份重建依赖区。
        if (!versionRows.isEmpty()) {
            selectModVersion(rowVersions.get(0), versionRows.get(0));
        }
        // §6 底部状态条
        modDetailStatus.setText("");
        // 1.0.1：版本列表回填后滚动条节点才生成，此时补装浮动皮肤（幂等），
        // 否则详情弹窗两条滚动条走原生皮肤 + CSS min 会被撑到 48×48 越界。
        javafx.application.Platform.runLater(this::installFloatScrollBars);
    }

    /**
     * 某个 Modrinth 项目是否已装（0.8.8 Q3）：以 {@link ModStore#scan()} 磁盘文件为唯一真源。
     * {@link ModIndex#entries(String)} 只把 projectId 反查成 filename 做对账辅助——
     * 索引有条目但磁盘无该文件 → 未装；索引无条目也不直接判未装（再按 jar 的 modId 兜底找磁盘）。
     * 弹窗「✓ 已安装」与游戏管理页因此同真源，消除 B1「两张截图自相矛盾」。
     */
    private boolean isModInstalled(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return false;
        }
        List<ModStore.Mod> mods = ModStore.scan();   // 磁盘唯一真源
        if (mods.isEmpty()) {
            return false;
        }
        java.util.Set<String> filenames = new java.util.HashSet<>();
        String game = effectiveGameId();
        if (game != null) {
            for (ModIndex.Entry e : ModIndex.entries(game)) {
                if (projectId.equals(e.projectId()) && e.filename() != null) {
                    filenames.add(e.filename());
                }
            }
        }
        for (ModStore.Mod m : mods) {
            if (m.fileName() == null) {
                continue;
            }
            if (filenames.contains(m.fileName())) {
                return true;   // 索引指认的文件确实在磁盘上
            }
            // 索引缺条目时不静默判未装：按 jar 元数据 modId 兜底认磁盘
            if (projectId.equalsIgnoreCase(m.modId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一个待反查的 Modrinth 依赖卡：标题标签 + pid + 前后缀，外加 r10 批量回填要用的
     * 封面 / 描述 / 分类节点引用（由 depCard 构造时挂上，反查阶段一次填齐）。
     */
    private record DepLabel(Label label, String pid, String prefix, String suffix,
                            ImageView icon, Label desc, HBox tags) {
        /** 兼容 0.8.x 老调用（只反查标题，不回填封面/描述/分类）。 */
        private DepLabel(Label label, String pid, String prefix, String suffix) {
            this(label, pid, prefix, suffix, null, null, null);
        }
    }

    /** 依赖卡状态（0.8.8 ④，四类弹窗共用）。embedded / incompatible / tool 一律不给安装按钮。 */
    private enum DepState { INSTALLED, INSTALLABLE, EXTERNAL, EMBEDDED, INCOMPATIBLE, TOOL }

    /**
     * 四类详情弹窗共用的依赖卡规格（0.8.8 ④）：Mr 模组 / CF 模组 / 整合包 / 三类内容
     * 都产出同一 {@code List<DepCardSpec>} 交给 {@link #depCard(DepCardSpec, List, Map)}，
     * 保证节点类名 / 结构一致（US-4②）。{@code mrProjectId} 非空 = Modrinth 依赖；
     * {@code cfModId > 0} = CurseForge 依赖。
     */
    private record DepCardSpec(String name, String category, DepState state,
                               String mrProjectId, long cfModId, boolean cf) {}

    /**
     * 依赖卡统一节点构造器（四类弹窗唯一入口，替代 0.8.7 的 12.5px 单行）。
     * 最小高度 ≥64px、ImageView 图标（coverPlaceholder 占位兜底，绝不因单条图标缺失隐藏整卡）、
     * 名称 / 分类元信息 / 动作区；CSS 类名严格按 §7.3：
     * dep-card / dep-card-icon / dep-card-name / dep-card-meta / dep-card-action。
     */
    /** 1.2.8：依赖卡分类标签按必装 / 选装 / 内置分色；分组标题与卡片结构不变。 */
    private static String depCategoryChipStyle(DepCardSpec spec) {
        if (spec.state() == DepState.EMBEDDED) {
            return "tag-dep-embedded";
        }
        return switch (spec.category() == null ? "" : spec.category()) {
            case "必须安装" -> "tag-dep-required";
            case "可选安装" -> "tag-dep-optional";
            default -> "tag-cat";   // 不兼容 / 工具 / 兜底保持原灰标签
        };
    }

    private Node depCard(DepCardSpec spec, List<DepLabel> mrLabels,
                         Map<Label, Long> cfLabels) {
        HBox card = new HBox(12);
        card.getStyleClass().addAll("card", "dep-card");
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMinHeight(64);   // 兜底：即使 CSS 未加载也不塌成单行
        card.setPadding(new Insets(8, 10, 8, 10));

        ImageView icon = new ImageView();
        icon.getStyleClass().add("dep-card-icon");
        icon.setFitWidth(48);
        icon.setFitHeight(48);
        icon.setImage(coverPlaceholder(48));
        // r10：不再按 projectId 逐个起线程拉封面（那是「没封面」的根因）；
        // 真正图标由 resolveDepCardNames 用 projectBriefs 的 iconUrl 一次批量回填。

        Label name = new Label(spec.name() == null ? "未知依赖" : spec.name());
        name.getStyleClass().add("dep-card-name");
        name.setWrapText(true);
        // r10：HMCL 风格 —— 描述单行省略 + 项目分类 chip，反查阶段按 brief 批量填
        Label desc = new Label();
        desc.getStyleClass().add("dep-card-desc");
        desc.setWrapText(false);
        desc.setMaxWidth(360);
        HBox tags = new HBox(6);
        tags.setAlignment(Pos.CENTER_LEFT);
        tags.getStyleClass().add("dep-card-tags");
        // r10：节点引用挂在标题上，供 CF 反查阶段回填（不改 depCard 方法签名）
        name.getProperties().put("gbl.dep.icon", icon);
        name.getProperties().put("gbl.dep.desc", desc);
        name.getProperties().put("gbl.dep.tags", tags);
        if (spec.mrProjectId() != null && !spec.mrProjectId().isBlank() && mrLabels != null) {
            mrLabels.add(new DepLabel(name, spec.mrProjectId(), "", "", icon, desc, tags));
        }
        if (spec.cf() && spec.cfModId() > 0 && cfLabels != null) {
            cfLabels.put(name, spec.cfModId());
        }
        HBox meta = new HBox(8);
        meta.setAlignment(Pos.CENTER_LEFT);
        meta.getStyleClass().add("dep-card-meta");
        meta.getChildren().add(tagChip(spec.category(), depCategoryChipStyle(spec)));
        meta.getChildren().add(tags);
        Label src = new Label(spec.cf() ? "CurseForge" : "Modrinth");
        src.getStyleClass().add("page-desc");
        meta.getChildren().add(src);
        VBox body = new VBox(4, name, meta, desc);
        body.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(body, Priority.ALWAYS);

        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);

        HBox actionBox = new HBox(8);
        actionBox.setAlignment(Pos.CENTER_RIGHT);
        actionBox.getStyleClass().add("dep-card-action");
        switch (spec.state()) {
            case INSTALLED -> {
                Label ok = new Label("✓ 已安装");
                ok.getStyleClass().add("mod-dep-ok");
                actionBox.getChildren().add(ok);
            }
            case INSTALLABLE -> {
                // 0.8.9 R-01（用户 2026-11-07 二次澄清）：点「查看」直接打开这个前置项目
                // 自己的详情弹窗（Iris 的 Sodium → Sodium 项目页），不是去搜索框搜「钠」。
                // 版本仍由用户在新弹窗里自己挑，绝不自动下载，故文案不叫「安装」。
                Button view = new Button("查看");
                view.getStyleClass().add("btn-download");
                view.setMinWidth(72);
                long modId = spec.cf() ? spec.cfModId() : 0L;
                String pid = spec.mrProjectId();
                // name 可能已被后台反查线程改成真名（如 Sodium），读 Label 当前文本，
                // 免得新弹窗标题先闪一下 projectId。
                view.setOnAction(e -> openDependencyProject(name.getText(), pid, modId));
                actionBox.getChildren().add(view);
            }
            case EXTERNAL -> {
                Label ext = new Label("外部依赖，需自行准备");
                ext.getStyleClass().add("page-desc");
                actionBox.getChildren().add(ext);
            }
            case EMBEDDED -> {
                Label emb = new Label("已内置（作者已打包，无需安装）");
                emb.getStyleClass().add("page-desc");
                actionBox.getChildren().add(emb);
            }
            case INCOMPATIBLE -> {
                Label warn = new Label("不兼容");
                warn.getStyleClass().add("mod-dep-warn");
                actionBox.getChildren().add(warn);
            }
            case TOOL -> {
                Label tool = new Label("工具 · 需自行准备");
                tool.getStyleClass().add("page-desc");
                actionBox.getChildren().add(tool);
            }
        }
        card.getChildren().addAll(icon, body, gap, actionBox);
        return card;
    }

    /** 按 category 取一组（保持原始顺序；反查失败兜底卡也在其中，不先过滤 id 非空再计数）。 */
    private static List<DepCardSpec> groupOf(List<DepCardSpec> all, String category) {
        List<DepCardSpec> out = new ArrayList<>();
        for (DepCardSpec s : all) {
            if (s != null && category.equals(s.category())) {
                out.add(s);
            }
        }
        return out;
    }

    /** 渲染一个分区（标题 + 同款 dep-card）；四类弹窗共用，保证结构一致。 */
    private void addDepSection(String title, String headStyle, List<DepCardSpec> all,
                               List<DepLabel> mrLabels, Map<Label, Long> cfLabels) {
        List<DepCardSpec> group = groupOf(all, title);
        if (group.isEmpty()) {
            return;
        }
        Label head = new Label(title);
        head.getStyleClass().add(headStyle);
        head.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        head.setWrapText(true);
        modDetailDeps.getChildren().add(head);
        for (DepCardSpec s : group) {
            modDetailDeps.getChildren().add(depCard(s, mrLabels, cfLabels));
        }
    }

    /** r10：按当前条数刷新 §4 标题文案（HMCL 风格醒目直显，不再提示点标题展开）。 */
    private void updateDepsTitle() {
        if (modDepsTitle == null) {
            return;
        }
        if (modDepsCount <= 0) {
            modDepsTitle.setText("前置依赖");
            return;
        }
        modDepsTitle.setText("前置依赖 · 共 " + modDepsCount
                + " 项（必须安装 / 可选 / 作者已内置 / 不兼容）");
    }

    /** r10：保留兼容入口；依赖卡改为默认直显，标题不再触发折叠，故此为空操作。 */
    private void toggleModDeps() {
        // r10：卡片始终直显，仅保留方法避免历史调用点编译失败。
    }

    /**
     * 四类弹窗共用的依赖区渲染器：分区标题 + 同款 dep-card；四类皆空（无依赖数据）才隐藏整个板块。
     * 反查失败兜底卡一律保留；切版本 / 换弹窗用 {@link #depsRenderToken} 丢弃在飞旧结果。
     */
    private void renderDepCards(List<DepCardSpec> specs) {
        modDetailDeps.getChildren().clear();
        List<DepCardSpec> list = specs == null ? List.of() : specs;
        modDepsCount = list.size();
        if (list.isEmpty()) {
            modDetailDeps.setVisible(false);   // 无依赖数据的类型不显示空板块、不报错
            if (modDepsTitle != null) {
                modDepsTitle.setVisible(false);
                modDepsTitle.setManaged(false);
            }
            return;
        }
        // r10：标题常显 + 卡片默认直显，无需点标题展开
        if (modDepsTitle != null) {
            modDepsTitle.setVisible(true);
            modDepsTitle.setManaged(true);
        }
        modDetailDeps.setVisible(true);
        final int token = ++depsRenderToken;
        List<DepLabel> mrLabels = new ArrayList<>();
        Map<Label, Long> cfLabels = new LinkedHashMap<>();
        addDepSection("必须安装", "mod-dep-required", list, mrLabels, cfLabels);
        addDepSection("可选安装", "mod-group-title", list, mrLabels, cfLabels);
        addDepSection("已内置（作者已打包，无需安装）", "mod-group-title", list, mrLabels, cfLabels);
        List<DepCardSpec> incompat = groupOf(list, "不兼容");
        if (!incompat.isEmpty()) {
            Label warn = new Label("⚠ 与以下项目不兼容，同时安装可能进不去游戏：");
            warn.getStyleClass().add("mod-dep-warn");
            warn.setWrapText(true);
            modDetailDeps.getChildren().add(warn);   // 保留 0.8.7 独立红色警告条
            for (DepCardSpec s : incompat) {
                modDetailDeps.getChildren().add(depCard(s, mrLabels, cfLabels));
            }
        }
        addDepSection("工具", "mod-group-title", list, mrLabels, cfLabels);
        resolveDepCardNames(mrLabels, cfLabels, token);
        updateDepsTitle();
        // 1.0.1：前置依赖卡片回填后补装浮动皮肤（幂等），杜绝越界横线。
        javafx.application.Platform.runLater(this::installFloatScrollBars);
    }

    /**
     * r10：批量反查依赖卡展示信息 —— 一次 {@link ModrinthClient#projectBriefs(List)} 同时拿齐
     * 真名 / 简介 / 图标 URL / 分类，按 brief 直接回填（封面用 iconUrl 直接 loadCover），
     * 不再为每个依赖起 project() 请求拉图标。CF 依赖仍按 modId 反查名字与图标。
     * 失败 / 缺 id 一律用 projectId 兜底、不隐藏卡片；切版本用在飞令牌丢弃旧结果。
     */
    private void resolveDepCardNames(List<DepLabel> mrLabels, Map<Label, Long> cfLabels,
                                     int token) {
        List<String> pids = new ArrayList<>();
        for (DepLabel dl : mrLabels) {
            if (dl.pid() != null && !dl.pid().isBlank() && !pids.contains(dl.pid())) {
                pids.add(dl.pid());
            }
        }
        if (!pids.isEmpty()) {
            Thread t = new Thread(() -> {
                Map<String, ModrinthClient.ProjectBrief> briefs;
                try {
                    briefs = ModrinthClient.projectBriefs(pids);
                } catch (IOException ignore) {
                    briefs = Map.of();
                }
                if (briefs == null) {
                    briefs = Map.of();
                }
                final Map<String, ModrinthClient.ProjectBrief> fb = briefs;
                // 1.0.8：批量 briefs 空 / 缺项时逐 id 兜底。同一个 pid 只发一条请求，
                // 结果回填给所有同 pid 的卡；统一走有界池（4 线程），不让几十个前置项目开几十条线程。
                Map<String, List<DepLabel>> fallbackGroups = new LinkedHashMap<>();
                for (DepLabel dl : mrLabels) {
                    ModrinthClient.ProjectBrief b = fb.get(dl.pid());
                    if (dl.pid() != null && !dl.pid().isBlank()
                            && (b == null || b.title() == null || b.title().isBlank()
                                || b.iconUrl() == null || b.iconUrl().isBlank())) {
                        fallbackGroups.computeIfAbsent(dl.pid(), k -> new ArrayList<>()).add(dl);
                    }
                }
                for (Map.Entry<String, List<DepLabel>> e : fallbackGroups.entrySet()) {
                    fallbackDepBrief(e.getKey(), e.getValue(), token);
                }
                javafx.application.Platform.runLater(() -> {
                    if (depsRenderToken != token) {
                        return;
                    }
                    for (DepLabel dl : mrLabels) {
                        ModrinthClient.ProjectBrief b = fb.get(dl.pid());
                        // 名字：真名优先，反查失败保留 projectId 兜底
                        String title = (b != null && b.title() != null && !b.title().isBlank())
                                ? b.title() : dl.pid();
                        dl.label().setText(dl.prefix() + title + dl.suffix());
                        if (b == null) {
                            continue;   // 没查到：描述/分类/封面维持占位兜底
                        }
                        // 封面：用 brief 的 iconUrl 直接加载，绝不再按 projectId 逐个起线程
                        if (dl.icon() != null && b.iconUrl() != null && !b.iconUrl().isBlank()) {
                            loadCover(dl.icon(), b.iconUrl(), 48);
                        }
                        // 简介：单行省略号，过长截断
                        if (dl.desc() != null) {
                            dl.desc().setText(ellipsize(b.description(), 64));
                        }
                        // 分类：Modrinth slug 转中文 chip，未收录原样显示
                        if (dl.tags() != null && b.categories() != null) {
                            dl.tags().getChildren().clear();
                            for (String cat : b.categories()) {
                                if (cat != null && !cat.isBlank()) {
                                    dl.tags().getChildren().add(tagChip(depCategoryZh(cat), "tag-cat"));
                                }
                            }
                        }
                    }
                });
            }, "gbl-dep-briefs");
            t.setDaemon(true);
            t.start();
        }
        for (Map.Entry<Label, Long> e : cfLabels.entrySet()) {
            final long modId = e.getValue();
            final Label label = e.getKey();
            Thread t = new Thread(() -> {
                CurseForgeClient.Project p = null;
                try {
                    p = CurseForgeClient.project(modId);
                } catch (IOException ignore) {
                    // 反查失败：留 modId 兜底
                }
                final CurseForgeClient.Project cp = p;
                javafx.application.Platform.runLater(() -> {
                    if (depsRenderToken != token) {
                        return;
                    }
                    if (cp == null) {
                        return;
                    }
                    if (cp.name() != null && !cp.name().isBlank()) {
                        label.setText(cp.name());
                    }
                    // CF 图标能拿到就填（节点引用挂在 label 的 properties 上，不改 depCard 签名）
                    if (label.getProperties().get("gbl.dep.icon") instanceof ImageView iv
                            && cp.iconUrl() != null && !cp.iconUrl().isBlank()) {
                        loadCover(iv, cp.iconUrl(), 48);
                    }
                    if (label.getProperties().get("gbl.dep.desc") instanceof Label d) {
                        d.setText(ellipsize(cp.summary(), 64));
                    }
                    if (label.getProperties().get("gbl.dep.tags") instanceof HBox tg
                            && cp.categories() != null) {
                        tg.getChildren().clear();
                        for (String cat : cp.categories()) {
                            if (cat != null && !cat.isBlank()) {
                                tg.getChildren().add(tagChip(cat, "tag-cat"));
                            }
                        }
                    }
                });
            }, "gbl-cf-dep-name");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * 1.0.8：批量 briefs 缺项时的逐 id 兜底——一条 {@code project(pid)} 同时拿真名与图标 URL，
     * 比「loadCoverAsync 拉图标 + project 拉名字」少一半请求；图标回填仍走 {@link #loadCover}
     * （自带 URL 令牌 / epoch / 离场三重校验），外面再加 depsRenderToken 与弹窗可见性校验，
     * 切版本或关弹窗后旧结果一律丢弃，不会串图。
     */
    private void fallbackDepBrief(String pid, List<DepLabel> labels, int token) {
        if (pid == null || pid.isBlank() || labels == null || labels.isEmpty()) {
            return;
        }
        DEP_FALLBACK_POOL.execute(() -> {
            ModrinthClient.Project p = null;
            try {
                p = ModrinthClient.project(pid);
            } catch (IOException | RuntimeException ex) {
                System.err.println("[modrinth] 依赖卡逐项兜底失败 " + pid + ": " + ex.getMessage());
            }
            final ModrinthClient.Project fp = p;
            javafx.application.Platform.runLater(() -> {
                if (depsRenderToken != token || fp == null
                        || modLayer == null || !modLayer.isVisible()) {
                    return;   // 切版本 / 关弹窗后旧结果整份丢弃
                }
                for (DepLabel dl : labels) {
                    if (dl.label() == null || dl.icon() == null) {
                        continue;
                    }
                    String title = fp.title();
                    if (title != null && !title.isBlank()) {
                        dl.label().setText(dl.prefix() + title + dl.suffix());
                    }
                    if (fp.iconUrl() != null && !fp.iconUrl().isBlank()) {
                        loadCover(dl.icon(), fp.iconUrl(), 48);
                    }
                    if (dl.desc() != null && fp.description() != null) {
                        dl.desc().setText(ellipsize(fp.description(), 64));
                    }
                    if (dl.tags() != null && fp.categories() != null) {
                        dl.tags().getChildren().clear();
                        for (String cat : fp.categories()) {
                            if (cat != null && !cat.isBlank()) {
                                dl.tags().getChildren().add(tagChip(depCategoryZh(cat), "tag-cat"));
                            }
                        }
                    }
                }
            });
        });
    }

    /** r10：单行截断，超长加省略号（灰色小字一行显示，不撑高卡片）。 */
    private static String ellipsize(String text, int max) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String t = text.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max).trim() + "…";
    }

    /** r10：Modrinth 英文分类 slug → 中文 chip 文案；未收录的原样显示。 */
    private static String depCategoryZh(String cat) {
        if (cat == null) {
            return "";
        }
        return switch (cat.toLowerCase(java.util.Locale.ROOT)) {
            case "decoration" -> "装饰";
            case "utility", "utilities" -> "实用";
            case "optimization" -> "优化";
            case "management" -> "管理";
            case "adventure" -> "冒险";
            case "creative" -> "建造";
            case "library" -> "库";
            case "worldgen", "world-generation" -> "地形生成";
            case "mob" -> "生物";
            case "food" -> "食物";
            case "magic" -> "魔法";
            case "tech", "technology" -> "科技";
            default -> cat;
        };
    }

    /**
     * 0.8.9 R-01（用户 2026-11-07 二次当面澄清）：点前置依赖卡上的「查看」=
     * <b>直接打开该前置项目自己的详情弹窗</b>（Iris 的前置 Sodium → 立刻弹出 Sodium
     * 的项目页：它自己的简介、标签、262 个版本、按 Minecraft 版本分组与推荐徽章），
     * 不是在搜索框里搜索「钠」，也不是切回下载页模组 tab。
     *
     * <p>「绝不自动下载」不变：新弹窗里所有版本都由用户自己挑，只有点版本行的「安装」
     * 才落盘。本方法 0 处 install 调用、0 处搜索框写入、下载队列 0 新增。
     *
     * <p>实现上只需构造一条最小 Hit 交给 {@link #openModDialog} / {@link #openCfDialog}：
     * 这两个入口本来就会在后台拉 project + versions/files，再用真实 Project 覆盖标题 /
     * 封面 / 简介 / 标签 / 版本列表，所以不需要额外反查线程，也不需要切数据源、切 tab。
     * 网络失败时弹窗仍停在前置项目页并显示可读错误，不回滚、不跳搜索。
     */
    private void openDependencyProject(String shownName, String mrProjectId, long cfModId) {
        // 1.3.0：从下载确认框点「查看」时先关确认框 —— modLayer 比确认框先加进 modalHost，
        // 不关就会被确认框遮罩压在底下，看着像「点了没反应」。一处覆盖详情前置区与
        // 模组/整合包下载确认框。只关 install-confirm：关闭详情弹窗那个方法会隐藏与新旧详情
        // 共用的 modLayer 单例，把正要打开的新弹窗自己关掉，绝对不能调它。
        if (installConfirmLayer != null && installConfirmLayer.isVisible()) {
            closeInstallConfirm();
        }
        String title = shownName == null || shownName.isBlank() ? "前置模组" : shownName;
        if (cfModId > 0) {
            // CF 依赖：直接开 CF 项目详情，与当前下载源是 Modrinth 还是「两者合并」无关
            openCfDialog(new CurseForgeClient.Hit(cfModId, title, "", 0L, null, null));
            return;
        }
        if (mrProjectId != null && !mrProjectId.isBlank()) {
            // Modrinth project() 同时接受 project_id 与 slug，依赖里的 projectId 可直接用
            openModDialog(new ModrinthClient.Hit(mrProjectId, mrProjectId, title, "", 0L, null));
            return;
        }
        toast("这个前置没有详情页面");
    }

    /** Modrinth Dependency → 统一 DepCardSpec（四类文案沿用 0.8.7）。 */
    private DepCardSpec mrDepSpec(ModrinthClient.Dependency d) {
        String pid = d.projectId();
        boolean external = pid == null || pid.isBlank();
        String name = depDisplayName(d);
        String type = d.type() == null || d.type().isBlank() ? "required" : d.type();
        DepState normal = external ? DepState.EXTERNAL
                : (isModInstalled(pid) ? DepState.INSTALLED : DepState.INSTALLABLE);
        return switch (type) {
            case "embedded" -> new DepCardSpec(name, "已内置（作者已打包，无需安装）",
                    DepState.EMBEDDED, external ? null : pid, 0, false);
            case "optional" -> new DepCardSpec(name, "可选安装", normal,
                    external ? null : pid, 0, false);
            case "incompatible" -> new DepCardSpec(name, "不兼容",
                    DepState.INCOMPATIBLE, external ? null : pid, 0, false);
            default -> new DepCardSpec(name, "必须安装", normal,
                    external ? null : pid, 0, false);
        };
    }

    /**
     * CurseForge 文件级依赖适配（0.8.8 ④）。按 CF API 真值 / QA-0.8.8-dep-samples 实证口径
     * 映射 relationType：3=Required（必须·给按钮）/ 2=Optional（可选·给按钮）/
     * 1·6=Embedded·Include（已内置·无按钮）/ 4=Tool（兜底卡·无按钮）/
     * 5=Incompatible（不兼容·无按钮）/ 其它按不兼容保守处理。
     * 反查失败按 modId / fileId 兜底显示名，绝不因无法分类隐藏整卡或整个板块。
     */
    private void populateCfDeps(List<CurseForgeClient.FileDependency> deps) {
        renderDepCards(cfDepSpecs(deps));
    }

    /**
     * 1.2.9：CF relationType → 统一 DepCardSpec 的<b>唯一</b>映射。
     * 详情弹窗顶部与下载确认框共用这一份，避免两处 switch 日后漂移；
     * 反查失败按 modId / fileId 兜底显示名，绝不因无法分类隐藏整卡。
     */
    private static List<DepCardSpec> cfDepSpecs(List<CurseForgeClient.FileDependency> deps) {
        List<DepCardSpec> specs = new ArrayList<>();
        for (CurseForgeClient.FileDependency d :
                deps == null ? List.<CurseForgeClient.FileDependency>of() : deps) {
            if (d == null) {
                continue;
            }
            long modId = d.modId();
            String name = modId > 0 ? ("CF 模组 " + modId)
                    : (d.fileId() > 0 ? ("CF 文件 " + d.fileId()) : "未知依赖");
            switch (d.relationType()) {
                case 3 -> specs.add(new DepCardSpec(name, "必须安装", DepState.INSTALLABLE,
                        null, modId, true));
                case 2 -> specs.add(new DepCardSpec(name, "可选安装", DepState.INSTALLABLE,
                        null, modId, true));
                case 1, 6 -> specs.add(new DepCardSpec(name, "已内置（作者已打包，无需安装）",
                        DepState.EMBEDDED, null, modId, true));
                case 5 -> specs.add(new DepCardSpec(name, "不兼容", DepState.INCOMPATIBLE,
                        null, modId, true));
                case 4 -> specs.add(new DepCardSpec(name, "工具", DepState.TOOL,
                        null, modId, true));
                default -> specs.add(new DepCardSpec(name, "不兼容", DepState.INCOMPATIBLE,
                        null, modId, true));
            }
        }
        return specs;
    }

    /**
     * 详情弹窗前置依赖板块（0.8.7 A-2：版本级 + 三分类，随选中版本联动）。
     *
     * <p>数据源不再是恒空的项目级 {@code Project.dependencies()}，而是
     * {@code ModrinthClient.dependenciesOf(当前选中 Version)}。四类严格分治：
     * required（必须安装）/ embedded（作者已打包，灰字可折叠，绝不给安装按钮）/
     * optional（可选安装）/ incompatible（独立红色警告条，不混进必需 / 选装）。
     * 四类皆空时整个板块连标题一起隐藏（标题与卡片由 modDepsTitle/modDetailDeps 按条数同步显隐）。
     *
     * <p>切版本不残留：首行清容器，并用 {@link #selectedVersion} 做令牌——在飞的反查结果
     * 回来时先校验令牌，过期整份丢弃、不回填。标题反查走
     * {@link ModrinthClient#projectTitles(List)} 一次批量请求、按返回项自身 id 回填，
     * 绝不按数组下标对齐；空 Map / 反查失败都用 projectId 兜底，不隐藏整个板块。
     */
    private void populateDeps(List<ModrinthClient.Dependency> deps) {
        List<DepCardSpec> specs = new ArrayList<>();
        for (ModrinthClient.Dependency d :
                deps == null ? List.<ModrinthClient.Dependency>of() : deps) {
            if (d != null) {
                specs.add(mrDepSpec(d));   // 反查失败兜底卡也进 specs，绝不先过滤掉
            }
        }
        renderDepCards(specs);
    }

    /**
     * 一行 Modrinth 依赖（0.8.8 ④ 升级为 {@code .dep-card}）：签名与 0.8.7 保持一致，
     * 内部委托给四类弹窗共用的 {@link #depCard(DepCardSpec, List, Map)}，节点类名 / 结构一致。
     */
    private Node depRow(ModrinthClient.Dependency d, List<DepLabel> depLabels) {
        return depCard(mrDepSpec(d), depLabels, null);
    }

    /** 依赖显示名：project_id 优先；为空但有 file_name 就显示文件名；再兜 version_id。 */
    private static String depDisplayName(ModrinthClient.Dependency d) {
        String pid = d.projectId();
        if (pid != null && !pid.isBlank()) {
            return pid;
        }
        String fn = d.fileName();
        if (fn != null && !fn.isBlank()) {
            return fn;
        }
        String vid = d.versionId();
        return vid != null && !vid.isBlank() ? vid : "未知依赖";
    }

    /**
     * 后台批量把 projectId 换成真实标题：一次 {@link ModrinthClient#projectTitles(List)}
     * 请求（返回顺序不保证，按返回项自身 id 建 Map 回填，绝不按数组下标对齐）。
     * 用 {@link #selectedVersion} 令牌校验：切版本 / 关闭弹窗后在飞的旧结果整份丢弃、不回填。
     * 反查失败 / 部分缺失都用 projectId 兜底，绝不因此隐藏整个依赖板块。
     */
    private void resolveDepNames(List<DepLabel> depLabels, Label conflictWarn,
                                 List<ModrinthClient.Dependency> incompatible,
                                 ModrinthClient.Version token) {
        List<String> pids = new ArrayList<>();
        for (DepLabel dl : depLabels) {
            if (dl.pid() != null && !dl.pid().isBlank() && !pids.contains(dl.pid())) {
                pids.add(dl.pid());
            }
        }
        for (ModrinthClient.Dependency d : incompatible) {
            String pid = d.projectId();
            if (pid != null && !pid.isBlank() && !pids.contains(pid)) {
                pids.add(pid);
            }
        }
        if (pids.isEmpty()) {
            return;
        }
        Thread depName = new Thread(() -> {
            Map<String, String> titles;
            try {
                titles = ModrinthClient.projectTitles(pids);
            } catch (IOException ex) {
                titles = Map.of();   // 整体失败：全部留 ID，不阻断界面
            }
            if (titles == null) {
                titles = Map.of();
            }
            final Map<String, String> finalTitles = titles;
            javafx.application.Platform.runLater(() -> {
                // 令牌校验：切版本 / 关闭弹窗后，旧结果整份丢弃，不残留
                if (selectedVersion != token) {
                    return;
                }
                for (DepLabel dl : depLabels) {
                    String title = finalTitles.getOrDefault(dl.pid(), dl.pid());
                    dl.label().setText(dl.prefix() + title + dl.suffix());
                }
                if (conflictWarn != null) {
                    List<String> names = new ArrayList<>();
                    for (ModrinthClient.Dependency d : incompatible) {
                        String pid = d.projectId();
                        names.add(pid != null && !pid.isBlank()
                                ? finalTitles.getOrDefault(pid, pid) : depDisplayName(d));
                    }
                    conflictWarn.setText("⚠ 与以下项目不兼容，同时安装可能进不去游戏："
                            + String.join("、", names));
                }
            });
        }, "gbl-dep-names");
        depName.setDaemon(true);
        depName.start();
    }

    /**
     * 冲突二次确认：按「正要安装的这个版本」的版本级依赖实时判断，不依赖界面选中状态，
     * 也不做递归。有本地已装的 incompatible 项才弹「仍要安装？」，不静默阻断。
     */
    private boolean confirmConflictInstall(ModrinthClient.Version v) {
        List<String> installed = new ArrayList<>();
        for (ModrinthClient.Dependency d : ModrinthClient.dependenciesOf(v)) {
            if (d != null && "incompatible".equals(d.type())
                    && d.projectId() != null && !d.projectId().isBlank()
                    && isModInstalled(d.projectId())) {
                installed.add(depDisplayName(d));
            }
        }
        if (installed.isEmpty()) {
            return true;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        styleAlert(confirm);
        confirm.setTitle("检测到冲突");
        confirm.setHeaderText("检测到已装 " + String.join("、", installed)
                + "，与本模组冲突，仍要安装？");
        confirm.setContentText("同时安装可能进不去游戏。");
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /** 选中 / 展开某个版本行：清旧高亮 + 立即刷新依赖区（不残留上一个版本内容）。 */
    private void selectModVersion(ModrinthClient.Version v, Node row) {
        if (modDetailVersions != null) {
            clearVersionSelection(modDetailVersions);
        }
        if (row != null) {
            row.getStyleClass().add("mod-version-selected");
        }
        // 0.8.7：选中版本既是依赖区数据源 / 安装 5 参 requires 来源，也是反查结果的令牌，
        // 必须在 populateDeps 之前设好，切版本后旧的反查结果才会被判为过期而丢弃。
        selectedVersion = v;
        populateDeps(ModrinthClient.dependenciesOf(v));
    }

    /** 递归清掉版本区里所有选中高亮（版本行嵌套在分组 VBox 里）。 */
    private static void clearVersionSelection(Node node) {
        node.getStyleClass().remove("mod-version-selected");
        if (node instanceof javafx.scene.Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                clearVersionSelection(c);
            }
        }
    }

    /**
     * 0.8.8 R-03 / 0.8.9 R-01：原「一键直装前置」的 {@code installDependency()} /
     * {@code installCfDependency()} 已整段删除。用户 2026-11-07 二次澄清要的是
     * 「点前置 → 直接打开该前置项目的详情弹窗 → 自己挑版本」，既不自动下载、
     * 也不去搜索框搜名字；Modrinth / CurseForge 统一由
     * {@link #openDependencyProject} 处理，依赖卡路径 0 处 install 调用。
     */

    /** 版本类型优先级：release 0 / beta 1 / alpha 2 / 其它 3（推荐版与依赖安装共用）。 */
    private static int versionTypeRank(String t) {
        if (t == null) {
            return 3;
        }
        return switch (t) {
            case "release" -> 0;
            case "beta" -> 1;
            case "alpha" -> 2;
            default -> 3;
        };
    }

    /** 一行版本（详情弹窗内）：D3 统一渲染器，Modrinth / 整合包共用。 */
    private Node versionRow(ModrinthClient.Version v, boolean modpack, boolean installed, String coverUrl) {
        ModrinthClient.File f = v.primaryFile();
        String type = v.versionType() == null ? "other" : v.versionType();
        List<String> loaders = v.loaders() == null ? List.of() : v.loaders();
        List<String> extra = new ArrayList<>();
        if (modpack) {
            // 整合包 §5 要求「MC 版本 / 加载器版本 / 文件数 / 包体大小」。在线接口不返回
            // .mrpack 清单：MC 版本由组头给出，包体大小用 primaryFile().size() 近似，
            // 文件数如实标注「导入后可见」（导入流程里 ModpackImporter.Manifest.files() 可用）。
            extra.add("包体 " + (f != null && f.size() > 0 ? LibFetcher.human(f.size()) : "未知"));
            extra.add("文件数 导入后可见");
            if (!loaders.isEmpty()) {
                extra.add("加载器 " + String.join(" / ", loaders.stream()
                        .map(ShellView::modLoaderCn).toList()));
            }
        }
        return versionRowView(new VersionRowSpec(
                shortTypeCn(type), "tag-" + type,
                text(v.name()), loaders, modDate(v.datePublished()),
                f == null ? 0L : f.size(), f == null ? "" : text(f.filename()),
                extra, installed && !modpack ? "重装" : (modpack ? "安装整合包" : "安装"),
                installed && !modpack,
                // 0.8.7：详情弹窗安装回调统一经 dispatchInstall 分发（MOD / MODPACK / 三类内容）。
                // 模组 / 整合包行 kind 恒为 null（落 mods/ 或走 .mrpack 链）。
                btn -> dispatchInstall(new InstallCtx(v, null, modpack, text(v.name()), null, coverUrl), btn),
                mrDepSummary(v)));
    }

    /**
     * 详情弹窗安装回调的统一载体：把「装哪个源 / 是否整合包 / 世界目录名」打包。
     * 三处版本行（{@link #versionRow} / {@link #cfVersionRow} / 内容版本行）都构造它，
     * 全部交给 {@link #dispatchInstall} 一个出口分派，杜绝内容页文件被塞进 mods/。
     * Modrinth 源用 mrVersion，CurseForge 源用 cfRef，二者恰有一个非 null。
     */
    private record InstallCtx(ModrinthClient.Version mrVersion, CfRef cfRef,
                              boolean modpack, String displayName, ContentStore.Kind kind,
                              String coverUrl) {}

    /**
     * 弹窗安装携带的封面取值口：优先用详情接口拉到的 {@code Project.iconUrl}。
     * 搜索 Hit 的 iconUrl 只在「从搜索卡进弹窗」时才有值——前置「查看」/ 已装模组
     * 等入口构造的是最小 Hit（无图标），只认它会让下载队列永远拿不到封面（0.9.0 实测）。
     */
    private static String dialogCover(ModrinthClient.Project p, ModrinthClient.Hit hit) {
        if (p != null && p.iconUrl() != null && !p.iconUrl().isBlank()) {
            return p.iconUrl();
        }
        return hit == null ? null : hit.iconUrl();
    }

    /** CurseForge 版 {@link #dialogCover}。 */
    private static String dialogCover(CurseForgeClient.Project p, CurseForgeClient.Hit hit) {
        if (p != null && p.iconUrl() != null && !p.iconUrl().isBlank()) {
            return p.iconUrl();
        }
        return hit == null ? null : hit.iconUrl();
    }

    /** CurseForge 源文件引用：安装需要 modId + File 两个值。 */
    private record CfRef(long modId, CurseForgeClient.File file) {}

    /**
     * 详情弹窗所有安装回调的唯一出口（0.8.7）。0.8.8 起按请求自带的 {@link InstallCtx#kind()}
     * 分派落盘（Kind 随请求走，废弃全局可变分派开关，杜绝看过内容弹窗再开模组弹窗时残留串味）：
     * <pre>
     *   kind = null                     → 模组 / 整合包：走 mods/ 或 .mrpack 链
     *   modpack = true（kind = null）   → 原 .mrpack 链（installModpackVersion）
     *   kind = RESOURCEPACK / SHADER   → ContentStore.installFile
     *   kind = WORLD                   → ContentStore.installWorld
     *   其它组合                         → 拒绝并给可读提示，不静默落盘
     * </pre>
     * 弹窗安装路径里 {@code ModrinthClient.install(} / {@code CurseForgeClient.install(}
     * 只在本方法内出现；自动补装 {@link #installApiIfNeeded} 不经这里。
     * 前置依赖点「查看」只打开该前置项目详情让用户自己挑版本、绝不自动下载，见 {@link #openDependencyProject}。
     */
    private void dispatchInstall(InstallCtx ctx, Button btn) {
        final ContentStore.Kind kind = ctx.kind();

        // 1.2.9：模组 / 整合包先弹 HMCL 式下载确认框（分区逐条列前置），点「安装」才走下面的原安装链。
        // 内容页（资源包 / 光影 / 世界）没有「前置模组」概念，保持原状直接装，不多加一跳。
        if (kind == null && !installConfirmApproved) {
            showInstallConfirm(ctx, btn);
            return;
        }
        installConfirmApproved = false;   // 一次性令牌，放行后立即复位

        // —— 内容页（资源包 / 光影 / 世界）：一律走 ContentStore，不要求「当前游戏」——
        if (kind != null) {
            if (ctx.modpack()) {
                toast("内容弹窗不含整合包，已取消安装");
                return;
            }
            if (kind != ContentStore.Kind.RESOURCEPACK
                    && kind != ContentStore.Kind.SHADER
                    && kind != ContentStore.Kind.WORLD) {
                toast("未知内容类型，已取消安装");
                return;
            }
            dispatchContentInstall(ctx, btn, kind);
            return;
        }

        // —— 模组 / 整合包（0.8.6 原语义）——
        if (ctx.modpack()) {
            if (ctx.mrVersion() == null) {
                toast("整合包只能从 Modrinth 安装");
                return;
            }
            installModpackVersion(ctx.mrVersion(), btn, ctx.coverUrl());
            return;
        }
        // 0.8.8 Q3：去掉「全局当前游戏为空就静默 return」的硬拒绝，统一走单一取值口；
        // null 时给含兜底引导的可见提示，而不是把安装吞掉。
        String game = effectiveGameId();
        if (game == null) {
            toast("还没有可安装的游戏：去「游戏列表」选一个当前游戏，或去「下载」装一个，再点安装");
            return;
        }

        if (ctx.mrVersion() != null) {
            // Modrinth 模组：5 参 install 把「这一行用户所选的版本」传进去，
            // ModIndex.Entry.requires 才会来自该版本的依赖（U1 闭环）。冲突二次确认按这个
            // 版本实时判断，命中本地已装的 incompatible 项才弹框，不静默阻断。
            ModrinthClient.Version v = ctx.mrVersion();
            if (!confirmConflictInstall(v)) {
                return;
            }
            ModrinthClient.File f = v.primaryFile();
            if (f == null || f.url() == null || f.url().isBlank()) {
                toast("这个版本没有可下载的文件");
                return;
            }
            btn.setDisable(true);
            modDetailStatus.setText("正在安装 " + v.name() + " …");
            final String modTitle = v.name();
            // 0.9.0 R-01/R-02：装进 DownloadCenter 统一队列，Sodium 之类任何异常都收敛成 FAILED，
            // 不再卡在「正在安装…」。进度经 ModrinthClient.ProgressListener 回传。
            DownloadCenter.DEFAULT.submit(modTitle, DownloadCenter.Category.MOD, ctx.coverUrl(),
                (DownloadCenter.Progress p) -> {
                    try {
                        Path installed = ModrinthClient.install(f, v, game,
                                new java.util.concurrent.atomic.AtomicBoolean(),
                                (done, total) -> p.onProgress(done, total));
                        javafx.application.Platform.runLater(() -> {
                            btn.setDisable(false);
                            if (installed == null) {
                                modDetailStatus.setText("已取消安装 " + modTitle);
                                return;
                            }
                            modDetailStatus.setText(modTitle + " 装好了（" + f.filename() + "）");
                            toast(modTitle + " 装好了");
                            // 0.9.4：记下安装时所选的适配 MC 版本，供管理页版本胶囊回退。
                            AdaptedMc.remember(f.filename(), game);
                            refreshGameManage();   // 0.8.8 ③：装完无条件刷新（方法自带守卫）
                        });
                        return installed;
                    } catch (Exception ex) {
                        if (ex instanceof java.util.concurrent.CancellationException) {
                            javafx.application.Platform.runLater(() -> {
                                btn.setDisable(false);
                                modDetailStatus.setText("已取消安装 " + modTitle);
                            });
                        } else {
                            javafx.application.Platform.runLater(() -> {
                                btn.setDisable(false);
                                modDetailStatus.setText("没装上：" + ex.getMessage());
                            });
                        }
                        throw ex;
                    }
                });
        } else if (ctx.cfRef() != null) {
            // CurseForge 模组：换临时直链 → 下到 mods 目录 → 写索引（0.8.6 原逻辑）。
            CurseForgeClient.File f = ctx.cfRef().file();
            long modId = ctx.cfRef().modId();
            btn.setDisable(true);
            modDetailStatus.setText("正在安装 " + f.displayName() + " …");
            final String cfName = f.displayName();
            // 0.9.0 R-01/R-02：统一进 DownloadCenter，任何异常都收敛成 FAILED（修掉卡死）。
            DownloadCenter.DEFAULT.submit(cfName, DownloadCenter.Category.MOD, ctx.coverUrl(),
                (DownloadCenter.Progress p) -> {
                    try {
                        Path installed = CurseForgeClient.install(modId, f, game,
                                new java.util.concurrent.atomic.AtomicBoolean(),
                                (done, total) -> p.onProgress(done, total));
                        javafx.application.Platform.runLater(() -> {
                            btn.setDisable(false);
                            if (installed == null) {
                                modDetailStatus.setText("已取消安装 " + cfName);
                                return;
                            }
                            modDetailStatus.setText(cfName + " 装好了（" + f.fileName() + "）");
                            toast(cfName + " 装好了");
                            // 0.9.4：记下安装时所选的适配 MC 版本，供管理页版本胶囊回退。
                            AdaptedMc.remember(f.fileName(), game);
                            refreshGameManage();   // 0.8.8 ③：装完无条件刷新（方法自带守卫）
                        });
                        return installed;
                    } catch (Exception ex) {
                        if (ex instanceof java.util.concurrent.CancellationException) {
                            javafx.application.Platform.runLater(() -> {
                                btn.setDisable(false);
                                modDetailStatus.setText("已取消安装 " + cfName);
                            });
                        } else {
                            javafx.application.Platform.runLater(() -> {
                                btn.setDisable(false);
                                modDetailStatus.setText("没装上：" + ex.getMessage());
                            });
                        }
                        throw ex;
                    }
                });
        } else {
            toast("没有可安装的文件");
        }
    }

    // ---------------------------------------------------------------- 1.2.9 HMCL 式下载确认框

    /**
     * 组装确认框单例：标题 + 版本条 + 限高可滚动的前置清单 + 右对齐「安装 / 取消」。
     * 只用 GBL 白卡 / 碳灰配色，形态照 HMCL 的「整合包下载 - XXX」确认框；
     * 结构上是独立节点，绝不复用详情弹窗的 modDetailDeps 容器。
     */
    private Node buildInstallConfirmDialog() {
        VBox card = new VBox(0);
        card.getStyleClass().addAll("card", "install-confirm-card");
        card.setPadding(new Insets(18, 20, 14, 20));
        // 1.3.1 修复轮：窄窗口下定宽卡不溢出（前置区 ScrollPane 已有 320 硬上限）
        card.setMinWidth(320);
        card.setPrefWidth(660);
        card.setMaxWidth(720);
        if (shell != null) {
            card.maxWidthProperty().bind(shell.widthProperty().subtract(48));
        }
        // 卡片自身高度由内容 pref 决定；前置区限高，70+ 条也不会把弹窗拉成竖长条
        card.setMinHeight(Region.USE_PREF_SIZE);
        card.setMaxHeight(Region.USE_PREF_SIZE);

        installConfirmTitle = new Label("模组下载");
        installConfirmTitle.getStyleClass().addAll("dlg-title", "install-confirm-title");
        installConfirmTitle.setTextOverrun(OverrunStyle.ELLIPSIS);
        installConfirmTitle.setMaxWidth(Double.MAX_VALUE);

        installConfirmVersionBar = new HBox(8);
        installConfirmVersionBar.getStyleClass().add("install-confirm-version-bar");
        installConfirmVersionBar.setAlignment(Pos.CENTER_LEFT);

        installConfirmDeps = new VBox(6);
        installConfirmDeps.getStyleClass().addAll("install-confirm-deps", "mod-deps");
        installConfirmScroll = new ScrollPane(installConfirmDeps);
        installConfirmScroll.getStyleClass().addAll("content-scroll", "install-confirm-scroll");
        installConfirmScroll.setFitToWidth(true);
        installConfirmScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        installConfirmScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        installConfirmScroll.setPrefHeight(220);
        installConfirmScroll.setMinHeight(88);
        installConfirmScroll.setMaxHeight(320);   // 硬上限：无论 1 条还是 70 条都不再长

        HBox actions = new HBox(10);
        actions.getStyleClass().add("install-confirm-actions");
        actions.setAlignment(Pos.CENTER_RIGHT);
        installConfirmCancel = new Button("取消");
        installConfirmCancel.getStyleClass().add("btn-ghost");
        installConfirmCancel.setMinHeight(32);
        installConfirmCancel.setMinWidth(78);
        installConfirmCancel.setOnAction(e -> closeInstallConfirm());
        installConfirmOk = new Button("安装");
        installConfirmOk.getStyleClass().add("btn-download");
        installConfirmOk.setMinHeight(32);
        installConfirmOk.setMinWidth(78);
        installConfirmOk.setOnAction(e -> {
            InstallCtx pending = installConfirmCtx;
            Button pendingBtn = installConfirmBtn;
            closeInstallConfirm();
            if (pending == null) {
                return;
            }
            installConfirmApproved = true;   // 一次性放行，真正的安装仍走原 dispatchInstall
            dispatchInstall(pending, pendingBtn);
        });
        actions.getChildren().addAll(installConfirmCancel, installConfirmOk);

        card.getChildren().addAll(installConfirmTitle, installConfirmVersionBar,
                sepLine(), installConfirmScroll, actions);

        Region backdrop = new Region();
        backdrop.getStyleClass().add("dlg-backdrop");
        backdrop.setOnMouseClicked(e -> closeInstallConfirm());
        VBox shellBox = new VBox(card);
        shellBox.setAlignment(Pos.CENTER);
        shellBox.setFillWidth(false);
        StackPane layer = new StackPane(backdrop, shellBox);
        layer.getStyleClass().add("dlg-layer");
        layer.setVisible(false);
        layer.setManaged(false);
        layer.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                closeInstallConfirm();
            }
        });
        installConfirmLayer = layer;
        return layer;
    }

    /**
     * 从版本行安装按钮进来：先弹确认框，真正安装留给框里的「安装」。
     * 数据源全部来自 {@link InstallCtx} 自带的版本 / 文件对象，零新增联网请求。
     */
    private void showInstallConfirm(InstallCtx ctx, Button btn) {
        if (ctx == null || installConfirmLayer == null) {
            if (btn != null) {
                btn.setDisable(false);
            }
            return;
        }
        installConfirmCtx = ctx;
        installConfirmBtn = btn;
        final int seq = ++installConfirmSeq;
        final String name = text(ctx.displayName());
        installConfirmTitle.setText((ctx.modpack() ? "整合包下载 - " : "模组下载 - ") + name);
        installConfirmVersionBar.getChildren().clear();
        String type = "release";
        String date = "";
        List<String> loaders = List.of();
        if (ctx.mrVersion() != null) {
            ModrinthClient.Version v = ctx.mrVersion();
            type = v.versionType() == null || v.versionType().isBlank()
                    ? "release" : v.versionType();
            date = modDate(v.datePublished());
            loaders = v.loaders() == null ? List.of() : v.loaders();
        } else if (ctx.cfRef() != null) {
            CurseForgeClient.File f = ctx.cfRef().file();
            if (f != null) {
                type = cfTypeKey(f.releaseType());
                date = modDate(f.fileDate());
                loaders = loadersFromGameVersions(f.gameVersions());
            }
        }
        installConfirmVersionBar.getChildren().add(tagChip(shortTypeCn(type), "tag-" + type));
        Label versionName = new Label(name);
        versionName.getStyleClass().add("install-confirm-version-name");
        versionName.setTextOverrun(OverrunStyle.ELLIPSIS);
        versionName.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(versionName, Priority.ALWAYS);
        installConfirmVersionBar.getChildren().add(versionName);
        if (date != null && !date.isBlank()) {
            Label dateLabel = new Label(date);
            dateLabel.getStyleClass().add("page-desc");
            dateLabel.setMinWidth(Region.USE_PREF_SIZE);
            installConfirmVersionBar.getChildren().add(dateLabel);
        }
        for (String loader : loaders) {
            installConfirmVersionBar.getChildren().add(tagChip(modLoaderCn(loader), "tag-loader"));
        }

        cancelAllRipples();   // 1.0.5：开弹窗前清涟漪
        showOverlayLayer(installConfirmLayer, "install-confirm");
        // 1.2.9：ObservableList 变更统一在 FX 线程；seq 校验丢弃已关闭 / 被替换的旧弹框回填
        Platform.runLater(() -> {
            if (seq != installConfirmSeq || !installConfirmLayer.isVisible()) {
                return;
            }
            renderInstallConfirmDeps(ctx);
            installFloatScrollBars();
        });
    }

    /** 关闭确认框且不安装；同时作废旧回填令牌。 */
    private void closeInstallConfirm() {
        hideOverlayLayer(installConfirmLayer, "install-confirm");
        installConfirmSeq++;
        installConfirmCtx = null;
        installConfirmBtn = null;
    }

    /**
     * 渲染确认框自己的前置分区：必须 / 可选 / 内置 / 不兼容 / 工具。
     * 复用 depCard 与 resolveDepCardNames（无状态渲染），但容器是独立的 installConfirmDeps。
     */
    private void renderInstallConfirmDeps(InstallCtx ctx) {
        installConfirmDeps.getChildren().clear();
        List<DepCardSpec> specs = new ArrayList<>();
        if (ctx.mrVersion() != null) {
            for (ModrinthClient.Dependency d : ModrinthClient.dependenciesOf(ctx.mrVersion())) {
                if (d != null) {
                    specs.add(mrDepSpec(d));   // 反查失败兜底卡也保留，绝不先过滤
                }
            }
        } else if (ctx.cfRef() != null && ctx.cfRef().file() != null) {
            specs.addAll(cfDepSpecs(ctx.cfRef().file().dependencies()));
        }
        List<DepLabel> mrLabels = new ArrayList<>();
        Map<Label, Long> cfLabels = new LinkedHashMap<>();
        if (specs.isEmpty()) {
            Label empty = new Label("此版本没有额外前置依赖");
            empty.getStyleClass().add("install-confirm-empty");
            installConfirmDeps.getChildren().add(empty);
        } else {
            addInstallConfirmSection("必须安装", "必须安装", "mod-dep-required",
                    specs, mrLabels, cfLabels);
            addInstallConfirmSection("可选安装", "可选安装", "mod-group-title",
                    specs, mrLabels, cfLabels);
            addInstallConfirmSection("已内置（作者已打包，无需安装）",
                    "内置的前置内容（已经由作者打包在文件中，无需另外下载）",
                    "mod-group-title", specs, mrLabels, cfLabels);
            addInstallConfirmSection("不兼容", "不兼容", "mod-dep-warn",
                    specs, mrLabels, cfLabels);
            addInstallConfirmSection("工具", "工具", "mod-group-title",
                    specs, mrLabels, cfLabels);
        }
        // 条数少时自适应、条数多时封顶 320：确认框高度不随前置条数增长
        double estimate = specs.isEmpty() ? 64 : 34.0 * 3 + 70.0 * specs.size();
        installConfirmScroll.setPrefHeight(Math.max(88, Math.min(320, estimate)));
        installConfirmScroll.setMinHeight(Region.USE_PREF_SIZE);
        installConfirmScroll.setMaxHeight(320);
        // 复用详情弹窗的反查令牌：关框 / 切版本后旧结果整份作废；失败保留 projectId 原名
        int token = ++depsRenderToken;
        resolveDepCardNames(mrLabels, cfLabels, token);
    }

    /** 确认框分区：按 spec.category 取组，显示标题可与规格分类不同（内置用 HMCL 原文案）。 */
    private void addInstallConfirmSection(String category, String title, String headStyle,
                                          List<DepCardSpec> all, List<DepLabel> mrLabels,
                                          Map<Label, Long> cfLabels) {
        List<DepCardSpec> group = groupOf(all, category);
        if (group.isEmpty()) {
            return;
        }
        Label head = new Label(title);
        head.getStyleClass().addAll("install-confirm-section", headStyle);
        head.setWrapText(true);
        installConfirmDeps.getChildren().add(head);
        for (DepCardSpec s : group) {
            Region card = (Region) depCard(s, mrLabels, cfLabels);
            card.setMinHeight(Region.USE_PREF_SIZE);
            card.setMaxHeight(Region.USE_PREF_SIZE);   // 卡片不竖长
            installConfirmDeps.getChildren().add(card);
        }
    }

    // ---------------------------------------------------------------- 1.2.9 QA 钩子

    /** QA：确认框此刻是否可见（真实节点读数，不看任何布尔镜像）。 */
    public static boolean isInstallConfirmVisibleForTests() {
        ShellView s = lastCreatedForTests;
        return s != null && s.installConfirmLayer != null && s.installConfirmLayer.isVisible();
    }

    /** 1.3.0 QA：模组详情弹窗此刻是否可见（读真实节点，不看任何布尔镜像）。 */
    public static boolean isModDetailVisibleForTests() {
        ShellView s = lastCreatedForTests;
        return s != null && s.modLayer != null && s.modLayer.isVisible();
    }

    /** 1.3.0 QA：详情标题当前文字，用来验证「查看」确实跳到了目标模组。 */
    public static String getModDetailTitleForTests() {
        ShellView s = lastCreatedForTests;
        return s == null || s.modDetailTitle == null ? "" : s.modDetailTitle.getText();
    }

    /** QA：确认框里实际的依赖卡数量（不含分区标题 / 空提示）。 */
    public static int getInstallConfirmDepCountForTests() {
        ShellView s = lastCreatedForTests;
        if (s == null || s.installConfirmDeps == null) {
            return 0;
        }
        int count = 0;
        for (Node node : s.installConfirmDeps.getChildren()) {
            if (node.getStyleClass().contains("dep-card")) {
                count++;
            }
        }
        return count;
    }

    /** QA：确认框独立容器的原始节点数（含分区标题；无前置时为 1 条空提示）。 */
    public static int getInstallConfirmNodeCountForTests() {
        ShellView s = lastCreatedForTests;
        return s == null || s.installConfirmDeps == null
                ? 0 : s.installConfirmDeps.getChildren().size();
    }

    /** QA：确认框标题 + 所有分区标题 / 卡片首行的逐字文本，用 | 分隔。 */
    public static String getInstallConfirmTextForTests() {
        ShellView s = lastCreatedForTests;
        if (s == null || s.installConfirmDeps == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (s.installConfirmTitle != null) {
            sb.append(s.installConfirmTitle.getText());
        }
        for (Node n : s.installConfirmDeps.getChildren()) {
            if (n instanceof Label l && l.getText() != null && !l.getText().isBlank()) {
                sb.append('|').append(l.getText());
            }
        }
        return sb.toString();
    }

    /** QA：前置区实际限高读数（layoutBounds 高度），验证 70+ 前置也不拉长。 */
    public static double getInstallConfirmDepsHeightForTests() {
        ShellView s = lastCreatedForTests;
        return s == null || s.installConfirmScroll == null
                ? 0 : s.installConfirmScroll.getLayoutBounds().getHeight();
    }

    /** QA：走真实「安装」按钮 fire（会触发原安装链）。 */
    public static void clickInstallConfirmOkForTests() {
        ShellView s = lastCreatedForTests;
        if (s != null && s.installConfirmOk != null) {
            s.installConfirmOk.fire();
        }
    }

    /** QA：走真实「取消」按钮 fire（不安装）。 */
    public static void clickInstallConfirmCancelForTests() {
        ShellView s = lastCreatedForTests;
        if (s != null && s.installConfirmCancel != null) {
            s.installConfirmCancel.fire();
        }
    }

    /** QA：列出详情弹窗当前版本行的名字，供 triggerInstallConfirmForTests 选目标。 */
    public String[] versionRowNamesForTests() {
        List<String> names = new ArrayList<>();
        if (modDetailVersions != null) {
            collectLabelTexts(modDetailVersions, names);
        }
        return names.toArray(new String[0]);
    }

    /** QA：按版本名找到真实版本行的安装按钮并 fire，走完整「点击 → 确认框」链路。 */
    public boolean triggerInstallForTests(String versionName) {
        if (versionName == null || modDetailVersions == null) {
            return false;
        }
        return findAndFireInstall(modDetailVersions, versionName);
    }

    /** QA 静态入口：按版本名程序化触发某版本的安装确认。 */
    public static boolean triggerInstallConfirmForTests(String versionName) {
        ShellView s = lastCreatedForTests;
        return s != null && s.triggerInstallForTests(versionName);
    }

    private static void collectLabelTexts(Node node, List<String> out) {
        if (node instanceof Label l && l.getText() != null && !l.getText().isBlank()) {
            out.add(l.getText());
        }
        if (node instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collectLabelTexts(child, out);
            }
        }
    }

    private static boolean findAndFireInstall(Node node, String name) {
        if (node instanceof Label l && name.equals(l.getText())) {
            javafx.scene.Parent p = l.getParent();
            while (p != null) {
                for (Node child : p.getChildrenUnmodifiable()) {
                    if (child instanceof Button b && b.getStyleClass().contains("btn-download")) {
                        b.fire();
                        return true;
                    }
                }
                p = p.getParent();
            }
        }
        if (node instanceof javafx.scene.Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                if (findAndFireInstall(child, name)) {
                    return true;
                }
            }
        }
        return false;
    }
    /** 内容分支：拼出下载直链后交给 {@link #installContentBytes} 落盘到 ContentStore。 */
    private void dispatchContentInstall(InstallCtx ctx, Button btn, ContentStore.Kind kind) {
        if (ctx.mrVersion() != null) {
            ModrinthClient.File f = ctx.mrVersion().primaryFile();
            if (f == null || f.url() == null || f.url().isBlank()) {
                toast("这个版本没有可下载的文件");
                return;
            }
            installContentBytes(() -> f.url(), f.filename(), ctx.displayName(), btn, kind,
                    ctx.coverUrl(), f.sha1(), f.size(), effectiveGameId());
        } else if (ctx.cfRef() != null) {
            CurseForgeClient.File f = ctx.cfRef().file();
            long modId = ctx.cfRef().modId();
            installContentBytes(() -> resolveCfUrl(modId, f), f.fileName(),
                    ctx.displayName(), btn, kind, ctx.coverUrl(), null, f.fileLength(),
                    effectiveGameId());
        } else {
            toast("没有可安装的文件");
        }
    }

    /** 关闭模组详情弹窗：同时重置状态机（内容类型 / 选中版本），避免下次打开误分派落盘。 */
    private void closeModDialog() {
        cancelAllRipples();   // 1.0.5
        if (modLayer != null) {
            hideOverlayLayer(modLayer, "mod-detail");
        }
        resetDetailState();
    }

    /** 一个小标签（分类 / 加载器 / 版本类型共用）。 */
    private static Label tagChip(String text, String extra) {
        Label chip = new Label(text);
        chip.getStyleClass().addAll("tag", extra);
        return chip;
    }

    // ---------------------------------------------------------------- 0.8.6 D3：卡片 / 版本行统一渲染器

    /** 卡片展示规格（Modrinth / CurseForge / 整合包共用）。sizeBytes 为整合包包体近似值。 */
    private record CardSpec(String title, String summary, String author, String source,
                            List<String> categories, long downloads, String dateUpdated,
                            String mcVersion, String loader, long sizeBytes) {

        /** 兼容 9 参构造：包体大小默认 0（在线搜索接口不返回该字段）。 */
        private CardSpec(String title, String summary, String author, String source,
                         List<String> categories, long downloads, String dateUpdated,
                         String mcVersion, String loader) {
            this(title, summary, author, source, categories, downloads, dateUpdated,
                    mcVersion, loader, 0L);
        }
    }

    private static CardSpec specOf(ModrinthClient.Hit h) {
        List<String> cats = h.categories() == null ? List.of() : h.categories();
        return new CardSpec(text(h.title()), text(h.description()), text(h.author()),
                h.source() == null ? "Modrinth" : h.source(),
                cats, h.downloads(), text(h.dateUpdated()), text(h.mcVersion()),
                loaderOf(cats));
    }

    private static CardSpec specOf(CurseForgeClient.Hit h) {
        List<String> cats = h.categories() == null ? List.of() : h.categories();
        return new CardSpec(text(h.name()), text(h.summary()), text(h.author()),
                h.source() == null ? "CurseForge" : h.source(),
                cats, h.downloads(), text(h.dateUpdated()), text(h.mcVersion()),
                loaderOf(cats));
    }

    private static CardSpec specOf(UnifiedMod m) {
        List<String> cats = m.categories() == null ? List.of() : m.categories();
        return new CardSpec(text(m.title()), text(m.summary()), text(m.author()),
                m.source() == null ? "Modrinth" : m.source(),
                cats, m.downloads(), text(m.dateUpdated()), text(m.mcVersion()),
                loaderOf(cats));
    }

    /** 分类里若带加载器 slug / 名称，就在卡片 R5 显示一个加载器徽章。 */
    private static String loaderOf(List<String> categories) {
        if (categories == null) {
            return "";
        }
        for (String c : categories) {
            if (c == null) {
                continue;
            }
            String key = c.toLowerCase(java.util.Locale.ROOT);
            if (key.equals("forge") || key.equals("fabric") || key.equals("neoforge")
                    || key.equals("quilt") || key.equals("liteloader")) {
                return modLoaderCn(key);
            }
        }
        return "";
    }

    /** 分类名统一走「Modrinth slug 中文 → CurseForge 英文类目中文」两级映射。 */
    private static String catLabel(String c) {
        String modrinth = modCatCn(c);
        return cfCatCn(modrinth.isEmpty() ? c : modrinth);
    }

    /** 标签区竖分隔（分类 tags │ 加载器 tags）。 */
    private static Region tagSep() {
        Region r = new Region();
        r.getStyleClass().add("mod-tag-sep");
        r.setMinWidth(1);
        r.setPrefWidth(1);
        r.setMaxWidth(1);
        r.setMinHeight(18);
        r.setPrefHeight(18);
        r.setMaxHeight(18);
        return r;
    }

    /** 统一 null 容错：null → 空串。 */
    private static String text(String s) {
        return s == null ? "" : s;
    }

    /** 一行 1px 分隔线（D3 分区用）。 */
    private static Region sepLine() {
        Region line = new Region();
        line.getStyleClass().add("mod-sep");
        line.setMinHeight(1);
        line.setPrefHeight(1);
        line.setMaxHeight(1);
        VBox.setMargin(line, new Insets(9, 0, 9, 0));
        return line;
    }

    /** 分区小标题（「简介」「相关链接」「版本列表」）。 */
    private static Label sectionTitle(String label) {
        Label l = new Label(label);
        l.getStyleClass().add("mod-section-title");
        return l;
    }

    private static Label statLabel(String label) {
        Label l = new Label(label);
        l.getStyleClass().add("mod-stat");
        return l;
    }

    /** 版本占位：null/空统一显示「未知版本」，超长做省略，避免挤坏行布局。 */
    private static String versionText(String version) {
        if (version == null || version.isBlank()) {
            return "未知版本";
        }
        String v = version.trim();
        return v.length() > 40 ? v.substring(0, 39) + "…" : v;
    }

    /**
     * 0.9.1 r3：版本徽章与「fabric」加载器徽章同款圆角胶囊（实底 + 1px 边框 + 高对比字色），
     * 不再用 page-desc 灰色小字。模组 / 整合包 / 材质包 / 光影 / 世界 五项统一走这里，
     * 显示方式和加载器标识一样清晰；胶囊里只放一个游戏版本号。
     */
    private static Label versionTag(String version) {
        Label l = tagChip(shortGameVersion(version), "tag-version");
        l.setMaxWidth(160);
        l.setTextOverrun(OverrunStyle.ELLIPSIS);
        return l;
    }

    /**
     * 只留一个游戏版本号，明了清晰：「1.20.1 · Forge 47.2.0」→「1.20.1」、
     * 「1.20.5–1.21.1」→「1.20.5」、「1.21+」→「1.21」；认不出才回退首个短 token。
     */
    private static String shortGameVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return "未知版本";
        }
        String v = raw.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("1\\.\\d+(?:\\.\\d+)?(?:\\s*[+\\-–])?").matcher(v);
        if (m.find()) {
            String one = m.group().replaceAll("\\s*[+\\-–]$", "").trim();
            if (!one.isEmpty()) {
                return one;
            }
        }
        int cut = v.length();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == ' ' || c == '·' || c == '(' || c == '（' || c == ',' || c == '、') {
                cut = i;
                break;
            }
        }
        String one = v.substring(0, cut).trim();
        if (one.isEmpty()) {
            one = v;
        }
        return one.length() > 16 ? one.substring(0, 15) + "…" : one;
    }

    /** 整合包版本：偏好 MC 版本 + 加载器版本，回退 versionId / 包名里的版本号，再不行 null。 */
    private static String packVersionText(ModpackRegistry.Pack pk) {
        String mc = pk.mcVersion();
        if (mc != null && !mc.isBlank()) {
            String loader = pk.loaderType();
            String lv = pk.loaderVersion();
            StringBuilder sb = new StringBuilder(mc);
            if (loader != null && !loader.isBlank() && !"原版".equals(loader)) {
                sb.append(" · ").append(loader);
                if (lv != null && !lv.isBlank()) {
                    sb.append(' ').append(lv);
                }
            }
            return sb.toString();
        }
        if (pk.versionId() != null && !pk.versionId().isBlank()) {
            return pk.versionId();
        }
        if (pk.name() != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("1\\.\\d+(?:\\.\\d+)?").matcher(pk.name());
            if (m.find()) {
                return m.group();
            }
        }
        return null;
    }

    /** 下载量格式化：1.2M / 12.3K / 原数。 */
    private static String humanDownloads(long n) {
        if (n >= 1_000_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0);
        }
        if (n >= 1_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fK", n / 1_000.0);
        }
        return String.valueOf(n);
    }

    /** ISO 时间 → 相对天数；解析不了就退回绝对日期，null / 空白返回空串。 */
    private static String relativeDate(String iso) {
        if (iso == null || iso.isBlank()) {
            return "";
        }
        try {
            java.time.Instant t = java.time.Instant.parse(iso);
            long days = java.time.Duration.between(t, java.time.Instant.now()).toDays();
            if (days < 0) {
                days = 0;
            }
            if (days == 0) {
                return "今天更新";
            }
            if (days < 30) {
                return days + " 天前更新";
            }
            if (days < 365) {
                return (days / 30) + " 个月前更新";
            }
            return (days / 365) + " 年前更新";
        } catch (Exception ex) {
            return modDate(iso);
        }
    }

    /**
     * 推荐列表卡片统一渲染（R1 头部 / R2 分隔线 / R3 简介 / R4 标签 / R5 统计）。
     * 4 个卡片入口只负责各自的 {@code loadCover} 调用与点击目标，布局全在这里，
     * 保证 Modrinth / CurseForge / 整合包三种卡片视觉完全一致。
     */
    private Node modCardView(ImageView cover, CardSpec s, boolean modpack, Runnable onOpen) {
        VBox card = new VBox(0);
        card.getStyleClass().addAll("card", "mod-card");
        card.setCursor(Cursor.HAND);
        card.setPadding(new Insets(12));
        card.setPrefWidth(360);
        card.setMinHeight(150);

        // R1 头部：64×64 封面 | 名称 + 来源徽章 / by 作者
        HBox head = new HBox(12);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setMinHeight(64);
        VBox info = new VBox(4);
        HBox titleRow = new HBox(6);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label(s.title());
        title.getStyleClass().add("card-title");
        title.setMaxWidth(modpack ? 130 : 180);
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        titleRow.getChildren().add(title);
        if (modpack) {
            titleRow.getChildren().add(tagChip("整合包", "tag-modpack"));
        }
        titleRow.getChildren().add(tagChip(s.source(), "tag-source"));
        Label author = new Label("by " + (s.author().isBlank() ? "未知作者" : s.author()));
        author.getStyleClass().add("mod-card-author");
        author.setMaxWidth(248);
        author.setTextOverrun(OverrunStyle.ELLIPSIS);
        info.getChildren().addAll(titleRow, author);
        head.getChildren().addAll(cover, info);

        // R2 分隔线
        Region sep = sepLine();

        // R3 简介（约两行高度）
        Label summary = new Label(s.summary());
        summary.getStyleClass().add("mod-card-summary");
        summary.setWrapText(true);
        summary.setMinHeight(38);
        summary.setPrefHeight(38);
        summary.setMaxHeight(38);

        // R4 分类标签（最多 3 个）
        FlowPane tags = new FlowPane(6, 4);
        tags.getStyleClass().add("mod-card-tags");
        tags.setMinHeight(22);
        List<String> cats = s.categories() == null ? List.of() : s.categories();
        int shown = 0;
        for (String c : cats) {
            if (shown >= 3) {
                break;
            }
            if (c == null || c.isBlank()) {
                continue;
            }
            String cn = catLabel(c);
            tags.getChildren().add(tagChip(cn.isEmpty() ? c : cn, "tag-cat"));
            shown++;
        }
        if (shown == 0) {
            tags.getChildren().add(tagChip("未分类", "tag-cat"));
        }

        // R5 统计行：普通模组 ↓ 下载量 │ ↻ 相对日期 │ MC 版本 │ 加载器徽章；
        // 整合包变体换成 ↓ 下载量 · MC 版本 · 加载器 · 包体大小。
        HBox stats = new HBox(10);
        stats.setAlignment(Pos.CENTER_RIGHT);
        stats.getStyleClass().add("mod-card-stats");
        stats.getChildren().add(statLabel("↓ " + humanDownloads(s.downloads())));
        if (!modpack) {
            String rel = relativeDate(s.dateUpdated());
            if (!rel.isBlank()) {
                stats.getChildren().add(statLabel("↻ " + rel));
            }
        }
        if (!s.mcVersion().isBlank()) {
            stats.getChildren().add(statLabel("MC " + s.mcVersion()));
        }
        if (!s.loader().isBlank()) {
            stats.getChildren().add(tagChip(s.loader(), "tag-loader-sm"));
        } else if (modpack) {
            stats.getChildren().add(statLabel("加载器 见详情"));
        }
        if (modpack) {
            // 在线搜索 hit 没有包体大小字段：不编造，提示到详情 §5 看主文件大小。
            stats.getChildren().add(statLabel(s.sizeBytes() > 0
                    ? "包体 " + LibFetcher.human(s.sizeBytes()) : "包体 见详情"));
        }

        card.getChildren().addAll(head, sep, summary, tags, stats);
        card.setOnMouseClicked(e -> onOpen.run());
        return card;
    }

    /** 1.2.8：Modrinth 版本行前置摘要（type ∈ required / optional / embedded / 其它）。 */
    private static DepSummary mrDepSummary(ModrinthClient.Version v) {
        int req = 0;
        int opt = 0;
        int emb = 0;
        int other = 0;
        for (ModrinthClient.Dependency d : ModrinthClient.dependenciesOf(v)) {
            if (d == null) {
                continue;
            }
            String type = d.type() == null || d.type().isBlank() ? "required" : d.type();
            switch (type) {
                case "required" -> req++;
                case "optional" -> opt++;
                case "embedded" -> emb++;
                default -> other++;   // incompatible / 未知类型
            }
        }
        return new DepSummary(req, opt, emb, other);
    }

    /** 1.2.8：CurseForge 文件行前置摘要（relationType 3 / 2 / 1·6 / 其余）。 */
    private static DepSummary cfDepSummary(CurseForgeClient.File f) {
        int req = 0;
        int opt = 0;
        int emb = 0;
        int other = 0;
        List<CurseForgeClient.FileDependency> ds =
                f == null || f.dependencies() == null ? List.of() : f.dependencies();
        for (CurseForgeClient.FileDependency d : ds) {
            if (d == null) {
                continue;
            }
            switch (d.relationType()) {
                case 3 -> req++;
                case 2 -> opt++;
                case 1, 6 -> emb++;
                default -> other++;   // 4=Tool / 5=Incompatible / 未知
            }
        }
        return new DepSummary(req, opt, emb, other);
    }

    /**
     * 1.2.8：版本行前置摘要（零联网，直接从版本自带 dependencies 统计）。
     * other = 不兼容 / 工具 / 未知类型；无依赖时不渲染徽章。
     */
    private record DepSummary(int required, int optional, int embedded, int other) {
        private static final DepSummary EMPTY = new DepSummary(0, 0, 0, 0);

        int total() {
            return required + optional + embedded + other;
        }

        boolean isEmpty() {
            return total() == 0;
        }

        /** 紧凑徽章文案：必装优先，如「必装2·选装1·内置1」。 */
        String badgeText() {
            if (isEmpty()) {
                return "";
            }
            StringBuilder b = new StringBuilder();
            if (required > 0) {
                b.append("必装").append(required);
            }
            if (optional > 0) {
                if (b.length() > 0) {
                    b.append("·");
                }
                b.append("选装").append(optional);
            }
            if (embedded > 0) {
                if (b.length() > 0) {
                    b.append("·");
                }
                b.append("内置").append(embedded);
            }
            if (other > 0) {
                if (b.length() > 0) {
                    b.append("·");
                }
                b.append("其它").append(other);
            }
            return b.toString();
        }
    }

    /** 版本行展示规格（Modrinth / CurseForge / 整合包共用）。 */
    private record VersionRowSpec(String badgeText, String badgeStyle, String name,
                                  List<String> loaders, String date, long sizeBytes,
                                  String fileName, List<String> extraStats,
                                  String buttonText, boolean installed,
                                  java.util.function.Consumer<Button> onInstall,
                                  DepSummary deps) {
    }

    /**
     * 统一版本行：徽章(44) | 名称 + 加载器/日期/大小/文件名 | gap | 安装按钮(72)。
     * CF 侧同样补齐加载器徽章、稳定徽章与文件大小，两源字段完全一致。
     */
    private Node versionRowView(VersionRowSpec s) {
        HBox row = new HBox(10);
        row.getStyleClass().addAll("card", "mod-version-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 12, 8, 12));

        Label badge = tagChip(s.badgeText(), s.badgeStyle());
        badge.getStyleClass().add("mod-version-badge");
        badge.setMinWidth(44);
        badge.setPrefWidth(44);

        Label name = new Label(s.name() == null ? "" : s.name());
        name.getStyleClass().add("mod-version-name");
        name.setMinWidth(Region.USE_PREF_SIZE);
        name.setWrapText(true);
        // 0.9.2：原来单行 HBox 塞不下时，JavaFX 会把收缩量按比例摊给每一个 statLabel，
        // Labeled 默认 ELLIPSIS 于是日期 / 包体 / 文件数 / 加载器全变成孤立的「...」。
        // 改 FlowPane + 每个子节点 USE_PREF_SIZE：放不下就换行，绝不压扁文字。
        FlowPane sub = new FlowPane(Orientation.HORIZONTAL, 8, 4);
        sub.setAlignment(Pos.CENTER_LEFT);
        List<String> loaders = s.loaders() == null ? List.of() : s.loaders();
        for (String l : loaders) {
            Label chip = tagChip(modLoaderCn(l), "tag-loader-sm");
            chip.setMinWidth(Region.USE_PREF_SIZE);
            sub.getChildren().add(chip);
        }
        if (s.installed()) {
            Label chip = tagChip("已装", "tag-release");
            chip.setMinWidth(Region.USE_PREF_SIZE);
            sub.getChildren().add(chip);
        }
        // 1.2.8：每行直接显示前置摘要，243 个版本不用逐个点开看；塞进已有 sub，不新增行
        DepSummary deps = s.deps() == null ? DepSummary.EMPTY : s.deps();
        if (!deps.isEmpty()) {
            Label chip = tagChip(deps.badgeText(), "mod-version-dep-badge");
            if (deps.required() > 0) {
                chip.getStyleClass().add("required");   // 必装醒目
            }
            chip.setMinWidth(Region.USE_PREF_SIZE);
            sub.getChildren().add(chip);
        }
        if (s.date() != null && !s.date().isBlank()) {
            Label l = statLabel(s.date());
            l.setMinWidth(Region.USE_PREF_SIZE);
            sub.getChildren().add(l);
        }
        if (s.sizeBytes() > 0) {
            Label l = statLabel(LibFetcher.human(s.sizeBytes()));
            l.setMinWidth(Region.USE_PREF_SIZE);
            sub.getChildren().add(l);
        }
        if (s.fileName() != null && !s.fileName().isBlank()) {
            Label l = statLabel(s.fileName());
            l.setMinWidth(Region.USE_PREF_SIZE);
            sub.getChildren().add(l);
        }
        for (String extra : s.extraStats() == null ? List.<String>of() : s.extraStats()) {
            if (extra != null && !extra.isBlank()) {
                Label l = statLabel(extra);
                l.setMinWidth(Region.USE_PREF_SIZE);
                sub.getChildren().add(l);
            }
        }
        VBox text = new VBox(3, name, sub);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMaxWidth(Double.MAX_VALUE);
        sub.setMinWidth(0);

        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Button install = new Button(s.buttonText());
        install.getStyleClass().add("btn-download");
        install.setMinHeight(30);
        install.setMinWidth(72);
        install.setPrefWidth(72);
        // installed 只加「已装」徽章，不禁用按钮：用户仍可换版本 / 重装（保留原有能力）。
        install.setOnAction(e -> s.onInstall().accept(install));
        row.getChildren().addAll(badge, text, gap, install);
        return row;
    }

    /** 版本类型短徽章（固定 44px 用短词）。 */
    private static String shortTypeCn(String t) {
        if (t == null) {
            return "其他";
        }
        return switch (t) {
            case "release" -> "稳定";
            case "beta" -> "测试";
            case "alpha" -> "快照";
            default -> t.length() > 4 ? t.substring(0, 4) : t;
        };
    }

    /** CF releaseType 数字 → Modrinth 同款类型 key（1=稳定 / 2=测试 / 3=快照）。 */
    private static String cfTypeKey(int type) {
        return switch (type) {
            case 2 -> "beta";
            case 3 -> "alpha";
            default -> "release";
        };
    }

    /** 从 CF 的 gameVersions（混着 "Forge"/"Fabric" 与 MC 版本号）里挑已知加载器。 */
    private static List<String> loadersFromGameVersions(List<String> games) {
        List<String> out = new ArrayList<>();
        if (games == null) {
            return out;
        }
        for (String g : games) {
            if (g == null) {
                continue;
            }
            String loader = switch (g.toLowerCase(java.util.Locale.ROOT)) {
                case "forge" -> "forge";
                case "fabric" -> "fabric";
                case "neoforge" -> "neoforge";
                case "quilt" -> "quilt";
                default -> null;
            };
            if (loader != null && !out.contains(loader)) {
                out.add(loader);
            }
        }
        return out;
    }

    /** 版本分组头：MC 版本 + 推荐徽章 + 该组版本数。0.8.9：兜底组不加「Minecraft 」前缀。 */
    private Node groupHeader(String mc, boolean recommend, int count) {
        HBox h = new HBox(8);
        h.setAlignment(Pos.CENTER_LEFT);
        h.getStyleClass().add("mod-group-head");
        String head = (mc == null || FALLBACK_MC_GROUP.equals(mc))
                ? FALLBACK_MC_GROUP : "Minecraft " + mc;
        Label t = new Label(head);
        t.getStyleClass().add("mod-group-title");
        h.getChildren().add(t);
        if (recommend) {
            h.getChildren().add(tagChip("推荐", "tag-release"));
        }
        h.getChildren().add(statLabel(count + " 个版本"));
        return h;
    }

    /** 项目外链键 → 中文按钮名。 */
    private static String modCatLinkLabel(String key) {
        return switch (key) {
            case "issues" -> "问题反馈";
            case "source" -> "源码";
            case "wiki" -> "Wiki";
            case "website" -> "官网";
            case "discord" -> "Discord";
            default -> key;
        };
    }

    // ---------------------------------------------------------------- Mod 中心：整合包 tab

    /**
     * 整合包 tab：上半部在 Modrinth 上逛整合包（搜 / 排序 / 分类 / 封面卡片 + 详情弹窗装），
     * 下半部保留「导入本地 .mrpack」入口（朋友发的包、或离线发行版）。
     *
     * <p>参考 HMCL：封面卡片网格 + 排序（下载量 / 最新发布 / 最近更新）+ 分类胶囊，
     * 点卡片开详情弹窗（复用模组详情弹窗的壳）按游戏版本分组版本，每行「安装整合包」会
     * 下载该 .mrpack 后复用 {@link ModpackImporter#run} 全自动导入。
     */
    private Node buildModpackTab() {
        VBox box = new VBox(12);
        box.getStyleClass().add("mod-tab-body");

        Label desc = new Label("挑一个整合包点开看详情，点「安装」即可装好；"
                + "也可导入本地整合包文件。");
        desc.getStyleClass().add("page-desc");
        desc.setWrapText(true);

        // 本地导入按钮（保留 GBL 原有的离线发行版入口）
        Button pick = new Button("导入本地整合包");
        pick.setGraphic(Icons.create(Icons.DOWNLOAD, 15));
        pick.setGraphicTextGap(9);
        pick.getStyleClass().add("btn-download");
        pick.setMinHeight(38);
        pick.setOnAction(e -> chooseMrpack());

        // 1.0.8：筛选条口径对齐 buildContentTab（资源包 / 光影 / 世界），模组页不动
        modpackSearchField = new TextField();
        modpackSearchField.getStyleClass().add("version-search");
        modpackSearchField.setPromptText("搜「整合包」…（支持中文别名）");
        modpackSearchField.setPrefHeight(34);
        modpackSearchField.setMaxWidth(420);
        HBox.setHgrow(modpackSearchField, Priority.ALWAYS);
        Button search = new Button("搜索");
        search.getStyleClass().add("btn-download");
        search.setMinHeight(34);
        search.setOnAction(e -> refreshModpackTab());
        modpackSearchDebounce = new PauseTransition(Duration.millis(400));
        modpackSearchDebounce.setOnFinished(e -> refreshModpackTab());
        modpackSearchField.textProperty().addListener((obs, oldText, newText) -> {
            if (newText != null && !newText.isBlank()) {
                modpackSearchDebounce.playFromStart();
            } else if (modpackSearchDebounce != null) {
                modpackSearchDebounce.stop();
                if (modpackResultBox != null) {
                    refreshModpackTab();
                }
            }
        });

        modpackSortBox = new ComboBox<>();
        wireDarkPopup(modpackSortBox);   // 0.9.1：深色下拉补底
        modpackSortBox.getStyleClass().add("setting-select");
        modpackSortBox.getItems().setAll("下载量", "最新发布", "最近更新");
        modpackSortBox.getSelectionModel().selectFirst();
        modpackSortBox.setPrefWidth(120);
        modpackSortBox.setOnAction(e -> refreshModpackTab());

        Button refresh = new Button("刷新");
        refresh.getStyleClass().add("btn-ghost");
        refresh.setMinHeight(34);
        refresh.setOnAction(e -> refreshModpackTab());

        Button openFolder = new Button("打开文件夹");
        openFolder.getStyleClass().add("btn-ghost");
        openFolder.setMinHeight(34);
        openFolder.setOnAction(e -> {
            // 整合包没有 ContentStore.dir 那样的公开常量；这里与 ModpackRegistry.DIR 同源
            // （~/.gbl/modpacks，登记簿 / 导入记录就放这里），与资源包页的打开文件夹行为对齐。
            Path packDir = Paths.get(System.getProperty("user.home"), ".gbl", "modpacks");
            try {
                Files.createDirectories(packDir);
            } catch (IOException ignored) {
                // reveal 对不存在的路径自己打日志，不崩
            }
            com.mclauncher.Platform.reveal(packDir);
        });

        HBox tools = new HBox(10, modpackSearchField, search, modpackSortBox, refresh, openFolder);
        tools.setAlignment(Pos.CENTER_LEFT);
        tools.getStyleClass().add("mod-tools");
        tools.setMaxWidth(MOD_CONTENT_WIDTH);

        modpackCategoryRow = new FlowPane(8, 6);
        modpackCategoryRow.getStyleClass().add("mod-cat-row");
        modpackSelectedCats = new ArrayList<>();
        buildModpackCategoryChips();

        modpackResultCount = new Label("正在加载推荐整合包…");
        modpackResultCount.getStyleClass().add("page-desc");
        modpackResultBox = new FlowPane();
        modpackResultBox.getStyleClass().add("mod-grid");
        modpackResultBox.setHgap(12);
        modpackResultBox.setVgap(12);
        modpackResultBox.setPrefWrapLength(1140);
        // 0.9.9 r4：撤掉整块位图缓存。网格高度 3200~6400px 在 150% 缩放下达 8~16M 像素、超 d3d 的 4096 纹理上限，
        // 且 hover / 封面回填都会让整张位图重画；整窗阴影摘掉后这笔缓存的边际收益为负。
        modpackResultBox.setCache(false);

        // 本地导入区用一条分隔提示与上面的浏览区分开
        Label localHint = new Label("或导入电脑里的整合包文件：");
        localHint.getStyleClass().add("page-desc");

        box.getChildren().addAll(desc, tools, modpackCategoryRow, modpackResultCount,
                modpackResultBox, localHint, pick);
        // 首次进页就拉一波（切 tab 时 refreshModCenter 也会再触发，这里保证第一次就有人）
        refreshModpackTab();
        return box;
    }

    /** 整合包分类胶囊（Modrinth 整合包分类的子集）：点一下切换选中（可多选），驱动重新拉取。 */
    private void buildModpackCategoryChips() {
        String[][] defs = {
                {"冒险", "adventure"}, {"科技", "tech"}, {"魔法", "magic"}, {"性能", "performance"},
                {"原版", "vanilla"}, {"大杂烩", "kitchen-sink"}, {"地图", "map"}, {"PvP", "pvp"},
                {"任务", "quest"}, {"实用", "utility"}, {"装饰", "decor"}, {"社交", "social"}};
        for (String[] d : defs) {
            Label chip = new Label(d[0]);
            chip.getStyleClass().addAll("filter-chip", "mod-cat-chip");
            final String slug = d[1];
            chip.setUserData(slug);
            chip.setOnMouseClicked(e -> {
                if (chip.getStyleClass().contains("on")) {
                    chip.getStyleClass().remove("on");
                    modpackSelectedCats.remove(slug);
                } else {
                    chip.getStyleClass().add("on");
                    if (!modpackSelectedCats.contains(slug)) {
                        modpackSelectedCats.add(slug);
                    }
                }
                refreshModpackTab();
            });
            modpackCategoryRow.getChildren().add(chip);
        }
    }

    /** 当前选中的整合包分类 slug 列表（副本，避免外部改到内部状态）。 */
    private List<String> selectedModpackCategories() {
        return modpackSelectedCats == null ? List.of() : new ArrayList<>(modpackSelectedCats);
    }

    /** 刷新整合包浏览页：搜 / 排 / 分类都走这，复用模组详情弹窗的壳展示。 */
    private void refreshModpackTab() {
        if (modpackResultBox == null || modpackSearchField == null) {
            return;   // 这一页还没建出来
        }
        // 0.9.4 r2 B1/A3：与其它 tab 同口径——发请求前不再 clear()（0.9.1 的教训：请求前清空
        // 就是 UI 卡顿来源），旧卡一直留到新结果回来由 appendCardsInBatches 原子替换。
        // seq 判本次刷新是否作废、epoch 判是否已切页，两者都在回填与每批开头复核。
        final long seq = ++modpackRefreshSeq;
        final long epochAtStart = contentEpoch;
        String query = modpackSearchField.getText() == null ? "" : modpackSearchField.getText().trim();
        List<String> cats = selectedModpackCategories();
        String sort = modpackSortBox == null ? "下载量" : modpackSortBox.getValue();
        modpackResultCount.setText(query.isBlank() ? "正在加载推荐整合包…" : "正在搜「" + query + "」…");
        Thread work = new Thread(() -> {
            try {
                List<ModrinthClient.Hit> hits =
                        searchModrinthAliased(query, 48, sort, cats, "modpack");
                javafx.application.Platform.runLater(() -> {
                    FlowPane container = modpackResultBox;
                    // 序号 / 容器 / 切页 epoch 任一不符就整份丢弃（附加条件，不替换 seq 语义）
                    if (seq != modpackRefreshSeq || container == null) {
                        return;
                    }
                    if (epochAtStart != contentEpoch) return;
                    if (hits.isEmpty()) {
                        modpackResultCount.setText(query.isBlank() ? "暂时没有推荐整合包，稍后重试"
                                : "没搜到，换个关键词");
                        container.getChildren().setAll(modCenterEmpty(
                                query.isBlank() ? "暂时没有推荐整合包" : "没有匹配的整合包"));
                        return;
                    }
                    modpackResultCount.setText((query.isBlank() ? "推荐整合包" : "搜索结果")
                            + " · " + hits.size() + " 个" + aliasHint(query));
                    // 0.9.4 r2 B1：按 appendCardsInBatches 的真实签名传「数据 + 卡片工厂」，
                    // 每批 12 张跨帧建卡、第一批 setAll 原子换旧卡，不再一帧 for 循环 add 48 张。
                    appendCardsInBatches(container, hits,
                            this::modpackCoverCard,
                            () -> seq == modpackRefreshSeq && epochAtStart == contentEpoch
                                    && modpackResultBox == container);
                });
            } catch (IOException ex) {
                javafx.application.Platform.runLater(() -> {
                    if (seq != modpackRefreshSeq || modpackResultBox == null) return;
                    if (epochAtStart != contentEpoch) return;
                    modpackResultCount.setText("加载失败：" + ex.getMessage());
                    modpackResultBox.getChildren().setAll(
                            modCenterEmpty("网络抽风了，稍后重试"));
                });
            }
        }, "gbl-modpack");
        work.setDaemon(true);
        work.start();
    }

    /** 一张整合包卡片：D3 统一渲染器的 modpack 变体（R1 带「整合包」徽章）。 */
    private Node modpackCoverCard(ModrinthClient.Hit hit) {
        ImageView cover = new ImageView();
        cover.getStyleClass().add("mod-cover");
        cover.setFitWidth(64);
        cover.setFitHeight(64);
        loadCover(cover, hit.iconUrl(), 112);
        return modCardView(cover, specOf(hit), true, () -> openModpackDialog(hit));
    }

    /** 打开整合包详情弹窗：复用模组详情弹窗的壳，先填骨架 + 封面，再后台拉详情与版本。 */
    private void openModpackDialog(ModrinthClient.Hit hit) {
        if (modLayer == null) {
            return;
        }
        // 0.8.8 ①：四个 open* 入口全部 resetDetailState()（S16 / R-03-C1），清旧反查令牌。
        resetDetailState();
        loadCover(modDetailCover, hit.iconUrl(), 144);
        modDetailTitle.setText(hit.title());
        modDetailSource.setText("Modrinth · 整合包");
        modDetailMeta.setText("by " + (text(hit.author()).isBlank() ? "未知作者" : hit.author())
                + " · Modrinth");
        modDetailDesc.setText(text(hit.description()));
        modDetailExtra.setVisible(false);
        modDetailExtra.setManaged(false);
        modDetailTags.getChildren().clear();
        modDetailStats.getChildren().clear();
        modDetailStats.getChildren().add(statLabel("↓ " + humanDownloads(hit.downloads())));
        modDetailLinks.getChildren().clear();
        modDetailDeps.setVisible(false);
        modDetailVersions.getChildren().clear();
        modDetailVersionHead.setText("版本列表 · 正在加载…");
        modDetailStatus.setText("正在加载详情…");
        cancelActiveRipplesBeforeOpenModal();   // 1.0.6
        showOverlayLayer(modLayer, "mod-detail");
        javafx.application.Platform.runLater(this::installFloatScrollBars);
        Thread work = new Thread(() -> {
            try {
                ModrinthClient.Project p = ModrinthClient.project(hit.projectId());
                List<ModrinthClient.Version> vers = ModrinthClient.allVersions(hit.projectId());
                javafx.application.Platform.runLater(() -> populateModpackDialog(hit, p, vers));
            } catch (IOException ex) {
                javafx.application.Platform.runLater(() ->
                        modDetailStatus.setText("详情加载失败：" + ex.getMessage()));
            }
        }, "gbl-modpack-detail");
        work.setDaemon(true);
        work.start();
    }

    /** 填充整合包详情弹窗（D3 分区）：§2 带包体摘要，§5 显示 MC/加载器/文件数/包体。 */
    private void populateModpackDialog(ModrinthClient.Hit hit, ModrinthClient.Project p,
                                       List<ModrinthClient.Version> vers) {
        loadCover(modDetailCover, p.iconUrl() != null ? p.iconUrl() : null, 144);
        modDetailTitle.setText(p.title());
        modDetailSource.setText("Modrinth · 整合包");
        modDetailMeta.setText("by " + (text(hit.author()).isBlank() ? "未知作者" : hit.author())
                + " · Modrinth");
        modDetailDesc.setText(text(p.description()));

        // §1 标签区：分类 tags │ 竖分隔 │ 加载器 tags 分组
        modDetailTags.getChildren().clear();
        List<String> cats = p.categories() == null ? List.of() : p.categories();
        for (String c : cats) {
            String cn = catLabel(c);
            modDetailTags.getChildren().add(tagChip(cn.isEmpty() ? c : cn, "tag-cat"));
        }
        java.util.LinkedHashSet<String> loaders = new java.util.LinkedHashSet<>();
        for (ModrinthClient.Version v : vers) {
            if (v.loaders() != null) {
                loaders.addAll(v.loaders());
            }
        }
        if (!cats.isEmpty() && !loaders.isEmpty()) {
            modDetailTags.getChildren().add(tagSep());
        }
        for (String l : loaders) {
            modDetailTags.getChildren().add(tagChip(modLoaderCn(l), "tag-loader"));
        }
        if (modDetailTags.getChildren().isEmpty()) {
            modDetailTags.getChildren().add(tagChip("未分类", "tag-cat"));
        }

        // §1 统计行
        modDetailStats.getChildren().clear();
        modDetailStats.getChildren().add(statLabel("↓ " + humanDownloads(p.downloads())));
        String rel = relativeDate(hit.dateUpdated());
        if (!rel.isBlank()) {
            modDetailStats.getChildren().add(statLabel("↻ " + rel));
        }
        if (!text(hit.mcVersion()).isBlank()) {
            modDetailStats.getChildren().add(statLabel("MC " + hit.mcVersion()));
        }

        // §2 补充摘要：在线接口不返回 .mrpack 清单，如实说明「包含 N 个模组」需导入后读。
        // ModpackImporter.Manifest 实测含 files() / totalBytes()，导入流程里能给出准确值。
        modDetailExtra.setText("下方「前置依赖」可点开查看每个模组（必须安装/可选/已内置/不兼容）；"
                + "下面的大小是压缩包的近似值。");
        modDetailExtra.setVisible(true);
        modDetailExtra.setManaged(true);

        // §3 相关链接
        modDetailLinks.getChildren().clear();
        String modrinthUrl = "https://modrinth.com/modpack/" + (p.slug() != null ? p.slug() : p.id());
        Hyperlink mrl = new Hyperlink("Modrinth");
        mrl.getStyleClass().add("link-btn");
        mrl.setOnAction(e -> openExternal(modrinthUrl));
        modDetailLinks.getChildren().add(mrl);
        if (p.links() != null) {
            for (Map.Entry<String, String> e : p.links().entrySet()) {
                String key = e.getKey();
                if (key.equals("issues") || key.equals("source") || key.equals("wiki")
                        || key.equals("website") || key.equals("discord")) {
                    Hyperlink hl = new Hyperlink(modCatLinkLabel(key));
                    hl.getStyleClass().add("link-btn");
                    hl.setOnAction(ev -> openExternal(e.getValue()));
                    modDetailLinks.getChildren().add(hl);
                }
            }
        }
        // §4 前置依赖（r9 覆盖）：版本接口 dependencies 直接给出所有前置（含 70+ embedded），
        // 无需下载 96MB 的 .mrpack。选推荐版本并先设 selectedVersion 令牌，再渲染依赖区。
        ModrinthClient.Version rec = null;
        if (vers != null && !vers.isEmpty()) {
            final String eg4 = effectiveGameId();
            for (ModrinthClient.Version v : vers) {
                List<String> gv = v.gameVersions() == null ? List.of() : v.gameVersions();
                if (gv.contains(eg4)) {
                    rec = v;   // allVersions 按时间倒序，首个匹配即最新推荐版
                    break;
                }
            }
            if (rec == null) {
                rec = vers.get(0);
            }
        }
        if (rec != null) {
            selectedVersion = rec;
            populateDeps(ModrinthClient.dependenciesOf(rec));
        } else {
            modDetailDeps.setVisible(false);
            if (modDepsTitle != null) {
                modDepsTitle.setVisible(false);
                modDepsTitle.setManaged(false);
            }
        }

        // §5 版本区：按 MC 版本分组，组头带推荐徽章与版本数
        modDetailVersionHead.setText("版本列表 · 共 " + vers.size() + " 个 · 按 Minecraft 版本分组");
        modDetailVersions.getChildren().clear();
        Map<String, List<ModrinthClient.Version>> groups = new LinkedHashMap<>();
        for (ModrinthClient.Version v : vers) {
            List<String> games = v.gameVersions() == null ? List.of() : v.gameVersions();
            for (String g : games) {
                if (g == null || g.isBlank()) {
                    continue;
                }
                groups.computeIfAbsent(g, k -> new ArrayList<>()).add(v);
            }
        }
        List<String> keys = new ArrayList<>(groups.keySet());
        final String eg = effectiveGameId();   // 0.8.8 Q5：推荐判定用单一取值口，只算一次
        keys.sort((a, b) -> {
            if (a.equals(eg)) {
                return -1;
            }
            if (b.equals(eg)) {
                return 1;
            }
            return com.mclauncher.AppVersion.compare(b, a);
        });
        for (String g : keys) {
            boolean recommend = g.equals(eg);
            modDetailVersions.getChildren().add(groupHeader(g, recommend, groups.get(g).size()));
            VBox grpBox = new VBox(6);
            for (ModrinthClient.Version v : groups.get(g)) {
                grpBox.getChildren().add(versionRow(v, true, false, dialogCover(p, hit)));
            }
            modDetailVersions.getChildren().add(grpBox);
        }
        // §6 底部状态条
        modDetailStatus.setText("点「安装整合包」会下载该版本并自动导入到游戏目录");
    }

    /** 安装某个整合包版本：下 .mrpack 到临时目录 → 读 Manifest → 复用 ModpackImporter 全流程导入。 */
    private void installModpackVersion(ModrinthClient.Version v, Button btn, String coverUrl) {
        ModrinthClient.File f = v.primaryFile();
        if (f == null || f.url() == null || f.url().isBlank()) {
            toast("这个版本没有可下载的文件");
            return;
        }
        btn.setDisable(true);
        modDetailStatus.setText("正在下载整合包 " + v.name() + " …");
        final String mpTitle = v.name();
        // 0.9.2：.mrpack 下载与导入阶段共用一个取消旗标，别再各 new 一个孤立对象。
        final AtomicBoolean cancelled = new AtomicBoolean();
        // 0.9.0 R-01/R-02：整合包安装也进 DownloadCenter，异常一律收敛成 FAILED，不再卡死。
        // .mrpack 自身字节进度走 FileFetch.ProgressListener，导入阶段字节进度走 Listener.onBytes。
        DownloadCenter.DEFAULT.submit(mpTitle, DownloadCenter.Category.MODPACK, coverUrl,
            (DownloadCenter.Progress p) -> {
                // 两处字节进度共用一个出口：既把真实字节数喂给 DownloadCenter，
                // 也在队列「移出」（p.onProgress 抛 CancellationException）时把本地 cancelled
                // 置上——ModpackImporter 每步都问这个旗标，否则导入阶段会停不下来。
                final DownloadCenter.Progress byteProgress = (done, len) -> {
                    try {
                        p.onProgress(done, len);
                    } catch (java.util.concurrent.CancellationException ce) {
                        cancelled.set(true);
                        throw ce;
                    }
                };
                Path tmp = null;
                final boolean[] failed = {false};
                try {
                    Path tmpDir = Files.createTempDirectory("gbl-mrpack");
                    tmp = tmpDir.resolve(f.filename() == null || f.filename().isBlank()
                            ? "pack.mrpack" : f.filename());
                    boolean ok = FileFetch.download(f.url(), tmp, f.sha1(), null,
                            cancelled, (done, len) -> byteProgress.onProgress(done, len));
                    if (!ok) {
                        javafx.application.Platform.runLater(() -> {
                            btn.setDisable(false);
                            modDetailStatus.setText("整合包没下下来，重试或换个版本");
                        });
                        throw new IOException("整合包没下下来，重试或换个版本");
                    }
                    ModpackImporter.Manifest manifest = ModpackImporter.read(tmp);

                    // r10：下载成功并读 Manifest 后直接开装，删除安装前确认弹窗。
                    ModpackImporter.run(tmp, manifest, cancelled,
                            new ModpackImporter.Listener() {
                                @Override
                                public void onStage(String text) {
                                    // DownloadCenter.Progress 没有 setNote，阶段文字只写详情弹窗，
                                    // 不硬塞进队列（避免污染字节进度）。
                                    javafx.application.Platform.runLater(() -> modDetailStatus.setText(text));
                                }

                                @Override
                                public void onBytes(long done, long total) {
                                    byteProgress.onProgress(done, total);
                                }

                                @Override
                                public void onFileDone(int done, int total, String fileName, boolean ok2) {
                                    // 文件个数不是字节数，别再喂给字节进度条；只更新详情弹窗文字。
                                    javafx.application.Platform.runLater(() -> modDetailStatus.setText(
                                            "正在导入整合包…（" + done + "/" + total + "）"
                                                    + (ok2 ? "" : " 「" + fileName + "」失败")));
                                }

                                @Override
                                public void onFinished(boolean ok3, String note) {
                                    javafx.application.Platform.runLater(() -> {
                                        btn.setDisable(false);
                                        modDetailStatus.setText(note);
                                        toast(note);
                                        if (ok3) {
                                            refreshModCenter();
                                            refreshGames();
                                            refreshGameManage();   // 0.8.8 ③：整合包装完管理页同步可见
                                        }
                                    });
                                    if (!ok3) {
                                        failed[0] = true;
                                    }
                                }
                            });
                    if (failed[0]) {
                        throw new IOException("整合包导入未完成");
                    }
                    return java.nio.file.Paths.get(f.filename() == null ? mpTitle : f.filename());
                } catch (Exception ex) {
                    javafx.application.Platform.runLater(() -> btn.setDisable(false));
                    throw ex;
                } finally {
                    // 清掉临时下载的 .mrpack，别在系统临时目录留垃圾
                    if (tmp != null) {
                        try {
                            Files.deleteIfExists(tmp);
                            Files.deleteIfExists(tmp.getParent());
                        } catch (IOException ignore) {
                            // 删不掉就算了，临时目录有系统兜底清理
                        }
                    }
                }
            });
    }

    /** 选文件 → 读 index → 确认卡。读不出来直接 toast 人话，不进确认流程。 */
    private void chooseMrpack() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选一个 .mrpack 整合包");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Modrinth 整合包", "*.mrpack", "*.zip"));
        java.io.File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        ModpackImporter.Manifest manifest;
        try {
            manifest = ModpackImporter.read(file.toPath());
        } catch (IOException ex) {
            toast("这个整合包读不了：" + ex.getMessage());
            return;
        }
        int optional = 0;
        for (ModpackImporter.MrFile one : manifest.files()) {
            if (one.optional()) {
                optional++;
            }
        }
        String loaderText = manifest.loaderType() == null
                ? "原版 / 未识别（暂不支持 Forge、NeoForge 整合包）"
                : manifest.loaderType().label() + " " + manifest.loaderVersion();
        // 0.8.8 Q3：导入提示目标与安装/读取统一取值口（无游戏时仍用清单 MC 版本兜底显示）。
        String importTarget = effectiveGameId();
        if (importTarget == null) {
            importTarget = manifest.mcVersion();
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        styleAlert(alert);
        alert.setTitle("导入整合包");
        alert.setHeaderText(manifest.name()
                + (manifest.versionId() == null ? "" : "  " + manifest.versionId()));
        alert.setContentText("MC 版本：" + manifest.mcVersion()
                + "\n加载器：" + loaderText
                + "\n文件数：" + manifest.files().size()
                + (optional > 0 ? "（其中 " + optional + " 个可选文件默认跳过）" : "")
                + "\n总大小：" + LibFetcher.human(manifest.totalBytes())
                + (manifest.summary() == null || manifest.summary().isBlank()
                ? "" : "\n简介：" + manifest.summary())
                + "\n\n会装到游戏「" + importTarget + "」上；已有文件内容不同会先备份再覆盖。");
        alert.showAndWait().ifPresent(button -> {
            if (button == ButtonType.OK) {
                runModpackImport(file.toPath(), manifest);
            }
        });
    }

    /** 起 daemon 线程跑导入；阶段文字与文件进度进状态条那一行。 */
    private void runModpackImport(Path mrpack, ModpackImporter.Manifest manifest) {
        modpackCancelled.set(false);
        modImportRow.setVisible(true);
        modImportRow.setManaged(true);
        modImportStage.setText("正在准备导入「" + manifest.name() + "」…");
        Thread work = new Thread(() -> {
            try {
                ModpackImporter.run(mrpack, manifest, modpackCancelled,
                        new ModpackImporter.Listener() {
                            @Override
                            public void onStage(String text) {
                                javafx.application.Platform.runLater(
                                        () -> modImportStage.setText(text));
                            }

                            @Override
                            public void onFileDone(int done, int total, String fileName,
                                                   boolean ok) {
                                javafx.application.Platform.runLater(() -> modImportStage.setText(
                                        "正在下载整合包文件…（" + done + "/" + total + "）"
                                                + (ok ? "" : " 「" + fileName + "」失败")));
                            }

                            /** 0.9.4：字节进度接线 —— 只在百分比整数变化时刷一次文字，别每字节刷爆 FX 队列。 */
                            private volatile int lastPct = -1;
                            @Override
                            public void onBytes(long doneBytes, long totalBytes) {
                                if (totalBytes <= 0) {
                                    return;
                                }
                                int pct = (int) (doneBytes * 100 / totalBytes);
                                if (pct == lastPct) {
                                    return;
                                }
                                lastPct = pct;
                                javafx.application.Platform.runLater(() -> modImportStage.setText(
                                        "正在下载整合包文件…（" + pct + "%）"));
                            }

                            @Override
                            public void onFinished(boolean ok, String note) {
                                javafx.application.Platform.runLater(() -> {
                                    toast(note);
                                    if (ok) {
                                        refreshModCenter();
                                        refreshGames();   // 游戏列表 / 首页跟着刷
                                        refreshGameManage();   // 0.8.8 ③：整合包装完管理页同步可见
                                    }
                                });
                            }
                        });
            } catch (Throwable ex) {
                // 0.9.4：RuntimeException / Error 也要接住，否则状态条会永久停在「正在准备导入…」。
                javafx.application.Platform.runLater(
                        () -> toast("导入失败：" + ex.getMessage()));
            } finally {
                // 0.9.4：无论成功 / 失败 / 未检查异常，都无条件收掉状态条。
                javafx.application.Platform.runLater(() -> {
                    modImportRow.setVisible(false);
                    modImportRow.setManaged(false);
                });
            }
        }, "gbl-mrpack");
        work.setDaemon(true);
        work.start();
    }

    /** 导入进度那一行：阶段文字 + 取消。没在导入时整行藏起来。 */
    private HBox modImportRow() {
        modImportStage = new Label();
        modImportStage.getStyleClass().add("page-desc");
        Button cancel = new Button("取消");
        cancel.getStyleClass().add("btn-ghost");
        cancel.setOnAction(e -> {
            modpackCancelled.set(true);
            modImportStage.setText("正在取消…（下好的文件会留着，重导入可续）");
        });
        HBox row = new HBox(10, modImportStage, cancel);
        row.setAlignment(Pos.CENTER_RIGHT);
        row.setVisible(false);
        row.setManaged(false);
        return row;
    }

    // ---------------------------------------------------------------- 账号页（v0.5.2）

    /**
     * 账号页：列出已加账号、显示当前选中的那个、提供「添加离线账号 / 设为当前 / 删除」。
     *
     * <p>本期只暴露「离线」一种类型（外置 little skin / 微软留 Type 占位，账号流程没做）。
     * 没有账号时启动游戏会被 {@link #startGame} 拦下，必须先到这里加一个。
     */
    private ScrollPane buildAccountPage() {
        VBox page = new VBox(16);
        page.getStyleClass().add("account-page");
        page.setPadding(new Insets(20));
        page.setMaxWidth(1180);

        Label title = new Label("账号");
        title.getStyleClass().add("page-title");
        Label desc = new Label("选一个账号才能进游戏。离线账号建议用英文字符 / 数字 / 下划线，长度不超过 16 个字符；"
                + "外置账号用 LittleSkin 用户名 + 密码登录（联机需服务器也支持 LittleSkin）；"
                + "微软账号用设备码登录（浏览器里登微软账号，可进官方 / 认微软认证的联机服，需能访问微软的网络）。");
        desc.getStyleClass().add("setting-desc");
        desc.setWrapText(true);
        desc.setMaxWidth(720);

        accountRows = new VBox(8);

        Button add = new Button("添加离线账号");
        add.setGraphic(Icons.create(Icons.ACCOUNT, 15));
        add.setGraphicTextGap(9);
        add.getStyleClass().add("btn-download");
        add.setMinHeight(38);
        add.setOnAction(e -> openAccountPrompt());

        Button addExt = new Button("添加外置账号");
        addExt.setGraphic(Icons.create(Icons.ACCOUNT, 15));
        addExt.setGraphicTextGap(9);
        addExt.getStyleClass().add("btn-download");
        addExt.setMinHeight(38);
        addExt.setOnAction(e -> openExternalPrompt());

        // v0.5.8：微软账号走设备码流（浏览器登录 + 后台轮询），和外置并排
        Button addMs = new Button("添加微软账号");
        addMs.setGraphic(Icons.create(Icons.ACCOUNT, 15));
        addMs.setGraphicTextGap(9);
        addMs.getStyleClass().add("btn-download");
        addMs.setMinHeight(38);
        addMs.setOnAction(e -> openMicrosoftPrompt());

        // v0.5.5：离线 / 外置两个「添加」按钮并排（外置走 little skin 用户名 + 密码登录）
        HBox addRow = new HBox(10);
        addRow.setAlignment(Pos.CENTER_LEFT);
        addRow.getChildren().addAll(add, addExt, addMs);

        // v0.5.3：添加按钮挪到「账号」标题下方，列表只放账号行
        page.getChildren().addAll(title, addRow, desc, accountRows);

        StackPage wrap = new StackPage(page);
        ScrollPane scroll = new ScrollPane(wrap);
        scroll.getStyleClass().add("content-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        refreshAccountPage();
        return scroll;
    }

    /** 重画账号列表（无账号时给一句引导）；同时刷新右下角账号卡文案。 */
    private void refreshAccountPage() {
        if (accountRows == null) {
            return;
        }
        accountRows.getChildren().clear();
        if (accountStore.accounts().isEmpty()) {
            Label empty = new Label("还没有账号。点「添加离线账号」建一个就能玩。");
            empty.getStyleClass().add("setting-desc");
            accountRows.getChildren().add(empty);
            refreshUserChip();
            return;
        }
        for (AccountStore.Account acc : accountStore.accounts()) {
            accountRows.getChildren().add(accountRow(acc));
        }
        refreshUserChip();
    }

    /** 一行账号：绿点标记当前（点非当前的绿点即设为当前）+ 名字 + 类型 + 删除。 */
    private HBox accountRow(AccountStore.Account acc) {
        boolean current = accountStore.current() != null
                && accountStore.current().id.equals(acc.id);
        HBox row = new HBox(12);
        row.getStyleClass().addAll("card", "account-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(14, 16, 14, 16));
        row.setMinHeight(64);

        // 头像：按该账号当前皮肤渲染头部
        row.getChildren().add(skinHeadView(acc, 40));

        // 绿点就是「当前」标记：当前账号整颗填实；非当前可点，点一下设为当前
        Circle dot = new Circle(7);
        dot.getStyleClass().setAll("account-dot");
        if (current) {
            dot.getStyleClass().add("on");
        } else {
            dot.setCursor(Cursor.HAND);
            dot.setOnMouseClicked(e -> {
                accountStore = accountStore.withCurrent(acc.id);
                accountStore.save();
                refreshAccountPage();
                refreshHero();
            });
        }

        VBox info = new VBox(4);
        info.setMinWidth(0);
        Label name = new Label(acc.name);
        name.getStyleClass().add("account-name");
        name.setMaxWidth(360);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        String typeLabel;
        if (acc.type == AccountStore.Type.LITTLESKIN) {
            typeLabel = "外置";
        } else if (acc.type == AccountStore.Type.MICROSOFT) {
            typeLabel = "微软";
        } else {
            typeLabel = "离线";
        }
        Label sub = new Label(current ? (typeLabel + " · 当前账号") : typeLabel);
        sub.getStyleClass().add("setting-desc");
        info.getChildren().addAll(name, sub);

        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);
        row.getChildren().addAll(dot, info, gap);

        // 1.1.3：LittleSkin 账号也给它入口，但进去是只读「服务器皮肤」卡片（无本地导入/导出）
        Button skinBtn = new Button(
                acc.type == AccountStore.Type.LITTLESKIN ? "服务器皮肤" : "皮肤");
        skinBtn.getStyleClass().add("btn-ghost");
        skinBtn.setMinHeight(34);
        skinBtn.setMinWidth(acc.type == AccountStore.Type.LITTLESKIN ? 92 : 60);
        skinBtn.setOnAction(e -> openSkinManager(acc));
        row.getChildren().add(skinBtn);

        Button del = new Button("删除");
        del.getStyleClass().add("btn-danger");
        del.setMinHeight(34);
        del.setOnAction(e -> removeAccount(acc.id));
        row.getChildren().add(del);
        return row;
    }

    private void removeAccount(String id) {
        accountStore = accountStore.withRemoved(id);
        accountStore.save();
        refreshAccountPage();
        refreshHero();
    }

    // ---------------------------------------------------------------- 设置页
    // v0.2 只做「零依赖」的两组：外观（主题 / 动效）与窗口行为（关闭即托盘）。
    // 启动与资源（游戏目录 / 内存 / JVM 参数）、账户、高级这三条线都要等下载器、
    // 参数拼装与登录接进来才有东西可配，到时照这里的骨架加组就行，不用动页面结构。

    /** 设置页的分组；ordinal 即按钮在分组栏里的顺序。 */
    private enum SettingGroup {
        APPEARANCE("外观"),
        WINDOW("窗口行为"),
        ADVANCED("高级");

        private final String label;

        SettingGroup(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private ScrollPane buildSettingsPage() {
        // 构造 PauseTransition 不会自动开始播放，先挂上回调，真正落盘靠 commitSettings 里的 playFromStart
        settingsSaveDebounce = new PauseTransition(Duration.millis(300));
        settingsSaveDebounce.setOnFinished(e -> flushSettings());

        VBox page = new VBox(16);
        page.setPadding(new Insets(20));

        // 不设内容宽上限：窗口放大后跟随其它页一样撑满，右边那片留白才不会像「没画完」。
        // 说明文字的宽度另由 .setting-row > VBox 的 max-width 收着，长句不会拉成一条。
        VBox content = new VBox(12);
        content.getChildren().addAll(
                buildSettingsHeader(), buildSettingsGroups(), buildSettingsDanger());
        page.getChildren().add(content);

        StackPage wrap = new StackPage(page);
        ScrollPane scroll = new ScrollPane(wrap);
        scroll.getStyleClass().add("content-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        return scroll;
    }

    private HBox buildSettingsHeader() {
        VBox left = new VBox(4);
        Label title = new Label("设置");
        title.getStyleClass().add("page-title");
        Label desc = new Label(settings.configCorrupted()
                ? "上次设置没能读取，已恢复默认；改任意一项即可保存。"
                : "外观与窗口行为的改动即时生效并保存。");
        desc.getStyleClass().add("page-desc");
        desc.setWrapText(true);
        left.getChildren().addAll(title, desc);

        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);
        settingsStatus = chip("已保存", "chip chip-accent");
        HBox box = new HBox(16);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(left, gap, settingsStatus);
        return box;
    }

    private Node buildSettingsGroups() {
        HBox seg = new HBox(8);
        seg.setAlignment(Pos.CENTER_LEFT);
        settingsGroupButtons.clear();
        for (SettingGroup group : SettingGroup.values()) {
            Pane button = segBtn(group.label());
            button.setOnMouseClicked(e -> {
                showSettingsGroup(group);
                button.requestFocus();      // 点完留在分组上，接着按方向键能继续切
            });
            settingsGroupButtons.add(button);
            seg.getChildren().add(button);
        }
        // 键盘也能切分组：焦点在某一枚药丸上时事件会冒到这层 HBox 上。
        // 药丸是自绘 Pane 不是 Button，天生吃不到方向键，只能在这里兜。
        seg.setOnKeyPressed(e -> {
            int step = e.getCode() == KeyCode.RIGHT ? 1 : e.getCode() == KeyCode.LEFT ? -1 : 0;
            if (step == 0) {
                return;
            }
            int current = settingsGroupButtons.indexOf(
                    settingsGroupButtons.stream()
                            .filter(b -> b.getStyleClass().contains("selected"))
                            .findFirst().orElse(null));
            int next = Math.floorMod(current + step, settingsGroupButtons.size());
            showSettingsGroup(SettingGroup.values()[next]);
            settingsGroupButtons.get(next).requestFocus();
            e.consume();
        });

        // 1.3.3：三个可展开区的宿主先建好，下面 buildCard 的参数从左到右求值时才能拿到非 null
        textPickerHost = pickerHost();
        accentPickerHost = pickerHost();
        wallpaperStripHost = buildWallpaperStripHost();

        // 「主题」一个下拉统管深浅和壁纸；选到「自定义壁纸」时，壁纸条就在下拉正下方展开。
        // 不再单列「背景」药丸行、也不再单列「自定义壁纸」行——那两处跟这个下拉说的是同一件事。
        appearanceGroup = buildCard("外观", "整套界面的配色、背景与动效。",
                rowWithPanel(row("主题",
                        "深浅两套配色，也可以换一张横屏图片当壁纸。选「跟随系统」就跟着电脑变。",
                        themeSelect()), wallpaperStripHost),
                rowWithPanel(row("文字颜色",
                        "标题、正文、次要提示、按钮字与输入框字一起换色，自动分三级明度。",
                        textColorControl()), textPickerHost),
                rowWithPanel(row("主题色",
                        "GBL 默认是绿色；改成别的颜色后，界面上所有绿色都会换成它。",
                        accentColorControl()), accentPickerHost),
                row("界面动效", "是否显示切页的淡入淡出效果。",
                        motionToggle()),
                row("按钮特效", "点按钮时泛起的一圈涟漪。关掉它点按钮就没有动画了。",
                        buttonEffectsToggle()));
        windowGroup = buildCard("窗口行为", "关闭按钮与启动后窗口的处理方式。",
                row("关闭时收进系统托盘",
                        "开启后点 × 只是把 GBL 收进托盘，点托盘图标再叫回来；"
                                + "关掉这项，点 × 就直接退出。",
                        trayToggle()),
                row("启动后关闭启动器",
                        "开启后进游戏时启动器会自动退出，"
                                + "不影响游戏。",
                        closeAfterLaunchToggle()),
                row("开机自启",
                        "开机后自动打开 GBL。",
                        bootAutostartToggle()));

        advancedGroup = buildCard("数据源 / 高级", "进阶设置。",
                cfKeyRow(), javaPathRow(),
                row("自动下载合适的 Java",
                        "游戏需要某个 Java 版本、本机又没有时，自动下载并安装它；"
                                + "关掉就用机器上现有的 Java 凑合，可能需要手动指定。",
                        autoDownloadJavaToggle()),
                row("自动更新",
                        "开启后自动提示新版本；关掉则只在手动检查时提示。",
                        autoUpdateToggle()),
                row("游戏内存 (MB)",
                        "给游戏分配的内存，一般不用改；内存小调小、想流畅调大。",
                        gameMemoryField()),
                row("并发下载数",
                        "同时下载的任务数，网络慢就调小。",
                        concurrentDownloadsField()),
                row("下载源",
                        "下载用的线路，切换后立即生效。",
                        downloadSourceSelect()),
                row("清理缓存",
                        "清空封面缓存，不影响已下载的游戏。",
                        clearCacheButton()),
                // 0.9.7：把「线路自动挑最快」和「有没有新版本」这两件原本只发生在后台的事摊到界面上
                row("出海线路",
                        "自动挑最快的下载线路；换网络后点「重新测速」。",
                        lineStatusRow()),
                row("版本与更新",
                        "当前版本。点「检查更新」可查新版。",
                        versionRow()));

        // 多组叠在一层里，切分组只改 visible/managed；必须先把默认那组点亮，
        // 否则 StackPane 里多张卡都 visible，会叠在一起
        settingsGroupBody = new StackPane(appearanceGroup, windowGroup, advancedGroup);
        showSettingsGroup(SettingGroup.APPEARANCE);
        // 1.3.3：外观的初始状态（主题下拉选中项 / 壁纸条 / 色点与取值）
        refreshAppearanceUi();

        VBox box = new VBox(12);
        box.getChildren().addAll(seg, settingsGroupBody);
        return box;
    }

    private void showSettingsGroup(SettingGroup group) {
        for (Node button : settingsGroupButtons) {
            button.getStyleClass().remove("selected");
        }
        settingsGroupButtons.get(group.ordinal()).getStyleClass().add("selected");
        appearanceGroup.setVisible(group == SettingGroup.APPEARANCE);
        appearanceGroup.setManaged(appearanceGroup.isVisible());
        windowGroup.setVisible(group == SettingGroup.WINDOW);
        windowGroup.setManaged(windowGroup.isVisible());
        advancedGroup.setVisible(group == SettingGroup.ADVANCED);
        advancedGroup.setManaged(advancedGroup.isVisible());
    }

    /**
     * 分组栏里的一枚药丸按钮。底色、描边、文字全归 CSS。
     *
     * <p>用 HBox 而不是 Pane：Pane 不排版子节点，之前靠手算 {@code layoutY=7} 把标签摞进药丸，
     * 字号一换就不在正中（截图里「外观 / 窗口行为」是偏上的）。HBox 会给子节点做垂直居中，
     * 左右内边距交给 padding，两边对称也不用再手算。
     */
    private static Pane segBtn(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("seg-btn-label");

        HBox button = new HBox(label);
        button.getStyleClass().add("seg-btn");
        button.setCursor(Cursor.HAND);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setPadding(new Insets(0, 16, 0, 16));
        button.setPrefHeight(33);
        button.setMinHeight(33);
        // Pane 默认不吃焦点，Tab 就走不进分组栏，得显式打开
        button.setFocusTraversable(true);
        return button;
    }

    private static VBox buildCard(String title, String hint, Node... rows) {
        VBox card = new VBox(0);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(14, 20, 14, 20));
        card.getChildren().add(cardHead(title, hint));
        for (Node row : rows) {
            card.getChildren().addAll(row, sep());
        }
        return card;
    }

    private static HBox cardHead(String title, String hint) {
        Label titleNode = new Label(title);
        titleNode.getStyleClass().add("card-title");
        Label hintNode = new Label(hint);
        hintNode.getStyleClass().add("setting-desc");
        VBox text = new VBox(3);
        text.getChildren().addAll(titleNode, hintNode);
        return new HBox(text);
    }

    /** 一行设置：左边标题 + 说明，右边控件（窄屏由 CSS/容器压到上下堆叠）。 */
    private static HBox row(String title, String desc, Node control) {
        Label titleNode = new Label(title);
        titleNode.getStyleClass().add("setting-title");
        Label descNode = new Label(desc);
        descNode.getStyleClass().add("setting-desc");
        descNode.setWrapText(true);

        VBox text = new VBox(3);
        text.getChildren().addAll(titleNode, descNode);
        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);

        HBox row = new HBox(20);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("setting-row");
        row.getChildren().addAll(text, gap, control);
        return row;
    }

    /** 组内分隔线。Region 默认不占位，必须显式给 1px 高，否则这一行等于没插。 */
    private static Region sep() {
        Region line = new Region();
        line.getStyleClass().add("setting-sep");
        return line;
    }

    // ------------------------------------------------------------ 控件与落盘

    // ------------------------------------------------------ 自定义外观（1.3.3）
    // 两块：主题（深浅 + 壁纸，一个下拉统管）、文字颜色 / 主题色。
    // 口径（改前请先读）：
    //  · 深浅和壁纸只留一个入口：「主题」下拉四项（跟随系统 / 浅色 / 深色 / 自定义壁纸）。
    //    早先那版把深/浅又做成三个圆形药丸摆一行，用户直接指出"主题和背景重复了"——同一个
    //    状态开两个入口，必然出现"下拉写深色、药丸写浅色"这种读不懂的画面。别再拆回去。
    //  · 内部存储仍是正交的：settings.theme() 只存 LIGHT/DARK/SYSTEM，theme.json 的 background
    //    另存 WALLPAPER 开关。下拉第 4 项只写 themeStore.background、不动 settings.theme()，
    //    所以选壁纸时深浅档不丢，取消壁纸能原样退回去。
    //  · 两个调色面板内联展开（不弹窗），见 ColorPickerPanel 的类注释。

    /** 主题下拉里「自定义壁纸」那一项的索引（前三项对应 settings.theme() 的三态）。 */
    private static final int THEME_ITEM_WALLPAPER = 3;

    /** 当前实际生效的背景档位（壁纸没图时按深浅回落，下拉不会"选着壁纸却没有壁纸"）。 */
    private ThemeStore.BackgroundMode activeBackgroundMode() {
        if (themeStore.background() == ThemeStore.BackgroundMode.WALLPAPER
                && themeStore.wallpaperUsable()) {
            return ThemeStore.BackgroundMode.WALLPAPER;
        }
        return themeDark ? ThemeStore.BackgroundMode.DARK : ThemeStore.BackgroundMode.LIGHT;
    }

    /** 主题下拉该选中第几项：壁纸优先，否则看 settings.theme()。 */
    private int themeSelectionIndex() {
        if (activeBackgroundMode() == ThemeStore.BackgroundMode.WALLPAPER) {
            return THEME_ITEM_WALLPAPER;
        }
        return switch (settings.theme()) {
            case LIGHT -> 1;
            case DARK -> 2;
            case SYSTEM -> 0;
        };
    }

    /**
     * 主题行下面那条「当前壁纸 / 更换图片 / 清除壁纸」。
     *
     * <p>挂在下拉正下方、只有选到第 4 项才显示：这样"主题"一个控件就管完了深浅和壁纸，
     * 不再有第二处入口跟它打架。收起时 {@code managed=false}——不占高度，也不会多吃一条
     * 分隔线（外层用 {@code rowWithPanel} 裹成一组）。
     */
    private VBox buildWallpaperStripHost() {
        wallpaperStateLabel = new Label();
        wallpaperStateLabel.getStyleClass().add("wallpaper-name");
        // 不省略：导入时文件名固定是 wallpaper.<ext>（图由我们自己复制并命名），本来就很短；
        // 真变长也宁可放宽，别把名字截成「wallp…」让人以为导入坏了。
        wallpaperStateLabel.setMinWidth(Region.USE_PREF_SIZE);

        Label hint = new Label("当前壁纸");
        hint.getStyleClass().add("setting-desc");
        hint.setMinWidth(Region.USE_PREF_SIZE);

        Button change = compactButton("更换图片");
        change.setOnAction(e -> chooseWallpaper());
        Button clear = compactButton("清除壁纸");
        clear.setOnAction(e -> clearWallpaper());

        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);

        HBox strip = new HBox(10, hint, wallpaperStateLabel, gap, change, clear);
        strip.setAlignment(Pos.CENTER_LEFT);
        strip.getStyleClass().add("wallpaper-strip");

        VBox host = new VBox(strip);
        host.setVisible(false);
        host.setManaged(false);
        return host;
    }

    /** 清除壁纸：只解除绑定，不动磁盘上的文件（零删除），退回按当前深浅的纯色背景。 */
    private void clearWallpaper() {
        themeStore = themeStore.withWallpaperPath(null);
        themeStore = themeStore.withBackground(themeDark
                ? ThemeStore.BackgroundMode.DARK : ThemeStore.BackgroundMode.LIGHT);
        applyAppearance(false);
    }

    /**
     * 主题下拉选中某一项之后干的事：前三项切深浅档，第四项切壁纸。
     *
     * <p>壁纸那一档**不改** {@code settings.theme()}：壁纸是正交的一层，深浅照旧管文字与按钮
     * 配色（蒙版随深浅自动切），所以选壁纸前用户挑的深浅档要留着，取消壁纸时好退回去。
     */
    private void onThemeSelected(int index) {
        if (syncingAppearance) {
            return;
        }
        if (index == THEME_ITEM_WALLPAPER) {
            themeStore = themeStore.withBackground(ThemeStore.BackgroundMode.WALLPAPER);
            applyAppearance(false);
            if (!themeStore.wallpaperUsable()) {
                // 还没有图：直接把文件选择器打开，别让用户点完一片空白不知道下一步
                chooseWallpaper();
                if (!themeStore.wallpaperUsable()) {
                    // 用户取消了选择：退回纯色背景，不留"选了壁纸却没有壁纸"的悬空状态
                    themeStore = themeStore.withBackground(themeDark
                            ? ThemeStore.BackgroundMode.DARK : ThemeStore.BackgroundMode.LIGHT);
                    applyAppearance(false);
                }
            }
            return;
        }
        SettingsStore.ThemeMode mode = switch (index) {
            case 1 -> SettingsStore.ThemeMode.LIGHT;
            case 2 -> SettingsStore.ThemeMode.DARK;
            default -> SettingsStore.ThemeMode.SYSTEM;
        };
        settings = settings.withTheme(mode);
        // 先按当前深浅估一档：目的是让运行时先知道"壁纸关了"，再切深浅，避免中间闪一帧壁纸。
        // 「跟随系统」可能随后翻面，所以下面按真正的 themeDark 再校一次。
        themeStore = themeStore.withBackground(themeDark
                ? ThemeStore.BackgroundMode.DARK : ThemeStore.BackgroundMode.LIGHT);
        applyAppearance(false);
        applyTheme();
        themeStore = themeStore.withBackground(themeDark
                ? ThemeStore.BackgroundMode.DARK : ThemeStore.BackgroundMode.LIGHT);
        commitThemeStore();
        commitSettings();
    }

    /**
     * 取值小胶囊：色点在前、完整取值文字紧随（如 {@code #237a44（默认）}）。
     *
     * <p>排布是「色点 + 取值 → 选择颜色 → 恢复默认」：色块和它对应的值挨在一起，两个动作
     * 再往右，一眼能分清"哪个是当前值、哪个是按钮"。
     *
     * <p>宽度按内容走、{@code minWidth=USE_PREF_SIZE} 不参与收缩——这是修「取值被截成
     * #2f9e… / 断成两行」的关键；同理两颗按钮也用 USE_PREF_SIZE。三样加起来约 310px，
     * 窗口再窄宁可让左边的说明文字折行，也不许压这里。别再给它们加 maxWidth。
     */
    private Label valueChip(boolean forText) {
        Region dot = new Region();
        dot.getStyleClass().add("color-chip-dot");
        Label chip = new Label();
        chip.setGraphic(dot);
        chip.setContentDisplay(ContentDisplay.LEFT);
        chip.setGraphicTextGap(6);
        chip.getStyleClass().add("color-value-chip");
        chip.setMinWidth(Region.USE_PREF_SIZE);
        if (forText) {
            textColorValueLabel = chip;
            textColorSwatch = dot;
        } else {
            accentColorValueLabel = chip;
            accentColorSwatch = dot;
        }
        return chip;
    }

    /** 文字颜色行右侧：色点 + 取值 → 「选择颜色」→「恢复默认」。 */
    private HBox textColorControl() {
        Button pick = compactButton("选择颜色");
        pick.setOnAction(e -> togglePicker(true));
        Button reset = compactButton("恢复默认");
        reset.setOnAction(e -> {
            themeStore = themeStore.withText(null);
            if (textPicker != null) {
                textPicker.setColor(defaultTextHex());
            }
            applyAppearance(false);
        });
        HBox box = new HBox(10, valueChip(true), pick, reset);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    /** 主题色行右侧：同上。 */
    private HBox accentColorControl() {
        Button pick = compactButton("选择颜色");
        pick.setOnAction(e -> togglePicker(false));
        Button reset = compactButton("恢复默认");
        reset.setOnAction(e -> {
            themeStore = themeStore.withAccent(null);
            if (accentPicker != null) {
                accentPicker.setColor(ThemeStore.defaultAccentHex());
            }
            applyAppearance(false);
        });
        HBox box = new HBox(10, valueChip(false), pick, reset);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    /**
     * 设置行里的紧凑按钮：{@code minWidth=USE_PREF_SIZE} 钉死，窗口再窄也不许把按钮
     * 文字压成省略号（那正是这次要修的毛病），宁可让左侧说明列多折两行。
     */
    private static Button compactButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().addAll("btn-ghost", "btn-compact");
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    /** 调色面板的宿主容器：默认收起且 unmanaged（不占高度，不撑大设置卡）。 */
    private static VBox pickerHost() {
        VBox host = new VBox();
        host.setVisible(false);
        host.setManaged(false);
        // 展开时给出上下留白：面板别贴着上一行，也别贴着下面的分隔线
        host.setPadding(new Insets(2, 0, 14, 0));
        return host;
    }

    /**
     * 一行设置 + 它下面可展开的调色面板，打包成一组。
     *
     * <p>为什么不把面板当成独立一行塞给 buildCard：buildCard 会给**每个**子节点后面补一条
     * 分隔线，面板收起（unmanaged）时那条线依然会画出来，两个设置行之间就冒出「双分隔线」。
     * 裹成一组后，收起状态整组只有一行高、分隔线也只一条。
     */
    private static VBox rowWithPanel(HBox row, VBox host) {
        return new VBox(0, row, host);
    }

    /** 展开 / 收起调色面板（{@code text = true} 表示文字色那个）。 */
    private void togglePicker(boolean text) {
        VBox host = text ? textPickerHost : accentPickerHost;
        if (host == null) {
            return;
        }
        boolean show = !host.isVisible();
        if (show) {
            ensurePicker(text);
        }
        host.setVisible(show);
        host.setManaged(show);
    }

    /** 懒建调色面板：设置页首次打开不建 Canvas，省一帧。 */
    private void ensurePicker(boolean text) {
        if (text) {
            if (textPicker != null) {
                return;
            }
            textPicker = new ColorPickerPanel("文字颜色",
                    "拖动色环或输入 HEX，界面会立刻跟着变。标题、正文、次要提示各取一级明度。",
                    themeStore.customText() ? themeStore.textColor() : defaultTextHex(),
                    hex -> {
                        themeStore = themeStore.withText(hex);
                        applyAppearance(true);
                    },
                    () -> {
                        themeStore = themeStore.withText(null);
                        textPicker.setColor(defaultTextHex());
                        applyAppearance(false);
                    },
                    () -> {
                        textPickerHost.setVisible(false);
                        textPickerHost.setManaged(false);
                    });
            textPickerHost.getChildren().add(textPicker);
            return;
        }
        if (accentPicker != null) {
            return;
        }
        accentPicker = new ColorPickerPanel("主题色",
                "选一个颜色，界面上所有绿色都会换成它；深浅主题各取对应的明暗档。",
                themeStore.effectiveAccent(),
                hex -> {
                    themeStore = themeStore.withAccent(hex);
                    applyAppearance(true);
                },
                () -> {
                    themeStore = themeStore.withAccent(null);
                    accentPicker.setColor(ThemeStore.defaultAccentHex());
                    applyAppearance(false);
                },
                () -> {
                    accentPickerHost.setVisible(false);
                    accentPickerHost.setManaged(false);
                });
        accentPickerHost.getChildren().add(accentPicker);
    }

    /** 当前主题下的"默认文字色"，用作调色面板打开时的起点（不做自定义时它就是界面上的颜色）。 */
    private String defaultTextHex() {
        return themeDark ? "#e9eef5" : "#101828";
    }

    /**
     * 选壁纸：只收横屏，复制进 {@code ~/.gbl/wallpapers/}（微信/浏览器的临时路径过几天就没了）。
     *
     * <p>图偏小不拦，只提示"放大后可能不清晰"——那是用户的审美判断，不是我们拦的理由。
     */
    private void chooseWallpaper() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择壁纸（只支持横屏图片）");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("图片",
                        "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif", "*.webp"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        java.io.File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        Image probe;
        try {
            probe = new Image(file.toURI().toString());
        } catch (RuntimeException ex) {
            probe = null;
        }
        if (probe == null || probe.isError() || probe.getWidth() <= 0 || probe.getHeight() <= 0) {
            toast("这张图片读不了，换一张试试");
            return;
        }
        double iw = probe.getWidth();
        double ih = probe.getHeight();
        if (!ThemeStore.isLandscapeForTests(iw, ih)) {
            String shape = iw == ih ? "正方形" : "竖屏";
            toast(String.format("只支持横屏图片（宽 > 高）。这张是 %d×%d 的%s，"
                    + "铺满窗口会把主体裁掉，换一张横着的吧。",
                    (int) iw, (int) ih, shape));
            return;
        }
        try {
            themeStore = themeStore.importWallpaper(file.toPath());
        } catch (Exception ex) {
            toast("壁纸保存失败：" + ex.getMessage());
            return;
        }
        applyAppearance(false);
        if (iw < 1280 || ih < 720) {
            toast("壁纸已应用。这张图偏小，放大后可能不清晰。");
        } else {
            toast("壁纸已应用");
        }
    }

    /** 外观改动落到运行时（{@code live = true} 走节流，用于拖色环的实时预览）。 */
    private void applyAppearance(boolean live) {
        if (themeRuntime != null) {
            themeRuntime.setStore(themeStore);
            if (live) {
                themeRuntime.reapplyLive();
            } else {
                themeRuntime.reapply();
            }
        }
        refreshAppearanceUi();
        commitThemeStore();
    }

    /** theme.json 落盘（300ms 防抖，拖动色环不该每帧写盘）。 */
    private void commitThemeStore() {
        if (themeSaveDebounce == null) {
            themeSaveDebounce = new PauseTransition(Duration.millis(300));
            themeSaveDebounce.setOnFinished(e -> {
                if (!themeStore.save()) {
                    toast("外观设置保存失败，这次改动可能留不下来");
                }
                refreshAppearanceUi();
            });
        }
        themeSaveDebounce.playFromStart();
    }

    /** 把配置回读到设置页控件上（主题下拉选中项、壁纸条、色点与取值）。 */
    private void refreshAppearanceUi() {
        if (themeBox == null) {
            return;
        }
        syncingAppearance = true;
        try {
            // 回读也是走同一个 onAction：syncingAppearance 那面旗子负责别让它反过来再改一次配置
            themeBox.getSelectionModel().select(themeSelectionIndex());
            boolean wallpaperOn = activeBackgroundMode() == ThemeStore.BackgroundMode.WALLPAPER;
            if (wallpaperStripHost != null) {
                wallpaperStripHost.setVisible(wallpaperOn);
                wallpaperStripHost.setManaged(wallpaperOn);
            }
            if (wallpaperStateLabel != null) {
                wallpaperStateLabel.setText(themeStore.wallpaperUsable()
                        ? fileNameOf(themeStore.wallpaperPath())
                        : (themeStore.wallpaperPath() == null ? "未选择图片" : "图片已丢失"));
            }
            if (textColorSwatch != null) {
                String hex = themeStore.customText() ? themeStore.textColor() : defaultTextHex();
                textColorSwatch.setStyle("-fx-background-color: " + hex + ";");
                textColorValueLabel.setText(themeStore.customText() ? hex : hex + "（默认）");
            }
            if (accentColorSwatch != null) {
                String hex = themeStore.effectiveAccent();
                accentColorSwatch.setStyle("-fx-background-color: " + hex + ";");
                accentColorValueLabel.setText(themeStore.customAccent() ? hex : hex + "（默认）");
            }
        } finally {
            syncingAppearance = false;
        }
    }

    private static String fileNameOf(String path) {
        if (path == null) {
            return "";
        }
        try {
            Path name = Paths.get(path).getFileName();
            return name == null ? path : name.toString();
        } catch (RuntimeException ex) {
            return path;
        }
    }

    /** 涟漪高光取色（Scene 还没建好时按出厂口径兜底）。 */
    private Color rippleColor() {
        if (themeRuntime != null) {
            return themeRuntime.rippleColor();
        }
        boolean darkNow = shell != null && shell.getStyleClass().contains("dark");
        return darkNow ? Color.web("#ffffff", 0.30) : Color.web("#2f9e58", 0.20);
    }

    /** 成功提示的内联字色：跟着主题色走（内联样式绕过 CSS，只能在这里接）。 */
    private String successTextStyle() {
        String green = themeRuntime == null ? "#19b36b" : themeRuntime.accentVariant("#19b36b");
        return "-fx-text-fill:" + green + ";";
    }

    private Node themeSelect() {
        ComboBox<String> box = new ComboBox<>();
        // 第 4 项「自定义壁纸」= 把壁纸当成第四种背景：选它会在下面展开壁纸条，
        // 深浅档不被覆盖（见 onThemeSelected 的注释）。
        box.getItems().addAll("跟随系统", "浅色", "深色", "自定义壁纸");
        box.getSelectionModel().select(themeSelectionIndex());
        // theme-select 是给这一颗下拉单独抬宽的类：.setting-select 的 -fx-min-width 是 150，
        // CSS 会盖掉 setMinWidth，不加这个类窄窗口下第四项就被截成「自定义…」了
        box.getStyleClass().addAll("setting-select", "theme-select");
        box.setPrefWidth(170);
        box.setOnAction(e -> onThemeSelected(box.getSelectionModel().getSelectedIndex()));
        // 弹层是独立 scene，shell 上的 .dark 传不进去，得自己给弹层补一份。
        // 除了给根节点补类，还要挂 popup-dark.css：根节点自己的类名（popup）不会
        // 进入子节点的匹配链，只补类的话 list-view / list-cell 仍然是 modena 的白底。
        wireDarkPopup(box);   // 0.9.1：与其余 11 个下拉统一走辅助方法
        themeBox = box;
        return box;
    }

    /**
     * 0.9.1：给任意 ComboBox 接上「深色弹层补底」。
     *
     * <p>此前只有加载器卡版本下拉和主题下拉挂了 {@code setOnShown(paintPopupDark)}，
     * 全项目 12 个 ComboBox 里另外 10 个展开时弹层还是 modena 默认/透明，能透出主窗口的
     * 分隔线、绿色进度条和按钮。现在所有创建点统一照抄这一行，不再有漏网的。
     *
     * <p>若下拉本来就有 {@code onShown} 逻辑则把原 handler 合并进来再补底，不覆盖它。
     * 本方法不能是 static：{@link #paintPopupDark()} 要读实例字段 {@code themeDark}。
     */
    private <T> void wireDarkPopup(ComboBox<T> box) {
        GblMotion.wireComboBox(box, () -> themeDark);
    }

    private void paintPopupDark() {
        GblMotion.paintPopups(themeDark);
    }

    /**
     * 0.9.1：切回浅色时把先前挂到弹层 scene 上的 {@link #POPUP_DARK_CSS} 摘干净。
     * 弹层不属于 shell，摘掉 shell 的 {@code .dark} 类管不到它们；深浅来回切会残留深底。
     * 深色方向不用额外动作——下拉展开时 {@link #wireDarkPopup} 会重新挂上。
     * 0.9.4：连同 {@link #POPUP_THEME_CSS} 一起摘掉，避免浅→深→浅来回切样式叠加。
     */
    private void stripPopupDark() {
        GblMotion.stripPopupStyles();
    }

    /**
     * 「界面动效」开关。
     *
     * <p>v0.2.4 之前这里绑反了：开关直接吃 {@code settings.reduceMotion()}，可数据层存的是
     * 「要不要减少动效」（默认 false = 有动效），开关表达的是「动效开不开」，两者正好相反。
     * 结果就是出厂值让开关显示为关、把开关打开反而动效没了（用户原话：关掉它有动效，
     * 打开了就没了）。开关和它下面的数据源中间必须取一次反。
     */
    private Node motionToggle() {
        // ON = 有淡入（reduceMotion=false）；OFF = 直接切换，不做过渡
        ToggleSwitch toggle = new ToggleSwitch(!settings.reduceMotion());
        this.motionToggle = toggle;
        toggle.selectedProperty().addListener((obs, ov, nv) -> {
            settings = settings.withReduceMotion(!nv);
            commitSettings();
        });
        return toggle;
    }

    /**
     * 「按钮特效」开关（1.0.7）：控制点按钮时的涟漪动画，默认开。
     *
     * <p>与 {@link #motionToggle()} 不同，这里开关语义和数据语义是一致的
     * （开 = 有特效），所以**不用取反**——别顺手把上面那段取反也抄过来。
     */
    private Node buttonEffectsToggle() {
        ToggleSwitch toggle = new ToggleSwitch(settings.buttonEffects());
        this.buttonEffectsToggle = toggle;
        toggle.selectedProperty().addListener((obs, ov, nv) -> {
            settings = settings.withButtonEffects(nv);
            if (!nv) {
                // 关掉的瞬间把正在播的涟漪收掉，别留半圈动画在屏幕上
                cancelAllRipples();
            }
            commitSettings();
        });
        return toggle;
    }

    /**
     * 「自动下载合适的 Java」开关（1.1.2）：开启时，游戏需要但本机没有精确主版本 Java，
     * 会自动下载并安装；默认开。开关语义与数据一致（开 = 自动下载），不用取反。
     */
    private Node autoDownloadJavaToggle() {
        ToggleSwitch toggle = new ToggleSwitch(settings.isAutoDownloadJava());
        this.autoDownloadJavaToggle = toggle;
        toggle.selectedProperty().addListener((obs, ov, nv) -> {
            settings = settings.withAutoDownloadJava(nv);
            commitSettings();
        });
        return toggle;
    }

    private Node trayToggle() {
        ToggleSwitch toggle = new ToggleSwitch(settings.closeToTray());
        this.trayToggle = toggle;
        toggle.selectedProperty().addListener((obs, ov, nv) -> {
            settings = settings.withCloseToTray(nv);
            commitSettings();
        });
        return toggle;
    }

    /**
     * 开机自启：把 GBL 注册到系统启动项（Windows 注册表 / macOS LaunchAgent）。
     * 0.9.6 r3：初值取 {@link com.mclauncher.BootAutostart#isEnabled()} 的真实系统状态；
     * {@code setEnabled} 返回 false 时回滚开关并提示、不写盘；成功才同步 config.json 并落盘。
     */
    private Node bootAutostartToggle() {
        ToggleSwitch toggle = new ToggleSwitch(BootAutostart.isEnabled());
        this.bootAutostartToggle = toggle;
        // guard：失败回滚 toggle.setSelected(ov) 会再触发本监听器，压住它别形成 setEnabled 回环；
        // refreshSettingsUi() 程序回填时也共用同一个 guard（字段 bootAutostartGuard）。
        final boolean[] guard = bootAutostartGuard;
        toggle.selectedProperty().addListener((obs, ov, nv) -> {
            if (guard[0]) {
                return;
            }
            boolean ok = BootAutostart.setEnabled(nv);
            // 0.9.6 r3：一律以「真实 Run 键状态」为最终判据，不信任命令返回码。
            // 关闭时值本就不存在（reg delete 返回 1）是幂等成功；定位不到 GBL.exe 或权限
            // 不足时值仍在，是真失败。两者都可能 ok=false，只有回读真实状态才分得开。
            // 即便 ok=true 而状态未达成也按失败处理，绝不留「配置说开了、系统其实没开」的假显示。
            boolean real = BootAutostart.isEnabled();
            if (real != nv) {
                if (!ok) {
                    System.err.println("[boot] 0.9.6 r3 开机自启未达成，按真实 Run 键状态回滚");
                }
                guard[0] = true;
                toggle.setSelected(real);
                guard[0] = false;
                toast("开机自启设置失败，请检查程序目录或系统权限");
                return;   // 不写 settings、不 commitSettings
            }
            // reg.exe 完全不可用时 isEnabled() 恒 false，开关初值即为关；那种环境自启本就
            // 写不进，按真实状态显示即可，无需额外分支，也不会出现「假已开启」。
            settings = settings.withLaunchOnBoot(nv);
            commitSettings();
        });
        return toggle;
    }

    /** 自动更新：开启后启动器自动检测并提示新版本。 */
    private Node autoUpdateToggle() {
        ToggleSwitch toggle = new ToggleSwitch(settings.isAutoUpdate());
        this.autoUpdateToggle = toggle;
        toggle.selectedProperty().addListener((obs, ov, nv) -> {
            settings = settings.withAutoUpdate(nv);
            commitSettings();
        });
        return toggle;
    }

    /**
     * 启动后关闭启动器：游戏进程拉起后直接退出启动器。
     * 游戏是 {@link com.mclauncher.GameLauncher} 拉起的独立进程，退出启动器不会带走它。
     */
    private Node closeAfterLaunchToggle() {
        ToggleSwitch toggle = new ToggleSwitch(settings.isCloseAfterLaunch());
        this.closeAfterLaunchToggle = toggle;
        toggle.selectedProperty().addListener((obs, ov, nv) -> {
            settings = settings.withCloseAfterLaunch(nv);
            commitSettings();
        });
        return toggle;
    }

    /**
     * 游戏内存 (MB)：写入 JVM -Xmx 的目标值。
     * 只认正整数，非法输入忽略（保留上一份合法值），不在这里拼命令行——
     * 启动器在 {@link com.mclauncher.GameLauncher} 组装 JVM 参数时会读取
     * {@code SettingsStore.gameMemoryMb()} 落地 -Xmx。
     */
    private Node gameMemoryField() {
        TextField field = new TextField();
        field.getStyleClass().add("search-input");
        field.setPrefWidth(160);
        field.setPrefHeight(34);
        field.setText(Integer.toString(settings.gameMemoryMb()));
        this.gameMemoryField = field;
        field.textProperty().addListener((obs, old, nv) -> {
            int v;
            try {
                v = Integer.parseInt(nv.trim());
            } catch (NumberFormatException ex) {
                return;   // 空 / 非数字：不落盘，保留上一份
            }
            if (v <= 0) {
                return;   // 非正：同样忽略
            }
            settings = settings.withGameMemoryMb(v);
            commitSettings();
        });
        return field;
    }

    /**
     * 并发下载数：写入下载池大小上限。
     * 非法（空 / 非数字 / <=0）输入忽略，保留上一份合法值；实际生效点在
     * {@link com.mclauncher.Net} 的下载线程池（启动时读取该设置）。
     */
    private Node concurrentDownloadsField() {
        TextField field = new TextField();
        field.getStyleClass().add("search-input");
        field.setPrefWidth(160);
        field.setPrefHeight(34);
        field.setText(Integer.toString(settings.maxConcurrentDownloads()));
        this.concurrentDownloadsField = field;
        field.textProperty().addListener((obs, old, nv) -> {
            int v;
            try {
                v = Integer.parseInt(nv.trim());
            } catch (NumberFormatException ex) {
                return;   // 空 / 非数字：不落盘
            }
            if (v <= 0) {
                return;   // 非正：同样忽略
            }
            settings = settings.withMaxConcurrentDownloads(v);
            commitSettings();
        });
        return field;
    }

    /**
     * 下载源：和「下载」页的 modSourceBox 同源，只是挪到设置里也放一份。
     * 切源即时写盘——{@link com.mclauncher.Mirror} 每次下载时读
     * {@code SettingsStore.load().downloadSource()}，落盘即生效，不用重启。
     */
    private Node downloadSourceSelect() {
        ComboBox<String> box = new ComboBox<>();
        box.getStyleClass().add("setting-select");
        box.setPrefWidth(180);
        box.getItems().setAll(
                java.util.Arrays.stream(SettingsStore.DownloadSource.values())
                        .map(SettingsStore.DownloadSource::label)
                        .toList());
        box.getSelectionModel().select(settings.downloadSource().ordinal());
        box.setOnAction(e -> {
            SettingsStore.DownloadSource source = SettingsStore.DownloadSource.values()
                    [Math.max(0, Math.min(box.getSelectionModel().getSelectedIndex(),
                            SettingsStore.DownloadSource.values().length - 1))];
            settings = settings.withDownloadSource(source);
            // 两处下拉保持同步：设置在设置页切了源，「下载」页的 modSourceBox 也跟着变
            if (modSourceBox != null) {
                modSourceBox.getSelectionModel().select(source.ordinal());
            }
            commitSettings();
        });
        wireDarkPopup(box);   // 深色弹层补底，与主题 / 加载器下拉一致
        this.downloadSourceSelect = box;
        return box;
    }

    /**
     * 清理缓存：清空封面缩略图的内存缓存（{@link #coverCache}）。
     * 本项目的封面只在内存里解码 + 缓存，没有落盘的封面缓存目录，
     * 所以这一步就是清空内存 map；下次滚动列表会重新异步解码。已下载的游戏本体不受影响。
     */
    private Node clearCacheButton() {
        Button button = new Button("清理缓存");
        button.getStyleClass().addAll("btn-ghost");
        button.setOnAction(e -> {
            coverCache.clear();
            toast("封面缓存已清理");
        });
        return button;
    }

    /**
     * 0.9.7 出海线路：把「自动选了哪条线路」摊开给用户看，并给一个重新测速的按钮。
     *
     * <p>线路本身一直是全自动的（启动时 {@link ProxyRoute#prefetch()} 并发竞速挑最快的那条），
     * 这里只做「可见 + 可重测」两件事，不新增任何手动选端口的选项 ——
     * 让普通用户自己去填 127.0.0.1:xxxx 只会把简单事变复杂。
     */
    private Node lineStatusRow() {
        lineStatusLabel = new Label(ProxyRoute.describe());
        lineStatusLabel.getStyleClass().add("setting-desc");

        Button retest = new Button("重新测速");
        retest.getStyleClass().add("btn-ghost");
        retest.setOnAction(e -> {
            retest.setDisable(true);
            lineStatusLabel.setText("正在测速…");
            ProxyRoute.invalidate();
            ProxyRoute.prefetch();
            // prefetch 是后台异步的，这里起一条短命守护线程盯着结果，最多盯 14 秒
            Thread t = new Thread(() -> {
                String last = "";
                for (int i = 0; i < 14; i++) {
                    try {
                        Thread.sleep(900);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    String d = ProxyRoute.describe();
                    last = d;
                    final String shown = d;
                    javafx.application.Platform.runLater(() -> {
                        if (lineStatusLabel != null) {
                            lineStatusLabel.setText(shown);
                        }
                    });
                    if (!"未探测".equals(d)) {
                        break;
                    }
                }
                final String end = last;
                javafx.application.Platform.runLater(() -> {
                    retest.setDisable(false);
                    if (lineStatusLabel != null && "未探测".equals(end)) {
                        lineStatusLabel.setText("还没测出来（可能暂时没网），稍后再试");
                    }
                });
            }, "gbl-line-poll");
            t.setDaemon(true);
            t.start();
        });

        HBox box = new HBox(10, lineStatusLabel, retest);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    /**
     * 0.9.7 版本与更新：把「我现在是什么版本、有没有新版」变成看得见的一行。
     * 以前只有启动时静默查一次，用户想知道版本只能去看标题栏括号里的短码。
     */
    private Node versionRow() {
        Label current = new Label("当前 v" + LauncherApp.VERSION + " (" + LauncherApp.BUILD + ")");
        current.getStyleClass().add("setting-desc");
        versionResult = new Label("");
        versionResult.getStyleClass().add("setting-desc");

        Button check = new Button("检查更新");
        check.getStyleClass().add("btn-ghost");
        check.setOnAction(e -> {
            if (check.isDisable()) {
                return;
            }
            check.setDisable(true);
            versionResult.setText("正在检查…");
            Thread t = new Thread(() -> {
                UpdateChecker.ManualResultEx r = UpdateChecker.checkManualWithReason();
                javafx.application.Platform.runLater(() -> {
                    check.setDisable(false);
                    switch (r.state()) {
                        case LATEST -> versionResult.setText("已是最新版本");
                        case FAILED -> versionResult.setText(r.reason() != null && !r.reason().isBlank()
                                ? r.reason() : "没能连上更新服务器，检查一下网络再试");
                        case FOUND -> {
                            versionResult.setText("发现新版本 v" + r.info().version());
                            askToUpdateNow(r.info());
                        }
                    }
                });
            }, "gbl-update-manual");
            t.setDaemon(true);
            t.start();
        });

        HBox box = new HBox(10, current, versionResult, check);
        box.setAlignment(Pos.CENTER_RIGHT);
        return box;
    }

    /** 手动检查到新版本后，复用启动时那套对话框直接升级，不再另起一套流程。 */
    private void askToUpdateNow(UpdateInfo info) {
        showUpdateDialog(info,
                () -> {
                    // 0.9.9 P2：点击瞬间先闸门 + 立即禁用按钮，不等第一次 onFraction，
                    // 堵住下载启动最长 6 秒的竞速窗口里连点导致多安装线程互相覆盖。
                    if (UpdateInstaller.isInstalling()) {
                        return;
                    }
                    updateDlgUpdateBtn.setDisable(true);
                    UpdateInstaller.installAndRestart(info, new UpdateInstaller.Progress() {
                        @Override
                        public void onStage(String text) {
                            javafx.application.Platform.runLater(() -> updateProgress(-1, text));
                        }

                        @Override
                        public void onFraction(double fraction) {
                            javafx.application.Platform.runLater(() -> updateProgress(fraction, null));
                        }

                        @Override
                        public void onCanceled() {
                            // 0.9.9 P2：手动更新被取消——onCanceled 在安装线程回调，
                            // 必须 Platform.runLater 切回 FX；关对话框即隐藏进度条与「更新中…」按钮。
                            javafx.application.Platform.runLater(() -> closeUpdateDialog());
                        }
                    }, t -> {
                        // r4：不关对话框，直接把真实失败原因显示在里面（关掉再开会让界面闪一下）
                        showUpdateFailed(t == null || t.getMessage() == null || t.getMessage().isBlank() ? UPDATE_FAILED_MSG : t.getMessage());
                    });
                },
                this::closeUpdateDialog);
    }

    /** CurseForge API Key 输入框：用户在设置页粘贴自己的 key，优先级高于内置兜底 key。 */
    private Node cfKeyRow() {
        TextField keyField = new TextField();
        keyField.getStyleClass().add("search-input");
        keyField.setPrefWidth(420);
        keyField.setPrefHeight(34);
        keyField.setPromptText("粘贴密钥（留空也行）");
        String cur = settings.cfApiKey();
        if (cur != null) {
            keyField.setText(cur);
        }
        this.cfKeyField = keyField;
        keyField.textProperty().addListener((obs, old, nv) -> {
            settings = settings.withCfApiKey(nv);
            commitSettings();
        });
        return row("CurseForge 密钥",
                "用 CurseForge 模组要填密钥；"
                        + "留空也能用，启动器会自带一个。",
                keyField);
    }

    /**
     * 0.8.6 D5：游戏 Java 路径。错误提示里让用户「在设置页手动指定 Java 路径」，
     * 这里必须真的有这一行，否则干净机器上只剩一句做不到的指引。
     * 默认留空 → 启动器自带 GBL/jre 的 Java 25 兜底，普通用户不需要动它。
     */
    private Node javaPathRow() {
        TextField field = new TextField();
        field.getStyleClass().add("search-input");
        field.setPrefWidth(420);
        field.setPrefHeight(34);
        field.setPromptText("留空就行（用启动器自带的运行环境）");
        String cur = settings.javaPath();
        field.setText(cur == null ? "" : cur);
        this.javaPathField = field;
        field.textProperty().addListener((obs, old, nv) -> {
            settings = settings.withJavaPath(nv);
            commitSettings();
        });
        return row("游戏 Java 路径",
                "启动器自带运行环境，"
                        + "这里留空就行；"
                        + "只有自带环境出问题才需要手动改。",
                field);
    }

    /**
     * 把界面刷回 {@link #settings} 当前那份（恢复默认之后调用）。
     * 开关是字段而不是局部变量：监听器里只改 settings，这里赋值不会回环。
     */
    private void refreshSettingsUi() {
        // 「主题」下拉现在有第四项「自定义壁纸」，回读按实际生效档位算，别只照 settings.theme()
        if (themeBox != null) {
            syncingAppearance = true;
            try {
                themeBox.getSelectionModel().select(themeSelectionIndex());
            } finally {
                syncingAppearance = false;
            }
        }
        // 同 motionToggle()：开关是「动效开」，数据是「减少动效」，回读也要取反
        motionToggle.setSelected(!settings.reduceMotion());
        // 按钮特效：开关与数据同义（开 = 有特效），直接回读，不用取反
        if (buttonEffectsToggle != null) {
            buttonEffectsToggle.setSelected(settings.buttonEffects());
        }
        // 自动下载 Java：开关与数据同义（开 = 缺版本自动下载），直接回读
        if (autoDownloadJavaToggle != null) {
            autoDownloadJavaToggle.setSelected(settings.isAutoDownloadJava());
        }
        trayToggle.setSelected(settings.closeToTray());
        if (cfKeyField != null) {
            cfKeyField.setText(settings.cfApiKey() == null ? "" : settings.cfApiKey());
        }
        if (javaPathField != null) {
            javaPathField.setText(settings.javaPath() == null ? "" : settings.javaPath());
        }
        if (bootAutostartToggle != null) {
            // 0.9.6 r3：开机自启以系统 Run 键的真实状态为准，并把 config.json 的 launchOnBoot
            // 同步到该值，避免「配置说开了、系统里其实没写」导致 UI 与真实自启不一致。
            // setSelected 会触发监听器，故用同一个 guard 压住：一次 UI 回读不该反过来写系统 /
            // 触发防抖落盘 / 弹「设置失败」提示，也不该把开关拨回去。
            boolean real = BootAutostart.isEnabled();
            bootAutostartGuard[0] = true;
            try {
                bootAutostartToggle.setSelected(real);
            } finally {
                bootAutostartGuard[0] = false;
            }
            if (settings.isLaunchOnBoot() != real) {
                settings = settings.withLaunchOnBoot(real);
            }
        }
        if (autoUpdateToggle != null) {
            autoUpdateToggle.setSelected(settings.isAutoUpdate());
        }
        if (closeAfterLaunchToggle != null) {
            closeAfterLaunchToggle.setSelected(settings.isCloseAfterLaunch());
        }
        if (gameMemoryField != null) {
            gameMemoryField.setText(Integer.toString(settings.gameMemoryMb()));
        }
        if (concurrentDownloadsField != null) {
            concurrentDownloadsField.setText(Integer.toString(settings.maxConcurrentDownloads()));
        }
        if (downloadSourceSelect != null) {
            downloadSourceSelect.getSelectionModel().select(settings.downloadSource().ordinal());
        }
        // 1.3.3：外观三块（背景药丸 / 两个色块 / 壁纸文件名）一并回读
        refreshAppearanceUi();
    }

    /**
     * 改动落盘，防抖 300ms：拖滑块 / 连点开关不该把同一个文件写十遍。
     * 顶栏那枚胶囊先把「正在保存」亮出来，让点了有反馈。
     */
    private void commitSettings() {
        setStatusClass("chip-pending", "正在保存…");
        settingsSaveDebounce.stop();
        settingsSaveDebounce.playFromStart();
    }

    private void flushSettings() {
        boolean saved = settings.save();
        setStatusClass(saved ? "chip-accent" : "chip-error",
                saved ? "已保存" : "保存失败");
    }

    /**
     * 胶囊换状态：先把类名整份换掉，再挂上状态类。
     *
     * <p>为什么是「整份 setAll」而不是摘一个挂一个：{@code getStyleClass()} 是允许重复的
     * ObservableList，连点开关会往里攒出一串同名 {@code chip-pending}（点 20 下就是 20 个），
     * 而 {@code remove(单个)} 一次只摘得掉第一个——剩下的同名类和 {@code chip-accent} 同时挂着，
     * 两条 CSS 打架，后声明的那条赢，于是「已保存」被 pending 的橙棕色盖住，
     * 看着就是「点多了胶囊变红」。v0.2.4 之前每次保存都只补不摘，攒够两次就复现。
     */
    private void setStatusClass(String stateClass, String text) {
        // chip 是按空格拆开逐条 add 的（见 chip()），这里整份重设同样要拆开
        settingsStatus.setText(text);
        // label 是 Label 自带的那一个类，setAll 会连它一起抹掉，得显式带回来
        settingsStatus.getStyleClass().setAll("label", "chip");
        if (stateClass != null) {
            settingsStatus.getStyleClass().add(stateClass);
        }
    }

    /**
     * 恢复默认：同一枚按钮点两次才算（点了不弹窗，就地换文案），三秒不点自己弹回去。
     * 文案要说清后果——这里只重置外观与窗口行为，不动任何游戏数据。
     */
    private void resetSettings(Button button) {
        if (!RESET_ARMED.equals(button.getText())) {
            button.setText(RESET_ARMED);
            PauseTransition back = new PauseTransition(Duration.seconds(3));
            back.setOnFinished(e -> button.setText(RESET_LABEL));
            back.play();
            return;
        }
        button.setText(RESET_LABEL);
        settings = SettingsStore.defaults();
        // 1.3.3：外观也回出厂（浅色背景 + 出厂绿 + 默认文字色）。壁纸文件留在磁盘上不删，
        // 只是不再引用——零删除，用户想再开壁纸不必重新导入。
        themeStore = themeStore.resetAppearance();
        // 0.9.6 r3：恢复默认时把真实系统启动项里的 GBL 值一并清掉，
        // 否则 config.json 回到出厂值但注册表仍留着开机自启，用户会遇到「关了却还自启」。
        // 放在 refreshSettingsUi() 之前：后者以 isEnabled() 回读真实状态，此时已是 false。
        BootAutostart.setEnabled(false);
        refreshSettingsUi();
        applyTheme();
        // 运行时也要跟着复位：摘掉壁纸层/蒙版，并把运行时样式表撤干净
        applyAppearance(false);
        commitSettings();
    }

    private Node buildSettingsDanger() {
        VBox text = new VBox(3);
        Label title = new Label("恢复默认设置");
        title.getStyleClass().add("card-title");
        Label desc = new Label("把上面两组恢复默认。");
        desc.getStyleClass().add("setting-desc");
        desc.setWrapText(true);
        text.getChildren().addAll(title, desc);

        Region gap = filler();
        HBox.setHgrow(gap, Priority.ALWAYS);
        Button reset = new Button(RESET_LABEL);
        reset.getStyleClass().addAll("btn-ghost", "danger-btn");
        reset.setOnAction(e -> resetSettings(reset));

        HBox box = new HBox(16);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(text, gap, reset);

        VBox card = new VBox(12);
        card.getStyleClass().addAll("card", "danger-card");
        card.setPadding(new Insets(16, 20, 16, 20));
        card.getChildren().add(box);
        return card;
    }

    /**
     * 主题落到 CSS 上：深色那套 token 全部挂在 shell 的 {@code .dark} 下面（见 theme.css），
     * 这里只负责加/摘这个类名。SYSTEM 模式要真的去问系统当前是深是浅。
     */
    private void applyTheme() {
        // -Dgbl.theme 是调试强制开关，得盖过配置里存的那一档：不然上一条自测把 theme 存成
        // DARK 之后，-Dgbl.theme=light 就只剩「探测结果」这一半起作用，界面还是深色的。
        boolean dark;
        if (SystemTheme.isForced()) {
            dark = SystemTheme.isDark();
        } else {
            SettingsStore.ThemeMode mode = settings.theme();
            dark = mode == SettingsStore.ThemeMode.DARK
                    || (mode == SettingsStore.ThemeMode.SYSTEM && SystemTheme.isDark());
        }
        themeDark = dark;
        // 1.3.3：不再「深浅没变就直接 return」——外观运行时也要跟着刷新（壁纸层可见性、
        // 主题色对 Java 侧取色的影响都在里面）。两处都是幂等的，重复调没有副作用。
        if (dark) {
            if (!shell.getStyleClass().contains("dark")) {
                shell.getStyleClass().add("dark");
            }
        } else {
            shell.getStyleClass().remove("dark");
            // 0.9.1：浅色时把已挂到弹层 scene 上的深色样式表也摘掉。
            // 弹层是独立 Window，不受 shell 的 .dark 类管辖，不清理的话深→浅→深来回切会残留深底。
            stripPopupDark();
        }
        if (themeRuntime != null) {
            themeRuntime.refresh(dark);
        }
        // 1.3.3：深浅一变，主题下拉的选中项、壁纸条、"默认文字色"的取值都要跟着刷新
        refreshAppearanceUi();
    }

    /**
     * 0.9.7 新手上手引导：一个游戏都没装的时候，启动页除了一颗「启动游戏」什么都没有，
     * 新用户会卡在「我该先干什么」。这张卡只做一件事：把「先装一个游戏」讲清楚，
     * 并给三个装的人最多的版本，点一下直接进安装流程。
     *
     * <p>已经装过任何版本的机器上返回 {@code null}，界面上完全不会出现这张卡。
     */
    private Node buildFirstRunGuide() {
        try {
            if (!GameScanner.scan().isEmpty()) {
                return null;
            }
        } catch (Throwable ex) {
            return null;   // 扫不动就别乱下结论，宁可不出引导卡
        }
        System.err.println("[shell] " + FP_097_GUIDE);

        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        card.getStyleClass().add("first-run-guide");
        card.setPadding(new Insets(18, 20, 18, 20));

        Label title = new Label("第一次用 GBL？先把游戏装起来");
        title.getStyleClass().add("card-title");
        Label desc = new Label("点一个版本就开始下载，"
                + "装好回来按「启动游戏」就能玩。想装别的版本，去「下载游戏」页找。");
        desc.getStyleClass().add("setting-desc");
        desc.setWrapText(true);

        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_LEFT);
        String[][] picks = {
                {"1.21.4", "最新正式版"},
                {"1.20.1", "模组最多"},
                {"1.12.2", "老电脑最流畅"}};
        for (String[] pick : picks) {
            Button b = new Button(pick[0] + "  ·  " + pick[1]);
            b.getStyleClass().add("btn-download");
            b.setMinHeight(36);
            b.setPrefHeight(36);
            b.setOnAction(e -> startRecommendedInstall(pick[0]));
            buttons.getChildren().add(b);
        }
        card.getChildren().addAll(title, desc, buttons);
        return card;
    }

    /**
     * 引导卡里点了某个推荐版本：清单里查得到就直接开安装对话框，
     * 查不到（第一次启动、清单还没拉下来）就先带用户去下载页，别让他点了没反应。
     */
    private void startRecommendedInstall(String id) {
        List<VersionManifest.OfficialVersion> pool = new ArrayList<>(versionRows);
        pool.addAll(VersionManifest.defaultView());
        for (VersionManifest.OfficialVersion v : pool) {
            if (v != null && id.equals(v.id())) {
                selectVersionRow(v.id());
                openInstallDialog(v);
                return;
            }
        }
        showPage(Page.DOWNLOAD);
        showDownloadTab(0);
        toast("版本清单还在加载，稍等一会儿在列表里搜 " + id + " 就行");
    }

    private ScrollPane buildContentScroll() {
        VBox page = new VBox(16);
        page.setPadding(new Insets(20, 20, 0, 20));
        page.setMaxWidth(1180);
        // 下方卡片撑满剩余高度：不撑的话窗口下半截会空出一大片页面底色，比一整张白卡散
        VBox.setVgrow(lowerWide, Priority.ALWAYS);
        VBox.setVgrow(lowerNarrow, Priority.ALWAYS);
        // 0.9.4：「最近游玩」整栏注释掉保留（不是删除，以后再整，功能直接可用）。
        // 直接挂 lowerWide/lowerNarrow：buildLower() 每次调用都会往两个容器各塞一张卡，再调一次会变两张
        // page.getChildren().addAll(buildHero(), lowerWide, lowerNarrow);
        page.getChildren().addAll(buildHero());
        // 0.9.7：一个版本都没装的新用户，启动页整块是空的，不知道该点哪 —— 补一张上手引导卡。
        // 已经装过游戏的用户这里返回 null，界面上完全看不到这张卡。
        firstRunGuideCard = buildFirstRunGuide();
        if (firstRunGuideCard != null) {
            page.getChildren().add(firstRunGuideCard);
        }

        StackPage wrap = new StackPage(page);
        ScrollPane scroll = new ScrollPane(wrap);
        scroll.getStyleClass().add("content-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        // ScrollPane 只有 fitToWidth 没有 fitToHeight：内容比视口矮时下半截会空出一大片页面底色。
        // 让内容至少跟视口一样高：减掉 page 上 padding 与卡片间距；0.9.4 r2 下 padding 归零，
        // 操作条贴到操作区底沿。
        scroll.viewportBoundsProperty().addListener((obs, ov, nv) -> {
            double available = nv == null ? 0 : nv.getHeight() - 20 - 16;
            page.setMinHeight(Math.max(0, available));
        });
        return scroll;
    }

    // ---------------------------------------------------------------- 控制台与启动

    /**
     * 游戏输出控制台。
     *
     * <p>三块：状态、中文失败原因、滚动输出。**失败原因单独占一行**是刻意的——
     * 本版目标是「启动失败要说人话」，让那句话混进几百行日志里，用户等于什么也看不见。
     */
    private ScrollPane buildConsolePage() {
        VBox page = new VBox(16);
        page.setPadding(new Insets(20));
        page.setMaxWidth(1180);

        VBox card = new VBox(14);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(20));

        HBox head = new HBox(10);
        head.setAlignment(Pos.CENTER_LEFT);
        consoleStatus = new Label("还没有启动过游戏");
        consoleStatus.getStyleClass().add("console-status");
        Region headFiller = filler();
        HBox.setHgrow(headFiller, Priority.ALWAYS);
        consoleStop = new Button("停止游戏");
        consoleStop.getStyleClass().add("console-stop");
        consoleStop.setDisable(true);
        consoleStop.setOnAction(e -> stopGame());
        consoleCopy = new Button("复制日志");
        consoleCopy.getStyleClass().add("console-copy");
        consoleCopy.setDisable(true);
        consoleCopy.setOnAction(e -> copyConsole());
        consoleDiag = new Button("导出诊断日志");
        consoleDiag.getStyleClass().add("console-copy");
        consoleDiag.setOnAction(e -> exportDiag());
        head.getChildren().addAll(consoleStatus, headFiller, consoleStop, consoleCopy, consoleDiag);

        consoleReason = new Label("");
        consoleReason.getStyleClass().add("console-reason");
        consoleReason.setWrapText(true);
        consoleReason.setVisible(false);

        consoleArea = new TextArea();
        consoleArea.getStyleClass().add("console-out");
        consoleArea.setEditable(false);

        card.getChildren().addAll(head, consoleReason, consoleArea);
        VBox.setVgrow(consoleArea, Priority.ALWAYS);
        page.getChildren().add(card);

        StackPage wrap = new StackPage(page);
        ScrollPane scroll = new ScrollPane(wrap);
        scroll.getStyleClass().add("content-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        return scroll;
    }

    /** Hero 上那颗「启动游戏」：有进程在跑就是停止键，没有才开一轮新的。 */
    private void onLaunchClick() {
        if (launchSession != null && launchSession.running()) {
            stopGame();
            return;
        }
        startGame();
    }

    /**
     * 开一轮启动。
     *
     * <p>准备阶段（读磁盘、补元信息、解压 natives）放在后台线程——那活儿少说几百毫秒，
     * 堵在 UI 线程上整个窗口会假死；**进程本身必须在 UI 线程拉**（JavaFX 启动窗口要归它管）。
     */
    private void startGame() {
        if (currentVersion == null || currentVersion.isBlank()) {
            return;
        }
        // 硬规则：没账号进不了游戏。离线账号是本期唯一可建类型，外置/微软留待后续。
        if (accountStore.current() == null) {
            showLaunchFailed("还没有账号：点右下角的账号卡打开账号页，添加一个离线或外置账号才能进游戏。");
            return;
        }
        launchSession = null;
        launching = true;
        refreshLaunchButton();
        consoleStalled = false;
        gameStarted = false;
        launchStopped = false;
        launchCancelled.set(false);
        launchTimedOut.set(false);
        launchPrepareDone = false;
        final long token = ++launchEpoch;
        stopPump();
        consoleReason.setVisible(false);
        consoleReason.setText("");
        consoleArea.clear();
        setConsoleStatus("正在准备…");
        consoleStop.setDisable(true);
        openLaunchOverlay(currentVersion);

        Thread worker = new Thread(() -> {
            List<String> notes = new ArrayList<>();
            AccountStore.Account cur = accountStore.current();
            try {
                // 补库可能要一会儿（HMCL 装的老版本动辄缺几十个），progress 实时报回来，
                // 别让按钮点了以后控制台一片安静，看着像又卡住了（v0.4.9）。
                // 顺手也喂给加载层的那行阶段文字：那几秒用户盯着的是窗口中央，不是侧边一条小字
                if (launchAborted(token)) {
                    closeLaunchOverlay();
                    return;
                }
                // 微软账号：MC 令牌约 24 小时过期，快过期就在后台静默刷新，免得用户每次都重登。
                // 0.8.6 D6 根治（HMCL 式）：needsRefresh 只表示「值得试一次刷新」，绝不是能否启动的判据。
                // 刷新失败按 MicrosoftAuth.RefreshKind 结构化分流，不再靠异常文案 contains 猜令牌死没死：
                //   NETWORK / SERVER / INTERRUPTED / ACCOUNT / CONFIG / 未知 → 放行已缓存令牌继续启动；
                //   只有 TOKEN_INVALID 且缓存令牌也已过期、或压根没有缓存令牌，才真的需要重新登录。
                if (cur != null && cur.type == AccountStore.Type.MICROSOFT) {
                    boolean hasCached = cur.accessToken != null && !cur.accessToken.isBlank();
                    if (MicrosoftAuth.needsRefresh(cur.expiresAt)) {
                        pushLaunchStage("正在刷新微软登录…（网络慢时可能要几十秒）", token);
                        try {
                            MicrosoftAuth.MinecraftAccount mc = MicrosoftAuth.refresh(cur.refreshToken);
                            cur = cur.withToken(mc.accessToken, mc.refreshToken, mc.expiresAt);
                            accountStore = accountStore.withTokenRefreshed(
                                    cur.id, mc.accessToken, mc.refreshToken, mc.expiresAt);
                            accountStore.save();
                        } catch (Exception ex) {
                            MicrosoftAuth.RefreshKind kind = MicrosoftAuth.classify(ex);
                            String msg = ex.getMessage() == null ? "" : ex.getMessage();
                            if (kind == MicrosoftAuth.RefreshKind.INTERRUPTED && launchAborted(token)) {
                                closeLaunchOverlay();
                                return;
                            }
                            boolean tokenStillUsable = cur.expiresAt > System.currentTimeMillis();
                            boolean mustRelogin = !hasCached
                                    || (kind == MicrosoftAuth.RefreshKind.TOKEN_INVALID && !tokenStillUsable);
                            if (mustRelogin) {
                                if (launchAborted(token)) {
                                    return;   // 超时 / 取消已经弹过错了，别再弹第二遍
                                }
                                closeLaunchOverlay();
                                String head = switch (kind) {
                                    case ACCOUNT -> "微软账号没通过验证：可能是儿童账号需家长同意、没绑定 Xbox，或没买游戏。重新登录通常没用：";
                                    case CONFIG -> "启动器没法用微软登录，这是启动器本身的问题：";
                                    case TOKEN_INVALID -> "微软登录已过期，重新登录一次就行：";
                                    default -> "本地没有可用的微软登录凭据，且本次刷新未完成（"
                                            + msoRefreshFailureText(kind) + "）：";
                                };
                                showLaunchFailed(head + msg
                                        + "。请到账号页重新登录微软账号（不用删除账号）。",
                                        "这不是游戏本体缺失，去「账号」页重新登录微软账号即可。");
                                return;
                            }
                            // 其余一律放行：网络抖动 / 微软限流 / 账号体系提示 / 未知异常都不再逼用户重登。
                            System.out.println("[msoauth] 后台刷新失败 kind=" + kind
                                    + "（" + msoRefreshFailureText(kind)
                                    + "），本次使用已缓存登录凭据启动：" + msg);
                            notes.add("微软登录没能自动续期，" + msoRefreshFailureText(kind)
                                    + "，这次先按上次登录继续启动；进联机服务器可能要重新登录。");
                        }
                    }
                }
                if (launchAborted(token)) {
                    return;   // 超时 / 取消已收尾，worker 不再碰界面、更不拉进程
                }
                pushLaunchStage("正在检查游戏文件…", token);
                GameLauncher.Plan plan = GameLauncher.prepare(currentVersion,
                        cur,                         note -> {
                            if (launchAborted(token)) {
                                return;
                            }
                            // 1.2：每条补库/校验文案都是进度心跳，长链路不再被墙钟误杀。
                            lastLaunchProgressAt.set(System.nanoTime());
                            String text = note;
                            javafx.application.Platform.runLater(() -> {
                                if (launchStage != null) {
                                    launchStage.setText(text);
                                }
                                setConsoleStatus(text);
                            });
                        });
                // 取消过就别拉进程了：准备白跑一趟没关系，多开一个游戏实例才要命
                if (launchAborted(token)) {
                    closeLaunchOverlay();
                    return;
                }
                notes.addAll(plan.notes());
                // 只有离线（无 token）账号才需要这句提醒：外置账号带着真实 token、已注入
                // authlib-injector，已经能进 LittleSkin 联机服，再说「会一直 401 转圈」就是误导（v0.5.6）
                boolean offline = cur == null || cur.type == AccountStore.Type.OFFLINE;
                if (offline) {
                    // 离线这件事在启动前就说清楚，别等 45 秒了再让人自己猜：
                    // 现在这一局带着空的 accessToken 起，连服务器／Realms 必然 401（Invalid session）
                    // 转圈，表现就是「加载界面一直转、从来进不去」——那是账号模式的必然结果，
                    // 不是启动器/游戏崩了。说了它至少不会被当成故障（v0.4.10 取证：--join 全程 401）
                    notes.add("当前是离线账号：单机可以玩，"
                            + "但进不了联机服务器。"
                            + "想联机，点左下角账号卡换成外置或微软账号。");
                }
                launchPrepareDone = true;
                Platform.runLater(() -> {
                    if (launchAborted(token)) {
                        closeLaunchOverlay();
                        return;
                    }
                    consoleArea.appendText("正在启动 " + plan.id() + "…\n");
                    for (String note : notes) {
                        consoleArea.appendText("[提醒] " + note + "\n");
                    }
                    // 进程马上要起了：加载层退场，剩下的时间交给游戏自己的黑屏
                    closeLaunchOverlay();
                    openSession(plan);
                });
            } catch (GameLauncher.Blocked blocked) {
                launchPrepareDone = true;
                // 1.1.1 P0：这个 catch 在 worker 线程「gbl-launch-prepare」上。原实现直接
                // closeLaunchOverlay()，内部的 rippleLayer.getChildren().clear() 在非 FX 线程抛
                // IllegalStateException，把 catch 拦腰截断，showLaunchFailed 永远执行不到 ——
                // 遮罩不关、launching 不复位、错误框不弹，用户就永久停在「正在校验…」。
                // 关遮罩 + 报错合并投递到 FX 线程，顺序执行；showLaunchFailed 内部再投一层也安全。
                Platform.runLater(() -> {
                    closeLaunchOverlay();
                    // 0.8.6 D5：朋友那种「点了启动没反应、也没有报错」多半死在准备阶段（缺 Java / 缺库），
                    // 以前提示只会误导去下载页重装版本，现在直接把取证入口指出来。
                    showLaunchFailed(blocked.getMessage(),
                            "点「导出诊断」保存文件，可附到反馈中，里面有排查需要的信息。");
                });
            } catch (Exception ex) {
                launchPrepareDone = true;
                Platform.runLater(() -> {
                    closeLaunchOverlay();
                    showLaunchFailed("启动前出了点问题：" + ex,
                            "点「导出诊断」保存文件，可附到反馈中，里面有排查需要的信息。");
                });
            } finally {
                // r4：启动线程无论正常 / 取消 / 异常都要退订数值总线，finally 双保险，
                // 与 closeLaunchOverlay() 里的 clear() 成对且幂等。
                if (token == launchEpoch) {
                    launchProgressActive = false;
                    LaunchProgress.clear();
                }
            }
        }, "gbl-launch-prepare");
        launchWorker = worker;
        worker.setDaemon(true);
        worker.start();

        // 1.2：看门狗不再按墙钟 90 秒误杀。补依赖 / 补资源 / 全量校验都可能合理超过 90 秒；
        // 现在每 5 秒看一次「进度心跳新鲜度」，只有连续 5 分钟没有任何进度帧/阶段推进才判真卡死。
        // 真卡死仍走 1.0.9/1.1.1 的 FX 线程强制收尾：判 launchLayer.isVisible() 真实界面状态，
        // launchEpoch / launchCancelled / launchTimedOut 去重令牌全部保留。
        Thread watchdog = new Thread(() -> {
            long idleNanos;
            try {
                while (true) {
                    Thread.sleep(LAUNCH_WATCHDOG_POLL_SECONDS * 1000L);
                    if (token != launchEpoch || launchCancelled.get()) {
                        return;   // 用户取消 / 开了新一轮：别谎报超时
                    }
                    long last = lastLaunchProgressAt.get();
                    if (last <= 0L) {
                        lastLaunchProgressAt.compareAndSet(0L, System.nanoTime());
                        continue;
                    }
                    idleNanos = System.nanoTime() - last;
                    if (idleNanos < LAUNCH_STALL_TIMEOUT_SECONDS * 1_000_000_000L) {
                        continue;   // 心跳在推进（正在补库/资源/校验）：绝不 interrupt worker
                    }
                    break;          // 真·长时间无进展，下面走强制收尾
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            long stalledSeconds = Math.max(0L, idleNanos / 1_000_000_000L);
            // 1.1.1 P0：真实状态只让 FX 线程自己看；看见还卡着就强制收尾并报错。
            Platform.runLater(() -> {
                if (token != launchEpoch || launchCancelled.get() || launchTimedOut.get()) {
                    return;
                }
                boolean overlayVisible = launchLayer != null && launchLayer.isVisible();
                if (!overlayVisible && !launching) {
                    return;   // 遮罩已关、启动中已复位：真的收尾干净了，不误报超时
                }
                if (!launchTimedOut.compareAndSet(false, true)) {
                    return;   // 收尾只做一次
                }
                // FX runnable 里再核一次心跳：迟到帧可能刚把状态救回来。
                long last = lastLaunchProgressAt.get();
                if (last > 0L && System.nanoTime() - last
                        < LAUNCH_STALL_TIMEOUT_SECONDS * 1_000_000_000L) {
                    launchTimedOut.set(false);   // 心跳恢复，撤销本次收尾，继续等
                    return;
                }
                launchCancelled.set(true);
                if (launchWorker != null) {
                    launchWorker.interrupt();
                }
                System.err.println("[launch] 启动准备已 " + stalledSeconds
                        + " 秒没有收到进度推进，强制收尾");
                closeLaunchOverlay();   // 关遮罩 + 复位按钮 + 退订 LaunchProgress
                showLaunchFailed("启动准备停滞：已 " + stalledSeconds + " 秒没有收到进度更新"
                                + "（正常补依赖/资源会持续刷新进度，不会被中断）。",
                        "可以在「下载」页切换下载源后重试；还不行就点控制台的「导出诊断」保存日志。"
                                + "（账号凭据异常也可能，若刚换过账号可到「账号」页重新登录一次。）");
            });
        }, "gbl-launch-timeout");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** 1.0.9：这一轮的准备是否已作废（取消 / 超时 / 又开了新一轮）。 */
    private boolean launchAborted(long token) {
        return token != launchEpoch || launchCancelled.get() || launchTimedOut.get();
    }

    /** 1.0.9：准备阶段的阶段文字推到 FX 线程，避免网络请求期间界面一片安静。 */
    private void pushLaunchStage(String text, long token) {
        // 1.2：阶段文字推进也算心跳——补库前的长校验即使没有数值帧，也证明 worker 在走。
        if (token == launchEpoch) {
            lastLaunchProgressAt.set(System.nanoTime());
        }
        Platform.runLater(() -> {
            if (token != launchEpoch || launchCancelled.get()) {
                return;
            }
            if (launchStage != null) {
                launchStage.setText(text);
            }
            setConsoleStatus(text);
        });
    }

    /** 拉起进程并开输出泵；拉不起就 commit 一句实话上去。 */
    private GameLauncher.Session openSession(GameLauncher.Plan plan) {
        try {
            launchSession = GameLauncher.start(plan, null);
        } catch (IOException ex) {
            // 进程都没起来时 logs/crash-reports 一定是空的，靠 launch-info.txt 取证。
            launching = false;
            refreshLaunchButton();
            showLaunchFailed("游戏进程没拉起来：" + ex,
                    "点「导出诊断」保存文件，可用于反馈。");
            return null;
        }
        setConsoleStatus("游戏正在运行 " + plan.id());
        consoleStop.setDisable(false);
        launching = false;
        refreshLaunchButton();
        consoleCopy.setDisable(false);
        gameStarted = false;   // 这一局还没「真正起来」

        // v0.4.13：开游戏时**先不收起**启动器，等游戏日志确认窗口已经起来
        // （Reloading ResourceManager / OpenAL initialized）再收起。这样游戏卡加载界面、
        // 或刚起来就崩时，启动器仍可见、你能直接看到报错，而不是一闪收起啥也看不见。
        // 收起后 AnimationTimer 会停跳，所以退出检测和唤回交给独立的看门狗线程。
        outputPump = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                if (launchSession == null) {
                    stop();
                    return;
                }
                for (String line : launchSession.drain()) {
                    consoleArea.appendText(line + "\n");
                    // 游戏窗口已起来：把启动器收进任务栏，别让启动器(D3D)和游戏(OpenGL)
                    // 两个 GL 上下文一起跑——老驱动下 DWM 会卡（太卡/退出冻的诱因之一）。
                    String low = line.toLowerCase(Locale.ROOT);
                    if (!gameStarted
                            && (low.contains("reloading resourcemanager")
                                || low.contains("openal initialized"))) {
                        gameStarted = true;
                        if (!stage.isIconified()) {
                            javafx.application.Platform.runLater(() -> {
                                setConsoleStatus("游戏已启动，启动器已收起（退出游戏后自动回来）");
                                if (!stage.isIconified()) {
                                    stage.setIconified(true);
                                }
                            });
                        }
                    }
                }
        }
        };
        outputPump.start();
        startSessionWatcher();
        // 0.9.6 C6：启动后关闭启动器。游戏是 GameLauncher.start 拉起的独立进程，
        // Platform.exit() 只退 JavaFX（关掉启动器窗口与界面），不会带走游戏；
        // 项目也没有在退出时 destroy 游戏进程的钩子。
        // 0.9.8 r6：这条也是真退出，走统一收口（摘常驻托盘图标 + 释放单实例锁 / 唤醒通道）。
        if (settings.isCloseAfterLaunch()) {
            SingleInstance.shutdown();
            javafx.application.Platform.exit();
        }
        return launchSession;
    }

    /**
     * 游戏生命周期看门狗：独立的 daemon 线程，每秒轮询一次，
     * **不依赖 JavaFX 的 AnimationTimer**——启动器在游戏运行期被最小化后，
     * FX 的动画脉冲会停跳，退出检测必须靠这条线程才能发现并唤回窗口（v0.4.11）。
     */
    private void startSessionWatcher() {
        final GameLauncher.Session s = launchSession;
        if (s == null) {
            return;
        }
        Thread w = new Thread(() -> {
            if (!s.running()) {
                // 1.0.9：进程在 watcher 起来之前就退了（启动失败很常见），
                // 旧代码 while 体一次都不跑，12s / 45s 提示与 finalizeSession 全被跳过。
                try {
                    Thread.sleep(150);   // 给输出 reader 最后一点时间把日志灌进 tail
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                javafx.application.Platform.runLater(() -> finalizeSession(s));
                return;
            }
            while (s.running()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // 运行期的人话提示（启动崩 / 卡加载）放这里，不依赖 FX 动画脉冲
                if (!consoleStalled && s.startupCrash() && s.idleMillis() > 12_000) {
                    consoleStalled = true;
                    String why = GameLauncher.explain(s);
                    javafx.application.Platform.runLater(() -> {
                        consoleReason.setText((why == null
                                        ? "游戏刚启动就崩了（Console 里是启动期异常）"
                                        : why)
                                + "\n进程还挂着但已经不动了，点「停止游戏」把它收掉；"
                                + "完整输出在上面，也可以去 .minecraft/crash-reports 找最新那份");
                        consoleReason.setVisible(true);
                        setConsoleStatus("游戏崩了（进程还在）");
                    });
                } else if (!consoleStalled && s.idleMillis() > 45_000) {
                    consoleStalled = true;
                    String joined = String.join(" ", s.tailLines()).toLowerCase(Locale.ROOT);
                    boolean offline = joined.contains("401")
                            || joined.contains("invalid session")
                            || joined.contains("invalidsession");
                    // 0.9.3 r2：离线 401 只在真·离线联网语境才提示，别把单机黑屏往「没正版验证」上套；
                    // 另外补一条 shader/驱动排查提示（画面停在加载界面/黑屏的常见原因）。
                    String offlineTip = offline
                            ? "· 离线账号：单机可以玩，"
                              + "但进不了联机服务器。想联机，点左下角账号卡换成外置或微软账号。\n"
                            : "";
                    String shaderTip = "· 一直卡在加载界面或黑屏："
                              + "先关掉光影再进一次游戏试试。\n";
                    javafx.application.Platform.runLater(() -> {
                        consoleReason.setText("游戏已经 45 秒没有新日志了，进主菜单后本来就不写日志，"
                                + "不一定代表卡住；若画面一直停着，多半是下面几个原因：\n"
                                + shaderTip
                                + offlineTip
                                + "· 真卡死时点「停止游戏」收不掉也正常（进程锁在驱动的原生调用里），"
                                + "那就按 Ctrl+Shift+Esc 在系统任务管理器里结束 Minecraft\n"
                                + "· 卡在哪个版本是看版本运气的事，换个版本启动一次就能反证\n"
                                + "这份日志在下面，去 .minecraft/crash-reports 也能找最新报告");
                        consoleReason.setVisible(true);
                        setConsoleStatus("游戏 45 秒没有新日志（可能在主菜单，也可能卡住）");
                    });
                }
            }
            // 游戏结束了：回 FX 线程收尾（唤回启动器、置状态、判崩/停止）
            javafx.application.Platform.runLater(() -> finalizeSession(s));
        }, "gbl-session-watcher");
        w.setDaemon(true);
        w.start();
    }

    /** 游戏进程结束后在 FX 线程收尾。{@code s} 是这一局抓到的 Session，防止开新局后被误清。 */
    private void finalizeSession(GameLauncher.Session s) {
        if (launchSession != s) {
            return;   // 已经开新一局了，这局的收尾别动新局的现场
        }
        if (outputPump != null) {
            outputPump.stop();
        }
        int code = s.exitCode();
        consoleStalled = false;
        launchSession = null;
        launching = false;
        refreshLaunchButton();
        final boolean startedThisRound = gameStarted;
        gameStarted = false;
        // 游戏进程已死：立刻把 options.txt 重新钉成 OpenGL + 干净退出，覆盖游戏自己回写的内容，
        // 打破 Mojang「意外关机恢复」把图形后端翻回 Vulkan 的振荡——正是 26.x「一些版本全黑 /
        // 卡主界面 / 退出冻到息屏」的根（详见 GameLauncher.pinGraphicsOptions）。
        GameLauncher.pinGraphicsOptions();
        consoleStop.setDisable(true);
        // 游戏刚结束那一刻是驱动最脆弱的窗口：MC 正在拆 OpenGL 上下文、DWM 正要恢复合成。
        // 此刻千万别强行让启动器(D3D)弹回呈现——两件事撞一起就是「退出后整桌卡死、
        // 息屏再开才好」的元凶（v0.4.12）。先保持收起，延迟几秒等驱动落定再唤回；
        // 这期间用户点任务栏图标 / 托盘也能自己唤回。
        if (stage.isIconified()) {
            setConsoleStatus("游戏已退出（退出码 " + code + "），启动器稍后自动恢复…");
            restoreStageLater(3);
        } else {
            setConsoleStatus("游戏已退出（退出码 " + code + "）");
        }
        if (launchStopped) {
            // 是我们自己点的「停止游戏」：退出码必然是 1（TerminateProcess），别报成异常退出
            setConsoleStatus("游戏已停止，启动器稍后自动恢复…");
            consoleReason.setText("已按「停止游戏」把进程收掉了。"
                    + "如果它经常停在加载界面收不掉，多半是卡在驱动/网络调用上，"
                    + "这时候系统任务管理器里结束 Minecraft 反而更快");
            consoleReason.setVisible(true);
            launchStopped = false;
            return;
        }
        String reason = GameLauncher.explain(s, startedThisRound);
        if (reason != null) {
            showLaunchFailed(reason);
        }
    }

    /** 游戏结束后别立刻把启动器弹回：MC 拆 OpenGL 上下文、DWM 恢复合成的这一两秒是驱动最脆弱的
     *  窗口，此刻强行让启动器(D3D)呈现会和它撞车，表现就是「退出后整桌卡死、息屏再开才好」。
     *  延迟几秒等驱动落定再唤回；这期间用户点任务栏图标 / 托盘也能自己唤回（v0.4.12）。
     *  期间若又开了新游戏（openSession 会重新收起），就不抢着弹回，免得偷走新游戏的焦点。 */
    private void restoreStageLater(int delaySec) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(delaySec * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            javafx.application.Platform.runLater(() -> {
                if (stage.isIconified() && launchSession == null) {
                    stage.setIconified(false);
                    stage.toFront();
                }
            });
        }, "gbl-restore-stage");
        t.setDaemon(true);
        t.start();
    }

    /** 结束这一轮：进程加中文原因一起落上去。 */
    /** 0.8.6 D6：把刷新失败类别翻译成人话，启动放行提示与皮肤上传提示共用，不再解析异常文案。 */
    private static String msoRefreshFailureText(MicrosoftAuth.RefreshKind kind) {
        if (kind == null) {
            return "原因未明的瞬时故障";
        }
        return switch (kind) {
            case TOKEN_INVALID -> "登录已过期";
            case NETWORK -> "网络不通、超时或瞬时故障";
            case SERVER -> "微软服务暂时不可用，稍后再试";
            case ACCOUNT -> "账号没通过验证（儿童账号、没绑定 Xbox 或没买游戏）";
            case CONFIG -> "微软登录暂时用不了";
            case INTERRUPTED -> "刷新被中断";
        };
    }

    /** 保持旧调用点兼容：版本没装好这类失败沿用原来的引导。 */
    private void showLaunchFailed(String reason) {
        showLaunchFailed(reason, "去「下载」页装好这个版本再启动");
    }

    /** 0.8.6：不同失败原因给不同的下一步，别把所有故障都甩给「重装版本」。 */
    private void showLaunchFailed(String reason, String versionHint) {
        Platform.runLater(() -> {
            consoleReason.setText(reason);
            consoleReason.setVisible(true);
            // 多问题聚合时 reason 可能是一大段带换行的文本：显式放开高度上限，
            // 让红框按内容自适应（外层是 ScrollPane，全部问题都能滚动看到，不截断）。
            consoleReason.setMaxHeight(Double.MAX_VALUE);
            setConsoleStatus("启动失败");
            launching = false;
            refreshLaunchButton();
            // 完整多行 reason 也写进可滚动日志区，保留换行、不丢后半截。
            appendConsole("启动失败：" + reason + "\n（" + versionHint + "）\n");
            // 顺手把人带这一页：没装客户端 jar 那类版本（26w06a、beta-1.7.3 一堆都是空的
            // 目录，只剩个 json）启动只会吐一行角落里的黄字——人还站在「游戏」页盯着列表，
            // 看着就跟点了没反应一样（v0.5「一些版本启动后卡住」其实就是这个）
            showPage(Page.CONSOLE);
        });
    }

    /** 结束游戏进程（控制台上的「停止游戏」）。 */
    private void stopGame() {
        if (launchSession == null) {
            return;
        }
        // 一句人话：MC 收尾那几秒（关世界、关 OpenAL、刷日志）会吃掉一整核，
        // 电脑整个顿一下。说清楚「它在收尾」比让用户以为又卡了强（v0.5）
        setConsoleStatus("游戏正在收尾（关世界 / 释放显卡），请稍候…");
        // 打上「是我们收的」这个章：进程一没，泵那边就不会再把它报成「异常退出」
        launchStopped = true;
        // 这里是 UI 线程，**绝对不能等进程退完**：老实现在 Session.stop() 里
        // waitFor(3s)，游戏卡在原生调用里时整个界面（连带桌面）会跟着顿好几秒。
        // 现在 stop() 只发一次终止信号，等它退是 daemon 收尸线程的事（v0.4.10）
        GameLauncher.Session target = launchSession;
        target.stop();

        // 收尾看门狗：正常的情况下 target 自己就退了，等满 8 秒说明它锁在原生调用里。
        // 与其让用户自己去任务管理器强杀，不如这里直接 destroyForcibly 收掉，并留一句实话。
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(STOP_FORCE_AFTER_SECONDS * 1000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            if (target == null || !target.running()) {
                return;                       // 已经自己退了，别多事
            }
            target.killNow();
            Platform.runLater(() -> {
                if (launchSession == target) {
                    appendConsole("[提醒] 点了「停止游戏」但游戏 " + STOP_FORCE_AFTER_SECONDS
                            + " 秒还没退，已经强制结束进程——它多半卡在驱动的原生调用里。"
                            + "以后真卡死时 Ctrl+Shift+Esc 结束 Minecraft 仍然是最快的一招。\n");
                }
            });
        }, "gbl-stop-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void copyConsole() {
        if (consoleArea == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(launchSession == null ? consoleArea.getText()
                : String.join("\n", launchSession.tailLines()));
        Clipboard.getSystemClipboard().setContent(content);
        // 日志已经出去了，按钮这时点不动就不必再劝，给一句人话收尾
        if (consoleCopy != null) {
            consoleCopy.setDisable(true);
        }
    }

    /**
     * 0.9.3 对话框深色化：new Alert(...) 会自建独立 Stage，既继承不到 shell 的 theme.css，
     * 也拿不到 shell 上的 .dark 类，于是整窗（连同按钮）都是 modena 默认浅灰 + 青色焦点环。
     * 这里手动把 theme.css 挂到对话框面板上，并在深色主题下补 .dark 类，
     * 由 CSS 的 .dark .button 兜底把按钮也带上 GBL 风格。
     */
    private static final String DIALOG_DARK_MARK = "0.9.3 对话框深色化";

    private void styleAlert(Alert alert) {
        String mark = DIALOG_DARK_MARK;   // 0.9.3 对话框深色化：标记随主题一并生效
        alert.getDialogPane().getStylesheets()
                .add(ShellView.class.getResource("/com/mclauncher/theme.css").toExternalForm());
        if (themeDark) {
            alert.getDialogPane().getStyleClass().add("dark");
        }
    }

    private void exportDiag() {
        try {
            Path zip = GameLauncher.exportDiagnostics();
            if (zip == null) {
                Alert diagFail = new Alert(Alert.AlertType.INFORMATION,
                        "诊断文件未生成。可截图控制台启动日志，附到反馈中。");
                styleAlert(diagFail);
                diagFail.showAndWait();
                return;
            }
            // 0.8.6 D5：即使游戏还没起来（进程都没拉起），launch-info.txt 也会带上
            // Java 路径、启动命令、缺失库和版本信息，别再说「没有可导出的日志」。
            Alert diagOk = new Alert(Alert.AlertType.INFORMATION,
                    "诊断文件已保存到：\n" + zip
                            + "\n\n里面有排查需要的信息，可用于反馈。");
            styleAlert(diagOk);
            diagOk.showAndWait();
            com.mclauncher.Platform.reveal(zip.getParent());
        } catch (IOException ex) {
            Alert diagErr = new Alert(Alert.AlertType.ERROR, "导出失败：" + ex.getMessage());
            styleAlert(diagErr);
            diagErr.showAndWait();
        }
    }

    private void stopPump() {
        if (outputPump != null) {
            outputPump.stop();
            outputPump = null;
        }
    }

    private void setConsoleStatus(String text) {
        if (consoleStatus != null) {
            consoleStatus.setText(text);
        }
    }

    private void appendConsole(String text) {
        if (consoleArea != null) {
            consoleArea.appendText(text);
        }
    }

    private VBox buildHero() {
        VBox hero = new VBox(18);
        hero.getStyleClass().addAll("card", "hero");
        hero.setPadding(new Insets(24, 24, 8, 24));

        // 0.9.4：启动页改成「上部说明 + 底部操作条」。hero 由 page 撑满剩余高度，
        // 顶部弹簧把底部操作条顶到操作区下沿：版本号大字号在左、两颗按钮在右。
        VBox left = new VBox(10);
        left.setAlignment(Pos.CENTER_LEFT);
        // 一个游戏都没装：Hero 标题说事实，desc 顺便把人引到「下载」去
        heroTitle = new Label("还没有游戏");
        heroTitle.getStyleClass().add("hero-title");
        heroDesc = new Label("暂无游戏，可在「下载」页安装。");
        heroDesc.getStyleClass().add("hero-desc");
        heroDesc.setWrapText(true);
        heroDesc.setMaxWidth(460);
        left.getChildren().addAll(heroTitle, heroDesc);

        // 右端按钮区：两颗按钮竖排（启动游戏在上）、右对齐、等宽加大（0.9.5 反馈）。
        VBox right = new VBox();
        right.setAlignment(Pos.CENTER_RIGHT);
        right.setMinWidth(220.0);
        VBox buttons = new VBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setFillWidth(true);
        heroPlay = new Button("启动游戏");
        heroPlay.setGraphic(Icons.create(Icons.PLAY, 16.0));
        heroPlay.setGraphicTextGap(9.0);
        heroPlay.getStyleClass().add("btn-play");
        // 独立类名：Snapshot 与「跑一局游戏」的自测要点名点它，别去摘 btn-play（那一类有好几颗）
        heroPlay.getStyleClass().add("hero-play");
        heroPlay.setMinHeight(54.0);
        heroPlay.setMaxWidth(Double.MAX_VALUE);
        heroPlay.setOnAction(e -> onLaunchClick());
        Button folder = new Button("打开游戏目录");
        folder.setGraphic(Icons.create(Icons.FOLDER, 16.0));
        folder.setGraphicTextGap(9.0);
        folder.getStyleClass().add("btn-ghost");
        // 0.9.6 r3 B4：独立类名，供 theme.css 用限定选择器只放大这两颗按钮的字号。
        folder.getStyleClass().add("hero-open-dir");
        folder.setMinHeight(54.0);
        folder.setMaxWidth(Double.MAX_VALUE);
        // r4：原来这颗按钮没有 setOnAction，纯装饰 → 点了必然没反应。
        // 复用管理页已验证的 runDiskOp(GameScanner.openFolder) 写法；但 Platform.reveal 对
        // 不存在的路径会静默 return，所以先确保游戏目录在（只创建不删除），失败给可见提示。
        folder.setOnAction(e -> {
            if (currentGameId == null) {
                toast("还没有当前游戏");
                return;
            }
            Path dir;
            try {
                // r4：用 public 的 gameDirFor 自己拼出 UI 当前游戏目录（不依赖 openFolder
                // 内部私有的 gameDir 解析），currentGameId 带加载器后缀时也按同一 id 落地。
                dir = GameScanner.gameDirFor(currentGameId);
                Files.createDirectories(dir);   // 只创建不删除：reveal 对不存在的路径会静默 return
            } catch (IOException | RuntimeException ex) {
                String msg = "打不开游戏目录（目录创建失败）：" + ex.getMessage();
                toast(msg);
                setConsoleStatus(msg);
                return;
            }
            if (!Files.isDirectory(dir)) {
                String msg = "打不开游戏目录（目录不可用）：" + dir;
                toast(msg);
                setConsoleStatus(msg);
                return;
            }
            // 目录已确认存在，复用管理页已验证的 runDiskOp(GameScanner.openFolder) 写法。
            // gameDirFor 与 openFolder 内部 gameDir 同源（都是 versionsDir().resolve(id)），
            // 不存在「解析路径与 UI 当前目录不一致」；即便将来服务层改了解析规则导致
            // openFolder 抛错，runDiskOp 也只记日志，下面的可见反馈仍保证按钮点了有反应。
            runDiskOp(() -> GameScanner.openFolder(currentGameId), "打开文件夹");
            String ok = "已打开游戏目录：" + dir;   // r4：点击后至少有一个可见反馈，绝不静默
            toast(ok);
            setConsoleStatus(ok);
        });
        buttons.getChildren().addAll(heroPlay, folder);
        right.getChildren().add(buttons);

        heroLeft = left;
        heroRight = right;

        // 底部操作条：左端文案 + 中段弹簧 + 右端按钮，天然左右分置（HBox 没有 SPACE_BETWEEN 枚举）。
        HBox actionBar = new HBox(10);
        actionBar.getStyleClass().add("hero-actionbar");
        actionBar.setAlignment(Pos.CENTER_LEFT);
        Region barFiller = new Region();
        HBox.setHgrow(barFiller, Priority.ALWAYS);
        actionBar.getChildren().addAll(left, barFiller, right);

        // 顶部弹簧把操作条顶到 hero 底部；hero 自身由 page 的 VBox.setVgrow 撑满剩余高度。
        Region topSpacer = new Region();
        VBox.setVgrow(topSpacer, Priority.ALWAYS);
        hero.getChildren().addAll(topSpacer, actionBar);
        // 0.9.4：hero 撑满 page 剩余高度，操作条才落到操作区底部。
        VBox.setVgrow(hero, Priority.ALWAYS);

        refreshHero();
        return hero;
    }

    /**
     * Hero 的文案与按钮一起由「现在有没有游戏」决定。
     *
     * 没有游戏时右侧整块（heroRight）都不挂：那块里本来就只有「启动游戏 / 打开游戏目录」两个按钮，
     * 留着就是一个空壳容器 + 一个撑着 minWidth 的空洞，不如直接收掉，让左边文案占满整张卡。
     */
    private void refreshHero() {
        boolean hasGame = currentVersion != null && !currentVersion.isBlank();
        boolean hasAccount = accountStore.current() != null;
        heroTitle.setText(hasGame ? currentVersion : "还没有游戏");
        if (!hasGame) {
            heroDesc.setText("暂无游戏，可在「下载」页安装。");
        } else if (!hasAccount) {
            heroDesc.setText("还没选账号：点右下角的账号卡打开账号页，添加一个离线账号才能进游戏。");
        } else {
            heroDesc.setText("点「启动游戏」进入这个版本（当前账号：" + accountStore.current().name + "）。");
        }
        // 既没游戏又没账号时，整块启动按钮收掉：无账号进不了游戏是硬规则
        heroRight.setVisible(hasGame && hasAccount);
        heroRight.setManaged(hasGame && hasAccount);
        refreshLaunchButton();
    }

    /** 按 launchSession / launching 刷新 Hero 按钮：文案 / 图标 / 颜色类 / 禁用态。必须在 FX 线程调用。 */
    private void refreshLaunchButton() {
        if (heroPlay == null) return;
        boolean running = launchSession != null && launchSession.running();
        heroPlay.setDisable(launching);
        heroPlay.getStyleClass().remove("btn-play-running");
        if (launching) {
            heroPlay.setText("启动中…");
            heroPlay.setGraphic(Icons.create(Icons.PLAY, 22));
        } else {
            heroPlay.setText(running ? "停止游戏" : "启动游戏");
            heroPlay.setGraphic(Icons.create(running ? Icons.STOP : Icons.PLAY, 22));
            if (running) heroPlay.getStyleClass().add("btn-play-running");
        }
        if (consoleStop != null) consoleStop.setDisable(!running);
    }

    /**
     * 环境状态卡（Java 运行时 / 游戏目录 / 分配内存）。
     *
     * v0.1.1 起主界面不再挂载这组卡，为 Hero 与下方卡片腾出纵向空间。
     * 数据模型与样式都在，接环境检测（模块 2/3）后把三行 statCard 塞回
     * statsWide（HBox）与 statsNarrow（VBox）即可恢复，宽窄仍是两套容器。
     */
    private VBox statCard(String label, String value, String hint, String icon) {
        HBox head = new HBox(8);
        head.setAlignment(Pos.CENTER_LEFT);
        SVGPath iconNode = Icons.create(icon, 17);
        iconNode.getStyleClass().add("stat-icon");
        Label labelText = new Label(label);
        labelText.getStyleClass().add("stat-label");
        head.getChildren().addAll(iconNode, labelText);

        VBox card = new VBox(6);
        card.getStyleClass().add("card");
        card.getStyleClass().add("stat-card");
        card.setPadding(new Insets(16, 18, 16, 18));

        Label valueText = new Label(value);
        valueText.getStyleClass().add("stat-value");
        Label hintText = new Label(hint);
        hintText.getStyleClass().add("stat-hint");

        card.getChildren().addAll(head, valueText, hintText);
        return card;
    }

    private HBox buildLower() {
        // 宽窄两套容器各要一张卡：同一个 Node 不能有两个父节点，这里必须新建
        VBox wide = infoCard();
        // 撑满剩余高度：否则窗口下半截会空出一大片页面底色，视觉上比一张大白卡散
        VBox.setVgrow(wide, Priority.ALWAYS);
        lowerWide.getChildren().add(wide);
        lowerWide.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));
        lowerNarrow.getChildren().addAll(infoCard());
        return lowerWide;
    }

    private VBox infoCard() {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        card.setPadding(new Insets(20));

        Label title = new Label("最近游玩");
        title.getStyleClass().add("card-title");

        // 实际状态就是「没装版本、没玩过」：空列表给一句居中提示，别留一片白。
        // 等有游玩记录了，refreshPlayCard() 会把空态换成 playRow(...) 行（playRow 留着就是为了这个）
        VBox empty = emptyHint("暂无游玩记录");
        VBox rows = new VBox(6);
        rows.setFillWidth(true);
        rows.setVisible(false);
        rows.setManaged(false);
        playBodies.add(new PlayCardBody(empty, rows));
        card.getChildren().addAll(title, empty, rows);
        return card;
    }

    /** 空态与游玩记录行换着显示，同一时刻只有一份参与布局。 */
    private void refreshPlayCard() {
        boolean showRows = hasPlayHistory && currentVersion != null && !currentVersion.isBlank();
        for (PlayCardBody body : playBodies) {
            body.empty().setVisible(!showRows);
            body.empty().setManaged(!showRows);
            if (showRows && body.rows().getChildren().isEmpty()) {
                // 行只在第一次有记录时建：当前版本名进标题栏，副行指向启动入口
                body.rows().getChildren().add(playRow(currentVersion, "点「启动游戏」继续玩", Icons.PLAY));
            }
            body.rows().setVisible(showRows);
            body.rows().setManaged(showRows);
        }
    }

    // ---------------------------------------------------------------- UI 状态入口
    // 界面现在还读不到「装了哪些版本 / 玩过哪些版本」，所以下面两个开关都停在空态，
    // 也就是你真实的当前状态：一个游戏没装、一次游戏没玩。
    // 等接上版本扫描与启动流程后，服务层拿到结果调一次，这两块界面就自己跟着变（文案 / 按钮 / 列表一起换），
    // 不用回来改界面代码。

    /** 当前可启动的版本；传 null 或空串表示「还没有游戏」（没游戏就不给任何启动类按钮）。 */
    public void setCurrentVersion(String version) {
        this.currentVersion = (version == null || version.isBlank()) ? null : version;
        refreshHero();
        refreshPlayCard();
    }

    /** 最近游玩有没有记录；false 时「最近游玩」卡显示空态，true 时换成当前版本的游玩行。 */
    public void setHasPlayHistory(boolean has) {
        this.hasPlayHistory = has;
        refreshPlayCard();
    }

    /** 空态块：吃掉卡片剩余高度，内容整体居中。 */
    private VBox emptyHint(String text) {
        VBox box = new VBox(10);
        box.setAlignment(Pos.CENTER);
        box.setFillWidth(false);
        VBox.setVgrow(box, Priority.ALWAYS);

        SVGPath icon = Icons.create(Icons.CUBE, 26);
        icon.getStyleClass().add("empty-icon");
        Label hint = new Label(text);
        hint.getStyleClass().add("empty-hint");
        box.getChildren().addAll(icon, hint);
        return box;
    }

    private HBox playRow(String title, String hint, String icon) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 8, 8, 8));
        row.getStyleClass().add("row");

        VBox text = new VBox(2);
        Label t = new Label(title);
        t.getStyleClass().add("row-title");
        Label h = new Label(hint);
        h.getStyleClass().add("row-hint");
        text.getChildren().addAll(t, h);

        Rectangle dot = new Rectangle(30, 30);
        dot.setArcWidth(30);
        dot.setArcHeight(30);
        dot.getStyleClass().add("row-badge");   // 底色归 CSS

        StackPane badge = new StackPane(dot, Icons.create(icon, 14));
        badge.getStyleClass().add("row-badge-wrap");

        Region filler = filler();
        HBox.setHgrow(filler, Priority.ALWAYS);
        row.getChildren().addAll(text, filler, badge);
        return row;
    }

    // ---------------------------------------------------------------- 状态栏

    /** 状态栏只留版本号：状态文字与顶栏的就绪 pill 重复，操作提示属于调试期信息，都不要。 */
    private HBox buildStatusBar() {
        HBox bar = new HBox(10);
        bar.getStyleClass().add("status-bar");
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(0, 20, 0, 20));

        Region filler = filler();
        HBox.setHgrow(filler, Priority.ALWAYS);
        bar.getChildren().addAll(filler,
                chip(LauncherApp.DISPLAY_VERSION, "chip"));
        return bar;
    }

    // ---------------------------------------------------------------- 无边框窗口的边缘缩放

    /** UNDECORATED 后系统不再给可拉伸边框，这里自己接：贴边 6px 内换光标，拖动按方向改窗口尺寸。 */
    private static void installResizer(Stage stage, Scene scene) {
        // 1.3.1：7 位 = x, y, w, h, 方向位掩码, 按下时鼠标屏幕 X, 按下时鼠标屏幕 Y。
        // 旧实现把 stage.getX()/getY() 当「按下时鼠标坐标」算 dx/dy，第一次拖动必然乱跳。
        final double[] st = new double[7];

        scene.setOnMouseMoved(e -> {
            if (stage.isMaximized()) {
                // 最大化时窗口贴着屏幕边，边缘缩放区在屏幕外，这里只能给回普通箭头（原来是 Cursor.NONE，光标会直接消失）
                scene.setCursor(Cursor.DEFAULT);
                return;
            }
            scene.setCursor(cursorFor(edgeOf(scene, e)));
        });
        scene.setOnMousePressed(e -> {
            st[4] = stage.isMaximized() ? 0 : edgeOf(scene, e);
            if (st[4] != 0) {
                st[0] = stage.getX();
                st[1] = stage.getY();
                st[2] = stage.getWidth();
                st[3] = stage.getHeight();
                st[5] = e.getScreenX();   // 按下鼠标的屏幕坐标，才是 dx/dy 的正确基准
                st[6] = e.getScreenY();
            }
        });
        scene.setOnMouseReleased(e -> st[4] = 0);
        scene.setOnMouseDragged(e -> {
            int dir = (int) st[4];
            if (dir == 0) return;
            double[] g = computeResizerGeometry(st[5], st[6], st[0], st[1], st[2], st[3],
                    e.getScreenX(), e.getScreenY(), dir, stage.getMinWidth(), stage.getMinHeight());
            if (g[0] != st[0]) stage.setX(g[0]);
            if (g[1] != st[1]) stage.setY(g[1]);
            if (g[2] != st[2]) stage.setWidth(g[2]);
            if (g[3] != st[3]) stage.setHeight(g[3]);
        });
    }

    /** 1.3.1：窗口缩放几何（纯函数，QA 可直测）。dx/dy 基于按下时鼠标屏幕坐标，不再用窗口原点。 */
    static double[] computeResizerGeometry(double pmx, double pmy, double sx, double sy, double sw, double sh,
                                           double mx, double my, int dir, double minW, double minH) {
        double dx = mx - pmx;
        double dy = my - pmy;
        double w = sw, h = sh, x = sx, y = sy;
        if ((dir & EDGE_W) != 0) {
            w = sw - dx;
            if (w < minW) {
                x = sx + (sw - minW);
                w = minW;
            } else {
                x = sx + dx;
            }
        } else if ((dir & EDGE_E) != 0) {
            w = Math.max(minW, sw + dx);
        }
        if ((dir & EDGE_N) != 0) {
            h = sh - dy;
            if (h < minH) {
                y = sy + (sh - minH);
                h = minH;
            } else {
                y = sy + dy;
            }
        } else if ((dir & EDGE_S) != 0) {
            h = Math.max(minH, sh + dy);
        }
        return new double[]{x, y, w, h};
    }

    /** 1.3.1 QA 钩子：模拟一次拖动后的窗口几何 {x, y, w, h}，验证鼠标坐标基准。 */
    public static double[] resizerGeometryForTests(double pressedMouseX, double pressedMouseY,
                                                   double stageX, double stageY, double stageW, double stageH,
                                                   double dragMouseX, double dragMouseY, int dir,
                                                   double minW, double minH) {
        return computeResizerGeometry(pressedMouseX, pressedMouseY, stageX, stageY, stageW, stageH,
                dragMouseX, dragMouseY, dir, minW, minH);
    }

    private static int edgeOf(Scene scene, MouseEvent e) {
        int dir = 0;
        if (e.getSceneX() <= EDGE) dir |= EDGE_W;
        if (e.getSceneX() >= scene.getWidth() - EDGE) dir |= EDGE_E;
        if (e.getSceneY() <= EDGE) dir |= EDGE_N;
        if (e.getSceneY() >= scene.getHeight() - EDGE) dir |= EDGE_S;
        return dir;
    }

    private static Cursor cursorFor(int dir) {
        switch (dir) {
            case EDGE_W:
            case EDGE_E:
                return Cursor.H_RESIZE;
            case EDGE_N:
            case EDGE_S:
                return Cursor.V_RESIZE;
            case EDGE_W | EDGE_N:
            case EDGE_E | EDGE_S:
                return Cursor.NW_RESIZE;
            case EDGE_E | EDGE_N:
            case EDGE_W | EDGE_S:
                return Cursor.NE_RESIZE;
            default:
                return Cursor.DEFAULT;
        }
    }

    // ---------------------------------------------------------------- 工具

    private static Region filler() {
        return new Region();
    }

    private static Label chip(String text, String styleClass) {
        Label label = new Label(text);
        // getStyleClass().add("chip chip-accent") 会把整串当一个类名，
        // chip-accent 从此永远匹配不上，字色退回 modena 默认的白 —— 浅色底上就是白字看不见。
        // 多个类名必须按空格拆开逐条 add。
        for (String one : styleClass.split("\\s+")) {
            if (!one.isEmpty()) {
                label.getStyleClass().add(one);
            }
        }
        return label;
    }

    /** 响应式：宽度变化时切换侧栏形态、统计卡片排布与 Hero 方向。 */
    private void applyResponsive(double width) {
        boolean narrowNav = width < NAV_BREAK;
        double navWidth = narrowNav ? NAV_NARROW : NAV_WIDE;
        nav.setPrefWidth(navWidth);
        nav.setMinWidth(navWidth);
        navSection.setVisible(!narrowNav);
        userCard.setVisible(!narrowNav);
        for (HBox item : navItems) {
            item.setAlignment(narrowNav ? Pos.CENTER : Pos.CENTER_LEFT);
            item.getChildren().get(1).setVisible(!narrowNav);
        }

        boolean stack = width < STACK_BREAK;
        lowerWide.setVisible(!stack);
        lowerWide.setManaged(!stack);
        lowerNarrow.setVisible(stack);
        lowerNarrow.setManaged(stack);

        // 0.9.4：启动页改为单一底部操作条（buildHero 的 hero-actionbar），heroLeft/heroRight
        // 常驻该操作条，宽窄切换不再用 heroBodyWide/heroBodyNarrow 搬运节点——搬运会把
        // 节点从操作条里搬走，导致操作条空白。宽窄只调整两区自身的对齐。
        heroLeft.setAlignment(stack ? Pos.TOP_LEFT : Pos.CENTER_LEFT);
        heroRight.setAlignment(stack ? Pos.TOP_LEFT : Pos.CENTER_RIGHT);
    }

    /** 让内容在超宽窗口里不无限铺开，同时保持水平居中。 */
    private static final class StackPage extends StackPane {
        StackPage(VBox page) {
            getChildren().add(page);
            setAlignment(Pos.TOP_CENTER);
            setPadding(new Insets(0));
        }
    }
}
