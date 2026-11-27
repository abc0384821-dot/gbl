package com.mclauncher.ui;

import com.mclauncher.SkinRender;
import com.mclauncher.multiplayer.IdentityBridge;
import com.mclauncher.multiplayer.InviteCode;
import com.mclauncher.multiplayer.LANBridge;
import com.mclauncher.multiplayer.MultiplayerConfig;
import com.mclauncher.multiplayer.RoomService;
import com.mclauncher.multiplayer.RoomState;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 1.2.6 多人联机页：弹窗高度由内容撑开（浅/深双主题都紧凑），忙态改为横向一行紧凑卡；
 * 弹层跨主菜单隐藏/恢复改走 {@link OverlayRegistry} 全局登记表（以「是否被用户真关闭」为唯一判据，
 * 彻底废弃关闭世代计数）；只有本页面内部开/关弹层才取消涟漪。
 * 保留 1.2.4 成功态 HMCL 玩家列表、关房真生效、扫描 20s + 5 分钟缓存与 1.2.3 就绪条。
 *
 * <p>大厅卡自上而下：① 常驻就绪条（文案 Label + 8px 引擎条；引擎真运行才显示
 * 「联机核心已就绪」，否则 indeterminate + 「联机核心初始化中…」）；② 两个 HMCL 列表行式
 * 入口（左图标 + 主标题 + 灰副标题 + 右箭头，整行可点，<b>任何状态下都可见</b>——忙/完成态
 * 只禁用不隐藏，修掉二次启动时入口消失的问题）。
 * 入口下方是互斥状态区：忙段（单行状态 + 取消）/ 完成段（状态头 + 邀请码行 + 操作按钮行 +
 * 局域网引导）。玩家身份行整行删除；不再附加任何授权说明段落。
 *
 * <p>房主走进程检测 {@link #confirmGameThen}；客人点入口<b>直接</b>弹居中邀请码模态
 * {@link #showInviteModal()}，不检查游戏、不弹「忘记启动游戏」大模态。两个模态都钉死紧凑尺寸、
 * 在整页 overlay 里居中。引擎二进制已内置（见 {@code TraversalProvider}），本层不再提示「缺少组件」。
 *
 * <p>FX 线程铁律：worker 只做数据/IO；改 JavaFX 树（模态 setAll/clear、toast add/remove、状态段 show/hide）
 * 一律经 {@link #runFx}。新样式限定 {@code .gbl-compact} 后代，不污染其它页。
 */
public final class MultiplayerPage {

    /** 邀请码字母表：Crockford Base32 去掉易混淆的 I/L/O/U（与 InviteCode 一致）。 */
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** 1.2 大宽行右箭头：24x24 坐标内的右向箭头，随 Icons.create 缩放居中。 */
    private static final String ARROW = "M12 4l-1.4 1.4L16.2 11H4v2h12.2l-5.6 5.6L12 20l8-8z";

    /** 单槽合并：最新快照胜出，一轮最多排一个 FX Runnable。 */
    private final AtomicReference<RoomState.StateSnapshot> pendingState = new AtomicReference<>();
    private final AtomicBoolean stateFlushScheduled = new AtomicBoolean(false);
    private String lastUiSignature;
    private volatile Runnable pendingModalAction;

    // 1.2.5：overlay 直接作为页面节点（StackPane，无 ScrollPane viewport），
    // 模态/忙态层挂它即等于 BorderPane center 的真实边界，不再被 viewport fit/padding 内缩。
    private final StackPane overlay = new StackPane();
    private final ScrollPane contentScroll = new ScrollPane();

    // 卡顶常驻就绪条：文案 + 引擎条；忙段只留单行状态 + 取消
    private final Label engineStatusLabel = new Label();
    private final ProgressBar engineBar = new ProgressBar();
    private final Label busyStatus = new Label();
    private final Button cancelBtn = new Button("取消");

    // 完成段：状态头 / 邀请码行 / 操作按钮行 / 局域网引导
    private final Label doneStatus = new Label();
    private final Label statusChip = new Label();
    private final HBox statusHead = new HBox(10);
    private final TextField inviteField = new TextField();
    private final HBox inviteRow = new HBox(8);
    private final VBox lanBar = new VBox(4);
    private final Label lanHintLabel = new Label();
    private final HBox actionRow = new HBox(10);

    // 两个常驻大宽行的透明整行点击区 + 其余动作按钮（复制/关闭/退出/返回待机/重试在完成段；取消在忙段）
    private final Button createBtn = new Button();
    private final Button joinRoomBtn = new Button();
    private final Button copyBtn = new Button("复制邀请码");
    private final Button closeBtn = new Button("关闭房间");
    private final Button exitBtn = new Button("退出房间");
    private final Button resetBtn = new Button("返回待机");
    private final Button retryBtn = new Button("重试");

    // 1.2.4 玩家列表（HMCL 风格：标题 + 人数 + 行 + 空态）
    private final VBox playerListBox = new VBox(6);
    private final Label playerListTitle = new Label("玩家列表");
    private final VBox playerListRows = new VBox(4);
    private final Label playerListEmpty = new Label("等待其他玩家加入…");

    // 1.2.4 忙态遮罩：只盖操作区，取消是遮罩层内唯一出口
    private final StackPane busyOverlay = new StackPane();
    private final Label busyOverlayStatus = new Label();
    private final Label busyOverlayElapsed = new Label();
    private final Button overlayCancelBtn = new Button("取消");
    private Timeline busyClock;
    private long busyStartedNanos;
    private boolean busySlowHint;

    // 1.2.5：toast 统一委托 ShellView 的右下角气泡；本地只保留动画句柄供 onHidden 停
    private Consumer<String> toastSink;
    private final List<Animation> toastAnimations = new ArrayList<>();

    // 1.2.5 B：只有本页面内部弹层开/关才取消涟漪；父界面切页绝不调用。
    private Runnable overlayRippleCancel;
    private int rippleCancelCount;

    // 1.2.6：弹层全局登记表。以「是否被用户真关闭」为唯一判据，跨主菜单隐藏/恢复。
    private OverlayRegistry overlayRegistry;
    private int overlayPageOrdinal = -1;
    private boolean overlayRefreshSuspended;
    private boolean modalLogicalOpen;
    private boolean busyLogicalOpen;
    private String savedInviteText = "";
    private String savedInviteErrorText = "";
    private String savedBusyStatusText = "";
    private String savedBusyElapsedText = "";
    private long savedBusyStartedNanos;
    private boolean savedBusySlowHint;
    private TextField activeInviteField;
    private Label activeInviteError;
    /** 当前模态的关闭动作（QA 钩子用于模拟用户点「取消/确定」真关闭）。 */
    private Runnable activeModalDismiss;

    /** 游戏检查/邀请码模态的控制器：只认逻辑开关，不信被登记表改过的 visible。 */
    private final OverlayRegistry.Controller modalOverlayController = new OverlayRegistry.Controller() {
        @Override
        public void saveState() {
            savedInviteText = activeInviteField == null ? "" : activeInviteField.getText();
            savedInviteErrorText = activeInviteError == null ? "" : activeInviteError.getText();
        }

        @Override
        public void restoreState() {
            if (activeInviteField != null) {
                activeInviteField.setText(savedInviteText);
            }
            if (activeInviteError != null) {
                activeInviteError.setText(savedInviteErrorText);
                setShown(activeInviteError, !savedInviteErrorText.isEmpty());
            }
        }

        @Override
        public boolean isOpen() {
            return modalLogicalOpen;
        }
    };

    /** 忙态遮罩的控制器：保存计时起点与文案，切回继续按真实秒数刷新。 */
    private final OverlayRegistry.Controller busyOverlayController = new OverlayRegistry.Controller() {
        @Override
        public void saveState() {
            savedBusyStatusText = busyOverlayStatus.getText();
            savedBusyElapsedText = busyOverlayElapsed.getText();
            savedBusyStartedNanos = busyStartedNanos;
            savedBusySlowHint = busySlowHint;
            stopBusyClock();
        }

        @Override
        public void restoreState() {
            setTextIfChanged(busyOverlayStatus, savedBusyStatusText);
            setTextIfChanged(busyOverlayElapsed, savedBusyElapsedText);
            busyStartedNanos = savedBusyStartedNanos;
            busySlowHint = savedBusySlowHint;
            if (busyClock != null) {
                busyClock.playFromStart();
            }
        }

        @Override
        public boolean isOpen() {
            return busyLogicalOpen;
        }
    };

    // 状态区容器（buildHallCard 里赋值）：两大宽行常驻，只有状态段随状态互斥切换
    private VBox engineStrip;
    private VBox busyBox;
    private VBox doneBox;
    private Region statusDivider;

    // 忘记启动游戏 / 邀请码 模态层（页面内叠层，不另起 Stage）
    private final StackPane modalLayer = new StackPane();

    // 订阅
    private final RoomService.RoomStateListener roomListener = this::onRemoteState;
    private boolean subscribed;
    private boolean firstShown;

    /** 房主自己生成的房间码 / 上次操作，供邀请码回填与「重试」复用。 */
    private volatile String lastCreatedCode = "";
    private volatile String lastRoomForRetry = "";
    private volatile boolean lastWasCreate = true;

    public MultiplayerPage() {
        VBox page = new VBox();
        page.getStyleClass().addAll("gbl-compact", "mp-root");

        // 两个入口行用透明按钮铺满整行（mp-row-hit），其余六个动作按钮走幽灵样式。
        createBtn.getStyleClass().add("mp-row-hit");
        joinRoomBtn.getStyleClass().add("mp-row-hit");
        for (Button b : new Button[]{copyBtn, closeBtn, exitBtn, resetBtn, cancelBtn, retryBtn}) {
            b.getStyleClass().add("btn-ghost");
        }

        // 一次构建出铺满的主内容场景，所有延迟字段此时都已初始化。
        page.getChildren().add(buildScene());

        // gbl-compact 挂 overlay：模态层是 page 的兄弟节点，祖先作用域才能命中 .gbl-compact .mp-modal-*。
        overlay.getStyleClass().add("gbl-compact");
        // 1.2.5 D Round 2：专用类让宿主 padding 在基础 .gbl-compact 之后归零，遮罩四边才等于 center。
        overlay.getStyleClass().add("mp-overlay-host");
        overlay.setAlignment(Pos.TOP_CENTER);
        overlay.setPadding(new Insets(0));
        overlay.getChildren().add(page);
        // 模态层最后加入：StackPane 后加的在上层，盖住整页（含侧栏）。
        overlay.getChildren().add(modalLayer);
        // 忙态遮罩最后加入：盖住整页操作区（不盖窗口顶栏与左侧导航），仅「取消」可点。
        buildBusyOverlay();
        overlay.getChildren().add(busyOverlay);
        setShown(busyOverlay, false);
        // 1.2.5：不再用外层 ScrollPane 包 overlay——viewport 的 fit 会把遮罩内缩 10px。
        // 内容滚动仍由 contentScroll 承担；遮罩层与 center 边界严丝合缝。

        setShown(lanBar, false);
        setShown(inviteRow, false);
        applyButtons(RoomState.State.WAITING);
        applyState(RoomState.UNKNOWN_SNAPSHOT);
        applyButtons(stateOf(RoomService.currentState()));
    }

    /** QA/接线：把短提示交给窗口右下角的全局气泡，页内不再自建 toast。 */
    public void setToastSink(Consumer<String> sink) {
        this.toastSink = sink;
    }

    /** QA 钩子：当前仍在播放的本地 toast 动画数（离开页面必须归零）。 */
    public int activeToastAnimationCount() {
        return toastAnimations.size();
    }

    /** QA 钩子：忙态遮罩是否可见。 */
    public boolean isBusyOverlayShown() {
        return busyOverlay.isVisible();
    }

    /** 1.2.5 B：接线 ShellView 的取消涟漪 hook（只服务本页内部弹层）。 */
    public void setOverlayRippleCancel(Runnable hook) {
        this.overlayRippleCancel = hook;
    }

    /** 1.2.5 B：本页内部开/关弹层时调用一次，立刻掐掉未播完的涟漪。 */
    private void cancelOverlayRipples() {
        rippleCancelCount++;
        Runnable hook = overlayRippleCancel;
        if (hook != null) {
            hook.run();
        }
    }

    /** QA 钩子：本页内部取消涟漪次数（父页切换不得增长）。 */
    public int rippleCancelCountForTests() {
        return rippleCancelCount;
    }

    /** QA 钩子：模态/忙态逻辑开关 + 可见性 + 邀请码文本，供跨页四组合断言。 */
    public String overlayStateForTests() {
        return "modal=" + modalLogicalOpen
                + "|mvis=" + modalLayer.isVisible()
                + "|code=" + (activeInviteField == null ? "" : activeInviteField.getText())
                + "|err=" + (activeInviteError == null ? "" : activeInviteError.getText())
                + "|busy=" + busyLogicalOpen
                + "|bvis=" + busyOverlay.isVisible();
    }

    /** QA 钩子：本页在全局登记表里的层数（未接线时为 0）。 */
    public int registeredOverlayCountForTests() {
        return overlayRegistry == null ? 0 : overlayRegistry.size();
    }

    /** QA 钩子：忙态卡片实际布局宽高 [w, h]（必须在 FX 线程调用）。 */
    public double[] busyCardBoundsForTests() {
        return cardBounds(busyOverlay);
    }

    /**
     * QA 钩子：忙态两段文案的「无约束」pref 宽度 [状态文字, 秒数文字]。
     *
     * <p>慢文案（节点较多…）最长，用来核对卡片内的可用宽度是否够用、会不会被省略号截掉秒数；
     * prefWidth(-1) 取的是文字本身宽度，不受 setMaxWidth 影响。
     */
    public double[] busyTextPrefWidthsForTests() {
        return new double[]{busyOverlayStatus.prefWidth(-1), busyOverlayElapsed.prefWidth(-1)};
    }

    /** QA 钩子：忙态两段文案的「实际分配」宽度 [状态文字, 秒数文字]，用来判定有没有被截断。 */
    public double[] busyTextLayoutWidthsForTests() {
        return new double[]{busyOverlayStatus.getWidth(), busyOverlayElapsed.getWidth()};
    }

    /** QA 钩子：模态卡片实际布局宽高 [w, h]（必须在 FX 线程调用）。 */
    public double[] modalCardBoundsForTests() {
        return cardBounds(modalLayer);
    }

    private static double[] cardBounds(StackPane layer) {
        for (Node n : layer.getChildren()) {
            if (n instanceof Region r && r.getStyleClass().contains("card")) {
                return new double[]{r.getLayoutBounds().getWidth(), r.getLayoutBounds().getHeight()};
            }
        }
        return new double[]{0, 0};
    }

    /** QA 钩子：直接打开游戏检查模态（不下载、不检查进程）。 */
    public void openGameModalForTests() {
        runFx(() -> showGameModal(() -> {
        }));
    }

    /** QA 钩子：直接打开邀请码模态。 */
    public void openInviteModalForTests() {
        runFx(this::showInviteModal);
    }

    /** QA 钩子：直接打开忙态遮罩。 */
    public void openBusyForTests() {
        runFx(() -> showBusyOverlay("正在扫描节点…"));
    }

    /** QA 钩子：模拟用户点取消/确定真关闭当前模态（触发注销）。 */
    public void closeActiveModalForTests() {
        runFx(() -> {
            Runnable dismiss = activeModalDismiss;
            if (dismiss != null) {
                dismiss.run();
            }
        });
    }

    /** QA 钩子：向邀请码输入框写文字，供跨页恢复断言。 */
    public void setInviteTextForTests(String text) {
        runFx(() -> {
            if (activeInviteField != null) {
                activeInviteField.setText(text == null ? "" : text);
            }
        });
    }

    /** QA 钩子：读取邀请码输入框当前文字。 */
    public String inviteTextForTests() {
        return activeInviteField == null ? "" : activeInviteField.getText();
    }

    /**
     * 1.2.6：接入全局弹层登记表。ShellView 建好本页后调用一次；
     * 若此刻已有弹层打开（极少见的重挂树场景）就补登记。
     */
    public void bindOverlayRegistry(OverlayRegistry registry, int pageOrdinal) {
        this.overlayRegistry = registry;
        this.overlayPageOrdinal = pageOrdinal;
        if (modalLogicalOpen) {
            registerModalOverlay();
        }
        if (busyLogicalOpen) {
            registerBusyOverlay();
        }
    }

    private void registerModalOverlay() {
        if (overlayRegistry != null && overlayPageOrdinal >= 0) {
            overlayRegistry.register(overlayPageOrdinal, "multiplayer-modal", modalLayer, modalOverlayController);
        }
    }

    private void unregisterModalOverlay() {
        if (overlayRegistry != null) {
            overlayRegistry.unregister("multiplayer-modal");
        }
    }

    private void registerBusyOverlay() {
        if (overlayRegistry != null && overlayPageOrdinal >= 0) {
            overlayRegistry.register(overlayPageOrdinal, "multiplayer-busy", busyOverlay, busyOverlayController);
        }
    }

    private void unregisterBusyOverlay() {
        if (overlayRegistry != null) {
            overlayRegistry.unregister("multiplayer-busy");
        }
    }

    /**
     * 1.2.6：切页 + onShown 刷新期间的弹层冻结开关。
     * 冻结时状态刷新不得打开/关闭忙态遮罩，保证「切回原样恢复」不被 onShown 内部刷新破坏。
     */
    public void setOverlayRefreshSuspended(boolean suspended) {
        this.overlayRefreshSuspended = suspended;
        if (overlayRegistry != null) {
            overlayRegistry.setFrozen(suspended);
        }
    }

    /** QA 钩子：对注入的快照立即刷新 UI（等价引擎推送）。 */
    public void applySnapshotForTests(RoomState.StateSnapshot snapshot) {
        runFx(() -> applyState(snapshot));
    }

    /** QA 钩子：玩家列表标题与行数，供真机断言。 */
    public String playerListSummary() {
        return playerListTitle.getText() + "|" + playerListRows.getChildren().size()
                + "|" + playerListRows.getChildren().stream()
                        .map(n -> n instanceof Label l ? l.getText() : n.toString())
                        .reduce("", (a, b) -> a + ";" + b);
    }

    /** 供 ShellView 薄接线用：拿到本页面节点（1.2.5：StackPane，遮罩与 center 同边界）。 */
    public StackPane getView() {
        return overlay;
    }

    /**
     * 切到本页时调用。首次调用才在后台拉起联机引擎（构造时绝不下发/下载），
     * 并订阅状态变化、刷新一次状态卡；重复调用只补订阅。
     */
    public void onShown() {
        if (!firstShown) {
            firstShown = true;
            Thread t = new Thread(this::ensureEngineStarted, "gbl-mp-ui-first");
            t.setDaemon(true);
            t.start();
        }
        if (!subscribed) {
            RoomService.get().subscribe(roomListener);
            subscribed = true;
        }
        // 首次就绪后预热本地扫描缓存（只读磁盘，不发引擎请求、不产生副作用）。
        RoomService.get().warmScanCacheAsync();
        onRemoteState(RoomService.currentState());
        applyButtons(stateOf(RoomService.currentState()));
    }

    /** 离开本页时调用：退订、停忙态时钟与 toast 动画，避免动画跟到新页面。 */
    public void onHidden() {
        if (subscribed) {
            RoomService.get().unsubscribe(roomListener);
            subscribed = false;
        }
        stopBusyClock();
        for (Animation a : new ArrayList<>(toastAnimations)) {
            a.stop();
        }
        toastAnimations.clear();
        RoomSkinCache.clear();   // 1.3.1：离开本页清皮肤旁路缓存，换房/退出后头不串人
    }

    // ------------------------------------------------------------------ 构建：场景

    /** 内容 VBox + 单个可滚 ScrollPane 铺满整页。GBL 主窗口已有左侧主导航与左下角全局账号卡。 */
    private StackPane buildScene() {
        VBox content = buildContent();

        contentScroll.setContent(content);
        contentScroll.setFitToWidth(true);
        contentScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        contentScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        contentScroll.getStyleClass().addAll("content-scroll", "mp-content-scroll");

        StackPane scene = new StackPane(contentScroll);
        scene.getStyleClass().add("mp-scene");

        // 模态层挂在最外层 overlay：stack 顺序保证模态盖住整页。
        modalLayer.getStyleClass().add("mp-modal-layer");
        setShown(modalLayer, false);
        return scene;
    }

    // ------------------------------------------------------------------ 构建：主内容

    private VBox buildContent() {
        // HMCL 内容页统一骨架：VBox(10) + Insets(10)，与 GBL 其它 7 页同呼吸。
        VBox content = new VBox(10);
        content.getStyleClass().add("mp-content");
        content.setPadding(new Insets(10));

        Label title = new Label("联机大厅");
        title.getStyleClass().add("page-title");

        content.getChildren().addAll(title, buildHallCard());
        return content;
    }

    /**
     * 大厅主卡：卡顶常驻就绪条 + 两个常驻大宽行 + 下方互斥状态区（忙 / 完成）。
     * 动作按钮在此<b>统一绑一次</b> {@code setOnAction}，可见性/禁用交给 {@link #applyButtons}。
     */
    private VBox buildHallCard() {
        VBox card = new VBox(0);
        // GBL 原生卡片：浅色白底 / 深色深卡。
        card.getStyleClass().addAll("card", "mp-hall-card");

        // ---- 常驻入口：1.2 的两大宽行，整行可点，任何状态都可见 ----
        VBox rows = new VBox(0);
        rows.getStyleClass().add("mp-big-rows");
        rows.getChildren().addAll(
                bigRow(Icons.CUBE, "我想当房主", "创建房间并生成邀请码，与好友一起畅玩", createBtn),
                divider(),
                bigRow(Icons.ACCOUNT, "我想当客人", "输入房主提供的邀请码加入游戏世界", joinRoomBtn));

        // ---- 卡顶常驻就绪条：文案 + 8px 引擎条；任何状态都可见（状态由 updateEngineBar 驱动） ----
        engineStatusLabel.getStyleClass().addAll("mp-engine-text", "mp-engine-loading");
        engineStatusLabel.setText("联机核心初始化中…");
        engineBar.setPrefWidth(Double.MAX_VALUE);
        engineBar.setMinHeight(8);
        engineBar.setPrefHeight(8);
        engineBar.setProgress(-1.0);   // indeterminate，引擎真正就绪再置 1
        engineBar.getStyleClass().add("mp-engine-bar");
        engineStrip = new VBox(6);
        engineStrip.getStyleClass().add("mp-engine-strip");
        engineStrip.getChildren().addAll(engineStatusLabel, engineBar);

        // 1.3.1：联机核心说明——三句话讲清 P2P / 中继 / 游戏内开局域网，
        // 放在就绪条与两个大宽行之间。门禁锁定的 6 条字面量一条不动，只新增这一块。
        Label coreNote = new Label(
                "房间走 P2P 直连，用邀请码把大家带到同一网络，不经游戏中心服务器；"
                        + "网络不通时自动走中继，仍可联机，延迟可能更高；"
                        + "进房间后还要在游戏里开「对局域网开放」，房间才会出现在多人列表。");
        coreNote.getStyleClass().add("mp-core-note");
        coreNote.setWrapText(true);
        coreNote.setMaxWidth(Double.MAX_VALUE);

        // ---- 忙段：单行状态 + 取消（引擎条已提到卡顶常驻，忙段不再重复） ----
        busyStatus.getStyleClass().add("setting-desc");
        HBox busyLine = new HBox(10);
        busyLine.getStyleClass().add("mp-busy-line");
        busyLine.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(busyStatus, Priority.ALWAYS);
        busyLine.getChildren().addAll(busyStatus, cancelBtn);
        busyBox = new VBox(6);
        busyBox.getStyleClass().add("mp-busy-box");
        busyBox.getChildren().add(busyLine);

        // ---- 完成段：状态头 + 邀请码行 + 操作按钮行 + 局域网引导 ----
        doneStatus.getStyleClass().add("gbl-row-title");
        doneStatus.setWrapText(true);
        statusChip.getStyleClass().add("chip");
        statusHead.setAlignment(Pos.CENTER_LEFT);
        statusHead.getChildren().addAll(doneStatus, statusChip);

        inviteField.setEditable(false);
        inviteField.getStyleClass().addAll("gbl-invite-code", "mp-invite");
        inviteField.setAlignment(Pos.CENTER);
        inviteField.setMaxWidth(Double.MAX_VALUE);
        inviteField.setOnMouseClicked(e -> doCopy());
        Label inviteDesc = new Label("点击邀请码即可复制，对方用它加入同一房间");
        inviteDesc.getStyleClass().add("setting-desc");
        VBox inviteBox = new VBox(4);
        inviteBox.setAlignment(Pos.CENTER);
        HBox.setHgrow(inviteBox, Priority.ALWAYS);
        inviteBox.getChildren().addAll(inviteDesc, inviteField);
        inviteRow.setAlignment(Pos.CENTER);
        inviteRow.getChildren().add(inviteBox);

        actionRow.setAlignment(Pos.CENTER_LEFT);
        actionRow.setSpacing(8);
        actionRow.getChildren().addAll(copyBtn, closeBtn, exitBtn, retryBtn, resetBtn);

        buildLanBar();

        buildPlayerListPanel();
        doneBox = new VBox(8);
        doneBox.getStyleClass().add("mp-done-box");
        doneBox.getChildren().addAll(statusHead, inviteRow, playerListBox, actionRow, lanBar);

        // ---- 八个动作按钮一次性全绑（根因修复：可见性由 applyButtons 控，绑定只绑一次） ----
        createBtn.setOnAction(e -> startHostFlow());
        joinRoomBtn.setOnAction(e -> startGuestFlow());   // 1.2.2：直接弹邀请码模态，不检查游戏
        copyBtn.setOnAction(e -> doCopy());
        closeBtn.setOnAction(e -> stopRoom(true));
        exitBtn.setOnAction(e -> stopRoom(false));
        resetBtn.setOnAction(e -> doReset());
        cancelBtn.setOnAction(e -> doCancel());
        retryBtn.setOnAction(e -> doRetry());

        // 状态区与入口之间只在有状态内容时显示 1px 分隔线，不留空占位。
        statusDivider = divider();
        card.getChildren().addAll(engineStrip, rows, statusDivider, busyBox, doneBox);
        card.getChildren().add(1, coreNote);
        return card;
    }

    /** 1.2.4 玩家列表面板：HMCL 风格标题 + 人数 + 圆点头像行 + 空态。纯构建，状态刷新在 FX 线程。 */
    private void buildPlayerListPanel() {
        playerListTitle.getStyleClass().add("mp-player-list-title");
        playerListRows.getStyleClass().add("mp-player-list-rows");
        playerListEmpty.getStyleClass().add("mp-player-list-empty");
        playerListBox.getStyleClass().add("mp-player-list");
        playerListBox.getChildren().addAll(playerListTitle, playerListRows, playerListEmpty);
        setShown(playerListEmpty, false);
    }

    /** 1.2.6 忙态遮罩：半透明黑层 + 居中横向紧凑卡，取消按钮是遮罩内唯一出口。 */
    private void buildBusyOverlay() {
        busyOverlay.getStyleClass().add("mp-busy-overlay");
        StackPane card = new StackPane();
        card.getStyleClass().addAll("card", "mp-busy-card");
        // 1.2.6：高度由内容撑开，绝不让 StackPane 父容器把卡片竖向拉满（竖长条根因）。
        card.setMinHeight(Region.USE_PREF_SIZE);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setMinWidth(Region.USE_PREF_SIZE);
        card.setPrefWidth(420);
        card.setMaxWidth(420);
        // HMCL 结构：一行文字 + 一个次要按钮，垂直居中。
        HBox body = new HBox(10);
        body.setAlignment(Pos.CENTER_LEFT);
        busyOverlayStatus.getStyleClass().add("mp-busy-overlay-title");
        busyOverlayElapsed.getStyleClass().add("mp-busy-overlay-elapsed");
        // 1.2.6：一行装不下时，优先保住「已等待 N 秒」——秒数是用户判断卡死与否的唯一依据。
        // 状态文字允许压缩（minWidth 0 + 省略号），秒数文字按 pref 固定，绝不截断。
        // 实测：慢文案「节点较多，仍在扫描…（已等待 12 秒）」= 193.4px，
        // 卡片内宽 420-32=388，减 10+10 间距与 64 取消键后仍有 110px 给状态文字。
        busyOverlayStatus.setMinWidth(0);
        busyOverlayStatus.setMaxWidth(Double.MAX_VALUE);
        busyOverlayElapsed.setMinWidth(Region.USE_PREF_SIZE);
        busyOverlayElapsed.setMaxWidth(Region.USE_PREF_SIZE);
        overlayCancelBtn.getStyleClass().addAll("btn-ghost", "mp-busy-overlay-cancel");
        overlayCancelBtn.setOnAction(e -> doCancel());
        HBox.setHgrow(busyOverlayStatus, Priority.ALWAYS);
        body.getChildren().addAll(busyOverlayStatus, busyOverlayElapsed, overlayCancelBtn);
        card.getChildren().add(body);
        busyOverlay.getChildren().add(card);
        busyOverlay.setPickOnBounds(true);   // 必须拦截操作区鼠标，顶栏/侧栏不在这一层
    }

    private void showBusyOverlay(String text) {
        if (overlayRefreshSuspended) {
            return;   // 1.2.6：切页/onShown 刷新期间冻结弹层可见性
        }
        cancelOverlayRipples();   // 1.2.5 B：联机页内部打开忙态弹层立即清涟漪
        if (busyOverlay.isVisible()) {
            setTextIfChanged(busyOverlayStatus, text);
            return;
        }
        busyStartedNanos = System.nanoTime();
        busySlowHint = false;
        setTextIfChanged(busyOverlayStatus, text);
        setTextIfChanged(busyOverlayElapsed, "已等待 0 秒");
        busyLogicalOpen = true;      // 1.2.6：逻辑打开，登记表据此跨页恢复
        registerBusyOverlay();
        setShown(busyOverlay, true);
        if (busyClock == null) {
            busyClock = new Timeline(new KeyFrame(Duration.seconds(1), e -> refreshBusyElapsed()));
            busyClock.setCycleCount(Animation.INDEFINITE);
        }
        busyClock.playFromStart();
    }

    private void hideBusyOverlay() {
        if (overlayRefreshSuspended) {
            return;   // 1.2.6：冻结期间状态刷新不得关闭刚从跨页恢复的忙态
        }
        if (!busyLogicalOpen && !busyOverlay.isVisible()) {
            return;
        }
        cancelOverlayRipples();   // 1.2.5 B：联机页内部关闭忙态弹层立即清涟漪
        busyLogicalOpen = false;  // 1.2.6：真关闭 → 注销，切回不复活
        unregisterBusyOverlay();
        stopBusyClock();
        setShown(busyOverlay, false);
    }

    private void stopBusyClock() {
        if (busyClock != null) {
            busyClock.stop();
        }
    }

    private void refreshBusyElapsed() {
        if (!busyOverlay.isVisible()) {
            stopBusyClock();
            return;
        }
        long seconds = (System.nanoTime() - busyStartedNanos) / 1_000_000_000L;
        boolean slow = seconds >= 12L;
        String message = slow ? "节点较多，仍在扫描…（已等待 " + seconds + " 秒）"
                : "已等待 " + seconds + " 秒";
        setTextIfChanged(busyOverlayElapsed, message);
        if (slow) {
            busySlowHint = true;
        }
    }

    private static Region divider() {
        Region r = new Region();
        r.getStyleClass().add("mp-divider");
        r.setMinHeight(1);
        r.setPrefHeight(1);
        return r;
    }

    /** 1.2 原版大宽行：左线性图标 + 主标题 + 灰副标题 + 右箭头；透明按钮铺满整行，整行可点。 */
    private static HBox bigRow(String iconPath, String title, String sub, Button hit) {
        SVGPath icon = Icons.create(iconPath, 27);
        icon.getStyleClass().add("mp-big-icon");
        VBox text = new VBox(3);
        Label t = new Label(title);
        t.getStyleClass().add("mp-big-title");
        Label s = new Label(sub);
        s.getStyleClass().add("mp-big-sub");
        s.setWrapText(true);
        text.getChildren().addAll(t, s);
        HBox.setHgrow(text, Priority.ALWAYS);
        SVGPath arrow = Icons.create(ARROW, 15);
        arrow.getStyleClass().add("mp-big-arrow");

        HBox row = new HBox(14);
        row.getStyleClass().add("mp-big-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(icon, text, arrow);

        hit.setMaxWidth(Double.MAX_VALUE);
        hit.setMaxHeight(Double.MAX_VALUE);
        StackPane holder = new StackPane(row, hit);
        holder.getStyleClass().add("mp-big-holder");
        HBox wrapper = new HBox(holder);
        HBox.setHgrow(holder, Priority.ALWAYS);
        return wrapper;
    }

    /** 局域网引导：中性提示，不断言用户没开，也不再作为红色异常出现。 */
    private void buildLanBar() {
        lanHintLabel.getStyleClass().addAll("setting-desc", "mp-lan-hint");
        lanHintLabel.setWrapText(true);
        lanBar.getChildren().add(lanHintLabel);
        lanBar.setPadding(new Insets(2, 0, 0, 0));
    }

    // ------------------------------------------------------------------ 房主/客人流程 + 模态

    /** 房主：先按真实进程判断游戏是否启动，未启动给同款模态确认，再建房间。 */
    private void startHostFlow() {
        confirmGameThen(() -> createRoom(generateInviteCode()));
    }

    /** 客人：直接弹居中邀请码模态——不检查游戏、不弹「忘记启动游戏」大模态。 */
    private void startGuestFlow() {
        showInviteModal();
    }

    /**
     * 1.2.1：客人邀请码居中模态。必填 / 格式<b>红字</b>停留，[取消] 真关模态
     * （{@code modalLayer} 隐藏且 children 清空 + {@code pendingModalAction} 归空）。
     * 仿 {@link #showGameModal} 写法：复用 modalLayer + {@code .card.mp-modal-card} + StackPane 居中。
     */
    private void showInviteModal() {
        Label icon = new Label("?");
        icon.getStyleClass().add("mp-modal-icon");
        VBox text = new VBox(4);
        Label title = new Label("请输入房主提供的邀请码");
        title.getStyleClass().add("mp-modal-title");
        Label sub = new Label("输入邀请码（格式 U/XXXX-XXXX-XXXX-XXXX）");
        sub.getStyleClass().add("mp-modal-sub");
        // 1.2.6：副标题宁可换行也不许省略号截断（格式说明被截掉等于没说清）。
        // 给死 pref 宽 340 是为了让卡片宽高可预测，不随文字宽度飘。
        sub.setWrapText(true);
        sub.setPrefWidth(340);
        sub.setMinWidth(0);
        text.getChildren().addAll(title, sub);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox head = new HBox(14);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(icon, text);

        TextField modalCode = new TextField();
        activeInviteField = modalCode;   // 1.2.5 G：快照要恢复已输入文字
        modalCode.setPromptText("粘贴邀请码");
        modalCode.getStyleClass().addAll("gbl-input", "mp-invite-modal-input");
        modalCode.setMaxWidth(Double.MAX_VALUE);
        Label modalErr = new Label();
        activeInviteError = modalErr;
        modalErr.getStyleClass().add("chip-error");
        modalErr.setWrapText(true);
        setShown(modalErr, false);

        Button ok = new Button("确定");
        Button cancel = new Button("取消");
        ok.getStyleClass().addAll("btn-primary", "mp-modal-btn");
        cancel.getStyleClass().addAll("btn-ghost", "mp-modal-btn");
        HBox buttons = modalButtonBar(ok, cancel);

        VBox card = new VBox(12);   // 1.2.6：留白紧凑，高度完全由内容撑开
        card.getStyleClass().addAll("card", "mp-modal-card", "mp-invite-modal");
        card.setMinWidth(Region.USE_PREF_SIZE);
        card.setPrefWidth(440);
        card.setMaxWidth(440);
        card.setMinHeight(Region.USE_PREF_SIZE);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.getChildren().addAll(head, modalCode, modalErr, buttons);

        modalLayer.getChildren().setAll(card);
        StackPane.setAlignment(card, Pos.CENTER);
        cancelOverlayRipples();   // 1.2.5 B：打开邀请码弹层立即清涟漪
        modalLogicalOpen = true;  // 1.2.6：登记后跨页隐藏/恢复
        registerModalOverlay();
        setShown(modalLayer, true);
        modalCode.requestFocus();

        Runnable dismiss = () -> {
            cancelOverlayRipples();   // 1.2.5 B：关闭邀请码弹层立即清涟漪
            modalLogicalOpen = false; // 1.2.6：真关闭 → 注销，切回不复活
            unregisterModalOverlay();
            setShown(modalLayer, false);
            modalLayer.getChildren().clear();
            pendingModalAction = null;
            activeInviteField = null;
            activeInviteError = null;
            activeModalDismiss = null;
        };
        activeModalDismiss = dismiss;
        ok.setOnAction(e -> {
            String code = InviteCode.normalize(modalCode.getText());
            if (code == null || code.isBlank()) {
                modalErr.setText("必填项");
                setShown(modalErr, true);
                return;
            }
            if (!InviteCode.isValid(code)) {
                modalErr.setText("邀请码错误");
                setShown(modalErr, true);
                return;
            }
            dismiss.run();
            doJoinByCode(code);
        });
        cancel.setOnAction(e -> dismiss.run());
    }

    /**
     * HMCL 对话框按钮条：卡片底部右对齐，按钮统一 112x36 / 间距 12 / 字号 14。
     * 文案由调用方逐字传入，主按钮仍是「游戏已启动」/「确定」。
     */
    private static HBox modalButtonBar(Button... buttons) {
        HBox bar = new HBox(12, buttons);
        bar.getStyleClass().add("mp-modal-actions");
        bar.setAlignment(Pos.CENTER_RIGHT);
        return bar;
    }

    /** 进程级粗判：命令行含 minecraft / .minecraft 即认为游戏在跑；任何异常按「未检测到」。 */
    private void confirmGameThen(Runnable action) {
        Thread t = new Thread(() -> {
            boolean running = minecraftRunning();
            runFx(() -> {
                if (running || pendingModalAction != null) {
                    action.run();
                } else {
                    showGameModal(action);
                }
            });
        }, "gbl-mp-ui-gamecheck");
        t.setDaemon(true);
        t.start();
    }

    private static boolean minecraftRunning() {
        try {
            return ProcessHandle.allProcesses().anyMatch(p -> {
                try {
                    Optional<String> cmd = p.info().commandLine();
                    if (cmd.isEmpty()) {
                        return false;
                    }
                    String lower = cmd.get().toLowerCase(Locale.ROOT);
                    return lower.contains("minecraft") || lower.contains(".minecraft");
                } catch (Throwable ignored) {
                    return false;
                }
            });
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 页面内模态：您似乎忘记启动游戏了 / 未能找到正在运行的游戏 + 三按钮。 */
    private void showGameModal(Runnable action) {
        pendingModalAction = action;

        Label icon = new Label("?");
        icon.getStyleClass().add("mp-modal-icon");
        VBox text = new VBox(4);
        Label title = new Label("您似乎忘记启动游戏了");
        title.getStyleClass().add("mp-modal-title");
        Label sub = new Label("未能找到正在运行的游戏");
        sub.getStyleClass().add("mp-modal-sub");
        text.getChildren().addAll(title, sub);
        HBox.setHgrow(text, Priority.ALWAYS);

        Button launch = new Button("启动游戏");
        Button started = new Button("游戏已启动");
        Button cancel = new Button("取消");
        launch.getStyleClass().addAll("btn-ghost", "mp-modal-btn");
        started.getStyleClass().addAll("btn-primary", "mp-modal-btn");
        cancel.getStyleClass().addAll("btn-ghost", "mp-modal-btn");

        HBox buttons = modalButtonBar(launch, started, cancel);

        VBox card = new VBox(12);
        // 1.2.6：宽度收窄到 400（按钮 3×112 + 2×12 = 360 正好落在内容区），
        // 高度由内容撑开（标题区 36 + 间距 12 + 按钮 36 + padding 40 ≈ 124），绝不设固定高度。
        card.getStyleClass().addAll("card", "mp-modal-card", "mp-game-modal");
        card.setMinWidth(Region.USE_PREF_SIZE);
        card.setPrefWidth(400);
        card.setMaxWidth(400);
        card.setMinHeight(Region.USE_PREF_SIZE);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        HBox head = new HBox(14);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getChildren().addAll(icon, text);
        card.getChildren().addAll(head, buttons);

        modalLayer.getChildren().setAll(card);
        StackPane.setAlignment(card, Pos.CENTER);
        cancelOverlayRipples();   // 1.2.5 B：打开游戏检查弹层立即清涟漪
        modalLogicalOpen = true;  // 1.2.6：登记后跨页隐藏/恢复
        registerModalOverlay();
        setShown(modalLayer, true);

        Runnable dismiss = () -> {
            cancelOverlayRipples();   // 1.2.5 B：关闭游戏检查弹层立即清涟漪
            modalLogicalOpen = false; // 1.2.6：真关闭 → 注销
            unregisterModalOverlay();
            setShown(modalLayer, false);
            modalLayer.getChildren().clear();
            pendingModalAction = null;
            activeModalDismiss = null;
        };
        activeModalDismiss = dismiss;
        launch.setOnAction(e -> {
            dismiss.run();
            showToast("请到左侧「启动」页启动游戏，之后再来这里开房间");
        });
        started.setOnAction(e -> {
            Runnable next = pendingModalAction;
            dismiss.run();
            if (next != null) {
                next.run();
            }
        });
        cancel.setOnAction(e -> dismiss.run());
    }

    // ------------------------------------------------------------------ 状态刷新

    /** 轮询 / onShown 共用的合并入口：旧快照自然丢弃，一轮最多排一个 FX Runnable。 */
    private void onRemoteState(RoomState.StateSnapshot snapshot) {
        pendingState.set(snapshot);
        if (stateFlushScheduled.compareAndSet(false, true)) {
            runFx(this::flushPendingState);
        }
    }

    private void flushPendingState() {
        stateFlushScheduled.set(false);
        RoomState.StateSnapshot snap = pendingState.getAndSet(null);
        if (snap != null) {
            applyState(snap);
        }
    }

    private void applyState(RoomState.StateSnapshot snapshot) {
        RoomState.StateSnapshot snap = snapshot == null ? RoomState.UNKNOWN_SNAPSHOT : snapshot;
        RoomState.State s = stateOf(snap);
        String diff = str(snap.difficulty());
        String room = str(snap.room());
        boolean running = RoomService.get().isRunning();

        String sig = s.name() + "|" + room + "|" + diff + "|" + snap.exceptionType() + "|" + running
                + "|" + snap.players();
        if (sig.equals(lastUiSignature)) {
            return;
        }
        lastUiSignature = sig;

        int ex = snap.exceptionType();
        boolean notice = s == RoomState.State.EXCEPTION
                && (ex == RoomService.NETWORK_NOTICE_TYPE_RELAY
                    || ex == RoomService.NETWORK_NOTICE_TYPE_LAN);
        String text = switch (s) {
            case WAITING -> "待机 · 尚未创建或加入房间";
            case HOST_SCANNING -> "正在扫描节点…";
            case HOST_STARTING -> "正在创建房间…";
            case HOST_OK -> "房间已创建 · 邀请码";
            case GUEST_CONNECTING -> "正在连接房间…";
            case GUEST_STARTING -> "正在配置本机网络（" + diff + "）…";
            case GUEST_OK -> "已加入房间";
            case EXCEPTION -> {
                // CGNAT / 经中继 / 内网隔离是可正常联机的形态，显示中性「提示」，绝不给红色「异常」致命前缀。
                if (ex == RoomService.NETWORK_NOTICE_TYPE_RELAY) {
                    yield "网络提示 · 经中继转发，联机可用；直连可开 IPv6 或换网络";
                }
                if (ex == RoomService.NETWORK_NOTICE_TYPE_LAN) {
                    yield "内网提示 · 请在游戏内开启对局域网开放";
                }
                // 进程失联 / 引擎真失败仍如实提示，可重试
                yield "出错：" + exceptionText(ex);
            }
            case UNKNOWN -> "未知状态";
        };

        if (room.isEmpty()) {
            room = lastCreatedCode;
        }
        setTextIfChanged(inviteField, room);
        setShownIfChanged(inviteRow, s == RoomState.State.HOST_OK || s == RoomState.State.GUEST_OK);

        // 1.3.1：忙态状态只在遮罩 busyOverlayStatus 显示一次，删掉 busyBox 里的重复一行。
        // 完成段状态头
        setTextIfChanged(doneStatus, text);
        setTextIfChanged(statusChip, notice ? "提示" : chipText(s));
        setChipStyle(statusChip, notice ? "chip-accent"
                : s == RoomState.State.EXCEPTION ? "chip-error" : "chip-accent");

        applyButtons(s);
        // 忙态遮罩只盖操作区；取消是遮罩内唯一出口，顶栏与左侧导航照旧可点。
        if (isBusyState(s)) {
            showBusyOverlay(text);
        } else {
            hideBusyOverlay();
        }
        try {
            updatePlayerList(snap.players(), s);
        } catch (RuntimeException playerEx) {
            renderPlayerList(List.of(), s);   // 坏数据降级空表，绝不带崩 UI
        }
        updateEngineBar(running);

        // 局域网提示只在房间真正建好/加入后给一句中性引导，绝不反复弹，也不进 EXCEPTION 态。
        if (LANBridge.needsLanOpen(s)) {
            String hint = LANBridge.lanHint(s);
            setTextIfChanged(lanHintLabel, hint == null || hint.isBlank()
                    ? "若尚未开启：选项 → 游戏 → 对局域网开放；已开启请忽略" : hint);
            setShownIfChanged(lanBar, true);
        } else {
            setShownIfChanged(lanBar, false);
        }
    }

    /** 卡顶常驻就绪条：引擎真运行才「联机核心已就绪」，否则 indeterminate + 「联机核心初始化中…」。 */
    private void updateEngineBar(boolean running) {
        if (running) {
            engineBar.setProgress(1.0);
        } else {
            // JavaFX ProgressBar 以负 progress 表示 indeterminate，不调用不存在的 setIndeterminate
            engineBar.setProgress(-1.0);
        }
        engineBar.getStyleClass().removeAll("mp-engine-ready", "mp-engine-loading");
        engineBar.getStyleClass().add(running ? "mp-engine-ready" : "mp-engine-loading");
        setTextIfChanged(engineStatusLabel, running ? "联机核心已就绪" : "联机核心初始化中…");
        engineStatusLabel.getStyleClass().removeAll("mp-engine-ready", "mp-engine-loading");
        engineStatusLabel.getStyleClass().add(running ? "mp-engine-ready" : "mp-engine-loading");
    }

    private static boolean isBusyState(RoomState.State s) {
        return s == RoomState.State.HOST_SCANNING
                || s == RoomState.State.HOST_STARTING
                || s == RoomState.State.GUEST_CONNECTING
                || s == RoomState.State.GUEST_STARTING;
    }

    /** 玩家列表刷新入口：所有 JavaFX ObservableList 变更都在 FX 线程。 */
    private void updatePlayerList(List<String> rawPlayers, RoomState.State state) {
        runFx(() -> {
            try {
                List<String> players = new ArrayList<>();
                if (rawPlayers != null) {
                    for (String p : rawPlayers) {
                        if (p != null && !p.isBlank()) {
                            players.add(p.trim());
                        }
                    }
                }
                String me = playerName();
                if (!me.isEmpty() && players.stream().noneMatch(p -> p.equalsIgnoreCase(me))) {
                    players.add(me);
                }
                renderPlayerList(players, state);
            } catch (RuntimeException ex) {
                runFx(() -> renderPlayerList(List.of(), state));
            }
        });
    }

    /** 真正改 ObservableList：必须在 FX 线程调用。 */
    private void renderPlayerList(List<String> players, RoomState.State state) {
        List<String> safe = players == null ? List.of() : players;
        setTextIfChanged(playerListTitle, "玩家列表（" + safe.size() + " 人）");
        playerListRows.getChildren().clear();
        if (safe.isEmpty()) {
            setShown(playerListEmpty, true);
            return;
        }
        setShown(playerListEmpty, false);
        String me = playerName();
        for (int i = 0; i < safe.size(); i++) {
            String name = safe.get(i);
            // 1.3.1：绿点占位（.mp-player-dot 门禁锁定，绝不删）+ 皮肤头像覆盖。
            // 头像：本机同名账号 → CSL → 名字稳定默认头；IO 在 worker 线程，回调已切回 FX 线程。
            Label dot = new Label();
            dot.getStyleClass().add("mp-player-dot");
            StackPane avatar = new StackPane(dot);
            avatar.setAlignment(Pos.CENTER);
            ImageView head = new ImageView();
            head.getStyleClass().add("mp-player-head");
            head.setFitWidth(18);
            head.setFitHeight(18);
            head.setPreserveRatio(true);
            head.setSmooth(false);
            head.setMouseTransparent(true);
            head.setVisible(false);
            avatar.getChildren().add(head);
            byte[] cached = RoomSkinCache.get(name);
            if (cached != null) {
                head.setImage(SkinRender.head(cached));
                head.setVisible(true);
            } else {
                RoomSkinCache.loadAsync(name, bytes -> {
                    if (bytes != null && head.getScene() != null) {
                        head.setImage(SkinRender.head(bytes));
                        head.setVisible(true);
                    }
                });
            }
            Label label = new Label(name);
            label.getStyleClass().add("mp-player-name");
            Label tag = new Label(playerTag(name, me, i, state));
            tag.getStyleClass().add("mp-player-tag");
            HBox row = new HBox(8, avatar, label);
            row.getStyleClass().add("mp-player-row");
            HBox.setHgrow(label, Priority.ALWAYS);
            row.getChildren().add(tag);
            playerListRows.getChildren().add(row);
        }
    }

    private static String playerTag(String name, String me, int index, RoomState.State state) {
        if (index == 0 && (state == RoomState.State.HOST_OK || state == RoomState.State.HOST_STARTING
                || state == RoomState.State.HOST_SCANNING)) {
            return "房主";
        }
        if (!me.isEmpty() && name.equalsIgnoreCase(me)) {
            return "自己";
        }
        return "已连接";
    }

    /** 按状态组切三段互斥子区，并控每个动作按钮的可见性（绑定只在 buildHallCard 做一次）。 */
    private void applyButtons(RoomState.State s) {
        boolean isBusy = isBusyState(s);
        boolean isDone = s == RoomState.State.HOST_OK
                || s == RoomState.State.GUEST_OK
                || s == RoomState.State.EXCEPTION;

        // 1.2.2 常驻入口：两个大宽行任何状态都可见（忙/完成态禁用防重复操作，绝不隐藏）。
        setShownIfChanged(createBtn, true);
        setShownIfChanged(joinRoomBtn, true);
        createBtn.setDisable(isBusy);
        joinRoomBtn.setDisable(isBusy);

        setShownIfChanged(busyBox, isBusy);
        setShownIfChanged(doneBox, isDone);
        setShownIfChanged(statusDivider, isBusy || isDone);

        setShownIfChanged(cancelBtn, isBusy);
        setShownIfChanged(copyBtn, s == RoomState.State.HOST_OK);
        setShownIfChanged(closeBtn, s == RoomState.State.HOST_OK);
        setShownIfChanged(exitBtn, s == RoomState.State.GUEST_OK);
        setShownIfChanged(retryBtn, s == RoomState.State.EXCEPTION);
        setShownIfChanged(resetBtn, s == RoomState.State.EXCEPTION);
        boolean any = copyBtn.isVisible() || closeBtn.isVisible() || exitBtn.isVisible()
                || retryBtn.isVisible() || resetBtn.isVisible();
        setShownIfChanged(actionRow, any);
    }

    /** 状态兜底解析：兼容 kebab-case / camelCase / 下划线各种引擎串。 */
    private static RoomState.State stateOf(RoomState.StateSnapshot snap) {
        RoomState.State s = snap.stateEnum();
        if (s != RoomState.State.UNKNOWN) {
            return s;
        }
        String raw = str(snap.state()).trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_').replace("_", "");
        for (RoomState.State v : RoomState.State.values()) {
            if (v.name().toLowerCase(Locale.ROOT).replace("_", "").equals(raw)) {
                return v;
            }
        }
        return s;
    }

    private static String chipText(RoomState.State s) {
        return switch (s) {
            case WAITING -> "待机";
            case HOST_SCANNING, HOST_STARTING -> "建房中";
            case HOST_OK -> "房主";
            case GUEST_CONNECTING, GUEST_STARTING -> "加入中";
            case GUEST_OK -> "访客";
            case EXCEPTION -> "异常";
            case UNKNOWN -> "未知";
        };
    }

    /** 异常类型中文描述（短文案）。case 3/4 给可操作建议，其余给根因名。 */
    private static String exceptionText(int type) {
        return switch (type) {
            case 0 -> "节点不可达：公共节点暂连不上，可重试或更换自定义节点";
            case 1 -> "端口冲突：联机端口被占用，请关闭占用程序后重试";
            case 2 -> "版本不匹配：引擎版本与客户端不一致，请重启后再试";
            case 3 -> "网络受限：当前经中继转发流量；若房间无法加入，请尝试开 IPv6 或切自定义节点";
            case 4 -> "内网隔离：建议先在游戏内选项→游戏→对局域网开放开启";
            case 5 -> "未知";
            case 6 -> "联机引擎失联：进程已退出或连续请求无响应，请点重试重新启动";
            default -> "未知错误（" + type + "）";
        };
    }

    // ------------------------------------------------------------------ 操作

    /** 房主创建：异步扫描节点；局域网引导改由房间状态驱动，不再无条件强弹。 */
    private void createRoom(String code) {
        if (!withEngine()) {
            return;
        }
        lastWasCreate = true;
        lastCreatedCode = code;
        lastRoomForRetry = code;
        RoomService.get().scanRoomAsync(code, playerName(), publicNodes(), (ok, error) -> runFx(() -> {
            if (error != null || !Boolean.TRUE.equals(ok)) {
                showToast("创建房间失败：" + shortError(error));
            } else {
                showToast("房间已创建");
            }
        }));
    }

    /** 1.2.1：客人加入的执行体，接收邀请码模态输入值。 */
    private void doJoinByCode(String code) {
        if (!withEngine()) {
            return;
        }
        lastWasCreate = false;
        lastRoomForRetry = code;
        RoomService.get().guestRoomAsync(code, playerName(), publicNodes(), (ok, error) -> runFx(() -> {
            if (error != null || !Boolean.TRUE.equals(ok)) {
                showToast("加入房间失败：" + shortError(error));
            } else {
                showToast("已加入房间");
            }
        }));
    }

    /** 1.2.1：取消——中断当前建房/加入，回待机；引擎 /state/ide 保留进程，下次可直接用。 */
    private void doCancel() {
        RoomService.get().resetIdleAsync((ok, error) -> runFx(() -> {
            if (error != null || !Boolean.TRUE.equals(ok)) {
                showToast("取消失败：" + shortError(error));
            }
        }));
    }

    private void doRetry() {
        if (lastWasCreate) {
            String code = lastRoomForRetry.isEmpty() ? generateInviteCode() : lastRoomForRetry;
            createRoom(code);
        } else {
            doJoinByCode(lastRoomForRetry);
        }
    }

    private void doCopy() {
        String code = inviteField.getText();
        if (code == null || code.isBlank()) {
            showToast("当前没有可复制的邀请码");
            return;
        }
        try {
            ClipboardContent content = new ClipboardContent();
            content.putString(code);
            boolean done = Clipboard.getSystemClipboard().setContent(content);
            showToast(done ? "邀请码已复制" : "复制失败，请手动选中邀请码");
        } catch (Throwable t) {
            showToast("复制失败，请手动选中邀请码");
        }
    }

    private void doReset() {
        RoomService.get().resetIdleAsync((ok, error) -> runFx(() -> {
            if (error != null || !Boolean.TRUE.equals(ok)) {
                showToast("返回待机失败：" + shortError(error));
            }
        }));
    }

    /**
     * 1.2.4 关房/退房：按钮立即置忙态禁用；shutdownAsync 走可逆 /state/ide 并发布 WAITING，
     * 成功后才 toast「房间已关闭 / 已退出房间」；失败如实报原因、绝不假成功。
     */
    private void stopRoom(boolean host) {
        runFx(() -> {
            closeBtn.setDisable(true);
            exitBtn.setDisable(true);
        });
        RoomService.get().shutdownAsync((ok, error) -> runFx(() -> {
            boolean good = Boolean.TRUE.equals(ok) && error == null;
            if (!good) {
                applyButtons(stateOf(RoomService.currentState()));
                showToast((host ? "关闭房间" : "退出房间") + "失败：" + shortError(error));
                return;
            }
            applyButtons(stateOf(RoomService.currentState()));
            showToast(host ? "房间已关闭" : "已退出房间");
            Thread t = new Thread(this::ensureEngineStarted, "gbl-mp-ui-restart");
            t.setDaemon(true);
            t.start();
        }));
    }

    /** 后台非阻塞地保证引擎已启动；失败不再崩页（引擎已内置，仅极端环境才需联网兜底）。 */
    private void ensureEngineStarted() {
        try {
            if (RoomService.get().isRunning()) {
                runFx(() -> updateEngineBar(true));
                return;
            }
            RoomService.get().startAsync((ok, error) -> runFx(() -> {
                boolean good = Boolean.TRUE.equals(ok) && error == null;
                updateEngineBar(good);
            }));
        } catch (Throwable t) {
            runFx(() -> updateEngineBar(false));
        }
    }

    /** 引擎没就绪时给个软提示，并在后台补一次启动，不阻塞也不报错弹窗。 */
    private boolean withEngine() {
        if (RoomService.get().isRunning()) {
            return true;
        }
        showToast("联机组件正在启动，请稍候再试");
        Thread t = new Thread(this::ensureEngineStarted, "gbl-mp-ui-ensure");
        t.setDaemon(true);
        t.start();
        return false;
    }

    // ------------------------------------------------------------------ 工具

    private List<String> publicNodes() {
        try {
            return new ArrayList<>(MultiplayerConfig.get().customPublicNodes());
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    private static String playerName() {
        try {
            return str(IdentityBridge.playerName());
        } catch (Throwable t) {
            return "";
        }
    }

    /** 房主本地生成一个合法邀请码：U/XXXX-XXXX-XXXX-XXXX。 */
    private static String generateInviteCode() {
        StringBuilder sb = new StringBuilder("U/");
        for (int g = 0; g < 4; g++) {
            if (g > 0) {
                sb.append('-');
            }
            for (int i = 0; i < 4; i++) {
                sb.append(ALPHABET.charAt(java.util.concurrent.ThreadLocalRandom.current()
                        .nextInt(ALPHABET.length())));
            }
        }
        return sb.toString();
    }

    private static String shortError(Throwable t) {
        if (t == null) {
            return "未知原因";
        }
        String m = t.getMessage();
        if (m == null || m.isBlank()) {
            return "未知原因";
        }
        return m.length() > 80 ? m.substring(0, 80) + "…" : m;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static void setTextIfChanged(Labeled label, String text) {
        if (!Objects.equals(label.getText(), text)) {
            label.setText(text);
        }
    }

    private static void setTextIfChanged(TextInputControl field, String text) {
        if (!Objects.equals(field.getText(), text)) {
            field.setText(text);
        }
    }

    private static void setShownIfChanged(Node node, boolean show) {
        if (node.isVisible() != show || node.isManaged() != show) {
            setShown(node, show);
        }
    }

    private static void setChipStyle(Label chip, String cls) {
        if (!chip.getStyleClass().contains(cls)) {
            chip.getStyleClass().removeAll("chip", "chip-accent", "chip-pending", "chip-error");
            chip.getStyleClass().add("chip");
            if (!"chip".equals(cls)) {
                chip.getStyleClass().add(cls);
            }
        }
    }

    private static void setShown(Node node, boolean show) {
        node.setVisible(show);
        node.setManaged(show);
    }

    private static void runFx(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    /**
     * 1.2.5：所有短提示统一委托 ShellView 的窗口右下角气泡（v0.4.1 同款），
     * 页内不再自建 BOTTOM_CENTER / BOTTOM_RIGHT 气泡，也不拦截点击。
     */
    private void showToast(String text) {
        runFx(() -> {
            Consumer<String> sink = toastSink;
            if (sink != null) {
                try {
                    sink.accept(text);
                } catch (RuntimeException ignored) {
                    // 提示失败绝不影响业务
                }
                return;
            }
            // 未接线时（独立单测）给一个可停的无 UI 动画句柄，保证 onHidden 清场语义一致。
            FadeTransition noop = new FadeTransition(Duration.millis(1));
            toastAnimations.add(noop);
            noop.setOnFinished(e -> toastAnimations.remove(noop));
            noop.play();
        });
    }
}
