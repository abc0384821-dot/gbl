package com.mclauncher;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 资源包 / 光影包 {@code pack.mcmeta} 里的 {@code pack_format} → 可读 Minecraft 版本文本。
 *
 * <p>{@code pack.pack_format} 是一个随 MC 版本递增的整数；1.20.2 起还可能出现
 * {@code supported_formats}（单个数字 / 数字数组 / {@code {min_inclusive,max_inclusive}} 对象）。
 * 这里把整数映射成人看得懂的 {@code 1.20–1.20.2} / {@code 1.21+}，
 * 没见过的格式号一律回退成 {@code 格式 N}，不猜、不抛。
 *
 * <p>表只收<strong>正式发布版</strong>的里程碑格式号；快照期的中间值由
 * {@link NavigableMap#floorEntry} 归到前一个正式版，属于合理近似，
 * 足够「给每个条目标个大概版本」这个用途。覆盖 1.6.4 起到 1.21.x。
 */
public final class PackVersion {

    /** 每个已知格式号覆盖的 MC 版本 [起始, 结束]；起止相同表示单点版本。 */
    private static final NavigableMap<Integer, String[]> TABLE = new TreeMap<>();

    static {
        put(1,  "1.6.4",  "1.8.9");
        put(2,  "1.9",    "1.10.2");
        put(3,  "1.11",   "1.12.2");
        put(4,  "1.13",   "1.14.4");
        put(5,  "1.15",   "1.16.1");
        put(6,  "1.16.2", "1.16.5");
        put(7,  "1.17",   "1.17.1");
        put(8,  "1.18",   "1.18.2");
        put(9,  "1.19",   "1.19.2");
        put(10, "1.19.3", "1.19.3");
        put(12, "1.19.4", "1.19.4");
        put(15, "1.20",   "1.20.1");
        put(18, "1.20.2", "1.20.2");
        put(22, "1.20.3", "1.20.4");
        put(32, "1.20.5", "1.20.6");
        put(34, "1.21",   "1.21.1");
        put(42, "1.21.2", "1.21.3");
        put(46, "1.21.4", "1.21.4");
        put(55, "1.21.5", "1.21.5");
        put(63, "1.21.6", "1.21.8");
    }

    /** 表内最大已知格式号；比它更大的按「不认识」处理。 */
    private static final int MAX_KNOWN = TABLE.lastKey();

    private PackVersion() {
        // 静态工具类，别让人 new 出来
    }

    private static void put(int format, String start, String end) {
        TABLE.put(format, new String[]{start, end});
    }

    /** 单个格式号 → 版本文本；不认识返回 {@code 格式 N}。 */
    public static String describe(int format) {
        if (format < 1 || format > MAX_KNOWN) {
            return "格式 " + format;
        }
        Map.Entry<Integer, String[]> e = TABLE.floorEntry(format);
        if (e == null) {
            return "格式 " + format;
        }
        String[] se = e.getValue();
        return se[0].equals(se[1]) ? se[0] : se[0] + "–" + se[1];
    }

    /**
     * 支持区间 → 文本：{@code [15,18]} 得 {@code 1.20–1.20.2}；
     * 上界到表顶或更大得 {@code 1.21+}；单点委托 {@link #describe(int)}。
     */
    public static String range(int min, int max) {
        if (min < 1) {
            return "格式 " + min;
        }
        if (max <= min) {
            return describe(min);
        }
        if (max >= MAX_KNOWN) {
            return startOf(min) + "+";
        }
        String s = startOf(min);
        String e = endOf(max);
        return s.equals(e) ? s : s + "–" + e;
    }

    private static String startOf(int format) {
        if (format < 1 || format > MAX_KNOWN) {
            return "格式 " + format;
        }
        Map.Entry<Integer, String[]> e = TABLE.floorEntry(format);
        return e == null ? "格式 " + format : e.getValue()[0];
    }

    private static String endOf(int format) {
        if (format < 1 || format > MAX_KNOWN) {
            return "格式 " + format;
        }
        Map.Entry<Integer, String[]> e = TABLE.floorEntry(format);
        return e == null ? "格式 " + format : e.getValue()[1];
    }
}
