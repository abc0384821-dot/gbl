package com.mclauncher;

/**
 * 1.3.3 自定义外观的纯颜色数学：色相映射、三级文字明度、WCAG 对比度。
 *
 * <p>刻意<b>不依赖 JavaFX</b>：这里全是 {@code int}/{@code double} 运算，QA 不开 Toolkit 也能直接
 * 调 {@link #deriveTextLadder(String, boolean)} 之类的方法做断言，真机不用起。
 *
 * <p>三条设计口径（改动前请先读）：
 * <ol>
 *   <li><b>主题色走色相替换，不改明度。</b>GBL 的绿不是一种绿，而是十几阶
 *       （{@code #17351f} 到 {@code #e7f6ec}）。把每一阶的<b>色相</b>换成用户选的那个色相、
 *       明度（L）与饱和（S）按比例保留，原来的明暗层次就整套保住了：
 *       浅色主题用的深绿阶自然变成"深的新颜色"，深色主题用的亮绿阶自然变成"亮的新颜色"——
 *       这就是需求里"同一个色相、浅色深一档、深色亮一档"，不需要为两套主题分别算。</li>
 *   <li><b>文字色只认蓝灰色阶。</b>theme.css 里 311 处 {@code -fx-text-fill} 中，正文/标题/次要提示
 *       用的是一族蓝灰（色相 212~220、饱和 0.09~0.47），而红/橙/蓝/紫那些是语义色（报错、警告、链接），
 *       不该跟着变。所以只映射蓝灰族，语义色原样保留。</li>
 *   <li><b>映射结果与深浅无关，可以一次算好。</b>每个原始色的明度本身就编码了它属于哪套主题
 *       （浅色主题的文字是深色，深色主题的文字是浅色），所以按<b>原始明度</b>分流即可，
 *       同一份运行时样式表同时服务深浅两套，切主题不必重算。</li>
 * </ol>
 */
public final class ThemeColor {

    /** GBL 出厂主题色（深绿）。用户没自定义时一切照旧。 */
    public static final String DEFAULT_ACCENT = "#237a44";

    /** 浅色主题页面底色（对比度以它为背景算）。 */
    public static final int LIGHT_BG = 0xf7f9fc;
    /** 深色主题页面底色。 */
    public static final int DARK_BG = 0x0b1a2b;

    /** 绿色族色相区间。GBL 实测绿阶落在 136~154，两侧留足余量，但绝不至于吃到蓝/黄。 */
    private static final double GREEN_HUE_MIN = 95.0;
    private static final double GREEN_HUE_MAX = 175.0;
    /** 绿色族的饱和下限：再低就是深色底上的"带一点点绿调的黑"，也算绿族，要跟着换。 */
    private static final double GREEN_SAT_MIN = 0.10;
    /** 太亮的（近白高光，如 #fbfdfb L=0.988）当白色处理，换了也看不出来，反而不稳。 */
    private static final double GREEN_LIGHT_MAX = 0.965;

    /** 文字阶（蓝灰）色相区间：theme.css 实测 212~220，避开 210 的链接蓝（饱和更高）。 */
    private static final double TEXT_HUE_MIN = 205.0;
    private static final double TEXT_HUE_MAX = 232.0;
    private static final double TEXT_SAT_MIN = 0.09;
    private static final double TEXT_SAT_MAX = 0.52;

    /**
     * 文字阶的明度分界（0.656）。
     *
     * <p>这一步是整套映射唯一的"数值魔法"，必须钉死：GBL 里浅色主题的次要文字是
     * {@code #98a2b3}（L=0.6490），深色主题的次要文字是 {@code #9aa7b8}（L=0.6627），
     * 两者只差 0.014 却是两套主题的两端。取中点 0.656 正好把它们分开。
     * 如果以后 theme.css 换了次要文字的灰，这个常量要跟着重算。
     */
    public static final double TEXT_LADDER_SPLIT = 0.656;

