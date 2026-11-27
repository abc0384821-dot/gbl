package com.mclauncher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内容下载的「出海线路」决策层（v0.9.0 提速第三轮）。
 *
 * <p><b>为什么需要它：</b>本机实测 `cdn.modrinth.com` 直连 13.6 KB/s，走本机 Clash 混合端口
 * 127.0.0.1:7897 是 2.06 MB/s —— <b>差 150 倍</b>。1.9 MB 的 Sodium 直连要 140 秒，走代理 0.9 秒。
 * 这是「HMCL 秒下、GBL 一分钟」的真实原因：<b>HMCL 自动读系统代理，GBL 原来不走</b>。
 * 查证 HMCL 源码（0.9.1 实测订正）：{@code EntryPoint.java:33} 设
 * {@code java.net.useSystemProxies=true}、{@code ProxyManager.java:26} 用
 * {@code ProxySelector.getDefault()}，因此 HMCL 零配置就能用上 Windows 系统代理；其
 * {@code HMCLDownloadProvider.java:150-154} 的 MCIM 规则是
 * {@code SimpleRule("https://cdn.modrinth.com", null, "https://mod.mcimirror.top")}
 * （{@code first=null, fallback=MCIM}）——候选顺序<b>恒为 [官方 cdn, MCIM]、官方在前</b>，
 * {@code FetchTask} 串行回退，并非「靠 MCIM 国内镜像无代理秒下」。
 *
 * <p><b>本类才是 GBL 提速的关键：</b>把<b>官方文件 URL 原样</b>交给本类，让它命中
 * {@link #OVERSEAS} 后缀，走「系统代理注册表 → 本地端口并发竞速选路」。反之若先把文件 URL
 * 改写成 {@code mod.mcimirror.top}（该域不在 {@link #OVERSEAS}），{@link #forUrl} 会在
 * {@code isOverseas()} 处直接返回 {@code Proxy.NO_PROXY}、<b>整套选路被绕过、文件恒直连</b>——
 * 这正是 GBL 文件下载慢的代码根因。MCIM 的<b>元数据 API 仍可用</b>（实测 200、境内 IP），
 * 但<b>文件分发上游已公告暂停</b>：文件 URL 普遍 302 回官方出海 CDN，只多一跳、无加速，
 * 故只作主源失败后的兜底候选（见 {@link FileFetch}）。
 *
 * <p><b>历史包袱：</b>{@link Net} 从 0.5.9 起被刻意改成「纯直连、靠 Clash TUN 在系统层兜底」，
 * 因为早期「无条件扫描代理端口并强制走 HTTP 代理」会把微软登录拽到不工作的端口上。但 TUN 的
 * 分流规则是把 `modrinth.com` 丢进 DIRECT 的，于是模组下载永远慢——直连能通但只有十几 KB/s，
 * 表现为「不是失败，只是慢」，最容易被忽略。
 *
 * <p><b>本轮策略（只给「出海域名」用代理，其它一律直连）：</b>
 * <ol>
 *   <li>只对 {@link #OVERSEAS} 里的后缀（Modrinth / CurseForge / GitHub / 各 maven）做线路决策；
 *       Mojang、BMCLAPI、LittleSkin 等原本就直连可用的域名<b>保持直连</b>，绝不改动已验证通路。</li>
 *   <li>决策顺序：环境变量 {@code gbl_proxy}（{@code direct} 或 {@code host:port}）→
 *       <b>Windows 系统代理注册表</b>（Clash / v2rayN 开了「系统代理」模式就会写进注册表——
 *       这正是 HMCL 零配置秒下的同一来源）→ 环境变量 {@code https_proxy}（CI/沙箱）→
 *       本机常见代理客户端端口（Clash TUN 模式下的混合口、v2rayN / Nekoray…）。</li>
 *   <li>候选集合与「直连」一起<b>并行</b>拉同一个真实 URL 的前 128 KB，量出各自速度，选最快的。
 *       并发探测，最坏 2 秒，全 JVM 只做一次；无任何代理（包括系统代理没开、也没本地端口）时
 *       直接返回直连、不探测、零延迟。</li>
 * </ol>
 *
 * <p>探测用真实 URL（调用方传进来的那个）而不是写死的测速端点——测速端点走的是另一条
 * Cloudflare 线路，实测直连 132 KB/s，与 `cdn.modrinth.com` 的 13.6 KB/s 差一个数量级，
 * 拿它当基准会选错线路。
 */
public final class ProxyRoute {

    /**
     * 需要走代理的「出海」域名后缀。命中即参与线路决策，未命中的一律直连。
     */
    private static final String[] OVERSEAS = {
            "modrinth.com",          // api / cdn / 官网
            "forgecdn.net",          // CurseForge 文件 CDN
            "curseforge.com",
            "github.com",
            "githubusercontent.com",
            "fabricmc.net",          // Fabric maven（Cloudflare）
            "quiltmc.org",
            "neoforged.net",         // NeoForge maven
            "minecraftforge.net",
            "optifine.net",
            "adoptium.net",
            "jetbrains.com",
    };

    /**
     * 常见代理客户端的本地监听端口（HTTP 混合口优先）。探测时并发逐个试，
     * 只有真能当 HTTP 代理用、且确实更快的才会被选中。
     */
    private static final int[] CANDIDATE_PORTS = {
            7897,   // Clash Verge / Mihomo 混合口（本机就是这个）
            7890,   // Clash for Windows
            7891, 7892, 7893, 7899,
            10809,  // v2rayN HTTP
            10808,  // v2rayN SOCKS（HTTP 探测会失败，自动排除）
            1080, 1081, 1087,
            2080, 2081,   // Nekoray
            20171,        // 旧版 v2rayN
            8118,         // Privoxy
            8889, 6152,
    };

    /** 探测时单个候选的<b>总时间预算</b>（毫秒）。实测 2 MB/s 读满 128 KB 只要 60 ms，
     *  13 KB/s 的直连读不完就按「已读字节 / 已用时间」折算速度——不需要等它读完，
     *  所以 900 ms 足够区分「秒下」和「一分钟」，也不会把首下拖慢。 */
    private static final int PROBE_TIMEOUT_MS = 900;
    /** 探测读多少字节就够判断速度了。 */
    private static final int PROBE_BYTES = 128 * 1024;
    /** TCP 探活超时：本机端口，300ms 足够。 */
    private static final int PORT_PROBE_MS = 300;

    private static final ExecutorService PROBE_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "gbl-proxy-probe");
        t.setDaemon(true);
        return t;
    });

    /** 决策结果容器；开始探测前为 null。 */
    private static volatile Decision decision;
    private static final Object LOCK = new Object();

    private ProxyRoute() {
    }

    /** 一次线路决策的结果。 */
    private static final class Decision {
        final Proxy proxy;
        final long proxySpeed;
        final long directSpeed;
        final String label;
        /** 是否由「真实文件 URL」探测得来；元数据 / 封面图得来的决策会被 {@link #warmupForFile} 纠正。 */
        final boolean fromFile;

        Decision(Proxy proxy, long proxySpeed, long directSpeed, String label) {
            this(proxy, proxySpeed, directSpeed, label, false);
        }

        Decision(Proxy proxy, long proxySpeed, long directSpeed, String label, boolean fromFile) {
            this.proxy = proxy;
            this.proxySpeed = proxySpeed;
            this.directSpeed = directSpeed;
            this.label = label;
            this.fromFile = fromFile;
        }
    }

    /** 该 URL 是否属于「出海域名」（只有它们才参与代理决策）。 */
    public static boolean isOverseas(String url) {
        String host = hostOf(url);
        if (host.isEmpty()) {
            return false;
        }
        for (String suffix : OVERSEAS) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    /** 该 URL 最终会不会走代理（给 FileFetch 决定要不要分片用）。 */
    public static boolean isProxied(String url) {
        return forUrl(url) != Proxy.NO_PROXY;
    }

    /**
     * 解析某个 URL 该用哪条线路。<b>可能阻塞</b>（首次自动探测最多约 2 秒），
     * 之后全部走缓存，永不重复探测。
     */
    public static Proxy forUrl(String url) {
        if (!isOverseas(url)) {
            return Proxy.NO_PROXY;
        }
        Decision d = decision;
        if (d != null) {
            return d.proxy;
        }
        synchronized (LOCK) {
            if (decision == null) {
                decision = discover(url);
            }
            return decision.proxy;
        }
    }

    /** 给 {@code java.net.http.HttpClient} 用的选择器：内部转调 {@link #forUrl}。 */
    public static ProxySelector selector() {
        return SELECTOR;
    }

    private static final ProxySelector SELECTOR = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            if (uri == null) {
                return List.of(Proxy.NO_PROXY);
            }
            String scheme = uri.getScheme();
            if (scheme != null && !"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return List.of(Proxy.NO_PROXY);
            }
            return List.of(forUrl(uri.toString()));
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // 代理挂了：清掉决策，下次调用重新探测（例如用户关了 Clash 再开）
            invalidate();
        }
    };

    /** 丢弃已缓存的决策，下次重新探测（设置变更 / 代理连接失败时调用）。 */
    public static void invalidate() {
        synchronized (LOCK) {
            decision = null;
        }
    }

    /**
     * 0.9.3：代理选路的有界直连兜底。当某个走代理的出海请求发生<b>连接级错误</b>
     * （代理进程挂了 / 端口消失 / 连接被拒）时调用：清掉全局决策缓存，让后续请求重新探测线路，
     * 并返回一个「当前这一发改用直连重试一次」的信号。
     *
     * <p>保守设计——每个请求最多多试一次直连，绝不对同一条已失败的代理死磕，
     * 也不进入「代理 ↔ 直连」的无限循环。整体发现逻辑（{@link #discover}）完全不动。</p>
     *
     * @param url 失败的请求 URL（只对外海域名生效，境内域名本就直连、无需兜底）
     * @return 是否应对<b>当前这一发</b>请求改用 {@link Proxy#NO_PROXY} 重试（true=是，只应发生一次）
     */
    public static boolean retryDirectOnConnectFailure(String url) {
        if (!isOverseas(url)) {
            return false;
        }
        Decision d = decision;
        if (d == null || d.proxy == Proxy.NO_PROXY) {
            // 当前本就直连 / 还没决策：没有「代理 → 直连」可退，交给上层正常重试逻辑
            return false;
        }
        // 清掉全局决策缓存，让后续请求（含本次重试之后）重新探测；代理若真死了会自然落到直连
        invalidate();
        // 本次这一发改用直连重试一次
        return true;
    }

    /**
     * 后台预热（0.9.1 订正）：<b>只做本地代理端口探活</b>，不再用「小元数据 URL」缓存全局 decision。
     * 那个 URL（{@code api.modrinth.com/v2/tag/loader}）是几十 KB 的 JSON，直连 / 代理都在 100ms
     * 内读完，速度差被压平，很容易把 decision 选成「直连」；而真实文件 URL 的 128KB 直连要 10s、
     * 代理 0.1s。故 decision 一律留给真实文件 URL 首次触发（{@link #warmupForFile}）时再产生。
     * 后台 daemon 线程，不阻塞启动。
     */
    public static void prefetch() {
        Thread t = new Thread(() -> {
            try {
                openCandidateProxies();
            } catch (RuntimeException ignored) {
                // 端口探活失败无所谓，真正下载时 discover() 还会再试
            }
        }, "gbl-proxy-warmup");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 用「真实文件 URL」预热 / 纠正线路决策（供 {@link FileFetch#download} 在文件下载前调用）。
     *
     * <p>{@code decision} 仍是<b>全 JVM 单例</b>：仅当 {@code decision == null} 或它不是
     * 「文件级决策」（{@code !fromFile}，例如被封面图 / 元数据 {@link #forUrl} 抢先产生）时，
     * 才用这个真实文件 URL 重新 {@link #discover} 一次并缓存为 {@code fromFile=true}；
     * 已是文件级决策则不动。非出海域名直接返回（{@link #forUrl} 本就恒直连）。
     *
     * <p>这样首次选路用的是「真实文件 URL 的前 128KB」而非被压平速度差的小 JSON，
     * 有系统代理 / 本地端口时能选到代理；无代理时 {@link #discover} 在候选为空时立即返回直连
     * （不发网络请求、不引入额外阻塞，沿用 {@code PROBE_TIMEOUT_MS=900} 预算）。
     * 本方法尽力而为，异常不外抛，绝不因预热失败影响下载。
     */
    public static void warmupForFile(String realFileUrl) {
        if (!isOverseas(realFileUrl)) {
            return;
        }
        synchronized (LOCK) {
            if (decision != null && decision.fromFile) {
                return;
            }
            try {
                Decision d = discover(realFileUrl);
                decision = new Decision(d.proxy, d.proxySpeed, d.directSpeed, d.label, true);
            } catch (RuntimeException ignored) {
                // 保留旧 decision（或 null），真正 forUrl() 时还会再发现
            }
        }
    }

    /** 当前线路的人话描述，供日志 / 状态栏显示。 */
    public static String describe() {
        Decision d = decision;
        if (d == null) {
            return "未探测";
        }
        if (d.proxy == Proxy.NO_PROXY) {
            return "直连（" + kb(d.directSpeed) + "）";
        }
        return d.label + "（" + kb(d.proxySpeed) + " / 直连 " + kb(d.directSpeed) + "）";
    }

    /** 把代理加进候选，按地址去重（系统代理 / env / 本地端口 可能指向同一个 Clash）。 */
    private static void addUnique(List<Proxy> candidates, Proxy p) {
        if (p == null) {
            return;
        }
        for (Proxy c : candidates) {
            if (addressOf(c).equals(addressOf(p))) {
                return;
            }
        }
        candidates.add(p);
    }

    /**
     * 读 Windows 系统代理注册表（与 HMCL 同款来源）。Clash / v2rayN 开了「系统代理」模式就会把
     * `ProxyServer` 写进 `Internet Settings`，Java 自己不会自动用——必须我们自己读出来。
     * 返回 null 表示没开 / 非 Windows / 读不到。
     */
    private static Proxy systemProxyFromRegistry() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            return null;
        }
        try {
            String key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
            String server = regQuery(key, "ProxyServer");
            String enable = regQuery(key, "ProxyEnable");
            if (server == null || server.isEmpty()) {
                server = regQuery("HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Internet Settings", "ProxyServer");
                enable = regQuery("HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Internet Settings", "ProxyEnable");
            }
            if (server == null || server.isEmpty()) {
                return null;
            }
            String e = enable == null ? "" : enable.trim().toLowerCase(Locale.ROOT);
            boolean on = e.equals("1") || e.equals("0x1") || e.equals("0x00000001");
            if (!on) {
                return null;
            }
            return parseProxy(pickHttpOrHttps(server.trim()));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 调 `reg query`，800ms 超时，解析出 REG_SZ / REG_DWORD 的值。 */
    private static String regQuery(String key, String value) {
        try {
            ProcessBuilder pb = new ProcessBuilder("reg", "query", key, "/v", value);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(800, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String out = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            for (String line : out.split("\r?\n")) {
                String t = line.trim();
                if (t.contains(value) && t.contains("REG_")) {
                    String after = t.substring(t.indexOf("REG_"));
                    String[] parts = after.split("\\s+", 2);
                    if (parts.length == 2 && !parts[1].trim().isEmpty()) {
                        return parts[1].trim();
                    }
                }
            }
            return null;
        } catch (IOException | InterruptedException | RuntimeException ex) {
            return null;
        }
    }

    /** 从 `127.0.0.1:7890` 或 `http=..;https=..;socks=..` 形式里挑 https / http 的端口。 */
    private static String pickHttpOrHttps(String server) {
        if (!server.contains("=")) {
            return server;
        }
        String fallback = null;
        for (String pair : server.split(";")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                String k = kv[0].trim().toLowerCase(Locale.ROOT);
                String v = kv[1].trim();
                if (k.equals("https") || k.equals("http")) {
                    return v;
                }
                if (fallback == null) {
                    fallback = v;
                }
            } else if (fallback == null) {
                fallback = pair.trim();
            }
        }
        return fallback;
    }

    private static String kb(long bytesPerSec) {
        if (bytesPerSec <= 0) {
            return "未测速";
        }
        if (bytesPerSec >= 1024 * 1024) {
            return String.format(Locale.ROOT, "%.2f MB/s", bytesPerSec / 1048576.0);
        }
        return String.format(Locale.ROOT, "%.0f KB/s", bytesPerSec / 1024.0);
    }

    // ---------------------------------------------------------------- 探测

    private static Decision discover(String probeUrl) {
        // 1) 显式指定：gbl_proxy=direct 关掉，gbl_proxy=host:port 指名（用户说了算，不探测）
        String manual = trimToNull(System.getenv("gbl_proxy"));
        if (manual == null) {
            manual = trimToNull(System.getenv("GBL_PROXY"));
        }
        if (manual != null) {
            if ("direct".equalsIgnoreCase(manual) || "off".equalsIgnoreCase(manual)
                    || "none".equalsIgnoreCase(manual)) {
                return new Decision(Proxy.NO_PROXY, 0, 0, "直连（手动指定）");
            }
            Proxy p = parseProxy(manual);
            if (p != null) {
                return new Decision(p, 0, 0, "代理 " + addressOf(p) + "（手动指定）");
            }
        }
        // 2) 候选集合（全部参与竞速，让实测速度说话，而不是谁先被读到）：
        //    Windows 系统代理（Clash 开了「系统代理」模式就会写进注册表 —— HMCL 就是靠它零配置秒下）
        //    + 环境变量 https_proxy（CI / 沙箱）+ 本机已监听的代理端口（Clash TUN 模式下的混合口）
        //    + 直连。环境变量里的代理未必最快（沙箱里就有一个 24 KB/s 的）。
        List<Proxy> candidates = new ArrayList<>();
        addUnique(candidates, systemProxyFromRegistry());   // 与 HMCL 同款：读注册表系统代理
        String env = trimToNull(System.getenv("https_proxy"));
        if (env == null) {
            env = trimToNull(System.getenv("HTTPS_PROXY"));
        }
        if (env != null) {
            addUnique(candidates, parseProxy(env));
        }
        for (Proxy p : openCandidateProxies()) {
            addUnique(candidates, p);
        }
        if (candidates.isEmpty()) {
            return new Decision(Proxy.NO_PROXY, 0, 0, "直连（本机没有代理客户端）");
        }
        // 3) 竞速：同一真实 URL 的前 128 KB，并发量速，最快的胜出；直连也参赛
        candidates.add(Proxy.NO_PROXY);
        List<Probe> results = raceProbe(probeUrl, candidates);
        long direct = 0;
        Probe best = null;
        for (Probe r : results) {
            if (r == null) {
                continue;
            }
            if (r.proxy == Proxy.NO_PROXY) {
                direct = r.speed();
            }
            if (r.bytes > 0 && (best == null || r.speed() > best.speed())) {
                best = r;
            }
        }
        if (best == null || best.proxy == Proxy.NO_PROXY) {
            return new Decision(Proxy.NO_PROXY, 0, direct, "直连（代理没测出优势）");
        }
        String label = "代理 " + addressOf(best.proxy);
        StringBuilder sb = new StringBuilder();
        for (Probe r : results) {
            if (r == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(addressOf(r.proxy)).append(' ').append(kb(r.speed()));
        }
        System.err.println("[proxy] 内容下载线路：" + label + " " + kb(best.speed())
                + "，直连 " + kb(direct) + "；候选 [" + sb + "]");
        return new Decision(best.proxy, best.speed(), direct, label);
    }

    /** 本机已监听、且能当 HTTP 代理用的候选。 */
    private static List<Proxy> openCandidateProxies() {
        Set<Proxy> out = new LinkedHashSet<>();
        for (int port : CANDIDATE_PORTS) {
            if (portOpen(port)) {
                out.add(new Proxy(Proxy.Type.HTTP,
                        InetSocketAddress.createUnresolved("127.0.0.1", port)));
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), PORT_PROBE_MS);
            return true;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    private static final class Probe {
        final Proxy proxy;
        long bytes;
        long millis;

        Probe(Proxy proxy) {
            this.proxy = proxy;
        }

        long speed() {
            return millis <= 0 ? 0 : bytes * 1000L / millis;
        }
    }

    /** 并发拉同一个 URL 的前 128 KB，量速度。 */
    private static List<Probe> raceProbe(String urlStr, List<Proxy> candidates) {
        List<Probe> results = new ArrayList<>();
        for (Proxy p : candidates) {
            results.add(new Probe(p));
        }
        CountDownLatch latch = new CountDownLatch(results.size());
        for (Probe pr : results) {
            PROBE_POOL.execute(() -> {
                try {
                    measure(urlStr, pr);
                } catch (RuntimeException ignored) {
                    // 单个候选失败不影响其它
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await(PROBE_TIMEOUT_MS + 1200L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        return results;
    }

    private static void measure(String urlStr, Probe pr) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(urlStr);
            conn = (HttpURLConnection) u.openConnection(pr.proxy);
            conn.setConnectTimeout(PROBE_TIMEOUT_MS);
            conn.setReadTimeout(PROBE_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", GameDownloader.AGENT);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Range", "bytes=0-" + (PROBE_BYTES - 1));
            long start = System.currentTimeMillis();
            long deadline = start + PROBE_TIMEOUT_MS;
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return;
            }
            long read = 0;
            AtomicLong lastByteAt = new AtomicLong(start);
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[16 * 1024];
                int n;
                // readTimeout 只在「卡住」时触发，慢而不停的流不会被打断——必须自己带截止时间，
                // 否则 13 KB/s 的直连会把探测拖到 10 秒。
                while (read < PROBE_BYTES && System.currentTimeMillis() < deadline
                        && (n = in.read(buf)) > 0) {
                    read += n;
                    lastByteAt.set(System.currentTimeMillis());
                }
            }
            pr.bytes = read;
            pr.millis = Math.max(1, lastByteAt.get() - start);
        } catch (IOException | RuntimeException ignored) {
            // 该候选不可用
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    private static Proxy parseProxy(String raw) {
        String p = raw.trim().replaceFirst("^(?i)https?://", "").replaceFirst("^(?i)socks5?://", "");
        int slash = p.indexOf('/');
        if (slash >= 0) {
            p = p.substring(0, slash);
        }
        int idx = p.lastIndexOf(':');
        if (idx <= 0 || idx == p.length() - 1) {
            return null;
        }
        String host = p.substring(0, idx);
        int port;
        try {
            port = Integer.parseInt(p.substring(idx + 1).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
        if (port <= 0 || port > 65535) {
            return null;
        }
        return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port));
    }

    private static String addressOf(Proxy p) {
        if (p == null || p.type() == Proxy.Type.DIRECT) {
            return "直连";
        }
        SocketAddress sa = p.address();
        if (sa instanceof InetSocketAddress isa) {
            return isa.getHostString() + ":" + isa.getPort();
        }
        return String.valueOf(sa);
    }

    private static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        try {
            URI u = URI.create(url);
            String h = u.getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
