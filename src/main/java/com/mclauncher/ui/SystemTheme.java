package com.mclauncher.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * 「跟随系统」到底跟随到哪：读系统当前的深色开关。
 *
 * <p>JavaFX 自己不提供系统深浅色探测，只能各平台各查各的——查不到就把系统当浅色，
 * 不能因为探测失败把整个主题模式卡住（等价于「跟随系统 = 浅色」）。
 *
 * <p>三端都接了，但只有 Windows 那条是实打实的注册表查询；macOS 走 {@code defaults}、
 * Linux 走 GTK 的 {@code settings.ini}。以后换平台就在这里补一条。
 */
public final class SystemTheme {

    /**
     * 调试开关 {@code -Dgbl.theme=light|dark}：直接钉死深浅，跳过系统探测。
     * 两个用处：自测要出「浅色 / 深色」两张图时不用去改系统；受限环境（沙箱、CI）
     * 起不了 reg / defaults 也能照常跑起来，而不是卡在探测上。
     */
    private static final String PROP = "gbl.theme";

    /** 探测结果按「一次探测 + 后续复用」缓存：真去问一次系统要几十毫秒，别重进窗口就问一遍。 */
    private static Boolean cached;
    private static Boolean forced;

    private SystemTheme() {
    }

    public static boolean isDark() {
        if (forced == null) {
            forced = forcedFromProperty();
        }
        if (forced != null) {
            return forced;
        }
        if (cached != null) {
            return cached;
        }
        cached = probe();
        return cached;
    }

    /** 有没有带 {@code -Dgbl.theme}（和 {@link #isDark} 分开问，是因为调用方要判断「要不要盖过配置」）。 */
    public static boolean isForced() {
        return forcedFromProperty() != null;
    }

    private static Boolean forcedFromProperty() {
        String value = System.getProperty(PROP);
        if (value == null) {
            return null;
        }
        if ("light".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        if ("dark".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        return null;
    }

    /** 窗口重新拿到焦点时清缓存，才能跟上「用户中途把系统切成深色」。 */
    public static void refresh() {
        cached = null;
        forced = null;
    }

    private static boolean probe() {
        try {
            return read();
        } catch (Exception ex) {
            // 探测失败按浅色兜，而且只静默一次：沙箱 / 受限环境里 reg 起不来是常态，不该刷屏
            System.err.println("[theme] 系统主题探测失败，按浅色兜: " + ex);
            return false;
        }
    }

    private static boolean read() throws Exception {
        String os = String.valueOf(System.getProperty("os.name", "")).toLowerCase();
        if (os.contains("win")) {
            return windowsDark();
        }
        if (os.contains("mac")) {
            return macosDark();
        }
        return linuxDark();
    }

    /**
     * Win10/11 的深色开关在
     * {@code HKCU\...\Themes\Personalize} 的 {@code AppsUseLightTheme}：0 = 深色、1 = 浅色。
     * 用 {@code reg query} 而不是再引个注册表库，也不去碰 JNI。
     */
    private static boolean windowsDark() throws Exception {
        Process process = new ProcessBuilder("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme")
                .redirectErrorStream(true)
                .start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return false;
        }
        for (String line : out.split("\\R")) {
            if (!line.contains("AppsUseLightTheme")) {
                continue;
            }
            String[] fields = line.trim().split("\\s+");
            // 形如「AppsUseLightTheme    REG_DWORD    0x0」，值取最后一个字段
            String value = fields[fields.length - 1];
            return "0".equals(value) || "0x0".equalsIgnoreCase(value);
        }
        return false;
    }

    /** macOS：{@code defaults -g AppleInterfaceStyle} 输出「Dark」时是深色（键不存在即浅色）。 */
    private static boolean macosDark() throws Exception {
        Process process = new ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                .redirectErrorStream(true)
                .start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return false;
        }
        return out.contains("Dark");
    }

    /** Linux：GTK 主题名里带 dark 就是深色（环境变量优先，其次配置文件）。 */
    private static boolean linuxDark() throws java.io.IOException {
        String env = System.getenv("GTK_THEME");
        if (env != null && env.toLowerCase().contains("dark")) {
            return true;
        }
        Path ini = Paths.get(System.getProperty("user.home"), ".config", "gtk-3.0", "settings.ini");
        if (!Files.isRegularFile(ini)) {
            return false;
        }
        for (String line : Files.readAllLines(ini)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("gtk-theme-name=")) {
                return trimmed.substring(15).toLowerCase().contains("dark");
            }
        }
        return false;
    }
}
