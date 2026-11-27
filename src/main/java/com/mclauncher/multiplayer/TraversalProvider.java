package com.mclauncher.multiplayer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ForkJoinPool;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联机引擎（Terracotta v0.4.2 Windows x86_64）二进制服务层：
 * 按需下载 → SHA256 校验 → 解压 → 子进程启动 → 端口发现 → 守护。
 *
 * <p><b>落点结构（架构定稿）</b>：
 * <pre>
 * ~/.gbl/multiplayer/
 *   cache/
 *     terracotta-0.4.2-windows-x86_64-pkg.tar.gz   # 固定暂存，覆盖写；校验失败改名 .bad-&lt;ts&gt; 保留
 *     staging-&lt;yyyyMMddHHmmss&gt;/                     # 解压中转，失败保留不删
 *   engines/
 *     terracotta-0.4.2-&lt;yyyyMMddHHmmss&gt;/            # 唯一时间戳新建，不覆盖已有
 *       terracotta-0.4.2-windows-x86_64.exe
 *       VCRUNTIME140.DLL
 *       port.txt                                    # 占位，引擎端口由调用方传入的 portFile 承载
 *       .complete                                   # exe+DLL 齐全且校验通过后才写
 * </pre>
 * <b>零删除铁律</b>：本层不删任何文件——暂存失败改名 .bad-&lt;ts&gt; 保留，解压中转失败原样保留，
 * 安装目录一旦唯一新建就不再覆盖。
 *
 * <p>本类不碰 JavaFX：所有网络 / 文件 / 进程操作在 ForkJoinPool.commonPool 的后台线程上跑，
 * 回调发生在后台线程，调用方自行 {@code Platform.runLater}。
 * 用 {@link #get()} 取单例后调用实例方法；{@link #recentLog()} 为静态全局日志口。
 */
public final class TraversalProvider {

    public static final String ASSET = "terracotta-0.4.2-windows-x86_64-pkg.tar.gz";
    public static final String SHA256 = "07ebe139e3ca5f74576e58b1a96efe59abdfbe148d3f1a49bfdca8b6f70745f0";
    public static final String MIRROR_GITEE = "https://gitee.com/burningtnt/Terracotta/releases/download/v0.4.2/" + ASSET;
    public static final String MIRROR_CNB = "https://cnb.cool/HMCL-Terracotta/Terracotta/-/releases/download/v0.4.2/" + ASSET;

    private static final String EXE_NAME = "terracotta-0.4.2-windows-x86_64.exe";
    private static final String DLL_NAME = "VCRUNTIME140.DLL";
    private static final Set<String> KEEP_NAMES = Set.of(EXE_NAME, DLL_NAME);

    /** 1.2.1 引擎内置：两份二进制的 SHA256 定死，供内置目录强校验（与 vendor/ 逐字节一致）。 */
    static final String EXE_SHA256 = "74c10568a7fea9c1d38cf8d2d4ca90baf1517f8e5a26c63d3349db70bc449796";
    static final String DLL_SHA256 = "475ab98b7722e965bd38c8fa6ed23502309582ccf294ff1061cb290c7988f0d1";

    private static final Path BASE = Paths.get(System.getProperty("user.home"), ".gbl", "multiplayer");
    private static final Path ENGINES_DIR = BASE.resolve("engines");
    private static final Path CACHE_DIR = BASE.resolve("cache");
    private static final Path STAGING_TAR = CACHE_DIR.resolve(ASSET);
    private static final Path CANDIDATE_INSTALL_DIR = ENGINES_DIR.resolve("terracotta-0.4.2");

    /** 1.2.1 内置引擎目录：发行包内 GBL/engines/terracotta/（与 jre/、runtime/ 平级，只读零写入）。 */
    static final Path BUILTIN_ENGINE_DIR = appRoot().resolve("engines").resolve("terracotta");

    private static final String ENGINE_PREFIX = "terracotta-0.4.2-";
    private static final String COMPLETE_MARKER = ".complete";
    private static final String PORT_TXT = "port.txt";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Pattern ENGINE_DIR_RE =
            Pattern.compile("^terracotta-0\\.4\\.2-(\\d{14})(?:-(\\d+))?$");
    private static final long MIN_EXE_SIZE = 1024L * 1024L;   // 1 MB

    private static volatile Process currentProcess;
    private static volatile Integer lastExitCode;

    private static final int LOG_CAP = 200;
    private static final ArrayDeque<String> LOG_LINES = new ArrayDeque<>();

    private static final Pattern PORT_RE = Pattern.compile("\"port\"\\s*:\\s*(\\d+)");
    private static final Pattern META_VERSION_RE =
            Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");
    private static final HttpClient META_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private static volatile TraversalProvider instance;

    public TraversalProvider() {
    }

    public static TraversalProvider get() {
        TraversalProvider p = instance;
        if (p == null) {
            synchronized (TraversalProvider.class) {
                p = instance;
                if (p == null) {
                    instance = p = new TraversalProvider();
                }
            }
        }
        return p;
    }

    /** 当前解析到的 exe 路径；未安装时返回预期候选路径。 */
    public Path exePath() {
        Path dir = resolveInstalledDir();
        return (dir != null ? dir : CANDIDATE_INSTALL_DIR).resolve(EXE_NAME);
    }

    /** 当前解析到的引擎目录；未安装时返回预期候选路径。 */
    public Path installDir() {
        Path dir = resolveInstalledDir();
        return dir != null ? dir : CANDIDATE_INSTALL_DIR;
    }

    /**
     * 应用根目录：与 {@code GameLauncher.appDir()} 同款——取 {@code GBL.exe} 所在目录
     * （{@code ProcessHandle.current().info().command()} 的父目录），探不到退当前工作目录（IDE 跑时）。
     */
    static Path appRoot() {
        try {
            java.util.Optional<String> cmd = ProcessHandle.current().info().command();
            if (cmd.isPresent() && !cmd.get().isBlank()) {
                Path parent = Paths.get(cmd.get()).toAbsolutePath().normalize().getParent();
                if (parent != null) {
                    return parent;
                }
            }
        } catch (RuntimeException ignored) {
            // 某些 JVM / 权限下拿不到命令行，按工作目录兜
        }
        return Paths.get("").toAbsolutePath().normalize();
    }

    /**
     * 1.2.1 三级引擎解析（离线冷启动优先）：
     * <ol>
     *   <li><b>内置</b> {@link #BUILTIN_ENGINE_DIR}（发行包内 GBL/engines/terracotta/）——
     *       若 {@code .complete} 标记 + exe/DLL 齐全即直接命中，<b>无需联网</b>，最高优先；</li>
     *   <li><b>用户目录</b> 扫 {@code ~/.gbl/multiplayer/engines/}（服务更新上来的老用户与已装过者）；</li>
     *   <li><b>联网兜底</b> 前两层都没有则返回 {@code null}，由 {@code ensureInstalledAsync()} 走下载。</li>
     * </ol>
     * 内置目录为只读资产，本方法对其<b>零写入</b>；写入只发生在第 3 层的 {@code ~/.gbl}。
     */
    public boolean isInstalled() {
        return resolveInstalledDir() != null;
    }

    private static Path resolveInstalledDir() {
        // 第 1 层：内置目录（离线冷启动即用）。命中即返回，绝不回退到联网。
        Path builtin = builtinEngineDir();
        if (isCompleteEngine(builtin)) {
            return builtin;
        }
        // 第 2 层：~/.gbl 版本化目录（老用户 / 已装过者）。
        return scanUserEnginesDir();
    }

    /** 内置目录是否存在且非空；不存在返回自身路径（供 isCompleteEngine 判 false）。 */
    private static Path builtinEngineDir() {
        return BUILTIN_ENGINE_DIR;
    }

    /** 扫 {@code ~/.gbl/multiplayer/engines/} 找 .complete 齐全的版本化目录；取时间戳最新。 */
    private static Path scanUserEnginesDir() {
        try {
            if (!Files.isDirectory(ENGINES_DIR)) {
                return null;
            }
            try (var stream = Files.list(ENGINES_DIR)) {
                return stream.filter(Files::isDirectory)
                        .filter(TraversalProvider::isCompleteEngine)
                        .max(Comparator.comparingLong(TraversalProvider::engineOrder))
                        .orElse(null);
            }
        } catch (IOException | RuntimeException ex) {
            logLine("扫描引擎目录失败: " + ex);
            return null;
        }
    }

    /** 目录名形如 terracotta-0.4.2-&lt;14位时间戳&gt;[-后缀] 才参与排序；不认识的名字排最前。 */
    private static long engineOrder(Path dir) {
        Matcher m = ENGINE_DIR_RE.matcher(dir.getFileName().toString());
        if (!m.matches()) {
            return -1L;
        }
        long ts = Long.parseLong(m.group(1));
        int suffix = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        return ts * 1000L + suffix;
    }

    /** 完整引擎目录判定：.complete 标记 + exe(>1MB) + DLL 必须全都存在。 */
    private static boolean isCompleteEngine(Path dir) {
        try {
            return Files.isRegularFile(dir.resolve(COMPLETE_MARKER))
                    && Files.isRegularFile(dir.resolve(EXE_NAME))
                    && Files.size(dir.resolve(EXE_NAME)) > MIN_EXE_SIZE
                    && Files.isRegularFile(dir.resolve(DLL_NAME))
                    && Files.size(dir.resolve(DLL_NAME)) > 0;
        } catch (IOException | RuntimeException ex) {
            return false;
        }
    }

    /** 子进程是否仍存活；未启动或已退出均为 false。 */
    public boolean isProcessAlive() {
        Process p = currentProcess;
        return p != null && p.isAlive();
    }

    /** 最近日志（含子进程 stdout/stderr），供 UI 取用；调用方按需切回 FX 线程。 */
    public static String recentLog() {
        synchronized (LOG_LINES) {
            return String.join("\n", LOG_LINES);
        }
    }

    /** 强制终止联机引擎子进程，幂等。 */
    public void terminateNow() {
        Process p = currentProcess;
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
            logLine("已强制终止联机引擎子进程");
        }
    }

    // ---------------------------------------------------------------- 安装

    /** 确保引擎已安装：已安装直接回 exe 路径；否则下载 → 校验 → 解压。 */
    public void ensureInstalledAsync(Callback<Path> cb) {
        if (isInstalled()) {
            cb.onResult(exePath(), null);
            return;
        }
        runAsync(() -> {
            try {
                ensureInstalled();
                cb.onResult(exePath(), null);
            } catch (Throwable t) {
                cb.onResult(null, t);
            }
        });
    }

    private static void ensureInstalled() throws IOException {
        logLine("开始下载联机引擎 " + ASSET);
        // 主源 Gitee → 备源 CNB，任一 SHA256 命中即用。
        boolean ok = false;
        String lastReason = "";
        for (String mirror : new String[]{MIRROR_GITEE, MIRROR_CNB}) {
            try {
                logLine("下载源: " + mirror);
                download(mirror);
                String actual = sha256(STAGING_TAR);
                logLine("SHA256=" + actual);
                if (SHA256.equalsIgnoreCase(actual)) {
                    ok = true;
                    logLine("SHA256 校验通过");
                    break;
                }
                lastReason = "SHA256 不匹配（实际 " + actual + "）";
                logLine(lastReason + "，切换备源");
            } catch (IOException ex) {
                lastReason = "下载失败: " + ex.getMessage();
                logLine(lastReason + "，切换备源");
            }
            renameBadStaging();   // 坏包改名 .bad-<ts> 保留，绝不删
        }
        if (!ok) {
            throw new IOException("联机引擎下载失败：主备镜像均未通过校验。" + lastReason
                    + "。坏文件已改名保留，未删除任何文件。");
        }

        Path stagingDir = uniqueDir(CACHE_DIR, "staging-");
        Files.createDirectories(stagingDir);
        // 主路径：纯 JDK 解压；失败再用 Windows 自带 tar.exe 兜底。
        try {
            TarGzExtractor.extract(STAGING_TAR, stagingDir, KEEP_NAMES);
            logLine("解压完成（TarGzExtractor）→ " + stagingDir);
        } catch (IOException ex) {
            logLine("TarGzExtractor 解压失败，尝试系统 tar 兜底: " + ex);
            if (!trySystemTarFallback(STAGING_TAR, stagingDir)) {
                // 解压中转失败：原样保留，绝不删
                throw new IOException("联机引擎解压失败（中转目录已保留 " + stagingDir + "）："
                        + ex.getMessage(), ex);
            }
            logLine("解压完成（系统 tar 兜底）");
        }

        Path exe = stagingDir.resolve(EXE_NAME);
        Path dll = stagingDir.resolve(DLL_NAME);
        if (!Files.isRegularFile(exe) || !Files.isRegularFile(dll)) {
            throw new IOException("解压后 exe / DLL 不齐全（中转目录已保留 " + stagingDir + "）");
        }
        long size = Files.size(exe);
        if (size <= MIN_EXE_SIZE) {
            throw new IOException("解压所得 exe 体积异常（" + size + " 字节），拒绝启动。");
        }

        Path target = uniqueDir(ENGINES_DIR, ENGINE_PREFIX);
        Files.createDirectories(ENGINES_DIR);
        try {
            Files.move(stagingDir, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(stagingDir, target);
        }
        logLine("引擎目录已就位: " + target);

        // 先补齐 port.txt 占位，最后才写 .complete —— 标记出现即表示整个目录可用。
        Files.writeString(target.resolve(PORT_TXT), "", StandardCharsets.UTF_8);
        Files.writeString(target.resolve(COMPLETE_MARKER),
                LocalDateTime.now().format(TS), StandardCharsets.UTF_8);
        logLine("引擎就绪: " + target.resolve(EXE_NAME) + " (" + size + " 字节)");
    }

    /** 下载 / 校验失败：坏包改名 .bad-&lt;ts&gt; 保留，绝不删除。 */
    private static void renameBadStaging() {
        try {
            if (Files.isRegularFile(STAGING_TAR)) {
                Path bad = STAGING_TAR.resolveSibling(ASSET + ".bad-" + LocalDateTime.now().format(TS));
                Files.move(STAGING_TAR, bad);
                logLine("坏包已保留为 " + bad);
            }
        } catch (IOException | RuntimeException ex) {
            logLine("坏包改名失败（原样保留）: " + ex);
        }
    }

    /** 在 parent 下生成唯一目录名 prefix+时间戳（同秒用 -2/-3... 区分），不覆盖已有。 */
    private static Path uniqueDir(Path parent, String prefix) {
        String ts = LocalDateTime.now().format(TS);
        Path candidate = parent.resolve(prefix + ts);
        int n = 2;
        while (Files.exists(candidate)) {
            candidate = parent.resolve(prefix + ts + "-" + n++);
        }
        return candidate;
    }

    private static void download(String url) throws IOException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "GBL-Launcher/1.1.5")
                .GET()
                .build();
        HttpResponse<InputStream> resp;
        try {
            resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断", ie);
        }
        int code = resp.statusCode();
        if (code < 200 || code >= 300) {
            try {
                resp.body().close();
            } catch (IOException ignored) {
                // 关闭失败无所谓
            }
            throw new IOException("HTTP " + code);
        }
        Files.createDirectories(CACHE_DIR);
        // 固定路径覆盖写（CREATE + TRUNCATE_EXISTING），失败也保留临时文件，不 delete。
        try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(STAGING_TAR)) {
            in.transferTo(out);
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IOException("JDK 不支持 SHA-256", ex);
        }
    }

    /** Windows 自带 tar.exe 兜底：解到唯一临时目录，再按文件名拷贝所需文件；中转失败原样保留。 */
    private static boolean trySystemTarFallback(Path tarGz, Path destDir) {
        String win = System.getenv("WINDIR");
        Path tar = null;
        if (win != null && !win.isBlank()) {
            Path candidate = Paths.get(win, "System32", "tar.exe");
            if (Files.isExecutable(candidate)) {
                tar = candidate;
            }
        }
        if (tar == null) {
            return false;
        }
        try {
            Path unpacked = uniqueDir(CACHE_DIR, "tar-unpack-");
            Files.createDirectories(unpacked);
            ProcessBuilder pb = new ProcessBuilder(tar.toString(), "-xzf", tarGz.toString(),
                    "-C", unpacked.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code != 0) {
                logLine("系统 tar 解压失败 code=" + code + " " + out
                        + "（中转目录已保留 " + unpacked + "）");
                return false;
            }
            Files.createDirectories(destDir);
            for (String name : KEEP_NAMES) {
                Path found = findByName(unpacked, name);
                if (found != null) {
                    Files.copy(found, destDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return Files.isRegularFile(destDir.resolve(EXE_NAME))
                    && Files.isRegularFile(destDir.resolve(DLL_NAME));
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logLine("系统 tar 兜底异常: " + ex);
            return false;
        }
    }

    private static Path findByName(Path root, String name) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
        }
    }

    // ---------------------------------------------------------------- 启动 / 端口发现

    /**
     * 启动引擎并发现 HTTP 端口。后台执行，立即返回；成功回 port，失败回 error。
     * portFile 由调用方传入，本层只读不删。
     */
    public void launchAsync(Path portFile, Callback<Integer> cb) {
        runAsync(() -> {
            try {
                Path exe = exePath();
                if (!Files.isRegularFile(exe)) {
                    throw new IOException("联机引擎未安装：请先 ensureInstalledAsync");
                }
                boolean preExisting = Files.exists(portFile);
                String preContent = preExisting ? readAll(portFile) : null;
                long preMtime = preExisting ? Files.getLastModifiedTime(portFile).toMillis() : -1L;

                Files.createDirectories(exe.getParent());
                ProcessBuilder pb = new ProcessBuilder(exe.toString(), "--hmcl", portFile.toString());
                pb.directory(installDir().toFile());
                Process p = pb.start();
                currentProcess = p;
                lastExitCode = null;
                logLine("引擎已启动 pid=" + p.pid() + " portFile=" + portFile);
                drain(p.getInputStream(), "out");
                drain(p.getErrorStream(), "err");
                watch(p);

                int port = discoverPort(portFile, preExisting, preContent, preMtime);
                if (!metaOk(port)) {
                    throw new IOException("端口 " + port + " 的 /meta 未返回 version，引擎可能未就绪");
                }
                logLine("端口发现成功: " + port);
                cb.onResult(port, null);
            } catch (Throwable t) {
                cb.onResult(null, t);
            }
        });
    }

    /** 轮询端口文件（200ms/次，总 20s），再回退到 %TEMP%/terracotta/terracotta.lock。 */
    private static int discoverPort(Path portFile, boolean preExisting, String preContent, long preMtime)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Integer port = readPortFromFile(portFile, preExisting, preContent, preMtime);
            if (port == null) {
                port = readLockPort();
            }
            if (port != null && port > 0 && port <= 65535) {
                return port;
            }
            Thread.sleep(200);
        }
        throw new IOException("20 秒内未从端口文件 / 锁文件发现可用端口，请查看 recentLog()");
    }

    private static Integer readPortFromFile(Path portFile, boolean preExisting, String preContent, long preMtime) {
        try {
            if (!Files.isRegularFile(portFile)) {
                return null;
            }
            String content = readAll(portFile);
            if (content == null || content.isBlank()) {
                return null;
            }
            // 忽略启动前就存在的旧内容，避免误读上次遗留端口。
            if (preExisting && content.equals(preContent)
                    && Files.getLastModifiedTime(portFile).toMillis() <= preMtime) {
                return null;
            }
            Matcher m = PORT_RE.matcher(content);
            if (m.find()) {
                int v = Integer.parseInt(m.group(1));
                if (v > 0 && v <= 65535) {
                    return v;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 文件可能正在被写入，下一轮再读
        }
        return null;
    }

    /** 锁文件格式：2 字节大端 unsigned short。 */
    private static Integer readLockPort() {
        String tmp = System.getenv("TEMP");
        if (tmp == null || tmp.isBlank()) {
            tmp = System.getProperty("java.io.tmpdir");
        }
        if (tmp == null || tmp.isBlank()) {
            return null;
        }
        Path lock = Paths.get(tmp, "terracotta", "terracotta.lock");
        try {
            byte[] b = Files.readAllBytes(lock);
            if (b.length >= 2) {
                int v = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
                if (v > 0 && v <= 65535) {
                    return v;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 锁文件不存在或尚未生成，正常
        }
        return null;
    }

    /**
     * 1.2：启动前探测可复用引擎——端口文件与 %TEMP%/terracotta/terracotta.lock 里的端口，
     * 任一 /meta 健康即返回该端口。上一次 GBL 运行留下的正常引擎也算，直接复用，
     * 避免再启动一个会因单实例/端口冲突秒退的短命进程。
     */
    public static Integer findReusablePort(Path portFile) {
        LinkedHashSet<Integer> candidates = new LinkedHashSet<>();
        Integer fromFile = readAnyPortFromFile(portFile);
        if (fromFile != null) {
            candidates.add(fromFile);
        }
        Integer fromLock = readLockPort();
        if (fromLock != null) {
            candidates.add(fromLock);
        }
        for (int port : candidates) {
            if (port > 0 && port <= 65535 && metaOk(port)) {
                logLine("发现可复用引擎端口 " + port + "（跳过新进程启动）");
                return port;
            }
        }
        return null;
    }

    /** 读端口文件当前内容，不做「启动前旧内容」过滤，专供复用探测使用。 */
    private static Integer readAnyPortFromFile(Path portFile) {
        try {
            if (!Files.isRegularFile(portFile)) {
                return null;
            }
            String content = readAll(portFile);
            if (content == null || content.isBlank()) {
                return null;
            }
            Matcher m = PORT_RE.matcher(content);
            if (m.find()) {
                int v = Integer.parseInt(m.group(1));
                if (v > 0 && v <= 65535) {
                    return v;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 文件可能正在被写入，交给正常启动流程处理
        }
        return null;
    }

    /** GET http://127.0.0.1:N/meta，要求 200 且 version 非空。 */
    public static boolean metaOk(int port) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/meta"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> resp = META_CLIENT.send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200 || resp.body() == null) {
                return false;
            }
            Matcher m = META_VERSION_RE.matcher(resp.body());
            return m.find() && !m.group(1).isBlank();
        } catch (IOException | RuntimeException ex) {
            return false;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String readAll(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- 后台设施

    private static void runAsync(Runnable task) {
        ForkJoinPool.commonPool().execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                logLine("后台任务异常: " + t);
            }
        });
    }

    private static void drain(InputStream in, String tag) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    logLine("[" + tag + "] " + line);
                }
            } catch (IOException ignored) {
                // 流关闭属正常
            }
        }, "gbl-terracotta-" + tag);
        t.setDaemon(true);
        t.start();
    }

    /** 守护线程：记录意外退出状态，供 isProcessAlive / RoomService 查询。不自动重启。 */
    private static void watch(Process p) {
        Thread t = new Thread(() -> {
            try {
                int code = p.waitFor();
                lastExitCode = code;
                logLine("联机引擎子进程退出，code=" + code);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, "gbl-terracotta-watch");
        t.setDaemon(true);
        t.start();
    }

    private static void logLine(String message) {
        String line = LocalTime.now().withNano(0) + " " + message;
        synchronized (LOG_LINES) {
            while (LOG_LINES.size() >= LOG_CAP) {
                LOG_LINES.pollFirst();
            }
            LOG_LINES.addLast(line);
        }
        System.err.println("[traversal] " + line);
    }
}
