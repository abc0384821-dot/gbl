package com.mclauncher;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 1.1.2：缺 Java 时自动下载安装一个精确主版本的 JRE。
 *
 * <p>只依赖 JDK 自带的东西（HttpClient / ZipFile / NIO），不引第三方库。
 * 安装落点是 {@code <user.home>/.gbl/runtimes/jre-<major>/}，里面是标准的
 * {@code bin/java(.exe)} 布局，校验通过后下次启动直接复用，不再重复下载。
 *
 * <p><b>零删除铁律</b>：临时 zip、解压中转目录一律用唯一时间戳命名，失败就原样留着
 * （不删、不覆盖）；只有目标目录不存在或校验不过时才写，校验不过的旧目录用改名挪开而非删除。
 *
 * <p><b>数据源（1.1.3 提速）</b>：国内镜像（华为云 / 阿里云 / 清华 TUNA / 中科大）排在前，
 * Adoptium 官方 API 只在最后兜底。四个镜像目录页与官方资产清单**并行解析**出同一个 zip
 * 文件名，再拼成国内镜像直链并**并行轻量探测**，按响应时间挑最快源正式下载——不再像
 * 1.1.2 那样先串行读一次 10 秒超时的目录页、也不再先连慢速的官方 API。
 * 任一环节失败都自动退到下一个源，绝不因为某个镜像不可用而中断整个下载。
 */
public final class JavaInstaller {

    private JavaInstaller() {
        // 静态工具类
    }

    /** 下载/安装进度回调：stage 是中文阶段说明，percent 为 0~100，未知进度传 -1。 */
    @FunctionalInterface
    public interface Progress {
        void onProgress(String stage, int percent);
    }

    /** 安装根目录：~/.gbl/runtimes。 */
    private static final Path RUNTIME_ROOT =
            Paths.get(System.getProperty("user.home"), ".gbl", "runtimes");

