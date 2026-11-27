package com.mclauncher;

import javafx.application.Platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 自动更新：后台查最新版本。
 *
 * <p>0.9.9 r9：按「主源 → 备源」顺序遍历多个真正返回 JSON 的 {@code version.json}：
 * 主源是官网静态 json（Cloudflare Pages，带 sha256 + size + github 备源），备源是同一份 json 的
 * GitHub raw 镜像。旧版兜底指向 Gitee 的 {@code /releases/latest} 网页端点（302 到 HTML，
 * 几乎必然解析不出 JSON），且主源只重试 2 次、间隔为 0，两者叠加导致「一次瞬时失败 = 本次检查
 * 彻底没有更新」。现在每个源内部最多 3 次重试（200ms / 400ms 递增退避），任一源拿到合法 JSON
 * 即用；全失败才回传带源标签的可读原因给界面。
 *
 * <p>sha256 / size 是安装器判断下载包是否完整的契约字段，只从可信源读取；
 * 换源重试与严格校验都在 {@link UpdateInstaller} 里做。
 *
 * <p>任何异常一律静默吞掉——更新是「有了更好」，绝不能因为网络抖动弹错或卡界面。
 * 下载走 {@link HttpClient} 的 {@code Redirect.NORMAL}：Gitee 直链有两级 302
 * （releases/download → attach_files → foruda），必须跟随重定向（RaceFetch 那条
 * 竞速连接不能用，会丢重定向）；版本请求固定 HTTP/1.1 + 20 秒超时（0.9.9 r4 由 8 秒放宽，
 * 实测 Cloudflare 冷连接 TTFB 已到 7.02 秒，8 秒太贴边）。
 */
public final class UpdateChecker {

    /** 主版本源：官网静态 json。 */
    public static final String VERSION_URL = "https://gbl-website.pages.dev/version.json";
    /** 0.9.9 r9 兜底版本源：GitHub raw 静态 version.json（真 JSON，与官网同源镜像）。常量名保留以免外部引用断裂。 */
    public static final String FALLBACK_URL = "https://raw.githubusercontent.com/abc0384821-dot/gbl-website/main/version.json";
    /** 0.9.9 r9：真正返回 JSON 的版本源列表，数组顺序即回退顺序：主源在前，GitHub raw 备源在后。 */
    private static final String[] VERSION_SOURCES = { VERSION_URL, FALLBACK_URL };

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            // 0.9.8：强制 HTTP/1.1。默认 HTTP/2 在高延迟链路上会把窗口压死，
            // 版本检查本身只是几 KB，但同样会被拖到超时，看起来像「没有自动更新」。
            .version(HttpClient.Version.HTTP_1_1)
            // 0.9.9 r4：跟随「线路」设置。旧版没设 selector，用户设了代理后只有游戏下载走代理，
            // 版本检查仍然裸连 —— 在需要代理才通外网的机器上表现为「永远查不到更新」。
            .proxy(ProxyRoute.selector())
            // 0.9.9 r4：8 秒改 20 秒。实测 Cloudflare Pages 冷连接 TTFB 已到 7.02 秒，
            // 8 秒几乎贴边，用户网络稍差就必然超时，白丢一次检查机会。
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private UpdateChecker() {
    }

    /**
     * 0.9.9 r9：一次检查里累积的失败信息。{@code reason} 记首个可读失败原因（保持旧语义）；
     * {@link #notes} 收集每条「哪个源、为什么失败」的详情，全部源失败时拼起来回传给 UI；
     * {@code latest} 标记「成功拿到版本但已是最新」——这种不是失败，不能回传给 UI 的 onFailed。
     */
    private static final class Failure {
        String reason;
        boolean latest;
        private final java.util.List<String> notes = new java.util.ArrayList<>();

        /** 0.9.9 r9：记录一条带来源标签的失败详情，全部源失败时一并回传给用户。 */
        void note(String detail) {
            if (detail != null && !detail.isBlank()) {
                notes.add(detail);
            }
        }

