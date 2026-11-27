package com.mclauncher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 加载器安装 / 卸载 / 状态（v0.7.0 P0-2 元数据型 + v0.8 安装器型）。
 *
 * <p><b>两类加载器，两套集成点</b>：
 * <ul>
 *   <li><b>元数据型</b>（Fabric / Quilt / LegacyFabric，{@link LoaderType#isMeta}）：
 *       不新建目录，直接把加载器的 libraries / mainClass / 游戏参数<b>合并写回</b>目标版本的
 *       {@code version.json} 与 {@code <id>.json}（见 {@link #installMeta}）。</li>
 *   <li><b>安装器型</b>（Forge / NeoForge，{@link LoaderType#isInstaller}）：
 *       installer 跑完<b>自己生成一个独立的新 profile</b> {@code <mc>-forge-<v>} /
 *       {@code <mc>-neoforge-<v>}，原版目录保持原样。GBL 只负责下载 installer jar、
 *       无头运行、给生成的 profile 写 {@code gblLoader} 标记（见 {@link #installInstaller}）。</li>
 * </ul>
 *
 * <p>纯服务层、不碰 UI：失败一律抛 {@link IOException}，message 就是能直接显示的那句话。
 */
public final class LoaderInstaller {

    /** meta API 连接 / 读取超时：版本列表是个小 JSON，20 秒够；连不上就该让用户去查网络。
     *  单版本 launcherMeta（安装期）与安装器型列表继续用它；元数据型「版本列表」另见下方专用超时。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** 元数据型「版本列表」专用超时（0.8.8 Q4 S6）：镜像优先、失败才回落官方，各自留出余量。
     *  实测镜像 0.34~0.58s → 取 3s；官方 2.94~4.23s（LegacyFabric 可能更慢）→ 取 6s。
     *  N1「官方源首次无缓存 ≤3s / 镜像源 ≤2s」由主源（镜像）满足，官方仅作回落、不进冷启动首屏。 */
    private static final Duration META_LIST_MIRROR_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration META_LIST_OFFICIAL_TIMEOUT = Duration.ofSeconds(6);

    private static final String VANILLA_MAIN = "net.minecraft.client.main.Main";

    /**
     * 1.2.5 F：版本列表 GET 客户端。补齐 {@code ProxyRoute.selector()}（原先是全局唯一
     * 没挂选择器的 HttpClient）：别的电脑靠 gbl_proxy / https_proxy 环境变量上网时，
     * Forge/NeoForge 版本列表此前恒直连失败，表现为「本机能用、别的电脑下载不了」。
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .proxy(ProxyRoute.selector())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private LoaderInstaller() {
        // 静态工具类，别让人 new 出来
    }

    /** 一个可装的加载器版本。{@code stable} 缺省 true（Quilt BMCLAPI 扁平列表无此字段，
     *  按稳定版算，避免 UI 永远选 index 0；见 0.8.8 Q4 versionsMeta）。 */
    public record LoaderVersion(String version, boolean stable) {
    }

    /** 某版本现在装着的加载器；没装返回 null（由 {@link #status} 判）。 */
    public record Status(LoaderType type, String loaderVersion) {
    }

    /** 安装 / 卸载过程的进度出口。回调发生在调用线程上——界面要自己往 FX 线程送。 */
    public interface Listener {
        /** 「正在拉 Fabric 版本信息…」「正在下载依赖库…」这类阶段文字。 */
        default void onStage(String text) {
        }

        /** 补下载加载器库的进度（第几个 / 共几个）。 */
        default void onLibProgress(int done, int total) {
        }
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 该 MC 版本可用的加载器版本，**最新在前**（stable 优先与否由 UI 决定默认选中）。
     *
     * <p>按类型分流：元数据型走 {@link #versionsMeta}（原 Fabric/Quilt/LegacyFabric 逻辑，
     * 零改动）；安装器型走 {@link #versionsForge} / {@link #versionsNeoForge} /
     * {@link #versionsOptiFine}（BMCLAPI 列表，原版没干净 JSON 可用）。
     *
     * <p>OptiFine 分支：版本列表走 BMCLAPI {@code /optifine/<mc>}，按 JSON 数组解析；
     * 连不上 / 认不出来 / 不是数组 / 空列表一律抛明确 {@link IOException}，
     * <b>不静默返回空列表</b>（主理人拍板口径）。
     *
     * @throws IOException 接口连不上 / 返回认不出来（message 说人话带下一步动作）
     */
    public static List<LoaderVersion> versions(LoaderType type, String mcVersion)
            throws IOException {
        if (type.isMeta()) {
            return versionsMeta(type, mcVersion);
        }
        if (type == LoaderType.FORGE) {
            return versionsForge(mcVersion);
        }
        if (type == LoaderType.NEOFORGE) {
            return versionsNeoForge(mcVersion);
        }
        if (type == LoaderType.OPTIFINE) {
            return versionsOptiFine(mcVersion);
        }
        return List.of();
    }

    /**
     * 读本地 version.json 的 {@code gblLoader} 标记；没有（或认不出来）再回落到
     * 「扫描派生 profile 目录」——安装器型（Forge/NeoForge/OptiFine）的标记写在派生
     * profile 上，不在 base 版本上，得靠目录名反推。都没有返回 null。
     *
     * <p>{@code gblLoader.type} 由 {@link #byId} 认（遍历 values()，OPTIFINE 天然覆盖）；
     * 只读本地、不触发网络：界面每次刷新都要问，不能顺手打网络。
     */
    public static Status status(String versionId) throws IOException {
        Map<String, Object> local = VersionJson.local(versionId);
        if (local != null) {
            Object marker = local.get("gblLoader");
            if (marker instanceof Map<?, ?> m) {
                LoaderType type = byId(str(m.get("type")));
                String loaderVersion = str(m.get("loaderVersion"));
                if (type != null && loaderVersion != null) {
                    return new Status(type, loaderVersion);
                }
                // 将来的标记 / 手改坏的：说一声但按「没装」处理，别把界面卡住
                System.err.println("[loader] 「" + versionId + "」的 gblLoader 标记认不出来（type="
                        + m.get("type") + "），按没装处理");
            }
        }
        // base 版本上没有 gblLoader 标记：安装器型把标记写在派生 profile 目录上，
        // 扫一眼 versions/ 下有没有 <mc>-forge-<v> / <mc>-neoforge-<v> /
        // <mc>-optifine-<v>，有就反推一个 Status。
        String forge = findDerivedProfile(versionId, LoaderType.FORGE);
        if (forge != null) {
            return new Status(LoaderType.FORGE,
                    stripPrefix(forge, versionId + "-forge-", forge));
        }
        String neo = findDerivedProfile(versionId, LoaderType.NEOFORGE);
        if (neo != null) {
            // NeoForge 旧命名复用 "forge"，两种前缀都认；解析不出版本就退回整段目录名
            String lv = stripPrefix(neo, versionId + "-neoforge-",
                    stripPrefix(neo, versionId + "-forge-", neo));
            return new Status(LoaderType.NEOFORGE, lv);
        }
        String optifine = findDerivedProfile(versionId, LoaderType.OPTIFINE);
        if (optifine != null) {
            return new Status(LoaderType.OPTIFINE,
                    stripPrefix(optifine, versionId + "-optifine-", optifine));
        }
        return null;
    }

    // ------------------------------------------------------------------ 安装

    /**
     * 安装入口：先做 OptiFine 组合前置校验（P0 不自动组合），再按类型分流。
     *
     * <p>元数据型交给 {@link #installMeta}（合并写回，原逻辑零改动），返回 null 表示
     * 「没有派生 profile id」；安装器型交给 {@link #installInstaller}，返回生成的派生
     * profile id（如 {@code "1.20.1-forge-47.2.0"} / {@code "1.20.1-optifine-HD_U_I6"}），
     * UI 据此把「当前游戏」切过去。
     *
     * @return 派生 profile id（安装器型）；元数据型返回 null
     * @throws IOException 组合不被允许时抛可读错误（message 直接可显示，由 UI 展示）
     */
    public static String install(String versionId, LoaderType type, String loaderVersion,
                                Listener listener) throws IOException {
        assertCombinationAllowed(versionId, type);
        if (type.isInstaller()) {
            return installInstaller(versionId, type, loaderVersion, listener);
        }
        installMeta(versionId, type, loaderVersion, listener);
        return null;
    }

    /**
     * OptiFine 组合前置校验：P0 一律禁止自动组合。
     *
     * <p>GBL 没有 HMCL 的 priority patch 系统，两个安装器型都会往 {@code gblLoader}
     * 写标记、互相覆盖；且 OptiFine 与 Fabric 系原生不兼容。这里在 install 入口统一拦截，
     * 失败抛带可读 message 的 {@link IOException}，UI catch 后直接展示即可，无需新入口。
     *
     * <p>判定口径（{@link #status} 只读本地、不触网）：
     * <ul>
     *   <li>目标没装加载器，或已装的就是本次要装的同类 → 放行（重装交给后续逻辑）；</li>
     *   <li>组合与 OptiFine 无关（如在原版上装 Forge）→ 放行，沿用既有行为；</li>
     *   <li>一端 OptiFine、另一端 Fabric/Quilt/LegacyFabric → 明确不兼容 +
     *       推荐 Iris Shaders + Sodium，直接拒绝；</li>
     *   <li>一端 OptiFine、另一端 Forge/NeoForge → 给版本匹配提示 +
     *       「0.8.7 不自动组合」，直接拒绝。</li>
     * </ul>
     */
    private static void assertCombinationAllowed(String versionId, LoaderType type)
            throws IOException {
        Status existing = status(versionId);
        if (existing == null || existing.type() == type) {
            return;
        }
        LoaderType other = existing.type();
        boolean involvesOptiFine = type == LoaderType.OPTIFINE || other == LoaderType.OPTIFINE;
        if (!involvesOptiFine) {
            return;
        }
        if (other.isFabricFamily() || type.isFabricFamily()) {
            throw new IOException("「" + versionId + "」已装 " + other.label()
                    + "，OptiFine 与 Fabric / Quilt / LegacyFabric 明确不兼容，不能叠加安装；"
                    + "请先卸载 " + other.label()
                    + " 后单独安装 OptiFine，或改用 Iris Shaders + Sodium（光影方案）");
        }
        // Forge / NeoForge ↔ OptiFine：只给版本匹配提示，P0 不自动组合
        throw new IOException("「" + versionId + "」已装 " + other.label() + " "
                + existing.loaderVersion() + "。OptiFine 与 " + other.label()
                + " 组合需要两者严格匹配同一 Minecraft 版本的兼容构建，部分组合会崩溃；"
                + "0.8.7 P0 暂不自动组合，请先卸载 " + other.label()
                + " 后单独安装 OptiFine，或手动安装已确认兼容的构建");
    }

    /**
     * 安装器型入口（Forge / NeoForge / OptiFine）。
     *
     * <p>Forge / NeoForge：下载 installer jar → 无头运行（{@link #runInstallerJar}，
     * 用 Forge installer 的专属无头参数）→ 给生成的派生 profile 写
     * {@code gblLoader} 标记。installer 自己生成独立的新 profile 目录，原版 base 不动。
     *
     * <p>OptiFine（v1.4 定稿）：**不走无头 installer**。实证下载 jar 的
     * {@code Main-Class=optifine.InstallerFrame}（Swing GUI）、jar 内无该无头参数、
     * 无 {@code net.minecraft.launchwrapper} 类，实跑 headless 抛
     * {@code HeadlessException}、零产出；只能作 library + tweakClass 用。故转
     * {@link #installOptiFine}「运行时拉 jar → 原样当主库 + 从 jar 内嵌解出
     * launchwrapper → 落 libraries → 自写派生 profile」，零 GUI、零
     * {@code user.home} 依赖、完全落在便携目录。
     *
     * @param baseId       原版 MC 版本 id（如 {@code "1.20.1"}）
     * @param type         {@link LoaderType#FORGE} / {@link LoaderType#NEOFORGE} /
     *                     {@link LoaderType#OPTIFINE}
     * @param loaderVersion Forge 传 build（{@code "47.2.0"}）；NeoForge 传版本 id
     *                     （{@code "1.20.1-47.1.85"} 或 {@code "21.1.0.1"}）；
     *                     OptiFine 传 {@code <type>/<patch>}（如 {@code "HD_U/I5"}）
     * @return 派生 profile id
     */
    public static String installInstaller(String baseId, LoaderType type, String loaderVersion,
                                         Listener listener) throws IOException {
        // OptiFine 走「库 jar + 自写派生 profile」，绝不跑 installer（无头参数对它无效）。
        // 这一 early return 保证 OptiFine 安装路径不进入下面 Forge/NeoForge 的 runInstallerJar。
        if (type == LoaderType.OPTIFINE) {
            return installOptiFine(baseId, loaderVersion, listener);
        }

        // 前置：base 原版必须先装好（installer 是基于已装原版补 patch 出来的）
        if (!GameDownloader.installed(baseId)) {
            throw new IOException("「" + baseId + "」还没安装，请先装好原版再装 " + type.label());
        }

        // 派生 profile 全名：NeoForge 的版本 id 可能已带 "1.20.1-" 前缀，别拼成
        // "1.20.1-1.20.1-47.1.85" 这种重复前缀
        String full = loaderVersion.startsWith(baseId + "-")
                ? loaderVersion : (baseId + "-" + loaderVersion);

        // 主源恒为 BMCLAPI 国内镜像、备用源为官方 maven —— 两条真正不同的可用线路。
        // 「下载源：官方」只管 Mojang 游戏文件 / 资源，不该让加载器 installer 直接连国内
        // 连不上的海外 maven：旧逻辑在 Mirror.enabled()==false 时主源与备用源都等于同一个
        // 坏掉的官方 URL，两次下载打同一个 404（0.9.1 NeoForge 报障根因）。故这里不再用
        // Mirror.enabled() 决定 installer 主源，镜像优先保证「没有代理也能装上」。
        String primaryUrl;
        String fallbackUrl;
        if (type == LoaderType.FORGE) {
            String rel = "/maven/net/minecraftforge/forge/" + full + "/forge-" + full
                    + "-installer.jar";
            primaryUrl = LoaderType.BMCLAPI + rel;
            fallbackUrl = officialForgeUrl(full);
        } else {
            String installerPath = neoforgeInstallerPath(baseId, loaderVersion);
            if (installerPath == null) {
                throw new IOException("在 BMCLAPI 上找不到 NeoForge " + loaderVersion
                        + " 的安装器，稍后重试或检查网络");
            }
            String rel = installerPath.startsWith("/") ? installerPath : "/" + installerPath;
            primaryUrl = LoaderType.BMCLAPI + rel;
            fallbackUrl = officialNeoForgeUrl(rel);
        }

        // 下载 installer jar（.part + 哈希 + 回落由 FileFetch 负责；这里只管主源→备用源换源）
        Path jar = Platform.minecraftDir().resolve(".gbl-tmp")
                .resolve("forge-installer-" + full + ".jar");
        stage(listener, "正在下载 " + type.label() + " 安装器…");
        boolean ok = FileFetch.download(primaryUrl, jar, "", "", null, null);
        if (!ok) {
            stage(listener, "主源没下下来，换备用源重试…");
            ok = FileFetch.download(fallbackUrl, jar, "", "", null, null);
        }
        if (!ok) {
            throw new IOException("「" + type.label() + "」安装器下载失败（试过主源和备用源都不行），"
                    + "稍后重试或检查网络");
        }

        // 无头运行 installer 前，先备好官方 launcher 的 profiles 注册表（Forge/NeoForge 安装器
        // 会读它找原版 profile，没有就报「no minecraft launcher profile」直接中止）。
        // GBL 自己不读这个文件，纯为喂给安装器；写坏了也不影响 GBL 其它逻辑。
        ensureLauncherProfiles(baseId);

        // 无头运行 installer
        runInstallerJar(jar, type, listener);

        // 定位生成的派生 profile 目录（多数情况就是 full，NeoForge 可能叫 -neoforge- / -forge-）
        Path versionsDir = Platform.minecraftDir().resolve("versions");
        Path profileDir = versionsDir.resolve(full);
        String resolved = full;
        if (!Files.isDirectory(profileDir)) {
            String found = findDerivedProfile(baseId, type);
            if (found != null) {
                resolved = found;
                profileDir = versionsDir.resolve(found);
            }
        }
        if (!Files.isDirectory(profileDir)) {
            throw new IOException("安装器跑完了但没找到生成的版本目录");
        }

        // 读生成的 profile JSON，塞 gblLoader 标记后写回（复用 VersionJson.save 的 tmp+move）
        Map<String, Object> local = VersionJson.local(resolved);
        if (local == null) {
            local = VersionJson.raw(resolved);
        }
        if (local == null) {
            throw new IOException("读不到生成的版本信息");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = (Map<String, Object>) deep(local);
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("type", type.id());
        marker.put("loaderVersion", loaderVersion);
        marker.put("minecraftVersion", baseId);
        marker.put("installedAt", Instant.now().toString());   // ISO-8601 UTC
        merged.put("gblLoader", marker);
        VersionJson.save(resolved, merged);

        // 清理：删临时 installer jar（体积大，留着没意义；best-effort）
        try {
            Files.deleteIfExists(jar);
        } catch (IOException ignored) {
            // 删不掉就留着，下次写同名覆盖
        }
        stage(listener, type.label() + " " + loaderVersion + " 安装完成");
        return resolved;
    }

    // ================================================================== 安装器型：OptiFine 自写派生 profile（v1.4 定稿）

    /**
     * OptiFine 安装（v1.4）：运行时拉 installer jar → 原样当主库 + 从 jar 内嵌解出
     * launchwrapper-of → 落 libraries → 自写独立派生 profile。
     *
     * <p><b>依据（#57 实证 + T-10 只读补测 + 本次 jar 内检）</b>：下载 jar 的
     * {@code Main-Class=optifine.InstallerFrame}（Swing GUI）、含
     * {@code optifine.OptiFineTweaker}、jar 内无 Forge 专属无头参数；实跑 headless
     * 抛 {@code HeadlessException} 零产出。故不能无头跑 installer，只能把 installer jar
     * 当 library + {@code --tweakClass} 注入（架构候选 A，Q6/U5 拍板）。
     *
     * <p>流程（任何一步失败都抛可读 {@link IOException}，<b>绝不静默生成坏版本</b>）：
     * <ol>
     *   <li>防御性备份 base 客户端 jar / json（{@link #backupBaseClientJar}）；</li>
     *   <li>解析版本串 {@code <type>/<patch>}；</li>
     *   <li>GET 下载 OptiFine installer jar（容忍 206 + 跟随重定向），校验是有效 jar；</li>
     *   <li>用 {@link #extractLaunchwrapper} 从该 jar 的内嵌条目解出 launchwrapper-of
     *       （字节直写、零外部请求；老版本无内嵌则明确失败）；</li>
     *   <li>H9 幂等覆盖：两个目标 path 先删再写
     *       {@code libraries/optifine/OptiFine/<ver>/OptiFine-<ver>.jar} 与
     *       {@code libraries/optifine/launchwrapper-of/<v>/launchwrapper-of-<v>.jar}；</li>
     *   <li>{@link #writeOptiFineProfile} 以完整父 JSON 深拷贝为基底自写派生 version.json
     *       （H7 两条 library 全 {@code downloads.artifact} + H8 参数继承）；</li>
     *   <li>写 {@code gblLoader.type=optifine} → {@link VersionJson#save}，复制派生
     *       {@code <id>.jar} 作「已装」标记（E21）。</li>
     * </ol>
     *
     * <p>jar 全程运行时拉取，绝不打包进分发包。
     */
    private static String installOptiFine(String baseId, String loaderVersion, Listener listener)
            throws IOException {
        if (!GameDownloader.installed(baseId)) {
            throw new IOException("「" + baseId + "」还没安装，请先装好原版再装 OptiFine");
        }
        // 版本串 <type>/<patch>：用 '/' 分隔无歧义、自包含，且不依赖版本列表（支持手动输入降级）
        String[] parts = loaderVersion == null ? new String[0] : loaderVersion.split("/", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IOException("OptiFine 版本串格式不对：应为 <类型>/<补丁>（如 HD_U/I5），"
                    + "实际收到「" + loaderVersion + "」");
        }
        String type = parts[0].trim();
        String patch = parts[1].trim();
        String ver = baseId + "_" + type + "_" + patch;              // library 坐标版本
        String resolved = baseId + "-optifine-" + type + "_" + patch; // 派生 profile id

        // 防御性备份（实证 installer 产出独立 profile、不改写原版 jar；此备份仅兜底）
        backupBaseClientJar(baseId, listener);

        Path libDir = Platform.minecraftDir().resolve("libraries");
        String ofPath = "optifine/OptiFine/" + ver + "/OptiFine-" + ver + ".jar";
        Path ofJar = libDir.resolve(ofPath);
        Path tmpDir = Platform.minecraftDir().resolve(".gbl-tmp");

        // 1) OptiFine installer jar：GET 下载（FileFetch 跟随 Redirect.NORMAL、容忍 206 + 签名 302）。
        //    #70 实测 BMCLAPI 偶发 CDN 截断（1.16.5 首次只拿到 1,179,648B 非 zip，重试第 1 次
        //    即成功）：FileFetch.download() 传空 sha1/sha512、内部重试认不出「HTTP 200 但流半截」，
        //    会把半截文件 move 成目标返回 true。故这里把「下载 + 有效 zip 校验」作为一个整体
        //    最多做 2 次，半截 jar 绝不进安装流程；两次都失败才给手动指引。
        stage(listener, "正在下载 OptiFine " + loaderVersion + "（运行时拉取，绝不打包）…");
        Path tmpOf = tmpDir.resolve("optifine-" + ver + ".jar");
        boolean gotJar = false;
        for (int attempt = 1; attempt <= 2 && !gotJar; attempt++) {
            if (attempt > 1) {
                stage(listener, "下载没完成，正在重试…");
            }
            gotJar = FileFetch.download(optifineDownloadUrl(baseId, loaderVersion),
                    tmpOf, "", "", null, null) && isValidJar(tmpOf);
            if (!gotJar) {
                deleteQuietly(tmpOf);
            }
        }
        if (!gotJar) {
            throw optiFineManualHint("OptiFine jar 下载失败或文件不完整"
                    + "（BMCLAPI GET 重试后仍拿不到有效 jar），", baseId);
        }

        // 2) launchwrapper-of：从 installer jar 的内嵌条目解出（T-10 / E14 闭环，零外部请求）。
        //    老版本 OptiFine 安装包实测不带内嵌库（1.7.10 HD_U/D3 内无嵌入 .jar）→
        //    extractLaunchwrapper 抛明确错误 + 手动指引，绝不静默装坏（E26 干净失败）。
        //    失败点必须在 placeJar / placeBytes / VersionJson.save / 复制派生 jar **之前**：
        //    这样 versions/<derived>/ 不会被创建、两个 library path 不会被写入、
        //    无 version.json / gblLoader、游戏列表不出现该版本。同时清掉刚下载的
        //    临时 jar，别在 .gbl-tmp 留 7MB 垃圾。
        Launchwrapper lw;
        try {
            lw = extractLaunchwrapper(tmpOf, baseId);
        } catch (IOException ex) {
            deleteQuietly(tmpOf);
            throw ex;
        }
        String lwPath = "optifine/launchwrapper-of/" + lw.version()
                + "/launchwrapper-of-" + lw.version() + ".jar";
        Path lwJar = libDir.resolve(lwPath);

        // 3) H9 幂等覆盖：两个目标 path 先删再写，保证落盘文件 sha1 == profile 声明 sha1。
        //    目标上可能残留合法但 sha1 不同的旧文件（如纯 mod jar f572669b…），不覆盖会让
        //    verifyExisting() 每次启动判坏 → 请求占位 url → 把 7MB 写进 15KB path → 必崩。
        placeJar(tmpOf, ofJar);          // A 方案：installer jar 原样当主库
        placeBytes(lw.bytes(), lwJar);   // 内嵌 launchwrapper-of 字节直写

        // 4) 两条 library 的 jar 都真实落盘后才写 profile（H7 归一 + H8 参数继承）
        Map<String, Object> profile = writeOptiFineProfile(baseId, resolved, ver,
                loaderVersion, ofPath, lwPath, lw.version(), ofJar, lwJar);
        VersionJson.save(resolved, profile);

        // 5) GameScanner.scan() → hasJar() 要求版本目录里有 .jar 才列为「已装」：从父版本
        //    复制一份 client jar 作存在标记（E21）。inheritsFrom 型启动链不读它
        //    （GameLauncher.prepare 仅在 inheritsFrom==null 时要求客户端 jar），
        //    version.json 的 jar 字段仍为 null（满足 QA 形状断言）。
        Path derivedDir = GameScanner.gameDirFor(resolved);
        Path baseJar = GameScanner.gameDirFor(baseId).resolve(baseId + ".jar");
        Path derivedJar = derivedDir.resolve(resolved + ".jar");
        if (Files.isRegularFile(baseJar) && !Files.exists(derivedJar)) {
            try {
                Files.copy(baseJar, derivedJar);
            } catch (IOException ex) {
                throw optiFineManualHint("生成派生版本目录标记失败（" + ex.getMessage() + "），",
                        baseId);
            }
        }
        stage(listener, "OptiFine " + loaderVersion + " 安装完成（独立版本 " + resolved + "）");
        return resolved;
    }

    /**
     * 按实证形状自写 OptiFine 派生 profile version.json（架构 §3.8 候选 A / v1.4 定稿）。
     *
     * <p><b>H8 参数继承（P0，照抄增量式 profile 会启动必崩）</b>：
     * {@code VersionJson.resolveInheritance()} 对 {@code arguments} 是整键「子覆盖父」，
     * 只新建一个 {@code game=[--tweakClass,...]} 的 arguments 会把原版父的 jvm/game
     * 全部参数顶掉。这里以 {@link VersionJson#raw} 的<b>完整父 JSON 深拷贝</b>为基底，
     * 保留 {@code arguments.jvm} + {@code arguments.game} 全数组、{@code assetIndex} /
     * {@code javaVersion} / {@code type} / {@code time} 等，只覆盖
     * {@code id / mainClass / libraries / jar}，再复用 {@link #appendTweakClass} 在 game
     * 末尾追加 tweakClass（它只在 game 段追加、不动 jvm 段）。
     *
     * <p><b>H7 库形状归一</b>：两条 library 都写全
     * {@code downloads.artifact.{path,url,sha1,size}}，sha1/size 取落盘文件真实值
     * （动态算，不写死单版本定值，任意 mc/patch 都满足「落盘 sha1 == 声明 sha1」），
     * url 走 E22 哨兵（见 {@link #optiFineSentinelUrl}）——
     * 裸 name 条目会被 {@code LibFetcher.missing()} 与
     * {@code GameLauncher.addToClasspath()} 双双跳过。
     */
    private static Map<String, Object> writeOptiFineProfile(String baseId, String resolved,
                                                           String ver, String loaderVersion,
                                                           String ofPath, String lwPath,
                                                           String lwVer, Path ofJar, Path lwJar)
            throws IOException {
        Map<String, Object> parent = VersionJson.raw(baseId);
        if (parent == null) {
            throw new IOException("读不到原版「" + baseId + "」的游戏信息，"
                    + "请先把这个原版装好，再来装光影相关组件");
        }
        // 顶层深拷贝 + arguments 单独深拷贝：避免 appendTweakClass 改到父缓存对象
        Map<String, Object> profile = new LinkedHashMap<>(parent);
        if (parent.get("arguments") instanceof Map<?, ?> args) {
            Map<String, Object> argsCopy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : args.entrySet()) {
                Object v = e.getValue();
                argsCopy.put(String.valueOf(e.getKey()),
                        v instanceof List<?> list ? new ArrayList<>(list) : v);
            }
            profile.put("arguments", argsCopy);
        }

        // 仅覆盖这几项；其余（type/time/releaseTime/assetIndex/javaVersion/downloads）留父的。
        // libraries 只写两条 OptiFine 库：resolveInheritance() 会按 name 把父的原版库合并回来。
        profile.put("id", resolved);
        profile.put("inheritsFrom", baseId);
        profile.put("mainClass", "net.minecraft.launchwrapper.Launch");
        profile.put("jar", null);   // 独立 profile，客户端 jar 靠 inheritsFrom 继承父版本

        List<Object> libs = new ArrayList<>();
        // E22：两条 artifact.url 都是哨兵（非 blank 可过 LibFetcher.java:227-232 校验，
        // 但指向本机不可连接端口）：Mirror.rewrite() 对 127.0.0.1 恒等、无 originUrl 回落，
        // 即便 H9 幂等覆盖防线被破坏，也只会快速失败、不会把 7MB installer jar 下到错误
        // path、不留 .part。安装期 FileFetch.download() 仍用 optifineDownloadUrl() 真实直链。
        libs.add(optiFineLibrary("optifine:OptiFine:" + ver, ofPath,
                optiFineSentinelUrl("optifine:OptiFine:" + ver), ofJar));
        libs.add(optiFineLibrary("optifine:launchwrapper-of:" + lwVer, lwPath,
                optiFineSentinelUrl("optifine:launchwrapper-of:" + lwVer), lwJar));
        profile.put("libraries", libs);

        // H8：在继承来的完整 game 参数末尾追加 tweakClass（不动 jvm 段）
        appendTweakClass(profile, "optifine.OptiFineTweaker");

        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("type", LoaderType.OPTIFINE.id());   // "optifine"
        marker.put("loaderVersion", loaderVersion);
        marker.put("minecraftVersion", baseId);
        marker.put("installedAt", Instant.now().toString());
        profile.put("gblLoader", marker);
        return profile;
    }

    /** 一条带完整 downloads.artifact 的 library（H7）：path/url 非 blank，sha1/size 取落盘真实值。 */
    private static Map<String, Object> optiFineLibrary(String name, String path, String url,
                                                       Path jar) throws IOException {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("path", path);
        artifact.put("url", url);
        artifact.put("sha1", sha1Hex(jar));   // 真实值：verifyExisting 通过、不触发重下（H7/H9）
        artifact.put("size", Files.size(jar));
        Map<String, Object> downloads = new LinkedHashMap<>();
        downloads.put("artifact", artifact);
        Map<String, Object> lib = new LinkedHashMap<>();
        lib.put("name", name);
        lib.put("downloads", downloads);
        return lib;
    }

    /**
     * E22 哨兵 url：写进 {@code downloads.artifact.url} 的值。
     *
     * <p>格式 {@code http://127.0.0.1:1/gbl-no-download/<coord>}，{@code <coord>} 是
     * library name 把 {@code ':'} 换成 {@code '/'}（如
     * {@code optifine/OptiFine/1.20.1_HD_U_I5}）。非 blank 可过
     * {@code LibFetcher.java:227-232} 校验；但 host 是 {@code 127.0.0.1} →
     * {@code Mirror.rewrite()} 恒等、无 {@code originUrl()} 回落、
     * {@code LibFetcher.copyInto()} 连接失败时尚未创建 {@code .part} →
     * 即便 H9 幂等覆盖防线被破坏，也只会快速失败、不会把 7MB installer jar 下到
     * 15KB 的 launchwrapper path、不留 {@code .part} 垃圾。
     *
     * <p><b>注意</b>：这只改「写进 profile 的 url」；安装期
     * {@link #optifineDownloadUrl} 的真实 BMCLAPI 直链本体保留、仍由
     * {@code FileFetch.download()} 使用（§11 {@code OPTIFINE_URL_SENTINEL} 会断言两者分离）。
     */
    private static String optiFineSentinelUrl(String libraryName) {
        return "http://127.0.0.1:1/gbl-no-download/" + libraryName.replace(':', '/');
    }

    /** 从 installer jar 解出的 launchwrapper-of（版本号 + 字节），零外部请求（T-10 / E14）。 */
    private record Launchwrapper(String version, byte[] bytes) {}

    /**
     * 从 OptiFine installer jar 的内嵌条目解出 launchwrapper-of：
     * <ul>
     *   <li>内嵌 {@code launchwrapper-of-<ver>.jar}（或唯一内嵌 .jar）的字节直接写盘 ——
     *       T-10 已做字节级比对，与 installer 自身落盘文件一致（True）；</li>
     *   <li>版本号优先读 {@code launchwrapper-of.txt}（installer 用它决定落盘版本，权威），
     *       读不到则从内嵌 jar 文件名解析（{@link #versionFromJarName}），
     *       两者都认不出 → 抛明确 {@link IOException}；<b>绝不兜底常量 2.3</b> ——
     *       #70 实测 2.1 / 2.2 / 2.3 三种内嵌库都真实存在且 sha1 不同；</li>
     *   <li><b>老版本 OptiFine 安装包不带内嵌库</b>：#70 跨 13 版本实测边界
     *       <b>1.13.2 → 1.14.4</b>（1.13.2 及更早零嵌入 .jar / 零 launchwrapper 条目，
     *       1.14.4 起都有）→ 抛明确 {@link IOException} + 手动指引，绝不静默生成坏版本
     *       （team-lead「绝不假成功」/ architect「缺库给明确 toast」）。</li>
     * </ul>
     *
     * <p>只扫 zip 条目判有无内嵌库，<b>不用 MANIFEST 当信号</b>：#70 实测
     * {@code Automatic-Module-Name} 与内嵌库不同步（1.14.4~1.17.1 有内嵌库却无该字段），
     * 而 {@code Main-Class} / {@code TweakClass} 所有版本都有，都不具判别力。
     * 动态扫描、不强依赖确切路径，installer 结构微调也能自适应。
     */
    private static Launchwrapper extractLaunchwrapper(Path installerJar, String baseId)
            throws IOException {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(installerJar.toFile())) {
            List<java.util.zip.ZipEntry> jars = new ArrayList<>();
            String version = null;
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry e = entries.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                String lower = e.getName().toLowerCase(Locale.ROOT);
                if (lower.endsWith(".jar")) {
                    jars.add(e);
                }
                if (lower.endsWith("launchwrapper-of.txt")) {
                    version = new String(zipBytes(zf, e), StandardCharsets.UTF_8).trim();
                }
            }
            java.util.zip.ZipEntry picked = null;
            for (java.util.zip.ZipEntry e : jars) {
                if (e.getName().toLowerCase(Locale.ROOT).contains("launchwrapper-of")) {
                    picked = e;
                    break;
                }
            }
            if (picked == null && jars.size() == 1) {
                picked = jars.get(0);
            }
            if (picked == null) {
                throw optiFineManualHint("这个 OptiFine 安装包里没有内嵌的 launchwrapper-of 库"
                        + "（老版本 OptiFine 安装包不带它），GBL 无法可靠自动安装，", baseId);
            }
            // 版本号：优先 launchwrapper-of.txt（installer 自己用它决定落盘版本，权威）；
            // 读不到则从内嵌 jar 文件名解析（#70 实测 13 样本「txt 与 jar 名版本一致」）；
            // 两者都认不出 → 抛明确 IOException，绝不兜底常量 2.3 ——
            // 2.1 / 2.2 / 2.3 三种内嵌库都真实存在且 sha1 不同，兜底 2.3 会让
            // 坐标 / path 与落盘字节描述不同版本。
            if (version == null || !version.matches("\\d[\\w.\\-]*")) {
                version = versionFromJarName(picked.getName());
            }
            if (version == null || !version.matches("\\d[\\w.\\-]*")) {
                throw optiFineManualHint("这个 OptiFine 安装包里读不出 launchwrapper-of 版本"
                        + "（launchwrapper-of.txt 与内嵌 jar 文件名都认不出来），"
                        + "GBL 无法可靠自动安装，", baseId);
            }
            return new Launchwrapper(version, zipBytes(zf, picked));
        }
    }

    /**
     * 从内嵌 jar 文件名解析 launchwrapper-of 版本：
     * {@code launchwrapper-of-2.2.jar} → {@code 2.2}；认不出返回 null。
     *
     * <p>#70 实测 1.14.4→2.1 / 1.15.2~1.17.1→2.2 / 1.18.1 起→2.3，
     * 文件名与同 jar 内 {@code launchwrapper-of.txt} 内容在 13 个样本中全部一致，
     * 可作 txt 缺失时的权威备份（仍会在调用处过合法版本号校验）。
     */
    private static String versionFromJarName(String entryName) {
        String file = entryName.substring(entryName.lastIndexOf('/') + 1)
                .toLowerCase(Locale.ROOT);
        String prefix = "launchwrapper-of-";
        if (!file.startsWith(prefix) || !file.endsWith(".jar")) {
            return null;
        }
        String v = file.substring(prefix.length(), file.length() - ".jar".length());
        return v.isEmpty() ? null : v;
    }

    /** 读 ZipFile 某条目的全部字节（ZipFile 没有 readAllBytes(Entry)，走 getInputStream）。 */
    private static byte[] zipBytes(java.util.zip.ZipFile zf, java.util.zip.ZipEntry e)
            throws IOException {
        try (var in = zf.getInputStream(e)) {
            return in.readAllBytes();
        }
    }

    /** 文件真实 sha1（小写 hex），与 {@code LibFetcher.sha1Of(Path)} 同格式；JDK 必带 SHA-1。 */
    private static String sha1Hex(Path file) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] buffer = new byte[64 * 1024];
            try (var in = Files.newInputStream(file)) {
                int n;
                while ((n = in.read(buffer)) > 0) {
                    md.update(buffer, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IOException("JDK 缺 SHA-1，无法校验 OptiFine 库文件", ex);
        }
    }

    /**
     * H9 幂等覆盖：先删目标（残留旧文件 sha1 可能不符），再写入本次正确文件。
     *
     * <p>顺带清同级的 {@code *.part}（{@code LibFetcher.java:389} 产物 {@code <target>.part}）：
     * E22 哨兵 url 下正常安装根本不会创建 .part（连接失败在 newByteChannel 之前），但
     * §11 {@code OPTIFINE_NO_PART_RESIDUE} 要断言「同级无 *.part 残留」并保留作防线
     * 破坏检测，故安装期主动清预置 / 历史残留。本目录是 OptiFine 专属、无并发其它库下载。
     */
    private static void placeBytes(byte[] data, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        deleteQuietly(target);
        cleanPartResidue(target);
        Files.write(target, data);
    }

    /** 清掉目标同级目录里的 {@code *.part}（best-effort，扫不动不影响主流程）。 */
    private static void cleanPartResidue(Path target) {
        Path dir = target.getParent();
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var stream = Files.newDirectoryStream(dir, "*.part")) {
            for (Path part : stream) {
                deleteQuietly(part);
            }
        } catch (IOException ignored) {
            // 目录扫不动就算了，不盖住真正的安装错误
        }
    }

    /** 把下载好的临时 jar 落到 {@code libraries/<path>}（走 placeBytes 幂等覆盖，写完删临时文件）。 */
    private static void placeJar(Path tmp, Path target) throws IOException {
        placeBytes(Files.readAllBytes(tmp), target);
        deleteQuietly(tmp);
    }

    /** 文件是不是一个有效的 zip/jar（能打开中央目录即算，挡住 CDN 错误页 / 半截文件）。 */
    private static boolean isValidJar(Path file) {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(file.toFile())) {
            return zf.size() > 0;
        } catch (IOException ex) {
            return false;
        }
    }

    /** 删不掉就算了（best-effort），不因清理失败盖住真正的错误。 */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 忽略：下次写同名会覆盖
        }
    }

    /** 统一给「无法可靠自动安装」的可读错误，附手动运行 installer 的明确指引，绝不假成功。 */
    private static IOException optiFineManualHint(String reason, String baseId) {
        return new IOException(reason + "0.8.7 不会生成一个能启动但进不了游戏的坏版本。"
                + "请手动运行 OptiFine 安装器：双击下载到的 OptiFine jar，在「安装目录」里选 "
                + "GBL 的 .minecraft（原版 " + baseId + " 所在目录），装好后回 GBL 刷新版本列表");
    }

    /**
     * 定位某 base 版本下已装的派生 profile；没装返回 null（卸载 / 状态用）。
     *
     * <p>不抛异常：任何读盘错误都按「没找到」返回 null（界面不该因为扫盘失败崩）。
     */
    public static String findDerivedProfile(String baseId, LoaderType type) {
        try {
            Path versions = Platform.minecraftDir().resolve("versions");
            if (!Files.isDirectory(versions)) {
                return null;
            }
            String forgePrefix = baseId + "-forge-";
            String neoPrefix = baseId + "-neoforge-";
            String optifinePrefix = baseId + "-optifine-";
            try (var children = Files.list(versions)) {
                List<String> hits = new ArrayList<>();
                for (Path child : children.toList()) {
                    if (!Files.isDirectory(child)) {
                        continue;
                    }
                    String name = child.getFileName().toString();
                    if (type == LoaderType.FORGE) {
                        if (name.startsWith(forgePrefix)) {
                            hits.add(name);
                        }
                    } else if (type == LoaderType.NEOFORGE) {
                        // NeoForge 旧命名复用 "forge"，两种前缀都算
                        if (name.startsWith(neoPrefix) || name.startsWith(forgePrefix)) {
                            hits.add(name);
                        }
                    } else if (type == LoaderType.OPTIFINE) {
                        // OptiFine 独立派生 profile：<mc>-optifine-<v>
                        if (name.startsWith(optifinePrefix)) {
                            hits.add(name);
                        }
                    }
                }
                if (hits.isEmpty()) {
                    return null;
                }
                // 名字倒序：新版本（号大）排前面，优先返回最新的那个
                hits.sort(Comparator.reverseOrder());
                return hits.get(0);
            }
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 卸载

    /**
     * 卸载入口：先问 {@link #status} 分流。
     *
     * <p>安装器型（base 上没有标记、标记在派生 profile 上）→ {@link #uninstallInstaller}；
     * 元数据型 → {@link #uninstallMeta}（从备份逐字节还原）。
     *
     * <p>卸载 OptiFine 时，删完派生 profile 后额外从 {@code .gbl-backup} 备份逐字节还原
     * base 客户端 jar / version json（见 {@link #restoreBaseClientJar}）。
     */
    public static void uninstall(String versionId, Listener listener) throws IOException {
        Status status = status(versionId);
        if (status != null && status.type().isInstaller()) {
            uninstallInstaller(versionId, status.type(), listener);
            if (status.type() == LoaderType.OPTIFINE) {
                restoreBaseClientJar(versionId, listener);
            }
            return;
        }
        uninstallMeta(versionId, listener);
    }

    /**
     * 卸载派生 profile：把整个 profile 目录移进回收站（recycle，可捡回）。
     *
     * <p>libraries/ 里的 Forge/NeoForge 库不删：多版本共用，留着无害，下次安装直接命中。
     */
    public static void uninstallInstaller(String baseId, LoaderType type, Listener listener)
            throws IOException {
        String profile = findDerivedProfile(baseId, type);
        if (profile == null) {
            throw new IOException("没找到对应的 " + type.label() + " 版本目录，没法卸载");
        }
        Path profileDir = Platform.minecraftDir().resolve("versions").resolve(profile);
        stage(listener, "正在卸载 " + type.label() + "（" + profile + "）…");
        if (!Platform.recycle(profileDir)) {
            throw new IOException("删不掉 " + profile + "（回收站没写进去），没法卸载");
        }
        System.out.println("[loader] 已将 " + profile + " 移入回收站");
        stage(listener, type.label() + " 已卸载（共享的库文件留着，下次安装可直接命中）");
    }

    // ================================================================== 安装器型：OptiFine base 客户端 jar 备份 / 还原

    /**
     * OptiFine 安装前<b>防御性</b>备份 base 客户端 jar 与 version json。
     *
     * <p>v1.1 实证修正：installer 产出的是独立 profile（{@code inheritsFrom} 指向原版、
     * {@code jar} 为 null），<b>不改写原版客户端 jar、不合并进原版 JSON</b>；本备份仅作
     * 兜底（防将来 installer 行为变化 / 用户手动 patch 破坏原版），不再声称「必被改写」。
     * 命名规则：在版本目录内把源文件追加 {@code .gbl-backup} 后缀——
     * <ul>
     *   <li>{@code versions/<mc>/<mc>.jar → versions/<mc>/<mc>.jar.gbl-backup}</li>
     *   <li>{@code versions/<mc>/<mc>.json → versions/<mc>/<mc>.json.gbl-backup}
     *       （{@code <mc>.json} 不存在时改备份 {@code version.json → version.json.gbl-backup}）</li>
     * </ul>
     *
     * <p>幂等：备份已存在就不覆盖（永远保留最初那份原版），重复安装 / 上次中断残留都安全。
     * 源文件不存在就跳过（base 没装全时不该卡住安装）。
     */
    private static void backupBaseClientJar(String baseId, Listener listener) throws IOException {
        Path dir = GameScanner.gameDirFor(baseId);
        backupOne(dir.resolve(baseId + ".jar"), listener, "客户端 jar");
        // json 源：<id>.json 优先，缺则 version.json（GBL 元数据型会落这一份）
        Path json = Files.isRegularFile(dir.resolve(baseId + ".json"))
                ? dir.resolve(baseId + ".json") : dir.resolve("version.json");
        backupOne(json, listener, "版本信息");
    }

    /** 单文件备份：源存在且备份不存在才 copy；都不满足就跳过（不是错误）。 */
    private static void backupOne(Path source, Listener listener, String what) throws IOException {
        if (!Files.isRegularFile(source)) {
            return;
        }
        Path backup = source.resolveSibling(source.getFileName() + ".gbl-backup");
        if (Files.exists(backup)) {
            return;   // 已存在 = 上次中断残留 / 重复安装，保留最初那份
        }
        Files.copy(source, backup);
        System.out.println("[loader] 已备份原版" + what + " -> " + backup.getFileName());
        stage(listener, "已备份原版" + what + "（" + backup.getFileName() + "）");
    }

    /**
     * 卸载 OptiFine 时从 {@code .gbl-backup} 逐字节还原 base 客户端 jar 与 version json。
     *
     * <p>备份不存在 = 没备份过（旧数据 / 手动装），静默跳过不报错；
     * 还原成功后删掉备份（对齐元数据型 {@link #uninstallMeta} 的清理口径）。
     *
     * <p>UI 通常传 base id（如 {@code "1.20.1"}）；万一传的是
     * {@code <mc>-optifine-<v>} 派生 id，从该 profile 的
     * {@code gblLoader.minecraftVersion} 反推原版 id。
     */
    private static void restoreBaseClientJar(String versionId, Listener listener)
            throws IOException {
        String baseId = versionId;
        Map<String, Object> prof = VersionJson.local(versionId);
        if (prof != null && prof.get("gblLoader") instanceof Map<?, ?> m) {
            String mc = str(m.get("minecraftVersion"));
            if (mc != null) {
                baseId = mc;
            }
        }
        Path dir = GameScanner.gameDirFor(baseId);
        boolean restored = false;
        restored |= restoreOne(dir.resolve(baseId + ".jar"), "客户端 jar");
        // json 备份可能落在 <id>.json.gbl-backup 或 version.json.gbl-backup，两个都探
        restored |= restoreOne(dir.resolve(baseId + ".json"), "版本信息");
        restored |= restoreOne(dir.resolve("version.json"), "版本信息");
        if (restored) {
            stage(listener, "已还原原版客户端 jar / 版本信息（从 .gbl-backup 备份）");
        }
    }

    /** 单文件还原：备份存在才逐字节拷回（tmp+move），随后删备份；返回是否真的还原了。 */
    private static boolean restoreOne(Path target, String what) throws IOException {
        Path backup = target.resolveSibling(target.getFileName() + ".gbl-backup");
        if (!Files.isRegularFile(backup)) {
            return false;
        }
        byte[] bytes = Files.readAllBytes(backup);
        writeBytesAtomically(target, bytes);
        Files.deleteIfExists(backup);
        System.out.println("[loader] 已从备份还原原版" + what + " <- " + backup.getFileName());
        return true;
    }

    // ================================================================== 元数据型（原逻辑，改名保留）

    /**
     * 元数据型安装：合并写回 + 备份 + 补库（原 Fabric/Quilt/LegacyFabric 逻辑，零改动）。
     * 任何人话失败都抛 {@link IOException}（message 直接可显示）。
     *
     * <p>补库失败**不抛**：JSON 已带加载器标记，下次启动 {@link GameLauncher#prepare} 的
     * {@link LibFetcher#missing} 会自愈补库；只通过 {@link Listener#onStage} 提示一句。
     */
    private static void installMeta(String versionId, LoaderType type, String loaderVersion,
                                   Listener listener) throws IOException {
        // 1) 取完整版：合并必须基于完整版，精简版里没有 libraries 可并
        Map<String, Object> full = VersionJson.raw(versionId);
        if (full == null) {
            throw new IOException("「" + versionId + "」的游戏信息没读到，"
                    + "这个游戏可能没装全，去「下载」页重新装一次");
        }
        if (isThin(full)) {
            throw new IOException("版本元信息补不齐（连不上官方、本地也没有缓存），加载器装不了；联网后重试");
        }

        // 2) 前置校验（两条硬闸）
        Status already = status(versionId);
        if (already != null) {
            throw new IOException("这个版本已经装了 " + already.type().label() + " "
                    + already.loaderVersion() + "，先卸载它才能装别的加载器");
        }
        String mainClass = str(full.get("mainClass"));
        if (!VANILLA_MAIN.equals(mainClass)) {
            throw new IOException("这个版本的 mainClass 不是原版（可能已经装过 Forge 等加载器），"
                    + "GBL 暂不支持在它上面叠加安装");
        }

        // 3) 备份：不存在才创建。已存在 = 上次安装中断的残留，保留不动——备份永远是最初那份
        Path dir = GameScanner.gameDirFor(versionId);
        Path backup = dir.resolve(versionId + ".vanilla.json");
        if (!Files.exists(backup)) {
            Path source = Files.isRegularFile(dir.resolve("version.json"))
                    ? dir.resolve("version.json")
                    : dir.resolve(versionId + ".json");
            if (!Files.isRegularFile(source)) {
                throw new IOException("找不到要备份的游戏信息（" + dir + "），这个游戏可能装得不全");
            }
            Files.copy(source, backup);
            System.out.println("[loader] 已备份原版版本信息 -> " + backup.getFileName());
        }

        // 4) 拉加载器元数据：{loader, intermediary, launcherMeta}
        stage(listener, "正在拉 " + type.label() + " 版本信息…");
        Map<String, Object> meta = fetchLoaderMeta(type, versionId, loaderVersion);
        Map<?, ?> launcherMeta = meta.get("launcherMeta") instanceof Map<?, ?> m ? m : null;
        if (launcherMeta == null) {
            throw new IOException(type.label() + " 的版本接口返回里没有 launcherMeta，"
                    + "稍后重试或检查网络");
        }

        // 5) 合并（深拷贝出 merged，逐项并）。local 只读本地、不触网，用来保全
        // type / jar / time 这三个「官方完整版没有、GameScanner 又要用」的字段
        stage(listener, "正在把 " + type.label() + " 合并进版本信息…");
        Map<String, Object> local = VersionJson.local(versionId);
        Map<String, Object> merged =
                merge(full, type, versionId, loaderVersion, meta, launcherMeta, local);

        // 6) 落盘：version.json 与 <id>.json 同一内容（tmp+move，不截断直写）
        stage(listener, "正在写入版本信息…");
        VersionJson.save(versionId, merged);

        // 7) 补下载加载器库：此时本地已是完整版，load 直接返回不走网络
        stage(listener, "正在下载 " + type.label() + " 依赖库…");
        VersionJson.Meta reloaded = VersionJson.load(versionId);
        List<LibFetcher.Lib> absent = LibFetcher.missing(reloaded);
        if (!absent.isEmpty()) {
            List<LibFetcher.Lib> failed = LibFetcher.fetch(absent, new LibFetcher.Listener() {
                @Override
                public void onProgress(int done, int total, long doneBytes, long bytesPerSecond) {
                    if (listener != null) {
                        listener.onLibProgress(done, total);
                    }
                }
            });
            if (!failed.isEmpty()) {
                System.err.println("[loader] 「" + versionId + "」有 " + failed.size()
                        + " 个加载器库没下下来（启动时会自愈补库）");
                stage(listener, "有 " + failed.size() + " 个库没下下来，启动时会自动补");
            }
        }
        stage(listener, type.label() + " " + loaderVersion + " 安装完成");
    }

    /**
     * 元数据型版本列表：原 Fabric/Quilt/LegacyFabric 逻辑（从 {@link #versions} 拆出），
     * 0.8.8 Q4 补强双结构 / 扁平 stable 默认 / 空列表非异常。
     */
    private static List<LoaderVersion> versionsMeta(LoaderType type, String mcVersion)
            throws IOException {
        String text = fetchMetaText(type, mcVersion, null);
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (RuntimeException ex) {
            throw new IOException(unreadableMessage(type));
        }
        if (!(parsed instanceof List<?> list)) {
            throw new IOException(unreadableMessage(type));
        }
        List<LoaderVersion> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            // 容错（U2）：正常形状是 {"loader": {...}, ...}，也认字段平铺的
            // （Quilt BMCLAPI 全局列表是扁平 [{separator,build,maven,version}]）。
            Map<?, ?> info = entry.get("loader") instanceof Map<?, ?> loader ? loader : entry;
            String version = str(info.get("version"));
            if (version == null) {
                continue;   // 一条都拼不出版本号的，丢了别往界面上塞无名行
            }
            // 扁平结构（Quilt BMCLAPI）无 stable 字段 → 默认 true；
            // 嵌套结构（Fabric 官方）带 stable 布尔 → 取真值。缺省按稳定版，
            // 否则 ensureLoaderVersions 找不到 stable 就选 index 0，与「默认最新稳定版」口径不一致。
            Object stableRaw = info.get("stable");
            boolean stable = stableRaw == null || Boolean.TRUE.equals(stableRaw);
            out.add(new LoaderVersion(version, stable));
        }
        // 空数组 / 上游 400（LegacyFabric 不支持该 MC 版本）/ 条目都无版本串
        // → 返回空列表（非异常），由 UI 显示「暂无兼容版本」（0.8.8 Q4 S5）。
        if (out.isEmpty()) {
            return List.of();
        }
        // 版本串比大小，不是字典序："0.15.7" 要排在 "0.9.11" 前面（字典序会认反）
        out.sort(NEWEST_FIRST);
        return out;
    }

    /**
     * 元数据型卸载：从 {@code <id>.vanilla.json} 逐字节还原 + 删备份（原逻辑，零改动）。
     */
    private static void uninstallMeta(String versionId, Listener listener) throws IOException {
        Path dir = GameScanner.gameDirFor(versionId);
        Path backup = dir.resolve(versionId + ".vanilla.json");
        if (!Files.isRegularFile(backup)) {
            throw new IOException("找不到原版备份 " + versionId + ".vanilla.json，没法卸载还原；"
                    + "也可以去「游戏管理」把这个版本删掉重装");
        }
        stage(listener, "正在还原原版版本信息…");
        // 逐字节还原：直接拷备份的字节，不重新序列化（验收③「卸载后 JSON 与备份逐字节一致」）。
        // 仍走 tmp+move：写到一半断电不该留下半截 JSON
        byte[] bytes = Files.readAllBytes(backup);
        writeBytesAtomically(dir.resolve("version.json"), bytes);
        writeBytesAtomically(dir.resolve(versionId + ".json"), bytes);
        Files.deleteIfExists(backup);
        System.out.println("[loader] 「" + versionId + "」已从备份还原，备份已删除");
        // libraries/ 里的加载器 jar 不删：多版本共用，留着无害，下次安装可直接命中
        stage(listener, "已还原成原版（加载器库文件留着，下次安装可直接命中）");
    }

    // ================================================================== 安装器型：列表 / URL

    /** Forge 版本列表（BMCLAPI，官方无干净 JSON）：`/forge/minecraft/<mc>`。 */
    private static List<LoaderVersion> versionsForge(String mcVersion) throws IOException {
        List<?> list = getJsonArray(LoaderType.BMCLAPI + "/forge/minecraft/" + mcVersion, "Forge");
        List<LoaderVersion> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            String version = str(entry.get("version"));   // 仅 Forge build，如 "47.2.0"
            if (version == null) {
                continue;
            }
            out.add(new LoaderVersion(version, true));
        }
        if (out.isEmpty()) {
            throw new IOException("「" + mcVersion + "」没有可用的 Forge 版本"
                    + "（这个 MC 版本可能太新或太老）");
        }
        out.sort(NEWEST_FIRST);
        return out;
    }

    /** NeoForge 版本列表（BMCLAPI）：`/neoforge/list/<mc>`，返回 `{version, installerPath, ...}`。 */
    private static List<LoaderVersion> versionsNeoForge(String mcVersion) throws IOException {
        List<?> list = getJsonArray(LoaderType.BMCLAPI + "/neoforge/list/" + mcVersion, "NeoForge");
        List<LoaderVersion> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            // version 已含 MC 前缀（如 "1.20.1-47.1.85"）或纯 raw（如 "21.1.0.1"）
            String version = str(entry.get("version"));
            if (version == null) {
                continue;
            }
            out.add(new LoaderVersion(version, true));
        }
        if (out.isEmpty()) {
            throw new IOException("「" + mcVersion + "」没有可用的 NeoForge 版本");
        }
        out.sort(NEWEST_FIRST);
        return out;
    }

    // ---- OptiFine（v0.8.7 / v1.1 实证）：版本列表 + 下载 URL，全部收口，方便一处替换 ----

    /**
     * OptiFine 版本列表 URL（BMCLAPI）：{@code /optifine/<mc>}。
     *
     * <p>实证（#57）：200 JSON 数组，字段固定 7 个
     * {@code _id / mcversion / type / patch / filename / forge / __v}（无 url）。
     * 要换端点 / 改拼装只改这一个方法。
     */
    private static String optifineVersionsUrl(String mcVersion) {
        return LoaderType.BMCLAPI + "/optifine/" + mcVersion;
    }

    /**
     * OptiFine jar 下载 URL（BMCLAPI）：{@code /optifine/<mc>/<type>/<patch>}。
     *
     * <p>实证（#57）：GET 返回 206 + 302 到带签名重定向；{@code FileFetch} 用
     * {@code HttpClient.Redirect.NORMAL} 跟随、且只把非 2xx 判失败，故 206 天然通过。
     * HEAD 全返 000 → 绝不做 HEAD 探活；多加一段 filename 会 404 → 不拼 filename。
     * 要换拼装只改这一个方法（team-lead 收口要求）。
     *
     * @param loaderVersion {@code <type>/<patch>}（如 {@code HD_U/I5}；preview 为
     *                      {@code HD_U_I6/pre6}）
     */
    private static String optifineDownloadUrl(String mcVersion, String loaderVersion) {
        return LoaderType.BMCLAPI + "/optifine/" + mcVersion + "/" + loaderVersion;
    }

    /**
     * OptiFine 版本列表（BMCLAPI JSON 数组，7 字段已实证固化）。
     *
     * <p><b>字段假设（#57 实证，不再是 TODO）</b>：数组每项
     * <pre>
     * {"_id":"...","mcversion":"1.20.1","patch":"I5","type":"HD_U","__v":0,
     *  "filename":"OptiFine_1.20.1_HD_U_I5.jar","forge":"Forge 47.0.35"}
     * </pre>
     * preview 项形如 {@code type=HD_U_I6, patch=pre6,
     * filename=preview_OptiFine_1.20.1_HD_U_I6_pre6.jar}。
     *
     * <p>版本串统一编码为 {@code <type>/<patch>}（用 '/' 分隔无歧义、自包含）——
     * {@link #optifineDownloadUrl} 直接拿它拼下载 URL，{@code install()} 也不反查列表，
     * 因此 BMCLAPI 挂掉时 UI 可让用户手动输入版本串照样安装（D6 降级）。
     * 按 team-lead 要求取 {@code type / patch / mcversion / filename} 四个字段；
     * {@code forge} 供 UI 版本匹配提示、{@code _id / __v} 实证存在但暂不参与拼装。
     *
     * <p>stable：{@code filename} 以 {@code preview} 开头或 {@code patch} 以 {@code pre}
     * 开头按不稳定，其余按稳定（列表无 stable 字段）。
     *
     * <p>连不上 / 非 2xx / 解析坏 / 非数组 / 空列表一律抛明确 {@link IOException}，
     * <b>不静默返回空列表</b>（team-lead 拍板）。UI catch 后展示「空态 + 手动输入版本串」。
     */
    private static List<LoaderVersion> versionsOptiFine(String mcVersion) throws IOException {
        List<?> list = getJsonArray(optifineVersionsUrl(mcVersion), "OptiFine");
        List<LoaderVersion> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            // 实证 7 字段：_id / mcversion / type / patch / filename / forge / __v（无 url）
            String type = str(entry.get("type"));
            String patch = str(entry.get("patch"));
            String mc = str(entry.get("mcversion"));
            String filename = str(entry.get("filename"));
            if (type == null || patch == null) {
                continue;   // 拼不出版本串的丢了，别往界面塞无名行
            }
            if (mc != null && !mc.equals(mcVersion)) {
                continue;   // 防御：列表串进别的 MC 版本
            }
            boolean preview = (filename != null
                    && filename.toLowerCase(Locale.ROOT).startsWith("preview"))
                    || patch.toLowerCase(Locale.ROOT).startsWith("pre");
            out.add(new LoaderVersion(type + "/" + patch, !preview));
        }
        if (out.isEmpty()) {
            throw new IOException("「" + mcVersion + "」没有可用的 OptiFine 版本"
                    + "（这个 MC 版本可能太新或太老，或 OptiFine 版本接口格式有变）；"
                    + "也可在界面里手动输入版本串（格式 HD_U/I5）");
        }
        out.sort(NEWEST_FIRST);
        return out;
    }

    /** 从 BMCLAPI NeoForge 列表里取出某版本的 installerPath（权威 jar 相对路径）。
     *
     *  <p>保守口径（0.9.1 定稿）：老 MC 版本（如 {@code /neoforge/list/1.20.1}）的列表条目
     *  <b>不带</b> {@code installerPath} 字段，只有 {@code _id/rawVersion/mcversion/version}。
     *  拿不到该字段时这里保持返回 {@code null}、由上层抛可读错误，<b>不</b>据 rawVersion /
     *  version 编造 maven 路径（1.20.1 时代 artifact 名可能是 {@code forge} 而非
     *  {@code neoforge}，未经实证不猜）。 */
    private static String neoforgeInstallerPath(String baseId, String loaderVersion)
            throws IOException {
        List<?> list = getJsonArray(LoaderType.BMCLAPI + "/neoforge/list/" + baseId, "NeoForge");
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            String version = str(entry.get("version"));
            if (!loaderVersion.equals(version)) {
                continue;
            }
            // 1.2.5 F：1.20.4+ 的条目带 installerPath，优先用权威路径。
            String path = str(entry.get("installerPath"));
            if (path != null) {
                return path;
            }
            // 1.2.5 F（根因修复）：实测 1.20.1 的 /neoforge/list 条目只有
            // {_id, rawVersion, mcversion, version}，没有 installerPath；旧代码在此
            // return null，上层直接抛「找不到安装器」，于是 1.20.1 在任何电脑都装不上 NeoForge。
            // 按 BMCLAPI/官方 maven 的真实坐标推导：1.20.1 时代 artifact 是 forge，
            // 1.20.2+ 才是 neoforge（ARCH-0.8 §6 边界，非猜测）。
            String derived = deriveNeoForgeInstallerPath(baseId, version,
                    str(entry.get("rawVersion")));
            if (derived != null) {
                return derived;
            }
        }
        return deriveNeoForgeInstallerPath(baseId, loaderVersion, null);
    }

    /**
     * 1.2.5 F：无 installerPath 时按 mcversion 推导 NeoForge installer 的 maven 相对路径。
     * 纯函数、无网络、无本机路径依赖，供跨平台 QA 直接注入非本机 user.home/分隔符模拟。
     *
     * @param mcVersion   原版 MC 版本（如 {@code "1.20.1"}）
     * @param version     列表 version（如 {@code "47.1.5"} 或 {@code "1.20.1-47.1.5"}）
     * @param rawVersion  列表 rawVersion（如 {@code "1.20.1-47.1.5"}），可为 null
     * @return {@code /maven/...installer.jar}；认不出版本串返回 null（不编造 URL）
     */
    public static String deriveNeoForgeInstallerPath(String mcVersion, String version,
                                                    String rawVersion) {
        if (mcVersion == null || mcVersion.isBlank() || version == null || version.isBlank()) {
            return null;
        }
        boolean legacy = isLegacyNeoForgeMc(mcVersion);
        // 1.20.1 时代的 version 只是 Forge build（47.1.5），必须补 MC 前缀；
        // 1.20.2+ 的 version 已是完整 NeoForge 版本（21.1.1 / 26.3.0.1-beta），原样使用。
        String full;
        if (version.startsWith(mcVersion + "-")) {
            full = version;
        } else if (legacy) {
            full = (rawVersion != null && rawVersion.startsWith(mcVersion + "-"))
                    ? rawVersion : mcVersion + "-" + version;
        } else {
            full = version;
        }
        String artifact = legacy ? "forge" : "neoforge";
        return "/maven/net/neoforged/" + artifact + "/" + full + "/"
                + artifact + "-" + full + "-installer.jar";
    }

    /** 1.20.1 及更早用 net.neoforged:forge；1.20.2 起用 net.neoforged:neoforge。 */
    private static boolean isLegacyNeoForgeMc(String mcVersion) {
        String[] a = mcVersion.split("[.]");
        int major = 0, minor = 0;
        try {
            if (a.length > 0) major = Integer.parseInt(a[a.length - 2]);
            if (a.length > 1) minor = Integer.parseInt(a[a.length - 1]);
        } catch (NumberFormatException ex) {
            return false;
        }
        // 1.20.1 → major=20/minor=1（旧坐标）；1.20.4 → 20/4（新坐标）；1.21.x → 新坐标
        return major < 20 || (major == 20 && minor <= 1);
    }

    /** Forge installer jar 官方 maven 地址。 */
    private static String officialForgeUrl(String full) {
        return "https://maven.minecraftforge.net/net/minecraftforge/forge/" + full
                + "/forge-" + full + "-installer.jar";
    }

    /**
     * NeoForge installer jar 官方地址：releases 根 + installerPath，但要先去掉
     * BMCLAPI 带来的 {@code /maven/} 前缀。
     *
     * <p>实证（0.9.1）：BMCLAPI 的 {@code installerPath} 形如
     * {@code /maven/net/neoforged/neoforge/<v>/neoforge-<v>-installer.jar}，本身带
     * {@code /maven/} 前缀；官方 maven 的 releases 根下<b>没有</b>这一层，直接拼会得到
     * {@code https://maven.neoforged.net/releases/maven/net/neoforged/...}（官方 404）。
     * 正确形态是 {@code https://maven.neoforged.net/releases/net/neoforged/...}。
     */
    private static String officialNeoForgeUrl(String rel) {
        String path = rel.startsWith("/") ? rel : "/" + rel;
        path = path.startsWith("/maven/") ? path.substring("/maven".length()) : path;
        return "https://maven.neoforged.net/releases" + path;
    }

    // ================================================================== 安装器型：运行 installer

    /**
     * 解析无头运行 installer 用的 <b>完整 JRE</b> 的 {@code java} 可执行文件路径。
     *
     * <p>便携包里 {@code java.home} = {@code GBL/runtime/}（jpackage 生成的 11 模块裁剪镜像，
     * <b>没有 java.exe</b>），直接拿它 {@code new ProcessBuilder(...).start()} 会抛
     * {@code CreateProcess error=2, 系统找不到指定的文件}（0.9.1 装 Forge 的报障根因）。
     * 能跑 MC / installer 的完整 JRE 在 {@code GBL/jre/bin/java[.exe]}（39 模块、含 java.se）。
     * 探测顺序与 {@code GameLauncher.findBundledJre()} 一致，<b>刻意不把
     * {@code runtime/bin/java} 当首选</b>：
     * <ol>
     *   <li>{@code <java.home>/../jre/bin/java[.exe]}（jpackage 下 java.home = &lt;appDir&gt;/runtime）；</li>
     *   <li>{@code <user.dir>/jre/bin/java[.exe]}；</li>
     *   <li>（兜底）{@code <java.home>/bin/java[.exe]}，仅当它确实存在——非裁剪镜像场景
     *       下 java.home 本身可能就是完整 JDK/JRE，但仍放最后、绝不作为首选。</li>
     * </ol>
     * 每个候选都要求 {@link Files#isRegularFile} 且 {@link Files#isExecutable}；全部找不到时
     * 抛说人话的 {@link IOException}，<b>不再退回那个注定失败的 runtime 路径</b>。
     *
     * @return 完整 JRE 的 java 可执行文件绝对路径字符串
     * @throws IOException 三处候选都不可用（便携包不完整），message 说人话、提示检查便携包
     */
    private static String installerJavaExe() throws IOException {
        String exe = isWindows() ? "java.exe" : "java";
        List<Path> probes = new ArrayList<>();
        String home = System.getProperty("java.home", "");
        if (!home.isBlank()) {
            Path parent = Paths.get(home).toAbsolutePath().normalize().getParent();
            if (parent != null) {
                probes.add(parent.resolve("jre").resolve("bin").resolve(exe));
            }
        }
        probes.add(Paths.get(System.getProperty("user.dir", ""))
                .toAbsolutePath().normalize().resolve("jre").resolve("bin").resolve(exe));
        // 1.2.5 F：非本机 user.dir / 不同路径分隔符场景，按便携根再探一次。
        probes.add(Platform.appDir().resolve("jre").resolve("bin").resolve(exe));
        if (!home.isBlank()) {
            // 兜底：java.home/bin/java 仅当确实存在（如开发机上的完整 JDK）。便携包的
            // runtime 是裁剪镜像、没有 java.exe，此候选会被 isRegularFile 挡掉。
            probes.add(Paths.get(home).toAbsolutePath().normalize().resolve("bin").resolve(exe));
        }
        for (Path probe : probes) {
            try {
                Path p = probe.toAbsolutePath().normalize();
                if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                    return p.toString();
                }
            } catch (RuntimeException ignored) {
                // 非法路径片段 / 权限问题：跳过该候选
            }
        }
        throw new IOException("找不到可运行安装器的 Java（便携包 GBL/jre/bin/" + exe
                + " 缺失或不可执行），请检查便携包是否完整、或重新安装 GBL");
    }

    /**
     * 无头运行 <b>Forge / NeoForge</b> installer jar：
     * {@code java -Djava.awt.headless=true -jar <jar> --installClient <mcDir>}。
     *
     * <p>{@code --installClient} 是 Forge installer 的专属参数，<b>对 OptiFine 无效</b>
     * （#57 实证 jar 内扫不到该字符串、实跑 exit 1 {@code HeadlessException} 零产出）。
     * OptiFine 走 {@link #installOptiFine} 自写 profile，绝不进本方法；下面保留一道防御。
     * 本方法只服务 Forge/NeoForge，故这处 {@code --installClient} 是有意保留的——
     * 删掉会破坏 Forge/NeoForge 安装。
     *
     * <p>{@code java} 由 {@link #installerJavaExe()} 解析便携包自带的<b>完整 JRE</b>
     * （{@code GBL/jre/bin/java[.exe]}，39 模块含 java.se），<b>不再</b>取自
     * {@code java.home/bin/java} —— 后者在便携包里是 11 模块裁剪镜像、没有 java.exe
     * （0.9.1 装 Forge 报 CreateProcess error=2 的根因）。
     * 实时把 stdout/stderr 转发给 {@code listener}（前 200 行，避免刷屏），进程非 0 退出时把末
     * 15 行拼进异常 message。最多等 10 分钟（30s × 20 次），超时强杀。
     */
    private static void runInstallerJar(Path jar, LoaderType type, Listener listener)
            throws IOException {
        if (type == LoaderType.OPTIFINE) {
            throw new IOException("OptiFine 不能用无头 installer 安装（--installClient 无效），"
                    + "应走 installOptiFine 自写派生 profile");
        }
        // 用完整 JRE（GBL/jre/bin），别用裁剪镜像 runtime/bin/java（无 java.exe）。
        String javaExe = installerJavaExe();

        List<String> command = new ArrayList<>();
        command.add(javaExe);
        command.add("-Djava.awt.headless=true");
        command.add("-jar");
        command.add(jar.toAbsolutePath().toString());
        command.add("--installClient");
        command.add(Platform.minecraftDir().toAbsolutePath().toString());

        stage(listener, "正在运行 " + type.label()
                + " 安装器（可能要联网拉依赖，请稍候）…");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // 后台读输出：转发前 200 行给界面，并保留末 200 行供失败时报错
        List<String> tail = new ArrayList<>();
        AtomicInteger forwarded = new AtomicInteger(0);
        Thread reader = new Thread(() -> {
            try (var br = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    synchronized (tail) {
                        tail.add(line);
                        if (tail.size() > 200) {
                            tail.remove(0);
                        }
                    }
                    if (forwarded.incrementAndGet() <= 200) {
                        forwardInstallerLine(listener, line);
                    }
                }
            } catch (IOException ignored) {
                // 进程被打断或流已关，忽略
            }
        });
        reader.setDaemon(true);
        reader.start();

        boolean finished = false;
        for (int i = 0; i < 20; i++) {
            try {
                if (process.waitFor(30, TimeUnit.SECONDS)) {
                    finished = true;
                    break;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new IOException("安装器运行被中断");
            }
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("「" + type.label() + "」安装器运行超时（超过 10 分钟），"
                    + "可能是依赖下载太慢，稍后重试");
        }
        try {
            reader.join(2000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }

        int code = process.exitValue();
        if (code != 0) {
            StringBuilder sb = new StringBuilder();
            synchronized (tail) {
                int from = Math.max(0, tail.size() - 15);
                for (int i = from; i < tail.size(); i++) {
                    sb.append(tail.get(i)).append('\n');
                }
            }
            throw new IOException("Forge/NeoForge 安装器失败（退出码 " + code + "）：\n" + sb);
        }
    }

    /**
     * 给 Forge/NeoForge 安装器备好官方 launcher 的 profiles 注册表。
     *
     * <p>安装器用 {@code MinecraftLauncherProfiles}（即 {@code launcher_profiles.json}）找原版 profile，
     * 读不到就中止。GBL 自己从不管这个文件，这里只是临时喂给安装器：已有的 profiles 保留、
     * 把 base 原版版本登记进去即可。GBL 用户通常没有这个文件，首次会新建一个极简结构。
     */
    private static void ensureLauncherProfiles(String baseId) throws IOException {
        Path file = Platform.minecraftDir().resolve("launcher_profiles.json");
        Map<String, Object> root;
        if (Files.isRegularFile(file)) {
            try {
                Object parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
                root = parsed instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : null;
            } catch (IOException | RuntimeException ex) {
                root = null;   // 坏的就重建
            }
        } else {
            root = null;
        }
        if (root == null) {
            root = new LinkedHashMap<>();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> profiles = root.get("profiles") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : new LinkedHashMap<>();
        Map<String, Object> prof = new LinkedHashMap<>();
        prof.put("id", baseId);
        prof.put("type", "release");
        prof.put("lastVersionId", baseId);
        profiles.put(baseId, prof);
        root.put("profiles", profiles);
        root.put("selectedProfile", baseId);
        try {
            Files.writeString(file, Json.write(root), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            // 写不出注册表就别卡安装：装完若真连原版都认不出，用户重装原版即可
            System.err.println("[loader] 写 launcher_profiles.json 失败（安装器可能认不出原版）: " + ex);
        }
    }

    /** 转发 installer 一行输出：打日志 + 送 listener（listener 为 null 时只打日志）。 */
    private static void forwardInstallerLine(Listener listener, String line) {
        String text = "Forge 安装器：" + line;
        System.out.println("[loader] " + text);
        if (listener != null) {
            listener.onStage(text);
        }
    }

    /** 取 installer 用的 java 可执行文件名（按当前 OS）。 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    // ================================================================== 元数据型：合并细节（原逻辑，零改动）

    /** 对完整版深拷贝出 merged，逐项合并（架构 §1.3 第 5 步）。 */
    private static Map<String, Object> merge(Map<String, Object> full, LoaderType type,
                                            String versionId, String loaderVersion,
                                            Map<String, Object> meta,
                                            Map<?, ?> launcherMeta,
                                            Map<String, Object> local) {
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = (Map<String, Object>) deep(full);

        // 5.1 mainClass 覆盖：字符串或 {"client": "..."} 对象都认（蓝本第 2 节 A 类）
        Object mainClass = launcherMeta.get("mainClass");
        if (mainClass instanceof Map<?, ?> mc) {
            Object client = mc.get("client");
            if (client instanceof String s && !s.isBlank()) {
                merged.put("mainClass", s);
            }
        } else if (mainClass instanceof String s && !s.isBlank()) {
            merged.put("mainClass", s);
        }

        // 5.2 libraries 去重追加：以 downloads.artifact.path 为键（无 path 用 maven name）
        List<Object> libs = new ArrayList<>();
        if (merged.get("libraries") instanceof List<?> existing) {
            libs.addAll(existing);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Object raw : libs) {
            String key = libraryKey(raw);
            if (key != null) {
                seen.add(key);
            }
        }
        if (launcherMeta.get("libraries") instanceof Map<?, ?> groups) {
            // common + server 是蓝本口径；client 一并收是防御性并集（有的加载器把库分在三边上）
            for (String side : new String[]{"common", "server", "client"}) {
                if (!(groups.get(side) instanceof List<?> items)) {
                    continue;
                }
                for (Object item : items) {
                    Map<String, Object> lib = toLibrary(type, item);
                    if (lib == null) {
                        continue;
                    }
                    String key = libraryKey(lib);
                    if (key != null && seen.add(key)) {
                        libs.add(lib);
                    }
                }
            }
        }
        // 显式追加 intermediary + loader 两个 maven 库（PRD 规则②、对齐 HMCL FabricInstallTask）：
        // launcherMeta 里一般已经带了，这里是防御性并集——按 path 去重，带了也不会重复收
        for (String coordinate : new String[]{mavenOf(meta.get("intermediary")),
                mavenOf(meta.get("loader"))}) {
            if (coordinate == null) {
                continue;
            }
            // Quilt 的 intermediary 在 fabricmc 仓库、loader 在 quilt 仓库：按组选仓库
            Map<String, Object> lib = toLibrary(type, coordinate, mavenBaseFor(coordinate, type));
            String key = libraryKey(lib);
            if (key != null && seen.add(key)) {
                libs.add(lib);
            }
        }
        merged.put("libraries", libs);

        // 5.3 游戏参数追加（仅 launchwrapper 型加载器需要；现代 Fabric/Quilt 不触发，逻辑保留）
        String tweaker = clientTweaker(launcherMeta);
        if (tweaker != null) {
            appendTweakClass(merged, tweaker);
        }

        // 5.4 gblLoader 标记：卸载 / 状态识别认它
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("type", type.id());
        marker.put("loaderVersion", loaderVersion);
        marker.put("minecraftVersion", versionId);
        marker.put("installedAt", Instant.now().toString());   // ISO-8601 UTC
        merged.put("gblLoader", marker);

        // 5.5 元信息字段保全：本地精简版里的 type / jar / time，merged 没有就补上。
        // 官方完整版 JSON 没有 type，不补 GameScanner.describe 会把类型退化成「正式版」。
        // local 由调用方读好传进来（只读本地，不触网）
        if (local != null) {
            for (String key : new String[]{"type", "jar", "time"}) {
                if (!merged.containsKey(key) && local.get(key) != null) {
                    merged.put(key, local.get(key));
                }
            }
        }
        return merged;
    }

    /**
     * maven 坐标 → version.json 的 library 条目。
     *
     * <p>{@code g:a:v} → {@code g/a/v/a-v.jar}；{@code g:a:v:c} → {@code g/a/v/a-v-c.jar}。
     * sha1 空串、size 0：Fabric/Quilt 的 launcherMeta 不给哈希，{@link LibFetcher#downloadOne}
     * 对空 sha1 走「不校验」分支（PRD U3 接受）。
     */
    private static Map<String, Object> toLibrary(LoaderType type, String coordinate, String url) {
        String path = mavenPath(coordinate);
        if (path == null) {
            return null;
        }
        String base = (url == null || url.isBlank()) ? type.maven() : url;
        if (!base.endsWith("/")) {
            base = base + "/";
        }
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("path", path);
        artifact.put("url", base + path);
        artifact.put("sha1", "");
        artifact.put("size", 0L);
        Map<String, Object> downloads = new LinkedHashMap<>();
        downloads.put("artifact", artifact);
        Map<String, Object> lib = new LinkedHashMap<>();
        lib.put("name", coordinate);
        lib.put("downloads", downloads);
        return lib;
    }

    /** launcherMeta.libraries 的条目：maven 坐标串，或 {"name": ..., "url": ...} 对象都认。 */
    private static Map<String, Object> toLibrary(LoaderType type, Object item) {
        if (item instanceof String coordinate) {
            return toLibrary(type, coordinate, null);
        }
        if (item instanceof Map<?, ?> m) {
            String name = str(m.get("name"));
            return name == null ? null : toLibrary(type, name, str(m.get("url")));
        }
        return null;
    }

    /** maven 组 → 仓库基地址：Quilt 的 intermediary 在 fabricmc 仓库，不能一律用 LoaderType.maven。 */
    private static String mavenBaseFor(String coordinate, LoaderType type) {
        String group = coordinate.contains(":")
                ? coordinate.substring(0, coordinate.indexOf(':')) : "";
        if (group.startsWith("net.fabricmc")) {
            // BMCLAPI 镜像了 fabric maven（/maven 前缀，302→CDN）；GFW 下直连 maven.fabricmc.net
            // 常慢/不通，国内优先镜像，回落官方（与 Forge/NeoForge 主备口径一致）。
            return Mirror.enabled() ? LoaderType.BMCLAPI + "/maven/" : "https://maven.fabricmc.net/";
        }
        if (group.startsWith("org.quiltmc")) {
            return "https://maven.quiltmc.org/repository/release/";
        }
        if (group.startsWith("net.legacyfabric")) {
            return "https://maven.legacyfabric.net/";
        }
        return type.maven();
    }

    /** {@code g:a:v[:c]} → artifact 相对路径；认不出（段数不够）返回 null。 */
    private static String mavenPath(String coordinate) {
        String[] parts = coordinate.split(":");
        if (parts.length < 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            return null;
        }
        String group = parts[0].replace('.', '/');
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length >= 4 && !parts[3].isBlank() ? parts[3] : null;
        String file = artifact + "-" + version + (classifier == null ? "" : "-" + classifier) + ".jar";
        return group + "/" + artifact + "/" + version + "/" + file;
    }

    /** meta 顶层 {@code intermediary} / {@code loader} 对象里的 maven 坐标。 */
    private static String mavenOf(Object raw) {
        return raw instanceof Map<?, ?> m ? str(m.get("maven")) : null;
    }

    /** 去重键：{@code downloads.artifact.path} 优先，没有用 maven {@code name}（架构 §1.3）。 */
    private static String libraryKey(Object raw) {
        if (!(raw instanceof Map<?, ?> lib)) {
            return null;
        }
        if (lib.get("downloads") instanceof Map<?, ?> dl
                && dl.get("artifact") instanceof Map<?, ?> art
                && art.get("path") instanceof String path && !path.isBlank()) {
            return "path:" + path;
        }
        return lib.get("name") instanceof String name && !name.isBlank() ? "name:" + name : null;
    }

    /** {@code launcherMeta.launchwrapper.tweakers.client[0]}；没有返回 null（现代加载器都没这段）。 */
    private static String clientTweaker(Map<?, ?> launcherMeta) {
        if (!(launcherMeta.get("launchwrapper") instanceof Map<?, ?> lw)
                || !(lw.get("tweakers") instanceof Map<?, ?> tweakers)
                || !(tweakers.get("client") instanceof List<?> client)
                || client.isEmpty()
                || !(client.get(0) instanceof String tweaker)) {
            return null;
        }
        return tweaker.isBlank() ? null : tweaker;
    }

    /**
     * 追加 {@code --tweakClass <tweaker>}：新老两种 arguments 格式都处理。
     *
     * <p>{@code arguments.game} 是数组 → 末尾追加；否则 {@code minecraftArguments} 是字符串 →
     * 末尾追加；两者都没有 → 新建 {@code arguments: {game: [...]}}（arguments 已存在但缺 game 时
     * 往里补 game，不覆盖——覆盖会把 jvm 段弄丢）。
     * {@link GameLauncher} 的展开对裸字符串原样放过，两种格式都通。
     */
    private static void appendTweakClass(Map<String, Object> merged, String tweaker) {
        if (merged.get("arguments") instanceof Map<?, ?> args) {
            @SuppressWarnings("unchecked")
            Map<String, Object> argMap = (Map<String, Object>) args;
            List<Object> game = new ArrayList<>();
            if (argMap.get("game") instanceof List<?> existing) {
                game.addAll(existing);
            }
            game.add("--tweakClass");
            game.add(tweaker);
            argMap.put("game", game);
            return;
        }
        if (merged.get("minecraftArguments") instanceof String text) {
            merged.put("minecraftArguments", text + " --tweakClass " + tweaker);
            return;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("game", new ArrayList<>(List.of("--tweakClass", tweaker)));
        merged.put("arguments", args);
    }

    // ------------------------------------------------------------------ 网络 / 工具

    /** 拉某加载器某版本的完整元信息：{@code {loader, intermediary, launcherMeta}}。 */
    private static Map<String, Object> fetchLoaderMeta(LoaderType type, String mcVersion,
                                                      String loaderVersion) throws IOException {
        String text = fetchMetaText(type, mcVersion, loaderVersion);
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (RuntimeException ex) {
            throw new IOException(unreadableMessage(type));
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IOException(unreadableMessage(type));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) map;
        return out;
    }

    /** 发一次 GET 拿文本；非 2xx / 网络错误抛 {@link IOException}（message 直接可显示）。 */
    private static String getText(String url, String what) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", GameDownloader.AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException(what + " 的版本接口暂时连不上（HTTP "
                        + response.statusCode() + "），稍后重试或检查网络");
            }
            return response.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("拉取 " + what + " 版本信息被中断");
        }
    }

    /**
     * 拉元数据型加载器的版本列表 / 单版本元信息（0.8.8 Q4）。
     *
     * <p><b>去门控</b>：meta 域（Fabric / Quilt / LegacyFabric）恒走「镜像优先 → 官方回落」，
     * <b>不受 {@link Mirror#enabled()} 控制</b>——下载源开关语义是「Mojang 资源下载源」，
     * 不该决定加载器 meta 域。官方直连慢 / SSL 失败会让加载器 tab 恒空白（B6），镜像优先可解。
     *
     * <p><b>列表 / 单版本分派（按 {@code loaderVersion == null}，见 §4.2）</b>：
     * <ul>
     *   <li>{@code loaderVersion == null}（版本列表）：primary = {@link #metaUrlMirror}
     *       （FABRIC 带 mc / QUILT 全局不带 mc / LEGACY 无镜像），失败回落官方
     *       （QUILT 回落「全局不带 mc」官方，其余回落官方带 mc）。都失败抛带「检查网络 / 换下载源」
     *       提示的 {@link IOException}；上游 HTTP 400（LegacyFabric 不支持该 MC 版本）当作空列表返回。</li>
     *   <li>{@code loaderVersion != null}（单版本 launcherMeta，安装期）：<b>一律</b>官方带 mc 路径
     *       {@code type.metaUrl(mcVersion) + "/" + loaderVersion}。⛔ Quilt 的全局列表 URL 绝不能
     *       用于单版本元信息，否则「列表能出来、一点安装就失败」（R-2）。</li>
     *   <li>安装器型（{@link LoaderType#isInstaller()}）由 {@link #versions} 分流到
     *       versionsForge / versionsNeoForge / versionsOptiFine，不进本 meta 分支。</li>
     * </ul>
     *
     * @param loaderVersion 非空时拉单版本完整元信息；null 时拉版本列表
     */
    private static String fetchMetaText(LoaderType type, String mcVersion, String loaderVersion)
            throws IOException {
        if (!type.isMeta()) {
            // 安装器型走 versionsForge/NeoForge/OptiFine，不拉取 meta（metaBase 为 null）；
            // 到达这里说明调用串味，明确报错而不是拼出坏 URL。
            throw new IOException(type.label() + " 不是元数据型加载器，不拉取 meta 版本信息");
        }

        // 单版本 launcherMeta（安装期）：按 type 分派（0.8.8 Q4 追加修正）。
        // FABRIC 的 BMCLAPI 支持带 mc 的单版本路径（实测 200/0.216s，官方 3.558s），
        // 故与列表一样镜像优先 + ≤3s 短超时、失败回落官方（0.8.7 既有口径，勿再退化）；
        // QUILT（BMCLAPI 带 mc 404）/ LEGACY_FABRIC（无镜像）一律官方带 mc，零改动。
        // ⛔ Quilt 的全局列表 URL 绝不用于单版本（R-2）。
        // 镜像请求复用 getMetaListText（本质是带超时 + 400 容忍的 meta 专用 GET，非仅列表）。
        if (loaderVersion != null) {
            String official = type.metaUrl(mcVersion) + "/" + loaderVersion;
            if (type != LoaderType.FABRIC) {
                return getText(official, type.label());
            }
            String fabricMirror = LoaderType.BMCLAPI
                    + "/fabric-meta/v2/versions/loader/" + mcVersion + "/" + loaderVersion;
            try {
                return getMetaListText(fabricMirror, type.label(), META_LIST_MIRROR_TIMEOUT, false);
            } catch (IOException ex) {
                System.err.println("[loader] " + type.label() + " 单版本镜像拉取失败，回落官方源: "
                        + ex.getMessage());
                try {
                    return getText(official, type.label());
                } catch (IOException ex2) {
                    throw new IOException(type.label()
                            + " 单版本元信息拉取失败，请检查网络或切换下载源后重试（"
                            + ex2.getMessage() + "）", ex2);
                }
            }
        }

        // 版本列表：镜像优先 → 官方回落（不受下载源开关控制）。
        String mirror = metaUrlMirror(type, mcVersion, null);
        String official = type == LoaderType.QUILT
                ? "https://meta.quiltmc.org/v3/versions/loader"   // 全局、不带 mc
                : type.metaUrl(mcVersion);                        // 官方带 mc

        if (mirror == null) {
            // LEGACY_FABRIC 无镜像：直接官方；官方 400 = 该 MC 版本不支持 → 空列表（非异常）。
            return getMetaListText(official, type.label(), META_LIST_OFFICIAL_TIMEOUT, true);
        }
        try {
            return getMetaListText(mirror, type.label(), META_LIST_MIRROR_TIMEOUT, false);
        } catch (IOException ex) {
            System.err.println("[loader] " + type.label() + " 镜像拉取失败，回落官方源: "
                    + ex.getMessage());
            try {
                return getMetaListText(official, type.label(), META_LIST_OFFICIAL_TIMEOUT, true);
            } catch (IOException ex2) {
                throw new IOException(type.label()
                        + " 版本列表拉取失败，请检查网络或切换下载源后重试（" + ex2.getMessage() + "）", ex2);
            }
        }
    }

    /**
     * 元数据型「版本列表」的 BMCLAPI 镜像主源 URL（三类 URL 策略表，0.8.8 Q4 S3）。
     *
     * <p>本表<b>只服务版本列表</b>；单版本 launcherMeta 由 {@link #fetchMetaText} 直接走官方带 mc
     * 路径，不调用本方法。{@code loaderVersion} 参数保留以固化签名，但策略表恒按列表处理。
     *
     * <ul>
     *   <li>FABRIC：{@code /fabric-meta/v2/versions/loader/<mc>}（带 mc，嵌套结构）。</li>
     *   <li>QUILT：{@code /quilt-meta/v3/versions/loader}（<b>全局、不带 mc</b>；带 mc 的 404，
     *       返回扁平结构，由 versionsMeta 兼容）。</li>
     *   <li>LEGACY_FABRIC：无镜像 → 返回 null，直接走官方。</li>
     *   <li>FORGE / NEOFORGE / OPTIFINE：安装器型，不走 meta 镜像列表 → null。</li>
     * </ul>
     */
    private static String metaUrlMirror(LoaderType type, String mcVersion, String loaderVersion) {
        return switch (type) {
            case FABRIC -> LoaderType.BMCLAPI + "/fabric-meta/v2/versions/loader/" + mcVersion;
            case QUILT -> LoaderType.BMCLAPI + "/quilt-meta/v3/versions/loader";
            case LEGACY_FABRIC -> null;
            case FORGE, NEOFORGE, OPTIFINE -> null;
        };
    }

    /**
     * 元数据型「版本列表」专用 GET：与 {@link #getText} 同口径，但 ① 用列表专用超时；
     * ② {@code tolerate400} 为真时把 HTTP 400 当作「该 MC 版本不支持」返回 {@code "[]"}
     * （LegacyFabric 官方对 1.20.1 / 26.3 返回 400 + []，属正常结果而非网络错误），
     * 由 {@link #versionsMeta} 解析成空列表、UI 显示「暂无兼容版本」。
     *
     * <p>不改 {@link #getText} 通用签名；镜像主源传 {@code tolerate400=false}（400 视为失败回落），
     * 官方回落传 {@code true}（400 视为空列表）。
     */
    private static String getMetaListText(String url, String what, Duration timeout,
                                          boolean tolerate400) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", GameDownloader.AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int code = response.statusCode();
            if (code == 400 && tolerate400) {
                return "[]";
            }
            if (code < 200 || code >= 300) {
                throw new IOException(what + " 的版本接口暂时连不上（HTTP "
                        + code + "），稍后重试或检查网络");
            }
            return response.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("拉取 " + what + " 版本信息被中断");
        }
    }

    /** 拉一份 JSON 数组（版本列表用）；非 2xx / 解析坏 / 不是数组都抛 {@link IOException}。 */
    private static List<?> getJsonArray(String url, String what) throws IOException {
        String text = getText(url, what);
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (RuntimeException ex) {
            throw new IOException("「" + what + "」的版本接口返回认不出来，稍后重试或检查网络");
        }
        if (!(parsed instanceof List<?> list)) {
            throw new IOException("「" + what + "」的版本接口返回格式不对，稍后重试或检查网络");
        }
        return list;
    }

    /** 接口返回认不出来时的那句人话（带上域名，用户好排查）。 */
    private static String unreadableMessage(LoaderType type) {
        return type.label() + " 的版本接口返回了一大段认不出来的东西（"
                + hostOf(type.metaBase()) + "），稍后重试或检查网络";
    }

    private static String hostOf(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost() == null ? url : uri.getHost();
        } catch (RuntimeException ex) {
            return url;
        }
    }

    /** 深拷贝一份 JSON 值（Map / List 递归，标量原样）。合并只在拷贝上动手，不动原始数据。 */
    private static Object deep(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), deep(e.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(deep(item));
            }
            return out;
        }
        return value;
    }

    /** 精简版判据：libraries / assetIndex 缺一个就是补不齐（和 {@link VersionJson} 内部同口径）。 */
    private static boolean isThin(Map<String, Object> map) {
        return !(map.containsKey("libraries") && map.get("libraries") != null)
                || !(map.containsKey("assetIndex") && map.get("assetIndex") != null);
    }

    /**
     * {@code gblLoader.type} → 枚举：遍历 {@link LoaderType#values()}，
     * 新增 OPTIFINE 后 {@code "optifine"} 天然被认出（无需在此硬编码映射，
     * 否则 status()/UI 会显示「原版」）。认不出来（手改坏 / 未知标记）返回 null。
     */
    private static LoaderType byId(String id) {
        if (id == null) {
            return null;
        }
        String want = id.trim().toLowerCase(Locale.ROOT);
        for (LoaderType type : LoaderType.values()) {
            if (type.id().equals(want)) {
                return type;
            }
        }
        return null;
    }

    /** 去掉前缀；去掉后为空或没前缀就退回 fallback（解析不出版本号时给整段原名）。 */
    private static String stripPrefix(String name, String prefix, String fallback) {
        if (name.startsWith(prefix)) {
            String rest = name.substring(prefix.length());
            return rest.isEmpty() ? fallback : rest;
        }
        return fallback;
    }

    /** 单文件 tmp + move：内容是一次性写好的，改名这一步不会写出半截。 */
    private static void writeBytesAtomically(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException ex) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException | RuntimeException ignored) {
                // 删不掉就留着，下次写同名覆盖
            }
            throw ex;
        }
    }

    private static void stage(Listener listener, String text) {
        System.out.println("[loader] " + text);
        if (listener != null) {
            listener.onStage(text);
        }
    }

    private static String str(Object raw) {
        return raw instanceof String text && !text.isBlank() ? text : null;
    }

    /** 版本串比大小：按 {@code . _ -} 切段，数字段按数值比（长串先比长度再比值，别 int 溢出）。 */
    private static final Comparator<LoaderVersion> NEWEST_FIRST =
            (a, b) -> compareVersions(b.version(), a.version());

    private static int compareVersions(String x, String y) {
        String[] xs = x.split("[._-]");
        String[] ys = y.split("[._-]");
        int n = Math.max(xs.length, ys.length);
        for (int i = 0; i < n; i++) {
            int c = compareChunk(i < xs.length ? xs[i] : "", i < ys.length ? ys[i] : "");
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static int compareChunk(String x, String y) {
        boolean xNum = x.matches("\\d+");
        boolean yNum = y.matches("\\d+");
        if (xNum && yNum) {
            String a = trimLeadingZeros(x);
            String b = trimLeadingZeros(y);
            if (a.length() != b.length()) {
                return Integer.compare(a.length(), b.length());
            }
            return a.compareTo(b);
        }
        if (xNum != yNum) {
            return xNum ? 1 : -1;   // 数字段比字母段大：1.0.0 排在 1.0.0-beta 前
        }
        return x.compareTo(y);
    }

    private static String trimLeadingZeros(String s) {
        int i = 0;
        while (i < s.length() - 1 && s.charAt(i) == '0') {
            i++;
        }
        return s.substring(i);
    }
}
