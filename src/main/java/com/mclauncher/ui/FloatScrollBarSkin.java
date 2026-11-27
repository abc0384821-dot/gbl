package com.mclauncher.ui;

import javafx.event.EventHandler;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Skin;
import javafx.scene.control.SkinBase;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;

/**
 * GBL 1.0.3 自绘滚动条皮肤：观感照 HMCL（最小 48px 好抓握 / 细胶囊 / 透明轨道），
 * 交互公式与 JavaFX 原生 {@code ScrollBarSkin} + {@code ScrollBarBehavior} 逐字一致。
 *
 * <p><b>1.0.3 修的是什么</b>：1.0.1 为了修「滑块越界」，把 thumb 改成
 * {@code layoutY = trackStart + travel * (value-min)/(max-min-visibleAmount)}、并把
 * translateY 清成 0；但拖动仍走原生 skin 的
 * {@code value = min + (preDragPos + dragPos/(trackLength-thumbLength)) * (max-min)}。
 * 定位用「减去 visibleAmount 的分母 + layoutY」，拖动用「不减 visibleAmount 的分母 +
 * translateY」——两套坐标系互相打架：鼠标每移 1px，slider 的实际位移与手指位移不成比例，
 * 松手瞬间又被 value 监听拉回另一套公式算出的位置，于是表现为「乱跳 / 不跟手 / 卡顿」。
 *
 * <p>本版不再继承原生 skin，而是自绘 track / thumb 并自管全部交互，唯一一套公式：
 * <pre>
 *   position = (value - min) / (max - min)                    // 不减 visibleAmount
 *   thumbTranslate = (trackLength - thumbLength) * position    // 用 translate，不用 layoutY
 *   dragValue  = min + (preDragPos + drag / (trackLength - thumbLength)) * (max - min)
 * </pre>
 * 与 OpenJFX 21 {@code ScrollBarSkin.positionThumb()} / {@code setOnMouseDragged()} 的公式
 * 完全相同，因此「拖到哪就停哪」，且 thumb 恒在 track 内（fraction 天然 ∈ [0,1]）。
 *
 * <p>尺寸：thumb 最短 48px（原生 minThumbLength 只有 1.5×breadth≈18px，长列表会缩成小点），
 * 厚度沿用 CSS 的 12px 轨道，不强改宽度，避免 1.0.1 之前「48×48 撑出横线」的越界。
 * 明/暗配色、hover/pressed 全部沿用 theme.css / popup-dark.css 现有 .thumb 规则。
 *
 * <p>安装入口保持不变：{@link #install(ScrollPane...)} / {@link #installAll(Parent)}，幂等。
 */
public final class FloatScrollBarSkin extends SkinBase<ScrollBar> {

    /** 1.0.3 指纹：自绘皮肤与原生共用一套拖动公式。构建门禁扫常量池，勿改这串字。 */
    public static final String FP_103_NATIVE_MATH = "GBL 1.0.3 自绘皮肤与原生同公式";
    static { System.err.println("[scroll] " + FP_103_NATIVE_MATH); }

    /** 1.0.4 指纹：不可见 / 未管理的滚动条不绘制 track/thumb，杜绝隐藏水平条的灰色横线。构建门禁扫常量池，勿改这串字。 */
    public static final String FP_104_HIDDEN_HBAR = "GBL 1.0.4 隐藏滚动条不绘制";
    static { System.err.println("[scroll] " + FP_104_HIDDEN_HBAR); }

    /**
     * 1.0.5 指纹：单 thumb、无原生 ScrollBarSkin 残留。构建门禁扫 class 常量池，
     * 必须是真实引用的字符串常量，勿改这串字。
     */
    public static final String FP_105_SINGLE_THUMB = "GBL 1.0.5 单thumb无原生残留";
    static { System.err.println("[scroll] " + FP_105_SINGLE_THUMB); }

    /** 垂直 / 水平方向的最小滑块长度；原生只有约 18px，太短不好抓。 */
    private static final double MIN_THUMB_LEN = 48;

    private final Region track = new Region();
    private final Region thumb = new Region();

    private double trackLength;
    private double thumbLength;
    private double preDragThumbPos;
    private Point2D dragStart;
    private boolean dragging;

    private final EventHandler<KeyEvent> keyHandler = this::handleKey;
    private final EventHandler<ScrollEvent> scrollHandler = this::handleScroll;