        void record(String s) {
            if (reason == null && s != null) {
                reason = s;
            }
        }

        String get() {
            return reason;
        }

        /** 全部失败时给 UI 的可读原因：带来源的详情优先（用；连接），否则退回首个短原因。 */
        String getDetail() {
            if (!notes.isEmpty()) {
                return String.join("；", notes);
            }
            return reason;
        }
    }

    /** 后台查更新；查到比当前新的版本才回调（在主线程）。查不到 / 出错一律不回调。 */
    public static void checkAsync(Consumer<UpdateInfo> onFound) {
        checkAsync(onFound, null);
    }

    /**
     * 0.9.9 r6：带失败原因的后台检查。
     *
     * <p>查到新版回调 {@code onFound}；真正拉取 / 解析失败（超时 / HTTP 状态 / JSON 解析失败 /
     * Content-Type 不对等）时在 FX 线程把可读原因回传给 {@code onFailed}。「已是最新版」不是失败，
     * 不回调 onFailed。两个回调都在 FX 线程执行，调用方无需再切线程。
     */
    public static void checkAsync(Consumer<UpdateInfo> onFound, Consumer<String> onFailed) {
        new Thread(() -> {
            Failure f = new Failure();
            try {
                UpdateInfo info = check(f);
                if (info != null) {
                    final UpdateInfo found = info;
                    Platform.runLater(() -> onFound.accept(found));
                } else if (onFailed != null && !f.latest) {
                    // 1.2.7：f.latest（已是最新）绝不回调 onFailed —— 保持静默。
                    // 失败原因只交给非阻断钩子，由 UI 压成一句友好短提示，不堆技术串。
                    final String reason = f.getDetail() == null ? "检查一下网络再试" : f.getDetail();
                    Platform.runLater(() -> onFailed.accept(reason));
                }
            } catch (Throwable t) {
                System.err.println("[update] 检查更新异常: " + t);
                if (onFailed != null) {
                    final String reason = readableReason(t);
                    Platform.runLater(() -> onFailed.accept(reason));
                }
            }
        }, "gbl-update-check").start();
    }

    static UpdateInfo check() {
        return check(new Failure());
    }

    private static UpdateInfo check(Failure f) {
        UpdateInfo info = checkAllSources(f);
        if (info == null) {
            return null;
        }
        if (!updateAvailable(info)) {
            f.latest = true;   // 拿到版本但已是最新：不是失败，别回传 onFailed
            return null;
        }
        return info;
    }

    /**
     * 0.9.9 r9：按 {@link #VERSION_SOURCES} 顺序逐源尝试，任一源拿到合法 JSON 立即返回。
     *
     * <p>所有源都传 {@code trusted=true}：GitHub raw 备源是官网 version.json 的同源镜像，
     * 同样带 sha256 / size / github 字段，可信；旧版给「兜底源」传 false 是因为它指向 HTML 网页。
     * 全失败时原因由各源的 parse / fetch 写进 {@code Failure}，交给调用方回传界面。
     */
    private static UpdateInfo checkAllSources(Failure f) {
        for (String url : VERSION_SOURCES) {
            UpdateInfo info = parseWithRetry(url, true, f);
            if (info != null) {
                return info;
            }
        }
        return null;
    }

    /** 手动「检查更新」的三态结果：查到新版 / 已是最新 / 查不到（网络或服务器问题）。 */
    public enum CheckState {
        /** 有比当前更新的版本。 */
        FOUND,
        /** 服务器说当前就是最新版。 */
        LATEST,
        /** 两个源都没拿到可用的版本信息（多为断网 / 被拦截）。 */
        FAILED
    }

    /** 手动检查的完整结果；{@code info} 在 LATEST / FAILED 时可能是 null。 */
    public record ManualResult(CheckState state, UpdateInfo info) {
    }

