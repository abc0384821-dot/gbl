package com.mclauncher.multiplayer;

import com.mclauncher.multiplayer.RoomState.StateSnapshot;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 联网体检卡（后台执行，可失败，建议非门禁）。
 *
 * <p>五项检查各自独立超时（≤3s）：公网 IPv4 / CGNAT、IPv6、NAT 定性、公共节点 TCP 连通、
 * 当前连接是否走中继。任何一项失败都只置 UNKNOWN 并写 notes，<b>不抛、不阻塞、不拦截联机</b>；
 * 全部结果汇总后一次回调。对外描述只用「无中心游戏服务器 / 无需自建或租用专用服务器 /
 * 无需公网 IP」这类说法，不作任何加密或节点可信度的承诺。
 */
public final class NetworkProbe {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private static final String IPV4_ECHO = "https://api.ipify.org";
    private static final String IPV6_ECHO = "https://ipv6.ipify.org";

    /** 体检结果缓存有效期：30 秒内切页复用，不重复起 8 线程池。 */
    private static final long CACHE_TTL_MS = 30_000L;

    private static final NetworkProbe INSTANCE = new NetworkProbe();

    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong(0L);
    private final AtomicReference<Callback<ProbeReport>> latestCallback = new AtomicReference<>();
    private volatile ProbeReport cached;
    private volatile long cachedAt;

    private NetworkProbe() {
        // 单例
    }

    public static NetworkProbe get() {
        return INSTANCE;
    }

    public enum PublicIpv4 { PUBLIC, PRIVATE, UNKNOWN }

    public enum PublicIpv6 { AVAILABLE, NONE, UNKNOWN }

    public enum Cgnat { YES, NO, UNKNOWN }

    public enum NatTier { OPEN, MODERATE, STRICT, UNKNOWN }

    public enum Relay { DIRECT, RELAY, UNKNOWN }

    /** 从状态快照的 raw / profiles / difficulty 里粗判是否走中继；判不出返回 false。 */
    public static boolean isRelayed(StateSnapshot s) {
        if (s == null) {
            return false;
        }
        StringBuilder b = new StringBuilder()
                .append(String.valueOf(s.state())).append(' ')
                .append(String.valueOf(s.difficulty())).append(' ')
                .append(String.valueOf(s.url()));
        if (s.profiles() != null) {
            b.append(' ').append(String.valueOf(s.profiles().targetTuple()))
                    .append(' ').append(String.valueOf(s.profiles().easytierVersion()))
                    .append(' ').append(String.valueOf(s.profiles().raw()));
        }
        return b.toString().toLowerCase(Locale.ROOT).contains("relay");
    }

    /** 30 秒内的成功结果可直接回填；过期返回 null。 */
    public ProbeReport cachedReport() {
        ProbeReport report = cached;
        return report != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS
                ? report : null;
    }

    /**
     * 后台并发体检，汇总后回调一次。单飞 + generation：进行中的重复调用只保留最后一次
     * callback，旧 worker 的结果按 generation 丢弃，并为最后一次调用补跑一次。
     * 回调发生在后台线程，UI 层自行 runLater。
     */
    public void probeAsync(Callback<ProbeReport> cb) {
        if (cb == null) {
            return;
        }
        long gen = generation.incrementAndGet();
        ProbeReport fresh = cachedReport();
        if (fresh != null) {
            deliver(cb, fresh);
            return;
        }
        latestCallback.set(cb);
        if (!inFlight.compareAndSet(false, true)) {
            return;   // 已有 worker，最后一次 callback 已登记
        }
        startWorker(gen);
    }

    private void startWorker(long gen) {
        Thread worker = new Thread(() -> runProbe(gen), "gbl-net-probe");
        worker.setDaemon(true);
        worker.start();
    }

    private static void deliver(Callback<ProbeReport> cb, ProbeReport report) {
        if (cb == null) {
            return;
        }
        try {
            cb.onResult(report, null);
        } catch (Throwable ignored) {
            // 调用方回调炸了不影响体检线程
        }
    }

    private void runProbe(long gen) {
        List<String> notes = new ArrayList<>();
        PublicIpv4 ipv4 = PublicIpv4.UNKNOWN;
        PublicIpv6 ipv6 = PublicIpv6.UNKNOWN;
        Cgnat cgnat = Cgnat.UNKNOWN;
        NatTier natTier = NatTier.UNKNOWN;
        Relay relay = Relay.UNKNOWN;

        List<String> nodes = nodeAddresses();
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.max(2, Math.min(8, nodes.size() + 2)),
                r -> {
                    Thread t = new Thread(r, "gbl-net-probe-item");
                    t.setDaemon(true);
                    return t;
                });

