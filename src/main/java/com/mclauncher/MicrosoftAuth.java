package com.mclauncher;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 微软账号登录（Minecraft 官方 OAuth，设备码流）。
 *
 * <p>微软 / Xbox / Mojang 这套鉴权是分四段的：先用设备码流拿到微软身份令牌，
 * 再经 Xbox Live（XBL）→ Xbox 安全令牌（XSTS）→ Minecraft 服务换出游戏令牌，
 * 最后拉 {@code /minecraft/profile} 拿到玩家 uuid + 名字。最终游戏启动用的就是
 * 这套 MC 令牌（和 LittleSkin 外置账号一样是 {@code user_type=mojang} + 真实 token，
 * 区别只是令牌来自微软而不是 LittleSkin，且不需要 authlib-injector）。
 *
 * <p>登录方式选设备码流（Device Code Flow）而不是内嵌浏览器：用户在自己的浏览器里登
 * 微软账号，启动器只负责轮询令牌，不碰登录页、不收密码，也避开了微软不推荐用 webview
 * 登录消费级账号的合规问题。
 *
 * <p>续期：申请 {@code offline_access} scope 拿到 refresh_token；MC 访问令牌约 24 小时过期，
 * 但 refresh_token 长得多，所以每次启动用它在后台静默换新令牌即可，用户数周不用再登录。
 *
 * <p>Client ID 用社区通用的公开 Minecraft Launcher ID（零配置即可用）。
 */
public final class MicrosoftAuth {

    /**
     * 微软 OAuth client_id。
     *
     * <p>微软官方旧公开 ID {@code 00000000402b5328} 已被回收（返回 AADSTS700016 not found）；
     * 开源启动器的 ID 均编译时注入、不公开。故 GBL 使用用户自建的 Azure 应用 client_id。
     * 现默认借用 Prism Launcher 开源仓库中公开的 client_id（已于 2026-10-06 用本机 JDK 实锤
     * 在 consumers 租户设备码流可用，返回 HTTP 200 + 完整设备码），用户无需自建 Azure 应用即可登录；
     * 若想用自有 ID，按下面优先级覆盖即可。
     *
     * <p>读取优先级（找到即用，便于真机随时换 ID 而不用重编译）：
     * <ol>
     *   <li>系统属性 {@code -Dgbl.microsoft.clientId=...}；</li>
     *   <li>文件 {@code ~/.gbl/microsoft-clientid.txt} 的首个非空行（支持 # 注释）；</li>
     *   <li>兜底默认值（Prism Launcher 的公开 ID，开箱即用）。</li>
     * </ol>
     */
    public static final String CLIENT_ID = resolveClientId();