    /** 浅色主题文字明度锚点（索引 0=主 1=正文 2=次要），按现网 #101828/#344054/#667085 实测。 */
    private static final double[] LIGHT_ANCHORS = {0.110, 0.267, 0.461};
    /** 深色主题文字明度锚点，按现网 #e9eef5/#c3cdd9/#9aa7b8 实测。 */
    private static final double[] DARK_ANCHORS = {0.937, 0.813, 0.663};

    /** 三级文字的目标明度：浅色主题越次要越浅，深色主题越次要越暗（两套都要够对比度）。 */
    private static final double[] LIGHT_TARGET_L = {0.20, 0.30, 0.46};
    private static final double[] DARK_TARGET_L = {0.96, 0.87, 0.68};
    /** 各级饱和上限：越次要越不扎眼，层次才立得住。 */
    private static final double[] SAT_CAP = {0.62, 0.48, 0.32};
    /** 各级最低对比度（WCAG AA：正文 4.5:1；主标题留到 7:1，次要可以松到 3.2:1）。 */
    private static final double[] MIN_CONTRAST = {7.0, 4.5, 3.2};

    private ThemeColor() {
        // 静态工具类
    }

    // ------------------------------------------------------------------ 基础转换

    /** {@code #RRGGBB} → 0xRRGGBB；认不出来返回 -1（调用方据此原样保留，绝不抛异常）。 */
    public static int parseHex(String hex) {
        if (hex == null) {
            return -1;
        }
        String text = hex.trim();
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        if (text.length() != 6) {
            return -1;
        }
        for (int i = 0; i < 6; i++) {
            if (Character.digit(text.charAt(i), 16) < 0) {
                return -1;
            }
        }
        return Integer.parseInt(text, 16);
    }

    /** 0xRRGGBB → {@code #rrggbb}（小写，与 theme.css 的写法一致，便于 diff）。 */
    public static String toHex(int rgb) {
        return String.format("#%06x", rgb & 0xffffff);
    }

    /** 规范化：合法输入返回小写 {@code #rrggbb}，非法返回 null。 */
    public static String normalize(String hex) {
        int rgb = parseHex(hex);
        return rgb < 0 ? null : toHex(rgb);
    }

    /** 0xRRGGBB → {@code double[]{h(0~360), s(0~1), l(0~1)}}。 */
    public static double[] toHsl(int rgb) {
        double r = ((rgb >> 16) & 0xff) / 255.0;
        double g = ((rgb >> 8) & 0xff) / 255.0;
        double b = (rgb & 0xff) / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double l = (max + min) / 2.0;
        if (max - min < 1e-9) {
            return new double[] {0.0, 0.0, l};
        }
        double d = max - min;
        double s = l > 0.5 ? d / (2.0 - max - min) : d / (max + min);
        double h;
        if (max == r) {
            h = ((g - b) / d) % 6.0;
        } else if (max == g) {
            h = (b - r) / d + 2.0;
        } else {
            h = (r - g) / d + 4.0;
        }
        h *= 60.0;
        if (h < 0) {
            h += 360.0;
        }
        return new double[] {h, s, l};
    }

    /** HSL → 0xRRGGBB。h 可以超出 0~360（自动折回），s/l 自动夹到 0~1。 */
    public static int fromHsl(double h, double s, double l) {
        double hh = ((h % 360.0) + 360.0) % 360.0;
        double ss = clamp01(s);
        double ll = clamp01(l);
        double c = (1.0 - Math.abs(2.0 * ll - 1.0)) * ss;
        double x = c * (1.0 - Math.abs((hh / 60.0) % 2.0 - 1.0));
        double m = ll - c / 2.0;
        double r;
        double g;
        double b;
        if (hh < 60) {
            r = c;
            g = x;
            b = 0;
        } else if (hh < 120) {
            r = x;
            g = c;
            b = 0;
        } else if (hh < 180) {
            r = 0;
            g = c;
            b = x;
        } else if (hh < 240) {
            r = 0;
            g = x;
            b = c;
        } else if (hh < 300) {
            r = x;
            g = 0;
            b = c;
        } else {
            r = c;
            g = 0;
            b = x;
        }
        int ri = (int) Math.round((r + m) * 255.0);
        int gi = (int) Math.round((g + m) * 255.0);
        int bi = (int) Math.round((b + m) * 255.0);
        return (clamp255(ri) << 16) | (clamp255(gi) << 8) | clamp255(bi);
    }

