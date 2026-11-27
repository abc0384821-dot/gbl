package com.mclauncher.ui;

import com.mclauncher.AccountStore;
import com.mclauncher.Json;
import com.mclauncher.SkinRender;
import com.mclauncher.SkinStore;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 1.3.1：离线联机「玩家列表头像」旁路缓存。
 *
 * <p>只服务 UI 头像，不碰联机引擎协议、不改 {@code RoomState.StateSnapshot.players}（仍是 List&lt;String&gt;）。
 * key = 规范化玩家名（trim + 小写），{@link ConcurrentHashMap} 保证并发安全。
 *
 * <p>加载优先级（抄 HMCL {@code Skin.load(String username)} + {@code TexturesLoader.getDefaultSkin(UUID)}）：
 * ① 玩家名对应本机账号 → 直接用 {@link SkinStore#skinBytes}（本地 IO，最快）；
 * ② 按名字从皮肤站 CSL 拉（{@code /csl/<name>.json} → textures.default/slim.hash → {@code /textures/<hash>}）；
 * ③ 兜底：按名字 UUID 稳定选一张内置默认皮肤，保证「同一个人头像稳定」。
 *
 * <p>IO 全在 worker 线程；缓存回填与回调一律 {@code Platform.runLater} 回 FX 线程。
 * 任何失败静默降级（留 {@code .mp-player-dot} 绿点占位），绝不把异常抛到渲染线程。
 */
public final class RoomSkinCache {

    /** 可配置 CSL 根地址：系统属性 -Dgbl.room.csl=... 覆盖，默认 LittleSkin。 */
    private static final String CSL_BASE = System.getProperty("gbl.room.csl", "https://littleskin.cn");
    private static final int TIMEOUT_MS = 4000;

    private static final ConcurrentHashMap<String, byte[]> CACHE = new ConcurrentHashMap<>();
    /** 已经尝试过加载的名字：失败也记入，避免每帧重复网络 IO。 */
    private static final ConcurrentHashMap<String, Boolean> ATTEMPTED = new ConcurrentHashMap<>();

    private RoomSkinCache() {
        // 工具类，禁止实例化
    }

    /** 规范化玩家名：trim + 小写（Locale.ROOT，避免土耳其语 i 问题）。 */
    static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /** QA 钩子：按名字塞一张皮肤字节，绕过网络。 */
    public static void putRoomSkinForTests(String name, byte[] png) {
        if (name == null || png == null) {
            return;
        }
        CACHE.put(normalize(name), png);
    }

    /** QA 钩子：该名字是否已有缓存。 */
    public static boolean hasRoomSkinForTests(String name) {
        return CACHE.containsKey(normalize(name));
    }

    /** QA 钩子：返回该名字缓存皮肤渲染出的头图（64×64 BufferedImage），供像素断言；无缓存返回 null。 */
    public static BufferedImage getRoomSkinHeadForTests(String name) {
        byte[] png = CACHE.get(normalize(name));
        if (png == null) {
            return null;
        }
        try {
            BufferedImage skin = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
            if (skin == null || skin.getWidth() < 48 || skin.getHeight() < 16) {
                return null;
            }
            int f = 8;
            BufferedImage out = new BufferedImage(f * 8, f * 8, BufferedImage.TYPE_INT_ARGB);
            int height = skin.getHeight();
            for (int y = 0; y < f; y++) {
                for (int x = 0; x < f; x++) {
                    int argb = skin.getRGB(8 + x, 8 + y);
                    if (height >= 64) {
                        int hat = skin.getRGB(40 + x, 8 + y);
                        if ((hat & 0xFF000000) != 0) {
                            argb = hat;
                        }
                    }
                    for (int dy = 0; dy < 8; dy++) {
                        for (int dx = 0; dx < 8; dx++) {
                            out.setRGB(x * 8 + dx, y * 8 + dy, argb);
                        }
                    }
                }
            }
            return out;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /** 取缓存（UI 在 FX 线程读，命中即可直接显示）。 */
    public static byte[] get(String name) {
        return CACHE.get(normalize(name));
    }

    /** 清缓存：换房 / 退出本页时调用，避免上一个房间的头像串到下一个房间。 */
    public static void clear() {
        CACHE.clear();
        ATTEMPTED.clear();
    }

    /**
     * 异步按名字加载皮肤；命中缓存立即回调，否则 worker 线程做 IO，
     * 完成后缓存回填 + {@code Platform.runLater} 回调。失败静默（回调 bytes 为 null）。
     *
     * @param name    玩家名
     * @param onDone  完成回调（在 FX 线程执行），参数为皮肤字节或 null
     */
    public static void loadAsync(String name, Consumer<byte[]> onDone) {
        String key = normalize(name);
        if (key.isEmpty()) {
            fire(onDone, null);
            return;
        }
        byte[] hit = CACHE.get(key);
        if (hit != null) {
            fire(onDone, hit);
            return;
        }
        if (ATTEMPTED.putIfAbsent(key, Boolean.TRUE) != null && onDone != null) {
            // 正在加载或已失败过：不重复 IO。失败过的这次仍回 null，让 UI 保持绿点占位。
            fire(onDone, null);
            return;
        }
        final String who = name;
        Thread worker = new Thread(() -> {
            byte[] bytes = loadFromWorker(who, key);
            if (bytes != null) {
                CACHE.put(key, bytes);
            }
            fire(onDone, bytes);
        }, "gbl-room-skin-" + key);
        worker.setDaemon(true);
        worker.start();
    }

    /** 回调统一回 FX 线程。 */
    private static void fire(Consumer<byte[]> onDone, byte[] bytes) {
        if (onDone == null) {
            return;
        }
        javafx.application.Platform.runLater(() -> onDone.accept(bytes));
    }

    /** worker：本机账号 → CSL → 稳定默认头。 */
    private static byte[] loadFromWorker(String name, String key) {
        // ① 本机同名账号
        try {
            AccountStore store = AccountStore.load();
            if (store != null) {
                for (AccountStore.Account acc : store.accounts()) {
                    if (acc != null && key.equals(normalize(acc.name))) {
                        byte[] local = SkinStore.skinBytes(acc);
                        if (local != null && local.length > 0) {
                            return local;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 本地读取失败 → 继续走 CSL
        }
        // ② CSL
        byte[] remote = loadFromCsl(name);
        if (remote != null) {
            return remote;
        }
        // ③ 按名字 UUID 稳定选默认头（同一个人始终同一张）
        return defaultForName(key);
    }

    /** 抄 HMCL TexturesLoader.getDefaultSkin(UUID)：名字稳定 hash → 稳定默认皮肤。 */
    private static byte[] defaultForName(String key) {
        try {
            UUID uuid = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
            boolean slim = (uuid.getLeastSignificantBits() & 1L) != 0;
            return SkinStore.defaultBytes(slim ? "alex" : "steve");
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** CSL：GET /csl/<name>.json → textures.default|slim.hash → GET /textures/<hash>。失败返回 null。 */
    private static byte[] loadFromCsl(String name) {
        HttpURLConnection jsonConn = null;
        try {
            String jsonUrl = CSL_BASE + "/csl/" + URLEncoder.encode(name, StandardCharsets.UTF_8) + ".json";
            jsonConn = open(jsonUrl);
            if (jsonConn == null) {
                return null;
            }
            byte[] body = readLimit(jsonConn.getInputStream(), 128 * 1024);
            Object parsed = Json.parse(new String(body, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> root)) {
                return null;
            }
            Object texturesObj = root.get("textures");
            if (!(texturesObj instanceof Map<?, ?> textures)) {
                return null;
            }
            String hash = layerHash(textures, "default");
            if (hash == null) {
                hash = layerHash(textures, "slim");
            }
            if (hash == null || hash.isBlank()) {
                return null;
            }
            HttpURLConnection texConn = open(CSL_BASE + "/textures/" + hash);
            if (texConn == null) {
                return null;
            }
            try {
                return readLimit(texConn.getInputStream(), 4 * 1024 * 1024);
            } finally {
                texConn.disconnect();
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (jsonConn != null) {
                jsonConn.disconnect();
            }
        }
    }

    private static String layerHash(Map<?, ?> textures, String layer) {
        Object o = textures.get(layer);
        if (!(o instanceof Map<?, ?> layerMap)) {
            return null;
        }
        Object hash = layerMap.get("hash");
        return hash == null ? null : String.valueOf(hash);
    }

    /** 打开一个 GET 连接；非 200 返回 null。 */
    private static HttpURLConnection open(String url) throws IOException {
        // URL(String) 只抛 MalformedURLException（IOException 子类），无 URI 的受检 URISyntaxException
        HttpURLConnection conn = (HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "GBL/1.3.1 RoomSkin");
        return conn.getResponseCode() == 200 ? conn : null;
    }

    /** 带上限读取，避免畸形响应把内存打爆。 */
    private static byte[] readLimit(InputStream in, int limit) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (bos.size() + n > limit) {
                break;
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
