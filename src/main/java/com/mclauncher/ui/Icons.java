package com.mclauncher.ui;

import javafx.scene.paint.Color;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.geometry.Bounds;

/**
 * 内置矢量图标，统一 24x24 坐标系、纯填充路径，避免引入图标字体或额外依赖。
 */
public final class Icons {

    public static final String LOGO = "M12 2.4 20.4 7v10L12 21.6 3.6 17V7z";
    public static final String PLAY = "M8.4 5.1 19 12 8.4 18.9z";
    public static final String STOP = "M7 7h10v10H7z";   // 24x24 实心方块 ■
    public static final String VERSIONS = "M4 4.4h6.3v6.3H4zM13.7 4.4H20v6.3h-6.3zM4 13.3h6.3v6.3H4zM13.7 13.3H20v6.3h-6.3z";
    public static final String DOWNLOAD = "M12 3.3 8.1 7.2 9.8 8.9 11.2 7.5v7.6h1.6V7.5l1.4 1.4 1.7-1.7zM6.6 15.9h1.6v4.5h7.6v-4.5h1.6v6.1H6.6z";
    public static final String CONSOLE = "M3.4 5h17.2A1.4 1.4 0 0122 6.4v11.2a1.4 1.4 0 01-1.4 1.4H3.4A1.4 1.4 0 012 17.6V6.4A1.4 1.4 0 013.4 5zm3.1 3.3 3.2 3.2-3.2 3.2-1.1-1.1 2.1-2.1-2.1-2.1zm5.3 2.3h7.2v1.6h-7.2z";
    public static final String SETTINGS = "M4 6.8h4v2H4zM9.9 6.8H20v2H9.9zM4 15.2h11.2v2H4zM17.5 15.2H20v2h-2.5z";
    public static final String FOLDER = "M3 6.4h6.1l2.1 2.3H21v11H3z";
    public static final String REFRESH = "M13.6 3.2 6.9 12.6h3.9l-1.2 8.2 7.4-10.3h-4.4z";
    public static final String CUBE = "M12 2.8 20.6 7.3v9.4L12 21.2 3.4 16.7V7.3z";
    /**
     * 账号（人像）：头 + 肩两段路径。侧边栏「账号」入口与添加账号弹层共用。
     */
    public static final String ACCOUNT = "M12 12.2a4 4 0 100-8 4 4 0 000 8zM5.2 20.4a6.8 6.8 0 0113.6 0z";
    /**
     * 「更多」三点。跟 {@link #VERSIONS} 同一个路子——用小方块拼，不用圆点：
     * 描边图标（WIN_* 那组）走 fill=透明 + 描边，而这里要的是「实心小点」的观感，
     * 方块在 16px 下比圆点更容易看清、也和整页的方角语言一致。
     */
    public static final String MORE = "M5.4 10.3h3.1v3.4H5.4zM10.7 10.3h3.1v3.4h-3.1zM16 10.3h3.1v3.4H16z";

    // ---------------------------------------------------------------- 1.0.8 侧边栏专用导航图标
    // 只给侧边栏 6 个 NavItem 用：PLAY / DOWNLOAD / CUBE 等老常量被封面兜底与启动按钮
    // 多处复用，一个字都不能改。全部 24x24、填充友好（偶数奇 fill rule 挖空细节），
    // 与 theme.css 的 .nav-item .icon-path { -fx-fill: ... } 直接兼容，深浅主题无需改 CSS。

