package com.mclauncher;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 网络层（v0.5.9 起不再内置代理逻辑）。
 *
 * <p>实测结论：本机在 Clash 等工具的 TUN 透明代理下，第三方启动器（PCL / HMCL）都是
 * <b>直接连接</b>微软登录域就能成功——流量被 TUN 在系统层兜底接走。因此 GBL 也改为纯直连，
 * 不再主动探测 / 安装任何代理。早期版本里「自动扫描本机代理端口并强制走 HTTP 代理」的
 * 逻辑反而是 bug：它会把请求引到本机不工作的 HTTP 代理端口（如 Clash 的混合端口），
 * 导致在直连本可用的环境下微软登录反而失败。需要代理时交给系统 / TUN 层处理即可。
 *
 * <p>保留这些方法是历史兼容：调用方无需改动即可编译，但全部为 no-op，运行时就是直连。
 */
public final class Net {

    /** 单张图总预算（秒）：并发竞速全部候选的总上限，从真正开始下载时算起。0.9.2 r4：12 → 8。 */
    private static final long IMAGE_BUDGET_SECONDS = 8;
    /** 单个候选一次 HTTP GET 最长等待（毫秒）。0.9.2 r4：4s → 3s，并发竞速不再靠单候选硬扛。 */
    private static final long PER_REQUEST_TIMEOUT_MS = 3000;
    /**
     * 并发上限由线程池兑现，而不是「抢不到信号量就丢请求」。
     * 0.8.6 首版用 {@code Semaphore(4) + tryAcquire(1.5s)}：模组库一屏上百张封面同时请求时，
     * 绝大多数连 HTTP 都没发就被丢弃（满屏「封面下载排队超时」），且每张图照样起一条线程，
     * 既没省资源又把用户最在意的封面全变成了占位图。
     */
    private static final int IMAGE_THREADS = 10;
    /**
     * 封面下载主池。池大小取自设置项 {@code maxConcurrentDownloads}（≤0 兜底 4），
     * 由 {@link #imagePool()} 在首次使用时惰性创建——刻意不放在类加载的 static 初始化里，
     * 以避免与 {@link SettingsStore} 的配置加载产生静态初始化顺序耦合。
     * <p>
     * 说明：固定大小线程池一旦创建便无法在线程数上动态伸缩，故池大小只取「首次实际下载」那一刻
     * 的设置值；运行期改设置需重启启动器生效。这与设计意图（有界池兑现并发上限）一致，
     * 不引入可热改线程数的额外复杂度。
     */
    private static volatile ExecutorService IMAGE_POOL;

    /**
     * 惰性、线程安全地获取封面下载主池：延迟到首次下载才读取 {@link SettingsStore} 设置，
     * 用 {@code volatile} + 双重检查（double-checked locking）保证只构建一次且多线程安全。
     *
     * @return 封面下载主池（线程名前缀 {@code gbl-image}，daemon 线程）
     */
    private static ExecutorService imagePool() {
        ExecutorService pool = IMAGE_POOL;
        if (pool == null) {
            synchronized (Net.class) {
                pool = IMAGE_POOL;
                if (pool == null) {
                    int n = SettingsStore.get().maxConcurrentDownloads();
                    if (n <= 0) {
                        n = 4;
                    }
                    IMAGE_POOL = Executors.newFixedThreadPool(n, r -> {
                        Thread t = new Thread(r, "gbl-image");
                        t.setDaemon(true);
                        return t;
                    });
                    pool = IMAGE_POOL;
                }
            }
        }
        return pool;
    }
    /**
     * 0.9.2 r4 候选并发竞速专用有界池：{@link #downloadImage} 本身已跑在 IMAGE_POOL 的线程上，
     * 若再把候选子任务也提交进 IMAGE_POOL，一屏封面同时下载时会「池内套池」——10 条线程全被
     * 父任务占着等子任务，子任务又排队等线程，直接饥饿死锁。故用独立有界 daemon 池跑候选 GET。
     */
    private static final ExecutorService IMAGE_RACE_POOL = Executors.newFixedThreadPool(IMAGE_THREADS * 2, r -> {
        Thread t = new Thread(r, "gbl-image-race");
        t.setDaemon(true);
        return t;
    });
    /** 同一 URL 在途只拉一次：同一个图标常同时出现在卡片、详情、整合包行里。 */
    private static final ConcurrentMap<String, CompletableFuture<byte[]>> INFLIGHT = new ConcurrentHashMap<>();
    /** 全局共用一个 HttpClient（连接池复用），不再每张封面 new 一个。 */
    private static final HttpClient IMAGE_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .proxy(ProxyRoute.selector())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 0.9.3 封面恢复 MCIM 回退指纹。构建门禁会扫描产物 class 文件的 CONSTANT_Utf8 池，
     * 校验本字面量确实被编译进 jar（而非仅停留在注释里）。请勿改动这串字。
     */
    private static final String COVER_MCIM_FALLBACK_FINGERPRINT = "0.9.3 封面恢复MCIM回退";

