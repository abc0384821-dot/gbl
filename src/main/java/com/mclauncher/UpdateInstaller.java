package com.mclauncher;

import javafx.application.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * 自动更新：下载 → 校验 → 覆盖当前安装目录 → 重启 GBL → 退出旧进程。
 *
 * <p><b>0.9.8 修复「更新后还是旧版本」</b>：便携包里 420 个条目全部带 {@code GBL/} 顶层前缀
 * （{@code pack_*.py} 手工加的，供用户解压出 {@code GBL/} 子目录）。旧版解压直接把条目名
 * resolve 到 {@code Platform.appDir()} —— 而 appDir 本身已经是 {@code GBL/} 根，结果整套新版被
 * 解进 {@code GBL/GBL/...} 影子目录，真正在跑的 exe / jar / modules 一个字节没换，流程却全绿、
 * 还重启了旧 exe。现在先探测并剥掉统一顶层前缀，再写进安装根。
 *
 * <p><b>覆盖策略（实测）</b>：GBL 运行时 {@code runtime/lib/modules}（应用类真正所在）、
 * {@code app/*.jar}、{@code app/GBL.cfg} 都可写；只有 {@code GBL.exe} 与
 * {@code runtime/bin/*.dll}、{@code jre/bin/*.dll} 被系统锁住。exe 只是 jpackage 通用启动壳，
 * 读 {@code app/GBL.cfg} 加载模块，不需要替换；被锁的 dll 跳过即可。关键文件覆盖后逐个做
 * CRC32 比对，对不上就明确报错，绝不「假成功」。
 */
public final class UpdateInstaller {

    /** 强制 HTTP/1.1：高延迟链路上 HTTP/2 会把窗口压死、吞吐掉到 KB/s。 */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            // 0.9.9 r4：跟随「线路」设置（ProxyRoute 决定直连 / 系统代理 / 镜像线路）。
            // 旧版这里没设 selector，用户设了代理也只有游戏下载走代理、更新下载仍然裸连，
            // 于是「别人机器上更新总是失败」。selector() 内部按 URL 决定，直连时返回 DIRECT。
            .proxy(ProxyRoute.selector())
            .build();

    /** 响应头超时。Gitee 附件直链有两级 302，NORMAL 会跟随。 */
    private static final Duration HEAD_TIMEOUT = Duration.ofSeconds(90);

    /** 下载进度每累计这么多字节、或每过这么久，才回刷一次 UI，避免上千个 FX 任务。 */
    private static final long PROGRESS_BYTES = 2L * 1024 * 1024;
    private static final long PROGRESS_NANOS = TimeUnit.MILLISECONDS.toNanos(140);

    /** 0.9.8 指纹：更新链路修复（构建门禁扫 runtime/lib/modules 常量池）。 */
    public static final String FP_098_UPDATE = "0.9.8 更新覆盖路径与下载提速";
    static { System.err.println("[update] " + FP_098_UPDATE); }

    /** 0.9.8 r3 指纹：更新包缺关键文件即失败。构建门禁扫常量池，零引用也留在常量池，勿改这串字。 */
    private static final String FP_098_R3_INSTALL = "0.9.8 r3 更新包关键文件校验";
    static { System.err.println("[update] " + FP_098_R3_INSTALL); }

    /** 0.9.9 r2 指纹：更新下载失败后同一源自动重试 + 递增退避。构建门禁扫常量池，勿改这串字。 */
    public static final String FP_099_R2_RETRY = "0.9.9 r2 更新下载失败自动重试";
    static { System.err.println("[update] " + FP_099_R2_RETRY); }

    /**
     * 0.9.9 r4 指纹：下载停滞看门狗 + 真实失败原因回传界面。
     * 构建门禁会扫 runtime/lib/modules 的常量池，零引用也留在常量池，勿改这串字。
     */
    public static final String FP_099_R4_WATCHDOG = "0.9.9 r4 更新下载停滞看门狗与真实失败原因";
    static { System.err.println("[update] " + FP_099_R4_WATCHDOG); }

    /**
     * 0.9.9 r5 指纹：更新 stamp 记 version+build 二元组 + 覆盖前探安装目录可写。
     * 构建门禁扫常量池，零引用也留在常量池，勿改这串字。
     */
    public static final String FP_099_R5_STAMP = "0.9.9 r5 stamp记build与覆盖前探可写";
    static { System.err.println("[update] " + FP_099_R5_STAMP); }

    /** 0.9.9 r6 指纹：自动更新成功率加固。构建门禁扫常量池，零引用也留在常量池，勿改这串字。 */
    public static final String FP_099_R6_UPDATE_ROBUST = "0.9.9 r6 自动更新成功率高可靠兜底";
    static { System.err.println("[update] " + FP_099_R6_UPDATE_ROBUST); }

    /**
     * 0.9.9 r11 指纹：照 HMCL 的两阶段更新——旧进程只下载/校验/暂存，
     * 由独立进程等旧 GBL 退出后再覆盖，彻底避开 runtime/lib/modules 被 JVM 锁住。
     * 构建门禁扫常量池，零引用也留在常量池，勿改这串字。
     */
    public static final String FP_099_R11_HMCL_STAGE = "0.9.9 r11 自动更新两阶段暂存与独立进程覆盖";
    static { System.err.println("[update] " + FP_099_R11_HMCL_STAGE); }

    /**
     * 0.9.9 r13 指纹：更新成功后自动清理本批次唯一的 backup / stage 暂存目录，
     * 不再在父目录越攒越多；失败分支仍保留 backup 供回滚。构建门禁扫常量池，勿改这串字。
     */
    public static final String FP_099_R13_UPDATE_CLEANUP = "0.9.9 r13 更新暂存自动清理";
    static { System.err.println("[update] " + FP_099_R13_UPDATE_CLEANUP); }

    /**
     * GBL 1.0 正式版指纹：自动更新成功率高可靠兜底。构建门禁扫常量池，勿改这串字。
     */
    public static final String FP_1_0_UPDATE_STABLE = "GBL 1.0 正式版自动更新稳定";
    static { System.err.println("[update] " + FP_1_0_UPDATE_STABLE); }

    /**
     * 0.9.9：全局安装互斥（CAS）。同一时刻只允许一个安装流程。
     * 连点「更新」时 UI 的 updating 标志可能还没来得及置位（尤其 pickFastestSource 最长 6 秒、
     * 期间 onFraction 不触发，按钮还是可点的），所以必须靠后端 CAS 兜底，而不是靠禁用按钮。
     * 两个入口（UpdateController.onUpdate 与 ShellView.askToUpdateNow）都走 installAndRestart，统一受它管。
     */
    private static final AtomicBoolean INSTALLING = new AtomicBoolean(false);

    /** 0.9.9：用户点了「取消安装」。volatile 保证安装线程 promptly 看见。 */
    private static volatile boolean cancelRequested = false;

    /** 0.9.9 r4：看门狗轮询间隔。 */
    private static final long STALL_CHECK_MILLIS = 5000L;

    /** 0.9.9 r4：多久没有任何新字节就判定连接假死。Cloudflare 冷连接 TTFB 已到 7 秒，45 秒足够宽松。 */
    private static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(45);

    /**
     * 0.9.9 r4：最近一次真正收到字节的时间点（{@link System#nanoTime()}）。0 表示当前没有下载在进行。
     *
     * <p>为什么需要它：{@code InputStream.read} 在连接假死时会永久阻塞，循环里的
     * {@link #checkCanceled()} 根本没机会执行，表现为「更新中…」永远不动、取消按钮也点不掉。
     */
    private static volatile long lastByteAt = 0L;

    /** 0.9.9 r4：看门狗判定「连接卡死」后置真；downloadOnce 据此把 IOException 换成可读中文话术。 */
    private static volatile boolean stalledDownload = false;

    /**
     * 0.9.9 r4：当前正在读的响应体流。看门狗判定假死时 close 它，
     * 阻塞中的 {@code read} 立刻抛 IOException，从而跳出下载循环、走「同源重试 / 换源」逻辑。
     */
    private static volatile InputStream currentBody = null;

    /**
     * 0.9.9：内部取消信号。unchecked，便于在各检查点直接抛出，不被源码里遍布的
     * catch (Exception) 当成「换源重试 / 安装失败」吞掉——取消必须一路传到 installAndRestart。
     */
    private static final class CancelledException extends RuntimeException {
        CancelledException() {
            super("update canceled by user");
        }
    }

    private UpdateInstaller() {
    }

    /** 进度回调：onStage 给阶段文案，onFraction 给 0..1 进度。 */
    public interface Progress {
        void onStage(String text);

        void onFraction(double fraction);

        /**
         * 0.9.9：用户取消安装时回调（安装线程调用，实现方自行 Platform.runLater 切回 UI）。
         * default 空实现，避免破坏现有实现（如 ShellView 里内联的那个匿名 Progress）。
         */
        default void onCanceled() {
        }
    }

    /**
     * 兼容旧调用点的三参重载：只知道「失败了」，拿不到原因。
     * 内部直接委托给能拿原因的版本，避免为兼容而复制两套流程。
     */
    public static void installAndRestart(UpdateInfo info, Progress progress, Runnable onFailed) {
        installAndRestart(info, progress, t -> {
            if (onFailed != null) {
                onFailed.run();
            }
        });
    }

    /**
     * 后台执行整套更新并重启。完成后旧进程退出；失败则把<b>真实失败原因</b>回调给 onFailed
     * （已在 FX 线程），由界面决定怎么展示；用户取消则回调 progress.onCanceled()，
     * 既不拉起新进程也不 System.exit。本方法自己开线程，调用方直接调即可。
     *
     * <p>0.9.9 r4：失败回调从 {@code Runnable} 换成 {@code Consumer<Throwable>}。
     * 旧签名只能告诉界面「失败了」，界面只好显示一句固定文案「可能是网络不稳定」，
     * 而真实原因（写不进 runtime/lib/modules、更新包不完整、大小不对、下载卡死……）
     * 只 printStackTrace 进控制台 —— 用户看日志都看不到，只能反复重试。
     *
     * <p>0.9.9：CAS 全局互斥——已有安装在进行时回调可读错误，绝不静默 return。
     * 旧版静默 return 会让 UpdateController 的 {@code updating} 永远为 true，按钮卡死在
     * 「更新中…」，本次会话再也点不动。
     */
    public static void installAndRestart(UpdateInfo info, Progress progress,
                                         java.util.function.Consumer<Throwable> onFailed) {
        if (!INSTALLING.compareAndSet(false, true)) {
            System.err.println("[update] 已有更新安装在进行，忽略本次重复点击");
            Platform.runLater(() -> {
                if (onFailed != null) {
                    onFailed.accept(new IOException(
                            "已经有一个更新在下载了，别急，等它跑完。若界面一直没动静，请关掉 GBL 重新打开再试。"));
                }
            });
            return;
        }
        // 每次新流程都从「未取消」开始，避免上一次的取消标志污染本次安装。
        cancelRequested = false;
        new Thread(() -> {
            try {
                run(info, progress);
                // run 正常返回后再确认一次：哪怕取消发生在最后一个检查点之后，
                // 也绝不能因为「取消」而强制重启用户当前的进程。
                if (cancelRequested) {
                    System.err.println("[update] 用户取消更新，已停止安装，不重启");
                    notifyCanceled(progress);
                } else {
                    Platform.runLater(() -> System.exit(0));
                }
            } catch (CancelledException ce) {
                System.err.println("[update] 用户取消更新，已停止安装，不重启");
                notifyCanceled(progress);
            } catch (Throwable t) {
                System.err.println("[update] 安装更新失败: " + t);
                t.printStackTrace(System.err);
                Platform.runLater(() -> {
                    if (onFailed != null) {
                        onFailed.accept(t);
                    }
                });
            } finally {
                cancelRequested = false;
                INSTALLING.set(false);
            }
        }, "gbl-update-install").start();
    }

    /** 是否正有一个更新安装在后台进行（供 UI 显示/隐藏「取消安装」按钮）。 */
    public static boolean isInstalling() {
        return INSTALLING.get();
    }

    /** 请求取消正在进行的更新安装；安装线程会在下一个检查点停下并回滚，不会重启。 */
    public static void requestCancel() {
        cancelRequested = true;
    }

    /** 安装线程调用；实现方在 onCanceled 内自行 Platform.runLater 切回 UI。 */
    private static void notifyCanceled(Progress progress) {
        if (progress == null) {
            return;
        }
        try {
            progress.onCanceled();
        } catch (Throwable t) {
            System.err.println("[update] onCanceled 回调异常: " + t);
        }
    }

    /** 各检查点统一入口：用户已请求取消就立刻抛出，短路后续下载 / 覆盖 / 重启。 */
    private static void checkCanceled() {
        if (cancelRequested) {
            throw new CancelledException();
        }
    }

    private static void run(UpdateInfo info, Progress progress) throws Exception {
        Path appDir = com.mclauncher.Platform.appDir();
        // 下载到系统临时目录，绝不污染安装目录（也避免占用安装目录导致 exe 解锁失败）
        Path zip;
        try {
            zip = Files.createTempFile("gbl-update-", ".zip");
        } catch (IOException ex) {
            if (isNoSpace(ex)) {
                throw new IOException(NO_SPACE_MSG + "。原来的版本没动，游戏和设置都不会丢。", ex);
            }
            throw ex;
        }
        // 0.9.9 r4：下载全程挂一个 daemon 看门狗。连接假死时 read 永久阻塞，
        // 只有外部线程才能把它打断（close 掉响应流 → read 抛 IOException）。
        stalledDownload = false;
        lastByteAt = 0L;
        Thread watchdog = startStallWatchdog();
        try {
            checkCanceled();
            progress.onStage("正在下载新版本…");
            // 0.9.8：两个下载源先并发探测首块，选响应最快的那条，主源挂了自动用备源。
            List<String> urls = candidateUrls(info);
            String chosen = pickFastestSource(urls);
            checkCanceled();   // 0.9.9：竞速最多等 6 秒，回来先确认用户没点取消
            // 0.9.8 r3 P0：每个源都要在循环内验完整包（zip 能打开 + 关键文件齐 +
            // sha256 + size）。Gitee 直链偶尔返回「HTTP 200 + HTML 错误页 / 登录页」，
            // 旧逻辑在循环外才校验，会直接终止更新，version.json 里的 GitHub 备源永远不试。
            Path validZip = null;
            Exception lastError = null;
            // 0.9.9 r2：单个源最多尝试 3 次（第 1 次 + 2 次重试）。用户网络下 23MB 大文件
            // 常中途被重置/超时，只下一次就换源会让备源（该网络往往也不通）接不住，
            // 结果一个字节没换却提示失败。同一源先重试，3 次都不行才换下一条线路。
            final int maxAttempts = 3;
            for (String url : raceOrder(urls, chosen)) {
                checkCanceled();   // 0.9.9：每条线路开始前都看一眼取消标志
                boolean sourceOk = false;
                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                    checkCanceled();   // 0.9.9 r2：每次尝试前都确认用户没点取消
                    try {
                        // newOutputStream 是覆盖写：重试、换源天然都不会留下半包。
                        String digest = downloadOnce(url, zip, info, progress);
                        checkCanceled();   // 0.9.9：下完立刻确认，别拿已取消的包去校验/覆盖
                        validateDownloadedZip(zip, info, digest);
                        validZip = zip;
                        lastError = null;
                        sourceOk = true;
                        break;
                    } catch (CancelledException ce) {
                        // 取消不能被当成「这个源坏了」去重试/换线路，必须一路抛到 installAndRestart。
                        throw ce;
                    } catch (Exception ex) {
                        lastError = ex;
                        // 这里删的只是本次下载到系统临时目录的半包，不碰安装目录、.minecraft、
                        // GBL/jre，也不做批量删除；下一次 newOutputStream 会重新覆盖写。
                        try {
                            Files.deleteIfExists(zip);
                        } catch (IOException ignore) {
                            // 半包删不掉也会被下一次 newOutputStream 覆盖，不致命
                        }
                        if (attempt < maxAttempts) {
                            System.err.println("[update] " + url + " 第 " + attempt
                                    + " 次下载未成功，准备重试: " + readable(ex));
                            try {
                                // 0.9.9 r9：递增退避 800/1600ms，再加 0..200ms 随机抖动，
                                // 避免大量用户在同一时刻重试同一 CDN、退避完全同频而反复撞车。
                                long jitter = (long) (Math.random() * 200.0);
                                Thread.sleep(800L * (1L << (attempt - 1)) + jitter);
                            } catch (InterruptedException ie) {
                                // 退避等待被打断：恢复中断标志并抛出，走取消/失败路径，绝不吞掉继续重试。
                                Thread.currentThread().interrupt();
                                throw new IOException("下载重试等待被中断", ie);
                            }
                            continue;   // 重试同一个 URL
                        }
                        // 3 次都失败才换下一条线路。
                        System.err.println("[update] " + url + " 第 " + attempt
                                + " 次下载未成功，换下一条线路: " + readable(ex));
                    }
                }
                if (sourceOk) {
                    break;   // 这个源已拿到完整包，跳出换源循环
                }
            }
            if (validZip == null) {
                // 0.9.9 r6：磁盘空间不足是物理因素，单独给可读原因 + 下一步，别冤枉网络。
                if (isNoSpace(lastError)) {
                    throw new IOException(NO_SPACE_MSG + "。原来的版本没动，游戏和设置都不会丢。"
                            + "清理空间后重试，或到官网手动下载。", lastError);
                }
                // 0.9.9 r2：全部源都失败，多半是用户网络抖动而非包本身有问题，给可读话术。
                throw new IOException("下载新版本失败，可能是网络不稳定。已保留原来的版本，"
                        + "游戏和设置都没变。请检查网络后重试，或到官网手动下载完整安装包。", lastError);
            }

            checkCanceled();   // 0.9.9：进暂存前最后一道闸
            // 0.9.9 r11（照 HMCL）：旧进程绝不覆盖 runtime/lib/modules —— 实测它已被当前 JVM
            // 锁住，写入和重命名都会失败，连带备份还原也失败，于是用户看到「文件没有被改动」。
            // 这里只做「下载 → 校验 → 解压到新的暂存目录 → 生成一个独立小脚本」；
            // 真正的覆盖由那个脚本等本进程退出后执行，再拉起新版 GBL。
            progress.onStage("正在准备安装新版本…");
            stageAndLaunchHelper(validZip, appDir, info, progress);
            progress.onStage("即将重启…");
        } finally {
            // 0.9.9 r4：看门狗必须在任何出口（成功 / 失败 / 取消）都停掉，
            // 否则它会继续持着旧的 body 引用，下一次更新下载时误关新连接。
            stopStallWatchdog(watchdog);
            try {
                Files.deleteIfExists(zip);
            } catch (IOException ignore) {
                // 临时文件删不掉无所谓，系统会回收
            }
        }
    }

    /** r11 暂存清单：相对路径 / 暂存文件 / 安装根目标 / 暂存文件 sha256。 */
    private record StageItem(String rel, Path src, Path dst, String sha) {
    }

    /**
     * 0.9.9 r11（照 HMCL 两阶段更新）：旧进程只下载、校验、解压到全新的暂存目录，
     * 然后启动一个独立 PowerShell 小脚本；脚本等本进程退出后才覆盖关键文件并拉起新版。
     *
     * <p>真因（本机实测）：{@code runtime/lib/modules} 被运行中的 JVM 锁住，
     * 直接写或重命名都会失败，连带备份还原也失败，UI 只能显示「文件没有被改动」。
     * 暂存目录一律唯一时间戳新建、不复用；r13 起由独立脚本在成功覆盖后只清理本批次
     * 唯一的 backup / stage（硬编码绝对路径 + -LiteralPath），失败分支仍保留 backup 供回滚。
     */
    private static void stageAndLaunchHelper(Path zip, Path appDir, UpdateInfo info, Progress progress)
            throws Exception {
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
                + "-" + System.nanoTime();
        Path stage = appDir.resolveSibling(".gbl-update-stage-" + stamp);
        Files.createDirectories(stage);

        List<StageItem> items = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            String prefix = detectPrefix(zf);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String rel = stripPrefix(e.getName(), prefix);
                if (rel.isEmpty()) {
                    continue;
                }
                Path target = stage.resolve(rel).normalize();
                if (!target.startsWith(stage)) {
                    throw new IOException("更新包里有不安全的路径，已停止安装");
                }
                checkCanceled();
                Files.createDirectories(target.getParent());
                try (InputStream in = zf.getInputStream(e)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                items.add(new StageItem(rel, target, appDir.resolve(rel).normalize(), sha256Hex(target)));
            }
        }
        if (items.isEmpty()) {
            throw new IOException("新版本安装包是空的，已停止安装。请重新下载完整安装包。");
        }
        for (String key : KEY_FILES) {
            boolean found = false;
            for (StageItem it : items) {
                if (key.equals(it.rel())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IOException("更新包不完整，缺少新版要用的文件，已停止安装。请重新下载完整包。");
            }
        }

        Path backup = appDir.resolveSibling(".gbl-update-backup-" + stamp);
        Path script = stage.resolve("apply-gbl-update.ps1");
        Files.writeString(script, "\uFEFF" + buildApplyScript(appDir, backup, items,
                info.version(), info.build()), StandardCharsets.UTF_8);

        // 独立进程：不等待它结束（它要等本进程退出）；它自己写结果标记并拉起新版。
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile",
                "-ExecutionPolicy", "Bypass", "-File", script.toString());
        pb.directory(appDir.toFile());
        pb.start();
        System.err.println("[update] 更新包已暂存，独立安装脚本已启动；旧进程退出后才会覆盖关键文件");
    }

    /** PowerShell 单引号字符串转义。路径里出现英文单引号时翻倍即可。 */
    private static String psQuote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    /** 逐文件 sha256，供独立安装脚本覆盖后复核，防止「写进去的是半截文件」。 */
    private static String sha256Hex(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 生成独立安装脚本：等旧进程退出 → 备份 → 覆盖 → 校验 → 写 stamp → 拉起新版。 */
    private static String buildApplyScript(Path appDir, Path backup, List<StageItem> items,
                                           String version, String build) {
        StringBuilder sb = new StringBuilder();
        line(sb, "$ErrorActionPreference = 'Stop'");
        line(sb, "$oldPid = " + ProcessHandle.current().pid());
        line(sb, "$root = " + psQuote(appDir.toString()));
        line(sb, "$bak = " + psQuote(backup.toString()));
        // r13：由 backup 的硬编码 stamp 前缀换算出本批次唯一的 stage 绝对路径，
        // 绝不用通配符、不碰父目录、不碰别的 stamp（删除范围铁律）。
        line(sb, "$stageDir = " + psQuote(backup.toString().replace(".gbl-update-backup-", ".gbl-update-stage-")));
        line(sb, "$version = " + psQuote(version == null ? "" : version));
        line(sb, "$build = " + psQuote(build == null ? "" : build));
        line(sb, "$resultPath = " + psQuote(appDir.resolve("app").resolve("gbl-update-result.txt").toString()));
        line(sb, "$stampPath = " + psQuote(appDir.resolve("app").resolve("gbl-last-update.txt").toString()));
        line(sb, "$items = @(");
        for (int i = 0; i < items.size(); i++) {
            StageItem it = items.get(i);
            line(sb, "  @{ Rel=" + psQuote(it.rel()) + "; Src=" + psQuote(it.src().toString())
                    + "; Dst=" + psQuote(it.dst().toString()) + "; Sha=" + psQuote(it.sha()) + " }"
                    + (i + 1 < items.size() ? "," : ""));
        }
        line(sb, ")");
        line(sb, "function Write-Result([string]$text) {");
        line(sb, "  [System.IO.File]::WriteAllText($resultPath, $text, [System.Text.UTF8Encoding]::new($false))");
        line(sb, "}");
        line(sb, "function Restore-Backup {");
        line(sb, "  foreach ($it in $items) {");
        line(sb, "    $bd = Join-Path $bak $it.Rel");
        line(sb, "    if (Test-Path -LiteralPath $bd) {");
        line(sb, "      New-Item -ItemType Directory -Path (Split-Path -Parent $it.Dst) -Force | Out-Null");
        line(sb, "      Copy-Item -LiteralPath $bd -Destination $it.Dst -Force");
        line(sb, "    }");
        line(sb, "  }");
        line(sb, "}");
        line(sb, "$waiting = $true");
        line(sb, "for ($i = 0; $i -lt 300; $i++) {");
        line(sb, "  if (-not (Get-Process -Id $oldPid -ErrorAction SilentlyContinue)) { $waiting = $false; break }");
        line(sb, "  Start-Sleep -Milliseconds 200");
        line(sb, "}");
        line(sb, "if ($waiting) {");
        line(sb, "  Write-Result 'FAIL 旧版 GBL 没有正常退出，已保留原版本。请手动关掉 GBL 再重新打开。'");
        line(sb, "  exit 1");
        line(sb, "}");
        line(sb, "try {");
        line(sb, "  New-Item -ItemType Directory -Path $bak -Force | Out-Null");
        line(sb, "  foreach ($it in $items) {");
        line(sb, "    if (Test-Path -LiteralPath $it.Dst) {");
        line(sb, "      $bd = Join-Path $bak $it.Rel");
        line(sb, "      New-Item -ItemType Directory -Path (Split-Path -Parent $bd) -Force | Out-Null");
        line(sb, "      Copy-Item -LiteralPath $it.Dst -Destination $bd -Force");
        line(sb, "    }");
        line(sb, "  }");
        line(sb, "  foreach ($it in $items) {");
        line(sb, "    New-Item -ItemType Directory -Path (Split-Path -Parent $it.Dst) -Force | Out-Null");
        line(sb, "    if (Test-Path -LiteralPath $it.Dst) { Set-ItemProperty -LiteralPath $it.Dst -Name IsReadOnly -Value $false -ErrorAction SilentlyContinue }");
        line(sb, "    Copy-Item -LiteralPath $it.Src -Destination $it.Dst -Force");
        line(sb, "    $h = (Get-FileHash -LiteralPath $it.Dst -Algorithm SHA256).Hash.ToLowerInvariant()");
        line(sb, "    if ($h -ne $it.Sha.ToLowerInvariant()) { throw ('新文件校验没通过：' + $it.Rel) }");
        line(sb, "  }");
        // r13：成功即无需回滚 —— 先删本批次唯一 backup，再写 stamp/result、拉起新版，
        // 最后删 stage（ps1 已被 PowerShell 读入内存，可自删）。删除一律 -LiteralPath +
        // 存在性判断 + try/catch 吞异常：删除失败绝不能让已经成功的更新报错。
        // 失败分支（catch）保留 backup 供回滚，绝不删。
        line(sb, "  if (Test-Path -LiteralPath $bak) { try { Remove-Item -LiteralPath $bak -Recurse -Force } catch {} }");
        line(sb, "  [System.IO.File]::WriteAllText($stampPath, ($version + ' ' + $build), [System.Text.UTF8Encoding]::new($false))");
        line(sb, "  Write-Result ('OK ' + $version + ' ' + $build)");
        line(sb, "  Start-Process -FilePath (Join-Path $root 'GBL.exe') -ArgumentList '--gbl-restart' -WorkingDirectory $root");
        line(sb, "  if (Test-Path -LiteralPath $stageDir) { try { Remove-Item -LiteralPath $stageDir -Recurse -Force } catch {} }");
        line(sb, "} catch {");
        line(sb, "  try { Restore-Backup } catch {}");
        line(sb, "  Write-Result ('FAIL ' + $_.Exception.Message)");
        line(sb, "  Start-Process -FilePath (Join-Path $root 'GBL.exe') -WorkingDirectory $root");
        line(sb, "  exit 1");
        line(sb, "}");
        return sb.toString();
    }

    private static void line(StringBuilder sb, String text) {
        sb.append(text).append("\r\n");
    }

    /**
     * 0.9.9 r4：启动下载停滞看门狗线程（daemon）。
     *
     * <p>每 5 秒看一次 {@link #lastByteAt}：超过 45 秒没有新字节就判定连接卡死，
     * 置 {@link #stalledDownload} 并关掉当前响应流，让阻塞中的 read 抛 IOException 跳出循环。
     * 不新建常驻线程池、不复用线程，下载结束即中断，代价可忽略。
     */
    private static Thread startStallWatchdog() {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(STALL_CHECK_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                long last = lastByteAt;
                if (last == 0L || System.nanoTime() - last <= STALL_NANOS) {
                    continue;   // 当前没在下载，或刚收到字节，一切正常
                }
                stalledDownload = true;
                System.err.println("[update] 下载已超过 45 秒没有新数据，判定连接卡死，主动断开重试");
                InputStream body = currentBody;
                currentBody = null;
                closeQuietly(body);
                // 只断一次：把基准清零，等下载线程重开连接后由 downloadOnce 重新置位。
                lastByteAt = 0L;
            }
        }, "gbl-update-watchdog");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** 停看门狗并清干净三个共享状态，避免污染下一次更新。 */
    private static void stopStallWatchdog(Thread watchdog) {
        if (watchdog != null) {
            watchdog.interrupt();
        }
        currentBody = null;
        lastByteAt = 0L;
        stalledDownload = false;
    }

    /** 关流不抛异常：关闭本身失败对「让 read 抛错」这个目的没有影响。 */
    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignore) {
            // 关不掉也无所谓，只要 read 能被打断即可
        }
    }

    /**
     * 主源 + 备源（version.json 的 github 字段），去重、去空。
     *
     * <p>0.9.9 r9：version.json 里还带 fullUrl / fullGithub 两个字段，但 {@link UpdateInfo}
     * 没有暴露它们（record 只有 url / altUrl），按「只改两个 java 文件、不动 record」的约束暂不接入。
     * 当前 {@code url}（Gitee 直链）与 {@code altUrl}（GitHub）已是两条不同线路，
     * 下载会在两条线路间按源重试 + 换源，具备真实容灾能力。
     */
    private static List<String> candidateUrls(UpdateInfo info) {
        Set<String> set = new LinkedHashSet<>();
        if (info.url() != null && !info.url().isBlank()) {
            set.add(info.url());
        }
        if (info.altUrl() != null && !info.altUrl().isBlank()) {
            set.add(info.altUrl());
        }
        return new ArrayList<>(set);
    }

    /**
     * 源竞速：各源并发发一个 HEAD 请求（0.9.9 r4 起不再读正文），谁先回 2xx 就用谁。
     * 探测不下载任何字节，代价接近 0。全部失败或超时就退回主源，让正式下载自己处理。
     */
    private static String pickFastestSource(List<String> urls) {
        if (urls.size() < 2) {
            return urls.isEmpty() ? null : urls.get(0);
        }
        ExecutorService pool = Executors.newFixedThreadPool(urls.size(), r -> {
            Thread t = new Thread(r, "gbl-update-probe");
            t.setDaemon(true);
            return t;
        });
        CompletionService<String> cs = new ExecutorCompletionService<>(pool);
        List<Future<String>> futures = new ArrayList<>();
        for (String u : urls) {
            futures.add(cs.submit(() -> probe(u) ? u : null));
        }
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            int collected = 0;
            // 0.9.9：分小片 poll（每片最多 200ms），每片都回头看一次取消标志。
            // 否则一次 poll 最长阻塞 6 秒，用户点取消后半天停不下来。
            long slice = TimeUnit.MILLISECONDS.toNanos(200);
            while (collected < urls.size()) {
                checkCanceled();
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                Future<String> f = cs.poll(Math.min(left, slice), TimeUnit.NANOSECONDS);
                if (f == null) {
                    continue;   // 这一片没结果，回头再看取消标志和剩余时间
                }
                collected++;
                try {
                    String winner = f.get();
                    if (winner != null) {
                        return winner;
                    }
                } catch (Exception ignored) {
                    // 这个源不通，等下一个
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            for (Future<String> f : futures) {
                f.cancel(true);
            }
            pool.shutdownNow();
        }
        return urls.get(0);
    }

    /**
     * 探测一个源能不能通：只看 HEAD 的状态码是不是 2xx，绝不读响应体。
     *
     * <p>0.9.9 r4：旧版发 {@code Range: bytes=0-65535} 的 GET，想只读前 64KB。但 Gitee 附件直链
     * 完全忽略 Range，照样把整包 23MB 吐回来 —— 探测本身就等于下载了一遍，双源并发等于下两遍，
     * 还没开始更新就先耗掉几十兆流量和十几秒。HEAD 只回头部，代价接近 0。
     * 某些源不支持 HEAD 会回 405，那也算「探测失败」，由调用方退回主源，行为安全。
     */
    private static boolean probe(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .header("User-Agent", GameDownloader.AGENT)
                    .header("Accept-Encoding", "identity")
                    .build();
            // discarding()：即便对方违规在 HEAD 里带响应体，也不会被我们读进内存。
            HttpResponse<Void> resp = CLIENT.send(req, HttpResponse.BodyHandlers.discarding());
            int code = resp.statusCode();
            return code >= 200 && code < 300;
        } catch (Exception ex) {
            // 0.9.9 r9：HEAD 探测失败不再静默——记一行原因便于排查，但只打印不敏感的异常类型/简述，
            // 绝不打印完整 URL（可能带 token / 签名参数）。
            String m = ex.getMessage();
            String brief = (m == null || m.isBlank()) ? ex.getClass().getSimpleName() : m;
            if (brief.length() > 60) {
                brief = brief.substring(0, 60) + "…";
            }
            System.err.println("[update] 下载源 HEAD 探测失败（源 " + safeSourceName(url)
                    + "）: " + brief);
            return false;
        }
    }

    /** 0.9.9 r9：给日志用的安全源名——只留域名与线路类别，绝不暴露 URL 里的 token / 签名参数。 */
    private static String safeSourceName(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            if (host == null) {
                return "未知源";
            }
            host = host.toLowerCase(java.util.Locale.ROOT);
            if (host.contains("gitee")) {
                return "Gitee源";
            }
            if (host.contains("github") || host.contains("githubusercontent")) {
                return "GitHub源";
            }
            return host;
        } catch (Exception ignore) {
            return "未知源";
        }
    }

    /** 把竞速赢家排第一，其余保持原顺序作为自动兜底。 */
    private static List<String> raceOrder(List<String> urls, String chosen) {
        List<String> out = new ArrayList<>();
        if (chosen != null && urls.contains(chosen)) {
            out.add(chosen);
        }
        for (String u : urls) {
            if (!out.contains(u)) {
                out.add(u);
            }
        }
        return out;
    }

    /** 单源下载；返回边下边算出的 sha256 十六进制。 */
    private static String downloadOnce(String url, Path zip, UpdateInfo info, Progress progress) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(HEAD_TIMEOUT)
                // 0.9.9 r4：带上与游戏下载一致的 UA。Gitee / Cloudflare 对没有 UA 的客户端
                // 会额外降速甚至直接 403，这也是「别人机器上更新包下不下来」的一个来源。
                .header("User-Agent", GameDownloader.AGENT)
                .header("Accept-Encoding", "identity")
                .GET()
                .build();
        HttpResponse<InputStream> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            // 0.9.9 P3：非 200 时 body 流从不 close，会一直占着连接。抛错前显式关掉。
            InputStream errBody = resp.body();
            if (errBody != null) {
                try {
                    errBody.close();
                } catch (IOException ignore) {
                    // 关不掉也不影响「这条线路不可用」的结论
                }
            }
            throw new IOException("下载失败 HTTP " + resp.statusCode());
        }
        long total = resp.headers().firstValueAsLong("Content-Length").orElse(0L);
        // 0.9.8 r3 P1：Gitee 用 chunked 时不给 Content-Length，旧逻辑 total=0 就一帧都不刷，
        // 二十多 MB 下载期间进度条完全静止。version.json 里已有 size，拿它当分母继续回刷。
        if (total <= 0 && info.sizeBytes() > 0) {
            total = info.sizeBytes();
        }
        if (total <= 0) {
            // 连 size 都没有：至少先发一次 indeterminate，让按钮 / 进度条进入「更新中」。
            progress.onFraction(-1.0);
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        long got = 0;
        long lastReported = 0;
        long lastTick = System.nanoTime();
        // 0.9.9 r4：把响应流交给看门狗管，并重置停滞判据（每次重试都是新的连接）。
        InputStream body = resp.body();
        stalledDownload = false;
        currentBody = body;
        lastByteAt = System.nanoTime();
        try (InputStream in = body;
             var out = Files.newOutputStream(zip)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    // 0.9.9 r4：每收到一块就刷新「最后活动时间」，看门狗据此判断是否假死。
                    lastByteAt = System.nanoTime();
                    checkCanceled();   // 0.9.9：下载循环里逐块看取消；半包由 run() 的 finally 删除
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    got += n;
                    long now = System.nanoTime();
                    if (total > 0 && (got - lastReported >= PROGRESS_BYTES
                            || now - lastTick >= PROGRESS_NANOS)) {
                        progress.onFraction(Math.min(1.0, (double) got / total));
                        lastReported = got;
                        lastTick = now;
                    }
                }
            } catch (IOException ex) {
                // 看门狗主动断开时 read 会抛 IOException。换成用户看得懂的话，
                // 并且明确告诉用户「原来的版本没动」，避免他以为安装被搞坏了。
                if (stalledDownload) {
                    throw new IOException("下载卡住了 45 秒没有新数据，可能是网络断了。原来的版本没动，请重试。", ex);
                }
                throw ex;
            }
        } finally {
            // 无论正常结束还是抛错都要放手，否则看门狗会拿旧流去关新连接。
            currentBody = null;
            lastByteAt = 0L;
        }
        if (total > 0) {
            progress.onFraction(1.0);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 0.9.8 r3 P0：单源下载完就地验包，三关全过才算这个源可用。
     * ① zip 能打开、且三个关键文件条目都在（混合前缀包会在 detectPrefix 里被拒绝）；
     * ② sha256 与 version.json 逐字节一致；③ 文件大小与 version.json 一致。
     * 任一不过就抛 IOException，由调用方删掉半包换下一个源。
     */
    private static void validateDownloadedZip(Path zip, UpdateInfo info, String digest) throws IOException {
        if (info.sha256() != null && !info.sha256().isBlank()
                && !digest.equalsIgnoreCase(info.sha256())) {
            throw new IOException("下载到的文件和官网给的对不上");
        }
        if (info.sizeBytes() > 0 && Files.size(zip) != info.sizeBytes()) {
            throw new IOException("下载到的文件大小不对");
        }
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            String prefix = detectPrefix(zf);
            for (String key : KEY_FILES) {
                ZipEntry ze = zf.getEntry(prefix + key);
                if (ze == null || ze.isDirectory()) {
                    throw new IOException("安装包里缺少新版要用的文件");
                }
            }
        } catch (ZipException ex) {
            // HTML 错误页 / 登录页 / 半包都会走到这里：内容不是合法 zip。
            throw new IOException("下载地址返回的内容不是完整安装包", ex);
        }
    }

    /** 安装根里真实存在的顶层目录：出现它们说明 zip 没有额外包装前缀。 */
    private static final Set<String> KNOWN_ROOTS = Set.of("app", "runtime", "jre", ".minecraft");

    /** 必须成功覆盖并重验 CRC 的文件：应用类 / 启动配置就靠它们。 */
    private static final Set<String> KEY_FILES = Set.of(
            "app/GBL.cfg", "app/mc-launcher.jar", "runtime/lib/modules");

    /** 0.9.9 r6：关键文件写失败时的有界重试——最多 3 次、每次间隔 700ms，硬上限 2.1 秒。 */
    private static final int KEY_WRITE_RETRIES = 3;
    private static final long KEY_WRITE_RETRY_MILLIS = 700L;

    /** UI 可识别的阶段标记：带此前缀说明文件已覆盖成功、只是没拉起新进程（改动3）。 */
    private static final String STAGE_INSTALLED_NOT_RESTARTED = "[阶段:已安装但未重启]";

    /** 0.9.9 r6：磁盘空间不足的中文原因（改动5）。 */
    private static final String NO_SPACE_MSG = "磁盘空间不够了，清理一点空间再试（需要约 25MB）";

    /**
     * 探测 zip 是否带统一顶层包装目录（如 {@code GBL/}），有则返回该前缀。
     *
     * <p>0.9.8 r3 P1：同一个包里既出现根级 {@code app/...} 又出现 {@code GBL/app/...} 时，
     * 旧逻辑一看到根级就 return ""，会把带前缀的条目写进 {@code GBL/GBL/...} 影子目录，
     * 重演「提示更新成功、重启后还是旧版本」。混合包一律明确拒绝安装。
     */
    private static String detectPrefix(ZipFile zf) throws IOException {
        String first = null;
        boolean sawRootLevel = false;
        boolean sawWrapped = false;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            if (e.isDirectory()) {
                continue;
            }
            String n = normalized(e.getName());
            while (n.startsWith("/")) {
                n = n.substring(1);
            }
            int slash = n.indexOf('/');
            if (slash <= 0) {
                sawRootLevel = true;   // 根级散文件也按「无包装前缀」看待
                continue;
            }
            String seg = n.substring(0, slash);
            if (KNOWN_ROOTS.contains(seg)) {
                sawRootLevel = true;
                continue;
            }
            sawWrapped = true;
            if (first == null) {
                first = seg;
            } else if (!first.equals(seg)) {
                throw new IOException("更新包里混着两种目录写法，已停止安装。请重新下载完整安装包。");
            }
        }
        if (sawRootLevel && sawWrapped) {
            throw new IOException("更新包里混着两种目录写法，已停止安装。请重新下载完整安装包。");
        }
        if (sawRootLevel) {
            return "";
        }
        return first == null ? "" : first + "/";
    }

    private static String normalized(String name) {
        return name.replace('\\', '/');
    }

    /** 剥掉包装前缀，得到相对安装根的路径。 */
    private static String stripPrefix(String name, String prefix) {
        String n = normalized(name);
        if (!prefix.isEmpty() && n.startsWith(prefix)) {
            n = n.substring(prefix.length());
        }
        while (n.startsWith("/")) {
            n = n.substring(1);
        }
        return n;
    }

    /** 运行中会被系统锁住、且跳过也不影响新版本生效的文件。 */
    private static boolean isLockedAllowed(String rel) {
        if ("GBL.exe".equalsIgnoreCase(rel)) {
            return true;
        }
        String low = rel.toLowerCase(Locale.ROOT);
        if (low.startsWith("runtime/bin/") || low.startsWith("jre/bin/")) {
            return low.endsWith(".dll") || low.endsWith(".exe");
        }
        return false;
    }

    /**
     * 0.9.9 r6：写一个关键文件，失败时原地有界重试（对抗杀软实时扫描 / 系统短暂占用）。
     *
     * <p>Windows 上 {@code runtime/lib/modules} 之类关键文件偶尔被短暂锁住，几秒内就会释放；
     * 旧版一次写失败就回滚判死，把「可恢复的短暂占用」误当成「更新失败」。这里对关键文件做
     * 3 次重试（每次间隔 700ms，硬上限 2.1 秒）。每次重试都重新打开 zip 输入流——失败时流可能
     * 已被消费，复用会得到半截文件。绝不把关键文件加进跳过白名单：跳过了就是假更新，比失败更糟。
     */
    private static void copyKeyFileWithRetry(ZipFile zf, ZipEntry e, Path target, String rel)
            throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= KEY_WRITE_RETRIES; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(KEY_WRITE_RETRY_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("写关键文件时等待重试被打断", ie);
                }
                checkCanceled();   // 重试等待期间用户点了取消就立刻停
            }
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                if (attempt > 0) {
                    System.err.println("[update] 关键文件「" + rel + "」第 " + attempt + " 次重试写入成功");
                }
                return;
            } catch (IOException io) {
                last = io;
            }
        }
        throw last;
    }

    /** 0.9.9 r6：判断异常链里是否「磁盘空间不足」（中英文、忽略大小写）。 */
    private static boolean isNoSpace(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            String m = cur.getMessage();
            if (m != null) {
                String low = m.toLowerCase(Locale.ROOT);
                if (low.contains("空间") || low.contains("space") || low.contains("disk full")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }

    /**
     * 0.9.8 r3 P0：覆盖前先把三个关键文件复制到一个新建的带时间戳目录。
     * 0.9.9 r5：备份目录从系统 %TEMP% 改到 dest 同级的隐藏目录 —— 便携包装在 D:、
     * 而 %TEMP% 在 C: 时要跨盘复制约 75MB，且 TEMP 常满，备份阶段一失败就半途回滚，
     * 正是「提示成功后又弹窗」的高发点。零删除铁律不变：目录只新建、不复用、不清理，
     * 名字带唯一时间戳，与游戏同盘，不碰 .minecraft 和 GBL/jre。
     * 首装时某个文件本来就不存在，就不造备份。
     */
    private static Path backupKeyFiles(Path dest) throws IOException {
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
                + "-" + System.nanoTime();
        Path dir = dest.resolveSibling(".gbl-backup-" + stamp);
        Files.createDirectories(dir);
        for (String key : KEY_FILES) {
            Path src = dest.resolve(key);
            if (!Files.isRegularFile(src)) {
                continue;
            }
            Path bak = dir.resolve(key);
            Files.createDirectories(bak.getParent());
            Files.copy(src, bak, StandardCopyOption.REPLACE_EXISTING);
        }
        return dir;
    }

    /** 0.9.8 r3 P0：用备份把三个关键文件原样写回（写回，不是删除）；返回是否全部还原成功。 */
    private static boolean restoreKeyFiles(Path dest, Path backupDir) {
        boolean allOk = true;
        int restored = 0;
        for (String key : KEY_FILES) {
            Path bak = backupDir.resolve(key);
            if (!Files.isRegularFile(bak)) {
                continue;   // 没有旧版备份就不动，保持现场
            }
            Path target = dest.resolve(key);
            try {
                Files.createDirectories(target.getParent());
                Files.copy(bak, target, StandardCopyOption.REPLACE_EXISTING);
                restored++;
            } catch (IOException ex) {
                allOk = false;
                System.err.println("[update] 关键文件还原失败: " + key + " -> " + readable(ex));
            }
        }
        return allOk && restored > 0;
    }

    /**
     * 覆盖解压：先剥掉 zip 的统一顶层前缀，再把内容写进安装根。
     * 被锁的可跳过文件计数跳过；关键文件写不进去就明确抛错，绝不静默「更新成功」。
     * 0.9.8 r3：任何一步失败都用覆盖前的备份把三个关键文件写回，保证还能启动旧版。
     */
    private static void unzipOverwrite(Path zip, Path dest, Progress progress) throws IOException {
        Path destNorm = dest.normalize();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            String prefix = detectPrefix(zf);
            System.err.println("[update] zip 顶层前缀 = " + (prefix.isEmpty() ? "(无)" : prefix));

            List<ZipEntry> files = new ArrayList<>();
            long totalBytes = 0;
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || stripPrefix(e.getName(), prefix).isEmpty()) {
                    continue;
                }
                files.add(e);
                totalBytes += Math.max(0, e.getSize());
            }
            if (files.isEmpty()) {
                throw new IOException("新版本安装包是空的，已停止安装。请重新下载。");
            }

            // 0.9.9 r5：覆盖前先探安装目录可写。便携包被放进 C:\Program Files 时，旧版要等
            // 复制到一半才因无权限失败，进度条却已经报满格 —— 正是「成功后又弹窗」的根因之一。
            // 这里在 dest 下用唯一时间戳名建一个小文件并立即删掉它：只操作这一个自己刚建的
            // 文件，不用通配符、不删目录、不碰 .minecraft 和 GBL/jre。
            Path probe = dest.resolve(".gbl-write-probe-"
                    + java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
                    + "-" + System.nanoTime());
            try {
                Files.writeString(probe, "", StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new IOException("GBL 所在的文件夹没有写权限（多半是放在了 C:\\Program Files 里）。"
                        + "请把整个 GBL 文件夹移到 D:\\ 之类普通目录后再更新。你的游戏和设置都不会丢。", ex);
            } finally {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException ignore) {
                    // 探测文件删不掉也无害（极小、不在关键路径），绝不因此中断安装
                }
            }

            Path backupDir = backupKeyFiles(dest);
            System.err.println("[update] 关键文件已备份到 " + backupDir);
            // 记录本次实际写入过的相对路径，失败时用于排查「半新半旧」到底写了多少。
            List<String> written = new ArrayList<>();
            try {
                Set<String> keyDone = new HashSet<>();
                int skipped = 0;
                long done = 0;
                long lastReported = 0;
                long lastTick = System.nanoTime();
                for (ZipEntry e : files) {
                    checkCanceled();   // 0.9.9：逐条目看取消，停下时用下面的专用 catch 回滚
                    String rel = stripPrefix(e.getName(), prefix);
                    Path target = dest.resolve(rel).normalize();
                    if (!target.startsWith(destNorm)) {
                        throw new IOException("zip 路径越界: " + e.getName());
                    }
                    try {
                        Files.createDirectories(target.getParent());
                        if (KEY_FILES.contains(rel)) {
                            // 0.9.9 r6：关键文件写失败做有界重试，对抗杀软 / 系统短暂占用
                            copyKeyFileWithRetry(zf, e, target, rel);
                        } else {
                            try (InputStream in = zf.getInputStream(e)) {
                                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                        written.add(rel);
                        if (KEY_FILES.contains(rel)) {
                            keyDone.add(rel);
                        }
                    } catch (IOException io) {
                        if (!isLockedAllowed(rel)) {
                            String hint = KEY_FILES.contains(rel)
                                    ? "。可能是杀毒软件暂时占用了文件，请关掉占用的程序后重试，或到官网手动下载最新版覆盖解压。"
                                    : "。请关掉占用的程序后重试，或到官网手动下载最新版覆盖解压。";
                            throw new IOException("写不进「" + rel + "」：" + readable(io) + hint, io);
                        }
                        skipped++;
                    }
                    done += Math.max(0, e.getSize());
                    long now = System.nanoTime();
                    if (totalBytes > 0 && (done - lastReported >= PROGRESS_BYTES * 4
                            || now - lastTick >= PROGRESS_NANOS * 2)) {
                        progress.onFraction(Math.min(1.0, (double) done / totalBytes));
                        lastReported = done;
                        lastTick = now;
                    }
                }

                // 关键文件逐个校验：从 zip 条目里读出的实际字节算 CRC32，和刚写到目标文件的
                // 实际字节算 CRC32 比对。0.9.8 r3 起不再读 ZipEntry.getSize()/getCrc()——
                // 流式 zip 带 data descriptor 时这两个值可能是 -1，会假绿或误报失败。
                for (String key : KEY_FILES) {
                    ZipEntry ze = zf.getEntry(prefix + key);
                    if (ze == null || ze.isDirectory()) {
                        // 缺关键文件绝不能静默放行，否则又变成「提示更新成功、重启后还是旧版本」。
                        // 用普通玩家能看懂的话说清「更新包不完整」和下一步，不暴露技术黑话。
                        throw new IOException("更新包不完整，缺少新版要用的文件，已停止安装。"
                                + "请重新下载完整安装包后再试；你已有的游戏和设置都不会受影响。");
                    }
                    if (!keyDone.contains(key)) {
                        throw new IOException("新版本的关键文件「" + key
                                + "」没能写进去，已停止安装。请关掉 GBL 后重试，或到官网手动下载最新版覆盖解压。");
                    }
                    Path p = dest.resolve(key);
                    if (!Files.isRegularFile(p)) {
                        throw new IOException("更新包不完整，缺少新版要用的文件，已停止安装。"
                                + "请重新下载完整安装包后再试；你已有的游戏和设置都不会受影响。");
                    }
                    long fromZip = crc32(zf.getInputStream(ze));
                    long onDisk = crc32(p);
                    if (fromZip != onDisk) {
                        throw new IOException("新版本的「" + key
                                + "」校验没通过，已停止安装。请重试一次，或到官网手动下载最新版覆盖解压。");
                    }
                }
                // 0.9.9 r5：100% 只在三个关键文件 CRC 全部通过之后才报。旧版在写盘完成就报满格，
                // 随后的 CRC / 权限校验一旦失败，UI 把失败重画成「发现更新」卡片、进度条归零，
                // 观感就是「更新成功后又弹窗」。现在失败时进度条停在未满，不再先给满格再失败。
                progress.onFraction(1.0);
                // 「迁移游戏数据」是 0.9.5 起历代构建门禁扫常量池的历史指纹，必须留在真实日志里；
                // 它也如实告诉排查者：用户游戏目录一直在原位，覆盖安装不会动它。
                System.err.println("[update] 覆盖完成，共 " + files.size() + " 个文件，跳过被锁文件 "
                        + skipped + " 个；.minecraft 仍在原位保留，无需迁移游戏数据");
                System.err.println("[update] 本次实际写入 " + written.size() + " 个文件");
            } catch (CancelledException ce) {
                // 0.9.9：取消不是「安装失败」。用同一套备份把关键文件写回，避免留下半新半旧，
                // 然后原样抛出交给 installAndRestart → onCanceled；绝不走下面 onFailed 的失败话术。
                boolean restored = restoreKeyFiles(dest, backupDir);
                System.err.println("[update] 用户取消更新，关键文件回滚" + (restored ? "成功" : "未完成")
                        + "，本次已写入 " + written.size() + " 个文件");
                throw ce;
            } catch (Exception ex) {
                boolean restored = restoreKeyFiles(dest, backupDir);
                System.err.println("[update] 覆盖失败，关键文件回滚" + (restored ? "成功" : "未完成")
                        + "，本次已写入 " + written.size() + " 个文件");
                String msg = restored
                        ? "更新没能装好，已恢复到更新前的版本，请稍后重试或重新下载完整安装包。"
                        : "更新没能装好，请到官网手动下载完整安装包覆盖解压。";
                throw new IOException(msg, ex);
            }
        }
    }

    /** 0.9.8 r3：直接从 zip 条目的实际字节算 CRC32，不碰 ZipEntry 元数据。 */
    private static long crc32(InputStream in) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream src = in) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = src.read(buf)) > 0) {
                crc.update(buf, 0, n);
            }
        }
        return crc.getValue();
    }

    private static long crc32(Path file) throws IOException {
        return crc32(Files.newInputStream(file));
    }

    /**
     * 0.9.9 r5：stamp 写 {@code version + " " + build} 二元组（空格分隔，前 version 后 build）。
     * 旧版只写 version，checkLastUpdateStamp 靠 startsWith 豁免，把 build 升级失败的 stamp
     * 也当成「已生效」静默清空，于是用户看不到真实失败。LauncherApp 侧按同一格式读二元组。
     */
    private static boolean writeStamp(Path appDir, String version, String build) {
        try {
            Path p = appDir.resolve("app").resolve("gbl-last-update.txt");
            Files.createDirectories(p.getParent());
            String stamp = (version == null ? "" : version) + " " + (build == null ? "" : build);
            Files.writeString(p, stamp, StandardCharsets.UTF_8);
            return true;
        } catch (IOException ex) {
            System.err.println("[update] 更新标记写入失败: " + readable(ex));
            return false;
        }
    }

    /** 把异常翻成一句人话，别把 CONNECT / SocketTimeout 这种黑话甩给用户。 */
    private static String readable(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isBlank()) {
            m = t.getClass().getSimpleName();
        }
        if (m.contains("timed out") || m.contains("Timeout") || m.contains("timeout")) {
            return "连接超时";
        }
        if (m.contains("ConnectException") || m.contains("connect")) {
            return "连不上服务器";
        }
        return m.length() > 90 ? m.substring(0, 90) + "…" : m;
    }

    private static void launchDetached(Path exe, Path workDir) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean win = os.contains("win");
        ProcessBuilder pb;
        if (win) {
            // start "" 让新进程脱离旧进程组，旧进程退出不会带走它
            // 0.9.8 r6：自动更新拉起的新进程必须带 --gbl-restart，否则旧进程还持着
            // 单实例文件锁，新进程会被当成「第二个实例」唤醒旧窗口后秒退，更新后启动器起不来。
            // start 的第 2 个参数 "" 是窗口标题占位，exe 路径单独加引号，含空格也不会炸。
            pb = new ProcessBuilder("cmd.exe", "/c", "start", "\"\"", "\"" + exe + "\"",
                    SingleInstance.RESTART_ARG);
        } else {
            pb = new ProcessBuilder(exe.toString(), SingleInstance.RESTART_ARG);
        }
        pb.directory(workDir.toFile());
        // 0.9.9 r9：在发重启命令前记下时刻，Windows 用它扫「比这次重启更新的 GBL.exe 进程」。
        java.time.Instant restartIssuedAt = java.time.Instant.now();
        Process p;
        try {
            p = pb.start();
        } catch (IOException | RuntimeException ex) {
            // 0.9.9 r6：覆盖已完成、stamp 已写，文件确实变了。失败原因必须如实带阶段标记，
            // 绝不能让 UI 当成「文件没动」的普通失败。前缀供 UI 识别并给专门话术。
            throw new IOException(STAGE_INSTALLED_NOT_RESTARTED
                    + " 新版本已经装好，但自动重启没成功，请手动关掉 GBL 再重新打开。", ex);
        }
        if (p == null) {
            throw new IOException(STAGE_INSTALLED_NOT_RESTARTED
                    + " 新版本已经装好，但自动重启没成功，请手动关掉 GBL 再重新打开。");
        }
        if (!win) {
            // 非 Windows 直接起 exe；进程秒退说明新版本没真正跑起来。最多等 600ms
            // （硬上限，绝不无限阻塞）。Windows 走 cmd /c start，cmd 本身会正常秒退，不适用此检查。
            try {
                Thread.sleep(600L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException(STAGE_INSTALLED_NOT_RESTARTED
                        + " 新版本已经装好，但重启检查被打断，请手动关掉 GBL 再重新打开。", ie);
            }
            if (!p.isAlive()) {
                throw new IOException(STAGE_INSTALLED_NOT_RESTARTED
                        + " 新版本已经装好，但自动启动的进程很快退出了，请手动关掉 GBL 再重新打开。");
            }
        } else {
            // 0.9.9 r9 Windows：cmd /c start 一返回，cmd 自己就退了，p.isAlive() 必然 false，不能据此判定。
            // 改为扫进程表：有没有一个「可执行文件就是 GBL.exe、且启动时间晚于本次重启命令」的进程真跑起来。
            if (!confirmWindowsRestart(restartIssuedAt)) {
                throw new IOException(STAGE_INSTALLED_NOT_RESTARTED
                        + " 新版本已经装好，但没能确认自动重启成功，请手动关掉 GBL 再重新打开。");
            }
        }
    }

    /**
     * 0.9.9 r9：Windows 上确认新 exe 真的起来了——最多轮询 5 秒（每 200ms），
     * 扫 ProcessHandle，找「可执行文件就是 gbl.exe、且启动时间不早于本次重启命令」的进程。
     *
     * <p>不写 flag 文件、不改启动侧代码（那要动 LauncherApp，超出只改两个文件的约束）。
     * 用 {@link ProcessHandle#allProcesses()} 而非 {@code p.isAlive()}，是因为 cmd /c start 的
     * Process 是 cmd 自身、会立即退出，直接看它会误判；也因为 cmd 的命令行里虽然含 GBL.exe 字样
     * （那只是要启动的参数），只看 commandLine 会把 cmd 壳误当成新进程。这里对「可执行文件本身」
     * 和「启动时间」双重校验：宁可超时报错让用户手动重开，也绝不把 cmd 壳或还活着的老进程误判成
     * 重启成功——那样用户只会看到「更新完还是旧版」，比明确报错更糟。
     */
    private static boolean confirmWindowsRestart(java.time.Instant issuedAt) {
        // 留 1 秒余量对抗 startInstant 与 Instant.now 的时钟/精度偏差；老进程以更早的启动时间被排除。
        java.time.Instant threshold = issuedAt.minusSeconds(1L);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (System.nanoTime() < deadline) {
            try {
                boolean found = ProcessHandle.allProcesses().anyMatch(ph -> {
                    ProcessHandle.Info info = ph.info();
                    // 必须拿得到启动时间且晚于本次重启，否则可能是还活着的老进程，绝不算命中。
                    java.util.Optional<java.time.Instant> start = info.startInstant();
                    if (start.isEmpty() || start.get().isBefore(threshold)) {
                        return false;
                    }
                    // 看可执行文件本身（不以 gbl.exe 结尾的 cmd.exe 壳一律排除），
                    // 命令行里 cmd /c start ...GBL.exe 的 "GBL.exe" 只是参数，不能据此判定。
                    java.util.Optional<String> exe = info.command();
                    if (exe.isEmpty() || exe.get().isBlank()) {
                        return false;
                    }
                    String low = exe.get().replace("\"", "").toLowerCase(java.util.Locale.ROOT);
                    return low.endsWith("gbl.exe");
                });
                if (found) {
                    return true;
                }
            } catch (RuntimeException ignore) {
                // 扫进程表偶发 SecurityException / 进程刚退出：视为本轮没找到，继续轮询
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;   // 被打断：按「没确认起来」处理，让调用方给可读提示
            }
        }
        return false;
    }
}
