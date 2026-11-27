package com.mclauncher;

/**
 * 一次可用的更新（来自官网 {@code version.json} 或 Gitee latest 兜底）。
 *
 * <p>{@code sha256} / {@code sizeBytes} 在走 Gitee 兜底时可能为空 / 0，
 * 安装器据此决定是否做严格校验（主源一定带，兜底不强求）。
 *
 * <p>{@code altUrl} 是 0.9.8 新增的备用下载地址（version.json 的 {@code github} 字段）；
 * 安装器会先并发探测主源/备源谁响应快，主源挂了自动用备源，避免「一条线路慢就干等」。
 */
public record UpdateInfo(String version, String build, String url, String sha256,
                         long sizeBytes, String altUrl) {

    /** 兼容只有主源的构造方式（Gitee latest 兜底、手动拼装等）。 */
    public UpdateInfo(String version, String build, String url, String sha256, long sizeBytes) {
        this(version, build, url, sha256, sizeBytes, null);
    }
}