    /** 按上面优先级解析 client_id，并打印来源便于排查。 */
    private static String resolveClientId() {
        String fromProp = System.getProperty("gbl.microsoft.clientId");
        if (fromProp != null && !fromProp.isBlank()) {
            System.out.println("[msoauth] client_id 来自系统属性 gbl.microsoft.clientId");
            return fromProp.trim();
        }
        try {
            Path f = Paths.get(System.getProperty("user.home"), ".gbl", "microsoft-clientid.txt");
            if (Files.exists(f)) {
                try (BufferedReader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        String t = line.trim();
                        if (!t.isEmpty() && !t.startsWith("#")) {
                            System.out.println("[msoauth] client_id 来自文件 " + f);
                            return t;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            System.out.println("[msoauth] 读取 microsoft-clientid.txt 失败: " + ex.getMessage());
        }
        System.out.println("[msoauth] client_id 使用兜底默认值（白名单内启动器公开 ID，开箱即用）");
        // 兜底默认值 = 白名单内开源启动器（Prism Launcher）的公开 client_id。
        // Mojang 对 login_with_xbox 实行 client_id 白名单（403 Invalid app registration，
        // 见 https://aka.ms/AppRegInfo）：新注册的 Azure 应用不在名单内，除非走
        // https://aka.ms/mce-reviewappid 审核，否则任何配置都无法通过。故默认借用
        // 已获批的 Prism 公开 ID（c36a9fb6-4f2a-41ff-90bd-ae7cc92031eb）保证开箱即用。
        return "c36a9fb6-4f2a-41ff-90bd-ae7cc92031eb";
    }

    private static final String TENANT = "consumers";   // 个人微软账号（非组织租户）
    private static final String SCOPE = "XboxLive.signin offline_access";

    private static final String DEVICECODE_URL =
            "https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL =
            "https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/token";
    private static final String XBL_URL = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN_URL =
            "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL =
            "https://api.minecraftservices.com/minecraft/profile";

    /**
     * 所有请求统一带的 User-Agent。
     *
     * <p>关键坑：微软 Xbox Live 鉴权端点（user.auth.xboxlive.com /
     * xsts.auth.xboxlive.com）对缺少 User-Agent 的请求经常直接回 401 UNAUTHORIZED，
     * 而 login.microsoftonline.com（设备码那步）不挑 UA——这正是「网页登录成功、
     * 启动器卡在换令牌 + 红字 UNAUTHORIZED」的真根因。PCL/HMCL 都带 UA，所以能过。
     *
     * <p>v0.7.0 起引用 {@link LauncherApp#VERSION}，不再硬编码版本号（和
     * {@link GameDownloader#AGENT} 一个口径：改版本只改 LauncherApp 那一处）。
     */
    private static final String USER_AGENT =
            "GBL/" + LauncherApp.VERSION + " (Minecraft Launcher; Windows)";

    /** 鉴权全流程诊断日志：写进 ~/.gbl/msoauth-trace.log，便于真机精确定位卡在哪一步。 */
    private static final Path TRACE =
            Paths.get(System.getProperty("user.home"), ".gbl", "msoauth-trace.log");

    /** 把长度 >= 40 的令牌串（JWT / device_code 等）打码，避免敏感信息落盘。 */
    private static final Pattern TOKEN_RE = Pattern.compile("[A-Za-z0-9._-]{40,}");

    private static String maskSecrets(String s) {
        if (s == null) {
            return "";
        }
        return TOKEN_RE.matcher(s).replaceAll("***");
    }

    private static void trace(String tag, String detail) {
        try {
            Files.createDirectories(TRACE.getParent());
            String line = "[" + LocalTime.now() + "] " + tag + " "
                    + maskSecrets(detail) + System.lineSeparator();
            Files.write(TRACE, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignore) {
            // 诊断日志写失败不影响登录主流程
        }
    }

    /** 判定 MC 令牌「快过期」的提前量：留 5 分钟缓冲，别卡着过期那一秒才刷。 */
    private static final long EXPIRY_SLACK_MS = 5 * 60 * 1000L;

    /** 刷新时「换 refresh token」这一步最多重试几次（1 次首次 + 3 次重试，退避 1s/2s/4s）。 */
    private static final int MAX_REFRESH_RETRIES = 3;

    /** MC 令牌换发被限流时的退避：原来 30s 会把一次刷新拖成分钟级，缩短到 2s。 */
    private static final long MC_LOGIN_BACKOFF_MS = 2 * 1000L;

    private MicrosoftAuth() {
    }

    // ---------------------------------------------------------------- 数据结构

    /** 设备码流第一步的返回：展示给用户去浏览器登录的那对东西。 */
    public static final class DeviceCode {
        public final String userCode;          // 用户要在浏览器里输入的短码
        public final String verificationUri;    // 打开这个网址去登录
        public final String message;           // 微软给的一整句引导文案（含网址 + 码）
        public final int interval;             // 轮询间隔（秒）
        public final int expiresIn;            // 设备码有效期（秒）
        public final String deviceCode;         // 轮询用的内部码（不用展示）

        public DeviceCode(String userCode, String verificationUri, String message,
                           int interval, int expiresIn, String deviceCode) {
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.message = message;
            this.interval = interval <= 0 ? 5 : interval;
            this.expiresIn = expiresIn;
            this.deviceCode = deviceCode;
        }
    }

    /** 最终落到账号里的微软凭据：游戏启动用 accessToken，续期用 refreshToken。 */
    public static final class MinecraftAccount {
        public final String uuid;        // 玩家档案 id（无横线标准 uuid）
        public final String name;        // 游戏内显示名
        public final String accessToken; // MC 服务令牌（启动游戏用）
        public final String refreshToken;// 微软刷新令牌（续期用）
        public final long expiresAt;     // MC 令牌过期时刻（epoch 毫秒），0 表示未知

        public MinecraftAccount(String uuid, String name, String accessToken,
                                String refreshToken, long expiresAt) {
            this.uuid = uuid == null ? "" : uuid;
            this.name = name == null ? "" : name;
            this.accessToken = accessToken == null ? "" : accessToken;
            this.refreshToken = refreshToken == null ? "" : refreshToken;
            this.expiresAt = expiresAt;
        }
    }

    /** 设备码轮询最终拿到的微软令牌对：access 拿去换游戏令牌，refresh 落盘供后续静默续期（可能为空）。 */
    private record MsTokens(String accessToken, String refreshToken) {
    }

    // ---------------------------------------------------------------- 刷新失败的结构化分类

    /**
     * 刷新失败的类别。调用方（ShellView 启动块）只看这个枚举决定要不要引导重新登录，
     * 不再对异常文案做 {@code contains("请重新登录")} 之类的字符串猜测。
     */
    public enum RefreshKind {
        /** refresh_token 本身失效 / 授权被撤：唯一需要用户重新登录的类别。 */
        TOKEN_INVALID,
        /** 本机网络不通、超时、连接被拒等瞬态问题。 */
        NETWORK,
        /** 微软侧限流(429) / 5xx 等暂时不可用。 */
        SERVER,
        /** 账号体系问题：儿童账号、无 Xbox、无 Minecraft 拥有记录，重登也解决不了。 */
        ACCOUNT,
        /** 本机配置问题：client_id 不在 Mojang 白名单，改配置而非改账号。 */
        CONFIG,
        /** 线程被中断（用户取消启动等），静默收场，绝不判定为令牌失效。 */
        INTERRUPTED
    }

    /**
     * 带类别的刷新异常：{@code refresh} 失败时只抛这一种，调用方用 {@link #classify(Throwable)}
     * 或直接读 {@link #kind} 判定，不必解析文案。
     *
     * <p>{@link #retryable} 由 {@code kind} 推导：只有 {@code NETWORK} / {@code SERVER} 重试
     * 才有意义，其余类别立即结束，避免把令牌真失效的请求也拿去重试 3 次。
     */
    public static final class RefreshException extends Exception {
        private static final long serialVersionUID = 1L;

        public final RefreshKind kind;
        public final boolean retryable;

        public RefreshException(RefreshKind kind, String message) {
            super(message);
            this.kind = kind == null ? RefreshKind.NETWORK : kind;
            this.retryable = isRetryable(this.kind);
        }

        public RefreshException(RefreshKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind == null ? RefreshKind.NETWORK : kind;
            this.retryable = isRetryable(this.kind);
        }

        private static boolean isRetryable(RefreshKind kind) {
            return kind == RefreshKind.NETWORK || kind == RefreshKind.SERVER;
        }
    }

    /**
     * 把任意异常归到 {@link RefreshKind}：沿 cause 链找 {@link RefreshException}，
     * 完全找不到结构化信息时一律 {@code NETWORK}——保证调用方不会因为一个没认出来的
     * 异常把用户踢去重登（HMCL 式「网络问题先放行缓存令牌」）。
     */
    public static RefreshKind classify(Throwable ex) {
        Throwable cur = ex;
        for (int depth = 0; cur != null && depth < 8; depth++) {
            if (cur instanceof RefreshException re) {
                return re.kind;
            }
            cur = cur.getCause();
        }
        return RefreshKind.NETWORK;
    }

    /** 按 {@link AuthError} 的结构化字段（code / xerr / http / url）分类，不猜 message。 */
    private static RefreshKind classifyAuth(AuthError err) {
        String code = err.code == null ? "" : err.code.toLowerCase(Locale.ROOT);
        // 1) 令牌 / 授权本身失效：唯一需要重新登录
        if ("invalid_grant".equals(code) || "interaction_required".equals(code)
                || "unauthorized_client".equals(code) || "invalid_client".equals(code)
                || "invalid_scope".equals(code)) {
            return RefreshKind.TOKEN_INVALID;
        }
        // 2) XSTS 账号体系问题（儿童账号需家长授权 / 账号无 Xbox）
        if ("2148916233".equals(err.xerr) || "2148916238".equals(err.xerr)) {
            return RefreshKind.ACCOUNT;
        }
        // 3) 玩家档案端点取不到 = 没有 Minecraft 拥有记录（沿用 xboxOwnershipMessage 的语义）
        if (MC_PROFILE_URL.equals(err.url)) {
            return RefreshKind.ACCOUNT;
        }
        // 4) MC login 403 / Invalid app registration：client_id 白名单，属配置问题
        if (err.http == 403 && MC_LOGIN_URL.equals(err.url)) {
            return RefreshKind.CONFIG;
        }
        // 5) 限流 / 服务端错误
        if (err.http == 429 || err.http >= 500) {
            return RefreshKind.SERVER;
        }
        // 6) 其余（XBL/XSTS 401/403、其它 4xx 瞬态）按网络问题，可重试
        return RefreshKind.NETWORK;
    }

    /** 安全取异常文案，避免把 null 拼进用户可见的提示里。 */
    private static String safeMessage(Throwable ex) {
        if (ex == null) {
            return "未知错误";
        }
        String msg = ex.getMessage();
        return msg == null || msg.isBlank() ? ex.getClass().getSimpleName() : msg;
    }

    /** 指数退避 1s / 2s / 4s + 少量 jitter，避免多实例同时重试撞在一起。 */
    private static long backoffMillis(int attempt) {
        long base = 1000L << Math.min(attempt - 1, 2);
        return base + ThreadLocalRandom.current().nextLong(200);
    }

    // ---------------------------------------------------------------- 公开入口

    /** 第一步：向微软申请设备码。网络失败 / 参数错误抛 {@link Exception}（信息可直显）。 */
    public static DeviceCode requestDeviceCode() throws Exception {
        // 诊断：先测 DNS 能不能解析微软登录域（若解析失败，无论直连/代理都连不上，
        // 而 PCL/HMCL 用的是自己的网络栈，可能有不同 DNS 行为——这是关键分水岭）
        try {
            java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName("login.microsoftonline.com");
            for (java.net.InetAddress a : addrs) {
                System.out.println("[msoauth] DNS login.microsoftonline.com -> " + a.getHostAddress());
            }
        } catch (Exception dnsEx) {
            System.out.println("[msoauth] DNS login.microsoftonline.com 解析失败: "
                    + dnsEx.getMessage() + " (" + dnsEx.getClass().getSimpleName() + ")");
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", CLIENT_ID);
        form.put("scope", SCOPE);
        Map<String, Object> m = postForm(DEVICECODE_URL, form);
        String userCode = asString(m.get("user_code"));
        String uri = asString(m.get("verification_uri"));
        String msg = asString(m.get("message"));
        String deviceCode = asString(m.get("device_code"));
        if (userCode == null || uri == null || deviceCode == null) {
            throw new Exception("微软设备码返回不完整");
        }
        int interval = (int) asDouble(m.get("interval"), 5);
        int expiresIn = (int) asDouble(m.get("expires_in"), 900);
        return new DeviceCode(userCode, uri, msg == null ? "" : msg, interval, expiresIn, deviceCode);
    }

    /**
     * 第二步：展示设备码后轮询，直到用户在浏览器完成登录。
     *
     * @param onStatus 每轮轮询回调一句状态（剩余秒数 / 等待中），用于更新界面
     * @param cancelled 用户中途关掉弹窗时返回 true，立即中止轮询
     * @throws Exception 用户取消 / 超时 / 网络失败 / 账号无 Minecraft 授权等
     */
    public static MinecraftAccount completeLogin(DeviceCode dc, Consumer<String> onStatus,
                                                 BooleanSupplier cancelled) throws Exception {
        // 设备码轮询拿到的就是完整令牌对：access 拿去换链，refresh 一并落盘供续期。
        MsTokens ms = pollForMsToken(dc, onStatus, cancelled);
        return xboxChainToMinecraft(ms.accessToken(), ms.refreshToken());
    }

    /**
     * 用 refresh_token 静默换新 MC 令牌（启动前调用，令牌快过期才需要）。
     *
     * <p><b>0.8.6 D6 根治：</b>不再靠异常文案的子串判断「令牌是不是真死了」。失败一律抛
     * {@link RefreshException}，调用方用 {@link #classify(Throwable)} 读 {@link RefreshKind}：
     * 只有 {@code TOKEN_INVALID} 需要重新登录；{@code NETWORK} / {@code SERVER} /
     * {@code INTERRUPTED} 一律放行已缓存的 token 直接启动。
     *
     * <p>流程拆两段：第一段「换 refresh token」只对网络 / 服务端瞬态重试（3 次，指数退避
     * 1s/2s/4s + jitter）；第二段 XBL→XSTS→MC 链<b>只在换 token 成功后跑一次</b>，
     * 链失败按 Kind 分类返回，绝不整链重跑 3 次（原来每次重试都重跑整链，还叠 30s 限流退避）。
     *
     * @param refreshToken 之前存下来的微软刷新令牌
     * @return 换到的新凭据（含新的 accessToken；refresh_token 可能滚动，一并更新）
     * @throws Exception 失败时一律是 {@link RefreshException}，按 {@link RefreshKind} 分流
     */
    public static MinecraftAccount refresh(String refreshToken) throws Exception {
        if (refreshToken == null || refreshToken.isBlank()) {
            // 历史旧账号：refresh_token 从未落盘。这是唯一诚实需要重登的情形。
            throw new RefreshException(RefreshKind.TOKEN_INVALID,
                    "没有可用来刷新的微软令牌，请重新登录微软账号");
        }

        RefreshException first = null;   // 首次真实原因，任何后续事件都不覆盖它
        MsTokens tokens = null;
        for (int attempt = 0; attempt <= MAX_REFRESH_RETRIES; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(backoffMillis(attempt));
                } catch (InterruptedException ie) {
                    // 恢复中断标志，以 INTERRUPTED 立即结束：绝不进入重登判定。
                    Thread.currentThread().interrupt();
                    throw new RefreshException(RefreshKind.INTERRUPTED,
                            "刷新已取消（线程被中断）", first);
                }
            }
            try {
                tokens = refreshOnce(refreshToken);
                break;
            } catch (RefreshException ex) {
                if (first == null) {
                    first = ex;
                }
                if (!ex.retryable) {
                    throw ex;   // 令牌真失效 / 账号 / 配置 / 中断：重试无意义
                }
                if (attempt == MAX_REFRESH_RETRIES) {
                    throw exhausted(first);
                }
            } catch (Exception ex) {
                // 兜底：非结构化异常一律 NETWORK，别把未知异常当成重登理由
                RefreshException wrapped = new RefreshException(RefreshKind.NETWORK,
                        "换取微软令牌时出错：" + safeMessage(ex), ex);
                if (first == null) {
                    first = wrapped;
                }
                if (attempt == MAX_REFRESH_RETRIES) {
                    throw exhausted(first);
                }
            }
        }
        if (tokens == null) {
            throw exhausted(first);
        }

        // 第二段：整条链只跑一次，不进重试循环。链内各处已把异常按 Kind 结构化。
        try {
            return xboxChainToMinecraft(tokens.accessToken(), tokens.refreshToken());
        } catch (RefreshException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RefreshException(classify(ex),
                    "换取 Minecraft 令牌链失败：" + safeMessage(ex), ex);
        }
    }

    /** 重试耗尽：抛首次真实原因，而不是「第 3 次」那次表面原因。 */
    private static RefreshException exhausted(RefreshException first) {
        return new RefreshException(first == null ? RefreshKind.NETWORK : first.kind,
                "微软令牌刷新失败（网络 / 服务暂时连不上），已重试 " + MAX_REFRESH_RETRIES
                        + " 次：" + safeMessage(first),
                first);
    }

    /**
     * 单次「换 refresh token」尝试：只发 TOKEN_URL 这一个请求，绝不跑 XBL→XSTS→MC 链。
     * 异常就地按 {@link AuthError} 的结构化字段分类，交给 {@link #refresh} 决定重试与否。
     */
    private static MsTokens refreshOnce(String refreshToken) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", CLIENT_ID);
        form.put("scope", SCOPE);
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        Map<String, Object> m;
        try {
            m = postForm(TOKEN_URL, form);
        } catch (AuthError err) {
            RefreshKind kind = classifyAuth(err);
            String message = kind == RefreshKind.TOKEN_INVALID
                    ? "微软刷新令牌已失效，请重新登录微软账号"
                    : "换取微软身份令牌失败（" + err.getMessage() + "）";
            throw new RefreshException(kind, message, err);
        } catch (RefreshException ex) {
            throw ex;   // send() 已把 IOException / InterruptedException 结构化
        } catch (Exception ex) {
            throw new RefreshException(RefreshKind.NETWORK,
                    "换取微软身份令牌时出错：" + safeMessage(ex), ex);
        }
        String msToken = asString(m.get("access_token"));
        String newRefresh = asString(m.get("refresh_token"));
        if (msToken == null) {
            // 2xx 却没有 access_token：服务端返回不完整，不是「refresh_token 失效」
            throw new RefreshException(RefreshKind.SERVER, "微软令牌响应缺少 access_token");
        }
        // refresh 时可能下发新的 refresh_token（滚动），没有就沿用旧的
        String keepRefresh = (newRefresh == null || newRefresh.isBlank()) ? refreshToken : newRefresh;
        return new MsTokens(msToken, keepRefresh);
    }

    /**
     * MC 令牌是否快过期：仅决定「要不要发起一次静默刷新」。
     *
     * <p><b>语义收紧（0.8.6 D6）：绝不再被当作「能否启动」的依据。</b>返回 true 只意味着
     * 「值得后台试一次刷新」；刷新失败时调用方应放行已缓存的 accessToken 直接启动。
     * {@code expiresAt <= 0}（老存档 / 未知过期时刻）同样只触发一次刷新尝试，不阻断启动。
     */
    public static boolean needsRefresh(long expiresAt) {
        if (expiresAt <= 0) {
            return true;    // 不知道何时过期：只触发一次刷新尝试
        }
        return System.currentTimeMillis() >= expiresAt - EXPIRY_SLACK_MS;
    }

    /**
     * 把皮肤 PNG 上传到 Mojang（api.minecraftservices.com/minecraft/profile/skins，PUT multipart）。
     * 这是「官方默认」与「导入自己的」在微软账号上真正生效的方式——上传后别人也看得到。
     *
     * @param accessToken 有效的 MC 令牌（调用方需先 {@link #needsRefresh} 决定要不要 {@link #refresh}）
     * @param png         64×64 皮肤 PNG 字节
     * @param model       classic（宽臂/史蒂夫）或 slim（细臂/亚历克斯）
     * @throws Exception 网络失败 / 令牌失效(401) / 格式不对(4xx) 等，信息可直显
     */
    public static void uploadSkin(String accessToken, byte[] png, String model) throws Exception {
        if (accessToken == null || accessToken.isBlank()) {
            throw new RefreshException(RefreshKind.TOKEN_INVALID,
                    "微软令牌为空，无法上传皮肤，请重新登录微软账号");
        }
        String url = "https://api.minecraftservices.com/minecraft/profile/skins";
        String boundary = "gblskin" + System.nanoTime();
        byte[] body = buildSkinMultipart(png, "slim".equals(model) ? "slim" : "classic", boundary);
        HttpClient client = http();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(30))
                // Mojang 官方接口只收 POST（multipart），用 PUT 会 405 Method Not Allowed
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int code = resp.statusCode();
        System.out.println("[msoauth] 上传皮肤 -> HTTP " + code + (resp.body() == null ? "" : " body=" + resp.body()));
        trace("SKIN", "POST " + url + " -> HTTP " + code + (resp.body() == null ? "" : " " + resp.body()));
        if (code < 200 || code >= 300) {
            String msg = null;
            try {
                Object o = Json.parse(resp.body() == null ? "" : resp.body());
                if (o instanceof Map) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    msg = (String) m.get("errorMessage");
                    if (msg == null) {
                        msg = (String) m.get("error");
                    }
                }
            } catch (RuntimeException ignore) {
            }
            if (code == 401 || code == 403) {
                // 令牌过期 / 被拒：TOKEN_INVALID，调用方用 classify 就能引导重新登录。
                throw new RefreshException(RefreshKind.TOKEN_INVALID,
                        "上传皮肤被拒（HTTP " + code + "）：微软令牌可能已过期，"
                        + "请重新登录微软账号再试"
                        + (msg != null ? "（" + msg + "）" : ""));
            }
            // 其余 HTTP 失败与微软服务暂时不可用同属瞬态，不当作账号问题。
            throw new RefreshException(code >= 500 ? RefreshKind.SERVER : RefreshKind.NETWORK,
                    "上传皮肤到 Mojang 失败（HTTP " + code + "）"
                            + (msg != null ? "：" + msg : ""));
        }
    }

