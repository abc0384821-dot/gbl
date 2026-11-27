package com.mclauncher;

import java.util.ArrayList;
import java.util.List;

/**
 * 点分版本号比较（0.9.4 / 0.9.5 / 0.9.10 这类）。
 *
 * <p>复刻 {@link LoaderInstaller} 里那套比较口径，但抽成独立工具，自动更新与主程序共用，
 * 避免自动更新模块反向依赖加载器实现。
 */
public final class AppVersion {

    private AppVersion() {
    }

    /** 把版本串里的小数点分隔数字段抽出来；非数字字符（前缀字母等）一律忽略。 */
    private static List<Long> parts(String v) {
        List<Long> out = new ArrayList<>();
        if (v == null) {
            return out;
        }
        StringBuilder num = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isDigit(c)) {
                num.append(c);
            } else if (c == '.') {
                if (num.length() > 0) {
                    out.add(Long.parseLong(num.toString()));
                    num.setLength(0);
                }
            }
            // 其它字符（分隔符、字母前缀）忽略
        }
        if (num.length() > 0) {
            out.add(Long.parseLong(num.toString()));
        }
        return out;
    }

    /**
     * 比较 a、b 两个版本：a 新返回正数，b 新返回负数，相等返回 0。
     * 长度不同且前缀一致时，多出的非零段算更新（0.9.4 &lt; 0.9.4.1），
     * 纯靠位数多不算（0.9.10 的第三段 10 大于 0.9.4 的第三段 4）。
     */
    public static int compare(String a, String b) {
        List<Long> pa = parts(a);
        List<Long> pb = parts(b);
        int n = Math.max(pa.size(), pb.size());
        for (int i = 0; i < n; i++) {
            long xa = i < pa.size() ? pa.get(i) : 0;
            long xb = i < pb.size() ? pb.get(i) : 0;
            if (xa != xb) {
                return Long.compare(xa, xb);
            }
        }
        return 0;
    }

    /** candidate 是否比 current 新。 */
    public static boolean isNewer(String current, String candidate) {
        return compare(candidate, current) > 0;
    }
}