    /** 启动：一枚斜向点火的火箭，舷窗挖空，尾焰落在 6 点钟方向，侧翼带折角。 */
    public static final String NAV_LAUNCH =
            "M12 2.2c3.1 1.5 5.2 4.5 5.6 8.1l-1.7 3.4-2 2.5H9.9l-2-2.5-1.7-3.4C6.6 6.7 8.8 3.7 12 2.2z"
            + "M6.2 11.4 3.4 15.2l3.6-.8zM17.8 11.4l2.8 3.8-3.6-.8z"
            + "M10.2 18.2h3.6L12 21.8z"
            + "M12 6.8a2 2 0 1 0 0 4 2 2 0 0 0 0-4z";
    // 1.0.9：下面四个导航图标改为「等宽线条」画法——每条线都是一条闭合的细长轮廓，
    // 经 createNav() 的 EVEN_ODD 填充后视觉上等同 1.6~1.8px 描边，深浅主题无需动 CSS。
    // 只用 M/L/H/V/C/Z：上一版 NAV_GAMES 用 SVG 弧线命令触发过 JavaFX
    // 「0 <= tfrom <= tto <= 1 required」且 bounds 变 -1，这次整组彻底禁掉 A。

    /**
     * 1.1.2 按参考图重画：圆角文件夹描边（左上 tab + 斜肩） + 一支实心向下箭头。
     * 1.1 旧版是「文件夹轮廓 + 挖空线条箭头」；新版箭头落在文件夹挖空内，EVEN_ODD 下自然反白成实心。
     * 只用 M/L/H/V/C/Z，禁 A。
     */
    public static final String NAV_DOWNLOAD =
            "M4.2 3.2 H9.3 L11.1 5.4 H19.8 C20.7 5.4 21.2 6 21.2 6.9 V18.6"
            + " C21.2 19.5 20.7 20.1 19.8 20.1 H4.2 C3.3 20.1 2.8 19.5 2.8 18.6 V4.7"
            + " C2.8 3.8 3.3 3.2 4.2 3.2 Z"
            + " M4.8 5 H8.6 L10 7 H18.9 C19.2 7 19.4 7.3 19.4 7.7 V17.8"
            + " C19.4 18.2 19.2 18.4 18.9 18.4 H4.8 C4.5 18.4 4.3 18.2 4.3 17.8 V5.6"
            + " C4.3 5.3 4.5 5 4.8 5 Z"
            + " M10.8 8.6 H13.2 V12.7 H16.4 L12 17 L7.6 12.7 H10.8 Z";
    /** 1.1 游戏列表：照抄参考图「三本书」——左本斜立、右两本直立，书脊挖空、两枚圆点。只用 M/L/H/V/C/Z。 */
    public static final String NAV_GAMES =
            "M4.8 5L8.8 5.9L7.2 19.8L3.2 18.9Z"
            + "M5.9 7.1L7.5 7.5L6.2 17.2L4.6 16.8Z"
            + "M9.4 4.7H14V19.8H9.4Z"
            + "M10.5 6H12.9V18.4H10.5Z"
            + "M11.7 7.25C12.225 7.25 12.65 7.675 12.65 8.2C12.65 8.725 12.225 9.15 11.7 9.15"
            + "C11.175 9.15 10.75 8.725 10.75 8.2C10.75 7.675 11.175 7.25 11.7 7.25Z"
            + "M14.4 4.7H19.2V19.8H14.4Z"
            + "M15.5 6H18.1V18.4H15.5Z"
            + "M16.8 7.25C17.325 7.25 17.75 7.675 17.75 8.2C17.75 8.725 17.325 9.15 16.8 9.15"
            + "C16.275 9.15 15.85 8.725 15.85 8.2C15.85 7.675 16.275 7.25 16.8 7.25Z";
    /**
     * 1.1.2 按参考图重画：圆角文件夹描边 + 文件夹内一枚实心八齿尖齿齿轮（中心留白孔）。
     * 1.1 旧版是「圆角文件夹 + 实心花形齿轮」；新版文件夹更高更方，齿轮改为尖齿小齿轮。
     * 只用 M/L/H/V/C/Z，禁 A。
     */
    public static final String NAV_MANAGE =
            "M5 3.4 H9.4 L11.2 5.6 H19.2 C20.3 5.6 21 6.3 21 7.4 V17.6"
            + " C21 18.7 20.3 19.4 19.2 19.4 H4.8 C3.7 19.4 3 18.7 3 17.6 V5.2"
            + " C3 4.1 3.7 3.4 5 3.4 Z"
            + " M5.3 5.1 H8.9 L10.3 7.2 H18.9 C19.2 7.2 19.4 7.5 19.4 7.9 V17.2"
            + " C19.4 17.6 19.2 17.8 18.9 17.8 H5.1 C4.8 17.8 4.6 17.6 4.6 17.2 V5.7"
            + " C4.6 5.4 4.8 5.1 5.3 5.1 Z"
            + " M14.12 11.12 C14.32 11.59 15.43 11.13 15.48 11.63 C15.51 11.88 15.51 12.12 15.48 12.37"
            + " C15.43 12.87 14.32 12.41 14.12 12.88 C13.93 13.35 15.04 13.81 14.72 14.20"
            + " C14.57 14.39 14.39 14.57 14.20 14.72 C13.81 15.04 13.35 13.93 12.88 14.12"
            + " C12.41 14.32 12.87 15.43 12.37 15.48 C12.12 15.51 11.88 15.51 11.63 15.48"
            + " C11.13 15.43 11.59 14.32 11.12 14.12 C10.65 13.93 10.19 15.04 9.80 14.72"
            + " C9.61 14.57 9.43 14.39 9.28 14.20 C8.96 13.81 10.07 13.35 9.88 12.88"
            + " C9.68 12.41 8.57 12.87 8.52 12.37 C8.49 12.12 8.49 11.88 8.52 11.63"
            + " C8.57 11.13 9.68 11.59 9.88 11.12 C10.07 10.65 8.96 10.19 9.28 9.80"
            + " C9.43 9.61 9.61 9.43 9.80 9.28 C10.19 8.96 10.65 10.07 11.12 9.88"
            + " C11.59 9.68 11.13 8.57 11.63 8.52 C11.88 8.49 12.12 8.49 12.37 8.52"
            + " C12.87 8.57 12.41 9.68 12.88 9.88 C13.35 10.07 13.81 8.96 14.20 9.28"
            + " C14.39 9.43 14.57 9.61 14.72 9.80 C15.04 10.19 13.93 10.65 14.12 11.12 Z"
            + " M13.00 12.00 C13.00 12.55 12.55 13.00 12.00 13.00 C11.45 13.00 11.00 12.55 11.00 12.00"
            + " C11.00 11.45 11.45 11.00 12.00 11.00 C12.55 11.00 13.00 11.45 13.00 12.00 Z";
    /** 控制台：开窗终端，尖括号与光标条在镂空窗口里反白显示，区别于老实心终端（1.0.8 原件未动）。 */
    public static final String NAV_CONSOLE =
            "M3.2 4.4h17.6v15.2H3.2zM5.4 6.6h13.2v10.8H5.4z"
            + "M7.2 9l2.6 2.2-2.6 2.2-1.2-1.2 1.4-1-1.4-1z"
            + "M11.2 13.1h4.6v1.7h-4.6z";
    /**
     * 1.1.2 按参考图重画：六齿圆头齿轮描边 + 中心圆环（外齿轮挖空成粗描边）。
     * 1.1 旧版是「八齿圆头齿轮 + 中心圆环」；按参考图实测齿数为六齿（极坐标谷间隔 60°）。
     * 只用 M/L/H/V/C/Z，禁 A。
     */
    public static final String NAV_SETTINGS =
            "M18.75 8.10 C19.11 8.72 21.14 9.33 21.29 10.02 C21.57 11.33 21.57 12.67 21.29 13.98"
            + " C21.14 14.67 19.11 15.28 18.75 15.90"
            + " C18.40 16.52 18.89 18.58 18.36 19.06 C17.37 19.95 16.20 20.62 14.94 21.04"
            + " C14.26 21.26 12.71 19.80 12.00 19.80"
            + " C11.29 19.80 9.74 21.26 9.06 21.04 C7.80 20.62 6.63 19.95 5.64 19.06"
            + " C5.11 18.58 5.60 16.52 5.25 15.90"
            + " C4.89 15.28 2.86 14.67 2.71 13.98 C2.43 12.67 2.43 11.33 2.71 10.02"
            + " C2.86 9.33 4.89 8.72 5.25 8.10"
            + " C5.60 7.48 5.11 5.42 5.64 4.94 C6.63 4.05 7.80 3.38 9.06 2.96"
            + " C9.74 2.74 11.29 4.20 12.00 4.20"
            + " C12.71 4.20 14.26 2.74 14.94 2.96 C16.20 3.38 17.37 4.05 18.36 4.94"
            + " C18.89 5.42 18.40 7.48 18.75 8.10 Z"
            + " M17.20 9.00 C17.55 9.62 19.38 9.70 19.53 10.40 C19.76 11.45 19.76 12.55 19.53 13.60"
            + " C19.38 14.30 17.55 14.38 17.20 15.00"
            + " C16.84 15.62 17.68 17.24 17.15 17.72 C16.35 18.44 15.41 18.99 14.38 19.32"
            + " C13.70 19.54 12.71 18.00 12.00 18.00"
            + " C11.29 18.00 10.30 19.54 9.62 19.32 C8.59 18.99 7.65 18.44 6.85 17.72"
            + " C6.32 17.24 7.16 15.62 6.80 15.00"
            + " C6.45 14.38 4.62 14.30 4.47 13.60 C4.24 12.55 4.24 11.45 4.47 10.40"
            + " C4.62 9.70 6.45 9.62 6.80 9.00"
            + " C7.16 8.38 6.32 6.76 6.85 6.28 C7.65 5.56 8.59 5.01 9.62 4.68"
            + " C10.30 4.46 11.29 6.00 12.00 6.00"
            + " C12.71 6.00 13.70 4.46 14.38 4.68 C15.41 5.01 16.35 5.56 17.15 6.28"
            + " C17.68 6.76 16.84 8.38 17.20 9.00 Z"
            + " M15.40 12.00 C15.40 13.88 13.88 15.40 12.00 15.40 C10.12 15.40 8.60 13.88 8.60 12.00"
            + " C8.60 10.12 10.12 8.60 12.00 8.60 C13.88 8.60 15.40 10.12 15.40 12.00 Z"
            + " M13.90 12.00 C13.90 13.05 13.05 13.90 12.00 13.90 C10.95 13.90 10.10 13.05 10.10 12.00"
            + " C10.10 10.95 10.95 10.10 12.00 10.10 C13.05 10.10 13.90 10.95 13.90 12.00 Z";

