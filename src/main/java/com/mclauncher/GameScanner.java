package com.mclauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 游戏列表的数据源：扫 {@code .minecraft/versions} 下的每个目录。
 *
 * <p>纯 Java、不碰 UI——界面调 {@link #scan()} 拿列表，剩下重命名 / 复制 / 删除都是
 * 给定 id 的操作，界面不拼路径、不知道磁盘长什么样。
 *
 * <p>三个设计取舍：
 * <ul>
 *   <li>**没游戏不是错误**：{@code versions} 不存在或空 → 空列表，界面直接进空态。
 *       第一次跑启动器本来就没有这个目录，当错误处理会让「正常」变成一片红字。</li>
 *   <li>**排序按最后修改时间倒序**：最近动过的排最上面，跟下载页「新版本在上」一个语义。
 *       按版本号排会把 {@code 26w06a} 和 {@code 1.21.11} 排到谁前面全看运气。</li>
 *   <li>**读不到 version.json 照样有这一行**：类型退成「正式版」、日期退回文件夹时间，
 *       比整行消失强——用户看得到有个东西在那儿。</li>
 * </ul>
 */
public final class GameScanner {

    /**
     * 游戏 id 允许什么字符。只允许这一套，是为了挡住路径穿越：
     * id 会直接拼进路径里用，{@code ../..} 或绝对路径都不该能从界面传进来。
     */
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** 1.1.3 流畅度：体积缓存判据 = 目录 mtime + 顶层子项数，两者都没变才复用旧值。 */
    private static final Map<Path, SizeCache> SIZE_CACHE = new ConcurrentHashMap<>();
    private record SizeCache(long stamp, int topCount, long bytes) {
    }

    /** 1.1.3 流畅度：scan 结果 TTL 缓存（400ms），消掉同一批界面刷新里的重复扫盘。 */
    private static final long SCAN_TTL_MS = 400L;
    private static volatile ScanCache scanCache;
    private record ScanCache(Path dir, long at, List<GameEntry> games) {
    }

    private GameScanner() {
        // 静态工具类
    }

    /** {@code .minecraft/versions}；目录还没建出来就返回不存在的路径，由调用方判断。 */
    public static Path versionsDir() {
        return Platform.minecraftDir().resolve("versions");
    }

    /** 扫一遍已装的游戏，最近修改的在最前面。任何读盘都失败都返回空列表，不让界面崩。 */
    public static List<GameEntry> scan() {
        return scan(versionsDir());
    }

    /** 1.1.3 流畅度：400ms TTL 缓存版；路径变了或超时才真扫。探针用隔离路径也能验证。 */
    private static List<GameEntry> scan(Path dir) {
        long now = System.currentTimeMillis();
        ScanCache hit = scanCache;
        if (hit != null && hit.dir().equals(dir) && now - hit.at() < SCAN_TTL_MS) {
            return hit.games();
        }
        List<GameEntry> games = scanUncached(dir);
        scanCache = new ScanCache(dir, now, games);
        return games;
    }

    /** 真正扫一遍磁盘（无缓存），TTL 缓存 miss 与探针反证都走这里。 */
    private static List<GameEntry> scanUncached(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        // 必须是可变 List：Stream.toList() 给的是不可变集合，下面还要按时间倒序排一遍
        List<Path> gameDirs = new ArrayList<>();
        try (var children = Files.list(dir)) {
            gameDirs.addAll(children.filter(Files::isDirectory).toList());
        } catch (IOException ex) {
            System.err.println("[games] 扫游戏目录失败，按空列表兜: " + ex);
            return List.of();
        }
        gameDirs.sort(Comparator.comparingLong(GameScanner::lastModified).reversed());

        List<GameEntry> out = new ArrayList<>();
        for (Path gameDir : gameDirs) {
            // v0.4.1：没有客户端 jar 的目录不算「装好的游戏」——
            // 只有一份 json（官方启动器留下的空壳）或者只剩 .jar.part 半成品的目录，
            // 点进去跑不起来，列在那儿就是一行假数据。磁盘上的文件夹一个都不动。
            if (!hasJar(gameDir)) {
                continue;
            }
            out.add(describe(gameDir));
        }
        return out;
    }

    /** 目录里有没有一个真下完的客户端 jar（{@code *.jar}，{@code .jar.part} 半成品不算）。 */
    private static boolean hasJar(Path gameDir) {
        try (var children = Files.list(gameDir)) {
            return children.anyMatch(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().endsWith(".jar"));
        } catch (IOException ex) {
            System.err.println("[games] 看不清 " + gameDir.getFileName() + " 里有没有 jar，"
                    + "按没有处理: " + ex);
            return false;
        }
    }

    /** 单个游戏的详情给界面复用：列表行、确认框、菜单都要拿它。 */
    public static GameEntry describe(String id) throws IOException {
        return describe(gameDir(id));
    }

    public static GameEntry describe(Path gameDir) {
        long size = sizeOf(gameDir);
        long modified = lastModified(gameDir);
        Path json = gameDir.resolve("version.json");
        String typeRaw = null;
        String timeRaw = null;
        if (Files.isRegularFile(json)) {
            try {
                String text = Files.readString(json, StandardCharsets.UTF_8);
                typeRaw = readField(text, "type");
                timeRaw = readField(text, "time");
            } catch (IOException | RuntimeException ex) {
                // version.json 读不了（被别的程序占着、编码怪）：按「读不到」处理，
                // 下面会用文件夹时间兜，不让它把整行干掉
                System.err.println("[games] 读 version.json 失败: " + ex);
            }
        }
        String id = gameDir.getFileName().toString();
        return new GameEntry(
                id,
                // 愚人节那几条官方也标成 snapshot（version.json 里就是 "snapshot"），
                // 认 id 才能把它们标成「愚人节版」而不是「快照版」（v0.4.2）
                GameEntry.GameType.detect(id, typeRaw),
                size,
                dateOf(timeRaw, modified));
    }

    // ---------------------------------------------------------------- 四种操作

    /** 重命名。真改文件夹名，校验过 id 合法性后调用 {@code Files.move}。 */
    public static void rename(String id, String newId) throws IOException {
        Path from = gameDir(id);
        checkId(newId);
        Path target = versionsDir().resolve(newId);
        if (Files.exists(target)) {
            throw new IOException("已经有叫「" + newId + "」的游戏了");
        }
        Files.move(from, target);
    }

    /** 复制：把整个游戏文件夹拷一份到新 id，原版一动不动。 */
    public static void copy(String id, String newId) throws IOException {
        Path from = gameDir(id);
        checkId(newId);
        Path target = versionsDir().resolve(newId);
        if (Files.exists(target)) {
            throw new IOException("已经有叫「" + newId + "」的游戏了");
        }
        Files.createDirectories(target);
        try (var stream = Files.walk(from)) {
            for (Path source : stream.toList()) {
                // 根 itself 在 walk 里也会出现一次，relativize 出来是空路径，直接 resolve 会抛
                Path destination = source.equals(from) ? target : target.resolve(from.relativize(source));
                copyOne(source, destination);
            }
        }
    }

    /** 删除：不直接删，走回收站（windows 走系统回收站，其它落软回收站）。 */
    public static void remove(String id) throws IOException {
        Path dir = gameDir(id);
        if (!Platform.recycle(dir)) {
            throw new IOException("删不掉，回收站没写进去：" + dir);
        }
    }

    /** 在游戏文件夹上开系统文件管理器。 */
    public static void openFolder(String id) throws IOException {
        Platform.reveal(gameDir(id));
    }

    // ---------------------------------------------------------------- 底层

    /**
     * 某个游戏的目录（不要求已存在）。
     *
     * <p>v0.4.7 起 {@link VersionJson} 要读 {@code <id>.json}，得拿同一套 id 合法性校验，
     * 别让它自己拼路径绕开 {@link #VALID_ID}。
     */
    public static Path gameDirFor(String id) throws IOException {
        checkId(id);
        return versionsDir().resolve(id);
    }

    /** 目录名拼路径；非法 id 直接挡在最前面，不让它走到文件系统那一层。 */
    private static Path gameDir(String id) throws IOException {
        checkId(id);
        Path dir = versionsDir().resolve(id);
        if (!Files.isDirectory(dir)) {
            throw new NoSuchFileException("没有这个游戏: " + id);
        }
        return dir;
    }

    private static void checkId(String id) throws IOException {
        if (id == null || !VALID_ID.matcher(id).matches()) {
            throw new IOException("游戏名只能用字母、数字、点、下划线和连字符（1-64 个）");
        }
    }

    /**
     * 从 version.json 里取**顶层**字符串字段；抠不到 / 解析失败返回空串。
     *
     * <p>0.8.6：原来是正则 {@code while(matcher.find())} 取「第一个同名 key」。
     * 官方完整版 version.json 的键序里 {@code logging.client.type="log4j2-xml"}
     * 排在顶层 {@code "type":"release"} 前面，装过加载器后
     * {@link VersionJson#save} 会把完整版写回 version.json，于是 26.3 被读成
     * {@code log4j2-xml}、再被兜底成「远古版」。这里改用项目自带的
     * {@link Json#parse(String)} 真解析顶层 Map，只认顶层字段。
     *
     * <p>容错：坏 JSON / 非对象 / 字段缺失一律返回空串，绝不抛异常——
     * 游戏列表少一行比显示错标签更糟，日期也会由 {@link #dateOf} 退回文件夹时间。
     */
    private static String readField(String json, String key) {
        return topField(parseTopLevel(json), key);
    }

    /** 解析一份 version.json 顶层对象；坏 JSON 返回空 Map，不往外抛。 */
    private static Map<String, Object> parseTopLevel(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = Json.parse(json);
            if (parsed instanceof Map<?, ?> raw) {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    out.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return out;
            }
        } catch (RuntimeException ex) {
            System.err.println("[games] version.json 不是合法 JSON，按读不到处理: " + ex);
        }
        return Map.of();
    }

    /** 只取顶层那个 key；值不是非空字符串就按空串（= 读不到）处理。 */
    private static String topField(Map<String, Object> fields, String key) {
        Object value = fields.get(key);
        return value instanceof String text && !text.isBlank() ? text : "";
    }

    /** 官方时间字符串 → 界面日期；解析不了就用文件夹时间，再不行给 null。 */
    private static String dateOf(String timeRaw, long fallbackMillis) {
        if (timeRaw != null) {
            try {
                return Instant.parse(timeRaw)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .toString();
            } catch (RuntimeException ex) {
                // DateTimeParseException 是 RuntimeException 的子类，一起 catch 编译不过
                System.err.println("[games] 时间解析不了，退回文件夹时间: " + ex);
            }
        }
        return fallbackMillis > 0 ? Platform.formatDate(fallbackMillis) : null;
    }

    private static long lastModified(Path dir) {
        try {
            return Files.getLastModifiedTime(dir).toMillis();
        } catch (IOException | RuntimeException ex) {
            return 0;
        }
    }

    /** 整棵树的字节数。某个文件读不到就跳过，别因为一个权限问题让整行体积变 0。 */
    private static long sizeOf(Path root) {
        // 1.1.3 流畅度：mtime + 顶层子项数都没变就直接复用，避免反复 walk 整个版本目录
        long stamp = lastModified(root);
        int topCount = topLevelCount(root);
        SizeCache hit = SIZE_CACHE.get(root);
        if (hit != null && hit.stamp() == stamp && hit.topCount() == topCount) {
            return hit.bytes();
        }
        long[] total = {0L};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException ex) {
                    System.err.println("[games] 数体积时跳过 " + file.getFileName() + ": " + ex);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException ex) {
            System.err.println("[games] 算体积失败，按 0 显示: " + ex);
        }
        long bytes = total[0];
        SIZE_CACHE.put(root, new SizeCache(stamp, topCount, bytes));
        return bytes;
    }

    /** 顶层子项数：与 mtime 一起做体积缓存的失效判据，只列一层很便宜。 */
    private static int topLevelCount(Path root) {
        try (var stream = Files.list(root)) {
            return (int) stream.count();
        } catch (IOException | RuntimeException ex) {
            return -1;
        }
    }

    private static void copyOne(Path source, Path destination) throws IOException {
        if (Files.isDirectory(source)) {
            Files.createDirectories(destination);
            return;
        }
        Files.copy(source, destination);
    }

    /** 体积的人话：不到 1 MB 就给 KB，免得全部显示成「0 MB」。 */
    public static String sizeText(Long sizeBytes) {
        if (sizeBytes == null) {
            return "—";
        }
        long size = sizeBytes;
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB", size / 1024.0);
        }
        return String.format(Locale.ROOT, "%.0f MB", size / (1024.0 * 1024.0));
    }
}
