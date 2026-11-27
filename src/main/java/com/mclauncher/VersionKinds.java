package com.mclauncher;

import java.util.Locale;

/**
 * 官方 {@code version.json} / {@code version_manifest.json} 的 {@code type} 字段
 * → 展示分类的**唯一**映射表（0.8.6）。
 *
 * <p>以前下载页 {@link VersionManifest} 与游戏列表 {@link GameEntry} 各写了一套 switch，
 * 兜底还相反：前者未知 type 兜 {@code RELEASE}，后者兜 {@code LEGACY("远古版")}。
 * 于是游戏列表一旦读到不认识的 type（例如完整版 JSON 里嵌套的
 * {@code logging.client.type="log4j2-xml"}），26.3 这种新正式版就被标成「远古版」。
 *
 * <p>现在两处都调这里的 {@link #detect(String, String)} / {@link #of(String)}，
 * 拿同一个 {@link Standard} 再各自翻成自己的枚举，口径永远一致。
 *
 * <p>对照 HMCL 的 {@code ReleaseType}：分类只依据官方 manifest 的 {@code type}，
 * **版本号正则不参与大类归类**；1.0 以前的 alpha / beta / infdev 由
 * {@code old_alpha} / {@code old_beta} / 历史 id 明确落到「远古版」，
 * 未知 / 官方新增 type 落「正式版」并在控制台留痕，绝不 default→远古版。
 *
 * <p>纯 Java 工具类，不碰 UI、不碰文件系统、不联网。
 */
public final class VersionKinds {

    /** 归一化后的四类。枚举常量固定为四个，界面层的穷举 switch 不会因此编译失败。 */
    public enum Standard {
        RELEASE,
        SNAPSHOT,
        APRIL,
        LEGACY
    }

    private VersionKinds() {
        // 静态工具类
    }

    /**
     * 只按 {@code type} 字符串归类（不带 id），给 {@link GameEntry.GameType#from(String)} 用。
     *
     * <p>兜底是 {@link Standard#RELEASE}：官方未来新增的 type、或者解析脏值，
     * 都按「正式版」显示并留一行日志，不能伪装成历史版本。
     * 本批次不引入「未分类」枚举——界面层 {@code ShellView.cssKind} 是穷举 switch，
     * 新增常量会让本轮不可修改的 ShellView 编译失败，留待后续批次。
     */
    public static Standard of(String rawType) {
        return of(rawType, null);
    }

    /**
     * 只按 {@code type} 字符串归类，日志里带上版本 id 便于定位。
     *
     * @param rawType version.json / manifest 里的原始 type；null / 空串按正式版
     * @param id      版本 id，仅用于日志；可为 null
     */
    public static Standard of(String rawType, String id) {
        if (rawType == null || rawType.isBlank()) {
            return Standard.RELEASE;
        }
        // HMCL 清单里带连字符的 type（old-beta / old-alpha / experimental-snapshot）
        // 统一换成下划线再比，省得为每个变体各写一遍；legacy 等现有词不受影响。
        String lower = rawType.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (lower) {
            case "release", "modified" -> Standard.RELEASE;
            // HMCL 的 pending / unobfuscated 与实验快照都按快照显示
            case "snapshot", "pending", "unobfuscated", "experimental", "experimental_snapshot" ->
                    Standard.SNAPSHOT;
            case "april" -> Standard.APRIL;
            // "legacy" 是 VersionManifest.writeCache 把 Kind.LEGACY.name() 小写后写回快照的
            // 内部词（下载页离线缓存会再读回来）。不认它，远古版重启一次就变「正式版」。
            case "old", "old_alpha", "old_beta", "old_snapshot", "alpha", "beta", "infdev", "legacy" ->
                    Standard.LEGACY;
            default -> {
                System.err.println("[verkind] 未知 type=" + rawType + " 按正式版处理 id="
                        + (id == null ? "(未提供)" : id));
                yield Standard.RELEASE;
            }
        };
    }

    /**
     * 完整判定：愚人节版本按 id 优先认出来（官方把它们标成 {@code snapshot}），
     * 其余交给 {@link #of(String, String)}。下载页与游戏列表必须都走这里。
     *
     * <p>愚人节判据沿用 {@link GameEntry#isAprilFools(String)}，不另抄一份。
     */
    public static Standard detect(String id, String rawType) {
        return GameEntry.isAprilFools(id) ? Standard.APRIL : of(rawType, id);
    }
}
