package com.mclauncher.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Cursor;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * 自绘开关。JavaFX 没有原生 Switch 控件，官方答案（CheckBox / 改皮的 ToggleButton）
 * 都要么长得不像、要么自带焦点环和灰底方块，跟这套界面并用不上。
 *
 * <p>四块矩形叠出来：轨道 + 选中色叠加层 + 描边用的焦点环 + 滑块。填充色全部交给 CSS
 * （{@code .toggle-track / .toggle-track.on / .toggle-thumb}），这里只管几何与位置——
 * 深色主题要换颜色时不用回 Java 改色值。
 *
 * <p>1.1：滑块位置与轨道颜色都走 {@link Timeline} 平滑过渡（HMCL 那种胶囊滑动 + 颜色渐变，
 * 没有涟漪扩散）；颜色渐变不写死色值，靠叠加层的 opacity 在两种 CSS 颜色之间插值。
 * 只有用户点击 / 空格 / 回车才播动画；构造器初值与程序 setSelected 一律瞬间落位，
 * 避免开屏时一屏开关一起动。
 *
 * <p>键盘可达：Tab 能聚焦、空格 / 回车切换，聚焦时露出描边环。
 */
public final class ToggleSwitch extends Pane {

    private static final double W = 34;      // 轨道宽
    private static final double H = 20;      // 轨道高
    private static final double THUMB = 16;  // 滑块直径
    private static final double PAD = 2;     // 滑块到轨道边的间隙
    private static final double RING = 2;    // 焦点环外扩
    /** HMCL 式滑动时长：200ms，短到不拖沓、长到看得见滑块在走。 */
    private static final Duration SLIDE_DURATION = Duration.millis(200);

    private final BooleanProperty selected = new SimpleBooleanProperty(false);
    private final Rectangle track = new Rectangle(W, H);
    /** 选中色叠加层：CSS 类同 {@code .toggle-track.on}，Java 只动 opacity，不写色值。 */
    private final Rectangle onLayer = new Rectangle(W, H);
    private final Rectangle ring = new Rectangle(W - RING * 2, H - RING * 2);
    private final Rectangle thumb = new Rectangle(THUMB, THUMB);

    private Timeline slide;
    /** track 上的 "on" 类名是否已落地：动画期间颜色由 onLayer 的 opacity 承担。 */
    private boolean trackOnApplied;
    /** true = 本次 selected 变化来自用户交互，属性监听器跳过瞬间重绘（改由动画驱动）。 */
    private boolean userDriven;

