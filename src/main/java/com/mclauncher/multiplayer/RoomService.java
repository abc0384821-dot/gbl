package com.mclauncher.multiplayer;

import com.mclauncher.multiplayer.RoomState.StateSnapshot;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
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
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 房间编排服务（单例）。负责：拉起/关闭联机引擎、下发扫描/加入指令、500ms 状态轮询、
 * 日志抓取与状态订阅。
 *
 * <p>纯 JDK：所有 Async 方法都在后台线程执行并回调；<b>不碰 JavaFX</b>，
 * UI 层拿到回调后自行 {@code Platform.runLater}。回调里的用户异常会被吞掉，
 * 绝不让监听器/调用方把后台线程打断。
 */
public final class RoomService {

    // NOTE: public_nodes 在引擎 HTTP API 里是**重复键**查询参数（每节点一条
    // &public_nodes=<urlencoded>），不是逗号分隔、也不是 JSON。引擎内部同样按
    // 每节点一条 -p 参数下发；逗号拼接会被当成单个非法节点 URL，房间建不起来。

    /** 端口文件路径：传给 A 层 launchAsync，A 层把引擎发现的 HTTP 端口写到这里。 */
    public static final Path PORT_FILE =
            Paths.get(System.getProperty("user.home"), ".gbl", "multiplayer", "terracotta.port");

    /** 订阅态轮询间隔：联机页打开时保持 500ms 跟手。 */
    private static final int POLL_MS = 500;
    /** 1.2.4：扫描/加入命令单独 20s 超时；健康 /meta 与 /state 仍走 3s。 */
    private static final int COMMAND_TIMEOUT_SECONDS = 20;
    private static final int HEALTH_TIMEOUT_SECONDS = 3;
    /** 1.2.4：同房间 + 同玩家 + 同节点集合的扫描结果缓存 5 分钟。 */
    private static final long SCAN_CACHE_TTL_MS = 5L * 60L * 1000L;
    private static final int SCAN_CACHE_MAX = 32;
    /** 无人订阅时的空闲心跳间隔：调度仍 500ms 一次，轻量跳过多余请求。 */
    private static final int IDLE_HEARTBEAT_MS = 2000;
    private static final int MAX_FAILURES = 3;
    /** 引擎自身 exception_type=3/4 是「网络受限/内网隔离」提示，中继下仍可正常联机，绝不当致命态。 */
    public static final int NETWORK_NOTICE_TYPE_RELAY = 3;
    public static final int NETWORK_NOTICE_TYPE_LAN = 4;
    /**
     * 轮询侧「引擎进程失联 / 连续 HTTP 失败」专用类型。
     * 1.1.9 之前误用 3，与引擎自身的「网络受限」提示撞号，
     * 导致 CGNAT/中继这种可正常工作的状态也进红色「异常」致命态。
     * 改用 6 与引擎提示彻底分流，真实失联仍如实报错、仍可重试。
     */
    private static final int TIMEOUT_EXCEPTION_TYPE = 6;
    /** 连续失败达到阈值后，每 3 个 tick 才真正发一次 GET /state。 */
    private static final int BACKOFF_TICKS = 3;

    private static final RoomService INSTANCE = new RoomService();

    private final TraversalProvider engine = new TraversalProvider();
    private final ExecutorService commands =
            Executors.newSingleThreadExecutor(r -> daemon(r, "gbl-mp-cmd"));
    private ScheduledExecutorService poller = newPoller();

    private volatile StateSnapshot lastState = RoomState.UNKNOWN_SNAPSHOT;
    private final AtomicLong lastIndex = new AtomicLong(-1L);
    private volatile int httpPort;
    private volatile boolean running;

