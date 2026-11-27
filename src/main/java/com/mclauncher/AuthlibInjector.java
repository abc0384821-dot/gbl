package com.mclauncher;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.function.Consumer;

/**
 * authlib-injector：让外置账号（LittleSkin）真正能进联机服。
 *
 * <p>MC 自带 authlib 只认 Mojang 官方的 Yggdrasil。authlib-injector 是一个 JVM agent，
 * 把游戏里所有 Yggdrasil 调用（hasJoined 校验、皮肤 / 档案查询…）重定向到我们指定的第三方认证服务器。
 * 加上它之后，用 LittleSkin 账号登录的游戏就能在「认 LittleSkin 这套 Yggdrasil」的服务器上联机，
 * 皮肤 / 披风也按 uuid 拉到；纯单机则完全不受它影响。
 *
 * <p>用法：给游戏 JVM 加一条 {@code -javaagent:<jar>=<认证服务器URL>}。
 * 认证服务器 URL 用 LittleSkin 的 Yggdrasil 根 {@code https://littleskin.cn/api/yggdrasil}。
 *
 * <p>jar 从 GitHub 下载一次缓存在 {@code ~/.gbl/cache/}，带 SHA-256 校验，之后都走缓存。
 * 这东西能读到游戏的网络 / 账号交互，必须可信，所以下载后逐字节校验官方发布摘要。
 */
public final class AuthlibInjector {

    /** LittleSkin 的 Yggdrasil 根：authlib-injector 会把它后面拼上 /authserver、/sessionserver、/api。 */
    public static final String YGGDRASIL_URL = "https://littleskin.cn/api/yggdrasil";

    /** 固定版本（v1.2.8，兼容到 26.3 等新版 MC）。换版本改这一处 + 下面的摘要即可。 */
    private static final String BASE =
            "https://github.com/yushijinhun/authlib-injector/releases/download/v1.2.8/authlib-injector-1.2.8.jar";

    /**
     * 下载地址候选：GitHub 原站优先，后面是两个常见的 GitHub 代理镜像，
     * 给直连 GitHub 不稳的地区（比如国内）兜底。镜像只做字节转发，文件内容不变，
     * 所以 SHA-256 仍对得上。依次尝试，谁先下到且校验通过用谁。
     */
    private static final String[] DOWNLOAD_URLS = {
            BASE,
            "https://ghproxy.net/" + BASE,
            "https://mirror.ghproxy.com/" + BASE,
    };

    /** 官方 release asset 公布的 SHA-256（GitHub API 的 asset digest 字段）。下载后比对，不符即丢弃。 */
    private static final String EXPECTED_SHA256 =
            "9c7f4343e6c82034958ffb48c14a2cb0c85928be7283103ce17da00c6d5a7b10";

    private AuthlibInjector() {
    }

    /** 原来的缓存位置（{@code ~/.gbl/cache/}），也是联网下载唯一允许落盘的位置（用户目录可写）。 */
    private static Path legacyCacheFile() {
        return Paths.get(System.getProperty("user.home"), ".gbl", "cache", "authlib-injector.jar");
    }

    /**
     * 找 jar 的位置（0.9.9 r6）：优先 GBL 便携目录里随包的那份，没有再回退用户缓存目录。
     *
     * <p>随包那份由打包流程把 {@code src/main/deploy/authlib-injector.jar} 复制到 GBL/ 根目录，
     * 朋友全新机器也有，不必联网从 GitHub 下（国内常常连不上，失败被静默吞掉就回退 Steve）。
     * 安装目录可能只读，所以返回这里之后 {@link #valid(Path)} 仍会逐字节校验；
     * 若随包那份不存在或校验不过，{@link #javaAgentArg(String, Consumer)} 会退到用户缓存目录处理。
     */
    private static Path cachedFile() {
        Path bundled = Platform.appDir().resolve("authlib-injector.jar");
        if (Files.isRegularFile(bundled)) {
            return bundled;
        }
        return legacyCacheFile();
    }

