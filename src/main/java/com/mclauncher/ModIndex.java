package com.mclauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mod 本地索引（v0.7.0，P1-3 更新检测的前置）：{@code ~/.gbl/cache/mods/<versionId>/index.json}。
 *
 * <p>记的是「这个游戏版本装过哪些 Modrinth 上的 mod」——projectId / versionId / filename / sha1。
 * <b>只写 {@code ~/.gbl/}，不写进 {@code .minecraft}</b>（PRD T7 / 共享约定 ⑧：便携 zip 保持干净）。
 *
 * <p>职责收得很窄：只读写索引文件，不下载、不扫盘。读不到（没有 / 坏了）一律当空列表——
 * 索引是「锦上添花」的更新检测数据源，不该因为它让列表打不开。
 */
public final class ModIndex {

    /**
     * 索引格式版本。0.9.1 重发：{@code +1} 到 2 —— {@link Entry} 追加可选字段
     * {@code requiresTitles}。读盘不按版本拦截：旧文件缺新字段一律当空 Map
     * （见 {@link #readTitles}），向后兼容、不丢条目、不抛异常。
     */
    public static final int SCHEMA_VERSION = 2;

    private static final Path DIR =
            Paths.get(System.getProperty("user.home"), ".gbl", "cache", "mods");

    /**
     * 0.9.1 r3：离线内置的常见前置真名（projectId → title），已逐条联网核实。
     *
     * <p>存在的理由：{@code requiresTitles} 是 0.9.1 r2 才加的字段，用户磁盘上「旧版装的模组」
     * 索引里根本没有，只靠后台联网回填会有一小段空窗，而且离线用户可能永远补不上——
     * 报「缺前置」时依旧显示一串 {@code AANobbMI}，用户就会认为「bug 没改」。
     * 这张表覆盖 Fabric/ Sodium 生态最常见的依赖，命中即可立刻显示「Sodium」「Fabric API」。
     * 长尾 ID 仍由 {@link #backfillTitlesAsync()} 联网补齐。
     */
    private static final Map<String, String> KNOWN_TITLES = Map.ofEntries(
            Map.entry("AANobbMI", "Sodium"),
            Map.entry("P7dR8mSH", "Fabric API"),
            Map.entry("YL57xq9U", "Iris Shaders"),
            Map.entry("gvQqBUqZ", "Lithium"),
            Map.entry("9s6osm5g", "Cloth Config API"),
            Map.entry("Ha28R6CL", "Fabric Language Kotlin"),
            Map.entry("fALzjamp", "Chunky"),
            Map.entry("mOgUt4GM", "Mod Menu"),
            Map.entry("lhGA9TYQ", "Architectury API"),
            Map.entry("1eAoo2KR", "YetAnotherConfigLib (YACL)"),
            Map.entry("ccKDOlHs", "owo-lib"),
            Map.entry("Orvt0mRa", "Indium"),
            Map.entry("ohNO6lps", "Forge Config API Port"),
            Map.entry("PtjYWJkn", "Sodium Extra"),
            Map.entry("NNAgCjsB", "Entity Culling"),
            Map.entry("5ZwdcRci", "ImmediatelyFast"),
            Map.entry("u6dRKJwZ", "Just Enough Items (JEI)"),
            Map.entry("ssUbhMkL", "Gravestones"));

    /** 离线查一个 projectId 的可读名称；不认识返回 {@code null}。 */
    public static String knownTitle(String projectId) {
        return projectId == null ? null : KNOWN_TITLES.get(projectId);
    }

    /**
     * 一条索引：某个 Modrinth 文件装进了哪个游戏版本。requires = 它的必需前置 projectId 列表。
     *
     * <p>{@code requiresTitles} = projectId → 可读名称（0.9.1 重发）。安装期 best-effort 反查
     * Modrinth 标题后落盘，供 {@link ModValidator} 报「缺前置」时显示真名（如「Sodium」）而不是
     * 一串项目 ID（如 {@code AANobbMI}）。既含前置 projectId 的标题，也可含本条目自身 projectId
     * 的标题。旧索引没有这个字段，{@link #entries} 会读成空 Map，绝不丢条目、绝不抛异常；
     * 0.9.1 r3 起由 {@link #backfillTitlesAsync()} 后台补齐、{@link #knownTitle} 离线兜底。
     */
    public record Entry(String projectId, String versionId, String filename, String sha1,
                        String installedAt, List<String> requires,
                        Map<String, String> requiresTitles) {
    }

