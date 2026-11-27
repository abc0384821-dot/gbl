package com.mclauncher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「已装内容 → 适配 Minecraft 版本」的落盘与解析（0.9.4）。
 *
 * <p>游戏管理页五类条目的版本胶囊要显示该内容下载安装时所选的 MC 版本，而不是随
 * 「当前游戏」切换而变的 {@code effectiveGameId()}。本类是纯 Java 服务层，不依赖 JavaFX，
 * 也不认识 ShellView 的任何字段；所有取值都来自「文件名 + 落盘元数据 + 登记簿 + 文件名解析
 * + ModIndex 索引」，<b>任何一级都不回退当前游戏</b>，否则 bug 原样复现。
 *
 * <p>落盘写法完全对齐 ShellView#rememberContentCover：{@code ~/.gbl} 目录、
 * Properties load→setProperty→store、任何异常一律吞掉，绝不影响安装结果。
 */
public final class AdaptedMc {

    /** 适配版本元数据：{@code ~/.gbl/installed-content-meta.properties}。 */
    private static final Path META_FILE = Paths.get(
            System.getProperty("user.home"), ".gbl", "installed-content-meta.properties");

    /** 0.9.4 r2 指纹：禁用后缀 key 规范化 + 版本号正则收紧。构建门禁扫常量池，勿改这串字。 */
    private static final String FP_094_R2_NORM = "0.9.4 r2 适配版本键规范化";
    static { System.err.println("[adapted] " + FP_094_R2_NORM); }

    /** 带 mc 前缀的版本：+mc26.2 / mc1.21.1 / _mc26w06a_（mc 前缀会被去掉）。 */
    private static final Pattern MC_PREFIX = Pattern.compile(
            "(?i)mc(\\d{1,2}(?:\\.\\d+){1,3}|\\d{2}w\\d{2}[a-z]?)");
    /** 快照形态：26w06a / 24w03a（前后不能再跟字母数字，避免嵌进别的词里）。 */
    private static final Pattern SNAPSHOT = Pattern.compile(
            "(?i)(?:^|[^0-9a-z])(\\d{2}w\\d{2}[a-z]?)(?:$|[^0-9a-z])");
    /** 分隔符包围的正式版 / 年份式版本：+1.21.1 / _1.20.1_ / -1.21.1- / 26.2；
     *  '.' 不作前导分隔符，避免把 5.9.3 中段 9.3 误判成 MC 版本（0.9.4 r2）。 */
    private static final Pattern PLAIN_VERSION = Pattern.compile(
            "(?:^|[+_\\-])(\\d{1,2}\\.\\d+(?:\\.\\d+)?)(?=$|[+_\\-.])");

    private AdaptedMc() {
        // 静态工具类，别让人 new 出来
    }

    /** 规范化版本键：null 安全、去首尾空白、去末尾 .disabled（大小写不敏感）。0.9.4 r2。 */
    private static String norm(String f) {
        if (f == null) {
            return null;
        }
        return f.trim().replaceAll("(?i)\\.disabled$", "");
    }

    /** 安装成功后把「文件名 → 适配 MC 版本」记到磁盘；任何失败都吞掉，绝不影响安装结果。 */
    public static void remember(String fileName, String mcVersion) {
        if (fileName == null || mcVersion == null || mcVersion.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(META_FILE.getParent());
            Properties props = new Properties();
            if (Files.isRegularFile(META_FILE)) {
                try (var in = Files.newInputStream(META_FILE)) {
                    props.load(in);
                }
            }
            props.setProperty(norm(fileName), mcVersion);
            try (var out = Files.newOutputStream(META_FILE)) {
                props.store(out, "GBL installed content adapted MC versions");
            }
        } catch (Exception ignored) {
            // 适配版本落盘失败绝不影响安装结果
        }
    }

