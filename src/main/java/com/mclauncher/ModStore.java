package com.mclauncher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * mods 目录的扫描 / 启停 / 卸载 + jar 元数据读取（v0.7.0 P0-3）。
 *
 * <p>蓝本第 3 节的机制，GBL 照做：
 * <ul>
 *   <li>一个 mod 就是 {@code .minecraft/mods} 里的一个 jar / zip / litemod 文件，
 *       认**一级子目录**（Forge / Quilt / Cleanroom / LiteLoader 都支持子目录）；</li>
 *   <li>启停 = {@code .disabled} 后缀改名（就这么朴素，别过度设计）；</li>
 *   <li>卸载 = {@link Platform#recycle}（Windows 进系统回收站，其它进软回收站）；</li>
 *   <li>元数据按优先级读 {@code fabric.mod.json} / {@code quilt.mod.json} /
 *       {@code mcmod.info} / {@code META-INF/mods.toml} / {@code litemod.json}，
 *       读不到用文件名兜底——不报错、不隐藏（PRD 规则②）。</li>
 * </ul>
 *
 * <p>纯服务层、不碰 UI：回调线程自行转 FX 线程（沿用现有约定）。
 */
public final class ModStore {

    /** 禁用后缀。HMCL 同款：追加即禁用、去掉即启用。 */
    private static final String DISABLED = ".disabled";

    private ModStore() {
        // 静态工具类，别让人 new 出来
    }

    /**
     * 一个装着的 mod。{@code enabled=false} 就是磁盘上带 {@code .disabled} 的那个。
     * {@code version} 来自 jar 元数据（{@link ModInfo#version()}），读不到为 null，
     * 由 UI 层决定怎么显示占位；服务层不写界面文案。
     */
    public record Mod(Path file, String fileName, String displayName, String modId,
                      String loader, boolean enabled, long sizeBytes, String subdir,
                      String version) {
    }

    /** jar 里读出来的元数据；读不到时各字段为 null（由 {@link Mod} 用文件名兜底）。 */
    public record ModInfo(String modId, String displayName, String loader, String version) {
    }

    /** {@code .minecraft/mods}；目录还没建出来就返回不存在的路径，由调用方判断。 */
    public static Path modsDir() {
        return Platform.minecraftDir().resolve("mods");
    }

    /**
     * 扫一遍 mods 根 + 一级子目录，含 {@code .disabled}。
     *
     * <p>任何一步读不了都不抛：一个坏 jar 不该让整个列表消失。排序按
     * 「子目录 → 文件名」，稳定可复现（界面刷新不会乱跳）。
     */
    public static List<Mod> scan() {
        List<Mod> out = new ArrayList<>();
        Path root = modsDir();
        if (!Files.isDirectory(root)) {
            return out;   // 没这个目录 = 一个 mod 都没有，正常路径不是错误
        }
        List<Path> files = new ArrayList<>();
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (Files.isRegularFile(child) && isModFile(child)) {
                    files.add(child);
                } else if (Files.isDirectory(child)) {
                    // 一级子目录（Forge 的 1.8.9/、Quilt 的 fabric/ 这类），不再往下递归
                    try (var sub = Files.list(child)) {
                        for (Path f : sub.toList()) {
                            if (Files.isRegularFile(f) && isModFile(f)) {
                                files.add(f);
                            }
                        }
                    } catch (IOException ex) {
                        System.err.println("[mod] 扫子目录 " + child.getFileName() + " 失败，跳过: " + ex);
                    }
                }
            }
        } catch (IOException ex) {
            System.err.println("[mod] 扫 mods 目录失败，按空列表兜: " + ex);
            return out;
        }
        files.sort(Comparator.comparing(ModStore::subdirOf, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(p -> p.getFileName().toString()));
        for (Path file : files) {
            out.add(describe(file));
        }
        return out;
    }

    /** 一个文件 → 一行列表数据。元数据读不到就用文件名兜底。 */
    private static Mod describe(Path file) {
        String fileName = file.getFileName().toString();
        boolean enabled = !fileName.endsWith(DISABLED);
        long size = 0L;
        try {
            size = Files.size(file);
        } catch (IOException | RuntimeException ex) {
            System.err.println("[mod] 数 " + fileName + " 体积失败，按 0 显示: " + ex);
        }
        ModInfo info = readInfo(file);
        String baseName = enabled ? fileName
                : fileName.substring(0, fileName.length() - DISABLED.length());
        return new Mod(file, fileName,
                info.displayName() == null ? baseName : info.displayName(),
                info.modId(), info.loader(), enabled, size, subdirOf(file), info.version());
    }

    /**
     * 启用 / 禁用：{@code .disabled} 改名法。
     *
     * <p>已经是目标态就什么都不做（返回前不碰磁盘）。目标名已被占用才抛——
     * 那种冲突必须让人知道，静默改会让用户丢一个 mod。
     */
    public static void setEnabled(Path file, boolean enabled) throws IOException {
        String name = file.getFileName().toString();
        boolean disabled = name.endsWith(DISABLED);
        if (disabled == !enabled) {
            return;   // 已经在目标态
        }
        Path target = disabled
                ? file.resolveSibling(name.substring(0, name.length() - DISABLED.length()))
                : file.resolveSibling(name + DISABLED);
        if (Files.exists(target)) {
            throw new IOException("已经有一个叫 " + target.getFileName() + " 的文件了，"
                    + "先处理它再启停这个");
        }
        Files.move(file, target);
        System.out.println("[mod] " + name + " → " + target.getFileName()
                + (enabled ? "（已启用）" : "（已禁用）"));
    }

    /** 卸载：走回收站（Windows 真进系统回收站，其它进软回收站）。返回是否真送进去了。 */
    public static boolean uninstall(Path file) {
        if (file == null || !Files.exists(file)) {
            return false;
        }
        boolean ok = Platform.recycle(file);
        System.out.println("[mod] 卸载 " + file.getFileName() + " → 回收站=" + ok);
        return ok;
    }

    /**
     * 读 jar 里的 mod 元数据；读不到返回全 null 记录（**不抛**）。
     *
     * <p>五种格式按优先级试：{@code fabric.mod.json} → {@code quilt.mod.json} →
     * {@code mcmod.info} → {@code META-INF/mods.toml} → {@code litemod.json}。
     * {@code mods.toml} 是 TOML 不是 JSON，用极简行扫只取 {@code modId} / {@code displayName}
     * 两个键——不引 TOML 库（架构 D3）。
     */
    public static ModInfo readInfo(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return new ModInfo(null, null, null, null);
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            // 1) Fabric
            if (zip.getEntry("fabric.mod.json") != null) {
                Object parsed = parseEntry(zip, "fabric.mod.json");
                if (parsed instanceof Map<?, ?> m) {
                    return new ModInfo(str(m.get("id")), str(m.get("name")), "fabric",
                            str(m.get("version")));
                }
            }
            // 2) Quilt：{"quilt_loader": {"id": ..., "version": ..., "metadata": {"name": ...}}}
            if (zip.getEntry("quilt.mod.json") != null) {
                Object parsed = parseEntry(zip, "quilt.mod.json");
                if (parsed instanceof Map<?, ?> m && m.get("quilt_loader") instanceof Map<?, ?> ql) {
                    Map<?, ?> metadata = ql.get("metadata") instanceof Map<?, ?> md ? md : null;
                    return new ModInfo(str(ql.get("id")),
                            metadata == null ? null : str(metadata.get("name")), "quilt",
                            str(ql.get("version")));
                }
            }
            // 3) mcmod.info：老格式是数组，新格式是 {"modList": [...]}
            if (zip.getEntry("mcmod.info") != null) {
                Object parsed = parseEntry(zip, "mcmod.info");
                Map<?, ?> first = null;
                if (parsed instanceof List<?> list && !list.isEmpty()
                        && list.get(0) instanceof Map<?, ?> m) {
                    first = m;
                } else if (parsed instanceof Map<?, ?> m && m.get("modList") instanceof List<?> l
                        && !l.isEmpty() && l.get(0) instanceof Map<?, ?> item) {
                    first = item;
                }
                if (first != null) {
                    String modId = str(first.get("modid"));
                    String name = str(first.get("name"));
                    return new ModInfo(modId, name == null ? modId : name, "forge",
                            str(first.get("version")));
                }
            }
            // 4) META-INF/mods.toml：TOML 行扫，只取第一个 [[mods]] 块的 modId / displayName
            ZipEntry toml = zip.getEntry("META-INF/mods.toml");
            if (toml != null) {
                ModInfo info = readModsToml(zip, toml);
                if (info != null) {
                    return info;
                }
            }
            // 5) LiteLoader
            if (zip.getEntry("litemod.json") != null) {
                Object parsed = parseEntry(zip, "litemod.json");
                if (parsed instanceof Map<?, ?> m) {
                    String name = str(m.get("name"));
                    return new ModInfo(name, name, "liteLoader", str(m.get("version")));
                }
            }
        } catch (IOException | RuntimeException ex) {
            // 打不开 / 不是 zip / 条目读坏：按读不到处理，调用方用文件名兜底
            System.err.println("[mod] 读不了 " + file.getFileName() + " 的元数据，按文件名兜底: " + ex);
        }
        return new ModInfo(null, null, null, null);
    }

    /** 去 {@code .disabled} 后缀的基名（无后缀则原样返回）。UI 持有改名前的旧文件名时用它归位。 */
    public static String stripDisabled(String fileName) {
        if (fileName != null && fileName.endsWith(DISABLED)) {
            return fileName.substring(0, fileName.length() - DISABLED.length());
        }
        return fileName;
    }

    /**
     * 按基名在 mods 根 + 一级子目录里找当前文件（启用或已禁用都算）。找不到返回 null。
     *
     * <p>0.8.5 修「启用态丢失」：UI 把 mod 行渲染出来后，开关动作若直接拿当时扫到的旧路径去改名，
     * 一旦该文件已经被前一次改名挪过位置（同一行没刷新就二次切换），旧路径就失效、改名失败、
     * 刷新后反而显示成禁用。这里每次都按基名重新从磁盘定位，从源头消除 stale-path。
     */
    public static Path resolve(String baseName) {
        if (baseName == null || baseName.isBlank()) {
            return null;
        }
        Path root = modsDir();
        Path enabled = root.resolve(baseName);
        if (Files.isRegularFile(enabled)) {
            return enabled;
        }
        Path disabled = root.resolve(baseName + DISABLED);
        if (Files.isRegularFile(disabled)) {
            return disabled;
        }
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (Files.isDirectory(child)) {
                    Path e = child.resolve(baseName);
                    if (Files.isRegularFile(e)) {
                        return e;
                    }
                    Path d = child.resolve(baseName + DISABLED);
                    if (Files.isRegularFile(d)) {
                        return d;
                    }
                }
            }
        } catch (IOException ex) {
            System.err.println("[mod] resolve " + baseName + " 失败: " + ex);
        }
        return null;
    }

    /** jar / zip / litemod（可带 {@code .disabled} 后缀）才算 mod 文件。 */
    public static boolean isModFile(Path file) {
        if (file == null) {
            return false;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(DISABLED)) {
            name = name.substring(0, name.length() - DISABLED.length());
        }
        return name.endsWith(".jar") || name.endsWith(".zip") || name.endsWith(".litemod");
    }

    // ---------------------------------------------------------------- 内部

    /** 读 zip 里的一个条目并解析成 JSON；读不了 / 不是 JSON 返回 null。 */
    private static Object parseEntry(ZipFile zip, String entryName) {
        try (InputStream in = zip.getInputStream(zip.getEntry(entryName))) {
            return Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ex) {
            System.err.println("[mod] 读 " + entryName + " 失败，试下一种格式: " + ex);
            return null;
        }
    }

    /**
     * 极简 TOML 行扫：只要第一个 {@code [[mods]]} 块的 {@code modId} / {@code displayName} /
     * {@code version}。
     *
     * <p>不引 TOML 库的代价说清楚：多行字符串、行内注释、花哨转义都不认——
     * 但这三个键在任何真实 mods.toml 里都是最普通的 {@code key="value"} 行，够用。
     */
    private static ModInfo readModsToml(ZipFile zip, ZipEntry entry) {
        String modId = null;
        String displayName = null;
        String version = null;
        boolean inBlock = false;
        try (InputStream in = zip.getInputStream(entry)) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .split("\\r?\\n")) {
                String text = line.trim();
                if (text.startsWith("#") || text.isEmpty()) {
                    continue;
                }
                if (text.startsWith("[[") && text.endsWith("]]")) {
                    if (inBlock) {
                        break;   // 第二个块起是别的 mod，不归这一行管
                    }
                    inBlock = "[[mods]]".equals(text);
                    continue;
                }
                if (!inBlock) {
                    continue;
                }
                int eq = text.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = text.substring(0, eq).trim();
                String value = tomlValue(text.substring(eq + 1));
                switch (key) {
                    case "modId" -> modId = value;
                    case "displayName" -> displayName = value;
                    case "version" -> version = value;
                    default -> { /* 只取这三个键，别的不管 */ }
                }
            }
        } catch (IOException | RuntimeException ex) {
            System.err.println("[mod] 读 META-INF/mods.toml 失败，试下一种格式: " + ex);
            return null;
        }
        if (modId == null && displayName == null) {
            return null;
        }
        return new ModInfo(modId, displayName == null ? modId : displayName, "forge", version);
    }

    /** {@code key = "value"} 的值：去引号、去行内注释。认不出来返回 null。 */
    private static String tomlValue(String raw) {
        String text = raw.trim();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1);
        } else if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
            text = text.substring(1, text.length() - 1);
        }
        return text.isBlank() ? null : text;
    }

    /** 一级子目录名；根目录下的文件返回 null。 */
    private static String subdirOf(Path file) {
        Path parent = file.toAbsolutePath().getParent();
        Path root = modsDir().toAbsolutePath();
        if (parent == null || parent.equals(root)) {
            return null;
        }
        return parent.getFileName() == null ? null : parent.getFileName().toString();
    }

    private static String str(Object raw) {
        return raw instanceof String text && !text.isBlank() ? text : null;
    }
}