    // ------------------------------------------------------------------ 族判定

    /** 这个颜色是不是 GBL 绿族的一阶（决定要不要跟着主题色换）。 */
    public static boolean isGreenFamily(int rgb) {
        if (rgb < 0) {
            return false;
        }
        double[] hsl = toHsl(rgb);
        return hsl[0] >= GREEN_HUE_MIN && hsl[0] <= GREEN_HUE_MAX
                && hsl[1] >= GREEN_SAT_MIN
                && hsl[2] <= GREEN_LIGHT_MAX
                && hsl[2] >= 0.02;
    }

    /**
     * 这个颜色是不是"文字蓝灰阶"的一阶（决定要不要跟着文字色换）。
     *
     * <p>纯白 {@code #ffffff}（S=0）不算：它是彩色按钮上的字，跟着主题色走反而对。
     */
    public static boolean isTextLadderFamily(int rgb) {
        if (rgb < 0) {
            return false;
        }
        double[] hsl = toHsl(rgb);
        return hsl[0] >= TEXT_HUE_MIN && hsl[0] <= TEXT_HUE_MAX
                && hsl[1] >= TEXT_SAT_MIN
                && hsl[1] <= TEXT_SAT_MAX;
    }

    // ------------------------------------------------------------------ 主题色映射

    /**
     * 把一阶 GBL 绿换成"新色相、原明暗"。
     *
     * @param greenHex  theme.css 里的原始绿阶，如 {@code #237a44}
     * @param accentHex 用户选的主题色，如 {@code #2563eb}
     * @param dark      当前是否深色主题。**当前实现下不影响结果**（色相替换后深浅两套自然对齐，
     *                  见类注释第 3 条），参数保留是为了 QA 钩子签名稳定与日后按主题细分。
     * @return 替换后的 {@code #rrggbb}；输入非法或不是绿族时原样返回
     */
    public static String mapAccent(String greenHex, String accentHex, boolean dark) {
        int g = parseHex(greenHex);
        int a = parseHex(accentHex);
        if (g < 0 || a < 0) {
            return greenHex;
        }
        if (!isGreenFamily(g)) {
            return toHex(g);
        }
        double[] gh = toHsl(g);
        double[] ah = toHsl(a);
        if (ah[1] < 0.12) {
            // 无彩色兜底：用户选了纯黑/纯白/灰，色相没有意义。
            // 这时不硬套色相，改成"保明度去饱和"——绿阶变成同明度的灰阶，
            // 深浅层次与对比度和原来一模一样，界面不会坏。
            return toHex(fromHsl(ah[0], Math.min(ah[1], 0.06), gh[2]));
        }
        // 目标色本身够鲜艳就少压一点饱和；目标是灰调（0.15~0.75）时按比例收敛，
        // 免得用户选了浅蓝、界面上却蹦出一片荧光蓝。
        double vib = 0.75 + 0.25 * clamp01((ah[1] - 0.15) / 0.60);
        double s = clamp01(gh[1] * vib);
        return toHex(fromHsl(ah[0], s, gh[2]));
    }

    // ------------------------------------------------------------------ 文字色映射

    /**
     * 从一个颜色派生三级文字色：{@code [0]=标题/主, [1]=正文, [2]=次要提示}。
     *
     * <p>明度由主题定（浅色主题压深、深色主题提亮），色相沿用用户选的，饱和按级别递减
     * （越次要越灰），最后按 WCAG 对比度做一次兜底修正——所以用户选纯黄也不会得到
     * 白底上看不清的正文。
     *
     * @param textHex 用户选的文字颜色
     * @param dark    true = 深色主题那套明度
     * @return 三级 {@code #rrggbb}；输入非法返回 null
     */
    public static String[] deriveTextLadder(String textHex, boolean dark) {
        int base = parseHex(textHex);
        if (base < 0) {
            return null;
        }
        double[] hsl = toHsl(base);
        double[] targets = dark ? DARK_TARGET_L : LIGHT_TARGET_L;
        int bg = dark ? DARK_BG : LIGHT_BG;
        String[] out = new String[3];
        for (int i = 0; i < 3; i++) {
            double s = Math.min(hsl[1], SAT_CAP[i]);
            int candidate = fromHsl(hsl[0], s, targets[i]);
            // 深色主题往亮里调、浅色主题往暗里调，直到够对比度
            out[i] = toHex(ensureContrast(candidate, bg, MIN_CONTRAST[i], dark));
        }
        return out;
    }

