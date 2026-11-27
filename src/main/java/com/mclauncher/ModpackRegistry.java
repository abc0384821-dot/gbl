package com.mclauncher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已安装整合包登记簿（0.8.5）：把每次导入的 Modrinth 整合包记到 {@code ~/.gbl/modpacks/}。
 *
 * <p>「游戏管理 → 管理整合包」板块靠它列出已装的包，并支持卸载。每条记录含包名、MC 版本、
 * 加载器、以及它带来的文件相对路径清单（卸载只删这些，不碰用户别的文件）。
 *
 * <p>读不到（目录没有 / 文件坏了）一律当空列表——登记簿是「锦上添花」的管理清单，
 * 不该因为它让界面打不开。
 */
public final class ModpackRegistry {

    private static final Path DIR = Paths.get(System.getProperty("user.home"), ".gbl", "modpacks");

    /** 一条已安装整合包记录。 */
    public record Pack(String name, String versionId, String mcVersion, String loaderType,
                       String loaderVersion, List<String> files, String installedAt,
                       boolean enabled) {
    }

    private ModpackRegistry() {
        // 静态工具类，别让人 new 出来
    }

    /** 所有已安装整合包（按安装时间倒序靠文件名无法保证，这里按记录顺序返回）。 */
    public static List<Pack> all() {
        if (!Files.isDirectory(DIR)) {
            return List.of();
        }
        List<Pack> out = new ArrayList<>();
        try (var children = Files.list(DIR)) {
            for (Path p : children.filter(f -> f.getFileName().toString().endsWith(".json"))
                    .toList()) {
                try {
                    Object parsed = Json.parse(Files.readString(p, StandardCharsets.UTF_8));
                    if (!(parsed instanceof Map<?, ?> m)) {
                        continue;
                    }
                    out.add(toPack(m));
                } catch (IOException | RuntimeException ex) {
                    System.err.println("[modpack-registry] 读 " + p.getFileName()
                            + " 失败，跳过: " + ex);
                }
            }
        } catch (IOException ex) {
            System.err.println("[modpack-registry] 扫登记簿失败，当空处理: " + ex);
            return List.of();
        }
        return out;
    }

    /** JSON → 记录。抽出来给 all() 与 setEnabled() 共用，保证两处口径一致。 */
    private static Pack toPack(Map<?, ?> m) {
        return new Pack(str(m.get("name")), str(m.get("versionId")),
                str(m.get("mcVersion")), str(m.get("loaderType")),
                str(m.get("loaderVersion")), readFiles(m.get("files")),
                str(m.get("installedAt")), readEnabled(m.get("enabled")));
    }

    /** 1.1.2 之前的旧记录没有 enabled 键，一律当作「启用」，保持向后兼容。 */
    private static boolean readEnabled(Object raw) {
        return !(raw instanceof Boolean b) || b;
    }

