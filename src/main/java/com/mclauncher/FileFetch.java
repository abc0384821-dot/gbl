package com.mclauncher;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通用单文件下载（v0.7.0 起）：{@code .part} 落盘 → 验哈希 → move。Modrinth / mrpack /
 * CurseForge / 加载器 jar 共用。
 *
 * <p><b>v0.9.0 提速第二轮（对齐 HMCL 的传输层，根治「几分钟才下完」）：</b>
 * <ul>
 *   <li><b>HTTP/1.1 + 系统代理</b>：把传输层从 {@code java.net.http.HttpClient}（默认 HTTP/2，
 *       在高延迟链路上会把窗口压死、吞吐掉到 KB/s，且 {@code Net} 的代理是 no-op 纯直连）
 *       换成 {@code java.net.HttpURLConnection}——这正是 HMCL {@code FetchTask} 的做法：
 *       HTTP/1.1 单连接（HttpURLConnection 本就不走 HTTP/2，与 HMCL {@code FetchTask} 一致）。</li>
 *   <li><b>线路（v0.9.0 第三轮，真正治本的一刀）</b>：以上都做完仍然慢，是因为<b>根本没走代理</b>。
 *       本机实测 {@code cdn.modrinth.com} 直连 13.6 KB/s、走 Clash 混合口 127.0.0.1:7897 是
 *       2.06 MB/s（差 150 倍）；TUN 分流把 Modrinth 丢进 DIRECT，所以「能下、但一分钟」。
 *       现由 {@link ProxyRoute} 对出海域名自动探测并选择最快线路，走代理时不再分片。</li>
 *   <li><b>文件主源 = 官方 URL 原样（v0.9.1 第二阶段订正）</b>：查 HMCL 源码确认它并非靠 MCIM
 *       文件镜像秒下，而是<b>官方优先 + MCIM 兜底 + 串行回退 + {@code useSystemProxies=true}
 *       自动读系统代理</b>。故 {@link #download} <b>不</b>再对文件 URL 做 {@link Mirror#rewriteContent}，
 *       让 {@link ProxyRoute#warmupForFile} 用真实文件 URL 竞速、命中 OVERSEAS 恢复系统代理 /
 *       本地端口选路——这才是「有代理时秒下」的关键。MCIM 降为主源彻底失败后的兜底候选，
 *       且开跑前先做不跟随重定向的 302 预检（上游已公告文件分发暂停，302 回官方慢线多一跳），
 *       判无效即跳过、不跟随。{@code openResolved} 绝不做任何镜像改写。</li>
 *   <li><b>读超时</b>：{@code setReadTimeout(30s)}。连接卡住会在 30 秒内抛错重试，
 *       不再无限期挂成「几分钟」。</li>
 *   <li><b>小文件也并行分片</b>：分段阈值降到 512KB，像 Sodium（约 1.9MB）这种也会切成 3 段
 *       多条连接并行，高延迟链路上一条连接的带宽窗口有限，多连接能把带宽加起来。</li>
 *   <li><b>不重复读盘</b>：单流档直接拿边下边算的流式摘要比对；分片档末尾一次性顺序读校验。</li>
 *   <li><b>慢线路主动放弃（v0.9.1 同版本重发）</b>：单次下载尝试带吞吐 / 总时长看门狗，过了宽限期
 *       平均吞吐仍低于 {@link #MIN_BYTES_PER_SEC}，或超过按文件大小估算的总时长上限，就主动断开、
 *       让 {@link #download} 立刻切到下一条候选线路，不再「慢而不断」拖满一分半（30s readTimeout
 *       只管单次 read 阻塞，管不了持续吐字节的慢链路）。</li>
 *   <li><b>双源并发竞速（v0.9.2 r4 同版本重发，本轮根因修复）</b>：首字节前不再串行 await
 *       {@code warmupForFile} / MCIM 同步 HEAD 预检 / {@code probe} HEAD（冷启动固定空等 7~9s）。
 *       主源官方 URL 与 MCIM 备源由 {@code RaceFetch} 并发 GET、先返回有效 2xx 者胜出；
 *       {@code SpeedWatch} 仍在但本轮起 {@code allowSlowAbort} 恒 false，<b>不再主动掐断慢线</b>
 *       （0.9.1 把 13KB/s 的官方慢线掐断后死守 MCIM 兜底，正是「比以前还慢」的回归）。</li>
 *   <li><b>0.9.4 整合包提速（本轮根因修复）</b>：把方法体内 {@code newClient} 提为类级单例
 *       {@link #RACE_CLIENT} 复用连接池（F1）；恢复 {@link #MCIM_FILE_CACHE} 的一次性惰性判定，
 *       命中「MCIM 文件分发 302 回官方即不可用」后把备源短路、走单源退化，且预检全异步、
 *       绝不阻塞首字节（F2）；新增 {@link Options} 重载给整合包单独的竞速首字节预算（F3）。</li>
 * </ul>
 */
public final class FileFetch {

    /** 单个文件（含每段）最多试几次。0.9.2 r4：3 → 2，双源竞速已覆盖重试，别再叠等待。 */
    public static final int RETRY = 2;

    /** 0.9.2 r4：10s → 6s，连接卡住更快判失败。 */
    private static final int CONNECT_TIMEOUT_MS = 6_000;
    /** 单次 read 卡住超过这么久就判失败重试——避免坏连接把任务挂成「几分钟」。0.9.2 r4：30s → 15s。 */
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int BUF = 64 * 1024;
    /** 小于这个体积不切段。0.9.2 r4：512KB → 2MB，Sodium 这类小文件一律单流 + 流式摘要，
     *  不再「切 6 段 + 整文件重读校验」白付握手开销。 */
    private static final long SEGMENT_MIN = 2L * 1024 * 1024;
    /** 单个文件最多切几段。0.9.2 r4：6 → 4。 */
    private static final int MAX_SEGMENTS = 4;
    /** 进度节流：每 {@value} 毫秒最多对外报一次。 */
    private static final long THROTTLE_MS = 150L;

    // ---- 慢线路主动放弃（v0.9.1 同版本重发）：阈值集中于此，便于二阶段按实测调整 ----
    /** 宽限期：给 TCP 慢启动 / 首字节留余量，过了它才开始按平均吞吐判慢。 */
    private static final long SLOW_GRACE_MS = 2_000L;
    /** 最低可接受平均吞吐（字节/秒）：远高于实测的 13KB/s，远低于正常的 MB/s 级。 */
    private static final long MIN_BYTES_PER_SEC = 64L * 1024L;
    /** 单次尝试总时长的下限（小文件也至少给这么多）。 */
    private static final long ATTEMPT_MIN_MS = 8_000L;
    /** 单次尝试总时长的绝对上限，防止大文件被无限拖。 */
    private static final long ATTEMPT_MAX_MS = 45_000L;
    /** 按文件大小估算总时长时额外留的余量。 */
    private static final long ATTEMPT_SLACK_MS = 5_000L;
    /** 吞吐检查周期（毫秒）：单流读取循环 / 分片监控循环每这么久判一次。 */
    private static final long THROUGHPUT_CHECK_MS = 500L;
    /** 0.9.9 r6：单文件 HTTP 429 退避上限（毫秒）——无论 Retry-After 要多久，单次最多等 20s，
     *  绝不让单个文件因服务端给的超大值无限等待。 */
    private static final long MAX_429_BACKOFF_MS = 20_000L;

    /**
     * 段级线程池——和 DownloadCenter 的「文件级」池分开（参考 LibFetcher 的教训：
     * 一池两用会让分段任务互相排队卡死）。守护线程，JVM 退出不被拖住。
     */
    private static final ExecutorService SEGMENT_POOL =
            Executors.newFixedThreadPool(24, runnable -> {
                Thread thread = new Thread(runnable, "gbl-fetch-seg");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * MCIM 文件分发状态按 host 的进程内缓存（0.9.2）。状态一次启动内不变，
     * 上游恢复服务后重启启动器即刷新；不设 TTL、不定时刷新（最小变更）。
     * key = {@link Mirror#hostOf} 的结果（解析失败退化为 {@code "mcim"}，保证非 null），
     * value = 该 host 的 MCIM 兜底是否可用。整合包几十个小文件只做一次 HEAD 预检，
     * 不再每个文件都白等 10s+（串行叠加就是「根本下载不动」）。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> MCIM_FILE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private FileFetch() {
        // 静态工具类，别让人 new 出来
    }

    /** 进度出口。回调发生在下载线程上，接的人要自己往 FX 线程送。 */
    public interface ProgressListener {
        void onProgress(long doneBytes, long totalBytes);
    }

    // ---- 0.9.4 新增：共享连接池 / 指纹 / 一次性 MCIM 预检调度 / 可调策略（F1/F2/F3/F5） ----

    /**
     * 0.9.4 F1：内容下载竞速专用 client 单例——HTTP/1.1 + ProxyRoute.selector() + NEVER 重定向，
     * 共享一个实例复用连接池。照抄 AssetFetcher / LibFetcher 现成范本，根治「每文件新建
     * HttpClient、几百个 cdn.modrinth.com 文件重复 TCP+TLS 握手」。HttpClient 本身线程安全，
     * 且 RaceFetch.neverRedirectClient 对本 client 直接 return、零额外开销。
     */
    private static final HttpClient RACE_CLIENT = RaceFetch.newClient(6000);

    /**
     * 0.9.4 F2：按 host 记录「已提交过一次性 MCIM 备源预检」的标记，避免并发下重复提交。
     * 预检本体 {@link #mcimFileAvailable} 异步执行（不阻塞首字节），结论写入 MCIM_FILE_CACHE，
     * 仅对后续文件生效。ConcurrentHashMap.newKeySet() 保证多线程可见与原子 add。
     */
    private static final java.util.Set<String> MCIM_PROBE_SCHEDULED =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 0.9.4 指纹：内容下载共享连接池。构建门禁扫 class 常量池核验，勿改这串字。 */
    private static final String FP_094_SHARED_CLIENT = "0.9.4 内容下载共享连接池";
    static { System.err.println("[fetch] 0.9.4: " + FP_094_SHARED_CLIENT); }

    /** 0.9.6 r6 指纹：世界下载 302 竞速修复。构建门禁扫 class 常量池核验，勿改这串字。 */
    public static final String FP_096_R6_WORLD_302 = "0.9.6 r6 世界下载302竞速修复";
    static { System.err.println("[fetch] 0.9.6 r6: " + FP_096_R6_WORLD_302); }

    /** 0.9.9 r6 指纹：整合包下载线路与限流修复。构建门禁扫 class 常量池核验，勿改这串字。 */
    public static final String FP_099_R6_MODPACK = "0.9.9 r6 整合包下载线路与限流修复";
    static { System.err.println("[fetch] 0.9.9 r6: " + FP_099_R6_MODPACK); }

    /**
     * r6：最近一次 {@code download()} 失败的可读原因，按线程独立（内容下载由 DownloadCenter
     * 各自的 worker 线程发起，不能串台）。每次 download 开头清空，成功出口再清一次，见 {@link #lastFailure()}。
     */
    private static final ThreadLocal<String> LAST_FAILURE = new ThreadLocal<>();

    /**
     * 0.9.4：单次下载的可调策略（F3）。原有 6 参 {@code download(...)} 委托到
     * {@link #defaults()}，整合包 / 失败重试链路用 {@link #modpack()} /
     * {@link #retryDirect()} / {@link #retryAuto()}。三个字段只影响「本轮竞速首字节预算 /
     * MCIM 备源开关 / 兜底线路」，绝不改任何全局常量，也不改其它调用点的既有签名与入参。
     *
     * @param firstByteMs   {@link RaceFetch#race} 的竞速首字节预算（毫秒）
     * @param mcimEnabled   是否允许生成 MCIM 备源参与竞速（受 {@link #MCIM_FILE_CACHE} 一次性判定短路）
     * @param routeOverride 线路覆盖：null = 交给 {@code ProxyRoute.selector()} 自动选路；
     *                      {@link Proxy#NO_PROXY} = 跳过竞速、走官方单源兜底并强制直连
     *                      （0.9.4 F4 失败文件有界重试「换线路」用）
     * @param forceMcim     0.9.9 r6：true = 忽略 {@link #MCIM_FILE_CACHE} 的一次性失败短路，
     *                      强制本轮生成 MCIM 备源（仅失败重试兜底轮用）
     */
    public record Options(int firstByteMs, boolean mcimEnabled, Proxy routeOverride,
                          boolean forceMcim) {
        /** 默认策略：与 0.9.3 完全一致（首字节 12000ms、启用 MCIM、自动选路）。 */
        public static Options defaults() {
            return new Options(GameDownloader.RACE_FIRST_BYTE_MS, true, null, false);
        }
        /** 整合包快通道：慢文件更早判失败、更早进兜底；不改全局 RACE_FIRST_BYTE_MS。 */
        public static Options modpack() {
            return new Options(7_000, true, null, false);
        }
        /** F4 重试第 1 轮：强制 NO_PROXY 直连换线路 + 禁 MCIM 备源 + 短预算。 */
        public static Options retryDirect() {
            return new Options(6_000, false, Proxy.NO_PROXY, false);
        }
        /** F4 重试第 2 轮：恢复自动选路竞速（出海域名即代理）+ 禁 MCIM 备源 + 完整首字节预算。 */
        public static Options retryAuto() {
            return new Options(GameDownloader.RACE_FIRST_BYTE_MS, false, null, false);
        }
        /** 0.9.9 r6 重试第 3 轮：启用 MCIM 备源 + 自动选路竞速，且忽略 MCIM_FILE_CACHE 的一次性失败短路，
         *  强制再试一次备源——解决「两轮都禁 MCIM、本质都是裸直连，换线路没换成」。 */
        public static Options retryMirror() {
            return new Options(GameDownloader.RACE_FIRST_BYTE_MS, true, null, true);
        }
    }

    // ---- 0.9.6 r6：失败可读原因（w1 的 ShellView.contentFailureMessage 按 why 做 contains 分类） ----

    /** 记录失败原因并返回 false，供各下载失败出口直接 return。 */
    private static boolean fail(String reason) {
        LAST_FAILURE.set(reason);
        return false;
    }

    /** 只记录原因、由调用方决定返回值（竞速异常、哈希比较等）。 */
    private static void markFailure(String reason) {
        LAST_FAILURE.set(reason);
    }

    /** 仅当尚无记录时补一条兜底原因，避免覆盖深层写下的具体原因。 */
    private static void ensureFailure(String reason) {
        if (LAST_FAILURE.get() == null) {
            LAST_FAILURE.set(reason);
        }
    }

    /** 最近一次 download() 失败的可读原因；无记录返回 null。按线程独立。 */
    public static String lastFailure() {
        return LAST_FAILURE.get();
    }

    /**
     * 下载 {@code url → target}（0.9.4：保留原签名，内部委托到带 {@link Options} 的重载，
     * 用 {@link Options#defaults()} 维持与 0.9.3 完全一致的策略）。
     *
     * @param sha1      期望的 SHA-1（小写 hex）；空 = 不校
     * @param sha512    期望的 SHA-512（小写 hex）；优先于 sha1，空 = 看 sha1
     * @param cancelled 取消旗标；取消后删 {@code .part} 返回 false
     * @param on        进度出口（可为 null）；总长未知时 {@code totalBytes} 给 -1
     * @return 成功 true；取消 / 重试到头 false（此时目标目录没有残留文件）
     * @throws IOException 目标目录建不出来这类「环境坏了」的错（message 说人话）
     */
    public static boolean download(String url, Path target, String sha1, String sha512,
                                   AtomicBoolean cancelled, ProgressListener on)
            throws IOException {
        return download(url, target, sha1, sha512, cancelled, on, Options.defaults());
    }

    /**
     * 0.9.4：带可调策略的下载重载。{@link Options} 只控制本轮竞速首字节预算、MCIM 备源开关
     * 与兜底线路；F1 复用类级单例 {@link #RACE_CLIENT}，F2 对 MCIM 备源做一次性惰性短路，
     * F4 的强制直连重试轮在此「换线路」。
     *
     * <p>0.9.2 r4 的「首字节前不串行 await 任何前置」红线继续保留：F2 的预检是异步的，
     * 仅对后续文件生效，绝不阻塞本文件首字节。
     */
    public static boolean download(String url, Path target, String sha1, String sha512,
                                   AtomicBoolean cancelled, ProgressListener on, Options opt)
            throws IOException {
        // r6：本线程本次下载开始即清空上次失败记录，成功出口会再清一次。
        LAST_FAILURE.remove();
        if (url == null || url.isBlank() || target == null) {
            throw new IOException("下载地址或目标路径是空的，没法学");
        }
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path part = target.resolveSibling(target.getFileName() + ".part");

        // 0.9.9 r6：用真实文件 URL 预热 / 纠正线路决策——让 ProxyRoute 的选路基于「这个文件本身」，
        // 而不是此前被封面 / 元数据压平速度差后产生的决策（注释里的承诺此前代码未兑现）。
        // 无代理时 warmupForFile 立即返回、不引入额外延迟；异常内部已吞，绝不影响下载。
        ProxyRoute.warmupForFile(url);

        // MCIM 备源仍用 rewriteContent 生成（逐字不动 Mirror.java，本轮红线）；
        // GBL_MIRROR=off / 未命中映射时 rewriteContent 原样返回 → secondary 为 null，退化为官方单源。
        // 0.9.4 F2：resolveSecondary 内做一次性惰性判定——命中「MCIM 不可用」即把 secondary 置 null，
        // 走 RaceFetch.race 的单源退化分支，后续文件不再每文件白发一次注定 302 回官方的 MCIM GET。
        String secondary = resolveSecondary(url, opt);

        // 0.9.4 F4：强制直连重试轮——跳过双源竞速（竞速用的 RACE_CLIENT 挂的是自动选路 selector，
        // 换不了线路），直接走官方单源兜底链路并强制 NO_PROXY，真正换一条线路；不新建 client。
        if (opt.routeOverride() == Proxy.NO_PROXY) {
            boolean ok = tryDownload(url, target, part, sha1, sha512, cancelled, on,
                    new Attempt(false), Proxy.NO_PROXY);
            if (ok) {
                LAST_FAILURE.remove();
                return true;
            }
            ensureFailure("下载失败：网络不通、连接超时或服务器无响应，检查网络后重试");
            return false;
        }

        // 0.9.4 F1：复用类级单例 RACE_CLIENT（HTTP/1.1 + ProxyRoute.selector() + Redirect.NEVER），
        // 不再每文件 newClient；HttpClient 线程安全，RaceFetch.neverRedirectClient 对它直接 return。
        RaceFetch.Winner w = null;
        try {
            // 双源并发竞速：主源 = 官方 URL 原样，备源 = MCIM（可能为 null → 单源退化）；
            // firstByteMs 由 Options 决定（整合包快通道更短，慢文件更早进兜底），
            // 绝不改 GameDownloader.RACE_FIRST_BYTE_MS 全局常量。
            w = RaceFetch.race(url, secondary, 6000, opt.firstByteMs(), RACE_CLIENT);
        } catch (IOException ex) {
            System.err.println("[fetch] 双源竞速没拿到有效响应，转官方单源兜底: " + url
                    + "（" + ex.getMessage() + "）");
            // r6：把竞速双败的真实原因记进 lastFailure（含 302 / 连接等分类关键字）。
            markFailure("双源竞速没拿到有效响应（302 跳转判无效或连接失败）：" + ex.getMessage());
        }
        if (w != null) {
            try {
                if (raceDownload(w, part, sha1, sha512, cancelled, on)) {
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                    LAST_FAILURE.remove();
                    return true;
                }
            } finally {
                w.close();   // Winner 的流无论成败都在这里关掉，绝不泄漏
            }
            // 竞速落盘 / 校验没过：清掉半成品，让下面的官方单源兜底干净重来。
            deleteQuietly(part);
        }
        // 两路都失败 / 竞速落盘失败：保留原有「官方 URL 单源再试一次」兜底语义，不直接抛给用户。
        // 传 new Attempt(false)：allowSlowAbort 恒 false，SpeedWatch 不再主动掐断慢线（0.9.1 的回归修复）。
        boolean ok = tryDownload(url, target, part, sha1, sha512, cancelled, on,
                new Attempt(false), opt.routeOverride());
        if (ok) {
            LAST_FAILURE.remove();
            return true;
        }
        ensureFailure("下载失败：网络不通、连接超时或服务器无响应，检查网络后重试");
        return false;
    }

    /**
     * 0.9.4 F2：生成 MCIM 备源，并按 {@link #MCIM_FILE_CACHE} 做一次性惰性可用性短路。
     *
     * <p>关键约束（r4 砍同步预检的原因）：<b>绝不在首字节前同步阻塞</b>。首次遇到某个 host 时
     * 只提交一次异步预检（{@link #mcimFileAvailable}，用 ForkJoinPool.commonPool 守护线程跑
     * 不跟随重定向的 HEAD），本文件照常带备源竞速；预检结论写入缓存后<b>仅对后续文件生效</b>：
     * 命中「不可用（302 回官方）」即返回 null，让 race 走单源退化分支，不再白发 MCIM GET。
     * 并发可见性由 ConcurrentHashMap / newKeySet 保证。
     */
    private static String resolveSecondary(String url, Options opt) {
        if (!opt.mcimEnabled()) {
            return null;
        }
        // r6：MCIM 文件分发已停，CF 文件直链 302 在双源竞速（Redirect.NEVER，任何 3xx 判该路失败）
        // 下必然双败——主源 302 判败 + 备源 302/0 字节判败，落到官方单源兜底又因跨协议 302 不跟随
        // 而失败，正是世界「文件没下下来」的根因。世界是唯一仅 CF classId=17 的类型，downloadUrl
        // 常为 302 直链、无 sha1、默认带 MCIM 备源（MCIM_FILE_CACHE 预检还是异步的），首次必踩双败。
        // 故对命中的 CF 出海 CDN 文件下载直链不生成 MCIM 备源 → 退化为 RaceFetch.raceSingle
        // （已实现手动跟随 3xx ≤5 跳，官方 CDN 302 属正常路径），世界即可正常下完。
        // Modrinth（cdn/api.modrinth.com）的 MCIM 备源历史验证可用，保持原行为不变；
        // 本层只收口「文件 URL 不生成备源」，绝不动 Mirror.rewriteContent 的映射表（元数据 API 还要用）。
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("https://edge.forgecdn.net")
                || lower.startsWith("https://media.forgecdn.net")
                || (lower.startsWith("https://api.curseforge.com/") && lower.contains("/files/"))) {
            return null;
        }
        String mirror = Mirror.rewriteContent(url);
        if (mirror.equals(url)) {
            return null;   // GBL_MIRROR=off / 未命中映射：本就无备源
        }
        String host = Mirror.hostOf(mirror);
        String cacheKey = host == null ? "mcim" : host;
        Boolean usable = MCIM_FILE_CACHE.get(cacheKey);
        if (usable != null && !usable && !opt.forceMcim()) {
            return null;   // 已判不可用：短路，后续文件不再白发 MCIM GET；但重试兜底轮可强制再试一次
        }
        if (usable == null && MCIM_PROBE_SCHEDULED.add(cacheKey)) {
            final String probeUrl = mirror;
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                mcimFileAvailable(probeUrl);
            });
        }
        return mirror;
    }

    /**
     * 把竞速胜出的 {@code RaceFetch.Winner} 落盘到 {@code part} 并校验；成功 true。
     *
     * <p>0.9.2 r4 分片收紧：仅当胜出源明确支持 Range、文件不小于 {@link #SEGMENT_MIN}（2MB）、
     * 且胜出 URL 不是 MCIM 域时才分片；其余一律单流 + 流式摘要（零整文件重读）。
     * 旧链路「512KB 起切 6 段 + verifyByRead 整文件重读」在小文件上 6 次握手 + 重读，是净倒退。
     */
    private static boolean raceDownload(RaceFetch.Winner w, Path part,
                                         String sha1, String sha512,
                                         AtomicBoolean cancelled, ProgressListener on)
            throws IOException {
        String winnerUrl = w.url();
        long total = w.contentLength();   // 缺失 -1，不再发 probe HEAD
        boolean ranged = w.acceptRanges()
                && total >= SEGMENT_MIN
                && !isMcimHost(winnerUrl);
        if (ranged) {
            // 胜出 URL 另开 Range 连接分片；Winner 自带整文件流此档不用（由 download() 的 finally 关闭）。
            SegResult r = fetchSegmented(winnerUrl, part, total, cancelled, on, new Attempt(false), null);
            if (r == SegResult.OK) {
                return verifyByRead(part, sha1, sha512);
            }
            if (r == SegResult.FALLBACK) {
                return raceStream(w, part, total, sha1, sha512, cancelled, on);
            }
            return fail("竞速胜出源下载失败（分片中断或连接断开）");
        }
        return raceStream(w, part, total, sha1, sha512, cancelled, on);
    }

    /** 胜出 URL 是否落在 MCIM 镜像域（该域文件分发常 302 回官方，且不宜分片）。 */
    private static boolean isMcimHost(String url) {
        return url != null && url.contains("mod.mcimirror.top");
    }

    /**
     * 从 {@code RaceFetch.Winner} 的响应流单流落盘：边下边算 sha1/sha512 摘要，
     * 校验直接用流式摘要（零整文件重读），带取消检查与 150ms 进度节流。
     */
    private static boolean raceStream(RaceFetch.Winner w, Path part, long total,
                                      String sha1, String sha512,
                                      AtomicBoolean cancelled, ProgressListener on) {
        MessageDigest d512 = newDigest("SHA-512");
        MessageDigest d1 = newDigest("SHA-1");
        try (InputStream in = w.stream();
             var out = Files.newOutputStream(part,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[BUF];
            long done = 0;
            long lastReport = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                if (cancelled != null && cancelled.get()) {
                    return fail("下载已取消");
                }
                out.write(buffer, 0, n);
                if (d512 != null) {
                    d512.update(buffer, 0, n);
                }
                if (d1 != null) {
                    d1.update(buffer, 0, n);
                }
                done += n;
                long now = System.currentTimeMillis();
                if (on != null && now - lastReport >= THROTTLE_MS) {
                    lastReport = now;
                    on.onProgress(done, total);
                }
            }
            if (on != null) {
                on.onProgress(done, total);
            }
        } catch (IOException ex) {
            System.err.println("[fetch] 竞速流没写成「" + part.getFileName() + "」: " + ex);
            return fail("竞速流写入失败（连接被拒或读超时）：" + ex.getMessage());
        }
        return verifyByDigest(d1, d512, sha1, sha512);
    }

    /**
     * 对单个地址重试下载（RETRY 次），成功 true；取消 / 因慢放弃 / 重试到头 false。
     *
     * <p>0.9.3：若首轮 RETRY 次都失败、且失败确由「走代理的连接错误」引起，
     * 调 {@link ProxyRoute#retryDirectOnConnectFailure} 清掉代理决策、用 {@link Proxy#NO_PROXY}
     * 整轮重跑一次（有界兜底，只多试一次直连），给「代理进程闪退」一个出口，
     * 而不是对所有出海下载一刀切判失败。境内域名 / 本就直连的请求不会进入该分支。</p>
     *
     * <p>0.9.4 F4：{@code forceRoute} 非 null 时整轮强制走该线路（失败重试换线路用
     * {@link Proxy#NO_PROXY}），且此时不再叠加 0.9.3 的「连接错误 → 直连兜底」分支
     * （本就已是直连，也避免无谓 invalidate 决策缓存）。null = 自动选路，行为与 0.9.3 一致。</p>
     */
    private static boolean tryDownload(String url, Path target, Path part, String sha1, String sha512,
                                       AtomicBoolean cancelled, ProgressListener on, Attempt ctx,
                                       Proxy forceRoute)
            throws IOException {
        for (int attempt = 0; attempt < RETRY; attempt++) {
            if (cancelled != null && cancelled.get()) {
                deleteQuietly(part);
                return fail("下载已取消");
            }
            if (fetchAndVerify(url, part, sha1, sha512, cancelled, on, ctx, forceRoute)) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
            deleteQuietly(part);
            // 本轮因「慢而不断」被看门狗主动放弃：不对同一条慢线重试、不 sleep，直接返回，
            // 让 download() 立刻切到下一条候选线路。全程不碰 cancelled。
            if (ctx.tooSlow) {
                return fail("下载线路太慢已放弃（连接超时或吞吐过低）");
            }
            // 0.9.9 r6：429 走秒级退避（对齐 LibFetcher），其余失败维持 400ms 急促重试。
            // 退避毫秒由 fetchStream / fetchSegment 读 Retry-After 写进 ctx，取不到则用 2000*(attempt+1)，
            // 一律 clamp 到 20s；用后清零，避免污染下一轮判断。
            String why = LAST_FAILURE.get();
            boolean hit429 = (why != null && why.contains("429")) || ctx.retryAfterMs429 > 0L;
            if (hit429) {
                long wait = ctx.retryAfterMs429 > 0L ? ctx.retryAfterMs429 : default429Backoff(attempt);
                sleepQuietly(Math.min(wait, MAX_429_BACKOFF_MS));
                ctx.retryAfterMs429 = 0L;
            } else {
                sleepQuietly(400L * (attempt + 1));
            }
        }
        // 0.9.3：走代理的连接错误 → 有界直连兜底重试一次（只多试一次，绝不对死代理死磕）。
        // 0.9.4 F4：仅自动选路（forceRoute==null）时触发；强制直连重试轮不再叠加。
        if (forceRoute == null && ctx.proxyConnectFailed
                && ProxyRoute.retryDirectOnConnectFailure(url)) {
            deleteQuietly(part);
            boolean ok = fetchAndVerify(url, part, sha1, sha512, cancelled, on,
                    new Attempt(false), Proxy.NO_PROXY);
            if (ok) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
            deleteQuietly(part);
        }
        return false;
    }

    /**
     * 写一遍 + 验哈希（不 move）。成功 true。
     *
     * <p>0.9.3：{@code forceProxy} 非 null 时忽略 {@link ProxyRoute} 选路、强制走该代理
     * （直连兜底重试时传 {@link Proxy#NO_PROXY}）。</p>
     */
    private static boolean fetchAndVerify(String url, Path part, String sha1, String sha512,
                                         AtomicBoolean cancelled, ProgressListener on, Attempt ctx,
                                         Proxy forceProxy)
            throws IOException {
        Probe probe = probe(url, forceProxy);
        long total = probe == null ? -1 : probe.total;
        // 走代理时不再分片：代理已经把速率拉满（实测 2 MB/s），再开 3~6 条隧道穿过同一个
        // 代理反而互相挤占，HMCL 也是单连接。直连（代理不可用 / 强制直连兜底）时保留分片，
        // 多连接能叠加带宽。
        boolean proxied = forceProxy == null ? ProxyRoute.isProxied(url) : (forceProxy != Proxy.NO_PROXY);
        boolean ranged = !proxied && probe != null && probe.acceptRanges && total > SEGMENT_MIN;
        if (ranged) {
            SegResult r = fetchSegmented(url, part, total, cancelled, on, ctx, forceProxy);
            if (r == SegResult.OK) {
                return verifyByRead(part, sha1, sha512);
            }
            if (r == SegResult.FALLBACK) {
                // 服务端嘴上说支持 Range 实际不认（罕见）→ 整文件单流兜底
                return fetchStream(url, part, sha1, sha512, cancelled, on, ctx, forceProxy);
            }
            // 0.9.9 r6：分片线程在各自 ThreadLocal 里记的 429 原因跨线程不可见，但共享 Attempt 上的
            // retryAfterMs429 可见——据此在本线程也记一条 429 原因，保证 tryDownload 读到可读文案并走秒级退避；
            // 否则维持原有「分片下载失败」文案，不削弱任何失败可读性。
            if (ctx.retryAfterMs429 > 0L) {
                fail("分片下载返回 HTTP 429（服务器限流），已按退避策略重试");
            } else {
                String why = LAST_FAILURE.get();
                if (why == null || !why.contains("429")) {
                    fail("分片下载失败（连接中断或服务器不支持断点续传）");
                }
            }
            return false;
        }
        return fetchStream(url, part, sha1, sha512, cancelled, on, ctx, forceProxy);
    }

    private enum SegResult { OK, FALLBACK, FAIL }

    /**
     * 一次「候选线路下载尝试」的共享状态。每轮（官方主源 / MCIM 兜底）各建一个 {@code Attempt}，
     * 互不污染：{@code slowAbort} 把慢判定广播给所有分片线程，且与 {@code cancelled} 完全分离，
     * 慢线路不会污染取消旗标。
     */
    private static final class Attempt {
        final AtomicBoolean slowAbort = new AtomicBoolean(false);
        final SpeedWatch watch = new SpeedWatch();
        volatile boolean tooSlow = false;
        /** 0.9.3：本轮是否因「走代理的连接错误」失败——触发 tryDownload 的有界直连兜底重试。 */
        volatile boolean proxyConnectFailed = false;
        /** 是否允许「因慢主动放弃」：只有后面还有下一条<b>有效</b>候选可换时才为 true
         *  （MCIM 兜底须先通过 302 预检才算有效）。 */
        final boolean allowSlowAbort;
        /** 0.9.9 r6：本轮遇到 HTTP 429 时，由 fetchStream / fetchSegment 写入的服务端建议退避毫秒
         *  （读 Retry-After，上限 20s；>0 表示该轮确实撞上 429）。tryDownload 据此在重试间做秒级退避。 */
        volatile long retryAfterMs429 = 0L;

        Attempt(boolean allowSlowAbort) {
            this.allowSlowAbort = allowSlowAbort;
        }

        void markTooSlow() {
            tooSlow = true;
        }
    }

    /**
     * 吞吐 / 总时长看门狗：基于「整体已读字节 + 已用时间」判慢，一次尝试只打一条日志。
     * 单连接路径由下载线程单点调用，分片路径由监控（await）线程单点调用；{@code logged} 仍用
     * 原子布尔兜底，防止将来误改成多点调用时刷屏。
     */
    private static final class SpeedWatch {
        private long startMs;
        private long deadlineMs = ATTEMPT_MAX_MS;
        private String host = "?";
        private boolean proxied = false;
        private final AtomicBoolean logged = new AtomicBoolean(false);

        void start(String resolvedUrl, long totalBytes) {
            this.startMs = System.currentTimeMillis();
            this.deadlineMs = attemptDeadlineMs(totalBytes);
            String h = Mirror.hostOf(resolvedUrl);
            this.host = h == null ? "?" : h;
            // 本轮实际线路：只读 ProxyRoute 的线路决策缓存、不阻塞。0.9.9 r6 起 download() 在真正
            // 下载前已用真实文件 URL 调 warmupForFile 预热（见 download），这里读到的是纠正后的决策。
            this.proxied = ProxyRoute.isProxied(resolvedUrl);
        }

        /** 已读 {@code doneBytes} 时是否判定为慢线路；成立瞬间打一条中性 stderr 日志（是否放弃由调用方决定）。 */
        boolean isTooSlow(long doneBytes) {
            long elapsed = System.currentTimeMillis() - startMs;
            boolean slow = false;
            if (elapsed >= SLOW_GRACE_MS) {
                long avg = elapsed > 0 ? doneBytes * 1000L / elapsed : 0L;
                if (avg < MIN_BYTES_PER_SEC) {
                    slow = true;
                }
            }
            if (elapsed > deadlineMs) {
                slow = true;
            }
            if (slow && logged.compareAndSet(false, true)) {
                long avg = elapsed > 0 ? doneBytes * 1000L / elapsed : 0L;
                System.err.println("[fetch] 检测到慢线路: 本轮线路 host=" + host
                        + " 走代理=" + (proxied ? "是" : "否")
                        + " 已读=" + doneBytes + "B 用时=" + elapsed + "ms 平均=" + avg
                        + "B/s 门槛=" + MIN_BYTES_PER_SEC + "B/s 上限=" + deadlineMs + "ms");
            }
            return slow;
        }
    }

    /** 按文件大小动态算单次尝试总时长上限：size / 最低吞吐 * 1000 + 余量，再 clamp 到 [下限, 绝对上限]。 */
    private static long attemptDeadlineMs(long totalBytes) {
        long dyn = ATTEMPT_MIN_MS;
        if (totalBytes > 0) {
            long bySize = totalBytes / MIN_BYTES_PER_SEC * 1000L + ATTEMPT_SLACK_MS;
            dyn = Math.max(ATTEMPT_MIN_MS, bySize);
        }
        return Math.min(ATTEMPT_MAX_MS, dyn);
    }

    /** HEAD 探长度 + 是否支持 Range；全失败返回 null（交给单流档自己从 GET 拿总长）。
     *  {@code forceProxy} 非 null 时忽略 {@link ProxyRoute} 选路、强制走该代理。 */
    private static Probe probe(String url, Proxy forceProxy) {
        HttpURLConnection conn = null;
        try {
            conn = openResolved(url, "HEAD", 0, 0, true, forceProxy);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            long total = conn.getContentLengthLong();
            String ar = conn.getHeaderField("Accept-Ranges");
            boolean acceptRanges = ar != null && ar.toLowerCase(Locale.ROOT).contains("bytes");
            return new Probe(total, acceptRanges);
        } catch (IOException | RuntimeException ex) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static final class Probe {
        final long total;
        final boolean acceptRanges;
        Probe(long total, boolean acceptRanges) {
            this.total = total;
            this.acceptRanges = acceptRanges;
        }
    }

    /**
     * 把文件切成若干段并行下载到 {@code part} 的各自偏移（Range + SeekableByteChannel）。
     * 全部成功 OK；某段发现服务端不认 Range 则 FALLBACK（让上层退化为单流）；其余失败 FAIL。
     */
    private static SegResult fetchSegmented(String url, Path part, long total,
                                          AtomicBoolean cancelled, ProgressListener on, Attempt ctx,
                                          Proxy forceProxy) {
        int segments = (int) Math.min(MAX_SEGMENTS, Math.max(1, total / SEGMENT_MIN));
        // 先按总长截断/预扩，避免上一轮失败留下的残片干扰本轮
        try (SeekableByteChannel trunc = Files.newByteChannel(part,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            trunc.truncate(total);
        } catch (IOException ex) {
            return SegResult.FAIL;
        }
        AtomicLong done = new AtomicLong(0);
        AtomicLong lastEmit = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(segments);
        List<SegResult> results = new ArrayList<>(segments);
        for (int i = 0; i < segments; i++) {
            results.add(SegResult.FAIL);
        }
        for (int i = 0; i < segments; i++) {
            final long from = total * i / segments;
            final long to = total * (i + 1) / segments - 1;
            final int idx = i;
            SEGMENT_POOL.execute(() -> {
                SegResult r = SegResult.FAIL;
                try {
                    r = fetchSegment(url, part, from, to, done, total, cancelled, on, lastEmit, ctx,
                            forceProxy);
                } catch (RuntimeException ex) {
                    System.err.println("[fetch] 第 " + from + " 段异常: " + ex);
                }
                synchronized (results) { results.set(idx, r); }
                latch.countDown();
            });
        }
        // 整体吞吐 / 总时长看门狗：基于所有分片累计的 done（整体进度）判慢，
        // 不针对某个恰好慢启动的分片，避免误杀快线路。
        ctx.watch.start(url, total);
        boolean aborted = false;
        while (true) {
            boolean allDone = false;
            try {
                allDone = latch.await(THROUGHPUT_CHECK_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                ctx.slowAbort.set(true);
                aborted = true;
                break;
            }
            if (allDone) {
                break;
            }
            if (cancelled != null && cancelled.get()) {
                ctx.slowAbort.set(true);
                aborted = true;
                break;
            }
            if (ctx.watch.isTooSlow(done.get()) && ctx.allowSlowAbort) {
                ctx.markTooSlow();
                ctx.slowAbort.set(true);
                aborted = true;
                break;
            }
        }
        if (aborted) {
            // 等所有分片线程真正退出再返回，避免残留线程继续写同一个 .part、与下一轮冲突。
            try {
                latch.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return SegResult.FAIL;
        }
        if (cancelled != null && cancelled.get()) {
            return SegResult.FAIL;
        }
        boolean anyFallback = false;
        for (SegResult r : results) {
            if (r == SegResult.FAIL) {
                return SegResult.FAIL;
            }
            if (r == SegResult.FALLBACK) {
                anyFallback = true;
            }
        }
        return anyFallback ? SegResult.FALLBACK : SegResult.OK;
    }

    /** 下载并写入 [from, to] 这一段。 */
    private static SegResult fetchSegment(String url, Path part, long from, long to,
                                        AtomicLong done, long total, AtomicBoolean cancelled,
                                        ProgressListener on, AtomicLong lastEmit, Attempt ctx,
                                        Proxy forceProxy) {
        for (int attempt = 0; attempt < RETRY; attempt++) {
            if ((cancelled != null && cancelled.get()) || ctx.slowAbort.get()) {
                return SegResult.FAIL;
            }
            HttpURLConnection conn = null;
            try {
                conn = openResolved(url, "GET", from, to, true, forceProxy);
                int code = conn.getResponseCode();
                if (code == 206) {
                    // 正常分片响应
                } else if (code == 200) {
                    // 服务端忽略 Range、吐了整文件——各段都会冲突，整体退化为单流
                    return SegResult.FALLBACK;
                } else if (code == 429) {
                    // 0.9.9 r6：限流不按普通失败急促重试——登记服务端 Retry-After 退避，
                    // 由 tryDownload 在重试间做秒级等待（不在此就地 sleep，避免双倍等待）。
                    note429(conn, ctx, attempt);
                    fail("分片下载返回 HTTP 429（服务器限流），已按退避策略重试");
                    return SegResult.FAIL;
                } else {
                    throw new IOException("服务器不支持分片（HTTP " + code + "）");
                }
                try (InputStream in = conn.getInputStream();
                     SeekableByteChannel ch = Files.newByteChannel(part,
                             StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                    ch.position(from);
                    byte[] buffer = new byte[BUF];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        if ((cancelled != null && cancelled.get()) || ctx.slowAbort.get()) {
                            return SegResult.FAIL;
                        }
                        ch.write(ByteBuffer.wrap(buffer, 0, n));
                        long nd = done.addAndGet(n);
                        long now = System.currentTimeMillis();
                        long prev = lastEmit.get();
                        if (on != null && now - prev >= THROTTLE_MS
                                && lastEmit.compareAndSet(prev, now)) {
                            on.onProgress(nd, total);
                        }
                    }
                }
                return SegResult.OK;
            } catch (IOException ex) {
                // 正在换线就别再刷失败日志 / 重试了，尽快退出让 latch 归零。
                if (ctx.slowAbort.get()) {
                    return SegResult.FAIL;
                }
                // 0.9.3：走代理的连接错误 → 标记，让 tryDownload 触发有界直连兜底
                if (forceProxy == null && isConnectError(ex) && ProxyRoute.isProxied(url)) {
                    ctx.proxyConnectFailed = true;
                }
                System.err.println("[fetch] 第 " + from + " 段第 " + (attempt + 1)
                        + " 次失败: " + ex);
                sleepQuietly(400L * (attempt + 1));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
        return SegResult.FAIL;
    }

    /** 单连接整文件下载，并<b>直接拿流式摘要校验</b>，不再整文件重读。
     *  {@code forceProxy} 非 null 时强制走该代理（直连兜底重试时传 {@link Proxy#NO_PROXY}）。 */
    private static boolean fetchStream(String url, Path part, String sha1, String sha512,
                                      AtomicBoolean cancelled, ProgressListener on, Attempt ctx,
                                      Proxy forceProxy) {
        MessageDigest d512 = newDigest("SHA-512");
        MessageDigest d1 = newDigest("SHA-1");
        HttpURLConnection conn = null;
        try {
            conn = openResolved(url, "GET", 0, 0, true, forceProxy);
            int code = conn.getResponseCode();
            if (code == 429) {
                // 0.9.9 r6：HTTP 429 不再当普通失败急促重试——读 Retry-After 登记退避，
                // 由 tryDownload 在重试间做秒级等待（上限 20s）。
                note429(conn, ctx, 0);
                System.err.println("[fetch] 「" + url + "」返回 HTTP 429（限流），已登记退避交重试轮处理");
                return fail("下载地址返回 HTTP 429（服务器限流，" + url + "），已按退避策略重试");
            }
            if (code < 200 || code >= 300) {
                System.err.println("[fetch] 「" + url + "」返回 HTTP " + code);
                return fail("下载地址返回 HTTP " + code + "（" + url + "），服务器没能给出文件内容");
            }
            long total = conn.getContentLengthLong();
            ctx.watch.start(url, total);
            try (InputStream in = conn.getInputStream();
                 var out = Files.newOutputStream(part,
                         StandardOpenOption.CREATE,
                         StandardOpenOption.TRUNCATE_EXISTING,
                         StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[BUF];
                long done = 0;
                long lastReport = 0;
                long lastCheck = System.currentTimeMillis();
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (cancelled != null && cancelled.get()) {
                        return fail("下载已取消");
                    }
                    out.write(buffer, 0, n);
                    if (d512 != null) {
                        d512.update(buffer, 0, n);
                    }
                    if (d1 != null) {
                        d1.update(buffer, 0, n);
                    }
                    done += n;
                    long now = System.currentTimeMillis();
                    if (on != null && now - lastReport >= THROTTLE_MS) {
                        lastReport = now;
                        on.onProgress(done, total);
                    }
                    // 周期性按整体吞吐 / 总时长判慢；仅当本轮还有下一条候选可换时才主动放弃换线。
                    // return 会跳过循环末尾的「最终进度」汇报与 verifyByDigest，不会在残缺文件上校验。
                    if (now - lastCheck >= THROUGHPUT_CHECK_MS) {
                        lastCheck = now;
                        if (ctx.watch.isTooSlow(done) && ctx.allowSlowAbort) {
                            ctx.markTooSlow();
                            return fail("下载线路太慢已放弃（连接超时或吞吐过低）");
                        }
                    }
                }
                if (on != null) {
                    on.onProgress(done, total);
                }
            }
            return verifyByDigest(d1, d512, sha1, sha512);
        } catch (IOException ex) {
            // 0.9.3：走代理的连接错误 → 标记，让 tryDownload 触发有界直连兜底（直连兜底本身不会再标）
            if (forceProxy == null && isConnectError(ex) && ProxyRoute.isProxied(url)) {
                ctx.proxyConnectFailed = true;
            }
            System.err.println("[fetch] 「" + part.getFileName() + "」单流没写成: " + ex);
            return fail("单流下载失败（连接被拒、读超时或网络断开）：" + ex.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 打开一个 {@link HttpURLConnection}：HTTP/1.1、跟随系统代理、带连接/读超时、默认跟随重定向。
     * {@code to > from} 发 Range；{@code from > 0} 且无 end 发开放式 Range。
     *
     * <p><b>传入的必须是「已定稿 URL」</b>：文件主源是官方 URL 原样，MCIM 兜底是 {@link #download}
     * 入口构造好的定稿 URL；本方法绝不再调用 {@link Mirror#rewriteContent}。代理决策基于这个
     * 已定稿 URL（MCIM 域 NO_PROXY、官方域按 {@link ProxyRoute} 判定）。
     */
    private static HttpURLConnection openResolved(String urlStr, String method, long from, long to)
            throws IOException {
        return openResolved(urlStr, method, from, to, true, null);
    }

    /** 同上，但可显式关闭自动跟随重定向（MCIM 兜底的 302 预检用，避免被 JVM 跟随到官方慢线）。 */
    private static HttpURLConnection openResolved(String urlStr, String method, long from, long to,
                                                  boolean followRedirects)
            throws IOException {
        return openResolved(urlStr, method, from, to, followRedirects, null);
    }

    /**
     * 同上，但 {@code forceProxy} 非 null 时忽略 {@link ProxyRoute} 选路、强制走该代理
     * （0.9.3 直连兜底重试时传 {@link Proxy#NO_PROXY}，给代理闪退一个出口）。
     */
    private static HttpURLConnection openResolved(String urlStr, String method, long from, long to,
                                                  boolean followRedirects, Proxy forceProxy)
            throws IOException {
        URL u = new URL(urlStr);
        Proxy proxy = forceProxy != null ? forceProxy : resolveProxy(urlStr);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection(proxy);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(followRedirects);
        conn.setRequestMethod(method);
        conn.setRequestProperty("User-Agent", GameDownloader.AGENT);
        conn.setRequestProperty("Accept", "application/octet-stream");
        conn.setRequestProperty("Accept-Encoding", "identity");
        if (to > from) {
            conn.setRequestProperty("Range", "bytes=" + from + "-" + to);
        } else if (from > 0) {
            conn.setRequestProperty("Range", "bytes=" + from + "-");
        }
        return conn;
    }

    /**
     * MCIM 兜底候选的「文件分发可用性」预检（0.9.1 第二阶段；0.9.2 提速）。
     *
     * <p>按 host 做进程内缓存：MCIM 文件分发状态一次启动内不变，恢复服务后重启启动器即刷新，
     * 不设 TTL / 定时刷新。整合包几十个小文件只做一次 HEAD 预检，不再每个文件都白等
     * 10s+（串行叠加就是「根本下载不动」）。{@link Mirror#hostOf} 解析失败时退化为
     * {@code "mcim"} 这个固定 key，保证 {@code computeIfAbsent} 的 key 不为 null。
     *
     * <p>真正的探测在 {@link #probeMcimFile}：发一次<b>不跟随重定向</b>的 HEAD；若响应是 3xx
     * 且 {@code Location} 的 host 落在官方出海 CDN（{@link ProxyRoute#isOverseas}，覆盖
     * cdn/api.modrinth.com、edge/media.forgecdn.net 等），判定「MCIM 文件分发已暂停、302 回官方
     * 慢线」→ 返回 false，跳过该兜底、不跟随，避免白多一跳。只比 host 不比完整路径
     * （MCIM 会把 {@code %2B} 解成 {@code +}）。
     *
     * <p>2xx / 非 3xx / Location 非出海域 → true（交正式下载自己处理）；预检本身连不上、超时
     * 或解析失败 → false：镜像此刻不可用，别再让正式下载白等一轮 MCIM 兜底。调用方
     * {@link #download} 里 {@code mcimUsable=false} 会走「官方主源 + {@code Attempt(false)}
     * 死守慢线」的保底路径，仍保证装得上，反而更快。
     */
    private static boolean mcimFileAvailable(String mirrorUrl) {
        String host = Mirror.hostOf(mirrorUrl);
        String key = host == null ? "mcim" : host;
        return MCIM_FILE_CACHE.computeIfAbsent(key, k -> probeMcimFile(mirrorUrl));
    }

    /** 单个 host 的 MCIM 文件分发预检本体；结果由 {@link #MCIM_FILE_CACHE} 按 host 缓存。 */
    private static boolean probeMcimFile(String mirrorUrl) {
        HttpURLConnection conn = null;
        try {
            conn = openResolved(mirrorUrl, "HEAD", 0, 0, false);
            // openResolved 内部设的是全局 10s/30s；返回时连接尚未建立，这里收紧到 3s/5s，
            // 让「镜像此刻不可用」尽快判定。正式下载仍走全局超时，不受影响。
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                if (loc != null && ProxyRoute.isOverseas(loc)) {
                    String h = Mirror.hostOf(loc);
                    System.err.println("[fetch] MCIM 文件分发不可用（HTTP " + code + " → "
                            + (h == null ? loc : h) + "），跳过镜像兜底，不改走官方慢线");
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException ex) {
            // 预检连不上 / 超时 / 解析失败：镜像此刻不可用，别再让正式下载白等一轮兜底
            System.err.println("[fetch] MCIM 文件预检失败，跳过镜像兜底：" + ex);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 线路交给 {@link ProxyRoute}：只对 Modrinth / CurseForge / GitHub 等「出海域名」判定，
     * Mojang、BMCLAPI、LittleSkin 等原本直连可用的域名一律直连，不动已验证通路。
     */
    private static Proxy resolveProxy(String urlStr) {
        try {
            return ProxyRoute.forUrl(urlStr);
        } catch (RuntimeException ignored) {
            return Proxy.NO_PROXY;
        }
    }

    /** 末尾一次性顺序读校验（分片档用）。 */
    private static boolean verifyByRead(Path file, String sha1, String sha512) {
        String want512 = norm(sha512);
        String want1 = norm(sha1);
        if (want512.isEmpty() && want1.isEmpty()) {
            return true;
        }
        String got = !want512.isEmpty() ? hexOf(file, "SHA-512") : hexOf(file, "SHA-1");
        if (got.isEmpty()) {
            return true; // 算不出来按不校验，别硬判失败
        }
        boolean ok = want512.isEmpty() ? want1.equals(got) : want512.equals(got);
        if (!ok) {
            System.err.println("[fetch] 「" + file.getFileName() + "」哈希对不上，当没下");
            markFailure("文件校验没过（哈希不符），可能下到残包或被替换");
        }
        return ok;
    }

    /** 用边下边算的流式摘要直接比对（单流档用，零额外读盘）。 */
    private static boolean verifyByDigest(MessageDigest d1, MessageDigest d512,
                                         String sha1, String sha512) {
        String want512 = norm(sha512);
        String want1 = norm(sha1);
        if (!want512.isEmpty()) {
            boolean ok = want512.equals(toHex(d512.digest()));
            if (!ok) {
                markFailure("文件校验没过（SHA-512 哈希不符），可能下到残包或被替换");
            }
            return ok;
        }
        if (!want1.isEmpty()) {
            boolean ok = want1.equals(toHex(d1.digest()));
            if (!ok) {
                markFailure("文件校验没过（SHA-1 哈希不符），可能下到残包或被替换");
            }
            return ok;
        }
        return true;
    }

    private static String hexOf(Path file, String algorithm) {
        MessageDigest digest = newDigest(algorithm);
        if (digest == null) {
            return "";
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[BUF];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        } catch (IOException | RuntimeException ex) {
            System.err.println("[fetch] 「" + file.getFileName() + "」算 " + algorithm
                    + " 失败，按不校验处理: " + ex);
            return "";
        }
        return toHex(digest.digest());
    }

    private static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (java.security.NoSuchAlgorithmException ex) {
            return null;
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 期望哈希归一：null / 空白 → 空串；否则小写。 */
    private static String norm(String sha) {
        return sha == null ? "" : sha.trim().toLowerCase(Locale.ROOT);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException ex) {
            System.err.println("[fetch] 清半成品失败（不影响使用）: " + ex);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 0.9.9 r6：读 HTTP 429 的 {@code Retry-After}。只认「整数秒」（HTTP-date 等非整数格式忽略），
     * 并 clamp 到 {@link #MAX_429_BACKOFF_MS}，绝不让单个文件因服务端给出的超大值无限等待。
     *
     * @return 建议退避毫秒；0 = 无有效 Retry-After，交调用方走默认退避
     */
    private static long parseRetryAfterMs(HttpURLConnection conn) {
        if (conn == null) {
            return 0L;
        }
        String raw = conn.getHeaderField("Retry-After");
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            long sec = Long.parseLong(raw.trim());
            if (sec <= 0L) {
                return 0L;
            }
            // 用「先比较秒再相乘」，避免超大整数秒在 *1000 时溢出成负数
            if (sec >= MAX_429_BACKOFF_MS / 1000L) {
                return MAX_429_BACKOFF_MS;
            }
            return sec * 1000L;
        } catch (NumberFormatException ex) {
            return 0L;   // 非整数秒（如 HTTP-date）：按无 Retry-After 处理
        }
    }

    /** 0.9.9 r6：无 Retry-After 时的 429 退避 = 2000ms*(attempt+1)，同样受 20s 上限约束。 */
    private static long default429Backoff(int attempt) {
        return Math.min(MAX_429_BACKOFF_MS, 2000L * (attempt + 1L));
    }

    /** 0.9.9 r6：登记一次 429——把退避建议写进本轮共享的 {@link Attempt}，由 tryDownload 的重试循环
     *  统一 sleep，避免在 fetch 层与重试层各睡一次造成双倍等待。 */
    private static void note429(HttpURLConnection conn, Attempt ctx, int attempt) {
        if (ctx == null) {
            return;
        }
        long ra = parseRetryAfterMs(conn);
        ctx.retryAfterMs429 = ra > 0L ? ra : default429Backoff(attempt);
    }

    /**
     * 0.9.3：判断异常是否属「连接级错误」——代理进程挂了 / 端口消失 / 连接被拒 / 连接超时等。
     * 这类错误才值得走直连兜底；4xx/5xx、哈希不符等「内容级」错误不在此列（直连也救不了）。
     */
    private static boolean isConnectError(Throwable t) {
        if (t == null) {
            return false;
        }
        String m = t.getMessage();
        if (m != null) {
            String lower = m.toLowerCase(Locale.ROOT);
            if (lower.contains("connection refused") || lower.contains("connection timed out")
                    || lower.contains("connect") || lower.contains("timed out")
                    || lower.contains("no route") || lower.contains("unreachable")
                    || lower.contains("proxy") || lower.contains("reset")) {
                return true;
            }
        }
        for (Throwable c = t; c != null; c = c.getCause()) {
            String name = c.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (name.contains("connect") || name.contains("socket")
                    || name.contains("nosuch") || name.contains("unknownhost")
                    || name.contains("timeout")) {
                return true;
            }
        }
        return false;
    }
}