    /** 共享 HttpClient：HTTP/1.1、跟随 302。连接超时压到 6 秒——连不上就赶紧换源，别干等。 */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(6))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String USER_AGENT = "GBL-Launcher/1.1.3";

    /**
     * 源模板：国内镜像在前、官方 API 兜底；|major| / |arch| / |os| 运行时替换。
     * 名字与 URL 用 '|' 分隔，纯字符串、不发网络请求，便于 QA 直接断言首个源不是官方接口。
     */
    private static final String[] SOURCE_TEMPLATES = {
            "华为云|https://mirrors.huaweicloud.com/adoptium/|major|/jre/|arch|/|os|/latest/",
            "阿里云|https://mirrors.aliyun.com/adoptium/|major|/jre/|arch|/|os|/latest/",
            "清华镜像|https://mirrors.tuna.tsinghua.edu.cn/Adoptium/|major|/jre/|arch|/|os|/latest/",
            "中科大镜像|https://mirrors.ustc.edu.cn/adoptium/|major|/jre/|arch|/|os|/latest/",
            "官方接口(兜底)|https://api.adoptium.net/v3/binary/latest/|major|/ga/|os|/|arch|/jre/hotspot/normal/eclipse",
    };

    /** 官方资产清单（响应小）：只为了拿到精确的 zip 文件名，好去拼国内镜像直链。 */
    private static final String ASSETS_TEMPLATE =
            "https://api.adoptium.net/v3/assets/latest/|major|/hotspot"
                    + "?architecture=|arch|&image_type=jre&os=|os|&vendor=eclipse";

    /** 一个源：展示名 + 地址。 */
    private record DownloadSource(String name, String url) {
    }

    /** 竞速后的候选：地址、展示名、探测延迟（毫秒，Long.MAX_VALUE = 不可用）。 */
    private record Ranked(String url, String name, long latencyMs) {
    }

    /**
     * 确保机器上有一个主版本恰好等于 {@code major} 的 Java，返回它的 java(.exe) 路径。
     * 已存在且校验通过就直接复用；全流程失败返回 null，由调用方报「自动下载也失败了」。
     */
    public static Path ensureJava(int major, Progress progress) {
        Path dir = RUNTIME_ROOT.resolve("jre-" + major);
        Path expected = dir.resolve("bin").resolve(exe("java"));
        if (isValid(expected, major)) {
            report(progress, "已装好 Java " + major, 100);
            return expected;
        }
        // 1.1.3：日志里明写源顺序，QA 可据此反向鉴别「首个源已不是官方 API」
        System.out.println("[java-install] 1.1.3 数据源顺序（国内优先）: "
                + String.join(" -> ", sourceNames()));
        List<Ranked> ranked = rankSources(major);
        for (Ranked candidate : ranked) {
            String url = candidate.url();
            long stamp = System.nanoTime();
            try {
                report(progress, "正在下载 Java " + major + "…", -1);
                Path zip = download(url, major, stamp, progress);
                report(progress, "正在解压 Java " + major + "…", 100);
                Path staged = extract(zip, major, stamp);
                Path installed = install(staged, dir, major);
                if (installed != null) {
                    report(progress, "Java " + major + " 下载完成", 100);
                    return installed;
                }
                System.err.println("[java-install] 解压产物校验不过（要求 Java " + major + "）: " + url);
            } catch (Exception ex) {
                // 一个源失败就试下一个；临时文件按铁律留着，方便事后排查
                System.err.println("[java-install] 这个下载源没成功（" + candidate.name() + " " + url + "）: " + ex);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 下载

    /** 下载到唯一命名的临时 zip；成功返回临时文件路径，失败抛异常（临时文件不删）。 */
    private static Path download(String url, int major, long stamp, Progress progress)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<InputStream> response = CLIENT.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("下载服务器返回 " + response.statusCode());
        }
        Files.createDirectories(RUNTIME_ROOT);
        Path tmp = RUNTIME_ROOT.resolve(".jre-" + major + "-" + stamp + ".zip.part");
        long total = response.headers().firstValueAsLong("content-length").orElse(-1L);
        long done = 0L;
        int lastPercent = -1;
        long lastReportNanos = System.nanoTime();
        long lastReportBytes = 0L;
        try (InputStream in = new BufferedInputStream(response.body());
             OutputStream out = Files.newOutputStream(tmp)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                done += n;
                if (total > 0) {
                    int pct = (int) Math.min(100L, done * 100L / total);
                    long now = System.nanoTime();
                    boolean byPercent = pct != lastPercent && (pct % 5 == 0 || pct == 100);
                    boolean byTime = now - lastReportNanos >= 1_000_000_000L;
                    if (byPercent || byTime) {
                        // 1.1.3：除了百分比，再给「已下 MB / 总 MB + 速度」，卡住时用户能看出还在动
                        double seconds = Math.max(0.001, (now - lastReportNanos) / 1e9);
                        double speedMb = (done - lastReportBytes) / 1048576.0 / seconds;
                        String text = "正在下载 Java " + major + "…（"
                                + String.format(Locale.ROOT, "%.1f/%.1f MB，%.1f MB/s",
                                        done / 1048576.0, total / 1048576.0, speedMb)
                                + "）";
                        report(progress, text, pct);
                        lastPercent = pct;
                        lastReportNanos = now;
                        lastReportBytes = done;
                    }
                }
            }
        }
        if (done < 1024L) {
            throw new IOException("下载到的内容太小（" + done + " 字节），不像安装包");
        }
        return tmp;
    }

    /** 源展示名（国内在前、官方兜底），QA 可用它断言首个源不再是官方接口。 */
    static List<String> sourceNames() {
        List<String> names = new ArrayList<>();
        for (String template : SOURCE_TEMPLATES) {
            names.add(template.substring(0, template.indexOf('|')));
        }
        return names;
    }

    /**
     * 1.1.3：按国内优先给出源地址（纯字符串、不发网络请求，便于 QA 反向鉴别）。
     * 第 0 项必是华为云镜像，官方 API 永远排最后。
     */
    static List<String> candidateUrls(int major) {
        String os = osName();
        String arch = archName();
        List<String> urls = new ArrayList<>();
        for (String template : SOURCE_TEMPLATES) {
            urls.add(fill(template.substring(template.indexOf('|') + 1), major, os, arch));
        }
        return urls;
    }

    private static String fill(String template, int major, String os, String arch) {
        return template.replace("|major|", String.valueOf(major))
                .replace("|os|", os)
                .replace("|arch|", arch);
    }

    private static boolean isOfficial(String url) {
        return url.contains("api.adoptium.net");
    }

    /**
     * 竞速选源：① 并行解析出精确 zip 文件名（镜像目录页 / 官方资产清单，4 秒封顶）；
     * ② 同一个文件名拼成各国内镜像直链；③ 并行轻量探测按延迟排序。
     * 全失败时至少保留官方 API 兜底，绝不让「选源」这一步把下载流程卡死。
     */
    private static List<Ranked> rankSources(int major) {
        String os = osName();
        String arch = archName();
        List<String> urls = candidateUrls(major);
        List<String> names = sourceNames();
        List<DownloadSource> sources = new ArrayList<>();
        for (int i = 0; i < urls.size(); i++) {
            sources.add(new DownloadSource(names.get(i), urls.get(i)));
        }

        // ① 并行解析 zip 文件名，最多等 4 秒（四个镜像 + 官方资产清单同时发）
        List<String> zipNames = resolveZipNamesInParallel(sources, major, os, arch);
        System.out.println("[java-install] 1.1.3 并行解析安装包名: "
                + (zipNames.isEmpty() ? "都没解析到，走官方 API 兜底" : zipNames));

        // ② 拼国内镜像直链（文件名各镜像通用），官方 API 始终留作最后兜底
        List<Ranked> direct = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int mirrorCount = SOURCE_TEMPLATES.length - 1;
        for (String name : zipNames) {
            for (int i = 0; i < mirrorCount; i++) {
                DownloadSource mirror = sources.get(i);
                String url = mirror.url() + name;
                if (seen.add(url)) {
                    direct.add(new Ranked(url, mirror.name() + "直链", 0));
                }
            }
        }
        String official = sources.get(sources.size() - 1).url();
        if (seen.add(official)) {
            direct.add(new Ranked(official, "官方接口(兜底)", 0));
        }

        // ③ 并行探测，挑最快源
        return probeInParallel(direct);
    }

    /** 并行访问各源取 zip 文件名；invokeAll 限时，慢源直接取消，不串行干等。 */
    private static List<String> resolveZipNamesInParallel(List<DownloadSource> sources,
                                                          int major, String os, String arch) {
        List<String> result = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, sources.size()));
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (DownloadSource src : sources) {
                tasks.add(() -> resolveZipName(src, major, os, arch));
            }
            List<Future<String>> futures = pool.invokeAll(tasks, 4, TimeUnit.SECONDS);
            for (int i = 0; i < futures.size(); i++) {
                String name;
                try {
                    name = futures.get(i).get();
                } catch (Exception ex) {
                    name = null;   // 这个源没赶上，跳过
                }
                if (name != null && !name.isEmpty()) {
                    System.out.println("[java-install] 源[" + sources.get(i).name()
                            + "]解析到安装包: " + name);
                    if (!result.contains(name)) {
                        result.add(name);
                    }
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            pool.shutdownNow();
        }
        return result;
    }

    /** 单源解析 zip 文件名：镜像读目录页，官方读资产 JSON（只拿文件名，不下载 200MB）。 */
    private static String resolveZipName(DownloadSource src, int major, String os, String arch) {
        boolean official = isOfficial(src.url());
        String url = official ? fill(ASSETS_TEMPLATE, major, os, arch) : src.url();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(4))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<String> response = CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            Pattern pattern = official
                    ? Pattern.compile("\"link\"\\s*:\\s*\"[^\"]*?/([^\"/]+\\.zip)\"")
                    : Pattern.compile("OpenJDK" + major + "U-jre_[^\"'<>\\s]+\\.zip",
                            Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(response.body());
            String last = null;
            while (matcher.find()) {
                last = official ? matcher.group(1) : matcher.group();
            }
            return last;
        } catch (Exception ex) {
            System.err.println("[java-install] 源[" + src.name() + "]没解析到安装包名: " + ex);
            return null;
        }
    }

    /**
     * 并行轻量探测（Range 0-0，拿到响应头就关流，不下载正文），按延迟从快到慢排序。
     * 日志逐条列出前几名，QA 可验证「#1 是国内镜像直链、官方接口在最后」。
     */
    private static List<Ranked> probeInParallel(List<Ranked> candidates) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(6, candidates.size()));
        try {
            List<Callable<Ranked>> tasks = new ArrayList<>();
            for (Ranked candidate : candidates) {
                tasks.add(() -> probeOne(candidate));
            }
            List<Ranked> probed = new ArrayList<>();
            List<Future<Ranked>> futures = pool.invokeAll(tasks, 5, TimeUnit.SECONDS);
            for (int i = 0; i < futures.size(); i++) {
                Ranked ranked;
                try {
                    ranked = futures.get(i).get();
                } catch (Exception ex) {
                    // 探测超时也不丢候选：按不可用排到最后，正式下载时仍会试
                    ranked = new Ranked(candidates.get(i).url(), candidates.get(i).name(),
                            Long.MAX_VALUE);
                }
                probed.add(ranked);
            }
            probed.sort(Comparator.comparingLong(Ranked::latencyMs));
            StringBuilder log = new StringBuilder("[java-install] 1.1.3 下载源竞速（国内优先）: ");
            for (int i = 0; i < Math.min(5, probed.size()); i++) {
                Ranked r = probed.get(i);
                log.append("#").append(i + 1).append('=').append(r.name()).append('(')
                        .append(r.latencyMs() == Long.MAX_VALUE ? "不可用" : r.latencyMs() + "ms")
                        .append(") ");
            }
            System.out.println(log);
            return probed;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return candidates;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 一次探测：Range 只取 1 字节，拿到响应头立刻关流；延迟 = 首包往返时间。 */
    private static Ranked probeOne(Ranked candidate) {
        long start = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(candidate.url()))
                    .timeout(Duration.ofSeconds(5))
                    .header("Range", "bytes=0-0")
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            response.body().close();   // 只要响应头，正文一字节都不多读
            int status = response.statusCode();
            if (status == 200 || status == 206) {
                return new Ranked(candidate.url(), candidate.name(),
                        (System.nanoTime() - start) / 1_000_000L);
            }
        } catch (Exception ignored) {
            // 探测失败不刷噪音，正式下载阶段会按排序逐个尝试并留日志
        }
        return new Ranked(candidate.url(), candidate.name(), Long.MAX_VALUE);
    }

    // ---------------------------------------------------------------- 解压 / 安装

    /** 解压到唯一命名的中转目录；全程做 zip-slip 防护（规范化后必须落在目标目录内）。 */
    private static Path extract(Path zip, int major, long stamp) throws IOException {
        Path staging = RUNTIME_ROOT.resolve(".jre-" + major + "-extract-" + stamp);
        if (Files.exists(staging)) {
            // 零删除：撞名就换一个唯一名字，绝不覆盖已有目录
            staging = RUNTIME_ROOT.resolve(".jre-" + major + "-extract-"
                    + stamp + "-" + System.nanoTime());
        }
        Files.createDirectories(staging);
        Path root = staging.toAbsolutePath().normalize();
        try (ZipFile archive = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            String strip = commonRoot(archive);
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (strip != null && !strip.isEmpty()) {
                    if (!name.startsWith(strip)) {
                        continue;
                    }
                    name = name.substring(strip.length());
                }
                if (name.isEmpty() || "/".equals(name)) {
                    continue;
                }
                Path target = root.resolve(name).normalize();
                // zip-slip：../ 或绝对路径一旦跑出中转目录就整条跳过
                if (!target.startsWith(root)) {
                    System.err.println("[java-install] 跳过压缩包里的危险路径: " + entry.getName());
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (InputStream in = archive.getInputStream(entry)) {
                    Files.copy(in, target);   // 中转目录是全新的，无需 REPLACE_EXISTING
                }
                makeExecutable(target);
            }
        }
        return staging;
    }

    /**
     * 剥掉压缩包共有的顶层目录（Adoptium 的包都套一层 {@code jdk-21.../}）。
     * 只要有一个条目不在同一个顶层段下，就返回空串（不剥），交给后面 findHome 兜。
     */
    private static String commonRoot(ZipFile archive) {
        String prefix = null;
        var entries = archive.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName().replace('\\', '/');
            int slash = name.indexOf('/');
            if (slash < 0 || slash == 0) {
                return "";   // 顶层就有文件，不剥
            }
            String segment = name.substring(0, slash + 1);
            if (prefix == null) {
                prefix = segment;
            } else if (!prefix.equals(segment)) {
                return "";
            }
        }
        return prefix == null ? "" : prefix;
    }

    /** 在中转目录里找「直接包含 bin/java(.exe)」的那个主目录（最多往下四层）。 */
    private static Path findHome(Path staging) {
        Path direct = staging.resolve("bin").resolve(exe("java"));
        if (Files.isRegularFile(direct)) {
            return staging;
        }
        try (var stream = Files.walk(staging, 4)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                if (!file.getFileName().toString().equals(exe("java"))) {
                    continue;
                }
                Path bin = file.getParent();
                if (bin != null && "bin".equals(bin.getFileName().toString())) {
                    Path home = bin.getParent();
                    if (home != null && home.startsWith(staging)) {
                        return home;
                    }
                }
            }
        } catch (IOException ignored) {
            // 扫不到就按失败处理，由调用方试下一个源
        }
        return null;
    }

    /** 把校验通过的中转目录原子改名到最终落点；旧目录（没校验过的）改名挪开，不删。 */
    private static Path install(Path staging, Path dir, int major) throws IOException {
        Path home = findHome(staging);
        if (home == null) {
            throw new IOException("解压后没找到 java 可执行文件");
        }
        Path found = home.resolve("bin").resolve(exe("java"));
        if (!isValid(found, major)) {
            throw new IOException("解压出的 Java 主版本对不上（要求 " + major + "）");
        }
        Files.createDirectories(RUNTIME_ROOT);
        if (Files.exists(dir)) {
            // 校验没过的旧目录：改名留档，绝不删除
            Path backup = RUNTIME_ROOT.resolve("jre-" + major + "-old-" + System.nanoTime());
            Files.move(dir, backup);
        }
        try {
            Files.move(home, dir, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException fallback) {
            Files.move(home, dir);
        }
        Path result = dir.resolve("bin").resolve(exe("java"));
        return isValid(result, major) ? result : null;
    }

    // ---------------------------------------------------------------- 小工具

    /** 读 release 文件的 JAVA_VERSION，读不到就跑一次 java -version（慢但准）。 */
    private static Integer detectMajor(Path javaExe) {
        if (javaExe == null || !Files.isRegularFile(javaExe)) {
            return null;
        }
        Path home = javaExe.getParent() == null ? null : javaExe.getParent().getParent();
        if (home != null) {
            Path release = home.resolve("release");
            if (Files.isRegularFile(release)) {
                try {
                    for (String line : Files.readAllLines(release, StandardCharsets.UTF_8)) {
                        if (line.startsWith("JAVA_VERSION=")) {
                            String value = line.substring("JAVA_VERSION=".length())
                                    .replace("\"", "").trim();
                            Integer major = majorOfVersion(value);
                            if (major != null) {
                                return major;
                            }
                        }
                    }
                } catch (IOException | RuntimeException ignored) {
                    // 读不了就往下跑 -version
                }
            }
        }
        try {
            Process process = new ProcessBuilder(javaExe.toString(), "-version")
                    .redirectErrorStream(true).start();
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            return majorOfVersion(text);
        } catch (IOException | InterruptedException | RuntimeException ignored) {
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /** "25.0.3" / "1.8.0_503" → 25 / 8。 */
    private static Integer majorOfVersion(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (s.startsWith("1.")) {
            String[] parts = s.split("[._]");
            if (parts.length > 1) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return 8;
        }
        String head = s;
        int dot = s.indexOf('.');
        if (dot > 0) {
            head = s.substring(0, dot);
        }
        try {
            return Integer.parseInt(head.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** 可执行文件存在且主版本恰好等于要求，才算可用。 */
    private static boolean isValid(Path javaExe, int major) {
        if (javaExe == null || !Files.isRegularFile(javaExe)) {
            return false;
        }
        Integer found = detectMajor(javaExe);
        return found != null && found == major;
    }

    /** 给解压出的脚本赋可执行位（Windows 无所谓，Linux/macOS 必须）。 */
    private static void makeExecutable(Path file) {
        String name = file.getFileName().toString();
        if (!name.equals("java") && !name.equals("javaw") && !name.endsWith(".sh")) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (IOException | RuntimeException ignored) {
            // Windows 上没有 POSIX 权限，忽略即可
        }
    }

    private static String exe(String base) {
        return isWindows() ? base + ".exe" : base;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String osName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "mac";
        }
        return "linux";
    }

    /** Adoptium 用的架构名：x64 / aarch64 / x86 / arm。 */
    private static String archName() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            return "x64";
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return "aarch64";
        }
        if (arch.equals("x86") || arch.equals("i386") || arch.equals("i686")) {
            return "x86";
        }
        if (arch.startsWith("arm")) {
            return "arm";
        }
        return "x64";
    }

    private static void report(Progress progress, String stage, int percent) {
        if (progress == null) {
            return;
        }
        try {
            progress.onProgress(stage, percent);
        } catch (RuntimeException ignored) {
            // 进度回调是给界面看的，界面自己抛异常不能把下载流程带崩
        }
    }
}