    private final AtomicBoolean starting = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean fetching = new AtomicBoolean(false);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicInteger backoffTicks = new AtomicInteger();
    private final AtomicInteger tickCounter = new AtomicInteger();
    private final AtomicLong lastHeartbeat = new AtomicLong(0L);
    private final CopyOnWriteArrayList<RoomStateListener> listeners = new CopyOnWriteArrayList<>();
    private static final Map<String, CachedScan> SCAN_CACHE = new LinkedHashMap<>();
    private static final java.nio.file.Path SCAN_CACHE_FILE =
            java.nio.file.Paths.get(System.getProperty("user.home"), ".gbl", "multiplayer", "scan-cache.json");
    private static final java.util.concurrent.atomic.AtomicLong SCAN_REQUESTS =
            new java.util.concurrent.atomic.AtomicLong();
    private static volatile int lastCommandTimeoutSeconds = HEALTH_TIMEOUT_SECONDS;
    private volatile boolean forceFreshStart;
    /** QA 可注入的 HTTP 取值器：(path, timeoutSeconds) -> body；null 表示走真实 HTTP。 */
    private static volatile HttpGetter testHttpGetter;
    /** 内存扫描缓存项：快照 + 原始 /state JSON（供落盘预热）+ 写入时刻。 */
    private record CachedScan(StateSnapshot snapshot, String rawJson, long createdAt) {
    }
    /** QA mock 钩子：注入延迟引擎 / 计数代理。 */
    @FunctionalInterface
    public interface HttpGetter {
        String get(String pathWithQuery, int timeoutSeconds) throws IOException;
    }

    /** 状态变化监听。回调发生在后台线程，UI 层自行 runLater。 */
    @FunctionalInterface
    public interface RoomStateListener {
        void onState(StateSnapshot snapshot);
    }

    /** 给 A 层异步方法桥接用的内部签名。 */
    @FunctionalInterface
    private interface AsyncOp<T> {
        void run(Callback<T> callback);
    }

    private RoomService() {
        // 单例
    }

    public static RoomService get() {
        return INSTANCE;
    }

    /** QA 钩子：注入/撤销 mock HTTP（传 null 还原真实链路）。 */
    public static void setTestHttpGetter(HttpGetter getter) {
        testHttpGetter = getter;
    }

    /** QA 证据：真实发出的扫描/加入命令请求数（缓存命中不增加）。 */
    public static long scanRequestCount() {
        return SCAN_REQUESTS.get();
    }

    /** QA 证据：最近一次命令使用的超时秒数（扫描/加入应为 20）。 */
    public static int lastCommandTimeoutSeconds() {
        return lastCommandTimeoutSeconds;
    }

    /** QA 钩子：清内存缓存与计数，保证两轮探测互不污染。 */
    public static void resetScanProbeState() {
        synchronized (SCAN_CACHE) {
            SCAN_CACHE.clear();
        }
        SCAN_REQUESTS.set(0L);
        lastCommandTimeoutSeconds = HEALTH_TIMEOUT_SECONDS;
    }

    public boolean isRunning() {
        return running;
    }

    /** 当前状态快照（未连接时返回 UNKNOWN 哨兵，非 null）。 */
    public static StateSnapshot currentState() {
        return INSTANCE.lastState;
    }

    // ------------------------------------------------------------------ 生命周期

