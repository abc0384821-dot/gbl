package com.mclauncher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 进程层：所有跟操作系统沾边的差异都收口在这一个类里。
 *
 * <p>上层的 {@link GameScanner} 只说「删除 / 打开 / 找游戏目录」，不自己拼平台路径，
 * 也不自己决定「删除」到底落到哪——那三件事在这里按各自系统的规矩做掉，
 * 换平台时只动这一个文件。
 *
 * <p>三个方法都可能抛 {@link IOException}，也可能被安全策略拦下（这台机器上
 * {@code reg.exe} 就拦过），调用方一律当「操作没做成」处理，不要当成意外崩掉。
 */
public final class Platform {

    /** 日期在界面上长这样：2025-06-17。含时分秒对游戏列表没用，只会让列变宽。 */
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private Platform() {
        // 静态工具类，别让人 new 出来
    }

    /**
     * Minecraft 的游戏根目录。
     *
     * <p><b>便携模式（v0.7+）</b>：GBL 自己的游戏数据独立存放在 <code>GBL.exe</code>
     * 同目录下的 <code>.minecraft</code>，<strong>不再自动从系统迁数据</strong>。
     * 新用户拿到的是干净的空目录；已有用户的数据已经在第一次运行时落在这里。
     * 目录取「启动器可执行文件所在目录」下的 {@code .minecraft}：
     * jpackage 会设 {@code jpackage.app-path} 为 exe 全路径，取其父目录即可；
     * 开发/自测直接用 {@code java} 跑时退到 {@code java.application.home}，再退到工作目录。
     */
    public static Path minecraftDir() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            return Paths.get(appPath).getParent().resolve(".minecraft");
        }
        String appHome = System.getProperty("java.application.home");
        if (appHome != null && !appHome.isBlank()) {
            return Paths.get(appHome).resolve(".minecraft");
        }
        return Paths.get("").toAbsolutePath().resolve(".minecraft");
    }

    /**
     * 启动器可执行文件所在目录（便携根目录）。
     *
     * <p>jpackage 下 {@code jpackage.app-path} 是 exe 全路径，取父目录就是 {@code GBL/} 便携根；
     * 自动更新要往它的同级建一个新目录、并复制它的 {@code .minecraft}，所以单独收一个入口。
     * 开发/自测直接用 {@code java} 跑时退到 {@code java.application.home}，再退到工作目录。
     */
    public static Path appDir() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            return Paths.get(appPath).getParent();
        }
        String appHome = System.getProperty("java.application.home");
        if (appHome != null && !appHome.isBlank()) {
            return Paths.get(appHome);
        }
        return Paths.get("").toAbsolutePath();
    }

    /**
     * 在系统的文件管理器里定位到某个路径（Windows 资源管理器选中、macOS Finder 揭示）。
     *
     * <p>路径不存在时打一条警告就返回：界面上「查看游戏文件夹」点了没反应，
     * 原因多半是那个游戏根本没装完整，比弹一个错误框更让人明白。
     */
    public static void reveal(Path path) {
        if (path == null || !Files.exists(path)) {
            System.err.println("[platform] 要打开的路径不存在，跳过: " + path);
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                // /select, 后面必须跟逗号且路径加引号：少了逗号会打开资源管理器根目录
                new ProcessBuilder("explorer.exe", "/select,", "\"" + path + "\"")
                        .start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", "-R", path.toString()).start();
            } else {
                new ProcessBuilder("xdg-open", path.toString()).start();
            }
        } catch (IOException | RuntimeException ex) {
            System.err.println("[platform] 打开文件管理器失败: " + ex);
        }
    }

    /**
     * 把一整个目录（或单个文件）移进回收站，而不是直接删掉。
     *
     * <p>Windows 走 .NET 的 {@code FileSystem.DeleteDirectory/DeleteFile(..., SendToRecycleBin)}：
     * 零依赖、真正进系统回收站、误删了还能在回收站里捡回来，比自己搬到一个隐藏目录强。
     * 其它系统没有能无条件调用的 API，退到 GBL 自己的软回收站 {@code ~/.gbl/trash/<时间戳>/}。
     *
     * <p>目录与单文件分流：VB 两兄弟方法名不一样， DeleteDirectory 喂文件路径会报
     * 「不是目录」，DeleteFile 喂目录会报「是目录」。卸载 mod（单文件）与删游戏（目录）
     * 都走这一个入口，谁也别自己去拼命令。
     *
     * @return 移进去了返回 true；没能移（盘只读、被占用、命令挂了）返回 false
     */
    public static boolean recycle(Path path) {
        if (path == null || !Files.exists(path)) {
            return false;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return recycleToSoftTrash(path);
        }
        // 非目录走 DeleteFile，目录走 DeleteDirectory；判不准（链接/异常）时按目录兜底，
        // 旧行为（删游戏整目录）不受影响
        boolean directory;
        try {
            directory = Files.isDirectory(path);
        } catch (RuntimeException ex) {
            directory = true;
        }
        return directory ? recycleViaRecycleBin(path) : recycleFileViaRecycleBin(path);
    }

    /**
     * Windows：PowerShell 调 VB 的 FileSystem.DeleteDirectory。
     *
     * <p><b>只能写三参重载 {@code (dir, UIOption, RecycleOption)}。</b>
     * 四参那个重载的第四参是 {@code UICancelOption}，可选值只有 {@code DoNothing} 和
     * {@code ThrowException}——写 {@code 'Default'}、'Continue' 都是我们自己编出来的名字，
     * 命令会 exit=1 报「Cannot convert argument "onUserCancel"」收场：回收站没进、
     * 目录原样还在，界面上看着就是「点了删除什么都没发生」（v0.4.2 用户报的
     * 「游戏列表删不了游戏」就是这个）。三参那个才是真把目录扔进回收站的版本。
     */
    private static boolean recycleViaRecycleBin(Path path) {
        // 路径里的单引号要写成两个，否则 PowerShell 会把截断后的半截路径当参数
        String quoted = "'" + path.toString().replace("'", "''") + "'";
        String command = "Add-Type -AssemblyName Microsoft.VisualBasic;"
                + "[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory(" + quoted
                + ", 'OnlyErrorDialogs', 'SendToRecycleBin')";
        try {
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", command)
                    .redirectErrorStream(true)
                    .start();
            // 等它自己收尾，但别让界面干等：回收站操作一般几百毫秒，4 秒足够
            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            if (process.exitValue() != 0) {
                // 非 0 就是没送进去，也别回 false 让上层卡在「删不掉」：
                // 下面那层软回收站至少保证目录没被彻底销毁（v0.4.2）
                System.err.println("[platform] 回收站调用返回 " + process.exitValue()
                        + "，退到软回收站: " + path);
                return recycleToSoftTrash(path);
            }
            return true;
        } catch (IOException | RuntimeException ex) {
            System.err.println("[platform] 回收站调用失败，退到软回收站: " + ex);
            return recycleToSoftTrash(path);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Windows：PowerShell 调 VB 的 FileSystem.DeleteFile（单文件版）。
     *
     * <p>与 {@link #recycleViaRecycleBin} 同一套三参重载规矩：{@code 'OnlyErrorDialogs'}
     * 只弹错误框、{@code 'SendToRecycleBin'} 才真进回收站。卸载 mod 这种单文件删除走这条，
     * 用户在回收站里还能把误卸的 mod 捡回来。同样挂/超时都退软回收站兜底。
     */
    private static boolean recycleFileViaRecycleBin(Path path) {
        // 路径里的单引号要写成两个，否则 PowerShell 会把截断后的半截路径当参数
        String quoted = "'" + path.toString().replace("'", "''") + "'";
        String command = "Add-Type -AssemblyName Microsoft.VisualBasic;"
                + "[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile(" + quoted
                + ", 'OnlyErrorDialogs', 'SendToRecycleBin')";
        try {
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", command)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            if (process.exitValue() != 0) {
                System.err.println("[platform] 回收站(文件)调用返回 " + process.exitValue()
                        + "，退到软回收站: " + path);
                return recycleToSoftTrash(path);
            }
            return true;
        } catch (IOException | RuntimeException ex) {
            System.err.println("[platform] 回收站(文件)调用失败，退到软回收站: " + ex);
            return recycleToSoftTrash(path);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 非 Windows：搬到 ~/.gbl/trash/ 下留一份，位置写进日志，至少不是彻底没了。 */
    private static boolean recycleToSoftTrash(Path path) {
        try {
            Path trashHome = Paths.get(System.getProperty("user.home"), ".gbl", "trash");
            Files.createDirectories(trashHome);
            Path target = trashHome.resolve(
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
            Files.move(path, target);
            System.err.println("[platform] 已移到软回收站: " + target);
            return true;
        } catch (IOException | RuntimeException ex) {
            System.err.println("[platform] 软回收站也搬不动: " + ex);
            return false;
        }
    }

    /** 把某个时间格式化成界面用的「2025-06-17」。 */
    public static String formatDate(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime()
                .format(DATE);
    }
}