    public FloatScrollBarSkin(ScrollBar bar) {
        super(bar);
        track.getStyleClass().setAll("track");
        thumb.getStyleClass().setAll("thumb");
        getChildren().addAll(track, thumb);

        // 与原生一致：value 变化只调 positionThumb（不 requestLayout），
        // min/max/visibleAmount/orientation 变化才重新布局。
        bar.valueProperty().addListener((obs, ov, nv) -> positionThumb());
        bar.minProperty().addListener((obs, ov, nv) -> getSkinnable().requestLayout());
        bar.maxProperty().addListener((obs, ov, nv) -> getSkinnable().requestLayout());
        bar.visibleAmountProperty().addListener((obs, ov, nv) -> getSkinnable().requestLayout());
        bar.orientationProperty().addListener((obs, ov, nv) -> getSkinnable().requestLayout());
        // 1.0.4：可见 / 管理状态翻转时立即同步 track/thumb，覆盖「invisible 后 managed=false
        // 导致 layoutChildren 不再被调用」的情形，确保隐藏的水平条不会残留灰色 thumb。
        bar.visibleProperty().addListener((obs, ov, nv) -> {
            syncChildrenVisibility();
            getSkinnable().requestLayout();
        });
        bar.managedProperty().addListener((obs, ov, nv) -> {
            syncChildrenVisibility();
            getSkinnable().requestLayout();
        });

        installMouseHandlers(bar);
        bar.addEventHandler(KeyEvent.KEY_RELEASED, keyHandler);
        bar.addEventHandler(ScrollEvent.SCROLL, scrollHandler);
        getSkinnable().requestLayout();
    }

    /* ------------------------------------------------------------------ */
    /* 供探针直接验证的纯函数：与 skin 内部调用完全同一份实现                 */
    /* ------------------------------------------------------------------ */

    /** 原生定位/拖动统一用的比例，分母是 max-min（不减 visibleAmount）。 */
    public static double positionFraction(double value, double min, double max) {
        double range = max - min;
        if (range <= 0) {
            return 0;
        }
        return clamp((value - min) / range, 0, 1);
    }

    /** thumb 起点在轨道上的偏移；恒 ∈ [0, trackLength-thumbLength]，天然不出轨。 */
    public static double thumbOffset(double value, double min, double max,
                                     double trackLength, double thumbLength) {
        double travel = Math.max(0, trackLength - thumbLength);
        return travel * positionFraction(value, min, max);
    }

    /** 拖动：手指位移 1px → thumb 位移 1px（因为 value 与位置共用同一比例）。 */
    public static double valueFromDrag(double preDragThumbPos, double dragPos,
                                       double trackLength, double thumbLength,
                                       double min, double max) {
        double travel = trackLength - thumbLength;
        if (travel <= 0) {
            return clamp(min + preDragThumbPos * (max - min), min, max);
        }
        double pos = clamp(preDragThumbPos + dragPos / travel, 0, 1);
        return clamp(min + pos * (max - min), min, max);
    }

    /** thumb 长度：原生 visiblePortion 比例，夹到 [最小 48px, 整个轨道]。 */
    public static double thumbLengthFor(double visibleAmount, double min, double max,
                                        double trackLength) {
        double range = max - min;
        double portion = range > 0 ? visibleAmount / range : 1;
        return Math.min(trackLength, Math.max(MIN_THUMB_LEN, trackLength * portion));
    }

    /* ------------------------------------------------------------------ */
    /* 安装入口（保持不变）                                                 */
    /* ------------------------------------------------------------------ */

    /** 给指定 ScrollPane 内部的每一条 scroll-bar 换上本皮肤（幂等）。 */
    public static void install(ScrollPane... panes) {
        if (panes == null) {
            return;
        }
        for (ScrollPane pane : panes) {
            if (pane == null) {
                continue;
            }
            for (Node n : pane.lookupAll(".scroll-bar")) {
                installOn(n);
            }
        }
    }

    /** 遍历整棵场景树（主 scene 的 shell 即可，详情弹窗是 shell 内遮罩、同一 scene）。 */
    public static void installAll(Parent root) {
        if (root == null) {
            return;
        }
        for (Node n : root.lookupAll(".scroll-bar")) {
            installOn(n);
        }
    }

