package com.mclauncher.ui;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.mclauncher.ThemeColor;
import com.mclauncher.ThemeCss;
import com.mclauncher.ThemeStore;

import javafx.animation.PauseTransition;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/**
 * 1.3.3 外观运行时：把 {@link ThemeStore} 里的选择真正落到界面上。
 *
 * <p>它只管四件事，别往里加业务：
 * <ol>
 *   <li><b>壁纸层</b>：插在 shell 的最底下（index 0）。shell 自带一圈圆角裁剪
 *       （{@code frameClip}），所以壁纸<b>天生跟着圆角走</b>，不用另裁一次，
 *       最大化时也会跟其它内容一起变回直角。</li>
 *   <li><b>固定蒙版</b>：壁纸之上、内容之下的半透明层（浅色盖白、深色盖黑），
 *       透明度写死在 CSS 里（{@code .gbl-wallpaper-scrim}），界面不给调——
 *       它不负责"给壁纸换色"，只负责让字看得清。</li>
 *   <li><b>运行时样式表</b>：主题色/文字色被映射成一份新样式表追加到 Scene 上，
 *       详见 {@link ThemeCss}。</li>
 *   <li><b>给 Java 侧取色</b>：涟漪、内联 {@code -fx-text-fill} 这类写死在代码里的绿，
 *       通过 {@link #accentVariant(String)} 跟上主题色。</li>
 * </ol>
 *
 * <p>**出厂状态下这个类什么都不做**：没壁纸就不插可见层、两色都没自定义就不注入样式表，
 * 界面与 1.3.2 逐像素一致。
 */
public final class ThemeRuntime {

    /** 壁纸模式类名，挂在 shell 上，CSS 用它把 .frame/.app-bar/.status-bar 的底色让出来。 */
    private static final String WALLPAPER_CLASS = "gbl-wallpaper";
    /** 蒙版类名。 */
    private static final String SCRIM_CLASS = "gbl-wallpaper-scrim";

    /** 实时预览节流：色环拖动时每 140ms 才真正重写一次样式表（114 KB）。 */
    private static final Duration CSS_THROTTLE = Duration.millis(140);

    private final StackPane shell;
    private final Scene scene;
    private ThemeStore store;

    /** 壁纸层（最底）与蒙版（壁纸之上）。 */
    private final Pane wallpaperLayer = new Pane();
    private final ImageView wallpaperView = new ImageView();
    private final Region scrim = new Region();

    /** 已挂到 Scene 上的运行时样式表 URL，换色时先摘干净再挂新的。 */
    private final List<String> appliedSheets = new ArrayList<>();

    private Image wallpaperImage;
    /** 当前已载入的壁纸路径，避免重复解码。 */
    private String wallpaperSource;
    /** 载入失败的路径：同一个文件失败一次就不再重试，免得每帧都去读一张坏图。 */
    private String wallpaperFailedSource;

    private boolean attached;
    private boolean dark;
    private boolean cssDirty = true;
    private PauseTransition cssThrottle;
    /** 上一次真正注入过的 (主题色, 文字色)。没变就不重写样式表——切背景、开关设置都不该重算。 */
    private String appliedAccent;
    private String appliedText;
    private boolean cssAppliedOnce;

