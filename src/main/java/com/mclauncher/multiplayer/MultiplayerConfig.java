package com.mclauncher.multiplayer;

import com.mclauncher.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多人联机独立配置（1.1.4）。刻意不塞进 {@code SettingsStore}：那份服务主程序、
 * 这份只服务联机引擎，互不牵连——联机配置读写失败不能拖累启动器主配置。
 *
 * <p>落盘 {@code ~/.gbl/multiplayer.json}。读取宽容降级：文件不存在 / 坏 JSON /
 * 字段缺失或类型不对，一律按默认值处理，绝不抛异常打断界面。
 *
 * <p>不可变：改一项走 {@code withXxx} 拿新实例，再调 {@link #save()} 落盘。
 */
public final class MultiplayerConfig {

    /** 配置文件路径（用户 home 目录下）。 */
    private static final Path FILE =
            Paths.get(System.getProperty("user.home"), ".gbl", "multiplayer.json");

    /**
     * 用户手动填写的附加公共节点，默认空表——表示不附加，完全使用 Terracotta 引擎内置节点。
     * 内置节点由 Terracotta 自己维护、官方不列出具体地址，本类刻意不硬编码任何域名或预设列表。
     * 配置里只存用户填的附加节点，拼 HTTP 请求时按重复键 {@code public_nodes=...} 逐个传入。
     */
    private final List<String> customPublicNodes;
    /** 是否优先 IPv6（默认关）。 */
    private final boolean preferIPv6;
    /** 启动器启动时自动拉起联机引擎（P2 预留，默认关；当前版本编排层可忽略）。 */
    private final boolean autoStartOnLaunch;

    /** {@link #get()} 缓存的当前实例（懒加载）。 */
    private static volatile MultiplayerConfig instance;

    private MultiplayerConfig(List<String> customPublicNodes, boolean preferIPv6, boolean autoStartOnLaunch) {
        this.customPublicNodes = customPublicNodes == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(customPublicNodes));
        this.preferIPv6 = preferIPv6;
        this.autoStartOnLaunch = autoStartOnLaunch;
    }

    /** 出厂设置：无自定义节点 / 不优先 IPv6 / 不自动启动。 */
    public static MultiplayerConfig defaults() {
        return new MultiplayerConfig(List.of(), false, false);
    }

    /** 单例出口：首次调用落一份并缓存，配置变更走 {@code withXxx} + {@link #save()}。 */
    public static MultiplayerConfig get() {
        MultiplayerConfig current = instance;
        if (current == null) {
            synchronized (MultiplayerConfig.class) {
                current = instance;
                if (current == null) {
                    instance = current = load();
                }
            }
        }
        return current;
    }

    /** 读配置。文件不存在 / 读不到 / 坏 JSON / 字段异常，全部返回默认值，绝不抛。 */
    public static MultiplayerConfig load() {
        Map<String, Object> map = readObject();
        if (map == null) {
            return defaults();
        }
        return new MultiplayerConfig(
                parseNodes(map.get("customPublicNodes")),
                parseBool(map, "preferIPv6"),
                parseBool(map, "autoStartOnLaunch"));
    }

    public List<String> customPublicNodes() {
        return customPublicNodes;
    }

    public boolean preferIPv6() {
        return preferIPv6;
    }

    public boolean autoStartOnLaunch() {
        return autoStartOnLaunch;
    }

    /** 换自定义节点列表。null 按空表处理；同值不换新实例。 */
    public MultiplayerConfig withCustomPublicNodes(List<String> nodes) {
        List<String> want = new ArrayList<>();
        if (nodes != null) {
            for (String node : nodes) {
                if (node != null && !node.isBlank()) {
                    want.add(node.trim());
                }
            }
        }
        return want.equals(customPublicNodes) ? this
                : new MultiplayerConfig(want, preferIPv6, autoStartOnLaunch);
    }

    /** 换 IPv6 偏好。同值不换新实例。 */
    public MultiplayerConfig withPreferIPv6(boolean on) {
        return on == preferIPv6 ? this
                : new MultiplayerConfig(customPublicNodes, on, autoStartOnLaunch);
    }

    /** 换自动启动开关。同值不换新实例。 */
    public MultiplayerConfig withAutoStartOnLaunch(boolean on) {
        return on == autoStartOnLaunch ? this
                : new MultiplayerConfig(customPublicNodes, preferIPv6, on);
    }

    /** 写回磁盘。写临时文件再整体改名，避免留下半截 JSON。 */
    public void save() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("customPublicNodes", customPublicNodes);
        map.put("preferIPv6", preferIPv6);
        map.put("autoStartOnLaunch", autoStartOnLaunch);
        String text = Json.write(map);
        Path dir = FILE.toAbsolutePath().getParent();
        try {
            if (dir != null) {
                Files.createDirectories(dir);
            }
        } catch (IOException | RuntimeException ex) {
            System.err.println("[multiplayer-cfg] 建配置目录失败: " + ex);
            return;
        }
        Path tmp = dir.resolve(FILE.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, FILE, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException fallback) {
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
            // save 成功后刷新单例，get() 立即拿到最新配置
            instance = this;
        } catch (IOException | RuntimeException ex) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException | RuntimeException ignored) {
                // 删不掉就留着，下次写同名覆盖
            }
            System.err.println("[multiplayer-cfg] 保存失败: " + ex);
        }
    }

    // ---------------------------------------------------------------- 解析

    private static boolean parseBool(Map<String, Object> map, String key) {
        Object raw = map.get(key);
        return raw instanceof Boolean ? (Boolean) raw : false;
    }

    /** 节点数组：只收字符串元素，缺字段 / 类型不对 → 空表。 */
    private static List<String> parseNodes(Object raw) {
        List<String> result = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) {
                    result.add(s.trim());
                }
            }
        }
        return result;
    }

    /** 读一份顶层 JSON 对象；读不到或不是对象就返回 null（调用方降级默认值）。 */
    private static Map<String, Object> readObject() {
        String text;
        try {
            if (!Files.isRegularFile(FILE)) {
                return new LinkedHashMap<>();
            }
            text = Files.readString(FILE, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException ex) {
            System.err.println("[multiplayer-cfg] 读取失败，按默认值兜: " + ex);
            return null;
        }
        try {
            Object parsed = Json.parse(text);
            if (parsed instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) parsed;
                return map;
            }
            return null;
        } catch (RuntimeException ex) {
            System.err.println("[multiplayer-cfg] 配置不是合法 JSON，按默认值兜: " + ex);
            return null;
        }
    }
}
