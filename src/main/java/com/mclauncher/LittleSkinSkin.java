package com.mclauncher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.1.3：读取 LITTLESKIN 外置账号在服务器上的真实皮肤（1.1.2 之前这类账号头像
 * 永远是默认史蒂夫，因为 {@link SkinStore#skinBytes} 对 LITTLESKIN 直接兜底返回 steve）。
 *
 * <p>LittleSkin 是标准 Yggdrasil：{@code GET sessionserver/session/minecraft/profile/<uuid>?unsigned=false}
 * 返回 {@code properties[]{name:"textures", value:"<base64>"}}，base64 解出的 JSON 里
 * {@code textures.SKIN.url} 是 PNG 地址（另有可选 {@code metadata.model=="slim"} 和
 * {@code textures.CAPE.url}），再 GET 那个 URL 拿 PNG。本类只负责「拿到字节」，
 * 渲染交给 {@link SkinRender#head(byte[])} / {@link SkinRender#fullImage(byte[])}。
 *
 * <p>缓存：{@code ~/.gbl/littleskin-skins/<uuid>.png} + 同名 .json（model / capeUrl /
 * fetchedAt）。新鲜（24 小时内）直接用缓存；过期后台重拉，失败则沿用旧缓存。
 * 写盘一律「唯一时间戳临时文件 + 原子 move」，不删除任何已有文件。
 *
 * <p>线程约定：本类不碰 JavaFX，可在任意后台线程调用；调用方负责把结果用
 * {@code Platform.runLater} 搬回界面线程。
 */
public final class LittleSkinSkin {

    private static final String TAG = "[littleskin-skin]";
    /** 与 {@link LittleSkinAuth#API_ROOT} 共用同一端点前缀。 */
    private static final String PROFILE_API = LittleSkinAuth.API_ROOT + "sessionserver/session/minecraft/profile/";
    private static final long CACHE_TTL_MS = 24L * 60 * 60 * 1000;
    private static final Path CACHE_DIR =
            Paths.get(System.getProperty("user.home"), ".gbl", "littleskin-skins");

    private LittleSkinSkin() {
    }

    /** 一次成功的拉取结果：PNG 字节 + 模型（slim / classic）+ 披风地址（可空）+ 拉取时刻。 */
    public static final class Texture {
        public final byte[] png;
        public final String model;
        public final String capeUrl;
        public final long fetchedAt;

        public Texture(byte[] png, String model, String capeUrl, long fetchedAt) {
            this.png = png;
            this.model = model == null || model.isBlank() ? "classic" : model;
            this.capeUrl = capeUrl == null || capeUrl.isBlank() ? null : capeUrl;
            this.fetchedAt = fetchedAt;
        }
    }

    /** profile → textures 解析出来的引用（尚未下载 PNG）。包内可见，供探针直接验解析。 */
    static final class TextureRef {
        final String skinUrl;
        final String capeUrl;
        final String model;

        TextureRef(String skinUrl, String capeUrl, String model) {
            this.skinUrl = skinUrl;
            this.capeUrl = capeUrl;
            this.model = model;
        }
    }

    /**
     * 可注入的 HTTP 取数器。真实实现走 JDK HttpClient；探针传一个假的即可覆盖
     * 「HTTP 500」「坏 body」等分支，不需要联网。任何失败都抛异常，由上层兜成 null。
     */
    interface Fetcher {
        String getText(String url) throws Exception;
        byte[] getBytes(String url) throws Exception;
    }

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static final Fetcher NETWORK = new Fetcher() {
        @Override
        public String getText(String url) throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            HttpResponse<String> resp = CLIENT.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IOException("HTTP " + resp.statusCode());
            }
            return resp.body();
        }

        @Override
        public byte[] getBytes(String url) throws Exception {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();
            HttpResponse<byte[]> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IOException("HTTP " + resp.statusCode());
            }
            return resp.body();
        }
    };

    /** 取当前账号的服务器皮肤：命中 24h 缓存直接用，过期后台重拉，失败沿用旧缓存。 */
    public static Texture fetchTexture(AccountStore.Account acc) {
        return fetchTexture(acc, false);
    }

    /**
     * @param force true=跳过缓存强制重拉（皮肤管理「刷新」按钮用）；失败仍会用旧缓存兜底。
     */
    public static Texture fetchTexture(AccountStore.Account acc, boolean force) {
        if (acc == null || acc.uuid == null || acc.uuid.isBlank()) {
            log("账号为空或 uuid 为空，跳过");
            return null;
        }
        String uuid = acc.uuid;
        Texture cached = loadCached(uuid);
        if (!force && cached != null && cached.fetchedAt > 0
                && System.currentTimeMillis() - cached.fetchedAt < CACHE_TTL_MS) {
            log("命中缓存 " + safeName(uuid) + " (" + cached.png.length + " bytes)");
            return cached;
        }
        Texture fresh = fetchTexture(acc, NETWORK);
        if (fresh != null && fresh.png != null && fresh.png.length > 0) {
            saveCached(uuid, fresh);
            log("拉取成功 " + safeName(uuid) + " model=" + fresh.model
                    + " bytes=" + fresh.png.length);
            return fresh;
        }
        if (cached != null) {
            log("用旧缓存兜底 " + safeName(uuid));
            return cached;
        }
        return null;
    }

    /**
     * 带可注入 HTTP 的取数入口（不读写缓存）：探针用假 Fetcher 覆盖 good / 坏数据 /
     * HTTP 500 等分支，任何失败都兜成 null，绝不 NPE。
     */
    static Texture fetchTexture(AccountStore.Account acc, Fetcher fetcher) {
        if (acc == null || fetcher == null || acc.uuid == null || acc.uuid.isBlank()) {
            return null;
        }
        try {
            return fetchRemote(acc, fetcher);
        } catch (Exception ex) {
            log("拉取失败: " + ex);
            return null;
        }
    }

    /** 纯网络路径（不读写缓存）。 */
    private static Texture fetchRemote(AccountStore.Account acc, Fetcher fetcher) throws Exception {
        if (acc == null || fetcher == null || acc.uuid == null || acc.uuid.isBlank()) {
            return null;
        }
        String profileUrl = PROFILE_API + acc.uuid + "?unsigned=false";
        String profileJson = fetcher.getText(profileUrl);
        TextureRef ref = parseProfile(profileJson);
        if (ref == null || ref.skinUrl == null || ref.skinUrl.isBlank()) {
            log("profile 里没有可用的 SKIN 纹理");
            return null;
        }
        byte[] png = fetcher.getBytes(ref.skinUrl);
        if (png == null || png.length == 0) {
            log("皮肤 PNG 为空");
            return null;
        }
        return new Texture(png, ref.model, ref.capeUrl, System.currentTimeMillis());
    }

    /**
     * 解析 sessionserver 的 profile 响应 JSON，取出 base64 textures 值再解出皮肤地址。
     * 纯函数，坏数据一律返回 null，绝不抛异常 / NPE。
     */
    static TextureRef parseProfile(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (RuntimeException ex) {
            log("profile JSON 解析失败: " + ex.getMessage());
            return null;
        }
        if (!(parsed instanceof Map<?, ?> root)) {
            return null;
        }
        Object props = root.get("properties");
        if (!(props instanceof List<?> list)) {
            return null;
        }
        String encoded = null;
        for (Object o : list) {
            if (o instanceof Map<?, ?> p && "textures".equals(p.get("name"))
                    && p.get("value") instanceof String v && !v.isBlank()) {
                encoded = v;
                break;
            }
        }
        return encoded == null ? null : decodeTextures(encoded);
    }

    /** 解 base64 的 textures JSON → SKIN/CAPE url + model。纯函数，坏 base64 返回 null。 */
    static TextureRef decodeTextures(String base64) {
        if (base64 == null || base64.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException ex) {
            log("textures base64 非法: " + ex.getMessage());
            return null;
        }
        Object parsed;
        try {
            parsed = Json.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            log("textures JSON 解析失败: " + ex.getMessage());
            return null;
        }
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("textures") instanceof Map<?, ?> tex)) {
            return null;   // 缺 textures 字段
        }
        String skinUrl = null, capeUrl = null;
        if (tex.get("SKIN") instanceof Map<?, ?> skin && skin.get("url") instanceof String su
                && !su.isBlank()) {
            skinUrl = su;
        }
        if (tex.get("CAPE") instanceof Map<?, ?> cape && cape.get("url") instanceof String cu
                && !cu.isBlank()) {
            capeUrl = cu;
        }
        if (skinUrl == null && capeUrl == null) {
            return null;   // 连皮肤带披风都没有，没意义
        }
        // model 可能在 textures.SKIN.metadata.model（Mojang 标准），也可能在顶层 metadata.model，
        // 两种都认，别因为服务端放的位置不一样就丢掉 slim。
        String model = "classic";
        if (tex.get("SKIN") instanceof Map<?, ?> skin2
                && skin2.get("metadata") instanceof Map<?, ?> sm
                && "slim".equals(sm.get("model"))) {
            model = "slim";
        } else if (tex.get("metadata") instanceof Map<?, ?> tm && "slim".equals(tm.get("model"))) {
            model = "slim";
        } else if (root.get("metadata") instanceof Map<?, ?> rm && "slim".equals(rm.get("model"))) {
            model = "slim";
        }
        return new TextureRef(skinUrl, capeUrl, model);
    }

    // ---------------------------------------------------------------- 缓存（原子写，不删文件）

    private static Texture loadCached(String uuid) {
        Path png = cachePng(uuid);
        Path meta = cacheJson(uuid);
        try {
            if (!Files.isRegularFile(png) || !Files.isRegularFile(meta)) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(png);
            if (bytes.length == 0) {
                return null;
            }
            Object parsed = Json.parse(Files.readString(meta, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?> m)) {
                return null;
            }
            long ts = 0;
            if (m.get("fetchedAt") instanceof Number n) {
                ts = n.longValue();
            }
            String model = m.get("model") instanceof String s && !s.isBlank() ? s : "classic";
            String cape = m.get("capeUrl") instanceof String s && !s.isBlank() ? s : null;
            return new Texture(bytes, model, cape, ts);
        } catch (Exception ex) {
            log("读缓存失败（忽略）: " + ex);
            return null;
        }
    }

    private static void saveCached(String uuid, Texture tex) {
        try {
            Files.createDirectories(CACHE_DIR);
            String base = safeName(uuid);
            long stamp = System.nanoTime();
            Path tmpPng = CACHE_DIR.resolve(base + ".png." + stamp + ".tmp");
            Path tmpJson = CACHE_DIR.resolve(base + ".json." + stamp + ".tmp");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uuid", uuid);
            m.put("model", tex.model);
            m.put("capeUrl", tex.capeUrl == null ? "" : tex.capeUrl);
            m.put("fetchedAt", tex.fetchedAt);
            Files.write(tmpPng, tex.png);
            Files.writeString(tmpJson, Json.write(m), StandardCharsets.UTF_8);
            atomicMove(tmpPng, cachePng(uuid));
            atomicMove(tmpJson, cacheJson(uuid));
        } catch (Exception ex) {
            // 零删除铁律：写失败就留着临时文件，绝不动用户已有缓存
            log("写缓存失败（保留已有文件）: " + ex);
        }
    }

    private static void atomicMove(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException ex) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path cachePng(String uuid) {
        return CACHE_DIR.resolve(safeName(uuid) + ".png");
    }

    private static Path cacheJson(String uuid) {
        return CACHE_DIR.resolve(safeName(uuid) + ".json");
    }

    /** uuid 只留字母数字和横线，避免异常 uuid 拼出路径穿越。 */
    private static String safeName(String uuid) {
        StringBuilder sb = new StringBuilder(uuid.length());
        for (int i = 0; i < uuid.length(); i++) {
            char c = uuid.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' ? c : '_');
        }
        return sb.toString();
    }

    private static void log(String msg) {
        System.out.println(TAG + " " + msg);
    }
}
