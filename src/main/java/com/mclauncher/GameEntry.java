package com.mclauncher;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 一个装好的游戏：磁盘上 {@code versions} 下的一个目录。
 *
 * <p>{@code id} 就是文件夹名，也是唯一标识——改名走的是「改文件夹名」，
 * 所以显示名始终等于 id，这里不再单存一份名字，省得两份对不上。
 *
 * <p>体积是扫出来的字节数，日期优先取 {@code version.json} 里官方记的时间，
 * 读不到就退回文件夹的修改时间（两种情况都给 UI，由它决定显示哪个）。
 * 解析不出来时这两个字段可以为 null，界面上按「—」渲染，不要让整行打不开。
 */
public record GameEntry(String id, GameType type, Long sizeBytes, String date) {

    /**
     * 愚人节那几条在 id 上的长相：{@code <年>w<周><一串英文词>}。
     *
     * <p>官方自己把它们归在 {@code snapshot} 底下（{@code version.json} 的 type 就是
     * {@code snapshot}），光看类型字段永远是「快照版」，只有 id 认得出来：
     * {@code 24w14potato} / {@code 25w14craftmine} / {@code 22w13oneblockatatime} /
     * {@code 20w14infinite}……正常快照的后缀只有一个字母（{@code 26w06a}），
     * 所以「周号后面跟了 3 个以上字母」就是愚人节的稳定特征，不用把 id 一张张抄下来。
     */
    private static final Pattern WORD_SUFFIX = Pattern.compile("^\\d+w\\d+[a-z]{3,}$");

    /**
     * 不合 {@link #WORD_SUFFIX} 规律、但确实是愚人节版的 id，必须显式列出。
     * 关键词（april/shareware/funny/~）与 {@link #WORD_SUFFIX} 认不出的都在这里点名。
     *
     * <ul>
     *   <li>{@code 15w14a} / {@code 26w14a}：周号后缀只有 1 个字母，不满足「3+ 字母」。</li>
     *   <li>{@code 1.RV-Pre1}：2.0 玩笑版（红石更新预览），根本不是周号命名。</li>
     *   <li>{@code 23w13a_or_b}：2023 愚人节（A 还是 B），后缀夹下划线，正则对不上。</li>
     *   <li>{@code 2.0} / {@code 2.0_*} / {@code 2point0_*}：2013 愚人节「Minecraft 2.0」及其
     *       HMCL 补充变体；纯数字+点，不能用 startsWith("2.") 之类会误伤正式版的宽泛规则。</li>
     *   <li>{@code 13w12~} / {@code 20w14~}：带波浪号的玩笑快照。</li>
     *   <li>{@code 22w13oneblockatatime} / {@code 24w14potato} / {@code 25w14craftmine} /
     *       {@code 20w14infinite} / {@code 3d shareware v1.34}：历年愚人节整活版，显式列出更稳。</li>
     * </ul>
     *
     * <p>统一小写存，因为 {@link #isAprilFools} 先把 id 转小写再比。
     */
    private static final Set<String> APRIL_FOOLS_IDS = Set.of(
            "15w14a", "1.rv-pre1", "23w13a_or_b",
            "26w14a",
            "2.0", "2.0_purple", "2.0_red", "2.0_blue",
            "2point0_purple", "2point0_red", "2point0_blue",
            "20w14~", "13w12~",
            "22w13oneblockatatime", "24w14potato", "25w14craftmine", "20w14infinite",
            "3d shareware v1.34");

    /**
     * 这个 id 是不是愚人节那一条。
     *
     * <p>下载页靠 {@code VersionManifest.kindOf} 用它，游戏列表靠
     * {@code GameScanner} 用它——两处必须是同一个判据：同一个版本在下载页写「愚人节版」、
     * 装好之后在游戏列表却写「快照版」，比两头都写错更让人犯嘀咕。
     */
    public static boolean isAprilFools(String id) {
        if (id == null) {
            return false;
        }
        String text = id.trim().toLowerCase(java.util.Locale.ROOT);
        if (text.contains("april") || text.contains("shareware") || text.contains("funny")
                || text.contains("~")) {
            return true;   // 3D Shareware v1.34 那类带词的；带 ~ 的玩笑快照（13w12~ / 20w14~）
        }
        if (APRIL_FOOLS_IDS.contains(text)) {
            return true;   // 15w14a / 1.RV-Pre1 / 23w13a_or_b 这几个不合周号规律的
        }
        return WORD_SUFFIX.matcher(text).matches();
    }

    /**
     * 游戏类型。跟 {@code version.json} 的 {@code type} 字段一一对上，
     * 界面拿 {@link #cssSuffix()} 直接拼 CSS 类名（见 ShellView.cssName 那套）。
     */
    public enum GameType {
        RELEASE("正式版", "release"),
        SNAPSHOT("快照版", "snapshot"),
        APRIL("愚人节版", "april"),
        LEGACY("远古版", "legacy");

        private final String label;
        private final String cssSuffix;

        GameType(String label, String cssSuffix) {
            this.label = label;
            this.cssSuffix = cssSuffix;
        }

        public String label() {
            return label;
        }

        public String cssSuffix() {
            return cssSuffix;
        }

        /**
         * 官方 type 字符串映射到我们这套枚举。
         *
         * <p>0.8.6：口径统一交给 {@link VersionKinds}，下载页的
         * {@code VersionManifest.kindOf} 也调同一张表。以前这里
         * {@code default -> LEGACY("远古版")}，只要读到不认识的 type
         * （例如完整版 version.json 里嵌套的 {@code logging.client.type="log4j2-xml"}）
         * 就把 26.3 这种新正式版标成「远古版」。现在未知 type 兜「正式版」并留痕，
         * 1.0 以前的 alpha/beta/infdev 由表里那几个值明确落到「远古版」。
         */
        public static GameType from(String raw) {
            return toGameType(VersionKinds.of(raw));
        }

        /**
         * 按「磁盘上那个目录名 + 官方 type」判类型，愚人节那几条优先。
         *
         * <p>直接读 {@code version.json} 会把 {@code 24w14potato} 判成「快照版」——
         * Mojang 自己就是这么标的。认 id 这一层才是「它其实是愚人节版」的唯一依据，
         * 判据在 {@link VersionKinds#detect(String, String)} 里与下载页共用。
         */
        public static GameType detect(String id, String raw) {
            return toGameType(VersionKinds.detect(id, raw));
        }

        /** 归一化分类 → 本枚举。四个分支穷举，新增分类不会让界面层的 switch 悄悄漏一种。 */
        private static GameType toGameType(VersionKinds.Standard kind) {
            return switch (kind) {
                case RELEASE -> RELEASE;
                case SNAPSHOT -> SNAPSHOT;
                case APRIL -> APRIL;
                case LEGACY -> LEGACY;
            };
        }
    }
}
