package com.mclauncher;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内容元数据（搜索 / 项目 / 版本）的「镜像 + 官方」并发竞速层（v0.9.1 二次提速）。
 *
 * <p><b>解决的问题：</b>旧逻辑是「先打国内镜像 MCIM，等它彻底失败（读超时 20s + 连接 10s）再回官方」——
 * 任何一次镜像抖动都会把界面卡在「正在加载…」长达 30~60 秒，且没有任何进度反馈。本类把镜像与官方
 * 两次请求<b>并发</b>发起，谁先回 2xx 就用谁的结果；任一方慢 / 挂都不拖累另一方，整体最坏等待被
 * 限制在 {@link #BUDGET_MS} 之内。
 *
 * <p><b>0.9.1 相比 0.9.0 的四处加固：</b>
 * <ol>
 *   <li>线程池由无界 {@code newCachedThreadPool} 改为<b>固定 8 线程有界池</b>，落败请求不再无限堆积；</li>
 *   <li>{@link JsonFetch} 新增带取消旗标的 default 重载，竞速分出胜负后给落败一路置位；
 *       配合任务入口的旗标检查，<b>仍排在线程池队列里、尚未开始的落败任务会直接跳过、连请求都不发</b>；</li>
 *   <li>整体预算 {@link #BUDGET_MS} 由 13s 降到 8s（各 Client 单请求超时同步降到 4s / 连接 3s），
 *       MCIM 实测 0.6s、官方 1.4s，8s 足够覆盖正常抖动，超时快速失败让 UI 立刻落到空态 / 缓存；</li>
 *   <li>新增<b>短 TTL 元数据缓存</b>：以官方原始 URL 为 key，命中即零网络往返直接返回，
 *       这是「再快一截」的关键（切 tab / 反复开同一详情 / 中文别名多词搜索都能受益）。</li>
 * </ol>
 *
 * <p>竞速只在「镜像地址 ≠ 官方地址」时启用（即 {@code Mirror.rewriteContent(...)} 真的改写过 URL、
 * 说明 MCIM 镜像开着）；镜像被 {@code GBL_MIRROR=off} 关掉时两个 Client 直接走官方单请求，
 * 既不竞速也不查缓存，零额外开销。
 */
final class HttpRace {

    /**
     * 有界竞速线程池：守护线程，线程名仍为 {@code gbl-json-race}。
     *
     * <p>0.9.0 用无界 cached 池，而 {@code CompletableFuture.cancel(true)} 对阻塞在
     * {@code HttpClient.send()} 的线程并不会真正中断，落败请求仍会跑满自身超时（当时 10s），
     * 高频切 tab 会攒出一堆线程和连接。改成固定 8 线程后，积压上限是「队列里等待的任务」而非
     * 无限增长的线程；再叠加单请求 4s 超时与落败旗标跳过，池不会被拖垮。
     */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "gbl-json-race");
        t.setDaemon(true);
        return t;
    });

    /**
     * 整体等待预算（毫秒）。单请求超时 4s / 连接 3s，两源都慢时会在 ~4s 处双双异常并立即结束，
     * 不会真等到 8s；任一源秒回则立刻返回。8s 只是兜底上限。
     */
    static final long BUDGET_MS = 8_000;

    /** 短 TTL 元数据缓存时长（毫秒）：同一官方 URL 60s 内重复请求直接命中，零网络往返。 */
    private static final long CACHE_TTL_MS = 60_000L;

    /** 缓存条目上限；超出时逐出写入时间最早的一条，避免无限增长。 */
    private static final int CACHE_MAX = 256;

    /**
     * 元数据缓存。key = 竞速的<b>官方原始 URL</b>（不要用镜像 URL），保证 {@code GBL_MIRROR}
     * 开关切换时 key 一致。value = 解析后的 Object + 写入时刻。
     */
    private static final ConcurrentHashMap<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    /** 缓存条目：{@code Json.parse(...)} 的结果 + 写入时刻（毫秒）。 */
    private static final class CacheEntry {
        final Object value;
        final long writtenAt;

        CacheEntry(Object value, long writtenAt) {
            this.value = value;
            this.writtenAt = writtenAt;
        }
    }

    /**
     * 一次 JSON 抓取（镜像或官方），失败抛 {@link IOException}。
     *
     * <p>{@code call(AtomicBoolean)} 是 0.9.1 新增的 <b>default 重载</b>：给实现方一个
     * 「本路已落败」的可取消句柄，落败时由 {@code race(...)} 把对应旗标置 {@code true}。
     * 默认实现忽略旗标、直接转发到无参 {@code call()}，因此本接口仍是<b>单抽象方法函数式接口</b>，
     * 所有既有的 {@code () -> ...} lambda 无需改动即可编译。
     */
    interface JsonFetch {
        /** 无参抓取（唯一的抽象方法，兼容既有 lambda）。 */
        Object call() throws IOException;

        /** 带取消旗标的抓取；默认忽略旗标，保持与旧实现完全一致的语义。 */
        default Object call(AtomicBoolean cancelled) throws IOException {
            return call();
        }
    }

    private HttpRace() {
    }

    /**
     * 兼容出口：<b>不走缓存</b>的竞速。内部转调 {@code race(String, JsonFetch, JsonFetch)}，
     * {@code cacheKey} 传 {@code null} 表示跳过缓存。保留此两参签名以兼容既有调用链。
     *
     * @param mirror   经 {@code Mirror.rewriteContent(...)} 改写后的镜像地址抓取
     * @param official 官方原始地址抓取
     * @return 先到的那份解析结果
     * @throws IOException 两源都失败 / 超时才抛（message 说人话）
     */
    static Object race(JsonFetch mirror, JsonFetch official) throws IOException {
        return race(null, mirror, official);
    }

    /**
     * 带短 TTL 缓存的竞速（0.9.1 新增重载）。
     *
     * <p>命中且未过期（{@link #CACHE_TTL_MS}，60 秒）时<b>直接返回缓存值，不发任何请求</b>；
     * 未命中才发起镜像 + 官方竞速，成功后写入缓存。<b>只缓存成功的非 null 结果</b>：
     * 竞速失败会抛 {@link IOException} 直接向外传播，不会走到写缓存；返回 null 也不缓存。
     *
     * <p><b>cacheKey 必须是官方原始 URL，不要用经 {@code Mirror.rewriteContent(...)} 改写后的
     * 镜像 URL</b>，这样 {@code GBL_MIRROR} 开关切换时 key 稳定一致。传 {@code null} / 空白
     * 即表示整场竞速跳过缓存（两参 {@link #race(JsonFetch, JsonFetch)} 走的就是这条）。
     *
     * <p><b>调用方不得修改返回值</b>：缓存的是 {@code Json.parse(...)} 解析出来的 Map / List
     * 对象本身，命中缓存时多个调用方拿到的是同一个实例。现有调用方
     * （{@code search} / {@code project} / {@code allVersions} / {@code files} / {@code categories}
     * / {@code projectTitles}）全部只读后再组装新对象，符合此约束。
     *
     * @param cacheKey 官方原始 URL；{@code null} / 空白表示跳过缓存
     * @param mirror   经 {@code Mirror.rewriteContent(...)} 改写后的镜像地址抓取
     * @param official 官方原始地址抓取
     * @return 先到的那份解析结果（或缓存值）
     * @throws IOException 两源都失败 / 超时才抛（message 说人话）
     */
    static Object race(String cacheKey, JsonFetch mirror, JsonFetch official) throws IOException {
        final String key = cacheKey;
        final boolean cacheable = key != null && !key.isBlank();
        if (cacheable) {
            CacheEntry hit = CACHE.get(key);
            if (hit != null) {
                if (System.currentTimeMillis() - hit.writtenAt < CACHE_TTL_MS) {
                    return hit.value;
                }
                CACHE.remove(key, hit);   // 过期条目先清掉，避免占位
            }
        }
        Object result = raceNow(mirror, official);
        if (cacheable && result != null) {
            putCache(key, result);
        }
        return result;
    }

    /**
     * 写入缓存：先清过期条目，再在达到 {@link #CACHE_MAX} 时逐出写入时间最早的一条。
     * 全程最多扫描 256 项，开销可忽略；用 {@code remove(k, v)} 条件删除避免并发下误删新值。
     */
    private static void putCache(String key, Object value) {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, CacheEntry> e : CACHE.entrySet()) {
            if (now - e.getValue().writtenAt >= CACHE_TTL_MS) {
                CACHE.remove(e.getKey(), e.getValue());
            }
        }
        if (CACHE.size() >= CACHE_MAX && !CACHE.containsKey(key)) {
            String oldestKey = null;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<String, CacheEntry> e : CACHE.entrySet()) {
                long t = e.getValue().writtenAt;
                if (t < oldest) {
                    oldest = t;
                    oldestKey = e.getKey();
                }
            }
            if (oldestKey != null) {
                CACHE.remove(oldestKey);
            }
        }
        CACHE.put(key, new CacheEntry(value, now));
    }

    /**
     * 真正的并发竞速：镜像与官方同时发请求，第一个成功（非抛异常）的结果胜出。
     *
     * <p>每路一个 {@link AtomicBoolean} 取消旗标：一路胜出时给另一路置位，
     * {@code finally} 里再把两路都置位（覆盖超时 / 双失败）。对<b>仍排在线程池队列里、
     * 尚未开始</b>的任务，任务入口会先检查旗标并直接跳过，连 HTTP 请求都不发——这是有界池
     * 下的关键止损。已经进入 {@code HttpClient.send(...)} 的落败请求无法被中断
     * （{@code cancel(true)} 对阻塞线程无效），但两个 Client 已把单请求超时降到 4s，
     * 它最多再活 4s 就会抛 {@code HttpTimeoutException} 释放线程与连接。
     */
    private static Object raceNow(JsonFetch mirror, JsonFetch official) throws IOException {
        final AtomicBoolean mirrorCancelled = new AtomicBoolean(false);
        final AtomicBoolean officialCancelled = new AtomicBoolean(false);

        CompletableFuture<Object> fm = CompletableFuture.supplyAsync(() -> {
            if (mirrorCancelled.get()) {
                throw new CompletionException(new IOException("竞速已结束，镜像请求跳过"));
            }
            try {
                return mirror.call(mirrorCancelled);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, POOL);
        CompletableFuture<Object> fo = CompletableFuture.supplyAsync(() -> {
            if (officialCancelled.get()) {
                throw new CompletionException(new IOException("竞速已结束，官方请求跳过"));
            }
            try {
                return official.call(officialCancelled);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, POOL);

        CompletableFuture<Object> winner = new CompletableFuture<>();
        final AtomicReference<Throwable> firstErr = new AtomicReference<>(null);
        fm.whenComplete((r, e) -> {
            if (e == null) {
                winner.complete(r);
                officialCancelled.set(true);   // 镜像先胜：通知官方一路尽快收尾
            } else {
                firstErr.compareAndSet(null, e);
            }
        });
        fo.whenComplete((r, e) -> {
            if (e == null && !winner.isDone()) {
                winner.complete(r);
                mirrorCancelled.set(true);    // 官方先胜：通知镜像一路尽快收尾
            } else if (e != null) {
                firstErr.compareAndSet(null, e);
            }
        });
        CompletableFuture.allOf(fm, fo).whenComplete((v, e) -> {
            if (!winner.isDone()) {
                Throwable c = firstErr.get();
                if (c instanceof CompletionException ce && ce.getCause() != null) {
                    c = ce.getCause();
                }
                winner.completeExceptionally(c instanceof IOException io
                        ? io : new IOException("两个数据源都暂时连不上，稍后重试"));
            }
        });

        try {
            return winner.get(BUDGET_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException tex) {
            throw new IOException("两个数据源都太慢，稍后重试");
        } catch (ExecutionException ex) {
            Throwable c = ex.getCause() == null ? ex : ex.getCause();
            if (c instanceof IOException io) {
                throw io;
            }
            throw new IOException("加载失败：" + c.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("加载被中断");
        } finally {
            // 无论胜出 / 超时 / 双失败，都通知两路收尾；仍排队未启动的任务会据此直接跳过。
            mirrorCancelled.set(true);
            officialCancelled.set(true);
            fm.cancel(true);
            fo.cancel(true);
        }
    }
}
