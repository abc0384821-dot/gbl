package com.mclauncher;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 离线账号的「本地皮肤服务」：实现 Yggdrasil 的子集，让游戏经 authlib-injector 重定向后，
 * 把离线账号的自定义皮肤当成「已认证皮肤」加载——这样离线账号也能在游戏里显示自定义皮肤。
 *
 * <p>只监听 127.0.0.1（本机），游戏退出即由 GameLauncher 调 {@link #stop()} 关闭。
 * 提供的最小端点：
 * <ul>
 *   <li>POST /authserver/authenticate — 返回带 textures 属性的玩家档案（游戏登录用）</li>
 *   <li>POST /authserver/validate、/authserver/refresh — 放行（204 / 重新发档案）</li>
 *   <li>GET  /sessionserver/session/minecraft/profile/&lt;uuid&gt; — 单机 fillProfile 用</li>
 *   <li>GET  /sessionserver/session/minecraft/hasJoined — 联机校验用（忽略 serverId）</li>
 *   <li>GET  /skin — 返回皮肤 PNG 字节（textures 里的 url 指向这里）</li>
 * </ul>
 */
public final class LocalSkinServer {

    private final ServerSocket sock;
    private final String uuid;       // 无横线标准 uuid（游戏侧 auth_uuid）
    private final String name;
    private final byte[] skin;
    private final String model;       // classic | slim
    private final Thread thread;

    public LocalSkinServer(String uuidNoDash, String playerName, byte[] skinBytes, String model)
            throws IOException {
        this.uuid = uuidNoDash;
        this.name = playerName == null ? "Player" : playerName;
        this.skin = skinBytes;
        this.model = "slim".equals(model) ? "slim" : "classic";
        this.sock = new ServerSocket();
        this.sock.setReuseAddress(true);
        this.sock.bind(new InetSocketAddress("127.0.0.1", 0));
        this.thread = new Thread(this::loop, "gbl-local-skin");
        this.thread.setDaemon(true);
    }

    public int port() {
        return sock.getLocalPort();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    public void start() {
        thread.start();
    }

    public void stop() {
        try {
            sock.close();
        } catch (IOException ignore) {
        }
    }

    private void loop() {
        while (!sock.isClosed()) {
            try (Socket c = sock.accept()) {
                handle(c);
            } catch (IOException ex) {
                if (sock.isClosed()) {
                    break;
                }
            } catch (Throwable ignore) {
                // 单连接出错不影响整体；继续等下一个
            }
        }
    }

    private String texturesValue() {
        String url = baseUrl() + "/skin";
        // 老 MC 1.8 之前认 metadata.model；新版认 metadata.model，保持 classic/slim
        String json = "{\"timestamp\":" + System.currentTimeMillis()
                + ",\"profileId\":\"" + uuid + "\""
                + ",\"profileName\":\"" + jsonEscape(name) + "\""
                + ",\"textures\":{\"SKIN\":{\"url\":\"" + url
                + "\",\"metadata\":{\"model\":\"" + model + "\"}}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private String profileJson() {
        return "{\"id\":\"" + uuid + "\",\"name\":\"" + jsonEscape(name) + "\""
                + ",\"properties\":[{\"name\":\"textures\",\"value\":\""
                + texturesValue() + "\",\"signature\":\"\"}]}";
    }

    private String authResponse() {
        String p = profileJson();
        return "{\"accessToken\":\"gbl-offline-skin\",\"clientToken\":\"gbl-offline-skin\""
                + ",\"availableProfiles\":[" + p + "],\"selectedProfile\":" + p + "}";
    }

    private void handle(Socket c) throws IOException {
        BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        String line = r.readLine();
        if (line == null) {
            return;
        }
        String[] parts = line.split(" ");
        String method = parts[0];
        String path = parts.length > 1 ? parts[1] : "/";
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        // 读完请求头（直到空行），POST 的 body 我们不解析
        while (true) {
            String h = r.readLine();
            if (h == null || h.isEmpty()) {
                break;
            }
        }
        OutputStream out = c.getOutputStream();
        if ("/".equals(path) || path.isEmpty()) {
            // authlib-injector 启动时会 GET API 根拿元数据：skinDomains 是皮肤纹理 URL 的
            // 白名单，没有它游戏直接拒绝从 127.0.0.1 拉皮肤 → 离线皮肤「在游戏里看不见」
            sendJson(out, "{\"skinDomains\":[\"127.0.0.1\",\"localhost\"],"
                    + "\"meta\":{\"implementationName\":\"GBL\",\"serverName\":\"GBL 本地皮肤服务\"}}");
        } else if ("/skin".equals(path)) {
            sendBytes(out, "image/png", skin);
        } else if (path.contains("/profile/")) {
            sendJson(out, profileJson());
        } else if (path.contains("/hasJoined")) {
            sendJson(out, profileJson());
        } else if (path.endsWith("/authenticate")) {
            sendJson(out, authResponse());
        } else if (path.endsWith("/validate")) {
            sendStatus(out, 204);
        } else if (path.endsWith("/refresh")) {
            sendJson(out, authResponse());
        } else {
            sendJson(out, "{\"error\":\"not_found\"}");
        }
    }

    private static void sendStatus(OutputStream out, int code) throws IOException {
        byte[] head = ("HTTP/1.1 " + code + " OK\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        out.write(head);
        out.flush();
    }

    private static void sendJson(OutputStream out, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        head.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        head.write(b);
        out.write(head.toByteArray());
        out.flush();
    }

    private static void sendBytes(OutputStream out, String contentType, byte[] b) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        head.write(("HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\n"
                + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        head.write(b);
        out.write(head.toByteArray());
        out.flush();
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