    /**
     * 初值必须真的写进 {@link #selected}。
     *
     * <p>v0.2.4 之前这里的形参叫 {@code selected}，把字段盖住了又从没用过——
     * 内部属性永远是构造时的 false，于是「界面动效」传 true 也照样画成关着的，
     * 表现就是开关永远默认 OFF（用户看到的「默认应该是打开的」）。
     * 现在走 setSelected：监听器会带着 on 把滑块挪好、把类名切掉，但不会播动画。
     */
    public ToggleSwitch(boolean on) {
        // 自身也要挂样式类，否则 theme.css 的 .toggle-switch:focused .toggle-ring 永不命中，
        // 键盘 Tab 聚焦时焦点环显示不出来（与类注释「聚焦时露出描边环」矛盾）
        getStyleClass().add("toggle-switch");
        for (Rectangle rect : new Rectangle[]{track, onLayer, ring, thumb}) {
            // getStyleClass().add("a b") 会把整串当一个类名，多个类名必须逐条 add
            rect.getStyleClass().add("toggle-piece");
        }
        track.getStyleClass().add("toggle-track");
        // 叠加层常驻 "on" 类（颜色由 CSS 给），关态只是把它的 opacity 降到 0
        onLayer.getStyleClass().add("toggle-track");
        onLayer.getStyleClass().add("on");
        ring.getStyleClass().add("toggle-ring");
        thumb.getStyleClass().add("toggle-thumb");

        // arc* 是椭圆的直径：高度 20 → 直径 20 = 半径 10，正好半个轨道高，两端是半圆
        track.setArcWidth(H);
        track.setArcHeight(H);
        onLayer.setArcWidth(H);
        onLayer.setArcHeight(H);
        // 正方形配自己 = 直径 = 完整圆
        thumb.setArcWidth(THUMB);
        thumb.setArcHeight(THUMB);
        // 环画在轨道上沿内缩 RING*2 处，描边宽度 2，正好贴着轨道外缘
        ring.setArcWidth(H - RING * 2);
        ring.setArcHeight(H - RING * 2);
        ring.setStrokeWidth(RING);
        ring.setTranslateX(RING);
        ring.setTranslateY(RING);

        // 环要在滑块下面、轨道上面：否则焦点环会被滑块压掉半条边
        getChildren().addAll(track, onLayer, ring, thumb);

        setCursor(Cursor.HAND);
        setMinSize(W, H);
        setPrefSize(W, H);
        setFocusTraversable(true);
        selectedProperty().addListener((obs, ov, nv) -> {
            // 用户交互由 toggleByUser 自己开动画；程序赋值 / 初值瞬间落位，不开屏齐动
            if (!userDriven) {
                applyImmediate(nv);
            }
        });
        // 过一遍 setSelected（而不是直接改字段）：监听器会带着 on 把滑块挪好、把类名切掉
        setSelected(on);
        applyImmediate(getSelected());

        setOnMouseClicked(e -> toggleByUser());
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.ENTER) {
                toggleByUser();
                e.consume();
            }
        });
    }

    public boolean getSelected() {
        return selected.get();
    }

    /** 程序设置：瞬间落位，不播动画（开屏 / 配置回填不会一屏开关一起滑）。 */
    public void setSelected(boolean value) {
        selected.set(value);
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }

    /** 用户交互专用入口：先改语义值，再用 Timeline 把几何与颜色平滑推过去。 */
    private void toggleByUser() {
        boolean next = !getSelected();
        boolean wasOn = trackOnApplied;
        userDriven = true;
        try {
            selected.set(next);
        } finally {
            userDriven = false;
        }
        animateTo(next, wasOn);
    }

    /** 瞬间落位：构造器初值与程序设置走这里。 */
    private void applyImmediate(boolean on) {
        if (slide != null) {
            slide.stop();
            slide = null;
        }
        thumb.setX(on ? W - THUMB - PAD : PAD);
        thumb.setY((H - THUMB) / 2.0);
        onLayer.setOpacity(on ? 1.0 : 0.0);
        setTrackOn(on);
    }

    /**
     * HMCL 式过渡：滑块 X 与叠加层 opacity 同一条 Timeline、同一条 EASE_BOTH 曲线。
     *
     * <p>轨道的 "on" 类名在动画期间不直接切：关→开时动画末尾才挂（此前叠加层已不透明，
     * 视觉无跳变）；开→关时先把叠加层提到不透明、再摘类名，之后靠它淡出。
     * 这样既有 CSS 驱动的深浅主题颜色，又不需要在 Java 里写死任何色值。
     */
    private void animateTo(boolean on, boolean wasOn) {
        if (slide != null) {
            slide.stop();
            slide = null;
        }
        double fromX = thumb.getX();
        double toX = on ? W - THUMB - PAD : PAD;
        double fromOpacity = onLayer.getOpacity();
        double toOpacity = on ? 1.0 : 0.0;
        if (!on && wasOn) {
            // 轨道此刻是选中色：先把叠加层铺满（视觉不变）再摘 "on"，整段渐变交给 opacity
            onLayer.setOpacity(1.0);
            fromOpacity = 1.0;
            setTrackOn(false);
        }
        if (fromX == toX && fromOpacity == toOpacity) {
            setTrackOn(on);
            return;
        }
        slide = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(thumb.xProperty(), fromX, Interpolator.EASE_BOTH),
                        new KeyValue(onLayer.opacityProperty(), fromOpacity, Interpolator.EASE_BOTH)),
                new KeyFrame(SLIDE_DURATION,
                        new KeyValue(thumb.xProperty(), toX, Interpolator.EASE_BOTH),
                        new KeyValue(onLayer.opacityProperty(), toOpacity, Interpolator.EASE_BOTH)));
        slide.setOnFinished(e -> {
            slide = null;
            onLayer.setOpacity(on ? 1.0 : 0.0);
            setTrackOn(on);
        });
        slide.play();
    }

    /** 类名同步：ObservableList 允许重复元素，先比标志再增删，避免列表被塞两个 "on"。 */
    private void setTrackOn(boolean on) {
        if (trackOnApplied == on) {
            return;
        }
        if (on) {
            track.getStyleClass().add("on");
        } else {
            track.getStyleClass().remove("on");
        }
        trackOnApplied = on;
    }
}
