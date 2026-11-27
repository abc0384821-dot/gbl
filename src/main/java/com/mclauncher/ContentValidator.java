package com.mclauncher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 启动前「整合包 / 资源包 / 世界 / 光影」四类内容的离线校验（0.9.1 重发）。
 *
 * <p>和 {@link ModValidator} 一样由 {@link GameLauncher#prepare} 在启动前调用，结果合并成一份
 * 清单一次全显示。全部纯本地（java.nio.file + 读 zip/gzip + 读 {@link Json}），
 * <b>绝不联网、绝不抛异常、绝不因单个读不出的文件而阻断校验流程</b>。
 *
 * <p>能可靠判定才报；读不出 / 元数据缺失 / 版本认不出一律跳过，绝不误拦离线启动。
 * 只校验启用态内容（带 {@code .disabled} 后缀的跳过）；目录形态的资源包/光影包因边界
 * 不确定一律跳过，只校验 {@code .zip}。不构造 {@code ContentStore.Item} / {@code ModStore.Mod}，
 * 直接用 java.nio.file 扫目录，避免与并行改动中的 record 字段耦合。
 */
public final class ContentValidator {

    private static final String DISABLED = ".disabled";
    /** 世界安装临时目录前缀，参考 ContentStore 扫描口径，校验时跳过。 */
    private static final String WORLD_TEMP_PREFIX = ".gbl-world-";
    private static final Pattern MC_PAIR = Pattern.compile("(\\d+)\\.(\\d+)");
    private static final Pattern MC_EXACT = Pattern.compile("^(\\d+\\.\\d+(?:\\.\\d+)?)");

    private ContentValidator() {
        // 静态工具类
    }

    /** 返回四类内容的阻断问题清单（空 = 可以启动）。任何异常都在内部吞掉，绝不外抛。 */
    public static List<String> validate(String gameId) {
        List<String> problems = new ArrayList<>();
        if (gameId == null) {
            return problems;
        }
        Path mc = Platform.minecraftDir();
        String currentMc = gameMcVersion(gameId);
        String currentSeries = mcSeries(currentMc);

        checkPacks(mc.resolve("resourcepacks"), "资源包", false, problems);
        checkPacks(mc.resolve("shaderpacks"), "光影包", true, problems);
        checkShaderLoader(mc, mc.resolve("shaderpacks"), problems);
        checkWorlds(mc.resolve("saves"), problems);
        checkModpack(mc, gameId, currentMc, currentSeries, problems);
        return problems;
    }

    // ---------------------------------------------------------------- 当前 MC 版本

    /**
     * 当前游戏实例的 MC 版本（如 {@code 1.20.1}）。先读 {@code versions/<id>/<id>.json}
     * 的 {@code id} / {@code inheritsFrom}，再用 gameId 兜；都提取不出标准版本号返回 null
     * （调用方据此跳过所有版本类检查）。仅供离线比对，不联网。ModValidator 也复用它。
     */
    public static String gameMcVersion(String gameId) {
        try {
            Path json = Platform.minecraftDir().resolve("versions").resolve(gameId)
                    .resolve(gameId + ".json");
            if (Files.isRegularFile(json)) {
                Object parsed = Json.parse(Files.readString(json, StandardCharsets.UTF_8));
                if (parsed instanceof Map<?, ?> m) {
                    String id = m.get("id") instanceof String s ? s : null;
                    String inherits = m.get("inheritsFrom") instanceof String s ? s : null;
                    String v = exactMc(id);
                    if (v == null) {
                        v = exactMc(inherits);
                    }
                    if (v != null) {
                        return v;
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 读不出就用 gameId 兜
        }
        return exactMc(gameId);
    }

    /** 从头锚定抠标准版本号（1.20 / 1.20.1）；自定义 profile 名抠不出返回 null。 */
    private static String exactMc(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = MC_EXACT.matcher(raw.trim());
        return m.find() ? m.group(1) : null;
    }

    /** 归一化到「主版本.次版本」（1.20.1 → 1.20）；认不出返回 null。 */
    private static String mcSeries(String v) {
        if (v == null) {
            return null;
        }
        Matcher m = MC_PAIR.matcher(v);
        return m.find() ? m.group(1) + "." + m.group(2) : null;
    }

    // ---------------------------------------------------------------- 资源包 / 光影 zip

    /**
     * 扫一类 zip 内容。只校验启用态 {@code .zip}；目录形态一律跳过。
     * 能可靠判定的才报：zip 打不开、pack.mcmeta 存在但 JSON 损坏、光影包缺 shaders/。
     * pack.mcmeta「缺失」不报（大量正常老包没有它，报了会误拦）；pack_format 与 MC 版本
     * 无稳定一一映射（1.21+ 尤其），比不了就跳过，绝不误报。
     */
    private static void checkPacks(Path root, String label, boolean shader,
                                   List<String> problems) {
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Path> zips;
        try (var children = Files.list(root)) {
            zips = children.filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return Files.isRegularFile(p) && !n.endsWith(DISABLED) && n.endsWith(".zip");
            }).toList();
        } catch (IOException | RuntimeException ex) {
            return;   // 目录读不了就跳过，别拦启动
        }
        for (Path zip : zips) {
            String name = zip.getFileName().toString();
            try (ZipFile zf = new ZipFile(zip.toFile())) {
                ZipEntry mcmeta = zf.getEntry("pack.mcmeta");
                if (mcmeta != null) {
                    String text = readZipText(zf, mcmeta);
                    if (text != null) {
                        try {
                            Json.parse(text);   // 只验 JSON 能不能解析，不深究字段
                        } catch (RuntimeException ex) {
                            problems.add(label + "「" + name
                                    + "」里的 pack.mcmeta 内容坏了（JSON 解析失败），"
                                    + "MC 可能识别不了这个包，重新下载或手动修好 pack.mcmeta");
                        }
                    }
                }
                if (shader && !hasShadersDir(zf)) {
                    problems.add("光影包「" + name + "」里没有 shaders/ 目录，不是有效的光影包，"
                            + "换一个光影包或重新下载（MC 光影包必须含 shaders/ 文件夹）");
                }
            } catch (IOException | RuntimeException ex) {
                problems.add(label + "「" + name + "」这个 zip 打不开（不是有效的 zip 或已损坏），"
                        + "删掉重新下载 / 重新导入，坏包会让游戏读取资源时报错");
            }
        }
    }

    private static boolean hasShadersDir(ZipFile zf) {
        var entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String n = e.getName().replace('\\', '/');
            if (n.startsWith("shaders/")) {
                return true;
            }
        }
        return false;
    }

    private static String readZipText(ZipFile zip, ZipEntry entry) {
        try (InputStream in = zip.getInputStream(entry)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == 0xFEFF) {
                text = text.substring(1);
            }
            return text;
        } catch (IOException | RuntimeException ex) {
            return null;   // 读不出来按「没有 pack.mcmeta」处理，不报
        }
    }

    /** 启用态光影包存在、但 mods 里找不到 Iris / Oculus / OptiFine 时提示（读不出就跳过）。 */
    private static void checkShaderLoader(Path mc, Path shaderRoot, List<String> problems) {
        if (!Files.isDirectory(shaderRoot)) {
            return;
        }
        boolean hasEnabledShader = false;
        try (var children = Files.list(shaderRoot)) {
            for (Path p : children.toList()) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!n.endsWith(DISABLED) && (n.endsWith(".zip") || Files.isDirectory(p))) {
                    hasEnabledShader = true;
                    break;
                }
            }
        } catch (IOException | RuntimeException ex) {
            return;   // 读不了就跳过
        }
        if (!hasEnabledShader) {
            return;
        }
        Path mods = mc.resolve("mods");
        if (!Files.isDirectory(mods)) {
            return;   // mods 目录读不了 / 没有，跳过，不误报
        }
        boolean hasRenderer = false;
        try (var children = Files.list(mods)) {
            for (Path p : children.toList()) {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!n.endsWith(DISABLED) && (n.contains("iris")
                        || n.contains("oculus") || n.contains("optifine"))) {
                    hasRenderer = true;
                    break;
                }
                // 一级子目录里的加载器也算（Forge 的 1.8.9/ 这类布局）
                if (Files.isDirectory(p)) {
                    try (var sub = Files.list(p)) {
                        for (Path f : sub.toList()) {
                            String fn = f.getFileName().toString().toLowerCase(Locale.ROOT);
                            if (!fn.endsWith(DISABLED) && (fn.contains("iris")
                                    || fn.contains("oculus") || fn.contains("optifine"))) {
                                hasRenderer = true;
                                break;
                            }
                        }
                    } catch (IOException | RuntimeException ignored) {
                        // 子目录读不了就继续看别的
                    }
                }
                if (hasRenderer) {
                    break;
                }
            }
        } catch (IOException | RuntimeException ex) {
            return;
        }
        if (!hasRenderer) {
            // 0.9.9：GBL 官方支持的 OptiFine 是库 jar
            // （libraries/optifine/OptiFine/<ver>/OptiFine-<ver>.jar + launchwrapper-of），
            // 由 LoaderInstaller 写派生 profile 安装，根本不落进 mods 目录。这里再递归查一眼
            // 库目录，有 jar 就算有光影加载器，避免把合法 OptiFine 用户误拦在启动前。
            hasRenderer = hasOptiFineLibraryJar(mc.resolve("libraries").resolve("optifine"));
        }
        if (!hasRenderer) {
            problems.add("光影：shaderpacks 里有启用的光影包，但 mods 里没找到 Iris / Oculus / "
                    + "OptiFine 这类光影加载器，光影不会生效；装一个对应加载器，或先禁用光影包");
        }
    }

    /**
     * 递归查 OptiFine 库安装留下的 jar（作为库安装，不在 mods 目录）。读不到 / 目录不存在
     * 一律返回 false 跳过，绝不误报。
     */
    private static boolean hasOptiFineLibraryJar(Path optiFineLib) {
        if (!Files.isDirectory(optiFineLib)) {
            return false;
        }
        try (var stream = Files.walk(optiFineLib)) {
            return stream.anyMatch(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"));
        } catch (IOException | RuntimeException ex) {
            return false;   // 读不了就跳过，不误报
        }
    }

    // ---------------------------------------------------------------- 世界

    /**
     * 只校验能可靠判定的：level.dat 缺失、level.dat 的 gzip 损坏 / 空内容。
     * 「世界由更高版本 MC 创建」要读 {@code Data.Version.Id} 得解 NBT，本类不引入 NBT 解析器、
     * 也不依赖别处的最小 NBT 工具，版本读不出就跳过（符合「读不到就跳过」）。
     * session.lock 异常无可靠判据，同样跳过。
     */
    private static void checkWorlds(Path saves, List<String> problems) {
        if (!Files.isDirectory(saves)) {
            return;
        }
        List<Path> worlds;
        try (var children = Files.list(saves)) {
            worlds = children.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith(WORLD_TEMP_PREFIX))
                    .toList();
        } catch (IOException | RuntimeException ex) {
            return;
        }
        for (Path world : worlds) {
            String name = world.getFileName().toString();
            Path level = world.resolve("level.dat");
            if (!Files.isRegularFile(level)) {
                problems.add("世界「" + name + "」缺 level.dat（存档主文件），不是有效的世界存档，"
                        + "MC 里看不到或打不开；确认这个文件夹是不是世界，或用 level.dat_old 恢复");
                continue;
            }
            // gzip 头 + 一点内容读不出来就当损坏；只读几十字节，绝不整盘解压
            try (InputStream raw = Files.newInputStream(level);
                 GZIPInputStream gz = new GZIPInputStream(raw)) {
                byte[] buf = new byte[64];
                if (gz.read(buf) < 0) {
                    problems.add("世界「" + name + "」的 level.dat 是空的 / gzip 内容不完整，"
                            + "存档可能损坏，试试把 level.dat_old 改名成 level.dat 恢复");
                }
            } catch (IOException | RuntimeException ex) {
                problems.add("世界「" + name + "」的 level.dat 读不了（gzip 损坏或不是 NBT 存档），"
                        + "存档可能已损坏；试试把同目录下的 level.dat_old 改名成 level.dat 恢复，"
                        + "或从备份还原");
            }
        }
    }

    // ---------------------------------------------------------------- 整合包清单

    /**
     * 校验 .minecraft 根下的 {@code modrinth.index.json}（ModpackImporter 落的那份清单）：
     * JSON 损坏、声明 MC 版本与当前实例明显不符、加载器与当前实例冲突、声明的核心模组缺失。
     * 读不出 / 认不出一律跳过，不联网。
     */
    private static void checkModpack(Path mc, String gameId, String currentMc,
                                     String currentSeries, List<String> problems) {
        Path index = mc.resolve("modrinth.index.json");
        if (!Files.isRegularFile(index)) {
            return;
        }
        Object parsed;
        try {
            parsed = Json.parse(Files.readString(index, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ex) {
            problems.add("整合包：.minecraft 根下的 modrinth.index.json 读不了或已损坏"
                    + "（JSON 解析失败），整合包的更新 / 导出会不正常，重新导入一次整合包可修复");
            return;
        }
        if (!(parsed instanceof Map<?, ?> root)) {
            problems.add("整合包：modrinth.index.json 内容不是预期的对象结构，清单可能坏了，"
                    + "重新导入一次整合包");
            return;
        }
        Map<?, ?> deps = root.get("dependencies") instanceof Map<?, ?> d ? d : Map.of();

        // MC 版本不符：只在两边都能读出主次版本、且不同的情况下才报
        String packMc = deps.get("minecraft") instanceof String s ? s : null;
        String packSeries = mcSeries(exactMc(packMc));
        if (packSeries != null && currentSeries != null && !packSeries.equals(currentSeries)) {
            problems.add("整合包：清单声明的 MC 版本是 " + packMc + "，但当前游戏是 MC "
                    + currentMc + "，版本对不上，模组 / 配置可能不兼容；"
                    + "用整合包要求的 MC 版本启动，或重新导入对应版本的整合包");
        }

        // 加载器不符：只在实例加载器明确（非 vanilla / 非 null）且冲突时才报
        String gameFam = ModValidator.detectGameLoader(gameId);
        if (gameFam != null && !"vanilla".equals(gameFam)) {
            String need = packLoaderFamily(deps);
            if (need != null && !need.equals(gameFam)) {
                problems.add("整合包：清单声明的是 " + famLabel(need) + " 加载器，"
                        + "但当前游戏实例用的是 " + famLabel(gameFam) + "，加载器对不上；"
                        + "用对应加载器的游戏版本启动这个整合包");
            }
        }

        // 声明的核心模组缺失（只核对 mods/ 下的 jar，config 等用户常手动调整，跳过以免误报）
        checkDeclaredMods(root, mc, problems);
    }

    /** 清单 dependencies 里的加载器键 → 家族；没有返回 null。 */
    private static String packLoaderFamily(Map<?, ?> deps) {
        for (Object key : deps.keySet()) {
            String k = String.valueOf(key).toLowerCase(Locale.ROOT);
            if (k.contains("fabric") || k.contains("quilt")) {
                return "fabric";
            }
            if (k.contains("neoforge") || k.contains("forge")) {
                return "forge";
            }
        }
        return null;
    }

    private static String famLabel(String fam) {
        return "fabric".equals(fam) ? "Fabric/Quilt" : "Forge/NeoForge";
    }

    private static void checkDeclaredMods(Map<?, ?> root, Path mc, List<String> problems) {
        if (!(root.get("files") instanceof List<?> files)) {
            return;
        }
        Path base = mc.toAbsolutePath().normalize();
        int missing = 0;
        List<String> samples = new ArrayList<>();
        for (Object item : files) {
            if (!(item instanceof Map<?, ?>)) {
                continue;
            }
            Map<?, ?> m = (Map<?, ?>) item;
            if (isOptional(m)) {
                continue;
            }
            Object pathObj = m.get("path");
            if (!(pathObj instanceof String) || ((String) pathObj).isBlank()) {
                continue;
            }
            String rel = (String) pathObj;
            String unified = rel.replace('\\', '/');
            // 只核对整合包的核心模组；挡住绝对路径 / .. 穿越
            if (!unified.startsWith("mods/") || !unified.endsWith(".jar")
                    || unified.contains("..")) {
                continue;
            }
            Path target = base.resolve(unified).normalize();
            if (!target.startsWith(base)) {
                continue;
            }
            if (!Files.exists(target)) {
                missing++;
                if (samples.size() < 3) {
                    samples.add(rel);
                }
            }
        }
        if (missing > 0) {
            problems.add("整合包：清单里声明的 " + missing + " 个核心模组在 mods 里找不到（如 "
                    + String.join("、", samples) + "），整合包可能没装全或被人删过；"
                    + "重新导入一次这个整合包补齐");
        }
    }

    /** mrpack 的 env.client == "optional"，或清单直接写 optional:true。 */
    private static boolean isOptional(Map<?, ?> m) {
        if (m.get("env") instanceof Map<?, ?> e && e.get("client") instanceof String c) {
            return "optional".equalsIgnoreCase(c);
        }
        return Boolean.TRUE.equals(m.get("optional"));
    }
}
