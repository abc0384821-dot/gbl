package com.mclauncher;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * 下载源镜像：把 Mojang 官方域名的 URL 改写到国内镜像（BMCLAPI），只做「URL → URL」的重写与开关判断。
 *
 * <p><b>它不发起任何请求、不知道下载语义</b>——0.9.2 r4 起每个接入点（版本清单 / 客户端 jar /
 * 依赖库 / 资源 / 版本 JSON）用 {@link #vanillaCandidates} 拿镜像 + 官方双源交给 {@code RaceFetch}
 * 竞速，这里只负责按规则表产出候选，不再有「先镜像、失败回落官方一次」的串行链。
 * 加载器 meta（Fabric / Quilt / LegacyFabric）恒「镜像优先 → 官方回落」，<b>不受下载源开关控制</b>
 * （0.8.8 Q4：下载源开关语义是「Mojang 资源下载源」，不决定加载器 meta 域，见 LoaderInstaller）；
 * Modrinth <b>不走镜像</b>（国内多数可直连，架构已拍板）。
 *
 * <p>规则表驱动（架构 §1.6）：命中 {@code fromHost} 就换基地址、路径原样拼接。
 * <b>0.9.2 r2 修订</b>：piston-meta 的 {@code /v1/packages/<hash>/<ver>.json} 在 BMCLAPI 上
 * 就是原样路径（实测 206），不再另认目录结构；piston-data（client.jar）在 BMCLAPI 上 302 回
 * 官方、无加速价值，改写只会 404，故<b>不改写、原样返回</b>。认不出的主机原样返回，
 * 由接入点的「回落官方」兜底——映射不准最坏是这一次请求慢一拍，功能不瘫。
 *
 * <p>两个入口共享同一张规则表：{@link #rewrite} 受「下载源」设置控制（官方源恒等），
 * {@link #rewriteVanilla} 不受设置控制、恒把原版游戏链路改写到 BMCLAPI（只受
 * {@code GBL_MIRROR=off} 全局开关约束），供「补依赖前移到安装阶段」使用。
 */
public final class Mirror {

    /** 下载源。UI 上直接显示 {@link #label()}。 */
    public enum Source {
        OFFICIAL("官方"),
        BMCLAPI("BMCLAPI 镜像");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 一条镜像规则：官方主机名 → 镜像基地址。{@code note} 只说给人听，不参与逻辑。 */
    public record Rule(String fromHost, String toBase, String note) {
    }

    /**
     * 规则表。顺序即优先级（主机名不会重复，顺序其实无所谓）。
     *
     * <p>三条「路径原样拼接」的注意尾斜杠：{@code libraries.minecraft.net} 与
     * {@code resources.download.minecraft.net} 的镜像基地址自带分类前缀且以 {@code /} 收尾，
     * 官方路径以 {@code /} 起笔，拼接时去掉一个斜杠，别拼出 {@code //}。
     */
    public static final List<Rule> RULES = List.of(
            new Rule("launchermeta.mojang.com", "https://bmclapi2.bangbang93.com",
                    "路径原样拼接（version_manifest.json）"),
            new Rule("piston-meta.mojang.com", "https://bmclapi2.bangbang93.com",
                    "路径原样拼接（/v1/packages/<hash>/<ver>.json，BMCLAPI 实测 206）"),
            // 0.9.2 r2 实测订正（硬 bug）：BMCLAPI 的依赖库挂在 /maven/ 下，/libraries/ 会 404。
            // 用真实 1.20.1 version.json 的三个库并发比对：/libraries/ 一个 404、一个 28.5s；
            // /maven/ 三个都 200 且字节数一致。原规则会让恒镜像主源整批撞 404，
            // 每个库都要白等一次超时才回落官方，这正是「装 26.3 很久」的放大器。
            new Rule("libraries.minecraft.net", "https://bmclapi2.bangbang93.com/maven",
                    "BMCLAPI 依赖库真实路径是 /maven/，不是 /libraries/"),
            new Rule("resources.download.minecraft.net", "https://bmclapi2.bangbang93.com/assets",
                    "路径原样拼接"));

    /**
     * 原版恒镜像链路（{@link #rewriteVanilla}）专用开关：{@code GBL_MIRROR=off} 时整体关闭。
     *
     * <p>判断表达式<b>照抄内容源 {@link #rewriteContent} 的关法</b>，但用独立常量，与内容源
     * 那套（{@code CONTENT_MIRROR_ENABLED} 及其 5 条 MCIM 映射）<b>完全隔离、互不牵连</b>：
     * 0.9.2 r2 回归红线要求内容源链路逐字不动，这里不复用、不重命名它的任何标识符。
     */
    private static final boolean VANILLA_MIRROR_ENABLED =
            !"off".equalsIgnoreCase(trimToNull(System.getenv("GBL_MIRROR")));

    private Mirror() {
        // 静态工具类，别让人 new 出来
    }

    /**
     * 当前下载源：读设置（{@link SettingsStore#downloadSource()}），切源即时生效、不用重启。
     *
     * <p>设置里存的是 {@link SettingsStore.DownloadSource}（持久化口径），这里换成
     * {@link Source}（重写口径）——两个枚举各管一头（架构 §1.6 / §3 就是这么分的），
     * 按名字对齐：BMCLAPI 对 BMCLAPI，其余（含将来的新源）一律按官方走，
     * 「多出来的源不会重写」比「少认一个源把用户关在镜像外」安全。
     */
    public static Source currentSource() {
        SettingsStore.DownloadSource stored = SettingsStore.load().downloadSource();
        return stored == SettingsStore.DownloadSource.BMCLAPI ? Source.BMCLAPI : Source.OFFICIAL;
    }

    /** 镜像开没开。官方源恒 false——此时 {@link #rewrite} 是恒等函数。 */
    public static boolean enabled() {
        return currentSource() != Source.OFFICIAL;
    }

    /**
     * 把官方 URL 改写成镜像 URL（受「下载源」设置控制：官方源恒等）。
     *
     * <p>空 URL、认不出的主机——一律原样返回（调用方接着回落官方）。规则表的应用抽到
     * {@link #applyRules}，与 {@link #rewriteVanilla} 共用，避免两处各维护一张表。
     *
     * @param url 官方下载地址
     * @return 镜像地址；不需要（或不能）改写时就是传进来的那个
     */
    public static String rewrite(String url) {
        if (url == null || url.isBlank() || !enabled()) {
            return url;
        }
        return applyRules(url);
    }

    /**
     * 原版游戏链路<b>恒镜像</b>：不受「下载源」设置控制，把 client.jar / libraries /
     * assets / 版本 JSON 一律改写到 BMCLAPI（0.9.2 r2 问题 7：「GBL 下载很久」）。
     *
     * <p>门禁只看 {@code GBL_MIRROR=off}（专用独立开关 {@code VANILLA_MIRROR_ENABLED}，
     * 表达式照抄 {@link #rewriteContent} 的关法但与之完全隔离，不触碰内容源那套）；
     * <b>不看 {@link #enabled()}</b>——默认下载源是 OFFICIAL，
     * 若沿用 rewrite 的门禁就等于恒等、原版链路全走官方海外域名，这正是「很久」的根因。
     *
     * <p>规则表与 {@link #rewrite} 完全共享（{@link #applyRules}）。注意 piston-data
     * （client.jar）按规则表<b>不改写</b>：BMCLAPI 对它 302 回官方、无加速价值，改写只会 404
     * 并触发 {@code GameDownloader.fail()} 用官方地址把整个 jar 重下一遍。
     *
     * @param url 官方下载地址
     * @return BMCLAPI 镜像地址；全局开关关闭 / 空 URL / 认不出的主机（含 piston-data）原样返回
     */
    public static String rewriteVanilla(String url) {
        // 0.9.2 r4：内部委托到双源候选出口的首元素（镜像 URL），对外语义与 r2 完全一致。
        // 方法名与 VANILLA_MIRROR_ENABLED 字段保留（构建指纹），实际下载链路的接入点
        // 已改用 {@link #vanillaCandidates} 交给 RaceFetch 双源竞速，不再走「先镜像、失败再串行回落官方」。
        return vanillaCandidates(url)[0];
    }

    /**
     * 返回 {@code {镜像URL, 官方URL}} 双源候选，供 {@code RaceFetch} 同时竞速、取最快 2xx。
     *
     * <p>0.9.2 r4：替代 r2「恒镜像 + 失败后串行回落官方」——那种写法下每个慢库都要白等
     * 一个超时才回落，88 个库里 15 个超时反而把整批拖慢。这里把镜像与官方一并交给竞速层，
     * 谁先返回有效 2xx 就用谁，慢线被当场淘汰，不再串行等待。
     *
     * <p>复用现有 {@link #RULES} / {@link #applyRules} 的改写结果生成镜像 URL；
     * {@code officialUrl} 原样作为第二候选。以下情形退化为单元素 {@code {官方URL}}
     * （调用方据此把 secondary 传 null，语义与 r2 的 GBL_MIRROR=off 一致）：
     * {@code GBL_MIRROR=off}（{@link #VANILLA_MIRROR_ENABLED}）、piston-data / 认不出的主机
     * （{@link #applyRules} 原样返回）、空 URL。
     *
     * <p>只作用于原版游戏链路的规则表，<b>不触碰</b>内容源 {@link #rewriteContent} 的
     * 5 条 MCIM 映射与 {@link #CONTENT_MIRROR_ENABLED}（回归红线：逐字不动）。
     */
    public static String[] vanillaCandidates(String officialUrl) {
        if (!VANILLA_MIRROR_ENABLED || officialUrl == null || officialUrl.isBlank()) {
            return new String[]{officialUrl};
        }
        String mirror = applyRules(officialUrl);
        if (mirror == null || mirror.isBlank() || mirror.equals(officialUrl)) {
            // piston-data（client.jar）/ 认不出的主机：没有可用镜像，退化为单源
            return new String[]{officialUrl};
        }
        return new String[]{mirror, officialUrl};
    }

    /** 规则表应用：命中 {@code fromHost} 就「基地址 + 原路径」原样拼接；认不出原样返回。 */
    private static String applyRules(String url) {
        URI uri = parse(url);
        if (uri == null) {
            return url;
        }
        String host = uri.getHost();
        String path = uri.getPath();
        if (host == null || path == null) {
            return url;
        }
        for (Rule rule : RULES) {
            if (rule.fromHost().equalsIgnoreCase(host)) {
                return join(rule.toBase(), path);
            }
        }
        return url;
    }

    /** 基地址 + 路径：两边都可能有斜杠，拼出来只留一个。 */
    private static String join(String base, String path) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String p = path.startsWith("/") ? path : "/" + path;
        return b + p;
    }

    /** 解析失败（不是个像样的 URL）返回 null，调用方原样返回不硬改。 */
    private static URI parse(String url) {
        try {
            return URI.create(url.trim());
        } catch (RuntimeException ex) {
            System.err.println("[mirror] URL 解析不了，按不改写处理: " + url);
            return null;
        }
    }

    /** 主机名小写化后的样子（只给日志和排查用，逻辑里不认大小写）。 */
    static String hostOf(String url) {
        URI uri = parse(url);
        return uri == null || uri.getHost() == null ? null : uri.getHost().toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------- 内容源镜像（MCIM）

    /**
     * 内容源（Modrinth / CurseForge）镜像（MCIM，{@code mod.mcimirror.top}）的 URL 映射表。
     *
     * <p><b>0.9.1 实测订正（勿再按旧结论走）：</b>过往以为「HMCL 无代理秒下靠把文件换成 MCIM
     * 境内镜像」，查 HMCL 源码后确认<b>相反</b>：{@code HMCLDownloadProvider.java:150-154} 的规则是
     * {@code SimpleRule("https://cdn.modrinth.com", null, "https://mod.mcimirror.top")}
     * （{@code first=null, fallback=MCIM}）→ 候选顺序<b>恒为 [官方 cdn, MCIM]、官方在前</b>，
     * {@code FetchTask} 串行回退；HMCL 零配置快来自 {@code EntryPoint.java:33} 的
     * {@code java.net.useSystemProxies=true} + {@code ProxyManager.java:26} 的
     * {@code ProxySelector.getDefault()} 自动读 Windows 系统代理，<b>不是</b>靠 MCIM 文件镜像。
     *
     * <p>因此 GBL 的<b>文件</b>下载必须保持官方 URL 原样，交给 {@link ProxyRoute} 命中出海域做选路；
     * 若把文件 URL 改写成 {@code mod.mcimirror.top}（该域不在 {@code ProxyRoute.OVERSEAS}），
     * 选路会被整段绕过、文件恒直连慢线。本套映射现在主要服务<b>元数据 API</b>
     * （{@link ModrinthClient} / {@link CurseForgeClient} / {@link Net} 的 getJson / 封面，
     * 实测 200、境内 IP、搜索快）以及 {@link FileFetch} 主源彻底失败后的 MCIM <b>兜底候选</b>。
     *
     * <p>与上面的 Mojang/BMCLAPI 镜像<b>两套独立</b>：那个受「下载源」设置控制；这个不受设置影响
     * （除非 {@code GBL_MIRROR=off} 整体关闭）。
     *
     * <p>映射（与 HMCL 同款；<b>注意这是「官方→MCIM」的映射表，不代表候选优先级</b>）：
     * <pre>
     *   api.modrinth.com   -&gt; mod.mcimirror.top/modrinth
     *   cdn.modrinth.com   -&gt; mod.mcimirror.top
     *   api.curseforge.com -&gt; mod.mcimirror.top/curseforge
     *   edge.forgecdn.net  -&gt; mod.mcimirror.top
     *   media.forgecdn.net -&gt; mod.mcimirror.top
     * </pre>
     *
     * <p><b>MCIM 元数据 API 仍可用（实测 200、境内 IP）；文件分发上游已公告暂停</b>
     * （{@code mcim-rust-api} README：「OpenMCIM 已暂停运行……文件下载暂时不提供」）：
     * 文件 URL 普遍返回 302、0 字节，Location 回官方出海 CDN，跟随只会多一跳、无加速。
     * 故 {@link FileFetch} 对 MCIM 兜底先做不跟随重定向的 302 预检，判定无效即跳过、不跟随。
     */
    private static final boolean CONTENT_MIRROR_ENABLED =
            !"off".equalsIgnoreCase(trimToNull(System.getenv("GBL_MIRROR")));

    /**
     * 把内容源 URL 换成 MCIM 镜像地址；命中不了、镜像关了或 URL 为空时原样返回。
     * 现在主要用于<b>元数据 API 的镜像优先</b>与 {@link FileFetch} 主源失败后的 MCIM 兜底候选；
     * 文件主源不再调用它（保持官方 URL 才能让 {@link ProxyRoute} 选路生效）。
     */
    public static String rewriteContent(String url) {
        if (!CONTENT_MIRROR_ENABLED || url == null || url.isBlank()) {
            return url;
        }
        if (url.startsWith("https://api.modrinth.com/")) {
            return "https://mod.mcimirror.top/modrinth"
                    + url.substring("https://api.modrinth.com".length());
        }
        if (url.startsWith("https://cdn.modrinth.com")) {
            return "https://mod.mcimirror.top"
                    + url.substring("https://cdn.modrinth.com".length());
        }
        if (url.startsWith("https://api.curseforge.com/")) {
            return "https://mod.mcimirror.top/curseforge"
                    + url.substring("https://api.curseforge.com".length());
        }
        if (url.startsWith("https://edge.forgecdn.net")) {
            return "https://mod.mcimirror.top"
                    + url.substring("https://edge.forgecdn.net".length());
        }
        if (url.startsWith("https://media.forgecdn.net")) {
            return "https://mod.mcimirror.top"
                    + url.substring("https://media.forgecdn.net".length());
        }
        return url;
    }

    /** 该 URL 是否已落在 MCIM 镜像域内（CurseForge 据此决定是否带 API Key）。 */
    public static boolean isContentMirrored(String url) {
        return url != null && url.startsWith("https://mod.mcimirror.top");
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
