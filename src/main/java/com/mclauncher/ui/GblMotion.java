package com.mclauncher.ui;

import com.mclauncher.SettingsStore;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.PopupControl;
import javafx.stage.Window;
import javafx.util.Duration;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/**
 * GBL 1.1.5 动画与弹层统一工具。
 *
 * <p>单一真源：时长 {@link #REVEAL}、缓动 {@link #EASE_BOTH}、入场位移 {@link #POPUP_OFFSET}。
 * 所有 150ms 都引用这里的常量，不写魔法数字。
 *
 * <p>reduceMotion 同时看已存在的 {@link SettingsStore#reduceMotion()} 与
 * {@code Scene.getPreferences().isReducedMotion()}。本项目锁定的 JavaFX 21 运行时尚未公开
 * 后一个 API，因此用反射安全探测；在提供该 API 的运行时上自动生效，缺失时安全降级为
 * 只按设置项判定，绝不因反射失败影响界面。
 *
 * <p>ComboBox 弹层是 {@link PopupControl}（独立 Window），不是 PopupWindow；
 * 弹层 scene 的样式表按完整资源 URL 去重挂载，深浅来回切不会叠加残留。
 */
public final class GblMotion {

    /** 入场 / 折叠动画时长：120–180ms 区间中值。 */
    public static final Duration REVEAL = Duration.millis(150);

    /** 统一缓动曲线。 */
    public static final Interpolator EASE_BOTH = Interpolator.EASE_BOTH;

    /** 类图里的别名，指向同一缓动实例。 */
    public static final Interpolator EASE = EASE_BOTH;

    /** 弹层入场起始位移（px）：轻微下滑，从 -6 回到 0。 */
    public static final double POPUP_OFFSET = -6.0;

    /** 与 ShellView 保持同一条完整资源 URL，模块路径下才能识别重复挂载。 */
    public static final String POPUP_THEME_CSS =
            GblMotion.class.getResource("/com/mclauncher/theme.css").toExternalForm();
    public static final String POPUP_DARK_CSS =
            GblMotion.class.getResource("/com/mclauncher/popup-dark.css").toExternalForm();

    /** 正在播放的弹层入场动画：弱键，窗口关闭后自动回收。 */
    private static final Map<Window, Animation> POPUP_ANIMATIONS = new WeakHashMap<>();

    /** 记住每个 ComboBox 上一次入场的弹层，onHidden 时弹层可能已关闭，仍要复位。 */
    private static final Map<ComboBox<?>, Window> LAST_POPUP = new WeakHashMap<>();

    private GblMotion() {
        // 工具类，禁止实例化
    }

    /**
     * 当前场景是否允许播放新动效。设置项与系统偏好任一要求减少动效即返回 false。
     */
    public static boolean allowed(Scene scene) {
        try {
            if (SettingsStore.get().reduceMotion()) {
                return false;
            }
        } catch (Throwable ignored) {
            // 设置项读取失败按「允许」处理，避免启动早期无配置时卡住 UI
        }
        return !systemPrefersReducedMotion(scene);
    }