    /**
     * 1.1.4 多人联机：两台相连的设备（两个方块 + 一条折线连线）。
     * 两枚方块各自由「外框 + 内框」两条闭合子路径经 EVEN_ODD 挖空成 1.6px 描边，
     * 连线是一条自带厚度的 L 形闭合带，三者只在边界相接、不重叠（重叠会被 EVEN_ODD 挖穿）。
     * 只用 M/L/H/V/Z，禁 A。
     */
    public static final String NAV_MULTIPLAYER =
            "M2.5 3.5 H9.5 V10.5 H2.5 Z"
            + "M4.1 5.1 H7.9 V8.9 H4.1 Z"
            + "M14.5 13.5 H21.5 V20.5 H14.5 Z"
            + "M16.1 15.1 H19.9 V18.9 H16.1 Z"
            + "M9.5 6.2 H12.8 V16.2 H14.5 V17.8 H11.2 V7.8 H9.5 Z";

    /**
     * 自绘窗口按钮的图标路径（描边风格，统一 24x24 坐标）。
     * 矩形一律闭合（Z）：fill 已由 {@link #stroke} 设成透明，闭合只用来把四条边补齐。
     * 真正禁止的是「描边图标忘了设 fill」——那会把它填成实心黑方块（最大化图标当初就是这样变黑的）。
     * 最大化 / 还原必须是正方形（10x10），否则看起来是个长方形。
     */
    public static final String WIN_MIN = "M6.6 12H17.4";
    public static final String WIN_MAX = "M7 7H17V17H7Z";
    public static final String WIN_RESTORE = "M6.8 6.8H15.8V15.8H6.8ZM9.2 9.2H18.2V18.2H9.2Z";
    public static final String WIN_CLOSE = "M7.4 7.4 16.6 16.6M16.6 7.4 7.4 16.6";

