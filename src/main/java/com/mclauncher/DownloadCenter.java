package com.mclauncher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通用内容下载任务中心：模组 / 整合包 / 资源包 / 光影 / 世界（也容得下原版游戏）共用一条队列。
 *
 * <p>0.9.0 之前五类内容各下各的，界面上没法统一看进度；更糟的是「下载 Sodium 时进度永远不结束」：
 * 安装体里随便一个异常逃出线程，那一行的状态就永远停在「下载中」。这里把两个问题一起收掉，
 * 几个刻意的选择：
 *
 * <ul>
 * <li><b>和 {@link GameDownloader} 平行，不去改它。</b>后者强绑定原版 client.jar 的分段下载，
 * 让内容安装复用它只会把两件事搅在一起。这里只认 {@link Work}：给我一个能跑、会报进度的执行体，
 * 别管里面是 HTTP、解压还是拷贝。</li>
 * <li><b>异常一律收敛。</b>{@code Work.run} 外面套 try/catch/finally：catch 接 {@code Exception}
 * （含全部 RuntimeException），finally 再兜一次底——只要状态还停在 QUEUED / DOWNLOADING 就强判
 * FAILED。这样连 {@code catch(Exception)} 接不住的 {@code Error} 也不会让界面卡在「下载中」。</li>
 * <li><b>收尾那一帧不节流。</b>进度回调至少隔 {@value #THROTTLE_MS} 毫秒才往外吐一次，但字段是
 * 「先回写、后判断节流」；任务一结束无条件再 emit 一次，最后一帧进度（尤其 100%）绝不会丢。</li>
 * <li><b>取消是「问」出来的，不是「等」出来的。</b>每个 Job 持 {@code cancelled} 标记，
 * {@link Progress#onProgress} 每次先问一句，已取消就抛 {@link CancellationException} 让下载循环
 * 立刻断掉；{@link #remove} 同时 {@code Future.cancel(true)} 打断阻塞中的 IO。</li>
 * <li><b>Sink 是隔离区。</b>{@link Sink#onUpdate} 在下载线程上直接调（不切 FX 线程，由界面自己包
 * {@code Platform.runLater}），任何 Throwable 都被吞掉打印——界面一个空指针不该拖死下载线程。</li>
 * <li><b>同时最多 {@value #MAX_ACTIVE} 个。</b>daemon 固定线程池，排在第 4 个以后的老实等着，
 * 前一个收尾时自动把下一个拉起来。</li>
 * </ul>
 *
 * <p>本类不引用任何 JavaFX 类，是纯服务层；对外统一走 {@link #DEFAULT} 这个单例。
 */
public final class DownloadCenter {

    /** 同时真正在下的任务数，其余排队。0.9.2 r4：4 → 8（对齐 LauncherX 高并发，配合五类内容批量提速）。 */
    public static final int MAX_ACTIVE = 8;
    /**
     * 进度回调至少隔这么久才往外吐一次。
     *
     * <p>模组装一小段就回调一次，一秒几百帧会把 FX 线程按死；但只靠节流会丢掉最后一帧，
     * 所以 {@code finally} 里那次 emit 不受这个限制。
     */
    private static final long THROTTLE_MS = 150L;
    /** 看门狗：连续这么久没收到任何进度帧就判失败，留足「无代理裸直连慢线」的余量。 */
    private static final long STALE_PROGRESS_MS = TimeUnit.MINUTES.toMillis(5);
    /** 看门狗：单个任务整体最长耗时，与 0.9.2 整合包 latch.await(20, MINUTES) 同口径。 */
    private static final long MAX_JOB_MS = TimeUnit.MINUTES.toMillis(20);
    /**
     * 整合包 / 世界专用放宽阈值（0.9.2 r2 引入、r4 把 WORLD 纳入）：这两类是仅有的
     * 「会合法长时间没有任何字节进度帧」的类别。
     *
     * <p>整合包：{@code ModpackImporter.ensureBaseVersion()} 等基础版本最多静默 15 分钟，期间
     * 只调 {@code Listener.onStage()} 报文字、不喂 {@code Progress.onProgress()}；随后
     * {@code downloadFiles()} 的 {@code latch.await(20, MINUTES)} 又可能再花 20 分钟，
     * 合法总耗时可达约 35 分钟。世界（WORLD）的 zip 解压阶段同样存在长静默期，
     * 沿用 5 / 20 分钟判据会把正在解压的世界误判成 FAILED、卡在「下着呢」收敛不掉。
     *
     * <p>整合包内部自带 15 分钟轮询超时 + 20 分钟 latch 超时，两处都会抛 {@code IOException}
     * 由 {@code runJob} 收敛成 FAILED，本来看门狗就不是它的唯一出口；这里只给一个更宽的兜底。
     *
     * <p>其余类别（尤其用户报障的资源包 RESOURCEPACK）仍走 5 / 20 分钟，卡在「下着呢」能及时收敛。
     */
    private static final long MODPACK_STALE_PROGRESS_MS = TimeUnit.MINUTES.toMillis(20);
    private static final long MODPACK_MAX_JOB_MS = TimeUnit.MINUTES.toMillis(45);
    /** 0.9.2 r4：世界 zip 解压有长静默期，阈值与整合包同级放宽（20min / 45min）。 */
    private static final long WORLD_STALE_PROGRESS_MS = TimeUnit.MINUTES.toMillis(20);
    private static final long WORLD_MAX_JOB_MS = TimeUnit.MINUTES.toMillis(45);
    /** 看门狗扫描间隔：5 / 20 分钟级判据，30 秒精度足够，开销可忽略。 */
    private static final long WATCHDOG_PERIOD_MS = TimeUnit.SECONDS.toMillis(30);
    /** 全局递增序号：同名任务也各有各的 id，重复安装 Sodium 会产生两行而不是互相覆盖。 */
    private static final AtomicLong SEQ = new AtomicLong();
    /**
     * 队列的静态影子，给 {@code Snapshot} 自测看，和 {@link GameDownloader#jobIds()} 一个道理：
     * 自测的用例是枚举常量、碰不到实例字段，必须有个静态入口。
     */
    private static Map<String, Job> jobs = new LinkedHashMap<>();

    /** 队列里一行的类别；{@link #label()} / {@link #icon()} 返回中文名与 classpath 图标文件名。 */
    public enum Category {
        GAME("原版游戏", "grass.png"),
        MOD("模组", "mod.png"),
        MODPACK("整合包", "chest.png"),
        RESOURCEPACK("资源包", "resourcepack.png"),
        SHADER("光影", "shader.png"),
        WORLD("世界", "world.png"),
        // 0.9.1：加载器安装不再借 MOD 归类（那样标签显示「模组」、图标是 chest.png）。
        // 默认图标取 forge.png，实际提交时 ShellView 会按 LoaderType.id() 传具体图标。
        LOADER("模组加载器", "forge.png");

        private final String label;
        private final String icon;

        Category(String label, String icon) {
            this.label = label;
            this.icon = icon;
        }

        /** 界面上这一类的中文名。 */
        public String label() {
            return label;
        }

        /** 图标文件名（不是完整 classpath 路径，界面自己拼前缀）。 */
        public String icon() {
            return icon;
        }
    }

    /** 一个任务现在什么状态。界面上那句话就照着它写，不做第二种口径。 */
    public enum State {
        /** 排在后面等位置。 */
        QUEUED,
        /** 正在下。 */
        DOWNLOADING,
        /** 下完落盘了。 */
        DONE,
        /** 执行体抛异常（或异常兜底）后收敛到这里，绝不会停在 DOWNLOADING。 */
        FAILED,
        /** 被移出 / 执行体返回 null / 服务关闭。 */
        CANCELLED
    }

    /**
     * 交给界面的一行快照。每次 emit 都是一份新的，界面整行重画即可。
     *
     * <p>字段做温和归一化：外部（自测 / 界面）直接 new 一个 Item 时传 null 也不会炸。
     */
    public record Item(String id, String title, Category category, String icon,
                       State state, long doneBytes, long totalBytes, String note) {

        /** 字段归一化，避免 null 流进界面渲染。 */
        public Item {
            if (id == null) {
                id = "";
            }
            if (title == null) {
                title = "";
            }
            if (category == null) {
                category = Category.MOD;
            }
            if (state == null) {
                state = State.QUEUED;
            }
            if (icon == null || icon.isBlank()) {
                icon = category.icon();
            }
            if (note == null) {
                note = "";
            }
        }

        /** totalBytes&lt;=0 返回 -1（不确定态），否则 0~100。 */
        public int percent() {
            if (totalBytes <= 0) {
                return -1;
            }
            if (doneBytes <= 0) {
                return 0;
            }
            // 走 double 再 clamp：doneBytes 偶尔会超过 Content-Length（分块/重试重复计数），
            // 也可能大到让 doneBytes * 100 溢出 long，两种都不该让进度条画出 137% 或负数。
            long pct = Math.round((double) doneBytes * 100.0 / (double) totalBytes);
            return (int) Math.max(0L, Math.min(100L, pct));
        }
    }

    /** 任务执行体：返回落盘路径；返回 null 表示用户取消（不算失败）；抛任何异常都算失败。 */
    @FunctionalInterface
    public interface Work {
        /**
         * 真正干活。
         *
         * @param progress 进度出口，下载循环里每读一段就调一次
         * @return 装到哪儿了；null = 用户取消
         * @throws Exception 任何异常都会被任务中心收敛成 {@link State#FAILED}
         */
        Path run(Progress progress) throws Exception;
    }

    /** 进度出口，回调发生在下载线程上（调用方需自行切 FX 线程）。 */
    @FunctionalInterface
    public interface Progress {
        /**
         * 报一次进度。
         *
         * <p>实现里会先检查取消标记：任务已被 {@link #remove} 掉时抛
         * {@link CancellationException}，调用方不必自己判断，只要别吞掉这个异常即可。
         */
        void onProgress(long doneBytes, long totalBytes);
    }

    /** 队列变化的出口。界面实现里请自行包 {@code Platform.runLater}，本类不碰 JavaFX。 */
    @FunctionalInterface
    public interface Sink {
        /** 某一行有了新快照（按 {@link Item#id()} upsert）。 */
        void onUpdate(Item item);
    }

    /**
     * 一个任务的全部可变状态。
     *
     * <p>下载线程写、{@link #items()} / {@link #item(String)} 在别的线程读，所以除 final 字段外
     * 一律 volatile 或 Atomic；这样读快照不必抢锁，也不会看到半新半旧的一行。
     */
    private static final class Job {
        final String id;
        final String title;
        final Category category;
        final String icon;
        final Work work;
        volatile State state = State.QUEUED;
        volatile long doneBytes;
        volatile long totalBytes;
        volatile String note = "排队中";
        /** 上一次往外吐进度的时间，做 {@code 150} 毫秒节流用。 */
        volatile long lastEmitMs;
        /** 最近一次收到进度帧的时间（含被节流挡掉的帧），看门狗据此判「新鲜度」。 */
        volatile long lastProgressMs;
        /** 进入 DOWNLOADING 的时间，看门狗据此判「整体耗时」。 */
        volatile long startedMs;
        /** 取消是跨线程给的：进度回调每次都要问一句。 */
        final AtomicBoolean cancelled = new AtomicBoolean();
        /**
         * 已从队列移除 / 服务已关闭：之后再 emit 也不再打扰界面。
         *
         * <p>为什么需要它：remove 会先发一帧 CANCELLED 让界面收掉那一行，但下载线程可能还要过
         * 一会儿才走到 finally。若 finally 又 emit 一帧，按 id upsert 的界面会把刚删掉的行重新插
         * 回来（「删了又冒出来」）。先发终态、再静音，两边都照顾到。
         */
        volatile boolean silent;
        /** 线程池句柄，remove 时 cancel(true) 打断阻塞中的 IO。 */
        volatile Future<?> future;

        Job(String id, String title, Category category, String icon, Work work) {
            this.id = id;
            this.title = title;
            this.category = category;
            this.icon = icon;
            this.work = work;
        }
    }

    /** 全局这一个。界面、自测都走它，省得实例到处传。 */
    public static final DownloadCenter DEFAULT = new DownloadCenter();

    /** 按排入顺序存全部任务（含 DONE / FAILED，remove 后不再有）。 */
    private final LinkedHashMap<String, Job> list = new LinkedHashMap<>();
    private Sink sink;
    /** shutdown 之后不再接新活；DEFAULT 是单例，关了就关了。 */
    private volatile boolean closed = false;
    /**
     * daemon 固定池：Platform.exit() 之后 JVM 不该被下载线程吊着不退。
     * 线程名统一 {@code gbl-content-dl}，抓线程 dump 时一眼能认出来。
     */
    private final ExecutorService pool = Executors.newFixedThreadPool(MAX_ACTIVE, runnable -> {
        Thread thread = new Thread(runnable, "gbl-content-dl");
        thread.setDaemon(true);
        return thread;
    });
    /**
     * 进度帧新鲜度看门狗：daemon 单线程，命名 {@code gbl-download-watchdog}，随单例只启动一次。
     *
     * <p>只负责把「长时间没有进度帧 / 整体超时」的任务收敛成 FAILED（走既有 {@link #emit}），
     * 绝不 interrupt 工作线程、不 cancel future——Work 仍在原线程同步跑，最终返回时由
     * {@code runJob} 的 guard 保证不把 FAILED 覆盖回 DONE。
     */
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "gbl-download-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    private DownloadCenter() {
        // 单例在 DEFAULT：看门狗随单例只启动一次，绝不每个 submit 起一个线程。
        watchdog.scheduleAtFixedRate(this::sweepStalledJobs,
                WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * {@code Snapshot} 用的静态影子，必须在自测里指一次。
     *
     * <p>和 {@link GameDownloader#jobIds()} 不同之处：这里返回一份拷贝而非活视图——自测遍历
     * Set 的时候下载线程可能正在 submit/remove，活视图会直接 ConcurrentModificationException。
     */
    public static Set<String> jobIds() {
        synchronized (DEFAULT) {
            return new LinkedHashSet<>(jobs.keySet());
        }
    }

    /** 装进度出口；给 null 就是不再要（退出前用）。 */
    public synchronized void setSink(Sink sink) {
        this.sink = sink;
    }

    /**
     * 把一个内容安装任务排进队列：有空位就立刻下，否则排队。
     *
     * <p>id 由 {@code 标题 + "#" + 全局序号} 生成，不拿标题当 key——同名模组连点两次安装
     * 就该是两行；iconHint 空着就退回该类别的默认图标。
     *
     * @return 这个任务现在的快照，界面可以马上画一行
     */
    public synchronized Item submit(String title, Category category, String iconHint, Work work) {
        Objects.requireNonNull(work, "work");
        Category cat = category == null ? Category.MOD : category;
        String name = (title == null || title.isBlank()) ? cat.label() : title;
        String icon = (iconHint == null || iconHint.isBlank()) ? cat.icon() : iconHint;
        String id = name + "#" + SEQ.incrementAndGet();
        Job job = new Job(id, name, cat, icon, work);
        list.put(id, job);
        jobs = list;
        if (closed) {
            // 关服之后还点「安装」：不抛异常、也不留一行永远排队，直接给个终态让界面显示。
            job.state = State.FAILED;
            job.note = "下载服务已关闭";
        } else {
            pump();
        }
        return snapshotOf(job);
    }

    /** 队列里所有任务（按排入顺序）。 */
    public synchronized List<Item> items() {
        List<Item> out = new ArrayList<>(list.size());
        for (Job job : list.values()) {
            out.add(snapshotOf(job));
        }
        return out;
    }

    /** 指定任务现在什么样；没排过（或已被移出）给 null。 */
    public synchronized Item item(String id) {
        Job job = list.get(id);
        return job == null ? null : snapshotOf(job);
    }

    /**
     * 移出队列。正在下的先掐断，这一项就没了（和 {@link GameDownloader#remove} 一个语义）。
     *
     * <p>顺序有讲究：先发一帧 CANCELLED 给界面，再把任务静音，最后才 cancel(true) 打断线程。
     * 这样界面收到的是明确的「已取消」，而不是一行凭空消失、或者稍后被 finally 重新插回来。
     *
     * @return 之前是不是真在队列里
     */
    public synchronized boolean remove(String id) {
        Job job = list.remove(id);
        jobs = list;
        if (job == null) {
            return false;
        }
        job.cancelled.set(true);
        job.state = State.CANCELLED;
        job.note = "已取消安装";
        emit(job);              // 发终态：界面据此收掉这一行
        job.silent = true;      // 此后 finally 的收尾 emit 不再打扰界面
        Future<?> running = job.future;
        if (running != null) {
            running.cancel(true);   // 打断阻塞中的 IO，让线程尽快走到 finally 释放位置
        }
        pump();                 // 若移掉的是还没启动的 QUEUED 项，把后面的拉起来
        return true;
    }

    /** 清空队列：每一项都按「移出」处理，正在下的也掐掉。 */
    public synchronized void clear() {
        // 先统一打上取消标记，避免逐个 remove 时 pump() 把刚要被删的 QUEUED 项白拉起来一次。
        for (Job job : list.values()) {
            job.cancelled.set(true);
        }
        for (String id : new ArrayList<>(list.keySet())) {
            remove(id);
        }
    }

    /** 队列里还有几个在排 / 在下（QUEUED + DOWNLOADING），与 GameDownloader 同口径。 */
    public synchronized int active() {
        int n = 0;
        for (Job job : list.values()) {
            if (job.state == State.QUEUED || job.state == State.DOWNLOADING) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ 调度

    /** 有空位就把排在前面的拉起来；队列每次变动、每个任务收尾都调一次。 */
    private synchronized void pump() {
        if (closed || pool.isShutdown()) {
            return;
        }
        int running = 0;
        for (Job job : list.values()) {
            if (job.state == State.DOWNLOADING) {
                running++;
            }
        }
        for (Job job : list.values()) {
            if (running >= MAX_ACTIVE) {
                break;   // 前面堵着，后面的乖等着——这就是「队列」和「一次性全开」的区别
            }
            if (job.state != State.QUEUED || job.cancelled.get()) {
                continue;
            }
            running++;
            long start = System.currentTimeMillis();
            job.state = State.DOWNLOADING;
            job.note = "下载中…";
            job.startedMs = start;       // 整体 20 分钟计时起点（从被拉起算）
            job.lastProgressMs = start;  // 新鲜度起点：还没收到任何进度帧，从这一刻开始等
            try {
                // 只在这里提交；Work.run 绝不在这把锁里跑（否则界面查队列会被下载卡住）。
                job.future = pool.submit(() -> execute(job));
            } catch (RuntimeException ex) {
                // 池子被关掉等极端情况：别让这一项永远停在 QUEUED。
                job.state = State.FAILED;
                job.note = "下载服务已关闭：" + describe(ex);
                System.err.println("[content-dl] 「" + job.id + "」提交失败: " + ex);
                emit(job);
            }
        }
    }

    /**
     * 最外层闸门：连 {@code runJob} 的兜底逻辑自己出了岔子（例如 Error），也保证状态收敛、
     * 队列继续往下走，异常绝不逃出线程。
     */
    private void execute(Job job) {
        try {
            runJob(job);
        } catch (Throwable unexpected) {
            synchronized (this) {
                job.state = State.FAILED;
                job.note = "任务异常终止：" + describe(unexpected);
                job.future = null;
            }
            System.err.println("[content-dl] 「" + job.id + "」收尾兜底: " + unexpected);
            emit(job);
            synchronized (this) {
                pump();
            }
        }
    }

    /**
     * 一个任务的完整生命周期：标记开始 → 跑执行体 → 收敛终态 → 无条件发最后一帧 → 拉起下一个。
     */
    private void runJob(Job job) {
        final Progress progress = (doneBytes, totalBytes) -> {
            // 取消优先：先问标记再干活。已取消就抛专用异常让下载循环立刻断掉，
            // 不必等一个大文件块读完才发现白下了。
            if (job.cancelled.get()) {
                throw new CancellationException("已取消：" + job.title);
            }
            // 先无条件回写字段、再判断节流：即便这一帧被 150ms 挡掉，
            // 下面 finally 那次 emit 也能带上最新的 done/total，最后一帧进度不会丢。
            job.doneBytes = doneBytes;
            job.totalBytes = totalBytes;
            long now = System.currentTimeMillis();
            job.lastProgressMs = now;   // 每收到一帧就刷新新鲜度（含被 150ms 节流挡掉的帧）
            if (now - job.lastEmitMs >= THROTTLE_MS) {
                job.lastEmitMs = now;
                emit(job);
            }
        };

        try {
            synchronized (this) {
                if (job.cancelled.get()) {
                    job.state = State.CANCELLED;
                    job.note = "已取消安装";
                    return;              // 交给 finally 统一收尾、拉起下一个
                }
                job.state = State.DOWNLOADING;
                job.note = "下载中…";
            }
            emit(job);                   // 「开始下了」这一帧不受节流影响
            Path path = job.work.run(progress);
            synchronized (this) {
                if (job.cancelled.get() || path == null) {
                    // 返回 null 是调用方自己决定取消，不算失败；被 remove 掉的也一样。
                    job.state = State.CANCELLED;
                    job.note = "已取消安装";
                } else if (job.state == State.FAILED || job.state == State.CANCELLED) {
                    // 看门狗（或移出）已把这一行收敛成终态：Work 虽最终跑完，也绝不把
                    // FAILED / CANCELLED 覆盖回 DONE，否则用户会看到「失败后又变成功」的抖动。
                    // 终态与文案保持原样，交给 finally 发最后一帧。
                } else {
                    job.state = State.DONE;
                    Path name = path.getFileName();
                    job.note = "装好了 · " + (name == null ? path.toString() : name.toString());
                }
            }
        } catch (Exception ex) {
            // 任何 Exception（含全部 RuntimeException、以及我们自己的 CancellationException）
            // 都必须落到一个终态上，绝不允许逃出线程让界面永远停在「下载中」。
            synchronized (this) {
                if (job.cancelled.get() || ex instanceof CancellationException) {
                    job.state = State.CANCELLED;
                    job.note = "已取消安装";
                } else {
                    job.state = State.FAILED;
                    job.note = describe(ex);
                }
            }
            System.err.println("[content-dl] 「" + job.id + "」失败: " + ex);
        } finally {
            // 最后一道保险：万一 try 里抛的是 Error（catch(Exception) 接不住），或者执行体
            // 用别的路径提前跳出，状态还停在 QUEUED / DOWNLOADING 就是 0.9.0 的老毛病，
            // 这里强制收敛成 FAILED。
            synchronized (this) {
                if (job.state == State.QUEUED || job.state == State.DOWNLOADING) {
                    job.state = State.FAILED;
                    job.note = "任务异常终止（未收到结束信号）";
                }
            }
            // 收尾这一次不受 150ms 节流限制，无条件 emit 最终态。
            // emit 内部对已 remove / shutdown 的 silent 任务会跳过，避免删掉的行复活。
            emit(job);
            synchronized (this) {
                job.future = null;
                pump();              // 空出位置，把后面排队的拉起来
            }
        }
    }

    /**
     * 0.9.2 r2 兜底：定时把「卡住」的任务收敛成 FAILED，避免队列永远挂在 DOWNLOADING /
     * 界面永远显示「下着呢」。判据是进度帧新鲜度，不是瞬时字节速率——无代理裸直连慢线只要
     * 持续有帧就一直留着；只有完全收不到帧（5 分钟）或整体超时（20 分钟）才动手。
     *
     * <p>只收敛 UI 状态，不动工作线程：不 interrupt、不 cancel future、不 pump。
     * Work 最终返回时由 {@link #runJob} 的 guard 保证失败态不被覆盖回 DONE；用户「移出」
     * 仍由 CancellationException 收敛成 CANCELLED，本方法对非 DOWNLOADING 状态一律跳过。
     */
    private void sweepStalledJobs() {
        try {
            List<Job> toEmit = new ArrayList<>();
            long now = System.currentTimeMillis();
            synchronized (this) {
                for (Job job : list.values()) {
                    // 只碰仍在下载、且没被用户移出 / 没被 shutdown 静音的任务；
                    // 任何终态（DONE / FAILED / CANCELLED）一律跳过，绝不覆盖。
                    if (job.state != State.DOWNLOADING || job.cancelled.get() || job.silent) {
                        continue;
                    }
                    // 0.9.2 r4：整合包 / 世界按类别放宽（等基础版本 / zip 解压阶段只有文字心跳、
                    // 没有字节进度帧是合法的）；其余类别仍走通用 5 / 20 分钟及时收敛。
                    boolean modpack = job.category == Category.MODPACK;
                    boolean world = job.category == Category.WORLD;
                    long staleMs = modpack ? MODPACK_STALE_PROGRESS_MS
                            : world ? WORLD_STALE_PROGRESS_MS : STALE_PROGRESS_MS;
                    long maxMs = modpack ? MODPACK_MAX_JOB_MS
                            : world ? WORLD_MAX_JOB_MS : MAX_JOB_MS;
                    boolean starved = now - job.lastProgressMs > staleMs;
                    boolean overrun = now - job.startedMs > maxMs;
                    if (starved || overrun) {
                        job.state = State.FAILED;
                        job.note = starved
                                ? "下载超时：长时间没有收到任何数据，请检查网络或切换线路后重试"
                                : "下载超时：整体耗时过长，请检查网络或切换线路后重试";
                        toEmit.add(job);
                    }
                }
            }
            // emit 会调界面代码，绝不在上面的锁里调；锁外逐帧走既有通知出口。
            for (Job job : toEmit) {
                emit(job);
            }
        } catch (Throwable unexpected) {
            // 一次扫描出错不能让看门狗整个停摆（否则又回到「永远挂着」）。
            System.err.println("[content-dl] 看门狗扫描出错（已忽略）: " + unexpected);
        }
    }

    // ------------------------------------------------------------------ 出口

    /**
     * 发一帧快照给界面。
     *
     * <p>刻意做成「隔离区」：Sink 是界面代码，它抛的任何 Throwable 都在这里被吞掉打印，
     * 绝不能顺着调用栈回到下载线程把线程弄死（那样又会出现一行永远的「下载中」）。
     * 也不切 FX 线程——是否 {@code Platform.runLater} 由 ShellView 自己决定。
     */
    private void emit(Job job) {
        if (job.silent) {
            return;
        }
        Sink target;
        synchronized (this) {
            // 二次检查：remove / shutdown 是在同一把锁里把 silent 置 true 的。
            // 若它们先跑完，这里必然看得见，收尾帧就不会把刚删掉的行重新插回界面。
            if (job.silent) {
                return;
            }
            target = sink;
        }
        if (target == null) {
            return;
        }
        Item item = snapshotOf(job);
        try {
            target.onUpdate(item);
        } catch (Throwable ex) {
            System.err.println("[content-dl] 进度回调出错（已忽略）: " + ex);
        }
    }

    /** 从 volatile 字段现取一份快照，不抢锁也能拿到自洽的一行。 */
    private static Item snapshotOf(Job job) {
        return new Item(job.id, job.title, job.category, job.icon, job.state,
                job.doneBytes, job.totalBytes, job.note);
    }

    /** 异常说明：有消息用消息，没有就退回滚类名，别让 note 变成 null 或空串。 */
    private static String describe(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    /**
     * 收尾用的：等正在下的那些真的停了（退出 / 自测关窗口前调一下）。
     *
     * <p>每个未终态的任务都会收到一帧 CANCELLED，界面不会留着「下载中」的行；
     * 之后 DEFAULT 不再接新活（线程池已关），属于终止性操作。
     */
    public void shutdown() {
        List<Job> all;
        synchronized (this) {
            closed = true;
            all = new ArrayList<>(list.values());
        }
        for (Job job : all) {
            boolean changed;
            synchronized (this) {
                job.cancelled.set(true);
                changed = job.state == State.QUEUED || job.state == State.DOWNLOADING;
                if (changed) {
                    job.state = State.CANCELLED;
                    job.note = "下载服务已关闭";
                }
                Future<?> running = job.future;
                if (running != null) {
                    running.cancel(true);
                }
            }
            if (changed) {
                emit(job);
            }
            job.silent = true;
        }
        pool.shutdownNow();
        watchdog.shutdownNow();   // 看门狗一并停掉，关闭后不残留 daemon 调度线程
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                System.err.println("[content-dl] 下载线程池没收干净");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
