package com.mclauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 中文别名搜索（0.8.6 引入，0.8.7 改为按下载 tab 上下文隔离）。
 *
 * <p>Modrinth / CurseForge 的索引里只有英文项目名，直接把「钠」「材质包」丢给搜索接口必然零结果。
 * 这里维护「中文名 → 英文项目名」的映射，把中文查询扩展成英文关键词再搜：
 * <ol>
 *   <li>内置主表（约 90 条，离线可用，覆盖常见优化 / 科技 / 魔法 / 冒险模组与知名整合包）；</li>
 *   <li>用户可在 {@code ~/.gbl/mod-aliases.txt} 自行扩充，每行 {@code 中文=English|Another English}；</li>
 *   <li>模糊包含匹配：输入「钠模组」「钠优化」也能命中「钠」，输入部分中文名即可。</li>
 * </ol>
 *
 * <p><b>为什么必须按 context 隔离词表（0.8.7）：</b>
 * 0.8.6 只有「模组 / 整合包」两个搜索入口，一张表够用。0.8.7 新增「资源包 / 光影 / 世界」
 * 三个下载 tab 后，若继续共用同一张主表就会「串味」——主表里 {@code 光影→Iris Shaders}、
 * {@code 地图→JourneyMap} 其实都是 <em>mod</em>：在光影 tab 搜「光影」会补出 Iris Shaders（一个模组）
 * 而不是光影包，在资源包 tab 搜「材质包」则主表里根本没有词、一条都补不出。
 * 因此把词表按 tab 上下文切开：
 * <ul>
 *   <li>{@code mod} / {@code modpack}：只用内置主表；</li>
 *   <li>{@code resourcepack} / {@code shader} / {@code world}：各用一张上下文专用表，
 *       <b>与内置主表互不参与</b>，从根上避免补出另一个 tab 的内容；</li>
 *   <li>用户自定义 {@code ~/.gbl/mod-aliases.txt} 仍对<b>所有</b> context 生效（用户主动扩充，优先级高于内置）；</li>
 *   <li>未知 context 一律按 {@code mod} 处理，保证 0.8.6 的四个旧调用链行为不变。</li>
 * </ul>
 *
 * <p>只对含 CJK 的查询做扩展；英文 / 数字查询原样交给 API，不改变既有行为
 * （英文缩写如 {@code bsl} / {@code seus} 放在词表里，是为了服务「bsl 光影」这类中英混合查询）。
 */
public final class ModAliases {

    /**
     * 一次查询最多扩展出几条关键词，避免中文短词命中过多别名把接口打爆。
     *
     * <p>0.9.1 由 4 下调到 3：ShellView 的 {@code searchModrinthAliased} /
     * {@code searchContentModrinth} / {@code searchCurseForgeAliased} 对扩展出的词是<b>串行</b>
     * 逐个发请求的（每个词还会各自走一次镜像 + 官方竞速），第 4 个词就是第 4 倍往返。
     * 3 条 = 原文 + 最多 2 个英文别名，已能完整覆盖词表里所有「一对多」条目
     * （如「材质包→Faithful / Vanilla Tweaks」「光影包→Complementary Shaders / BSL Shaders」），
     * 只裁掉模糊匹配凑出来的第 3 个边缘别名，搜索质量基本不受影响，中文搜索少一次串行往返。
     * 再叠加 HttpRace 的 60s TTL 元数据缓存，重复词命中缓存后连竞速都省了。
     */
    private static final int MAX_TERMS = 3;

    /** 内置主表：模组 / 整合包词表（0.8.6 原表逐字保留），只对 mod / modpack context 生效。 */
    private static final Map<String, String[]> ALIASES = new LinkedHashMap<>();
    /** 上下文专用表：仅在对应 context 参与扩展，与内置主表完全隔离。 */
    private static final Map<String, String[]> RESOURCEPACK_ALIASES = new LinkedHashMap<>();
    private static final Map<String, String[]> SHADER_ALIASES = new LinkedHashMap<>();
    private static final Map<String, String[]> WORLD_ALIASES = new LinkedHashMap<>();
    /** 用户自定义别名：对所有 context 生效，同名项覆盖内置。 */
    private static final Map<String, String[]> USER_ALIASES = new LinkedHashMap<>();
    /** 各 context 的最终词表视图（内置表 + 用户表覆盖），类加载时构建一次。 */
    private static final Map<String, Map<String, String[]>> MERGED = new LinkedHashMap<>();