    /**
     * 官网（地球）：外圈 + 赤道 + 两条经线。顶栏「访问官网」按钮用，走描边风格。
     */
    public static final String GLOBE = "M12 3a9 9 0 100 18 9 9 0 000-18zM3 12h18M12 3c-4.5 2.8-4.5 15.2 0 18M12 3c4.5 2.8 4.5 15.2 0 18";

    private Icons() {
    }

    /**
     * 描边式图标（窗口按钮用）：只画轮廓不填充。
     * 描边图标必须显式透明填充，否则闭合路径会被 fill 填成实心块（最大化图标就是这么变成黑方块的）。
     */
    public static SVGPath stroke(String data, double width) {
        SVGPath path = new SVGPath();
        path.setContent(data);
        path.getStyleClass().add("stroke-icon");
        path.setFill(Color.TRANSPARENT);
        path.setStroke(Color.web("#667085"));
        path.setStrokeWidth(width);
        path.setStrokeLineCap(StrokeLineCap.ROUND);
        path.setStrokeLineJoin(StrokeLineJoin.ROUND);
        return path;
    }

    public static SVGPath stroke(String data) {
        return stroke(data, 1.7);
    }

    /** 和 {@link #create(String, double)} 一样按 size 缩放并居中，但走描边不填充（窗口按钮用）。 */
    public static SVGPath strokeScaled(String data, double size, double width) {
        SVGPath path = stroke(data, width);
        scaleInto(path, size);
        return path;
    }