    private static void installOn(Node n) {
        if (n instanceof ScrollBar sb && !(sb.getSkin() instanceof FloatScrollBarSkin)) {
            // 1.0.4 第一道门禁：ScrollPane 在 HbarPolicy=NEVER 时把水平条设为
            // visible=false / managed=false，但它仍留在场景树里会被 lookupAll(".scroll-bar")
            // 找到。这种隐藏条不换自绘皮肤，交给原生 skin，从源头杜绝灰色横线。
            if (!isDrawable(sb)) {
                return;
            }
            // 1.0.5：Control.setSkin() 只换 skin 属性，不会移除旧 ScrollBarSkin 建的子节点。
            // 换皮前先清掉 ScrollBar 现有 children（track-background / increment / decrement /
            // track / thumb 这些原生残留一并移除），否则每条可见滚动条上会同时出现两个 thumb：
            // 原生 ScrollBarSkin$1（约 13×20、固定在顶部、点击走已被替换的原生 behavior，拖不动）
            // 与本皮肤自绘的 13×48 主滑块。
            // 注意：Parent.getChildren() 是 protected，跨包不能直接在 ScrollBar 上调；
            // 但 SkinBase.getChildren() 是 public，且它返回的就是 Control 的那一份 children
            // 列表（SkinBase 构造时取自 control.getControlChildren()），故借旧皮肤清空即可。
            // clear 必须在 setSkin 之前：新皮肤的 track/thumb 由 SkinBase 构造时自己 addAll 进去。
            Skin<?> oldSkin = sb.getSkin();
            if (oldSkin instanceof SkinBase<?> oldBase) {
                oldBase.getChildren().clear();
            }
            sb.setSkin(new FloatScrollBarSkin(sb));
        }
    }

