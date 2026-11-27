package com.mclauncher;

import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * .mrpack 整合包导入（v0.7.0 P0-6）。
 *
 * <p>两段式：{@link #read} 只读 zip 里的 {@code modrinth.index.json} 拼出确认卡数据
 * （不落盘、不发网络）；{@link #run} 在后台线程里跑全流程——
 * 确保基础版本 → 装加载器 → 解压 overrides → 下载 files → 落索引。
 * 加载器安装直接复用 {@link LoaderInstaller}，基础版本下载直接复用
 * {@link GameDownloader}，本类不复制任何安装逻辑（架构 §1.7）。
 *
 * <ul>
 *   <li><b>取消</b>：{@code cancelled} 旗标每步都问；取消后删本次产生的全部 {@code .part}、
 *       不写索引、已下完的文件保留（重导入按哈希跳过，可续）。</li>
 *   <li><b>不静默覆盖</b>（PRD 规则②）：目标已存在且内容哈希一致 → 跳过；
 *       不一致 → 旧文件移到 {@code .minecraft/.gbl-modpack-backup/<整合包名>/} 再写新的。</li>
 *   <li><b>zip-slip 防护</b>：overrides 条目解压前归一化，越出 {@code .minecraft} 根的一律跳过。</li>
 *   <li><b>线程</b>：{@code run()} 由 UI 起 daemon 线程调用（{@code gbl-mrpack}）；
 *       {@link Listener} 回调发生在调用线程 / 文件下载线程上，界面要自己
 *       {@code Platform.runLater} 转 FX 线程。</li>
 * </ul>
 */
public final class ModpackImporter {

    /**
     * files 下载并行度上限。0.9.2 r4：4 → 32，对齐 LauncherX / HMCL 高并发。
     * 0.9.9 r6 缺陷3：判定出海域名走直连时整批降到 6（见 {@link #downloadFiles} 里的独立池），
     * 避免无代理机器 32 路并发打海外直连批量超时；{@code latch.await(20, MINUTES)} 与
     * 内层 finally 里无条件 {@code countDown()} 保持不变（0.9.2 r1 的血泪修复，绝不回归）。
     */
    private static final int PARALLEL = 32;
    /** 等基础版本装好的轮询上限（分钟，U7 拍板）。 */
    private static final int BASE_POLL_MINUTES = 15;
    /** 轮询间隔。 */
    private static final long POLL_MILLIS = 2_000L;
    /** zip 里两个「解压到 .minecraft 根」的前缀目录（后者优先，同路径时覆盖前者）。 */
    private static final String OVERRIDES = "overrides/";
    private static final String CLIENT_OVERRIDES = "client-overrides/";
    /** 备份目录名：只备份真被覆盖的文件，不把用户目录塞满。 */
    private static final String BACKUP_DIR = ".gbl-modpack-backup";

    private static final ExecutorService POOL = Executors.newFixedThreadPool(PARALLEL, runnable -> {
        Thread thread = new Thread(runnable, "gbl-mrpack-file");
        thread.setDaemon(true);   // 下载线程绝不能拦住 JVM 退出
        return thread;
    });

    /** 0.9.4 指纹：整合包失败有界重试。 */
    private static final String FP_094_BOUNDED_RETRY = "0.9.4 整合包失败有界重试";
    static { System.err.println("[mrpack] 0.9.4: " + FP_094_BOUNDED_RETRY); }

    /** 0.9.9 r7 指纹：整合包导入前先查已装加载器，同版本复用 / 异版本换装，别再撞「先卸载」硬闸。 */
    public static final String FP_099_R7_MODPACK = "0.9.9 r7 整合包加载器冲突自动换装";
    static { System.err.println("[mrpack] 0.9.9 r7: " + FP_099_R7_MODPACK); }

    private ModpackImporter() {
        // 静态工具类，别让人 new 出来
    }

    // ------------------------------------------------------------------ 数据

    /** index.json 里的一条待下文件。{@code optional} = {@code env.client} 为
     * {@code "optional"} 或 {@code "unsupported"}（二者下载阶段都跳过；0.9.9 r10 补上
     * unsupported）；{@code envClient} 保留 env.client 原始三态
     * （required / optional / unsupported / null），
     * 供安装确认卡按「必须下载 / 可选 / 已内置」分组展示（0.9.9 r9）。 */
    public record MrFile(String path, String url, String sha512, String sha1,
                         long size, boolean optional, String envClient) {
    }

    /** 确认卡要展示的全部信息。{@code loaderType} 为 null = 原版包；Forge/NeoForge 已由
     * {@link LoaderType#fromDepKey} 识别并复用 {@link LoaderInstaller}。 */
    public record Manifest(String name, String versionId, String summary, String mcVersion,
                           LoaderType loaderType, String loaderVersion,
                           List<MrFile> files, long totalBytes) {
    }

    /** 导入过程的事件出口。回调发生在调用线程 / 下载线程上，界面自行转 FX 线程。 */
    public interface Listener {
        /** 「正在安装 Fabric…」「正在解压…」这类阶段文字。 */
        void onStage(String text);

        /** 一个文件落地（成功 / 失败 / 已存在跳过都算一次，optional 不进分母）。 */
        void onFileDone(int done, int total, String fileName, boolean ok);

        /** 收尾。{@code ok=false} 时 {@code note} 说原因（取消 / 失败人话）。 */
        void onFinished(boolean ok, String note);

        /** 字节级进度：整包已下字节 / 整包总字节。回调发生在下载线程上。 */
        default void onBytes(long doneBytes, long totalBytes) { }
    }

    // ------------------------------------------------------------------ 读取（只读，不落盘）

    /**
     * 只读 zip 里的 {@code modrinth.index.json}，拼出确认卡数据。
     *
     * <p>不做任何写盘 / 网络动作，界面拿到 {@link Manifest} 就能渲染确认卡。
     * 文件数与总大小按 index 全量统计（含 optional）——PRD 验收②要求二者与 index 一致。
     *
     * @throws IOException 不是 zip / 没有 index / index 认不出来 / 没写 MC 版本（message 说人话）
     */
    public static Manifest read(Path mrpack) throws IOException {
        if (mrpack == null || !Files.isRegularFile(mrpack)) {
            throw new IOException("找不到这个整合包文件，重新选一个试试");
        }
        Map<?, ?> index;
        try (ZipFile zip = new ZipFile(mrpack.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry("modrinth.index.json");
            if (entry == null) {
                throw new IOException("这个文件里没有 modrinth.index.json，"
                        + "可能不是 Modrinth 整合包（.mrpack）");
            }
            try (InputStream in = zip.getInputStream(entry)) {
                index = asMap(Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)),
                        "modrinth.index.json");
            }
        } catch (IOException ex) {
            throw ex;   // 上面那些人话原样带出去
        } catch (RuntimeException ex) {
            throw new IOException("modrinth.index.json 里有一段认不出来的内容，这个整合包可能坏了");
        }

        String name = str(index.get("name"));
        if (name == null || name.isBlank()) {
            // 整合包没写名字：拿文件名顶上，确认卡不能空一行
            name = stripExtension(mrpack.getFileName().toString());
        }
        String versionId = str(index.get("versionId"));
        String summary = str(index.get("summary"));

        Map<?, ?> dependencies = index.get("dependencies") instanceof Map<?, ?> deps ? deps : Map.of();
        String mcVersion = str(dependencies.get("minecraft"));
        if (mcVersion == null || mcVersion.isBlank()) {
            throw new IOException("这个整合包没写要哪个 MC 版本（dependencies.minecraft 缺失），不敢导");
        }
        LoaderType loaderType = null;
        String loaderVersion = null;
        for (Map.Entry<?, ?> dep : dependencies.entrySet()) {
            LoaderType type = LoaderType.fromDepKey(String.valueOf(dep.getKey()));
            if (type != null) {
                loaderType = type;   // 整合包只会带一个元数据型加载器
                loaderVersion = String.valueOf(dep.getValue());
                break;
            }
        }

        List<MrFile> files = new ArrayList<>();
        long total = 0L;
        if (index.get("files") instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                String path = str(m.get("path"));
                if (path == null) {
                    continue;   // 没路径的文件落不了盘，等于不存在
                }
                String sha512 = null;
                String sha1 = null;
                if (m.get("hashes") instanceof Map<?, ?> hashes) {
                    sha512 = str(hashes.get("sha512"));
                    sha1 = str(hashes.get("sha1"));
                }
                // 0.9.9 r9：保留 env.client 原始三态（required/optional/unsupported），
                // 缺省 null 按「必须下载」处理（原语义不变）。
                // 0.9.9 r10 补 BUG-1：optional 和 unsupported 都按跳过下载处理——
                // .mrpack 规范里 unsupported 表示客户端不需要该文件（常见服务端专用 mod），
                // 不能白下进客户端 mods 目录；envClient 原值仍完整保留供确认卡分组展示。
                String envClient = null;
                if (m.get("env") instanceof Map<?, ?> env) {
                    envClient = str(env.get("client"));
                }
                boolean optional = "optional".equals(envClient) || "unsupported".equals(envClient);
                String url = "";
                if (m.get("downloads") instanceof List<?> downloads && !downloads.isEmpty()) {
                    url = String.valueOf(downloads.get(0));
                }
                // fileSize 是 Double 解析的（自研 Json 口径），取整走 longValue
                long size = m.get("fileSize") instanceof Number n ? n.longValue() : 0L;
                files.add(new MrFile(path, url, sha512, sha1, size, optional, envClient));
                total += size;
            }
        }
        return new Manifest(name, versionId, summary, mcVersion, loaderType, loaderVersion,
                List.copyOf(files), total);
    }

    // ------------------------------------------------------------------ 导入（后台线程调用）

    /**
     * 全流程导入。取消走 {@code cancelled} 旗标（返回前会调 {@link Listener#onFinished}）；
     * 任何人话失败抛 {@link IOException}。
     *
     * <p>步骤与架构 §1.7 一致：① 确保基础版本 → ② 装加载器 → ③ 解压 overrides →
     * ④ 下载 files（并行≤4）→ ⑤ 落 {@code modrinth.index.json} → ⑥ 写 ModIndex。
     */
    public static void run(Path mrpack, Manifest manifest, AtomicBoolean cancelled,
                           Listener listener) throws IOException {
        if (manifest == null) {
            throw new IOException("整合包信息没读出来，重新选一个试试");
        }
        // 没人听也别让导入停下来：全部落空，日志照打
        Listener out = listener == null ? new Listener() {
            @Override
            public void onStage(String text) {
            }

            @Override
            public void onFileDone(int done, int total, String fileName, boolean ok) {
            }

            @Override
            public void onFinished(boolean ok, String note) {
            }
        } : listener;
        String mc = manifest.mcVersion();
        System.out.println("[mrpack] 开始导入「" + manifest.name() + "」→ " + mc
                + "，文件 " + manifest.files().size() + " 个");

        // ① 基础版本：装好了就跳过；没装就复用下载队列现下一份（U7：轮询上限 15 分钟）。
        // 等安装的途中被取消 → 就此收尾，别接着装加载器 / 解压（取消要停得干净）
        if (!ensureBaseVersion(mc, cancelled, out)) {
            out.onFinished(false, "已取消");
            return;
        }

        // ② 加载器：元数据型 / 安装器型（Forge/NeoForge）统一复用 LoaderInstaller.install
        // 0.9.9 r10 补 BUG-2：ensureLoader 接受取消旗标并在各阶段/回调检查，
        // 安装中途点取消要立刻收尾，别一路装完。
        if (!ensureLoader(mrpack, manifest, cancelled, out)) {
            out.onFinished(false, "已取消");
            return;
        }

        // ③ overrides：先 client-overrides 后 overrides——同路径时客户端那份说了算
        if (!unzipOverrides(mrpack, manifest, CLIENT_OVERRIDES, cancelled, out)
                || !unzipOverrides(mrpack, manifest, OVERRIDES, cancelled, out)) {
            out.onFinished(false, "已取消");
            return;
        }

        // ④ files：optional 跳过；已存在且哈希一致跳过；其余下载（失败可重导入续）
        int skippedOptional = 0;
        List<MrFile> wanted = new ArrayList<>();
        for (MrFile file : manifest.files()) {
            if (file.optional()) {
                skippedOptional++;
                System.out.println("[mrpack] 跳过可选文件: " + file.path());
                continue;
            }
            wanted.add(file);
        }
        if (!downloadFiles(wanted, cancelled, out)) {
            out.onFinished(false, "已取消");
            return;
        }

        if (cancelled != null && cancelled.get()) {
            out.onFinished(false, "已取消");
            return;
        }

        // ⑤ 索引原样落一份到 .minecraft 根（P2 更新 / 导出用）：直接从 zip 里拷字节，
        // 不重新序列化——保持和整合包作者发出来的一模一样
        writeIndexFile(mrpack);

        // ⑥ Modrinth 文件写本地索引（U8）：P1 更新检测的数据源
        writeModIndex(wanted, mc);

        // 0.8.5：记进整合包登记簿（游戏管理 → 管理整合包 板块用）
        try {
            List<String> relFiles = wanted.stream().map(MrFile::path).toList();
            String loaderId = manifest.loaderType() == null ? null : manifest.loaderType().id();
            ModpackRegistry.record(new ModpackRegistry.Pack(manifest.name(), manifest.versionId(),
                    manifest.mcVersion(), loaderId, manifest.loaderVersion(), relFiles,
                    java.time.Instant.now().toString(), true));
        } catch (IOException ex) {
            System.err.println("[mrpack] 写整合包登记簿失败（不影响导入）: " + ex);
        }

        String note = "整合包「" + manifest.name() + "」导入完成";
        if (skippedOptional > 0) {
            note += "（跳过 " + skippedOptional + " 个可选文件）";
        }
        out.onFinished(true, note);
        System.out.println("[mrpack] 「" + manifest.name() + "」导入完成");
    }

    // ------------------------------------------------------------------ ① 基础版本

    /**
     * 确保基础版本已安装。没装就排进下载队列等它装好；官方清单里查不到 → 提示去下载页先装（U7）。
     *
     * @return false = 等待途中被取消（调用方收尾退出，不再往下装加载器）
     */
    private static boolean ensureBaseVersion(String mcVersion, AtomicBoolean cancelled,
                                             Listener out) throws IOException {
        if (GameDownloader.installed(mcVersion)) {
            return true;
        }
        VersionManifest.OfficialVersion found = null;
        for (VersionManifest.OfficialVersion v : VersionManifest.allView()) {
            if (mcVersion.equals(v.id())) {
                found = v;
                break;
            }
        }
        if (found == null) {
            throw new IOException("「" + mcVersion + "」还没安装，官方清单里也找不到它——"
                    + "请先到下载页安装该版本再导入");
        }
        out.onStage("正在下载基础版本 " + mcVersion + " …");
        System.out.println("[mrpack] 基础版本「" + mcVersion + "」没装，排进下载队列");
        GameDownloader.DEFAULT.submit(mcVersion, found.kind(), found.released(), found.url());

        long deadline = System.currentTimeMillis() + BASE_POLL_MINUTES * 60_000L;
        long started = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadline) {
            if (cancelled != null && cancelled.get()) {
                return false;   // 取消由调用方收尾；队列里那个任务用户可以自己在下载页取消
            }
            GameDownloader.Item item = GameDownloader.DEFAULT.item(mcVersion);
            if (item == null) {
                throw new IOException("基础版本「" + mcVersion + "」的下载任务被移出了队列，重试一次看看");
            }
            switch (item.state()) {
                case DONE -> {
                    return true;
                }
                case FAILED -> throw new IOException("基础版本「" + mcVersion + "」没下下来："
                        + item.note() + "。检查网络后重试");
                case CANCELLED -> throw new IOException("基础版本「" + mcVersion + "」的下载被取消了");
                default -> {
                    // 还在排队 / 正在下：等一拍再问。最长可能等 15 分钟，期间给界面一条
                    // 阶段心跳（只报文字，不动控制流 / 取消判据 / 超时 / 轮询间隔），
                    // 免得用户以为「卡死了、永不结束」。
                    long waitedSec = (System.currentTimeMillis() - started) / 1000L;
                    out.onStage("正在准备基础版本 " + mcVersion + " …（已等 " + waitedSec + " 秒）");
                }
            }
            sleepQuietly(POLL_MILLIS);
        }
        if (!GameDownloader.installed(mcVersion)) {
            throw new IOException("等基础版本「" + mcVersion + "」装好等了 " + BASE_POLL_MINUTES
                    + " 分钟还没好，检查网络后重试");
        }
        return true;
    }

    // ------------------------------------------------------------------ ② 加载器

    /**
     * 受控取消信号：{@link LoaderInstaller} 安装 / 卸载过程中从 listener 回调里抛出的中断，
     * 由 {@link #ensureLoader} 捕获后统一收敛成「已取消」（返回 false），不当成安装失败。
     *
     * <p>0.9.9 r10 补 BUG-2。用受控 RuntimeException 而非受检异常，是因为
     * {@link LoaderInstaller#install} / {@code uninstall} 的签名只有 {@code throws IOException}，
     * 无法往回调里透传受检异常；而它们内部对 listener 回调没有兜底 catch，信号能原样冒回来。
     */
    private static final class CancelSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /**
     * 装加载器。{@code manifest.loaderType()} 非 null → 直接复用 {@link LoaderInstaller}（元数据型与
     * 安装器型 Forge/NeoForge 都走这里，后者由 {@code installInstaller} 生成独立派生 profile）；
     * 为 null → 没有可识别的加载器依赖，按原版整合包处理。
     *
     * <p><b>0.9.9 r7</b>：导入前先看这个 MC 版本上已经装着什么加载器（{@link LoaderInstaller#status}）。
     * 旧实现无脑调用 {@code install}，元数据型的 {@code installMeta} 有「已装就必须先手动卸载」的硬闸，
     * 于是「朋友用同一个 MC 版本装过 Fabric 0.20.1、再导入一个要别的 Fabric 版本的整合包」时，
     * 文件明明全下完了却在装加载器这一步抛「这个版本已经装了 Fabric 0.20.1，先卸载它才能装别的加载器」，
     * 界面上就成了「绿色满进度条 + 红字」，看着像下载失败。现在按三种情形处理：
     * <ol>
     *   <li>没装加载器 → 原样安装（老行为）；</li>
     *   <li>已装且类型+版本与整合包声明<b>完全一致</b> → 直接复用，跳过安装（导入幂等，不会重复改 JSON、不报错）；</li>
     *   <li>已装但类型或版本不同 → 先把旧的卸载还原成原版（{@link LoaderInstaller#uninstall}，
     *       从 {@code <id>.vanilla.json} 逐字节还原），再按整合包声明装新的——这是整合包要的版本优先，
     *       不该让用户为「导个包」先手动卸载。</li>
     * </ol>
     * 卸载失败（备份丢了 / 安装器型派生目录找不到）不吞，抛中文 IOException 说清下一步，
     * 绝不带着半新半旧的加载器状态继续解压 overrides。
     *
     * <p><b>0.9.9 r10</b>（BUG-2）：全程检查 {@code cancelled} 旗标——进方法、卸载前、
     * 安装前、安装返回后，以及传给 {@link LoaderInstaller} 的 listener 回调里都问；
     * 一旦取消就停在当前阶段、不再装加载器、不写残留半成品，返回 {@code false}
     * 由调用方给「已取消」反馈。正常走完返回 {@code true}。
     */
    private static boolean ensureLoader(Path mrpack, Manifest manifest, AtomicBoolean cancelled,
                                        Listener out) throws IOException {
        if (cancelled != null && cancelled.get()) {
            return false;
        }
        LoaderType type = manifest.loaderType();
        String version = manifest.loaderVersion();
        if (type != null) {
            if (version == null || version.isBlank()) {
                throw new IOException("这个整合包没写 " + type.label()
                        + " 的版本（dependencies 里只有键没有值），不敢导");
            }
            String mcVersion = manifest.mcVersion();
            LoaderInstaller.Status already = LoaderInstaller.status(mcVersion);
            LoaderInstaller.Listener loaderListener = new LoaderInstaller.Listener() {
                @Override
                public void onStage(String text) {
                    // 0.9.9 r10 补 BUG-2：onStage 在调用线程上（元数据型各阶段、安装器型子进程
                    // 启动前），抛受控信号让 ensureLoader 当场收尾，不再往下一步走。
                    // 安装器型子进程的输出转发在后台 reader 线程上，抛出去只会停掉转发线程，
                    // 主流程靠 install 返回后的旗标检查兜底。
                    if (cancelled != null && cancelled.get()) {
                        throw new CancelSignal();
                    }
                    out.onStage(text);
                }

                @Override
                public void onLibProgress(int done, int total) {
                    // 库下载进度回调在工作线程上：这里只能停 UI 刷新，绝不能抛——
                    // 抛了会让 LibFetcher 的完成闩锁漏 countDown，白等 10 分钟。
                    // 真正的中断由 onStage / install 返回后的检查负责。
                    if (cancelled != null && cancelled.get()) {
                        return;
                    }
                    out.onStage("正在下载加载器依赖库…（" + done + "/" + total + "）");
                }
            };
            if (already != null) {
                boolean same = already.type() == type
                        && version.equals(already.loaderVersion());
                if (same) {
                    if (cancelled != null && cancelled.get()) {
                        return false;
                    }
                    // 情形②：整合包要的加载器和已装的完全一致，直接复用，别再走 install 撞硬闸。
                    out.onStage("这个版本已经装了 " + type.label() + " " + version
                            + "，和整合包要的一样，直接接着用");
                    System.out.println("[mrpack] 加载器已装同版本，复用: " + type.label()
                            + " " + version + "（" + FP_099_R7_MODPACK + "）");
                    return true;
                }
                // 情形③：类型/版本不一样 —— 整合包声明优先，先卸载旧的还原成原版，再装新的。
                out.onStage("这个版本装的是 " + already.type().label() + " "
                        + already.loaderVersion() + "，整合包要 " + type.label() + " "
                        + version + "，先换成整合包要的…");
                if (cancelled != null && cancelled.get()) {
                    return false;
                }
                try {
                    LoaderInstaller.uninstall(mcVersion, loaderListener);
                } catch (CancelSignal sig) {
                    return false;
                } catch (IOException ex) {
                    throw new IOException("整合包要 " + type.label() + " " + version
                            + "，但这个版本已经装了 " + already.type().label() + " "
                            + already.loaderVersion() + "，自动卸载没成功（"
                            + ex.getMessage() + "）。可以手动到「游戏管理」卸载加载器后重新导入",
                            ex);
                }
            }
            if (cancelled != null && cancelled.get()) {
                return false;
            }
            out.onStage("正在安装 " + type.label() + " " + version + " …");
            try {
                LoaderInstaller.install(mcVersion, type, version, loaderListener);
            } catch (CancelSignal sig) {
                return false;
            }
            // 安装器型（Forge/NeoForge）子进程在后台 reader 线程转发 stage，那边抛出的
            // 信号冒不回调用线程；install 返回后再问一次旗标兜底。
            if (cancelled != null && cancelled.get()) {
                return false;
            }
            return true;
        }
        // loaderType 为 null 说明这个整合包没带可识别的加载器依赖。
        // 注：Forge/NeoForge 在解析阶段已经通过 LoaderType.fromDepKey 被识别（它们的 depKey
        // 本就是 "forge" / "neoforge"），会走上面的 install 分支，这里不会再遇到它们，
        // 因此直接按原版整合包处理即可。
        System.out.println("[mrpack] 没有加载器依赖，按原版整合包处理");
        return true;
    }

    // ------------------------------------------------------------------ ③ overrides 解压

    /**
     * 把 zip 里 {@code prefix} 开头的条目解到 {@code .minecraft} 根。
     *
     * <p>zip-slip 防护：条目名归一化后拼出的路径必须仍在 {@code .minecraft} 根底下。
     * 冲突按哈希判：一致 → 跳过；不一致 → 旧文件备份到 {@value #BACKUP_DIR} 再写新的。
     * 一律先写 {@code .part} 再 move——取消时不留半个配置文件。
     *
     * @return false = 中途取消（调用方收尾退出）
     */
    private static boolean unzipOverrides(Path mrpack, Manifest manifest, String prefix,
                                          AtomicBoolean cancelled, Listener out)
            throws IOException {
        Path root = Platform.minecraftDir();
        Files.createDirectories(root);
        out.onStage("正在解压" + ("client-overrides/".equals(prefix) ? "客户端配置" : "整合包文件") + "…");
        int count = 0;
        try (ZipFile zip = new ZipFile(mrpack.toFile(), StandardCharsets.UTF_8)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (cancelled != null && cancelled.get()) {
                    return false;
                }
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(prefix)) {
                    continue;
                }
                String rel = normalizeRelative(name.substring(prefix.length()));
                if (rel.isEmpty()) {
                    continue;   // 前缀目录自己
                }
                Path target = root.resolve(rel).normalize();
                if (!target.startsWith(root)) {
                    System.err.println("[mrpack] 跳过越目录的条目: " + name);
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                Path part = target.resolveSibling(target.getFileName() + ".part");
                String newHash;
                try {
                    try (InputStream in = zip.getInputStream(entry)) {
                        newHash = copyHashing(in, part);
                    }
                    if (Files.isRegularFile(target)) {
                        String oldHash = sha1Of(target);
                        if (!oldHash.isBlank() && oldHash.equals(newHash)) {
                            deleteQuietly(part);   // 内容一样，跳过（不备份不覆盖）
                            continue;
                        }
                        backupThenReplace(target, rel, manifest.name());
                    }
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException | RuntimeException ex) {
                    // 写一半挂了（盘满 / 条目损坏）：半截 .part 绝不留在用户目录里
                    deleteQuietly(part);
                    throw ex;
                }
                count++;
            }
        }
        System.out.println("[mrpack] 「" + prefix + "」解出 " + count + " 个文件");
        return true;
    }

    /** 旧文件搬到 {@code .minecraft/.gbl-modpack-backup/<整合包名>/<原相对路径>}，再让新文件写上。 */
    private static void backupThenReplace(Path target, String rel, String packName)
            throws IOException {
        Path backup = Platform.minecraftDir()
                .resolve(BACKUP_DIR)
                .resolve(safeName(packName))
                .resolve(rel);
        Files.createDirectories(backup.getParent());
        Files.move(target, backup, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("[mrpack] 「" + rel + "」已存在且内容不同，旧文件备份到 " + backup);
    }

    // ------------------------------------------------------------------ ④ files 下载

    /**
     * 并行下载（上限 {@link #PARALLEL}，出海域名判定为直连时降到 6）。已存在且哈希一致 → 跳过（记一次 onFileDone(ok=true)）；
     * 其余走 {@link FileFetch}（.part + 验哈希 + 取消）。
     *
     * @return false = 中途取消
     */
    private static boolean downloadFiles(List<MrFile> wanted,
                                         AtomicBoolean cancelled, Listener out)
            throws IOException {
        if (wanted.isEmpty()) {
            return true;
        }
        Path root = Platform.minecraftDir();
        Files.createDirectories(root);
        int total = wanted.size();
        AtomicLong doneCount = new AtomicLong();
        AtomicLong doneBytes = new AtomicLong();
        long wantBytes = wanted.stream().mapToLong(MrFile::size).sum();
        List<MrFile> failed = new CopyOnWriteArrayList<>();
        List<String> skipped = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(total);
        out.onStage("正在下载整合包文件…（0/" + total + "）");
        // 0.9.2：把整包字节总量报给下载队列，进度条才有确定的分母（不再是 0/0 不确定态）。
        out.onBytes(0, wantBytes);

        // 0.9.9 r6 缺陷3：无代理机器 32 路并发打海外直连会批量超时——若判定出海域名走直连，
        // 本批降到 6 路；否则维持 32。线路是全 JVM 单例，判定一次即可；只影响本方法这批文件的
        // 独立线程池，绝不碰类级共享 POOL / 其它下载链路，也不动下面的 latch 与 countDown 配对。
        int parallel = PARALLEL;
        try {
            String probe = null;
            for (MrFile f : wanted) {
                if (ProxyRoute.isOverseas(f.url())) {
                    probe = f.url();
                    break;
                }
            }
            if (probe != null) {
                boolean direct = ProxyRoute.forUrl(probe) == Proxy.NO_PROXY
                        || ProxyRoute.describe().contains("直连");
                if (direct) {
                    parallel = 6;
                }
            }
        } catch (RuntimeException ignored) {
            // 判定失败按原并发，绝不影响下载本身
        }
        System.out.println("[mrpack] files 下载并发=" + parallel
                + (parallel < PARALLEL ? "（出海直连，已降并发避免批量超时）" : ""));
        // 独立线程池：只服务本批文件，任务照样跑完后再由 shutdown() 回收，不打断 latch 等待。
        ExecutorService filePool = Executors.newFixedThreadPool(parallel, runnable -> {
            Thread thread = new Thread(runnable, "gbl-mrpack-file");
            thread.setDaemon(true);   // 下载线程绝不能拦住 JVM 退出
            return thread;
        });
        for (MrFile file : wanted) {
            filePool.execute(() -> {
                // 这一个文件的已下字节：进度条要在「整包已下」里算上正在下的这一份
                AtomicLong fileDone = new AtomicLong();
                boolean ok = true;
                try {
                    if (cancelled != null && cancelled.get()) {
                        ok = false;   // 取消：这一个什么都没做，别记成成功
                        return;       // 闩锁在 finally 里放行
                    }
                    Path target = root.resolve(file.path()).normalize();
                    if (!target.startsWith(root)) {
                        throw new IOException("文件路径越出了游戏目录: " + file.path());
                    }
                    String name = target.getFileName().toString();
                    if (Files.isRegularFile(target) && matchesHash(target, file)) {
                        skipped.add(name);
                        System.out.println("[mrpack] 「" + name + "」已存在且哈希一致，跳过");
                    } else {
                        boolean good = FileFetch.download(file.url(), target, file.sha1(),
                                file.sha512(), cancelled, (done, len) -> {
                                    fileDone.set(Math.max(0, done));   // 先记账再报，进度条才连得上
                                    // 0.9.2：整包字节进度 = 已完成文件字节 + 当前文件已下字节；
                                    // Math.min 兜住不得超过总字节（防进度条超 100%）。
                                    out.onBytes(Math.min(wantBytes,
                                                    doneBytes.get() + fileDone.get()),
                                            wantBytes);
                                    out.onStage(
                                            "正在下载 " + name + "…（"
                                                    + LibFetcher.human(fileDone.get()) + " / "
                                                    + LibFetcher.human(Math.max(0, len)) + "）"
                                                    + packProgress((int) doneCount.get(), total,
                                                            doneBytes.get() + fileDone.get(),
                                                            wantBytes));
                                }, FileFetch.Options.modpack());   // 0.9.4 F3：整合包快通道
                        if (!good) {
                            if (cancelled != null && cancelled.get()) {
                                ok = false;   // 取消不是失败：不记 failed，整体按取消收尾
                                return;
                            }
                            ok = false;
                            failed.add(file);
                        }
                    }
                } catch (IOException | RuntimeException ex) {
                    ok = false;
                    failed.add(file);
                    System.err.println("[mrpack] 「" + file.path() + "」没处理好: " + ex);
                } finally {
                    try {
                        if (ok) {
                            doneBytes.addAndGet(Math.max(0, file.size()));
                        }
                        int done = (int) doneCount.incrementAndGet();
                        // 0.9.2：文件落地后把「已完成字节」定稿报一次；跳过已存在的文件也走这里，
                        // doneBytes 加了 file.size()，进度照样前进。
                        out.onBytes(Math.min(wantBytes, doneBytes.get()), wantBytes);
                        out.onFileDone(done, total, fileNameOf(file.path()), ok);
                    } finally {
                        // 0.9.2 P0：onBytes / onFileDone 转发到 DownloadCenter.Progress，队列任务
                        // 被移出时会抛 CancellationException（需继续往上抛，不在此吞）。
                        // countDown 必须在自己的 finally 里无条件执行，否则少 1 个计数 →
                        // latch.await 永久阻塞 → Work.run 永不返回、队列永远挂在 DOWNLOADING。
                        latch.countDown();
                    }
                }
            });
        }
        filePool.shutdown();   // 提交完毕即关池；已提交任务照常跑完，latch 仍等到实际任务数归零
        try {
            // 0.9.2：任一文件卡住时不再无限等——20 分钟还没收敛就抛 IOException，
            // 上游会走失败收尾，下载队列才能收敛成 FAILED 而不是永远挂在「下载中」。
            if (!latch.await(20, TimeUnit.MINUTES)) {
                throw new IOException("整合包文件下载超时（20 分钟），请检查网络后重试");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("导入整合包时被中断了");
        }
        if (cancelled != null && cancelled.get()) {
            return false;
        }
        // 0.9.4 F4：最终判定前做一轮有界重试（换线路），仍失败的才按「整体失败」收尾。
        retryFailed(failed, root, cancelled, out);
        if (cancelled != null && cancelled.get()) {
            return false;
        }
        if (!failed.isEmpty()) {
            StringBuilder sb = new StringBuilder("有 ").append(failed.size())
                    .append(" 个整合包文件没下下来：");
            int shown = Math.min(3, failed.size());
            for (int i = 0; i < shown; i++) {
                sb.append(i == 0 ? "" : "、").append(fileNameOf(failed.get(i).path()));
            }
            if (failed.size() > shown) {
                sb.append(" 等 ").append(failed.size()).append(" 个");
            }
            sb.append("。重新导入一次即可续传（下好的不会重下）");
            throw new IOException(sb.toString());
        }
        if (!skipped.isEmpty()) {
            System.out.println("[mrpack] " + skipped.size() + " 个文件已存在，按哈希跳过");
        }
        return true;
    }

    /**
     * 0.9.4 F4 / 0.9.9 r6：最终判定前，对 failed 清单做「有界重试」——最多 3 轮，每轮换一条线路：
     * 轮 1 {@link FileFetch.Options#retryDirect()} 强制 NO_PROXY 直连、轮 2
     * {@link FileFetch.Options#retryAuto()} 恢复自动选路竞速（出海域名即代理）、
     * 轮 3 {@link FileFetch.Options#retryMirror()} 启用 MCIM 备源 + 自动选路并<b>忽略一次性失败短路</b>
     * （0.9.9 r6 缺陷2：前两轮都禁 MCIM、本质都是裸直连，第三轮真正「换线路」）。
     * 前两轮禁用 MCIM 备源；三轮都复用 FileFetch 的类级单例 RACE_CLIENT / 同一套兜底链路，不新建 client。
     *
     * <p>重试成功的文件从 failed 移除；仍失败 / 取消 / 异常 / 越目录的一律保留在 failed，
     * 交回调用方按「整体失败」收尾（<b>绝不静默跳过失败文件</b>——会装出残包进游戏崩）。
     * 串行执行、不引入新 latch / 新线程，绝不触碰初始轮 downloadFiles 的 countDown / await
     * （0.9.2 r1 血泪修复）；进度出口传 null，不干扰 DownloadCenter 进度状态机。
     */
    private static void retryFailed(List<MrFile> failed, Path root,
                                    AtomicBoolean cancelled, Listener out) {
        if (failed.isEmpty()) {
            return;
        }
        FileFetch.Options[] rounds = {
                FileFetch.Options.retryDirect(),   // 轮 1：强制直连换线路
                FileFetch.Options.retryAuto(),     // 轮 2：恢复自动选路竞速
                FileFetch.Options.retryMirror()    // 轮 3：MCIM 备源 + 自动选路（忽略一次性失败短路）
        };
        for (FileFetch.Options opt : rounds) {
            if (failed.isEmpty() || (cancelled != null && cancelled.get())) {
                return;
            }
            out.onStage("正在重试 " + failed.size() + " 个没下下来的整合包文件…");
            System.out.println("[mrpack] 0.9.4 有界重试：本轮 " + failed.size()
                    + " 个失败文件，线路=" + (opt.routeOverride() == null ? "自动选路" : "强制直连")
                    + "，MCIM备源=" + (opt.mcimEnabled() ? (opt.forceMcim() ? "强制" : "启用") : "禁用"));
            List<MrFile> stillFailed = new ArrayList<>();
            for (MrFile file : failed) {
                if (cancelled != null && cancelled.get()) {
                    stillFailed.add(file);   // 取消：保留在 failed，按取消收尾，绝不静默跳过
                    continue;
                }
                try {
                    Path target = root.resolve(file.path()).normalize();
                    if (!target.startsWith(root)) {
                        stillFailed.add(file);
                        continue;
                    }
                    boolean good = FileFetch.download(file.url(), target, file.sha1(),
                            file.sha512(), cancelled, null, opt);
                    if (!good) {
                        stillFailed.add(file);
                    }
                } catch (IOException | RuntimeException ex) {
                    stillFailed.add(file);
                    System.err.println("[mrpack] 0.9.4 重试「" + file.path() + "」没处理好: " + ex);
                }
            }
            failed.clear();
            failed.addAll(stillFailed);
        }
    }

    /** 阶段条后面的总进度尾巴：「3/12 · 45.2/128.5 MB」。 */
    private static String packProgress(int doneFiles, int totalFiles, long doneBytes,
                                       long wantBytes) {
        return "（" + doneFiles + "/" + totalFiles + " · "
                + LibFetcher.human(doneBytes) + "/" + LibFetcher.human(wantBytes) + "）";
    }

    /** 已存在的文件是不是就是这个整合包要的那一份：sha512 优先、退化 sha1。 */
    private static boolean matchesHash(Path target, MrFile file) {
        String want512 = norm(file.sha512());
        String want1 = norm(file.sha1());
        if (!want512.isEmpty()) {
            return want512.equals(shaOf(target, "SHA-512"));
        }
        if (!want1.isEmpty()) {
            return want1.equals(shaOf(target, "SHA-1"));
        }
        return false;   // 整合包没给哈希：不敢断定「已存在」就是对的，重下一遍更稳
    }

    // ------------------------------------------------------------------ ⑤⑥ 落索引

    /** 把 zip 里的 modrinth.index.json 原样拷到 .minecraft 根（P2 更新 / 导出用）。 */
    private static void writeIndexFile(Path mrpack) throws IOException {
        Path target = Platform.minecraftDir().resolve("modrinth.index.json");
        try (ZipFile zip = new ZipFile(mrpack.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zip.getEntry("modrinth.index.json");
            if (entry == null) {
                return;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                Files.createDirectories(target.getParent());
                Path part = target.resolveSibling(target.getFileName() + ".part");
                Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        System.out.println("[mrpack] 索引已落到 " + target);
    }

    /**
     * Modrinth CDN 文件写本地 {@link ModIndex}（U8）。projectId / versionId 从
     * {@code cdn.modrinth.com/data/<id>/versions/<vid>/} 直链里抠（与
     * {@link ModrinthClient#install} 同一口径）。
     */
    private static void writeModIndex(List<MrFile> wanted, String mcVersion) {
        String now = Instant.now().toString();
        int added = 0;
        // 0.9.1 重发：先一次性批量反查所有 projectId 的可读名（best-effort、有界超时、失败吞掉），
        // 不逐个 MrFile 发请求。mrpack 的清单通常不带项目 title，能拿就填、拿不到留空；
        // 反查失败绝不影响导入（异常吞在 resolveTitles 内）。
        List<String> allIds = new ArrayList<>();
        for (MrFile file : wanted) {
            String[] id = ModrinthClient.cdnIdentity(file.url());
            if (id[0] != null && !allIds.contains(id[0])) {
                allIds.add(id[0]);
            }
        }
        Map<String, String> allTitles = ModrinthClient.resolveTitles(allIds);
        for (MrFile file : wanted) {
            String[] identity = ModrinthClient.cdnIdentity(file.url());
            if (identity[0] == null) {
                continue;   // 不是 Modrinth 直链（第三方源），索引不记
            }
            // 0.8.7 P0：这里原先用 project(identity[0]) + ModValidator.requiredOf(...) 反查
            // 「项目级依赖」，但 Modrinth 官方 /v2/project/{id} 的 Project schema 根本没有
            // dependencies 字段 → 反查恒空、还白白多打一次无效网络请求（U1 同源坏点）。
            // .mrpack 导入只持有 CDN 直链（MrFile），拿不到用户选中的 Version 对象，也就
            // 没有 Version.dependencies[] 这个真源可用；整合包的模组前置由包内文件清单
            // 自洽，本就不需要启动前的 requires 对账，故直接留空、不再发请求。
            List<String> requires = List.of();
            String selfTitle = allTitles.get(identity[0]);
            Map<String, String> titles = (selfTitle == null || selfTitle.isBlank())
                    ? Map.of() : Map.of(identity[0], selfTitle);
            try {
                ModIndex.add(mcVersion, new ModIndex.Entry(identity[0], identity[1],
                        fileNameOf(file.path()), norm(file.sha1()), now, requires, titles));
                added++;
            } catch (IOException ex) {
                // 索引写不进去不影响导入本身：文件都在盘上，P1 更新检测少一条而已
                System.err.println("[mrpack] 写 ModIndex 失败（不影响导入）: " + ex);
            }
        }
        if (added > 0) {
            System.out.println("[mrpack] 已把 " + added + " 个 Modrinth 文件记进本地索引");
        }
    }

    // ------------------------------------------------------------------ 内部工具

    /** 拷贝流并边写边算 sha1（小写 hex）；返回新内容的 sha1，供「内容一致 → 跳过」判据用。 */
    private static String copyHashing(InputStream in, Path part) throws IOException {
        MessageDigest digest = newDigest("SHA-1");
        try (InputStream source = in;
             var out = Files.newOutputStream(part, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = source.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (digest != null) {
                    digest.update(buffer, 0, n);
                }
            }
        }
        return digest == null ? "" : toHex(digest.digest());
    }

    /** 归一化相对路径：统一斜杠、去掉开头斜杠与 "." 段。空串 = 目录自己。 */
    private static String normalizeRelative(String raw) {
        String path = raw.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty() || ".".equals(path)) {
            return "";
        }
        return path;
    }

    /** 整合包名 → 备份目录名：只留安全字符，超长截断（Windows 路径段上限 255，留足余量）。 */
    private static String safeName(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5._-]", "_");
        if (cleaned.isBlank()) {
            return "modpack";
        }
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }

    private static String fileNameOf(String path) {
        int at = path.replace('\\', '/').lastIndexOf('/');
        return at < 0 ? path : path.substring(at + 1);
    }

    private static String stripExtension(String name) {
        int at = name.lastIndexOf('.');
        return at > 0 ? name.substring(0, at) : name;
    }

    private static String norm(String sha) {
        return sha == null ? "" : sha.trim().toLowerCase(Locale.ROOT);
    }

    private static String shaOf(Path file, String algorithm) {
        MessageDigest digest = newDigest(algorithm);
        if (digest == null) {
            return "";
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        } catch (IOException | RuntimeException ex) {
            System.err.println("[mrpack] 「" + file.getFileName() + "」算 " + algorithm
                    + " 失败，按不匹配处理: " + ex);
            return "";
        }
        return toHex(digest.digest());
    }

    private static String sha1Of(Path file) {
        return shaOf(file, "SHA-1");
    }

    private static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (java.security.NoSuchAlgorithmException ex) {
            return null;   // JDK 必带这两种；真缺了按「不校验」处理，别为校验堵死导入
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException ex) {
            System.err.println("[mrpack] 清半成品失败（不影响导入）: " + ex);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static Map<?, ?> asMap(Object parsed, String what) throws IOException {
        if (parsed instanceof Map<?, ?> map) {
            return map;
        }
        throw new IOException(what + " 认不出来（不是一份 JSON 对象），这个整合包可能坏了");
    }

    private static String str(Object raw) {
        return raw instanceof String text && !text.isBlank() ? text : null;
    }
}
