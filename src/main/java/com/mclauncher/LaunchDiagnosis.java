package com.mclauncher;

import java.util.List;
import java.util.Locale;

/**
 * 游戏启动失败时的「人话诊断」。
 *
 * <p>为什么要有这个类：0.9.6 之前用户看到的是一句「游戏异常退出」或者干脆什么也没有，
 * 只能自己去翻控制台几百行英文日志。普通玩家不需要看懂 {@code NoSuchMethodError}，
 * 他需要知道三件事：<b>哪里出错了 / 为什么 / 我下一步该做什么</b>。
 *
 * <p>本类只做「分类 + 写中文说明」这一件事，不碰任何 UI，也不猜没有证据的原因：
 * 全部结论都来自真实退出码与游戏自己打印的日志特征串，认不出来就老实说认不出，
 * 并引导用户去「导出诊断」把日志交出来，绝不编一个像模像样的假原因。
 */
public final class LaunchDiagnosis {

    /** 0.9.7 指纹：启动失败可读诊断。构建门禁扫常量池，勿改这串字。 */
    public static final String FP_097_DIAG = "0.9.7 启动失败可读诊断";

    static {
        System.err.println("[diag] " + FP_097_DIAG);
    }

    /** 诊断结果：一句话标题 + 两三句可操作建议。 */
    public record Report(String title, String advice) {
    }

    private LaunchDiagnosis() {
    }

    /**
     * 归类一次「游戏已经跑起来过、但退出得不对劲」的情况。
     *
     * @param exitCode 进程退出码；拿不到传 {@link Integer#MIN_VALUE}
     * @param tail     控制台最后若干行（顺序为原始输出顺序，尾部是最后打印的）
     * @param gameId   启动的是哪个版本，只用于文案
     */
    public static Report explain(int exitCode, List<String> tail, String gameId) {
        String name = (gameId == null || gameId.isBlank()) ? "这个版本" : "「" + gameId + "」";
        String log = (tail == null) ? "" : String.join("\n", tail).toLowerCase(Locale.ROOT);

        // ① 内存不够：MC 崩溃里最常见的一类，报错原文固定
        if (log.contains("outofmemoryerror") || log.contains("java heap space")
                || log.contains("insufficient memory")) {
            return new Report(name + "因为内存不够被系统结束了",
                    "去「设置 → 游戏内存」把这个值调小一些（比如 2048），"
                            + "或者在游戏里把渲染距离、光影降一档再试。"
                            + "内存不是越大越好：给得比电脑实际可用内存还多，游戏反而更容易被系统杀掉。");
        }

        // ② Java 版本太老：老启动器最常见的新手坑
        if (log.contains("unsupportedclassversionerror")
                || log.contains("has been compiled by a more recent version")) {
            return new Report(name + "需要的 Java 版本比现在用的更新",
                    "这个版本要求更高版本的 Java。去「设置 → 游戏 Java」指定一个新一点的 Java，"
                            + "或者改用下载页里更新的游戏版本。");
        }

        // ③ 模组 / 加载器版本不匹配
        if (log.contains("mod file") && (log.contains("requires") || log.contains("missing"))
                || log.contains("missing or unsupported mandatory dependencies")
                || log.contains("incompatible mod set")
                || log.contains("mod resolution encountered an incompatible mod set")) {
            return new Report(name + "的模组版本对不上",
                    "多半是某个模组分给了别的游戏版本，或者少了它依赖的前置模组。"
                            + "去「模组」页把刚装的模组先关掉几个再启动，能启动就说明是它们其中之一。");
        }

        if (log.contains("nosuchmethoderror") || log.contains("noclassdeffounderror")
                || log.contains("abstractmethoderror")) {
            return new Report(name + "里有个模组和当前版本不兼容",
                    "这类报错几乎都是「模组是给另一个游戏版本编的」。"
                            + "把最近新加的模组停用，或者换回和游戏版本对应的那一版模组。");
        }

        // ④ 显卡 / OpenGL：驱动太旧或核显顶不住
        if (log.contains("pixel format not accelerated")
                || log.contains("failed to create window")
                || log.contains("glfw error")
                || log.contains("opengl")
                || log.contains("unsupported on this platform")) {
            return new Report(name + "打不开游戏窗口（显卡驱动相关）",
                    "先把显卡驱动更新到最新；如果是笔记本双显卡，试着让游戏用独立显卡跑。"
                            + "还不行就在游戏里关掉光影，把图像品质调低。");
        }

        // ⑤ 运行库 / native 文件缺失
        if (log.contains("failed to locate library")
                || log.contains("unsatisfiedlinkerror")
                || log.contains("no lwjgl")
                || log.contains("could not find or load main class")) {
            return new Report(name + "的运行文件不完整",
                    "通常是上次下载中断留下的半套文件。重新下载安装一次这个版本一般就能修好；"
                            + "如果反复出现，点「导出诊断」把日志发出来。");
        }

        // ⑥ 认证 / 网络
        if (log.contains("authentication servers down")
                || log.contains("failed to verify username")
                || log.contains("invalid session")) {
            return new Report(name + "登录验证没通过",
                    "先确认网络能正常打开网页，再检查一下账号是不是掉了。"
                            + "离线账号玩单机不受影响，用离线账号启动即可。");
        }

        // ⑦ 退出码兜底：被系统或安全软件杀掉
        //    -1073741510 是 STATUS_CONTROL_C_EXIT(0xC000013A) 的有符号写法，130 是类 Unix 的 Ctrl+C
        if (exitCode == 130 || exitCode == -1073741510) {
            return new Report(name + "被强制中断了",
                    "一般是关窗口时按了 Ctrl+C，或者任务管理器里结束了它。重新启动一次即可，不用改任何设置。");
        }
        if (exitCode == 1 || exitCode == 255) {
            return new Report(name + "启动过程中报错退出了",
                    "点下面的「导出诊断」会把这次的完整日志打包出来，"
                            + "把它发到反馈里我们就能定位。也可以先试试把刚装的模组 / 光影关掉再启动。");
        }
        if (exitCode == Integer.MIN_VALUE) {
            return new Report(name + "已经退出，但拿不到退出原因",
                    "点「导出诊断」把日志打包出来更方便排查。如果之前玩得好好的，"
                            + "多半是刚装的东西引起的，先把新装的模组或光影停用试试。");
        }
        return new Report(name + "异常退出（退出码 " + exitCode + "）",
                "点「导出诊断」可以把这次的完整日志打包出来。"
                        + "常见原因是模组冲突、内存不足或驱动问题，先停用最近新装的模组再启动一次试试。");
    }

    /**
     * 把一段控制台输出按行切好并只留尾部 {@code keep} 行，避免诊断时把几万行都读进内存。
     * 传入的整段文本允许为 null。
     */
    public static List<String> tailLines(String consoleText, int keep) {
        if (consoleText == null || consoleText.isEmpty() || keep <= 0) {
            return List.of();
        }
        String[] all = consoleText.split("\r?\n", -1);
        int from = Math.max(0, all.length - keep);
        return List.of(all).subList(from, all.length);
    }
}
