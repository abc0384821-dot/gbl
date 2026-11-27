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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 补下载一个版本**缺的依赖库**（v0.4.9）。
 *
 * <p>为什么需要它：{@link GameDownloader} 只会按官方清单下「客户端 jar」，而磁盘上现成的老版本
 * （HMCL / 官方 launcher 装的 1.20.1 这类）往往只夹了一个客户端 jar，
 * {@code .minecraft/libraries} 里一个依赖都没有——补齐就是 61 个 guava/netty/lwjgl……
 * 以前 prepare 只会甩一句「缺 61 个依赖库，去补下载」，可启动器自己压根没这个能力，
 * 用户读完只能自己去浏览器找。这里把「缺 → 下 → 落盘」这条链补上，
 * 非官方装的游戏才真的开得起来（Forge / Fabric 同样适用：它们的库条目也带 {@code url}）。
 *
 * <p>刻意做窄：只认 {@code version.json} 的 {@code libraries} 段里那几个字段
 * （{@code downloads.artifact.{path,url,sha1,size}}），不铺开一套 Maven 解析器。
 *
 * <ul>
 * <li>**缺什么先列清楚再动手**：列表一次算完，界面第一句就能说「要补 61 个」，
 *      「第 12/61」这种进度也才报得准。</li>
 * <li>**本机那份 natives 一起补**：老格式（1.20.1 这类）的 natives 是独立的
 *      {@code lwjgl-*-natives-windows.jar}，不进 classpath、也不在 natives 目录里，
 *      少一颗就卡在「Failed to locate library: lwjgl.dll」。</li>
 * <li>**下完验 sha1**：Mojang 每个库都带 sha1，白送的一道校验；对不上就当没下，
 *      绝不把半个文件塞进 classpath。</li>
 * <li>**一个失败不拖垮别人**：库之间互不依赖，全停了反而分不清是网络还是地址坏了。</li>
 * <li>**双源竞速**（0.9.2 r4）：按 {@link Mirror#vanillaCandidates} 拿到镜像 + 官方两路，
 *      {@code RaceFetch.race} 同时发 GET、取最快 2xx，慢线当场淘汰；不再走 r2「先镜像、
 *      等超时、再回落官方」的串行链（那样每个慢库白等一个超时，反而把整批拖慢）。</li>
 * <li>**中央仓优先、镜像只兜底**（0.9.9 r4）：镜像有 10 次/60 秒/IP 的硬限流，
 *      补 53 个库根本跑不完；阿里云 Maven 中央仓不限流且路径就是 {@code artifact.path}，
 *      绝大多数库一次就成。只有 {@code com/mojang/*} 走镜像（中央仓没有这些构件），
 *      镜像永远留在最后一组兜底，见 {@link #sourceGroups}。</li>
 * </ul>
 */
public final class LibFetcher {

    /**
     * 同时下载几个库（0.9.2 r4：8→32；0.9.9 r4：32→12）。
     *
     * <p>32 路在弱网下是负收益：连接互相饿死，每一路都只剩几十 KB/s，整批反而更慢。
     * 更要紧的是镜像额度——{@code com/mojang/*} 这类中央仓没有的构件只能走镜像，而镜像是
     * <b>10 次/60 秒/IP</b>，32 路齐发一瞬间就打穿，换回来的是整分钟 429，
     * 把本来能下的库一起卡死。12 路既能吃满带宽，也不会一把烧掉额度。
     */
    private static final int PARALLEL = 12;
    /** 单个库最多试几次（0.9.2 r4：3→2；0.9.9 r4：2→3，换源后多给一次的成本很低）。 */
    private static final int RETRY = 3;
    /** 对外进度节流：每 {@value} 毫秒最多报一次。 */
    private static final long THROTTLE_MS = 150L;
    /** 0.9.2 r4：分段 / 连接超时收紧（15s / 6s），不再长超时硬等慢线。 */
    private static final Duration CHUNK_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(6);
    /** 大于这个长度才切段，不然切 6 段下 3 MB 只是把一次请求拆成六次。 */
    private static final long SEGMENT_MIN = 8 * 1024 * 1024L;

    /**
     * 0.9.2 r4 双源竞速预算（毫秒）：连接 6s / 首字节 15s，慢线当场淘汰。
     *
     * <p>首字节 8s→15s（0.9.9 r4）：8 秒对慢网太紧，会把本来能成的一路判死；
     * 现在第一组是阿里云中央仓（实测 2.5MB/s 量级），这 15 秒实际只对兜底组生效，
     * 留着宽一点换「兜底真的能兜住」。
     */
    private static final int RACE_CONNECT_MS = 6000;
    private static final int RACE_FIRST_BYTE_MS = 15000;

    /**
     * 阿里云 Maven 中央仓：国内量大、<b>不限流</b>。
     *
     * <p>为什么能直接拼：MC 的库本来就是 Maven 坐标，{@code downloads.artifact.path}
     * （如 {@code com/google/code/gson/gson/2.10/gson-2.10.jar}）就是中央仓的相对路径，
     * 一个字符都不用改写。实测 2026-11-18：gson / lwjgl natives / fastutil（23MB）全部 200，
     * 2.5MB/s；而 BMCLAPI 镜像只有 10 次/60 秒/IP，补 53 个库光拿地址就要 5 分钟以上。
     *
     * <p>唯一不覆盖的是 {@code com/mojang/*}（text2speech、brigadier 实测 404）——
     * 那是 Mojang 自家构件，不在中央仓里，仍要靠镜像，见 {@link #sourceGroups}。
     */
    private static final String MAVEN_PUBLIC = "https://maven.aliyun.com/repository/public/";

    /** 竞速专用 client：HTTP/1.1 + ProxyRoute 选路 + NEVER 重定向，共享一个实例复用连接池。 */
    private static final HttpClient RACE_CLIENT = RaceFetch.newClient(RACE_CONNECT_MS);

    /**
     * 一个要补的库；{@code nativeJar} 那几个是 natives 包（不进 classpath）。
     *
     * <p>{@code originUrl} 是<strong>官方原址</strong>，{@code url} 是
     * {@link Mirror#rewriteVanilla} 改写后的镜像地址（保留供兼容 / 指纹）。0.9.2 r4 起
     * {@link #downloadOne} 用 {@link Mirror#vanillaCandidates}({@code originUrl}) 生成
     * 镜像 + 官方双源竞速，不再依赖 {@code url} 做串行回落。
     */
    public record Lib(String path, String url, String sha1, long size, boolean nativeJar,
                      String originUrl) {

        /** 兜底：没给官方地址就拿当前地址顶上——竞速两路相同会退化单源，但绝不会 NPE。 */
        public Lib {
            if (originUrl == null || originUrl.isBlank()) {
                originUrl = url;
            }
        }

        /** 给人看的名字：最后一截文件名。 */
        public String name() {
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    /** 补下载的进度/结果。回调都发生在下载线程上，接的人要自己往 FX 线程送。 */
    public interface Listener {
        default void onStart(int total) {
        }

        /** 每个库落盘（成功或失败都来一次）。 */
        default void onSettled(Lib lib, boolean ok, String note) {
        }

        /** 整体进度：已下完几个、下了多少字节、平均速率。 */
        default void onProgress(int done, int total, long doneBytes, long bytesPerSecond) {
        }
    }

    /**
     * 分段续发用的 client：竞速选出快线后，大库按 Range 分段时由 {@link #copyInto} 用它对
     * 胜出 URL 续发。强制 HTTP/1.1 + ProxyRoute 选路，保留 NORMAL 重定向以兼容官方 piston-data
     * 跳转；连接超时已收紧到 6s。单源 / 单流主路径走 {@link #RACE_CLIENT} 竞速。
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .proxy(ProxyRoute.selector())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 跑「一个库」的池。
     *
     * <p>{@link #SEGMENT_POOL} 必须和它**分开**：61 个库任务占满这个池以后，
     * 每个库又要把自己的分段任务提交进来，排在同一个队列尾部就永远轮不到——
     * 一池两用看着省线程，实际是让补库僵在那儿（v0.4.9 原样踩过，7 分钟一个库没下来）。
     */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(PARALLEL, runnable -> {
        Thread thread = new Thread(runnable, "gbl-lib");
        thread.setDaemon(true);   // 补库线程绝不能拦住 JVM 退出
        return thread;
    });

    /** 跑「一个大库的一段」的池。 */
    private static final ExecutorService SEGMENT_POOL =
            Executors.newFixedThreadPool(PARALLEL, runnable -> {
                Thread thread = new Thread(runnable, "gbl-lib-seg");
                thread.setDaemon(true);
                return thread;
            });

    private LibFetcher() {
        // 静态工具类
    }

    /** {@code .minecraft/libraries}；和 GameLauncher 指的是同一个目录，别各指一处。 */
    public static Path libDir() {
        return Platform.minecraftDir().resolve("libraries");
    }

    /** jar 里的实现版本：文件 → 版本串。同一份文件只读一次，prepare 每次启动都要问。 */
    private static final Map<Path, String> VERSION_CACHE = new java.util.HashMap<>();

    /**
     * 库文件在，但**装的是别的版本**吗？
     *
     * <p>判据读 jar 里的 {@code Manifest: Implementation-Version}，跟 path 里的版本段比。
     * 读不到版本（老 jar 常常没这一项）就放行——宁可漏判一次，也别把每次启动都变成重装。
     *
     * <p>为什么必须管：同一个 {@code libraries} 目录会被不同版本共用（26.1.2 装了
     * jtracy 1.0.37，26.4-snapshot-2 只要 1.14.38 里的
     * {@code TracyClient.createSectionCategory(String)}）。只认「文件在不在」的话，
     * 旧 jar 一直杵在那儿，新游戏加载的是旧类，崩在 {@code NoSuchMethodError}——
     * 界面看着就是「点了启动以后一直黑屏」，谁也想不到是库版本的事（v0.5 用户报的那个「卡住」）。
     */
    private static boolean staleVersion(Path file) {
        String have = jarVersion(file);
        String want = versionInPath(file.toString());
        return have != null && want != null && !have.equals(want);
    }

    /** {@code com/mojang/jtracy/1.14.38/jtracy-1.14.38.jar} → {@code 1.14.38}；认不出返回 null。 */
    private static String versionInPath(String path) {
        String[] parts = path.split("/");
        if (parts.length < 3) {
            return null;
        }
        String candidate = parts[parts.length - 2];
        return candidate.matches("\\d[\\w.\\-]*") ? candidate : null;
    }

    /** 一个 jar 的 {@code Implementation-Version}；没这个条目或读不到返回 null。 */
    private static String jarVersion(Path file) {
        String cached = VERSION_CACHE.get(file);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String found = null;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
            if (entry != null) {
                try (InputStream in = zip.getInputStream(entry)) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    for (String line : text.split("\r?\n")) {
                        if (line.startsWith("Implementation-Version:")) {
                            found = line.substring("Implementation-Version:".length()).trim();
                            break;
                        }
                        // MANIFEST 会折行续写（两空格开头），版本串不会折行，不用管
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            // 读不出来就等于没法判，放行比拦下安全
        }
        VERSION_CACHE.put(file, found == null ? "" : found);
        return found;
    }

    /**
     * 这个版本现在缺哪些库（含本机那份 natives 包），顺序同 version.json。
     *
     * <p>带 {@code os} 规则的按当前系统过滤——只认 windows 的库不会跑到 Linux 上白报一条「缺」。
     */
    public static List<Lib> missing(VersionJson.Meta meta) {
        return missing(meta, false);
    }

    /**
     * 0.8.6：完整性校验版（HMCL ①）。除了「在不在」，还验 sha1 与 jar 能不能打开。
     *
     * @param forceFull 上次异常退出时为 true：绕过本进程内的校验缓存，逐条重验
     */
    public static List<Lib> missing(VersionJson.Meta meta, boolean forceFull) {
        if (meta == null) {
            return List.of();
        }
        List<Object> libs = meta.libraries() == null ? List.of() : meta.libraries();
        Path root = libDir();
        List<Lib> out = new ArrayList<>();
        for (Object raw : libs) {
            if (!(raw instanceof Map<?, ?> lib) || !GameLauncher.allowed(lib)) {
                continue;
            }
            if (!(lib.get("downloads") instanceof Map<?, ?> dl)
                    || !(dl.get("artifact") instanceof Map<?, ?> art)) {
                continue;
            }
            if (!(art.get("path") instanceof String path) || !(art.get("url") instanceof String url)) {
                continue;
            }
            if (path.isBlank() || url.isBlank()) {
                continue;
            }
            boolean nativeJar = isNativeJar(path);
            // 老格式里带 natives 字段的库（1.7.3 那类 jinput）本来不进 classpath、也不单独
            // 解压，跳过；新格式则全走 downloads.artifact
            boolean nativesField = lib.containsKey("natives")
                    || Boolean.TRUE.equals(lib.get("nativesOnly"));
            if (!nativeJar && nativesField) {
                continue;
            }
            // natives 包和库都落在 libraries 里的同一个位置（解压时也从那儿读），
            // 「libraries 里有没有这个文件」是判据之一——但**不是唯一的那一个**：
            // 同一个 path 上可能躺着上一个版本的 jar（见 staleVersion），只看存在与否
            // 会一直以为齐了（v0.5：26.4-snapshot-2 就吃了一口 jtracy 1.0.37 的旧 jar）
            Path file = root.resolve(path);
            // 0.9.2 r4：url 存恒镜像改写（保留 rewriteVanilla 指纹），originUrl 存官方原址；
            // downloadOne 用 vanillaCandidates(originUrl) 生成镜像 + 官方双源竞速
            Lib want = new Lib(path, Mirror.rewriteVanilla(url), sha1Of(art.get("sha1")),
                    sizeOf(art.get("size")), nativeJar, url);
            if (!Files.isRegularFile(file)) {
                out.add(want);
                continue;
            }
            // 0.8.6：文件「在」不等于「好」——半截 jar / CDN 错误页 / 解压截断都会被放行，
            // 进程能起来但 MC 崩在类初始化。逐条验，坏了就加进 out 让 prepare 重下。
            String problem = verifyExisting(file, want, forceFull);
            if (problem != null) {
                System.err.println("[libfetch] 「" + want.name() + "」" + problem + "，当没下重补");
                out.add(want);
                continue;
            }
            if (staleVersion(file)) {
                System.out.println("[libfetch] 「" + path + "」目录里是 " + jarVersion(file)
                        + "，这个版本要的是 " + versionInPath(path) + "，重装");
                out.add(want);
            }
        }
        return out;
    }

    /**
     * 已验过文件的指纹缓存：{@code path -> "size:mtime:sha1"}。
     * 记录「上次成功校验」的 mtime，供快速放行判断文件是否被外部改动过。
     */
    private static final Map<String, String> VERIFIED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 每个库最后一次失败的原因（一行、已压平、截断），供 {@link #failedText} 说人话。
     *
     * <p>为什么要有：用户看到的是「53 个没下下来」，这句话对他的下一步毫无帮助——
     * 限流 / 超时 / 全源 404 该做的事完全不同（等一分钟、换网络、换版本）。
     * 只在内存里留最近一条、成功即删，不做任何持久化，也不改对外接口。
     */
    private static final Map<String, String> LAST_REASONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 返回 null 表示这个文件能用，否则一句人话说明坏在哪。异常一律容错，不往外抛。
     *
     * <p>0.9.2 r4 V3 快速放行（对齐 LauncherX UseFastGameFileCheck）：正常启动
     * （{@code forceFull=false}）只要「文件在 + 非 0 + size 与官方 version.json 期望一致」、
     * 且 mtime 不早于上次成功校验（{@link #VERIFIED} 命中或首次登记）即直接放行，
     * <b>跳过全量 SHA-1 与 ZipFile 打开</b>——旧实现每次启动对 88 个库逐个重算哈希，
     * 是「补依赖」阶段最重的固定开销。首次下载的完整性由 {@link #downloadFrom} /
     * {@link #writeWinner} 的流式 SHA-1 保证，不丢。
     * {@code forceFull}（异常退出 / 用户手动重新校验）、mtime 确实变过、或 version.json 未给
     * 权威 size（无从判断半截文件）时一律退回完整 SHA-1 + ZipFile，绝不误放行缺库 / 半截文件。
     */
    private static String verifyExisting(Path file, Lib want, boolean forceFull) {
        String key = file.toAbsolutePath().normalize().toString();
        long size;
        long stamp;
        try {
            size = Files.size(file);
            stamp = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException | RuntimeException ex) {
            return "读不到文件属性（" + ex + "）";
        }
        if (size == 0) {
            return "是个 0 字节空壳";
        }
        String expected = want.sha1() == null
                ? "" : want.sha1().trim().toLowerCase(Locale.ROOT);
        // 同一个 path 可能被不同 MC 版本要求不同的 sha1（v0.5 的 jtracy 就是），
        // 指纹里必须带上期望值，否则换了版本会被「刚验过」误放行
        String fingerprint = size + ":" + stamp + ":" + expected;
        String cached = VERIFIED.get(key);
        if (!forceFull) {
            if (want.size() > 0 && size != want.size()) {
                // 有权威 size 且对不上（半截文件 / CDN 错误页）→ 判坏，missing() 会加进待补重下
                return "大小对不上（" + size + " / " + want.size() + "）";
            }
            // 只有「version.json 给了权威 size 且与本地一致」才敢快速放行：
            // 元数据缺 size 时无从判断半截文件，一律落到下面的完整 SHA-1 + ZipFile，
            // 绝不把缺库 / 半截文件误判成已有（team-lead 对 V3 的 runtime 担心点）。
            if (want.size() > 0 && (cached == null || fingerprint.equals(cached))) {
                VERIFIED.put(key, fingerprint);
                return null;
            }
            // 无权威 size 可比 / mtime 变过（文件被外部改动）：落到下面的完整校验
        }
        // forceFull / 无权威 size / mtime 变过：保留完整 SHA-1 + ZipFile
        if (!expected.isEmpty() && !expected.equals(sha1Of(file))) {
            return "文件不完整，需要重新下载";
        }
        // 只对 jar 验 zip 完整性：非 jar 条目不强制，字段缺失就跳过
        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            try (java.util.zip.ZipFile ignored = new java.util.zip.ZipFile(file.toFile())) {
                // ZipFile 构造时已校验中央目录，坏文件在这里抛
            } catch (IOException | RuntimeException ex) {
                return "jar 打不开（" + ex + "）";
            }
        }
        VERIFIED.put(key, fingerprint);
        return null;
    }

    /**
     * 并发补下载，返回**没下下来的**那几个（空列表 = 齐了）。
     *
     * <p>只等这一批落地（不是关池），超时也放行——异常由上层说人话。
     */
    public static List<Lib> fetch(List<Lib> libs, Listener listener) throws IOException {
        if (libs == null || libs.isEmpty()) {
            return List.of();
        }
        Path root = libDir();
        Files.createDirectories(root);
        List<Lib> failed = new CopyOnWriteArrayList<>();
        if (listener != null) {
            listener.onStart(libs.size());
        }
        AtomicLong done = new AtomicLong();
        AtomicLong doneBytes = new AtomicLong();
        AtomicLong lastReport = new AtomicLong(0L);
        long startAt = System.currentTimeMillis();
        // 0.9.2 r4 数值进度旁路（不改原有 Listener 文案通道）：起始帧 + 150ms 心跳，
        // 即使还没有库 settle 也持续用 bytes 增量上报，让进度条 / 数字一直「在动」。
        LaunchProgress.emit("正在补依赖库…", 0, libs.size(), 0, 0.0);
        AtomicBoolean beatStop = new AtomicBoolean();
        Thread beat = new Thread(() -> {
            while (!beatStop.get()) {
                int d = (int) done.get();
                LaunchProgress.emit("正在补依赖库…", d, libs.size(), doneBytes.get(),
                        d / (double) libs.size());
                try {
                    Thread.sleep(THROTTLE_MS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "gbl-lib-beat");
        beat.setDaemon(true);
        beat.start();
        // **别用 POOL.awaitTermination**：这个池不关（下次启动还要复用），
        // awaitTermination 只会在超时那一刻返回——库早下完了也得多等 10 分钟，
        // 用户那边就是「点启动以后卡住不动」。用闩锁数任务数（v0.4.9 两处同源，一起改）。
        CountDownLatch latch = new CountDownLatch(libs.size());
        for (Lib lib : libs) {
            POOL.execute(() -> {
                boolean ok = false;
                String note = "";
                try {
                    ok = downloadOne(lib, root);
                } catch (IOException ex) {
                    note = ex.getMessage();
                    System.err.println("[libfetch] 「" + lib.name() + "」没下下来: " + ex);
                }
                if (!ok) {
                    failed.add(lib);
                }
                int finished = (int) done.incrementAndGet();
                doneBytes.addAndGet(lib.size() > 0 ? lib.size() : 0);
                if (listener != null) {
                    listener.onSettled(lib, ok, note);
                    long now = System.currentTimeMillis();
                    long prev = lastReport.get();
                    boolean last = finished == libs.size();
                    if (last || now - prev >= THROTTLE_MS) {
                        // 最后一帧必报，别停在 99%；其余靠 CAS 保证每 THROTTLE_MS 至多一帧
                        if (last || lastReport.compareAndSet(prev, now)) {
                            long seconds = Math.max(1, (now - startAt) / 1000);
                            listener.onProgress(finished, libs.size(), doneBytes.get(),
                                    doneBytes.get() / seconds);
                        }
                    }
                }
                latch.countDown();
            });
        }
        try {
            // 只等这一批落地（不关池，下次启动可能还要补别的库）
            if (!latch.await(10, TimeUnit.MINUTES)) {
                System.err.println("[libfetch] 补库 10 分钟内没收完，剩下的按失败算");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            beatStop.set(true);
            beat.interrupt();
            // 最后一帧必报：无论超时 / 是否有失败，都收在 1.0，别停在 99%
            LaunchProgress.emit("正在补依赖库…", libs.size(), libs.size(), doneBytes.get(), 1.0);
        }
        return failed;
    }

    /**
     * 0.9.2 r4：单库下载改双源竞速；0.9.9 r4：改成「组内双源竞速、组间按优先级串行」。
     *
     * <p>{@link #sourceGroups} 给出按优先级排好的 {@code {主源, 副源}} 组：第一组通常是
     * 阿里云中央仓 + 官方（不限流、国内直连），{@code com/mojang/*} 则第一组就是
     * 镜像 + 官方的老竞速。任何一组成功即返回；只有前一组彻底失败（含组内
     * {@link #RETRY} 次重试）才碰下一组，避免为了「也许快一点」白烧镜像那 10 次/分钟的额度。
     *
     * <p>从 {@code w.stream()} 读流落盘、{@code w.contentLength()} 作 total（缺失 -1）、
     * {@code w.acceptRanges()} 决定要不要分片、{@code w.close()} 放 finally；
     * 大库且服务端认 Range 时改用胜出线路分段并发，其余走单流。全组都失败时把最后一条
     * 原因记进 {@link #LAST_REASONS}，让 {@link #failedText} 能说清到底卡在哪。
     */
    private static boolean downloadOne(Lib lib, Path root) throws IOException {
        List<String[]> groups = sourceGroups(lib);
        String reason = "";
        for (int g = 0; g < groups.size(); g++) {
            String[] pair = groups.get(g);
            String primary = pair[0];
            String secondary = pair.length > 1 ? pair[1] : null;
            for (int attempt = 0; attempt < RETRY; attempt++) {
                RaceFetch.Winner w = null;
                try {
                    w = RaceFetch.race(primary, secondary,
                            RACE_CONNECT_MS, RACE_FIRST_BYTE_MS, RACE_CLIENT);
                    long total = w.contentLength();       // 缺失 -1
                    boolean ranges = w.acceptRanges();
                    String winnerUrl = w.url();
                    boolean ok;
                    if (ranges && total > SEGMENT_MIN) {
                        // 大库 + 服务端认 Range：关掉竞速流，用胜出线路走原分段并发
                        w.close();
                        w = null;
                        ok = downloadFrom(lib, root, winnerUrl);
                    } else {
                        ok = writeWinner(lib, root, w, total);
                    }
                    if (ok) {
                        LAST_REASONS.remove(lib.path());
                        return true;
                    }
                    // 网络通了但内容不对（半截 / sha1 不符）：换源也一样，仍如实记下来
                    reason = "下载内容校验不过（大小或 sha1 对不上）";
                } catch (IOException | RuntimeException ex) {
                    reason = oneLine(ex);
                    System.err.println("[libfetch] 「" + lib.name() + "」第 " + (g + 1)
                            + " 组第 " + (attempt + 1) + " 次失败: " + ex);
                } finally {
                    if (w != null) {
                        try {
                            w.close();
                        } catch (IOException ignored) {
                            // 关闭失败不影响结果
                        }
                    }
                }
                // 429 是「整分钟窗口」，退避 300ms 等于没退——立刻重试还是 429，
                // 白烧掉仅剩的额度。含 429 的失败按秒级退避，等窗口过去
                if (reason.contains("429")) {
                    sleepQuietly(2000L * (attempt + 1));
                } else {
                    sleepQuietly(300L * (attempt + 1));
                }
            }
        }
        LAST_REASONS.put(lib.path(), truncate(reason, 120));
        return false;
    }

    /**
     * 一个库「按优先级排好的双源竞速组」，每组 {@code {主源, 副源}}，组内两路同时发、
     * 组间串行（前一组全败才碰下一组）。
     *
     * <p>排序规则与理由：
     * <ul>
     * <li><b>普通库</b>（gson / lwjgl / fastutil …）：先 {@link #MAVEN_PUBLIC} + 官方，
     *     镜像押到最后。这些库在中央仓一次就成，不碰镜像就不会烧额度。</li>
     * <li><b>{@code com/mojang/*}</b>：先镜像 + 官方。这类构件中央仓<b>没有</b>
     *     （实测 text2speech 1.17.9、brigadier 1.0.18 都是 404），放前面只是白等一次必然失败；
     *     但万一哪天补上了，仍然留着兜底组。</li>
     * <li><b>镜像永不为空</b>：{@code GBL_MIRROR=off} 时 {@link Mirror#vanillaCandidates}
     *     只回一个元素，此时把「官方」槽位替换成 {@code c[0]}，绝不产出 null
     *     （null 副源会让 {@code RaceFetch.race} 退化成单源，白白浪费组内的一路并发）。</li>
     * </ul>
     * 若某组的两个地址与上一组完全相同（{@code GBL_MIRROR=off} 下的退化情形），直接跳过，
     * 免得对着同一个地址反复重试 {@link #RETRY} 次。
     */
    private static List<String[]> sourceGroups(Lib lib) {
        String[] c = Mirror.vanillaCandidates(lib.originUrl());
        String mirror = c[0];
        String official = c.length > 1 && c[1] != null && !c[1].isBlank() ? c[1] : c[0];
        String central = MAVEN_PUBLIC + lib.path();
        List<String[]> groups = new ArrayList<>(2);
        if (lib.path().startsWith("com/mojang/")) {
            groups.add(new String[]{mirror, official});
            groups.add(new String[]{central, official});
        } else {
            groups.add(new String[]{central, official});
            groups.add(new String[]{mirror, official});
        }
        List<String[]> out = new ArrayList<>(groups.size());
        for (String[] group : groups) {
            if (!out.isEmpty() && samePair(out.get(out.size() - 1), group)) {
                continue;
            }
            out.add(group);
        }
        return out;
    }

    /** 两组是否指向同两个地址（同序比较：竞速组是有序的，主源优先级不同就算不同组）。 */
    private static boolean samePair(String[] a, String[] b) {
        String a0 = a.length > 0 ? a[0] : null;
        String a1 = a.length > 1 ? a[1] : null;
        String b0 = b.length > 0 ? b[0] : null;
        String b1 = b.length > 1 ? b[1] : null;
        return java.util.Objects.equals(a0, b0) && java.util.Objects.equals(a1, b1);
    }


    /**
     * 0.9.2 r4：从竞速胜出的 {@code w.stream()} 单流落盘一个库，边下边算 sha1。
     * total 取自 {@code w.contentLength()}（缺失 -1 时退回 version.json 的 size），
     * 首次下载的完整性校验不丢；{@code w.close()} 由 {@link #downloadOne} 的 finally 负责。
     */
    private static boolean writeWinner(Lib lib, Path root, RaceFetch.Winner w, long total)
            throws IOException {
        Path target = root.resolve(lib.path());
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        MessageDigest digest = newSha1();
        long written = 0;
        try (InputStream in = w.stream();
             var out = Files.newOutputStream(part, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                written += n;
                if (digest != null) {
                    digest.update(buffer, 0, n);
                }
            }
        }
        long expect = total > 0 ? total : lib.size();
        if (expect > 0 && written != expect) {
            System.err.println("[libfetch] 「" + lib.name() + "」只收到 "
                    + written + " / " + expect + " 字节，当没下");
            return false;
        }
        String want = lib.sha1() == null ? "" : lib.sha1().trim().toLowerCase(Locale.ROOT);
        if (!want.isEmpty()) {
            String got = digest != null ? toHex(digest.digest()) : sha1Of(part);
            if (!want.equals(got)) {
                System.err.println("[libfetch] 「" + lib.name() + "」sha1 对不上，当没下");
                return false;
            }
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    /**
     * 从指定地址下完一个库（写 .part → 验 sha1 → 挪位）。
     *
     * <p>0.9.2 r2：删掉多余的 HEAD {@code probe}，长度直接用 version.json 的
     * {@code downloads.artifact.size}；单流档<b>边下边算 sha1</b>，落盘后直接 {@code digest()}
     * 比对，不再整文件重读（照抄 {@code FileFetch.fetchStream} 的范式）。
     * 分段并发档完成顺序不定、无法流式顺序算哈希，仍退回落盘后重读校验。
     */
    private static boolean downloadFrom(Lib lib, Path root, String url) throws IOException {
        Path target = root.resolve(lib.path());
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        long total = lib.size();
        // 与 writeInto 的 segments 判据一致：只有「整文件单流」才流式算 sha1
        boolean single = total <= SEGMENT_MIN
                || (int) Math.min(GameDownloader.SEGMENTS, total / SEGMENT_MIN) == 1;
        MessageDigest digest = single ? newSha1() : null;
        if (!writeInto(url, lib, part, total, digest)) {
            return false;
        }
        if (total > 0 && Files.size(part) != total) {
            System.err.println("[libfetch] 「" + lib.name() + "」只收到 "
                    + Files.size(part) + " / " + total + " 字节，当没下");
            return false;
        }
        String want = lib.sha1().toLowerCase(Locale.ROOT);
        if (!want.isEmpty()) {
            String got = digest != null ? toHex(digest.digest()) : sha1Of(part);
            if (!want.equals(got)) {
                System.err.println("[libfetch] 「" + lib.name() + "」sha1 对不上，当没下");
                return false;
            }
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    /**
     * 把 URL 的内容写进 part。{@code total > 0} 且够大时切段并发；
     * 服务端不认 Range（或者文件本来就小）就一条流灌完。
     *
     * <p>{@code digest} 只在「整文件单流」时被 {@link #copyInto} 喂字节；分段并发顺序不定，
     * 传进来的 digest 不会被 update（此时由 {@link #downloadFrom} 退回重读校验）。
     *
     * <p><b>0.9.9 r4 修的关键 bug</b>：原实现把段任务 {@code execute} 完就 `return
     * ok.stream().noneMatch(v -> !v)`——此刻任务多半还没跑，{@code ok} 是空表，
     * 「没有失败」恒成立 ⇒ 恒返回 true；调用方 {@link #downloadFrom} 紧接着
     * {@code Files.size(part) != total} 必然判失败。触发条件是 {@code segments >= 2}
     * （{@code total >= 16MB}），1.20.1 的 fastutil 23MB 正好命中，<b>网络再好也永远下不下来</b>。
     * 现在改成 {@link CountDownLatch} 真等所有段落地，并校验「落地的段数 == 提交的段数」。
     */
    private static boolean writeInto(String url, Lib lib, Path part, long total,
                                     MessageDigest digest) {
        int segments = total <= SEGMENT_MIN ? 1
                : (int) Math.min(GameDownloader.SEGMENTS, total / SEGMENT_MIN);
        if (segments == 1) {
            return writeOnce(url, part, 0, 0, digest);
        }
        // 先按「实际会提交几段」算好再建闩锁：`to < from` 的空段不提交任务，
        // 如果先把 segments 当计数再 continue，latch 永远等不到 0（死等 6 分钟）
        int live = 0;
        for (int i = 0; i < segments; i++) {
            if (total * (i + 1) / segments - 1 >= total * i / segments) {
                live++;
            }
        }
        if (live <= 0) {
            return writeOnce(url, part, 0, 0, digest);
        }
        CountDownLatch done = new CountDownLatch(live);
        List<Boolean> ok = new CopyOnWriteArrayList<>();
        AtomicLong alive = new AtomicLong(live);
        for (int i = 0; i < segments; i++) {
            long from = total * i / segments;
            long to = total * (i + 1) / segments - 1;
            if (to < from) {
                continue;
            }
            long start = from;
            long end = to;
            SEGMENT_POOL.execute(() -> {
                boolean good = false;
                try {
                    good = writeOnce(url, part, start, end, digest);
                } catch (RuntimeException ex) {
                    System.err.println("[libfetch] 「" + lib.name() + "」第 " + start
                            + " 段没下下来: " + ex);
                } finally {
                    ok.add(good);
                    // alive 只用于打一行汇总日志；完成判定一律看闩锁，别再用它
                    if (alive.decrementAndGet() == 0 && ok.stream().anyMatch(v -> !v)) {
                        System.err.println("[libfetch] 「" + lib.name() + "」有段下失败了");
                    }
                    done.countDown();   // 必须在 finally：上面任何一步抛异常也要放闩
                }
            });
        }
        try {
            // 6 分钟 = 23MB 库里最慢的一段也够下完；超时按失败算，让上层换源重试
            if (!done.await(6, TimeUnit.MINUTES)) {
                System.err.println("[libfetch] 「" + lib.name() + "」分段下载 6 分钟没收完，当没下");
                return false;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
        // 段数不齐（任务被池拒绝 / 线程被中断）也算失败，不能拿「没记录」当「没失败」
        return ok.size() >= live && ok.stream().noneMatch(v -> !v);
    }

    /** 写一段（{@code end <= start} 表示读到连接结束）；失败 {@code RETRY} 次才放弃。 */
    private static boolean writeOnce(String url, Path part, long start, long end,
                                     MessageDigest digest) {
        for (int attempt = 0; attempt < RETRY; attempt++) {
            try {
                // 重试要从头重算摘要，别把上一次半截的字节累积进 digest
                if (digest != null) {
                    digest.reset();
                }
                copyInto(url, part, start, end, digest);
                return true;
            } catch (IOException ex) {
                System.err.println("[libfetch] 「" + part.getFileName() + "」第 "
                        + (attempt + 1) + " 次失败: " + ex);
                sleepQuietly(400L * (attempt + 1));
            }
        }
        return false;
    }

    /** 一次（带 Range 的）GET，把响应体灌进 part 的指定位置。 */
    private static void copyInto(String url, Path part, long start, long end,
                                 MessageDigest digest) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(CHUNK_TIMEOUT)
                .header("User-Agent", GameDownloader.AGENT)
                .header("Accept", "application/octet-stream")
                .header("Accept-Encoding", "identity");
        if (end > start) {
            request.header("Range", "bytes=" + start + "-" + end);
        } else if (start > 0) {
            request.header("Range", "bytes=" + start + "-");
        }
        HttpResponse<InputStream> response;
        try {
            response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断", ex);
        }
        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            throw new IOException("服务器返回 HTTP " + code);
        }
        // 只有「整文件单流」(start==0 且无 Range) 才边下边算 sha1；分段并发顺序不定不喂 digest
        boolean streamDigest = digest != null && start == 0 && end <= start;
        try (InputStream in = response.body();
             SeekableByteChannel channel = Files.newByteChannel(part,
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(start);
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                channel.write(ByteBuffer.wrap(buffer, 0, n));
                if (streamDigest) {
                    digest.update(buffer, 0, n);
                }
            }
        }
    }

    /**
     * 这个 path 是不是**本机**那份 natives 包。
     *
     * <p>只看文件名里的系统标记、顺手排掉 arm64 / x86 那两份——它们是同一颗库给别的架构的，
     * 收进来只会让「缺」的数目虚高、解压时把本机那份盖掉。
     */
    static boolean isNativeJar(String path) {
        String name = path.toLowerCase(Locale.ROOT);
        if (!name.contains("natives")) {
            return false;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String marker = os.contains("mac") ? "natives-mac" : os.contains("win") ? "natives-win" : "natives-lin";
        return name.contains(marker);
    }

    private static String sha1Of(Object raw) {
        return raw instanceof String text ? text.trim().toLowerCase(Locale.ROOT) : "";
    }

    /** 文件实际的 sha1（小写 hex），读不到给空串（= 不校验）。 */
    private static String sha1Of(Path file) {
        MessageDigest digest = newSha1();
        if (digest == null) {
            return "";
        }
        try {
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = Files.newInputStream(file)) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (IOException | RuntimeException ex) {
            System.err.println("[libfetch] 「" + file.getFileName() + "」算 sha1 失败: " + ex);
            return "";
        }
    }

    private static long sizeOf(Object raw) {
        return raw instanceof Number number ? number.longValue() : 0L;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** SHA-1（JDK 必带，够不着就返回 null = 不校验）。 */
    private static MessageDigest newSha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (java.security.NoSuchAlgorithmException ex) {
            return null;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** 进度文案：给人看的一行。 */
    public static String progressText(int done, int total, long bytesPerSecond) {
        String speed = bytesPerSecond <= 0 ? "" : " · " + human(bytesPerSecond) + "/s";
        return "正在补依赖库…（" + done + "/" + total + "）" + speed;
    }

    /** 字节数的人话。 */
    public static String human(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** 补库失败那一句实话。 */
    public static String failedText(List<Lib> failed) {
        if (failed == null || failed.isEmpty()) {
            return "依赖库补齐了";
        }
        StringBuilder sb = new StringBuilder("有 " + failed.size() + " 个依赖库没下下来");
        int tail = Math.min(3, failed.size());
        for (int i = 0; i < tail; i++) {
            sb.append(i == 0 ? "：" : "、").append(failed.get(i).name());
        }
        if (failed.size() > tail) {
            sb.append(" 等 " + failed.size() + " 个");
        }
        // 「53 个没下下来」对用户下一步毫无指导：限流该等一分钟，超时该换网络，
        // 全源 404 该换版本。附上最近一条原因，至少能让他知道先试哪个动作。
        String reason = lastReasonText(failed);
        if (!reason.isEmpty()) {
            sb.append("（最近一条原因：").append(reason).append("）");
        }
        return sb.toString();
    }

    /** 取失败列表里第一条有记录的 {@link #LAST_REASONS}，翻译成人话；全无记录返回空串。 */
    private static String lastReasonText(List<Lib> failed) {
        for (Lib lib : failed) {
            String raw = LAST_REASONS.get(lib.path());
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String low = raw.toLowerCase(Locale.ROOT);
            if (low.contains("429")) {
                return "下载源限流了";
            }
            if (low.contains("timeout") || low.contains("timed out")) {
                return "连接超时";
            }
            if (low.contains("404")) {
                return "这个文件所有下载源都没有";
            }
            return truncate(raw, 120);
        }
        return "";
    }

    /** 把异常压成一行（去掉换行、压掉多余空白），给用户看的记录只留这一行。 */
    private static String oneLine(Throwable ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = ex.getClass().getSimpleName();
        }
        return msg.replaceAll("\\s+", " ").trim();
    }

    /** 截到 {@code n} 个字符，超了加省略号；{@code null} 当空串。 */
    private static String truncate(String text, int n) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= n ? flat : flat.substring(0, n) + "…";
    }
}
