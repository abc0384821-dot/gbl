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
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CurseForge 模组库（v0.8.4 新增）。
 *
 * <p>与 {@link ModrinthClient} 平级，提供另一套模组源。机制差异：
 * <ul>
 *   <li>强制要求 API Key——CurseForge 没有匿名访问，key 写在 {@code ~/.gbl/curseforge.key}
 *       （一行纯文本）。没有 key 时所有方法抛 {@link IOException} 提示去申请，不静默崩；</li>
 *   <li>搜索走 {@code GET /v1/mods/search}（gameId=432=Minecraft，classId=6=Mods），
 *       排序用 {@code sortField} 数字枚举（下载量=6 / 最近更新=3 / 最新=2）；</li>
 *   <li>下载没有匿名直链：必须先拿 fileId，再调 {@code /v1/mods/{id}/files/{fileId}/download-url}
 *       换一份**有时效的临时直链**，再下到 mods 目录；</li>
 *   <li>国内访问不稳（同 Modrinth），失败会抛 {@link IOException} 说人话，UI 自己兜底。</li>
 * </ul>
 *
 * <p>设计口径对齐 {@link ModrinthClient}：静态工具类 + 共享 HttpClient + 抛 IOException 说人话 +
 * 落盘复用 {@link FileFetch}（.part + sha1 + 取消）+ 写 {@link ModIndex}（P1 更新检测数据源）。
 */
public final class CurseForgeClient {

    /**
     * 内置默认 API Key（0.8.6 起内置开发者申请的 key，分发给他人即可开箱用 CurseForge）。
     *
     * <p>填进这里后，别人不必自己申请 key 也能用 CurseForge。仍保留两条兜底：本地
     * {@code ~/.gbl/curseforge.key} 文件、以及「设置」页粘贴的 key，优先级都高于本常量。
     * 注意：这是公开在 jar 里的 key（反编译即可见），Overwolf TOS 要求 key 不共享给第三方，
     * 内置属于游走在灰区；如担心被刷 / 想合规，请在「设置」页让用户填自己的，或仅自己用本地文件。
     */
    /** 发布副本：内置 key 已移除，只从环境变量 GBL_CF_API_KEY 读。 */
    public static final String DEFAULT_API_KEY = System.getenv("GBL_CF_API_KEY");

    /** API 根地址。 */
    public static final String API = "https://api.curseforge.com/v1";

    /** Minecraft 在 CurseForge 的 gameId。 */
    private static final int GAME_ID = 432;

    /**
     * CurseForge 内容分类 classId（0.8.7 起公开，供「资源包 / 光影 / 世界」下载 tab 复用
     * 6 参 search；数值经真实 API 实测确认）。5 参 search 固定走 CLASS_ID_MODS，
     * 其余分类把对应常量作为 classId 传进 6 参 search 即可。
     */
    public static final int CLASS_ID_MODS = 6;
    public static final int CLASS_ID_MODPACKS = 4471;
    public static final int CLASS_ID_RESOURCE_PACKS = 12;
    public static final int CLASS_ID_WORLDS = 17;
    public static final int CLASS_ID_SHADERS = 6552;

    /**
     * 单次 GET 超时（0.9.1 由 10s 降到 4s）：竞速落败的一路虽不能被 {@code cancel(true)} 中断，
     * 但 4s 后必然抛 {@code HttpTimeoutException} 释放线程与连接，配合 HttpRace 的 8s 总预算
     * 与 8 线程有界池，高频切 tab 也不会拖垮线程池。
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))   // 0.9.1：由 5s 降到 3s
            .proxy(ProxyRoute.selector())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** key 文件：一行纯文本 key。 */
    private static final Path KEY_FILE =
            Paths.get(System.getProperty("user.home"), ".gbl", "curseforge.key");

    /** 分类缓存（name/中文 -> id），首次拉取后复用，避免每次搜索都打接口。 */
    private static volatile List<Category> CATEGORY_CACHE = null;

    private CurseForgeClient() {
    }