    public ThemeRuntime(StackPane shell, Scene scene, ThemeStore store) {
        this.shell = shell;
        this.scene = scene;
        this.store = store == null ? ThemeStore.defaults() : store;
        wallpaperView.setPreserveRatio(false);
        wallpaperView.setSmooth(true);
        wallpaperLayer.getStyleClass().add("gbl-wallpaper-layer");
        wallpaperLayer.setMouseTransparent(true);
        wallpaperLayer.setMinSize(0, 0);
        wallpaperLayer.getChildren().add(wallpaperView);
        wallpaperLayer.setVisible(false);
        scrim.getStyleClass().add(SCRIM_CLASS);
        scrim.setMouseTransparent(true);
        scrim.setVisible(false);
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 把壁纸层与蒙版插进 shell。必须在 shell 的子节点（root / 菜单 / 涟漪层 / toast）
     * 都加完之后调用，否则 index 0/1 会把别的东西顶到前面去。
     */
    public void attach(boolean darkNow) {
        if (attached) {
            return;
        }
        attached = true;
        this.dark = darkNow;
        shell.getChildren().add(0, scrim);
        shell.getChildren().add(0, wallpaperLayer);
        // 窗口尺寸变化时重新算"盖满"的比例；圆角裁剪由 shell 自己的 clip 负责，这里不用管
        shell.widthProperty().addListener((obs, ov, nv) -> layoutWallpaper());
        shell.heightProperty().addListener((obs, ov, nv) -> layoutWallpaper());
        refresh(darkNow);
    }

    public ThemeStore store() {
        return store;
    }

    /** 换一份配置；调用方负责随后 {@link #reapply()}。 */
    public void setStore(ThemeStore next) {
        this.store = next == null ? ThemeStore.defaults() : next;
        this.cssDirty = true;
    }

    /**
     * 刷新：深浅主题切换、配置变更都走这里。
     *
     * <p>幂等——{@code ShellView.applyTheme()} 每次拿焦点都会调一次，这里不能有副作用累积。
     * 深浅切换<b>不需要</b>重算样式表：映射结果与深浅无关（见 {@link ThemeColor} 类注释），
     * 只有"用户改了颜色"才把 {@link #cssDirty} 置脏。
     */
    public void refresh(boolean darkNow) {
        this.dark = darkNow;
        if (!attached) {
            return;
        }
        syncWallpaper();
        if (cssDirty) {
            cssDirty = false;
            applyCss();
        }
    }

    /** 配置变了（颜色 / 壁纸）：立刻重算一次。 */
    public void reapply() {
        cssDirty = true;
        refresh(dark);
    }

    /**
     * 实时预览：拖动色环时高频调用，节流到 {@value #CSS_THROTTLE} 毫秒一次，
     * 观感上是"立刻变"，但不会把整窗样式重算 60 次/秒。
     */
    public void reapplyLive() {
        cssDirty = true;
        if (!attached) {
            return;
        }
        if (cssThrottle == null) {
            cssThrottle = new PauseTransition(CSS_THROTTLE);
            cssThrottle.setOnFinished(e -> {
                syncWallpaper();
                if (cssDirty) {
                    cssDirty = false;
                    applyCss();
                }
            });
        }
        cssThrottle.playFromStart();
    }

    // ------------------------------------------------------------------ 壁纸

    private void syncWallpaper() {
        if (!store.wallpaperUsable()) {
            deactivateWallpaper();
            return;
        }
        String path = store.wallpaperPath();
        if (wallpaperImage != null && !wallpaperImage.isError() && path.equals(wallpaperSource)) {
            activateWallpaper();
            return;
        }
        if (path.equals(wallpaperFailedSource)) {
            deactivateWallpaper();
            return;
        }
        loadWallpaperAsync(path);
    }

    /** 后台解码（大图不阻塞 FX 线程）；成功才切换成壁纸模式，失败安静退回纯色背景。 */
    private void loadWallpaperAsync(String path) {
        Image image;
        try {
            image = new Image(Paths.get(path).toUri().toString(), true);
        } catch (RuntimeException ex) {
            wallpaperFailedSource = path;
            deactivateWallpaper();
            return;
        }
        image.progressProperty().addListener((obs, ov, nv) -> {
            if (nv == null || nv.doubleValue() < 1.0) {
                return;
            }
            if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) {
                wallpaperFailedSource = path;
                deactivateWallpaper();
                System.err.println("[theme] 壁纸解码失败，退回纯色背景: " + path);
                return;
            }
            wallpaperImage = image;
            wallpaperSource = path;
            wallpaperFailedSource = null;
            wallpaperView.setImage(image);
            layoutWallpaper();
            activateWallpaper();
        });
        image.errorProperty().addListener((obs, ov, nv) -> {
            if (Boolean.TRUE.equals(nv)) {
                wallpaperFailedSource = path;
                deactivateWallpaper();
            }
        });
    }

    private void activateWallpaper() {
        if (!shell.getStyleClass().contains(WALLPAPER_CLASS)) {
            shell.getStyleClass().add(WALLPAPER_CLASS);
        }
        wallpaperLayer.setVisible(true);
        scrim.setVisible(true);
    }

    private void deactivateWallpaper() {
        shell.getStyleClass().remove(WALLPAPER_CLASS);
        wallpaperLayer.setVisible(false);
        scrim.setVisible(false);
    }

    /**
     * HMCL 式 {@code BackgroundSize(AUTO, AUTO, false, false, false, true)} 的等价实现：
     * 等比放大到<b>盖满</b>窗口（取宽高比的较大缩放），超出部分居中裁掉、不留边、不变形；
     * 窗口拉伸时只是重算这一个比例，图本身不重采样。小图会跟着放大（可能糊，导入时已提示过）。
     */
    private void layoutWallpaper() {
        if (wallpaperImage == null) {
            return;
        }
        double width = shell.getWidth();
        double height = shell.getHeight();
        double iw = wallpaperImage.getWidth();
        double ih = wallpaperImage.getHeight();
        if (width <= 0 || height <= 0 || iw <= 0 || ih <= 0) {
            return;
        }
        double scale = Math.max(width / iw, height / ih);
        double fitW = iw * scale;
        double fitH = ih * scale;
        wallpaperView.setFitWidth(fitW);
        wallpaperView.setFitHeight(fitH);
        // 居中：多出来的部分两边均分，shell 的圆角裁剪会把溢出部分切掉
        wallpaperView.setX((width - fitW) / 2.0);
        wallpaperView.setY((height - fitH) / 2.0);
    }

