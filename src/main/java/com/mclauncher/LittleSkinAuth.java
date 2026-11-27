package com.mclauncher;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 外置（little skin）账号登录。
 *
 * <p>LittleSkin 是兼容 Mojang Yggdrasil 协议的第三方皮肤 / 账号服务，
 * 登录接口走标准 {@code /authserver/authenticate}：用户名（或邮箱）+ 密码换一个
 * accessToken 和选中的游戏档案（uuid + 名字）。拿到后启动游戏时用这套真实凭据，
 * 离线单机照常，皮肤 / 披风也能按 uuid 拉到。
 *
 * <p>API 根：{@code https://littleskin.cn/api/yggdrasil/}
 */
public final class LittleSkinAuth {

    /** Yggdrasil API 根（authserver / sessionserver 都挂在这下面）。 */
    public static final String API_ROOT = "https://littleskin.cn/api/yggdrasil/";

    /** 一次成功登录的结果。 */
    public static final class Result {
        public final String accessToken;
        public final String uuid;   // 带横线的标准 uuid
        public final String name;   // 游戏内显示名（来自选中的档案）

        public Result(String accessToken, String uuid, String name) {
            this.accessToken = accessToken;
            this.uuid = uuid == null ? "" : uuid;
            this.name = name == null ? "" : name;
        }
    }

    private LittleSkinAuth() {
    }

    /**
     * 用用户名 + 密码登录 LittleSkin。
     *
     * @return 成功时的 {@link Result}
     * @throws Exception 用户名 / 密码为空、网络失败、凭据错误、或返回格式异常时抛出，
     *                   异常信息可直接展示给用户（已尽量取服务端 errorMessage）。
     */
    public static Result authenticate(String username, String password) throws Exception {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        String clientToken = UUID.randomUUID().toString();
        // agent 是 Mojang 旧协议的遗留字段，LittleSkin 的 authserver 也要（缺了会回
        // "agent: agent must be an object"）
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("name", "Minecraft");
        agent.put("version", 1);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agent", agent);
        body.put("username", username);
        body.put("password", password);
        body.put("clientToken", clientToken);
        body.put("requestUser", true);
        String json = Json.write(body);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_ROOT + "authserver/authenticate"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException | InterruptedException ex) {
            throw new Exception("连不上 LittleSkin（" + ex.getMessage() + "），检查网络后重试");
        }

        int code = resp.statusCode();
        String text = resp.body() == null ? "" : resp.body();
        if (code != 200) {
            String msg = "LittleSkin 登录失败（HTTP " + code + "）";
            Object parsed = tryParse(text);
            if (parsed instanceof Map) {
                Object em = ((Map<?, ?>) parsed).get("errorMessage");
                if (em instanceof String && !((String) em).isBlank()) {
                    msg = (String) em;
                }
            }
            throw new Exception(msg);
        }

        Object parsed = tryParse(text);
        if (!(parsed instanceof Map)) {
            throw new Exception("LittleSkin 返回格式异常");
        }
        Map<?, ?> m = (Map<?, ?>) parsed;
        String accessToken = asString(m.get("accessToken"));
        if (accessToken == null || accessToken.isBlank()) {
            throw new Exception("LittleSkin 未返回 accessToken");
        }
        String uuid = "";
        String name = username;
        Object sp = m.get("selectedProfile");
        if (sp instanceof Map) {
            Map<?, ?> prof = (Map<?, ?>) sp;
            String pid = asString(prof.get("id"));
            if (pid != null) {
                uuid = pid;
            }
            String pname = asString(prof.get("name"));
            if (pname != null) {
                name = pname;
            }
        }
        return new Result(accessToken, uuid, name);
    }

    private static Object tryParse(String text) {
        try {
            return Json.parse(text);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String asString(Object o) {
        if (!(o instanceof String)) {
            return null;
        }
        String s = ((String) o).trim();
        return s.isEmpty() ? null : s;
    }
}