    static {
        // —— 性能优化 / 渲染 ——
        p("钠", "Sodium");
        p("锂", "Lithium");
        p("铱", "Iridium");
        p("铷", "Rubidium");
        p("磷", "Phosphor");
        p("嵌入式", "Embeddium");
        p("高清修复", "OptiFine");
        p("高清修补", "OptiFine");
        p("光影", "Iris Shaders");
        p("星光", "Starlight");
        p("现代修复", "ModernFix");
        p("实体剔除", "EntityCulling");
        p("快速启动", "LazyDFU");
        // —— 实用 / 界面 ——
        p("小地图", "JourneyMap");
        p("旅行地图", "JourneyMap");
        p("地图", "JourneyMap");
        p("背包整理", "Inventory Profiles Next");
        p("R键整理", "Inventory Profiles Next");
        p("连锁挖矿", "FTB Ultimine");
        p("一键挖矿", "FTB Ultimine");
        p("动态光源", "Dynamic Lights");
        p("矿物透视", "XRay");
        p("透视", "XRay");
        p("物品管理器", "Just Enough Items");
        p("合成表", "Just Enough Items");
        p("模组菜单", "Mod Menu");
        p("配置界面", "Cloth Config");
        p("饰品栏", "Trinkets");
        p("饰品", "Trinkets");
        p("旅行背包", "Traveler's Backpack");
        p("背包", "Traveler's Backpack");
        p("伤害显示", "Damage Indicators");
        p("血条", "Neat");
        p("墓碑", "Gravestone");
        p("投影", "Litematica");
        p("创世神", "WorldEdit");
        p("世界编辑", "WorldEdit");
        p("回放", "ReplayMod");
        p("录像", "ReplayMod");
        p("自由视角", "FreeCam");
        p("缩放", "Ok Zoomer");
        p("物品物理", "ItemPhysic");
        p("真实物理", "Physics Mod");
        p("物理", "Physics Mod");
        p("幸运方块", "Lucky Block");
        p("变形", "Morph");
        p("家具", "MrCrayfish's Furniture Mod");
        p("农夫乐事", "Farmer's Delight");
        // —— 科技 / 魔法 / 冒险 ——
        p("机械动力", "Create");
        p("应用能源", "Applied Energistics 2");
        p("精炼存储", "Refined Storage");
        p("通用机械", "Mekanism");
        p("热力膨胀", "Thermal Expansion");
        p("沉浸工程", "Immersive Engineering");
        p("存储抽屉", "Storage Drawers");
        p("铁箱子", "Iron Chests");
        p("工业", "IndustrialCraft 2");
        p("林业", "Forestry");
        p("无尽贪婪", "Avaritia");
        p("植物魔法", "Botania");
        p("血魔法", "Blood Magic");
        p("神秘时代", "Thaumcraft");
        p("星辉魔法", "Astral Sorcery");
        p("暮色森林", "The Twilight Forest");
        p("以太", "The Aether");
        p("匠魂", "Tinkers' Construct");
        p("拔刀剑", "SlashBlade");
        p("冰与火", "Ice and Fire");
        p("冰火传说", "Ice and Fire");
        p("灾变", "Cataclysm");
        p("宝可梦", "Cobblemon");
        p("精灵宝可梦", "Cobblemon");
        p("虚无世界", "Advent of Ascension");
        p("星系", "Galacticraft");
        p("太空", "Galacticraft");
        p("更好的下界", "BetterNether");
        p("更好的末地", "BetterEnd");
        p("地牢", "When Dungeons Arise");
        // —— 知名整合包 ——
        p("天空工厂", "SkyFactory");
        p("石头世界", "StoneBlock");
        p("空岛", "SkyBlock");
        p("格雷科技", "GregTech");
        p("格雷", "GregTech");
        p("新视野", "GregTech New Horizons");
        p("群峦传说", "TerraFirmaCraft");
        p("群峦", "TerraFirmaCraft");
        p("更好的我的世界", "Better Minecraft");
        p("乌托邦", "Utopia");

        // —— 资源包专用表（不参与 mod / modpack，也不受主表「地图→JourneyMap」等污染）——
        rp("材质包", "Faithful", "Vanilla Tweaks");
        rp("材质", "Faithful");
        rp("原版增强", "Vanilla Tweaks");
        rp("3D材质", "Faithful 3D");
        rp("深色", "Default Dark Mode");
        rp("深色模式", "Default Dark Mode");
        rp("卡通", "Bare Bones");
        rp("简约", "Bare Bones");
        rp("faithful", "Faithful");
        rp("vanillatweaks", "Vanilla Tweaks");
        rp("barebones", "Bare Bones");
        rp("defaultdarkmode", "Default Dark Mode");

        // —— 光影专用表（「光影」在这里补的是光影包，而不是主表里的 Iris Shaders mod）——
        sh("光影包", "Complementary Shaders", "BSL Shaders");
        sh("bsl", "BSL Shaders");
        sh("互补", "Complementary Shaders");
        sh("complementary", "Complementary Shaders");
        sh("超写实", "SEUS");
        sh("seus", "SEUS");
        sh("低配光影", "MakeUp UltraFast");
        sh("低配", "MakeUp UltraFast");
        sh("makeup", "MakeUp UltraFast");
        sh("sildurs", "Sildur's Vibrant Shaders");

        // —— 世界 / 地图专用表（world 数据源只有 CurseForge，这里只用通用地图类型词）——
        wo("空岛", "SkyBlock");
        wo("生存岛", "SkyBlock");
        wo("天空岛", "SkyBlock");
        wo("跑酷", "Parkour");
        wo("起床战争", "BedWars");
        wo("建筑", "Build");
        wo("解密", "Puzzle");
        wo("解谜", "Puzzle");

        loadUserAliases();

        // 预构建各 context 的最终词表：内置（主表或专用表）+ 用户自定义覆盖。
        // mod / modpack 的合并结果与 0.8.6「用户别名直接 put 进主表」逐字一致，保证向后兼容。
        for (String ctx : new String[]{"mod", "modpack", "resourcepack", "shader", "world"}) {
            MERGED.put(ctx, merge(ctx));
        }
    }