    /**
     * 一阶文字蓝灰 → 对应的那一级派生色。
     *
     * <p>分流只看<b>原始明度</b>：原始文字本来就深（浅色主题用的）就走浅色那套阶梯，
     * 本来就浅（深色主题用的）就走深色那套。所以一份样式表能同时喂两套主题。
     *
     * @param greyHex  原始文字色
     * @param lightLadder {@link #deriveTextLadder(String, boolean)} 的浅色结果
     * @param darkLadder  {@link #deriveTextLadder(String, boolean)} 的深色结果
     */
    public static String mapTextLadder(String greyHex, String[] lightLadder, String[] darkLadder) {
        int g = parseHex(greyHex);
        if (g < 0 || !isTextLadderFamily(g)) {
            return greyHex;
        }
        double l = toHsl(g)[2];
        if (l < TEXT_LADDER_SPLIT) {
            return lightLadder == null ? greyHex : lightLadder[nearestAnchor(l, LIGHT_ANCHORS)];
        }
        return darkLadder == null ? greyHex : darkLadder[nearestAnchor(l, DARK_ANCHORS)];
    }

    /** 明度落在哪一级：返回离它最近的锚点下标（0=主 1=正文 2=次要）。 */
    private static int nearestAnchor(double l, double[] anchors) {
        int best = 0;
        double bestGap = Double.MAX_VALUE;
        for (int i = 0; i < anchors.length; i++) {
            double gap = Math.abs(l - anchors[i]);
            if (gap < bestGap) {
                bestGap = gap;
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ 对比度

    /** WCAG 相对亮度（0=纯黑，1=纯白）。 */
    public static double relativeLuminance(int rgb) {
        double r = linear(((rgb >> 16) & 0xff));
        double g = linear(((rgb >> 8) & 0xff));
        double b = linear((rgb & 0xff));
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** 两色的 WCAG 对比度（1:1 ~ 21:1）。 */
    public static double contrastRatio(int a, int b) {
        double la = relativeLuminance(a);
        double lb = relativeLuminance(b);
        double hi = Math.max(la, lb);
        double lo = Math.min(la, lb);
        return (hi + 0.05) / (lo + 0.05);
    }

    /**
     * 对比度兜底：不够就沿着明度梯度挪，每次 1%，最多 100 步。
     *
     * @param lighten true = 往亮里挪（深色主题），false = 往暗里挪（浅色主题）
     */
    public static int ensureContrast(int color, int background, double minRatio, boolean lighten) {
        double[] hsl = toHsl(color);
        double l = hsl[2];
        int candidate = color;
        for (int step = 0; step <= 100; step++) {
            candidate = fromHsl(hsl[0], hsl[1], l);
            if (contrastRatio(candidate, background) >= minRatio) {
                return candidate;
            }
            l += lighten ? 0.01 : -0.01;
            if (l <= 0 || l >= 1) {
                break;
            }
        }
        return candidate;
    }

    // ------------------------------------------------------------------ 内部

    private static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double clamp01(double v) {
        if (v < 0) {
            return 0;
        }
        return Math.min(v, 1.0);
    }

    private static int clamp255(int v) {
        if (v < 0) {
            return 0;
        }
        return Math.min(v, 255);
    }

    /** QA 用配色样板：GBL 现网最常用的 12 阶绿（改动 theme.css 绿阶时同步这份清单）。 */
    public static final String[] GREEN_LADDER = {
            "#17351f", "#2f6b41", "#237a44", "#2f9a56", "#37a85a", "#19b36b",
            "#3cb46b", "#6fd18f", "#7ee2a0", "#a9dcbc", "#d8efdf", "#e7f6ec"};
}
