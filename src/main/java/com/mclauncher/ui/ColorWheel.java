package com.mclauncher.ui;

import java.util.function.Consumer;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;

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
    /** 色环**外缘**半径（描边真正画到的地方，不是描边中心线）。 */
    private static final double RING_OUT = 96;
    /** 色环宽度（描边线宽）。 */
    private static final double RING_WIDTH = 16;
    /** 内部饱和度/亮度方块的半边长（对角 79.2 < 内半径 80，整块落在环内）。 */
    private static final double SQUARE_HALF = 56;
    /** 圆心。 */
    private static final double CENTER = SIZE / 2.0;
    /** 环内半径（描边内缘）。 */
    private static final double RING_IN = RING_OUT - RING_WIDTH;
    /**
     * 描边**中心线**半径 = 外缘 - 半个线宽。
     *
     * <p>1.3.4 修的就是这里：{@code strokeArc} 的线宽是向外、向内各扩一半，
     * 1.3.3 直接把椭圆半径写成 {@code RING_OUT}，描边实际外缘变成
     * {@code 96 + 16/2 = 104}，而画布半宽只有 {@code 98}，于是上下左右四个正方向
     * 被方形画布裁成直边、只有四角（对角线方向没超界）还留着弧。
     * 改成用中心线半径画椭圆后，外缘 = 88 + 8 = 96 ≤ 98，整圆完整落在画布内。
     */
    private static final double RING_STROKE_R = RING_OUT - RING_WIDTH / 2.0;
    /** 色相标记所在半径（环中线，与描边中心线重合）。 */
    private static final double MARKER_R = RING_STROKE_R;
    /** 命中判定：环外沿额外容差（手指/鼠标点偏一点点仍算环内）。 */
    private static final double HIT_OUTER_SLACK = 2.0;
    /** 命中判定：环内沿额外容差（沿用 1.3.3 口径，方块四角行为不变）。 */
    private static final double HIT_INNER_SLACK = 3.0;

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

    // ------------------------------------------------------------------ QA 钩子
    // 下面几个是静态纯函数（不碰 JavaFX Toolkit、不读磁盘），QA 可直接断言几何口径；
    // 1.3.4 新增，用来证明「圆环外缘不再超出画布、不会被裁成直边」。

    /** QA：画布边长（像素）。 */
    public static double sizeForTests() {
        return SIZE;
    }

    /** QA：圆心坐标（画布边长的一半）。 */
    public static double centerForTests() {
        return CENTER;
    }

    /** QA：圆环视觉外缘半径（应 ≤ {@link #centerForTests()}，否则上下左右会被裁平）。 */
    public static double ringOuterRadiusForTests() {
        return RING_OUT;
    }

    /** QA：圆环视觉内缘半径。 */
    public static double ringInnerRadiusForTests() {
        return RING_IN;
    }

    /** QA：描边中心线半径（{@code strokeArc} 该用的椭圆半径）。 */
    public static double ringStrokeRadiusForTests() {
        return RING_STROKE_R;
    }

    /** QA：色相标记所在半径，必须等于描边中心线半径（标记落在环中线）。 */
    public static double ringMarkerRadiusForTests() {
        return MARKER_R;
    }

    /** QA：圆环外缘到画布边的余量，必须 &gt;= 0。 */
    public static double ringOuterMarginForTests() {
        return CENTER - RING_OUT;
    }

    /** QA：描边外扩后是否仍完整落在画布内（true = 不裁切，完整圆）。 */
    public static boolean ringFitsCanvasForTests() {
        return RING_STROKE_R + RING_WIDTH / 2.0 <= CENTER;
    }

    /** QA：命中判定外沿（超过这个距离的点击当误触，不响应）。 */
    public static double hitOuterForTests() {
        return RING_OUT + HIT_OUTER_SLACK;
    }

    /** QA：取色环画布快照用（QA 渲染后直接读像素，判断外缘是否为完整圆）。 */
    public Canvas ringCanvasForTests() {
        return ringCanvas;
    }

    // ------------------------------------------------------------------ 绘制

    /**
     * 色环：按「像素到圆心的距离」逐点着色，画出一个外缘正好落在 {@link #RING_OUT} 的完整圆环。
     *
     * <p><b>为什么不再用 360 段 {@code strokeArc}：</b>1.3.3 是「2° 弧、1° 步进」画 360 段，
     * 相邻弧重叠 1°，同一个边缘像素会被抗锯齿地叠加几十上百次，覆盖率
     * {@code 1-(1-α)^360} 直接饱和成不透明 —— 描边外缘从 96 被"糊"到 ~97.5，
     * 而画布半宽只有 98，于是上下左右四个正方向仍然贴到边界、看起来像被切平。
     * 换成「一个像素只写一次」，外缘就精确停在 96，四边留 2px 余量，整圆不再被裁。
     *
     * <p>色相口径与 1.3.3 完全一致：0° 在 3 点钟方向、逆时针递增（和 JavaFX 画弧一致），
     * 所以色相标记（{@link #MARKER_R}）与环上颜色仍然一一对应。
     */
    private void paintRing() {
        GraphicsContext gc = ringCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, SIZE, SIZE);
        int side = (int) SIZE;
        WritableImage image = new WritableImage(side, side);
        PixelWriter writer = image.getPixelWriter();
        // 只扫环带的外接正方形，中间的方块区域与四角一律不动
        int min = (int) Math.floor(CENTER - RING_OUT - 1);
        int max = (int) Math.ceil(CENTER + RING_OUT + 1);
        for (int y = Math.max(0, min); y < Math.min(side, max); y++) {
            for (int x = Math.max(0, min); x < Math.min(side, max); x++) {
                double dx = x + 0.5 - CENTER;
                double dy = CENTER - (y + 0.5);   // 屏幕 y 向下，换算成数学坐标
                double dist = Math.hypot(dx, dy);
                // 内沿/外沿各留 1px 羽化：一次写一个像素，覆盖率不会被重复叠加
                double coverage = Math.min(dist - (RING_IN - 0.5), (RING_OUT + 0.5) - dist);
                if (coverage <= 0) {
                    continue;
                }
                if (coverage > 1) {
                    coverage = 1;
                }
                double deg = Math.toDegrees(Math.atan2(dy, dx));
                if (deg < 0) {
                    deg += 360;
                }
                writer.setColor(x, y, Color.hsb(deg, 1.0, 1.0, coverage));
            }
        }
        gc.drawImage(image, 0, 0);
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
        if (dist >= RING_IN - HIT_INNER_SLACK) {
            // 环带（含内外沿一点点容差）：改色相。
            // 1.3.4 起环的实际外缘就是 RING_OUT（96），再远就是画布角落的死区，直接不响应。
            if (dist > RING_OUT + HIT_OUTER_SLACK) {
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