    /**
     * 生成给游戏 JVM 用的 {@code -javaagent=…} 参数；jar 不在就先下载（带进度）。
     *
     * @param onProgress 下载进度回调（「正在准备联机组件…」这类串）；传 null 不报进度
     * @throws IOException 下载失败 / 校验不过时抛出，调用方转成「起不来」的提示
     */
    public static String javaAgentArg(Consumer<String> onProgress) throws IOException {
        return javaAgentArg(YGGDRASIL_URL, onProgress);
    }

    /**
     * 生成给游戏 JVM 用的 {@code -javaagent=…} 参数，认证服务器根 URL 可指定。
     *
     * @param apiUrl     认证服务器根（LittleSkin 用默认；离线本地皮肤服务传本地 http URL）
     * @param onProgress 下载进度回调；传 null 不报进度
     * @throws IOException 下载失败 / 校验不过时抛出
     */
    public static String javaAgentArg(String apiUrl, Consumer<String> onProgress) throws IOException {
        Path jar = cachedFile();
        // 随包那份已存在且校验通过 → 直接用，全程不联网。
        if (!valid(jar)) {
            // 两个来源都按同一 SHA-256 校验：随包那份存在但校验不过时，绝不写安装目录，
            // 退到用户缓存目录处理（下载只往用户目录写，安装目录可能只读）。
            Path cache = legacyCacheFile();
            if (!jar.equals(cache)) {
                System.err.println("[authlib] 随包 authlib-injector 校验未通过，退到用户缓存目录: " + jar);
                jar = cache;
            }
            if (!valid(jar)) {
                if (onProgress != null) {
                    onProgress.accept("正在下载联机组件 authlib-injector…");
                }
                download(jar);
            }
        }
        if (!valid(jar)) {
            throw new IOException("authlib-injector 校验失败，无法启用外置账号联机。"
                    + "请检查网络后重启启动器，或把一个校验过的 authlib-injector.jar 放到 "
                    + jar + " 再试。");
        }
        // -javaagent 路径由 ProcessBuilder 按独立参数元素传，不靠 shell，路径含空格也不怕；
        // 等号后面是认证服务器根 URL，authlib-injector 自己决定往哪拼 /authserver 等路径。
        return "-javaagent:" + jar + "=" + apiUrl;
    }

    /** 文件存在、非 0 字节、且 SHA-256 对得上才认。 */
    private static boolean valid(Path jar) {
        if (!Files.isRegularFile(jar) || sizeOf(jar) == 0) {
            return false;
        }
        return EXPECTED_SHA256.equalsIgnoreCase(sha256(jar));
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException | RuntimeException ex) {
            return 0;
        }
    }

    /** 下载到临时文件，校验通过再原子改名，避免下载到一半被当成可用文件。 */
    private static void download(Path dest) throws IOException {
        Path dir = dest.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = dir.resolve(dest.getFileName() + ".part");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        IOException last = null;
        for (String url : DOWNLOAD_URLS) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .header("User-Agent", "GBL-Minecraft-Launcher")
                        .timeout(Duration.ofSeconds(120))
                        .GET()
                        .build();
                HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(tmp,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING));
                int code = resp.statusCode();
                if (code < 200 || code >= 300) {
                    last = new IOException("下载 authlib-injector 失败（HTTP " + code + "）@ " + url);
                    continue;
                }
                if (!EXPECTED_SHA256.equalsIgnoreCase(sha256(tmp))) {
                    last = new IOException("下载的 authlib-injector 校验和不符，可能已被篡改，已丢弃 @ " + url);
                    continue;
                }
                // 校验通过，落盘后结束
                try {
                    Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException fallback) {
                    Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("下载 authlib-injector 被中断", ex);
            } catch (java.io.IOException ex) {
                last = new IOException("连不上 " + url + " 下载 authlib-injector（" + ex.getMessage() + "）");
                // 试下一个候选地址
            }
        }
        Files.deleteIfExists(tmp);
        throw new IOException((last == null ? "下载 authlib-injector 失败" : last.getMessage())
                + "。检查网络后重试，或换网络再启动外置账号；也可手动把校验过的 jar 放到 "
                + legacyCacheFile());
    }

    /** 文件的 SHA-256，十六进制小写；读不出返回空串（valid 会据此判不合格）。 */
    private static String sha256(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException | IOException | RuntimeException ex) {
            return "";
        }
    }
}
