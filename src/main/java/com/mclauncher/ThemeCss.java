package com.mclauncher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.3.3 运行时样式表生成器。
 *
 * <p>GBL 的主题色/文字色不是 CSS 变量（JavaFX 不支持 {@code var()}），而是散落在 theme.css 里的
 * 300 多个色值字面量。要"换掉所有绿"最稳的办法不是给每个选择器补一条覆盖规则
 * （311 处 {@code -fx-text-fill}，补漏一处就是一处颜色没跟上），而是：
 * <b>把 theme.css 整份读进来、按值替换、生成一份新样式表，追加到原样式表之后</b>。
 * 后挂的同名规则天然覆盖前面的，两套主题（浅色默认 + {@code .dark} 后代覆盖）都原样保留，
 * 连 {@code .dark} 分支都跟着一起变——不会出现"只改了深色、浅色白字"。
 *
 * <p>替换分两步，互不干扰：
 * <ol>
 *   <li>绿色族：全文件替换（所有绿色都要换，包括渐变里的、阴影里的）。</li>
 *   <li>文字蓝灰阶：<b>只替换 {@code -fx-text-fill} / {@code -fx-fill} 后面的值</b>，
 *       同一个灰当背景/描边用时不受影响。</li>
 * </ol>
 *
 * <p>两步都<b>不改变选择器与结构</b>，所以生成的样式表在语法层面和原表一样，
 * 即便某条没解析成功也只是少一条覆盖，不会把界面搞坏。
 */
public final class ThemeCss {

    /** 六位色值，允许带两位 alpha（{@code #237a4480} 这种），替换时 alpha 原样保留。 */
    private static final Pattern HEX = Pattern.compile(
            "#([0-9a-fA-F]{6})([0-9a-fA-F]{2})?(?![0-9a-fA-F])");

    /** 只命中"填色属性"的位置：{@code -fx-fill:} 与 {@code -fx-text-fill:}。 */
    private static final Pattern FILL_POSITION = Pattern.compile(
            "(-fx-(?:text-)?fill\\s*:\\s*)#([0-9a-fA-F]{6})([0-9a-fA-F]{2})?(?![0-9a-fA-F])");

    /** 生成文件落在 {@code ~/.gbl/theme-runtime/}，不进项目目录、不进安装目录。 */
    private static final Path RUNTIME_DIR =
            Paths.get(System.getProperty("user.home"), ".gbl", "theme-runtime");

    /** 原样式表只读一次（114 KB，每次调色盘拖动都读一遍没必要）。 */
    private static volatile String baseCssCache;

    /** 弹层深色样式表同样要跟着映射（下拉里「选中项」的绿底绿字就在里面）。 */
    private static volatile String popupCssCache;

    private ThemeCss() {
        // 静态工具类
    }

    /** 读 {@code popup-dark.css}（ComboBox 弹层专用）；读不到返回空串。 */
    public static String popupDarkCss() {
        String cached = popupCssCache;
        if (cached != null) {
            return cached;
        }
        String text = readResource("/com/mclauncher/popup-dark.css");
        popupCssCache = text;
        return text;
    }

    /** 读一个 CSS 资源；读不到返回空串（调用方跳过注入，绝不因此中断流程）。 */
    private static String readResource(String path) {
        try (InputStream in = ThemeCss.class.getResourceAsStream(path)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            System.err.println("[theme] 读 " + path + " 失败，跳过注入: " + ex);
            return "";
        }
    }

    /**
     * 读原始 theme.css。读不到返回空串——调用方据此跳过注入，界面退回出厂配色，
     * 绝不能因为读不到样式表把启动流程卡住。
     */
    public static String baseCss() {
        String cached = baseCssCache;
        if (cached != null) {
            return cached;
        }
        String text = readResource("/com/mclauncher/theme.css");
        baseCssCache = text;
        return text;
    }

    /**
     * 按用户选择生成映射后的整份样式表。
     *
     * @param css       原始样式表文本
     * @param accentHex 主题色；null / 等于出厂绿 → 不做绿色替换（出厂状态必须与旧版逐字节一致）
     * @param textHex   文字色；null → 不做文字替换
     * @return 映射后的样式表；两个都为空时原样返回 {@code css}
     */
    public static String map(String css, String accentHex, String textHex) {
        if (css == null || css.isEmpty()) {
            return "";
        }
        String out = css;
        String accent = ThemeColor.normalize(accentHex);
        if (accent != null && !accent.equals(ThemeColor.DEFAULT_ACCENT)) {
            out = mapGreens(out, accent);
        }
        String text = ThemeColor.normalize(textHex);
        if (text != null) {
            String[] lightLadder = ThemeColor.deriveTextLadder(text, false);
            String[] darkLadder = ThemeColor.deriveTextLadder(text, true);
            if (lightLadder != null && darkLadder != null) {
                out = mapTextFills(out, lightLadder, darkLadder);
            }
        }
        return out;
    }

    /** 第一步：全文件把绿族换成新色相。 */
    public static String mapGreens(String css, String accentHex) {
        Matcher m = HEX.matcher(css);
        StringBuilder sb = new StringBuilder(css.length() + 1024);
        while (m.find()) {
            String body = m.group(1);
            String alpha = m.group(2) == null ? "" : m.group(2);
            String mapped = ThemeColor.mapAccent("#" + body, accentHex, false);
            // 只有真的变了才重写，没变时保留原始大小写，减少 diff 噪音
            String replacement = mapped == null || mapped.equalsIgnoreCase("#" + body)
                    ? m.group(0)
                    : mapped + alpha;
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 第二步：只把"填色位置"的文字蓝灰换成派生三级色。 */
    public static String mapTextFills(String css, String[] lightLadder, String[] darkLadder) {
        Matcher m = FILL_POSITION.matcher(css);
        StringBuilder sb = new StringBuilder(css.length() + 1024);
        while (m.find()) {
            String prefix = m.group(1);
            String body = m.group(2);
            String alpha = m.group(3) == null ? "" : m.group(3);
            String mapped = ThemeColor.mapTextLadder("#" + body, lightLadder, darkLadder);
            String replacement = mapped == null || mapped.equalsIgnoreCase("#" + body)
                    ? m.group(0)
                    : prefix + mapped + alpha;
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 把生成的样式表落盘，返回它的 file URI。
     *
     * <p>为什么每次换一个新的文件名：JavaFX 按 URL 缓存已解析的样式表，同一个 URL 重新
     * {@code add} 回去拿到的是旧缓存（改了颜色界面不跟着变）。换文件名是最省事又不会失手的办法；
     * 文件登记了 {@code deleteOnExit}，进程正常退出时自己清掉，不需要谁去删目录。
     */
    public static String writeRuntimeCss(String css) {
        try {
            Files.createDirectories(RUNTIME_DIR);
            Path file = Files.createTempFile(RUNTIME_DIR, "theme-", ".css");
            Files.writeString(file, css, StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
            return file.toUri().toString();
        } catch (IOException | RuntimeException ex) {
            System.err.println("[theme] 写运行时样式表失败，本次不注入: " + ex);
            return null;
        }
    }
}