    /**
     * 一条搜索结果（卡片用）。0.8.6 D3 起补作者 / 分类 / 更新时间 / MC 版本 / 来源，
     * 全部 null 容错，取不到就是空串 / 空 List。
     */
    public record Hit(long modId, String name, String summary, long downloads,
                      String iconUrl, String websiteUrl, String author, List<String> categories,
                      String dateUpdated, String mcVersion, String source) {

        /** 兼容旧 6 参构造。 */
        public Hit(long modId, String name, String summary, long downloads,
                   String iconUrl, String websiteUrl) {
            this(modId, name, summary, downloads, iconUrl, websiteUrl,
                    "", List.of(), "", "", "CurseForge");
        }
    }

    /**
     * 一个可装的版本文件。D3 起补 {@code releaseType}（1=稳定 / 2=测试 / 3=快照）
     * 与 {@code fileLength}，供详情版本行显示稳定徽章与文件大小（原先 CF 版本行缺这两项）。
     * 0.8.7 起补 {@code dependencies}（文件级依赖，P1 前置提醒的数据源）。
     */
    public record File(long fileId, String displayName, String fileName,
                       List<String> gameVersions, String downloadUrl, String fileDate,
                       int releaseType, long fileLength, List<FileDependency> dependencies) {

        /** 兼容旧 8 参构造（依赖传空）。 */
        public File(long fileId, String displayName, String fileName,
                    List<String> gameVersions, String downloadUrl, String fileDate,
                    int releaseType, long fileLength) {
            this(fileId, displayName, fileName, gameVersions, downloadUrl, fileDate,
                    releaseType, fileLength, List.of());
        }

        /** 兼容旧 6 参构造。 */
        public File(long fileId, String displayName, String fileName,
                    List<String> gameVersions, String downloadUrl, String fileDate) {
            this(fileId, displayName, fileName, gameVersions, downloadUrl, fileDate, 1, 0L);
        }
    }

    /**
     * 一个文件级依赖（CF {@code dependencies[]}）。
     * relationType：1=EmbeddedLibrary / 2=OptionalDependency / 3=RequiredDependency /
     * 4=Tool / 5=Incompatible / 6=Include。
     */
    public record FileDependency(long modId, long fileId, int relationType) {
    }

    /** 取字符串，null / 非 String / 空白一律空串。 */
    private static String safeStr(Object raw) {
        return raw instanceof String s && !s.isBlank() ? s : "";
    }

