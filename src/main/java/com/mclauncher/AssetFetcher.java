package com.mclauncher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 补下载一个版本缺的**资源**（assets，v0.4.9）。
 *
 * <p>为什么需要它：客户端 jar 里只有代码，贴图/音效/语言/主界面背景全在
 * {@code assets/objects/}，得按资源索引（{@code assets/indexes/<id>.json}）一个个捞。
 * 启动器以前只在启动前「数一数缺多少」然后提醒一句，可从来不会真去下——
 * 磁盘上只有 {@code 32.json} / {@code 34.json}，1.20.1 要的 {@code 5.json} 压根没有，
 * 于是 3598 个资源一个不在：主界面背景是黑的、菜单中文变成一堆方块、
 * 日志里几百行 {@code Missing sound for event}，玩一会儿还会崩在资源加载上（v0.4.9 用户实测）。
 *
 * <p>资源对象和依赖库不一样：**特别小**（大部分几 KB，最大的也就几百 KB）、
 * 名字是内容的 sha1，所以不切段、不分段，{@link #PARALLEL} 条连接直接并发拉就行。
 *
 * <p>布局照抄官方：{@code assets/indexes/<id>.json} 是索引，
 * {@code assets/objects/<hash 前两位>/<hash>} 是对象本体——游戏自己也是这么找的，
 * 别自己发明一套目录。
 */
public final class AssetFetcher {

    /** 同时下几个资源对象（0.9.2 r4：32→64，对齐 LauncherX 高并发；上万小对象并发拉满才有意义）。 */
    private static final int PARALLEL = 64;
    /** 单个对象最多试几次（0.9.2 r4：双源竞速已淘汰慢线，3→2）。 */
    private static final int RETRY = 2;
    /** 对外进度节流：每 {@value} 毫秒最多报一次，别让上万个小文件刷爆 UI。 */
    private static final long THROTTLE_MS = 150L;
    /** 0.9.2 r4：连接 / 读超时收紧（6s / 15s），双源竞速淘汰慢线后不再长超时硬等。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration IO_TIMEOUT = Duration.ofSeconds(15);
    /** 一轮最多等这么久；超时按「还缺」算，让上层说人话，别无限等下去。 */
    private static final int WAIT_MINUTES = 12;

    /** 0.9.2 r4 双源竞速预算（毫秒）：连接 6s / 首字节 8s，慢线当场淘汰。 */
    private static final int RACE_CONNECT_MS = 6000;
    private static final int RACE_FIRST_BYTE_MS = 8000;
    /** 竞速专用 client：HTTP/1.1 + ProxyRoute 选路 + NEVER 重定向，共享一个实例复用连接池。 */
    private static final HttpClient RACE_CLIENT = RaceFetch.newClient(RACE_CONNECT_MS);

    /** 官方资源 CDN：对象地址是 {@code <前两位>/<hash>}。 */
    private static final String OBJECT_CDN = "https://resources.download.minecraft.net/";

    /** 保留的单源 client（已收紧连接超时）；0.9.2 r4 起下载主路径是 {@link #RACE_CLIENT} 竞速，此实例留作兼容。 */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final ExecutorService POOL = Executors.newFixedThreadPool(PARALLEL, runnable -> {
        Thread thread = new Thread(runnable, "gbl-asset");
        thread.setDaemon(true);   // 资源线程绝不能拦住 JVM 退出
        return thread;
    });

    /** 一个待补的资源对象。 */
    public record Obj(String name, String hash, long size) {

        /**
         * 对象地址：官方 CDN 经 {@link Mirror#rewriteVanilla} 恒改写到 BMCLAPI（0.9.2 r2 起）。
         *
         * <p>0.9.2 r4：实际下载已改由 {@link #downloadOne} 走 {@link Mirror#vanillaCandidates}
         * 双源竞速，本方法保留供兼容 / 指纹；{@code GBL_MIRROR=off} 时它是恒等函数、返回官方地址。
         */
        public String url() {
            return Mirror.rewriteVanilla(OBJECT_CDN + hash.substring(0, 2) + "/" + hash);
        }

        /** 官方原址：{@link #downloadOne} 用它经 {@link Mirror#vanillaCandidates} 生成双源竞速候选。 */
        public String originUrl() {
            return OBJECT_CDN + hash.substring(0, 2) + "/" + hash;
        }
    }

    /** 补资源的进度/结果。回调发生在下载线程上，接的人自己往 FX 线程送。 */
    public interface Listener {
        default void onStart(int total) {
        }

        default void onSettled(Obj obj, boolean ok) {
        }

        default void onProgress(int done, int total, long doneBytes) {
        }
    }

    private AssetFetcher() {
        // 静态工具类
    }

    public static Path assetsDir() {
        return Platform.minecraftDir().resolve("assets");
    }

    /** 资源索引落盘位置。 */
    public static Path indexPath(String indexId) {
        return assetsDir().resolve("indexes").resolve(indexId + ".json");
    }

    /**
     * 确保资源索引在本地（没有就按 {@code assetIndex.url} 下一份）。
     *
     * <p>0.9.2 r4：改为 {@link Mirror#vanillaCandidates} 双源候选一次竞速——镜像与官方
     * 同时发 GET、取最快 2xx。不再走 r2 的「先镜像、失败再串行回落官方」（那会为每个慢索引
     * 白等一个超时）。{@code GBL_MIRROR=off} 时候选退化为单元素，secondary 传 null，语义不变。
     *
     * @return 索引文件；拿不到给 {@code null}
     */
    public static Path ensureIndex(String indexId, String indexUrl) {
        Path local = indexPath(indexId);
        if (Files.isRegularFile(local) && sizeOf(local) > 2) {
            return local;
        }
        if (indexUrl == null || indexUrl.isBlank()) {
            return null;
        }
        String[] c = Mirror.vanillaCandidates(indexUrl);
        if (downloadIndex(c[0], c.length > 1 ? c[1] : null, local)) {
            return local;
        }
        return null;
    }

    /**
     * 从双源候选竞速拉一份资源索引落盘；{@code w.close()} 放 finally。
     * 两路全失败时 {@code RaceFetch} 的 {@code IOException} 已含两路状态，记日志不静默吞。
     */
    private static boolean downloadIndex(String primary, String secondary, Path local) {
        RaceFetch.Winner w = null;
        try {
            w = RaceFetch.race(primary, secondary,
                    RACE_CONNECT_MS, RACE_FIRST_BYTE_MS, RACE_CLIENT);
            byte[] body = w.stream().readAllBytes();
            if (body.length < 2) {
                System.err.println("[asset] 资源索引内容异常（空体）");
                return false;
            }
            Files.createDirectories(local.getParent());
            Path part = local.resolveSibling(local.getFileName() + ".part");
            Files.write(part, body);
            Files.move(part, local, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException ex) {
            System.err.println("[asset] 资源索引双源竞速失败: " + ex);
            return false;
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
     * 这个版本还缺哪些资源对象。
     *
     * @param indexId 资源索引 id（{@code version.json} 的 {@code assetIndex.id}）
     */
    public static List<Obj> missing(String indexId) {
        return missing(indexId, false);
    }

    /**
     * 0.8.6：完整性校验版（HMCL ①）。文件「在」之外还验大小；
     * 上次异常退出（{@code forceFull}）时再逐个验 sha1（对象名本身就是内容的 sha1）。
     *
     * <p>正常启动**不算 sha1**：资源动辄上万个小文件，全量读一遍会把首屏拖慢；
     * 大小校验已经能挡住 0 字节 / 半截 / 错误页，字段缺失就跳过。
     */
    public static List<Obj> missing(String indexId, boolean forceFull) {
        List<Obj> out = new ArrayList<>();
        Path index = indexPath(indexId);
        if (!Files.isRegularFile(index)) {
            return out;
        }
        Object parsed;
        try {
            parsed = Json.parse(Files.readString(index, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ex) {
            System.err.println("[asset] 资源索引读不了: " + ex);
            return out;
        }
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("objects") instanceof Map<?, ?> objects)) {
            return out;
        }
        Path objectsDir = assetsDir().resolve("objects");
        int reported = 0;
        for (Object[] entry : objects.entrySet().stream()
                .map(e -> new Object[]{e.getKey(), e.getValue()}).toList()) {
            if (!(entry[1] instanceof Map<?, ?> item)) {
                continue;
            }
            Object hashRaw = item.get("hash");
            if (!(hashRaw instanceof String hash) || hash.length() < 2) {
                continue;
            }
            Obj obj = new Obj(String.valueOf(entry[0]), hash, sizeOf(item.get("size")));
            Path file = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
            if (!Files.isRegularFile(file)) {
                out.add(obj);
                continue;
            }
            String problem = verifyExisting(file, obj, forceFull);
            if (problem != null) {
                if (reported++ < 20) {
                    System.err.println("[asset] 「" + obj.name() + "」" + problem + "，重新下");
                }
                out.add(obj);
            }
        }
        return out;
    }

    /**
     * 返回 null 表示这个资源文件能用，否则一句人话说明坏在哪；异常一律容错。
     *
     * <p>0.9.2 r4 V3 快速放行：正常启动只按 size 判（在 + 非 0 + 与期望一致即放行），
     * <b>不逐个重算 SHA-1</b>——资源动辄 5221 个小对象，全量读一遍会把补资源拖死；
     * {@code forceFull}（异常退出 / 手动重校）才补一道 sha1。首次下载仍由
     * {@link #tryDownload} 边下边算 sha1，完整性校验不丢。
     */
    private static String verifyExisting(Path file, Obj obj, boolean forceFull) {
        try {
            long actual = Files.size(file);
            if (actual == 0) {
                return "是个 0 字节空壳";
            }
            if (obj.size() > 0 && actual != obj.size()) {
                return "大小对不上（" + actual + " / " + obj.size() + "）";
            }
            if (forceFull && !obj.hash().equalsIgnoreCase(sha1Of(file))) {
                return "sha1 对不上";
            }
            return null;
        } catch (IOException | RuntimeException ex) {
            return "读不到（" + ex + "）";
        }
    }

    /**
     * 排「启动必需」的那批资源，**其余排在后面**。
     *
     * <p>为什么分两拨：中文和主界面背景是用户一睁眼就要看见的（没它们等于白启动），
     * 音效/字体/材质包量大但可以随后补。分两拨是为了让「等不到」的时候也有个能看的界面，
     * 而不是卡在黑屏 loading 上。
     *
     * <p>注意 {@code minecraft:include/unifont}（{@code minecraft/font/} 开头）也在必胜》里：
     * 少了它中文会渲染不出来，日志报 {@code Unable to load font 'minecraft:include/unifont'}。
     */
    public static List<Obj> pickStartupEssentials(List<Obj> all) {
        List<Obj> must = new ArrayList<>();
        List<Obj> rest = new ArrayList<>();
        for (Obj obj : all) {
            String name = obj.name();
            boolean essential = (name.contains("/lang/") && name.endsWith(".json"))
                    || name.startsWith("minecraft/textures/gui/title/")
                    || name.startsWith("minecraft/font/");
            (essential ? must : rest).add(obj);
        }
        List<Obj> out = new ArrayList<>(must.size() + rest.size());
        out.addAll(must);
        out.addAll(rest);
        return out;
    }

    /** 这一批里有多少是「非有不可」的（用来决定等不等剩下的）。 */
    public static int essentialCount(List<Obj> objs) {
        int n = 0;
        for (Obj obj : objs) {
            String name = obj.name();
            if ((name.contains("/lang/") && name.endsWith(".json"))
                    || name.startsWith("minecraft/textures/gui/title/")
                    || name.startsWith("minecraft/font/")) {
                n++;
            }
        }
        return n;
    }

    /** 并发补下载，返回**没下下来的**那几个（空列表 = 齐了）。 */
    public static List<Obj> fetch(List<Obj> objs, Listener listener) {
        if (objs == null || objs.isEmpty()) {
            return List.of();
        }
        List<Obj> failed = new CopyOnWriteArrayList<>();
        if (listener != null) {
            listener.onStart(objs.size());
        }
        AtomicLong done = new AtomicLong();
        AtomicLong doneBytes = new AtomicLong();
        AtomicLong lastReport = new AtomicLong(0L);
        // 0.9.2 r4 数值进度旁路（不改原有 Listener 文案通道）：起始帧先把关卡推到 0，
        // 再起一个 150ms 心跳，即使还没有文件 settle 也持续用 bytes 增量上报，
        // 让进度条与数字一直「在动」，不再定格在 0/N、0 B。
        LaunchProgress.emit("正在补资源…", 0, objs.size(), 0, 0.0);
        AtomicBoolean beatStop = new AtomicBoolean();
        Thread beat = new Thread(() -> {
            while (!beatStop.get()) {
                int d = (int) done.get();
                LaunchProgress.emit("正在补资源…", d, objs.size(), doneBytes.get(),
                        d / (double) objs.size());
                try {
                    Thread.sleep(THROTTLE_MS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "gbl-asset-beat");
        beat.setDaemon(true);
        beat.start();
        // **别用 POOL.awaitTermination**：这个池从来不关（要留给下次启动复用），
        // awaitTermination 只会一直等到超时——哪怕资源早就下完了，也得干等满
        // WAIT_MINUTES 才返回，用户看到的就是「点启动后卡住不动」（v0.4.9 踩过，
        // LibFetcher 里同一个写法也一起改了）。改用闩锁数任务数。
        CountDownLatch latch = new CountDownLatch(objs.size());
        for (Obj obj : objs) {
            POOL.execute(() -> {
                boolean ok;
                try {
                    ok = downloadOne(obj);
                } catch (IOException | RuntimeException ex) {
                    System.err.println("[asset] 「" + obj.name() + "」没下下来: " + ex);
                    ok = false;
                }
                if (!ok) {
                    failed.add(obj);
                }
                int finished = (int) done.incrementAndGet();
                doneBytes.addAndGet(Math.max(0, obj.size()));
                if (listener != null) {
                    listener.onSettled(obj, ok);
                    long now = System.currentTimeMillis();
                    long prev = lastReport.get();
                    boolean last = finished == objs.size();
                    if (last || now - prev >= THROTTLE_MS) {
                        // 最后一帧必报，别停在 99%；其余靠 CAS 保证每 THROTTLE_MS 至多一帧
                        if (last || lastReport.compareAndSet(prev, now)) {
                            listener.onProgress(finished, objs.size(), doneBytes.get());
                        }
                    }
                }
                latch.countDown();
            });
        }
        try {
            if (!latch.await(WAIT_MINUTES, TimeUnit.MINUTES)) {
                System.err.println("[asset] 补资源 " + WAIT_MINUTES + " 分钟内没收完，剩下的按失败算");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            beatStop.set(true);
            beat.interrupt();
            // 最后一帧必报：无论超时 / 是否有失败，都收在 1.0，别停在 99%
            LaunchProgress.emit("正在补资源…", objs.size(), objs.size(), doneBytes.get(), 1.0);
        }
        return failed;
    }

    /**
     * 0.9.2 r4：单文件下载改双源竞速。镜像与官方同时发 GET、取最快 2xx，
     * 不再「先镜像、失败再串行回落官方」——那会为每个慢对象白等一个超时。
     * {@code GBL_MIRROR=off} 时 {@link Mirror#vanillaCandidates} 退化为单元素，secondary 传 null。
     */
    private static boolean downloadOne(Obj obj) throws IOException {
        String[] c = Mirror.vanillaCandidates(obj.originUrl());
        return tryDownload(obj, c[0], c.length > 1 ? c[1] : null);
    }

    /**
     * 从双源候选竞速下一个对象（{@value #RETRY} 次重试，<b>边下边算 sha1</b> 后挪位）。
     *
     * <p>0.9.2 r4：用 {@code RaceFetch.race} 拿到胜出流，从 {@code w.stream()} 读流落盘；
     * {@code w.contentLength()} 作 total（缺失 -1，用于落盘字节数校验，不再发每文件 HEAD probe）、
     * {@code w.acceptRanges()} 仅备查（资源对象按内容 sha1 寻址、不切段，故意不分片）、
     * {@code w.close()} 放 finally。首次下载仍流式验 sha1，完整性校验不丢；
     * 两路全失败时 {@code RaceFetch} 的 {@code IOException} 带两路状态，由重试到头的日志打出，不静默吞。
     */
    private static boolean tryDownload(Obj obj, String primary, String secondary) throws IOException {
        Path target = assetsDir().resolve("objects")
                .resolve(obj.hash().substring(0, 2)).resolve(obj.hash());
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        for (int attempt = 0; attempt < RETRY; attempt++) {
            MessageDigest digest = newSha1();
            RaceFetch.Winner w = null;
            try {
                w = RaceFetch.race(primary, secondary,
                        RACE_CONNECT_MS, RACE_FIRST_BYTE_MS, RACE_CLIENT);
                long total = w.contentLength();       // 缺失 -1
                boolean ranges = w.acceptRanges();    // 资源对象按 hash 寻址、不切段，仅按契约备查
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
                // total 从 Winner 取；对不上就是半个文件，当没下重来
                if (total > 0 && written != total) {
                    System.err.println("[asset] 「" + obj.name() + "」只收到 "
                            + written + " / " + total + " 字节，当没下");
                    sleepQuietly(300L * (attempt + 1));
                    continue;
                }
                // 资源名就是它的 sha1，顺手验一道（对不上多半是 CDN 给了错误页）
                if (digest == null || !obj.hash().equalsIgnoreCase(toHex(digest.digest()))) {
                    System.err.println("[asset] 「" + obj.name() + "」sha1 对不上，当没下");
                    Files.deleteIfExists(part);
                    sleepQuietly(300L * (attempt + 1));
                    continue;
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (IOException | RuntimeException ex) {
                if (attempt == RETRY - 1) {
                    System.err.println("[asset] 「" + obj.name() + "」第 "
                            + RETRY + " 次仍失败: " + ex);
                } else {
                    sleepQuietly(300L * (attempt + 1));
                }
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
        return false;
    }

    /** 进度文案（给界面直接显示）。 */
    public static String progressText(int done, int total, long bytes) {
        return "正在补资源…（" + done + "/" + total + "，" + humanSize(bytes) + "）";
    }

    /** 缺资源的人话。 */
    public static String failedText(List<Obj> failed) {
        if (failed == null || failed.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(3, failed.size());
        for (int i = 0; i < shown; i++) {
            sb.append(i > 0 ? "、" : "").append(failed.get(i).name());
        }
        if (failed.size() > shown) {
            sb.append(" 等 ").append(failed.size()).append(" 个");
        }
        return sb.toString();
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    /** SHA-1（JDK 必带，够不着返回 null = 不校验）。 */
    private static MessageDigest newSha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
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

    private static String sha1Of(Path file) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (java.security.NoSuchAlgorithmException ex) {
            return "";
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        } catch (IOException ex) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static long sizeOf(Object raw) {
        return raw instanceof Number number ? number.longValue() : 0L;
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException ex) {
            return 0L;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