    // ------------------------------------------------------------------ 样式表

    private void applyCss() {
        if (!attached) {
            return;
        }
        boolean need = store.needsRuntimeCss();
        String accentKey = need ? store.effectiveAccent() : null;
        String textKey = need ? store.effectiveText() : null;
        if (cssAppliedOnce && java.util.Objects.equals(accentKey, appliedAccent)
                && java.util.Objects.equals(textKey, appliedText)) {
            return;   // 两色都没变（比如只是换了壁纸 / 拖了其它设置）：不重写 114 KB 样式表
        }
        appliedAccent = accentKey;
        appliedText = textKey;
        cssAppliedOnce = true;

        for (String url : appliedSheets) {
            scene.getStylesheets().remove(url);
        }
        appliedSheets.clear();
        if (!need) {
            // 出厂状态：一份样式表都不多挂，弹层也退回原样式表
            GblMotion.setExtraPopupStylesheets(null);
            return;
        }
        String base = ThemeCss.baseCss();
        if (base.isEmpty()) {
            GblMotion.setExtraPopupStylesheets(null);
            return;
        }
        String mapped = ThemeCss.map(base, store.effectiveAccent(), store.effectiveText());
        if (mapped.isEmpty()) {
            GblMotion.setExtraPopupStylesheets(null);
            return;
        }
        String url = ThemeCss.writeRuntimeCss(mapped);
        if (url == null) {
            GblMotion.setExtraPopupStylesheets(null);
            return;
        }
        // 追加在 theme.css 之后：同名规则后者赢，深浅两套分支都跟着一起变
        scene.getStylesheets().add(url);
        appliedSheets.add(url);

        // 弹层（ComboBox 下拉、右键菜单那类独立 Window）不认主 Scene 的样式表，
        // 单独生成一份映射后的 popup-dark.css 一起注册过去；没这份，下拉里还会是绿的。
        List<String> popupSheets = new ArrayList<>(2);
        popupSheets.add(url);
        String popupBase = ThemeCss.popupDarkCss();
        if (!popupBase.isEmpty()) {
            String popupUrl = ThemeCss.writeRuntimeCss(
                    ThemeCss.map(popupBase, store.effectiveAccent(), store.effectiveText()));
            if (popupUrl != null) {
                popupSheets.add(popupUrl);
            }
        }
        GblMotion.setExtraPopupStylesheets(popupSheets);
    }

    // ------------------------------------------------------------------ 给 Java 侧取色

    /**
     * 把一个写死在 Java 里的绿色换成当前主题色（没自定义时原样返回）。
     * 用于内联样式与自绘元素——它们绕过了 CSS，只能在这里接。
     */
    public String accentVariant(String originalGreen) {
        if (!store.customAccent()) {
            return originalGreen;
        }
        String mapped = ThemeColor.mapAccent(originalGreen, store.effectiveAccent(), dark);
        return mapped == null ? originalGreen : mapped;
    }

    /** 按钮涟漪那圈高光：深色主题用白光，浅色主题用主题色。 */
    public Color rippleColor() {
        if (dark) {
            return Color.web("#ffffff", 0.30);
        }
        return Color.web(accentVariant("#2f9e58"), 0.20);
    }

    /** 当前生效主题色的 {@code #rrggbb}（出厂绿或用户选的那个）。 */
    public String accentHex() {
        return store.effectiveAccent();
    }

    // ------------------------------------------------------------------ QA 钩子

    /** QA：壁纸层当前是否真的在显示（模式 + 文件 + 解码三者都成立才算）。 */
    public boolean wallpaperVisibleForTests() {
        return wallpaperLayer.isVisible() && scrim.isVisible()
                && shell.getStyleClass().contains(WALLPAPER_CLASS);
    }

    /** QA：当前挂了几个运行时样式表（出厂状态下必须是 0）。 */
    public int injectedSheetCountForTests() {
        return appliedSheets.size();
    }

    /** QA：壁纸按"盖满"算出来的显示尺寸 {@code [宽, 高]}，未加载时为 {@code [0, 0]}。 */
    public double[] wallpaperFitForTests() {
        return new double[] {wallpaperView.getFitWidth(), wallpaperView.getFitHeight()};
    }
}
