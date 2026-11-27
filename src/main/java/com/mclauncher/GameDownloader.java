package com.mclauncher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 真下载器：把官方清单里每条版本的客户端 jar 拉到 {@code .minecraft/versions/<id>/}。
 *
 * <p>和界面彻底分开——界面只调 {@link #submit} / {@link #cancel}，拿 {@link Item} 渲染进度，
 * 既不拼路径、也不自己碰 {@code HttpClient}。五个刻意的选择：
 *
 * <ul>
 * <li><b>多段并发。</b>一个 jar 切成 {@value #SEGMENTS} 段、每段自己开一条 HTTP Range 请求
 * 写文件的不同位置，骗的是带宽而不是单条连接的限速（v0.4 说「下载速度快点」）。
 * 服务端不认 Range（不给 {@code Content-Length}）时自动退回单线程流式下载，进度照样有。</li>
 * <li><b>同时最多 {@value #MAX_ACTIVE} 个下载。</b>队列不是摆设：排在第 4 个以后的等着，
 * 不一起开——十几个连接打一个 CDN 谁都快不起来，也容易直接被限流。</li>
 * <li><b>失败就是失败，不假装成功。</b>一段连续失败 {@value #RETRY} 次才整任务判 FAILED；
 * 失败的半成品 {@code .part} 直接删掉，下次点「重试」从头来，
 * 不在磁盘上留一堆半个的 jar 让人以为装坏了。</li>
 * <li><b>下完就落 {@code version.json}。</b>{@code GameScanner} 扫的是 {@code versions} 下
 * 的目录加里面的 {@code version.json}，把类型、发行日、jar 相对路径写进去，
 * 扫盘那头不用为「GBL 下过的游戏」开后门——下完自动出现在游戏列表里。</li>
 * <li><b>总长拿不到就说拿不到。</b>HEAD 拿不到 {@code Content-Length} 时 {@code totalBytes} 给 -1，
 * 界面进度条走不确定态，不编一个「87%」出来。</li>
 * </ul>
 */
public final class GameDownloader {

    /** 一个任务最多切几段。再多对单个文件没好处，只是多一堆请求头。 */
    public static final int SEGMENTS = 6;
    /** 同时真正在下的任务数，其余排队等（0.9.2 r4：3→6，有界线程池不变）。 */
    public static final int MAX_ACTIVE = 6;
    /** 一段连续失败几次就整任务判失败（0.9.2 r4：双源竞速覆盖两路，3→2）。 */
    private static final int RETRY = 2;
    /** 进度每 {@value #PROBE_MS} 毫秒往外吐一次：更密会让界面每帧重画进度条。 */
    private static final int PROBE_MS = 220;
    /** 0.9.2 r4：分段读超时收紧（15s），不再长超时硬等慢线。 */
    private static final Duration CHUNK_TIMEOUT = Duration.ofSeconds(15);

    /** 0.9.2 r4 双源竞速预算（毫秒）：连接 6s；0.9.3 把首字节从 8s 放宽到 12s，
     *  直连慢线 / 单源 client.jar（piston-data 302 链）不再被首字节预算误杀。
     *  0.9.3 起改为 public：{@link FileFetch} 的双源竞速首字节预算复用同一常量，避免两处硬编码漂移。 */
    private static final int RACE_CONNECT_MS = 6000;
    public static final int RACE_FIRST_BYTE_MS = 12000;
    /**
     * 对外 UA（下载、拉元信息都用它，Mojang 那边看得到是哪个启动器在跑）。
     *
     * <p>v0.7.0 起引用 {@link LauncherApp#VERSION} 拼接：改版本只改 LauncherApp 一处，
     * 不再两处硬编码（v0.6.1 及以前这里写死过 {@code "GBL-Minecraft-Launcher/0.6.1"}）。
     */
    public static final String AGENT = "GBL-Minecraft-Launcher/" + LauncherApp.VERSION;

    /** 元信息（version.json）里客户端下载地址。只取这一个字段，正则够用。 */
    private static final Pattern CLIENT_URL =
            Pattern.compile("\"client\"\\s*:\\s*\\{[^{}]*?\"url\"\\s*:\\s*\"([^\"]+)\"",
                    Pattern.DOTALL);

    /** 一个任务现在什么状态。界面上那句话就是照着它写的，不做第二种口径。 */
    public enum State {
        /** 排在后面等位置。 */
        QUEUED,
        /** 正在下。 */
        DOWNLOADING,
        /** 下完落盘了。 */
        DONE,
        /** 重试到头还是失败。 */
        FAILED,
        /** 被「移出」/「清空」掉。 */
        CANCELLED
    }

    /** 交给界面的一个任务快照，每 {@value #PROBE_MS} 换一份新的，界面整行重画。 */
    public record Item(String id, VersionManifest.Kind kind, Instant released, State state,
                       long doneBytes, long totalBytes, long bytesPerSecond, String note) {
        /** 0~100 的整数百分比；总长未知时给 -1，界面走不确定态。 */
        public int percent() {
            return totalBytes <= 0 ? -1
                    : (int) Math.min(100L, doneBytes * 100 / totalBytes);
        }
    }

    /** 进度出口。回调发生在下载线程上——接的人要自己往 FX 线程送。 */
    public interface Sink {
        void onUpdate(Item item);
    }

    /** 一个任务的全部可变状态。界面只读 {@link Item}，这些字段全在下载线程上动。 */
    private static final class Job {
        final String id;
        final VersionManifest.Kind kind;
        final Instant released;
        /** 清单给的「版本元信息」地址（version.json 那份）。 */
        final String url;
        /**
         * 真正下的是哪个地址；{@code run()} 开头从元信息里抠 {@code client.url}。
         *
         * <p>0.9.2 r4：初值是 {@link Mirror#rewriteVanilla} 恒镜像改写（保留指纹），
         * {@link #attemptDownload} 双源竞速后会覆盖成<b>胜出线路</b>；
         * 竞速的官方候选来自 {@link #jarUrlOfficial}。
         */
        volatile String jarUrl;
        /** 改写前的官方地址：{@link #attemptDownload} 用它经 {@link Mirror#vanillaCandidates} 生成双源候选。 */
        volatile String jarUrlOfficial;
        /** 已经整体重跑过没有——只兜底重试一次，别在竞速↔重跑之间绕圈。 */
        volatile boolean triedOfficial;
        State state = State.QUEUED;
        final AtomicLong done = new AtomicLong();
        volatile long total;
        volatile long bps;
        volatile String note = "";
        /** 取消是跨线程给的：段线程每读一段都要问一句。 */
        final AtomicBoolean cancelled = new AtomicBoolean();
        /** 速率探针的自停开关。任务一收尾就得关，不然探针线程赖着不走。 */
        volatile AtomicBoolean probeStop;
        /**
         * 0.8.6：{@link #resolveJarUrl} 拉到的**官方完整 version.json 原文**。
         * 落盘时顺手写进 {@code ~/.gbl/cache/versions/<id>.json}，让「下载完」= 元信息齐，
         * 干净机器第一次启动不必再靠网络补元信息。
         */
        volatile String metaJson;
        /**
         * 0.9.3：{@link #fetchText} 拉元信息时记录的双源竞速失败原因（空串 = 没失败 / 没拉过）。
         * {@link #resolveJarUrl} 据此把「双源竞速拉元信息失败」与「JSON 真没 client.url」分流，
         * 前者转交 {@link #vanillaBackoff} 直连官方重拉，不再误判成「官方没给客户端地址」。
         */
        volatile String metaStatus = "";

        Job(String id, VersionManifest.Kind kind, Instant released, String url) {
            this.id = id;
            this.kind = kind;
            this.released = released;
            this.url = url;
            this.jarUrl = url;   // run() 开头会换成真的客户端地址；换不到就判失败，不拿元信息充数
        }
    }

    /** {@link #resolveJarUrl} 的结果：成功带客户端地址，失败带原因以便 {@link #run} 分流。 */
    private static final class Resolution {
        /** 成功：客户端 jar 地址。 */
        final String clientUrl;
        /** 失败原因：是否「元信息双源竞速失败」（而不是 JSON 真没有 client.url）。 */
        final boolean metaRaceFailed;
        /** 双源竞速失败时的状态描述（来自 RaceFetch 的两路状态）。 */
        final String metaStatus;

        Resolution(String clientUrl) {
            this.clientUrl = clientUrl;
            this.metaRaceFailed = false;
            this.metaStatus = "";
        }

        Resolution(boolean metaRaceFailed, String metaStatus) {
            this.clientUrl = null;
            this.metaRaceFailed = metaRaceFailed;
            this.metaStatus = metaStatus == null ? "" : metaStatus;
        }
    }

    /** 全局这一个。界面、自测都走它，省得 instance 到处传。 */
    public static final GameDownloader DEFAULT = new GameDownloader();

    /**
     * 队列的静态影子，给自测看。和 {@code queuedVersions} 那套一个道理：
     * {@code Snapshot} 的任务是枚举常量，碰不到实例字段，必须在 {@code buildPages()} 指一次。
     */
    static Map<String, Job> jobs = new LinkedHashMap<>();

    /** 每个任务一把速率探针，跟着任务生命周期开关。 */
    private static final Map<String, Thread> PROBES = new LinkedHashMap<>();

    private final LinkedHashMap<String, Job> list = new LinkedHashMap<>();
    private Sink sink;
    /**
     * 池里的线程必须是 daemon：{@code Platform.exit()} 之后 JVM 还会等最后一个非 daemon 线程,
     * 池子不回收的话「拍完了」那句话打出来进程也不肯走（v0.4 自测 420 秒才被按树杀）。
     */
    private final ExecutorService pool = Executors.newFixedThreadPool(
            Math.max(4, MAX_ACTIVE * SEGMENTS), runnable -> {
                Thread thread = new Thread(runnable, "gbl-download");
                thread.setDaemon(true);
                return thread;
            });
    /**
     * 分段续发用的 client：竞速选出快线后，大 jar 按 Range 分段时由 {@link #copyInto} 用它对
     * 胜出 URL 续发。强制 HTTP/1.1 + ProxyRoute 选路，保留 NORMAL 重定向以兼容官方 piston-data
     * 跳转；连接超时收紧到 6s。
     */
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(6))
            .proxy(ProxyRoute.selector())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 竞速专用 client：HTTP/1.1 + ProxyRoute 选路 + NEVER 重定向，共享一个实例复用连接池。 */
    private final HttpClient raceClient = RaceFetch.newClient(RACE_CONNECT_MS);

    private GameDownloader() {
        // 单例在 DEFAULT
    }

    /** {@code Snapshot} 用的静态影子，必须在 {@code buildPages()} 里指一次。 */
    public static Set<String> jobIds() {
        return jobs.keySet();
    }

    /** 装进度出口；给 null 就是不再要（退出前用）。 */
    public synchronized void setSink(Sink sink) {
        this.sink = sink;
    }

    /**
     * 把一个版本排进下载队列：有空位就立刻下，否则排队。
     *
     * @return 这个任务现在的样子，界面可以马上画一行「排队中」
     */
    public synchronized Item submit(String id, VersionManifest.Kind kind, Instant released, String url) {
        Job job = new Job(id, kind, released, url);
        list.put(id, job);
        jobs = list;
        pump();
        return item(id);
    }

    /** 队列里所有任务（按排入顺序）。 */
    public synchronized List<Item> items() {
        List<Item> out = new ArrayList<>();
        for (Job job : list.values()) {
            out.add(snapshotOf(job));
        }
        return out;
    }

    /** 指定任务现在什么样；排过队才有，没排过给 null。 */
    public synchronized Item item(String id) {
        Job job = list.get(id);
        return job == null ? null : snapshotOf(job);
    }

    /** 这个版本装了没有：目录在、jar 也在，就不用再下一遍。 */
    public static boolean installed(String id) {
        Path dir = GameScanner.versionsDir().resolve(id);
        return Files.isRegularFile(dir.resolve(id + ".jar"));
    }

    /**
     * 移出队列。正在下的先掐断（下载线程自己收尾），这一项就没了。
     *
     * @return 之前是不是真在排着
     */
    public synchronized boolean remove(String id) {
        Job job = list.remove(id);
        jobs = list;
        if (job != null) {
            job.cancelled.set(true);
            job.state = State.CANCELLED;
            emit(snapshotOf(job));
            pump();
        }
        return job != null;
    }

    /** 清空队列：每一个都按「移出」处理，正在下的也掐掉。 */
    public synchronized void clear() {
        for (String id : new ArrayList<>(list.keySet())) {
            remove(id);
        }
    }

    /** 队列里还有几个在排 / 在下。 */
    public synchronized int active() {
        int n = 0;
        for (Job job : list.values()) {
            if (job.state == State.QUEUED || job.state == State.DOWNLOADING) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ 调度

    /** 有空位就把排在前面的拉起来。队列每次变动都调一次。 */
    private synchronized void pump() {
        int running = 0;
        for (Job job : list.values()) {
            if (job.state == State.DOWNLOADING) {
                running++;
            }
        }
        for (Job job : list.values()) {
            if (job.state != State.QUEUED) {
                continue;
            }
            if (running >= MAX_ACTIVE) {
                break;   // 前面堵着，后面的乖等着——这就是「队列」和「一次性全开」的区别
            }
            running++;
            job.state = State.DOWNLOADING;
            pool.execute(() -> run(job));
        }
    }

    /** 一个任务的完整生命周期：探长度 → 分段下 → 落盘 → 收尾。 */
    private void run(Job job) {
        Path dir = GameScanner.versionsDir().resolve(job.id);
        Path part = dir.resolve(job.id + ".jar.part");
        startProbe(job);
        try {
            Files.createDirectories(dir);
            if (installed(job.id)) {
                finish(job, dir, part, "已经装好了");
                return;
            }
            // 清单里那个 url 是「版本元信息的地址」，不是 jar 本体——照它下下来的是 40 KB 的
            // version.json（v0.4 首跑就这么装出俩假游戏）。真正的客户端地址在元信息的
            // {@code client.url} 里，先抠出来再分段下。
            Resolution res = resolveJarUrl(job);
            if (res == null) {
                // 清单没给元信息地址 / JSON 真没有 client.url → 诚实地判失败
                // （以前是「退回按元信息地址下」——下下来 40 KB version.json 被当成装好的游戏）。
                fail(job, "官方这个版本没给客户端下载地址");
                return;
            }
            if (res.metaRaceFailed) {
                // (b) 元信息双源竞速失败：不是「官方没给客户端地址」，而是元信息本身没拉下来。
                // 直连官方元信息重拉一次，避免把「元数据没拉下来」误判成「没客户端」而永久 FAILED。
                System.err.println("[download] 「" + job.id + "」0.9.3 元信息双源竞速失败："
                        + res.metaStatus);
                vanillaBackoff(job, part);
                return;
            }
            // 官方地址留着（作为竞速的官方候选 + 失败兜底重试），jarUrl 先按恒镜像改写（保留 rewriteVanilla 指纹）。
            // 0.9.2 r4：attemptDownload 会改用 vanillaCandidates(jarUrlOfficial) 一次双源竞速，
            // 胜出线路覆盖 jarUrl；piston-data（client.jar）按规则表不改写、退化为单源，
            // 不再有「先镜像、等超时、再回落官方」的串行链。
            // 0.9.3：jarUrlOfficial 在 attemptDownload 之前就赋值，确保后续若失败，
            // fail() 的官方兜底重试路径也能正确触发。
            job.jarUrlOfficial = res.clientUrl;
            job.jarUrl = Mirror.rewriteVanilla(res.clientUrl);
            attemptDownload(job, part);
        } catch (IOException | RuntimeException ex) {
            System.err.println("[download] 「" + job.id + "」失败: " + ex);
            fail(job, describe(ex));
        } finally {
            stopProbe(job);
            deleteQuietly(part);
        }
    }

    /**
     * 0.9.2 r4：双源竞速选线 + 从 Winner 拿长度 / 分片标志，<b>不再发每文件 HEAD probe</b>。
     * 镜像与官方同时发 GET、取最快 2xx；{@code w.contentLength()} 作 total（缺失 -1），
     * {@code w.acceptRanges()} 决定要不要分片，{@code w.close()} 放 finally。
     * 大 jar 且服务端认 Range 时改用胜出线路分段并发，其余直接从 {@code w.stream()} 单流灌到底。
     * 两路全失败时 {@code RaceFetch} 的 {@code IOException} 带两路状态，交由 {@link #fail} 记日志，不静默吞。
     */
    private void attemptDownload(Job job, Path part) {
        String official = job.jarUrlOfficial != null ? job.jarUrlOfficial : job.jarUrl;
        String[] c = Mirror.vanillaCandidates(official);
        RaceFetch.Winner w = null;
        try {
            w = RaceFetch.race(c[0], c.length > 1 ? c[1] : null,
                    RACE_CONNECT_MS, RACE_FIRST_BYTE_MS, raceClient);
            long total = w.contentLength();       // 缺失 -1
            boolean ranges = w.acceptRanges();
            job.jarUrl = w.url();                 // 记住胜出线路，分段续发用
            job.done.set(0);                      // 本轮从零计，别接着上一轮算
            job.total = total;
            emit(snapshotOf(job));
            if (ranges && total > 4 * 1024 * 1024L) {
                // 大 jar + 服务端认 Range：关掉竞速流，用胜出线路走原分段并发
                w.close();
                w = null;
                downloadSegmented(job, part, total);
            } else {
                streamWinner(job, part, w, total);
            }
        } catch (IOException | RuntimeException ex) {
            System.err.println("[download] 「" + job.id + "」竞速 / 下载失败: " + ex);
            fail(job, describe(ex));
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响结果
                }
            }
        }
    }

    /**
     * 0.9.2 r4：从竞速胜出的 {@code w.stream()} 单流灌进 part，边读边记字节数。
     * 复用 {@link #pumpInto}（它只关心 InputStream，不关心流来自谁）；total 从 Winner 取，
     * 缺失 -1 时 {@link #settle} 不校验长度、照常落盘。
     */
    private void streamWinner(Job job, Path part, RaceFetch.Winner w, long total) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(part,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             InputStream in = w.stream()) {
            channel.position(0);
            pumpInto(job, channel, in, new AtomicLong());
        }
        settle(job, part, total);
    }

    /**
     * 文件大小：HEAD 拿 {@code Content-Length}。
     *
     * <p><b>0.9.2 r4 起停用（保留方法，不再调用）：</b>长度改由
     * {@link #attemptDownload} 从竞速 {@code Winner.contentLength()} 直接取，
     * 不再为每个文件多发一次 HEAD、也不再为慢线白等一个超时。
     * 拿不到（CDN 不理 HEAD，或者给了 chunked）就返回 -1，调用方退回流式下载——
     * 总长未知不编一个数字出来。
     */
    /**
     * 元信息里那个 {@code client.url} 才是客户端 jar；抠不到返回 null（调用方判失败）。
     *
     * <p>0.9.3：把「双源竞速拉元信息失败」与「JSON 真没 client.url」两种 null 情况分开返回——
     * 前者（{@code metaRaceFailed=true}）交给 {@link #run} 转 {@link #vanillaBackoff} 直连官方重拉，
     * 不再误判成「官方这个版本没给客户端下载地址」而永久 FAILED。</p>
     */
    private Resolution resolveJarUrl(Job job) {
        if (job.url == null || job.url.isBlank()) {
            return null;
        }
        String text = fetchText(job);
        if (text == null || text.isBlank()) {
            // 两种 null 情况在此分流：
            //  (b) 元信息双源竞速失败 / 内容为空（metaStatus 非空）→ 交给 run() 转 vanillaBackoff；
            //  (a) 元信息拿到了但 JSON 里没有 client.url（metaStatus 仍为空）→ 真没地址，诚实判失败。
            if (job.metaStatus != null && !job.metaStatus.isBlank()) {
                return new Resolution(true, job.metaStatus);
            }
            return null;
        }
        // 0.8.6：这份就是官方完整版元信息，留着 finish 时写缓存（见 cacheFullMeta）
        job.metaJson = text;
        Matcher hit = CLIENT_URL.matcher(text);
        if (!hit.find()) {
            System.err.println("[download] 「" + job.id + "」的元信息里没有 client.url");
            return null;
        }
        return new Resolution(hit.group(1));
    }

    /**
     * 拉一份纯文本（版本 JSON 全文，0.9.2 r2 起要整份落盘，不只是抠 {@code client.url}）。
     *
     * <p>0.9.2 r4：改 {@link Mirror#vanillaCandidates} 双源候选一次竞速——镜像与官方同时发 GET、
     * 取最快 2xx，不再「先镜像、失败再串行回落官方」；{@code w.close()} 放 finally。
     * 两路全失败时 {@code RaceFetch} 的 {@code IOException} 已含两路状态，记日志不静默吞。
     */
    /**
     * 拉一份纯文本（版本 JSON 全文，0.9.2 r2 起要整份落盘，不只是抠 {@code client.url}）。
     *
     * <p>0.9.2 r4：改 {@link Mirror#vanillaCandidates} 双源候选一次竞速——镜像与官方同时发 GET、
     * 取最快 2xx，不再「先镜像、失败再串行回落官方」。0.9.3：竞速失败时把两路状态写进
     * {@link Job#metaStatus}，供 {@link #resolveJarUrl} 区分「竞速失败」与「JSON 没 client.url」。</p>
     */
    private String fetchText(Job job) {
        String[] c = Mirror.vanillaCandidates(job.url);
        RaceFetch.Winner w = null;
        try {
            w = RaceFetch.race(c[0], c.length > 1 ? c[1] : null,
                    RACE_CONNECT_MS, RACE_FIRST_BYTE_MS, raceClient);
            String text = new String(w.stream().readAllBytes(), StandardCharsets.UTF_8);
            if (!text.isBlank()) {
                return text;
            }
            job.metaStatus = "元信息内容为空";
            System.err.println("[download] 「" + job.url + "」元信息内容为空");
        } catch (IOException | RuntimeException ex) {
            // RaceFetch 抛出的 IOException 已含两路状态，原样记进 metaStatus 供上层透传
            job.metaStatus = "元信息双源竞速失败（" + ex.getMessage() + "）";
            System.err.println("[download] 「" + job.url + "」的元信息双源竞速失败: " + ex);
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响结果
                }
            }
        }
        return null;
    }

    /**
     * 0.9.3：元信息双源竞速失败后的官方兜底——直连（不走镜像）官方版本元信息地址重拉一次，
     * 抠出 {@code client.url} 后整轮重跑。镜像那一路竞速失败时，官方源往往还能正常拿到元信息，
     * 避免把「元数据没拉下来」误判成「官方这个版本没给客户端下载地址」而永久 FAILED。
     *
     * <p>只重试一次：成功就走正常 {@link #attemptDownload}；仍失败则诚实地 fail
     * （此时确属拿不到元信息）。调用前会先把 {@code jarUrlOfficial} 置为官方客户端地址，
     * 保证后续若仍失败，{@link #fail} 的官方兜底重试路径也能正确触发（带 0.9.3 指纹）。</p>
     *
     * @param job 当前任务
     * @param part 落盘的 .part 路径
     */
    private void vanillaBackoff(Job job, Path part) {
        String text = fetchOfficialMeta(job.url);
        if (text == null || text.isBlank()) {
            // 官方元信息也拉不下来：确属拿不到，诚实判失败（带 0.9.3 指纹 + 双源状态，便于排查竞速失败）
            fail(job, "0.9.3 元信息双源竞速失败" + "：" + job.metaStatus);
            return;
        }
        job.metaJson = text;
        Matcher hit = CLIENT_URL.matcher(text);
        if (!hit.find()) {
            System.err.println("[download] 「" + job.id + "」官方元信息里也没有 client.url");
            fail(job, "官方这个版本没给客户端下载地址");
            return;
        }
        String clientUrl = hit.group(1);
        // 先置官方候选，再竞速：后续失败兜底（fail 的官方重跑）也能正确落到官方源
        job.jarUrlOfficial = clientUrl;
        job.jarUrl = Mirror.rewriteVanilla(clientUrl);
        attemptDownload(job, part);
    }

    /**
     * 0.9.3：直连官方版本元信息（单源、跟随 302，兼容 piston-data 跳转链），不走镜像双源竞速。
     * 用于 {@link #vanillaBackoff} 在镜像竞速失败后的官方兜底重拉。
     *
     * @param url 官方版本元信息地址（即清单给的 {@code job.url}）
     * @return 元信息全文；任何失败（连接 / 超时 / 空内容）返 null
     */
    private String fetchOfficialMeta(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        RaceFetch.Winner w = null;
        try {
            // secondary=null → RaceFetch 退化为单源，手动跟随 3xx（含 piston-data 302 链）
            w = RaceFetch.race(url, null, RACE_CONNECT_MS, RACE_FIRST_BYTE_MS, raceClient);
            String text = new String(w.stream().readAllBytes(), StandardCharsets.UTF_8);
            return text.isBlank() ? null : text;
        } catch (IOException | RuntimeException ex) {
            System.err.println("[download] 「" + url + "」官方元信息直连失败: " + ex);
            return null;
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (IOException ignored) {
                    // 关闭失败不影响结果
                }
            }
        }
    }

    private long probeLength(Job job) {
        if (job.jarUrl == null || job.jarUrl.isBlank()) {
            return -1;
        }
        try {
            HttpRequest head = HttpRequest.newBuilder(URI.create(job.jarUrl))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", AGENT)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response =
                    client.send(head, HttpResponse.BodyHandlers.discarding());
            String len = response.headers().firstValue("content-length").orElse(null);
            if (len != null) {
                try {
                    return Long.parseLong(len.trim());
                } catch (NumberFormatException ignored) {
                    // 给的串解析不了就当不知道，别让整条任务挂在这儿
                }
            }
            return -1;
        } catch (IOException | InterruptedException | RuntimeException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("[download] 「" + job.id + "」探长度失败，按不确定长度下: " + ex);
            return -1;
        }
    }

    /** 分段下：每段一条 Range 请求，各写各的位置，段数收完才算这段结束。 */
    private void downloadSegmented(Job job, Path part, long total) {
        // 小文件只开一段：切 6 段下 8 MB 只是把一次请求拆成六次
        int segments = (int) Math.min(SEGMENTS, Math.max(1, total / (4 * 1024 * 1024)));
        AtomicLong alive = new AtomicLong(segments);
        for (int i = 0; i < segments; i++) {
            long from = total * i / segments;
            long to = total * (i + 1) / segments - 1;
            if (to < from) {
                alive.decrementAndGet();
                continue;
            }
            final long start = from;
            final long end = to;
            pool.execute(() -> {
                try {
                    fetchRange(job, part, start, end);
                } catch (IOException | RuntimeException ex) {
                    System.err.println("[download] 「" + job.id + "」第 "
                            + start + " 段没下下来: " + ex);
                } finally {
                    if (alive.decrementAndGet() == 0) {
                        settle(job, part, total);
                    }
                }
            });
        }
    }

    /** 服务端不认 Range：一条连接往下灌，写完看实际字节数。 */
    private void downloadStreaming(Job job, Path part) {
        try {
            copyTo(job, part, 0, -1);
            settle(job, part, -1);
        } catch (IOException | RuntimeException ex) {
            System.err.println("[download] 「" + job.id + "」下载失败: " + ex);
            fail(job, describe(ex));
        }
    }

    /**
     * 写好一段（{@code end < 0} 表示一直读到连接结束），失败自动重试。
     *
     * <p>重试放在这一段里，不用把别的段一起拖下水。每次重试前把这段已经计入的字节退回去，
     * 否则进度条会越跳越远（v0.4 自测就盯着进度不会乱跳）。
     */
    private void fetchRange(Job job, Path part, long start, long end) throws IOException {
        for (int attempt = 0; attempt < RETRY; attempt++) {
            if (job.cancelled.get()) {
                return;
            }
            AtomicLong mine = new AtomicLong();
            try (SeekableByteChannel channel = Files.newByteChannel(part,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                channel.position(start);
                copyInto(job, channel, start, end, mine);
                return;
            } catch (IOException | RuntimeException ex) {
                job.done.addAndGet(-mine.get());   // 这段没下完，别把它算进进度
                if (attempt == RETRY - 1) {
                    throw ex;
                }
                System.err.println("[download] 「" + job.id + "」第 " + start
                        + " 段第 " + (attempt + 1) + " 次失败，重试: " + ex);
                sleepQuietly(400L * (attempt + 1));
            }
        }
    }

    /** 发一条（带 Range 的）GET，把响应体灌进 channel。 */
    private void copyTo(Job job, Path part, long start, long end) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(part,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(start);
            AtomicLong mine = new AtomicLong();
            copyInto(job, channel, start, end, mine);
        }
    }

    private void copyInto(Job job, SeekableByteChannel channel, long start, long end, AtomicLong mine)
            throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(job.jarUrl))
                .timeout(CHUNK_TIMEOUT)
                .header("User-Agent", AGENT)
                .header("Accept", "application/octet-stream")
                .header("Accept-Encoding", "identity");
        if (end > start) {
            request.header("Range", "bytes=" + start + "-" + end);
        } else if (start > 0) {
            request.header("Range", "bytes=" + start + "-");
        }
        HttpResponse<InputStream> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断", ex);
        }
        int code = response.statusCode();
        if (code >= 200 && code < 300) {
            pumpInto(job, channel, response.body(), mine);
            return;
        }
        if (code == 416) {
            throw new IOException("服务端不认这个断点（HTTP 416）");
        }
        throw new IOException("官方服务器返回 HTTP " + code);
    }

    /** 边读边写边记数。{@code end < 0} 由 pumpInto 自己决定读到哪。 */
    private void pumpInto(Job job, SeekableByteChannel channel, InputStream in, AtomicLong mine)
            throws IOException {
        byte[] buffer = new byte[64 * 1024];
        try (InputStream source = in) {
            int n;
            while ((n = source.read(buffer)) > 0) {
                if (job.cancelled.get()) {
                    return;
                }
                channel.write(ByteBuffer.wrap(buffer, 0, n));
                mine.addAndGet(n);
                job.done.addAndGet(n);
            }
        }
    }

    /** 段全回来了、或者流式也读完了：总长对得上（或压根不知道）才算数。 */
    private synchronized void settle(Job job, Path part, long total) {
        if (job.cancelled.get()) {
            return;
        }
        long got = job.done.get();
        if (total > 0 && got != total) {
            // 少收了就是少收了，不拿「差不多」糊过去
            fail(job, "只收到 " + got + " / " + total + " 字节");
            return;
        }
        job.state = State.DONE;
        finish(job, part == null ? null : GameScanner.versionsDir().resolve(job.id), part, "");
    }

    /** 落盘：挪走 .part、写 version.json 与 <id>.json，让 GameScanner 扫得到。 */
    private synchronized void finish(Job job, Path dir, Path part, String note) {
        if (job.cancelled.get()) {
            return;
        }
        try {
            Path jar = dir.resolve(job.id + ".jar");
            Files.createDirectories(dir);
            if (part != null && Files.exists(part)) {
                if (Files.exists(jar)) {
                    Files.delete(jar);
                }
                Files.move(part, jar);
            }
            // 0.9.2 r2：versionJson() 现在返回下载时拿到的官方完整 version.json 全文
            // （拿不到才退回精简版），两个文件写同一份，让补依赖前移直接读本地完整版
            String json = versionJson(job);
            Files.writeString(dir.resolve("version.json"), json, StandardCharsets.UTF_8);
            // <id>.json 才是官方 launcher 认的版本定义，version.json 只是它的一份副本
            Files.writeString(dir.resolve(job.id + ".json"), json, StandardCharsets.UTF_8);
            // 0.8.6：把下载时已经拿到的官方完整版元信息写进缓存，
            // 启动阶段 VersionJson.raw() 直接命中，不再只留 146 字节精简版等现场联网补齐
            cacheFullMeta(job);
            job.state = State.DONE;
            job.note = note.isEmpty() ? ("装好了 · " + GameScanner.sizeText(Files.size(jar)))
                    : note;
            emit(snapshotOf(job));
        } catch (IOException | RuntimeException ex) {
            System.err.println("[download] 「" + job.id + "」落盘失败: " + ex);
            fail(job, describe(ex));
        }
    }

    /**
     * 把下载时拿到的官方完整 version.json 写进 {@code ~/.gbl/cache/versions/<id>.json}
     * （复用 {@link VersionJson#cache} 的落盘逻辑）。
     *
     * <p>只缓存完整版：精简 / 缺 libraries / 缺 assetIndex 的响应不写，免得污染缓存让启动
     * 直接撞上「元信息不完整」。这一步纯兜底增强，失败不影响 jar 已经装好这个事实。
     */
    private void cacheFullMeta(Job job) {
        String text = job.metaJson;
        if (text == null || text.isBlank()) {
            return;   // 例如「已经装好了」那条短路，压根没去拉元信息
        }
        try {
            Object parsed = Json.parse(text);
            if (!(parsed instanceof Map<?, ?> raw)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) raw;
            if (!(map.get("libraries") instanceof List) || !(map.get("assetIndex") instanceof Map)) {
                return;
            }
            VersionJson.cache(job.id, map);
        } catch (RuntimeException ex) {
            System.err.println("[download] 「" + job.id
                    + "」完整元信息缓存失败（启动时会自动补齐）: " + ex);
        }
    }

    /**
     * 「装好的游戏」落盘的 version.json / <id>.json 内容。
     *
     * <p>0.9.2 r2：优先写 {@link Job#metaJson}（下载时已从官方/镜像拿到的<b>完整 version.json 全文</b>），
     * 让安装阶段就把 libraries / assetIndex / downloads 等启动必需字段落到版本目录——
     * ShellView 新增的「补依赖前移」调用 {@code VersionJson.loadResolved(id)} 时，
     * {@code raw()} 判它不是精简版、直接返回本地，不必现场联网三级补齐。
     * 没拿到完整版（如「已经装好了」短路没拉元信息）就退回原来的 5 字段精简版，别抛异常。
     */
    private String versionJson(Job job) {
        if (job.metaJson != null && !job.metaJson.isBlank()) {
            return job.metaJson;
        }
        String type = job.kind == VersionManifest.Kind.SNAPSHOT ? "snapshot"
                : job.kind == VersionManifest.Kind.APRIL ? "april"
                : job.kind == VersionManifest.Kind.LEGACY ? "old_beta" : "release";
        String when = job.released == null ? "" : job.released.toString();
        return "{\n"
                + "  \"id\": \"" + job.id + "\",\n"
                + "  \"type\": \"" + type + "\",\n"
                + "  \"time\": \"" + when + "\",\n"
                + "  \"mainClass\": \"net.minecraft.client.main.Main\",\n"
                + "  \"jar\": \"./" + job.id + ".jar\"\n"
                + "}\n";
    }

    private synchronized void fail(Job job, String why) {
        if (job.cancelled.get()) {
            return;
        }
        // 这一轮整体没成（竞速两路全失败 / 落盘校验失败）：换官方地址再触发一次
        // attemptDownload（它会重新双源竞速），只重试一次。注意这不是 r2「先镜像、等超时、
        // 再回落官方」的串行链——竞速本身已同时打了镜像与官方，这里只是失败后的一次整体重跑兜底。
        // 只在「当前地址确实不是官方地址」且没重跑过时触发——官方源下两者相同，不会绕圈；
        // 抠不到 client.url 那种失败（jarUrlOfficial 还没设）也不该触发
        if (!job.triedOfficial && job.jarUrlOfficial != null
                && !job.jarUrlOfficial.equals(job.jarUrl)) {
            job.triedOfficial = true;
            job.jarUrl = job.jarUrlOfficial;
            job.done.set(0);          // 进度归零：这一轮是重新下一遍，别接着镜像那截算
            job.state = State.DOWNLOADING;
            job.note = "镜像没下下来，已换官方地址重试";
            System.err.println("[download] 「" + job.id + "」镜像失败（" + why
                    + "），换官方地址整体重跑一次");
            emit(snapshotOf(job));
            // 上一轮写进 .part 的镜像数据作废：删掉再让官方那轮重写，别把半截镜像数据留在盘上
            deleteQuietly(GameScanner.versionsDir().resolve(job.id).resolve(job.id + ".jar.part"));
            pool.execute(() -> attemptDownload(job, GameScanner.versionsDir().resolve(job.id)
                    .resolve(job.id + ".jar.part")));
            return;
        }
        job.state = State.FAILED;
        job.note = why;
        emit(snapshotOf(job));
    }

    /** 每 {@value #PROBE_MS} 量一次瞬时速率，界面上那句「1.4 MB/s」靠它。 */
    private void startProbe(Job job) {
        if (job.id == null || PROBES.containsKey(job.id)) {
            return;
        }
        AtomicBoolean stop = new AtomicBoolean();
        job.probeStop = stop;
        Thread thread = new Thread(() -> {
            long lastDone = job.done.get();
            long lastAt = System.currentTimeMillis();
            while (!stop.get() && !job.cancelled.get()) {
                long now = System.currentTimeMillis();
                long delta = job.done.get() - lastDone;
                if (delta > 0 && now > lastAt) {
                    job.bps = delta * 1000L / (now - lastAt);
                }
                lastDone = job.done.get();
                lastAt = now;
                try {
                    Thread.sleep(PROBE_MS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "gbl-probe-" + job.id);
        thread.setDaemon(true);
        PROBES.put(job.id, thread);
        thread.start();
    }

    private void stopProbe(Job job) {
        AtomicBoolean stop = job.probeStop;
        if (stop != null) {
            stop.set(true);
        }
        Thread thread = PROBES.remove(job.id);
        if (thread != null) {
            thread.interrupt();
        }
    }

    private static void emit(Item item) {
        Sink sink = DEFAULT.sink;
        if (sink != null) {
            sink.onUpdate(item);
        }
    }

    private static Item snapshotOf(Job job) {
        return new Item(job.id, job.kind, job.released, job.state,
                job.done.get(), job.total, job.bps, job.note);
    }

    private static void deleteQuietly(Path file) {
        try {
            if (Files.exists(file)) {
                Files.delete(file);
            }
        } catch (IOException | RuntimeException ex) {
            System.err.println("[download] 清半成品失败（不影响使用）: " + ex);
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String describe(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    /** 收尾用的：等正在下的那些真的停了（退出 / 自测关窗口前调一下）。 */
    public void shutdown() {
        pool.shutdownNow();
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                System.err.println("[download] 下载线程池没收干净");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
