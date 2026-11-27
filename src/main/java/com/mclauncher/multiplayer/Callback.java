package com.mclauncher.multiplayer;

/**
 * 服务层异步回调。成功与失败都走同一个出口，调用方按需切回 JavaFX 线程
 * （本层纯 JDK、不碰 JavaFX，回调发生在后台线程上）。
 *
 * @param <T> 成功时回传的结果类型
 */
@FunctionalInterface
public interface Callback<T> {
    /**
     * @param value 成功结果；失败时为 null
     * @param error 失败原因；成功时为 null
     */
    void onResult(T value, Throwable error);
}