    static {
        // 0.9.3 封面恢复 MCIM 回退：随版上线埋点，便于构建门禁核验指纹已随版发布。
        System.err.println("[net] cover-mcim-fallback: " + COVER_MCIM_FALLBACK_FINGERPRINT);
    }

    /**
     * 只读出口：返回全局复用的封面 / 通用图片请求客户端 {@link #IMAGE_CLIENT}（挂
     * {@code ProxyRoute.selector()}、{@code followRedirects(NORMAL)}）。本方法只返回句柄、
     * 不改动客户端任何配置，供封面链路按需复用。
     *
     * <p>注意：内容<b>文件</b>下载链路不走这里——它必须用
     * {@code RaceFetch.newClient(6000)}（强制 HTTP/1.1 + {@code ProxyRoute.selector()} +
     * {@code Redirect.NEVER}），否则会把 0.9.0 修过的 HTTP/2 吞吐回归带回来。
     */
    public static HttpClient imageClient() {
        return IMAGE_CLIENT;
    }
    /** 封面磁盘缓存（HMCL 同款思路）：同一张图标只在首次下载，重开启动器不再挤爆线程池。 */
    private static final Path COVER_CACHE_DIR =
            Paths.get(System.getProperty("user.home"), ".gbl", "covers");

    private Net() {
    }

    /** 启动兼容入口：纯直连，不安装任何代理。 */
    public static void installProxyFromSystem() {
        // no-op：纯直连
    }

    /** 兼容入口：纯直连，不安装任何代理（manualAddress 忽略）。 */
    public static void configure(String manualAddress) {
        // no-op：纯直连
    }

    /** 兼容入口：纯直连，不安装任何代理。 */
    public static void reconfigure(String manualAddress) {
        // no-op：纯直连
    }

    /**
     * 微软登录触发点：纯直连下无需任何代理准备，直接放行。
     * （历史版本在此自动扫描本机代理端口并强制走 HTTP 代理，已移除——那会让直连可用的
     * 环境下反而失败。）
     */
    public static void ensureProxyForMicrosoft() {
        // no-op：纯直连
    }

    /** 当前生效的代理选择器：始终返回平台默认（直连），供 HttpClient 挂载。 */
    public static ProxySelector currentSelector() {
        return ProxySelector.getDefault();
    }

    /** 当前生效的代理地址：纯直连下无代理，返回 null。 */
    public static String appliedProxyAddress() {
        return null;
    }

