package com.mclauncher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 把「装好的游戏」真正拉起来（v0.4.7 的终点：能玩）。
 *
 * <p>服务层，不碰 UI：拼出一条 {@code java ...} 命令交给 {@link ProcessBuilder}，
 * 进程的输出一行一行喂给 {@link Session} 的回调，退出后由 {@link #explain(Session)}
 * 把「为什么没起来」翻成人话。
 *
 * <p>本版踩过的两个真问题，都在代码里留了痕：
 * <ul>
 *   <li>**磁盘上的 version.json 是精简版**，libraries / assetIndex / javaVersion 全不在里面。
 *       照着它启动必然缺 114 个库，所以元信息走 {@link VersionJson} 三级补齐。</li>
 *   <li>**新版 MC 必须给 {@code --uuid}**（1.21+ 的启动参数里写死了
 *       {@code --uuid ${auth_uuid}}），离线模式也得自己造一个 MD5 当 uuid，
 *       少传一个参数就会在启动阶段直接退出——而且退出码是 0，看不出毛病在哪。</li>
 * </ul>
 */
public final class GameLauncher {

    /** 控制台保留最近多少行：够翻「最后一点报错」，又不至于把内存吃光。 */
    private static final int TAIL_LIMIT = 2000;

    /** 启动前数资源缺不缺，最多数多少个（全量一万多个，数完 prepare 要好几秒）。 */
    private static final int ASSET_SCAN_LIMIT = 3000;
    /** 资源统计的缓存时间：同一版本短时间内连着启动不必重数。 */
    private static final long ASSET_CACHE_MS = 3 * 60 * 1000L;
    /** 资源统计按版本 id 缓存：值 = 统计那一刻 + 缺的个数。 */
    private static final Map<String, AssetStat> ASSET_CACHE = new java.util.HashMap<>();

    /** 一次资源统计的结果快照。 */
    private record AssetStat(long at, int missing) {
    }

    // ---------------------------------------------------------------- 1.3.0：已装 mod 的 Java 要求
    /** mod Java 要求缓存：键 = mods 目录绝对路径；值 = (目录最后修改时间, jar 数量) → 最高 major。 */
    private static final Map<Path, ModJavaStat> MOD_JAVA_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 一次扫描的指纹与结果；maxMajor 为 null 表示一个 mod 都没声明 Java 要求。 */
    private record ModJavaStat(long dirMtime, int jarCount, Integer maxMajor) {
    }

    /** 只采信合理区间的 Java major（8~25），畸形值一律忽略。 */
    private static final int MOD_JAVA_MIN = 8;
    private static final int MOD_JAVA_MAX = 25;

    /**
     * 一输出就「已经炸了」的标记。
     *
     * <p>MC 有一条讨厌的脾气：**主线程崩了 JVM 常常不死**——资源下载、存档线程还挂着，
     * 于是进程一直 alive，控制台再也不来一行，界面看着就是「点了启动以后黑屏卡住」。
     * 以前只能干等 45 秒 {@code idle} 才提示一句，用户早就以为启动器坏了。
     * 有了这几个标记，日志里一冒出来就能定性，等它安静 12 秒直接说人话（v0.5）。
     */
    /** 发完终止信号后还留多少秒给它自己收尾，超时才动强杀。 */
    private static final long STOP_GRACE_SECONDS = 4L;
    private static final String[] STARTUP_CRASH_MARKS = {
            "exceptionininitializererror",   // 类初始化就炸（最常见的「启动即崩」）
            "nosuchmethoderror",             // 库版本对不上：26.x 的 jtracy 就是这么死的
            "exception in thread \"render thread\"",
            "exception in thread \"main\"",
            "error occurred during initialization of vm",
    };

    /** 资源补完之后调用：把「还缺多少」的缓存作废，重新数一遍。 */
    private static void forgetAssetCache() {
        ASSET_CACHE.clear();
    }

    /** 离线模式默认玩家名（设置页以后可以改）。 */
    public static final String DEFAULT_PLAYER = "GBLPlayer";

    /** 上次异常退出标志（HMCL ③）：文件在 = 下次启动强制全量校验。 */
    private static final Path ABNORMAL_EXIT_FLAG =
            Paths.get(System.getProperty("user.home"), ".gbl", "last-exit-abnormal");

    /**
     * 诊断用的最近一次启动现场。服务层没有 UI 上下文，导出诊断时才知道该写谁的命令。
     * 全部 volatile + 只做整体赋值，启动线程与 UI 线程之间不需要额外同步，也不引入线程问题。
     */
    private static volatile String lastPrepareId = "(未知)";
    private static volatile Plan lastPlan = null;
    private static volatile String lastBlockedReason = "(无)";

    /** {@code java --list-modules} 的结果缓存：同一个可执行文件一轮启动只探一次。 */
    private static final Map<Path, Boolean> JAVA_MODULE_CHECK =
            new java.util.concurrent.ConcurrentHashMap<>();

    private GameLauncher() {
        // 静态工具类
    }

    /**
     * 启动准备结果：一条能直接喂 {@link ProcessBuilder} 的命令，外加启动前该提醒的事。
     *
     * @param notes 不阻断启动但要说的实话（比如「有 N 个资源文件缺着」）
     */
    public record Plan(String id,
                       Path javaExe,
                       List<String> argv,
                       List<String> notes,
                       Path nativesDir,
                       String player,
                       List<Runnable> cleanup) {

        /** 命令行给人看的样子（状态栏/控制台贴一行，便于用户报问题）。 */
        String commandLine() {
            return String.join(" ", argv);
        }
    }

    /** 起不来就抛这个：message 已经是给人看的一句话，界面直接显示。 */
    public static final class Blocked extends IOException {
        public Blocked(String message) {
            super(message);
            // 0.8.6：留一份给「导出诊断」——用户说不清报错时，这份才是现场证据
            lastBlockedReason = message == null ? "(无)" : message;
        }
    }

    /**
     * 准备一次启动。任何「现在还起不来」的情况都抛 {@link Blocked}，
     * **不抛别的异常**——界面只管把它显示出来。
     */
    public static Plan prepare(String id, String playerName) throws IOException {
        // 没传账号对象时按离线兜（给其他调用方留的简便入口）
        String p = (playerName == null || playerName.isBlank()) ? DEFAULT_PLAYER : playerName;
        return prepare(id, AccountStore.newOffline(p), null);
    }

    /**
     * 准备一次启动，顺带把缺的依赖库补下来。
     *
     * @param account 当前账号（离线 / 外置）；传 null 按离线默认玩家兜
     * @param onProgress 补库进度（「正在补依赖库…（12/61)」这种串）；传 null 就是不看进度
     */
    public static Plan prepare(String id, AccountStore.Account account, Consumer<String> onProgress)
            throws IOException {
        // 0.9.2 r4：数值进度旁路。准备一开始先推不确定态，进度条从打开对话框就脉动，
        // 不再定格；原有 onProgress（Consumer<String>）文案通道保持不变。
        // 下面这行是 r4 发布门禁的中文指纹：必须是字符串字面量（注释不进 class 常量池，
        // tools/build_092.py 会在 jpackage 产物 runtime/lib/modules 里逐字节搜这串）。
        System.out.println("r4 启动进度数值出口: 准备阶段数值帧开始（" + id + "）");
        LaunchProgress.emit("正在准备启动…", -1, -1, 0, -1);
        // 1.0.9：细粒度阶段文字——卡在准备阶段时，让用户看到具体卡在哪一步，而不是一片安静。
        // 复用现成的 onProgress 文案通道（ShellView 的 note 回调会推到加载层），不新增接口。
        if (onProgress != null) {
            onProgress.accept("正在读取版本配置…");
        }
        lastPrepareId = id == null ? "(未知)" : id;
        lastPlan = null;
        lastBlockedReason = "(无)";
        // HMCL ③：上次异常退出（解压后少文件 / 半截 jar）时，本局强制全量校验
        boolean forceFullVerify = abnormalExitPending();
        if (forceFullVerify) {
            System.out.println("[launch] 上次是异常退出，本局强制全量校验依赖库与资源");
        }
        String player = (account == null || account.name == null || account.name.isBlank())
                ? DEFAULT_PLAYER : account.name;
        // 凭据：外置 / 微软账号都用真实的 uuid / token / userType=mojang；
        // 离线账号仍走官方离线档（空 token + userType=legacy，见 ctx 注释）。
        String authUuid;
        String authToken;
        String authUserType;
        boolean online = account != null
                && (account.type == AccountStore.Type.LITTLESKIN
                    || account.type == AccountStore.Type.MICROSOFT);
        if (online) {
            authUuid = (account.uuid != null && !account.uuid.isBlank()) ? account.uuid : uuidOf(player);
            authToken = account.accessToken == null ? "" : account.accessToken;
            authUserType = "mojang";
        } else {
            authUuid = uuidOf(player);
            authToken = "";
            authUserType = "legacy";
        }
        Path gameDir = GameScanner.gameDirFor(id);

        // 2) 元信息（精简版要补；v0.8 解析 inheritsFrom，Forge/NeoForge 派生 profile 靠它并回父版本库）
        VersionJson.Meta meta = VersionJson.loadResolved(id);
        if (meta == null) {
            throw new Blocked("「" + id + "」的游戏信息没读到，可能这个版本没装完整。"
                    + "去「下载」页把这个版本重新装一次。");
        }
        // 0.9.1 重发：把「版本元信息 / 模组 / 四类内容」三路启动前校验一次全量汇总，
        // 任何一条都进同一份清单，全部显示给用户，不再只报第一条、也不再分多次拦。
        // 顺序：版本元信息（meta.problems）→ 模组（ModValidator）
        //      → 整合包/资源包/世界/光影（ContentValidator）。
        List<String> preflight = new ArrayList<>();
        if (onProgress != null) {
            onProgress.accept("正在校验游戏文件与模组…");
        }
        preflight.addAll(meta.problems());                 // 版本元信息
        if (onProgress != null) {
            onProgress.accept("正在校验模组…");
        }
        preflight.addAll(ModValidator.validate(id));       // 模组：加载器/前置/兼容/版本/损坏/哈希
        if (onProgress != null) {
            onProgress.accept("正在校验资源包 / 世界 / 整合包…");
        }
        preflight.addAll(ContentValidator.validate(id));   // 整合包 / 资源包 / 世界 / 光影
        if (!preflight.isEmpty()) {
            throw new Blocked("启动前检查发现 " + preflight.size()
                    + " 个问题，先处理好再启动：\n" + String.join("\n", preflight));
        }

        // 1) 客户端 jar：继承型版本（Forge/NeoForge 派生 profile）自身目录没有 jar——
        //    真正的客户端由父版本库 + FML 在运行时按坐标加载，这里不要求它。
        Path client = null;
        if (meta.inheritsFrom() == null) {
            client = firstJar(gameDir);
            if (client == null) {
                throw new Blocked("「" + id + "」这个版本还没有客户端 jar，先去下载页装一个");
            }
        }

        // 3) Java：版本对不上是新手最常见的「点了没反应」
        // 1.1.2：version.json 缺 javaVersion 时按版本 id 推明确主版本（1.20.5+/1.21 → 21、
        // 1.19/1.20.0~1.20.4 → 17），别再把 Java 25 误塞给要 Java 21 的整合包。
        // 1.3.0：MC 要求 + 已装 mod 自己声明的 Java 要求取最大值。
        // 宝可梦这类整合包的前置按 Java 21 字节码发布，而 MC 1.20.1 本身只要 17；
        // 只看 MC 就会用 Java 17 去起它们 → UnsupportedClassVersionError → 被误报成「Java 版本不对」。
        Integer mcMajor = meta.javaMajor() != null ? meta.javaMajor() : inferJavaMajor(id);
        Integer modMajor = scanModsMaxJavaMajor(modsDirFor(id));   // 只读小元数据，失败静默跳过
        Integer effectiveMajor = maxMajor(mcMajor, modMajor);
        // 1.1.1 P1：javaFor 逐个探测候选 JDK 要 440~612ms，是校验段最慢点，单给一句文字。
        if (onProgress != null) {
            onProgress.accept("正在选择合适的 Java…");
        }
        Path javaExe = javaFor(effectiveMajor);
        boolean triedDownload = false;
        if (javaExe == null && effectiveMajor != null && SettingsStore.autoDownloadEnabled()) {
            // 本机没有精确主版本的 Java（比如只有 25 但 Cobblemon 要 21）→ 自动装一份精确的
            triedDownload = true;
            javaExe = JavaInstaller.ensureJava(effectiveMajor, (stage, percent) -> {
                if (onProgress != null) {
                    onProgress.accept(stage + (percent >= 0 ? "（" + percent + "%）" : "…"));
                }
            });
        }
        if (javaExe == null && !triedDownload) {
            // 没尝试自动下载（用户关掉了 / 版本未知）：退回 1.1.1 老行为兜底
            javaExe = javaForLoose(effectiveMajor);
        }
        // 1.3.3：**自动下载失败 ≠ 这个包玩不了**。诊断包那个 1.20.1 + Fabric（59 个 mod、
        // fabric-api / sodium / fabricloader 全是 Java 17 级别）的整合包，在同一台机器上用
        // Java 27 完整跑通过——世界生成、存档、正常退出、零 Java 报错。它被挡在门外唯一的原因
        // 就是：本机没有精确的 Java 17，自动下载又失败，然后这里直接 throw。
        // 所以精确版拿不到时，宁可退一步用本机已有的、主版本不低于要求的 Java 起。
        // 老版本（effectiveMajor < 17）不进这条兜底：Java 25/27 跑 1.16 会崩在反射 / ASM 上，
        // 那条继续走原来的明确提示（fallbackTried 也保持 false，免得文案说瞎话）。
        boolean fallbackTried = false;
        if (javaExe == null && triedDownload && downloadFallbackAllowed(effectiveMajor)) {
            fallbackTried = true;
            javaExe = javaForDownloadFallback(effectiveMajor);
        }
        if (javaExe == null) {
            // 自动下载也失败、本机也没有更高的可用 Java：说清「什么失败 + 为什么 + 下一步」
            throw new Blocked(javaMissingReason(effectiveMajor, mcMajor, triedDownload,
                    fallbackTried));
        }

        // natives 落点跟着这个版本的 jvm 参数走：26.3 这类新版写死
        // ${natives_directory}/java，老版（1.20.1 及更早）就是 ${natives_directory}。
        // 一律解到 java 子目录的话，老版拿到的 -Djava.library.path 指向的却是空的 natives 根，
        // 游戏照样报 Failed to locate library（v0.4.9）
        Path nativesDir = gameDir.resolve("natives");
        Path nativeOut = nativeOutDir(nativesDir, meta.jvmArgs());
        List<String> notes = new ArrayList<>();
        List<Runnable> cleanup = new ArrayList<>();   // 游戏进程退出时要收尾的事（如本地皮肤服务）

        // 4) 缺的依赖库 / 本机那份 natives 包：能补就补。
        // HMCL、官方 launcher 装的老版本常常只夹了一个客户端 jar，libraries 里空空如也
        // （1.20.1 实测缺 61 个）。以前只能说一句「去补下载」，可启动器压根没这能力，
        // 用户读完只能自己去浏览器里找——v0.4.9 把它变成真的自己下
        // 1.1.1 P1：missing() 逐条核对本地库也有耗时，先报一句再跑，文字不再定格。
        if (onProgress != null) {
            onProgress.accept("正在核对本地依赖库…");
        }
        List<LibFetcher.Lib> absent = LibFetcher.missing(meta, forceFullVerify);
        if (onProgress != null) {
            onProgress.accept("正在检查依赖库…");
        }
        java.util.concurrent.atomic.AtomicLong libBytes =
                new java.util.concurrent.atomic.AtomicLong();
        if (!absent.isEmpty()) {
            if (onProgress != null) {
                onProgress.accept(LibFetcher.progressText(0, absent.size(), 0));
            }
            List<LibFetcher.Lib> failed = LibFetcher.fetch(absent, new LibFetcher.Listener() {
                @Override
                public void onProgress(int done, int total, long doneBytes, long bytesPerSecond) {
                    libBytes.set(doneBytes);
                    if (onProgress != null) {
                        onProgress.accept(LibFetcher.progressText(done, total, bytesPerSecond));
                    }
                }
            });
            if (!failed.isEmpty()) {
                throw new Blocked("「" + id + "」要补的 " + absent.size() + " 个运行文件里有 "
                        + failed.size() + " 个没下下来：" + LibFetcher.failedText(failed)
                        + "。多半是网络不稳定，等会儿再点一次启动；"
                        + "一直不行就去「下载」页重新装这个版本");
            }
            System.out.println("[launch] 「" + id + "」补了 " + (absent.size() - failed.size())
                    + " / " + absent.size() + " 个依赖库");
        }
        // 0.9.2 r4：补库完成，数值进度收在 1.0（旁路，不动原有 onProgress 文案通道）
        LaunchProgress.emit("资源就绪", absent.size(), absent.size(), libBytes.get(), 1.0);

        // 6) natives：老格式的库要解压出来（新格式的 lwjgl 从 jar 里自取，这里解压也无害）
        try {
            decompressNatives(meta.libraries(), nativeOut);
        } catch (IOException ex) {
            // 解压不出来就别放游戏起来：让它带着指向空目录的 -Djava.library.path 去跑，
            // 只会崩在「加载界面」——画面定在那儿，启动器还以为在跑，用户完全不知道为什么
            throw new Blocked("「" + id + "」的 Native 库（图形/音效那几个动态库）没解压出来："
                    + ex.getMessage() + "。删掉 " + nativesDir + " 整个目录再启动一次通常就好；"
                    + "还不行就去「下载」页把这个版本重新装一次");
        }

        // 7) classpath（继承型版本没有自身 jar，跳过；客户端由父版本库 + FML 提供）
        List<String> classpath = new ArrayList<>();
        if (client != null) {
            classpath.add(client.toString());
        }
        int missingLibs = addToClasspath(meta.libraries(), classpath);
        if (missingLibs > 0) {
            throw new Blocked("「" + id + "」还缺 " + missingLibs + " 个运行文件，"
                    + "去「下载」页把这个版本重新装一次");
        }

        // 7) assets：以前只「数缺多少」然后提醒一句，可从来不会真去下——磁盘上只有别的版本的
        // 资源索引，1.20.1 要的那个压根没有，于是 3598 个资源一个不在：主界面背景全黑、
        // 中文变方块（还报 Unable to load font 'minecraft:include/unifont'）、日志几百行
        // Missing sound，玩一会儿还崩（v0.4.9 用户实测）。所以这里改成**真补全量**。
        // 补完再 count 一遍：数出来的数就是「还差多少」的实话，别再拿缓存里的旧数说事。
        if (meta.assetIndexId() != null) {
            if (onProgress != null) {
                onProgress.accept("正在补资源索引…");
            }
            AssetFetcher.ensureIndex(meta.assetIndexId(), meta.assetIndexUrl());
            List<AssetFetcher.Obj> absentAssets = AssetFetcher.pickStartupEssentials(
                    AssetFetcher.missing(meta.assetIndexId(), forceFullVerify));
            if (onProgress != null) {
                onProgress.accept("正在检查资源文件…");
            }
            if (!absentAssets.isEmpty()) {
                if (onProgress != null) {
                    onProgress.accept(AssetFetcher.progressText(0, absentAssets.size(), 0));
                }
                long startedAt = System.currentTimeMillis();
                java.util.concurrent.atomic.AtomicLong assetBytes =
                        new java.util.concurrent.atomic.AtomicLong();
                List<AssetFetcher.Obj> failedAssets = AssetFetcher.fetch(absentAssets,
                        new AssetFetcher.Listener() {
                            @Override
                            public void onProgress(int done, int total, long doneBytes) {
                                assetBytes.set(doneBytes);
                                if (onProgress != null) {
                                    onProgress.accept(AssetFetcher.progressText(done, total, doneBytes));
                                }
                            }
                        });
                long tookMs = System.currentTimeMillis() - startedAt;
                // 补完就把「还缺多少」的缓存丢掉：不然接下来 countMissingAssets 拿的是
                // 补之前的数，界面会报「还有 2835 个资源没下载」——刚下完就说这话，纯瞎话
                forgetAssetCache();
                if (failedAssets.isEmpty()) {
                    System.out.println("[launch] 「" + id + "」补了 " + absentAssets.size()
                            + " 个资源（" + AssetFetcher.humanSize(assetBytes.get())
                            + "，用时 " + tookMs / 1000 + " 秒）");
                } else {
                    notes.add("有 " + failedAssets.size() + " 个资源没下下来（"
                            + AssetFetcher.failedText(failedAssets) + "），界面可能缺图或缺中文");
                }
            }
            int missingAssets = countMissingAssets(meta.assetIndexId());
            if (missingAssets > 0) {
                notes.add("还有 " + missingAssets + " 个资源文件没下载，个别贴图/音效会缺");
            }
        }

        // 8) 拼命令。新版 jvm 段自己带 -cp ${classpath}（已经在 argv 里了），
        // 别再补一次——同一个 -cp 写两遍，出问题时排错排到怀疑人生；
        // 老格式（没有 arguments 段）由上头那次兜住，两种格式都能跑
        List<String> argv = new ArrayList<>();
        argv.add(javaExe.toString());
        addHeapCap(argv);   // 见下：不传 -Xmx 时默认最大堆能吃满物理内存 1/4（封顶 8G），
                            // 低内存机器退出时整桌卡死，按物理内存给个保守上限

        // 外置账号：注入 authlib-injector，把游戏内的 Yggdrasil 调用重定向到 LittleSkin，
        // 这样才能进「认 LittleSkin 这套认证」的联机服（皮肤/披风也按 uuid 拉）。
        // 离线账号不走——纯离线本就用不到 authlib 联网校验，加了反而多一次 agent 加载。
        if (account != null && account.type == AccountStore.Type.LITTLESKIN) {
            try {
                argv.add(AuthlibInjector.javaAgentArg(onProgress));
                notes.add("已启用 authlib-injector：外置账号可进 LittleSkin 联机服（认 LittleSkin 认证的服务器）");
            } catch (IOException ex) {
                // 组件下不下来不该把整局游戏堵死：单机照常能玩，只是联机服进不去。
                // 说清楚原因，让用户联网重试即可，而不是报一个莫名其妙的「启动失败」。
                notes.add("authlib-injector 没准备好（" + ex.getMessage()
                        + "）：这一局只能单机，联机服进不去；联网后重启用外置账号即可加载联机组件");
            }
        }

        // 离线账号选了非默认皮肤：起一个本地皮肤服务 + 注入 authlib-injector，
        // 让游戏把离线账号当成「已认证」加载自定义皮肤（游戏内显示，别人看不到）。
        if (account != null && account.type == AccountStore.Type.OFFLINE
                && SkinStore.needsLocalSkin(account)) {
            try {
                byte[] skin = SkinStore.skinBytes(account);
                LocalSkinServer srv = new LocalSkinServer(
                        authUuid, player, skin, SkinStore.model(account));
                srv.start();
                argv.add(AuthlibInjector.javaAgentArg(srv.baseUrl(), onProgress));
                authUserType = "mojang";
                if (authToken == null || authToken.isBlank()) {
                    authToken = "gbl-offline-skin";
                }
                cleanup.add(srv::stop);
                notes.add("已为离线账号注入本地皮肤服务（游戏内显示自定义皮肤）");
            } catch (Exception ex) {
                notes.add("皮肤组件没准备好（" + (ex.getMessage() == null ? "原因未知" : ex.getMessage())
                        + "）：这一局先用默认史蒂夫皮肤，不影响进游戏。下一步：检查网络后重启启动器再试；"
                        + "如果是从朋友那儿传来的皮肤，可在皮肤管理里用「从朋友导入」重新导入皮肤礼包。");
            }
        }

        expandList(meta.jvmArgs(), argv, ctx(player, authUuid, authToken, authUserType, id, meta,
                nativesDir, String.join(java.io.File.pathSeparator, classpath)));
        // 0.9.9 r6：补齐现代 G1 垃圾回收参数，减少 GC 停顿造成的运行卡顿（对齐 HMCL 默认做法）。
        // 必须在主类名之前、且放在版本 jvmArgs 展开之后，才能检查并尊重版本自带的 GC 选择。
        addGcTuning(argv);
        if (!argv.contains("-cp")) {
            argv.add("-cp");
            argv.add(String.join(java.io.File.pathSeparator, classpath));
        }
        argv.add(mainClassOf(meta, gameDir));
        expandList(meta.gameArgs(), argv, ctx(player, authUuid, authToken, authUserType, id, meta,
                nativeOut, null));
        // 默认中文：命令行 --language 是给 1.7 及更早看的，1.8+ 认 options.txt 的 lang:，
        // 两头都写，老版本新版本都成中文（1.20.1 冷启动实测：窗口标题变中文）
        //
        // 尺寸只在命令行给：实测 1.20.1 在 options.txt 里写 overrideWidth/Height 也照样
        // 653×396（它启动时按自己的默认值走，退出时才把实际尺寸回写），
        // 所以别去改 options.txt 的尺寸——改了不起作用，还会被游戏覆盖（v0.4.9 实测）
        if (!argv.contains("--language")) {
            argv.add("--language");
            argv.add("zh_cn");
        }
        if (!argv.contains("--width")) {
            argv.add("--width");
            argv.add("1280");
        }
        if (!argv.contains("--height")) {
            argv.add("--height");
            argv.add("720");
        }
        tuneOptions(gameDir, notes);

        // 9) 兜底自检：只有**命令行里真的指定了 java.library.path** 的版本才验（26.3 这类新版
        //    自己把路径写死成 ${natives_directory}/java）。老版本从 classpath 自提取，这条不许拦。
        //    空目录 = 上次清空没解回来，放行就是「游戏崩在加载界面、启动器还以为在跑」。
        String libraryPath = libraryPathArg(argv);
        if (libraryPath != null) {
            Path libDir = Path.of(libraryPath);
            String problem = missingCoreNative(libDir);
            if (problem != null) {
                // 光看「目录非空」不够：缺 lwjgl 那颗时别的库照样解得出 9 个文件，
                // 游戏就是卡在 Failed to locate library: lwjgl.dll 崩在加载界面
                throw new Blocked("「" + id + "」的 Native 库目录 " + libDir + "：" + problem
                        + "。删掉 " + nativesDir + " 整个目录再启动一次通常就好");
            }
        }

        Plan plan = new Plan(id, javaExe, List.copyOf(argv), List.copyOf(notes), nativesDir,
                player, List.copyOf(cleanup));
        lastPlan = plan;
        // 校验真的走完一轮才清标志：中途 Blocked 时下一次启动仍会强制全量校验
        if (forceFullVerify) {
            clearAbnormalExitFlag();
        }
        return plan;
    }

    /**
     * 给游戏 JVM 一个堆上限，免得它把整机内存吃光、退出时整桌卡死。
     *
     * <p>不传 -Xmx 时 HotSpot 默认最大堆 = 物理内存 1/4（封顶 8G）。低内存机器上这足够
     * 让操作系统开始 swap，MC 退出（JVM 释放大块堆 + 驱动收尾）时整桌鼠标都动不了——
     * 用户报的「关闭游戏后卡死」大半是这个。默认上限优先取用户设置 {@code gameMemoryMb()}（MB）；
     * 设置无效（<=0）时按物理内存给个保守上限；版本自带 -Xmx 不覆盖。
     */
    private static void addHeapCap(List<String> argv) {
        // 1) 版本 / 用户显式的 -Xmx 优先级最高：argv 里已经带了就绝对不覆盖
        for (String a : argv) {
            if (a.startsWith("-Xmx")) {
                return;                     // 版本 jvmArgs 自带了，别动
            }
        }

        // 2) 设置项里的堆上限（MB）。> 0 时作为默认上限，单位是兆（-Xmx<mb>m）
        int mb = SettingsStore.get().gameMemoryMb();
        if (mb > 0) {
            argv.add("-Xmx" + mb + "m");    // 例如 2048 → -Xmx2048m
            return;
        }

        // 3) 兜底（设置项无效 / <=0）：按物理内存给保守上限，跟旧逻辑一致
        long total = totalPhysicalMemoryBytes();
        long gb = total <= 0 ? 0 : total / (1024L * 1024 * 1024);
        long cap;
        if (gb <= 0) cap = 4;               // 读不到内存就给个稳妥默认（26.x 也跑得动）
        else if (gb <= 4) cap = 2;
        else if (gb <= 8) cap = 3;
        else if (gb <= 16) cap = 4;
        else cap = 6;                       // 再大也别给满，留点给系统和启动器
        argv.add("-Xmx" + cap + "G");
    }

    /**
     * 给游戏 JVM 补一组现代、稳健的 GC 参数，专门压低「玩着玩着卡一下」的停顿（0.9.9 r6）。
     *
     * <p>真因：本类此前只通过 {@link #addHeapCap} 给了 {@code -Xmx}，从不传任何 GC 参数，
     * 于是游戏只能用 JVM 默认收集器。跑 1.16 及更早版本时 {@link #inferJavaMajor} 会把 Java
     * 锁到 8，而 Java 8 默认 GC 是 Parallel GC（吞吐优先，一次 full GC 停顿可达数百毫秒），
     * 玩家直观感受就是「别人启动的 MC 比 GBL 流畅」；跑新版本用的 Java 17/21 虽已默认 G1，
     * 但没有停顿目标约束，偶发长停顿依旧。HMCL 默认就显式开 G1GC 并把停顿目标压到 50ms，
     * 这里对齐它的做法。
     *
     * <p>稳健性：这组参数在 Java 8 / 11 / 17 / 21 上全部受支持，不含任何在新 Java 上已被
     * 移除的选项（如 {@code UseConcMarkSweepGC}，Java 14 起删除），也不含
     * {@code AlwaysPreTouch} 这类会拖慢启动的参数——只解决「堆回收停顿」，不牺牲启动速度。
     * 若版本 json 的 jvmArgs 已显式选过收集器（整合包作者自带），一个都不加，完全尊重原设置。
     */
    private static void addGcTuning(List<String> argv) {
        // 版本已显式指定收集器就不插手：G1 / Parallel / Serial / ZGC / Shenandoah 都算
        for (String a : argv) {
            if (a.startsWith("-XX:+UseG1GC") || a.startsWith("-XX:-UseG1GC")
                    || a.startsWith("-XX:+UseParallelGC") || a.startsWith("-XX:+UseSerialGC")
                    || a.startsWith("-XX:+UseConcMarkSweepGC") || a.startsWith("-XX:+UseZGC")
                    || a.startsWith("-XX:+UseShenandoahGC")) {
                return;
            }
        }
        argv.add("-XX:+UseG1GC");                           // 明确启用 G1，取代 Java 8 的 Parallel GC
        argv.add("-XX:MaxGCPauseMillis=50");                // 单次 GC 停顿目标压到 50ms，帧率更平稳
        argv.add("-XX:+DisableExplicitGC");                 // 忽略 Mod 乱调 System.gc() 引发的瞬间卡顿
        argv.add("-XX:G1ReservePercent=20");                // 预留 20% 堆，减少转移失败带来的长停顿
        argv.add("-XX:InitiatingHeapOccupancyPercent=40");  // 堆占用 40% 就启动并发标记，别等堆满才 GC
    }

    /**
     * 物理内存总量（字节）。裁剪过的启动器 runtime 可能没带 {@code jdk.management}，
     * 所以用纯反射探；探不到（类不在 / 抛任何异常）就回 0，让调用方走兜底上限。
     * 注意：绝不能写 {@code java.lang.management.ManagementFactory} 这种直接引用——
     * 那个类不在裁剪 runtime 里时会让整个 {@code GameLauncher} 类加载失败。
     */
    private static long totalPhysicalMemoryBytes() {
        try {
            Class<?> beanClass = Class.forName("com.sun.management.OperatingSystemMXBean");
            Class<?> mfClass = Class.forName("java.lang.management.ManagementFactory");
            Object bean = mfClass.getMethod("getOperatingSystemMXBean").invoke(null);
            if (beanClass.isInstance(bean)) {
                Object v = beanClass.getMethod("getTotalPhysicalMemorySize").invoke(bean);
                if (v instanceof Number) {
                    return ((Number) v).longValue();
                }
            }
        } catch (Throwable ignored) {
            // 没 jdk.management / 反射失败都静默回退
        }
        return 0;
    }

    /**
     * 把图形后端锁回 OpenGL、语言锁中文、并标「上次干净退出」。
     *
     * <p>这是打破 Mojang「意外关机恢复」翻转图形后端的关键。游戏崩（startedCleanly:false）时，
     * Minecraft 会在下次启动把 {@code preferredGraphicsBackend} 翻回 Vulkan（Minecraft.class 里
     * "Detected unexpected shutdown during last game startup: resetting preferred graphics API" 即是），
     * 在这台混合显卡（NVIDIA RTX 5060 + Intel Arc）+ 旧驱动上 Vulkan 就是全黑 / 卡主界面 /
     * 退出冻死（息屏才恢复）的来源。26.x 没有命令行或系统属性级别的强制开关（client jar 里
     * grep 不到 forcedGraphicsApi），所以只能钉 options.txt，而且必须在「游戏进程死后」由启动器
     * 再钉一次（见 {@link #pinGraphicsOptions}），覆盖游戏自己回写的内容。
     */
    private static void pinOptions(Path gameDir) throws IOException {
        Path options = gameDir.resolve("options.txt");
        List<String> lines = Files.isRegularFile(options)
                ? new ArrayList<>(Files.readAllLines(options, StandardCharsets.UTF_8))
                : new ArrayList<>();
        setOption(lines, "lang:", "lang:zh_cn");
        setOption(lines, "preferredGraphicsBackend:", "preferredGraphicsBackend:\"opengl\"");
        setOption(lines, "startedCleanly:", "startedCleanly:true");
        Files.createDirectories(gameDir);
        Files.write(options, lines, StandardCharsets.UTF_8);
    }

    /** 游戏会话真正结束后（无论正常退出还是被强杀）由启动器调用：把 options.txt 重新钉成
     *  OpenGL + 干净退出，确保下次启动前文件一定是 opengl + startedCleanly:true，
     *  Mojang 不会触发「意外关机恢复」把后端翻回 Vulkan。 */
    public static void pinGraphicsOptions() {
        try {
            pinOptions(Platform.minecraftDir());
        } catch (IOException | RuntimeException ex) {
            System.err.println("[launch] 钉 options.txt 失败（图形后端可能回落 Vulkan）: " + ex);
        }
    }

    /**
     * 一键采集诊断日志：把 .minecraft 下的 {@code logs}、{@code crash-reports}，
     * 外加一份**始终存在**的 {@code launch-info.txt} 打成 zip，放到 GBL 根目录。
     *
     * <p>0.8.6：以前两个目录都空就返回 null，UI 弹「没有可导出的日志」。而「启动前就被
     * Blocked」（找不到 Java / 元信息不齐）时游戏进程根本没起来，logs 必然是空的，
     * 用户点「导出诊断」什么也拿不到 —— 这正是「用户未提供报错」的来源。现在
     * {@code launch-info.txt} 无论如何都打包，方法**不再返回 null**。
     */
    public static Path exportDiagnostics() throws IOException {
        Path mc = Platform.minecraftDir();
        Path outDir = mc.getParent(); // GBL 根目录
        if (outDir == null || !Files.isDirectory(outDir)) {
            outDir = Paths.get("").toAbsolutePath();
        }
        Files.createDirectories(outDir);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path zip = outDir.resolve("GBL-diagnostics-" + ts + ".zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            addDirToZip(zos, mc.resolve("logs"), mc);
            addDirToZip(zos, mc.resolve("crash-reports"), mc);
            zos.putNextEntry(new ZipEntry("launch-info.txt"));
            zos.write(buildLaunchInfo().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return zip;
    }

    /**
     * 组装 {@code launch-info.txt}：启动命令（脱敏）、classpath、Java 路径/版本、环境、
     * 元信息完整度、缺库清单、最后一次 Blocked 原因。任何一项读不到都写「读不到」，
     * 绝不让导出本身失败。
     */
    private static String buildLaunchInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("GBL 启动诊断\n");
        sb.append("生成时间: ").append(LocalDateTime.now()).append('\n');
        sb.append("版本 id: ").append(lastPrepareId).append('\n');
        sb.append("OS: ").append(System.getProperty("os.name", "?")).append(' ')
                .append(System.getProperty("os.version", "?")).append(" / ")
                .append(System.getProperty("os.arch", "?")).append('\n');
        sb.append("Java(启动器自身): ").append(System.getProperty("java.version", "?"))
                .append(" @ ").append(System.getProperty("java.home", "?")).append('\n');
        long total = totalPhysicalMemoryBytes();
        sb.append("物理内存: ").append(total > 0 ? (total / (1024 * 1024)) + " MB" : "读不到")
                .append(" / JVM 最大堆 ").append(Runtime.getRuntime().maxMemory() / (1024 * 1024))
                .append(" MB\n");
        sb.append("游戏目录: ").append(Platform.minecraftDir().toAbsolutePath()).append('\n');
        sb.append("便携模式: ").append(portableMode() ? "是" : "否").append('\n');
        sb.append("自带完整 JRE (jre/bin/java): ").append(bundledJrePresent() ? "存在" : "缺失")
                .append('\n');
        sb.append("上次 Blocked 原因: ").append(lastBlockedReason).append('\n');
        sb.append("异常退出标志: ").append(abnormalExitPending() ? "存在" : "无").append('\n');

        Plan plan = lastPlan;
        if (plan == null) {
            sb.append("javaExe: (本次 prepare 未完成，未生成启动命令)\n");
            sb.append("启动命令: (同上)\n");
            sb.append("classpath: (同上)\n");
        } else {
            sb.append("javaExe: ").append(plan.javaExe()).append('\n');
            sb.append("启动命令(已脱敏):\n  ")
                    .append(String.join(" ", maskedArgv(plan.argv()))).append('\n');
            sb.append("classpath:\n  ").append(classpathOf(plan.argv())).append('\n');
        }

        try {
            Map<String, Object> local = VersionJson.local(lastPrepareId);
            boolean thin = local == null
                    || !(local.get("libraries") instanceof List)
                    || !(local.get("assetIndex") instanceof Map);
            sb.append("本地 version.json: ")
                    .append(local == null ? "读不到" : (thin ? "精简版" : "完整版"))
                    .append(" (").append(GameScanner.versionsDir().resolve(lastPrepareId)
                            .resolve("version.json")).append(")\n");
            VersionJson.Meta meta = VersionJson.load(lastPrepareId);
            sb.append("需要 Java 主版本: ")
                    .append(meta == null || meta.javaMajor() == null ? "读不到" : meta.javaMajor())
                    .append('\n');
            List<LibFetcher.Lib> missingLibs = LibFetcher.missing(meta, true);
            sb.append("依赖库缺失/损坏数: ").append(missingLibs.size()).append('\n');
            int shown = 0;
            for (LibFetcher.Lib lib : missingLibs) {
                if (shown++ >= 30) {
                    sb.append("  ... 其余省略\n");
                    break;
                }
                sb.append("  ").append(lib.path()).append('\n');
            }
        } catch (IOException | RuntimeException ex) {
            sb.append("元信息/缺库清单读不到: ").append(ex).append('\n');
        }
        return sb.toString();
    }

    /** 命令行脱敏：只遮 {@code --accessToken} 的值，其余原样保留便于排错。 */
    private static List<String> maskedArgv(List<String> argv) {
        List<String> out = new ArrayList<>(argv.size());
        boolean nextIsToken = false;
        for (String arg : argv) {
            if (nextIsToken) {
                out.add("***");
                nextIsToken = false;
                continue;
            }
            if ("--accessToken".equals(arg)) {
                out.add(arg);
                nextIsToken = true;
            } else if (arg.startsWith("--accessToken=")) {
                out.add("--accessToken=***");
            } else {
                out.add(arg);
            }
        }
        return out;
    }

    /** 从命令行里把 classpath 单独摘出来记一份（新版 jvmArgs 自带 -cp ${classpath}）。 */
    private static String classpathOf(List<String> argv) {
        for (int i = 0; i + 1 < argv.size(); i++) {
            String flag = argv.get(i);
            if ("-cp".equals(flag) || "-classpath".equals(flag) || "--classpath".equals(flag)) {
                return argv.get(i + 1);
            }
        }
        for (String arg : argv) {
            if (arg.contains(java.io.File.pathSeparator) && arg.contains(".jar")) {
                return arg;
            }
        }
        return "(命令行里没有显式 classpath)";
    }

    /** 把 dir 整个目录树加进 zip（以 base 为相对根），返回是否加进了至少一个文件。 */
    private static boolean addDirToZip(ZipOutputStream zos, Path dir, Path base) throws IOException {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            return false;
        }
        boolean[] any = {false};
        Files.walk(dir).forEach(p -> {
            if (!Files.isRegularFile(p)) {
                return;
            }
            try {
                String name = base.relativize(p).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(name));
                Files.copy(p, zos);
                zos.closeEntry();
                any[0] = true;
            } catch (IOException ex) {
                throw new RuntimeException("诊断打包失败: " + p, ex);
            }
        });
        return any[0];
    }

    private static void tuneOptions(Path gameDir, List<String> notes) {
        try {
            pinOptions(gameDir);
            // 0.9.9：options.txt 写进 versions/<id>/，但 game_directory 指向 Platform.minecraftDir()，
            // 当前这一局读不到刚写的值，实际是下次启动才生效——别再说「已锁定」，改成实话。
            notes.add("图形后端设置将在下次启动游戏时生效");
        } catch (IOException | RuntimeException ex) {
            // 这些只是偏好，写不进去不该拦住游戏
            System.err.println("[launch] 写 options.txt 失败: " + ex);
            notes.add("options.txt 写不进去，游戏语言/图形后端可能不生效");
        }
    }

    /**
     * 把以 {@code prefix} 开头的行设成 {@code value}（没有就插到最前）。
     * 返回是否真的改动过——用来决定要不要往控制台报一句。
     */
    private static boolean setOption(List<String> lines, String prefix, String value) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) {
                if (lines.get(i).equals(value)) {
                    return false;
                }
                lines.set(i, value);
                return true;
            }
        }
        lines.add(0, value);
        return true;
    }

    /**
     * 这个版本的 {@code -Djava.library.path} 写的是 {@code ${natives_directory}/java}，
     * 还是直接 {@code ${natives_directory}}？
     *
     * <p>26.3 之后新版 jvm 段把落点写死成子目录 {@code java}，老版（1.20.1 及更早）没有这段，
     * 值就是版本目录下的 {@code natives} 本身。解压跟随它，游戏才找得到 .dll。
     * 判据取**没展开的**占位符写法——展开后两个长得一样，就分不出来了。
     */
    private static boolean nestedNatives(List<Object> jvmArgs) {
        if (jvmArgs == null) {
            return false;
        }
        for (Object raw : jvmArgs) {
            String text = String.valueOf(raw);
            if (text.contains("${natives_directory}/java")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 动态库该解压到哪：{@code natives/java}（新版写死子目录）还是 {@code natives} 本身（老版）。
     *
     * <p>返回之前先夹一道：落点**必须是这个版本 natives 目录或其子目录**。
     * 老版落点就是 natives 根，落点认错了清旧文件时一路往上删，
     * 能把整个 {@code versions/<id>/}（客户端 jar 在内）删干净——v0.4.9 自己踩的，
     * 一台机器的 1.20.1 就是这么空掉的。夹不住就不许往下走。
     */
    private static Path nativeOutDir(Path nativesDir, List<Object> jvmArgs) {
        Path out = nestedNatives(jvmArgs) ? nativesDir.resolve("java") : nativesDir;
        return out.startsWith(nativesDir) ? out : nativesDir.resolve("java");
    }

    /** 命令行里 {@code -Djava.library.path=…} 的值；没设就 null。 */
    private static String libraryPathArg(List<String> argv) {
        for (String a : argv) {
            if (a.startsWith("-Djava.library.path=")) {
                return a.substring("-Djava.library.path=".length());
            }
        }
        return null;
    }

    /**
     * 这个 native 目录是不是「解歪了」：一个动态库都没有、或者解到一半缺了 lwjgl 核心那颗。
     * 返回 null 表示能用，否则返回一句人话。
     */
    private static String missingCoreNative(Path dir) {
        if (!Files.isDirectory(dir)) {
            return "路径都不存在";
        }
        boolean any = false;
        boolean lwjgl = false;
        try (var walk = Files.list(dir)) {
            for (java.util.Iterator<Path> it = walk.iterator(); it.hasNext();) {
                String name = it.next().getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(nativeSuffix())) {
                    continue;
                }
                any = true;
                if (name.startsWith("lwjgl")) {
                    lwjgl = true;
                }
            }
        } catch (IOException ex) {
            return "读不了这个目录（" + ex + "）";
        }
        if (!any) {
            return "一个 " + nativeSuffix() + " 都没有";
        }
        // 只认 LWJGL 核心这一颗：别的库的 natives（lwjgl_opengl / lwjgl_stb …）补不上它，
        // 少了它游戏就报 Failed to locate library: lwjgl.dll 崩在加载界面。
        // 判据必须写全名——用 startsWith("lwjgl") 会被 lwjgl_opengl.dll 骗过去。
        Path core = dir.resolve(coreNativeName());
        if (!Files.isRegularFile(core)) {
            return "缺 " + coreNativeName() + "（别的 dll 都在，就它没解出来）";
        }
        // v0.4.10：光「存在」还不够——上一局退出卡死、息屏强杀会把 lwjgl.dll 留成
        // 被占用锁死/写一半的坏文件，文件在但加载必炸 Failed to locate library，
        // 直接放行游戏就黑屏/卡主界面。这里按「读得动才算好文件」再验一道。
        String bad = badNative(core);
        if (bad != null) {
            return coreNativeName() + bad;
        }
        return null;
    }

    /**
     * 这一版的 native 目录是不是「已经解好且完整可读」——满足才允许跳过重解。
     *
     * <p>v0.4.10 新增：上一局「退出卡死、息屏强杀」会把 lwjgl.dll 留成坏文件（0 字节 /
     * 写一半 / 被占用锁死），{@code Files.isRegularFile} 照样返回 true，跳过重解后游戏
     * 一加载就 Failed to locate library。所以这里不只查「在不在」，还验核心那颗能真正打开读。
     */
    private static boolean nativeOk(Path dir) {
        Path core = dir.resolve(coreNativeName());
        return Files.isRegularFile(core) && badNative(core) == null;
    }

    /**
     * 判断核心原生库是不是「看着在、其实读不了」的坏文件。返回 null 表示能用，
     * 否则一句人话说明坏在哪。只做**不落盘**的探测：打开读一点字节，失败就是被锁死/写一半。
     */
    private static String badNative(Path core) {
        try {
            if (Files.size(core) == 0) {
                return "是个 0 字节空壳（上一局退出没写完整）";
            }
            // 真打开读一个字节：被别的进程独占锁死、或写到一半被强杀，这里会抛
            try (var in = Files.newInputStream(core)) {
                in.read();
            }
            return null;
        } catch (IOException ex) {
            return "读不动（多半被上一局没退干净的游戏占着：" + ex.getMessage() + "）";
        }
    }

    /** MC 少谁都跑不起来的那一颗原生库：LWJGL 核心，文件名跟系统走。 */
    private static String coreNativeName() {
        String suffix = nativeSuffix();
        return (".dll".equals(suffix) ? "lwjgl" : "liblwjgl") + suffix;
    }

    // ---------------------------------------------------------------- 拉进程

    /** 拉起游戏进程。{@code onLine} 每一行在后台线程被调用，UI 那边自己切线程。 */
    public static Session start(Plan plan, Consumer<String> onLine) throws IOException {
        return new Session(plan, onLine);
    }

    /**
     * 一个跑着的游戏进程。输出行攒在 {@link #drain()} 里，由 UI 定时取——
     * **不让后台线程直接碰 UI 控件**（JavaFX 单线程，碰了就是随机的花屏）。
     */
    public static final class Session {
        private final Process process;
        private final List<String> tail = new ArrayList<>();
        private final List<String> fresh = new ArrayList<>();
        private volatile long lastLineAt = System.currentTimeMillis();
        /** 启动阶段就已经炸了（主线程崩、JVM 却不一定退）：见 {@link #startupCrash()}。 */
        private volatile boolean startupCrash;
        private final List<Runnable> cleanup;
        private final AtomicBoolean cleaned = new AtomicBoolean();
        /** 用户主动点了「停止游戏」：进程被 destroy 的非 0 退出码不该算异常退出。 */
        private volatile boolean stoppedByUser;

        private Session(Plan plan, Consumer<String> onLine) throws IOException {
            this.cleanup = plan.cleanup();
            lastPlan = plan;
            // 0.8.6：拉进程前把完整命令（脱敏）与 classpath 落进日志，
            // 这两样以前只在内存里的 Plan.argv() 里，出问题时无从取证。
            System.out.println("[launch] cmd=" + String.join(" ", maskedArgv(plan.argv())));
            System.out.println("[launch] classpath=" + classpathOf(plan.argv()));
            System.out.println("[launch] javaExe=" + plan.javaExe());
            process = new ProcessBuilder(plan.argv())
                    .directory(Platform.minecraftDir().toFile())
                    .redirectErrorStream(true)
                    .start();
            // 0.9.2 r4：拉起 JVM 后到进程退出前，持续以不确定态心跳推「正在启动…」。
            // 游戏启动没有可量化的文件进度，进度条应显示脉动而不是定格；进程退出后
            // while 自然结束，daemon 线程不拦 JVM 退出。emit 内部在 sink 为 null 时零开销。
            Thread beat = new Thread(() -> {
                while (process.isAlive()) {
                    LaunchProgress.emit("正在启动…", -1, -1, 0, -1);
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "gbl-launch-beat");
            beat.setDaemon(true);
            beat.start();
            Thread reader = new Thread(() -> {
                try (BufferedReader in = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (!startupCrash) {
                            String lower = line.toLowerCase(Locale.ROOT);
                            for (String mark : STARTUP_CRASH_MARKS) {
                                if (lower.contains(mark)) {
                                    startupCrash = true;
                                    break;
                                }
                            }
                        }
                        synchronized (tail) {
                            tail.add(line);
                            if (tail.size() > TAIL_LIMIT) {
                                tail.remove(0);
                            }
                            fresh.add(line);
                            lastLineAt = System.currentTimeMillis();
                        }
                        if (onLine != null) {
                            onLine.accept(line);
                        }
                    }
                } catch (IOException ignored) {
                    // 进程被杀时管道断掉，这是正常收场，不用吭声
                }
            }, "gbl-game-output");
            reader.setDaemon(true);
            reader.start();
            // 游戏进程自然退出后收尾（关本地皮肤服务等）；手动 stop 的收尾在 reaper 里也跑一遍
            Thread watch = new Thread(() -> {
                try {
                    process.waitFor();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                recordExit();
                runCleanup();
                // 0.9.2 r4：流程收尾发 1.0 帧，进度条不停在脉动 / 中间值
                LaunchProgress.emit("游戏已退出", -1, -1, 0, 1.0);
            }, "gbl-game-watchdog");
            watch.setDaemon(true);
            watch.start();
        }

        /**
         * 进程退出后记「上次异常退出」标志（HMCL ③）：正常退出清掉，异常退出留下，
         * 下次 {@link #prepare} 会强制全量校验依赖库与资源。用户主动停止不算异常。
         * 只碰一个标记文件，不起新线程、不加锁，读的时候只看文件在不在。
         */
        private void recordExit() {
            if (stoppedByUser) {
                clearAbnormalExitFlag();
                return;
            }
            int code;
            try {
                code = process.exitValue();
            } catch (RuntimeException ex) {
                code = -1;
            }
            boolean noOutput;
            synchronized (tail) {
                noOutput = tail.isEmpty();
            }
            if (code != 0 || startupCrash || noOutput) {
                markAbnormalExit();
            } else {
                clearAbnormalExitFlag();
            }
        }

        /** 游戏进程退出后收一次尾（关本地皮肤服务等），只跑一次。 */
        private void runCleanup() {
            if (cleaned.compareAndSet(false, true)) {
                for (Runnable r : cleanup) {
                    try {
                        r.run();
                    } catch (Throwable ignore) {
                    }
                }
            }
        }

        /** 取走自上次调用以来新到的行（UI 按固定间隔来取）。 */
        public List<String> drain() {
            List<String> out;
            synchronized (tail) {
                out = new ArrayList<>(fresh);
                fresh.clear();
            }
            return out;
        }

        /** 最近若干行（给 {@link #explain} 用，也是控制台订阅之外的一份备份）。 */
        public List<String> tailLines() {
            synchronized (tail) {
                return List.copyOf(tail);
            }
        }

        public boolean running() {
            return process.isAlive();
        }

        /**
         * 距最后一行游戏输出过了多少毫秒。
         *
         * <p>MC 主线程崩了以后 JVM 常常不退出（下载线程之类的还活着），
         * 于是进程一直 alive、日志再也不来一行——界面看着就是「卡在加载界面」。
         * 光看 alive 判断不出来，得看日志停没停。
         */
        public long idleMillis() {
            return System.currentTimeMillis() - lastLineAt;
        }

        /**
         * 启动阶段就已经崩了（日志里出现过 {@link #STARTUP_CRASH_MARKS} 之一）。
         *
         * <p>光看进程活不活判断不出来——MC 崩了以后 JVM 往往还挂着，
         * 得看它到底在不在说话。这个标志让界面不用等满 45 秒就能下结论。
         */
        public boolean startupCrash() {
            return startupCrash;
        }

        /** 退出码；还在跑返回 -1（{@link Process#exitValue()} 还没就绪的写法）。 */
        public int exitCode() {
            return process.isAlive() ? -1 : process.exitValue();
        }

        /** 已经结束就返回；超时（默认 5 秒）还没完就原地返回，别把 UI 堵住。 */
        public int awaitExit(long seconds) {
            try {
                return process.waitFor(seconds, TimeUnit.SECONDS) ? process.exitValue() : -1;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        /**
         * 结束游戏（「退出游戏」/「停止游戏」按钮）。
         *
         * <p><b>这一句绝不能阻塞调用它的线程。</b>老实现在这里 {@code process.waitFor(3s)}，
         * 而调用方（v0.4.9 的 {@code ShellView.stopGame}）跑在 JavaFX 主线程上——
         * 游戏卡死时它往往正卡在驱动的原生调用里，TerminateProcess 要等那个调用返回才生效，
         * 于是 UI 线程白等最多 3 秒（更久），窗口不重绘，连带桌面一起顿住，
         * 用户报的「退出游戏电脑卡死」有一半是这么来的。
         *
         * <p>现在这里只发一次 {@code destroy()}，真正等它退的是下面这条 daemon 收尸线程；
         * 界面催进度走 {@link #running()} 轮询就够了。
         */
        public void stop() {
            if (!process.isAlive()) {
                return;
            }
            stoppedByUser = true;
            process.destroy();
            Thread reaper = new Thread(() -> {
                try {
                    if (!process.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        try {
                            process.waitFor(3, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    runCleanup();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }, "gbl-game-reaper");
            reaper.setDaemon(true);
            reaper.start();
        }

        /** 不等收尾、直接把进程拍死（界面上明确的「强制结束」用这个）。 */
        public void killNow() {
            stoppedByUser = true;
            process.destroyForcibly();
        }
    }

    // ---------------------------------------------------------- 异常退出标志（HMCL ③）

    /** 标志文件在 = 上次是异常退出，下次启动强制全量校验。 */
    private static boolean abnormalExitPending() {
        return Files.isRegularFile(ABNORMAL_EXIT_FLAG);
    }

    private static void markAbnormalExit() {
        try {
            Path parent = ABNORMAL_EXIT_FLAG.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(ABNORMAL_EXIT_FLAG, LocalDateTime.now().toString(),
                    StandardCharsets.UTF_8);
            System.out.println("[launch] 记下异常退出标志：" + ABNORMAL_EXIT_FLAG);
        } catch (IOException | RuntimeException ex) {
            System.err.println("[launch] 异常退出标志写不了（不影响本局）: " + ex);
        }
    }

    private static void clearAbnormalExitFlag() {
        try {
            Files.deleteIfExists(ABNORMAL_EXIT_FLAG);
        } catch (IOException | RuntimeException ex) {
            System.err.println("[launch] 异常退出标志删不掉: " + ex);
        }
    }

    /**
     * 把「没起来」翻成人话；起来了（或原因不明）返回 null，界面按「异常退出」显示。
     *
     * <p>退出码 0 也可能是**启动就跑了**：新版 MC 的 bootstrap 会 fork 第二个 JVM，
     * 这时候单看退出码会误报「正常退出」，所以 additionally 看进程是不是还活着。
     */
    public static String explain(Session session) {
        int code = session.exitCode();
        List<String> lines = session.tailLines();
        String last = lines.isEmpty() ? "" : lines.get(lines.size() - 1);
        String crash = newestCrashSummary();

        // 关键词 → 人话：按「最像的原因」从上往下匹配
        String reason = keywordReason(lines);
        if (reason != null) {
            return reason;
        }
        if (code == 0) {
            // 玩完退出就是 0，别把「正常关游戏」报成异常——用户会觉得启动器在乱说
            return last.isBlank() ? "游戏进程刚起来就退出了（退出码 0）。多半是启动参数不对，"
                    + "看控制台最后几行；也可以去 .minecraft/crash-reports 找报告" : null;
        }
        // 0.9.7：认不出具体原因时不再甩一句「异常退出 + 六行英文日志」，
        // 交给 LaunchDiagnosis 按「哪里错 / 为什么 / 下一步做什么」写人话；
        // 原始日志仍然附在后面，方便用户反馈时直接复制。
        LaunchDiagnosis.Report report = LaunchDiagnosis.explain(code, lines, lastPrepareId);
        StringBuilder sb = new StringBuilder(report.title());
        sb.append('\n').append(report.advice());
        if (!lines.isEmpty()) {
            sb.append("\n\n（技术细节）最后几行：\n");
            for (String line : lines.subList(Math.max(0, lines.size() - 6), lines.size())) {
                sb.append(line).append('\n');
            }
        }
        if (crash != null) {
            sb.append("\n崩溃报告：").append(crash);
        }
        return sb.toString();
    }

    /**
     * 1.0.9：带「本局游戏窗口是否真的起来过」的补充判定。
     *
     * <p>旧的单参 {@link #explain(Session)} 一个字不动，避免影响其它调用点；这里只在它
     * 给不出原因、退出码为 0、且 UI 从未收到「Reloading ResourceManager / OpenAL initialized」
     * 时补一句实话——进程几秒内就退出的失败过去连红色原因框都没有（0.9.2 r4 软约定靠
     * ratio=1.0 文案，根本没人消费）。
     */
    public static String explain(Session session, boolean gameStarted) {
        String reason = explain(session);
        if (reason != null || session == null || gameStarted || session.exitCode() != 0) {
            return reason;
        }
        String joined = String.join(" ", session.tailLines()).toLowerCase(Locale.ROOT);
        if (joined.contains("reloading resourcemanager") || joined.contains("openal initialized")) {
            return reason;   // 日志证明窗口起来过，那就是正常退出
        }
        return "游戏进程很快就退出了（退出码 0），日志里也没有游戏窗口启动的迹象。"
                + "多半是启动参数不对、这个版本选的 Java 不匹配，或客户端文件不完整。"
                + "先看控制台最后几行；也可以点「导出诊断」保存日志再重试。";
    }

    /** 从输出里认出最常见的一类启动失败，直接给结论，不让用户自己去翻。 */
    private static String keywordReason(List<String> lines) {
        String joined = String.join("\n", lines).toLowerCase(Locale.ROOT);
        if (joined.contains("could not find or load main class")) {
            return "Java 找不到主类：这个版本的客户端 jar 不完整，重新下载一次客户端再试";
        }
        if (joined.contains("unsupported class file major version")) {
            return "Java 版本不对：整合包里的模组是用更高版本的 Java 编译的，当前 Java 跑不了它。"
                    + "请确认整合包模组要求的 Java 版本，GBL 会在启动时自动选择（本机缺版本时会自动下载）。";
        }
        if (joined.contains("could not reserve space for object heap")
                || joined.contains("there is insufficient memory")) {
            return "内存不够：给游戏分配的内存撞到机器上限了，去设置里把内存分配调小一点";
        }
        if (joined.contains("unable to create the java virtual machine")
                || joined.contains("error occurred during initialization of vm")) {
            return "Java 起不来（JVM 初始化失败）：多半是内存分配参数超出机器能给的，"
                    + "去设置里把内存调小；也可能是 Java 坏了";
        }
        if (joined.contains("java.lang.unsupportedoperationexception")
                || joined.contains("illegal reflective access")) {
            return "Java 版本与游戏不兼容：这个版本需要更老（或更新）的 Java 才能跑";
        }
        if (joined.contains("failed to extract natives")
                || joined.contains("no suitable method was found to load the library")
                || joined.contains("failed to locate library")
                || joined.contains("could not initialize class org.lwjgl.system.library")) {
            return "Native 库（图形/音频底层）没加载上：先看控制台最后几行报的是哪个 .dll，"
                    + "多半是安全软件拦了、或者这个版本目录下的 natives 不全——"
                    + "删掉 <游戏目录>/natives 再启动一次会重新解一份";
        }
        if (joined.contains("nosuchmethoderror")
                || joined.contains("exceptionininitializererror")
                || joined.contains("nosuchfielderror")) {
            // 这一类几乎都是**库版本对不上**：磁盘 libraries 里躺着上一个版本的 jar
            // （26.1.2 的 jtracy 1.0.37 还杵着，26.4-snapshot-2 却要 1.14.38 的新方法）。
            // 只说「崩了」等于没帮上忙，得把「谁家版本旧了」点出来（v0.5）
            String who = "某个依赖库";
            for (String line : lines) {
                String lower = line.toLowerCase(Locale.ROOT);
                int at = lower.indexOf(joined.contains("exceptionininitializererror")
                        && !lower.contains("nosuchmethoderror")
                        ? "caused by:" : "nosuchmethoderror:");
                if (at >= 0) {
                    String from = line.substring(at);
                    int end = from.indexOf('\'');
                    if (end > 0) {
                        who = from.substring(0, end);
                        break;
                    }
                }
            }
            return "运行文件版本对不上（" + who + "）：之前装的文件和这个版本不合，"
                    + "多半是这个版本刚更新过、文件还没跟上。"
                    + "去「下载」页把这个版本重新装一次就好";
        }
        if (joined.contains("noclassdeffounderror")
                || joined.contains("classnotfound")) {
            return "运行文件没下全，游戏起不来。"
                    + "去「下载」页把这个版本重新装一次";
        }
        // 0.9.3 r2：光影/驱动在渲染阶段崩溃的特征——进了世界、开始建渲染管线，然后 Stopping! 退出。
        // 这是游戏/显卡驱动侧问题，启动器改不了，给明确排查方向，别让它被当成「启动器 bug」。
        if (joined.contains("creating pipeline for dimension") && joined.contains("stopping")) {
            return "游戏在「创建世界渲染管线」阶段停了（多半是光影/Iris 在 OpenGL 通路下编译着色器崩了，"
                    + "常见于笔记本独显 + 较重光影如 Complementary）。这是游戏/显卡驱动侧的问题，启动器改不了："
                    + "先把 .minecraft/shaderpacks 里的光影包临时移走、纯 Sodium 进一次世界验证；"
                    + "或更新 NVIDIA 显卡驱动到最新、在 Iris 设置里调低 shader 画质档、换更轻的光影（如 BSL）";
        }
        return null;
    }

    // ---------------------------------------------------------------- Java 探测

    /**
     * 从版本 id 解析 Java 主版本，供 version.json 缺 {@code javaVersion.majorVersion} 时兜底。
     *
     * <p>1.1.2 修正：原先 1.19+ 一律返回 null，导致 MC 1.21.1 这类版本如果 version.json
     * 没写 javaVersion，{@link #javaFor} 就会挑到机器上最高的 Java（比如便携包自带的 Java 25），
     * Cobblemon 这类模组明确要求「恰好 Java 21」时直接拒启。现在按官方要求给出明确提示：
     * <ul>
     *   <li>{@code 1.N} 且 N&lt;=16 → Java 8；N==17/18/19 → Java 17；</li>
     *   <li>1.20.0~1.20.4 → Java 17，1.20.5 起（含 1.21.x）→ Java 21；</li>
     *   <li>再往后（1.22+ 等）还没定版，返回 null 交回元信息，不瞎猜。</li>
     * </ul>
     * 老版本 beta-1.7.3 / alpha-1.6 的 {@code beta-/alpha-} 前缀照样认。
     */
    private static Integer inferJavaMajor(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return null;
        }
        Matcher m = Pattern.compile("(?:beta-|alpha-)?1\\.(\\d+)(?:\\.(\\d+))?")
                .matcher(versionId);
        if (!m.find()) {
            return null;
        }
        int minor;
        try {
            minor = Integer.parseInt(m.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
        int patch = 0;
        if (m.group(2) != null) {
            try {
                patch = Integer.parseInt(m.group(2));
            } catch (NumberFormatException ignored) {
                // 补丁号解析不出来就按 0 处理（保守推成 Java 17）
            }
        }
        if (minor <= 16) {
            return 8;
        }
        if (minor >= 17 && minor <= 19) {
            return 17;
        }
        if (minor == 20) {
            // 1.20.5 是分水岭：之后 MC 要求 Java 21，之前的 1.20.x 仍是 Java 17
            return patch >= 5 ? 21 : 17;
        }
        if (minor == 21) {
            return 21;
        }
        return null;   // 1.22+ 交给元信息，别把 1.21.1 误判成 17 或 25
    }

    // ---------------------------------------------------------------- 1.3.0：已装 mod 的 Java 要求（只读元数据）

    /**
     * 扫一个 mods 目录里所有启用 jar 的元数据，取它们声明的最高 Java major。
     *
     * <p>Fabric/Quilt 读 {@code java} 字段（{@code >=21} / {@code [17,)} / {@code 21}）；
     * Forge/NeoForge 读 {@code [[dependencies.*]]} 里 {@code modId="java"} 的
     * {@code versionRange}，NeoForge 也认 {@code javaVersion}。只用 ZipFile 读那几个小文件，
     * 不解压整包。单个 jar 读失败 / 格式异常静默跳过，任何情况下都不抛、不阻断启动。
     *
     * <p>缓存键 = 「mods 目录最后修改时间 + jar 数量」，同一实例连着启动不重复全扫；
     * 只扫当前要启动的实例（有实例独占 mods 就用它，否则用 GBL 全局 mods）。
     */
    static Integer scanModsMaxJavaMajor(Path modsDir) {
        if (modsDir == null) {
            return null;
        }
        Path dir;
        long mtime;
        List<Path> jars;
        try {
            dir = modsDir.toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                return null;
            }
            mtime = Files.getLastModifiedTime(dir).toMillis();
            jars = new ArrayList<>();
            collectModJars(dir, jars);
        } catch (IOException | RuntimeException ex) {
            return null;   // 扫描失败一律当「没有声明」，绝不阻断启动
        }
        int count = jars.size();
        ModJavaStat cached = MOD_JAVA_CACHE.get(dir);
        if (cached != null && cached.dirMtime() == mtime && cached.jarCount() == count) {
            return cached.maxMajor();
        }
        int max = -1;
        for (Path jar : jars) {
            try {
                Integer found = javaMajorOfJar(jar);
                if (found != null && found > max) {
                    max = found;
                }
            } catch (RuntimeException ignored) {
                // 单个坏 jar 静默跳过
            }
        }
        Integer result = max >= MOD_JAVA_MIN ? max : null;
        MOD_JAVA_CACHE.put(dir, new ModJavaStat(mtime, count, result));
        return result;
    }

    /** mods 根 + 一级子目录里的启用 .jar（与 ModStore.scan 口径一致，不再往下递归）。 */
    private static void collectModJars(Path root, List<Path> out) throws IOException {
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                if (Files.isRegularFile(child) && isEnabledJar(child)) {
                    out.add(child);
                } else if (Files.isDirectory(child)) {
                    try (var sub = Files.list(child)) {
                        for (Path f : sub.toList()) {
                            if (Files.isRegularFile(f) && isEnabledJar(f)) {
                                out.add(f);
                            }
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // 一个子目录读不到就跳过，不影响其它 mod
                    }
                }
            }
        }
    }

    /** .jar.disabled 不以 .jar 结尾，天然排除已禁用模组。 */
    private static boolean isEnabledJar(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar");
    }

    /** 只读 jar 里那几个小元数据文件，绝不解压整包。 */
    private static Integer javaMajorOfJar(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Integer major = null;
            String fabric = readZipEntryText(zip, "fabric.mod.json");
            if (fabric != null) {
                major = maxMajor(major, javaMajorFromFabric(fabric));
            }
            String quilt = readZipEntryText(zip, "quilt.mod.json");
            if (quilt != null) {
                major = maxMajor(major, javaMajorFromQuilt(quilt));
            }
            for (String entry : new String[]{"META-INF/mods.toml", "META-INF/neoforge.mods.toml"}) {
                String toml = readZipEntryText(zip, entry);
                if (toml != null) {
                    major = maxMajor(major, javaMajorFromToml(toml));
                }
            }
            return major;
        } catch (IOException | RuntimeException ex) {
            return null;   // 打不开 / 不是 zip / 条目读坏：静默跳过
        }
    }

    /** 读 zip 里一条小文本，顺手剥掉 UTF-8 BOM；读不到返回 null。 */
    private static String readZipEntryText(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (var in = zip.getInputStream(entry)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return !text.isEmpty() && text.charAt(0) == 0xFEFF ? text.substring(1) : text;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /** Fabric：顶层 {@code "java": ">=21" / "[17,)" / "21"}。 */
    private static Integer javaMajorFromFabric(String json) {
        try {
            Object root = Json.parse(json);
            if (root instanceof Map<?, ?> map) {
                return firstJavaMajor(map.get("java"));
            }
        } catch (RuntimeException ignored) {
            // 元数据坏：按没声明处理
        }
        return null;
    }

    /** Quilt：{@code java} 可能在顶层，也可能在 {@code quilt_loader} 里。 */
    private static Integer javaMajorFromQuilt(String json) {
        try {
            Object root = Json.parse(json);
            if (root instanceof Map<?, ?> map) {
                Integer major = firstJavaMajor(map.get("java"));
                if (map.get("quilt_loader") instanceof Map<?, ?> loader) {
                    major = maxMajor(major, firstJavaMajor(loader.get("java")));
                }
                return major;
            }
        } catch (RuntimeException ignored) {
            // 同上，静默
        }
        return null;
    }

    /** JSON / TOML 里的 Java 值可能是字符串（">=21"）或数字（21），取第一个合理 major。 */
    private static Integer firstJavaMajor(Object value) {
        if (value instanceof Number number) {
            return validJavaMajor(number.intValue());
        }
        if (value instanceof String s) {
            Matcher m = Pattern.compile("\\d{1,2}").matcher(s);
            return m.find() ? validJavaMajor(Integer.parseInt(m.group())) : null;
        }
        return null;
    }

    /**
     * Forge / NeoForge：{@code [[dependencies.*]]} 里 {@code modId="java"} 的
     * {@code versionRange}（如 {@code [21,)}）；NeoForge 也认 {@code javaVersion}。
     * 极简行扫，不引 TOML 库；读不到返回 null。
     */
    private static Integer javaMajorFromToml(String text) {
        Integer best = null;
        boolean inDep = false;
        String modId = null;
        String range = null;
        String javaVersion = null;
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[[") || line.startsWith("[")) {
                if (inDep) {
                    best = maxMajor(best, depJavaMajor(modId, range, javaVersion));
                }
                inDep = line.startsWith("[[dependencies");
                modId = null;
                range = null;
                javaVersion = null;
                continue;
            }
            if (!inDep) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String value = unquote(line.substring(eq + 1).trim());
            if (key.equals("modid")) {
                modId = value;
            } else if (key.equals("versionrange")) {
                range = value;
            } else if (key.equals("javaversion")) {
                javaVersion = value;
            }
        }
        if (inDep) {
            best = maxMajor(best, depJavaMajor(modId, range, javaVersion));
        }
        return best;
    }

    private static Integer depJavaMajor(String modId, String range, String javaVersion) {
        if (!"java".equalsIgnoreCase(modId) && javaVersion == null) {
            return null;
        }
        return maxMajor(firstJavaMajor(range), firstJavaMajor(javaVersion));
    }

    /** TOML 值去掉两端引号；不是带引号的字符串就原样返回。 */
    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.charAt(0) == '"' && value.endsWith("\""))
                    || (value.charAt(0) == '\'' && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** 实例独占 mods 有就用它；没有就用 GBL 当前全局 mods（1.3.0 前所有版本共用这一份）。 */
    private static Path modsDirFor(String id) {
        try {
            Path instanceMods = GameScanner.gameDirFor(id).resolve("mods");
            if (Files.isDirectory(instanceMods)) {
                return instanceMods;
            }
        } catch (IOException | RuntimeException ignored) {
            // 实例目录读不到 → 退回全局 mods，绝不让 Java 扫描阻断启动
        }
        return ModStore.modsDir();
    }

    private static Integer validJavaMajor(int major) {
        return major >= MOD_JAVA_MIN && major <= MOD_JAVA_MAX ? major : null;
    }

    /** 两个可空 major 取较大者；都为空返回 null。 */
    private static Integer maxMajor(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a >= b ? a : b;
    }

    // ---------------------------------------------------------------- 1.3.0 QA 钩子

    /** QA：喂一个构造的 mods 目录，返回扫到的最高 Java major；无 mod / 无声明返回 null。 */
    public static Integer scanModsMaxJavaMajorForTests(Path modsDir) {
        return scanModsMaxJavaMajor(modsDir);
    }

    /** QA：含 mod 扫描的最终 effectiveMajor；无法确定返回 -1。 */
    public static int resolveEffectiveJavaMajorForTests(String instanceId) {
        try {
            VersionJson.Meta meta = VersionJson.loadResolved(instanceId);
            Integer mc = meta == null ? null : meta.javaMajor();
            if (mc == null) {
                mc = inferJavaMajor(instanceId);
            }
            Integer effective = maxMajor(mc, scanModsMaxJavaMajor(modsDirFor(instanceId)));
            return effective == null ? -1 : effective;
        } catch (IOException | RuntimeException ex) {
            return -1;
        }
    }

    /**
     * 找一个能跑这个版本的 Java（0.8.6 定稿：**系统 Java 优先、自带 JRE 兜底**）。
     *
     * <p>第一步只从「手动 javaPath → 真 PATH → JAVA_HOME → 常见安装目录」里挑
     * **匹配 {@code requiredMajor}** 的系统 Java；挑到就直接返回。机器装了 Java 17 时
     * 玩 1.20.1 不会被强塞 Java 25。挑不到才考虑自带 JRE。
     *
     * <p>第二步：仅当系统侧一个都没挑到、且
     * {@code requiredMajor == null} 或 {@code 17 <= requiredMajor <= 25} 时，
     * 才用便携包自带的 {@code GBL/jre/bin/java.exe}（JDK 25.0.3、含 java.se 等 39 个模块）。
     * 老 MC（{@code requiredMajor < 17}）不用它兜底——Java 25 的反射 / ASM 变化会让
     * 1.16 及更早起不来，改走 {@link #javaMissingReason} 给明确提示。
     * {@code requiredMajor > 25} 时自带 Java 25 也不够，同样不兜底。
     *
     * <p><b>{@code <appDir>/runtime/bin/java.exe} 不是可用候选</b>：那是 11 模块的裁剪
     * jlink 镜像，缺 {@code java.logging} 会让 MC 在类初始化阶段
     * {@code ClassNotFoundException: java.util.logging.Logger}。它若经 JAVA_HOME /
     * java.home 混进候选，{@link #needsModuleCheck} 会跑 {@code java --list-modules}
     * 验一遍，不含 {@code java.logging} 就跳过并留日志。
     *
     * <p>{@code requiredMajor} 来自 version.json 的 {@code javaVersion.majorVersion}
     * （26.3 是 25），**不写死**。
     */
    static Path javaFor(Integer requiredMajor) {
        JavaPick best = pickBest(collectSystemCandidates(), requiredMajor);
        // 1.1.2 精确策略：requiredMajor >= 17 视为「精确主版本要求」。MC 1.19+ 与 Cobblemon
        // 这类主流模组都按大版本卡 Java（要 21 就连 25 也拒启），所以没有精确候选时
        // 返回 null，交给 prepare 走 JavaInstaller 自动下载；稍高的那份只在用户关掉
        // 自动下载时由 javaForLoose 兜底（保持旧行为，不强塞给开了自动下载的用户）。
        boolean strict = requiredMajor != null && requiredMajor >= 17;
        if (best != null && (!strict || best.major() == requiredMajor)) {
            return best.exe();
        }
        if (strict) {
            // 便携包哪天换成正好是这个主版本的 JRE 也能直接用，不必下载
            Path bundled = bundledJavaFor(requiredMajor, true);
            if (bundled != null) {
                return bundled;
            }
            System.out.println("[launch] 本机没有 Java " + requiredMajor
                    + "（只探到 " + (best == null ? "更高版本或没有" : best.major())
                    + "），交给自动下载装一份精确的");
            return null;
        }
        return bundledJavaFor(requiredMajor, false);
    }

    /**
     * 关掉「自动下载 Java」时的老行为：允许用比要求新的 Java、也允许自带 JRE 兜底。
     * 这是用户显式选择「别自动下载」后的退路，保持 1.1.1 及以前的口径不变。
     */
    private static Path javaForLoose(Integer requiredMajor) {
        JavaPick best = pickBestLoose(collectSystemCandidates(), requiredMajor);
        if (best != null) {
            return best.exe();
        }
        Path bundled = bundledJavaFor(requiredMajor, false);
        if (bundled != null && requiredMajor != null && requiredMajor >= 17) {
            Integer bundledMajor = majorOf(bundled);
            // 自带完整 JRE 是 Java 25：别拿它硬跑明确要 17 / 21 的版本（ASM / 反射会崩）。
            if (bundledMajor != null && bundledMajor > requiredMajor + 1) {
                System.out.println("[launch] 自带 JRE " + bundledMajor + " 比要求的 Java "
                        + requiredMajor + " 高太多，不用它硬跑");
                return null;   // 宁可走 javaMissingReason 明确提示，也不崩给用户看
            }
        }
        return bundled;
    }

    /**
     * 1.3.3：**自动下载失败后的最后一条活路**——用本机已有的、主版本 &ge; 要求的 Java 起。
     *
     * <p>为什么要有它：1.3.0 起 {@link #javaFor} 对「要求 &ge; 17」的版本走精确主版本匹配，
     * 本机没有精确那一份时返回 {@code null} 交给 {@link JavaInstaller#ensureJava} 自动下载。
     * 这个策略本身是对的（Cobblemon 要 Java 21 时不能被 Java 25 顶包，否则
     * UnsupportedClassVersionError），但它把「下载失败」也退化成了「不能玩」：
     * 实测那份 1.20.1 + Fabric 59 mod 的整合包在本机 Java 27 上完整跑通（世界、存档、
     * 正常退出、零报错），却因为下不到精确的 Java 17 被直接 Blocked。
     * 精确的拿不到，就用能顶上的——这就是本方法。
     *
     * <p>判据只有一条：候选的 {@code major >= effectiveMajor}（自然满足「Cobblemon 要 21、
     * 本机只有 17 就仍然拦」这条 1.3.0 的老防护）。此外还有两道闸：
     * <ol>
     *   <li>只在 {@code effectiveMajor >= 17} 时启用——老 MC（1.16 要 Java 8）用 Java 25
     *       会因反射 / ASM 变化崩，那条仍然走 {@link #javaMissingReason} 的明确提示；</li>
     *   <li>候选数量大于一且都合格时取**主版本最高**的那份——高版本向下兼容更稳，
     *       本机同时有 25 和 27 时用 27。</li>
     * </ol>
     *
     * <p>这个兜底**只在自动下载失败后**触发：{@link #javaFor} 的精确优先不变，自动下载成功
     * 时仍用下载的精确版本，用户关掉自动下载时仍走 {@link #javaForLoose} 的老路径。
     *
     * @param effectiveMajor 本版本真正需要的主版本（MC 要求与已装 mod 声明的最大值）
     * @return 可直接启动的 java 可执行文件；本机确实没有能顶上的返回 {@code null}
     */
    private static Path javaForDownloadFallback(Integer effectiveMajor) {
        if (!downloadFallbackAllowed(effectiveMajor)) {
            // 老版本不兜底：Java 25/27 跑 1.16 会崩在反射 / ASM 上，宁可给明确提示
            return null;
        }
        List<Path> candidates = new ArrayList<>(collectSystemCandidates());
        // 便携包自带的完整 JRE（Java 25）也是候选之一：本机既没装别的、下载又失败时它常常
        // 是唯一能给的东西。这里直接用 findBundledJre() 而不是 bundledJavaFor(...)：后者会打印
        // 「系统 Java 没匹配到」这种此刻未必成立的话，且它内部还带着 +1 上限的旧口径，
        // 本兜底恰恰要放开那个上限（诊断已证明 Java 27 能跑 1.20.1）。
        Path bundled = findBundledJre();
        if (bundled != null) {
            candidates.add(bundled);
        }
        JavaPick pick = selectDownloadFallback(candidates, effectiveMajor, GameLauncher::majorOf);
        if (pick == null) {
            System.out.println("[launch] 精确版 Java " + effectiveMajor
                    + " 没装上（自动下载失败），本机也没有 " + effectiveMajor + " 及以上的可用 Java");
            return null;
        }
        // 日志写清用的是「本机装的」还是「便携包自带的」，方便看日志定位（诊断包里就靠这几行）
        boolean fromBundled = bundled != null && bundled.equals(pick.exe());
        if (fromBundled) {
            System.out.println("[launch] 精确版 Java " + effectiveMajor
                    + " 下载失败，改用便携包自带 JRE（Java " + pick.major()
                    + "）启动（该版本已能运行本游戏）: " + pick.exe());
        } else {
            System.out.println("[launch] 精确版 Java " + effectiveMajor + " 下载失败，改用本机 Java "
                    + pick.major() + " 启动（该版本已能运行本游戏）: " + pick.exe());
        }
        return pick.exe();
    }

    /** 兜底开关：只有现代版本（{@code effectiveMajor >= 17}）才允许用「更高的 Java」顶上。 */
    private static boolean downloadFallbackAllowed(Integer effectiveMajor) {
        return effectiveMajor != null && effectiveMajor >= 17;
    }

    /**
     * 兜底选择的核心（纯函数，major 探测可注入，QA 直接调）。
     *
     * <p>规则：跳过不存在的、跳过裁剪 runtime（缺 {@code java.logging}），跳过主版本低于
     * {@code effectiveMajor} 的；在剩下的里挑主版本最高的那份。
     */
    private static JavaPick selectDownloadFallback(List<Path> candidates, Integer effectiveMajor,
                                                   Function<Path, Integer> majorResolver) {
        if (!downloadFallbackAllowed(effectiveMajor) || candidates == null) {
            return null;
        }
        JavaPick best = null;
        for (Path candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            try {
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
            } catch (RuntimeException ex) {
                continue;   // 非法路径片段，跳过
            }
            if (needsModuleCheck(candidate) && !javaHasLoggingModules(candidate)) {
                continue;   // 裁剪 runtime 缺 java.logging，MC 会在类初始化阶段崩
            }
            Integer major = majorResolver == null ? majorOf(candidate) : majorResolver.apply(candidate);
            if (major == null || major < effectiveMajor) {
                // 低于要求的一律不选：Cobblemon 要 Java 21 而本机只有 17 时，必须仍然拦
                continue;
            }
            if (best == null || major > best.major()) {
                best = new JavaPick(candidate, major);   // 高版本向下兼容更稳，取最高的
            }
        }
        return best;
    }

    /**
     * QA 钩子：候选可注入，按真实机器上的 {@code release} / {@code -version} 探主版本。
     *
     * <p>用法：造几个假候选（目录里放一个空的 {@code bin/java}，同目录 {@code release} 写
     * {@code JAVA_VERSION="27"}），断言返回的是主版本够高的那一份。传入
     * {@code effectiveMajor = 8}（老 MC）应恒为 {@code null}。
     */
    public static Path javaForDownloadFallbackForTests(List<Path> candidates,
                                                       Integer effectiveMajor) {
        JavaPick pick = selectDownloadFallback(candidates, effectiveMajor, GameLauncher::majorOf);
        return pick == null ? null : pick.exe();
    }

    /**
     * QA 钩子：连主版本探测也能注入（不想造 {@code release} 文件、也不想真跑 {@code -version}
     * 拖慢测试时用）。{@code majorResolver} 返回 {@code null} 表示「这份探不出主版本」，
     * 按不可用处理。
     */
    public static Path javaForDownloadFallbackForTests(List<Path> candidates,
                                                       Integer effectiveMajor,
                                                       Function<Path, Integer> majorResolver) {
        JavaPick pick = selectDownloadFallback(candidates, effectiveMajor, majorResolver);
        return pick == null ? null : pick.exe();
    }

    /** 系统 Java 候选：手动 javaPath → 真 PATH → JAVA_HOME → 常见安装目录 → 系统目录。 */
    private static List<Path> collectSystemCandidates() {
        List<Path> out = new ArrayList<>();
        // 1) 用户手动指定（设置页 javaPath）
        String configured = SettingsStore.configuredJavaPath();
        if (configured != null && !configured.isBlank()) {
            Path manual = Paths.get(configured);
            // 允许填 JDK 根目录（用户手改 config 时很常见），自动补 bin/java(.exe)
            if (Files.isDirectory(manual)) {
                manual = manual.resolve("bin").resolve(exe("java"));
            }
            addIfExists(out, manual);
        }
        // 1.5) 本启动器自动下载过的 JRE（~/.gbl/runtimes/jre-*/bin/java）：下次启动直接命中，
        // 不必再进一次「没找到 → 自动下载 → 发现已装好」的流程
        addDownloadedJavaCandidates(out);
        // 2) 真 PATH 搜索（替换原来 Paths.get("", "java.exe") 那个假搜索）
        addPathCandidates(out);
        // 3) JAVA_HOME（未设置时 javaHome() 回退到当前 JVM，天然覆盖 java.home/bin/java）
        addIfExists(out, Paths.get(javaHome(), "bin", exe("java")));
        // 4) 常见安装目录 + 系统目录（原有候选，全保留）
        for (Path candidate : javaHomeLike()) {
            addIfExists(out, candidate);
        }
        for (Path candidate : osLike()) {
            addIfExists(out, candidate);
        }
        return out;
    }

    /** 把 ~/.gbl/runtimes 下每个 jre-N 子目录里的 bin/java(.exe) 加进候选（只读扫描，读不了就跳过）。 */
    private static void addDownloadedJavaCandidates(List<Path> out) {
        Path root = Paths.get(System.getProperty("user.home"), ".gbl", "runtimes");
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var children = Files.list(root)) {
            for (Path dir : children.filter(Files::isDirectory).toList()) {
                String name = dir.getFileName().toString();
                if (!name.startsWith("jre-")) {
                    continue;   // 临时 zip / 中转目录不碰
                }
                addIfExists(out, dir.resolve("bin").resolve(exe("java")));
            }
        } catch (IOException | RuntimeException ignored) {
            // 目录读不了就当没装过，后面会按需自动下载
        }
    }

    /** 自带完整 JRE 兜底；strict 时必须主版本恰好相等，否则宁可不兜、交出去自动下载。 */
    private static Path bundledJavaFor(Integer requiredMajor, boolean strict) {
        boolean mayUseBundled = requiredMajor == null
                || (requiredMajor >= 17 && requiredMajor <= 25);
        if (!mayUseBundled) {
            System.out.println("[launch] 需要 Java " + requiredMajor
                    + "，自带 Java 25 不兜底（老版本反射/ASM 不兼容，或 25 还不够新）");
            return null;
        }
        Path bundled = findBundledJre();
        if (bundled == null) {
            return null;
        }
        Integer major = majorOf(bundled);
        if (major == null) {
            return null;
        }
        boolean usable = strict
                ? major == requiredMajor
                : requiredMajor == null || major >= requiredMajor;
        if (!usable) {
            System.out.println("[launch] 自带 JRE 版本不合适（" + major
                    + "，要求 " + requiredMajor + "），不用它兜底: " + bundled);
            return null;
        }
        System.out.println("[launch] 系统 Java 没匹配到，使用便携包自带 JRE: " + bundled);
        return bundled;
    }

    /** 一次 Java 挑选结果：可执行文件 + 已探到的主版本，避免后面再跑一次 java -version。 */
    private record JavaPick(Path exe, int major) {
    }

    /**
     * 系统候选里挑一份：**精确主版本优先**（{@code major == requiredMajor} 直接选它），
     * 没有精确的才退而求其次挑 {@code major > requiredMajor} 里最小的（兼容旧行为）。
     * 没写版本要求时按老行为挑主版本最高的。落在裁剪 runtime 下且缺 {@code java.logging}
     * 的直接跳过。
     *
     * <p>注意：这里返回「更高的那份」并不代表它能用——{@link #javaFor} 在 strict 场景
     * （requiredMajor &gt;= 17）会把它丢掉改走自动下载，只有关掉自动下载时
     * {@link #javaForLoose} 才会用它兜底。这样 Cobblemon 要 Java 21 时不会被 Java 25 顶包。
     */
    private static JavaPick pickBest(List<Path> candidates, Integer requiredMajor) {
        JavaPick exact = null;
        JavaPick higher = null;
        JavaPick highestAny = null;
        for (Path candidate : candidates) {
            if (needsModuleCheck(candidate) && !javaHasLoggingModules(candidate)) {
                continue;
            }
            Integer major = majorOf(candidate);
            if (major == null) {
                continue;
            }
            if (requiredMajor == null) {
                if (highestAny == null || major > highestAny.major()) {
                    highestAny = new JavaPick(candidate, major);
                }
                continue;
            }
            if (major == requiredMajor) {
                if (exact == null) {
                    exact = new JavaPick(candidate, major);
                }
            } else if (major > requiredMajor && (higher == null || major < higher.major())) {
                higher = new JavaPick(candidate, major);
            }
        }
        if (requiredMajor == null) {
            return highestAny;
        }
        return exact != null ? exact : higher;
    }

    /** 1.1.1 老算法：满足要求的前提下挑最小的（没要求就挑最大的），供 javaForLoose 用。 */
    private static JavaPick pickBestLoose(List<Path> candidates, Integer requiredMajor) {
        JavaPick best = null;
        for (Path candidate : candidates) {
            if (needsModuleCheck(candidate) && !javaHasLoggingModules(candidate)) {
                continue;
            }
            Integer major = majorOf(candidate);
            if (major == null) {
                continue;
            }
            if (requiredMajor != null && major < requiredMajor) {
                continue;
            }
            // 1.3.0：过高同样起不来（Java 25 跑 1.20.1 会 ASM / 反射崩），只放行高一个小版本。
            // 上限只对 >=17 的现代版本生效，老 MC 的 loose 口径保持原样不动。
            if (requiredMajor != null && requiredMajor >= 17 && major > requiredMajor + 1) {
                continue;
            }
            if (best == null) {
                best = new JavaPick(candidate, major);
                continue;
            }
            if (requiredMajor == null ? major > best.major() : major < best.major()) {
                best = new JavaPick(candidate, major);
            }
        }
        return best;
    }

    /** 当前 JVM 的 home（只读系统属性，不受环境变量影响）。 */
    private static String currentJavaHome() {
        String home = System.getProperty("java.home", "");
        return home == null ? "" : home;
    }

    /** GBL.exe 所在目录；探不到就退回当前工作目录。 */
    private static Path appDir() {
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
     * 找便携包自带的**完整** JRE（打包侧定稿 {@code GBL/jre/}，39 模块 / JDK 25.0.3，
     * 自带 java.exe/javaw.exe）。三处探测按顺序取第一个存在且可执行的：
     * <ol>
     *   <li>{@code java.home/../jre/bin/java(.exe)}（jpackage 下 java.home = &lt;appDir&gt;/runtime）；</li>
     *   <li>{@code <appDir>/jre/bin/java(.exe)}，appDir 由 GBL.exe 命令行推导；</li>
     *   <li>{@code user.dir/jre/bin/java(.exe)}。</li>
     * </ol>
     *
     * <p><b>刻意不探 {@code runtime/bin/java}</b>：那是 11 模块的裁剪镜像，
     * 不是可用候选（若它经别的途径混进候选，由 {@link #needsModuleCheck} 挡掉）。
     */
    private static Path findBundledJre() {
        String javaExe = exe("java");
        List<Path> probes = new ArrayList<>();
        String home = currentJavaHome();
        if (!home.isBlank()) {
            Path parent = Paths.get(home).toAbsolutePath().normalize().getParent();
            if (parent != null) {
                probes.add(parent.resolve("jre").resolve("bin").resolve(javaExe));
            }
        }
        probes.add(appDir().resolve("jre").resolve("bin").resolve(javaExe));
        probes.add(Paths.get(System.getProperty("user.dir", ""))
                .resolve("jre").resolve("bin").resolve(javaExe));
        for (Path probe : probes) {
            try {
                Path normalized = probe.toAbsolutePath().normalize();
                if (Files.isRegularFile(normalized) && Files.isExecutable(normalized)) {
                    return normalized;
                }
            } catch (RuntimeException ignored) {
                // 非法路径片段跳过
            }
        }
        return null;
    }

    /** 诊断用：自带完整 JRE 在不在磁盘上。 */
    private static boolean bundledJrePresent() {
        return findBundledJre() != null;
    }

    /** 诊断用：数据目录是不是 GBL.exe 同级的 .minecraft（便携模式）。 */
    private static boolean portableMode() {
        try {
            return Platform.minecraftDir().toAbsolutePath().normalize()
                    .equals(appDir().resolve(".minecraft").toAbsolutePath().normalize());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** 真 PATH 搜索：按 pathSeparator 切分，逐个拼 java(.exe)，用 isExecutable 判定。 */
    private static void addPathCandidates(List<Path> out) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            try {
                Path candidate = Paths.get(dir.trim()).resolve(exe("java"));
                if (Files.isExecutable(candidate)) {
                    addIfExists(out, candidate);
                }
            } catch (RuntimeException ignored) {
                // PATH 里有非法路径片段就跳过，别让探测挂掉
            }
        }
    }

    /**
     * 只有可能落在「启动器自带裁剪 runtime」下的候选才需要验模块（外部 JDK 默认齐全）。
     * 覆盖两类：当前 {@code java.home}，以及 {@code <appDir>/runtime} —— 后者即使用户
     * 把 JAVA_HOME 指过去、或测试时拷了 java.exe 进去，也能被挡住。
     */
    private static boolean needsModuleCheck(Path javaExe) {
        Path normalized;
        try {
            normalized = javaExe.toAbsolutePath().normalize();
        } catch (RuntimeException ex) {
            return false;
        }
        String home = currentJavaHome();
        if (!home.isBlank()) {
            try {
                if (normalized.startsWith(Paths.get(home).toAbsolutePath().normalize())) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // 继续看 appDir/runtime
            }
        }
        try {
            if (normalized.startsWith(appDir().resolve("runtime").toAbsolutePath().normalize())) {
                return true;
            }
        } catch (RuntimeException ignored) {
            // 探不到就按外部 JDK 处理
        }
        return false;
    }

    /** 跑一次 {@code java --list-modules} 确认 {@code java.logging} 在；按可执行文件缓存。 */
    private static boolean javaHasLoggingModules(Path javaExe) {
        Boolean cached = JAVA_MODULE_CHECK.get(javaExe);
        if (cached != null) {
            return cached;
        }
        boolean ok = false;
        try {
            Process process = new ProcessBuilder(javaExe.toString(), "--list-modules")
                    .redirectErrorStream(true).start();
            if (process.waitFor(5, TimeUnit.SECONDS)) {
                String text = new String(process.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                if (process.exitValue() != 0 || text.contains("Unrecognized option")) {
                    // 老 JDK 不认 --list-modules：探不出来就别拦，免得误伤能跑老版本的那份 Java
                    ok = true;
                } else {
                    ok = text.contains("java.logging");
                }
            } else {
                process.destroyForcibly();
            }
        } catch (IOException | InterruptedException | RuntimeException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            System.err.println("[launch] 探 Java 模块失败，跳过 " + javaExe + ": " + ex);
        }
        if (!ok) {
            System.err.println("[launch] 跳过裁剪 runtime（缺 java.logging，MC 会 "
                    + "ClassNotFoundException: java.util.logging.Logger）: " + javaExe);
        }
        JAVA_MODULE_CHECK.put(javaExe, ok);
        return ok;
    }

    /**
     * 找不到时那句实话（0.8.6 定稿文案）：说清自带 JRE 已经存在，
     * 手动指定只是可选出路之一，不再是唯一出路。
     *
     * <p>本机探到的系统 Java 明细不再塞进这句话（用户看着太长），
     * 完整环境信息在 {@code exportDiagnostics} 的 {@code launch-info.txt} 里。
     */
    private static String javaMissingReason(Integer requiredMajor) {
        return javaMissingReason(requiredMajor, false);
    }

    /**
     * 1.1.2：自动下载也失败时多说一句「刚才那步也失败了 + 下一步怎么办」。
     * 原文案一个字不动地保留在前缀里，避免动到老版本一直在用的提示口径。
     */
    private static String javaMissingReason(Integer requiredMajor, boolean autoFailed) {
        return javaMissingReason(requiredMajor, null, autoFailed);
    }

    /**
     * 1.3.0：真实冲突往往来自整合包模组而非 Minecraft 本身，旧文案一律写成「Minecraft 的要求」，
     * 用户照着装了 Java 17 还是会崩。这里把两个来源分开说清楚。
     */
    private static String javaMissingReason(Integer requiredMajor, Integer mcMajor,
                                            boolean autoFailed) {
        return javaMissingReason(requiredMajor, mcMajor, autoFailed, false);
    }

    /**
     * 1.3.3：自动下载失败、且「用本机更高的 Java 顶上」这条兜底也没成时，把这两步都写清楚，
     * 并给出真正能做的下一步（换网络重试 / 手动指定），别让人读完不知道干什么。
     *
     * <p>{@code fallbackTried} 为 {@code false} 时（老版本 effectiveMajor &lt; 17，兜底本来
     * 就不启用）保持 1.1.2 那句原文案不变。
     */
    private static String javaMissingReason(Integer requiredMajor, Integer mcMajor,
                                            boolean autoFailed, boolean fallbackTried) {
        String need = requiredMajor == null ? "更高版本的 Java" : ("Java " + requiredMajor);
        String why = (mcMajor != null && requiredMajor != null && requiredMajor > mcMajor)
                ? "这个整合包的模组需要 " + need + "（Minecraft 本身只要 Java " + mcMajor + "）"
                : "本游戏需要 " + need + "（当前版本 Minecraft 的要求）";
        String base = why + "。"
                + "便携包已自带 Java 25（jre/bin/java.exe）；"
                + "若仍找不到可用 Java，请在设置页手动指定或安装对应版本。";
        if (!autoFailed) {
            return base;
        }
        if (fallbackTried) {
            String tail = requiredMajor == null
                    ? "刚才自动下载 Java 也没成功，本机也没有别的可用 Java。"
                    : "刚才自动下载 Java " + requiredMajor + " 也没成功，本机也没有 Java "
                            + requiredMajor + " 及以上的可选项。";
            return base + tail + "可以换个网络再点一次启动，让启动器重新下一遍。";
        }
        return base + "刚才尝试自动下载也失败了，请检查网络，或手动指定 Java 路径。";
    }

    private static List<Path> javaHomeLike() {
        List<Path> out = new ArrayList<>();
        String[] roots = {
            "C:\\Program Files\\Microsoft",       // 本机这套 JDK 就在这儿（v0.4.7 实测）
            "C:\\Program Files\\Java",
            "C:\\Program Files\\Eclipse Adoptium",
            "C:\\Program Files\\Adoptium",
            "C:\\Program Files\\Amazon Corretto",
            "C:\\Program Files\\Zulu",
            "C:\\Program Files\\OpenJDK",
        };
        for (String root : roots) {
            Path dir = Paths.get(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (var children = Files.list(dir)) {
                for (Path child : children.filter(Files::isDirectory).toList()) {
                    addIfExists(out, child.resolve("bin").resolve(exe("java")));
                }
            } catch (IOException ignored) {
                // 某个目录看不了就跳过，还有别的候选
            }
        }
        return out;
    }

    private static List<Path> osLike() {
        List<Path> out = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            Path dir = Paths.get(System.getProperty("user.home"),
                    "Library", "Java", "JavaVirtualMachines");
            try (var children = Files.list(dir)) {
                for (Path child : children.filter(Files::isDirectory).toList()) {
                    addIfExists(out, child.resolve("Contents").resolve("Home")
                            .resolve("bin").resolve("java"));
                }
            } catch (IOException ignored) {
                // 同上
            }
        } else if (os.contains("nix") || os.contains("nux")) {
            Path dir = Paths.get("/usr/lib/jvm");
            try (var children = Files.list(dir)) {
                children.filter(Files::isDirectory).forEach(child -> addIfExists(out,
                        child.resolve("bin").resolve("java")));
            } catch (IOException ignored) {
                // 同上
            }
        }
        return out;
    }

    /** 读 {@code release} 拿大版本，读不到就跑一次 {@code -version}（慢，但准）。 */
    private static Integer majorOf(Path javaExe) {
        if (javaExe == null || !Files.isRegularFile(javaExe)) {
            return null;
        }
        Path home = javaExe.getParent().getParent();
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
        try {
            Process process = new ProcessBuilder(javaExe.toString(), "-version")
                    .redirectErrorStream(true).start();
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(5, TimeUnit.SECONDS);
            return majorOfVersion(text);
        } catch (IOException | InterruptedException | RuntimeException ignored) {
            return null;
        }
    }

    /** {@code 25.0.3} / {@code "1.8.0_503"} → {25} / {8}。 */
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

    // ---------------------------------------------------------------- classpath / natives

    /** 目录里第一个真下完的 jar（{@code .jar.part} 不算）。 */
    private static Path firstJar(Path gameDir) {
        try (var children = Files.list(gameDir)) {
            return children.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".jar"))
                    .sorted(Comparator.comparingLong(GameLauncher::modified))
                    .findFirst().orElse(null);
        } catch (IOException ex) {
            System.err.println("[launch] 看不清 " + gameDir.getFileName() + " 里有没有 jar: " + ex);
            return null;
        }
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0;
        }
    }

    /**
     * 把该进 classpath 的库加进去，返回**缺了几个**。
     *
     * <p>{@code rules} 按当前系统过滤（{@code allow/os.name=windows} 这类），
     * 带 {@code natives} 的库进 natives 不进 classpath。
     */
    private static int addToClasspath(List<Object> libraries, List<String> classpath) {
        Path libDir = Platform.minecraftDir().resolve("libraries");
        int missing = 0;
        if (libraries == null) {
            return 0;
        }
        for (Object raw : libraries) {
            if (!(raw instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> lib = (Map<String, Object>) raw;
            if (!allowed(lib)) {
                continue;
            }
            boolean isNatives = lib.containsKey("natives")
                    || Boolean.TRUE.equals(lib.get("nativesOnly"));
            if (isNatives) {
                continue;   // natives 由 decompressNatives 处理
            }
            Object downloads = lib.get("downloads");
            if (!(downloads instanceof Map)) {
                continue;
            }
            Object artifact = ((Map<?, ?>) downloads).get("artifact");
            if (!(artifact instanceof Map)) {
                continue;
            }
            Object path = ((Map<?, ?>) artifact).get("path");
            if (!(path instanceof String text)) {
                continue;
            }
            Path file = libDir.resolve(text);
            if (Files.isRegularFile(file)) {
                classpath.add(file.toString());
            } else {
                missing++;
            }
        }
        return missing;
    }

    /**
     * {@code arguments} 里那几个 {@code features} 开关。
     *
     * <p>判错了的代价不对称：{@code has_quick_plays_support} 判成 true 会把
     * {@code --quickPlayPath ${quickPlayPath}} 整条交给游戏，占位符没人填，
     * 启动阶段直接抛；判成 false 只是少个我们本来也给不了的参数。
     */
    private static final Map<String, Boolean> FEATURES = Map.of(
            "is_demo_user", false,               // 离线模式不该走 30 分钟演示限制
            "has_custom_resolution", true,       // 我们固定给 1280x720
            "has_quick_plays_support", false,
            "is_quick_play_singleplayer", false,
            "is_quick_play_multiplayer", false,
            "is_quick_play_realms", false);

    /** 规则的 {@code os.arch} 跟本机架构对得上吗（{@code x86} 之类）。 */
    private static boolean archOk(String want) {
        if (want == null || want.isBlank() || "any".equals(want)) {
            return true;
        }
        String current = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (want.equalsIgnoreCase(current)) {
            return true;
        }
        // 规则写 x86 一族时，本机是 32 位才算对上；amd64 不算（官方 launcher 也这么判）
        return current.startsWith("x86") && want.toLowerCase(Locale.ROOT).startsWith("x86");
    }

    /** 规则允许当前系统用这个库 / 参数吗（库与 arguments 共用一份实现）。{@code LibFetcher} 也问它。 */
    static boolean allowed(Map<?, ?> lib) {
        Object rules = lib.get("rules");
        if (!(rules instanceof List)) {
            return true;
        }
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        for (Object raw : (List<?>) rules) {
            if (!(raw instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> rule = (Map<?, ?>) raw;
            boolean allow = "allow".equals(String.valueOf(rule.get("action")));
            boolean hit;
            if (rule.containsKey("os")) {
                // os 条件：name 比系统，arch 比机器架构（拿规则里的值和本机 os.arch 比，
                // 不是拿它和常量 "x86" 比——否则 64 位机上老参数全被误留）
                Object cond = rule.get("os");
                Object name = cond instanceof Map ? ((Map<?, ?>) cond).get("name") : null;
                // **规则没写 arch 就是「不限架构」**：这里绝不能 String.valueOf(...)——
                // 缺 key 时它会把 null 变成字符串 "null"，archOk("null") 恒 false，
                // 于是每条只写了 os.name 的库（natives 包全这类）都被当成「架构对不上」拒掉，
                // v0.4.9 之前老版本因此少掉整套 natives，一启动就 Failed to locate library
                Object arch = cond instanceof Map ? ((Map<?, ?>) cond).get("arch") : null;
                hit = ((name == null) || name.equals("windows") == win)
                        && archOk(arch == null ? null : String.valueOf(arch));
            } else {
                // features 条件：按 FEATURES 查；认不出来的默认 false（宁可少给，别传半截参数）
                Object cond = rule.get("features");
                hit = cond instanceof Map
                        && ((Map<?, ?>) cond).keySet().stream()
                        .allMatch(k -> Boolean.TRUE.equals(FEATURES.get(String.valueOf(k))));
            }
            if (hit != allow) {
                return false;
            }
        }
        return true;
    }

    /** 本机是不是 arm64（挑原生库时用来排掉别的架构那份）。 */
    private static boolean isArm64() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return arch.contains("aarch64") || arch.contains("arm64");
    }

    /** 当前系统要的原生库后缀。 */
    private static String nativeSuffix() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return ".dylib";
        }
        return os.contains("nix") || os.contains("nux") ? ".so" : ".dll";
    }

    /**
     * 把 natives 解压出来。
     *
     * <p>**落点必须是 {@code natives/java}/**，不是 natives 根：新版 jvm 参数里写死了
     * {@code -Djava.library.path=${natives_directory}/java}，落错地方 LWJGL 直接
     * 「Failed to locate library: lwjgl.dll」——v0.4.7 卡在这里最久。
     *
     * <p>顺带说明为什么**自己解而不用 LWJGL 自提取**：新格式的 natives 都藏在
     * {@code META-INF/windows/x64/...} 下，且实测第二次进去 LWJGL 懒得再提一次，
     * 游戏就起不来了。自己解一份落盘，跟抽没抽过没关系，永远在。
     */
    /**
     * 把 native 动态库从 libraries 里的 natives 包中解到 {@code natives/java}。
     *
     * <p>返回解出来的个数；**一个都没解出来就抛 IOException**，由 prepare 翻成中文 Blocked。
     * 这里绝不能静默放行：natives 目录被清空却没 dll，游戏会带着指向空目录的
     * {@code -Djava.library.path} 起来，崩在「加载界面」——画面定格、启动器还以为在跑，
     * 用户看到的正是「卡在加载界面」。
     *
     * <p>v0.4.9 补两条：**已经解好且完整就一步跳过**（重解一遍是几秒的 IO，还可能撞上上局
     * 游戏占着的 dll），以及**只收本机系统这一份 natives 包**——把 linux/mac/arm64 的
     * 一起收进来，它们全不在磁盘上，凑数凑出一堆「缺 28 个 native 包」的假故障。
     *
     * @param nativeOut 动态库落到哪个目录：老版是 {@code natives} 根，新版是它的 {@code java}
     *                  子目录，由 {@link #nestedNatives} 挑
     */
    private static int decompressNatives(List<Object> libraries, Path nativeOut) throws IOException {
        if (libraries == null) {
            return 0;
        }
        String suffix = nativeSuffix();
        Path libDir = Platform.minecraftDir().resolve("libraries");
        // 上一局留下的这份还在、核心那颗也在**且完整可读** → 直接放行。整包重解是几秒的磁盘 IO，
        // 而且会把正被游戏占着的 dll 删掉（v0.4.8 那批 .tmp+move 就是为了绕它）。
        // v0.4.10：光看「文件在不在」不够——上一局「退出卡死、息屏强杀」会把 lwjgl.dll
        // 留成被占用锁死/写一半的坏文件，文件「在」但加载必炸 Failed to locate library，
        // 两个自检都拦不住，游戏就黑屏/卡主界面/窗口全黑。这里改成校验完整性。
        if (nativeOk(nativeOut)) {
            return 0;
        }
        List<Path> zips = new ArrayList<>();
        for (Object raw : libraries) {
            if (!(raw instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> lib = (Map<String, Object>) raw;
            // 新版 lwjgl（26.3 就这套）natives jar 是独立的库条目、走 downloads.artifact
            // （老格式 1.20.1 一样），判据就是「文件名里带本机的 natives 标记」——
            // 只收本机那份，别把 linux/mac/arm64 的也收进来凑数（v0.4.9）
            if (!(lib.get("downloads") instanceof Map<?, ?> dl)
                    || !(dl.get("artifact") instanceof Map<?, ?> artifact)
                    || !(artifact.get("path") instanceof String text)) {
                continue;
            }
            if (LibFetcher.isNativeJar(text)) {
                addNativeJar(libDir, text, zips);
            }
        }
        if (zips.isEmpty()) {
            return 0;   // 没找到 natives 压缩包，游戏自己会从 classpath 里提，这里没活干
        }
        int missing = 0;
        int failed = 0;
        int made = 0;
        // 这个包里一共该出哪些动态库——拿它当名册校对，比猜文件名准：
        // 用 startsWith("lwjgl") 判「核心库在不在」会被 lwjgl_opengl.dll 骗过去
        java.util.Set<String> expected = new java.util.LinkedHashSet<>();
        // 先建出 natives 根：上次可能整个目录都不在了，Files.walk 会直接 NoSuchFileException
        Files.createDirectories(nativeOut);
        // 倒序删：Files.walk 是前序，正着删会先碰到不为空的目录、抛
        // DirectoryNotEmptyException（v0.4.7 原样踩过）。
        // **清旧文件的起点必须是 nativeOut 自己**——拿它的父目录当起点，
        // 老版本（落点就是 natives 根）会一路删到 versions/<id>/ 上去（v0.4.9 删过一次用户的 1.20.1）
        try (var walk = Files.walk(nativeOut)) {
            walk.sorted(Comparator.reverseOrder()).forEach(GameLauncher::deleteQuietly);
        }
        Files.createDirectories(nativeOut);
        for (Path zip : zips) {
            if (!Files.isRegularFile(zip)) {
                // 别的系统（linux/macos/arm64）的 natives 包本来就不在磁盘上，
                // 数一数给个提醒就行，不能当故障——缺的那颗由核心库校验兜底
                missing++;
                continue;
            }
            try (ZipFile file = new ZipFile(zip.toFile())) {
                var entries = file.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    // 只挑原生动态库：.dll / .so / .dylib，目录和小文件一律不管
                    if (entry.isDirectory()
                            || !entry.getName().toLowerCase(Locale.ROOT).endsWith(suffix)) {
                        continue;
                    }
                    String name = entry.getName();
                    int slash = name.lastIndexOf('/');
                    Path dest = nativeOut.resolve(name.substring(slash + 1)).normalize();
                    if (!dest.startsWith(nativeOut)) {
                        continue;   // 防 zip slip
                    }
                    expected.add(dest.getFileName().toString());
                    // 先落临时文件、再整体挪过去：Windows 上 dll 正被上局游戏占着时，
                    // 直接 REPLACE_EXISTING 覆盖会整轮抛异常，把后面没解完的 dll 一起坑掉
                    Path tmp = nativeOut.resolve(dest.getFileName() + ".tmp");
                    try (var in = file.getInputStream(entry)) {
                        Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        Files.deleteIfExists(dest);
                        Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        made++;
                    } catch (IOException ex) {
                        failed++;
                        deleteQuietly(tmp);
                    }
                }
            } catch (IOException ex) {
                failed++;
            }
        }
        if (missing > 0) {
            System.err.println("[launch] 有 " + missing + " 个 natives 包不在磁盘上，跳过");
        }
        if (made == 0) {
            throw new IOException(missing > 0
                    ? ("有 " + missing + " 个运行文件没找到"
                            + "（多半没下载完整），去「下载」页把这个版本重新装一次")
                    : ("运行文件读不出来（失败 " + failed + " 个），"
                            + "多半是上局游戏没退干净，请完全退出游戏后再试"));
        }
        if (!expected.isEmpty()) {
            java.util.List<String> absent = new java.util.ArrayList<>();
            for (String name : expected) {
                if (!Files.isRegularFile(nativeOut.resolve(name))) {
                    absent.add(name);
                }
            }
            if (!absent.isEmpty()) {
                throw new IOException("动态库解到一半停了，缺 " + String.join("、", absent)
                        + "（失败 " + failed + " 个，多半是上局游戏没退干净、dll 被占着）");
            }
        }
        return made;
    }

    /** 删一个路径，删不掉就算了（换个地方覆盖写）。 */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
            // 文件被别的进程占着（比如上回那局游戏没退干净），跳过即可
        }
    }

    /** 收一个 natives 压缩包；别的架构那份别进来——同名 dll 会把本机那份盖掉。 */
    private static void addNativeJar(Path libDir, String path, List<Path> zips) {
        Path jar = libDir.resolve(path);
        boolean armJar = jar.getFileName().toString()
                .toLowerCase(Locale.ROOT).contains("arm64");
        if (armJar == isArm64() && !zips.contains(jar)) {
            zips.add(jar);
        }
    }

    /**
     * 资源索引里有没有缺的文件；用来在启动前就提醒一句。
     *
     * <p>v0.4.9 加了两个闸：一次最多数 {@value #ASSET_SCAN_LIMIT} 个对象（全量是 1 万多个，
     * 每次启动都要 stat 一遍，实测把 prepare 拖到 3.5 秒），以及按「索引 + 那一刻」缓存，
     * 同一版本连着启动不算第二遍。提醒只是提醒，没必要为了它把启动拖成慢动作。
     *
     * <p>但**刚补完资源时必须把缓存丢掉**（{@link #forgetAssetCache()}）：
     * 缓存里存的是「补之前」的数，补完还照着旧数说「还缺 2835 个」，界面就成了瞎话。
     */
    private static int countMissingAssets(String indexId) {
        Path index = Platform.minecraftDir().resolve("assets").resolve("indexes")
                .resolve(indexId + ".json");
        if (!Files.isRegularFile(index)) {
            return -1;   // 索引都没有，别瞎报数
        }
        long stamp = System.currentTimeMillis();
        AssetStat hit = ASSET_CACHE.get(indexId);
        if (hit != null && stamp - hit.at() < ASSET_CACHE_MS) {
            return hit.missing();
        }
        try {
            Object parsed = Json.parse(Files.readString(index, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map)) {
                return -1;
            }
            Object objects = ((Map<?, ?>) parsed).get("objects");
            if (!(objects instanceof Map)) {
                return -1;
            }
            Path objectsDir = Platform.minecraftDir().resolve("assets").resolve("objects");
            int missing = 0;
            int scanned = 0;
            for (Object raw : ((Map<?, ?>) objects).values()) {
                if (scanned++ >= ASSET_SCAN_LIMIT) {
                    break;
                }
                if (!(raw instanceof Map)) {
                    continue;
                }
                Object hash = ((Map<?, ?>) raw).get("hash");
                if (!(hash instanceof String text) || text.length() < 2) {
                    continue;
                }
                if (!Files.isRegularFile(objectsDir.resolve(text.substring(0, 2))
                        .resolve(text))) {
                    missing++;
                }
            }
            ASSET_CACHE.put(indexId, new AssetStat(stamp, missing));
            return missing;
        } catch (IOException | RuntimeException ex) {
            System.err.println("[launch] 数资源缺不缺时出问题: " + ex);
            return -1;
        }
    }

    // ---------------------------------------------------------------- 参数

    /** 主类：新格式 {@code mainClass}，极老格式兜 {@code net.minecraft.client.Minecraft}。 */
    private static String mainClassOf(VersionJson.Meta meta, Path gameDir) {
        if (meta.mainClass() != null) {
            return meta.mainClass();
        }
        return "net.minecraft.client.Minecraft";
    }

    private static Map<String, String> ctx(String player, String uuid, String accessToken,
                                           String userType, String id, VersionJson.Meta meta,
                                           Path nativesDir, String classpath) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("natives_directory", nativesDir.toString());
        if (classpath != null) {
            map.put("classpath", classpath);
        }
        map.put("launcher_name", "GBL");
        map.put("launcher_version", LauncherApp.VERSION);
        map.put("auth_player_name", player);
        map.put("version_name", id);
        // v0.8：Forge/NeoForge 派生 profile 的 jvm 参数用到这两个令牌（官方 launcher 同款），
        // 之前 ctx 没给，会导致 -DlibraryDirectory / -p 模块路径里的 ${...} 原样残留 → 启动失败
        map.put("library_directory", Platform.minecraftDir().resolve("libraries").toString());
        map.put("classpath_separator", System.getProperty("path.separator"));
        map.put("game_directory", Platform.minecraftDir().toString());
        map.put("assets_root", Platform.minecraftDir().resolve("assets").toString());
        map.put("assets_index_name", meta.assetIndexId() == null ? "" : meta.assetIndexId());
        map.put("auth_uuid", uuid);
        // accessToken / userType 由调用方按账号类型填：
        // 离线 = 空 token + legacy（官方离线档，非空 token 会让 1.8+ 去 sessionserver 验 -> 401）；
        // 外置(little skin) = 登录拿到的真实 token + mojang（单人照常，联机需服务器也认这套 Yggdrasil）。
        map.put("auth_access_token", accessToken);
        map.put("clientid", "0");
        map.put("auth_xuid", "");
        map.put("version_type", "release");
        map.put("auth_type", "mojang");
        map.put("user_type", userType);
        map.put("resolution_width", "1280");
        map.put("resolution_height", "720");
        return map;
    }

    /** 离线 uuid：MC 要 32 位无横线 hex，拿玩家名直接造（官方也是这么算的）。 */
    private static String uuidOf(String player) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(player.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            // MD5 是 JDK 必带的，走不到这儿；真走了就给个占位，别让启动参数断在这
            return "00000000000000000000000000000000";
        }
    }

    /**
     * 展开 {@code arguments} 里的一串参数：先按 {@code rules} 过滤，再替换占位符。
     *
     * <p>{@code value} 可能是数组、单个字符串，或 {@code {rules, value}} 这种带条件的项——
     * 三种都见过，别只认一种（26.3 的 jvm 段三种形态全有）。
     */
    private static void expandList(List<Object> args, List<String> out, Map<String, String> ctx) {
        if (args == null) {
            return;
        }
        for (Object raw : args) {
            flatten(ctx, raw, out);
        }
    }

    /**
     * 把一个参数项摊平成一串命令行参数。
     *
     * <p>三种形态都可能撞上，所以**递归**处理，别只拆第一层：
     * 裸字符串 {@code -Xmx2G}、数组 {@code ["--add-exports", "..."]}、
     * 带条件的 {@code {rules, value: ...}}（value 又可能是数组）。
     * v0.4.7 第一版只拆了两层，{@code {rules=..., value=[...]}} 被 {@code String.valueOf}
     * 摊成了整串地图打印，java 当时就报「不认识的选项」。
     */
    private static void flatten(Map<String, String> ctx, Object raw, List<String> out) {
        if (raw instanceof Map<?, ?> item) {
            if (!allowed(item)) {
                return;   // 条件不满足这一项整条丢
            }
            flatten(ctx, item.get("value"), out);
            return;
        }
        if (raw instanceof List<?> items) {
            for (Object item : items) {
                flatten(ctx, item, out);
            }
            return;
        }
        if (raw != null) {
            out.add(expand(String.valueOf(raw), ctx));
        }
    }

    /** 把 {@code ${xxx}} 换成实际值；认不出的占位符原样留着（方便看出是哪个参数没配套）。 */
    private static String expand(String text, Map<String, String> ctx) {
        if (text == null || !text.contains("${")) {
            return text;
        }
        String out = text;
        for (Map.Entry<String, String> entry : ctx.entrySet()) {
            out = out.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return out;
    }

    // ---------------------------------------------------------------- 小工具

    /** 最新一份崩溃报告的前几行，附在失败原因后面。 */
    private static String newestCrashSummary() {
        Path dir = Platform.minecraftDir().resolve("crash-reports");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (var children = Files.list(dir)) {
            return children.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparingLong(GameLauncher::modified).reversed())
                    .findFirst()
                    .map(path -> {
                        try {
                            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                            return path.getFileName() + "：" + (lines.isEmpty() ? ""
                                    : lines.get(0));
                        } catch (IOException | RuntimeException ex) {
                            return path.getFileName().toString();
                        }
                    })
                    .orElse(null);
        } catch (IOException ex) {
            return null;
        }
    }

    private static void addIfExists(List<Path> out, Path path) {
        if (path == null) {
            return;
        }
        try {
            // 统一成绝对规范路径：同一份 java.exe 经 JAVA_HOME / 自带 JRE / PATH 三条路
            // 进来时文本可能不同，去重才有效；majorOf 的 getParent().getParent() 也更可靠
            Path normalized = path.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized) && !out.contains(normalized)) {
                out.add(normalized);
            }
        } catch (RuntimeException ignored) {
            // 非法路径片段跳过，别让探测挂掉
        }
    }

    private static String javaHome() {
        String home = System.getenv("JAVA_HOME");
        if (home != null && !home.isBlank()) {
            return home;
        }
        // 0.8.6：JAVA_HOME 没设时回退到当前 JVM，别让候选退化成相对路径 bin/java
        String fallback = System.getProperty("java.home", "");
        return fallback == null ? "" : fallback;
    }

    private static String exe(String name) {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? name + ".exe" : name;
    }
}