        try {
            Future<Ipv4Result> f4 = pool.submit(this::probeIpv4);
            Future<Boolean> f6 = pool.submit(this::probeIpv6);
            List<Future<String>> nodeFutures = new ArrayList<>();
            for (String node : nodes) {
                nodeFutures.add(pool.submit(() -> testNode(node)));
            }

            try {
                Ipv4Result r4 = f4.get(3, TimeUnit.SECONDS);
                ipv4 = r4.kind();
                cgnat = r4.cgnat();
                notes.addAll(r4.notes());
            } catch (Exception e) {
                notes.add("公网 IPv4 未测得（不影响联机）");
            }

            try {
                Boolean has6 = f6.get(3, TimeUnit.SECONDS);
                ipv6 = Boolean.TRUE.equals(has6) ? PublicIpv6.AVAILABLE : PublicIpv6.NONE;
            } catch (Exception e) {
                notes.add("IPv6 未测得（可选，不影响联机）");
            }

            for (int i = 0; i < nodeFutures.size(); i++) {
                try {
                    String note = nodeFutures.get(i).get(3, TimeUnit.SECONDS);
                    if (note != null && !note.isBlank()) {
                        notes.add(note);
                    }
                } catch (Exception e) {
                    notes.add("节点检测超时: " + nodes.get(i));
                }
            }

            natTier = estimateNat(ipv4, cgnat, ipv6, notes);

            StateSnapshot snap = RoomService.currentState();
            if (snap == null || "UNKNOWN".equals(snap.state())) {
                notes.add("直连/中继状态未知（尚未建立房间连接）");
            } else if (isRelayed(snap)) {
                relay = Relay.RELAY;
                notes.add("当前连接经中继转发（正常联机形态，不是错误）");
            } else {
                relay = Relay.DIRECT;
                notes.add("直连/中继为粗判，实际以联机结果为准");
            }
        } catch (Throwable t) {
            // 体检本身出问题：所有项保持 UNKNOWN，只记一条 note，绝不外抛
            notes.add("体检过程发生异常：" + t.getClass().getSimpleName());
        } finally {
            pool.shutdownNow();
        }

        ProbeReport report = new ProbeReport(ipv4, ipv6, cgnat, natTier, relay, notes);
        cached = report;
        cachedAt = System.currentTimeMillis();