    /**
     * 深色主题判定，口径与 ShellView.applyTheme() 一致：强制主题优先，否则看设置项。
     */
    public static boolean isDarkActive() {
        try {
            if (SystemTheme.isForced()) {
                return SystemTheme.isDark();
            }
            SettingsStore.ThemeMode mode = SettingsStore.get().theme();
            return mode == SettingsStore.ThemeMode.DARK
                    || (mode == SettingsStore.ThemeMode.SYSTEM && SystemTheme.isDark());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 给任意 ComboBox 接上深色弹层 + 150ms 入场，并<b>合并保留</b>调用方原有的
     * onShown / onHidden 回调（不覆盖）。
     *
     * @param box  目标下拉框，null 时直接返回
     * @param dark 深色判定 supplier；null 时退化为 {@link #isDarkActive()}
     */
    public static <T> void wireComboBox(ComboBox<T> box, BooleanSupplier dark) {
        if (box == null) {
            return;
        }
        EventHandler<Event> priorShown = box.getOnShown();
        EventHandler<Event> priorHidden = box.getOnHidden();

        box.setOnShown(e -> {
            if (priorShown != null) {
                priorShown.handle(e);
            }
            boolean useDark = dark == null ? isDarkActive() : dark.getAsBoolean();
            paintPopups(useDark);
            Window popup = showingPopupFor(box);
            if (popup != null) {
                LAST_POPUP.put(box, popup);
                animatePopupIn(popup);
            }
        });
        box.setOnHidden(e -> {
            if (priorHidden != null) {
                priorHidden.handle(e);
            }
            Window popup = LAST_POPUP.remove(box);
            if (popup == null) {
                popup = showingPopupFor(box);
            }
            if (popup != null) {
                resetPopup(popup);
            }
        });
    }

    /**
     * 弹层入场：opacity 0→1 + translateY -6→0，150ms EASE_BOTH。
     * 出场不做动画（公开 API 不可控），由 onHidden 复位，下次展开无残留。
     */
    public static void animatePopupIn(Window popup) {
        if (popup == null || popup.getScene() == null) {
            return;
        }
        resetPopup(popup);
        Node root = popup.getScene().getRoot();
        if (root == null || !allowed(popup.getScene())) {
            return;
        }
        root.setOpacity(0.0);
        root.setTranslateY(POPUP_OFFSET);

        FadeTransition fade = new FadeTransition(REVEAL, root);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.setInterpolator(EASE_BOTH);

        TranslateTransition slide = new TranslateTransition(REVEAL, root);
        slide.setFromY(POPUP_OFFSET);
        slide.setToY(0.0);
        slide.setInterpolator(EASE_BOTH);

        ParallelTransition animation = new ParallelTransition(fade, slide);
        animation.setOnFinished(e -> {
            root.setOpacity(1.0);
            root.setTranslateY(0.0);
            if (POPUP_ANIMATIONS.get(popup) == animation) {
                POPUP_ANIMATIONS.remove(popup);
            }
        });
        POPUP_ANIMATIONS.put(popup, animation);
        animation.play();
    }

    /** 复位弹层根节点状态，并停掉可能仍在跑的入场动画。 */
    public static void resetPopup(Window popup) {
        if (popup == null) {
            return;
        }
        Animation running = POPUP_ANIMATIONS.remove(popup);
        if (running != null) {
            running.stop();
        }
        if (popup.getScene() == null || popup.getScene().getRoot() == null) {
            return;
        }
        Node root = popup.getScene().getRoot();
        root.setOpacity(1.0);
        root.setTranslateY(0.0);
    }

    /** 给所有可见 ComboBox 弹层挂主题样式表；深色再叠加 popup-dark.css。 */
    public static void paintPopups(boolean dark) {
        for (Window window : Window.getWindows()) {
            if (!(window instanceof PopupControl popup) || popup.getScene() == null) {
                continue;
            }
            if (!popup.getScene().getStylesheets().contains(POPUP_THEME_CSS)) {
                popup.getScene().getStylesheets().add(POPUP_THEME_CSS);
            }
            if (dark && !popup.getScene().getStylesheets().contains(POPUP_DARK_CSS)) {
                popup.getScene().getStylesheets().add(POPUP_DARK_CSS);
            }
        }
    }

    /** 切回浅色时摘掉所有弹层样式表，防止深→浅→深残留。 */
    public static void stripPopupStyles() {
        for (Window window : Window.getWindows()) {
            if (!(window instanceof PopupControl popup) || popup.getScene() == null) {
                continue;
            }
            popup.getScene().getStylesheets().remove(POPUP_DARK_CSS);
            popup.getScene().getStylesheets().remove(POPUP_THEME_CSS);
            resetPopup(popup);
        }
    }

    /** 找到当前正显示、且属于该 ComboBox 所在窗口的弹层。 */
    private static Window showingPopupFor(ComboBox<?> box) {
        Window owner = box.getScene() == null ? null : box.getScene().getWindow();
        Window best = null;
        for (Window window : Window.getWindows()) {
            if (!(window instanceof PopupControl popup) || !popup.isShowing()
                    || popup.getScene() == null) {
                continue;
            }
            if (owner != null && popup.getOwnerWindow() != owner) {
                continue;
            }
            best = popup;
        }
        return best;
    }

    /** 反射探测系统「减少动态效果」偏好；JavaFX 21 无该 API 时返回 false。 */
    private static boolean systemPrefersReducedMotion(Scene scene) {
        Boolean fromScene = readReducedMotion(scene, "getPreferences");
        if (fromScene != null) {
            return fromScene;
        }
        try {
            Class<?> platform = Class.forName("javafx.application.Platform");
            Method get = platform.getMethod("getPreferences");
            Object preferences = get.invoke(null);
            return Boolean.TRUE.equals(readBoolean(preferences));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Boolean readReducedMotion(Scene scene, String getterName) {
        if (scene == null) {
            return null;
        }
        try {
            Method get = scene.getClass().getMethod(getterName);
            Object preferences = get.invoke(scene);
            return readBoolean(preferences);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Boolean readBoolean(Object preferences) {
        if (preferences == null) {
            return null;
        }
        try {
            Method isReduced = preferences.getClass().getMethod("isReducedMotion");
            Object value = isReduced.invoke(preferences);
            return value instanceof Boolean b ? b : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
