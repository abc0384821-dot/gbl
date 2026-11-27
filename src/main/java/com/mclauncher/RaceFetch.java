package com.mclauncher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 共享契约：双源并发竞速取最快（v0.9.2 r4 提速核心原语，纯 Java 服务层）。
 *
 * <p><b>对齐 LauncherX UseMultiSourceMirrorDownload：官方 + 国内镜像双源并发，取最快。</b>
 * 对同一份资源同时向「调用方给的官方原始 URL」与「国内镜像 / 备源 URL」发起 GET，
 * 谁先返回有效的 2xx 且响应体可用，就用谁的 {@link InputStream}；落败一路立即取消并关闭，
 * 避免连接与线程泄漏。内容文件下载链路（{@code FileFetch}）与原版游戏链路
 * （{@code AssetFetcher} / {@code LibFetcher}）共用本类，签名固定、不得改动。
 *
 * <p><b>与 {@code HttpRace} 的分工：</b>{@code HttpRace} 竞速的是「已解析完的 JSON 元数据」，
 * 自带线程池与 TTL 缓存；本类竞速的是「响应体流」，流的所有权必须明确移交给调用方
 * （{@link Winner} 实现 {@link AutoCloseable}），因此<b>不做缓存、不新建任何线程池或线程</b>，
 * 只使用传入 {@link HttpClient} 自带的执行器与 {@link CompletableFuture} 组合。
 *
 * <p><b>重定向策略（0.9.9 r4 修正）：</b>3xx <b>不再</b>判该路失败，而是<b>在该路内部自动跟随</b>
 * （最多 {@link #MAX_RACE_REDIRECTS} 跳）。这是国内镜像 BMCLAPI 取文件的正常路径——它会把
 * {@code bmclapi2.bangbang93.com/maven/...} 302 到 {@code bmclapi2.hurtmc.com/download/<hash>?name=…&s=…&e=…}
 * 的签名直链；旧口径「双源竞速时任何非 2xx 一律判该路失败（含 3xx）」把镜像这条唯一可用线路
 * 100% 判死，叠上官方 {@code libraries.minecraft.net} 在境内多数不可达，两路全灭，
 * 现场表现为「53 个依赖库 53 个都补不上、游戏起不来」。只有<b>跳数耗尽</b>或<b>非 3xx 的非 2xx</b>
 * 才判该路失败。
 *
 * <p>JDK 的 {@code followRedirects} 是 client 级配置，request 上无法覆盖，故沿用
 * {@link HttpClient.Redirect#NEVER}（{@link #newClient(int)} 即此），重定向改由 {@link #sendFollowing}
 * 手动跟：这样才能拿到<b>签名后的最终直链</b>（{@code LibFetcher} 之后要拿它发 Range 分段请求，
 * 原始 302 地址是拿不到内容的），也能逐跳关流、限制跳数、把每跳都压在同一份超时预算内，语义可控。
 * 双源竞速时仍会按需构造一个 NEVER + {@link HttpClient.Version#HTTP_1_1} 的 client <b>副本</b>
 * （复制 proxy / executor / 连接超时 / TLS / cookie / 认证器等可读配置），
 * <b>绝不修改调用方传入的 client 本身</b>；副本复用传入 client 的 executor，
 * 不新建线程池、不新建 daemon 线程。传入 client 已是 NEVER + HTTP/1.1 + 显式挂 selector 时
 * （即 {@link #newClient(int)} 的产物）直接复用其连接池，零额外开销。
 *
 * <p><b>承载文件下载的 client 硬性要求（0.9.2 r4 追加）：</b>必须是 HTTP/1.1（默认 HTTP/2 在
 * 高延迟链路会把拥塞窗口压死、吞吐掉到 KB/s，0.9.0 曾为此把文件下载从 HttpClient 换回
 * HttpURLConnection），且必须挂 {@code ProxyRoute.selector()}（官方出海 CDN 走代理选路，
 * 实测直连 13.6 KB/s vs 代理 2.06 MB/s）。<b>推荐直接调用 {@link #newClient(int)} 构造 client</b>；
 * 该 selector 按 URI 调 {@code forUrl}，官方出海域走决策、境内 MCIM 域返回 NO_PROXY，
 * 一个共享 client 天然支持两源不同线路。
 *
 * <p><b>超时口径：</b>{@code connectMs}（连接超时）与 {@code firstByteMs}（竞速预算）共同兑现为
 * 请求级 {@code HttpRequest.Builder.timeout(Duration)} 上限——JDK 的 request timeout 覆盖
 * 「连接 + 等到响应头」全过程，正是连接超时与首字节等待的实际上限；竞速总预算另由
 * {@code winner.get(firstByteMs, ...)} 严格兜底，绝不无限期阻塞。
 *
 * <p><b>退化单源：</b>备源为空 / 与主键字符串相同，只用传入 client <b>原样同步 GET</b>，
 * 并在 3xx 时手动跟随 {@code Location}（最多 {@link #MAX_SINGLE_REDIRECTS} 跳、相对地址 resolve、
 * 检测循环、每跳带超时）：此时只剩官方一条路，其 302 是正常且唯一的获取路径，判失败等于功能回归。
 * 不构造 NEVER 副本、不发起任何异步任务、不启用任何线程池；调用方统一用
 * {@link #newClient(int)} 即可，单源与双源无需准备两套 client。
 */
public final class RaceFetch {

    /** 与现有 {@code Net.fetchBytes} 一致的请求头。 */
    private static final String USER_AGENT = "GBL-Launcher";
    private static final String ACCEPT = "*/*";

    /** 单源退化时允许的最大重定向跳数：官方 CDN / Mojang 的正常 302 够用，同时防止跳转环把请求拖死。 */
    private static final int MAX_SINGLE_REDIRECTS = 5;

    /**
     * 双源竞速时<b>每一路</b>允许的最大重定向跳数。
     *
     * <p>国内镜像 BMCLAPI 对每个 {@code /maven/...} 都会 302 到 hurtmc 的签名直链（1 跳），
     * 少量资源还会有 302 → CDN 的二次跳转，留 4 跳足够覆盖正常链路；
     * 同时它也是防跳转环的硬上限——超过即判该路失败，不会把竞速预算耗在无意义的跳转上。
     */
    private static final int MAX_RACE_REDIRECTS = 4;

    /**
     * 出海 CDN 主机白名单：备源（MCIM 等国内镜像）常以 302 把文件请求弹回这些官方出海节点，
     * 跟随它只会多一跳、无加速。命中即判该路无效，把机会留给另一路。
     */
    private static final String[] OVERSEAS_CDN_HOSTS = {
            "cdn.modrinth.com",
            "media.modrinth.com",
            "edge.forgecdn.net",
            "media.forgecdn.net",
            "api.modrinth.com",
            "api.curseforge.com",
    };

    /**
     * NEVER 副本缓存：调用方传入的 HttpClient 通常是长生命周期单例，按实例复用副本即可，
     * 避免每次竞速都新建连接池、重复 TLS 握手。key / value 均为强引用，条目数等于不同 client 数，有限。
     */
    private static final ConcurrentHashMap<HttpClient, HttpClient> NEVER_CLIENTS =
            new ConcurrentHashMap<>();

    /** 竞速胜出者。调用方负责 close()。 */
    public record Winner(String url, InputStream stream, long contentLength, boolean acceptRanges)
            implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (stream != null) stream.close();
        }
    }

    /**
     * 一路竞速「跟完重定向后的最终落点」。
     *
     * @param url  真正拿到 {@code resp} 的那个 URL（可能是原始 URL，也可能是 302 链末端签名直链）
     * @param resp 该 URL 的响应；3xx 且跳数耗尽时也可能是 3xx（留给上层读 Location 生成错误文案）
     */
    private record Hop(String url, HttpResponse<InputStream> resp) {}

    /** 一路竞速的状态：原始 URL、异步跟随链 future、已落地的最终位置、以及该路终态（含 HTTP 状态码）。 */
    private static final class Lane {
        final String url;
        final CompletableFuture<Hop> future;
        final AtomicReference<Hop> landed = new AtomicReference<>(null);
        final AtomicReference<String> outcome = new AtomicReference<>(null);

        Lane(String url, CompletableFuture<Hop> future) {
            this.url = url;
            this.future = future;
        }
    }

    private RaceFetch() {
    }

    /**
     * 构造适合文件下载的 HttpClient：强制 HTTP/1.1 + 出海线路自动选路 + 不跟随重定向。
     *
     * <p>HTTP/1.1 是硬要求：默认 HTTP/2 在高延迟链路会把拥塞窗口压死、吞吐掉到 KB/s
     * （0.9.0 曾因此把文件下载从 HttpClient 换回 HttpURLConnection）。
     * {@code ProxyRoute.selector()} 按 URI 调 {@code forUrl}，官方出海域走代理决策、
     * 境内 MCIM 域（不在出海域集合）返回 NO_PROXY，因此<b>一个共享 client 天然支持两源不同线路</b>，
     * 不必为两源各建一个 client。
     *
     * @param connectMs 连接超时（毫秒）；非正数按 1ms 处理，避免 Duration 非法 / 无限等待
     * @return 可直接传给 {@link #race(String, String, int, int, HttpClient)} 的 HttpClient
     */
    public static HttpClient newClient(int connectMs) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(ProxyRoute.selector())
                .connectTimeout(Duration.ofMillis(Math.max(1L, connectMs)))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 对同一资源同时向 primary 与 secondary 发 GET，先返回 2xx 且响应体可用者胜出；
     * 落败一路立即取消并关闭，避免连接泄漏。
     *
     * <p><b>双源竞速时 3xx 在本路内部自动跟随</b>（最多 {@link #MAX_RACE_REDIRECTS} 跳，这是国内镜像
     * BMCLAPI 302 到 hurtmc 签名直链的正常取文件路径）；只有<b>跳数耗尽</b>、
     * 或<b>非 3xx 的非 2xx</b>（4xx / 5xx / 超时 / IO 异常）才判该路失败，让另一路胜出。
     * {@link HttpClient.Redirect#NEVER} 保证 JDK 不自动跟随，跳转统一由 {@link #sendFollowing} 手动推进，
     * 从而能逐跳关流、控跳数、并把最终签名直链作为 {@link Winner#url()} 交出。
     * 两路都失败时抛 {@link IOException}，message 里带两路的状态码 / 异常，便于排查。
     * <b>单源退化不适用此口径</b>：只剩官方一条路时其 302 是正常获取路径，由 {@code raceSingle}
     * 用传入 client 同步手动跟随（最多 {@link #MAX_SINGLE_REDIRECTS} 跳），避免误杀官方跳转。
     *
     * <p><b>client 硬性要求：</b>必须为 {@link HttpClient.Version#HTTP_1_1} 且已挂
     * {@code ProxyRoute.selector()}（否则会把 0.9.0 修过的 HTTP/2 吞吐回归与「官方 CDN 纯直连慢线」
     * 带回来）。<b>推荐直接调用 {@link #newClient(int)} 构造 client</b>；若传入其它 client，
     * 双源路径会自动改用 NEVER + HTTP/1.1 副本，复制其 proxy / executor 等配置，
     * 并在其未挂 selector 时兜底补上 {@code ProxyRoute.selector()}。
     *
     * @param primary      主源 URL（必填，调用方给的原始官方 URL）
     * @param secondary    备源 URL（可为 null / 空 / 与 primary 相同 → 退化为单源）
     * @param connectMs    连接超时（毫秒）
     * @param firstByteMs  竞速阶段预算（毫秒）：超时内两路都无有效 2xx 则抛 IOException
     * @param client       调用方提供的 HttpClient（须 HTTP/1.1 + ProxyRoute.selector()，推荐 RaceFetch.newClient()）
     */
    public static Winner race(String primary, String secondary, int connectMs, int firstByteMs,
                              HttpClient client) throws IOException {
        if (primary == null || primary.isBlank()) {
            throw new IllegalArgumentException("primary 主源 URL 不能为 null 或空白");
        }
        if (client == null) {
            throw new IllegalArgumentException("HttpClient 不能为 null");
        }

        // JDK 请求级 timeout 覆盖「连接 + 等到响应头」；两参数取较大者，且至少 1ms 防止 0/负值。
        final int budgetMs = Math.max(1, Math.max(connectMs, firstByteMs));
        final int waitMs = Math.max(1, firstByteMs);

        // 退化为单源：同步直发，不构造 NEVER 副本、不启用任何异步任务 / 线程池。
        if (secondary == null || secondary.isBlank() || secondary.equals(primary)) {
            return raceSingle(primary, client, budgetMs);
        }
        return raceDual(primary, secondary, client, budgetMs, waitMs);
    }

    /**
     * 单源退化路径：只用传入 client <b>原样同步 GET</b>，自己手动跟随 3xx，流的所有权交给 {@link Winner}。
     *
     * <p><b>不套用双源的「任何非 2xx 一律判败」口径</b>：这里没有「另一路」可让，官方 CDN /
     * Mojang / BMCLAPI 的 302 是正常且唯一的获取路径，判失败等于功能回归。两个调用组统一使用
     * {@link #newClient(int)}（{@link HttpClient.Redirect#NEVER}），故本方法在 client 返回 3xx 时
     * 按 {@code Location} 同步跟随；若传入 client 本身是 NORMAL，JDK 已跟随完、拿到的是 2xx，
     * 跟随循环自然不会触发，保持兼容、不强判 NEVER。
     *
     * <p>最多跟随 {@link #MAX_SINGLE_REDIRECTS} 跳；相对 Location 用 {@code URI.resolve(...)} 处理；
     * 同一 URL 重复出现即判循环；每跳都带 request timeout，且有整体截止时间，绝不无限等待。
     * 最终 {@link Winner} 的 url / stream / contentLength / acceptRanges 一律取自<b>最终 2xx 响应</b>。
     * 只用传入的 client，不新建 client、不启线程池、不引入 JavaFX 依赖。
     */
    private static Winner raceSingle(String url, HttpClient client, int budgetMs) throws IOException {
        String currentUrl = url;
        final Set<String> visited = new HashSet<>();
        // 整体截止时间 = 每跳预算 × (最大跳数 + 1 次原始请求)，保证 302 链不会把总等待拖成无限。
        final long deadlineNs = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos((long) budgetMs * (MAX_SINGLE_REDIRECTS + 1));
        HttpResponse<InputStream> resp = null;
        boolean handedOff = false;
        int lastStatus = 0;   // 记录上一跳状态码，供循环 / 超时 / IO 异常的排查信息使用
        try {
            for (int hop = 0; hop <= MAX_SINGLE_REDIRECTS; hop++) {
                if (!visited.add(currentUrl)) {
                    throw new IOException("单源重定向循环: " + currentUrl
                            + "（上一跳 HTTP " + lastStatus + "）");
                }
                long remainMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime());
                if (remainMs <= 0) {
                    throw new IOException("单源重定向超时: " + currentUrl
                            + "（上一跳 HTTP " + lastStatus + "）");
                }
                int perHopMs = (int) Math.max(1L, Math.min((long) budgetMs, remainMs));

                // 上一跳的 3xx 响应体必须关闭，避免连接泄漏。
                if (resp != null) {
                    closeQuietly(resp.body());
                    resp = null;
                }
                HttpRequest req = newGetChecked(currentUrl, perHopMs);
                try {
                    resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                } catch (IOException ex) {
                    throw new IOException("单源请求 IO 异常: " + currentUrl
                            + "（" + messageOf(ex) + "，上一跳 HTTP " + lastStatus + "）", ex);
                }

                int code = resp.statusCode();
                lastStatus = code;
                if (code >= 200 && code < 300) {
                    // NORMAL client 会自动跟随：resp.uri() 才是真正最终 2xx 的 URL。
                    URI finalUri = resp.uri();
                    String finalUrl = finalUri == null ? currentUrl : finalUri.toString();
                    Winner w = toWinner(finalUrl, resp);
                    if (w == null) {
                        throw new IOException("单源响应体不可用: HTTP " + code + " " + currentUrl);
                    }
                    handedOff = true;   // 流已移交 Winner，由调用方 close()
                    return w;
                }
                if (code < 300 || code >= 400) {
                    throw new IOException("单源请求失败: HTTP " + code + " " + currentUrl);
                }
                String location = resp.headers().firstValue("Location").orElse(null);
                if (location == null || location.isBlank()) {
                    throw new IOException("单源重定向缺少 Location: HTTP " + code + " " + currentUrl);
                }
                if (hop == MAX_SINGLE_REDIRECTS) {
                    throw new IOException("单源重定向超过 " + MAX_SINGLE_REDIRECTS + " 跳: HTTP "
                            + code + " " + currentUrl + " → " + location);
                }
                try {
                    currentUrl = URI.create(currentUrl).resolve(location.trim()).toString();
                } catch (IllegalArgumentException ex) {
                    throw new IOException("单源重定向 Location 非法: HTTP " + code + " "
                            + currentUrl + " → " + location, ex);
                }
            }
            throw new IOException("单源重定向超过 " + MAX_SINGLE_REDIRECTS + " 跳: " + currentUrl
                    + "（HTTP " + lastStatus + "）");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("单源请求被中断：" + currentUrl, ex);
        } finally {
            // 只有未成功移交 Winner 时才关最后一跳的流；成功时流归 Winner，绝不在这里关。
            if (!handedOff && resp != null) {
                closeQuietly(resp.body());
            }
        }
    }

    /**
     * 双源并发竞速：两路各自「异步发出 + 内部跟完 3xx」，第一个「有效 2xx」胜出。
     * 无效 / 后到 / 超时 / 双失败的所有响应流都在 {@code whenComplete} 与 {@code finally} 里关闭。
     */
    private static Winner raceDual(String primary, String secondary, HttpClient client,
                                   int budgetMs, int waitMs) throws IOException {
        final HttpClient neverClient = neverRedirectClient(client);

        // sendFollowing 内部已把「URL 非法 / 建请求失败」折成 failedFuture，不会抛出来，
        // 因此两路一定都已接管，不存在「一路已发出、另一路构造失败」的在途泄漏。
        final CompletableFuture<Hop> fp =
                sendFollowing(neverClient, primary, budgetMs, MAX_RACE_REDIRECTS);
        final CompletableFuture<Hop> fs =
                sendFollowing(neverClient, secondary, budgetMs, MAX_RACE_REDIRECTS);

        final Lane lp = new Lane(primary, fp);
        final Lane ls = new Lane(secondary, fs);
        final List<Lane> lanes = List.of(lp, ls);
        final CompletableFuture<Winner> winner = new CompletableFuture<>();

        for (Lane lane : lanes) {
            lane.future.whenComplete((hop, err) -> {
                if (err != null) {
                    // 含 cancel(true) 触发的 CancellationException：记该路终态、无需关流。
                    lane.outcome.set(lane.url + " 请求异常：" + messageOf(err));
                    return;
                }
                if (hop == null) {
                    lane.outcome.set(lane.url + " 返回空响应");
                    return;
                }
                lane.landed.set(hop);
                // 用「跟完 302 之后的最终 URL」构造 Winner：LibFetcher 随后要拿这个 URL 发 Range
                // 分段请求，必须是签名后的直链，原始 302 地址取不到内容。
                Winner w = toWinner(hop.url(), hop.resp());
                if (w == null) {
                    // 跳数耗尽的 3xx / 4xx / 5xx：立即关流并把机会留给另一路。
                    lane.outcome.set(describeInvalid(hop.url(), hop.resp()));
                    closeQuietly(hop.resp().body());
                    return;
                }
                lane.outcome.set("HTTP " + hop.resp().statusCode() + "（有效 2xx）");
                if (winner.complete(w)) {
                    cancelLosers(lanes, lane, w.stream());
                } else {
                    // 已有更早的胜出者，本路后到的流立即关闭，绝不泄漏。
                    closeQuietly(hop.resp().body());
                }
            });
        }

        // 两路都结束（含异常 / 取消）且仍无有效胜出 → 立刻失败，不必等满竞速预算。
        // message 同时给出两路的状态码 / 异常，便于排查「BMCLAPI 302 跳数异常」这类场景。
        CompletableFuture.allOf(lp.future, ls.future).whenComplete((v, e) -> {
            if (!winner.isDone()) {
                winner.completeExceptionally(new IOException(
                        "双源竞速失败（两路均未返回有效 2xx）：主源[" + primary + "] "
                                + outcomeOf(lp) + "；备源[" + secondary + "] " + outcomeOf(ls)));
            }
        });

        Winner won = null;
        try {
            won = winner.get(waitMs, TimeUnit.MILLISECONDS);
            return won;
        } catch (TimeoutException tex) {
            throw new IOException("双源竞速超时: " + waitMs + "ms 内两路均未返回有效 2xx（"
                    + primary + " / " + secondary + "）");
        } catch (ExecutionException ex) {
            Throwable c = ex.getCause() == null ? ex : ex.getCause();
            if (c instanceof IOException io) {
                throw io;
            }
            throw new IOException("双源竞速失败: " + messageOf(c), c);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("双源竞速被中断");
        } finally {
            // 兜底：胜出 / 超时 / 双失败都取消两路，并关闭所有「非胜出」响应流。
            // 胜出流归调用方，绝不在这里关闭；已完成 future 的 cancel 无副作用。
            final InputStream keep = won == null ? null : won.stream();
            for (Lane lane : lanes) {
                lane.future.cancel(true);
                Hop hop = lane.landed.get();
                if (hop != null && hop.resp().body() != keep) {
                    closeQuietly(hop.resp().body());
                }
            }
        }
    }

    /**
     * 发起一次 GET，并在<b>本路内部</b>异步跟随 3xx，直到拿到「最终落点」或跳数耗尽。
     *
     * <p><b>为什么必须跟随（0.9.9 r4 修复的根因）：</b>国内镜像
     * {@code bmclapi2.bangbang93.com/maven/...} 现在对<b>每一个依赖库</b>都回 302，
     * {@code Location} 指向 {@code bmclapi2.hurtmc.com/download/<hash>?name=…&s=…&e=…} 的签名直链。
     * 旧口径「竞速时任何非 2xx 一律判该路失败」等于把镜像这条唯一可用线路 100% 判死，
     * 官方 {@code libraries.minecraft.net} 在境内又多不可达 → 两路全灭 → 依赖库一个都补不上。
     * 所以 3xx 必须在本路内部跟到底，并把<b>签名后的最终直链</b>交出去。
     *
     * <p>纯 {@code sendAsync} + {@code thenCompose} 链式推进：不阻塞调用线程、不新建线程池、不 new 线程。
     * 中转的 3xx 响应体立刻关闭（它没有内容，不关会白占连接）；<b>跳数耗尽那一跳不关</b>，
     * 交给上层 {@code describeInvalid} 读状态码 / Location 生成可读错误后再统一关闭。
     * 任何异常都折进 future，绝不 swallow 成静默成功。
     *
     * @param client   发起请求的 client（应为 NEVER 策略，重定向由本方法手动推进）
     * @param url      本次请求的 URL
     * @param budgetMs 单跳请求预算（覆盖连接 + 等响应头），非正数按 1ms 兜底
     * @param hopsLeft 还允许跟随的跳数；0 表示这一跳若是 3xx 就直接当最终落点返回（判该路无效）
     */
    private static CompletableFuture<Hop> sendFollowing(HttpClient client, String url,
                                                        int budgetMs, int hopsLeft) {
        final HttpRequest req;
        try {
            req = newGetChecked(url, budgetMs);
        } catch (IOException ex) {
            // 建请求就失败（URL 非法）：该路直接判败，不影响另一路。
            return CompletableFuture.failedFuture(ex);
        }
        return client.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream())
                .thenCompose(resp -> {
                    final int code = resp.statusCode();
                    final boolean redirect = code >= 300 && code < 400;
                    final String location = resp.headers().firstValue("Location").orElse(null);
                    if (!redirect || hopsLeft <= 0 || location == null || location.isBlank()) {
                        // 2xx / 4xx / 5xx，或「跳数耗尽 / 缺 Location」的 3xx：原样交回上层判定。
                        return CompletableFuture.completedFuture(new Hop(url, resp));
                    }
                    // 这一跳只是中转：体里没有内容，先关掉再跟下一跳，避免连接被白占。
                    closeQuietly(resp.body());
                    final String next;
                    try {
                        // 相对 Location 也要能解析（RFC 允许），故统一走 URI.resolve。
                        next = URI.create(url).resolve(location.trim()).toString();
                    } catch (IllegalArgumentException ex) {
                        return CompletableFuture.<Hop>failedFuture(new IOException(
                                "重定向 Location 非法: HTTP " + code + " " + url + " → " + location, ex));
                    }
                    return sendFollowing(client, next, budgetMs, hopsLeft - 1);
                });
    }

    /** 取某路的终态描述（含 HTTP 状态码 / 异常），供双失败的 IOException message 使用。 */
    private static String outcomeOf(Lane lane) {
        String s = lane.outcome.get();
        return s == null || s.isBlank() ? "无结果（未完成 / 已取消）" : s;
    }

    /** 胜出后立刻取消落败 future，并尽力关闭落败响应已到达的 body 流（绝不触碰胜出流）。 */
    private static void cancelLosers(List<Lane> lanes, Lane winLane, InputStream keep) {
        for (Lane lane : lanes) {
            if (lane == winLane) {
                continue;
            }
            lane.future.cancel(true);
            Hop hop = lane.landed.get();
            if (hop != null && hop.resp().body() != keep) {
                closeQuietly(hop.resp().body());
            }
        }
    }

    /**
     * 构造 NEVER + HTTP/1.1 副本：只有传入 client 已同时满足 NEVER、HTTP/1.1、
     * 且显式挂了 proxy selector 时才直接复用（{@link #newClient(int)} 生成的就是这种，零额外开销）；
     * 否则构造副本，逐项复制 proxy / executor / 连接超时 / TLS / cookie / 认证器，
     * 强制 {@link HttpClient.Version#HTTP_1_1}（避免默认 HTTP/2 在高延迟链路压死拥塞窗口），
     * 并在调用方没挂 selector 时兜底补上 {@code ProxyRoute.selector()}（避免官方 CDN 退回纯直连慢线）。
     * executor 直接复用调用方自带执行器（未显式指定时沿用 JDK 默认，不另行创建线程池）。
     * 绝不改动传入 client 本身。
     */
    private static HttpClient neverRedirectClient(HttpClient client) {
        if (client.followRedirects() == HttpClient.Redirect.NEVER
                && client.version() == HttpClient.Version.HTTP_1_1
                && client.proxy().isPresent()) {
            return client;
        }
        return NEVER_CLIENTS.computeIfAbsent(client, src -> {
            HttpClient.Builder b = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .version(HttpClient.Version.HTTP_1_1)
                    .sslContext(src.sslContext())
                    .sslParameters(src.sslParameters());
            src.connectTimeout().ifPresent(b::connectTimeout);
            src.proxy().ifPresentOrElse(b::proxy, () -> b.proxy(ProxyRoute.selector()));
            src.executor().ifPresent(b::executor);
            src.cookieHandler().ifPresent(b::cookieHandler);
            src.authenticator().ifPresent(b::authenticator);
            return b.build();
        });
    }

    /** 构造 GET 请求：UA / Accept 与现有网络层一致；request timeout 兑现连接 + 首字节预算。 */
    private static HttpRequest newGetChecked(String url, int budgetMs) throws IOException {
        try {
            return HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(1L, budgetMs)))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", ACCEPT)
                    .GET()
                    .build();
        } catch (IllegalArgumentException ex) {
            throw new IOException("竞速 URL 非法：" + url, ex);
        }
    }

    /**
     * 判定响应是否可作为胜出者：仅 2xx 且 body 非 null 有效。
     * 3xx 已在 {@link #sendFollowing} 里于本路内部跟完，走到这里的 3xx 只可能是跳数耗尽，按无效处理。
     */
    private static Winner toWinner(String url, HttpResponse<InputStream> resp) {
        if (resp == null) {
            return null;
        }
        int code = resp.statusCode();
        if (code < 200 || code >= 300) {
            return null;
        }
        InputStream body = resp.body();
        if (body == null) {
            return null;
        }
        return new Winner(url, body, contentLength(resp), acceptRanges(resp));
    }

    /** 生成失败原因（中文）：3xx 到达这里说明本路跟随到跳数上限仍未落地。 */
    private static String describeInvalid(String url, HttpResponse<InputStream> resp) {
        if (resp == null) {
            return url + " 返回空响应";
        }
        int code = resp.statusCode();
        if (code >= 300 && code < 400) {
            String location = resp.headers().firstValue("Location").orElse("");
            if (isOverseasCdn(location)) {
                return url + " 302 出海慢线判无效（HTTP " + code + " → " + location + "）";
            }
            // 3xx 只可能在跳数耗尽 / 缺 Location 时才落回上层：跳转环或链路过长的净效果等同失败。
            return url + " 重定向超过 " + MAX_RACE_REDIRECTS + " 跳，判无效（最后 HTTP "
                    + code + " → " + location + "）";
        }
        return url + " 返回 HTTP " + code + "（非 2xx）";
    }

    /** Content-Length 取 long；缺失 / 非法一律 -1。 */
    private static long contentLength(HttpResponse<InputStream> resp) {
        Optional<String> raw = resp.headers().firstValue("Content-Length");
        if (raw.isEmpty()) {
            return -1L;
        }
        try {
            return Long.parseLong(raw.get().trim());
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    /** Accept-Ranges 是否含 bytes（大小写不敏感，兼容多值头）。 */
    private static boolean acceptRanges(HttpResponse<InputStream> resp) {
        for (String value : resp.headers().allValues("Accept-Ranges")) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains("bytes")) {
                return true;
            }
        }
        return false;
    }

    /** Location 的 host 是否命中出海 CDN 白名单（精确或子域，大小写不敏感）。 */
    private static boolean isOverseasCdn(String location) {
        if (location == null || location.isBlank()) {
            return false;
        }
        final String host;
        try {
            String parsed = URI.create(location.trim()).getHost();
            if (parsed == null) {
                return false;   // 相对 Location 无 host，按非出海处理
            }
            host = parsed.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        for (String cdn : OVERSEAS_CDN_HOSTS) {
            if (host.equals(cdn) || host.endsWith("." + cdn)) {
                return true;
            }
        }
        return false;
    }

    /** 尽力关闭流；清理异常（含 Error）一律吞掉，绝不影响竞速主流程。 */
    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Throwable ignored) {
            // 落败 / 无效响应的清理失败与主流程无关
        }
    }

    /** 把异常压成一行人话，供 IOException 的 message 使用。 */
    private static String messageOf(Throwable t) {
        if (t == null) {
            return "未知原因";
        }
        String name = t.getClass().getSimpleName();
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? name : name + ": " + msg;
    }
}