    /**
     * 0.9.7：给设置页「检查更新」按钮用的一次性同步查询。
     *
     * <p>与 {@link #check()} 的区别是它把「已是最新」和「查不到」分开了 ——
     * 后台自动检查不需要区分（都是不打扰用户），但用户主动点按钮时必须说清是哪一种，
     * 否则「点了没反应」和「已经最新」在界面上长得一模一样。
     * 网络请求可能耗时，调用方必须放在后台线程里跑。
     */
    public static ManualResult checkManual() {
        ManualResultEx ex = checkManualWithReason();
        return new ManualResult(ex.state(), ex.info());
    }

    /** 0.9.9 r6：带可读失败原因的手动检查结果；{@code reason} 为 null 表示没有失败。 */
    public record ManualResultEx(CheckState state, UpdateInfo info, String reason) {
    }

    /**
     * 0.9.9 r6：带失败原因的手动检查。设置页可在 FAILED 时展示真实原因，不再只有固定文案
     * 「检查一下网络再试」。FOUND / LATEST 时 reason 为 null。
     */
    public static ManualResultEx checkManualWithReason() {
        Failure f = new Failure();
        try {
            UpdateInfo info = checkAllSources(f);
            if (info == null) {
                return new ManualResultEx(CheckState.FAILED, null,
                        f.getDetail() == null ? "检查一下网络再试" : f.getDetail());
            }
            return updateAvailable(info)
                    ? new ManualResultEx(CheckState.FOUND, info, null)
                    : new ManualResultEx(CheckState.LATEST, info, null);
        } catch (Throwable t) {
            System.err.println("[update] 手动检查更新失败: " + t);
            return new ManualResultEx(CheckState.FAILED, null, readableReason(t));
        }
    }

    /** 0.9.8 r2 指纹：补丁号让旧更新器也能收到同版本重发。构建门禁扫常量池，勿改这串字。 */
    private static final String FP_098_R2_PATCH = "0.9.8 r2 补丁号触发旧更新器";
    static { System.err.println("[update] " + FP_098_R2_PATCH); }

    /** 0.9.8 r3 指纹：补丁段与构建号加固。构建门禁扫常量池，勿改这串字。 */
    private static final String FP_098_R3_PATCH = "0.9.8 r3 补丁段与构建号加固";
    static { System.err.println("[update] " + FP_098_R3_PATCH); }

    /** 0.9.9 r9 指纹：更新检查多源容灾与重试加固。构建门禁扫常量池，勿改这串字。 */
    public static final String FP_099_R9_UPDATE_RESILIENCE = "0.9.9 r9 更新检查多源容灾与重试加固";
    static { System.err.println("[update] " + FP_099_R9_UPDATE_RESILIENCE); }

    /** 1.0.2 指纹：语义版本不再被误判成补丁号，自动更新恢复。勿改这串字。 */
    public static final String FP_102_UPDATE_LOGIC = "GBL 1.0.2 语义版本更新判断修复";
    static { System.err.println("[update] " + FP_102_UPDATE_LOGIC); }

    /**
     * 1.2.7 指纹：所有联网失败都只回传可读原因给非阻断钩子；「已是最新」不是失败，
     * 绝不再回调 onFailed（保持静默）。勿改这串字。
     */
    public static final String FP_1_2_7_CHECK_DEGRADE = "GBL 1.2.7 检查失败可读降级已是最新静默";
    static { System.err.println("[update] " + FP_1_2_7_CHECK_DEGRADE); }

