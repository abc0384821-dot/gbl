package com.mclauncher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 0.9.8 r6：进程层单实例，修掉「反复运行 GBL.exe 会在通知区累积多个图标」。
 *
 * <p>根因：每个 GBL.exe 都是新 JVM，各自 {@code TraySupport.icon == null}，各自往系统托盘
 * add() 一枚图标；老进程因为 {@code setImplicitExit(false)} + 「关闭到托盘」仍活着。而
 * {@link java.awt.SystemTray#getTrayIcons()} 只返回「本 application」通过 add() 注册的
 * TrayIcon（JDK Javadoc 原文 "You can't access the icons added by another application"），
 * remove() 传别进程的对象是 no-op —— 托盘层面无解，只能在进程层面拦住第二个进程。
 *
 * <p>方案：首实例持有一把 {@link FileLock}（互斥的唯一权威），并在 127.0.0.1 的随机端口
 * 上开一条本地唤醒通道；第二个进程拿不到锁时，把「把主窗口叫出来」通过 magic token
 * 转交首实例，然后自己直接退出，绝不建窗口、绝不进 JavaFX、绝不挂托盘图标。
 *
 * <p><b>同一用户会话全局单例</b>：锁文件与端口文件固定放在用户目录 {@code ~/.gbl/}
 * （{@code gbl-single-instance.lock} / {@code gbl-single-instance.port}），不按安装目录
 * 派生哈希。用户验收是「任意时刻托盘里至多只有一枚 GBL 图标」：按目录隔离时，机器上
 * 同时存在两份 GBL 目录就会各起一个进程、又是两枚图标；微信 / QQ / Discord / Steam
 * 也都是同一用户会话全局只跑一个进程。逃生口是 {@link #RESTART_ARG}（自动更新重启），
 * 不需要靠目录隔离。放用户目录还保证可写，且不会被增量更新包覆盖。
 *
 * <p><b>互斥权威只能是 FileLock</b>：ServerSocket 只是唤醒通道，bind 失败只记日志，
 * 绝不影响首实例启动。锁相关对象一律保存在 static 强引用里，绝不放进 try-with-resources
 * 或长期只以局部变量存在 —— 被 GC / close 掉会让锁形同虚设。
 */
public final class SingleInstance {

    /** 0.9.8 r6 指纹：构建门禁扫 runtime/lib/modules 常量池，零引用也留在常量池，勿改这串字。 */
    public static final String FP_098_R6_TRAY = "0.9.8 r6 单实例文件锁与本地唤醒";
    static { System.err.println("[tray] " + FP_098_R6_TRAY); }

    /**
     * 0.9.8 r6 自动更新重启标记：main() 与 launchDetached() 都引用本常量，不各写字符串字面量。
     * static final String 编译期字面量会进 class 常量池，供构建门禁按字节扫描（第三条 r6 指纹）。
     */
    public static final String RESTART_ARG = "--gbl-restart";

    /** 唤醒协议：第二个进程连上后只写这一行，首实例校验通过才唤回主窗口。 */
    private static final String MAGIC = "GBL-SHOW-098R6";

    private static final int CONNECT_TIMEOUT_MS = 500;
    private static final int SO_TIMEOUT_MS = 500;
    private static final int PORT_RETRIES = 10;
    private static final long PORT_RETRY_INTERVAL_MS = 100L;
    private static final int RESTART_RETRIES = 10;
    private static final long RESTART_RETRY_INTERVAL_MS = 100L;

    /** tryAcquireOnce() 的三种结果，避免在 IO 故障与「已有实例」之间误判。 */
    private static final int ACQUIRED = 1;
    private static final int HELD = 0;
    private static final int ERROR = -1;

    // 全局 static 强引用：锁、通道、唤醒 socket 绝不能被 GC，也不能被提前 close。
    private static FileChannel lockChannel;
    private static FileLock fileLock;
    private static ServerSocket wakeSocket;
    private static Thread wakeThread;

    private static Path lockPath;
    private static Path portPath;

    /** 唤醒通道是否已关闭：必须先置标志再关 socket，否则 acceptLoop 会空转打满 CPU。 */
    private static volatile boolean closed = false;
    /** 统一退出收口是否已走过，保证 shutdown() 幂等。 */
    private static volatile boolean shutdownDone = false;

    private SingleInstance() {
        // 静态工具类，别让人 new 出来
    }

    /**
     * 进程入口检测。
     *
     * @return true 表示本进程是首实例（或检测不可用要放行），调用方继续 launch；
     *         false 表示已有实例在跑，唤醒请求已转交，调用方必须立即返回、绝不进 JavaFX
     */
    public static boolean acquireOrForward() {
        int result = tryAcquireOnce();
        if (result == ACQUIRED) {
            registerShutdownHook();
            return true;
        }
        if (result == HELD) {
            // 只转交一次「叫出主窗口」；无论成功失败都静默，绝不把异常抛到上层。
            forwardShowToFirstInstance();
            return false;
        }
        // 锁目录不可写 / 锁文件打不开：宁可多一个图标，
        // 也绝不能因为检测本身出问题让启动器起不来。
        System.err.println("[single] 单实例检测不可用，按首实例照常启动（不阻断启动）");
        return true;
    }

    /**
     * 自动更新重启专用：旧进程此刻还持锁，绝不能走「转发后退出」那条路。
     *
     * <p>最多重试 1 秒（10 次 × 100ms）等旧进程释放锁；拿到锁就正常成为首实例并重建
     * 唤醒通道，避免「更新后的新进程没登记为单实例，第三个 GBL.exe 又能起第二个窗口」。
     * 等不到就按首实例直接启动（不转发、不退出），保证更新后一定能起来。
     */
    public static void acquireForRestart() {
        for (int i = 0; i < RESTART_RETRIES; i++) {
            int result = tryAcquireOnce();
            if (result == ACQUIRED) {
                registerShutdownHook();
                return;
            }
            if (result == ERROR) {
                break;
            }
            try {
                Thread.sleep(RESTART_RETRY_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.err.println("[single] 自动更新重启：旧进程仍持锁，按首实例直接启动（不转发、不退出）");
    }

    /** 幂等释放：进程退出时 OS 本来也会自动释放，这里只是把唤醒 socket 一并收干净。 */
    public static void release() {
        // 先置关闭标志，再关 socket；被 shutdown hook 直接调用时 acceptLoop 也能立刻退出。
        closed = true;
        try {
            if (fileLock != null) {
                fileLock.release();
            }
        } catch (Throwable ignored) {
            // 退出路径上抛异常没有意义
        }
        fileLock = null;
        closeChannelQuietly();
        try {
            if (wakeSocket != null) {
                wakeSocket.close();
            }
        } catch (Throwable ignored) {
            // 同上
        }
        wakeSocket = null;
    }

    /**
     * 0.9.8 r6 统一退出收口：三条真退出路径与两个 shutdown hook 共调这一个符号。
     *
     * <p>javafx.application.Platform.exit() 只停 JavaFX，若 JVM 被非 daemon 线程
     * （AWT EDT / 仍在系统托盘里的 TrayIcon）吊住，shutdown hook 根本不会跑 ——
     * 文件锁不释放、FX 已停、唤醒无人接，用户再双击 GBL.exe 就成了「点了完全没反应」。
     * 所以必须在退出路径上显式释放，hook 只是补充兜底。
     *
     * <p>按序：先置 closed（让 acceptLoop 退出，避免 socket 关闭后空转打满 CPU）→
     * 摘掉常驻托盘图标 → 释放锁 / 通道 / 唤醒 socket → 打断 accept 线程。
     * 全程幂等、null 安全、异常吞掉；允许在没调用过 acquireOrForward() 或
     * --gbl-restart 跳过检测时被调用。
     */
    public static void shutdown() {
        if (shutdownDone) return;
        shutdownDone = true;
        closed = true;                       // ★ 先置标志，再关端口
        try { TraySupport.dispose(); } catch (Throwable ignored) {}
        release();                           // 幂等：fileLock → channel → wakeSocket
        Thread t = wakeThread;
        if (t != null) t.interrupt();
    }

    /**
     * 真正拿一次锁。成功时把 {@link #lockChannel} / {@link #fileLock} 留在 static 强引用里，
     * 并启动唤醒服务；锁被别人持有时只关掉本进程刚打开的 channel，不动首实例的任何东西。
     */
    private static int tryAcquireOnce() {
        try {
            // preparePaths() 已不再抛受检异常（全局单例固定路径）；这一层 try 只需兜住
            // createDirectories / FileChannel.open 的 IO 故障，任何异常都放行启动。
            preparePaths();
            Files.createDirectories(lockPath.getParent());
            // 注意：不用 try-with-resources。这个 channel 必须在首实例整个生命周期里活着。
            lockChannel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (Throwable ex) {
            System.err.println("[single] 锁文件打不开，按首实例照常启动: " + ex);
            return ERROR;
        }

        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
        } catch (Throwable ex) {
            // 某些平台不支持文件锁 / 同一 JVM 重叠上锁（OverlappingFileLockException）：
            // 这是检测能力问题，不是「已有实例」，放行启动。
            System.err.println("[single] tryLock 不可用，按首实例照常启动: " + ex);
            return ERROR;
        }

        if (acquired == null) {
            // 已有实例：本进程不持锁，关掉自己刚打开的 channel 即可（首实例的锁不受影响）。
            closeChannelQuietly();
            return HELD;
        }

        // 全局强引用：绝不能放局部变量 / try-with-resources，否则 GC 或 close 会让锁失效。
        fileLock = acquired;
        startWakeServer();   // 内部 bind 失败只记日志，绝不抛到这里、绝不影响返回首实例
        return ACQUIRED;
    }

    /** 首实例：随机端口 + daemon accept 线程。bind 失败绝不影响首实例启动。 */
    private static void startWakeServer() {
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            // 随机端口：固定端口可能被第三方占用，那会让首实例 bind 失败；
            // 这里 bind 失败也只记日志，因为互斥的唯一权威是 FileLock。
            server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            wakeSocket = server;   // 全局强引用

            // 覆盖写、零删除：第二个进程读这个端口来转发「叫出主窗口」。
            try {
                Files.writeString(portPath, Integer.toString(server.getLocalPort()),
                        StandardCharsets.UTF_8);
            } catch (Throwable ex) {
                System.err.println("[single] 唤醒端口写不进文件（不影响首实例运行）: " + ex);
            }

            wakeThread = new Thread(SingleInstance::acceptLoop, "gbl-single-wake");
            wakeThread.setDaemon(true);   // 后台线程必须 daemon，否则 Platform.exit() 后吊住 JVM
            wakeThread.start();
        } catch (Throwable ex) {
            System.err.println("[single] 本地唤醒通道 bind 失败（绝不影响首实例启动）: " + ex);
            // 端口文件里可能还是上一轮留下的旧端口；覆盖写空，避免后来进程连到无关服务。
            try {
                if (portPath != null) {
                    Files.writeString(portPath, "", StandardCharsets.UTF_8);
                }
            } catch (Throwable ignored) {
                // 写不上也不致命
            }
        }
    }

    /**
     * 唤醒监听：只投递 Runnable，绝不碰 Stage 或任何 JavaFX Node。
     * 单条连接出错一律 catch Throwable 继续 accept，绝不因一次异常退出监听。
     */
    private static void acceptLoop() {
        while (!closed && wakeSocket != null && !wakeSocket.isClosed()) {
            try (Socket client = wakeSocket.accept()) {
                client.setSoTimeout(SO_TIMEOUT_MS);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                String line = reader.readLine();
                String token = line == null ? "" : line.trim();
                if (MAGIC.equals(token)) {
                    // 由 javafx.application.Platform.runLater 切回 FX 线程调用；
                    // 本类在 com.mclauncher 包内，Platform 必须写全限定，裸写会被解析成
                    // com.mclauncher.Platform 导致编译失败。
                    javafx.application.Platform.runLater(LauncherApp::showMainWindow);
                }
            } catch (Throwable ex) {
                if (closed || wakeSocket == null || wakeSocket.isClosed()) {
                    return;   // 已关闭：正常收尾，退出监听，绝不空转
                }
                // 一次连接异常（含 FX 工具包尚未初始化时的 IllegalStateException）不影响后续
                System.err.println("[single] 唤醒连接处理失败，继续监听: " + ex);
            }
        }
    }

    /**
     * 第二个进程：读端口 → 连本机 → 写一行 magic token → 静默关闭。
     * 无论成功失败都不抛异常到上层；调用方拿到 false 后直接退出。
     */
    private static void forwardShowToFirstInstance() {
        int port = readPortWithRetry();
        if (port <= 0) {
            System.err.println("[single] 已有实例在跑但没拿到唤醒端口，本进程直接退出");
            return;
        }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                    CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(SO_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write((MAGIC + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable ignored) {
            // 首实例可能正在退出 / 端口已被别的服务占用：静默，绝不抛到上层
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
                // 同上
            }
        }
    }

    /** 端口文件可能还没被首实例写完，最多重试 10 次 × 100ms；文件不存在 / 内容非法都重试。 */
    private static int readPortWithRetry() {
        for (int i = 0; i < PORT_RETRIES; i++) {
            try {
                if (Files.isRegularFile(portPath)) {
                    String text = Files.readString(portPath, StandardCharsets.UTF_8).trim();
                    int port = Integer.parseInt(text);
                    if (port > 0 && port <= 65535) {
                        return port;
                    }
                }
            } catch (Throwable ignored) {
                // 文件不存在 / 内容非法：继续重试
            }
            if (i < PORT_RETRIES - 1) {
                try {
                    Thread.sleep(PORT_RETRY_INTERVAL_MS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return 0;
                }
            }
        }
        return 0;
    }

    /** 全局单例：锁与端口文件固定在用户目录 ~/.gbl/，同一用户会话只跑一个 GBL 进程。 */
    private static void preparePaths() {
        Path dir = Paths.get(System.getProperty("user.home"), ".gbl");
        lockPath = dir.resolve("gbl-single-instance.lock");
        portPath = dir.resolve("gbl-single-instance.port");
    }

    /** 只关本进程打开的 channel；不碰首实例持有的锁。 */
    private static void closeChannelQuietly() {
        try {
            if (lockChannel != null) {
                lockChannel.close();
            }
        } catch (Throwable ignored) {
            // 关不掉也不影响退出
        }
        lockChannel = null;
    }

    /** 退出时补一次统一收口；进程真退出时 OS 也会自动释放文件锁。 */
    private static void registerShutdownHook() {
        try {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(SingleInstance::shutdown, "gbl-single-release"));
        } catch (Throwable ignored) {
            // 关机钩子注册不上也没关系，OS 会在进程结束时回收锁
        }
    }
}
