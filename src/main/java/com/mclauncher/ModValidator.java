package com.mclauncher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 启动前模组校验（0.8.5 建，0.9.1 重发扩展为多类别全量收集）。
 *
 * <p>纯本地（扫盘 + 读 {@link ModIndex} + 读 jar 元数据 + 读版本 json），<b>不联网</b>——
 * 既不让启动变慢，也不在离线时误拦（离线本就拉不到依赖信息，拦了反而进不去游戏）。
 * 返回非空清单时，{@link GameLauncher#prepare} 抛 {@link GameLauncher.Blocked}，
 * 界面把所有问题一次列全，不把游戏拉起来。
 *
 * <p>覆盖的类别（一律全量收集，不 break、不截断）：
 * <ul>
 *   <li><b>加载器没安装 / 不对</b>：原版实例塞了 Fabric/Forge 模组，或模组加载器家族与实例冲突。
 *       认不出的模组 / 认不出的游戏加载器一律不拦（宁可不报，别误伤）。</li>
 *   <li><b>兼容性</b>：同一 modId 装了多个版本、Fabric 系与 Forge 系专属模组混装、
 *       OptiFine 与 Sodium/Iris（含 Oculus）互斥。</li>
 *   <li><b>MC 版本不对</b>：从 fabric.mod.json / mcmod.info 能读出精确声明版本、且与当前实例
 *       主次版本不符才报；范围表达式、元数据读不出一律跳过，绝不误报。</li>
 *   <li><b>没安装前置</b>：仅针对 GBL 安装的 Modrinth 模组，按 {@link ModIndex} 的 requires 对账。
 *       配合 {@link ModIndex.Entry#requiresTitles()} 显示真名（如「Sodium」），查不到回退显示 ID。</li>
 *   <li><b>其它可靠判定</b>：jar 损坏打不开、索引 sha1 与磁盘文件不符、requires 指向自身
 *       （循环前置）。</li>
 * </ul>
 *
 * <p><b>禁用模组不参与任何校验</b>：{@code .jar.disabled} 运行期根本不加载，不能把它当启用模组
 * 误拦启动（0.9.1 刚修的 bug，下面的 enabled 收口务必保留）。
 */
public final class ModValidator {

    private ModValidator() {
        // 静态工具类，别让人 new 出来
    }

    /**
     * 校验某游戏版本已装模组；返回「阻断启动」的问题清单（空 = 可以启动）。
     * 全量收集、一次返回，不联网、不抛异常。
     */
    public static List<String> validate(String gameId) {
        List<String> problems = new ArrayList<>();
        if (gameId == null) {
            return problems;
        }
        String gameFam = detectGameLoader(gameId);
        String currentMc = ContentValidator.gameMcVersion(gameId);

        // 只扫一次盘：ModStore.scan() 全量（含 .disabled 禁用态），供游戏管理页显示「已禁用」行；
        // 但运行期 Forge/Fabric 只加载 .jar，.jar.disabled 根本不会进游戏。所以校验层必须按
        // enabled() 收口出「当前真正会被加载的文件」，否则禁用模组会被当成启用模组误拦启动。
        List<ModStore.Mod> allMods = ModStore.scan();
        List<ModStore.Mod> enabled = new ArrayList<>();
        Set<String> enabledFiles = new HashSet<>();
        Map<String, List<String>> byModId = new HashMap<>();
        for (ModStore.Mod mod : allMods) {
            if (!mod.enabled()) {
                continue;
            }
            enabled.add(mod);
            enabledFiles.add(mod.fileName());
            if (mod.modId() != null && !mod.modId().isBlank()) {
                byModId.computeIfAbsent(mod.modId(), k -> new ArrayList<>())
                        .add(mod.fileName());
            }
        }

        // 1) 加载器没安装 / 加载器不对
        checkLoaders(gameFam, enabled, problems);
        // 2) 兼容性：重复 modId、Fabric/Forge 混装、OptiFine × Sodium/Iris
        checkCompatibility(enabled, byModId, problems);
        // 3) jar 损坏 + MC 版本不对（一次打开 jar 复用，纯本地、读不出就跳过）
        checkJarHealthAndMcVersion(enabled, currentMc, problems);
        // 4) 缺前置（显示真名）/ 循环前置 / sha1 不符 / 索引自洽
        checkIndex(gameId, enabledFiles, allMods, problems);
        return problems;
    }

    // ---------------------------------------------------------------- 1) 加载器

    private static void checkLoaders(String gameFam, List<ModStore.Mod> enabled,
                                     List<String> problems) {
        if (gameFam == null) {
            return;   // 认不出游戏加载器（版本 json 不在 / 读不了），不拦，避免误伤
        }
        for (ModStore.Mod mod : enabled) {
            String modFam = modLoaderFamily(mod);
            if (modFam == null || compatible(gameFam, modFam)) {
                continue;   // 认不出的模组 / 匹配的模组都不报
            }
            if ("vanilla".equals(gameFam)) {
                problems.add("「" + mod.displayName() + "」是 " + famLabel(modFam)
                        + " 模组，但当前游戏是原版、还没安装任何模组加载器——"
                        + "先去「下载」页给这个版本装 " + famLabel(modFam)
                        + " 加载器，否则模组不会加载、游戏还可能崩溃");
            } else {
                problems.add("「" + mod.displayName() + "」需要 " + famLabel(modFam)
                        + " 加载器，但当前游戏用的是 " + famLabel(gameFam)
                        + "，加载器不匹配，游戏可能直接崩溃");
            }
        }
    }

    // ---------------------------------------------------------------- 2) 兼容性

    private static void checkCompatibility(List<ModStore.Mod> enabled,
                                           Map<String, List<String>> byModId,
                                           List<String> problems) {
        // 同一 modId 装了多个版本（不同文件）
        for (Map.Entry<String, List<String>> e : byModId.entrySet()) {
            if (e.getValue().size() > 1) {
                problems.add("模组「" + e.getKey() + "」同时装了 " + e.getValue().size()
                        + " 个版本（" + String.join("、", e.getValue())
                        + "），运行期会冲突崩溃，保留一个、其余禁用或卸载");
            }
        }
        // Fabric/Quilt 专属与 Forge/NeoForge 专属混装
        boolean hasFabric = false;
        boolean hasForge = false;
        for (ModStore.Mod mod : enabled) {
            String fam = modLoaderFamily(mod);
            if ("fabric".equals(fam)) {
                hasFabric = true;
            } else if ("forge".equals(fam)) {
                hasForge = true;
            }
        }
        if (hasFabric && hasForge) {
            problems.add("同时装了 Fabric/Quilt 专属模组和 Forge/NeoForge 专属模组，"
                    + "两种加载器的模组不能混在一个游戏里，按当前加载器保留一类、其余禁用或卸载");
        }
        // OptiFine 与 Sodium / Iris / Oculus 互斥
        boolean optifine = false;
        boolean sodiumIris = false;
        for (ModStore.Mod mod : enabled) {
            String tag = (safe(mod.displayName()) + " " + safe(mod.modId()) + " "
                    + safe(mod.fileName())).toLowerCase(Locale.ROOT);
            if (tag.contains("optifine")) {
                optifine = true;
            }
            if (tag.contains("sodium") || tag.contains("iris") || tag.contains("oculus")) {
                sodiumIris = true;
            }
        }
        if (optifine && sodiumIris) {
            problems.add("OptiFine 与 Sodium / Iris（含 Oculus）同时启用会互相冲突、游戏崩溃，"
                    + "请禁用 OptiFine 或 Sodium/Iris，只保留一套渲染优化方案");
        }
    }

    // ---------------------------------------------------------------- 3) jar 健康 + MC 版本

    private static void checkJarHealthAndMcVersion(List<ModStore.Mod> enabled,
                                                   String currentMc, List<String> problems) {
        String currentSeries = mcSeries(currentMc);
        for (ModStore.Mod mod : enabled) {
            String declared = null;
            try (ZipFile zip = new ZipFile(mod.file().toFile())) {
                declared = readDeclaredMc(zip);
            } catch (IOException | RuntimeException ex) {
                problems.add("模组「" + mod.displayName() + "」（" + mod.fileName()
                        + "）不是有效的 jar/zip 或已损坏、无法打开，"
                        + "删掉重新下载这个模组，否则加载器会直接报错");
                continue;
            }
            if (declared == null || currentSeries == null) {
                continue;   // 读不出声明版本 / 认不出当前 MC 版本：跳过，绝不误报
            }
            String declaredSeries = mcSeries(declared);
            if (declaredSeries != null && !declaredSeries.equals(currentSeries)) {
                problems.add("模组「" + mod.displayName() + "」（" + mod.fileName()
                        + "）声明支持 MC " + declared + "，但当前游戏是 MC " + currentMc
                        + "，版本不匹配，可能进不去游戏或崩溃，换成对应 MC 版本的模组");
            }
        }
    }

    /**
     * 读 jar 元数据里声明支持的 MC 版本。只认「精确版本号」：
     * {@code fabric.mod.json} 的 {@code depends.minecraft}、{@code mcmod.info} 的 {@code mcversion}。
     * 范围表达式（{@code >=1.20} / {@code ~1.20} / {@code [1.20,1.21)}）与
     * {@code META-INF/mods.toml} 的 {@code versionRange} 无法可靠还原成单一版本，
     * 一律返回 null（读不到就跳过，绝不误报）。
     */
    private static String readDeclaredMc(ZipFile zip) {
        Object fabric = parseZipJson(zip, "fabric.mod.json");
        if (fabric instanceof Map<?, ?> fab && fab.get("depends") instanceof Map<?, ?> fdep
                && fdep.get("minecraft") instanceof String fmc) {
            String exact = exactMcVersion(fmc);
            if (exact != null) {
                return exact;
            }
        }
        Object info = parseZipJson(zip, "mcmod.info");
        Map<?, ?> first = null;
        if (info instanceof List<?> arr && !arr.isEmpty()
                && arr.get(0) instanceof Map<?, ?> arrFirst) {
            first = arrFirst;
        } else if (info instanceof Map<?, ?> obj
                && obj.get("modList") instanceof List<?> modList
                && !modList.isEmpty() && modList.get(0) instanceof Map<?, ?> modFirst) {
            first = modFirst;
        }
        if (first != null && first.get("mcversion") instanceof String mc) {
            return exactMcVersion(mc);
        }
        return null;
    }

    private static Object parseZipJson(ZipFile zip, String entry) {
        String text = readZipText(zip, entry);
        if (text == null) {
            return null;
        }
        try {
            return Json.parse(text);
        } catch (RuntimeException ex) {
            return null;   // 元数据 JSON 坏了：读不到就跳过
        }
    }

    private static String readZipText(ZipFile zip, String entry) {
        ZipEntry ze = zip.getEntry(entry);
        if (ze == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(ze)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == 0xFEFF) {
                text = text.substring(1);   // 有些元数据带 UTF-8 BOM
            }
            return text;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    /** 只认精确版本号 1.20 / 1.20.1；范围 / 通配符 / 带 x 的一律 null。 */
    private static String exactMcVersion(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.matches("\\d+\\.\\d+(?:\\.\\d+)?") ? t : null;
    }

    /** 归一化到「主版本.次版本」（1.20.1 → 1.20、1.7.10 → 1.7）；认不出返回 null。 */
    private static String mcSeries(String v) {
        if (v == null) {
            return null;
        }
        Matcher m = Pattern.compile("(\\d+)\\.(\\d+)").matcher(v);
        return m.find() ? m.group(1) + "." + m.group(2) : null;
    }

    // ---------------------------------------------------------------- 4) 索引 / 前置 / 哈希

    private static void checkIndex(String gameId, Set<String> enabledFiles,
                                   List<ModStore.Mod> allMods, List<String> problems) {
        List<ModIndex.Entry> entries = ModIndex.entries(gameId);
        // 启用态文件名 → 真实路径（含一级子目录），供 sha1 对账
        Map<String, Path> enabledPaths = new HashMap<>();
        for (ModStore.Mod mod : allMods) {
            if (mod.enabled()) {
                enabledPaths.put(mod.fileName(), mod.file());
            }
        }
        Set<String> installedPids = new HashSet<>();
        for (ModIndex.Entry e : entries) {
            if (e.filename() == null || !enabledFiles.contains(e.filename())) {
                continue;   // 已禁用 / 已卸载的索引条目不算「已装」
            }
            if (e.projectId() != null) {
                installedPids.add(e.projectId());
            }
        }
        for (ModIndex.Entry e : entries) {
            if (e.filename() == null || !enabledFiles.contains(e.filename())) {
                continue;   // 已禁用 / 已卸载的模组不再触发任何拦截
            }
            verifySha1(e, enabledPaths.get(e.filename()), allMods, problems);
            List<String> requires = e.requires();
            if (requires == null) {
                continue;
            }
            Map<String, String> titles = e.requiresTitles() == null
                    ? Map.of() : e.requiresTitles();
            for (String reqPid : requires) {
                if (reqPid == null || reqPid.isBlank()) {
                    continue;
                }
                if (reqPid.equals(e.projectId())) {
                    problems.add("「" + displayNameOf(e.filename(), allMods)
                            + "」的前置列表指向了它自己（循环前置），索引数据异常，"
                            + "删掉重装这个模组可修复");
                    continue;
                }
                if (!installedPids.contains(reqPid)) {
                    String title = titles.get(reqPid);
                    if (title == null || title.isBlank()) {
                        // 0.9.1 r3：旧索引没记 requiresTitles，先用离线内置表把常见前置
                        // （AANobbMI→Sodium 等）即时显示成真名，不必等用户重装模组。
                        title = ModIndex.knownTitle(reqPid);
                    }
                    String dep = (title != null && !title.isBlank())
                            ? "「" + title + "」"
                            : "（Modrinth 项目 ID：" + reqPid + "；这条模组是旧版 GBL 装的，"
                            + "索引里没记名字，GBL 已在后台补全，也可在游戏管理页重装它立即显示真名）";
                    problems.add("「" + displayNameOf(e.filename(), allMods) + "」需要前置模组"
                            + dep + "，它还没装——缺前置游戏可能直接崩溃，"
                            + "先去下载页把这个前置装上");
                }
            }
        }
    }

    private static void verifySha1(ModIndex.Entry e, Path file,
                                   List<ModStore.Mod> allMods, List<String> problems) {
        String expected = e.sha1();
        if (expected == null || expected.isBlank() || file == null) {
            return;   // 没有记录哈希 / 定位不到文件：跳过
        }
        try {
            String actual = sha1(file);
            if (actual != null && !actual.equalsIgnoreCase(expected.trim())) {
                problems.add("模组「" + displayNameOf(e.filename(), allMods) + "」（"
                        + e.filename() + "）的文件内容和安装时的记录对不上（sha1 不符），"
                        + "可能被人替换过或下载时损坏了，删掉重新装一次");
            }
        } catch (IOException | RuntimeException ex) {
            // 读不出来就跳过，别因校验本身把启动拦了
        }
    }

    private static String sha1(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            return null;   // JVM 没有 SHA-1（理论不可能），跳过不报错
        }
    }

    // ---------------------------------------------------------------- 公共 / 工具

    /**
     * 当前游戏版本的加载器家族：{@code "fabric"} / {@code "forge"} / {@code "vanilla"} / null。
     *
     * <p>null = 认不出（版本 json 不在 / 读不了）——这种情况下不拦加载器不匹配，避免误伤。
     * 版本 json 在但没有任何加载器坐标 = 原版（{@code "vanilla"}），原版塞 Fabric/Forge 模组
     * 同样要拦。ContentValidator 也复用它判整合包加载器是否对得上。
     */
    public static String detectGameLoader(String gameId) {
        Path json = Platform.minecraftDir().resolve("versions").resolve(gameId)
                .resolve(gameId + ".json");
        if (!Files.isRegularFile(json)) {
            return null;
        }
        String text;
        try {
            text = Files.readString(json, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (IOException | RuntimeException ex) {
            return null;
        }
        // 顺序很重要：NeoForge 的坐标里也含 "forge" 子串，先认 neoforged 再认 minecraftforge
        if (text.contains("net.neoforged") || text.contains("\"neoforge\"")) {
            return "forge";
        }
        if (text.contains("net.minecraftforge") || text.contains("\"forge\"")) {
            return "forge";
        }
        if (text.contains("org.quiltmc") || text.contains("quilt_loader")
                || text.contains("quiltmc")) {
            return "fabric";
        }
        if (text.contains("net.fabricmc") || text.contains("fabricloader")
                || text.contains("fabric-loader")) {
            return "fabric";
        }
        return "vanilla";
    }

    /** 模组 jar 元数据声明的加载器归到家族；认不出返回 null。 */
    private static String modLoaderFamily(ModStore.Mod mod) {
        String loader = mod.loader();
        if (loader == null) {
            return null;
        }
        return switch (loader) {
            case "fabric", "quilt", "legacyfabric" -> "fabric";
            case "forge", "neoforge", "liteLoader" -> "forge";
            default -> null;
        };
    }

    private static String famLabel(String fam) {
        return "fabric".equals(fam) ? "Fabric/Quilt" : "Forge/NeoForge";
    }

    /** 游戏的加载器家族是否兼容模组的加载器家族。null（认不出）一律当兼容，不拦。 */
    private static boolean compatible(String gameFam, String modFam) {
        if (gameFam == null) {
            return true;
        }
        return gameFam.equals(modFam);
    }

    /** 文件名 → 显示名（拿这一次扫描的结果，不重复扫盘；没有就退回文件名）。 */
    private static String displayNameOf(String fileName, List<ModStore.Mod> mods) {
        if (fileName == null) {
            return "未知模组";
        }
        for (ModStore.Mod mod : mods) {
            if (fileName.equals(mod.fileName())) {
                return mod.displayName();
            }
        }
        return fileName;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** 仅给 {@link ModrinthClient} 复用：从已解析的项目里抠「必需」前置 projectId。 */
    static List<String> requiredOf(ModrinthClient.Project p) {
        List<String> out = new ArrayList<>();
        if (p == null || p.dependencies() == null) {
            return out;
        }
        for (ModrinthClient.Dependency d : p.dependencies()) {
            if ("required".equals(d.type()) && d.projectId() != null) {
                out.add(d.projectId());
            }
        }
        return out;
    }
}
