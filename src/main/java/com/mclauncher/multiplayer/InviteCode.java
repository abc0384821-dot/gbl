package com.mclauncher.multiplayer;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 房间邀请码的纯校验与诊断拆分（无 IO、无副作用）。
 *
 * <p>格式：{@code U/XXXX-XXXX-XXXX-XXXX}，字母表为 Crockford Base32 去掉 I/O/L/U，
 * 即 {@code 0-9 A-H J-N P-Z}。前两段是网络名，后两段是加入凭据（仅诊断展示，不要手填）。
 */
public final class InviteCode {

    private static final Pattern CODE =
            Pattern.compile("^U/[0-9A-HJ-NP-Z]{4}(-[0-9A-HJ-NP-Z]{4}){3}$");

    /** Crockford Base32 字母表（去掉易混淆的 I/L/O/U）。 */
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private InviteCode() {
        // 纯工具类
    }

    /** 严格格式校验。小写 / 缺段 / 含 I O 等非法字符一律 false。 */
    public static boolean isValid(String code) {
        return code != null && CODE.matcher(code).matches();
    }

    /** 去首尾空白、去内部所有空白、字母转大写（U 前缀随之保留）。 */
    public static String normalize(String code) {
        if (code == null) {
            return "";
        }
        return code.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }

    /** 诊断用网络名：取前两段拼成 scaffolding-mc-NNNN-NNNN。 */
    public static String networkName(String code) {
        String[] groups = groups(code);
        if (groups.length < 2) {
            return "";
        }
        return "scaffolding-mc-" + groups[0] + "-" + groups[1];
    }

    /** 诊断用凭据：取后两段拼成 SSSS-SSSS。不要让用户手动填这个。 */
    public static String secret(String code) {
        String[] groups = groups(code);
        if (groups.length < 4) {
            return "";
        }
        return groups[2] + "-" + groups[3];
    }

    /**
     * 小端整型校验（仅作提示，<b>不阻断</b>有效码）：把 16 个 Base32 字符解成比特流，
     * 取前 4 字节按小端读成 int，能被 7 整除则 true。
     */
    public static boolean passesChecksum(String code) {
        String[] groups = groups(code);
        if (groups.length != 4) {
            return false;
        }
        byte[] bytes = new byte[10];   // 16 * 5 = 80 bit
        int bit = 0;
        for (String group : groups) {
            for (int i = 0; i < group.length(); i++) {
                int v = ALPHABET.indexOf(group.charAt(i));
                if (v < 0) {
                    return false;
                }
                for (int b = 4; b >= 0; b--) {
                    if (((v >> b) & 1) != 0) {
                        bytes[bit / 8] |= (byte) (1 << (7 - (bit % 8)));
                    }
                    bit++;
                }
            }
        }
        int little = (bytes[0] & 0xFF)
                | ((bytes[1] & 0xFF) << 8)
                | ((bytes[2] & 0xFF) << 16)
                | ((bytes[3] & 0xFF) << 24);
        return little % 7 == 0;
    }

    private static String[] groups(String code) {
        String s = normalize(code);
        if (!s.startsWith("U/")) {
            return new String[0];
        }
        String[] parts = s.substring(2).split("-", -1);
        return parts.length == 4 ? parts : new String[0];
    }
}
