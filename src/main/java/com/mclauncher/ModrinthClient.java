package com.mclauncher;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modrinth 搜索 / 版本查询 / 安装（v0.7.0 P0-4）。
 *
 * <p>机制对齐蓝本第 3 节：搜 {@code /v2/search}（facets 固定 {@code project_type:mod} +
 * {@code versions:<mc>}，装了加载器再追加 {@code categories:<loader>}）；安装走
 * {@code /v2/project/<id>/version} 按 {@code game_versions} / {@code loaders} 过滤取最新兼容版，
 * 文件本体在 {@code cdn.modrinth.com} 直链下载到 mods 目录。
 *
 * <ul>
 *   <li><b>限速自律</b>：Modrinth 300 req/min，搜索串行调用（UI 侧另加 300ms 防抖），
 *       不预取翻页（架构 D4）；</li>
 *   <li><b>下载不走镜像</b>：蓝本第 5 节——Modrinth 国内多数可直连，架构已拍板；</li>
 *   <li><b>落盘复用 {@link FileFetch}</b>：{@code .part} + sha1 校验 + 取消，失败无残留；</li>
 *   <li><b>写 {@link ModIndex}</b>：装成功就记索引（P1 更新检测的数据源，PRD T7）。</li>
 * </ul>
 */
public final class ModrinthClient {

    /** API 根地址。 */
    public static final String API = "https://api.modrinth.com/v2";