    /**
     * 1.0.4：自身可见、被管理且所有祖先可见时，才允许绘制 track/thumb。
     *
     * <p>{@code ScrollPane} 在 {@code HbarPolicy=NEVER} 下把水平滚动条置为
     * {@code visible=false} / {@code managed=false}，但它依旧挂在场景树里，
     * {@code lookupAll(".scroll-bar")} 仍能找到；必须在这里拦下来。父链可见性一并检查，
     * 覆盖整块弹层被隐藏的情形。
     */
    private static boolean isDrawable(ScrollBar bar) {
        if (bar == null || !bar.isVisible() || !bar.isManaged()) {
            return false;
        }
        for (Node p = bar.getParent(); p != null; p = p.getParent()) {
            if (!p.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /** 1.0.4：隐藏时把 track/thumb 清成 0×0 并设为不可见，确保一条像素都不画。 */
    private void syncChildrenVisibility() {
        boolean draw = isDrawable(getSkinnable());
        track.setVisible(draw);
        thumb.setVisible(draw);
        if (!draw) {
            track.resize(0, 0);
            thumb.resize(0, 0);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 布局：尺寸与 track 位置；thumb 位置交给 positionThumb（translate）    */
    /* ------------------------------------------------------------------ */

    @Override
    protected void layoutChildren(double x, double y, double w, double h) {
        ScrollBar bar = getSkinnable();
        // 1.0.4 第二道门禁：每次布局先按可见性同步 track/thumb；隐藏的水平条被清成 0×0
        // 且不可见，直接返回，绝不走到下面的 thumb 绘制（灰色横线根因）。
        syncChildrenVisibility();
        if (!isDrawable(bar)) {
            return;
        }
        boolean vertical = bar.getOrientation() == Orientation.VERTICAL;
        double range = bar.getMax() - bar.getMin();
        double visiblePortion = range > 0 ? bar.getVisibleAmount() / range : 1;

        if (vertical) {
            trackLength = snapSizeY(h);
            thumbLength = Math.min(trackLength, Math.max(MIN_THUMB_LEN, trackLength * visiblePortion));
            track.resizeRelocate(snapPositionX(x), snapPositionY(y), w, trackLength);
            thumb.resize(snapSizeX(w), thumbLength);
        } else {
            trackLength = snapSizeX(w);
            thumbLength = Math.min(trackLength, Math.max(MIN_THUMB_LEN, trackLength * visiblePortion));
            track.resizeRelocate(snapPositionX(x), snapPositionY(y), trackLength, h);
            thumb.resize(thumbLength, snapSizeY(h));
        }
        positionThumb();
    }

    /**
     * 与原生 ScrollBarSkin.positionThumb() 同构：用 translate 定位，layout 永远留在 0，
     * 杜绝 1.0.1 的 layoutY/translateY 双重定位。fraction 用 (value-min)/(max-min)。
     */
    private void positionThumb() {
        ScrollBar bar = getSkinnable();
        double offset = thumbOffset(bar.getValue(), bar.getMin(), bar.getMax(),
                trackLength, thumbLength);
        if (bar.getOrientation() == Orientation.VERTICAL) {
            thumb.setLayoutX(0);
            thumb.setLayoutY(0);
            thumb.setTranslateX(snapPositionX(snappedLeftInset()));
            thumb.setTranslateY(snapPositionY(offset + snappedTopInset()));
        } else {
            thumb.setLayoutX(0);
            thumb.setLayoutY(0);
            thumb.setTranslateX(snapPositionX(offset + snappedLeftInset()));
            thumb.setTranslateY(snapPositionY(snappedTopInset()));
        }
    }

    /* ------------------------------------------------------------------ */
    /* 交互：与原生 skin + behavior 公式一致                                */
    /* ------------------------------------------------------------------ */

    private void installMouseHandlers(ScrollBar bar) {
        // 点轨道 = 原生 trackPress：立即按点击比例调整一次（adjustValue 内部按 max-min 换算）。
        track.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY || trackLength <= 0) {
                return;
            }
            boolean vertical = bar.getOrientation() == Orientation.VERTICAL;
            double along = vertical ? e.getY() : e.getX();
            if (!Double.isNaN(along)) {
                bar.adjustValue(clamp(along / trackLength, 0, 1));
            }
            e.consume();
        });
        track.setOnMouseReleased(MouseEvent::consume);

        // 拖 thumb：与原生逐字一致——preDragThumbPos 分母 max-min，位移除以轨道余量。
        thumb.setOnMousePressed(e -> {
            if (e.isSynthesized()) {
                e.consume();
                return;
            }
            if (bar.getMax() > bar.getMin()) {
                dragStart = thumb.localToParent(e.getX(), e.getY());
                double clampedValue = clamp(bar.getValue(), bar.getMin(), bar.getMax());
                preDragThumbPos = (clampedValue - bar.getMin()) / (bar.getMax() - bar.getMin());
                dragging = true;
                e.consume();
            }
        });
        thumb.setOnMouseDragged(e -> {
            if (e.isSynthesized()) {
                e.consume();
                return;
            }
            if (bar.getMax() > bar.getMin() && trackLength > thumbLength) {
                Point2D cur = thumb.localToParent(e.getX(), e.getY());
                if (dragStart == null) {
                    dragStart = thumb.localToParent(e.getX(), e.getY());
                }
                double dragPos = bar.getOrientation() == Orientation.VERTICAL
                        ? cur.getY() - dragStart.getY()
                        : cur.getX() - dragStart.getX();
                double value = valueFromDrag(preDragThumbPos, dragPos, trackLength, thumbLength,
                        bar.getMin(), bar.getMax());
                bar.setValue(value);
            }
            e.consume();
        });
        thumb.setOnMouseReleased(e -> {
            dragging = false;
            dragStart = null;
            e.consume();
        });
    }

    private void handleKey(KeyEvent e) {
        ScrollBar bar = getSkinnable();
        KeyCode code = e.getCode();
        boolean handled = true;
        if (code == KeyCode.HOME) {
            bar.setValue(bar.getMin());
        } else if (code == KeyCode.END) {
            bar.setValue(bar.getMax());
        } else if (code == KeyCode.UP || code == KeyCode.KP_UP) {
            bar.decrement();
        } else if (code == KeyCode.DOWN || code == KeyCode.KP_DOWN) {
            bar.increment();
        } else if (code == KeyCode.LEFT || code == KeyCode.KP_LEFT) {
            bar.decrement();
        } else if (code == KeyCode.RIGHT || code == KeyCode.KP_RIGHT) {
            bar.increment();
        } else {
            handled = false;
        }
        if (handled) {
            e.consume();
        }
    }

    private void handleScroll(ScrollEvent e) {
        if (trackLength <= thumbLength) {
            return;
        }
        ScrollBar bar = getSkinnable();
        double dx = e.getDeltaX();
        double dy = e.getDeltaY();
        double delta = bar.getOrientation() == Orientation.VERTICAL
                ? dy : (Math.abs(dx) < Math.abs(dy) ? dy : dx);
        if (delta > 0 && bar.getValue() > bar.getMin()) {
            bar.decrement();
            e.consume();
        } else if (delta < 0 && bar.getValue() < bar.getMax()) {
            bar.increment();
            e.consume();
        }
    }

    @Override
    public void dispose() {
        ScrollBar bar = getSkinnable();
        if (bar != null) {
            bar.removeEventHandler(KeyEvent.KEY_RELEASED, keyHandler);
            bar.removeEventHandler(ScrollEvent.SCROLL, scrollHandler);
        }
        dragging = false;
        dragStart = null;
        super.dispose();
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