    /** 读安装时落盘的适配 MC 版本；文件不存在 / 读坏 / 没这个 key 都返回 null。 */
    public static String of(String fileName) {
        if (fileName == null) {
            return null;
        }
        try {
            if (!Files.isRegularFile(META_FILE)) {
                return null;
            }
            Properties props = new Properties();
            try (var in = Files.newInputStream(META_FILE)) {
                props.load(in);
            }
            String v = props.getProperty(fileName);
            if (v != null) {
                return v;
            }
            return props.getProperty(norm(fileName));
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 从文件名解析适配版本：{@code +mc26.2→26.2}、{@code +1.21.1→1.21.1}、
     * {@code _1.20.1_→1.20.1}、{@code -1.21.1-→1.21.1}、{@code 26w06a}；去掉 mc 前缀；
     * 无匹配返回 null。多个候选时取最靠近扩展名的那个（通常是 MC 版本而非模组自身版本）。
     */
    public static String fromFileName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        name = norm(name);
        Matcher mc = MC_PREFIX.matcher(name);
        if (mc.find()) {
            return mc.group(1);
        }
        Matcher snap = SNAPSHOT.matcher(name);
        if (snap.find()) {
            return snap.group(1);
        }
        Matcher v = PLAIN_VERSION.matcher(name);
        String last = null;
        while (v.find()) {
            last = v.group(1);
        }
        return last;
    }

    /**
     * 分级兜底解析适配版本：
     * <ol>
     *   <li>落盘元数据 {@link #of(String)} / 调用方传入的 metaMc；</li>
     *   <li>packMc（整合包登记簿 mcVersion，可靠来源）；</li>
     *   <li>{@link #fromFileName(String)}；</li>
     *   <li>ModIndex 目录反查（遍历 {@code ~/.gbl/cache/mods/*}，按 installedAt 取最新）；</li>
     *   <li>itemVersion（包版本 / NBT，语义是「包版本」，仅作最后兜底）；</li>
     *   <li>全落空返回 null，由 UI 显示「未知版本」。</li>
     * </ol>
     * 红线：任何一级都不回退「当前游戏」。
     */
    public static String resolve(String fileName, String metaMc, String packMc, String itemVersion) {
        String n = norm(fileName);
        String fromMeta = (metaMc != null && !metaMc.isBlank()) ? metaMc : of(n);
        if (fromMeta != null && !fromMeta.isBlank()) {
            return fromMeta;
        }
        if (packMc != null && !packMc.isBlank()) {
            return packMc;
        }
        String fromName = fromFileName(n);
        if (fromName != null && !fromName.isBlank()) {
            return fromName;
        }
        String fromIndex = lookupIndex(n);
        if (fromIndex != null && !fromIndex.isBlank()) {
            return fromIndex;
        }
        if (itemVersion != null && !itemVersion.isBlank()) {
            return itemVersion;
        }
        return null;
    }

    /**
     * ModIndex 目录反查：遍历 {@code ~/.gbl/cache/mods/<versionId>/index.json}，
     * 用 {@link ModIndex#entries(String)} 找含该 filename 的目录名（即安装时的游戏版本）；
     * 同名跨版本取 installedAt 最新。任何异常返回 null，绝不影响 UI。
     */
    private static String lookupIndex(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        fileName = norm(fileName);
        try {
            Path root = Paths.get(System.getProperty("user.home"), ".gbl", "cache", "mods");
            if (!Files.isDirectory(root)) {
                return null;
            }
            String bestVersion = null;
            String bestAt = null;
            try (var dirs = Files.newDirectoryStream(root)) {
                for (Path dir : dirs) {
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    String versionId = dir.getFileName().toString();
                    for (ModIndex.Entry e : ModIndex.entries(versionId)) {
                        if (fileName.equals(e.filename())) {
                            String at = e.installedAt() == null ? "" : e.installedAt();
                            if (bestAt == null || at.compareTo(bestAt) > 0) {
                                bestAt = at;
                                bestVersion = versionId;
                            }
                        }
                    }
                }
            }
            return bestVersion;
        } catch (Exception ignored) {
            return null;
        }
    }
}
