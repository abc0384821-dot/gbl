package com.mclauncher;

import java.util.Locale;

/**
 * 加载器枚举：元数据型（Fabric / Quilt / LegacyFabric）+ 安装器型（Forge / NeoForge / OptiFine）。
 *
 * <p>元数据型接入方式完全同构——都只要问一次 meta API 拿 {@code launcherMeta}，区别只在
 * API 基地址、maven 仓库和两个「外部标识」：{@link #id()}（写进版本 JSON 的
 * {@code gblLoader.type}）、{@link #category()}（Modrinth 分类名）、{@link #depKey()}
 * （mrpack {@code dependencies} 键名）。
 *
 * <p>安装器型（Forge / NeoForge，v0.8 新增；OptiFine，v0.8.7 新增）走另一套：下载
 * installer jar → 生成独立派生 profile，不合并进 base 版本（Forge/NeoForge 无头运行
 * installer；OptiFine 不跑 GUI、把 jar 当 library 自写 profile，见下文）。它们的
 * {@link #metaBase} 为 null（{@link #metaUrl} 因而返回 null），由 {@link #isMeta()} /
 * {@link #isInstaller()} 分流。
 *
 * <p>OptiFine 按 HMCL 口径是「独立加载器 / 独立版本」：实证 installer jar 的
 * {@code Main-Class=optifine.InstallerFrame}（Swing GUI）、只能作 library + tweakClass 用，
 * 故 GBL 走「运行时拉 jar → 原样当主库 + 从 jar 内嵌解出 launchwrapper → 落 libraries
 * → 自写独立派生 profile」（不跑 installer GUI、不请求第三方 launchwrapper 源）。
 * 版本列表与 jar 运行时从 BMCLAPI 拉取，绝不打包进分发包；产出的 profile 不改写原版
 * 客户端 jar（装前仍做防御性备份）。它与 Fabric 系明确不兼容、与 Forge 系 P0 不自动组合
 * （见 {@link LoaderInstaller}）。
 */
public enum LoaderType {
    FABRIC("Fabric", "fabric", "fabric",
            "https://meta.fabricmc.net/v2/versions/loader/", "https://maven.fabricmc.net/",
            "fabric-loader"),
    QUILT("Quilt", "quilt", "quilt",
            "https://meta.quiltmc.org/v3/versions/loader/",
            "https://maven.quiltmc.org/repository/release/", "quilt-loader"),
    LEGACY_FABRIC("LegacyFabric", "legacyfabric", "legacy-fabric",
            "https://meta.legacyfabric.net/v2/versions/loader/", "https://maven.legacyfabric.net/",
            "legacy-fabric-loader"),
    // —— 新增（v0.8）：安装器 jar 型，metaBase 为 null（不走 metaUrl 合并） ——
    FORGE("Forge", "forge", "forge", null, "https://maven.minecraftforge.net/", "forge"),
    NEOFORGE("NeoForge", "neoforge", "neoforge", null, "https://maven.neoforged.net/releases/",
            "neoforge"),
    // —— 新增（v0.8.7）：OptiFine 独立加载器/独立版本，按安装器型复用流程 ——
    // metaBase=null；maven 填官网仅作占位，实际 jar 走 BMCLAPI，URL 拼装收口在
    // LoaderInstaller.optifineVersionsUrl() / optifineDownloadUrl()（实证后一处改）。
    // 不能引用本枚举下面才声明的 BMCLAPI 常量（枚举常量构造期 forward reference 非法），故写字面量。
    OPTIFINE("OptiFine", "optifine", "optifine", null, "https://optifine.net/", "optifine");

    /** BMCLAPI 镜像根（Forge/NeoForge 版本列表 + installer jar 镜像源；OptiFine 也走它）。 */
    public static final String BMCLAPI = "https://bmclapi2.bangbang93.com";

    private final String label;
    /** 写进 {@code gblLoader.type} 的标识（卸载 / 状态识别按它认人）。 */
    private final String id;
    /** Modrinth 分类名（搜 mod facets 用）。 */
    private final String category;
    /** meta API 基地址，自带尾 '/'；安装器型为 null。 */
    private final String metaBase;
    /** maven 仓库基地址，自带尾 '/'。 */
    private final String maven;
    /** mrpack {@code dependencies} 里的键名。 */
    private final String depKey;

    LoaderType(String label, String id, String category, String metaBase, String maven,
               String depKey) {
        this.label = label;
        this.id = id;
        this.category = category;
        this.metaBase = metaBase;
        this.maven = maven;
        this.depKey = depKey;
    }

    public String label() {
        return label;
    }

    public String id() {
        return id;
    }

    public String category() {
        return category;
    }

    public String metaBase() {
        return metaBase;
    }

    public String maven() {
        return maven;
    }

    public String depKey() {
        return depKey;
    }

    /** 元数据型（Fabric/Quilt/LegacyFabric）：走 metaUrl + launcherMeta 合并。 */
    public boolean isMeta() {
        return this == FABRIC || this == QUILT || this == LEGACY_FABRIC;
    }

    /**
     * 安装器型（Forge/NeoForge/OptiFine）：走下载 installer jar + 生成独立派生 profile。
     * Forge/NeoForge 无头运行 installer；OptiFine 不跑 installer GUI，改为把 jar 当 library
     * 自写派生 profile（详见 {@link LoaderInstaller}）。
     */
    public boolean isInstaller() {
        return this == FORGE || this == NEOFORGE || this == OPTIFINE;
    }

    /**
     * Fabric 系（Fabric / Quilt / LegacyFabric）：与 OptiFine 明确不兼容（P0 不组合）。
     *
     * <p>这是「兼容性分组」语义，和「集成方式」语义的 {@link #isMeta()} 分开：将来若新增
     * 别的元数据型加载器，两者不必然等价。服务层的 OptiFine 互斥判定按本方法认人。
     */
    public boolean isFabricFamily() {
        return this == FABRIC || this == QUILT || this == LEGACY_FABRIC;
    }

    /**
     * 某 MC 版本的加载器列表地址：{@code metaBase + mcVersion}。
     * 安装器型 {@link #metaBase} 为 null，这里返回 null（调用方不会拿它去拉列表）。
     */
    public String metaUrl(String mcVersion) {
        return metaBase == null ? null : metaBase + mcVersion;
    }

    /**
     * mrpack {@code dependencies} 键 → 枚举。
     *
     * @param key 键名（{@code fabric} / {@code quilt} / {@code legacy-fabric} /
     *            {@code forge} / {@code neoforge} / {@code optifine}）
     * @return 对应枚举；不认识的键返回 null
     */
    public static LoaderType fromDepKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String want = key.trim().toLowerCase(Locale.ROOT);
        for (LoaderType type : values()) {
            if (type.depKey.equals(want)) {
                return type;
            }
        }
        return null;
    }
}
