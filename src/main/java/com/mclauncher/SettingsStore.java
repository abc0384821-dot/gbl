package com.mclauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 设置持久化（v0.2 第一批字段：外观 + 窗口行为）。
 *
 * <p>配置落在 {@code ~/.gbl/config.json}，带 {@code schemaVersion}。读取按「宽容」处理：
 * <ul>
 *   <li>缺字段 → 用默认值（以后加设置不会让老配置报错）</li>
 *   <li>未知字段 → 忽略（老版本读到新配置不会崩）</li>
 *   <li>坏 JSON → 整份降级为默认值，并把 {@link #configCorrupted()} 置 true 给界面提示</li>
 * </ul>
 *
 * <p>不引第三方 JSON 依赖：这批字段全是标量，自带的极简读写（内部 Json）够用，
 * 等要塞链表（下载源、模组列表）时再换正式解析器。
 *
 * <p>不可变：改一项走 {@code withXxx} 拿新实例，界面持有的是当前那份。
 */
public final class SettingsStore {

    /** 配置格式版本。以后字段结构变了 +1，识别不出来就当默认值兜底。 */
    public static final int SCHEMA_VERSION = 1;

    /**
     * 一次写盘最多试几遍。连点开关时 Windows 偶发抢写，重试吸收掉，
     * 不该让顶栏胶囊为此翻红——「已保存」变红是要真保存失败才该有的反馈。
     */

    /** 设置所在的目录与文件名。 */
    private static final Path FILE =
            Paths.get(System.getProperty("user.home"), ".gbl", "config.json");

    /** 主题模式。「跟随系统」会把系统深浅色翻译成实际生效的那套 token。 */
    public enum ThemeMode { SYSTEM, LIGHT, DARK }

    /**
     * 下载源（v0.7.0）：官方直连或 BMCLAPI 国内镜像。
     *
     * <p>只作用于 Mojang 那几个域名（{@link Mirror} 规则表），Modrinth 与加载器 meta
     * 不走镜像。缺省 {@link #OFFICIAL}——老配置没有这个键时宽容读取消，不报错。
     */
    public enum DownloadSource {
        OFFICIAL("官方"),
        BMCLAPI("BMCLAPI 镜像");

        private final String label;

        DownloadSource(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final ThemeMode theme;
    private final boolean reduceMotion;
    private final boolean closeToTray;
    /** 游戏列表里当前选中的那个游戏（文件夹 id）；null 表示没选。 */
    private final String currentGame;
    /** 下载源。默认官方；切到 BMCLAPI 后 Mojang 域名走镜像、失败回落官方（见 {@link Mirror}）。 */
    private final DownloadSource downloadSource;
    /**
     * CurseForge API Key（用户可在设置页粘贴）。
     * 优先级低于本地 {@code ~/.gbl/curseforge.key} 文件、高于 {@link CurseForgeClient#DEFAULT_API_KEY} 内置兜底——
     * 让普通用户不必改文件也能用自己的 key（0.8.5）。
     */
    private final String cfApiKey;
    /**
     * 手动指定的 Java 可执行文件路径（0.8.6）。空串 = 没指定，走自动探测。
     *
     * <p>存进来只为了 {@code GameLauncher.javaFor} 有个落点：干净机器上探不到系统 Java 时，
     * 那句「去设置里手动指定一个 Java 路径」以前指向一个不存在的设置项。
     * 本轮只落字段，设置页输入框由下一轮串行补（ShellView 本轮不可改）。
     */
    private final String javaPath;
    /** 上一次读取时配置不是合法 JSON（已按默认值兜住，界面上给一句提示）。 */
    private final boolean configCorrupted;

    /** 开机自启（0.9.6）：true 时写 HKCU Run 键，登录后静默挂托盘。默认关。 */
    private final boolean launchOnBoot;
    /** 自动更新（0.9.6）：开则启动后异步查一次更新；关则完全不查。默认开。 */
    private final boolean autoUpdate;
    /** 分配给游戏的最大内存（MB）（0.9.6）。默认 2048。 */
    private final int gameMemoryMb;
    /** 同时下载的最大并发数（0.9.6）。默认 4。 */
    private final int maxConcurrentDownloads;
    /** 启动游戏后是否关闭启动器（0.9.6）。默认关。 */
    private final boolean closeAfterLaunch;
    /**
     * 按钮特效（1.0.7）：true 时点按钮播 HMCL 式涟漪。默认开。
     *
     * <p>与 {@link #reduceMotion} 正交：那个只管切页淡入淡出，这个只管按钮涟漪。
     * 关掉它不影响别的动效，两边开关互不干涉。
     */
    private final boolean buttonEffects;
    /**
     * 自动下载缺失的 Java（1.1.2）：开启时，游戏需要但本机没有精确主版本 Java 会自动下载安装；
     * 关闭时退回旧的「用更高版本凑合」行为。老配置缺键默认开，默认 true。
     */
    private final boolean autoDownloadJava;

    /** {@link #get()} 缓存的当前配置实例（懒加载）。 */
    private static volatile SettingsStore instance;

    private SettingsStore(ThemeMode theme, boolean reduceMotion, boolean closeToTray,
                          String currentGame, DownloadSource downloadSource,
                          String cfApiKey, String javaPath, boolean configCorrupted,
                          boolean launchOnBoot, boolean autoUpdate, int gameMemoryMb,
                          int maxConcurrentDownloads, boolean closeAfterLaunch,
                          boolean buttonEffects, boolean autoDownloadJava) {
        this.theme = theme;
        this.reduceMotion = reduceMotion;
        this.closeToTray = closeToTray;
        this.currentGame = currentGame;
        this.downloadSource = downloadSource;
        this.cfApiKey = cfApiKey;
        this.javaPath = javaPath == null ? "" : javaPath;
        this.configCorrupted = configCorrupted;
        this.launchOnBoot = launchOnBoot;
        this.autoUpdate = autoUpdate;
        this.gameMemoryMb = gameMemoryMb;
        this.maxConcurrentDownloads = maxConcurrentDownloads;
        this.closeAfterLaunch = closeAfterLaunch;
        this.buttonEffects = buttonEffects;
        this.autoDownloadJava = autoDownloadJava;
    }

    /** 出厂设置：跟随系统 / 有动效 / 按钮特效开 / 关闭即退出 / 没选游戏 / 官方下载源 / 无 CF key / 不指定 Java。 */
    public static SettingsStore defaults() {
        return new SettingsStore(ThemeMode.SYSTEM, false, false, null, DownloadSource.OFFICIAL,
                null, "", false, false, true, 2048, 4, false, true, true);
    }

    /**
     * 单例出口：服务层（没有 SettingsStore 实例）读当前配置用。首次调用落一份并缓存，
     * 之后每次返回同一份——配置变更走 {@code withXxx} + {@code save}，需要时重新 {@link #load()}。
     */
    public static SettingsStore get() {
        SettingsStore current = instance;
        if (current == null) {
            synchronized (SettingsStore.class) {
                current = instance;
                if (current == null) {
                    instance = current = load();
                }
            }
        }
        return current;
    }

    /**
     * 读配置。文件不存在、读不到、解析失败都返回默认值，绝不因为配置把启动器卡在启动阶段——
     * 用户第一次跑本来就没有配置文件，这必须是正常路径。
     */
    public static SettingsStore load() {
        Map<String, Object> map = readObject();
        if (map == null) {
            return new SettingsStore(ThemeMode.SYSTEM, false, false, null,
                    DownloadSource.OFFICIAL, null, "", true, false, true, 2048, 4, false, true, true);
        }
        return new SettingsStore(
                parseTheme(map.get("theme")),
                parseBool(map, "reduceMotion"),
                parseBool(map, "closeToTray"),
                parseString(map, "currentGame"),
                parseSource(map.get("downloadSource")),
                parseString(map, "cfApiKey"),
                parseJavaPath(map.get("javaPath")),
                false,
                parseBool(map, "launchOnBoot"),
                parseBoolDefaultTrue(map, "autoUpdate"),   // 0.9.9 r6：老配置缺键默认开
                parseInt(map, "gameMemoryMb", 2048),
                parseInt(map, "maxConcurrentDownloads", 4),
                parseBool(map, "closeAfterLaunch"),
                parseBoolDefaultTrue(map, "buttonEffects"),   // 1.0.7：老配置缺键默认开
                parseBoolDefaultTrue(map, "autoDownloadJava"));   // 1.1.2：老配置缺键默认开
    }

    public ThemeMode theme() {
        return theme;
    }

    /** 切页淡入淡出这类动效的开关（无障碍：系统里开「减少动态效果」就该跟着关）。 */
    public boolean reduceMotion() {
        return reduceMotion;
    }

    public boolean closeToTray() {
        return closeToTray;
    }

    /** 开机自启：true 时登录后静默挂托盘。 */
    public boolean isLaunchOnBoot() {
        return launchOnBoot;
    }

    /** 自动更新总开关：true 时启动后异步查一次更新。 */
    public boolean isAutoUpdate() {
        return autoUpdate;
    }

    /** 分配给游戏的最大内存（MB）。 */
    public int gameMemoryMb() {
        return gameMemoryMb;
    }

    /** 同时下载的最大并发数。 */
    public int maxConcurrentDownloads() {
        return maxConcurrentDownloads;
    }

    /** 启动游戏后是否关闭启动器。 */
    public boolean isCloseAfterLaunch() {
        return closeAfterLaunch;
    }

    /**
     * 按钮特效（1.0.7）：true 时点按钮播涟漪动画。
     *
     * <p>与 {@link #reduceMotion()} 正交：这个只管按钮涟漪，那个只管切页淡入淡出。
     */
    public boolean buttonEffects() {
        return buttonEffects;
    }

    /**
     * 游戏列表里当前选中的游戏 id（文件夹名），没选或那个游戏已经不在了时为 null。
     *
     * <p>不在这里判断「那个目录还在不在」：配置该是配置，磁盘状态该由扫描结果决定，
     * 界面拿到两次结果对一对就行，别让持久化层去碰文件系统。
     */
    public String currentGame() {
        return currentGame;
    }

    /** 下载源：官方直连或 BMCLAPI 镜像。 */
    public DownloadSource downloadSource() {
        return downloadSource;
    }

    /** CurseForge API Key（设置页粘贴的）；null/空表示没配，由调用方回落到内置兜底 key。 */
    public String cfApiKey() {
        return cfApiKey;
    }

    /**
     * 手动指定的 Java 可执行文件路径；**永远不为 null**，没指定就是空串。
     * {@code GameLauncher.javaFor} 拿它当最高优先级候选。
     */
    public String javaPath() {
        return javaPath;
    }

    /**
     * 静态便利出口：服务层（没有 SettingsStore 实例）取手动指定的 Java 路径。
     *
     * <p>task 里写的 {@code SettingsStore.javaPath()} 与本类的实例 getter 同名会冲突
     * （Java 不允许同签名的静态 + 实例方法），故静态的那个改名为
     * {@code configuredJavaPath()}；实例 getter 仍严格叫 {@code javaPath()}。
     * 每次启动读一次配置，宽容读取天然兼容老 config。
     */
    public static String configuredJavaPath() {
        try {
            return load().javaPath();
        } catch (RuntimeException ex) {
            System.err.println("[settings] 读 javaPath 失败，按未指定处理: " + ex);
            return "";
        }
    }

    public boolean configCorrupted() {
        return configCorrupted;
    }

    /** 自动下载缺失 Java 的开关（1.1.2）：默认开，老配置缺键也按开处理。 */
    public boolean isAutoDownloadJava() {
        return autoDownloadJava;
    }

    /**
     * 静态便利出口：服务层（没有 SettingsStore 实例）读「要不要自动下载 Java」。
     * 跟 {@link #configuredJavaPath()} 一样每次现读配置，宽容兼容老 config.json。
     */
    public static boolean autoDownloadEnabled() {
        try {
            return load().autoDownloadJava;
        } catch (RuntimeException ex) {
            System.err.println("[settings] 读 autoDownloadJava 失败，按开启处理: " + ex);
            return true;
        }
    }

    /** 换自动下载 Java 开关（1.1.2）。同值不换新实例。 */
    public SettingsStore withAutoDownloadJava(boolean on) {
        return on == autoDownloadJava ? this
                : new SettingsStore(theme, reduceMotion, closeToTray, currentGame, downloadSource,
                        cfApiKey, javaPath, configCorrupted, launchOnBoot, autoUpdate, gameMemoryMb,
                        maxConcurrentDownloads, closeAfterLaunch, buttonEffects, on);
    }

    public SettingsStore withTheme(ThemeMode mode) {
        return new SettingsStore(mode, reduceMotion, closeToTray, currentGame, downloadSource,
                cfApiKey, javaPath, configCorrupted, launchOnBoot, autoUpdate, gameMemoryMb,
                maxConcurrentDownloads, closeAfterLaunch, buttonEffects, autoDownloadJava);
    }

    public SettingsStore withReduceMotion(boolean on) {
        return new SettingsStore(theme, on, closeToTray, currentGame, downloadSource,
                cfApiKey, javaPath, configCorrupted, launchOnBoot, autoUpdate, gameMemoryMb,
                maxConcurrentDownloads, closeAfterLaunch, buttonEffects, autoDownloadJava);
    }

    public SettingsStore withCloseToTray(boolean on) {
        return new SettingsStore(theme, reduceMotion, on, currentGame, downloadSource,
                cfApiKey, javaPath, configCorrupted, launchOnBoot, autoUpdate, gameMemoryMb,
                maxConcurrentDownloads, closeAfterLaunch, buttonEffects, autoDownloadJava);
    }

    /** 换开机自启开关（0.9.6）。 */
    public SettingsStore withLaunchOnBoot(boolean v) {
        return new SettingsStore(theme, reduceMotion, closeToTray, currentGame, downloadSource,
                cfApiKey, javaPath, configCorrupted, v, autoUpdate, gameMemoryMb,
                maxConcurrentDownloads, closeAfterLaunch, buttonEffects, autoDownloadJava);
    }

    /** 换自动更新总开关（0.9.6）。 */
    public SettingsStore withAutoUpdate(boolean v) {
        return new SettingsStore(theme, reduceMotion, closeToTray, currentGame, downloadSource,
                cfApiKey, javaPath, configCorrupted, launchOnBoot, v, gameMemoryMb,
                maxConcurrentDownloads, closeAfterLaunch, buttonEffects, autoDownloadJava);
    }

    /** 换游戏最大内存（MB）（0.9.6）。 */
    public SettingsStore withGameMemoryMb(int v) {
        return new SettingsStore(theme, reduceMotion, closeToTray, currentGame, downloadSource,
                cfApiKey, javaPath, configCorrupted, launchOnBoot, autoUpdate, v,
                maxConcurrentDownloads, closeAfterLaunch, buttonEffects, autoDownloadJava);
    }

    /** 换同时下载最大并发数（0.9.6）。 */
    public SettingsStore withMaxConcurrentDownloads(int v) {
        return new SettingsStore(theme, reduceMotion, closeToTray, currentGame, downloadSource,
                cfApiKey, javaPath, configCorrupted, launchOnBoot, autoUpdate, gameMemoryMb,
                v, closeAfterLaunch, buttonEffects, autoDownloadJava);
    }

    /** 换启动游戏后是否关闭启动器（0.9.6）。 */
    public SettingsStore withCloseAfterLaunch(boolean v) {
        return new SettingsStore(theme, reduceMotion, closeToTray, currentGame, downloadSource,
                cfApiKey, javaPath, configCorrupted, launchOnBoot, autoUpdate, gameMemoryMb,
                maxConcurrentDownloads, v, buttonEffects, autoDownloadJava);
    }

    /**
     * 换按钮特效开关（1.0.7）：true = 点按钮播涟漪。
     *
     * <p>语义与开关一致（不用取反）：这跟 {@link #withReduceMotion} 那条「开关=有动效 /
     * 数据=减少动效」的历史坑不同，这里开关和数据说的是同一件事。
     */
    public SettingsStore withButtonEffects(boolean v) {
        return new SettingsStore(theme, reduceMotion, closeToTray, currentGame, downloadSource,
                cfApiKey, javaPath, configCorrupted, launchOnBoot, autoUpdate, gameMemoryMb,
                maxConcurrentDownloads, closeAfterLaunch, v, autoDownloadJava);
    }

    /** 换 CurseForge API Key（设置页粘贴）。同值不换新实例。 */
    public SettingsStore withCfApiKey(String key) {
        String trimmed = (key == null || key.isBlank()) ? null : key.trim();
        return java.util.Objects.equals(trimmed, cfApiKey) ? this
                : new SettingsStore(theme, reduceMotion, closeToTray, currentGame, downloadSource,
                        trimmed, javaPath, configCorrupted, launchOnBoot, autoUpdate, gameMemoryMb,
                        maxConcurrentDownloads, closeAfterLaunch, buttonEffects, autoDownloadJava);
    }

    /** 换当前游戏。传 null / 空串表示「取消选中」。 */
    public SettingsStore withCurrentGame(String gameId) {
        String trimmed = (gameId == null || gameId.isBlank()) ? null : gameId;
        // 值没变就返回自己：界面拿它去跑「要不要写盘」，同值不写能省掉一次无意义的落盘
        return java.util.Objects.equals(trimmed, currentGame) ? this
                : new SettingsStore(theme, reduceMotion, closeToTray, trimmed, downloadSource,
                        cfApiKey, javaPath, configCorrupted, launchOnBoot, autoUpdate, gameMemoryMb,
                        maxConcurrentDownloads, closeAfterLaunch, buttonEffects, autoDownloadJava);
    }

    /** 换下载源。传 null 按官方兜；同值不换新实例（同 {@link #withCurrentGame} 的口径）。 */
    public SettingsStore withDownloadSource(DownloadSource source) {
        DownloadSource want = source == null ? DownloadSource.OFFICIAL : source;
        return want == downloadSource ? this
                : new SettingsStore(theme, reduceMotion, closeToTray, currentGame, want,
                        cfApiKey, javaPath, configCorrupted, launchOnBoot, autoUpdate, gameMemoryMb,
                        maxConcurrentDownloads, closeAfterLaunch, buttonEffects, autoDownloadJava);
    }

    /**
     * 换手动指定的 Java 路径（0.8.6）。null / 空串 = 清除，回到自动探测；
     * 同值不换新实例。字段先落地，设置页输入框下一轮串行补。
     */
    public SettingsStore withJavaPath(String path) {
        String want = (path == null || path.isBlank()) ? "" : path.trim();
        return java.util.Objects.equals(want, javaPath) ? this
                : new SettingsStore(theme, reduceMotion, closeToTray, currentGame, downloadSource,
                        cfApiKey, want, configCorrupted, launchOnBoot, autoUpdate, gameMemoryMb,
                        maxConcurrentDownloads, closeAfterLaunch, buttonEffects, autoDownloadJava);
    }

    /**
     * 写回磁盘。目录建不出来、盘只读、写了改名失败都返回 false，由界面提示「保存失败」。
     *
     * <p>一次成功就把真因钉住：连点开关导致胶囊变红是界面层类名攒重复，不在这条写盘路径上
     * （见 ShellView.setStatusClass），别往这里加重试兜底。
     */
    public boolean save() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schemaVersion", SCHEMA_VERSION);
        map.put("theme", theme.name());
        map.put("reduceMotion", reduceMotion);
        map.put("closeToTray", closeToTray);
        map.put("currentGame", currentGame);
        map.put("downloadSource", downloadSource.name());
        map.put("cfApiKey", cfApiKey);
        map.put("javaPath", javaPath);
        map.put("launchOnBoot", launchOnBoot);
        map.put("autoUpdate", autoUpdate);
        map.put("gameMemoryMb", gameMemoryMb);
        map.put("maxConcurrentDownloads", maxConcurrentDownloads);
        map.put("closeAfterLaunch", closeAfterLaunch);
        map.put("buttonEffects", buttonEffects);
        map.put("autoDownloadJava", autoDownloadJava);
        String text = Json.write(map);
        Path dir = FILE.toAbsolutePath().getParent();
        try {
            if (dir != null) {
                Files.createDirectories(dir);
            }
        } catch (IOException | RuntimeException ex) {
            System.err.println("[settings] 建配置目录失败: " + ex);
            return false;
        }
        // 写临时文件再整体改名：别用「截断 + 边写」直写目标文件，
        // 那样别的线程（比如下次启动的读配置）可能读到写到一半的半截 JSON。
        Path tmp = dir.resolve(FILE.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, FILE, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException fallback) {
                // 这套文件系统不支持原子改名（或目标还占着）：退回覆盖式改名，
                // 内容是完整写好的再改，同样不会留下半截配置
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException ex) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException | RuntimeException ignored) {
                // 删不掉就留着，下次写同名覆盖
            }
            System.err.println("[settings] 保存失败: " + ex);
            return false;
        }
    }

    // ---------------------------------------------------------------- 解析

    private static ThemeMode parseTheme(Object raw) {
        if (raw instanceof String) {
            try {
                return ThemeMode.valueOf(((String) raw).trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 认不出来的枚举值当 SYSTEM：比「界面上显示不出来」安全
            }
        }
        return ThemeMode.SYSTEM;
    }

    private static boolean parseBool(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        return raw instanceof Boolean ? (Boolean) raw : false;
    }

    /**
     * 开关型字段专用——老配置没有这个键时默认开，
     * 免得老用户升级后静默失去该功能（1.0.7 起服务 {@code autoUpdate} 与 {@code buttonEffects}）。
     * 不改全局 parseBool 默认值，其它设置维持「缺键 false」的既有口径。用户显式写 false 仍被尊重。
     */
    private static boolean parseBoolDefaultTrue(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        return raw instanceof Boolean ? (Boolean) raw : true;
    }

    /** 整数字段：缺字段 / 不是数字 → 用默认值 def（宽容读，0.9.6 之前的 config.json 原样加载）。 */
    private static int parseInt(Map<String, Object> map, String key, int def) {
        Object raw = map.get(key);
        return raw instanceof Number ? ((Number) raw).intValue() : def;
    }

    /** 字符串字段：空串当 null 兜，认不出来（或压根没这个字段）就是「没选」。 */
    private static String parseString(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        if (!(raw instanceof String)) {
            return null;
        }
        String text = ((String) raw).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * Java 路径：老配置**没有这个键**时返回空串，绝不返回 null、绝不触发 configCorrupted。
     * 沿用「缺字段用默认值」的宽容读口径，0.8.6 之前的 config.json 原样能加载。
     */
    private static String parseJavaPath(Object raw) {
        return raw instanceof String text ? text.trim() : "";
    }

    /** 下载源：老配置没这个键 / 值不认识 → 官方（宽容读，不为一个枚举把整份配置降级）。 */
    private static DownloadSource parseSource(Object raw) {
        if (raw instanceof String) {
            try {
                return DownloadSource.valueOf(((String) raw).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // 认不出来当官方：比「界面上显示不出来」安全
            }
        }
        return DownloadSource.OFFICIAL;
    }

    /** 读一份顶层 JSON 对象；读不到或不是对象就返回 null（调用方降级默认值）。 */
    private static Map<String, Object> readObject() {
        String text;
        try {
            if (!Files.isRegularFile(FILE)) {
                return new LinkedHashMap<>();
            }
            text = Files.readString(FILE, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            System.err.println("[settings] 读取失败，按默认值兜: " + ex);
            return null;
        }
        try {
            Object parsed = Json.parse(text);
            if (parsed instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) parsed;
                return map;
            }
            return null;
        } catch (RuntimeException ex) {
            System.err.println("[settings] 配置不是合法 JSON，按默认值兜: " + ex);
            return null;
        }
    }

}