    /**
     * 安装引擎（按需）→ 启动子进程 → {@code GET /meta} 校验 → 起 500ms 轮询。
     * 幂等：已运行直接回 true；并发启动只有一个真正动手，其余回一个可读的错误。
     */
    public void startAsync(Callback<Boolean> cb) {
        if (running) {
            ok(cb, Boolean.TRUE, null);
            return;
        }
        if (!starting.compareAndSet(false, true)) {
            fail(cb, "联机引擎正在启动中，请稍候");
            return;
        }
        shuttingDown.set(false);
        commands.execute(() -> {
            try {
                // 1.2 核心修复：先探测已有引擎。上一次 GBL 运行留下的引擎进程若 HTTP /meta
                // 健康，就直接使用它；否则本次新起的进程会因单实例/端口冲突秒退，
                // currentProcess 指向死进程，界面在 HTTP 完全正常时仍误报 ex=6。
                Integer reusable = forceFreshStart ? null
                        : TraversalProvider.findReusablePort(PORT_FILE);
                if (reusable != null && reusable > 0 && reusable <= 65535) {
                    httpPort = reusable;
                    running = true;
                    consecutiveFailures.set(0);
                    backoffTicks.set(0);
                    lastIndex.set(-1L);
                    startPoller();
                    ok(cb, Boolean.TRUE, null);
                    return;
                }
                if (!engine.isInstalled()) {
                    Path installed = await(engine::ensureInstalledAsync, 600, "联机引擎安装");
                    if (installed == null) {
                        throw new IOException("联机引擎安装失败：未返回可执行文件路径");
                    }
                }
                try {
                    Path parent = PORT_FILE.toAbsolutePath().getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                } catch (Exception ignored) {
                    // 端口文件父目录建不出来不致命：A 层有自己的兜底路径
                }

                Integer port = await(c -> engine.launchAsync(PORT_FILE, c), 60, "联机引擎启动");
                if (port == null || port <= 0 || port > 65535) {
                    throw new IOException("联机引擎未返回有效的 HTTP 端口");
                }

                int previousPort = httpPort;
                httpPort = port;
                forceFreshStart = false;
                try {
                    String meta = httpGet("/meta");
                    if (meta == null || meta.isBlank()) {
                        throw new IOException("元数据响应为空");
                    }
                } catch (Throwable t) {
                    httpPort = previousPort;
                    throw new IOException("联机引擎元数据校验失败：" + msg(t), t);
                }

                running = true;
                consecutiveFailures.set(0);
                lastIndex.set(-1L);
                startPoller();
                ok(cb, Boolean.TRUE, null);
            } catch (Throwable t) {
                ok(cb, Boolean.FALSE, t instanceof Exception e ? e : new IOException(msg(t), t));
            } finally {
                starting.set(false);
            }
        });
    }

    /** 房主扫描：20s 超时 + 同参数 5 分钟内存缓存；命中直接发布缓存快照、跳过引擎调用。 */
    public void scanRoomAsync(String room, String player, List<String> publicNodes, Callback<Boolean> cb) {
        String key = cacheKey("/state/scanning", room, player, publicNodes);
        StateSnapshot cached = cachedSnapshot(key);
        if (cached != null) {
            publishCached(cached);
            ok(cb, Boolean.TRUE, null);
            return;
        }
        roomCommand("/state/scanning", room, player, publicNodes, COMMAND_TIMEOUT_SECONDS, key, cb);
    }

    /** 访客加入：与扫描同用 20s 超时，但不缓存（加入是连接动作，重放没有意义）。 */
    public void guestRoomAsync(String room, String player, List<String> publicNodes, Callback<Boolean> cb) {
        roomCommand("/state/guesting", room, player, publicNodes, COMMAND_TIMEOUT_SECONDS, null, cb);
    }

