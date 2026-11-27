package com.mclauncher;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Windows 开机自启控制：直接写 {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Run} 下的
 * {@code GBL} 值，让登录后正常显示主窗口并挂托盘。
 *
 * <p>0.9.6 r3 起一律用 {@code C:\Windows\System32\reg.exe} 操作真实 Run 键。
 * 旧实现误以为 Java 偏好 API 的 {@code userRoot()} 能映射到 HKCU\Run：其 {@code node()} 层级分隔符是
 * {@code /} 而非 {@code \}，整串反斜杠被当成单个节点名再编码，数据全落到
 * {@code HKCU\Software\JavaSoft} 下偏好树生成的假子键里，Windows 登录时读取的 Run 键始终为空；
 * 且 {@code put()} 正常不抛异常，令「写不动就回落 reg.exe」的分支永不触发，开关看似打开实则什么都没写。
 * 所有原生调用都包 try/catch，失败只打日志、不抛——设置开关不该因注册表权限把界面卡死。
 *
 * <p>0.9.6 r6：Run 值不再带 {@code --silent}（开机自启也正常显示主窗口）；启动时
 * {@link #refreshIfStale()} 只自愈历史遗留的带 {@code --silent} 旧值，幂等不重复写注册表。
 */
public final class BootAutostart {

    /** 0.9.6 r6 指纹：开机自启 Run 值自愈。构建门禁扫常量池，勿改这串字。 */
    public static final String FP_096_R6_BOOT = "0.9.6 r6 自启Run值自愈";
    static { System.err.println("[boot] " + FP_096_R6_BOOT); }

    /** Run 键里本启动器占用的那个值名。 */
    public static final String RUN_VALUE_NAME = "GBL";

    /** 旧版开机自启参数（0.9.6 r6 起不再写入 Run 值），仅用于判定并自愈历史旧值。 */
    private static final String SILENT_ARG = "--silent";

    /** Run 键在注册表里的完整路径（HKCU），供三处 reg 命令复用。 */
    private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    private BootAutostart() {
    }

    /**
     * 开/关开机自启。
     *
     * <p>{@code on=true} 时往真实 Run 键写 {@code GBL="<GBL.exe 绝对路径>"}（r6 起不带 --silent，
     * 登录后正常显示主窗口）；{@code on=false} 时删掉 {@code GBL} 值。成功返回 {@code true}，
     * 定位不到 exe 或 reg 命令失败返回 {@code false}（供界面判定成败并回滚开关）。
     * 原生调用失败只记日志、不抛。
     */
    public static boolean setEnabled(boolean on) {
        String exePath = resolveExePath();
        if (exePath == null) {
            System.err.println("[boot] 无法定位 GBL 可执行文件，跳过开机自启设置");
            return false;
        }
        // 0.9.6 r3：值内容必须自带引号，否则 Windows 登录执行时按空格截断含空格的路径。
        // 0.9.6 r6 A1：去掉 " " + SILENT_ARG —— 开机自启正常显示主窗口，不再静默只挂托盘。
        String value = "\"" + exePath + "\"";
        System.err.println("[boot] 0.9.6 r6 真实 Run 键：" + (on ? "写入" : "删除") + " " + RUN_VALUE_NAME);
        return on ? writeViaReg(RUN_VALUE_NAME, value)
                  : removeViaReg(RUN_VALUE_NAME);
    }

    /** 当前是否开了开机自启（真实 Run 键里存在 GBL 值即算开）。 */
    public static boolean isEnabled() {
        return queryViaReg(RUN_VALUE_NAME);
    }

    /**
     * 0.9.6 r6 A2：自愈旧版开机自启 Run 值。老用户注册表里那条值带 {@code --silent}，
     * 只改 {@link #setEnabled(boolean)} 的写入逻辑不会动到它；这里在每次启动时读回，
     * 仅当「值存在且包含 --silent」时才重写为不带 --silent 的新值。
     * 内容已是新值（或值不存在、解析不出来、定位不到 exe）就一次都不写，
     * 幂等，绝不每次启动都动注册表；复用 {@code writeViaReg} + {@code runReg}（HKCU 不需 UAC，失败只日志）。
     */
    public static void refreshIfStale() {
        String current = readValue(RUN_VALUE_NAME);
        if (current == null || !current.contains(SILENT_ARG)) {
            return; // 值不存在 / 已是新值 / 解析失败 —— 一律不写，绝不崩
        }
        String exePath = resolveExePath();
        if (exePath == null) {
            System.err.println("[boot] refreshIfStale：无法定位 GBL.exe，跳过自愈");
            return;
        }
        String fresh = "\"" + exePath + "\"";
        System.err.println("[boot] refreshIfStale：检测到旧 --silent 值，自愈为 " + fresh);
        writeViaReg(RUN_VALUE_NAME, fresh);
    }

    /**
     * 定位 GBL 可执行文件绝对路径。优先找当前 jar/exe 同目录的 {@code GBL.exe}，
     * 再退到进程映像路径，最后看进程映像同目录；都拿不到返回 null。
     */
    private static String resolveExePath() {
        // 1) 当前类所在 jar / 类的目录找兄弟 GBL.exe
        try {
            java.security.ProtectionDomain pd = BootAutostart.class.getProtectionDomain();
            if (pd != null && pd.getCodeSource() != null
                    && pd.getCodeSource().getLocation() != null) {
                String raw = pd.getCodeSource().getLocation().getPath();
                String decoded = urlDecode(raw);
                File here = new File(decoded);
                File dir = here.isDirectory() ? here : here.getParentFile();
                if (dir != null) {
                    File sibling = new File(dir, "GBL.exe");
                    if (sibling.isFile()) {
                        return sibling.getAbsolutePath();
                    }
                }
            }
        } catch (Throwable ex) {
            System.err.println("[boot] 从 jar 目录定位 GBL.exe 失败: " + ex);
        }
        // 2) 进程映像路径（java.exe 或 GBL.exe）
        try {
            // 注：imageName() 是 Java 23+ API，本工程以 --release 21 编译不可用；
            // 改用 Java 21 即有的 command()（返回当前进程可执行文件绝对路径），
            // 既满足编译约束，又比仅取文件名更利于定位 GBL.exe。
            String image = ProcessHandle.current().info().command().orElse(null);
            if (image != null) {
                File f = new File(image);
                if (f.getName().equalsIgnoreCase("GBL.exe") && f.isFile()) {
                    return f.getAbsolutePath();
                }
                File dir = f.getParentFile();
                if (dir != null) {
                    File sibling = new File(dir, "GBL.exe");
                    if (sibling.isFile()) {
                        return sibling.getAbsolutePath();
                    }
                }
            }
        } catch (Throwable ex) {
            System.err.println("[boot] 从进程映像定位 GBL.exe 失败: " + ex);
        }
        return null;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Throwable ex) {
            return s;
        }
    }

    // ---------------------------------------------------------------- reg.exe（真实 Run 键读写）

    private static boolean writeViaReg(String name, String value) {
        List<String> cmd = new ArrayList<>();
        cmd.add("C:\\Windows\\System32\\reg.exe");
        cmd.add("add");
        cmd.add(RUN_KEY);
        cmd.add("/v"); cmd.add(name);
        cmd.add("/t"); cmd.add("REG_SZ");
        cmd.add("/d"); cmd.add(value);      // value 已含 exe 路径引号，见 setEnabled
        cmd.add("/f");
        return runReg(cmd) == 0;
    }

    private static boolean removeViaReg(String name) {
        List<String> cmd = new ArrayList<>();
        cmd.add("C:\\Windows\\System32\\reg.exe");
        cmd.add("delete");
        cmd.add(RUN_KEY);
        cmd.add("/v");
        cmd.add(name);
        cmd.add("/f");
        return runReg(cmd) == 0;
    }

    private static boolean queryViaReg(String name) {
        List<String> cmd = new ArrayList<>();
        cmd.add("C:\\Windows\\System32\\reg.exe");
        cmd.add("query");
        cmd.add(RUN_KEY);
        cmd.add("/v");
        cmd.add(name);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            // 先排空输出流再 waitFor，避免管道缓冲死锁；判定只看退出码，不解析可能乱码的中文输出
            p.getInputStream().readAllBytes();
            int code = p.waitFor();
            return code == 0;
        } catch (Throwable ex) {
            System.err.println("[boot] reg query 失败: " + ex);
            return false;
        }
    }

    /**
     * 0.9.6 r6 A2：读回 Run 值文本，解析 {@code REG_SZ} 后面的值内容。
     * Windows 中文系统表头可能乱码，但 {@code REG_SZ} 与值里的 {@code --silent} 都是 ASCII，
     * 宽松匹配够用；解析不出来 / reg 失败都返回 null，由调用方跳过刷新，绝不崩。
     * 与 {@link #queryViaReg(String)} 并存：那个只判退出码，这个要拿到值文本。
     */
    private static String readValue(String name) {
        List<String> cmd = new ArrayList<>();
        cmd.add("C:\\Windows\\System32\\reg.exe");
        cmd.add("query");
        cmd.add(RUN_KEY);
        cmd.add("/v");
        cmd.add(name);
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] bytes = p.getInputStream().readAllBytes();
            int code = p.waitFor();
            if (code != 0) {
                return null;
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) {
                int idx = line.indexOf("REG_SZ");
                if (idx >= 0) {
                    return line.substring(idx + "REG_SZ".length()).trim();
                }
            }
            return null;
        } catch (Throwable ex) {
            System.err.println("[boot] reg query 读取值失败: " + ex);
            return null;
        }
    }

    private static int runReg(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.waitFor();
            if (code != 0) {
                System.err.println("[boot] reg 返回 " + code + ": " + out.trim());
            }
            return code;
        } catch (Throwable ex) {
            System.err.println("[boot] reg 命令执行失败: " + ex);
            return -1;
        }
    }
}