    /** 连通性探针：能拿到响应就说明网络通。保留供将来按需使用。 */
    public static boolean testReachMicrosoft(java.time.Duration timeout) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(timeout)
                    .proxy(ProxySelector.getDefault())
                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                    .build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create("https://login.microsoftonline.com/consumers/oauth20/v2.0/devicecode"))
                    .timeout(timeout)
                    .GET()
                    .build();
            client.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (java.io.IOException | InterruptedException ex) {
            return false;
        }
    }

    /**
     * 异步拉封面字节：所有请求排进有界线程池，<b>不丢弃</b>；同 URL 在途合并成一次下载。
     * 回调在池线程上执行，UI 侧需自行 {@code Platform.runLater}。
     */
    public static void downloadImageAsync(String url, Consumer<byte[]> callback) {
        if (url == null || url.isBlank()) {
            callback.accept(null);
            return;
        }
        CompletableFuture<byte[]> future = INFLIGHT.computeIfAbsent(url, u -> {
            CompletableFuture<byte[]> f = new CompletableFuture<>();
            try {
                imagePool().execute(() -> {
                    try {
                        f.complete(downloadImage(u));
                    } catch (Throwable t) {
                        f.complete(null);
                    } finally {
                        INFLIGHT.remove(u, f);
                    }
                });
            } catch (RejectedExecutionException ex) {
                INFLIGHT.remove(u, f);
                f.complete(null);
            }
            return f;
        });
        future.thenAccept(callback);
    }

    /**
     * 下载图片字节（模组 / 整合包封面用）。
     *
     * <p><b>为什么不用 JavaFX 自带的 {@code new Image(url)}：</b>那东西走 JavaFX 自己的 HTTP 栈，
     * 不经过本机代理 / TUN，国服下 CDN 拉不到。这里改用应用自己的 HttpClient 把字节拉回来，
     * 由调用方本地构造 Image。
     *
     * <p><b>0.8.6 三次修订：</b>① 去掉「排队超时即放弃」的信号量，改由
     * {@link #downloadImageAsync(String, Consumer)} 的有界线程池排队兑现并发上限；
     * ② 命中磁盘缓存直接返回，不给 CDN 制造重复洪峰；③ 校验图片魔数，避免把错误页缓存下来。
     *
     * <p><b>0.9.2 r4（别人机器封面全不显示的根因修复）：</b>旧实现是<b>串行 for</b> 逐个候选
     * 试，单候选 4s 超时 + 600ms 退避 ×2、总预算 12s；一屏 8 张卡打满 IMAGE_POOL(10) 后集体
     * 超时 → 清一色占位图（开发者本机 1332 个 {@code ~/.gbl/covers} 缓存掩盖了问题）。
     * 现改为<b>全部候选并发竞速</b>：谁先返回有效图片用谁，loser 取消并关闭；单候选 3s、
     * 总预算 8s、去掉退避重试（并发已覆盖）。候选以官方 CDN 原 URL 直连置顶、同站换 host 次之，
     * 0.9.3 起再把 MCIM（mod.mcimirror.top）作为<b>最后兜底</b>候选（见 {@link #imageUrlCandidates}），
     * 不再像旧版那样把 MCIM 置顶改写。
     *
     * <p><b>返回语义不变</b>：拿到图片字节返回之，任意失败返回 null（并打印一条带 host /
     * 耗时 / 原因的埋点），由调用方显示占位图。
     */
    public static byte[] downloadImage(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        byte[] cached = readCoverCache(url);
        if (cached != null) {
            return cached;
        }
        long startMs = System.currentTimeMillis();
        String[] candidates = imageUrlCandidates(url);
        long perCallMs = Math.min(PER_REQUEST_TIMEOUT_MS,
                TimeUnit.SECONDS.toMillis(IMAGE_BUDGET_SECONDS));
        // 全部候选同时发 GET：并发竞速，慢候选不再拖住整批封面。
        List<CompletableFuture<byte[]>> pending = new ArrayList<>(candidates.length);
        for (String candidate : candidates) {
            pending.add(CompletableFuture.supplyAsync(
                    () -> fetchBytes(candidate, perCallMs), IMAGE_RACE_POOL));
        }
        // 「第一个有效图片」聚合：result 只被第一个拿到字节的候选 complete，其余候选取消。
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        AtomicBoolean won = new AtomicBoolean(false);
        AtomicInteger remaining = new AtomicInteger(candidates.length);
        List<String> failures = new CopyOnWriteArrayList<>();
        for (CompletableFuture<byte[]> f : pending) {
            f.whenComplete((data, err) -> {
                if (data != null && won.compareAndSet(false, true)) {
                    result.complete(data);
                } else if (err != null) {
                    failures.add(describeFailure(err));
                } else if (data == null) {
                    failures.add("非图片或非 2xx 响应");
                }
                if (remaining.decrementAndGet() == 0 && !won.get()) {
                    result.complete(null);   // 所有候选都结束且无人胜出
                }
            });
        }
        byte[] data = null;
        try {
            data = result.get(IMAGE_BUDGET_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            failures.add("被中断");
        } catch (Exception ex) {
            failures.add("总预算 " + IMAGE_BUDGET_SECONDS + "s 内无有效图片: " + describeFailure(ex));
        } finally {
            // 胜出 / 超时 / 全失败都取消其余候选：cancel 让落败 future 立即完成、其结果不再被采用；
            // 仍在途的慢候选由 3s 单次请求超时自行结束并归还连接，不残留占着 IMAGE_RACE_POOL。
            for (CompletableFuture<byte[]> f : pending) {
                f.cancel(true);
            }
        }
        if (data != null) {
            writeCoverCache(url, data);
            return data;
        }
        // 全部候选失败：一条可读埋点（host + 耗时 + 失败原因），便于跨机器排查，绝不静默返回 null。
        System.err.println("[net] 封面全部候选失败: host=" + hostOf(url)
                + " 候选数=" + candidates.length
                + " 耗时=" + (System.currentTimeMillis() - startMs) + "ms"
                + " 原因=" + String.join(" | ", failures));
        return null;
    }

    /** 一次 HTTP GET；返回有效图片字节，任何失败（含非 2xx / 非图片）返回 null，供并发竞速判定。 */
    private static byte[] fetchBytes(String url, long timeoutMs) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(1L, timeoutMs)))
                    .header("User-Agent", "GBL-Launcher")
                    .header("Accept", "image/*")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = IMAGE_CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300
                    && looksLikeImage(resp.body())) {
                return resp.body();
            }
            return null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CompletionException("被中断", ie);
        } catch (IOException ex) {
            throw new CompletionException(ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
        }
    }

    /** 埋点用：从 URL 抠 host，解析失败就退回原串。 */
    private static String hostOf(String url) {
        try {
            String h = URI.create(url).getHost();
            return h == null ? url : h;
        } catch (IllegalArgumentException ex) {
            return url;
        }
    }

    /** 埋点用：把 CompletionException / ExecutionException 解包成「类名: 消息」一行。 */
    private static String describeFailure(Throwable err) {
        Throwable t = err;
        while ((t instanceof CompletionException || t instanceof ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg);
    }

    /** 只认常见图片魔数，防止把 CDN 的 XML/HTML 错误页当成图片缓存。 */
    private static boolean looksLikeImage(byte[] b) {
        if (b == null || b.length < 12) {
            return false;
        }
        int b0 = b[0] & 0xFF;
        int b1 = b[1] & 0xFF;
        if (b0 == 0x89 && b1 == 0x50 && b[2] == 0x4E && b[3] == 0x47) {
            return true;                                  // PNG
        }
        if (b0 == 0xFF && b1 == 0xD8) {
            return true;                                  // JPEG
        }
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') {
            return true;                                  // GIF
        }
        if (b[0] == 'B' && b[1] == 'M') {
            return true;                                  // BMP
        }
        return b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P'; // RIFF/WEBP
    }

    private static Path coverCacheFile(String url) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8));
            return COVER_CACHE_DIR.resolve(HexFormat.of().formatHex(hash) + ".img");
        } catch (Exception ex) {
            return null;
        }
    }

    private static byte[] readCoverCache(String url) {
        try {
            Path p = coverCacheFile(url);
            if (p == null || !Files.isRegularFile(p)) {
                return null;
            }
            byte[] data = Files.readAllBytes(p);
            return looksLikeImage(data) ? data : null;
        } catch (IOException ex) {
            return null;
        }
    }

    private static void writeCoverCache(String url, byte[] data) {
        try {
            Path p = coverCacheFile(url);
            if (p == null || !looksLikeImage(data)) {
                return;
            }
            Files.createDirectories(COVER_CACHE_DIR);
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.write(tmp, data);
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException ex) {
            // 缓存写失败不影响本次显示
        }
    }

    /**
     * 已知 CDN 主机不可达时的回落候选（同站资产，换主机保留路径）。
     * Modrinth 与 CurseForge 两套 CDN 都列上；换主机只是尽力而为，全失败由调用方显示占位图。
     *
     * <p><b>0.9.2 r4（别人机器封面全不显示的根因修复）：</b>旧实现把每个候选都过一遍
     * {@code Mirror.rewriteContent} 生成 MCIM 候选并<b>置顶</b>，于是连 {@code cdn.modrinth.com} /
     * {@code media.forgecdn.net} 这类 CDN 文件域都被改写到 {@code mod.mcimirror.top}；在无本地
     * 封面缓存的机器上，8 张卡并发全打在 MCIM + 串行退避上集体超时 → 清一色占位图。
     * 现对齐 LauncherX：封面<b>直连官方 CDN 原 URL</b>，不再把 MCIM 置顶改写；官方原 URL 置顶，
     * 同站换 host 仅作第二候选。<b>0.9.3 起把 MCIM（mod.mcimirror.top）作为最后兜底候选</b>
     * （见方法体内 rewriteContent 调用），既保留 r4 的并发竞速设计，又恢复境内 MCIM 快速回落。
     * 文件下载链路仍用 {@code rewriteContent()} 生成 MCIM 备源（不改 Mirror.java）。
     */
    private static String[] imageUrlCandidates(String url) {
        // 官方原 URL 置顶（直连），同站换 host 作为第二候选；LinkedHashSet 去重且保序。
        java.util.LinkedHashSet<String> ordered = new java.util.LinkedHashSet<>();
        ordered.add(url);
        if (url.contains("media.modrinth.com")) {
            ordered.add(url.replace("media.modrinth.com", "cdn.modrinth.com"));
        } else if (url.contains("cdn.modrinth.com")) {
            ordered.add(url.replace("cdn.modrinth.com", "media.modrinth.com"));
        } else if (url.contains("media.forgecdn.net")) {
            ordered.add(url.replace("media.forgecdn.net", "edge.forgecdn.net"));
        } else if (url.contains("edge.forgecdn.net")) {
            ordered.add(url.replace("edge.forgecdn.net", "media.forgecdn.net"));
        } else if (url.contains("media.overwolf.com")) {
            ordered.add(url.replace("media.overwolf.com", "content.overwolf.com"));
        }
        // 0.9.3 封面恢复 MCIM 回退：把镜像（mod.mcimirror.top）作为最后兜底候选。
        // 官方 CDN（Cloudflare）在国服常被墙/慢，置顶直连仍超时后由 MCIM 境内 IP 兜底。
        // 这里对「官方 + 同站换 host」的每个候选都再过一遍 Mirror.rewriteContent：
        // 它不仅覆盖 cdn.modrinth.com，也能把 edge/media.forgecdn.net 映射到 MCIM；
        // 对 media.modrinth.com 这种 rewriteContent 不直接负责的源域名，则靠上面的同站换 host
        // （media → cdn）候选被 rewriteContent 命中而落到 MCIM，确保两套 Modrinth 主机都能回退。
        // GBL_MIRROR=off 时 rewriteContent 原样返回，不会产生多余候选（LinkedHashSet 去重）。
        java.util.List<String> snapshots = new java.util.ArrayList<>(ordered);
        for (String candidate : snapshots) {
            ordered.add(Mirror.rewriteContent(candidate));
        }
        return ordered.toArray(new String[0]);
    }
}
