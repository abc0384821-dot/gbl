package com.mclauncher;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 每个账号的皮肤选择持久化（~/.gbl/skins.json）。
 *
 * <p>一条「当前选择」记录：{@code source}(内置官方 key 如 steve/alex/ari…，或 custom) +
 * {@code file}(custom 时为本地 PNG 绝对路径) + {@code model}(classic|slim)。
 * 另外每个账号还有一份「自定义皮肤库」({@code customs})：导入的 PNG 落盘后列在这里，可反复重选。
 *
 * <p>离线账号与微软账号都共用这一套；区别只在「应用」时的动作：
 * 微软=上传到 Mojang（全局真实生效），离线=本地保存 + 启动游戏时由 LocalSkinServer 喂给游戏。
 *
 * <p>LittleSkin 账号的皮肤由 LittleSkin 服务器管，本启动器不碰，所以这里一律不读写它的皮肤。
 */
public final class SkinStore {

    private static final Path FILE =
            Paths.get(System.getProperty("user.home"), ".gbl", "skins.json");
    private static final Path CUSTOM_DIR =
            Paths.get(System.getProperty("user.home"), ".gbl", "skins");
    /** 可选「官方默认皮肤」扩展目录：用户可把更多官方皮肤 PNG 丢进来，UI 自动列出。 */
    public static final Path GALLERY_DIR =
            Paths.get(System.getProperty("user.home"), ".gbl", "skins", "defaults");

    /** 内置官方皮肤（打进 jar 的 /skins/ 下）：key → 英文名。顺序即展示顺序。 */
    private static final Map<String, String> BUILTIN = new LinkedHashMap<>();
    static {
        BUILTIN.put("steve", "Steve");
        BUILTIN.put("alex", "Alex");
    }

    /** 0.9.4 指纹：官方 Alex 纹理与 slim 元数据。构建门禁扫 class 常量池，勿改这串字。 */
    private static final String FP_094_OFFICIAL_SLIM = "0.9.4 官方 Alex 纹理与 slim 元数据";
    static { System.err.println("[skin] 0.9.4: " + FP_094_OFFICIAL_SLIM); }

    /** 0.9.9 r6 指纹：离线皮肤随包与失败可见。构建门禁扫 class 常量池，勿改这串字。 */
    public static final String FP_099_R6_SKIN = "0.9.9 r6 离线皮肤随包与失败可见";
    static { System.err.println("[skin] r6: " + FP_099_R6_SKIN); }

    /** 当前选择：accId → Entry。 */
    private static final Map<String, Entry> CACHE = new LinkedHashMap<>();
    /** 自定义皮肤库：accId → 该账号导入过的皮肤列表。 */
    private static final Map<String, List<Custom>> CUSTOMS = new LinkedHashMap<>();

    private SkinStore() {
    }

    /** 一条皮肤记录（当前选择）。 */
    public static final class Entry {
        public final String source; // 内置官方 key，或 "custom"
        public final String file;    // custom 时的本地 PNG 路径；其余为 null
        public final String model;   // classic | slim

        public Entry(String source, String file, String model) {
            this.source = source;
            this.file = file;
            this.model = model;
        }
    }

    /** 自定义皮肤库里的一条（导入保存的）。 */
    public static final class Custom {
        public final String name;  // 展示名（导入时的文件名，去扩展名）
        public final String file;  // 本地 PNG 绝对路径
        // 1.2.8：臂宽可由用户手动改（仅自导入皮肤），由 setCustomModel 唯一入口写回。
        public String model; // classic | slim

        public Custom(String name, String file, String model) {
            this.name = name;
            this.file = file;
            this.model = model;
        }
    }

