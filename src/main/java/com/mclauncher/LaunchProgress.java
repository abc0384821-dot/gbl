package com.mclauncher;

/**
 * 启动 / 补依赖阶段的数值进度总线。纯服务层，不依赖 JavaFX。
 *
 * <p>0.9.2 r4：以前启动前只有 {@code Consumer<String>} 文案通道，没有任何数值进度出口，
 * UI 的进度条只能在打开对话框时定格在一个固定值。这里补一条旁路数值通道：服务层在
 * 后台线程按阶段 {@link #emit} 数值帧，UI 在 {@link Sink} 里自行用 {@code Platform.runLater}
 * 切回 FX 线程更新进度条与文案。<b>本类不引用任何 JavaFX 类型。</b>
 *
 * <p>订阅是全局单例式的一对一：UI 打开启动对话框时 {@link #setSink}、关闭时 {@link #clear()}。
 * {@link #emit} 必须吞掉 {@link Sink} 抛出的任何 {@code Throwable}——UI 侧的异常绝不能
 * 拖垮启动流程；且 sink 为 null 时零开销（正常未订阅路径只是一次 volatile 读）。
 */
public final class LaunchProgress {

    @FunctionalInterface
    public interface Sink {
        /**
         * @param stage 中文阶段文案，如「正在补资源…」「正在补依赖库…」「正在启动…」
         * @param done  已完成文件数；不确定态传 -1
         * @param total 总文件数；不确定态传 -1
         * @param bytes 累计字节数
         * @param ratio 0.0~1.0；&lt; 0 表示不确定态（UI 应显示脉动动画）
         */
        void on(String stage, int done, int total, long bytes, double ratio);
    }

    private static volatile Sink sink;

    public static void setSink(Sink s) {
        sink = s;
    }

    public static void clear() {
        sink = null;
    }

    public static void emit(String stage, int done, int total, long bytes, double ratio) {
        Sink s = sink;
        if (s != null) {
            try {
                s.on(stage, done, total, bytes, ratio);
            } catch (Throwable ignored) {
                // UI 异常不能拖垮启动
            }
        }
    }
}
