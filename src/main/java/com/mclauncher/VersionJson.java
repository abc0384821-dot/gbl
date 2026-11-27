package com.mclauncher;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
 * 一个版本的**元信息**：真正启动游戏要的东西都从 {@code version.json} 里来。
 *
 * <p>v0.4.7 踩到的第一个坑：现在 Mojang 装在磁盘上的 {@code version.json} 是**精简版**
 * （146 字节，只有 {@code id/jar/mainClass/time/type}）——libraries、assetIndex、javaVersion
 * 全都不在里面。照着它启动，缺 114 个依赖库、拿不到 Java 版本，游戏起不来还看不出为什么。
 * 所以这里按「本地 → 缓存 → 网络」三级补齐，并把补到的完整版缓存下来，
 * **离线第二次启动也用得上**（缓存目录 {@code ~/.gbl/cache/versions/}）。
 *
 * <p>纯服务层、不碰 UI：读不到、拉不到都不要抛出去把界面搞黄，
 * 缺什么记进 {@link #problems()} 由调用方说人话。
 *
 * <p>不引 JSON 依赖：用 {@link Json}（零依赖解析器），和 {@link VersionManifest} 一个路子。
 */
public final class VersionJson {

    /** 远程完整版元信息缓存到这个目录下，按版本 id 分文件。 */
    private static final Path CACHE_DIR =
            Paths.get(System.getProperty("user.home"), ".gbl", "cache", "versions");

    /**
     * 补齐元信息的网络客户端（0.9.9）：连不上 8 秒放弃、跟随 302。
     *
     * <p>启动期联网补齐必须整体有上限——之前用 {@code HttpClient.newHttpClient()} 无任何
     * 超时，主机 TCP accept 后挂起不发响应时 {@code send} 永久阻塞，启动卡死且无自救。
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private VersionJson() {
        // 静态工具类
    }

    /**
     * 补齐后的版本元信息。字段全部可能为 null——**缺哪个字段由调用方自己判**，
     * 这里不猜默认值（猜错了比直接说「缺」更难查。
     */
    public record Meta(String id,
                       String assetIndexId,
                       String assetIndexUrl,
                       Integer javaMajor,
                       List<Object> libraries,
                       String mainClass,
                       List<Object> jvmArgs,
                       List<Object> gameArgs,
                       String inheritsFrom) {

        /** 除了「认不出来的 JSON」之外的实话：这一版缺了哪些启动必需项。 */
        List<String> problems() {
            List<String> out = new ArrayList<>();
            if (libraries == null || libraries.isEmpty()) {
                // 0.8.6：给用户一句能照着做的话，别只说「多半是没补齐」
                out.add("这个版本还缺一些运行文件。联网后重新启动一次，"
                        + "或去「下载」页把这个版本重新装一次");
            }
            if (assetIndexId == null) {
                out.add("这个版本没有资源索引（assetIndex），拿不到材质/音效资源");
            }
            if (mainClass == null) {
                out.add("这个版本没有主类（mainClass），不知道该跑哪个类");
            }
            return out;
        }
    }

    /**
     * 取某个版本的元信息。
     *
     * @param id 版本 id（{@link GameScanner} 扫出来的那个文件夹名）
     * @return 元信息；连本地 json 都读不到时返回 null（由调用方说「这个游戏装得不全」）
     */
    public static Meta load(String id) throws IOException {
        Map<String, Object> merged = raw(id);
        return merged == null ? null : toMeta(id, merged, null);
    }

    /**
     * 取某个版本的元信息，并解析 {@code inheritsFrom}（父版本继承）。
     *
     * <p>v0.8 新增：Forge / NeoForge 生成的派生 profile 就是 {@code inheritsFrom} 型——
     * 它自己只带 Forge 那 29 个库 + 一组 jvm/game 参数，客户端 jar 和 LWJGL 等基础库全在父版本里。
     * 不把父版本的库并进来，拼出来的 classpath 就没有 LWJGL，游戏起不来。
     *
     * <p>合并规则（对齐官方 launcher / HMCL）：父库在前、子库在后，同名库子覆盖父；
     * 其余字段（assetIndex / javaVersion / mainClass / arguments）子版本有就用子的，没有回退父的。
     */
    public static Meta loadResolved(String id) throws IOException {
        Map<String, Object> merged = raw(id);
        if (merged == null) {
            return null;
        }
        Map<String, Object> resolved = resolveInheritance(merged);
        return toMeta(id, resolved, str(merged.get("inheritsFrom")));
    }

    /**
     * 只读本地那份（{@code <id>.json}，缺则 {@code version.json}），读不到返回 null。
     *
     * <p>v0.7.0 新增：加载器安装 / mrpack 导入要「基于完整版合并」，得先看清磁盘上现在是什么，
     * 又不能顺手触发网络补齐——所以给一个纯本地的出口。
     */
    public static Map<String, Object> local(String id) throws IOException {
        return readLocal(id);
    }

    /**
     * 完整原始 JSON Map：本地完整 → 缓存（过期重拉）→ 网络；全失败返回本地那份
     * （调用方自己判它是不是精简版——{@code libraries} / {@code assetIndex} 缺字段就是）。
     *
     * <p>v0.7.0 从 {@link #load} 里三级补齐那段原样提出来：{@code load} 改成
     * {@code raw → toMeta}，原有三级、过期重拉、坏缓存重拉语义一点没动。
     */
    public static Map<String, Object> raw(String id) throws IOException {
        Map<String, Object> local = readLocal(id);
        if (local == null) {
            return null;
        }
        if (!isThin(local)) {
            return local;   // 本地就是完整版（装过加载器的版本 JSON 全量落盘），不用补
        }
        // 本地精简 → 去缓存找完整版；缓存也没有就现拉一次（离线时拉不到，用本地那份顶着）
        Map<String, Object> full = readCache(id);
        // 缓存**过期**也重新拉一次（拉不到就用旧的，别为元信息卡住启动）：
        // 库版本是按这份缓存认的，缓存停在几天前，就会照着旧声明去匹配库——
        // 26.4-snapshot-2 崩在 `NoSuchMethodError: TracyClient.createSectionCategory`
        // （13:25 两次都是）根儿就在这儿：那份缓存里 jtracy 还写着 1.0.37，
        // 于是补回来的库跟客户端对不上（v0.5）
        if (full != null && cacheStale(id)) {
            Map<String, Object> fresh = fetchRemote(id);
            if (fresh != null) {
                full = fresh;
            }
        }
        if (full == null) {
            full = fetchRemote(id);
        }
        if (full == null) {
            // 0.8.6：干净机器没有缓存、又断网时，这里会拿精简版顶着，
            // prepare 随后被 meta.problems() 拦下。留一行实话方便导出诊断时定位。
            System.err.println("[versionjson] 「" + id
                    + "」元信息不完整，需联网补齐（本地只有精简版，缓存与网络都没拿到完整版）");
            return local;
        }
        return full;
    }

    /**
     * 把完整版 JSON 写回游戏目录：{@code version.json} 与 {@code <id>.json} 同一内容。
     *
     * <p>v0.7.0 新增（加载器安装 / mrpack 导入的落盘点）：tmp + move，不截断直写——
     * 写到一半断电不该留下半截 JSON，那会让整个版本「读不了元信息」。
     */
    public static void save(String id, Map<String, Object> full) throws IOException {
        Path dir = GameScanner.gameDirFor(id);
        Files.createDirectories(dir);
        String text = Json.write(full);
        writeAtomically(dir.resolve(id + ".json"), text);
        writeAtomically(dir.resolve("version.json"), text);
    }

    /** 单文件 tmp + move：内容是一次性写好的，改名这一步不会写出半截。 */
    private static void writeAtomically(Path target, String text) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ex) {
            // 这套文件系统可能不认覆盖式改名（或目标被占）：删掉临时文件别留垃圾，
            // 把真因抛给调用方说人话
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException | RuntimeException ignored) {
                // 删不掉就留着，下次写同名覆盖
            }
            throw ex;
        }
    }

    /** 已经落盘/下载好的完整版元信息；拿不到返回 null。 */
    public static Meta cached(String id) {
        Map<String, Object> full = readCache(id);
        if (full == null) {
            return null;
        }
        Map<String, Object> resolved = resolveInheritance(full);
        return toMeta(id, resolved, str(full.get("inheritsFrom")));
    }

    // ---------------------------------------------------------------- 读本地

    /** 读游戏目录下的 {@code <id>.json}，读不到再退 {@code version.json}。 */
    private static Map<String, Object> readLocal(String id) throws IOException {
        Path dir = GameScanner.gameDirFor(id);
        return readAny(dir.resolve(id + ".json"), dir.resolve("version.json"));
    }

    /** 优先第一个存在且能解析的文件，都没有返回 null（= 这个游戏没装全）。 */
    private static Map<String, Object> readAny(Path... candidates) {
        for (Path file : candidates) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                Object parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
                if (parsed instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) parsed;
                    return map;
                }
            } catch (IOException | RuntimeException ex) {
                System.err.println("[versionjson] 读不了 " + file.getFileName() + ": " + ex);
            }
        }
        return null;
    }

    /** 是不是精简版（缺启动必需字段）：全都在就说明这份 json 已经够用了。 */
    private static boolean isThin(Map<String, Object> map) {
        return !(map.containsKey("libraries") && map.get("libraries") != null)
                || !(map.containsKey("assetIndex") && map.get("assetIndex") != null);
    }

    // ---------------------------------------------------------------- 缓存 / 网络

    private static Map<String, Object> readCache(String id) {
        Map<String, Object> map = readAny(CACHE_DIR.resolve(id + ".json"));
        if (map == null) {
            return null;
        }
        // 缓存的是补齐后的完整版，别再被 isThin 判成精简版多拉一次网络。
        // 但「有这个键」不算数——v0.4.7 第一版 write() 把嵌套结构写成字符串，
        // 落盘的缓存看着键也在、值其实是条 3 万字符的字符串，照读就缺 114 个库。
        // 值不是数组一律当这份缓存坏了，宁可重拉一次也别拿它启动。
        return map.get("libraries") instanceof List ? map : null;
    }

    /** 完整版元信息缓存多久算过期：快照版天天变，放太久就会照着旧声明配库（见 {@link #load}）。 */
    private static final long METADATA_TTL_MS = 6 * 60 * 60 * 1000L;

    /** 缓存这份元信息是不是已经过期（读不到时间就算过期）。 */
    private static boolean cacheStale(String id) {
        try {
            return System.currentTimeMillis() - Files.getLastModifiedTime(CACHE_DIR.resolve(id + ".json"))
                    .toMillis() > METADATA_TTL_MS;
        } catch (IOException | RuntimeException ex) {
            return true;
        }
    }

    /** 把补全的元信息落盘：下次离线启动直接读它，不用再问网络。 */
    public static boolean cache(String id, Map<String, Object> full) {
        try {
            Files.createDirectories(CACHE_DIR);
            Files.writeString(CACHE_DIR.resolve(id + ".json"), Json.write(full));
            return true;
        } catch (IOException | RuntimeException ex) {
            System.err.println("[versionjson] 缓存完整元信息失败（下次会再拉一次）: " + ex);
            return false;
        }
    }

    /**
     * 从 Mojang 拉完整版 {@code version.json}。
     *
     * <p>地址先在本项目 {@link VersionManifest} 的清单里找（它已经处理了离线兜缓存），
     * 找到就依它给的 url 去拉；实在找不到就按官网固定地址试一次。
     *
     * <p>v0.7.0（架构 §1.6）：url 先按下载源改写，镜像拉不到回落官方一次。
     */
    private static Map<String, Object> fetchRemote(String id) {
        // 全量清单里查这个版本的下载地址；读的是缓存（allView 走缓存兜），离线也拿得到
        String url = null;
        try {
            for (VersionManifest.OfficialVersion v : VersionManifest.allView()) {
                if (v.id().equals(id)) {
                    url = v.url();
                    break;
                }
            }
        } catch (RuntimeException ex) {
            // allView 只抛运行时异常（离线/坏缓存它自己兜），不要写 catch IOException——
            // 受检异常不在 try 抛出范围内是编译错误
            System.err.println("[versionjson] 版本清单读不到，跳过远程补齐: " + ex);
        }
        if (url == null || url.isBlank()) {
            return null;
        }
        // 先镜像、失败回落官方一次（架构 §1.6）；官方源下两个地址相同，不会多跑一趟
        Map<String, Object> map = fetchAndParse(id, Mirror.rewrite(url));
        if (map == null) {
            String mirrored = Mirror.rewrite(url);
            if (!mirrored.equals(url)) {
                System.err.println("[versionjson] 镜像没拉到，回落官方地址再试一次");
                map = fetchAndParse(id, url);
            }
        }
        return map;
    }

    /**
     * 拉一份完整版并解析、落缓存。
     *
     * <p>拉不到、非 2xx、解析坏、拉回来还是精简版——一律返回 null（用本地那份兜），
     * 不往外抛：补齐失败不该把启动 / 安装流程带黄。
     */
    private static Map<String, Object> fetchAndParse(String id, String url) {
        String text;
        try {
            HttpResponse<String> response = CLIENT.send(
                    HttpRequest.newBuilder(java.net.URI.create(url))
                            .timeout(java.time.Duration.ofSeconds(15))
                            .header("User-Agent", GameDownloader.AGENT)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.err.println("[versionjson] 完整元信息地址返回 HTTP "
                        + response.statusCode() + ": " + url);
                return null;
            }
            text = response.body();
        } catch (IOException | InterruptedException | RuntimeException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("[versionjson] 拉完整元信息失败（用本地精简版兜）: " + url + " -> " + ex);
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) Json.parse(text);
            if (isThin(map)) {
                return null;
            }
            cache(id, map);
            return map;
        } catch (RuntimeException ex) {
            System.err.println("[versionjson] 完整元信息解析不了（用本地精简版兜）: " + ex);
            return null;
        }
    }

    // ---------------------------------------------------------------- 组装

    /** 从一份（已继承合并过的）version.json 里抠出启动要的字段；缺哪个留哪个 null。 */
    private static Meta toMeta(String id, Map<String, Object> map, String inheritsFrom) {
        Object assetIndex = map.get("assetIndex");
        String indexId = null;
        String indexUrl = null;
        if (assetIndex instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> ai = (Map<String, Object>) assetIndex;
            indexId = str(ai.get("id"));
            indexUrl = str(ai.get("url"));
        }
        Object javaVersion = map.get("javaVersion");
        Integer javaMajor = null;
        if (javaVersion instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> jv = (Map<String, Object>) javaVersion;
            Object major = jv.get("majorVersion");
            if (major instanceof Number) {
                javaMajor = ((Number) major).intValue();
            } else if (major instanceof String) {
                try {
                    javaMajor = Integer.parseInt(((String) major).trim());
                } catch (NumberFormatException ignored) {
                    // 版本号写成别的形式（比如 "21")认不出来就当没要求，交给上层按实际版本比对
                }
            }
        }
        Object args = map.get("arguments");
        Map<String, Object> jvm = new LinkedHashMap<>();
        Map<String, Object> game = new LinkedHashMap<>();
        // 模式匹配带上类型参数，不然 args.get(...) 解析不到（args 声明是 Object）
        if (args instanceof Map<?, ?> rawArgs) {
            collect(jvm, rawArgs.get("jvm"));
            collect(game, rawArgs.get("game"));
        }
        // 老版本（1.13 之前）没有 arguments，参数全是裸字符串写在 argv 里。
        //
        // 0.9.2 r2 修复（用户报 1.8 启动失败：Unrecognized option --username）：
        // minecraftArguments 里是 --username / --version / --gameDir / --uuid ... 这些
        // **游戏参数**，必须落在 mainClass 之后；此前误收进 jvm map，GameLauncher
        // 又会把 jvm 列表展开到 -cp / mainClass 之前，JVM 于是把 --username 当成
        // 不认识的长选项，直接拒绝创建虚拟机并退出码 1。
        // 老版本真正需要的 -Djava.library.path / -cp 由 GameLauncher 在
        // jvm.isEmpty() 时自行补齐，不在这串字符串里，所以整串归 game 是正确的。
        if (jvm.isEmpty() && game.isEmpty()) {
            Object legacy = map.get("minecraftArguments");
            collectRaw(game, legacy);
            if (legacy instanceof String s && !s.isBlank()) {
                // 一次性诊断：真机日志里看到这行，就说明老版本走的是修好的分支。
                // 同时也是 0.9.2 r2 的当版指纹（字符串会进 class 常量池）。
                System.out.println("[versionjson] 0.9.2-r2 老格式参数已归入游戏参数段: " + id);
            }
        }
        return new Meta(id, indexId, indexUrl, javaMajor, list(map.get("libraries")),
                str(map.get("mainClass")), List.copyOf(jvm.values()), List.copyOf(game.values()),
                inheritsFrom);
    }

    /**
     * 解析 {@code inheritsFrom}：把父版本的库并进来（子覆盖父），其余字段子优先。
     * 父版本递归解析自己的继承链；父读不到就跳过合并、按子版本原样启动（顶多缺库）。
     */
    private static Map<String, Object> resolveInheritance(Map<String, Object> child) {
        Object rawParent = child.get("inheritsFrom");
        if (!(rawParent instanceof String pid) || pid.isBlank()) {
            return child;
        }
        Map<String, Object> parent;
        try {
            parent = raw(pid);
        } catch (IOException ex) {
            System.err.println("[versionjson] 读父版本「" + pid + "」失败，跳过继承合并: " + ex);
            return child;
        }
        if (parent == null) {
            System.err.println("[versionjson] 找不到父版本「" + pid + "」（子版本继承它），"
                    + "跳过继承合并，按子版本原样启动");
            return child;
        }
        Map<String, Object> base = new LinkedHashMap<>(parent);   // 以父为基底
        // 库：父在前、子在后，同名子覆盖父（防重复收）
        Map<String, Object> libs = new LinkedHashMap<>();
        for (Object l : list(parent.get("libraries"))) {
            libs.put(libName(l), l);
        }
        for (Object l : list(child.get("libraries"))) {
            libs.put(libName(l), l);
        }
        base.put("libraries", new ArrayList<>(libs.values()));
        // 其余字段：子版本有就用子的（覆盖父），没有就留父的
        for (Map.Entry<String, Object> e : child.entrySet()) {
            String k = e.getKey();
            if ("libraries".equals(k) || "inheritsFrom".equals(k)) {
                continue;
            }
            base.put(k, e.getValue());
        }
        return base;
    }

    /** 库的去重键：库条目是 Map 取 {@code name}，是裸字符串原样取。 */
    private static String libName(Object lib) {
        if (lib instanceof Map<?, ?> m) {
            Object name = m.get("name");
            return name instanceof String ? (String) name : String.valueOf(m);
        }
        return String.valueOf(lib);
    }

    /**
     * 把 {@code arguments.jvm / arguments.game} 那一串原样收进来。
     *
     * <p>**一定要把元素打散**，别把整个数组裹成一个 list 元素：裹起来之后
     * 下游展开时看到的是「list 里套 list 里套 map」，命中最里面那个
     * {@code String.valueOf(map)}，会把 {@code {rules=..., value=...}} 整串当成一条
     * 命令行参数交给 java（v0.4.7 实测就是这么崩的）。
     */
    private static void collect(Map<String, Object> out, Object raw) {
        if (raw instanceof List<?> items) {
            out.put("list", new ArrayList<>(items));
        }
    }

    /** 旧格式的 {@code minecraftArguments} 是一个拼好的长串：按空格切开备用。 */
    private static void collectRaw(Map<String, Object> out, Object raw) {
        if (!(raw instanceof String text) || text.isBlank()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        for (String part : text.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        out.put("raw", parts);
    }

    private static String str(Object raw) {
        return raw instanceof String text && !text.isBlank() ? text : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object raw) {
        return raw instanceof List<?> items ? List.copyOf(items) : null;
    }
}