    /**
     * 生成一个按 size 缩放并居中的图标路径。
     * 用 translate 而不是 layoutX 定位：VBox/HBox 会重置子节点的 layoutX/layoutY，不会动 transform。
     */
    public static SVGPath create(String data, double size) {
        // 用 SVGPath 而不是 Path：Path.setContent(String) 在 JavaFX 9 之后被移除
        SVGPath path = new SVGPath();
        path.setContent(data);
        path.getStyleClass().add("icon-path");
        scaleInto(path, size);
        return path;
    }

    /**
     * 1.0.8 侧边栏专用：默认 {@link #create} 走 NON_ZERO 填充，导航图标的「洞中洞」
     * 细节需要 EVEN_ODD 才能按预期镂空 / 反白。只影响这 6 个新图标，老图标一颗不动。
     */
    public static SVGPath createNav(String data, double size) {
        SVGPath path = create(data, size);
        path.setFillRule(FillRule.EVEN_ODD);
        return path;
    }

    /**
     * 把 24x24 坐标里的路径整体缩放到 size，并把它的视觉中心挪到 size 的正中央。
     *
     * <p>居中必须走「内容中心」而不是「包围盒左上角」：包围盒退化时会算错。
     * 典型坑是最小化图标 {@code WIN_MIN}——它是一条横线，getBoundsInLocal().getHeight() 直接返回 0，
     * 按包围盒左上角算会把 translateY 顶到 size/2，整条线掉到按钮下方 5px（三个窗口按钮会高低不齐）。
     * 所以宽高退化（以及压根没内容）时一律回落到 24 视框的中心 12。
     */
    private static void scaleInto(SVGPath path, double size) {
        double scale = size / 24.0;
        path.setScaleX(scale);
        path.setScaleY(scale);

        Bounds b = path.getBoundsInLocal();
        double cx = b.getWidth() > 0.5 ? b.getMinX() + b.getWidth() / 2 : 12.0;
        double cy = b.getHeight() > 0.5 ? b.getMinY() + b.getHeight() / 2 : 12.0;
        path.setTranslateX(size / 2 - cx * scale);
        path.setTranslateY(size / 2 - cy * scale);
    }
}