    /**
     * 0.9.8 r2 / r3：同版本重发也要能被自动更新推到用户手上。
     *
     * <p>r1 / 0.9.7 旧客户端只比 version 数字段，所以线上 version.json 写的是当前版本的
     * 补丁号（0.9.8.1 / 0.9.8.2）才能把它们拉上来。本版客户端不能跟着一遍遍提示，于是
     * 补丁号分支同时看「补丁段数字」和「build 批次」：
     *
     * <ul>
     *   <li>补丁段比本机当前补丁段大 → 算更新；小 → 是回滚，不算。</li>
     *   <li>补丁段相同 → 再比 build 批次，只有更大才算更新（装完 r3 不反复提示）。</li>
     *   <li>build 格式异常 / 溢出 → 记一条可见告警并保守返回 false，绝不误报。</li>
     * </ul>
     *
     * <p>本机补丁段按批次号推导：r1→0、r2→1、r3→2（buildNumber - 1，下限 0）。
     * 真正更高的版本（0.9.9 等）与补丁号无关，照常提示。
     */
    private static boolean updateAvailable(UpdateInfo info) {
        if (info == null) {
            System.err.println("[update] 版本信息为空，保守不提示更新");
            return false;
        }
        String candidate = info.version();
        if (candidate == null || candidate.isBlank()) {
            System.err.println("[update] 版本源没给版本号，保守不提示更新");
            return false;
        }
        try {
            // 补丁号先单独处理：0.9.8.0 这类「多一段 0」在 AppVersion.compare 里和
            // 0.9.8 相等，若落到同版本分支只看 build，会把更旧的补丁段误报成更新。
            // 1.0.2 修复：补丁号方案（0.9.8.0/0.9.9.N）以 build=rN 为前提。
            // 1.0 起 version/build 都用语义版本号，candidate "1.0.1"/"1.0.2" 会被
            // isPatchOfCurrent 误当成 "1.0" 的补丁号，但 build 不是 rN → patchUpdateAvailable
            // 保守返回 false → 误判"已是最新版"、更新弹窗弹不出来。加 build 格式门禁，
            // 让语义版本发布走下面 AppVersion.isNewer 的正道；老 rN 客户端不受影响。
            if (isValidBuild(info.build()) && isPatchOfCurrent(candidate)) {
                return patchUpdateAvailable(info);
            }
            if (AppVersion.isNewer(LauncherApp.VERSION, candidate)) {
                return true;   // 1.0 -> 1.0.1/1.0.2 这类语义版本升级，与 build 批次无关
            }
            if (AppVersion.compare(LauncherApp.VERSION, candidate) != 0) {
                return false;  // 线上版本比本机旧：回滚，不提示
            }
            // 版本号完全相同：再比 build。旧版 build 是 rN，1.0 起是语义版本号，两种口径都支持。
            if (isValidBuild(info.build()) && isValidBuild(LauncherApp.BUILD)) {
                return buildNumber(info.build()) > buildNumber(LauncherApp.BUILD);
            }
            if (info.build() == null || info.build().isBlank()) {
                return false;
            }
            return AppVersion.isNewer(LauncherApp.BUILD, info.build());
        } catch (RuntimeException ex) {
            // AppVersion 用 Long 解析数字段，畸形 / 超长版本串可能抛异常；
            // 更新判据必须永远「宁可不提示，也不能误报或卡住启动」。
            System.err.println("[update] 版本号解析异常，保守不提示更新: " + candidate + " -> " + ex);
            return false;
        }
    }

    /** 补丁号分支：补丁段数字优先，相同再看 build 批次。 */
    private static boolean patchUpdateAvailable(UpdateInfo info) {
        if (!isValidBuild(info.build())) {
            System.err.println("[update] 版本源 build 号格式异常，保守不提示更新: " + info.build());
            return false;
        }
        int candidateBuild = buildNumber(info.build());
        if (candidateBuild <= 0) {
            System.err.println("[update] 版本源 build 号超出范围，保守不提示更新: " + info.build());
            return false;
        }
        int currentBuild = buildNumber(LauncherApp.BUILD);
        long candidatePatch = patchNumberOf(info.version());
        if (candidatePatch < 0) {
            System.err.println("[update] 补丁号超出范围，保守不提示更新: " + info.version());
            return false;
        }
        long currentPatch = Math.max(0L, currentBuild - 1L);
        if (candidatePatch != currentPatch) {
            return candidatePatch > currentPatch;
        }
        return candidateBuild > currentBuild;
    }