    /** 首次页面就绪后预热：只读本地 scan-cache.json，不发引擎请求、不产生副作用。 */
    public void warmScanCacheAsync() {
        commands.execute(() -> {
            try {
                if (!Files.isRegularFile(SCAN_CACHE_FILE)) {
                    return;
                }
                Object parsed = JsonLike.parse(Files.readString(SCAN_CACHE_FILE, StandardCharsets.UTF_8));
                if (!(parsed instanceof Map<?, ?> root)) {
                    return;
                }
                long now = System.currentTimeMillis();
                synchronized (SCAN_CACHE) {
                    for (Map.Entry<?, ?> e : root.entrySet()) {
                        if (!(e.getKey() instanceof String k) || !(e.getValue() instanceof Map<?, ?> item)) {
                            continue;
                        }
                        long ts = JsonLike.longOf(item.get("ts"), 0L);
                        String body = JsonLike.str(item.get("stateJson"));
                        if (body == null || ts <= 0L || now - ts > SCAN_CACHE_TTL_MS) {
                            continue;
                        }
                        StateSnapshot snap = RoomState.fromJson(body);
                        if (snap.index() >= 0L) {
                            putCached(k, snap, body, ts);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 预热失败不影响页面：缓存是可有可无的加速，绝不阻断联机
            }
        });
    }

    private static String cacheKey(String endpoint, String room, String player, List<String> nodes) {
        StringBuilder key = new StringBuilder(endpoint).append('|').append(room == null ? "" : room.trim())
                .append('|').append(player == null ? "" : player.trim());
        if (nodes != null) {
            for (String node : nodes) {
                if (node != null && !node.isBlank()) {
                    key.append('|').append(node.trim());
                }
            }
        }
        return key.toString();
    }

    private static StateSnapshot cachedSnapshot(String key) {
        if (key == null) {
            return null;
        }
        synchronized (SCAN_CACHE) {
            CachedScan hit = SCAN_CACHE.get(key);
            if (hit == null) {
                return null;
            }
            if (System.currentTimeMillis() - hit.createdAt() > SCAN_CACHE_TTL_MS) {
                SCAN_CACHE.remove(key);
                return null;
            }
            return hit.snapshot();
        }
    }

    private static void putCached(String key, StateSnapshot snap, String rawJson, long createdAt) {
        if (key == null) {
            return;
        }
        synchronized (SCAN_CACHE) {
            SCAN_CACHE.put(key, new CachedScan(snap, rawJson, createdAt));
            while (SCAN_CACHE.size() > SCAN_CACHE_MAX) {
                String oldest = SCAN_CACHE.keySet().iterator().next();
                SCAN_CACHE.remove(oldest);
            }
        }
        // 落盘只留最近 32 条，坏盘/只读盘一律忽略；重启后 warmScanCacheAsync 再读回。
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            for (Map.Entry<String, CachedScan> e : new java.util.HashMap<>(SCAN_CACHE).entrySet()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("ts", e.getValue().createdAt());
                item.put("stateJson", e.getValue().rawJson() == null ? "" : e.getValue().rawJson());
                root.put(e.getKey(), item);
            }
            Path parent = SCAN_CACHE_FILE.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(SCAN_CACHE_FILE, JsonLike.write(root), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // 缓存是加速：落盘失败绝不阻断本次扫描
        }
    }

    private static void clearScanCache() {
        synchronized (SCAN_CACHE) {
            SCAN_CACHE.clear();
        }
    }

    /** 缓存命中也通知 UI：状态机不靠 index 单调性决定「用户是否看到回执」。 */
    private void publishCached(StateSnapshot snap) {
        lastIndex.set(Math.max(lastIndex.get(), snap.index()));
        lastState = snap;
        notifyListeners(snap);
    }

    /** 1.2.4：WAITING 哨兵——关房/退房/取消后必须真实发布给 UI。 */
    private StateSnapshot idleSnapshot() {
        return new StateSnapshot("WAITING", lastIndex.get(), "", lastState.profiles(),
                lastState.difficulty(), "", 0, List.of());
    }

    /** 回到空闲：{@code GET /state/ide}（引擎端点原名就是 ide）。 */
    public void resetIdleAsync(Callback<Boolean> cb) {
        commands.execute(() -> {
            try {
                httpGet("/state/ide", HEALTH_TIMEOUT_SECONDS);
                clearScanCache();
                publish(idleSnapshot());
                ok(cb, Boolean.TRUE, null);
            } catch (Throwable t) {
                ok(cb, Boolean.FALSE, t instanceof Exception e ? e : new IOException(msg(t), t));
            }
        });
    }

    /**
     * 1.2.4 关房/退房：走可逆的 {@code GET /state/ide} 并发布 WAITING 快照，
     * 绝不吞错假成功；/state/ide 真失败时强制终止旧进程、标记下次必须新起进程，
     * 禁止复用仍健康的旧房间。shuttingDown 只挡并发，finally 必定释放。
     */
    public void shutdownAsync(Callback<Boolean> cb) {
        if (!shuttingDown.compareAndSet(false, true)) {
            fail(cb, "房间正在关闭中，请稍候再点");
            return;
        }
        commands.execute(() -> {
            try {
                Throwable resetError = null;
                try {
                    httpGet("/state/ide", HEALTH_TIMEOUT_SECONDS);
                } catch (Throwable t) {
                    resetError = t;
                }
                if (resetError == null) {
                    clearScanCache();
                    publish(idleSnapshot());
                    ok(cb, Boolean.TRUE, null);
                    return;
                }
                // 可逆关闭失败：强制终止旧进程，绝不「关不掉还报成功」。
                terminateNow();
                if (httpPort > 0 && metaHealthy(httpPort)) {
                    throw new IOException("关闭房间失败：引擎仍在运行（" + msg(resetError) + "）");
                }
                forceFreshStart = true;
                clearScanCache();
                publish(idleSnapshot());
                ok(cb, Boolean.TRUE, null);
            } catch (Throwable t) {
                ok(cb, Boolean.FALSE, t instanceof Exception e ? e : new IOException(msg(t), t));
            } finally {
                shuttingDown.set(false);
            }
        });
    }

    /** 立即强杀引擎进程并停轮询。 */
    public void terminateNow() {
        running = false;
        stopPoller();
        try {
            engine.terminateNow();
        } catch (Throwable ignored) {
            // 强杀失败不抛：调用方通常是关闭窗口路径
        }
    }

    // ------------------------------------------------------------------ 订阅 / 日志

    public void subscribe(RoomStateListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void unsubscribe(RoomStateListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    /** 并发抓日志时返回的约定值；UI 侧直接展示，不再另起线程。 */
    public static final String LOGS_IN_FLIGHT = "正在读取日志，请稍候…";

    /** 优先取引擎日志（{@code /log?fetch=true}），失败回 A 层环形缓冲。单飞。 */
    public String fetchLogs() {
        if (!fetching.compareAndSet(false, true)) {
            return LOGS_IN_FLIGHT;
        }
        try {
            if (running && httpPort > 0) {
                try {
                    return httpGet("/log?fetch=true");
                } catch (Throwable ignored) {
                    // 落到 A 层日志缓冲
                }
            }
            try {
                String log = TraversalProvider.recentLog();
                return log == null ? "" : log;
            } catch (Throwable ignored) {
                return "";
            }
        } finally {
            fetching.set(false);
        }
    }

    // ------------------------------------------------------------------ 内部实现

    private void roomCommand(String endpoint, String room, String player, List<String> publicNodes,
                             int timeoutSeconds, String cacheKey, Callback<Boolean> cb) {
        if (room == null || room.isBlank()) {
            fail(cb, "房间码不能为空");
            return;
        }
        if ((!running || httpPort <= 0) && testHttpGetter == null) {
            fail(cb, "联机引擎尚未启动");
            return;
        }
        commands.execute(() -> {
            try {
                StringBuilder url = new StringBuilder(endpoint)
                        .append("?room=").append(encode(room))
                        .append("&player=").append(encode(player == null ? "" : player));
                // 每个节点一条同名查询参数（Rocket Vec<String> = 重复键），逐个编码
                if (publicNodes != null) {
                    for (String node : publicNodes) {
                        if (node == null || node.isBlank()) {
                            continue;
                        }
                        url.append("&public_nodes=").append(encode(node.trim()));
                    }
                }
                SCAN_REQUESTS.incrementAndGet();
                lastCommandTimeoutSeconds = timeoutSeconds;
                String body = httpGet(url.toString(), timeoutSeconds);
                StateSnapshot snap = RoomState.fromJson(body);
                if (snap.index() >= 0L) {
                    if (cacheKey != null && snap.stateEnum() != RoomState.State.UNKNOWN) {
                        putCached(cacheKey, snap, body, System.currentTimeMillis());
                    }
                    publish(snap);
                }
                ok(cb, Boolean.TRUE, null);
            } catch (Throwable t) {
                ok(cb, Boolean.FALSE, t instanceof Exception e ? e : new IOException(msg(t), t));
            }
        });
    }

    /**
     * 轮询：单线程 GET /state。订阅态 500ms 跟手；无人订阅降为 2s 心跳；连续失败按 tick 退避。
     *
     * <p>1.2 核心修复：判活以 HTTP {@code /meta} 为准（200 + 非空 version）。本机
     * {@code currentProcess} 可能指向本次 JVM 拉起却因单实例秒退的短命进程，
     * 而真正提供服务的进程由上一条生命周期留下——「进程死但 HTTP 通」是正常场景，
     * 绝不能报 ex=6。只有 HTTP 连续不通才认定真失联。
     */
    private void pollTick() {
        if (!running) {
            return;
        }
        long now = System.currentTimeMillis();
        if (listeners.isEmpty()) {
            long previous = lastHeartbeat.get();
            if (now - previous < IDLE_HEARTBEAT_MS) {
                return;
            }
            lastHeartbeat.set(now);
        }
        int backoff = backoffTicks.get();
        if (backoff > 0 && tickCounter.incrementAndGet() % backoff != 0) {
            return;
        }
        String meta;
        try {
            meta = httpGet("/meta");
        } catch (Throwable t) {
            onHttpFailure(msg(t));
            return;
        }
        if (!validMeta(meta)) {
            onHttpFailure("/meta 未返回有效 version");
            return;
        }
        // HTTP 通：无论 currentProcess 是否存活，引擎都在正常服务，正常解析状态并清零失败计数。
        try {
            StateSnapshot snap = RoomState.fromJson(httpGet("/state"));
            long idx = snap.index();
            long old = lastIndex.get();
            boolean stateChanged = !Objects.equals(lastState.state(), snap.state());
            boolean indexGrew = idx > old;
            if (stateChanged || indexGrew) {
                lastState = snap;
                lastIndex.set(Math.max(old, idx));
                notifyListeners(snap);
            }
            consecutiveFailures.set(0);
            backoffTicks.set(0);
        } catch (Throwable t) {
            onHttpFailure(msg(t));
        }
    }

    /** HTTP 连续失败计数：达到阈值才认定真失联；若本次进程僵死则先终止再如实报错。 */
    private void onHttpFailure(String reason) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= 2) {
            backoffTicks.set(BACKOFF_TICKS);
        }
        if (failures >= MAX_FAILURES) {
            try {
                engine.terminateNow();
            } catch (Throwable ignored) {
                // 进程不归本 JVM 管时终止失败无妨，仍要如实报失联
            }
            publishEngineLost();
        }
    }

    /** 进程退出或连续失败到底：走与原有超时异常相同的状态路径。 */
    private void publishEngineLost() {
        running = false;
        stopPoller();
        StateSnapshot ex = new StateSnapshot(
                "EXCEPTION", lastIndex.get(), lastState.room(), lastState.profiles(),
                lastState.difficulty(), lastState.url(), TIMEOUT_EXCEPTION_TYPE, List.of());
        lastState = ex;
        notifyListeners(ex);
    }

    /** 供指令响应复用：按同样的去重规则更新并推送。 */
    private void publish(StateSnapshot snap) {
        long old = lastIndex.get();
        if (snap.index() > old || !Objects.equals(lastState.state(), snap.state())) {
            lastState = snap;
            lastIndex.set(Math.max(old, snap.index()));
            notifyListeners(snap);
        }
    }

    private void notifyListeners(StateSnapshot snap) {
        for (RoomStateListener l : listeners) {
            try {
                l.onState(snap);
            } catch (Throwable ignored) {
                // 一个监听器炸了不能影响其余监听器和轮询线程
            }
        }
    }

    private String httpGet(String pathWithQuery) throws IOException {
        return httpGet(pathWithQuery, HEALTH_TIMEOUT_SECONDS);
    }

    private String httpGet(String pathWithQuery, int timeoutSeconds) throws IOException {
        HttpGetter mock = testHttpGetter;
        if (mock != null) {
            String body = mock.get(pathWithQuery, timeoutSeconds);
            return body == null ? "" : body;
        }
        if (httpPort <= 0) {
            throw new IOException("联机引擎尚未启动");
        }
        URI uri = URI.create("http://127.0.0.1:" + httpPort + pathWithQuery);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
        try {
            HttpResponse<String> resp = HTTP.send(
                    req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = resp.statusCode();
            if (code < 200 || code >= 300) {
                throw new IOException("联机引擎返回 HTTP " + code + "（" + pathWithQuery + "）");
            }
            String body = resp.body();
            return body == null ? "" : body;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("联机引擎请求被中断（" + pathWithQuery + "）", ie);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("联机引擎请求失败（" + pathWithQuery + "）：" + msg(e), e);
        }
    }

    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private static <T> T await(AsyncOp<T> op, long seconds, String what) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        op.run((v, e) -> {
            value.set(v);
            error.set(e);
            latch.countDown();
        });
        if (!latch.await(seconds, TimeUnit.SECONDS)) {
            throw new IOException(what + "超时（" + seconds + " 秒）");
        }
        Throwable t = error.get();
        if (t != null) {
            if (t instanceof Exception ex) {
                throw ex;
            }
            throw new IOException(what + "失败：" + msg(t), t);
        }
        return value.get();
    }

    /**
     * 引擎可用性：HTTP {@code /meta} 健康为准，{@code currentProcess.isAlive()} 仅作参考，
     * 绝不再作为唯一判据（老引擎进程由上一次 JVM 启动，本机进程对象必然是死的）。
     */
    private boolean isAlive() {
        if (httpPort > 0 && metaHealthy(httpPort)) {
            return true;
        }
        try {
            return engine.isProcessAlive();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 从 /meta 响应里提取非空 version；nil / 空串 / 非 JSON 都不算健康。 */
    private static boolean validMeta(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        Matcher m = META_VERSION_RE.matcher(body);
        return m.find() && !m.group(1).isBlank();
    }

    private static final Pattern META_VERSION_RE =
            Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    /** 指定端口的 /meta 健康检查；异常一律按不健康，绝不抛给轮询线程。 */
    private static boolean metaHealthy(int port) {
        try {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/meta"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.statusCode() == 200 && validMeta(resp.body());
        } catch (Throwable t) {
            return false;
        }
    }

    private void startPoller() {
        ScheduledExecutorService p = poller;
        if (p == null || p.isShutdown()) {
            p = poller = newPoller();
        }
        try {
            p.scheduleWithFixedDelay(this::pollTick, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // 关闭竞态：忽略
        }
    }

    private void stopPoller() {
        ScheduledExecutorService p = poller;
        if (p != null && !p.isShutdown()) {
            p.shutdownNow();
        }
    }

    private static ScheduledExecutorService newPoller() {
        return Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "gbl-mp-poll"));
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** 缓存落盘用的极简 JSON 读写：只吃 Map/String/Long，坏数据一律安全降级。 */
    private static final class JsonLike {
        private JsonLike() {
        }

        static String str(Object o) {
            return o instanceof String s ? s : null;
        }

        static long longOf(Object o, long fallback) {
            if (o instanceof Number n) {
                return n.longValue();
            }
            return fallback;
        }

        static String write(Map<String, Object> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                Object v = e.getValue();
                if (v instanceof Number || v instanceof Boolean) {
                    sb.append(v);
                } else {
                    quote(sb, String.valueOf(v));
                }
            }
            return sb.append('}').toString();
        }

        static Object parse(String text) {
            // 仅解析 {"key":{"ts":123,"stateJson":"..."}, ...}；出错返回 null 由调用方降级。
            try {
                return com.mclauncher.Json.parse(text);
            } catch (Throwable t) {
                return null;
            }
        }

        private static void quote(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') {
                    sb.append('\\');
                }
                if (c == '\n' || c == '\r' || c == '\t') {
                    sb.append(' ');
                } else {
                    sb.append(c);
                }
            }
            sb.append('"');
        }
    }

    private static String msg(Throwable t) {
        if (t == null) {
            return "";
        }
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }

    private static <T> void ok(Callback<T> cb, T value, Throwable error) {
        if (cb == null) {
            return;
        }
        try {
            cb.onResult(value, error);
        } catch (Throwable ignored) {
            // 调用方自己的回调炸了不影响服务线程
        }
    }

    private static <T> void fail(Callback<T> cb, String message) {
        ok(cb, null, new IOException(message));
    }
}
