package com.mclauncher.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.mclauncher.ThemeColor;
import com.mclauncher.ThemeCss;
import com.mclauncher.ThemeStore;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
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
    /** 当前已载入那张图的**文件指纹**（size + lastModified）。1.3.4 起判「同一张」不再只看路径。 */
    private String wallpaperSignature;
    /** 载入失败的路径：同一个文件失败一次就不再重试，免得每帧都去读一张坏图。 */
    private String wallpaperFailedSource;
    /** 失败那次的文件指纹（同一路径换了内容要允许重试）。 */
    private String wallpaperFailedSignature;
    /** 正在解码的路径 / 指纹，避免同一次刷新重复起线程。 */
    private String wallpaperLoadingSource;
    private String wallpaperLoadingSignature;
    /** 解码序号：期间又换了图就丢掉旧结果，绝不出现「后选的图被先选的覆盖」。 */
    private long wallpaperLoadToken;

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

    /**
     * 把 {@link #store} 里的壁纸选择同步到界面上。
     *
     * <p><b>1.3.4 修「换了提示说换了，但没换」：</b>1.3.3 只用
     * {@code path.equals(wallpaperSource)} 判「同一张」就直接 return，跳过了重新解码；
     * 而那时壁纸文件是固定名覆盖写，路径从头到尾不变，于是画面永远停在第第一张。
     *
     * <p>现在两重保险：
     * <ol>
     *   <li>{@link ThemeStore#importWallpaper} 每次换图都给出**新路径**（文件名带内容指纹）；</li>
     *   <li>这里再比一次文件的 {@code size + lastModified} 指纹 —— 就算路径没变
     *       （老版本留下的固定名文件被外部改写了），只要内容变了也照样重新解码。</li>
     * </ol>
     */
    private void syncWallpaper() {
        if (!store.wallpaperUsable()) {
            deactivateWallpaper();
            return;
        }
        String path = store.wallpaperPath();
        String signature = wallpaperFileFingerprintForTests(Paths.get(path));
        // 指纹读不到（极端情况：文件被独占）时退回只看路径，保持 1.3.3 的行为，绝不疯狂重试
        boolean sameLoaded = path.equals(wallpaperSource)
                && (signature == null || signature.equals(wallpaperSignature));
        if (wallpaperImage != null && !wallpaperImage.isError() && sameLoaded) {
            activateWallpaper();
            return;
        }
        boolean sameFailed = path.equals(wallpaperFailedSource)
                && (signature == null || signature.equals(wallpaperFailedSignature));
        if (sameFailed) {
            deactivateWallpaper();
            return;
        }
        boolean sameLoading = path.equals(wallpaperLoadingSource)
                && (signature == null || signature.equals(wallpaperLoadingSignature));
        if (sameLoading) {
            return;   // 这张已经在解码了，等它回来
        }
        loadWallpaperAsync(path, signature);
    }

    /**
     * 后台解码（大图不阻塞 FX 线程）；成功才切换成壁纸模式，失败安静退回纯色背景。
     *
     * <p>1.3.4 起改为「从 {@link InputStream} 解码」而不是 {@code new Image(url)}：
     * 同一个 URI 在 JavaFX 里有被复用的可能，走输入流是真正重新读字节，换图必生效。
     */
    private void loadWallpaperAsync(String path, String signature) {
        final long token = ++wallpaperLoadToken;
        wallpaperLoadingSource = path;
        wallpaperLoadingSignature = signature;
        Thread worker = new Thread(() -> {
            Image loaded = null;
            String failure = null;
            try (InputStream in = Files.newInputStream(Paths.get(path))) {
                loaded = new Image(in, 0, 0, true, true);
                if (loaded.isError() || loaded.getWidth() <= 0 || loaded.getHeight() <= 0) {
                    failure = "解码失败";
                }
            } catch (IOException | RuntimeException ex) {
                failure = String.valueOf(ex);
            }
            final Image image = loaded;
            final String error = failure;
            Platform.runLater(() -> applyLoadedWallpaper(token, path, signature, image, error));
        }, "gbl-wallpaper-load");
        worker.setDaemon(true);
        worker.start();
    }

    /** 解码结果回主线程落地；期间又换了图（token 变了）就整份丢掉。 */
    private void applyLoadedWallpaper(long token, String path, String signature,
                                      Image loaded, String failure) {
        if (token != wallpaperLoadToken) {
            return;
        }
        wallpaperLoadingSource = null;
        wallpaperLoadingSignature = null;
        if (loaded == null || failure != null) {
            wallpaperFailedSource = path;
            wallpaperFailedSignature = signature;
            deactivateWallpaper();
            System.err.println("[theme] 壁纸解码失败，退回纯色背景: " + path + " (" + failure + ")");
            return;
        }
        wallpaperImage = loaded;
        wallpaperSource = path;
        wallpaperSignature = signature;
        wallpaperFailedSource = null;
        wallpaperFailedSignature = null;
        wallpaperView.setImage(loaded);
        layoutWallpaper();
        activateWallpaper();
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

    /**
     * QA：壁纸文件的指纹（{@code size:lastModified}）。静态纯函数，不碰 JavaFX、只读文件属性。
     *
     * <p>断言点：同一个路径换掉内容后指纹必须变 —— 这是 1.3.4
     * 「换图必须重新解码」的第二道保险。
     *
     * @return 指纹串；文件不存在或读不到返回 {@code null}
     */
    public static String wallpaperFileFingerprintForTests(Path file) {
        if (file == null) {
            return null;
        }
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * QA：一张图的内容签名 {@code 宽x高:哈希}（8x8 网格抽样 + FNV-1a）。
     *
     * <p>只读 {@link Image}，不用起窗口。断言点：换图后签名必须变，
     * 同一张图重复加载签名必须稳定。
     *
     * @return 签名；图片为空 / 解码失败时返回 {@code null}
     */
    public static String imageSignatureForTests(Image image) {
        if (image == null || image.isError()) {
            return null;
        }
        int width = (int) Math.round(image.getWidth());
        int height = (int) Math.round(image.getHeight());
        PixelReader reader = image.getPixelReader();
        if (reader == null || width <= 0 || height <= 0) {
            return null;
        }
        long hash = 0xcbf29ce484222325L;   // FNV-1a 64 位
        for (int gy = 0; gy < 8; gy++) {
            for (int gx = 0; gx < 8; gx++) {
                int x = Math.min(width - 1, (int) ((gx + 0.5) * width / 8.0));
                int y = Math.min(height - 1, (int) ((gy + 0.5) * height / 8.0));
                hash ^= reader.getArgb(x, y) & 0xffffffffL;
                hash *= 0x100000001b3L;
            }
        }
        return width + "x" + height + ":" + Long.toHexString(hash);
    }

    /** QA：当前真正挂在壁纸层上的那张图的签名；没加载时 {@code null}。 */
    public String wallpaperImageSignatureForTests() {
        return imageSignatureForTests(wallpaperImage);
    }

    /** QA：当前已解码进 {@link #wallpaperView} 的壁纸路径；没加载时 {@code null}。 */
    public String wallpaperSourceForTests() {
        return wallpaperSource;
    }

    /** QA：壁纸层当前挂的那张图（供断言像素/尺寸）；没加载时 {@code null}。 */
    public Image wallpaperImageForTests() {
        return wallpaperImage;
    }
}