    /** 从 {@code [{"name":"..."}]} 形态数组里收集 name；任何形态异常都跳过，不抛异常。 */
    private static List<String> objectNames(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("name") instanceof String s && !s.isBlank()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /** 第一个作者名；没有就空串。 */
    private static String firstAuthor(Object authors) {
        List<String> names = objectNames(authors);
        return names.isEmpty() ? "" : names.get(0);
    }

    /** 从 {@code latestFiles[].gameVersions} 里取第一个 MC 版本号。 */
    private static String firstGameVersion(Object latestFiles) {
        if (latestFiles instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("gameVersions") instanceof List<?> games) {
                    for (Object g : games) {
                        if (g instanceof String s && !s.isBlank()) {
                            return s;
                        }
                    }
                }
            }
        }
        return "";
    }

    /** 一个项目的完整信息（详情弹窗用）。 */
    public record Project(long modId, String name, String summary, long downloads,
                          String iconUrl, String websiteUrl, List<String> categories) {
    }

    /** 一个分类（胶囊用）。 */
    public record Category(long id, String name, String slug) {
    }

    /** 排序项：UI 文案 -> API sortField 枚举值。 */
    private static int sortField(String sort) {
        if (sort == null) {
            return 6; // 下载量
        }
        return switch (sort) {
            case "最新发布" -> 2;   // Popularity（CF 无"发布时间"排序，用热度近似）
            case "最近更新" -> 3;   // LastUpdated
            default -> 6;          // 下载量 TotalDownloads
        };
    }

    /**
     * 读 key，按优先级回落：
     * ① 本地 {@code ~/.gbl/curseforge.key} 文件（老用户 / 高级用户手动放）；
     * ② 应用内设置（用户在「设置」页粘贴的 {@link SettingsStore#cfApiKey}）；
     * ③ 内置 {@link #DEFAULT_API_KEY}（开发者打包时内置，确保分发给他人后开箱即用）；
     * 都没有才抛 IOException 说人话，而不是静默崩。
     */
    private static String apiKey() throws IOException {
        // ① 本地文件优先
        if (Files.isRegularFile(KEY_FILE)) {
            String raw = Files.readString(KEY_FILE, StandardCharsets.UTF_8).trim();
            if (!raw.isBlank()) {
                return raw;
            }
        }
        // ② 应用内设置
        String setKey = SettingsStore.load().cfApiKey();
        if (setKey != null && !setKey.isBlank()) {
            return setKey;
        }
        // ③ 内置默认 key
        if (DEFAULT_API_KEY != null && !DEFAULT_API_KEY.isBlank()) {
            return DEFAULT_API_KEY;
        }
        throw new IOException("CurseForge 还没配置 API Key：去「设置」页粘贴一个 CurseForge API Key，"
                + "或把 key 写入 " + KEY_FILE + "（一行纯文本）后重试");
    }

    /**
     * 浏览 / 搜索模组。
     *
     * @param query     关键词（空串 = 不按词，按排序 / 分类列）
     * @param mcVersion 当前 MC 版本（null = 不过滤）
     * @param sort      排序（下载量 / 最新发布 / 最近更新）
     * @param categoryIds 分类 id 列表（可空）
     * @param limit     最多几条
     */
    public static List<Hit> search(String query, String mcVersion, String sort,
                                  List<Long> categoryIds, int limit) throws IOException {
        return search(query, mcVersion, sort, categoryIds, limit, CLASS_ID_MODS);
    }

    /**
     * 浏览 / 搜索指定 classId 的内容（0.8.7：资源包 / 光影 / 世界 复用同一套搜索，
     * 5 参版固定走 Mods）。Hit.source 恒为 {@code "CurseForge"}，UI 侧按 tab 自行标注来源。
     *
     * @param query       关键词（空串 = 不按词，按排序 / 分类列）
     * @param mcVersion   当前 MC 版本（null = 不过滤）
     * @param sort        排序（下载量 / 最新发布 / 最近更新）
     * @param categoryIds 分类 id 列表（可空）
     * @param limit       最多几条
     * @param classId     CurseForge classId（见 CLASS_ID_MODS 等常量）
     */
    public static List<Hit> search(String query, String mcVersion, String sort,
                                  List<Long> categoryIds, int limit, int classId) throws IOException {
        StringBuilder url = new StringBuilder(API)
                .append("/mods/search?gameId=").append(GAME_ID)
                .append("&classId=").append(classId)
                .append("&sortField=").append(sortField(sort))
                .append("&sortOrder=desc")
                .append("&pageSize=").append(Math.max(1, Math.min(limit, 50)));
        if (query != null && !query.isBlank()) {
            url.append("&searchFilter=").append(encode(query));
        }
        if (mcVersion != null && !mcVersion.isBlank()) {
            url.append("&gameVersion=").append(encode(mcVersion));
        }
        if (categoryIds != null && !categoryIds.isEmpty()) {
            // CF 一次只接受一个 categoryId；取第一个即可（UI 单选更自然，但这里兼容多选取首）
            url.append("&categoryId=").append(categoryIds.get(0));
        }
        Object parsed = getJson(url.toString());
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("data") instanceof List<?> data)) {
            throw new IOException("CurseForge 搜索结果认不出来，稍后重试");
        }
        List<Hit> out = new ArrayList<>();
        for (Object item : data) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            long id = num(m.get("id"));
            String website = null;
            if (m.get("links") instanceof Map<?, ?> links) {
                website = str(links.get("websiteUrl"));
            }
            out.add(new Hit(id, str(m.get("name")), str(m.get("summary")),
                    num(m.get("downloadCount")), logoUrl(m), website,
                    firstAuthor(m.get("authors")), objectNames(m.get("categories")),
                    safeStr(m.get("dateModified")), firstGameVersion(m.get("latestFiles")),
                    "CurseForge"));
        }
        return out;
    }

    /**
     * 从 CurseForge 的 Mod 对象里抠封面 URL。
     *
     * <p>CF API v1 的 Mod 对象<b>没有</b> {@code iconUrl} 字段，图标在 {@code logo} 这个对象里：
     * {@code "logo": {"url": "...", "thumbnailUrl": "..."}}。0.8.6 之前 search / project 两处都读
     * {@code m.get("iconUrl")}，恒为 null，导致 CurseForge 卡片与详情弹窗永远没有封面
     * （{@code loadCover} 见到空 url 直接返回，连下载都不会发起）。
     *
     * <p>取不到时返回空串而不是 null：调用方统一走「占位图」分支，不会 NPE。
     */
    private static String logoUrl(Map<?, ?> mod) {
        if (mod == null || !(mod.get("logo") instanceof Map<?, ?> logo)) {
            return "";
        }
        String url = str(logo.get("url"));
        if (url != null) {
            return url;
        }
        String thumb = str(logo.get("thumbnailUrl"));
        return thumb == null ? "" : thumb;
    }

    /** 取一个项目的完整信息（详情弹窗封面 / 描述 / 外链）。 */
    public static Project project(long modId) throws IOException {
        Object parsed = getJson(API + "/mods/" + modId);
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("data") instanceof Map<?, ?> m)) {
            throw new IOException("CurseForge 项目信息认不出来，稍后重试");
        }
        String website = null;
        if (m.get("links") instanceof Map<?, ?> links) {
            website = str(links.get("websiteUrl"));
        }
        List<String> cats = new ArrayList<>();
        if (m.get("categories") instanceof List<?> cl) {
            for (Object c : cl) {
                if (c instanceof Map<?, ?> cm && cm.get("name") instanceof String s) {
                    cats.add(s);
                }
            }
        }
        return new Project(modId, str(m.get("name")), str(m.get("summary")),
                num(m.get("downloadCount")), logoUrl(m), website, cats);
    }

    /**
     * 解析 {@code data[].dependencies[]} 为文件级依赖列表（0.8.7 / P1 前置提醒数据源）。
     * 字段缺失 / 类型异常按 0 处理、非法元素直接跳过，单条脏数据绝不影响整个文件列表。
     */
    private static List<FileDependency> dependencies(Object raw) {
        List<FileDependency> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add(new FileDependency(num(m.get("modId")), num(m.get("fileId")),
                            (int) num(m.get("relationType"))));
                }
            }
        }
        return out;
    }

    /** 取一个项目的全部文件版本（详情弹窗按游戏版本分组用）。 */
    public static List<File> files(long modId) throws IOException {
        Object parsed = getJson(API + "/mods/" + modId + "/files");
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("data") instanceof List<?> data)) {
            throw new IOException("CurseForge 版本列表认不出来，稍后重试");
        }
        List<File> out = new ArrayList<>();
        for (Object item : data) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            List<String> games = new ArrayList<>();
            if (m.get("gameVersions") instanceof List<?> g) {
                for (Object o : g) {
                    if (o instanceof String s) {
                        games.add(s);
                    }
                }
            }
            out.add(new File(num(m.get("id")), str(m.get("displayName")), str(m.get("fileName")),
                    games, str(m.get("downloadUrl")), str(m.get("fileDate")),
                    (int) num(m.get("releaseType")), num(m.get("fileLength")),
                    dependencies(m.get("dependencies"))));
        }
        return out;
    }

    /**
     * 拿某个文件的临时下载直链（CurseForge 不直接给匿名直链）。
     *
     * <p>r6：接口返回空 / 403 / 404（多为作者没在 CurseForge 开放第三方直接下载）时，
     * 抛玩家看得懂、可执行的完整中文，而不是含糊的「换一个版本试试」或英文堆栈。
     *
     * @return 直接可下到 mods 的临时 URL；拿不到抛可读中文 {@link IOException}
     */
    public static String downloadUrl(long modId, long fileId) throws IOException {
        final String noDirectUrl = "这个文件的作者没有在 CurseForge 开放直接下载，"
                + "请换一个版本，或到项目页面手动下载压缩包后用本地导入安装";
        final Object parsed;
        try {
            parsed = getJson(API + "/mods/" + modId + "/files/" + fileId + "/download-url");
        } catch (IOException ex) {
            String m = ex.getMessage() == null ? "" : ex.getMessage();
            // 纯网络 / 超时 / 中断按原异常上抛（该查网络，不该说成「作者没开放」）
            boolean networkOnly = (m.contains("超时") || m.contains("timed out")
                    || m.contains("timeout") || m.contains("中断") || m.contains("太慢")
                    || m.contains("连不上") || m.contains("网络"))
                    && !m.contains("403") && !m.contains("404");
            if (networkOnly) {
                throw ex;
            }
            // 403 / 404 / 接口返回空 / 认不出来：作者没开放直链，给玩家可执行的完整中文
            throw new IOException(noDirectUrl, ex);
        }
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("data") instanceof Map<?, ?> d)) {
            throw new IOException(noDirectUrl);
        }
        String url = str(d.get("downloadUrl"));
        if (url == null) {
            throw new IOException(noDirectUrl);   // data 里没有直链 = 作者没开放
        }
        return url;
    }

    /**
     * 全部分类（缓存一次）。用于 UI 胶囊。
     *
     * <p>0.8.6 修复：<b>空结果不写缓存</b>。原来一次接口返回空列表就会把
     * {@code CATEGORY_CACHE} 固化成空，之后永远不再拉，分类行就一直空着。现在空结果直接抛
     * {@link IOException}，UI 侧落到同步中文兜底分类并打日志，不留空行也不装死。
     */
    public static List<Category> categories() throws IOException {
        List<Category> cached = CATEGORY_CACHE;
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        Object parsed = getJson(API + "/categories?gameId=" + GAME_ID + "&classId=" + CLASS_ID_MODS);
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("data") instanceof List<?> data)) {
            throw new IOException("CurseForge 分类列表认不出来，稍后重试");
        }
        List<Category> out = new ArrayList<>();
        for (Object item : data) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            out.add(new Category(num(m.get("id")), str(m.get("name")), str(m.get("slug"))));
        }
        if (out.isEmpty()) {
            throw new IOException("CurseForge 分类列表是空的（接口无数据），稍后重试");
        }
        CATEGORY_CACHE = out;
        return out;
    }

    /**
     * 下载到 mods 目录 + 写索引（复用 Modrinth 同款落盘口径：同名跳过、.part 落地、取消无残留）。
     *
     * @param modId    项目 id（索引用）
     * @param file     一个文件
     * @param versionId 游戏版本 id（索引分目录）
     * @param cancelled 取消旗标
     * @return 落盘路径；取消返回 null
     */
    /** 旧 4 参入口：不带进度回调（向前兼容）。 */
    public static Path install(long modId, File file, String versionId, AtomicBoolean cancelled)
            throws IOException {
        return install(modId, file, versionId, cancelled, null);
    }

    /**
     * 下载到 mods 目录 + 写索引（复用 Modrinth 同款落盘口径：同名跳过、.part 落地、取消无残留）。
     *
     * @param modId      项目 id（索引用）
     * @param file       一个文件
     * @param versionId  游戏版本 id（索引分目录）
     * @param cancelled  取消旗标
     * @param listener   进度出口（可为 null）；CurseForge 文件无哈希，故不做完整性校验
     * @return 落盘路径；取消返回 null
     */
    public static Path install(long modId, File file, String versionId, AtomicBoolean cancelled,
                               FileFetch.ProgressListener listener) throws IOException {
        String url = file.downloadUrl();
        if (url == null || url.isBlank()) {
            // 文件没带直链——现换一份
            url = downloadUrl(modId, file.fileId());
        }
        if (url == null || url.isBlank()) {
            throw new IOException("这个 CurseForge 文件没有可用的下载地址，换一个版本试试");
        }
        Path mods = ModStore.modsDir();
        Files.createDirectories(mods);
        // 0.9.9：API 返回的 fileName 先净化再拼路径，并守住「必须落在 mods 目录内」。
        Path target = mods.resolve(safeFileName(file.fileName()));
        if (!target.startsWith(mods)) {
            throw new IOException("模组文件名不合法，拒绝安装: " + file.fileName());
        }
        if (Files.isRegularFile(target)) {
            // 同名已存在：跳过下载。0.9.9：跳过也补一条索引（项目 id + 文件 id + 文件名），
            // 否则别的模组 requires 指向它时，ModValidator 会因索引没有它而误报「缺前置」拦启动。
            ModIndex.add(versionId, new ModIndex.Entry(String.valueOf(modId),
                    String.valueOf(file.fileId()), target.getFileName().toString(), null,
                    java.time.Instant.now().toString(), List.of(), Map.of()));
            System.out.println("[curseforge] 「" + target.getFileName()
                    + "」已存在，跳过下载并登记到模组索引");
            return target;
        }
        boolean ok = FileFetch.download(url, target, null, null, cancelled, listener);
        if (!ok) {
            if (cancelled != null && cancelled.get()) {
                return null;
            }
            throw new IOException("「" + target.getFileName() + "」没下下来，检查网络后重试");
        }
        // 0.9.1 重发：Entry 追加 requiresTitles 字段。CurseForge 的依赖是数字 modId，
        // 与 ModIndex 用的 Modrinth base62 projectId 不是一套命名空间——若把 CF 依赖记进
        // requires，当前置其实是从 Modrinth 装的（或用户手动放进 mods 的）时会永远对不上，
        // 系统性误报「缺前置」并拦住启动。故 CF 这里：不记 requires、也不为反查名称发任何
        // 网络请求（纯数字 ID 本就会被 resolveTitles 过滤掉），titles 留空、按原 ID 兜底。
        ModIndex.add(versionId, new ModIndex.Entry(String.valueOf(modId),
                String.valueOf(file.fileId()), target.getFileName().toString(), null,
                java.time.Instant.now().toString(), List.of(), Map.of()));
        System.out.println("[curseforge] 「" + target.getFileName() + "」装好了");
        return target;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 0.9.9：安装落盘前的文件名净化。ContentStore.sanitizeName 是 private，这里做等价实现——
     * 取最后一段、去路径分隔符、拒 {@code .}/{@code ..}/含 {@code ..}、替换 Windows 非法字符，
     * 防 API 返回的 fileName 带路径穿越把文件写到 mods 目录之外。空文件名回退到 {@code mod.jar}。
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
     * 发一次 GET 拿 JSON：<b>国内镜像 MCIM（免 API Key）与官方（带 Key）并发竞速</b>，谁先回 2xx
     * 用谁；任一方慢 / 挂都不拖累另一方。MCIM 关掉时直接走官方单请求，零额外开销。非 2xx /
     * 网络错误抛 {@link IOException}（message 说人话）。
     *
     * <p>0.9.1：改调 {@code HttpRace.race(String, JsonFetch, JsonFetch)}，把<b>官方原始 url</b>
     * 作为短 TTL 缓存 key 传进去（60s 内同一 URL 命中即零网络往返）。key 用官方 URL 而非镜像
     * URL，保证 {@code GBL_MIRROR} 开关切换时 key 一致；镜像与官方返回的是同一份数据，
     * 带不带 {@code x-api-key} 不影响缓存内容。镜像关闭时仍走 {@code getJsonOnce(url, true)}
     * 单请求，既不竞速也不查缓存，保持零开销。
     */
    private static Object getJson(String url) throws IOException {
        String mirror = Mirror.rewriteContent(url);
        if (mirror.equals(url)) {
            // 镜像关了：官方带 Key，单请求零开销
            return getJsonOnce(url, true);
        }
        // 镜像不需要 Key（MCIM 绕开 Overwolf 的 Key 限制），官方带 Key
        return HttpRace.race(url,
                () -> getJsonOnce(mirror, false),
                () -> getJsonOnce(url, true));
    }

    /** 对单个地址发一次 GET 拿 JSON；{@code withKey} 为真才带 CurseForge API Key。 */
    private static Object getJsonOnce(String url, boolean withKey) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        if (withKey) {
            b.header("x-api-key", apiKey());
        }
        HttpRequest request = b.build();
        try {
            HttpResponse<String> response = CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 403 || response.statusCode() == 401) {
                throw new IOException("CurseForge 拒绝了请求（HTTP " + response.statusCode()
                        + "）：API Key 无效或没权限，去「设置」页检查 / 粘贴自己的 CurseForge API Key");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("CurseForge 接口暂时连不上（HTTP " + response.statusCode()
                        + "），稍后重试");
            }
            return Json.parse(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("拉取 CurseForge 数据被中断");
        } catch (IOException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new IOException("CurseForge 返回了一大段认不出来的东西，稍后重试");
        }
    }

    private static String encode(String text) {
        return URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8);
    }

    private static String str(Object raw) {
        return raw instanceof String text && !text.isBlank() ? text : null;
    }

    /** JSON 数字统一 Number 取 long。 */
    private static long num(Object raw) {
        return raw instanceof Number n ? n.longValue() : 0L;
    }
}