    private ModIndex() {
        // 静态工具类，别让人 new 出来
    }

    /** 某游戏版本的索引；读不到 / 读不懂返回空列表（不抛）。 */
    public static List<Entry> entries(String versionId) {
        Path file = indexFile(versionId);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            Object parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> root) || !(root.get("entries") instanceof List<?> list)) {
                System.err.println("[modindex] 索引结构不对，当空处理: " + file);
                return List.of();
            }
            List<Entry> out = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                out.add(new Entry(str(m.get("projectId")), str(m.get("versionId")),
                        str(m.get("filename")), str(m.get("sha1")), str(m.get("installedAt")),
                        readRequires(m.get("requires")), readTitles(m.get("requiresTitles"))));
            }
            return out;
        } catch (IOException | RuntimeException ex) {
            // 索引读不了不该把界面堵红：更新检测本来就还没做（P1），这版它只是备着
            System.err.println("[modindex] 索引读不了，当空处理: " + ex);
            return List.of();
        }
    }

    /**
     * 加一条索引。同文件名的旧条目先摘掉——重装 / 覆盖装要以新的为准。
     * 与 {@link #backfillTitlesAsync()} 的回写共用 {@code ModIndex.class} 锁，避免后台回填
     * 把用户刚装的模组覆盖掉。
     *
     * @throws IOException 索引写不进去（磁盘满 / 权限）：上层说人话，不静默
     */
    public static synchronized void add(String versionId, Entry entry) throws IOException {
        if (entry == null || entry.filename() == null || entry.filename().isBlank()) {
            return;   // 没文件名无从对账，别往索引里塞无名行
        }
        List<Entry> kept = new ArrayList<>();
        for (Entry e : entries(versionId)) {
            if (!entry.filename().equals(e.filename())) {
                kept.add(e);
            }
        }
        kept.add(entry);
        write(versionId, kept);
    }

    /** 卸载时按文件名摘除。索引文件本来就没有（从没装过 Modrinth mod）时什么都不做。 */
    public static synchronized void remove(String versionId, String filename) throws IOException {
        if (filename == null || filename.isBlank()) {
            return;
        }
        List<Entry> current = entries(versionId);
        if (current.isEmpty()) {
            return;
        }
        List<Entry> kept = new ArrayList<>();
        for (Entry e : current) {
            if (!filename.equals(e.filename())) {
                kept.add(e);
            }
        }
        if (kept.size() == current.size()) {
            return;   // 没这条，不用重写
        }
        write(versionId, kept);
    }

    /** 索引文件位置：{@code ~/.gbl/cache/mods/<versionId>/index.json}。 */
    public static Path indexFile(String versionId) {
        return DIR.resolve(versionId).resolve("index.json");
    }

    // ---------------------------------------------------------------- 落盘

    /** tmp + move：别截断直写，写到一半断电不该留下半截索引。 */
    private static void write(String versionId, List<Entry> entries) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        List<Object> list = new ArrayList<>();
        for (Entry e : entries) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("projectId", e.projectId());
            one.put("versionId", e.versionId());
            one.put("filename", e.filename());
            one.put("sha1", e.sha1());
            one.put("installedAt", e.installedAt());
            if (e.requires() != null && !e.requires().isEmpty()) {
                one.put("requires", new ArrayList<>(e.requires()));
            }
            // 0.9.1 重发：可选字段，旧条目 / 空 Map 不写，读盘时按空处理（向后兼容）
            if (e.requiresTitles() != null && !e.requiresTitles().isEmpty()) {
                one.put("requiresTitles", new LinkedHashMap<>(e.requiresTitles()));
            }
            list.add(one);
        }
        root.put("entries", list);
        Path file = indexFile(versionId);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ex) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException | RuntimeException ignored) {
                // 删不掉就留着，下次写同名覆盖
            }
            throw ex;
        }
    }

    private static String str(Object raw) {
        return raw instanceof String text && !text.isBlank() ? text : null;
    }

    /** 从索引条目里读 requires（List<String>）；认不出返回空列表，不抛。 */
    private static List<String> readRequires(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    /** 读 requiresTitles（projectId → 名称）；旧索引缺字段 / 认不出返回空 Map，不抛。 */
    private static Map<String, String> readTitles(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key && !key.isBlank()
                    && entry.getValue() instanceof String title && !title.isBlank()) {
                out.put(key, title);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 0.9.1 r3 旧索引回填

    /**
     * 后台补全旧索引的 {@code requiresTitles}。
     *
     * <p>这个字段是 0.9.1 r2 才引入的，用户磁盘上旧版安装的模组索引一律缺，不重装模组就
     * 永远显示「Modrinth 项目 AANobbMI」，看起来就像 bug 没改。启动时后台扫一遍、批量反查、
     * 回写：daemon 线程、不阻塞窗口、任何失败静默。下次校验就能显示真名；本次校验由
     * {@link #knownTitle} 离线兜底，不依赖网络。
     */
    public static void backfillTitlesAsync() {
        Thread t = new Thread(ModIndex::backfillTitles, "gbl-modindex-backfill");
        t.setDaemon(true);
        t.start();
    }

    private static void backfillTitles() {
        if (!Files.isDirectory(DIR)) {
            return;
        }
        List<Path> dirs;
        try (var stream = Files.list(DIR)) {
            dirs = stream.filter(p -> Files.isDirectory(p)).toList();
        } catch (IOException | RuntimeException ex) {
            return;   // 目录读不了就算了，索引本来就只是锦上添花
        }
        for (Path dir : dirs) {
            try {
                backfillOne(dir.getFileName().toString());
            } catch (RuntimeException ignored) {
                // 单个游戏版本失败不影响其他版本（backfillOne 内部已吞 IOException）
            }
        }
    }

    private static void backfillOne(String versionId) {
        List<Entry> current = entries(versionId);
        if (current.isEmpty()) {
            return;
        }
        // 1) 只有「内置表也没有、索引里也没记」的 ID 才需要联网，避免每次启动都打一次 API
        List<String> lookup = new ArrayList<>();
        boolean hasBuiltin = false;
        for (Entry e : current) {
            Map<String, String> have = e.requiresTitles() == null
                    ? Map.of() : e.requiresTitles();
            for (String id : idsOf(e)) {
                if (have.containsKey(id)) {
                    continue;
                }
                if (knownTitle(id) != null) {
                    hasBuiltin = true;
                } else if (!lookup.contains(id)) {
                    lookup.add(id);
                }
            }
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        if (!lookup.isEmpty()) {
            // best-effort：自带 3s connect / 4s request 有界超时，离线 / 超时一律吞异常
            resolved.putAll(ModrinthClient.resolveTitles(lookup));
        }
        if (resolved.isEmpty() && !hasBuiltin) {
            return;   // 已经补全过，或确实查不到，不用动盘
        }
        // 2) 合并回写。与 add/remove 共用类锁并重读一次，避免覆盖后台期间的新安装
        synchronized (ModIndex.class) {
            List<Entry> fresh = entries(versionId);
            List<Entry> updated = new ArrayList<>();
            boolean changed = false;
            for (Entry e : fresh) {
                Map<String, String> merged = new LinkedHashMap<>(
                        e.requiresTitles() == null ? Map.of() : e.requiresTitles());
                boolean oneChanged = false;
                for (String id : idsOf(e)) {
                    if (merged.containsKey(id)) {
                        continue;
                    }
                    String title = resolved.get(id);
                    if (title == null || title.isBlank()) {
                        title = knownTitle(id);
                    }
                    if (title != null && !title.isBlank()) {
                        merged.put(id, title);
                        oneChanged = true;
                    }
                }
                if (oneChanged) {
                    changed = true;
                    updated.add(new Entry(e.projectId(), e.versionId(), e.filename(), e.sha1(),
                            e.installedAt(), e.requires(), merged));
                } else {
                    updated.add(e);
                }
            }
            if (changed) {
                try {
                    write(versionId, updated);
                    System.out.println("[modindex] r3 已补全旧索引前置名：" + versionId);
                } catch (IOException | RuntimeException ex) {
                    System.err.println("[modindex] r3 旧索引回写失败（不影响使用）：" + ex);
                }
            }
        }
    }

    /** 一条索引里所有需要真名的 projectId：它的前置 + 它自己。 */
    private static List<String> idsOf(Entry e) {
        List<String> ids = new ArrayList<>();
        if (e.requires() != null) {
            for (String r : e.requires()) {
                if (r != null && !r.isBlank() && !ids.contains(r)) {
                    ids.add(r);
                }
            }
        }
        if (e.projectId() != null && !e.projectId().isBlank()
                && !ids.contains(e.projectId())) {
            ids.add(e.projectId());
        }
        return ids;
    }
}
