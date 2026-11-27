package com.mclauncher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 资源包 / 光影 / 世界 三类内容的本地存储与安装层（v0.8.7）。
 *
 * <p>纯服务层、不碰 UI，逻辑对齐 {@link ModStore} 的口径：
 * <ul>
 *   <li>三个目录都在 {@link Platform#minecraftDir()} 下：
 *       {@code resourcepacks} / {@code shaderpacks} / {@code saves}；</li>
 *   <li>启停 = {@code .disabled} 后缀改名（直接复用 {@link ModStore#setEnabled}）；</li>
 *   <li>卸载 = {@link Platform#recycle}（回收站，误删能捡回）；</li>
 *   <li>列表数据一律「读不坏不抛」：单个坏项跳过、元数据读不到用文件名兜底。</li>
 * </ul>
 *
 * <p>世界安装是这里唯一会「写很多文件」的动作：一律先解到 saves 下的隐藏临时目录、
 * 全程 zip-slip 校验、成功后再原子 rename 成正式世界目录，任何失败 finally 清掉临时目录，
 * 绝不在 saves 里留半成品。
 */
public final class ContentStore {

    /** 三种内容。 */
    public enum Kind { RESOURCEPACK, SHADER, WORLD }

    /**
     * 一行列表数据。{@code enabled=false} 即磁盘上带 {@code .disabled} 的那个。
     * {@code version} 为资源包 / 光影的包版本（pack.mcmeta 等），世界项因 NBT 重 IO
     * 放到 UI 后台按需读取，scan 阶段恒为 null；读不到一律 null，由 UI 层决定占位。
     */
    public record Item(Path path, String fileName, String displayName,
                       boolean enabled, long sizeBytes, String source, String version) {
    }

    /** 禁用后缀，与 {@link ModStore} 同款。改名口径也直接委托给它，不另写一套。 */
    private static final String DISABLED = ".disabled";
    /** 本地导入的内容没有来源平台可标，统一写这个。 */
    private static final String SOURCE_LOCAL = "本地导入";
    /** 世界安装用的临时目录前缀；scan 会跳过它，避免把半成品当世界列出来。 */
    private static final String WORLD_TEMP_PREFIX = ".gbl-world-";

    private ContentStore() {
        // 静态工具类，别让人 new 出来
    }

    // ------------------------------------------------------------------ 目录 / 扫描

    /** {@code .minecraft/<对应目录>}；目录还没建出来就返回不存在的路径，由调用方判断。 */
    public static Path dir(Kind kind) {
        Path mc = Platform.minecraftDir();
        return switch (kind) {
            case RESOURCEPACK -> mc.resolve("resourcepacks");
            case SHADER -> mc.resolve("shaderpacks");
            case WORLD -> mc.resolve("saves");
        };
    }

    /**
     * 扫一遍对应目录。目录不存在返回空 List（正常路径不是错误，不抛）。
     *
     * <p>资源包 / 光影：扫根目录下的一级子项，只认 {@code .zip} 文件与「目录」，
     * 二者都可带 {@code .disabled} 后缀；其余文件（如说明 txt）忽略。
     * 世界：扫 {@code saves} 下的一级子目录，跳过安装临时目录，enabled 恒 true。
     * 目录的 {@code sizeBytes} 递归求和；单项读坏（算体积 / 读元数据）跳过、不影响整表。
     * 按文件名稳定排序，界面刷新不会乱跳。
     */
    public static List<Item> scan(Kind kind) {
        List<Item> out = new ArrayList<>();
        Path root = dir(kind);
        if (!Files.isDirectory(root)) {
            return out;
        }
        List<Path> paths = new ArrayList<>();
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (kind == Kind.WORLD) {
                    if (Files.isDirectory(child) && !name.startsWith(WORLD_TEMP_PREFIX)) {
                        paths.add(child);
                    }
                } else {
                    // 去掉 .disabled 再判扩展名：foo.zip.disabled 仍是 zip，要扫进来
                    String base = stripDisabled(name);
                    boolean zip = base.toLowerCase(Locale.ROOT).endsWith(".zip");
                    if (Files.isDirectory(child) || zip) {
                        paths.add(child);
                    }
                }
            }
        } catch (IOException ex) {
            System.err.println("[content] 扫 " + root.getFileName() + " 失败，按空列表兜: " + ex);
            return out;
        }
        paths.sort(Comparator.comparing((Path p) -> p.getFileName().toString()));
        for (Path path : paths) {
            try {
                String name = path.getFileName().toString();
                boolean enabled = kind == Kind.WORLD || !name.endsWith(DISABLED);
                long size = sizeOf(path);
                String display;
                String version;
                if (kind == Kind.WORLD) {
                    // 世界目录：显示名直接用目录名，版本（gzip NBT）由 UI 后台按需读取，
                    // 不在 scan 阶段对全部世界做重 IO（避免管理页打开卡顿）。
                    display = stripDisabled(name);
                    version = null;
                } else {
                    // 资源包 / 光影：一次打开 zip 同时拿显示名与版本，避免同一个 zip 开两次。
                    PackMeta pm = describe(path);
                    display = pm.description();
                    version = pm.version();
                }
                out.add(new Item(path, name, display, enabled, size, SOURCE_LOCAL, version));
            } catch (IOException | RuntimeException ex) {
                System.err.println("[content] 读 " + path.getFileName() + " 失败，跳过这一项: " + ex);
            }
        }
        return out;
    }

    /** 文件取字节数，目录递归求和。读不动由调用方按「坏项跳过」处理。 */
    private static long sizeOf(Path path) throws IOException {
        if (Files.isRegularFile(path)) {
            return Files.size(path);
        }
        long total = 0L;
        try (var walk = Files.walk(path)) {
            for (Path p : walk.filter(p -> Files.isRegularFile(p)).toList()) {
                total += Files.size(p);
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ 启停 / 卸载

    /**
     * 启用 / 禁用：直接委托 {@link ModStore#setEnabled(Path, boolean)}，复用它那套
     * {@code .disabled} 改名口径（已在目标态不动、目标名已占用抛 {@link IOException}）。
     * 世界不支持启停：命中 saves 目录抛 {@link UnsupportedOperationException}。
     */
    public static void setEnabled(Path path, boolean enabled) throws IOException {
        if (isWorld(path)) {
            throw new UnsupportedOperationException("世界不支持启用 / 禁用");
        }
        ModStore.setEnabled(path, enabled);
    }

    /** 卸载：走回收站，返回 {@link Platform#recycle} 的结果。 */
    public static boolean uninstall(Path path) {
        return Platform.recycle(path);
    }

    /** path 是否落在 {@code saves} 目录下（世界项）。 */
    private static boolean isWorld(Path path) {
        if (path == null) {
            return false;
        }
        return path.toAbsolutePath().normalize()
                .startsWith(dir(Kind.WORLD).toAbsolutePath().normalize());
    }

    // ------------------------------------------------------------------ 安装（资源包 / 光影）

    /**
     * 把一个文件（资源包 / 光影的 {@code .zip} 等）写进 {@link #dir} 对应目录。
     * 世界请走 {@link #installWorld}，这里传 {@link Kind#WORLD} 抛 {@link IllegalArgumentException}。
     * 文件名净化（去路径分隔符、拒绝 {@code ..} 与盘符 / Windows 非法字符），
     * 先写同目录临时 {@code .part} 再原子 rename，失败清掉临时文件。返回落盘 Path。
     */
    public static Path installFile(Kind kind, String fileName, byte[] bytes) throws IOException {
        if (kind == Kind.WORLD) {
            throw new IllegalArgumentException("世界是目录，请用 installWorld 安装");
        }
        if (bytes == null) {
            throw new IllegalArgumentException("文件内容为空");
        }
        String name = sanitizeName(fileName);
        Path root = dir(kind).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("文件名越出了目标目录: " + fileName);
        }
        Path part = Files.createTempFile(root, ".gbl-install-", ".part");
        try {
            Files.write(part, bytes);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(part);
        }
        return target;
    }

    // ------------------------------------------------------------------ 安装（世界）

    /**
     * 把世界 zip 装到 {@code saves/<worldName>/}。
     *
     * <p>流程（契约要求「先解到临时目录再移动，失败清理，不留半成品」）：
     * <ol>
     *   <li>在 saves 下建隐藏临时目录，把 zip 全解到其中的 {@code extracted/}；</li>
     *   <li>每个 ZipEntry 归一化后必须仍在解压根内，遇到 {@code ../}、绝对路径、
     *       畸形 / 异常条目立即抛 {@link IOException}；空包也按坏包拒绝；</li>
     *   <li>若 {@code extracted/} 下整体只有一个顶层目录（CurseForge 世界包常见形态），
     *       把该目录内容提升为世界根（直接拿它当内容根）；</li>
     *   <li>把内容根原子 rename 成正式世界目录；finally 无论成败都清掉临时目录。</li>
     * </ol>
     * 目标世界名已存在时抛 {@link IOException} 拒绝覆盖——世界存档不能静默覆盖用户进度。
     * worldName 同样净化，返回世界目录 Path。
     */
    public static Path installWorld(String worldName, byte[] zipBytes) throws IOException {
        String safe = sanitizeName(worldName);
        Path saves = dir(Kind.WORLD).toAbsolutePath().normalize();
        Files.createDirectories(saves);
        Path finalDir = saves.resolve(safe).normalize();
        if (!finalDir.startsWith(saves)) {
            throw new IOException("世界名非法: " + worldName);
        }
        if (Files.exists(finalDir)) {
            throw new IOException("世界「" + safe + "」已存在，请先卸载再安装");
        }
        // 临时目录建在 saves 内：与 finalDir 同文件系统，move 是原子 rename，跨盘复制的坑绕开
        Path temp = Files.createTempDirectory(saves, WORLD_TEMP_PREFIX);
        try {
            Path extracted = temp.resolve("extracted");
            extractZipSafely(zipBytes, extracted);
            Path contentRoot = promoteSingleTopDir(extracted);
            Files.move(contentRoot, finalDir);
            return finalDir;
        } finally {
            deleteRecursively(temp);
        }
    }

    /**
     * 把 {@code zipBytes} 全解到 {@code destDir}，逐项做 zip-slip 校验。
     * 任何越界 / 绝对路径 / 异常条目都以 {@link IOException} 抛出（不留半成品由调用方 finally 清理）。
     */
    private static void extractZipSafely(byte[] zipBytes, Path destDir) throws IOException {
        if (zipBytes == null) {
            throw new IOException("世界包内容为空");
        }
        Path destRoot = destDir.toAbsolutePath().normalize();
        Files.createDirectories(destRoot);
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(
                new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                count++;
                String name = entry.getName();
                try {
                    if (name == null || name.isEmpty()) {
                        throw new IOException("zip 里有空名条目");
                    }
                    // 统一斜杠：恶意包可能用反斜杠拼 ..\ 穿越
                    String unified = name.replace('\\', '/');
                    if (unified.startsWith("/") || unified.matches("^[A-Za-z]:.*")) {
                        throw new IOException("zip 里有绝对路径条目: " + name);
                    }
                    Path target = destRoot.resolve(unified).normalize();
                    if (!target.startsWith(destRoot)) {
                        throw new IOException("zip 条目路径越界（zip-slip）: " + name);
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        try (OutputStream out = Files.newOutputStream(target,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE)) {
                            zis.transferTo(out);
                        }
                    }
                } catch (RuntimeException ex) {
                    // InvalidPathException 之类：按「异常条目」收口成 IOException
                    throw new IOException("zip 条目异常: " + name, ex);
                }
                // getNextEntry() 会自动关闭上一个条目，这里不必手动 closeEntry
            }
        }
        if (count == 0) {
            throw new IOException("世界包里没有任何内容（可能不是有效的 zip）");
        }
    }

    /** 整体只有一个顶层目录时把它当内容根（提升为世界根），否则内容根就是解压根。 */
    private static Path promoteSingleTopDir(Path extracted) throws IOException {
        List<Path> top;
        try (var children = Files.list(extracted)) {
            top = children.toList();
        }
        if (top.size() == 1 && Files.isDirectory(top.get(0))) {
            return top.get(0);
        }
        return extracted;
    }

    // ------------------------------------------------------------------ 显示名 / 版本

    /** 单个元数据条目读取上限，防畸形大文件吃内存。 */
    private static final int MAX_MCMETA_BYTES = 256 * 1024;
    private static final int MAX_PROPS_BYTES = 256 * 1024;
    /** 文件名 / 文本里可识别的 MC 版本号，如 1.20.1。 */
    private static final java.util.regex.Pattern MC_VERSION =
            java.util.regex.Pattern.compile("1\\.\\d+(?:\\.\\d+)?");
    /** shaders.properties 里偶有的版本键：version= / mcVersion= / minecraft=。 */
    private static final java.util.regex.Pattern SHADER_VERSION_KEY =
            java.util.regex.Pattern.compile(
                    "(?im)^\\s*(?:version|mcversion|minecraft)\\s*[:=]\\s*([0-9][0-9.]*)");

    /** 一次读取拿到的包元数据：显示名（已含兜底）与版本文本（读不到为 null）。 */
    private record PackMeta(String description, String version) {
    }

    /**
     * 显示名，任何异常都不抛、返回兜底字符串（去扩展名的文件名 / 目录名）。
     * 内部走 {@link #describe(Path)}，只取描述部分，行为与旧版一致。
     */
    public static String displayName(Path path) {
        return describe(path).description();
    }

    /**
     * 一次 IO 同时取显示名与版本：{@code .zip} 只打开一次，同时读 {@code pack.mcmeta}
     * 的 description 与 pack_format / supported_formats；影包再回退
     * {@code shaders/shaders.properties} 与文件名版本号。目录形式读目录下
     * {@code pack.mcmeta}（只取版本，显示名仍用目录名）。任何异常都吞掉，返回兜底显示名。
     */
    private static PackMeta describe(Path path) {
        String fallback = fallbackName(path);
        if (path == null) {
            return new PackMeta("", null);
        }
        try {
            if (Files.isDirectory(path)) {
                // 目录形式（未打包资源包）：版本读 pack.mcmeta，显示名保持目录名
                return new PackMeta(fallback, readPackVersionFromFile(path.resolve("pack.mcmeta")));
            }
            String base = stripDisabled(path.getFileName().toString());
            if (base.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                return readZipMeta(path, fallback);
            }
            return new PackMeta(fallback, null);
        } catch (Exception ex) {
            return new PackMeta(fallback, null);
        }
    }

    /** 目录名 / 去扩展名文件名兜底（去 {@code .disabled} 后缀）。 */
    private static String fallbackName(Path path) {
        if (path == null) {
            return "";
        }
        String fileName = path.getFileName().toString();
        String base = stripDisabled(fileName);
        return Files.isDirectory(path) ? base : stripExtension(base);
    }

    /**
     * 打开 zip 一次，读 pack.mcmeta 的 description + 版本；影包再试
     * shaders/shaders.properties，最后回退文件名版本号。绝不重复打开同一个 zip。
     */
    private static PackMeta readZipMeta(Path path, String fallback) {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            String description = null;
            String version = null;
            ZipEntry mc = zip.getEntry("pack.mcmeta");
            if (mc != null) {
                try (InputStream in = zip.getInputStream(mc)) {
                    String text = new String(readLimited(in, MAX_MCMETA_BYTES), StandardCharsets.UTF_8);
                    if (!text.isEmpty() && text.charAt(0) == 0xFEFF) {
                        text = text.substring(1);   // 有些 pack.mcmeta 带 UTF-8 BOM
                    }
                    Object parsed = Json.parse(text);
                    if (parsed instanceof Map<?, ?> root
                            && root.get("pack") instanceof Map<?, ?> pack) {
                        Object desc = pack.get("description");
                        String raw = null;
                        if (desc instanceof String s) {
                            raw = s;
                        } else if (desc instanceof Map<?, ?> m && m.get("text") instanceof String t) {
                            raw = t;   // 文本组件形态 {"text": "..."}
                        }
                        if (raw != null) {
                            description = stripColorCodes(raw).trim();
                        }
                        version = versionFromPack(pack);
                    }
                }
            }
            if (version == null) {
                // 影包通常没有 pack.mcmeta，读 shaders/shaders.properties 里的版本线索
                ZipEntry props = zip.getEntry("shaders/shaders.properties");
                if (props != null) {
                    try (InputStream in = zip.getInputStream(props)) {
                        version = versionFromShadersProps(
                                new String(readLimited(in, MAX_PROPS_BYTES), StandardCharsets.UTF_8));
                    }
                }
            }
            if (version == null) {
                version = versionFromText(fallback);
            }
            return new PackMeta(description == null || description.isBlank() ? fallback : description,
                    version);
        } catch (IOException | RuntimeException ex) {
            // 不是 zip / 条目读坏 / JSON 认不出：按读不到处理，显示名与版本都兜底
            System.err.println("[content] 读 " + path.getFileName()
                    + " 的包元数据失败，按文件名兜底: " + ex);
            return new PackMeta(fallback, versionFromText(fallback));
        }
    }

    /** 目录下 {@code pack.mcmeta} → 版本文本；读不到返回 null，不抛。 */
    private static String readPackVersionFromFile(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            if (Files.size(file) > MAX_MCMETA_BYTES) {
                return null;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == 0xFEFF) {
                text = text.substring(1);
            }
            Object parsed = Json.parse(text);
            if (parsed instanceof Map<?, ?> root
                    && root.get("pack") instanceof Map<?, ?> pack) {
                return versionFromPack(pack);
            }
        } catch (IOException | RuntimeException ex) {
            // 目录里没这个文件 / 读坏了：按读不到处理
        }
        return null;
    }

    /** pack{} → 版本：优先 supported_formats，其次 pack_format。 */
    private static String versionFromPack(Map<?, ?> pack) {
        Object supported = pack.get("supported_formats");
        if (supported != null) {
            String v = versionFromSupported(supported);
            if (v != null) {
                return v;
            }
        }
        int format = intOf(pack.get("pack_format"));
        return format >= 1 ? PackVersion.describe(format) : null;
    }

    /**
     * supported_formats 可能是：单个数字、数字数组、字符串数组、
     * 或 {@code {min_inclusive, max_inclusive}} 对象（值可为数字或版本字符串）。
     */
    private static String versionFromSupported(Object raw) {
        if (raw instanceof Number n) {
            return PackVersion.describe(n.intValue());
        }
        if (raw instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        if (raw instanceof List<?> list && !list.isEmpty()) {
            Integer min = null;
            Integer max = null;
            boolean allNumbers = true;
            List<String> texts = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Number n) {
                    int v = n.intValue();
                    min = min == null ? v : Math.min(min, v);
                    max = max == null ? v : Math.max(max, v);
                } else {
                    allNumbers = false;
                    if (o instanceof String s && !s.isBlank()) {
                        texts.add(s.trim());
                    }
                }
            }
            if (allNumbers && min != null) {
                return PackVersion.range(min, max);
            }
            if (!texts.isEmpty()) {
                return texts.size() == 1 ? texts.get(0)
                        : texts.get(0) + "–" + texts.get(texts.size() - 1);
            }
        }
        if (raw instanceof Map<?, ?> m) {
            Object mn = m.get("min_inclusive");
            Object mx = m.get("max_inclusive");
            if (mn instanceof Number a && mx instanceof Number b) {
                return PackVersion.range(a.intValue(), b.intValue());
            }
            if (mn instanceof String a && mx instanceof String b) {
                return a.equals(b) ? a : a + "–" + b;
            }
            if (mn instanceof String a) {
                return a + "+";
            }
        }
        return null;
    }

    /** shaders.properties → 版本线索：先找 version/mcVersion 键，再找文本里 1.x 号。 */
    private static String versionFromShadersProps(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = SHADER_VERSION_KEY.matcher(text);
        if (m.find()) {
            String v = m.group(1).replaceAll("\\.+$", "");
            if (!v.isBlank()) {
                return v;
            }
        }
        return versionFromText(text);
    }

    /** 文本 / 文件名里第一个可识别的 MC 版本号（1.x / 1.x.y），没有返回 null。 */
    private static String versionFromText(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher m = MC_VERSION.matcher(text);
        return m.find() ? m.group() : null;
    }

    /** Json.parse 把数字一律给成 Double，这里统一取 int；认不出返回 -1。 */
    private static int intOf(Object raw) {
        return raw instanceof Number n ? n.intValue() : -1;
    }

    /** 只读前 {@code max} 字节，超过即抛，防畸形大条目吃内存。 */
    private static byte[] readLimited(InputStream in, int max) throws IOException {
        byte[] buf = in.readNBytes(max + 1);
        if (buf.length > max) {
            throw new IOException("元数据条目过大（超过 " + max + " 字节）");
        }
        return buf;
    }

    /**
     * 世界版本：读 {@code level.dat}（gzip NBT）的 {@code Data.Version.Name / Id}。
     * 重 IO 由调用方放到后台线程按需做，不在 {@link #scan} 里对全部世界解析；
     * 任何异常都吞掉返回 null。
     */
    public static String worldVersion(Path worldDir) {
        if (worldDir == null) {
            return null;
        }
        try {
            return NbtLite.worldVersion(worldDir.resolve("level.dat"));
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /** 去掉 Minecraft 的 {@code §X} 颜色 / 格式码（{@code §} 即 U+00A7，每个 § 吃掉后一个字符）。 */
    private static String stripColorCodes(String text) {
        return text.replaceAll("\u00A7.", "");
    }

    // ------------------------------------------------------------------ 内部工具

    /**
     * 名字净化：取最后一段去掉路径分隔符，拒绝 {@code .} / {@code ..} / 含 {@code ..}，
     * 再替换掉 Windows 文件名非法字符。契约要求的「去路径分隔符、拒绝 ..」都在这里。
     */
    private static String sanitizeName(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("名字为空");
        }
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name) || name.contains("..")) {
            throw new IllegalArgumentException("非法名字: " + raw);
        }
        return name.replaceAll("[:*?\"<>|]", "_");
    }

    private static String stripDisabled(String name) {
        return name != null && name.endsWith(DISABLED)
                ? name.substring(0, name.length() - DISABLED.length())
                : name;
    }

    private static String stripExtension(String name) {
        int at = name.lastIndexOf('.');
        return at > 0 ? name.substring(0, at) : name;
    }

    /** 递归删除临时目录，吞掉异常（清理不能盖掉主流程的原始异常）。 */
    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.<Path>reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException | RuntimeException ignored) {
                    // 单个删不掉不阻断其余清理
                }
            });
        } catch (IOException | RuntimeException ex) {
            System.err.println("[content] 清理临时目录失败（不影响主流程）: " + ex);
        }
    }
}
