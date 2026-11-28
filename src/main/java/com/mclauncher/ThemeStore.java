package com.mclauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 1.3.3 自定义外观的持久化（背景 / 壁纸 / 文字色 / 主题色）。
 *
 * <p><b>为什么不塞进 config.json：</b>那条铁律是"只读现有 → 改一项 → 写回"，
 * 而 {@link SettingsStore#save()} 是整份重建（它持有全字段）。两套代码同时往同一个文件写，
 * 谁的字段谁负责，跨文件混写一定会丢字段。所以外观单独落在
 * {@code ~/.gbl/theme.json}，一个文件一件事，读写互不干扰。
 *
 * <p>写入用"临时文件 + 改名"，读取全程宽容：缺字段用默认、认不出的枚举回默认、
 * 坏 JSON 整份降级并把 {@link #corrupted()} 置 true 给界面提示。**出厂默认 = 与 1.3.2 完全一致**：
 * 没有 theme.json 的机器读出来就是浅色背景 + 出厂绿 + 默认文字色，界面一个像素都不变。
 *
 * <p>不可变：改一项走 {@code withXxx} 拿新实例。
 */
public final class ThemeStore {

    /** 存储格式版本。 */
    public static final int SCHEMA_VERSION = 1;

    /** 背景三选一。DARK / LIGHT 是"纯色背景"，WALLPAPER 是"自定义壁纸"。 */
    public enum BackgroundMode {
        /** 深色纯色背景。 */
        DARK,
        /** 浅色纯色背景（出厂）。 */
        LIGHT,
        /** 自定义壁纸 + 固定蒙版。 */
        WALLPAPER
    }

    private static final Path DIR = Paths.get(System.getProperty("user.home"), ".gbl");
    private static final Path FILE = DIR.resolve("theme.json");
    /** 导入的壁纸复制到这里，**绝不引用微信/浏览器的临时路径**（那种路径过几天就没了）。 */
    private static final Path WALLPAPER_DIR = DIR.resolve("wallpapers");

    private final BackgroundMode background;
    /** 壁纸绝对路径；null = 没导入过。 */
    private final String wallpaperPath;
    /** 用户选的主题色；null = 从没选过。 */
    private final String accentColor;
    /** 用户选的文字色；null = 从没选过。 */
    private final String textColor;
    /** 主题色自定义开关（关掉 = 用出厂绿，但记住上次选的颜色，方便再打开）。 */
    private final boolean customAccent;
    /** 文字色自定义开关。 */
    private final boolean customText;
    /** 上一份文件读坏了。 */
    private final boolean corrupted;

    private ThemeStore(BackgroundMode background, String wallpaperPath, String accentColor,
                       String textColor, boolean customAccent, boolean customText,
                       boolean corrupted) {
        this.background = background == null ? BackgroundMode.LIGHT : background;
        this.wallpaperPath = (wallpaperPath == null || wallpaperPath.isBlank())
                ? null : wallpaperPath.trim();
        this.accentColor = ThemeColor.normalize(accentColor);
        this.textColor = ThemeColor.normalize(textColor);
        this.customAccent = customAccent && this.accentColor != null;
        this.customText = customText && this.textColor != null;
        this.corrupted = corrupted;
    }

    /** 出厂主题色（深绿）。界面「恢复默认」与调色面板的起点都用它。 */
    public static String defaultAccentHex() {
        return ThemeColor.DEFAULT_ACCENT;
    }

    /** 出厂状态：浅色背景、无壁纸、出厂绿、默认文字色——与 1.3.2 的观感逐像素一致。 */
    public static ThemeStore defaults() {
        return new ThemeStore(BackgroundMode.LIGHT, null, null, null, false, false, false);
    }

    /**
     * 读 {@code ~/.gbl/theme.json}。文件不存在是正常路径（第一次跑），返回默认值且不标 corrupted。
     */
    public static ThemeStore load() {
        Map<String, Object> map = readObject();
        if (map == null) {
            return new ThemeStore(BackgroundMode.LIGHT, null, null, null, false, false, true);
        }
        return new ThemeStore(
                parseBackground(map.get("background")),
                parseString(map, "wallpaperPath"),
                parseString(map, "accentColor"),
                parseString(map, "textColor"),
                parseBoolean(map, "customAccent"),
                parseBoolean(map, "customText"),
                false);
    }

    // ------------------------------------------------------------------ 取值

    public BackgroundMode background() {
        return background;
    }

    public String wallpaperPath() {
        return wallpaperPath;
    }

    /** 用户上次选的主题色（可能是关掉状态下的记忆值）；没选过为 null。 */
    public String accentColor() {
        return accentColor;
    }

    public String textColor() {
        return textColor;
    }

    public boolean customAccent() {
        return customAccent;
    }

    public boolean customText() {
        return customText;
    }

    public boolean corrupted() {
        return corrupted;
    }

    /** 当前真正生效的主题色（关掉自定义时回出厂绿）。 */
    public String effectiveAccent() {
        return customAccent && accentColor != null ? accentColor : ThemeColor.DEFAULT_ACCENT;
    }

    /** 当前真正生效的文字色；没自定义时 null（= 用 theme.css 原色，不做任何覆盖）。 */
    public String effectiveText() {
        return customText && textColor != null ? textColor : null;
    }

    /**
     * 壁纸是否真的可用：选了壁纸模式、存了路径、而且那个文件还在。
     * 用户自己删了壁纸文件时靠这条自动退回纯色背景，不至于给一个"半透明空窗口"。
     */
    public boolean wallpaperUsable() {
        if (background != BackgroundMode.WALLPAPER || wallpaperPath == null) {
            return false;
        }
        try {
            return Files.isRegularFile(Paths.get(wallpaperPath));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** 是否需要注入运行时样式表（两色都没自定义 = 不注入，出厂状态零风险）。 */
    public boolean needsRuntimeCss() {
        return (customAccent && accentColor != null) || (customText && textColor != null);
    }

    // ------------------------------------------------------------------ 改一项

    public ThemeStore withBackground(BackgroundMode mode) {
        BackgroundMode want = mode == null ? BackgroundMode.LIGHT : mode;
        return want == background ? this : copy(want, wallpaperPath, accentColor, textColor,
                customAccent, customText);
    }

    /** 记录壁纸路径（导入后调用）。传 null = 清空路径。 */
    public ThemeStore withWallpaperPath(String path) {
        String want = (path == null || path.isBlank()) ? null : path.trim();
        if (java.util.Objects.equals(want, wallpaperPath)) {
            return this;
        }
        return copy(background, want, accentColor, textColor, customAccent, customText);
    }

    /**
     * 换主题色。传 null 或与出厂绿相同的值 = 关掉自定义（恢复默认绿色），
     * 但**记住**上次选的色，下次再打开直接回来。
     */
    public ThemeStore withAccent(String hex) {
        String want = ThemeColor.normalize(hex);
        if (want == null) {
            return copy(background, wallpaperPath, accentColor, textColor, false, customText);
        }
        boolean custom = !want.equals(ThemeColor.DEFAULT_ACCENT);
        return copy(background, wallpaperPath, want, textColor, custom, customText);
    }

    /** 换文字色。传 null = 关掉自定义（回到 theme.css 原色）。 */
    public ThemeStore withText(String hex) {
        String want = ThemeColor.normalize(hex);
        if (want == null) {
            return copy(background, wallpaperPath, accentColor, textColor, customAccent, false);
        }
        return copy(background, wallpaperPath, accentColor, want, customAccent, true);
    }

    /**
     * 「恢复默认」：背景回浅色、主题色回出厂绿、文字色回默认。
     *
     * <p>**保留 wallpaperPath**：文件还在磁盘上，用户想再开壁纸不必重新导入；
     * 而且本项目规矩是零删除，这里也不去删它。
     */
    public ThemeStore resetAppearance() {
        return copy(BackgroundMode.LIGHT, wallpaperPath, null, null, false, false);
    }

    private ThemeStore copy(BackgroundMode bg, String wall, String accent, String text,
                            boolean customA, boolean customT) {
        return new ThemeStore(bg, wall, accent, text, customA, customT, false);
    }

    // ------------------------------------------------------------------ 壁纸导入

    /**
     * 把用户选的图片复制到 {@code ~/.gbl/wallpapers/} 并返回带新路径的实例。
     *
     * <p>复制而不是记住原路径：用户从微信/QQ 的临时目录里选图，那个目录过几天就被清了，
     * 记原路径等于下次启动壁纸变白。
     *
     * <p>文件名保持稳定（{@code wallpaper.<ext>}）+ 覆盖写：同一次会话里连着换三张图，
     * 目录里也只留一张，不会越攒越多。
     */
    public ThemeStore importWallpaper(Path source) throws IOException {
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("图片文件不存在: " + source);
        }
        Files.createDirectories(WALLPAPER_DIR);
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 && dot < name.length() - 1
                ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "png";
        Path target = WALLPAPER_DIR.resolve("wallpaper." + ext);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return copy(BackgroundMode.WALLPAPER, target.toAbsolutePath().toString(), accentColor,
                textColor, customAccent, customText);
    }

    /** 壁纸目录（给界面显示"已保存到哪"用）。 */
    public static Path wallpaperDir() {
        return WALLPAPER_DIR;
    }

    // ------------------------------------------------------------------ 写盘

    /** 写回磁盘；失败返回 false，由界面提示"保存失败"，绝不影响界面本身。 */
    public boolean save() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", SCHEMA_VERSION);
        map.put("background", background.name());
        map.put("wallpaperPath", wallpaperPath);
        map.put("accentColor", accentColor);
        map.put("textColor", textColor);
        map.put("customAccent", customAccent);
        map.put("customText", customText);
        String text = Json.write(map);
        try {
            Files.createDirectories(DIR);
        } catch (IOException | RuntimeException ex) {
            System.err.println("[theme] 建目录失败: " + ex);
            return false;
        }
        Path tmp = DIR.resolve(FILE.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, FILE, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException fallback) {
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException ex) {
            System.err.println("[theme] 保存失败: " + ex);
            return false;
        }
    }

    // ------------------------------------------------------------------ 解析

    private static BackgroundMode parseBackground(Object raw) {
        if (raw instanceof String text) {
            try {
                return BackgroundMode.valueOf(text.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 认不出来的值当浅色：比"界面回不到已知状态"安全
            }
        }
        return BackgroundMode.LIGHT;
    }

    private static String parseString(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (!(raw instanceof String text)) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean parseBoolean(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        return raw instanceof Boolean value && value;
    }

    private static Map<String, Object> readObject() {
        String text;
        try {
            if (!Files.isRegularFile(FILE)) {
                return new LinkedHashMap<>();
            }
            text = Files.readString(FILE, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            System.err.println("[theme] 读取失败，按默认值兜: " + ex);
            return null;
        }
        try {
            if (Json.parse(text) instanceof Map<?, ?> parsed) {
                Map<String, Object> map = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : parsed.entrySet()) {
                    map.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return map;
            }
            return null;
        } catch (RuntimeException ex) {
            System.err.println("[theme] theme.json 不是合法 JSON，按默认值兜: " + ex);
            return null;
        }
    }

    // ------------------------------------------------------------------ QA 钩子
    // 下面几个全是静态纯函数：不开 JavaFX Toolkit、不碰界面、不读磁盘，QA 直接断言即可。

    /**
     * 横屏判定：宽必须严格大于高。正方形（宽 == 高）与竖屏一律拒绝，
     * 因为"盖满窗口"的铺法会把竖图放大到看不见主体。
     */
    public static boolean isLandscapeForTests(double width, double height) {
        return width > 0 && height > 0 && width > height;
    }

    /**
     * QA：把一个主题色应用到底色板上，返回 12 阶映射结果（顺序同
     * {@link ThemeColor#GREEN_LADDER}）。
     *
     * @param baseHex 用户选的主题色
     * @param dark    深色主题（当前实现下不影响结果，保留签名对齐需求）
     */
    public static List<String> mapGreenFamilyForTests(String baseHex, boolean dark) {
        List<String> out = new ArrayList<>(ThemeColor.GREEN_LADDER.length);
        for (String green : ThemeColor.GREEN_LADDER) {
            out.add(ThemeColor.mapAccent(green, baseHex, dark));
        }
        return out;
    }

    /** QA：单阶绿映射。 */
    public static String mapGreenForTests(String greenHex, String baseHex, boolean dark) {
        return ThemeColor.mapAccent(greenHex, baseHex, dark);
    }

    /**
     * QA：三级文字色派生结果 {@code [主, 正文, 次要]}。
     * 断言点：三级明度严格递减（浅色）/ 递增（深色），且每级与主题底色的对比度达标。
     */
    public static String[] deriveTextColorsForTests(String hex, boolean dark) {
        return ThemeColor.deriveTextLadder(hex, dark);
    }

    /**
     * QA：整份样式表映射（绿色族 + 文字填色）。返回映射后的 CSS 文本，可断言
     * "旧绿一个不剩""语义红还在""浅色/深色两套都跟着变"。
     */
    public static String mapThemeCssForTests(String css, String accentHex, String textHex) {
        return ThemeCss.map(css, accentHex, textHex);
    }

    /** QA：解析用户输入的 HEX（容忍不带 #、大小写），非法返回 null。 */
    public static String parseColorForTests(String input) {
        return ThemeColor.normalize(input);
    }
}