        long newest = generation.get();
        Callback<ProbeReport> target = null;
        synchronized (this) {
            inFlight.set(false);
            if (newest == gen) {
                target = latestCallback.getAndSet(null);
            }
        }
        if (newest == gen) {
            deliver(target, report);
            return;
        }
        // 本次结果已过期：只保留最后一次 callback，并为它补跑一次新体检。
        Callback<ProbeReport> newer = latestCallback.getAndSet(null);
        if (newer == null) {
            return;
        }
        if (inFlight.compareAndSet(false, true)) {
            startWorker(generation.get());
        } else {
            latestCallback.set(newer);
        }
    }

    // ------------------------------------------------------------------ 各检查项

    private record Ipv4Result(PublicIpv4 kind, Cgnat cgnat, List<String> notes) {
    }

    private Ipv4Result probeIpv4() {
        List<String> notes = new ArrayList<>();
        byte[] ip = parseIpv4(httpText(IPV4_ECHO));
        if (ip == null) {
            notes.add("未能获取公网 IPv4");
            return new Ipv4Result(PublicIpv4.UNKNOWN, Cgnat.UNKNOWN, notes);
        }
        if (isCgnat(ip)) {
            notes.add("检测到运营商级 NAT（CGNAT），可经中继正常联机；直连可能受限");
            return new Ipv4Result(PublicIpv4.PRIVATE, Cgnat.YES, notes);
        }
        if (isPrivate(ip) || localFirstOctets().contains(ip[0])) {
            notes.add("出口 IPv4 为私网地址");
            return new Ipv4Result(PublicIpv4.PRIVATE, Cgnat.UNKNOWN, notes);
        }
        return new Ipv4Result(PublicIpv4.PUBLIC, Cgnat.NO, notes);
    }

    /** 本机全局单播 IPv6 或 IPv6 echo 任一成功即认为可用；都失败返回 false（NONE）。 */
    private boolean probeIpv6() {
        String echoed = httpText(IPV6_ECHO);
        if (echoed != null && echoed.indexOf(':') > 0) {
            return true;
        }
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress a = addresses.nextElement();
                    if (a instanceof Inet6Address v6
                            && !v6.isLoopbackAddress()
                            && !v6.isLinkLocalAddress()
                            && !v6.isSiteLocalAddress()
                            && !v6.isMulticastAddress()
                            && !v6.isAnyLocalAddress()) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // 枚举失败按无 IPv6 处理
        }
        return false;
    }

    private static NatTier estimateNat(PublicIpv4 ipv4, Cgnat cgnat,
                                       PublicIpv6 ipv6, List<String> notes) {
        if (ipv4 == PublicIpv4.PUBLIC && cgnat == Cgnat.NO) {
            return NatTier.OPEN;
        }
        if (cgnat == Cgnat.YES || ipv4 == PublicIpv4.PRIVATE) {
            notes.add("NAT 类型为定性估计，实际连通性以联机结果为准");
            return ipv6 == PublicIpv6.AVAILABLE ? NatTier.MODERATE : NatTier.STRICT;
        }
        notes.add("NAT 类型为定性估计，实际连通性以联机结果为准");
        return NatTier.UNKNOWN;
    }

    private static List<String> nodeAddresses() {
        // 只体检用户显式配置的附加节点；引擎内置节点由引擎自己维护，配置层不硬编码
        List<String> source = MultiplayerConfig.get().customPublicNodes();
        List<String> out = new ArrayList<>();
        for (String node : source) {
            if (node != null && !node.isBlank()) {
                out.add(node.trim());
            }
        }
        return out;
    }

    /** 解析 host:port 并 TCP 连接 3s；不可达返回一条 note，可达返回 null。 */
    private static String testNode(String node) {
        String scheme = "tcp";
        String rest = node;
        int sep = rest.indexOf("://");
        if (sep > 0) {
            scheme = rest.substring(0, sep).toLowerCase(Locale.ROOT);
            rest = rest.substring(sep + 3);
        }
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            rest = rest.substring(0, slash);
        }
        String host;
        int port;
        if (rest.startsWith("[")) {
            int close = rest.indexOf(']');
            if (close < 0) {
                return "节点地址格式错误: " + node;
            }
            host = rest.substring(1, close);
            String tail = rest.substring(close);
            port = tail.startsWith(":") ? parsePort(tail.substring(1), scheme) : defaultPort(scheme);
        } else {
            int colon = rest.lastIndexOf(':');
            if (colon > 0) {
                host = rest.substring(0, colon);
                port = parsePort(rest.substring(colon + 1), scheme);
            } else {
                host = rest;
                port = defaultPort(scheme);
            }
        }
        if (host.isEmpty() || port <= 0 || port > 65535) {
            return "节点地址格式错误: " + node;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            return null;
        } catch (Exception e) {
            return "节点不可达: " + host + ":" + port;
        }
    }

    private static int parsePort(String raw, String scheme) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultPort(scheme);
        }
    }

    private static int defaultPort(String scheme) {
        return ("https".equals(scheme) || "wss".equals(scheme)) ? 443 : 80;
    }

    // ------------------------------------------------------------------ 小工具

    private static String httpText(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(
                    req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return null;
            }
            String body = resp.body();
            return body == null ? null : body.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] parseIpv4(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            InetAddress addr = InetAddress.getByName(text.trim());
            if (addr instanceof Inet4Address v4) {
                return v4.getAddress();
            }
        } catch (Exception ignored) {
            // 不是 IPv4 字面量
        }
        return null;
    }

    private static boolean isCgnat(byte[] ip) {
        // 100.64.0.0/10
        return (ip[0] & 0xFF) == 100 && ((ip[1] & 0xFF) & 0xC0) == 64;
    }

    private static boolean isPrivate(byte[] ip) {
        int a = ip[0] & 0xFF;
        int b = ip[1] & 0xFF;
        if (a == 10 || a == 127 || (a == 169 && b == 254) || a == 0 || (a == 192 && b == 168)) {
            return true;
        }
        return a == 172 && b >= 16 && b <= 31;
    }

    private static Set<Byte> localFirstOctets() {
        Set<Byte> firstOctets = new HashSet<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                Enumeration<InetAddress> addresses = nics.nextElement().getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress a = addresses.nextElement();
                    if (a instanceof Inet4Address) {
                        // 只需首个八位组参与「出口是否本机地址」的粗判
                        firstOctets.add(a.getAddress()[0]);
                    }
                }
            }
        } catch (Exception ignored) {
            // 枚举失败返回空集合
        }
        return firstOctets;
    }

}
