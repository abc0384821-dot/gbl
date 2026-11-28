package com.mclauncher.ui;

import java.util.function.Consumer;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;

/**
 * 1.3.3 自绘调色盘：外面一圈色环选色相，里面一个方块选饱和度/亮度。
 *
 * <p>为什么自己画而不是用 JavaFX 自带的 {@code ColorPicker}：自带控件的弹层是 modena 默认皮，
 * 深色主题下白底刺眼，而且它内部是调 {@code ColorPickerSkin} 的私有结构，改不动。
 * 这里两块 {@link Canvas} 全自己画，皮肤、尺寸、命中判定都归自己，深浅主题下只要给容器挂
 * 一个类名就够了。
 *
 * <p>交互口径（与 Blockbench / HMCL 那类观感一致，实现完全自写）：
 * <ul>
 *   <li>色环：按下/拖动改色相，0° 在 3 点钟方向、逆时针递增（与 JavaFX 画弧的口径一致）。</li>
 *   <li>方块：横轴饱和度（左白右纯色），纵轴亮度（上亮下黑）。</li>
 *   <li>环外/角落的死区不响应，绝不"点一下界面就跳色"。</li>
 * </ul>
 *
 * <p>刻意做成不依赖场景/皮肤的纯 {@link Region}：QA 可以直接 {@code setColor} 再读
 * {@link #colorHex()} 做断言，不需要真的去点它。
 */
public final class ColorWheel extends Region {

    /** 控件边长。 */
    private static final double SIZE = 196;
    /** 色环外半径。 */
    private static final double RING_OUT = 96;
    /** 色环宽度。 */
    private static final double RING_WIDTH = 16;
    /** 内部饱和度/亮度方块的半边长（对角 79.2 < 内半径 88，整块落在环内）。 */
    private static final double SQUARE_HALF = 56;
    /** 圆心。 */
    private static final double CENTER = SIZE / 2.0;
    /** 环内半径。 */
    private static final double RING_IN = RING_OUT - RING_WIDTH;
    /** 色相标记所在半径（环中线）。 */
    private static final double MARKER_R = RING_OUT - RING_WIDTH / 2.0;

    /** 色环只画一次：它跟当前色无关，拖动时重画 360 段弧纯属浪费。 */
    private final Canvas ringCanvas = new Canvas(SIZE, SIZE);
    /** 方块 + 两个标记，每次变色重画（一条渐变 + 两个圆，开销可忽略）。 */
    private final Canvas layerCanvas = new Canvas(SIZE, SIZE);

    private double hue = 142.8;      // 出厂绿 #237a44 的色相，打开调色盘就停在这里
    private double saturation = 0.554;
    private double brightness = 0.478;

    /** 用户改色回调（收到 {@code #rrggbb}）。拖拽过程中会连续回调，节流由调用方负责。 */
    private Consumer<String> onColorChanged;

    public ColorWheel() {
        getStyleClass().add("color-wheel");
        // Region 默认不排版子节点：两块画布都在 (0,0)，尺寸由构造参数定死，不需要 layout
        getChildren().addAll(ringCanvas, layerCanvas);
        setMinSize(SIZE, SIZE);
        setPrefSize(SIZE, SIZE);
        setMaxSize(SIZE, SIZE);
        setFocusTraversable(false);
        setPickOnBounds(true);   // Region 没有不透明背景，不显式打开的话空白处收不到鼠标
        setPickOnBounds(true);   // Region 没有不透明背景，不显式打开的话空白处收不到鼠标
        paintRing();
        paintLayer();

        setOnMousePressed(e -> handleMouse(e.getX(), e.getY()));
        setOnMouseDragged(e -> handleMouse(e.getX(), e.getY()));
    }

    // ------------------------------------------------------------------ 对外

    /** 设置当前颜色（不回调和 {@link #onColorChanged}）。非法输入忽略。 */
    public void setColor(String hex) {
        Color color = toColor(hex);
        if (color == null) {
            return;
        }
        saturation = color.getSaturation();
        brightness = color.getBrightness();
        // 纯黑/纯白的色相没有意义，别把色环标记甩回 0°，保留上一次的色相手感更好
        if (saturation > 0.01) {
            hue = color.getHue();
        }
        paintLayer();
    }

    /** 当前颜色 {@code #rrggbb}（小写）。 */
    public String colorHex() {
        return toHex(Color.hsb(hue, saturation, brightness));
    }

