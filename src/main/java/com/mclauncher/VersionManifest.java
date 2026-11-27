package com.mclauncher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 官方版本清单：从 Mojang 的启动器元数据里拉 Minecraft 全版本列表。
 *
 * <p>清单在 {@code launchermeta.mojang.com/mc/game/version_manifest.json}，
 * 一个文件里有 900+ 条（正式版、快照、远古 alpha/beta），每条带 id、类型、发布时间、客户端地址。
 * 只拉这一个文件就够列出版本——真正的 jar 要等用户真要下载时再按每条的 {@code url} 去拿，
 * 所以这里是「能看见、不能下」这一版的正确停点。
 *
 * <p>三件事必须说清楚，否则界面会骗人：
 * <ul>
 * <li><b>拉不到不等于没有版本。</b>离线、代理、DNS 挂了都要落到{@link State#CACHED}或
 *     {@link State#OFFLINE}，拿上一次成功的东西继续显示，别把列表清空成「无数据」。</li>
 * <li><b>快照占了 753/917。</b>全铺开首屏全是 1.3 那年的老快照，{@link #defaultView()}
 *     只放正式版 + 最近 {@value #SNAPSHOT_WINDOW} 个快照，愚人节和远古版则全量保留，
 *     剩下的老快照交给「显示全部」。</li>
 * <li><b>「有新版本」是拿基线比出来的。</b>{@link #baselineIds()} 存着上次见过的所有 id，
 *     新拉的清单里多出来的那些才是「官方更新了」，用于界面上的「新」徽标。</li>
 * </ul>
 *
 * <p>这一层是纯 Java，不碰任何 JavaFX：拉数据和渲染不该绑一起，
 * 以后真做下载器也是同一份清单接着用。
 */
public final class VersionManifest {

    /** 官方版本清单地址。只有这一个外部地址，换源改这一行。 */
    private static final String MANIFEST_URL =
            "https://launchermeta.mojang.com/mc/game/version_manifest.json";

    /** 默认视图里最多放多少个快照。快照多但都在变，露最近这几个够用。 */
    public static final int SNAPSHOT_WINDOW = 30;

    /** 缓存文件。和 {@code config.json} 同级目录，都是 {@code ~/.gbl/} 下的东西。 */
    private static final Path CACHE =
            Paths.get(System.getProperty("user.home"), ".gbl", "cache", "version-manifest.json");

    /**
     * 一个对象块：{@code {"id":"26.3","type":"release","url":"...","time":"..."}}。
     *
     * <p>清单里没有嵌套对象，所以 {@code [^{}]*} 就够切出每条，不用写完整的 JSON 解析器；
     * 块里再按 key 取字段（同 GameScanner 的做法，别按出现顺序硬认）。
     */
    private static final Pattern ENTRY = Pattern.compile("\\{[^{}]*\"id\"[^{}]*\\}");
    private static final Pattern FIELD = Pattern.compile("\"([a-zA-Z]+)\"\\s*:\\s*\"([^\"]*)\"");

    /**
     * 一条官方版本。kind 是拿清单 type 换过的展示用分类，发布日期是 ISO-8601 串。
     *
     * <p><b>{@code released} 取的是清单里的 {@code releaseTime}，不是 {@code time}。</b>
     * 清单里有两个时间：{@code releaseTime} 是这版真正发行的日子（1.20.2 是 2023-09-20），
     * {@code time} 是它在清单里被登记的日子——Mojang 会把老版本的 {@code time} 成批重打，
     * 一拉最新清单，2023 年的 1.20.2 的 {@code time} 就变成「今天」，
     * 按它排会把 1.20.2 顶到 26.3 上面、日期也显示成今天（v0.3.5 自测首行读到的就是 1.20.2）。
     * 排序和行上那行日期都只用 {@code releaseTime}，缺了才退回 {@code time}。
     */
    public record OfficialVersion(String id, Kind kind, Instant released, String url) {
        /** 行上显示的那个时间：发行日；拿不到就退回清单登记日。 */
        public Instant shown() {
            return released;
        }
    }

    /** 官方 type 到展示分类的映射。愚人节那几条官方也归在远古里，这里单独挑出来好认。 */
    public enum Kind {
        RELEASE("正式版"),
        SNAPSHOT("快照"),
        APRIL("愚人节"),
        LEGACY("远古版");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 这一趟清单是从哪来的，界面上照着说人话。 */
    public enum State {
        /** 刚联网拉到最新的。 */
        ONLINE,
        /** 拉失败了，屏幕上的是上次存下来的。 */
        CACHED,
        /** 一次都没成功过，连缓存都没有。 */
        OFFLINE,
        /** 拉过、也存过，但这次连不上也没读出缓存。 */
        FAILED
    }

    /** 一次取清单的结果：版本列表 + 来源 + 拿到手的时间 + 官方新增了哪些。 */
    public record Result(List<OfficialVersion> versions,
                         State state,
                         Instant at,
                         String detail,
                         Set<String> fresh) {
        /** 界面上那句人话，状态条直接用。 */
        public String summary() {
            int n = versions.size();
            switch (state) {
                case ONLINE:
                    return fresh.isEmpty()
                            ? "已是最新 · 共 " + n + " 个版本"
                            : "官方新增 " + fresh.size() + " 个版本";
                case CACHED:
                    return "离线 · 用 %s 的缓存，共 %d 个版本".formatted(
                            at == null ? "上次" : at, n);
                case OFFLINE:
                    return "还没有缓存，连不上官方服务器";
                default:
                    return "取清单失败，稍后再试";
            }
        }
    }

    private VersionManifest() {
        // 静态工具类，别让人 new 出来
    }

    // ------------------------------------------------------------------ 拉

    /**
     * 联网拉一次清单，顺手落缓存。
     *
     * <p>拉不到不抛异常——界面上「检查更新」转圈完了总得有个说法，
     * 抛出去只会变成一个报错框。失败时按 {@link State#CACHED}/{@link State#OFFLINE} 兜。
     */
    public static Result fetch() {
        String text;
        try {
            text = download();
        } catch (IOException | RuntimeException ex) {
            System.err.println("[manifest] 拉官方清单失败: " + ex);
            // 连不上不可怕，可怕的是把列表清空让用户以为自己没有版本可下
            List<OfficialVersion> cached = readCache();
            Instant at = cachedAt();
            if (!cached.isEmpty()) {
                return new Result(cached, State.CACHED, at, "连接失败，用缓存", Set.of());
            }
            return new Result(List.of(), State.OFFLINE, null, "连接失败", Set.of());
        }

        // 空判断必须落在 parsed 上：205 条内嵌常量不能掩盖「官方改了 JSON 结构」，
        // 否则解析失败时 merge 出来的 205 条会让下面这段回落缓存保护永远不触发。
        List<OfficialVersion> parsed = parse(text);
        if (parsed.isEmpty()) {
            // 解析不出东西多半是官方改了结构，别拿空列表把用户原来的缓存顶掉
            List<OfficialVersion> cached = readCache();
            if (!cached.isEmpty()) {
                return new Result(cached, State.CACHED, cachedAt(), "响应无法解析，用缓存", Set.of());
            }
            return new Result(List.of(), State.FAILED, null, "响应无法解析", Set.of());
        }
        // 把 HMCL 未列出特殊版本（205 条，自带 version json url）并入官方清单
        List<OfficialVersion> versions = UnlistedVersions.merge(parsed);

        Set<String> known = baselineIds();
        writeCache(text, versions);
        Set<String> fresh = known.isEmpty() ? Set.of() : difference(versions, known);
        return new Result(versions, State.ONLINE, Instant.now(), "已同步官方清单", fresh);
    }

    /**
     * 真发一次 HTTP。8 秒连不上就别再等，15 秒读不完当失败——都没结果就别占着界面。
     *
     * <p>v0.7.0（架构 §1.6）：地址先按下载源改写（开了 BMCLAPI 镜像才是镜像地址），
     * 镜像拉不到回落官方地址再试一次。官方源下两个地址相同，不会多跑一趟。
     */
    private static String download() throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        String url = Mirror.rewrite(MANIFEST_URL);
        String text = get(client, url);
        if (text == null && !url.equals(MANIFEST_URL)) {
            System.err.println("[manifest] 镜像没拉下来，回落官方地址再试一次");
            text = get(client, MANIFEST_URL);
        }
        if (text == null) {
            throw new IOException("官方清单连不上（镜像也试过了）");
        }
        return text;
    }

    /** 发一次 GET 拿文本；非 2xx / 网络错误返回 null（由调用方决定要不要回落）。 */
    private static String get(HttpClient client, String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("User-Agent", GameDownloader.AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.err.println("[manifest] 清单地址返回 HTTP " + response.statusCode() + ": " + url);
                return null;
            }
            return response.body();
        } catch (InterruptedException ex) {
            // 中断必须打回自己的标志位再包成 IOException 往外抛，吞掉会让上层永远等不到结果
            Thread.currentThread().interrupt();
            throw new IOException("拉取官方清单被中断", ex);
        }
    }

    // ------------------------------------------------------------------ 解

    /**
     * 把 JSON 文本切成版本列表，按发布时间倒序（最新在最上）。
     *
     * <p>拿不到时间的一律排到最前：没有发布时间的条目基本是官方漏填的，
     * 比悄悄沉到底部更容易被看见，也省得界面上出现一批排不进序的乱序行。
     */
    public static List<OfficialVersion> parse(String json) {
        List<OfficialVersion> versions = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return versions;
        }
        for (Matcher block = ENTRY.matcher(json); block.find();) {
            String id = null;
            String type = null;
            String time = null;
            String released = null;
            String url = null;
            for (Matcher f = FIELD.matcher(block.group()); f.find();) {
                switch (f.group(1)) {
                    case "id" -> id = f.group(2);
                    case "type" -> type = f.group(2);
                    case "time" -> time = f.group(2);
                    case "releaseTime" -> released = f.group(2);
                    case "url" -> url = f.group(2);
                    default -> { /* 清单里还有别的字段，不认 */ }
                }
            }
            if (id == null || id.isBlank()) {
                continue;   // 一条都拼不出来，整条丢，别往界面里塞无名行
            }
            // 排序与展示都走发行日；发行日缺失（老条目有，但别指望永远有）才退回登记日
            Instant when = parseTime(released);
            if (when == null) {
                when = parseTime(time);
            }
            versions.add(new OfficialVersion(id, kindOf(id, type), when, url));
        }
        versions.sort(Comparator.comparing(OfficialVersion::released,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .reversed());
        return versions;
    }

    /**
     * 官方 type 值 → 展示分类。
     *
     * <p>0.8.6：与游戏列表 {@link GameEntry.GameType#from(String)} 收敛到
     * {@link VersionKinds} 这一张表，两边兜底一致（未知 type → 正式版，不再是远古版）。
     * 愚人节判据仍在 {@code VersionKinds.detect} → {@link GameEntry#isAprilFools}，
     * 下载页与游戏列表继续共用同一个 id 判据。
     *
     * <p>排序（{@link #parse} 的 {@code releaseTime} 倒序）不受影响，
     * 不按版本号字典序排，也不按版本号猜大类。
     */
    private static Kind kindOf(String id, String type) {
        return toKind(VersionKinds.detect(id, type));
    }

    /** 归一化分类 → 本枚举。与 {@link GameEntry.GameType} 的换算完全对称。 */
    private static Kind toKind(VersionKinds.Standard kind) {
        return switch (kind) {
            case RELEASE -> Kind.RELEASE;
            case SNAPSHOT -> Kind.SNAPSHOT;
            case APRIL -> Kind.APRIL;
            case LEGACY -> Kind.LEGACY;
        };
    }

    /** 官方时间 sometimes 缺失，缺失给 null 而不是吞掉这条。 */
    private static Instant parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            System.err.println("[manifest] 时间解析失败，按无时间处理: " + raw);
            return null;
        }
    }

    // ------------------------------------------------------------------ 视图

    /**
     * 默认视图：正式版全量 + 最近 {@value #SNAPSHOT_WINDOW} 个快照 + 全部愚人节 + 全部远古版，最新的在前。
     *
     * <p>快照默认只露最近这一小截，是因为 917 条里 753 条是快照、大半是十几年前的老东西；
     * 愚人节和远古版（R-05）则全量露在默认视图里，跟 HMCL 一致；想要全量快照就走
     * {@link #allView()}。
     */
    public static List<OfficialVersion> defaultView() {
        List<OfficialVersion> all = readCache();
        return viewOf(all);
    }

    /** 全部版本（含远古 alpha/beta），同一个排序规则。 */
    public static List<OfficialVersion> allView() {
        return readCache();
    }

    /**
     * 按默认规则从任意一份清单里裁：{@link Kind#RELEASE} 全量 + 最近 {@value #SNAPSHOT_WINDOW} 个快照
     * + 全部 {@link Kind#APRIL}（愚人节）+ 全部 {@link Kind#LEGACY}（远古 alpha/beta）。
     *
     * <p>愚人节和远古版都全量进默认视图——R-05 要求下载页/安装对话框的默认列表像 HMCL 一样
     * 把这几类都露出来，而不是只藏在「显示全部」里。快照仍然只露最近一小截（917 条里 753 条是
     * 快照，大半是十几年前的老东西，全铺开会把首屏顶成 1.3 那年的老快照）；想要全量快照就走
     * {@link #allView()}。
     */
    public static List<OfficialVersion> viewOf(List<OfficialVersion> all) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<OfficialVersion> releases = new ArrayList<>();
        List<OfficialVersion> snapshots = new ArrayList<>();
        List<OfficialVersion> aprils = new ArrayList<>();
        List<OfficialVersion> legacies = new ArrayList<>();
        for (OfficialVersion v : all) {
            switch (v.kind()) {
                case RELEASE -> releases.add(v);
                case SNAPSHOT -> snapshots.add(v);
                case APRIL -> aprils.add(v);     // 愚人节：默认视图也全量露
                case LEGACY -> legacies.add(v);   // 远古版：默认视图也全量露
            }
        }
        // 快照本来就是按时间倒序排好的，取前 N 个就是「最近 N 个」
        List<OfficialVersion> out = new ArrayList<>(releases);
        out.addAll(snapshots.subList(0, Math.min(SNAPSHOT_WINDOW, snapshots.size())));
        out.addAll(aprils);
        out.addAll(legacies);
        // 再按发行日整体排一遍：「正式版一堆、快照一路跟在末尾」和「显示全部」就不是同一口径了，
        // 首行一会儿是 26.3 一会儿是 26.4-snapshot-2，看着像排序坏了。
        // 两条视图都用同一条时间线（v0.3.5 首跑默认视图首行读出来的是 26.3）
        out.sort(Comparator.comparing(OfficialVersion::released,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .reversed());
        return out;
    }

    // ------------------------------------------------------------------ 缓存与基线

    /** 上次见过的所有版本 id，用来判「官方这次多了什么」。 */
    public static Set<String> baselineIds() {
        String json = readText(CACHE);
        if (json == null) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (OfficialVersion v : readCache()) {
            ids.add(v.id());
        }
        return ids;
    }

    /**
     * 把基线钉成给定的 id 集合——**第一次拿到清单时该自动调一次**，
     * 否则首屏就会把整个历史版本全标成「新」，那不叫更新、叫噪音。
     *
     * <p>也留给自测：钉一个只有一条的基线，再拉一次，就能验「检出新增」这条链路真的通。
     */
    public static void primeBaseline(Collection<String> ids) {
        try {
            Path dir = CACHE.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            String json = readText(CACHE);
            String body;
            if (json == null || json.isBlank()) {
                body = "[]";
            } else {
                body = json;
            }
            // 基线就是缓存里那批版本，改成用户指定的这批就行，发布时间/类型一并留着
            List<OfficialVersion> keep = new ArrayList<>();
            Set<String> want = new LinkedHashSet<>(ids);
            for (OfficialVersion v : readCache()) {
                if (want.contains(v.id())) {
                    keep.add(v);
                }
            }
            if (keep.isEmpty()) {
                // 钉的 id 一个都不在缓存里（自测常这么干），就只存 id，渲染层按 id 认
                keep = new ArrayList<>();
                for (String id : want) {
                    keep.add(new OfficialVersion(id, Kind.RELEASE, null, null));
                }
            }
            writeCache(body, keep);
        } catch (IOException | RuntimeException ex) {
            System.err.println("[manifest] 写基线失败（不影响使用）: " + ex);
        }
    }

    /** 缓存里有什么（含时间缺失的），解析失败返回空列表。 */
    public static List<OfficialVersion> readCache() {
        String json = readText(CACHE);
        if (json == null) {
            return List.of();
        }
        List<OfficialVersion> versions = new ArrayList<>();
        for (OfficialVersion v : parse(json)) {
            versions.add(v);
        }
        // 老用户升级后离线也能看到 HMCL 那 205 条特殊版本；baselineIds() 含这些 id 后
        // difference() 不会在首次升级时把它们全标成「官方新增」。merge 按 id 去重，幂等。
        return UnlistedVersions.merge(versions);
    }

    private static Instant cachedAt() {
        try {
            if (Files.exists(CACHE)) {
                return Files.getLastModifiedTime(CACHE).toInstant();
            }
        } catch (IOException | RuntimeException ex) {
            System.err.println("[manifest] 读缓存时间失败: " + ex);
        }
        return null;
    }

    private static void writeCache(String rawJson, List<OfficialVersion> versions) {
        try {
            Path dir = CACHE.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            // 把解析过的条目重写回去，界面刷新才不用每次都再解一遍原始 JSON
            StringBuilder sb = new StringBuilder("{\"savedAt\":\"");
            sb.append(Instant.now()).append("\",\"versions\":[");
            for (int i = 0; i < versions.size(); i++) {
                OfficialVersion v = versions.get(i);
                if (i > 0) {
                    sb.append(',');
                }
                // 字段必须叫 type：parse() 只认 id/type/time/url，写错成 kind 下次读回来
                // 快照会全部退化成「正式版」（v0.3.5 自测就栽在这）。
                // 这里写的是发行日（没有发行日才写登记日），跟 parse 的取值口径一致
                sb.append("{\"id\":\"").append(v.id()).append('"')
                        .append(",\"type\":\"").append(v.kind().name().toLowerCase(Locale.ROOT))
                        .append('"')
                        .append(",\"time\":").append(v.shown() == null ? "null" : ("\"" + v.shown() + "\""))
                        .append(",\"url\":").append(v.url() == null ? "null" : ("\"" + v.url() + "\""))
                        .append('}');
            }
            sb.append("]}");
            Files.writeString(CACHE, sb.toString(), StandardCharsets.UTF_8);
            // 原始响应用不着存，删掉省得缓存里躺着两份明文
        } catch (IOException | RuntimeException ex) {
            System.err.println("[manifest] 写缓存失败（不影响使用）: " + ex);
        }
    }

    private static String readText(Path file) {
        try {
            if (!Files.exists(file)) {
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            System.err.println("[manifest] 读缓存失败: " + ex);
            return null;
        }
    }

    /** 这次清单里、基线里没有的那些 id——「官方更新了」的具体证据。 */
    private static Set<String> difference(List<OfficialVersion> versions, Set<String> known) {
        Set<String> fresh = new LinkedHashSet<>();
        for (OfficialVersion v : versions) {
            if (!known.contains(v.id())) {
                fresh.add(v.id());
            }
        }
        return fresh;
    }

    /** 界面上那个时刻只要「10:52」这种，秒和时区都是噪音。 */
    private static String timeOf(Instant at) {
        return at.atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
    }
}