    /**
     * 判断 candidate 是不是「当前版本 + 一个点 + 一段非空纯数字」，例如 0.9.8.2 相对 0.9.8。
     * 0.9.8.1.2 / 0.9.8.beta / 0.9.8.1x / 0.9.8. 都不算，避免被宽松 startsWith 误判。
     */
    private static boolean isPatchOfCurrent(String candidate) {
        if (candidate == null) {
            return false;
        }
        String head = LauncherApp.VERSION + ".";
        if (!candidate.startsWith(head)) {
            return false;
        }
        String tail = candidate.substring(head.length());
        if (tail.isEmpty() || tail.indexOf('.') >= 0) {
            return false;
        }
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** 取补丁段数字；超长溢出返回 -1，由调用方保守处理。 */
    private static long patchNumberOf(String candidate) {
        String tail = candidate.substring(LauncherApp.VERSION.length() + 1);
        try {
            return Long.parseLong(tail);
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    /** 只认 r + 纯数字（r1 / r2 / r3）；其它格式或溢出告警后按 0 处理。 */
    private static int buildNumber(String build) {
        if (!isValidBuild(build)) {
            System.err.println("[update] build 号格式异常（按 0 处理）: " + build);
            return 0;
        }
        try {
            return Integer.parseInt(build.substring(1));
        } catch (NumberFormatException ex) {
            System.err.println("[update] build 号超出范围（按 0 处理）: " + build);
            return 0;
        }
    }

    private static boolean isValidBuild(String build) {
        if (build == null || build.length() < 2 || build.charAt(0) != 'r') {
            return false;
        }
        for (int i = 1; i < build.length(); i++) {
            char c = build.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * 0.9.9 r9：单个版本源最多尝试 3 次，每次之间递增退避 200ms / 400ms。
     *
     * <p>原因：官网静态 json 走 Cloudflare，冷连接首次请求偶发超时 / 连接被重置；
     * 旧版只重试 1 次（共 2 次）且两次之间没有间隔，瞬时失败就直接换源。这里给每次源内尝试
     * 加短退避，显著提高单次检查拿到版本的概率。被打断时恢复中断标志并按失败处理。
     */
    private static UpdateInfo parseWithRetry(String url, boolean trusted, Failure f) {
        for (int attempt = 0; attempt < 3; attempt++) {
            UpdateInfo info = parse(url, trusted, f);
            if (info != null) {
                return info;
            }
            if (attempt < 2) {
                System.err.println("[update] 版本源 " + url + " 第 " + (attempt + 1)
                        + " 次没拉到，退避 " + (200L * (attempt + 1)) + "ms 后重试");
                try {
                    Thread.sleep(200L * (attempt + 1));   // 200ms, 400ms
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    f.record("检查更新被中断");
                    f.note(sourceLabel(url) + "：检查被中断");
                    return null;
                }
            }
        }
        return null;
    }

    /** 0.9.9 r9：给失败原因加可读的源标签，让用户知道是哪个源、为什么失败。 */
    private static String sourceLabel(String url) {
        if (VERSION_URL.equals(url)) {
            return "官网源";
        }
        if (FALLBACK_URL.equals(url)) {
            return "GitHub备源";
        }
        return "版本源";
    }

    private static UpdateInfo parse(String url, boolean trusted, Failure f) {
        String body = fetch(url, f);
        if (body == null) {
            return null;
        }
        // 0.9.8 r3：网页端点 / Cloudflare 软 404 首页即便侥幸过了 Content-Type 门槛，
        // 解析前再按形态挡一道：必须是 JSON 对象开头，否则记一条日志后放弃这个源。
        String trimmed = body.trim();
        if (!trimmed.startsWith("{")) {
            System.err.println("[update] 版本源不是 JSON 对象，已跳过: " + url);
            f.record("版本源返回的不是 JSON 对象");
            f.note(sourceLabel(url) + "：返回的不是 JSON 对象");
            return null;
        }
        try {
            Object parsed = Json.parse(trimmed);
            if (!(parsed instanceof Map<?, ?> map)) {
                System.err.println("[update] 版本源不是 JSON 对象，已跳过: " + url);
                f.record("版本源返回的不是 JSON 对象");
                f.note(sourceLabel(url) + "：返回的不是 JSON 对象");
                return null;
            }
            String version = str(map.get("version"));
            String build = str(map.get("build"));
            String dlUrl = str(map.get("url"));
            if (version == null || version.isBlank() || dlUrl == null || dlUrl.isBlank()) {
                System.err.println("[update] 版本源缺少 version / url 字段，已跳过: " + url);
                f.record("版本源缺少 version / url 字段");
                f.note(sourceLabel(url) + "：缺少 version / url 字段");
                return null;
            }
            String sha = trusted ? str(map.get("sha256")) : null;
            long size = 0;
            Object sz = map.get("size");
            if (sz instanceof Number) {
                size = ((Number) sz).longValue();
            }
            // 0.9.8：把 version.json 里一直没人读的 github 备源接进更新链路。
            String alt = trusted ? str(map.get("github")) : null;
            return new UpdateInfo(version, build, dlUrl, sha, size, alt);
        } catch (RuntimeException ex) {
            // JSON 解析失败保护：HTML 页面、半截响应、畸形字段都只记日志，不抛给界面。
            System.err.println("[update] 解析版本源失败（已忽略）: " + url + " -> " + ex);
            f.record("解析版本数据失败");
            f.note(sourceLabel(url) + "：解析 JSON 失败");
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String fetch(String url, Failure f) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    // 0.9.9 r4：8 秒改 20 秒（与 client connectTimeout 同步），冷连接 TTFB 7 秒不再贴边超时。
                    .timeout(Duration.ofSeconds(20))
                    // 0.9.9 r4：带上与游戏下载一致的 UA，否则 Cloudflare / Gitee 会把我们当爬虫降速或 403。
                    .header("User-Agent", GameDownloader.AGENT)
                    .GET()
                    .build();
            HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                f.record("版本源返回 HTTP " + resp.statusCode());
                f.note(sourceLabel(url) + "：HTTP " + resp.statusCode());
                return null;
            }
            String ct = resp.headers().firstValue("Content-Type").orElse("").toLowerCase();
            // Cloudflare 软 404：不存在路径返回 200 + text/html 首页，必须按内容类型挡掉，
            // 否则会把首页 HTML 当版本数据解析出空字段，假绿。
            if (!ct.contains("json")) {
                f.record("版本源返回的内容不是 JSON（Content-Type 不对）");
                f.note(sourceLabel(url) + "：返回内容不是 JSON");
                return null;
            }
            String body = resp.body();
            if (body == null || body.isBlank()) {
                f.record("版本源返回了空内容");
                f.note(sourceLabel(url) + "：返回空内容");
                return null;
            }
            return body;
        } catch (Exception ex) {
            System.err.println("[update] 拉版本源失败（已忽略）: " + url + " -> " + ex);
            String why = readableReason(ex);
            f.record(why);
            f.note(sourceLabel(url) + "：" + why);
            return null;
        }
    }

    /** 把异常翻成一句人话，作为失败原因回传给 UI。 */
    private static String readableReason(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isBlank()) {
            m = t.getClass().getSimpleName();
        }
        String low = m.toLowerCase(Locale.ROOT);
        if (low.contains("timeout") || low.contains("timed out")) {
            return "连接超时";
        }
        if (low.contains("connect") || m.contains("ConnectException")) {
            return "连不上服务器";
        }
        return m.length() > 80 ? m.substring(0, 80) + "…" : m;
    }
}