    /**
     * 单次 GET 超时（0.9.1 由 10s 降到 4s）：竞速落败的一路虽不能被 {@code cancel(true)} 中断，
     * 但 4s 后必然抛 {@code HttpTimeoutException} 释放线程与连接，配合 HttpRace 的 8s 总预算
     * 与 8 线程有界池，高频切 tab 也不会拖垮线程池。
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    /** {@code cdn.modrinth.com/data/<projectId>/versions/<versionId>/<file>} 的身份段。 */
    private static final Pattern CDN = Pattern.compile(
            "/data/([^/]+)/versions/([^/]+)/");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))   // 0.9.1：由 5s 降到 3s
            .proxy(ProxyRoute.selector())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private ModrinthClient() {
        // 静态工具类，别让人 new 出来
    }

    /**
     * 一条搜索结果。0.8.6 D3 起补作者 / 分类 / 更新时间 / MC 版本 / 来源，供卡片分区展示。
     * 新增字段一律 null 容错：取不到就是空串 / 空 List，绝不让解析抛异常导致整页空白。
     */
    public record Hit(String projectId, String slug, String title, String description,
                      long downloads, String iconUrl, String author, List<String> categories,
                      String dateUpdated, String mcVersion, String source) {

        /** 兼容旧 6 参构造（外部若还有直接 new Hit(...) 的调用不会断）。 */
        public Hit(String projectId, String slug, String title, String description,
                   long downloads, String iconUrl) {
            this(projectId, slug, title, description, downloads, iconUrl,
                    "", List.of(), "", "", "Modrinth");
        }
    }

    /** 取字符串，null / 非 String / 空白一律空串（D3 卡片字段的 null 容错口径）。 */
    private static String safeStr(Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : "";
    }

    /** 只收集 List 里的 String 元素；非 List、元素非 String 全跳过，不抛异常。 */
    private static List<String> stringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** 从 versions 数组里取第一个版本号；非 List / 空 / 元素非 String → 空串。 */
    private static String firstVersion(Object raw) {
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s && !s.isBlank()) {
                    return s;
                }
            }
        }
        return "";
    }

    /** 一个可装的文件（Modrinth CDN 直链）。 */
    public record File(String url, String filename, String sha1, long size, boolean primary) {
    }

    /**
     * 一个依赖项（前置 / 可选 / 不兼容 / 内嵌）。projectId 为空表示「游戏 / 加载器」这类非模组依赖。
     *
     * <p>0.8.7 P0：Modrinth 官方 {@code /v2/project/{id}} 的 Project schema 根本不含
     * dependencies 字段，依赖真源在 {@code Version.dependencies[]}。这里的 fileName 对应
     * 版本依赖里的 {@code file_name}（部分依赖带文件名、无 project_id）。
     */
    public record Dependency(String projectId, String versionId, String type, String fileName) {

        /** 兼容旧 3 参构造（项目级依赖 / 旧调用不会断）：缺 fileName 给空串。 */
        public Dependency(String projectId, String versionId, String type) {
            this(projectId, versionId, type, "");
        }
    }

    /** r10：依赖卡批量展示所需的精简项目信息，一次 /v2/projects 请求拿齐。 */
    public record ProjectBrief(String title, String description, String iconUrl,
                               List<String> categories) {
    }

    /** 一个项目的完整信息（详情弹窗用）。注意：官方 Project 不含 dependencies，见 {@link #project}。 */
    public record Project(String id, String slug, String title, String description,
                          String iconUrl, List<String> categories, Map<String, String> links,
                          long downloads, List<Dependency> dependencies) {
    }

    /**
     * 一个版本（详情弹窗的版本列表用）。0.8.7 P0 起带 {@code dependencies}——
     * 这是 Modrinth 依赖信息的真源（Project 没有此字段）。
     */
    public record Version(String id, String name, String versionNumber, String versionType,
                          List<String> gameVersions, List<String> loaders, String datePublished,
                          File primaryFile, List<Dependency> dependencies) {

        /** 兼容旧 8 参构造（依赖传空 List，旧调用不会断）。 */
        public Version(String id, String name, String versionNumber, String versionType,
                       List<String> gameVersions, List<String> loaders, String datePublished,
                       File primaryFile) {
            this(id, name, versionNumber, versionType, gameVersions, loaders, datePublished,
                    primaryFile, List.of());
        }
    }

    /** 下载进度。回调发生在下载线程上，接的人要自己往 FX 线程送。 */
    public interface ProgressListener {
        void onProgress(long doneBytes, long totalBytes);
    }

    // ------------------------------------------------------------------ 搜索 / 浏览

    /**
     * 排序：下载量（默认）/ 最新发布 / 最近更新 → Modrinth 搜索的 {@code index} 字段。
     *
     * <p>Modrinth 没有「按名称」这个索引字段（旧代码误把 {@code name} 塞进 {@code index}，
     * 又被拼成 {@code &sort=downloads&index=desc}——{@code index=desc} 是非法字段值，
     * 接口直接回 HTTP 400，表现是「网络抽风」其实是客户端拼错参数）。方向统一走
     * {@code &sort=desc}（最多 / 最新在前），合法 {@code index} 值只有
     * {@code relevance / downloads / follows / newest / updated}。
     */
    private static String sortParam(String sort) {
        if (sort == null) {
            return "downloads";
        }
        return switch (sort) {
            case "最新发布" -> "newest";
            case "最近更新" -> "updated";
            default -> "downloads";
        };
    }

    /**
     * 浏览 / 搜索：facets 固定 {@code project_type:mod}；{@code mcVersion} 非 null 时追加
     * {@code versions:<mc>}（null = 不过滤，展示全部版本——实现「没搜索也显示所有模组」）；
     * {@code loader} 非 null 时追加 {@code categories:<loader>}；{@code categories} 里每项
     * 各追加一枚 {@code categories:<cat>}（多枚之间是 OR，对应 HMCL 的分类筛选）。
     *
     * @param query     关键词（空串 = 不按词搜，只按排序 / 分类列）
     * @param mcVersion 当前 MC 版本（null = 不过滤版本）
     * @param loader    当前加载器（null = 不过滤加载器）
     * @param limit     最多几条
     * @param sort      排序（下载量 / 更新时间 / 名称），见 {@link #sortParam}
     * @param categories 功能分类筛选（优化 / 实用 …），可空
     * @throws IOException 接口连不上 / 返回认不出来（message 说人话）
     */
    public static List<Hit> search(String query, String mcVersion, LoaderType loader, int limit,
                                   String sort, List<String> categories) throws IOException {
        return search(query, mcVersion, loader, limit, sort, categories, "mod");
    }

    /**
     * 浏览 / 搜索：facets 固定 {@code project_type:<projectType>}；{@code mcVersion} 非 null 时
     * 追加 {@code versions:<mc>}；{@code loader} 非 null 时追加 {@code categories:<loader>}；
     * {@code categories} 里每项各追加一枚 {@code categories:<cat>}（多枚之间是 OR）。
     *
     * <p>0.8.7：{@code projectType} 按 Modrinth 的 {@code project_type} 原样透传，不设白名单，
     * 因此 {@code mod} / {@code modpack} / {@code resourcepack} / {@code shader} /
     * {@code datapack} 等全部生效——新加的资源包 / 光影 tab 直接复用本方法即可，
     * 无需为每种类型改 facets 拼接。
     *
     * @param query      关键词（空串 = 不按词搜，只按排序 / 分类列）
     * @param mcVersion  当前 MC 版本（null = 不过滤版本）
     * @param loader     当前加载器（null = 不过滤加载器）
     * @param limit      最多几条
     * @param sort       排序（下载量 / 最新发布 / 最近更新），见 {@link #sortParam}
     * @param categories 功能分类筛选，可空
     * @param projectType 项目类型，原样透传（mod / modpack / resourcepack / shader …）
     * @throws IOException 接口连不上 / 返回认不出来（message 说人话）
     */
    public static List<Hit> search(String query, String mcVersion, LoaderType loader, int limit,
                                   String sort, List<String> categories, String projectType)
            throws IOException {
        // projectType 原样透传（仅 null/空回退到 mod），不硬编码只认 mod/modpack：
        // resourcepack / shader 等新 tab 复用时 facets 自动带上对应 project_type。
        String pt = projectType == null || projectType.isBlank() ? "mod" : projectType;
        StringBuilder facets = new StringBuilder("[[\"project_type:" + pt + "\"]");
        if (mcVersion != null) {
            facets.append(",[\"versions:").append(mcVersion).append("\"]");
        }
        if (loader != null) {
            facets.append(",[\"categories:").append(loader.category()).append("\"]");
        }
        if (categories != null) {
            for (String c : categories) {
                if (c != null && !c.isBlank()) {
                    facets.append(",[\"categories:").append(c).append("\"]");
                }
            }
        }
        facets.append(']');
        // 正确形态：index=<字段>&sort=<方向>。旧代码写成 &sort=downloads&index=desc
        // （index=desc 是非法字段值）→ HTTP 400。统一 desc（最多 / 最新在前）。
        String url = API + "/search?limit=" + Math.max(1, limit) + "&offset=0&index="
                + sortParam(sort) + "&sort=desc&query=" + encode(query == null ? "" : query)
                + "&facets=" + encode(facets.toString());
        Object parsed = getJson(url, "modrinth");
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("hits") instanceof List<?> hits)) {
            throw new IOException("Modrinth 搜索结果认不出来，稍后重试");
        }
        List<Hit> out = new ArrayList<>();
        for (Object item : hits) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            out.add(new Hit(str(m.get("project_id")), str(m.get("slug")), str(m.get("title")),
                    str(m.get("description")), number(m.get("downloads")), str(m.get("icon_url")),
                    safeStr(m.get("author")), stringList(m.get("categories")),
                    safeStr(m.get("date_modified")), firstVersion(m.get("versions")),
                    "Modrinth"));
        }
        return out;
    }

    /** 兼容旧调用：按当前游戏版本 + 加载器过滤，按下载量排序。 */
    public static List<Hit> search(String query, String mcVersion, LoaderType loader, int limit)
            throws IOException {
        return search(query, mcVersion, loader, limit, "downloads", null);
    }

    /** 浏览推荐：不按词、不过滤版本 / 加载器，按下载量排（默认进模组页就展示）。 */
    public static List<Hit> browse(int limit, String sort, List<String> categories)
            throws IOException {
        return search("", null, null, limit, sort, categories);
    }

    /** 浏览推荐整合包：不按词、不过滤版本 / 加载器，按下载量排（默认进整合包页就展示）。 */
    public static List<Hit> browseModpacks(int limit, String sort, List<String> categories)
            throws IOException {
        return search("", null, null, limit, sort, categories, "modpack");
    }

    // ------------------------------------------------------------------ 项目详情

    /** 取一个项目的完整信息（详情弹窗的标题 / 描述 / 封面 / 分类 / 外链）。 */
    public static Project project(String projectId) throws IOException {
        String url = API + "/project/" + encode(projectId);
        Object parsed = getJson(url, "modrinth");
        if (!(parsed instanceof Map<?, ?> m)) {
            throw new IOException("Modrinth 项目信息认不出来，稍后重试");
        }
        Map<String, String> links = new LinkedHashMap<>();
        if (m.get("links") instanceof Map<?, ?> lm) {
            for (var e : lm.entrySet()) {
                links.put(String.valueOf(e.getKey()), str(e.getValue()));
            }
        }
        List<String> cats = new ArrayList<>();
        if (m.get("categories") instanceof List<?> cl) {
            for (Object c : cl) {
                if (c instanceof String s) {
                    cats.add(s);
                }
            }
        }
        // 0.8.7 P0：Modrinth 官方 /v2/project/{id} 的 Project schema 根本不含 dependencies
        // 字段，下面这段解析恒为空（m.get("dependencies") 永远是 null）。真源在
        // Version.dependencies[]，由 allVersions() 解析、dependenciesOf() / install(Version)
        // 消费。此处仅作兼容占位：保留 Project record 签名与旧的「项目级依赖」调用链，
        // 不删、不依赖它产出真实前置。UI 的前置提示请改读选中 Version 的依赖。
        List<Dependency> deps = new ArrayList<>();
        if (m.get("dependencies") instanceof List<?> dl) {
            for (Object d : dl) {
                if (!(d instanceof Map<?, ?> dm)) {
                    continue;
                }
                String pid = str(dm.get("project_id"));
                if (pid == null) {
                    continue;   // 游戏 / 加载器这类非模组依赖不做前置提醒
                }
                deps.add(new Dependency(pid, str(dm.get("version_id")),
                        str(dm.get("dependency_type"))));
            }
        }
        return new Project(str(m.get("id")), str(m.get("slug")), str(m.get("title")),
                str(m.get("description")), str(m.get("icon_url")), cats, links,
                number(m.get("downloads")), deps);
    }

    /**
     * 批量反查项目标题：一次请求拿 N 个 projectId 的 title，供「依赖提示」显示真实项目名。
     *
     * <p>走 {@code GET /v2/projects?ids=["A","B"]}（一次 N 个 id 返 N 条，避免逐个反查
     * 造成 N 次请求）。<b>返回顺序不保证与请求一致</b>（实测 10 个 id 顺序全乱、集合相同），
     * 因此按返回项自身的 {@code id} 建 Map 回填，<b>绝不按数组下标对齐</b>。
     *
     * <p>全链路容错：请求失败 / 返回认不出 / 某 id 缺失 / 单条脏数据都不抛异常，只把成功
     * 解析到的 {@code id → title} 放进 Map（可能为空）。调用方对每个没命中的 projectId
     * 用 id 兜底显示即可，<b>绝不因反查失败让整个详情弹窗空白</b>。保留
     * {@code throws IOException} 仅为对齐既有契约（UI 写 try-catch 合法），本方法内部已兜底，
     * 正常运行不会向外抛。
     *
     * <p>注：Version.dependencies[] 的 {@code file_name} 实测 100% 为 null、{@code version_id}
     * 90.6% 为 null，依赖展示只能靠 {@code project_id} 反查标题，别指望这俩字段。
     *
     * @param projectIds 待反查的 projectId 列表（null / 空 → 空 Map）
     * @return id → title，只含成功查到的项，顺序无关
     */
    public static Map<String, String> projectTitles(List<String> projectIds) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (projectIds == null || projectIds.isEmpty()) {
            return out;
        }
        // 去重 + 过滤脏 id：Modrinth projectId 是 base62（字母数字，slug 另含 - / _）。
        // 含引号 / 反斜杠 / 控制字符的 id 直接不进请求，避免破坏 ids 的 JSON 数组，
        // 也符合「单个脏数据不抛异常、缺的 id 不进 Map」。
        List<String> ids = new ArrayList<>();
        for (String id : projectIds) {
            if (id != null && id.matches("[-A-Za-z0-9_]+") && !ids.contains(id)) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return out;
        }
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                arr.append(',');
            }
            arr.append('"').append(ids.get(i)).append('"');
        }
        arr.append(']');
        try {
            String url = API + "/projects?ids=" + encode(arr.toString());
            Object parsed = getJson(url, "modrinth");
            if (!(parsed instanceof List<?> list)) {
                return out;   // 顶层不是数组 = 认不出来，当全没查到，UI 用 id 兜底
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;   // 单条脏数据跳过
                }
                String id = str(m.get("id"));
                String title = safeStr(m.get("title"));
                if (id != null && ids.contains(id) && !title.isBlank()) {
                    out.put(id, title);   // 按返回项自身 id 回填，不按数组下标
                }
            }
        } catch (IOException | RuntimeException ignore) {
            // 反查整体失败：返回已收集到的部分（可能为空），UI 用 id 兜底，不阻断弹窗
        }
        return out;
    }

    /**
     * r10：批量反查依赖卡的展示信息：一次 {@code GET /v2/projects?ids=[...]} 同时拿齐
     * title / description / icon_url / categories，供整合包详情的前置依赖卡片一次性渲染，
     * 避免 UI 为每个依赖单独发请求拉图标被限流。
     *
     * <p>与 {@link #projectTitles} 同一套容错风格：返回顺序不保证与请求一致，按返回项
     * 自身的 {@code id} 建 Map（绝不按数组下标对齐）；null / 空入参、脏 id、单条脏数据、
     * 网络失败一律不抛异常，只把成功解析到的部分放进 Map（可能为空），缺的 id 由 UI 兜底。
     *
     * @param projectIds 待反查的 projectId 列表（null / 空 → 空 Map）
     * @return id → ProjectBrief，只含成功查到的项，顺序无关
     */
    public static Map<String, ProjectBrief> projectBriefs(List<String> projectIds)
            throws IOException {
        Map<String, ProjectBrief> out = new LinkedHashMap<>();
        if (projectIds == null || projectIds.isEmpty()) {
            return out;
        }
        // 去重 + 过滤脏 id：Modrinth projectId 是 base62（字母数字，slug 另含 - / _）。
        // 含引号 / 反斜杠 / 控制字符的 id 直接不进请求，避免破坏 ids 的 JSON 数组，
        // 也符合「单个脏数据不抛异常、缺的 id 不进 Map」。
        List<String> ids = new ArrayList<>();
        for (String id : projectIds) {
            if (id != null && id.matches("[-A-Za-z0-9_]+") && !ids.contains(id)) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return out;
        }
        // 1.0.8：每批最多 20 个 id —— 几十个 id 拼一条 GET 会把 URL 撑长（414/431），
        // 也更容易撞 429；分批后单批失败不再拖垮整份反查。
        final int batchSize = 20;
        for (int start = 0; start < ids.size(); start += batchSize) {
            List<String> batch = ids.subList(start, Math.min(ids.size(), start + batchSize));
            StringBuilder arr = new StringBuilder("[");
            for (int i = 0; i < batch.size(); i++) {
                if (i > 0) {
                    arr.append(',');
                }
                arr.append('"').append(batch.get(i)).append('"');
            }
            arr.append(']');
            String url = API + "/projects?ids=" + encode(arr.toString());
            Object parsed = null;
            IOException lastError = null;
            // 1.0.8：首次 + 1 次短退避重试；getJson 自带 4s 超时与镜像竞速，这里不再加长等待
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    parsed = getJson(url, "modrinth-project-briefs");
                    lastError = null;
                    break;
                } catch (IOException | RuntimeException ex) {
                    lastError = ex instanceof IOException io ? io
                            : new IOException(String.valueOf(ex));
                    if (attempt == 0) {
                        try {
                            Thread.sleep(180L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            if (lastError != null) {
                System.err.println("[modrinth] projectBriefs 批次 "
                        + (start / batchSize + 1) + " / " + batch.size()
                        + " 项失败: " + lastError.getMessage());
            }
            if (!(parsed instanceof List<?> list)) {
                continue;   // 本批认不出来 / 失败：保持已收集部分，UI 对缺项逐 id 兜底
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;   // 单条脏数据跳过
                }
                String id = str(m.get("id"));
                if (id == null || !ids.contains(id)) {
                    continue;   // 只认请求过的合法 id，按返回项自身 id 回填，不按数组下标
                }
                String title = safeStr(m.get("title"));
                String description = safeStr(m.get("description"));
                String iconUrl = safeStr(m.get("icon_url"));
                List<String> categories = new ArrayList<>();
                if (m.get("categories") instanceof List<?> cl) {
                    for (Object c : cl) {
                        String cat = safeStr(c);
                        if (!cat.isBlank()) {
                            categories.add(cat);
                        }
                    }
                }
                out.put(id, new ProjectBrief(title, description, iconUrl,
                        categories.isEmpty() ? List.of() : categories));
            }
        }
        return out;
    }

    /**
     * best-effort 批量反查 projectId → 可读名称（0.9.1 重发，三处安装写入点共用）。
     *
     * <p>只对「8 位 base62 且至少含一个字母」的 ID（真正的 Modrinth projectId，如
     * {@code AANobbMI}）调 {@link #projectTitles}；CurseForge 的纯数字 modId / 带连字符的
     * slug 一律原样跳过，避免无意义的网络请求和把数字错映射到同形 ID 的项目名。
     *
     * <p>整体 best-effort：{@code projectTitles} 自带 3s connect / 4s request 有界超时并吞掉
     * 异常，这里再兜一层——超时 / 离线 / 任何 RuntimeException 一律吞，返回已查到的部分
     * （可能为空），<b>绝不抛、绝不因反查失败中断安装或启动</b>。查不到名称的 ID 由调用方
     * 回退显示原 ID。
     */
    public static Map<String, String> resolveTitles(List<String> projectIds) {
        Map<String, String> out = new LinkedHashMap<>();
        if (projectIds == null || projectIds.isEmpty()) {
            return out;
        }
        List<String> ids = new ArrayList<>();
        for (String id : projectIds) {
            // Modrinth projectId：8 位 base62 且至少含一个字母；纯数字是 CurseForge modId，跳过
            if (id != null && id.matches("(?=.*[A-Za-z])[0-9A-Za-z]{8}") && !ids.contains(id)) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return out;
        }
        try {
            out.putAll(projectTitles(ids));
        } catch (IOException | RuntimeException ignore) {
            // best-effort：拿不到就留空，缺前置文案回退到 ID
        }
        return out;
    }

    /** 取一个项目的全部版本（不过滤游戏 / 加载器，详情弹窗按游戏版本分组用）。 */
    public static List<Version> allVersions(String projectId) throws IOException {
        String url = API + "/project/" + encode(projectId) + "/version";
        Object parsed = getJson(url, "modrinth");
        if (!(parsed instanceof List<?> list)) {
            throw new IOException("Modrinth 版本列表认不出来，稍后重试");
        }
        List<Version> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            List<String> games = new ArrayList<>();
            if (m.get("game_versions") instanceof List<?> g) {
                for (Object o : g) {
                    if (o instanceof String s) {
                        games.add(s);
                    }
                }
            }
            List<String> loaders = new ArrayList<>();
            if (m.get("loaders") instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof String s) {
                        loaders.add(s);
                    }
                }
            }
            File primary = null;
            if (m.get("files") instanceof List<?> files) {
                for (Object f : files) {
                    if (!(f instanceof Map<?, ?> file)) {
                        continue;
                    }
                    String sha1 = null;
                    if (file.get("hashes") instanceof Map<?, ?> hashes) {
                        sha1 = str(hashes.get("sha1"));
                    }
                    File cand = new File(str(file.get("url")), str(file.get("filename")), sha1,
                            number(file.get("size")), Boolean.TRUE.equals(file.get("primary")));
                    if (cand.primary()) {
                        primary = cand;
                        break;
                    }
                    if (primary == null) {
                        primary = cand;
                    }
                }
            }
            // 0.8.7 P0：依赖真源在这里——Version.dependencies[]（官方 Project 无此字段）。
            // parseDependencies 全程 null 安全，单条脏数据只跳过该条，不拖垮整份版本列表。
            List<Dependency> deps = parseDependencies(m.get("dependencies"));
            out.add(new Version(str(m.get("id")), str(m.get("name")), str(m.get("version_number")),
                    str(m.get("version_type")), games, loaders, str(m.get("date_published")),
                    primary, deps));
        }
        return out;
    }

    /**
     * 解析一条 Version 的 {@code dependencies[]}（0.8.7 P0 真源）。
     *
     * <p>字段映射：{@code project_id → projectId} / {@code version_id → versionId} /
     * {@code dependency_type → type} / {@code file_name → fileName}。全部 null 安全：
     * 非 List / 单条非 Map / 字段类型不对都跳过或给空串，绝不抛异常，单条脏数据不会
     * 让整个版本列表失败。
     */
    private static List<Dependency> parseDependencies(Object raw) {
        List<Dependency> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object d : list) {
            if (!(d instanceof Map<?, ?> dm)) {
                continue;   // 单条脏数据：跳过，不影响其它依赖或整份版本列表
            }
            out.add(new Dependency(
                    safeStr(dm.get("project_id")),
                    safeStr(dm.get("version_id")),
                    safeStr(dm.get("dependency_type")),
                    safeStr(dm.get("file_name"))));
        }
        return out;
    }

    /** 取一个版本的依赖列表；v 或 v.dependencies() 为 null 一律返回空 List，绝不返回 null。 */
    public static List<Dependency> dependenciesOf(Version v) {
        return v == null || v.dependencies() == null ? List.of() : v.dependencies();
    }

    /**
     * 某项目的兼容版本（新 → 旧）：{@code game_versions} 含 {@code mcVersion} 且
     * {@code loaders} 含 {@code loader}（loader 为 null 时不过滤加载器）。
     *
     * @throws IOException 接口连不上 / 返回认不出来
     */
    public static List<File> versions(String projectId, String mcVersion, LoaderType loader)
            throws IOException {
        String url = API + "/project/" + encode(projectId) + "/version";
        Object parsed = getJson(url, "modrinth");
        if (!(parsed instanceof List<?> list)) {
            throw new IOException("Modrinth 版本列表认不出来，稍后重试");
        }
        List<File> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            if (!(m.get("game_versions") instanceof List<?> games)
                    || !games.contains(mcVersion)) {
                continue;
            }
            if (loader != null) {
                if (!(m.get("loaders") instanceof List<?> loaders)
                        || !loaders.contains(loader.category())) {
                    continue;
                }
            }
            if (!(m.get("files") instanceof List<?> files)) {
                continue;
            }
            for (Object f : files) {
                if (!(f instanceof Map<?, ?> file)) {
                    continue;
                }
                String sha1 = null;
                if (file.get("hashes") instanceof Map<?, ?> hashes) {
                    sha1 = str(hashes.get("sha1"));
                }
                out.add(new File(str(file.get("url")), str(file.get("filename")), sha1,
                        number(file.get("size")),
                        Boolean.TRUE.equals(file.get("primary"))));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 安装

    /**
     * 下载到 mods 目录 + 验 sha1 + 写 {@link ModIndex}（原 4 参签名保持不变）。
     *
     * <p>同名已存在 → 跳过并返回已存在的路径（不覆盖、不重复下）。
     * 取消 → 返回 null；失败 → 抛 {@link IOException}（半成品 {@code .part} 由
     * {@link FileFetch} 删掉，mods 目录无残留）。
     *
     * <p>0.8.7 P0：此重载拿不到用户选中的 Version，{@code requires} 直接记空列表——
     * <b>不再打恒空的 {@code project()} 接口反查依赖</b>（那正是 U1 根因：官方 Project
     * 无 dependencies 字段，反查永远为空，还平白多一次网络请求）。要记录真实前置，请改调
     * {@link #install(File, Version, String, AtomicBoolean, ProgressListener)} 把选中版本传进来。
     *
     * @param versionId 游戏版本 id（索引按它分目录）
     * @param cancelled 取消旗标
     * @param listener  下载进度（可为 null）
     * @return 落盘后的文件路径；取消返回 null
     */
    public static Path install(File file, String versionId, AtomicBoolean cancelled,
                               ProgressListener listener) throws IOException {
        return doInstall(file, null, versionId, cancelled, listener);
    }

    /**
     * 带选中版本的安装重载（0.8.7 P0 前置修复）：{@code requires} 取自
     * {@link Version#dependencies()}——Modrinth 官方 Project 不含 dependencies，真源在 Version。
     *
     * <p>用 {@link #dependenciesOf(Version)} 挑 {@code type == "required"} 且 projectId 非空的
     * 项，把 projectId 列表写进 {@link ModIndex.Entry#requires()}，供 {@link ModValidator}
     * 启动前对账「缺前置就拦」。UI 安装某个具体版本时（如 ShellView.installVersionFile）
     * 应把该 Version 传进来。
     *
     * <p>参数个数与旧重载不同（5 参 vs 4 参），不会与旧调用产生重载歧义；旧 4 参调用
     * （整合包导入、自动补前置等）仍可用，只是 {@code requires} 记空、不再发那次恒空反查。
     */
    public static Path install(File file, Version version, String versionId,
                               AtomicBoolean cancelled, ProgressListener listener)
            throws IOException {
        return doInstall(file, version, versionId, cancelled, listener);
    }

    /** 安装主体：两个 public 重载共用，仅「依赖来源」不同（version 是否为 null）。 */
    private static Path doInstall(File file, Version version, String versionId,
                                  AtomicBoolean cancelled, ProgressListener listener)
            throws IOException {
        if (file == null || file.url() == null || file.url().isBlank()) {
            throw new IOException("这个 Mod 文件没有下载地址，换一个版本试试");
        }
        Path mods = ModStore.modsDir();
        Files.createDirectories(mods);
        // 0.9.9：API 返回的 filename 先净化再拼路径，并守住「必须落在 mods 目录内」。
        Path target = mods.resolve(safeFileName(file.filename()));
        if (!target.startsWith(mods)) {
            throw new IOException("模组文件名不合法，拒绝安装: " + file.filename());
        }
        if (Files.isRegularFile(target)) {
            // 同名已存在：跳过下载（PRD 规则④）。启用态不管——禁着的那份也是用户装过的。
            // 0.9.9：跳过也要补一条索引（至少 projectId + sha1 + 文件名），否则别的模组 requires
            // 指向它时，ModValidator 会因为索引里没有它的 projectId 而误报「缺前置」并拦启动。
            String[] skipIdentity = cdnIdentity(file.url());
            ModIndex.add(versionId, new ModIndex.Entry(skipIdentity[0], skipIdentity[1],
                    target.getFileName().toString(), file.sha1(), Instant.now().toString(),
                    List.of(), Map.of()));
            System.out.println("[modrinth] 「" + target.getFileName()
                    + "」已存在，跳过下载并登记到模组索引");
            return target;
        }
        // 两个 ProgressListener 是同形不同名的两个接口（各自的契约），这里转接一下
        boolean ok = FileFetch.download(file.url(), target, file.sha1(), null, cancelled,
                listener == null ? null : (done, len) -> listener.onProgress(done, len));
        if (!ok) {
            if (cancelled != null && cancelled.get()) {
                return null;   // 取消不是错误，UI 自己收尾
            }
            throw new IOException("「" + target.getFileName() + "」没下下来（重试过 "
                    + FileFetch.RETRY + " 次）：检查网络后重试，或换个版本");
        }
        // 写索引（U8 同款口径：利于 P1 更新检测）。projectId / 版本 id 从 CDN 直链里抠——
        // File 记录不带这两个字段，而 cdn.modrinth.com/data/<id>/versions/<vid>/ 是稳定形状
        String[] identity = cdnIdentity(file.url());
        List<String> requires = collectRequires(version);
        // 0.9.1 重发：安装期 best-effort 反查「自身 + 前置」的可读名，落进索引供启动前报错显示真名。
        // 合并成一次批量请求；离线 / 超时 / 失败一律吞掉（resolveTitles 内），绝不拖累安装。
        List<String> idsForTitles = new ArrayList<>(requires);
        if (identity[0] != null) {
            idsForTitles.add(identity[0]);
        }
        Map<String, String> titles = resolveTitles(idsForTitles);
        ModIndex.add(versionId, new ModIndex.Entry(identity[0], identity[1], target.getFileName()
                .toString(), file.sha1(), Instant.now().toString(), requires, titles));
        System.out.println("[modrinth] 「" + target.getFileName() + "」装好了");
        return target;
    }

    /**
     * 采集写进索引的必需前置 projectId。
     *
     * <p>version 非 null（UI 传了选中版本）→ 真源 {@link Version#dependencies()}，用
     * {@link #dependenciesOf(Version)} 取列表，挑 {@code "required"} 且 projectId 非空；
     * version 为 null（旧 4 参调用）→ 直接空列表：U1 根因就是打恒空的 {@code project()}
     * 反查依赖，这里不再发那次请求，也不依赖恒空的项目级数据。
     */
    private static List<String> collectRequires(Version version) {
        if (version == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Dependency d : dependenciesOf(version)) {
            if ("required".equals(d.type())
                    && d.projectId() != null && !d.projectId().isBlank()) {
                out.add(d.projectId());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 0.9.9：安装落盘前的文件名净化。ContentStore.sanitizeName 是 private，这里做等价实现——
     * 取最后一段、去路径分隔符、拒 {@code .}/{@code ..}/含 {@code ..}、替换 Windows 非法字符，
     * 防 API 返回的 filename 带路径穿越把文件写到 mods 目录之外。空文件名回退到 {@code mod.jar}。
     */
    private static String safeFileName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "mod.jar";
        }
        String name = raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name) || name.contains("..")) {
            return "mod.jar";
        }
        return name.replaceAll("[:*?\"<>|]", "_");
    }

    /**
     * 发一次 GET 拿 JSON：<b>国内镜像 MCIM 与官方并发竞速</b>（谁先回 2xx 用谁），任一方慢 /
     * 挂都不拖累另一方。MCIM 关掉（{@code GBL_MIRROR=off}）时直接走官方单请求，零额外开销。
     * 非 2xx / 网络错误抛 {@link IOException}（message 说人话）。
     *
     * <p>0.9.1：改调 {@code HttpRace.race(String, JsonFetch, JsonFetch)}，
     * 把<b>官方原始 url</b> 作为短 TTL 缓存 key 传进去（60s 内同一 URL 命中即零网络往返）。
     * 缓存 key 用官方 URL 而非镜像 URL，保证 {@code GBL_MIRROR} 开关切换时 key 一致。
     * 镜像关闭时仍走 {@code getJsonOnce} 单请求，既不竞速也不查缓存，保持零开销。
     */
    private static Object getJson(String url, String what) throws IOException {
        String mirror = Mirror.rewriteContent(url);
        if (mirror.equals(url)) {
            return getJsonOnce(url, what);
        }
        return HttpRace.race(url,
                () -> getJsonOnce(mirror, what),
                () -> getJsonOnce(url, what));
    }

    /** 对单个地址发一次 GET 拿 JSON。 */
    private static Object getJsonOnce(String url, String what) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", GameDownloader.AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException(what + " 接口暂时连不上（HTTP " + response.statusCode()
                        + "），稍后重试");
            }
            return Json.parse(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("拉取 " + what + " 数据被中断");
        } catch (RuntimeException ex) {
            throw new IOException(what + " 返回了一大段认不出来的东西，稍后重试");
        }
    }

    /**
     * 从 Modrinth CDN 直链抠 {@code [projectId, versionId]}。
     *
     * <p>{@code https://cdn.modrinth.com/data/<projectId>/versions/<versionId>/<file>}。
     * 认不出来（老形状的地址）返回 {@code [null, null]}——索引少两个字段不影响安装，
     * P1 更新检测对这类条目自然跳过。
     */
    static String[] cdnIdentity(String url) {
        if (url == null) {
            return new String[]{null, null};
        }
        Matcher m = CDN.matcher(url);
        if (m.find()) {
            return new String[]{m.group(1), m.group(2)};
        }
        return new String[]{null, null};
    }

    private static String encode(String text) {
        return URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8);
    }

    private static String str(Object raw) {
        return raw instanceof String text && !text.isBlank() ? text : null;
    }

    /** JSON 数字一律 Double 解析（自研 {@link Json} 的口径），取整用 {@code longValue()}。 */
    private static long number(Object raw) {
        return raw instanceof Number n ? n.longValue() : 0L;
    }
}