    public void setOnColorChanged(Consumer<String> listener) {
        this.onColorChanged = listener;
    }

    // ------------------------------------------------------------------ 绘制

    /** 色环：360 段 2° 弧（多画 1° 让相邻段咬住，避免极细的接缝）。 */
    private void paintRing() {
        GraphicsContext gc = ringCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, SIZE, SIZE);
        gc.setLineCap(StrokeLineCap.BUTT);
        gc.setLineWidth(RING_WIDTH);
        for (int deg = 0; deg < 360; deg++) {
            gc.setStroke(Color.hsb(deg, 1.0, 1.0));
            gc.strokeArc(CENTER - RING_OUT, CENTER - RING_OUT, RING_OUT * 2, RING_OUT * 2,
                    deg, 2.0, ArcType.OPEN);
        }
    }

    /** 方块（白→纯色的横向渐变 + 透明→黑的纵向渐变）+ 两个标记。 */
    private void paintLayer() {
        GraphicsContext gc = layerCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, SIZE, SIZE);

        double side = SQUARE_HALF * 2;
        double sx = CENTER - SQUARE_HALF;
        double sy = CENTER - SQUARE_HALF;
        gc.setFill(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.WHITE), new Stop(1, Color.hsb(hue, 1.0, 1.0))));
        gc.fillRect(sx, sy, side, side);
        gc.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.TRANSPARENT), new Stop(1, Color.BLACK)));
        gc.fillRect(sx, sy, side, side);
        gc.setStroke(Color.web("#ffffff", 0.35));
        gc.setLineWidth(1);
        gc.strokeRect(sx + 0.5, sy + 0.5, side - 1, side - 1);

        // 色相标记：环中线上的小白点，外面套一圈半透明黑，深浅底上都看得见
        double angle = Math.toRadians(hue);
        double mx = CENTER + MARKER_R * Math.cos(angle);
        double my = CENTER - MARKER_R * Math.sin(angle);
        gc.setStroke(Color.web("#0b1a2b", 0.55));
        gc.setLineWidth(4);
        gc.strokeOval(mx - 5, my - 5, 10, 10);
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(2);
        gc.strokeOval(mx - 5, my - 5, 10, 10);

        // 饱和度/亮度标记
        double px = sx + saturation * side;
        double py = sy + (1.0 - brightness) * side;
        gc.setStroke(Color.web("#0b1a2b", 0.55));
        gc.setLineWidth(4);
        gc.strokeOval(px - 5.5, py - 5.5, 11, 11);
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(2);
        gc.strokeOval(px - 5.5, py - 5.5, 11, 11);
    }

    // ------------------------------------------------------------------ 交互

    private void handleMouse(double x, double y) {
        double dx = x - CENTER;
        double dy = CENTER - y;   // 屏幕 y 向下，换算成数学坐标
        double dist = Math.hypot(dx, dy);
        boolean changed = false;
        if (dist >= RING_IN - 3) {
            // 环带（含外沿一点点容差）：改色相
            if (dist > RING_OUT + 8) {
                return;   // 点得太远，当误触
            }
            double deg = Math.toDegrees(Math.atan2(dy, dx));
            if (deg < 0) {
                deg += 360;
            }
            hue = deg;
            changed = true;
        } else {
            // 环内：夹到方块范围内改饱和/亮度，角落误点也不会跳到界外
            double sx = CENTER - SQUARE_HALF;
            double sy = CENTER - SQUARE_HALF;
            double side = SQUARE_HALF * 2;
            double relX = clamp((x - sx) / side, 0, 1);
            double relY = clamp((y - sy) / side, 0, 1);
            saturation = relX;
            brightness = 1.0 - relY;
            changed = true;
        }
        if (!changed) {
            return;
        }
        paintLayer();
        if (onColorChanged != null) {
            onColorChanged.accept(colorHex());
        }
    }

    // ------------------------------------------------------------------ 工具

    private static Color toColor(String hex) {
        if (hex == null) {
            return null;
        }
        String text = hex.trim();
        if (!text.startsWith("#")) {
            text = "#" + text;
        }
        try {
            return Color.web(text);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String toHex(Color color) {
        return String.format("#%02x%02x%02x",
                Math.round(color.getRed() * 255),
                Math.round(color.getGreen() * 255),
                Math.round(color.getBlue() * 255));
    }

    private static double clamp(double v, double min, double max) {
        if (v < min) {
            return min;
        }
        return Math.min(v, max);
    }
}