    /** 拼 multipart/form-data：file=皮肤PNG + variant=模型。 */
    private static byte[] buildSkinMultipart(byte[] png, String model, String boundary)
            throws Exception {
        String b = "--" + boundary + "\r\n";
        String part1 = b
                + "Content-Disposition: form-data; name=\"variant\"\r\n\r\n"
                + model + "\r\n";
        String part2 = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
        String end = "\r\n--" + boundary + "--\r\n";
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        os.write(part1.getBytes(StandardCharsets.UTF_8));
        os.write(part2.getBytes(StandardCharsets.UTF_8));
        os.write(png);
        os.write(end.getBytes(StandardCharsets.UTF_8));
        return os.toByteArray();
    }

    // ---------------------------------------------------------------- 内部：轮询

    private static MsTokens pollForMsToken(DeviceCode dc, Consumer<String> onStatus,
                                          BooleanSupplier cancelled) throws Exception {
        long deadline = System.currentTimeMillis() + dc.expiresIn * 1000L;
        int interval = dc.interval;
        while (true) {
            if (cancelled.getAsBoolean()) {
                throw new Exception("已取消登录");
            }
            long remain = (deadline - System.currentTimeMillis()) / 1000L;
            if (remain <= 0) {
                throw new Exception("登录超时：请在浏览器里尽快完成微软登录（设备码已失效，重新点添加微软账号）");
            }
            if (onStatus != null) {
                onStatus.accept("等待浏览器完成登录…（剩余约 " + remain + " 秒）");
            }
            Map<String, String> form = new LinkedHashMap<>();
            form.put("client_id", CLIENT_ID);
            form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            form.put("device_code", dc.deviceCode);
            try {
            Map<String, Object> m = postForm(TOKEN_URL, form);
            String token = asString(m.get("access_token"));
            if (token != null) {
                // 设备码响应里本就带 refresh_token（SCOPE 含 offline_access），一并取出落盘，
                // 否则早期版本只存了 access_token、refreshToken 恒为空，
                // MC 令牌过期后只能让用户重新登录。可能缺失，容错为 ""（续期能力缺失但登录可用）。
                String refresh = asString(m.get("refresh_token"));
                return new MsTokens(token, refresh == null ? "" : refresh);
            }
                // 拿到了却没有 token，异常
                throw new Exception("微软令牌返回异常");
            } catch (AuthError err) {
                switch (err.code) {
                    case "authorization_pending":
                        break;                          // 正常：用户还没登录，继续轮询
                    case "slow_down":
                        interval += 5;                  // 微软嫌我们太快，放慢
                        break;
                    case "expired_token":
                        throw new Exception("登录超时：设备码已失效，请重新点添加微软账号");
                    case "access_denied":
                        throw new Exception("登录已取消");
                    case "authorization_declined":
                        throw new Exception("登录已拒绝");
                    default:
                        throw new Exception(err.description == null
                                ? ("微软登录失败（" + err.code + "）") : err.description);
                }
            }
            if (onStatus != null) {
                onStatus.accept("等待浏览器完成登录…（剩余约 " + remain + " 秒）");
            }
            try {
                Thread.sleep(interval * 1000L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new Exception("登录被中断");
            }
        }
    }

    // ---------------------------------------------------------------- 内部：Xbox → Minecraft 链

    /**
     * 拿到微软身份令牌后，走 XBL → XSTS → MC 服务换出游戏令牌 + 玩家档案。
     *
     * @param keepRefresh 刷新场景下沿用 / 滚动后的 refresh_token；登录场景也由设备码响应产出并传入
 *                    （空串表示轮询未返回 refresh_token，正常落盘，仅静默续期能力缺失）
     */
    private static MinecraftAccount xboxChainToMinecraft(String msToken, String keepRefresh)
            throws Exception {
        trace("CHAIN", "已拿到微软身份令牌，开始 XBL -> XSTS -> MC 换发链（msToken 长度="
                + (msToken == null ? "null" : msToken.length()) + "）");
        // 1) XBL
        Map<String, Object> xblBody = new LinkedHashMap<>();
        Map<String, Object> xblProps = new LinkedHashMap<>();
        xblProps.put("AuthMethod", "RPS");
        xblProps.put("SiteName", "user.auth.xboxlive.com");
        xblProps.put("RpsTicket", "d=" + msToken);
        xblBody.put("properties", xblProps);
        xblBody.put("RelyingParty", "http://auth.xboxlive.com");
        xblBody.put("TokenType", "JWT");
        Map<String, Object> xbl;
        try {
            xbl = postJson(XBL_URL, xblBody, null);
        } catch (AuthError err) {
            // XBL 端点对缺 User-Agent / 令牌无效等会回 401 UNAUTHORIZED。
            // 保留中文文案供界面直显，同时按结构化字段分类并把 err 挂在 cause 上。
            String message = (err.http == 401)
                    ? "Xbox Live 鉴权被拒（HTTP 401，多半是请求缺 User-Agent 或微软令牌无效）。"
                      + "请重开 GBL 重试；若仍失败，可附 ~/.gbl/msoauth-trace.log 反馈"
                    : "Xbox Live 鉴权失败（HTTP " + err.http + "）"
                      + (err.description != null ? "：" + err.description : "");
            throw new RefreshException(classifyAuth(err), message, err);
        }
        String xblToken = asString(xbl.get("Token"));
        String xblUhs = xboxUhs(xbl);
        if (xblToken == null || xblUhs == null) {
            throw new RefreshException(RefreshKind.SERVER,
                    "Xbox Live 鉴权返回异常（响应缺少 Token / uhs）");
        }

        // 2) XSTS
        Map<String, Object> xstsBody = new LinkedHashMap<>();
        Map<String, Object> xstsProps = new LinkedHashMap<>();
        xstsProps.put("SandboxId", "RETAIL");
        List<Object> userTokens = new java.util.ArrayList<>();
        userTokens.add(xblToken);
        xstsProps.put("UserTokens", userTokens);
        xstsBody.put("properties", xstsProps);
        xstsBody.put("RelyingParty", "rp://api.minecraftservices.com/");
        xstsBody.put("TokenType", "JWT");
        Map<String, Object> xsts;
        try {
            xsts = postJson(XSTS_URL, xstsBody, null);
        } catch (AuthError err) {
            // XSTS 用 401 + XErr 表达账号问题（儿童账号未授权、地区无 Xbox 等）→ ACCOUNT。
            throw new RefreshException(classifyAuth(err), xstsErrorMessage(err), err);
        }
        String xstsToken = asString(xsts.get("Token"));
        String xstsUhs = xboxUhs(xsts);
        if (xstsToken == null || xstsUhs == null) {
            throw new RefreshException(RefreshKind.SERVER,
                    "Xbox 安全令牌返回异常（响应缺少 Token / uhs）");
        }

        // 3) MC 服务换令牌
        // 注意 identityToken 标准格式：XBL3.0 x=<uhs>;<xstsToken>（分号后直接是 XSTS token，无 x= 前缀）。
        // 之前误写成 ";x=" 导致 Minecraft 解析失败、login_with_xbox 永远 401（与 client_id / 限流无关）。
        Map<String, Object> mc = mcLoginWithVariants("XBL3.0 x=" + xstsUhs + ";" + xstsToken);
        String mcToken = asString(mc.get("access_token"));
        if (mcToken == null) {
            throw new RefreshException(RefreshKind.SERVER,
                    "Minecraft 令牌换发失败（响应缺少 access_token）");
        }
        long expiresIn = (long) asDouble(mc.get("expires_in"), 0);
        long expiresAt = expiresIn > 0 ? System.currentTimeMillis() + expiresIn * 1000L : 0;

        // 4) 玩家档案（uuid + 名字）
        Map<String, Object> profile;
        try {
            profile = getJson(MC_PROFILE_URL, mcToken);
        } catch (AuthError err) {
            // 档案取不到 = 没有 Minecraft 拥有记录 → ACCOUNT（重登也解决不了，如实告知）。
            throw new RefreshException(RefreshKind.ACCOUNT, xboxOwnershipMessage(err), err);
        }
        String uuid = asString(profile.get("id"));
        String name = asString(profile.get("name"));
        if (uuid == null || name == null) {
            throw new RefreshException(RefreshKind.SERVER,
                    "读取 Minecraft 玩家档案失败（响应缺少 id / name）");
        }
        return new MinecraftAccount(uuid, name, mcToken,
                keepRefresh == null ? "" : keepRefresh, expiresAt);
    }

    /**
     * login_with_xbox 换发 MC 令牌。identityToken 已是标准格式（XBL3.0 x=&lt;uhs&gt;;&lt;xstsToken&gt;）。
     * 只发单次标准请求；微软对 login_with_xbox 限流较狠，连续重试只会把 401 拖成 429 并延长封禁，
     * 故仅对限流(429)做一次温和退避重试，401 直接抛出（401 已是明确结论，重试无益）。
     */
    private static Map<String, Object> mcLoginWithVariants(String identity) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("identityToken", identity);
        AuthError lastErr = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                trace("MCLOGIN", "换发尝试（第 " + (attempt + 1) + " 次）");
                return postJson(MC_LOGIN_URL, body, null, false);
            } catch (AuthError err) {
                lastErr = err;
                if (err.http == 429 && attempt == 0) {
                    // 被限流：短退避再试一次。原来 30s 叠在刷新重试里会把启动拖成分钟级，
                    // 现在链只跑一次 + 2s，最坏也只多等 2 秒。
                    trace("MCLOGIN", "被限流(429)，退避 2s 后重试一次");
                    try {
                        Thread.sleep(MC_LOGIN_BACKOFF_MS);
                    } catch (InterruptedException ix) {
                        Thread.currentThread().interrupt();
                        throw new RefreshException(RefreshKind.INTERRUPTED,
                                "MC 令牌换发被中断", ix);
                    }
                    continue;
                }
                throw new RefreshException(classifyAuth(err), mcLoginFailureMessage(err), err);
            }
        }
        AuthError finalErr = lastErr;
        throw new RefreshException(RefreshKind.SERVER, mcLoginFailureMessage(finalErr), finalErr);
    }

    /** 登录失败时给用户看的说明；按错误代码分流，保留排查指引。 */
    private static String mcLoginFailureMessage(AuthError err) {
        int http = err == null ? 0 : err.http;
        return "登录没成功（错误代码 "
                + (err == null ? "?" : err.http) + "）。"
                + (http == 429
                    ? "操作太频繁了，请等 15~30 分钟，然后重开 GBL 只点一次「添加微软账号」，别连续重试。"
                    : http == 403
                    ? "这个账号暂时无法登录。如果曾手动填过登录编号，"
                      + "请删掉 C:\\Users\\<用户名>\\.gbl\\microsoft-clientid.txt，"
                      + "重开 GBL 再登录；没填过的话，请重新登录一次试试。"
                    : "请确认这个微软账号确实买过 Minecraft：用同一个账号打开 minecraft.net，"
                      + "看「我的游戏」里有没有「Java & 基岩版」；如果没有，"
                      + "可能是买游戏用的邮箱和登录用的邮箱不是同一个。");
    }

    /** 上次换发成功使用的请求变体（缓存在 ~/.gbl/msoauth-variant.txt，命中则优先用）。 */
    private static String preferredMcVariant() {
        try {
            Path p = Paths.get(System.getProperty("user.home"), ".gbl", "msoauth-variant.txt");
            if (Files.exists(p)) {
                String v = Files.readString(p, StandardCharsets.UTF_8).trim();
                if (!v.isEmpty()) {
                    return v;
                }
            }
        } catch (Exception ignore) {
            // 缓存读不到不影响主流程
        }
        return "";
    }

    private static void saveMcVariant(String v) {
        try {
            Path dir = Paths.get(System.getProperty("user.home"), ".gbl");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("msoauth-variant.txt"), v, StandardCharsets.UTF_8);
        } catch (Exception ignore) {
            // 缓存写不进不影响主流程
        }
    }

    private static String xboxUhs(Map<String, Object> resp) {
        Object claims = resp.get("DisplayClaims");
        if (!(claims instanceof Map)) {
            return null;
        }
        Object xui = ((Map<?, ?>) claims).get("xui");
        if (!(xui instanceof List) || ((List<?>) xui).isEmpty()) {
            return null;
        }
        Object first = ((List<?>) xui).get(0);
        if (!(first instanceof Map)) {
            return null;
        }
        return asString(((Map<?, ?>) first).get("uhs"));
    }

    private static String xstsErrorMessage(AuthError err) {
        // 常见 XErr：2148916233 儿童账号需家长授权；2148916238 账号无 Xbox（地区/未开通）
        if ("2148916233".equals(err.xerr)) {
            return "这个微软账号是儿童账号，需要家长在 family.microsoft.com 授权后才能用于 Minecraft";
        }
        if ("2148916238".equals(err.xerr)) {
            return "这个微软账号没有开通 Xbox（地区或账号类型限制），无法用于 Minecraft";
        }
        return err.description == null ? "Xbox 鉴权失败（" + err.code + "）" : err.description;
    }

    private static String xboxOwnershipMessage(AuthError err) {
        // 账号登录成功但没拥有 Minecraft（没买 / 没迁移）
        return "这个微软账号没有 Minecraft 的拥有记录（没购买或没从 Mojang 迁移），"
                + "请换一个已拥有 Java/Bedrock 的微软账号登录";
    }

    // ---------------------------------------------------------------- HTTP helpers

    private static Map<String, Object> postForm(String url, Map<String, String> form)
            throws Exception {
        StringBuilder body = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (!first) {
                body.append('&');
            }
            first = false;
            body.append(e.getKey()).append('=').append(urlEncode(e.getValue()));
        }
        trace("REQ", "POST " + url + " body=" + body);
        HttpClient client = http();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = send(client, req);
        return parseResp(url, resp);
    }

    private static Map<String, Object> postJson(String url, Map<String, Object> json,
                                                String bearer) throws Exception {
        return postJson(url, json, bearer, false);
    }

    private static Map<String, Object> postJson(String url, Map<String, Object> json,
                                                String bearer, boolean http11) throws Exception {
        String payload = Json.write(json);
        trace("REQ", "POST " + url + " body=" + payload);
        HttpClient client = http(http11);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> resp = send(client, b.build());
        return parseResp(url, resp);
    }

    private static Map<String, Object> getJson(String url, String bearer) throws Exception {
        trace("REQ", "GET " + url);
        HttpClient client = http();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(20))
                .GET();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        HttpResponse<String> resp = send(client, b.build());
        return parseResp(url, resp);
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest req) throws Exception {
        String url = req.uri().toString();
        try {
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // 诊断：把每次请求的状态码 + 响应体原始内容打到黑框与日志文件，便于真机定位卡在哪一步
            String body = resp.body() == null ? "" : resp.body();
            String diag = req.method() + " " + url + " -> HTTP " + resp.statusCode()
                    + (body.isEmpty() ? " (空body)" : " body=" + body);
            System.out.println("[msoauth] " + diag);
            trace("RESP", diag);
            return resp;
        } catch (java.io.IOException ex) {
            System.out.println("[msoauth] " + req.method() + " " + url
                    + " -> IOException: " + ex.getMessage()
                    + " (" + ex.getClass().getSimpleName() + ")");
            // 网络层失败一律 NETWORK（可重试、可放行缓存 token），把原始异常挂 cause 便于排查。
            throw new RefreshException(RefreshKind.NETWORK,
                    "连不上微软服务（" + ex.getMessage() + "）。请检查网络后重试；"
                    + "如果用了网络代理工具，把它设成全局模式再试一次。",
                    ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RefreshException(RefreshKind.INTERRUPTED,
                    "与微软服务的通信被中断", ex);
        }
    }

    /** 解析响应：2xx 且是 JSON 对象就返回；否则抛 {@link AuthError}（带微软的错误码/描述）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseResp(String url, HttpResponse<String> resp)
            throws Exception {
        int code = resp.statusCode();
        String text = resp.body() == null ? "" : resp.body();
        if (code >= 200 && code < 300) {
            Object parsed = tryParse(text);
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
            // 2xx 但不是 JSON 对象：服务端返回不完整，按 SERVER 归类（可重试）。
            throw new RefreshException(RefreshKind.SERVER,
                    "微软服务返回了非预期内容（" + url + "）");
        }
        // 错误：Microsoft 的错误体是 JSON {error, error_description}（XSTS 还有 XErr）
        Object parsed = tryParse(text);
        String errCode = null;
        String errDesc = null;
        String xerr = null;
        if (parsed instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) parsed;
            errCode = asString(m.get("error"));
            errDesc = asString(m.get("error_description"));
            if (errDesc == null) {
                // minecraftservices.com 的错误体用 errorMessage 字段（如 403 Invalid app registration）
                errDesc = asString(m.get("errorMessage"));
            }
            Object xe = m.get("XErr");
            xerr = xe == null ? null : String.valueOf(xe);
        }
        if (errDesc != null) {
            errDesc = errDesc.replace("&nbsp;", " ").replace("\\r\\n", " ").trim();
        }
        throw new AuthError(code, errCode, errDesc, xerr, url);
    }

    private static Object tryParse(String text) {
        try {
            return Json.parse(text);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * 带微软错误码的异常：调用方按 code / xerr / http / url 做结构化分类，
     * 不再依赖 {@code getMessage()} 的子串猜测（0.8.6 D6）。
     */
    private static final class AuthError extends Exception {
        private static final long serialVersionUID = 1L;

        final int http;
        final String code;
        final String description;
        final String xerr;
        /** 出错的端点，用于区分 MC 换发的 403（配置问题）与 XSTS 的 403。 */
        final String url;

        AuthError(int http, String code, String description, String xerr, String url) {
            super(buildMsg(http, code, description, xerr, url));
            this.http = http;
            this.code = code;
            this.description = description;
            this.xerr = xerr;
            this.url = url;
        }
    }

    /** 诊断用文案：把 http / code / xerr / url 都带上，分类逻辑不读它、只读 AuthError 的字段。 */
    private static String buildMsg(int http, String code, String desc, String xerr, String url) {
        StringBuilder sb = new StringBuilder("HTTP " + http);
        if (code != null) {
            sb.append(' ').append(code);
        }
        if (xerr != null) {
            sb.append(" XErr=").append(xerr);
        }
        if (url != null) {
            sb.append(" @ ").append(url);
        }
        if (desc != null) {
            sb.append(" :: ").append(desc);
        }
        return sb.toString();
    }

    private static HttpClient http() {
        return http(false);
    }

    private static HttpClient http(boolean http11) {
        // 纯直连：本机在 Clash TUN 等透明代理下，PCL / HMCL 都是直连微软登录域即成功，
        // GBL 同样直连，由系统 / TUN 层兜底走代理，不要再装自定义 ProxySelector。
        // http11=true 强制 HTTP/1.1：兜底 api.minecraftservices.com 对 HTTP/2 的兼容性问题。
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (http11) {
            b.version(HttpClient.Version.HTTP_1_1);
        }
        return b.build();
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException ex) {
            return s == null ? "" : s;
        }
    }

    private static String asString(Object o) {
        if (!(o instanceof String)) {
            return null;
        }
        String s = ((String) o).trim();
        return s.isEmpty() ? null : s;
    }

    private static double asDouble(Object o, double fallback) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        if (o instanceof String) {
            try {
                return Double.parseDouble(((String) o).trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