    private ModAliases() {
    }

    /** 内置主表条目（mod / modpack）。 */
    private static void p(String cn, String... en) {
        put(ALIASES, cn, en);
    }

    /** 资源包专用表条目。 */
    private static void rp(String cn, String... en) {
        put(RESOURCEPACK_ALIASES, cn, en);
    }

    /** 光影专用表条目。 */
    private static void sh(String cn, String... en) {
        put(SHADER_ALIASES, cn, en);
    }

    /** 世界专用表条目。 */
    private static void wo(String cn, String... en) {
        put(WORLD_ALIASES, cn, en);
    }

    private static void put(Map<String, String[]> table, String cn, String... en) {
        table.put(cn.toLowerCase(Locale.ROOT), en);
    }

    /** 用户自定义别名：{@code 中文=English One|English Two}，# 开头为注释，解析失败只跳过该行。 */
    private static void loadUserAliases() {
        try {
            Path file = Paths.get(System.getProperty("user.home"), ".gbl", "mod-aliases.txt");
            if (!Files.isRegularFile(file)) {
                return;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#") || !s.contains("=")) {
                    continue;
                }
                int i = s.indexOf('=');
                String cn = s.substring(0, i).trim().toLowerCase(Locale.ROOT);
                String[] en = s.substring(i + 1).split("\\|");
                List<String> clean = new ArrayList<>();
                for (String e : en) {
                    if (!e.trim().isEmpty()) {
                        clean.add(e.trim());
                    }
                }
                if (!cn.isEmpty() && !clean.isEmpty()) {
                    USER_ALIASES.put(cn, clean.toArray(new String[0]));
                }
            }
        } catch (IOException | RuntimeException ex) {
            System.err.println("[alias] 自定义别名读取失败，使用内置表: " + ex.getMessage());
        }
    }

    /** 未知 / null context 一律按 {@code mod} 处理。 */
    private static String effectiveContext(String context) {
        if (context == null) {
            return "mod";
        }
        switch (context) {
            case "mod":
            case "modpack":
            case "resourcepack":
            case "shader":
            case "world":
                return context;
            default:
                return "mod";
        }
    }

    /** 该 context 的内置表：专用表只认自己的，主表兜底 mod / modpack。 */
    private static Map<String, String[]> builtinFor(String context) {
        switch (effectiveContext(context)) {
            case "resourcepack":
                return RESOURCEPACK_ALIASES;
            case "shader":
                return SHADER_ALIASES;
            case "world":
                return WORLD_ALIASES;
            default:
                return ALIASES;
        }
    }

    /** 内置表在前、用户表覆盖在后；用户自定义对所有 context 生效。 */
    private static Map<String, String[]> merge(String context) {
        Map<String, String[]> out = new LinkedHashMap<>();
        out.putAll(builtinFor(context));
        out.putAll(USER_ALIASES);
        return out;
    }

    /** 取该 context 的最终词表；未知 context 落到 {@code mod}。 */
    private static Map<String, String[]> tableFor(String context) {
        return MERGED.get(effectiveContext(context));
    }

    /**
     * 把用户输入扩展成多条查询词：第一条永远是原文，后面是按中文别名补出的英文关键词。
     * 结果去重、最多 {@link #MAX_TERMS} 条。
     *
     * <p>向后兼容入口：等价于 {@code expand(raw, "mod")}，供 0.8.6 的模组 / 整合包调用链使用。
     */
    public static List<String> expand(String raw) {
        return expand(raw, "mod");
    }

    /**
     * 按下载 tab 上下文扩展查询词：第一条永远是原文，后面是按该 context 词表补出的英文关键词。
     * 结果去重、最多 {@link #MAX_TERMS} 条。
     *
     * @param context {@code mod} / {@code modpack} / {@code resourcepack} / {@code shader} / {@code world}；
     *                未知值按 {@code mod} 处理。专用表与主表隔离，用户自定义表对所有 context 生效。
     */
    public static List<String> expand(String raw, String context) {
        String q = raw == null ? "" : raw.trim();
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        terms.add(q);
        // 0.9.1 复核确认：空 query（推荐 / 浏览）与非 CJK 查询一律只返回 1 个元素（原文），
        // 绝不在这里放大请求数——否则「推荐」浏览会凭空从 1 个请求变成 N 个。此分支保持不变。
        if (q.isEmpty() || !hasCjk(q)) {
            return new ArrayList<>(terms);
        }
        String norm = normalize(q);
        if (norm.isEmpty()) {
            return new ArrayList<>(terms);
        }
        Map<String, String[]> table = tableFor(context);
        // 精确命中优先
        collect(terms, table.get(norm));
        // 模糊：中文短词「钠」能命中「钠模组」，反过来「钠优化」也能命中「钠」
        for (Map.Entry<String, String[]> e : table.entrySet()) {
            if (terms.size() >= MAX_TERMS) {
                break;
            }
            if (e.getKey().contains(norm) || norm.contains(e.getKey())) {
                collect(terms, e.getValue());
            }
        }
        return new ArrayList<>(terms);
    }

    private static void collect(Set<String> terms, String[] en) {
        if (en == null) {
            return;
        }
        for (String s : en) {
            if (terms.size() >= MAX_TERMS) {
                return;
            }
            if (s != null && !s.isBlank()) {
                terms.add(s);
            }
        }
    }

    /** 界面上给用户的提示，例如「别名扩展：Sodium」；没扩展时返回空串。 */
    public static String hint(String raw) {
        return hint(raw, "mod");
    }

    /**
     * 按 context 生成提示文案「别名扩展：X / Y」；没扩展时返回空串。
     * 与 {@link #hint(String)} 口径一致，只是词表随 context 切换。
     */
    public static String hint(String raw, String context) {
        List<String> terms = expand(raw, context);
        if (terms.size() < 2) {
            return "";
        }
        return "别名扩展：" + String.join(" / ", terms.subList(1, terms.size()));
    }

    private static String normalize(String q) {
        return q.toLowerCase(Locale.ROOT)
                .replace("模组", "").replace("mod", "")
                .replace("插件", "").replace("附属", "").replace("版", "")
                .replace(" ", "").trim();
    }

    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '\u4e00' && c <= '\u9fff') {
                return true;
            }
        }
        return false;
    }
}