    private static String str(Object o, String d) {
        return o == null ? d : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static void load() {
        CACHE.clear();
        CUSTOMS.clear();
        try {
            if (!Files.exists(FILE)) {
                return;
            }
            Object root = Json.parse(new String(Files.readAllBytes(FILE), StandardCharsets.UTF_8));
            Map<String, Object> obj = (root instanceof Map) ? (Map<String, Object>) root : null;
            Map<String, Object> skins = obj;
            Map<String, Object> customs = null;
            if (obj != null) {
                // 新格式：{skins:{...}, customs:{...}}；老格式直接是 skins 平铺
                if (obj.containsKey("skins")) {
                    skins = (Map<String, Object>) obj.get("skins");
                }
                if (obj.containsKey("customs")) {
                    customs = (Map<String, Object>) obj.get("customs");
                }
            }
            if (skins != null) {
                for (Map.Entry<String, Object> e : skins.entrySet()) {
                    if (e.getValue() instanceof Map) {
                        Map<?, ?> vm = (Map<?, ?>) e.getValue();
                        String src = str(vm.get("source"), "steve");
                        CACHE.put(e.getKey(), new Entry(src, str(vm.get("file"), null),
                                str(vm.get("model"), modelOf(src))));
                    }
                }
            }
            if (customs != null) {
                for (Map.Entry<String, Object> e : customs.entrySet()) {
                    if (e.getValue() instanceof List) {
                        List<Custom> list = new ArrayList<>();
                        for (Object o : (List<?>) e.getValue()) {
                            if (o instanceof Map) {
                                Map<?, ?> vm = (Map<?, ?>) o;
                                list.add(new Custom(str(vm.get("name"), "custom"),
                                        str(vm.get("file"), null),
                                        str(vm.get("model"), "classic")));
                            }
                        }
                        CUSTOMS.put(e.getKey(), list);
                    }
                }
            }
        } catch (Exception ignore) {
            // 读坏不致命：当没记录，全部回退默认
        }
    }

    static {
        load();
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> skins = new LinkedHashMap<>();
            for (Map.Entry<String, Entry> e : CACHE.entrySet()) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("source", e.getValue().source);
                v.put("file", e.getValue().file);
                v.put("model", e.getValue().model);
                skins.put(e.getKey(), v);
            }
            Map<String, Object> customs = new LinkedHashMap<>();
            for (Map.Entry<String, List<Custom>> e : CUSTOMS.entrySet()) {
                List<Object> list = new ArrayList<>();
                for (Custom c : e.getValue()) {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("name", c.name);
                    v.put("file", c.file);
                    v.put("model", c.model);
                    list.add(v);
                }
                customs.put(e.getKey(), list);
            }
            out.put("skins", skins);
            out.put("customs", customs);
            Files.write(FILE, Json.write(out).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignore) {
        }
    }

    private static String modelOf(String source) {
        return "alex".equals(source) ? "slim" : "classic";
    }

    /** 取账号当前皮肤记录；没有就回退「史蒂夫 / classic」。 */
    public static Entry get(AccountStore.Account acc) {
        Entry e = CACHE.get(acc.id);
        return e == null ? new Entry("steve", null, "classic") : e;
    }

    /** 是否需要在启动游戏时注入本地皮肤服务（即当前选的不是游戏默认史蒂夫）。 */
    public static boolean needsLocalSkin(AccountStore.Account acc) {
        if (acc == null || acc.type != AccountStore.Type.OFFLINE) {
            return false; // 微软走 Mojang 上传；LittleSkin 由服务器管
        }
        return !"steve".equals(get(acc).source);
    }

    /** 当前皮肤的模型（classic/slim）；内置官方按 key 判定，custom 用记录的 model。 */
    public static String model(AccountStore.Account acc) {
        Entry e = get(acc);
        if ("custom".equals(e.source) && e.file != null) {
            return e.model;
        }
        if (BUILTIN.containsKey(e.source)) {
            return officialModel(e.source);   // 0.9.4：官方 Alex 纹理不靠透明列判细臂
        }
        try {
            return SkinRender.isSlim(skinBytes(acc)) ? "slim" : "classic";
        } catch (IOException ex) {
            return e.model;
        }
    }

    // ---------------------------------------------------------------- 内置官方

    /** 列出内置官方皮肤（key → 英文名）。 */
    public static Map<String, String> builtinDefaults() {
        return new LinkedHashMap<>(BUILTIN);
    }

    /** 取某个内置官方皮肤的字节（从打进 jar 的资源 /skins/<key>.png 读）。 */
    public static byte[] officialBytes(String key) throws IOException {
        if (!BUILTIN.containsKey(key)) {
            return defaultBytes("steve");
        }
        try (InputStream in = SkinStore.class.getResourceAsStream("/skins/" + key + ".png")) {
            if (in == null) {
                throw new IOException("缺少内置皮肤资源 /skins/" + key + ".png");
            }
            return in.readAllBytes();
        }
    }