    /** 记一条整合包（同名覆盖旧的）。 */
    public static void record(Pack pack) throws IOException {
        Files.createDirectories(DIR);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", pack.name());
        root.put("versionId", pack.versionId());
        root.put("mcVersion", pack.mcVersion());
        root.put("loaderType", pack.loaderType());
        root.put("loaderVersion", pack.loaderVersion());
        root.put("installedAt", pack.installedAt());
        root.put("enabled", pack.enabled());
        List<Object> files = new ArrayList<>();
        if (pack.files() != null) {
            files.addAll(pack.files());
        }
        root.put("files", files);
        Path file = DIR.resolve(safeName(pack.name()) + ".json");
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ex) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException | RuntimeException ignored) {
                // 删不掉就留着，下次写同名覆盖
            }
            throw ex;
        }
    }

    /** 启停结果：改动了多少文件、跳过了多少（跳过 = 文件不在 / 目标已占用 / 路径越界）。 */
    public record EnabledResult(int changed, int skipped) {
    }

    /** 禁用时给文件加的后缀；启用时去掉它。 */
    private static final String DISABLE_SUFFIX = ".gbl-disabled";

    /** 1.1.2：只改启用状态，保留原文件清单（不重新扫磁盘）。 */
    public static void setEnabled(String name, boolean enabled) throws IOException {
        Path file = DIR.resolve(safeName(name) + ".json");
        Object parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> m)) {
            throw new IOException("整合包记录格式不对：" + name);
        }
        Pack old = toPack(m);
        record(new Pack(old.name(), old.versionId(), old.mcVersion(), old.loaderType(),
                old.loaderVersion(), old.files(), old.installedAt(), enabled));
    }

    /**
     * 1.1.2 整合包启停：给 pk.files() 里每个文件加 / 去 {@code .gbl-disabled} 后缀。
     *
     * <p>只做 Files.move，不带 REPLACE_EXISTING，全程零删除、零覆盖；目标名字已被占用就
     * 跳过并记日志，保证任何时刻都能改回来。相对路径解析后必须仍在 root 内（防路径穿越），
     * 目录条目一律跳过，不递归、不删除。
     *
     * @param root 游戏根目录，整合包相对路径的基准
     * @return 改动数与跳过数
     */
    public static EnabledResult applyEnabled(Path root, Pack pk, boolean enabled) {
        int changed = 0;
        int skipped = 0;
        if (root == null || pk == null || pk.files() == null) {
            return new EnabledResult(changed, skipped);
        }
        Path base = root.toAbsolutePath().normalize();
        for (String rel : pk.files()) {
            if (rel == null || rel.isBlank()) {
                continue;
            }
            Path target;
            try {
                target = base.resolve(rel).normalize();
            } catch (RuntimeException ex) {
                System.err.println("[modpack-registry] 非法路径 " + rel + "，跳过: " + ex);
                skipped++;
                continue;
            }
            if (target.equals(base) || !target.startsWith(base)) {
                System.err.println("[modpack-registry] 路径越界 " + rel + "，跳过");
                skipped++;
                continue;
            }
            Path backup = target.resolveSibling(target.getFileName().toString() + DISABLE_SUFFIX);
            Path from = enabled ? backup : target;
            Path to = enabled ? target : backup;
            if (Files.isDirectory(from) || Files.isDirectory(to)) {
                System.err.println("[modpack-registry] 目录条目 " + rel + "，跳过");
                skipped++;
                continue;
            }
            if (!Files.isRegularFile(from)) {
                skipped++;
                continue;
            }
            if (Files.exists(to)) {
                System.err.println("[modpack-registry] 目标已存在 " + to + "，跳过避免覆盖");
                skipped++;
                continue;
            }
            try {
                Files.move(from, to);
                changed++;
            } catch (IOException ex) {
                System.err.println("[modpack-registry] 改名 " + rel + " 失败: " + ex);
                skipped++;
            }
        }
        return new EnabledResult(changed, skipped);
    }

    /**
     * 1.1.3：把旧的 modrinth.index.json 改名留档（零删除，只 move）。
     *
     * <p>删掉最后一个整合包后，旧清单还留在 .minecraft 根，内容检查会照着它核对几十个模组
     * 然后全报缺失。改名成 .gbl-removed-&lt;时间戳&gt; 后检查器直接跳过；文件没删，随时能捡回来。
     * 目标撞名就换 stamp（追加序号）。
     *
     * @return 留档后的路径；本来就没有清单返回 null
     */
    public static Path archiveIndex(Path mcRoot) throws IOException {
        if (mcRoot == null) {
            return null;
        }
        Path index = mcRoot.resolve("modrinth.index.json").normalize();
        if (!Files.isRegularFile(index)) {
            return null;
        }
        String stamp = Long.toString(System.currentTimeMillis());
        Path out = index.resolveSibling("modrinth.index.json.gbl-removed-" + stamp);
        int seq = 1;
        while (Files.exists(out) && seq < 1000) {
            out = index.resolveSibling("modrinth.index.json.gbl-removed-" + stamp + "-" + (seq++));
        }
        if (Files.exists(out)) {
            throw new IOException("旧清单留档文件名撞名过多，清单保留原样");
        }
        Files.move(index, out);
        return out;
    }

    /** 删除一条登记（卸载时调用）。登记簿删不掉不影响文件删除。 */
    public static void remove(String name) throws IOException {
        Files.deleteIfExists(DIR.resolve(safeName(name) + ".json"));
    }

    private static List<String> readFiles(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    private static String str(Object raw) {
        return raw instanceof String text && !text.isBlank() ? text : null;
    }

    /** 整合包名 → 登记文件名：只留安全字符，超长截断（与 ModpackImporter 同款口径）。 */
    public static String safeName(String raw) {
        String cleaned = raw == null ? "" : raw.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5._-]", "_");
        if (cleaned.isBlank()) {
            return "modpack";
        }
        return cleaned.length() > 40 ? cleaned.substring(0, 40) : cleaned;
    }
}