    /** 内置官方皮肤的默认模型（steve=classic, alex=slim，其余按纹理判定）。 */
    public static String officialModel(String key) {
        if ("alex".equals(key)) {
            return "slim";
        }
        try {
            return SkinRender.isSlim(officialBytes(key)) ? "slim" : "classic";
        } catch (IOException ex) {
            return "classic";
        }
    }

    // ---------------------------------------------------------------- 用户扩展目录

    /** 扩展目录里用户自己放的 PNG（key=gallery:<文件名> → 文件名去扩展名）。 */
    public static Map<String, String> galleryDefaults() {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            Files.createDirectories(GALLERY_DIR);
            if (Files.isDirectory(GALLERY_DIR)) {
                try (var stream = Files.list(GALLERY_DIR)) {
                    stream.filter(p -> p.toString().toLowerCase().endsWith(".png"))
                            .sorted().forEach(p -> {
                                String fn = p.getFileName().toString();
                                m.put("gallery:" + fn, fn.replaceFirst("(?i)\\.png$", ""));
                            });
                }
            }
        } catch (IOException ignore) {
        }
        return m;
    }

    public static byte[] galleryBytes(String key) throws IOException {
        if (key.startsWith("gallery:")) {
            return Files.readAllBytes(GALLERY_DIR.resolve(key.substring("gallery:".length())));
        }
        return defaultBytes("steve");
    }

    // ---------------------------------------------------------------- 自定义皮肤库

    /** 该账号导入并保存过的自定义皮肤列表。 */
    public static List<Custom> listCustoms(AccountStore.Account acc) {
        List<Custom> list = CUSTOMS.get(acc.id);
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    /** 导入并保存一张自定义皮肤：落盘到 ~/.gbl/skins/custom/<账号>/<名>.png，
     *  加入该账号的库，并设为当前选择。返回新建的条目。 */
    public static Custom addCustom(AccountStore.Account acc, byte[] png, String name, String model)
            throws IOException {
        Path dir = CUSTOM_DIR.resolve("custom").resolve(sanitize(acc.id));
        Files.createDirectories(dir);
        String base = sanitize(name.isEmpty() ? "skin" : name);
        String fileName = base + ".png";
        Path p = dir.resolve(fileName);
        int i = 1;
        while (Files.exists(p)) {
            p = dir.resolve(base + "_" + i + ".png");
            i++;
        }
        Files.write(p, png);
        Custom c = new Custom(name.isEmpty() ? base : name, p.toString(), model);
        CUSTOMS.computeIfAbsent(acc.id, k -> new ArrayList<>()).add(c);
        CACHE.put(acc.id, new Entry("custom", p.toString(), model));
        save();
        return c;
    }

    /** 选用库里已有的自定义皮肤。 */
    public static void selectCustom(AccountStore.Account acc, Custom c) {
        CACHE.put(acc.id, new Entry("custom", c.file, c.model));
        save();
    }

    /**
     * 1.2.8：修改「我的皮肤」中某条自导入皮肤的臂宽模型。
     * 同步更新 CUSTOMS 里那条、调用方持有的 Custom 对象，以及它作为当前选择时的 CACHE Entry。
     * 官方 / gallery 皮肤不进自定义库，天然无法调用本方法，仍由自动判定决定模型。
     */
    public static synchronized void setCustomModel(AccountStore.Account acc, Custom c, String model) {
        if (acc == null || c == null) {
            return;
        }
        String m = "slim".equals(model) ? "slim" : "classic";
        List<Custom> list = CUSTOMS.get(acc.id);
        if (list != null) {
            for (Custom item : list) {
                if (item == c || (item.file != null && item.file.equals(c.file))) {
                    item.model = m;
                }
            }
        }
        c.model = m;   // UI 持有同一个对象，切完立即读到新值
        Entry cur = CACHE.get(acc.id);
        if (cur != null && "custom".equals(cur.source)
                && c.file != null && c.file.equals(cur.file)) {
            CACHE.put(acc.id, new Entry("custom", cur.file, m));
        }
        save();
    }

    /** 删除库里某条自定义皮肤（不影响当前选择，除非当前正选它）。 */
    public static void removeCustom(AccountStore.Account acc, Custom c) {
        List<Custom> list = CUSTOMS.get(acc.id);
        if (list != null) {
            list.removeIf(x -> x.file.equals(c.file));
            if (list.isEmpty()) {
                CUSTOMS.remove(acc.id);
            }
        }
        Entry cur = CACHE.get(acc.id);
        if (cur != null && "custom".equals(cur.source) && c.file.equals(cur.file)) {
            CACHE.remove(acc.id); // 删的是当前选中 → 回退默认
        }
        save();
    }

    // ---------------------------------------------------------------- 当前选择写入

    /** 选用内置默认皮肤（steve/alex/ari…）。 */
    public static void setDefault(AccountStore.Account acc, String key) {
        CACHE.put(acc.id, new Entry(key, null, modelOf(key)));
        save();
    }

    /** 写一条自定义皮肤（custom）：把 PNG 字节存到 ~/.gbl/skins/<id>.png 并设为当前选择（不进自定义库）。
     *  微软账号上传成功后也用这个在本地留一份，让启动器头像跟着显示。 */
    public static void setCustom(AccountStore.Account acc, byte[] png, String model) throws IOException {
        Files.createDirectories(CUSTOM_DIR);
        Path p = CUSTOM_DIR.resolve(sanitize(acc.id) + ".png");
        Files.write(p, png);
        CACHE.put(acc.id, new Entry("custom", p.toString(), model));
        save();
    }

    /** 恢复成游戏默认史蒂夫（等效清掉当前选择；自定义库保留）。 */
    public static void reset(AccountStore.Account acc) {
        CACHE.remove(acc.id);
        save();
    }

    // ---------------------------------------------------------------- 取字节

    /** 取出该账号当前应使用的皮肤 PNG 字节（内置默认/自定义统一成字节流）。 */
    public static byte[] skinBytes(AccountStore.Account acc) throws IOException {
        Entry e = get(acc);
        if ("custom".equals(e.source) && e.file != null) {
            return Files.readAllBytes(Path.of(e.file));
        }
        if (BUILTIN.containsKey(e.source)) {
            return officialBytes(e.source);
        }
        if (e.source != null && e.source.startsWith("gallery:")) {
            return galleryBytes(e.source);
        }
        return defaultBytes("steve");
    }

    /** 内置默认皮肤（史蒂夫 / 亚历克斯 / 各经典角色）的字节；从打进 jar 的资源读。 */
    public static byte[] defaultBytes(String source) throws IOException {
        String key = BUILTIN.containsKey(source) ? source : "steve";
        try (InputStream in = SkinStore.class.getResourceAsStream("/skins/" + key + ".png")) {
            if (in == null) {
                throw new IOException("缺少内置皮肤资源 /skins/" + key + ".png");
            }
            return in.readAllBytes();
        }
    }

    private static String sanitize(String id) {
        StringBuilder sb = new StringBuilder();
        for (char c : id.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 皮肤包导出 / 导入（0.9.9 r6）
    // 离线账号皮肤是纯本机私有数据（skins.json + skins/），不进安装包、不上传服务器。
    // 换机器要靠「导出皮肤包 → 拷过去 → 导入」把这份私有数据带过去。
    // 要害：皮肤记录以 acc.id（随机 UUID，非玩家名、非 MC 的 MD5 uuid）为 key。
    // 导出/导入必须原样保留这个 key，绝不可重新生成，否则「写 A 读 B」key 不一致，
    // 朋友机器上 get(acc) 读不到、needsLocalSkin() 一直是 false，还是显示 Steve。

    private static String stamp() {
        return System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** zip 条目统一用正斜杠；顺手把 Windows 反斜杠归一化。 */
    private static String zipName(String s) {
        return s.replace('\\', '/');
    }

    /**
     * 把某账号的「当前皮肤 + 自定义皮肤库 + 对应 PNG」打包成 zip，供拷到朋友机器导入。
     *
     * <p>包内条目（均为 zip 内相对路径、正斜杠）：
     * <ul>
     *   <li>{@code manifest.json}：账号 id / name / type 小清单（用项目 {@link Json}）。</li>
     *   <li>{@code skin.json}：该账号在 skins.json 里的条目，key 原样是 {@code acc.id}。</li>
     *   <li>{@code customs/<sanitized-id>/}.png、{@code <sanitized-id>.png}：该账号的 PNG。</li>
     * </ul>
     *
     * @param acc      要导出的账号（皮肤 key = acc.id）
     * @param targetZip 目标 zip 文件路径；不覆盖已存在文件
     * @throws IOException 失败时给「什么失败 + 为什么 + 下一步」的中文信息，不留半成品
     */
    public static void exportSkinBundle(AccountStore.Account acc, Path targetZip) throws IOException {
        if (acc == null || acc.id == null || acc.id.isBlank()) {
            throw new IOException("导出皮肤失败：没有有效账号。请先在启动器里选一个账号，再导出皮肤包。");
        }
        if (targetZip == null) {
            throw new IOException("导出皮肤失败：没有指定保存路径。请选择要保存到的 .zip 文件位置。");
        }
        String id = acc.id;
        String san = sanitize(id);
        Entry entry = CACHE.get(id);
        List<Custom> customs = CUSTOMS.get(id);
        if (entry == null && (customs == null || customs.isEmpty())) {
            throw new IOException("导出皮肤失败：账号「" + acc.name + "」当前没有皮肤记录，也没有自定义皮肤库，"
                    + "没什么可导出。请先给这个账号设置一张自定义皮肤再导出。");
        }

        // 先在内存里把 manifest / 皮肤条目 / PNG 字节全部凑齐——凑不齐就整体失败，绝不写半个包。
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("id", id);
        manifest.put("name", acc.name == null ? "" : acc.name);
        manifest.put("type", acc.type == null ? "" : acc.type.name());
        manifest.put("model", entry == null ? "classic" : entry.model);

        Map<String, Object> skinJson = new LinkedHashMap<>();
        if (entry != null) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("source", entry.source);
            v.put("file", entry.file);   // 原样保留 skins.json 里的条目
            v.put("model", entry.model);
            if ("custom".equals(entry.source) && entry.file != null) {
                String fn = zipName(entry.file);
                fn = fn.substring(fn.lastIndexOf('/') + 1);
                v.put("customName", stripPng(fn)); // 辅助导入时精准选中这张
            }
            skinJson.put(id, v); // key 原样 = acc.id
        } else if (!customs.isEmpty()) {
            // 没有「当前选择」但有库：把库里第一张作为导出后的当前选择。
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("source", "custom");
            v.put("file", customs.get(0).file);   // 原样保留
            v.put("model", customs.get(0).model);
            v.put("customName", customs.get(0).name);
            skinJson.put(id, v);
        }
        if (skinJson.isEmpty()) {
            throw new IOException("导出皮肤失败：账号「" + acc.name + "」的皮肤记录为空，无法生成皮肤包。"
                    + "请重新设置皮肤后再导出。");
        }

        // zip 内相对路径 → PNG 字节。custom/<san>/<名>.png 与 <san>.png 两类都收。
        Map<String, byte[]> pngs = new LinkedHashMap<>();
        // (1) 当前选择指向的 PNG（setCustom 落的是 <san>.png；addCustom 落的是 custom/<san>/<名>.png）
        String currentName = null;
        if (entry != null && "custom".equals(entry.source) && entry.file != null) {
            currentName = collectPng(entry.file, san, pngs, "当前选择的皮肤");
        } else {
            Object cn = skinJson.get(id) instanceof Map ? ((Map<?, ?>) skinJson.get(id)).get("customName") : null;
            if (cn instanceof String s && !s.isBlank()) {
                currentName = sanitize(s) + ".png";
            }
        }
        // (2) 自定义库里每一张
        if (customs != null) {
            for (Custom c : customs) {
                if (c.file != null) {
                    collectPng(c.file, san, pngs, "自定义皮肤「" + c.name + "」");
                }
            }
        }
        // (3) 兜底：two known on-disk locations
        Path single = CUSTOM_DIR.resolve(san + ".png");
        if (Files.isRegularFile(single)) {
            pngs.putIfAbsent(san + ".png", readPngOrThrow(single, "账号皮肤 PNG"));
        }
        Path customDir = CUSTOM_DIR.resolve("custom").resolve(san);
        if (Files.isDirectory(customDir)) {
            try (var stream = Files.list(customDir)) {
                stream.filter(p -> p.toString().toLowerCase().endsWith(".png"))
                        .forEach(p -> pngs.putIfAbsent(zipName("custom/" + san + "/" + p.getFileName()),
                                readPngUnchecked(p)));
            } catch (IOException ex) {
                throw new IOException("导出皮肤失败：读取账号的自定义皮肤目录「" + customDir + "」失败（"
                        + ex.getMessage() + "）。请检查该目录的读取权限。", ex);
            }
        }
        if (pngs.isEmpty()) {
            throw new IOException("导出皮肤失败：账号「" + acc.name + "」有皮肤记录，但皮肤记录指向的 PNG 文件"
                    + "都找不到（可能被手动移走或删掉了）。请重新给这个账号设置一次皮肤再导出。");
        }

        // 全部内容就绪，写唯一时间戳临时文件，成功后原子移动成目标；失败只清自己刚建的临时文件。
        Path target = targetZip.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("导出皮肤失败：保存路径「" + targetZip + "」没有父目录。请换一个完整路径。");
        }
        Files.createDirectories(parent); // 只建，不清空、不复用旧目录
        if (Files.exists(target)) {
            throw new IOException("导出皮肤失败：目标文件「" + target + "」已存在。为避免覆盖你的文件，"
                    + "请换一个新文件名，或先手动处理这个已有文件（本启动器不会替你删除它）。");
        }
        Path tmp = parent.resolve(target.getFileName() + ".export-" + stamp() + ".tmp");
        boolean moved = false;
        try {
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
                writeZipEntry(zos, "manifest.json", Json.write(manifest).getBytes(StandardCharsets.UTF_8));
                writeZipEntry(zos, "skin.json", Json.write(skinJson).getBytes(StandardCharsets.UTF_8));
                for (Map.Entry<String, byte[]> e : pngs.entrySet()) {
                    writeZipEntry(zos, e.getKey(), e.getValue());
                }
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException fallback) {
                Files.move(tmp, target);
            }
            moved = true;
        } catch (IOException | RuntimeException ex) {
            throw new IOException("导出皮肤失败：写 zip 文件时出错（" + ex.getMessage()
                    + "）。请检查磁盘空间与目标目录的写入权限，然后重试。", ex);
        } finally {
            if (!moved) {
                deleteOurTemp(tmp); // 零删除铁律：只清自己刚建的临时文件
            }
        }
    }

    /**
     * 从一个皮肤包 zip 导入到指定账号，覆盖其「当前皮肤 + 自定义皮肤库」。
     *
     * <p><b>要害：</b>无论包里 manifest/skin.json 记的是哪个 id，写回一律用调用方传入的
     * {@code acc.id} 作为 key（{@code CACHE.put(acc.id, entry)} + {@code save()}），
     * 绝不重新生成 UUID。这样导入后 {@code get(acc)} 必返回 custom，
     * {@code needsLocalSkin()} 为 true，离线皮肤才能喂给游戏显示出来。
     *
     * @param acc        要导入到的账号
     * @param sourceZip  皮肤包 zip 路径
     * @throws IOException 失败时给「什么失败 + 为什么 + 下一步」的中文信息，不留半成品
     */
    @SuppressWarnings("unchecked")
    public static void importSkinBundle(AccountStore.Account acc, Path sourceZip) throws IOException {
        if (acc == null || acc.id == null || acc.id.isBlank()) {
            throw new IOException("导入皮肤失败：没有有效账号。请先选一个账号，再导入皮肤包。");
        }
        if (sourceZip == null || !Files.isRegularFile(sourceZip)) {
            throw new IOException("导入皮肤失败：找不到皮肤包文件「" + sourceZip
                    + "」。请确认路径正确、文件是导出得到的 .zip 包。");
        }

        // 1) 先把整个 zip 读进内存；任何一项缺失/损坏都直接失败，绝不动现有皮肤配置。
        byte[] manifestBytes = null;
        byte[] skinBytes = null;
        Map<String, byte[]> pngs = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(sourceZip.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (ze.isDirectory()) {
                    continue;
                }
                String name = zipName(ze.getName());
                try (InputStream in = zf.getInputStream(ze)) {
                    byte[] data = in.readAllBytes();
                    if ("manifest.json".equals(name)) {
                        manifestBytes = data;
                    } else if ("skin.json".equals(name)) {
                        skinBytes = data;
                    } else if (name.toLowerCase().endsWith(".png")) {
                        pngs.put(name, data);
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            throw new IOException("导入皮肤失败：打不开或读坏皮肤包「" + sourceZip + "」（"
                    + ex.getMessage() + "）。请确认这是本启动器导出的 .zip 包，且文件未损坏。", ex);
        }
        if (manifestBytes == null || skinBytes == null) {
            throw new IOException("导入皮肤失败：皮肤包里缺少 manifest.json 或 skin.json，不是本启动器导出的皮肤包。"
                    + "请重新导出一份完整的皮肤包再导入。");
        }

        // manifest 仅用于展示/校验来源 id，不参与写回 key。
        Map<String, Object> manifest;
        try {
            Object o = Json.parse(new String(manifestBytes, StandardCharsets.UTF_8));
            manifest = (o instanceof Map) ? (Map<String, Object>) o : null;
        } catch (RuntimeException ex) {
            throw new IOException("导入皮肤失败：皮肤包的 manifest.json 不是合法 JSON（"
                    + ex.getMessage() + "）。请重新导出一份皮肤包。", ex);
        }
        if (manifest == null || manifest.get("id") == null) {
            throw new IOException("导入皮肤失败：皮肤包的 manifest.json 缺少账号 id，无法确认来源。"
                    + "请重新导出一份皮肤包。");
        }

        // skin.json：key 是包里记的 acc.id（仅作来源参考），条目的 source/model 才是要恢复的内容。
        Map<String, Object> skinRoot;
        Map<String, Object> skinEntry = null;
        try {
            Object o = Json.parse(new String(skinBytes, StandardCharsets.UTF_8));
            skinRoot = (o instanceof Map) ? (Map<String, Object>) o : null;
            if (skinRoot != null) {
                for (Object v : skinRoot.values()) {
                    if (v instanceof Map) {
                        skinEntry = (Map<String, Object>) v;
                        break;
                    }
                }
            }
        } catch (RuntimeException ex) {
            throw new IOException("导入皮肤失败：皮肤包的 skin.json 不是合法 JSON（"
                    + ex.getMessage() + "）。请重新导出一份皮肤包。", ex);
        }
        if (skinEntry == null) {
            throw new IOException("导入皮肤失败：皮肤包的 skin.json 里没有账号皮肤条目，无法恢复皮肤。"
                    + "请确认导出时这个账号确实设置了皮肤。");
        }
        String source = str(skinEntry.get("source"), "custom");
        String model = str(skinEntry.get("model"), "classic");
        String customName = str(skinEntry.get("customName"), null);

        // 目标账号的 sanitized id（导入后 PNG 一律落到这个账号名下）。
        String san = sanitize(acc.id);
        String sourceSan = sanitize(str(manifest.get("id"), ""));

        // 2) 把包里的 PNG 解到「唯一时间戳临时 staging 目录」，全部就位后再整体落地。
        Path staging = CUSTOM_DIR.resolve(".import-staging-" + stamp());
        try {
            Files.createDirectories(staging);
            // 包内路径可能带来源账号的 sanitized id；换成目标账号的 san，避免落到别人名下。
            for (Map.Entry<String, byte[]> e : pngs.entrySet()) {
                String mapped = e.getKey();
                if (!sourceSan.isEmpty() && !sourceSan.equals(san)) {
                    mapped = mapped.replace(sourceSan, san);
                }
                Path dest = staging.resolve(mapped.replace('/', java.io.File.separatorChar));
                Path dp = dest.getParent();
                if (dp != null) {
                    Files.createDirectories(dp);
                }
                Files.write(dest, e.getValue());
            }

            // 3) 全部 PNG 就位后，整体落地到目标账号名下。
            // 先收集成 List 再移动，避免边遍历惰性流边移动文件。
            List<Path> stagedFiles;
            try (var stream = Files.walk(staging)) {
                stagedFiles = stream.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toList());
            }
            Files.createDirectories(CUSTOM_DIR);
            String finalModel = model;
            List<Custom> imported = new ArrayList<>();
            for (Path p : stagedFiles) {
                String rel = staging.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                Path dest = CUSTOM_DIR.resolve(rel.replace('/', java.io.File.separatorChar));
                Path dp = dest.getParent();
                if (dp != null) {
                    Files.createDirectories(dp);
                }
                if (Files.exists(dest)) {
                    // 不覆盖用户已有同名文件：另存为 <名>.import-<时间戳>.png，并把引用指向新文件。
                    String fn = dest.getFileName().toString();
                    String base = fn.replaceFirst("(?i)\\.png$", "");
                    dest = dest.resolveSibling(base + ".import-" + stamp() + ".png");
                }
                Files.move(p, dest, StandardCopyOption.ATOMIC_MOVE);
                Custom c = new Custom(stripPng(dest.getFileName().toString()), dest.toString(), finalModel);
                imported.add(c);
            }

            // 当前选择：custom 时必须有 PNG 落地。
            // 优先级：skin.json 声明的 customName 对应文件 > <san>.png（单文件形态）> 第一张。
            if ("custom".equals(source)) {
                Path chosen = null;
                if (customName != null && !customName.isBlank()) {
                    String want = sanitize(customName) + ".png";
                    for (Custom c : imported) {
                        if (Path.of(c.file).getFileName().toString().equals(want)) {
                            chosen = Path.of(c.file);
                            break;
                        }
                    }
                }
                if (chosen == null) {
                    for (Custom c : imported) {
                        if (Path.of(c.file).getFileName().toString().equals(san + ".png")) {
                            chosen = Path.of(c.file);
                            break;
                        }
                    }
                }
                if (chosen == null && !imported.isEmpty()) {
                    chosen = Path.of(imported.get(0).file);
                }
                if (chosen == null) {
                    throw new IOException("导入皮肤失败：皮肤包里没有可落地的 PNG，无法恢复自定义皮肤。"
                            + "请确认导出时该账号确实选了自定义皮肤，并重导一次。");
                }
                CACHE.put(acc.id, new Entry("custom", chosen.toString(), model));
            } else {
                CACHE.put(acc.id, new Entry(source, null, model));
            }
            if (!imported.isEmpty()) {
                List<Custom> merged = new ArrayList<>(CUSTOMS.getOrDefault(acc.id, new ArrayList<>()));
                merged.addAll(imported);
                CUSTOMS.put(acc.id, merged);
            }
            save(); // 内部同样以 acc.id 为 key 写回 skins.json
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof IOException && ex.getMessage() != null && ex.getMessage().startsWith("导入皮肤失败")) {
                throw (IOException) ex;
            }
            throw new IOException("导入皮肤失败：写皮肤文件到「" + CUSTOM_DIR + "」时出错（"
                    + ex.getMessage() + "）。请检查该目录的写入权限与磁盘空间，然后重试。", ex);
        } finally {
            // 零删除铁律：只清自己建的 staging 临时目录，绝不碰用户原有文件。
            deleteOurTempDir(staging);
        }
    }

    /** 把 PNG 收进 pngs；返回 zip 内相对路径。缺文件时给清晰中文异常。 */
    private static String collectPng(String file, String san, Map<String, byte[]> pngs, String what)
            throws IOException {
        Path p;
        try {
            p = Path.of(file);
        } catch (RuntimeException ex) {
            throw new IOException("导出皮肤失败：" + what + "的路径「" + file + "」无法识别。请重新设置皮肤。", ex);
        }
        if (!Files.isRegularFile(p)) {
            throw new IOException("导出皮肤失败：" + what + "的 PNG 文件「" + file + "」找不到。"
                    + "可能已被手动移走，请重新给账号设置一次皮肤再导出。");
        }
        String name = p.getFileName().toString();
        String rel = name.equals(san + ".png") ? name : "custom/" + san + "/" + name;
        pngs.putIfAbsent(zipName(rel), readPngOrThrow(p, what));
        return rel;
    }

    private static byte[] readPngOrThrow(Path p, String what) throws IOException {
        try {
            return Files.readAllBytes(p);
        } catch (IOException ex) {
            throw new IOException("导出皮肤失败：读取" + what + "的 PNG「" + p + "」失败（"
                    + ex.getMessage() + "）。请检查该文件是否正被占用，稍后重试。", ex);
        }
    }

    private static byte[] readPngUnchecked(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private static void writeZipEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry ze = new ZipEntry(name); // 名字用正斜杠的相对路径
        zos.putNextEntry(ze);
        zos.write(data);
        zos.closeEntry();
    }

    private static String stripPng(String fn) {
        return fn.replaceFirst("(?i)\\.png$", "");
    }

    /** 只删自己刚建的临时文件；删不掉就算了，绝不扩大删除范围。 */
    private static void deleteOurTemp(Path p) {
        try {
            if (p != null) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | RuntimeException ignore) {
            // 临时文件留着不致命，下次导出另起新名字，不会误伤用户文件
        }
    }

    /** 只清自己刚建的 staging 目录（里面全是我这次解压出来的文件）。 */
    private static void deleteOurTempDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException | RuntimeException ignore) {
                            // 同上：清不掉也不管
                        }
                    });
        } catch (IOException | RuntimeException ignore) {
            // 同上
        }
    }

    /** 把字节流交给 SkinRender 用（避免重复 import）。 */
    public static InputStream skinStream(AccountStore.Account acc) throws IOException {
        return new ByteArrayInputStream(skinBytes(acc));
    }
}
